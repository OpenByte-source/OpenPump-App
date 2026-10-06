package org.openpump;

/**
 * THE LENGTH LADDER'S STATE AND SIGNALS, AROUND {@link Plan}'s length rungs (t10, lane C: the
 * owner's length decisions of 30 Sep - 1 Oct 2026).
 *
 * Plan decides from {@link Plan.Inputs}; this is everything on either side of that decision
 * that reads or writes the length track's own record: the signals walked from the logs each
 * evaluation ({@link #fill}), what the setup writes ({@link #atSetup}), and what accepting a
 * length card writes ({@link #acceptSets}, {@link #acceptLoad}, {@link #offerAnswered},
 * {@link #fellAccepted}). The screens call these; nothing here knows about a screen, so the
 * harness asks exactly the questions the app asks.
 *
 * OPTION D (R-40) needs three things a single evaluation cannot see: whether this BLOCK - the
 * training since the last deload - had a reading at 2 % or over, whether a D2 set was added in
 * it and whether its offer was made. The first is read from the logs; the other two are kept
 * on the track (TrainerTrackState#dAddedInBlock / #dOfferedInBlock) and cleared when a deload
 * starts (Deload#remember).
 */
public final class LengthTrack {

    private LengthTrack() { }

    /** The length protocol the goals are stated in - the strain rung's readings. */
    public static final int METHOD = Model.Reading.METHOD_BPSSL;

    private static final long DAY_MS = 86400000L;
    private static final double MONTH_MS = 30.44 * DAY_MS;

