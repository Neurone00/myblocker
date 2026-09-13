package com.neurone.myblocker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.web.WebFilters
import java.text.DateFormat
import java.util.Date

/** Cosmetic filtering for Samsung Internet: hides the empty ad boxes DNS blocking leaves behind. */
@Composable
fun WebScreen(nav: Navigator) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val changes by prefs.changes.collectAsStateWithLifecycle()
    val tick by rememberTick(2000)
    val browser = remember(tick) { WebFilters.samsungBrowser(context) }
    val rules = remember(changes) { prefs.webFiltersRules }
    val updated = remember(changes) { prefs.webFiltersUpdated }
    var updating by remember { mutableStateOf(false) }
    val fmtDate = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }


    Page(title = "Tidy web pages", onBack = nav.pop) {
        SectionCard("Samsung Internet") {
            Column(Modifier.padding(16.dp)) {
                if (browser == null) {
                    Text("Samsung Internet is not installed on this phone.", style = MaterialTheme.typography.bodyLarge)
                    Text("It ships with every Galaxy phone; you can reinstall it from Galaxy Store. Chrome cannot run content blockers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("One-time step", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "In Samsung Internet: menu (☰) › Settings › Extensions › Content blockers, then switch on Adbrella. The button below tries to take you straight there.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    Button(onClick = {
                        if (!WebFilters.openBrowserSettings(context)) Toast.makeText(context, "Could not open Samsung Internet", Toast.LENGTH_SHORT).show()
                    }) { Text("Open Samsung Internet settings") }
                }
            }
        }
        SectionCard("Rules") {
            Column(Modifier.padding(16.dp)) {
                Text("EasyList", style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (rules > 0) "${fmt(rules.toLong())} rules · " else "") +
                        (if (updated > 0) "updated ${fmtDate.format(Date(updated))}" else "bundled copy, refreshes automatically every 12 hours"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (updating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(enabled = !updating, onClick = {
                        updating = true
                        Thread {
                            val err = WebFilters.refresh(context)
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                updating = false
                                Toast.makeText(context, err?.let { "Update failed: $it" } ?: "Rules updated", Toast.LENGTH_LONG).show()
                            }
                        }.start()
                    }) { Text(if (updating) "Updating…" else "Update rules now") }
                    TextButton(onClick = { WebFilters.notifyBrowser(context); Toast.makeText(context, "Samsung Internet asked to reload the rules", Toast.LENGTH_SHORT).show() }) { Text("Reload in browser") }
                }
            }
        }
        Text(
            "Other browsers: Chrome on Android has no extension support, so it cannot hide ad boxes. Firefox with the uBlock Origin add-on does the same job as Samsung Internet with Adbrella.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
