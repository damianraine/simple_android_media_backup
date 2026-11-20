package app.carry.data

import app.carry.core.net.ShareEnum
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.PipeShare
import java.io.ByteArrayOutputStream

/**
 * Asks the server which disks it publishes. SMB has no share list in the dialect we speak,
 * so this is a short DCE call on the srvsvc pipe. A failure returns an empty list.
 */
internal object ShareWire {
    fun list(session: Session): List<String> {
        val tree = session.connectShare("IPC$")
        try {
            val pipes = tree as? PipeShare ?: return emptyList()
            val pipe = pipes.open(
                "srvsvc",
                SMB2ImpersonationLevel.Impersonation,
                setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_WRITE_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                setOf(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            try {
                val bound = pipe.transact(bindPdu())
                if (!accepted(bound)) return emptyList()
                val answer = pipe.transact(enumPdu())
                val stub = stubOf(answer) ?: return emptyList()
                return ShareEnum.diskNames(stub)
            } finally {
                runCatching { pipe.close() }
            }
        } finally {
            runCatching { tree.close() }
        }
    }

    private fun accepted(pdu: ByteArray): Boolean =
        pdu.size >= 16 && pdu[0] == RPC_VERSION && (pdu[2].toInt() and 0xFF) == BIND_ACK

    private fun stubOf(pdu: ByteArray): ByteArray? {
        if (pdu.isEmpty()) return null
        if (pdu[0] != RPC_VERSION) return pdu
        if (pdu.size < 24 || (pdu[2].toInt() and 0xFF) != RESPONSE) return null
        val frag = u16(pdu, 8).coerceIn(24, pdu.size)
        return pdu.copyOfRange(24, frag)
    }

    private fun bindPdu(): ByteArray {
        val body = bytes(
            u16(4280),
            u16(4280),
            u32(0),
            byteArrayOf(1, 0),
            u16(0),
            u16(0),
            byteArrayOf(1, 0),
            uuid(SRVSVC),
            u16(3),
            u16(0),
            uuid(NDR),
            u16(2),
            u16(0),
        )
        return header(BIND, body)
    }

    private fun enumPdu(): ByteArray {
        val stub = bytes(
            u32(0),
            u32(0x00020000),
            u32(1),
            u32(1),
            u32(0x00020004),
            u32(0),
            u32(0),
            u32(0xFFFFFFFF.toInt()),
            u32(0),
        )
        val body = bytes(u32(stub.size), u16(0), u16(OP_SHARE_ENUM), stub)
        return header(REQUEST, body)
    }

    private fun header(type: Int, body: ByteArray): ByteArray {
        val length = 16 + body.size
        return bytes(
            byteArrayOf(RPC_VERSION, 0, type.toByte(), FIRST_AND_LAST),
            byteArrayOf(0x10, 0, 0, 0),
            u16(length),
            u16(0),
            u32(1),
            body,
        )
    }

    private fun uuid(text: String): ByteArray {
        val hex = text.replace("-", "")
        val raw = ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        return byteArrayOf(
            raw[3], raw[2], raw[1], raw[0],
            raw[5], raw[4],
            raw[7], raw[6],
            raw[8], raw[9],
            raw[10], raw[11], raw[12], raw[13], raw[14], raw[15],
        )
    }

    private fun u16(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun u32(value: Int): ByteArray = u16(value) + u16(value shr 16)

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun bytes(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private const val RPC_VERSION: Byte = 5
    private const val REQUEST = 0
    private const val RESPONSE = 2
    private const val BIND = 11
    private const val BIND_ACK = 12
    private const val FIRST_AND_LAST: Byte = 3
    private const val OP_SHARE_ENUM = 15
    private const val SRVSVC = "4b324fc8-1670-01d3-1278-5a47bf6ee188"
    private const val NDR = "8a885d04-1ceb-11c9-9fe8-08002b104860"
}
