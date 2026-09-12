package com.neurone.myblocker.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.Toast
import com.neurone.myblocker.BuildConfig
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.UpstreamMode
import com.neurone.myblocker.dns.BlockMode
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.vpn.BlockerVpnService

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private var needsRestart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        prefs = Prefs.get(this)
        col = Ui.page(this)
        render()
    }

    override fun onPause() {
        super.onPause()
        if (needsRestart) {
            needsRestart = false
            BlockerVpnService.restartIfRunning(this)
        }
    }

    private fun render() {
        col.removeAllViews()
        val ctx = this

        val dns = Ui.card(ctx)
        dns.addView(Ui.heading(ctx, "Upstream DNS"))
        dns.addView(Ui.muted(ctx, "Where allowed lookups are sent. Encrypted options hide your DNS from the carrier or Wi-Fi owner."))
        val group = RadioGroup(ctx)
        for (mode in UpstreamMode.entries) {
            val rb = RadioButton(ctx)
            rb.id = 2000 + mode.ordinal
            rb.text = "${mode.label}\n${mode.detail}"
            rb.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
            group.addView(rb)
        }
        group.check(2000 + prefs.upstreamMode.ordinal)
        group.setOnCheckedChangeListener { _, id ->
            val mode = UpstreamMode.entries[id - 2000]
            prefs.upstreamMode = mode
            needsRestart = true
            when (mode) {
                UpstreamMode.DOH_CUSTOM -> prompt("DNS-over-HTTPS URL", "https://dns.example/dns-query", prefs.customDohUrl) {
                    if (it.startsWith("https://")) prefs.customDohUrl = it else Toast.makeText(ctx, "URL must start with https://", Toast.LENGTH_SHORT).show()
                }
                UpstreamMode.PLAIN_CUSTOM -> prompt("DNS server IP(s)", "9.9.9.9, 149.112.112.112", prefs.customDnsIp) { prefs.customDnsIp = it }
                else -> Unit
            }
        }
        dns.addView(group)
        col.addView(dns)

        val block = Ui.card(ctx)
        block.addView(Ui.heading(ctx, "Blocked answer"))
        block.addView(Ui.muted(ctx, "How a blocked name is answered. Null IP is the most app-friendly; NXDOMAIN is what a missing domain looks like."))
        val bg = RadioGroup(ctx)
        val nullIp = RadioButton(ctx).apply { id = 3001; text = "Null IP (0.0.0.0 / ::) — recommended" }
        val nx = RadioButton(ctx).apply { id = 3002; text = "NXDOMAIN" }
        bg.addView(nullIp); bg.addView(nx)
        bg.check(if (prefs.blockMode == BlockMode.NULL_IP) 3001 else 3002)
        bg.setOnCheckedChangeListener { _, id ->
            prefs.blockMode = if (id == 3001) BlockMode.NULL_IP else BlockMode.NXDOMAIN
            needsRestart = true
        }
        block.addView(bg)
        col.addView(block)

        val behaviour = Ui.card(ctx)
        behaviour.addView(Ui.heading(ctx, "Behaviour"))
        behaviour.addView(switch("Start at boot", "Turn protection back on after a restart (needs the VPN permission granted once).", prefs.startAtBoot) { prefs.startAtBoot = it })
        behaviour.addView(switch("Query log", "Keep the last 1,500 lookups in memory and attribute them to apps. Needed for per-app stats.", prefs.logEnabled) { prefs.logEnabled = it })
        behaviour.addView(switch("Built-in safety allowlist", "Never block Play, push notifications, Samsung account and connectivity checks even if a list contains them.", prefs.safetyList) { prefs.safetyList = it; FilterEngine.reloadUserRules(ctx) })
        behaviour.addView(switch("Auto-update lists", "Refresh enabled blocklists every 12 hours.", prefs.autoUpdateLists) { prefs.autoUpdateLists = it })
        behaviour.addView(switch("Badge notifications", "Notify when you unlock an achievement.", prefs.achievementNotifications) { prefs.achievementNotifications = it })
        col.addView(behaviour)

        val system = Ui.card(ctx)
        system.addView(Ui.heading(ctx, "Keep it running on Samsung"))
        system.addView(Ui.muted(ctx, "One UI kills background apps aggressively. These three steps keep protection on."))
        system.addView(Ui.button(ctx, "1. Allow unrestricted battery") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(ctx, "Already unrestricted", Toast.LENGTH_SHORT).show()
            } else {
                @Suppress("BatteryLife")
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        })
        system.addView(Ui.button(ctx, "2. Set Always-on VPN") { open(Intent(Settings.ACTION_VPN_SETTINGS)) })
        system.addView(Ui.muted(ctx, "Tap the gear next to MyBlocker, enable Always-on VPN. Leave \"Block connections without VPN\" OFF, otherwise bypassed apps and MyBlocker's own encrypted DNS lose network access."))
        system.addView(Ui.button(ctx, "3. Turn off Private DNS") { open(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
        system.addView(Ui.muted(ctx, "Connections → More connection settings → Private DNS → Off. With Private DNS on, lookups take a detour around the filter."))
        system.addView(Ui.button(ctx, "Full setup guide & limitations") { startActivity(Intent(ctx, HelpActivity::class.java)) })
        col.addView(system)

        col.addView(Ui.muted(ctx, "MyBlocker v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}) · lists loaded: ${Ui.format(FilterEngine.blockedEntryCount.toLong())} rules"))
    }

    private fun switch(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val ctx = this
        val row = Ui.row(ctx)
        row.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
        val text = Ui.column(ctx)
        text.addView(Ui.body(ctx, title))
        text.addView(Ui.muted(ctx, detail))
        row.addView(Ui.weight(text, 1f))
        val sw = Switch(ctx)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.addView(sw)
        return row
    }

    private fun prompt(title: String, hint: String, current: String, onDone: (String) -> Unit) {
        val edit = EditText(this)
        edit.hint = hint
        edit.setText(current)
        val box = Ui.column(this)
        val pad = Ui.dp(this, 20)
        box.setPadding(pad, 0, pad, 0)
        box.addView(edit)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> onDone(edit.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "That settings screen is not available on this device", Toast.LENGTH_SHORT).show()
        }
    }
}
