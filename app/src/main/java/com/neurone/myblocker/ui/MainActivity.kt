package com.neurone.myblocker.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import com.neurone.myblocker.BuildConfig
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.R
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.stats.Levels
import com.neurone.myblocker.stats.StatsStore
import com.neurone.myblocker.vpn.BlockerVpnService

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var toggle: Switch
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var todayText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.get(this)
        StatsStore.init(this)

        toggle = bind(R.id.toggle)
        statusText = bind(R.id.status_text)
        statusDetail = bind(R.id.status_detail)
        todayText = bind(R.id.today_blocked)
        bind<TextView>(R.id.version).text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})"

        toggle.setOnClickListener {
            if (toggle.isChecked) requestStart() else BlockerVpnService.stop(this)
        }
        bind<Button>(R.id.btn_stats).setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        bind<Button>(R.id.btn_log).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        bind<Button>(R.id.btn_lists).setOnClickListener { startActivity(Intent(this, ListsActivity::class.java)) }
        bind<Button>(R.id.btn_rules).setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
        bind<Button>(R.id.btn_apps).setOnClickListener { startActivity(Intent(this, AppsActivity::class.java)) }
        bind<Button>(R.id.btn_settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        bind<Button>(R.id.btn_help).setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }

        if (!prefs.onboardingDone) {
            prefs.onboardingDone = true
            showWelcome()
        } else if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            requestStart()
        } else if (intent.getStringExtra("open") == "stats") {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        if (!FilterEngine.loaded) FilterEngine.reloadAsync(this) { handler.post { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(BlockerVpnService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
        runCatching { unregisterReceiver(stateReceiver) }
    }

    private fun requestStart() {
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQ_VPN)
        } else {
            BlockerVpnService.start(this)
            refresh()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) BlockerVpnService.start(this) else {
                toggle.isChecked = false
                AlertDialog.Builder(this)
                    .setTitle("VPN permission needed")
                    .setMessage("MyBlocker filters DNS through a local VPN. Nothing leaves your phone through a remote server; the permission only lets the app see DNS lookups.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun refresh() {
        val running = BlockerVpnService.isRunning
        val starting = BlockerVpnService.isStarting
        if (!toggle.isPressed) toggle.isChecked = running || starting
        statusText.text = if (running) "Protected" else BlockerVpnService.state.ifEmpty { "Off" }
        val err = BlockerVpnService.lastError
        statusDetail.text = when {
            starting -> "Starting protection…"
            running -> "Filtering DNS for every app · ${Ui.format(FilterEngine.blockedEntryCount.toLong())} rules · ${prefs.level.label}"
            err != null -> err
            FilterEngine.loaded -> "Tap the switch to start · ${Ui.format(FilterEngine.blockedEntryCount.toLong())} rules ready"
            else -> "Loading blocklists…"
        }
        todayText.text = Ui.format(StatsStore.todayBlocked())
        bind<TextView>(R.id.total_blocked).text = Ui.format(StatsStore.totalBlocked)
        bind<TextView>(R.id.streak).text = StatsStore.streakDays().toString()

        val level = Levels.forXp(StatsStore.totalBlocked)
        bind<TextView>(R.id.level_title).text = "Level ${level.level} · ${level.title}"
        bind<ProgressBar>(R.id.level_progress).progress = level.progressPercent
        bind<TextView>(R.id.level_detail).text = level.nextThreshold?.let {
            "${Ui.format(level.xp)} / ${Ui.format(it)} blocked to reach level ${level.level + 1}"
        } ?: "Maximum level reached"

        val hours = StatsStore.lastHours(24)
        val chart = bind<BarChartView>(R.id.chart_24h)
        val nowHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        chart.setData(
            hours.map { it.blocked }.toLongArray(),
            Array(24) { i -> if (i % 6 == 0) "${(nowHour - 23 + i + 24) % 24}h" else null },
        )
        bind<TextView>(R.id.chart_caption).text = "Blocked in the last 24 hours: ${Ui.format(hours.sumOf { it.blocked })}"
    }

    private fun showWelcome() {
        AlertDialog.Builder(this)
            .setTitle("Welcome to MyBlocker")
            .setMessage(
                "MyBlocker blocks ads and trackers in every app by filtering DNS through a local VPN. " +
                    "No traffic is sent to any server of ours.\n\n" +
                    "Blocked requests fail instantly, so apps keep working. If something breaks, open the Query log and tap the domain to allow it, or add the app to Bypass.\n\n" +
                    "What it cannot do: remove in-stream video ads on YouTube, Instagram or TikTok, because those come from the same servers as the videos. Rewarded ads will simply be unavailable.\n\n" +
                    "Turn it on with the big switch, then check the Setup guide for the Samsung battery settings.",
            )
            .setPositiveButton("Turn on") { _, _ -> toggle.isChecked = true; requestStart() }
            .setNegativeButton("Later", null)
            .show()
    }

    companion object {
        const val EXTRA_AUTO_START = "auto_start"
        private const val REQ_VPN = 100
        private const val REQ_NOTIFICATIONS = 101
    }
}
