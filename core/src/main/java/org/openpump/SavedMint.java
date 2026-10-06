package org.openpump;

import java.util.List;

/**
 * A ROUTINE THE PLAN SAVED - is it still what the plan built, and does it pull harder than
 * today's would?
 *
 * WHY THIS EXISTS (audit D1, D7). The plan keeps its saved routines current: a taper day, a
 * cylinder cut or a plan change rewrites the routine in place - unless the person has edited
 * it, because a routine somebody shaped is theirs. The "has it been edited?" answer used to
 * be a COUNT: how many holds sit at the stored pressure for the prescribed length. That count
 * was blind to everything the builder learned to do since. A ramp's last step and the top-up
 * after it are two presets at the pressure; a traditional 5-minute hold is stitched into
 * shorter wire presets; an ascending climb has one chunk at the top. So a Ramped, traditional
 * or ascending routine read as edited the moment it was saved - and a reduction or a plan
 * change never reached it. A routine meant to run 4 hg lighter on the first day back ran at
 * full pressure, and switching the Program away from Ramped did nothing.
 *
 * THE ANSWER NOW COMES FROM THE BUILDER ITSELF (the owner's decision). The saved routine is
 * compared, stage by stage and preset by preset, with a FRESH BUILD of the prescription it
 * was minted from - into a scratch copy of the model, so nothing lands in the library - in
 * the shape it was minted in, on the day it was minted. The signature stored with the routine
 * records all of that: the prescription (track, level, sets, pressure, hold, fatigue), the
 * Shape screen's and the Program's choices (Model#rxShapeTag), the rack (the traction tag)
 * and the taper step (Deload#stepTag). A routine the builder would make identically is the
 * plan's, whatever shape it has; one it would not make is the person's.
 *
 * WHAT IS COMPARED is what the pump is told: every commanding preset's pressure, drop, hold,
 * drop time, speed and duration, which stages are the fatigue block, the retention hold, the
 * traction blocks and the manual ones, and every rest's length. Names are not (a person may
 * rename a set, and a name commands nothing), nor the cylinder a stage names (that is the
 * rack's, and the rack has its own tag).
 *
 * A TRACTION SESSION IS COMPARED WHOLE (review M5). It used to be compared by its coda alone,
 * so a strain pull the person had lowered read as the plan's and the next plan step wrote it
 * back up. Its pulls take their pressure from the LOAD, which the ladder moves: the rebuild is
 * made at the pull the signature's traction tag records (#applyTractionTag), so a load the
 * ladder has moved since is not mistaken for an edit, and a pull the person moved is one.
 *
 * RESTS ARE COMPARED TOO (review M5), but the rest SETTING is not in the signature (rest is
 * recovery - Model#rxShapeTag), so a routine whose rests are all one length the setting can
 * give is asked again at that setting (#restAsSaved): the setting moving is not an edit.
 *
 * WHAT A DAY'S CUT GOVERNS is narrower (#governedPeakKpa): not a session's pulls, which take no
 * cut - the run-start net weighs only what the cut reaches.
 *
 * PURE: no Android, so the grid of builds this has to answer for is pinned in JUnit
 * (SavedMintTest). SessionActivity asks it; nothing here reads the clock except where a caller
 * passes one in.
 */
public final class SavedMint {

    private SavedMint() { }

    /**
     * THE PRESCRIPTION A SIGNATURE RECORDS, as the plan wrote it - {@link Mint#rxFromSignature}
     * with the track's own rest put back (the signature leaves rest out by rule; the build
     * reads it only to decide where rest stages go, which this class does not compare, but a
     * rebuild should be the same build). Null for an empty or malformed signature.
     */
    public static Mint.Rx rxOf(String sig) {
        Mint.Rx r = Mint.rxFromSignature(sig);
        if (r == null) return null;
        return new Mint.Rx(r.track, r.level, r.sets, r.holdSec,
                           Mint.restSecFor(r.track, r.level), r.pressureKpa, r.fatigue,
                           r.netTargetMin, Mint.POWER_PCT, r.offKpa);
    }

    /**
     * THE PRESCRIPTION A SIGNATURE'S ROUTINE WAS BUILT FROM - {@link #rxOf}, with an "Adjust
     * first..." save's own sets and pressure put in (Mint#adjustedSignature). Null where rxOf
     * is. Whether the pressure takes a bias is {@link #ownKpa}.
     */
    public static Mint.Rx builtRx(String sig) {
        Mint.Rx rx = rxOf(sig);
        Mint.Adjust a = Mint.adjustOf(sig);
        return (rx == null || a == null) ? rx : a.on(rx);
    }

    /** Was the pressure of the routine `sig` records set by the person ("Adjust first...")? */
    public static boolean ownKpa(String sig) {
        Mint.Adjust a = Mint.adjustOf(sig);
        return a != null && a.ownKpa;
    }

    /**
     * THE ADJUSTMENT A TRACK'S SAVED ROUTINE STILL CARRIES TODAY: the one its stored signature
     * records, while today's prescription is still one the save answered (Mint#answers: the
     * offer it adjusted, or that offer at the person's pressure) - as it is, or apart from the
     * day's taper step (Deload#sameApartFromStep). Null when there is none, or when the plan
     * has moved on: a new prescription is a new offer, and the plan's own.
     *
     * So a taper step rebuilds an adjusted routine as the person set it, under the new day's
     * cut (the owner's ruling: the cut and the gentle pull apply as to any trainer routine),
     * and the run-start net weighs it against today's build of the same adjustment.
     */
    public static Mint.Adjust carried(String storedSig, String todaySig) {
        Mint.Adjust a = Mint.adjustOf(storedSig);
        if (a == null || todaySig == null) return null;
        String offer = Mint.withoutAdjust(storedSig), own = Mint.adjustedIdentity(storedSig);
        return offer.equals(todaySig) || own.equals(todaySig)
            || Deload.sameApartFromStep(offer, todaySig)
            || Deload.sameApartFromStep(own, todaySig) ? a : null;
    }

