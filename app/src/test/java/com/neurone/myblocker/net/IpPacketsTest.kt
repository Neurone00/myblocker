package com.neurone.myblocker.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPacketsTest {
    private fun v4Udp(payload: ByteArray, srcPort: Int = 40000, dstPort: Int = 53): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[8] = 64; p[9] = 17
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        p[20] = (srcPort shr 8).toByte(); p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte(); p[23] = dstPort.toByte()
        val ul = 8 + payload.size
        p[24] = (ul shr 8).toByte(); p[25] = ul.toByte()
        payload.copyInto(p, 28)
        return p
    }

    @Test fun parsesV4Udp() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val parsed = IpPackets.parse(v4Udp(payload), 33)
        assertTrue(parsed is ParsedPacket.Udp)
        val d = (parsed as ParsedPacket.Udp).datagram
        assertEquals(4, d.version)
        assertEquals(40000, d.srcPort)
        assertEquals(53, d.dstPort)
        assertEquals(28, d.payloadOffset)
        assertEquals(5, d.payloadLength)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), d.dst)
    }

    @Test fun replySwapsAddressesAndHasValidChecksums() {
        val parsed = IpPackets.parse(v4Udp(byteArrayOf(9, 9)), 30) as ParsedPacket.Udp
        val reply = IpPackets.buildUdpReply(parsed.datagram, byteArrayOf(7, 7, 7))
        assertEquals(31, reply.size)
        assertEquals(0x45, reply[0].toInt())
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), reply.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), reply.copyOfRange(16, 20))
        assertEquals(53, IpPackets.u16(reply, 20))
        assertEquals(40000, IpPackets.u16(reply, 22))
        // IP header checksum verifies to zero
        assertEquals(0, IpPackets.checksum(null, reply, 20))
        // reply parses back as UDP with swapped ports
        val back = IpPackets.parse(reply, reply.size) as ParsedPacket.Udp
        assertEquals(53, back.datagram.srcPort)
        assertEquals(3, back.datagram.payloadLength)
    }

    @Test fun v6RoundTrip() {
        val payload = byteArrayOf(1, 2, 3)
        val p = ByteArray(40 + 8 + payload.size)
        p[0] = 0x60
        p[4] = 0; p[5] = (8 + payload.size).toByte()
        p[6] = 17; p[7] = 64
        val src = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
        val dst = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        src.copyInto(p, 8); dst.copyInto(p, 24)
        p[40] = 0x9c.toByte(); p[41] = 0x40.toByte() // 40000
        p[42] = 0; p[43] = 53
        p[44] = 0; p[45] = (8 + payload.size).toByte()
        payload.copyInto(p, 48)
        val parsed = IpPackets.parse(p, p.size) as ParsedPacket.Udp
        assertEquals(6, parsed.datagram.version)
        assertEquals(40000, parsed.datagram.srcPort)
        val reply = IpPackets.buildUdpReply(parsed.datagram, byteArrayOf(5))
        assertEquals(49, reply.size)
        assertArrayEquals(dst, reply.copyOfRange(8, 24))
        assertArrayEquals(src, reply.copyOfRange(24, 40))
        val back = IpPackets.parse(reply, reply.size) as ParsedPacket.Udp
        assertEquals(53, back.datagram.srcPort)
        assertEquals(1, back.datagram.payloadLength)
    }

    @Test fun tcpSynBecomesRst() {
        val p = ByteArray(40)
        p[0] = 0x45; p[2] = 0; p[3] = 40; p[9] = 6
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        p[20] = 0x9c.toByte(); p[21] = 0x40.toByte()
        p[22] = 0x03; p[23] = 0x55 // 853
        p[24] = 0; p[25] = 0; p[26] = 0x10; p[27] = 0x00 // seq 4096
        p[32] = 0x50; p[33] = 0x02 // data offset 5, SYN
        val parsed = IpPackets.parse(p, 40)
        assertTrue(parsed is ParsedPacket.Tcp)
        val syn = (parsed as ParsedPacket.Tcp).segment
        assertTrue(syn.syn)
        assertEquals(853, syn.dstPort)
        assertEquals(4096L, syn.seq)
        assertEquals(0, syn.payloadLength)
        val rst = IpPackets.buildTcpRst(syn)
        assertEquals(40, rst.size)
        assertEquals(0x14, rst[33].toInt())
        assertEquals(4097L, IpPackets.u32(rst, 28))
        assertEquals(853, IpPackets.u16(rst, 20))
        // checksum of the built segment verifies to zero over the pseudo header
        val pseudo = ByteArray(12).also { byteArrayOf(10, 111, 222.toByte(), 2).copyInto(it, 0); byteArrayOf(10, 111, 222.toByte(), 1).copyInto(it, 4); it[9] = 6; it[11] = 20 }
        assertEquals(0, IpPackets.checksum(pseudo, rst.copyOfRange(20, 40), 20))
    }

    @Test fun tcpBuildParseRoundTrip() {
        val src = byteArrayOf(10, 111, 222.toByte(), 1)
        val dst = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
        val payload = "GET / HTTP/1.1\r\n".toByteArray()
        val pkt = IpPackets.buildTcp(4, src, dst, 51000, 443, 0xFFFFFFF0L, 77L, IpPackets.TCP_PSH or IpPackets.TCP_ACK, 65535, payload, 0, payload.size)
        val parsed = IpPackets.parse(pkt, pkt.size) as ParsedPacket.Tcp
        val s = parsed.segment
        assertEquals(51000, s.srcPort)
        assertEquals(443, s.dstPort)
        assertEquals(0xFFFFFFF0L, s.seq)
        assertEquals(77L, s.ack)
        assertTrue(s.ackFlag)
        assertTrue(!s.syn)
        assertEquals(65535, s.window)
        assertEquals(payload.size, s.payloadLength)
        assertArrayEquals(payload, pkt.copyOfRange(s.payloadOffset, s.payloadOffset + s.payloadLength))
        assertEquals(0, IpPackets.checksum(null, pkt, 20))

        val synAck = IpPackets.buildTcp(4, dst, src, 443, 51000, 1000L, 2000L, IpPackets.TCP_SYN or IpPackets.TCP_ACK, 65535, null, 0, 0, mss = 1400)
        val sa = (IpPackets.parse(synAck, synAck.size) as ParsedPacket.Tcp).segment
        assertTrue(sa.syn && sa.ackFlag)
        assertEquals(1400, sa.mss)
        assertEquals(0, sa.payloadLength)
        assertEquals(44, synAck.size)
    }

    @Test fun ignoresFragmentsAndOtherProtocols() {
        val p = v4Udp(byteArrayOf(1))
        p[6] = 0x00; p[7] = 0x01 // fragment offset 1
        assertTrue(IpPackets.parse(p, p.size) is ParsedPacket.Other)
        val icmp = v4Udp(byteArrayOf(1)); icmp[9] = 1
        assertTrue(IpPackets.parse(icmp, icmp.size) is ParsedPacket.Other)
        assertTrue(IpPackets.parse(ByteArray(10), 10) is ParsedPacket.Other)
    }
}
