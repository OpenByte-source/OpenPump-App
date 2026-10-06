# 0008. A driver seam with one safety supervisor

Date: 2026-09-24 · Status: proposed

## Context
OpenPump drives one pump protocol, the ZD21 (the Epic Hydro PE Pump's), and it is spread through the app. Twenty-six transmit sites in `SessionActivity` build ZD21 frames. The session engine sizes plans to the ZD21's 9-slot table and 255-second hold. The safety machinery — the vent watch, the START gate, the link watchdogs, the 2-hour cap, the last-resort stops — lives in the Android activity, held there by `WiringCheck`. Adding a second pump today would mean editing all of it. ADR 0005 requires drivers to be compiled in and tested with a conformance kit; ADR 0006 hides unverified drivers.

## Decision
- A `PumpDriver` interface in `core`, pure Java: identity, how to recognise the pump, where to write and listen, what the hardware can do, a table-shaped command set, and a parser that reports readings with an explicit "no measurement".
- A `SafetySupervisor` in `core` that is the only way to command a pump: the ceiling, capability checks, the START gate, the vent confirmation by telemetry, the link watchdog, the 2-hour cap and the journal. It never refuses, delays or rate-limits a stop. Driver commands and transport writes need a `Permit` only the supervisor can create; `WiringCheck` and `ArchitectureTest` forbid the escape hatches Java cannot close.
- The simulator stays at the byte level, below the driver, so practice mode and the tests run the real driver and the real supervisor.
- A conformance kit in JUnit that every driver passes, with golden wire transcripts from real hardware.
- Built in small steps that change no behaviour, as packages inside `core` that map onto the future `pump/api` and `pump/zd21` modules. Where the supervisor would do more than the app does today, each addition is a separate step that needs the owner's yes.

Design: [docs/design/driver-seam.md](../design/driver-seam.md). Plan: [docs/design/driver-seam-plan.md](../design/driver-seam-plan.md).

## Consequences
- Adding a pump means a driver, its simulator, its protocol doc and a passing kit — hidden until its hardware checklist is signed off (ADR 0006).
- The safety rules live once, in pure code that runs in JUnit in milliseconds. The Android wiring shrinks to screens and Bluetooth, which is what ADR 0004's shared core needs.
- A long migration through safety-critical code. Every step that touches commanded pressure or the stop path needs the owner's safety review. For a while some protections exist twice — the app's own and the supervisor's — and the app's are removed one at a time.
- The table-shaped interface fits pumps with a preset table; a pump without one keeps a table in its driver. A pump that differs more may need the interface to grow, as a reviewed change to this record.
