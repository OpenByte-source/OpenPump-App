# Changelog

All notable changes are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [0.10.0] — unreleased

The first published release, and the first with a signed APK to download. 0.9.0 was prepared
but never released: its notes are kept below, and everything in them is in this release too.

**Moving from a build you made yourself:** that build was signed with a debug key, so the
release can't install over it. Make a backup first (Settings › Device & developer › Developer
options, code 0000, › Backup & restore), uninstall, install the release, then restore the
backup.

### Added

**Setting up**
- A new phone opens on a six-step setup before Today: Welcome (what the app is and isn't, with
  a box to tick), Units and safety, You and your cylinder, Your routine, Privacy and feel, and
  Ready. The app's header is hidden while it runs and comes back on every way out of it.
  Settings › Run the first-run setup opens it again, and the "Get set up" checklist opens the
  trainer's own setup.
- The trainer's setup places each track by how long its session is now: one question per
  track, in minutes. The track starts at the level and week whose session, as the plan writes
  it, is the closest without being longer (0, or new to pumping: Level 1, week 1). Skip keeps
  the months placement; your months still decide when Levels 3 and 4 open, and your pressure
  answers still set the pressure. The Confirm screen says where each track starts and what
  placed it ("Level 1, week 6 — closest to your 20 min").
- Length has its own "Most you will go to", and its load and pressure are one value in the
  setup: type either and the other follows, converted at your length cylinder's bore. A
  cylinder marked for length is never picked for girth work.
- A load unit, lb or kg (Settings › Load unit, and the first-run setup); it starts from your
  size unit.
- Back on the trainer setup's first step asks "Leave the setup?" before it leaves; nothing is
  saved until you confirm the last step.
- A trainer set up before this version shows one card, "The plan has new choices": your values
  are kept, and the new questions (Long training days, Length load after month 3, When girth
  follows length) can be answered on one screen, or in the full setup.

**The trainer plan**
- Long training days, on the Trainer page and in the setup's week step, for a plan with both
  tracks on: "Alternate days, each track 3 days a week" (girth Mon/Wed/Fri, length Tue/Thu/Sat,
  about an hour a day; the default for a new setup), "Alternate on my days" (the setup asks which
  track leads), "Same days, stop
  growing at 90 min" (holds and strain sets stop growing once the day would pass 90 minutes) or
  "Same days, no limit". A plan set up before keeps the week it had. With one track on, the row
  is hidden and the plan follows your own days.
- A training day of 90 minutes or more is said ahead of time. The day counts every routine the
  plan runs that day, girth, length and the feeders, with warm-ups and rests. The Trainer page
  gets an "A long training day" card with the figure, and the setup's week step shows it too.
  Where the day runs both tracks, one tap moves the week to alternate days, and the message it
  leaves has Undo, which puts the week back exactly as it was. Nothing changes without the tap.
- The deload week asks when it starts. When one is due, "Time for a deload week" offers From
  tomorrow, From next Monday or Pick a day; today's session still runs, and "Not now" asks again
  tomorrow. A deload week rests both tracks together for 7 days. A week off the plan offers for any other reason starts tomorrow, so
  today is never taken away.
- The month-12 break: at month 12 the Trainer offers a 4-week break on both tracks, dated, from
  tomorrow. Girth comes back at Level 3, where it was, never at a higher pressure. The first week
  back is gentle: girth at 60 % of its working pressure with the gentle warm-up, and length at
  three quarters of its load, climbing back to it as your readings allow (a week under 2 %
  raises it by half the gap). Girth's Level 4 is offered again after 4 training weeks back,
  length's after the gentle week. You can come back early from the Trainer.
- Length is judged by one reading after the session (after against before), aimed above 2 %.
  Over 6 %: measure again; over 6 % twice lowers the load by 0.5 lb (at most once in 7 days,
  never under 5 lb). Under 2 % for a week: if this block between deloads already reached 2 %,
  the reading fell, and the week off comes forward instead of adding work; if it never reached
  2 %, one more strain set (up to 12, then load); still under a week after that set, the
  Trainer offers a week off or a girth block. This replaces the old 21-day rule.
