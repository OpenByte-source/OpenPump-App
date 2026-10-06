# Verification

Two things this file tracks, because they get confused with each other:

1. **The port** — everything that changed when the app became OpenPump, and the check that
   proves each change does what it should. Nothing here is a feature; the port was meant to
   be invisible.
2. **Field findings** — what real-hardware testing has turned up since, with the root cause
   and where the fix landed. These are app bugs, not port damage; the parity check below is
   how that distinction is kept honest.

## The parity check

The port was a copy plus a rename, so the strongest check is a diff against the tree it was
copied from, with the rename normalised:

```
for f in app/src/main/java/org/openpump/*.java core/src/main/java/org/openpump/*.java; do
  sed 's/org\.openpump/<old package>/g' "$f" | diff --strip-trailing-cr "<old tree>/$(basename $f)" -
done
```

Result on 2026-09-22, against the tree OpenPump was generated from: **83 of 83 files ported,
4 files differ**, listed below. Resources differ only by the rename (`res/xml/shortcuts.xml`,
which carries the package in a target and an extra key). Everything else is byte-identical.

## 1. Port checks

| # | What changed | Why it could break something | Check | Status |
|---|---|---|---|---|
| P1 | Java package `com.pumpdebug` → `org.openpump` in all 83 files | A package name reached by string rather than by symbol keeps pointing at the old app | Parity diff above; grep the APK's dex for the old package | **pass** — 0 hits in dex |
| P2 | Application id → `org.openpump` | Installs beside the old app; the data directory is new, so nothing migrates by itself | Install both, launch, check the label and that old data is untouched | **pass** on emulator and phone |
| P3 | Provider authorities → `org.openpump.{logs,capture,export}` | Sharing a log, a capture or an export fails if code and manifest disagree | Authorities are constants (`LogProvider`, `CapturePaths`, `Export`) checked against the merged manifest; `dumpsys package providers` | **pass** — all three registered |
| P4 | Launcher shortcut target and extra key renamed | A stale target silently does nothing from the long-press menu | Long-press the icon, run "start last" | **not yet run** |
| P5 | `<queries>` added for `OPEN_DOCUMENT` and `PICK` | Android 11+ hides other apps; without this the gallery picker never opens | Photo slot → "From gallery" | **pass** — picker opens, image imports (this was a real bug, also present in the pre-open-source app) |
| P6 | `Backup.rebasePhotoPaths` + the call in `restoreFromBackup` | Photo paths are absolute and contain the app id, so a restore under the new id would lose every photo | `BackupRebaseTest` (3 cases) + an end-to-end restore of a backup made by the old app | **pass** — photo re-pointed and displayed |
| P7 | `INTERNET` moved to the debug manifest only | The release build must not be able to reach the network | Merged manifest for both variants | **pass** — present in debug, absent in release |
| P8 | Hard-coded `android:debuggable="false"` removed | It made debug builds undebuggable and failed the release lint gate | Merged manifests; `aapt dump badging` on both APKs | **pass** — debug debuggable, release not |
| P9 | `ACCESS_COARSE_LOCATION` declared beside fine, capped at API 30 | Scanning permission shape on Android 11 and below | Lint `CoarseFineLocation`; permission list in the APK | **pass** |
| P10 | Biometric guard moved from API 28 to 29, `catch (Throwable)`, `@TargetApi` | `BiometricManager` is API 29; on Android 9 the resulting `NoClassDefFoundError` is an `Error`, so the old `catch (Exception)` would not have caught it and app lock would crash | Lint `NewApi`; code review. **Not exercised on a real Android 9 device** — no API 28 image here | **fix reasoned, not run** |
| P11 | Build moved from a hand-rolled script to Gradle; Java 8 → 17 target; SelfTest no longer compiled into the APK | A different compiler and dexer for the same source | Full harness on the built tree: SELF-TEST 35,699/0, MIGRATION OK, SCENARIOS 58/0, WIRING OK; JUnit; dex grep for test classes | **pass** — `SelfTest` absent from the APK |
| P12 | `versionCode` derived from the git commit count | A build with no git history must still build | Fresh clone with no `local.properties`, `ANDROID_HOME` only | **pass** — debug and release APKs build |
| P13 | Scrubbed `DEFAULT_MAC` / `DEFAULT_PC` in `MainActivity` | The diagnostic console used to auto-target one device | In a debug build (the console is not in the release APK), open the console, confirm it scans instead of auto-connecting | **not yet run** |
| P14 | Widget provider, boot receiver, reminders — renamed component names | A renamed component that something still addresses by the old name is dead | `dumpsys appwidget`; schedule a reminder and reboot the emulator | **partly run** — provider registered; reminder across reboot not yet run |

