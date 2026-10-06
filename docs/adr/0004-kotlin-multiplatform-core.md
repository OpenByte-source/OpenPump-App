# 0004. Kotlin Multiplatform for the shared core

Date: 2026-09-22 · Status: accepted

## Context
OpenPump is Android-first, but another phone platform may follow. Rewriting the safety supervisor and pump drivers for a second platform would create two safety implementations that drift apart.

## Decision
The pure core will be converted to Kotlin Multiplatform, module by module, after the pump driver seam is stable. Screens and Bluetooth stay per platform.

## Consequences
The core is written once for every platform. Conversion happens behind the existing checks, so each step can be proven to change nothing. Until then the core stays Java.
