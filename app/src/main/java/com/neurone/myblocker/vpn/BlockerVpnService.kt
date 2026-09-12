package com.neurone.myblocker.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.stats.Achievements
import com.neurone.myblocker.stats.AppNames
import com.neurone.myblocker.stats.LogEntry
import com.neurone.myblocker.stats.QueryLog
import com.neurone.myblocker.stats.StatsStore
import com.neurone.myblocker.system.Notifications
import com.neurone.myblocker.ui.MainActivity
import com.neurone.myblocker.upstream.UpstreamFactory
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Local VPN that captures only DNS. The tunnel gets a private address, a fake
 * DNS server inside that range and a route to that single address, so every
 * app's lookups land in [DnsProxy] while all other traffic uses the network
 * directly. This app itself is excluded from the tunnel, as are bypassed apps.
 */
class BlockerVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var proxy: DnsProxy? = null
    private var thread: Thread? = null
    @Volatile private var stopRequested = false
    private var restarts = 0
    /** Incremented for every tunnel thread; lets a dying thread tell whether it has been superseded. */
    @Volatile private var generation = 0
    private lateinit var appNames: AppNames
    private val logExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "query-log").apply { isDaemon = true } }
    private val handler = Handler(Looper.getMainLooper())
    private val notificationTicker = object : Runnable {
        override fun run() {
            if (isRunning) {
                Notifications.updateRunning(this@BlockerVpnService)
                handler.postDelayed(this, NOTIFICATION_REFRESH_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appNames = AppNames(this)
        StatsStore.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.get(this).wantsProtection = false
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                startForegroundCompat()
                restartVpn()
                return START_STICKY
            }
            else -> {
                // ACTION_START, always-on start (SERVICE_INTERFACE) or a sticky restart with a null intent.
                startForegroundCompat()
                Prefs.get(this).wantsProtection = true
                startVpn()
                return START_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked by system or another VPN")
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        StatsStore.flush()
        logExecutor.shutdown()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = Notifications.buildRunning(this, starting = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(Notifications.ID_RUNNING, notification)
        }
    }

    @Synchronized
    private fun startVpn() {
        if (thread?.isAlive == true) return
        stopRequested = false
        isStarting = true
        val gen = ++generation
        val t = Thread({ runTunnel(gen) }, "vpn-tunnel")
        thread = t
        t.start()
    }

    @Synchronized
    private fun restartVpn() {
        stopRequested = true
        proxy?.stop()
        runCatching { tun?.close() }
        thread?.join(3000)
        thread = null
        restarts = 0
        startVpn()
    }

    @Synchronized
    private fun stopVpn() {
        stopRequested = true
        proxy?.stop()
        runCatching { tun?.close() }
        tun = null
        handler.removeCallbacks(notificationTicker)
        isRunning = false
        isStarting = false
        broadcastState()
    }

    private fun runTunnel(gen: Int) {
        try {
            state = "Loading blocklists…"
            broadcastState()
            if (!FilterEngine.loaded) FilterEngine.reload(this)
            if (stopRequested) return

            val prefs = Prefs.get(this)
            val pfd = buildTunnel(prefs) ?: run {
                lastError = "Could not establish the VPN interface. Grant the VPN permission and retry."
                state = "Error"
                broadcastState()
                return
            }
            tun = pfd
            val upstream = UpstreamFactory.create(this) { protect(it) }
            val p = DnsProxy(pfd, upstream, prefs.blockMode, MTU) { onQuery(it) }
            proxy = p
            isRunning = true
            isStarting = false
            lastError = null
            state = "Protected"
            restarts = 0
            StatsStore.markActive()
            broadcastState()
            handler.post(notificationTicker)
            p.run() // blocks until stopped or the tunnel dies
        } catch (e: Exception) {
            Log.e(TAG, "tunnel failed", e)
            lastError = e.message
        } finally {
            val wasStopRequested = stopRequested
            runCatching { tun?.close() }
            if (gen == generation) {
                tun = null
                proxy = null
                isRunning = false
            }
            StatsStore.flush()
            if (!wasStopRequested && restarts < MAX_RESTARTS) {
                restarts++
                state = "Restarting…"
                isStarting = true
                broadcastState()
                Log.w(TAG, "tunnel died unexpectedly, restart #$restarts")
                handler.postDelayed({ if (!stopRequested && gen == generation) startVpn() }, 1500L * restarts)
            } else if (gen == generation) {
                // Genuinely stopped (not superseded by a restart): tear the service down.
                isStarting = false
                state = "Off"
                broadcastState()
                handler.post {
                    if (gen == generation && !isRunning) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun buildTunnel(prefs: Prefs): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(com.neurone.myblocker.R.string.app_name))
            .setMtu(MTU)
            .addAddress(TUN_ADDRESS_V4, 24)
            .addAddress(TUN_ADDRESS_V6, 64)
            .addDnsServer(DNS_ADDRESS_V4)
            .addDnsServer(DNS_ADDRESS_V6)
            .addRoute(DNS_ADDRESS_V4, 32)
            .addRoute(DNS_ADDRESS_V6, 128)
            .setBlocking(true)
        builder.setMetered(false)
        if (prefs.catchHardcodedResolvers) {
            for (ip in HARDCODED_RESOLVERS) runCatching { builder.addRoute(ip, 32) }
            for (ip in HARDCODED_RESOLVERS_V6) runCatching { builder.addRoute(ip, 128) }
        }
        val configure = Intent(this, MainActivity::class.java)
        builder.setConfigureIntent(
            PendingIntent.getActivity(this, 0, configure, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        // Our own traffic (DoH, list downloads) must never loop back into the tunnel.
        runCatching { builder.addDisallowedApplication(packageName) }
        for (pkg in prefs.bypassApps) {
            runCatching { builder.addDisallowedApplication(pkg) }.onFailure { Log.d(TAG, "bypass app missing: $pkg") }
        }
        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
    }

    private fun onQuery(event: QueryEvent) {
        val prefs = Prefs.get(this)
        val log = prefs.logEnabled
        logExecutor.execute {
            val d = event.datagram
            val pkg = if (log) appNames.packageFor(d.src, d.srcPort, d.dst, d.dstPort) else null
            StatsStore.record(event.question.name, event.decision.blocked, pkg, event.time)
            if (log) {
                QueryLog.add(
                    LogEntry(
                        event.time, event.question.name, event.question.typeName, event.decision.blocked,
                        event.decision.reason, event.decision.rule, pkg,
                    ),
                )
            }
            if (event.decision.blocked && StatsStore.totalBlocked % 50 == 1L) {
                val fresh = Achievements.evaluate()
                if (fresh.isNotEmpty() && prefs.achievementNotifications) {
                    for (a in fresh) Notifications.showAchievement(this, a)
                }
            }
        }
    }

    private fun broadcastState() {
        val i = Intent(ACTION_STATE_CHANGED).setPackage(packageName)
        i.putExtra(EXTRA_RUNNING, isRunning)
        i.putExtra(EXTRA_STATE, state)
        sendBroadcast(i)
    }

    companion object {
        private const val TAG = "BlockerVpnService"
        const val ACTION_START = "com.neurone.myblocker.START"
        const val ACTION_STOP = "com.neurone.myblocker.STOP"
        const val ACTION_RESTART = "com.neurone.myblocker.RESTART"
        const val ACTION_STATE_CHANGED = "com.neurone.myblocker.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_STATE = "state"

        const val MTU = 4000
        const val TUN_ADDRESS_V4 = "10.111.222.1"
        const val DNS_ADDRESS_V4 = "10.111.222.2"
        const val TUN_ADDRESS_V6 = "fd53:4d59:424c::1"
        const val DNS_ADDRESS_V6 = "fd53:4d59:424c::2"
        private const val MAX_RESTARTS = 5
        private const val NOTIFICATION_REFRESH_MS = 30_000L

        @Volatile var isRunning: Boolean = false
            private set(v) { field = v; running.value = v }
        /** True while lists load or the tunnel is being (re)established. */
        @Volatile var isStarting: Boolean = false
            private set(v) { field = v; starting.value = v }
        @Volatile var state: String = "Off"
            private set(v) { field = v; stateText.value = v }
        @Volatile var lastError: String? = null
            private set

        /** Observable mirrors of the flags above for Compose. */
        val running = MutableStateFlow(false)
        val starting = MutableStateFlow(false)
        val stateText = MutableStateFlow("Off")

        /**
         * Public resolvers some apps talk to directly, skipping the system DNS. Routing them into
         * the tunnel means those lookups are filtered too; DoT/DoH attempts to them get a TCP reset
         * so the app falls back to plain DNS (which we then filter).
         */
        val HARDCODED_RESOLVERS: List<String> = listOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
            "208.67.222.222", "208.67.220.220", "94.140.14.14", "94.140.15.15", "76.76.2.0", "76.76.10.0",
        )
        val HARDCODED_RESOLVERS_V6: List<String> = listOf(
            "2001:4860:4860::8888", "2001:4860:4860::8844", "2606:4700:4700::1111", "2606:4700:4700::1001",
            "2620:fe::fe", "2620:fe::9",
        )

        fun start(context: Context) {
            val i = Intent(context, BlockerVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, BlockerVpnService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        /** Re-establishes the tunnel to pick up new bypass apps or upstream settings. No-op when off. */
        fun restartIfRunning(context: Context) {
            if (!isRunning) return
            val i = Intent(context, BlockerVpnService::class.java).setAction(ACTION_RESTART)
            context.startForegroundService(i)
        }
    }
}
