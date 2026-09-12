package com.neurone.myblocker

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import com.neurone.myblocker.dns.BlockMode
import com.neurone.myblocker.filter.ListSource
import com.neurone.myblocker.filter.ProtectionLevel

/** Upstream resolver choices shown in Settings. */
enum class UpstreamMode(val label: String, val detail: String) {
    DOH_QUAD9("Quad9 (DNS-over-HTTPS)", "Encrypted. Also blocks known malware domains. Default."),
    DOH_CLOUDFLARE("Cloudflare 1.1.1.1 (DNS-over-HTTPS)", "Encrypted. Very fast, no extra filtering."),
    DOH_GOOGLE("Google (DNS-over-HTTPS)", "Encrypted. Fast."),
    DOH_ADGUARD("AdGuard (DNS-over-HTTPS)", "Encrypted. Extra ad filtering on the resolver side."),
    DOH_CUSTOM("Custom DNS-over-HTTPS URL", "Any RFC 8484 endpoint, e.g. https://dns.example/dns-query"),
    SYSTEM("Network DNS (unencrypted)", "Uses the DNS servers of the Wi-Fi or mobile network you are on."),
    PLAIN_CUSTOM("Custom DNS server IP (unencrypted)", "Plain DNS to an IP address you choose.");

    companion object {
        fun from(name: String?): UpstreamMode = entries.firstOrNull { it.name == name } ?: DOH_QUAD9
    }
}

/** Thin typed wrapper around SharedPreferences. */
class Prefs private constructor(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("myblocker", Context.MODE_PRIVATE)

    /** Bumped on every change so Compose screens can re-read values. */
    val changes = MutableStateFlow(0L)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changes.value = changes.value + 1 }

    init {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    var level: ProtectionLevel
        get() = ProtectionLevel.from(sp.getString(KEY_LEVEL, null))
        set(v) = sp.edit().putString(KEY_LEVEL, v.name).apply()

    var enabledSources: Set<String>
        get() = sp.getStringSet(KEY_SOURCES, null)?.toSet() ?: defaultSourcesFor(ProtectionLevel.AGGRESSIVE)
        set(v) = sp.edit().putStringSet(KEY_SOURCES, HashSet(v)).apply()

    var customSourcesJson: String
        get() = sp.getString(KEY_CUSTOM_SOURCES, "[]") ?: "[]"
        set(v) = sp.edit().putString(KEY_CUSTOM_SOURCES, v).apply()

    var listMetaJson: String
        get() = sp.getString(KEY_LIST_META, "{}") ?: "{}"
        set(v) = sp.edit().putString(KEY_LIST_META, v).apply()

    var userAllow: Set<String>
        get() = sp.getStringSet(KEY_ALLOW, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_ALLOW, HashSet(v)).apply()

    var userBlock: Set<String>
        get() = sp.getStringSet(KEY_BLOCK, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_BLOCK, HashSet(v)).apply()

    var bypassApps: Set<String>
        get() = sp.getStringSet(KEY_BYPASS, null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_BYPASS, HashSet(v)).apply()

    var upstreamMode: UpstreamMode
        get() = UpstreamMode.from(sp.getString(KEY_UPSTREAM, null))
        set(v) = sp.edit().putString(KEY_UPSTREAM, v.name).apply()

    var customDohUrl: String
        get() = sp.getString(KEY_CUSTOM_DOH, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_DOH, v.trim()).apply()

    var customDnsIp: String
        get() = sp.getString(KEY_CUSTOM_DNS, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_DNS, v.trim()).apply()

    var blockMode: BlockMode
        get() = runCatching { BlockMode.valueOf(sp.getString(KEY_BLOCK_MODE, null) ?: "") }.getOrDefault(BlockMode.NULL_IP)
        set(v) = sp.edit().putString(KEY_BLOCK_MODE, v.name).apply()

    var startAtBoot: Boolean
        get() = sp.getBoolean(KEY_BOOT, true)
        set(v) = sp.edit().putBoolean(KEY_BOOT, v).apply()

    var logEnabled: Boolean
        get() = sp.getBoolean(KEY_LOG, true)
        set(v) = sp.edit().putBoolean(KEY_LOG, v).apply()

    var safetyList: Boolean
        get() = sp.getBoolean(KEY_SAFETY, true)
        set(v) = sp.edit().putBoolean(KEY_SAFETY, v).apply()

    var achievementNotifications: Boolean
        get() = sp.getBoolean(KEY_ACHIEVEMENT_NOTIFY, true)
        set(v) = sp.edit().putBoolean(KEY_ACHIEVEMENT_NOTIFY, v).apply()

    var autoUpdateLists: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_UPDATE, v).apply()

    var lastListUpdate: Long
        get() = sp.getLong(KEY_LAST_UPDATE, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_UPDATE, v).apply()

    /** Set to true when the user explicitly stopped protection; boot/tile start honour it. */
    var wantsProtection: Boolean
        get() = sp.getBoolean(KEY_WANTS, false)
        set(v) = sp.edit().putBoolean(KEY_WANTS, v).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /** Route well-known public resolver IPs into the tunnel so apps that skip system DNS are filtered too. */
    var catchHardcodedResolvers: Boolean
        get() = sp.getBoolean(KEY_CATCH_RESOLVERS, true)
        set(v) = sp.edit().putBoolean(KEY_CATCH_RESOLVERS, v).apply()

    var autoUpdateApp: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE_APP, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_UPDATE_APP, v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    /** Android offers no API to read the Always-on VPN choice; we remember that the user visited that screen. */
    var alwaysOnAcknowledged: Boolean
        get() = sp.getBoolean(KEY_ALWAYS_ON_ACK, false)
        set(v) = sp.edit().putBoolean(KEY_ALWAYS_ON_ACK, v).apply()

    /** Applies a preset: sets the level and the matching enabled sources. */
    fun applyLevel(level: ProtectionLevel) {
        this.level = level
        if (level != ProtectionLevel.CUSTOM) enabledSources = defaultSourcesFor(level)
    }

    fun addAllow(domain: String) {
        userAllow = userAllow + domain
        userBlock = userBlock - domain
    }

    fun addBlock(domain: String) {
        userBlock = userBlock + domain
        userAllow = userAllow - domain
    }

    companion object {
        private const val KEY_LEVEL = "level"
        private const val KEY_SOURCES = "sources"
        private const val KEY_CUSTOM_SOURCES = "custom_sources"
        private const val KEY_LIST_META = "list_meta"
        private const val KEY_ALLOW = "user_allow"
        private const val KEY_BLOCK = "user_block"
        private const val KEY_BYPASS = "bypass_apps"
        private const val KEY_UPSTREAM = "upstream"
        private const val KEY_CUSTOM_DOH = "custom_doh"
        private const val KEY_CUSTOM_DNS = "custom_dns"
        private const val KEY_BLOCK_MODE = "block_mode"
        private const val KEY_BOOT = "start_at_boot"
        private const val KEY_LOG = "log_enabled"
        private const val KEY_SAFETY = "safety_list"
        private const val KEY_ACHIEVEMENT_NOTIFY = "achievement_notify"
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_WANTS = "wants_protection"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_CATCH_RESOLVERS = "catch_resolvers"
        private const val KEY_AUTO_UPDATE_APP = "auto_update_app"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_ALWAYS_ON_ACK = "always_on_ack"

        @Volatile private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context.applicationContext).also { instance = it } }

        fun defaultSourcesFor(level: ProtectionLevel): Set<String> =
            ListSource.BUILTIN.filter { level in it.levels }.map { it.id }.toSet()
    }
}
