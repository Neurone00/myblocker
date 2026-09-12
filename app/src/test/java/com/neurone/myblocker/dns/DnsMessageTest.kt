package com.neurone.myblocker.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {
    private fun query(name: String, type: Int = DnsMessage.TYPE_A, id: Int = 0x1234): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xff)
        out.write(0x01); out.write(0x00) // RD
        out.write(0); out.write(1) // QDCOUNT
        out.write(0); out.write(0); out.write(0); out.write(0); out.write(0); out.write(0)
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xff)
        out.write(0); out.write(1)
        return out.toByteArray()
    }

    @Test fun parsesQuestion() {
        val q = DnsMessage.parseQuestion(query("Ads.Example.COM"))
        assertNotNull(q)
        assertEquals("ads.example.com", q!!.name)
        assertEquals(0x1234, q.id)
        assertEquals(DnsMessage.TYPE_A, q.type)
        assertEquals("A", q.typeName)
        assertEquals(12 + 17 + 4, q.questionEnd)
    }

    @Test fun rejectsResponsesAndGarbage() {
        val r = query("a.b")
        r[2] = (r[2].toInt() or 0x80).toByte()
        assertNull(DnsMessage.parseQuestion(r))
        assertNull(DnsMessage.parseQuestion(ByteArray(5)))
        assertNull(DnsMessage.parseQuestion(query("a.b").copyOf(14)))
    }

    @Test fun nullIpAnswerForA() {
        val raw = query("ads.example.com")
        val q = DnsMessage.parseQuestion(raw)!!
        val resp = DnsMessage.buildBlockedResponse(raw, q, BlockMode.NULL_IP, ttl = 60)
        assertEquals(0x1234, DnsMessage.id(resp))
        assertTrue("QR bit set", resp[2].toInt() and 0x80 != 0)
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(resp))
        assertEquals(1, resp[7].toInt()) // ANCOUNT
        assertEquals(0, resp[9].toInt()) // NSCOUNT
        // question copied verbatim
        assertArrayEquals(raw.copyOfRange(12, q.questionEnd), resp.copyOfRange(12, q.questionEnd))
        val rr = q.questionEnd
        assertEquals(0xC0, resp[rr].toInt() and 0xff)
        assertEquals(0x0C, resp[rr + 1].toInt())
        assertEquals(DnsMessage.TYPE_A, resp[rr + 3].toInt())
        assertEquals(60, resp[rr + 9].toInt())
        assertEquals(4, resp[rr + 11].toInt())
        assertArrayEquals(ByteArray(4), resp.copyOfRange(rr + 12, rr + 16))
        assertEquals(rr + 16, resp.size)
    }

    @Test fun nullIpAnswerForAAAAAndOthers() {
        val raw = query("ads.example.com", DnsMessage.TYPE_AAAA)
        val q = DnsMessage.parseQuestion(raw)!!
        val resp = DnsMessage.buildBlockedResponse(raw, q, BlockMode.NULL_IP)
        assertEquals(1, resp[7].toInt())
        assertEquals(16, resp[q.questionEnd + 11].toInt())

        val https = query("ads.example.com", DnsMessage.TYPE_HTTPS)
        val q2 = DnsMessage.parseQuestion(https)!!
        val resp2 = DnsMessage.buildBlockedResponse(https, q2, BlockMode.NULL_IP)
        assertEquals(0, resp2[7].toInt())
        assertEquals(1, resp2[9].toInt()) // SOA in authority
        assertEquals(DnsMessage.RCODE_NOERROR, DnsMessage.rcode(resp2))
    }

    @Test fun nxdomainAnswer() {
        val raw = query("ads.example.com")
        val q = DnsMessage.parseQuestion(raw)!!
        val resp = DnsMessage.buildBlockedResponse(raw, q, BlockMode.NXDOMAIN)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, DnsMessage.rcode(resp))
        assertEquals(0, resp[7].toInt())
        assertEquals(1, resp[9].toInt())
        assertEquals(DnsMessage.TYPE_SOA, resp[q.questionEnd + 3].toInt())
    }

    @Test fun servfailAndTruncated() {
        val raw = query("x.example.com")
        val q = DnsMessage.parseQuestion(raw)!!
        val sf = DnsMessage.buildServfail(raw, q)
        assertEquals(DnsMessage.RCODE_SERVFAIL, DnsMessage.rcode(sf))
        assertEquals(q.questionEnd, sf.size)
        val tc = DnsMessage.buildTruncated(raw, q)
        assertTrue(tc[2].toInt() and 0x02 != 0)
    }

    @Test fun idRoundTrip() {
        val raw = query("x.example.com")
        DnsMessage.setId(raw, 0)
        assertEquals(0, DnsMessage.id(raw))
        DnsMessage.setId(raw, 0xBEEF)
        assertEquals(0xBEEF, DnsMessage.id(raw))
    }
}
