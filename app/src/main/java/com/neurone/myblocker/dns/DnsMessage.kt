package com.neurone.myblocker.dns

import java.io.ByteArrayOutputStream

/** The first question of a DNS query, plus the byte offset where the question section ends. */
class DnsQuestion(
    val id: Int,
    val flags: Int,
    val name: String,
    val type: Int,
    val clazz: Int,
    val questionEnd: Int,
) {
    val typeName: String
        get() = when (type) {
            DnsMessage.TYPE_A -> "A"
            DnsMessage.TYPE_AAAA -> "AAAA"
            DnsMessage.TYPE_CNAME -> "CNAME"
            DnsMessage.TYPE_HTTPS -> "HTTPS"
            DnsMessage.TYPE_SVCB -> "SVCB"
            DnsMessage.TYPE_TXT -> "TXT"
            DnsMessage.TYPE_MX -> "MX"
            DnsMessage.TYPE_PTR -> "PTR"
            DnsMessage.TYPE_SRV -> "SRV"
            else -> "T$type"
        }
}

/** How blocked names are answered. */
enum class BlockMode {
    /** A -> 0.0.0.0, AAAA -> ::, everything else NODATA. Fastest failure for most SDKs (Pi-hole default). */
    NULL_IP,
    /** NXDOMAIN with a synthetic SOA so resolvers negative-cache it. */
    NXDOMAIN,
    /**
     * A -> 198.18.0.1, AAAA -> 2001:db8::1: public-looking addresses that Adbrella routes into its
     * own tunnel and refuses at once. Fails as fast as NULL_IP, but an app that checks whether ad
     * domains resolve to 0.0.0.0 / localhost (the usual "ad blocker detected" test) sees nothing odd.
     */
    INVISIBLE,
}

