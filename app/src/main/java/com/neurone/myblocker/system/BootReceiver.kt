package com.neurone.myblocker.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.vpn.BlockerVpnService

/** Restores protection after a reboot or an app update, if the user left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs.get(context)
        if (!prefs.startAtBoot || !prefs.wantsProtection) return
        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN consent missing; cannot auto-start")
            return
        }
        ListUpdateJobService.schedule(context)
        BlockerVpnService.start(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
