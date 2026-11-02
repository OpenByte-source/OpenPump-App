# Adding support for another pump

This page has two halves. The first is for someone who owns a pump OpenPump does not
support yet: you do not need to know anything about Bluetooth. The second is for a
maintainer who already has an agreed protocol description for a pump and is writing its
driver.

## Where OpenPump is today

OpenPump drives one pump protocol, the ZD21 (inside the Epic Hydro PE Pump). The app still
knows ZD21 details in many places, so a second pump cannot simply be plugged in yet.

The fix is the **driver seam**: one interface every pump driver implements, and a safety
supervisor every command passes through. It is designed, not built. The design is
[docs/design/driver-seam.md](design/driver-seam.md) and the plan is
[docs/design/driver-seam-plan.md](design/driver-seam-plan.md).

The ZD21 is the worked example all the way through. Everything said about it here comes
from [docs/protocols/zd21.md](protocols/zd21.md).

---

## If you own another pump

Open a **New pump** issue. On GitHub: **Issues › New issue › New pump request**. The form
asks for:

- **Make and model** (required);
- **The name it advertises over Bluetooth**, exactly, capitals and all. Any Bluetooth
  scanner app on a phone shows it. A pump that is connected to an app stops advertising, so
  nothing else can find it: close other apps first, and make sure OpenPump is not connected;
- **Whether you can help test**, and what you have to test with;
- **Whether it reports pressure back, and how often**, if you know.

Your pump's Bluetooth address and its serial number identify it. Keep them to yourself. The
model and the firmware version are fine to share.

### What you can offer

- **Willingness to test.** Someone with the pump has to run a driver on the real device
  before anyone can use it. You would run test builds on the bench, follow the safety rules
  below and report what happened.
- **A rigid, sealed container made to hold vacuum.** Many pumps' own cylinder works with its
  opening sealed. Nothing that can collapse or shatter.
- **A vacuum gauge,** if you have one. It lets you check the pump's own readings.
- **What you can find out by hand.** Whether the pump has a release valve or a quick-release
  fitting, and whether switching it off releases the vacuum. On some pumps it does not.

An issue records that a pump is wanted. It is not a promise that the pump will be supported.

---

## Safety first

Read this before you test anything. The pump pulls a vacuum, and a driver that has not been
verified can go wrong in ways nobody has seen yet.

1. **Never on the body.** Not while testing, not "just to check". Your pump does not go on a
   body with OpenPump until its driver has passed the conformance kit (planned — see the
   design) *and* someone has run the hardware checklist on the real pump and signed it off
   ([ADR 0006](adr/0006-unverified-drivers-hidden.md)).
2. **Bench only.** Connect the hose to a rigid, sealed container made to hold vacuum.
3. **Find the stop first.** Before you try anything else, know how to stop the pump and see
   it work from every state.
4. **Low pressures.** Stay under 10 kPa (about 3 inHg) until the stop has worked every time.
5. **A hand on the vent.** Know how to let air back in without the phone: the pump's release
   valve, a quick-release fitting, or pulling the hose off.
6. **Only send what the protocol description documents.** Never write to anything named OTA,
   DFU, firmware or update: that can put the pump into update mode or break it.
7. **One change at a time.** Write down what you did and when.
8. **Stop when surprised.** If the pump does something you did not expect, stop it, vent it
   and write down what happened before you go on.

---

## If you are writing a driver

Driver work starts from a protocol description the maintainers have agreed on. A second pump
also needs the driver seam first. When it lands, a pump becomes one driver, made of:

1. **its protocol doc**, in the shape below;
2. **a driver** that recognises the pump when scanning, turns the app's commands into its
   frames, turns its frames into pressure readings with "no measurement" said plainly, and
   makes stop always vent;
3. **a list of what the hardware can do** — pressure range, longest hold, programs it can
   store, whether it reports pressure, whether its stop vents. The app refuses anything
   outside it;
4. **a small simulator** of the pump, so everyone can work on it without owning one;
5. **a pass of the conformance kit** (planned): stops vent and are confirmed, the ceiling
   always holds, a lost link stops the pump, garbage data never crashes the app;
6. **a pull request.** The driver ships hidden, as experimental, behind a developer switch,
   until someone with the pump runs the hardware checklist on the real device and signs it
   off, with two reviews, one from a safety maintainer
   ([ADR 0006](adr/0006-unverified-drivers-hidden.md)).

### What the protocol doc has to say

Follow the shape of [zd21.md](protocols/zd21.md): every section below has a filled-in
counterpart there.

**Commands**

- **Frames.** The header, the command code, the payload, any checksum. Every ZD21 command
  starts `66 2A`, and its third byte is the opcode: `2C` starts a preset, `2D` stops.
- **Pressure.** The unit and the range. The ZD21 sets pressures in whole kPa. Some pumps take
  a level (1 to 9), not a pressure. Then write down what pressure each level reaches. A level
  is not a pressure.
- **Times and holds.** The unit and the largest value. A one-byte field can hold at most 255,
  which is why one ZD21 preset's hold stops at 255 seconds (OpenPump builds a longer hold from
  several presets).
- **Speed.** A plain percentage or a code. The ZD21 turns 0–100 % into a 0–255 code:
  `code = (pct × 255 + 50) / 100`.
