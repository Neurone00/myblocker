package com.neurone.myblocker.tls

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * User-facing side of the local CA: creating it on demand, exporting the public
 * certificate to Downloads (Android 11+ only installs CA certificates from a file
 * through Settings), and checking whether it is present in the user trust store.
 */
object CaInstall {
    private const val TAG = "CaInstall"
    const val FILE_NAME = "adbrella-ca.crt"

    @Volatile private var authority: CertAuthority? = null

    fun dir(context: Context): File = File(context.filesDir, "ca")

    fun exists(context: Context): Boolean = authority != null || CertAuthority.exists(dir(context))

    /** Loads or generates the CA. Generation takes about a second; call off the main thread. */
    @Synchronized
    fun get(context: Context): CertAuthority {
        authority?.let { return it }
        val ca = CertAuthority.load(dir(context))
        authority = ca
        return ca
    }

    /** True when the CA certificate is installed in Android's user trust store. */
    fun isInstalled(context: Context): Boolean {
        if (!exists(context)) return false
        val ours = runCatching { get(context).caCert }.getOrNull() ?: return false
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null, null)
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!alias.startsWith("user:")) continue
                val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                if (cert.encoded.contentEquals(ours.encoded)) return true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "cannot read trust store", e)
            false
        }
    }

    /** Writes the PEM certificate to the public Downloads folder. Returns the file's Uri or null. */
    fun exportToDownloads(context: Context): Uri? {
        val ca = get(context)
        val pem = CertAuthority.pem(ca.caCert).toByteArray(Charsets.US_ASCII)
        val resolver = context.contentResolver
        return try {
            // Replace an earlier export so the installer always sees the current certificate.
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(FILE_NAME),
            )
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(pem) } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.w(TAG, "export failed", e)
            null
        }
    }

    /** Opens Android's security settings, where "Install from device storage" lives. */
    fun openSecuritySettings(context: Context): Boolean {
        val candidates = listOf(
            Intent("android.settings.SECURITY_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    fun fingerprint(context: Context): String = runCatching { CertAuthority.fingerprint(get(context).caCert) }.getOrDefault("")

    /** Deletes the CA and its keys; the installed certificate must be removed from Settings by hand. */
    fun reset(context: Context) {
        synchronized(this) {
            authority = null
            dir(context).deleteRecursively()
        }
    }
}
