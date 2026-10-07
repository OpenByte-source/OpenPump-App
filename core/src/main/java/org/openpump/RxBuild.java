package org.openpump;

/**
 * THE ROUTINE A PRESCRIPTION BECOMES.
 *
 * Every one of these methods was a private method on the Activity, and that placement was
 * the reason five things could only ever be checked with a cuff attached: the warm-up's
 * shape, a reduced session's commanded pressure, where the rests fall, whether the
 * retention hold closes the work, and what a split half actually contains. None of them is
 * about Android. All of them decide what the pump is told to do.
 *
 * MOVED, NOT REWRITTEN. The bodies are the same bodies, taking the model as an argument
 * instead of reading a field, so nothing about the routines the app builds changes - and
 * the desktop harness can now build the identical routine and drive a simulated pump
 * through it, which is what "test it before the cuff" has to mean.
 *
 * PURE, so test.sh compiles it: no Android import, and the one diagnostic log line the
 * reshape used to print is gone rather than smuggled behind an interface. What it said is
 * derivable from the prescription it returns.
 */
public final class RxBuild {

    private RxBuild() { }

    /**
     * THE DAY A BUILD IS FOR: its instant, the cut in force and whether it pulls slower.
     *
     * Every one of these used to be read off the clock inside the build, which is right for
     * a routine being written today and wrong for the one other question the builder is asked:
     * "is this saved routine still what you built?" (SavedMint). That routine was built on
     * the day it was saved - under that day's cut, that day's speed and that month's band - so
     * asking the question with today's figures made every routine saved on another kind of
     * day read as edited. The build takes the day as a value instead, and an ordinary mint
     * passes today ({@link #today}), which is exactly what it read before.
     */
    public static final class Day {
        public final long atMs;
        /** The cut taken off the prescription before a set is written, kPa. */
        public final double cutKpa;
        /** A taper step is in force: the day pulls slower as well as lower. */
        public final boolean gentle;
        /** A length girth-focus block: 1 in one, 0 not, -1 ask the track at {@link #atMs}. */
        public final int focus;
        /**
         * THE PRESSURE IS THE PERSON'S - set in "Adjust first..." - so the Program's Firm or
         * Gentle bias does not land on it (the owner's ruling, 0.10: the bias applies to the
         * plan's own prescription only). The day's cut and speed still do.
         */
        public final boolean ownKpa;
        /**
         * THE SET COUNT IS THE PERSON'S, OR WHAT IS LEFT - an "Adjust first..." save, or the
         * rest of a stopped session - not the plan's own prescription. It reaches one rule:
         * the hybrid, whose five-minute holds are otherwise the guidance's count whatever the
         * interval prescription says (the owner's ruling), honours a LOWER count - fewer
         * holds, never more than the guidance's (review M4, H1). Everywhere else the sets
         * are simply the prescription's, as they always were.
         */
        public final boolean ownSets;
        /**
         * t10 R-06 (R1) - BOTH TRACKS RUN TODAY: a length traction session ends after its
         * strain holds, with no tube swap and no expansion coda; the girth session gives up
         * nothing for it. Set by the caller for the run it starts (TrainerTab#dayChoice); a
         * mint for the library is built for a day of its own track (false).
         */
        public final boolean bothTracks;
        /**
         * t10 R-07 (R4) - GIRTH FOLLOWS LENGTH TODAY: a length session is already filed today,
         * so the girth session drops its fatigue block and leads in as the person chose
         * ({@link Model#girthAfterLength}). Set by the caller for the run it starts.
         */
        public final boolean girthAfterLength;
        /**
         * t10 R-05 (P4) - NO WARM-UP: the other track's session ended within the half hour
         * (SameDay#WARM_SKIP_WINDOW_MS), or a remainder built without one. No warm-up stage and
         * no P2 carry ramp (R-02): the tissue is already worked.
         */
        public final boolean skipWarm;

        public Day(long atMs, double cutKpa, boolean gentle, int focus) {
            this(atMs, cutKpa, gentle, focus, false, false);
        }

        public Day(long atMs, double cutKpa, boolean gentle, int focus, boolean ownKpa) {
            this(atMs, cutKpa, gentle, focus, ownKpa, false);
        }

        public Day(long atMs, double cutKpa, boolean gentle, int focus, boolean ownKpa,
                   boolean ownSets) {
            this(atMs, cutKpa, gentle, focus, ownKpa, ownSets, false, false, false);
        }

        public Day(long atMs, double cutKpa, boolean gentle, int focus, boolean ownKpa,
                   boolean ownSets, boolean bothTracks, boolean girthAfterLength,
                   boolean skipWarm) {
            this.atMs = atMs; this.cutKpa = cutKpa; this.gentle = gentle; this.focus = focus;
            this.ownKpa = ownKpa; this.ownSets = ownSets;
            this.bothTracks = bothTracks; this.girthAfterLength = girthAfterLength;
            this.skipWarm = skipWarm;
        }

        /** The same day, for a prescription whose pressure is (or is not) the person's. */
        public Day ownPressure(boolean own) {
            return own == ownKpa ? this : new Day(atMs, cutKpa, gentle, focus, own, ownSets,
                                                  bothTracks, girthAfterLength, skipWarm);
        }

        /** The same day, for a prescription whose set count is (or is not) the person's. */
        public Day ownSetCount(boolean own) {
            return own == ownSets ? this : new Day(atMs, cutKpa, gentle, focus, ownKpa, own,
                                                   bothTracks, girthAfterLength, skipWarm);
        }

        /** The same day, as the run it starts finds it (t10 R-05/R-06/R-07): both tracks
         *  today, girth after length, no warm-up. */
        public Day sameDay(boolean both, boolean afterLength, boolean noWarm) {
            if (both == bothTracks && afterLength == girthAfterLength && noWarm == skipWarm)
                return this;
            return new Day(atMs, cutKpa, gentle, focus, ownKpa, ownSets, both, afterLength,
                           noWarm);
        }

        /** The same day, for a prescription an "Adjust first..." save built (`a`, or none):
         *  its pressure the person's where they set it, its set count theirs. */
        public Day adjusted(Mint.Adjust a) {
            return ownPressure(a != null && a.ownKpa).ownSetCount(a != null);
        }

        /** The day `atMs` falls on, as the model says it is - ONE instant for every question,
         *  so a build straddling midnight cannot take one day's cut and the other day's speed. */
        public static Day at(Model model, long atMs) {
            return new Day(atMs, model.reductionKpa(atMs), model.gentleNow(atMs), -1);
        }

        public static Day today(Model model) { return at(model, System.currentTimeMillis()); }

        boolean girthFocus(Model model) {
            return focus < 0 ? model.trainerLength.inGirthFocus(atMs) : focus == 1;
        }
    }

    /** The whole prescription, unsplit - what every caller outside the split path wants. */
    public static String routineFromRx(Model model, Mint.Rx rx) {
        return routineFromRx(model, rx, 0, 0);
    }

    public static String routineFromRx(Model model, Mint.Rx rx,
                                       int part, int ofParts) {
        return routineFromRx(model, rx, part, ofParts, Day.today(model));
    }

    /** The same build, for the day given rather than for today. */
    public static String routineFromRx(Model model, Mint.Rx rx,
                                       int part, int ofParts, Day day) {
        return build(model, rx, part, ofParts, day, null);
    }

    /**
     * HOW MANY WORK HOLDS A ROUTINE BUILT FROM `rx` ON `day` RUNS - the count its name states,
     * make-up cycles, a ramp's top step and a hybrid's holds included - asked of the builder
     * itself, in a scratch copy of the model (nothing in `model` is touched). What a card or a
     * row describing that routine counts (the owner's ruling, 0.10: the count is what runs,
     * never the prescription's). -1 for a session that is not counted in holds (traction, named
     * for its load) or when it cannot tell.
     */
    public static int holdsRun(Model model, Mint.Rx rx, Day day) {
        if (model == null || rx == null || day == null) return -1;
        // Asked on every Trainer draw: remembered per input state (BuildCache).
        String key = "H|" + BuildCache.stamp(model, rx.track, rx.level, day.atMs) + "|"
            + BuildCache.rx(rx) + "|" + BuildCache.day(model, day);
        Object got = BuildCache.get(key);
        if (got != null) return ((Integer) got).intValue();
        int n;
        try {
            int[] out = { -1 };
            build(SavedMint.scratchOf(model), rx, 0, 0, day, out);
            n = out[0];
        } catch (RuntimeException e) {
            n = -1;
        }
        BuildCache.put(key, Integer.valueOf(n));
        return n;
    }

    /**
     * t10 R-01 / R-04 - WHAT THE ROUTINE CARD ADDS for `rx` as Save would build it today:
     * [0] the P2 warm-up line (#p2WarmLine: "Warm-up: 6 reps from -3.5 to -8.0 inHg, holds
     * 30 → 60 s", to where the reps get) where P2 builds the warm-up, and [1] #trimmedNote where
     * the two-hour trim shortened the routine. Asked of the builder in a scratch copy of the
     * model (nothing in `model` is touched) and remembered per input state, as #holdsRun is.
     * "" for nothing to say.
     */
    public static String[] cardLines(Model model, Mint.Rx rx) {
        if (model == null || rx == null || rx.track == Plan.TRACK_FEEDER)
            return new String[]{ "", "" };
        Day day = Day.today(model);
        String key = "C|" + BuildCache.stamp(model, rx.track, rx.level, day.atMs) + "|"
            + BuildCache.rx(rx) + "|" + BuildCache.day(model, day);
        Object got = BuildCache.get(key);
        if (got != null) return ((String[]) got).clone();
        String[] out = { "", "" };
        try {
            Model scratch = SavedMint.scratchOf(model);
            Model.Routine r = scratch.routine(build(scratch, rx, 0, 0, day, null));
            if (r != null) {
                out[1] = trimmedNote(r);
                for (int i = 0; i < r.stages.size() && p2Applies(scratch, rx.track); i++) {
                    Model.Stage st = r.stages.get(i);
                    if (st == null || st.colour != Model.STAGE_WARM || st.name == null
                            || !st.name.startsWith("Warm-up to ")) continue;
                    int end = lastPullKpa(scratch, st);
                    if (end > 0) out[0] = p2WarmLine(end, false);
                    break;
                }
            }
        } catch (RuntimeException e) {
            out = new String[]{ "", "" };
        }
        BuildCache.put(key, out);
        return out.clone();
    }

    /**
     * REAL-6 - THE HOLDS THE SESSION'S TIME CAP LETS RUN for `rx`: at its level, of its hold,
     * with the person's own fatigue block (Plan#r2MaxHolds, Mint#r2FatSec) - what the make-up
     * cycles may fill the work up to. Integer.MAX_VALUE for a track the cap does not apply to
     * (length, the feeder).
     */
    static int capHolds(Model model, Mint.Rx rx) {
        if (rx == null || (rx.track != Plan.TRACK_GIRTH_INTERVAL
                           && rx.track != Plan.TRACK_GIRTH_TRADITIONAL))
            return Integer.MAX_VALUE;
        return Plan.r2MaxHolds(rx.level, rx.holdSec, Mint.r2FatSec(model, rx.track, rx.level));
    }

    /** The whole cycles laid out in the work blocks so far - the counted holds, a counted
     *  climb's among them (an uncounted climb has blocks of its own). */
    private static int laidCycles(Model model, java.util.List<java.util.List<String>> blockIds,
                                  int cycleSec) {
        int n = 0;
        for (int b = 0; b < blockIds.size(); b++)
            for (int i = 0; i < blockIds.get(b).size(); i++) {
                Model.Set s = model.set(blockIds.get(b).get(i));
                if (s != null) n += s.dur / Math.max(1, cycleSec);
            }
        return n;
    }

