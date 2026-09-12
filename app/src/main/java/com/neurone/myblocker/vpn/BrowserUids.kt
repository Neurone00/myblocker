package com.neurone.myblocker.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.system.OsConstants
import com.neurone.myblocker.proxy.InterceptProxy
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Tells whether a flow on the tunnel belongs to a browser we are allowed to tidy.
 * Only those apps are ever intercepted; everything else stays untouched.
 */
class BrowserUids(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val pm = context.packageManager
    private val cache = HashMap<Int, Boolean>()

    fun isBrowserTcp(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Boolean =
        isBrowser(owner(OsConstants.IPPROTO_TCP, src, srcPort, dst, dstPort))

    fun isBrowserUdp(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Boolean =
        isBrowser(owner(OsConstants.IPPROTO_UDP, src, srcPort, dst, dstPort))

    private fun owner(proto: Int, src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Int = try {
        cm.getConnectionOwnerUid(
            proto,
            InetSocketAddress(InetAddress.getByAddress(src), srcPort),
            InetSocketAddress(InetAddress.getByAddress(dst), dstPort),
        )
    } catch (e: Exception) {
        Process.INVALID_UID
    }

    @Synchronized
    private fun isBrowser(uid: Int): Boolean {
        if (uid == Process.INVALID_UID || uid < Process.FIRST_APPLICATION_UID) return false
        cache[uid]?.let { return it }
        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull() ?: emptyArray()
        val browser = pkgs.any { it in InterceptProxy.BROWSER_PACKAGES }
        cache[uid] = browser
        return browser
    }
}
