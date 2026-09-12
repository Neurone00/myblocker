# ☂ Adbrella

Keeps the ads off you. A system-wide ad and tracker blocker for Android, built for a Samsung Galaxy S23 (One UI, Android 14+). No root, no remote servers, no accounts.

Adbrella runs a **local VPN that captures only DNS**. Every app's lookups pass through it; names on the blocklists get an instant empty answer, so the ad or tracker never loads. Everything else is forwarded, encrypted (DNS-over-HTTPS to Quad9 by default), to the resolver you choose. Real traffic never enters the tunnel, so there is no speed or battery cost beyond the DNS work itself.

## Get the APK

Every push builds a signed APK on GitHub Actions and publishes it twice:

- a versioned **GitHub Release** (`Adbrella v1.0.0-b42`, file `Adbrella-v1.0.0-b42-<sha>-release.apk`), and
- the rolling **adbrella-latest** release with `adbrella.apk` and `update.json`, which the app's self-updater reads.

Install once from either. From then on **the app updates itself**: it checks `update.json` on launch (at most every 6 hours) and once a day in the background, downloads the APK, verifies its SHA-256 and installs it through PackageInstaller. Android asks you to confirm the first self-update; afterwards updates apply silently on Android 12+. Toggle it under Settings › Update automatically.

**Private repository?** GitHub returns 404 for anonymous downloads of a private repo's release assets, so self-update (and the Releases links above) only work if the repo is public **or** you give the app a token: create a fine-grained personal access token limited to this repository with *Contents: Read-only* and paste it under Settings › Private repository token. The app then uses GitHub's API for the check and the download; the token never leaves the phone.

The version scheme is `1.0.0-b<build number>`; the build number is also the Android `versionCode`, so newer always installs over older.

## First run on the S23

1. Flip the switch and accept the VPN request. (The VPN is local: nothing goes to a remote server.)
2. The home screen shows a card with the phone settings still to do; it disappears once they are done. They are also under **Settings → Keep the umbrella open**:
   - Battery → *Unrestricted*, and add MyBlocker to *Never sleeping apps* (Settings → Battery → Background usage limits).
   - Always-on VPN: Settings → Connections → More connection settings → VPN → gear next to MyBlocker → **Always-on VPN on**. Leave *Block connections without VPN* **off**.
   - Private DNS: Settings → Connections → More connection settings → Private DNS → **Off** (or Automatic). In "provider hostname" mode Android sends DNS around the filter.
3. Chrome → Settings → Privacy and security → Use secure DNS → **Off**, otherwise Chrome uses its own DoH and skips the filter.
4. Optional: add the MyBlocker tile to Quick Settings.

## Features

- **Consumer-facing UI** (Jetpack Compose, Material 3): follows your wallpaper colors on Android 12+ (One UI "Color palette"), four tabs (Umbrella, Activity, Stats, Settings). All technical controls sit under **Settings › Advanced**. A short umbrella splash on launch, then the brand stays out of the way.
- **Self-updating** from the rolling GitHub release (see above).
- **Catches hard-coded resolvers**: public resolver IPs (8.8.8.8, 1.1.1.1, 9.9.9.9, …) are routed into the tunnel too, so apps that skip the system DNS are still filtered; their DNS-over-TLS/HTTPS attempts get a TCP reset so they fall back to plain DNS.
- **Tidy web pages**: Adbrella registers as a **Samsung Internet content blocker** (the same extension API AdGuard Content Blocker and Adblock Plus use) and supplies EasyList, whose element-hiding rules remove the empty ad boxes DNS blocking leaves behind and let pages reflow. One-time step: Samsung Internet › Settings › Extensions › Content blockers › switch on Adbrella (Settings › Tidy web pages opens that page). Chrome has no extension support; Firefox + uBlock Origin is the alternative.
- **Deep clean (beta, Advanced)**: tidies pages in Chrome, Brave, Samsung Internet and similar browsers, the way AdGuard's paid app does. All traffic is routed through Adbrella's userspace TCP/UDP relay; browser connections go to a local proxy that terminates TLS with a per-phone certificate authority (a small X.509 encoder, no BouncyCastle; you install only the public certificate from Settings), verifies the real server against the system trust store, relays HTTP/1.1 and adds two stylesheet links to HTML so EasyList's element-hiding rules remove the empty ad boxes, plus a small in-page collapser that hides leftover placeholders (grey "ADV" boxes, wrappers left empty around a blocked slot, slots inserted later while scrolling) and gives a box back if real content turns up in it; it runs against a synthetic news page in CI (`tools/collapser-test`). CSP headers are extended, not removed; Alt-Svc is stripped and browser QUIC dropped so pages stay on HTTP/1.1. Only browsers are ever intercepted; every other app, including anything that pins certificates (Instagram, YouTube, banking), passes through untouched. Within browsers, a built-in list of certificate-pinned hosts (Google, Apple, Microsoft, Meta, payment and Samsung account domains) plus any you add is tunnelled raw by SNI so pinning still holds, and the interception path is covered by an end-to-end test that stands up a real TLS origin and checks the injected result.
- **Protection strength**: Light, Balanced, Strong (default) or Custom, each a preset of lists. Any list can be toggled individually.
- **Lists**: curated mobile ad-SDK list (AdMob, Unity Ads, AppLovin, ironSource, Vungle, Chartboost, InMobi, Meta Audience Network, Mintegral, Pangle, …), HaGeZi Light / Pro / Pro++, Samsung telemetry, TikTok tracking, StevenBlack, AdAway, plus any URL you add (hosts, plain domain or `||domain^` format). HaGeZi Pro, Light, Samsung and TikTok ship inside the APK so protection works offline immediately; all lists refresh every 12 hours.
- **Invisible to ad-blocker checks**: blocked names answer with a real-looking address (198.18.0.1 / 2001:db8::1) that Adbrella routes into its own tunnel and refuses instantly, so it fails as fast as a null answer but an app that tests whether ad domains resolve to 0.0.0.0 or localhost sees nothing unusual (the default; Null IP and NXDOMAIN remain available under Advanced). Apps that refuse to run with any VPN can be excluded; apps that only unlock once an ad really loaded cannot be helped.
- **Does not break apps**: blocked names fail instantly (null IP, Pi-hole style) instead of timing out; a built-in safety allowlist keeps Play, push notifications, connectivity checks and Samsung account reachable; the **Activity** tab shows every lookup with the app that made it and lets you allow a domain with one tap; **Apps that skip the umbrella** exempts stubborn apps entirely.
- Works with the usual in-app ad SDKs (AdMob, AppLovin/MAX, Unity Ads, ironSource, Vungle, Chartboost, InMobi, Meta Audience Network, Mintegral, Pangle, Fyber, Tapjoy …), which is what apps like MangaZone and Vampire Survivors use for their banners and interstitials. Their "watch an ad for a reward" offers will report no ad available.
- **Encrypted upstream**: Quad9, Cloudflare, Google or AdGuard over DNS-over-HTTPS, a custom DoH URL, or plain DNS. Automatic fallback to plain DNS if DoH is unreachable.
- **Stats & gamification**: today / total / streak, 24-hour and 7-day charts, most blocked domains and apps, estimated data saved, XP levels (Light drizzle → Desert) and badges with notifications.
- **Always on**: foreground service with auto-restart, starts at boot, Quick Settings tile, Always-on VPN support.
- **Standby with the phone**: with the screen off the app stops refreshing its notification and the UI stops its refresh timer entirely (a stopped activity keeps its composition, so an unconditional timer would go on waking the CPU every couple of seconds all night). The tunnel itself blocks on its sockets, so an idle phone costs nothing; everything resumes on the next screen-on.
- **Android Auto aware**: Android Auto refuses to start with any VPN active (error 21) and detects the VPN itself, so a bypass cannot help. Android Auto also checks *before* it ever reports being connected, so Adbrella watches for the car instead — it switches protection off the moment the phone joins the car over Bluetooth, enters car mode, is plugged in over USB in accessory mode (which wired Android Auto uses and a plain charger does not), or as soon as the Android Auto app starts talking on the network (its lookups pass through Adbrella, so this one cannot miss), and switches back on when the car is gone (cable out, car Bluetooth disconnected, Android Auto no longer projecting, or none of those left after a check). The home card, tile and notification say "Paused for Android Auto" meanwhile (Settings → Pause for Android Auto).
- IPv4 and IPv6, TCP probes to the fake resolver are refused immediately so Private DNS "Automatic" falls back without delay.