- Girth readings aim for 6–12 % after the session (the swelling pumping always brings is
  included), at every level and style the rule applies to. Three readings under 6 % add sets;
  three over 12 % take sets off from Level 3 (never under the level's base). If the sets added
  still don't help, or the sets are at their top, the Trainer offers a week off or 4 weeks of
  length focus instead of adding more.
- Time under pressure in a girth session has a cap: 20, 30, 36 and 44 minutes at Levels 1 to 4,
  the fatigue block included. Holds the plan would add past it are not added; the pressure
  rises by the same amount of work instead, on the usual pressure step's clock and at most
  1.0 inHg a morning, never past your limits.
- Volume tops: interval girth stops growing at 14 sets at Level 3 and 18 at Level 4; length at
  12 strain sets. A level-up and a volume step never land on the same morning.
- With nothing measured for 4 training weeks, the plan steps the volume itself (girth from
  month 6 at Level 3 or 4: 2 sets, or 1 hold on traditional; length from month 3: 1 strain set,
  up to 6), then waits another 4. Measuring lets it adjust to you instead.
- "Most you will go to" above the usual top: the plan keeps its pressure step (+1 inHg every 3
  training weeks) up to it, asked once.
- Length strain sets start by your months: 2 before month 3, adding one every 3 training weeks,
  and 6 from the fourth month; readings take them on to 12. "Length load after month 3"
  (Trainer, with a length cylinder): Small steps over time (+0.5 lb every 2 training weeks
  toward 5 lb, then 12 lb, while strain sets are under 12) or Only after 12 strain sets. Above
  the usual top the pull follows the length pressure one step at a time; passing 12 lb before
  month 12 is said once, and 15 lb is never passed.
- "When girth follows length" (Trainer, both tracks on): on a day that runs length first, girth
  from Level 3 drops its fatigue block and runs a 2-minute climbing warm-up, lets its first 3
  holds climb, or runs nothing in its place.
- While a track gains on target, its calendar steps come half as often (girth pressure every 6
  training weeks, the length climb every 2 months, the small load step every 4 training weeks).
  When the normal step is due, the Trainer card says so and offers it: "Step up now" or "Wait".
  Gains that stall bring the normal pace back on their own; it never adds anything because of
  gains.
- Trainer › What it writes › Hold lengths: fatigue holds 30, 45 or 60 s (30 s by default), work
  holds 1 to 3 minutes from Level 3, the rest between blocks 3 to 5 minutes. The plan keeps its
  minutes at pressure; a shorter hold runs more of them.
- My pressure, "plan ± x": each track (girth, length) has one personal offset, set in the
  Trainer's My pressure card or by a setup answer above or below the plan's figure (kept, where
  it used to be clamped). Every routine the plan writes for the track runs at the plan's figure
  plus the offset; the plan keeps stepping up underneath, and the level's top moves with the
  offset. Going past the usual top is asked once; the ceiling, "Most you will go to" and 15 inHg
  never move, and somebody new to pumping keeps the first month's 6 inHg and 4 lb. Below the
  plan, time still counts (the counting line moves down with you) and the level gates read the
  plan's own figure. On the length track one offset moves the traction pulls and the expansion
  together.
- The Program's pressure choices are now gentle (plan −1.0 inHg), standard (the plan) and firm
  (plan +1.0 inHg), stacked on My pressure; they used to be the level's floor and the top of its
  band.
- Ramps settings in the Trainer (shown while a track's work sets are "ramp in each set", one set
  of settings for both tracks): where the first block starts (80 % of the day's working
  pressure, 60 to 95 %), how far a block climbs after a rest (2 steps, none to 3), how much each
  hold may climb (1.0 inHg, 0.3 to 1.0), whether lighter days keep the ramp (on), and whether
  the climbing holds count (on; off, only holds at the working pressure count), with a live
  preview. A change rewrites the trainer's routines at once, with the plan-change note and Undo.
- The gentle warm-up, for "I mark or bruise easily": the warm-up starts at 4.0 inHg and 60 %
  speed and climbs rep by rep, at most 1.0 inHg a rep, to the working pressure, its speed rising
  to the work's own. Its start, speed and step are in the Trainer's Gentle warm-up card, with a
  preview. It is the app's own rule, not the guidance's. On the length track it stops at 80 % of
  the work, as every length warm-up does.
- A paused plan, or the month-12 break, can be resumed where you were: "Resume where I was"
  keeps every track's level, week and working pressure (never higher than when you paused), with
  the gentle return's lighter first days. A pause of a week or more is still a layoff, and the
  plan still offers its step-back. "Set up again" is still there.
- A trainer guide, `docs/trainer-guide.md`: how the trainer places you, plans your sessions and
  moves you on, with a build check that fails when one of its numbers stops matching the code.

**The run screen**
- The run screen, redrawn: the routine's bar at the top, live (one part per set of the routine
  actually running, the set playing outlined and filled to its clock, rests in their own colour,
  skipped steps hatched and changed ones dotted) with the words "now: Work · next: Rest" under
  it ("now: Work · ramp" while a ramp plays inside a work stage); a status line that names the
  phase and only the phase ("WORK · SET 6 OF 10 · HOLD", "· DROP", "RAMP · STEP 3 OF 5",
  "WARM-UP", "REST · PULL IN 1:46") with elapsed / planned; the NOW card as the timer, with one
  line of where you are and what comes next ("Hold · set 3 of 10 · next: set 4", "Step 2 of 5 at
  −3.9 inHg · next: −4.4 inHg"); a bigger chart. The big timer is gone, and so is the glow behind
  the timer. The pressure is said once: the chart's reading is the live value, and the − / +
  strip shows the targets.
- The run screen's − / + strip: the one place that changes the step that is playing, for this
  run only. In a set it has four cells (pull, hold time, drop, drop time; steps of 1 kPa, 5 s,
  1 kPa and 1 s); in a rest, a ramp and the warm-up one (rest length, time per step, warm-up
  length; 15 s, 5 s and 15 s). A tap goes to the pump at once and the countdown does not
  restart; the set playing keeps its number. A longer or shorter hold or drop time keeps the
  block's number of sets and moves its end, so every set left runs whole ("Hold 0:50. This block
  now ends 1:40 later — 10 sets, each whole."), inside the two-hour stop. Press and hold
  repeats, with a light vibration per step; a button at its limit is dimmed, and tapping it says
  which limit it met. More › opens Adjust the running set (speed, and Rest of this block or This
  set only). It replaces the NOW card's "SET ›" row; "ROUTINE ›" stays.
- Settings › On the run screen › Where the − / + controls sit: Pinned above the buttons (the
  default), Under the chart, where the chart keeps its full height, or Chart first on ramps: on a
  ramp step and in the warm-up the chart comes first with the controls right under it, and every
  other step keeps them pinned.
- Rests on the chart: a hatched band "REST · m:ss left", the line on at the vented level once the
  vent is confirmed, the whole rest in view and a dotted ramp to the next pull. Past rests keep a
  lighter hatch. The reading says "vented" in a rest, and the NOW card says "REST" once.
- The chart's line takes the colour of the step playing (work and ramp lime, warm-up blue, rest,
  a pause white), with a soft fill under it.
- In the last ten seconds of a rest the status line pulses "PULL IN 0:10"; Settings › On the run
  screen › Vibrate before the pull (off by default) adds a short buzz.
