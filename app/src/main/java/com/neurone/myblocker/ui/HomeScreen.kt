package com.neurone.myblocker.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.stats.AppNames
import com.neurone.myblocker.stats.Levels
import com.neurone.myblocker.stats.QueryLog
import com.neurone.myblocker.stats.StatsStore
import com.neurone.myblocker.system.SetupChecks
import com.neurone.myblocker.update.Updater
import com.neurone.myblocker.vpn.BlockerVpnService
import kotlinx.coroutines.launch

/**
 * How far open the umbrella rests while protection is off: properly furled, the same shape as the
 * status bar's closed icon. Half-open was tried and looks wrong at 44dp — the scallops fall below a
 * pixel and the canopy reads as a mushroom cap — so the resting state commits to being shut.
 */
private const val UMBRELLA_REST = 0.18f

@Composable
fun HomeScreen(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs.get(context) }
    val running by BlockerVpnService.running.collectAsStateWithLifecycle()
    val starting by BlockerVpnService.starting.collectAsStateWithLifecycle()
    val stateText by BlockerVpnService.stateText.collectAsStateWithLifecycle()
    val carPaused by BlockerVpnService.carPaused.collectAsStateWithLifecycle()
    val update by Updater.state.collectAsStateWithLifecycle()
    val autoStart by MainActivity.autoStart.collectAsStateWithLifecycle()
    val tick by rememberTick(1000)
    val appNames = remember { AppNames(context) }

    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) BlockerVpnService.start(context)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun turnOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Needed to recognise the car's Bluetooth so protection can pause for Android Auto.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.pauseForAndroidAuto &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val consent = VpnService.prepare(context)
        if (consent != null) vpnConsent.launch(consent) else BlockerVpnService.start(context)
    }

    LaunchedEffect(Unit) {
        if (!FilterEngine.loaded) FilterEngine.reloadAsync(context)
        if (prefs.autoUpdateApp) launch { Updater.check(context, manual = false) }
    }
    LaunchedEffect(autoStart) {
        if (autoStart) {
            MainActivity.autoStart.value = false
            if (!BlockerVpnService.isRunning) turnOn()
        }
    }

    // Values re-read every second; cheap and keeps the screen live without observable stores.
    val todayBlocked = remember(tick) { StatsStore.todayBlocked() }
    val totalBlocked = remember(tick) { StatsStore.totalBlocked }
    val streak = remember(tick) { StatsStore.streakDays() }
    val level = remember(tick) { Levels.forXp(StatsStore.totalBlocked) }
    val recent = remember(tick) { QueryLog.snapshot().filter { it.blocked }.take(3) }
    val setup = remember(tick) { SetupChecks.items(context) }
    val setupPending = setup.filter { !it.done }

    // A soft "bounce" on the today counter whenever it changes.
    var lastToday by remember { mutableStateOf(todayBlocked) }
    var bump by remember { mutableStateOf(false) }
    LaunchedEffect(todayBlocked) {
        if (todayBlocked != lastToday) {
            lastToday = todayBlocked
            bump = true
        }
    }
    val bumpScale by animateFloatAsState(
        targetValue = if (bump) 1.12f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        finishedListener = { bump = false },
        label = "bump",
    )

    Page {
        // Status card: the one control that matters.
        val on = running || starting
        val cardColor by animateColorAsState(
            if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            label = "status",
        )
        // Anticipate and bounce: the umbrella crouches, then springs open past full while the canopy
        // swings three times on the handle and the squash settles out. While starting it breathes
        // between furled and mostly open; turning off lowers it gently, no bounce.
        val umbrellaOpen = remember { Animatable(if (running) 1f else UMBRELLA_REST) }
        val umbrellaTilt = remember { Animatable(0f) }
        val umbrellaSquash = remember { Animatable(1f) }
        LaunchedEffect(running, starting) {
            when {
                running -> {
                    if (umbrellaOpen.value < 0.95f) {
                        launch {
                            umbrellaTilt.animateTo(
                                0f,
                                keyframes {
                                    durationMillis = 1050
                                    -12f at 180; 9f at 420; -5f at 640; 2f at 840; 0f at 1050
                                },
                            )
                        }
                        launch {
                            umbrellaSquash.animateTo(
                                1f,
                                keyframes { durationMillis = 700; 1.06f at 170; 0.98f at 420; 1f at 700 },
                            )
                        }
                        umbrellaOpen.animateTo(0.28f, tween(170, easing = LinearOutSlowInEasing))
                        umbrellaOpen.animateTo(1f, spring(dampingRatio = 0.26f, stiffness = 280f))
                    } else {
                        umbrellaOpen.animateTo(1f, tween(250))
                    }
                }
                starting -> {
                    umbrellaTilt.snapTo(0f)
                    umbrellaSquash.snapTo(1f)
                    while (true) {
                        umbrellaOpen.animateTo(0.62f, tween(650, easing = FastOutSlowInEasing))
                        umbrellaOpen.animateTo(UMBRELLA_REST, tween(650, easing = FastOutSlowInEasing))
                    }
                }
                else -> {
                    umbrellaTilt.snapTo(0f)
                    umbrellaSquash.snapTo(1f)
                    umbrellaOpen.animateTo(UMBRELLA_REST, tween(480, easing = FastOutSlowInEasing))
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = cardColor), shape = MaterialTheme.shapes.large) {
            // Fixed text lines so the card keeps its height whatever the state says.
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                UmbrellaGlyph(
                    open = umbrellaOpen.value,
                    tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp),
                    tilt = umbrellaTilt.value,
                    squash = umbrellaSquash.value,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            running -> "You're covered"
                            starting -> "Opening up…"
                            carPaused -> "Paused for Android Auto"
                            else -> "Umbrella down"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            running -> "Ads keep knocking. Nobody's home."
                            starting -> stateText
                            carPaused -> "Android Auto refuses any VPN. Back on the moment you leave the car."
                            BlockerVpnService.lastError != null -> BlockerVpnService.lastError ?: ""
                            else -> "Ads are walking right in."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = on,
                    onCheckedChange = { want ->
                        when {
                            want && carPaused -> android.widget.Toast.makeText(context, "Android Auto is connected. Adbrella comes back by itself when you leave the car.", android.widget.Toast.LENGTH_LONG).show()
                            want -> turnOn()
                            else -> BlockerVpnService.stop(context)
                        }
                    },
                    modifier = Modifier.scale(1.15f),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Modifier.weight(1f).scale(bumpScale), fmt(todayBlocked), "bounced today")
            StatTile(Modifier.weight(1f), fmt(totalBlocked), "bounced ever")
            StatTile(Modifier.weight(1f), streak.toString(), if (streak == 1) "day streak" else "day streak")
        }

        val u = update
        if (u is Updater.State.Available || u is Updater.State.Downloading || u is Updater.State.Installing) {
            UpdateCard(u) { scope.launch { (u as? Updater.State.Available)?.let { Updater.downloadAndInstall(context, it.info) } } }
        }

        if (setupPending.isNotEmpty()) {
            SectionCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        if (setupPending.size == 1) "One phone setting to go" else "${setupPending.size} phone settings to go",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Your phone likes closing umbrellas in the background. This card leaves once they are done.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (item in setup) {
                    RowDivider()
                    SettingRow(
                        title = item.title,
                        subtitle = if (item.done) null else item.detail,
                        onClick = if (item.done) null else ({
                            if (item.id == "alwayson") prefs.alwaysOnAcknowledged = true
                            if (item.screen == "advanced") nav.push(Screen.Advanced)
                            else item.intent?.let { runCatching { context.startActivity(it) } }
                        }),
                        trailing = {
                            if (item.done) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                    )
                }
            }
        }

        SectionCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Level ${level.level} · ${level.title}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        level.nextThreshold?.let { "${fmt(it - level.xp)} to go" } ?: "Maxed out",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { level.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }

        SectionCard {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bounced lately", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { nav.goTab(Tab.Activity) }) { Text("See all") }
            }
            if (recent.isEmpty()) {
                Text(
                    if (running) "Quiet so far. Open an app and watch the ads bounce." else "Open the umbrella to see what bounces.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            for (e in recent) {
                val app = e.app?.let { appNames.labelFor(it) } ?: "An app"
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Block, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("$app tried to show an ad", style = MaterialTheme.typography.bodyMedium)
                        Text(e.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(state: Updater.State, onInstall: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is Updater.State.Available -> {
                    Text("A newer Adbrella is out: ${state.info.versionName}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onInstall) { Text("Update now") }
                }
                is Updater.State.Downloading -> {
                    Text("Downloading ${state.info.versionName}… ${state.percent}%", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is Updater.State.Installing -> {
                    Text("Installing ${state.info.versionName}…", style = MaterialTheme.typography.titleMedium)
                    Text("If Android asks, tap Update.", style = MaterialTheme.typography.bodySmall)
                }
                else -> Unit
            }
        }
    }
}
