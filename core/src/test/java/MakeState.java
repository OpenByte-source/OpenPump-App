import org.openpump.*;

/**
 * Builds a model.json in the state the user's screenshots show, so the emulator can be put
 * there in one push instead of twenty minutes of tapping. Writes to the path given, or
 * ./model.json.
 *
 * It mints through RxBuild, the same builder the app uses, so the routines on the emulator
 * are the routines the app would have written - not hand-made fakes that would let a layout
 * bug hide behind tidy data.
 */
public final class MakeState {

    public static void main(String[] args) throws Exception {
        Model m = new Model();

        m.trainerEnrolled = true;
        m.trainerEnrolledAt = System.currentTimeMillis() - 40L * 24 * 3600 * 1000;
        m.trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        m.trainerGirth.level = Plan.L3;
        m.trainerGirth.weekIndex = 2;
        m.trainerGirth.pressureKpa = 29.0;               // about 8.6 inHg
        m.trainerGirth.carriedSets = 10;
        m.trainerLengthOn = true;
        m.trainerLength.level = Plan.L1;
        m.trainerLength.pressureKpa = 20.0;              // about 5.9 inHg
        m.rxLengthFirst = true;
        m.simPump = true;               // so a run can be driven with no cuff

        long now = System.currentTimeMillis();
        for (int i = 0; i < Schedule.DAYS; i++) m.sched.days[i] = true;
        m.sched.setOverride(PhotoCalendar.dayKey(now), Schedule.PLAN_BOTH);

        // The three prescriptions the picker showed.
        Mint.Rx g = Mint.prescribe(Plan.TRACK_GIRTH_INTERVAL, Plan.L3, 2, 29.0, 1,
                                   m.ceilKpa, 10, null);
        m.trainerGirth.lastMintId = RxBuild.routineFromRx(m, g);
        m.trainerGirth.lastMintSig = Mint.signature(g);

        Mint.Rx f = Mint.prescribe(Plan.TRACK_FEEDER, Plan.L3, 0, 22.0, 1,
                                   m.ceilKpa, 0, null);
        m.trainerFeederMintId = RxBuild.routineFromRx(m, f);
        m.trainerFeederMintSig = Mint.signature(f);

        Mint.Rx l = Mint.prescribe(Plan.TRACK_LENGTH, Plan.L1, 1, 20.0, 1,
                                   m.ceilKpa, 0, null);
        m.trainerLength.lastMintId = RxBuild.routineFromRx(m, l);

        // The loaded routine, matching the screenshot: girth is what START runs.
        m.selected = m.trainerGirth.lastMintId;

        // Two sessions already this week, so "one more day and this week counts" fires.
        for (int back = 2; back >= 1; back--) {
            Model.Sess s = new Model.Sess();
            s.id = "seed" + back;
            s.routineId = m.trainerGirth.lastMintId;
            s.ts = now - back * 24L * 3600 * 1000;
            s.dayKey = PhotoCalendar.dayKey(s.ts);
            s.durSec = 2400;
            s.completed = true;
            s.netTupSec = Double.valueOf(18.3 * 60);
            s.netTargetMin = Double.valueOf(20.0);
            s.peakKpa = Double.valueOf(28.6);
            s.tag = "no after";
            m.sessLog.all.add(0, s);
        }

        String path = args.length > 0 ? args[0] : "model.json";
        java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(path), "UTF-8");
        w.write(m.toJson().toString());
        w.close();
        System.out.println("wrote " + path + "  (" + m.routines.size() + " routines, "
            + m.sessLog.all.size() + " sessions)");
    }
}
