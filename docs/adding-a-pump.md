# Adding support for another pump

This guide is for someone who owns a pump OpenPump does not support yet. You are
comfortable with a computer. You do not need to know anything about Bluetooth.

By the end you will have three things:

1. a **protocol doc** for your pump, in the same shape as
   [docs/protocols/zd21.md](protocols/zd21.md);
2. **captures** of the maker's app talking to your pump, cleaned of anything personal;
3. a **New pump** issue that shares both.

That is the part only a pump owner can do. Somebody else can write the code.

## Where OpenPump is today

OpenPump drives one pump protocol, the ZD21 (inside the Epic Hydro PE Pump). The app still
knows ZD21 details in many places, so a second pump cannot simply be plugged in yet.

The fix is the **driver seam**: one interface every pump driver implements, and a safety
supervisor every command passes through. It is designed, not built. The design is
[docs/design/driver-seam.md](design/driver-seam.md) and the plan is
[docs/design/driver-seam-plan.md](design/driver-seam-plan.md).

So the work in this guide does not make OpenPump drive your pump. It makes it possible
for someone to write a driver for it once the seam lands.

The ZD21 is the worked example all the way through. Everything said about it here comes
from [docs/protocols/zd21.md](protocols/zd21.md).

---

## Safety first

Read this before you touch anything. The pump pulls a vacuum. Commands you send by hand
can go wrong in ways the maker's app never would.

1. **Never on the body.** Not while capturing, not while experimenting, not "just to
   check". Your pump does not go on a body with OpenPump until its driver has passed the
   conformance kit (planned — see the design) *and* someone has run the hardware
   checklist on the real pump and signed it off
   ([ADR 0006](adr/0006-unverified-drivers-hidden.md)).
2. **Bench only.** Connect the hose to a rigid, sealed container made to hold vacuum —
   many pumps' own cylinder works with its opening sealed. Nothing that can collapse or
   shatter.
3. **Find the stop first.** Before you send any command of your own, know which command
   stops the pump and see it work from every state.
4. **Low pressures.** Use the lowest settings the maker's app offers. When you start
   sending your own frames, stay under 10 kPa (about 3 inHg) until the stop has worked
   every time.
5. **A hand on the vent.** Know how to let air back in without the phone: the pump's
   release valve, a quick-release fitting, or pulling the hose off. Find out whether
   switching the pump off releases the vacuum. On some pumps it does not.
6. **Only send what you have seen.** Write only to the characteristic the maker's app
   writes to, and only frames shaped like ones you captured. Never write to anything
   named OTA, DFU, firmware or update: that can put the pump into update mode or break
   it.
7. **One change at a time.** Write down what you did and when.
8. **Stop when surprised.** If the pump does something you did not expect, stop it, vent
   it and write down what happened before you go on.

---

## What you need

- **The pump**, charged, and the **maker's app** on an Android phone.
- **Developer options** on that phone (step 2 shows how).
- **nRF Connect for Mobile**, a free app from Nordic Semiconductor. It shows what a
  Bluetooth device offers and lets you watch it.
- A computer with **Wireshark** (free) to read the capture, and Android's **platform
  tools** (free, for the `adb` command) to copy the capture off the phone.
- A **notebook** or text file for a timeline, and a clock.
- A **rigid sealed container** for the bench (see safety). A vacuum gauge helps if you
  have one: it lets you check the pump's own readings.

If the maker's app only runs on an iPhone: Apple offers a Bluetooth logging profile for
iOS and a Mac tool, PacketLogger, that records the same kind of log. Wireshark opens
PacketLogger files, so the rest of this guide still applies.

