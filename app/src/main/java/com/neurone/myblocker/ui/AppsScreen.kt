package com.neurone.myblocker.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.vpn.BlockerVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppItem(val pkg: String, val label: String, val system: Boolean)

/** Apps that bypass the filter entirely (their DNS goes straight to the network). */
@Composable
fun AppsScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val changes by prefs.changes.collectAsStateWithLifecycle()
    val bypass = remember(changes) { prefs.bypassApps }
    var all by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var query by rememberSaveable { mutableStateOf("") }
    var changed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (changed) BlockerVpnService.restartIfRunning(context) } }

    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val initial = prefs.bypassApps
            pm.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull { ai ->
                if (ai.packageName == context.packageName) return@mapNotNull null
                val internet = pm.checkPermission(Manifest.permission.INTERNET, ai.packageName) == PackageManager.PERMISSION_GRANTED
                if (!internet && ai.packageName !in initial) return@mapNotNull null
                val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val launchable = pm.getLaunchIntentForPackage(ai.packageName) != null
                if (system && !launchable && ai.packageName !in initial) return@mapNotNull null
                AppItem(ai.packageName, pm.getApplicationLabel(ai).toString(), system)
            }.sortedWith(compareBy({ it.pkg !in initial }, { it.label.lowercase() }))
        }
    }

    val visible = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) || it.pkg.contains(q) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            IconButton(onClick = nav.pop) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Apps that skip the umbrella", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "Checked apps are not filtered at all. Use this for an app that misbehaves (banking, some games). Applies when you leave this screen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search apps") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
            shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        if (all.isEmpty()) Text("Loading apps…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(visible, key = { it.pkg }) { app ->
                val checked = app.pkg in bypass
                Row(
                    Modifier.fillMaxWidth().clickable {
                        prefs.bypassApps = if (checked) prefs.bypassApps - app.pkg else prefs.bypassApps + app.pkg
                        changed = true
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = {
                        prefs.bypassApps = if (checked) prefs.bypassApps - app.pkg else prefs.bypassApps + app.pkg
                        changed = true
                    })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.pkg + if (app.system) " · system" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
