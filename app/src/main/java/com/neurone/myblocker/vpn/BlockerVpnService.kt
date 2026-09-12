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
import com.neurone.myblocker.proxy.DeepCleanStats
import com.neurone.myblocker.proxy.InterceptProxy
import com.neurone.myblocker.tls.CaInstall
import com.neurone.myblocker.web.WebFilters
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
    private var relayThread: Thread? = null
    private var interceptProxy: InterceptProxy? = null
    @Volatile private var stopRequested = false
    private var restarts = 0
    /** Incremented for every tunnel thread; lets a dying thread tell whether it has been superseded. */
    @Volatile private var generation = 0
    private var carConnection: com.neurone.myblocker.system.CarConnection? = null
    private var carReceiver: android.content.BroadcastReceiver? = null
    @Volatile private var carBtConnected = false
    /** True while protection is held down because the phone is connected to a car (Android Auto rejects any VPN). */
    @Volatile private var pausedForCar = false
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
        if (Prefs.get(this).pauseForAndroidAuto) startCarWatch()
    }

    /**
     * Watches for the car itself connecting, not for Android Auto projecting. Android Auto checks for
     * a VPN before it publishes any projection state, so the projection signal comes too late; the
     * Bluetooth link to the car and car mode fire when you get in, before Android Auto tries to start.
     */
    private fun startCarWatch() {
        if (carReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED ->
                        if (isCarBluetooth(intent)) { carBtConnected = true; handler.post { onCarState(true) } }
                    android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                        if (isCarBluetooth(intent)) { carBtConnected = false; handler.post { onCarState(false) } }
                    android.app.UiModeManager.ACTION_ENTER_CAR_MODE -> handler.post { onCarState(true) }
                    android.app.UiModeManager.ACTION_EXIT_CAR_MODE -> if (!carBtConnected) handler.post { onCarState(false) }
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.app.UiModeManager.ACTION_ENTER_CAR_MODE)
            addAction(android.app.UiModeManager.ACTION_EXIT_CAR_MODE)
        }
        runCatching { registerReceiver(r, filter) }
        carReceiver = r
        // Projection state is a late but reliable extra resume signal.
        carConnection = com.neurone.myblocker.system.CarConnection(this) { projecting ->
            if (projecting) handler.post { onCarState(true) }
        }
        carConnection?.start()
        // If we are already connected to the car (service started mid-drive), pause now.
        if (isCarBluetoothConnectedNow() || com.neurone.myblocker.system.CarConnection.isProjecting(this)) {
            carBtConnected = true
            handler.post { onCarState(true) }
        }
    }

    private fun stopCarWatch() {
        carReceiver?.let { runCatching { unregisterReceiver(it) } }
        carReceiver = null
        carConnection?.stop()
        carConnection = null
    }

    /** Registers or removes the car watch to match the current preference. */
    private fun syncCarWatch() {
        if (Prefs.get(this).pauseForAndroidAuto) startCarWatch() else stopCarWatch()
    }

    /** True if the Bluetooth device in [intent] is a car kit (car audio or hands-free class). */
    private fun isCarBluetooth(intent: Intent): Boolean {
        val device: android.bluetooth.BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
        return device != null && isCarDevice(device)
    }

    private fun isCarDevice(device: android.bluetooth.BluetoothDevice): Boolean {
        return try {
            val cls = device.bluetoothClass ?: return false
            val dc = cls.deviceClass
            dc == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                dc == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        } catch (e: SecurityException) {
            false // BLUETOOTH_CONNECT not granted; car mode remains the trigger
        }
    }

    private fun isCarBluetoothConnectedNow(): Boolean {
        return try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager ?: return false
            val adapter = bm.adapter ?: return false
            val bonded = adapter.bondedDevices ?: return false
            bonded.any { isCarDevice(it) && isDeviceConnected(bm, it) }
        } catch (e: Exception) {
            false
        }
    }

    private fun isDeviceConnected(bm: android.bluetooth.BluetoothManager, device: android.bluetooth.BluetoothDevice): Boolean {
        return try {
            bm.getConnectionState(device, android.bluetooth.BluetoothProfile.GATT) == android.bluetooth.BluetoothProfile.STATE_CONNECTED ||
                bm.getConnectedDevices(android.bluetooth.BluetoothProfile.HEADSET).contains(device)
        } catch (e: Exception) {
            false
        }
    }

    /** Android Auto rejects any active VPN, so hold protection down while it projects and restore after. */
    private fun onCarState(projecting: Boolean) {
        if (!Prefs.get(this).pauseForAndroidAuto) return
        if (projecting && !pausedForCar) {
            pausedForCar = true
            state = "Paused for Android Auto"
            Log.i(TAG, "Android Auto connected: pausing protection")
            stopRequested = true
            proxy?.stop()
            runCatching { tun?.close() }
            tun = null
            isRunning = false
            isStarting = false
            broadcastState()
            runCatching { Notifications.updateRunning(this) }
        } else if (!projecting && pausedForCar) {
            pausedForCar = false
            Log.i(TAG, "Android Auto disconnected: resuming protection")
            if (Prefs.get(this).wantsProtection) {
                stopRequested = false
                startForegroundCompat()
                startVpn()
            }
        }
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
                syncCarWatch()
                restartVpn()
                return START_STICKY
            }
            else -> {
                // ACTION_START, always-on start (SERVICE_INTERFACE) or a sticky restart with a null intent.
                startForegroundCompat()
                Prefs.get(this).wantsProtection = true
                syncCarWatch()
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
        stopCarWatch()
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
        // While Android Auto is projecting, stay down no matter what asks us to start (tile, always-on
        // restart, boot). We come back automatically when the car disconnects.
        if (Prefs.get(this).pauseForAndroidAuto && (pausedForCar || com.neurone.myblocker.system.CarConnection.isProjecting(this))) {
            pausedForCar = true
            state = "Paused for Android Auto"
            broadcastState()
            return
        }
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
            var relay: FullTunnel? = null
            var intercept: InterceptProxy? = null
            DeepCleanStats.reset()
            DeepCleanStats.intercepting = false
            if (prefs.deepClean) {
                val resolverIps = (HARDCODED_RESOLVERS + HARDCODED_RESOLVERS_V6 + DNS_ADDRESS_V4 + DNS_ADDRESS_V6)
                    .mapNotNull { runCatching { java.net.InetAddress.getByName(it).address.toList() }.getOrNull() }.toHashSet()
                val internalIp = InterceptProxy.INTERNAL_IP.toList()
                // Page tidying needs the local certificate to be trusted by the browser; otherwise stay transparent.
                val canIntercept = prefs.interceptBrowsers && CaInstall.isInstalled(this)
                if (canIntercept) {
                    intercept = InterceptProxy(CaInstall.get(this), { protect(it) }, { WebFilters.cosmeticRules(this) })
                    intercept.userExcluded = prefs.webExcludedHosts
                    intercept.start()
                    Thread({ WebFilters.cosmeticRules(this) }, "cosmetic-parse").start()
                }
                val browsers = BrowserUids(this)
                relay = FullTunnel(
                    protectTcp = { protect(it) },
                    protectUdp = { protect(it) },
                    writeToTun = { pkt -> proxy?.writePacket(pkt) },
                    policy = object : FullTunnel.Policy {
                        override fun resetTcp(dst: ByteArray, dstPort: Int): Boolean =
                            (dstPort == 53 || dstPort == 853 || dstPort == 443) && dst.toList() in resolverIps

                        override fun dropUdp(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Boolean {
                            // While intercepting, drop all QUIC (UDP/443). Per-connection UID lookup is
                            // unreliable for UDP, and if a browser stays on HTTP/3 it bypasses tidying
                            // entirely. Dropping QUIC makes every client fall back to interceptable TCP;
                            // non-browser apps simply use TCP and still pass through untouched.
                            val drop = intercept != null && dstPort == 443
                            if (drop) DeepCleanStats.quicDropped++
                            return drop
                        }

                        override fun intercept(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): Boolean {
                            if (intercept == null) return false
                            if (dst.toList() == internalIp) return true
                            if (dstPort != 443 && dstPort != 80) return false
                            return browsers.isBrowserTcp(src, srcPort, dst, dstPort)
                        }
                    },
                )
                val ip = intercept
                if (ip != null) {
                    relay.redirect = object : FullTunnel.Redirect {
                        override val address = java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), ip.port)
                        override fun register(localPort: Int, dst: ByteArray, dstPort: Int) = ip.register(localPort, dst, dstPort)
                    }
                }
                relayThread = Thread(relay, "relay").also { it.isDaemon = true }
            }
            interceptProxy = intercept
            DeepCleanStats.intercepting = intercept != null
            val p = DnsProxy(
                pfd, upstream, prefs.blockMode, MTU, { onQuery(it) }, relay,
                internalHost = if (intercept != null) InterceptProxy.INTERNAL_HOST else null,
                internalIp = InterceptProxy.INTERNAL_IP,
            )
            proxy = p
            relayThread?.start()
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
            runCatching { interceptProxy?.stop() }
            interceptProxy = null
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
            .setBlocking(true)
        builder.setMetered(false)
        if (prefs.deepClean) {
            // Everything goes through the userspace relay.
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        } else {
            builder.addRoute(DNS_ADDRESS_V4, 32)
            builder.addRoute(DNS_ADDRESS_V6, 128)
        }
        if (prefs.catchHardcodedResolvers && !prefs.deepClean) {
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
        private const val NOTIFICATION_REFRESH_MS = 120_000L // Handler tick, no wakelock; only refreshes the count while the CPU is already awake

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
