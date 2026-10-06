import org.openpump.*;

import java.util.ArrayList;
import java.util.List;

/**
 * THE FIVE THINGS THAT COULD ONLY EVER BE CHECKED WITH A CUFF ATTACHED, run.
 *
 * Not assertions about arithmetic - those live in SelfTest. This builds the SAME routines
 * the app builds (RxBuild, the mint lifted out of the Activity), expands them into the
 * SAME preset frames the run sequencer sends, and drives a SimPump with them, printing what
 * the pump did. What comes out is a transcript of a session that never happened, which is
 * the closest thing to a device this repository has.
 *
 * IT FAILS LOUDLY. Every scenario ends in checks; a failure exits non-zero so test.sh
 * stops, exactly like the assertion harness. A transcript nobody would notice was wrong is
 * a transcript, not a test.
 *
 * WHAT IT CANNOT REACH: the screens. The run sequencer's timers, the cards, the buttons and
 * the taps are all on the Activity, and this drives the layer beneath them. It proves the
 * pump is commanded correctly; it does not prove the right button was pressed to command
 * it. That part still wants the phone.
 */
public final class Scenarios {

    private static int checks = 0, failed = 0;

    public static void main(String[] args) {
        // The count is the header's one job; a stale one is the smallest possible version of
        // the defect this whole file exists to catch.
        System.out.println("=== EIGHT SCENARIOS, AGAINST A SIMULATED PUMP ===");
        scenario1WarmUpShape();
        scenario2BothTracksDay();
        scenario3ResumeAfterStopping();
        scenario4ReducedPressure();
        scenario5RestsAndResume();
        scenario6TractionSession();
        scenario7AYearOfTraining();
        scenario8ReportedDeload();
        System.out.println();
        System.out.println("SCENARIOS: " + checks + " checks, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /* ================================================================== helpers */

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failed++; System.out.println("    FAIL: " + what); }
        else System.out.println("    ok   " + what);
    }

    private static void head(String n, String title) {
        System.out.println();
        System.out.println("--- SCENARIO " + n + ": " + title + " ---");
    }

    /** A model set up the way somebody actually training would have it. */
    private static Model enrolled(int level, double kpa) {
        Model m = new Model();
        m.trainerEnrolled = true;
        /* Somebody already training - several of these sit at Level 2, which nobody new to
         * pumping reaches in their first month. Since 0.10 a new person's first month is held
         * to 6 inHg on every build (Scale#hardKpa), so a fixture left at the default "new" with
         * no dates would be that person, and every Level 2 scenario would run at 6 inHg. */
        m.rxNewToPumping = false;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirth.level = level;
        m.trainerGirth.pressureKpa = kpa;
        for (int i = 0; i < Schedule.DAYS; i++) m.sched.days[i] = true;
        return m;
    }

