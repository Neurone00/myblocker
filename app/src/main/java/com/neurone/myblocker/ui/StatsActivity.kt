package com.neurone.myblocker.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.neurone.myblocker.stats.Achievements
import com.neurone.myblocker.stats.AppNames
import com.neurone.myblocker.stats.Levels
import com.neurone.myblocker.stats.StatsStore

class StatsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Stats & badges"
        StatsStore.init(this)
        Achievements.evaluate()
        render(Ui.page(this))
    }

    private fun render(col: LinearLayout) {
        val ctx = this
        val level = Levels.forXp(StatsStore.totalBlocked)

        val overview = Ui.card(ctx)
        overview.addView(Ui.heading(ctx, "Overview"))
        val row = Ui.row(ctx)
        row.addView(Ui.weight(stat(ctx, Ui.format(StatsStore.totalBlocked), "blocked"), 1f))
        row.addView(Ui.weight(stat(ctx, Ui.format(StatsStore.totalQueries), "lookups"), 1f))
        val pct = if (StatsStore.totalQueries == 0L) 0 else (StatsStore.totalBlocked * 100 / StatsStore.totalQueries).toInt()
        row.addView(Ui.weight(stat(ctx, "$pct%", "blocked share"), 1f))
        overview.addView(row)
        val row2 = Ui.row(ctx)
        row2.addView(Ui.weight(stat(ctx, "${StatsStore.streakDays()}", "day streak"), 1f))
        row2.addView(Ui.weight(stat(ctx, "${StatsStore.activeDayCount()}", "days protected"), 1f))
        row2.addView(Ui.weight(stat(ctx, "~" + Ui.bytes(StatsStore.estimatedBytesSaved()), "data not loaded"), 1f))
        overview.addView(row2)
        overview.addView(Ui.muted(ctx, "Data estimate assumes ~45 KB per blocked request (request + creative it would have fetched)."))
        col.addView(overview)

        val levelCard = Ui.card(ctx)
        levelCard.addView(Ui.heading(ctx, "Level"))
        levelCard.addView(Ui.title(ctx, "Level ${level.level} · ${level.title}"))
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progress = level.progressPercent
        bar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 12))
        levelCard.addView(bar)
        levelCard.addView(
            Ui.muted(
                ctx,
                level.nextThreshold?.let { "${Ui.format(level.xp)} / ${Ui.format(it)} XP · every blocked request is 1 XP" }
                    ?: "You have reached the top. Legend status.",
            ),
        )
        col.addView(levelCard)

        val weekCard = Ui.card(ctx)
        weekCard.addView(Ui.heading(ctx, "Last 7 days"))
        val days = StatsStore.lastDays(7)
        val weekChart = BarChartView(ctx)
        weekChart.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 140))
        weekChart.setData(days.map { it.second.blocked }.toLongArray(), days.map { it.first }.toTypedArray())
        weekCard.addView(weekChart)
        weekCard.addView(Ui.muted(ctx, "Blocked this week: ${Ui.format(days.sumOf { it.second.blocked })}"))
        col.addView(weekCard)

        val dayCard = Ui.card(ctx)
        dayCard.addView(Ui.heading(ctx, "Last 24 hours"))
        val hours = StatsStore.lastHours(24)
        val hourChart = BarChartView(ctx)
        hourChart.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 120))
        val nowHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        hourChart.setData(hours.map { it.blocked }.toLongArray(), Array(24) { i -> if (i % 4 == 0) "${(nowHour - 23 + i + 24) % 24}h" else null })
        dayCard.addView(hourChart)
        col.addView(dayCard)

        val appNames = AppNames(ctx)
        val appsCard = Ui.card(ctx)
        appsCard.addView(Ui.heading(ctx, "Apps with most blocked requests"))
        val apps = StatsStore.topApps(10)
        if (apps.isEmpty()) appsCard.addView(Ui.muted(ctx, "Nothing yet. Use your phone for a while."))
        for ((pkg, n) in apps) appsCard.addView(kv(ctx, appNames.labelFor(pkg), Ui.format(n)))
        col.addView(appsCard)

        val domCard = Ui.card(ctx)
        domCard.addView(Ui.heading(ctx, "Most blocked domains"))
        val doms = StatsStore.topDomains(15)
        if (doms.isEmpty()) domCard.addView(Ui.muted(ctx, "Nothing yet."))
        for ((d, n) in doms) domCard.addView(kv(ctx, d, Ui.format(n)))
        col.addView(domCard)

        val badges = Ui.card(ctx)
        badges.addView(Ui.heading(ctx, "Badges"))
        val unlocked = StatsStore.unlockedAchievements()
        val fmt = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        for (a in Achievements.ALL) {
            val when_ = unlocked[a.id]
            val line = Ui.row(ctx)
            val emoji = Ui.body(ctx, if (when_ != null) a.emoji else "🔒", 22f)
            emoji.setPadding(0, 0, Ui.dp(ctx, 12), 0)
            line.addView(emoji)
            val text = Ui.column(ctx)
            text.addView(Ui.body(ctx, a.title).apply { if (when_ == null) alpha = 0.5f })
            text.addView(Ui.muted(ctx, if (when_ != null) "${a.description} · ${fmt.format(java.util.Date(when_))}" else a.description))
            line.addView(Ui.weight(text, 1f))
            badges.addView(line)
        }
        col.addView(badges)

        col.addView(Ui.button(ctx, "Reset statistics") {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Reset statistics?")
                .setMessage("Counters, charts, level and badges go back to zero.")
                .setPositiveButton("Reset") { _, _ -> StatsStore.reset(); recreate() }
                .setNegativeButton("Cancel", null)
                .show()
        })
    }

    private fun stat(ctx: Activity, value: String, label: String): LinearLayout {
        val c = Ui.column(ctx)
        c.gravity = android.view.Gravity.CENTER
        c.addView(Ui.title(ctx, value).apply { gravity = android.view.Gravity.CENTER })
        c.addView(Ui.muted(ctx, label).apply { gravity = android.view.Gravity.CENTER })
        return c
    }

    private fun kv(ctx: Activity, key: String, value: String): LinearLayout {
        val r = Ui.row(ctx)
        r.addView(Ui.weight(Ui.body(ctx, key, 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }, 1f))
        r.addView(Ui.body(ctx, value, 14f))
        return r
    }
}