- Coming steps, a settings list for the steps still to come, for this run only: the step
  playing is shown read-only ("change it with the − / + on the run screen"); each set of a stage
  has its own card ("Sets 6–10", "Ramp · 5 steps"), so the later sets of the stage playing can be
  changed too; a later work block has its sets, hold each, drop to and drop time; a rest its
  length; a ramp its number of steps and time per step, its pressure range shown and unchanged;
  any later step can be skipped. Each card has its own undo, the list an "Undo all", and the
  total shows the change and what it "was". It never raises a pull, and stays inside the set
  advisory and the two-hour stop.
- Settings › On the run screen › Run colours: colour the run by step (status line only by
  default, or a soft or strong tint), a colour per step kind and four sets (Default, Colour-blind
  safe, High contrast, Calm). STOP stays red; colours too close to it, or to each other, are
  refused.
- Past the run screen's usual limits - the strip's 10.0 inHg, the routine offset's plan step, a
  length routine's offset - is now asked once for each new highest value in a run instead of
  refused. The hard limits are refused as ever.
- The drop between holds may be set above −3.0 inHg (a smaller release), asked once, and always
  at least 1.0 inHg under the pull. The seconds between holds never count as time at pressure.
- Every stopped session's summary says why it ended: STOP, a lost link, a pump not reached,
  Android closing the app, a safety stop.
- A run rejoined after the app closed, or resumed after STOP, is filed as one session, and its
  summary says so ("Rejoined once after the app closed", "Resumed once after STOP").

**Privacy**
- Settings › Privacy: incognito. Every feature has its own switch, and "Incognito mode" turns
  the ones you choose on and off together. All off until you turn them on.
- The home-screen icon and name can be one of three disguises instead of OpenPump: "Fitness
  log" (a pulse line on teal), "Habits" (a check mark on green) or "Notes" (a lined page on
  yellow). Pick one in Settings › Privacy › Home-screen icon and name. The app wears the one you
  chose in its top bar, in recent apps and on its long-press shortcuts; the app lock and the
  phone's fingerprint or PIN prompt show its icon and name, never OpenPump's; and from Android
  13 so does the splash as the app opens (below that, the splash shows no logo at all). A backup
  is offered as "<disguise>-backup.zip" and an export's subject says only "Data export". The
  app's passing messages are drawn by the app itself while a disguise is on, because Android's
  own carry the real logo. Your launcher may take a few seconds to update, and home-screen
  shortcuts or the widget may need adding again. Android's own app list and notification
  headers still say "OpenPump".
- Discreet notifications now read "Session running · 12:30 left" (no pressures, routine or track
  names) and show "Contents hidden" on the lock screen. STOP and Pause stay, on the lock screen
  too. STOP now reads "STOP · vent now".
- Safety warnings (vent not confirmed, the app closed during a hold, an interrupted run, the
  ring timer) can read "Session needs attention now" on the lock screen, and show their full
  words once the phone is unlocked. They still ring and vibrate. The notice shown while the pump
  vents after a stop reads "Session ending" there instead.
- Hide from recent apps (the old "Block screenshots & recording", now in Privacy), quick hide
  (double-tap the top bar to leave - or, with a screen reader, its "Quick hide" action; during a
  run it can leave, Pause, or STOP — STOP by default, through the normal vent; a pause that
  can't go up, or that the pump refuses, stops the run instead), discreet reminders ("Reminder"
  only), app lock when incognito (it asks again when you come back after a quick hide, never in
  front of a run, a hold or a safety warning), and hiding the home-screen widget.

### Changed

**The trainer plan**
- A week counts for a track with 2 full sessions of it, or 3 sessions as before. A full session
  is one that delivered what its routine asked that day (girth: its minutes at pressure; length:
  the time its routine plans); two shorter ones add up, two runs in one day are one session, and
  one session never counts alone. A four-day week of girth twice and length twice now moves both
  tracks, their pressure steps, level gates, the length calendar and the deload. With both
  tracks on, the deload's week counts when both tracks' weeks count, or with 3 training days
  that include each track. Weeks are counted from the session log, so past weeks of 2 full
  sessions per track now count too. Two full sessions count only once the track has no more
  scheduled days that week, so a week of three days counts on the third as before and nothing
  moves sooner. The Trainer's This week card says why a week waits.
- The warm-up of a trainer routine is about 5 minutes: holds from 3.5 inHg growing from 30 to 60
  s, climbing at most 1.0 inHg a rep to the work (length: to 80 % of the pull). If it ends under
  the work, the first holds after it carry on climbing, 1 kPa a hold. "I mark or bruise easily"
  keeps its gentle warm-up instead; Library and manual routines are unchanged.
- No run lasts more than 2 hours on the whole clock: a trainer routine built longer is trimmed
  from its last work holds ("Trimmed to stay under 2 hours"), and a live run stops at 2 hours.
