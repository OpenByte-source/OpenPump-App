package org.openpump;

/**
 * The decidable half of Task 17's visual and motion pass, kept PURE (no `import android`)
 * so test.sh compiles it and SelfTest can assert on it. Ui.java holds the view builders
 * and needs android.widget; this holds the NUMBERS those builders use — the palette, the
 * type scale, the motion durations and their resolution against the system animation
 * setting — plus a real WCAG contrast computation. None of that needs a device, and a
 * contrast ratio that computes the true value and fails below AA is worth having and is
 * entirely pure, exactly as the brief asks.
 *
 * Nothing here is seen by the harness as PIXELS — the harness cannot see a colour on a
 * screen. What it can see, and what is pinned here, is that the palette hexes are the ones
 * the brief specifies, that the type scale is the one scale, that a duration collapses to
 * zero when the user has turned animations off, and that the colour pairings the app
 * actually draws clear the contrast bar (and that `faint` on `ground`, the one the brief
 * flags, clears it only as large text — which is why it is used for labels and units and
 * nothing a user must read as prose).
 */
public final class Look {
    private Look() { }

    /* ---- palette — colour maps to pump state -------------------------------- */
    /* ARGB, opaque. The four state colours are EARNED: amber only where the pump may be
     * applying pressure, teal only for measured values, green only for telemetry-confirmed
     * safe, red only for unsafe/failed. Ui reads these; the meaning is enforced at the call
     * sites, not here. */

    /* THE GREYS ARE THE APPROVED options4.html `:root` VALUES. They were left at the older
     * design.html set when the P′R′T′U′V round was approved, which made the ground lighter
     * and bluer than approved and shrank the step from card to ground until every screen
     * read as one flat sheet of navy. Restoring them only DARKENS the ground, so every
     * contrast pairing pinned in SelfTest can only improve; the pins are re-run there. */
    public static final int GROUND     = 0xFF07090D;   // deep blue-black — vacuum reads as depth
    public static final int SURFACE    = 0xFF10141B;   // raised card, one step up
    public static final int SURFACEHI  = 0xFF171C26;   // the live/active surface
    public static final int LINE       = 0xFF222935;   // hairline divider, never a box outline
    public static final int TEXT       = 0xFFEEF2F7;   // primary
    public static final int DIM        = 0xFF8A94A6;   // secondary; still 4.5:1 on ground
    /**
     * A1 - TERTIARY, AND FOR LABELS AND UNITS ONLY.
     *
     * THE LINE, stated so the next person does not have to guess it: any run of PROSE at
     * SP_CAPTION or above takes DIM. FAINT is left to the sub-caption labels and unit words
     * it is named for - the "PULL" under a tile, the "%" after a figure, an axis tick - where
     * it is doing the job of being quiet beside a number that is not.
     *
     * Four sentences were breaking that and now take DIM: a field caption, a "used in N
     * routines" foot, the set editor's ladder hint and a trainer week's time column.
     *
     * IT CLEARS AA AS BODY TEXT, and that is a correction rather than a refinement. This was
     * #57606E - 3.13:1 on the ground - and the self-test justified it with the LARGE-text
     * threshold, which nothing drawn in it has ever met: every use is SP_MICRO, 10.5sp. The
     * assertion was true about a size the app does not draw. Raised to #7B8697 it is 5.41:1
     * on the ground and 5.01:1 on a card, so the label under a number is READABLE at the size
     * it is actually set in.
     *
     * THE OLD VALUE DID HAVE ONE HONEST JOB and it kept it - see {@link #FAINT_OFF}. That is
     * why this is two tokens and not one moved: the same grey cannot be both the quiet label
     * beside a live number and the ink that says a control is switched off.
     */
    public static final int FAINT      = 0xFF7B8697;

    /**
     * THE UNAVAILABLE INK - a control that is switched off, a day with nothing in it.
     *
     * #57606E, which is what {@link #FAINT} used to be, kept at that value deliberately. It
     * is 2.90:1 on a card and the self-test pins it UNDER 3:1 on purpose: WCAG 1.4.3 exempts
     * inactive components, and the whole job of this colour is to be too quiet to read as
     * live. Raising it - which is what moving the single old token would have done - would
     * have put a disabled row at 5.01:1, a hair from {@link #DIM} at 6.03:1, and a switched
     * off control would have stopped looking switched off.
     *
     * ONLY FOUR THINGS ARE DRAWN IN IT, and they are all the same thing: {@code
     * Ui#stateFill}'s unavailable state, {@code Ui#dependentBlock}'s greyed children, and a
     * calendar cell for a day that holds nothing. Anything a reader is meant to READ takes
     * FAINT above, or DIM.
     */
    public static final int FAINT_OFF  = 0xFF57606E;

    /**
     * LIME — THE APP'S ACTION COLOUR. What you can do, what you have done, and how far you
     * have come: the selected tab, the primary button, the streak, the progress trend, the
     * warm segment of a stage rail, the done segments of the step indicator.
     *
     * WHY IT REPLACED TEAL. Teal used to carry two unrelated jobs at once — "this is a
     * measured value from the device" and "this is the thing to tap" — and a colour with
     * two meanings has none. Worse, the two jobs sat next to each other constantly: the
     * live pressure readout and the START button were the same colour on the same screen.
     * So the meanings were split. Everything that meant ACTION or PROGRESS is now lime;
     * everything that meant A PRESSURE THE PUMP IS UNDER is AMBER, which is the one thing
     * amber has ever meant here; body measurements are violet. Teal is retired rather than
     * left lying around for a future screen to reach for and re-blur the line.
     *
     * AMBER IS UNTOUCHED AND STAYS THE NARROWEST COLOUR IN THE APP. Lime deliberately does
     * NOT appear on the live pressure number, the pressure trace, a hold, or the work
     * segment of a stage rail. If it is about the cuff being under pressure it is amber; if
     * it is about the person using the app it is lime.
     */
    public static final int ACCENT     = 0xFFC8FF3D;   // LIME — action, progress, streak
    /** The label colour on a filled lime button — near-black, because lime is a very light
     *  fill and a white label on it fails AA by a wide margin. Pinned in SelfTest. */
    /** The label colour on a filled VIOLET button. BODY is a light fill and TEXT on it is
     *  2.51:1 - below even this app's own large-text bar, on a primary button. Near-black
     *  reads 6.78:1. The fourth ink, beside the three that already exist for the same
     *  reason. */
    public static final int ON_BODY = 0xFF140A22;

