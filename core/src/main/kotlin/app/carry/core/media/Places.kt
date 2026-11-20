package app.carry.core.media

import app.carry.core.text.FileNames

/**
 * Which MediaStore folders are worth copying, and what to call them on the drive.
 * Camera, DCIM, Pictures, WhatsApp, Downloads, and other albums stay.
 * Caches, thumbnails, app-private data, and WhatsApp statuses do not.
 */
object MediaPlaces {
    private val knownLeaves = listOf(
        "WhatsApp Images",
        "WhatsApp Video",
        "WhatsApp Animated Gifs",
        "Telegram Images",
        "Telegram Video",
        "Camera",
        "Screenshots",
    )
    private val ignored = setOf(
        "android",
        "media",
        "dcim",
        "pictures",
        "movies",
        "download",
        "downloads",
    )

    fun include(relativeDir: String): Boolean {
        val lower = segments(relativeDir).map { it.lowercase() }
        if (lower.any { it.startsWith(".") }) return false
        if (lower.any { it == "cache" || it == "thumbnails" || it == ".thumbnails" }) return false
        if (lower.any { it == "statuses" || it == ".statuses" }) return false
        if (lower.firstOrNull() == "android" && lower.getOrNull(1) == "data") return false
        return true
    }

    fun source(relativeDir: String, bucket: String?): String {
        val parts = segments(relativeDir).filter { !it.startsWith(".") }
        val joined = parts.joinToString("/")
        knownLeaves.firstOrNull { joined.contains(it, ignoreCase = true) }?.let { return it }
        if (parts.any { it.equals("Download", true) || it.equals("Downloads", true) }) return "Downloads"
        val leaf = parts.lastOrNull { it.lowercase() !in ignored }
        if (leaf != null) return FileNames.folder(leaf)
        if (!bucket.isNullOrBlank()) return FileNames.folder(bucket)
        return "Pictures"
    }

    fun segments(relativeDir: String): List<String> =
        relativeDir.replace('\\', '/').split('/').filter { it.isNotBlank() }
}