- A day that runs both tracks treats them as one day. Anything it changes applies to that run
  only: your saved routines stay as they are, and the box before START lists every change in
  plain words.
  - With a length cylinder, the length session runs no expansion after its pulls, and the girth
    session keeps all its sets. With length as expansion only, the girth session gives 5 of its
    sets to the length session's expansion, always keeping at least one, and the plan counts
    them as done.
  - A session started within 30 minutes of the other track's has no warm-up ("No warm-up: your
    length session ended 12 min ago"). Its fatigue holds and strain sets stay.
  - The Tissue response test runs only in the day's first session. In the second it is marked
    "not run: second session today".
  - When a measurement is due that day, the girth before-reading is taken before the length
    session, so length work doesn't spoil it. A girth before-reading taken within 4 hours of a
    length session is marked "after other work" and left out of the girth yield.
  - The guidance puts length first. If girth runs first, the length session's START box says so
    and offers the expansion only, with no traction; START then also asks whether to do the
    5-minute hand release first.
  - After a session with traction, Today still offers the next track, labelled "from HH:MM".
    That is 40 minutes after the pull, or sooner if you answer "It cleared" to the numbness
    question. You can start earlier at any time.
  - The box before START shows the day's minutes so far, and before a session that would take
    the day past 90 minutes it says so (the guidance's limit for a day of both tracks). The
    warning never stops a session; Trainer › Both tracks in a day can turn it off.
  - During a length girth-focus block, the girth track pauses. Today doesn't offer girth
    sessions, and the weeks of the block don't count as missed.
- Feeders run on girth days only, 4–6 hours after the girth session, 2 a day at least 4 hours
  apart. Today and the feeder card show when the next one is due, and you can start it sooner
  with one tap.
- Traditional girth grows the way the guidance's long-hold path does. Level 1 starts at 3
  five-minute holds and adds one every 4 training weeks at the level, up to 6; Level 2 runs 6;
  Level 3 runs the fatigue block and then 6, adding one every 4 training weeks up to 7; Level 4
  runs 8 (up to 9 by readings). Its rests are 30 s at every level. The session's time cap holds
  it too: holds past the cap become pressure. It used to run 2 holds at every level, growing
  only by yield. A traditional routine saved before is rewritten once, with the plan-change note
  and Undo; above Level 1 it builds up from half the level's count (3 at Level 2, 4 at Levels 3
  and 4) or what it was running if more, adding one hold every 2 training weeks.
- A ramp in each set climbs from 80 % of the day's working pressure, at most 1.0 inHg a hold, to
  the working pressure and then holds there; each block after a rest climbs only 2 steps. It
  used to climb from the level's floor across every hold of the block. A lighter day now keeps
  its ramp, climbing to the lighter pressure. A climbing hold under the line time is counted
  from counts nothing and is not made up, so a Ramped session is about as long as fixed holds;
  the level credits it at the rate the session delivered its target. Existing Ramped routines
  are rewritten to the new ramp once, with a note that says so.
- The fatigue block is 15 holds of 30 s by default (it was 10 of 45 s), the same minutes.
- Level 1 interval girth's pressure follows the guidance's week table while the sets are being
  added: 5 inHg, then 6 from week 4 and 7 from week 8, one step of at most 1 inHg at a time, only
  upward, and only once each of your last three sessions at your current pressure held its
  planned minutes. A table pressure counts as reached within 1 kPa, so 6.8 inHg (23 kPa) is the
  table's 7. It used to wait until sessions held 20 minutes. The 6 inHg first-month cap, the 8
  inHg Level 1 cap, your pressure ceiling, deload weeks, the gentle return and the safety flag
  all still hold it back.
- Moving from Level 1 to Level 2 never lowers the pressure: Level 2 starts at the higher of 8
  inHg and where Level 1 was.
- Girth Levels 3 and 4 need the volume as well as the month. Level 3 comes at month 6 once each
  of your last three sessions held 30 minutes at pressure, where the guidance's Level 2 stops
  adding; Level 2's week table runs on to those 30 minutes (15 sets, weeks 27 to 32, with a
  deload row in week 29) instead of stopping at 26. Level 4 at month 12 once each held 20
  minutes, and the month-12 choice is offered then. Traditional girth asks each session's own
  planned minutes. Length levels still follow the calendar.
- A deload week rests the length track too: no length session and no traction that week. The
  pulls come back at your normal load after the week. A routine that pulls can still be
  started; the START confirm says it is a deload week.
- The length track with no cylinder that pulls moves up at months 3, 6 and 12 like the length
  track with one; its expansion then runs to that level's top.
- "I mark or bruise easily" now says what it does, in the first-run setup and the trainer's own:
  the gentle warm-up, and the gentle return after a week or more away.
- The "1st and 4th session" measurement cadence counts the sessions of the track you are
  starting, not every session.
- Post vs pre (and the length reading the plan uses) pairs each after-reading with its own
  session's baseline, so two measured sessions on one day are two pairs.
- The "Ready to progress?" step-up offer no longer appears on trainer routines — the trainer
  already runs its own progression for those.
- The Trainer's wording and screens, reworked in plain words: one decision card at a time, the
  plan's new questions alone, the setup in plain words, and longer explanations behind a small
  ⓘ. Where the app refers to its sources it says "the guidance".

**The run screen**
- The run screen's buttons follow the step, and Hold is now Pause (Resume while paused), always
  the first button: Pause, Skip these sets, +30 s hold and Rest in a set; Pause (greyed, the
  cuff is vented), End rest and +30 s rest in a rest; Pause, Skip step and +30 s step on a ramp
  (one whole cycle, "+0:42 step", on a step of several cycles); Pause, Skip warm-up and +30 s
  warm-up in the warm-up. "+30 s hold" stops at 4:15 and, like a longer hold on the
  strip, keeps the number of sets and moves the block's end. The notification says PAUSE /
  RESUME. STOP · vent now is unchanged.
- No message on the run screen covers STOP: the strip's and the buttons' show above the whole
  pinned footer (the − / + strip, the buttons and STOP), and one said over a sheet, or the
  pump's answer to a change, shows near the top of the screen. None is Android's own toast.
- The ROUTINE card on the run screen keeps the plan's total instead of letting it climb while
  the run plays; time added by a Pause, an inserted rest, a changeover wait or at-pressure
  timing now shows beside it as "+m:ss", the way the NOW card already reports it for one set.
- The run screen's "run ends in" line now names the after-session tissue test when this run has
  one, since that figure counts it and the ROUTINE card's total does not.
- When the hold's count is done, the hold screen's title says "Hold complete — measure now"
  instead of a passing message.
- The flow's "STEP n OF m" bars are no longer drawn on the run screen; the Today and Library
  stage bars draw through the same code as the run screen's.
