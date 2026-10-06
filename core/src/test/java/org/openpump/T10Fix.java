package org.openpump;

import java.util.ArrayList;
import java.util.List;

/**
 * t10 lane A - the fixtures the routine-build tests share: the owner's profile (SPEC t8
 * constraints: 7 months, ceiling 12.7 inHg, length cylinder 4.5 cm bore) and the readers that
 * turn a built routine into the figures SPEC.md's acceptance tests state (t10/acc.js, the
 * editor's model): each hold's pull, each stage's holds, the whole clock in minutes.
 */
final class T10Fix {
    private T10Fix() { }

    static final long MIN = 60_000L;
    /** Local noon on a fixed day, so no assertion straddles a midnight. */
    static final long NOW;
    static {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(2026, java.util.Calendar.SEPTEMBER, 15, 12, 0, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        NOW = c.getTimeInMillis();
    }

    /** A session of `r` that ENDED at `endMs`, `durMin` long, filed. */
    static Model.Sess filed(Model m, Model.Routine r, long endMs, int durMin) {
        Model.Sess s = new Model.Sess();
        s.durSec = durMin * 60;
        s.ts = endMs - s.durSec * 1000L;
        s.id = "sess" + s.ts;
        s.routineId = r.id;
        s.routineName = r.name;
        s.completed = true;
        s.dayKey = PhotoCalendar.dayKey(s.ts);
        m.sessLog.file(s);
        return s;
    }

    /** The owner: enrolled, 7 months in, ceiling 12.7 inHg (43 kPa). */
    static Model owner() {
        Model m = new Model();
        m.trainerEnrolled = true;
        m.trainerMonthsPumping = 7;
        m.ceilKpa = 43;
        return m;
    }

    /** A girth interval prescription: `sets` 2-minute holds at `kpa`, the fatigue block from L3. */
    static Mint.Rx girth(int level, int sets, int kpa) {
        return new Mint.Rx(Plan.TRACK_GIRTH_INTERVAL, level, sets, 120,
                           Mint.restSecFor(Plan.TRACK_GIRTH_INTERVAL, level), kpa,
                           level >= Plan.L3, sets * 2.0);
    }

    /** A traditional prescription: `holds` 5-minute holds at `kpa`. */
    static Mint.Rx trad(int level, int holds, int kpa) {
        return new Mint.Rx(Plan.TRACK_GIRTH_TRADITIONAL, level, holds, 300,
                           Mint.restSecFor(Plan.TRACK_GIRTH_TRADITIONAL, level), kpa,
                           level >= Plan.L3, holds * 5.0);
    }

    /** The length prescription the traction builder takes (its coda's), at `kpa`. */
    static Mint.Rx length(int kpa) {
        return new Mint.Rx(Plan.TRACK_LENGTH, Plan.L2, 5, 120, 0, kpa, false, 10.0);
    }

    static RxBuild.Day day(Model m) { return RxBuild.Day.at(m, NOW); }

    static Model.Routine build(Model m, Mint.Rx rx, RxBuild.Day d) {
        return m.routine(RxBuild.routineFromRx(m, rx, 0, 0, d));
    }

    /** The traction session at a pull of `pullKpa` in the owner's 4.5 cm length cylinder. */
    static Model.Routine traction(Model m, int pullKpa, int strainSets, RxBuild.Day d) {
        double lb = Traction.loadLbAtBore(pullKpa, 4.5);
        return m.routine(RxBuild.tractionRoutineFromRx(m, length(pullKpa), lb, 4.5,
                                                       strainSets, false, d));
    }

    /** Each hold's pull in stage `si`, in order (a preset repeats its cycle for its length; a
     *  long hold the wire stitches counts once). */
    static List<Integer> pulls(Model m, Model.Routine r, int si) {
        List<Integer> out = new ArrayList<Integer>();
        Model.Stage st = r.stages.get(si);
        for (int j = 0; j < st.setIds.size(); j++) {
            Model.Set s = m.set(st.setIds.get(j));
            if (s == null || s.rest) continue;
            if (s.ramp) {
                List<Model.Preset> lad = s.ladder();
                int k = Math.max(1, s.rampCycles());
                for (int p = 0; p < lad.size(); p++)
                    for (int c = 0; c < k; c++) out.add(lad.get(p).up);
            } else {
                int n = Math.max(1, s.dur / s.cycle());
                for (int h = 0; h < n; h++) out.add(s.up);
            }
        }
        return out;
    }

    /** Each hold's length, seconds, in stage `si`. */
    static List<Integer> holdSecs(Model m, Model.Routine r, int si) {
        List<Integer> out = new ArrayList<Integer>();
        Model.Stage st = r.stages.get(si);
        for (int j = 0; j < st.setIds.size(); j++) {
            Model.Set s = m.set(st.setIds.get(j));
            if (s == null || s.rest) continue;
            if (s.ramp) {
                int n = s.rampSteps(), k = Math.max(1, s.rampCycles());
                for (int p = 0; p < n; p++)
                    for (int c = 0; c < k; c++) out.add(s.stepUh(p, n));
            } else {
                int n = Math.max(1, s.dur / s.cycle());
                for (int h = 0; h < n; h++) out.add(s.uh);
            }
        }
        return out;
    }

    /** The index of the first stage `pick` says yes to, or -1. */
    static int stage(Model.Routine r, String kind) {
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (kind.equals("warm") && !st.rest && st.colour == Model.STAGE_WARM
                    && !"Ramp-in".equals(st.name)) return i;
            if (kind.equals("rampin") && !st.rest && "Ramp-in".equals(st.name)) return i;
            if (kind.equals("fatigue") && st.fatigueBlock) return i;
            if (kind.equals("strain") && st.traction && !st.fatigueBlock) return i;
            if (kind.equals("swap") && st.rest && st.awaitAck) return i;
        }
        return -1;
    }

