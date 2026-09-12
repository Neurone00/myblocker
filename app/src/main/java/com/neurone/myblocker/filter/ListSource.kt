package com.neurone.myblocker.filter

import org.json.JSONObject

/** Protection presets. Each one maps to a set of enabled sources. */
enum class ProtectionLevel(val label: String) {
    OFF("Off"),
    LIGHT("Light"),
    BALANCED("Balanced"),
    AGGRESSIVE("Aggressive"),
    CUSTOM("Custom");

    companion object {
        fun from(name: String?): ProtectionLevel = entries.firstOrNull { it.name == name } ?: AGGRESSIVE
    }
}

/**
 * A blocklist. Built-in sources may ship an asset in the APK so protection works
 * offline on first launch; every source with a URL can be refreshed.
 */
data class ListSource(
    val id: String,
    val name: String,
    val description: String,
    val url: String?,
    val asset: String?,
    val builtin: Boolean,
    val levels: Set<ProtectionLevel>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("description", description).put("url", url)

    companion object {
        const val ASSET_DIR = "lists"

        val BUILTIN: List<ListSource> = listOf(
            ListSource(
                id = "mobile_ads",
                name = "Mobile ad networks (curated)",
                description = "Endpoints used by in-app ad SDKs: AdMob, Unity Ads, AppLovin, ironSource, Vungle, Chartboost, InMobi, Meta Audience Network, Mintegral, Pangle and more. Bundled, no download.",
                url = null,
                asset = "mobile_ads.txt",
                builtin = true,
                levels = setOf(ProtectionLevel.LIGHT, ProtectionLevel.BALANCED, ProtectionLevel.AGGRESSIVE),
            ),
            ListSource(
                id = "hagezi_light",
                name = "HaGeZi Light",
                description = "Small, very safe list of ads and trackers. Basically no breakage.",
                url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt",
                asset = "hagezi_light.txt.gz",
                builtin = true,
                levels = setOf(ProtectionLevel.LIGHT),
            ),
            ListSource(
                id = "hagezi_pro",
                name = "HaGeZi Pro",
                description = "Ads, trackers, telemetry, phishing, malware, scams. Recommended for solid blocking with rare breakage.",
                url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
                asset = "hagezi_pro.txt.gz",
                builtin = true,
                levels = setOf(ProtectionLevel.BALANCED, ProtectionLevel.AGGRESSIVE),
            ),
            ListSource(
                id = "hagezi_samsung",
                name = "Samsung telemetry",
                description = "Samsung ads and telemetry endpoints (One UI, Galaxy Store, Samsung apps). Does not touch Samsung account or updates.",
                url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.samsung-onlydomains.txt",
                asset = "hagezi_samsung.txt.gz",
                builtin = true,
                levels = setOf(ProtectionLevel.AGGRESSIVE),
            ),
            ListSource(
                id = "hagezi_tiktok",
                name = "TikTok tracking",
                description = "TikTok tracking and fingerprinting endpoints; the app keeps working.",
                url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/native.tiktok-onlydomains.txt",
                asset = "hagezi_tiktok.txt.gz",
                builtin = true,
                levels = setOf(ProtectionLevel.AGGRESSIVE),
            ),
            ListSource(
                id = "stevenblack",
                name = "StevenBlack Unified",
                description = "Classic unified hosts file (adware + malware). Downloaded on first update.",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                asset = null,
                builtin = true,
                levels = setOf(ProtectionLevel.AGGRESSIVE),
            ),
            ListSource(
                id = "hagezi_proplus",
                name = "HaGeZi Pro++",
                description = "Even more aggressive than Pro. May break some sites or app features. Off by default.",
                url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.plus-onlydomains.txt",
                asset = null,
                builtin = true,
                levels = emptySet(),
            ),
            ListSource(
                id = "adaway",
                name = "AdAway",
                description = "AdAway's mobile-focused hosts list. Off by default.",
                url = "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
                asset = null,
                builtin = true,
                levels = emptySet(),
            ),
        )

        fun fromJson(o: JSONObject): ListSource = ListSource(
            id = o.getString("id"),
            name = o.optString("name", o.getString("id")),
            description = o.optString("description", ""),
            url = o.optString("url", "").ifEmpty { null },
            asset = null,
            builtin = false,
            levels = emptySet(),
        )
    }
}
