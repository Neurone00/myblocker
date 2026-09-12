package com.neurone.myblocker.proxy

import com.neurone.myblocker.web.CosmeticRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class InterceptTest {
    /** Builds a minimal TLS 1.2 ClientHello record carrying an SNI extension. */
    private fun clientHello(host: String?): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(3, 3)) // version
        body.write(ByteArray(32)) // random
        body.write(0) // session id length
        body.write(byteArrayOf(0, 2, 0x13, 0x01)) // one cipher suite
        body.write(byteArrayOf(1, 0)) // compression
        val ext = ByteArrayOutputStream()
        if (host != null) {
            val name = host.toByteArray()
            val list = ByteArrayOutputStream()
            list.write(0); list.write(name.size shr 8); list.write(name.size and 0xff); list.write(name)
            val listBytes = list.toByteArray()
            ext.write(0); ext.write(0) // type server_name
            val extLen = listBytes.size + 2
            ext.write(extLen shr 8); ext.write(extLen and 0xff)
            ext.write(listBytes.size shr 8); ext.write(listBytes.size and 0xff)
            ext.write(listBytes)
        }
        val extBytes = ext.toByteArray()
        body.write(extBytes.size shr 8); body.write(extBytes.size and 0xff); body.write(extBytes)
        val hs = body.toByteArray()
        val handshake = ByteArrayOutputStream()
        handshake.write(1) // ClientHello
        handshake.write(hs.size shr 16); handshake.write((hs.size shr 8) and 0xff); handshake.write(hs.size and 0xff)
        handshake.write(hs)
        val h = handshake.toByteArray()
        val record = ByteArrayOutputStream()
        record.write(0x16); record.write(3); record.write(1)
        record.write(h.size shr 8); record.write(h.size and 0xff)
        record.write(h)
        return record.toByteArray()
    }

    @Test fun sniIsExtractedAndBytesPreserved() {
        val raw = clientHello("www.Example.com") + "tail".toByteArray()
        val hello = TlsPeek.readClientHello(ByteArrayInputStream(raw))
        assertNotNull(hello)
        assertEquals("www.example.com", hello!!.sni)
        assertTrue(raw.copyOf(hello.consumed.size).contentEquals(hello.consumed))
        assertNull(TlsPeek.readClientHello(ByteArrayInputStream(clientHello(null)))!!.sni)
        assertNull(TlsPeek.readClientHello(ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray()))!!.sni)
    }

    @Test fun cosmeticRulesParse() {
        val f = Files.createTempFile("easylist", ".txt").toFile()
        f.writeText(
            """
            [Adblock Plus 2.0]
            ! comment
            ##.ad-banner
            ##div[id^="ad-"]
            example.com,news.example.org##.sponsored
            ~example.com##.not-here
            example.com#@#.sponsored
            example.com#?#div:-abp-has(> .ad)
            ##.bad{selector}
            """.trimIndent(),
        )
        val r = CosmeticRules.parse(f)
        assertEquals(2, r.genericCount)
        assertTrue(r.genericCss.contains(".ad-banner{display:none!important}"))
        assertFalse(r.genericCss.contains("bad{selector"))
        assertTrue(r.siteCss("shop.example.com").contains(".sponsored{display:none!important}"))
        assertTrue(r.siteCss("news.example.org").contains(".sponsored"))
        assertEquals("", r.siteCss("other.net"))
        assertEquals(16, r.version.length)
        f.delete()
    }

    @Test fun cspGetsTheRulesHostAppended() {
        val p = InterceptProxy(ca = com.neurone.myblocker.tls.CertAuthority.load(Files.createTempDirectory("ca").toFile()), protect = { true }, rules = { CosmeticRules.EMPTY })
        assertEquals("default-src 'self'; style-src 'self' https://rules.adbrella.internal", p.adjustCsp("default-src 'self'; style-src 'self'"))
        assertEquals("style-src https://rules.adbrella.internal", p.adjustCsp("style-src 'none'"))
        assertEquals("default-src 'self' cdn.x; style-src 'self' cdn.x https://rules.adbrella.internal", p.adjustCsp("default-src 'self' cdn.x"))
        assertEquals("script-src 'self'", p.adjustCsp("script-src 'self'"))
    }
}