- **Programs stored on the pump.** Some pumps keep a table of programs and have commands to
  add, delete, list and start them. The ZD21 has a table of nine; Add appends to it and Delete
  closes the gap. Others take every setting live.
- **Answers.** What comes back after each write, and what happens when the pump does not
  accept a command. The ZD21 answers Add, Delete and Start with `<opcode> 01` and List with
  its table; whether it answers every stop is still being checked. A Start of an empty slot is
  answered `<opcode> FD` (`2C FD`), and the pump carries on with what it was running. It
  quietly doesn't store an all-zero preset.

**Pressure readings**

- **Format.** Text or binary, and the layout. The ZD21 sends text: `#AUTO,-189,179,0`.
- **Unit and scale.** On the ZD21, `-189` is 18.9 kPa: tenths of a kPa.
- **Sign.** A vacuum can be sent as a negative number (the ZD21 does), a positive one, or as
  absolute pressure (about 101 kPa of air minus the vacuum).
- **How often.** The ZD21 sends about four a second.
- **On its own, or on request.** Whether the pump sends readings by itself once subscribed, or
  only when asked.

**What "no measurement" looks like**

OpenPump never treats a missing reading as a pressure. On the ZD21, a reading of 0.0 means
"not measuring", **not** "no vacuum" ([zd21.md](protocols/zd21.md), Telemetry). Code that took
that 0.0 as a real zero would draw slopes and add up doses from readings that never happened.

Every pump has its own way of saying it is not measuring. Do not assume a zero means ambient
pressure. The doc says what the readings show:

- just after connecting, before any start;
- running with the hose open to the air, so no vacuum can build;
- during a stop, as it vents, and a minute afterwards;
- while the pump is starting up.

**The stop, and whether it vents**

- The frame. The ZD21's is `66 2A 2D`.
- Whether the pressure falls quickly (the pump opened a valve: it **vents**) or stays where it
  was (it **paused**, still holding vacuum). OpenPump's rule is "stop means vent"
  ([SAFETY.md](../SAFETY.md)). A stop that only pauses is not a stop OpenPump can use.
- How fast it vents: the fall in pressure divided by the time, over the first few seconds. The
  ZD21 vents at about 4.68 kPa per second.

**Other behaviour**

- **Keeping on with no phone.** Whether the pump keeps running with no app connected matters a
  great deal. The ZD21 does: once a preset is started it keeps cycling it by itself, and only
  the phone moves it on or stops it. That is why OpenPump treats a lost Bluetooth link as
  dangerous.
- **Holding.** Whether the pump keeps topping the pressure up at a steady setting, or pulls
  once and lets it drift down. The ZD21 does the second: it coasts.
- **Limits.** The highest pressure, the longest hold, the number of stored programs.
- **Silence.** Whether the pump stops by itself if the phone goes quiet.
- **Power.** Whether switching it off releases the vacuum.
- **Firmware.** The version.

A skeleton to start from, as `docs/protocols/<your-pump>.md`:

```markdown
# <ADVERTISED_NAME> — Bluetooth LE protocol

What OpenPump would send to and receive from a <pump>.

Status: **unverified** — <date>, firmware <version>.

## Discovery and connection

| Item | Value |
|---|---|
| Advertised name | |
| Service | |
| Write characteristic | (with or without response) |
| Notify characteristic | |
| Enable telemetry | (which descriptor, which value) |

## Commands (phone → pump)

Frame layout: <header, command code, payload, checksum?>. The pump answers with <…>.

| Code | Name | Payload | Notes |
|---|---|---|---|
| | Start | | |
| | Stop | | **Vents** / pauses — say which |

- Pressures in commands: <unit, range>.
- Times: <unit, range>.
- Speed: <how it is encoded>.

## Telemetry (pump → phone)

<how often; text or binary; the layout, with one real example and what it means>

- Pressure: <unit, scale, sign>.
- **No measurement** looks like: <what it shows, in which situations>.

## Behaviour worth knowing

- Stop <vents at about X kPa/s, measured / only pauses>.
- During a hold the pump <coasts / tops up>.
- Once started, it <keeps running on its own / stops when the phone goes quiet>.
- Switching it off <releases / keeps> the vacuum.

## Open questions

- <anything not confirmed>
```

Put the open questions in. A doc that says what it does not know is more useful than one that
guesses.

### From unverified to verified

A new doc says **unverified**. [zd21.md](protocols/zd21.md) says **verified** because it has
been checked against a real device. Bench checks of a new driver follow the safety rules above,
in this order:

1. With the pump idle, send the stop. Nothing should happen, and nothing bad should.
2. Start the pump at its lowest setting and send the stop. The pressure must fall. Repeat until
   it works every time.
3. Start the pump at its lowest setting, drop the connection and note whether the pump keeps
   running. Then reconnect and stop it.
4. Only then try higher settings, a hand on the vent, with the stop straight after.

Passing all of this does not put a driver in front of users. That takes the hardware checklist,
signed off ([ADR 0006](adr/0006-unverified-drivers-hidden.md)).

---

## Ground rules

- Contribute only code and text you have the right to license under the AGPL-3.0.
- A pump that cannot report pressure cannot confirm a vent. Support for it will be limited or
  declined.
- A pump whose stop does not vent cannot keep SAFETY.md's "stop means vent". The same applies.
- Nothing goes on a body until the driver has passed the conformance kit and the hardware
  checklist has been signed off.
