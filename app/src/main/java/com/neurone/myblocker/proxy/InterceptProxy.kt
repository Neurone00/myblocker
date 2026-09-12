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
        DeepCleanStats.connections = connections
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

    /** Extra hostnames (and their subdomains) the user chose to leave untouched. */
    @Volatile var userExcluded: Set<String> = emptySet()
    @Volatile var passthroughs: Long = 0; private set

    /** True when [host] should be tunnelled raw rather than intercepted. Matches the host and its parents. */
    fun isExcluded(host: String): Boolean {
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
        return out
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
var RE=/(^|[^a-z])(adv|ads|advert|advertis|advertisement|reklam|werbung|publicidad|pubblicit|sponsor|banner|billboard|leaderboard)([^a-z]|${'$'})/i;
var LABEL=/^\s*(adv|ads|advert|advertisement|advertising|publicidad|pubblicit[aà]|sponsor|sponsored|werbung|reklama|annuncio|annunci|pubblicità)\s*${'$'}/i;
function cls(el){var c=el.className;return typeof c==='string'?c:(c&&c.baseVal)||'';}
function adish(el){return RE.test(el.id+' '+cls(el))||el.hasAttribute('data-ad-slot')||el.hasAttribute('data-ad-client')||el.hasAttribute('data-google-query-id')||el.hasAttribute('data-ad');}
function empty(el){
 if(el.querySelector('img[src],picture,video,canvas,form,input,button,h1,h2,h3,h4,article,p'))return false;
 var t=(el.textContent||'').replace(/\s+/g,' ').trim();
 if(t.length>25&&!LABEL.test(t))return false;
 var ifr=el.querySelector('iframe');
 return true;
}
function hide(el){try{if(el&&el.style&&el.getAttribute('data-adb')!=='1'){el.style.setProperty('display','none','important');el.setAttribute('data-adb','1');}}catch(e){}}
function labelOnly(el){var t=(el.textContent||'').replace(/\s+/g,' ').trim();return t.length<=18&&LABEL.test(t);}
function sweep(){try{
 var s=document.querySelectorAll('ins.adsbygoogle,[id^=google_ads_],[id^=div-gpt-ad],[id*=div-gpt-ad],iframe[src*=doubleclick],iframe[src*=googlesyndication],iframe[src*=amazon-adsystem],iframe[src*=adnxs],[data-ad-slot],[data-google-query-id]');
 for(var i=0;i<s.length;i++)hide(s[i]);
 var d=document.querySelectorAll('div,section,aside,ul,li,figure,ins,span');
 for(var j=0;j<d.length;j++){var el=d[j];if(el.getAttribute('data-adb')==='1')continue;
  // Placeholder whose entire visible text is just an ad label (e.g. a grey "ADV" box), whatever its class.
  if(labelOnly(el)&&!el.querySelector('img[src],video,canvas,input,h1,h2,h3')){hide(el);var p=el.parentElement;if(p&&labelOnly(p))hide(p);continue;}
  if(adish(el)&&empty(el))hide(el);}
}catch(e){}}
function run(){sweep();}
if(document.readyState!=='loading')run();else document.addEventListener('DOMContentLoaded',run);
var n=0,mo;try{mo=new MutationObserver(function(){if(n++>300)return;sweep();});mo.observe(document.documentElement||document,{childList:true,subtree:true});}catch(e){}
setTimeout(function(){try{mo&&mo.disconnect();}catch(e){}sweep();},12000);
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
