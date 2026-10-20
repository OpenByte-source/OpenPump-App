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
- The retry job through airplane mode, network changes, Doze, reboot, force-stop and a stopped job.
- Android Keystore and `AtomicFile` behaviour on a real phone: missing or invalidated key, full disk, tampered file.
- The labels and set timings against real recordings from the pump.
- Incognito, the app lock and `FLAG_SECURE` on every GrowthTrack screen and dialog, on a real phone.
- The browser opening on every Connect tap, rotation and page state, Back from About during setup, and a callback arriving during a live run (refused, never covering the run).

Until these run, the record does not claim end-to-end validation.
