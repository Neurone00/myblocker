package com.neurone.myblocker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neurone.myblocker.BuildConfig
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.UpstreamMode
import com.neurone.myblocker.dns.BlockMode
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.filter.ListParser
import com.neurone.myblocker.filter.ListRepository
import com.neurone.myblocker.filter.ListSource
import com.neurone.myblocker.filter.ProtectionLevel
import com.neurone.myblocker.stats.StatsStore
import com.neurone.myblocker.vpn.BlockerVpnService
import java.text.DateFormat
import java.util.Date

@Composable
fun AdvancedScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val changes by prefs.changes.collectAsStateWithLifecycle()
    val tick by rememberTick(3000)
    var needsRestart by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (needsRestart) BlockerVpnService.restartIfRunning(context) } }

    val enabledCount = remember(changes) { prefs.enabledSources.size }
    val rules = remember(changes) { prefs.userAllow.size to prefs.userBlock.size }
    val blockMode = remember(changes) { prefs.blockMode }
    val upstream = remember(changes) { prefs.upstreamMode }

    Page(title = "Advanced", onBack = nav.pop) {
        Text(
            "Everything here works out of the box. Change it only if you know what you are looking for.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        SectionCard("Filtering") {
            SettingRow("Blocklists", "$enabledCount lists on · ${fmt(FilterEngine.blockedEntryCount.toLong())} domains loaded", onClick = { nav.push(Screen.Lists) })
            RowDivider()
            SettingRow("Your allow and block rules", "${rules.first} allowed · ${rules.second} blocked", onClick = { nav.push(Screen.Rules) })
            RowDivider()
            SettingRow(
                "Blocked answer",
                if (blockMode == BlockMode.NULL_IP) "Null IP (0.0.0.0 / ::), recommended" else "NXDOMAIN",
                onClick = {
                    prefs.blockMode = if (blockMode == BlockMode.NULL_IP) BlockMode.NXDOMAIN else BlockMode.NULL_IP
                    needsRestart = true
                },
            )
            RowDivider()
            SwitchRow("Safety allowlist", "Never blocks Play, push notifications, connectivity checks or Samsung account", remember(changes) { prefs.safetyList }) {
                prefs.safetyList = it
                FilterEngine.reloadUserRules(context)
            }
        }
        SectionCard("DNS") {
            SettingRow("Upstream resolver", upstream.label, onClick = { nav.push(Screen.Upstream) })
            RowDivider()
            SwitchRow("Catch hard-coded resolvers", "Also filters apps that talk to 8.8.8.8, 1.1.1.1 and friends directly", remember(changes) { prefs.catchHardcodedResolvers }) {
                prefs.catchHardcodedResolvers = it
                needsRestart = true
            }
        }
        SectionCard("Deep clean (beta)") {
            SwitchRow(
                "Route all traffic through Adbrella",
                "Foundation for tidying pages inside Chrome. Every connection is relayed by the app instead of only DNS. No page changes yet; turn off if anything misbehaves.",
                remember(changes) { prefs.deepClean },
            ) {
                prefs.deepClean = it
                needsRestart = true
            }
        }
        SectionCard("Diagnostics") {
            SwitchRow("Activity log", "Keep the last 1,500 lookups with the app that made them. Needed for per-app stats.", remember(changes) { prefs.logEnabled }) { prefs.logEnabled = it }
            RowDivider()
            SwitchRow("Auto-update blocklists", "Refresh enabled lists every 12 hours", remember(changes) { prefs.autoUpdateLists }) { prefs.autoUpdateLists = it }
            RowDivider()
            SettingRow("Reset statistics", null, onClick = { confirmReset = true })
            RowDivider()
            SettingRow("Version", "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA} · lists loaded in ${FilterEngine.lastLoadMillis} ms", onClick = null)
        }
        remember(tick) { Unit }
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
fun ListsScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val repo = remember { ListRepository(context) }
    val changes by prefs.changes.collectAsStateWithLifecycle()
    var updating by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val sources = remember(changes, refresh) { repo.allSources().map { it to repo.status(it) } }
    val fmtDate = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    fun update(only: List<ListSource>?) {
        updating = true
        Thread {
            val results = if (only == null) repo.updateEnabled() else only.associate { it.id to repo.download(it) }
            FilterEngine.reload(context)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                updating = false
                refresh++
                val failed = results.filterValues { it != null }
                val msg = if (failed.isEmpty()) "Lists updated" else "Some lists failed: " + failed.entries.joinToString { "${it.key}: ${it.value}" }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    Page(title = "Blocklists", onBack = nav.pop) {
        Text(
            "Toggling a list switches the protection strength to Custom. Domains match themselves and every subdomain.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (updating) LinearProgressIndicator(Modifier.fillMaxWidth())
        SectionCard {
            sources.forEachIndexed { i, (src, st) ->
                if (i > 0) RowDivider()
                val status = when {
                    st.error != null && !st.downloaded && !st.bundled -> "Not downloaded yet · last error: ${st.error}"
                    st.downloaded -> "${fmt(st.entries.toLong())} domains · updated ${fmtDate.format(Date(st.lastUpdated))}"
                    st.bundled -> "${fmt(st.entries.toLong())} domains · bundled copy"
                    else -> "Not downloaded yet"
                }
                SettingRow(src.name, "${src.description}\n$status") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!src.builtin) {
                            IconButton(onClick = { repo.removeCustomSource(src.id); FilterEngine.reloadAsync(context); refresh++ }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove")
                            }
                        }
                        Switch(checked = st.enabled, onCheckedChange = { on ->
                            prefs.enabledSources = if (on) prefs.enabledSources + src.id else prefs.enabledSources - src.id
                            prefs.level = ProtectionLevel.CUSTOM
                            FilterEngine.reloadAsync(context)
                            if (on && !st.downloaded && !st.bundled) update(listOf(src))
                        })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { adding = true }) { Text("Add list URL") }
            Button(onClick = { if (!updating) update(null) }, enabled = !updating) { Text(if (updating) "Updating…" else "Update now") }
        }
        val last = remember(changes) { prefs.lastListUpdate }
        Text(
            if (last > 0) "Last update ${fmtDate.format(Date(last))}. Lists refresh every 12 hours." else "Lists refresh every 12 hours.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    if (adding) {
        var name by rememberSaveable { mutableStateOf("") }
        var url by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add blocklist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("https://… (hosts, domains or ||domain^)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!url.trim().startsWith("http")) {
                        Toast.makeText(context, "Enter a valid URL", Toast.LENGTH_SHORT).show()
                    } else {
                        val src = repo.addCustomSource(name, url.trim())
                        adding = false
                        update(listOf(src))
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun RulesScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val changes by prefs.changes.collectAsStateWithLifecycle()
    val allow = remember(changes) { prefs.userAllow.sorted() }
    val block = remember(changes) { prefs.userBlock.sorted() }

    Page(title = "Your rules", onBack = nav.pop) {
        Text(
            "A rule covers the domain and all its subdomains. The allowlist always wins over every blocklist; use it to fix an app that stopped working.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        RuleSection("Always allow", allow, add = { prefs.addAllow(it) }, remove = { prefs.userAllow = prefs.userAllow - it })
        RuleSection("Always block", block, add = { prefs.addBlock(it) }, remove = { prefs.userBlock = prefs.userBlock - it })
    }
}

@Composable
private fun RuleSection(title: String, entries: List<String>, add: (String) -> Unit, remove: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    SectionCard(title) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("example.com") }, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val d = ListParser.normalizeDomain(text, allowTld = true)
                if (d == null) Toast.makeText(context, "That does not look like a domain", Toast.LENGTH_SHORT).show()
                else {
                    add(d)
                    FilterEngine.reloadUserRules(context)
                    text = ""
                }
            }) { Text("Add") }
        }
        if (entries.isEmpty()) {
            Text("No rules yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp))
        }
        for (d in entries) {
            RowDivider()
            SettingRow(d, null) {
                IconButton(onClick = { remove(d); FilterEngine.reloadUserRules(context) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
            }
        }
    }
}

@Composable
fun UpstreamScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val changes by prefs.changes.collectAsStateWithLifecycle()
    val mode = remember(changes) { prefs.upstreamMode }
    var doh by rememberSaveable { mutableStateOf(prefs.customDohUrl) }
    var ip by rememberSaveable { mutableStateOf(prefs.customDnsIp) }
    var dirty by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            prefs.customDohUrl = doh
            prefs.customDnsIp = ip
            if (dirty) BlockerVpnService.restartIfRunning(context)
        }
    }
    Page(title = "Upstream resolver", onBack = nav.pop) {
        Text(
            "Where allowed lookups go. Encrypted options hide your DNS from the carrier or Wi-Fi owner. Applies when you leave this screen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        SectionCard {
            UpstreamMode.entries.forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                RadioRow(m.label, m.detail, selected = mode == m) { prefs.upstreamMode = m; dirty = true }
            }
        }
        if (mode == UpstreamMode.DOH_CUSTOM) {
            OutlinedTextField(value = doh, onValueChange = { doh = it; dirty = true }, label = { Text("DNS-over-HTTPS URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (mode == UpstreamMode.PLAIN_CUSTOM) {
            OutlinedTextField(value = ip, onValueChange = { ip = it; dirty = true }, label = { Text("DNS server IPs, comma separated") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}
