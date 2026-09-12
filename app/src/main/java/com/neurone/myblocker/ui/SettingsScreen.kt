package com.neurone.myblocker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neurone.myblocker.BuildConfig
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.filter.ProtectionLevel
import com.neurone.myblocker.system.SetupChecks
import com.neurone.myblocker.update.Updater
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/** Consumer-facing settings. Everything technical lives behind "Advanced". */
@Composable
fun SettingsScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val changes by prefs.changes.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val tick by rememberTick(2000)
    val level = remember(changes) { prefs.level }
    val bypass = remember(changes) { prefs.bypassApps.size }
    val setupDone = remember(tick, changes) { SetupChecks.items(context).count { it.done } }
    var tokenDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Page(title = "Settings") {
        SectionCard {
            SettingRow(
                "Protection strength",
                when (level) {
                    ProtectionLevel.OFF -> "Off. Only your own rules apply."
                    ProtectionLevel.LIGHT -> "Light. The big ad networks, nothing else."
                    ProtectionLevel.BALANCED -> "Balanced. Ads and most trackers."
                    ProtectionLevel.AGGRESSIVE -> "Strong. Ads, trackers and phone telemetry."
                    ProtectionLevel.CUSTOM -> "Custom selection of lists."
                },
                onClick = { nav.push(Screen.Strength) },
            )
            RowDivider()
            SettingRow("Apps that skip the umbrella", if (bypass == 0) "None" else "$bypass app${if (bypass == 1) "" else "s"}", onClick = { nav.push(Screen.Apps) })
            RowDivider()
            SettingRow("Tidy web pages", "Remove empty ad boxes in Samsung Internet", onClick = { nav.push(Screen.Web) })
        }
        SectionCard {
            SettingRow("Keep the umbrella open", "$setupDone of 3 phone settings done", onClick = { nav.push(Screen.KeepRunning) })
            RowDivider()
            SwitchRow("Open after a restart", "Turns protection back on when the phone reboots", prefs.startAtBoot) { prefs.startAtBoot = it }
            RowDivider()
            SwitchRow(
                "Pause for Android Auto",
                "Android Auto refuses to start with any VPN active. Adbrella pauses itself the moment your phone connects to the car — over Bluetooth, car mode, or the USB cable — and resumes when you disconnect. Needs the Nearby devices permission to recognise the car.",
                remember(changes) { prefs.pauseForAndroidAuto },
            ) { prefs.pauseForAndroidAuto = it }
        }
        SectionCard {
            SwitchRow(
                "Update automatically",
                "Version ${Updater.currentVersionName(context)} installed. New builds install in the background.",
                remember(changes) { prefs.autoUpdateApp },
            ) { prefs.autoUpdateApp = it }
            RowDivider()
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val u = update
                TextButton(
                    onClick = {
                        scope.launch {
                            val info = Updater.check(context, manual = true)
                            if (info != null) Updater.downloadAndInstall(context, info)
                        }
                    },
                    enabled = u !is Updater.State.Checking && u !is Updater.State.Downloading && u !is Updater.State.Installing,
                ) { Text("Check for updates") }
                Spacer(Modifier.width(8.dp))
                Text(
                    when (u) {
                        is Updater.State.Idle -> ""
                        is Updater.State.Checking -> "Checking…"
                        is Updater.State.UpToDate -> "Up to date"
                        is Updater.State.Available -> "${u.info.versionName} available"
                        is Updater.State.Downloading -> "Downloading ${u.percent}%"
                        is Updater.State.Installing -> "Installing…"
                        is Updater.State.Error -> u.message
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()
            val token = remember(changes) { prefs.githubToken }
            SettingRow(
                "Private repository token",
                if (token.isEmpty()) "Only needed while the GitHub repo is private. Tap to add a read-only token." else "Set (${token.take(8)}…). Tap to change or clear.",
                onClick = { tokenDialog = true },
            )
            RowDivider()
            SwitchRow("Badge notifications", "A nudge when you earn one", remember(changes) { prefs.achievementNotifications }) { prefs.achievementNotifications = it }
        }
        SectionCard {
            SettingRow("What Adbrella can and cannot block", null, onClick = { nav.push(Screen.About) })
            RowDivider()
            SettingRow("Advanced", "Blocklists, your own rules, DNS, logging", onClick = { nav.push(Screen.Advanced) })
        }
        Text(
            "Adbrella ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }

    if (tokenDialog) {
        var value by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(prefs.githubToken) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { tokenDialog = false },
            title = { Text("Private repository token") },
            text = {
                Column {
                    Text(
                        "GitHub refuses anonymous downloads from a private repository. Create a fine-grained personal access token on github.com (Settings › Developer settings › Fine-grained tokens) limited to the ${Updater.REPO} repository with Contents: Read-only, and paste it here. Leave empty if the repository is public.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = value, onValueChange = { value = it }, singleLine = true,
                        label = { Text("github_pat_…") }, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.githubToken = value
                    prefs.lastUpdateCheck = 0
                    tokenDialog = false
                    scope.launch { Updater.check(context, manual = true) }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { tokenDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun StrengthScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val changes by prefs.changes.collectAsStateWithLifecycle()
    val level = remember(changes) { prefs.level }
    val options = listOf(
        ProtectionLevel.LIGHT to "Blocks the big ad networks. Nothing ever breaks.",
        ProtectionLevel.BALANCED to "Blocks ads and most trackers. Rarely needs a fix.",
        ProtectionLevel.AGGRESSIVE to "Blocks ads, trackers and phone telemetry. If an app misbehaves, allow it from Activity.",
        ProtectionLevel.OFF to "Nothing from the lists. Only your own block rules apply.",
        ProtectionLevel.CUSTOM to "Your own selection, set under Advanced › Blocklists.",
    )
    Page(title = "Protection strength", onBack = nav.pop) {
        SectionCard {
            options.forEachIndexed { i, (opt, desc) ->
                if (i > 0) RowDivider()
                RadioRow(opt.label, desc, selected = level == opt) {
                    if (opt == ProtectionLevel.CUSTOM) {
                        prefs.level = ProtectionLevel.CUSTOM
                    } else {
                        prefs.applyLevel(opt)
                        com.neurone.myblocker.filter.FilterEngine.reloadAsync(context)
                    }
                }
            }
        }
        Text(
            "Stronger settings block more but may occasionally stop a feature in an app. When that happens, open Activity and tap Allow next to the app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
fun RadioRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    SettingRow(title, subtitle, onClick = onSelect) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onSelect)
    }
}

@Composable
fun KeepRunningScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val tick by rememberTick(1500)
    val items = remember(tick) { SetupChecks.items(context) }
    val mainExecutor = remember { Executor { r -> android.os.Handler(android.os.Looper.getMainLooper()).post(r) } }
    Page(title = "Keep the umbrella open", onBack = nav.pop) {
        Text(
            "One UI is strict with background apps. These settings keep Adbrella running all day and after restarts.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        SectionCard {
            items.forEachIndexed { i, item ->
                if (i > 0) RowDivider()
                SettingRow(
                    item.title, item.detail,
                    onClick = {
                        if (item.id == "alwayson") prefs.alwaysOnAcknowledged = true
                        item.intent?.let { runCatching { context.startActivity(it) } }
                    },
                    trailing = {
                        Text(if (item.done) "Done" else "Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    },
                )
            }
        }
        SectionCard {
            SettingRow("Quick Settings tile", "Toggle Adbrella from the notification shade", onClick = {
                val asked = SetupChecks.requestTile(context, mainExecutor) { }
                if (!asked) Toast.makeText(context, "Pull down the shade, tap the pencil and drag Adbrella in.", Toast.LENGTH_LONG).show()
            })
        }
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(
                "Do not enable \"Block connections without VPN\" in the Always-on VPN settings: it would cut off bypassed apps and Adbrella's own encrypted DNS.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AboutScreen(nav: Navigator) {
    Page(title = "What it can and cannot block", onBack = nav.pop) {
        AboutSection(
            "How it works",
            "Adbrella runs a local VPN whose only job is to see every app's DNS lookups. Names on the blocklists get an instant empty answer, so the ad or tracker never loads. Everything else is forwarded, encrypted, to the resolver you chose.",
            "Only DNS enters the tunnel. Browsing, streaming and gaming traffic goes straight to the network, so nothing gets slower and the battery cost is negligible.",
            "Nothing is sent to any server run by the author of this app.",
        )
        AboutSection(
            "Blocked",
            "Banners and interstitials in apps and games, web page ads in any browser, trackers, analytics and telemetry, ads in Samsung apps.",
        )
        AboutSection(
            "Not blocked (nobody can do this at DNS level)",
            "In-stream video ads on YouTube, Instagram, TikTok, Twitch and Spotify: they come from the same servers as the content.",
            "Sponsored posts inside Facebook, Instagram, X and Reddit feeds: they arrive inside the normal API responses.",
            "Rewarded ads (\"watch an ad to get X\") will simply report \"no ad available\".",
            "Apps that hard-code IP addresses or ship their own encrypted DNS (Adbrella catches the common public resolvers).",
        )
        AboutSection(
            "Web pages",
            "DNS blocking stops the ad from loading but leaves its empty box behind. Settings › Tidy web pages plugs Adbrella into Samsung Internet as a content blocker with EasyList, which also hides those boxes and reflows the page.",
            "Chrome has no extension support on Android, so it cannot do this. Samsung Internet (installed on your phone) or Firefox with uBlock Origin can.",
        )
        AboutSection(
            "If an app breaks",
            "Open Activity, find the app, tap the bounced domain and choose Allow. Your allowlist beats every blocklist.",
            "For stubborn apps, add them under Settings › Apps that skip the umbrella.",
        )
        AboutSection(
            "Apps that complain about ad blockers",
            "Apps look for a blocker in three ways. The usual one resolves a known ad domain and checks for a 0.0.0.0 or localhost answer; Adbrella's Invisible answer (Advanced › Blocked answer, the default) returns a real-looking address that it refuses itself, so the app only sees an ad server that is down.",
            "Some apps refuse to run while any VPN is active. Add them under Apps that skip the umbrella: they stop seeing one, but they also get no blocking.",
            "A few only unlock once their ad SDK has actually received an ad. That cannot be faked without impersonating the ad server, and Adbrella does not do that.",
        )
        AboutSection(
            "Android Auto",
            "Android Auto refuses to start whenever any VPN is active (\"communication error 21\"), and it checks before it ever reports being connected, so waiting for it to connect is too late. Adbrella instead pauses the moment your phone joins the car — over Bluetooth, in car mode, or through the USB cable (wired Android Auto puts the phone in USB accessory mode, which a plain charger does not) — all of which happen before Android Auto starts, and resumes when you leave. Grant the Nearby devices permission so it can tell the car apart from headphones.",
            "If you use Always-on VPN, keep \"Block connections without VPN\" off, otherwise the phone has no network while paused and Android Auto still cannot connect.",
        )
        AboutSection(
            "Privacy",
            "The activity log stays in memory on the phone. Statistics are stored in the app's private storage. No analytics, no accounts, no internet access except your chosen DNS resolver, the blocklist downloads and the update check on GitHub.",
        )
    }
}

@Composable
private fun AboutSection(title: String, vararg lines: String) {
    SectionCard {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            for (l in lines) Text(l, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
