# Integration test plan — AdBlock V3

**Status of every item below: NOT RUN.** The development environment had no Android SDK, no emulator or device, and no network access. Nothing here was executed. Use the status column to record results on a real device.

Legend for the Status column: PASS / FAIL / NOT RUN. Record the device, WebView version and date beside each result.

## A. Build and unit tests (run on a machine with the Android SDK)
| # | Check | Command | Status |
|---|---|---|---|
| A1 | JVM core tests | `./gradlew testDebugUnitTest` (or `tools/run-core-tests.sh <gradle-dir>`) | NOT RUN |
| A2 | Lint | `./gradlew lintDebug` | NOT RUN |
| A3 | Debug build | `./gradlew assembleDebug` | NOT RUN |

## B. General websites (device)
| # | Check | Expected | Status |
|---|---|---|---|
| B1 | A news site with banner ads | Ad boxes hidden or absent; page layout intact | NOT RUN |
| B2 | Page with a tracking pixel / analytics script | Request blocked; counter increases | NOT RUN |
| B3 | Site on the per-site allow-list | Nothing blocked or hidden on that site | NOT RUN |
| B4 | Click that opens a pop-up ad | Pop-up not opened; notice "Blocked a pop-up from …" | NOT RUN |
| B5 | Link that redirects through an ad host | Redirect hop stopped; notice shown | NOT RUN |
| B6 | Login flow on a site that uses Google sign-in | Still works (no Google domain blocked globally) | NOT RUN |

## C. YouTube (device, logged in and logged out)
| # | Check | Expected | Status |
|---|---|---|---|
| C1 | Video with a skippable pre-roll | Ad muted; skip button clicked when it appears | NOT RUN |
| C2 | Video with a non-skippable ad | Ad muted until it ends; no seeking; content resumes muted state restored | NOT RUN |
| C3 | Mid-roll ad | Same as C1/C2 | NOT RUN |
| C4 | Overlay / banner ad | Overlay closed or hidden | NOT RUN |
| C5 | Normal playback with no ads | Playback unaffected; seeking by the user works | NOT RUN |
| C6 | Switching videos (SPA navigation, no reload) | Handler still active on the new video | NOT RUN |
| C7 | Mini-player open/close | Handler still active | NOT RUN |
| C8 | Player recreation (e.g. quality change, fullscreen) | Handler still active | NOT RUN |
| C9 | Comments, recommendations, subtitles, settings menu | Visible and usable | NOT RUN |
| C10 | YouTube protection switched off | Script not injected; YouTube endpoint rules not applied | NOT RUN |

## D. Filter lists
| # | Check | Expected | Status |
|---|---|---|---|
| D1 | First launch with network | EasyList/EasyPrivacy downloaded; "Last updated" shown | NOT RUN |
| D2 | Second launch within 7 days | No download attempted | NOT RUN |
| D3 | Airplane mode, cache present | Cached lists used; no crash | NOT RUN |
| D4 | Airplane mode, no cache | Bundled rules used; error shown only after a check | NOT RUN |
| D5 | Server returns HTML instead of a list | Rejected; cache kept; error shown | NOT RUN |
| D6 | Server redirects to http | Rejected | NOT RUN |
| D7 | Malformed rules in a custom list | Counted in "Skipped: … malformed"; valid rules still apply | NOT RUN |
| D8 | "Check filter lists now" | Downloads even if the list is not due | NOT RUN |

## E. Stability
| # | Check | Expected | Status |
|---|---|---|---|
| E1 | Rapid navigation between 10 sites | No crash; no ANR | NOT RUN |
| E2 | Several tabs, including YouTube in two | Each tab keeps its own page script | NOT RUN |
| E3 | Background and foreground switching during an ad | No crash; mute state restored | NOT RUN |
| E4 | WebView recreated (tab closed and reopened) | Script re-injected once; no duplicate timers | NOT RUN |
| E5 | Cold start with a large cached list | Browser responsive; list loads in background | NOT RUN |
| E6 | Toggle YouTube protection while a video plays | Takes effect on next page load; no crash | NOT RUN |

## Reporting
Attach the device model, Android version, WebView version, and the Logcat output for any FAIL.
