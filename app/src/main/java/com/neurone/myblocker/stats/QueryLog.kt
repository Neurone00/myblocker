package com.neurone.myblocker.stats

import com.neurone.myblocker.filter.Reason

class LogEntry(
    val time: Long,
    val host: String,
    val type: String,
    val blocked: Boolean,
    val reason: Reason,
    val rule: String?,
    val app: String?,
) {
    /** Unique, monotonically increasing; assigned by [QueryLog.add]. */
    var id: Long = 0
        internal set
}

/** In-memory ring buffer of recent DNS lookups for the Query Log screen. */
object QueryLog {
    private const val CAPACITY = 1500
    private val entries = ArrayDeque<LogEntry>(CAPACITY)

    @Volatile var version: Long = 0
        private set

    private var nextId = 1L

    @Synchronized fun add(e: LogEntry) {
        e.id = nextId++
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(e)
        version++
    }

    /** Newest first. */
    @Synchronized fun snapshot(): List<LogEntry> = entries.asReversed().toList()

    @Synchronized fun clear() {
        entries.clear()
        version++
    }
}
