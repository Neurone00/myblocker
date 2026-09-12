package com.neurone.myblocker.dns

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvisibleAnswerTest {
    private fun query(name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.trimEnd('.').split('.')) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0)
        out.write(byteArrayOf(0, type.toByte(), 0, 1))
        return out.toByteArray()
    }

    private fun answerCount(r: ByteArray) = ((r[6].toInt() and 0xff) shl 8) or (r[7].toInt() and 0xff)
    private fun authorityCount(r: ByteArray) = ((r[8].toInt() and 0xff) shl 8) or (r[9].toInt() and 0xff)

    @Test fun aRecordPointsAtTheSinkhole() {
        val q = query("ads.example.com.", 1)
        val r = DnsMessage.buildBlockedResponse(q, DnsMessage.parseQuestion(q)!!, BlockMode.INVISIBLE)
        assertEquals(0, DnsMessage.rcode(r))
        assertEquals(1, answerCount(r))
        assertArrayEquals(byteArrayOf(198.toByte(), 18, 0, 1), r.copyOfRange(r.size - 4, r.size))
    }

    @Test fun aaaaRecordPointsAtTheSinkhole() {
        val q = query("ads.example.com.", 28)
        val r = DnsMessage.buildBlockedResponse(q, DnsMessage.parseQuestion(q)!!, BlockMode.INVISIBLE)
        assertEquals(1, answerCount(r))
        assertArrayEquals(DnsMessage.SINK_V6, r.copyOfRange(r.size - 16, r.size))
    }

    @Test fun otherTypesGetNodata() {
        val q = query("ads.example.com.", 16)
        val r = DnsMessage.buildBlockedResponse(q, DnsMessage.parseQuestion(q)!!, BlockMode.INVISIBLE)
        assertEquals(0, DnsMessage.rcode(r))
        assertEquals(0, answerCount(r))
        assertEquals(1, authorityCount(r))
    }

    @Test fun sinkholeRangesAreRecognised() {
        assertTrue(DnsMessage.isSinkhole(byteArrayOf(198.toByte(), 18, 0, 1)))
        assertTrue(DnsMessage.isSinkhole(byteArrayOf(198.toByte(), 19, 255.toByte(), 254.toByte())))
        assertFalse(DnsMessage.isSinkhole(byteArrayOf(198.toByte(), 20, 0, 1)))
        assertFalse(DnsMessage.isSinkhole(byteArrayOf(0, 0, 0, 0)))
        assertTrue(DnsMessage.isSinkhole(DnsMessage.SINK_V6))
        assertFalse(DnsMessage.isSinkhole(ByteArray(16)))
    }
}
