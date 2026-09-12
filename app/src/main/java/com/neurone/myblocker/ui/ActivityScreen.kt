package com.neurone.myblocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.stats.AppNames
import com.neurone.myblocker.stats.LogEntry
import com.neurone.myblocker.stats.QueryLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every lookup, newest first, in plain words. Tap a row to allow or block. */
@Composable
fun ActivityScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val appNames = remember { AppNames(context) }
    val tick by rememberTick(1500)
    var query by rememberSaveable { mutableStateOf("") }
    var blockedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val entries = remember(tick, query, blockedOnly) {
        var list = QueryLog.snapshot()
        if (blockedOnly) list = list.filter { it.blocked }
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            list = list.filter { e ->
                e.host.contains(q) || (e.app?.let { it.contains(q) || appNames.labelFor(it).lowercase().contains(q) } ?: false)
            }
        }
        list
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Who tried what", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search apps or domains") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = blockedOnly, onClick = { blockedOnly = !blockedOnly }, label = { Text("Bounced only") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { QueryLog.clear() }) { Text("Clear") }
        }
        if (!prefs.logEnabled) {
            Text("The activity log is off. Turn it on under Settings › Advanced.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entries.isEmpty()) {
            Text(
                "Nothing yet. Open a few apps and come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(entries, key = { "${it.time}-${it.host}-${it.type}" }) { e ->
                val app = e.app?.let { appNames.labelFor(it) } ?: "Unknown app"
                Row(
                    Modifier.fillMaxWidth().clickable { selected = e }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (e.blocked) Icons.Filled.Block else Icons.Filled.Check, null,
                        tint = if (e.blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("$app · ${if (e.blocked) "got the cold shoulder" else "went through"}", style = MaterialTheme.typography.bodyMedium)
                        Text("${e.host} · ${time.format(Date(e.time))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (e.blocked) {
                        TextButton(onClick = { prefs.addAllow(e.host); FilterEngine.reloadUserRules(context) }) { Text("Allow") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }

    selected?.let { e ->
        val parent = e.host.substringAfter('.', "")
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(e.host) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${if (e.blocked) "Bounced" else "Allowed"} · ${e.reason.label}${e.rule?.let { " · rule: $it" } ?: ""}")
                    e.app?.let { Text("App: ${appNames.labelFor(it)}") }
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { if (e.blocked) prefs.addAllow(e.host) else prefs.addBlock(e.host); FilterEngine.reloadUserRules(context); selected = null }) {
                        Text(if (e.blocked) "Allow ${e.host}" else "Block ${e.host}")
                    }
                    if (parent.contains('.')) {
                        TextButton(onClick = { if (e.blocked) prefs.addAllow(parent) else prefs.addBlock(parent); FilterEngine.reloadUserRules(context); selected = null }) {
                            Text(if (e.blocked) "Allow everything under $parent" else "Block everything under $parent")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
}