    public static final int ON_ACCENT  = 0xFF101400;
    public static final int COMMANDED  = 0xFFF0A63C;   // AMBER — the pump is under command
    /**
     * GREEN, AND IT NOW HAS TWO SANCTIONED USES. This was written as "telemetry-confirmed
     * vented / completed" and nothing else, and the app then broke its own rule in one place
     * for years: Today's START is green, and START is not a verdict about the pump.
     *
     * That contradiction was put to the owner as a choice - move START to the action colour,
     * or widen the rule - and the rule was widened deliberately. So it is widened HERE, at
     * the constant, rather than left as an exception hiding in one screen's comment.
     *
     * THE TWO USES, and they do not overlap in practice:
     *   1. A VERDICT ABOUT THE PUMP: telemetry-confirmed vented, or a session completed.
     *      This is the original meaning and it is unchanged. Everything that reads green as
     *      "confirmed safe" is still reading it correctly.
     *   2. THE AFFIRMATIVE PRIMARY ACTION OF A SCREEN, of which there is at most ONE, and
     *      only where that action is the thing the screen exists for. Today's START is the
     *      whole example. It is not a state, it does not appear on the run screen, and it
     *      never sits beside a use of sense 1 - a screen showing a vent verdict has no
     *      affirmative primary to offer.
     *
     * WHAT IS STILL FORBIDDEN, and this is the part that keeps the colour worth anything: a
     * green that means "fine", "good", "healthy" or "pass" on any figure, chip, row or chart.
     * A rest day is not green. A high number is not green. Progress is not green - that is
     * {@link #ACCENT}. If a use is neither a pump verdict nor the one primary action, it is
     * not this colour.
     */
    /**
     * GREEN, AND IT NOW HAS TWO SANCTIONED USES. This was written as "telemetry-confirmed
     * vented / completed" and nothing else, and the app then broke its own rule in one place:
     * Today's START is green, and START is not a verdict about the pump.
     *
     * That contradiction was put to the owner as a choice - move START to the action colour,
     * or widen the rule - and the rule was widened deliberately. So it is widened HERE, at the
     * constant, rather than left as an exception hiding in one screen's comment.
     *
     * THE TWO USES, and they do not overlap in practice:
     *   1. A VERDICT ABOUT THE PUMP: telemetry-confirmed vented, or a session completed.
     *      The original meaning, unchanged. Everything reading green as "confirmed safe" is
     *      still reading it correctly.
     *   2. THE AFFIRMATIVE PRIMARY ACTION OF A SCREEN, of which there is at most ONE, and only
     *      where that action is the thing the screen exists for. Today's START is the whole
     *      example. It is not a state, it never appears on the run screen, and it never sits
     *      beside a use of sense 1 - a screen showing a vent verdict has no affirmative
     *      primary to offer.
     *
     * WHAT IS STILL FORBIDDEN, and this is what keeps the colour worth anything: green that
     * means "fine", "good", "healthy" or "pass" on any figure, chip, row or chart. A rest day
     * is not green. A high number is not green. Progress is not green - that is ACCENT. If a
     * use is neither a pump verdict nor the one primary action, it is not this colour.
     */
    public static final int SAFE       = 0xFF4CC77E;   // GREEN — confirmed vented / completed
    public static final int CRITICAL   = 0xFFF0564C;   // RED — unsafe, unconfirmed, failed
    /** VIOLET — the BODY MEASUREMENT domain: the tape measure, the photographs, the
     *  before/after pair. Added so Settings can mark its Measurements category without
     *  reaching for a colour that already means something: teal is what the PUMP measured
     *  and now, green is confirmed-safe, and AMBER IS RESERVED for the pump being under
     *  pressure and appears nowhere in Settings at all. A measurement of the body is a
     *  fourth thing, so it gets a fourth colour rather than borrowing a state colour and
     *  quietly weakening it. */
    public static final int BODY       = 0xFFA88BE0;

    /**
     * GREEN, FOR A BODY-MEASUREMENT TREND — bit-identical to {@link #SAFE} (same hex,
     * `0xFF4CC77E`) but a DIFFERENT semantic domain, and never interchangeable with it.
     * {@link #SAFE}'s own doc reserves green for exactly one meaning app-wide —
     * "telemetry-confirmed vented / completed", a verdict about the PUMP — and this file's
     * own header states the four state colours are EARNED at their call sites, not
     * borrowed. Stage D's S6+ trend badge ("ahead of trend") and the "GOAL REACHED"
     * one-time card are verdicts about a LENGTH/GIRTH READING against a projection or a
     * target — the body-measurement domain {@link #BODY} already carries, not the pump's
     * safety state — so they need their own name even though the locked mock
     * (round6-options.html's `--good`) happens to land on the identical hex SAFE already
     * uses. A second constant, not a reuse of SAFE, keeps that "green means vented" promise
     * intact: nothing that reads {@link #SAFE} to mean "confirmed safe" can be quietly true
     * for a body-measurement screen that was never about the pump's state at all.
     */
    public static final int TREND_GOOD = 0xFF4CC77E;

    /**
     * THE BLOCK PALETTE — the only colours the "save what you ran" card and its chart may
     * use to tell one block from another. Block i takes BLOCKS[i % BLOCKS.length], so a run
     * with more blocks than colours repeats rather than inventing one.
     *
     * These are IDENTITY colours, not state colours: they say "this is block 3", never
     * "this is safe" or "this is under pressure". That is why the list may NEVER contain
     * amber (COMMANDED — the pump is under pressure, the narrowest colour in the app), lime
     * (ACCENT — action and progress), violet (BODY — body measurements), green (SAFE —
     * telemetry-confirmed vented) or red (CRITICAL — unsafe/failed). Every one of those is
     * earned at its call sites; a block borrowing one would spend a meaning it did not earn
     * on a swatch that means nothing more than "the next one along". Six blues/pinks were
     * chosen precisely because none of them is any of those five, and SelfTest pins that.
     *
     * Order is the reading order: blue, pink, sky, orchid, periwinkle, ice.
     */
    public static final int[] BLOCKS = {0xFF5B9CFF, 0xFFF2A0D4, 0xFF7FD3FF,
                                        0xFFC77DFF, 0xFF9AA8FF, 0xFFCFE3FF};

    /**
     * ONE MEASUREMENT METHOD'S INK on the Progress chart - its trace, its goal line and the
     * line sample on its series chip, so the chip is the chart's key. Per method from
     * {@link #BLOCKS}; the girth methods (MSEG, MSSG) are shifted to follow Std, so on the
     * girth chart, where those three are the only series, none of them shares Std's blue.
     */
    public static int seriesInk(int method) {
        int idx = (method <= Model.Reading.METHOD_NBPSL)
            ? method : (method - Model.Reading.METHOD_MSEG + 1);
        return BLOCKS[Math.floorMod(idx, BLOCKS.length)];
    }

    /**
     * THE COLOUR A MARK IS DRAWN IN \u2014 {@link Model.Set#mark} / {@link Model.Routine#mark}.
     *
     * Marks reuse {@link #BLOCKS} rather than introducing a seventh palette, and they do
     * it for the reason BLOCKS exists: those six were chosen precisely because none of
     * them is lime, amber, green, violet or red, so none of them can spend a meaning it
     * did not earn. That is exactly the property a user's own mark needs \u2014 it means
     * whatever they decided it means and nothing the app taught them.
     *
     * Mark 0 is "unmarked" and has no colour; callers ask {@link #marked} first and simply
     * do not draw the rail or the dot. This method answers {@link #LINE} for 0 rather than
     * throwing, so a stale value can never crash a list, but a caller that draws that
     * answer has drawn a hairline where it meant to draw nothing.
     */
    public static int markColour(int mark) {
        return mark >= 1 && mark <= BLOCKS.length ? BLOCKS[mark - 1] : LINE;
    }

