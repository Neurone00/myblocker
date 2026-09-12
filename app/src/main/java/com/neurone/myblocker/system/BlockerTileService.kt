package com.neurone.myblocker.system

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.neurone.myblocker.stats.StatsStore
import com.neurone.myblocker.ui.MainActivity
import com.neurone.myblocker.vpn.BlockerVpnService

/** Quick Settings toggle. */
class BlockerTileService : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (BlockerVpnService.isRunning) {
            BlockerVpnService.stop(this)
        } else if (VpnService.prepare(this) != null) {
            // Consent dialog needed: hand over to the main screen.
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        } else {
            BlockerVpnService.start(this)
        }
        // The service flips its state on a background thread; show the optimistic state now.
        qsTile?.let {
            it.state = if (BlockerVpnService.isRunning) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = BlockerVpnService.isRunning
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "MyBlocker"
        tile.subtitle = if (on) "Blocked today: ${StatsStore.todayBlocked()}" else "Off"
        tile.updateTile()
    }
}
