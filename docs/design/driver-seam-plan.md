# The driver seam — the plan

The design is in [driver-seam.md](driver-seam.md). This is the order of work. The one pump
today is the Epic Hydro PE Pump, called the ZD21 here and in the code.

Every step:

- ships on its own, with `./gradlew test` green;
- changes no behaviour, unless it says so and the owner has agreed (the design's
  section 7);
- is one pull request, small enough to review in one sitting.

**Safety review** marks the steps that touch the stop/vent path or commanded pressure.
They need two approving reviews, one from the owner as safety maintainer
([SAFETY.md](../../SAFETY.md)).

**Size** is a rough guide: S is under about 200 changed lines, M up to about 800, L more.
Tests count.

## Two checks every step uses

**Encoder goldens** (made in S0). Text files of the exact bytes each command shape
produces today. A step that moves code must reproduce them byte for byte.

**Journal parity.** A fixed set of practice sessions (the simulated pump) run on an
emulator before and after the step: a routine to the end, STOP mid-routine, a live
adjustment, a hold and release, an inserted rest, the standardisation hold, the seal
check, a link loss by switching the simulator off and on. Compare the journals' `CMD`,
`ACK` and `LINK` lines with the times masked. Any difference is either the point of the
step or a bug. This is the parity method `docs/verification.md` used for the port.

## The steps at a glance

| Step | What | Safety review | Size |
|---|---|---|---|
| S0 | Pin today's bytes | no | S |
| S1 | The interface, the values and the ZD21 driver, not yet wired | no | M |
| S2 | The transport comes out of `PumpLink` | **yes** | M |
| S3 | Every write except the stop goes through a pass-through supervisor | **yes** | M |
| S4 | The stop path goes through the supervisor | **yes** | S |
| S5 | The ceiling clamp in the supervisor | **yes** | S–M |
| S6 | The vent watch moves into the supervisor | **yes** | L |
| S7 | One link watchdog, the auto-stop and the 2-hour cap in the supervisor | **yes** | M |
| S8 | Episodes, the START gate and capability refusals | **yes** | M–L |
| S9 | `SimPump` becomes the ZD21 driver's simulator | **yes** (practice labelling) | S–M |
| S10 | The conformance kit | no (tests), code-owned | M |
| S11 | Golden wire transcripts from the real ZD21 | no (tests) | M |
| S12 | ZD21 facts leave the session engine | **yes** | L, in parts |
| S13 | The driver catalog and the experimental switch | **yes** | S–M |
| S14 | Docs, the driver template, ADR 0008 accepted | no | S |
| P1–P3 | Optional protections, only with the owner's yes | **yes** | S each |

S9 and S10 can move earlier if that helps: S9 needs only S2, S10 needs S9.

---

## S0 — Pin today's bytes

Tests only. Nothing moves.

- **Files.** New: `core/src/test/java/org/openpump/pump/Transcript.java` (reads the
  transcript format in the design, 4.3); `core/src/test/java/org/openpump/pump/WireGoldenTest.java`;
  `core/src/test/resources/transcripts/zd21/encoder/*.txt`, one per frame shape in the
  design's table 1.3 — the routine upload, the three equal-setpoint holds (standardisation,
  guided start, seal check), the assessment pull, the override (delete, add, start), start,
  stop, list, and the validation's compaction probe; `…/decoder/*.txt` with the telemetry,
  ack and dump shapes from `docs/protocols/zd21.md` and `SimPump`.
- **Tests.** `WireGoldenTest`: `Proto` produces every encoder golden exactly; `Proto.parse`
  and `Validate.parseDump` read every decoder golden as recorded.
- **Also.** Record the journal-parity "before" set on an emulator and keep it with the
  pull request.
- **Could regress.** Nothing.
- **Size.** S.

## S1 — The interface, the values and the ZD21 driver, not yet wired

- **Files.** New in `core/src/main/java/org/openpump/pump/`: `PumpDriver`, `DriverEvents`,
  `Transport`, `Cycle`, `Reading`, `Capabilities`, `Identity`, `Advert`, `LinkProfile`,
  `Clock`, `Scheduler`, and a first `SafetySupervisor` holding only the `Permit` class.
  New `core/src/main/java/org/openpump/pump/zd21/Zd21Driver.java`, which calls `Proto` for
  every byte. `.github/CODEOWNERS`: add `/core/src/main/java/org/openpump/pump/`.
