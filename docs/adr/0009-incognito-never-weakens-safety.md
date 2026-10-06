# 0009. Incognito never weakens safety

Date: 2026-09-27 · Status: accepted

## Context
0.10 added incognito: a disguised launcher icon and name, discreet notification wording,
hiding the app from recent apps (and blocking screenshots/recording), quick hide, and
neutral wording for safety alerts on the lock screen. Every one of these can be turned on
by someone who wants the app to draw no attention on a shared or borrowed phone. That
pulls against the app's other job: a pump is under vacuum, and a stop that goes unnoticed
is a safety problem, not a privacy one.

## Decision
Privacy features are only ever allowed to change wording, appearance and navigation — never
whether STOP is reachable, whether a safety notice fires, or whether it fires loudly:

- STOP ("STOP · vent now") stays on every notification, in every mode, and quick hide's
  default action is STOP itself, taken through the same vent-confirmation path as the
  button on screen.
- Safety alerts (vent not confirmed, the app closed during a hold, the ring timer) sit on
  their own `safety-alert` channel: `IMPORTANCE_HIGH`, sound and vibration on, and
  `CATEGORY_ALARM` so Do Not Disturb's "alarms" allowance still lets them through. Incognito
  can make their *lock-screen* wording neutral ("Session needs attention now"); it never
  makes them quiet, and the full wording still shows once the phone is unlocked.
- The app lock that incognito can turn on re-asks after a quick hide, but it never covers a
  live run, a hold, or an unanswered safety question — a cover that could sit between the
  person and STOP is treated as worse than the privacy it would buy.
- A quick-hide pause (the run screen's Pause) that cannot go up, or that the pump refuses,
  ends in STOP rather than leaving a run unattended and unheld.

## Consequences
Incognito is safe to recommend without a caveat: turning every privacy switch on changes
what the phone shows, never what the app will do to get the pressure off. The trade-off is
explicit where it bites — `CATEGORY_ALARM` means a safety alert can sound even under Do Not
Disturb, and a quick-hide pause that isn't confirmed stops a run that might otherwise have
held. Both are deliberate: this app would rather interrupt someone than let a stop go
unnoticed.