    /**
     * MAY A PLAN CHANGE REWRITE THE ROUTINE SAVED UNDER `storedSig` INTO `todaySig`'S
     * PRESCRIPTION, or must it offer? (Review H2.)
     *
     * A pressure the person set in "Adjust first..." is theirs (the owner's ruling). A taper
     * step keeps it (#carried: rebuilt as they set it, under the new cut). But a NEW
     * prescription is the plan's own, and writing it in place put the Program's bias back on:
     * under Firm, a routine somebody had lowered to 19 was rewritten at the band's top, about
     * 23, with a notice that said only "6 -> 7 sets". Before 0.10 an adjusted save read as edited,
     * so the plan offered instead - and that is what it does again for a save whose pressure is
     * the person's: the new prescription is offered on its card, at the figure it would run at,
     * and the routine they set stays theirs until they take it. A save that moved only the sets
     * left the pressure the plan's, and is rewritten as before.
     */
    public static boolean planMayRewrite(String storedSig, String todaySig) {
        Mint.Adjust a = Mint.adjustOf(storedSig);
        if (a == null || !a.ownKpa) return true;
        return carried(storedSig, todaySig) != null;
    }

    /**
     * WOULD REBUILDING `saved` FOR TODAY TURN IT INTO THE OTHER LENGTH SESSION? (Review M2.) A
     * length routine is rebuilt through RxBuild#routineFromRx, which builds the traction session
     * whenever the rack pulls and the plain one when it does not - so a plain routine rebuilt on
     * a day the rack now pulls came back a traction session (a release by hand, pulls in another
     * cylinder, a tube swap mid-run) under "Rebuilt for today's lighter day". The plan never
     * makes that switch silently (applyPlanTo offers it); the run-start net refuses instead.
     */
    public static boolean switchesShape(Model m, Model.Routine saved, long nowMs) {
        if (m == null || saved == null || saved.trainerTrack != Plan.TRACK_LENGTH) return false;
        return Say.isTraction(saved) != (m.tractionShapeTag(nowMs).length() > 0);
    }

    /** Today's prescription as the saved routine is built from it: `todayRx` with a carried
     *  adjustment's sets and pressure ({@link #carried}), or `todayRx` itself. */
    public static Mint.Rx carriedRx(Mint.Rx todayRx, String todaySig, String storedSig) {
        Mint.Adjust a = carried(storedSig, todaySig);
        return (a == null || todayRx == null) ? todayRx : a.on(todayRx);
    }

    /** The signature to store beside a routine rebuilt from today's prescription: today's,
     *  with a carried adjustment's segment kept on it. */
    public static String carriedSig(String todaySig, String storedSig) {
        Mint.Adjust a = carried(storedSig, todaySig);
        return a == null ? todaySig : todaySig + "|" + a.tag();
    }

    /**
     * HAS THE PERSON EDITED THIS SAVED ROUTINE?
     *
     * `sig` is the signature stored when it was minted, `part`/`ofParts` which half of a split
     * it is (0/0 for a whole session), `mintedAtMs` when it was built (the month's band a
     * Firm or Gentle bias lands inside is that month's). `nowMs` is only for a routine whose
     * signature was CLEARED - an accepted load or strain change, a cylinder answer, an Undo:
     * with no record of what it was built from, it is the plan's only if it is what the plan
     * would build today (`todayRx`) under a cut it could have been built under. Anything that
     * cannot be answered reads as edited: the plan then offers rather than overwrites, and on
     * a reduced day the run-start net ({@link #heavierThanToday}) still stands between the
     * routine and the pump.
     */
    public static boolean edited(Model m, Model.Routine saved, String sig, int part,
                                 int ofParts, long mintedAtMs, Mint.Rx todayRx, long nowMs) {
        if (m == null || saved == null) return false;   // nothing saved, nothing edited
        String have = workPrint(m, saved);
        Mint.Rx asked = builtRx(sig);
        if (asked == null) asked = todayRx;
        if (asked == null) return true;                 // nothing to compare it with
        /* ASKED ON EVERY TRAINER DRAW (the row, whether the track speaks): remembered per input
         * state (BuildCache) - the saved routine's own print is in the key, so an edit to it is
         * a new question. */
        String key = "E|" + BuildCache.stamp(m, asked.track, asked.level, nowMs) + "|" + sig
            + "|" + part + "/" + ofParts + "|" + mintedAtMs + "|" + BuildCache.rx(todayRx)
            + "|" + saved.id + "|" + have;
        Object got = BuildCache.get(key);
        if (got != null) return ((Boolean) got).booleanValue();
        boolean answer = editedBuilt(m, saved, have, sig, part, ofParts, mintedAtMs, todayRx,
                                     nowMs);
        BuildCache.put(key, Boolean.valueOf(answer));
        return answer;
    }

