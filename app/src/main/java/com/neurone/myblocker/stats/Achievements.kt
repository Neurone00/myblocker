package com.neurone.myblocker.stats

/** Levels: XP is simply the number of blocked requests. */
object Levels {
    private val thresholds = longArrayOf(
        0, 100, 500, 1_500, 4_000, 10_000, 25_000, 60_000, 150_000, 400_000, 1_000_000, 2_500_000, 6_000_000,
    )
    private val titles = arrayOf(
        "Rookie", "Scout", "Sentinel", "Guardian", "Warden", "Ad Slayer", "Tracker Hunter",
        "Privacy Knight", "Signal Ghost", "Grand Blocker", "Legend", "Mythic", "Ascended",
    )

    class Info(val level: Int, val title: String, val xp: Long, val currentFloor: Long, val nextThreshold: Long?) {
        /** 0..100 progress toward the next level. */
        val progressPercent: Int
            get() {
                val next = nextThreshold ?: return 100
                val span = (next - currentFloor).coerceAtLeast(1)
                return ((xp - currentFloor) * 100 / span).toInt().coerceIn(0, 100)
            }
    }

    fun forXp(xp: Long): Info {
        var idx = 0
        while (idx + 1 < thresholds.size && xp >= thresholds[idx + 1]) idx++
        val next = if (idx + 1 < thresholds.size) thresholds[idx + 1] else null
        return Info(idx + 1, titles[idx], xp, thresholds[idx], next)
    }
}

class Achievement(val id: String, val emoji: String, val title: String, val description: String, val check: () -> Boolean)

/** Badge definitions. Evaluated against [StatsStore]; unlocks are persisted there. */
object Achievements {
    val ALL: List<Achievement> = listOf(
        Achievement("first_block", "🛡️", "First blood", "Block your first ad or tracker") { StatsStore.totalBlocked >= 1 },
        Achievement("blocked_100", "🎯", "Century", "Block 100 requests") { StatsStore.totalBlocked >= 100 },
        Achievement("blocked_1k", "🔥", "Thousand cuts", "Block 1,000 requests") { StatsStore.totalBlocked >= 1_000 },
        Achievement("blocked_10k", "⚡", "Ten thousand", "Block 10,000 requests") { StatsStore.totalBlocked >= 10_000 },
        Achievement("blocked_100k", "🌪️", "Storm wall", "Block 100,000 requests") { StatsStore.totalBlocked >= 100_000 },
        Achievement("blocked_1m", "👑", "Millionaire", "Block 1,000,000 requests") { StatsStore.totalBlocked >= 1_000_000 },
        Achievement("day_500", "☀️", "Busy day", "Block 500 requests in a single day") { StatsStore.todayBlocked() >= 500 },
        Achievement("streak_3", "🌱", "Warming up", "3 days in a row protected") { StatsStore.streakDays() >= 3 },
        Achievement("streak_7", "📅", "One week", "7 days in a row protected") { StatsStore.streakDays() >= 7 },
        Achievement("streak_30", "🏆", "Iron month", "30 days in a row protected") { StatsStore.streakDays() >= 30 },
        Achievement("streak_100", "💎", "Diamond streak", "100 days in a row protected") { StatsStore.streakDays() >= 100 },
        Achievement("data_100mb", "💾", "Data saver", "Roughly 100 MB of ad traffic never downloaded") { StatsStore.estimatedBytesSaved() >= 100L * 1024 * 1024 },
        Achievement("data_1gb", "🗄️", "Gigabyte ghost", "Roughly 1 GB of ad traffic never downloaded") { StatsStore.estimatedBytesSaved() >= 1024L * 1024 * 1024 },
    )

    /** Returns achievements that just became unlocked. */
    fun evaluate(): List<Achievement> {
        val already = StatsStore.unlockedAchievements()
        val fresh = ArrayList<Achievement>()
        for (a in ALL) {
            if (already.containsKey(a.id)) continue
            val ok = runCatching { a.check() }.getOrDefault(false)
            if (ok && StatsStore.unlock(a.id)) fresh.add(a)
        }
        return fresh
    }
}