    private static String build(Model model, Mint.Rx rx, int part, int ofParts, Day day,
                                int[] holdsOut) {
        /* A LENGTH TRACK THAT CAN PULL BUILDS A DIFFERENT SESSION. Diverted here, at the one
         * door every mint already comes through, so no caller has to know which shape it is
         * asking for and none of them can accidentally ask for the wrong one.
         *
         * THE CONDITION IS THE RACK, not a setting: with no cylinder that pulls there is no
         * load to govern, so the session is the expansion one this track has always run, and
         * an install with an empty rack is byte-for-byte unchanged. That is the same test the
         * ladder's first rung applies, asked the same way.
         *
         * A SPLIT IS NOT OFFERED for a traction session: its five blocks are a sequence -
         * release, pull, swap, expand - and half of it is not a smaller version of it. Splits
         * come from the girth path only, so ofParts is simply not consulted here.
         */
        if (rx != null && rx.track == Plan.TRACK_LENGTH) {
            // THE SAME QUESTION THE ENGINE ASKS, through the same method - this comment used
            // to claim "the same test the ladder's first rung applies, asked the same way"
            // while the two were asking about different cylinders entirely. A cylinder MARKED
            // for length pulls, with or without a logged girth (owner, 2026-09-30): the fit
            // band only warns (Model#lengthFitWarning).
            if (model.lengthPulls()) {
                if (holdsOut != null) holdsOut[0] = -1;     // named for its load, not a count
                Model.TrainerTrackState st = model.trainerLength;
                /* ONE LENGTH OFFSET MOVES BOTH HALVES (the owner, final): the pulls take the
                 * length offset in their own pounds (Scale#commandedLoadLb), the expansion coda
                 * takes it in pressure through the prescription, so the two move together. */
                return tractionRoutineFromRx(model, reshapeToHold(model, rx),
                    Scale.pullLoadLb(model, day.atMs), model.lengthBoreCm(),
                    st.strainSets, day.girthFocus(model), day);
            }
        }
        /* The hybrid's holds are five minutes whatever "each hold" says, so the interval hold
         * is not reshaped under it: a reshape recounts the sets from the target at the other
         * hold, and a count the person chose (or what is left of a session) came out as some
         * other number of holds (review M4). The plan's own hybrid is untouched by this -
         * #hybridRx gives it the guidance's count whatever the sets. */
        final boolean hybrid = hybridApplies(model, rx);
        if (!hybrid) rx = reshapeToHold(model, rx);
        // The compensation (Q4) measures against the PRESCRIPTION - the day's, cut but not
        // biased - so a Gentle bias is made up for and a Firm one is not paid back.
        // (t10-K, B3: and the gentle week's 60 % after the month-12 break, girth only.)
        final int prescribedRef =
            MonthBreak.girthDay(model, dayOf(rx, day), day.atMs).pressureKpa;
        // How far this routine sits from the plan's own figure (0.10) - stamped on it below.
        final int scale = scaleKpa(model, rx, day);
        // WHAT THE PUMP IS TOLD: the bias on the full prescription, then the day's cut and
        // speed (see #commanded - the card and the row ask the same function).
        rx = commanded(model, rx, day);
        /* D11 - THE HYBRID IS THE GUIDANCE'S HYBRID (the owner's decision): the fatigue block,
         * then five-minute traditional holds IN PLACE OF the intervals. It used to put one
         * five-minute hold in front of the full interval volume - a session the guidance does
         * not describe, whose extra hold was counted by net and missing from the target.
         * Swapped in here, before the split and the layout, so every rule below - the halves,
         * the rests, the shape, the make-up, the target restated from what is laid out - reads
         * the hybrid exactly as it reads any other prescription. */
        if (hybrid) rx = hybridRx(model, rx, day.ownSets);
        // R-27 / REAL-6: the holds the session's time cap lets run, counted on the whole session
        // before a split halves it - what the make-up below may fill up to and never past.
        final int capHolds = capHolds(model, rx);
        if (ofParts > 1) {
            int sets = Mint.splitSets(rx.sets, part);
            /* A REDUCED HALF STILL ASKS FOR NOTHING. This recomputes the part's own net
             * target from its own set count - which is right for an ordinary split, and was
             * quietly wrong for a reduced one: applyReduction had just set the target to
             * zero to say "this session was not asked for net", and this line put a real
             * figure back. A reduced split half would then be scored as a total failure,
             * and three of them would propose stepping the level back - the exact defect Q2
             * exists to remove, reintroduced one line later. */
            double half = rx.netTargetMin <= 0.0
                        ? 0.0 : Math.max(1, sets) * (rx.holdSec / 60.0);
            rx = new Mint.Rx(rx.track, rx.level, Math.max(1, sets), rx.holdSec, rx.restSec,
                             rx.pressureKpa, rx.fatigue, half, rx.powerPct, rx.offKpa);
        }
        final int mintPart = part, mintOfParts = ofParts;
        /* WAVE 3b — the track's Program shapes this mint. The pressure bias lands on the
         * prescription itself, so the warm-up ("never deeper than the work") and every
         * block follow it; the feeder has no Program and takes none of this. */
        Model.Program prog = model.programFor(rx.track);
        Mint.SetSpec wspec = Mint.workSet(rx, model.rxDropKpa);

        Model.Routine r = new Model.Routine();
        r.id = model.newRoutineId();
        // (Named once the work is laid out, below: the name counts the holds it runs.)
        r.trainerTrack = rx.track;
        // Q2 - stamped at birth beside trainerLevel, so this routine is scored against what
        // IT was asked for however far the prescription moves afterwards. A split part
        // already carries its own halved rx by this point, so this is that part's target.
        // (Restated below once the work is laid out: D12 - the target is the work it counts.)
        r.netTargetMin = rx.netTargetMin;
        // Stamped at birth, so this routine's Net TUP is always scored against the floor it
        // was actually prescribed at, however far the track's level moves afterwards (A8).
        r.trainerLevel = rx.level;
        // ...and the scale it was built on, so a session run under the plan still counts.
        r.trainerScaleKpa = scale;

        // ONE SET THAT RUNS FOR THE WHOLE BLOCK, not N references to a one-rep set
        // (audit F1). Mint.workSet's dur is exactly one cycle -- hold plus drop -- so the
        // old shape pushed the same id into the stage rx.sets times and the editor,
        // truthfully, drew that many identical rows: ten "Work hold" lines to scroll past
        // for one instruction.
        //
        // WHAT THE PUMP DOES IS UNCHANGED. A fixed set repeats its own hold/drop cycle for
        // its duration -- that is what the device does with one preset -- so one set of
        // N x cycle commands exactly the profile N sets of one cycle did, minus N-1
        // re-arms. The signature is computed from the prescription, not from this shape
        // (Mint.signature reads rx), so idempotency across the change is unaffected.
        //
        // Chunked only because Set#dur is bounded at an hour: a block longer than that
        // becomes as few whole-cycle sets as it takes, never a rep-by-rep list.
        //
        // AND SPLIT INTO BLOCKS WHERE THE PRESCRIPTION ASKS FOR A REST BETWEEN THEM. The L2
        // table's "rest 3-5 min every 5 sets" needed a seam to sit in and there was none:
        // one unbroken set has no every-five-sets boundary. Mint#workBlocks decides the
        // division; L1 gets a single block, which is its table's own shape.
        int cycleSec = Math.max(1, wspec.uh + wspec.lh);
        int perChunkMax = Math.max(cycleSec, (3600 / cycleSec) * cycleSec);
        /* R4 - HOW OFTEN THE RESTS FALL. The guidance rests three to five minutes after
         * every five sets, which is a RANGE the app was treating as a point. Shorter blocks
         * are the honest
         * accommodation for somebody who cannot hold five straight: the cycles are
         * identical either way, the recovery is simply more frequent. */
        int per = Mint.setsPerBlock(rx.track, rx.level);
        /* THE PREFERENCE GOVERNS THE INTERVAL TRACK'S RANGE ONLY. The guide gives interval
         * work "every 3-5 sets", which is the range this setting exists to move within. A
         * traditional set is its own block by definition, and letting the preference merge
         * two of them would delete the rest the guide asks for between them. */
        if (per > 0 && rx.track == Plan.TRACK_GIRTH_INTERVAL) per = model.rxSetsPerBlock;
        // A hybrid's traditional hold is its own block, as a traditional set is: the guidance
        // rests between each of them.
        if (hybrid) per = 1;
        int[] blocks = Mint.workBlocks(Math.max(1, rx.sets), per);
        /* WAVE 3b — the WORK-SET SHAPE. Two passes: the chunk layout first, then each
         * chunk's pressure from Mint.chunkUpKpa (ascending climbs the band, pyramid
         * peaks at the prescription mid-session) or a ramp INSIDE each set. Work below
         * the prescription is compensated pro-rata back to the plan's own volume (Q4). */
        int workShape = prog == null ? Model.Program.WORK_FIXED : prog.work;
        /* 0.10 - THE RAMPS (the owner's decisions; Model#rampStartPct and beside it). A Ramped
         * block climbs from a share of the day's working pressure - the first block fully, a
         * block after a rest a short way - a step at most the person's each hold, then holds
         * at the working pressure; a lighter day keeps its ramp, climbing to the lighter
         * figure. Laid out by #layoutRamped. Where there is nothing to climb today (the start at
         * the work, blocks of one hold, a lighter day the person runs flat) it is the fixed
         * holds it would play. A scratch model rebuilding a routine minted before the settings
         * (Model#legacyRamp) takes the old path below, the climb from the level's floor. */
        RampPlan rampPlan = null;
        if (workShape == Model.Program.WORK_RAMP_IN_SET && !model.legacyRamp) {
            rampPlan = rampPlan(model, wspec.up, blocks, day);
            if (rampPlan == null) workShape = Model.Program.WORK_FIXED;
        }
        int floorK = (int) Math.round(Plan.floorKpa(rx.level));
        java.util.List<int[]> chunkPlan = new java.util.ArrayList<int[]>();
        for (int b = 0; b < blocks.length && rampPlan == null; b++) {
            int remaining = blocks[b] * cycleSec;
            while (remaining > 0) {
                int take = remaining > perChunkMax ? perChunkMax : remaining;
                chunkPlan.add(new int[]{ b, take });
                remaining -= take;
            }
        }
        java.util.List<java.util.List<String>> blockIds =
            new java.util.ArrayList<java.util.List<String>>();
        // A block's climb, where it is a stage of its own (the climb not counted, 0.10).
        java.util.List<java.util.List<String>> climbIds =
            new java.util.ArrayList<java.util.List<String>>();
        for (int b = 0; b < blocks.length; b++) {
            blockIds.add(new java.util.ArrayList<String>());
            climbIds.add(new java.util.ArrayList<String>());
        }
        double effSec = 0;
        // The work ramps, in order - where a ramp's compensation goes (D3, below).
        java.util.List<Model.Set> ramps = new java.util.ArrayList<Model.Set>();
        java.util.List<Model.Set> workChunks = new java.util.ArrayList<Model.Set>();
        int chunkNo = 0;   // names the work sets: "Work hold", "Work hold 2", ...
        final String holdName = hybrid ? "Traditional hold" : "Work hold";
        // The climbs' sets (0.10), for the count below: every hold is named, not all counted.
        java.util.List<Model.Set> climbSets = new java.util.ArrayList<Model.Set>();
        if (rampPlan != null)
            effSec = layoutRamped(model, rampPlan, wspec, blocks, cycleSec, perChunkMax,
                prescribedRef, countFromKpa(model, r, day), holdName, blockIds, climbIds,
                workChunks, climbSets);
        for (int ci = 0; ci < chunkPlan.size(); ci++) {
            int bIdx = chunkPlan.get(ci)[0], take = chunkPlan.get(ci)[1];
            String id = model.newSetId();
            String nm = chunkNo == 0 ? holdName : holdName + " " + (chunkNo + 1);
            Model.Set chunk;
            int rampSteps = Math.min(Proto.SLOTS, Math.max(1, take / cycleSec));
            int start = Math.max(2, Math.min(floorK, wspec.up));
            /* D9 - A "RAMP" THAT HAS NOTHING TO CLIMB IS A FIXED SET. At or under the floor (a
             * Gentle bias, a low week, every reduced day) the start and the top are one
             * figure, and the ramp played the same preset as N separate steps under a ramp's
             * name. The plain set it really is commands the identical pressure. */
            if (workShape == Model.Program.WORK_RAMP_IN_SET && rampSteps >= 2
                    && start < wspec.up) {
                chunk = Model.Set.ramp(id, nm, start, RunEdit.dropKept(wspec.lo, start, false),
                    wspec.uh, wspec.lh, wspec.sp, wspec.up, wspec.lo, wspec.uh,
                    wspec.lh, wspec.sp, rampSteps, take);
            } else {
                int up = Mint.chunkUpKpa(workShape, ci, chunkPlan.size(), floorK, wspec.up);
                chunk = Model.Set.fixed(id, nm, up, RunEdit.dropKept(wspec.lo, up, false),
                    wspec.uh, wspec.lh, wspec.sp, take);
            }
            chunk.fromPlan = true;      // L4 - written by the plan, not by you
            chunk.clamp(model.ceilKpa);
            model.sets.add(chunk);
            blockIds.get(bIdx).add(id);
            effSec += Mint.effectiveSec(take, chunk.up, chunk.up2, chunk.ramp, prescribedRef);
            if (chunk.ramp) ramps.add(chunk);
            workChunks.add(chunk);
            chunkNo++;
        }
        // The top-up runs at the SAME biased pressure the user chose — gentle stays
        // gentle — so each extra cycle also counts pro-rata, and more of them are added.
        double effCycle = Mint.effectiveSec(cycleSec, wspec.up, wspec.up, false,
                                            prescribedRef);
        int extraCycles = Mint.compensationCycles(effSec,
            Math.max(1, rx.sets) * cycleSec, (int) Math.max(1, Math.floor(effCycle)));
        /* D12 - NOTHING TO MAKE UP ON A DAY THAT ASKS FOR NO NET. The make-up cycles exist to
         * bring the work up to the volume the plan asks for, and a reduced day asks for none
         * (its target is 0 - Q1/Q2): it is lighter on purpose. Since D2 a Gentle bias lands
         * before the day's cut, so a taper day's Gentle work can sit a long way under even
         * the reduced prescription - and measured pro-rata against it, the "make-up" came to
         * several times the session's own length, at the lowest pressure in the plan, on the
         * day meant to be easiest. */
        if (rx.netTargetMin <= 0.0) extraCycles = 0;
        /* REAL-6 (t10 round 2, the parity run) - THE MAKE-UP COUNTS IN THE TIME CAP. The
         * session's time under pressure is capped at 20 / 30 / 36 / 44 minutes (R-27), and the
         * plan writes no hold past it; the make-up cycles are holds too, and a Gentle day or a
         * ramp added them on top of a session already at the cap - 25 minutes at Level 1. They
         * fill the room under the cap and no more (a split half takes its share of it). */
        if (capHolds != Integer.MAX_VALUE) {
            int cap = ofParts <= 1 ? capHolds
                : part == 1 ? (capHolds + 1) / 2 : capHolds - (capHolds + 1) / 2;
            extraCycles = Math.min(extraCycles,
                Math.max(0, cap - laidCycles(model, blockIds, cycleSec)));
        }
        /* D3 - A RAMP'S MAKE-UP IS MORE RAMP, not a second set after it (the owner's
         * decision). The top-up used to follow the ramp at the ramp's own last pressure, so
         * the run climbed to the top, then announced a new set and held the same figure
         * again: "Set 1 at -8.0" straight after the ramp's own step at -8.0. The extra cycles
         * are the same cycles; they are now the ramp's own steps - the ramp gets N+1 where it
         * owed one - so the climb simply takes as long as the plan needs. */
        if (extraCycles > 0 && rampPlan != null)
            extraCycles = foldIntoWork(model, workChunks, blockIds, extraCycles, cycleSec,
                                       perChunkMax);
        else if (extraCycles > 0 && workShape == Model.Program.WORK_RAMP_IN_SET)
            extraCycles = foldIntoRamps(ramps, extraCycles, cycleSec, model.ceilKpa);
        /* D6 - EVERY RAMP STEP IS WHOLE CYCLES. A ramp longer than the device's table (more
         * cycles than Proto.SLOTS steps) used to divide its time evenly over nine steps, so each
         * step cut a hold short and began another. Its last step now holds the whole cycles
         * left over (#topStep), and it is placed straight after its ramp, in the same block. */
        for (int i = 0; i < ramps.size(); i++) {
            Model.Set ramp = ramps.get(i);
            Model.Set top = topStep(model, ramp, cycleSec);
            if (top == null) continue;
            for (int b = 0; b < blockIds.size(); b++) {
                int at = blockIds.get(b).indexOf(ramp.id);
                if (at >= 0) { blockIds.get(b).add(at + 1, top.id); break; }
            }
            workChunks.add(workChunks.indexOf(ramp) + 1, top);
        }
        if (extraCycles > 0) {
            // The plan keeps its own volume: the below-prescription shortfall comes back as
            // whole cycles at the WORK'S OWN FIGURE - the biased one, so a Gentle routine's
            // make-up is Gentle too - appended to the last block. (Ramped work took its
            // make-up as more ramp steps above, and a reduced day has none.)
            String id = model.newSetId();
            Model.Set topUp = Model.Set.fixed(id, "Top-up hold",
                wspec.up, wspec.lo, wspec.uh, wspec.lh, wspec.sp,
                Math.min(3600, extraCycles * cycleSec));
            topUp.fromPlan = true;
            topUp.clamp(model.ceilKpa);
            model.sets.add(topUp);
            blockIds.get(blockIds.size() - 1).add(id);
            workChunks.add(topUp);
        }
        /* D12 - THE TARGET IS THE WORK THE PLAN COUNTS, in the net's own currency.
         *
         * Net counts every hold at or above the count-from line in full; the compensation is
         * sized pro-rata. The target used to be the prescription's minutes alone, so a
         * Gentle or ramped routine - whose work sits under the prescription and is made up
         * with extra cycles - planned 12:00 or 16:00 of counted holds against a 10:00 or
         * 12:00 target, and the scoring credited it as over-delivery on work lighter than
         * the prescription. The made-up cycles are part of what this routine asks for, so
         * they are part of its target: every work cycle is one hold of the prescribed length
         * (the fatigue block stays out of net by its own flag, the warm-up by its colour).
         * A session asked for no net - a reduced day - still asks for none. */
        int workCycles = 0;
        for (int i = 0; i < workChunks.size(); i++)
            workCycles += workChunks.get(i).dur / cycleSec;
        /* 0.10 - A COUNTED CLIMB IS COUNTED WHERE NET COUNTS IT. The climb's holds are the
         * block's own and count in full (the owner's decision) - but net counts a hold only at
         * or above the line this routine is scored from, and a climb that starts at 80 % of
         * the work can start under it. Such a hold is run and named, counted by nothing and
         * not made up (#layoutRamped): the target leaves it out, so the plan counts exactly its
         * target, and its minutes are recorded beside the target (Routine#climbUnderLineMin)
         * for the level to credit, so target and credit together are the plan's volume. A
         * climb the person does not count is a stage of its own, out of net, and in neither
         * the target, the credit nor the name's count. */
        int countedCycles = workCycles, underLine = 0;
        if (model.rampCountClimb) {
            for (int i = 0; i < climbSets.size(); i++) {
                Model.Set cs = climbSets.get(i);
                int holds = cs.dur / cycleSec, counts = countedClimbHolds(model, r, cs, day);
                workCycles += holds;
                countedCycles += counts;
                underLine += Math.max(0, holds - counts);
            }
        }
        if (rx.netTargetMin > 0.0) {
            r.netTargetMin = countedCycles * (rx.holdSec / 60.0);
            r.climbUnderLineMin = Model.clampClimbUnder(underLine * (rx.holdSec / 60.0));
        }
        if (holdsOut != null) holdsOut[0] = workCycles;
        /* THE NAME STATES THE HOLDS THIS ROUTINE RUNS (the owner's decision, 0.10), make-up
         * cycles included: named from the prescription it said "5×2min" over a Gentle routine
         * that runs seven. The same count the target is restated from, so the name, the target
         * and the work cannot disagree; the pressure is the one the work commands. */
        r.name = Mint.name(rx, workCycles) + (mintOfParts > 1
            ? "  ·  part " + mintPart + " of " + mintOfParts : "");
        /* THE WARM-UP IS PART OF THE PRESCRIPTION, not a separate offer.
         *
         * It used to be a card that appeared after a level change and added a ramp to
         * whatever routine happened to be SELECTED - which is not necessarily the one the
         * plan just wrote, so a prescription could arrive with no way into its own working
         * pressure while an unrelated routine got a warm-up it never asked for. A routine
         * that opens with a full-pressure hold from cold is the thing a warm-up exists to
         * prevent, so it belongs inside the routine the plan builds rather than beside it.
         */
        // (Built once the work is laid out, below: it must end under the work's first pull.)
        final boolean wantWarm = !(mintOfParts > 1 && mintPart > 1 && !model.rxSplitWarmBoth);
        // Retention closes the WORK, so it goes on the last part only.
        Model.Stage holdStage = (mintOfParts > 1 && mintPart < mintOfParts)
            ? null : retentionStage(model, rx);

        // ONE STAGE PER BLOCK, so the rest between them is a stage of its own and the rail
        // draws the session's real shape - work, rest, work - rather than one green bar.
        java.util.List<Model.Stage> workStages = new java.util.ArrayList<Model.Stage>();
        java.util.List<Model.Stage> climbStages = new java.util.ArrayList<Model.Stage>();
        for (int b = 0; b < blockIds.size(); b++) {
            java.util.List<String> ids = blockIds.get(b);
            String stName = hybrid
                ? (b == 0 ? "Traditional hold" : "Traditional hold " + (b + 1))
                : (blockIds.size() == 1 ? "Work" : "Work " + (b + 1));
            workStages.add(Model.Stage.of(stName, Model.STAGE_WORK,
                ids.toArray(new String[ids.size()])));
            java.util.List<String> cids = climbIds.get(b);
            Model.Stage cst = null;
            if (!cids.isEmpty()) {
                cst = Model.Stage.of(blockIds.size() == 1 ? "Climb" : "Climb " + (b + 1),
                    Model.STAGE_WORK, cids.toArray(new String[cids.size()]));
                cst.climb = true;           // out of net: the person counts it as nothing
            }
            climbStages.add(cst);
        }

        int fatMode = prog == null ? Model.Program.FAT_STANDARD : prog.fatigue;
        /* t10 R-07 (R4) - GIRTH AFTER LENGTH, the same day: from L3 the fatigue block goes, and
         * in its place the person's choice (Model#girthAfterLength) - a ramp-in, the first
         * holds ramped, or nothing. At L1/L2 there is no fatigue block to drop, so the choice
         * leads in only when P4 has dropped the warm-up (fix e). R2's hold limit is the
         * plan's, counted with the fatigue block, so nothing here adds a hold. */
        final boolean r4 = day.girthAfterLength && rx.fatigue && fatMode != Model.Program.FAT_OFF
            && rx.level >= Plan.L3;
        final boolean r4lo = !r4 && day.girthAfterLength && day.skipWarm && rx.level < Plan.L3
            && model.girthAfterLength != Model.R4_NONE;
        final int r4Mode = (r4 || r4lo) ? model.girthAfterLength : -1;
        // The holds in order, for the climbs below (R-02's carry, R-07's first holds).
        java.util.List<Model.Stage> heldStages = new java.util.ArrayList<Model.Stage>();
        if (rx.fatigue && fatMode != Model.Program.FAT_OFF && !r4) {
            /* THE FATIGUE BLOCK RUNS FIRST, then the rest, then the main work. The spec is
             * explicit about the order - "L3 -> two blocks: fatigue (10 x 30-60 s) THEN main
             * (7-20 x 1-3 min)" - and this built them the other way round, so every minted
             * L3+ routine ran its main block into a finisher instead of pre-fatiguing into
             * its main block. That is not a cosmetic ordering: the two blocks exist to be
             * done in that sequence, and a routine that runs them backwards is a different
             * session from the one prescribed.
             *
             * Nothing else moves. Net TUP excludes the fatigue block by its own flag, never
             * by position; Mint#signature is computed from the prescription rather than from
             * this shape, so idempotency across the change is unaffected. */
            // R11-5: the person's fatigue hold (30 s by default), the block's minutes kept.
            Mint.SetSpec fspec = Mint.fatigueSet(rx, model.rxFatigueHoldSec);
            String fatSetId = model.newSetId();
            Model.Set fat = Model.Set.fixed(fatSetId, "Fatigue hold",
                fspec.up, fspec.lo, fspec.uh, fspec.lh, fspec.sp, fspec.dur);
            fat.fromPlan = true;
            fat.clamp(model.ceilKpa);
            model.sets.add(fat);
            // THE FATIGUE BLOCK GETS THE SAME TREATMENT AS THE WORK STAGE (F1, round two).
            // The work stage was fixed to emit one duration-filled set; this one was missed
            // and went on pushing the same id in FATIGUE_BLOCK_SETS times, so a minted L3+
            // routine still opened on a column of identical "Fatigue hold" rows \u2014 which is
            // exactly what the original report showed, one stage further down.
            //
            // Same reasoning, same result: fatigueSet's dur is one cycle, a fixed set repeats
            // its own cycle for its duration, so one set of N x cycle commands the identical
            // profile. The stage keeps its fatigueBlock mark, so those frames stay out of
            // Net TUP exactly as before.
            int fatCycle = Math.max(1, fspec.uh + fspec.lh);
            int fatChunkMax = Math.max(fatCycle, (3600 / fatCycle) * fatCycle);
            int fatSets = Math.max(1, Plan.FATIGUE_BLOCK_SETS);
            // EXTENDED adds half again (wave 3b); OFF never reaches here.
            if (fatMode == Model.Program.FAT_EXTENDED) fatSets = fatSets * 3 / 2;
            fatSets = Mint.fatigueHolds(fatSets, fspec.uh);
            int fatLeft = fatSets * fatCycle;
            java.util.List<String> fatIdList = new java.util.ArrayList<String>();
            int fatPart = 0;
            while (fatLeft > 0) {
                int take = fatLeft > fatChunkMax ? fatChunkMax : fatLeft;
                String id = fatPart == 0 ? fatSetId : model.newSetId();
                if (fatPart == 0) {
                    fat.dur = take;
                    fat.clamp(model.ceilKpa);
                } else {
                    Model.Set more = Model.Set.fixed(id, "Fatigue hold " + (fatPart + 1),
                        fspec.up, fspec.lo, fspec.uh, fspec.lh, fspec.sp, take);
                    more.fromPlan = true;
            more.clamp(model.ceilKpa);
                    model.sets.add(more);
                }
                fatIdList.add(id);
                fatLeft -= take;
                fatPart++;
            }
            Model.Stage fs = Model.Stage.of("Fatigue block", Model.STAGE_WORK,
                fatIdList.toArray(new String[fatIdList.size()]));
            fs.fatigueBlock = true;
            r.stages.add(fs);
            heldStages.add(fs);
            if (rx.restSec > 0)
                r.stages.add(Model.Stage.restOf("Rest", model.restSecFor(rx.track)));
        }
        /* WAVE 3b (Q7a) - HYBRID, from L3: its traditional holds ARE the work blocks above
         * (D11, #hybridRx), so there is nothing to add here. */
        for (int b = 0; b < workStages.size(); b++) {
            // THE REST GOES BETWEEN BLOCKS, never after the last one: a routine that ends on
            // three minutes of nothing has a rest the user waits through for no reason, and
            // the summary would count it as session time.
            // Q4 - the track's own rest. Traditional girth is given 2-5 minutes by the
            // guide where interval work gets 3, and one number covered both.
            if (b > 0 && rx.restSec > 0)
                r.stages.add(Model.Stage.restOf("Rest", model.restSecFor(rx.track)));
            if (climbStages.get(b) != null) {
                r.stages.add(climbStages.get(b));
                heldStages.add(climbStages.get(b));
            }
            r.stages.add(workStages.get(b));
            heldStages.add(workStages.get(b));
        }
        final int workKpa = Math.min(wspec.up, model.ceilKpa);
        // R-07: the ramp-in leads into the work, after the warm-up (where there is one).
        if (r4Mode == Model.R4_WARM) {
            Model.Stage in = r4RampIn(model, rx, workKpa);
            if (in != null) r.stages.add(0, in);
        }
        // R-07 "Ramped first sets": the first holds climb to the work, and count.
        if (r4Mode == Model.R4_SETS)
            lowerHolds(model, workStages, new FirstSetsRule(workKpa, Plan.R4_RAMP_SETS,
                                                            Plan.P2_STEP_KPA));
        // FIRST IN THE LIST, whatever else the prescription put there. The fatigue block is
        // inserted before the work above; the warm-up goes in front of all of it, because
        // the pressure it ramps into is the pressure every block after it holds.
        /* D5 - THE WARM-UP ENDS UNDER WHERE THE WORK STARTS. Asked of the work as laid out -
         * the fatigue block, a ramp's floor, an ascending first chunk - because a warm-up that
         * climbed to the prescription and then handed over to work starting at the floor was
         * the hardest pull of the session, first, under the warm-up's name. */
        /* t10 R-01 - P2 ends AT the work where it gets there, so D5's "under the first pull" is
         * now the gentle warm-up's alone (it asks the first pull). P4 (R-05): none at all when
         * the other track ended within the half hour (Day#skipWarm). */
        Model.Stage warmStage = wantWarm && !day.skipWarm
            ? warmupStage(model, rx, firstPullKpa(model, r), false) : null;
        /* t10 R-02 - THE CARRY RAMP: a P2 warm-up that stopped short of the work (its step is
         * held to 1.0 inHg) climbs on through the holds after it at +1 kPa a hold - the fatigue
         * block, then the work - until it reaches the work. They stay what they are: a fatigue
         * hold out of the count, a work hold counted. Not after an R4 ramp-in, which reaches
         * the work itself. */
        if (warmStage != null && p2Applies(model, rx.track) && r4Mode != Model.R4_WARM
                && !model.legacyT10) {
            int end = lastPullKpa(model, warmStage);
            if (end > 0 && end < workKpa)
                lowerHolds(model, heldStages, new CarryRule(end, workKpa, Plan.P2_CARRY_KPA));
        }
        if (holdStage != null) r.stages.add(holdStage);
        if (warmStage != null) r.stages.add(0, warmStage);
        holdToHardLimits(model, r, rx.track, day.atMs);
        /* t10 R-04 (P1) - two hours on the whole clock at most: counted work off the end. The
         * name, the target and the count say what is left. */
        int trimmed = model.legacyT10 ? 0 : trimToCap(model, r);
        if (trimmed > 0) {
            workCycles = Math.max(0, workCycles - trimmed);
            if (r.netTargetMin > 0.0)
                r.netTargetMin = Math.max(0.0, r.netTargetMin - trimmed * (rx.holdSec / 60.0));
            if (holdsOut != null) holdsOut[0] = workCycles;
            r.name = Mint.name(rx, workCycles) + (mintOfParts > 1
                ? "  ·  part " + mintPart + " of " + mintOfParts : "");
        }
        // THE CHECK PULL NEVER EXCEEDS THE SESSION (wave 1 §4, owner ruling): the
        // default Assess is 20 kPa in the first commanding stage's tube. Capped at this
        // routine's own work peak — min never raises, so a re-mint is idempotent.
        int wavePk = model.workPeakKpa(r);
        if (wavePk > 0) r.assess.kpa = Math.min(r.assess.kpa, wavePk);
        model.routines.add(r);
        return r.id;
    }


