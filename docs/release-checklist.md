# Release checklist

What has to pass before a build is called a release rather than a beta, and — just as
important — what has **not** been checked yet.

Status values: **pass** (checked on this build), **partial** (checked in part, the gap is
named), **not run** (nobody has tried it), **blocked** (needs something we do not have).
Priority: **B** blocks a release, **S** should be fixed first, **N** nice to have.

Automated checks (`./gradlew test`) are not repeated here; they run on every build and cover
the pure layer. Everything below is what a machine cannot answer.

**Owner only** marks a row only the project owner can close: it needs the real pump, the
owner's own phone, the signing key, or the admin settings of
github.com/OpenByte-source/OpenPump-App. Anyone can run the other rows on the simulated pump
or an emulator. "Emulator" in a status means a debug build on the Android emulator.

---

## 1. Build and package

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| B1 | Whole suite green | `./gradlew test` | 35,828 self-test, 58 scenarios, wiring, migration, JUnit — all pass | B | **pass** |
| B2 | Debug APK builds from a clean clone | fresh clone, no `local.properties` | builds | B | **pass** |
| B3 | Release APK builds and passes the lint gate | `./gradlew :app:assembleRelease` | builds, not debuggable | B | **pass** |
| B4 | Lint has no errors | `:app:lintDebug` | 0 errors, 0 fatal (540 style warnings remain) | B | **pass** |
| B5 | Release APK is signed with the project's key (**owner only**) | signing config + keystore | installs and updates without uninstalling | B | **partial** — the key exists and its fingerprint is in the README and pinned in `release.yml`; the build signs from four `OPENPUMP_*` variables and stays unsigned without them, and `release.yml` signs from the protected `release` environment and checks the certificate with `apksigner`. Not yet set up in OpenByte-source/OpenPump-App (X7). Steps: [docs/release.md](release.md) |
| B6 | versionName/versionCode set for the release | `app/build.gradle.kts` | a release versionName; a versionCode that only goes up | B | **pass** — 0.10.0 set (0.9.0 was skipped); versionCode stays the commit count, by decision |
| B7 | No test or desktop classes in the APK | dex grep | `SelfTest`, JUnit absent | S | **pass** — the desktop replay tool `JournalCheck` was in core/src/main and shipped; it moved to core/src/test (0 of 1021 release classes). `DesktopToolsStayOutTest` pins it, and no shipped source may declare a `main()` |
| B8 | No private data in the APK or repo | string scan | no MAC, no personal paths or addresses | B | **pass** |
| B9 | APK size sane | `ls -l` | under 2 MB | N | **partial** — 968 KB for the 0.9.0 candidate; the 0.10.0 release APK not yet measured (its debug build is about 1.7 MB) |
| B10 | No diagnostic console in the release APK | `dexdump` the release APK's classes; `aapt dump xmltree` its manifest | no `org.openpump.MainActivity` class and no activity for it (both present in the debug APK) | B | **pass** — 0 of 971 classes in release, 34 (the class and its inner classes) in debug; release manifest declares SessionActivity only. `ConsoleIsDebugOnlyTest` pins the source placement |

