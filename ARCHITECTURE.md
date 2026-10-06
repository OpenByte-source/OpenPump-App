# Architecture

OpenPump is a phone app. Everything ships inside the Android APK; the split below exists
so that most of the logic can be understood, changed and tested without a phone.

## Modules today

```
app/     Android shell — screens, the foreground run service, Bluetooth (PumpLink),
         notifications, widget, file providers. Depends on core.
         app/src/debug is in debug builds only: the diagnostic console (MainActivity,
         its own Bluetooth connection and raw frames) and the INTERNET permission its
         log upload needs. The release APK carries neither.
core/    Plain Java — no Android imports, enforced by ArchitectureTest.
         Training plan, session engine, data model and persistence, share codes,
         progress/analytics, and the ZD21 pump protocol (Proto) with its simulator;
         the ZD21 is the Epic Hydro PE Pump.
```

Orders flow one way: screens → session logic → pump protocol → Bluetooth. Screens never
build pump frames themselves (that coupling still exists inside `SessionActivity` today and
is being removed — see the roadmap).

## Where things live

| You want to… | Look in |
|---|---|
| Change a screen | `app/src/main/java/org/openpump/*Screen.java`, `SessionActivity.java` |
| Change the training plan | `core/…/Plan.java`, `RxBuild.java`, `Mint.java`, `Deload.java` |
| Change how a run is recorded or saved | `core/…/AsRun.java`, `RunEdit.java`, `Session.java` |
| Change the saved data format | `core/…/Model.java` — and add an old-file case to `Migrate` |
| Change the pump's wire format | `core/…/Proto.java`, `SimPump.java` — safety review |
| Change Bluetooth | `app/…/PumpLink.java` — safety review |
| Change colours, sizes, shared UI pieces | `core/…/Look.java`, `app/…/Ui.java` |
| Use the diagnostic console | a debug build (`./gradlew installDebug`); the code is `app/src/debug/…/MainActivity.java` |

## Tests

`./gradlew test` runs, in order:

1. **The original harnesses** (plain Java programs, a non-zero exit fails the build):
   `selfTest` (~35,800 checks), `migrateCheck` (old saved files still load),
   `wiringCheck` (static invariants over the source, e.g. the stop/vent paths — it reads
   `app/src/debug` too, so the console is still checked),
   `scenarioCheck` (whole routines against the simulated pump).
2. **JUnit 5 tests** in `core/src/test/java` — new tests go here, and the harnesses move
   here piece by piece.

## Where it is going

The plan, in phases (each ships on its own and changes no behaviour unless stated):

1. ✅ **Clean repository and standard build** — Gradle, CI, `core`/`app` split.
2. **Finer modules** — `core` splits into `model`, `training`, `session`, `progress`,
   `safety`; `pump/api` and `pump/zd21`.
3. **Pump driver seam** — a `PumpDriver` interface between the session engine and the
   wire, a `SafetySupervisor` every driver must pass through, a conformance kit every
   driver must pass. Adding a pump then means adding a driver module. Verified
   byte-for-byte against recorded pump traffic and on real hardware.
   **Next:** the design is [docs/design/driver-seam.md](docs/design/driver-seam.md) and
   the step-by-step plan [docs/design/driver-seam-plan.md](docs/design/driver-seam-plan.md)
   ([ADR 0008](docs/adr/0008-driver-seam.md), proposed).
4. **Split `SessionActivity`** — the run state machine moves into `core`.
5. **Kotlin Multiplatform core** — so another phone platform can reuse the plan, the
   session engine, the safety supervisor and the drivers instead of rewriting them.

Decisions behind this are recorded in [docs/adr](docs/adr).

## Historical references in comments

Many comments cite design notes, mockups and research files from the project's private
history (`docs/superpowers/…`, `dist/…`, `proto/pump-console.html`).
Those files are not published. The comments still explain *why* the code is the way it is;
replacing the references with the reasoning itself is a good first contribution.
