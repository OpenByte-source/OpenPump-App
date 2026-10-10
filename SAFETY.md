# Safety

OpenPump sends commands to a pump that applies vacuum to the body. This file lists the
rules the code keeps so that stays safe, and the rules every contributor keeps with it.

**OpenPump is not a medical device.** It does not diagnose, treat or advise. The training
plan applies published guidance mechanically; it cannot know your body. You use it at your
own risk — the full terms, including the warranty and liability disclaimer, are in
[DISCLAIMER.md](DISCLAIMER.md).

## Invariants — these must never break

1. **The ceiling holds.** No command ever reaches the pump above the user's safety
   ceiling, whatever a routine, a live adjustment or a plan says.
2. **Stop means vent.** STOP releases pressure. A stop is not "done" until the pump's own
   telemetry shows the pressure falling; an unconfirmed stop is retried and reported.
3. **No start on an unconfirmed stop.** While the last stop is unconfirmed, the app
   refuses to start anything new.
4. **A lost link is treated as dangerous.** After 5 s without telemetry the run screen
   says plainly that the pump may still be running. 6 s after that (about 11 s after the
   last frame) the app sends a stop through the vent watch, the one every stop in the app
   goes through:
   - **Three attempts.** Each attempt writes one StopWork and watches telemetry for 6 s
     from the moment that write completed, re-checking every 300 ms. An attempt that ends
     without evidence waits 2 s, then the next writes StopWork again. On a link that still
     delivers frames the attempts go out about 8 s apart; on a link that is down each
     attempt fails at once, so they go out about 2 s apart.
   - **Only frames that arrived after the write count.** Every telemetry frame is stamped
     when it arrives at the phone, and only frames that arrived after the StopWork's write
     completed are evidence. The fall is measured from the last real reading that arrived
     before the write, and only if it is no more than 600 ms older than the write. All of
     these times come from the phone's monotonic clock (`SystemClock.elapsedRealtime()`),
     which a change to the phone's clock cannot move. A run of "no measurement" frames
     after a low last reading is shown as a strong sign of a vent, never as a confirmed
     one.
   - **Then the person is asked.** After the third attempt the app stops sending and asks
     — "The pump is not reporting a pressure fall" — with two answers, **I can see the cuff
     is vented** or **Keep trying** (a fresh watch: three more attempts), and tells the
     person to disconnect the tubing at the cuff if it is not clearly vented. The person's
     answer is recorded as theirs, never as the pump's. Until telemetry or the person
     settles it the stop stays unconfirmed: START is refused (invariant 3), and a last stop
     is sent if the screen is destroyed.
   - **Nothing resumes by itself.** If telemetry comes back during a run, the person
     chooses to resume, start over or end; a choice left unanswered for 60 s ends the run
     with a stop.
   - **Known issues** (CHANGELOG, 0.10.0). A stop lost at the moment the pump starts its
     own release can be read as the vent, because the readings fall anyway; requiring the
     pump's acknowledgement would close this, but only if the pump acknowledges every stop
     (release checklist H15, not yet run). And on a busy phone the app can say the pump is
     not reporting a fall after a stop that did vent, because the fall arrived between two
     of its checks; "Keep trying" clears it.
5. **A run survives the screen.** A running session lives in a foreground service; closing
   or leaving a screen never orphans a pump under pressure.
6. **Practice mode is never mistaken for a pump.** Simulated runs are labelled everywhere
   and filed as simulated.
7. **Connection is optional and authorizes automatic sync.** GrowthTrack linking uses
   the system browser; every new real finished session transfers to that grant. Disconnected
   and preconnection history never sends. No measurements, photos or notes are uploaded.
   Account-bound encrypted pending work retries automatically. Data transfer owns no pump connection and
   cannot start hardware. The diagnostic log console remains debug-only.

These are enforced by code review, by `WiringCheck` (static checks over the source), by
the self-test, and — as pump drivers arrive — by a conformance kit every driver must pass.

## Changes that need a safety review

Any change touching what pressure is commanded, when, or how a run stops: the session
engine, live adjustments, the pump protocol, Bluetooth handling, the run service. These
need **two approving reviews, one from a safety maintainer** (see `.github/CODEOWNERS`).

## Reporting a safety problem

If you find a way the app could command pressure it should not, fail to vent, or keep
running when it should stop: **do not open a public issue.** Report it privately — see
[SECURITY.md](SECURITY.md).
