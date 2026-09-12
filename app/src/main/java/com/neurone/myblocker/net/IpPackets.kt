package com.neurone.myblocker.net

/** A UDP datagram as seen on the TUN device. Addresses are raw 4- or 16-byte arrays. */
class UdpDatagram(
    val version: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

/** A TCP SYN as seen on the TUN device; only what is needed to answer with RST. */
class TcpSyn(
    val version: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val seq: Long,
)

sealed class ParsedPacket {
    class Udp(val datagram: UdpDatagram) : ParsedPacket()
    class Syn(val syn: TcpSyn) : ParsedPacket()
    object Other : ParsedPacket()
}

/**
 * Tiny IPv4/IPv6 + UDP/TCP codec. The VPN only ever carries DNS (and the odd
 * TCP probe on port 53/853), so a full stack is not needed.
 */
object IpPackets {
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17

    fun parse(buf: ByteArray, len: Int): ParsedPacket {
        if (len < 20) return ParsedPacket.Other
        return when ((buf[0].toInt() and 0xf0) shr 4) {
            4 -> parseV4(buf, len)
            6 -> parseV6(buf, len)
            else -> ParsedPacket.Other
        }
    }

    private fun parseV4(buf: ByteArray, len: Int): ParsedPacket {
        val ihl = (buf[0].toInt() and 0x0f) * 4
        if (ihl < 20 || ihl > len) return ParsedPacket.Other
        val totalLen = minOf(u16(buf, 2), len)
        if (u16(buf, 6) and 0x1fff != 0) return ParsedPacket.Other // fragment; ignore
        val proto = buf[9].toInt() and 0xff
        val src = buf.copyOfRange(12, 16)
        val dst = buf.copyOfRange(16, 20)
        return parseTransport(4, proto, buf, ihl, totalLen, src, dst)
    }

    private fun parseV6(buf: ByteArray, len: Int): ParsedPacket {
        if (len < 40) return ParsedPacket.Other
        val payloadLen = u16(buf, 4)
        val end = minOf(40 + payloadLen, len)
        val next = buf[6].toInt() and 0xff
        val src = buf.copyOfRange(8, 24)
        val dst = buf.copyOfRange(24, 40)
        return parseTransport(6, next, buf, 40, end, src, dst)
    }

    private fun parseTransport(
        version: Int, proto: Int, buf: ByteArray, off: Int, end: Int, src: ByteArray, dst: ByteArray,
    ): ParsedPacket {
        when (proto) {
            PROTO_UDP -> {
                if (off + 8 > end) return ParsedPacket.Other
                val srcPort = u16(buf, off)
                val dstPort = u16(buf, off + 2)
                val udpLen = u16(buf, off + 4)
                val payloadLen = minOf(udpLen - 8, end - off - 8)
                if (payloadLen < 0) return ParsedPacket.Other
                return ParsedPacket.Udp(UdpDatagram(version, src, dst, srcPort, dstPort, off + 8, payloadLen))
            }
            PROTO_TCP -> {
                if (off + 20 > end) return ParsedPacket.Other
                val flags = buf[off + 13].toInt() and 0xff
                val syn = flags and 0x02 != 0
                val ack = flags and 0x10 != 0
                if (!syn || ack) return ParsedPacket.Other
                val seq = u32(buf, off + 4)
                return ParsedPacket.Syn(TcpSyn(version, src, dst, u16(buf, off), u16(buf, off + 2), seq))
            }
            else -> return ParsedPacket.Other
        }
    }

    /** Builds a UDP reply to [req] (addresses and ports swapped) carrying [payload]. */
    fun buildUdpReply(req: UdpDatagram, payload: ByteArray, payloadLen: Int = payload.size): ByteArray {
        val udpLen = 8 + payloadLen
        val udp = ByteArray(udpLen)
        put16(udp, 0, req.dstPort)
        put16(udp, 2, req.srcPort)
        put16(udp, 4, udpLen)
        System.arraycopy(payload, 0, udp, 8, payloadLen)
        // src/dst swapped: reply goes from the original destination back to the sender
        val sum = checksum(pseudoHeader(req.version, req.dst, req.src, PROTO_UDP, udpLen), udp, udpLen)
        put16(udp, 6, if (sum == 0) 0xffff else sum)
        return wrapIp(req.version, req.dst, req.src, PROTO_UDP, udp)
    }

    /** Builds a TCP RST/ACK answering [syn], so the peer fails fast instead of timing out. */
    fun buildTcpRst(syn: TcpSyn): ByteArray {
        val tcp = ByteArray(20)
        put16(tcp, 0, syn.dstPort)
        put16(tcp, 2, syn.srcPort)
        put32(tcp, 4, 0) // seq
        put32(tcp, 8, (syn.seq + 1) and 0xffffffffL) // ack
        tcp[12] = (5 shl 4).toByte() // data offset 5 words
        tcp[13] = 0x14 // RST + ACK
        put16(tcp, 14, 0) // window
        val sum = checksum(pseudoHeader(syn.version, syn.dst, syn.src, PROTO_TCP, 20), tcp, 20)
        put16(tcp, 16, sum)
        return wrapIp(syn.version, syn.dst, syn.src, PROTO_TCP, tcp)
    }

    private fun wrapIp(version: Int, src: ByteArray, dst: ByteArray, proto: Int, transport: ByteArray): ByteArray {
        return if (version == 4) {
            val total = 20 + transport.size
            val pkt = ByteArray(total)
            pkt[0] = 0x45
            pkt[1] = 0
            put16(pkt, 2, total)
            put16(pkt, 4, 0) // id
            put16(pkt, 6, 0x4000) // DF
            pkt[8] = 64 // TTL
            pkt[9] = proto.toByte()
            System.arraycopy(src, 0, pkt, 12, 4)
            System.arraycopy(dst, 0, pkt, 16, 4)
            put16(pkt, 10, checksum(null, pkt, 20))
            System.arraycopy(transport, 0, pkt, 20, transport.size)
            pkt
        } else {
            val pkt = ByteArray(40 + transport.size)
            pkt[0] = 0x60
            put16(pkt, 4, transport.size)
            pkt[6] = proto.toByte()
            pkt[7] = 64
            System.arraycopy(src, 0, pkt, 8, 16)
            System.arraycopy(dst, 0, pkt, 24, 16)
            System.arraycopy(transport, 0, pkt, 40, transport.size)
            pkt
        }
    }

    private fun pseudoHeader(version: Int, src: ByteArray, dst: ByteArray, proto: Int, length: Int): ByteArray {
        return if (version == 4) {
            val p = ByteArray(12)
            System.arraycopy(src, 0, p, 0, 4)
            System.arraycopy(dst, 0, p, 4, 4)
            p[9] = proto.toByte()
            put16(p, 10, length)
            p
        } else {
            val p = ByteArray(40)
            System.arraycopy(src, 0, p, 0, 16)
            System.arraycopy(dst, 0, p, 16, 16)
            put32(p, 32, length.toLong())
            p[39] = proto.toByte()
            p
        }
    }

    /** Internet checksum over an optional pseudo header followed by [len] bytes of [data]. */
    fun checksum(pseudo: ByteArray?, data: ByteArray, len: Int): Int {
        var sum = 0L
        if (pseudo != null) {
            var i = 0
            while (i + 1 < pseudo.size) {
                sum += ((pseudo[i].toInt() and 0xff) shl 8) or (pseudo[i + 1].toInt() and 0xff)
                i += 2
            }
        }
        var i = 0
        while (i + 1 < len) {
            sum += ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i < len) sum += (data[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)

    fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xff) shl 24) or ((b[off + 1].toLong() and 0xff) shl 16) or
            ((b[off + 2].toLong() and 0xff) shl 8) or (b[off + 3].toLong() and 0xff)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v shr 24).toByte()
        b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