    /**
     * THE LENGTH LADDER'S SIGNALS for `st` at `now`, every one walked from the logs at this
     * instant - the ones SessionActivity#buildTrackInputs used to gather itself, and option D's.
     * The cylinder's (girthCm, fitState, lengthTube, boreCm) are the caller's: the girth the
     * fit is judged on is the screen's.
     */
    public static void fill(Model m, Model.TrainerTrackState st, int monthIndex, long now,
                            Plan.Inputs in) {
        // Length: no GIRTH yield gate and no per-session girth milestone - those two stay off,
        // because the yield machinery is the girth engine's and a length session must never
        // move it.
        in.hasYieldData = false;
        in.netTupMin = 0.0;
        // trainingWeeksAtPressure carries the WHOLE MONTHS since the length pressure last
        // moved (the coda creep's caller contract). TRULY MONTHLY (t10 R-42, C11): 30.44 days
        // from the pressure clock alone, floored - a re-mint (a strain set, the load) does not
        // restart it, and a fortnight is not rounded up to a month.
        in.trainingWeeksAtPressure = TrainerTab.monthsElapsed(st.pressureSinceMs, now);
        // REAL-14 - the first month's 6 inHg top binds only somebody new to pumping.
        in.newToPumping = Scale.isNew(m);
        // G2 - the top the length pressure climbs to when its own maximum is above the usual
        // top (the pull follows it, under the 15 lb limit).
        in.climbTopKpa = Scale.planTopKpa(m, Plan.TRACK_LENGTH, st.level, monthIndex);

        /* N24 (the owner, 2 Oct 2026): THE LADDER'S READINGS START AGAIN AT EACH WEEK OFF - the
         * low run counts from the later of the last change to the work and the end of the
         * last week off (the block, #blockStartAt), and an over-6 % confirmation is never
         * straddled across one (Meas#strainHighConfirmed). A pair confirmed wholly before the
         * week off still cuts at the first session back (A-5); a single reading from before it
         * asks for nothing more once it is over. */
        long offEnd = weekOffEndBefore(m, now);
        in.strainHighConfirmed = Meas.strainHighConfirmed(m, METHOD, Plan.LENGTH_STRAIN_HI,
                                                          st.strainSinceMs, offEnd);
        // OPTION D: THE ONE AFTER-SESSION READING (Meas#strainReadings - a length session that
        // pulled, its own before-reading, like for like).
        Double strain = strainActedOn(m, now);
        in.strainPct = strain == null ? Double.NaN : strain.doubleValue();
        Double fatigue = Meas.fatiguePct(m.measLog, METHOD, now);
        in.fatiguePct = fatigue == null ? Double.NaN : fatigue.doubleValue();   // shown only
        // Counted from the last change to the length work only (st.strainSinceMs): one step
        // per week of low readings measured on the work as it now stands - and, N24, measured
        // in this block.
        in.strainMissDays = Meas.strainMissDays(m, METHOD, Plan.LENGTH_STRAIN_LO,
                                                Math.max(st.strainSinceMs, blockStartAt(m, now)),
                                                now);
        // O8: a reduced return day's reading counts (O5) but is not part of the low streak.
        in.strainNewestReduced = Meas.strainNewestReduced(m, METHOD, now);
        in.lastStrainHigh = Meas.lastStrainHigh(m, METHOD, Plan.LENGTH_STRAIN_HI);
        // The block that is RUNNING - from the last week off that is over, never one booked for
        // a later day (round 3 follow-up: a block that reached 2 % still "fell" before it).
        in.blockHadReachLo = Meas.reachedSince(m, METHOD, blockStartAt(m, now),
                                               Plan.LENGTH_STRAIN_LO);
        /* OPEN-2a: THE BLOCK'S OWN FLAGS (O4, blocks restart together). Set in the days before a
         * week off booked for later, they belonged to the block that week off ends - and read
         * in the next, a D2 set brought D3. Read only in the block they were set in
         * (TrainerTrackState#dBlockMs; 0 - a file from before - as they are). */
        boolean thisBlock = st.dBlockMs <= 0L || st.dBlockMs == blockStartAt(m, now);
        in.dAddedInBlock = st.dAddedInBlock && thisBlock;
        in.dOfferedInBlock = st.dOfferedInBlock && thisBlock;
        in.daysSinceLastCut = st.lastCutMs > 0L
            ? (int) (Math.max(0L, now - st.lastCutMs) / DAY_MS) : -1;
        // REAL-8: the cut answered the reading that confirmed it; only a newer one asks again.
        long newest = Meas.strainNewestMs(m, METHOD);
        in.strainHighAnswered = st.lastCutMs > 0L && newest > 0L && newest <= st.lastCutMs;
        // REAL-11: one load change between two length sessions.
        in.loadMovedSinceSession = st.loadMovedMs > 0L
            && st.loadMovedMs > TrainerTab.lastPlanSessionMs(m, Plan.TRACK_LENGTH);
        // t10 parity run 2, A-3 (R-46): one change to the length work a morning.
        in.lengthChangedToday = changedToday(st, now);
        in.handedOver = st.handedOver;
        in.lengthLoadMode = m.lengthLoadMode;
        in.deloadNextAnyway = deloadNextAnyway(m, monthIndex, now);

        in.stretchOutrunningErect = Meas.stretchOutrunningErect(
            m.measLog, Plan.DIVERGENCE_WEEKS, now);
        // G3 - training weeks with no strain pair since the length work last changed.
        in.weeksWithoutReadings = TrainerTab.lengthWeeksWithoutReadings(m, now);
        in.loadLb = st.loadLb;
        // OPEN-5: the person's offset as pounds of pull, so the cut and its floor are the pull's.
        in.lengthOffsetLb = Scale.offsetLb(
            Scale.appliedOffsetKpa(m, Plan.TRACK_LENGTH, monthIndex), m.lengthBoreCm());
        in.strainSets = st.strainSets;
        in.inGirthFocus = st.inGirthFocus(now);
        // The calendar ladder's clock: qualifying training weeks on THIS track since enrolment.
        in.lengthTrainingWeeks = TrainerTab.accumulatedTrainingWeeks(
            m, Plan.TRACK_LENGTH, m.trainerEnrolledAt, now);
        in.strainCalWeeks = st.strainCalWeeks;
        in.slowLoadDue = slowLoadDue(m, st, in.lengthTrainingWeeks, now);
        // R11-4 - the brake: on target and rising, the climb every 2 months and the slow step
        // every 4 training weeks; "Step up now" answered for the step waiting gives it.
        in.gainBrake = GainBrake.on(m, Plan.TRACK_LENGTH, GainBrake.lengthOnTarget(in.strainPct),
                                    now);
        in.slowLoadBraked = in.slowLoadDue && st.slowLoadWeeks >= 0
            && GainBrake.slowBraked(in.gainBrake, in.lengthTrainingWeeks - st.slowLoadWeeks);
        in.brakeStepUp = GainBrake.steppedUp(st, GainBrake.clockKey(st, GainBrake.KIND_CLIMB));
        in.brakeStepUpSlow = GainBrake.steppedUp(st, GainBrake.clockKey(st, GainBrake.KIND_SLOW));
        // R-60 (CAP90): what the next strain step would add to a both-tracks day - the
        // hand-over's whole step, or one set.
        int add = handoverPending(st, monthIndex)
            ? Plan.LENGTH_HANDOVER_SETS - st.strainSets : 1;
        in.heldAt90 = TrainerTab.heldAt90(m, Plan.TRACK_LENGTH,
            add * Plan.HELD_AT_90_STRAIN_SET_SEC, now);
        // t10-K: the month-12 break, its gentle week and the climb back, last.
        MonthBreak.fill(m, Plan.TRACK_LENGTH, now, in);
    }