### The four files that differ from the pre-open-source tree

- `MainActivity` — app name in the log banner, share subject and TAG; `DEFAULT_MAC`/`DEFAULT_PC` emptied.
- `SessionActivity` — header text "OpenPump"; the biometric guard (P10); the restore photo re-point (P6).
- `Backup` — the new `rebasePhotoPaths` method.
- `Proto` — one comment now cites `docs/protocols/zd21.md` instead of private notes.

## 2. Field findings

Found by the owner on real hardware, 2026-09-22. All of them predate the open-sourcing.

| # | Symptom | Root cause | Status |
|---|---|---|---|
| F1 | The commanded band draws as a vertical hatch; the card names one preset while the paused-clock line demands another's pressure; a rest reads "Shortfall … below commanded" | The run screen resolved "which preset is live" from the wall clock while the pump was given `planIdx`. They drift apart at every boundary, through a paused clock, and after any hold or rest — about 79 s in one report | **fixed** — `RunEdit.liveIndex`, one answer for the tiles, band, deviation, net-TUP recorder and phase line (`LiveIndexTest`) |
| F2 | "Clock paused — not at −8.6 inHg yet" while −11.5 was being commanded | The sentence printed the routine's stored pull; the decision used the adjusted one | **fixed** — it quotes the figure the decision used |
| F3 | Pressing ± did nothing visible for seconds, so the pull was walked up until the seal let go | Each press re-arms a 400 ms settle and the cell showed only the applied value | **fixed** — the pending figure shows at once, captioned "sending…" |
| F4 | ± chips that refuse do so silently | Every clamp was silent | **fixed** — the chip names the limit that stopped it |
| F5 | A rest reported a pressure shortfall | Same as F1 | **fixed** — it says it is resting |
| F6 | "First month" and the beginner cap for someone who entered six months | The months answer was never stored; afterwards the month was months-since-enrolment | **fixed** — the answer is kept with its date; `TrainerTab.monthIndexNow` is the single derivation (`TrainerMonthTest`, `Migrate`) |
| F7 | Setup step 4 could not rename or resize a cylinder | The rows were plain text; editing lived only in Settings | **fixed** — setup renders the same rack editor |
| F8 | Pressure stays high after an adjustment stops carrying | The pump only comes down by bleeding to the drop or by venting | **fixed** as far as software can: the adjustment says which of the two will happen |
| F9 | The phase line counts to a boundary the pump has already passed | The app cannot see the pump's own cycle clock | **partly** — the phase counts from when the preset was armed; reading the phase from the measured trace is still open |
| F10 | A 12:00 manual run reported "COMPLETE after 0:03" | Not reproduced. An advance fired 2.4 s into a step scheduled for twelve minutes | **guarded** — an advance arriving before its own deadline is re-posted, never acted on, and logs that it did |
| F11 | Skip appeared not to skip | The skips worked (six in the log); the screen did not move, which was F1 | **fixed with F1** |
| F12 | Pressure collapsed to zero four times with nothing commanded | Not software: the cuff let go at 39.0–39.3 kPa each time, with no write from the app | **hardware** — a stalled clock now says "nothing is holding" instead of reporting a shortfall for ever |

F10 is the one still worth catching in the wild. The guard makes it harmless, and it now
writes a line naming the step and how early the advance came, so the next log that contains
it names the culprit.
