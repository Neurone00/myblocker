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
                else -> "Not installed, so browsers are not being tidied at all. Android only accepts a CA certificate that you install yourself in Settings; Adbrella saves the file and takes you there. The private half never leaves this phone."
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
        if (!installed && !CaInstall.hasScreenLock(context)) {
            Text(
                "This phone has no screen lock. Android will not keep a certificate at all without a PIN, pattern or password, and the install ends without saying so. Set one first, then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
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
                            hint = when (startCertificateInstall(context)) {
                                CertInstallRoute.INSTALLER ->
                                    "Android is asking to install it. Pick CA certificate if it offers a choice, then confirm the warning."
                                CertInstallRoute.SETTINGS ->
                                    "Saved to Downloads as ${CaInstall.FILE_NAME}, and Settings is open. Android does not let an app install a CA certificate, so finish it here:\n\n" +
                                        "1. Other security settings\n" +
                                        "2. Install from device storage\n" +
                                        "3. CA certificate — then Install anyway\n" +
                                        "4. Pick ${CaInstall.FILE_NAME} (in Downloads)\n\n" +
                                        "You do not have to come back: the moment it lands, tidying turns on and a notification says so.\n\n" +
                                        "Shorter route if that menu is hard to find: open My Files › Downloads and tap ${CaInstall.FILE_NAME}. " +
                                        "Android blocks Adbrella from opening it, but the system file manager is allowed to."
                                CertInstallRoute.SAVED_ONLY ->
                                    "Saved to Downloads as ${CaInstall.FILE_NAME}, but Settings would not open. Open Settings › Security and privacy › Other security settings › Install from device storage › CA certificate and pick that file."
                                CertInstallRoute.FAILED ->
                                    "Could not save the certificate. Check that storage is available and try again."
                            }
                        }
                    }.start()
                }) { Text(if (busy) "Preparing…" else "Save certificate and open Settings") }
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
