package com.neurone.myblocker.upstream

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.UpstreamMode
import com.neurone.myblocker.dns.DnsMessage
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL

/** Resolves a raw DNS query to a raw DNS response. Implementations block; call from a worker. */
interface Upstream {
    val description: String

    /** Returns the response bytes, or null if every attempt failed. */
    fun resolve(query: ByteArray): ByteArray?

    fun close() {}
}

/** Plain DNS over UDP, one short-lived socket per query, trying servers in order. */
class UdpUpstream(
    private val servers: () -> List<InetAddress>,
    private val protect: (DatagramSocket) -> Boolean,
    private val timeoutMs: Int = 2500,
    override val description: String = "Plain DNS",
) : Upstream {
    override fun resolve(query: ByteArray): ByteArray? {
        val list = servers()
        if (list.isEmpty()) return null
        for (server in list) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = timeoutMs
                socket.send(DatagramPacket(query, query.size, server, 53))
                val buf = ByteArray(4096)
                val packet = DatagramPacket(buf, buf.size)
                while (true) {
                    socket.receive(packet)
                    if (packet.length >= 2 && DnsMessage.id(buf) == DnsMessage.id(query)) {
                        return buf.copyOf(packet.length)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "udp upstream $server failed: ${e.message}")
            } finally {
                socket?.close()
            }
        }
        return null
    }

    companion object {
        private const val TAG = "UdpUpstream"
    }
}

/** DNS-over-HTTPS (RFC 8484, POST). Falls back to [fallback] when the HTTPS path fails. */
class DohUpstream(
    private val url: String,
    private val fallback: Upstream?,
    private val timeoutMs: Int = 4000,
) : Upstream {
    override val description: String = "DNS-over-HTTPS $url"

    @Volatile private var consecutiveFailures = 0

    override fun resolve(query: ByteArray): ByteArray? {
        val result = post(query)
        if (result != null) {
            consecutiveFailures = 0
            return result
        }
        consecutiveFailures++
        return fallback?.resolve(query)
    }

    private fun post(query: ByteArray): ByteArray? {
        // RFC 8484 recommends ID 0 for cacheability; we restore it on the way back.
        val body = query.copyOf()
        DnsMessage.setId(body, 0)
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/dns-message")
            conn.setRequestProperty("Accept", "application/dns-message")
            conn.setRequestProperty("User-Agent", "MyBlocker/1.0")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                Log.d(TAG, "DoH $url returned HTTP $code")
                runCatching { conn.errorStream?.use { it.readBytes() } }
                return null
            }
            val out = ByteArrayOutputStream(512)
            conn.inputStream.use { inp ->
                val buf = ByteArray(2048)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 65535) break
                }
            }
            val resp = out.toByteArray()
            if (resp.size < DnsMessage.HEADER_LENGTH) return null
            DnsMessage.setId(resp, DnsMessage.id(query))
            return resp
        } catch (e: Exception) {
            Log.d(TAG, "DoH $url failed: ${e.message}")
            return null
        }
        // no disconnect(): lets HttpURLConnection keep the TLS connection alive for the next query
    }

    companion object {
        private const val TAG = "DohUpstream"
    }
}

/** Builds the configured upstream chain. */
object UpstreamFactory {
    private const val TAG = "UpstreamFactory"

    val QUAD9: List<InetAddress> = addrs("9.9.9.9", "149.112.112.112", "2620:fe::fe")
    val CLOUDFLARE: List<InetAddress> = addrs("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111")
    val GOOGLE: List<InetAddress> = addrs("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888")
    val ADGUARD: List<InetAddress> = addrs("94.140.14.14", "94.140.15.15")

    fun create(context: Context, protect: (DatagramSocket) -> Boolean): Upstream {
        val prefs = Prefs.get(context)
        val system = UdpUpstream({ systemDnsServers(context).ifEmpty { QUAD9 } }, protect, description = "Network DNS")
        return when (prefs.upstreamMode) {
            UpstreamMode.DOH_QUAD9 -> DohUpstream("https://dns.quad9.net/dns-query", UdpUpstream({ QUAD9 }, protect))
            UpstreamMode.DOH_CLOUDFLARE -> DohUpstream("https://cloudflare-dns.com/dns-query", UdpUpstream({ CLOUDFLARE }, protect))
            UpstreamMode.DOH_GOOGLE -> DohUpstream("https://dns.google/dns-query", UdpUpstream({ GOOGLE }, protect))
            UpstreamMode.DOH_ADGUARD -> DohUpstream("https://dns.adguard-dns.com/dns-query", UdpUpstream({ ADGUARD }, protect))
            UpstreamMode.DOH_CUSTOM -> {
                val url = prefs.customDohUrl
                if (url.startsWith("https://")) DohUpstream(url, system) else system
            }
            UpstreamMode.SYSTEM -> system
            UpstreamMode.PLAIN_CUSTOM -> {
                val custom = addrs(*prefs.customDnsIp.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
                if (custom.isEmpty()) system else UdpUpstream({ custom }, protect, description = "Custom DNS")
            }
        }
    }

    /** DNS servers of the underlying (non-VPN) network, as seen by this app which is excluded from the tunnel. */
    fun systemDnsServers(context: Context): List<InetAddress> {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return emptyList()
            val lp = cm.getLinkProperties(network) ?: return emptyList()
            lp.dnsServers.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !isOurs(it) }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read system DNS", e)
            emptyList()
        }
    }

    private fun isOurs(a: InetAddress): Boolean {
        val s = a.hostAddress ?: return false
        return s.startsWith("10.111.222.") || s.startsWith("fd53:4d59:424c")
    }

    private fun addrs(vararg s: String): List<InetAddress> =
        s.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }

    @Suppress("unused")
    private fun socketAddress(a: InetAddress, port: Int) = InetSocketAddress(a, port)
}