- The run screen, the summary, Library, Progress, Settings, Compare, Export and the camera:
  plainer labels, one type scale, and explanations behind a small ⓘ.

**Measurements and progress**
- The Length · girth chart is drawn by day: a before and an after on one day sit together,
  joined by a short line.
- "Hide from recent apps" (the old "Block screenshots & recording") now covers the app's dialogs
  too, so they can't be screenshotted or recorded either.

### Removed
- The run screen's list of upcoming steps under the NOW card, with its "Edit ›" for a step not
  yet started: Coming steps is now the one place to see and change later steps, and the NOW card
  says what comes next. The Settings row "Coaching line", whose sentence lived under that list,
  went with it.
- The 21-day rule that started a deload when length readings stayed low; the length reading
  rules above replace it.

### Fixed
- "Undo this change" on a plan update put back a routine with no holds in it (its sets had been
  tidied away with the old shape), and START then offered to run it: 0 cycles, only the rests.
  Undo now puts back the whole routine and the hold length the change came from (fatigue holds
  back to 45 s); one that cannot be put back whole is left as it is, and said so. START never
  offers a routine with no holds: it says why and offers to rebuild a plan routine from the plan.
- The run bar and "set N of M" count the same holds in the fatigue block: its two climbing holds
  are sets 1 and 2 of 15, and after "Adjust the running set › Rest of this block" the bar keeps
  its parts and its fill never goes back. "Skip these sets" hatches the sets it skipped, and a
  skipped warm-up is hatched, instead of drawing them done.
- A plan routine saved before "Most you will go to" was lowered or "New to pumping" was turned on
  (or saved by an earlier build, which read "Most" at setup only) still pulled past the new
  limit. START now holds every step of a plan routine, and its check pull, to today's hard
  limits and says so in one line; and a difference that comes only from a hard limit no longer
  reads as an edit, so the plan rewrites the routine as usual.
- Saved Ramped, traditional and ascending routines now follow reduced days and plan changes, and
  Firm or Gentle no longer undo a lighter day.
- "Rest of this ramp" moved the later steps under the safety ceiling alone, so a raise could take
  them past a first month's 6 inHg, "Most you will go to", 15 inHg or the strip's usual 10.0 inHg
  without a word. Each later step now stops at its own hard limit, and at the strip's usual limit
  unless you have confirmed past it; the one-time warning asks about the highest pull the change
  puts anywhere on the ramp, and a step held at a limit is said.
- A ramp's step that was shorter than one pump cycle, or that a live change left ending inside a
  hold, could pull on with no drop. Every ramp step now runs whole cycles of its own hold and
  drop; a live hold or drop-time change, Time per step, the sheet's Reshape and recount, and a
  restart of the step all keep that, and the extend button on such a step adds one whole cycle
  ("+0:42 step").
- No edit of a run raises a drop past −3.0 inHg by itself, or past the step's own planned drop.
- "+30 s hold", the strip's hold and Resume carry the hold under way on; none restarts it.
- A recalibration filled the pressure answers with the plan's figure alone, so Confirm wrote the
  offset back to 0 and someone on "plan −2.0 inHg" came out 2 inHg harder, unasked. The answers
  now start at what each track runs at (the plan plus the offset); left as they are, the offset
  is kept.
- Recalibrate pre-fills your months in total, what you answered at setup plus the whole months
  since. It pre-filled only the months since you first set up, so confirming it could place you
  lower than you are.
- A traction load answered before "New to pumping" was turned on stayed hidden behind it and was
  saved anyway. With New on the answer is now ignored: a first setup starts at the plan's own
  load, a recalibration keeps the load on file.
- A session's pressure scale was read from a file as it stood, so a hand-edited backup could pass
  the Level 1 gate and drop the counting line. It is now held the same way as a routine's.
- On a day with both tracks, the girth holds given to length's expansion came off the end of the
  session, so the girth session never reached the plan's pressure and interval girth with the
  length track on stayed at Level 1 all year. They now come out spread across the session, each
  block keeping its shape, the session always keeps a hold at its working pressure, and the Level
  2 gate counts the sets given up.
- "Most you will go to" was read at setup and nowhere after it; it now holds every prescription,
  Adjust first and the run screen's controls on a plan's routine.
- The setup's "Kept, not corrected" was not true for girth (the answer was clamped); the answer
  is now kept, as the track's offset, and says so.
- Adjust first read the months since enrolment for its limit instead of the plan's month,
  holding an experienced person to the first month's 6 inHg; and its "+" (like the old
  working-pressure card's) could lower a pressure above the limit. Both fixed.
- A working-pressure answer of 8.0 inHg (stored as 27 kPa) was read as under the Level 2 floor,
  so somebody at 6 or 12 months was placed at Level 1. The setup now reads the floor in whole
  kPa, as the app stores and commands pressure.
- A set that yield adds is now kept. It used to vanish as soon as the run of low readings ended.
  After any yield change, three new readings are needed for the next one. Moving up a level
  keeps your total set count, up to the new level's top.
- A pressure step now waits for 3 training weeks at your current working pressure, counted from
  when that pressure last changed, not from when the plan last rewrote your routine. The lighter
  days after each deload used to restart the count, so a beginner stayed at the starting
  pressure until month 4. A step still never comes in a deload week, while the gentle return
  after one is running, or under a safety flag, and never goes above the level's cap, your
  pressure ceiling or the app's maximum.
- Traditional girth's pressure can now step up, and traditional girth can now move up to Level
  2: both asked for 20-minute sessions, which its holds never reached; they now ask that each of
  your last three sessions held the minutes its own routine planned (at 8 inHg for the gate, for
  two training weeks).
- A level is no longer proposed on a lighter day of the gentle return after a rest, on any
  track: the proposal comes on the first full day back.
- Moving up a level restarts the count toward the next pressure step only when it changes your
  working pressure. Level 2 to 3 and 3 to 4 keep the pressure, and now keep the count too.
