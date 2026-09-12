package com.neurone.myblocker.ui

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import androidx.core.content.FileProvider
import com.neurone.myblocker.tls.CaInstall
import com.neurone.myblocker.tls.CertAuthority
import java.io.File

/**
 * Hands the public CA certificate straight to Android's certificate installer, so installing it
 * is one tap instead of "save a file, then find five levels of Settings" (which is how it ended up
 * never installed at all, leaving browsers un-tidied with nothing to show why).
 *
 * The mime type is what matters: `application/x-x509-ca-cert` puts it in the CA trust store that
 * browsers read, rather than the "VPN and app user certificate" slot they ignore. Falls back to the
 * KeyChain install intent and then to the Settings screen, and returns which route opened
 * ("installer", "keychain", "settings") so the caller can say what to expect, or null if none did.
 *
 * Lives with the UI because it starts activities; [CaInstall] stays free of androidx and of intents.
 */
fun installCertificateNow(context: Context): String? {
    val uri = runCatching {
        val ca = CaInstall.get(context)
        val file = File(CaInstall.dir(context), CaInstall.FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText(CertAuthority.pem(ca.caCert))
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }.getOrNull()

    if (uri != null) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/x-x509-ca-cert")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(view) }.isSuccess) return "installer"
    }

    val keychain = runCatching {
        KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, CaInstall.get(context).caCert.encoded)
            putExtra(KeyChain.EXTRA_NAME, "Adbrella")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }.getOrNull()
    if (keychain != null && runCatching { context.startActivity(keychain) }.isSuccess) return "keychain"

    return if (CaInstall.openSecuritySettings(context)) "settings" else null
}
