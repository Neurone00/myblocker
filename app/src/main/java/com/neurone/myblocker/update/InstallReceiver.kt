package com.neurone.myblocker.update

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.neurone.myblocker.R
import com.neurone.myblocker.system.Notifications

/**
 * Receives PackageInstaller status for the self-update session. When Android
 * wants the user to confirm, we either open the confirmation directly (app in
 * foreground) or post a notification that opens it.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (foreground) {
                    try {
                        context.startActivity(confirm)
                        return
                    } catch (_: Exception) {
                    }
                }
                val info = (Updater.state.value as? Updater.State.Installing)?.info
                val pi = PendingIntent.getActivity(
                    context, 42, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val n = Notification.Builder(context, Notifications.CHANNEL_UPDATES)
                    .setSmallIcon(R.drawable.ic_umbrella)
                    .setContentTitle(context.getString(R.string.update_ready_title))
                    .setContentText(context.getString(R.string.update_ready_text, info?.versionName ?: ""))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(Notifications.ID_UPDATE, n)
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = Updater.State.Idle
            else -> {
                Log.w("InstallReceiver", "install failed: $status $message")
                Updater.state.value = Updater.State.Error("Install failed: ${message ?: "code $status"}")
            }
        }
    }

    companion object {
        const val ACTION = "com.neurone.myblocker.INSTALL_STATUS"

        /** Set by MainActivity so we know whether we may pop the confirmation dialog directly. */
        @Volatile var foreground: Boolean = false
    }
}
