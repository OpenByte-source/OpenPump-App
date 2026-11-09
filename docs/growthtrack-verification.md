# GrowthTrack verification record

What has been checked for the optional GrowthTrack connection, and what has not. The behaviour being checked is described in [the developer handoff](growthtrack-developer-handoff.md); the owner's decisions of 19 October 2026 are the reference. Tests use a fake transport and fictional data: nothing here contacted GrowthTrack, and no check ran against a pump.

## The gate

Every change to the connection must pass the project gate:

```
./gradlew --offline test :app:assembleDebug :app:lintDebug
```

That is: `Migrate` OK, the scenario harness (58 or more, 0 failing), the self-test (0 failing), `WiringCheck` (0 violations) and JUnit (0 failures), plus a debug build and lint. No check or test was weakened, skipped or deleted to pass. Where one of Karl's tests asserted a behaviour the owner reversed, it was changed to assert the new behaviour, with a comment saying why.

## Bugs found in review, and the tests that hold them

A review of pull request #1 found the problems below and wrote a failing test for each. The fixes make these tests pass; they stay in the suite so a regression shows up.

| Test class | What it holds |
|---|---|
| `GrowthTrackSyncBugsTest` (with `GtSim`), 18 tests | offline or 5xx at token refresh keeps the connection and the session; reconnecting after a permission pause delivers the paused session; starting then cancelling a reconnect leaves the connection alone; a request-level rejection does not drop the whole batch; one 413 does not shrink later batches; a failing session does not hold back new ones; a session deleted while queued is not uploaded; retry timing survives the clock being set back; a stitch chunk a millisecond late, and a Traditional session with ordinary timer lateness, are still sent; timer lateness invents no Milking sets; a Length session's girth expansion is not `length_pumping`; warm-up is not sent as work sets; STOP then Resume ends with the whole session on GrowthTrack; an unacknowledged set is not left out; a short dropout does not make a session unsendable; a long routine name is shortened |
| `AppFindingsTest`, 3 tests | a session finished offline an hour after connecting stays queued and connected; a job stopped during refresh does not disconnect; a resumed session replaces the fragment already sent |
| `GrowthTrackSecurityBugTest`, 7 tests | offline, a busy token endpoint or a stopped job during refresh keep the connection and queue, and a session caught by that is still sent; a transport failure during refresh neither replays the token nor strands the marker; the OAuth `state` does not become a stored identifier; an expired sign-in request does not stay pending |

Findings without a JVM test (the browser opening only the first time, practice runs marked for sending, the setup offer marked seen when drawn, extra callback parameters such as `iss`, the callback covering a live run, Incognito and the app lock on the new screens, dialogs without `FLAG_SECURE`, rotation, Back from About in setup, Erase all finishing before the GrowthTrack erase) are fixed in the core and app code, with a JUnit test wherever the logic is pure; what can only be seen on a phone is on the list below.

## The screens and their lifecycle (app phase)