    private static boolean editedBuilt(Model m, Model.Routine saved, String have, String sig,
                                       int part, int ofParts, long mintedAtMs, Mint.Rx todayRx,
                                       long nowMs) {
        Model scratch = scratchOf(m);
        // An "Adjust first..." save is built from the person's own sets and pressure, and is
        // the plan's routine all the same (the owner's ruling, 0.10): its signature records
        // both, so the rebuild is the same build (Mint#adjustedSignature).
        Mint.Rx rx = builtRx(sig);
        if (rx != null) {
            // THE SHAPE IT WAS MINTED IN, not the one the screens hold now: a Program moved
            // from Ramped to fixed is the plan's change to make (D7), not the person's edit.
            applyShapeTag(scratch, sig, rx.track, rx.level);
            // AND THE OFFSET IT WAS SCALED BY (0.10): an offset changed since is the plan's
            // change to make - a new signature, rewritten with a notice - never the person's
            // edit to this routine.
            applyOffsetTag(scratch, sig, rx.track);
            // AND THE LOAD IT PULLED (review M5): the pulls are compared now, and the ladder
            // moves the load - the traction tag records what this routine was built with.
            applyTractionTag(scratch, sig);
            long at = mintedAtMs > 0L ? mintedAtMs : nowMs;
            RxBuild.Day day = new RxBuild.Day(at, Deload.mintCutKpa(m, sig),
                                              Deload.stepHg(sig) > 0.0, focusOf(sig))
                .adjusted(Mint.adjustOf(sig));
            if (have.equals(printOfBuild(scratch, rx, part, ofParts, day))) return false;
            // THE REST PREFERENCE IS NOT IN THE SIGNATURE (Model#rxShapeTag: rest is recovery,
            // and folding it in would re-offer on a cosmetic change). So a rest setting moved
            // since the save is not the person's edit to this routine: asked again with the
            // rest the saved routine's own rests say it was built with.
            Model again = restAsSaved(m, saved, sig, rx.track, rx.level);
            if (again != null && have.equals(printOfBuild(again, rx, part, ofParts, day)))
                return false;
            // AND A HARD LIMIT THAT MOVED IS NOT AN EDIT EITHER (review 2, finding 1).
            return !sameApartFromHardLimits(m, saved, sig, rx, part, ofParts, day, nowMs);
        }
        if (todayRx == null) return true;
        /* A CLEARED SIGNATURE. The cut it was built under is one of: the day's own, none, or
         * the cylinder's - the last two are exactly the before and after of the cylinder
         * answers that clear it. Today's shape: nothing records another. */
        double[] cuts = { m.reductionKpa(nowMs), 0.0,
                          Plan.BIG_CYLINDER_HG * Model.Fmt.KPA_PER_INHG };
        boolean[] gentle = { m.gentleNow(nowMs), false, false };
        Model again = restAsSaved(m, saved, null, todayRx.track, todayRx.level);
        for (int i = 0; i < cuts.length; i++) {
            RxBuild.Day day = new RxBuild.Day(nowMs, cuts[i], gentle[i], -1);
            if (have.equals(printOfBuild(scratch, todayRx, part, ofParts, day))) return false;
            if (again != null
                    && have.equals(printOfBuild(again, todayRx, part, ofParts, day))) return false;
        }
        return true;
    }

    /**
     * DOES `saved` DIFFER FROM ITS REBUILD ONLY WHERE A HARD LIMIT HOLDS ONE OF THEM? (Review 2,
     * finding 1.)
     *
     * The rebuild is made in a copy of TODAY's model, so it carries today's hard limits - the
     * ceiling, "Most you will go to", 15 inHg and a new person's first-month 6 inHg. Lowering
     * "Most", or turning on "New to pumping", made the rebuild of a routine saved before it
     * lower than the routine, so the routine read as EDITED: the plan offered instead of
     * rewriting, and nothing ever brought the saved routine under the new limit. The same for
     * a routine saved by 0.9, which read "Most" at setup only.
     *
     * So both sides are held to today's limits first (RxBuild#holdToHardLimits, the build's own
     * last step): the saved routine as it is, and a rebuild made with the limits lifted - the
     * routine as it was built, whatever limits held then. If they are then the same, nothing
     * but a limit tells them apart, and the plan's rewrite (which builds at today's limits) is
     * what reaches the pump. Lifted two ways - "Most" and the first month only, and the
     * ceiling too - so a build that reads the ceiling for its shape is still matched. An edit
     * that only took a pressure past a limit reads as no edit: it could never run there, and
     * the rewrite is the plan's own at today's limits.
     */
    private static boolean sameApartFromHardLimits(Model m, Model.Routine saved, String sig,
                                                   Mint.Rx rx, int part, int ofParts,
                                                   RxBuild.Day day, long nowMs) {
        int month = TrainerTab.monthIndexNow(m, nowMs);
        int hard = Scale.workHardKpa(m, rx.track, month);   // the coda: girth's too (I3)
        int pull = Scale.pullCapKpa(m, m.lengthBoreCm(), nowMs);
        Model held = scratchOf(m);
        Model.Routine copy = held.routine(saved.id);
        if (copy == null) return false;                 // not in the library: unanswerable
        RxBuild.holdToHardLimits(held, copy, hard, pull);
        String have = workPrint(held, copy);
        for (int ceiling = 0; ceiling < 2; ceiling++) {
            boolean liftCeiling = ceiling == 1;
            Model free = lifted(scratchOf(m), sig, rx.track, rx.level, liftCeiling);
            if (have.equals(heldPrint(free, rx, part, ofParts, day, hard, pull))) return true;
            Model again = restAsSaved(m, saved, sig, rx.track, rx.level);
            if (again != null && have.equals(heldPrint(liftLimits(again, liftCeiling), rx, part,
                                                       ofParts, day, hard, pull))) return true;
        }
        return false;
    }

