# 0005. Pump drivers are compiled in and reviewed

Date: 2026-09-22 · Status: accepted

## Context
Drivers could be loaded at runtime as plugins, but a plugin would let unreviewed code command a pressure device.

## Decision
Every driver is a module in this repository, reviewed and tested in CI with the conformance kit.

## Consequences
Slower for third parties than a plugin store; every driver meets the same safety bar.
