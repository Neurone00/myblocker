package com.neurone.myblocker.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.neurone.myblocker.dns.BlockMode
import com.neurone.myblocker.dns.DnsMessage
import com.neurone.myblocker.dns.DnsQuestion
import com.neurone.myblocker.filter.Decision
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.net.IpPackets
import com.neurone.myblocker.net.ParsedPacket
import com.neurone.myblocker.net.UdpDatagram
import com.neurone.myblocker.upstream.Upstream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Everything the service wants to know about one lookup. */
class QueryEvent(
    val time: Long,
    val question: DnsQuestion,
    val decision: Decision,
    val datagram: UdpDatagram,
)

/**
 * The packet loop. Reads IP packets from the TUN device, answers blocked DNS
 * queries locally and forwards the rest to the configured upstream on a small
 * worker pool. Only DNS is routed into the tunnel, so this is all it ever sees.
 */
class DnsProxy(
    private val tun: ParcelFileDescriptor,
    private val upstream: Upstream,
    private val blockMode: BlockMode,
    private val mtu: Int,
    private val listener: (QueryEvent) -> Unit,
    /** Present in deep-clean mode: everything that is not DNS is handed to the relay. */
    private val relay: FullTunnel? = null,
    /** Name answered locally with [internalIp] (the deep-clean stylesheet host), or null. */
    private val internalHost: String? = null,
    private val internalIp: ByteArray = ByteArray(4),
) : Runnable {
    @Volatile private var running = true
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val workers = ThreadPoolExecutor(
        4, 12, 30, TimeUnit.SECONDS, LinkedBlockingQueue(512),
        { r -> Thread(r, "dns-upstream").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    @Volatile var forwarded: Long = 0; private set
    @Volatile var answeredLocally: Long = 0; private set
    @Volatile var upstreamFailures: Long = 0; private set

    override fun run() {
        val buffer = ByteArray(65535)
        val pollFd = StructPollfd()
        pollFd.fd = tun.fileDescriptor
        pollFd.events = OsConstants.POLLIN.toShort()
        val polls = arrayOf(pollFd)
        Log.i(TAG, "proxy loop started, upstream=${upstream.description}")
        while (running) {
            try {
                pollFd.revents = 0
                val ready = Os.poll(polls, POLL_TIMEOUT_MS)
                if (ready <= 0) continue
                val revents = pollFd.revents.toInt()
                if (revents and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) {
                    Log.w(TAG, "tun closed (revents=$revents)")
                    break
                }
                if (revents and OsConstants.POLLIN == 0) continue
                val len = input.read(buffer)
                if (len <= 0) continue
                handlePacket(buffer, len)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                if (running) Log.e(TAG, "poll failed", e)
                break
            } catch (e: IOException) {
                if (running) Log.e(TAG, "tun read failed", e)
                break
            } catch (e: Exception) {
                Log.e(TAG, "unexpected error in packet loop", e)
            }
        }
        running = false
        relay?.stop()
        workers.shutdownNow()
        runCatching { upstream.close() }
        Log.i(TAG, "proxy loop ended")
    }

    fun stop() {
        running = false
    }

    val isRunning: Boolean get() = running

    private fun handlePacket(buf: ByteArray, len: Int) {
        when (val p = IpPackets.parse(buf, len)) {
            is ParsedPacket.Udp -> {
                if (p.datagram.dstPort == 53) handleDns(p.datagram, buf) else relay?.offer(buf, len)
            }
            is ParsedPacket.Tcp -> {
                if (relay != null) {
                    relay.offer(buf, len)
                } else if (p.segment.syn && !p.segment.ackFlag) {
                    // DNS-only mode: only resolver addresses are routed here, so any TCP SYN is
                    // DNS-over-TCP, a Private-DNS probe (853) or DNS-over-HTTPS (443) to a captured
                    // public resolver. Refuse immediately so the client falls back to plain DNS.
                    val port = p.segment.dstPort
                    if (port == 53 || port == 853 || port == 443) writeToTun(IpPackets.buildTcpRst(p.segment))
                }
            }
            ParsedPacket.Other -> Unit
        }
    }

    fun writePacket(packet: ByteArray) = writeToTun(packet)

    private fun handleDns(udp: UdpDatagram, buf: ByteArray) {
        if (udp.payloadLength < DnsMessage.HEADER_LENGTH) return
        val query = buf.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        val q = DnsMessage.parseQuestion(query) ?: return
        val internal = internalHost
        if (internal != null && q.name == internal) {
            // The stylesheet host for deep clean lives inside the tunnel.
            reply(udp, DnsMessage.buildAddressAnswer(query, q, internalIp))
            return
        }
        val decision = FilterEngine.decide(q.name)
        listener(QueryEvent(System.currentTimeMillis(), q, decision, udp))
        if (decision.blocked) {
            answeredLocally++
            reply(udp, DnsMessage.buildBlockedResponse(query, q, blockMode))
            return
        }
        workers.execute {
            val response = upstream.resolve(query)
            if (response == null) {
                upstreamFailures++
                reply(udp, DnsMessage.buildServfail(query, q))
            } else {
                forwarded++
                reply(udp, response, q)
            }
        }
    }

    private fun reply(udp: UdpDatagram, payload: ByteArray, q: DnsQuestion? = null) {
        val headers = if (udp.version == 4) 28 else 48
        var body = payload
        if (body.size + headers > mtu) {
            // Would not fit in one packet on the tunnel: mark it truncated so the stub retries via TCP.
            val question = q ?: DnsMessage.parseQuestion(payload) ?: return
            body = DnsMessage.buildTruncated(payload, question)
        }
        writeToTun(IpPackets.buildUdpReply(udp, body))
    }

    @Synchronized
    private fun writeToTun(packet: ByteArray) {
        if (!running) return
        try {
            output.write(packet)
        } catch (e: IOException) {
            if (running) Log.w(TAG, "tun write failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DnsProxy"
        private const val POLL_TIMEOUT_MS = 500
    }
}