## 2. Install, upgrade, identity

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| I1 | Installs beside the old private app | install both | separate icons, separate data | B | **pass** |
| I2 | Upgrade in place keeps data | install over an earlier OpenPump | history, routines, settings intact | B | **pass** (several times) |
| I3 | First launch on a clean device | wipe through Settings ▸ Delete all data | opens on Today, no crash, default routines seeded, empty states read correctly | B | **pass** |
| I4 | Launcher shortcut "start last" | the shortcut's own intent extra | opens straight to the start confirmation for the last routine | S | **pass** |
| I5 | Home-screen widget | add, run, tap | shows state, opens the app | S | **partial** — provider registered and an update broadcast completes with no crash; on-screen rendering needs a launcher host |
| I6 | Reminders survive a reboot | set a reminder, reboot the device | the alarm is re-registered without opening the app | S | **pass** — `*walarm*:org.openpump.REMINDER` present after boot |
| I7 | `SCHEDULE_EXACT_ALARM` declared with no `maxSdkVersion`; `RingTimerReceiver` present and `exported=false` | merged manifest, `aapt dump xmltree` | permission and receiver both present; `USE_EXACT_ALARM` absent | S | **not run** on this build (`OneScreenManifestTest` covers this statically) |
| I8 | Disguised launcher icon switches live | Settings › Privacy › choose "Fitness log", then "Habits", then "Notes", then OpenPump, while the app is open | the launcher icon/name follows each pick; a running screen or hold is never interrupted; only one of the four aliases is enabled | B | **not run** on this build (Fitness log checked on an emulator on an earlier build; Habits and Notes never run on a device; the owner's Samsung phone is the one to check) |
| I9 | The lock and the PIN prompt wear the disguise (**owner only**) | a disguise and App lock on; cold-open the app, then quick hide and come back; try "Use PIN instead" | the lock card and the phone's fingerprint prompt and PIN screen show the disguise's icon and name, never OpenPump's (Android 15+ draws the asking app's icon on both); cancelling leaves the lock card with its Unlock | B | **not run** — needs a device with a screen lock (and Android 15 for the prompt's icon) |
| I10 | The splash wears the disguise | a disguise on; swipe the app away, open it from the disguised icon | Android 13+: the splash shows the disguise's icon; Android 12: a blank icon on the dark ground; older: the dark ground only — never the OpenPump mark | S | **not run** |
| I11 | Messages wear no logo | a disguise on; turn a Settings switch, then trigger a message on the run screen | the message is drawn by the app, near the top, with no OpenPump logo beside it; as itself, off the run screen, Android's own toast | B | **not run** — Android 12+ draws its toast with the app's real icon, the reason for this |

## 3. The run loop — simulated pump

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| R1 | Start → prime → work → rest → summary | Today ▸ START | plays through, summary files | B | **pass** |
| R2 | Skip | Skip on a preset | ends that step, starts the next | B | **pass** |
| R3 | +30 s (+30 s hold, rest or step) | during a step | the step is 30 s longer, the countdown follows | B | **partial** — passed as "+30 s"; the buttons that say what they add are not yet run (R29) |
| R4 | Pause and resume | Pause, then Resume | pressure held, clock frozen, resumes | B | **partial** — passed on the earlier 0.10 build as Hold and release (override written, "HOLD released … resumes with 1:43 left"); the final Pause / Resume buttons not yet run |
| R5 | Rest inserted by hand | Rest ▸ | vents, counts down, rejoins | B | **pass** — vented, released after 0:30, step resumed |
| R6 | − / + pull and drop | the − / + strip in a set | the cell moves at once, the pump follows, the countdown does not restart | B | **partial** — passed with the old nudge chips; the strip is not yet run on a device (R24) |
| R7 | − / + at a limit | + on Pull at the ceiling | the button is dimmed, a tap says which limit stopped it ("10.0 inHg is the ceiling." or the safety ceiling) and buzzes harder | S | **partial** — passed with the old chips ("That is your safety ceiling, −6.5 inHg — Settings › Session is where it moves"); the strip's words not yet seen on a device |
| R8 | This rep vs This set scope | switch and adjust | carry ends where the scope says | S | **not run** |
| R9 | Whole-routine offset | ROUTINE ▸ | moves remaining work sets only; past the plan's +2 kPa - and on a Length routine past nothing - a dialog asks once for each new highest offset (0.10, replacing the Length refusal); every refusal and the cap are written on the sheet | S | **partial** — the cap message was seen on device as a toast; it is now written on the sheet, and a Length routine shows its reason instead of the buttons (RoutineOffsetTest, WiringCheck 140) — not yet re-seen on device |
| R10 | Speed slider | drag mid-run | pump speed changes | S | **not run** |
| R11 | STOP vents and confirms | STOP | vent watch reaches VENTED | B | **pass** |
| R17 | A step ends on its own and the next begins | let a preset run out | next slot started at the deadline, nothing early | B | **pass** — prime ended at exactly 4:00, work hold started |
| R18 | A superseded advance cannot act | come out of a rest, then let the step end | the leftover is ignored, the run continues | B | **pass** — seen and ignored on the emulator |
| R12 | Leaving and returning mid-run | Home, then back | run continues, screen consistent | B | **pass** |
| R13 | Screen off during a run | lock the phone | run continues (wake lock) | B | **pass** — a rest began and ended with the screen off |
| R14 | Notification controls | pull down the shade | STOP and PAUSE / RESUME work from there | B | **partial** — the ongoing notification shows live values and both actions (as HOLD before the rename); tapping them from the shade not yet confirmed |
| R15 | Process killed mid-run | swipe from Recents | recovery dialog offers rejoin/stop | B | **partial** — dialog seen after a force-stop |
| R16 | Rotation / font-scale change mid-run | change the font scale mid-run | no restart, run intact | S | **pass** — absorbed mid-run; orientation is locked to portrait by design |
| R19 | The 0.10 run screen: stage bar, status line, NOW timer | simulated pump, a routine with a rest | the live stage bar fills (ticks per set, rest its own segment, current outlined) with "now: … · next: …" under it ("now: Work · ramp" while a ramp plays inside a work stage); the NOW card's line says where you are and what is next ("Hold · set 3 of 10 · next: set 4", "Step 2 of 5 at … · next: …"), and no list of upcoming steps sits under it; the status line names the phase only ("WORK · SET n OF m · HOLD" / "· DROP", "RAMP · STEP k OF n", "WARM-UP", "REST · PULL IN m:ss") with elapsed / planned and never a pressure; no hero timer and no glow behind the timer; STOP where it was | B | **partial** — the earlier 0.10 screen passed on the emulator 2026-09-28; the final wording not yet seen |
| R20 | Rest on the chart | let a planned rest play; insert one with Rest | hatched band "REST · m:ss left", the vented line in the rest colour once vented, the chart's reading says "vented", the NOW card says "REST" once with "· vented" under it, dotted ramp to the next pull, past rests lighter; last 10 s the status line pulses "PULL IN 0:10" | B | **partial** — the band, the line and both rests seen on the emulator 2026-09-28 (the buzz ("Vibrate before the pull") not felt there); "vented" and the single REST not yet seen |
| R21 | Pause says so | Pause, then Resume | status line white, on one line, "PAUSED · PRESSURE KEPT · TAP RESUME" (or "PAUSED · PRESSURE KEPT" where the full line does not fit); NOW card "PAUSED · …" with a grey timer; the button reads Resume in solid amber; the chart line white | B | **partial** — the earlier "HOLD · CLOCK WAITING" line and Release button passed; the final words not yet seen |
| R22 | Coming steps | the button under the chart | one card per set of a stage ("Sets 6–10", "Ramp · 5 steps"), the later sets of the stage playing among them; the playing step is listed read-only ("change it with the − / + on the run screen") with no controls, its summary as it runs now; later work blocks edit sets, hold each, drop to and drop time; rests length; ramps steps and time per step with the pressure range read-only; skip switches (none on the playing step or the warm-up); the total shows the change and "was"; undo per card and Undo all; the bar marks a stage skipped (hatched) once all its cards are, and changed (dot); no pull can be raised; caps refused on the card | B | **partial** — the earlier list passed (rest +30 s and a skip seen on the emulator); the caps and refusals are held by RunScreenRulesTest and WiringCheck 225; the final list not yet seen on a device |
| R23 | Run colours | Settings › On the run screen › Run colours | switch, where (status line default), per-kind colours, four sets; STOP never changes; a colour near STOP red refused; the chart line follows the step colour (amber with the switch off, white when paused) | S | **partial** — sheet seen on the emulator; refusal by RunScreenRulesTest, not tapped on a device |
| R24 | The − / + strip in a set | simulated pump, a work routine; tap − and + on each cell | THIS SET · CHANGES APPLY NOW; Pull to · target ±1 kPa, Hold time ±5 s, Drop to · target ±1 kPa (0 reads "vent"), Drop time ±1 s; each change reaches the pump at once, the countdown does not restart and the set number is kept (the pump starts the set again from its hold); a change of hold or drop time keeps the number of sets and moves the block's end so every set left runs whole, and says so ("Hold 0:50. This block now ends 1:40 later — 10 sets, each whole."); a move past the two-hour stop is refused; nothing else on the page edits the step; the NOW card has no "SET ›" row and keeps "ROUTINE ›" | B | **not run** |
| R25 | The strip at its limits | drive each cell to its end | a button at its limit is dimmed; a tap says the limit ("That is the lowest.", "Already a full vent.", "The drop stays below the pull.", "The pull stays above the drop.", "4:15 is the longest the pump takes.", "10.0 inHg is the ceiling.") and buzzes harder; + past 10.0 inHg asks once for each new highest pull (0.10) and never passes the safety ceiling, "Most you will go to" or a new person's first-month 6.0 inHg; while paused a change says to resume first | B | **not run** |
| R26 | The strip in a rest, a ramp and the warm-up | a routine with all three | rest: THIS REST · CUFF VENTED, Rest length ±15 s, never below what has run + 5 s ("Use End rest to finish it now.", on a ramp "Use Skip step …", in the warm-up "Use Skip warm-up …"); ramp: THIS RAMP · from → to inHg, Time per step ±5 s; warm-up: Warm-up length ±15 s; the countdown follows the change | B | **not run** |
| R27 | Press and hold, and touch feedback | hold + or − | after half a second it repeats and stops at a limit; a light vibration per step, a stronger one at a limit; nothing when the phone's touch feedback is off | S | **not run** |
| R28 | Where the − / + controls sit; More › | Settings › On the run screen › Where the − / + controls sit; More › in a set | Pinned above the buttons (default) keeps the strip in view above the buttons; Under the chart puts it right after the chart, which keeps its full height; the choice survives a restart; More › opens Adjust the running set with speed and Rest of this block / This set only | S | **not run** |
| R29 | The buttons follow the step | play a warm-up, a set, a ramp and a rest | Pause always first; work Pause · Skip these sets · +30 s hold · Rest; rest Pause (greyed; a tap says "Nothing to pause in a rest: the cuff is vented.") · End rest · +30 s rest; ramp Pause · Skip step · +m:ss step (one whole cycle; +30 s step on a one-cycle step); warm-up Pause · Skip warm-up · +30 s warm-up; +30 s hold stops at 4:15, keeps the number of sets and moves the block's end, and in the drop says "The pump goes back to the hold now." | B | **not run** |
| R31 | One session from a rejoin or a resume | kill the app mid-run and Rejoin; STOP, then Resume, then Done | History shows one row; the summary says "Rejoined once after the app closed" or "Resumed once after STOP"; its time adds up both parts | B | **partial** — the rejoin passed on the emulator; STOP › Resume filed only the first part there, fixed after, not re-run |
| R32 | The run bar is the routine running | a fatigue block; "Adjust the running set › Rest of this block"; "Skip these sets"; a skipped warm-up | one part per set, "set N of M" fills the Nth part, the fill never goes back; skipped sets and a skipped warm-up hatched | B | **pass** — emulator |
| R33 | A stopped run says why | STOP; a lost link; Android closing the app | the summary names the reason | S | **partial** — STOP passed on the emulator; the others not run |
| R30 | Messages clear STOP | with the strip pinned above the buttons, then under the chart: a strip limit, "+30 s hold", a change of hold; then a message with a sheet open (a refused ramp change with More › closed, the pump refusing a change) | the strip's and the buttons' messages show above the strip, the buttons and STOP; one said over a sheet, or the pump's answer, near the top of the screen; none over STOP, and none is Android's own toast, disguise or not | B | **not run** |

## 4. The run loop — real pump

Every row in this section is **owner only**: it needs the real pump.

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| H1 | Scan, connect, reconnect | Connect screen | finds the Epic Hydro PE Pump (ZD21), connects, remembers | B | **pass** (owner) |
| H2 | Full trainer session start to finish | run one | completes, files, no stall | B | **not run** on this build |
| H3 | Link loss mid-run | walk out of range | lost screen, three-way choice, vent | B | **not run** |
| H4 | Reconnect and resume at step N | choose rejoin | same step resumes, table rewritten | B | **not run** |
| H5 | Commanded band matches the wire | compare chart to the log | band = what the log says was sent | B | **not run** on this build |
| H6 | Adjusted pressure reaches the pump | nudge, read the log | one override per settle, values match | B | **partial** — log confirms writes land |
| H7 | Clock pause behaves with a real cuff | let pressure fall short | pauses, says why, recovers | B | **not run** on this build |
| H8 | Seal failure is named | pressure collapses | "nothing is holding", not a shortfall | B | **not run** on this build |
| H9 | Ceiling is never exceeded | set a low ceiling, push ± | nothing commanded above it | B | **not run** |
| H10 | Vent on STOP confirmed by telemetry | STOP under pressure | VENTED within seconds | B | **pass** (owner's log) |
| H11 | The after-session tissue test runs | a plan session with the τ test, to the end | both τ measured; the log says "arming the pull from the open air" and the last real reading before the pump went quiet was ≤ 2 kPa | B | **not run** on this build (fixed on the simulator; the arming from a vented cuff rests on that ≤ 2 kPa reading) |
| H12 | A pull from a vented cuff reads a pressure at once | same run, the first frames of each τ pull | real readings, not 0.0 (0.0 frames make τ refuse with "no reading") | B | **not run** |
| H13 | The pump's table rewritten mid-step | during a ramp, Reshape or shift the rest of the set | the pump either keeps the step or stops and vents; it must not jump ahead to the next, higher step | B | **observed** on the owner's log (22 Sep, build 097cd8a) — keeps cycling the step; not run on this build (the simulator now keeps cycling too) |
| H14 | How fast a hold coasts | any plan session, the log's pressure through a 2-minute hold | the coast rate in kPa/s — it decides how the net counting is fixed (known issue in the 0.9.0 notes) | I | **not run** |
| H15 | Does every StopWork get its ack | the session journal (ACK/CMD lines) from 20+ StopWorks in three states: under pressure, during the pump's own release (a routine's drop), and idle or already vented (START after a STOP, or STOP twice); plus one with the pump switched off mid-hold | an `ACK stop ms=…` for every `CMD stop` that reached a powered pump, with its latency; none for the switched-off one. A single missing ack in any state means the ack can't be required for a vent verdict (it would close the "lost stop during the pump's own release" gap) | B | **not run** |
| H16 | Adjacent presets play without a break (the seam test) | two one-minute routines with the same commanded profile, one sent as a single preset and one as two; then a set with a hold longer than 255 s (a 300 s hold at 20 kPa is sent as two presets back to back) | the pressure carries across each join as it does inside one preset: no release, no re-pull, no pause. Record the session journal around every join (the `CMD start` line and the `PMP` readings from 5 s before it to 10 s after), and what the pump was heard or seen to do there | B | **not run** — holds longer than 255 s are built this way and are on by default; per-repetition overrides stay off until it passes |

## 5. Trainer

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| T1 | First enrolment, all six steps | fresh profile ▸ Trainer | months, pressures, tracks, rack, program, week | B | **pass** on an earlier build — ends on a derived position and an offer to save the first routine; 0.10's questions are T11 |
| T2 | Months answer is kept | answer 6, walk to the end | reads month 6, not "first month" | B | **pass** — unit, migration, and the wizard's own "Month (from your months answer): 6" |
| T3 | Cylinder edit inside setup | step 4 | rename, bore, length all work | B | **pass** — rename, inside diameter and usable length all present and working in step 4 |
| T4 | Recalibrate, three steps | Where I am ▸ Recalibrate | pre-filled, saves | B | **pass** |
| T5 | Level gates L1→L2→L3 | advance months | gates open at 6 and 12 | S | **not run** |
| T6 | Deload week | reach a deload | offered and applied | S | **not run** |
| T7 | Length / traction track | enable, add a cylinder | prescribes traction, load in lb | S | **not run** |
| T8 | Feeder sessions | enable at L3 | extra sessions appear | N | **not run** |
| T9 | Pause and resume the plan | Pause the plan | nothing prescribed while paused | S | **not run** |
| T10 | Prescription matches the guide | read the week table | printed figures = commanded figures | B | **partial** — covered by scenarios |
| T11 | Placement by session minutes | Set up again; answer each track's minutes | Confirm states each track's level and week and what placed it ("Level 1, week 6 — closest to your 20 min", "Placed by your 30-min session…"), and "Months pumping" | B | **pass** — emulator |
| T12 | Weeks by volume | two full girth and two full length sessions in a week | both tracks' weeks count once their scheduled days are done; This week says why a week waits | B | **partial** — the wording passed on the emulator; the count itself by tests only |
| T13 | Long training days and the 90-minute day | both tracks on; each of the four choices; a day over 90 min | the week each choice gives; the long-day card; its switch to alternate days and Undo | S | **partial** — the wording seen on the emulator; the switch and Undo not tapped |
| T14 | The gain brake | readings on target and rising at-rest measurements | "You're gaining, so the next step waits until … Step up now?"; Step up now applies the step; Wait keeps the slower pace | S | **pass** — emulator |
| T15 | Hold lengths and Undo | Trainer › What it writes › Hold lengths; then Undo on the plan's notice | the routine rewritten (15 × 30 s fatigue block); Undo puts back a runnable routine and its hold length; START never offers 0 cycles | B | **pass** — emulator |
| T16 | The deload question and the month-12 break | reach a due deload; reach month 12 | From tomorrow / From next Monday / Pick a day; the break's dates and the way back | S | **not run** on a device (tests only) |
| T17 | An upgrade from an earlier trainer | install over a build with the trainer set up | "The plan has new choices" once; the new questions on one screen; values kept | S | **partial** — the earlier upgrade notice seen on the emulator |

## 6. Library and routines

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| L1 | Create, edit, delete a set | Library ▸ Sets | persists across a restart | B | **not run** |
| L2 | Ramp editor and drag graph | edit a ramp set | handles move, values clamp | S | **not run** |
| L3 | Routine editor: stages, order, rests | Library ▸ Routines | plays in the order shown | B | **partial** — the editor opens, shows stages and per-set rows, duplicate/rename/share/delete; reordering and playback order not exercised |
| L4 | Quick run and favourites | Today ▸ Run something else | starts the chosen set | S | **partial** — sheet opened |
| L5 | Manual run | Manual cycle ▸ Run | runs for the duration set | B | **pass** (6:00 played) |
| L6 | Try this set from the editor | Try ▸ | runs the edited values, editor untouched | S | **not run** |
| L7 | Save changes after a run | change mid-run, finish | offers in place / copy / tried set | S | **partial** — the summary offered SAVE CHANGES after a mid-run adjustment |
| L8 | Share code out | share a routine | `PD3:` code, short | B | **pass** — a 68-character `PD3:` code |
| L9 | Share code in | paste the code back | same routine, same values | B | **pass** — same duration, peak and set values |
| L10 | v2 code still imports | paste an old code | imports unchanged | S | **not run** |
| L11 | Junk code refused | paste nonsense | says so, changes nothing | S | **pass** — "That doesn't look like a routine code", nothing added |

## 7. Measurements, photos, progress

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| M1 | Log a reading | Log a reading | saved, appears in history | B | **partial** — the measurement sheet opens with its method list; nothing saved yet |
| M2 | Baseline and standardise hold | enable, run | asked at the right step | S | **not run** |
| M3 | Camera capture | photo slot ▸ camera | preview, level bubble, saved | B | **pass** (earlier build) |
| M4 | From gallery | photo slot ▸ gallery | picker opens, imports | B | **pass** |
| M5 | Photo compare | Progress ▸ Compare | two photos side by side, scaled | S | **not run** |
| M6 | Photo calendar | Progress ▸ Photos | thumbnails by date | S | **not run** |
| M7 | Charts and goals | Progress | lines, goal, projection | S | **not run** |
| M8 | Measurement history edit | edit a reading | changes persist | S | **not run** |
| M9 | Camera denied | deny the permission | falls back to gallery, says why | S | **not run** |
| M10 | No camera hardware | emulator without camera | no dead button | N | **not run** |

## 8. Data: backup, restore, export

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| D1 | Backup export | Settings ▸ 0000 ▸ Backup | zip written, shareable | B | **pass** |
| D2 | Restore into the same app | restore own zip | everything back | B | **pass** — twice, photos included, behind two confirmations |
| D3 | Restore from the old app id | restore a PumpDebug zip | photos re-pointed and visible | B | **pass** |
| D4 | Restore a corrupt or foreign zip | feed it junk | refuses, keeps current data | B | **pass** — "That file isn't a valid pump-backup zip", nothing changed |
| D5 | Export CSV / session data | Export screen | file opens elsewhere | S | **not run** |
| D6 | Share a diagnostic log | Settings ▸ logs ▸ share | chooser opens, file readable | S | **not run** |
| D7 | Old save files still load | keep a pre-Gradle model.json | loads, nothing lost | B | **pass** (Migrate) |
| D8 | Storage permission-free paths | photos and logs | app-private dirs only | B | **pass** (by design) |

## 9. Settings and safety

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| S1 | Safety ceiling change | lower it to −6.5, then push ± up ten times | nothing commanded above it | B | **pass** — highest pull on the wire was exactly 22 kPa, the ceiling |
| S2 | Units kPa ↔ inHg | switch | every figure converts, none stale | B | **pass**, with one gap: a routine's stored NAME keeps the unit it was minted in ("@ 5.0 inHg" while its figures read kPa) |
| S3 | App lock — refuses without a device credential | turn it on with no screen lock set | refuses and explains | B | **pass** — "App lock needs a fingerprint, PIN, pattern or password set on this phone to challenge" |
| S3b | App lock — PIN challenge (**owner only**: the owner's phone) | enable with a screen lock, reopen | asks on open / on photos | B | **pass** — owner, on their own phone, 2026-09-23 |
| S4 | App lock — biometric (**owner only**: the owner's phone) | enrol a fingerprint | prompt appears, PIN fallback works | B | **pass** — owner, on their own phone, 2026-09-23 ("worked perfectly"); whether the fingerprint path and the PIN fallback were both exercised was not reported separately |
| S5 | App lock on Android 9 | API 28 device | PIN path, no crash | B | **blocked** — no API 28 image |
| S6 | Developer gate 0000 | enter it | dev section appears | S | **pass** |
| S7 | Simulated pump toggle | on / off | runs against the model, marked SIMULATED | B | **pass** |
| S8 | Haptics and tones | toggle | countdown ticks, stage change | N | **not run** |
| S9 | Discreet notifications | toggle | notification text changes | N | **not run** |
| S10 | Battery-optimisation prompt | tap "Fix this" | opens the right settings page | S | **not run** |
| S11 | Notifications denied | deny POST_NOTIFICATIONS | run still works, warns once | B | **partial** — warning seen in the owner's log |
| S12 | Bluetooth off / denied | turn BLE off | says so, no crash | B | **not run** |
| S13 | Disconnect refused mid-run | tap Disconnect while running | refuses and says to stop the session first | S | **pass** |
| S14 | Incognito: hide from recent apps | toggle, then check the app switcher | preview blanked; screenshots and screen recording refused, including in a dialog | S | **not run** on this build |
| S15 | Incognito: quick hide during a run | double-tap the top bar, try each of A/B/C | A leaves with the run still going; B pauses (or STOPs if the pause can't go up or is refused); C (default) STOPs through the normal vent check; every case leaves to the home screen | B | **not run** on this build |
| S16 | Incognito: app lock re-asks after quick hide | turn on App lock and quick hide, then return | asks again — but never while a run, a hold or a safety warning is on screen | B | **not run** on this build |
| S17 | Ring timer fires as a system alarm | start it, then kill the app | the "take it off" notification posts on time on its own, on the safety-alert channel, ringing and vibrating | S | **not run** on this build |
| S17b | Ring timer countdown after a restart | start it, kill the app, reopen that session's summary before the end | the card shows the running countdown (not "Start"); "Took it off" cancels it and no notification follows | S | **not run** on this build |
| S18 | Safety alerts still ring and vibrate with the lock screen set to neutral wording | trigger "Vent not confirmed" with Safety warnings on the lock screen on | lock screen reads "Session needs attention now"; the phone rings and vibrates regardless | B | **not run** on this build |

## 10. Platform matrix

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| P1 | Android 14 (API 34) | emulator + owner's phone | everything above | B | **pass** |
| P2 | Android 12–13 | permission model changes | BLE scan and notifications behave | B | **not run** |
| P3 | Android 11 | package visibility, scoped storage | gallery, share, photos | B | **not run** |
| P4 | Android 7–10 (min 24) | oldest supported | launches, runs, app lock takes the PIN path | B | **not run** |
| P5 | Small screen / large font | 360 dp at 320 dpi, font scale 1.3 | no clipped or crushed controls | S | **pass** after two fixes — the achievements row crushed its label to one character, and the header broke the pump's name mid-word; both fixed and re-checked. The run card's cells still sit under the footer until scrolled |
| P6 | Dark and light | system theme | readable both ways | S | **not run** — the app draws its own dark palette; a light-theme device has never been tried |
| P7 | Every control is labelled | dump the tree on each tab | no clickable node without text or a description | S | **pass** — Today, Library, Progress, Trainer, Settings all clean |
| P8 | Touch targets | measure every clickable node | 48 dp minimum | S | **pass** — the three nodes reported under 48 dp are inner text views; the controls themselves respond at their visible size (verified by tapping) |

## 12. Hostile and malformed input

Everything the app does not control: a Bluetooth device, a file from a picker, a save file
of unknown age. All of these are covered by `HostileInputTest` and run on every build.

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| Z1 | Garbage on the wire | 20,000 random frames plus near-miss shapes | never a sample, never an exception | B | **pass** |
| Z2 | A reading that is not a finite number | `#AUTO,-1e400,0,0`, `NaN` | refused — it used to come through as infinity | B | **fixed today**, then pass |
| Z3 | A backup that is not a zip, or is truncated | feed junk | no entries, nothing restorable | B | **pass** |
| Z4 | A zip bomb | one 80 MB entry | refused at the ceiling | B | **pass** |
| Z5 | A backup entry that escapes the photos folder | `photos/../../reading-x.jpg` | ignored — it used to be written outside the staging folder | B | **fixed today**, then pass |
| Z6 | A junk or truncated save file | eleven shapes of malformed JSON | loads as a usable model, never crashes | B | **pass** |
| Z7 | A tampered save file | pressures far above the ceiling | the plan still obeys the ceiling | B | **pass** |
| Z8 | A clock that jumps backwards mid-run | elapsed against an earlier now | never negative | B | **pass** |
| Z9 | A long run's memory | 200,000 samples into the trace | the window stays bounded | B | **pass** |
| Z10 | A comma-decimal phone | type "5,9" | accepted, along with the printed minus sign | S | **pass** — `typedNumber` already handles both |
| Z11 | Storage full or unwritable | fill the disk, then capture a photo | says so, loses nothing | S | **not run** |
| Z12 | Two pumps in range, or someone else's pump | scan with both | the right one is remembered | S | **not run** |


## 11. Release hygiene

| # | Check | How | Expected | Pri | Status |
|---|---|---|---|---|---|
| X1 | Contact address in README and CODE_OF_CONDUCT | fill in | a real address | B | pass — theopenpe.team@gmail.com (README, SECURITY, CODE_OF_CONDUCT, site) |
| X2 | CODEOWNERS names a real reviewer | GitHub | every owner in `.github/CODEOWNERS` resolves | B | **pass** — `@OpenByte-source`, the owner's account (there is no organisation team) |
| X3 | CHANGELOG entry for the release | write it | what changed since the last public main | S | **pass** — `0.10.0 — unreleased`, the first published release (0.9.0 was never released); the date goes in when it is tagged |
| X4 | SAFETY.md still true of the code | re-read | invariants match behaviour | B | **partial** — its timings (5 s, 6 s, three attempts, 300 ms, 600 ms, 60 s) checked against the code for 0.10.0; behaviour on a real pump is section 4 |
| X5 | Licence headers / AGPL notice | check | present | S | **pass** |
| X6 | Tag the release commit (**owner only**) | `git tag -a v0.10.0` | annotated tag on `main` | S | **not done** |
| X7 | The `release` environment in OpenByte-source/OpenPump-App (**owner only**) | [docs/release.md](release.md) steps 3–5 | required reviewer, `v*.*.*` tags only, the four secrets | B | **not done** |
| X8 | Rulesets on `main` and `v*` tags (**owner only**) | release.md step 6 | no force push or deletion; tags only by the owner | B | **not done** |
| X9 | Pages, Discussions (Ideas, Q&A) and private vulnerability reporting (**owner only**) | release.md steps 7–9 | the site deploys; the README's and site's links to Ideas, Q&A and Report a vulnerability open | S | **not done** |
| X10 | Push `main` (**owner only**: the owner says go) | push, then CI | CI green on `main` | B | **not done** |

---

## What blocks a release today

Every priority-B row above that is not yet **pass**:

1. **B5, X7, X8, X10** (owner only) — the key and the signing machinery are in place and the
   version is 0.10.0 (B6); the new repository still needs its `release` environment with the
   four secrets, its rulesets, and `main` pushed ([docs/release.md](release.md)).
2. **H2–H5, H7–H9, H11–H13, H15, H16** (owner only) — not run on real hardware with this build, and **H6**
   is partial; of section 4 only H1 and H10 pass. Everything in section 4 was diagnosed from
   one log against the previous build. (H14, the coast rate, is not run either; it is marked
   priority I rather than B.)
3. **S5** — app lock on Android 9 is blocked: there is no API 28 image here. S3, S3b and S4
   pass.
4. **P2–P4** — the app supports Android 7 upward and has only ever run on 14.
5. **R14, R15, R31** (partial) — the notification's STOP and PAUSE not yet tapped from the
   shade; the recovery dialog seen only after a force-stop, not a swipe from Recents; STOP ›
   Resume filed as one session not re-run since its fix.
   **R3, R4, R6, R19–R22, R24–R26, R29, R30** — the final 0.10 run screen not yet walked
   through in full; **I8, I9** (owner only) and **I11** — the disguises on a device.
6. **S11** (partial) and **S12** (not run) — notifications denied, and Bluetooth off or
   denied.
7. **L1** (not run) and **L3** (partial) — sets persisting across a restart; a routine's
   reordering and playback order.
8. **M1** (partial) — a reading has not yet been saved and seen in history.
9. **T10** (partial) — the printed prescription against the commanded figures, covered by
   the scenarios only.
10. **X4** (partial) — SAFETY.md's behaviour on a real pump is section 4.
11. **T12** (partial) — weeks by volume: the count is held by tests, not yet watched on a device.
