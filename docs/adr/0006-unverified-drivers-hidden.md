# 0006. Unverified drivers are hidden

Date: 2026-09-22 · Status: accepted

## Context
A driver that passes the simulator-based conformance kit has not yet met a real pump.

## Decision
New drivers ship marked experimental and hidden behind a developer switch until a hardware checklist is run on the real device and signed off.

## Consequences
Users only see drivers proven on hardware; contributors can still test experimental ones.