    /**
     * t10 parity run 2, A-3 (R-46, ONE CHANGE A MORNING): WHETHER THE LENGTH WORK ALREADY CHANGED
     * ON THE DAY OF `now` - its level (TrainerTrackState#weekBaseMs, the level's anchor, which
     * #crossLevel sets as a girth crossing does), its load (#loadMovedMs), its strain sets
     * (#strainSinceMs) or its pressure (#pressureSinceMs). Each moves only when the work does;
     * a stamp later than `now` is not today's.
     */
    static boolean changedToday(Model.TrainerTrackState st, long now) {
        if (st == null) return false;
        int today = PhotoCalendar.dayKey(now);
        long[] at = { st.weekBaseMs, st.loadMovedMs, st.strainSinceMs, st.pressureSinceMs };
        for (int i = 0; i < at.length; i++)
            if (at[i] > 0L && at[i] <= now && PhotoCalendar.dayKey(at[i]) == today) return true;
        return false;
    }

    /**
     * A LENGTH LEVEL CROSSING, ACCEPTED - what "Move to Level N" does on the length track (moved
     * from TrainerScreen's LevelUpTap so the harness crosses the way the app does). A length
     * crossing moves the level and nothing else: the load and the strain sets are the ladder's
     * to move, from the evidence, and a level crossing is not evidence about either. The mint
     * pointer is cleared so the level's routine is written, and the level's anchor
     * (TrainerTrackState#weekBaseMs) is today - the morning's one change (#changedToday).
     */
    public static void crossLevel(Model m, int nextLevel, long now) {
        if (m == null) return;
        Model.TrainerTrackState st = m.trainerLength;
        st.level = nextLevel;
        st.lastMintId = ""; st.lastMintSig = ""; st.lastMintMs = 0L;
        st.weekBaseMs = now;
    }

    /**
     * R-40 D1 - WHETHER THE REGULAR DELOAD FOLLOWS THIS TRAINING WEEK ANYWAY: the plan-wide
     * weeks counted before this one (TrainerTab#planTrainingWeeks from the cadence's anchor),
     * plus this one, bring it due. Then a "fell" brings nothing forward - the card says the
     * week off is next anyway.
     */
    static boolean deloadNextAnyway(Model m, int monthIndex, long now) {
        long anchor = TrainerTab.deloadAnchorMs(m);
        // Two hours before this week's Monday: the counted weeks are 7 x 24 h from the
        // anchor's Monday, so after a clock change they start an hour either side of
        // midnight - and asked at 23:59 on the Sunday, the hour-early window that holds this
        // week's sessions would be counted as last week's.
        long before = TrainerTab.mondayStartMs(now) - 2L * 60L * 60L * 1000L;
        int counted = before > anchor ? TrainerTab.planTrainingWeeks(m, anchor, before) : 0;
        return counted + 1 >= Plan.deloadNeedWeeks(!m.trainerFirstDeloadTaken, monthIndex);
    }

    /** R-45 - the month-3 hand-over still to come: month index 3 or more, under 6 sets, not
     *  handed over. */
    static boolean handoverPending(Model.TrainerTrackState st, int monthIndex) {
        return st != null && !st.handedOver && monthIndex >= Plan.LENGTH_METRICS_FROM_MONTH
            && st.strainSets < Plan.LENGTH_HANDOVER_SETS;
    }

