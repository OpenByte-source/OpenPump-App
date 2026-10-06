package org.openpump;

import android.app.KeyguardManager;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** THE SETTINGS SCREEN and every section it renders.
 *  Lifted out of SessionActivity verbatim - see
 *  docs/superpowers/plans/2026-09-07-sessionactivity-split.md. A screen class
 *  renders into the Activity's own body column and owns no state. */
final class SettingsScreen {
    private final SessionActivity a;

    SettingsScreen(SessionActivity a) { this.a = a; }

    /** Every word this view and everything under it says, lower-cased - the text a query is
     *  matched against. Content descriptions are included deliberately: a switch's state
     *  ("on", "off") and a stepper's spoken name live there and nowhere else, so a search
     *  for "reminder" should find the row whose only visible text is a glyph. */
    private static void harvestText(View v, StringBuilder into) {
        if (v == null) return;
        CharSequence cd = v.getContentDescription();
        if (cd != null) into.append(cd).append(' ');
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) into.append(t).append(' ');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) harvestText(g.getChildAt(i), into);
        }
    }

    /**
     * THE SETTINGS SEARCH.
     *
     * It used to show or hide whole CATEGORIES. Typing "seal" left you looking at the entire
     * "How a run behaves" category with the seal check somewhere inside it - a search that
     * narrows eight categories to one, and then stops helping exactly where the hunting
     * starts. It now hides the cards that do not match, so what is left on screen is the
     * answer rather than the neighbourhood the answer lives in.
     *
     * EVERY WORD MUST MATCH, in any order. "lock app" and "app lock" now find the same
     * thing; before, only the second did, because the query was one substring. Words rather
     * than a phrase is the right model for somebody typing what they remember of a setting.
     *
     * A CATEGORY NAMED OUTRIGHT KEEPS ALL OF ITSELF. Typing "developer" is asking for the
     * category, not for the one row inside it whose text happens to repeat the word - so a
     * header hit shows everything it heads.
     *
     * AND IT RESTORES THE FOLD WHEN IT IS DONE. Clearing the search used to set every child
     * of every category visible and stop there, which quietly undid N1's one-category-open
     * rule: you came back from a search with all eight expanded, and nothing said why. That
     * was the bug behind "Settings acted weird after I opened Device and developer".
     */
    private void applySettingsFilter(String raw) {
        a.settingsQuery = raw == null ? "" : raw.trim();
        if (a.settingsAnchors.isEmpty()) return;
        String q = a.settingsQuery.toLowerCase(Locale.US);
        boolean searching = q.length() > 0;
        String[] terms = Find.terms(a.settingsQuery);

        // The strip is a table of contents, and a table of contents for a filtered screen
        // would offer to jump to categories that are not on it.
        if (a.settingsJump != null)
            a.settingsJump.setVisibility(searching ? View.GONE : View.VISIBLE);

        if (!searching) {
            for (int i = 0; i < a.body.getChildCount(); i++) {
                View child = a.body.getChildAt(i);
                if (child != null) child.setVisibility(View.VISIBLE);
            }
            // ...and hand the screen back to the fold, rather than leaving it wide open.
            applySettingsCollapse();
            if (a.settingsSearchSay != null) a.settingsSearchSay.setVisibility(View.GONE);
            return;
        }

        int found = 0, cats = 0;
        for (int own = 0; own < a.settingsAnchors.size(); own++) {
            View header = a.settingsAnchors.get(own);
            int start = a.body.indexOfChild(header);
            if (start < 0) continue;
            int end = own + 1 < a.settingsAnchors.size()
                    ? a.body.indexOfChild(a.settingsAnchors.get(own + 1)) : a.body.getChildCount();
            if (end < start) end = a.body.getChildCount();

            boolean wholeCategory = Find.matchesAll(textOf(header), terms);
            int hitsHere = 0;
            for (int i = start + 1; i < end; i++) {
                View child = a.body.getChildAt(i);
                if (child == null) continue;
                boolean hit = wholeCategory || Find.matchesAll(textOf(child), terms);
                child.setVisibility(hit ? View.VISIBLE : View.GONE);
                if (hit) hitsHere++;
            }
            // A header with nothing under it is a promise the screen cannot keep.
            header.setVisibility(hitsHere > 0 ? View.VISIBLE : View.GONE);
            if (hitsHere > 0) { cats++; found += hitsHere; }
        }

        if (a.settingsSearchSay == null) return;
        a.settingsSearchSay.setVisibility(View.VISIBLE);
        a.settingsSearchSay.setText(found == 0
            ? "Nothing in Settings mentions " + (char) 0x201c + a.settingsQuery + (char) 0x201d
            : found + (found == 1 ? " setting" : " settings") + " in "
              + cats + (cats == 1 ? " category" : " categories"));
        // NO AMBER FOR AN EMPTY SEARCH. "Nothing in Settings mentions X" is a report
        // about a string filter, not about a pump - and amber used as a warning tint is
        // exactly the meaning Look retired when it narrowed the colour to one thing.
        a.settingsSearchSay.setTextColor(Ui.DIM);
    }

    /** Everything a view and its children say, lower-cased once. */
    private static String textOf(View v) {
        StringBuilder sb = new StringBuilder();
        harvestText(v, sb);
        return sb.toString().toLowerCase(Locale.US);
    }

    private final class SettingsSearchWatch implements android.text.TextWatcher {
        @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
        @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
        @Override public void afterTextChanged(android.text.Editable e) {
            String q = e == null ? "" : e.toString();
            // THE ✕ EXISTS ONLY WHILE THERE IS SOMETHING TO CLEAR. Shown on an empty
            // field it offered to undo nothing and read as a second, unexplained button.
            if (searchClear != null)
                searchClear.setVisibility(q.length() > 0 ? View.VISIBLE : View.GONE);
            applySettingsFilter(q);
        }
    }

    /** The search field's ✕ - kept so typing can show and hide it without a rebuild. */
    private View searchClear;

    private final class ClearSettingsSearchTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.settingsSearch != null) a.settingsSearch.setText("");
            applySettingsFilter("");
        }
    }

    private void settingsCategory(String title) {
        int at = a.body.getChildCount();
        // POLISH #4: the header SAYS it opens. It used to say so with a ▸/▾ inside a glyphed,
        // coloured, uppercase label ("◷ ▸ TRAINING SCHEDULE"), which read like log output;
        // it is now plain words and a chevron at the right (Ui#foldHeader) - right and DIM
        // when closed, down and lime when open. Anchors keep the bare title.
        boolean open = title.equals(a.settingsOpenCategory);
        Ui.foldHeader(a, a.body, title, open);
        View v = a.body.getChildAt(at);
        if (v != null) {
            a.settingsAnchors.add(v); a.settingsAnchorNames.add(title);
            // The header is the control. A whole row is a bigger target than any chevron
            // could be, and it is already the thing somebody reaches for. Ui#foldHeader
            // gives it the 48dp floor and says "expanded"/"collapsed" to a reader.
            v.setOnClickListener(new ToggleSettingsCategory(title));
        }
        a.settingsCatNames.add(title);
        a.settingsCatStarts.add(Integer.valueOf(a.body.getChildCount()));
    }

    private final class ToggleSettingsCategory implements View.OnClickListener {
        private final String title;
        ToggleSettingsCategory(String t) { title = t; }
        @Override public void onClick(View v) {
            a.settingsOpenCategory = title.equals(a.settingsOpenCategory) ? null : title;
            showSettings();
        }
    }

    /**
     * Folds every category except the open one. Run AFTER the whole screen is built, for the
     * same reason applySettingsFilter is: it works over the finished column, and a category's
     * span is everything between its own header and the next one.
     *
     * A no-op while a search is live - applySettingsFilter owns visibility then, and two
     * things deciding what is on screen is how a screen ends up showing neither.
     */
    private void applySettingsCollapse() {
        if (a.settingsQuery.length() > 0) return;
        if (a.settingsAnchors.isEmpty()) return;
        for (int i = 0; i < a.settingsAnchors.size(); i++) {
            int start = a.body.indexOfChild(a.settingsAnchors.get(i));
            if (start < 0) continue;
            int end = i + 1 < a.settingsAnchors.size()
                    ? a.body.indexOfChild(a.settingsAnchors.get(i + 1)) : a.body.getChildCount();
            if (end < start) end = a.body.getChildCount();
            boolean open = a.settingsAnchorNames.get(i).equals(a.settingsOpenCategory);
            // The header itself always stays; only what it heads folds away.
            for (int k = start + 1; k < end; k++) {
                View child = a.body.getChildAt(k);
                if (child != null) child.setVisibility(open ? View.VISIBLE : View.GONE);
            }
        }
    }

    /** Fill the placeholder left at the top of the screen: the "JUMP TO" label, then one
     *  chip per category. The chips were Ui.row's equal-width buttons, four to a line, and
     *  the long names wrapped INSIDE them, so one line of chips was two heights. Each chip
     *  is now one line at its own width (Ui#pill), with a short name where the short name
     *  is unambiguous (Say#jumpLabel), and the LINE breaks instead (Ui.Flow). A chip still
     *  announces the category's full name. */
    private void buildSettingsJump() {
        if (a.settingsJump == null || a.settingsAnchors.isEmpty()) return;
        a.settingsJump.removeAllViews();
        View label = Ui.fieldLabel(a, a.settingsJump, "Jump to", null);
        label.setPadding(0, Ui.dp(a, Look.S5), 0, Ui.dp(a, 2));
        // 8dp between chips; the −4dp line gap is the 48dp touch box drawing a 36dp chip
        // (see Ui.Flow) - the drawn chips sit 8dp apart, line to line as along a line.
        Ui.Flow flow = new Ui.Flow(a, Ui.dp(a, Look.S3), -Ui.dp(a, 4));
        for (int i = 0; i < a.settingsAnchorNames.size(); i++) {
            String name = a.settingsAnchorNames.get(i);
            flow.addView(Ui.pill(a, Say.jumpLabel(name), "Jump to " + name,
                                 new JumpToSettingTap(i)));
        }
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.bottomMargin = Ui.dp(a, Look.S3);
        a.settingsJump.addView(flow, flp);
    }

    private final class JumpToSettingTap implements View.OnClickListener {
        private final int idx;
        JumpToSettingTap(int i) { idx = i; }
        @Override public void onClick(View v) {
            if (idx < 0 || idx >= a.settingsAnchors.size()) return;
            // POLISH #4: a jump OPENS what it jumps to. Scrolling to a collapsed header
            // that looks like every other line read as the chip doing nothing.
            String name = a.settingsAnchorNames.get(idx);
            if (!name.equals(a.settingsOpenCategory)) {
                a.settingsOpenCategory = name;
                showSettings();
            }
            if (idx >= a.settingsAnchors.size()) return;   // rebuilt above
            final View target = a.settingsAnchors.get(idx);
            if (target == null || a.bodyScroll == null) return;
            // Posted, because a tap can arrive before the freshly built column has been
            // laid out and getTop() is 0 until it has.
            a.bodyScroll.post(a.new ScrollToAnchor(target));
        }
    }

    /**
     * X1 - THE SEAL CHECK, BEHIND THE DEVELOPER GATE.
     *
     * Withdrawn from the normal app rather than deleted. Every part of it still works - the
     * commanded hold, the coast, the decay measurement and the report - and
     * model.sealBeforeRoutine still decides whether a routine runs it. What changed is that
     * this card is the only place that flag can be set, and this card is only drawn once
     * Developer options are unlocked.
     *
     * WHAT REPLACED IT, said here because a developer turning this back on should know: the
     * guided start asks the same question - is this cuff actually sealed - and answers it by
     * refusing to begin a routine until telemetry has SEEN the cuff hold the target. Running
     * both would put two gates with two verdicts in front of every run, which is the reason
     * beginRunFlow already lets the guided start take precedence.
     */
    private void sealCheckCard() {
        LinearLayout gSeal = Ui.cardGroup(a, a.body, "Seal check", null, Ui.DIM);
        Ui.noteInfo(a, gSeal,
            "Withdrawn from the app — the guided start does this job instead.",
            "Why the seal check moved",
            "Withdrawn from the app. It is not offered before a routine or a "
            + "manual run any more, and the guided start does this job instead \u2014 it will not "
            + "let a routine begin until the cuff has actually held its target.");
        Ui.kvRow(a, gSeal, "Run it before every routine", a.model.sealBeforeRoutine,
                 new ToggleSealBefore());
        LinearLayout sealSub = Ui.col(a);
        gSeal.addView(sealSub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int sealCap = Math.min(57, a.model.ceilKpa);
        Ui.stepperRow(a, sealSub, "Target  (" + a.rangeLabel(5, sealCap) + ")",
            Model.Fmt.p(a.model.sealCheckKpa),
            new BumpSealKpa(-1), new BumpSealKpa(+1),
            a.new TypeTap(SessionActivity.TypedField.SEAL_KPA), "seal check target");
        // THE LABEL READS THE BOUND, never a literal beside it: the two disagreed, and the
        // row was offering values the check cannot measure in (Model#SEAL_CHECK_HOLD_MIN_S).
        Ui.stepperRow(a, sealSub,
            "Hold  (" + Model.SEAL_CHECK_HOLD_MIN_S + "\u2013120 s)",
            a.model.sealCheckHoldS + " s",
            new BumpSealHold(-1), new BumpSealHold(+1),
            a.new TypeTap(SessionActivity.TypedField.SEAL_HOLD), "seal check hold");
        Ui.dependentBlock(a, sealSub, a.model.sealBeforeRoutine);
        Ui.noteInfo(a, gSeal,
            "Nothing is vented between the check and the routine.",
            "What the seal check does",
            "A short hold at the target, then the pressure is watched as it "
            + "coasts and the decay rate is reported \u2014 the app does not judge it. Nothing is "
            + "vented between the check and the routine. Turning this on puts a second gate in "
            + "front of every routine; the guided start already carries one.");
    }

    void showSettings() {
        a.body.removeAllViews();
        a.enterDest(Nav.SCR_SETTINGS);
        // A SEARCH IS A THING YOU ARE DOING RIGHT NOW. It has to survive the rebuild every
        // toggle on this screen causes — otherwise it clears itself the moment you use
        // what you just searched for — but it must NOT survive walking away and coming
        // back, or Settings reopens filtered days later with the reason forgotten, which
        // reads as a Settings screen that has lost most of its contents. enterDest already
        // knows which of the two this is: bodyArrived is true only on a genuine arrival,
        // false on a same-screen re-render. Same rule the library's provenance chips follow.
        if (a.bodyArrived) a.settingsQuery = "";
        Ui.head(a, a.body, "Settings");
        // TASK 6 — "Settings & data export" gates the WHOLE of this screen, not one
        // section of it: every category below (Schedule/Pump/Session/Measurements/Device &
        // developer, and the App lock card itself) is behind this one check, placed right
        // after the title so a locked visit still reads "Settings" before it reads "locked"
        // rather than showing nothing named at all. This is deliberately self-consistent:
        // the App lock master/scope toggles live INSIDE this same gated screen (see the
        // "App lock" category below, near "Device & developer"), so turning your own app
        // lock off — or reconfiguring it — needs the same challenge as anything else here,
        // exactly as it should for a security control.
        if (a.appLockGate(AppLock.SETTINGS, new Runnable() {
                @Override public void run() { showSettings(); }
            })) return;

        // THE DOOR TO THE HELP SCREEN, above the first category on purpose. Everything
        // below this line is a control; this is the one row that explains what the controls
        // are for, and a person looking for that is looking at the top of the screen, not
        // eleven cards down past the ceiling and the units. A card row with a chevron at
        // its right edge (Ui#navCard): the chevron says it leads somewhere else, and it
        // holds no switch or value, so it reads as a way OUT of Settings, not as a setting.
        Ui.navCard(a, a.body, "How this app works", new OpenHelpTap());
        // THE FIRST-RUN SETUP, ON REQUEST, beside the help door for the same reason: it is a
        // way through the app, not a setting. It only opens by itself on a brand-new install,
        // so without this a phone that already has data could never see it. It starts from
        // the current settings (IN_PROGRESS, so FirstRun#begin presets nothing).
        Ui.navCard(a, a.body, "Run the first-run setup", new RunSetupAgainTap());

        // O12 — the placeholder. It is filled at the END of this method, from the
        // categories that actually got rendered.
        a.settingsAnchors.clear(); a.settingsAnchorNames.clear();
        a.settingsCatNames.clear(); a.settingsCatStarts.clear();

        // THE SEARCH FIELD, above the jump strip: it answers the commoner question, and it
        // is what the strip collapses into once a query is typed. ONE 48dp field: a
        // magnifier at its leading edge says what it is before anything is typed, and the
        // ✕ lives INSIDE it, shown only while there is text to clear.
        LinearLayout searchRow = new LinearLayout(a);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(Ui.roundRect(a, Ui.SURFHI, Look.R_CTRL));
        searchRow.setPadding(Ui.dp(a, Look.S5), 0, 0, 0);
        searchRow.addView(Ui.iconView(a, R.drawable.ic_search, Ui.FAINT, 18));
        a.settingsSearch = new EditText(a);
        a.settingsSearch.setHint("Search settings");
        a.settingsSearch.setSingleLine(true);
        a.settingsSearch.setTextColor(Ui.TEXT);
        a.settingsSearch.setHintTextColor(Ui.FAINT);
        a.settingsSearch.setTextSize(Look.SP_BODY);
        a.settingsSearch.setBackground(null);
        a.settingsSearch.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        a.settingsSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        a.settingsSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        a.settingsSearch.setContentDescription("Search settings by name \u2014 categories that "
            + "do not mention what you type are hidden while you type");
        // RESTORED, not reset. Nearly every control on this screen calls showSettings()
        // when it is touched, so a query that died with the rebuild would clear itself the
        // moment you used the thing you had just searched for. The watcher is attached
        // AFTER the text is put back, or restoring it would count as the user typing.
        if (a.settingsQuery.length() > 0) a.settingsSearch.setText(a.settingsQuery);
        a.settingsSearch.addTextChangedListener(new SettingsSearchWatch());
        LinearLayout.LayoutParams seLp = new LinearLayout.LayoutParams(
                0, Ui.dp(a, 48), 1f);
        searchRow.addView(a.settingsSearch, seLp);

        android.widget.ImageButton clearSearch = new android.widget.ImageButton(a);
        clearSearch.setImageDrawable(Ui.icon(a, R.drawable.ic_close, Ui.DIM));
        clearSearch.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        clearSearch.setBackground(null);
        // 48dp target, an 18dp mark: the padding is the difference.
        clearSearch.setPadding(Ui.dp(a, 15), Ui.dp(a, 15), Ui.dp(a, 15), Ui.dp(a, 15));
        clearSearch.setContentDescription("Clear the search and show every setting");
        clearSearch.setOnClickListener(new ClearSettingsSearchTap());
        clearSearch.setOnTouchListener(new Ui.Press());
        clearSearch.setVisibility(a.settingsQuery.length() > 0 ? View.VISIBLE : View.GONE);
        searchClear = clearSearch;
        searchRow.addView(clearSearch,
            new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
        a.body.addView(searchRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        a.settingsSearchSay = new TextView(a);
        a.settingsSearchSay.setTextColor(Ui.DIM);
        a.settingsSearchSay.setTextSize(Look.SP_CAPTION);
        // Room under it before the first heading's rule - the jump strip that normally sits
        // between them is hidden while a query is live.
        a.settingsSearchSay.setPadding(0, Ui.dp(a, Look.S3), 0, Ui.dp(a, Look.S4));
        a.settingsSearchSay.setVisibility(View.GONE);
        a.body.addView(a.settingsSearchSay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        a.settingsJump = Ui.col(a);
        a.body.addView(a.settingsJump, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ===== SCHEDULE — the top of the screen, because the streak is counted over it.
         * The categories carry no colour of their own any more (see Ui#foldHeader); the
         * cards' tones below are what Ui#cardGroup is told, and it draws an edge only for a
         * warning. */
        settingsCategory("Training schedule");

        /* EVERY CONTROL NOW LIVES INSIDE THE CARD THAT NAMES IT. Settings used to be one
         * unbroken column with uppercase labels floating in it, so a category header was a
         * claim about grouping that nothing on the screen backed up — a stepper eleven rows
         * down looked exactly as attached to the heading above it as the row beneath it did.
         * Ui.cardGroup returns the column, so every existing builder call is unchanged
         * except for the parent it is handed. */
        boolean daysBySetting = LongDays.daysSetByLongDays(a.model.sched);
        // The streak rule (and, while Long training days sets the week, how the ticks and
        // that setting meet) are behind the card title's ⓘ; the chips show the days.
        LinearLayout gDays = Ui.cardGroup(a, a.body, "Training days", null, Ui.TEXT,
            "Training days", "Your streak counts these days only. A day you do not train on "
            + "neither adds to it nor breaks it — " + a.model.sched.daysLine()
            + ". One missed training day per run is covered by a rest day; a second ends it."
            + (daysBySetting ? "\n\n" + LongDays.daysNote(a.model.sched) : ""), false);
        // ST-1: REAL DAY CHIPS - the day's name on a filled (training) or outlined (rest)
        // chip, its state said to a reader; no ●/○ glyph in the label.
        Button[] dayRow1 = Ui.row(a, gDays,
            new String[]{ Schedule.DAY_ABBR[0], Schedule.DAY_ABBR[1], Schedule.DAY_ABBR[2],
                          Schedule.DAY_ABBR[3] },
            new View.OnClickListener[]{ new ToggleDay(0), new ToggleDay(1),
                                        new ToggleDay(2), new ToggleDay(3) });
        Button[] dayRow2 = Ui.row(a, gDays,
            new String[]{ Schedule.DAY_ABBR[4], Schedule.DAY_ABBR[5], Schedule.DAY_ABBR[6] },
            new View.OnClickListener[]{ new ToggleDay(4), new ToggleDay(5), new ToggleDay(6) });
        for (int i = 0; i < 7; i++) {
            Button chip = i < 4 ? dayRow1[i] : dayRow2[i - 4];
            boolean trains = a.model.sched.days[i];
            Ui.stateFill(a, chip, trains, true);
            chip.setSelected(trains);
            chip.setContentDescription(Schedule.DAY_ABBR[i] + ", "
                + (trains ? "training day" : "rest day"));
        }
        /* t10 fix (review D row 21) - UNDER "ALTERNATE DAYS, EACH TRACK 3 DAYS A WEEK" THE
         * TICKS ARE NOT THE WEEK: it runs Monday to Saturday whatever is ticked, so a tap here
         * would change nothing the week shows. The toggles are shown unavailable, keeping the
         * ticks the other three choices use, and the card says so in the editor's words. */
        if (daysBySetting) {
            for (int i = 0; i < dayRow1.length; i++)
                Ui.stateFill(a, dayRow1[i], a.model.sched.days[i], false);
            for (int i = 0; i < dayRow2.length; i++)
                Ui.stateFill(a, dayRow2[i], a.model.sched.days[4 + i], false);
        }
        // SAID WHERE IT IS TRUE: the Trainer counts a training week over these days, so this
        // row is one of the plan's inputs, and the Trainer's "When it runs" room links here.
        // (ST-2: while Long training days sets the week, its one line is under the strip.)
        if (!daysBySetting)
            Ui.note(a, gDays, "The Trainer plans its weeks around these days.");
        weeklyPlannerRows(gDays);

        // ST-3: the card and its row no longer share one name. What a reminder sends is the
        // card's ⓘ.
        LinearLayout gTime = Ui.cardGroup(a, a.body, "Time & reminders", null, Ui.TEXT,
            "Reminder notifications", "Reminders fire on your training days only — nothing "
            + "else is ever sent.", false);
        Ui.kvRow(a, gTime, "Time", a.model.sched.hhmm(), Ui.TEXT, new PickSchedTime());
        // A SWITCH ROW, not a sentence with its state on the end. The row is the target,
        // the drawn switch is the value, and the spoken form is still "…: on" / "…: off".
        Ui.kvRow(a, gTime, "Reminder notifications", a.model.sched.remind, new ToggleRemind());
        // A2 - a question about WHICH reminders, so it lives under the one that decides
        // whether there are any, and is hidden entirely while there are not.
        if (a.model.sched.remind) {
            Ui.kvRow(a, gTime, "Also remind me about an unrun track",
                     a.model.remindOtherTrack, new ToggleOtherTrack());
            /* BOTH FACTS, NO TAP. The dropped clause said the reminder fires once and only
             * while the track is still outstanding - which is what "mentions the second one"
             * already implies, and the cutoff is the half somebody would be wrong about. */
            Ui.note(a, gTime, "On a day set to run both, this mentions the second one "
                + "about three hours later, and never after "
                + Reminders.OTHER_TRACK_LATEST_HOUR + ":00.");
        }

        /* ===== PUMP — the device and what it is allowed to do ========================= */
        settingsCategory("Pump");

        LinearLayout gCeil = Ui.cardGroup(a, a.body, "Safety ceiling", null, Ui.ACCENT);
        // (The line "Every routine the Trainer writes is capped here." is gone: the line
        // under the stepper says the same, more exactly.)
        /* OPTION C - WHILE A RUN IS GOING THIS IS A READING, NOT A CONTROL.
         *
         * The stepper used to be drawn live and refuse the press. That is the worst of both:
         * the row invites a tap it has already decided to reject, and the rule is only
         * learnable by being caught by it. A ceiling that cannot move is a fact about the
         * app right now, and a fact is a value with a label - so it is drawn as one, with
         * the suffix saying when it becomes a control again. */
        if (a.running)
            Ui.kvRow(a, gCeil, "Never exceed" + a.AFTER_RUN,
                     Model.Fmt.p(a.model.ceilKpa), Ui.FAINT, null);
        else
            Ui.stepperRow(a, gCeil, "Never exceed  (" + a.rangeLabel(7, 57) + ")",
                Model.Fmt.p(a.model.ceilKpa), new BumpCeil(-1), new BumpCeil(+1),
                a.new TypeTap(SessionActivity.TypedField.CEIL), "safety ceiling");
        // What happens to the person's SET, in their words. This used to say how the code
        // sees it ("enforced app-side before every write"): true, and no help to the person
        // holding the phone, who wants to know what becomes of the set they built.
        Ui.note(a, gCeil, "No set goes past this. One that would is lowered to the ceiling, "
            + "and its editor tells you.");

        // ST-14: the switch the other two units already use; the definition is the title's ⓘ.
        LinearLayout gUnit = Ui.cardGroup(a, a.body, "Pressure unit", null, Ui.ACCENT,
            "Pressure unit", "Vacuum reads negative in inHg and cmHg, positive in kPa. cmHg is "
            + "the same mercury column as inHg measured in centimetres, so it is 2.54× the "
            + "number and steps 2.54× finer. The pump always speaks kPa on the wire — this "
            + "only changes what you see. Sets, the run screen and history all switch "
            + "together.", false);
        Ui.segmented(a, gUnit, new String[]{ "inHg", "cmHg", "kPa" },
            new String[]{ "inches of mercury", "centimetres of mercury", "kilopascals" },
            "kPa".equals(a.model.unit) ? 2 : "cmHg".equals(a.model.unit) ? 1 : 0,
            new View.OnClickListener[]{ a.new SetUnit("inHg"), a.new SetUnit("cmHg"),
                                        a.new SetUnit("kPa") });

        // A SEPARATE SETTING, and separate on purpose: reading vacuum in inHg while
        // measuring yourself in centimetres is an ordinary combination, and one control
        // driving both would make it impossible to ask for.
        // (Measurement polish, item 20: the segmented control, where this was ●/○ buttons.)
        LinearLayout gSize = Ui.cardGroup(a, a.body, "Measurement unit", null, Look.BODY,
            "Measurement unit", "Length and girth are stored in centimetres whatever this says — "
            + "your readings do not move, only how they are printed. Inches are shown to two "
            + "decimals because a tenth of a centimetre is 0.04 in, and a unit that could not "
            + "print the difference between two readings would make a real change look like "
            + "none. Every export keeps its raw centimetre columns and adds one in the unit "
            + "you have chosen.", false);
        Ui.segmented(a, gSize, new String[]{ "cm", "inches" },
            new String[]{ "centimetres", "inches" }, "in".equals(a.model.sizeUnit) ? 1 : 0,
            new View.OnClickListener[]{ new SetSizeUnit("cm"), new SetSizeUnit("in") });

        /* THE LOAD UNIT (owner, 2026-09-30) - the third unit, beside pressure and size. Every
         * traction load on every screen is drawn in it (Model.Fmt#load); the plan, its caps and
         * the pump's own figures stay in pounds underneath. */
        // NEW-20: the step sizes said in plain words, behind the title's ⓘ - and no "works in
        // pounds underneath" on the face.
        LinearLayout gLoad = Ui.cardGroup(a, a.body, "Load unit", null, Ui.ACCENT,
            "Load unit", "How a traction load is shown: the length load, its caps and steps, "
            + "and the load in a routine's name. Loads show in kg; the plan’s steps are "
            + "0.5 lb (about 0.25 kg). What the pump is told does not change.", false);
        Ui.segmented(a, gLoad, new String[]{ "lb", "kg" },
            new String[]{ "pounds", "kilograms" },
            Model.Fmt.L_KG.equals(a.model.loadUnit) ? 1 : 0,
            new View.OnClickListener[]{ new SetLoadUnit(Model.Fmt.L_LB),
                                        new SetLoadUnit(Model.Fmt.L_KG) });

        // THE CONNECTION CARD IS GONE. It held exactly one control — a "Connection" row
        // that opened the guided connect screen — and that screen is now one tap away from
        // EVERY screen in the app: the header's pump chip opens it when there is no link,
        // and its sheet releases the link when there is. A settings row that duplicates a
        // persistent header control is a second door to one room, and the one people found
        // first was never this one. No setting lived here; nothing was moved.

        /* "REMEMBERED PUMPS" (T9) — every pump this app has been told to name/remember,
         * one row each: the name on the left, when it last actually connected on the
         * right, tap for Rename/Forget. NOT the connection card removed above — this is
         * not a door to the connect screen, it is the list the scan's own RSSI comparison
         * reads, and a pump can be managed here whether or not one is connected right now. */
        // How remembering works is the title's ⓘ; the card shows the pumps, or "None yet."
        LinearLayout gKnown = Ui.cardGroup(a, a.body, "Remembered pumps", null, Ui.ACCENT,
            "Remembered pumps", "The next pump this app finds will offer to be remembered, by "
            + "name, so it is picked automatically next time.\n\nOn every scan, the strongest "
            + "signal among your remembered pumps is connected automatically — the same RSSI "
            + "comparison whichever way you reach the connect screen from. A pump this app has "
            + "never seen before offers once to be remembered under a name, or you can connect "
            + "to it just that one time without remembering — it will offer again next time. "
            + "Tap a remembered pump to rename or forget it.", false);
        if (a.model.knownPumps.isEmpty()) {
            Ui.note(a, gKnown, "None yet.");
        } else {
            for (int i = 0; i < a.model.knownPumps.size(); i++) {
                Model.KnownPump p = a.model.knownPumps.get(i);
                Ui.kvRow(a, gKnown, PumpMatch.displayName(p.name),
                    a.lastConnectedLabel(p.lastConnected), Ui.DIM,
                    new PumpRowTap(p.address));
            }
        }

        /* ===== SESSION — what happens inside a run ==================================== */
        settingsCategory(a.SESSION_CATEGORY);

        /* THE CYLINDER RACK STAYS IN SESSION (measurement polish, item 9). It used to sit
         * inside the Standardisation hold card, which moved to Measurements; the rack is about
         * the pump's hardware, and "Change cylinder" lands on this category
         * (SessionActivity#openCylinderRack), so it keeps a card of its own here. */
        LinearLayout gCyl = Ui.cardGroup(a, a.body, "Cylinder", null, Ui.BODY);
        a.cylinderRows(gCyl);

        LinearLayout gRun = Ui.cardGroup(a, a.body, "On the run screen", null, Ui.ACCENT);
        Ui.kvRow(a, gRun, "Haptic ticks", a.model.hapticTicks, new ToggleHaptic());
        Ui.noteInfo(a, gRun, "A tick on a step's last three seconds, a buzz when it changes.",
            "Haptic ticks", "A short tick on each of the last three seconds of a step, a "
            + "longer buzz when the step changes, and one when the run ends. They count "
            + "time only — nothing buzzes off the live pressure reading.");
        /* RUN COLOURS (0.10 run-screen redesign): the run screen's status line takes the
         * colour of the step playing - warm-up, work, rest and the rest - and, if asked, the
         * top of the screen a soft or strong tint of it. STOP is never one of them. */
        Ui.kvRow(a, gRun, "Run colours", !a.model.runColourOn ? "Off"
                : RunLook.WHERE_NAMES[RunLook.clampWhere(a.model.runColourWhere)],
            Ui.TEXT, new OpenRunColours());
        Ui.noteInfo(a, gRun, "The run screen coloured by the step playing.",
            "Run colours", "The status line on the run screen takes the colour of the kind of "
            + "step playing: warm-up, work, the drop inside a set, rest, the fatigue block, "
            + "traction, and Hold. You can let the colour tint the top of the screen too, and "
            + "pick each colour or one of four sets (Default, Colour-blind safe, High contrast, "
            + "Calm).\n\nThe words always say what the colour says, and a Hold always shows, "
            + "coloured or not. STOP stays red and can't be changed; a colour too close to it, "
            + "or to another step's colour, is refused. Nothing about what is sent to the pump "
            + "reads this.");
        /* WHERE THE − / + CONTROLS SIT (0.10 final): the strip that changes the step playing,
         * pinned above the run screen's buttons (the default) or in the page under the chart. */
        Ui.kvRow(a, gRun, QuickAdjust.WHERE_TITLE,
            QuickAdjust.WHERE_NAMES[Model.clampRunStripWhere(a.model.runStripWhere)],
            Ui.TEXT, new OpenRunStripWhere());
        Ui.noteInfo(a, gRun, "Pinned above the buttons, under the chart, or chart first on ramps.",
            QuickAdjust.WHERE_TITLE, "The run screen's − / + controls change the step that is "
            + "playing: its pull, hold, drop and drop time in a set, or how long a rest, a ramp "
            + "step or the warm-up runs. Pinned above the buttons (the default) they are always "
            + "in view and the chart is a little shorter; under the chart the chart keeps its "
            + "full height and the controls scroll with the page. Chart first on ramps puts the "
            + "chart at the top of the page with the controls right under it while a ramp step "
            + "or the warm-up plays, and keeps them pinned on every other step. Whichever you "
            + "pick they do the same thing. Nothing about what is sent to the pump reads this.");
        Ui.kvRow(a, gRun, "Vibrate before the pull", a.model.pullBuzz, new TogglePullBuzz());
        Ui.noteInfo(a, gRun, "A short buzz ten seconds before a rest ends in a pull. Off by default.",
            "Vibrate before the pull", "In the last ten seconds of a rest, the status line "
            + "pulses and says \"PULL IN 0:10\". With this on, the phone also gives one short "
            + "buzz as those ten seconds begin, so a rest can be spent looking away. It is a cue "
            + "only: nothing about what is sent to the pump reads it.");
        Ui.kvRow(a, gRun, "Tone cues", a.model.toneCues, new ToggleTone());
        Ui.noteInfo(a, gRun, "A generated tone on the same three moments, off by default.",
            "Tone cues", "A short generated tone at a step's last three seconds, when it "
            + "changes, and when the run ends — the same three moments the haptic ticks mark, "
            + "played through the media volume so it follows that slider rather than the "
            + "ringer's. Off by default, and even while it is on, a cue still checks the "
            + "ringer and Do Not Disturb each time and stays silent if either is asking for "
            + "quiet. Nothing about what is sent to the pump reads this flag.");
        Ui.kvRow(a, gRun, "Sound on a repetition", RepCue.label(a.model.repCueAt),
                 Ui.TEXT, new PickRepCue());
        Ui.noteInfo(a, gRun,
            a.model.repCueAt == RepCue.OFF
                ? "Off. A tone when the routine reaches a repetition you name."
                : "A tone when the routine reaches " + (a.model.repCueAt == RepCue.LAST
                    ? "its last repetition." : "repetition " + a.model.repCueAt + "."),
            "Sound on a repetition",
            "A tone when the run reaches a repetition you choose — the last one, or a "
            + "number. It counts the routine's own cycles as the routine was written when "
            + "the run began: every part of the plan that commands a pull counts, including "
            + "the warm-up and a retention hold, and rests count as none.\n\n"
            + "It sounds once per run, never during a rest or a hold, and follows the media "
            + "volume, staying quiet whenever the ringer or Do Not Disturb asks for quiet.");
        // ST-7: the sound's ⓘ was about 350 words; it is its first paragraph and one line.
        /* (0.10 final) The "Coaching line" row is gone with the run screen's upcoming list -
         * its one sentence lived under that list's NEXT row. The NOW card's timer says what
         * comes next now. Model#coachLine is kept, unread, so a saved choice round-trips. */
        Ui.kvRow(a, gRun, "Keep the screen on during a run", a.model.keepScreenOn,
            new ToggleKeepAwake());
        Ui.noteInfo(a, gRun, "The run screen holds the display awake.",
            "Keep the screen on", "The run screen holds the display awake so the countdown, the "
            + "pressure trace and STOP stay in front of you. Turning it off saves battery; "
            + "the run itself is unaffected either way — it keeps going in the background "
            + "with its notification.");
        // "Discreet notifications" lives in Settings › Privacy since 0.10 (incognito).
        /* RELEASE REVIEW - WHERE THE NOTIFICATION IS ASKED FOR AGAIN. The first START or hold
         * asks once (NotifyAsk); after a "no" this is the person's own way back. Drawn from the
         * permission itself, so it never claims a notification the system will not show.
         * Android 13+ only: below that there is nothing to grant. */
        if (android.os.Build.VERSION.SDK_INT >= NotifyAsk.FIRST_SDK) {
            Ui.kvRow(a, gRun, "Notifications for runs and holds", a.notificationsAllowed(),
                new AskRunNotifications());
            Ui.noteInfo(a, gRun, a.notificationsAllowed()
                    ? "The countdown, a Release button, and a warning if a vent can't be confirmed."
                    : "Off: a run or hold shows nothing outside the app.",
                "Notifications for runs and holds", NotifyAsk.REASON + " They appear in the "
                + "status bar and on the lock screen while a run or hold is going. Tap the row "
                + "to turn them on, or to open the app's notification settings.");
        }
        sealCheckSettings();
        offerToSaveSettings();

        // M4: the test's own name, and that a NEW routine starts with it off.
        Ui.noteInfo(a, a.body, "The " + TauSay.NAME + " is set per routine.",
            TauSay.NAME, "The " + TauSay.NAME + " is set per ROUTINE, in the routine editor "
            + "(Library › the routine) — it is a property of what you are running, not of the "
            + "app. A new routine starts with it off; turn it on there.");

        /* ===== MEASUREMENTS — the body, not the pump =================================== */
        settingsCategory("Measurements");

        /* ONE HOME FOR MEASUREMENTS (measurement polish, item 9). The standardisation hold
         * was set under Session while how often to measure was set here, so the two halves
         * of one question lived a category apart. The hold is how a measurement is TAKEN;
         * it lives with the measurements now. */
        LinearLayout gStd = Ui.cardGroup(a, a.body, "Standardisation hold",
            "The pump pulls to one pressure and holds it before you measure, so every "
            + "reading is taken the same way.", Ui.BODY);
        Ui.kvRow(a, gStd, "Hold before measuring", a.model.std.on, new ToggleStdOn());
        /* THE DEPENDENT BLOCK IS BUILT WHETHER OR NOT THE HOLD IS ON, and greyed when it
         * is off. It used to be dropped from the tree entirely, so turning the switch off
         * made five settings disappear — the screen shortened under the user's finger and
         * the values they had chosen were nowhere to be seen or checked. Now they stay put,
         * unavailable, and a reader still reads them (announced as disabled). */
        LinearLayout stdSub = Ui.col(a);
        gStd.addView(stdSub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int holdCap = Math.min(57, a.model.ceilKpa);
        Ui.stepperRow(a, stdSub, "Hold pressure  (" + a.rangeLabel(5, holdCap) + ")",
            Model.Fmt.p(a.model.std.kpa), new BumpStdKpa(-1), new BumpStdKpa(+1));
        Ui.stepperRow(a, stdSub, "Hold for  (10–60 s)", a.model.std.sec + " s",
            new BumpStdSec(-1), new BumpStdSec(+1));
        /* A SWITCH THAT DOES WHAT IT SAYS. "Photo during the hold" was saved and read
         * nowhere, so it did nothing either way. It now opens the camera on the capture
         * screen as a served hold hands over to it (SessionActivity#photoDuringHold), while
         * the pump is still holding - before any number is typed. OFF unless the person
         * turns it on; an existing install is switched off once on the update
         * (Model#stdPhotoOffDone), and the switch saves at once so an "on" is kept. */
        Ui.kvRow(a, stdSub, "Photo during the hold", a.model.std.photo, new ToggleStdPhoto());
        Ui.note(a, stdSub, "The camera opens while the pump holds, before you measure.");
        /* "VENT DOWN TO", which is what it is. It was "Release to after the photo", naming a
         * photo step that does not exist: the number is how far the pump vents before the
         * session goes on. */
        int relCap = Math.min(57, a.model.std.kpa);
        Ui.stepperRow(a, stdSub, "Vent down to  (" + a.rangeLabel(0, relCap) + ")",
            Model.Fmt.p(a.model.std.release), new BumpStdRel(-1), new BumpStdRel(+1));
        double ventS = Math.max(0, (a.model.std.kpa - a.model.std.release) / a.VENT_RATE);
        Ui.note(a, stdSub, a.model.std.release >= a.model.std.kpa
            ? "That is the hold pressure itself, so there is nothing to vent."
            : "Takes about " + Math.max(1, Math.round(ventS)) + " s.");
        Button preview = Ui.flat(a, stdSub, "Preview the hold ›");
        preview.setOnClickListener(a.new PreviewStdTap());
        if (!a.model.std.on)
            Ui.noteInfo(a, stdSub, "Unavailable while the hold is off — these are what it would run.",
            "Standardisation hold", "Unavailable while the hold is off — these are what it "
                + "would run. Turn the hold on above to change them.");
        Ui.dependentBlock(a, stdSub, a.model.std.on);
        /* P1 - THE HOLD LIMIT, and it belongs with this card because THIS is the hold that had
         * none. It bounds every hold in the app: this one, and the one a routine can end into.
         * Not inside the greyed block above, because the bound applies whenever a hold happens
         * and a limit somebody cannot see is a limit they cannot trust. */
        Ui.stepperRow(a, gStd, "Vent any hold after at most",
            Model.Fmt.t(a.model.holdMaxSec),
            new BumpHoldMax(-1), new BumpHoldMax(+1), "hold limit");
        Ui.noteSafety(a, gStd, "A hold vents itself after this whatever else is happening \u2014 "
            + "including while you are typing a measurement or in the camera. Leaving the app "
            + "vents it immediately. A hold with no ending is the one thing this must never be.");

        /* HOW OFTEN, AS THE APP'S OWN CONTROL. Five ●/○ buttons across three rows are now
         * one segmented control of four: "Every session" is Sessions with an interval of 1,
         * which the caption says (Model.Meas#cadenceSegment and #stepSessions keep the
         * stored modes exactly as they were, so the question asked at START is unchanged). */
        LinearLayout gAsk = Ui.cardGroup(a, a.body, "Ask for measurements", null, Look.BODY);
        int seg = a.model.meas.cadenceSegment();
        Ui.segmented(a, gAsk, new String[]{ "Sessions", "Hours", "Week", "Never" },
            new String[]{ "ask after a number of sessions", "ask after a number of hours",
                          "ask at the 1st and 4th session of a training week", "never ask" },
            seg, new View.OnClickListener[]{ new SetMeasSegment(Model.Meas.CAD_SESSIONS),
                new SetMeasSegment(Model.Meas.CAD_HOURS), new SetMeasSegment(Model.Meas.CAD_WEEK),
                new SetMeasSegment(Model.Meas.CAD_NEVER) });
        // The interval only exists for two of the four; the others have no value for it at
        // all, so there is nothing to grey — it is genuinely absent.
        if (seg == Model.Meas.CAD_SESSIONS) {
            int n = a.model.meas.everySessions();
            Ui.stepperRow(a, gAsk, "Every  (1–20 sessions)",
                n + (n == 1 ? " session" : " sessions"),
                new BumpMeasInterval(-1), new BumpMeasInterval(+1));
            Ui.noteInfo(a, gAsk, "1 asks every session. Measuring too often reads noise as progress.",
                "Ask for measurements", "Measuring too often reads noise as progress — every "
                + "4–6 sessions is usually where the signal clears the noise. 1 asks every "
                + "session.");
        } else if (seg == Model.Meas.CAD_HOURS) {
            Ui.stepperRow(a, gAsk, "Every  (1–50 h)", a.model.meas.hours + " h",
                new BumpMeasInterval(-1), new BumpMeasInterval(+1));
            Ui.noteInfo(a, gAsk, "Measuring too often reads noise as progress.",
                "Ask for measurements", "Measuring too often reads noise as progress — every "
                + "4–6 sessions is usually where the signal clears the noise.");
        } else if (seg == Model.Meas.CAD_WEEK) {
            // OFFERED, NEVER SWITCHED TO. The length tier's strain and fatigue figures need
            // before/after PAIRS at comparable points in a week, and this cadence produces
            // them - but a plan that needs a measurement is a reason to ask for one, not a
            // licence to change a setting somebody chose.
            Ui.note(a, gAsk, "Asks at the 1st and 4th session of each training week.");
        } else {
            Ui.note(a, gAsk, "Never asks before a session. Log a reading from Today whenever "
                + "you like.");
        }

        // THE MEASUREMENT REMINDER — the second alarm, and deliberately in THIS card rather
        // than beside the training one: it is about the cadence set above, and it is off and
        // inert whenever that cadence is.
        boolean cadenceOn = !"off".equals(a.model.meas.mode);
        Ui.kvRow(a, gAsk, "Remind me to measure", a.model.measRemind && cadenceOn,
                 new ToggleMeasRemind());
        // ST-8: "Check at" is greyed (and inert) while measurements are off, as every
        // dependent row on this screen is.
        LinearLayout checkAt = Ui.col(a);
        gAsk.addView(checkAt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.kvRow(a, checkAt, "Check at",
            String.format(Locale.US, "%02d:%02d", a.model.measRemindHour, a.model.measRemindMin),
            Ui.TEXT, cadenceOn ? new PickMeasTime() : null);
        Ui.dependentBlock(a, checkAt, cadenceOn);
        Ui.noteInfo(a, gAsk, !cadenceOn ? "Unavailable while measurements are off."
            : a.model.measRemind ? "On — you are notified only when a reading is due."
            : "Off.",
            "Reminder", !cadenceOn
            ? "Unavailable while measurements are off — there is no cadence for a reminder to "
              + "follow. Choose Sessions, Hours or Week above."
            : a.model.measRemind
              ? ("The app checks at " + String.format(Locale.US, "%02d:%02d",
                    a.model.measRemindHour, a.model.measRemindMin)
                 + " each day and notifies you only when the cadence above says a reading is "
                 + "actually due — so it is silent on the days it is not.")
              : "Off. Turn it on for one notification, at the time you choose, on the days the "
                + "cadence above says a reading is due — never on any other day.");

        /* WHAT FOLLOWS A SESSION, IN ITS OWN CARD. Retention wear and the ring timer sat in
         * "Ask for measurements", which they have nothing to do with; the note that shears
         * had moved to the Trainer went too - it only pointed somewhere else. */
        LinearLayout gAfter = Ui.cardGroup(a, a.body, "After a session", null, Look.BODY);
        // RETENTION, opt-in. In the body-measurement category rather than the session one:
        // it is a thing done to a body between sessions, and the pump is not involved.
        Ui.kvRow(a, gAfter, "Track daily retention wear", a.model.retentionOn,
                 a.new ToggleDailyRetention());
        // THE RING TIMER. Off is 0 minutes, and turning it ON seeds the level's own starting
        // figure rather than a number the app insists on - the choice is whether to run one,
        // not how long, and the stepper is right there.
        Ui.kvRow(a, gAfter, "Ring timer after a session", a.model.rxCockringMin > 0,
                 a.new ToggleCockring());
        if (a.model.rxCockringMin > 0)
            Ui.stepperRow(a, gAfter, "For  (5–" + Model.COCKRING_MAX_MIN + " min)",
                a.model.rxCockringMin + " min",
                a.new BumpCockring(-5), a.new BumpCockring(+5));

        // ST-5: "Privacy" is the category below; this card says what it does.
        LinearLayout gPriv = Ui.cardGroup(a, a.body, "Blur on screen", null, Look.BODY);
        Ui.kvRow(a, gPriv, "Blur photos and measurements",
                 a.model.privacyBlur, new TogglePrivacyBlur());
        Ui.noteInfo(a, gPriv, "Photos and the numbers beside them render blurred.",
            "Blur on screen", "Photos and the numbers beside them render blurred. One 👁 beside "
            + "the Progress title (and on the Photos header) uncovers the lot while you are "
            + "reading, and covers it again — it does NOT change this switch, and it is "
            + "forgotten when the app closes. This switch is the master. It changes how "
            + "things are DRAWN and nothing else: your readings, your photo files and every "
            + "export are untouched.");

        LinearLayout gGoal = Ui.cardGroup(a, a.body, "Goals", null, Look.BODY,
            "How the goal line is drawn",
            "The trend draws a dashed line SLOPING from your first reading "
            + "toward the goal, at the fastest rate this app is willing to describe — so the "
            + "picture answers “am I on pace?” instead of only “how far away is "
            + "it?”, and it lands on the goal exactly at the end of the horizon above. "
            + "Above the line is ahead; below it is behind. A goal is capped at "
            + Model.Fmt.len(Model.MAX_LEN_GAIN_CM_PER_YEAR) + " of length and "
            + Model.Fmt.len(Model.MAX_GIR_GAIN_CM_PER_YEAR) + " of girth PER YEAR of that "
            + "horizon — one inch and half an inch a year — and never more than "
            + Model.Fmt.len(Model.MAX_LEN_GAIN_CM_LIFETIME) + " of length or "
            + Model.Fmt.len(Model.MAX_GIR_GAIN_CM_LIFETIME) + " of girth over your first "
            + "reading in that method however long the horizon is. That is not a forecast; "
            + "it is a refusal to draw a target that would make honest progress look like "
            + "failure.");
        // THREE GOALS, ONE ROW EACH, and no pickers. The card used to ask for one number
        // and then two more questions about it — "measured as" (eight chips) and "chart
        // goal" (two more) — which is three controls to state one target, and still left
        // every other series on the trend without a line. Each row below IS its protocol,
        // so the number needs no second control to say what it means, and the chart draws
        // each goal on the series it belongs to. The horizon under them is shared: it is
        // the one "by when", and three different deadlines is not a thing anyone asked for.
        for (int gi = 0; gi < Model.GOAL_METHODS.length; gi++) {
            int gMethod = Model.GOAL_METHODS[gi];
            Double gv = a.model.goalForMethod(gMethod);
            // ST-8: plain names - the method is said in the ⓘ, not as the row's code.
            Ui.stepperRow(a, gGoal,
                goalName(gMethod) + "  (" + Model.Fmt.lenUnit() + ")",
                gv == null ? "none" : Model.Fmt.len(gv.doubleValue()),
                new BumpGoal(gMethod, -1), new BumpGoal(gMethod, +1),
                new GoalTypeTap(gMethod), A11y.fieldName(goalName(gMethod)));
        }
        // BY WHEN. Beside the value steppers, because the two are one statement: a value
        // with no horizon cannot say whether today is ahead or behind, and the horizon is
        // also what bounds how large the value may be. ONE row for all three — see above.
        // ST-8: a field label and the app's switch, where a note and ●/○ chips were.
        Ui.fieldLabel(a, gGoal, "Goal deadline", "(all three goals)");
        int hz = a.model.goalHorizonMonths;
        Ui.segmented(a, gGoal, new String[]{ "6 mo", "1 yr", "2 yr", "3 yr" },
            new String[]{ "six months", "one year", "two years", "three years" },
            hz <= 6 ? 0 : hz <= 12 ? 1 : hz <= 24 ? 2 : 3,
            new View.OnClickListener[]{ new GoalHorizonTap(6),  new GoalHorizonTap(12),
                                        new GoalHorizonTap(24), new GoalHorizonTap(36) });
        Ui.noteInfo(a, gGoal, "Tap a value to type it. Each goal is stated in its own method.",
            "Goals", "Tap a value to type it. Each goal is stated in its own "
            + "method, so the trend draws it on that method's line and nowhere else — "
            + "the length goal on the BPSSL series (stretched, pressed to the bone), the "
            + "erect girth goal on MSEG and the soft girth goal on MSSG. A goal can't be "
            + "below your latest reading in its method.");
        // S10+: "GOAL PROJECTION USES" (round6-options.html's amendment) — which reading
        // history the M1 instrument's trend badge and PACE ACTUAL compare against. See
        // Model#goalProjectionSmoothed's own doc for exactly what this redirects (and, as
        // importantly, what it does not: the projection LINE and the progress percentage
        // are both unaffected).
        Ui.fieldLabel(a, gGoal, "Projection based on", null);
        Ui.segmented(a, gGoal, new String[]{ "Smoothed", "Latest reading" },
            new String[]{ "smoothed", "latest reading" },
            a.model.goalProjectionSmoothed ? 0 : 1,
            new View.OnClickListener[]{ new GoalProjectionSourceTap(true),
                                        new GoalProjectionSourceTap(false) });
        Ui.noteInfo(a, gGoal,
            "Smoothed (default) is steadier; the latest reading reacts faster.",
            "Projection based on", "Smoothed averages the last " + Meas.SMOOTH_WINDOW
            + " readings of a method — the default, steadier because one unusually high "
            + "or low reading no longer swings the trend badge or your pace alone. Latest "
            + "reading reads the newest reading un-touched — it reacts to a fresh measurement "
            + "immediately, noise and all. Only the trend badge and your pace follow "
            + "this choice; the progress percentage above always reflects your literal "
            + "latest reading, and the dashed projection line itself never moves — it is "
            + "a plan, not a trend fit.");
        // ST-9: a destructive act looks like one - and it can be undone from the snack.
        if (a.model.anyGoalSet())
            Ui.danger(a, gGoal, "Clear goals", false).setOnClickListener(new ClearGoalsTap());

        LinearLayout gHist = Ui.cardGroup(a, a.body, "Measurement history", null, Look.BODY);
        Model.Reading lastLogged = a.model.measLog.latestPre();
        Ui.kvRow(a, gHist, "Last logged",
            lastLogged == null ? "never" : a.dayLabel(lastLogged.ts), Ui.TEXT, null);
        Ui.kvRow(a, gHist, "Next reading", a.measDueText(), Ui.TEXT, null);
        if (lastLogged != null)
            Ui.note(a, gHist, "That was " + a.agoText(lastLogged.ts) + ".");
        Button openMeasHist = Ui.flat(a, gHist, "Measurement history ›");
        openMeasHist.setOnClickListener(a.new Tap(SessionActivity.Tap.MEASHIST));

        LinearLayout gPhoto = Ui.cardGroup(a, a.body, "Photos", null, Look.BODY);
        int photos = storedPhotoCount();
        Ui.kvRow(a, gPhoto, "Stored on this phone", String.valueOf(photos), Ui.TEXT, null);
        // ST-11: one destructive style, and the "…" of a button that asks first.
        Ui.danger(a, gPhoto, "Erase photos", true).setOnClickListener(new ErasePhotosTap());
        Ui.noteInfo(a, gPhoto, photos == 0 ? "No photos stored on this phone."
                        : photos + (photos == 1 ? " photo" : " photos") + " stored on this phone.",
            "Photos", photos == 0
            ? "No photos stored on this phone."
            : photos + (photos == 1 ? " photo" : " photos") + " stored on this phone. Erasing "
              + "deletes the image files and removes them from their readings; the readings "
              + "themselves, and their measurements, are kept.");

        /* ===== PRIVACY (incognito, 0.10) — see privacyCategory. */
        settingsCategory("Privacy");
        privacyCategory();

        /* ===== APP LOCK (Task 6, S16+) — an infra/security control, not a pump or
         * body-measurement state. */
        settingsCategory("App lock");

        LinearLayout gLock = Ui.cardGroup(a, a.body, "App lock", null, Ui.DIM,
            "App lock",
            "Biometric or device-PIN gate over the areas you choose below. Each area you "
            + "turn on prompts once the first time you open it in an app session and stays "
            + "open until the app leaves the foreground. \"Whole app\" instead gates the "
            + "very first thing this app shows after a cold start, and makes the four areas "
            + "below it redundant while it is on. Pump control itself is never lockable "
            + "mid-run — a running session's STOP is always reachable, lock or no lock.");
        // ST-4: the row says what it does; "App lock" was also the name of a Privacy switch.
        Ui.kvRow(a, gLock, "Require fingerprint or PIN", a.model.appLockOn,
                 new ToggleAppLockOn());

        // WHOLE APP, in its own sub-column so it can be greyed as a unit along with the
        // four areas under it whenever the master switch above is off — the same
        // "stays put, unavailable, and a reader still reads it" rule the standardisation
        // hold's own dependent block already established for this screen.
        LinearLayout lockSub = Ui.col(a);
        gLock.addView(lockSub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.kvRow(a, lockSub, AppLock.scopeLabel(AppLock.WHOLE_APP), a.model.appLockWholeApp,
            new ToggleAppLockScope(AppLock.WHOLE_APP));
        /* WHAT IT GATES ON THE FACE; WHAT IT GREYS OUT BEHIND THE TAP. The greying is
         * visible on the screen as it happens - the four rows go dim two inches below - so
         * the paragraph explaining it is describing something the reader can already see. */
        Ui.noteInfo(a, lockSub, "Gates the cold open only — the very first screen "
            + "after launching the app.",
            "What the whole-app lock covers",
            "Gates the cold open only — the very first screen after "
            + "launching the app. Turning this on makes the four areas below redundant, so "
            + "they are greyed out (their own choices are kept, not cleared, for whenever "
            + "this goes back off).");

        // THE FOUR AREAS — a second, nested sub-column so "Whole app" ON can grey exactly
        // these four without also greying the "Whole app" row itself, which stays live so
        // it can be turned back off.
        LinearLayout areaSub = Ui.col(a);
        lockSub.addView(areaSub, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.kvRow(a, areaSub, AppLock.scopeLabel(AppLock.PHOTOS), a.model.appLockPhotos,
            new ToggleAppLockScope(AppLock.PHOTOS));
        Ui.kvRow(a, areaSub, AppLock.scopeLabel(AppLock.MEASUREMENTS),
            a.model.appLockMeasurements, new ToggleAppLockScope(AppLock.MEASUREMENTS));
        Ui.kvRow(a, areaSub, AppLock.scopeLabel(AppLock.SESSIONS), a.model.appLockSessions,
            new ToggleAppLockScope(AppLock.SESSIONS));
        Ui.kvRow(a, areaSub, AppLock.scopeLabel(AppLock.SETTINGS), a.model.appLockSettings,
            new ToggleAppLockScope(AppLock.SETTINGS));
        // Built AFTER their children, per dependentBlock's own "call it after the block's
        // children are built" doc. The two calls target two DIFFERENT containers (areaSub,
        // then lockSub which contains areaSub) — greying lockSub when the master is off
        // re-disables an already-disabled areaSub too, which is harmless (greyOut() only
        // ever disables further, never re-enables), not a double-visible effect (each call
        // paints its OWN container's background/padding, not its children's).
        Ui.dependentBlock(a, areaSub, !a.model.appLockWholeApp);
        Ui.dependentBlock(a, lockSub, a.model.appLockOn);

        /* SCREEN PRIVACY (Task 3, Stage G) - "Block screenshots & recording" - is Settings ›
         * Privacy's "Hide from recent apps" since 0.10 (incognito): the same switch
         * (Model#secureWindow), the same flag, now beside the other things that keep the app
         * out of sight. */
        // Whether this build has the diagnostic console at all: only a debug build does (see
        // the Diagnostics card below).
        boolean console = SessionActivity.hasConsole();

        /* ===== DEVICE & DEVELOPER — deliberately last ================================== */
        // TASK 16. Marked as a developer section, and one screen further from anything a
        // user reaches by accident: this button only EXPLAINS the routine, and the routine
        // itself is behind a confirmation that states what the cuff must be attached to.
        // It commands the pump unattended, which nothing else in this app does.
        settingsCategory(a.DEV_CATEGORY);

        /* O11 — WHICH BUILD THIS IS, and deliberately OUTSIDE the developer code gate
         * below. buildStamp() has always existed and has always gone only into the log, so
         * the one question a person actually asks after installing an update — "is this
         * the one with the fix?" — was answerable only by exporting a log file. It is one
         * row. It commands nothing, exports nothing and reveals nothing, so gating it
         * behind the code that guards the pump-driving tools would be gating the wrong
         * thing. */
        LinearLayout gBuild = Ui.cardGroup(a, a.body, "This build", null, Ui.DIM);
        Ui.kvRow(a, gBuild, "Version", a.buildStamp(), Ui.TEXT, null);
        Ui.note(a, gBuild, "Quote this if something looks wrong — it says exactly which "
            + "build is on this phone.");

        /* D1 - THE SIMULATED PUMP, OUTSIDE THE CODE GATE.
         *
         * It used to sit behind it, described as being there "with everything else that
         * drives or releases the link" - and it is the one thing under that header which
         * does neither. It needs no Bluetooth, no permission and no pump; it drives no
         * radio, claims no link and releases none. It was gated by association.
         *
         * WHAT THAT COST: there was no discoverable way to see this app work without owning
         * the hardware. Somebody deciding whether the app is for them had to buy a pump
         * first, and the one feature that answers the question was behind a four-digit code
         * nothing on any screen mentions. That is not a safety boundary, it is an accident
         * of where the switch was filed.
         *
         * WHAT STAYS GATED, and why the gate is still right: diagnostics (debug builds only)
         * release the live link, the hardware self-test commands the pump unattended, and
         * backup & restore replaces everything the app has stored. Those genuinely are not
         * part of using the app, and a mis-tap into any of them matters.
         *
         * THE WARNING TRAVELS WITH IT. Simulated sessions are still FILED and still count
         * toward the week, the streak and the net, which is a real consequence of turning
         * this on and is now being offered to people who have not read a word of the
         * developer documentation. The explainer says so, and Today shows a banner for as
         * long as it is on. */
        LinearLayout gSim = Ui.cardGroup(a, a.body, "Simulated pump", null,
                                         a.model.simPump ? Ui.CRIT : Ui.DIM);
        Ui.kvRow(a, gSim, "Run against a simulated pump", a.model.simPump,
                 a.new ToggleSimPump());
        Ui.noteInfo(a, gSim, a.model.simPump
            ? "ON — nothing is connected and nothing is under pressure."
            : "Off. The app talks to your pump.",
            "The simulated pump",
            "Try the whole app without a pump. The link answers its own writes instead of a "
            + "radio, and everything above it runs unchanged — the write queue, the ack "
            + "pairing, the vent watch, the stop retry. That is the point: a simulator that "
            + "bypassed those would only prove itself.\n\nIT NEEDS NO BLUETOOTH, no "
            + "permission and no pump, so it works on a phone with the radio switched "
            + "off.\n\nSESSIONS ARE STILL FILED, and they have to be — the behaviour worth "
            + "seeing is what happens AFTER a session is filed. They count toward your "
            + "week, your streak and your net exactly as real ones do. Each is marked "
            + "SIMULATED in your history, but the plan does not read that mark, so use this "
            + "on an install you are willing to have opinions written into, or export a "
            + "backup first.\n\nA banner on Today says so for as long as it is on.");

        // COLLAPSED BEHIND A CODE. Everything under this header commands the pump
        // unattended, releases the BLE link, exports a file, or replaces everything this
        // app has stored (Task 9's Backup & restore); none of it is part of using the app,
        // and all of it sat one scroll below the photo settings. It renders as ONE row
        // until a 4-digit code is entered, and the unlock lasts for the session only
        // (devUnlocked is a field, never persisted) — closing the app re-collapses it.
        if (!a.devUnlocked) {
            LinearLayout gGate = Ui.cardGroup(a, a.body, "Developer options", null, Ui.DIM,
                "Developer options",
                (console
                    ? "Diagnostics and the hardware self-test drive the pump directly; the "
                    : "The hardware self-test drives the pump directly; the ")
                + "debug-log export and Backup & restore move data in or out instead — the "
                + "log out, a full backup out or in. None of it is part of a session, so "
                + "all of it sits behind a code that stops a mis-tap from reaching any of "
                + "it.", true);
            Button open = Ui.flat(a, gGate, "Developer options ›");
            open.setContentDescription("Developer options. Asks for a 4-digit code.");
            open.setOnClickListener(new DevUnlockTap());
            /* THE GATE IS THE COMMON CASE, AND IT WAS SKIPPING THREE FINALISERS.
             *
             * The jump strip, the live search filter and the category fold are all built at
             * the END of this method, from the categories that actually rendered - and this
             * early return, taken by default on every locked session, jumped over all three.
             * The contents strip was empty, a typed query survived nothing, and a collapsed
             * category re-opened on the next toggle. Only the cascade was remembered here.
             *
             * Named, and called at both exits, so a future early return cannot skip them
             * again by being written the same way. */
            finishSettings();
            return;
        }

        sealCheckCard();

        // THE CONSOLE IS NOT IN THE DOWNLOADABLE APP (the owner's decision,
        // docs/design/driver-seam.md question 9): it keeps its own Bluetooth connection and
        // sends raw frames outside every safety check. It lives in src/debug, so a release
        // APK has neither the class nor its manifest entry - and its door is simply not
        // drawn there, rather than drawn and then failing. The hardware self-test below is
        // in every build, so the card stays; only the console's row and words go.
        LinearLayout gDev = console
            ? Ui.cardGroup(a, a.body, "Diagnostics", null, Ui.DIM,
                "Diagnostics",
                "The raw console — the live log, the wire traffic and the manual "
                + "controls. Opening it releases the link; coming back re-claims it. It is "
                + "in builds you make yourself only; the published app does not include it.")
            : Ui.cardGroup(a, a.body, "Diagnostics", null, Ui.DIM);
        // ONE DIAGNOSTIC DOOR PER THING. This group used to carry a "Diagnostics |
        // Self-test" segmented row above the hardware self-test row, and the two words
        // "self-test" then meant two different things one line apart: that button ran the
        // PURE assertion harness (SelfTest.run — Proto/Model invariants, no pump involved
        // at all), which is a developer's build check and not a hardware door. Three
        // overlapping entries where a user is looking for one. The pure harness runs on
        // every build through test.sh, which is where it belongs, so its button is gone;
        // the raw console keeps its own row, named for what it actually opens.
        if (console) {
            Button diag = Ui.flat(a, gDev, "Debug log / diagnostics ›");
            diag.setOnClickListener(a.new Tap(SessionActivity.Tap.DIAG));
        }

        // ONE hardware door. It used to be the validation routine's; it is now the merged
        // self-test's, and the validation routine is reachable from the self-test's report
        // ("Full measurement routine ›") rather than from here. Two doors to two hardware
        // routines is two chances to open the wrong one, and only one of them answers the
        // question anybody actually has: does this work.
        /* M4 - THE SESSION LOG'S FILE, stated here and only here (the owner's choice for
         * item 17). The summary printed "Log: /storage/emulated/0/Android/data/…" under
         * every session; it now says the log is in Diagnostics, and this is where it is.
         * The export itself stays the Debug log card's one button, further down. */
        java.io.File logF = new java.io.File(a.logPath);
        boolean logOk = a.logPath != null && !a.logPath.startsWith("(");
        Ui.kvRow(a, gDev, "Session log", logOk ? logF.getName() : a.logPath, Ui.TEXT, null);
        if (logOk)
            Ui.note(a, gDev, "Saved in " + logF.getParent() + ". Export it with Debug log, "
                + "further down.");
        Button hwtest = Ui.flat(a, gDev, "Hardware self-test ›");
        hwtest.setOnClickListener(a.new HwOpenTap());
        /* THE ONE FACT SOMEBODY NEEDS WITHOUT TAPPING, AND THE REST BEHIND THE TAP.
         *
         * noteSafety is documented as the single way past the rule that refuses a long
         * note, and reserved for what has to be read without tapping - the rigid vessel.
         * The other four sentences are all on the screen this row opens, where they are
         * read at the moment they matter. Kept safetyRelevant, so the glyph stays amber. */
        Ui.noteInfo(a, gDev,
            "Runs the pump against a RIGID SEALED VESSEL, never against your body.",
            "Hardware self-test",
            "Runs the pump against a RIGID SEALED VESSEL, never against your body, and "
            + "reports a ✓ or ✗ per phase with the evidence for each: every preset "
            + "acknowledged, every adjustment carried, every hold released into the right "
            + "thing. About 10 minutes; the first "
            + Validate.mmss(HwTest.preflightBoundMs(a.model.ceilKpa)) + " command nothing and "
            + "run while you attach the vessel. Vessel phases are capped at "
            + Model.Fmt.p(HwTest.shallowKpa(a.model.ceilKpa)) + ", inside the ceiling above. "
            + "The deep measurement routine is behind the report it produces.", true);

        // TASK 9 (N4) — MANUAL BACKUP / RESTORE. dist/round5-options.html #n4, decision
        // "A — MANUAL EXPORT / IMPORT": one zip via the system file picker, so the user
        // controls where it lands (Drive, USB, anywhere) and nothing leaves the device on
        // its own. Ui.DIM, matching gDev/gLog either side of it: this is infra, not one of
        // the app's four state colours, and RESTORE — the half that actually destroys
        // local data — gets its own CRIT text on its own button below, exactly the way
        // gWipe's "Delete all data…" is CRIT inside an otherwise plain card. Neither
        // button is safetyRelevant (CMD/amber): amber is reserved for the pump being under
        // pressure, and neither backing up nor restoring commands the pump at all — the
        // dialogs below say so explicitly, the same promise gWipe's own explanation makes.
        // WAVE 4 ITEM 10: with the Sets tab gone, the unused-set list keeps a door here —
        // the set browser filtered to sets no routine references. Only the user's own:
        // the app's plan-written orphans are auto-cleaned (Model#gcOrphanSets).
        int unusedSets = 0;
        for (int i = 0; i < a.model.sets.size(); i++)
            if (a.model.usedIn(a.model.sets.get(i).id) == 0) unusedSets++;
        if (unusedSets > 0) {
            LinearLayout gUnused = Ui.cardGroup(a, a.body, "Unused sets", null, Ui.DIM,
                "Unused sets",
                "Sets that are not in any routine. They are yours to keep — drafts, "
                + "experiments — and yours to delete. This opens the set browser filtered "
                + "to them.");
            Button openUnused = Ui.flat(a, gUnused, "Unused sets (" + unusedSets + ") ›");
            openUnused.setOnClickListener(new OpenUnusedSetsTap());
        }

        LinearLayout gBackup = Ui.cardGroup(a, a.body, "Backup & restore", null, Ui.DIM,
            "Backup & restore",
            "Everything this app has stored — sessions, readings, photos, "
            + "routines, sets and settings — as one zip file you choose where to save. "
            + "Restoring replaces everything currently on this phone with what is in the "
            + "zip; your current data cannot be recovered afterwards. The pump is a "
            + "separate device and is not touched by either action.");
        Button backupNow = Ui.flat(a, gBackup, "Back up now");
        backupNow.setContentDescription("Back up now. Saves a zip of everything this app "
            + "has stored to a location you choose.");
        backupNow.setOnClickListener(new BackupTap());
        Button restore = Ui.danger(a, gBackup, "Restore", true);
        restore.setContentDescription("Restore. Replaces everything on this phone with a "
            + "backup zip you choose. Asks twice.");
        restore.setOnClickListener(new RestoreTap());

        // BRINGING THE SETUP CHECKLIST BACK (audit A30). setupDismissed was a ONE-WAY latch:
        // dismissing the "Get set up" popup — by accident, or before granting the battery
        // exemption — hid it permanently, and the only route back was erasing all app data.
        // An unfinished setup step could therefore become invisible forever. Offered here
        // rather than on Today, because a checklist you have finished should not keep
        // advertising a way to summon itself.
        if (a.model.setupDismissed) {
            LinearLayout gSetup = Ui.cardGroup(a, a.body, "Setup", null, Ui.DIM,
                "Setup",
                "The \"Get set up\" checklist walks through the permissions and pairing this "
                + "app needs. You dismissed it; this brings it back. It closes itself again "
                + "once every step is done.");
            Button reopen = Ui.flat(a, gSetup, "Show the setup checklist again");
            reopen.setContentDescription("Show the setup checklist again. Brings back the "
                + "Get set up popup you dismissed.");
            reopen.setOnClickListener(new ReopenSetupTap());
        }


        // A PLAIN CARD. Erasing is a tool, not a warning about the state the app is in, so
        // its card takes no red edge; the red is on the one button that destroys, where
        // it says exactly what it is about to do.
        LinearLayout gWipe = Ui.cardGroup(a, a.body, "Erase this app's data", null,
            "Erase this app's data",
            "Everything this app has stored — sessions, readings, photos, "
            + "routines, sets and settings — removed from this phone. It asks twice and "
            + "there is no undo, so it lives behind the code with the other tools that are "
            + "not part of using the app. The PUMP is a separate device and is not touched: "
            + "its own preset table is exactly as you left it.");
        Button wipe = Ui.danger(a, gWipe, "Erase all data", true);      // ST-11: one verb
        wipe.setContentDescription("Erase all data. Erases every session, reading, photo "
            + "and setting on this phone. Asks twice.");
        wipe.setOnClickListener(new DeleteAllTap());

        LinearLayout gLog = Ui.cardGroup(a, a.body, "Debug log", null, Ui.DIM,
            "Debug log",
            "The debug log records every tap and screen change, what was sent to the pump "
            + "and what it reported, what the screen showed, and any mismatch the app found "
            + "between them. It never contains measurements, photos, names you typed, or the "
            + "pump's Bluetooth address. This export is this session's log file only, handed "
            + "over as a content:// link that grants the receiving app read access to that "
            + "one file and nothing else. Your measurements leave this phone only through "
            + "Progress › Export, which states what its files contain.");
        Ui.row(a, gLog, new String[]{ "Export debug log" },
            new View.OnClickListener[]{ a.new ExportLogTap() });

        finishSettings();
    }

    /**
     * THE FOUR THINGS THAT MUST HAPPEN AFTER EVERY SETTINGS BUILD, whichever way it ended.
     *
     * The jump strip is written from the categories that actually rendered rather than from
     * a plan of them; the filter is applied over the finished column, which is also what
     * carries a live query across the rebuild a toggle causes; the collapse runs after the
     * filter, because a live query outranks it; and the cascade is the entrance.
     */
    private void finishSettings() {
        buildSettingsJump();
        applySettingsFilter(a.settingsQuery);
        applySettingsCollapse();
        a.staggerBodyIn();
    }

    private final class ToggleAppLockOn implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!a.model.appLockOn && refuseAppLockWithoutDeviceSecurity()) return;
            a.model.appLockOn = !a.model.appLockOn;
            Store.save(a, a.model);
            showSettings();
        }
    }


    private final class ToggleAppLockScope implements View.OnClickListener {
        private final int scope;
        ToggleAppLockScope(int s) { scope = s; }
        @Override public void onClick(View v) {
            boolean was = AppLock.scopeToggle(a.model, scope);
            if (!was && refuseAppLockWithoutDeviceSecurity()) return;
            AppLock.setScopeToggle(a.model, scope, !was);
            Store.save(a, a.model);
            showSettings();
        }
    }

    /**
     * Shared refusal for turning ANY app-lock toggle (master or one of the four areas) ON
     * when this phone has no fingerprint/PIN/pattern/password to challenge against —
     * otherwise the switch would trap a person's own Photos or Measurements behind a
     * question their phone can never ask, which is worse than the feature not existing.
     * Mirrors the measurement reminder's own POST_NOTIFICATIONS discipline: "if denied the
     * toggle goes back off rather than sitting on while nothing can ever appear".
     *
     * This is the FRONT door; showCredentialFallback() keeps its own belt-and-braces
     * runtime check for the same fact, because a phone whose owner removes their screen
     * lock AFTER turning this on needs handling too, and Settings has no way to reach back
     * and un-ring that bell retroactively — see that method's own doc.
     *
     * Returns true (having shown the explanation) when the toggle should NOT change; false
     * means the caller may proceed with the flip.
     */
    /* ================================================================ PRIVACY (0.10)
     *
     * INCOGNITO - the owner's decisions of 2026-09-26, the approved mock "Settings › Privacy".
     * Every feature is its own switch ("allow me to select which incognito features I want");
     * the master turns the chosen set on and off together, and a feature can be on without
     * it. The rule is Incognito's (pure, IncognitoTest); each switch here only flips its
     * field through Incognito#setFeature / #setMaster, saves, and has the Activity apply what
     * the phone must be told (applyIncognito). Nothing here reads or writes anything the pump
     * is sent: STOP, Resume and every safety warning stay whatever is chosen.
     *
     * Two switches predate it and keep their fields: "Discreet notifications" (T17, from the
     * run card) and "Hide from recent apps" (Screen privacy's "Block screenshots &
     * recording", from the App lock category).
     */
    private void privacyCategory() {
        LinearLayout gMaster = Ui.cardGroup(a, a.body, null, null);
        privacyRow(gMaster, "Incognito mode",
            "Turns the ones you choose below on and off together. STOP, Pause/Resume and "
            + "safety warnings always stay. " + Incognito.coversLine(a.model),
            a.model.incognito, new ToggleIncognito(), true, false);

        LinearLayout g = Ui.cardGroup(a, a.body, "Incognito", null);         // ST-5
        iconRow(g);
        privacyRow(g, "Discreet notifications",
            "“Session running” instead of pressures and routine names, and "
            + "“Contents hidden” on the lock screen. STOP, Pause/Resume and safety "
            + "warnings always stay.",
            a.model.discreetNotifications, new ToggleFeature(Incognito.NOTIFICATIONS), false, true);
        // (the incognito safety review, C1) "Still urgent" is true now: the safety alerts
        // ring and vibrate on their own HIGH channel (RunService#CHANNEL_ALERT_ID).
        privacyRow(g, "Safety warnings on the lock screen",
            "“Session needs attention now” while the phone is locked, the full "
            + "warning once it is unlocked. Still urgent: they ring and vibrate.",
            a.model.neutralSafetyNotices, new ToggleFeature(Incognito.SAFETY_WORDS), false, true);
        privacyRow(g, "Hide from recent apps",
            "Blanks the app’s preview in the app switcher. Also blocks screenshots and "
            + "screen recording in the app, so it has its own switch.",
            a.model.secureWindow, new ToggleFeature(Incognito.RECENTS), false, true);
        privacyRow(g, "Quick hide",
            "Double-tap the top bar to leave to the home screen.",
            a.model.quickHide, new ToggleFeature(Incognito.QUICK_HIDE), false, !a.model.quickHide);
        if (a.model.quickHide) quickHideChoice(g);
        privacyRow(g, "Discreet reminders",
            "Reminders say only “Reminder”. The ring timer is a safety warning and "
            + "keeps its words.",
            a.model.discreetReminders, new ToggleFeature(Incognito.REMINDERS), false, true);
        // (the incognito safety review, M5) it asks again after a quick hide, never in front
        // of anything live (AppLock#relockAsks).
        privacyRow(g, "Lock again after a quick hide",                       // ST-4
            "Asks for your fingerprint or PIN when the app starts, and when you come back "
            + "after a quick hide — never in front of a run, a hold or a safety warning.",
            a.model.incognitoLock, new ToggleFeature(Incognito.APP_LOCK), false, true);
        privacyRow(g, "Hide the home-screen widget",
            "Takes it off your home screen and out of the widget list. Add it again after "
            + "turning this off.",
            a.model.hideWidget, new ToggleFeature(Incognito.WIDGET), false, false);
        Ui.note(a, g, "Photos you add “From gallery” are copied into the app’s "
            + "own storage, which the phone’s gallery does not show.");

        Ui.noteInfo(a, a.body, "Android’s own app list and notification headers always "
            + "say “OpenPump”.",
            "What incognito cannot change",
            "Android’s own Settings › Apps list and the small name on top of each "
            + "notification always show the app’s real name, “OpenPump”. The app "
            + "can’t change that while it runs.\n\nWords are hidden on the lock screen "
            + "only when the phone is set to hide sensitive notification content there; set "
            + "to show everything, the lock screen shows what the unlocked phone does — the "
            + "neutral run words, and a safety warning in full.");
    }

    /**
     * A switch row with its words under the name - the mock's row: the name, a caption that
     * says what changes, and the switch. The whole row is the target (48 dp or more) and one
     * focus stop reading "name: on/off. caption".
     */
    private void privacyRow(ViewGroup parent, String title, String caption, boolean on,
                            View.OnClickListener tap, boolean big, boolean rule) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 48));
        r.setPadding(0, Ui.dp(a, Look.S3), 0, Ui.dp(a, Look.S3));
        LinearLayout col = Ui.col(a);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        if (big) {
            t.setTextSize(Look.SP_HEADING);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        } else {
            t.setTextSize(Look.SP_BODY);
            Ui.medium(t);
        }
        col.addView(t);
        TextView c = new TextView(a);
        c.setText(caption);
        c.setTextColor(Ui.DIM);
        c.setTextSize(Look.SP_CAPTION);
        c.setPadding(0, Ui.dp(a, Look.S1), 0, 0);
        col.addView(c);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                Ui.dp(a, Ui.SWITCH_W), Ui.dp(a, Ui.SWITCH_H));
        sp.leftMargin = Ui.dp(a, Look.S4);
        r.addView(Ui.switchView(a, on), sp);
        Ui.group(r, A11y.collapse(title) + ": " + (on ? "on" : "off") + ". "
            + A11y.collapse(caption));
        r.setSelected(on);
        r.setOnClickListener(tap);
        r.setOnTouchListener(new Ui.Press());
        parent.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (rule) Ui.divider(a, parent);
    }

    /** The icon row: which name the home screen shows, and "Change ›" to the choices - the app
     *  itself or one of the disguises. */
    private void iconRow(ViewGroup parent) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 48));
        r.setPadding(0, Ui.dp(a, Look.S3), 0, Ui.dp(a, Look.S3));
        LinearLayout col = Ui.col(a);
        TextView t = new TextView(a);
        t.setText("Home-screen icon and name");
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_BODY);
        Ui.medium(t);
        col.addView(t);
        int identity = Incognito.identity(a.model);
        String shown = "Shown as “" + Incognito.shownName(identity) + "”";
        if (!IncognitoShell.launcherIs(a, identity))
            shown += " — changes when this session ends";
        TextView c = new TextView(a);
        c.setText(shown);
        c.setTextColor(Ui.DIM);
        c.setTextSize(Look.SP_CAPTION);
        c.setPadding(0, Ui.dp(a, Look.S1), 0, 0);
        col.addView(c);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = new TextView(a);
        go.setText("Change ›");
        go.setTextColor(Ui.ACCENT);
        go.setTextSize(Look.SP_BODY);
        Ui.medium(go);
        go.setPadding(Ui.dp(a, Look.S4), 0, 0, 0);
        r.addView(go);
        Ui.group(r, "Home-screen icon and name: " + A11y.collapse(shown) + ". Change");
        r.setOnClickListener(new OpenIconSheet());
        r.setOnTouchListener(new Ui.Press());
        parent.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.divider(a, parent);
    }

    /** Quick hide's choice during a run - A, B or C - and what the chosen one does. */
    private void quickHideChoice(ViewGroup parent) {
        int c = Incognito.quickHideChoice(a.model.quickHideAction);
        TextView lead = new TextView(a);
        lead.setText("During a run, a double-tap:");
        lead.setTextColor(Ui.DIM);
        lead.setTextSize(Look.SP_CAPTION);
        lead.setPadding(0, 0, 0, Ui.dp(a, Look.S1));
        parent.addView(lead);
        Ui.segmented(a, parent, new String[]{ "Leave", "Pause", "STOP" },
            new String[]{ "Leave, the run keeps going", "Leave and pause the run",
                          "Leave and stop the run" },
            c, new View.OnClickListener[]{ new PickQuickHide(Incognito.QH_LEAVE),
                new PickQuickHide(Incognito.QH_HOLD), new PickQuickHide(Incognito.QH_STOP) });
        Ui.note(a, parent, c == Incognito.QH_LEAVE
            ? "Leave; the run keeps going, with its notification and every safety stop."
            : c == Incognito.QH_HOLD
            ? "Leave and pause the run, with the pause's time limit. A run already paused "
              + "stays paused. If the run can't be paused right now, it stops."
            : "Leave and STOP the run. It vents, and the app confirms the vent as it "
              + "does for every STOP.");
        Ui.divider(a, parent);
    }

    /** The master. Turning it on brings the chosen set - and asks, like the App lock card
     *  does, before an app lock the phone has no screen lock to back. */
    private final class ToggleIncognito implements View.OnClickListener {
        @Override public void onClick(View v) {
            boolean on = !a.model.incognito;
            Incognito.setMaster(a.model, on);
            if (on && a.model.incognitoLock && refuseAppLockWithoutDeviceSecurity())
                a.model.incognitoLock = false;
            Store.save(a, a.model);
            boolean waits = a.applyIncognito();
            a.toast(on ? (waits ? "Incognito is on — the icon changes when this session ends"
                               : "Incognito is on")
                       : "Incognito is off");
            showSettings();
        }
    }

    /** One feature's own switch. */
    private final class ToggleFeature implements View.OnClickListener {
        private final int f;
        ToggleFeature(int f) { this.f = f; }
        @Override public void onClick(View v) {
            boolean on = !Incognito.on(a.model, f);
            if (on && f == Incognito.APP_LOCK && refuseAppLockWithoutDeviceSecurity()) return;
            Incognito.setFeature(a.model, f, on);
            Store.save(a, a.model);
            a.applyIncognito();
            showSettings();
        }
    }

    private final class PickQuickHide implements View.OnClickListener {
        private final int c;
        PickQuickHide(int c) { this.c = c; }
        @Override public void onClick(View v) {
            a.model.quickHideAction = Incognito.quickHideChoice(c);
            Store.save(a, a.model);
            showSettings();
        }
    }

    /** The icons, the mock's choice sheet: OpenPump, or one of the disguises - Fitness log,
     *  Habits or Notes (Incognito#DISGUISES), each drawn with its own icon and name. */
    private final class OpenIconSheet implements View.OnClickListener {
        @Override public void onClick(View v) {
            LinearLayout col = Ui.col(a);
            col.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S3), Ui.dp(a, Look.S5), 0);
            Ui.note(a, col, "Pick how the app looks on your home screen and in the app drawer. "
                + "Your launcher may take a few seconds to update.");
            final AlertDialog[] holder = new AlertDialog[1];
            iconChoice(col, Incognito.REAL, "The real name and logo", holder);
            for (int i = 0; i < Incognito.DISGUISES.length; i++) {
                int d = Incognito.DISGUISES[i];
                iconChoice(col, d, Incognito.DISGUISE_LOOKS[d], holder);
            }
            Ui.note(a, col, "Shortcuts and the widget may need adding again. During a session "
                + "the change waits until it ends.");
            holder[0] = Ui.dialog(a)
                .setTitle("Home-screen icon and name")
                .setView(col)
                .setNegativeButton("Close", null)
                .show();
            Ui.sheet(a, holder[0]);
        }
    }

    /** One choice in the icon sheet: the icon itself, its name, and a ring when chosen. */
    private void iconChoice(ViewGroup parent, int identity, String sub, AlertDialog[] sheet) {
        String name = Incognito.shownName(identity);
        boolean chosen = Incognito.identity(a.model) == identity;
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(a, 64));
        r.setPadding(Ui.dp(a, Look.S5), Ui.dp(a, Look.S3), Ui.dp(a, Look.S5), Ui.dp(a, Look.S3));
        android.graphics.drawable.GradientDrawable bg = Ui.roundRect(a, Ui.SURFHI, Look.R_CARD);
        if (chosen) bg.setStroke(Ui.dp(a, 2), Ui.ACCENT);
        r.setBackground(bg);
        android.widget.ImageView icon = new android.widget.ImageView(a);
        icon.setImageResource(IncognitoShell.iconRes(identity));
        Ui.decorative(icon);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48));
        ip.rightMargin = Ui.dp(a, Look.S5);
        r.addView(icon, ip);
        LinearLayout col = Ui.col(a);
        TextView t = new TextView(a);
        t.setText(name);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_BODY);
        Ui.medium(t);
        col.addView(t);
        TextView s = new TextView(a);
        s.setText(sub);
        s.setTextColor(Ui.DIM);
        s.setTextSize(Look.SP_CAPTION);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Ui.group(r, A11y.state(name, chosen) + ". " + sub);
        r.setSelected(chosen);
        r.setOnClickListener(new PickIcon(identity, sheet));
        r.setOnTouchListener(new Ui.Press());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, Look.S3);
        parent.addView(r, lp);
    }

    /** A pick in the icon sheet: through Incognito#choose, which keeps the switch's rule. */
    private final class PickIcon implements View.OnClickListener {
        private final int identity;
        private final AlertDialog[] sheet;
        PickIcon(int id, AlertDialog[] s) { identity = id; sheet = s; }
        @Override public void onClick(View v) {
            if (sheet[0] != null) sheet[0].dismiss();
            if (Incognito.identity(a.model) == identity) return;
            Incognito.choose(a.model, identity);
            Store.save(a, a.model);
            boolean waits = a.applyIncognito();
            a.toast(waits
                ? "The icon changes to “" + Incognito.shownName(identity)
                  + "” when this session ends"
                : "The home screen now shows “" + Incognito.shownName(identity)
                  + "” — your launcher may take a few seconds");
            showSettings();
        }
    }

    private boolean refuseAppLockWithoutDeviceSecurity() {
        if (deviceSecure(a)) return false;
        Ui.dress(a, Ui.dialog(a)
            .setTitle("No screen lock set")
            .setMessage("App lock needs a fingerprint, PIN, pattern or password set on this "
                + "phone to challenge — otherwise there would be nothing to ask. Set one in "
                + "your phone's own Settings, then come back and turn this on.")
            .setPositiveButton("OK", null)
            .show());
        return true;
    }

    /** Whether this phone has a fingerprint, PIN, pattern or password for an app lock to ask
     *  for - the fact every app-lock switch is refused without. Shared with the first-run
     *  setup's "Only me", which says the refusal in its own words rather than a dialog. */
    static boolean deviceSecure(android.app.Activity a) {
        KeyguardManager km = (KeyguardManager) a.getSystemService(android.content.Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    private final class TogglePrivacyBlur implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.privacyBlur = !a.model.privacyBlur;
            // Turning the blur ON must not leave the page toggle standing: it is
            // "I am reading this right now" state, and a stale one would leave every
            // number the switch was just flipped to hide in plain sight.
            a.blurRevealSession = false;
            Store.save(a, a.model);
            a.toast(a.model.privacyBlur
                ? "Photos and measurements are blurred — 👁 on Progress reveals them"
                : "Blur off — photos and measurements show normally");
            showSettings();
        }
    }

    /** One 0.5 cm step of ONE of the three goals — `method` says which
     *  (Model#GOAL_METHODS). The FIRST tap on an unset goal does not step from zero: it
     *  seeds from the latest reading OF THAT METHOD, so "+" means "a little more than I am
     *  now" rather than "0.5 cm", which as an absolute goal would be nonsense. Seeding from
     *  the method's own reading rather than from the newest reading of ANY method is the
     *  same discipline the cap follows below — a BPSSL goal seeded off a standardized
     *  reading starts at a number measured a different way.
     *  Every save goes through Model#capGoal, so the stored number is always one the app
     *  will draw. */
    private final class BumpGoal implements View.OnClickListener {
        private final int method;
        private final int dir;
        BumpGoal(int m, int d) { method = m; dir = d; }
        @Override public void onClick(View v) {
            boolean len = Model.goalMethodIsLength(method);
            double now = Meas.goalLatestCm(a.model.measLog.all, method, len);
            Double cur = a.model.goalForMethod(method);
            double next;
            if (cur == null) {
                if (now <= 0) {
                    a.toast("Log a " + Model.Reading.methodLabel(method) + " reading first — "
                        + "a goal is measured from where you are");
                    return;
                }
                next = now + (dir > 0 ? a.GOAL_STEP_CM : -a.GOAL_STEP_CM);
            } else {
                next = cur.doubleValue() + dir * a.GOAL_STEP_CM;
            }
            commitGoal(method, next, now);
        }
    }

    /** Tapping the VALUE of a goal row types it exactly — the same instrument the set
     *  editor gives every parameter (ParamTypeTap), on the one screen that had only ±.
     *  A length is typed in whatever unit the screen is SHOWING, so it comes back through
     *  Fmt#typedCm; all three goals are lengths in that sense (a girth is a circumference,
     *  measured in the same unit), so there is one conversion and no branch. */
    private final class GoalTypeTap implements View.OnClickListener {
        private final int method;
        GoalTypeTap(int m) { method = m; }
        @Override public void onClick(View v) {
            if (a.isFinishing()) return;
            final EditText e = new EditText(a);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                         | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            e.setTextColor(Ui.TEXT);
            e.setHint(Model.Fmt.lenUnit());
            Ui.dress(a, Ui.dialog(a)
                .setTitle(Model.goalLabel(method) + " (" + Model.Fmt.lenUnit() + ")")
                .setView(e)
                .setPositiveButton("Set", new GoalTypeConfirm(method, e))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class GoalTypeConfirm implements DialogInterface.OnClickListener {
        private final int method; private final EditText e;
        GoalTypeConfirm(int m, EditText edit) { method = m; e = edit; }
        @Override public void onClick(DialogInterface d, int w) {
            double typed;
            try {
                typed = Model.Fmt.typedNumber(e.getText().toString());
            } catch (NumberFormatException ex) {
                a.toast("Not a number — nothing changed");
                return;
            }
            boolean len = Model.goalMethodIsLength(method);
            commitGoal(method, Model.Fmt.typedCm(typed),
                       Meas.goalLatestCm(a.model.measLog.all, method, len));
        }
    }

    /** The ONE place a goal is written: below the method's own latest reading it is
     *  CLEARED (a target already met draws a line under the trend and captions "0.0 cm to
     *  go" forever), otherwise it is capped against that method's own FIRST reading and
     *  stored. Both the stepper and the typed dialog end here, so the two gestures can
     *  never bound the same number two different ways — the defect the set editor's
     *  setParamExact funnel exists to prevent, applied to goals.
     *  `nowCm` is the method's latest reading (0 when it has none). */
    private void commitGoal(int method, double next, double nowCm) {
        boolean len = Model.goalMethodIsLength(method);
        if (Double.isNaN(next) || Double.isInfinite(next)) {
            a.toast("Not a number — nothing changed");
            return;
        }
        // ST-10: THE STEPPER STOPS AT THE LATEST READING and says why. Stepping below it
        // used to clear the goal silently ("Goal cleared — it was below…").
        if (nowCm > 0 && next < nowCm) {
            next = nowCm;
            a.toast("A goal can't be below your latest reading");
        }
        // The cap is measured from THIS goal's own method's first reading — never from the
        // newest reading of any method, which would bound a BPSSL goal against a
        // standardized one. No reading of that method yet means no cap.
        double capBase = Meas.goalBaselineCm(a.model.measLog.all, method, len);
        double capped = Model.capGoal(next, capBase, len, a.model.goalHorizonMonths);
        boolean wasCapped = Math.abs(capped - next) > 1e-9;
        a.model.setGoalForMethod(method, Double.valueOf(capped));
        Store.save(a, a.model);
        if (wasCapped) a.toast("Goal capped to a realistic rate");
        showSettings();
    }

    /** The goals' shared TIME HORIZON. Shortening it can make a goal that was reachable
     *  over three years unreachable over one, so ALL THREE goals are re-capped against
     *  their own baselines here and now — leaving a stored number the new horizon would
     *  refuse would draw a line steeper than the cap the app promises never to draw. */
    private final class GoalHorizonTap implements View.OnClickListener {
        private final int months;
        GoalHorizonTap(int m) { months = m; }
        @Override public void onClick(View v) {
            a.model.goalHorizonMonths = Model.clampHorizon(months);
            boolean trimmed = recapGoals();
            Store.save(a, a.model);
            if (trimmed) a.toast("Goal capped to a realistic rate");
            showSettings();
        }
    }

    /** S10+'s "Goal projection uses" row (Settings > Goals) — flips {@link
     *  Model#goalProjectionSmoothed}. No goal to re-cap and nothing to trim (unlike {@link
     *  GoalHorizonTap} beside it): this changes which reading the trend badge/PACE ACTUAL
     *  compare against, never the goal value or its horizon. */
    private final class GoalProjectionSourceTap implements View.OnClickListener {
        private final boolean smoothed;
        GoalProjectionSourceTap(boolean s) { smoothed = s; }
        @Override public void onClick(View v) {
            a.model.goalProjectionSmoothed = smoothed;
            Store.save(a, a.model);
            showSettings();
        }
    }

    /** Re-caps ALL THREE goals, each against ITS OWN method's baseline and the shared
     *  horizon, returning whether any was actually trimmed. One place, so the steppers, the
     *  typed dialog and the horizon chips can never bound a goal three different ways. A
     *  method with no reading has no baseline and so no cap — see Meas#goalBaselineCm. */
    private boolean recapGoals() {
        boolean trimmed = false;
        for (int i = 0; i < Model.GOAL_METHODS.length; i++) {
            int method = Model.GOAL_METHODS[i];
            Double g = a.model.goalForMethod(method);
            if (g == null) continue;
            boolean len = Model.goalMethodIsLength(method);
            double c = Model.capGoal(g.doubleValue(),
                Meas.goalBaselineCm(a.model.measLog.all, method, len),
                len, a.model.goalHorizonMonths);
            if (Math.abs(c - g.doubleValue()) > 1e-9) trimmed = true;
            a.model.setGoalForMethod(method, Double.valueOf(c));
        }
        return trimmed;
    }

    private final class ClearGoalsTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            // ST-9: the goals as they were, for the snack's Undo.
            Double[] was = new Double[Model.GOAL_METHODS.length];
            for (int i = 0; i < Model.GOAL_METHODS.length; i++) {
                was[i] = a.model.goalForMethod(Model.GOAL_METHODS[i]);
                a.model.setGoalForMethod(Model.GOAL_METHODS[i], null);
            }
            Store.save(a, a.model);
            showSettings();
            Ui.snack(a, a.rootFrame, "Goals cleared", Ui.UNDO, new UndoClearGoalsTap(was),
                     Snack.HOLD_MS_ACTIONABLE);
        }
    }

    /** ST-9's Undo: every goal back as it was. */
    private final class UndoClearGoalsTap implements View.OnClickListener {
        private final Double[] was;
        UndoClearGoalsTap(Double[] w) { was = w; }
        @Override public void onClick(View v) {
            for (int i = 0; i < Model.GOAL_METHODS.length && i < was.length; i++)
                a.model.setGoalForMethod(Model.GOAL_METHODS[i], was[i]);
            Store.save(a, a.model);
            showSettings();
        }
    }

    /** ST-8: a goal row's plain name - the method is said in the Goals ⓘ. */
    private static String goalName(int method) {
        switch (method) {
            case Model.Reading.METHOD_BPSSL: return "Length goal";
            case Model.Reading.METHOD_MSEG:  return "Erect girth goal";
            case Model.Reading.METHOD_MSSG:  return "Soft girth goal";
            default:                         return Model.goalLabel(method);
        }
    }

    private final class OpenHelpTap implements View.OnClickListener {
        @Override public void onClick(View v) { a.showHelp(); }
    }

    private final class DevUnlockTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.isFinishing()) return;
            final EditText e = new EditText(a);
            e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            e.setHint("4-digit code");
            e.setTextColor(Ui.TEXT);
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Developer options")
                .setMessage("These drive the pump directly and are not part of a session. "
                    + "Enter the 4-digit code to show them until the app is closed.")
                .setView(e)
                .setPositiveButton("Open", new DevUnlockConfirm(e))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class DevUnlockConfirm implements DialogInterface.OnClickListener {
        private final EditText e;
        DevUnlockConfirm(EditText edit) { e = edit; }
        @Override public void onClick(DialogInterface d, int w) {
            int typed;
            try {
                typed = Integer.parseInt(e.getText().toString().trim());
            } catch (NumberFormatException ex) {
                a.toast("Not the code");
                return;
            }
            // A plain int compare. "0000", "000" and "0" all parse to 0 and all open it;
            // that is fine, and saying so here is cheaper than pretending otherwise.
            if (typed != a.DEV_CODE) { a.toast("Not the code"); return; }
            a.devUnlocked = true;
            /* THE CODE OPENS THE THING THE CODE WAS FOR.
             *
             * showSettings() rebuilds the whole column, and the rebuild lands at the top -
             * so entering the code put a person back at "Units", several screens above the
             * rows they had just authenticated to reach, with nothing on screen having
             * visibly changed. It read as the code not having worked.
             *
             * Now the category is opened and scrolled to, which is what "Open" said it
             * would do. Posted after the rebuild for the same reason JumpToSettingTap posts:
             * getTop() is 0 until the fresh column has been laid out. */
            a.settingsOpenCategory = a.DEV_CATEGORY;
            showSettings();
            a.scrollToSettingsCategory(a.DEV_CATEGORY);
        }
    }

    /** Item 10: the set browser, filtered to sets no routine references. */
    private final class OpenUnusedSetsTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.setsFilter = null;
            a.setsProv = SessionActivity.PROV_ALL;
            a.setsUnusedOnly = true;
            a.showSetsFiltered();
        }
    }

    private final class ReopenSetupTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.setupDismissed = false;
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "The setup checklist is back on Today");
            a.showHome();
        }
    }

    private final class RunSetupAgainTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (RunService.isRunning() || a.stillUnsafe()) {
                a.toast("Not while a run is going or the pump may be under pressure");
                return;
            }
            a.model.firstRun = FirstRun.IN_PROGRESS;
            Store.save(a, a.model);
            a.showFirstRun();
        }
    }

    private final class DeleteAllTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.stillUnsafe()) {
                a.toast("Not while the pump may be under pressure — vent first");
                return;
            }
            dangerConfirm(Ui.dialog(a)
                .setTitle("Erase all data?")
                .setMessage("Erases every session, reading, photo and setting. The pump is "
                    + "not touched. This cannot be undone.")
                .setPositiveButton("Erase all data", new DeleteAllConfirm1())
                .setNegativeButton("Cancel", null));
        }
    }

    private final class DeleteAllConfirm1 implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) {
            // Re-asked, because the state can have moved between the two taps — a run
            // started from a notification, a link-loss auto-stop arming a vent watch.
            if (a.stillUnsafe()) {
                a.toast("Not while the pump may be under pressure — vent first");
                return;
            }
            dangerConfirm(Ui.dialog(a)
                .setTitle("Really erase everything?")
                .setPositiveButton("Erase everything", new DeleteAllConfirm2())
                .setNegativeButton("Keep my data", null));
        }
    }

    /** ST-11: a destructive confirm - shown, dressed, and its positive button red. */
    private void dangerConfirm(android.app.AlertDialog.Builder b) {
        android.app.AlertDialog d = b.show();
        Ui.dress(a, d);
        Ui.dangerPositive(d);
    }

    private final class DeleteAllConfirm2 implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) { deleteAllData(); }
    }

    private void deleteAllData() {
        // The other answer to an unreadable store: erase it deliberately. Same reason
        // as the restore path — without this the writes below would be refused.
        Store.clearLoadFailure();
        if (a.compare != null) a.compare.stop();

        int photos = 0;
        for (int i = 0; i < a.model.measLog.all.size(); i++) {
            Model.Reading r = a.model.measLog.all.get(i);
            if (r.photoFront != null && a.deletePhotoFile(r.photoFront.path)) photos++;
            if (r.photoSide  != null && a.deletePhotoFile(r.photoSide.path))  photos++;
            if (r.photoTop   != null && a.deletePhotoFile(r.photoTop.path))   photos++;
        }
        File[] orphans = a.orphanPhotoFiles();
        if (orphans != null)
            for (int i = 0; i < orphans.length; i++)
                if (a.deletePhotoFile(orphans[i].getAbsolutePath())) photos++;

        boolean filesGone = Store.deleteAll(a);

        a.model = Model.seed();
        // THE TWO SCREENS THAT HOLD THE MODEL BY REFERENCE ARE REBUILT ON IT. CameraScreen
        // and CompareScreen each keep a `final Model` captured at construction (onCreate),
        // so replacing this field alone would leave both of them reading the DELETED
        // library — Compare would go on offering the erased photos, and the camera's align
        // ghost would resolve against readings that no longer exist. There is no third
        // holder: everything else reads `model` through this field on every use.
        a.camera = new CameraScreen(a, a.body, a.model, a.session, new CameraScreen.ObservedKpa() {
            @Override public Double observedKpaNow() { return a.observedForPhotoOrNull(); }
        }, a, a);
        a.compare = new CompareScreen(a, a.body, a.model, a.new OpenMeasHistBack());

        // Anything holding an id from the model that no longer exists.
        a.blurRevealSession = false;
        a.editSetId = null; a.editRoutId = null; a.draftSetId = null; a.setSnapId = null;
        a.edId = null; a.edOriginal = null; a.edWorking = null;
        a.filedSession = null;
        a.measReadingId = null;
        a.photoReadingId = null; a.photoView = null;
        a.devUnlocked = false;      // the door closes behind an erase, like a fresh launch
        // TASK 6 — the same "door closes" rule for app lock's transient per-session
        // unlocks. model = Model.seed() above already reset the PERSISTED toggles to a
        // fresh install's defaults, but the session-unlock state is a bare static array in
        // AppLock, not part of Model, so wiping the model does not by itself touch it —
        // clearAllSessionState() is the one that does, WHOLE_APP included (onLeaveForeground()
        // alone would not reach it — see that distinction in AppLock's own doc).
        AppLock.clearAllSessionState();
        // THE SAME RE-DERIVATION THE RESTORE PATH DOES, for the same reason: the seeded
        // model carries a fresh install's units and has every reminder off, but neither the
        // formatter nor the alarms notice that on their own. Rescheduling against the seeded
        // model is what CANCELS whatever the erased model had pending (audit A9/A10).
        Model.Fmt.unit = a.model.unit;
        Model.Fmt.sizeUnit = a.model.sizeUnit;
        Model.Fmt.loadUnit = a.model.loadUnit;
        Reminders.reschedule(a, a.model.sched);
        Reminders.rescheduleMeas(a, a.model);
        Reminders.rescheduleTrainer(a, a.model);
        a.refreshWidgetIdle();
        // Incognito (0.10): the seeded model has every switch off - the real icon, the widget
        // back in the list, the window flag off.
        a.applyIncognito();

        a.log("delete all data: " + photos + " photo file(s) removed, store files "
            + (filesGone ? "deleted" : "PARTIALLY deleted"));
        Ui.snack(a, a.rootFrame,
            filesGone ? "All data deleted" : "Deleted — some files could not be removed");
        a.showHome();
    }

    private final class BackupTap implements View.OnClickListener, Runnable {
        /** H1 - the same backup, asked again once a hold's vent is confirmed. */
        @Override public void run() { onClick(null); }
        @Override public void onClick(View v) {
            // H1 - the file picker is another app: a hold on the cuff vents first.
            if (a.ventFirst(HoldHandOff.FILES, null, this, null)) return;
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE);
            create.setType("application/zip");
            create.putExtra(Intent.EXTRA_TITLE,
                Incognito.backupFileName(Incognito.identity(a.model)));
            try {
                a.launchCaptureIntent(create, a.REQ_BACKUP_CREATE);
            } catch (Exception e) {
                a.toast("Could not open the file picker");
            }
        }
    }

    private final class RestoreTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.stillUnsafe()) {
                a.toast("Not while the pump may be under pressure — vent first");
                return;
            }
            // H1 - the door every launch asks. The refusal above is stricter (it covers every
            // way the pump may be under pressure), so this always answers "now" here; it is
            // asked anyway so no launch in the app skips it.
            if (a.ventFirst(HoldHandOff.FILES, null, new RestoreAgain(), null)) return;
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            open.addCategory(Intent.CATEGORY_OPENABLE);
            open.setType("application/zip");
            try {
                a.launchCaptureIntent(open, a.REQ_RESTORE_PICK);
            } catch (Exception e) {
                a.toast("Could not open the file picker");
            }
        }
    }

    /** H1 - the restore picker, asked again once a hold's vent is confirmed. */
    private final class RestoreAgain implements Runnable {
        @Override public void run() { new RestoreTap().onClick(null); }
    }

    /**
     * SETTINGS › SESSION › "Seal check" — the switch that decides whether a ROUTINE run is
     * preceded by the check at all, and the two numbers the check itself uses.
     *
     * The switch defaults OFF. That is the decision on record, not an oversight: a fixed
     * 45-second hold in front of every routine run is a toll on the path people take daily,
     * and the check answers a question ("is the cuff seated?") that most runs do not have.
     * Manual mode keeps its OWN per-run seal toggle and is deliberately not folded in here.
     *
     * The two steppers are a DEPENDENT BLOCK: greyed while the switch is off, never removed,
     * so turning the check off does not make the numbers you chose vanish — the same rule
     * the standardisation hold's block above follows, for the same reason.
     *
     * Target is stored in kPa and rendered through Fmt, so switching display unit only
     * re-renders it; the printed range is min(57, ceiling), the expression clampAll actually
     * enforces, never a copy of it.
     */
    private void sealCheckSettings() {
        /* F16 - THE GUIDED START, above the seal check because it overrides it. Stated as
         * an override rather than shown as a third mutually-exclusive radio: the seal
         * check's own numbers are still used by a MANUAL run with its own check turned on,
         * so hiding them would hide a control that is still live. */
        /* THESE SIX ARE NOT DEVELOPER SETTINGS, and until now they rendered as though they
         * were. Settings is divided by settingsCategory() headers, and the LAST one before
         * this point is "Device & developer" - so Guided start, Set timing, the seal check
         * and the three added after them all sat below "Erase this app's data" and "Debug
         * log", inside that category.
         *
         * That is not only untidy. The jump strip is built FROM the category headers, so
         * none of these had a chip; and the search filter spans header to header, so
         * searching "seal" reported a match in "Device & developer". Two features whose
         * whole job is finding a setting were both telling the truth about a structure that
         * was wrong.
         *
         * They belong together and they belong under their own name: every one of them
         * answers "what does a run DO", which is a different question from "how is a
         * session scheduled and recorded" that the Session category above owns. */
        settingsCategory("How a run behaves");

        LinearLayout gGuide = Ui.cardGroup(a, a.body, "Guided start", null, Ui.ACCENT);
        Ui.kvRow(a, gGuide, "Wait until the cuff holds", a.model.guidedStart,
                 new ToggleGuidedStart());
        // Every routine goes through beginRunFlow, which is where this is asked.
        Ui.note(a, gGuide, "Applies before every routine, the Trainer's included.");
        Ui.noteInfo(a, gGuide,
            (a.model.guidedStart ? ("On. A routine starts only once the cuff has HELD " + Model.Fmt.p(PreRunHold.guidedTargetKpa(a.model.ceilKpa)) + " for two seconds.") : "Off. A routine starts the moment you tap START."),
            "What guided start does",
            a.model.guidedStart
            ? ("On. A routine starts only once the cuff has HELD "
               + Model.Fmt.p(PreRunHold.guidedTargetKpa(a.model.ceilKpa))
               + " for two seconds — not when the pull is sent, so a seal that will not "
               + "take never turns into a routine that runs anyway. After thirty seconds "
               + "it asks what you want to do. This replaces the old seal check for "
               + "routines.")
            : ("Off. A routine starts the moment you tap START. Turn this on and it waits "
               + "for the cuff to actually reach and hold "
               + Model.Fmt.p(PreRunHold.guidedTargetKpa(a.model.ceilKpa)) + " first."));
        if (a.model.guidedStart) {
            /* WHO DOES THE PULLING. The feature shipped assuming a hand bulb and said so
             * on screen, which is an impossible instruction on the electric pump this app
             * exists to drive - the guided start could not be completed by following it.
             * The gate is unchanged either way: the routine starts on EVIDENCE that the
             * cuff held the target, never on a command having been sent. */
            Ui.segmented(a, gGuide, new String[]{ "The pump pulls", "I pull by hand" },
                new String[]{ "the pump pulls", "I pull by hand" },
                a.model.guidedStartAssist ? 0 : 1,
                new View.OnClickListener[]{ new SetGuidedAssist(true), new SetGuidedAssist(false) });
            Ui.note(a, gGuide, a.model.guidedStartAssist
                ? ("The app commands the pull itself, the same way the seal check does, "
                   + "and waits for telemetry to confirm the cuff held it. Cancel vents.")
                : ("Nothing is commanded while it waits — you pull with a hand bulb and "
                   + "the routine starts once the pressure has stayed there."));
        }

        /* F12 - how a set's duration is counted. Beside the guided start because both are
         * answers to "when does the work actually happen", and neither commands anything.
         * C6 - "AT PRESSURE" IS SAID AS WHAT IT NOW IS. In a session the plan counts, a set is
         * timed from the line net time is counted from (the card below), so it runs until it
         * has delivered its planned time under pressure; the drops a set planned are part of
         * its time. "At its target" was true of neither. */
        LinearLayout gTup = Ui.cardGroup(a, a.body, "Set timing", null, Ui.ACCENT);
        Ui.segmented(a, gTup, new String[]{ "By the clock", "At pressure only" },
            new String[]{ "by the clock", "at pressure only" }, a.model.tupTiming ? 1 : 0,
            new View.OnClickListener[]{ new SetTupTiming(false), new SetTupTiming(true) });
        Ui.noteInfo(a, gTup,
            (a.model.tupTiming ? "A set's countdown only advances while the cuff is at pressure." : "A two-minute set runs for two minutes, whatever the pressure did in them."),
            "How a set is timed",
            a.model.tupTiming
            ? ("A set's countdown only advances while the cuff is at pressure, so a slow "
               + "pull or a leak costs wall time and not delivered work. In a session your "
               + "plan counts, at pressure means at or above the line net time is counted "
               + "from (Pressure tolerance, below), so a set runs until it has "
               + "delivered its planned time under pressure; otherwise it means at the set's "
               + "own target. The drops a set planned are part of its time. If a set is "
               + "not reaching pressure the run screen says so and waits, but never for "
               + "longer than the set was planned, and never past 20 minutes in all (a set "
               + "planned longer is not lengthened). Then the set ends and the screen says "
               + "it did not get its time at pressure.")
            : ("A two-minute set runs for two minutes, whatever the pressure did in them. "
               + "This is what the app has always done."));

        /* F15b - THE SWITCH, WHERE IT CAN BE FOUND. It shipped only inside a set's own
         * Repetitions card, which renders for a fixed non-rest set with more than one
         * repetition and nowhere else, so a feature gated on a test the user had run had no
         * path in unless they already knew where to look. A preference belongs in Settings,
         * where the search bar can reach it; the card's own button stays as a second door. */
        // ST-12: a switch named for what it allows, and an Off line that says what Off does.
        LinearLayout gReps = Ui.cardGroup(a, a.body, "Per-repetition edits", null, Ui.ACCENT);
        Ui.kvRow(a, gReps, "Vary repetitions within a set", a.model.repOverridesEnabled,
                 new SetRepOverrides(!a.model.repOverridesEnabled));
        Ui.note(a, gReps,
            a.model.repOverridesEnabled ? "A varied set is sent to the pump as one preset per repetition instead of one repeated." : "Every repetition of a set plays the same values.");
        Ui.noteInfo(a, gReps, "Turn this on only after checking your pump plays back-to-back "
            + "steps without a gap.",
            "The seam test", "A varied set is sent as a short SEQUENCE of presets rather "
            + "than one repeated preset. Whether your pump plays adjacent presets without a "
            + "break is a fact about the device, not something the app can work out. The "
            + "test is two one-minute routines with the same commanded profile — one as a "
            + "single preset, one as two — and you watch the join. If nothing happens "
            + "there, this is safe to turn on. Sets that ALREADY carry per-repetition "
            + "values keep playing them whatever this says, so turning it off never "
            + "changes the shape of a routine you have already built and tested.");

        /* THE COUNTING THRESHOLD. Beside Set timing because the two are the same subject
         * seen from either end: that one decides when the CLOCK advances, this one decides
         * when a SECOND counts as work. Both are answers to "what does delivered mean", and
         * neither commands anything.
         * C6 - AND IN A PLAN SESSION THEY ARE ONE ANSWER. The at-pressure clock asked the
         * control band instead, so a set could run its whole time "at pressure" and file half
         * of it as net. It now times a set from this line, so moving it also moves how long
         * those sets run, and the info below says so. */
        LinearLayout gCount = Ui.cardGroup(a, a.body, "Pressure tolerance", null,   // ST-13
            Ui.ACCENT);
        Ui.stepperRow(a, gCount, "Still counts if this far under target",
            String.format(Locale.US, "%.1f", a.model.tupCountPct) + " %",
            new BumpTupCount(-1), new BumpTupCount(+1), "counting threshold");
        int lvlNow = a.model.trainerEnrolled && a.model.trainerGirth != null
                   ? a.model.trainerGirth.level : Plan.L1;
        double flrNow = a.model.netFloorKpa(Plan.floorKpa(lvlNow));
        Ui.noteInfo(a, gCount,
            "Net time under pressure is counted against your plan's level — " + Model.Fmt.p(flrNow) + " at your current level.",
            "How net time is counted",
            "Net time under pressure is counted against your plan's level — "
            + Model.Fmt.p(flrNow) + " at your current level. A "
            + "reading this far below it still counts, because a pump settles a little "
            + "under a target and breathes around it.");
        Ui.note(a, gCount, "Counting starts at "
            + Model.Fmt.p(a.model.tupCountFloorKpa(Plan.floorKpa(lvlNow)))
            + ". The run chart draws that line.");
        Ui.noteInfo(a, gCount, "Lower is a stricter claim about delivered work.",
            "Pressure tolerance", "This decides whether a second spent slightly under "
            + "your plan's level is counted as time under pressure — and, with Set timing at "
            + "At pressure only, when a set in a plan session is at pressure, so a stricter "
            + "figure makes those sets run longer. It does "
            + "not change what the pump is commanded to do, it does not change the safety "
            + "ceiling, and it does not touch sessions already filed — their figures were "
            + "computed when they were saved and are left exactly as they were. "
            + "0 % means a reading must be at or above the level exactly. Larger values "
            + "count more of a session, which makes the plan's checks easier to pass — "
            + "so the honest direction to move this is down, not up.");

        /* Q3 - HOW A ROUTINE ENDS. In the Session category rather than Measurements
         * because it is about what the PUMP does at the end of a run; the measurement is
         * what it is for, not what it is. */
        // ...AND ITS TONE IS NOT AMBER: nothing here puts the cuff under pressure, and
        // amber is the one tone Ui#cardGroup would draw as a warning edge.
        LinearLayout gEnd = Ui.cardGroup(a, a.body, "When a routine ends", null, Ui.ACCENT);
        Ui.segmented(a, gEnd, new String[]{ "Vent fully", "Hold for the measurement" },
            new String[]{ "vent fully", "hold for the measurement" },
            a.model.endHoldForMeasure ? 1 : 0,
            new View.OnClickListener[]{ new SetEndHold(false), new SetEndHold(true) });
        Ui.note(a, gEnd,
            a.model.endHoldForMeasure ? ("The last set is followed by a hold at " + Model.Fmt.p(a.model.std.kpa) + ", so an after reading is taken the same way as your standardised readings.") : ("An after reading is then taken on a vented cuff, or the standardisation hold pulls back up to take one."));
        // P1 - THE LIMIT MOVED OUT OF THIS CARD. It is no longer this hold's own number: it
        // bounds every hold, including the standardisation hold, so it belongs to holds
        // rather than to routine endings. Its row is in the Standardisation hold card (under
        // Measurements since item 9), and the sentence below points there instead of
        // repeating it.
        if (a.model.endHoldForMeasure)
            Ui.note(a, gEnd, "Like every hold, it vents itself after the hold limit in "
                + "Settings, and leaving the app vents it immediately.");
        Ui.noteInfo(a, gEnd, "It only applies to a routine that finishes normally.",
            "Holding for the measurement", "This does nothing when you STOP a run: an abort "
            + "is somebody asking for pressure to end, and it is never answered with more "
            + "of it. It also does nothing for a manual run, which is not offered an after "
            + "measurement at all, or before you have taken a first reading to compare "
            + "against. The hold is the same one the standardisation hold uses, with the "
            + "same link watchdog: if telemetry stops arriving the pump is vented and you "
            + "are told.");

        // X1 - THE SEAL CHECK'S CARD NOW LIVES IN DEVELOPER OPTIONS, and this comment is
        // where it used to be so the absence is deliberate rather than mysterious. The
        // feature is withdrawn, not deleted: sealCheckCard() below is the same card, drawn
        // behind the developer gate, and model.sealBeforeRoutine still drives the check
        // exactly as it always did once somebody turns it back on there.
    }

    private final class SetRepOverrides implements View.OnClickListener {
        private final boolean on;
        SetRepOverrides(boolean o) { on = o; }
        @Override public void onClick(View v) {
            a.model.repOverridesEnabled = on;
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class SetGuidedAssist implements View.OnClickListener {
        private final boolean assist;
        SetGuidedAssist(boolean own) { assist = own; }
        @Override public void onClick(View v) {
            a.model.guidedStartAssist = assist;
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class BumpTupCount implements View.OnClickListener {
        private final int dir;
        BumpTupCount(int d) { dir = d; }
        @Override public void onClick(View v) {
            // A tenth of a percent per tap: the whole useful range is a few percent, so a
            // whole-percent step would be four taps from one end of it to the other.
            double next = a.model.tupCountPct + dir * 0.1;
            // Quantised for the same reason Fmt#offerStep quantises: n taps up then n taps
            // down must land on the same number, not on 1.9999999999999998.
            next = Math.round(next * 10.0) / 10.0;
            if (next < Model.TUP_COUNT_PCT_MIN) next = Model.TUP_COUNT_PCT_MIN;
            if (next > Model.TUP_COUNT_PCT_MAX) next = Model.TUP_COUNT_PCT_MAX;
            a.model.tupCountPct = next;
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class SetEndHold implements View.OnClickListener {
        private final boolean on;
        SetEndHold(boolean o) { on = o; }
        @Override public void onClick(View v) {
            a.model.endHoldForMeasure = on;
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class BumpHoldMax implements View.OnClickListener {
        private final int dir;
        BumpHoldMax(int d) { dir = d; }
        @Override public void onClick(View v) {
            int next = a.model.holdMaxSec + dir * 30;
            if (next < Model.END_HOLD_MIN_SEC) next = Model.END_HOLD_MIN_SEC;
            if (next > Model.END_HOLD_MAX_SEC) next = Model.END_HOLD_MAX_SEC;
            a.model.holdMaxSec = next;
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class SetTupTiming implements View.OnClickListener {
        private final boolean tup;
        SetTupTiming(boolean t) { tup = t; }
        @Override public void onClick(View v) {
            a.model.tupTiming = tup;
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class ToggleGuidedStart implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.guidedStart = !a.model.guidedStart;
            Store.save(a, a.model);
            a.toast(a.model.guidedStart
                ? "Routines will wait for the cuff to hold"
                : "Routines start as soon as you tap START");
            showSettings();
        }
    }

    private final class ToggleSealBefore implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.sealBeforeRoutine = !a.model.sealBeforeRoutine;
            Store.save(a, a.model);
            a.toast(a.model.sealBeforeRoutine
                ? "Seal check runs before every routine"
                : "Seal check off for routines");
            showSettings();
        }
    }

    /** Steps in whole kPa through the display unit's own step, exactly as the ceiling and
     *  the hold pressure do — and clamps through Model#clampAll, so the printed range and
     *  the enforced range are the same code. */
    private final class BumpSealKpa implements View.OnClickListener {
        private final int d;
        BumpSealKpa(int d) { this.d = d; }
        @Override public void onClick(View v) {
            if (!a.model.sealBeforeRoutine) return;
            a.model.sealCheckKpa = a.stepKpa(a.model.sealCheckKpa, d);
            a.model.clampAll();
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class BumpSealHold implements View.OnClickListener {
        private final int d;
        BumpSealHold(int d) { this.d = d; }
        @Override public void onClick(View v) {
            if (!a.model.sealBeforeRoutine) return;
            a.model.sealCheckHoldS += d * 5;
            a.model.clampAll();
            Store.save(a, a.model);
            showSettings();
        }
    }

    /**
     * SETTINGS › SESSION › "Offer to save what I ran" — the two thresholds, the skip
     * switch, and the per-routine opt-out list.
     *
     * THE PRESSURE THRESHOLD IS STORED IN kPa AND STEPPED IN THE DISPLAY UNIT. Every
     * number here goes out through Model.Fmt (mag() for the threshold — it is a SIZE of a
     * change, never a reading, so it carries no sign in either unit) and comes back in
     * through Model.Fmt.offerStep, which owns the step size and the bounds. Switching the
     * unit therefore only re-renders: nothing is re-stored, so a value cannot drift by
     * being converted out and back.
     *
     * The opt-out list is the other end of the card's "don't ask for this routine again"
     * link — the same Routine.noSaveOffer flag, so a routine added there appears here and
     * ✕ here makes its next run offer again.
     */
    private void offerToSaveSettings() {
        // ST-15: named for what it offers, in DIM - green is the confirmed vent's colour.
        LinearLayout g = Ui.cardGroup(a, a.body, "Offer to save changes",
            "After a run, ask to save a set or routine when you changed it live.", Ui.DIM);

        Ui.stepperRow(a, g, "Ask when pressure changes by at least",
            Model.Fmt.mag(a.model.offerPressureKpa),
            new BumpOfferKpa(-1), new BumpOfferKpa(+1),
            a.new TypeTap(SessionActivity.TypedField.OFFER_KPA), "offer pressure threshold");
        Ui.stepperRow(a, g, "Hold or speed changes by at least",
            a.model.offerHoldSpeedPct + " %",
            new BumpOfferPct(-1), new BumpOfferPct(+1),
            a.new TypeTap(SessionActivity.TypedField.OFFER_PCT), "offer hold and speed threshold");
        Ui.kvRow(a, g, "Also when I skip a set", a.model.offerOnSkip, new ToggleOfferSkip());
        // Fixed sentence — it does not change with the values above, because the quiet
        // line is what happens under ANY threshold, and 0 is the documented way to be
        // asked about everything rather than a disabled state.
        Ui.noteInfo(a, g, "Smaller changes are recorded as a quiet line, not a card.",
            "Offer to save changes", "Smaller changes are still recorded — you'll see a quiet "
            + "\"you nudged it · save ›\" line instead of a card. Set the pressure value "
            + "to 0 to be asked for any change.");

        LinearLayout gNo = Ui.cardGroup(a, a.body, "Don't offer for…",
            "No card after these routines. You can still save from Sessions.", Ui.DIM);
        int listed = 0;
        for (int i = 0; i < a.model.routines.size(); i++) {
            Model.Routine r = a.model.routines.get(i);
            if (!r.noSaveOffer) continue;
            listed++;
            // ST-15: a value row - the name, and "Remove" - with Undo on the snack.
            Ui.kvRow(a, gNo, Say.routineDisplayTitle(r), "Remove", Ui.ACCENT,
                     new OfferAgainTap(r.id));
        }
        if (listed == 0)
            Ui.note(a, gNo, "None — these routines won't show a save card.");
        Button add = Ui.flat(a, gNo, "+ Add routine");
        add.setOnClickListener(new AddNoOfferTap());
    }

    private final class BumpOfferKpa implements View.OnClickListener {
        private final int d;
        BumpOfferKpa(int d) { this.d = d; }
        @Override public void onClick(View v) {
            a.model.offerPressureKpa = Model.Fmt.offerStep(a.model.offerPressureKpa, d);
            a.offerChanged();
        }
    }

    private final class BumpOfferPct implements View.OnClickListener {
        private final int d;
        BumpOfferPct(int d) { this.d = d; }
        @Override public void onClick(View v) {
            a.model.offerHoldSpeedPct = Say.clampI(a.model.offerHoldSpeedPct + d * 5, 5, 50);
            a.offerChanged();
        }
    }

    private final class ToggleOfferSkip implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.offerOnSkip = !a.model.offerOnSkip;
            a.offerChanged();
        }
    }

    /** ✕ on a listed routine: it offers the card again from its next run. */
    private final class OfferAgainTap implements View.OnClickListener {
        private final String id;
        OfferAgainTap(String id) { this.id = id; }
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(id);
            if (r == null) return;
            r.noSaveOffer = false;
            a.offerChanged();
            Ui.snack(a, a.rootFrame, "“" + r.name + "” will offer again", Ui.UNDO,
                new NoOfferAgainTap(id), Snack.HOLD_MS_ACTIONABLE);
        }
    }

    /** ST-15's Undo: the routine goes back on the list. */
    private final class NoOfferAgainTap implements View.OnClickListener {
        private final String id;
        NoOfferAgainTap(String id) { this.id = id; }
        @Override public void onClick(View v) {
            Model.Routine r = a.model.routine(id);
            if (r == null) return;
            r.noSaveOffer = true;
            a.offerChanged();
        }
    }

    /** "+ add routine" — a picker of the routines NOT already opted out. */
    private final class AddNoOfferTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            final List<Model.Routine> pick = new ArrayList<Model.Routine>();
            for (int i = 0; i < a.model.routines.size(); i++) {
                Model.Routine r = a.model.routines.get(i);
                if (!r.noSaveOffer) pick.add(r);
            }
            if (pick.isEmpty()) {
                a.toast(a.model.routines.isEmpty()
                    ? "No routines yet"
                    : "Every routine is already listed");
                return;
            }
            String[] names = new String[pick.size()];
            String[] ids = new String[pick.size()];
            for (int i = 0; i < pick.size(); i++) {
                names[i] = pick.get(i).name;
                ids[i] = pick.get(i).id;
            }
            Ui.sheet(a, Ui.dialog(a)
                .setTitle("Don't offer after…")
                .setItems(names, new PickNoOffer(ids))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class PickNoOffer implements DialogInterface.OnClickListener {
        private final String[] ids;
        PickNoOffer(String[] ids) { this.ids = ids; }
        @Override public void onClick(DialogInterface dlg, int which) {
            if (which < 0 || which >= ids.length) return;
            Model.Routine r = a.model.routine(ids[which]);
            if (r == null) return;
            r.noSaveOffer = true;
            a.offerChanged();
            Ui.snack(a, a.rootFrame, "Added to \"Don't offer for…\"");
        }
    }

    /**
     * R5 - THE WEEKLY PLANNER: what each training day runs.
     *
     * IT LIVES UNDER THE DAY TOGGLES because it is the same question asked one level deeper,
     * and separating them would produce two screens that each tell half of the week. The
     * toggles say WHETHER; these rows say WHAT.
     *
     * A REST DAY SHOWS ITS PLAN GREYED RATHER THAN BLANK. Turning Wednesday off and on again
     * should not forget that Wednesday was your length day - the plan is kept, simply not
     * consulted - so the row keeps printing it, dimmed, and is not tappable while the day is
     * off. Nothing here can be edited into a state the toggles above contradict.
     *
     * "PLAN'S CHOICE" IS A REAL ANSWER, not an empty one, and it is the default: it means
     * whatever you are enrolled in, which is exactly what every day did before this existed.
     */
    private void weeklyPlannerRows(LinearLayout g) {
        /* SEVEN CELLS, NOT SEVEN ROWS.
         *
         * This was one full-width row per weekday, about 150 px each - a thousand pixels of
         * scrolling to express seven values that, on a fresh install, are all the same. A
         * list is at its worst on exactly that case: you have to read all seven to learn
         * there is nothing to read, and you can never hold more than four on screen, so the
         * one thing the setting is actually about - the SHAPE of the week - was the one
         * thing the layout made impossible to see.
         *
         * It is also the week drawn twice. The day toggles above are already seven cells in
         * two rows; this repeated the same seven days underneath in a weaker style with no
         * visual relationship to them. One strip now carries both facts: whether the day
         * trains, and what it runs.
         */
        Ui.sectionHead(a, g, "What each day runs", Ui.DIM);
        /* t10 R-60 - WHILE AN ALTERNATE LONG TRAINING DAYS IS ON, the days and what each runs
         * are the setting's: the strip shows the week it gives, and says so, and a day is not
         * cycled here - the Trainer page's row is where that week is chosen. */
        boolean bySetting = a.model.sched.planSetByLongDays();
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Schedule.DAYS; i++) {
            boolean on = a.model.sched.trainsOn(i);
            int dayPlan = a.model.sched.planOn(i);
            LinearLayout cell = Ui.col(a);
            cell.setGravity(Gravity.CENTER);
            cell.setMinimumHeight(Ui.dp(a, 56));
            cell.setPadding(0, Ui.dp(a, Look.S3), 0, Ui.dp(a, Look.S3));
            cell.setBackground(Ui.roundRect(a, on ? Ui.SURFHI : Ui.SURF, Look.R_CTRL));

            TextView d = new TextView(a);
            d.setText(Schedule.DAY_LETTER[i]);
            d.setGravity(Gravity.CENTER);
            d.setTextSize(Look.SP_CAPTION);
            d.setTextColor(on ? Ui.TEXT : Ui.FAINT);
            cell.addView(d, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            /* THE GLYPH IS THE VALUE, and a day with no preference shows a dash rather than
             * a letter: "plan's choice" is the absence of a choice, and giving it a mark of
             * its own would be a symbol every reader has to learn to ignore. */
            TextView v = new TextView(a);
            String glyph = !on ? "\u00b7" : Say.planLetter(dayPlan);
            v.setText(glyph);
            v.setGravity(Gravity.CENTER);
            v.setTextSize(Look.SP_HEADING);
            v.setTypeface(null, android.graphics.Typeface.BOLD);
            v.setTextColor(!on ? Ui.FAINT : planColour(dayPlan));
            cell.addView(v, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            Ui.group(cell, Schedule.DAY_ABBR[i] + ": "
                + (on ? Schedule.planLabel(dayPlan) : "rest day")
                + (bySetting ? ". " + LongDays.SET_BY + "."
                             : ". Activate to change what it runs."));
            if (on && !bySetting) {
                cell.setOnClickListener(new CyclePlan(i));
                cell.setOnTouchListener(new Ui.Press());
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < Schedule.DAYS - 1) lp.rightMargin = Ui.dp(a, 3);
            row.addView(cell, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(a, Look.S2);
        g.addView(row, rlp);

        TextView key = new TextView(a);
        // One line on a phone: the five marks with one space each, not four. Wrapped,
        // a key stops being a key and becomes another sentence to read.
        // ST-1 / SYS-9: the key in the app's sans, in DIM, its marks each named.
        key.setText("G girth · L length · B both · – plan’s choice · · rest");
        key.setTextColor(Ui.DIM);
        key.setTextSize(Look.SP_MICRO);
        key.setPadding(Ui.dp(a, 2), Ui.dp(a, Look.S3), 0, 0);
        g.addView(key);

        // ST-2: ONE LINE, where the setting was said three times - who sets these days,
        // where to change it, and the days each track gets.
        if (bySetting)
            Ui.note(a, g, LongDays.SET_BY + " (Trainer › When it runs) — "
                + (a.model.sched.splitLengthFirst()
                    ? "length Mon/Wed/Fri, girth Tue/Thu/Sat."
                    : "girth Mon/Wed/Fri, length Tue/Thu/Sat."));
        else Ui.noteInfo(a, g, a.model.trainerLengthOn
            ? "Tap a day to cycle what it runs."
            : "Only the girth track is enrolled, so every day runs girth. Turn the length "
              + "track on in the trainer to use this.",
            "What each day runs",
            "PLAN\u2019S CHOICE is the default and means whatever you are enrolled in "
            + "\u2014 girth, plus length if the length track is on.\n\nGIRTH or LENGTH pins "
            + "the day to one of them, which is how you alternate: girth Monday, length "
            + "Tuesday, and so on.\n\nBOTH runs the two in one day. Today offers them one "
            + "at a time, in the order you set under How you run it, and offers the second "
            + "as soon as the first is filed \u2014 so you can do one in the morning and the "
            + "other in the evening, or stop after one and come back.\n\nNOTHING HERE "
            + "CHANGES YOUR PRESCRIPTION. The plan writes the same routines either way; this "
            + "decides which of them Today puts in front of you.");
    }

    /** Girth takes the action colour, length the body-measurement violet\u2019s neighbour in
     *  the block palette, both the plain text colour, and the plan\u2019s choice is faint -
     *  it is a default, not a decision. */
    private static int planColour(int plan) {
        switch (plan) {
            case Schedule.PLAN_GIRTH:  return Ui.ACCENT;
            case Schedule.PLAN_LENGTH: return Look.BLOCKS[2];
            case Schedule.PLAN_BOTH:   return Ui.TEXT;
            default:                   return Ui.FAINT;
        }
    }

    private final class CyclePlan implements View.OnClickListener {
        private final int i;
        CyclePlan(int i) { this.i = i; }
        @Override public void onClick(View v) {
            int next = (a.model.sched.plan[i] + 1) % Schedule.PLANS_WEEKLY;
            // LENGTH and BOTH are unreachable while the length track is off: offering a day
            // a track the user is not enrolled in would print a plan nothing can satisfy.
            if (!a.model.trainerLengthOn
                && (next == Schedule.PLAN_LENGTH || next == Schedule.PLAN_BOTH))
                next = Schedule.PLAN_ANY;
            a.model.sched.plan[i] = next;
            a.schedChanged();
        }
    }

    private final class ToggleOtherTrack implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.remindOtherTrack = !a.model.remindOtherTrack;
            Store.save(a, a.model);
            // Turning it off drops any alarm already outstanding rather than leaving one
            // aimed at a day whose setting no longer explains it.
            if (!a.model.remindOtherTrack) Reminders.scheduleOtherTrack(a, 0L);
            showSettings();
        }
    }

    private final class ToggleDay implements View.OnClickListener {
        private final int i;
        ToggleDay(int i) { this.i = i; }
        @Override public void onClick(View v) {
            a.model.sched.days[i] = !a.model.sched.days[i];
            // clamp() inside schedChanged turns reminders off when the last day goes, so
            // emptying the schedule cancels the alarm rather than leaving one aimed at a
            // week with nothing in it.
            a.schedChanged();
        }
    }

    /** A 24-hour clock picker for the training time, replacing the two steppers that both
     *  read hh:mm and so looked identical. */
    private final class PickSchedTime implements View.OnClickListener {
        @Override public void onClick(View v) {
            Ui.showPicker(a, Ui.timePicker(a, new SchedTimeSet(),                // ST-16
                a.model.sched.hour, a.model.sched.minute, true));
        }
    }

    private final class SchedTimeSet implements android.app.TimePickerDialog.OnTimeSetListener {
        @Override public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
            a.model.sched.hour = ((h % 24) + 24) % 24;
            a.model.sched.minute = ((m % 60) + 60) % 60;
            a.schedChanged();
        }
    }

    /**
     * The reminder toggle. Turning it ON on API 33+ needs POST_NOTIFICATIONS, so the
     * permission is requested at exactly that moment — the point of use, with the rationale
     * already on screen in the note above the row — rather than at launch alongside
     * Bluetooth. If it is DENIED the toggle goes back OFF and says why: a switch left on
     * while nothing can ever appear is a lie the app tells every time the screen is opened.
     */
    private final class ToggleRemind implements View.OnClickListener {
        @Override public void onClick(View v) { toggleRemind(); }
    }

    /** The reminder switch's whole rule, shared with the first-run setup's "Remind me" so the
     *  two switches cannot ask for the permission differently. schedChanged redraws whichever
     *  of the two screens the switch is on. */
    void toggleRemind() {
        if (a.model.sched.remind) {
            a.model.sched.remind = false;
            a.schedChanged();
            return;
        }
        if (!a.model.sched.any()) {
            a.toast("Choose at least one training day first");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
            a.pendingRemindGrant = true;
            a.requestPermissions(new String[]{ "android.permission.POST_NOTIFICATIONS" },
                               a.REQ_POST_NOTIFICATIONS);
            return;
        }
        a.model.sched.remind = true;
        Reminders.ensureChannel(a);
        a.schedChanged();
    }

    /** The same 24-hour clock picker the training time uses — one pattern, two reminders. */
    private final class PickMeasTime implements View.OnClickListener {
        @Override public void onClick(View v) {
            Ui.showPicker(a, Ui.timePicker(a, new MeasTimeSet(),
                a.model.measRemindHour, a.model.measRemindMin, true));
        }
    }

    private final class MeasTimeSet implements android.app.TimePickerDialog.OnTimeSetListener {
        @Override public void onTimeSet(android.widget.TimePicker tp, int h, int m) {
            a.model.measRemindHour = ((h % 24) + 24) % 24;
            a.model.measRemindMin = ((m % 60) + 60) % 60;
            a.measRemindChanged();
        }
    }

    /** The measurement reminder's toggle. Same POST_NOTIFICATIONS rule the training one
     *  follows, asked at the point of use, and the same refusal to leave a switch on while
     *  nothing can ever appear. It additionally refuses while the CADENCE is off: a reminder
     *  for a question the app has been told never to ask is a notification that can only
     *  ever be wrong. */
    private final class ToggleMeasRemind implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (a.model.measRemind) {
                a.model.measRemind = false;
                a.measRemindChanged();
                return;
            }
            if ("off".equals(a.model.meas.mode)) {
                a.toast("Turn measurements on above first — there is no cadence to follow");
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                a.pendingMeasRemindGrant = true;
                a.requestPermissions(new String[]{ "android.permission.POST_NOTIFICATIONS" },
                                   a.REQ_POST_NOTIFICATIONS);
                return;
            }
            a.model.measRemind = true;
            Reminders.ensureMeasChannel(a);
            a.measRemindChanged();
        }
    }

    /** The SIZE unit's twin of {@link SetUnit} — the model field and the formatter's static
     *  are set together, here, because a screen reading one while the other still says
     *  something else would print a number in a unit its own label denies. */
    private final class SetSizeUnit implements View.OnClickListener {
        private final String u;
        SetSizeUnit(String u) { this.u = u; }
        @Override public void onClick(View v) {
            setSizeUnit(u);
            showSettings();
        }
    }

    /** The size unit, set and saved - shared with the first-run setup. */
    void setSizeUnit(String u) {
        a.model.sizeUnit = u;
        Model.Fmt.sizeUnit = u;
        Store.save(a, a.model);
    }

    /** The LOAD unit's twin of {@link SetSizeUnit} (2026-09-30). */
    private final class SetLoadUnit implements View.OnClickListener {
        private final String u;
        SetLoadUnit(String u) { this.u = u; }
        @Override public void onClick(View v) {
            setLoadUnit(u);
            showSettings();
        }
    }

    /** The load unit, set and saved - the model field and the formatter's static together,
     *  shared with the first-run setup. */
    void setLoadUnit(String u) {
        if (!Model.Fmt.isLoadUnit(u)) return;
        a.model.loadUnit = u;
        Model.Fmt.loadUnit = u;
        Store.save(a, a.model);
    }

    /**
     * T9 — tapping a remembered pump's row in Settings offers the two things there are to
     * do with one: Rename or Forget. A THIRD dialog rather than swallowing either action
     * into the row's own tap (there is no room on a kvRow for two independent targets),
     * mirroring how a routine's own actions live behind opening it rather than on its
     * library row.
     */
    private final class PumpRowTap implements View.OnClickListener {
        private final String address;
        PumpRowTap(String own) { address = own; }
        @Override public void onClick(View v) {
            Model.KnownPump p = a.model.knownPump(address);
            if (p == null) { showSettings(); return; }
            Ui.dress(a, Ui.dialog(a)
                .setTitle(PumpMatch.displayName(p.name))
                .setMessage(address)
                .setPositiveButton("Rename", new RenamePumpChoice(address))
                .setNegativeButton("Forget", new ForgetPumpChoice(address))
                .setNeutralButton("Cancel", null)
                .show());
        }
    }

    /** Opens the rename text-entry dialog — the same shape {@link RenameRoutineTap} uses,
     *  chained off PumpRowTap's own dialog rather than off a View tap. */
    private final class RenamePumpChoice implements DialogInterface.OnClickListener {
        private final String address;
        RenamePumpChoice(String own) { address = own; }
        @Override public void onClick(DialogInterface d, int w) {
            Model.KnownPump p = a.model.knownPump(address);
            if (p == null) return;
            EditText input = new EditText(a);
            input.setHint("pump name");
            input.setText(PumpMatch.displayName(p.name));
            input.setSelection(input.getText().length());
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Pump name")
                .setView(input)
                .setPositiveButton("Rename", new RenamePumpConfirm(address, input))
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class RenamePumpConfirm implements DialogInterface.OnClickListener {
        private final String address; private final EditText input;
        RenamePumpConfirm(String a, EditText e) { address = a; input = e; }
        @Override public void onClick(DialogInterface d, int w) {
            if (a.model.knownPump(address) == null) return;
            a.model.rememberPump(address, input.getText().toString());
            Store.save(a, a.model);
            Ui.snack(a, a.rootFrame, "Renamed");
            showSettings();
        }
    }

    /** The confirm step — {@link DelRoutineTap}'s own single-step shape, appropriate here
     *  for the identical reason: forgetting a pump loses a label and an auto-pick, nothing
     *  irreplaceable (the pump itself is untouched and can always be remembered again). */
    private final class ForgetPumpChoice implements DialogInterface.OnClickListener {
        private final String address;
        ForgetPumpChoice(String own) { address = own; }
        @Override public void onClick(DialogInterface d, int w) {
            Model.KnownPump p = a.model.knownPump(address);
            if (p == null) return;
            dangerConfirm(Ui.dialog(a)
                .setTitle("Forget " + PumpMatch.displayName(p.name) + "?")
                .setMessage("This app will stop auto-connecting to it by name. It is not "
                    + "disconnected if it is connected right now, and you can connect to it "
                    + "and remember it again at any time.")
                .setPositiveButton("Forget", new ForgetPumpConfirm(address))
                .setNegativeButton("Cancel", null));
        }
    }

    private final class ForgetPumpConfirm implements DialogInterface.OnClickListener {
        private final String address;
        ForgetPumpConfirm(String own) { address = own; }
        @Override public void onClick(DialogInterface d, int w) {
            if (a.model.forgetPump(address)) {
                Store.save(a, a.model);
                Ui.snack(a, a.rootFrame, "Forgotten");
            }
            showSettings();
        }
    }

    /** One segment of the cadence control. Through {@link Model.Meas#chooseSegment}, so
     *  "Sessions" with an interval of 1 is stored as the "every" mode it always was. */
    private final class SetMeasSegment implements View.OnClickListener {
        private final int segment;
        SetMeasSegment(int seg) { segment = seg; }
        @Override public void onClick(View v) {
            a.model.meas.chooseSegment(segment);
            // Through the reminder's own path: turning the cadence OFF turns the reminder
            // off (clampAll) and must therefore CANCEL its alarm, or one would stay
            // outstanding aimed at a question the app has been told never to ask.
            a.measRemindChanged();
        }
    }

    private final class BumpMeasInterval implements View.OnClickListener {
        private final int delta;
        BumpMeasInterval(int d) { delta = d; }
        @Override public void onClick(View v) {
            if ("hours".equals(a.model.meas.mode))
                a.model.meas.hours = Say.clampI(a.model.meas.hours + delta, 1, 50);
            else
                a.model.meas.stepSessions(delta);
            Store.save(a, a.model);
            showSettings();
        }
    }

    /** Settings › Session › Keep the screen on — flips Model.keepScreenOn (default ON).
     *  Applied on the NEXT run-screen entry; flicking it here cannot be applying a window
     *  flag to a run, because Settings is a destination and a run is not. */
    private final class ToggleKeepAwake implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.keepScreenOn = !a.model.keepScreenOn;
            Store.save(a, a.model);
            a.toast(a.model.keepScreenOn ? "The screen stays on during a run"
                                     : "The screen may sleep during a run");
            showSettings();
        }
    }

    /** Settings › On the run screen › Run colours (0.10). */
    private final class OpenRunColours implements View.OnClickListener {
        @Override public void onClick(View v) { new RunColoursSheet(a).open(); }
    }

    /** Settings › On the run screen › Where the − / + controls sit (0.10 final): the two
     *  answers as radio rows, each with what it means; a tap chooses, saves and closes. */
    private final class OpenRunStripWhere implements View.OnClickListener {
        @Override public void onClick(View v) {
            LinearLayout host = Ui.col(a);
            int pad = Ui.dp(a, 18);
            host.setPadding(pad, Ui.dp(a, 4), pad, Ui.dp(a, 8));
            TextView note = new TextView(a);
            note.setText("Where the − / + controls for the running step sit.");
            note.setTextColor(Ui.DIM);
            note.setTextSize(Look.SP_CAPTION);
            note.setPadding(0, 0, 0, Ui.dp(a, 10));
            host.addView(note);
            final android.app.AlertDialog[] held = new android.app.AlertDialog[1];
            int now = Model.clampRunStripWhere(a.model.runStripWhere);
            for (int i = 0; i < QuickAdjust.WHERE_NAMES.length; i++)
                host.addView(stripWhereRow(i, i == now, held));
            held[0] = Ui.dialog(a)
                .setTitle("Run screen")
                .setView(host)
                .setNegativeButton("Close", null)
                .show();
            Ui.dress(a, held[0]);
        }
    }

    /** One answer: a radio glyph, its name and what it means - one 48 dp+ row, one tap. */
    private View stripWhereRow(final int which, boolean on,
                               final android.app.AlertDialog[] held) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setMinimumHeight(Ui.dp(a, 56));
        row.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 12));
        android.graphics.drawable.GradientDrawable ring =
            new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setStroke(Ui.dp(a, 2), on ? Ui.ACCENT : Ui.DIM);
        ring.setColor(0);
        android.widget.FrameLayout glyph = new android.widget.FrameLayout(a);
        glyph.setBackground(ring);
        if (on) {
            View dotV = new View(a);
            android.graphics.drawable.GradientDrawable dot =
                new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(Ui.ACCENT);
            dotV.setBackground(dot);
            android.widget.FrameLayout.LayoutParams dl = new android.widget.FrameLayout.LayoutParams(
                Ui.dp(a, 10), Ui.dp(a, 10), android.view.Gravity.CENTER);
            glyph.addView(dotV, dl);
        }
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20));
        gl.rightMargin = Ui.dp(a, 12);
        gl.topMargin = Ui.dp(a, 1);
        row.addView(glyph, gl);
        LinearLayout words = Ui.col(a);
        TextView name = new TextView(a);
        name.setText(QuickAdjust.WHERE_NAMES[which]);
        name.setTextColor(Ui.TEXT);
        name.setTextSize(Look.SP_BODY);
        words.addView(name);
        TextView says = new TextView(a);
        says.setText(QuickAdjust.WHERE_SAYS[which]);
        says.setTextColor(Ui.DIM);
        says.setTextSize(Look.SP_CAPTION);
        says.setPadding(0, Ui.dp(a, 2), 0, 0);
        words.addView(says);
        row.addView(words, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setContentDescription(QuickAdjust.WHERE_NAMES[which] + ". "
            + QuickAdjust.WHERE_SAYS[which] + (on ? " Selected." : ""));
        row.setSelected(on);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.model.runStripWhere = which;
                Store.save(a, a.model);
                if (held[0] != null && held[0].isShowing()) held[0].dismiss();
                showSettings();
            }
        });
        return row;
    }

    /** Settings › On the run screen › Vibrate before the pull (0.10, default OFF). */
    private final class TogglePullBuzz implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.pullBuzz = !a.model.pullBuzz;
            Store.save(a, a.model);
            // Buzz on the tap that turns it on, so it demonstrates itself.
            if (a.model.pullBuzz) a.vibrateNow(300);
            showSettings();
        }
    }

    private final class ToggleHaptic implements View.OnClickListener {
        @Override public void onClick(View v) {
            toggleHaptic();
            showSettings();
        }
    }

    /** Flips the haptic ticks and saves - shared with the first-run setup. */
    void toggleHaptic() {
        a.model.hapticTicks = !a.model.hapticTicks;
        Store.save(a, a.model);
        // Buzz ON the tap that turns it on, so the setting demonstrates itself rather
        // than being a word the user has to start a run to verify. Turning it OFF is
        // silent, which is the honest confirmation of "off".
        if (a.model.hapticTicks) a.buzz(a.CHANGE_MS);
    }

    /** Settings › On the run screen › Tone cues — flips Model.toneCues (S12, default OFF). */
    private final class ToggleTone implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.toneCues = !a.model.toneCues;
            Store.save(a, a.model);
            // Same demonstration rule as ToggleHaptic, immediately above: sound ON the tap
            // that turns it on, so the setting proves itself rather than being a word to
            // trust — and going through playTone() rather than a raw ToneGenerator call
            // here means the demo obeys the identical ringer/DND gate a real cue would, so
            // a demo that stays silent is telling the truth about what the run screen will
            // do too. Turning it OFF is silent, the honest confirmation of "off".
            if (a.model.toneCues) a.playTone(Tone.STAGE_CHANGE);
            showSettings();
        }
    }

    /** Settings › On the run screen › Sound on a repetition — opens the choice
     *  between OFF, the routine's last repetition, and a number typed in. Three items
     *  rather than a stepper: two of the three are not numbers, and "last" is the one most
     *  people want and the one whose number differs per routine. */
    private final class PickRepCue implements View.OnClickListener {
        @Override public void onClick(View v) {
            String[] items = { "Off", "Last repetition", "A repetition number…" };
            Ui.dress(a, Ui.dialog(a)
                .setTitle("Sound on a repetition")
                .setItems(items, new RepCueChoice())
                .setNegativeButton("Cancel", null)
                .show());
        }
    }

    private final class RepCueChoice implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int which) {
            if (which == 2) { askRepCueNumber(); return; }
            commitRepCue(which == 1 ? RepCue.LAST : RepCue.OFF);
        }
    }

    /** The typed branch. Bounded by {@link RepCue#MAX} and refused rather than clamped: a
     *  number outside the range is a mistake, and quietly storing a different one would
     *  leave the row saying something the person did not choose. */
    private void askRepCueNumber() {
        final EditText e = new EditText(a);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setTextColor(Ui.TEXT);
        e.setHint("1 to " + RepCue.MAX);
        Ui.dress(a, Ui.dialog(a)
            .setTitle("Which repetition")
            .setMessage("Counted in the routine's own repetitions, from the start of the "
                + "session. A number past the end of a routine stays silent on it.")
            .setView(e)
            .setPositiveButton("Set", new RepCueTypeConfirm(e))
            .setNegativeButton("Cancel", null)
            .show());
    }

    private final class RepCueTypeConfirm implements DialogInterface.OnClickListener {
        private final EditText e;
        RepCueTypeConfirm(EditText edit) { e = edit; }
        @Override public void onClick(DialogInterface d, int w) {
            String raw = e.getText().toString().trim();
            int typed;
            try {
                typed = Integer.parseInt(raw);
            } catch (NumberFormatException ex) {
                // ELEVEN DIGITS IS A NUMBER, it is just not an int. Telling someone who
                // typed 10000000000 that it is "not a number" names the wrong mistake; the
                // mistake is the range, and the range message is right there.
                boolean digits = raw.length() > 0;
                for (int i = 0; i < raw.length() && digits; i++)
                    if (raw.charAt(i) < '0' || raw.charAt(i) > '9') digits = false;
                a.toast(digits ? ("A repetition is 1 to " + RepCue.MAX + " — nothing changed")
                               : "Not a number — nothing changed");
                return;
            }
            if (typed < 1 || typed > RepCue.MAX) {
                a.toast("A repetition is 1 to " + RepCue.MAX + " — nothing changed");
                return;
            }
            commitRepCue(typed);
        }
    }

    /** The ONE place the preference is written, so the three ways to choose it cannot
     *  store it three different ways. Sounds the cue on the choice that turns it on —
     *  {@link ToggleTone}'s own demonstration rule, and through the same playTone, so a
     *  demo that stays silent is telling the truth about a run that would too. */
    private void commitRepCue(int pref) {
        a.model.repCueAt = RepCue.clampPref(pref);
        Store.save(a, a.model);
        // CHOSEN DURING A RUN, IT SPEAKS ABOUT WHAT IS LEFT OF IT. Without this the latch
        // is still at NONE, and a number already passed fires on the very next heartbeat -
        // announcing a repetition that is behind you.
        a.armRepCue();
        if (a.model.repCueAt != RepCue.OFF) a.playTone(Tone.REP_CUE, true);
        showSettings();
    }

    /** Asks again for the notification permission, because the person chose to here. */
    private final class AskRunNotifications implements View.OnClickListener {
        @Override public void onClick(View v) { a.askNotificationsFromSettings(); }
    }


    private final class ToggleStdOn implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.std.on = !a.model.std.on;
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class ToggleStdPhoto implements View.OnClickListener {
        @Override public void onClick(View v) {
            a.model.std.photo = !a.model.std.photo;
            Store.save(a, a.model);
            showSettings();
        }
    }

    /** The hold pressure is capped by the safety ceiling — data-cap="ceil" in the
     *  prototype's markup — and dragging it down pulls release-to down with it. */
    private final class BumpStdKpa implements View.OnClickListener {
        private final int delta;
        BumpStdKpa(int d) { delta = d; }
        @Override public void onClick(View v) {
            int cap = Math.min(57, a.model.ceilKpa);
            a.model.std.kpa = Say.clampI(a.stepKpa(a.model.std.kpa, delta), 5, cap);
            if (a.model.std.release > a.model.std.kpa) a.model.std.release = a.model.std.kpa;
            Store.save(a, a.model);
            showSettings();
        }
    }

    private final class BumpStdSec implements View.OnClickListener {
        private final int delta;
        BumpStdSec(int d) { delta = d; }
        @Override public void onClick(View v) {
            a.model.std.sec = Say.clampI(a.model.std.sec + delta * 5, 10, 60);
            Store.save(a, a.model);
            showSettings();
        }
    }

    /** Release-to is capped by the hold pressure itself — data-cap="hold" — never by
     *  the ceiling directly. */
    private final class BumpStdRel implements View.OnClickListener {
        private final int delta;
        BumpStdRel(int d) { delta = d; }
        @Override public void onClick(View v) {
            int cap = Math.min(57, a.model.std.kpa);
            a.model.std.release = Say.clampI(a.stepKpa(a.model.std.release, delta), 0, cap);
            Store.save(a, a.model);
            showSettings();
        }
    }

    /** Changing the ceiling re-clamps every set AND the standardisation hold immediately
     *  (Model.clampAll), not on the next edit of each field. */
    private final class BumpCeil implements View.OnClickListener {
        private final int delta;
        BumpCeil(int d) { delta = d; }
        @Override public void onClick(View v) {
            /* THE CEILING CANNOT MOVE MID-RUN, and this is the one lock that is about a
             * number rather than an action.
             *
             * The ceiling is applied when the table is UPLOADED - every preset is clamped on
             * its way to the device - so lowering it now would not lower what the pump is
             * already holding, and the app would be showing a ceiling the running session is
             * not obeying. Raising it is worse: the next batch of a run already in progress
             * would be written at a limit the person set while under pressure, which is
             * exactly the moment to be least trusted with it. */
            if (bumpCeiling(delta)) showSettings();
        }
    }

    /** One step of the ceiling, re-clamping everything under it, and saved; false (having
     *  said why) while a run is on. Shared with the first-run setup, so the ceiling moves by
     *  the same steps within the same bounds wherever it is set. */
    boolean bumpCeiling(int delta) {
        if (a.lockedDuringRun("Changing the safety ceiling")) return false;
        a.model.ceilKpa = Say.clampI(a.stepKpa(a.model.ceilKpa, delta), 7, 57);
        a.model.clampAll();
        Store.save(a, a.model);
        return true;
    }

    /** How many photo files this phone is holding: every captured view on every reading,
     *  plus any capture left on disk by a baseline that was abandoned before Save (the
     *  file is written at "Use this", the reading only at Save — so orphans are real and
     *  a count that ignored them would understate what erasing has to remove). */
    private int storedPhotoCount() {
        int n = 0;
        for (int i = 0; i < a.model.measLog.all.size(); i++) {
            Model.Reading r = a.model.measLog.all.get(i);
            if (r.photoFront != null) n++;
            if (r.photoSide != null) n++;
            if (r.photoTop != null) n++;
        }
        File[] orphans = a.orphanPhotoFiles();
        return n + (orphans == null ? 0 : orphans.length);
    }

    /**
     * The confirmation names the number, because this is not undoable and the button sits
     * next to an export button. The previous build simply toasted "Photos erased" and did
     * nothing at all — a false confirmation about a privacy control, which is worse than
     * an absent feature.
     */
    private final class ErasePhotosTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            int n = storedPhotoCount();
            if (n == 0) { a.toast("No photos to erase"); return; }
            dangerConfirm(Ui.dialog(a)
                .setTitle("Erase " + n + (n == 1 ? " photo?" : " photos?"))
                .setMessage("Deletes the image files from this phone and removes them from "
                    + "their readings. Measurements, notes and history are kept. This cannot "
                    + "be undone.")
                .setPositiveButton("Erase", new ErasePhotosConfirm())
                .setNegativeButton("Cancel", null));
        }
    }

    private final class ErasePhotosConfirm implements DialogInterface.OnClickListener {
        @Override public void onClick(DialogInterface d, int w) { erasePhotos(); }
    }

    /**
     * Deletes every capture and drops every reference to one. Compare is stopped first:
     * it holds decoded bitmaps of files that are about to stop existing, and its next
     * render then finds fewer than two photos and draws its own empty state.
     *
     * Reports what actually happened rather than what was attempted — a file the OS
     * refuses to delete is named in the toast, not quietly counted as erased. The
     * reference is dropped either way: a reading pointing at a file this app can no
     * longer manage would be a phantom.
     */
    private void erasePhotos() {
        if (a.compare != null) a.compare.stop();
        int gone = 0, left = 0;
        for (int i = 0; i < a.model.measLog.all.size(); i++) {
            Model.Reading r = a.model.measLog.all.get(i);
            if (r.photoFront != null) { if (a.deletePhotoFile(r.photoFront.path)) gone++; else left++; }
            if (r.photoSide  != null) { if (a.deletePhotoFile(r.photoSide.path))  gone++; else left++; }
            if (r.photoTop   != null) { if (a.deletePhotoFile(r.photoTop.path))   gone++; else left++; }
            r.photoFront = null;
            r.photoSide = null;
            r.photoTop = null;
            r.photo = false;
        }
        File[] orphans = a.orphanPhotoFiles();
        if (orphans != null)
            for (int i = 0; i < orphans.length; i++) {
                if (a.deletePhotoFile(orphans[i].getAbsolutePath())) gone++; else left++;
            }
        Store.save(a, a.model);
        a.log("erase photos: " + gone + " removed, " + left + " could not be deleted");
        Ui.snack(a, a.rootFrame, left == 0
            ? gone + (gone == 1 ? " photo erased" : " photos erased")
            : gone + " erased · " + left + " could not be deleted");
        showSettings();
    }
}
