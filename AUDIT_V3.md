# Shadow / Nova AdBlock V3 — Audit (before V3 changes)

Source: the ZIP supplied in this conversation (original kept read-only as the upload; this work is in `nova/`).
Nothing was compiled: the sandbox has no Android SDK, Kotlin compiler or network access.

## What works (kept as-is)
- Pure-Kotlin filter engine (`core/FilterEngine.kt`): ABP subset, `||`, `|`, `*`, `^`, `@@` exceptions, `$third-party`, `$domain`, type options, `$important`, hosts format. Covered by unit tests (see TEST_REPORT.md).
- Validator for custom lists (`core/FilterListValidator.kt`): https only, size and line caps, refuses HTML/JSON and "block everything" lists.
- Main-frame and sub-resource interception in `engine/Tabs.kt` (`shouldInterceptRequest`, `shouldOverrideUrlLoading`). Fail-closed for Tor.
- Real blocked-request counters (incremented only after a blocked response is returned).
- Per-site allow-list, master switch, custom list install with atomic write.

## Partially implemented (fixed in V3)
- **Cosmetic filtering** existed only as a parser (`CosmeticRules`) plus an injection script. It was used, but there was no bundled list for YouTube and no toggle.
- **YouTube handling** was a fixed `setInterval` (every 400 ms) that wrote `video.currentTime = video.duration` whenever the ad class was present, with no check of the ad state beyond a class name. It also muted nothing and never restored state.
- **Filter lists:** only the 62 hand-picked bundled hosts and one optional custom list. EasyList/EasyPrivacy were not supported as subscriptions. Updating was manual.
- **Settings:** one master switch in `PrivacyVpn.kt`. No separate tracker, YouTube or cosmetic toggles.

## Broken or risky (fixed in V3)
- `AdBlockEngine.reload()` ran in its constructor on the **main thread** (Application.onCreate). Parsing a large list there would cause ANR. V3 loads on a background thread and swaps the engine in atomically.
- The YouTube script's `currentTime = duration` was a blind seek: it could skip content if the class was stale and ignored user seeking. Removed; the script only mutes and clicks skip/close controls, and only while an ad is confirmed.
- `YouTube` network rules were bundled with general ad rules, so turning YouTube protection off was impossible. Split into `filters_youtube.txt`, loaded only when the toggle is on.
- Duplicate injection: the old script was idempotent only by accident; the new one guards explicitly via `window.__novaYt.v` and stops the previous version before starting.

## Not implemented before V3 (status now)
- EasyList and EasyPrivacy subscriptions: **added** (`core/SubscriptionPolicy.kt`, weekly refresh, cache fallback, retry backoff, HTTPS only).
- Filter-list manager with offline cache and validation on every load: **added**.
- Tracker toggle: **added**. Cosmetic toggle: **added**. YouTube toggle: **added**.
- Filter-list status UI (last update, last error, manual check): **added**.
- Malware/phishing feed: **not added** (no feed is bundled and none can be fetched from this environment; Chromium Safe Browsing is on).

## Should remain untouched
- Tabs, navigation, bookmarks, history, downloads, Tor, VPN, private profile logic, settings for everything other than ad blocking.
- Existing custom-list flow and its validator.
- The fail-closed Tor path in `shouldInterceptRequest`.

## Known limits that cannot be fixed with this WebView design
- In-stream YouTube video ads come from the same video hosts as the content. Network rules cannot separate them. The page script only mutes and skips.
- Non-skippable ads play muted until they end.
- Service-worker-initiated requests are not seen by `shouldInterceptRequest` unless a ServiceWorkerClient is added (not done).
- Element hiding does not honour `#@#` exception rules yet; a site can lose a legitimate element that an exception would have kept.
- YouTube changes its markup often. Selectors are treated as replaceable, and a broken selector means the handler stops working until updated.
