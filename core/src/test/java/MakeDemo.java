import org.openpump.*;

/**
 * A MODEL WITH A HISTORY IN IT, for screenshots.
 *
 * MakeState builds the minimum a defect needs to be reproduced. This builds what the app
 * looks like to somebody six weeks in: a streak, thirty-odd sessions, a measurement trend
 * with a goal to run at, saved shapes, routines of their own. Every screen that draws a
 * chart, a fraction or a trend has something real to draw.
 *
 * IT MINTS THROUGH RxBuild, like MakeState, so the routines are the routines the app writes
 * - a screenshot of a hand-made fixture would be a screenshot of something the app cannot
 * produce.
 *
 * Nothing here is shipped. It writes a model.json for a device that is being photographed.
 */
public final class MakeDemo {

    private static final long DAY = 24L * 3600 * 1000;

    public static void main(String[] args) throws Exception {
        Model m = new Model();
        long now = System.currentTimeMillis();

        /* ---- the plan: six weeks in, Level 3 girth, length running alongside ---- */
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = now - 44 * DAY;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.weekIndex = 2;
        m.trainerGirth.pressureKpa = 29.0;
        m.trainerGirth.carriedSets = 10;
        m.trainerGirth.weekBaseMs = m.trainerEnrolledAt;
        m.trainerLengthOn = true;
        m.trainerLength.level = Plan.L1;
        m.trainerLength.pressureKpa = 20.0;
        m.rxLengthFirst = true;
        // A second argument turns the simulator on, for the run and summary captures - a
        // live screen needs a pump, and there is not one on a desk.
        m.simPump = args.length > 1;

        /* ---- a week with a shape to it: girth Mon/Thu, length Tue, both Wed ---- */
        for (int i = 0; i < Schedule.DAYS; i++) m.sched.days[i] = true;
        m.sched.days[Schedule.SAT] = false;
        m.sched.days[Schedule.SUN] = false;
        m.sched.plan[Schedule.MON] = Schedule.PLAN_GIRTH;
        m.sched.plan[Schedule.TUE] = Schedule.PLAN_LENGTH;
        m.sched.plan[Schedule.WED] = Schedule.PLAN_BOTH;
        m.sched.plan[Schedule.THU] = Schedule.PLAN_GIRTH;
        m.sched.plan[Schedule.FRI] = Schedule.PLAN_ANY;

        /* ---- the prescriptions ---- */
        Mint.Rx g = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 2, 29.0, 1,
                                   m.ceilKpa, 10, null);
        m.trainerGirth.lastMintId = RxBuild.routineFromRx(m, g);
        m.trainerGirth.lastMintSig = Mint.signature(g);
        m.trainerGirth.lastMintMs = now - 6 * DAY;

        Mint.Rx f = Mint.prescribe(Plan.TRACK_FEEDER, Plan.L3, 0, 22.0, 1,
                                   m.ceilKpa, 0, null);
        m.trainerFeederMintId = RxBuild.routineFromRx(m, f);
        m.trainerFeederMintSig = Mint.signature(f);

