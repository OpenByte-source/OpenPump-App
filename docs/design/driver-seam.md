# The driver seam

Status: **proposed** ([ADR 0008](../adr/0008-driver-seam.md)). The plan, step by step:
[driver-seam-plan.md](driver-seam-plan.md).

This is roadmap phase 3 in [ARCHITECTURE.md](../../ARCHITECTURE.md): one interface between
the app and any pump, one safety supervisor that every command passes through, and a test
kit that every driver must pass. When it is done, adding a pump means writing one driver —
a codec, a simulator of the pump and a protocol doc — and nothing in the screens or the
session engine changes. Today there is one pump, the Epic Hydro PE Pump, which this
document and the code call the ZD21.

Nothing here changes what the app does. The plan moves code in small steps, each with the
whole suite green. Every step that touches commanded pressure or the stop path needs the
owner's safety review ([SAFETY.md](../../SAFETY.md)). The few places where the supervisor
would do *more* than the app does today are listed separately, as decisions for the owner
(section 7).

Line numbers below are from commit `4c10258`. They will drift; the method names will not.

---

## 1. Today

### 1.1 The layers

```
screens — SessionActivity, RunScreen, MeasureScreens …
   │  26 link.tx(Proto.…) calls, all in SessionActivity.
   │  RunScreen and MeasureScreens only call SessionActivity methods.
   ▼
PumpLink (app) — scan, connect, write queue, the simulator switch; ZD21 UUIDs and names
   ▼
Proto (core) — ZD21 frames and telemetry         SimPump (core) — a ZD21 made of arithmetic
```

The safety machinery lives in `SessionActivity`: the vent watch, the START gate, the link
watchdogs, the 2-hour cap and the last-resort stops. The pure decisions behind them are
already in `core` (`Session`, `Handoff`, `Validate`, `Plan`). `WiringCheck` holds the
Android code to them.

### 1.2 Everything that talks to the pump

| # | Where | What it does | What it assumes about the ZD21 |
|---|---|---|---|
| 1 | `PumpLink.java:34-39` | UUIDs and the name filter | service `fff0`, write `fff1`, notify `fff4`; names `ZD21_PUMP` and `ZD21_PUMP_OTA` |
| 2 | `PumpLink.java:361-526` | scan, collect sightings, pick one (`PumpMatch`) | the advertised name is what identifies a pump |
| 3 | `PumpLink.java:540-683` | connect, discover, subscribe | notifications preferred, indications as fallback (`:648-653`) |
| 4 | `PumpLink.java:685-698` | parse every notification with `Proto.parse`, on the Bluetooth thread, then post | a notification is ZD21 telemetry or "some other frame" |
| 5 | `PumpLink.java:239-275, 706-829` | the write queue: one frame in flight, 400 ms timeout, 3 refusals | nothing — this part is already pump-neutral |
| 6 | `PumpLink.java:102-171, 720-731` | practice mode: `SimPump` answers instead of a radio | the simulator speaks ZD21 bytes |
| 7 | `SessionActivity.java:87, 169, 671` | owns the one `PumpLink`, implements its `Listener` | |
| 8 | `SessionActivity.java:25866-25875`, `4173`, `31409`, `33553` | start or restart the link | |
| 9 | 26 `link.tx(Proto.…)` sites in `SessionActivity` | every command — see 1.3 | table, slots, frames |
| 10 | `SessionActivity.java:26188-26270` | `onSent` / `onFrame` / `onSample`: journal, START-ack pairing, the validation dump, telemetry fanned out to about ten consumers | `Proto.isAck(raw, Proto.OP_START)` (`:26198`) |
| 11 | `RunScreen.java:1715, 1819, 1841, 1845, 1903, 1931, 1955-1957` | adjust, rewrite the rest of the table, skip, extend, hold, insert a rest | only through `SessionActivity` methods |
| 12 | `MeasureScreens.java:956, 974` | `link.isReady()`, then `startStd` | |
| 13 | `RunService.java:46-60, 88-99` | never touches the link, on purpose: one owner of the connection | |
| 14 | `app/src/debug/…/MainActivity.java:76, 103, 224, 710` | the diagnostic console, **in debug builds only** (decision 9): its **own** GATT connection and raw frames, behind the developer unlock (`SettingsScreen.java:1012-1065`, drawn only when `BuildConfig.DEBUG`); reached through `handOffToDiagnostics` (`SessionActivity.java:32189`), which refuses in a release build and otherwise asks `stillUnsafe()` first | everything, hard-coded |

### 1.3 Every write, by the method that makes it

| Method | Lines | Frames | Where the pressure comes from, and its clamp |
|---|---|---|---|
| `VentWatcher.attempt` | 9030 | stop | — |
| `sendStartSlot` — the one START | 9646 | start *n* | — |
| `sendHoldPreset` — the standardisation hold | 9819-9821 | 9 × delete 0, add, then start 0 | `std.kpa`, clamped at the write: `RunEdit.clampUpper(std.kpa, ceilKpa)`; also bounded by `Model.clampAll` (`Model.java:8913`) |
| `uploadBatch` — the routine | 11485-11497 | 9 × delete 0, up to 8 adds | `RunEdit.clampUpper(p.up, ceilKpa)` at the write, lower strictly below upper (`RunEdit.clampLower`) |
| `armAssessmentPull` | 12173-12174 | 9 × delete 0, add | `Tau.commandedKpa(r, ceil)` (`:12161`) |
| `sendGuidedStartPull` | 12835-12840 | 9 × delete 0, add | `PreRunHold.guidedTargetKpa(ceil)` = min(17 kPa, ceiling), through `RunEdit.clampUpper` at the write; the wait that starts the routine tests the same value (`WiringCheck` invariant 127) |
| `beginSealCheck` | 12933-12937 | 9 × delete 0, add | `sealCheckKpa`, clamped at the write: `RunEdit.clampUpper(sealCheckKpa, ceilKpa)`; also bounded by `Model.clampAll` (`Model.java:8948`) |
| `armPhase` — hardware validation, self-test pre-flight | 13513-13552 | deletes, adds, delete of one slot, list | `Validate.planPresets(ceil)`; probes the ZD21's compaction and its table dump |
| `sendOverridePreset` — every live adjustment, the hold, the carry, the reconnect re-assert, extend | 21157-21165 | delete the override slot, add, then `sendStartSlot` | `RunEdit.clampUpper` / `clampLower` (`:21163-21164`) |
| `CrashGuard` | 33189 | stop, last resort | — |
| `onDestroy` | 34692 | stop, last resort | — |

