package org.openpump;

/**
 * A DELOAD, AND WHAT COMING BACK FROM ONE COSTS.
 *
 * Every rule about the rest week and the return lives here, and nothing here knows about a
 * screen: the window's own dates, whether a gap in training is a layoff or the rest the plan
 * asked for, whether a reported range may be accepted, the missed-week charges a report
 * refunds, and the two-step taper the first training days back run at.
 *
 * PURE ON PURPOSE. It decides what pressure the pump is commanded at after a week off, which
 * is exactly the kind of arithmetic that should be asserted on a desk rather than discovered
 * on a cuff (see the note at the top of RxBuild for the same reasoning).
 */
public final class Deload {

    private Deload() { }

    /* ===================== THE WINDOW ===================== */

    /** When the recorded deload started; 0 when none has been taken. */
    public static long startMs(Model m) { return m == null ? 0L : m.trainerLastDeloadMs; }

    /**
     * When it ended, EXCLUSIVE. A reported deload carries its own end; a tap deload — and
     * every file written before a deload could be reported — ends {@link Plan#LAYOFF_MS}
     * after it started, which is what the whole app meant by "a deload week" before this.
     */
    public static long endMs(Model m) {
        long s = startMs(m);
        if (s <= 0L) return 0L;
        return m.trainerLastDeloadEndMs > s ? m.trainerLastDeloadEndMs : s + Plan.LAYOFF_MS;
    }

    /**
     * PUTS A DELOAD ON RECORD - the newest window, and the list of recent ones the pressure
     * clock asks ({@link #touches}). One writer, so the two cannot disagree about what was
     * rested. A report that extends the window just recorded replaces it rather than adding
     * a second, overlapping entry.
     */
    public static void remember(Model m, long startMs, long endMs) {
        if (m == null || startMs <= 0L) return;
        m.trainerLastDeloadMs = startMs;
        m.trainerLastDeloadEndMs = endMs;
        long end = endMs > startMs ? endMs : startMs + Plan.LAYOFF_MS;
        int n = m.deloadWindows.size();
        if (n > 0 && m.deloadWindows.get(n - 1)[0] == startMs) m.deloadWindows.remove(n - 1);
        // t10 REAL-1: a week off answered for a later day (Deload#dueStartMs) is on record before
        // it begins. One taken sooner - "Take a deload week now" - replaces it rather than
        // standing beside it: the rest is taken once, and the cycle counts from this one's end.
        while (!m.deloadWindows.isEmpty()
                && m.deloadWindows.get(m.deloadWindows.size() - 1)[0] > startMs)
            m.deloadWindows.remove(m.deloadWindows.size() - 1);
        m.deloadWindows.add(new long[]{ startMs, end });
        while (m.deloadWindows.size() > Model.DELOAD_WINDOWS_KEPT) m.deloadWindows.remove(0);
        // t10 R-40 (option D): a deload starts a new length block - its added set and its
        // offer are the old block's.
        m.trainerLength.dAddedInBlock = false;
        m.trainerLength.dOfferedInBlock = false;
    }

    /**
     * WHETHER ANY RECORDED DELOAD OVERLAPS [fromMs, toMs) - the kept windows and the newest
     * one, which an upgraded file knows only through trainerLastDeloadMs. A deload week never
     * counts toward a pressure step (the owner's decision, 2026-09-26), however much light
     * work was logged in it.
     */
    public static boolean touches(Model m, long fromMs, long toMs) {
        if (m == null || toMs <= fromMs) return false;
        if (overlapMs(m, fromMs, toMs) > 0L) return true;
        for (int i = 0; i < m.deloadWindows.size(); i++) {
            long[] w = m.deloadWindows.get(i);
            if (w[0] < toMs && w[1] > fromMs) return true;
        }
        return false;
    }

    /** Whether `now` sits inside the recorded window. */
    public static boolean inWindow(Model m, long now) {
        long s = startMs(m);
        return s > 0L && now >= s && now < endMs(m);
    }

    /* ===================== THE LENGTH TRACK IN THE WEEK ===================== */

