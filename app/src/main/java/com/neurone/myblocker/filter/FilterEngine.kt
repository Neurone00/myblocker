package com.neurone.myblocker.filter

import android.content.Context
import android.util.Log
import com.neurone.myblocker.Prefs
import java.util.concurrent.atomic.AtomicBoolean

/** Why a name was blocked or allowed. */
enum class Reason(val label: String) {
    USER_ALLOW("Your allowlist"),
    USER_BLOCK("Your blocklist"),
    SAFETY("Built-in safety list"),
    LIST("Blocklist"),
    NONE("Not listed"),
}

class Decision(val blocked: Boolean, val reason: Reason, val rule: String?) {
    companion object {
        val ALLOW = Decision(false, Reason.NONE, null)
    }
}

/**
 * Holds the currently active rule sets and answers "should this name be blocked?".
 * Lookups are lock-free; [reload] builds new sets on the calling thread and swaps them in.
 */
object FilterEngine {
    private const val TAG = "FilterEngine"

    @Volatile private var blocklist: DomainSet = DomainSet.EMPTY
    @Volatile private var userAllow: DomainSet = DomainSet.EMPTY
    @Volatile private var userBlock: DomainSet = DomainSet.EMPTY
    @Volatile private var safety: DomainSet = DomainSet.EMPTY
    @Volatile var loaded: Boolean = false
        private set
    @Volatile var blockedEntryCount: Int = 0
        private set
    @Volatile var lastLoadMillis: Long = 0
        private set

    private val reloading = AtomicBoolean(false)

    /**
     * Names the filter must never block, regardless of lists. These are needed for
     * Play, push notifications, connectivity checks, Samsung services and this app's
     * own upstream DNS. Applied only when the "safety list" setting is on.
     */
    val SAFETY_DOMAINS: List<String> = listOf(
        "connectivitycheck.gstatic.com", "connectivitycheck.android.com", "play.googleapis.com",
        "android.clients.google.com", "mtalk.google.com", "fcm.googleapis.com",
        "firebaseinstallations.googleapis.com", "accounts.google.com", "oauth2.googleapis.com",
        "www.googleapis.com", "androidx.dev", "clients3.google.com", "clients4.google.com",
        "time.android.com", "graph.facebook.com", "b-graph.facebook.com", "api.whatsapp.com",
        "samsungcloud.com", "samsungosp.com", "ospserver.net", "samsungcloudsolution.com",
        "account.samsung.com", "samsungapps.com", "smartthings.com", "samsungpay.com",
        "dns.quad9.net", "cloudflare-dns.com", "dns.google", "dns.adguard-dns.com",
        "family.cloudflare-dns.com", "family.adguard-dns.com", "doh.cleanbrowsing.org",
        "raw.githubusercontent.com", "github.com", "objects.githubusercontent.com",
    )

    fun decide(host: String): Decision {
        if (!loaded) return Decision.ALLOW
        val h = if (host.endsWith('.')) host.dropLast(1) else host
        userAllow.matches(h)?.let { return Decision(false, Reason.USER_ALLOW, it) }
        userBlock.matches(h)?.let { return Decision(true, Reason.USER_BLOCK, it) }
        safety.matches(h)?.let { return Decision(false, Reason.SAFETY, it) }
        blocklist.matches(h)?.let { return Decision(true, Reason.LIST, it) }
        return Decision.ALLOW
    }

    fun isBlocked(host: String): Boolean = decide(host).blocked

    /** Rebuilds all sets from preferences and list files. Blocking; call off the main thread. */
    fun reload(context: Context) {
        if (!reloading.compareAndSet(false, true)) {
            // Another reload is in flight; wait for it so callers can rely on fresh data.
            while (reloading.get()) Thread.sleep(50)
            return
        }
        val start = System.currentTimeMillis()
        try {
            val prefs = Prefs.get(context)
            val repo = ListRepository(context)
            val enabled = prefs.enabledSources
            val set = HashSet<String>(300_000)
            var loadedSources = 0
            for (src in repo.allSources()) {
                if (src.id !in enabled) continue
                val stream = repo.open(src) ?: continue
                try {
                    val before = set.size
                    stream.use { ListParser.parse(it, set) }
                    repo.recordBundledCount(src.id, set.size - before)
                    loadedSources++
                } catch (e: Exception) {
                    Log.w(TAG, "failed to load ${src.id}", e)
                }
            }
            // Apply the new sets atomically enough for our purposes: each field is volatile
            // and a lookup that races a reload just uses the previous consistent set.
            userAllow = DomainSet.of(prefs.userAllow)
            userBlock = DomainSet.of(prefs.userBlock)
            safety = if (prefs.safetyList) DomainSet.of(SAFETY_DOMAINS) else DomainSet.EMPTY
            blocklist = DomainSet.wrap(set)
            blockedEntryCount = set.size
            lastLoadMillis = System.currentTimeMillis() - start
            loaded = true
            Log.i(TAG, "loaded ${set.size} domains from $loadedSources sources in ${lastLoadMillis}ms")
        } finally {
            reloading.set(false)
        }
    }

    fun reloadAsync(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        Thread({
            try {
                reload(app)
            } catch (e: Exception) {
                Log.e(TAG, "reload failed", e)
            }
            onDone?.invoke()
        }, "filter-reload").start()
    }

    /** Fast path for the user rule sets only (no list re-parse). */
    fun reloadUserRules(context: Context) {
        val prefs = Prefs.get(context)
        userAllow = DomainSet.of(prefs.userAllow)
        userBlock = DomainSet.of(prefs.userBlock)
        safety = if (prefs.safetyList) DomainSet.of(SAFETY_DOMAINS) else DomainSet.EMPTY
    }
}