    /** A scratch model in the shape, offset and load `sig` records, its hard limits lifted. */
    private static Model lifted(Model s, String sig, int track, int level, boolean ceiling) {
        applyShapeTag(s, sig, track, level);
        applyOffsetTag(s, sig, track);
        applyTractionTag(s, sig);
        return liftLimits(s, ceiling);
    }

    /** No "Most you will go to", not new to pumping, and with `ceiling` the device's top
     *  ceiling: the build as no hard limit but 15 inHg would hold it. */
    private static Model liftLimits(Model s, boolean ceiling) {
        s.rxWorkMaxKpa = 0.0;
        s.rxLengthMaxKpa = 0.0;
        s.rxNewToPumping = false;
        if (ceiling) s.ceilKpa = CEILING_TOP_KPA;
        return s;
    }

    /** The highest a device ceiling can be set (Model#clampAll). */
    private static final int CEILING_TOP_KPA = 57;

    /** The build's print in `scratch`, every set held to `hard` / `pull` as a build holds it. */
    private static String heldPrint(Model scratch, Mint.Rx rx, int part, int ofParts,
                                    RxBuild.Day day, int hard, int pull) {
        String id = RxBuild.routineFromRx(scratch, rx, part, ofParts, day);
        Model.Routine r = scratch.routine(id);
        if (r == null) return null;
        RxBuild.holdToHardLimits(scratch, r, hard, pull);
        return workPrint(scratch, r);
    }

    /**
     * A SCRATCH MODEL WHOSE REST SETTING BUILDS THE RESTS `saved` HAS - or null when there is
     * none that could: its rests differ from one another, or the length they share is not one
     * the rest setting can produce for this track. The manual stages (a traction session's
     * release and changeover) are the builder's constants, not the setting's, and are left out.
     * `sig` null: today's shape (a cleared signature records none).
     *
     * So a rest the person changed on its own, or to a length the setting cannot give, is an
     * edit; all of a routine's rests moved together to one the setting can give read the same
     * as the setting having moved - rest is recovery, and a rewrite that puts it back to the
     * setting commands nothing.
     */
    private static Model restAsSaved(Model m, Model.Routine saved, String sig, int track,
                                     int level) {
        int len = -1;
        for (int i = 0; i < saved.stages.size(); i++) {
            Model.Stage st = saved.stages.get(i);
            if (st == null || !st.rest || st.manual) continue;
            if (len < 0) len = st.restSec;
            else if (st.restSec != len) return null;
        }
        if (len < 0) return null;                 // no rests: nothing the setting explains
        Model s = scratchOf(m);
        if (sig != null) {
            applyShapeTag(s, sig, track, level);
            applyOffsetTag(s, sig, track);
            applyTractionTag(s, sig);
        }
        for (int base = REST_SETTING_MIN; base <= REST_SETTING_MAX; base += REST_SETTING_STEP) {
            s.rxRestSec = base;
            s.rxRestSecTrad = base;
            if (s.restSecFor(track) == len) return s;
        }
        return null;
    }

    /** The rest setting's range and step (Model#clampRxShape, the Shape screen's stepper). */
    private static final int REST_SETTING_MIN = 120, REST_SETTING_MAX = 300,
                             REST_SETTING_STEP = 30;

    /**
     * PUTS THE LOAD A SIGNATURE'S TRACTION TAG RECORDS BACK ON A (SCRATCH) MODEL: the strain
     * pull, as the load that makes it in the length cylinder the model holds now (by its bore,
     * owner 2026-09-30), and the strain sets.
     * The tag stores the commanded pull ("T" + kPa : sets : focus : tubes -
     * Model#tractionShapeTag), which is exactly what the builder turns the load back into, so
     * the rebuild pulls what the saved routine was built to pull - whatever the ladder has done
     * to the load since. No tag, or no length cylinder: left as it is.
     */
    static void applyTractionTag(Model s, String sig) {
        String t = segment(sig, 'T');
        if (t == null) return;
        String[] f = t.substring(1).split(":", -1);
        if (f.length < 2) return;
        try {
            int kpa = Integer.parseInt(f[0]);
            int sets = Integer.parseInt(f[1]);
            double bore = s.lengthBoreCm();
            if (kpa < 1 || sets < 1 || bore <= 0) return;
            /* The tag records the pull AS COMMANDED - the length offset in it (0.10) - and the
             * builder adds the offset to the track's load, so the load put back is the pull's
             * less the offset the signature records (Scale#offsetLb). */
            s.trainerLength.loadLb = Traction.loadLbAtBore(kpa, bore)
                - Scale.offsetLb(Scale.offsetOfSig(sig), bore);
            s.trainerLength.strainSets = sets;
        } catch (NumberFormatException e) {
            // an unreadable tag: the model's own load, as before
        }
    }

