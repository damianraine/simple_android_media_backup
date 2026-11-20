package app.carry.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.carry.BuildConfig
import app.carry.UpdateInstallReceiver
import app.carry.core.text.ReleaseNotes
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the sideload APK that GitHub replaces on every main build and installs it.
 * The address does not change. A newer versionCode in the release notes is what makes
 * an installed copy take the file. The signing key in the repo is what lets Android
 * replace the old copy instead of refusing it.
 */
class AppUpdate(private val context: Context, private val http: OkHttpClient) {
    private val apkUrl =
        "https://github.com/damianraine/simple_android_media_backup/releases/download/continuous/carry.apk"
    private val notesUrl =
        "https://api.github.com/repos/damianraine/simple_android_media_backup/releases/tags/continuous"

    fun installedLabel(): String = "Carry ${BuildConfig.VERSION_NAME}"

    suspend fun installLatest(onStatus: (String) -> Unit) {
        val report: suspend (String) -> Unit = { text ->
            withContext(Dispatchers.Main) { onStatus(text) }
        }
        withContext(Dispatchers.IO) {
            val remote = remoteVersion()
            if (remote != null) {
                when (ReleaseNotes.choose(BuildConfig.VERSION_CODE, remote.code)) {
                    ReleaseNotes.Choice.SAME -> {
                        report("Already the latest, ${remote.name}.")
                        return@withContext
                    }
                    ReleaseNotes.Choice.NEWER -> {
                        report("This copy is newer than the published build.")
                        return@withContext
                    }
                    ReleaseNotes.Choice.DOWNLOAD -> Unit
                }
            }
            report(if (remote == null) "Downloading the latest build" else "Downloading ${remote.name}")
            val file = File(context.cacheDir, "updates/carry.apk")
            file.parentFile?.mkdirs()
            val request = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "Carry")
                .header("Accept", "application/octet-stream")
                .build()
            val download = http.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.MINUTES)
                .writeTimeout(3, TimeUnit.MINUTES)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            try {
                download.newCall(request).execute().use { response ->
                    if (response.code == 404 || response.code == 401 || response.code == 403) {
                        report(
                            "This phone cannot fetch the file while the GitHub repository is private. " +
                                "Make the repository public, then press Update again.",
                        )
                        return@withContext
                    }
                    if (!response.isSuccessful) {
                        report("The download answered HTTP ${response.code}.")
                        return@withContext
                    }
                    val body = response.body
                    val total = body.contentLength()
                    var copied = 0L
                    var mark = 0
                    file.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                copied += read
                                if (total > 0) {
                                    val percent = ((copied * 100) / total).toInt()
                                    if (percent >= mark + 25) {
                                        mark = percent
                                        report("Downloading $percent%")
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                report("The download did not finish. Check this phone's internet connection and press Update again.")
                return@withContext
            }
            if (!looksLikeApk(file)) {
                file.delete()
                report("The file that arrived is not an app. The GitHub repository has to be public.")
                return@withContext
            }
            report("Opening the installer. Confirm it on this phone.")
            withContext(Dispatchers.Main) {
                onStatus(launchInstaller(file))
            }
        }
    }

    private fun looksLikeApk(file: File): Boolean {
        if (file.length() < 100_000) return false
        val header = ByteArray(2)
        return try {
            file.inputStream().use { input ->
                input.read(header) == 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun remoteVersion(): ReleaseNotes.Remote? {
        val request = Request.Builder()
            .url(notesUrl)
            .header("User-Agent", "Carry")
            .header("Accept", "application/vnd.github+json")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                ReleaseNotes.version(response.body.string())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun launchInstaller(file: File): String {
        if (sameCertificate(file) == false) {
            openUninstall()
            return "This copy of Carry was signed with a different key, so Android will not update it in place. " +
                "Uninstall it once. The next update installs over the top."
        }
        if (!television()) return openPackageView(file)
        return try {
            commit(file)
            "Confirm the install on this device. If it says the app was not installed, uninstall Carry once and press Update again."
        } catch (_: SecurityException) {
            openUnknownSources()
            "Allow Carry to install unknown apps, then press Update again."
        } catch (_: Exception) {
            openPackageView(file)
        }
    }

    private fun television(): Boolean {
        val manager = context.packageManager
        return manager.hasSystemFeature("android.software.leanback") ||
            manager.hasSystemFeature("amazon.hardware.fire_tv")
    }

    private fun sameCertificate(file: File): Boolean? {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, flags)
            }
        }.getOrNull()
        val incoming = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(file.absolutePath, flags)
        }
        val mine = certificateDigests(installed)
        val theirs = certificateDigests(incoming)
        if (mine.isEmpty() || theirs.isEmpty()) return null
        return mine.intersect(theirs).isNotEmpty()
    }

    private fun certificateDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun openUninstall() {
        val uninstall = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
        uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(uninstall) }
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(file.length())
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("carry.apk", 0, file.length()).use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateInstallReceiver::class.java),
                flags,
            )
            session.commit(pending.intentSender)
        } catch (error: Exception) {
            session.abandon()
            throw error
        } finally {
            session.close()
        }
    }

    private fun openUnknownSources() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(settings) }
    }

    private fun openPackageView(file: File): String {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "app.carry.files", file)
        listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        ).forEach { installer ->
            runCatching { context.grantUriPermission(installer, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(install)
            "Confirm the install on this phone. If Android says the signatures do not match, uninstall Carry once. " +
                "Later updates then install over the top."
        } catch (_: Exception) {
            "This phone would not open the installer. Allow apps from this source, then press Update again."
        }
    }
}
