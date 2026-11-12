# GrowthTrack executable imports: draft integration

This branch adds explicit OAuth routine-read consent and private, account-scoped imports. Import and preview never start a pump. Existing session-only connections are not silently widened.

## Supported execution vs stored imports

The dedicated ZD21 run entry is wired through the existing app-lock and screen-entry gates, with an account recheck and explicit preview/start confirmation. Its supported code path is deliberately narrow: one `static_pumping` step with one pressure phase, 121–255 seconds, no tension, confirmation, or timed ramp. Pressure is rounded **down** to whole kPa and limited by the member ceiling. The preview must show this effective value. It routes through the existing run, foreground notification, link-loss and telemetry-confirmed stop machinery, without adding a warmup. The GT-specific clock includes approach time rather than extending the requested duration until pressure is reached. The deadline is anchored to the acknowledged start. After process death, this mode fails closed rather than resuming through native at-pressure timing; recorded partial evidence is retained.

Arbitrary patterns can be imported and inspected, but must not be advertised as executable on ZD21. Unknown actuator fields, unknown step controls and unrecognized roles are rejected, not silently discarded. Descriptive extensions can be stored under metadata but never influence control. Unsupported timed ramps, short phases, tension and unverified transition continuity must fail before table upload or START. The standalone pattern playhead is synthetic groundwork, not evidence of physical device capability.

## Contract decisions

- `openpump-pattern-v1` explicitly negotiates execution semantics; old readers must not ignore pattern/checkpoint fields.
- Omitted `reach_seconds` requests maximum supported speed, still subject to device and member limits. This is an explicit integration extension, not a measured guarantee.
- Phase duration includes approach time. Explicit timed targets must be rejected when a device cannot implement them.
- GT converts existing fractional-second template phases to whole seconds with ceiling rounding as approved by the owner. This adaptation is visible in the exported data; it is not exact reproduction of fractional device timing.
- Zero rest requests continuity. Positive rest begins only after verified release in the synthetic scheduler. A confirmation checkpoint waits for readiness, releases after at most 600 seconds, and still does not advance automatically.
- Exercise method and length/girth classification are retained independently of names. Targets must never be uploaded as measured pressure/tension. The native static route records an explicit static_pumping marker, including short aborted runs; this bypasses native hold-threshold inference and fatigue/retention filtering for the imported static run. Actual recorded duration and telemetry remain authoritative.

## Verification before release

Run the complete core test task and Android build. Verify explicit preview/start wiring, owner isolation, normalization, acknowledged-start deadline, upload classification, and mode cleanup after failed start/completion/abort. Test fixtures include actual GT catalog exports, immutable import validation, capability rejection, verified-release rest continuation and confirmation timeout.

Hardware validation remains required. In particular, ZD21 protocol notes mark adjacent-preset continuity as **unverified H16**. Verify STOP/vent confirmation, link loss, missed/refused START acknowledgements, timing at maximum speed and ceiling enforcement. Never infer these from a simulated run.

Pressure/timing changes require two approving reviews, including a safety maintainer, under SAFETY.md. This work is for a draft PR, not an unattended production release or automatic start.