    /** Whether a mark is one to draw at all. */
    public static boolean marked(int mark) { return mark >= 1 && mark <= BLOCKS.length; }

    /** The label colour on a filled amber/green button — near-black, for contrast on the
     *  light state colours (a white label on amber fails AA). Two shades so each is tuned
     *  to its ground; both clear 4.5:1 (pinned in SelfTest). */
    public static final int ON_AMBER   = 0xFF180E02;
    public static final int ON_GREEN   = 0xFF04160B;
    /** ...and on the red STOP (polish item 17): near-black, 5.8:1 on CRITICAL, where the
     *  white label it replaces was 3.4:1. */
    public static final int ON_RED     = 0xFF1A0404;

    /**
     * THE LABEL INK FOR A FILLED BUTTON - one table, so every light fill gets its dark ink.
     *
     * Ui.labelOn used to hold this table and had no red branch (polish SYS-1): every red
     * filled button - the pre-run "Cancel", "STOP — end and vent", "Delete this reading" -
     * drew TEXT on CRITICAL at 3.0:1, under AA. Pure, so the whole table is pinned in a test
     * (InkOnFillTest) rather than read off a screenshot. Dark fills keep TEXT.
     */
    public static int inkOn(int fill) {
        if (fill == COMMANDED) return ON_AMBER;
        if (fill == SAFE)      return ON_GREEN;
        if (fill == ACCENT)    return ON_ACCENT;   // lime is the lightest fill of all
        // VIOLET IS A LIGHT FILL TOO: TEXT on it is 2.51:1.
        if (fill == BODY)      return ON_BODY;
        if (fill == CRITICAL)  return ON_RED;
        return TEXT;
    }

    /* ---- the two DIM TINTS — a state colour used as a FILL, not as ink ------- */
    /*
     * The mockup never fills a control with a full-strength state colour unless that
     * control is the screen's one primary button; a SELECTED pill and the Hold control are
     * filled with the state colour at low alpha and lettered in the state colour at full
     * strength. These are those two fills, translucent ARGB, composited by the framework
     * over whatever surface they land on.
     */

    /** `rgba(200,255,61,.14)` — LIME at 14%. The fill of a SELECTED pill/segment and of
     *  any ●-marked control that is on. Lime means ACTION, so this is legal anywhere the
     *  user's own choice is being shown back to them. Lime at full strength stays on the
     *  ink (the label), which clears AA over this fill on either surface — pinned. */
    public static final int ACCENT_DIM = 0x24C8FF3D;

    /**
     * `rgba(240,166,60,.12)` — AMBER at 12%. THIS IS STILL AN AMBER USE. Diluting a colour
     * does not dilute its meaning: amber says the pump may be applying pressure and says
     * nothing else, so this fill is legal ONLY on controls that are about the cuff being
     * under pressure — the HOLD control (a hold is still pressure, which is the whole
     * argument for the button) and the RAMP pill. It must never be reached for as "a warm
     * tint" on a Settings row, a Library segment or any selected pill: selection is lime's
     * job and ACCENT_DIM is the fill for it.
     */
    public static final int CMD_DIM    = 0x1FF0A63C;

    /**
     * PAUSE WHERE THERE IS NOTHING TO PAUSE (0.10 run screen): in a rest the cuff is vented,
     * so the Pause button keeps its place but wears amber at 5 % behind amber ink at 35 % -
     * plainly not available, still where the thumb expects it, and a tap says why.
     */
    public static final int CMD_OFF_FILL = 0x0DF0A63C;
    public static final int CMD_OFF_INK  = 0x59F0A63C;

    /**
     * THE RUN SCREEN'S − / + STRIP (0.10, the owner-approved mock's `.qc` keys): the 48 dp
     * square either end of a cell, a shade up from the cell's SURFACEHI well; pressed, a
     * shade further; and the glyph of a key at its limit - dimmed, still tappable, so the
     * tap can say which limit it met.
     */
    public static final int STEP_KEY          = 0xFF1F2530;
    public static final int STEP_KEY_DOWN     = 0xFF2A3240;
    public static final int STEP_KEY_AT_LIMIT = 0xFF4A525E;

    /**
     * COMING STEPS, the approved "1A" settings list: the well a card's rows sit in, the
     * sheet's own − / + keys, and the warning line's ink.
     */
    public static final int COMING_WELL      = 0xFF1B222C;
    public static final int COMING_KEY       = 0xFF262E39;
    public static final int WARN_INK         = 0xFFF5B5AE;

    /**
     * `rgba(240,86,76,.13)` — RED at 13%. CRITICAL's own low-alpha fill: the background of
     * an instrument tile (or any small status cell) reporting an EVIDENCED FAILURE, never a
     * warning and never a maybe. The self-test's 17-cell phase grid is the first thing that
     * draws this — a cell earns the fill only when HwTest.Verdict says the phase actually
     * failed, the same discipline CRITICAL itself is held to at full strength everywhere
     * else in the app.
     */
    public static final int CRIT_DIM   = 0x21F0564C;

    /**
     * `rgba(240,86,76,.2)` — RED at 20%, the SAME underlying red as CRITICAL/CRIT_DIM (the
     * RGB matches exactly — pinned in SelfTest) at a higher, more opaque alpha than
     * CRIT_DIM's 13%. CRIT_DIM is a small STATUS-CELL fill for an evidenced failure; this is
     * the WASH behind a whole alarm block — the Link Lost screen's red-framed instrument
     * card (round-3 D3) — where the surface under several lines of text needs to read as
     * "alarm" at a glance, not just tint one cell. Named so any future alarm surface (a
     * full-screen warning, not a status tile) can reuse it rather than re-typing a raw hex.
     */
    public static final int ALARM_WASH = 0x33F0564C;

    /**
     * `rgba(91,156,255,.14)` — the mockup's own --blueDim: BLOCKS[0]'s blue (the mock's
     * --blue, #5B9CFF, exact) at 14%, the fill of the mock's `.pb` pill. Blue is this
     * app's informational/plan colour — the ✎ edit affordance, the projection wedge —
     * and this is its low-alpha fill for the review wizard's diff-row word pills:
     * every NON-pressure change ("SHORTER"/"LONGER"/"FASTER"/"SLOWER") and the
     * less-pressure one ("SHALLOWER") wear blue, because CMD_DIM stays what its own doc
     * says it is — legal ONLY where the pump may be under pressure, which for a pill
     * word means ONLY "ran deeper than planned" (WizardDiff.colour, pinned). The alpha
     * is ACCENT_DIM's 14%, not CMD_DIM's 12%: the mock's pill fills (--limeDim,
     * --violetDim, --blueDim) are all .14, and CMD_DIM's .12 is the Hold control's own,
     * kept where it predates this. Pinned in SelfTest like the other DIM tints — the
     * hex, that it is BLOCKS[0] diluted rather than a fifth blue, translucency, and AA
     * of the full-strength blue ink over the composited fill.
     */
    public static final int BLUE_DIM   = 0x245B9CFF;

