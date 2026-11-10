# Test report

Environment: Linux sandbox, OpenJDK 21, Gradle 8.14.3 distribution (offline), **no Android SDK, no access to Google Maven / Maven Central / services.gradle.org**.

## What was run
| Check | Result |
|---|---|
| Kotlin compile of `app/src/main/.../core/*.kt` + `app/src/test/.../core/*.kt` (Kotlin 2.0.21, JVM) | **Passed**, no errors |
| JUnit 4.13.2 on those tests (`tools/run-core-tests.sh /opt/gradle-8.14.3`) | **143 tests, 0 failures** |
| Gradle wrapper generation | Succeeded (`gradle wrapper --gradle-version 8.9`); the 8.9 distribution itself was not downloaded |

Two real bugs were found and fixed by these tests during the work: `java.net.URI` rejecting `[ ] | { }` in URLs (the filter would have failed open), and a wrong assumption that `/ads/` is a plain pattern (in ABP syntax it is a regex rule; it is now skipped and counted).

### Covered by the 143 tests
URL/search resolution and engine switching; unsafe schemes (`javascript:`, `data:`, `file:`, `content:`, `intent:`, `blob:`, `ftp:`), userinfo spoofing, control chars, oversize input; filename sanitization incl. 2,000-case fuzz (no separators/`..`/leading dot), uniquification; filter matching (anchors, wildcards, separators, exceptions, `$third-party`, `$domain`, type options, `$important`, hosts format), unsupported (`$redirect`, cosmetic, regex) and malformed rules, rule-line accounting, 60k-rule performance bound; filter-list validation (https, size, HTML, block-everything); VPN configuration validation (IPv4/IPv6, CIDR, DNS, endpoint, key length/zero/base64, placeholder API); JS-bridge input validation; session codec (private never persisted, injection-safe, tamper-safe); history policy; Tor fail-closed routing policy; download state transitions and retry limit.

## What was NOT run (nothing below is verified)
- `./gradlew assembleDebug`, `test`, `lint` — **not run** (no SDK/network). **No debug APK was produced.**
- Any Android-dependent code: `Tabs.kt` (WebView, Profile API), `MainActivity`, `DownloadRepo`, `AdBlockEngine` wrapper, `VpnEngine` (WireGuard `GoBackend`, `Statistics.peer(...)` API names), `TorEngine` (`TorService` constants), Compose UI, `Prefs`. They were edited carefully but **may contain compile errors**.
- Instrumentation/Compose tests: **none written**.
- Emulator/device runs: **none**. No browsing, private-tab isolation, VPN handshake/leak, Tor routing/DNS/WebRTC leak, ad-block-on-real-pages, download or notification-permission test was performed.
- Library API assumptions to verify first on sync: `ProfileStore.allProfileNames/deleteProfile`, `WebViewCompat.setProfile`, `Profile.cookieManager` (androidx.webkit 1.12.1); `Statistics.peers()/peer(key).latestHandshakeEpochMillis()` (wireguard tunnel); existence of `info.guardianproject:tor-android:0.4.8.12` and `jtorctl:0.4.5.7` on Maven Central.
