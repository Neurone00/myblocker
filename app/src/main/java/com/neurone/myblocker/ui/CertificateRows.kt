package com.neurone.myblocker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import com.neurone.myblocker.tls.CaInstall

/**
 * Status and actions for the local certificate that lets deep clean open browser HTTPS.
 * Android 11+ only installs CA certificates from a file via Settings, so the flow is:
 * save to Downloads, open Settings, pick the file.
 */
@Composable
fun CertificateRows() {
    val context = LocalContext.current
    val tick by rememberTick(3000)
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val exists = remember(tick, refresh) { CaInstall.exists(context) }
    val installed = remember(tick, refresh) { exists && CaInstall.isInstalled(context) }
    val fingerprint = remember(exists, refresh) { if (exists) CaInstall.fingerprint(context) else "" }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Certificate for page tidying", style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                installed -> "Installed. Browser pages can be tidied once that feature ships."
                exists -> "Created, not yet installed. Save it to Downloads, then install it from Settings."
                else -> "Not created yet. Adbrella makes a private certificate that stays on this phone; you install only its public half."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fingerprint.isNotEmpty()) {
            Text(
                "SHA-256 ${fingerprint.take(23)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(enabled = !busy, onClick = {
                busy = true
                Thread {
                    val uri = CaInstall.exportToDownloads(context)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        busy = false
                        refresh++
                        Toast.makeText(
                            context,
                            if (uri != null) "Saved ${CaInstall.FILE_NAME} to Downloads" else "Could not save the certificate",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }.start()
            }) { Text(if (busy) "Working…" else if (exists) "Save to Downloads" else "Create and save to Downloads") }
            TextButton(enabled = exists && !installed, onClick = { CaInstall.openSecuritySettings(context) }) { Text("Open Settings") }
        }
        if (exists && !installed) {
            Text(
                "In Settings: Security and privacy › Other security settings › Install from device storage › CA certificate › Install anyway › pick ${CaInstall.FILE_NAME} in Downloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