    /**
     * PUTS THE OFFSET A SIGNATURE RECORDS BACK ON A (SCRATCH) MODEL (0.10): the offset that
     * applied when the routine was built (Scale#offsetTag, Model#mintShapeTag), as the track's
     * own - 0 where the signature records none, so a routine saved before the person set one
     * is rebuilt on the plan's own figure it was built on. The warned state is left alone:
     * nothing it decides reaches a build.
     */
    static void applyOffsetTag(Model s, String sig, int track) {
        if (s == null) return;
        Model.TrainerTrackState st = Scale.stateOf(s, track);
        if (st == null) return;
        st.offsetKpa = Scale.offsetOfSig(sig);
        /* The applied figure is what was recorded; a first month that no longer applies it
         * cannot be reproduced by the setting alone, so the scratch says "not new" when the
         * recorded offset is non-zero (a new person's first month records none). */
        if (st.offsetKpa != 0.0) s.rxNewToPumping = false;
    }

    /**
     * THE RUN-START NET: does `saved` pull harder than the routine the plan would build for
     * today from `todayRx`, in the blocks the day's reduction governs?
     *
     * Asked on a reduced day, when a plan routine is started. A routine the rewrite could not
     * reach - one the person edited, or one saved before a fix - may still hold at full
     * pressure on a day the plan wants lighter, and a reduction reaches the pump only through
     * the routine (nothing lowers it at run time). The yardstick is today's own build rather
     * than the bare reduced figure, so a Firm bias or a ramp's shape that today's build would
     * command anyway is never mistaken for a routine that is too heavy.
     */
    public static boolean heavierThanToday(Model m, Model.Routine saved, Mint.Rx todayRx,
                                           int part, int ofParts, long nowMs) {
        return heavierThanToday(m, saved, todayRx, part, ofParts, nowMs, false);
    }

    /** The same, where `todayRx`'s pressure is the person's ({@link #carried}): today's build
     *  of it takes the day's cut and no bias, as the saved routine did. */
    public static boolean heavierThanToday(Model m, Model.Routine saved, Mint.Rx todayRx,
                                           int part, int ofParts, long nowMs, boolean ownKpa) {
        return heavierThanToday(m, saved, todayRx, part, ofParts,
                                RxBuild.Day.at(m, nowMs).ownPressure(ownKpa));
    }

    /** The same, where `todayRx` carries an "Adjust first..." save's sets and pressure
     *  (`kept`, {@link #carried}; null for none): today's build of that adjustment. */
    public static boolean heavierThanToday(Model m, Model.Routine saved, Mint.Rx todayRx,
                                           int part, int ofParts, long nowMs, Mint.Adjust kept) {
        if (m == null) return false;
        return heavierThanToday(m, saved, todayRx, part, ofParts,
                                RxBuild.Day.at(m, nowMs).adjusted(kept));
    }

    private static boolean heavierThanToday(Model m, Model.Routine saved, Mint.Rx todayRx,
                                            int part, int ofParts, RxBuild.Day day) {
        if (m == null || saved == null || todayRx == null) return false;
        Model scratch = scratchOf(m);
        String id = RxBuild.routineFromRx(scratch, todayRx, part, ofParts, day);
        Model.Routine today = scratch.routine(id);
        if (today == null) return false;
        return governedPeakKpa(m, saved) > governedPeakKpa(scratch, today);
    }

    /**
     * REWRITE A SAVED ROUTINE INTO A NEW PRESCRIPTION, in place - built today.
     *
     * Built by minting into a scratch routine and transplanting its stages, so there is ONE
     * definition of what a prescription looks like - RxBuild - rather than a second one here
     * that could drift from it. The identity that matters is kept: the id Today selects by,
     * and a name the person chose.
     *
     * THE NET TARGET GOES WITH THE STAGES. It is stamped at birth so a routine is scored
     * against what IT was asked for (Model.Routine#netTargetMin), and a rewrite is a new
     * birth: a routine rewritten for a reduced day asks for no net, and one rewritten to seven
     * sets asks for seven sets' worth. Keeping the old figure scored a taper day's lighter
     * session against the full target it was never asked to reach - the under-delivery the
     * target exists to stop counting.
     */
    public static boolean rewrite(Model m, Model.Routine target, Mint.Rx rx, int part,
                                  int ofParts) {
        return rewrite(m, target, rx, part, ofParts, false);
    }

    /** The same rewrite, where `rx`'s pressure is the person's - an "Adjust first..." routine
     *  a taper step rebuilds ({@link #carried}): the day's cut, and no bias. */
    public static boolean rewrite(Model m, Model.Routine target, Mint.Rx rx, int part,
                                  int ofParts, boolean ownKpa) {
        if (m == null) return false;
        return rewrite(m, target, rx, part, ofParts, RxBuild.Day.today(m).ownPressure(ownKpa));
    }

    /** The same rewrite, where `rx` carries an "Adjust first..." save's sets and pressure
     *  (`kept`, {@link #carried}; null for none) - their pressure unbiased, their count kept
     *  (a hybrid of theirs keeps its lower count of holds: review M4). */
    public static boolean rewrite(Model m, Model.Routine target, Mint.Rx rx, int part,
                                  int ofParts, Mint.Adjust kept) {
        if (m == null) return false;
        return rewrite(m, target, rx, part, ofParts, RxBuild.Day.today(m).adjusted(kept));
    }

