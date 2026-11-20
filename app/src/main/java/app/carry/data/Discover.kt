package app.carry.data

import app.carry.core.model.FoundDrive
import app.carry.core.net.HostText
import app.carry.core.net.Ipv4Subnets
import app.carry.core.net.Netbios
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Knocks on TCP 445 across the local /24, then asks UDP 137 for a name when a drive answers. */
class Discover {
    suspend fun scan(onProgress: (String) -> Unit): List<FoundDrive> = withContext(Dispatchers.IO) {
        val nets = localNets()
        if (nets.isEmpty()) {
            onProgress("This phone does not have a local address.")
            return@withContext emptyList()
        }
        val hosts = nets.flatMap { (address, prefix) -> Ipv4Subnets.hosts(address, prefix) }.distinct()
        onProgress("checking ${hosts.size} addresses")
        val open = coroutineScope {
            val gate = Semaphore(48)
            hosts.map { host ->
                async {
                    gate.withPermit {
                        if (portOpen(host, 445, 300)) host else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        onProgress(if (open.isEmpty()) "nothing answered on port 445" else "naming ${open.size}")
        val named = coroutineScope {
            val gate = Semaphore(16)
            open.map { host ->
                async {
                    gate.withPermit {
                        val name = netbiosName(host) ?: host
                        FoundDrive(host, name)
                    }
                }
            }.awaitAll()
        }
        named.sortedWith(compareBy({ it.name == it.host }, { it.name.lowercase() }, { it.host }))
    }

    fun typed(input: String): FoundDrive? {
        val host = HostText.acceptable(input) ?: return null
        val bare = host.substringBeforeLast(':').ifBlank { host }
        return FoundDrive(host, bare)
    }

    private fun localNets(): List<Pair<String, Int>> {
        val found = mutableListOf<Pair<String, Int>>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (bound in nif.interfaceAddresses) {
                val ip = bound.address
                if (ip !is Inet4Address || ip.isLoopbackAddress) continue
                val host = ip.hostAddress ?: continue
                if (host.startsWith("169.254.")) continue
                found += host to bound.networkPrefixLength.toInt().coerceIn(0, 32)
            }
        }
        return found.distinct()
    }

    private fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun netbiosName(host: String): String? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            val query = Netbios.query()
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(host), 137))
            val buffer = ByteArray(1024)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            Netbios.computerName(Netbios.parse(buffer.copyOf(response.length)))
        }
    } catch (_: Exception) {
        null
    }
}
