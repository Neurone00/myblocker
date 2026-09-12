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

    /** Local port -> where the flow was really going, with the time it was registered (see [register]). */
    private val pending = ConcurrentHashMap<Int, Pair<Target, Long>>()
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

    /**
     * The relay calls this right after starting a redirected connect; [localPort] is the relay socket's
     * port. Entries are normally consumed by the matching accept, but a flow that dies before the proxy
     * accepts it would leave one behind forever — and the OS reuses local ports, so a later connection
     * could inherit a stale destination and be sent to the wrong origin. Old entries are dropped.
     */
    fun register(localPort: Int, dst: ByteArray, dstPort: Int) {
        val now = System.currentTimeMillis()
        if (pending.size > 64) pending.entries.removeAll { now - it.value.second > PENDING_TTL_MS }
        pending[localPort] = Target(dst, dstPort) to now
    }

    private fun handle(client: Socket) {
        connections++
        DeepCleanStats.connections = connections
        val entry = pending.remove(client.port)
        if (entry == null || System.currentTimeMillis() - entry.second > PENDING_TTL_MS) {
            runCatching { client.close() }
            return
        }
        val target = entry.first
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
                if (!internal && isExcluded(host)) {
                    // Certificate-pinned or user-excluded site: tunnel the raw TLS untouched so pinning holds.
                    passthrough(client, SequenceInputStream(ByteArrayInputStream(hello.consumed), cin), target)
                    return
                }
                val minted = ca.forHost(host)
                val replay = SequenceInputStream(ByteArrayInputStream(hello.consumed), cin)
                val ssl = minted.sslContext.socketFactory.createSocket(PrefixedSocket(client, replay), host, target.dstPort, true) as SSLSocket
                ssl.useClientMode = false
                val params = ssl.sslParameters
                params.applicationProtocols = arrayOf("http/1.1")
                ssl.sslParameters = params
                try {
                    ssl.startHandshake()
                } catch (e: Exception) {
                    // The browser refused our certificate (not installed / not trusted) or dropped the
                    // handshake; counted so the status screen can say so instead of silently showing 0 pages.
                    if (!internal) {
                        handshakeFailures++
                        DeepCleanStats.handshakeFailures = handshakeFailures
                        // Pass this site through untouched next time, so a reload works even if
                        // tidying never can here.
                        if (hello.sni != null) {
                            troubled.add(host.lowercase().trimEnd('.'))
                            DeepCleanStats.givenUp = troubled.size
                        }
                    }
                    throw e
                }
                if (internal) serveInternal(ssl.getInputStream(), ssl.getOutputStream(), tls = true)
                else relayHttp(ssl.getInputStream(), ssl.getOutputStream(), ssl, host, target, tls = true)
            } else if (internal) {
                // Plain HTTP works without the certificate, so the test page can explain what is missing.
                serveInternal(cin, client.getOutputStream(), tls = false)
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

    /** Extra hostnames (and their subdomains) the user chose to leave untouched. */
    @Volatile var userExcluded: Set<String> = emptySet()
    @Volatile var passthroughs: Long = 0; private set
    @Volatile var handshakeFailures: Long = 0; private set

    /**
     * Hosts that went wrong under interception (the browser refused our certificate, or the origin's
     * TLS would not negotiate) and are tunnelled raw from then on. Browsing must never stay broken
     * because tidying cannot handle a site, so the first failure is the last one it costs.
     */
    private val troubled = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Sites given up on and passed through raw since the tunnel started; shown in the UI. */
    val troubledHosts: List<String> get() = synchronized(troubled) { troubled.sorted() }

    /** True when [host] should be tunnelled raw rather than intercepted. Matches the host and its parents. */
    fun isExcluded(host: String): Boolean {
        if (host.lowercase().trimEnd('.') in troubled) return true
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in PINNED_HOSTS || h in userExcluded) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    /** Splices the client's raw TLS stream to the origin and back, so certificate pinning is preserved. */
    private fun passthrough(client: Socket, clientReplay: InputStream, target: Target) {
        passthroughs++
        DeepCleanStats.passthroughs = passthroughs
        val upstream = Socket()
        try {
            protect(upstream)
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(InetAddress.getByAddress(target.dst), target.dstPort), 15_000)
            val toUpstream = Thread({ runCatching { clientReplay.copyTo(upstream.getOutputStream()); upstream.getOutputStream().flush() } }, "pass-up")
            toUpstream.isDaemon = true
            toUpstream.start()
            runCatching { upstream.getInputStream().copyTo(client.getOutputStream()); client.getOutputStream().flush() }
            toUpstream.join(1000)
        } catch (e: Exception) {
            Log.d(TAG, "passthrough to ${ip(target.dst)}:${target.dstPort} failed: ${e.message}")
        } finally {
            runCatching { upstream.close() }
        }
    }

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
                    upstream = try {
                        openUpstream(host, target, tls)
                    } catch (e: Exception) {
                        // We cannot reach the origin the way interception needs to; hand this site
                        // back to the browser untouched from now on.
                        troubled.add(host.lowercase().trimEnd('.'))
                        DeepCleanStats.givenUp = troubled.size
                        throw e
                    }
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
                        var canInject = encoding == null || encoding == "identity"
                        if (encoding == "gzip") {
                            val decoded = runCatching { GZIPInputStream(ByteArrayInputStream(bytes)).readBytes() }.getOrNull()
                            if (decoded != null) {
                                bytes = decoded
                                canInject = true // we hold the plain HTML now; drop the encoding header below
                            }
                        }
                        val nonce = randomNonce()
                        if (canInject) {
                            val injected = inject(bytes, pageHost, nonce)
                            if (injected !== bytes) {
                                pagesTidied++
                                DeepCleanStats.pagesTidied = pagesTidied
                            }
                            bytes = injected
                            resp.remove("Content-Encoding")
                        }
                        resp.remove("Transfer-Encoding")
                        resp.set("Content-Length", bytes.size.toString())
                        resp.headers.replaceAll { (k, v) -> if (k.equals("Content-Security-Policy", true)) k to adjustCsp(v, nonce) else k to v }
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

    private fun inject(html: ByteArray, pageHost: String, nonce: String): ByteArray {
        val v = rules().version
        val n = " nonce=\"$nonce\""
        // Three layers, each independent so one failing still helps:
        //  - inline compact CSS for the most common ad slots (works with no external request),
        //  - external links to the full generic + per-site EasyList (best coverage when reachable),
        //  - an inline collapser that removes leftover empty ad placeholders (e.g. grey "ADV" boxes).
        val snippet = (
            "<style$n>$INLINE_CSS</style>" +
                "<link rel=\"stylesheet\"$n href=\"https://$INTERNAL_HOST/g.css?v=$v\">" +
                "<link rel=\"stylesheet\"$n href=\"https://$INTERNAL_HOST/s/${pageHost.lowercase()}.css?v=$v\">" +
                "<script$n>$COLLAPSER_JS</script>"
            ).toByteArray(Charsets.UTF_8)
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
        return if (lower.contains("content-security-policy")) adjustMetaCsp(out, nonce) else out
    }

    /** A CSP delivered as <meta http-equiv> gets the same nonce/origin additions as the header. */
    private fun adjustMetaCsp(html: ByteArray, nonce: String): ByteArray {
        val text = String(html, Charsets.ISO_8859_1) // byte-preserving
        val meta = Regex("(?is)<meta\\b[^>]*>")
        val attr = Regex("(?is)(\\bcontent\\s*=\\s*)([\"'])(.*?)\\2")
        val fixed = meta.replace(text) { m ->
            val tag = m.value
            if (!Regex("(?is)http-equiv\\s*=\\s*[\"']?content-security-policy").containsMatchIn(tag)) tag
            else attr.replace(tag) { a -> a.groupValues[1] + a.groupValues[2] + adjustCsp(a.groupValues[3], nonce) + a.groupValues[2] }
        }
        return if (fixed === text || fixed == text) html else fixed.toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * Lets the injected style/script run under the site's CSP without weakening it for the site:
     *  - style-src gains the internal rules host (for the external stylesheets), and
     *  - a nonce is added to style-src and script-src ONLY where inline is currently blocked, so a
     *    site relying on 'unsafe-inline' keeps it (adding a nonce would otherwise disable it).
     * The nonce matches the one placed on the injected tags.
     */
    fun adjustCsp(value: String, nonce: String): String {
        val directives = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val origin = "https://$INTERNAL_HOST"
        val nonceTok = "'nonce-$nonce'"

        fun tokensOf(name: String): List<String>? {
            val d = directives.firstOrNull { it.startsWith(name, true) && (it.length == name.length || it[name.length] == ' ') }
                ?: directives.firstOrNull { it.startsWith("default-src", true) && (it.length == 11 || it[11] == ' ') }
            return d?.trim()?.split(Regex("\\s+"))?.drop(1)
        }
        // Inline is already allowed when unsafe-inline is present and not neutralised by a nonce/hash.
        fun inlineAllowed(name: String): Boolean {
            val toks = tokensOf(name) ?: return false // no CSP for this type -> inline allowed anyway (handled by caller)
            val hasUnsafe = toks.any { it.equals("'unsafe-inline'", true) }
            val hasNonceOrHash = toks.any { it.startsWith("'nonce-", true) || it.startsWith("'sha", true) }
            return hasUnsafe && !hasNonceOrHash
        }
        fun ensure(name: String, extra: List<String>) {
            val idx = directives.indexOfFirst { it.startsWith(name, true) && (it.length == name.length || it[name.length] == ' ') }
            if (idx >= 0) {
                var d = directives[idx]
                if (d.contains("'none'", true)) d = name
                for (tok in extra) if (!d.contains(tok, true)) d += " $tok"
                directives[idx] = d
            } else {
                // Inherit from default-src if present, otherwise a fresh directive.
                val base = tokensOf(name)?.filterNot { it.equals("'none'", true) } ?: emptyList()
                directives.add((listOf(name) + base + extra).joinToString(" "))
            }
        }

        val styleExtra = mutableListOf(origin)
        if (!inlineAllowed("style-src")) styleExtra.add(nonceTok)
        ensure("style-src", styleExtra)
        if (!inlineAllowed("script-src")) ensure("script-src", listOf(nonceTok))
        return directives.joinToString("; ")
    }

    private fun randomNonce(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    // ------------------------------------------------------------- internal rules host

    /** Lines for the test page describing the deep-clean state; set by the service. */
    @Volatile var statusProvider: (() -> List<String>)? = null

    private fun serveInternal(rawIn: InputStream, rawOut: OutputStream, tls: Boolean) {
        val input = BufferedInputStream(rawIn)
        val out = BufferedOutputStream(rawOut)
        while (running) {
            val req = try { readHead(input) ?: return } catch (e: SocketTimeoutException) { return }
            val path = req.startLine.split(' ').getOrNull(1) ?: "/"
            val clean = path.substringBefore('?')
            val r = rules()
            val isTest = clean == "/test" || clean == "/test/"
            val css: String? = when {
                isTest -> testPage(tls)
                clean == "/g.css" -> r.genericCss
                clean.startsWith("/s/") && clean.endsWith(".css") -> r.siteCss(clean.removePrefix("/s/").removeSuffix(".css"))
                else -> null
            }
            val etag = "\"${r.version}\""
            val body: ByteArray
            val status: String
            if (css == null) {
                status = "404 Not Found"; body = ByteArray(0)
            } else if (!isTest && req.get("If-None-Match") == etag) {
                status = "304 Not Modified"; body = ByteArray(0)
            } else {
                status = "200 OK"; body = css.toByteArray(Charsets.UTF_8)
            }
            val head = StringBuilder("HTTP/1.1 $status\r\n")
                .append(if (isTest) "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" else "Content-Type: text/css; charset=utf-8\r\nCache-Control: public, max-age=86400\r\n")
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

    /**
     * Self-test page at https://rules.adbrella.internal/test. Reaching it at all proves the tunnel,
     * the proxy and the certificate work in that browser; it then shows three placeholders shaped
     * like the boxes news sites leave behind and reports whether the collapser removed them.
     */
    private fun testPage(tls: Boolean): String {
        val active = DeepCleanStats.intercepting
        val status = runCatching { statusProvider?.invoke() }.getOrNull().orEmpty()
        val statusHtml = if (status.isEmpty()) "" else "<ul>" + status.joinToString("") { "<li>" + it.replace("&", "&amp;").replace("<", "&lt;") + "</li>" } + "</ul>"
        val certCard = if (tls)
            "<div class=\"card\"><p class=\"ok\">Certificate: trusted by this browser.</p><p>You reached this page over HTTPS with Adbrella's own certificate.</p></div>"
        else
            "<div class=\"card\"><p><b>Certificate check:</b> this page came over plain HTTP. <a href=\"https://$INTERNAL_HOST/test\">Open the HTTPS version</a>: if it loads, Chrome trusts Adbrella's certificate; a certificate warning means it is not installed as a <i>CA certificate</i> yet (Settings › Security and privacy › Other security settings › Install from device storage › <b>CA certificate</b>).</p></div>"
        return """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Adbrella test</title>
<style>body{font-family:sans-serif;margin:0;padding:20px;background:#f3f4f8;color:#1b1c1f}h1{font-size:22px;margin:0 0 8px}.card{background:#fff;border-radius:16px;padding:16px;margin:0 0 14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
.ph{background:#dedede;color:#9a9a9a;text-align:center;font-size:34px;line-height:120px;min-height:120px;margin:10px 0}.ok{color:#1b7f3b;font-weight:600}.bad{color:#b3261e;font-weight:600}small{color:#666}#t3::before{content:"Pubblicità"}</style></head>
<body><div class="card"><h1>☂ Adbrella can see this browser</h1><p>This page comes from Adbrella itself, so Deep clean is routing this browser's traffic.</p>$statusHtml</div>
$certCard
<div class="card"><p>Three placeholders shaped like the boxes news sites leave behind, removed by the script running <i>in this page</i> — a check of the collapser itself, not proof that real sites are reached:</p>
<div id="t1" class="adv-box ph">ADV</div>
<div id="t2" class="box-top ph"><ins class="adsbygoogle" style="display:block;height:120px"></ins></div>
<div id="t3" class="slot-top ph"></div>
<p id="v">Checking…</p></div>
<div class="card"><small>Since the tunnel started: browser connections ${connections}, pages tidied ${pagesTidied}, certificate refused by a browser ${handshakeFailures} times.</small></div>
<script>$COLLAPSER_JS</script>
<script>var ACTIVE=$active;
setTimeout(function(){var h=function(i){var e=document.getElementById(i);return e&&e.getAttribute('data-adb')?1:0;};var n=h('t1')+h('t2')+h('t3');var v=document.getElementById('v');
if(n===3&&ACTIVE){v.textContent='All three removed, and this browser really is being tidied. If a site still shows a box, its markup is one the collapser does not recognise yet — say so and it gets added.';v.className='ok';}
else if(n===3){v.textContent='The collapser removed all three, but this page ran the script itself: real pages are NOT being tidied yet, because the certificate above is not installed.';v.className='bad';}
else{v.textContent='Only '+n+' of 3 removed ('+(h('t1')?'':'label ')+(h('t2')?'':'slot ')+(h('t3')?'':'css-label ')+'left). The script runs but misses that shape.';v.className='bad';}},1500);</script>
</body></html>
""".trim()
    }

    companion object {
        private const val TAG = "InterceptProxy"
        private const val MAX_HEAD = 64 * 1024
        /** How long a registered destination stays valid; a redirected connect is accepted at once. */
        private const val PENDING_TTL_MS = 30_000L
        private const val MAX_HTML = 4 * 1024 * 1024
        const val INTERNAL_HOST = "rules.adbrella.internal"
        val INTERNAL_IP: ByteArray = byteArrayOf(10, 111, 222.toByte(), 3)

        /** Compact, high-value ad-slot selectors, inlined so tidying works even if the external list cannot load. */
        const val INLINE_CSS =
            "ins.adsbygoogle,[id^=google_ads_],[id^=div-gpt-ad],[id*=div-gpt-ad],iframe[src*=doubleclick]," +
                "iframe[src*=googlesyndication],iframe[src*=amazon-adsystem],iframe[src*=adnxs],[data-ad-slot]," +
                "[data-google-query-id],[class*=adsbygoogle]{display:none!important}"

        /**
         * In-page collapser: removes leftover ad slots that a hosts/DNS blocker empties but leaves behind,
         * including grey "ADV" placeholder boxes that regional lists would otherwise be needed for. It is
         * deliberately conservative (only ad-marked elements with no real content) and self-limiting: the
         * observer stops after a short window so it costs no battery once the page settles.
         */
        val COLLAPSER_JS: String = """
(function(){try{
var W=window,D=document;
var RE=/(^|[^a-z])(ad|adv|ads|advert|advertis|advertisement|reklam|werbung|publicidad|pubblicit|sponsor|banner|billboard|leaderboard|skyscraper|mpu|dfp|gpt|adslot|adunit|adbox|adwrap|adcontainer|adholder|adzone|adspace|adframe|adcode|adlabel)([^a-z]|${'$'})/i;
var LABEL=/^[\s\-–—•·.:|()\[\]]*(adv|ads|ad|advert|advertisement|advertising|publicidad|publicit[eé]|pubblicit[aà]|sponsor|sponsored|sponsorizzato|contenuto sponsorizzato|werbung|anzeige|reklama|annuncio|annunci)[\s\-–—•·.:|()\[\]]*${'$'}/i;
var KEEP=/^(html|body|main|article|header|footer|nav|form|table|tbody|thead|tr|td|th)${'$'}/i;
var SEL='ins.adsbygoogle,[id^=google_ads_],[id^=div-gpt-ad],[id*=div-gpt-ad],iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=amazon-adsystem],iframe[src*=adnxs],iframe[src*=criteo],iframe[src*=taboola],iframe[src*=outbrain],[data-ad-slot],[data-google-query-id]';
var ADF=/doubleclick|googlesyndication|adnxs|amazon-adsystem|adsafeprotected|criteo|rubiconproject|pubmatic|openx|taboola|outbrain|adform|smartadserver|teads|seedtag|yieldlab|^about:blank${'$'}/i;
var MEDIA='img[src],picture,video,audio,canvas,form,input,button,select,textarea,h1,h2,h3,h4,h5,h6,article,p,table';
function hiddenWithin(x,root){for(var q=x;q&&q!==root;q=q.parentElement){if(q.getAttribute('data-adb'))return true;}return false;}
/* an embedded player or widget is content; a frame from an ad network is not */
function realFrame(el,root){var f=el.querySelectorAll('iframe');for(var i=0;i<f.length;i++){var x=f[i],s=x.getAttribute('src')||'';if(!s||ADF.test(s))continue;if(root&&hiddenWithin(x,root))continue;return true;}return false;}
function cls(el){var c=el.className;return typeof c==='string'?c:(c&&c.baseVal)||'';}
function adish(el){return RE.test(el.id+' '+cls(el))||el.hasAttribute('data-ad-slot')||el.hasAttribute('data-ad-client')||el.hasAttribute('data-google-query-id')||el.hasAttribute('data-ad')||el.hasAttribute('data-ad-unit')||el.hasAttribute('data-adunit');}
function norm(s){return (s||'').replace(/\s+/g,' ').trim();}
function txt(el){return norm(el.textContent);}
/* text of el ignoring anything we already hid, so an emptied wrapper reads as empty */
function vtxt(el){if(el.nodeType===3)return el.nodeValue||'';if(el.nodeType!==1||el.getAttribute('data-adb'))return '';var s='';for(var n=el.firstChild;n;n=n.nextSibling)s+=vtxt(n);return s;}
function pseudo(el){try{var b=W.getComputedStyle(el,'::before').content,a=W.getComputedStyle(el,'::after').content;return norm(((b&&b!=='none'&&b!=='normal')?b:'')+' '+((a&&a!=='none'&&a!=='normal')?a:'')).replace(/["']/g,'');}catch(e){return '';}}
/* whole visible text is just an ad label ("ADV", "Pubblicità"), literal or CSS-generated on an ad-ish box */
function labelOnly(el,a){var t=txt(el);if(t.length>24)return false;if(t&&LABEL.test(t))return true;if(!t&&a){var p=pseudo(el);return !!p&&LABEL.test(p);}return false;}
function hasContent(el){if(el.querySelector(MEDIA)||realFrame(el))return true;var t=txt(el);return t.length>25&&!LABEL.test(t);}
function hasVisibleContent(el){var m=el.querySelectorAll(MEDIA);for(var i=0;i<m.length;i++){if(!hiddenWithin(m[i],el))return true;}if(realFrame(el,el))return true;var t=norm(vtxt(el));return t.length>0&&!LABEL.test(t);}
/* a big empty box (reserved ad space) whatever its class; the label may be CSS-generated */
function tallEmpty(el){if(txt(el)!==''||el.querySelector(MEDIA)||realFrame(el))return false;return el.offsetHeight>=80&&el.offsetWidth>=150;}
function mark(el,v){try{if(el&&el.style){if(!el.getAttribute('data-adb-d'))el.setAttribute('data-adb-d',el.style.getPropertyValue('display')||'-');el.style.setProperty('display','none','important');el.setAttribute('data-adb',v);}}catch(e){}}
/* after hiding an ad, collapse the ancestors it leaves empty (the grey reserved box), a few levels up */
function collapseUp(el){var p=el.parentElement,d=0;while(p&&d++<4){if(KEEP.test(p.tagName)||p.getAttribute('data-adb'))return;if(hasVisibleContent(p))return;var ks=p.children,n=0;for(var i=0;i<ks.length;i++){if(!ks[i].getAttribute('data-adb'))n++;}if(n>6)return;mark(p,'2');p=p.parentElement;}}
function hide(el){if(el.getAttribute('data-adb'))return;mark(el,'1');collapseUp(el);}
/* a wrapper we collapsed that later receives real content (widget loaded by script) is given back */
function restore(){var r=D.querySelectorAll('[data-adb="2"]');for(var i=0;i<r.length;i++){var el=r[i];if(hasVisibleContent(el)){var o=el.getAttribute('data-adb-d');el.style.removeProperty('display');if(o&&o!=='-')el.style.setProperty('display',o);el.removeAttribute('data-adb');el.removeAttribute('data-adb-d');}}}
var sweeps=0,mo=null;
function sweep(){try{
 sweeps++;
 var s=D.querySelectorAll(SEL);for(var i=0;i<s.length;i++)hide(s[i]);
 var d=D.querySelectorAll('div,section,aside,ul,li,figure,ins,span,td');
 for(var j=0;j<d.length;j++){var el=d[j];if(el.getAttribute('data-adb'))continue;var a=adish(el);
  if(labelOnly(el,a)&&!el.querySelector(MEDIA)){hide(el);continue;}
  if(a){if(!hasContent(el))hide(el);continue;}
  if(tallEmpty(el)){var p=pseudo(el);if(p&&LABEL.test(p))hide(el);}}
 restore();
 if(sweeps>600&&mo){try{mo.disconnect();}catch(e){}mo=null;}
}catch(e){}}
var pend=null;function sched(ms){if(pend)return;pend=setTimeout(function(){pend=null;sweep();},ms||250);}
if(D.readyState!=='loading')sweep();else D.addEventListener('DOMContentLoaded',function(){sweep();});
try{mo=new MutationObserver(function(){sched(250);});mo.observe(D.documentElement||D,{childList:true,subtree:true,attributes:true,attributeFilter:['class','id','style']});}catch(e){}
W.addEventListener('load',function(){sched(50);});
W.addEventListener('scroll',function(){sched(700);},{passive:true});
W.addEventListener('resize',function(){sched(700);},{passive:true});
setTimeout(function(){sweep();},1500);setTimeout(function(){sweep();},5000);
}catch(e){}})();
""".trim()

        /**
         * Hosts that pin certificates or must not be touched for safety. Browsing to these through
         * an intercepting proxy would break with our certificate, so we tunnel them raw instead.
         * Google properties are pinned in Chrome; payment and account domains are left intact.
         */
        val PINNED_HOSTS: Set<String> = setOf(
            "google.com", "gstatic.com", "googleapis.com", "youtube.com", "ytimg.com", "ggpht.com",
            "gmail.com", "googleusercontent.com", "google-analytics.com", "doubleclick.net",
            "facebook.com", "fbcdn.net", "instagram.com", "cdninstagram.com", "whatsapp.com", "whatsapp.net",
            "apple.com", "icloud.com", "microsoft.com", "live.com", "office.com", "windowsupdate.com",
            "mozilla.org", "mozilla.com", "cloudflareclient.com",
            "paypal.com", "stripe.com", "coinbase.com", "revolut.com",
            "samsung.com", "samsungcloud.com", "samsungqbe.com",
        )

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
