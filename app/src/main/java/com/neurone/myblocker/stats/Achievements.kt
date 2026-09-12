package com.neurone.myblocker.stats

/** Levels: XP is simply the number of blocked requests. */
object Levels {
    private val thresholds = longArrayOf(
        0, 100, 500, 1_500, 4_000, 10_000, 25_000, 60_000, 150_000, 400_000, 1_000_000, 2_500_000, 6_000_000,
    )
    private val titles = arrayOf(
        "Light drizzle", "Shower", "Steady rain", "Downpour", "Storm", "Monsoon", "Typhoon",
        "Cold front", "Ice wall", "Weatherproof", "Bone dry", "Dry season", "Desert",
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

class Achievement(val id: String, val badge: String, val title: String, val description: String, val check: () -> Boolean)

/** Badge definitions. Evaluated against [StatsStore]; unlocks are persisted there. */
object Achievements {
    val ALL: List<Achievement> = listOf(
        Achievement("first_block", "1", "First bounce", "The first ad that never made it in") { StatsStore.totalBlocked >= 1 },
        Achievement("blocked_100", "100", "Cold shoulder", "100 ads and trackers bounced") { StatsStore.totalBlocked >= 100 },
        Achievement("blocked_1k", "1k", "Dry as a bone", "1,000 bounced") { StatsStore.totalBlocked >= 1_000 },
        Achievement("blocked_10k", "10k", "Downpour", "10,000 bounced and not a drop got through") { StatsStore.totalBlocked >= 10_000 },
        Achievement("blocked_100k", "100k", "Monsoon", "100,000 bounced") { StatsStore.totalBlocked >= 100_000 },
        Achievement("blocked_1m", "1M", "Weatherproof", "One million bounced. Legendary.") { StatsStore.totalBlocked >= 1_000_000 },
        Achievement("day_500", "500", "Busy day", "500 bounced in a single day") { StatsStore.todayBlocked() >= 500 },
        Achievement("streak_3", "3d", "Warming up", "3 days in a row under the umbrella") { StatsStore.streakDays() >= 3 },
        Achievement("streak_7", "7d", "Dry week", "7 days in a row") { StatsStore.streakDays() >= 7 },
        Achievement("streak_30", "30d", "Dry month", "30 days in a row") { StatsStore.streakDays() >= 30 },
        Achievement("streak_100", "100d", "Hundred days dry", "100 days in a row") { StatsStore.streakDays() >= 100 },
        Achievement("data_100mb", "MB", "Data saver", "About 100 MB of ads never downloaded") { StatsStore.estimatedBytesSaved() >= 100L * 1024 * 1024 },
        Achievement("data_1gb", "GB", "Gigabyte ghost", "About 1 GB of ads never downloaded") { StatsStore.estimatedBytesSaved() >= 1024L * 1024 * 1024 },
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