    /**
     * TWO MORE LIME FILLS, for the DOSE HEATMAP's day cells (Stage D task 3, S7 A) — NOT a
     * reuse of {@link #ACCENT_DIM} above, even though both are low-alpha lime. ACCENT_DIM's
     * own doc reserves it for exactly one job, "the fill of a SELECTED pill/segment" — a
     * binary on/off state. A dose-heatmap cell is not selected or unselected; it is
     * GRADUATED — "roughly how much was delivered that day", read against the heaviest day
     * this app has ever recorded ({@link Insight#doseTier}) — so it needs its own steps
     * rather than borrowing a constant a reader would otherwise have to re-confirm means
     * "selected" every time this file is read.
     *
     * The domain is still ACCENT's own: ACCENT's doc already names "the streak, the
     * progress trend" among what lime carries, and a day's dose is that same fact — the
     * user's own training activity — at finer grain. So unlike {@link #TREND_GOOD} a few
     * paragraphs up, this is NOT "identical hex, different domain, needs a distinct name to
     * protect a promise" — it is the SAME domain needing more than one step of it, purely a
     * graduated INTENSITY, never a second hue.
     *
     * Two steps, not three: the heaviest tier is full-strength {@link #ACCENT} itself
     * (paired with {@link #ON_ACCENT} ink, the same near-black label the primary lime
     * button already uses — a solid lime fill fails AA under a plain white label, which is
     * exactly ON_ACCENT's own reason for existing), so there is no third constant for a
     * value that already has a name. The two alphas below were chosen, then PROVED, not
     * guessed: each is the fill a day-cell's plain {@link Look#TEXT} label is actually
     * drawn on, and SelfTest pins that both composited pairings clear 4.5:1 over the
     * heatmap card's own SURFACE — an alpha picked for "looks about right" and never
     * checked against the ink drawn on it is exactly how the START button's own contrast
     * bug (style-gaps.html finding #01) happened the first time.
     */
    public static final int DOSE_TIER_LOW = 0x40C8FF3D;   // lime, ~25%
    public static final int DOSE_TIER_MED = 0x60C8FF3D;   // lime, ~38%

