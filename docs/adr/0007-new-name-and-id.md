# 0007. New name and application id

Date: 2026-09-22 · Status: accepted

## Context
The private project was called PumpDebug (`com.pumpdebug`), a debugging tool's name.

## Decision
The project is OpenPump, application id `org.openpump`. Existing users move their data once with Backup → Restore; restoring rewrites photo paths from the old id to the new one (`Backup.rebasePhotoPaths`).

## Consequences
OpenPump installs beside the old app instead of replacing it. Data migration is one backup and one restore, verified on an emulator with a photo.