If your phone will not produce a log at all, a small USB Bluetooth sniffer (for example a
Nordic nRF52840 dongle with Nordic's sniffer software) can capture the traffic over the
air instead. You probably will not need one.

---

## Step 1 — Meet the pump with nRF Connect

A pump that is connected to one app stops advertising, so nothing else can find it. Close
the maker's app completely first, and make sure OpenPump is not connected.

1. Open nRF Connect and scan. Find your pump in the list.
2. Write down the **advertised name** exactly, capitals and all. Note any service UUIDs
   the advert lists. The address (six pairs like `C4:7F:…`) identifies your pump: keep it
   for yourself, do not publish it.
3. **Connect.** nRF Connect lists the pump's services. Some are standard:
   - Generic Access (`1800`) and Generic Attribute (`1801`) — every device has these;
   - Device Information (`180A`), if present, can tell you the model and firmware
     version. Those are worth writing down. A serial number is not for sharing.

   The maker's own service is usually a short UUID like `FFF0` or `FFE0`, or a long one.
4. Open the maker's service. For each characteristic, note its UUID and its properties:
   - **WRITE** or **WRITE NO RESPONSE** — where commands go;
   - **NOTIFY** or **INDICATE** — where the pump talks back.

   Some pumps use one characteristic for both.
5. **Subscribe** to the notify characteristic (the arrows icon). Watch for a minute with
   the pump idle. Does anything arrive by itself? How often? Does it show as readable
   text, or only as hex?
6. Disconnect.

Do not write anything from nRF Connect yet.

**The ZD21 at this step:** name `ZD21_PUMP`; service `fff0`; write `fff1`; notify `fff4`.
After subscribing, about four lines a second arrive, like `#AUTO,-189,179,0`.

---

## Step 2 — Capture the maker's app

Android can record every Bluetooth packet the phone sends and receives. This is the
**Bluetooth HCI snoop log**.

1. Turn on Developer options: **Settings › About phone**, tap **Build number** seven
   times. (Menus differ a little between phone makers.)
2. In **Developer options**, turn on **USB debugging** and **Enable Bluetooth HCI snoop
   log**. If it offers a choice, pick **Enabled**, not **Filtered**: a filtered log can
   leave out the data you need.
3. Turn Bluetooth off and on again so logging starts. Some phones need a restart.
4. Switch off or disconnect other Bluetooth things near the phone — earbuds, a watch, the
   car. Everything the phone does over Bluetooth goes into the log. Less noise means less
   to clean out later.
5. Start the timeline. Note the phone's clock time for every action.
6. Open the maker's app, connect, and do **one thing at a time, ten seconds apart**:
   - connect, then wait 30 seconds doing nothing;
   - start at the lowest setting; wait 30 seconds;
   - change the pressure by one step; wait;
   - stop; wait 30 seconds while the pressure falls;
   - disconnect.

   A timeline line looks like this: `14:02:10 start, level 1 (app shows 5 kPa)`. Write
   down what the app shows as well as what you tapped.
7. Turn the snoop log off when you are done. It keeps growing, and it records everything.
8. Copy the log to the computer. With the phone plugged in, run
   `adb bugreport bugreport.zip`. Inside the zip, find `btsnoop_hci.log`. It is often
   under `FS/data/misc/bluetooth/logs/`, but the folder differs between phone makers —
   search the zip for "btsnoop".

Several short captures are easier to read than one long one. Make one capture per
question: one for pressure, one for hold times, one for the stop, and so on.

---

## Step 3 — Read the capture in Wireshark

1. Open `btsnoop_hci.log` in Wireshark.
2. Type `btatt` in the filter bar. This keeps only the attribute traffic — the reads,
   writes and notifications apps and devices use to talk. If other devices are in the log,
   narrow it to the pump, for example by the handles you find below.
3. These are the lines that matter:

   | Wireshark says | ATT opcode | Direction | What it is |
   |---|---|---|---|
   | Write Request | `0x12` | phone → pump | a command; the pump confirms receipt |
   | Write Command | `0x52` | phone → pump | a command, no confirmation |
   | Handle Value Notification | `0x1b` | pump → phone | data from the pump |
   | Handle Value Indication | `0x1d` | pump → phone | data; the phone confirms receipt |

4. Writes and notifications name a **handle** (a small number like `0x0025`), not a UUID.
   If you captured a fresh connection, the log also holds the discovery that maps handles
   to UUIDs: "Read By Group Type Response" lists services, "Read By Type Response" lists
   characteristics with their handles. A write of `01 00` to a descriptor `0x2902` is the
   app switching notifications on (`02 00` means indications).
5. The bytes are the **Value** in each packet's details.
6. For a plain list you can read and share, use `tshark`, which comes with Wireshark:

   ```
   tshark -r btsnoop_hci.log -Y "btatt.opcode == 0x12 || btatt.opcode == 0x52 || btatt.opcode == 0x1b || btatt.opcode == 0x1d" -T fields -e frame.time_relative -e btatt.opcode -e btatt.handle -e btatt.value
   ```

   One line per packet: seconds since the log began, the kind, the handle, the bytes.
7. Line the list up with your timeline. The first write after "start" is the start
   command. The first after "stop" is the stop.

---

## Step 4 — Map commands to frames

The method is always the same: **change one setting, capture, compare.**

- **What never changes** is a header. Every ZD21 command starts `66 2A`.
- **What changes between kinds of command** is a command code. The ZD21's third byte is
  its opcode: `2C` starts a preset, `2D` stops.
- **Pressure.** Capture the same command at three settings in a row, such as 10, 11 and
  12 kPa, or levels 1, 2 and 3. The byte that moves one step per setting is the pressure.
  Its value tells you the unit:
  - `0A 0B 0C` for 10, 11, 12 kPa is whole kPa (the ZD21 sets pressures this way);
  - `64 6E 78` would be tenths of a kPa.

  If the app shows inHg or mmHg, convert first (1 inHg = 3.386 kPa, 1 mmHg = 0.1333 kPa)
  and see which unit makes the numbers come out round.
- **A number in two bytes.** Try both byte orders. `01 2C` is 300 one way round and 11265
  the other. Only one will make sense.
- **Times and holds.** Same method. Note the largest value the app allows. A one-byte
  field can hold at most 255 — which is why one ZD21 preset's hold stops at 255 seconds
  (OpenPump builds a longer hold from several presets).
- **Speed.** It may be a plain percentage or a code. The ZD21 turns 0–100 % into a 0–255
  code: `code = (pct × 255 + 50) / 100`.
- **A checksum.** A last byte that changes whenever anything else changes is probably a
  checksum. Try the sum of the other bytes, keeping only the last byte of the total; then
  all the bytes XORed together; then a CRC. A correct guess works for every frame you
  have.
- **Levels instead of pressures.** Some pumps take a level (1 to 9), not a pressure. Then
  measure what pressure each level reaches — from the pump's readings or a gauge — and
  write that table down. A level is not a pressure.
- **Programs stored on the pump.** Some pumps keep a table of programs and have commands
  to add, delete, list and start them. The ZD21 has a table of nine; Add appends to it and
  Delete closes the gap. Others take every setting live.
- **Answers.** After each write, does the pump send something back at once? The ZD21
  answers Add, Delete and Start with `<opcode> 01` and List with its table; whether it
  answers every stop is still being checked. Note what happens when the pump does not
  accept a command. The ZD21 does refuse: a Start of an empty slot is answered
  `<opcode> FD` (`2C FD`), and the pump carries on with what it was running. Look for such a
  reply before you decide a pump only ever stays silent — and find out what it does with
  odd presets: the ZD21 quietly doesn't store an all-zero one.

---

## Step 5 — Find the pressure readings

Run the pump on the sealed container and watch the notifications.

- **Which field?** Look for the value that moves as the pressure moves.
- **Text or numbers?** Bytes between `20` and `7E` that read as letters are text:
  `23 41 55 54 4F 2C` is `#AUTO,`. The ZD21 sends text: `#AUTO,-189,179,0`.
- **Unit and scale.** Compare the raw value with what the maker's app shows at the same
  moment, or with a gauge. On the ZD21, `-189` is 18.9 kPa: tenths of a kPa. Check at two
  or three pressures, not just one.
- **Sign.** A vacuum can be sent as a negative number (the ZD21 does), a positive one, or
  as absolute pressure (about 101 kPa of air minus the vacuum). Find out which.
- **How often.** Count readings per second from the times in your list. The ZD21 sends
  about four a second.
- **On its own, or on request?** Does the pump send readings by itself once subscribed, or
  only when asked? If asked, which command asks?

---

## Step 6 — Find what "no measurement" looks like

OpenPump never treats a missing reading as a pressure. On the ZD21, a reading of 0.0
means "not measuring", **not** "no vacuum" ([zd21.md](protocols/zd21.md), Telemetry).
Code that took that 0.0 as a real zero would draw slopes and add up doses from readings
that never happened.

Your pump will have its own way of saying it is not measuring. Record what the readings
show:

- just after connecting, before any start;
- running with the hose open to the air, so no vacuum can build;
- during a stop, as it vents, and a minute afterwards;
- while the pump is starting up.

For each, write down exactly what you saw: a zero, a special value, a flag in another
field, or no readings at all. Do not assume a zero means ambient pressure. Find out.

---

## Step 7 — Find the stop, and whether it vents

1. Find the maker's stop in your captures and note the frame. The ZD21's is `66 2A 2D`.
2. Watch the readings after it. Does the pressure fall quickly (the pump opened a valve:
   it **vents**) or stay where it was (it **paused**, still holding vacuum)? OpenPump's
   rule is "stop means vent" ([SAFETY.md](../SAFETY.md)). A stop that only pauses is not a
   stop OpenPump can use; look for a separate release command.
3. Measure how fast it vents: the fall in pressure divided by the time, over the first
   few seconds. The ZD21 vents at about 4.68 kPa per second.
4. Now test the stop yourself, on the bench, following the safety rules:
   - With the pump idle, connect with nRF Connect and write the stop frame to the write
     characteristic. Nothing should happen, and nothing bad should.
   - Start the pump from the maker's app at its lowest setting. Close the maker's app and
     note whether the pump **keeps running** without it. Then connect with nRF Connect
     and send your stop. The pressure must fall.
   - Repeat until it works every time.
5. Only then try a start frame of your own — at the lowest pressure, a hand on the vent,
   and your stop straight after.

Whether the pump keeps running with no app connected matters a great deal. The ZD21
does: once a preset is started it keeps cycling it by itself, and only the phone moves it
on or stops it. That is why OpenPump treats a lost Bluetooth link as dangerous.

---

## Step 8 — Other behaviour worth writing down

- **Holding.** At a steady setting, watch the readings for two minutes. Does the pump
  keep topping the pressure up, or pull once and let it drift down? The ZD21 does the
  second: it coasts.
- **Limits.** The highest pressure, the longest hold, the number of stored programs.
- **Silence.** Does the pump stop by itself if the phone goes quiet?
- **Power.** Does switching it off release the vacuum?
- **Firmware.** The version, from Device Information if the pump has it.

---

## Step 9 — Write it up

Create `docs/protocols/<your-pump>.md`. Follow the shape of
[zd21.md](protocols/zd21.md): it is the worked example, and every section below has a
filled-in counterpart there.

Write **wire facts only**: what you saw on the wire and what you measured. Never the
maker's code, never text copied from the maker's app, never anything from a decompiled
copy of it.

A skeleton to start from:

```markdown
# <ADVERTISED_NAME> — Bluetooth LE protocol

What OpenPump would send to and receive from a <pump>, as observed on the wire.
Nothing here is copied from any manufacturer software.

Status: **unverified** — worked out from captures of the maker's app, <date>,
firmware <version>.

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
- **No measurement** looks like: <what you saw, in which situations>.

## Behaviour worth knowing

- Stop <vents at about X kPa/s, measured / only pauses>.
- During a hold the pump <coasts / tops up>.
- Once started, it <keeps running on its own / stops when the phone goes quiet>.
- Switching it off <releases / keeps> the vacuum.

## Open questions

- <anything you have not confirmed>
```

Put your open questions in. A doc that says what it does not know is more useful than one
that guesses.

---

## Step 10 — Share captures safely

A snoop log holds everything the phone did over Bluetooth while logging was on, not only
the pump. It can contain:

- the addresses of your phone, your pump and every other Bluetooth device around;
- device names, which often include a person's name ("Anna's Pixel");
- data from other devices — earbuds, watches, cars, fitness bands;
- what the maker's app sends: an account or device ID, a serial number, the time (the
  ZD21 has a set-time command), sometimes more.

