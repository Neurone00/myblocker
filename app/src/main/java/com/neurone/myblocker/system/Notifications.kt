package com.neurone.myblocker.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.neurone.myblocker.R
import com.neurone.myblocker.stats.Achievement
import com.neurone.myblocker.stats.StatsStore
import com.neurone.myblocker.ui.MainActivity
import com.neurone.myblocker.vpn.BlockerVpnService

object Notifications {
    const val CHANNEL_RUNNING = "running"
    const val CHANNEL_ACHIEVEMENTS = "achievements"
    const val CHANNEL_UPDATES = "updates"
    const val ID_RUNNING = 1
    const val ID_UPDATE = 2
    private const val ID_ACHIEVEMENT_BASE = 1000

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the umbrella is open"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACHIEVEMENTS, context.getString(R.string.notification_channel_badges), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Milestones and badges"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.notification_channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun buildRunning(context: Context, starting: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, BlockerVpnService::class.java).setAction(BlockerVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val today = StatsStore.todayBlocked()
        val text = if (starting && !BlockerVpnService.isRunning) "Opening…" else "$today bounced today · ${StatsStore.totalBlocked} all time"
        return Notification.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_umbrella)
            .setContentTitle(if (BlockerVpnService.isRunning || starting) "Umbrella open" else "Adbrella")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.notification_stop), stop).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    fun updateRunning(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_RUNNING, buildRunning(context))
    }

    fun showAchievement(context: Context, a: Achievement) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java).putExtra("open", "stats"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL_ACHIEVEMENTS)
            .setSmallIcon(R.drawable.ic_umbrella)
            .setContentTitle("Badge earned: ${a.title}")
            .setContentText(a.description)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(ID_ACHIEVEMENT_BASE + a.id.hashCode().and(0xffff), n)
    }
}
