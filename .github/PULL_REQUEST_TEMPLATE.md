## What this changes

<!-- One or two sentences. Link the issue: Closes #123 -->

## How it was checked

- [ ] `./gradlew test` passes
- [ ] Tried on a phone or emulator (simulated pump is fine unless this touches Bluetooth)
- [ ] Old saved data still loads (if the data format changed: a `Migrate` case was added)

## Safety

- [ ] This does **not** change what pressure is commanded, when, or how a run stops
- [ ] It **does** — explained below, and two reviews (one safety maintainer) are needed

## AI assistance

<!-- If an AI assistant wrote a meaningful part of this, say which parts. -->