    /**
     * WHERE THIS BLOCK STARTS (option D): the end of the last deload, never before enrolment,
     * and never before month 3 - a reading in the calendar months is not one the ladder reads,
     * so it cannot make a block one that "reached" its target.
     */
    public static long blockStartMs(Model m) {
        if (m == null) return 0L;
        return Math.max(Math.max(Math.max(Deload.endMs(m), m.trainerEnrolledAt), metricsFromMs(m)),
                        MonthBreak.blockFromMs(m));   // t10-K (O4): a new block at the return
    }

    /**
     * N24 - WHERE THE BLOCK `now` IS IN STARTS: {@link #blockStartMs}, but from the last week off
     * already OVER. A week off answered for a later day is on record from the answer (REAL-1),
     * and until it has run the block is still the one before it.
     */
    static long blockStartAt(Model m, long now) {
        if (m == null) return 0L;
        return Math.max(Math.max(weekOffEndBefore(m, now), m.trainerEnrolledAt), metricsFromMs(m));
    }

    /**
     * THE STRAIN FIGURE THE LADDER ACTS ON at `now` (Inputs#strainPct; the Trainer's strain line
     * shows the same): the newest reading (Meas#strainPct - the week off does not age it, A-5),
     * except one from before the last week off that is over which no confirmed pair stands
     * behind - N24 restarted its re-measure there, so it is no longer the figure. Null: none.
     */
    public static Double strainActedOn(Model m, long now) {
        if (m == null) return null;
        Double strain = Meas.strainPct(m, METHOD, now);
        long offEnd = weekOffEndBefore(m, now);
        if (strain != null && offEnd > 0L && Meas.strainNewestMs(m, METHOD) <= offEnd
                && !Meas.strainHighConfirmed(m, METHOD, Plan.LENGTH_STRAIN_HI,
                                             m.trainerLength.strainSinceMs, offEnd))
            return null;
        return strain;
    }

    /** N24 - when the last recorded week off that is over by `now` ended - a deload, or the
     *  month-12 break's return (t10-K, MonthBreak#blockFromMs); 0 when none has. */
    static long weekOffEndBefore(Model m, long now) {
        if (m == null) return 0L;
        long end = Deload.endMs(m);
        if (end > now) end = 0L;
        for (int i = 0; i < m.deloadWindows.size(); i++) {
            long e = m.deloadWindows.get(i)[1];
            if (e <= now && e > end) end = e;
        }
        long back = MonthBreak.blockFromMs(m);
        if (back <= now && back > end) end = back;
        return end;
    }

    /** The instant the month index reaches {@link Plan#LENGTH_METRICS_FROM_MONTH}
     *  (TrainerTab#monthIndexNow, inverted); 0 when it already had at setup. */
    public static long metricsFromMs(Model m) {
        if (m == null) return 0L;
        int answered = Math.max(0, m.trainerMonthsPumping);
        if (answered >= Plan.LENGTH_METRICS_FROM_MONTH) return 0L;
        long from = m.trainerMonthsAt > 0L ? m.trainerMonthsAt : m.trainerEnrolledAt;
        if (from <= 0L) return 0L;
        return from + (long) Math.ceil((Plan.LENGTH_METRICS_FROM_MONTH - answered) * MONTH_MS);
    }

    /**
     * L2 (R-46) - IS THE SLOW CALENDAR LOAD STEP DUE: two length training weeks since the last
     * one (TrainerTrackState#slowLoadWeeks; its clock starts at the first evaluation from
     * month 3, {@link #startSlowClock}), and the pull not yet at "Most you will go to" for
     * length at the length cylinder's bore - the one limit Plan.Inputs does not carry.
     */
    static boolean slowLoadDue(Model m, Model.TrainerTrackState st, int lengthWeeks, long now) {
        if (st.slowLoadWeeks < 0) return false;
        if (lengthWeeks - st.slowLoadWeeks < Plan.LENGTH_LOAD_STEP_WEEKS) return false;
        double bore = m.lengthBoreCm();
        if (m.rxLengthMaxKpa > 0 && bore > 0) {
            double most = Traction.loadLbAtBore(Math.floor(m.rxLengthMaxKpa + 1e-9), bore);
            if (Scale.pullLoadLb(m, st.loadLb, now) >= most - 1e-9) return false;
        }
        return true;
    }