    /**
     * THE PRESCRIPTION AS THE PUMP IS TOLD IT on `day`: the track's pressure bias, then the
     * day's cut, then the day's speed. The one definition of "the figure this routine runs
     * at" - the builder writes its sets and its name from it, and the Trainer card and row
     * print it ({@link #commandedKpa}), so none of the three can state a pressure the pump is
     * not sent (audit D4b: the card printed the plain prescription beside a Firm routine
     * named, and run, at the band's top).
     *
     * D2 - THE BIAS COMES FIRST, the cut second (the owner's decision). The other way round,
     * Firm's "the top of the band" put a taper day's or a big cylinder's reduced figure
     * straight back up to the band cap: a first day back from a break ran at full pressure
     * with only the slower pull to show it was a gentle day. Biased first, the day's cut
     * comes off whatever the bias chose - Firm runs lighter on a lighter day like every
     * other choice, and Gentle, already under the prescription, goes lighter still.
     */
    public static Mint.Rx commanded(Model model, Mint.Rx rx, Day day) {
        if (rx == null) return null;
        int bp = personalKpa(model, rx, day);
        if (bp != rx.pressureKpa)
            rx = new Mint.Rx(rx.track, rx.level, rx.sets, rx.holdSec, rx.restSec, bp,
                             rx.fatigue, rx.netTargetMin, rx.powerPct, rx.offKpa);
        // t10-K (B3): girth in the gentle week after the month-12 break runs at its 60 %.
        return MonthBreak.girthDay(model, dayOf(rx, day), day.atMs);
    }

    /**
     * THE FIGURE BEFORE THE DAY'S CUT: the Program's gentle or firm on the prescription (the
     * owner's decision, 0.10: one inHg under or over the plan, stacked on the person's offset
     * the prescription already carries - Scale#biasedKpa), then the HARD LIMITS on whatever
     * came out - the ceiling, "Most you will go to", 15 inHg and a new person's first-month
     * 6 inHg (Scale#hardKpa). The hard limits bind every build, a pressure the person set in
     * "Adjust first..." and a prescription read back from an older signature included: the
     * stated maximum used to be read at setup and nowhere after it.
     *
     * A pressure the person set in "Adjust first..." is theirs: no bias (Day#ownKpa).
     */
    static int personalKpa(Model model, Mint.Rx rx, Day day) {
        int month = TrainerTab.monthIndexNow(model, day.atMs);
        return Math.max(1, Math.min(biasedOf(model, rx, day, month),
                                    Scale.hardKpa(model, rx.track, month)));
    }

    /** The prescription with the Program's bias on it (none on a pressure that is the
     *  person's), before the final hard-limit clamp #personalKpa adds. */
    private static int biasedOf(Model model, Mint.Rx rx, Day day, int month) {
        Model.Program prog = model.programFor(rx.track);
        if (day.ownKpa || prog == null || prog.pressure == Model.Program.PRESS_STANDARD)
            return rx.pressureKpa;
        return Scale.biasedKpa(rx.pressureKpa, prog.pressure,
            Scale.effectiveTopWholeKpa(model, rx.track, rx.level, month),
            Scale.hardKpa(model, rx.track, month));
    }

    /**
     * HOW FAR A ROUTINE BUILT FROM `rx` ON `day` SITS FROM THE PLAN'S OWN FIGURE, kPa, before
     * the day's cut: the person's offset as it landed (Mint.Rx#offKpa) and the Program's bias
     * as it landed. What the routine is stamped with (Model.Routine#trainerScaleKpa). A hard
     * limit that holds the work down is NOT in it: it moves no counting line, as a low
     * ceiling never did - only the person's own scale does.
     */
    public static int scaleKpa(Model model, Mint.Rx rx, Day day) {
        if (model == null || rx == null || day == null) return 0;
        int month = TrainerTab.monthIndexNow(model, day.atMs);
        return biasedOf(model, rx, day, month) - rx.pressureKpa + rx.offKpa;
    }

    /**
     * THE HARD LIMITS ON EVERY SET A BUILD WROTE (0.10), the last thing a build does: a set
     * above the limit its stage answers to is clamped to it - a traction pull to the pull's
     * own (Scale#pullCapKpa: its first-month limit is its load's), everything else to the
     * trainer's (Scale#hardKpa). Belt and braces behind #personalKpa, the way every set is
     * also clamped to the ceiling: nothing the plan writes can pass "Most you will go to".
     */
    static int holdToHardLimits(Model model, Model.Routine r, int track, long atMs) {
        if (model == null || r == null) return 0;
        int month = TrainerTab.monthIndexNow(model, atMs);
        // A length session's expansion runs in the girth tube: girth's limit binds it too
        // (Scale#workHardKpa, review I3).
        return holdToHardLimits(model, r, Scale.workHardKpa(model, track, month),
                                Scale.pullCapKpa(model, model.lengthBoreCm(), atMs));
    }