    private static boolean rewrite(Model m, Model.Routine target, Mint.Rx rx, int part,
                                   int ofParts, RxBuild.Day day) {
        if (m == null || target == null || rx == null) return false;
        String freshId = RxBuild.routineFromRx(m, rx, part, ofParts, day);
        Model.Routine fresh = m.routine(freshId);
        if (fresh == null) return false;
        java.util.List<String> oldSets = setIdsOf(target);
        /* AND ITS NAME, IF THE NAME IS THE APP'S. A minted label states the prescription, so a
         * rewrite that kept it left the routine announcing the prescription it no longer runs,
         * on Today, in the library and in the pre-run confirm. A name somebody chose themselves
         * is untouched. */
        if (Mint.isMintedName(target.name) && Mint.isMintedName(fresh.name))
            target.name = fresh.name;
        target.stages.clear();
        for (int i = 0; i < fresh.stages.size(); i++) target.stages.add(fresh.stages.get(i));
        target.trainerLevel = rx.level;
        target.trainerTrack = rx.track;
        target.netTargetMin = fresh.netTargetMin;
        // ...and the climb minutes it leaves out of that target, which the level credits.
        target.climbUnderLineMin = fresh.climbUnderLineMin;
        // The scale it was rebuilt on, so its sessions count from the line it now runs at.
        target.trainerScaleKpa = fresh.trainerScaleKpa;
        /* THE CHECK PULL COMES DOWN WITH THE WORK. The builder caps a new routine's check pull
         * at its own work peak (the owner's wave-1 ruling: the check never exceeds the
         * session); a rewrite kept the old routine's figure, so a taper day's lighter session
         * could still open or close on a pull at the old, heavier pressure. Capped the same
         * way - min, so it never raises a pull the person set lower, and a later rewrite back
         * to full pressure does not guess what it used to be. */
        int workPeak = m.workPeakKpa(target);
        if (target.assess != null && workPeak > 0)
            target.assess.kpa = Math.min(target.assess.kpa, workPeak);
        // The scratch routine goes; its stages now belong to the real one.
        m.routines.remove(fresh);
        // ...and the sets the old shape used, if nothing else still plays them. A library
        // that grows a "Work hold" every fortnight is the pile this was meant to end.
        for (int i = 0; i < oldSets.size(); i++) {
            Model.Set st = m.set(oldSets.get(i));
            if (st == null || !st.fromPlan) continue;
            if (m.usedIn(st.id) == 0) m.sets.remove(st);
        }
        return true;
    }

    /* ------------------------------------------------------------------ the comparison */