    /**
     * L2 - STARTS THE SLOW LOAD STEP'S CLOCK the first time the length track is evaluated from
     * month 3 with the slow step chosen: the first step is two length training weeks on. Returns
     * whether it wrote anything, so the caller saves.
     */
    public static boolean startSlowClock(Model m, long now) {
        if (m == null) return false;
        Model.TrainerTrackState st = m.trainerLength;
        if (st.slowLoadWeeks >= 0 || m.lengthLoadMode != Model.LENGTH_LOAD_SLOW) return false;
        if (TrainerTab.monthIndexNow(m, now) < Plan.LENGTH_METRICS_FROM_MONTH) return false;
        st.slowLoadWeeks = TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_LENGTH,
                                                               m.trainerEnrolledAt, now);
        return true;
    }

    /**
     * R-44 (C13) - A GIRTH-FOCUS BLOCK THAT HAS ENDED HANDS THE LADDER BACK ON FRESH READINGS:
     * the strain clock restarts at the block's end, so nothing measured during the block
     * counts toward a step, and the no-readings fallback counts from there too. Once per block.
     * Returns whether it wrote anything, so the caller saves.
     */
    public static boolean focusEnded(Model.TrainerTrackState st, long now) {
        if (st == null || st.focusBlockUntilMs <= 0L || st.inGirthFocus(now)) return false;
        if (st.strainSinceMs >= st.focusBlockUntilMs) return false;
        st.restartStrainClock(st.focusBlockUntilMs);
        return true;
    }

    /**
     * WHAT THE SETUP WRITES ON THE LENGTH TRACK (R-45, L1): from month index 3 the guidance's
     * 6 strain sets, handed over; before it the calendar's 2. The block and cut record of any
     * earlier plan is cleared, and the slow step's clock starts at the first evaluation.
     */
    public static void atSetup(Model m, long now) {
        if (m == null) return;
        Model.TrainerTrackState st = m.trainerLength;
        boolean handed = TrainerTab.monthIndexNow(m, now) >= Plan.LENGTH_METRICS_FROM_MONTH;
        st.setStrainSets(handed ? Plan.LENGTH_HANDOVER_SETS : Plan.LENGTH_STRAIN_SETS_START, now);
        st.handedOver = handed;
        st.strainCalWeeks = 0;
        st.slowLoadWeeks = Model.TrainerTrackState.SLOW_LOAD_NONE;
        st.dAddedInBlock = false;
        st.dOfferedInBlock = false;
        st.warned12 = false;
        st.lastCutMs = 0L;
        st.cutsInRow = 0;
        MonthBreak.atSetup(m);          // t10-K: no climb back after a setup
    }

    /**
     * R11-3 - THE STRAIN SETS A SETUP PLACED BY THE SESSION'S LENGTH (Placement
     * #lengthStrainSets), after {@link #atSetup}: the count placed, handed over when it is the
     * hand-over's six or more (or the months are past month 3, as atSetup has it), and the
     * calendar going on from it. Nothing when it was not placed (-1).
     */
    public static void placedAt(Model m, int strainSets, long now) {
        if (m == null || strainSets < Plan.LENGTH_STRAIN_SETS_START) return;
        Model.TrainerTrackState st = m.trainerLength;
        int n = Math.min(Plan.LENGTH_STRAIN_SETS_MAX, strainSets);
        st.setStrainSets(n, now);
        st.handedOver = st.handedOver || n >= Plan.LENGTH_HANDOVER_SETS;
        st.strainCalWeeks = Placement.strainCalendarWeeks(n);
    }

    /** An accepted strain-set card (`rule` the decision's): the count, and what option D and
     *  the hand-over remember of it. */
    public static void acceptSets(Model m, String rule, int sets, long now) {
        Model.TrainerTrackState st = m.trainerLength;
        st.setStrainSets(sets, now);   // restarts the strain clock
        if (Plan.LENGTH_NEVER_RULE.equals(rule)) markBlock(m, now).dAddedInBlock = true;
        if (Plan.LENGTH_HANDOVER_RULE.equals(rule) || sets >= Plan.LENGTH_HANDOVER_SETS)
            st.handedOver = true;
    }

    /**
     * An accepted load card: the load, and - for a climb or a catch-up step (`kpa` given, not a
     * cut) - the length pressure, whose month starts again with it (R-43: one step a month).
     * A cut (`rule` {@link Plan#LENGTH_CUT_RULE}) records itself for C10's week and the
     * "cuts in a row" check, and lowers the climbed pressure with it when `kpa` is given.
     */
    public static void acceptLoad(Model m, String rule, double lb, double kpa, long now) {
        Model.TrainerTrackState st = m.trainerLength;
        boolean cut = Plan.LENGTH_CUT_RULE.equals(rule);
        if (cut) {
            // In a row: every reading since the last cut was over the window as well.
            boolean run = st.lastCutMs > 0L
                && Meas.allHighSince(m, METHOD, Plan.LENGTH_STRAIN_HI, st.lastCutMs);
            st.cutsInRow = run ? st.cutsInRow + 1 : 1;
            st.lastCutMs = now;
        }
        if (Plan.LENGTH_NEVER_RULE.equals(rule)) markBlock(m, now).dAddedInBlock = true;
        if (Plan.LENGTH_SLOW_LOAD_RULE.equals(rule))
            st.slowLoadWeeks = TrainerTab.accumulatedTrainingWeeks(m, Plan.TRACK_LENGTH,
                                                                   m.trainerEnrolledAt, now);
        if (past12Due(m, lb, now)) st.warned12 = true;
        /* WHICH STEPS ARE NEW WORK TO BE READ AFRESH. A cut, a D2 half-pound and a calendar
         * step restart the strain clock (TrainerTrackState#setLoadLb): the next step waits for
         * readings on the new load. The pull FOLLOWING THE CLIMB and the SLOW CALENDAR STEP do
         * not (the editor model, R-43 / R-46): they come every month or every two weeks on
         * their own clocks, and restarting the readings' week each time would hold option D's
         * set off for good. */
        boolean afresh = cut || !(Plan.LENGTH_SLOW_LOAD_RULE.equals(rule) || !Double.isNaN(kpa));
        long since = st.strainSinceMs;
        st.setLoadLb(lb, now);
        if (!afresh) st.strainSinceMs = since;
        if (!Double.isNaN(kpa)) {
            st.setWorkingPressure(kpa, now);
            if (!cut) st.restartPressureClock(now);
        }
        // t10-K (B5): a confirmed cut, or reaching the target, ends the climb back.
        MonthBreak.loadAccepted(m, rule, lb);
    }

    /** OPEN-2a - the length track, its D flags made this block's (#blockStartAt at `now`): one
     *  set in an earlier block is cleared first. */
    static Model.TrainerTrackState markBlock(Model m, long now) {
        Model.TrainerTrackState st = m.trainerLength;
        long block = blockStartAt(m, now);
        if (st.dBlockMs > 0L && st.dBlockMs != block) {
            st.dAddedInBlock = false;
            st.dOfferedInBlock = false;
        }
        st.dBlockMs = block;
        return st;
    }

    /** R-40 D3 - the offer was answered, whichever way: once a block, and the ladder carries
     *  on from a fresh week of readings ("Not now": the next week under adds a set). */
    public static void offerAnswered(Model m, long now) {
        Model.TrainerTrackState st = markBlock(m, now);
        st.dOfferedInBlock = true;
        st.restartStrainClock(now);
    }

    /** R-40 D1 - the week off a fall brought forward was taken: the readings before it are
     *  the old block's, and the new block's week of low readings starts afresh. */
    public static void fellAccepted(Model m, long now) {
        m.trainerLength.restartStrainClock(now);
    }

    /**
     * R-43 / A9 - WHETHER A LOAD STEP TO `toLb` OWES THE ONE-TIME WORDS: it takes the plan's
     * pull past 12 lb before month 12, and they have not been said
     * (TrainerTrackState#warned12). The words are {@link Plan#lengthPast12Words}.
     */
    public static boolean past12Due(Model m, double toLb, long now) {
        if (m == null) return false;
        Model.TrainerTrackState st = m.trainerLength;
        return !st.warned12 && TrainerTab.monthIndexNow(m, now) < 12
            && st.loadLb <= Plan.LENGTH_LOAD_MAX_LB + 1e-9
            && toLb > Plan.LENGTH_LOAD_MAX_LB + 1e-9;
    }
}
