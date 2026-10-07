# Epic Hydro PE Pump (ZD21): how OpenPump talks to it

This guide explains how the OpenPump Android app controls the Epic Hydro PE Pump over Bluetooth
Low Energy: which bytes it sends, what the pump sends back, and what the pump does in between. It
is written for a developer who wants to drive the same pump from their own app.

You don't need the OpenPump source to follow it. Where a detail is OpenPump's own design choice
rather than something the pump requires, the text says so. The pump advertises itself as
`ZD21_PUMP`, and the protocol takes its name from that: ZD21. A one-page summary of the protocol
is in [protocols/zd21.md](protocols/zd21.md).

How the trainer decides what a routine should be is in its companion, the
[trainer guide](trainer-guide.md).

## Contents

- [1. The pump in one page](#1-the-pump-in-one-page)
- [2. Bluetooth](#2-bluetooth)
- [3. Command frames](#3-command-frames)
- [4. The preset table](#4-the-preset-table)
- [5. Telemetry](#5-telemetry)
- [6. From a routine to pump commands](#6-from-a-routine-to-pump-commands)
- [7. Changing a run while it plays](#7-changing-a-run-while-it-plays)
- [8. Manual runs and trying a set](#8-manual-runs-and-trying-a-set)
- [9. Guided start, holds and measurements](#9-guided-start-holds-and-measurements)
- [10. Stopping and venting](#10-stopping-and-venting)
- [11. Limits OpenPump enforces](#11-limits-openpump-enforces)
- [12. Timing](#12-timing)
- [13. A worked example](#13-a-worked-example)
- [14. Still to be confirmed](#14-still-to-be-confirmed)
- [15. Licence](#15-licence)
- [Appendix A. The simulated pump](#appendix-a-the-simulated-pump)
- [Appendix B. Constants at a glance](#appendix-b-constants-at-a-glance)
- [Appendix C. Where it lives in OpenPump](#appendix-c-where-it-lives-in-openpump)

---

## 1. The pump in one page

### How it works

The pump keeps a small table of up to nine **presets**. A preset describes one cycle: pull to an
upper pressure, hold there, drop to a lower pressure, hold there, and repeat. Your app writes
presets into the table, then tells the pump which one to run.

Once started, the pump runs that preset on its own, cycle after cycle, for as long as it is left
alone. It never moves on to another preset by itself, and it never stops by itself. Your app keeps
the clock. When a step's time is up, the app starts the next preset; when the session is over, it
sends STOP. It follows that if the Bluetooth link drops, the pump carries on running whatever it
was last told to run.

**STOP releases the vacuum.** The pump vents, at about 4.68 kPa per second. It is a release, not a
pause; the protocol has no pause command.

While it is connected, the pump sends about four pressure readings a second as short lines of text.

```
 phone                                               pump
   |  66 2A 2A 00  (x9)  clear the table   ------->    |
   |  66 2A 2B ...       add preset(s)     ------->    |  table: up to 9 presets
   |  66 2A 2C 00        start slot 0      ------->    |  runs slot 0 on its own
   |  <-------  "#AUTO,-189,179,0"  about 4 a second   |
   |  66 2A 2C 01        start slot 1      ------->    |  (the phone decides when)
   |  66 2A 2D           STOP              ------->    |  vents at about 4.68 kPa/s
   |  <-------  readings fall, then 0.0 ("no measurement")
```

### Units at a glance

| Quantity | How it is expressed |
|---|---|
| Pressure inside OpenPump | kPa of vacuum, as a positive number; 0 means none |
| Pressure in commands | whole kPa, one byte, **0–57** |
| Pressure in telemetry | tenths of a kPa (deci-kPa), **negative**: `-189` is 18.9 kPa of vacuum |
| Hold times in commands | whole seconds, **one byte, 0–255** |
| Speed | a 0–255 code on the wire, a percentage in the app |
| inHg | 1 inHg = 3.38639 kPa. OpenPump shows mercury units as negative numbers, so 22 kPa reads −6.50 inHg |
| cmHg | 1 kPa = 0.750062 cmHg |

Some useful values: 7 kPa = 2.07 inHg, the lowest ceiling OpenPump allows · 17 kPa = 5.02 inHg,
the guided start's target · 20 kPa = 5.91 inHg · 40 kPa = 11.81 inHg, OpenPump's default ceiling
· 57 kPa = 16.83 inHg, the most a command can ask for · 4.68 kPa/s = 1.38 inHg/s, the vent rate.

### What OpenPump does with the pump

- **Connects and listens.** It finds the pump, connects, remembers it, and shows the live pressure.
- **Runs routines.** It writes up to eight presets at a time and starts each one on its own clock.
  Rests are vented.
- **Changes a run as it plays.** Pull, drop, hold, drop time and speed can change for this rep, this
  set or the rest of the routine. There are also Hold (the run screen's Pause button), Skip,
  +30 s, a rest on demand, tools to reshape a ramp, and Coming steps for the steps still to come.
- **Stops.** It sends STOP, watches the readings until they show the vent, retries if they don't,
  and asks the person if it still can't tell. It keeps watching after the run has ended.
- **Handles a dropped link.** It warns after 5 seconds of silence and sends STOP 6 seconds later.
- **Enforces limits.** A pressure ceiling, one time limit for every hold, and a two-hour stop.
- **Special uses.** A guided start, a measurement hold, a seal check, a Tissue response test and a
  hardware validation routine.
- **Practice mode.** A simulated pump for use without hardware.

OpenPump never sends the Set time command (`0x25`).

---

## 2. Bluetooth

### Finding the pump

The pump advertises the name `ZD21_PUMP`. OpenPump also accepts `ZD21_PUMP_OTA` and treats it
exactly the same way. Despite the suffix, `_OTA` is not a firmware-update mode; it is simply a
second name some pumps advertise. Why a pump uses it, and whether the pump behaves any differently
under it, isn't known. OpenPump has no firmware update feature and never writes to anything named
OTA, DFU, firmware or update.

OpenPump scans with no filters in low-latency mode and matches the name without regard to case. It
reads the name from the scan record, or from the phone's cached name for the device when the record
has none. A scan gives up after 15 seconds.

OpenPump remembers pump addresses. When two or more are remembered, it collects sightings for 2.5
seconds after the first match, then connects to the remembered pump with the strongest signal. The
first time it sees an unknown pump, it asks whether to remember it. It skips that question for the
very first pump an install ever sees, or when the pump may still be running; then it remembers the
pump and connects straight away.

A connected pump stops advertising, so only one phone or app can hold it at a time.

### Services and characteristics

| Role | UUID |
|---|---|
| Service | `0000fff0-0000-1000-8000-00805f9b34fb` |
| Write characteristic, for commands | `0000fff1-0000-1000-8000-00805f9b34fb` |
| Notify characteristic, for replies and telemetry | `0000fff4-0000-1000-8000-00805f9b34fb` |
| Client configuration descriptor on FFF4 | `00002902-0000-1000-8000-00805f9b34fb` |

Everything the pump sends arrives as notifications on FFF4: acknowledgements, the table dump and the
telemetry lines.

### Connecting on Android

OpenPump connects in a particular way. Each detail is there because leaving it out produced the
Android connection error "status 133" on a real phone:

1. Stop the scan, then wait **500 ms** before connecting. Some phones, Samsung's among them, need
   the pause.
2. Connect from the main thread, never from the scan callback's thread.
3. Look the device up again by its address. A device object taken from a scan result goes stale.
4. Close any previous GATT connection before every attempt.
5. Connect with auto-connect **off** and the transport set to **LE**.
6. Once connected, discover services.
7. Find FFF0, FFF1 and FFF4, turn notifications on and write the descriptor (next section). The link
   is ready once that descriptor write succeeds.

A disconnection that reports status 133 is retried twice: after 1.2 seconds, then after 1.8
seconds. Nothing else reconnects automatically. After a dropped link, the person taps
**Reconnect**, which starts a fresh scan, and OpenPump never puts pressure back on without asking
(section 10).

### Turning on telemetry

Enable notifications on FFF4, then write its descriptor: `01 00` for notifications, or `02 00` for
indications if FFF4 only offers those. Telemetry starts straight away without any request, and it
keeps coming while the pump is idle.

### MTU

OpenPump never requests a larger MTU. Commands are at most 8 bytes and a telemetry line is about 16
bytes, so both fit comfortably in the default 20-byte payload. A full table dump is 46 bytes, which
does not. How the pump delivers it at the default MTU, split or not, isn't known yet. OpenPump only
reads the dump during hardware validation, and it reads each notification on its own.

### Sending commands

- **Write without response.** Commands go to FFF1 as "write without response". The phone's "write
  done" callback only means the frame left the phone. The pump's own acknowledgement (section 3)
  arrives separately.
- **One frame at a time.** Android allows one outstanding GATT write. A second one issued too soon
  is refused, and it used to be lost without a sound: an early version of OpenPump fired a whole
  table write in one loop, lost most of it, and the pump sat idle. OpenPump now queues every frame
  and sends the next only when the previous one is done, or after **400 ms** if the phone never says.
- **Bounded retries.** A frame the phone refuses is retried; after three refusals it is dropped and
  logged, so one bad frame can't block the queue.
- **Order is kept.** Order matters on this pump, because Add appends to the table and Delete closes
  gaps. The order of the Adds is the slot order.
- **A fresh start after a disconnect.** Frames queued for a lost link are dropped, never replayed.
- **A pause before START.** After writing a whole table, OpenPump waits **600 ms** before sending
  START. A START sent before its Add has landed starts nothing, and the pump sits idle. Paths that
  write a single preset (the holds, the guided start, the seal check, the test pull and every live
  adjustment) send START straight behind their Add in the same queue, without the pause.

### Pairing replies and timestamps

Android's "write done" callback doesn't say which write it belongs to. OpenPump tags each write and
notes the time just before handing it over. It accepts a "write done" only if the tag matches the
write in flight and the time isn't earlier than the handover. A late callback for an earlier frame
could otherwise be credited to the next frame, a STOP, and make readings from before the STOP look
like readings after it.

The pump's replies to STARTs, `2C 01` or the refusal `2C FD`, name no slot - and replies go
missing, and some come late. The owner's log of 22 September 2026 has 343 STARTs and 319 replies,
lost in bursts (5 STARTs and 3 replies, 2 and 1, 2 and 0), and replies that came 26 to 1033 ms after
their START. So a reply is not simply matched to the oldest START waiting: it is given **every START
it may be for**, and it counts as one START's only when that is the only one.

- Replies come back in the order the STARTs were sent, so a reply is never for a START older than
  the one the previous reply may have been for.
- A reply comes within **5 seconds** of its START's write, or not at all - five times the slowest
  in the log. A START written longer ago than that can no longer be answered.
- A reply is never for a START that hasn't been written yet. Writes leave in order, so a START
  still unwritten when a later one's write completes was dropped, and is forgotten.
- A reply that may be for more than one START confirms none of them and puts nothing in force. A
  reply that can be for no START at all - later than 5 seconds - is paired with nothing, and
  OpenPump sends what was in force again (section 7): when in doubt, it converges. Before this
  was fixed, a reply 2.8 s late was taken for the next START's, and a lowering the pump had
  refused was once counted as taken.
- Every START gets its place, including one that opened no row in the record of what ran (the
  +30 s refresh), and every place expires the same way.
- A live change is not sent while any START may still be answered (section 7), so its reply can be
  no one else's. After a START whose reply was lost, that can take up to 5 seconds.

OpenPump uses the replies for its record of what ran, and a live change goes in force only on its
own START's `2C 01` (section 7).

Every telemetry line is timestamped the moment it arrives, on the Bluetooth thread, with a monotonic
clock that can't be set back (Android's `elapsedRealtime`). The STOP's timings use the same clock.
When the main thread is busy, readings pile up and get handled in a burst. Stamping them when they
are handled would make a reading taken before a STOP look like one taken after it.

### Threads

OpenPump hands every Bluetooth callback to the main thread, always, even when it is already there,
so events are handled in the order they happened. Views touched from the Bluetooth thread fail
silently on Android, and that once froze the connect screen.

### The last stop when the app closes

If the screen is destroyed, or the app crashes, while the pump may still be running, OpenPump drops
everything waiting in the queue and sends STOP on its own. It keeps the link open until that write
is done plus 250 ms, and never longer than 1.5 seconds in all. Then it closes the link and never
reopens it. Nothing is left to watch the readings, so the vent can't be confirmed; the next time the
app opens, it tells the person to check the cuff.

### Android permissions

On Android 12 and newer, OpenPump uses `BLUETOOTH_SCAN` (declared "never for location") and
`BLUETOOTH_CONNECT`. On Android 11 and older, it uses `BLUETOOTH`, `BLUETOOTH_ADMIN` and location.
Runs and holds use a foreground service of type `connectedDevice`.

---

## 3. Command frames

Every command starts with the two bytes `66 2A`, followed by a one-byte opcode and, for some
commands, a payload. There is no length byte and no checksum. The pump answers Add, Delete and Start
with two bytes: the opcode, then `01`, or `FD` when it refuses.

| Opcode | Command | Frame | Payload | The pump replies | OpenPump sends it |
|---|---|---|---|---|---|
| `0x2B` | Add preset | `66 2A 2B SP UP UH LO LH` | 5 bytes (section 4) | `2B 01` | yes |
| `0x2A` | Delete preset | `66 2A 2A II` | table index | `2A 01` | yes |
| `0x2C` | Start preset | `66 2A 2C II` | slot 0–8 | `2C 01`, or `2C FD` (refused) | yes |
| `0x2D` | Stop, which **vents** | `66 2A 2D` | none | not known (section 10) | yes |
| `0x29` | List presets | `66 2A 29` | none | the table dump, `29 …` | only in validation |
| `0x25` | Set time | not known | not known | not known | never |

Telemetry comes the other way as text starting with `#`, which is the byte `0x23`.

A few things are worth knowing:

- The second header byte, `0x2A`, is also the Delete opcode, so "delete entry 0" is `66 2A 2A 00`.
- The pump **does** refuse. A START of an empty entry is answered `2C FD`, and the pump carries on
  with whatever it was running; the owner's log of 22 September 2026 has 23 such refusals. Only
  `2C FD` has been seen, but OpenPump reads `<opcode> FD` as a refusal for any command. A reply can
  also go missing, so silence proves nothing either way. OpenPump never waits for a reply before
  sending the next frame, and puts a live change in force only on its `2C 01` (section 7); the
  telemetry shows what the pump actually did.
- OpenPump's encoder quietly clamps values: slot indexes to 0–8, pressures to 0–57 and holds to
  0–255. So an index of −1 would become slot 0, a different preset, and a 600-second hold would
  become 255. OpenPump checks values before they reach the encoder, and your app should too.

Here are some frames built exactly as OpenPump builds them:

| Frame | Bytes | How |
|---|---|---|
| Stop | `66 2A 2D` | header, `2D` |
| Start slot 3 | `66 2A 2C 03` | header, `2C`, slot 3 |
| Delete entry 0 | `66 2A 2A 00` | header, `2A`, index 0 |
| List | `66 2A 29` | header, `29` |
| Add: 75 %, 20 kPa for 30 s, then 10 kPa for 5 s | `66 2A 2B BF 14 1E 0A 05` | speed (75 × 255 + 50) / 100 = 191 = `BF`; 20 = `14`; 30 = `1E`; 10 = `0A`; 5 = `05` |
| The pump's reply to a start | `2C 01` | opcode, `01` |
| The pump's refusal of a start | `2C FD` | opcode, `FD` |

---

## 4. The preset table

### One preset

An Add is `66 2A 2B` followed by five bytes that describe one cycle. The pump pulls to the upper
pressure, holds it for the upper hold, drops to the lower pressure, holds that for the lower hold,
and repeats until told otherwise.

| Byte | Field | Unit | Valid range |
|---|---|---|---|
| 4th | speed | code | 0–255 |
| 5th | upper pressure | whole kPa | 0–57 |
| 6th | upper hold | seconds | 0–255 |
| 7th | lower pressure | whole kPa | 0–57 |
| 8th | lower hold ("drop time") | seconds | 0–255 |

A hold is a single byte, so no preset can hold for longer than 255 seconds. Section 6 shows how
OpenPump builds longer holds.

Within those ranges OpenPump applies its own rules. Every upper pressure is clamped to the user's
ceiling (7–57 kPa, default 40) at the moment it is written. In routine and live presets the lower
pressure is always at least 1 kPa below the upper. OpenPump never writes an all-zero preset: the pump
doesn't store one (see below). Saved sets use speeds of 5–100 %. When the upper and lower pressures
are equal, the preset simply holds; OpenPump uses that shape for its measurement hold, guided start,
seal check and test pull. A lower hold of 0 means "no pause at the bottom".

**Speed.** Never put a raw percentage on the wire. The pump takes a 0–255 code:

- encode: `code = (percent × 255 + 50) / 100`, using integer division, with the percentage clamped
  to 0–100
- decode: `percent = (code × 100 + 127) / 255`, using integer division

| % | 5 | 50 | 55 | 60 | 65 | 70 | 75 | 80 | 85 | 90 | 100 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| code | 13 | 128 | 140 | 153 | 166 | 179 | 191 | 204 | 217 | 230 | 255 |
| hex | `0D` | `80` | `8C` | `99` | `A6` | `B3` | `BF` | `CC` | `D9` | `E6` | `FF` |

Every code in the table decodes back to the same percentage.

**Two habits of the pump.** First, during a long upper hold it **coasts**: it pulls once to the
setpoint, closes the valve, and lets the pressure drift down slowly instead of topping it up. How
fast it drifts hasn't been measured yet; one early log shows a drift from 13.1 to 9.0 kPa. Second, it
**only pulls**. Lowering a setpoint does not lower the pressure in the cuff; that comes down only at
the next drop, or with a vent.

### Nine entries: Add appends, Delete closes the gap

- The table holds nine presets.
- **Add appends** a preset to the end of the table.
- **Delete removes one entry and closes the gap**: every later entry moves down one place.
- Nine deletes of entry 0 therefore empty the table, whatever it held.
- **START runs the entry at that index.** Starting an empty entry is refused (`2C FD`), and the
  pump carries on with whatever it was running.
- **An all-zero preset is not stored.** `66 2A 2B 00 00 00 00 00` leaves the table as it was, so
  every later entry sits one place lower than a count of the Adds says. This is inferred from the
  owner's log of 22 September 2026: OpenPump used to write each rest that way, and every START of a
  live change after a rest in the same batch was refused.
- **Deleting the entry that is running doesn't stop it.** The pump keeps cycling the preset it was
  started on until the next START or a stop (from the same log).

A few edge cases haven't been tried on the pump: what an Add does when the table is already full,
and whether STOP leaves the stored table alone. OpenPump is built not to depend on either. In
particular, after every vent it writes the table again before starting anything.

### How OpenPump lays out the table during a run

- **Entries 0–7** hold up to eight presets of the routine, called a batch. A batch ends at the
  first rest. A preset's slot is its position in the routine minus the position the batch started
  at.
- **The next entry** is kept free for a live change, the "override". Its index is the number of
  routine entries in the table, so it is 8 only when the batch is full; after a final batch of three
  it is 3.
- **Why keep one free?** The protocol can't change the running preset. A live change has to be
  written into a free entry and started.
- **Why the last entry?** Deleting the last entry moves nothing else, so an old override can be
  removed without renumbering the routine's entries while the run goes on.
- **A rest takes no entry.** The pump doesn't store the all-zero preset a rest used to be written
  as, so every entry after it sat a place lower than OpenPump counted, and the first live change
  after a rest started an empty entry and was refused. A batch now stops at the rest: the table is
  written again when the rest ends anyway. So "slot = position minus batch start" is the pump's own
  numbering, and the override's index is the number of entries actually written.

### Writing, starting and stopping

| Operation | Frames |
|---|---|
| Write a batch | 9 × `66 2A 2A 00`, up to 8 Adds (stopping at the first rest), a 600 ms pause, then `66 2A 2C 00` |
| Set up a single preset (holds, guided start, seal check, test pull) | 9 × `66 2A 2A 00`, one Add, then `66 2A 2C 00` straight away |
| Move to the next step | `66 2A 2C <slot>` |
| Live change | `66 2A 2A <override index>` if an override is already there, then the Add, then `66 2A 2C <override index>` |
| Stop | `66 2A 2D`: the pump vents |

### Reading the table back

The List command (`66 2A 29`) returns the byte `29` followed by five bytes per stored preset, in
slot order and in the same field order as an Add: speed code, upper, upper hold, lower, lower hold.
There is no count byte; an empty table comes back as the single byte `29`. OpenPump uses List only in
its hardware validation, to check a written table byte for byte.

After the first batch of the worked example in section 13, the dump would be these 41 bytes:

```
29  99 14 FF 13 01  99 14 2D 0A 14  99 0E 14 07 0A  A6 10 14 08 0A
    B3 12 14 09 0A  BF 14 14 0A 0A  CC 16 14 0B 0A  D9 18 14 0C 0A
```

---

## 5. Telemetry

### The format

Each notification is one line of ASCII text: `#<mode>,<pressure>,<speed code>,<flag>`.

For example, `#AUTO,-189,179,0` is these 16 bytes:

```
23 41 55 54 4F 2C 2D 31 38 39 2C 31 37 39 2C 30
```

It means mode AUTO, 18.9 kPa of vacuum, speed 70 %, flag 0.

| Field | Meaning |
|---|---|
| mode | a word; `AUTO` while a preset runs. OpenPump only logs it. |
| pressure | tenths of a kPa, **negative**, because it is a vacuum. Take the magnitude and divide by 10. |
| speed | the same 0–255 code as in an Add |
| flag | a fourth field whose meaning isn't known; OpenPump ignores it |

Readings arrive about four times a second from the moment notifications are turned on, whether the
pump is running or idle. OpenPump's timeouts assume a reading every 240 ms, about 4.16 a second.

### 0.0 means "no measurement"

A reading of exactly zero means the pump **isn't measuring**. It does not mean ambient pressure, and
it does not mean "vented". After a vent, for instance, most readings are 0.0 while the telemetry
keeps flowing.

OpenPump treats a 0.0 reading like this:

- it never adds it into a dose or into time under pressure;
- it never draws a slope across it (one early analysis tool did, and reported a fall of 383 kPa
  per second);
- it never uses it as the starting point for measuring a vent;
- it never takes it, on its own, as proof of a vent;
- it draws it as a gap in the chart, not a plunge to zero;
- it still counts it as proof that the link is alive.

### How OpenPump reads a notification

1. Note the arrival time, on a monotonic clock, on the Bluetooth thread.
2. Read the bytes as text and find the first `#`. No `#` means it isn't telemetry.
3. Split what follows on commas. Fewer than two fields means it isn't telemetry.
4. Pressure is the absolute value of the second field × 0.1, in kPa. If it doesn't parse, or isn't a
   finite number (`NaN`, `1e400`), it isn't telemetry.
5. Speed is the decoded third field, if there is one.
6. The flag is set when a fourth field is present and isn't `0`.
7. "No reading" means the pressure is exactly 0.0.

Anything that isn't telemetry is another kind of frame: a reply, the table dump, or something
unknown. OpenPump logs those, and acts only on START replies and the dump.

| Notification | Result |
|---|---|
| `#AUTO,-189,179,0` | 18.9 kPa, 70 %, flag off |
| `#AUTO,0,179,0` | no reading |
| `#AUTO,-1e400,0,0` or `#AUTO,NaN,0,0` | rejected |
| `2C 01` | not telemetry: the reply to a START |
| `29 …` | not telemetry: the table dump |

OpenPump's tests feed the parser 20,000 random frames and near misses; none produce a reading or a
crash.

### What OpenPump uses readings for

| Question | Which readings count |
|---|---|
| Is the link alive? | any line, including 0.0. Five seconds without one means the link is lost. |
| What is the pressure now? | a real (non-zero) reading no more than 5 seconds old |
| Did a STOP vent? | only readings that arrived after the STOP was written, compared with the last real reading before it, if that one is no more than 600 ms old |
| Can the run hold here? | needs a real reading no more than 5 seconds old |
| Is a rest really resting? | ignores 0.0 and old readings |
| Has the guided start reached its target? | a fresh, real reading at or above the target |
| Two-hour stop | while pressure is commanded, a 0.0 or missing reading counts as time under a seal |
| Time under pressure | only real readings at or above the counting line; gaps are never bridged |

---

## 6. From a routine to pump commands

### The model

In OpenPump a **routine** is an ordered list of **stages**. A stage is either a list of **sets**
(the same set may appear more than once) or a **rest** (vented, 30 seconds to an hour, two minutes
by default). Each set expands into one or more presets, called steps, and the run plays the steps in
order.

| Set field | Meaning | OpenPump's limits |
|---|---|---|
| pull | upper pressure, kPa | 1 to the ceiling (at most 57) |
| drop | lower pressure, kPa | 0 up to 1 below the pull; a drop above 10 kPa stays at least 4 kPa (1.0 inHg) below it |
| hold | time at the pull, seconds | up to an hour for a fixed set; up to 255 for a ramp |
| drop time | time at the drop, seconds | 0–255 |
| speed | % | 5–100 |
| duration | how long the set runs, seconds | 30 seconds to an hour |
| ramp | walk from start values to end values | end values for all five fields |
| steps | how many steps a ramp has | 2–9 |
| rest | a rest set: vented for its duration | |
| per-rep changes | different values for repetition N | off by default |

A set's cycles, or reps, number `duration / (hold + drop time)`, at least one. For an ordinary set
they are simply the pump repeating one preset; OpenPump counts them but can't see them. The pump
doesn't report where it is in its cycle, so the app can't pick out one repetition inside a repeating
preset.

### A regular set becomes one preset

A fixed set whose hold is 255 seconds or less becomes **one preset**. OpenPump plays it for the
set's duration on its own clock while the pump repeats the cycle. The duration itself is never sent
to the pump.

Pull 20 kPa for 30 seconds, drop to 10 kPa for 5 seconds, at 75 %, for 105 seconds, becomes one
Add, `66 2A 2B BF 14 1E 0A 05`, one START, and the next step's START 105 seconds later. That is
three whole cycles. OpenPump's set editors keep a fixed set's duration to whole cycles (two minutes
typed for this set becomes 1:45), so the pump is moved on as a drop ends.

### A ramp becomes two to nine presets

A ramp walks each field from its start value to its end value. Step `i` of `n`, counting from 0,
takes `f = i / (n − 1)`, and each field becomes `round(start + (end − start) × f)`, with the hold
capped at 255 seconds. Each step runs whole cycles of its own hold and drop time, the same number
of cycles for every step and at least one, so every step ends as a drop ends and the next step's
pull starts from a released cuff. The duration is snapped to those whole cycles. A live change of
a step's hold or drop time keeps this: the step playing then ends at the end of the pump's cycle
nearest the end it had (never before the cycle under way is over), and with "Rest of this ramp"
each later step goes to the whole cycles of its new hold and drop nearest its length. "Rest of this
ramp" raises a later step's pull no further than that step's own hard limit (on a plan's routine:
your ceiling, the most you said you will go to, 15 inHg and a first month's 6 inHg), nor past the
strip's usual 10.0 inHg unless you have confirmed past it this run; the one-time question asks
about the highest pull the change puts anywhere on the ramp, and a step held at a limit is said.

A ramp can't build a hold longer than 255 seconds. Its steps are sent as they are, so OpenPump caps
a ramp's hold when the set is saved. The worked example in section 13 has a seven-step ramp.

### Holds longer than 255 seconds

A **set** of any length is still one preset, because the duration never goes to the pump. What
needs more than one preset is a **hold** longer than 255 seconds, since the hold field is one byte.
OpenPump "stitches" such a hold, one repetition at a time:

1. While more than 255 seconds of hold remain, it adds a chunk: 255 seconds at the pull, then one
   second a kPa lower, at the set's speed. OpenPump plays each chunk for 255 seconds.
2. Then it adds the last preset: the rest of the hold, followed by the set's own drop and drop time.
   OpenPump plays it for that remaining hold plus the drop time.

The chunk's lower pressure goes out as the pull minus 1 kPa, because OpenPump always keeps a routine
preset's lower pressure below its upper one.

Pull 20, drop 10, hold 300 seconds, drop time 20 seconds, 60 %, running 320 seconds, is one
repetition. It becomes `66 2A 2B 99 14 FF 13 01`, played for 255 seconds, then
`66 2A 2B 99 14 2D 0A 14`, played for 65 seconds.

**It isn't known yet whether the pump plays two adjacent presets without a break.** When a new START
arrives, it might carry straight on from the current pressure, or it might release or pause first.
Stitched holds cross that boundary every 255 seconds. OpenPump's simulated pump simply pulls back up
to the setpoint at each START.

### Per-repetition changes

A set can give repetition N its own values. That set then becomes one preset per repetition,
stitched where needed. Because the result depends on the same "adjacent presets" question, OpenPump
keeps the feature switched off until it has been tested on the pump. A set that already has such
changes still plays them.

### Rests

A rest takes no entry in the table: the batch before it ends there. When the run reaches a rest,
OpenPump sends STOP and watches for the vent (section 10) while the countdown runs. When the rest
ends, it writes the table again from the next step, pauses 600 ms, and starts slot 0. It rewrites
because it can't be sure the table survived the STOP. A routine once stopped delivering after its
first rest when the app simply started the slot it had written before the rest.

During a rest OpenPump keeps watching. If a real reading is still above 2 kPa three seconds after
the stop, it sends STOP again, no more than once every two seconds, and says so on screen. One rest
was seen with the pressure still climbing: the stop had not taken, and the pump kept pulling.

### The sequence on the app's clock

1. **The run starts.** Clear the table, write the first batch (up to eight steps), pause 600 ms,
   start slot 0.
2. **A step's time is up.** Start the next slot. A step's deadline is the moment it was started plus
   its duration.
3. **A batch runs out.** When the next step isn't in the table, clear it, write the next batch
   beginning with that step, pause 600 ms and start slot 0.
4. **The last step ends.** Send STOP. Depending on settings, run the Tissue response test or a
   measurement hold first (section 9).

OpenPump ignores a timer that fires more than 1.5 seconds before its step's deadline, and one that
has been superseded. An early timer once ended a twelve-minute run after three seconds.

### What the pump does between commands

- It keeps running the preset it was started on, indefinitely, with or without the phone.
- It never moves to the next preset by itself.
- It coasts during a long upper hold: it pulls once, then drifts down slowly.
- It overshoots its target slightly. On a test rig it reached 21.0 kPa against a target of 20.
- After STOP, it vents.

---

## 7. Changing a run while it plays

### How a live change reaches the pump

The protocol can't change the preset that is running, so every live change **replaces** it:

1. If an earlier override is in the table, delete it (`66 2A 2A <override index>`). It is the last
   entry, so nothing else moves.
2. Add the new preset. The upper pressure is clamped to the ceiling as it is written, and the lower
   pressure to at least 1 kPa below the upper.
3. Start it (`66 2A 2C <override index>`).

For an adjustment, the upper hold is capped at the seconds left in the step, rounded up and never
below one, so the pump isn't asked to hold past the moment the app will move it on. An override
never restarts the countdown.

A started preset begins its cycle with the hold, so a change never sends the whole hold again.
**The hold under way carries on from where it is**: the new entry's hold is what is left of the
new hold (never under 3 seconds), from the app's count of where the pump is in its cycle. On a set
the pump repeats, the same entry is written again with the full hold once that cycle's drop is
over, so the next cycle starts whole. A change made during the drop waits for the drop to end and
goes in with the next hold ("The drop is under way — the change goes in with the next hold."); on
a step that is one cycle the drop is its last part, so nothing is changed and the person is told.
Only a Revert starts the set again from its hold.

**A change of the cycle moves the block's end.** When the hold or the drop time of a set changes
(a tap on the strip's Hold time or Drop time, or "+30 s hold"), the step's deadline moves by
exactly what the change adds: the cycle under way ends with its new length, and each set after it
in the block by the change in its cycle. The deadline and the step's duration move together, the
way +30 s moves them. A move that would pass the two-hour stop is refused. On a ramp's step of
several cycles the step ends at the cycle end nearest the end it had. A change of the pull or the
drop, and a step that doesn't cycle, never move it.

Pull and drop move in 1 kPa steps (in inHg or cmHg, about half a display unit, rounded to whole
kPa). On the run screen's − / + strip, hold moves in 5-second steps and drop time in 1-second
steps; speed, in 5 % steps, is on the Adjust the running set sheet behind the strip's More ›. The
upper hold stays within 1–255 seconds, the drop time within 0–255 seconds, and speed within
0–100 %. The strip keeps to narrower ranges than these: pull from 12 kPa up to its usual 34 kPa
(10.0 inHg), drop 0–10 kPa and below the pull, hold 10–255 seconds, drop time 0–30 seconds. A pull
past 34 kPa is asked once for each new highest pull in the run, and never goes past the run's hard
limit (the ceiling and, on a plan's routine, the most you said you will go to, 15 inHg and a first
month's 6 inHg). A drop raised past 10 kPa is asked once a run, and then stays at least 4 kPa
(1.0 inHg) below the pull. What the strip lets through still goes through the checks above. A tap
that hits a limit says which limit stopped it. The strip is the one
place on the run screen that changes the step playing, and it goes by this same road.

OpenPump waits **400 ms** after the last change before sending, and sends only the final value. A
value set during one step is never applied to the next: if the step ends first, the change is
dropped and the person is told. A value that can't be sent, because the link is down or there is no
free entry, is never shown as if it were in force.

**A change is in force only when the pump acknowledges its START** (`2C 01`). Until then the
adjustment that was in force before it still carries into the next steps, the at-pressure clock
counts against it, and a ramp's remaining steps are not moved. This is what the owner's pump did
with four raises after a rest: it refused them, OpenPump had counted them anyway and written them
into the rest of the ramp, and the next step started 8 kPa above the plan.

- **An explicit refusal** (`2C FD`) known to be the change's own: the pump carries on with what it
  was running. The change is undone on screen and the person is told, for example "The pump
  refused that change — still at −7.1 inHg."
- **No reply within 800 ms is not a refusal.** The pump often carries a START out without
  replying, so the change may or may not be running. OpenPump **converges**: it sends what was in
  force before - the carried adjustment, or the step's own entry - again, as an ordinary START.
  That overtakes the lost change on the pump whichever way it went. When the pump acknowledges
  it, the pump and the screen agree, and the person is told "The pump didn't answer, so the change
  was undone — still at −7.1 inHg." If that START gets no reply either, it is sent again, three
  times in all. After that the person is told "The pump isn't answering — it may be at …", with
  the higher figure, and to check the cuff; STOP vents it. Until the pump acknowledges a START
  whose setting OpenPump knows - the next step, the end of a rest, a convergence, a Revert -
  every cell says "not confirmed", the pull shows the higher figure the pump may be at, and the
  run card says "The pump isn't answering — may be at −7.8 inHg (not confirmed)". A refusal, or
  a reply that may be anyone's, doesn't count as answering. No live change is sent meanwhile. The
  link watch decides whether the link itself has gone.
- **The higher figure is everything the pump may still be running**: the last setting it is
  known to have taken, and every START sent after it that it hasn't refused with certainty - a
  change whose reply never came, a convergence, a step. When the pump refuses a step's START it
  carries on with the step before, so that step stays in the figure until the pump takes
  something else. An acknowledgement of a table entry that a refusal put in doubt doesn't say
  what the pump runs, so it doesn't lower the figure either.
- **A refusal anywhere else** - of a step's START, of a convergence, or a `2C FD` that may be for
  more than one START - is a refusal all the same: the pump didn't take something OpenPump sent,
  so what it runs, and what its table holds, aren't known. OpenPump writes the table again and
  sends what was in force. If the pump then refuses **that** START too, on a table it was just
  given, nothing OpenPump sends is being taken, and the pump may be running something the screen
  doesn't show: the run stops as STOP stops it - the pump vents, watched - and the summary says
  "Stopped: the pump refused the pressure on screen, twice - the cuff was vented". A warning alone
  would leave an unknown pull on the cuff until someone read it. A `2C FD` that may be for more
  than one START counts here when every START it may be for is the setting on screen, sent on a
  table just given for a refusal: whichever it answers, it is that refusal. A refusal of a
  convergence that has already ended counts too - the table is written again, and what is on
  screen sent again.
- **After a refusal, the next step writes the table again before it starts.** The pump's
  acknowledgement of an entry a refusal put in doubt doesn't say what it runs; starting from a
  table it was just given, the next step's acknowledgement does, and "not answering" can end
  there instead of lasting the rest of the run.
- The replies come back in order. Any other START sent after a change (the next step, a revert,
  +30 s's refresh) is what the pump runs if it takes it, so a change still waiting then never goes
  in force.
- A new change waits while any START may still be answered, while a convergence is under way, or
  while a START waits to be sent behind the table: its reply could otherwise be credited to the
  other one, or the waiting START would overtake it. The 400 ms settle simply waits; "Apply now"
  says the pump is still answering. A change tapped while the previous one is still on its way
  builds on it, and goes with it if that one doesn't go in force.
- **Nothing rewrites the table while an answer is due.** The whole-routine offset, Coming steps,
  the ramp's reshape, step count and step size, and +30 s's refresh of an adjustment wait exactly
  as a live change does - while any START may still be answered, a START waits to be sent, a
  convergence is under way, the pump isn't answering, or a hold may be up - and say so where they
  were pressed: "The pump is still answering the last command — nothing was changed yet. Try again
  in a moment." Before this was fixed, an offset tapped in the 600 ms a convergence's START waited
  behind a rewrite moved the table under it; the START was dropped, and the pump stayed at 30 kPa
  with the screen at 24, both quiet, until the step ended.
- **Hold** works the same way, with one difference: its time limit is armed the moment the Hold
  is sent, not when it is acknowledged. HOLDING and the frozen countdown begin on the pump's
  acknowledgement. A refused Hold says so, and the step carries on. A Hold with no reply
  converges back to the step, and its limit stays armed until the pump is known to be off it -
  so a Hold whose reply was lost can never hold with no limit. If the pump stops answering, the
  person is told the hold may be up, and the limit still ends it.
- **Resume** takes the hold down on screen at once, but the limit stays armed until the pump
  acknowledges a START sent after it (or, when the run moved on into a rest, until that rest's
  vent is seen). A refused or unanswered Resume is sent again, like any convergence. Before this
  was fixed, the limit went the moment Resume was sent, and a Resume the pump refused left it
  holding with no limit. While a hold may still be up, the ramp's reshape and step count,
  the whole-routine offset, a dialled change and +30 s's refresh all wait.
- **A START that has to wait** - behind a table rewrite, or for a dial to settle - is stamped with
  the step, the table and the moment it was made for. If the run has moved on by the time it
  fires (the next step, a rest, a hold, a new table), it isn't sent as it was - and it isn't
  simply dropped either, because it was the pump's way to what the screen shows. If the step can
  still be commanded, what is in force now is sent again, as a convergence, for the step and table
  as they are now - a Hold that may still be up but isn't HOLDING on screen included. If the run
  moved into a rest, a Hold on screen or its end, what comes next writes the pump (the rest's STOP,
  Resume, STOP). Before this was fixed, a convergence's START, waiting 600 ms
  behind a rewrite, could fire after the step ended into a rest, start the step before it and
  cancel the rest's vent watch: the pump pulled through the rest.
- These decisions - what a reply or a silence does, when the table is written first, what a
  waiting START does when it fires, what may be edited, the higher figure, when the run stops -
  live in one place, `RunControl` in the core, which the run screen calls. The tests drive that
  same code against the simulated pump, with edits tapped inside the 600 ms waits, refused and
  unanswered STARTs, late replies and holds reaching their limit.

### This rep, this set, or the whole routine

| Scope | What OpenPump does |
|---|---|
| This rep (on the sheet: **This set only**, or **This step only** on a ramp) | The override applies to the step playing now, and the next step plays as planned. For an ordinary fixed set, which is a single repeating preset, that means the whole set. |
| This set (the default; on the sheet: **Rest of this block**, or **Rest of this ramp** on a ramp) | For fixed and stitched sets, the change is sent again as each later step of the set starts. For a ramp, every remaining step shifts by the same amount, keeping the ramp's shape, and the table is written again from the next step. |
| Whole routine (**Whole-routine offset**) | Pull only. Every remaining work step's pull moves by the change (never the warm-up, a rest, the fatigue block or the retention hold), each within the ceiling and its own hard limit, and the table is written again from the next step. On a routine from OpenPump's training plan, going more than 2 kPa above the plan (any rise at all on a length routine) is asked once for each new highest offset, and no rise is allowed on a reduced day. |

The scope goes back to "this set" at every set boundary. A ramp shift keeps its original shape as a
reference, so repeated nudges never build on each other, and a nudge toward less pressure never
raises any later step.

### The other controls

**Hold** is the run screen's **Pause** button, and releasing a Hold is its **Resume**; this guide
keeps the pump-side names.

| Control | What reaches the pump |
|---|---|
| **Skip** (the button reads "Skip these sets", "Skip step", "Skip warm-up" or, in a rest, "End rest") | The next step's START, now, or the next batch. For a few seconds after, the same button reads "Undo skip" and puts back what was skipped. |
| **Done** (the tunica release, a step done by hand) | Nothing while it plays: the pump is vented and nothing is commanded. When its 5 minutes are up the run waits, still sending nothing, until Done; then the next step's START, as for Skip. The changeover's **I've swapped** is the same. |
| **+30 s** | In a set (the button reads "+30 s hold"): the hold of the set playing grows by 30 seconds, 4:15 at most, and is sent as a live change, like a tap on the strip's hold: the hold under way goes on 30 seconds longer from where it is (in the drop, the next hold is the longer one), and the block's end moves by what that adds. In a rest or in the warm-up ("+30 s rest", "+30 s warm-up"): usually nothing, the app's deadline moves 30 seconds later; if an adjustment is in force, it is written again carrying its hold on from where it is (in the warm-up, 30 seconds longer), and in the drop nothing is written. On a ramp's step of several cycles the button adds one whole cycle and says it ("+0:42 step" for a 37 s hold and a 5 s drop), so the step still ends after a drop; on a step that is one cycle ("+30 s step") it is 30 seconds more hold. |
| **Hold** | An override at the current reading rounded to whole kPa (at least 1, at most the ceiling): 255 seconds there, then one second at 1 kPa lower, at the current speed. The countdown freezes. Hold needs a real reading no more than 5 seconds old. |
| **Release a Hold** | The hold that was under way carries on with what was left of it: the step's figures (or the adjustment's) are written into the override entry with that much hold and started, and on a set the pump repeats the full hold is written again once that cycle's drop is over. A Hold that began in the drop simply starts the next hold: the step's own entry is started again, or the adjustment is sent again. If an edit has since rewritten the table, OpenPump rewrites it from the running step first and pauses 600 ms. |
| **Rest** ("Rest for how long?": 30 s, 1, 2 or 5 min) | STOP, and the countdown freezes. At the end, the table is written again from the running step, followed by a 600 ms pause and a START (or the adjustment is sent again). It never resumes while the link is down. |
| **Revert** (on the Adjust the running set sheet) | The step's own entry is started again, rewriting first if needed, so the pump starts the set again from its hold. |
| **Reshape** (ramps) | The remaining steps are recalculated from the values in force now to a new end point; their durations don't change. The table is written again from the next step. |
| **Step count** (ramps) | The ramp's remaining time is divided into a new number of steps, and the table is written again from the next step. |
| **+ step** / **− step** (ramps, on the strip) | One more step at the ramp's top, with its time per step (nine steps at most), or an added step taken out before it starts. The table is written again from the next step; the countdown of the step playing is kept. |
| **Coming steps** | A later step's sets, hold, drop and drop time, a rest's length, a ramp's number of steps and time per step, or a skip, for this run only; never a pull. When the step changed is in the pump's table, the table is written again from the next step. The step playing is never changed there. |

**A Hold vents at the hold limit.** It has the same limit as every other hold in OpenPump, the
setting "Vent any hold after at most": 1:00 to 15:00 in 30-second steps, 5:00 by default. The limit
is counted on a monotonic clock from the moment the Hold is sent (whether or not the pump replies),
and the run screen and its notification show when the hold will vent. Meanwhile the pump keeps cycling the hold preset: 255
seconds at the setpoint, one second a kPa lower, and again. When the limit is reached, the run ends
exactly as STOP ends it: the pump is vented through the vent check (section 10), the run is filed as
stopped with the reason "Stopped: the hold reached its limit (5:00)", and nothing resumes. While the
link is lost, the limit waits; the link-loss stop is already handling the pump then.

### What gets rewritten, and when

| Event | Frames | Pause before START | Is the running entry deleted? |
|---|---|---|---|
| The run starts | 9 × Delete 0, up to 8 Adds (stopping at the first rest), START 0 | 600 ms | yes, if something is running, such as the guided start's pull |
| A step ends inside a batch | START n | none | no |
| A batch runs out | 9 × Delete 0, up to 8 Adds (stopping at the first rest), START 0 | 600 ms | yes, for up to 600 ms |
| After a planned rest | 9 × Delete 0, Adds from the next step, START 0 | 600 ms | nothing is running |
| After a Rest you added, or resuming after a reconnect | 9 × Delete 0, Adds from the running step, START 0 (or the adjustment sent again) | 600 ms | after a rest nothing is running; after a reconnect the pump may still be running |
| A live change, or Hold | Delete the last entry if there is one, Add, START it (for a live change on a set the pump repeats, the same again with the full hold once the cycle's drop is over) | none | yes, when an earlier override is running |
| Release a Hold | as a live change, with what was left of the hold; or, after a Hold in the drop, START the step's own entry (rewriting first if needed) | 600 ms after a rewrite | only if rewritten |
| Revert | START the step's own entry (rewriting first if needed) | 600 ms after a rewrite | only if rewritten |
| +30 s hold (in a set) | as a live change | none | yes, when an earlier override is running |
| +30 s rest, step or warm-up | nothing, or the adjustment sent again | | |
| Ramp shift, Reshape, Step count, + step / − step, Whole routine, Coming steps | 9 × Delete 0, Adds from the next step, **no START** | the running step keeps its countdown | **yes, in the middle of a step** |
| Skip | START the next step, or a batch | as above | as above |

### Nothing is rewritten while a Hold is up

A Hold lives in the override entry, and every rewrite deletes the whole table, the Hold's entry with
it. So OpenPump refuses every rewrite (the whole-routine change, a Coming-steps change, reshaping,
the step count, + step / − step and the ramp shift) while a Hold is up, and says so. An adjustment still waiting its
400 ms when the Hold began is dropped, with a message. The person releases the Hold first, then
adjusts.

### Rewriting the table in the middle of a step

A rewrite in the middle of a step deletes every entry, including the one playing, then writes the
rest of the routine from the next step. OpenPump expects the pump to keep running the preset it was
started on, since a Delete removes an entry, not the cycle already running.
**The owner's log of 22 September 2026 shows that it does**: a warm-up
step's entry was deleted by a rewrite, and the pump kept cycling that step for 54 seconds, until
the next START. It did not stop, and did not jump to what the rewrite put in its slot. OpenPump's
simulated pump now does the same.

The same kind of delete happens at every batch boundary (for up to 600 ms), at the hand-over from
the guided start to the routine, and whenever one live change replaces another.

---

## 8. Manual runs and trying a set

**Manual run.** OpenPump's Manual screen keeps one cycle as a setting: pull, drop, hold, drop time,
speed and duration, fixed or as a ramp. The defaults are 28 kPa and 12 kPa, 30 seconds and 5
seconds, 75 %, for six minutes, clamped to the ceiling. Starting it wraps that set in a one-stage
routine and runs it along exactly the same path as any routine, with the same clamps, table writes,
vent checks and link watching. With the defaults, the one Add is `66 2A 2B BF 1C 1E 0C 05`.

**Try this set.** From the set editor, or from Quick run on the home screen (recent single-set
runs, starred sets, or any set), the same path runs a copy of the set's current values. The set
itself isn't saved or changed.

Both confirm the peak pressure that will actually be sent, after the ceiling clamp. Both refuse to
start without a ready link, or while the last stop is unconfirmed. The guided start runs first when
it is on.

---

## 9. Guided start, holds and measurements

These features set up the pump the same way a run does. Each one clears any stop check still
running as it starts, clamps its pressure to the ceiling as it writes, and ends with a vent if the
telemetry goes quiet for five seconds.

### Guided start

The guided start is on by default. Before a run, OpenPump waits for the cuff to seal. Its target is
17 kPa, or the ceiling if that is lower.

- With "assist" on, also the default, it clears the table and pulls to the target:
  9 × `66 2A 2A 00`, then `66 2A 2B FF 11 FF 11 01` (100 %, 17 kPa for 255 seconds, 17 kPa for 1
  second, with the default ceiling), then `66 2A 2C 00`.
- With assist off, for a hand pump, it sends nothing and only watches.
- The routine starts once fresh, real readings have stayed at or above **the target for two
  seconds** in a row. A 0.0 reading never counts. The routine's first table write then takes over
  from the guided pull.
- After 30 seconds it asks whether to keep waiting. The whole wait is limited to **ten minutes**
  from the first pull, however many times the person chooses to keep waiting. A question left
  unanswered for **60 seconds** ends the attempt. Both of these end with a vent.
- Its time counts toward the two-hour stop.
- **A routine that opens with a step done by hand** (a length session's tunica release) starts on
  that step with nothing sent: no guided pull, and no seal check where that is on. The same check
  runs when **Done** is tapped, before the first step that can command pressure (the warm-up),
  with the same target, waits, questions and stops; its pass goes on to that step, whose table
  write takes over from the pull. A failed or stopped check ends the run with a vent, as at a start.
  A routine with a Tissue response test before it starts as it always did.

### Seal check

The seal check is a developer option and is off by default. It holds a pressure and measures how
fast it drifts. It sends 9 × Delete 0, then an Add at 100 % with the seal-check pressure (20 kPa by
default, 5 kPa up to the ceiling) for the seal-check hold (45 seconds by default, 40–120 seconds),
the same pressure again, one second, then START 0. With the defaults the Add is
`66 2A 2B FF 14 2D 14 01`. It waits up to 25 seconds for the pressure to level off, then watches the
drift for 10 seconds. It isn't vented at the end, because the routine's table takes over. When the
guided start is on, it replaces the seal check entirely.

### The measurement hold

The measurement (standardisation) hold keeps the cuff at a set pressure, so that body measurements
are taken under the same conditions each time.

- **Frames.** 9 × `66 2A 2A 00`, then an Add at **60 %** with the hold pressure (20 kPa by default,
  5 kPa up to the ceiling) for the count (30 seconds by default, 10–60 seconds), the same pressure
  again, one second; then `66 2A 2C 00`. With the defaults the Add is `66 2A 2B 99 14 1E 14 01`. The
  pump repeats this cycle until it is told otherwise.
- **Time limit.** OpenPump vents after the hold limit, "Vent any hold after at most": **1:00 to
  15:00 in 30-second steps, 5:00 by default**, counted on a monotonic clock from the moment the hold
  starts. It is the same limit the Hold button uses during a run.
- **The count** runs only while readings are at the target, within 5 % or 0.5 kPa, whichever is
  larger. After the count, the hold screen's title becomes "Hold complete — measure now", and the
  person has two minutes to measure.
- **The recorded vacuum.** A standardised reading records the vacuum the pump reported at the end
  of the count, the moment the hold standardises, not at saving: the pump can drift during the two
  minutes. The vacuum at saving is used only when no fresh reading arrived at the count's end.
  Photos taken in the two minutes record the same count-end vacuum. Two standardised readings are
  compared when these recorded vacuums are within 1 kPa. Readings saved before 0.10 keep the
  vacuum they were saved with.
- **Venting.** OpenPump vents if the telemetry goes quiet for 5 seconds, if the person leaves the
  app, or before another app's camera or share sheet opens. A foreground service with a wake lock
  keeps the app running, so the time limit fires even with the screen off.
- **The release step** sends STOP and moves on once the readings are at or below the release
  pressure, 5 kPa by default.
- **After a routine.** A routine can end straight into this hold for an after-measurement; this is
  off by default. It only happens when the run wasn't stopped, isn't a manual run, the link is ready,
  a before-measurement exists and the screen is visible. Otherwise the routine ends with a vent.

### The Tissue response test (τ)

The Tissue response test runs an identical short pull before and/or after the routine, each from a
vented cuff. It is off by default for new routines.

1. **Vent.** Send STOP, then wait until two readings in a row agree within 0.3 kPa at or below
   **2.0 kPa**, or until the pump has gone quiet on a live link after a low last reading. Give up
   after **20 seconds**.
2. **Pull.** 9 × Delete 0, then one Add: the test speed (60 % by default, 5–100), the test pressure
   (20 kPa by default, 5 kPa up to the ceiling) for **the test's length plus 30 seconds** (at most
   255), the same pressure again, one second; then START 0. With the defaults the Add is
   `66 2A 2B 99 14 4B 14 01`.
3. **Measure** for the test's length (45 seconds by default, 15–180). τ is the time from the START to
   the moment the pressure has covered **63.2 %** of the rise, from where it started to the highest
   point it reached. OpenPump reports no value, rather than a guess, when the data can't support one:
   gaps of more than a second, too few real readings, a start above 5 kPa, a pull that never reached
   pressure, and similar cases.

τ depends on the pump's rate, so it is a relative index, comparable only between identical tests.
Whether a pull from a vented cuff shows a real reading straight away, rather than 0.0, hasn't been
confirmed yet; the test refuses a result if it doesn't.

### Hardware validation and self-test

These are two tools for a rigid, sealed test vessel.

- **Validation** measures the pump against OpenPump's assumptions. It covers the vented baseline,
  the telemetry rate idle and under load, the vent rate, the deepest vacuum at the ceiling, the drift
  rate, the rise at several speeds, a restart after a stop, and a round trip of the preset table
  (write nine entries, List, compare byte for byte). It stops early if a reading goes more than
  2 kPa above the ceiling, if the telemetry is silent for 5 seconds, if a phase overruns, or if a
  vent between phases can't be confirmed. It reports numbers, not pass or fail.
- **Self-test** checks the app's wiring. It does a table round trip, checks where the override entry
  lands (append a ninth entry there, delete it, List to see that nothing moved), then runs a real
  session on the vessel with an adjustment, a revert, a Hold, +30 s, a skip and a batch boundary.

---

## 10. Stopping and venting

### STOP

`66 2A 2D` stops the pump **and releases the vacuum**. It is a release, not a pause. The vent rate
was measured once, at **about 4.68 kPa per second**, and OpenPump uses that figure as the fall it
expects. At that rate a vent from 20 kPa takes about 4.3 seconds, from 40 kPa about 8.5, and from
57 kPa about 12.2. A full vent has been seen to take about 3.4 seconds from STOP to open air. A STOP
sent to an idle pump is simply ignored.

Every stop in OpenPump, whether pressed by the person or sent automatically, goes through one
**vent check**, and a vent is believed only when the readings show it. For a full stop the target is
0 kPa; the release step of a measurement hold uses its release pressure instead.

### Why the readings, and not the write

Sending STOP only proves that the phone took the frame. A write without response gives no evidence
that the pump acted on it. **It isn't known yet whether the pump acknowledges a STOP with `2D 01`**
every time and in every state (under pressure, while it is already releasing, when idle). A stop that
was lost and one that worked look the same from the write alone, so OpenPump never waits for a
stop's reply and relies only on the pressure falling in the pump's own readings.

### How OpenPump confirms a vent

| Result | When |
|---|---|
| **Vented** | A real reading that arrived after the STOP was written is at or below the target plus 0.3 kPa. Or it has fallen from the reading just before the STOP by at least 2.5 kPa, more than the pump falls by itself while it holds (the Hold's 1 kPa step, a reading's jitter), and by at least half of what 4.68 kPa/s would give over the time since the STOP. That "before" reading must be real and no more than 600 ms old when the STOP was written. |
| **Probably vented** | At least 12 zero readings in a row, spanning at least 2.5 seconds with no gap over 1.2 seconds, on a link that is still live, after a last real reading of 6 kPa or less. If the pump has never reported a real reading at all this session, this also applies. OpenPump shows it as a strong sign, never as a confirmed vent. |
| **Not confirmed** | Neither of the above within 6 seconds (plus the time the write took). It is immediate if the link wasn't ready or the frame couldn't be queued. |
| **Waiting** | still inside the 6 seconds |

Silence (no readings at all) never counts as a vent. Zeros straight after a high reading never count
either: that is the sensor dropping out while the cuff is still under pressure. And a rise is never
mistaken for a fall, because nothing that arrives after the STOP is used as the "before" reading.

### When OpenPump can't confirm a vent

1. It re-checks every **300 ms**.
2. If the result is "not confirmed", it waits **2 seconds** and sends STOP again, with a fresh
   6-second window.
3. After **three** STOPs it stops polling but keeps the question open. It shows "The pump is not
   reporting a pressure fall" with two choices, **"I can see the cuff is vented"** and **"Keep
   trying"** (three more STOPs). The message tells the person to disconnect the tubing at the cuff if
   it isn't clearly vented.
4. If the person confirms by eye, OpenPump records it as their confirmation, not the telemetry's.
5. Until the vent is confirmed either way, OpenPump won't start anything new. Pressing START sends
   one fresh STOP, watches it, and offers the same "I can see…" button once the earlier check has
   given up.

On a live link that shows no fall, the three STOPs go out at about 0, 8 and 17 seconds, and the
question appears at about 25 seconds. On a link that is down, each attempt fails at once: the STOPs
go at about 0, 2.3 and 4.6 seconds, and the question appears at about 7 seconds.

### After the run has ended

A run can end in several ways: the person presses STOP, the plan finishes, the Hold reaches its
limit, the two-hour stop fires, or the link-loss stop goes out. Whichever it is, OpenPump keeps
watching the vent after the run is over. The foreground service stays up with a "Venting the pump"
notice until the readings show the vent. If the vent can't be confirmed, the service stays until
the person has seen the question in the app, and a "couldn't confirm" notice stays until the vent
is settled, by the readings or by the person. Every stopped run's summary says why it ended, for
example "Stopped: you ended the session", "Stopped: the link to the pump was lost, so the pump was
told to vent", "Stopped: the hold reached its limit (5:00)" or "Stopped: the two-hour limit". When
a limit ended the run (the hold's, the two-hour stop, or the pump refusing twice), the venting
notice gives the reason too. The reason is filed with the session, so it is still there when the
run is reopened from History.

**Resuming after STOP.** When the person stops a routine part-way (not a manual run, not on its last
step) and the link is still up, a short notice offers "Resume" for a few seconds ("Stopped and
vented at step N."). Resume rebuilds the run, writes the table again from that step and starts it,
the same way rejoining a run after the app closed does (below). Either way the parts are filed as one
session, and its summary says so: "Resumed once after STOP" or "Rejoined once after the app
closed". Only a stop the person made offers Resume; a limit or a lost link never does.

If a hold's stop, or an ended run's, couldn't be confirmed while the link was down and its retries
have run out, OpenPump sends a fresh STOP through the vent check as soon as the link comes back.

### When a stop question closes

Only three things close an open stop question: the readings showing the vent; the person confirming
it by eye; or a new, deliberate start of the pump, which cancels the old check as its first step.
That last rule stops a leftover retry from firing STOP into the next hold. It also stops a fall
during a new pull being mistaken for the old stop working. Just stopping the polling leaves the
question open. OpenPump then still refuses to start, and still sends a last stop when the app
closes.

### Two edge cases

- **A lost stop during the pump's own drop.** If a STOP is lost just as the pump begins its own drop
  to the lower setpoint, the pressure falls anyway, and OpenPump can take that fall for its vent.
  Requiring a `2D 01` reply would close this gap, but only if the pump replies to every STOP.
- **A false alarm on a busy phone.** A busy phone can report "not reporting a pressure fall" after a
  STOP that did vent, because the fall came between two checks and each check only counts readings
  after its own STOP. OpenPump accepts that false alarm, since it errs on the safe side; "Keep
  trying" clears it in a few seconds.

### A dropped link

Because the pump keeps running on its own, OpenPump treats a silent link as urgent:

| Time since the last reading | What OpenPump does |
|---|---|
| 5 seconds | shows "LINK LOST" ("The pump may still be running."), sounds an alarm, freezes the countdown, and cancels every pending timer (including the end of a Rest you added) |
| about 11 seconds (6 seconds after the warning) | sends STOP through the vent check (three tries, then the question) |
| telemetry returns | asks "Reconnected" with three choices: "Resume at step N", "Start over" or "End". Resume isn't offered while an automatic STOP hasn't yet been seen to vent. Nothing resumes by itself. |
| 60 seconds without an answer | ends the run and sends STOP |

"Resume" writes the table again from the running step and starts it after 600 ms, or sends the
adjustment in force again. The other phases (the measurement hold, the seal check, the guided start,
the Tissue response test) end and vent as soon as they have been silent for 5 seconds, since they
have nothing to resume. Reconnecting is done by hand, with the **Reconnect** button, apart from the
status-133 retry in section 2.

### When the app goes away

- A run lives in a foreground service with a wake lock, so leaving the screen or turning it off
  doesn't interrupt it.
- OpenPump has a single main screen. Launcher shortcuts, notifications and the widget bring back
  that same screen instead of opening a second one, so a run always has exactly one owner and a
  shortcut tapped mid-run doesn't end it. With incognito's disguised icon chosen, the launcher
  entry is one of four activity-aliases of `LauncherTrampoline` (the real one and one per
  disguise — Fitness log, Habits, Notes), exactly one enabled, and switching between them never
  interrupts a run: the alias is swapped with `setComponentEnabledSetting(DONT_KILL_APP)`, and the
  swap itself waits while anything unsafe is on screen.
- Incognito can neutralise a notice's wording on the lock screen ("Session needs attention now"),
  but never its urgency: the safety alerts (vent not confirmed, the app closed during a hold, the
  ring timer) are on their own `safety-alert` notification channel — `IMPORTANCE_HIGH`, sound and
  vibration on, `CATEGORY_ALARM` so Do Not Disturb's "alarms" setting still lets them through. They
  still ring and vibrate whatever the wording shows. The ring timer's own notice ("take it off")
  is a system alarm (`RingTimer`/`RingTimerReceiver`, exact where the phone allows it, inexact
  otherwise), so it still arrives if the app or its process is gone. The alarm keeps its end, its
  minutes and the session it belongs to, so if the process restarts while it is pending, that
  session's summary shows the running countdown again and "Took it off" still cancels the alarm.
- Leaving the app during a measurement hold vents the pump.
- If the screen is destroyed, or the app crashes, while the pump may be running, OpenPump sends the
  last stop described in section 2.
- If the app is force-stopped during a hold, nothing is left to vent the pump. The next time it
  opens, OpenPump tells the person to check the cuff.
- If the app closed during a routine, the next time it opens it asks "A run did not finish", with
  "Rejoin the run", "Stop the pump" or "Neither". Rejoining rebuilds the routine, writes the table
  again from the step it was on and starts it; stopping sends STOP through the vent check. The
  rejoined run is filed as one session with the part before it.

---

## 11. Limits OpenPump enforces

- **Pressure ceiling.** The user sets a ceiling of 7–57 kPa, 40 by default. OpenPump clamps every
  pull to the ceiling at the moment it is written, not just when a set is saved, so a ceiling
  lowered later still applies to stored routines. The lower pressure is always kept below the upper
  in routine and live presets.
- **One hold limit.** Every hold vents after "Vent any hold after at most": 1:00 to 15:00 in
  30-second steps, 5:00 by default. That covers the measurement hold, the hold a routine can end
  into, and the Hold button during a run. When the Hold button's limit is reached, the run ends as a
  STOP and is filed with the reason.
- **Other time limits.**
  - Guided start: ten minutes in all, and 60 seconds for an unanswered question.
  - Reconnect question: 60 seconds.
  - "At pressure only" timing can stretch a set to at most the longer of its planned time and
    twice its planned time capped at 20 minutes (section 12).
- **Two-hour stop.** Every run ends, with a vent, after two hours of time under a seal, and is filed
  with the reason "Stopped: the two-hour limit". While pressure is being commanded, zero or missing
  readings count toward that time, and so do pulls made before the run (the guided start, the seal
  check).
- **Start only when clear.** OpenPump won't start a run, a hold or a test while the last stop is
  unconfirmed, or while another phase is still using the pump. Nor will it start a routine with no
  holds in it: START says "(the routine's name) has nothing to run" and offers "Rebuild from the plan" (for a
  plan's routine) or "Open Routines", with Cancel.
- **Values the wire can carry.** Sets are limited to what can actually be sent: ramp holds to 255
  seconds, drop times to 255 seconds, ramps to nine steps.
- **Validation's overshoot stop.** During hardware validation only, a reading more than 2 kPa above
  the ceiling ends the routine.

---

## 12. Timing

### By the clock (the default)

Each step runs its planned duration from its START. A Hold, or a rest on demand, freezes the
countdown by pushing the deadline out while it lasts. While the link is lost, the elapsed time
freezes too.

### At pressure only

This setting, off by default, times each set by the time actually spent at pressure:

- In the **hold half** of each cycle, the set's clock moves only while a fresh real reading is at or
  above **the line**. In the **drop half**, it moves through the set's planned drop time and no
  further.
- **The line.** For runs scored by OpenPump's training plan, it is the plan's counting line when the
  step's pull reaches it: the level's floor, less any reduction, less a 2 % tolerance by default.
  Otherwise it is the pull less a band of 5 % (at least 0.5 kPa).
- **The limit.** A set can stretch to at most the longer of its planned time and twice its planned
  time capped at 20 minutes. Then it ends on time and says it didn't get its full time at pressure.
- The pump's own hold is untouched. It keeps cycling; only the app's deadline moves.

This mode exists because the pump pulls once and coasts, so part of every hold sits below the
target.

### Counting time under pressure

- **Net time under pressure**, the figure OpenPump records, counts only real readings at or above
  the counting line, and leaves out the drop half of each cycle. Gaps and zero readings are never
  bridged.
- **Gross sealed time** is the time with a real reading under a seal.
- **For the two-hour stop only**, a zero or missing reading counts as sealed while pressure is being
  commanded, because the app can't tell a dead sensor from a vented cuff.

On a pump that pulls once and coasts, a training hold sits right at the level's floor, so only about
its first half-minute counts as time under pressure. A correction will follow once the coast rate
has been measured.

### Which clock

The vent check, the holds and their limits, the last stop and every telemetry timestamp use a
monotonic clock. The run's step deadlines use the wall clock, guarded so that a clock set back never
produces a negative time.

---

## 13. A worked example

This example is worked out from OpenPump's own rules, not captured from a pump. The pump's replies
and readings aren't shown.

### The routine

The settings are OpenPump's defaults: a ceiling of 40 kPa, no seal check, no Tissue response test,
no measurement hold at the end, and timing by the clock. The only exception is the guided start,
which is on by default and is turned off here; the last part of this section adds it back. The link
stays healthy, and nobody changes anything during the run.

The routine has one stage with two sets:

| Set | Type | Pull | Drop | Hold | Drop time | Speed | Runs |
|---|---|---|---|---|---|---|---|
| A "Long hold" | fixed | 20 kPa | 10 kPa | **300 s** | 20 s | 60 % | 320 s |
| B "Climb" | ramp, 7 steps | 14 → 26 kPa | 7 → 13 kPa | 20 s | 10 s | 60 → 90 % | 420 s |

A ramp can't carry a hold longer than 255 seconds, so the long hold goes on the fixed set. The
ramp's 420 seconds are whole cycles: 7 steps × 2 cycles × (20 + 10) s.

### From sets to steps

| Step | From | Working | Pull | Drop | Hold | Drop time | Speed | Plays for |
|---|---|---|---|---|---|---|---|---|
| 0 | A, chunk | 320 / (300 + 20) = 1 rep; 300 > 255, so a 255 s chunk | 20 | 20 | 255 | 1 | 60 | 255 s |
| 1 | A, last | 300 − 255 = 45 s of hold left, then the drop | 20 | 10 | 45 | 20 | 60 | 45 + 20 = 65 s |
| 2 | B 1/7 | f = 0/6 | 14 | 7 | 20 | 10 | 60 | 2 × (20 + 10) = 60 s |
| 3 | B 2/7 | f = 1/6: 14 + 12/6, 7 + 6/6, 60 + 30/6 | 16 | 8 | 20 | 10 | 65 | 60 s |
| 4 | B 3/7 | f = 2/6 | 18 | 9 | 20 | 10 | 70 | 60 s |
| 5 | B 4/7 | f = 3/6 | 20 | 10 | 20 | 10 | 75 | 60 s |
| 6 | B 5/7 | f = 4/6 | 22 | 11 | 20 | 10 | 80 | 60 s |
| 7 | B 6/7 | f = 5/6 | 24 | 12 | 20 | 10 | 85 | 60 s |
| 8 | B 7/7 | f = 6/6 | 26 | 13 | 20 | 10 | 90 | 60 s |

That makes nine steps. Steps 0–7 fill the first batch of eight entries, and step 8 needs a second
batch.

### From steps to bytes

Each Add is `66 2A 2B` followed by the speed code, upper, upper hold, lower and lower hold. As each is
written, the upper is clamped to the ceiling (40) and the lower to at least 1 below the upper.

| Step | Speed code | Upper | Upper hold | Lower, after clamping | Lower hold | Frame |
|---|---|---|---|---|---|---|
| 0 | 60 % → 153 = `99` | 20 = `14` | 255 = `FF` | 20 → **19** = `13` | 1 = `01` | `66 2A 2B 99 14 FF 13 01` |
| 1 | `99` | `14` | 45 = `2D` | 10 = `0A` | 20 = `14` | `66 2A 2B 99 14 2D 0A 14` |
| 2 | `99` | 14 = `0E` | 20 = `14` | 7 = `07` | 10 = `0A` | `66 2A 2B 99 0E 14 07 0A` |
| 3 | 65 % → 166 = `A6` | 16 = `10` | `14` | 8 = `08` | `0A` | `66 2A 2B A6 10 14 08 0A` |
| 4 | 70 % → 179 = `B3` | 18 = `12` | `14` | 9 = `09` | `0A` | `66 2A 2B B3 12 14 09 0A` |
| 5 | 75 % → 191 = `BF` | 20 = `14` | `14` | 10 = `0A` | `0A` | `66 2A 2B BF 14 14 0A 0A` |
| 6 | 80 % → 204 = `CC` | 22 = `16` | `14` | 11 = `0B` | `0A` | `66 2A 2B CC 16 14 0B 0A` |
| 7 | 85 % → 217 = `D9` | 24 = `18` | `14` | 12 = `0C` | `0A` | `66 2A 2B D9 18 14 0C 0A` |
| 8 | 90 % → 230 = `E6` | 26 = `1A` | `14` | 13 = `0D` | `0A` | `66 2A 2B E6 1A 14 0D 0A` |

### The byte sequence

`t` is the number of seconds since the person confirmed START. Frames listed together are queued
together and leave one at a time, each after the previous one's write is done.

```
t (s)    frame                      meaning
0.0      66 2A 2A 00   (x9)         clear the table: delete entry 0, nine times
         66 2A 2B 99 14 FF 13 01    entry 0: A chunk    60 %  20/19 kPa  255/1 s
         66 2A 2B 99 14 2D 0A 14    entry 1: A last     60 %  20/10 kPa   45/20 s
         66 2A 2B 99 0E 14 07 0A    entry 2: B 1/7      60 %  14/7  kPa   20/10 s
         66 2A 2B A6 10 14 08 0A    entry 3: B 2/7      65 %  16/8
         66 2A 2B B3 12 14 09 0A    entry 4: B 3/7      70 %  18/9
         66 2A 2B BF 14 14 0A 0A    entry 5: B 4/7      75 %  20/10
         66 2A 2B CC 16 14 0B 0A    entry 6: B 5/7      80 %  22/11
         66 2A 2B D9 18 14 0C 0A    entry 7: B 6/7      85 %  24/12
0.6      66 2A 2C 00                start entry 0 (after the 600 ms pause)
255.6    66 2A 2C 01                start entry 1
320.6    66 2A 2C 02                start entry 2 (the ramp begins)
380.6    66 2A 2C 03
440.6    66 2A 2C 04
500.6    66 2A 2C 05
560.6    66 2A 2C 06
620.6    66 2A 2C 07                start entry 7 (B 6/7)
680.6    66 2A 2A 00   (x9)         second batch: clear (this also deletes the running entry 7)
         66 2A 2B E6 1A 14 0D 0A    entry 0: B 7/7      90 %  26/13 kPa   20/10 s
681.2    66 2A 2C 00                start entry 0 (after the 600 ms pause)
741.2    66 2A 2D                   STOP: the routine is over and the pump vents
```

That is 37 frames in all. The pump answers each Delete with `2A 01`, each Add with `2B 01` and each
START with `2C 01`, and it sends telemetry throughout.

**After the STOP.** From about 26 kPa the vent takes about 5.6 seconds. The vent check normally sees
the fall within the first second or two (a second after the STOP it needs about 2.3 kPa of fall) and
reports "vented". If it sees no fall, it sends STOP again at about 8 and 17 seconds, and asks the
person at about 25 seconds. Meanwhile the "Venting the pump" notice stays up (section 10).

**At the batch boundary**, the clear at 680.6 s deletes the entry that is running. The pump keeps
cycling that step through the 600 ms before the next START (section 7), and
OpenPump's simulated pump does the same.

### With the guided start on

With the guided start on (the default), these frames come first:

```
66 2A 2A 00   (x9)         clear the table
66 2A 2B FF 11 FF 11 01    100 %, 17/17 kPa, 255/1 s
66 2A 2C 00                start the guided pull
```

The routine's frames above follow once fresh readings have stayed at or above 17 kPa for two
seconds. The routine's first table write then deletes the guided pull while it is still running.

---

## 14. Still to be confirmed

These have not been established on the pump yet:

- whether the pump acknowledges every STOP with `2D 01`, in every state;
- what the pump does when its table is rewritten in the middle of a step: keep the step, stop and
  vent, or pick up whatever now sits in its slot;
- whether the pump plays two adjacent presets without a break;
- how fast the pressure drifts down during a long hold;
- the vent rate beyond its one measurement of about 4.68 kPa/s;
- whether a pull from a vented cuff shows a real reading straight away, rather than 0.0;
- what STOP does to the stored table, and what an Add to a full table does;
- the format of Set time (`0x25`), and the meaning of the fourth telemetry field;
- how a full 46-byte table dump arrives at the default MTU.

---

## 15. Licence

The facts in this guide (UUIDs, byte layouts, opcodes, ranges, timings and behaviour) describe how
the pump behaves over the air. You are free to use them to build your own app. Nothing here comes
from the maker's software.

OpenPump's source code is licensed under the GNU Affero General Public License v3.0. If you copy or
adapt OpenPump's code, your app becomes a derived work and must be released under the AGPL too, with
its complete source offered to its users. This guide describes behaviour rather than reproducing
code, so it can be used for an independent implementation.

---

## Appendix A. The simulated pump

OpenPump's practice mode models the pump in software and plugs it in beneath the Bluetooth layer,
so the real queue, reply matching and vent check all run against it. It advances by the real time
that has passed, so a two-minute hold takes two minutes even on a busy phone. It makes a useful
starting point for your own tests. Its pull, drop and drift rates are its own estimates, not
measurements.

| Behaviour | The simulated pump |
|---|---|
| Table | 9 entries; Add appends and replies; Delete closes the gap and replies |
| Add to a full table, or a short Add | stores nothing, no reply |
| An all-zero Add | replies, stores nothing, as the pump does |
| Delete of an entry that doesn't exist | replies, does nothing |
| Start of an entry that doesn't exist | refuses, `2C FD`, and carries on with what it was running, as the pump does |
| A lost reply | tests can make it lose chosen replies while still carrying the command out, as the pump does |
| Delete of the running entry | keeps cycling it, as the pump does |
| Start | runs the entry from the upper phase, pulling |
| Pull | 3.2 kPa/s × the speed fraction, to the upper setpoint, **once** per upper phase |
| At the setpoint | drifts down at 0.014 kPa/s |
| Drop | 5 kPa/s toward the lower setpoint |
| Stop | vents at 4.68 kPa/s to zero, then replies |
| List | `29` + 5 bytes per entry, no count byte |
| Telemetry | every 240 ms: `#AUTO` while running, `#IDLE` otherwise; negative deci-kPa; `0` at zero; the running entry's speed code, else 0; flag `0` |

---

## Appendix B. Constants at a glance

| What | Value |
|---|---|
| Write timeout | 400 ms |
| Refusals before a frame is dropped | 3 |
| Pause after a table write, before START | 600 ms |
| Scan timeout; collection window | 15 s; 2.5 s |
| Connect attempts | after 500, 1200 and 1800 ms |
| Link lost; automatic STOP; STOP retry | 5 s; 6 s after that (about 11 s after the last reading); 2 s |
| Vent window; re-check; STOPs before asking; "before" reading age | 6 s; 300 ms; 3; 600 ms |
| Vent rate | 4.68 kPa/s (measured once) |
| "Probably vented" | 12 zero readings, over 2.5 s, gaps under 1.2 s, last real reading ≤ 6.0 kPa |
| Vent noise floor (at the target); least fall; rate tolerance | 0.3 kPa; 2.5 kPa; half the expected fall |
| Last stop: grace; limit | 250 ms; 1.5 s |
| START reply window; latest reply; pending list | 800 ms; 5 s after the write (the horizon); 16 entries, each never written dropped after 15 s |
| Converging STARTs after a live change gets no reply | 3, then "the pump isn't answering" |
| Live-change settle | 400 ms |
| +30 s | 30 s |
| Early-timer slack | 1.5 s |
| Ceiling | 7–57 kPa, default 40 |
| Hold limit (every hold, including Hold during a run) | 1:00–15:00 in 30 s steps, default 5:00 |
| Measuring window after the count | 2 min |
| Guided start | 17 kPa or the ceiling if lower; 2 s at target; asks at 30 s; 10 min limit; 60 s for an answer |
| Seal check | 20 kPa; 45 s hold (40–120); 25 s to level off; 10 s of drift |
| Tissue response test | 20 kPa; 60 %; 45 s (+30 s on the hold); starts at ≤ 2.0 kPa; 20 s to vent |
| Rest check | re-STOP if above 2.0 kPa after 3 s; at most every 2 s |
| Reconnect question | 60 s |
| Two-hour stop | 7200 s under a seal, checked every 10 s |
| Validation overshoot stop | ceiling + 2 kPa |

---

## Appendix C. Where it lives in OpenPump

Paths are relative to the repository root.

| Topic | OpenPump source |
|---|---|
| Protocol summary | [docs/protocols/zd21.md](protocols/zd21.md) |
| Frames, speed code, telemetry parsing | [Proto.java](../core/src/main/java/org/openpump/Proto.java) |
| Scanning, connecting, the write queue, the last stop | [PumpLink.java](../app/src/main/java/org/openpump/PumpLink.java), [PumpMatch.java](../core/src/main/java/org/openpump/PumpMatch.java), [WritePairing.java](../core/src/main/java/org/openpump/WritePairing.java), [LastStop.java](../core/src/main/java/org/openpump/LastStop.java) |
| Connect screen states | [Conn.java](../core/src/main/java/org/openpump/Conn.java) |
| Routines, sets, ramps, long holds, the plan | [Model.java](../core/src/main/java/org/openpump/Model.java) (`Set`, `Preset`, `plan`) |
| Table writes, starts, sequencing, Hold, rests | [SessionActivity.java](../app/src/main/java/org/openpump/SessionActivity.java) (`uploadBatch`, `playPreset`, `sendStartSlot`, `sendOverridePreset`, `enterHold`) |
| Live-change rules, the override entry, the clamps | [RunEdit.java](../core/src/main/java/org/openpump/RunEdit.java), [LiveEdit.java](../core/src/main/java/org/openpump/LiveEdit.java), [SetShift.java](../core/src/main/java/org/openpump/SetShift.java), [HoldCarryOn.java](../core/src/main/java/org/openpump/HoldCarryOn.java), [QuickAdjust.java](../core/src/main/java/org/openpump/QuickAdjust.java) |
| Replies, convergence, when the table may be written | [RunControl.java](../core/src/main/java/org/openpump/RunControl.java), [LiveLink.java](../core/src/main/java/org/openpump/LiveLink.java) |
| The Hold's limit during a run | [InRunHold.java](../core/src/main/java/org/openpump/InRunHold.java) |
| Vent confirmation | [Session.java](../core/src/main/java/org/openpump/Session.java) (`ventWatchResult`), [SessionActivity.java](../app/src/main/java/org/openpump/SessionActivity.java) (`VentWatcher`, `startVentWatch`) |
| Watching the vent after a run or hold, the venting notice | [HoldForeground.java](../core/src/main/java/org/openpump/HoldForeground.java), [RunService.java](../app/src/main/java/org/openpump/RunService.java) |
| Why a run stopped | [RunStopReason.java](../core/src/main/java/org/openpump/RunStopReason.java) |
| A rejoined or resumed run filed as one session | [RunParts.java](../core/src/main/java/org/openpump/RunParts.java), [RunRejoin.java](../core/src/main/java/org/openpump/RunRejoin.java) |
| Refusing to start on an unconfirmed stop | [Handoff.java](../core/src/main/java/org/openpump/Handoff.java) |
| Link loss and reconnect | [SessionActivity.java](../app/src/main/java/org/openpump/SessionActivity.java) (`tickRun`, `resumeAfterReconnect`) |
| Timing at pressure, set time limits | [TupClock.java](../core/src/main/java/org/openpump/TupClock.java), [PlannedTime.java](../core/src/main/java/org/openpump/PlannedTime.java) |
| Two-hour stop | [Plan.java](../core/src/main/java/org/openpump/Plan.java), [Session.java](../core/src/main/java/org/openpump/Session.java), [PreRunHold.java](../core/src/main/java/org/openpump/PreRunHold.java) |
| Guided start target and limits | [PreRunHold.java](../core/src/main/java/org/openpump/PreRunHold.java) |
| Measurement hold window | [HoldWindow.java](../core/src/main/java/org/openpump/HoldWindow.java) |
| Tissue response test | [Tau.java](../core/src/main/java/org/openpump/Tau.java) |
| Validation and self-test | [Validate.java](../core/src/main/java/org/openpump/Validate.java), [HwTest.java](../core/src/main/java/org/openpump/HwTest.java) |
| Manual runs | [Manual.java](../core/src/main/java/org/openpump/Manual.java) |
| Simulated pump | [SimPump.java](../core/src/main/java/org/openpump/SimPump.java) |
| One main screen, shortcuts | [ShortcutTrampoline.java](../app/src/main/java/org/openpump/ShortcutTrampoline.java), `app/src/main/AndroidManifest.xml` |
| Incognito: the three disguises, launcher trampoline, the lock asked under the disguise, quick hide, hiding from recent apps | [Incognito.java](../core/src/main/java/org/openpump/Incognito.java), [LauncherTrampoline.java](../app/src/main/java/org/openpump/LauncherTrampoline.java), [IncognitoShell.java](../app/src/main/java/org/openpump/IncognitoShell.java), [LockHost.java](../app/src/main/java/org/openpump/LockHost.java) |
| Safety-alert notification channel, the ring timer as a system alarm | [RunService.java](../app/src/main/java/org/openpump/RunService.java) (`CHANNEL_ALERT_ID`), [RingAlarm.java](../core/src/main/java/org/openpump/RingAlarm.java), [RingTimer.java](../app/src/main/java/org/openpump/RingTimer.java), [RingTimerReceiver.java](../app/src/main/java/org/openpump/RingTimerReceiver.java) |
