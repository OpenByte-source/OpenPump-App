# Instructions for AI coding assistants

Read this before changing anything. Humans: this is also a compact summary of the rules.

## What this is

OpenPump — an Android app that commands a Bluetooth vacuum pump applied to the body.
Mistakes can hurt a person. Treat anything that changes commanded pressure, timing, or how
a run stops as safety-critical (see SAFETY.md).

## Layout

- `core/` — plain Java 17, **no `android.*` imports** (ArchitectureTest fails the build).
  Package `org.openpump`. Training plan, session engine, model/persistence, Proto (ZD21
  wire protocol, the Epic Hydro PE Pump's), SimPump.
- `app/` — Android shell (screens, RunService, PumpLink Bluetooth). Package `org.openpump`.
- `core/src/test/java` — JUnit 5 tests (`org.openpump`) plus the original harness programs
  in the default package (`Run`→SelfTest, `Migrate`, `WiringCheck`, `Scenarios`).

## Commands

- `./gradlew test` — every check (~35,800 harness assertions + JUnit). Must pass.
- `./gradlew :app:assembleDebug` — build the APK.
- `./gradlew :core:selfTest` / `:core:wiringCheck` / `:core:migrateCheck` /
  `:core:scenarioCheck` — one harness at a time.

## Rules

1. Never weaken, skip or delete a check to make a change pass. Fix the code or explain.
2. Never bypass the stop/vent confirmation, the ceiling clamp, or the START gate.
3. Saved-data changes must keep old files loading; add a `Migrate` case.
4. GrowthTrack is the optional release network integration: public-client PKCE, secure tokens,
   browser approval of an account connection enables automatic transfer of every new real
   finished session. Disconnected and unmarked earlier history never upload; pending work
   stays bound to its original grant. No per-session opt-in is required. Diagnostic log upload remains debug-only.
5. UI: Java-built views (no XML layouts), 48 dp minimum touch targets, colours and sizes
   from `Look`, dialogs through `Ui.dialog()` + `Ui.dress()`.
6. New logic that can be pure goes in `core/` with a JUnit test.
7. Comments explain *why*; many cite private history files that are not in this repo —
   do not go looking for them.
8. Keep changes small and focused; one concern per pull request.
