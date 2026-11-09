# GrowthTrack connection: developer handoff

OpenPump can send finished sessions to the person's own [GrowthTrack](https://pe-growth-track.com/) account. It is optional and off until the person connects. Nothing about it touches the pump, the run engine, STOP/vent or the live run screen.

The connection was contributed by Karl in pull request #1 (his two commits stay as they are). The owner reviewed it on 19 October 2026 and kept it, with the changes below made on top. This page describes the behaviour those changes are meant to give; where a check has not been run on a device yet, [the verification record](growthtrack-verification.md) says so.

## What the person sees

**Setup (Ready step).** Under a small "Optional" label below the battery card there is one quiet divider row: the GrowthTrack mark, "GrowthTrack", "Keep your sessions in your own account too", and a chevron. The whole row opens the connect page. There is no Skip button, it never blocks Done, and it is shown once. The offer counts as seen when the person acts on it or leaves the step, not when it is drawn. Once a sign-in has gone through, the row's line reads "✓ Connected · sam@example.com" (or "✓ Connected" when GrowthTrack's answer names no account); after a sign-in that did not connect it says why, for example "Not connected · sign-in was cancelled". Tapping it again opens the GrowthTrack screen.

**Settings.** Its own "Connected apps" group, after App lock, findable in Jump to and in Settings search. One row, "GrowthTrack", with a status line in one of three states:

- green dot: "Connected · last sent today 18:42"
- amber dot: "2 waiting · sends when online"
- grey dot: "Not connected"

**Connect page (before the browser).** One page, three blocks:

1. "Connect GrowthTrack" and "Choose what goes to your account. You can change it any time."
2. "Sessions to send": switches for Girth plan, Length plan, My own routines and Manual runs, plus "Include sessions stopped early" (off by default). These are the same stored setting the GrowthTrack screen shows.
3. "Already on this phone": "Only new sessions" (default) or "Also send earlier ones · N of M can be sent". Only sessions that can actually be sent are counted and offered.

A dashed box says "Never sent: measurements, photos, notes, practice runs." The button is "Sign in to GrowthTrack". The earlier-sessions question is asked once per account.

**GrowthTrack screen.** A status card at the top: the GrowthTrack mark, "Sending to GrowthTrack", the account (email or name when the sign-in gives one, otherwise nothing), the master switch, and one status line ("Last sent today 18:42 · nothing waiting", a waiting count, or an error in plain words). Results and errors live inside this card; there is no collapsed "Connection update" and no stacked notes. Below it, "Which sessions" (the same switches; a plan switch is hidden for a track the person does not train; the type switches dim when the master switch is off), then "Sent and waiting sessions ›", a list of each session as Sent, Waiting, or Not sent with the reason. At the bottom, a quiet "Change account" and a red "Disconnect". Master off means nothing sends, and the account stays connected.

**After the browser.** The GrowthTrack screen runs in a task of its own, and the sign-in's return brings that task back. So the screen records where it was opened from (setup's Ready step or Settings) when it opens, and keeps that across the browser round-trip and a process death (instance state, plus one stored word). After a sign-in this phone started, the return lands once on one of two pages:

- **It connected:** a tick, "You’re connected", the account when GrowthTrack's answer gives one (otherwise no line), a "What happens now" card (which sessions go, from the stored switches, for example "Your finished girth plan and own-routine sessions go to this account.", then "Change this any time in Settings › Connected apps."), a primary button named for where the screen was opened from ("Back to setup" or "Back to Settings"), and a quiet "See connection details" that shows the usual GrowthTrack screen.
- **It did not:** "Not connected" (or "Sign-in cancelled" when the person declined in the browser), the refusal's own words (for example "GrowthTrack approval was declined. No sessions were sent."), "Try again" and the same way-back button. Try again opens the connect page again, goes straight to the browser when the connection only needs a new sign-in, or opens "Change account" when an existing connection is still in place.

