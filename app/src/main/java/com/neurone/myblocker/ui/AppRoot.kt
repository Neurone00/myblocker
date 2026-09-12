package com.neurone.myblocker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Umbrella", Icons.Filled.Umbrella),
    Activity("Activity", Icons.Filled.History),
    Stats("Stats", Icons.Filled.BarChart),
    Settings("Settings", Icons.Filled.Settings),
}

/** Secondary screens pushed on top of a tab. */
enum class Screen { Strength, Apps, KeepRunning, Advanced, Lists, Rules, Upstream, About }

class Navigator(val push: (Screen) -> Unit, val pop: () -> Unit, val goTab: (Tab) -> Unit)

@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var stack by remember { mutableStateOf<List<Screen>>(emptyList()) }
    val nav = remember { Navigator(push = { s -> stack = stack + s }, pop = { stack = stack.dropLast(1) }, goTab = { t -> stack = emptyList(); tab = t }) }
    BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    Scaffold(
        bottomBar = {
            if (stack.isEmpty()) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (stack.lastOrNull()) {
                null -> when (tab) {
                    Tab.Home -> HomeScreen(nav)
                    Tab.Activity -> ActivityScreen()
                    Tab.Stats -> StatsScreen()
                    Tab.Settings -> SettingsScreen(nav)
                }
                Screen.Strength -> StrengthScreen(nav)
                Screen.Apps -> AppsScreen(nav)
                Screen.KeepRunning -> KeepRunningScreen(nav)
                Screen.Advanced -> AdvancedScreen(nav)
                Screen.Lists -> ListsScreen(nav)
                Screen.Rules -> RulesScreen(nav)
                Screen.Upstream -> UpstreamScreen(nav)
                Screen.About -> AboutScreen(nav)
            }
        }
    }
}