    /**
     * A translucent ARGB source composited over an OPAQUE background, returning the opaque
     * result — straight source-over alpha blending, which is exactly what the framework
     * does when it draws ACCENT_DIM on a card. It exists so the contrast of "lime ink on a
     * lime-tinted pill" is a computed number in SelfTest rather than an assumption, since
     * contrastRatio() is defined only for opaque colours.
     */
    public static int over(int src, int opaqueBg) {
        double a = ((src >>> 24) & 0xFF) / 255.0;
        int r = mix((src >> 16) & 0xFF, (opaqueBg >> 16) & 0xFF, a);
        int g = mix((src >> 8) & 0xFF,  (opaqueBg >> 8) & 0xFF,  a);
        int b = mix(src & 0xFF,         opaqueBg & 0xFF,         a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int s, int d, double a) {
        return (int) Math.round(s * a + d * (1 - a));
    }

    /* ---- the RADIUS scale — four roles, not ten one-off numbers ------------- */
    /*
     * The app had accumulated ten different corner radii typed at call sites. The mockup
     * has four, and each one is a ROLE rather than a size: the smaller the element, the
     * tighter the corner, so a pill and the sheet it sits in do not fight.
     */

    /** 9dp — a small inline PILL or badge ("ready", "+1 today"), and the day cells. */
    public static final int R_PILL  = 9;
    /** 11dp — a CONTROL: a button, a segment, a tile, a chip, a ●/○ row cell. */
    public static final int R_CTRL  = 11;
    /** 13dp — a CARD. The mockup's `.card{border-radius:13px}`. */
    public static final int R_CARD  = 13;
    /** 16dp — a SHEET or dialog, the largest surface, so its corner reads as the frame. */
    public static final int R_SHEET = 16;

    /* ---- the SPACING scale — six steps, in dp ------------------------------- */
    /* Twenty distinct dp gaps were in use. Six is the whole vocabulary: S1 hairline gaps,
     * S2 between siblings in a row, S3 between rows, S4 between cards, S5 card padding,
     * S6 the space above a section heading. */
    public static final int S1 = 4;
    public static final int S2 = 6;
    public static final int S3 = 8;
    public static final int S4 = 10;
    public static final int S5 = 14;
    public static final int S6 = 18;

    /* ---- PRINT palette — the PDF report only (§8 #9) ------------------------ */
    /* Dark ink on white: a PdfDocument page is paper, and the app's dark screen palette
     * printed as-is would be a black rectangle costing a cartridge. The chart views take
     * a `print` flag and swap to these — the same views, never duplicated ones, so a
     * chart fix can never land on screen but not on paper. PRINT_ACCENT is lime's
     * printable descendant (progress), PRINT_BODY violet's (body measurements): the
     * COLOUR ROLES survive the palette swap even though the hexes cannot. All pairings
     * against PRINT_GROUND clear WCAG AA (pinned in SelfTest). */
    public static final int PRINT_GROUND = 0xFFFFFFFF;   // the page
    public static final int PRINT_INK    = 0xFF14181D;   // primary text and table rules
    public static final int PRINT_DIM    = 0xFF525B66;   // captions, secondary text
    public static final int PRINT_LINE   = 0xFFC9D0D8;   // hairlines, axes
    public static final int PRINT_ACCENT = 0xFF4A7A00;   // progress — lime, ink-dark
    public static final int PRINT_BODY   = 0xFF6B4FA0;   // body measurements — violet, ink-dark

    /* ---- type scale — an instrument's hierarchy, in sp ---------------------- */
    /* Set once here, used nowhere else.
     *
     * TWO FACES FOR NUMBERS, AND THE LINE BETWEEN THEM. MONOSPACE is for a number that is
     * LIVE or being EDITED: the run clock, the live pressure readout, the NOW card's time,
     * a stepper's value cell. Those change under the eye, and a readout whose digits
     * shift width as they tick is a readout nobody can read.
     *
     * A READ-ONLY figure in a row, a card or a tile is set in the normal sans face with
     * TABULAR figures (Ui#tabular, the "tnum" feature). It used to be monospace too, which
     * made Settings and the Trainer print the same kind of number in a different face from
     * Today and Library - two typefaces for one job. Tabular digits keep a column of values
     * aligned exactly as monospace did, without the typewriter look beside sans labels. */

    public static final float SP_READOUT = 44f;   // MONOSPACE, weight 500 — one live number per screen
    /** 24sp — the self-test's PASS/FAIL verdict headline. Bigger than SP_TITLE (a screen's
     *  own head, 22sp) because this word is the one thing the report answers before any
     *  phase below it is read; smaller than SP_READOUT (44sp, reserved for the app's one
     *  live pressure number) because it is a word, not a measurement. */
    public static final float SP_VERDICT = 24f;
    public static final float SP_TITLE   = 22f;   // sans 600 — screen heads
    public static final float SP_HEADING = 17f;   // sans 600 — card titles
    public static final float SP_BODY    = 15.5f; // sans 400 — prose
    public static final float SP_LABEL   = 13f;   // sans 500, +0.6 tracking, UPPERCASE — labels, units
    public static final float SP_CAPTION = 12.5f; // sans 400 — notes and explanations
    /** 10.5sp — the MICRO LABEL. The mockup's `.greet`, `.sh`, `.now` and `.tile` captions
     *  are all 10-10.5px uppercase with tracking; the scale had no step below 12.5 and
     *  those labels were being drawn at SP_LABEL, a third larger than approved, which is
     *  why the uppercase furniture crowds the numbers it is captioning. UPPERCASE ONLY,
     *  and always with LABEL_TRACKING_EM — 10.5sp of tightly set capitals is a smudge. */
    public static final float SP_MICRO   = 10.5f;

    /** 12sp — the UPPERCASE label over a field or a group of controls: "NEVER EXCEED"
     *  above the ceiling stepper, "JUMP TO" above Settings' chips. Medium weight, tracked
     *  like every uppercase label, in DIM. A step under SP_LABEL because it names a group
     *  rather than captioning a figure, and it sits right above the thing it names. */
    public static final float SP_FIELD_LABEL = 12f;
    /** 14sp — the words on a chip (Settings' jump chips): medium weight, one line. */
    public static final float SP_CHIP = 14f;
    /** 15sp — a FOLD HEADING, the tappable row that opens and closes a Settings category.
     *  Semibold, sentence case, between SP_BODY and SP_HEADING: it outranks the rows it
     *  heads but not the titles of the cards inside it. */
    public static final float SP_FOLD_HEAD = 15f;

    /** The letter-spacing (em) applied to label text, and the line-height multiplier prose
     *  is given — long safety explanations are read, not scanned.
     *
     *  Android does NOT apply optical tracking to capitals the way CSS `letter-spacing`
     *  does, so an uppercase label with no explicit setLetterSpacing() is set tighter than
     *  the mockup at every size. Every uppercase label helper in Ui applies this. */
    /** Wave 4 item 8 — the CONTROL look's 1 dp edge: anything that acts in place
     *  carries a visible border, so a control and a card stop looking identical. */
    public static final int CTRL_EDGE = 0xFF5F6A7B;
    /** Wave 4 item 8 — the INFO blue: an (i) only ever explains, and it is BLUE so it
     *  cannot be mistaken for text (safety info keeps amber). */
    public static final int INFO_BLUE = 0xFF6FA8DC;

    public static final float LABEL_TRACKING_EM = 0.6f / SP_LABEL;   // +0.6sp expressed as em
    public static final float PROSE_LINE_MULT   = 1.55f;

    /* ---- motion — purposeful only, in milliseconds -------------------------- */
    /* Base durations before the system animation scale is applied. Every one must be doing
     * a job; the live pressure NUMBER is never among them (the bar eases, the digits jump). */

    public static final int MS_SCREEN   = 160;   // fade + translateY on a screen change
    public static final int MS_CARD     = 90;    // a card appearing
    public static final int MS_CARD_STAGGER = 25;// per-child stagger, first 6 children only
    public static final int MS_STATE    = 220;   // colour cross-fade on the state chip
    public static final int MS_VALUE    = 140;   // the live BAR easing to a new width
    public static final int MS_PRESS_IN  = 50;    // button press: scale 0.985 + alpha 0.90
    public static final int MS_PRESS_OUT = 140;   // release: decelerated settle back to 1.0/1.0
    public static final float PRESS_SCALE = 0.985f;
    public static final float PRESS_ALPHA = 0.90f;
    public static final int MS_SNACK_IN = 160;   // slide up 8dp + fade in

    /** How long a one-finger hold on the Align preview takes before it enters precision
     *  zoom/rotate mode (round-2 C8). Long enough that an ordinary pan-to-reposition tap never
     *  trips it (a pan already starts moving the photo on the first MOVE event, so precision
     *  mode only ever wins a gesture the user held still); short enough not to feel like a
     *  stuck finger once it does fire. Deliberately NOT run through resolveDuration — it is a
     *  RECOGNITION delay, not a played animation, so turning system motion off must not make
     *  it instant. */
    public static final int MS_LONG_PRESS = 400;

    /** SURFACEHI at ~70% opacity — the precision-mode HUD chip's background, the same
     *  translucent-dark-chip-over-a-live-frame treatment the pre-capture level readout uses
     *  (see CameraScreen's tilt/turn chip), so a floating readout over a photo always looks
     *  like the same kind of thing on this screen. */
    public static final int HUD_BG = 0xB3171C26;

    /* ---- swipe-to-remove — measurement log rows (round-2 C4) ---------------------- */
    /* The ✕ button that used to sit past miniSmall's −/+ is gone: removal is a deliberate
     * drag now, not a mistappable glyph, so both the distance that counts as "committed" and
     * the motion that follows it are named here rather than typed at Ui's one call site. */

    /** How far left, in dp, a measurement-log row must be dragged before releasing it
     *  removes the row rather than snapping back. */
    public static final int SWIPE_REMOVE_DP = 80;
    /** The row finishing its slide off-screen once a drag has cleared SWIPE_REMOVE_DP. */
    public static final int MS_SWIPE_REMOVE = 140;
    /** The row easing back to rest after a drag that did not clear SWIPE_REMOVE_DP. */
    public static final int MS_SWIPE_SNAP_BACK = 140;

    /** How many staggered children a card-appear animates before the rest simply show —
     *  past the sixth the stagger is imperceptible and only delays the screen. */
    public static final int CARD_STAGGER_LIMIT = 6;

    /**
     * A base duration resolved against the system animation scale
     * (Settings.Global.ANIMATOR_DURATION_SCALE, read by SessionActivity and passed in
     * here). A scale of 0 means the user has turned animations OFF — often for vestibular
     * reasons — and EVERY duration must become 0, giving them no motion at all. This is an
     * accessibility requirement, not a preference, which is why it lives in the pure layer
     * where it is pinned rather than in a view callback where it is not.
     *
     * A negative scale (the framework's "not set" sentinel is never negative, but a bad
     * read might be) is treated as off, failing closed to no motion rather than to some
     * multiplied-by-garbage duration.
     */
    public static int resolveDuration(int baseMs, float scale) {
        if (scale <= 0f) return 0;
        return Math.round(baseMs * scale);
    }

    /** The stagger delay for the i-th card child at a given scale — 0 for children past
     *  the limit, and 0 whenever motion is off, so a scale of 0 shows the whole screen at
     *  once with no cascade. */
    public static int staggerDelay(int childIndex, float scale) {
        if (childIndex < 0 || childIndex >= CARD_STAGGER_LIMIT) return 0;
        return resolveDuration(MS_CARD_STAGGER * childIndex, scale);
    }

    /** The one-shot "fill sweep" the week ring plays when Today ARRIVES — long enough to
     *  be read as one gesture, short enough that the screen is never waited on. Resolved
     *  through resolveDuration like every other duration, so animations-off means none. */
    public static final int MS_RING_SWEEP = 480;

    /* ---- Run screen live chart — endpoint pulse + Y-axis ticks (Stage B task 2) --- */
    /* The chart's own value-to-pixel mapping (Trace.yFraction/fullScaleKpa) stays in
     * Trace.java, PURE; these are only the pulse's colour/size/duration — the new values
     * EcgTrace's onDraw needs and nowhere else. */

    /** The Run screen's chart endpoint dot's pulse cycle — a slow "still live" breathe,
     *  not urgency. Resolved through resolveDuration like every other motion value. */
    public static final int MS_ENDPOINT_PULSE = 1600;

    /** The pulse's low point (the locked spec's `opacity:.35`) — the dot never fades past
     *  this, so the newest reading stays legible even mid-fade. Full opacity (1.0) is the
     *  other end of the cycle and needs no constant of its own. */
    public static final float ENDPOINT_PULSE_FLOOR = 0.35f;

    /** The Y-axis tick labels' text size, in dp — small enough to sit beside the grid
     *  hairlines without competing with the trace or the commanded band's own inline
     *  labels, which this chart already draws a size step up from here. */
    public static final int AXIS_TICK_DP = 9;

    /** The RUN chart's gridline labels and its unit, in sp (polish item 9): round numbers
     *  in the sans face, read at a glance, so a size up from AXIS_TICK_DP. */
    public static final float SP_AXIS = 11f;

    /* ---- week-ring geometry ------------------------------------------------ */
    /* The streak card composes ONE graphic: a track circle, seven day dots on it, and the
     * streak number in the middle. The dot placement and the reveal are arithmetic, so they
     * live here where SelfTest can pin them rather than inside an onDraw no harness can run. */

    /** Sweep in degrees for `done` of `total`, clamped into [0, 360]. `total <= 0` is 0 —
     *  a fraction of nothing is not a full ring. */
    public static float ringSweepDeg(int done, int total) {
        if (total <= 0) return 0f;
        int d = done < 0 ? 0 : (done > total ? total : done);
        return 360f * d / total;
    }

    /** Sweep in degrees for a COUNTDOWN with `secondsLeft` of `totalSeconds` remaining —
     *  a full ring at the start, draining to nothing at zero. The fraction-of-a-circle
     *  arithmetic is identical to ringSweepDeg's (a full ring at `secondsLeft == totalSeconds`,
     *  none at `secondsLeft == 0`, the same clamp at both ends), so this delegates to it
     *  rather than duplicating the formula — but it keeps its own name so the undo ring's
     *  onDraw reads as "how much of the countdown is left", not a reuse of a "days done
     *  this week" helper under a caller's understanding it doesn't share. */
    public static float drainRingSweepDeg(int secondsLeft, int totalSeconds) {
        return ringSweepDeg(secondsLeft, totalSeconds);
    }

    /**
     * The angle of the `index`-th of `count` evenly spaced dots around the ring, in the
     * DEGREES Canvas uses — measured from 3 o'clock, increasing clockwise. Index 0 is at
     * the top (-90), so the ring starts where a clock does and the last index lands just
     * anticlockwise of it. Feed straight to Math.toRadians/cos/sin.
     */
    public static float ringDotAngleDeg(int index, int count) {
        if (count <= 0) return -90f;
        return -90f + 360f * index / count;
    }

    /** The x of that dot's centre, on a circle of `radius` about `cx`. */
    public static float ringDotX(float cx, float radius, int index, int count) {
        return cx + radius * (float) Math.cos(Math.toRadians(ringDotAngleDeg(index, count)));
    }

    /** The y of that dot's centre, on a circle of `radius` about `cy`. */
    public static float ringDotY(float cy, float radius, int index, int count) {
        return cy + radius * (float) Math.sin(Math.toRadians(ringDotAngleDeg(index, count)));
    }

    /* ---- G1 achievement gauge — Today's combined week/achievements panel (Stage B
     *      task 6) ------------------------------------------------------------------- */
    /* The gauge reuses WeekRing's own arc math (track + Look.ringSweepDeg sweep + centred
     * "done/total" text) generalized to take its own colours and text size — see WeekRing
     * in SessionActivity. These two are only the sizes that class needs and streakBlock's
     * existing WeekRing call does not, so they are new constants rather than new numbers
     * typed at a call site. */

    /** The gauge's own square size, in dp — the locked visual reference's 64x64 SVG ring
     *  (dist/final-design.html section 1/TODAY). */
    public static final int G1_SIZE_DP = 64;
    /** The gauge's centred "done/total" text size, in dp — a step up from the 58dp post-
     *  session week ring's 12dp (WeekRing's original, unchanged, hardcoded value) since
     *  this ring is wider and its digit is the one number the whole panel leads with. */
    public static final int G1_TEXT_DP = 14;

    /* ---- Progress tab strip (Stage B task 9) -------------------------------------- */
    /* Ui.tabStrip() — the new TRENDS/PHOTOS/SESSIONS primitive. Its selected cell is
     * marked with a thin underline rather than periodRow()/metricRow()'s filled pill (see
     * tabStrip's own doc for why the two must not look interchangeable); this is the one
     * new size that grammar needs. */

    /** The selected tab's underline thickness, in dp — thin enough to read as a rule under
     *  the label rather than a second filled bar competing with periodRow's pills below it. */
    public static final int TAB_UNDERLINE_DP = 2;

    /* ---- W1 review wizard — steps progress bar (Stage C task 1) --------------------- */
    /* The wizard's `.steps` row (dist/final-design.html section 3): N equal segments,
     * `height:4px;border-radius:2px`, `gap:3px` — done and current segments lime, the
     * rest the raised surface. Sizes only: the colours are the existing ACCENT and
     * SURFACEHI roles, never new hexes. */

    /** One steps-bar segment's height, in dp — the mock's `.steps span{height:4px}`. */
    public static final int WIZ_STEP_H_DP = 4;
    /** The gap between adjacent segments, in dp — the mock's `.steps{gap:3px}`. */
    public static final int WIZ_STEP_GAP_DP = 3;
    /** A segment's corner radius, in dp — the mock's `border-radius:2px`, the same
     *  barely-rounded rule GOAL_BAR_RADIUS_DP records for the goal bar. */
    public static final int WIZ_STEP_R_DP = 2;

    /* ---- W1 review wizard — per-step overlay chart (Stage C task 2) ----------------- */
    /* The step panel's PLAN-under-RAN chart (dist/final-design.html section 3, the locked
     * SVG at ~lines 133-138). NO new colours: the mock's amber RAN (#F0A63C) IS COMMANDED,
     * its grey PLAN line and label (#5d6b80) map to FAINT exactly as the run chart's axis
     * tick labels already do, and its #060910 inset is GROUND exactly as every other chart
     * inset already is. These are only the sizes the mock fixes and nothing else in the
     * app defines; the label text reuses AXIS_TICK_DP. */

    /** The chart's height, in dp — the mock's 64px-tall SVG in its ~262px-wide phone
     *  (64/262 ≈ 0.24), scaled to the step panel's ~300dp inner width. */
    public static final int WIZ_CHART_H_DP = 72;
    /** The inset's corner radius, in dp — the mock's `border-radius:5px`. */
    public static final int WIZ_CHART_R_DP = 5;
    /** The PLAN polyline's stroke width, in dp — the mock's `stroke-width="1.4"`. */
    public static final float WIZ_PLAN_STROKE_DP = 1.4f;
    /** The RAN polyline's stroke width, in dp — the mock's `stroke-width="2"`. */
    public static final int WIZ_RAN_STROKE_DP = 2;
    /** The PLAN line's dash pattern, in dp — the mock's `stroke-dasharray="4 3"`. */
    public static final int WIZ_PLAN_DASH_ON_DP = 4;
    public static final int WIZ_PLAN_DASH_OFF_DP = 3;
    /** The bands above and below the plot, in dp, holding the mock's corner labels —
     *  "RAN −6.2" top-left (text at y=10 of its 0–14 band), "PLAN −4.1" bottom-left. */
    public static final int WIZ_CHART_BAND_DP = 14;

    /* ---- Progress consistency tick strip (Stage B task 11) -------------------------- */
    /* consistencyCard() shrank from a 28-cell (14x2) grid to a single-row strip of
     * Summary.WEEK_DAYS dots, per dist/final-design.html section 4/PROGRESS's locked `.dot`
     * row (`display:flex;gap:3px;align-items:center`, `.dot{width:7px;height:7px;
     * border-radius:50%}`, the TODAY dot's `outline:1px solid var(--lime)`). These three
     * are the only sizes that row needs and nothing else in the app already defines. */

    /** Each tick's diameter, in dp — the mockup's `.dot{width:7px;height:7px}`. */
    public static final int TICK_DOT_DP = 7;
    /** The gap between adjacent ticks, in dp — the mockup's `gap:3px`. */
    public static final int TICK_GAP_DP = 3;
    /** The TODAY tick's hollow-ring outline width, in dp — the mockup's `outline:1px solid`. */
    public static final int TICK_RING_DP = 1;

    /**
     * How far the `index`-th dot has been revealed at overall progress `frac` (0..1) — the
     * arrival sweep, which walks the dots round the ring rather than fading all seven at
     * once. 0 is "not yet", 1 is "fully there", and the values between are the one dot the
     * sweep is currently crossing.
     *
     * Clamped at BOTH ends on purpose: frac >= 1 must return exactly 1 for every dot, so a
     * cancelled or never-started animator can only ever leave the ring fully drawn — the
     * same fail-visible rule animateBodyIn follows, never a graphic stranded at zero.
     */
    public static float sweepReveal(float frac, int index, int count) {
        if (count <= 0) return 1f;
        if (frac >= 1f) return 1f;
        if (frac <= 0f) return 0f;
        float v = frac * count - index;
        return v <= 0f ? 0f : (v >= 1f ? 1f : v);
    }

    /* ---- Progress M1 goal instrument card (Stage B task 12) ------------------------- */
    /* The dedicated goal card — dist/final-design.html section 4/PROGRESS's "GOAL · BPSSL
     * 22.9 · IF THE TREND HOLDS" panel — draws history through TODAY, then a dashed
     * projection (Model#goalLineAt/goalSlopePerYear) out to where it crosses the capped
     * goal (Model#capGoal). These are the sizes and the one new colour that picture needs
     * and nothing already in this file provides. */

    /** The instrument chart's own height, in dp — shorter than the multi-series trend
     *  chart above it (this one draws a single series plus its projection, not a picker's
     *  worth of them), tall enough to keep the TODAY/GOAL/crossing labels off each other. */
    public static final int GOAL_CHART_HEIGHT_DP = 70;

    /** The S9 volume mini chart's own height, in dp — its own sibling of {@link
     *  #GOAL_CHART_HEIGHT_DP} above: shorter than the 150dp multi-series measurement
     *  chart it sits under (a "mini chart", by the S9 mock's own name, drawing a single
     *  derived series with no goal line and no picker of its own), but taller than the
     *  goal instrument's single-series-plus-projection chart since it still carries its
     *  own y-axis gridlines (polish item 14). */
    public static final int VOL_CHART_HEIGHT_DP = 90;

    /** The most gridlines the volume mini chart draws (polish item 14) — fewer than the
     *  measurement chart above it ({@link Trace#AXIS_MAX_LINES}, the same cap the run
     *  chart uses). This chart is only {@link #VOL_CHART_HEIGHT_DP} tall, 60% of the
     *  measurement chart's 150dp, and the same line count that reads fine at that height
     *  crowds at this one. */
    public static final int VOL_CHART_AXIS_MAX_LINES = 4;

    /**
     * THE UNCERTAINTY WEDGE'S HALF-HEIGHT AT THE CROSSING POINT, in dp — a FIXED, DECIDED
     * visual width, not a computed confidence interval. Model and Meas expose no variance,
     * standard-deviation or confidence figure for the goal projection anywhere (the slope
     * is a single capped rate, {@link Model#goalSlopePerYear} — see its own doc — with no
     * distribution behind it), so there is no real uncertainty number to draw the wedge
     * from. This constant exists so the projection reads as "a direction, not a promise"
     * without inventing a precision the underlying math does not have: the wedge tapers to
     * ZERO at today (the last actual reading, where there is no uncertainty yet) and opens
     * to this many dp above and below the projection line by the time it reaches the goal.
     * If real variance math is ever added, this is the one constant that stops being a
     * placeholder and starts being derived.
     */
    public static final int GOAL_WEDGE_HALF_DP = 6;

    /** The progress bar's track height, in dp — the mockup's `height:6px`. */
    public static final int GOAL_BAR_HEIGHT_DP = 6;
    /** The progress bar's corner radius, in dp — the mockup's `border-radius:2px`, well
     *  under the app's smallest named radius role (R_PILL, 9dp), which would turn a 6dp-tall
     *  bar into a pill instead of the mockup's barely-rounded rule. */
    public static final int GOAL_BAR_RADIUS_DP = 2;

    /** `rgba(91,156,255,.10)` — the projection's own ink (BLOCKS[0], the mockup's --blue)
     *  at 10%, the wedge's fill. Same pairing as ACCENT/ACCENT_DIM: the RGB matches BLOCKS[0]
     *  exactly (pinned in SelfTest) so the wedge reads as "this line's own uncertainty",
     *  never a second, unrelated colour. */
    public static final int GOAL_WEDGE_FILL = 0x1A5B9CFF;

    /**
     * THE PLAN BLUE AT FULL STRENGTH - BLOCKS[0]'s #5B9CFF, named for its second job: the
     * fill of a PROGRESS-TOWARD-A-TARGET bar (the Trainer's "Gate to Level N" rows). Blue
     * is this app's informational/plan colour ({@link #BLUE_DIM}, {@link #GOAL_WEDGE_FILL}
     * are this same blue diluted), and a gate bar is exactly that: how far the plan's own
     * condition has come. Not lime - lime would claim the condition is met, and the row
     * turns lime only once it is. Pinned equal to BLOCKS[0] so it can never drift into a
     * seventh blue.
     */
    public static final int PLAN_BLUE = 0xFF5B9CFF;

    /** A progress bar's track height, in dp - the gate rows' thin 4dp rule under each
     *  condition, drawn on SURFACEHI with {@link #PLAN_BLUE} filling it. */
    public static final int GATE_BAR_DP = 4;

    /**
     * How much of a progress bar to fill: |value| / |target|, clamped into [0, 1].
     *
     * MAGNITUDES, because pressure reads NEGATIVE in inHg and cmHg: −5.0 of a −8.0 inHg
     * target is 62% of the way there, not "less than nothing". A target of zero asks for
     * nothing, so it is already met (1); a value that is not a number fills nothing (0).
     */
    public static double barFill(double value, double target) {
        if (Double.isNaN(value) || Double.isNaN(target)) return 0.0;
        double t = Math.abs(target);
        if (t == 0.0) return 1.0;
        double f = Math.abs(value) / t;
        return f <= 0.0 ? 0.0 : (f >= 1.0 ? 1.0 : f);
    }

    /**
     * WHETHER A CARD'S TONE EARNS A COLOURED LEFT EDGE. Only a WARNING or a FAULT does:
     * {@link #CRITICAL} (unsafe, failed) or {@link #COMMANDED} (the pump may be under
     * pressure). Every other tone - lime, violet, the greys - draws a plain card.
     *
     * The edge used to go on nearly every card as a category or grouping mark, and a colour
     * on every card stops signalling anything; the one card that is a warning looked like
     * all the others. Ui.cardGroup and Ui.chip both ask this, so the rule is kept in one
     * place and callers go on stating the tone their card has.
     */
    public static boolean earnsSpine(int tone) {
        return tone == CRITICAL || tone == COMMANDED;
    }

    /** TASK 5 — the goal instrument's "more than one reading, one sitting" cue: an
     *  unfilled ring, in the chart's own {@link #BODY} ink, drawn around a history dot
     *  that shares its calendar day (Meas#sameDayClusters) with another reading of the
     *  same method. Raw pixels, not dp — matching this exact chart's own dot radii
     *  (2.5f/3f), which are already unscaled. Sized to equal the multi-series trend
     *  chart's own hollow-dot radius (4.5f) so the same "more than one" cue reads at one
     *  consistent size across both charts, even though the two charts differ on
     *  everything else (per-method colour there, single BODY ink here). */
    public static final float DAY_CLUSTER_RING_R = 4.5f;

    /* ---- the first-run setup (0.10) ------------------------------------------ */
    /* The approved setup mock's own pieces that nothing else in the app draws: the soft "i"
     * dot and the box it opens, the green response under a control and its amber warning
     * twin, and the red "Before you start" card. Everything else on those screens is the
     * existing surfaces, lime and ink. Pinned for contrast in SetupLookTest. */

    /** The closed "i": an 18 dp dot, no outline, a pale-blue letter. Open, it fills with
     *  {@link #INFO_BLUE} and the letter turns {@link #GROUND}. */
    public static final int SETUP_INFO_DOT = 0xFF1A2733;
    public static final int SETUP_INFO_INK = 0xFF8CC3EC;
    public static final int SETUP_INFO_DP = 18;
    /** The explanation an "i" opens, directly under its label: a blue-tinted surface. */
    public static final int SETUP_HELP_FILL = 0xFF0E1A24;
    public static final int SETUP_HELP_INK = 0xFFC5CFDA;
    /** The response under a control once it is used - green-tinted, the app answering. */
    public static final int SETUP_SAY_FILL = 0xFF141C0C;
    public static final int SETUP_SAY_EDGE = 0xFF26341A;
    public static final int SETUP_SAY_INK = 0xFFDDE6C8;
    /** The same box as a warning - amber-tinted words, never the pump's amber itself. */
    public static final int SETUP_WARN_FILL = 0xFF241A0E;
    public static final int SETUP_WARN_EDGE = 0xFF4A3416;
    public static final int SETUP_WARN_INK = 0xFFEAD2B0;
    /** The welcome's "Before you start" card: a dark red wash, its heading and its words. */
    public static final int SETUP_STOP_TOP = 0xFF261314;
    public static final int SETUP_STOP_BOTTOM = 0xFF1C1012;
    public static final int SETUP_STOP_EDGE = 0xFF5A2522;
    public static final int SETUP_STOP_HEAD = 0xFFFF8A80;
    public static final int SETUP_STOP_INK = 0xFFF3DCDA;
    /** A card's hairline edge, and the chosen segment's ring. */
    public static final int SETUP_CARD_EDGE = 0xFF1A202A;
    public static final int SETUP_SEG_EDGE = 0xFF2A3039;
    /** The suggested choice's lime dot, in dp. */
    public static final int SETUP_SUGGEST_DP = 6;
    /** The quieter half of a label ("· 3 a week") and a preview's caption. */
    public static final int SETUP_QUIET = 0xFF8894A3;

    /* ---- WCAG contrast — a real computation, not a guess -------------------- */

    /** The sRGB relative luminance of an opaque ARGB colour, per WCAG 2.1. */
    public static double luminance(int argb) {
        double r = channel(((argb >> 16) & 0xFF) / 255.0);
        double g = channel(((argb >> 8) & 0xFF) / 255.0);
        double b = channel((argb & 0xFF) / 255.0);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** The WCAG contrast ratio between two opaque colours — always >= 1.0, order-free. */
    public static double contrastRatio(int fg, int bg) {
        double l1 = luminance(fg), l2 = luminance(bg);
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** Whether a pairing meets WCAG AA: 4.5:1 for normal text, 3:1 for large text
     *  (>= 18pt, or >= 14pt bold — the readout, the state colours on chips, the button
     *  labels all qualify as large). */
    public static boolean meetsAA(int fg, int bg, boolean large) {
        return contrastRatio(fg, bg) >= (large ? 3.0 : 4.5);
    }
}