- **Tests.** `Zd21DriverTest`: every command reproduces the S0 encoder goldens; every
  decoder golden becomes the right `Reading` (0.0 → `measured = false`; `-189` → 18.9 kPa);
  `Cycle.of`'s argument order is pinned against `Proto.addPreset`'s bytes;
  `Capabilities.validate()` accepts the ZD21's declaration and refuses broken ones.
  `ArchitectureTest`: no source outside the pump folder declares a `org.openpump.pump`
  package.
- **Could regress.** Nothing reachable: no app code calls it.
- **Size.** M.
- **Safety review.** No — nothing is wired. The package is code-owned from this step, so
  every later change to it is.

## S2 — The transport comes out of `PumpLink`

- **Files.** New `app/…/BleTransport.java`: `PumpLink`'s scan, connect, discover,
  subscribe, write queue and dispatch, moved verbatim, with the UUIDs and names taken from
  `Zd21Driver.linkProfile()` and `matches()`. New `core/…/pump/SimTransport.java`:
  `PumpLink`'s practice loop (`simTick`), wrapping `SimPump` unchanged. `PumpLink` shrinks
  to a façade with its current public methods (`tx`, `isReady`, `start`, `close`,
  `connect`, `disconnect`, `setSimulated`, `setKnownAddresses`, …), so `SessionActivity`
  does not change. A `LinkState` enum is added beside the state strings; `Conn` keeps
  reading the strings until S12.
- **Tests.** `WiringCheck`: the listener-dispatch rule (`LISTENER_DISPATCH_FILE`,
  `WiringCheck.java:1085`) reads `BleTransport.java` and must still count at least one
  posted dispatch. New rule D2 (only `BleTransport` and the listed `MainActivity` touch
  the GATT write and connect calls; the console is in `app/src/debug`, which the flat
  source sync reads), with a failing case in `WiringCheck`'s self-tests.
  JUnit for `SimTransport`: a written frame is answered only on the next tick, never as a
  return value.
- **Could regress.** The connect sequence (each detail at `PumpLink.java:22-31` once cost a
  build), the write queue, threading.
- **Caught by.** The diff is a move, reviewed as one. Journal parity. On the owner's phone
  before merge: release checks H1 (scan, connect, reconnect) and H10 (STOP vents,
  confirmed).
- **Size.** M (mostly moved lines).
- **Safety review.** **Yes** — Bluetooth handling, and the stop frame now passes through
  moved code.

## S3 — Every write except the stop goes through a pass-through supervisor

- **Files.** `SafetySupervisor` gains `clearTable`, `append`, `deleteAt`, `start`,
  `readTable` — forwarding to the driver with the `Permit`, journaling each command, no
  checks yet. `SessionActivity`: the 23 non-stop `link.tx(Proto.…)` sites become supervisor
  calls in the same order with the same labels (`Proto.addPreset(a, b, c, d, e)` becomes
  `pump.append(Cycle.of(a, b, c, d, e), …)`). The journal calls in `onSent` and `onFrame`
  move into the supervisor's `PumpLog`.
- **Tests.** `WiringCheck` needles change, the lists do not: `ARMING_COMMANDS`
  (`WiringCheck.java:481`) and `TABLE_WRITES` (`:874`) name the supervisor calls instead of
  `Proto.startSlot(` / `Proto.addPreset(` / `Proto.deleteSlot(`; `ARMING_SITES` (`:508`)
  and every other site list stays word for word, and must find every site it found before.
  Invariant 4 (the literal frame at every transmit) is replaced by D1: no Android source
  builds a frame, except the listed `MainActivity` (debug builds only). JUnit: the
  supervisor passes each cycle through unchanged (encoder goldens).
- **Could regress.** A mistyped argument; a dropped or reordered write — order matters,
  because Delete compacts and Add appends.
- **Caught by.** The replacement is textual and keeps argument order. Journal parity must
  show identical `CMD` lines. On hardware: H5 (the commanded band matches the log) and H6
  (an adjustment reaches the pump).
- **Size.** M.
- **Safety review.** **Yes** — every command that sets pressure.

## S4 — The stop path goes through the supervisor

- **Files.** `SafetySupervisor.sendStop(why)` (returns whether the transport took it, as
  `link.tx` does today) for `VentWatcher.attempt`; `emergencyStop(why)` for `CrashGuard`
  and `onDestroy`, which catches everything and never throws.
