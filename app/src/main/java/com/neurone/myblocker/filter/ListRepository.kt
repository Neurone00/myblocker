package com.neurone.myblocker.filter

import android.content.Context
import android.util.Log
import com.neurone.myblocker.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Where a list's data comes from right now. */
data class ListStatus(
    val source: ListSource,
    val enabled: Boolean,
    val downloaded: Boolean,
    val bundled: Boolean,
    val lastUpdated: Long,
    val entries: Int,
    val error: String?,
)

/** Storage and refresh of blocklists: bundled assets + downloaded files under files/lists. */
class ListRepository(private val context: Context) {
    private val prefs = Prefs.get(context)
    private val dir: File get() = File(context.filesDir, "lists").also { it.mkdirs() }

    fun allSources(): List<ListSource> = ListSource.BUILTIN + customSources()

    fun customSources(): List<ListSource> {
        val arr = runCatching { JSONArray(prefs.customSourcesJson) }.getOrDefault(JSONArray())
        val out = ArrayList<ListSource>()
        for (i in 0 until arr.length()) {
            runCatching { out.add(ListSource.fromJson(arr.getJSONObject(i))) }
        }
        return out
    }

    fun addCustomSource(name: String, url: String): ListSource {
        val id = "custom_" + System.currentTimeMillis().toString(36)
        val src = ListSource(id, name.ifBlank { url }, "Custom list", url, null, false, emptySet())
        val arr = runCatching { JSONArray(prefs.customSourcesJson) }.getOrDefault(JSONArray())
        arr.put(src.toJson())
        prefs.customSourcesJson = arr.toString()
        prefs.enabledSources = prefs.enabledSources + id
        prefs.level = ProtectionLevel.CUSTOM
        return src
    }

    fun removeCustomSource(id: String) {
        val arr = runCatching { JSONArray(prefs.customSourcesJson) }.getOrDefault(JSONArray())
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != id) kept.put(o)
        }
        prefs.customSourcesJson = kept.toString()
        prefs.enabledSources = prefs.enabledSources - id
        fileFor(id).delete()
        setMeta(id, null)
    }

    fun fileFor(id: String): File = File(dir, "$id.txt")

    fun status(source: ListSource): ListStatus {
        val meta = meta(source.id)
        val file = fileFor(source.id)
        return ListStatus(
            source = source,
            enabled = source.id in prefs.enabledSources,
            downloaded = file.exists() && file.length() > 0,
            bundled = source.asset != null,
            lastUpdated = meta?.optLong("updated", 0L) ?: 0L,
            entries = meta?.optInt("entries", 0) ?: 0,
            error = meta?.optString("error", "")?.ifEmpty { null },
        )
    }

    /** Opens the best available data for [source]: downloaded file first, bundled asset second. */
    fun open(source: ListSource): InputStream? {
        val file = fileFor(source.id)
        if (file.exists() && file.length() > 0) return file.inputStream().buffered(1 shl 16)
        val asset = source.asset ?: return null
        return try {
            val raw = context.assets.open("${ListSource.ASSET_DIR}/$asset")
            if (asset.endsWith(".gz")) GZIPInputStream(raw, 1 shl 16) else raw.buffered(1 shl 16)
        } catch (e: Exception) {
            Log.w(TAG, "asset missing: $asset", e)
            null
        }
    }

    /** Downloads [source] to its file. Returns null on success, otherwise an error message. */
    fun download(source: ListSource): String? {
        val url = source.url ?: return "no URL"
        val tmp = File(dir, "${source.id}.tmp")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MyBlocker/1.0 (Android)")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return "HTTP $code"
            }
            val enc = conn.contentEncoding ?: ""
            val body: InputStream = if (enc.contains("gzip")) GZIPInputStream(conn.inputStream) else conn.inputStream
            var bytes = 0L
            body.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes += n
                    }
                }
            }
            if (bytes < 16) {
                tmp.delete()
                return "empty response"
            }
            // Sanity check: count entries before accepting the file.
            val set = HashSet<String>(1024)
            tmp.inputStream().buffered(1 shl 16).use { ListParser.parse(it, set) }
            if (set.isEmpty()) {
                tmp.delete()
                return "no domains found in response"
            }
            if (!tmp.renameTo(fileFor(source.id))) {
                tmp.copyTo(fileFor(source.id), overwrite = true)
                tmp.delete()
            }
            setMeta(source.id, JSONObject().put("updated", System.currentTimeMillis()).put("entries", set.size))
            null
        } catch (e: Exception) {
            tmp.delete()
            val msg = e.message ?: e.javaClass.simpleName
            val old = meta(source.id) ?: JSONObject()
            setMeta(source.id, old.put("error", msg))
            Log.w(TAG, "download failed for ${source.id}: $msg")
            msg
        }
    }

    /** Refreshes every enabled source that has a URL. Returns a map of id -> error (null = ok). */
    fun updateEnabled(): Map<String, String?> {
        val enabled = prefs.enabledSources
        val results = LinkedHashMap<String, String?>()
        for (src in allSources()) {
            if (src.url == null || src.id !in enabled) continue
            results[src.id] = download(src)
        }
        prefs.lastListUpdate = System.currentTimeMillis()
        return results
    }

    fun recordBundledCount(id: String, entries: Int) {
        if (meta(id) == null) setMeta(id, JSONObject().put("updated", 0L).put("entries", entries))
    }

    private fun meta(id: String): JSONObject? {
        val all = runCatching { JSONObject(prefs.listMetaJson) }.getOrDefault(JSONObject())
        return all.optJSONObject(id)
    }

    private fun setMeta(id: String, value: JSONObject?) {
        val all = runCatching { JSONObject(prefs.listMetaJson) }.getOrDefault(JSONObject())
        if (value == null) all.remove(id) else all.put(id, value)
        prefs.listMetaJson = all.toString()
    }

    companion object {
        private const val TAG = "ListRepository"
    }
}
