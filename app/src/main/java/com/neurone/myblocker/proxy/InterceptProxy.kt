package com.neurone.myblocker.proxy

import android.util.Log
import com.neurone.myblocker.tls.CertAuthority
import com.neurone.myblocker.web.CosmeticRules
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.SequenceInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Local HTTPS-terminating proxy for browser connections in deep-clean mode.
 *
 * The userspace relay redirects a browser's TCP connection here and registers the
 * original destination. This proxy peeks the ClientHello for the server name, mints
 * a certificate for it with the local CA, terminates TLS, opens a verified TLS
 * connection to the real server (system trust store, hostname check, HTTP/1.1 only)
 * and relays HTTP/1.1. HTML responses get two stylesheet links to the internal rules
 * host, whose CSS hides the empty ad containers. Everything else is copied through.
 *
 * Security stance: upstream certificates are verified exactly as the browser would;
 * a failing chain closes the connection instead of being hidden. Content-Security-Policy
 * headers are extended with the rules host rather than removed.
 */
class InterceptProxy(
    private val ca: CertAuthority,
    private val protect: (Socket) -> Boolean,
    private val rules: () -> CosmeticRules,
) {
    private class Target(val dst: ByteArray, val dstPort: Int)

    private val pending = ConcurrentHashMap<Int, Target>()
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "intercept").apply { isDaemon = true } }
    @Volatile private var running = false
    @Volatile var pagesTidied: Long = 0; private set
    @Volatile var connections: Long = 0; private set

    val port: Int get() = server?.localPort ?: 0

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 128)
        server = ss
        running = true
        acceptThread = Thread({
            while (running) {
                try {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed: ${e.message}")
                }
            }
        }, "intercept-accept").also { it.isDaemon = true; it.start() }
        Log.i(TAG, "listening on 127.0.0.1:$port")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    /** The relay calls this right after starting a redirected connect; [localPort] is the relay socket's port. */
    fun register(localPort: Int, dst: ByteArray, dstPort: Int) {
        pending[localPort] = Target(dst, dstPort)
    }

    private fun handle(client: Socket) {
        connections++
        val target = pending.remove(client.port)
        if (target == null) {
            runCatching { client.close() }
            return
        }
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val internal = target.dst.contentEquals(INTERNAL_IP)
            // Decide TLS-vs-plain from the first byte (0x16 = TLS handshake), not the port,
            // so HTTPS on non-standard ports and plain HTTP on 443 both behave correctly.
            val raw = client.getInputStream()
            val first = raw.read()
            if (first < 0) { client.close(); return }
            val cin = SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), raw)
            if (first == 0x16) {
                val hello = TlsPeek.readClientHello(cin)
                if (hello == null) { client.close(); return }
                val host = hello.sni ?: if (internal) INTERNAL_HOST else ip(target.dst)
                val minted = ca.forHost(host)
                val replay = SequenceInputStream(ByteArrayInputStream(hello.consumed), cin)
                val ssl = minted.sslContext.socketFactory.createSocket(PrefixedSocket(client, replay), host, target.dstPort, true) as SSLSocket
                ssl.useClientMode = false
                val params = ssl.sslParameters
                params.applicationProtocols = arrayOf("http/1.1")
                ssl.sslParameters = params
                ssl.startHandshake()
                if (internal) serveInternal(ssl) else relayHttp(ssl.getInputStream(), ssl.getOutputStream(), ssl, host, target, tls = true)
            } else {
                relayHttp(cin, client.getOutputStream(), client, ip(target.dst), target, tls = false)
            }
        } catch (e: Exception) {
            Log.d(TAG, "connection ended: ${e.javaClass.simpleName} ${e.message}")
            errorListener?.invoke(e)
        } finally {
            runCatching { client.close() }
        }
    }

    /** Diagnostics hook (tests): called with any exception that ends a connection. */
    @Volatile var errorListener: ((Throwable) -> Unit)? = null

    // ------------------------------------------------------------- upstream

    private fun openUpstream(host: String, target: Target, tls: Boolean): Socket {
        val plain = Socket()
        protect(plain)
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(InetAddress.getByAddress(target.dst), target.dstPort), 15_000)
        plain.soTimeout = 60_000
        if (!tls) return plain
        val isIp = host.matches(Regex("^[0-9.]+$")) || host.contains(':')
        val ssl = SSLContext.getDefault().socketFactory.createSocket(plain, host, target.dstPort, true) as SSLSocket
        val params = ssl.sslParameters
        params.applicationProtocols = arrayOf("http/1.1")
        if (!isIp) params.serverNames = listOf(SNIHostName(host))
        // The handshake itself verifies the certificate against the host, exactly as a browser does.
        // A mismatched or untrusted chain throws here, so a bad upstream is never silently accepted.
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl.startHandshake()
        return ssl
    }

    // ------------------------------------------------------------- HTTP relay

    private class Head(val startLine: String, val headers: MutableList<Pair<String, String>>) {
        fun get(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second
        fun remove(name: String) = headers.removeAll { it.first.equals(name, true) }
        fun set(name: String, value: String) { remove(name); headers.add(name to value) }
        fun bytes(): ByteArray {
            val sb = StringBuilder(startLine).append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            return sb.toString().toByteArray(Charsets.ISO_8859_1)
        }
    }

    private fun readHead(input: InputStream): Head? {
        val buf = ByteArrayOutputStream(1024)
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else throw IOException("truncated head")
            buf.write(b)
            last4 = ((last4 shl 8) or b) and 0xffffffff.toInt()
            if (last4 == 0x0d0a0d0a) break
            if (buf.size() > MAX_HEAD) throw IOException("head too large")
        }
        val text = buf.toString("ISO-8859-1")
        val lines = text.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val headers = ArrayList<Pair<String, String>>(lines.size)
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx <= 0) continue
            headers.add(lines[i].substring(0, idx).trim() to lines[i].substring(idx + 1).trim())
        }
        return Head(lines[0], headers)
    }

    private fun relayHttp(clientIn: InputStream, clientOut: OutputStream, clientSocket: Socket, host: String, target: Target, tls: Boolean) {
        val cin = BufferedInputStream(clientIn, 1 shl 16)
        val cout = BufferedOutputStream(clientOut, 1 shl 16)
        var upstream: Socket? = null
        var uin: InputStream? = null
        var uout: OutputStream? = null
        try {
            while (running) {
                val req = try { readHead(cin) ?: return } catch (e: SocketTimeoutException) { return }
                if (upstream == null || upstream.isClosed) {
                    upstream = openUpstream(host, target, tls)
                    uin = BufferedInputStream(upstream.getInputStream(), 1 shl 16)
                    uout = BufferedOutputStream(upstream.getOutputStream(), 1 shl 16)
                }
                val up = uin!!
                val uo = uout!!
                val method = req.startLine.substringBefore(' ')
                val upgrade = req.get("Upgrade") != null
                val clientClose = req.get("Connection")?.contains("close", true) == true || req.startLine.endsWith("HTTP/1.0")
                // We can decode gzip; drop brotli/zstd so HTML stays injectable.
                if (req.get("Accept-Encoding") != null) req.set("Accept-Encoding", "gzip, identity")
                uo.write(req.bytes())
                copyBody(cin, uo, req, allowUntilClose = false)
                uo.flush()

                val resp = readHead(up) ?: return
                val status = resp.startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (upgrade && status == 101) {
                    cout.write(resp.bytes()); cout.flush()
                    pump(cin, uo, up, cout)
                    return
                }
                val noBody = method == "HEAD" || status < 200 || status == 204 || status == 304
                val serverClose = resp.get("Connection")?.contains("close", true) == true || resp.startLine.startsWith("HTTP/1.0")
                resp.remove("Alt-Svc") // never let the browser escape to QUIC/HTTP3 for this origin
                val html = status == 200 && !noBody && resp.get("Content-Type")?.trim()?.lowercase()?.startsWith("text/html") == true
                val encoding = resp.get("Content-Encoding")?.lowercase()?.trim()
                if (html && (encoding == null || encoding == "gzip" || encoding == "identity")) {
                    val pageHost = req.get("Host")?.substringBefore(':') ?: host
                    val body = readBody(up, resp, MAX_HTML)
                    if (body != null) {
                        var bytes = body
                        if (encoding == "gzip") bytes = runCatching { GZIPInputStream(ByteArrayInputStream(bytes)).readBytes() }.getOrNull() ?: bytes.also { resp.set("Content-Encoding", "gzip") }
                        if (resp.get("Content-Encoding")?.equals("gzip", true) != true) {
                            val injected = inject(bytes, pageHost)
                            if (injected !== bytes) pagesTidied++
                            bytes = injected
                            resp.remove("Content-Encoding")
                        }
                        resp.remove("Transfer-Encoding")
                        resp.set("Content-Length", bytes.size.toString())
                        resp.headers.replaceAll { (k, v) -> if (k.equals("Content-Security-Policy", true)) k to adjustCsp(v) else k to v }
                        cout.write(resp.bytes())
                        cout.write(bytes)
                        cout.flush()
                    } else {
                        // Too large to buffer: already consumed nothing beyond the head, stream through.
                        cout.write(resp.bytes())
                        copyBody(up, cout, resp, allowUntilClose = true, noBody = noBody)
                        cout.flush()
                    }
                } else {
                    cout.write(resp.bytes())
                    if (!noBody) copyBody(up, cout, resp, allowUntilClose = true)
                    cout.flush()
                }
                if (clientClose || serverClose || (!noBody && resp.get("Content-Length") == null && !isChunked(resp))) return
            }
        } finally {
            runCatching { upstream?.close() }
        }
    }

    private fun isChunked(h: Head) = h.get("Transfer-Encoding")?.contains("chunked", true) == true

    /** Copies a message body according to its framing. Returns false if framed "until close" and consumed. */
    private fun copyBody(input: InputStream, out: OutputStream, head: Head, allowUntilClose: Boolean, noBody: Boolean = false) {
        if (noBody) return
        val len = head.get("Content-Length")?.trim()?.toLongOrNull()
        when {
            isChunked(head) -> copyChunked(input, out)
            len != null -> copyN(input, out, len)
            allowUntilClose -> { input.copyTo(out); out.flush(); throw IOException("body ended with connection") }
            else -> Unit
        }
    }

    private fun copyN(input: InputStream, out: OutputStream, n: Long) {
        val buf = ByteArray(1 shl 16)
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("truncated body")
            out.write(buf, 0, r)
            left -= r
        }
    }

    /** Copies chunked framing verbatim (sizes, chunks, trailers). */
    private fun copyChunked(input: InputStream, out: OutputStream) {
        while (true) {
            val line = readLine(input) ?: throw IOException("truncated chunk")
            out.write(line.toByteArray(Charsets.ISO_8859_1)); out.write("\r\n".toByteArray())
            val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0L) {
                // trailers until empty line
                while (true) {
                    val t = readLine(input) ?: throw IOException("truncated trailer")
                    out.write(t.toByteArray(Charsets.ISO_8859_1)); out.write("\r\n".toByteArray())
                    if (t.isEmpty()) return
                }
            }
            copyN(input, out, size)
            val crlf = readLine(input) ?: throw IOException("truncated chunk end")
            out.write(crlf.toByteArray(Charsets.ISO_8859_1)); out.write("\r\n".toByteArray())
        }
    }

    /** Reads a body fully (any framing), or returns null when it exceeds [cap] before anything was consumed. */
    private fun readBody(input: InputStream, head: Head, cap: Int): ByteArray? {
        val len = head.get("Content-Length")?.trim()?.toLongOrNull()
        if (len != null && len > cap) return null
        val out = ByteArrayOutputStream(if (len != null) len.toInt() else 64 * 1024)
        if (isChunked(head)) {
            while (true) {
                val line = readLine(input) ?: throw IOException("truncated chunk")
                val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size")
                if (size == 0L) {
                    while (true) { val t = readLine(input) ?: throw IOException("truncated trailer"); if (t.isEmpty()) break }
                    break
                }
                if (out.size() + size > cap) throw IOException("html too large")
                copyN(input, out, size)
                readLine(input)
            }
        } else if (len != null) {
            copyN(input, out, len)
        } else {
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > cap) throw IOException("html too large")
            }
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("line too long")
        }
        return sb.toString()
    }

    /** Bidirectional raw copy after a protocol upgrade (WebSocket). */
    private fun pump(cin: InputStream, uout: OutputStream, uin: InputStream, cout: OutputStream) {
        val t = Thread({ runCatching { cin.copyTo(uout); uout.flush() } }, "intercept-pump")
        t.isDaemon = true
        t.start()
        runCatching { uin.copyTo(cout); cout.flush() }
        t.join(1000)
    }

    // ------------------------------------------------------------- injection

    private fun inject(html: ByteArray, pageHost: String): ByteArray {
        val v = rules().version
        val snippet = ("<link rel=\"stylesheet\" href=\"https://$INTERNAL_HOST/g.css?v=$v\">" +
            "<link rel=\"stylesheet\" href=\"https://$INTERNAL_HOST/s/${pageHost.lowercase()}.css?v=$v\">").toByteArray(Charsets.US_ASCII)
        val lower = String(html, 0, minOf(html.size, 512 * 1024), Charsets.ISO_8859_1).lowercase()
        var at = lower.indexOf("</head>")
        if (at < 0) {
            val headOpen = lower.indexOf("<head")
            if (headOpen >= 0) {
                val close = lower.indexOf('>', headOpen)
                if (close > 0) at = close + 1
            }
        }
        if (at < 0) {
            val body = lower.indexOf("<body")
            if (body >= 0) at = body
        }
        if (at < 0) {
            if (!lower.contains("<html") && !lower.contains("<!doctype")) return html // probably not a page
            at = 0
        }
        val out = ByteArray(html.size + snippet.size)
        System.arraycopy(html, 0, out, 0, at)
        System.arraycopy(snippet, 0, out, at, snippet.size)
        System.arraycopy(html, at, out, at + snippet.size, html.size - at)
        return out
    }

    /** Adds the rules host to style-src (or derives one from default-src) so the injected links load. */
    fun adjustCsp(value: String): String {
        val directives = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val origin = "https://$INTERNAL_HOST"
        val styleIdx = directives.indexOfFirst { it.startsWith("style-src", true) && (it.length == 9 || it[9] == ' ') }
        if (styleIdx >= 0) {
            val d = directives[styleIdx]
            directives[styleIdx] = if (d.contains("'none'")) "style-src $origin" else "$d $origin"
        } else {
            val def = directives.firstOrNull { it.startsWith("default-src", true) }
            if (def != null) {
                val values = def.substring("default-src".length).trim().replace("'none'", "")
                directives.add("style-src $values $origin".replace("  ", " "))
            }
        }
        return directives.joinToString("; ")
    }

    // ------------------------------------------------------------- internal rules host

    private fun serveInternal(ssl: SSLSocket) {
        val input = BufferedInputStream(ssl.getInputStream())
        val out = BufferedOutputStream(ssl.getOutputStream())
        while (running) {
            val req = try { readHead(input) ?: return } catch (e: SocketTimeoutException) { return }
            val path = req.startLine.split(' ').getOrNull(1) ?: "/"
            val clean = path.substringBefore('?')
            val r = rules()
            val css: String? = when {
                clean == "/g.css" -> r.genericCss
                clean.startsWith("/s/") && clean.endsWith(".css") -> r.siteCss(clean.removePrefix("/s/").removeSuffix(".css"))
                else -> null
            }
            val etag = "\"${r.version}\""
            val body: ByteArray
            val status: String
            if (css == null) {
                status = "404 Not Found"; body = ByteArray(0)
            } else if (req.get("If-None-Match") == etag) {
                status = "304 Not Modified"; body = ByteArray(0)
            } else {
                status = "200 OK"; body = css.toByteArray(Charsets.UTF_8)
            }
            val head = StringBuilder("HTTP/1.1 $status\r\n")
                .append("Content-Type: text/css; charset=utf-8\r\n")
                .append("Cache-Control: public, max-age=86400\r\n")
                .append("ETag: $etag\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Content-Length: ${body.size}\r\n")
                .append("Connection: keep-alive\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
            if (req.get("Connection")?.contains("close", true) == true) return
        }
    }

    private fun ip(b: ByteArray): String = runCatching { InetAddress.getByAddress(b).hostAddress ?: "" }.getOrDefault("")

    companion object {
        private const val TAG = "InterceptProxy"
        private const val MAX_HEAD = 64 * 1024
        private const val MAX_HTML = 4 * 1024 * 1024
        const val INTERNAL_HOST = "rules.adbrella.internal"
        val INTERNAL_IP: ByteArray = byteArrayOf(10, 111, 222.toByte(), 3)

        /** Browsers that trust user-installed CAs and therefore can be tidied. Firefox is left out on purpose. */
        val BROWSER_PACKAGES: Set<String> = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.brave.browser", "com.brave.browser_beta", "com.brave.browser_nightly",
            "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
            "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
            "com.vivaldi.browser", "com.kiwibrowser.browser", "com.duckduckgo.mobile.android",
        )
    }
}