    /*
     * S11 (the owner's decision, 2026-09-26): NO TRACTION DURING THE DELOAD WEEK, NORMAL LOAD
     * AFTERWARDS.
     *
     * The deload was plan-wide, but the one thing it did to a session was the taper's cut,
     * which reaches the length coda and never the traction blocks - their pressure comes from
     * the load (RxBuild#tractionRoutineFromRx, Mint#tractionSet). So the week still pulled at
     * full load. The guidance describes the week as rest: complete rest or pulse stretches,
     * rest or light retention only, complete rest on training days, one week off in every
     * four. None of that is a
     * pump session, so the length track keeps NONE of
     * its session in the week - not the expansion coda either: nothing is offered, and Up
     * next does not put length forward (TrainerTab#upNextNow).
     *
     * A ROUTINE THAT PULLS CAN STILL BE STARTED BY HAND, as every run is only ever advised
     * against, never blocked (plan ruling 5). The START confirm says so ({@link #startNote}).
     *
     * AFTER THE WEEK the pulls come back at the normal load: the owner ruled out a load taper,
     * and the pressure taper never touched the traction blocks.
     */

    /** Whether the length track rests at `nowMs`: a plan is enrolled and the instant sits
     *  inside the recorded deload window - {@link TrainerTab#inDeloadWeek}, the one
     *  definition of "a deload is in progress", asked for the length track. */
    public static boolean lengthRests(Model m, long nowMs) {
        return TrainerTab.inDeloadWeek(m, nowMs);
    }

    /**
     * What the START confirm says before a routine that PULLS is started in the deload week,
     * or "" when there is nothing to say - the routine has no traction stage, or the week is
     * not now. Words only: the run is the person's to start.
     */
    public static String startNote(Model m, Model.Routine r, long nowMs) {
        if (r == null || !lengthRests(m, nowMs) || !Say.isTraction(r)) return "";
        return "Deload week: the plan runs no traction and no length session this week — "
            + "the guidance asks for rest, or light retention only. This routine pulls. "
            + "Your normal load is back when the week ends.";
    }

    /** How much of [fromMs, toMs) the recorded window covers, in ms. */
    public static long overlapMs(Model m, long fromMs, long toMs) {
        long s = startMs(m);
        if (s <= 0L || toMs <= fromMs) return 0L;
        long lo = Math.max(s, fromMs), hi = Math.min(endMs(m), toMs);
        return hi > lo ? hi - lo : 0L;
    }

    /**
     * A LAYOFF IS TIME AWAY THAT NOBODY PLANNED. Same seven days the engine always meant,
     * asked of the time that is NOT a recorded deload — a week the plan itself asked you to
     * rest must not then step the plan back for resting.
     */
    public static boolean layoff(Model m, long nowMs) {
        long last = TrainerTab.lastAnyPlanSessionMs(m);
        if (last <= 0L) return false;
        // t10-K (B1): nor the month-12 break the plan gave - its days are carved out too.
        return (nowMs - last - overlapMs(m, last, nowMs)
                - MonthBreak.overlapMs(m, last, nowMs)) >= Plan.LAYOFF_MS;
    }

    /**
     * WHERE THE UNEXPLAINED STRETCH STARTS, for the prompt that asks whether it was a deload
     * — the last plan session, or the end of the recorded deload when that deload sits inside
     * the gap. 0 when there is nothing to ask about: not enrolled, no session ever, inside a
     * deload right now, not a layoff, or this stretch has already been answered.
     */
    public static long askGapStartMs(Model m, long nowMs) {
        if (m == null || !m.trainerEnrolled) return 0L;
        if (inWindow(m, nowMs)) return 0L;
        long last = TrainerTab.lastAnyPlanSessionMs(m);
        if (last <= 0L) return 0L;
        if (!layoff(m, nowMs)) return 0L;
        long gapStart = overlapMs(m, last, nowMs) > 0L ? Math.max(last, endMs(m)) : last;
        // t10-K (B1): a stretch after the month-12 break starts where the break ended.
        if (MonthBreak.overlapMs(m, last, nowMs) > 0L)
            gapStart = Math.max(gapStart, MonthBreak.returnMs(m));
        return m.deloadAskAnchorMs == gapStart ? 0L : gapStart;
    }

