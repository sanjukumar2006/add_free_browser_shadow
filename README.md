# Shadow Browser (Android)

Kotlin + Jetpack Compose + Android System WebView (Chromium). Single Gradle module.

> **Build status: this project has NOT been compiled or run since the security update.** The pure-Kotlin core is unit-tested (143 tests); everything that touches Android APIs is unverified. Read TEST_REPORT.md and AUDIT.md before relying on any feature.

## Setup
1. Android Studio Koala or newer, JDK 17, Android SDK 34 installed.
2. Open this folder. Sync (the Gradle 8.9 wrapper is included and downloads on first use). Expect to fix compile errors in Android-dependent files first; see TEST_REPORT.md for the API names to check.
3. `./gradlew testDebugUnitTest lintDebug assembleDebug`.
4. Core tests without the Android SDK: `tools/run-core-tests.sh <gradle-dist-dir>`.
5. VPN is disabled until you set `VPN_API_BASE` (https) in `app/build.gradle.kts` to your own backend (`backend/README.md`).

Supported: minSdk 26 (Android 8). Private tabs additionally need an Android System WebView with profile support (WebView 128+); otherwise private tabs are refused.

## Layout (`app/src/main/java/com/nova/browser`)
| Area | Path |
|---|---|
| Pure, unit-tested logic (URL policy, filter engine, sanitizer, validators, routing policy, codecs) | `core/` |
| Tabs, WebView factory, JS bridge | `engine/Tabs.kt` |
| Ad/tracker blocking (Android wrapper) + `assets/filters_*.txt` | `adblock/` |
| VPN (WireGuard) | `vpn/VpnEngine.kt` |
| Tor | `tor/TorEngine.kt` |
| Downloads, video detection | `download/`, `video/` |
| Settings, bookmarks, history, encrypted store | `data/` |
| UI | `ui/`, `MainActivity.kt` |

## Feature status
Legend: **Verified** = logic unit-tested on JVM. **Written** = source exists, never compiled or run. **Infra** = needs servers you operate.

| Feature | Status |
|---|---|
| URL/search resolution, unsafe-scheme blocking | Verified (policy) + Written (wiring) |
| Multi-tab browser, lazy WebViews, metadata-only session restore | Written |
| Private tabs (separate WebView profile) | Written; **not** "fully isolated" — see below |
| Ad/tracker blocking: ABP-subset engine, exceptions, per-site whitelist, validated custom lists, real counters | Verified (engine) + Written (WebView wiring) |
| Downloads via system DownloadManager, sanitized names, categories/search/sort | Written. No pause/resume/rename/retry, no MediaStore/SAF, no in-app repository |
| Direct-file video detection (mp4/webm/mkv/mp3/m4a/ogg) | Written. No HLS/DASH/blob/DRM; no transcoding; restricted platforms intentionally excluded |
| Tor routing, fail-closed | Policy Verified; service/proxy Written; no bootstrap %; **not leak-tested** |
| VPN (WireGuard) | Written; handshake-gated; **Infra**: needs your backend; never device-tested |
| Secure DNS (DoH/DoT) | **Not implemented** (use Android Private DNS) |
| Kill switch | OS-only (Always-on VPN + Block without VPN); the app cannot enforce one |
| Tab previews, tab groups, wallpapers, toolbar customisation, accessibility pass, tablet layout | **Not implemented** |
| Instrumentation / Compose UI tests | **Not written** |

## Private tabs vs Tor (different things)
- **Private tab**: separate cookie jar, storage and cache (WebView profile `nova_private`), no history/session persistence, FLAG_SECURE. Deleted when the last private tab closes and on next start. **Limits:** all private tabs share one profile; the browser process, DNS cache and any proxy/VPN are device-wide; the system DownloadManager is shared (private downloads appear in system Downloads and never receive cookies); it gives **no network anonymity**. It is not labelled "fully isolated" and should not be.
- **Tor mode**: all WebView page traffic goes through Tor's SOCKS proxy (applies to normal and private tabs). Nothing loads unless Tor is confirmed ready; no direct fallback. Downloads are disabled in Tor mode. Not Tor Browser: no fingerprinting protection, WebRTC not covered, not leak-tested.

## Ad blocker: what it can and cannot do
`shouldInterceptRequest` sees main-frame and subresource requests made by page content, with an *inferred* resource type. It cannot see bodies, does not cover service-worker traffic, and cannot do cosmetic filtering, `$redirect`, `$removeparam` or scriptlets. Such rules are parsed, counted ("skipped") and **never applied**. This is not a browser-extension-compatible blocker. Bundled lists are 62 hand-picked hosts, not EasyList; add EasyList/EasyPrivacy yourself via a custom https list (check each list's licence). Third-party detection uses a short multi-part-TLD list rather than the full Public Suffix List.

## Permissions
INTERNET, ACCESS_NETWORK_STATE; POST_NOTIFICATIONS (asked on first Downloads visit); CAMERA / RECORD_AUDIO only when a site requests them (https only, per request); WRITE_EXTERNAL_STORAGE ≤ API 28. VPN permission via the system `VpnService.prepare` dialog. No location permission (geolocation denied).

## Privacy policy requirements
Publish one before release. Cover: browsing history/bookmarks stored locally; no analytics/ads SDKs; optional VPN account token (stored encrypted on device) and device WireGuard public key sent to *your* backend; what your backend logs; filter-list URLs the user adds; Tor/VPN limitations.

## External infrastructure still required
VPN control plane + WireGuard servers + account/subscription system (`backend/README.md`); your release signing key; privacy policy and store listing; (optional) an EasyList mirror you are licensed to use.

## Known issues
See AUDIT.md (Open items M1–M10).

## Test results
See TEST_REPORT.md. Summary: 143 JVM unit tests pass; Gradle build, lint, instrumentation and device tests were not run.
