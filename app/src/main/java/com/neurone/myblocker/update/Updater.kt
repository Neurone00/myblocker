package com.neurone.myblocker.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.neurone.myblocker.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-updater. CI publishes `update.json` next to the APK on the rolling
 * GitHub release; we compare its versionCode with ours, download the APK,
 * verify its SHA-256 and hand it to PackageInstaller. On Android 12+ the
 * update installs without a prompt once Adbrella is its own installer of
 * record (from the second self-update on).
 */
object Updater {
    private const val TAG = "Updater"
    const val REPO = "Neurone00/myblocker"
    const val RELEASE_TAG = "adbrella-latest"
    const val MANIFEST_URL = "https://github.com/$REPO/releases/download/$RELEASE_TAG/update.json"
    private const val API_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/tags/$RELEASE_TAG"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** [apkUrl] is the public download URL; [apkAssetUrl] the API asset URL used when a token is configured. */
    data class Info(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val apkAssetUrl: String? = null,
    )

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val current: String) : State()
        data class Available(val info: Info) : State()
        data class Downloading(val info: Info, val percent: Int) : State()
        data class Installing(val info: Info) : State()
        data class Error(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun currentVersionCode(context: Context): Long {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return pi.longVersionCode
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Checks the manifest. [manual] bypasses the 6-hour throttle. Returns the update if there is one. */
    suspend fun check(context: Context, manual: Boolean): Info? = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.lastUpdateCheck < CHECK_INTERVAL_MS) {
            return@withContext (state.value as? State.Available)?.info
        }
        state.value = State.Checking
        try {
            val token = prefs.githubToken
            val json: JSONObject
            var apkAssetUrl: String? = null
            if (token.isEmpty()) {
                json = JSONObject(fetch(MANIFEST_URL, 512 * 1024, null).toString(Charsets.UTF_8))
            } else {
                // Private repository: list the release through the API, then read the manifest asset.
                val release = JSONObject(fetch(API_RELEASE_URL, 1024 * 1024, token, api = true).toString(Charsets.UTF_8))
                val assets = release.getJSONArray("assets")
                var manifestUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    when (a.getString("name")) {
                        "update.json" -> manifestUrl = a.getString("url")
                        "adbrella.apk" -> apkAssetUrl = a.getString("url")
                    }
                }
                if (manifestUrl == null) throw IOException("update.json missing from the release")
                json = JSONObject(fetch(manifestUrl, 512 * 1024, token, octet = true).toString(Charsets.UTF_8))
            }
            val info = Info(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.getString("sha256").lowercase(),
                size = json.optLong("size", -1),
                apkAssetUrl = apkAssetUrl,
            )
            prefs.lastUpdateCheck = now
            if (info.versionCode > currentVersionCode(context)) {
                state.value = State.Available(info)
                info
            } else {
                state.value = State.UpToDate(currentVersionName(context))
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            val hint = when {
                msg.contains("404") && prefs.githubToken.isEmpty() -> " The GitHub repository is private: add a read-only token below or make the repo public."
                msg.contains("401") || msg.contains("403") -> " The token was rejected; it needs Contents: read on $REPO."
                else -> ""
            }
            state.value = State.Error("Could not check for updates ($msg).$hint")
            null
        }
    }

    /** Downloads and verifies the APK, then starts the install session. */
    suspend fun downloadAndInstall(context: Context, info: Info) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "adbrella-${info.versionCode}.apk")
        try {
            if (!(apk.exists() && sha256(apk) == info.sha256)) {
                state.value = State.Downloading(info, 0)
                val token = Prefs.get(ctx).githubToken
                val url = if (token.isNotEmpty() && info.apkAssetUrl != null) info.apkAssetUrl else info.apkUrl
                download(url, apk, info.size, if (token.isNotEmpty()) token else null) { pct -> state.value = State.Downloading(info, pct) }
                val digest = sha256(apk)
                if (digest != info.sha256) {
                    apk.delete()
                    throw IOException("downloaded file failed verification")
                }
            }
            state.value = State.Installing(info)
            install(ctx, apk)
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            state.value = State.Error("Update failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("adbrella.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    private fun fetch(url: String, maxBytes: Int, token: String?, api: Boolean = false, octet: Boolean = false): ByteArray {
        val conn = open(url, token, if (api) "application/vnd.github+json" else if (octet) "application/octet-stream" else "*/*")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readNBytes(maxBytes) }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, expectedSize: Long, token: String?, progress: (Int) -> Unit) {
        val conn = open(url, token, "application/octet-stream")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = if (expectedSize > 0) expectedSize else conn.contentLengthLong
            val tmp = File(dest.path + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                progress(pct)
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("could not save update")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * GitHub redirects asset downloads to a signed CDN URL; follow redirects manually and never
     * forward the token to another host (the CDN rejects requests that carry it).
     */
    private fun open(url: String, token: String?, accept: String): HttpURLConnection {
        var current = url
        val origin = URL(url).host
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Adbrella-Updater")
            conn.setRequestProperty("Accept", accept)
            if (!token.isNullOrEmpty() && URL(current).host == origin) {
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IOException("redirect without location")
                conn.disconnect()
                current = URL(URL(current), loc).toString()
            } else {
                return conn
            }
        }
        throw IOException("too many redirects")
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
