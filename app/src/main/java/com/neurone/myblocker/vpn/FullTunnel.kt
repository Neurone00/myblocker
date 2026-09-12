package com.neurone.myblocker.vpn

import android.util.Log
import com.neurone.myblocker.net.IpPackets
import com.neurone.myblocker.net.ParsedPacket
import com.neurone.myblocker.net.TcpSegment
import com.neurone.myblocker.net.UdpDatagram
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Userspace TCP/UDP relay for "deep clean" mode, where every packet of every app
 * enters the tunnel. Each TCP connection from an app becomes a real socket to the
 * destination; bytes are copied in both directions and the app sees an ordinary
 * TCP peer. Because the TUN path is loss-free, the state machine can stay small:
 * no retransmission timers, in-order delivery assumed, simple window accounting.
 *
 * All flow state is owned by the selector thread. The packet loop hands packets
 * in through [offer]; replies go out through [writeToTun].
 */
class FullTunnel(
    private val protectTcp: (Socket) -> Boolean,
    private val protectUdp: (DatagramSocket) -> Boolean,
    private val writeToTun: (ByteArray) -> Unit,
    private val policy: Policy,
) : Runnable {
    /** Decisions the service makes about individual flows. */
    interface Policy {
        /** Answer with RST instead of relaying (e.g. DNS-over-TLS to captured resolvers). */
        fun resetTcp(dst: ByteArray, dstPort: Int): Boolean
        /** Silently drop this datagram (e.g. QUIC from a browser we intercept). */
        fun dropUdp(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Boolean
        /** Send this new TCP flow to the local intercepting proxy instead of the real destination. */
        fun intercept(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Boolean = false
        /** Called once per new TCP flow. May be used for stats. */
        fun onTcpFlow(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int) {}
    }

    /** Where intercepted flows go, and how the proxy learns their original destination. */
    interface Redirect {
        val address: InetSocketAddress
        fun register(localPort: Int, dst: ByteArray, dstPort: Int)
    }

    /** Set before the relay starts; null means nothing is intercepted. */
    @Volatile var redirect: Redirect? = null

    private val selector: Selector = Selector.open()
    private val inbound = ConcurrentLinkedQueue<ByteArray>()
    private val tcpFlows = HashMap<String, TcpFlow>()
    private val udpFlows = HashMap<String, UdpFlow>()
    private val readBuffer = ByteBuffer.allocateDirect(65535)
    @Volatile private var running = true
    @Volatile var tcpCount = 0; private set
    @Volatile var udpCount = 0; private set

    fun offer(packet: ByteArray, len: Int) {
        if (!running) return
        if (inbound.size > MAX_QUEUE) { inbound.poll(); dropped++ }
        inbound.add(packet.copyOf(len))
        selector.wakeup()
    }

    fun stop() {
        running = false
        selector.wakeup()
    }

    @Volatile private var dropped = 0L
    private var lastSweep = 0L

    override fun run() {
        Log.i(TAG, "relay started")
        try {
            while (running) {
                // With open flows we wake every 15s to expire idle ones; with none there is nothing to
                // expire, so we block indefinitely and offer() wakes us. Either way an idle phone gets
                // no periodic wakeups from the relay and the CPU can stay asleep.
                val hasFlows = tcpFlows.isNotEmpty() || udpFlows.isNotEmpty()
                if (hasFlows) selector.select(15_000) else selector.select()
                if (!running) break
                var n = 0
                while (n++ < 512) {
                    val pkt = inbound.poll() ?: break
                    handlePacket(pkt)
                }
                val keys = selector.selectedKeys()
                val it = keys.iterator()
                while (it.hasNext()) {
                    val key = it.next()
                    it.remove()
                    if (!key.isValid) continue
                    when (val att = key.attachment()) {
                        is TcpFlow -> att.onSelected(key)
                        is UdpFlow -> att.onSelected(key)
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastSweep > 10_000) {
                    lastSweep = now
                    sweep(now)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "relay loop died", e)
        } finally {
            for (f in tcpFlows.values.toList()) f.close()
            for (f in udpFlows.values.toList()) f.close()
            runCatching { selector.close() }
            Log.i(TAG, "relay stopped (dropped=$dropped)")
        }
    }

    private fun handlePacket(pkt: ByteArray) {
        when (val p = IpPackets.parse(pkt, pkt.size)) {
            is ParsedPacket.Tcp -> handleTcp(p.segment, pkt)
            is ParsedPacket.Udp -> handleUdp(p.datagram, pkt)
            ParsedPacket.Other -> Unit
        }
    }

    private fun flowKey(version: Int, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): String {
        val sb = StringBuilder(64)
        sb.append(version).append('|')
        for (b in src) sb.append((b.toInt() and 0xff).toString(16)).append('.')
        sb.append(srcPort).append('>')
        for (b in dst) sb.append((b.toInt() and 0xff).toString(16)).append('.')
        sb.append(dstPort)
        return sb.toString()
    }

    // ----------------------------------------------------------------- TCP

    private fun handleTcp(seg: TcpSegment, pkt: ByteArray) {
        val key = flowKey(seg.version, seg.src, seg.srcPort, seg.dst, seg.dstPort)
        val flow = tcpFlows[key]
        if (flow == null) {
            if (seg.rst) return
            if (!seg.syn || seg.ackFlag) {
                // Stray segment for a flow we do not know: tell the app to start over.
                writeToTun(IpPackets.buildTcpRst(seg))
                return
            }
            if (policy.resetTcp(seg.dst, seg.dstPort)) {
                writeToTun(IpPackets.buildTcpRst(seg))
                return
            }
            if (tcpFlows.size >= MAX_TCP_FLOWS) {
                writeToTun(IpPackets.buildTcpRst(seg))
                return
            }
            val f = TcpFlow(key, seg)
            tcpFlows[key] = f
            tcpCount = tcpFlows.size
            policy.onTcpFlow(seg.src, seg.srcPort, seg.dst, seg.dstPort)
            f.open()
            return
        }
        flow.onClient(seg, pkt)
    }

    private enum class State { CONNECTING, ESTABLISHED, CLOSED }

    private inner class TcpFlow(val key: String, syn: TcpSegment) {
        private val version = syn.version
        private val clientAddr = syn.src
        private val serverAddr = syn.dst
        private val clientPort = syn.srcPort
        private val serverPort = syn.dstPort
        private val mss = minOf(if (syn.mss > 0) syn.mss else 1220, if (version == 4) 3900 else 3880)
        private var channel: SocketChannel? = null
        private var selKey: SelectionKey? = null
        private var state = State.CONNECTING
        /** Next sequence number we expect from the client (= our ACK number). */
        private var clientSeq = seqAdd(syn.seq, 1)
        /** Next sequence number we will send. */
        private var serverSeq = Random.nextLong(1L, 0xFFFF0000L)
        private val isn = serverSeq
        private var clientAck = 0L
        private var clientWindow = syn.window
        private val toServer = ArrayDeque<ByteBuffer>()
        private var pendingBytes = 0
        private var clientFin = false
        private var finSent = false
        private var finAcked = false
        private var synAckSent = false
        private var advertisedZero = false
        var lastActivity = System.currentTimeMillis()
        private var readPaused = false

        fun open() {
            try {
                val ch = SocketChannel.open()
                ch.configureBlocking(false)
                protectTcp(ch.socket())
                ch.socket().tcpNoDelay = true
                channel = ch
                val r = redirect
                if (r != null && policy.intercept(clientAddr, clientPort, serverAddr, serverPort)) {
                    // Bind and register the real destination BEFORE connecting, so the proxy's accept
                    // thread can never look up the mapping before it exists (it keys on our local port).
                    ch.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    r.register(ch.socket().localPort, serverAddr, serverPort)
                    ch.connect(r.address)
                } else {
                    ch.connect(InetSocketAddress(InetAddress.getByAddress(serverAddr), serverPort))
                }
                selKey = ch.register(selector, SelectionKey.OP_CONNECT, this)
            } catch (e: Exception) {
                Log.d(TAG, "connect failed ${addr(serverAddr)}:$serverPort: ${e.message}")
                sendRst()
                close()
            }
        }

        fun onSelected(key: SelectionKey) {
            try {
                if (key.isConnectable) onConnectable()
                if (key.isValid && key.isWritable) onWritable()
                if (key.isValid && key.isReadable) onReadable()
            } catch (e: Exception) {
                Log.d(TAG, "flow error ${addr(serverAddr)}:$serverPort: ${e.message}")
                sendRst()
                close()
            }
        }

        private fun onConnectable() {
            val ch = channel ?: return
            if (!ch.finishConnect()) return
            state = State.ESTABLISHED
            sendSynAck()
            updateInterest()
            if (clientFin && toServer.isEmpty()) runCatching { ch.shutdownOutput() }
        }

        private fun sendSynAck() {
            writeToTun(IpPackets.buildTcp(version, serverAddr, clientAddr, serverPort, clientPort, isn, clientSeq, IpPackets.TCP_SYN or IpPackets.TCP_ACK, ourWindow(), null, 0, 0, mss = if (version == 4) 1400 else 1380))
            if (!synAckSent) {
                synAckSent = true
                serverSeq = seqAdd(isn, 1)
                // Until the handshake ACK arrives, assume the client will acknowledge our SYN.
                clientAck = serverSeq
            }
        }

        fun onClient(seg: TcpSegment, pkt: ByteArray) {
            lastActivity = System.currentTimeMillis()
            if (seg.rst) { close(); return }
            if (seg.syn) {
                if (synAckSent) sendSynAck() // retransmitted SYN: repeat our SYN/ACK
                return
            }
            if (seg.ackFlag) {
                if (seqDiff(seg.ack, clientAck) > 0) clientAck = seg.ack
                clientWindow = seg.window
                if (finSent && seg.ack == serverSeq) finAcked = true
                if (readPaused && state == State.ESTABLISHED) updateInterest()
            }
            if (seg.payloadLength > 0) {
                if (seg.seq == clientSeq) {
                    val buf = ByteBuffer.allocate(seg.payloadLength)
                    buf.put(pkt, seg.payloadOffset, seg.payloadLength)
                    buf.flip()
                    toServer.addLast(buf)
                    pendingBytes += seg.payloadLength
                    clientSeq = seqAdd(clientSeq, seg.payloadLength.toLong())
                    sendAck()
                    if (state == State.ESTABLISHED) updateInterest()
                } else {
                    sendAck() // duplicate or out of order: re-announce what we have
                }
            }
            if (seg.fin) {
                val finSeq = seqAdd(seg.seq, seg.payloadLength.toLong())
                if (finSeq == clientSeq && !clientFin) {
                    clientFin = true
                    clientSeq = seqAdd(clientSeq, 1)
                    sendAck()
                    if (state == State.ESTABLISHED && toServer.isEmpty()) runCatching { channel?.shutdownOutput() }
                } else if (clientFin) {
                    sendAck()
                }
            }
            if (clientFin && finSent && finAcked) close()
        }

        private fun onWritable() {
            val ch = channel ?: return
            while (toServer.isNotEmpty()) {
                val b = toServer.first()
                val before = b.remaining()
                ch.write(b)
                pendingBytes -= before - b.remaining()
                if (b.hasRemaining()) break
                toServer.removeFirst()
            }
            if (toServer.isEmpty() && clientFin) runCatching { ch.shutdownOutput() }
            if (advertisedZero && pendingBytes < MAX_PENDING / 2) sendAck()
            updateInterest()
        }

        private fun onReadable() {
            val ch = channel ?: return
            var rounds = 0
            while (rounds++ < 64) {
                val room = clientWindow - seqDiff(serverSeq, clientAck).toInt()
                if (room <= 0) { readPaused = true; updateInterest(); return }
                readBuffer.clear()
                readBuffer.limit(minOf(mss, room, readBuffer.capacity()))
                val n = ch.read(readBuffer)
                if (n == -1) {
                    writeToTun(IpPackets.buildTcp(version, serverAddr, clientAddr, serverPort, clientPort, serverSeq, clientSeq, IpPackets.TCP_FIN or IpPackets.TCP_ACK, ourWindow(), null, 0, 0))
                    serverSeq = seqAdd(serverSeq, 1)
                    finSent = true
                    updateInterest()
                    if (clientFin && finAcked) close()
                    return
                }
                if (n == 0) return
                readBuffer.flip()
                val data = ByteArray(n)
                readBuffer.get(data)
                writeToTun(IpPackets.buildTcp(version, serverAddr, clientAddr, serverPort, clientPort, serverSeq, clientSeq, IpPackets.TCP_PSH or IpPackets.TCP_ACK, ourWindow(), data, 0, n))
                serverSeq = seqAdd(serverSeq, n.toLong())
                lastActivity = System.currentTimeMillis()
            }
        }

        private fun updateInterest() {
            val k = selKey ?: return
            if (!k.isValid || state != State.ESTABLISHED) return
            var ops = 0
            if (!finSent) {
                val room = clientWindow - seqDiff(serverSeq, clientAck).toInt()
                if (room > 0) { ops = ops or SelectionKey.OP_READ; readPaused = false } else readPaused = true
            }
            if (toServer.isNotEmpty()) ops = ops or SelectionKey.OP_WRITE
            k.interestOps(ops)
        }

        private fun ourWindow(): Int {
            val w = (MAX_PENDING - pendingBytes).coerceIn(0, 65535)
            advertisedZero = w == 0
            return w
        }

        private fun sendAck() {
            writeToTun(IpPackets.buildTcp(version, serverAddr, clientAddr, serverPort, clientPort, serverSeq, clientSeq, IpPackets.TCP_ACK, ourWindow(), null, 0, 0))
        }

        private fun sendRst() {
            writeToTun(IpPackets.buildTcp(version, serverAddr, clientAddr, serverPort, clientPort, serverSeq, clientSeq, IpPackets.TCP_RST or IpPackets.TCP_ACK, 0, null, 0, 0))
        }

        fun close() {
            if (state == State.CLOSED) return
            state = State.CLOSED
            runCatching { selKey?.cancel() }
            runCatching { channel?.close() }
            tcpFlows.remove(key)
            tcpCount = tcpFlows.size
        }

        fun expired(now: Long): Boolean {
            val idle = now - lastActivity
            return when {
                state == State.CONNECTING -> idle > 30_000
                finSent || clientFin -> idle > 30_000
                else -> idle > TCP_IDLE_MS
            }
        }
    }

    // ----------------------------------------------------------------- UDP

    private fun handleUdp(d: UdpDatagram, pkt: ByteArray) {
        if (policy.dropUdp(d.src, d.srcPort, d.dst, d.dstPort)) return
        val key = flowKey(d.version, d.src, d.srcPort, d.dst, d.dstPort)
        var flow = udpFlows[key]
        if (flow == null) {
            if (udpFlows.size >= MAX_UDP_FLOWS) return
            flow = UdpFlow(key, d)
            if (!flow.open()) return
            udpFlows[key] = flow
            udpCount = udpFlows.size
        }
        flow.send(pkt, d.payloadOffset, d.payloadLength)
    }

    private inner class UdpFlow(val key: String, first: UdpDatagram) {
        private val template = UdpDatagram(first.version, first.src, first.dst, first.srcPort, first.dstPort, 0, 0)
        private var channel: DatagramChannel? = null
        private var selKey: SelectionKey? = null
        var lastActivity = System.currentTimeMillis()

        fun open(): Boolean {
            return try {
                val ch = DatagramChannel.open()
                ch.configureBlocking(false)
                protectUdp(ch.socket())
                ch.connect(InetSocketAddress(InetAddress.getByAddress(template.dst), template.dstPort))
                channel = ch
                selKey = ch.register(selector, SelectionKey.OP_READ, this)
                true
            } catch (e: Exception) {
                Log.d(TAG, "udp open failed ${addr(template.dst)}:${template.dstPort}: ${e.message}")
                runCatching { channel?.close() }
                false
            }
        }

        fun send(pkt: ByteArray, off: Int, len: Int) {
            lastActivity = System.currentTimeMillis()
            try {
                channel?.write(ByteBuffer.wrap(pkt, off, len))
            } catch (e: IOException) {
                close()
            }
        }

        fun onSelected(key: SelectionKey) {
            val ch = channel ?: return
            try {
                if (!key.isReadable) return
                var rounds = 0
                while (rounds++ < 32) {
                    readBuffer.clear()
                    val n = ch.read(readBuffer)
                    if (n <= 0) break
                    readBuffer.flip()
                    val data = ByteArray(n)
                    readBuffer.get(data)
                    writeToTun(IpPackets.buildUdpReply(template, data, n))
                    lastActivity = System.currentTimeMillis()
                }
            } catch (e: IOException) {
                close()
            }
        }

        fun close() {
            runCatching { selKey?.cancel() }
            runCatching { channel?.close() }
            udpFlows.remove(key)
            udpCount = udpFlows.size
        }

        fun expired(now: Long): Boolean = now - lastActivity > UDP_IDLE_MS
    }

    private fun sweep(now: Long) {
        for (f in tcpFlows.values.toList()) if (f.expired(now)) f.close()
        for (f in udpFlows.values.toList()) if (f.expired(now)) f.close()
    }

    companion object {
        private const val TAG = "FullTunnel"
        private const val MAX_QUEUE = 4096
        private const val MAX_TCP_FLOWS = 2048
        private const val MAX_UDP_FLOWS = 1024
        private const val MAX_PENDING = 256 * 1024
        private const val TCP_IDLE_MS = 10 * 60 * 1000L
        private const val UDP_IDLE_MS = 90 * 1000L

        fun seqAdd(a: Long, n: Long): Long = (a + n) and 0xffffffffL

        /** Signed distance a - b in 32-bit sequence space. */
        fun seqDiff(a: Long, b: Long): Long = ((a - b) shl 32) shr 32

        private fun addr(b: ByteArray): String = runCatching { InetAddress.getByAddress(b).hostAddress ?: "?" }.getOrDefault("?")
    }
}
