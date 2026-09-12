package com.neurone.myblocker.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.neurone.myblocker.tls.CaInstall
import com.neurone.myblocker.tls.CertAuthority
import java.io.File

/** What [startCertificateInstall] managed to open, so the caller can say what happens next. */
enum class CertInstallRoute { INSTALLER, SETTINGS, SAVED_ONLY, FAILED }

/**
 * Gets the phone as close to an installed CA certificate as Android allows.
 *
 * From Android 11 the system refuses any app-initiated CA install outright ("Impossibile installare
 * i certificati CA — questo certificato deve essere installato in Impostazioni"), so there is no
 * one-tap path and pretending otherwise just walks the user into that dead end. Instead the
 * certificate is written to Downloads under a known name and Settings is opened, leaving only the
 * file-picking to do by hand. Below Android 11 the direct installer still works, so it is tried.
 */
fun startCertificateInstall(context: Context): CertInstallRoute {
    val saved = runCatching { CaInstall.exportToDownloads(context) }.getOrNull() != null

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        val uri = runCatching {
            val file = File(CaInstall.dir(context), CaInstall.FILE_NAME)
            file.parentFile?.mkdirs()
            file.writeText(CertAuthority.pem(CaInstall.get(context).caCert))
            FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }.getOrNull()
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/x-x509-ca-cert")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(view) }.isSuccess) return CertInstallRoute.INSTALLER
        }
    }

    if (CaInstall.openSecuritySettings(context)) return CertInstallRoute.SETTINGS
    return if (saved) CertInstallRoute.SAVED_ONLY else CertInstallRoute.FAILED
}