So:

- **Do not attach the raw log** to a public issue. Share an extract: only the pump's
  packets, as the text list from step 3, together with your timeline.
- **Replace the addresses.** Write `PUMP` for the pump's and `PHONE` for the phone's.
- **Read every write** for anything that looks like an ID, a serial number, an email or a
  name. Long runs of readable characters deserve a look. Replace them with `XX`, and say
  that you did.
- **Keep** the model and firmware version from Device Information. **Drop** the serial
  number.
- **Never share** the maker's app, a decompiled copy of it, or text copied from it
  ([CONTRIBUTING.md](../CONTRIBUTING.md)).

If you are not sure something is safe to post, hold it back and ask in the issue first.

A converter from the snoop log into the project's own capture format is planned (design,
section 4.3). Until it exists, the text list from step 3 is the thing to share.

---

## Step 11 — Open a New pump issue

On GitHub: **Issues › New issue › New pump request**. The form asks for:

- **Make and model** (required);
- **Name it advertises over Bluetooth** — exactly, from step 1;
- **Capture and notes** — attach the cleaned extract and your timeline;
- **Does it report pressure back? How often?** — from step 5.

In the notes, also say:

- what the stop does, and how fast it vents (step 7);
- what "no measurement" looks like (step 6);
- whether the pump keeps running with no app connected (step 7);
- what you could not work out.