- **Tests.** Invariant 3's needle (`WiringCheck.java:2804`) changes from `Proto.stop(` to
  the two supervisor calls; the sanctioned places stay the same three (the `VentWatcher`
  body, `CrashGuard`, `onDestroy`). New rule D6: nothing between the start of the
  supervisor's stop methods and the driver's `stopAndVent` can return, throw, gate or
  limit. JUnit: `emergencyStop` with a broken driver and a broken transport does not throw.
- **Could regress.** A stop that is not sent.
- **Caught by.** D6. Journal parity (every `CMD stop` line where it was). H10 on hardware.
- **Size.** S.
- **Safety review.** **Yes** — the stop path.

## S5 — The ceiling clamp in the supervisor

- **Files.** The supervisor clamps each `Cycle` before the driver sees it: upper ≤
  min(ceiling, `pressureMaxKpa`), lower ≤ upper. It reads the ceiling through
  `CeilingSource` (backed by `model.ceilKpa`) at each write. It writes a `SUP clamp` line
  whenever the clamp changes a value. The app's own clamps stay.
- **Tests.** JUnit: random cycles against every ceiling from 7 to 57 — nothing that reaches
  the simulator is above the ceiling. The clamp changes nothing in any encoder golden,
  including the three equal-setpoint holds, which it must not turn into cycles.
  `JournalWatch` learns a finding for a `SUP clamp` line: a clamp that fires means an
  upstream clamp missed. `WiringCheck` rule D5: every supervisor method that forwards a
  cycle calls the clamp first, unconditionally.
- **Could regress.** A clamp that changes a legitimate value.
- **Caught by.** The no-change test on the goldens; journal parity. H9 on hardware (a low
  ceiling, pushed with ±).
- **Size.** S–M.
- **Safety review.** **Yes** — commanded pressure. After this step the two holds that
  relied on `clampAll` alone (`sendHoldPreset`, `beginSealCheck`) are clamped at write time
  too.

## S6 — The vent watch moves into the supervisor

- **Files.** New `core/…/pump/VentWatch.java`: `SessionActivity`'s `VentWatcher`, the
  tick, the retry and the non-dialog half of the give-up (`SessionActivity.java:8998-9300`,
  `9649-9680`), moved with their constants as they are (window 6 s, poll 300 ms, retry
  2 s, 3 attempts, freshness 600 ms). The vent rate comes from the driver's capabilities
  (4.68 for the ZD21). The supervisor gains `stop`, `rest`, `ventSeenByUser` and
  `standDownKeepingEvidence`. In the app, `startVentWatch`, `cancelVentWatch` and
  `stopVentPollingKeepingEvidence` become short calls into the supervisor; the update
  classes (`SessionVentUpdate`, `LinkLossVentUpdate`, …) and the dialogs stay.
