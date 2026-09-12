package com.neurone.myblocker.ui

import android.app.Activity
import android.os.Bundle

class HelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Setup guide & limitations"
        val col = Ui.page(this)
        fun section(title: String, vararg lines: String) {
            val card = Ui.card(this)
            card.addView(Ui.heading(this, title))
            for (l in lines) card.addView(Ui.body(this, l, 14f))
            col.addView(card)
        }
        section(
            "How it works",
            "MyBlocker creates a local VPN whose only job is to receive DNS lookups from every app. Names on the blocklists get an instant empty answer, so the ad or tracker never loads. Everything else is forwarded, encrypted, to the resolver you chose.",
            "Only DNS enters the tunnel. Your actual browsing, streaming and gaming traffic goes straight to the network, so there is no speed or battery cost beyond the tiny DNS work.",
            "Nothing is sent to any server run by the author of this app.",
        )
        section(
            "Samsung Galaxy S23 setup (One UI)",
            "1. Settings → Apps → MyBlocker → Battery → Unrestricted.",
            "2. Settings → Battery → Background usage limits → make sure MyBlocker is NOT in \"Sleeping apps\" or \"Deep sleeping apps\". Add it to \"Never sleeping apps\".",
            "3. Settings → Connections → More connection settings → VPN → gear next to MyBlocker → Always-on VPN ON. Keep \"Block connections without VPN\" OFF.",
            "4. Settings → Connections → More connection settings → Private DNS → Off (or Automatic). Private DNS in \"Provider hostname\" mode bypasses the filter.",
            "5. Chrome: Settings → Privacy and security → Use secure DNS → Off (or \"use your current service provider\"). Chrome's own DoH would skip the filter.",
            "6. Optional: add the MyBlocker tile to Quick Settings for one-tap on/off.",
        )
        section(
            "If an app breaks",
            "Open Query log, filter by the app name, and tap a recently blocked domain → Allow. Your allowlist beats every blocklist.",
            "For stubborn apps (banking, some games) add them to Bypass apps; they then skip the filter entirely.",
            "Lower the Protection level to Balanced or Light if you prefer fewer rules over maximum blocking.",
        )
        section(
            "What DNS filtering cannot do",
            "In-stream video ads on YouTube, Instagram, TikTok, Twitch and Spotify are served from the same domains as the content, so a DNS filter cannot separate them. Use the platform's ad-free plan or a browser with a content blocker (e.g. Firefox with uBlock Origin) for the web versions.",
            "In-feed sponsored posts inside Facebook, Instagram, X and Reddit are delivered inside the app's normal API responses and cannot be removed at DNS level.",
            "Rewarded ads (\"watch an ad to get X\") will report \"no ad available\" when blocked. The app does not get told the ad was watched; MyBlocker only prevents the ad from loading.",
            "Apps that hard-code IP addresses or use their own encrypted DNS bypass any DNS-based blocker.",
        )
        section(
            "Privacy",
            "The Query log stays in memory on the device and is cleared when the service stops. Statistics are stored in the app's private storage. No analytics, no accounts, no internet access except to your chosen DNS resolver and the blocklist download URLs.",
        )
    }
}
