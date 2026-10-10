# GrowthTrack integration — developer handoff

Connecting a user's own GrowthTrack account now authorizes **automatic transfer of every new real finished session** recorded while connected. Disconnected sessions send nothing. There is no per-session picker, review, Send button or normal selective opt-in. Mixed techniques remain **one GrowthTrack session** with granular set/timing detail. Invalid, simulated or unrepresentable recordings have explicit status rather than a silent omission.

**Android compilation, lint and device/platform QA remain required before release.** JVM tests and source checks have passed. This work has not installed or run the Android app, made a live OAuth grant, transmitted real sessions or operated pump hardware.

## Checkout and scope

- Fork: <https://github.com/kwikmn/OpenPump-App>.
- Hemmaservern checkout: `F:\Codex\OpenPump-App`, branch `codex/growthtrack-integration`, baseline commit `1dddec436e552b0669e69427e9d054a71598d859`. The implementation is carried by the feature branch.
- Origin: `https://github.com/kwikmn/OpenPump-App.git`; upstream: `https://github.com/OpenByte-source/OpenPump-App.git`.
- Publication target: [personal-fork feature branch](https://github.com/kwikmn/OpenPump-App/tree/codex/growthtrack-integration). Publishing this branch does not merge main, deploy the site or send the developer message; no upstream PR was requested.
- GrowthTrack source was inspected read-only and remains unchanged. Its advanced routine builder is a future task, not implemented here.
- Pump commands, pressure ceilings, START, stop/vent and the session engine are unchanged. Filing adds data-only timestamp/connection markers and dispatches background work. GrowthTrack UI refuses browser/keyguard/navigation handoffs while the pump owner is unsafe or RunService is running. Neither data screens nor the retry service own a PumpLink.

## User behavior and consent

The fresh-install Ready screen offers connection once, with Skip; Settings → Connected apps → GrowthTrack remains available later. Existing models default the offer to already seen. Displaying it consumes the one-time offer, so cancellation, Back, upgrade and relaunch do not nag. Existing setup and START gates remain intact.

The connection dialog and information screen explain that browser approval enables automatic session sync to the user's own linked account. OAuth approval is still performed by the human in the system browser. Connection never starts hardware. Earlier history and records filed while disconnected are **not silently uploaded on connection**. This branch has no historical-import selection flow; historical policy was unspecified and remains a separate decision.

Every new filed real recording with representable saved work goes through the same sync path, including stopped sessions with recorded work. Simulations, missing stable identifiers, missing/inconsistent original timestamps, and unsupported timing have visible failure status. There is no technique override. Session summary opens sync status, not a send form. Retry is an optional recovery control; ordinary completed sessions require no action. A permission error asks for reconnect instead of repeatedly attempting unauthorized requests.

Local disconnect stops future requests, cancels the retry job and erases encrypted connection state and its Keystore key. Erase-all OpenPump data also resets the connection. A request already in flight may have committed; already received sessions remain in GrowthTrack. Reconnect changes grant identity and discards previous-account pending work and receipts. No profile/email scope exists in the inspected contract, so OpenPump does not invent a verified local account name. The browser identifies the approved account.

## Architecture and files

| Area | Responsibility |
|---|---|
| `GrowthTrackProtocol` | Fixed public registration, S256 PKCE, random state/verifier, strict callback, stable external IDs. |
| `GrowthTrackClient` | Serial OAuth/refresh, allowlisted completion snapshots, same-grant encrypted queue, receipts, bounded batches, retry/backoff and partial results. |
| `GrowthTrackSession`, `GrowthTrackTiming` | Recorded wall-clock/time/pressure mapping, fixed techniques, confirmed command-phase clipping and verified stitch joins. |
| `GrowthTrackOnboarding`, `SetupScreen` | One-time invitation, Skip and clear automatic-sync consent. |
| `GrowthTrackStore` | Keystore AES-GCM, AtomicFile and no-backup storage; no plaintext fallback. |
| `GrowthTrackHttp` | Fixed-origin HTTPS, no redirects/cookies/logging; bounded request/response size and timeouts; cancellation generations; assigned Android job network. |
| `GrowthTrackManager`, `GrowthTrackSyncService` | One serial worker; startup/filing recovery; persisted network-constrained JobScheduler retries; targeted job cancellation. |
| `GrowthTrackActivity`, callback activity | Private connection/status/information screens, system browser callback and own-account consent; optional existing read-only library previews. |
| `Model.Sess`, `AsRun`, `SessionActivity`, `Migrate` | Preserve new original timestamps, frozen track/stitch facts, completion grant marker and old-file compatibility. |
| Settings/summary/Store | Later connection, sync-status entry point and erase-all cleanup. |
| `docs/site/index.html`, `assets/growthtrack/` | Compact inline section, three supplied screenshots, implemented roadmap substep 2c. Separate GrowthTrack page removed. |

## Durable automatic completion and retry

`SessionActivity.fileSession()` captures the manager's non-secret browser-grant identity in `Sess.growthTrackConnectionId` immediately before its normal model save. It then dispatches sync; no HTTP runs on the pump/UI thread. Startup initializes the connection cache and also resumes pending work.

The serial worker reads saved records and only admits markers matching the **current exact browser grant**. This marker closes the filing/queue-save crash window without importing unmarked history. It is not a token or personal identity. Empty legacy/disconnected markers and markers from another approval cannot acquire a new account's consent. Restored model data alone cannot restore authority; encrypted tokens are kept outside backup.

Completion produces an allowlisted detached payload and appends it to the same-grant durable encrypted queue. Queued IDs and finished/failure receipts prevent duplicate completion callbacks from adding another item. Later source edits cannot alter a queued retry payload. Stable external IDs additionally protect server retries after a lost response. The existing JSON `approved` field remains the internal queue name; native production UI no longer invokes manual approval/selection APIs. Connections from the earlier per-send implementation require fresh browser approval before automatic behavior is enabled.

Offline, 408/429/5xx, malformed results and transient per-item failures retain the same queue and use exponential delay from 5 seconds to 1 hour; numeric Retry-After is bounded to 24 hours. Persisted one-shot Android jobs require any available network and preserve minimum retry time; Android can delay execution under Doze, quotas or force-stop. Reopening OpenPump also resumes eligible pending work. Scheduling failure is visible and pending snapshots remain secure. There is no fixed delivery-time promise.

Job ID `72041` is dedicated to GrowthTrack. The service is protected by `BIND_JOB_SERVICE`. Main manifest includes INTERNET and ACCESS_NETWORK_STATE; the latter is required for network-constrained jobs on Android 14. Existing RECEIVE_BOOT_COMPLETED supports persisted jobs. API28+ jobs use the network supplied by JobParameters; older supported platforms use ordinary HTTPS. Replacement scheduling is posted after job completion to avoid stopping the job just finished. A stopped queued job does not cancel unrelated OAuth/routine work; active same-job work is cancelled. Reset blocks new work and invalidates transport generations.

See official [JobInfo network requirements](https://developer.android.com/reference/android/app/job/JobInfo.Builder#setRequiredNetworkType(int)), [JobScheduler replacement semantics](https://developer.android.com/reference/android/app/job/JobScheduler#schedule(android.app.job.JobInfo)) and [JobService lifecycle](https://developer.android.com/reference/android/app/job/JobService).

Requests contain at most 100 sessions and 1,000,000 UTF-8 bytes. Oversized multi-session 413 batches split; a single unrepresentable record has a visible failure. Valid response counts and unique known rejected IDs must cover every submitted item before any receipt is recorded. Permanent rejections leave failure receipts; transient items alone remain queued. Accepted versus duplicate counts are aggregate, so a per-record receipt accurately says “Received (sent or already present).” No false individual distinction is invented. Missing permission or unexpected permanent HTTP failure pauses automatic retry visibly. Revocation/second401/410 or unsafe credential rotation clears authority and stops sync.

## Public OAuth registration and security

- Client: `gtc_bfa5809efbd314f246dd5d0f` (public PKCE; no native secret).
- Exact callback: `gt-openpump://oauth/callback`.
- API origin: `https://rxtqbktyeetmbigbpbdd.supabase.co/functions/v1`.
- Project/account setup: <https://pe-growth-track.com/>.
- Remote revoke/manage: <https://pe-growth-track.com/connected-apps>.
- Required `sessions:write`; optional `routines:read` for existing read-only previews.
- OAuth authorization is GET `/oauth-authorize`; token exchange/refresh is form POST `/oauth-token`; ingestion uses `/partner-ingest`; routine preview uses `/partner-routines`.

Parent verified the public registration screenshot. No older sandbox password or unrelated client was reused. Code/state are single-use locally. Callback rejects wrong endpoint/port/userinfo/fragment, duplicated/extra fields, wrong state, code plus error, stale/backwards-clock requests and missing required values. Pending lifetime is 15 minutes. Wrong callbacks preserve the legitimate pending request.

Access tokens last up to 900 seconds. Refresh rotates serially; the new refresh credential is durably saved before further authenticated HTTP. Before code/refresh requests, a durable exchange marker is written. Crash, ambiguous response or failed secure save forces fresh authorization instead of replaying a potentially consumed credential. Cancellation generations stop stale queued work opening HTTP after reset. These behaviors are covered by mocks, not live server/device tests.

Token/verifier/payload/receipt state is AES-256-GCM encrypted with AndroidKeystore key `org.openpump.growthtrack.v1`, random 12-byte IV, AAD `GT1`, AtomicFile and a 16 MB encrypted-file cap in no-backup storage. Missing/tampered/inaccessible storage blocks access; no plaintext fallback. Tokens do not enter preferences, model JSON, backup/export, logs, clipboard or forwarded Activity Intents. Only the non-secret completion grant marker enters Sess JSON. Release forbids cleartext; debug diagnostic upload remains separate. Secure-window and app-lock whole/settings/history preferences apply to connection screens and dialogs. Background automatic sync is the already approved connection behavior and does not navigate to UI or unlock the device.

GT exposes no native token-revocation endpoint in the inspected contract. Device disconnect cannot claim remote revocation; the UI guides the user to Connected apps.

## Mapping and material limits

| Positive confirmed hold, excluding release/rest | Fixed method |
|---|---|
| Through 6 seconds | `milking` |
| Above 6 through 30 | `rip` |
| Above 30 through 120 | `interval_pumping` |
| Above 120 | `static_pumping` |
| Structured recorded Length track | `length_pumping`, overriding duration classification |

Labels use saved acknowledged command holds, clipped to delivered closed-row spans. They are **not measured pressure-plateau timelines**: OP does not preserve every sampled plateau historically. Zero-release rows are one continuous hold. Proven `cyclePart` markers or matching older frozen plans join stitched chunks; 255+45 seconds is one 300-second Static hold when proven. Missing, changed, gapped or unconfirmed stitches and ambiguous paused/lost-link phase histories fail closed. A stopped final chunk is clipped to its delivered span. Release/rest does not affect technique thresholds. Total/set elapsed duration may include recorded releases, and must not be described as continuous time under pressure.

Structured frozen `trainerTrack` determines Length; no free-text name, picker or guessed category. Old snapshots retain unknown=-1. A verified Length record without detailed rows can use a session-level method without inventing sets, but it still needs reliable timestamps/duration. All mixed methods remain inside one original stable external_session_id; GT's current SetSchema supports per-set method and groups exercises within that sitting.

Uploaded fields are stable session ID, original started/completed timestamps, routine name, total elapsed seconds, set index/fixed method/recorded duration, observed overall peak (kPa magnitude divided by 3.38639) and saved measured **net** time under pressure when valid. Gross sealed elapsed includes releases and is never substituted. Whole seconds round down; subsecond set duration is null rather than a fabricated second. Unknown/implausible optional telemetry is omitted honestly. No measurements, photos, notes, user/email identity, invented set-pressure average, heat, vibration or traction data is uploaded.

Old `Sess.ts` meant filing/end in some releases and start in later ones; it cannot safely reconstruct original boundaries. New nonrejoined records preserve `recordedStartMs`/`recordedEndMs`. Old records and rejoined records without all original bounds are unrepresentable. No guessed backfill was added. New unsupported records receive an explicit status; this is a material coverage limitation, not an optional user selection. Detailed AsRun retention is 90 days, so long-pending records without a completed encrypted snapshot may become unsupported and show that status. The normal saved session remains in OpenPump.

Verified GT SQL deduplication uses `(user_id, partner_app_id, external_session_id)`, including same-account relinks; approval logic reuses the active app/user link while rotating grants. No SQL/RPC/server changes were performed.

Existing optional `routines:read` remains a **read-only preview**, not routine transfer/import or an executable pump builder. No device pressures/cycle/escalation settings are invented, no routine is inserted and no hardware can start from a preview. Private preview responses are not saved and are cleared on backgrounding. There is no routine-transfer claim on the website. The future advanced builder in GT is outside this task.

## Website and supplied screenshots

Only `docs/site/index.html#growthtrack` is the destination. It uses the same `.wrap` width/padding as adjacent sections. Copy is compact: automatic connection behavior; fixed techniques in one granular session; advanced tracking and voluntary study/data contribution benefits. It explicitly avoids claiming measurement upload or automatic research enrolment. Roadmap 2c says Implemented; no local-branch/QA metadata appears in public copy. All original ten main steps and 21 roadmap entries/metadata are preserved.

Three user-supplied PNGs were resolved and materialized through Library's official helper, verified as readable bytes, inspected and copied without pixel modification to `docs/site/assets/growthtrack/`. Source dimensions: dashboard327×692, girth313×550, length313×478. Desktop uses a 300px-high row; mobile a horizontal gallery with full-size image links. Aspect ratios are preserved. These screenshots show measurements and dated training charts, though no account name/email is visible. The user explicitly approved including these three screenshots unchanged. 

The existing main site's first-use liability agreement was not accepted by the agent. Live read-only DOM checks verify the section/wrap/roadmap. Desktop and390px mobile screenshots use an isolated local visual QA fixture built from the exact current section HTML and styles; all three images load and the document has no horizontal overflow. This is layout evidence, not acceptance of the site's notice. 

## Completed verification

Final `:core:test`: BUILD SUCCESSFUL in3m49s; **2,140 JUnit tests,0 failures,0 errors**. Includes57 protocol/mapping/integration mocks,12 automatic-sync mocks and5 onboarding tests. Legacy SelfTest35,830/0, Scenarios58/0, MigrationOK, Wiring1,787 self-tests/0 source violations. Earlier wiring violations were fixed in UI code; checks were not weakened. The final source-only wiring follow-up also passed in2m44s with453 pure/46 Android sources and0 violations.

Automatic mocks cover completion without selection, every distinct record appending, duplicate completion before/after receipt, disconnected/preconnection exclusion, offline durable delay/restart retry, filing-marker crash recovery, relink and disconnect/in-flight races, simulations/invalid/unrepresentable status, partial permanent/transient results, paused permissions and migration from old manual-only consent. Fixtures are fictional; mocked transport cannot reach GT.

Java17 parsing checks all45 main Android source files, and XML/source/whitespace checks pass. These are **not Android API/type compilation**. Hemmaservern has TemurinJDK21.0.7, Java17 target, Gradle8.11.1/AGP8.7.3 and F: Gradle cache. No Android SDK/adb/emulator was found. Per the user's instruction, no SDK/emulator/BlueStacks installation or license acceptance was attempted.

## Required developer validation

Use an already authorized Android environment with JDK17+, SDK/platform34 and build-tools34.0.0. Do not add secrets/signing keys. With no Android runtime currently available, the following remain **not run**:

```powershell
.\gradlew.bat :core:test --console=plain
.\gradlew.bat :app:compileDebugJavaWithJavac :app:lintDebug :app:assembleDebug --console=plain
```

1. **Build/API:** compile/dex/lint on minAPI24/25 and current Android; inspect release manifest, exact callback, private Activity, BIND_JOB_SERVICE, INTERNET/ACCESS_NETWORK_STATE, persisted-job boot permission, HTTPS/backup rules and absence of debug console. Check assigned-network API28 guards and all Keystore/minSDK calls.
2. **Consent/UI:** fresh Ready offer/Skip; existing users no offer; Back/decline/cancel/relaunch no nag; own account shown in browser; dialog explicitly authorizes all future new session transfers. No session picker/Send/technique override. Summary/status remains accurate under locks, rotation and stale Activity callbacks.
3. **Automatic completion:** connect, file multiple fictional real training sessions, including manual/ordinary and stopped-with-work records; all supported records queue/send automatically once. No transfer from simulations, disconnected/preconnection history or another grant. Unsupported records show reasons. Verify connection cache initialized before a first filed record and recovery after process kill between model save and encrypted queue save. Do not operate attached pump hardware for this data QA.
4. **Retry jobs:** airplane mode, intermittent networking, network switch/VPN, metered connection, Android14 permission, Doze/standby/quota, app background, process kill, reboot, force-stop/reopen, JobService stop while queued/active and schedule refusal. Respect persisted delay and bound same IDs; no busy loop or cancelled job interfering with OAuth. Every network/storage operation remains off UI/pump threads. Verify jobFinished/replacement order on device.
5. **OAuth/rotation:** write-only/write+read, exact own-account scopes, decline/no-browser/cancel, strict forged/stale/duplicate callbacks, cold/background/recreated callbacks,900s expiry/401 refresh, rotated credential saved first, second401/410/revoke,403 pause. Crash or lose response during code/refresh exchange: no old-credential replay. Concurrent operations never rotate in parallel.
6. **Secure storage:** AES-GCM integrity/random IV/AAD, AtomicFile failure/recovery, disk full, tampered/truncated/overlarge data, missing/invalidated Keystore key. No plaintext/token logging/preferences/backup/Intent leaks. Reset remains usable and erase-all removes file/key/job. Restored model markers cannot restore link authority.
7. **Labels/timing:** 6/6.001,30/30.001,120/120.001 boundaries;6s hold+80s release remainsMilking; continuous zero release; verified >255s stitches and interrupted last chunk. Compare confirmed command-phase projection to approved device recordings; ambiguous paused/lost/changed stitches report unsupported. Frozen Length survives rename/edit/delete; free text never determines it. Length cannot bypass timestamp facts.
8. **Payload/results:** one mixed-technique sitting remains one ID/session with proper set order. Verify original times, elapsed/release/net distinctions, kPa→inHg, missing optional telemetry, whole-second/null fractions and no measurements/photos/notes. Test100-record/UTF8byte batching,413 split/single failure,422,408,429,5xx, unknown/duplicate result IDs, incomplete totals, permanent/transient mixtures and lost-response server dedup. Source edits after enqueue cannot alter retries.
9. **Account/disconnect races:** reconnect while queued, same/different own test accounts, reset during token/upload and before a queued operation enters HTTP; no old-account payload carryover. A possible in-flight commit is stated accurately. Previously received records remain, local erasure does not claim server revoke; Connected apps removal stops access.
10. **Safety/privacy:** app-lock whole/settings/history and secure-window preferences; failed unlock reveals nothing; optional private preview cache cleared on background. Browser/keyguard/new screen refused during unsafe hold/RunService; no second owner, pump command, lost STOP handle or timing change. Automatic data-only background work must not interfere with existing pump behavior.
11. **Existing routine preview:** extra scope only, own/favorite/community authorization, matching UUID/schema,404/deleted access, own notes and expanded order/rest handling. No executable import, pressure substitution or routine builder was added.
12. **Website:** main inline links, 2c desktop/mobile roadmap, preserved original entries, no separate-page links/dev QA copy, keyboard/focus/full-size images, mobile swipe, readable aspect ratios and approved screenshot publication.

Do not release until Android/platform checks and command-phase timing assumptions are verified. This local implementation does not claim end-to-end production or device validation.
