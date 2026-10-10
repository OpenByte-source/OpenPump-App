package org.openpump;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * THE FIRST-RUN SETUP (0.10) - six short steps a new phone walks before Today: a welcome with
 * the safety points, units and the ceiling, the person and their cylinder, their week,
 * privacy and how a run feels, and a Ready screen that hands them to their first session.
 *
 * A RENDERER OVER THE MODEL, like every screen split out of SessionActivity: each choice is
 * written to the model and saved the moment it is made, through the same code Settings uses
 * for it (SettingsScreen's shared methods, SessionActivity#setPressureUnit, #addCylinder,
 * #schedChanged, #measRemindChanged), so quitting half way loses nothing and Settings shows
 * the same values. The only state of its own is where the person is in the walk and what
 * they have tapped - {@link Walk}, held on the Activity - which decides the responses: a
 * response answers something the person did, so it waits for them to do it.
 *
 * Every word comes from {@link SetupText}; FirstRun decides when the setup is owed and when
 * it is over. Nothing here commands the pump.
 */
final class SetupScreen {
    private final SessionActivity a;

    SetupScreen(SessionActivity a) { this.a = a; }

    /** The six steps. */
    static final int STEP_WELCOME = 0, STEP_UNITS = 1, STEP_YOU = 2, STEP_ROUTINE = 3,
        STEP_PRIVACY = 4, STEP_READY = 5, STEPS = 6;

    /** The first-session choices, in SetupText's own words for them. */
    static final String FIRST_TRAINER = "trainer", FIRST_STARTER = "starter", FIRST_OWN = "own";
    /** The experience choices, likewise. */
    static final String EXP_NEW = "new", EXP_SOME = "some", EXP_EXP = "exp";

    /** Which "i" is which - the keys of {@link Walk#open}. */
    private static final String I_WELCOME = "welcome", I_UNIT = "unit", I_CEIL = "ceil",
        I_CYL = "cyl", I_MARKS = "marks", I_DAYS = "days", I_REMIND = "remind", I_MEAS = "meas",
        I_OPEN = "open", I_COLOURS = "colours", I_HAPTIC = "haptic", I_BATTERY = "battery";

    /**
     * WHERE THE PERSON IS IN THE WALK, and what they have done in it - for the life of the
     * Activity only. Every setting itself is on the model; this holds only what the model has
     * no place for: the step, the tick on the welcome, Some versus Experienced (the model
     * keeps "new to pumping" and nothing finer), which explanations are open, and which
     * controls have been used, because a response is shown once its control is.
     */
    static final class Walk {
        int step = STEP_WELCOME;
        boolean agreed;
        /** "new", "some" or "exp"; null until chosen, then read from the model. */
        String exp;
        boolean unitTapped, cylSaved, marksTapped, daysTapped, measTapped, hapticTapped;
        /** The load unit was picked by hand - it then stops following the size unit. */
        boolean loadTapped;
        /** "Only me" was asked for on a phone with no screen lock. */
        boolean needsLock;
        String first = FIRST_TRAINER;
        final HashSet<String> open = new HashSet<String>();
        /** The battery row as last drawn, so a resume redraws only when it changed. */
        boolean drawnExempt;
        boolean growthTrackVisible;

        Walk() {
            // The one explanation open from the start: the battery's, on Ready.
            open.add(I_BATTERY);
        }
    }

    private Walk w() { return a.setupWalk; }

    /* ================================================================ the frame */

    /** The page the setup draws into, inside the Activity's body. Its own view, so the info
     *  dots' wider touch areas (InfoHits) go with it when another screen replaces it. */
    private LinearLayout page;
    private final List<View> infoDots = new ArrayList<View>();
    /** Responses drawn this time and last time, so only a new one is announced. */
    private List<String> said = new ArrayList<String>();
    private int saidOnStep = -1;

    void show() {
        Walk w = w();
        if (w.step < 0 || w.step >= STEPS) w.step = STEP_WELCOME;
        List<String> before = said;
        boolean sameStep = saidOnStep == w.step;
        said = new ArrayList<String>();
        saidOnStep = w.step;
        infoDots.clear();

        a.body.removeAllViews();
        a.enterDest(Nav.SCR_SETUP);
        page = Ui.col(a);
        page.setPadding(0, dp(Look.S5), 0, 0);
        a.body.addView(page, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        switch (w.step) {
            case STEP_WELCOME: welcome(); break;
            case STEP_UNITS:   units(); break;
            case STEP_YOU:     you(); break;
            case STEP_ROUTINE: routine(); break;
            case STEP_PRIVACY: privacy(); break;
            default:           ready(); break;
        }
        // The bar and the footer stay put while the step scrolls between them.
        a.pinSetupChrome(stepBar(w.step), footer(w.step));

        InfoHits hits = new InfoHits(page, infoDots);
        page.addOnLayoutChangeListener(hits);
        page.setTouchDelegate(hits);

        if (sameStep)
            for (int i = 0; i < said.size(); i++)
                if (!before.contains(said.get(i))) a.body.announceForAccessibility(said.get(i));
    }

    /** Back from a system screen: the battery row is the one thing that can have changed. */
    void onResume() {
        if (w().step == STEP_READY && a.isBatteryExempt() != w().drawnExempt) show();
    }

    /** The setup's Back - the footer's and the phone's: a step back, or, from the welcome,
     *  out of the app with the setup still owed. */
    void back() {
        Walk w = w();
        if (w.step <= STEP_WELCOME) { a.moveTaskToBack(true); return; }
        w.needsLock = false;
        w.step--;
        show();
        scrollTop();
    }

    private void next() {
        Walk w = w();
        if (w.step == STEP_WELCOME && !w.agreed) return;
        if (w.step >= STEP_READY) { finish(); return; }
        w.needsLock = false;
        w.step++;
        show();
        scrollTop();
    }

    /** The setup is over: saved as done, then the first session the person chose. The
     *  Starter goes through selectAndConfirmStart - Today, then START's own confirmation
     *  and gate, exactly as the checklist's row does. */
    private void finish() {
        FirstRun.finish(a.model);
        Store.save(a, a.model);
        String first = w().first;
        /* ONE ANSWER TO WHERE IT ENDS (FirstRun#finishAction): an enrolled phone's trainer
         * choice opens the Trainer as it stands - it never starts a fresh onboarding that
         * would reset a plan in progress (review L2). */
        int where = FirstRun.finishAction(first, starter() != null, a.model.trainerEnrolled);
        if (where == FirstRun.FINISH_STARTER) {
            a.selectAndConfirmStart(Model.SEED_STARTER_ROUTINE_ID);
        } else if (where == FirstRun.FINISH_LIBRARY) {
            a.showLibrary();
        } else if (where == FirstRun.FINISH_TRAINER_OPEN) {
            a.showTrainer();
        } else {
            // The trainer asks about marks again; it starts from the answer given here.
            a.trainerOnboardMarks = a.model.marksEasily;
            a.startTrainerOnboard(false);
        }
    }

    private void scrollTop() {
        if (a.bodyScroll != null) a.bodyScroll.post(new ScrollTop());
    }

    private final class ScrollTop implements Runnable {
        @Override public void run() { if (a.bodyScroll != null) a.bodyScroll.scrollTo(0, 0); }
    }

    /** What the first-run setup's bar calls it (polish G-Setup), beside the Trainer's own. */
    private static final String APP_SETUP = "App setup";

    /** The pinned "App setup · step n of 6" bar and its six segments. */
    private View stepBar(int step) {
        return kit().stepBar(APP_SETUP, step, STEPS, null);
    }

    /** The pinned footer: Back and Next. Back is hidden on the welcome, and kept in its
     *  place so Next does not jump between steps. */
    private View footer(int step) {
        String label = step == STEP_READY
            ? SetupText.finalButton(w().first, a.model.trainerEnrolled) : SetupText.NEXT;
        return kit().footer(SetupText.BACK, step != STEP_WELCOME, new BackTap(),
            label, step != STEP_WELCOME || w().agreed, new NextTap());
    }

    /** The setup's controls, shared with the trainer's setup. */
    private SetupKit kit;

    private SetupKit kit() {
        if (kit == null) kit = new SetupKit(a);
        return kit;
    }

    private final class BackTap implements View.OnClickListener {
        @Override public void onClick(View v) { back(); }
    }

    private final class NextTap implements View.OnClickListener {
        @Override public void onClick(View v) { next(); }
    }

    /** A step's head: its letter in a lime square, its title and its question. */
    private void hero(String mark, String title, String question) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = text(mark, 18f, Look.ON_ACCENT);
        ic.setTypeface(Typeface.DEFAULT_BOLD);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(Ui.roundRect(a, Ui.ACCENT, Look.R_CARD));
        Ui.decorative(ic);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(44), dp(44));
        il.rightMargin = dp(12);
        row.addView(ic, il);
        LinearLayout col = Ui.col(a);
        TextView t = text(title, 19f, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        col.addView(t);
        TextView q = text(question, 13.5f, Ui.DIM);
        q.setPadding(0, dp(2), 0, 0);
        col.addView(q);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rl = full();
        rl.bottomMargin = dp(12);
        page.addView(row, rl);
    }

    /* ================================================================ step 1 */

    private void welcome() {
        Walk w = w();
        LinearLayout col = Ui.col(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(a);
        logo.setImageResource(IncognitoShell.iconRes(Incognito.REAL));
        Ui.decorative(logo);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(72), dp(72));
        ll.topMargin = dp(Look.S2);
        col.addView(logo, ll);

        TextView title = text(SetupText.WELCOME_TITLE, 26f, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        col.addView(title, gapped(12));

        LinearLayout tagRow = new LinearLayout(a);
        tagRow.setOrientation(LinearLayout.HORIZONTAL);
        tagRow.setGravity(Gravity.CENTER);
        TextView tag = text(SetupText.WELCOME_LINE, 14.5f, Ui.DIM);
        tag.setGravity(Gravity.CENTER);
        tag.setMaxWidth(dp(260));
        tagRow.addView(tag);
        tagRow.addView(infoDot(I_WELCOME, SetupText.WELCOME_TITLE), dotParams());
        col.addView(tagRow, gapped(Look.S3));
        page.addView(col, full());
        help(page, I_WELCOME, SetupText.WELCOME_WHY);

        // BEFORE YOU START: the three safety points, on the red card, before anything else.
        LinearLayout warn = Ui.col(a);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{ Look.SETUP_STOP_TOP, Look.SETUP_STOP_BOTTOM });
        bg.setCornerRadius(dp(Look.R_CARD));
        bg.setStroke(Math.max(1, dp(1)), Look.SETUP_STOP_EDGE);
        warn.setBackground(bg);
        warn.setPadding(dp(15), dp(13), dp(15), dp(13));
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        WarnGlyph glyph = new WarnGlyph(a);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(20), dp(20));
        gl.rightMargin = dp(9);
        head.addView(glyph, gl);
        TextView ht = text(SetupText.BEFORE_TITLE, 14f, Look.SETUP_STOP_HEAD);
        ht.setTypeface(Typeface.DEFAULT_BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) ht.setAccessibilityHeading(true);
        head.addView(ht);
        warn.addView(head);
        bullet(warn, SetupText.BEFORE_1_LEAD, SetupText.BEFORE_1_REST);
        bullet(warn, SetupText.BEFORE_2_LEAD, SetupText.BEFORE_2_REST);
        bullet(warn, SetupText.BEFORE_3_LEAD, SetupText.BEFORE_3_REST);
        LinearLayout.LayoutParams wl = full();
        wl.topMargin = dp(Look.S5);
        page.addView(warn, wl);

        // THE TICK. Next stays off until it is on.
        LinearLayout agree = new LinearLayout(a);
        agree.setOrientation(LinearLayout.HORIZONTAL);
        agree.setGravity(Gravity.CENTER_VERTICAL);
        agree.setMinimumHeight(dp(48));
        agree.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable ab = Ui.roundRect(a, Ui.SURF, Look.R_CARD);
        ab.setStroke(dp(2), w.agreed ? Ui.ACCENT : Ui.LINE);
        agree.setBackground(ab);
        FrameLayout box = new FrameLayout(a);
        android.graphics.drawable.GradientDrawable bb = Ui.roundRect(a,
            w.agreed ? Ui.ACCENT : android.graphics.Color.TRANSPARENT, 7);
        bb.setStroke(dp(2), w.agreed ? Ui.ACCENT : Ui.DIM);
        box.setBackground(bb);
        if (w.agreed) {
            ImageView tick = Ui.iconView(a, R.drawable.ic_check, Look.ON_ACCENT, 18);
            box.addView(tick, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        }
        LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(dp(24), dp(24));
        xl.rightMargin = dp(12);
        agree.addView(box, xl);
        TextView at = text(SetupText.AGREE, 14f, Ui.TEXT);
        Ui.medium(at);
        agree.addView(at, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.group(agree, SetupText.AGREE
            + (w.agreed ? SetupText.AGREE_ON_SAID : SetupText.AGREE_OFF_SAID));
        agree.setSelected(w.agreed);
        agree.setClickable(true);
        agree.setOnClickListener(new AgreeTap());
        agree.setOnTouchListener(new Ui.Press());
        LinearLayout.LayoutParams gl2 = full();
        gl2.topMargin = dp(12);
        page.addView(agree, gl2);
    }

    private void bullet(LinearLayout parent, String lead, String rest) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View dot = new View(a);
        dot.setBackground(Ui.roundRect(a, Ui.CRIT, 3));
        Ui.decorative(dot);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(6), dp(6));
        dl.topMargin = dp(8);
        dl.rightMargin = dp(10);
        row.addView(dot, dl);
        SpannableStringBuilder sb = new SpannableStringBuilder(lead + rest);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, lead.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Ui.TEXT), 0, lead.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = new TextView(a);
        t.setText(sb);
        t.setTextColor(Look.SETUP_STOP_INK);
        t.setTextSize(Look.SP_LABEL);
        t.setLineSpacing(0f, 1.25f);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, gapped(7));
    }

    private final class AgreeTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            w().agreed = !w().agreed;
            show();
        }
    }

    /* ================================================================ step 2 */

    private void units() {
        Walk w = w();
        hero(SetupText.UNITS_MARK, SetupText.UNITS_TITLE, SetupText.UNITS_QUESTION);
        legend();

        LinearLayout c = card();
        label(c, SetupText.PRESSURE_LABEL, I_UNIT, SetupText.UNIT_WHY);
        String[] units = { Model.Fmt.U_INHG, Model.Fmt.U_CMHG, Model.Fmt.U_KPA };
        int chosen = indexOf(units, a.model.unit);
        View.OnClickListener[] taps = new View.OnClickListener[units.length];
        for (int i = 0; i < units.length; i++) taps[i] = new PickUnit(units[i]);
        seg(c, units, chosen, 0, taps);
        if (w.unitTapped) say(c, SetupText.unitResponse(a.model.unit), false);

        label(c, SetupText.SIZE_LABEL, null, null);
        String[] sizes = { Model.Fmt.S_CM, Model.Fmt.S_IN };
        seg(c, new String[]{ SetupText.SIZE_CM, SetupText.SIZE_IN }, indexOf(sizes, a.model.sizeUnit),
            0, new View.OnClickListener[]{ new PickSize(Model.Fmt.S_CM), new PickSize(Model.Fmt.S_IN) });

        // THE LOAD UNIT (owner, 2026-09-30), the same way: pounds or kilos, following the
        // measurements answer until it is picked by hand. The one marked "suggested" is the
        // size unit's (cm -> kg, in -> lb), and follows it when that is changed here - it
        // marked lb beside a kg chosen by the cm default (device walk, E-M1).
        label(c, SetupText.LOAD_LABEL, null, null);
        String[] loads = { Model.Fmt.L_LB, Model.Fmt.L_KG };
        seg(c, new String[]{ SetupText.LOAD_LB, SetupText.LOAD_KG },
            indexOf(loads, a.model.loadUnit),
            indexOf(loads, Model.Fmt.defaultLoadUnit(a.model.sizeUnit)), new View.OnClickListener[]{
                new PickLoad(Model.Fmt.L_LB), new PickLoad(Model.Fmt.L_KG) });

        LinearLayout c2 = card();
        int ceil = a.model.ceilKpa;
        LinearLayout step = stepper(Model.Fmt.p(ceil), SetupText.LOWER_SAID, SetupText.HIGHER_SAID,
            new BumpCeiling(-1), new BumpCeiling(+1));
        kv(c2, SetupText.CEILING_LABEL, SetupText.ceilingSubtitle(ceil), I_CEIL,
            SetupText.CEILING_WHY, step);
        say(c2, SetupText.ceilingWarning(ceil), true);
    }

    private final class PickUnit implements View.OnClickListener {
        private final String u;
        PickUnit(String u) { this.u = u; }
        @Override public void onClick(View v) {
            a.setPressureUnit(u);
            w().unitTapped = true;
            show();
        }
    }

    private final class PickSize implements View.OnClickListener {
        private final String u;
        PickSize(String u) { this.u = u; }
        @Override public void onClick(View v) {
            a.settingsScreenOf().setSizeUnit(u);
            // The load unit follows the size unit (inches -> lb, cm -> kg) until it is picked.
            if (!w().loadTapped)
                a.settingsScreenOf().setLoadUnit(Model.Fmt.defaultLoadUnit(u));
            show();
        }
    }

    private final class PickLoad implements View.OnClickListener {
        private final String u;
        PickLoad(String u) { this.u = u; }
        @Override public void onClick(View v) {
            a.settingsScreenOf().setLoadUnit(u);
            w().loadTapped = true;
            show();
        }
    }

    private final class BumpCeiling implements View.OnClickListener {
        private final int d;
        BumpCeiling(int d) { this.d = d; }
        @Override public void onClick(View v) {
            if (a.settingsScreenOf().bumpCeiling(d)) show();
        }
    }

    /* ================================================================ step 3 */

    private String exp() {
        Walk w = w();
        // On a resumed setup Some and Experienced cannot be told apart - the model keeps
        // only "new to pumping" - so anything else reads as Some.
        if (w.exp == null) w.exp = a.model.rxNewToPumping ? EXP_NEW : EXP_SOME;
        return w.exp;
    }

    private void you() {
        Walk w = w();
        hero(SetupText.YOU_MARK, SetupText.YOU_TITLE, SetupText.YOU_QUESTION);

        LinearLayout c = card();
        label(c, SetupText.EXP_LABEL, null, null);
        String[] keys = { EXP_NEW, EXP_SOME, EXP_EXP };
        seg(c, new String[]{ SetupText.EXP_NEW, SetupText.EXP_SOME, SetupText.EXP_EXP },
            indexOf(keys, exp()), 0, new View.OnClickListener[]{
                new PickExp(EXP_NEW), new PickExp(EXP_SOME), new PickExp(EXP_EXP) });
        say(c, SetupText.experienceResponse(exp()), false);

        LinearLayout c2 = card();
        int n = a.model.cylinders.size();
        label(c2, n > 1 ? SetupText.CYL_LABEL_MANY : SetupText.CYL_LABEL, I_CYL, SetupText.CYL_WHY);
        // The cards, the dashed Add and the editor sheet are SetupKit's, shared with the
        // trainer's own setup (0.10).
        kit().rack(c2, new RackChanged(), false, false);
        if (w.cylSaved && n > 0) say(c2, SetupText.CYL_SAVED, false);

        LinearLayout c3 = card();
        kv(c3, SetupText.MARKS_LABEL, SetupText.MARKS_SUBTITLE, I_MARKS, SetupText.MARKS_WHY,
            switchFor(a.model.marksEasily, SetupText.MARKS_LABEL, new ToggleMarks()));
        if (w.marksTapped && a.model.marksEasily) say(c3, SetupText.MARKS_ON, false);
    }

    private final class PickExp implements View.OnClickListener {
        private final String e;
        PickExp(String e) { this.e = e; }
        @Override public void onClick(View v) {
            w().exp = e;
            a.model.rxNewToPumping = EXP_NEW.equals(e);
            Store.save(a, a.model);
            show();
        }
    }

    private final class ToggleMarks implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.marksEasily = !a.model.marksEasily;
            Store.save(a, a.model);
            w().marksTapped = true;
            show();
        }
    }

    /** The setup's answer to a change in the rack: a saved cylinder earns the response. */
    private final class RackChanged implements SetupKit.RackListener {
        @Override public void rackChanged(boolean saved) {
            if (saved) w().cylSaved = true;
            show();
        }
    }

    /* ================================================================ step 4 */

    private void routine() {
        Walk w = w();
        hero(SetupText.ROUTINE_MARK, SetupText.ROUTINE_TITLE, SetupText.ROUTINE_QUESTION);
        Schedule s = a.model.sched;
        int count = s.count();

        LinearLayout c = card();
        label(c, SetupText.daysLabel(count), I_DAYS, SetupText.DAYS_WHY);
        LinearLayout days = new LinearLayout(a);
        days.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Schedule.DAYS; i++) {
            boolean on = s.days[i];
            Button d = new Button(a);
            d.setText(Schedule.DAY_LETTER[i]);
            d.setAllCaps(false);
            d.setTextSize(Look.SP_LABEL);
            d.setTypeface(Typeface.DEFAULT_BOLD);
            d.setTextColor(on ? Look.ON_ACCENT : Ui.DIM);
            d.setPadding(0, 0, 0, 0);
            d.setMinWidth(0);
            d.setMinimumWidth(0);
            d.setStateListAnimator(null);
            d.setBackground(new android.graphics.drawable.InsetDrawable(
                Ui.roundRect(a, on ? Ui.ACCENT : Ui.SURFHI, Look.R_CTRL), 0, dp(2), 0, dp(2)));
            d.setContentDescription(A11y.state(Schedule.DAY_ABBR[i], on));
            d.setSelected(on);
            d.setOnClickListener(new ToggleDay(i));
            d.setOnTouchListener(new Ui.Press());
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i < Schedule.DAYS - 1) dl.rightMargin = dp(4);
            days.addView(d, dl);
        }
        c.addView(days, gapped(Look.S3));
        if (w.daysTapped) {
            String key = FirstRun.daysKey(count);
            say(c, SetupText.daysResponse(key), "many".equals(key));
        }

        kv(c, SetupText.TIME_LABEL, null, null, null, stepper(s.hhmm(), SetupText.EARLIER_SAID,
            SetupText.LATER_SAID, new BumpHour(-1), new BumpHour(+1)));
        kv(c, SetupText.REMIND_LABEL, SetupText.remindersSubtitle(s), I_REMIND,
            SetupText.REMIND_WHY, switchFor(s.remind, SetupText.REMIND_LABEL, new ToggleRemind()));

        LinearLayout c2 = card();
        int segNow = a.model.meas.cadenceSegment();
        label(c2, SetupText.measureLabel(segNow, count), I_MEAS, SetupText.MEASURE_WHY);
        int[] segs = { Model.Meas.CAD_SESSIONS, Model.Meas.CAD_WEEK, Model.Meas.CAD_NEVER };
        int chosen = -1;
        for (int i = 0; i < segs.length; i++) if (segs[i] == segNow) chosen = i;
        seg(c2, new String[]{ SetupText.MEASURE_EVERY_5, SetupText.MEASURE_WEEK,
            SetupText.MEASURE_NEVER }, chosen, 0, new View.OnClickListener[]{
                new PickMeasure(Model.Meas.CAD_SESSIONS), new PickMeasure(Model.Meas.CAD_WEEK),
                new PickMeasure(Model.Meas.CAD_NEVER) });
        if (w.measTapped) say(c2, SetupText.measureResponse(segNow), false);
    }

    /** One training day on or off, through schedChanged as Settings' own day switch is. The
     *  last day stays: a setup that ends with no training day has planned nothing. */
    private final class ToggleDay implements View.OnClickListener {
        private final int i;
        ToggleDay(int i) { this.i = i; }
        @Override public void onClick(View v) {
            Schedule s = a.model.sched;
            if (s.days[i] && s.count() <= 1) return;
            s.days[i] = !s.days[i];
            w().daysTapped = true;
            a.schedChanged();
        }
    }

    /** The training time, an hour a tap - the setup's coarse version of Settings' clock. */
    private final class BumpHour implements View.OnClickListener {
        private final int d;
        BumpHour(int d) { this.d = d; }
        @Override public void onClick(View v) {
            Schedule s = a.model.sched;
            s.hour = ((s.hour + d) % 24 + 24) % 24;
            a.schedChanged();
        }
    }

    /** Settings' own reminder switch: the permission is asked here, at the tap, through the
     *  same pendingRemindGrant path, and a "no" leaves it off. */
    private final class ToggleRemind implements View.OnClickListener {
        @Override public void onClick(View v) { a.settingsScreenOf().toggleRemind(); }
    }

    /** A cadence, through Model.Meas#chooseSegment and measRemindChanged as Settings' own
     *  segment is. "Every 5" is the label's promise, so its interval is set to five. */
    private final class PickMeasure implements View.OnClickListener {
        private final int seg;
        PickMeasure(int s) { seg = s; }
        @Override public void onClick(View v) {
            if (seg == Model.Meas.CAD_SESSIONS) a.model.meas.n = FirstRun.MEASURE_EVERY_SESSIONS;
            a.model.meas.chooseSegment(seg);
            w().measTapped = true;
            a.measRemindChanged();
        }
    }

    /* ================================================================ step 5 */

    private void privacy() {
        Walk w = w();
        hero(SetupText.PRIVACY_MARK, SetupText.PRIVACY_TITLE, SetupText.PRIVACY_QUESTION);
        int identity = Incognito.identity(a.model);
        boolean disguised = Incognito.disguised(identity);

        // ON YOUR HOME SCREEN: the real icon or one of the three disguises.
        LinearLayout c = card();
        label(c, SetupText.HOME_TITLE, null, null);
        c.addView(text(SetupText.HOME_LINE, 12.5f, Ui.DIM), gapped(Look.S2));
        LinearLayout tiles = new LinearLayout(a);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tile(tiles, Incognito.REAL, identity == Incognito.REAL, true);
        for (int i = 0; i < Incognito.DISGUISES.length; i++) {
            int d = Incognito.DISGUISES[i];
            tile(tiles, d, identity == d, i == Incognito.DISGUISES.length - 1);
        }
        c.addView(tiles, gapped(Look.S3));
        TextView nt = Ui.microLabel(a, null, SetupText.NOTIF_TITLE, Look.SETUP_QUIET);
        c.addView(nt, gapped(Look.S4));
        c.addView(notificationPreview(identity, disguised), gapped(Look.S2));
        c.addView(text(SetupText.notificationLine(disguised), 12.5f, Ui.DIM), gapped(Look.S2));

        // WHO CAN OPEN IT: anyone, or only me - the whole-app lock.
        LinearLayout c2 = card();
        label(c2, SetupText.OPEN_TITLE, I_OPEN, SetupText.OPEN_WHY);
        boolean locked = AppLock.wholeAppOn(a.model);
        option(c2, SetupText.OPEN_ANYONE, SetupText.OPEN_ANYONE_LINE, !locked, false,
            identityIcon(identity, false), new PickLock(false));
        option(c2, SetupText.OPEN_ONLY_ME, SetupText.OPEN_ONLY_ME_LINE, locked, false,
            identityIcon(identity, true), new PickLock(true));
        if (w.needsLock && !locked) say(c2, SetupText.OPEN_NEEDS_LOCK, true);

        // DURING A RUN: the colours, with a small run screen that follows them, and the ticks.
        LinearLayout c3 = card();
        label(c3, SetupText.RUN_TITLE, null, null);
        LinearLayout colRow = new LinearLayout(a);
        colRow.setOrientation(LinearLayout.HORIZONTAL);
        colRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = Ui.col(a);
        words.addView(titleWithInfo(SetupText.COLOURS_LABEL, I_COLOURS));
        words.addView(text(SetupText.COLOURS_NOTE, 12.5f, Ui.DIM), gapped(Look.S1));
        colRow.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(128),
            ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.leftMargin = dp(Look.S4);
        colRow.addView(miniRun(), ml);
        c3.addView(colRow, gapped(Look.S3));
        help(c3, I_COLOURS, SetupText.COLOURS_WHY);
        int look = !a.model.runColourOn ? 2
            : RunLook.clampWhere(a.model.runColourWhere) == RunLook.WHERE_LINE ? 0
            : RunLook.clampWhere(a.model.runColourWhere) == RunLook.WHERE_SOFT ? 1 : -1;
        seg(c3, new String[]{ SetupText.COLOURS_LINE, SetupText.COLOURS_TINT, SetupText.COLOURS_OFF },
            look, 0, new View.OnClickListener[]{ new PickColours(RunLook.WHERE_LINE),
                new PickColours(RunLook.WHERE_SOFT), new PickColours(-1) });
        kv(c3, SetupText.HAPTIC_LABEL, SetupText.HAPTIC_SUBTITLE, I_HAPTIC, SetupText.HAPTIC_WHY,
            switchFor(a.model.hapticTicks, SetupText.HAPTIC_LABEL, new ToggleHaptic()));
        if (w.hapticTapped && !a.model.hapticTicks) say(c3, SetupText.HAPTIC_OFF, false);
    }

    /** One home-screen choice: the real icon or a disguise, with its name and its caption. */
    private void tile(LinearLayout row, int identity, boolean on, boolean last) {
        LinearLayout t = Ui.col(a);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setMinimumHeight(dp(48));
        t.setPadding(dp(2), dp(9), dp(2), dp(8));
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL);
        bg.setStroke(dp(2), on ? Ui.ACCENT : android.graphics.Color.TRANSPARENT);
        t.setBackground(bg);
        ImageView icon = new ImageView(a);
        icon.setImageResource(IncognitoShell.iconRes(identity));
        t.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        String name = Incognito.shownName(identity);
        TextView n = text(name, 11f, Ui.TEXT);
        n.setGravity(Gravity.CENTER);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(n, gapped(4));
        String cap = Incognito.disguised(identity) ? SetupText.HOME_DISGUISE_CAPTION
                                                   : SetupText.HOME_REAL_CAPTION;
        TextView cp = text(cap.toUpperCase(java.util.Locale.US), 9.5f, on ? Ui.ACCENT : Look.SETUP_QUIET);
        cp.setLetterSpacing(0.06f);
        cp.setGravity(Gravity.CENTER);
        t.addView(cp, gapped(2));
        Ui.group(t, A11y.state(name, on) + ". " + cap);
        t.setSelected(on);
        t.setClickable(true);
        t.setOnClickListener(new PickIdentity(identity));
        t.setOnTouchListener(new Ui.Press());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (!last) lp.rightMargin = dp(Look.S2);
        row.addView(t, lp);
    }

    /** The notification the chosen look would post mid-run: its icon, its title and its
     *  words, in the chosen pressure unit. */
    private View notificationPreview(int identity, boolean disguised) {
        LinearLayout box = Ui.col(a);
        box.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(a);
        icon.setImageResource(IncognitoShell.iconRes(identity));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(14), dp(14));
        il.rightMargin = dp(Look.S2);
        top.addView(icon, il);
        TextView title = text(SetupText.notificationTitle(identity), 11f, Ui.TEXT);
        Ui.medium(title);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(text(SetupText.NOTIF_NOW, 11f, Ui.DIM));
        box.addView(top);
        String body = SetupText.notificationBody(disguised);
        box.addView(text(body, 12f, Ui.TEXT), gapped(3));
        Ui.group(box, SetupText.notificationTitle(identity) + ": " + body);
        return box;
    }

    /** The app's icon as the phone would show it, with a padlock when it asks to open. */
    private View identityIcon(int identity, boolean lock) {
        FrameLayout f = new FrameLayout(a);
        ImageView icon = new ImageView(a);
        icon.setImageResource(IncognitoShell.iconRes(identity));
        f.addView(icon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        if (lock) {
            FrameLayout badge = new FrameLayout(a);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(Look.SETUP_SEG_EDGE);
            badge.setBackground(g);
            badge.addView(Ui.iconView(a, R.drawable.ic_lock, Ui.TEXT, 11),
                new FrameLayout.LayoutParams(dp(11), dp(11), Gravity.CENTER));
            f.addView(badge, new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.END | Gravity.BOTTOM));
        }
        Ui.decorative(f);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        return f;
    }

    /**
     * A home-screen pick. A disguise turns incognito on - the master, with the set it brings -
     * and chooses that disguise (Incognito#choose, as Settings' icon sheet does); the real
     * icon turns it off. Then applyIncognito, the one place the launcher is switched.
     *
     * WHO CAN OPEN IT STAYS AS IT WAS ANSWERED. The master's first set includes incognito's
     * own app lock; the setup asks that as its own question below, so picking a look does not
     * answer it too - the lock comes along only when the person already chose "Only me" (and
     * so has a screen lock), and never on a phone without one, the rule Settings' incognito
     * switch keeps.
     */
    private final class PickIdentity implements View.OnClickListener {
        private final int identity;
        PickIdentity(int id) { identity = id; }
        @Override public void onClick(View v) {
            Model m = a.model;
            w().needsLock = false;   // the warning answers "Only me", the last thing tapped
            if (Incognito.identity(m) == identity) { show(); return; }
            if (Incognito.disguised(identity)) {
                boolean wasLocked = AppLock.wholeAppOn(m);
                if (!m.incognito) Incognito.setMaster(m, true);
                Incognito.choose(m, identity);
                if (m.incognitoLock && (!wasLocked || !SettingsScreen.deviceSecure(a)))
                    Incognito.setFeature(m, Incognito.APP_LOCK, false);
            } else {
                if (m.incognito) Incognito.setMaster(m, false);
                Incognito.choose(m, Incognito.REAL);
            }
            Store.save(a, m);
            a.applyIncognito();
            show();
        }
    }

    /**
     * Who can open it. "Only me" is the App lock card's own two switches - the lock on, for
     * the whole app - and is refused, in words under the choice rather than a dialog, on a
     * phone with nothing to ask for (SettingsScreen#deviceSecure). "Anyone" turns every
     * whole-app lock off, incognito's included, so the answer is what happens.
     */
    private final class PickLock implements View.OnClickListener {
        private final boolean onlyMe;
        PickLock(boolean onlyMe) { this.onlyMe = onlyMe; }
        @Override public void onClick(View v) {
            Model m = a.model;
            Walk w = w();
            if (onlyMe) {
                if (AppLock.wholeAppOn(m)) return;
                if (!SettingsScreen.deviceSecure(a)) {
                    w.needsLock = true;
                    show();
                    return;
                }
                w.needsLock = false;
                m.appLockOn = true;
                AppLock.setScopeToggle(m, AppLock.WHOLE_APP, true);
            } else {
                w.needsLock = false;
                if (!AppLock.wholeAppOn(m)) { show(); return; }
                AppLock.setScopeToggle(m, AppLock.WHOLE_APP, false);
                m.appLockOn = false;
                if (m.incognitoLock) Incognito.setFeature(m, Incognito.APP_LOCK, false);
            }
            Store.save(a, m);
            a.applyIncognito();
            show();
        }
    }

    /** Run colours: on at the status line, on with a soft tint too, or off - the run colours
     *  sheet's own two settings. */
    private final class PickColours implements View.OnClickListener {
        private final int where;
        PickColours(int where) { this.where = where; }
        @Override public void onClick(View v) {
            w().needsLock = false;
            if (where < 0) {
                a.model.runColourOn = false;
            } else {
                a.model.runColourOn = true;
                a.model.runColourWhere = RunLook.clampWhere(where);
            }
            Store.save(a, a.model);
            show();
        }
    }

    private final class ToggleHaptic implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.settingsScreenOf().toggleHaptic();
            w().hapticTapped = true;
            w().needsLock = false;
            show();
        }
    }

    /** A small run screen in the chosen colours: the status line, the clock and the trace. */
    private View miniRun() {
        boolean on = a.model.runColourOn;
        int work = RunLook.colourOf(a.model.runColours, RunLook.WORK);
        int where = RunLook.clampWhere(a.model.runColourWhere);
        int ground = on && where != RunLook.WHERE_LINE
            ? over(work, RunLook.tintAlpha(where), Ui.BG) : Ui.BG;
        LinearLayout box = Ui.col(a);
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, ground, Look.R_CTRL);
        bg.setStroke(Math.max(1, dp(1)), Look.SETUP_SEG_EDGE);
        box.setBackground(bg);
        box.setPadding(dp(7), dp(7), dp(7), dp(7));

        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setBackground(Ui.roundRect(a, on ? work : Ui.SURFHI, 6));
        line.setPadding(dp(6), dp(5), dp(6), dp(5));
        int ink = on ? RunLook.inkOn(work) : Ui.TEXT;
        TextView l = text(SetupText.PREVIEW_STEP, 8.5f, ink);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        line.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView r = text(SetupText.PREVIEW_LEFT, 8.5f, ink);
        r.setTypeface(Typeface.DEFAULT_BOLD);
        line.addView(r);
        box.addView(line);

        TextView clock = text(SetupText.PREVIEW_CLOCK, 20f, on ? work : Ui.TEXT);
        clock.setTypeface(Typeface.MONOSPACE);
        box.addView(clock, gapped(5));

        View trace = new View(a);
        android.graphics.drawable.GradientDrawable tg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{ ((0x59) << 24) | ((on ? work : Ui.DIM) & 0x00FFFFFF), 0x00000000 });
        tg.setCornerRadius(dp(5));
        trace.setBackground(tg);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
        tl.topMargin = dp(5);
        box.addView(trace, tl);
        Ui.decorative(box);
        box.setContentDescription(null);
        return box;
    }

    /** `top` at `alpha` over the opaque `bottom`. */
    private static int over(int top, float alpha, int bottom) {
        float k = Math.max(0f, Math.min(1f, alpha));
        int r = Math.round(((top >> 16) & 0xFF) * k + ((bottom >> 16) & 0xFF) * (1 - k));
        int g = Math.round(((top >> 8) & 0xFF) * k + ((bottom >> 8) & 0xFF) * (1 - k));
        int b = Math.round((top & 0xFF) * k + (bottom & 0xFF) * (1 - k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /* ================================================================ step 6 */

    private Model.Routine starter() {
        return a.model.routine(Model.SEED_STARTER_ROUTINE_ID);
    }

    /** The Starter routine's own length, in whole minutes - the words follow the routine. */
    private int starterMinutes() {
        long sec = PlannedTime.clockSec(a.model, starter());
        return (int) Math.max(1, Math.round(sec / 60.0));
    }

    private void ready() {
        Walk w = w();
        if (GrowthTrackOnboarding.shouldOffer(a.model, RunService.isRunning(), a.dataStoreUnreadable())) {
            GrowthTrackOnboarding.presented(a.model);
            Store.save(a, a.model);
            w.growthTrackVisible = true;
        }
        TextView h = text(SetupText.READY_TITLE, 22f, Ui.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) h.setAccessibilityHeading(true);
        LinearLayout.LayoutParams hl = full();
        hl.bottomMargin = dp(Look.S4);
        page.addView(h, hl);

        if (w.growthTrackVisible) {
            LinearLayout gt = card();
            Ui.head(a, gt, "Connect your own GrowthTrack account?");
            Ui.note(a, gt, "GrowthTrack records PE training sessions, routines and measurements. Connection is optional; you can skip and connect later in Settings.");
            Ui.noteInfo(a, gt, "Nothing uploads just by connecting.", "Your choice",
                "Use your own account and approve in your browser. While connected, every new real finished session syncs automatically. Disconnected sessions and earlier history stay on your phone. Skipping, cancelling or going back leaves OpenPump ready to use.");
            Button connect = Ui.flat(a, gt, "Connect to GrowthTrack");
            connect.setOnClickListener(new GrowthTrackChoice(true));
            Button info = Ui.flat(a, gt, "About GrowthTrack and account setup");
            info.setOnClickListener(new GrowthTrackActivity.InformationTap(a));
            Button skip = Ui.flat(a, gt, "Skip for now");
            skip.setOnClickListener(new GrowthTrackChoice(false));
        }

        // THE BATTERY: the one explanation open from the start, and the one row that goes
        // out to a system screen - so the answer is read again on the way back (onResume).
        boolean exempt = a.isBatteryExempt();
        w.drawnExempt = exempt;
        LinearLayout c = card();
        Button allow = null;
        if (!exempt) {
            allow = button(SetupText.BATTERY_ALLOW, false);
            allow.setTextSize(Look.SP_LABEL);
            allow.setContentDescription(SetupText.BATTERY_ALLOW + ": " + SetupText.BATTERY_LABEL);
            allow.setOnClickListener(new AllowBattery());
            allow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        }
        kv(c, SetupText.BATTERY_LABEL, SetupText.BATTERY_SUBTITLE, I_BATTERY,
            SetupText.BATTERY_WHY, allow);
        if (exempt) say(c, SetupText.BATTERY_DONE, false);

        TextView sl = Ui.microLabel(a, null, SetupText.SUMMARY_TITLE, Ui.DIM);
        page.addView(sl, gapped(Look.S2));
        LinearLayout sum = card();
        summary(sum);

        TextView fl = Ui.microLabel(a, null, SetupText.FIRST_TITLE, Ui.DIM);
        page.addView(fl, gapped(Look.S2));
        LinearLayout opts = Ui.col(a);
        if (starter() == null && FIRST_STARTER.equals(w.first)) w.first = FIRST_TRAINER;
        boolean enrolled = a.model.trainerEnrolled;
        option(opts, enrolled ? SetupText.FIRST_TRAINER_ENROLLED : SetupText.FIRST_TRAINER,
            enrolled ? SetupText.FIRST_TRAINER_ENROLLED_LINE : SetupText.FIRST_TRAINER_LINE,
            FIRST_TRAINER.equals(w.first), true, null, new PickFirst(FIRST_TRAINER));
        if (starter() != null)
            option(opts, SetupText.FIRST_STARTER, SetupText.FIRST_STARTER_LINE,
                FIRST_STARTER.equals(w.first), false, null, new PickFirst(FIRST_STARTER));
        option(opts, SetupText.FIRST_OWN, SetupText.FIRST_OWN_LINE,
            FIRST_OWN.equals(w.first), false, null, new PickFirst(FIRST_OWN));
        say(opts, SetupText.firstResponse(w.first, starter() == null ? 0 : starterMinutes(),
            enrolled), false);
        LinearLayout.LayoutParams ol = full();
        ol.topMargin = dp(Look.S3);
        page.addView(opts, ol);
    }

    private final class GrowthTrackChoice implements View.OnClickListener {
        final boolean connect;
        GrowthTrackChoice(boolean connect) { this.connect = connect; }
        @Override public void onClick(View view) {
            w().growthTrackVisible = false;
            show();
            if (connect) GrowthTrackActivity.open(a, null);
        }
    }

    private void summary(LinearLayout parent) {
        Model m = a.model;
        String size = Model.Fmt.S_IN.equals(m.sizeUnit) ? SetupText.SIZE_IN : SetupText.SIZE_CM;
        sumRow(parent, SetupText.SUM_UNITS, m.unit + " · " + size + " · " + m.loadUnit, true);
        sumRow(parent, SetupText.SUM_CEILING, Model.Fmt.p(m.ceilKpa), false);
        String e = exp();
        sumRow(parent, SetupText.SUM_PUMPING, EXP_NEW.equals(e) ? SetupText.EXP_NEW
            : EXP_SOME.equals(e) ? SetupText.SUM_SOME : SetupText.EXP_EXP, false);
        Model.Cylinder cyl = m.cylinder();
        sumRow(parent, SetupText.SUM_CYLINDER, cyl == null ? SetupText.SUM_NO_CYLINDER : cyl.label,
            false);
        sumRow(parent, SetupText.SUM_TRAINING, m.sched.daysLine() + " · " + m.sched.hhmm()
            + (m.sched.remind ? " · " + SetupText.SUM_REMINDERS : ""), false);
        sumRow(parent, SetupText.SUM_MEASURE, SetupText.measureSummary(m.meas), false);
        int identity = Incognito.identity(m);
        sumRow(parent, SetupText.SUM_PRIVACY, (Incognito.disguised(identity)
            ? Incognito.shownName(identity) : SetupText.SUM_NOT_DISGUISED)
            + (AppLock.wholeAppOn(m) ? " · " + SetupText.SUM_LOCK : ""), false);
        String colours = !m.runColourOn ? SetupText.SUM_NO_COLOURS
            : RunLook.clampWhere(m.runColourWhere) == RunLook.WHERE_LINE ? SetupText.COLOURS_LINE
            : RunLook.clampWhere(m.runColourWhere) == RunLook.WHERE_SOFT ? SetupText.COLOURS_TINT
            : RunLook.WHERE_NAMES[RunLook.clampWhere(m.runColourWhere)];
        sumRow(parent, SetupText.SUM_RUN, colours
            + (m.hapticTicks ? " · " + SetupText.SUM_TICKS : ""), false);
    }

    private void sumRow(LinearLayout parent, String label, String value, boolean first) {
        if (!first) {
            View rule = new View(a);
            rule.setBackgroundColor(Ui.LINE);
            rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            parent.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) (a.getResources().getDisplayMetrics().density / 2f))));
        }
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(Look.S3), 0, dp(Look.S3));
        row.addView(text(label, 13f, Ui.DIM), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text(value, 13f, Ui.TEXT);
        Ui.medium(v);
        v.setGravity(Gravity.END);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vl.leftMargin = dp(Look.S4);
        row.addView(v, vl);
        Ui.group(row, label + ": " + value);
        parent.addView(row, full());
    }

    private final class AllowBattery implements View.OnClickListener {
        @Override public void onClick(View v) { a.openBatteryExemptionSettings(); }
    }

    private final class PickFirst implements View.OnClickListener {
        private final String first;
        PickFirst(String f) { first = f; }
        @Override public void onClick(View v) {
            w().first = first;
            show();
        }
    }

    /* ================================================================ the pieces */

    private int dp(int v) { return Ui.dp(a, v); }

    private static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams gapped(int topDp) {
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private TextView text(String s, float sp, int colour) { return kit().text(s, sp, colour); }

    private TextView quietCaps(String s) { return kit().quietCaps(s); }

    private int indexOf(String[] all, String one) {
        for (int i = 0; i < all.length; i++) if (all[i].equals(one)) return i;
        return -1;
    }

    /** A card of the setup: the raised surface with its hairline edge. */
    private LinearLayout card() {
        LinearLayout c = Ui.col(a);
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, Ui.SURF, Look.R_CARD);
        bg.setStroke(Math.max(1, dp(1)), Look.SETUP_CARD_EDGE);
        c.setBackground(bg);
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams lp = full();
        lp.bottomMargin = dp(12);
        page.addView(c, lp);
        return c;
    }

    /** "• marks our suggestion", once, on the units step. */
    private void legend() {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(suggestDot(), new LinearLayout.LayoutParams(dp(Look.SETUP_SUGGEST_DP),
            dp(Look.SETUP_SUGGEST_DP)));
        TextView t = text(SetupText.LEGEND, 11.5f, Ui.DIM);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(Look.S2);
        row.addView(t, tl);
        LinearLayout.LayoutParams rl = full();
        rl.bottomMargin = dp(Look.S4);
        page.addView(row, rl);
    }

    private View suggestDot() {
        View d = new View(a);
        d.setBackground(Ui.roundRect(a, Ui.ACCENT, Look.SETUP_SUGGEST_DP / 2));
        Ui.decorative(d);
        return d;
    }

    /**
     * A section label - tracked capitals, and after " · " a quieter tail in its own case
     * ("TRAINING DAYS · 3 a week") - with its "i" when it has one, and that "i"'s box under
     * it when open.
     */
    private void label(LinearLayout parent, String text, String key, String why) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int cut = text.indexOf(" · ");
        String head = cut < 0 ? text : text.substring(0, cut);
        TextView h = Ui.microLabel(a, null, head, Ui.DIM);
        row.addView(h);
        if (cut >= 0) {
            TextView tail = text(text.substring(cut), 11.5f, Look.SETUP_QUIET);
            row.addView(tail);
        }
        if (key != null) row.addView(infoDot(key, head), dotParams());
        parent.addView(row, parent.getChildCount() == 0 ? full() : gapped(Look.S5));
        if (key != null) help(parent, key, why);
    }

    /** A kv row's title with its "i" right after the words. */
    private View titleWithInfo(String title, String key) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 14.5f, Ui.TEXT);
        row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, 0f));
        if (key != null) row.addView(infoDot(key, title), dotParams());
        return row;
    }

    /**
     * A setting's row: its title (and "i"), a line under it, and its control at the right -
     * at least 48 dp. The control is its own target, never the whole row, so the "i" beside
     * the title keeps its own.
     */
    private void kv(LinearLayout parent, String title, String sub, String key, String why,
                    View control) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        LinearLayout col = Ui.col(a);
        col.addView(titleWithInfo(title, key));
        if (sub != null) col.addView(text(sub, 12.5f, Ui.DIM), gapped(2));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (control != null) {
            ViewGroup.LayoutParams cp = control.getLayoutParams();
            LinearLayout.LayoutParams lp = cp instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) cp
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(Look.S4);
            row.addView(control, lp);
        }
        parent.addView(row, parent.getChildCount() == 0 ? full() : gapped(Look.S3));
        if (key != null) help(parent, key, why);
    }

    /** The app's drawn switch in its own 48 dp target, saying its state. */
    private View switchFor(boolean on, String what, View.OnClickListener tap) {
        FrameLayout f = new FrameLayout(a);
        View sw = Ui.switchView(a, on);
        Ui.decorative(sw);
        f.addView(sw, new FrameLayout.LayoutParams(dp(Ui.SWITCH_W), dp(Ui.SWITCH_H), Gravity.CENTER));
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(what + ": " + (on ? "on" : "off"));
        f.setSelected(on);
        f.setOnClickListener(tap);
        f.setOnTouchListener(new Ui.Press());
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(48)));
        return f;
    }

    /** − value + , each button 48 dp. */
    private LinearLayout stepper(String value, String minusSaid, String plusSaid,
                                 View.OnClickListener minus, View.OnClickListener plus) {
        return kit().stepper(value, minusSaid, plusSaid, minus, plus);
    }

    /** A footer or row button: lime and filled for the way forward, outlined otherwise. */
    private Button button(String label, boolean primary) { return kit().button(label, primary); }

    /**
     * A segmented choice: the chosen segment raised, and the suggested one marked with a small
     * lime dot at its top right (said as ", suggested"). Each segment is 48 dp.
     */
    private void seg(LinearLayout parent, String[] labels, int chosen, int suggested,
                     View.OnClickListener[] taps) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == chosen;
            FrameLayout f = new FrameLayout(a);
            if (on) {
                android.graphics.drawable.GradientDrawable g = Ui.roundRect(a, Ui.SURF, Look.R_PILL);
                g.setStroke(Math.max(1, dp(1)), Look.SETUP_SEG_EDGE);
                f.setBackground(g);
            }
            TextView t = text(labels[i], 13.5f, on ? Ui.TEXT : Ui.DIM);
            Ui.medium(t);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            f.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            if (i == suggested) {
                FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(
                    dp(Look.SETUP_SUGGEST_DP), dp(Look.SETUP_SUGGEST_DP), Gravity.TOP | Gravity.END);
                dl.topMargin = dp(5);
                dl.rightMargin = dp(6);
                f.addView(suggestDot(), dl);
            }
            f.setClickable(true);
            f.setFocusable(true);
            f.setSelected(on);
            f.setContentDescription(A11y.state(labels[i], on)
                + (i == suggested ? SetupText.SUGGESTED_SAID : ""));
            if (taps != null && i < taps.length) f.setOnClickListener(taps[i]);
            f.setOnTouchListener(new Ui.Press());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            if (i > 0) lp.leftMargin = dp(3);
            row.addView(f, lp);
        }
        parent.addView(row, parent.getChildCount() == 0 ? full() : gapped(Look.S3));
    }

    /** A choice drawn as a card with a radio ring - the privacy and first-session choices. */
    private void option(LinearLayout parent, String title, String sub, boolean on,
                        boolean suggested, View side, View.OnClickListener tap) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, Ui.SURFHI, Look.R_CARD);
        bg.setStroke(dp(2), on ? Ui.ACCENT : android.graphics.Color.TRANSPARENT);
        row.setBackground(bg);
        FrameLayout ring = new FrameLayout(a);
        android.graphics.drawable.GradientDrawable rg = new android.graphics.drawable.GradientDrawable();
        rg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        rg.setStroke(dp(2), on ? Ui.ACCENT : Ui.DIM);
        ring.setBackground(rg);
        if (on) {
            View dot = new View(a);
            android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
            dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dg.setColor(Ui.ACCENT);
            dot.setBackground(dg);
            ring.addView(dot, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(20), dp(20));
        rl.rightMargin = dp(12);
        row.addView(ring, rl);
        LinearLayout col = Ui.col(a);
        LinearLayout tr = new LinearLayout(a);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 14.5f, Ui.TEXT);
        Ui.medium(t);
        tr.addView(t);
        if (suggested) {
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                dp(Look.SETUP_SUGGEST_DP), dp(Look.SETUP_SUGGEST_DP));
            dl.leftMargin = dp(Look.S2);
            tr.addView(suggestDot(), dl);
        }
        col.addView(tr);
        if (sub != null) col.addView(text(sub, 12.5f, Ui.DIM), gapped(1));
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (side != null) row.addView(side);
        Ui.group(row, A11y.state(title, on) + (suggested ? SetupText.SUGGESTED_SAID : "")
            + (sub != null ? ". " + sub : ""));
        row.setSelected(on);
        row.setClickable(true);
        row.setOnClickListener(tap);
        row.setOnTouchListener(new Ui.Press());
        parent.addView(row, parent.getChildCount() == 0 ? full() : gapped(Look.S2));
    }

    /**
     * THE "i": an 18 dp soft dot beside the words it explains, blue when its box is open. Its
     * touch area is 48 dp all the same - InfoHits hands the page's touches within 48 dp of it
     * to it - so the dot can sit tight against the label without being a small target.
     */
    private View infoDot(String key, String about) {
        boolean open = w().open.contains(key);
        TextView d = text("i", 10.5f, open ? Ui.BG : Look.SETUP_INFO_INK);
        d.setTypeface(Typeface.DEFAULT_BOLD);
        d.setGravity(Gravity.CENTER);
        d.setPadding(0, 0, 0, 0);
        d.setIncludeFontPadding(false);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(open ? Look.INFO_BLUE : Look.SETUP_INFO_DOT);
        d.setBackground(g);
        d.setClickable(true);
        d.setFocusable(true);
        d.setContentDescription(SetupText.ABOUT_SAID + A11y.collapse(about));
        d.setSelected(open);
        d.setOnClickListener(new ToggleInfo(key));
        infoDots.add(d);
        return d;
    }

    private LinearLayout.LayoutParams dotParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(Look.SETUP_INFO_DP),
            dp(Look.SETUP_INFO_DP));
        lp.leftMargin = dp(7);
        return lp;
    }

    private final class ToggleInfo implements View.OnClickListener {
        private final String key;
        ToggleInfo(String k) { key = k; }
        @Override public void onClick(View v) {
            HashSet<String> open = w().open;
            if (!open.remove(key)) open.add(key);
            show();
        }
    }

    /** The box an "i" opens, straight under its label: blue-tinted, the setting's why. */
    private void help(LinearLayout parent, String key, String why) {
        if (why == null || !w().open.contains(key)) return;
        TextView t = text(why, 13f, Look.SETUP_HELP_INK);
        t.setLineSpacing(0f, 1.3f);
        t.setBackground(Ui.roundRect(a, Look.SETUP_HELP_FILL, Look.R_CTRL));
        t.setPadding(dp(11), dp(9), dp(11), dp(9));
        parent.addView(t, gapped(Look.S2));
    }

    /**
     * The response under a control: green-tinted, or amber when it warns. Said aloud when it
     * is new (show()), because it is the app answering a tap the person just made.
     */
    private void say(LinearLayout parent, String s, boolean warn) {
        if (s == null || s.length() == 0) return;
        TextView t = text(s, 13f, warn ? Look.SETUP_WARN_INK : Look.SETUP_SAY_INK);
        t.setLineSpacing(0f, 1.3f);
        android.graphics.drawable.GradientDrawable g = Ui.roundRect(a,
            warn ? Look.SETUP_WARN_FILL : Look.SETUP_SAY_FILL, Look.R_CTRL);
        g.setStroke(Math.max(1, dp(1)), warn ? Look.SETUP_WARN_EDGE : Look.SETUP_SAY_EDGE);
        t.setBackground(g);
        t.setPadding(dp(11), dp(9), dp(11), dp(9));
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        parent.addView(t, gapped(Look.S3));
        said.add(s);
    }

    /**
     * THE "i"s' 48 dp TOUCH AREAS. Android gives a view one TouchDelegate, so the page holds
     * this one for all of them: each layout pass works out every dot's 48 dp square in the
     * page's coordinates, and a touch that no control took and that lands in one goes to that
     * dot. A touch on a control beside a dot stays that control's.
     */
    private static final class InfoHits extends TouchDelegate implements View.OnLayoutChangeListener {
        private final ViewGroup host;
        private final List<View> dots;
        private final List<TouchDelegate> areas = new ArrayList<TouchDelegate>();
        private TouchDelegate active;

        InfoHits(ViewGroup host, List<View> dots) {
            super(new Rect(), host);
            this.host = host;
            this.dots = new ArrayList<View>(dots);
        }

        @Override public void onLayoutChange(View v, int l, int t, int r0, int b, int ol,
                                             int ot, int or, int ob) {
            areas.clear();
            float d = host.getResources().getDisplayMetrics().density;
            int want = (int) (48 * d + 0.5f);
            for (int i = 0; i < dots.size(); i++) {
                View dot = dots.get(i);
                if (dot.getParent() == null || dot.getWidth() <= 0) continue;
                Rect r = new Rect();
                dot.getDrawingRect(r);
                host.offsetDescendantRectToMyCoords(dot, r);
                int growV = Math.max(0, (want - r.height()) / 2 + 1);
                int growH = Math.max(0, (want - r.width()) / 2 + 1);
                r.top -= growV; r.bottom += growV; r.left -= growH; r.right += growH;
                areas.add(new TouchDelegate(r, dot));
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                active = null;
                for (int i = 0; i < areas.size(); i++)
                    if (areas.get(i).onTouchEvent(e)) { active = areas.get(i); return true; }
                return false;
            }
            if (active == null) return false;
            boolean took = active.onTouchEvent(e);
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) active = null;
            return took;
        }
    }

    /** The welcome card's warning triangle. */
    private static final class WarnGlyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();

        WarnGlyph(android.content.Context c) {
            super(c);
            p.setColor(Look.SETUP_STOP_HEAD);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            float u = getWidth() / 20f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.8f * u);
            tri.reset();
            tri.moveTo(10 * u, 2 * u);
            tri.lineTo(19 * u, 18 * u);
            tri.lineTo(1 * u, 18 * u);
            tri.close();
            c.drawPath(tri, p);
            c.drawLine(10 * u, 8 * u, 10 * u, 12 * u, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(10 * u, 14.6f * u, 1.1f * u, p);
        }
    }
}