- Level 2's pressure steps start once each of your last three sessions held 20 minutes at
  pressure, as the guidance has it. They waited for 30, which the Level 2 week table never
  reaches. The cap is still 10 inHg.
- Wording that disagreed with what the trainer does: setup's confirm screen no longer calls the
  length track static; the Tracked note says at-rest readings count too; the "after other work"
  note says when it applies.
- At Level 2 the Trainer's "The next weeks" card showed Level 1's rows, and its header and the
  position line counted Level 2's weeks from 1. They now show Level 2's own rows, numbered 18 to
  32 as the table is. The next weeks show what the routine runs, never another level's table.
- "Vent not confirmed" and "The app closed during a hold" now ring and vibrate, on a new "Safety
  alerts" notification channel. In earlier builds they were silent: a stop the app could not
  confirm could go unnoticed with the phone in a pocket. The ring timer's "take it off" is on
  the same channel now, with a notification of its own that a training reminder can no longer
  replace.
- The ring timer's "take it off" is now a system alarm, so it still arrives if the phone closes
  the app or you leave the summary. It is on time when the phone allows exact alarms; on Android
  14 and later that is off by default, and the ring timer card says so and opens "Alarms &
  reminders" to allow it (meanwhile the alarm may come a few minutes late). A timer still running
  when the phone restarts is set again, and one that ran out while it was off is announced as it
  starts up. If the phone closes the app while the timer runs, that session's summary shows the
  running countdown again when you come back to it, and "Took it off" still cancels the alarm.
  The ring timer says "1 minute is up", not "1 minutes are up".
- Saving now forces your data onto the phone's storage before it replaces the old file, so a
  sudden power cut can lose at most the save in progress, never the one before it. The same goes
  for the run recordings and a backup restore's photos.
- Data written by a newer version of the app is no longer lost when an older one saves: a backup
  from a newer app, restored here, keeps the settings and reading or photo details this version
  doesn't know, and writes them back unchanged.
- Photos left behind when the app was closed in the middle of taking a measurement (so the
  reading was never saved) are now deleted the next time the app starts, once they are more than
  a day old. A photo any reading uses is never touched, and nothing is deleted while your data
  can't be read or when you have no readings at all.
- A standardised reading now records the vacuum the pump reported at the end of the hold's
  30-second count, when the hold standardises, instead of when you tap Save, and readings are
  compared on that. The vacuum at Save is used only if none was reported at the count's end;
  photos taken in the two minutes follow the same rule. Readings saved before keep the vacuum
  they were saved with.

### Known issues
Carried over from the 0.9.0 notes; none of them is new in this release.
- On a pump that pulls once and then coasts, a Trainer hold can sit right at the level's floor,
  so only its first half-minute or so counts as time under pressure. "Net per session" reads low
  and the plan may not move you up. The fix waits for a hardware log of how fast a hold coasts.
- If a stop command is lost at the moment the pump starts its own release, the app can read that
  release as its vent. Whether the pump acknowledges every stop is still to be checked on
  hardware; if it does, the app will require that acknowledgement.
- If the app is force-stopped during a hold, nothing is left running to vent the pump. The next
  launch tells you to check the cuff.
- On a busy phone the app can say "The pump is not reporting a pressure fall" after a STOP that
  did vent: the fall arrived between two of its checks. It errs on the safe side; "Keep trying"
  clears it in a few seconds.

## [0.9.0] — not released

Prepared as the first release that isn't a beta, but never published: everything below ships
in 0.10.0. Where 0.10.0 changes something here, its notes above say so.

### Added
- Signed release builds, published on the Releases page with a SHA-256 checksum.
- A launcher icon on Android 7.0 and 7.1, which had none — and a new icon everywhere.
- A full disclaimer in one place, [DISCLAIMER.md](DISCLAIMER.md), with a short version in
  the README and in the app's Help.
- Measuring under the hold: once the count finishes you have two minutes to measure, and
  they run until you save, not only while the hold screen is up. When they pass you can
  hold again, keep the reading as it is (kept apart from your standardised readings), or
  vent and measure at rest.
- Before START, the app says what today's session involves (the hold, baseline, release,
  seal check, Tissue response test, the routine, the after-reading) and roughly how long.
- Every measuring method (BPEL, BPSSL, BPSL, NBPEL, NBPSL, MSEG, MSSG, Standardised) is
  explained in one line, with how to take it.
- Photos at rest: at-rest readings have their own photo slots. Every photo records how it
  was taken (standardised at a pressure, held, or at rest), and Compare only puts photos
  taken the same way side by side.
- "Photo during the hold" now works: when you turn it on and tap "Measure now" after the
  hold's count, the camera opens first, while the pump still holds. It is off unless you
  turn it on.
- If the app was closed while the pump was holding or venting, the next launch says so and
  asks you to check the cuff is vented.
- On Android 13 and newer, the app asks once, before your first run or hold, to show
  notifications, so a run or hold can show its countdown and a Release button and warn you
  when a vent can't be confirmed. Saying no is fine; Settings › Session can ask again.
- readings.csv has three more columns at the end: `method`, `phase` (before or after the
  session) and `kind` (standardised, at rest, or no hold recorded). A length or girth that
  wasn't measured is an empty cell, never 0.

### Changed
- New look for the project and the app: the OpenPE and OpenPump names, logos and icon.
- A calmer app. The top bar is one pump chip that opens the connection, and the tab bar
  uses one set of outline icons. Coloured edges are kept for warnings only, and numbers
  use one typeface with even-width digits.
- Today leads with one main action. The simulator is a quiet banner, not a red card.
- The run screen shows the live pressure once, above a chart with round numbers on its
  scale. It says "On target" when the pump is on target, and nothing hides behind the
  STOP bar.