- **Tests.** `WiringCheck` invariants 1 (the fresh baseline), 5 (every settling call is
  categorised) and 6 (the polling primitive's two callers) are re-pointed at the pump
  package. They fail closed until then (`WiringCheck.java:1530, 1536`) — that is the net.
  `SANCTIONED_CANCEL_SITES` keeps its names. JUnit with a manual clock and `SimTransport`:
  a normal vent is confirmed; a pump that ignores the stop gets three attempts, then the
  watch stands down with the question open; a stream of no-measurement is inferred vented
  only when `Session`'s rules allow; a stale reading is never the baseline; a new arm
  settles the watch; a retry never fires into a phase armed after it — each replaying a
  bug the code's comments record.
- **Could regress.** Timer order and timing (a `Handler` becomes a `Scheduler`); a retry
  firing into a new phase.
- **Caught by.** The JUnit above; journal parity; H10 and H3 (link loss mid-run) on
  hardware before merge.
- **Size.** L.
- **Safety review.** **Yes** — the stop/vent path.

## S7 — One link watchdog, the auto-stop and the 2-hour cap in the supervisor

- **Files.** The supervisor keeps the time of the last reading itself. Whenever the pump
  may be under pressure (armed since the last confirmed vent, or a stop still open) it
  reports "silent" at 5 s and stops through its vent watch at 6 s. It counts gross sealed
  time from readings, with the rule `Session.netGrossTupSec` uses for gross, and stops at
  `Plan.GROSS_CAP_SEC`; the app keeps showing today's message. The app's own tickers
  (`tickRun`'s lost branch, `HoldLinkTick`, `tickSealLink`, `tickGuidedLink`,
  `tickAssessmentLink`, the validation and self-test tickers) stay as the screens' alarms;
  the supervisor's watchdog is the backstop no phase can forget. `checkGrossCap` becomes a
  reader of the supervisor's figure.
- **Tests.** JUnit: silence while armed → silent at 5 s, stop at 6 s, retried; silence while
  idle → nothing; the cap stops once and latches; the app's auto-stop and the supervisor's
  arriving together make one vent watch, not two. `WiringCheck` invariants 7 and 9 stay as
  they are.
- **Could regress.** A double stop, or a watch started twice; a stop on a phase that holds
  deliberately while telemetry is silent (none should exist).
- **Caught by.** The "one watch" test; journal parity; H3.
- **Behaviour change.** Only as a backstop: where a phase's own ticker is missing, the
  supervisor stops the pump. More stops, never fewer.
- **Size.** M.
- **Safety review.** **Yes.**

## S8 — Episodes, the START gate and capability refusals

- **Files.** `begin(kind)`, `resume(e)`, `rest(e)`, `end(e)`; arming calls take the
  episode. `begin` asks `Handoff.startRefusal` with the supervisor's own vent terms, and
  the app's `refuseStartIfGated` shows the answer as now. Capability refusals: a slot index
  or a table past `slots`, a hold past `maxHoldS`, a read-back the pump cannot do, a speed
  outside its range — none of which the app produces today. Each refusal is a `SUP` line.
- **Tests.** JUnit for each refusal. `WiringCheck`: every arming call passes an episode;
  `resume(` appears only in named methods (`resumeAfterReconnect`, the rejoin tap). The
  `Scenarios` harness runs its eight scenarios through the supervisor as well.
- **Could regress.** A legitimate flow refused: a rest's end, the reconnect resume, the
  guided start handing over to the run, the seal check handing over without a vent.
- **Caught by.** Journal parity (a refusal leaves a `SUP` line and a missing `CMD`). The
  scripted sessions cover each hand-over.
- **Behaviour change.** Only with the owner's yes to decision 6: every kind of phase asks
  the gate (today the measurement hold does not); after a stop, arming waits for the
  person's resume; nothing arms on a silent link. Without that yes, `begin` answers only
  where the gate is asked today, and the new refusals are journaled but not enforced.
- **Size.** M–L.
- **Safety review.** **Yes.**

## S9 — `SimPump` becomes the ZD21 driver's simulator

- **Files.** New `core/…/pump/SimDevice.java` (bytes in, bytes out, ticked). `SimPump`
  moves to `core/…/pump/zd21/Zd21Sim.java` and implements it, its behaviour unchanged.
  `SimTransport` takes any `SimDevice`. Practice mode is the ZD21 driver over
  `SimTransport(Zd21Sim)`, through the same supervisor. `JournalWatch.DROP_KPA_PER_S` and
  the other borrowed constants follow the move. `.github/CODEOWNERS` updated.
- **Tests.** Every test that uses `SimPump` passes against `Zd21Sim` (`SelfTest`,
  `Scenarios`, `AtPressureTimingTest`, …). JUnit: a simulated link's `Identity` says
  simulated, and the journal header says `pump=sim`, as today.
- **Could regress.** Practice mode mistaken for a pump (SAFETY.md rule 6).
- **Caught by.** The identity test; the practice banner and `Sess.sim` are unchanged; the
  existing suites.
- **Size.** S–M, mostly a move.
- **Safety review.** **Yes**, light — rule 6.

## S10 — The conformance kit

- **Files.** New `core/src/test/java/org/openpump/pump/DriverConformance.java` (abstract;
  every test method `final`), `FaultySim.java` (a test-only wrapper that ignores a stop,
  goes silent, drops acks or sends garbage on cue), a set of deliberately broken drivers,
  and `pump/zd21/Zd21ConformanceTest.java`. `.github/CODEOWNERS` adds the kit.
- **Tests.** The list in the design, section 4.2. The kit must fail every broken driver.
  `ArchitectureTest`: every driver has a conformance test.
- **Could regress.** Nothing in the app.
- **Size.** M.
- **Safety review.** No behaviour change, but the kit is code-owned: weakening it weakens
  safety.

## S11 — Golden wire transcripts from the real ZD21

- **Needs.** Captures from the owner's pump (decision 11): connect and subscribe; a
  routine upload and start; a long hold; a live adjustment; STOP from pressure through to
  no measurement; a table read-back; five minutes idle.
- **Files.** A converter from the Android HCI snoop log to the transcript format, in the
  test sources (plain Java, reads the btsnoop file format). Scrubbed transcripts in
  `core/src/test/resources/transcripts/zd21/hardware/`.
- **Tests.** The ZD21 driver decodes every recorded pump frame to the recorded meaning.
  `Zd21Sim` agrees with the hardware within stated tolerances: vent rate, reading period,
  coast rate. The coast figure closes release check H14.
- **Could regress.** Nothing in the app. A tolerance that fails is a finding about the
  simulator, to be fixed in its own reviewed step.
- **Size.** M.
- **Safety review.** No. The owner reads each transcript for identifiers before it is
  committed.

## S12 — ZD21 facts leave the session engine

In parts, one class or two per pull request.

- **Files.** `Model`, `RxBuild`, `RunEdit`, `HwTest`, `Validate`, `CycleGraph` and
  `JournalWatch` take slots, the longest hold, the vent rate and the reading period from
  `Capabilities` — the ZD21's when no pump is connected (planning), the connected driver's
  otherwise — instead of `Proto.SLOTS`, `Proto.WIRE_HOLD_MAX`,
  `SessionActivity.VENT_RATE` and `Tau.TELEMETRY_INTERVAL_MS`. `Conn.classify` reads
  `LinkState`, and `"no FFF0"` goes. The journal's frame decode becomes
  `driver.describe()`, with the same words for the ZD21. The hardware validation's ZD21
  probes (the compaction test, the dump read-back) become the ZD21 driver's own hardware
  checks.
- **Tests.** `SelfTest`, `Scenarios` and `Migrate` unchanged and green — the ZD21's numbers
  are the same numbers. `JournalCheck` replays the sample journal identically.
- **Could regress.** A plan built with different limits: stitched holds, ramp steps, batch
  sizes.
- **Caught by.** The plan assertions in `SelfTest` (about 35,700); `Migrate`; journal
  parity.
- **Size.** L in total.
- **Safety review.** **Yes** — plan building decides commanded pressure and timing.

## S13 — The driver catalog and the experimental switch

- **Files.** `core/…/pump/DriverCatalog.java`: the compiled-in list (ADR 0005), today one
  entry, the ZD21, `VERIFIED`. The scan asks the catalog's visible drivers. **Settings ›
  Device & developer › Experimental pump drivers**, behind the developer unlock, off by
  default. The connect screen, the pump chip and the journal header name the driver and
  its status.
- **Tests.** JUnit: every catalog entry has a protocol doc, a simulator and a conformance
  test; no two entries match the same advert; an experimental driver is invisible while
  the switch is off.
- **Could regress.** Nothing for the ZD21 — the only driver, and verified.
- **Size.** S–M.
- **Safety review.** **Yes** — it decides which pumps can be commanded.

## S14 — Docs, the driver template, ADR 0008 accepted

- **Files.** `docs/adding-a-pump.md`'s "after the seam" steps become current; a skeleton
  driver at `core/…/pump/template/` that passes nothing until filled in; `ARCHITECTURE.md`
  and `README.md` updated; ADR 0008's status becomes "accepted".
- **Size.** S.
- **Safety review.** No.

---

## Optional protections

Each is new behaviour, so each waits for the owner's yes (the design's section 7). Each is
small and sits on top of S7 or S8.

| Step | What | Decision | Tests |
|---|---|---|---|
| P1 | Stop on readings above the ceiling in every run — the hardware validation's rule (ceiling + 2 kPa, `Validate.java:453`), after several real readings in a row | 4 | JUnit with the simulator overshooting; journal parity shows no stop on healthy runs |
| P2 | A wall-clock backstop for the 2-hour cap: time the supervisor cannot show the pump vented, "not measuring" counted as sealed | 5 | JUnit with a pump that goes quiet under pressure |
| P3 | A limit on arming commands per second, sized from real journals; never on stops | 7 | JUnit with a runaway loop; journal parity shows no refusals on today's traffic |

All three: **safety review**, size S.

---

## After the plan

- Phase 2's module split: `org.openpump.pump` → a `pump/api` module and
  `org.openpump.pump.zd21` → `pump/zd21`, as a move (decision 2).
- Phase 4 (splitting `SessionActivity`) retires the app's per-phase tickers one at a time,
  now that the supervisor's watchdog covers every phase.
- A second driver, from a pump owner's protocol doc, hidden until its hardware checklist
  is signed off (ADR 0006).
