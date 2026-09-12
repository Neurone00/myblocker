package com.neurone.myblocker.tls

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Just enough DER encoding to write X.509 certificates. */
object Der {
    const val TAG_BOOLEAN = 0x01
    const val TAG_INTEGER = 0x02
    const val TAG_BIT_STRING = 0x03
    const val TAG_OCTET_STRING = 0x04
    const val TAG_NULL = 0x05
    const val TAG_OID = 0x06
    const val TAG_UTF8_STRING = 0x0C
    const val TAG_PRINTABLE_STRING = 0x13
    const val TAG_UTC_TIME = 0x17
    const val TAG_GENERALIZED_TIME = 0x18
    const val TAG_SEQUENCE = 0x30
    const val TAG_SET = 0x31

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        val len = content.size
        if (len < 0x80) {
            out.write(len)
        } else {
            val bytes = ArrayList<Int>()
            var l = len
            while (l > 0) {
                bytes.add(l and 0xff)
                l = l shr 8
            }
            out.write(0x80 or bytes.size)
            for (i in bytes.indices.reversed()) out.write(bytes[i])
        }
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg items: ByteArray): ByteArray = tlv(TAG_SEQUENCE, concat(*items))
    fun set(vararg items: ByteArray): ByteArray = tlv(TAG_SET, concat(*items))
    fun explicit(n: Int, content: ByteArray): ByteArray = tlv(0xA0 or n, content)
    fun implicit(n: Int, content: ByteArray): ByteArray = tlv(0x80 or n, content)
    fun nullValue(): ByteArray = byteArrayOf(TAG_NULL.toByte(), 0)
    fun boolean(v: Boolean): ByteArray = tlv(TAG_BOOLEAN, byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun integer(v: Long): ByteArray = integer(BigInteger.valueOf(v))
    fun integer(v: BigInteger): ByteArray = tlv(TAG_INTEGER, v.toByteArray())
    fun octetString(b: ByteArray): ByteArray = tlv(TAG_OCTET_STRING, b)
    fun bitString(b: ByteArray): ByteArray = tlv(TAG_BIT_STRING, byteArrayOf(0) + b)
    fun utf8(s: String): ByteArray = tlv(TAG_UTF8_STRING, s.toByteArray(Charsets.UTF_8))
    fun printable(s: String): ByteArray = tlv(TAG_PRINTABLE_STRING, s.toByteArray(Charsets.US_ASCII))

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val chunk = ArrayList<Int>()
            do {
                chunk.add((v and 0x7f).toInt())
                v = v shr 7
            } while (v > 0)
            for (j in chunk.indices.reversed()) out.write(chunk[j] or if (j > 0) 0x80 else 0)
        }
        return tlv(TAG_OID, out.toByteArray())
    }

    /** UTCTime for years before 2050, GeneralizedTime afterwards, as X.509 requires. */
    fun time(d: Date): ByteArray {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.time = d
        val year = cal.get(java.util.Calendar.YEAR)
        return if (year < 2050) {
            val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            tlv(TAG_UTC_TIME, f.format(d).toByteArray(Charsets.US_ASCII))
        } else {
            val f = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            tlv(TAG_GENERALIZED_TIME, f.format(d).toByteArray(Charsets.US_ASCII))
        }
    }

    /** X.501 Name from (oid, value) attribute pairs, one RDN each. */
    fun name(vararg attrs: Pair<String, String>): ByteArray =
        sequence(*attrs.map { (o, v) -> set(sequence(oid(o), utf8(v))) }.toTypedArray())

    fun concat(vararg items: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(items.sumOf { it.size })
        for (i in items) out.write(i)
        return out.toByteArray()
    }
}