    /** The routine, as the pump would hear it: every preset in order, with the stage it
     *  came from, so a rest is visible as the gap it is. */
    private static void describe(Model m, Model.Routine r) {
        System.out.println("  " + r.name);
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            StringBuilder b = new StringBuilder("    stage " + (i + 1) + "  " + st.name);
            if (st.rest) b.append("  [rest ").append(st.restSec).append("s]");
            if (st.retention) b.append("  [retention, out of net]");
            if (st.fatigueBlock) b.append("  [fatigue, out of net]");
            if (st.colour == Model.STAGE_WARM) b.append("  [warm-up, out of net]");
            System.out.println(b.toString());
            for (int j = 0; j < st.setIds.size(); j++) {
                Model.Set sx = m.set(st.setIds.get(j));
                if (sx == null) continue;
                System.out.println("        " + sx.name + "  ->  up " + sx.up + " kPa for "
                    + sx.uh + "s, lo " + sx.lo + " kPa for " + sx.lh + "s, "
                    + sx.dur + "s total, speed " + sx.sp + "%");
            }
        }
    }

    /** Drive a whole routine through a simulated pump, one preset at a time, exactly as the
     *  sequencer does: clear the table, add the preset, start it, run its duration. A REST
     *  stage sends StopWork and plays nothing, which is what a rest is. */
    private static List<Double> run(Model m, Model.Routine r, SimPump pump) {
        List<Double> trace = new ArrayList<Double>();
        List<Model.Preset> plan = m.plan(r);
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p.rest) {
                pump.write(Proto.stop());
                long left = p.durMs;
                while (left > 0) {
                    long step = Math.min(5000L, left);
                    pump.tick(step);
                    trace.add(Double.valueOf(pump.pressureKpa()));
                    left -= step;
                }
                continue;
            }
            for (int s = 0; s < Proto.SLOTS; s++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(p.sp, p.up, p.uh, p.lo, p.lh));
            pump.write(Proto.startSlot(0));
            long left = p.durMs;
            while (left > 0) {
                long step = Math.min(5000L, left);
                pump.tick(step);
                trace.add(Double.valueOf(pump.pressureKpa()));
                left -= step;
            }
        }
        pump.write(Proto.stop());
        pump.tick(15000L);
        trace.add(Double.valueOf(pump.pressureKpa()));
        return trace;
    }

    private static double peak(List<Double> t) {
        double p = 0;
        for (int i = 0; i < t.size(); i++) p = Math.max(p, t.get(i).doubleValue());
        return p;
    }

    /* ============================================ 6. the length session, actually run */

    /**
     * THE TRACTION SESSION DRIVEN THROUGH A PUMP - the one shape in this app that had never
     * been played to anything.
     *
     * Its blocks are not cycles. A traction set is a HOLD: lh is zero by design and
     * WiringCheck invariant 28 keeps it that way, so the gaps between the ten fatigue holds
     * are separate REST sets inside the stage rather than the low half of a cycle. That
     * distinction is invisible in the arithmetic and total under a cuff: the previous shape
     * built one set of (hold + gap) x count and every phantom gap second became MORE HOLD -
     * a hundred extra seconds of unbroken pull per session, with the routine's own summary
     * still reporting what the prescription asked for. This is the check that would have
     * caught it: play the session and measure the longest unbroken pull.
     */
    /* ======================================= 7. a year of it, week by week ==========
     *
     * THE QUESTION THIS ANSWERS is the one the other six do not: not "is this routine
     * right" but "does following this app for a year take somebody where the guidance says it
     * should". A compliant person trains every scheduled week, meets the net milestone, and
     * never trips a safety flag; the plan is asked each week what to do, the answer is
     * applied, and a routine is minted from the result.
     *
     * WHAT IS APPLIED HERE AND WHY. The half of the app that stores a decision lives in
     * TrainerScreen, which imports android and cannot run in this harness, so this walk
     * applies each decision itself - which makes it a test of the decision engine and of
     * the routines it produces, and NOT of the screen's bookkeeping. Written that way
     * deliberately: a copy of the screen's logic living here would drift from the screen's,
     * and then this would be checking itself.
     */
    private static void scenario7AYearOfTraining() {
        head("7", "a compliant year, week by week - where the plan actually takes somebody");

        Model m = enrolled(Plan.L1, Plan.L1_BAND_LO_KPA);
        m.ceilKpa = 57;                       // a device that is never the binding limit

        int level = Plan.L1;
        double pressure = Plan.L1_BAND_LO_KPA;
        int weekIndex = 1;                    // position in the level's own table
        int sinceDeload = 0;                  // training weeks since the last one
        int atPressure = 0;                   // training weeks at the current pressure
        boolean firstDeloadPending = true;
        int setsDelta = 0;
        /* THE GATE'S THIRD INPUT. gateL1toL2 wants the net milestone AND 8 hg AND the pair
         * HELD for two training weeks - the app derives that last one from the session log
         * ("somebody who has been at this for months does not get sent back to week one").
         * There is no log here, so it is counted the way a log would report it: consecutive
         * training weeks in which both other conditions were true. */
        int gateHeld = 0;

        java.util.List<Integer> deloadAfter = new java.util.ArrayList<Integer>();
        java.util.List<String> levelAt = new java.util.ArrayList<String>();
        int trainingWeeks = 0;
        double startPressure = pressure;
        int routinesBuilt = 0, emptyRoutines = 0, overCap = 0;

        for (int calendarWeek = 1; calendarWeek <= 52; calendarWeek++) {
            int monthIdx = Plan.monthIndex(trainingWeeks);

            Plan.Inputs in = new Plan.Inputs();
            in.track = Plan.TRACK_GIRTH_INTERVAL;
            in.level = level;
            in.monthIndex = monthIdx;
            in.pressureKpa = pressure;
            in.ceilKpa = m.ceilKpa;
            in.weekIndex = weekIndex;
            in.trainingWeeksAtPressure = atPressure;
            in.gateHeldTrainingWeeks = gateHeld;
            in.accumulatedTrainingWeeks = sinceDeload;
            in.firstDeloadPending = firstDeloadPending;
            // A COMPLIANT PERSON: the milestone is met, nothing is flagged, no layoff - and
            // from Level 2 the sessions hold the volume the level is left with (0.10: the
            // L3 and L4 gates ask for it as well as the month).
            in.netTupMin = Math.max(Plan.netMilestoneMin(level), Plan.levelExitNetMin(level));
            in.hasYieldData = false;
            in.redFlag = false;
            in.layoff = false;
            in.underDelivery = false;

            Plan.Decision d = Plan.evaluate(in);

            if (d.action == Plan.ACTION_DELOAD) {
                // A deload week is a week OFF: counters freeze, nothing is prescribed, and
                // the cycle starts again. It is not a training week and must not count as
                // one - that is the whole meaning of "usage-linked".
                deloadAfter.add(Integer.valueOf(sinceDeload));
                sinceDeload = 0;
                firstDeloadPending = false;
                continue;
            }

            trainingWeeks++;
            sinceDeload++;
            atPressure++;
            // Counted AFTER the decision and BEFORE the next one, so the gate is asked with
            // the weeks that have actually been held - never with this week included before
            // it has been trained.
            boolean qualifying = in.netTupMin >= Plan.L1_GATE_NET_MIN
                && Plan.pressureReachedHg(pressure, Plan.L1_GATE_PRESSURE_HG);
            gateHeld = qualifying ? gateHeld + 1 : 0;

            // ONE RUNG. The Decision names the target level in its prose but does not carry
            // it as a field (gateMetNextLevel is private to the engine), and the ladder is
            // sequential - L1, L2, L3, L4 - so a met gate is the next rung up.
            if (d.action == Plan.ACTION_LEVEL_UP && level < Plan.L4) {
                level = level + 1;
                weekIndex = 1;
            } else {
                weekIndex++;
            }
            if (!Double.isNaN(d.pressureKpa) && d.pressureKpa > pressure + 1e-9) {
                pressure = d.pressureKpa;
                atPressure = 0;               // the raise resets its own clock
            }
            setsDelta += d.setsDelta;

            // ...AND THE ROUTINE THAT COMES OUT OF IT HAS TO BE PLAYABLE.
            Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, level, weekIndex,
                                        pressure, monthIdx, m.ceilKpa, 0, d);
            String id = RxBuild.routineFromRx(m, rx);
            Model.Routine r = m.routine(id);
            routinesBuilt++;
            java.util.List<Model.Preset> plan = r == null ? null : m.plan(r);
            if (plan == null || plan.isEmpty()) emptyRoutines++;
            else {
                for (int i = 0; i < plan.size(); i++)
                    if (plan.get(i).up > m.ceilKpa) overCap++;
            }
            if (calendarWeek % 13 == 0)
                levelAt.add("wk " + calendarWeek + ": " + TrainerTab.levelLabel(level)
                    + " at " + Model.Fmt.p(pressure));
        }

        System.out.println("  trained " + trainingWeeks + " weeks, took " + deloadAfter.size()
            + " deloads, built " + routinesBuilt + " routines");
        System.out.println("  deloads came after " + deloadAfter + " training weeks each");
        for (int i = 0; i < levelAt.size(); i++) System.out.println("  " + levelAt.get(i));
        System.out.println("  pressure went " + Model.Fmt.p(startPressure) + " -> "
            + Model.Fmt.p(pressure) + ", volume delta " + setsDelta + " sets");

        check(emptyRoutines == 0,
              "EVERY WEEK OF THE YEAR PRESCRIBED A ROUTINE WITH SETS IN IT - a plan that "
            + "hands somebody an empty session is not a plan (" + emptyRoutines + " empty)");
        check(overCap == 0,
              "and no preset in any of them asked for more than the device ceiling");
        check(deloadAfter.size() >= 10,
              "a year of training takes ten or more deload weeks - roughly one a month, "
            + "which is what all three documents prescribe (got " + deloadAfter.size() + ")");
        check(deloadAfter.get(0).intValue() == Plan.DELOAD_FIRST_AFTER_TRAINING_WEEKS,
              "THE FIRST ONE COMES AFTER FOUR training weeks, as the dated table's "
            + "\"WEEKS 1-4 (Deload: Week 5)\" says");
        check(deloadAfter.get(1).intValue() == Plan.DELOAD_AFTER_TRAINING_WEEKS,
              "...and the second after three, as \"WEEKS 6-8 (Deload: Week 9)\" says");
        check(deloadAfter.get(deloadAfter.size() - 1).intValue()
                == Plan.DELOAD_FIRST_AFTER_TRAINING_WEEKS,
              "...and by the end of the year it is four again - \"MONTHS 4-6 (Weeks 13-24, "
            + "with deloads every 4 weeks)\", and both girth routines' \"every 4 weeks, take "
            + "1 week off\"");
        check(pressure > startPressure + 1e-9,
              "THE PRESSURE ACTUALLY MOVED. A year of meeting the milestone that left "
            + "somebody at their starting pressure would be a plan that cannot progress");
        check(pressure <= Plan.WORKING_CAP_KPA + 1e-9,
              "...and stopped at the working cap the guide gives, rather than climbing "
            + "through it (ended at " + Model.Fmt.p(pressure) + ")");
        check(level > Plan.L1,
              "AND THE LEVEL MOVED TOO - a year of compliant training reaches past L1 "
            + "(ended at " + TrainerTab.levelLabel(level) + ")");
        check(Plan.netMilestoneMin(level) >= Plan.netMilestoneMin(Plan.L1),
              "and the net milestone it is now held to is no lower than the one it started "
            + "at - progress is not a quieter target");
    }

    private static void scenario6TractionSession() {
        head("6", "the length session, played to a cuff");

        // A rack with a tube that pulls and one that fits - the condition the whole shape
        // hangs on, asked here exactly as RxBuild asks it.
        Model m = enrolled(Plan.L1, Plan.L1_FLOOR_KPA);
        m.trainerLengthOn = true;
        Model.Reading g = new Model.Reading();
        g.ts = 1788440800000L;
        g.method = Model.Reading.METHOD_MSEG;
        g.gir = 12.7;
        m.measLog.all.add(g);
        Model.Cylinder lt = new Model.Cylinder();
        lt.id = "L"; lt.label = "Length tube"; lt.role = Model.Cylinder.ROLE_LENGTH; lt.boreCm = 4.0; lt.lengthCm = 23.0;
        Model.Cylinder gt = new Model.Cylinder();
        gt.id = "G"; gt.label = "Girth tube"; gt.boreCm = 4.5; gt.lengthCm = 23.0;
        m.cylinders.add(lt);
        m.cylinders.add(gt);
        check(Traction.pulls(m.tractionFit(12.7)), "the rack really does have a tube that pulls");

        Mint.Rx rx = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 6.0 * Plan.HG, 2,
                                    m.ceilKpa, 0, null);
        Model.Routine r = m.routine(RxBuild.routineFromRx(m, rx));
        describe(m, r);

        int tractionStages = 0;
        for (int i = 0; i < r.stages.size(); i++)
            if (r.stages.get(i).traction) tractionStages++;
        check(tractionStages == 2,
              "it really is the traction shape - a fatigue block and a strain block, not the "
            + "plain expansion session wearing a length label");

        /* WALK IT, MEASURING THE LONGEST UNBROKEN PULL INSIDE THE TRACTION BLOCKS.
         *
         * The blocks only - and that boundary is the point rather than a convenience. The
         * expansion coda is a CYCLED girth set whose low half is 5 kPa, not zero: it does
         * not vent between repetitions, because "stay in the cylinder" is what an interval
         * girth rest IS. Measured across the whole session this scenario would report the
         * coda's 625 s as one unbroken pull and be right about the number and wrong about
         * everything else. A traction block has no low half at all (lh = 0, invariant 28),
         * so "unbroken" means something exact there and nowhere else in this routine. */
        SimPump pump = new SimPump();
        List<Model.Preset> plan = m.plan(r);
        long underLoadMs = 0, worstUnbrokenMs = 0, ventedInsideBlocksMs = 0, codaMs = 0;
        double peakKpa = 0;
        boolean tractionPulled = false;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            boolean inBlock = p.stageIdx >= 0 && p.stageIdx < r.stages.size()
                              && r.stages.get(p.stageIdx).traction;
            if (p.rest) {
                pump.write(Proto.stop());
                long left = p.durMs;
                while (left > 0) {
                    long step = Math.min(1000L, left);
                    pump.tick(step);
                    left -= step;
                    if (pump.pressureKpa() <= 0.05) {
                        underLoadMs = 0;
                        ventedInsideBlocksMs += step;
                    }
                }
                continue;
            }
            for (int sl = 0; sl < Proto.SLOTS; sl++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(p.sp, p.up, p.uh, p.lo, p.lh));
            pump.write(Proto.startSlot(0));
            long left = p.durMs;
            while (left > 0) {
                long step = Math.min(1000L, left);
                pump.tick(step);
                left -= step;
                double at = pump.pressureKpa();
                peakKpa = Math.max(peakKpa, at);
                if (!inBlock) { codaMs += step; underLoadMs = 0; continue; }
                if (at > 0.05) {
                    underLoadMs += step;
                    worstUnbrokenMs = Math.max(worstUnbrokenMs, underLoadMs);
                } else {
                    underLoadMs = 0;
                }
            }
            if (pump.pressureKpa() > 0.05) tractionPulled = true;
        }
        pump.write(Proto.stop());
        pump.tick(15000L);

        System.out.println("  longest unbroken pull in a block " + (worstUnbrokenMs / 1000)
            + "s, peak " + Math.round(peakKpa * 10) / 10.0 + " kPa, vented inside the "
            + "session for " + (ventedInsideBlocksMs / 1000) + "s, coda "
            + (codaMs / 1000) + "s");

        check(tractionPulled, "the session actually pulls - it is not a routine of rests");

        /* NO PRESET ASKS THE WIRE FOR MORE THAN IT CAN CARRY. The strain hold is 300 s and
         * the protocol's field tops out at WIRE_HOLD_MAX - so every strain hold MUST arrive
         * as stitched chunks. Unstitched it is not a long hold, it is a value that does not
         * fit in the field, and what the device does with it is the device's business. */
        int overWire = 0, stitchChunks = 0;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p.uh > Proto.WIRE_HOLD_MAX) overWire++;
            if (p.cyclePart) stitchChunks++;
        }
        check(overWire == 0,
              "every preset's hold fits the wire's own field - a 300s strain hold reaches the "
            + "pump as stitched chunks, never as a number the field cannot hold");
        check(stitchChunks > 0,
              "...and the stitching really did happen here rather than the check passing "
            + "because nothing in this routine was long enough to need it");
        /* THE FATIGUE BLOCK'S OWN LENGTH IS THE BOUND. Ten sixty-second holds with real gaps
         * means no single pull is longer than one hold plus the ramp on to it; the broken
         * shape produced one 700-second pull, which this would have failed on by a factor of
         * five. The bound is generous on purpose - it is testing SHAPE, not a stopwatch. */
        check(codaMs > 0,
              "and the expansion coda really did run after them - the girth work that closes "
            + "a length session, in the girth tube");
        /* THE STRAIN HOLD IS THE LONGEST THING IN A BLOCK, at 300 s. A bound of 330 leaves
         * room for the ramp on to it and nothing else: the broken shape's 700 s would fail
         * this by more than twice over, and so would any future change that quietly folded
         * the gaps back into the hold. */
        check(worstUnbrokenMs <= 330000L,
              "NO SINGLE PULL RUNS LONGER THAN A BLOCK'S OWN HOLD. The shape that built one "
            + "set of (hold + gap) x count turned every gap second into hold and pulled for "
            + "700s unbroken, at the governed load, with nothing on any screen saying so");
        check(worstUnbrokenMs >= Mint.TRACTION_STRAIN_HOLD_SEC * 1000L - 5000L,
              "...and the strain hold is not SHORTER than prescribed either - a bound that "
            + "only caught the long side would pass a block that had stopped pulling");
        check(ventedInsideBlocksMs > 0,
              "and the gaps are REAL - the cuff is at nothing between holds, not merely "
            + "counted as if it were");
        check(pump.pressureKpa() == 0.0, "and the session ends vented");

        /* AND IT NEVER EXCEEDS WHAT THE PLAN WOULD COMMAND. The traction blocks take their
         * pressure from Traction.kpaForLb and the coda from the prescription; both are
         * clamped, and the peak the cuff sees must respect the tighter of the two. */
        check(peakKpa <= m.ceilKpa + 0.5,
              "and nothing in it exceeds the safety ceiling - the pulls and the coda are "
            + "clamped by different rules and the cuff only ever feels the tighter one");
    }

    /* ============================================================== 1. the warm-up */

    private static void scenario1WarmUpShape() {
        head("1", "the warm-up's shape, on a Level 1 routine");
        Model m = enrolled(Plan.L1, Plan.L1_FLOOR_KPA);
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 1,
                                    Plan.L1_FLOOR_KPA, 0, m.ceilKpa, 0, null);
        String id = RxBuild.routineFromRx(m, rx);
        Model.Routine r = m.routine(id);
        describe(m, r);

        Model.Stage first = r.stages.get(0);
        check(first.colour == Model.STAGE_WARM,
              "LEVEL 1 GETS A WARM-UP AT ALL. It used to be skipped here, so the routine a "
            + "brand-new user was handed opened by pulling to the working pressure from cold");
        check(first.outOfNet(),
              "and it is out of net - preparation, not training volume");

        /* t10 R-01 (P2): reps from 3.5 inHg up to the work, at most 1.0 inHg a rep, holds
         * growing from 30 s to 60 s, each with a short drop - not the old flat prime. */
        int top = Math.min(rx.pressureKpa, m.ceilKpa);
        Model.Set rep = m.set(first.setIds.get(0));
        check(rep.up == Math.min(top, Plan.P2_START_KPA),
              "the warm-up starts at 3.5 inHg (" + rep.up + " kPa)");
        Model.Set lastRep = m.set(first.setIds.get(first.setIds.size() - 1));
        check(rep.uh == Plan.P2_HOLD0 && lastRep.endUh() == Plan.P2_HOLD1,
              "its holds grow from 30 s to 60 s");
        check(rep.lh == Plan.P2_DROP_SEC, "with a short drop after each rep");
        check(RxBuild.lastPullKpa(m, first) <= top,
              "and it never goes past the work it leads into");

        SimPump pump = new SimPump();
        List<Double> trace = run(m, r, pump);
        System.out.println("    peak reached: " + Math.round(peak(trace) * 10) / 10.0 + " kPa"
            + "   final: " + Math.round(trace.get(trace.size() - 1).doubleValue() * 10) / 10.0);
        check(peak(trace) <= top + 0.5,
              "and the pump never goes past the prescribed working pressure");
        check(trace.get(trace.size() - 1).doubleValue() == 0.0,
              "and it is vented at the end");
    }

    /* ========================================================= 2. a both-tracks day */

    private static void scenario2BothTracksDay() {
        head("2", "a both-tracks day turning over when the first is filed");
        Model m = enrolled(Plan.L2, Plan.L234_FLOOR_KPA);
        m.trainerLengthOn = true;
        m.rxLengthFirst = true;
        long now = System.currentTimeMillis();
        m.sched.setOverride(PhotoCalendar.dayKey(now), Schedule.PLAN_BOTH);

        UpNext first = TrainerTab.upNextNow(m, now, false);
        System.out.println("  morning:  " + name(first.what) + "  -  " + first.why);
        check(first.what == UpNext.LENGTH,
              "with 'length first' set, the day opens on length");

        // Run it, and file it the way the app files a session.
        Mint.Rx lrx = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L2, 1,
                                     Plan.L234_FLOOR_KPA, 4, m.ceilKpa, 0, null);
        String lid = RxBuild.routineFromRx(m, lrx);
        m.trainerLength.lastMintId = lid;
        SimPump pump = new SimPump();
        List<Double> lt = run(m, m.routine(lid), pump);
        System.out.println("  length run: peak " + Math.round(peak(lt) * 10) / 10.0 + " kPa");
        file(m, lid, now);

        UpNext second = TrainerTab.upNextNow(m, now, false);
        System.out.println("  evening:  " + name(second.what) + "  -  " + second.why);
        check(second.what == UpNext.GIRTH,
              "AND THE MOMENT IT IS FILED, THE OTHER HALF IS THE ANSWER. This is the run "
            + "that used to be invisible: Today went on showing the routine just finished");
        check(second.why.indexOf("length half is done") > 0,
              "with a reason that makes a second routine on one day make sense");

        Mint.Rx grx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 1,
                                     Plan.L234_FLOOR_KPA, 4, m.ceilKpa, 0, null);
        String gid = RxBuild.routineFromRx(m, grx);
        m.trainerGirth.lastMintId = gid;
        file(m, gid, now);
        UpNext done = TrainerTab.upNextNow(m, now, false);
        System.out.println("  after both: " + name(done.what) + "  -  " + done.why);
        check(done.what == UpNext.NOTHING, "and both filed closes the day");
    }

    private static String name(int what) {
        switch (what) {
            case UpNext.GIRTH:     return "GIRTH";
            case UpNext.LENGTH:    return "LENGTH";
            case UpNext.FEEDER:    return "FEEDER";
            case UpNext.REMAINDER: return "FINISH WHAT YOU STOPPED";
            default:               return "nothing";
        }
    }

    private static void file(Model m, String routineId, long ts) {
        Model.Sess s = new Model.Sess();
        s.id = "s" + m.sessLog.all.size();
        s.routineId = routineId;
        s.ts = ts;
        s.dayKey = PhotoCalendar.dayKey(ts);
        s.completed = true;
        Model.Routine r = m.routine(routineId);
        if (r != null && r.netTargetMin >= 0.0) s.netTargetMin = Double.valueOf(r.netTargetMin);
        m.sessLog.all.add(0, s);
    }

    /* ============================================== 3. finishing what you stopped */

    private static void scenario3ResumeAfterStopping() {
        head("3", "stopping part-way, then finishing with and without a warm-up");
        Model m = enrolled(Plan.L2, Plan.L234_FLOOR_KPA);
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 1,
                                    Plan.L234_FLOOR_KPA, 4, m.ceilKpa, 0, null);
        String id = RxBuild.routineFromRx(m, rx);
        System.out.println("  prescribed: " + rx.sets + " cycles of " + rx.holdSec + "s");

        // Stopped with four cycles left. The app mints exactly what remains.
        int left = 4;
        Mint.Rx rest = Mint.remainderOf(rx, left);

        int warmWas = m.rxWarmMin;
        String withWarm = RxBuild.routineFromRx(m, rest);
        // "finish without warming up" - the day says so (t10: P2 does not read the setting).
        String noWarm = RxBuild.routineFromRx(m, rest, 0, 0,
            RxBuild.Day.today(m).sameDay(false, false, true));

        Model.Routine a = m.routine(withWarm), b = m.routine(noWarm);
        System.out.println("  with a warm-up:    " + a.stages.size() + " stages, first is "
            + a.stages.get(0).name);
        System.out.println("  without:           " + b.stages.size() + " stages, first is "
            + b.stages.get(0).name);
        check(a.stages.get(0).colour == Model.STAGE_WARM,
              "the warm-up answer puts a warm-up in front of the remainder");
        check(b.stages.get(0).colour != Model.STAGE_WARM,
              "AND THE OTHER ANSWER DOES NOT. Ten minutes after stopping you are still warm; "
            + "four hours after, you are not, and only the person in the room knows which");
        check(m.rxWarmMin == warmWas,
              "and choosing leaves the SETTING alone - the choice was about today");

        SimPump pump = new SimPump();
        List<Double> t = run(m, b, pump);
        System.out.println("  no-warm-up run: peak "
            + Math.round(peak(t) * 10) / 10.0 + " kPa, ends at "
            + t.get(t.size() - 1).doubleValue());
        check(peak(t) > rx.pressureKpa * 0.6,
              "and it still reaches the working pressure - a remainder is real work");
    }

    /* ================================================ 4. a reduced session's pressure */

    private static void scenario4ReducedPressure() {
        head("4", "what the pump is actually commanded to after a week off");
        Model m = enrolled(Plan.L2, Plan.L234_FLOOR_KPA);
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 1,
                                    Plan.L234_FLOOR_KPA, 4, m.ceilKpa, 0, null);

        String normalId = RxBuild.routineFromRx(m, rx);
        SimPump p1 = new SimPump();
        double normalPeak = peak(run(m, m.routine(normalId), p1));
        System.out.println("  ordinary session:  commanded " + rx.pressureKpa
            + " kPa, pump reached " + Math.round(normalPeak * 10) / 10.0);

        // Now: marks easily, and a week has passed.
        m.marksEasily = true;
        Deload.arm(m, 1L);                       // the taper's first step is what is in force
        System.out.println("  reduction active:  " + Math.round(m.reductionKpa() * 10) / 10.0
            + " kPa (" + Plan.returnTaperHg(0) + " hg)");

        String reducedId = RxBuild.routineFromRx(m, rx);
        Model.Routine red = m.routine(reducedId);
        SimPump p2 = new SimPump();
        double reducedPeak = peak(run(m, red, p2));
        System.out.println("  reduced session:   pump reached "
            + Math.round(reducedPeak * 10) / 10.0 + " kPa");

        check(reducedPeak < normalPeak - 8.0,
              "THE CUFF IS ACTUALLY TAKEN LOWER. Not a note beside the routine - the frames "
            + "on the wire carry the reduced setpoint");
        check(red.netTargetMin == 0.0,
              "AND THE SESSION ASKS FOR NO NET. Below the level floor it produces none, so a "
            + "full target on it is a guaranteed failure and three failures propose stepping "
            + "the level back - the app would punish following the advice");
        check(m.routine(normalId).netTargetMin > 0.0,
              "while the ordinary session still carries its real target");

        // And the same reduction on a SPLIT prescription, which is where it went wrong.
        m.rxSplit = true;
        String halfId = RxBuild.routineFromRx(m, rx, 1, 2);
        check(m.routine(halfId).netTargetMin == 0.0,
              "A REDUCED SPLIT HALF ALSO ASKS FOR NOTHING. The split path recomputed each "
            + "half's target and put a real figure back - the same defect one line later");
        m.rxSplit = false;

        // Off again, and the prescription returns to full.
        Deload.end(m);
        m.marksEasily = false;
        String backId = RxBuild.routineFromRx(m, rx);
        SimPump p3 = new SimPump();
        double backPeak = peak(run(m, m.routine(backId), p3));
        System.out.println("  reduction spent:   pump reached "
            + Math.round(backPeak * 10) / 10.0 + " kPa");
        check(Math.abs(backPeak - normalPeak) < 0.5,
              "and once it is spent the pressure is the prescription's again");
    }

    /* ===================================================== 5. the rests, and resuming */

    private static void scenario5RestsAndResume() {
        head("5", "the rests - the one that bit before");
        Model m = enrolled(Plan.L2, Plan.L234_FLOOR_KPA);
        m.rxSetsPerBlock = 2;            // force several blocks, so rests actually fall
        Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L2, 1,
                                    Plan.L234_FLOOR_KPA, 4, m.ceilKpa, 0, null);
        String id = RxBuild.routineFromRx(m, rx);
        Model.Routine r = m.routine(id);

        int rests = 0;
        for (int i = 0; i < r.stages.size(); i++) if (r.stages.get(i).rest) rests++;
        System.out.println("  " + r.stages.size() + " stages, " + rests + " of them rests");
        check(rests > 0, "the prescription actually contains rests to test");

        // Walk it, watching what the pump does inside every rest.
        SimPump pump = new SimPump();
        List<Model.Preset> plan = m.plan(r);
        double worstInRest = 0.0;
        boolean pulledAfterRest = false;
        double beforeRest = 0.0;
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p.rest) {
                beforeRest = pump.pressureKpa();
                pump.write(Proto.stop());
                long left = p.durMs;
                while (left > 0) {
                    long step = Math.min(2000L, left);
                    pump.tick(step);
                    left -= step;
                    // Skip the first few seconds: venting is a release, not instantaneous.
                    if (p.durMs - left > 12000L)
                        worstInRest = Math.max(worstInRest, pump.pressureKpa());
                }
                System.out.println("    rest " + (p.durMs / 1000) + "s: entered at "
                    + Math.round(beforeRest * 10) / 10.0 + " kPa, held at "
                    + Math.round(worstInRest * 10) / 10.0);
                continue;
            }
            for (int s = 0; s < Proto.SLOTS; s++) pump.write(Proto.deleteSlot(0));
            pump.write(Proto.addPreset(p.sp, p.up, p.uh, p.lo, p.lh));
            pump.write(Proto.startSlot(0));
            double at = pump.pressureKpa();
            long left = p.durMs;
            while (left > 0) {
                long step = Math.min(2000L, left);
                pump.tick(step);
                left -= step;
            }
            if (beforeRest > 0.0 && pump.pressureKpa() > at + 3.0) pulledAfterRest = true;
        }

        check(worstInRest == 0.0,
              "THE PUMP IS AT NOTHING FOR THE WHOLE OF EVERY REST. The bug was the opposite: "
            + "a stop issued and ignored, the cuff still pulling while the countdown said "
            + "rest, and nothing anywhere noticed");
        check(pulledAfterRest,
              "AND IT PULLS AGAIN AFTER ONE. The second bug was silence - the table had been "
            + "rewritten and nothing re-armed, so the routine ran on commanding nothing");

        pump.write(Proto.stop());
        pump.tick(15000L);
        check(pump.pressureKpa() == 0.0, "and the session ends vented");
    }

    /* ==================== 8. a rested week, reported, and the days back ==================== */

    /**
     * A WEEK OFF NOBODY TOLD THE APP ABOUT, then told afterwards — and the three days back.
     *
     * The interesting part is not that the pressure drops; scenario 4 already proves a
     * reduced session commands less. It is that the WEEK is given back, the layoff stops
     * being a layoff, and the three days back command three different pairs of numbers
     * without anybody tapping anything in between.
     */
    private static void scenario8ReportedDeload() {
        head("8", "a rested week, reported afterwards, and the days back");
        long day = 86400000L;
        long base = Deload.dayStartMs(1789000000000L) + 18 * 3600000L;

        Model m = enrolled(Plan.L1, 34.0);
        // A session eight days ago, and nothing since.
        Model.Routine r = m.routine(RxBuild.routineFromRx(m,
            Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 1, 34.0, 0, m.ceilKpa, 0, null)));
        Model.Sess s = new Model.Sess();
        s.ts = base - 8 * day; s.routineId = r.id; s.manual = false;
        m.sessLog.all.add(s);
        check(Deload.layoff(m, base), "eight days with no session is a layoff");

        // The week just gone was charged as missed, the way applyMissPolicy charges it.
        long lastWeek = TrainerTab.mondayStartMs(base) - 7L * day;
        int policy = Plan.missPolicy(4, 1);        // four scheduled days missed, none trained
        Deload.recordCharge(m.trainerGirth, lastWeek, policy == Plan.MISS_STEP_BACK_WEEK ? 2 : 1);
        check(m.trainerGirth.weekRepeats == 2, "a whole week missed holds the plan back a week");

        // The report: the seven days ending yesterday, coming back gently.
        long from = Deload.dayStartPlus(base, -7), to = Deload.dayStartPlus(base, -1);
        long end = Deload.dayStartPlus(to, 1);
        check(Deload.validate(Summary.dayNumber(from), Summary.dayNumber(to),
              Summary.dayNumber(base), 0L) == Deload.REPORT_OK, "the range may be reported");
        m.trainerLastDeloadMs = from;
        m.trainerLastDeloadEndMs = end;
        m.trainerFirstDeloadTaken = true;
        int given = Deload.refund(m.trainerGirth, from, end);
        Deload.arm(m, end);
        System.out.println("  reported " + (Summary.dayNumber(to) - Summary.dayNumber(from) + 1)
            + " days, refunded " + given + " charge(s) - weekRepeats now "
            + m.trainerGirth.weekRepeats);
        check(m.trainerGirth.weekRepeats == 0, "the charged week is given back");
        check(!Deload.layoff(m, base), "and the gap is no longer a layoff");

        /* THE THREE DAYS BACK. Each is minted the one way the app ever mints a routine -
         * RxBuild.routineFromRx, handed nothing but the bare prescription - because that
         * method already asks the MODEL for the taper's own current step (Model#gentleNow,
         * Model#reductionKpa; see RxBuild's own "a gentle day pulls slower as well as
         * lower") the same way a real session build does, off the same state Deload#onFiled
         * advances below. Reducing the Rx by hand out here and THEN handing the reduced Rx
         * to routineFromRx would ask the model for the same cut a SECOND time and stack the
         * two - the two eased days would run at roughly double the documented taper - which
         * is exactly the kind of thing this scenario exists to catch, not something to
         * build into it. */
        int[] speeds = new int[3];
        double[] peaks = new double[3];
        for (int i = 0; i < 3; i++) {
            long when = base + i * day;
            Mint.Rx rx = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L1, 1, 34.0, 0,
                                        m.ceilKpa, 0, null);
            Model.Routine dayR = m.routine(RxBuild.routineFromRx(m, rx));
            Model.Preset work = trainingPreset(dayR, m.plan(dayR));
            speeds[i] = work.sp;
            SimPump pump = new SimPump();
            peaks[i] = peak(run(m, dayR, pump));
            System.out.println("  day " + (i + 1) + " back: commanded " + work.up
                + " kPa at " + work.sp + "%, pump reached "
                + Math.round(peaks[i] * 10) / 10.0);
            Deload.onFiled(m, when);
        }

        check(peaks[0] < peaks[2] && peaks[1] < peaks[2],
              "the first two days back command less pressure than the third");
        check(peaks[0] < peaks[1], "and the first day is the lowest of the three");
        /* PINNED, NOT JUST ORDERED. Three numbers that merely climb would also be produced
         * by a taper that cut the wrong amount, or by no taper at all layered on a level-up
         * - it is the SIZE of the two cuts, measured on the cuff against the day that has
         * none, that is the guidance's own 4 hg then 2 hg, not a stand-in for it. */
        double hg = Model.Fmt.KPA_PER_INHG;
        check(Math.abs((peaks[2] - peaks[0]) - 4.0 * hg) <= 1.0,
              "DAY ONE BACK IS 4 HG UNDER THE WORKING PRESSURE DAY THREE PROVES - "
            + Math.round((peaks[2] - peaks[0]) * 10) / 10.0 + " kPa of cut on the cuff, "
            + "the source's own figure and not merely some lower number");
        check(Math.abs((peaks[2] - peaks[1]) - 2.0 * hg) <= 1.0,
              "...and day two back is 2 hg under it - the taper's second, smaller step");
        check(speeds[0] == Plan.RETURN_POWER_PCT && speeds[1] == Plan.RETURN_POWER_PCT,
              "both reduced days pull at " + Plan.RETURN_POWER_PCT + " %");
        check(speeds[2] == Mint.POWER_PCT, "and the third is back at the plan's speed");
        check(!Deload.armed(m), "the full-pressure day closes the taper");
    }

    /** The first preset that is real training - not a rest, and not from a stage excluded
     *  from net. THE WARM-UP IS DELIBERATELY NOT THIS: RxBuild caps its prime at whatever
     *  the day's own working pressure is and never higher, so it would read as "correct" on
     *  all three days regardless of whether the reduction this scenario exists to prove had
     *  actually happened - it is the main work stage, not the warm-up in front of it, that
     *  a broken taper would leave commanding the wrong number. */
    private static Model.Preset trainingPreset(Model.Routine r, List<Model.Preset> plan) {
        for (int i = 0; i < plan.size(); i++) {
            Model.Preset p = plan.get(i);
            if (p.rest) continue;
            if (p.stageIdx >= 0 && p.stageIdx < r.stages.size()
                    && r.stages.get(p.stageIdx).outOfNet()) continue;
            return p;
        }
        return null;
    }
}
