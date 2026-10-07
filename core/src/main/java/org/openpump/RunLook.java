package org.openpump;

/**
 * THE RUN SCREEN'S STEP COLOURS AND ITS STATUS LINE, as pure rules (0.10 run-screen redesign).
 *
 * The owner-approved redesign colours the run by the KIND of step that is playing - warm-up,
 * work, the drop inside a work set, rest, the fatigue block, traction, and Hold - on the
 * status line by default, and optionally as a soft or strong tint over the top of the screen.
 * Everything that decides a colour, a word or whether a colour may be chosen lives here so it
 * can be tested; the app only paints what this returns.
 *
 * WHAT THIS CAN NEVER TOUCH: STOP. The STOP button is drawn in {@link Look#CRITICAL} by the
 * run screen and nothing here has a STOP kind, so no setting can recolour it; and a colour
 * too close to that red is refused ({@link #rejectWhy}), so no step can be mistaken for it.
 * WiringCheck holds both.
 */
public final class RunLook {
    private RunLook() { }

    /* ------------------------------------------------------------------ the kinds */

    public static final int WARM = 0, WORK = 1, DROP = 2, REST = 3, FATIGUE = 4,
                            TRACTION = 5, HOLD = 6;
    public static final int KINDS = 7;

    /** What each kind is called in Settings › Run colours. */
    public static final String[] NAMES = {
        "Warm-up", "Work", "Drop", "Rest", "Fatigue block", "Traction", "Hold"
    };

    /** The status line's word for each kind. */
    public static final String[] WORDS = {
        "WARM-UP", "WORK", "DROP", "REST", "FATIGUE BLOCK", "TRACTION", "HOLD"
    };

    /** The ONE colour no step may take or come near. Not a kind: STOP is not a step. */
    public static final int STOP = Look.CRITICAL;

    /**
     * WHICH KIND A STAGE IS, asked of the stage (the same facts SessionActivity#railColourOf
     * reads). A retention hold, a cool-down and a manual stage are preparation or recovery,
     * and take the warm-up's colour, as the Today rail already groups them.
     */
    public static int kindOf(Model.Stage st) {
        if (st == null) return WORK;
        if (st.rest) return REST;
        if (st.fatigueBlock) return FATIGUE;
        if (st.traction) return TRACTION;
        if (st.manual || st.retention || st.colour == Model.STAGE_WARM
                || st.colour == Model.STAGE_COOL) return WARM;
        return WORK;
    }

    /** The kind the screen shows NOW: a hold outranks everything (it is a state the user
     *  must never miss), then a rest (planned or inserted), then the drop phase of a work
     *  set, then the stage's own kind. */
    public static int liveKind(boolean holding, boolean resting, int stageKind, boolean dropPhase) {
        if (holding) return HOLD;
        if (resting) return REST;
        if (dropPhase && (stageKind == WORK || stageKind == FATIGUE)) return DROP;
        return stageKind;
    }

    /* ---------------------------------------------------------------- the colours */

    public static final int[] DEFAULT = {
        0xFF7CC4F2, 0xFFD4F53C, 0xFF3FD8B8, 0xFF6D8BFF, 0xFFC77DFF, 0xFFF2A0D4, 0xFFE8ECEF
    };
    /** Okabe-Ito, the palette built for the common colour-vision deficiencies. */
    public static final int[] COLOUR_BLIND = {
        0xFF56B4E9, 0xFFE69F00, 0xFF009E73, 0xFFCC79A7, 0xFF0072B2, 0xFFF0E442, 0xFFE8ECEF
    };
    public static final int[] HIGH_CONTRAST = {
        0xFF00E5FF, 0xFFFFFF00, 0xFF00FF7F, 0xFFFF7AF5, 0xFF9D7BFF, 0xFFFFB000, 0xFFFFFFFF
    };
    public static final int[] CALM = {
        0xFF8FB8C9, 0xFFB9D98C, 0xFF6FC7A4, 0xFF9AA6E8, 0xFFE0A6C8, 0xFFE0B98C, 0xFFE8ECEF
    };
    public static final String[] PRESET_NAMES = {
        "Default", "Colour-blind safe", "High contrast", "Calm"
    };
    public static int[] preset(int i) {
        int[] src = i == 1 ? COLOUR_BLIND : i == 2 ? HIGH_CONTRAST : i == 3 ? CALM : DEFAULT;
        return src.clone();
    }

