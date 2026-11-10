# NOVA Browser — Audit

Scope: all 27 files of the supplied ZIP (1,773 lines: 17 Kotlin files, Gradle, manifest, resources, 2 READMEs, 1 asset).
Toolchain declared: AGP 8.5.2, Kotlin 1.9.24, Compose compiler 1.5.14, Compose BOM 2024.06.00, JDK 17 target, minSdk 26, targetSdk/compileSdk 34,
androidx.webkit 1.11.0 (now 1.12.1), wireguard tunnel 1.0.20230706, tor-android 0.4.8.12, jtorctl 0.4.5.7. Single module `:app`.
The original README itself stated the code "was written without compiling or running it". It still has not been compiled by me (no Android SDK / Maven access in the
audit sandbox) — see TEST_REPORT.md.

**Status legend** — `FIXED-VERIFIED`: logic moved to pure Kotlin and unit-tested here. `FIXED-UNCOMPILED`: source changed but Android-dependent, never compiled or run.
`OPEN`: not fixed. `INFRA`: needs external infrastructure.

## Critical
| # | File | Issue | Fix | Status |
|---|---|---|---|---|
| C1 | engine/Tabs.kt `wipePrivateSession` | Private session "wipe" called `CookieManager.removeAllCookies` + `WebStorage.deleteAllData` — the **global** jar. Closing a private tab logged the user out of every normal site, and private cookies were readable by normal tabs while open. | Private tabs bind to an `androidx.webkit` Profile (`nova_private`) with its own cookies/cache/storage; the profile is deleted on last private tab close and on cold start. If `MULTI_PROFILE` is unsupported, private tabs are refused. Global jar never touched. | FIXED-UNCOMPILED (policy tests pass; profile API calls unverified) |
| C2 | download/DownloadRepo.kt | `enqueue` forwarded `CookieManager.getCookie(url)` as a `Cookie` header into the shared system DownloadManager, including for private tabs. | Cookies/Referer only for normal tabs; private never. Limitation documented: DownloadManager is shared, so private downloads still appear in system Downloads. | FIXED-UNCOMPILED |
| C3 | tor/TorEngine.kt, engine/Tabs.kt | Not fail-closed: pages loaded directly until the proxy was applied; `ERROR`/`OFF` after a Tor crash silently resumed direct traffic; status `ON` was inferred from the service broadcast, not the proxy override. | `NetworkMode`/`RoutingPolicy`: while Tor is requested every navigation and sub-request is refused unless proxy override is confirmed. `ON` set only in the override success callback. Downloads disabled in Tor mode. | policy FIXED-VERIFIED; wiring UNCOMPILED |
| C4 | vpn/VpnEngine.kt | `CONNECTED` was set immediately after `setState(UP)` with no handshake check; placeholder `https://vpn-api.example.com` treated as real; backend JSON passed unvalidated to `Config.Builder`; no reconnect bound. | Handshake required (15 s) before CONNECTED; placeholder/non-https API → explicit "not configured" state; `VpnConfigValidator` (IP/CIDR/port/key checks); bounded exponential-backoff reconnect; sign-out rotates the device key. | validator FIXED-VERIFIED; engine UNCOMPILED |

## High
| # | File | Issue | Fix | Status |
|---|---|---|---|---|
| H1 | engine/Tabs.kt, data/SearchEngines.kt | `file://` was navigable (typed or via link); `javascript:`/`data:` typed text had no rule; `http` upgrade loop possible. | `UrlPolicy`: only http(s) (+`about:blank`), no userinfo spoofing, control chars rejected; tel/mailto/sms to OS only on gesture; `intent:`/`file:`/`content:` dropped. | FIXED-VERIFIED (policy) |
| H2 | engine/Tabs.kt `onReceivedError` | Error page interpolated `e.description` into HTML unescaped. | `TextUtils.htmlEncode`; explicit `onReceivedSslError → cancel()` (never proceed). | FIXED-UNCOMPILED |
| H3 | engine/Tabs.kt `trimBackground` | Called `WebView.pauseTimers()` — **process-global**; paused the foreground tab too. | Removed. Background tabs' WebViews are released (metadata kept), reloaded on selection. | FIXED-UNCOMPILED |
| H4 | engine/Tabs.kt, ui/Browser.kt | WebView built with the Activity context (leak on recreation); `close()` destroyed a WebView still attached; no `onRelease`. | `MutableContextWrapper(appContext)`, base swapped while attached; detach in `onRelease`; proper teardown in `releaseWebView`. | FIXED-UNCOMPILED |
| H5 | MainActivity.kt | Web permission callback `r.grant(r.resources)` granted *all* requested resources once any Android permission result was true; origin not checked. | Grant only requested+authorized camera/mic; https origins only. | FIXED-UNCOMPILED |
| H6 | MainActivity.kt `onDestroy` | `tabs.none { false }` is always true — private wipe logic meaningless. | Close private tabs + wipe on finishing. FLAG_SECURE while a private tab is shown. | FIXED-UNCOMPILED |
| H7 | engine/Tabs.kt `VideoBridge` | JS bridge accepted arbitrary URL/kind/height from any origin (incl. http, file, about); unbounded list growth. | `BridgeValidator`: http(s) URL ≤2048, kind∈{video,audio}, height clamp, ≤40 candidates/page; active only on https pages. Limitation: `addJavascriptInterface` cannot restrict by frame origin; `WebMessageListener` with origin rules would be stronger (OPEN). | FIXED-VERIFIED (validator) |
| H8 | adblock/AdBlockEngine.kt | Plain domains parsed as substring patterns risk; heuristic `adPaths`/`trackerHints` (`pixel`, `stats.`) = false blocks; counters incremented even when the response was not blocked; dead `else if` branch; fabricated 45 KB "bytes saved"; `prefs.whitelist` re-parsed from SharedPreferences on every request; stats mutated off the main thread; custom list download unbounded, no HTTPS/redirect/content checks, non-atomic write. | New `FilterEngine` (ABP subset, exceptions, `$third-party`, `$domain`, types, `$important`, hosts format, token+host index) and `FilterListValidator`; counts only real blocked responses; whitelist snapshot; atomic validated install; fake savings removed. | FIXED-VERIFIED (engine) / UNCOMPILED (wrapper) |
| H9 | data/SearchEngines.kt, Prefs | Custom engine template only needed `%s` → `javascript:...%s` accepted; history/bookmark lines not validated. | https + one `%s` required; stored/loaded URLs filtered by `UrlPolicy`. | FIXED-VERIFIED |
| H10 | data/Prefs.kt | Session = newline-joined raw URLs (injection, no validation, private filter by caller only); `EncryptedSharedPreferences.create` crash on corrupted Keystore bricked startup. | `SessionCodec` (percent-encoded, validated, private never persisted); recovery path for corrupted secure store. | codec VERIFIED; Prefs UNCOMPILED |
| H11 | AndroidManifest.xml | Unused/unjustified permissions (location ×2, WIFI_STATE, FOREGROUND_SERVICE(+SPECIAL_USE) with no service, MODIFY_AUDIO); no network security config; no data-extraction rules; POST_NOTIFICATIONS requested at launch. | Pruned; NSC (no cleartext, system CAs only); backup/extraction rules exclude all; notification permission asked when Downloads is first opened. | FIXED-UNCOMPILED |
| H12 | build | No Gradle wrapper in ZIP; webkit 1.11 has no Profile API. | Wrapper 8.9 generated; webkit 1.12.1; JUnit added. | FIXED (wrapper unverified in IDE) |

