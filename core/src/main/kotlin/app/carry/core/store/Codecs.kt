package app.carry.core.store

import app.carry.core.json.CarryJson
import app.carry.core.model.CarrySettings
import app.carry.core.model.SecretBook
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object SettingsCodec {
    fun encode(settings: CarrySettings): String = CarryJson.json.encodeToString(settings)

    fun decode(raw: String?): CarrySettings {
        if (raw.isNullOrBlank()) return CarrySettings()
        return try {
            CarryJson.json.decodeFromString<CarrySettings>(raw)
        } catch (_: SerializationException) {
            CarrySettings()
        } catch (_: IllegalArgumentException) {
            CarrySettings()
        }
    }
}

object SecretCodec {
    fun encode(book: SecretBook): String = CarryJson.json.encodeToString(book)

    fun decode(raw: String?): SecretBook {
        if (raw.isNullOrBlank()) return SecretBook()
        return try {
            CarryJson.json.decodeFromString<SecretBook>(raw)
        } catch (_: SerializationException) {
            SecretBook()
        } catch (_: IllegalArgumentException) {
            SecretBook()
        }
    }
}
