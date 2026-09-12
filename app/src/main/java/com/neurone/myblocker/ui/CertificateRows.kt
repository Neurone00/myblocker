package com.neurone.myblocker.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neurone.myblocker.tls.CaInstall
import com.neurone.myblocker.vpn.BlockerVpnService

/**
 * Status and actions for the local certificate that lets deep clean open browser HTTPS.
 * Without it in Android's CA store nothing can be tidied, so installing it is the one action
 * on this row that matters: it is handed straight to the system certificate installer.
 */
@Composable
fun CertificateRows() {
    val context = LocalContext.current
    val tick by rememberTick(3000)
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var hint by remember { mutableStateOf<String?>(null) }
    val exists = remember(tick, refresh) { CaInstall.exists(context) }
    val installed = remember(tick, refresh) { exists && CaInstall.isInstalled(context) }
    val fingerprint = remember(exists, refresh) { if (exists) CaInstall.fingerprint(context) else "" }
    // The moment the install lands, the running tunnel is rebuilt so tidying starts by itself.
    LaunchedEffect(installed) { if (installed) Thread { BlockerVpnService.reconcileDeepClean(context) }.start() }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Certificate for page tidying", style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                installed -> "Installed. With Deep clean and \"Tidy pages in browsers\" on, pages in Chrome, Brave and Samsung Internet get their empty ad boxes removed; it takes effect on its own."
                else -> "Not installed, so browsers are not being tidied at all. Adbrella keeps the private half on this phone and installs only the public certificate."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        if (fingerprint.isNotEmpty()) {
            Text(
                "SHA-256 ${fingerprint.take(23)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!installed) {
            val n = CaInstall.userCertCount
            if (n > 0) {
                Text(
                    "Android's CA store lists $n user certificate(s), none of them this one: it was probably installed as a \"VPN and app user certificate\", which browsers do not read. Install it again and choose CA certificate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(top = 6.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    Thread {
                        // Creating the CA takes about a second the first time; keep it off the main thread.
                        runCatching { CaInstall.get(context) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            busy = false
                            refresh++
                            hint = when (installCertificateNow(context)) {
                                "installer" -> "Android is asking to install it now. If it offers a choice of what to use it for, pick CA certificate, then confirm the warning."
                                "keychain" -> "Name it Adbrella and confirm. If it asks what to use it for, pick CA certificate."
                                "settings" -> "Settings is open: Other security settings › Install from device storage › CA certificate › pick ${CaInstall.FILE_NAME} (save it to Downloads first, below)."
                                else -> "Could not open the certificate installer. Use \"Save to Downloads\" and install it from Settings."
                            }
                        }
                    }.start()
                }) { Text(if (busy) "Preparing…" else "Install certificate") }
            }
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
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
            }) { Text("Save to Downloads") }
            TextButton(onClick = { CaInstall.openSecuritySettings(context) }) { Text("Open Settings") }
        }
    }
}
