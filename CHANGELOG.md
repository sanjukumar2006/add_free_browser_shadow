# Changelog

## AdBlock V3 (uncompiled; no Android SDK in the author's environment)

### Changed
- `AdBlockEngine` rewritten: rules load on a background thread (previously on the main thread in `Application.onCreate`), and the engine is swapped in atomically. Public methods used by the UI and `Tabs.kt` are unchanged.
- YouTube handler replaced by `core/YouTubeScript` (version 3.0.0): MutationObserver with debounce, SPA navigation events, mutes only during a confirmed ad, clicks skip/close, restores the mute state. No polling. No seeking (the old `currentTime = duration` was removed).
- YouTube network rules moved from `filters_ads.txt` to `filters_youtube.txt`; loaded only when YouTube protection is on. `filters_ads.txt` now has 61 rules.
- Shields UI (Privacy tab): YouTube protection, tracker protection, and hide-ad-boxes switches; filter-list status with last update and last error; "Check filter lists now".
- Tracker toggle: tracker rules are ignored when off, ad rules still apply.

### Added
- `core/SubscriptionPolicy`: EasyList and EasyPrivacy definitions (HTTPS only), weekly update rule, clock-backwards handling.
- Subscriptions in `AdBlockEngine`: download and validate, atomic install to `subs_<id>.txt`, cache fallback offline, 12-hour retry after failure, forced check from the UI. Refresh runs on a background thread at startup and is skipped unless a list is due.
- Prefs: `yt_protect`, `trk_protect`, `cosmetic`, and per-list `sub_on_*`, `sub_ts_*`, `sub_try_*`, `sub_err_*` keys.
- Tests: `YouTubeScriptTest` (10), `SubscriptionPolicyTest` (9). `CosmeticRulesTest` now 15 (old YouTube test moved).
- `AUDIT_V3.md`, `INTEGRATION_TESTS.md`.

### Removed
- `CosmeticRules.YOUTUBE_AD_SKIP_JS` (replaced by `YouTubeScript`).

### Not done
- Malware/phishing feed. Element-hiding exception rules (`#@#`). ServiceWorkerClient interception. Ads from the same host as content. Non-skippable ads are not sped up.

## Ad/pop-up blocking pass (uncompiled Android wiring; core logic has unit tests)

- `FilterEngine.decideNavigation` / `AdBlockEngine.decideNavigation`: top-level navigations, pop-up targets and redirect hops are checked against the same rules (previously the main frame was always allowed). Respects the on/off switch and the per-site allow-list.
- `TabManager`: blocked navigations and user-gesture pop-ups are stopped and reported with a notice.
- Bundled lists expanded to 94 rules: YouTube ad-tracking/ad-stats endpoints, more ad and popup networks, more trackers.
- Tests: 9 new navigation tests in `FilterEngineTest`.
- Element hiding: `core/CosmeticRules` parses `##` rules (bundled `assets/filters_cosmetic.txt` plus custom lists) and injects an idempotent stylesheet on page start and finish. Exceptions (`#@#`) are ignored.
- YouTube ad skipper (best effort): mutes/fast-forwards ad breaks, clicks skip buttons, closes overlays.
- Tests: `CosmeticRulesTest` (16 tests).
- Not done: exception rules for element hiding, malware/phishing feed, ads from the same host as content.

## 1.0.1-security-update (unreleased, NOT compiled by the author of this change)

### Added (pure Kotlin, `app/src/main/java/com/nova/browser/core`, 143 unit tests passing)
- `UrlPolicy` / `UrlParts`: address-bar resolution, scheme allow-list, https upgrade, search-template validation.
- `FilterEngine` + `FilterListValidator` + `ResourceTypeGuesser`: ABP/EasyList-subset blocking with exceptions, `$third-party`, `$domain`, resource types, `$important`, hosts files; unsupported rules counted, never guessed.
- `FilenameSanitizer`, `BridgeValidator`, `SessionCodec`, `VpnConfigValidator`, `RoutingPolicy` (fail-closed Tor), `DownloadStateMachine`.
- Gradle wrapper (8.9), JUnit, `tools/run-core-tests.sh`.
- Network security config, backup/data-extraction rules.

### Changed (Android code — UNCOMPILED)
- Private tabs now use a separate WebView Profile (androidx.webkit 1.12.1); refused when unsupported; global cookies are never cleared for private sessions.
- Downloads: sanitized names, no cookies/referer for private tabs, disabled in Tor mode.
- VPN: honest "not configured" state, validated backend response, handshake-verified CONNECTED, bounded reconnect, diagnostics.
- Tor: fail-closed routing; ON only after proxy override confirmed; no direct fallback; honest messaging.
- Ad-block: real counts only; bundled list converted to ABP format and split ads/trackers; atomic validated custom-list updates; removed fake "data saved".
- WebView hardening: no file/content/universal access, mixed content blocked, geolocation off, explicit SSL cancel, escaped error page, JS bridge validated and https-only, permissions granted per request only.
- Manifest: removed location/Wi-Fi-state/foreground-service/audio-settings permissions; notification permission requested contextually.
- Session restore stores validated metadata only.

### Removed
- Fabricated "data saved" estimate and "sites protected" figure; `blocklist_hosts.txt` (replaced by `filters_ads.txt`, `filters_trackers.txt`).

### Not done
See AUDIT.md "Medium / Low" and README "Feature status".

## Static review pass 2 (uncompiled)
Fixed JS-bridge off-thread WebView call, private profile creation, silent download failures + API 26–28 storage permission, Tor OFF-during-startup race, unbounded VPN response read, Prefs theme crash. See AUDIT.md S1–S6. Added BUILD.md.
