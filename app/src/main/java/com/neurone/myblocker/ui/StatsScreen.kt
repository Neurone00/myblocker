package com.neurone.myblocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neurone.myblocker.stats.Achievements
import com.neurone.myblocker.stats.AppNames
import com.neurone.myblocker.stats.Levels
import com.neurone.myblocker.stats.StatsStore
import java.util.Calendar

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val appNames = remember { AppNames(context) }
    val tick by rememberTick(2000)
    var confirmReset by remember { mutableStateOf(false) }
    remember(tick) { Achievements.evaluate() }

    val total = remember(tick) { StatsStore.totalBlocked }
    val queries = remember(tick) { StatsStore.totalQueries }
    val pct = if (queries == 0L) 0 else (total * 100 / queries).toInt()
    val days = remember(tick) { StatsStore.lastDays(7) }
    val hours = remember(tick) { StatsStore.lastHours(24) }
    val apps = remember(tick) { StatsStore.topApps(8) }
    val domains = remember(tick) { StatsStore.topDomains(10) }
    val unlocked = remember(tick) { StatsStore.unlockedAchievements() }
    val level = remember(tick) { Levels.forXp(total) }
    val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    Page(title = "Stats") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Modifier.weight(1f), fmt(total), "bounced")
            StatTile(Modifier.weight(1f), "$pct%", "of all knocks")
            StatTile(Modifier.weight(1f), "~" + fmtBytes(StatsStore.estimatedBytesSaved()), "never downloaded")
        }
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Text("Level ${level.level} · ${level.title}", style = MaterialTheme.typography.titleMedium)
                Text(
                    level.nextThreshold?.let { "${fmt(level.xp)} of ${fmt(it)} · every bounce is a point" } ?: "Top of the scale. Nothing gets past you.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Text("This week", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BarChart(days.map { it.second.blocked }, days.map { it.first }, Modifier.fillMaxWidth().height(120.dp))
                Text("${fmt(days.sumOf { it.second.blocked })} bounced this week", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Text("Last 24 hours", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BarChart(hours.map { it.blocked }, List(24) { i -> if (i % 6 == 0) "${(nowHour - 23 + i + 24) % 24}h" else null }, Modifier.fillMaxWidth().height(100.dp))
            }
        }
        SectionCard {
            Text("Apps that keep trying", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
            if (apps.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp))
            for ((pkg, n) in apps) KeyValue(appNames.labelFor(pkg), fmt(n))
        }
        SectionCard {
            Text("Most bounced domains", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
            if (domains.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp))
            for ((d, n) in domains) KeyValue(d, fmt(n))
        }
        SectionCard {
            Row(Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Badges", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${unlocked.size} of ${Achievements.ALL.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (rowItems in Achievements.ALL.chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    for (a in rowItems) {
                        val done = unlocked.containsKey(a.id)
                        Column(Modifier.weight(1f).alpha(if (done) 1f else 0.45f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(48.dp).background(
                                    if (done) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, CircleShape,
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(a.badge, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Text(a.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                Achievements.ALL.joinToString("\n") { "${it.title}: ${it.description}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 12.dp),
            )
        }
        TextButton(onClick = { confirmReset = true }) { Text("Reset statistics") }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset statistics?") },
            text = { Text("Counters, charts, level and badges go back to zero.") },
            confirmButton = { TextButton(onClick = { StatsStore.reset(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