        Mint.Rx l = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 20.0, 1,
                                   m.ceilKpa, 0, null);
        m.trainerLength.lastMintId = RxBuild.routineFromRx(m, l);

        /* ---- two of the user's own, starred so Today's favourites have something ---- */
        Model.Routine warm = handRoutine(m, "Morning Warm-up", 14, 90, 10, 5, 4, 60);
        warm.star = true;
        Model.Routine pulse = handRoutine(m, "Pulse Session", 24, 45, 16, 10, 8, 80);
        pulse.star = true;

        m.selected = m.trainerGirth.lastMintId;

        /* ---- saved shapes, so R7's card is not an empty promise ---- */
        m.rxRetention = true; m.rxRetentionMin = 6; m.rxRestSec = 240; m.rxSetsPerBlock = 4;
        m.shapes.add(Model.Shape.capture(m, "sh1", "Long rests"));
        m.rxWarmMin = 8; m.rxEaseHg = 4.0; m.rxRetentionMin = 8;
        m.shapes.add(Model.Shape.capture(m, "sh2", "Marks easily"));
        m.rxSplit = true;
        m.shapes.add(Model.Shape.capture(m, "sh3", "Morning + evening"));
        // Back to the plan's own shape, so the dials on screen are the ordinary ones.
        m.rxWarmMin = 4; m.rxEaseHg = 3.0; m.rxRetention = false; m.rxRestSec = 180;
        m.rxSetsPerBlock = 5; m.rxSplit = false;
        m.clampRxShape();

        /* ---- SIX WEEKS OF SESSIONS. Four a week, net creeping up, one short. ---- */
        String[] ids = { m.trainerGirth.lastMintId, m.trainerLength.lastMintId,
                         m.trainerGirth.lastMintId, m.trainerFeederMintId };
        int n = 0;
        for (int week = 6; week >= 0; week--) {
            for (int k = 0; k < 4; k++) {
                // NOTHING FILED FOR TODAY: the screenshots want Today offering a
                // session, not congratulating one. The most recent is yesterday.
                long ts = now - (week * 7L + (4 - k)) * DAY + 19L * 3600 * 1000;
                if (ts > now) continue;
                Model.Sess s = new Model.Sess();
                s.id = "d" + (n++);
                s.routineId = ids[k % ids.length];
                s.ts = ts;
                s.dayKey = PhotoCalendar.dayKey(ts);
                s.completed = !(week == 3 && k == 2);         // one honest short one
                s.durSec = s.completed ? 2565 : 340;
                double net = 14.0 + (6 - week) * 0.9 + (k % 2) * 0.6;
                s.netTupSec = Double.valueOf((s.completed ? net : 3.1) * 60);
                s.netTargetMin = Double.valueOf(20.0);
                s.grossTupSec = Double.valueOf(net * 60 * 1.35);
                s.peakKpa = Double.valueOf(28.4 + (k % 3) * 0.3);
                s.doseKpaS = 15800 + n * 40;
                s.tag = s.completed ? "no after" : "stopped";
                m.sessLog.all.add(0, s);
            }
        }

        /* ---- A MEASUREMENT TREND, so Progress has a chart rather than an apology ---- */
        double len = 14.8, gir = 12.05;
        for (int week = 6; week >= 0; week--) {
            long ts = now - week * 7L * DAY + 8L * 3600 * 1000;
            if (ts > now) continue;
            Model.Reading r = new Model.Reading();
            r.id = "m" + week;
            r.ts = ts;
            r.method = Model.Reading.METHOD_MSEG;             // girth, at rest
            r.len = len; r.gir = gir;
            r.state = Model.Reading.STATE_HARD;
            r.label = "Weekly";
            m.measLog.all.add(0, r);
            len += 0.05; gir += 0.13;
        }
        m.goalMsegCm = Double.valueOf(13.5);                  // a goal to run at
        m.goalBpsslCm = Double.valueOf(15.5);

        String path = args.length > 0 ? args[0] : "demo.json";
        java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(path), "UTF-8");
        w.write(m.toJson().toString());
        w.close();
        System.out.println("wrote " + path + "  (" + m.routines.size() + " routines, "
            + m.sessLog.all.size() + " sessions, " + m.measLog.all.size() + " readings, "
            + m.shapes.size() + " shapes)");
    }

    /** One of the user's own routines - a plain two-stage build, named like a person names
     *  things rather than like the mint does. */
    private static Model.Routine handRoutine(Model m, String name, int up, int uh,
                                             int lo, int lh, int reps, int sp) {
        Model.Routine r = new Model.Routine();
        r.id = m.newRoutineId();
        r.name = name;
        Model.Set warm = Model.Set.fixed(m.newSetId(), "Ease in", up - 4, lo, 120, 0, 50, 120);
        warm.clamp(m.ceilKpa);
        m.sets.add(warm);
        Model.Set work = Model.Set.fixed(m.newSetId(), "Work", up, lo, uh, lh,
                                         sp, reps * (uh + lh));
        work.clamp(m.ceilKpa);
        m.sets.add(work);
        r.stages.add(Model.Stage.of("Warm-up", Model.STAGE_WARM, new String[]{ warm.id }));
        r.stages.add(Model.Stage.of("Work", Model.STAGE_WORK, new String[]{ work.id }));
        m.routines.add(r);
        return r;
    }
}
