package com.neurone.myblocker.stats

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress

/** Maps UDP flows on the tunnel to the app that owns them, with a small label cache. */
class AppNames(context: Context) {
    private val pm: PackageManager = context.packageManager
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val packages = HashMap<Int, String>()

    /** Package name owning the socket, or null when unknown. */
    fun packageFor(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): String? {
        val uid = try {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(src), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dst), dstPort),
            )
        } catch (e: Exception) {
            Process.INVALID_UID
        }
        if (uid == Process.INVALID_UID) return null
        return packageFor(uid)
    }

    @Synchronized fun packageFor(uid: Int): String? {
        packages[uid]?.let { return it }
        val pkg = when (uid) {
            0 -> "android.root"
            1000 -> "android.system"
            1051 -> "android.dns"
            else -> pm.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        }
        packages[uid] = pkg
        return pkg
    }

    private val labelCache = HashMap<String, String>()

    @Synchronized fun labelFor(pkg: String): String {
        labelCache[pkg]?.let { return it }
        val label = when {
            pkg == "android.root" -> "System (root)"
            pkg == "android.system" -> "Android system"
            pkg == "android.dns" -> "DNS resolver"
            pkg.startsWith("uid:") -> "Unknown app ($pkg)"
            else -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
        labelCache[pkg] = label
        return label
    }
}
