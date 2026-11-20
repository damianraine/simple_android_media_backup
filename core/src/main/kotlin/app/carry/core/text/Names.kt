package app.carry.core.text

object Quiet {
    /** Drop secrets out of a message before it is shown. Short strings are left alone so we do not blank the world. */
    fun redact(message: String, secrets: Collection<String>): String {
        var value = message
        for (secret in secrets) {
            if (secret.length < 4) continue
            value = value.replace(secret, "***")
        }
        return value
    }
}

object FileNames {
    private val banned = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val space = Regex("""\s+""")

    fun file(name: String): String {
        val cleaned = name.replace(banned, " ").replace(space, " ").trim().trim('.')
        return cleaned.take(180).ifBlank { "file" }
    }

    fun folder(name: String): String {
        val cleaned = name.replace(banned, " ").replace(space, " ").trim().trim('.')
        return cleaned.take(80).ifBlank { "Pictures" }
    }

    /** `photo.jpg` + 2 → `photo (2).jpg`. The original file on the drive is left where it is. */
    fun suffixed(name: String, number: Int): String {
        val slash = name.lastIndexOf('/')
        val parent = if (slash >= 0) name.substring(0, slash + 1) else ""
        val leaf = if (slash >= 0) name.substring(slash + 1) else name
        val dot = leaf.lastIndexOf('.')
        val stem = if (dot > 0) leaf.substring(0, dot) else leaf
        val ext = if (dot > 0) leaf.substring(dot) else ""
        return "$parent$stem ($number)$ext"
    }
}
