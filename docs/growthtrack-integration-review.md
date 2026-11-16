# GrowthTrack integration: decisions, findings and verification

This document accompanies the coordinated GrowthTrack/OpenPump changes. It distinguishes completed source work from device behavior that has not been verified. Companion GrowthTrack PR: https://github.com/kwikmn/growth-track-journey/pull/163.

## Decisions

- A valid newer upload with the same owner, partner and external session ID replaces the existing GT snapshot, rather than creating another session. Latest means latest accepted upload; there is no server-side sender-revision field.
- Describe performed technique, pressure/tension and duration. GT exports no fatigue/retention roles. `confirm` is solely a readiness control, not an exercise classification.
- Preserve the requested routine's pressure pattern and explicit classification. Never silently coerce an unsupported pattern into a native preset.
- Zero rest requests pressure continuity; positive rest requests release. Equipment confirmation can hold for at most 600 seconds, then releases and continues waiting. Timeout never means consent to continue.
- Missing `reach_seconds` requests maximum supported speed. Approach-inclusive phase timing is an explicit implementation clarification beyond the original PDF, subject to maintainer review. A timed ramp is not simulated by inventing a speed/deadline conversion.
- Phase duration follows the proposal: integer 1-7200 seconds, at most 256 phases. At the owner's explicit request, fractional GT template phases round UP for the OpenPump version (0.5 becomes 1); the source catalog for other devices is unchanged, and overall exercise work duration remains the selected duration.
- Users explicitly add routines:read consent. Existing session-only connections and paused uploads do not silently change behavior.
- The routines page gets a visible Import from GrowthTrack action. GT can save then open a UUID-only app handoff. Import/preview/save is separate from starting hardware; a native, gated confirmation is required to start.
- Existing app-lock, run/vent navigation gates, ceiling clamp, START gate, stop confirmation and foreground ownership remain authoritative.

## Findings and fixes

Some items below were found during review of this new, unreleased patch, not defects claimed against the released app.

### Sender and consent

1. Reconnect/carried queue entries could accept lower-revision content while retaining higher-revision metadata. Lower revisions are now rejected consistently. In-flight newer snapshots remain queued after an older upload completes.
2. A read-scope upgrade must not create an undisclosed initial session-upload connection or unpause a paused connection. Initial connection still uses its normal disclosure; explicit upgrades preserve existing pause state.
3. Missing routine-read permission must not disable session uploads. Read failures are handled separately; only an exact permission failure offers additional consent, not arbitrary network/server errors.
4. Cancelled upgrades preserve the existing token/queue. Consent remains explicit and grant identity is checked.

### Import and UI

5. Old GT library records do not contain executable pressure patterns. Executable discovery is explicitly negotiated (`execution=openpump-pattern-v1`), and identity/schema/capabilities are validated.
6. Unsupported phase/step controls, roles, grouped sets and confirmation-rest combinations now reject instead of being ignored. Metadata is not interpreted as actuator instructions.
7. A first navigation trampoline did not follow the repository's approved door/vent route. It was corrected without weakening WiringCheck. Final links use existing identity aliases, including incognito, rather than a new normal-identity exported component.
8. New Android listeners initially used lambdas unsupported by this runtime. They were replaced with named listeners; existing runtime checks remain enabled.
9. Async account changes and failed local saves must not launch or file a routine for the wrong connection. Download, rendering, save and native arrival recheck identity. Links contain one UUID only, never tokens or routine data.

### Execution and evidence

10. Native ephemeral setup injects warm-up and normalizes presets. That is not a faithful generic pattern importer. A separate, deliberately narrow static adapter avoids this shortcut; unsupported routines remain previewable but cannot start.
11. Imported timing initially risked changing to native timing after process recovery or reconnect Start over. Cold recovery now fails closed and files the observed partial; reconnect Start over ends/files the import and requires explicitly reopening it. In-place resume retains imported mode. No automatic restart is introduced.
12. Legacy hold-duration inference can misclassify an early-aborted static import. An allowlisted persisted technique marker retains explicit static classification through normal, aborted and cold-declined filing before outbox submission. Old records retain old behavior.
13. The session deadline is anchored after the existing acknowledged-start callback. Observed duration/pressure are used for upload; targets are not manufactured into telemetry or fictional sets. Confirmation shows effective device pressure after resolution/ceiling adjustment.

### Companion GT fixes

14. Unconditional exercise replacement on exact replay would invalidate explicit PACT receipts. Effective identical snapshots are now no-ops; genuine changed evidence still invalidates PACT selection according to existing rules.
15. Updating unchanged timestamp columns can trigger personal planned-receipt owner guards under a service-role import. Unchanged columns are omitted; changed timestamps on an explicitly linked personal slot reject atomically with an actionable owner-resolution reason rather than weakening that guard.
16. Insert/update counters are transactional and count new sessions only. Invalid replacement leaves the existing log intact. Owner-scoped draft policies and optimistic versions prevent cross-owner access and stale-save overwrite; tab recovery preserves unsaved edits without cross-account leakage.

## Verification

- GT: model/export/page tests, private draft RLS/version tests, exact-replay and real receipt-trigger regressions; My Plan regression suite; TypeScript, production build and dark desktop visual review. Current exact counts belong in the PR check results.
- OpenPump: all existing migration/scenario/self-test/WiringCheck checks remain enabled. New parser, actual 13-template catalog, capability, scheduler, strict-link, consent/transport, ordering, static-adapter, provenance and lifecycle-guard tests are included.
- An old test forbidding *any* routine-named API was updated for the intentionally added feature. It now preserves the actual boundary: a session-only grant cannot issue routine reads. This is not a skipped safety check.
- Local Android SDK is unavailable. Existing PR CI supplies Android compilation, debug APK and lint; no APK-build success is claimed before CI returns it.
- Synthetic tests are not physical pump validation, full production aggregate-trigger testing, or concurrency proof.

## Deliberate limits / required review

Current device execution is a single static_pumping phase, integer 121-255 seconds, maximum speed and no tension. This is a narrow adapter constraint, not a claim that the hardware universally supports only those durations. Other valid imports remain preview/save-only with explicit incompatibility reasons.

The public ZD21 protocol notes mark adjacent-preset continuity (H16) unverified and expose no deadline-ramp primitive. H15 acknowledgement/release behavior also requires device evidence. These are not solved by parsing a JSON document. Do not enable unverified capabilities from imported data.

The isolated generic scheduler is not itself a hardware driver. Arbitrary pattern execution, short-phase fidelity and broader device support remain gated on driver implementation and device verification. The proposal does not explicitly settle approach-inclusive timing or omitted reach semantics; maintainers should review these clarifications.

OpenPump automatic resend revisions currently follow resumes/rejoins, not arbitrary edits to old history. Very old receipts without revision metadata retain existing final behavior. GT still accepts a valid authorized latest same-ID snapshot.

Safety-critical changes require the repository's two approving reviews, including a safety maintainer. This PR is not a safety certification, automatic release, or approval to skip hardware checks. GT migrations/functions and site publication are separate rollout steps; this development work has not deployed them.
