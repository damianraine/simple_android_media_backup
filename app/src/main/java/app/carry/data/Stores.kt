package app.carry.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.carry.core.model.CarrySettings
import app.carry.core.model.SecretBook
import app.carry.core.store.SecretCodec
import app.carry.core.store.SettingsCodec
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private val Context.settingsDataStore by preferencesDataStore("carry_settings")

class SettingsStore(context: Context, scope: CoroutineScope) {
    private val data = context.applicationContext.settingsDataStore
    private val key = stringPreferencesKey("json")
    val settings: StateFlow<CarrySettings> = data.data
        .map { SettingsCodec.decode(it[key]) }
        .stateIn(scope, SharingStarted.Eagerly, CarrySettings())

    suspend fun read(): CarrySettings = SettingsCodec.decode(data.data.first()[key])

    suspend fun update(block: (CarrySettings) -> CarrySettings) {
        data.edit { prefs ->
            prefs[key] = SettingsCodec.encode(block(SettingsCodec.decode(prefs[key])))
        }
    }
}

/**
 * Passwords stay in the Android keystore. The file is private to the app and backup is off,
 * so an update signed with the same key keeps them and a different key cannot read them.
 */
class VaultStore(context: Context) {
    private val file = File(context.filesDir, "vault.bin")
    private val lock = Any()
    private var book: SecretBook = readFile()

    fun password(id: String): String? = synchronized(lock) { book.passwords[id] }

    fun setPassword(id: String, password: String) = synchronized(lock) {
        book = book.copy(passwords = book.passwords + (id to password))
        write(book)
    }

    fun forgetPassword(id: String) = synchronized(lock) {
        book = book.copy(passwords = book.passwords - id)
        write(book)
    }

    private fun readFile(): SecretBook {
        if (!file.exists()) return SecretBook()
        val blob = runCatching { file.readBytes() }.getOrNull() ?: return SecretBook()
        val plain = if (blob.size > 4 && blob[0] == 'C'.code.toByte()) {
            runCatching { decrypt(blob) }.getOrNull()
        } else {
            null
        } ?: return SecretBook()
        return SecretCodec.decode(plain.decodeToString())
    }

    private fun write(next: SecretBook) {
        val plain = SecretCodec.encode(next).encodeToByteArray()
        file.parentFile?.mkdirs()
        file.writeBytes(encrypt(plain))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        return byteArrayOf('C'.code.toByte(), 'R'.code.toByte(), 'Y'.code.toByte(), '1'.code.toByte(), iv.size.toByte()) +
            iv + body
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > 5 && blob[0] == 'C'.code.toByte())
        val ivLength = blob[4].toInt() and 0xFF
        val iv = blob.copyOfRange(5, 5 + ivLength)
        val body = blob.copyOfRange(5 + ivLength, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }

    private companion object {
        const val ALIAS = "carry.vault"
    }
}