Link your protocol doc, or open a pull request that adds it as
`docs/protocols/<your-pump>.md`.

---

## What happens next

A second pump needs the driver seam first. It is designed
([driver-seam.md](design/driver-seam.md)) and planned
([driver-seam-plan.md](design/driver-seam-plan.md)), not built. When it lands, a pump
becomes one driver, made of:

1. **its protocol doc** — the one you wrote;
2. **a driver** that recognises the pump when scanning, turns the app's commands into its
   frames, turns its frames into pressure readings with "no measurement" said plainly,
   and makes stop always vent;
3. **a list of what the hardware can do** — pressure range, longest hold, programs it can
   store, whether it reports pressure, whether its stop vents. The app refuses anything
   outside it;
4. **a small simulator** of the pump, so everyone can work on it without owning one;
5. **a pass of the conformance kit** (planned): stops vent and are confirmed, the ceiling
   always holds, a lost link stops the pump, garbage data never crashes the app — checked
   against golden transcripts made from real captures, which is why yours matter;
6. **a pull request.** The driver ships hidden, as experimental, behind a developer switch,
   until someone with the pump runs the hardware checklist on the real device and signs
   it off, with two reviews, one from a safety maintainer
   ([ADR 0006](adr/0006-unverified-drivers-hidden.md)).

## Ground rules

- Never commit a manufacturer's app, decompiled code, or text copied from it.
- A pump that cannot report pressure cannot confirm a vent. Support for it will be
  limited or declined.
- A pump whose stop does not vent cannot keep SAFETY.md's "stop means vent". The same
  applies.
- Nothing goes on a body until the driver has passed the conformance kit and the hardware
  checklist has been signed off.
