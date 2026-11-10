# Changelog

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