    /**
     * WHERE THE REPORT SHOULD OPEN for a prompted gap: the day after the last plan session,
     * or the recorded deload's own start when this stretch begins where that deload ended —
     * saving then EXTENDS the deload on record instead of being refused for ending before it.
     */
    public static long prefillFromMs(Model m, long gapStartMs) {
        if (m == null || gapStartMs <= 0L) return 0L;
        long recorded = startMs(m);
        // ADJACENCY, NOT "SOMETIME BEFORE". The extend branch is for a stretch that begins
        // exactly where the recorded deload ended - the case where the user rested longer than
        // they first said. A deload that ended, was trained after, and is now followed by a
        // SEPARATE gap is a different stretch: opening the report on that old deload's start
        // would span real training days and be refused for length or age.
        if (recorded > 0L && endMs(m) == gapStartMs) return recorded;
        return dayStartPlus(gapStartMs, 1);
    }

    /* ===================== WHEN THE CADENCE DELOAD STARTS ===================== */

    /*
     * t10 REAL-1 (the owner, 1 Oct 2026 night): THE PERSON CHOOSES. When the cadence deload comes
     * due - the morning after the session that made the block's last training week count - the
     * plan asks when the week off starts: from tomorrow, from next Monday, or a day picked within
     * the next seven. That morning's session runs and counts whatever the answer, so the earliest
     * start is tomorrow; "Take a deload week now" (Steer the plan) is still there for now.
     *
     * Until it is answered no new cycle starts: the cadence keeps counting from the last deload's
     * end and the deload stays due (TrainerTab#cadenceDeloadDue). Once it is, the window is on
     * record from its first day and the next cycle counts from its ACTUAL end
     * (TrainerTab#deloadAnchorMs) - for both tracks, since the window is the plan's.
     */
    public static final int START_TOMORROW = 0, START_MONDAY = 1, START_PICK = 2;
    /** The furthest a picked start can be: within the next seven days. */
    public static final int PICK_MAX_DAYS = 7;

    /**
     * The local midnight the week off starts on for an answer given at `nowMs`: tomorrow, the
     * next Monday (a week away when today is a Monday, tomorrow on a Sunday), or `days` days
     * ahead for a picked day - never today, never more than {@link #PICK_MAX_DAYS} away.
     */
    public static long dueStartMs(long nowMs, int choice, int days) {
        if (choice == START_MONDAY)
            return dayStartPlus(TrainerTab.mondayStartMs(nowMs), 7);
        if (choice == START_PICK)
            return dayStartPlus(nowMs, Math.max(1, Math.min(PICK_MAX_DAYS, days)));
        return dayStartPlus(nowMs, 1);
    }

    /**
     * t10 REAL-5: WHETHER THE WEEK OFF THE TAPER WAS ARMED FOR HAS NOT BEGUN ON `day`. A week off
     * answered for tomorrow or a later day is recorded - and the return taper armed for its end
     * (SessionActivity#recordDeload) - at the answer. The taper's cut belongs to the week off and
     * the return after it, never to the days before it: those run at the working pressure and
     * count. Only the taper armed for THIS window (its return from the window's end) waits; one
     * armed since by a resume or a week away is in force as it was.
     */
    public static boolean notBegunOn(Model m, long day) {
        long s = startMs(m);
        return armed(m) && s > 0L && m.returnFromMs == endMs(m) && day < Summary.dayNumber(s);
    }

    /* ===================== LOCAL DAYS ===================== */

