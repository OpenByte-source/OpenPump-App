# 0010. The release app may reach one service, and only when the person connects it

Date: 2026-10-19 · Status: accepted

## Context
Until 0.10.0 the published APK held no network permission at all: INTERNET lived only in
the debug manifest, for the diagnostic console's log upload, and the release workflow
failed any build that asked for it. A contributed change (pull request #1) adds an optional
connection to GrowthTrack, a separate service where a person can keep their own sessions:
they sign in in the system browser, and the app sends their finished sessions to their own
account. That needs INTERNET, and ACCESS_NETWORK_STATE so a background job can wait for a
network instead of retrying blind.

## Decision
The release app may hold INTERNET and ACCESS_NETWORK_STATE, for this connection only, with
these limits:

- **Opt-in, and off by default.** Nothing is sent until the person connects their own
  account, and a master switch stops all sending without disconnecting. Earlier sessions are
  sent only if the person chooses that when connecting.
- **One destination, HTTPS only.** The release manifest forbids cleartext. The app talks to
  GrowthTrack's fixed endpoints and nothing else; there is no analytics, crash reporting or
  advertising code, and the diagnostic log upload stays in debug builds.
- **Sessions only.** Measurements, photos, notes and practice runs are never sent. Sessions go
  to the person's own account, so they are never described as anonymous.
- **Never near the pump.** The transfer code does not touch the pump, the run engine, STOP or
  the live run screen, and a failure to send never affects a run.
- **Pinned permissions.** The release workflow lists every permission the APK may hold. A
  permission that is not on the list, network-related or not, fails the release until the list
  is changed in review; the release must also forbid cleartext traffic.

## Consequences
"No internet permission" can no longer be said of the release app. The privacy promise stays
"nothing leaves your phone unless you export, back up or share it yourself", with the
GrowthTrack connection named beside it as the one opt-in exception. A new network feature,
an SDK that adds its own permission, or cleartext in a release now needs a deliberate change
to the pinned list or a new ADR. The routine preview from the first draft of the connection
(a second, read scope) was removed before release; bringing it back means revisiting this
record.