    /** Where the colour shows. The status line only is the default (owner's pick). */
    public static final int WHERE_LINE = 0, WHERE_SOFT = 1, WHERE_STRONG = 2;
    public static final String[] WHERE_NAMES = { "Status line", "+ soft tint", "+ strong tint" };

    /** How strong the tint over the top of the screen is: none on the status line only. */
    public static float tintAlpha(int where) {
        return where == WHERE_STRONG ? 0.32f : where == WHERE_SOFT ? 0.14f : 0f;
    }

    public static int clampWhere(int w) {
        return w == WHERE_SOFT || w == WHERE_STRONG ? w : WHERE_LINE;
    }

    /** Two step colours closer than this (CIE76 ΔE) read as the same step. */
    public static final double MIN_APART = 20.0;
    /** And nothing may come this close to STOP's red. */
    public static final double MIN_FROM_STOP = 30.0;

    /**
     * WHY `candidate` MAY NOT BE THE COLOUR OF `kind`, or null when it may. Refused: a
     * colour near STOP red, and one too close to another kind's colour. Transparent
     * colours are refused too - the status line must be a solid band.
     */
    public static String rejectWhy(int[] colours, int kind, int candidate) {
        if (kind < 0 || kind >= KINDS) return "Not a step kind";
        if ((candidate >>> 24) != 0xFF) return "Pick a solid colour";
        if (deltaE(candidate, STOP) < MIN_FROM_STOP)
            return "Too close to STOP red — red is kept for STOP alone";
        for (int k = 0; k < KINDS; k++) {
            if (k == kind || colours == null || k >= colours.length) continue;
            if (deltaE(candidate, colours[k]) < MIN_APART)
                return "Too close to " + NAMES[k] + " — two steps would look the same";
        }
        return null;
    }

    /** The first problem with a whole set of colours, or null when every one may stand. */
    public static String problem(int[] colours) {
        if (colours == null || colours.length != KINDS) return "Not a full set of colours";
        for (int k = 0; k < KINDS; k++) {
            String why = rejectWhy(colours, k, colours[k]);
            if (why != null) return NAMES[k] + ": " + why;
        }
        return null;
    }

    /** A stored set, taken only when every colour in it may stand; the default otherwise.
     *  A hand-edited file cannot put red on a step or two steps in one colour. */
    public static int[] sanitize(int[] stored) {
        if (stored == null || stored.length != KINDS || problem(stored) != null)
            return DEFAULT.clone();
        return stored.clone();
    }

    /** Colour of `kind` in `colours`, falling back to the default set. */
    public static int colourOf(int[] colours, int kind) {
        int k = kind < 0 || kind >= KINDS ? WORK : kind;
        if (colours == null || colours.length != KINDS) return DEFAULT[k];
        return colours[k];
    }

    /** The ink the status line's words take on a band of `bg`: the app's near-black or its
     *  white, whichever reads better - so any colour a person picks stays legible. */
    public static int inkOn(int bg) {
        return Look.contrastRatio(Look.GROUND, bg) >= Look.contrastRatio(Look.TEXT, bg)
            ? Look.GROUND : Look.TEXT;
    }

    /** "#RRGGBB". */
    public static String hex(int argb) {
        return String.format(java.util.Locale.US, "#%06X", argb & 0xFFFFFF);
    }

