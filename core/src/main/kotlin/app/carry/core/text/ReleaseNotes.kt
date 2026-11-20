package app.carry.core.text

/**
 * The release notes written by the GitHub workflow. The app reads versionCode from them
 * before it downloads the APK, the same way NetSky decides whether a build is newer.
 */
object ReleaseNotes {
    data class Remote(val code: Int, val name: String)

    enum class Choice { DOWNLOAD, SAME, NEWER }

    fun version(body: String): Remote? {
        val code = Regex("versionCode:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val name = Regex("versionName:\\s*([^\\s\"]+)").find(body)?.groupValues?.get(1) ?: return null
        return Remote(code, name)
    }

    fun choose(installedCode: Int, remoteCode: Int): Choice = when {
        remoteCode == installedCode -> Choice.SAME
        remoteCode < installedCode -> Choice.NEWER
        else -> Choice.DOWNLOAD
    }
}