    /**
     * WHAT THE ROUTINE TELLS THE PUMP, as one string - stage by stage, preset by preset, over
     * the blocks this class compares (see the class comment). Two routines with the same
     * print command the same session.
     */
    public static String workPrint(Model m, Model.Routine r) {
        StringBuilder b = new StringBuilder();
        if (m == null || r == null) return "";
        List<Model.Preset> plan = m.plan(r);
        int lastStage = -1;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            Model.Stage st = stageOf(r, p);
            if (st == null) continue;
            if (p.stageIdx != lastStage) {
                b.append('[').append(st.fatigueBlock ? 'F' : '-')
                 .append(st.retention ? 'R' : '-').append(st.traction ? 'T' : '-')
                 .append(st.rest ? 'r' : '-').append(st.manual ? 'm' : '-')
                 .append(st.awaitAck ? 'a' : '-').append(st.climb ? 'c' : '-').append(']');
                lastStage = p.stageIdx;
            }
            // A REST IS ITS LENGTH (review M5): nothing is commanded in it, but a rest the
            // person lengthened or shortened is theirs, and a rewrite would put it back.
            if (st.rest || p.rest) { b.append("rest/").append(p.durMs).append(';'); continue; }
            b.append(p.up).append('/').append(p.lo).append('/').append(p.uh).append('/')
             .append(p.lh).append('/').append(p.sp).append('/').append(p.durMs)
             .append(p.cyclePart ? "c;" : ";");
        }
        return b.toString();
    }

    /** The deepest pull in the blocks the day's reduction governs: every commanding block
     *  but, in a session that pulls, the load's blocks and the warm-up that primes for them -
     *  those take their pressure from the load, which no cut reaches. 0 for a routine with
     *  none. */
    public static int governedPeakKpa(Model m, Model.Routine r) {
        if (m == null || r == null) return 0;
        boolean pulls = Say.isTraction(r);
        List<Model.Preset> plan = m.plan(r);
        int pk = 0;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (governed(stageOf(r, p), p, pulls) && p.up > pk) pk = p.up;
        }
        return pk;
    }

    private static Model.Stage stageOf(Model.Routine r, Model.Preset p) {
        return p.stageIdx >= 0 && p.stageIdx < r.stages.size() ? r.stages.get(p.stageIdx) : null;
    }

    /** A preset the day's cut governs: it commands, and - in a session that pulls - it is not
     *  one of the load's blocks or the warm-up that primes for them. (#workPrint compares
     *  every stage: review M5.) */
    private static boolean governed(Model.Stage st, Model.Preset p, boolean pulls) {
        if (st == null || st.rest || p.rest) return false;
        if (pulls && (st.traction || st.colour == Model.STAGE_WARM)) return false;
        return true;
    }

    /** The build's print, made in `scratch` - which is the scratch model's to keep. */
    private static String printOfBuild(Model scratch, Mint.Rx rx, int part, int ofParts,
                                       RxBuild.Day day) {
        String id = RxBuild.routineFromRx(scratch, rx, part, ofParts, day);
        return workPrint(scratch, scratch.routine(id));
    }

    /**
     * A COPY OF THE MODEL TO BUILD INTO - the persisted form read back, the same road a
     * restart takes - so a comparison can never leave a routine, a set or a changed preference
     * in the person's own library.
     *
     * WITHOUT THE SESSION LOG. It is most of the file - years of sessions are megabytes - and
     * nothing a build reads is in it (the day's cut and speed arrive as an RxBuild.Day). The
     * Trainer asks this on every draw, and a full copy of a four-year history measured 70 ms
     * a time on a desktop: a stutter on a phone, several times over per screen. The log is
     * set aside for the one call that writes the copy and put back in a finally, on the one
     * thread that owns the model.
     */
    static Model scratchOf(Model m) {
        Model.SessLog log = m.sessLog;
        String json;
        m.sessLog = new Model.SessLog();
        try {
            json = m.toJson();
        } finally {
            m.sessLog = log;
        }
        Model s = Model.fromJson(json);
        MonthBreak.copyTransient(m, s);     // t10-K: today's gentle-week warm-up, never saved
        return s;
    }

    private static java.util.List<String> setIdsOf(Model.Routine r) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (int i = 0; i < r.stages.size(); i++) {
            java.util.List<String> ids = r.stages.get(i).setIds;
            for (int j = 0; j < ids.size(); j++)
                if (!out.contains(ids.get(j))) out.add(ids.get(j));
        }
        return out;
    }

    /* ------------------------------------------------------------- the minted shape */

    /** The girth-focus flag a signature's traction tag carries: 1, 0, or -1 when it has none
     *  (ask the track). "T" + strain kPa : strain sets : focus : tubes - Model#tractionShapeTag. */
    static int focusOf(String sig) {
        String t = segment(sig, 'T');
        if (t == null) return -1;
        String[] f = t.substring(1).split(":", -1);
        if (f.length < 3) return -1;
        return "1".equals(f[2]) ? 1 : ("0".equals(f[2]) ? 0 : -1);
    }

    /** A token of a signature's shape segment (Model#rxShapeTag) that starts with `lead` and a
     *  digit - "Y80.2.100.1.1", "G1355.60.100", "P21111" - or null where it has none. */
    static String shapeToken(String sig, char lead) {
        String tag = segment(sig, 'S');
        if (tag == null) return null;
        String[] f = tag.substring(1).split(":", -1);
        for (int i = 0; i < f.length; i++)
            if (f[i].length() > 1 && f[i].charAt(0) == lead && Character.isDigit(f[i].charAt(1)))
                return f[i];
        return null;
    }

    /** The first segment after the prescription's six that starts with `lead` and a digit. */
    private static String segment(String sig, char lead) {
        if (sig == null) return null;
        String[] segs = sig.split("\\|", -1);
        for (int i = 6; i < segs.length; i++) {
            String s = segs[i];
            if (s.length() > 1 && s.charAt(0) == lead && Character.isDigit(s.charAt(1)))
                return s;
        }
        return null;
    }

    /**
     * PUTS THE SHAPE A SIGNATURE RECORDS BACK ON A (SCRATCH) MODEL - Model#rxShapeTag read
     * backwards, field for field and under the same relevance rules, so it can only set what
     * the tag could have said. No shape segment means every relevant choice was at its
     * default when the routine was minted (the tag is empty exactly then). A choice the tag
     * does not carry for this track cannot reach this track's build and is left alone.
     * Returns false - and leaves the model as it found it - for a segment it cannot read.
     */
    static boolean applyShapeTag(Model s, String sig, int track, int level) {
        // t10-K (B3): the gentle week after the month-12 break, as the signature records it.
        MonthBreak.fromSig(s, sig);
        String tag = segment(sig, 'S');
        int warmMin = 4, steps = 4, prime = 1355, ease = 300, retK = 1350, retMin = 5;
        int hold = 0, per = 5;
        // R11-5: a tag from before hold lengths built the fatigue block at 45 s.
        int fat = Mint.HOLD_FATIGUE_SEC;
        boolean ramp = false, ret = false, hybrid = false, t10 = false;
        int hybridRun = -1;     // the hybrid's holds run, where the tag says (review B F5)
        String prog = "";
        // 0.10 - the ramp settings (Model#rampTag) and the gentle warm-up (Model#gentleWarmTag).
        String rampT = null, gentleT = null;
        if (tag != null) {
            try {
                String[] f = tag.substring(1).split(":", -1);
                int k = 0;
                warmMin = Integer.parseInt(f[k++]);
                if (warmMin > 0) {
                    ramp = "1".equals(f[k++]);
                    prime = Integer.parseInt(f[k++]);
                    ease = Integer.parseInt(f[k++]);
                    if (ramp) steps = Integer.parseInt(f[k++]);
                }
                if (k < f.length && f[k].startsWith("R")) {
                    ret = "R1".equals(f[k++]);
                    if (ret) {
                        retK = Integer.parseInt(f[k++]);
                        retMin = Integer.parseInt(f[k++]);
                    }
                }
                for (; k < f.length; k++) {
                    String x = f[k];
                    if ("HY".equals(x)) hybrid = true;
                    else if (x.startsWith("HY")) {
                        hybrid = true;
                        hybridRun = Integer.parseInt(x.substring(2));
                    }
                    else if (Model.T10_BUILD_TOKEN.equals(x)) t10 = true;
                    else if (x.startsWith("H")) hold = Integer.parseInt(x.substring(1));
                    else if (x.startsWith("F")) fat = Integer.parseInt(x.substring(1));
                    else if (x.startsWith("B")) per = Integer.parseInt(x.substring(1));
                    else if (x.startsWith("P") && x.length() == 6) prog = x;
                    else if (x.startsWith("Y")) rampT = x;
                    else if (x.startsWith("G")) gentleT = x;
                    else return false;
                }
            } catch (RuntimeException e) {
                return false;          // NumberFormat, or a segment cut short
            }
        }
        // Read whole before anything is set, so an unreadable token leaves the model alone.
        int[] rampV = rampT == null ? null : tokenInts(rampT, 5);
        int[] gentleV = gentleT == null ? null : tokenInts(gentleT, 3);
        if ((rampT != null && rampV == null) || (gentleT != null && gentleV == null))
            return false;
        if (track != Plan.TRACK_FEEDER) {
            s.rxWarmMin = warmMin;
            if (warmMin > 0) {
                s.rxWarmRamp = ramp;
                s.rxPrimeKpa = hundredths(s.rxPrimeKpa, prime);
                s.rxEaseHg = hundredths(s.rxEaseHg, ease);
                if (ramp) s.rxWarmSteps = steps;
            }
            s.rxRetention = ret;
            if (ret) {
                s.rxRetentionKpa = hundredths(s.rxRetentionKpa, retK);
                s.rxRetentionMin = retMin;
            }
        }
        if (track == Plan.TRACK_GIRTH_INTERVAL && level >= Plan.L3) s.rxHoldSec = hold;
        if ((track == Plan.TRACK_GIRTH_INTERVAL || track == Plan.TRACK_GIRTH_TRADITIONAL)
                && Plan.fatigueBlockPresent(level)) s.rxFatigueHoldSec = fat;
        if (track == Plan.TRACK_GIRTH_INTERVAL) s.rxSetsPerBlock = per;
        if (track == Plan.TRACK_GIRTH_INTERVAL || track == Plan.TRACK_GIRTH_TRADITIONAL)
            s.trainerGirthHybrid = hybrid;
        /* THE HYBRID'S HOLDS IT RAN (review B F5): kept holds that build that count - none when
         * the count is the level's own or under it (the time cap holds it there either way). */
        if (hybridRun >= 0 && track == Plan.TRACK_GIRTH_INTERVAL && level >= Plan.L3
                && s.trainerGirth != null)
            s.trainerGirth.hybridYield = Math.max(0, hybridRun - Plan.hybridHolds(level, 0));
        Model.Program p = s.programFor(track);
        if (p != null) {
            Model.Program d = new Model.Program();      // the defaults, for an empty tag
            if (prog.length() == 6) {
                d.warm = prog.charAt(1) - '0';
                d.work = prog.charAt(2) - '0';
                d.pressure = prog.charAt(3) - '0';
                d.rest = prog.charAt(4) - '0';
                d.fatigue = prog.charAt(5) - '0';
            }
            p.warm = d.warm; p.work = d.work; p.pressure = d.pressure;
            p.rest = d.rest; p.fatigue = d.fatigue;
        }
        /* 0.10 - THE RAMP SETTINGS (Model#rampTag). A Ramped signature with no ramp token was
         * minted before the settings existed: the scratch builds it the way those builds did
         * (Model#legacyRamp), so the routine the plan saved then reads as the plan's - and the
         * new settings reach it as a rewrite with a notice, never as an "edited" routine left
         * behind. */
        s.legacyRamp = rampV == null && p != null && p.work == Model.Program.WORK_RAMP_IN_SET;
        /* t10 - A SIGNATURE WITH NO BUILD TOKEN (Model#T10_BUILD_TOKEN) was minted before the
         * trainer's rules of 1 Oct 2026: the scratch builds it the way those builds did
         * (Model#legacyT10) - the old warm-up, rests and hybrid - so the routine the plan saved
         * then reads as the plan's, and the new build reaches it as a rewrite with a notice. */
        s.legacyT10 = !t10 && track != Plan.TRACK_FEEDER;
        if (rampV != null) {
            s.rampStartPct = rampV[0];
            s.rampShortSteps = rampV[1];
            s.rampStepHg = hundredths(s.rampStepHg, rampV[2]);
            s.rampLighterDays = rampV[3] == 1;
            s.rampCountClimb = rampV[4] == 1;
        }
        /* ...AND THE GENTLE WARM-UP (Model#gentleWarmTag): "I mark or bruise easily" as it built
         * this routine - on, with its settings, where the token is there; off where it is not
         * (minted before the gentle warm-up, or with the answer off: warmed up the ordinary
         * way). Nothing else a build reads comes from that answer - the cylinder's cut it also
         * governs reaches a build as the day's cut. */
        if (track != Plan.TRACK_FEEDER) {
            s.marksEasily = gentleV != null;
            if (gentleV != null) {
                s.gentleWarmStartKpa = hundredths(s.gentleWarmStartKpa, gentleV[0]);
                s.gentleWarmSpeedPct = gentleV[1];
                s.gentleWarmStepHg = hundredths(s.gentleWarmStepHg, gentleV[2]);
            }
        }
        return true;
    }

    /** A token's dot-separated whole numbers after its letter - "Y80.2.100.1.1" - exactly `n`
     *  of them, or null for one that cannot be read. */
    private static int[] tokenInts(String token, int n) {
        String[] f = token.substring(1).split("[.]", -1);
        if (f.length != n) return null;
        int[] out = new int[n];
        try {
            for (int i = 0; i < n; i++) out[i] = Integer.parseInt(f[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** A preference the tag stores in whole hundredths: kept exactly as it is when it already
     *  says the same thing, so a value the tag had to round is not moved by reading it back. */
    private static double hundredths(double current, int tagged) {
        return Math.round(current * 100.0) == tagged ? current : tagged / 100.0;
    }
}
