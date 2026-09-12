package com.neurone.myblocker.proxy

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress

/** Reads a TLS ClientHello far enough to extract the server name, keeping the bytes for replay. */
object TlsPeek {
    class Hello(val sni: String?, val consumed: ByteArray)

    /** Returns null if the stream does not start with a TLS handshake record. */
    fun readClientHello(input: InputStream): Hello? {
        val out = ByteArrayOutputStream(1024)
        val head = ByteArray(5)
        if (!readFully(input, head, out)) return null
        if (head[0].toInt() != 0x16) return Hello(null, out.toByteArray()) // not a handshake record
        val recordLen = ((head[3].toInt() and 0xff) shl 8) or (head[4].toInt() and 0xff)
        if (recordLen < 4 || recordLen > 16384) return Hello(null, out.toByteArray())
        val body = ByteArray(recordLen)
        if (!readFully(input, body, out)) return Hello(null, out.toByteArray())
        return Hello(parseSni(body), out.toByteArray())
    }

    private fun readFully(input: InputStream, buf: ByteArray, copy: ByteArrayOutputStream): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        copy.write(buf)
        return true
    }

    private fun parseSni(b: ByteArray): String? {
        try {
            var p = 0
            if (b[p].toInt() != 0x01) return null // ClientHello
            p += 4 // type + 3-byte length
            p += 2 // client version
            p += 32 // random
            val sidLen = b[p].toInt() and 0xff; p += 1 + sidLen
            val csLen = u16(b, p); p += 2 + csLen
            val cmLen = b[p].toInt() and 0xff; p += 1 + cmLen
            if (p + 2 > b.size) return null
            val extLen = u16(b, p); p += 2
            val end = minOf(p + extLen, b.size)
            while (p + 4 <= end) {
                val type = u16(b, p)
                val len = u16(b, p + 2)
                p += 4
                if (type == 0) { // server_name
                    var q = p + 2 // list length
                    while (q + 3 <= p + len) {
                        val nameType = b[q].toInt() and 0xff
                        val nameLen = u16(b, q + 1)
                        q += 3
                        if (nameType == 0 && q + nameLen <= b.size) {
                            return String(b, q, nameLen, Charsets.US_ASCII).lowercase()
                        }
                        q += nameLen
                    }
                    return null
                }
                p += len
            }
        } catch (e: IndexOutOfBoundsException) {
            return null
        }
        return null
    }

    private fun u16(b: ByteArray, off: Int): Int = ((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)
}

/**
 * A socket view that replays already-consumed bytes before the live stream, so a TLS
 * socket can be layered on top of a connection whose ClientHello was peeked at.
 */
class PrefixedSocket(private val inner: Socket, private val input: InputStream) : Socket() {
    private val output: OutputStream = inner.getOutputStream()

    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun isConnected(): Boolean = inner.isConnected
    override fun isClosed(): Boolean = inner.isClosed
    override fun isBound(): Boolean = inner.isBound
    override fun getInetAddress(): InetAddress? = inner.inetAddress
    override fun getPort(): Int = inner.port
    override fun getLocalPort(): Int = inner.localPort
    override fun getLocalAddress(): InetAddress? = inner.localAddress
    override fun getRemoteSocketAddress(): SocketAddress? = inner.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = inner.localSocketAddress
    override fun getSoTimeout(): Int = inner.soTimeout
    override fun setSoTimeout(timeout: Int) { inner.soTimeout = timeout }
    override fun getTcpNoDelay(): Boolean = inner.tcpNoDelay
    override fun setTcpNoDelay(on: Boolean) { inner.tcpNoDelay = on }
    override fun getKeepAlive(): Boolean = inner.keepAlive
    override fun setKeepAlive(on: Boolean) { inner.keepAlive = on }
    override fun getReceiveBufferSize(): Int = inner.receiveBufferSize
    override fun getSendBufferSize(): Int = inner.sendBufferSize
    override fun getSoLinger(): Int = inner.soLinger
    override fun setSoLinger(on: Boolean, linger: Int) { inner.setSoLinger(on, linger) }
    override fun shutdownInput() { inner.shutdownInput() }
    override fun shutdownOutput() { inner.shutdownOutput() }
    override fun isInputShutdown(): Boolean = inner.isInputShutdown
    override fun isOutputShutdown(): Boolean = inner.isOutputShutdown
    override fun close() { inner.close() }
    override fun connect(endpoint: SocketAddress?) = throw UnsupportedOperationException("already connected")
    override fun connect(endpoint: SocketAddress?, timeout: Int) = throw UnsupportedOperationException("already connected")
}