## What it cannot do (honest limits of DNS filtering)

- **In-stream video ads** on YouTube, Instagram, TikTok, Twitch, Spotify: served from the same domains as the content. A DNS filter cannot tell them apart. Use the platform's ad-free plan, or for the web versions a browser with a content blocker (Firefox + uBlock Origin).
- **In-feed sponsored posts** inside Facebook, Instagram, X, Reddit: delivered inside the normal API responses.
- **Rewarded ads** ("watch an ad to get X"): the ad request fails, the app shows "no ad available". Adbrella does not, and will not, tell an app that an ad was watched.
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
  update/    Updater (manifest check, download, SHA-256 verify, PackageInstaller) + InstallReceiver
  web/       WebFilters (EasyList refresh, Samsung Internet notify) + WebFilterProvider (content-blocker provider)
  ui/        Compose: Theme, Splash, AppRoot, Home, Activity, Stats, Settings, Advanced, Lists, Rules, Upstream, Apps
app/src/main/assets/lists/   bundled blocklists (gzip) + mobile_ads.txt (curated)
app/src/test/                unit tests for the DNS codec, IP codec, list parser, levels
design/                      design canvas sources for the screens (see the Claude artifact link in the PR/commit)
.github/workflows/build.yml  test + build + versioned release + rolling latest release on every push
```

The engine is framework-only Kotlin (no OkHttp, no Room); the UI uses Jetpack Compose with Material 3.

## Building locally

Open the project in Android Studio (Ladybug or newer) and run, or:

```
./gradlew testReleaseUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## Signing

`keystore/myblocker-dev.jks` (password `myblocker`, alias `myblocker`) is a **development key committed on purpose** so every CI build is installable over the previous one without any setup, which is also what makes self-update possible. Anyone with the repo can sign an APK with it, so treat it like a debug key: fine for your own phone, not for distributing to others.

To use a private key instead, add these repository secrets and CI will pick them up automatically: `KEYSTORE_BASE64` (base64 of your .jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Switching keys requires uninstalling the dev-signed build once.

## Privacy

The activity log lives in memory and is gone when the service stops. Statistics are stored in the app's private storage. The app talks only to the DNS resolver you selected, the blocklist download URLs and GitHub for the update check. No analytics, no accounts.

## Credits

Web rules by [EasyList](https://easylist.to/). Blocklists by [HaGeZi](https://github.com/hagezi/dns-blocklists), [StevenBlack](https://github.com/StevenBlack/hosts) and [AdAway](https://github.com/AdAway/adaway.github.io). The DNS-only VPN approach follows DNS66, personalDNSfilter and RethinkDNS. The Adbrella name, icon, palette, self-updater and resolver capture come from the sibling Adbrella prototype in Neurone00/Carshare.
