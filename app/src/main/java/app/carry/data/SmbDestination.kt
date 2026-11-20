package app.carry.data

import app.carry.core.model.Destination
import app.carry.core.model.RemoteFile
import app.carry.core.sync.BackupPaths
import app.carry.core.sync.ShareNames
import app.carry.core.text.FileNames
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.util.concurrent.TimeUnit

class CarryException(message: String) : Exception(message)

/** FILE_CREATE refused because the name is already on the drive. The existing file is left alone. */
class AlreadyThere : Exception()

class SmbDestination {
    private val config: SmbConfig = SmbConfig.builder()
        .withTimeout(12, TimeUnit.SECONDS)
        .withSoTimeout(20, TimeUnit.SECONDS)
        .build()

    fun shares(host: String, username: String?, password: String?, domain: String?): List<String> =
        useSession(host, username, password, domain) { session ->
            val found = mutableListOf<String>()
            for (name in ShareNames.usual) {
                try {
                    val share = session.connectShare(name)
                    try {
                        if (share is DiskShare) found += name
                    } finally {
                        runCatching { share.close() }
                    }
                } catch (_: SMBApiException) {
                    // That name is not published. Try the next one.
                } catch (_: Exception) {
                    break
                }
            }
            found.distinct()
        }

    fun folders(
        host: String,
        username: String?,
        password: String?,
        domain: String?,
        shareName: String,
        folder: String,
    ): List<String> = useDisk(host, username, password, domain, shareName) { share ->
        val path = join(folder)
        if (path.isNotEmpty() && !share.folderExists(path)) return@useDisk emptyList()
        share.list(path).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == ".." || name.startsWith(".")) return@mapNotNull null
            if (!directory(info.fileAttributes)) return@mapNotNull null
            name
        }.sortedBy { it.lowercase() }
    }

    fun makeFolder(
        host: String,
        username: String?,
        password: String?,
        domain: String?,
        shareName: String,
        parent: String,
        name: String,
    ): String {
        val clean = FileNames.folder(name)
        val relative = BackupPaths.folder(listOf(parent, clean).filter { it.isNotBlank() }.joinToString("/"))
        useDisk(host, username, password, domain, shareName) { share ->
            ensure(share, join(relative))
        }
        return relative
    }

    fun <T> session(destination: Destination, password: String?, block: (ShareIO) -> T): T =
        useDisk(destination.host, destination.username, password, destination.domain, destination.share) { share ->
            block(ShareIO(share, destination.folder))
        }

    private inline fun <T> useDisk(
        host: String,
        username: String?,
        password: String?,
        domain: String?,
        shareName: String,
        block: (DiskShare) -> T,
    ): T = useSession(host, username, password, domain) { session ->
        val share = session.connectShare(shareName)
        if (share !is DiskShare) {
            runCatching { share.close() }
            throw CarryException("\"$shareName\" is not a folder share.")
        }
        try {
            block(share)
        } finally {
            runCatching { share.close() }
        }
    }

    private inline fun <T> useSession(
        host: String,
        username: String?,
        password: String?,
        domain: String?,
        block: (Session) -> T,
    ): T {
        val opened = logon(host, username, password, domain)
        try {
            return block(opened.session)
        } finally {
            runCatching { opened.session.close() }
            runCatching { opened.connection.close() }
            runCatching { opened.client.close() }
        }
    }

    private fun logon(host: String, username: String?, password: String?, domain: String?): Opened {
        val (name, port) = endpoint(host)
        if (!username.isNullOrBlank()) {
            return start(name, port).also { opened ->
                try {
                    opened.session = opened.connection.authenticate(
                        AuthenticationContext(username, password.orEmpty().toCharArray(), domain.orEmpty()),
                    )
                } catch (error: Exception) {
                    opened.closeQuietly()
                    throw error
                }
            }
        }
        val anonymous = start(name, port)
        try {
            anonymous.session = anonymous.connection.authenticate(AuthenticationContext.anonymous())
            return anonymous
        } catch (_: Exception) {
            anonymous.closeQuietly()
        }
        val guest = start(name, port)
        try {
            guest.session = guest.connection.authenticate(AuthenticationContext.guest())
            return guest
        } catch (error: Exception) {
            guest.closeQuietly()
            throw error
        }
    }

    private fun start(host: String, port: Int): Opened {
        val client = SMBClient(config)
        return try {
            Opened(client, client.connect(host, port))
        } catch (error: Exception) {
            runCatching { client.close() }
            throw error
        }
    }

    private fun endpoint(host: String): Pair<String, Int> {
        val trimmed = host.trim()
        val colon = trimmed.lastIndexOf(':')
        if (colon > 0 && trimmed.substring(colon + 1).all { it.isDigit() }) {
            val port = trimmed.substring(colon + 1).toIntOrNull()
            if (port != null && port in 1..65535) return trimmed.substring(0, colon) to port
        }
        return trimmed to SMBClient.DEFAULT_PORT
    }

    private class Opened(val client: SMBClient, val connection: Connection) {
        lateinit var session: Session

        fun closeQuietly() {
            if (::session.isInitialized) runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }
}

class ShareIO(private val share: DiskShare, private val root: String) {
    fun list(parents: Set<String>): List<RemoteFile> {
        val found = mutableListOf<RemoteFile>()
        for (parent in parents) {
            val path = join(root, parent)
            if (path.isNotEmpty() && !share.folderExists(path)) continue
            for (info in share.list(path)) {
                val name = info.fileName
                if (name == "." || name == "..") continue
                if (directory(info.fileAttributes)) continue
                val relative = listOf(parent, name).filter { it.isNotBlank() }.joinToString("/")
                found += RemoteFile(relative, info.endOfFile)
            }
        }
        return found
    }

    fun put(relative: String, input: InputStream) {
        val path = join(root, relative)
        val directory = path.substringBeforeLast('\\', "")
        try {
            ensure(share, directory)
            val remote = share.openFile(
                path,
                setOf(AccessMask.FILE_WRITE_DATA, AccessMask.FILE_APPEND_DATA),
                setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                setOf(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_CREATE,
                setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            try {
                remote.getOutputStream().use { out -> input.copyTo(out, 256 * 1024) }
            } catch (error: Exception) {
                runCatching { remote.close() }
                runCatching { share.rm(path) }
                throw error
            } finally {
                runCatching { remote.close() }
            }
        } catch (error: Exception) {
            if (collision(error)) throw AlreadyThere()
            throw error
        } finally {
            runCatching { input.close() }
        }
    }
}

private fun ensure(share: DiskShare, directory: String) {
    if (directory.isBlank()) return
    var current = ""
    for (part in directory.split('\\').filter { it.isNotBlank() }) {
        current = if (current.isEmpty()) part else "$current\\$part"
        if (share.folderExists(current)) continue
        try {
            share.mkdir(current)
        } catch (error: SMBApiException) {
            if (!share.folderExists(current)) throw error
        }
    }
}

private fun join(vararg parts: String): String =
    parts.flatMap { it.replace('\\', '/').split('/') }
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString("\\")

private fun directory(attributes: Long): Boolean =
    attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L

private fun collision(error: Exception): Boolean {
    val text = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    return text.contains("STATUS_OBJECT_NAME_COLLISION", true) ||
        text.contains("STATUS_OBJECT_NAME_EXISTS", true)
}
