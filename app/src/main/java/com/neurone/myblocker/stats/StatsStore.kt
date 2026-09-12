package com.neurone.myblocker.stats

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A (queries, blocked) pair. */
class Bucket(var queries: Long = 0, var blocked: Long = 0)

/**
 * Counters behind the dashboard, charts and gamification. Kept in memory and
 * flushed to files/stats.json at most every few seconds and on shutdown.
 */
object StatsStore {
    private const val TAG = "StatsStore"
    private const val FILE = "stats.json"
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_DAYS = 90
    private const val MAX_HOURS = 24 * 8
    private const val MAX_TOP = 400

    /** Rough average size of a blocked ad/tracker request plus the creative it would have loaded. */
    const val BYTES_PER_BLOCKED_REQUEST = 45_000L

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var file: File? = null
    private val lock = Any()
    private var dirty = false
    private var lastFlush = 0L
    private var loaded = false

    var totalQueries: Long = 0; private set
    var totalBlocked: Long = 0; private set
    var firstStart: Long = 0; private set
    private val perDay = LinkedHashMap<String, Bucket>()
    private val perHour = LinkedHashMap<Long, Bucket>()
    private val topDomains = HashMap<String, Long>()
    private val topApps = HashMap<String, Long>()
    private val activeDays = HashSet<String>()
    private val unlocked = LinkedHashMap<String, Long>()

    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            file = File(context.applicationContext.filesDir, FILE)
            load()
            loaded = true
        }
    }

    fun record(host: String, blocked: Boolean, app: String?, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            totalQueries++
            val day = dayKey(now)
            val hour = now / 3_600_000L
            val d = perDay.getOrPut(day) { Bucket() }
            val h = perHour.getOrPut(hour) { Bucket() }
            d.queries++
            h.queries++
            if (blocked) {
                totalBlocked++
                d.blocked++
                h.blocked++
                topDomains[host] = (topDomains[host] ?: 0) + 1
                if (app != null) topApps[app] = (topApps[app] ?: 0) + 1
                if (topDomains.size > MAX_TOP * 2) prune(topDomains)
                if (topApps.size > MAX_TOP) prune(topApps)
            }
            activeDays.add(day)
            trim()
            dirty = true
            if (now - lastFlush > FLUSH_INTERVAL_MS) flushLocked()
        }
    }

    fun markActive(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (firstStart == 0L) firstStart = now
            if (activeDays.add(dayKey(now))) dirty = true
        }
    }

    fun todayBlocked(): Long = synchronized(lock) { perDay[dayKey(System.currentTimeMillis())]?.blocked ?: 0 }
    fun todayQueries(): Long = synchronized(lock) { perDay[dayKey(System.currentTimeMillis())]?.queries ?: 0 }

    /** Blocked count per hour for the last [hours] hours, oldest first. */
    fun lastHours(hours: Int = 24): List<Bucket> = synchronized(lock) {
        val current = System.currentTimeMillis() / 3_600_000L
        (current - hours + 1..current).map { perHour[it]?.copy() ?: Bucket() }
    }

    /** (dayLabel, bucket) for the last [days] days, oldest first. */
    fun lastDays(days: Int = 7): List<Pair<String, Bucket>> = synchronized(lock) {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val out = ArrayList<Pair<String, Bucket>>(days)
        val label = SimpleDateFormat("EEE", Locale.getDefault())
        repeat(days) {
            val key = dayKey(cal.timeInMillis)
            out.add(label.format(cal.time) to (perDay[key]?.copy() ?: Bucket()))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        out
    }

    fun topDomains(n: Int = 20): List<Pair<String, Long>> = synchronized(lock) {
        topDomains.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    fun topApps(n: Int = 20): List<Pair<String, Long>> = synchronized(lock) {
        topApps.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    }

    /** Consecutive days (ending today or yesterday) on which protection was active. */
    fun streakDays(): Int = synchronized(lock) {
        val cal = Calendar.getInstance()
        var streak = 0
        if (!activeDays.contains(dayKey(cal.timeInMillis))) cal.add(Calendar.DAY_OF_YEAR, -1)
        while (activeDays.contains(dayKey(cal.timeInMillis))) {
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
            if (streak > 10_000) break
        }
        streak
    }

    fun activeDayCount(): Int = synchronized(lock) { activeDays.size }

    fun estimatedBytesSaved(): Long = totalBlocked * BYTES_PER_BLOCKED_REQUEST

    fun unlockedAchievements(): Map<String, Long> = synchronized(lock) { LinkedHashMap(unlocked) }

    /** Records [id] as unlocked; returns true if it was new. */
    fun unlock(id: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (unlocked.containsKey(id)) return false
        unlocked[id] = now
        dirty = true
        true
    }

    fun flush() = synchronized(lock) { flushLocked() }

    fun reset() = synchronized(lock) {
        totalQueries = 0; totalBlocked = 0
        perDay.clear(); perHour.clear(); topDomains.clear(); topApps.clear()
        unlocked.clear()
        dirty = true
        flushLocked()
    }

    // --- internals ---------------------------------------------------------

    private fun Bucket.copy() = Bucket(queries, blocked)

    private fun dayKey(millis: Long): String = synchronized(dayFormat) {
        dayFormat.timeZone = TimeZone.getDefault()
        dayFormat.format(Date(millis))
    }

    private fun prune(map: HashMap<String, Long>) {
        val keep = map.entries.sortedByDescending { it.value }.take(MAX_TOP).map { it.key }.toHashSet()
        map.keys.retainAll(keep)
    }

    private fun trim() {
        while (perDay.size > MAX_DAYS) perDay.remove(perDay.keys.first())
        while (perHour.size > MAX_HOURS) perHour.remove(perHour.keys.first())
    }

    private fun flushLocked() {
        if (!dirty) return
        val f = file ?: return
        try {
            val o = JSONObject()
            o.put("totalQueries", totalQueries)
            o.put("totalBlocked", totalBlocked)
            o.put("firstStart", firstStart)
            o.put("perDay", JSONObject().also { j -> perDay.forEach { (k, v) -> j.put(k, JSONArray().put(v.queries).put(v.blocked)) } })
            o.put("perHour", JSONObject().also { j -> perHour.forEach { (k, v) -> j.put(k.toString(), JSONArray().put(v.queries).put(v.blocked)) } })
            o.put("topDomains", JSONObject().also { j -> topDomains.forEach { (k, v) -> j.put(k, v) } })
            o.put("topApps", JSONObject().also { j -> topApps.forEach { (k, v) -> j.put(k, v) } })
            o.put("activeDays", JSONArray(activeDays.sorted()))
            o.put("unlocked", JSONObject().also { j -> unlocked.forEach { (k, v) -> j.put(k, v) } })
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString())
                tmp.delete()
            }
            dirty = false
            lastFlush = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "flush failed", e)
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            totalQueries = o.optLong("totalQueries")
            totalBlocked = o.optLong("totalBlocked")
            firstStart = o.optLong("firstStart")
            o.optJSONObject("perDay")?.let { j ->
                for (k in j.keys()) {
                    val a = j.getJSONArray(k)
                    perDay[k] = Bucket(a.getLong(0), a.getLong(1))
                }
            }
            o.optJSONObject("perHour")?.let { j ->
                val keys = ArrayList<Long>()
                for (k in j.keys()) keys.add(k.toLong())
                for (k in keys.sorted()) {
                    val a = j.getJSONArray(k.toString())
                    perHour[k] = Bucket(a.getLong(0), a.getLong(1))
                }
            }
            o.optJSONObject("topDomains")?.let { j -> for (k in j.keys()) topDomains[k] = j.getLong(k) }
            o.optJSONObject("topApps")?.let { j -> for (k in j.keys()) topApps[k] = j.getLong(k) }
            o.optJSONArray("activeDays")?.let { a -> for (i in 0 until a.length()) activeDays.add(a.getString(i)) }
            o.optJSONObject("unlocked")?.let { j -> for (k in j.keys()) unlocked[k] = j.getLong(k) }
            trim()
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
        }
    }
}
