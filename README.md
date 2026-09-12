# MyBlocker

System-wide ad and tracker blocker for Android, built for a Samsung Galaxy S23 (One UI, Android 14+). No root, no remote servers, no third-party libraries.

MyBlocker runs a **local VPN that captures only DNS**. Every app's lookups pass through it; names on the blocklists get an instant empty answer, so the ad or tracker never loads. Everything else is forwarded, encrypted (DNS-over-HTTPS to Quad9 by default), to the resolver you choose. Real traffic never enters the tunnel, so there is no speed or battery cost beyond the DNS work itself.

## Get the APK

Every push builds a signed APK on GitHub Actions and publishes it as a **GitHub Release**:

1. Open the repository's **Releases** page. The newest build is at the top, named like `MyBlocker v1.0.0-b42`.
2. Download `MyBlocker-v1.0.0-b42-<sha>-release.apk` on the phone and open it. Allow installs from your browser if asked.
3. Later builds install over earlier ones (same signing key), keeping your settings and stats.

The version scheme is `1.0.0-b<build number>`; the build number is also the Android `versionCode`, so newer always installs over older. The same file is attached to the workflow run as an artifact.

## First run on the S23

1. Tap the big switch and accept the VPN request. (The VPN is local: nothing goes to a remote server.)
2. **Settings → Keep it running on Samsung**, do all three steps:
   - Battery → *Unrestricted*, and add MyBlocker to *Never sleeping apps* (Settings → Battery → Background usage limits).
   - Always-on VPN: Settings → Connections → More connection settings → VPN → gear next to MyBlocker → **Always-on VPN on**. Leave *Block connections without VPN* **off**.
   - Private DNS: Settings → Connections → More connection settings → Private DNS → **Off** (or Automatic). In "provider hostname" mode Android sends DNS around the filter.
3. Chrome → Settings → Privacy and security → Use secure DNS → **Off**, otherwise Chrome uses its own DoH and skips the filter.
4. Optional: add the MyBlocker tile to Quick Settings.

## Features

- **Protection levels**: Light, Balanced, Aggressive (default) or Custom, each a preset of lists. Any list can be toggled individually.
- **Lists**: curated mobile ad-SDK list (AdMob, Unity Ads, AppLovin, ironSource, Vungle, Chartboost, InMobi, Meta Audience Network, Mintegral, Pangle, …), HaGeZi Light / Pro / Pro++, Samsung telemetry, TikTok tracking, StevenBlack, AdAway, plus any URL you add (hosts, plain domain or `||domain^` format). HaGeZi Pro, Light, Samsung and TikTok ship inside the APK so protection works offline immediately; all lists refresh every 12 hours.
- **Does not break apps**: blocked names fail instantly (null IP, Pi-hole style) instead of timing out; a built-in safety allowlist keeps Play, push notifications, connectivity checks and Samsung account reachable; the **Query log** shows every lookup with the app that made it and lets you allow a domain with one tap; **Bypass apps** exempts stubborn apps entirely.
- **Encrypted upstream**: Quad9, Cloudflare, Google or AdGuard over DNS-over-HTTPS, a custom DoH URL, or plain DNS. Automatic fallback to plain DNS if DoH is unreachable.
- **Stats & gamification**: today / total / streak, 24-hour and 7-day charts, most blocked domains and apps, estimated data saved, XP levels (Rookie → Legend) and badges with notifications.
- **Always on**: foreground service with auto-restart, starts at boot, Quick Settings tile, Always-on VPN support.
- IPv4 and IPv6, TCP probes to the fake resolver are refused immediately so Private DNS "Automatic" falls back without delay.

## What it cannot do (honest limits of DNS filtering)

- **In-stream video ads** on YouTube, Instagram, TikTok, Twitch, Spotify: served from the same domains as the content. A DNS filter cannot tell them apart. Use the platform's ad-free plan, or for the web versions a browser with a content blocker (Firefox + uBlock Origin).
- **In-feed sponsored posts** inside Facebook, Instagram, X, Reddit: delivered inside the normal API responses.
- **Rewarded ads** ("watch an ad to get X"): the ad request fails, the app shows "no ad available". MyBlocker does not, and will not, tell an app that an ad was watched.
- Apps that hard-code IP addresses or use their own encrypted DNS.

## Project layout

```
app/src/main/java/com/neurone/myblocker/
  vpn/       BlockerVpnService (tunnel setup, lifecycle)  ·  DnsProxy (packet loop, worker pool)
  net/       IpPackets: IPv4/IPv6 + UDP/TCP parsing, reply and RST building, checksums
  dns/       DnsMessage: question parsing, NULL-IP / NXDOMAIN / SERVFAIL / truncated answers
  filter/    FilterEngine, DomainSet (suffix match), ListParser, ListSource, ListRepository
  upstream/  UdpUpstream, DohUpstream (RFC 8484 POST), UpstreamFactory
  stats/     StatsStore (JSON persisted), QueryLog, Levels, Achievements, AppNames
  system/    Notifications, BootReceiver, BlockerTileService, ListUpdateJobService
  ui/        Main, Stats, Log, Lists, Rules, Apps, Settings, Help; BarChartView; Ui helpers
app/src/main/assets/lists/   bundled blocklists (gzip) + mobile_ads.txt (curated)
app/src/test/                unit tests for the DNS codec, IP codec, list parser, levels
.github/workflows/build.yml  test + build + release on every push
```

Framework-only Kotlin: no AndroidX, no OkHttp, no coroutines. This keeps the APK tiny and the build simple.

## Building locally

Open the project in Android Studio (Ladybug or newer) and run, or:

```
./gradlew testReleaseUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## Signing

`keystore/myblocker-dev.jks` (password `myblocker`, alias `myblocker`) is a **development key committed on purpose** so every CI build is installable over the previous one without any setup. Anyone with the repo can sign an APK with it, so treat it like a debug key: fine for your own phone, not for distributing to others.

To use a private key instead, add these repository secrets and CI will pick them up automatically: `KEYSTORE_BASE64` (base64 of your .jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Switching keys requires uninstalling the dev-signed build once.

## Privacy

The query log lives in memory and is gone when the service stops. Statistics are stored in the app's private storage. The app talks only to the DNS resolver you selected and the blocklist download URLs. No analytics, no accounts.

## Credits

Blocklists by [HaGeZi](https://github.com/hagezi/dns-blocklists), [StevenBlack](https://github.com/StevenBlack/hosts) and [AdAway](https://github.com/AdAway/adaway.github.io). The DNS-only VPN approach follows DNS66, personalDNSfilter and RethinkDNS.
