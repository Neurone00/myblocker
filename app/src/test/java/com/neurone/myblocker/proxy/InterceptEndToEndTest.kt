package com.neurone.myblocker.proxy

import com.neurone.myblocker.tls.CertAuthority
import com.neurone.myblocker.web.CosmeticRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Full path on the JVM: TLS client -> InterceptProxy (terminates TLS with a minted
 * certificate) -> verified TLS to an origin -> HTML injected on the way back.
 * The origin here also uses a certificate from the same local CA, and the JVM's
 * default trust is pointed at that CA, exactly as the phone trusts the installed one.
 */
class InterceptEndToEndTest {
    private class Response(val head: String, val body: ByteArray)

    private fun readResponse(input: InputStream): Response {
        val head = ByteArrayOutputStream()
        var last = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            head.write(b)
            last = ((last shl 8) or b) and -1
            if (last == 0x0d0a0d0a) break
        }
        val headText = head.toString("ISO-8859-1")
        val len = Regex("(?i)content-length:\\s*(\\d+)").find(headText)?.groupValues?.get(1)?.toInt() ?: -1
        val body = ByteArrayOutputStream()
        if (len >= 0) {
            val buf = ByteArray(4096)
            var left = len
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size, left))
                if (n < 0) break
                body.write(buf, 0, n)
                left -= n
            }
        } else {
            input.copyTo(body)
        }
        return Response(headText, body.toByteArray())
    }

    private fun trustingContext(ca: CertAuthority): SSLContext {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        ks.setCertificateEntry("adbrella", ca.caCert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tmf.trustManagers, null)
        return ctx
    }

    @Test fun pageIsTidiedThroughTheProxy() {
        val dir = Files.createTempDirectory("adbrella-e2e").toFile()
        val ca = CertAuthority.load(dir)
        val trust = trustingContext(ca)
        SSLContext.setDefault(trust) // the proxy verifies origins with the default context

        // --- origin: HTTPS server for "localhost" answering HTML, once gzip-compressed and once plain/chunked.
        val originCtx = ca.forHost("localhost").sslContext
        val origin = originCtx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        val html = "<!doctype html><html><head><title>t</title></head><body><div class=\"ad-banner\">x</div></body></html>"
        val requestsSeen = ArrayList<String>()
        val originThread = Thread {
            try {
                while (true) {
                    val s = origin.accept()
                    Thread {
                        s.use { sock ->
                            val input = BufferedInputStream(sock.getInputStream())
                            val out = sock.getOutputStream()
                            while (true) {
                                val head = readHeadText(input) ?: break
                                synchronized(requestsSeen) { requestsSeen.add(head) }
                                val path = head.substringBefore("\r\n").split(' ')[1]
                                if (path == "/gz") {
                                    val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(html.toByteArray()) } }.toByteArray()
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Encoding: gzip\r\nContent-Security-Policy: default-src 'self'\r\nAlt-Svc: h3=\":443\"\r\nContent-Length: ${gz.size}\r\n\r\n".toByteArray())
                                    out.write(gz)
                                } else if (path == "/chunked") {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                                    val bytes = html.toByteArray()
                                    val half = bytes.size / 2
                                    out.write("${Integer.toHexString(half)}\r\n".toByteArray()); out.write(bytes, 0, half); out.write("\r\n".toByteArray())
                                    out.write("${Integer.toHexString(bytes.size - half)}\r\n".toByteArray()); out.write(bytes, half, bytes.size - half); out.write("\r\n0\r\n\r\n".toByteArray())
                                } else if (path == "/image") {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 4\r\n\r\nPNG!".toByteArray())
                                } else {
                                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n".toByteArray())
                                    out.write(html.toByteArray())
                                }
                                out.flush()
                            }
                        }
                    }.start()
                }
            } catch (_: Exception) {
            }
        }
        originThread.isDaemon = true
        originThread.start()

        // --- proxy with a tiny rule set
        val rulesFile = Files.createTempFile("rules", ".txt").toFile()
        rulesFile.writeText("[Adblock Plus 2.0]\n##.ad-banner\nlocalhost##.sponsored\n")
        val rules = CosmeticRules.parse(rulesFile)
        val proxy = InterceptProxy(ca, protect = { true }, rules = { rules })
        val proxyErrors = java.util.Collections.synchronizedList(ArrayList<String>())
        proxy.errorListener = { e ->
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            proxyErrors.add(sw.toString())
        }
        proxy.start()
        val originAddr = InetAddress.getLoopbackAddress().address

        fun viaProxy(host: String, dst: ByteArray, dstPort: Int, request: String): Response {
            val plain = Socket()
            plain.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            proxy.register(plain.localPort, dst, dstPort)
            plain.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.port), 5000)
            val ssl = trust.socketFactory.createSocket(plain, host, dstPort, true) as SSLSocket
            try {
                ssl.startHandshake()
            } catch (e: Exception) {
                Thread.sleep(200)
                throw AssertionError("handshake with proxy failed: $e; proxy errors: ${proxyErrors.joinToString("\n---\n")}", e)
            }
            // The minted certificate must be for the requested host and chain to our CA.
            val peer = ssl.session.peerCertificates[0] as java.security.cert.X509Certificate
            assertTrue(peer.subjectX500Principal.name.contains("CN=$host"))
            peer.verify(ca.caCert.publicKey)
            ssl.getOutputStream().write(request.toByteArray())
            ssl.getOutputStream().flush()
            val r = readResponse(BufferedInputStream(ssl.getInputStream()))
            ssl.close()
            return r
        }

        // 1. plain HTML with Content-Length
        val r1 = viaProxy("localhost", originAddr, origin.localPort, "GET / HTTP/1.1\r\nHost: localhost\r\nAccept-Encoding: br, gzip\r\nConnection: close\r\n\r\n")
        assertTrue(r1.head.startsWith("HTTP/1.1 200"))
        val body1 = String(r1.body)
        assertTrue(body1.contains("https://rules.adbrella.internal/g.css?v=${rules.version}"))
        assertTrue(body1.contains("https://rules.adbrella.internal/s/localhost.css?v=${rules.version}"))
        assertTrue(body1.indexOf("<link") < body1.indexOf("</head>"))
        assertEquals(r1.body.size, Regex("(?i)content-length:\\s*(\\d+)").find(r1.head)!!.groupValues[1].toInt())
        synchronized(requestsSeen) { assertTrue(requestsSeen.last().contains("Accept-Encoding: gzip, identity")) }

        // 2. gzip + CSP + Alt-Svc: decoded, injected, CSP extended, Alt-Svc gone
        val r2 = viaProxy("localhost", originAddr, origin.localPort, "GET /gz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        val body2 = String(r2.body)
        assertTrue(body2.contains("rules.adbrella.internal/g.css"))
        assertFalse(r2.head.contains("Content-Encoding"))
        assertFalse(r2.head.contains("Alt-Svc"))
        assertTrue(r2.head.contains("Content-Security-Policy: default-src 'self'; style-src 'self' https://rules.adbrella.internal"))

        // 3. chunked HTML is de-chunked and injected
        val r3 = viaProxy("localhost", originAddr, origin.localPort, "GET /chunked HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        assertTrue(String(r3.body).contains("rules.adbrella.internal/g.css"))
        assertFalse(r3.head.contains("Transfer-Encoding"))

        // 4. non-HTML passes through untouched
        val r4 = viaProxy("localhost", originAddr, origin.localPort, "GET /image HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        assertEquals("PNG!", String(r4.body))

        // 5. the internal rules host serves the stylesheets with caching headers
        val g = viaProxy(InterceptProxy.INTERNAL_HOST, InterceptProxy.INTERNAL_IP, 443, "GET /g.css?v=${rules.version} HTTP/1.1\r\nHost: ${InterceptProxy.INTERNAL_HOST}\r\nConnection: close\r\n\r\n")
        assertTrue(g.head.contains("text/css"))
        assertTrue(g.head.contains("max-age=86400"))
        assertEquals(".ad-banner{display:none!important}\n", String(g.body))
        val s = viaProxy(InterceptProxy.INTERNAL_HOST, InterceptProxy.INTERNAL_IP, 443, "GET /s/localhost.css HTTP/1.1\r\nHost: ${InterceptProxy.INTERNAL_HOST}\r\nConnection: close\r\n\r\n")
        assertEquals(".sponsored{display:none!important}\n", String(s.body))

        assertEquals(3L, proxy.pagesTidied) // three HTML pages injected; the image and the rules host are not
        proxy.stop()
        origin.close()
        dir.deleteRecursively()
    }

    private fun readHeadText(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        var last = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (head.size() == 0) null else head.toString("ISO-8859-1")
            head.write(b)
            last = ((last shl 8) or b) and -1
            if (last == 0x0d0a0d0a) return head.toString("ISO-8859-1")
        }
    }
}
