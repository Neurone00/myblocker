package com.neurone.myblocker.web

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.neurone.myblocker.Prefs
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Cosmetic filtering for web pages through Samsung Internet's content-blocker
 * extension API. The browser reads an Adblock Plus-format list from
 * [WebFilterProvider]; EasyList's element-hiding rules ("##.ad-slot") remove the
 * empty ad containers that DNS blocking alone leaves behind.
 *
 * Contract (same one AdGuard Content Blocker and Adblock Plus for Samsung use):
 * permission com.samsung.android.sbrowser.permission.CONTENTBLOCKER, application
 * meta-data contentBlocker.interfaceVersion=API_1.0, a ContentProvider at
 * "<package>.contentBlocker.contentProvider" whose openFile() returns the list, and a
 * broadcast ACTION_UPDATE to the browser after the list changes.
 */
object WebFilters {
    private const val TAG = "WebFilters"
    const val LIST_URL = "https://easylist.to/easylist/easylist.txt"
    private const val ASSET = "lists/easylist.txt.gz"
    private const val FILE = "filters.txt"

    const val ACTION_SETTING = "com.samsung.android.sbrowser.contentBlocker.ACTION_SETTING"
    private const val ACTION_UPDATE = "com.samsung.android.sbrowser.contentBlocker.ACTION_UPDATE"
    private const val OPTIONS_URI = "internet://extension"
    private const val EXTRA_FRAGMENT = "sbrowser.extensions.show_fragment"
    private const val EXTRA_FRAGMENT_VALUE = "com.sec.android.app.sbrowser.blockers.content_block.view.ContentBlockPreferenceFragment"
    val SAMSUNG_PACKAGES = listOf("com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta")

    fun file(context: Context): File = File(File(context.filesDir, "web").also { it.mkdirs() }, FILE)

    @Volatile private var cosmetic: CosmeticRules? = null

    /** Element-hiding rules parsed from the current list; parsed once and after every refresh. */
    fun cosmeticRules(context: Context): CosmeticRules {
        cosmetic?.let { return it }
        synchronized(this) {
            cosmetic?.let { return it }
            val r = runCatching { CosmeticRules.parse(ensure(context)) }.getOrElse { CosmeticRules.EMPTY }
            cosmetic = r
            return r
        }
    }

    private fun invalidateCosmetic() {
        cosmetic = null
    }

    /** Makes sure the bundled list is on disk; cheap once done. */
    @Synchronized
    fun ensure(context: Context): File {
        val f = file(context)
        if (f.exists() && f.length() > 1024) return f
        try {
            val tmp = File(f.path + ".tmp")
            GZIPInputStream(context.assets.open(ASSET), 1 shl 16).use { inp ->
                FileOutputStream(tmp).use { out -> inp.copyTo(out, 1 shl 16) }
            }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            val prefs = Prefs.get(context)
            if (prefs.webFiltersRules == 0) prefs.webFiltersRules = countRules(f)
            Log.i(TAG, "bundled web filters installed (${f.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "could not install bundled web filters", e)
        }
        return f
    }

    /** Downloads the latest EasyList. Returns null on success, else an error message. */
    fun refresh(context: Context): String? {
        val f = file(context)
        val tmp = File(f.path + ".download")
        return try {
            val conn = URL(LIST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Adbrella/1.0 (Android; Samsung Internet content blocker)")
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return "HTTP $code"
            }
            conn.inputStream.use { inp -> FileOutputStream(tmp).use { out -> inp.copyTo(out, 1 shl 16) } }
            val head = tmp.bufferedReader().use { r -> r.readLine() ?: "" }
            if (!head.startsWith("[Adblock")) {
                tmp.delete()
                return "unexpected content"
            }
            if (tmp.length() < 100_000) {
                tmp.delete()
                return "list too small"
            }
            synchronized(this) {
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
            }
            val prefs = Prefs.get(context)
            prefs.webFiltersUpdated = System.currentTimeMillis()
            prefs.webFiltersRules = countRules(f)
            invalidateCosmetic()
            notifyBrowser(context)
            null
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "web filter refresh failed", e)
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Tells Samsung Internet to re-read our list. */
    fun notifyBrowser(context: Context) {
        for (pkg in SAMSUNG_PACKAGES) {
            val i = Intent(ACTION_UPDATE)
            i.data = Uri.parse("package:${context.packageName}")
            i.setPackage(pkg)
            runCatching { context.sendBroadcast(i) }
        }
    }

    /** Package name of an installed Samsung Internet, or null. */
    fun samsungBrowser(context: Context): String? {
        val pm = context.packageManager
        for (pkg in SAMSUNG_PACKAGES) {
            if (runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess) return pkg
        }
        // Fallback: any browser that resolves the content-blocker settings action (not ourselves).
        val list = runCatching { pm.queryIntentActivities(Intent(ACTION_SETTING), PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
        return list.map { it.activityInfo.packageName }.firstOrNull { it != context.packageName && it.startsWith("com.sec.") }
    }

    /** Opens Samsung Internet's "Content blockers" page, or the browser itself as a fallback. */
    fun openBrowserSettings(context: Context): Boolean {
        val pkg = samsungBrowser(context) ?: return false
        val deep = Intent(Intent.ACTION_VIEW, Uri.parse(OPTIONS_URI))
            .setPackage(pkg)
            .putExtra(EXTRA_FRAGMENT, EXTRA_FRAGMENT_VALUE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(deep) }.isSuccess) return true
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun countRules(f: File): Int {
        var n = 0
        f.bufferedReader().useLines { lines -> for (l in lines) if (l.isNotEmpty() && l[0] != '!' && l[0] != '[') n++ }
        return n
    }
}