- Progress: the chart's controls are compact, one chip per series drawn in its own line,
  with a round-numbered scale.
- Plainer words across Settings and the summary. The count question is one line, with
  the reasons behind "Why?", and the answers to "How did it go?" are no longer in alarm
  colours.
- Lowering the pull mid-run now says whether the pump will bleed down to it or has to be
  vented, since the pump only pulls.
- The pump is shown by its real name, Epic Hydro PE Pump.
- The STOP button reads "STOP · vent now": it sends the vent at once, and the app then
  confirms the pressure is falling (it used to promise it "releases all pressure").
- Standardised and at-rest readings are two equal ways to track progress. The screens no
  longer rank one below the other, and each kind is only compared with its own.
- The measurement settings are gathered under Settings › Measurements (how a routine ends
  stays under Session).
- "Skip the hold" does what it says: the pump vents, then you measure at rest.
- The release step says what the pump really does.
- The after-session tissue test is now the "Tissue response test". New routines start with
  it off; your existing routines keep their setting.
- During a hold the app keeps running in the background, so the hold's time limit ends it
  on time even with the screen off. Leaving the app during a hold vents the pump.
- During a hold, the pump vents before the app opens the phone's camera app, the gallery
  or a share sheet. The app's own camera still works under the hold. If the pump can't be
  reached, Bluetooth settings still open, so you can reconnect.
- If a hold ends without you choosing it (the link drops, or you leave the app), you come
  back to the same reading, now at rest, with its photos. Numbers typed under the hold are
  cleared, and nothing saves until the vent is confirmed or you say you can see the cuff
  is vented.
- The Hold button during a routine now vents after the same limit as every other hold
  (Settings › Measurements, "Vent any hold after at most", 5 minutes by default). The run
  screen shows when it will vent; at the limit the run stops, as STOP would. A limit that
  would land within seconds of the pump's own short step down (8:30 is the one setting that
  does) vents a few seconds earlier instead, never later.
- When a run ends, however it ends, the app keeps running in the background until the
  pump's vent is confirmed, with a notification saying it is venting. If the vent can't be
  confirmed, the notification says so and stays until you see the question in the app.
- When the Hold's limit or the two-hour limit stops a run, the summary says which, and so
  does the venting notification.

### Removed
- The diagnostic console is no longer in the downloadable app. It sends raw commands to
  the pump outside every safety check, so it stays only in debug builds you make yourself.

### Known issues
- On a pump that pulls once and then coasts, a Trainer hold sits right at the level's
  floor, so only its first half-minute or so counts as time under pressure. "Net per
  session" reads low and the plan may not move you up. The fix waits for a hardware log of
  how fast a hold coasts.
- If a stop command is lost at the moment the pump starts its own release, the app can read
  that release as its vent. Whether the pump acknowledges every stop is still to be checked
  on hardware (release checklist H15); if it does, the app will require that acknowledgement.
- If the app is force-stopped during a hold, nothing is left running to vent the pump. The
  next launch tells you to check the cuff.
- On a busy phone the app can say "The pump is not reporting a pressure fall" after a STOP
  that did vent: the fall arrived between two of its checks. It errs on the safe side; "Keep
  trying" clears it in a few seconds. Counting that fall safely needs the pump's receipt for
  the stop (release checklist H15); until then the app would rather ask than assume.
- The "Vent not confirmed" notice makes no sound and doesn't vibrate. If the app can't
  confirm a vent while you aren't looking at it, only the in-app question and a silent
  notification say so; check the phone after a STOP. 0.10 moves it to its own urgent channel.

### Fixed
- With a safety ceiling below 17 kPa (5.0 inHg), the guided start pulled to the ceiling but
  waited for 17 kPa, so the routine could never begin and the wait ended at its ten-minute
  limit with a vent. It now waits for the pressure it pulls to. Nothing it sends changed.
- Vent confirmation only counts pump readings that arrive after the stop, timed by a clock
  that can't jump backwards. A stop that never reaches the pump warns and retries instead
  of being read as vented (one exception is under Known issues).
- A small dip the pump makes by itself, like the 1 kPa step it takes every few minutes
  during a Hold, is no longer taken as proof that a stop vented. The pressure now has to
  fall at least 2.5 kPa, so a lost stop warns and retries instead. A real vent may take up
  to half a second longer to confirm; from a very low pull (7–8 kPa, about 2 inHg) it can
  take a few seconds longer, or the app asks you to check the cuff.
- A reading can't be saved as "at rest" until the pump has shown the vent, and a number
  typed under a hold never becomes an at-rest reading.
- A vent the app couldn't confirm is never shown as confirmed, and when the app loses track
  of one it keeps telling you to check the cuff.
- If Android closes the screen during a hold, the stop is sent before the connection
  closes, and the notification's Release button never does nothing.
- Back on the camera screen no longer closes the app.
- Tapping the app's notification, its widget or a reminder during a run or hold closed and
  reopened the screen, which ended the run. It now brings back the same screen.
- The "run was interrupted" notice could be replaced by a measurement reminder.
- The app now only ever has one main screen. A shortcut used during a run or hold no
  longer ends it, and a second copy of the screen can no longer take the run's
  notification and background protection away.
- With the whole-app lock on, a shortcut can't open anything until you unlock.
- On Android 12 and newer the app no longer asks for location on every launch (it doesn't
  need it there; Bluetooth scanning never uses your location).
- Compare never pairs a photo with itself, and never mixes photos, readings or chart
  smoothing taken different ways or at different hold pressures.