/**
 * Minimal, allocation-light DNS wire-format helpers. Only what a filtering
 * forwarder needs: read the question, synthesize block / failure answers,
 * and patch the transaction ID for DNS-over-HTTPS.
 */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_SOA = 6
    const val TYPE_PTR = 12
    const val TYPE_MX = 15
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    const val TYPE_SVCB = 64
    const val TYPE_HTTPS = 65
    const val CLASS_IN = 1

    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    private const val FLAG_QR = 0x8000
    private const val FLAG_AA = 0x0400
    private const val FLAG_TC = 0x0200
    private const val FLAG_RD = 0x0100
    private const val FLAG_RA = 0x0080

    const val HEADER_LENGTH = 12

    fun parseQuestion(data: ByteArray, len: Int = data.size): DnsQuestion? {
        if (len < HEADER_LENGTH) return null
        val id = u16(data, 0)
        val flags = u16(data, 2)
        if (flags and FLAG_QR != 0) return null // a response, not a query
        val qdCount = u16(data, 4)
        if (qdCount < 1) return null

        val sb = StringBuilder(64)
        var pos = HEADER_LENGTH
        var end = -1
        var jumps = 0
        while (true) {
            if (pos >= len) return null
            val l = data[pos].toInt() and 0xff
            if (l == 0) {
                pos++
                break
            }
            if (l and 0xC0 == 0xC0) {
                if (pos + 1 >= len) return null
                val ptr = ((l and 0x3f) shl 8) or (data[pos + 1].toInt() and 0xff)
                if (end < 0) end = pos + 2
                if (ptr >= pos) return null
                pos = ptr
                if (++jumps > 16) return null
                continue
            }
            if (l > 63 || pos + 1 + l > len) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until l) sb.append((data[pos + 1 + i].toInt() and 0xff).toChar())
            pos += 1 + l
        }
        if (end < 0) end = pos
        if (end + 4 > len) return null
        val type = u16(data, end)
        val clazz = u16(data, end + 2)
        return DnsQuestion(id, flags, sb.toString().lowercase(), type, clazz, end + 4)
    }

    /** Builds the answer returned for a blocked name. */
    /** Sinkhole addresses for [BlockMode.INVISIBLE]: benchmark range 198.18.0.0/15 and documentation prefix 2001:db8::/32. */
    val SINK_V4: ByteArray = byteArrayOf(198.toByte(), 18, 0, 1)
    val SINK_V6: ByteArray = byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)

    /** True for any address inside the sinkhole ranges, so the tunnel can refuse connections to them at once. */
    fun isSinkhole(a: ByteArray): Boolean = when (a.size) {
        4 -> a[0] == 198.toByte() && (a[1] == 18.toByte() || a[1] == 19.toByte())
        16 -> a[0] == 0x20.toByte() && a[1] == 0x01.toByte() && a[2] == 0x0d.toByte() && a[3] == 0xb8.toByte()
        else -> false
    }

    fun buildBlockedResponse(query: ByteArray, q: DnsQuestion, mode: BlockMode, ttl: Int = 60): ByteArray {
        val out = ByteArrayOutputStream(q.questionEnd + 64)
        val questionBytes = query.copyOfRange(HEADER_LENGTH, q.questionEnd)
        when (mode) {
            BlockMode.NULL_IP, BlockMode.INVISIBLE -> {
                val invisible = mode == BlockMode.INVISIBLE
                val answer: ByteArray? = when (q.type) {
                    TYPE_A -> if (invisible) SINK_V4 else ByteArray(4)
                    TYPE_AAAA -> if (invisible) SINK_V6 else ByteArray(16)
                    else -> null
                }
                writeHeader(out, q, RCODE_NOERROR, anCount = if (answer != null) 1 else 0, nsCount = if (answer == null) 1 else 0)
                out.write(questionBytes)
                if (answer != null) {
                    writeRecordHead(out, q.type, ttl, answer.size)
                    out.write(answer)
                } else {
                    writeSoa(out, ttl)
                }
            }
            BlockMode.NXDOMAIN -> {
                writeHeader(out, q, RCODE_NXDOMAIN, anCount = 0, nsCount = 1)
                out.write(questionBytes)
                writeSoa(out, ttl)
            }
        }
        return out.toByteArray()
    }

    /** Answers an A query with [ipv4]; AAAA and other types get NODATA. Used for the internal rules host. */
    fun buildAddressAnswer(query: ByteArray, q: DnsQuestion, ipv4: ByteArray, ttl: Int = 300): ByteArray {
        val out = ByteArrayOutputStream(q.questionEnd + 32)
        val questionBytes = query.copyOfRange(HEADER_LENGTH, q.questionEnd)
        if (q.type == TYPE_A) {
            writeHeader(out, q, RCODE_NOERROR, anCount = 1, nsCount = 0)
            out.write(questionBytes)
            writeRecordHead(out, TYPE_A, ttl, 4)
            out.write(ipv4)
        } else {
            writeHeader(out, q, RCODE_NOERROR, anCount = 0, nsCount = 1)
            out.write(questionBytes)
            writeSoa(out, ttl)
        }
        return out.toByteArray()
    }

    /** SERVFAIL for the given query; used when every upstream fails. */
    fun buildServfail(query: ByteArray, q: DnsQuestion): ByteArray {
        val out = ByteArrayOutputStream(q.questionEnd)
        writeHeader(out, q, RCODE_SERVFAIL, anCount = 0, nsCount = 0, authoritative = false)
        out.write(query, HEADER_LENGTH, q.questionEnd - HEADER_LENGTH)
        return out.toByteArray()
    }

    /** Header + question only, with TC set, so the stub resolver retries over TCP. */
    fun buildTruncated(query: ByteArray, q: DnsQuestion): ByteArray {
        val out = ByteArrayOutputStream(q.questionEnd)
        writeHeader(out, q, RCODE_NOERROR, anCount = 0, nsCount = 0, authoritative = false, truncated = true)
        out.write(query, HEADER_LENGTH, q.questionEnd - HEADER_LENGTH)
        return out.toByteArray()
    }

    fun id(data: ByteArray): Int = u16(data, 0)

    fun setId(data: ByteArray, id: Int) {
        data[0] = (id shr 8).toByte()
        data[1] = id.toByte()
    }

    fun rcode(data: ByteArray): Int = if (data.size >= 4) data[3].toInt() and 0x0f else -1

    // --- internals ---------------------------------------------------------

    private fun writeHeader(
        out: ByteArrayOutputStream,
        q: DnsQuestion,
        rcode: Int,
        anCount: Int,
        nsCount: Int,
        authoritative: Boolean = true,
        truncated: Boolean = false,
    ) {
        var flags = FLAG_QR or FLAG_RA or (q.flags and FLAG_RD) or (rcode and 0x0f)
        if (authoritative) flags = flags or FLAG_AA
        if (truncated) flags = flags or FLAG_TC
        u16(out, q.id)
        u16(out, flags)
        u16(out, 1) // QDCOUNT
        u16(out, anCount)
        u16(out, nsCount)
        u16(out, 0) // ARCOUNT
    }

    private fun writeRecordHead(out: ByteArrayOutputStream, type: Int, ttl: Int, rdLength: Int) {
        out.write(0xC0) // pointer to the question name at offset 12
        out.write(0x0C)
        u16(out, type)
        u16(out, CLASS_IN)
        u32(out, ttl.toLong())
        u16(out, rdLength)
    }

    private fun writeSoa(out: ByteArrayOutputStream, ttl: Int) {
        val rdata = ByteArrayOutputStream(64)
        writeName(rdata, "myblocker.invalid")
        writeName(rdata, "nobody.invalid")
        u32(rdata, 1) // serial
        u32(rdata, 3600) // refresh
        u32(rdata, 900) // retry
        u32(rdata, 604800) // expire
        u32(rdata, ttl.toLong()) // minimum / negative TTL
        val bytes = rdata.toByteArray()
        writeRecordHead(out, TYPE_SOA, ttl, bytes.size)
        out.write(bytes)
    }

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            out.write(label.length)
            for (c in label) out.write(c.code)
        }
        out.write(0)
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xff)
        out.write(v and 0xff)
    }

    private fun u32(out: ByteArrayOutputStream, v: Long) {
        out.write(((v shr 24) and 0xff).toInt())
        out.write(((v shr 16) and 0xff).toInt())
        out.write(((v shr 8) and 0xff).toInt())
        out.write((v and 0xff).toInt())
    }

    private fun u16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)
}
