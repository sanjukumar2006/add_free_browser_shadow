# Release checklist

Blockers (nothing here has been done):
- [ ] Open in Android Studio (JDK 17), sync, fix any compile errors in the Android-dependent files listed in TEST_REPORT.md.
- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug` all green.
- [ ] Verify dependency versions exist and licences: wireguard tunnel (Apache-2.0), tor-android + jtorctl (check bundled licence files), androidx.* , Compose.
- [ ] Device matrix: Android 8 (minSdk 26), 10, 13, 14/15. WebView < 128 → private tabs must show the "unsupported" notice.
- [ ] Private isolation test: log in on a normal tab, open a private tab on the same site (must be logged out), log in there, close it, normal session must still be logged in; cold start must leave no private profile.
- [ ] Tor: with Tor starting/erroring/stopped, confirm no page loads and no downloads; confirm exit IP at check.torproject.org; test DNS and WebRTC leaks.
- [ ] VPN (only with a real backend): handshake, IPv4/IPv6/DNS leak tests, reconnect, revoke from system, Always-on + block-without-VPN.
- [ ] Ad-block on real pages incl. breakage review of the bundled list; confirm counts equal blocked responses.
- [ ] Downloads: permission flow on API 28 and 33+, file-name edge cases, private-download behaviour.
- [ ] Privacy policy published (WebView browsing data, optional VPN account token, no analytics); Play Data Safety form; check current Play target-API requirement (project targets 34).
- [ ] Create your own signing key (below). Never commit it.

## Create your own release key
```
keytool -genkeypair -v -keystore ~/nova-release.jks -alias nova -keyalg RSA -keysize 4096 -validity 10000
```
Put paths/passwords in `~/.gradle/gradle.properties` (`NOVA_STORE_FILE`, `NOVA_STORE_PASSWORD`, `NOVA_KEY_ALIAS`, `NOVA_KEY_PASSWORD`), add a `signingConfigs.release` block that reads them via `providers.gradleProperty(...)`, then `./gradlew bundleRelease`. Back the keystore up offline.