## Medium / Low
| # | Area | Issue | Status |
|---|---|---|---|
| M1 | Downloads | Uses DownloadManager only: no pause/resume/rename/retry, no MediaStore/SAF, no in-app repository, no custom notifications. `DownloadStateMachine` (tested) exists but is not wired to a repository. File name sanitization IS wired. | OPEN |
| M2 | Video | Direct media files only. No HLS/DASH/blob/DRM, no MIME probing before download (`FilenameSanitizer.isAcceptableMedia` exists, unused). | OPEN (documented limit) |
| M3 | Tor | No bootstrap percentage (needs jtorctl control-port events); no leak test; WebRTC not routed; `ProxyController` is process-wide. tor-android version/license not verified offline. | OPEN |
| M4 | DNS | `secureDns` pref exists but nothing implements DoH/DoT. UI text now says so. | OPEN |
| M5 | VPN | No backend exists. Server list/subscription is `INFRA`. Kill switch is OS-level only. | INFRA |
| M6 | UI/UX (Phase 10) | Navigation-bar icons lack content descriptions; no tab previews (`Tab.preview` never set); no empty/error states beyond basics; no responsive/tablet layout; no per-site shield sheet. Only privacy/VPN/Tor/shield texts and states were corrected. | OPEN |
| M7 | Storage | Bookmarks/history in SharedPreferences strings (500 cap); Room would be better. `security-crypto 1.1.0-alpha06` is deprecated. | OPEN |
| M8 | Platform | targetSdk/compileSdk 34: check the current Google Play target-API requirement before publishing. | OPEN |
| M9 | Ads | Bundled list is 62 hand-picked hosts (not EasyList). Blocking `googletagmanager`/`connect.facebook.net` can break sites. No bundled EasyList (licence/size not vetted); users can add their own. | OPEN |
| M10 | Tests | No instrumentation or Compose tests were written (cannot run here). | OPEN |

## Static review pass 2 (no compiler available — all items UNCOMPILED)
| # | File | Issue | Fix |
|---|---|---|---|
| S1 | engine/Tabs.kt `VideoBridge` | Bridge callbacks run on the `JavaBridge` thread, but `pageUrl` called `WebView.getUrl()` (main-thread only) → likely crash on first detected video. | Bridge now reads `tab.url` (updated on the main thread). |
| S2 | engine/Tabs.kt | Private profile fetched with `getProfile()` (nullable, may not exist yet) → cookie settings silently skipped. | `getOrCreateProfile()` before `setProfile()` and for the cookie manager. |
| S3 | DownloadRepo / Tabs / Browser | `enqueue` returned -1 silently; on API 26–28 the missing runtime `WRITE_EXTERNAL_STORAGE` made every download fail with no message. | Reason stored in `lastRefusal` and shown to the user; permission requested when Downloads is opened on API ≤ 28. |
| S4 | tor/TorEngine.kt | An `OFF` broadcast during startup could flip STARTING → ERROR. | Treated as a crash only when the previous state was ON. |
| S5 | vpn/VpnEngine.kt | Backend response size checked after reading it fully. | Capped while streaming (256 KB). |
| S6 | data/Prefs.kt | A corrupted `theme` value crashed startup. | Falls back to SYSTEM. |

Still unverified (needs first Gradle sync): exact `TorService` constants (`EXTRA_SOCKS_PROXY_PORT` may be an Orbot constant, not tor-android's), existence of `tor-android:0.4.8.12` / `jtorctl:0.4.5.7` on Maven Central, `socks5://` acceptance by `ProxyConfig.addProxyRule`, and lint `RequiresFeature` warnings on Profile APIs (abortOnError is true).