    /** Local midnight of the day `ts` falls on — what a picked From/To date becomes. */
    public static long dayStartMs(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Local midnight `days` days after the day `ts` falls on. Calendar arithmetic, not
     *  86 400 000 × n: a DST change makes a day 23 or 25 hours long. */
    public static long dayStartPlus(long ts, int days) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(dayStartMs(ts));
        c.add(java.util.Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    /* ===================== WHAT MAY BE REPORTED ===================== */

    public static final int REPORT_OK = 0;
    public static final int REPORT_FUTURE = 1;
    public static final int REPORT_TOO_SHORT = 2;
    public static final int REPORT_TOO_LONG = 3;
    public static final int REPORT_TOO_OLD = 4;
    public static final int REPORT_BEFORE_RECORDED = 5;

    /**
     * Whether a reported range may be accepted. All four arguments are {@link
     * Summary#dayNumber} day numbers, so this is integer arithmetic with no clock in it.
     * `recordedStartDay` is 0 when no deload is on record.
     */
    public static int validate(long fromDay, long toDay, long todayDay, long recordedStartDay) {
        if (toDay > todayDay) return REPORT_FUTURE;
        long days = toDay - fromDay + 1;
        if (days < Plan.DELOAD_REPORT_MIN_DAYS) return REPORT_TOO_SHORT;
        if (days > Plan.DELOAD_REPORT_MAX_DAYS) return REPORT_TOO_LONG;
        if (toDay < todayDay - Plan.DELOAD_REPORT_MAX_AGE_DAYS) return REPORT_TOO_OLD;
        if (recordedStartDay > 0L && toDay < recordedStartDay) return REPORT_BEFORE_RECORDED;
        return REPORT_OK;
    }

    /** What to print under the dates when a range is refused. Empty for {@link #REPORT_OK}. */
    public static String reportProblem(int code) {
        switch (code) {
            case REPORT_FUTURE:  return "The last day can’t be after today.";
            case REPORT_TOO_SHORT:
                return "Two days or fewer is ordinary rest — there is nothing to report.";
            case REPORT_TOO_LONG:
                return "Longer than 3 weeks is a break, not a deload — recalibrate instead.";
            case REPORT_TOO_OLD: return "Only the last 4 weeks can be reported.";
            case REPORT_BEFORE_RECORDED:
                return "A later deload is already on record.";
            default: return "";
        }
    }

    /* ===================== MISSED WEEKS, AND GIVING ONE BACK ===================== */

    /** Charges a week: the repeats hold the advance back, and the charge is written down so a
     *  deload reported later can undo exactly this much. */
    public static void recordCharge(Model.TrainerTrackState st, long weekStartMs, int added) {
        if (st == null || weekStartMs <= 0L || added <= 0) return;
        st.weekRepeats += added;
        st.missCharges.add(new long[]{ weekStartMs, added });
        while (st.missCharges.size() > Plan.MISS_CHARGES_KEPT) st.missCharges.remove(0);
    }

    /**
     * Gives back every charge whose week the reported deload touches, and forgets it — so a
     * second report of the same days refunds nothing. Returns what was given back.
     *
     * A WEEK IS TOUCHED IF IT OVERLAPS AT ALL, the same rule applyMissPolicy already uses to
     * forgive a week a deload ran through: prorating a rest is not a thing this app does.
     */
    public static int refund(Model.TrainerTrackState st, long startMs, long endMs) {
        if (st == null || startMs <= 0L || endMs <= startMs) return 0;
        int back = 0;
        for (int i = st.missCharges.size() - 1; i >= 0; i--) {
            long[] c = st.missCharges.get(i);
            long ws = c[0], we = ws + 7L * 24L * 60L * 60L * 1000L;
            if (ws < endMs && we > startMs) { back += (int) c[1]; st.missCharges.remove(i); }
        }
        st.weekRepeats = Math.max(0, st.weekRepeats - back);
        return back;
    }

    /* ===================== THE GENTLE RETURN ===================== */

    /*
     * WHY A TAPER AND NOT THE ONE REDUCED SESSION THIS REPLACES.
     *
     * The guidance asks for 4 hg under after a week away, and the app used to give exactly one
     * session of it and then hand back the full working pressure. That second session is the
     * jump — raising pressure too fast is the same cause the red-dots section blames in
     * the first place, so landing on it deliberately was undoing the first session's point.
     * A second, smaller step costs one training day and removes the cliff.
     *
     * COUNTED PER TRAINING DAY, NOT PER SESSION. A split routine or a girth-plus-length day
     * files two sessions, and stepping per session would run the two halves of ONE day at two
     * different pressures — which is not a taper, it is an inconsistency the user would have
     * to notice and could not explain. The tissue does not know which routine ran.
     *
     * A REST DAY IS NOT A STEP EITHER. Steps are spent by training, never by the calendar:
     * nine quiet days after coming back, the first step is still the one waiting, because
     * nothing has happened to the tissue in the meantime.
     *
     * ALL OF IT IS HERE AND NONE OF IT IS ON A SCREEN, because Today, the Trainer and the
     * pump must agree about what this day runs at. Two screens each deciding from the raw
     * fields is exactly how they come to disagree.
     */

    /** Whether a taper is armed at all. */
    public static boolean armed(Model m) { return m != null && m.returnStep >= 0; }

    /** Arms the taper at its first step. `fromMs` is when steps start being used up — the end
     *  of the deload week, so a light session inside the week runs gently for free. */
    public static void arm(Model m, long fromMs) {
        if (m == null) return;
        m.returnStep = 0; m.returnDayRun = 0L; m.returnHeld = false;
        m.returnLastRun = -1; m.returnFromMs = fromMs;
    }

    /** Ends it: full pressure from here. */
    public static void end(Model m) {
        if (m == null) return;
        m.returnStep = -1; m.returnDayRun = 0L; m.returnHeld = false; m.returnLastRun = -1;
    }

    /** The step `day` runs at, WITHOUT changing anything: once a step has run, the day after
     *  it is the next step — unless a stay is held, which repeats it. */
    public static int stepOn(Model m, long day) {
        if (!armed(m)) return -1;
        if (notBegunOn(m, day)) return -1;                  // REAL-5: before the week off
        if (m.returnDayRun > 0L && day > m.returnDayRun)
            return m.returnHeld ? m.returnStep : m.returnStep + 1;
        return m.returnStep;
    }

    /** How far under the working pressure `day` runs, in inHg. 0 when nothing is in force. */
    public static double cutHg(Model m, long day) {
        return Plan.returnTaperHg(stepOn(m, day));
    }

    /**
     * t10 O8 - WHETHER THE DAY OF `ts` IS A REDUCED DAY OF THE RETURN: the taper's cut is in
     * force on it ({@link #cutHg}). What a session filed that day records (Sess#returnDay), so
     * its length reading is kept out of the one-week low streak. Asked before the filed session
     * spends its step, as the day it ran on.
     */
    public static boolean reducedOn(Model m, long ts) {
        return cutHg(m, Summary.dayNumber(ts)) > 0.0;
    }

    /**
     * WHETHER `nowMs` IS STILL THE DELOAD, before the return the taper was armed for.
     *
     * "Take a deload week now" arms the taper at the week's END, and the first step's cut
     * applies from the tap - so a light session in the week runs gently and spends nothing.
     * That is what the cards must say for the week. They said "Today runs 4 hg under" and
     * "day 1 of 2" for all seven days instead, as if the return had begun a week early.
     */
    public static boolean beforeReturn(Model m, long nowMs) {
        return armed(m) && nowMs < m.returnFromMs;
    }

    /** Whether the taper has run out but the card has not been closed yet. */
    public static boolean finished(Model m, long day) {
        return armed(m) && stepOn(m, day) >= Plan.RETURN_TAPER_STEPS;
    }

    /** Writes down what {@link #stepOn} already reports. Called as Today and the Trainer draw
     *  (SessionActivity#settleTaper) and immediately before a run starts
     *  (SessionActivity#beginRunFlow) - never while a session is running, where a step written
     *  down past midnight would be charged to that session, for a day it never ran on, when
     *  it files. Returns whether anything moved, so the caller saves. */
    public static boolean settle(Model m, long day) {
        if (!armed(m)) return false;
        if (!(m.returnDayRun > 0L && day > m.returnDayRun)) return false;
        if (!m.returnHeld) m.returnStep++;
        m.returnDayRun = 0L;
        m.returnHeld = false;
        return true;
    }

    /**
     * WHETHER A FILED SESSION SPENDS A STEP AT ALL.
     *
     * A TRAINING SESSION THAT RAN: girth in either style, or length - the days the taper counts
     * are training days back. A FEEDER is a top-up on a rest day, not a training day back
     * (owner's ruling, 2026-09-16); a MANUAL cycle has no plan to come back to; and a run
     * stopped before it started - a seal check abandoned at 0:00, filed with nothing delivered
     * (SessionActivity's `ranAtAll`, Session#isRunTracking) - put nothing on the tissue. Each
     * of those used to spend the day's step and move somebody on to a deeper one for a day
     * they never trained.
     */
    public static boolean spendsStep(int trainerTrack, boolean manual, boolean ranAtAll) {
        if (manual || !ranAtAll) return false;
        return trainerTrack == Plan.TRACK_GIRTH_INTERVAL
            || trainerTrack == Plan.TRACK_GIRTH_TRADITIONAL
            || trainerTrack == Plan.TRACK_LENGTH;
    }

    /** A session was filed, described the way the filing hook knows it. The ONE form that hook
     *  calls: the gate ({@link #spendsStep}) and the rules ({@link #onFiled(Model, long)}) in
     *  one place, so the gate cannot be skipped by calling the other. */
    public static boolean onFiled(Model m, long sessionTs, int trainerTrack, boolean manual,
                                  boolean ranAtAll) {
        if (!spendsStep(trainerTrack, manual, ranAtAll)) return false;
        return onFiled(m, sessionTs);
    }

    /**
     * A SESSION THAT SPENDS A STEP WAS FILED - the rules once {@link #spendsStep} has let it
     * through (the filing hook goes through {@link #onFiled(Model, long, int, boolean, boolean)}).
     * Returns whether state moved.
     */
    public static boolean onFiled(Model m, long sessionTs) {
        long day = Summary.dayNumber(sessionTs);
        boolean moved = settle(m, day);
        if (!armed(m)) return moved;
        if (sessionTs < m.returnFromMs) return moved;      // still inside the deload itself
        if (m.returnStep < Plan.RETURN_TAPER_STEPS) {
            if (m.returnDayRun == 0L) {
                m.returnDayRun = day;
                m.returnLastRun = m.returnStep;
                return true;
            }
            return moved;                                   // another session the same day
        }
        end(m);                                             // a full-pressure day closes it
        return true;
    }

    /**
     * THE MAIN PRESSURE A DAY ACTUALLY RUNS AT, in kPa: the working pressure less the taper's
     * cut in force on that day.
     *
     * What a FEEDER takes its share of (owner's ruling, 2026-09-16). On a day the taper reduces,
     * a feeder runs at its usual three quarters of the REDUCED main pressure - not at its normal
     * figure less the cut, which from 10 hg is 11 kPa where the day's share is 15 - so every
     * session on the day genuinely shares that day's pressure. Only the taper's cut: the
     * cylinder's own is the builder's to take, as it is for every routine.
     *
     * t10 REAL-13: THE REDUCED MAIN IS THE WHOLE kPa THE GIRTH SESSION IS SENT - the working
     * pressure as a whole kPa, less the cut, rounded again (Mint#reducedKpa, the girth
     * session's own arithmetic). Three quarters of the unrounded figure could land a kPa off
     * three quarters of the session actually run that day (33.4 less 4 hg: 15 beside 19).
     *
     * t10 parity run 2, A-1: A FULL DAY TOO. REAL-15 made the plan figure exact (10 hg is
     * 33.86 kPa), and a full day handed it on unrounded - three quarters of 33.86 is 25, where
     * the girth session is sent 34 and three quarters of that is 26. The whole kPa it is sent.
     */
    public static double mainKpaOn(Model m, double workingKpa, long nowMs) {
        double hg = cutHg(m, Summary.dayNumber(nowMs));
        int whole = (int) Math.round(workingKpa);
        if (hg <= 0.0) return whole;
        int lowered = Mint.reducedKpa(whole, hg * Model.Fmt.KPA_PER_INHG);
        return Math.min(whole, lowered);
    }

    /** Whether "stay one more day" has anything to repeat: something has run, no stay is
     *  already pending, and the step in force is either today's or the one just advanced past.
     *  False straight after a repeat, so the button can never walk the taper back twice. */
    public static boolean canStay(Model m) {
        return armed(m) && !m.returnHeld && m.returnLastRun >= 0
            && (m.returnDayRun > 0L || m.returnLastRun == m.returnStep - 1);
    }

    /** The cut "stay one more day" would repeat — what the button names. */
    public static double stayCutHg(Model m) {
        return canStay(m) ? Plan.returnTaperHg(m.returnLastRun) : 0.0;
    }

    /** Repeats the step that last ran on the next training day. */
    public static boolean stayOneMoreDay(Model m, long day) {
        settle(m, day);
        if (!canStay(m)) return false;
        if (m.returnDayRun > 0L) m.returnHeld = true;       // ran today: tomorrow repeats it
        else m.returnStep = m.returnLastRun;                // already advanced: step back one
        return true;
    }

    /* ===================== THE STEP, AS A SIGNATURE CARRIES IT ===================== */

    /*
     * WHY THE SIGNATURE IS THE WHOLE MECHANISM.
     *
     * Model#mintShapeTag appends the day's step to every mint signature ("RET40" while a day
     * runs 4 hg under), so the signature changes exactly when the day's cut changes - the day
     * rolling over, a stay, an end, a report - and SessionActivity#syncPlanRoutines rewrites
     * the saved routine on that alone. Nothing about the taper needs a stored signature
     * CLEARED to reach the pump, and clearing one does harm: an empty signature says nothing
     * about what the routine was, so the rewrite it forces can no longer be recognised as
     * "only the step moved", and it is announced as a plan change that did not happen.
     */

    /** The segment's fixed spelling, written by {@link #stepTag} and read back by
     *  {@link #stepHg}, so the tag and the code that takes it apart cannot disagree. */
    private static final String STEP_TAG = "RET";

    /** The tag for a day cut `cutHg` under, or "" when nothing is cut - so a signature with
     *  no step in force is byte-identical to one stored before the taper existed. */
    public static String stepTag(double cutHg) {
        return cutHg > 0.0 ? STEP_TAG + (int) Math.round(cutHg * 10) : "";
    }

    /** Whether one '|'-separated segment of a signature is the step's tag: the spelling and
     *  up to four digits, nothing else - a cylinder id or a shape tag can never be read as one. */
    private static boolean isStepSegment(String seg) {
        int n = STEP_TAG.length();
        if (seg == null || !seg.startsWith(STEP_TAG) || seg.length() <= n || seg.length() > n + 4)
            return false;
        for (int i = n; i < seg.length(); i++) if (!Character.isDigit(seg.charAt(i))) return false;
        return true;
    }

    /** The cut a signature was minted under, in inHg - "...|RET40" is 4.0. 0 when it carries
     *  no step, which includes an empty (cleared) signature. */
    public static double stepHg(String sig) {
        if (sig == null) return 0.0;
        String[] segs = sig.split("\\|", -1);
        for (int i = 0; i < segs.length; i++)
            if (isStepSegment(segs[i]))
                return Integer.parseInt(segs[i].substring(STEP_TAG.length())) / 10.0;
        return 0.0;
    }

    /** The signature with its step taken out: the prescription and its shape, apart from the
     *  day's cut. */
    public static String withoutStep(String sig) {
        if (sig == null) return "";
        String[] segs = sig.split("\\|", -1);
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < segs.length; i++) {
            if (isStepSegment(segs[i])) continue;
            if (!first) b.append('|');
            b.append(segs[i]);
            first = false;
        }
        return b.toString();
    }

    /**
     * THE SAME PRESCRIPTION, APART FROM THE DAY'S STEP.
     *
     * The one rewrite the gentle return makes on its own, and so the one that must not be
     * announced as "your plan changed": the card on Today already says what the day runs at.
     * Anything else that moved with it - a set added, a pressure raised - is a real change
     * and is announced as one, reduced day or not.
     *
     * AN EMPTY SIGNATURE IS NEVER "THE SAME". It records nothing about what the routine was,
     * so a rewrite from one cannot be shown to be the step alone.
     */
    public static boolean sameApartFromStep(String a, String b) {
        if (a == null || b == null || a.length() == 0 || b.length() == 0) return false;
        return withoutStep(a).equals(withoutStep(b));
    }

    /**
     * THE CUT A SAVED ROUTINE WAS BUILT UNDER, in kPa, read from the signature stored with it.
     *
     * The builder takes the cut off before it writes a single set, so a routine minted on a
     * reduced day holds its work at the reduced pressure. Asking "is this still what was
     * prescribed?" at the plan's own figure finds nothing there - and "nothing found" was
     * being read as "not edited", so a routine somebody changed on a reduced day was
     * overwritten at the next step. The answer has to be asked where the routine holds.
     *
     * The taper step is in the signature. The cylinder's own cut never is - changing it clears
     * the signature instead (SessionActivity#remintAfterReductionChange) - so when no step was
     * in force, the cylinder cut the model carries now is the one the routine was built under.
     */
    public static double mintCutKpa(Model m, String sig) {
        double hg = stepHg(sig);
        if (hg <= 0.0 && m != null) hg = m.cylinderCutHg();
        return hg * Model.Fmt.KPA_PER_INHG;
    }

    /**
     * THE SAME QUESTION FOR A SAVED FEEDER, whose answer differs on a reduced day. A feeder
     * minted then already IS its share of the day's reduced main pressure ({@link #mainKpaOn}),
     * and the builder takes nothing more off it - so nothing came off the pressure in its
     * signature. On any other day the builder took the cylinder's own cut, as for every routine.
     */
    public static double feederMintCutKpa(Model m, String sig) {
        if (stepHg(sig) > 0.0 || m == null) return 0.0;
        return m.cylinderCutHg() * Model.Fmt.KPA_PER_INHG;
    }
}