Every write that carries a pressure now clamps it to the ceiling where it is written.
When this review began, the standardisation hold and the seal check relied on
`Model.clampAll` alone — safe, since it runs on every load and every settings change, but
against the codebase's own rule, "clamp at write time" (the comment at
`Model.java:8875-8882`). Both now clamp at the send, and `WiringCheck` invariant 62 fails the
build if either pressure of any `Proto.addPreset` is not a clamp call, or a local assigned
from one, in the same method. (The clamp cells above are current; the line numbers are
still `4c10258`'s.)

### 1.4 ZD21 facts that have leaked above the wire

| Fact | Where it has leaked |
|---|---|
| 9-slot table; Add appends, Delete compacts | `RunEdit.routineBatchSize` / `overrideSlotIndex` (`RunEdit.java:228, 249`); `sendOverridePreset` ("safe only because it is the LAST entry", `SessionActivity.java:21154`); `Model.java:557, 679`; `RxBuild.java:173, 706`; `HwTest.java:549`; `JournalWatch.java:213-219`; `Validate.java:1180` |
| A hold is one byte (255 s) | `Model.Set#stitchedHold` and its ladder (`Model.java:278-312, 541, 566, 611, 676-677`); `CycleGraph.java:131` |
| Whole kPa, 0–57 | `Proto.addPreset` (`Proto.java:68-76`); `Math.min(57, ceilKpa)` in screens (`SessionActivity.java:3023, 18149, 18465`); the ceiling's own range, 7–57 (`Model.java:8883`) |
| Speed is a 0–255 code | `Proto.speedCode`; `Validate.java:1061`; the journal's decode |
| Telemetry about 4 Hz | `Tau.TELEMETRY_INTERVAL_MS` (`Tau.java:159`); `SimPump.TELEM_PERIOD_MS`; the reasoning behind `LINK_TIMEOUT_MS` (`SessionActivity.java:92-97`) |
| 0.0 means no measurement, and a vented ZD21 reports it | `Proto.Sample.noReading` (`Proto.java:101-107`); the inferred-vent rules (`Session.java:226-264, 400-480`) |
| Stop vents at 4.68 kPa/s | `SessionActivity.VENT_RATE` (`:90`) → `Session.ventResult`; `SimPump.VENT_KPA_PER_S`; `Validate` keeps a note about the two copies (`Validate.java:60`) |
| An ack is `<opcode> 01`; there is no NAK | START-ack pairing (`SessionActivity.java:26198`); `Journal.frame` (`Journal.java:163-176`); `JournalWatch`'s owed acks |
| The table dump has no count byte | `Validate.parseDump` (`Validate.java:1196`); `JournalWatch.java:293` |
| Stop is opcode `0x2D` | `WiringCheck` invariant 3 keys on the text `Proto.stop(` (`WiringCheck.java:2804`) |
| Link states are PumpLink's words | `Conn.classify` matches strings, including `"no FFF0"` (`Conn.java:138`) |
| Journal commands are ZD21 frames, decoded | `Journal.java:488-512`; `JournalWatch` reads the words back (`JournalWatch.java:558-563`) |

### 1.5 Where the safety rules live today

| Rule (SAFETY.md) | Where it lives | Held by |
|---|---|---|
| 1. The ceiling holds | the write-time clamps in 1.3 — every preset write, the measurement hold and the seal check included; `Model.clampAll`; `Proto`'s 0–57; the screens' bounds. A reading above the ceiling stops only the hardware validation (`Validate.java:453-460`) | `WiringCheck` invariants 12 (the closed list of arming methods, `WiringCheck.java:508`) and 62 (every preset write clamped where it is written); review; release check Z7 |
| 2. Stop means vent, confirmed by telemetry | `VentWatcher` (`SessionActivity.java:8998-9120`) → `Session.ventResult` (`Session.java:315-372`); `startVentWatch` (`:9156`); window 6 s from the write, poll 300 ms, 2 s between an unconfirmed window and the next attempt, 3 attempts then ask (`:123-135`, `:9649-9680`, `:9247`) | `WiringCheck` invariants 1, 3, 5, 6 |
| 3. No start on an unconfirmed stop | `startGate` / `refuseStartIfGated` (`:9512-9537`) → `Handoff.startRefusal` (`Handoff.java:270`), asked at three entries (`:8192`, `:33762`, `:34285`) and by the measurement hold: `startStd` asks `refuseStartIfGated()` before it shows or arms anything. The one hold that does not is the end-of-routine hold `finishSession` arms, which keeps the pressure the run already has. Validation and the self-test ask `stillUnsafe()` (`:13327`, `:14247`) | review; `WiringCheck` invariant 63 for the measurement hold |
| 4. A lost link is dangerous | `tickRun`: lost screen at 5 s of silence, auto-stop through the vent watch 6 s after that, about 11 s after the last frame (`:16323-16394`); separate watchdogs for the other phases: `HoldLinkTick` (`:9720`), `tickSealLink` (`:12241`), `tickGuidedLink` (`:12268`), `tickAssessmentLink` (`:12285`), and the validation and self-test tickers | `WiringCheck` invariants 7, 9, 11 |
| 5. A run survives the screen | `RunService`, a lifecycle shell that never touches the pump | invariant 8 and the run-handle rules |
| 6. Practice is never mistaken for a pump | `PumpLink.SIM_ADDRESS`, the banner, `Sess.sim` | review |
| The 2-hour cap | `checkGrossCap` (`:16266`), on gross sealed time from telemetry, `Plan.GROSS_CAP_SEC` (`Plan.java:199`), then `finishSession(true)` | review |
| Last-resort stops | `CrashGuard` (`:33179-33190`), `onDestroy` (`:34690-34696`) | invariant 3's sanctioned sites |
| A rest is vented | `tickRestVent` (`:16532`) re-issues the stop through the watch | the rest-branch rule in `WiringCheck` |

SAFETY.md rule 4 used to say that from 6 s the app "keeps trying to stop it — every 2 s —
until the pump's telemetry confirms the vent". The code tries **three** times
(`VENT_WATCH_MAX_ATTEMPTS`, `SessionActivity.java:130`), then stops retrying and asks the
person (`ventWatchGiveUp`, `:9247`). The comment there explains why: an endless retry on a
pump that reports no measurement when open never ends and tells nobody anything. Settled by
decision 3 in section 7: the code's behaviour stays, and SAFETY.md rule 4 now describes it.

### 1.6 Two lessons the code already records

- **The simulator belongs below the protocol.** `PumpLink.java:102-122` puts the practice
  seam *inside* the transport on purpose, so practice runs the write queue, the ack
  pairing, the vent watch and the stop retry unmodified. A second implementation beside
  `PumpLink` would have forked the vent-confirmation path. `SimPump.java:37-40` says the
  same. This design keeps that: the simulator stays at the byte level, under the driver.
  Practice mode runs the real driver and the real supervisor.
- **One owner of the connection.** `RunService.java:46-60`: two owners would mean two vent
  watches and two stop retries. This design keeps one supervisor per process, owned by
  `SessionActivity` until phase 4 moves the run state machine into `core`.

---

## 2. The design

### 2.1 Layers

```
screens and session logic (app)      ask; never build a frame
   │  begin · clearTable · append · start · stop · rest …
   ▼
SafetySupervisor (core, pure)        the only way to command a pump
   │  ceiling · capabilities · START gate · vent watch · link watchdog · 2-hour cap · journal
   │  calls the driver with a Permit
   ▼
PumpDriver (core, pure, one per pump)  commands → bytes, bytes → readings
   │  writes bytes with the same Permit
   ▼
Transport   BleTransport (app, Android)   or   SimTransport (core) + the pump's simulator
```

The rules the layers keep:

1. Only the supervisor calls a driver's commands. Only a driver writes to a transport.
   Section 2.6 says how that is enforced.
2. The supervisor knows nothing about any pump's bytes. A driver knows nothing about
   safety: it never decides whether a command should go.
3. The supervisor never refuses, delays or rate-limits a stop.
4. Nothing below the screens reads the clock or starts a thread. Time comes in through a
   `Clock` and a `Scheduler`. So the whole stack — supervisor, driver, simulated pump —
   runs in a JUnit test in milliseconds, the way `SimPump` already does.
5. Everything runs on one thread (the main thread in the app), as `PumpLink`'s listener
   dispatch already guarantees (`PumpLink.java:289-327`).
6. Named classes, no lambdas — the rule `WiringCheck` already holds the app to.

### 2.2 `PumpDriver`

A driver is a codec with a memory of what it sent. Sketch (package `org.openpump.pump`):

```java
/** One kind of pump: its bytes, and nothing about safety. */
public interface PumpDriver {

    // ---- who it is and what it can do (constant)
    Identity identity();
    Capabilities capabilities();

    // ---- finding and connecting
    /** Is this advertisement one of this driver's pumps? Pure. */
    boolean matches(Advert ad);
    /** Where to write and where to listen: service, write and notify characteristics. */
    LinkProfile linkProfile();
    /** The transport is up and subscribed. The driver keeps it to write to. */
    void attach(Transport link, DriverEvents out);
    void detach();

    // ---- from the pump
    /** One notification. Reports readings, acks and other frames through DriverEvents.
     *  Must never throw, whatever the bytes are. */
    void onBytes(byte[] frame);

    // ---- to the pump. Only the supervisor can call these: it alone can make a Permit.
    //      Each returns whether the transport accepted the frame(s) — the same meaning as
    //      today's PumpLink.tx.
    boolean clearTable(SafetySupervisor.Permit p, String why);
    boolean append(SafetySupervisor.Permit p, Cycle c, String why);
    boolean deleteAt(SafetySupervisor.Permit p, int index, String why);
    boolean start(SafetySupervisor.Permit p, int index, String why, Runnable onSent);
    boolean readTable(SafetySupervisor.Permit p, String why);
    /** Stop AND release pressure. Never a pause. */
    boolean stopAndVent(SafetySupervisor.Permit p, String why);

    // ---- for the journal
    /** A short description of a frame this driver wrote, e.g. "add spd=75 up=40 …". */
    String describe(byte[] frame);
}
```

Writing a preset is `append` then `start`. Writing a table is `clearTable` then `append`
for each entry.

**Why table-shaped.** The first interface mirrors what the app does today. That keeps the
first steps mechanical and byte-identical, which is what makes them safe to review. The
table's rules — how many slots, whether Delete compacts — are declared in `Capabilities`,
so nothing above the driver has to learn them from `Proto`. A pump with no table of its
own can still be driven: its driver keeps the table in memory and sends the chosen entry
when it is started. What the interface should look like for a pump that is *very*
different (streams setpoints, cannot cycle on its own) is better decided with that pump in
hand. The design leaves room for pump-neutral intents ("hold 18 kPa for 30 s") beside the
table calls, and does not guess them now.

`DriverEvents` is how a driver reports up:

```java
public interface DriverEvents {
    void reading(Reading r);                   // telemetry
    void ack(int command, String what);        // the pump confirmed a command (Command.START, …)
    void table(java.util.List<Cycle> entries); // a read-back, decoded
    void frame(byte[] raw);                    // anything else, kept for the journal
}
```

### 2.3 The values

**`Cycle`** — one preset, pump-neutral: speed %, upper kPa, upper hold s, lower kPa, lower
hold s. `Cycle.of(...)` takes its arguments in exactly `Proto.addPreset`'s order, so the
step that replaces `Proto.addPreset(a, b, c, d, e)` with `Cycle.of(a, b, c, d, e)` is a
textual change a reviewer can check by eye. A hold is a cycle whose lower setpoint equals
its upper one.

**`Reading`** — one telemetry sample:

```java
public final class Reading {
    /** False: the pump says it is not measuring. That is not a pressure, and never zero. */
    public final boolean measured;
    /** Vacuum in kPa, as a positive magnitude. 0.0 and meaningless when !measured. */
    public final double kpa;
    /** Speed the pump reports, in %, or -1 when it does not say. */
    public final int speedPct;
    /** The pump's own word for its mode, for the journal only. */
    public final String mode;
}
```

- The unit is always kPa of vacuum, positive. The driver converts. The ZD21 sends
  negative deci-kPa; `Proto.parse` already takes the magnitude (`Proto.java:127-136`).
- **No measurement is explicit.** The driver decides what counts as "not measuring" for
  its pump (for the ZD21, a reading of exactly 0.0) and says so with `measured = false`.
  Nothing above the driver may treat `kpa` as a pressure without checking `measured`
  first. This is the rule `Proto.Sample.noReading` carries today, with the decision kept
  where the pump's own convention is known. `kpa` stays 0.0 rather than NaN when not
  measured, because `Session`'s tested functions take parallel `kpa[]` / `noReading[]`
  arrays and must keep working unchanged.
- The rate is a capability (`telemetryPeriodMs`), not a property of each reading.

**`Capabilities`** — what the hardware can do. The supervisor refuses anything outside it.

| Field | Meaning | ZD21 | Used by |
|---|---|---|---|
| `slots` | entries in the pump's own table | 9 | batch size, override slot, refusals |
| `appendOnly`, `deleteCompacts` | how the table changes | yes, yes | the run sequencer's slot arithmetic |
| `canReadTable` | can the table be read back | yes (`0x29`) | hardware validation |
| `pressureMaxKpa`, `pressureStepKpa` | commandable setpoints | 57, 1 | the clamp, the ceiling's range |
| `maxHoldS` | longest hold one entry carries | 255 | stitched holds, refusals |
| `speedMinPct`, `speedMaxPct` | commandable speed; both 0 when there is no speed control | 0, 100 | refusals |
| `cyclesOnItsOwn` | keeps cycling a started entry with no help | yes | makes the link watchdog mandatory |
| `holdStyle` | `COASTS` (pulls once, then drifts down) or `REPULLS` (tops up) | `COASTS` | tolerance band, at-pressure counting |
| `stopVents` | stop releases pressure | yes | **required** |
| `ventRateKpaPerS` | measured vent rate | 4.68 | the vent watch's expected fall |
| `reportsPressure` | sends pressure readings | yes | **required** |
| `telemetryPeriodMs` | time between readings | 240 | watchdog margins |
| `pressureResolutionKpa` | smallest step in a reading | 0.1 | noise floors |
| `noMeasurementWhenVented` | a vented pump reports "not measuring" | yes | allows the *inferred* vent verdict (`Session.java:226-233`) |
| `acksCommands`, `hasNak` | how it answers | yes, no | ack pairing, the as-run recorder |

`Capabilities.validate()` refuses a driver whose declaration is incomplete or contradicts
itself — for example a vent rate of zero on a pump that vents, a pressure range above
atmospheric, or a telemetry period too slow for the 5-second link watchdog to tell a
dropped packet from a lost link.

**`Identity`** — the driver's id (`zd21`), a display name, its protocol doc
(`docs/protocols/zd21.md`), its status (`VERIFIED` or `EXPERIMENTAL`, ADR 0006), and
whether it is a simulator.

**`Advert`** — what a scan saw: the advertised name and service UUIDs. Not the address:
which pump to remember is `PumpMatch`'s business and stays there.

**`LinkProfile`** — service, write and notify characteristics, whether to write without
response, whether to prefer notifications or indications.

### 2.4 Transports

**`BleTransport`** (app) is today's `PumpLink` with the ZD21 taken out: scan, connect,
discover, subscribe, the serialised write queue, main-thread dispatch. It learns which
names to look for and which characteristics to use from the drivers in the catalog, not
from constants. Every hard-won detail of the connect sequence (`PumpLink.java:22-31`) and
the write queue (`:239-275`) moves across verbatim. It reports link states as an enum, so
`Conn.classify` stops parsing `PumpLink`'s words (`"no FFF0"`).

**`SimTransport`** (core) is `PumpLink`'s practice mode lifted out: it hands written frames
to a simulated pump and delivers what the pump answers on the next tick — "the same door
as a real one" (`PumpLink.java:725-728`). The simulated pump is a `SimDevice`: bytes in,
bytes out, ticked by the `Scheduler`. `SimPump` becomes the ZD21's `SimDevice`, unchanged
in behaviour. Every driver ships one (`docs/adding-a-pump.md` already asks for it).

Both transports refuse a write without a valid `Permit` (2.6).

### 2.5 `SafetySupervisor`

The supervisor is plain Java in `core`. It holds the driver, the transport, the clock, the
scheduler, the journal and a way to read the current ceiling. The app talks to it and to
nothing below it.

What it does, rule by rule:

| Rule | What the supervisor does | Today | New behaviour? |
|---|---|---|---|
| The ceiling | clamps every `Cycle`: upper ≤ min(ceiling, `pressureMaxKpa`), lower ≤ upper. Writes a journal line whenever the clamp changes anything. Reads the ceiling at each write, never a copy. | clamps at each arming method (1.3) | No. The app's clamps stay. The supervisor's is a second layer that never fires on today's traffic — and if it does, the journal says an upstream clamp missed. |
| Capabilities | refuses a slot index ≥ `slots`, a table longer than `slots`, a hold over `maxHoldS`, a read-back the pump cannot do, a speed outside its range. Refuses to attach a driver that fails `validate()`, or that cannot report pressure or does not vent on stop. | nothing to refuse: the app sizes everything to `Proto`'s constants | No, for the ZD21. |
| The START gate | `begin(kind)` asks `Handoff.startRefusal` with the supervisor's own vent terms, and returns a refusal the app shows as it does now | three entry points ask (1.5) | Only if the owner agrees (decision 6): every kind of phase would ask, not three. |
| Arming after a stop | after a `stop` or an auto-stop, arming is refused until the person chooses to resume (`resume(e)`). A planned rest (`rest(e)`) is not a stop: the routine's own clock may re-arm after it. | a timer could re-arm into a lost link (the bug described at `SessionActivity.java:16330-16337`, fixed there, locally) | Yes (decision 6). |
| Stop | never refused, delayed or rate-limited. Opens the one vent watch, or joins it if one is open. | `startVentWatch` | No. |
| Vent confirmation | `VentWatch`: today's `VentWatcher` and its plumbing, moved; same window, poll, retry and attempt count; verdicts from `Session.ventResult`, unchanged. The inferred verdict only when the driver declares `noMeasurementWhenVented`. | `SessionActivity.java:8998-9680` | No. |
| Link watchdog | runs whenever the pump may be under pressure — armed since the last confirmed vent, or a stop still open — whatever screen is up. Silent for 5 s: tells the app. 6 s: stops through the vent watch. | `tickRun` and a separate ticker per phase (1.5) | Only as a backstop: it adds stops where a phase forgot its ticker, never removes one. |
| The 2-hour cap | counts gross sealed time from the readings it sees, with the rule `Session` uses; at `Plan.GROSS_CAP_SEC` it stops and tells the app, which shows today's message | `checkGrossCap` in the run tick | No. A wall-clock backstop is decision 5. |
| Readings | drops a reading that is not finite or not physically possible (below 0, above atmospheric), with a journal line | `Proto.parse` drops NaN and infinity (`Proto.java:137-142`) | Hardly: more than 101.3 kPa of vacuum cannot be real. |
| Journal | writes every command (in the driver's words), ack, reading, link change and decision | `onSent`, `onFrame`, `onSample` in `SessionActivity` | Adds `SUP` lines for its decisions. |
| Over-ceiling reading | — | only the hardware validation stops on one | Decision 4. |
| Rate | — | nothing; the write queue serialises frames | Decision 7. |

The API, in outline:

```java
public final class SafetySupervisor {

    /** Proof that a command came through here. Only this class can make one. */
    public static final class Permit { private Permit() { } }

    public SafetySupervisor(PumpDriver driver, Transport link, Clock clock, Scheduler sched,
                            PumpLog journal, CeilingSource ceiling, Listener out) { … }

    // starting something: the START gate
    public Refusal mayBegin(int kind);                 // for greying out a button
    public Episode begin(int kind, String why);        // null when refused; the refusal is journaled

    // commands inside an open episode: each is clamped and checked against the capabilities
    public Result clearTable(Episode e, String why);
    public Result append(Episode e, Cycle c, String why);
    public Result deleteAt(Episode e, int index, String why);
    public Result start(Episode e, int index, String why, Runnable onSent);
    public Result readTable(Episode e, String why);
    public Result resume(Episode e, String why);       // the person chose to carry on after a stop

    // releasing pressure: never refused, never delayed
    public void rest(Episode e, String why, VentWatch.Listener l);        // planned; the episode goes on
    public void stop(String why, double targetKpa, VentWatch.Listener l); // ends every episode
    public void emergencyStop(String why);             // a dying process: send, do not watch, never throw

    // the only three ways an open stop question closes
    //   1. arming again (a new, deliberate pressure state takes over)
    //   2. ventSeenByUser(): a person saw it vent — recorded as theirs, never as telemetry's
    //   3. standDownKeepingEvidence(): stop polling, the question stays open
    public void ventSeenByUser();
    public void standDownKeepingEvidence();

    // questions the screens ask
    public String ventState();            // Session.VENT_STATE_*
    public boolean ventUnevidenced();
    public boolean mayBeUnderPressure();
    public int linkHealth();              // FRESH, SILENT (5 s), AUTO_STOPPING (6 s)
    public Reading lastReading();
    public Capabilities capabilities();
    public Identity identity();
}
```

The three ways a stop question closes are the three categories `cancelVentWatch`'s comment
spells out today (`SessionActivity.java:9551-9580`), and that `WiringCheck` invariants 5
and 6 enforce by method name. In the supervisor they are the API's shape: there is no
fourth method.

An **episode** is a token for one thing that commands the pump: a run, a manual run, the
standardisation hold, the seal check, the guided start, an assessment, the hardware
validation, the self-test. Arming needs one. The token is what lets the supervisor tell a
person's "resume" from a timer's re-arm.

**The journal.** Commands keep today's `CMD` words for the ZD21 (the driver's `describe`
produces them), so `JournalWatch` and old journals keep working. Supervisor decisions are a
new `SUP` tag: `SUP clamp up 60->40 (ceiling)`, `SUP refuse start (stop unconfirmed)`,
`SUP vent VENTED 3.4 s`, `SUP link silent 5.0 s`, `SUP autostop`. `JournalWatch` ignores
tags it does not know (`JournalWatch.java:192-200`), so old tools read new journals.

### 2.6 Why it cannot be bypassed

Four layers, because Java alone cannot close every door.

**The type system.**
- Every driver command takes a `SafetySupervisor.Permit`. Its constructor is private, so
  only `SafetySupervisor` can make one. The app can hold a driver (the catalog hands them
  out) and still cannot command it.
- `Transport.write` takes the same `Permit`, so the app cannot write bytes to the transport
  either. The transport refuses a null permit, and a permit from a different supervisor —
  which also catches the "two owners" mistake (1.6).
- `SafetySupervisor` is `final`, and its checks are private. Nothing can subclass it to
  skip them.
- The policy numbers (timeouts, windows, retries, the cap) are constants inside the
  supervisor. The app can set only the ceiling, and only through `CeilingSource`.

**`WiringCheck`** — new rules for what Java cannot express:

| Rule | What it forbids |
|---|---|
| D1 | building a frame outside the pump package: no `Proto` frame builder and no `0x66, 0x2A` literal in an Android source — except `MainActivity` (debug builds only), named in a list |
| D2 | touching the radio outside `BleTransport`: no `connectGatt(`, `writeCharacteristic(` or `writeDescriptor(` elsewhere — except `MainActivity` (debug builds only), listed |
| D3 | a second supervisor: exactly one `new SafetySupervisor(` in the Android sources; no Android source calls a driver command or `Transport.write` |
| D4 | a stored permit: no `Permit` field or local outside `SafetySupervisor.java`, and no literal `null` where a permit goes |
| D5 | a clamp that can be skipped: every supervisor method that forwards a `Cycle` calls the clamp and the capability check as unconditional top-level statements before the driver call (invariant 2's machinery, `WiringCheck.java:2763`) |
| D6 | a stop that can be refused: the supervisor's stop methods reach `stopAndVent` with no `return`, `throw`, gate, clamp or rate check before it |
| D7 | hidden time: nothing under `pump/` reads the clock or makes a thread (`currentTimeMillis`, `nanoTime`, `Thread`, `Timer`, `Executor`, `Handler`) |
| D8 | a rule that checks nothing: each rule counts what it found and fails on zero, as `WiringCheck` already does (`WiringCheck.java:1530-1572`) |

`WiringCheck` today applies most of its rules to the files with an `import android` line
(`androidSources`) and a few to every file (`allSources`). Both read only the top of the
flat source folder (`WiringCheck.java:1659-1693`), and `syncFlatSources` copies subfolders
as subfolders (`core/build.gradle.kts`). A file under `pump/` would be invisible to every
rule. So the new rules read the pump package explicitly and recursively, and fail if they
find it empty. The sync also copies `app/src/debug/java`, where the console now lives, and
`WiringCheck` fails if the console is missing from the folder.

**JUnit.** `ArchitectureTest` gains two checks: no source outside
`core/src/main/java/org/openpump/pump/` declares a `package org.openpump.pump…` (a split
package would reach package-private members), and every driver in the catalog has a
protocol doc, a simulator and a conformance test. The conformance kit's test methods are
`final`, so a driver's test class cannot switch one off by overriding it.

**Review.** `.github/CODEOWNERS` gains the pump package and the conformance kit.

**What stays trusted.** A `Permit` stops the app from commanding a driver. It cannot stop
a driver from misbehaving — a driver writes bytes, and the supervisor cannot read another
pump's bytes. That is what review and the conformance kit are for: a driver writes nothing
it was not asked to, its stop always vents, and it encodes exactly the cycle it was given
(section 4). Reflection is out of scope. `MainActivity` is a named exception (decision 9),
and it is not in the release APK.

### 2.7 Threading and time

One thread. `BleTransport` hops to the main thread before handing bytes up, as `PumpLink`
does today (`PumpLink.java:289-327`). The one change: `Proto.parse` runs on the main
thread instead of the Bluetooth thread. It is a few string operations on a 20-byte frame.

`Clock` and `Scheduler` are two small interfaces. The app backs them with
`System.currentTimeMillis()` and a `Handler`. Tests back them with a manual clock, so a
40-minute routine or a 2-hour cap runs in milliseconds and nothing is flaky.

---

## 3. What moves where

| Today | After |
|---|---|
| `PumpLink` scan, connect, write queue | `BleTransport` (app), pump-neutral |
| `PumpLink`'s UUIDs and names (`:34-39`) | `Zd21Driver.linkProfile()` and `matches()` |
| `PumpLink`'s practice mode (`:102-171`, `:720-731`) | `SimTransport` (core) |
| `Proto` | the codec inside `Zd21Driver` (`org.openpump.pump.zd21`); `Proto` stays as its implementation until the last steps, then moves beside it |
| `SimPump` | `Zd21Sim`, the ZD21's `SimDevice`, same behaviour |
| `PumpMatch` | stays; choosing among remembered pumps is not pump-specific |
| `Conn` | stays; reads an enum instead of `PumpLink`'s words |
| `VentWatcher` and the watch plumbing (`SessionActivity.java:8998-9680`) | `VentWatch`, owned by the supervisor. The dialogs stay in the app. |
| the 26 `link.tx(Proto.…)` sites | supervisor calls |
| the clamps in the arming methods | stay, as the first layer |
| `startGate` / `refuseStartIfGated` | `supervisor.begin()`; the app shows the refusal |
| link-loss detection in `tickRun` and the per-phase tickers | the supervisor's watchdog as a backstop first; the app's tickers go one at a time in phase 4 |
| `checkGrossCap` | the supervisor |
| the raw stops in `CrashGuard` and `onDestroy` | `supervisor.emergencyStop` |
| the journal calls in `onSent` / `onFrame` / `onSample` | the supervisor's `PumpLog` |
| the journal's ZD21 decode (`Journal.java:488-512`) | `driver.describe()`, same words |
| `Proto.SLOTS` / `WIRE_HOLD_MAX` in `Model`, `RxBuild`, `RunEdit`, `HwTest`, `Validate`, `CycleGraph`, `JournalWatch` | `Capabilities` |
| `VENT_RATE`, `Tau.TELEMETRY_INTERVAL_MS` | `Capabilities` |
| `Session`, `Handoff`, `Plan`, `Validate` decisions | stay where they are; the supervisor calls them |
| `RunService` | unchanged: never touches the pump |
| `MainActivity` (the console) | unchanged; a listed exception; debug builds only (`app/src/debug`) |

New files, all in `core` unless marked:

```
core/src/main/java/org/openpump/pump/        future Gradle module pump/api
    PumpDriver, DriverEvents, Transport, SimDevice, SimTransport,
    Cycle, Reading, Capabilities, Identity, Advert, LinkProfile,
    SafetySupervisor, VentWatch, Episode, Result, Refusal,
    Clock, Scheduler, PumpLog, CeilingSource, DriverCatalog
core/src/main/java/org/openpump/pump/zd21/   future Gradle module pump/zd21
    Zd21Driver, Zd21Sim
core/src/test/java/org/openpump/pump/        the conformance kit
    DriverConformance, FaultySim, Transcript
core/src/test/resources/transcripts/zd21/    golden wire transcripts
app/src/main/java/org/openpump/BleTransport.java
```

The packages map one-to-one onto the `pump/api` and `pump/zd21` modules of roadmap phase
2, so that split becomes a move (decision 2).

---

## 4. The conformance kit

### 4.1 What it is

An abstract JUnit 5 class, `DriverConformance`. A driver's test class extends it and
supplies a new driver, a new simulator of its pump, and its golden transcripts. The kit
wires them through the real `SafetySupervisor` and a `SimTransport` with a manual clock, so
every test runs in milliseconds. A test-only `FaultySim` wraps any simulator to misbehave
on cue: ignore a stop, go silent, drop acks, send garbage.

### 4.2 The tests

Each of these is a `final` test method every driver runs.

**Identity and capabilities**
- `Identity` is complete; the protocol doc it names exists.
- `Capabilities.validate()` passes. Pressure telemetry and a venting stop are present.

**Matching**
- `matches()` accepts the driver's own sample adverts and rejects every other driver's.
  No two drivers in the catalog match the same advert.

**Encoding** (the simulator is the independent judge)
- Every cycle the supervisor passes arrives in the simulator's table exactly — including
  the three equal-setpoint hold shapes the app sends (1.3).
- A table as long as `slots` fits; the supervisor refuses one entry more before the driver
  sees it.
- With any ceiling from 7 to 57 and any requested cycle, nothing the simulator receives is
  above the ceiling. (The clamp is the supervisor's; the test proves the driver does not
  undo it — for example by a unit mix-up.)
- The driver writes nothing it was not asked to: no frames after `attach` until the first
  command, and none between commands.

**Stopping**
- From every state — idle, pulling, holding, resting, in the middle of a table write — a
  stop reaches the simulator and the pressure falls at about the declared vent rate.
- The supervisor's vent watch reports `VENTED` within its window.
- A simulator that ignores the stop: the watch retries, then stands down with the
  question still open (`ventUnevidenced()` stays true).
- A lost link while armed: silent at 5 s, a stop at 6 s, retried; when the link returns,
  the stop lands and is confirmed.

**Readings**
- Every no-measurement signal the pump has becomes `measured = false`, never a pressure.
- A stream of nothing but no-measurement never confirms a vent, unless the driver
  declares `noMeasurementWhenVented` and `Session`'s inference rules are met.
- Garbage: 20,000 random frames, truncated frames, huge frames, non-numbers (`NaN`,
  `1e400`) — `onBytes` never throws and never reports a reading that is not a finite,
  possible pressure. (Release check Z1 already does this for `Proto`.)
- The reading rate is within 20 % of `telemetryPeriodMs`.

**Acks**
- Every command the pump acknowledges is paired with its ack. A command whose ack never
  comes is reported as unacknowledged, as the as-run recorder does today
  (`SessionActivity.java:20293`).

**Golden transcripts** (4.3)
- The driver encodes each recorded command sequence to exactly the recorded bytes.
- The driver decodes each recorded pump frame to the recorded reading or ack.
- The simulator's behaviour agrees with the hardware transcripts within stated
  tolerances: vent rate, reading period, coast rate.

The kit has its own tests: a set of deliberately broken drivers (a stop that pauses, a
pressure sent in the wrong unit, a parser that throws) that it must fail. An invariant
with no failing case has no evidence it works — `WiringCheck`'s own habit.

### 4.3 Golden wire transcripts

A transcript is a text file of bytes and times. It is diffable and holds no identifiers:

```
# openpump wire transcript v1
# pump=zd21 source=hardware captured=YYYY-MM-DD notes=stop from 18 kPa
# ms       dir  bytes                       note
+000000    TX   66 2A 2D                    stop
+000031    RX   2D 01                       ack stop
+000240    RX   "#AUTO,-189,179,0"          18.9 kPa, speed 70 %
+000480    RX   "#AUTO,-178,179,0"
```

(The numbers above show the format. They are not a recording.)

Three kinds:

1. **Encoder goldens** — a command sequence and the exact bytes it must produce. The first
   set is made from today's code before anything moves (plan step S0), for every frame
   shape in 1.3. They prove each migration step sends the same bytes.
2. **Decoder goldens** — real frames from a real pump and what they mean: telemetry
   (negative deci-kPa, the 0.0 no-measurement), acks, the table dump with no count byte.
3. **Behaviour goldens** — real runs: a stop from pressure (the 4.68 kPa/s vent), a long
   hold (the coast — which also answers release check H14), the reading period.

Kinds 2 and 3 must come from the real ZD21 — fresh captures from the owner's pump
(decision 11), cut down to the pump's traffic and scrubbed as the
[protocol guide](../adding-a-pump.md) describes. A small converter from Android's
Bluetooth HCI snoop log to this format lives in the test sources, so pump owners can use
it too.

### 4.4 What passing means

Passing the kit means the driver is consistent with its own simulator, its own captures
and the supervisor's rules. It does not mean the driver is safe on a person. Only the
hardware checklist on the real pump shows that (section 5).

---

## 5. ADR 0005 and 0006, applied

**0005 — drivers are compiled in.** `DriverCatalog` is a fixed list in the source. There is
no loading from files, no reflection, no plugin API. Adding a driver is a pull request
that adds a package under `pump/`, a protocol doc, a simulator, a conformance test and a
catalog line. `ArchitectureTest` checks each catalog entry has all of them. CI runs the
kit on every driver.

**0006 — unverified drivers are hidden.** Every `Identity` carries a status. A driver
marked `EXPERIMENTAL`:
- is left out of the scan unless **Settings › Device & developer › Experimental pump
  drivers** is on — behind the same developer unlock as the simulated pump and the
  diagnostics;
- is named as experimental on the connect screen, in the pump chip and in the journal
  header (`pump=<id> status=experimental`);
- becomes `VERIFIED` only by a reviewed change to its `Identity`, made after someone with
  the pump runs the hardware checklist and signs it off, with two reviews, one from a
  safety maintainer.

The hardware checklist is the release checklist's hardware rows (H1–H14 in
[release-checklist.md](../release-checklist.md)) plus the on-device hardware validation
routine (`Validate`, `HwTest`), made to read the driver's capabilities instead of
`Proto`'s constants. The ZD21 driver starts as `VERIFIED`: it is what the app has been
driving.

---

## 6. Migration risks

| Risk | How it is contained |
|---|---|
| A step changes the bytes sent | Encoder goldens for every frame shape before anything moves (S0). `Cycle.of` keeps `Proto.addPreset`'s argument order, so replacements are textual. A journal parity check — the same scripted practice sessions on an emulator, before and after, `CMD` lines compared with the times masked — the method `docs/verification.md` used for the port. |
| A `WiringCheck` rule silently stops checking after a move | Rules fail closed: they count what they find and fail on zero (`WiringCheck.java:1530-1572`). Moving `ventResult` out of the Android sources *will* fail invariant 1 until the rule is re-pointed — intended. Needles change in the same commit as the code. Each new needle gets a failing case in `WiringCheck`'s self-tests. |
| Two owners of the pump | One supervisor (D3). The transport refuses another supervisor's permit. The console handoff still asks `stillUnsafe()` first. |
| Timers behave differently on the `Scheduler` | Constants move verbatim. JUnit with a manual clock replays the bugs the comments record (a retry firing into a new phase, a stale baseline, a double heartbeat). Hardware checks H3 (link loss) and H10 (STOP confirmed) on the owner's phone before the vent-watch and watchdog steps merge. |
| The simulator stops being honest | It stays below the driver: practice runs the real driver and supervisor. Hardware transcripts tie the simulator to the real pump. |
| The supervisor refuses something the app needs (a rest's end, a resume, the seal check handing over to the run) | Refusals that change behaviour come only in the steps the owner agreed to. Each refusal is a `SUP` journal line. Journal parity shows a missing `CMD` at once. |
| The new stop path adds a way to fail | `emergencyStop` catches everything. The permit check is one comparison. D6. JUnit with a broken driver and a broken transport. |
| Drift into the `SessionActivity` split | Each step names its files. No step moves screen code. Phase 4 stays separate. |
| Old journals stop replaying | `CMD` words unchanged for the ZD21; `SUP` is a tag old readers skip. `JournalCheck` replays the sample journal in every step. |
| Saved data | No step changes the saved format. If capabilities ever bound saved routines, that step adds a `Migrate` case. |
| Parallel work in `SessionActivity` collides with the 26-site edit | The call-site steps are short-lived branches, rebased at the last moment. The edits are mechanical and can be redone. |

---

## 7. Decisions for the owner

Each is a behaviour or scope call. The plan does not take one without a yes.

1. **The simulator stays below the driver.** `SimPump` becomes the ZD21 driver's simulator
   behind `SimTransport` — not a driver of its own — so practice and the kit run the real
   driver and supervisor (1.6). *Recommended: yes.*
2. **Seam before modules.** Build the seam now as packages inside `core`; the Gradle
   module split of phase 2 follows as a move. *Recommended: yes.*
3. **SAFETY.md rule 4 or the code?** SAFETY.md says the app keeps retrying the stop every
   2 s until telemetry confirms it; the code retries three times, then asks the person.
   *Recommended: the supervisor encodes the code's bounded retry and the ask; SAFETY.md is
   reworded to match. Separately, consider sending one stop straight away whenever the link
   comes back with a stop still unconfirmed.*
4. **Stop on a reading above the ceiling, in every run.** Today only the hardware
   validation does (ceiling + 2 kPa, `Validate.java:453`). *Recommended: yes — same margin,
   and only after several real readings in a row, so an overshoot blip does not end a
   session.*
5. **A wall-clock backstop for the 2-hour cap.** The cap counts sealed time from readings,
   so a pump that stops reporting pressure while still under it never reaches the cap.
   *Recommended: yes — count time the supervisor cannot show the pump vented, with "not
   measuring" counted as sealed.*
6. **The START gate for every phase, and no arming on a silent link.** When this was
   written three entry points asked the gate and the measurement hold did not; the
   measurement hold now asks it too (1.5). After a stop, arming would wait for
   the person's resume (rests excepted), and nothing would arm without fresh telemetry.
   *Recommended: yes.*
7. **A limit on how often the pump can be armed**, sized from real journals so today's
   traffic never meets it. Never on stops. *Recommended: yes, as a guard against a runaway
   loop.*
8. **Pumps that cannot report pressure, or whose stop does not vent.** *Recommended: the
   supervisor refuses them. Revisit only with a real pump and a safety review.*
9. **The diagnostic console** (`MainActivity`) keeps its own connection and raw frames,
   outside every check. *Recommended: keep it as a named exception during the seam, behind
   the developer unlock; retire it once the protocol guide and nRF Connect cover what it
   was for.* *Decided: out of the downloadable app. Done — it lives in `app/src/debug`, so
   the release APK has neither the class nor its manifest entry, and Settings shows its
   door only in a debug build.*
10. **`ZD21_PUMP_OTA`.** The name filter accepts it (`PumpLink.java:39`). This review first read it as a
    firmware-update mode; nothing OpenPump sends or receives supports that, and what makes a pump
    advertise the name is unknown ([zd21.md](../protocols/zd21.md)). *Decided: keep accepting both
    names and treat them the same.*
11. **Where the hardware transcripts come from.** *Recommended: fresh captures from the
    owner's pump with the 0.9.0 build, scrubbed, and read by the owner before they are
    committed — not material from the private history.*

### Owner's answers (2026-09-24)

| # | Question | Decision |
|---|---|---|
| 1 | SimPump as a driver or underneath the ZD21 driver | **Underneath** the ZD21 driver, so tests run the real driver code. |
| 2 | Packages in `core` now, a module later | **Packages in `core` now**; the module split later, as a file move. |
| 3 | Stop retries: SAFETY.md vs the code | **Keep the code's behaviour** (three tries, then ask the person); reword SAFETY.md to match. |
| 4 | Stop the pump on readings above the ceiling in every run | **No.** Behaviour unchanged. |
| 5 | A wall-clock backstop for the 2-hour cap | **Yes, after a careful study** of how it behaves in every run shape (rests, holds, lost link, the after-test) before it's built. |
| 6 | START gate on every phase; re-arm only on resume; never arm on a silent link | **No.** Behaviour unchanged. |
| 7 | Rate-limit arming | **No.** Behaviour unchanged. |
| 8 | Refuse pumps that can't report pressure or whose stop doesn't vent | **No.** Behaviour unchanged. |
| 9 | The diagnostic console | **Out of the downloadable app.** It stays in builds developers make themselves. |
| 10 | `ZD21_PUMP_OTA` | **No change.** Keep accepting both names and treat them the same (see question 10 above). |
| 11 | Golden transcripts | **Yes:** fresh captures from the owner's pump with 0.9.0, scrubbed of anything identifying, read by the owner before they are committed. |

---

## 8. Not in scope

- Loading drivers at run time (ADR 0005 rules it out).
- Splitting `SessionActivity` (phase 4) or moving to Kotlin Multiplatform (phase 5, ADR
  0004). The seam is what both of those build on.
- Changing any commanded pressure, timing or stop behaviour, except the decisions above,
  each as its own reviewed step.
- A second driver. The seam should be proven with the ZD21 and its simulator first.
