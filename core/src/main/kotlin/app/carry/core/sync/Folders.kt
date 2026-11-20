package app.carry.core.sync

/** Matching folder names while the user is picking where copies should land. */
object FolderSearch {
    fun matches(path: String, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return path.contains(needle, ignoreCase = true)
    }

    fun narrow(names: List<String>, query: String): List<String> {
        val needle = query.trim()
        if (needle.isEmpty()) return names
        return names.filter { matches(it, needle) }
    }

    /** A path the user typed, with dot segments removed. Blank when it is not a folder path. */
    fun path(raw: String): String? {
        val cleaned = BackupPaths.folder(raw)
        if (cleaned.isEmpty() || cleaned.length > 240) return null
        return cleaned
    }
}
