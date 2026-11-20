package app.carry.core.net

/**
 * Hosts worth a port-445 knock.
 * A prefix wider than /24 is narrowed to the /24 that contains [address], so a
 * campus-sized mask cannot fan the scan out across the whole network.
 */
object Ipv4Subnets {
    fun ipv4ToLong(address: String): Long? {
        val parts = address.split('.')
        if (parts.size != 4) return null
        var bits = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bits = (bits shl 8) or value.toLong()
        }
        return bits and 0xFFFF_FFFFL
    }

    fun longToIpv4(value: Long): String {
        val bits = value and 0xFFFF_FFFFL
        return listOf(
            (bits ushr 24) and 0xFF,
            (bits ushr 16) and 0xFF,
            (bits ushr 8) and 0xFF,
            bits and 0xFF,
        ).joinToString(".")
    }

    /**
     * Hosts in the /24 around [address]. The phone's own address is left out.
     * .1 and .2 come first, because a small NAS often lives there.
     */
    fun hosts(address: String, prefixLength: Int, limit: Int = 254): List<String> {
        if (limit <= 0) return emptyList()
        val ip = ipv4ToLong(address) ?: return emptyList()
        val prefix = maxOf(prefixLength.coerceIn(0, 32), 24)
        val mask = mask(prefix)
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFF_FFFFL)
        val first = network + 1
        val last = broadcast - 1
        if (first > last) return emptyList()
        val result = LinkedHashSet<String>()
        fun offer(candidate: Long) {
            if (result.size >= limit) return
            if (candidate !in first..last || candidate == ip) return
            result.add(longToIpv4(candidate))
        }
        offer(network + 1)
        offer(network + 2)
        var cursor = first
        while (cursor <= last && result.size < limit) {
            offer(cursor)
            cursor++
        }
        return result.toList()
    }

    private fun mask(prefix: Int): Long {
        if (prefix <= 0) return 0L
        if (prefix >= 32) return 0xFFFF_FFFFL
        return (0xFFFF_FFFFL shl (32 - prefix)) and 0xFFFF_FFFFL
    }
}

/** A name a drive announced for itself. */
data class NetbiosName(
    val name: String,
    val suffix: Int,
    val group: Boolean,
)

/**
 * The NetBIOS node-status query used to learn a computer name after port 445 answers.
 * Plenty of SMB-only boxes ignore UDP 137. That is fine: the address still works.
 */
object Netbios {
    fun query(transactionId: Int = 0x4341): ByteArray {
        val name = encodeStar()
        val packet = ByteArray(12 + 1 + name.size + 1 + 4)
        packet[0] = (transactionId ushr 8).toByte()
        packet[1] = transactionId.toByte()
        packet[5] = 1 // one question
        packet[12] = name.size.toByte()
        name.copyInto(packet, 13)
        val typeAt = 13 + name.size + 1
        packet[typeAt] = 0x00
        packet[typeAt + 1] = 0x21 // NBSTAT
        packet[typeAt + 3] = 0x01 // IN
        return packet
    }

    fun parse(packet: ByteArray): List<NetbiosName> {
        if (packet.size < 12) return emptyList()
        val questions = u16(packet, 4)
        val answers = u16(packet, 6)
        if (answers < 1) return emptyList()
        var index = 12
        repeat(questions) {
            index = skipName(packet, index)
            if (index < 0 || index + 4 > packet.size) return emptyList()
            index += 4
        }
        index = skipName(packet, index)
        if (index < 0 || index + 10 > packet.size) return emptyList()
        index += 8 // type, class, ttl
        val length = u16(packet, index)
        index += 2
        if (length <= 0 || index >= packet.size) return emptyList()
        val count = packet[index].toInt() and 0xFF
        index += 1
        val names = ArrayList<NetbiosName>(count)
        repeat(count) {
            if (index + 18 > packet.size) return names
            val raw = packet.copyOfRange(index, index + 15).toString(Charsets.US_ASCII).trim()
            val suffix = packet[index + 15].toInt() and 0xFF
            val flags = u16(packet, index + 16)
            if (raw.isNotEmpty()) names += NetbiosName(raw, suffix, flags and 0x8000 != 0)
            index += 18
        }
        return names
    }

    /** Workstation name, then the server service name. Group names are skipped. */
    fun computerName(names: List<NetbiosName>): String? =
        names.firstOrNull { !it.group && it.suffix == 0x00 && it.name.isNotBlank() }?.name
            ?: names.firstOrNull { !it.group && it.suffix == 0x20 && it.name.isNotBlank() }?.name

    private fun encodeStar(): ByteArray {
        val padded = ByteArray(16) { ' '.code.toByte() }
        padded[0] = '*'.code.toByte()
        val out = ByteArray(32)
        for (i in padded.indices) {
            val value = padded[i].toInt() and 0xFF
            out[i * 2] = ('A'.code + (value ushr 4)).toByte()
            out[i * 2 + 1] = ('A'.code + (value and 0x0F)).toByte()
        }
        return out
    }

    private fun skipName(packet: ByteArray, start: Int): Int {
        var index = start
        if (index >= packet.size) return -1
        val first = packet[index].toInt() and 0xFF
        if (first and 0xC0 == 0xC0) return index + 2
        while (index < packet.size) {
            val length = packet[index].toInt() and 0xFF
            if (length == 0) return index + 1
            if (length and 0xC0 == 0xC0) return index + 2
            index += length + 1
        }
        return -1
    }

    private fun u16(packet: ByteArray, index: Int): Int {
        if (index + 1 >= packet.size) return 0
        return ((packet[index].toInt() and 0xFF) shl 8) or (packet[index + 1].toInt() and 0xFF)
    }
}

object HostText {
    fun acceptable(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > 253) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        if (trimmed.contains("://") || trimmed.contains("/") || trimmed.contains("\\")) return null
        return trimmed
    }
}
