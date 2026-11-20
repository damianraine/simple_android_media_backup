package app.carry.core.sync

/** Names a home drive often publishes. A typed name is still accepted when none of these answer. */
object ShareNames {
    val usual: List<String> = listOf(
        "Users",
        "Public",
        "public",
        "share",
        "Share",
        "shared",
        "Shared",
        "media",
        "Media",
        "backup",
        "backups",
        "homes",
        "home",
        "data",
        "Data",
        "nas",
        "storage",
        "photos",
        "Photos",
        "videos",
        "video",
        "documents",
        "Downloads",
        "Download",
        "volume1",
        "Volume1",
        "disk1",
        "Drive",
    )

    fun tidy(name: String): String? {
        val trimmed = name.trim().trim('\\', '/')
        if (trimmed.isEmpty() || trimmed.length > 80) return null
        if (trimmed.endsWith("$")) return null
        if (trimmed.any { it == '/' || it == '\\' || it.isISOControl() }) return null
        return trimmed
    }
}