    /**
     * The same with the limits given: `hard` for every commanding stage, `pull` for a traction
     * stage's. The routine's sets are `model`'s - a scratch copy's, where a comparison holds a
     * copy of a saved routine to TODAY's limits (SavedMint, review 2 finding 1). Returns how
     * many sets it clamped.
     */
    static int holdToHardLimits(Model model, Model.Routine r, int hard, int pull) {
        if (model == null || r == null) return 0;
        int n = 0;
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest) continue;
            int lim = st.traction ? pull : hard;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = model.set(st.setIds.get(j));
                if (s == null || s.rest) continue;
                if (s.up > lim || (s.ramp && s.up2 > lim)) { s.clamp(lim); n++; }
            }
        }
        return n;
    }

    /** {@link #commanded}'s pressure for today - what a card offering `rx` should print. */
    public static int commandedKpa(Model model, Mint.Rx rx) {
        return commandedKpa(model, rx, false);
    }

    /** The same, for a prescription whose pressure is the person's when `ownKpa` - a saved
     *  "Adjust first..." routine, which takes the day's cut and no bias. */
    public static int commandedKpa(Model model, Mint.Rx rx, boolean ownKpa) {
        Mint.Rx c = commanded(model, rx, Day.today(model).ownPressure(ownKpa));
        return c == null ? 0 : c.pressureKpa;
    }

    /**
     * `rx` WITH THE PRESSURE IT RUNS AT TODAY in place of the prescription's - #commandedKpa,
     * the person's pressure unbiased where `ownKpa`. What a "your plan changed" notice compares
     * (review H2): it compared the plain prescriptions, so a rewrite that put Firm back on a
     * pressure somebody had lowered read "6 -> 7 sets" and said nothing of the pressure.
     */
    public static Mint.Rx asCommanded(Model model, Mint.Rx rx, boolean ownKpa) {
        if (model == null || rx == null) return rx;
        return new Mint.Rx(rx.track, rx.level, rx.sets, rx.holdSec, rx.restSec,
                           commandedKpa(model, rx, ownKpa), rx.fatigue, rx.netTargetMin,
                           rx.powerPct, rx.offKpa);
    }

    /**
     * WHAT A ROUTINE BUILT FROM `rx` RUNS, in the prescription's own terms: its holds, their
     * length - the hybrid's five-minute holds where the hybrid shapes it (D11), not the
     * interval prescription it replaces. What a card or a row describing the routine counts
     * (the owner's decision, 0.10: "6×5min holds", never the "13×2min" it does not run). The
     * pressure is still the prescription's; {@link #commandedKpa} states the figure.
     */
    public static Mint.Rx runsAs(Model model, Mint.Rx rx) {
        return runsAs(model, rx, false);
    }

    /** The same, for a prescription whose set count is the person's ({@link Day#ownSets}): a
     *  hybrid of theirs runs their (lower) count of holds. */
    public static Mint.Rx runsAs(Model model, Mint.Rx rx, boolean ownSets) {
        if (rx == null || model == null) return rx;
        return hybridApplies(model, rx) ? hybridRx(model, rx, ownSets) : rx;
    }

    /**
     * THE PRESSURE THE PLAN ASKS FOR BEFORE THE DAY'S CUT: the prescription with the Program's
     * Firm or Gentle bias on it. What "Adjust first..." starts from and shows (review M1): the
     * sheet used to start from the plain prescription while the card showed the Gentle floor,
     * so stepping the plain figure down one - still above the floor - made the pressure the
     * person's, and the pump pulled harder than the plan's own Gentle routine would have. The
     * day's cut is not in it: the cut comes off a pressure the person sets exactly as it comes
     * off the plan's.
     */
    public static int biasedKpa(Model model, Mint.Rx rx) {
        if (model == null || rx == null) return 0;
        Mint.Rx c = commanded(model, rx, new Day(System.currentTimeMillis(), 0.0, false, -1));
        return c == null ? rx.pressureKpa : c.pressureKpa;
    }

    /* ---------------------------------------------------- "Adjust first..." (review M1, M4) */

    /** The most sets "Adjust first..." has ever offered, off the hybrid. */
    public static final int ADJUST_MAX_SETS = 40;

    /** The sets "Adjust first..." starts from: the holds the plan's own routine runs - a
     *  hybrid's five-minute holds, not the interval prescription it replaces. */
    public static int adjustStartSets(Model model, Mint.Rx rx) {
        Mint.Rx runs = runsAs(model, rx);
        return runs == null ? 0 : runs.sets;
    }

    /** The most sets "Adjust first..." offers. A hybrid never runs more holds than the
     *  guidance gives its level (the owner's ruling); anything else keeps the old bound. */
    public static int adjustMaxSets(Model model, Mint.Rx rx) {
        if (model != null && hybridApplies(model, rx)) return adjustStartSets(model, rx);
        return ADJUST_MAX_SETS;
    }

    /** The pressure "Adjust first..." starts from and shows: the plan's, biased (#biasedKpa). */
    public static int adjustStartKpa(Model model, Mint.Rx rx) {
        return biasedKpa(model, rx);
    }

    /**
     * THE MOST "ADJUST FIRST..." OFFERS, whole kPa (0.10): the track's effective top - its
     * usual top moved by the person's offset (Scale) - and never past the hard limits: the
     * ceiling, "Most you will go to", 15 inHg and a new person's first-month 6 inHg. Never
     * under the figure the sheet starts from, so the plan's own figure is always reachable.
     * Asked at the engine's month (TrainerTab#monthIndexNow) - the sheet used the months since
     * enrolment, which left out the months answered at setup (bug d).
     */
    public static int adjustMaxKpa(Model model, Mint.Rx rx, long nowMs) {
        if (model == null || rx == null) return 1;
        int month = TrainerTab.monthIndexNow(model, nowMs);
        int hard = Scale.hardKpa(model, rx.track, month);
        int top = Scale.effectiveTopWholeKpa(model, rx.track, rx.level, month);
        return Math.max(1, Math.min(hard, Math.max(top, adjustStartKpa(model, rx))));
    }

    /**
     * ONE STEP OF "ADJUST FIRST..."'S PRESSURE: from `cur` to `want` (the unit's step), or `cur`
     * itself when the step is refused - a "+" past #adjustMaxKpa. A "+" NEVER LOWERS (bug c's
     * kind): a figure already above the most is left where it is rather than pulled down to it
     * by the button that says more. A "-" goes no lower than 1 kPa.
     */
    public static int adjustBumpKpa(Model model, Mint.Rx rx, int cur, int want, long nowMs) {
        if (want > cur) return want > adjustMaxKpa(model, rx, nowMs) ? cur : want;
        return Math.max(1, want);
    }

    /** Does an "Adjust first..." save at `kpa` make the pressure the person's? Only when it was
     *  moved off the figure the sheet started from; an unmoved one stays the plan's, biased. */
    public static boolean adjustOwnKpa(Model model, Mint.Rx rx, int kpa) {
        return rx != null && kpa != adjustStartKpa(model, rx);
    }

    /** Is an "Adjust first..." save of (`sets`, `kpa`) an adjustment at all? */
    public static boolean adjustMoved(Model model, Mint.Rx rx, int sets, int kpa) {
        return rx != null && (sets != adjustStartSets(model, rx) || adjustOwnKpa(model, rx, kpa));
    }

    /**
     * THE PRESCRIPTION AN "ADJUST FIRST..." SAVE OF (`sets`, `kpa`) BUILDS, both in the sheet's
     * own terms (#adjustStartSets, #adjustStartKpa). `rx` itself when neither moved. A moved
     * pressure is the person's and is built unbiased (the owner's ruling); an unmoved one is the
     * plan's own and takes the bias as ever. A hybrid's sets are its five-minute holds, never
     * more than the guidance's - built with the count the person's (Day#ownSets).
     */
    public static Mint.Rx adjustedRx(Model model, Mint.Rx rx, int sets, int kpa) {
        if (rx == null || !adjustMoved(model, rx, sets, kpa)) return rx;
        int n = Math.max(1, Math.min(sets, adjustMaxSets(model, rx)));
        return Mint.adjusted(rx, n, adjustOwnKpa(model, rx, kpa) ? kpa : rx.pressureKpa);
    }

    /* ------------------------------------------------------- "· the rest" (review H1, L1) */

    /**
     * WHAT IS LEFT OF A STOPPED SESSION, as a prescription: `cycles` holds of what the track's
     * prescription builds today - with the saved routine's "Adjust first..." sets and pressure
     * where it still carries them (`kept`, SavedMint#carried; review L1: the rest of a routine
     * the person had set lighter ran at the plan's biased figure). No fatigue block: that was
     * done first. Built with the count its own (#remainderDay), so a hybrid runs that many of
     * its five-minute holds and never more than the guidance's (review H1: it built the whole
     * hybrid again - six five-minute holds for "2 cycles left").
     */
    public static Mint.Rx remainderRx(Mint.Rx todayRx, Mint.Adjust kept, int cycles) {
        if (todayRx == null || cycles < 1) return null;
        Mint.Rx rx = kept == null ? todayRx : kept.on(todayRx);
        return new Mint.Rx(rx.track, rx.level, cycles, rx.holdSec, rx.restSec, rx.pressureKpa,
                           false, cycles * (rx.holdSec / 60.0), rx.powerPct, rx.offKpa);
    }

    /** The day a remainder is built for: today; its pressure the person's where `kept` says
     *  so; its count always its own. */
    public static Day remainderDay(Model model, Mint.Adjust kept) {
        return Day.today(model).ownPressure(kept != null && kept.ownKpa).ownSetCount(true);
    }

    /**
     * BUILDS "· THE REST" OF `src` - OR REFUSES. The remainder is built in a scratch copy first
     * and weighed against `src`: what is left of a session never pulls harder than the session
     * did, in the blocks a day's cut governs (SavedMint#governedPeakKpa). Where it would - the
     * plan has moved on since `src` was saved, or `src` is one the person made lighter by hand -
     * nothing is built, `model` is untouched, and null is returned for the caller to say so.
     * `warm` false builds it with no warm-up; the setting itself is left as it was.
     */
    public static String remainder(Model model, Model.Routine src, Mint.Rx todayRx,
                                   Mint.Adjust kept, int cycles, boolean warm) {
        Mint.Rx rest = remainderRx(todayRx, kept, cycles);
        if (model == null || src == null || rest == null) return null;
        Model scratch = SavedMint.scratchOf(model);
        Model.Routine trial = scratch.routine(remainderInto(scratch, rest, kept, warm));
        if (trial == null || governedWorkPeakKpa(scratch, trial)
                             > governedWorkPeakKpa(model, src))
            return null;
        return remainderInto(model, rest, kept, warm);
    }

    /**
     * SavedMint#governedPeakKpa without the warm-up: the deepest pull of the WORK the day's cut
     * governs. t10 R-01: P2's warm-up climbs to the work, so a session whose work the person
     * lowered by hand still opened at the plan's figure - and weighed with its warm-up, the rest
     * of it at the plan's figure read as no heavier. What is left is weighed against the work.
     */
    static int governedWorkPeakKpa(Model m, Model.Routine r) {
        if (m == null || r == null) return 0;
        boolean pulls = Say.isTraction(r);
        java.util.List<Model.Preset> plan = m.plan(r);
        int pk = 0;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            Model.Stage st = p.stageIdx >= 0 && p.stageIdx < r.stages.size()
                ? r.stages.get(p.stageIdx) : null;
            if (st == null || st.rest || p.rest || st.colour == Model.STAGE_WARM) continue;
            if (pulls && st.traction) continue;
            if (p.up > pk) pk = p.up;
        }
        return pk;
    }

    private static String remainderInto(Model model, Mint.Rx rest, Mint.Adjust kept,
                                        boolean warm) {
        // No warm-up on THIS build only (t10: the day says so - P2 does not read the warm-up
        // length the setting used to be zeroed for); the settings are untouched by the choice.
        Day day = remainderDay(model, kept);
        return routineFromRx(model, rest, 0, 0, day.sameDay(false, false, !warm));
    }

    /* ------------------------------------------- t10: the run a plan's mint becomes today */

    /** A routine built for ONE RUN, in a scratch copy of the model (#dayRun): its sets are the
     *  scratch's own, for RunShape to copy into the run - nothing of it is in the library. */
    public static final class DayRun {
        public final Model scratch;
        public final Model.Routine routine;
        /** Parity run 3, OPEN-1: the deepest the plan's own routine runs once its warm-up - and
         *  the carry ramp after it - is not there: its work at the prescription. What a day's
         *  build may reach (RunShape#build), where the saved routine's carry stops short. */
        public final int planPeakKpa;
        DayRun(Model scratch, Model.Routine routine, int planPeakKpa) {
            this.scratch = scratch; this.routine = routine; this.planPeakKpa = planPeakKpa;
        }
    }

    /**
     * t10 R-05/R-06/R-07 - WHAT A TRACK'S SAVED ROUTINE RUNS TODAY, built by this builder for
     * the day (Day#bothTracks, #girthAfterLength, #skipWarm): no swap and no expansion after
     * the pulls on a day of both tracks; girth after length without its fatigue block, led in
     * as the person chose; no warm-up - and no carry ramp after it - within the half hour of
     * the other track's end.
     *
     * ONLY THE PLAN'S OWN ROUTINE, AS THE PLAN BUILT IT. `saved` must be the track's current
     * mint (or a half of its split), and rebuilding it from its signature in a scratch copy
     * of the model - in the shape, offset, load, cut and adjustment it was minted with, as
     * SavedMint compares it - must give back exactly what is saved. A routine the person has
     * edited is theirs: null, and the run keeps what is saved (RunShape takes out only what
     * the day takes out - the warm-up, a day of both tracks' coda). Null too when the day asks
     * nothing, or anything cannot be answered. Nothing in `model` is touched.
     */
    public static DayRun dayRun(Model model, Model.Routine saved, boolean bothTracks,
                                boolean girthAfterLength, boolean skipWarm) {
        if (model == null || saved == null || saved.id == null) return null;
        if (!bothTracks && !girthAfterLength && !skipWarm) return null;
        Model.TrainerTrackState st = saved.trainerTrack == Plan.TRACK_LENGTH
            ? model.trainerLength
            : (TrainerTab.isGirthTrack(saved.trainerTrack) ? model.trainerGirth : null);
        if (st == null) return null;
        boolean split = st.lastMintId2 != null && st.lastMintId2.length() > 0;
        int part, ofParts;
        if (saved.id.equals(st.lastMintId)) { part = split ? 1 : 0; ofParts = split ? 2 : 0; }
        else if (split && saved.id.equals(st.lastMintId2)) { part = 2; ofParts = 2; }
        else return null;
        String sig = st.lastMintSig;
        Mint.Rx rx = SavedMint.builtRx(sig);
        if (rx == null) return null;
        try {
            Model scratch = SavedMint.scratchOf(model);
            SavedMint.applyShapeTag(scratch, sig, rx.track, rx.level);
            SavedMint.applyOffsetTag(scratch, sig, rx.track);
            SavedMint.applyTractionTag(scratch, sig);
            long at = st.lastMintMs > 0L ? st.lastMintMs : System.currentTimeMillis();
            Day day = new Day(at, Deload.mintCutKpa(model, sig), Deload.stepHg(sig) > 0.0,
                              SavedMint.focusOf(sig)).adjusted(Mint.adjustOf(sig));
            Model.Routine plain = scratch.routine(routineFromRx(scratch, rx, part, ofParts, day));
            if (plain == null || !SavedMint.workPrint(scratch, plain)
                    .equals(SavedMint.workPrint(model, saved))) return null;
            Model.Routine run = scratch.routine(routineFromRx(scratch, rx, part, ofParts,
                day.sameDay(bothTracks, girthAfterLength, skipWarm)));
            if (run == null) return null;
            // OPEN-1: the plan's own work, no warm-up and so no carry - its prescription.
            Model.Routine bare = skipWarm ? run : scratch.routine(routineFromRx(scratch, rx,
                part, ofParts, day.sameDay(false, false, true)));
            int planPeak = Math.max(scratch.peak(plain), bare == null ? 0 : scratch.peak(bare));
            return new DayRun(scratch, run, planPeak);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * HOW LONG `saved` RUNS ON SUCH A DAY, seconds (Model#routineSec): #dayRun's build where it
     * answers, else the routine as saved. What a day of both tracks is planned to take
     * (DayLength#parts). Remembered per input state (BuildCache): asked on every Trainer draw.
     */
    public static long dayRunSec(Model model, Model.Routine saved, boolean bothTracks,
                                 boolean girthAfterLength, boolean skipWarm, long nowMs) {
        if (model == null || saved == null) return 0L;
        long asSaved = Math.max(0L, model.routineSec(saved));
        if (!bothTracks && !girthAfterLength && !skipWarm) return asSaved;
        String key = "DR|" + BuildCache.stamp(model, saved.trainerTrack, saved.trainerLevel,
            nowMs) + "|" + saved.id + "|" + bothTracks + girthAfterLength + skipWarm + "|"
            + asSaved;
        Object got = BuildCache.get(key);
        if (got != null) return ((Long) got).longValue();
        DayRun run = dayRun(model, saved, bothTracks, girthAfterLength, skipWarm);
        long sec = run == null ? asSaved : Math.max(0L, run.scratch.routineSec(run.routine));
        BuildCache.put(key, Long.valueOf(sec));
        return sec;
    }

    /** The day's cut and speed on a prescription, no bias. */
    static Mint.Rx dayOf(Mint.Rx rx, Day day) {
        if (rx == null) return null;
        /* A FEEDER IS NOT CUT HERE ON A GENTLE DAY. Its pressure already IS its usual share of
         * that day's REDUCED main pressure (TrainerTab#feederInputs, Deload#mainKpaOn - the
         * owner's ruling), so taking the taper's cut off it again would run it far under the day
         * every other session shares: 2 kPa rather than 15, on a first day back from 10 hg. On
         * any other day the cylinder's own cut still comes off a feeder, exactly as before. */
        if (!(day.gentle && rx.track == Plan.TRACK_FEEDER))
            rx = Mint.reduce(rx, day.cutKpa);
        // A GENTLE DAY PULLS SLOWER AS WELL AS LOWER. Same condition as the reduction itself,
        // asked of the Model (Day#at) so the card and the command cannot drift apart.
        if (day.gentle) rx = Mint.gentle(rx);
        return rx;
    }

    /**
     * D3 - GIVES `extra` WHOLE CYCLES TO THE WORK RAMPS as more of their own steps: the last
     * ramp first, each while it still has a table slot for a whole-cycle step and room under
     * the hour, then the one before it. What none has a slot for goes on the last ramp all
     * the same - a second set at the ramp's own top pressure is the thing this replaces, and
     * a ramp longer than the table is then stepped in whole cycles by #topStep (audit D6). Returns
     * the cycles no ramp could take (only when there is no ramp at all, or none under the
     * hour), which the caller tops up as before.
     */
    static int foldIntoRamps(java.util.List<Model.Set> ramps, int extra, int cycleSec,
                             int ceilKpa) {
        int left = extra;
        int hourCycles = 3600 / Math.max(1, cycleSec);
        for (int pass = 0; pass < 2 && left > 0; pass++)
            for (int i = ramps.size() - 1; i >= 0 && left > 0; i--) {
                Model.Set s = ramps.get(i);
                int have = s.dur / cycleSec;
                int room = hourCycles - have;
                if (pass == 0) room = Math.min(room, Proto.SLOTS - have);
                else if (i != ramps.size() - 1) continue;   // the leftover: the last ramp only
                if (room <= 0) continue;
                int k = Math.min(room, left);
                s.dur = (have + k) * cycleSec;
                s.steps = have + k;
                s.clamp(ceilKpa);                           // steps back inside the table
                left -= k;
            }
        return left;
    }

    /* ============================================================ the ramps (0.10) */

    /** A Ramped session's climbs, as the day's settings give them: the working pressure, the
     *  first block's climb and a later block's short one (the holds under the work; the
     *  arrival at it is the block's first working hold), and whether the climb counts. */
    static final class RampPlan {
        final int work;
        final int[] first, after;
        final boolean counted;
        RampPlan(int work, int[] first, int[] after, boolean counted) {
            this.work = work; this.first = first; this.after = after; this.counted = counted;
        }
        int[] climbFor(int block) { return block == 0 ? first : after; }
    }

    /**
     * THE CLIMBS A RAMPED SESSION TAKES TODAY at working pressure `workKpa`, or null where it
     * has none and plays as fixed holds: a lighter day (a taper step or a cylinder's cut) the
     * person runs flat (Model#rampLighterDays off), blocks of single holds (traditional and
     * the hybrid - one hold cannot climb inside itself, as before), or a start at the work.
     */
    static RampPlan rampPlan(Model model, int workKpa, int[] blocks, Day day) {
        boolean lighter = day != null && (day.cutKpa > 0.0 || day.gentle);
        if (lighter && !model.rampLighterDays) return null;
        int most = 0;
        for (int i = 0; i < blocks.length; i++) most = Math.max(most, blocks[i]);
        if (most < 2) return null;
        int[] first = Ramp.firstClimb(Ramp.startKpa(workKpa, model.rampStartPct), workKpa,
                                      Ramp.stepKpa(model.rampStepHg));
        if (first.length == 0) return null;
        return new RampPlan(workKpa, first, Ramp.shortClimb(first, model.rampShortSteps),
                            model.rampCountClimb);
    }

    /**
     * LAYS A RAMPED SESSION OUT, block by block (0.10, the owner's decisions): each block's
     * climb - the first block's full, a later one's short - as ramp sets of one whole cycle a
     * step (a climb longer than the device's table as consecutive ramps, Ramp#segments), then
     * the block's holds at the working pressure as fixed sets (an hour at most each, as every
     * work chunk). The climb's final step is under the work, so the working hold after it is
     * never a repeat of it (D3).
     *
     * COUNTED (the default), the climb is part of the block's prescribed holds - a block of
     * five with a climb of two runs two climbing holds and three working ones - and at least
     * one hold of every block is at the work: a climb longer than its block lengthens the block
     * rather than end it under the work. NOT COUNTED, the block's prescribed holds are all at
     * the work and the climb comes in front of them, a stage of its own (out of net).
     *
     * Fills `blockIds` (and `climbIds` for an uncounted climb), `workChunks` with the working
     * sets and `climbSets` with the climb's. Returns the effective seconds the make-up weighs:
     * the working sets, and the counted climb's holds at or above `countFromKpa` (the line net
     * counts from) pro-rata, as ever (Mint#effectiveSec) - so a climb hold that counts owes the
     * little it sits under the prescription, as it always did.
     *
     * A CLIMBING HOLD UNDER THE LINE IS NOT MADE UP (the owner's decision, 0.10). At 80 % of the
     * work a climb can start under the level's line; net counts such a hold as nothing, and it
     * weighs a whole hold here, so it owes nothing and the session stays about as long as fixed
     * holds. It is left out of the target, which is what the counting holds deliver, and the
     * level credits it back (Routine#climbUnderLineMin, TrainerTab#creditedNet), so the gates
     * still pass. (Made up in full, it ran Level 2's bottom at 43:20 against 35:00 fixed.)
     */
    private static double layoutRamped(Model model, RampPlan plan, Mint.SetSpec w, int[] blocks,
                                       int cycleSec, int perChunkMax, int prescribedRef,
                                       double countFromKpa, String holdName,
                                       java.util.List<java.util.List<String>> blockIds,
                                       java.util.List<java.util.List<String>> climbIds,
                                       java.util.List<Model.Set> workChunks,
                                       java.util.List<Model.Set> climbSets) {
        double eff = 0.0;
        for (int b = 0; b < blocks.length; b++) {
            int[] climb = plan.climbFor(b);
            String suffix = b == 0 ? "" : " " + (b + 1);
            java.util.List<String> into = plan.counted ? blockIds.get(b) : climbIds.get(b);
            java.util.List<int[]> segs = Ramp.segments(climb);
            for (int g = 0; g < segs.size(); g++) {
                int from = segs.get(g)[0], to = segs.get(g)[1], n = to - from + 1;
                int a = climb[from], z = climb[to];
                String nm = "Climb" + suffix + (segs.size() > 1 ? " \u00b7 " + (g + 1) : "");
                Model.Set c = n >= 2
                    ? Model.Set.ramp(model.newSetId(), nm, a, RunEdit.dropKept(w.lo, a, false), w.uh, w.lh,
                        w.sp, z, RunEdit.dropKept(w.lo, z, false), w.uh, w.lh, w.sp, n, n * cycleSec)
                    : Model.Set.fixed(model.newSetId(), nm, a, RunEdit.dropKept(w.lo, a, false), w.uh,
                        w.lh, w.sp, cycleSec);
                c.fromPlan = true;
                c.clamp(model.ceilKpa);
                model.sets.add(c);
                into.add(c.id);
                climbSets.add(c);
                if (plan.counted)
                    for (int i = from; i <= to; i++)
                        eff += climb[i] >= countFromKpa
                            ? Mint.effectiveSec(cycleSec, climb[i], climb[i], false,
                                                prescribedRef)
                            : cycleSec;      // counts nothing, owes nothing: not made up
            }
            int atWork = plan.counted ? Math.max(1, blocks[b] - climb.length) : blocks[b];
            int left = atWork * cycleSec, part = 0;
            while (left > 0) {
                int take = left > perChunkMax ? perChunkMax : left;
                String nm = holdName + suffix + (part > 0 ? " \u00b7 " + (part + 1) : "");
                Model.Set h = Model.Set.fixed(model.newSetId(), nm, plan.work, RunEdit.dropKept(w.lo, plan.work, false), w.uh, w.lh,
                                              w.sp, take);
                h.fromPlan = true;
                h.clamp(model.ceilKpa);
                model.sets.add(h);
                blockIds.get(b).add(h.id);
                workChunks.add(h);
                eff += Mint.effectiveSec(take, h.up, h.up, false, prescribedRef);
                left -= take;
                part++;
            }
        }
        return eff;
    }

    /**
     * A RAMPED SESSION'S MAKE-UP IS MORE HOLDS AT THE WORK, on its last block's last working
     * set (the ramp's own "top step", D3/D6: never a second set at a top already reached) -
     * that set lengthened while it stays under the hour, then a working set after it in the
     * same block for what is left. Returns what could not be placed: nothing.
     */
    private static int foldIntoWork(Model model, java.util.List<Model.Set> workChunks,
                                    java.util.List<java.util.List<String>> blockIds, int extra,
                                    int cycleSec, int perChunkMax) {
        if (workChunks.isEmpty() || extra <= 0) return extra;
        Model.Set last = workChunks.get(workChunks.size() - 1);
        java.util.List<String> block = null;
        for (int b = blockIds.size() - 1; b >= 0 && block == null; b--)
            if (blockIds.get(b).contains(last.id)) block = blockIds.get(b);
        if (block == null) return extra;
        int room = Math.max(0, (perChunkMax - last.dur) / cycleSec);
        int take = Math.min(room, extra);
        last.dur += take * cycleSec;
        last.clamp(model.ceilKpa);
        extra -= take;
        int part = 2;
        while (extra > 0) {
            int n = Math.min(extra, Math.max(1, perChunkMax / cycleSec));
            Model.Set h = Model.Set.fixed(model.newSetId(), last.name + " \u00b7 " + part,
                last.up, last.lo, last.uh, last.lh, last.sp, n * cycleSec);
            h.fromPlan = true;
            h.clamp(model.ceilKpa);
            model.sets.add(h);
            block.add(block.indexOf(last.id) + 1, h.id);
            workChunks.add(h);
            last = h;
            extra -= n;
            part++;
        }
        return 0;
    }

    /** The line routine `r`'s net counts from on `day`: Model#scoringFloorKpa - the level's
     *  floor on the routine's scale, moved down by the day's cut - less the counting tolerance,
     *  the line PlannedTime and the run's own net count from. Asked of the day the build is
     *  for, never of the clock, so a rebuild for another day (SavedMint) is the same build.
     *  Minus infinity for a routine nothing scores (every hold counts). */
    static double countFromKpa(Model model, Model.Routine r, Day day) {
        double floor = model.scoringFloorKpa(r, day.atMs);
        if (Double.isNaN(floor)) return Double.NEGATIVE_INFINITY;
        int level = r.trainerLevel > 0 ? r.trainerLevel : 0;
        floor = Model.scaledLevelFloorKpa(r, level) - day.cutKpa;
        return floor - model.tupCountTolKpa(floor);
    }

    /** How many of a climb set's holds net counts: those at or above the line routine `r` is
     *  scored from on `day` (#countFromKpa). */
    static int countedClimbHolds(Model model, Model.Routine r, Model.Set s, Day day) {
        java.util.List<Model.Preset> lad = s.ladder();
        double line = countFromKpa(model, r, day);
        int n = 0;
        int cyc = Math.max(1, s.cycle());
        for (int i = 0; i < lad.size(); i++) {
            Model.Preset p = lad.get(i);
            if (p.up >= line) n += (int) Math.round(p.durMs / 1000.0 / cyc);
        }
        return n;
    }

    /**
     * D6 - A RAMP LONGER THAN THE TABLE ENDS ON ONE LONG STEP. The device's table holds
     * {@link Proto#SLOTS} steps and {@link Model.Set#ladder} gives every step of a ramp an
     * equal share of its time, so a ramp of more cycles than steps cut a hold short on every
     * step. The climb now takes one whole cycle per step up to the step below the top, and the
     * top step - a fixed set at the ramp's own end values - holds every cycle left over: the
     * ramp's last step, holding the leftover whole cycles (the owner's decision), rather than a
     * second set at a top the ramp had already reached (audit D3).
     *
     * `ramp` is trimmed in place; the top step is returned, already in the model's sets, for
     * the caller to place straight after it - or null when the ramp fits the table, in which
     * case it is left at one cycle per step. The points of the climb are the ones the whole
     * ramp would have walked, so the figures the person sees are the same climb, stepped true.
     */
    static Model.Set topStep(Model model, Model.Set ramp, int cycleSec) {
        if (ramp == null || !ramp.ramp || cycleSec <= 0) return null;
        int cycles = ramp.dur / cycleSec;
        if (cycles <= Proto.SLOTS) return null;
        int start = ramp.up, top = ramp.up2;
        // As many one-cycle steps as the table leaves room for beside the top step - and no
        // more than there are whole kPa to climb, so no two points of the climb are one figure
        // and its last is always under the top.
        int climb = Math.max(1, Math.min(Proto.SLOTS - 1, top - start));
        Model.Set t = Model.Set.fixed(model.newSetId(), ramp.name + " \u00b7 top step",
            top, ramp.lo2, ramp.uh2, ramp.lh2, ramp.sp2, (cycles - climb) * cycleSec);
        t.fromPlan = ramp.fromPlan;
        t.clamp(model.ceilKpa);
        model.sets.add(t);
        if (climb >= 2) {
            ramp.up2 = (int) Math.round(start + (top - start) * (climb - 1) / (double) climb);
            ramp.lo2 = RunEdit.dropKept(ramp.lo2, ramp.up2, false);
            ramp.sp2 = (int) Math.round(ramp.sp + (ramp.sp2 - ramp.sp) * (climb - 1)
                                        / (double) climb);
            ramp.steps = climb;
        } else {
            // One kPa to climb: the step below the top is the start itself, one cycle of it.
            ramp.ramp = false;
            ramp.up2 = ramp.up; ramp.lo2 = ramp.lo; ramp.sp2 = ramp.sp;
            ramp.uh2 = ramp.uh; ramp.lh2 = ramp.lh;
        }
        ramp.dur = climb * cycleSec;
        ramp.clamp(model.ceilKpa);
        return t;
    }

    /** The first pull the routine's commanding stages ask for - where its work starts. The
     *  warm-up ends under it (D5). Integer.MAX_VALUE for a routine with none. */
    static int firstPullKpa(Model model, Model.Routine r) {
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest || st.manual || st.colour == Model.STAGE_WARM) continue;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = model.set(st.setIds.get(j));
                if (s != null && !s.rest) return s.up;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** The hybrid's traditional holds at L3 and at L4 - the guidance's own counts. */
    public static final int HYBRID_HOLDS_L3 = Plan.HYBRID_L3_HOLDS, HYBRID_HOLDS_L4 = Plan.HYBRID_L4_HOLDS;

    /** Does the hybrid shape this interval prescription? From L3, on the interval track.
     *  (A traditional prescription already IS the hybrid's work - five-minute holds after the
     *  fatigue block - so there is no interval work in it to replace.) */
    static boolean hybridApplies(Model model, Mint.Rx rx) {
        return model.trainerGirthHybrid && rx != null && rx.level >= Plan.L3
            && rx.track == Plan.TRACK_GIRTH_INTERVAL;
    }

    /**
     * D11 - THE HYBRID'S PRESCRIPTION: the intervals replaced by five-minute holds at the same
     * pressure, as many as the guidance gives the level (six at L3, eight at L4), a rest
     * between each. The fatigue block, the pressure and the rest are the prescription's own; a
     * session asked for no net (a reduced day) still asks for none.
     */
    static Mint.Rx hybridRx(Mint.Rx rx) {
        return hybridRx(null, rx, false);
    }

    /** The same, where the set count is the person's or what is left (`ownSets`,
     *  Day#ownSets): that many holds where it is lower than the guidance's count, never more
     *  (review M4, H1). The plan's own prescription always gets the guidance's count. */
    static Mint.Rx hybridRx(Mint.Rx rx, boolean ownSets) {
        return hybridRx(null, rx, ownSets);
    }

    /**
     * ...WITH THE HOLDS YIELD KEPT AND UNDER THE TIME CAP (the owner's decisions, 2026-10-01).
     * R-25: the low-yield step and the no-readings fallback move the hybrid's OWN holds
     * (TrainerTrackState#hybridYield: 6 at L3, 8 at L4, up to 8, Plan#hybridHolds), never the
     * hidden interval count - the guidance's count was fixed here, so yield did nothing. R-27:
     * never more five-minute holds than the level's time cap lets run with the fatigue block
     * (5 at L3, 7 at L4; the holds past it are the plan's pressure, Plan#r2Step). `model`
     * null: the guidance's count under the standard block.
     */
    static Mint.Rx hybridRx(Model model, Mint.Rx rx, boolean ownSets) {
        int n = hybridHoldsRun(model, rx.track, rx.level);
        if (ownSets) n = Math.max(1, Math.min(n, rx.sets));
        int hold = Mint.HOLD_TRADITIONAL_SEC;
        double net = rx.netTargetMin <= 0.0 ? 0.0 : n * (hold / 60.0);
        return new Mint.Rx(rx.track, rx.level, n, hold, rx.restSec, rx.pressureKpa,
                           rx.fatigue, net, rx.powerPct, rx.offKpa);
    }

    /**
     * THE HYBRID'S FIVE-MINUTE HOLDS THE PLAN'S BUILD RUNS at `level`: the kept holds
     * (Plan#hybridHolds) under the time cap with the person's fatigue block (R-27; not for a
     * routine minted before t10, Model#legacyT10). One count for the builder and for the
     * signature (Model#rxShapeTag), so a hybrid step that changes what runs rewrites the
     * routine, and one that does not, does not (t10 fix, review B F5). `model` null: the
     * guidance's count under the standard block.
     */
    static int hybridHoldsRun(Model model, int track, int level) {
        int kept = model == null || model.trainerGirth == null ? 0 : model.trainerGirth.hybridYield;
        int n = Plan.hybridHolds(level, kept);
        int fat = model == null ? Mint.standardFatSec(level) : Mint.r2FatSec(model, track, level);
        if (model == null || !model.legacyT10)
            n = Math.min(n, Plan.r2MaxHolds(level, Mint.HOLD_TRADITIONAL_SEC, fat));
        return n;
    }

    /**
     * D8 - DOES THIS WORK SHAPE CHANGE WHAT TODAY'S BUILD COMMANDS? The Program's picker
     * offers four shapes whatever the prescription, and at one block (L1, length, a hybrid of
     * single holds), under a Gentle bias, or on a traction coda's pyramid some of them build
     * exactly the fixed holds - the choice was taken and silently did nothing. Asked of the
     * builder itself, in a scratch copy: the shape's build against the fixed build, preset by
     * preset. Nothing in `model` is touched. True for fixed, and whenever it cannot tell.
     */
    public static boolean workShapeActs(Model model, Mint.Rx rx, int shape) {
        if (model == null || rx == null || shape == Model.Program.WORK_FIXED) return true;
        if (model.programFor(rx.track) == null) return true;
        // Asked for every shape on every draw of the Program pickers: remembered (BuildCache).
        long now = System.currentTimeMillis();
        String key = "W|" + BuildCache.stamp(model, rx.track, rx.level, now) + "|"
            + BuildCache.rx(rx) + "|" + BuildCache.day(model, Day.at(model, now)) + "|" + shape;
        Object got = BuildCache.get(key);
        if (got != null) return ((Boolean) got).booleanValue();
        boolean acts = workShapeActsBuilt(model, rx, shape);
        BuildCache.put(key, Boolean.valueOf(acts));
        return acts;
    }

    private static boolean workShapeActsBuilt(Model model, Mint.Rx rx, int shape) {
        try {
            Model s = SavedMint.scratchOf(model);
            Model.Program p = s.programFor(rx.track);
            p.work = Model.Program.WORK_FIXED;
            String fixed = SavedMint.workPrint(s, s.routine(routineFromRx(s, rx)));
            p.work = shape;
            String shaped = SavedMint.workPrint(s, s.routine(routineFromRx(s, rx)));
            return !fixed.equals(shaped);
        } catch (Exception e) {
            return true;
        }
    }

    public static Mint.Rx reshapeToHold(Model model, Mint.Rx rx) {
        if (rx == null) return null;
        model.clampRxShape();
        int want = Mint.holdSecWanted(rx.track, rx.level, model.rxHoldSec);
        if (want == rx.holdSec) return rx;
        int sets = Mint.setsForNet(rx.netTargetMin, want);
        return new Mint.Rx(rx.track, rx.level, sets, want, rx.restSec, rx.pressureKpa,
                           rx.fatigue, rx.netTargetMin, Mint.POWER_PCT, rx.offKpa);
    }


    public static Model.Stage retentionStage(Model model, Mint.Rx rx) {
        if (rx == null || rx.track == Plan.TRACK_FEEDER) return null;
        model.clampRxShape();
        if (!model.rxRetention) return null;
        int kpa = (int) Math.round(model.rxRetentionKpa);
        // Never above what the work itself ran at, and never above the ceiling: a
        // "retention" deeper than the session would be a second working block wearing
        // another name.
        kpa = Math.min(kpa, Math.min(rx.pressureKpa, model.ceilKpa));
        if (kpa < 1) return null;
        int sec = model.rxRetentionMin * 60;
        Model.Set h = new Model.Set();
        h.id = model.newSetId();
        h.name = "Retention at " + Model.Fmt.p(kpa);
        h.up = kpa;
        h.lo = Math.max(0, kpa - 1);
        h.uh = sec;          // one hold for the whole stage - see the note above
        h.lh = 0;
        h.sp = 55;
        h.dur = sec;
        h.fromPlan = true;
        h.clamp(model.ceilKpa);
        model.sets.add(h);
        Model.Stage st = Model.Stage.of("Retention", Model.STAGE_COOL,
                                        new String[]{ h.id });
        st.retention = true;
        return st;
    }


    /**
     * THE WARM-UP'S GENERATED NAME, in one place — because something has to be able to ask
     * "is this still the name I would have generated?" later.
     *
     * A name built from a value goes stale the moment the value is edited, and this one did:
     * a warm-up minted at 4.1 inHg and then edited to 5.0 kept a header reading "Prime at
     * -4.1 inHg" over its own "pull to -5.0 inHg" line. {@link Model.Set#renameIfGenerated}
     * is what fixes that, and it can only do so by comparing against this.
     */
    public static String primeName(int primeKpa) {
        return "Prime at " + Model.Fmt.p(primeKpa);
    }

    public static void manualWarmUp(Model model, Model.Routine r) {
        if (r == null || !model.rxManualWarm) return;
        model.clampRxShape();
        if (model.rxWarmMin <= 0) return;
        Model.Set adhoc = model.set(Manual.ID);
        int top = adhoc == null ? model.ceilKpa
                : Math.min(adhoc.ramp ? Math.max(adhoc.up, adhoc.up2) : adhoc.up,
                           model.ceilKpa);
        int prime = Math.min((int) Math.round(model.rxPrimeKpa), Math.max(1, top));
        int sec = model.rxWarmMin * 60;
        Model.Set p = new Model.Set();
        p.id = model.newSetId();
        p.name = primeName(prime);
        p.up = prime; p.lo = Math.max(0, prime - 1);
        p.uh = sec; p.lh = 0; p.sp = 50; p.dur = sec;
        p.clamp(model.ceilKpa);
        model.sets.add(p);
        r.stages.add(0, Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[]{ p.id }));
    }


    /** The warm-up for `rx` where the work it precedes is not known. The builders ask the
     *  longer forms. */
    public static Model.Stage warmupStage(Model model, Mint.Rx rx) {
        return warmupStage(model, rx, Integer.MAX_VALUE);
    }

    /** The warm-up for work whose first pull is `firstPullKpa`, a length track's ending at S18's
     *  80 % (#warmupStage(Model, Mint.Rx, int, boolean)). */
    public static Model.Stage warmupStage(Model model, Mint.Rx rx, int firstPullKpa) {
        return warmupStage(model, rx, firstPullKpa, rx != null && rx.track == Plan.TRACK_LENGTH);
    }

    /**
     * t10 R-01 - THE WARM-UP IS P2 (the owner's P2, 1 Oct 2026): about five minutes of reps from
     * 3.5 inHg, climbing rep by rep to the work - never more than 1.0 inHg a rep - its holds
     * growing evenly from 30 s to 60 s, each followed by a 5 s drop to the routine's drop. It
     * ends AT the work where the climb gets there (#p2Warm); where it cannot, the holds after it
     * carry the climb on (R-02, #lowerHolds with a #CarryRule). Built for every Program warm-up
     * choice but "none" (K19: "short" and "climb" build it too). The old prime, eased first
     * cycle and climbing ramp, and the steppers that shaped them (Model#rxWarmMin, #rxPrimeKpa,
     * #rxEaseHg, #rxWarmSteps, #rxWarmRamp), are no longer read here; they stay saved. Library
     * and manual routines keep theirs (#manualWarmUp).
     *
     * SOMEBODY WHO MARKS OR BRUISES EASILY GETS THEIR GENTLE WARM-UP INSTEAD (K3): their own
     * start, speed and step (#gentleWarmStage), ending at the work on girth, at 80 % on length.
     *
     * `lengthCap`: the warm-up leads into a TRACTION pull, so it ends at 80 % of it (S18, R-03;
     * RunShape#warmCapKpa). Everything else - girth, the expansion-only length session (fix b),
     * a girth-focus block's expansion - warms up to the work.
     */
    static Model.Stage warmupStage(Model model, Mint.Rx rx, int firstPullKpa,
                                   boolean lengthCap) {
        if (rx == null || rx.track == Plan.TRACK_FEEDER) return null;
        // A routine minted before t10, rebuilt from its signature (Model#legacyT10).
        if (model.legacyT10) return legacyWarmupStage(model, rx, firstPullKpa);
        model.clampRxShape();
        Model.Program wprog = model.programFor(rx.track);
        int wmode = wprog == null ? Model.Program.WARM_STANDARD : wprog.warm;
        if (wmode == Model.Program.WARM_NONE) return null;
        int top = Math.min(rx.pressureKpa, model.ceilKpa);
        if (top < 1) return null;

        /* 0.10 - "I MARK OR BRUISE EASILY" WARMS UP GENTLY (the owner's own rule for this app, not
         * the guidance's): it starts low and slow and climbs a little each rep to the working
         * pressure - see #gentleWarmStage. The warm-up length and the Program's "none" still
         * decide whether there is one (Model#gentleWarmFor). */
        if (model.gentleWarmFor(rx.track)) {
            Model.Stage g = gentleWarmStage(model, rx,
                firstPullKpa == Integer.MAX_VALUE ? top : Math.min(firstPullKpa, model.ceilKpa));
            if (g != null) return g;
        }
        return p2Warm(model, rx, lengthCap ? RunShape.warmCapKpa(top) : top);
    }

    /**
     * t10 - THE WARM-UP AS BUILDS BEFORE t10 MADE IT (Model#legacyT10): a flat prime at about
     * 4 inHg for the warm-up length set, then one cycle eased under the work - or the climbing
     * ramp, or the short prime - each held under the work's first pull (D5), a length session's
     * prime at 80 % of the pull. Asked only of a scratch model SavedMint rebuilds a routine in
     * from a signature minted before t10, so the routine the plan saved then is recognised as
     * the plan's and rewritten, with a notice - never mistaken for the person's own edit. The
     * same code as then, kept for that one question.
     */
    private static Model.Stage legacyWarmupStage(Model model, Mint.Rx rx, int firstPullKpa) {
        model.clampRxShape();
        Model.Program wprog = model.programFor(rx.track);
        int wmode = wprog == null ? Model.Program.WARM_STANDARD : wprog.warm;
        if (wmode == Model.Program.WARM_NONE) return null;
        int warmMin = wmode == Model.Program.WARM_SHORT
                ? (model.rxWarmMin <= 0 ? 0 : 2) : model.rxWarmMin;
        if (warmMin <= 0) return null;
        int top = Math.min(rx.pressureKpa, model.ceilKpa);
        if (top < 2) return null;

        /* 0.10 - "I MARK OR BRUISE EASILY" WARMS UP GENTLY (the owner's own rule for this app, not
         * the guidance's): whatever warm-up shape is chosen, it starts low and slow and climbs a
         * little each rep to the working pressure - see #gentleWarmStage. The warm-up length
         * and the Program's "none" still decide whether there is one (Model#gentleWarmFor). */
        if (model.gentleWarmFor(rx.track)) {
            Model.Stage g = gentleWarmStage(model, rx,
                firstPullKpa == Integer.MAX_VALUE ? top : Math.min(firstPullKpa, model.ceilKpa));
            if (g != null) return g;
        }

        if (wmode == Model.Program.WARM_RAMP
                || (wmode == Model.Program.WARM_STANDARD && model.rxWarmRamp)) {
            Model.Stage climb = legacyWarmupRamp(model, Math.min(top, firstPullKpa));
            if (climb != null) return climb;
            /* A CLIMB THAT DOES NOT FIT FALLS BACK TO THE SHORT PRIME (the owner's ruling,
             * 0.10). A light day - a taper step, a Gentle bias, every traction session's pulls -
             * leaves too little under the work for a climb that ends about 1 inHg below it, and
             * the warm-up used to vanish entirely: the session opened on its first work pull
             * from cold. The short prime below is what the Program's "short" warm-up builds,
             * held under the work's first pull like every warm-up step (D5); where even that
             * cannot sit under the work, there is no warm-up. */
            warmMin = 2;
        }

        java.util.List<String> ids = new java.util.ArrayList<String>();
        // PRIME - flat, and never deeper than the work itself: a "warm-up" above the
        // working pressure would be the hardest part of the session.
        /* S18 - AND ON LENGTH, NEVER AS DEEP AS THE WORK EITHER, AT LOW LOADS.
         *
         * rxPrimeKpa (13.55 kPa / 4 inHg) is shared with girth, whose work runs well above
         * that by L1. Length's `top` is the traction PULL, which at an L1 load is often
         * under 4 inHg to begin with - min(rxPrimeKpa, top) then collapsed to `top` exactly,
         * so the "warm-up" primed at 100% of the governed load from cold: the full-pressure
         * hold this stage exists to prevent (see warmupStage's own doc, above), wearing the
         * warm-up's name. Girth never hits this, so the 80% ceiling is length-only - a
         * no-op once the pull clears it, and it can only LOWER what min() already capped,
         * never raise it.
         *
         * NOT IN THE GUIDANCE. Its length and general material describe manual prep
         * before the device, not a pump prime at all, so there is no source figure to read
         * 80% off; it is this app's own choice of "clearly lighter than the work" (owner
         * ruling, 2026-09-26).
         */
        int primeCapKpa = rx.track == Plan.TRACK_LENGTH
            ? Math.max(1, (int) Math.round(top * 0.8)) : top;
        int prime = Math.min((int) Math.round(model.rxPrimeKpa), primeCapKpa);
        // D5 - under the work's first pull, not at it (a prime AT the work is the work).
        if (firstPullKpa != Integer.MAX_VALUE) prime = Math.min(prime, firstPullKpa - 1);
        if (prime < 1) return null;             // nothing under the work to prime at
        int primeSec = warmMin * 60;
        Model.Set p = new Model.Set();
        p.id = model.newSetId();
        p.name = primeName(prime);
        p.up = prime;
        p.lo = Math.max(0, prime - 1);
        p.uh = primeSec;          // one hold, not a cycle - nothing to repeat
        p.lh = 0;
        p.sp = 50;
        p.dur = primeSec;
        p.fromPlan = true;
        p.clamp(model.ceilKpa);
        model.sets.add(p);
        ids.add(p.id);

        // EASE - one cycle at the working pressure less the eased amount. Skipped when it
        // would land at or below the prime hold: easing to a pressure you have just spent
        // four minutes at is not an easing-in, it is the same hold twice.
        int easeKpa = (int) Math.round(top - model.rxEaseHg * Model.Fmt.KPA_PER_INHG);
        // Eased under the work's first pull, too: work that starts at the band floor starts
        // under "the prescription less 3 hg", and an easing-in above the start is not one.
        if (firstPullKpa != Integer.MAX_VALUE) easeKpa = Math.min(easeKpa, firstPullKpa - 1);
        if (model.rxEaseHg > 0 && easeKpa > prime) {
            Mint.SetSpec w = Mint.workSet(rx, model.rxDropKpa);
            Model.Set e = new Model.Set();
            e.id = model.newSetId();
            e.name = "First cycle at " + Model.Fmt.p(easeKpa);
            e.up = easeKpa;
            // The work's drop, kept under THIS cycle's lower pull (RunEdit#dropKept - review
            // I4: a drop of 26 under a 20 kPa ease cycle went out as 19, 1 kPa under it).
            e.lo = RunEdit.dropKept(w.lo, easeKpa, w.lh <= 0);
            e.uh = w.uh;
            e.lh = w.lh;
            e.sp = w.sp;
            e.dur = w.uh + w.lh;   // exactly one cycle
            e.fromPlan = true;
            e.clamp(model.ceilKpa);
            model.sets.add(e);
            ids.add(e.id);
        }
        return Model.Stage.of("Warm-up", Model.STAGE_WARM,
                              ids.toArray(new String[ids.size()]));
    }

    /** The pre-t10 climbing warm-up, for #legacyWarmupStage alone. */
    private static Model.Stage legacyWarmupRamp(Model model, int workKpa) {
        int gap = (int) Math.round(Model.Fmt.KPA_PER_INHG);          // about 1 inHg
        int end = workKpa - gap;
        int start = Math.max(7, Math.round(end / 2f));
        if (start >= end) return null;          // nothing left to ramp through
        int drop = Mint.clampDropKpa(model.rxDropKpa);
        int lh = Mint.DROP_SEC;
        int steps = Math.max(2, Math.min(Proto.SLOTS, model.rxWarmSteps));
        int stepSec = Math.max(1, model.rxWarmMin * 60 / steps);
        // Cycles per step: as near the old 35 s cycle as the step allows, at least one.
        int perStep = Math.max(1, Math.round(stepSec / 35f));
        int cycle = Math.max(lh + 1, stepSec / perStep);
        Model.Set w = new Model.Set();
        w.id = model.newSetId();
        w.name = "Warm-up to " + Model.Fmt.p(end);
        w.ramp = true;
        w.up = start;   w.up2 = end;
        w.lo = RunEdit.dropKept(drop, start, false);
        w.lo2 = RunEdit.dropKept(drop, end, false);
        w.uh = cycle - lh; w.lh = lh; w.uh2 = cycle - lh; w.lh2 = lh;
        w.sp = 60; w.sp2 = 75;
        w.steps = steps;
        w.dur = steps * perStep * cycle;
        w.fromPlan = true;
        w.clamp(model.ceilKpa);
        model.sets.add(w);
        return Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[]{ w.id });
    }

    /** Whether a warm-up built for `track` is P2's (R-01): a warm-up is built (the Program's
     *  is not "none"), it is not the feeder, and the gentle one does not take its place (K3). */
    static boolean p2Applies(Model model, int track) {
        if (model == null || track == Plan.TRACK_FEEDER) return false;
        Model.Program prog = model.programFor(track);
        if (prog != null && prog.warm == Model.Program.WARM_NONE) return false;
        return !model.gentleWarmFor(track);
    }

    /**
     * THE WARM-UP A TRAINER ROUTINE FOR `track` GETS, in the words the "What it writes" door
     * prints (Model.Shape#summaryParts; t10 device walk M2): "5-min climbing warm-up" (P2,
     * #p2Applies), "gentle warm-up" (marks, Model#gentleWarmFor) or "no warm-up" (the
     * Program's "none"). The same questions #warmupStage asks, so the door cannot describe a
     * warm-up the builder does not build.
     */
    public static String warmUpWord(Model model, int track) {
        if (p2Applies(model, track))
            return (Plan.P2_WARM_SEC / 60) + "-min climbing warm-up";
        if (model != null && model.gentleWarmFor(track)) return "gentle warm-up";
        return "no warm-up";
    }

    /** #warmUpWord for the tracks that are on: one phrase when they agree, "girth …, length …"
     *  when they do not. Girth alone when no track is on (the shape room is girth's first). */
    public static String warmUpFact(Model model) {
        if (model == null) return "";
        boolean girth = model.trainerGirthOn || !model.trainerLengthOn;
        String g = girth ? warmUpWord(model, Plan.TRACK_GIRTH_INTERVAL) : null;
        String l = model.trainerLengthOn ? warmUpWord(model, Plan.TRACK_LENGTH) : null;
        if (g == null) return l;
        if (l == null || l.equals(g)) return g;
        return "girth " + g + ", length " + l;
    }

    /** R-01: how many reps P2 runs - its minutes over the average rep (the mean hold and the
     *  drop): max(2, round(300 / 50)) = 6. */
    static int p2RepCount() {
        double avg = (Plan.P2_HOLD0 + Plan.P2_HOLD1) / 2.0 + Plan.P2_DROP_SEC;
        return Math.max(2, (int) Math.round(Plan.P2_WARM_SEC / avg));
    }

    /**
     * R-01: THE PRESSURES OF P2'S REPS toward `endKpa`, whole kPa: from 3.5 inHg (never above
     * the end), an even step of at most 1.0 inHg - ceil((end - start) / (n - 1)) - each rep at
     * most the end. With the step held to 1.0 inHg the last rep can stop short of the end: girth
     * L3 at 30 kPa is 12, 15 .. 27, and the holds after it carry on (R-02). At or under the
     * start every rep is at the end.
     */
    static int[] p2Reps(int endKpa) {
        int end = Math.max(1, endKpa);
        int n = p2RepCount();
        int start = Math.min(end, Plan.P2_START_KPA);
        int span = end - start;
        int step = Math.min(Plan.P2_STEP_KPA, Math.max(0, (span + n - 2) / (n - 1)));
        int[] ps = new int[n];
        for (int i = 0; i < n; i++) ps[i] = Math.min(end, start + i * step);
        return ps;
    }

    /** R-01: rep `i` of `n`'s hold, seconds - 30 s growing evenly to 60 s. */
    static int p2HoldSec(int i, int n) {
        return Ramp.ladderPoint(Plan.P2_HOLD0, Plan.P2_HOLD1, i, n);
    }

    /**
     * R-01 - THE P2 WARM-UP STAGE toward `endKpa` (#p2Reps): reps of 30 s growing to 60 s, a
     * 5 s drop to the routine's drop after each, at the work's own pump speed. A run of reps
     * the device can step through as one ramp is one ramp set (Ramp#segments), so the six reps
     * of an ordinary day are one set. Named for where it gets to: "Warm-up to -8.0 inHg". Not
     * work: the warm colour keeps it out of every count.
     */
    static Model.Stage p2Warm(Model model, Mint.Rx rx, int endKpa) {
        if (model == null || rx == null || endKpa < 1) return null;
        int[] ps = p2Reps(endKpa);
        int[] holds = new int[ps.length];
        for (int i = 0; i < ps.length; i++) holds[i] = p2HoldSec(i, ps.length);
        String name = "Warm-up to " + Model.Fmt.p(ps[ps.length - 1]);
        return repsStage(model, name, name, ps, holds, Plan.P2_DROP_SEC,
                         Math.max(5, Math.min(100, rx.powerPct)));
    }

    /** R-01 - the routine card's warm-up line for work at `workKpa`: "Warm-up: 6 reps from
     *  -3.5 to -8.0 inHg, holds 30 → 60 s" (the end where the reps get to). `lengthCap` for a
     *  traction session's 80 %. "" for nothing to warm up to. */
    public static String p2WarmLine(int workKpa, boolean lengthCap) {
        if (workKpa < 1) return "";
        int[] ps = p2Reps(lengthCap ? RunShape.warmCapKpa(workKpa) : workKpa);
        // Polish NEW-22: "holds", the word the run screen and Library use, not "reps".
        return "Warm-up: " + ps.length + " holds from " + Model.Fmt.pBare(ps[0]) + " to "
            + Model.Fmt.p(ps[ps.length - 1]) + ", " + Plan.P2_HOLD0 + " → "
            + Plan.P2_HOLD1 + " s";
    }

    /**
     * REPS AS SETS: one rep of `holds[i]` s at `ps[i]` kPa then a `lh` s drop to the person's
     * drop. A straight run of pressures the device can step through (at most a table's worth,
     * Ramp#segments) is one ramp set of one whole cycle a step; a lone rep is one fixed cycle.
     * The holds of a run must be straight too, as P2's and R4's are (evenly growing, or all one
     * length). A warm stage named `stageName`, its sets named `setName`.
     */
    private static Model.Stage repsStage(Model model, String stageName, String setName, int[] ps,
                                         int[] holds, int lh, int sp) {
        int drop = Mint.clampDropKpa(model.rxDropKpa);
        java.util.List<int[]> segs = Ramp.segments(ps);
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (int g = 0; g < segs.size(); g++) {
            int from = segs.get(g)[0], to = segs.get(g)[1], n = to - from + 1;
            int a = ps[from], z = ps[to];
            int dur = 0;
            for (int i = from; i <= to; i++) dur += holds[i] + lh;
            String nm = setName + (segs.size() > 1 ? " · " + (g + 1) : "");
            Model.Set w = n >= 2
                ? Model.Set.ramp(model.newSetId(), nm, a, RunEdit.dropKept(drop, a, false),
                    holds[from], lh, sp, z, RunEdit.dropKept(drop, z, false), holds[to], lh, sp,
                    n, dur)
                : Model.Set.fixed(model.newSetId(), nm, a, RunEdit.dropKept(drop, a, false),
                    holds[from], lh, sp, dur);
            w.fromPlan = true;
            w.clamp(model.ceilKpa);
            model.sets.add(w);
            ids.add(w.id);
        }
        if (ids.isEmpty()) return null;
        return Model.Stage.of(stageName, Model.STAGE_WARM, ids.toArray(new String[ids.size()]));
    }

    /** The last pull a stage commands - where a warm-up got to. 0 for none. */
    public static int lastPullKpa(Model model, Model.Stage st) {
        if (model == null || st == null) return 0;
        int last = 0;
        for (int j = 0; j < st.setIds.size(); j++) {
            Model.Set s = model.set(st.setIds.get(j));
            if (s == null || s.rest) continue;
            last = s.ramp ? s.up2 : s.up;
        }
        return last;
    }

    /* ============================================== t10 R-07: girth after length (R4) */

    /**
     * R-07 - THE RAMP-IN'S PRESSURES, in place of the fatigue block when girth follows length
     * (Model#R4_WARM): 30 s holds climbing to the work at most 1.0 inHg a rep. With P2 in force
     * it starts where P2 starts (3.5 inHg) and steps P2's step to the work: at 30 kPa 12, 15 ..
     * 27, 30. For somebody who marks (P2 not in force) about two minutes of reps under the
     * work: max(2, round(120 / 35)) = 3, at work - 6, work - 3 and the work.
     */
    static int[] r4RampReps(boolean p2, int workKpa) {
        int work = Math.max(1, workKpa), step = Plan.P2_STEP_KPA;
        if (p2) {
            int start = Math.min(work, Plan.P2_START_KPA);
            java.util.List<Integer> ps = new java.util.ArrayList<Integer>();
            for (int k = start; k < work; k += step) ps.add(Integer.valueOf(k));
            ps.add(Integer.valueOf(work));
            int[] out = new int[ps.size()];
            for (int i = 0; i < out.length; i++) out[i] = ps.get(i).intValue();
            return out;
        }
        int n = Math.max(2, (int) Math.round(Plan.R4_RAMP_SEC
            / (double) (Plan.R4_RAMP_HOLD_SEC + Plan.R4_RAMP_DROP_SEC)));
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = Math.max(1, work - (n - 1 - i) * step);
        return out;
    }

    /** R-07 - the ramp-in stage at `workKpa`: #r4RampReps as 30 s holds and 5 s drops, at the
     *  work's speed. A warm stage: not work, not counted, not in R2's time under pressure. */
    static Model.Stage r4RampIn(Model model, Mint.Rx rx, int workKpa) {
        if (model == null || rx == null || workKpa < 1) return null;
        int[] ps = r4RampReps(p2Applies(model, rx.track), workKpa);
        int[] holds = new int[ps.length];
        for (int i = 0; i < ps.length; i++) holds[i] = Plan.R4_RAMP_HOLD_SEC;
        return repsStage(model, "Ramp-in", "Ramp-in to " + Model.Fmt.p(ps[ps.length - 1]), ps,
                         holds, Plan.R4_RAMP_DROP_SEC, Math.max(5, Math.min(100, rx.powerPct)));
    }

    /* ============================================== t10: lowering holds, hold by hold */

    /** A rule over held holds in order: the pressure hold number `i` (from 0) takes, given the
     *  one it was built at. It only ever lowers. */
    abstract static class HoldRule {
        abstract int at(int i, int kpa);
    }

    /** R-02 - THE CARRY RAMP: from where the warm-up stopped, +`step` kPa a hold until the
     *  work, each hold the lower of that and its own. */
    static final class CarryRule extends HoldRule {
        private int cur;
        private final int to, step;
        CarryRule(int fromKpa, int toKpa, int stepKpa) {
            cur = fromKpa; to = toKpa; step = Math.max(1, stepKpa);
        }
        @Override int at(int i, int kpa) {
            cur = Math.min(to, cur + step);
            return Math.min(kpa, cur);
        }
    }

    /** R-07 "sets" - THE FIRST `n` HOLDS CLIMB TO THE WORK, at most `step` a hold:
     *  hold i is min(its own, work - (n - i) * step); the rest are untouched. */
    static final class FirstSetsRule extends HoldRule {
        private final int work, n, step;
        FirstSetsRule(int workKpa, int sets, int stepKpa) {
            work = workKpa; n = Math.max(0, sets); step = Math.max(1, stepKpa);
        }
        @Override int at(int i, int kpa) {
            if (i >= n) return kpa;
            return Math.min(kpa, Math.max(1, work - (n - i) * step));
        }
    }

    /**
     * LOWERS THE HOLDS OF `stages`, IN ORDER, AS `rule` SAYS (R-02's carry, R-07's first sets).
     * Every hold keeps its place, its hold and drop times and its speed, and only its pressure
     * can fall; a set with a lowered hold is laid out again as its lowered holds - ramp sets of
     * one cycle a step where they climb (Ramp#segments), the rest of it at its own pressure - in
     * the same stage, so a counted hold still counts and a fatigue hold stays one. Returns how
     * many holds were lowered.
     */
    static int lowerHolds(Model model, java.util.List<Model.Stage> stages, HoldRule rule) {
        if (model == null || stages == null || rule == null) return 0;
        int i = 0, lowered = 0;
        for (int si = 0; si < stages.size(); si++) {
            Model.Stage st = stages.get(si);
            if (st == null || st.rest) continue;
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set s = model.set(st.setIds.get(j));
                if (s == null || s.rest) continue;
                int[] was = holdPressures(s);
                int[] now = new int[was.length];
                int last = -1;
                for (int h = 0; h < was.length; h++) {
                    now[h] = rule.at(i++, was[h]);
                    if (now[h] < was[h]) { last = h; lowered++; }
                }
                if (last < 0) continue;
                java.util.List<String> ids = relaid(model, s, now, last);
                st.setIds.remove(j);
                st.setIds.addAll(j, ids);
                j += ids.size() - 1;
            }
        }
        return lowered;
    }

    /** Every hold a set commands, in order: a fixed set's whole cycles at its pressure, a
     *  ramp's steps their whole cycles each. */
    private static int[] holdPressures(Model.Set s) {
        if (s.ramp) {
            java.util.List<Model.Preset> lad = s.ladder();
            int k = Math.max(1, s.rampCycles());
            int[] out = new int[lad.size() * k];
            for (int p = 0; p < lad.size(); p++)
                for (int c = 0; c < k; c++) out[p * k + c] = lad.get(p).up;
            return out;
        }
        int n = Math.max(1, s.dur / s.cycle());
        int[] out = new int[n];
        for (int h = 0; h < n; h++) out[h] = s.up;
        return out;
    }

    /** Set `s` laid out again as the holds `ps` (lowered up to index `last`): the lowered run as
     *  ramp/fixed sets of one cycle a hold, the rest as `s` itself shortened - or, for a ramp,
     *  as its own steps again. A traction set's "×N" count is restated on each part. */
    private static java.util.List<String> relaid(Model model, Model.Set s, int[] ps, int last) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        int cyc = s.cycle();
        int[] head = java.util.Arrays.copyOfRange(ps, 0, last + 1);
        String name = s.name == null ? "" : s.name;
        String base = name.replaceAll(" ×\\d+$", "");
        boolean counts = !base.equals(name);
        boolean vented = s.lh <= 0;
        // A hold longer than the wire's is stitched, and a ramp never stitches (Model.Set#ladder):
        // such holds - a strain pull, a five-minute hold - are laid out one set a hold.
        boolean stitched = s.uh > Proto.WIRE_HOLD_MAX || s.lh > Proto.WIRE_HOLD_MAX;
        java.util.List<int[]> segs = stitched ? singles(head.length) : Ramp.segments(head);
        for (int g = 0; g < segs.size(); g++) {
            int from = segs.get(g)[0], to = segs.get(g)[1], n = to - from + 1;
            int a = head[from], z = head[to];
            String nm = base + " · climb" + (segs.size() > 1 ? " " + (g + 1) : "")
                + (counts ? " ×" + n : "");
            Model.Set c = n >= 2
                ? Model.Set.ramp(model.newSetId(), nm, a, RunEdit.dropKept(s.lo, a, vented),
                    s.uh, s.lh, s.sp, z, RunEdit.dropKept(s.lo, z, vented), s.uh, s.lh, s.sp,
                    n, n * cyc)
                : Model.Set.fixed(model.newSetId(), nm, a, RunEdit.dropKept(s.lo, a, vented),
                    s.uh, s.lh, s.sp, cyc);
            c.fromPlan = s.fromPlan;
            c.clamp(model.ceilKpa);
            model.sets.add(c);
            ids.add(c.id);
        }
        int rest = ps.length - (last + 1);
        if (rest <= 0) return ids;
        if (!s.ramp) {
            s.dur = rest * cyc;
            if (counts) s.name = base + " ×" + rest;
            s.clamp(model.ceilKpa);
            ids.add(s.id);
            return ids;
        }
        int[] tail = java.util.Arrays.copyOfRange(ps, last + 1, ps.length);
        java.util.List<int[]> ts = stitched ? singles(tail.length) : Ramp.segments(tail);
        for (int g = 0; g < ts.size(); g++) {
            int from = ts.get(g)[0], to = ts.get(g)[1], n = to - from + 1;
            int a = tail[from], z = tail[to];
            Model.Set c = n >= 2
                ? Model.Set.ramp(model.newSetId(), name, a, RunEdit.dropKept(s.lo, a, vented),
                    s.uh, s.lh, s.sp, z, RunEdit.dropKept(s.lo, z, vented), s.uh, s.lh, s.sp,
                    n, n * cyc)
                : Model.Set.fixed(model.newSetId(), name, a, RunEdit.dropKept(s.lo, a, vented),
                    s.uh, s.lh, s.sp, cyc);
            c.fromPlan = s.fromPlan;
            c.clamp(model.ceilKpa);
            model.sets.add(c);
            ids.add(c.id);
        }
        return ids;
    }

    /** {i, i} for each of `n` holds: one set a hold. */
    private static java.util.List<int[]> singles(int n) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        for (int i = 0; i < n; i++) out.add(new int[]{ i, i });
        return out;
    }

    /* ============================================== t10 R-04: the two-hour whole clock (P1) */

    /** What the stage a trim shortened is named with, so the routine says so (#trimmedNote). */
    public static final String TRIM_TAG = " · trimmed to 2 h";

    /** R-04 - the routine card's line for a routine #trimToCap shortened, or "". */
    public static String trimmedNote(Model.Routine r) {
        if (r == null) return "";
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st != null && st.name != null && st.name.endsWith(TRIM_TAG))
                return "Trimmed to stay under 2 hours";
        }
        return "";
    }

    /** Whether #trimToCap may take holds from `st`: a traction session's strain holds; any
     *  other routine's counted work (never its fatigue block, a climb out of the count, the
     *  retention hold, a rest or a stage done by hand). */
    private static boolean trimmable(Model.Stage st, boolean traction) {
        if (st == null || st.rest || st.manual || st.fatigueBlock) return false;
        if (traction) return st.traction;
        return st.colour == Model.STAGE_WORK && !st.retention && !st.climb && !st.traction;
    }

    /**
     * t10 R-04 (P1) - NO TRAINER ROUTINE IS BUILT OVER TWO HOURS ON THE WHOLE CLOCK: warm-up,
     * holds, drops, rests and the steps done by hand (Model#durationMs). Over it, counted work
     * holds come off THE END - a girth session's work, a traction session's strain holds - one
     * at a time, until it fits. The warm-up, the fatigue block, the release, the tube swap, the
     * expansion coda and the retention hold are never trimmed. A rest left at the end, or two
     * rests together, goes too. The stage it shortened says so (#TRIM_TAG). Returns how many
     * holds it took.
     */
    static int trimToCap(Model model, Model.Routine r) {
        if (model == null || r == null) return 0;
        long capMs = Plan.SESSION_CAP_MIN * 60000L;
        long total = model.durationMs(r);
        if (total <= capMs) return 0;
        boolean traction = false;
        for (int i = 0; i < r.stages.size(); i++) if (r.stages.get(i).traction) traction = true;
        int trimmed = 0;
        Model.Stage touched = null;
        for (int si = r.stages.size() - 1; si >= 0 && total > capMs; si--) {
            Model.Stage st = r.stages.get(si);
            if (!trimmable(st, traction)) continue;
            for (int j = st.setIds.size() - 1; j >= 0 && total > capMs; j--) {
                Model.Set s = model.set(st.setIds.get(j));
                if (s == null || s.rest) continue;
                if (s.ramp) {                   // a climb is not cut part-way: it goes whole
                    total -= s.rampDurSec() * 1000L;
                    trimmed += s.rampSteps() * Math.max(1, s.rampCycles());
                    st.setIds.remove(j);
                    continue;
                }
                int cyc = s.cycle(), n = s.dur / cyc;
                while (n > 0 && total > capMs) { n--; total -= cyc * 1000L; trimmed++; }
                if (n <= 0) st.setIds.remove(j);
                else {
                    s.dur = n * cyc;
                    if (s.name != null && s.name.matches(".* ×\\d+$"))
                        s.name = s.name.replaceAll(" ×\\d+$", " ×" + n);
                    s.clamp(model.ceilKpa);
                }
            }
            if (st.setIds.isEmpty()) r.stages.remove(si);
            else touched = st;
        }
        // A rest left at the end, or two together, separates nothing.
        for (int si = r.stages.size() - 1; si >= 0; si--) {
            Model.Stage st = r.stages.get(si);
            if (!st.rest || st.manual) continue;
            boolean atEnd = si == r.stages.size() - 1;
            boolean twice = si + 1 < r.stages.size() && r.stages.get(si + 1).rest
                         && !r.stages.get(si + 1).manual;
            if (atEnd || twice) r.stages.remove(si);
        }
        // The stage it shortened says so - or, where it emptied one whole, the last it left.
        if (touched == null)
            for (int si = r.stages.size() - 1; si >= 0 && touched == null; si--)
                if (trimmable(r.stages.get(si), traction)) touched = r.stages.get(si);
        if (trimmed > 0 && touched != null && (touched.name == null
                || !touched.name.endsWith(TRIM_TAG)))
            touched.name = (touched.name == null ? "" : touched.name) + TRIM_TAG;
        return trimmed;
    }

    /**
     * THE GENTLE WARM-UP TODAY'S ROUTINE FROM `rx` WOULD RUN, in a line - "7 reps: −4.1 → −8.9
     * inHg · speed 60% → 75%" - built in a scratch copy with "I mark or bruise easily" on, so
     * the Trainer page's preview is the builder's own answer at today's first work hold. ""
     * where no warm-up is built (a Program warm-up of none), or the track is not one a warm-up
     * is built for.
     */
    public static String gentleWarmLine(Model model, Mint.Rx rx) {
        if (model == null || rx == null) return "";
        try {
            Model s = SavedMint.scratchOf(model);
            s.marksEasily = true;
            if (!s.gentleWarmFor(rx.track)) return "No warm-up is built — the Program's warm-up is none.";
            Model.Routine r = s.routine(routineFromRx(s, rx));
            java.util.List<Model.Preset> plan = s.plan(r);
            int n = 0, first = -1, last = -1, sp0 = -1, sp1 = -1;
            for (int i = 0; i < plan.size(); i++) {
                Model.Preset p = plan.get(i);
                Model.Stage st = p.stageIdx >= 0 && p.stageIdx < r.stages.size()
                               ? r.stages.get(p.stageIdx) : null;
                if (st == null || st.colour != Model.STAGE_WARM || p.rest) continue;
                if (first < 0) { first = p.up; sp0 = p.sp; }
                last = p.up;
                sp1 = p.sp;
                n++;
            }
            if (n == 0) return "";
            if (n == 1)
                return "1 rep at " + Model.Fmt.p(first) + " · speed " + sp0 + "%";
            return n + " reps: " + Model.Fmt.pBare(first) + " → " + Model.Fmt.p(last)
                + " · speed " + sp0 + "% → " + sp1 + "%";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** How long each rep of the gentle warm-up holds, seconds: the climbing warm-up's own
     *  25 s hold, then the work's drop for the work's drop time - a 30 s rep. */
    public static final int GENTLE_REP_HOLD_SEC = 25;

    /**
     * THE GENTLE WARM-UP (0.10 - the owner's own rule for this app, never the guidance's), for
     * somebody who marks or bruises easily: it starts at Model#gentleWarmStartKpa (4.0 inHg by
     * default) and Model#gentleWarmSpeedPct (60 %), and climbs rep by rep to the working
     * pressure `workKpa` - the first work hold's commanded pressure - at most
     * Model#gentleWarmStepHg (1.0 inHg) a rep, its speed rising evenly to the work's own. It
     * ENDS AT the working pressure, where every other warm-up ends under it (D5): that is the
     * owner's rule for this warm-up. A start at or over the work starts at the work, never
     * above it - one rep, there.
     *
     * ON THE LENGTH TRACK IT ENDS WHERE A LENGTH WARM-UP MAY REACH: 80 % of the work (S18, the
     * safety review's rule that a length warm-up is never as deep as the pull or the expansion
     * it leads into - RunShape#warmCapKpa). The owner's "ends at the working pressure" would
     * take a length warm-up to 100 % of the pull from cold, which S18 exists to prevent, so
     * that part waits for the owner's ruling rather than weakening S18.
     *
     * Each rep is one cycle: a 25 s hold, then the work's own drop (never above the dose floor)
     * for the work's drop time. A climb longer than the device's table is played as
     * consecutive ramps (Ramp#segments). Null where there is nothing to pull to.
     */
    static Model.Stage gentleWarmStage(Model model, Mint.Rx rx, int workKpa) {
        int end = rx.track == Plan.TRACK_LENGTH ? RunShape.warmCapKpa(workKpa) : workKpa;
        if (end < 1) return null;
        int start = (int) Math.round(model.gentleWarmStartKpa);
        int[] reps = Ramp.gentleReps(start, end, Ramp.stepKpa(model.gentleWarmStepHg));
        if (reps.length == 0) return null;
        int workSp = Math.max(5, Math.min(100, rx.powerPct));
        // Never faster than the work it leads into: a slower day's work slows its warm-up too.
        int fromSp = Math.min(model.gentleWarmSpeedPct, workSp);
        int drop = Mint.clampDropKpa(model.rxDropKpa);
        int lh = Mint.DROP_SEC, uh = GENTLE_REP_HOLD_SEC, cyc = uh + lh;
        java.util.List<int[]> segs = Ramp.segments(reps);
        java.util.List<String> ids = new java.util.ArrayList<String>();
        String name = "Gentle warm-up to " + Model.Fmt.p(end);
        for (int g = 0; g < segs.size(); g++) {
            int from = segs.get(g)[0], to = segs.get(g)[1], n = to - from + 1;
            int a = reps[from], z = reps[to];
            int spA = Ramp.speedAt(fromSp, workSp, from, reps.length);
            int spZ = Ramp.speedAt(fromSp, workSp, to, reps.length);
            String nm = name + (segs.size() > 1 ? " \u00b7 " + (g + 1) : "");
            Model.Set w = n >= 2
                ? Model.Set.ramp(model.newSetId(), nm, a, RunEdit.dropKept(drop, a, false), uh, lh, spA,
                    z, RunEdit.dropKept(drop, z, false), uh, lh, spZ, n, n * cyc)
                : Model.Set.fixed(model.newSetId(), nm, a, RunEdit.dropKept(drop, a, false), uh, lh, spA,
                    cyc);
            w.fromPlan = true;
            w.clamp(model.ceilKpa);
            model.sets.add(w);
            ids.add(w.id);
        }
        return Model.Stage.of("Warm-up", Model.STAGE_WARM, ids.toArray(new String[ids.size()]));
    }

    /* ===================================================================== *
     *  THE LENGTH SESSION (trainer-v3 Phase 2)                              *
     * ===================================================================== */

    /** The tunica release that opens a length session - by hand, timed, five minutes. */
    public static final int TUNICA_RELEASE_SEC = 300;
    /** Ten fatigue holds, then the strain sets. */
    public static final int TRACTION_FATIGUE_HOLDS = 10;
    /** How long the changeover stage waits while the tubes are swapped. */
    /** The nominal window the changeover's frozen countdown SHOWS. It is not a deadline:
     *  the stage ends on an acknowledgement, not on this. */
    public static final int CHANGEOVER_SEC = 120;
    /** The expansion coda: the length track's own five intervals, unchanged. */
    public static final int CODA_SETS = Plan.LENGTH_A_SETS;
    public static final int CODA_HOLD_SEC = 120;

    /**
     * A LENGTH SESSION, in five blocks - three that command, two that only count.
     *
     *   1  tunica release   manual, vented, timed only
     *   2  fatigue block    [L] 10 x 60 s HOLDS
     *   3  strain block     [L] N x 300 s HOLDS at the governed load
     *   4  changeover       vented, swap to the girth tube
     *   5  expansion coda   [G] 5 x 120 s intervals - the old length session, unchanged
     *
     * THE TWO TUBES ARE NAMED PER STAGE, not per routine, because the session genuinely
     * uses both. Each stage carries the id of a cylinder that suits ITS work, resolved from
     * the measured girth at build time; where the rack has nothing suitable the id is empty,
     * which every reader already treats as "the active one" and no reader treats as a
     * missing cylinder.
     *
     * THE TRACTION BLOCKS ARE HOLDS. No drop, nothing to drop to - see Mint#tractionSet, and
     * the WiringCheck invariant that holds it open. Their pressure comes from the LOAD and
     * has nothing to do with the coda's, which is why the coda's monthly creep can move
     * without touching them.
     *
     * DURING A GIRTH-FOCUS BLOCK the traction work pauses and the coda doubles - the block
     * is a temporary change of emphasis, not a track switch, so the session keeps its shape
     * and its name and simply stops pulling for a while.
     */
    public static String tractionRoutineFromRx(Model model, Mint.Rx rx, double loadLb,
                                               double boreCm, int strainSets,
                                               boolean girthFocus) {
        return tractionRoutineFromRx(model, rx, loadLb, boreCm, strainSets, girthFocus,
                                     Day.today(model));
    }

    /** The same session, for the day given rather than for today (see {@link Day}).
     *  `boreCm` is the length cylinder's bore - the load converts at it (owner, 2026-09-30). */
    public static String tractionRoutineFromRx(Model model, Mint.Rx rx, double loadLb,
                                               double boreCm, int strainSets,
                                               boolean girthFocus, Day day) {
        /* THE CODA IS A GIRTH RUN IN A GIRTH CYLINDER, so every rule that governs one still
         * governs it. This line was missing and its absence was a real safety regression:
         * routineFromRx applies Mint.reduce BELOW the traction diversion, so describing a
         * traction tube raised the commanded expansion pressure by the whole 2 hg the
         * oversize-petechiae rule had just taken off - in the exact tube that rule exists
         * for, with nothing on any screen saying so.
         *
         * IT TOUCHES THE CODA ONLY, which is the point: the traction blocks below take their
         * pressure from Traction.kpaForLb and never read rx.pressureKpa, so a reduction
         * meant for inflation cannot reach work that is pulling. */
        /* D2 - THE CODA'S FIGURE IS BIASED FIRST, THEN CUT (#commanded), the same order the
         * girth builder uses: a Firm coda on a taper day runs the band's top LESS the day's
         * cut, not the band's top. Taken before the cut below, which is the plain one. */
        /* AND HELD TO GIRTH'S LIMIT AS WELL AS LENGTH'S (review I3, the controller's ruling
         * 2026-09-30): the coda runs in the GIRTH cylinder, so a length plan step, offset or
         * Firm bias past "Most you will go to" for girth would expand girth tissue past it.
         * The lower of the two maxima, and every limit #commanded already applied. */
        final int codaKpa = Math.max(1, Math.min(commanded(model, rx, day).pressureKpa,
            Scale.workHardKpa(model, Plan.TRACK_LENGTH,
                              TrainerTab.monthIndexNow(model, day.atMs))));
        // The coda's scale (0.10) - the part of this session net time is counted from.
        final int scale = scaleKpa(model, rx, day);
        // The pulls' own hard limits: the ceiling, "Most you will go to", 15 inHg and the
        // pressure that makes the hard load limit at this girth (Scale#pullCapKpa).
        final int pullHard = Scale.pullCapKpa(model, boreCm, day.atMs);
        // The cut and the gentle day's slower pull (#dayOf) - no bias: the prescription
        // itself, for everything below that is not the coda's pressure.
        rx = dayOf(rx, day);

        Model.Routine r = new Model.Routine();
        r.id = model.newRoutineId();
        r.trainerTrack = rx.track;
        r.trainerLevel = rx.level;
        r.trainerScaleKpa = scale;
        // Wave 3b: the length track's Program (read once; used by the fatigue block,
        // the coda's pressure bias and the coda's shape below).
        Model.Program lprog = model.programFor(Plan.TRACK_LENGTH);
        /* NET COMES FROM THE CODA, because the coda is the only thing in it.
         *
         * This used to copy rx.netTargetMin, which the mint derives from rx.sets - and a
         * traction routine IGNORES rx.sets entirely (the strain block reads persisted state,
         * the coda is a constant). So every accepted "+1 strain set" moved the routine's
         * stated target two minutes further out while the work in net did not change by a
         * second, and the session was then scored against a figure it could not reach. The
         * target is now what the coda actually asks for. */
        int codaSetsForNet = girthFocus ? CODA_SETS * 2 : CODA_SETS;
        /* A REDUCED DAY ASKS FOR NO NET HERE EITHER (Q1/Q2; the girth builder's own rule). The
         * day's cut left the prescription with a target of 0 (Mint#reduce) and the coda kept a
         * full one, so a traction session on a taper or cylinder-cut day - its coda commanded
         * under the line net counts from - was scored as under-delivery, the exact reading
         * three of which propose stepping the level back. */
        r.netTargetMin = rx.netTargetMin <= 0.0 ? 0.0 : codaSetsForNet * (CODA_HOLD_SEC / 60.0);
        /* t10 R-06 (R1) - BOTH TRACKS TODAY: the session ends after its strain holds. No tube
         * swap, no expansion - the girth session is the day's expansion, and it gives up no
         * holds for this one. With nothing of the coda left, nothing here is asked for net.
         * Never during a girth-focus block, whose expansion IS the work. */
        final boolean withCoda = girthFocus || !day.bothTracks;
        if (!withCoda) r.netTargetMin = 0.0;

        // The length cylinder the person marked; the coda's girth tube by fit, or with no
        // girth logged by role (Model#girthCylinderForCoda).
        String lTube = model.lengthCylinderId();
        String gTube = model.girthCylinderForCoda(model.girthForTraction());

        // 1. THE TUNICA RELEASE. A rest stage, because a rest is precisely "the cuff is
        //    vented, wait here" - which is what this is - plus the manual flag, so nothing
        //    can later mistake it for work and no set can be put in it (clampRest empties
        //    the list and there is no path from an empty stage to Proto). Its 5:00 is a guide:
        //    the run names it BY HAND and waits for Done once that time is up, derived from
        //    the manual flag (ByHand#waitsAfterClock; the owner's decision, 2026-10-07).
        Model.Stage rel = Model.Stage.restOf("Tunica release \u2014 by hand", TUNICA_RELEASE_SEC);
        rel.manual = true;
        r.stages.add(rel);

        /* AND THE WARM-UP, which this shape had silently dropped.
         *
         * warmupStage is called from routineFromRx's girth body, BELOW the traction
         * diversion - so describing a traction cylinder removed the prime-and-ease ramp in
         * front of the first full-load pull, which is precisely what that stage's own doc
         * says it exists to prevent ("a routine that opens with a full-pressure hold from
         * cold"). Nothing about pulling makes a cold start safer.
         *
         * AFTER the release rather than before it: the release commands nothing and is done
         * by hand outside the tube, so the ramp belongs between it and the first block that
         * actually pulls. warmupStage caps itself at rx.pressureKpa, so it can never be
         * deeper than the work it precedes. */
        /* IT WARMS UP FOR THE WORK IT PRECEDES, in the tube that work happens in - and
         * which work that is depends on whether there are pulls at all.
         *
         * ORDINARILY the pulls come next, so the warm-up runs in the traction tube and is
         * capped at the traction command. warmupStage's own contract is "never deeper than
         * the work itself", and it was measuring that against rx.pressureKpa - the CODA's
         * expansion pressure, twenty-odd kPa, which in the traction tube is a pull half
         * again heavier than the load the ladder governs. Played to a simulated cuff it
         * primed at 14 kPa, about 3.9 lb, in front of a block governed to 2.5 lb: the
         * hardest pull of the session, ungoverned, first, straight off a cold start. That
         * is the exact failure the stage exists to prevent, wearing its name.
         *
         * DURING A GIRTH-FOCUS BLOCK there are no pulls. The only commanded work is the
         * expansion coda in the girth tube, so the warm-up belongs to that - at the coda's
         * pressure and in the coda's cylinder. Naming the traction tube there sent somebody
         * to warm up in one cylinder and then expand in another, with the changeover stage
         * that would have told them to swap living inside the very branch that a focus block
         * skips.
         */
        /* During a girth-focus block the coda is the work, so the warm-up climbs toward the
         * coda's own figure - biased as the coda is - never past it (a Gentle coda runs under
         * the plain prescription). */
        Mint.Rx warmRx = new Mint.Rx(rx.track, rx.level, rx.sets, rx.holdSec, rx.restSec,
                                     codaKpa, rx.fatigue, rx.netTargetMin, rx.powerPct);
        if (!girthFocus) {
            int pullKpa = Mint.tractionSet(loadLb, boreCm, Mint.TRACTION_FATIGUE_HOLD_SEC,
                                           Mint.TRACTION_FATIGUE_REST_SEC, pullHard).up;
            warmRx = new Mint.Rx(rx.track, rx.level, rx.sets, rx.holdSec, rx.restSec,
                                 pullKpa, rx.fatigue, rx.netTargetMin, rx.powerPct);
        }
        // (Built once the blocks after it are laid out - below the coda - so it ends under the
        // first pull they ask for, D5; it goes in straight after the release.)

        // The holds the P2 carry climbs through (R-02): the fatigue holds, then the strain.
        java.util.List<Model.Stage> heldStages = new java.util.ArrayList<Model.Stage>();
        int pullKpaHeld = 0;
        if (!girthFocus) {
            // 2. THE FATIGUE BLOCK — one set of ten one-minute holds at the governed
            //    load, the gap a release to 0 inside the repetition (wave 3a ruling).
            Mint.SetSpec fs = Mint.tractionSet(loadLb, boreCm,
                Mint.TRACTION_FATIGUE_HOLD_SEC, Mint.TRACTION_FATIGUE_REST_SEC,
                pullHard);
            int fatMode = lprog == null ? Model.Program.FAT_STANDARD : lprog.fatigue;
            if (fatMode != Model.Program.FAT_OFF) {
                int fatN = TRACTION_FATIGUE_HOLDS;
                if (fatMode == Model.Program.FAT_EXTENDED) fatN = fatN * 3 / 2;
                Model.Stage fat = tractionStage(model, r, "Fatigue holds", "Fatigue hold",
                    fs, fatN, lTube);
                fat.fatigueBlock = true;
                r.stages.add(fat);
                heldStages.add(fat);
            }
            // No inter-block rest stage (wave 3a ruling): the source table's 30 s is the
            // rest BETWEEN strain sets, which now rides inside the strain set itself.

            // 3. THE STRAIN BLOCK - the work the ladder actually governs.
            Mint.SetSpec ss = Mint.tractionSet(loadLb, boreCm,
                Mint.TRACTION_STRAIN_HOLD_SEC, Mint.TRACTION_STRAIN_REST_SEC,
                pullHard);
            Model.Stage strain = tractionStage(model, r, "Strain holds", "Strain hold",
                ss, Math.max(1, strainSets), lTube);
            r.stages.add(strain);
            heldStages.add(strain);
            pullKpaHeld = Math.max(fs.up, ss.up);
        }

        if (!girthFocus && withCoda) {
            // 4. THE CHANGEOVER. Vented and uncommanded, carrying the one sentence that
            //    says what to do and what is coming.
            Model.Stage over = Model.Stage.restOf(
                "Swap to your girth cylinder \u2014 next is expansion at "
                + Model.Fmt.p(codaKpa), CHANGEOVER_SEC);
            over.manual = true;
            /* AND IT WAITS. Spec section 4 item 4: "held, uncommanded ... waits for
             * acknowledgement". The tunica release above does NOT get this flag: it waits
             * for Done only once its guide time is up (the owner's decision, 2026-10-07,
             * replacing spec item 1's "manual, timed only"), where this waits from its start,
             * because being wrong about whether the tubes have been swapped means commanding
             * pressure into the wrong one. */
            over.awaitAck = true;
            r.stages.add(over);
        }

        // 5. THE EXPANSION CODA - the length session this track has always run. Doubled
        //    while a girth-focus block is on, which is what the block IS.
        /* WAVE 3b — the length Program shapes the coda: pressure bias inside the band,
         * and ramp-in-set / ascending render the coda's cycles as a climb to the
         * prescription (pyramid falls back to fixed — one set cannot peak mid-way). The
         * traction blocks above are LOAD-governed and take no pressure shaping. */
        if (withCoda) {
            Mint.SetSpec cspec = Mint.workSet(new Mint.Rx(rx.track, rx.level, CODA_SETS,
                CODA_HOLD_SEC, rx.restSec, codaKpa, false, rx.netTargetMin, rx.powerPct),
                model.rxDropKpa);
            int codaSets = girthFocus ? CODA_SETS * 2 : CODA_SETS;
            Model.Stage coda = new Model.Stage();
            coda.name = "Expansion";
            coda.colour = Model.STAGE_WORK;
            coda.cylinderId = gTube;
            int cycle = Math.max(1, cspec.uh + cspec.lh);
            String cid = model.newSetId();
            Model.Set cset;
            int lshape = lprog == null ? Model.Program.WORK_FIXED : lprog.work;
            int codaStart = Math.max(2, Math.min(
                (int) Math.round(Plan.floorKpa(rx.level)), cspec.up));
            /* 0.10 - A RAMPED CODA CLIMBS AS A RAMPED BLOCK DOES (the owner's ramp decisions reach
             * length's expansion work too): from a share of the coda's own working pressure, a
             * step at most the person's each hold, then its holds at the work - one block, so the
             * first block's full climb. Ascending keeps the climb from the floor it always had. */
            RampPlan codaRamp = lshape == Model.Program.WORK_RAMP_IN_SET && !model.legacyRamp
                ? rampPlan(model, cspec.up, new int[]{ codaSets }, day) : null;
            java.util.List<Model.Set> codaClimb = new java.util.ArrayList<Model.Set>();
            Model.Stage codaClimbStage = null;
            if (codaRamp != null) {
                java.util.List<java.util.List<String>> ids = new java.util.ArrayList<java.util.List<String>>();
                java.util.List<java.util.List<String>> cids = new java.util.ArrayList<java.util.List<String>>();
                ids.add(new java.util.ArrayList<String>());
                cids.add(new java.util.ArrayList<String>());
                java.util.List<Model.Set> atWork = new java.util.ArrayList<Model.Set>();
                layoutRamped(model, codaRamp, cspec, new int[]{ codaSets }, cycle,
                    Math.max(cycle, (3600 / cycle) * cycle), 0, Double.NEGATIVE_INFINITY,
                    "Expansion hold", ids, cids, atWork, codaClimb);
                coda.setIds.addAll(ids.get(0));
                if (!cids.get(0).isEmpty()) {
                    codaClimbStage = Model.Stage.of("Climb", Model.STAGE_WORK,
                        cids.get(0).toArray(new String[cids.get(0).size()]));
                    codaClimbStage.climb = true;    // not counted, by the person's choice
                    codaClimbStage.cylinderId = gTube;
                    r.stages.add(codaClimbStage);
                }
                // THE CODA'S TARGET IS WHAT ITS NET COUNTS: its working holds, and the climb's
                // holds at or above the line where the climb is counted (as the girth builder).
                if (r.netTargetMin > 0.0) {
                    int counted = 0;
                    for (int i = 0; i < atWork.size(); i++) counted += atWork.get(i).dur / cycle;
                    if (codaRamp.counted)
                        for (int i = 0; i < codaClimb.size(); i++)
                            counted += countedClimbHolds(model, r, codaClimb.get(i), day);
                    r.netTargetMin = counted * (CODA_HOLD_SEC / 60.0);
                }
                r.stages.add(coda);
            }
            // D9: a climb with nothing to climb (at or under the floor) is the fixed set it plays.
            if (codaRamp != null) {
                cset = null;                        // laid out above
            } else if ((lshape == Model.Program.WORK_ASCENDING
                    || (lshape == Model.Program.WORK_RAMP_IN_SET && model.legacyRamp))
                    && codaSets >= 2 && codaStart < cspec.up) {
                cset = Model.Set.ramp(cid, "Expansion hold", codaStart,
                    RunEdit.dropKept(cspec.lo, codaStart, false), cspec.uh, cspec.lh, cspec.sp,
                    cspec.up, cspec.lo, cspec.uh, cspec.lh, cspec.sp,
                    Math.min(Proto.SLOTS, codaSets), cycle * codaSets);
            } else {
                cset = Model.Set.fixed(cid, "Expansion hold", cspec.up, cspec.lo,
                    cspec.uh, cspec.lh, cspec.sp, cycle * codaSets);
            }
            if (cset != null) {
                cset.fromPlan = true;
                cset.clamp(model.ceilKpa);
                model.sets.add(cset);
                coda.setIds.add(cid);
                // D6: a doubled coda (a girth-focus block) is more cycles than the table has steps -
                // its climb takes one cycle a step and its top step holds the rest (#topStep).
                Model.Set codaTop = topStep(model, cset, cycle);
                if (codaTop != null) coda.setIds.add(codaTop.id);
                r.stages.add(coda);
            }
            // A girth-focus block's expansion is the work the warm-up leads into (R-02's carry).
            if (girthFocus) {
                if (codaClimbStage != null) heldStages.add(codaClimbStage);
                heldStages.add(coda);
                pullKpaHeld = cspec.up;
            }
        }

        /* t10 R-01 - P2 in front of the pulls, ending at 80 % of them (S18, R-03); in front of a
         * girth-focus block's expansion, at the expansion. None on a P4 day (R-05). */
        Model.Stage warm = day.skipWarm ? null
            : warmupStage(model, warmRx, firstPullKpa(model, r), !girthFocus);
        // R-02: where P2 stopped short, the fatigue holds and then the strain holds climb on.
        if (warm != null && p2Applies(model, Plan.TRACK_LENGTH) && !model.legacyT10) {
            int end = lastPullKpa(model, warm);
            int to = Math.min(pullKpaHeld, model.ceilKpa);
            if (end > 0 && end < to)
                lowerHolds(model, heldStages, new CarryRule(end, to, Plan.P2_CARRY_KPA));
        }
        if (warm != null) {
            /* AND IT NAMES THE TUBE IT IS RUN IN. It is the first stage of this routine that
             * commands anything, which makes it the one the pre-run screen names. Without
             * this the confirm read "In: Girth tube" before a traction session, because an
             * unnamed stage falls back to whichever cylinder is active and the active one is
             * usually the girth tube. Caught on the emulator. */
            warm.cylinderId = girthFocus ? gTube : lTube;
            r.stages.add(1, warm);              // straight after the release
        }

        /* NO RETENTION HOLD IN THE LENGTH SESSION (wave 3a, owner ruling). In the guidance's
         * length work, retention means hours of wear, not a ten-minute pump hold — the
         * girth builders keep theirs, this one does not. */

        holdToHardLimits(model, r, Plan.TRACK_LENGTH, day.atMs);
        // t10 R-04 (P1): two hours on the whole clock at most - strain holds off the end.
        if (!model.legacyT10) trimToCap(model, r);
        r.name = Mint.tractionName(rx, loadLb, boreCm, pullHard, girthFocus);
        // THE CHECK PULL NEVER EXCEEDS THE SESSION (wave 1 §4, owner ruling): capped at
        // this routine's own work peak — min never raises, so a re-mint is idempotent.
        int wavePk = model.workPeakKpa(r);
        if (wavePk > 0) r.assess.kpa = Math.min(r.assess.kpa, wavePk);
        // A LENGTH mint checks AFTER only: the girth tube is mounted only after the
        // swap, so the check runs in the expansion coda's tube at the capped pressure.
        // No before-pull in the traction tube, ever.
        r.assess.when = Model.Assess.WHEN_AFTER;
        // ...and with no expansion today (R-06) there is no girth tube to check in: no check.
        if (!withCoda) r.assess.on = false;
        model.routines.add(r);
        return r.id;
    }

    /**
     * ONE TRACTION BLOCK IS ONE SET (wave 3a, owner ruling). The gap between holds is
     * the repetition's own DROP TO 0 — {pull, hold, release, gap} — so the device
     * cycles the block itself: no rest sets inside the stage, no per-hold sets, no
     * StopWork-and-reload at every gap, and a live adjustment carries across the whole
     * block instead of being wiped nine times. The delivered count is the prescribed
     * count by construction: dur = count × (hold + gap), and Set#repCount reads it
     * back out. The trailing release after the last rep stays, by ruling.
     *
     * The rest-set shape this replaces was the F1 regression one level down: ten sets
     * named "Fatigue holds 1..10" interleaved with nine sets all named "Rest", every
     * gap a full table re-upload, and the kicker reading "5/19".
     */
    private static Model.Stage tractionStage(Model model, Model.Routine r, String name,
                                             String setName, Mint.SetSpec spec, int count,
                                             String cylinderId) {
        Model.Stage st = new Model.Stage();
        st.name = name;
        st.colour = Model.STAGE_WORK;
        st.traction = true;
        st.cylinderId = cylinderId;
        int n = Math.max(1, count);
        int cycle = Math.max(1, spec.uh + spec.lh);
        // Model.Set#clamp caps a set at an hour, so a block whose reps exceed it is cut
        // into whole-rep chunks — the same 1 h rule the girth work blocks follow
        // ("Work hold", "Work hold 2"), and the owner ruled the chunks are separate sets.
        int perSet = Math.max(1, 3600 / cycle);
        int made = 0, part = 0;
        while (made < n) {
            int take = Math.min(perSet, n - made);
            part++;
            String id = model.newSetId();
            String nm = setName + (part > 1 ? " " + part : "") + " ×" + take;
            Model.Set hold = Model.Set.fixed(id, nm, spec.up, spec.lo, spec.uh, spec.lh,
                spec.sp, take * cycle);
            hold.fromPlan = true;
            hold.clamp(model.ceilKpa);
            model.sets.add(hold);
            st.setIds.add(id);
            made += take;
        }
        return st;
    }
}
