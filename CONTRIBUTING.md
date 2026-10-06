# Contributing to OpenPump

Thank you for helping. You do not need to be a programmer: bug reports, translations,
phone/pump compatibility reports, design ideas and protocol captures are all
contributions.

## Ways to help

| You are… | You can… |
|---|---|
| A user | Report a bug with the bug form (say which phone, Android version and pump). Suggest an idea, or vote on one, in Discussions › Ideas. |
| Owner of another pump | Capture how its own app talks to it and open a *New pump* issue. |
| A designer | Propose screens as sketches or mockups in an issue. |
| A developer (by hand or with AI) | Pick an issue labelled `good first issue`, then send a pull request. |

## Development setup

- JDK 17+, Android SDK platform 34 (Android Studio installs both).
- `./gradlew test` — all checks, no phone needed. Run it before every pull request.
- `./gradlew installDebug` — install on a phone or emulator.
- **Settings › Device & developer › Simulated pump** — develop without a pump.

## Pull requests

1. One change per pull request, small enough to review in one sitting.
2. Tests: new logic in `core/` comes with a JUnit test in `core/src/test/java`. Changes to
   the saved data format add an old-file case to `Migrate`.
3. `./gradlew test` passes locally.
4. Commit messages use conventional prefixes: `feat:`, `fix:`, `docs:`, `test:`,
   `refactor:`, `chore:`.
5. If an AI assistant wrote a meaningful part of the change, say so in the description.
   The same checks and reviews apply either way.

## The rules of the codebase

- **`core/` never imports Android.** If a class needs Android, it belongs in `app/`.
  `ArchitectureTest` enforces this.
- **Safety-critical code needs two reviews.** Anything that changes what pressure is
  commanded, when, or how a run stops — see [SAFETY.md](SAFETY.md).
- **Privacy:** no feature may send data off the phone unless the user turns it on, and it
  must be off by default.
- **UI conventions:** screens are built in Java (no XML layouts); touch targets are at
  least 48 dp; colours and sizes come from `Look`; dialogs go through `Ui.dialog()` and
  `Ui.dress()`. Keep the wording plain and specific.
- **Old saves must keep loading.** Never break `Model.fromJson` for an existing file.

## Adding support for another pump

Read [docs/adding-a-pump.md](docs/adding-a-pump.md). Protocol documentation contains
**facts observed on the wire only** — never code or text copied from a manufacturer's app.

## Code of conduct

Everyone taking part agrees to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Licence

By contributing you agree that your contribution is licensed under the
[AGPL-3.0](LICENSE), like the rest of the project.