    /** "#RRGGBB" or "RRGGBB" as an opaque colour, or null. */
    public static Integer parseHex(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() != 6) return null;
        try {
            return Integer.valueOf(0xFF000000 | Integer.parseInt(t, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** CIE76 colour difference between two sRGB colours (D65). */
    public static double deltaE(int a, int b) {
        double[] x = lab(a), y = lab(b);
        double dl = x[0] - y[0], da = x[1] - y[1], db = x[2] - y[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private static double[] lab(int argb) {
        double r = lin(((argb >> 16) & 0xFF) / 255.0);
        double g = lin(((argb >> 8) & 0xFF) / 255.0);
        double b = lin((argb & 0xFF) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = (0.2126 * r + 0.7152 * g + 0.0722 * b);
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[] { 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz) };
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : (7.787 * t + 16.0 / 116.0);
    }

    /* ------------------------------------------------------------ the status line */

    /** In the last this-many ms of a rest before a pull, the status line pulses and says so;
     *  "Vibrate before the pull" buzzes once as it begins. */
    public static final long PULL_WARN_MS = 10_000L;

    /** What the status line is built from - read off the run each tick. */
    public static final class Now {
        /** A preset is armed and playing. */
        public boolean armed;
        public boolean holding;
        /** A cylinder changeover is waiting for the user's "I've swapped" - or, with
         *  {@link #byHand}, the release's guide time is up and it waits for Done. */
        public boolean awaitingAck;
        /** THE RELEASE IS PLAYING (ByHand#waitsAfterClock): a step done by hand, the pump
         *  vented, its time a guide. The line says BY HAND, never REST, and warns of no pull -
         *  nothing pulls until Done (the owner's decision, 2026-10-07). */
        public boolean byHand;
        /** A rest is playing: the plan's own, or one inserted with Rest. */
        public boolean resting;
        /** Time left in the rest. */
        public long restLeftMs;
        /** The step after the rest commands pressure. */
        public boolean pullNext;
        /** The stage's kind ({@link #kindOf}). */
        public int stageKind = WORK;
        /** The pump is in the drop of a work set. */
        public boolean dropPhase;
        /** Which set of the block is playing, and of how many; 0 when not counted. */
        public int setK, setN;
        /** The commanded pressure, already formatted in the display unit. No longer said on
         *  the status line (0.10 final: the pressure is shown once - the chart's readout is
         *  the live value, the − / + strip the target); kept for the callers that set it. */
        public String pressure = "";
        /** A ramp is playing: the line counts its steps, not sets. */
        public boolean ramp;
        /** Which step of the ramp is playing, and of how many; 0 when not counted. */
        public int stepK, stepN;
        /** The status line is too narrow for the paused line, the changeover's or the by-hand
         *  wait's in full: say it short. */
        public boolean narrow;
    }

    /** True in the last {@link #PULL_WARN_MS} of a rest that a pull follows. */
    public static boolean pullWarning(Now n) {
        return n != null && n.resting && !n.holding && !n.awaitingAck && !n.byHand && n.pullNext
            && n.restLeftMs > 0 && n.restLeftMs <= PULL_WARN_MS;
    }

    /** A time left, rounded UP to the second, as the countdowns print it. */
    static String left(long ms) {
        return Model.Fmt.t((Math.max(0L, ms) + 999L) / 1000L);
    }

    /** What the status line says while the user has paused the run (the pump keeps the
     *  pressure it is reading; the clock waits). */
    public static final String PAUSED = "PAUSED · PRESSURE KEPT · TAP RESUME";
    /** ...and when that does not fit the line (the device check found it wrapping): the
     *  Resume button beside it says the rest. */
    public static final String PAUSED_SHORT = "PAUSED · PRESSURE KEPT";
    /** The changeover's line, and its short form where the line is too narrow (the device
     *  walk's H-4: cut to "CHANGE CYLINDER · TAP I’VE…" at 360 dp); the button says the rest. */
    public static final String SWAP = "CHANGE CYLINDER · TAP I’VE SWAPPED";
    public static final String SWAP_SHORT = "CHANGE CYLINDER";

    /**
     * THE STATUS LINE'S LEFT HALF: the phase, named once - never "STAGE n OF m" (the stage bar
     * above it says that) and never the pressure (0.10 final: the chart's readout is the live
     * value and the − / + strip shows the targets, so the pressure is on the screen once).
     *   work        "WORK · SET 6 OF 10 · HOLD" / "· DROP" (a block's own kind word for the
     *               fatigue block and traction)
     *   ramp        "RAMP · STEP 3 OF 5"
     *   warm-up     "WARM-UP"
     *   rest        "REST · PULL IN 1:46" (unchanged)
     *   by hand     "BY HAND · 4:32 LEFT", then "BY HAND · DONE WHEN YOU ARE" (ByHand)
     *   paused      "PAUSED · PRESSURE KEPT · TAP RESUME"
     * A PAUSE always says so, in words, whatever the colour setting: a run held at pressure
     * must never read as one that is running.
     */
    public static String statusLeft(Now n) {
        if (n == null) return "";
        if (n.holding) return n.narrow ? PAUSED_SHORT : PAUSED;
        // A step done by hand says so, where a rest would say REST (ByHand).
        if (n.byHand && (n.armed || n.awaitingAck))
            return ByHand.status(n.restLeftMs, n.awaitingAck, n.narrow);
        // ...and the changeover's, said short where the line is too narrow for it (H-4).
        if (n.awaitingAck) return n.narrow ? SWAP_SHORT : SWAP;
        if (!n.armed) return "STARTING";
        if (n.resting) {
            if (pullWarning(n)) return "PULL IN " + left(n.restLeftMs);
            if (n.pullNext) return "REST · PULL IN " + left(n.restLeftMs);
            return "REST · " + left(n.restLeftMs) + " LEFT";
        }
        int k = n.stageKind < 0 || n.stageKind >= KINDS ? WORK : n.stageKind;
        if (n.ramp) {
            StringBuilder r = new StringBuilder("RAMP");
            if (n.stepN > 1 && n.stepK >= 1)
                r.append(" · STEP ").append(Math.min(n.stepK, n.stepN)).append(" OF ").append(n.stepN);
            return r.toString();
        }
        if (k == WARM) return WORDS[WARM];
        StringBuilder sb = new StringBuilder(WORDS[k]);
        if (n.setN > 1 && n.setK >= 1)
            sb.append(" · SET ").append(Math.min(n.setK, n.setN)).append(" OF ").append(n.setN);
        sb.append(n.dropPhase && (k == WORK || k == FATIGUE || k == TRACTION)
            ? " · DROP" : " · HOLD");
        return sb.toString();
    }

    /* ------------------------------------------------------- sets inside a block */

    /** How long one set (a pump cycle: the hold and the drop) of a preset lasts, in ms;
     *  0 when the preset does not cycle (a rest, a stitched chunk, no hold). */
    public static long cycleMs(Model.Preset p) {
        if (p == null || p.rest || p.cyclePart || p.uh <= 0) return 0L;
        return (long) (p.uh + Math.max(0, p.lh)) * 1000L;
    }

    /** How many sets a preset runs: its length in cycles, at least 1; 0 when it does not
     *  cycle. Counted by Manual#cycles - the same primitive Model#setsInPreset and the
     *  upcoming list count with, so the status line cannot disagree with them. */
    public static int setsIn(Model.Preset p) {
        if (cycleMs(p) <= 0) return 0;
        return Manual.cycles(p.uh, Math.max(0, p.lh), (int) (p.durMs / 1000L));
    }

    /** Which set (1-based) is playing `elapsedMs` into the preset, never past the last. */
    public static int setAt(Model.Preset p, long elapsedMs) {
        long c = cycleMs(p);
        int n = setsIn(p);
        if (c <= 0 || n <= 0) return 0;
        int k = (int) (Math.max(0L, elapsedMs) / c) + 1;
        return Math.min(k, n);
    }

    /* ------------------------------------------------------------ the NOW card */

    /**
     * THE LINE UNDER THE NOW CARD'S TIME: what this is and what comes next, e.g.
     * "Rest · next: set 6 · 2:00 at −8.0 inHg" or "Work · set 3 of 10 · next: rest 3:00".
     *
     * @param nowWord  "Rest", "Work", "Warm-up" …
     * @param setK     the set playing (0: not counted)
     * @param setN     sets in the block (0: not counted)
     * @param next     the next step's description (see {@link #nextStep}), or null at the end
     */
    public static String nowLine(String nowWord, int setK, int setN, String next) {
        StringBuilder sb = new StringBuilder(nowWord == null ? "" : nowWord);
        if (setN > 1 && setK >= 1)
            sb.append(" · set ").append(Math.min(setK, setN)).append(" of ").append(setN);
        sb.append(" · ").append(next == null ? "then the routine ends" : "next: " + next);
        return sb.toString();
    }

    /** The next step, in words: "rest 3:00", or "set 6 · 2:00 at −8.0 inHg" (a hold of 2:00
     *  at that pull), or its name when it does not cycle. */
    public static String nextStep(Model.Preset p, int firstSetNo, String pressure) {
        if (p == null) return null;
        if (p.rest) return "rest " + Model.Fmt.t(p.durMs / 1000L);
        StringBuilder sb = new StringBuilder();
        if (cycleMs(p) > 0) {
            sb.append(firstSetNo >= 1 ? "set " + firstSetNo : "set").append(" · ")
              .append(Model.Fmt.t(p.uh));
        } else {
            sb.append(p.label == null ? "next step" : p.label.trim());
        }
        if (pressure != null && pressure.length() > 0) sb.append(" at ").append(pressure);
        return sb.toString();
    }

    /* ------------------------------------------ the NOW card, as the mock says it (0.10 final) */

    /** "the end" when nothing follows. */
    private static String orEnd(String next) {
        return next == null ? "the end" : next;
    }

    /**
     * A SET OF A BLOCK: "Hold · set 3 of 10 · next: set 4" - the next set of the SAME block while
     * one is left, and what follows the block after its last set ("rest 1:00", "ramp from
     * −3.0 inHg", "set 6 · 0:40 at −5.9 inHg", "the end").
     */
    public static String nowLineWork(boolean drop, int setK, int setN, boolean lastOfBlock,
                                     String afterBlock) {
        StringBuilder sb = new StringBuilder(drop ? "Drop" : "Hold");
        if (setN > 1 && setK >= 1)
            sb.append(" · set ").append(Math.min(setK, setN)).append(" of ").append(setN);
        sb.append(" · next: ").append(lastOfBlock || setK < 1 ? orEnd(afterBlock)
                                                          : "set " + (setK + 1));
        return sb.toString();
    }

    /** A RAMP STEP: "Step 2 of 5 at −3.9 inHg · next: −4.4 inHg", and after its last step what
     *  follows the ramp. Never a set count: a ramp is steps. */
    public static String nowLineRamp(int stepK, int stepN, String at, String nextStepAt,
                                     String afterRamp) {
        StringBuilder sb = new StringBuilder("Step ").append(stepK).append(" of ").append(stepN);
        if (at != null && at.length() > 0) sb.append(" at ").append(at);
        sb.append(" · next: ").append(stepK < stepN && nextStepAt != null ? nextStepAt
                                                                        : orEnd(afterRamp));
        return sb.toString();
    }

    /** A REST: "next: set 6 · 1:00 at −5.9 inHg" - the card's title already says REST. */
    public static String nowLineRest(String next) {
        return "next: " + orEnd(next);
    }

    /** THE WARM-UP: "Warm-up at −3.0 inHg · next: set 1". */
    public static String nowLineWarm(String at, String next) {
        return "Warm-up" + (at != null && at.length() > 0 ? " at " + at : "") + " · next: "
            + orEnd(next);
    }

    /** What follows, when it is a ramp: "ramp from −3.0 inHg". */
    public static String nextRamp(String from) {
        return "ramp from " + from;
    }

    /** The kind's word in sentence case, for the NOW card: "Warm-up", "Rest" … */
    public static String nowWord(int kind) {
        return kind < 0 || kind >= KINDS ? "Work" : NAMES[kind];
    }
}