| Check | What it holds |
|---|---|
| `GrowthTrackScreensTest`, 13 tests | the Settings row's three states and the status card's line; the latest result in plain words, never repeating the status line; the account shown only when the sign-in gave one; Back from the page a screen was opened on leaves it (setup's row, then Back, is setup again); the earlier-sessions question asked once per account, with "also" only when some can be sent; the sign-in return never covers a live run and answers only its own request; the setup offer seen when acted on or left, never when drawn; Erase all reports the GrowthTrack erase; one sign-in return entry per identity |
| `OneScreenManifestTest` | the four return entries (name, icon, filter, exactly one enabled); the callback not exported, drawing nothing, out of Recents; the GrowthTrack screen portrait with its configuration changes absorbed |
| `WiringCheck` 74 (strengthened) | a launch's gate counts by what it does: the hold's backstop only where it reads the hold, the GrowthTrack door only in a GrowthTrack file |
| `WiringCheck` 233 (strengthened) | the GrowthTrack screen's PIN asked through `LockHost` under a disguise, its Recents name and icon, the browser's way back switched to the identity before the browser |
| `WiringCheck` 252 (new) | no GrowthTrack screen opens or stays during a live run; the sign-in return finishes at once over a run and parses no model |
| `WiringCheck` 253 (new) | every GrowthTrack dialog goes through `Ui.dress` (secure flag) and is taken down when the screen stops |

## The way back after the browser (9 November 2026, the owner's pick A)

| Check | What it holds |
|---|---|
| `GrowthTrackReturnTest`, 10 tests | the origin (setup or Settings) kept across the browser and a process death, and the way-back button's words; the return lands on "You’re connected" only when the client stored the approval, otherwise on why not in the refusal's own words; a landing page stays until the person leaves it, and Back from it (or from the screen opened from it) leaves; where "Try again" goes; "What happens now" from the switches, a plan the person does not train left out; the account shown only when GrowthTrack gave one; setup's row: connected with or without the account, why the last sign-in did not connect, or the invitation; the client's answer to a return (approved, declined, another error) with the fake transport |
| `WiringCheck` 252 (f, new) | Back leaves through `leave()`, never `super.onBackPressed()`; `leave()` brings the one screen to the front before it finishes; that asks the door first and uses `RunService.appIntent(this)`; no GrowthTrack file names `SessionActivity.class` |

Not yet checked on a device: the whole round-trip with a real sign-in, from setup and from Settings (the owner's test with the developer): the landing page in both outcomes, its way back landing on setup at Ready or on Settings with the row up to date, hardware and gesture Back on the landing page, and Recents afterwards (one app task, no GrowthTrack task left behind).

On the emulator (19 October 2026, a side-by-side debug build with a local fake transport and airplane mode on; nothing signed in anywhere): every new screen in light and dark and at font scale 1.3; Connect opened the browser on three taps out of three; a return arriving during a live (simulated) run left the run screen in front and started no GrowthTrack screen; a refused return was said on the screen; the connected states (waiting, sent, sending off) and the list; Back from the connect page opened in setup returned to setup; the lock card after leaving, one card however often Unlock is tapped, and no dead end without a screen lock; an open Disconnect dialog gone when the screen came back under its lock; the screen stayed portrait with rotation forced.

Seen there and not fixed: Recents on that emulator shows the app's real icon over both the main task and the GrowthTrack task under a disguise (the task's name is the disguise's); it is the same for the main screen, so it is the launcher's behaviour, not the GrowthTrack screen's.

## Release permissions

The release workflow (`.github/workflows/release.yml`) pins every permission the release APK may hold and fails on anything else, and on a release manifest that allows cleartext traffic. The check was run locally on 19 October 2026 against `aapt2 dump xmltree` of an unsigned release APK built from this branch, and against edited copies of that dump:

| Manifest | Result |
|---|---|
| The release APK as built (17 permissions, INTERNET and ACCESS_NETWORK_STATE among them, cleartext off) | passes |
| plus `ACCESS_WIFI_STATE` | fails, naming it |
| plus `CHANGE_NETWORK_STATE` as `uses-permission-sdk-23` | fails, naming it |
| cleartext switched on | fails |
| ACCESS_NETWORK_STATE removed | passes, with a warning to take it off the list |
| empty dump | fails: the check read no permissions |
| the debug APK | fails: diagnostic console present |

## Website

The site's GrowthTrack section, gallery and screenshots were removed. The Data section keeps "Nothing leaves your phone unless you export, back up or share it yourself." and adds an "Optional · GrowthTrack" callout after it. Checked with Playwright at 1440 and 390 px wide, light and dark: no console errors, no horizontal overflow, text contrast 6.8:1 or better in the callout. The roadmap's step 2c row is kept and labelled Implemented; its link lands on the callout.

## Not yet checked on a device

- A live sign-in against a GrowthTrack test account, and a real upload. (Never against a real person's account, and never during a run.)
- The way back after the browser, from setup and from Settings, in both outcomes (see above).
- The retry job through airplane mode, network changes, Doze, reboot, force-stop and a stopped job.
- Android Keystore and `AtomicFile` behaviour on a real phone: missing or invalidated key, full disk, tampered file.
- The labels and set timings against real recordings from the pump.
- Incognito, the app lock and `FLAG_SECURE` on every GrowthTrack screen and dialog, on a real phone.
- The above on a real phone, and the app lock with a real screen lock (the emulator has none).

Until these run, the record does not claim end-to-end validation.