- A photo taken under a hold was labelled and saved as "At rest".
- A photo brought back with Undo could be deleted a few seconds later.
- Photos from a reading you give up are deleted from the phone; photos you kept are never
  touched. (If the app is closed in the middle of a reading, that reading's photos stay.)
- Connecting the pump from a reading screen brings you back to that reading, with its
  photos and numbers.
- The Tissue response test now starts on a pump that reports no reading once
  vented. It used to give up with "no after-reading", and on the simulator both tests did.
- A rest chosen, or left up, on the last set can no longer restart that set over the
  after-session test.
- Live changes during a run: every tap counts and shows at once. A gesture toward less
  pull can never raise any pressure, whichever step the adjust sheet was opened on.
  Nothing rewrites the pump's table while a Hold is up.
- The summary compares a session with what its own routine planned, counted the way Set
  timing counts (by the clock, or at pressure only). It used to compare every session
  with the level's 20-minute target. A reopened summary shows only its own record.
- "At pressure only" times a set by the same line the net counts from, and doesn't count
  planned drops as time owed, so the clock and the summary agree. Planned cycles are the
  routine as it started.
- The Progress chart stays inside its card, and its labels are no longer cut off.
- The run screen always shows what the pump was actually given. It used to drift from it
  at every step boundary, during a paused clock, and after a hold or a rest.
- A stalled clock says what is wrong — a cuff still climbing, or a seal that has let go —
  and keeps saying it for as long as the stall lasts.
- The step timer counts from when the step really started ("0:00 of 6:00" thirty seconds
  in is gone).
- A step can no longer be ended early by a leftover timer, which could file a 12-minute
  run as complete after 3 seconds.
- The trainer keeps your "how long have you been pumping" answer, instead of treating
  everyone as being in their first month.
- Setup's cylinder step can name and resize cylinders, instead of adding a fixed default.
- Hostile input: a pump reading that isn't a real number is refused, and a backup file
  can no longer write outside its own folder. Both are now covered by tests, along with
  junk and tampered save files, zip bombs, and a clock that jumps backwards.
- Two layouts that broke on small screens with large text.
- CI runs again: it no longer hangs accepting Android SDK licences.
- A live change the pump refused is no longer shown or counted as taken. The screen goes back
  to what the pump is running and says so, for example "The pump refused that change — still
  at −7.1 inHg". The at-pressure clock, the next steps and a ramp's remaining steps only move
  once the pump has taken the change, and a Hold the pump refused never says HOLDING.
- When the pump doesn't reply to a live change - it often carries it out anyway - the app no
  longer guesses. It sends what was in force before again, so the pump and the screen agree,
  and says so; if the pump still doesn't reply, it tells you the pump isn't answering and what
  it may be running. A Hold is limited from the moment it is sent, so one whose reply was lost
  still vents at the hold limit.
- A START that had to wait behind a table rewrite can no longer land on a later step. A
  change made just before a step ended, with its reply lost, could send the step before back
  to the pump in the middle of the next step's rest, and stop the rest's vent from being
  watched. Now it does nothing if the step, a rest, a hold or the table has changed.
- The pump's replies are no longer matched only by order. A reply that may be for more than one
  START confirms none of them, so one that comes late can't be taken for the next START's; a
  reply later than 5 seconds is ignored and the app sends what was in force again.
- A refusal (`2C FD`) counts wherever it lands: the app writes the pump's table again and sends
  what was in force. If the pump refuses that too, the run stops and the pump vents, and the
  summary says why.
- "The pump isn't answering" now sticks until the pump actually takes something the app knows
  it sent. Until then every cell says "not confirmed" and the pull shows the higher figure the
  pump may be at.
- After Resume, the hold's time limit stays armed until the pump takes the Resume. A Resume the
  pump refused used to leave it holding with no limit.
- Changing the routine while the app was correcting the pump could leave the pump above the
  screen for the rest of the step. A whole-routine offset tapped in the moment a correction waited
  to be sent made the app drop that correction: the pump stayed at 30 kPa with the screen at 24.
  The offset, Edit upcoming, the ramp's reshape and step count, and +30 s's refresh now wait while
  the pump may still answer, and say so; and a correction that can't be sent as planned is sent
  again for the step as it is now, never dropped.
- "The pump isn't answering — may be at …" now counts the step before when the pump refused a
  step's START (it carries on with the step before), and every START it never answered.
- The pump refusing the pressure on screen twice now stops the run even when the app can't tell
  which of two STARTs a refusal answers. A refusal that arrives after a correction finished counts
  too.
- After a refusal, the next step writes the pump's table again before it starts, so "The pump
  isn't answering" can end at the next step instead of lasting the rest of the run.
- Revert waits while the app is still sending a correction, and says so on the adjust sheet (it
  could leave the pump at the adjustment while the screen showed the step); Link Lost closes the
  adjust sheet. After an automatic stop, the reconnect offers End or Start over until the stop is
  seen to vent, and then "Resume at step N" too - the step as planned, without the adjustment.
- A rest in a routine no longer makes the pump refuse the first change after it. The app
  wrote each rest into the pump's table as an empty preset, which the pump doesn't keep, so
  a change made after a rest pointed at an empty slot. Four such raises once reached the
  next step as 8 kPa more than planned.

## [0.1.0] — first public import

### Added
- Open-source foundation: AGPL-3.0 licence, contribution guide, safety policy, security
  policy, code of conduct, architecture overview, decision records, CI.
- Standard Gradle build: `core` (plain Java) and `app` (Android) modules; the original
  ~35,700-check harnesses run inside `./gradlew test`, alongside new JUnit 5 tests.
- Restoring a backup made by the older PumpDebug app keeps its photos (paths are moved to
  the new app's folder).

### Changed
- New name and application id: OpenPump, `org.openpump`.
- Release builds no longer hold the Internet permission; the diagnostics console's
  developer upload is debug-only.
- The self-test no longer ships inside the app.

### Fixed
- "From gallery" did nothing on Android 11 and newer: the photo picker was hidden by
  package visibility rules. It now opens.
