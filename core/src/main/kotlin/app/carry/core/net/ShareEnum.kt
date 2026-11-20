package app.carry.core.net

/**
 * Disk names from a NetShareEnum level-1 stub. Hidden admin names ending in $ are left out.
 * IPC and printer shares are left out.
 *
 * The stub is the share struct, then the container, then the array count, then each name.
 */
object ShareEnum {
    fun diskNames(stub: ByteArray): List<String> {
        if (stub.size < 16) return emptyList()
        val reader = Reader(stub)
        if (reader.u32() == 0) return emptyList()
        val level = reader.u32()
        val switch = reader.u32()
        if (level != 1 || switch != 1) return emptyList()
        if (reader.u32() == 0) return emptyList()
        val entries = reader.u32()
        if (entries <= 0 || entries > 512) return emptyList()
        if (reader.u32() == 0) return emptyList()
        reader.u32()
        val slots = mutableListOf<Slot>()
        repeat(entries) {
            val nameRef = reader.u32()
            val type = reader.u32()
            val remarkRef = reader.u32()
            if (nameRef != 0) slots += Slot(name = true, type = type)
            if (remarkRef != 0) slots += Slot(name = false, type = type)
        }
        val names = mutableListOf<String>()
        for (slot in slots) {
            val text = reader.wstr()
            if (slot.name && disk(slot.type) && text.isNotBlank() && !text.endsWith("$")) names += text
        }
        return names.distinct()
    }

    private fun disk(type: Int): Boolean = (type and 0xFFFF) == 0

    private class Slot(val name: Boolean, val type: Int)

    private class Reader(private val bytes: ByteArray) {
        private var index = 0

        fun u32(): Int {
            if (index + 4 > bytes.size) return 0
            val value = (bytes[index].toInt() and 0xFF) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8) or
                ((bytes[index + 2].toInt() and 0xFF) shl 16) or
                ((bytes[index + 3].toInt() and 0xFF) shl 24)
            index += 4
            return value
        }

        fun wstr(): String {
            val actual = run {
                u32()
                u32()
                u32()
            }
            if (actual <= 0 || actual > 260 || index + actual * 2 > bytes.size) return ""
            val chars = CharArray(actual)
            for (n in 0 until actual) {
                val low = bytes[index].toInt() and 0xFF
                val high = bytes[index + 1].toInt() and 0xFF
                chars[n] = (low or (high shl 8)).toChar()
                index += 2
            }
            index = (index + 3) and 3.inv()
            return chars.concatToString().trimEnd('\u0000')
        }
    }
}
