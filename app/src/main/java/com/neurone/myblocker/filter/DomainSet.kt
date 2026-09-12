package com.neurone.myblocker.filter

/**
 * Immutable set of domains with suffix matching: an entry "ads.example.com"
 * matches "ads.example.com" and every name below it ("x.ads.example.com").
 */
class DomainSet private constructor(private val set: HashSet<String>) {
    val size: Int get() = set.size

    fun contains(host: String): Boolean = set.contains(host)

    /** Returns the matching entry (the host itself or one of its parent domains), or null. */
    fun matches(host: String): String? {
        var h = host
        while (true) {
            if (set.contains(h)) return h
            val i = h.indexOf('.')
            if (i < 0) return null
            h = h.substring(i + 1)
        }
    }

    fun toSortedList(): List<String> = set.sorted()

    companion object {
        val EMPTY = DomainSet(HashSet(0))

        fun of(entries: Collection<String>): DomainSet {
            val s = HashSet<String>(maxOf(16, entries.size * 4 / 3 + 1))
            for (e in entries) {
                val n = ListParser.normalizeDomain(e, allowTld = true) ?: continue
                s.add(n)
            }
            return DomainSet(s)
        }

        fun wrap(set: HashSet<String>): DomainSet = DomainSet(set)
    }
}
