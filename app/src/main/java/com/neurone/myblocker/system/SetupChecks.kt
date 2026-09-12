package com.neurone.myblocker.system

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.R
import com.neurone.myblocker.tls.CaInstall
import java.util.concurrent.Executor

/** The One UI settings that decide whether the umbrella stays open all day. */
object SetupChecks {
    /** [screen] names an in-app destination ("advanced") used instead of [intent] when set. */
    class Item(val id: String, val title: String, val detail: String, val done: Boolean, val intent: Intent?, val screen: String? = null)

    fun batteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** True unless Private DNS is in "provider hostname" mode, which routes lookups around the filter. */
    fun privateDnsOk(context: Context): Boolean {
        val mode = runCatching { Settings.Global.getString(context.contentResolver, "private_dns_mode") }.getOrNull()
        return mode == null || mode == "off" || mode == "opportunistic"
    }

    /** Best effort: Android exposes the always-on choice only through a hidden setting. */
    fun alwaysOnOk(context: Context): Boolean {
        val pkg = runCatching { Settings.Secure.getString(context.contentResolver, "always_on_vpn_app") }.getOrNull()
        if (pkg == context.packageName) return true
        return Prefs.get(context).alwaysOnAcknowledged
    }

    fun items(context: Context): List<Item> {
        val battery = batteryUnrestricted(context)
        val dns = privateDnsOk(context)
        val alwaysOn = alwaysOnOk(context)
        val prefs = Prefs.get(context)
        val tidy = mutableListOf<Item>()
        if (prefs.deepClean) {
            // Deep clean is on: page tidying also needs its toggle and the certificate, and people
            // reasonably expect the ad boxes to go once "the options are active".
            val toggled = prefs.interceptBrowsers
            tidy += Item(
                "tidy", "Turn on page tidying in browsers",
                if (toggled) "Done." else "Advanced › Deep clean › \"Tidy pages in browsers\", so empty ad boxes disappear in Chrome.",
                toggled, null, screen = "advanced",
            )
            if (toggled) {
                val cert = CaInstall.isInstalled(context)
                tidy += Item(
                    "cert", "Install the page-tidying certificate",
                    if (cert) "Done." else "Without it Chrome cannot be tidied and pages pass through untouched. Advanced › Certificate: save it, then install it from Settings.",
                    cert, null, screen = "advanced",
                )
            }
        }
        return tidy + listOf(
            Item(
                "battery", "Let Adbrella run in the background",
                if (battery) "Done. The phone will not close the umbrella to save battery." else "Battery → Unrestricted, so One UI stops closing the umbrella.",
                battery,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            ),
            Item(
                "dns", "Stop the phone from skipping the umbrella",
                if (dns) "Done. Private DNS is off or automatic." else "Connections → More connection settings → Private DNS → Off.",
                dns,
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            ),
            Item(
                "alwayson", "Keep it open after a restart",
                if (alwaysOn) "Done. Always-on VPN is set." else "VPN → gear next to Adbrella → Always-on VPN. Leave \"Block connections without VPN\" off.",
                alwaysOn,
                Intent(Settings.ACTION_VPN_SETTINGS),
            ),
        )
    }

    fun allDone(context: Context): Boolean = items(context).all { it.done }

    /** Android 13+: asks the system to add our Quick Settings tile. */
    fun requestTile(context: Context, executor: Executor, onResult: (Int) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val sbm = context.getSystemService(StatusBarManager::class.java) ?: return false
        sbm.requestAddTileService(
            ComponentName(context, BlockerTileService::class.java),
            context.getString(R.string.app_name),
            Icon.createWithResource(context, R.drawable.ic_umbrella),
            executor,
        ) { onResult(it) }
        return true
    }
}