Every way out of the GrowthTrack screen (the way-back button, Back on the page it was opened on or on a landing page, the lock card's back) brings the app's one screen to the front in its own task first, as it was (setup at Ready, or Settings), and never the home screen or the browser. It uses the launcher's own intent (`RunService.appIntent`), so it never creates a second session screen. While a run is live nothing GrowthTrack comes up, as before. The Settings row is read again when Settings comes back to the front.

Which page the return lands on is decided from what the client says the return came to (`GrowthTrackClient.callback` answers approved, declined, or another GrowthTrack error; a failure throws with its words), never from the wording. If the screen could not see the outcome (the return was answered with no screen, because a run was live or the local check was slow), it opens as it normally would and its status card says the result.

Known limit: setup's step lives for the life of the app's screen only. If Android ends the whole app while the browser is up, "Back to setup" brings the app back to the start of setup, not to Ready, and "Back to Settings" to where the app opens. The button's words still name where the person came from.

**Session summary.** No GrowthTrack button. Status lives on the GrowthTrack screen.

**Removed for now.** The routine preview (the optional `routines:read` scope, the library screens and their code) is out of this release. It may come back in a later one.

## Privacy and the app lock

- Every GrowthTrack screen, and the connect page, goes through the app lock: the Settings and Sessions areas (or the whole-app lock) gate it, using the same unlock state as the rest of the app, and under a disguise the PIN is asked through `LockHost`. A phone with no screen lock is never a dead end. It shows the disguised name and icon in Recents when Incognito is on, and is `FLAG_SECURE` under the same rule as the app's other screens ("Hide from recent apps"). GrowthTrack dialogs go through `Ui.dress`, are `FLAG_SECURE` too, and are taken down when the screen leaves the foreground, so none is usable over the app lock.
- The browser step respects Incognito the same way: the sign-in's way back is one of four return entries (an activity-alias per identity, `Incognito.RETURNS`), and the identity's own is switched on just before the browser opens, so the browser offers to continue to the disguise's name.
- The screens are portrait, like the rest of the app, and keep their state on rotation. Back from the page a screen was opened on leaves it: the connect page opened from setup's row goes straight back to setup. "About GrowthTrack" is the explanation behind the ⓘ on the connect page's first line.
- The sign-in return draws nothing and parses no model. While a run is live (or anything is unsafe) it finishes at once and the code is exchanged with no screen; with no sign-in waiting it does nothing; otherwise it brings up the GrowthTrack screen to show the result. No GrowthTrack screen opens or stays during a live run.
- Erase all data reports done only after the GrowthTrack state (tokens, queue, retry job) is erased as well, and says so if that part failed.

## What is sent, and when

A session is sent when it is a real (not practice) finished session of a type the person switched on, recorded while connected (or chosen under "Also send earlier ones"), and the master switch is on. Stopped-early sessions are sent only if that switch is on.

- **Never sent:** measurements, photos, notes, practice runs, and anything identifying the person beyond their own GrowthTrack account.
- **Deleting a session** withdraws it from the queue. A session already received stays in GrowthTrack.
- **STOP then Resume** keeps the original start time; the full session is sent under the same stable id, as an update of what was sent before, not as a fragment.
- **Earlier sessions from 0.10.0** have no `recordedStartMs`/`recordedEndMs`. Their times come from the session's own recording (the as-run rows hold real timestamps); a session whose recording cannot give them is not counted or offered.
- **Changing account** never sends the previous account's sessions to the new one. Reconnecting never wipes the queue, and starting a reconnect and then cancelling it leaves the working connection as it was.
- **One stable connection id**, random and separate from the OAuth `state` value.

### How a session is described

| Part of the session | Sent as |
|---|---|
| Warm-up | `warm_up` sets (agreed with GrowthTrack on 27 September) |
| A stage marked `Stage.traction` (the real length work) | `length_pumping` |
| Any other confirmed hold, through 6 s | `milking` |
| Above 6 s through 30 s | `rip` |
| Above 30 s through 120 s | `interval_pumping` |
| Above 120 s | `static_pumping` |

- The Length label comes from `Stage.traction`, not from the track, so a Length session's girth expansion and warm-up are not over-labelled as length work.
- Traditional (stitched) sessions are joined with a small millisecond slack, so their chunks join as they should.
- Timer lateness of a few milliseconds never creates Milking sets that did not happen.
- A pump change the pump carried out without acknowledging it is still counted, so the sets add up to the session.
- A short Bluetooth dropout or a Hold does not make the session unsendable.
- A routine name over 200 characters is shortened, not refused. The peak is rounded.
- All parts of one sitting stay one GrowthTrack session with one stable external id.

## Sending, retries and errors

- The sync thread works from its own snapshot of the saved sessions, taken on the main thread. It never calls `Store`'s shared statics.
- **Token refresh.** Only a definite refusal from the server (`invalid_grant` and the like) ends the connection. Offline, 5xx, 429, a timeout or a stopped job keep the connection and the queue and retry later. A crash in the middle of a refresh does not leave a stale "refreshing" marker or replay the old token.
- **Bad requests.** A request-level 400/422 does not drop the whole batch; the batch is split until the session at fault is found, and only that one is marked Not sent with its reason.
- **A session that always fails** stops being retried after a limit and is marked Not sent with its reason; it never holds back newer sessions.
- **413** splits that batch only; later batches go back to the normal size.
- **Retry timing** is measured on a clock that does not jump when the phone's clock is set back.
- **The sign-in callback** finishes cleanly when refused, never covers a live run, and accepts extra parameters the server may add (such as `iss`).
- **An expired sign-in request** shows as expired, not "waiting" for ever.

Retries run as a persisted Android job (id `72041`, `BIND_JOB_SERVICE`) that needs any network; Android may delay it under Doze or quotas, and reopening the app also resumes pending work. Requests carry at most 100 sessions and 1,000,000 bytes.

## Sign-in and storage

- Public PKCE client `gtc_bfa5809efbd314f246dd5d0f`, no client secret. Callback `gt-openpump://oauth/callback`.
- API origin `https://rxtqbktyeetmbigbpbdd.supabase.co/functions/v1`: `/oauth-authorize` (GET), `/oauth-token` (form POST), `/partner-ingest`.
- Scope: `sessions:write` only.
- Remote revoke: <https://pe-growth-track.com/connected-apps>. GrowthTrack has no token-revocation endpoint, so Disconnect removes the connection from this phone only; removing it on GrowthTrack's side is done on that page.
- Tokens, the verifier, queued payloads and receipts are AES-256-GCM encrypted with the Android Keystore key `org.openpump.growthtrack.v1`, written with `AtomicFile` into no-backup storage. There is no plaintext fallback. Only a non-secret connection marker is stored in the session JSON.
- The release manifest forbids cleartext traffic; the debug console's cleartext log upload stays in debug builds (`tools:replace="android:usesCleartextTraffic"` in the debug manifest).

## Permissions and the release

The release APK holds `INTERNET` and `ACCESS_NETWORK_STATE` for this connection only (`ACCESS_NETWORK_STATE` lets the retry job wait for a network on Android 14). Why, and the limits: [ADR 0010](adr/0010-optional-growthtrack-connection.md). The release workflow pins the full permission list and fails on any permission not on it, and on a release that allows cleartext.

## Where the code lives

`core/…/GrowthTrack*.java` holds the pure logic (protocol, client, session mapping, timing, onboarding, and `GrowthTrackReturn`: where the way back goes, the landing page, "What happens now" and setup's row) with JUnit tests that use a fake transport and never reach GrowthTrack. `app/…/GrowthTrack*.java` holds the screens, the browser callback, HTTP, encrypted storage, the manager and the retry job. `SessionActivity` only stamps the connection marker when filing and hands off; no HTTP runs on the pump or UI thread.

## Still to check on a device

See [the verification record](growthtrack-verification.md) for what has run and what has not. In short: a live sign-in against a GrowthTrack test account, the retry job through airplane mode, reboot and force-stop, Keystore behaviour, Incognito and the app lock on every GrowthTrack screen, and the labels against real recordings. None of this may run against a pump in use.