    /** The counted work stages (not a warm-up, a fatigue block, a rest, a climb, a retention
     *  or a traction stage), in order. */
    static List<Integer> workStages(Model.Routine r) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < r.stages.size(); i++) {
            Model.Stage st = r.stages.get(i);
            if (st.rest || st.manual || st.colour == Model.STAGE_WARM || st.fatigueBlock
                    || st.climb || st.retention || st.traction) continue;
            out.add(Integer.valueOf(i));
        }
        return out;
    }

    /** Every counted work hold's pull, in order. */
    static List<Integer> workPulls(Model m, Model.Routine r) {
        List<Integer> out = new ArrayList<Integer>();
        List<Integer> ws = workStages(r);
        for (int i = 0; i < ws.size(); i++) out.addAll(pulls(m, r, ws.get(i).intValue()));
        return out;
    }

    /** The whole clock, minutes, to the hundredth as the model prints it. */
    static double minutes(Model m, Model.Routine r) {
        return Math.round(m.durationMs(r) / 600.0) / 100.0;
    }

    static List<Integer> list(int... v) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < v.length; i++) out.add(Integer.valueOf(v[i]));
        return out;
    }

    static List<Integer> repeat(int v, int n) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) out.add(Integer.valueOf(v));
        return out;
    }

    /** Saves `r` as the track's current plan mint, as the app does - id, signature (with the
     *  shape tag of the day), mint time - so the day's run can be built from it. */
    static void asMint(Model m, Model.TrainerTrackState st, Mint.Rx rx, Model.Routine r) {
        st.lastMintId = r.id;
        st.lastMintId2 = "";
        st.lastMintSig = Mint.signature(rx, m.mintShapeTag(rx.track, rx.level, NOW));
        st.lastMintMs = NOW;
        st.level = rx.level;
    }

    /** The owner's rack: a 4.5 cm length cylinder marked for length, a 5.0 cm girth one, and
     *  a girth reading, so the length track pulls. */
    static void rack(Model m) {
        m.trainerLengthOn = true;
        Model.Reading g = new Model.Reading();
        g.ts = NOW - 86400000L;
        g.method = Model.Reading.METHOD_MSEG;
        g.gir = 13.5;
        m.measLog.all.add(g);
        Model.Cylinder lt = new Model.Cylinder();
        lt.id = "L"; lt.label = "Length"; lt.role = Model.Cylinder.ROLE_LENGTH;
        lt.boreCm = 4.5; lt.lengthCm = 23.0;
        Model.Cylinder gt = new Model.Cylinder();
        gt.id = "G"; gt.label = "Girth"; gt.boreCm = 5.0; gt.lengthCm = 23.0;
        m.cylinders.add(lt);
        m.cylinders.add(gt);
    }
}
