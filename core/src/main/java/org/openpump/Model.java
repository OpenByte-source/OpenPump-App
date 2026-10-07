package org.openpump;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The domain. A SET is one pump program; a ROUTINE is an ordered list of sets.
 *
 * A fixed set maps 1:1 onto a device preset. A ramp set walks target, drop and speed to
 * end values across `steps` presets — the pump holds only two setpoints at a time, so the
 * app owns that expansion and says so rather than pretending the hardware ramps.
 */
public final class Model {

    /* ------------------------------------------------------------------ set */

    public static final class Set {
        public String id, name;
        /**
         * SHOWN IN TODAY'S FAVOURITES (F3). A set is a runnable thing in its own right —
         * "Try this set" has always started one on the ephemeral run path — but reaching it
         * meant Library, then the set, then the editor. A starred set now sits beside the
         * starred routines, one tap from Today.
         *
         * MIGRATION: false. A set saved before this existed is one nobody has starred.
         */
        public boolean star;
        public boolean ramp;
        /**
         * A REST ENTRY, wearing a set's clothes. A stage can now contain "wait here, with
         * the cuff vented, for this long", and rather than inventing a second kind of thing
         * a stage may hold — which would mean a second shape in Stage#setIds, a second
         * branch in every reorder, picker and editor, and a second row type everywhere a
         * stage is listed — a rest is a SET whose only meaningful field is `dur`.
         *
         * Everything else on this object is ignored while `rest` is true: {@link #ladder}
         * still produces one preset so the plan's index arithmetic is untouched, but that
         * preset commands NOTHING (all five wire values zero) and is marked
         * {@link Preset#rest} for the run screen and the recorder. It is never armed — see
         * the rest branch in SessionActivity#playPreset.
         *
         * Migration: false, so every set ever saved is what it always was.
         */
        public boolean rest;
        public int up, lo, uh, lh, sp;          // kPa, kPa, s, s, %
        public int up2, lo2, sp2, steps;        // ramp end values
        /** Ramp end HOLD-at-max and DROP seconds. These ramp exactly as up/lo/sp do:
         *  a ramp can now walk its TIMING as well as its pressure. Seeded equal to
         *  uh/lh everywhere they are not explicitly given — including on load (see
         *  {@link #fromJson}) — so a set written before these existed ramps nothing in
         *  time and behaves exactly as it always did. */
        public int uh2, lh2;
        public int dur;                          // seconds this set runs for
        /** The session timestamp this set was SAVED OUT OF, or 0 when a person built it by
         *  hand. Set only by the "save what you ran" card, so a library row can say where a
         *  set came from and History can find the sets a given run produced. 0 on every set
         *  written before this existed, which is the truth about them: nothing in the app
         *  can retro-fit a run to a hand-made set. {@link #copy} deliberately clears it —
         *  a duplicate is a NEW set a person made from an old one, not a second record of
         *  the same run. */
        public long fromRun;
        /**
         * THE COLOUR MARK \u2014 0 for none, 1..6 an index into {@link Look#BLOCKS}.
         *
         * A mark means whatever the person who put it there decided it means. That is the
         * whole feature, and it is also the reason the palette is BLOCKS: those six are
         * documented and SelfTest-pinned as carrying NO meaning of their own, chosen
         * precisely because none of them is lime, amber, green, violet or red. A mark
         * drawn in amber would say "the pump is under pressure" to anyone who had learnt
         * the rest of the app, and it would be lying.
         *
         * It is also never drawn as text colour or as a fill behind text \u2014 only as a
         * rail at a row's leading edge or a dot beside a name, positions nothing semantic
         * uses \u2014 so it cannot be read as a state even by someone who has not learnt
         * the palette.
         *
         * MIGRATION: 0. Every set ever saved is one nobody has marked.
         */
        public int mark;
        /**
         * THIS SET ARRIVED IN A SHARE CODE (O4). Imported sets used to land in the library
         * with nothing distinguishing them from the person's own work; a couple of shared
         * routines and the list is full of strangers.
         *
         * Set at exactly one place \u2014 {@link Model#importShareCode}, the real boundary
         * where a foreign set enters this library \u2014 for the same reason `fromRun` is
         * cleared there: the sending phone's value for this field is not evidence about
         * this phone. {@link #copy} clears it, because a duplicate is a set THIS person
         * made, however they came by the numbers.
         *
         * MIGRATION: false. A set saved before this existed cannot be shown to have been
         * imported, and guessing from its shape would be inventing a fact.
         */
        public boolean imported;

        /**
         * THIS SET WAS WRITTEN BY THE PLAN, not by you.
         *
         * The library's provenance filter offers Mine, Imported and From a run. A set the
         * trainer minted is none of those - it is not imported and no run produced it - so
         * every prescription filed itself under MINE, beside the sets built by hand, and
         * each new week added another "Work hold" to the pile.
         *
         * They are two different kinds of thing: one you wrote, one was written for you.
         * This flag is what lets the shelf say which, and it is stamped at the single place
         * a mint creates sets, so nothing else has to know.
         *
         * MIGRATION: false. A set saved before this existed is treated as yours, which is
         * what the app has always called it - the flag adds a category, it does not
         * retroactively move sets out of one.
         */
        public boolean fromPlan;

        /**
         * F15b - PER-REPETITION OVERRIDES: "the third rep goes deeper".
         *
         * A fixed set repeats one pull/drop cycle for its whole duration, and until now
         * every repetition of it was identical by construction - the set IS the cycle, and
         * the device repeats it. Wanting the fourth rep two kPa deeper meant splitting the
         * set into two sets and losing the fact that it was one thing.
         *
         * SPARSE, and that is the whole design. A rep with no entry here plays the set's
         * own values, so a set with no overrides is byte-for-byte the set it always was and
         * {@link #ladder} returns exactly the one preset it always returned. Nothing about
         * a set nobody has overridden changes.
         *
         * HOW IT REACHES THE PUMP, and the one thing it depends on: a set with overrides
         * expands to ONE PRESET PER REPETITION instead of one preset for the whole set, so
         * the device plays a short sequence rather than one long repeat. Whether those
         * adjacent presets play as one continuous set, or the device breaks between them,
         * is the SEAM QUESTION - a hardware fact this app cannot determine by reasoning.
         * That is why {@link Model#repOverridesEnabled} exists and is off until the seam
         * test has been run - it has not yet been run on hardware (release checklist H16):
         * shipping this on by default would risk a routine that plays
         * differently from the one on screen, which is the one failure a routine editor may
         * never have.
         *
         * MIGRATION: empty. No set saved before this existed has an overridden repetition,
         * and no shape of old data could be read as one.
         */
        public static final class Rep {
            /** Which repetition, counting from 1 as the editor and the run screen do. */
            public int rep;
            public int up, lo, uh, lh, sp;

            public JSONObject toJson() throws JSONException {
                JSONObject o = new JSONObject();
                o.put("rep", rep);
                o.put("up", up); o.put("lo", lo);
                o.put("uh", uh); o.put("lh", lh); o.put("sp", sp);
                return o;
            }

            public static Rep fromJson(JSONObject o) {
                Rep r = new Rep();
                r.rep = o.optInt("rep", 1);
                r.up = o.optInt("up", 20); r.lo = o.optInt("lo", 10);
                r.uh = o.optInt("uh", 30); r.lh = o.optInt("lh", 5);
                r.sp = o.optInt("sp", 75);
                return r;
            }
        }

        public final List<Rep> reps = new ArrayList<Rep>();

        /** How many repetitions this set has - the number the overrides are indexed
         *  against. A ramp and a rest have none: a ramp's repetition IS its step, and a
         *  rest repeats nothing. */
        public int repCount() {
            return ramp || rest ? 0 : Math.max(1, dur / cycle());
        }

        /** The override for one repetition, or null when that repetition is the set's own
         *  values. Counting from 1. */
        public Rep repAt(int rep) {
            for (int i = 0; i < reps.size(); i++)
                if (reps.get(i).rep == rep) return reps.get(i);
            return null;
        }

        /** Set or clear one repetition's override. Passing null clears it, which is how a
         *  rep goes back to being the set - not by writing the set's current values into
         *  it, which would freeze them against a later edit of the set itself. */
        public void setRep(int rep, Rep r) {
            for (int i = reps.size() - 1; i >= 0; i--)
                if (reps.get(i).rep == rep) reps.remove(i);
            if (r != null) { r.rep = rep; reps.add(r); }
        }

        /**
         * EACH REPETITION'S OWN LENGTH IN SECONDS, in order.
         *
         * Derived by the SAME rule {@link #ladder} uses to give an overridden repetition
         * its own duration - an override's hold plus its drop, or the set's own cycle when
         * it has none. That is deliberate and load-bearing: the chart's repetition bands
         * and the presets the pump is actually sent have to divide the set at the same
         * instants, or tapping a band would open a repetition other than the one drawn
         * there.
         *
         * Empty for a ramp and for a rest, which have no repetitions.
         */
        public int[] repDurSec() {
            int n = repCount();
            if (n <= 0) return new int[0];
            int[] out = new int[n];
            int cyc = cycle();
            for (int i = 1; i <= n; i++) {
                Rep ov = repAt(i);
                out[i - 1] = ov != null ? Math.max(1, ov.uh + ov.lh) : cyc;
            }
            return out;
        }

        /**
         * Which repetition is playing at `frac` of the way through the set, counting from
         * 1; 0 when this set has no repetitions to speak of.
         *
         * TIME, NOT PIXELS, and not the drawn wave either. {@link Waveform} caps how many
         * pulses it draws, so on a long set the shape under a finger is a SCHEMATIC of the
         * period rather than the repetition itself - but the x axis is still proportional
         * to time, so the repetition PLAYING at that point is exact. A caller highlighting
         * the band this returns is telling the truth; one highlighting a drawn pulse would
         * not be.
         */
        public int repAtFraction(double frac) {
            int[] durs = repDurSec();
            if (durs.length == 0) return 0;
            double total = 0;
            for (int i = 0; i < durs.length; i++) total += durs[i];
            if (total <= 0) return 0;
            double at = frac * total;
            double run = 0;
            for (int i = 0; i < durs.length; i++) {
                run += durs[i];
                if (at < run) return i + 1;
            }
            return durs.length;      // exactly at (or past) the end is the last repetition
        }

        /** The {start, end} fractions one repetition occupies, or null when there is no
         *  such repetition. The inverse of {@link #repAtFraction}, from the same array. */
        public double[] repBand(int rep) {
            int[] durs = repDurSec();
            if (rep < 1 || rep > durs.length) return null;
            double total = 0;
            for (int i = 0; i < durs.length; i++) total += durs[i];
            if (total <= 0) return null;
            double lo = 0;
            for (int i = 0; i < rep - 1; i++) lo += durs[i];
            return new double[]{ lo / total, (lo + durs[rep - 1]) / total };
        }

        /** Drop overrides for repetitions this set no longer has. Called after any edit
         *  that can change repCount - an override on rep 7 of a set that now has four is
         *  not an instruction, it is a leftover, and leaving it would make the set play
         *  differently the moment the duration went back up. */
        public void trimReps() {
            int n = repCount();
            for (int i = reps.size() - 1; i >= 0; i--)
                if (reps.get(i).rep < 1 || reps.get(i).rep > n) reps.remove(i);
        }

        /** How long an upper hold may be. An hour: past that a "hold" is a session, and the
         *  editor's own duration field is the honest place to say so. */
        public static final int HOLD_MAX_SEC = 3600;

        /** True when this set's upper hold is longer than one preset can carry, so
         *  {@link #ladder} has to stitch it out of several. */
        public boolean stitchedHold() {
            return !ramp && !rest && uh > Proto.WIRE_HOLD_MAX;
        }

        /**
         * ONE REPETITION, as the presets the pump is actually sent.
         *
         * A hold within the wire's reach is one preset and the device repeats it. A longer
         * one becomes: as many FULL chunks as it takes, each planned with its two setpoints
         * equal (it holds, it does not drop), and then a final preset carrying whatever hold
         * is left plus the drop. ON THE WIRE a chunk is NOT equal: the routine's upload keeps
         * every lower setpoint strictly below its upper (RunEdit#clampLower), so a chunk goes
         * out as the pull for 255 s, then 1 kPa below it for 1 s - not the standardisation
         * hold's equal shape. The app starts the next chunk when the 255 s are up.
         *
         * The app's own clock decides when each chunk ends, exactly as it decides when any
         * preset ends. Nothing here depends on the device's timing.
         */
        private void stitchOne(List<Preset> out, String lbl) {
            stitchVals(out, lbl, up, lo, uh, lh, sp);
        }

        /** The stitch itself, on explicit values, so an overridden repetition (wave 2)
         *  stitches its OWN figures through exactly the same seams a plain one does. */
        private static void stitchVals(List<Preset> out, String lbl,
                                       int vUp, int vLo, int vUh, int vLh, int vSp) {
            int rem = Math.max(1, vUh);
            while (rem > Proto.WIRE_HOLD_MAX) {
                Preset c = new Preset();
                c.up = vUp; c.lo = vUp;        // planned equal: hold, do not drop (sent as
                                               // vUp and vUp - 1: RunEdit#clampLower)
                c.uh = Proto.WIRE_HOLD_MAX; c.lh = 1; c.sp = vSp;
                c.durMs = (long) Proto.WIRE_HOLD_MAX * 1000L;
                c.label = lbl;
                // PART OF A REPETITION, not one of its own. The final preset below carries
                // the repetition's drop and is the one that ends it.
                c.cyclePart = true;
                out.add(c);
                rem -= Proto.WIRE_HOLD_MAX;
            }
            Preset last = new Preset();
            last.up = vUp; last.lo = RunEdit.dropKept(vLo, vUp, vLh <= 0);   // the gap (I5)
            last.uh = rem; last.lh = vLh; last.sp = vSp;
            last.durMs = (long) (rem + Math.max(0, vLh)) * 1000L;
            last.label = lbl;
            out.add(last);
        }

        public Set() { }

        public static Set fixed(String id, String name,
                                int up, int lo, int uh, int lh, int sp, int dur) {
            Set s = new Set();
            s.id = id; s.name = name; s.ramp = false;
            s.up = up; s.lo = lo; s.uh = uh; s.lh = lh; s.sp = sp; s.dur = dur;
            s.up2 = up; s.lo2 = lo; s.sp2 = sp; s.steps = 4;
            s.uh2 = uh; s.lh2 = lh;
            return s;
        }

        public static Set ramp(String id, String name,
                               int up, int lo, int uh, int lh, int sp,
                               int up2, int lo2, int uh2, int lh2, int sp2, int steps, int dur) {
            Set s = fixed(id, name, up, lo, uh, lh, sp, dur);
            s.ramp = true; s.up2 = up2; s.lo2 = lo2; s.sp2 = sp2; s.steps = steps;
            s.uh2 = uh2; s.lh2 = lh2;
            return s;
        }

        /**
         * A REST entry: a name, a duration, and nothing else that means anything. Every
         * wire value is zero — not "low", ZERO — because a rest commands nothing at all,
         * and a set that carried a leftover 20 kPa in a field the editor no longer shows
         * would be one refactor away from sending it.
         */
        public static Set restOf(String id, String name, int dur) {
            Set s = new Set();
            s.id = id; s.name = name; s.rest = true; s.ramp = false;
            s.up = 0; s.lo = 0; s.uh = 0; s.lh = 0; s.sp = 0; s.dur = dur;
            s.up2 = 0; s.lo2 = 0; s.sp2 = 0; s.uh2 = 0; s.lh2 = 0; s.steps = 4;
            return s;
        }

        /** Defaults for a brand-new set, respecting the current ceiling — mirrors the
         *  prototype's newSet(). Starts in fixed mode but with ramp-end values already
         *  populated, so flipping to ramp later (setMode) has something sane to show. */
        public static Set fresh(String id, int ceilKpa) {
            Set s = fixed(id, "New set", Math.min(20, ceilKpa), Math.min(10, ceilKpa),
                          30, 5, 75, 120);
            s.up2 = Math.min(32, ceilKpa); s.lo2 = s.lo; s.sp2 = 95; s.steps = 4;
            s.clamp(ceilKpa);
            return s;
        }

        /** An independent copy under a new id — mirrors the prototype's dupSet(). Not a
         *  reference: editing the copy must never move the original, or vice versa. */
        public Set copy(String newId) {
            Set c = new Set();
            c.id = newId; c.name = name + " copy"; c.ramp = ramp; c.rest = rest;
            c.up = up; c.lo = lo; c.uh = uh; c.lh = lh; c.sp = sp; c.dur = dur;
            c.up2 = up2; c.lo2 = lo2; c.sp2 = sp2; c.steps = steps;
            c.uh2 = uh2; c.lh2 = lh2;
            // fromRun is NOT copied — see the field's own note. A copy is a new set, and
            // claiming it came out of a run it was never part of would put a false
            // provenance line on a library row.
            c.fromRun = 0L;
            // The MARK is carried \u2014 a mark is how this person groups their own work,
            // and a duplicate is meant to sit in the same group as the thing it came from.
            // `imported` is not, for the same reason fromRun is not: a copy is a set this
            // person made.
            c.mark = mark;
            c.imported = false;
            // The overrides are part of what the set IS, so a duplicate has them.
            for (int i = 0; i < reps.size(); i++) {
                Rep src = reps.get(i);
                Rep r = new Rep();
                r.rep = src.rep; r.up = src.up; r.lo = src.lo;
                r.uh = src.uh; r.lh = src.lh; r.sp = src.sp;
                c.reps.add(r);
            }
            return c;
        }

        /** THE STEP THIS APP WILL EVER SUGGEST ON ITS OWN (T1 A, "Ready to progress?") —
         *  a fixed, small, non-negotiable kPa increase, never a percentage of the set's
         *  own depth (which would suggest more at a high target and less at a low one,
         *  for no principled reason) and never a value any screen exposes as a dial. See
         *  {@link Routine#stepUpCopy}'s own doc for the reasoning behind offering a
         *  fixed step at all, and {@link Progression}'s own class doc for the
         *  consistency-streak trigger this feeds — kept in its own file, deliberately
         *  apart from Milestones.java, for the reasoning stated there. */
        public static final int STEP_UP_KPA = 2;

        /**
         * An independent copy under `newId` (via {@link #copy}) with its PULL targets —
         * {@link #up} and {@link #up2}, the "Pull to X" values {@link #say} prints, the
         * peak each stage of the ladder actually reaches — raised by {@link
         * #STEP_UP_KPA}, then reclamped to `ceilKpa` via {@link #clamp} — the EXACT SAME
         * METHOD, called with the EXACT SAME argument, the set editor itself calls on
         * every hand-typed edit. A bumped value can therefore never land anywhere a
         * human editing this set by hand could not also have reached; on a set already
         * sitting at the ceiling, the bump is silently absorbed back down to the ceiling
         * by that same clamp() call rather than pushed past it — SelfTest pins exactly
         * this case.
         *
         * {@link #lo}/{@link #lo2} (the drop-to targets), {@link #uh}/{@link #lh}
         * (hold/drop timing) and {@link #sp} (power) are left untouched — see {@link
         * Routine#stepUpCopy}'s own doc for why "pull/pressure fields" is read here as
         * the peak targets specifically, not the whole set.
         *
         * A REST set ({@link #rest}) comes back unchanged beyond the ordinary {@link
         * #copy}: {@link #clamp} already zeroes every commanded field on a rest, and a
         * rest has nothing to pull deeper — bumping it would quietly turn "vented" into
         * a pressure.
         */
        public Set stepUp(String newId, int ceilKpa) {
            Set c = copy(newId);
            if (!c.rest) {
                c.up += STEP_UP_KPA;
                c.up2 += STEP_UP_KPA;
            }
            c.clamp(ceilKpa);
            return c;
        }

        /** A one-line plain-language summary for the library row — mirrors the
         *  prototype's setLine(). Words, not a field dump. */
        public String line() {
            // A rest has one fact about it. Printing the pressure fields here would show
            // "hold 0.0 kPa" on a row that commands nothing at all.
            if (rest) return "rest — vented, for " + Fmt.t(dur);
            // Wave 3a (D4): no invented drop clause. lh == 0 has no drop phase at all
            // (the "drop to −2.1 for 0:00" the clamp's lo=up−1 filler used to print);
            // lo == 0 is a RELEASE, said as one.
            if (!ramp) {
                String drop = lh <= 0 ? ""
                    : lo == 0 ? ", release for " + Fmt.t(lh)
                              : ", drop to " + Fmt.p(lo) + " for " + Fmt.t(lh);
                return "hold " + Fmt.p(up) + " for " + Fmt.t(uh) + drop
                     + "  ·  " + sp + "%  ·  runs " + Fmt.t(dur);
            }
            return "ramp " + Fmt.p(up) + " → " + Fmt.p(endUp()) + "  ·  " + sp + "% → "
                 + endSp() + "%  ·  " + steps + " steps  ·  runs " + Fmt.t(dur);
        }

        /** The longer "what this set does" explanation shown in the editor — mirrors
         *  the prototype's setSay(). Pure text; the caller decides how/whether to warn
         *  about the ceiling on top of this. */
        public String say() {
            if (rest)
                return "Rest for " + Fmt.t(dur) + ". The cuff is vented first and nothing is "
                     + "commanded — the run simply waits here before the next set.";
            String cyc = "hold " + Fmt.t(uh) + " at the target, drop for " + Fmt.t(lh);
            if (!ramp)
                return "Pull to " + Fmt.p(up) + " and " + cyc + ", repeat — for " + Fmt.t(dur)
                     + " total.";
            List<String> mv = new ArrayList<String>();
            if (endUp() != up) mv.add("target " + Fmt.p(up) + " → " + Fmt.p(endUp()));
            if (endLo() != lo) mv.add("drop " + Fmt.p(lo) + " → " + Fmt.p(endLo()));
            if (endUh() != uh) mv.add("hold " + Fmt.t(uh) + " → " + Fmt.t(endUh()));
            if (endLh() != lh) mv.add("drop time " + Fmt.t(lh) + " → " + Fmt.t(endLh()));
            if (endSp() != sp) mv.add("power " + sp + "% → " + endSp() + "%");
            if (mv.isEmpty()) mv.add("nothing moves — set an end value");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mv.size(); i++) { if (i > 0) sb.append(", "); sb.append(mv.get(i)); }
            boolean timeMoves = endUh() != uh || endLh() != lh;
            return "Over " + Fmt.t(dur) + ", walk " + sb
                 + (timeMoves ? ", starting from " : ", while it continues to ") + cyc + ".";
        }

        public int endUp() { return ramp ? up2 : up; }
        public int endLo() { return ramp ? lo2 : lo; }
        public int endSp() { return ramp ? sp2 : sp; }
        public int endUh() { return ramp ? uh2 : uh; }
        public int endLh() { return ramp ? lh2 : lh; }
        public int cycle() { return Math.max(1, uh + lh); }

        /** The presets this set actually sends. A fixed set is one; a ramp is `steps`. */
        public List<Preset> ladder() {
            List<Preset> out = new ArrayList<Preset>();
            // A REST IS ONE PRESET THAT COMMANDS NOTHING. It still occupies a plan index —
            // that is what keeps the run's index arithmetic (batches, skip, the elapsed-to-
            // index mapping) completely unchanged by the existence of rests. What makes it
            // a rest rather than a preset at zero pressure is the `rest` mark: playPreset
            // reads it and arms nothing at all, so the zeros below are never written to any
            // slot and are simply what an unsent preset holds.
            if (rest) {
                Preset p = new Preset();
                p.up = 0; p.lo = 0; p.sp = 0; p.uh = 0; p.lh = 0;
                p.rest = true;
                p.durMs = Math.max(1000L, (long) dur * 1000L);
                p.label = name;
                out.add(p);
                return out;
            }
            /* F15b - ONE PRESET PER REPETITION, but ONLY when a repetition actually
             * differs. The guard is `reps.isEmpty()`, not a setting, and that is
             * deliberate: a set with no overrides must produce byte-for-byte the ladder it
             * always produced, whatever any switch elsewhere says, or every existing
             * routine's plan changes shape the day a preference is flipped. The switch
             * gates whether overrides can be CREATED; this gates what a set does once it
             * has them. */
            /* A STITCHED HOLD EXPANDS PER REPETITION, exactly as an overridden one does,
             * and for the same reason: once a set is more than one preset the app has to
             * play the repetitions itself rather than leaving the device to repeat one. The
             * two paths meet here so there is one expansion rather than two that could
             * disagree about how long a repetition is. */
            if (!ramp && !rest && reps.isEmpty() && stitchedHold()) {
                int repN = repCount();
                for (int i = 1; i <= repN; i++)
                    stitchOne(out, repN == 1 ? name : name + "  rep " + i + "/" + repN);
                return out;
            }
            if (!ramp && !reps.isEmpty()) {
                int repN = repCount();
                long cycMs = (long) cycle() * 1000L;
                for (int i = 1; i <= repN; i++) {
                    Rep ov = repAt(i);
                    int rUp = ov != null ? ov.up : up;
                    int rLo = ov != null ? ov.lo : lo;
                    int rUh = ov != null ? ov.uh : uh;
                    int rLh = ov != null ? ov.lh : lh;
                    int rSp = ov != null ? ov.sp : sp;
                    String lbl = name + "  rep " + i + "/" + repN;
                    /* WAVE 2: a long hold survives gaining its first override. A
                     * repetition whose hold exceeds the wire stitches, exactly as the
                     * whole set would with no overrides — adding one rep override used
                     * to silently cut a 600 s hold to 255 on the wire. */
                    if (rUh > Proto.WIRE_HOLD_MAX) {
                        stitchVals(out, lbl, rUp, rLo, rUh, rLh, rSp);
                        continue;
                    }
                    Preset p = new Preset();
                    // A repetition's own drop keeps the gap under its own pull (I5).
                    p.up = rUp; p.lo = RunEdit.dropKept(rLo, rUp, rLh <= 0);
                    p.uh = rUh; p.lh = rLh; p.sp = rSp;
                    // Each preset is ITS OWN repetition long, so an overridden rep with a
                    // different hold/drop takes the time it actually takes rather than
                    // being squeezed into the unoverridden cycle's length.
                    long own = ov != null ? (long) Math.max(1, ov.uh + ov.lh) * 1000L : cycMs;
                    p.durMs = Math.max(1000L, own);
                    p.label = lbl;
                    out.add(p);
                }
                return out;
            }
            int n = ramp ? rampSteps() : 1;
            /* EVERY RAMP STEP IS WHOLE CYCLES OF ITS OWN HOLD AND DROP, AND AT LEAST ONE.
             *
             * A step has no length on the wire: the pump repeats the step's hold and drop
             * until the app STARTs the next slot, at the step's durMs. This used to be
             * dur / n in milliseconds, which is almost never whole cycles, so a step ended
             * part-way through a hold and the pump went straight on to the next step's pull
             * with no drop between them. Worse, nothing stopped a step being SHORTER than one
             * cycle - nine 1:40 steps of a 4:10 hold never reached the drop at all: fifteen
             * minutes of pull climbing -8.0 to -10.3 inHg with no release, while the editor
             * said "drop for 0:05" (owner report, 0.10; the trainer fixed the same thing for
             * its own ramps as audit D6). Now every step runs {@link #rampCycles} whole cycles
             * of (its hold + its drop), so it always ends at the end of a drop, and the next
             * step's pull begins from a released cuff. This holds for any set that reaches a
             * plan - saved, imported, manual or written by the plan - whatever its `dur`
             * says; Set#clamp snaps `dur` to the same figure so the two never disagree. */
            int k = ramp ? rampCycles() : 0;
            for (int i = 0; i < n; i++) {
                double f = (n == 1) ? 0 : (double) i / (n - 1);
                Preset p = new Preset();
                p.up = (int) Math.round(up + (endUp() - up) * f);
                p.lo = (int) Math.round(lo + (endLo() - lo) * f);
                p.sp = (int) Math.round(sp + (endSp() - sp) * f);
                if (ramp) {
                    // Same backstop on the ramp's own steps: a ramp never stitches - and the
                    // drop is held to the wire too, so the cycle the step is timed in whole
                    // cycles of is the cycle the pump actually runs.
                    p.uh = stepUh(i, n);
                    p.lh = stepLh(i, n);
                    p.durMs = Math.max(1000L, (long) k * stepCycleSec(i, n) * 1000L);
                } else {
                    p.uh = Math.min((int) Math.round(uh + (endUh() - uh) * f),
                                    Proto.WIRE_HOLD_MAX);
                    p.lh = (int) Math.round(lh + (endLh() - lh) * f);
                    // One preset, the whole duration.
                    p.durMs = Math.max(1000L, (long) dur * 1000L);
                }
                // AN INTERPOLATED STEP KEEPS THE GAP TOO (RunEdit#dropKept, review I5): the
                // ends do (#clamp), but a ramp from a drop at the floor to one above it can
                // round a middle step's drop within 1.0 inHg of its pull.
                p.lo = RunEdit.dropKept(p.lo, p.up, p.lh <= 0);
                p.label = ramp ? (name + " " + (i + 1) + "/" + n) : name;
                out.add(p);
            }
            return out;
        }

        /* ================================================ A RAMP IN WHOLE CYCLES (0.10) ==
         *
         * ONE RULE for how long a ramp's steps run, read by the ladder, by Set#clamp, by both
         * set editors and by the run screen's strip and Coming steps:
         *   - every step runs the SAME number of whole cycles, k, of its own hold + drop
         *     (a ramp that walks its timing has longer steps where its cycle is longer);
         *   - k is dur / (one cycle of every step), FLOORED, at least 1 - so a step is never
         *     shorter than one cycle and never ends inside a hold. Floored for the reason a
         *     fixed set's duration is (Model#snapDurToCycle): a person who asked for 8:42
         *     asked for at most that much time under pressure, never more - except that one
         *     cycle a step is the least a ramp can be, because a step shorter than its cycle
         *     never reaches its drop;
         *   - no step runs past an even share of the hour a set may run
         *     ({@link #rampStepMaxSec}) - the app-clock bound the strip and Coming steps hold
         *     "Time per step" to, so every ramp an editor can build is inside their range;
         *   - a ramp whose longest single cycle would not fit that share plays fewer steps
         *     ({@link #rampSteps}) rather than a step shorter than its cycle.
         * Idempotent: a `dur` already snapped (k whole passes) snaps to itself. */

        /** The longest a ramp may run, and a set's shortest: Set#clamp's own bounds. */
        public static final int RAMP_MAX_SEC = 3600;
        public static final int DUR_MIN_SEC = 30;

        /** THE LONGEST ONE STEP OF AN n-STEP RAMP RUNS: its even share of the hour. The
         *  strip's and Coming steps' "Time per step" bound - the app's own clock, not the
         *  wire's 255 s, which is the width of one hold or one drop, never of a step. */
        public static int rampStepMaxSec(int n) {
            return RAMP_MAX_SEC / Math.max(2, n);
        }

        /** Step i of n's hold as the ladder sends it: interpolated, held to the wire. */
        public int stepUh(int i, int n) {
            double f = n <= 1 ? 0 : (double) i / (n - 1);
            return Math.max(0, Math.min((int) Math.round(uh + (endUh() - uh) * f),
                                        Proto.WIRE_HOLD_MAX));
        }

        /** Step i of n's drop time as the ladder sends it: interpolated, held to the wire. */
        public int stepLh(int i, int n) {
            double f = n <= 1 ? 0 : (double) i / (n - 1);
            return Math.max(0, Math.min((int) Math.round(lh + (endLh() - lh) * f),
                                        Proto.WIRE_HOLD_MAX));
        }

        /** One cycle of step i of n - its hold plus its drop, at least a second. */
        public int stepCycleSec(int i, int n) {
            return Math.max(1, stepUh(i, n) + stepLh(i, n));
        }

        /** The longest single cycle of an n-step ladder. */
        public int longestCycleSec(int n) {
            int most = 1;
            for (int i = 0; i < n; i++) most = Math.max(most, stepCycleSec(i, n));
            return most;
        }

        /** One cycle of every step of an n-step ladder, end to end. */
        public int passSec(int n) {
            int t = 0;
            for (int i = 0; i < n; i++) t += stepCycleSec(i, n);
            return Math.max(1, t);
        }

        /** THE STEPS A RAMP PLAYS: `steps` held to 2..the pump's table, and fewer when one
         *  cycle of its longest step would not fit a step's share of the hour. */
        public int rampSteps() {
            int n = Math.max(2, Math.min(Proto.SLOTS, steps));
            while (n > 2 && longestCycleSec(n) > rampStepMaxSec(n)) n--;
            return n;
        }

        /** The whole cycles each step of this ramp runs for a duration of `durSec`: as many
         *  whole passes (one cycle of every step) as fit it, at least one, never a step past
         *  its share of the hour, and 30 s at the least where that allows. */
        public int rampCyclesFor(int durSec) {
            int n = rampSteps();
            int pass = passSec(n);
            int cap = Math.max(1, rampStepMaxSec(n) / longestCycleSec(n));
            long k = Math.max(0, durSec) / pass;
            if (k > cap) k = cap;
            if (k < 1) k = 1;
            while (k * pass < DUR_MIN_SEC && k < cap) k++;
            return (int) k;
        }

        /** The whole cycles each step of this ramp runs (its `dur`, in whole cycles). */
        public int rampCycles() { return rampCyclesFor(dur); }

        /** How long this ramp really runs: k whole cycles of every step. */
        public int rampDurSec() { return rampCycles() * passSec(rampSteps()); }

        /** A ramp's duration one whole cycle a step longer (d = +1) or shorter (d = -1) -
         *  the editors' duration stepper. Never under one cycle a step. */
        public int rampDurStepped(int d) {
            int k = Math.max(1, rampCycles() + d);
            return k * passSec(rampSteps());
        }

        /** How many pull/drop cycles this set runs: a ramp's steps times its cycles a step,
         *  a fixed set's {@link Manual#cycles}. */
        public int cycleCount() {
            if (ramp) return rampSteps() * rampCycles();
            return Manual.cycles(uh, lh, dur);
        }

        /** "2 steps × 6 cycles (4:12 per step)" - what both ramp editors show, so how the
         *  duration divides is said before the run, not found during it. A ramp that walks
         *  its timing has a shortest and a longest step: "(3:30–4:12 per step)". */
        public String rampSaid() {
            int n = rampSteps(), k = rampCycles();
            int lo = Integer.MAX_VALUE, hi = 0;
            for (int i = 0; i < n; i++) {
                int t = k * stepCycleSec(i, n);
                lo = Math.min(lo, t); hi = Math.max(hi, t);
            }
            return n + " steps × " + k + (k == 1 ? " cycle (" : " cycles (")
                + (lo == hi ? Fmt.t(hi) : Fmt.t(lo) + "–" + Fmt.t(hi)) + " per step)";
        }

        /**
         * Keep the set physically sane and inside the ceiling.
         *
         * The upper setpoints floor at 1, not 0. At 0 the `lo < up` re-assertion below
         * degenerates to 0/0 — the invariant every other part of this app relies on,
         * broken at the bottom of the range — and the set editor has always PRINTED a
         * floor of 1 while enforcing 0, which is the "a printed range must be the range
         * actually enforced" rule failing in the other direction.
         *
         * The duration floor is 30, matching the 30-second step the editor moves in. It
         * was 10, which the UI could not even reach: from 30, one tap down lands on 30
         * (60-30), never on 10, so the floor described a value no user could produce.
         */
        /**
         * THE LONGEST PULL THIS SET CAN ACTUALLY DELIVER.
         *
         * An hour where {@link #ladder} stitches - a plain fixed set - and one wire byte
         * where it does not. A ramp's steps and the repetitions of a set carrying overrides
         * are both emitted raw, so a hold beyond the field is not a long hold there, it is a
         * number the device never sees.
         */
        /** The longest upper hold this SHAPE can deliver. A fixed set — with or without
         *  per-repetition overrides, since wave 2 stitches both — reaches an hour through
         *  {@link #ladder}'s stitching. A RAMP stays at the wire's 255: a ramp step is sent
         *  as one preset, its hold raw in one byte, and never stitches. (Its step runs whole
         *  cycles of that hold and its drop - see {@link #rampCycles}.) */
        public int holdCapSec() {
            return ramp ? Proto.WIRE_HOLD_MAX : HOLD_MAX_SEC;
        }

        public void clamp(int ceilKpa) {
            // A REST HAS ONE NUMBER. The clamps below exist to keep COMMANDED values sane
            // and inside the ceiling; a rest commands nothing, and running them would floor
            // its upper setpoint at 1 kPa — quietly turning "vented" into a pressure. Its
            // duration is bounded by exactly the same rule every other set's is.
            if (rest) {
                up = 0; lo = 0; uh = 0; lh = 0; sp = 0;
                up2 = 0; lo2 = 0; uh2 = 0; lh2 = 0; sp2 = 0;
                ramp = false;
                /* THIRTY SECONDS IS A FLOOR FOR A REST SOMEBODY TYPED, NOT FOR ONE THE PLAN
                 * WROTE.
                 *
                 * It is there because a hand-entered ten-second rest is almost always a slip
                 * of the stepper. But the traction fatigue block prescribes a gap of
                 * Mint#TRACTION_FATIGUE_REST_SEC - ten seconds, from the spec's own "rest
                 * 5-10 s" - and this floor tripled every one of them: ten sixty-second holds
                 * came out with half-minute gaps, three minutes longer than prescribed, and
                 * the block the plan scores was not the block that ran.
                 *
                 * A plan-written rest carries its own figure. Bounded still, at one second,
                 * because a zero-length rest is not a rest and nothing should be able to
                 * write one. */
                dur = fromPlan ? c(dur, 1, 3600) : c(dur, 30, 3600);
                return;
            }
            int cap = Math.min(57, ceilKpa);
            up  = c(up,  1, Math.max(1, cap));
            lo  = c(lo,  0, cap);
            up2 = c(up2, 1, Math.max(1, cap));
            lo2 = c(lo2, 0, cap);
            if (lo  >= up)  lo  = Math.max(0, up  - 1);
            if (lo2 >= up2) lo2 = Math.max(0, up2 - 1);
            /* THE UPPER HOLD IS NO LONGER BOUNDED BY THE WIRE.
             *
             * 255 was never a decision about training - it is the width of one byte in
             * Proto#addPreset, and it had leaked out of the protocol and into what a set is
             * allowed to BE. A five-minute hold is an ordinary thing to want and could not
             * be expressed.
             *
             * It is expressible now because {@link #ladder} STITCHES one: a hold longer than
             * the wire allows becomes several presets at the same pull, played back to
             * back. Whether the device plays adjacent presets as one continuous set is the
             * seam question - the same fact per-repetition overrides are built on - and the
             * seam test has NOT yet been run on hardware (release checklist H16). Stitched
             * holds are on regardless, so every hold past 255 s crosses that unproven seam.
             *
             * THE DROP HOLD IS STILL 255, deliberately. Stitching a long hold at the UPPER
             * setpoint sends each chunk as the pull and 1 kPa below it (see stitchVals), the
             * shape every routine preset already has on the wire - whereas stitching a long
             * hold at the LOWER one would need the device to release DOWN to a target, and
             * what it does then is not established. An unproven shape is not worth a feature
             * nobody has asked for. */
            /* ...BUT ONLY FOR THE SHAPE THAT ACTUALLY STITCHES.
             *
             * The paragraph above is right about every FIXED set — wave 2 stitches a
             * repetition of an overridden set too — and wrong about a RAMP. A ramp step
             * emits its hold raw, and Proto#addPreset packs it into one byte, clamping 600
             * to 255 without a word. The pump then released at 4:15 and began a fresh cycle
             * while the run screen was still counting the pull, on a person, with nothing
             * anywhere reporting the discrepancy.
             *
             * A hold longer than the wire is expressible where it can be DELIVERED, and
             * nowhere else. */
            int uhCap = holdCapSec();
            uh = c(uh, 0, uhCap); lh = c(lh, 0, Proto.WIRE_HOLD_MAX);
            uh2 = c(uh2, 0, uhCap); lh2 = c(lh2, 0, Proto.WIRE_HOLD_MAX);
            /* A DROP ABOVE THE FLOOR STAYS 1.0 inHg UNDER ITS PULL here too (RunEdit#dropKept,
             * review I5): every set saved, imported, typed or written by the plan passes here,
             * and so does a lowered ceiling (Model#clampAll) - which left a 30/26 routine at
             * 28/26. A drop at or under the floor keeps the rule above alone; a set that only
             * holds (no drop time) keeps its filler. */
            lo = RunEdit.dropKept(lo, up, lh <= 0);
            lo2 = RunEdit.dropKept(lo2, up2, lh2 <= 0);
            sp = c(sp, 5, 100); sp2 = c(sp2, 5, 100);
            steps = c(steps, 2, Proto.SLOTS);
            dur = c(dur, DUR_MIN_SEC, RAMP_MAX_SEC);
            /* A RAMP IS WHOLE CYCLES A STEP (see #rampCycles): its steps are the steps it
             * plays, and its duration is what those steps run - so the editor's row, the
             * library line, the routine's length and the pump all say one figure. Every set
             * saved, imported or typed is corrected here, on load as on every edit.
             *
             * A PLAN-WRITTEN RAMP KEEPS ITS DURATION HERE, as a plan-written rest keeps its
             * own figure: the trainer writes whole cycles by construction, and a climb
             * longer than the pump's table briefly carries more cycles than steps until
             * RxBuild#topStep splits it (audit D6) - snapping it first would lose those
             * cycles. Its ladder is whole cycles all the same, and Model#clampAll snaps
             * every ramp on load, plan-written or not. */
            if (ramp) {
                steps = rampSteps();
                if (!fromPlan) dur = rampDurSec();
            }
        }

        /** THE WHOLE-CYCLE SNAP ON ITS OWN, for any ramp - clampAll's, on load. */
        public void snapRamp() {
            if (!ramp || rest) return;
            steps = rampSteps();
            dur = rampDurSec();
        }

        private static int c(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("ramp", ramp); o.put("star", star);
            o.put("rest", rest);
            o.put("up", up); o.put("lo", lo); o.put("uh", uh); o.put("lh", lh);
            o.put("sp", sp); o.put("dur", dur);
            o.put("up2", up2); o.put("lo2", lo2); o.put("sp2", sp2); o.put("steps", steps);
            o.put("uh2", uh2); o.put("lh2", lh2);
            // Through a string, like every other long here: the desktop org.json shim has
            // no optLong, and forking the two readers is how a field ends up meaning one
            // thing on the phone and another in the test.
            o.put("fromRun", String.valueOf(fromRun));
            o.put("mark", mark);
            o.put("imported", imported);
            o.put("fromPlan", fromPlan);
            // F15b - an ARRAY, not an object keyed by rep number: the desktop org.json
            // shim exposes optJSONArray but not optJSONObject, and one shape both readers
            // decode with the same code is the rule this file already follows for `assess`.
            JSONArray repsArr = new JSONArray();
            for (int i = 0; i < reps.size(); i++) repsArr.put(reps.get(i).toJson());
            o.put("reps", repsArr);
            return o;
        }

        public static Set fromJson(JSONObject o) {
            Set s = new Set();
            s.id = o.optString("id"); s.name = o.optString("name", "Set");
            s.ramp = o.optBoolean("ramp");
            s.star = o.optBoolean("star", false);
            // MIGRATION: absent in every set saved before rests existed — false, which is
            // what every one of them is. There is no shape of old data that could have
            // meant "rest", so there is nothing here to infer.
            s.rest = o.optBoolean("rest", false);
            s.up = o.optInt("up", 20); s.lo = o.optInt("lo", 10);
            s.uh = o.optInt("uh", 30); s.lh = o.optInt("lh", 5);
            s.sp = o.optInt("sp", 75); s.dur = o.optInt("dur", 120);
            s.up2 = o.optInt("up2", s.up); s.lo2 = o.optInt("lo2", s.lo);
            s.sp2 = o.optInt("sp2", s.sp); s.steps = o.optInt("steps", 4);
            // THE MIGRATION. A set saved before hold/drop could ramp has no "uh2"/"lh2"
            // key; defaulting each to its own START value makes end == start, so
            // ladder() interpolates a constant and every such set plays byte-identically
            // to what it played before. A missing value is not a new behaviour.
            s.uh2 = o.optInt("uh2", s.uh); s.lh2 = o.optInt("lh2", s.lh);
            // MIGRATION: absent on every set written before the save-what-you-ran card
            // existed. 0 means "a person built this", which is exactly what those sets are.
            s.fromRun = parseLong(o.optString("fromRun", "0"));
            // MIGRATION: 0 / false. See both fields' own notes \u2014 neither can be
            // inferred from an old set's shape, and both defaults are the truth about it.
            s.mark = markOf(o.optInt("mark", 0));
            s.imported = o.optBoolean("imported", false);
            // MIGRATION: false - a set from before this existed is yours, as it always was.
            s.fromPlan = o.optBoolean("fromPlan", false);
            // MIGRATION: absent means empty means "every repetition is the set", which is
            // what every set saved before this existed is.
            JSONArray repsArr = o.optJSONArray("reps");
            if (repsArr != null)
                for (int i = 0; i < repsArr.length(); i++) {
                    JSONObject ro = repsArr.optJSONObject(i);
                    if (ro != null) s.reps.add(Rep.fromJson(ro));
                }
            s.trimReps();
            return s;
        }
    }

    /** How many colour marks there are, not counting "none". Six, because
     *  {@link Look#BLOCKS} is six. */
    public static final int MARKS = 6;

    /** What each mark is called, in {@link Look#BLOCKS} order, for the swatch row's
     *  content description \u2014 a colour with no name is unreachable to anyone using a
     *  screen reader, and "swatch 4" is not a name. Index 0 is "no mark". */
    public static final String[] MARK_NAMES = {
        "No mark", "Blue", "Pink", "Sky", "Orchid", "Periwinkle", "Ice"
    };

    /** Clamp anything to a real mark. A stored value outside 0..MARKS is not a mark this
     *  build knows \u2014 an older or newer file, or a hand-edited one \u2014 and the honest
     *  reading of "I do not know what colour that is" is "unmarked", never a wrong colour. */
    public static int markOf(int v) { return v < 0 || v > MARKS ? 0 : v; }

    /** One preset as it goes on the wire, plus how long to play it. */
    public static final class Preset {
        public int up, lo, uh, lh, sp;
        public long durMs;
        public String label;

        /**
         * TRUE ON A PRESET THAT IS ONLY PART OF A REPETITION - never the whole of one.
         *
         * A hold longer than the wire allows is STITCHED into several presets (see
         * {@link Set#stitchOne}): as many full 255 s chunks as it takes, then a final one
         * carrying the remainder and the drop. The chunks are the SAME repetition, cut up
         * so the device can be told about it.
         *
         * Everything that counts cycles counted presets, so a 5-minute hold - which is what
         * the traditional girth track and length L-B both prescribe - was reported as TWO
         * cycles per repetition, and a 12-minute one as four. The summary's "cycles X of Y",
         * Today's cycle count and the drift check all read the same wrong number.
         *
         * Counting is not the only reader that needs this, which is why it is a fact ON the
         * preset rather than a rule inside one counter: a stitch chunk is not a cycle
         * boundary for any purpose.
         *
         * TRANSIENT, like every other field here - a Preset is never persisted.
         */
        public boolean cyclePart;
        /**
         * THIS PRESET WAS BUILT WITHOUT A DROP (RunEdit#holdOnly on the figures it was built
         * with): the prime, a routine's hold set, a stitch chunk. Stamped by
         * {@link Model#plan} through RunEdit#asBuilt, and for a recounted ramp step by
         * RunEdit#resizeRemaining - and by NOTHING that edits a preset's figures.
         *
         * The run's plan is not the design once an edit has written into it: "This set" on a
         * ramp shifts the remaining steps (SetShift), and the Reshape and the step count
         * rewrite them. Reading the lock from those figures turned a drop time dialled to
         * 0 s into "hold only" on the steps after it, with no control left to raise it
         * (reported from a device on the 0.9.0 review build). The drop controls lock on
         * this (RunEdit#dropLocked) instead. TRANSIENT, like every other field here.
         */
        public boolean builtHoldOnly;
        /**
         * THE HIGHEST DROP THIS STEP MAY EVER BE WRITTEN WITH (kPa): the drop floor
         * (RunEdit#DROP_FLOOR_KPA, 10 kPa - a cuff that releases), or the step's own PLANNED
         * drop when that is higher - never raised past both by a road that moves a drop as a
         * side effect. Raised only where the person sets the drop on the step themselves, past
         * the floor as their own call (RunEdit#allowDrop - owner, 2026-09-30). Stamped with the build
         * (RunEdit#asBuilt), carried to every step an edit builds (a ramp's recount or
         * re-spread), and asked by every road that writes a drop - and by the wire itself
         * (RunEdit#capDrop in uploadBatch and the adjustment's write). −1: not stamped.
         * TRANSIENT, like every other field here.
         */
        public int loMax = -1;
        /** A step the person ADDED to the ramp playing ("+ step", RunEdit#addRampStep) - the
         *  only kind "− step" may take out, and only before it starts. TRANSIENT. */
        public boolean added;
        /** Index into the owning Routine's stages list this preset was expanded from —
         *  stamped by plan(), the ONLY place Presets are assembled for a real run. The
         *  run screen's stage rail is driven from this (via Model.stageDurationsMs and
         *  Session.liveStageIndex), never from a second, independent guess at "which
         *  stage is running" — two derivations of the same fact is exactly how defect
         *  #25 (the rail hardcoding stage index 1) happened. */
        public int stageIdx;

        /**
         * WHICH SET this preset was expanded from, and WHERE IN THAT SET'S LADDER it sits
         * (0 for a fixed set, 0..n-1 up a ramp). Stamped by {@link Model#plan} in the same
         * walk that stamps stageIdx, for the same reason: the as-run recorder groups what
         * actually happened by (stage, set) and has to be able to say "step 2 of the ramp",
         * and a second, independent guess at which set a preset came from is exactly the
         * duplication defect #25 was made of.
         *
         * TRANSIENT — deliberately absent from every toJson/fromJson. A Preset is never
         * persisted: the plan is rebuilt from the routine at the start of every run, so
         * these are re-derived rather than restored and a stale id can never outlive the
         * set it names. `setId` is null on any Preset assembled outside plan() (Set#ladder
         * itself does not know its owner's id).
         */
        public String setId;
        /** Meaningful only when {@link #setId} != null — a Preset assembled outside
         *  {@link Model#plan} carries a plain 0 here, which is a default, not a position. */
        public int ordinal;
        /**
         * WHERE IN ITS STAGE the owning set sits — the index into Stage#setIds — stamped by
         * {@link Model#plan} in the same walk as the other three. A stage may hold the SAME
         * set id twice in a row (two identical consecutive sets), and the as-run recorder
         * has to keep those two occurrences apart: they are two set boundaries, two blocks,
         * and "update the routine" has to splice each back into ITS position. (stageIdx,
         * setId) alone cannot tell them apart; (stageIdx, pos) can. Transient like the
         * rest; 0 on a Preset built outside plan().
         */
        public int pos;
        /**
         * THIS PRESET COMMANDS NOTHING — it is a rest window (see {@link Set#rest}).
         *
         * TRANSIENT, like setId/ordinal/pos: a Preset is never persisted, so there is no
         * toJson to add this to. It is stamped by {@link Set#ladder} for a rest set and
         * read by exactly one consumer that matters — playPreset, which takes the rest
         * branch and arms nothing rather than starting a slot.
         *
         * It is a MARK, not an inference from the zeros. Nothing may decide "up == 0 and
         * lo == 0, so this must be a rest": a clamped-away preset and a deliberate rest
         * would then be the same thing, and the branch that skips arming would start
         * firing on presets that were only supposed to be quiet.
         */
        /** TRANSIENT, like every other field here - a Preset is never persisted. Stamped
         *  only from a rest STAGE (see Model#plan): a rest SET has no stage of its own to
         *  name, and a library set must not be able to gate somebody's run. */
        public boolean awaitAck;
        public boolean rest;
        /**
         * THIS STEP IS DONE BY HAND - stamped from a manual rest STAGE by {@link Model#plan}
         * (the tunica release, the changeover), never from a set. The run names it ("BY HAND",
         * never "REST") and the release waits for Done once its guide time is up
         * ({@link ByHand#waitsAfterClock}). TRANSIENT, like every other field here.
         */
        public boolean manual;
        /**
         * HOW MUCH THE WHOLE-ROUTINE OFFSET HAS MOVED THIS PRESET'S PULL, in kPa - what it
         * actually changed, after the ceiling and the floor. RoutineOffset#apply holds the
         * trainer's cap against it step by step, so a step can never be taken further above
         * its prescription than the cap, whatever the run's counter says; a ramp's tail
         * rebuilt by a step-count change interpolates it (RunEdit#resizeRemaining), and any
         * other edit that writes the pull outright drops a negative one to 0
         * (RunEdit#writePull).
         * TRANSIENT, like every other field here - a Preset is never persisted.
         */
        public int offsetKpa;
        /**
         * THIS STEP'S PLACE IN THE PLAN AS BUILT (RunRejoin#stamp), −1 for a step an edit
         * built (a ramp's recount or re-spread, "+ step"). The run's plan is edited "this run
         * only" - skips take steps out, Undo puts copies back - so an index into it is not an
         * index into the rebuild a rejoin plays; this is (device check EMU9b N1). Carried by
         * RunEdit#copyPreset. TRANSIENT, like every other field here.
         */
        public int src = -1;
    }

    /* ---------------------------------------------------------- formatting */

    /**
     * Unit-aware formatting. Internal storage is ALWAYS kPa — `unit` only selects how a
     * value is displayed, mirroring the prototype's single global UNIT variable that
     * every one of its formatters (fmtP/fmtR) reads. Whoever changes the selected unit
     * (the Settings screen) is responsible for keeping Fmt.unit and the owning Model's
     * `unit` field in step; SessionActivity.onCreate syncs it once from the loaded model.
     *
     * Deliberately pure (no android.* imports) and nested in Model, not Ui, so the
     * desktop self-test — which compiles only Proto/Model/SelfTest, never Ui — can
     * actually exercise it. See SelfTest for the assertions.
     */
    public static final class Fmt {
        private Fmt() { }

        public static final double KPA_PER_INHG = 3.38639;
        /** 1 kPa = 0.750062 cmHg — the same mercury column inHg measures, in centimetres,
         *  so it is exactly 2.54× the inHg reading and carries the SAME negative-vacuum
         *  convention. Stated as cmHg-per-kPa (the multiply direction display uses) with
         *  the inverse derived once below, so nothing outside this class ever has to
         *  remember which way round it goes. */
        public static final double CMHG_PER_KPA = 0.750062;
        public static final double KPA_PER_CMHG = 1.0 / CMHG_PER_KPA;
        public static String unit = "inHg";

        /** The three units this class knows, and the ONE place the set is stated. Anything
         *  else selects inHg (see {@link Model#clampAll}), which is the historical default. */
        public static final String U_KPA = "kPa", U_INHG = "inHg", U_CMHG = "cmHg";

        public static boolean isUnit(String u) {
            return U_KPA.equals(u) || U_INHG.equals(u) || U_CMHG.equals(u);
        }

        /**
         * A kPa MAGNITUDE in whatever unit is selected — the one conversion every formatter
         * below goes through, so a new unit is added here and nowhere else.
         *
         * It converts a magnitude, never a direction: the negative-vacuum convention that
         * separates kPa from the two mercury units is applied by the CALLERS that own a
         * sign ({@link #p} and {@link #d}), because they are the only two that have one.
         */
        private static double conv(double kPa) {
            if (U_KPA.equals(unit)) return kPa;
            if (U_CMHG.equals(unit)) return kPa * CMHG_PER_KPA;
            return kPa / KPA_PER_INHG;
        }

        /** How many of the display unit one kPa is - the scale a chart axis counts in
         *  (Trace#axisTicks), so a round number of inHg lands on a gridline. */
        public static double perKpa() { return conv(1.0); }

        /** The inverse of {@link #conv} — a number read in the display unit, back in kPa. */
        private static double unconv(double shown) {
            if (U_KPA.equals(unit)) return shown;
            if (U_CMHG.equals(unit)) return shown * KPA_PER_CMHG;
            return shown * KPA_PER_INHG;
        }

        /** The word printed after the number. */
        public static String unitWord() {
            if (U_KPA.equals(unit)) return U_KPA;
            if (U_CMHG.equals(unit)) return U_CMHG;
            return U_INHG;
        }

        /** Whether the selected unit renders a VACUUM as a negative number. True for both
         *  mercury units (inHg and cmHg are the same column, so they agree on sign), false
         *  for kPa, which grows more positive as the vacuum deepens. */
        public static boolean negativeVacuum() { return !U_KPA.equals(unit); }

        /** A pressure. kPa keeps one decimal; the mercury units are always shown negative
         *  (vacuum reads negative there) — e.g. "34.0 kPa", "−10.0 inHg", "−25.5 cmHg". */
        public static String p(double kPa) {
            // Zero has no sign. Without this, p(0) printed "−0.0 inHg" — defect 21's
            // second clause — and, for a negative zero double, "-0.0 kPa" with an ASCII
            // hyphen where the rest of this class uses U+2212. Both are visible on a first
            // run: the "Pull to" and "Release to" steppers print their own floor of 0.
            // The test is on the ROUNDED magnitude, so 0.04 kPa (which formats as 0.0)
            // does not print a sign contradicting the digits beside it either.
            double mag = Math.abs(kPa);
            if (U_KPA.equals(unit)) {
                String t = String.format(Locale.US, "%.1f", mag);
                return ("0.0".equals(t) || kPa > 0 ? "" : "−") + t + " kPa";
            }
            String t = String.format(Locale.US, "%.1f", conv(mag));
            return ("0.0".equals(t) ? "" : "−") + t + " " + unitWord();
        }

        /**
         * THE SAME PRESSURE WITHOUT ITS UNIT - for an axis, where the unit is stated once
         * beside the chart and repeating it down a 30 dp gutter would be three copies of a
         * fact that has not changed between the ticks. The SIGN is kept: a vacuum reads
         * negative in the mercury units and an axis that dropped the minus would disagree
         * with every other pressure on the screen.
         */
        public static String pBare(double kPa) {
            String full = p(kPa);
            int sp = full.lastIndexOf(' ');
            return sp < 0 ? full : full.substring(0, sp);
        }

        /**
         * A PRESSURE RANGE, with the unit said once and no dash that could be read as a
         * minus sign.
         *
         * Built as p(lo) + "–" + p(hi) the weeks table printed "−5.0 inHg–−6.0 inHg":
         * the unit twice, and an en-dash butted against the negative sign of the second
         * value so it read as "inHg--6.0". In inHg EVERY pressure carries a leading minus,
         * so any range built by joining two formatted pressures has this collision - it is
         * not a font problem and not fixable by choosing a different dash.
         *
         * So the word "to" rather than a dash, and the unit only on the second value, which
         * is where a reader looks for it. A range whose ends format identically prints as a
         * single pressure: "−5.0 to −5.0 inHg" states a range that is not one.
         */
        public static String range(double loKpa, double hiKpa) {
            String hi = p(hiKpa);
            String lo = p(loKpa);
            if (lo.equals(hi)) return hi;
            // The low end loses its unit word - " kPa" / " inHg" / " cmHg" is always the
            // tail, so it is cut rather than reformatted, and the two ends can never
            // disagree about how a number is rendered.
            String tail = " " + unitWord();
            if (lo.endsWith(tail)) lo = lo.substring(0, lo.length() - tail.length());
            return lo + " to " + hi;
        }

        /**
         * A pressure MAGNITUDE — "how much pressure", with no direction attached. Same
         * decimals and same unit word as {@link #p}, and NEVER a sign in either unit.
         *
         * The save-what-you-ran card says things like "You nudged Work by <mag>". That is
         * a SIZE, not a reading and not a signed difference, so neither existing formatter
         * fits: p() unconditionally prints "−" in inHg because every absolute pressure it
         * is handed is a vacuum depth, and d() always prints an explicit + or −. Routing a
         * size through either produces a sign the sentence has already said in words.
         *
         * inHg is a positive number here for the same reason r() and dose() are: it is the
         * magnitude that is being converted, not a vacuum reading, so the negative-vacuum
         * convention does not apply. A negative input is its own magnitude — mag(-2.0) and
         * mag(2.0) are the same string — so a caller may hand it a raw delta without first
         * remembering to take Math.abs.
         */
        public static String mag(double kPa) {
            return String.format(Locale.US, "%.1f", conv(Math.abs(kPa))) + " " + unitWord();
        }

        /** The smallest bound the offer threshold may hold, and the largest. Stated here
         *  because both the stepper and clampAll must agree on them. */
        public static final double OFFER_MIN_KPA = 0.0, OFFER_MAX_KPA = 20.0;

        /**
         * ONE TAP OF THE "ask when pressure changes by at least" STEPPER, in kPa.
         *
         * The threshold is STORED in kPa and STEPPED in the display unit: 0.5 kPa per tap
         * in kPa, 0.1 inHg per tap in inHg. The inHg step is converted to kPa here, once,
         * so nothing outside this class ever multiplies by KPA_PER_INHG to move a stored
         * value. It is a MAGNITUDE (how big a change is worth asking about), so the inHg
         * step is positive — the negative-vacuum convention of p() does not apply, exactly
         * as it does not apply to mag(), which prints this value.
         */
        public static double stepKpaForUnit() {
            if (U_KPA.equals(unit)) return 0.5;
            // A tenth of the DISPLAYED unit, converted here so nothing outside this class
            // multiplies by a constant. A tenth of a cmHg is finer than a tenth of an inHg
            // by exactly 2.54, which is the point of offering it.
            return unconv(0.1);
        }

        /**
         * One tap on a WHOLE-kPa field: 1 kPa in kPa mode, otherwise one unit of whatever
         * scale is on screen — one inHg in inHg, one cmHg in cmHg. The conversion happens
         * HERE, once, so nothing outside this class multiplies a stored value by a
         * constant; that is the same rule {@link #stepKpaForUnit} follows, and the reason
         * {@link #conv}/{@link #unconv} are private.
         *
         * The result is rounded back to a whole kPa because the wire protocol accepts
         * nothing finer (see Proto.addPreset). A cmHg tap therefore moves the stored value
         * by roughly 1 kPa, which is the closest the hardware can express — and far closer
         * than the inHg-sized jump that caller-side arithmetic used to take for every unit
         * that was not kPa.
         */
        public static int stepWholeKpa(int current, int dir) {
            if (U_KPA.equals(unit)) return current + dir;
            return (int) Math.round(unconv(conv(current) + dir));
        }

        /**
         * ONE TAP ON A SETUP PRESSURE STEPPER, ON ONE FIXED GRID (device walk E-M5, 2026-09-30):
         * the whole kPa nearest each whole unit shown - round(k x one unit) counted from 0 - so
         * going up and coming down pass through the same values whatever the stepper started
         * from. {@link #stepWholeKpa} steps one unit from wherever it is and rounds, so the
         * rounding walked: up from the −5.0 inHg default it landed on −8.6 and −9.4, down on
         * −8.9, and the owner's −9 (−8.9, 30 kPa) was not on the way up. In inHg the grid runs
         * ... 27 (−8.0), 30 (−8.9), 34 (−10.0), 37 (−10.9), 41 (−12.1) ...; in kPa it is every
         * whole kPa, as before. The caller holds the result to its own limits.
         */
        public static int gridStepWholeKpa(int current, int dir) {
            if (U_KPA.equals(unit) || dir == 0) return current + dir;
            double step = unconv(1.0);
            if (!(step > 0)) return current + dir;
            if (dir > 0) {
                long k = (long) Math.floor(current / step);
                while (Math.round(k * step) <= current) k++;
                return (int) Math.round(k * step);
            }
            long k = (long) Math.ceil(current / step);
            while (k > 0 && Math.round(k * step) >= current) k--;
            return (int) Math.round(k * step);
        }

        /**
         * ONE TAP OF THE RUN SCREEN'S ± CHIP, in whole kPa.
         *
         * The chips were stepping a flat ±1 kPa whatever the display said. In kPa that is
         * one tick of the last digit on screen; in inHg it is 0.30, so three taps moved the
         * reading by a single digit and the first tap after a settle often moved nothing
         * visible at all. A control whose smallest step is smaller than the display's own
         * resolution reads as broken, and was reported as broken.
         *
         * HALF A UNIT, not a whole one. {@link #stepWholeKpa} exists and steps a full unit
         * of whatever is on screen — right for a stepper in an editor, too coarse for a live
         * adjustment to a cuff under pressure, where one inHg is 3.4 kPa in a single tap.
         * Half an inHg is 1.7 kPa: bigger than the display's resolution, so every tap shows,
         * and small enough to be a correction rather than a change of plan.
         *
         * Rounded back to a whole kPa because the wire accepts nothing finer, and converted
         * HERE so that nothing outside this class multiplies a stored value by a constant —
         * the same rule {@link #stepWholeKpa} and {@link #stepKpaForUnit} follow.
         */
        public static int nudgeKpa(int current, int dir) {
            if (U_KPA.equals(unit)) return current + dir;
            int stepped = (int) Math.round(unconv(conv(current) + dir * 0.5));
            // A half-unit that rounds to no change at all would make the chip dead again,
            // which is the whole defect. One kPa is the smallest the wire can express.
            if (stepped == current) stepped = current + dir;
            return stepped;
        }

        /**
         * The threshold after one tap of − (dir −1) or + (dir +1), bounded to
         * [OFFER_MIN_KPA, OFFER_MAX_KPA] — the same bounds clampAll enforces.
         *
         * NO DRIFT. The step is ADDED to the stored kPa, not re-derived from a rounded
         * display value: rounding the inHg reading to one decimal and converting back
         * would move 2.0 kPa to 2.03 kPa on a + followed by a −, and a user tapping either
         * way a few times would watch a value they never edited crawl. The result is
         * quantised to 1e-6 kPa instead, which is finer than either unit prints and exact
         * for both step sizes, so +1 then −1 lands back on the SAME double (2.0 stays
         * 2.0), and n taps up then n taps down is the identity.
         *
         * Both bounds are sticky by design: stepping down from 0 stays at 0, which is the
         * documented "ask about any change at all" setting, not an accident.
         */
        public static double offerStep(double kPa, int dir) {
            double v = kPa + dir * stepKpaForUnit();
            v = Math.rint(v * 1e6) / 1e6;
            if (v < OFFER_MIN_KPA) v = OFFER_MIN_KPA;
            if (v > OFFER_MAX_KPA) v = OFFER_MAX_KPA;
            return v;
        }

        /**
         * THE INVERSE OF {@link #p}, for a pressure the user TYPED.
         *
         * The tap-to-type field on a param row shows what p() printed, so what comes back
         * is a number in the DISPLAY unit — and storage is always kPa. Converting here, in
         * the one class that owns KPA_PER_INHG, is the same rule {@link #offerStep} follows:
         * nothing outside Fmt ever multiplies by the constant.
         *
         * The SIGN IS DROPPED. p() renders every vacuum depth negative in inHg, so a person
         * reading "−6.6 inHg" and re-typing it types the minus back; a person in kPa types
         * a positive number for the same pressure. Both mean the same depth, and a stored
         * setpoint is a magnitude, so −6.6 and 6.6 both come back as 22 kPa rather than one
         * of them clamping to zero.
         *
         * Rounded to whole kPa because every setpoint this feeds is an int on the wire.
         */
        /**
         * A NUMBER THE USER TYPED, in whatever way their keyboard writes one.
         *
         * Every typed field in this app parsed with {@link Double#parseDouble}, which accepts
         * a POINT and nothing else. A keyboard set to a comma-decimal locale - most of
         * Europe, most of South America - produces "10,5", and the app answered "Not a
         * number". The value was not out of range and the field was not misunderstood; the
         * app simply could not read its own user's digits.
         *
         * Both separators are accepted, and only one may appear: "1,5" is one and a half,
         * and "1,500.5" or "1.500,5" are rejected rather than guessed at, because a thousands
         * separator is a different claim from a decimal point and this app has no business
         * inferring which one somebody meant.
         *
         * Throws {@link NumberFormatException} exactly as parseDouble does, so every caller's
         * existing catch keeps working unchanged.
         */
        public static double typedNumber(String raw) {
            if (raw == null) throw new NumberFormatException("empty");
            String t = raw.trim();
            // U+2212 MINUS is what this app PRINTS for a negative pressure, so it is what a
            // person copying a value back in will type. parseDouble only knows the ASCII one.
            t = t.replace('\u2212', '-');
            int commas = 0, dots = 0;
            for (int i = 0; i < t.length(); i++) {
                if (t.charAt(i) == ',') commas++;
                else if (t.charAt(i) == '.') dots++;
            }
            if (commas > 0 && dots > 0) throw new NumberFormatException("mixed separators");
            if (commas > 1 || dots > 1) throw new NumberFormatException("more than one separator");
            if (commas == 1) t = t.replace(',', '.');
            return Double.parseDouble(t);
        }

        /** Wave 2: seconds typed as "300" OR as "5:00" (m:ss, or h:mm:ss) — a person who
         *  wants five minutes types the clock form the app itself prints everywhere.
         *  Throws NumberFormatException like typedNumber, same catch everywhere. */
        public static double typedSeconds(String raw) {
            if (raw == null) throw new NumberFormatException("empty");
            String t = raw.trim();
            if (t.indexOf(':') < 0) return typedNumber(t);
            String[] parts = t.split(":");
            if (parts.length < 2 || parts.length > 3)
                throw new NumberFormatException("not a clock time");
            long total = 0;
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) throw new NumberFormatException("empty clock field");
                int v = Integer.parseInt(p);
                if (v < 0 || (i > 0 && v > 59)) throw new NumberFormatException("bad clock field");
                total = total * 60 + v;
            }
            return total;
        }

        public static int typedKpa(double shown) {
            return (int) Math.round(unconv(Math.abs(shown)));
        }

        /**
         * THE REACHABLE VALUES EITHER SIDE OF ONE \u2014 "8.3 \u00b7 8.6 \u00b7 8.9" (O2).
         *
         * A setpoint is a WHOLE kPa on the wire, so in inHg the values that exist sit about
         * 0.295 apart and most round numbers are not among them: from 8.6 the stepper gives
         * 8.9, then 9.2, and 9.0 never appears however long you tap. That is a true fact
         * about the pump, and the honest-display ruling says we do not paper over it \u2014
         * but leaving it to be discovered by tapping past and back is not honesty either.
         *
         * Returns null in kPa, where the step IS the unit and the ladder would be three
         * consecutive integers stating the obvious. `maxKpa` is the ceiling, so the ladder
         * never advertises a rung the stepper refuses.
         */
        public static String ladder(int kPa, int maxKpa) {
            if (U_KPA.equals(unit)) return null;
            StringBuilder b = new StringBuilder();
            for (int v = kPa - 1; v <= kPa + 1; v++) {
                if (v < 0 || v > maxKpa) continue;
                if (b.length() > 0) b.append("  \u00b7  ");
                b.append(p(v));
            }
            return b.toString();
        }

        /**
         * WHAT TO SAY WHEN A TYPED PRESSURE IS NOT ON THE WIRE (O2).
         *
         * Someone types 9.0 inHg; the nearest whole kPa is 8.9. Silently storing 8.9 leaves
         * the row contradicting the box they just typed into. Returns null when the typed
         * number and the stored one print the same, which is the ordinary case and needs no
         * remark \u2014 a message on every typed value is a message nobody reads.
         */
        public static String snapNote(double typed, int storedKpa) {
            String want = String.format(Locale.US, "%.1f", conv(Math.abs(typed)));
            String got  = String.format(Locale.US, "%.1f", conv(storedKpa));
            if (want.equals(got)) return null;
            return want + " is not on the wire \u2014 set to " + got + " "
                 + unitWord() + ", the nearest whole kPa";
        }

        /** A pressure RATE (kPa/s), not a pressure — two decimals, never negative, and
         *  never routed through p(). Durations, cm and percentages never go through
         *  either formatter. */
        public static String r(double kPaPerSec) {
            return String.format(Locale.US, "%.2f", conv(kPaPerSec)) + " " + unitWord() + "/s";
        }

        /** A delivered DOSE (pressure integrated over time, stored as kPa·s). A magnitude,
         *  like r(): it follows the display unit (kPa·s or inHg·s) but carries no sign, so it
         *  is never routed through p(). The History row used to print a literal " kPa·s"
         *  beside a peak that had already switched to inHg — two units on one line. */
        public static String dose(double kPaSeconds) {
            return Math.round(conv(kPaSeconds)) + " " + unitWord() + "·s";
        }

        /** A duration in m:ss. Unit-independent. */
        public static String t(long seconds) {
            long m = seconds / 60, s = seconds % 60;
            return m + ":" + (s < 10 ? "0" : "") + s;
        }

        /**
         * ONE WAY TO WRITE A ROUTINE'S SHAPE (polish SYS-13): "10 × 2 min", never "10×2min",
         * "10×2.0min" or "5×5.0min" - the same shape was written three ways across Today, the
         * Trainer and the save sheet.
         *
         * A hold under two minutes is said in seconds ("10 × 60 s", "6 × 90 s"); from two
         * minutes up in minutes, with no trailing ".0" ("2 min", "2.5 min"), and a hold that is
         * neither whole nor half a minute keeps its seconds ("2 min 15 s"). The multiply sign
         * is U+00D7 with a plain space either side (Say#doorValue makes
         * them non-breaking where a line of facts needs it). `count` under 1 is said as 1.
         */
        public static String shape(int count, int holdSec) {
            return Math.max(1, count) + " × " + holdWords(holdSec);
        }

        /** The shape and its pressure: "10 × 2 min at −8.9 inHg" (the minus is U+2212, from
         *  {@link #p}). */
        public static String shape(int count, int holdSec, double kPa) {
            return shape(count, holdSec) + " at " + p(kPa);
        }

        /** A hold's length as {@link #shape} says it: "60 s", "2 min", "2.5 min", "2 min 15 s". */
        public static String holdWords(int holdSec) {
            int s = Math.max(0, holdSec);
            if (s < 120) return s + " s";
            if (s % 60 == 0) return (s / 60) + " min";
            if (s % 30 == 0) return (s / 60) + ".5 min";
            return (s / 60) + " min " + (s % 60) + " s";
        }

        /** A percentage with no space before the sign, as the app writes it: "6%", "75%",
         *  "6.5%" - one decimal only where there is one (SYS-13). */
        public static String pct(double v) {
            double r = Math.round(v * 10.0) / 10.0;
            if (r == Math.rint(r)) return String.valueOf((long) Math.rint(r)) + "%";
            return String.format(Locale.US, "%.1f%%", Double.valueOf(r));
        }

        /**
         * A pressure DELTA — e.g. actual minus commanded — in the SAME vacuum-depth
         * kPa terms p() takes (larger kPa magnitude = deeper vacuum). NEVER route a
         * delta through p(): kPa and inHg represent vacuum with OPPOSITE sign
         * conventions — kPa grows more POSITIVE as vacuum deepens, inHg grows more
         * NEGATIVE — so converting a delta, unlike an absolute pressure, must FLIP
         * sign for inHg. p() unconditionally prepends '−' because every absolute
         * pressure it is ever called with is a vacuum depth, always negative in
         * inHg; call it on a signed difference instead and that prefix is simply
         * wrong for roughly half of all deltas (defect #22 — the run screen's
         * shortfall printed "−0.5 inHg" for a positive difference).
         *
         * Always renders an explicit sign, so a caller never has to guess whether a
         * bare magnitude meant "above" or "below".
         */
        /**
         * THE SAME DELTA WITH NO SIGN, for a sentence that has already said the direction.
         *
         * "Shortfall +0.2 inHg - below commanded" reads as a contradiction: the word says
         * under, the sign says over. It is not a bug in the sign - a deficit in kPa IS a
         * positive figure once flipped into inHg, which is exactly what d() exists to get
         * right - it is a sign printed beside a word that already carries it. Where the
         * prose names the direction, the number is a magnitude.
         */
        public static String dMag(double deltaKpa) {
            String v = d(deltaKpa);
            return (v.startsWith("+") || v.startsWith("−")) ? v.substring(1) : v;
        }

        public static String d(double deltaKpa) {
            if (U_KPA.equals(unit))
                return (deltaKpa >= 0 ? "+" : "−")
                     + String.format(Locale.US, "%.1f kPa", Math.abs(deltaKpa));
            // SIGN FLIPS for both mercury units — see the doc above. cmHg is the same
            // column as inHg, so it flips for exactly the same reason and by the same rule.
            double shown = -conv(deltaKpa);
            return (shown >= 0 ? "+" : "−")
                 + String.format(Locale.US, "%.1f", Math.abs(shown)) + " " + unitWord();
        }

        /* ------------------------------------------------------------ SIZE (cm / in) ---
         *
         * THE SECOND UNIT AXIS, and deliberately a separate one. Pressure and body size
         * have nothing to do with each other: a user who reads vacuum in inHg may well
         * measure themselves in centimetres, and folding both onto one setting would make
         * one of those two choices unreachable.
         *
         * STORAGE IS ALWAYS CENTIMETRES, exactly as pressure storage is always kPa — every
         * Reading, every goal, every delta and every exported raw column is cm, and this
         * only picks how a number is DRAWN. Nothing downstream of these methods converts.
         */

        public static final double CM_PER_IN = 2.54;
        public static final String S_CM = "cm", S_IN = "in";

        /** The selected SIZE unit — "cm" or "in". Kept in step with {@link Model#sizeUnit}
         *  by whoever changes it, the same contract {@link #unit} has. */
        public static String sizeUnit = S_CM;

        public static boolean isSizeUnit(String u) { return S_CM.equals(u) || S_IN.equals(u); }

        /** The word printed after a length. */
        public static String lenUnit() { return S_IN.equals(sizeUnit) ? S_IN : S_CM; }

        /**
         * A LENGTH, stored in cm, as it should be DRAWN — "15.5 cm" or "6.10 in".
         *
         * INCHES KEEP TWO DECIMALS, centimetres one. That is not decoration: one tenth of a
         * centimetre is 0.039 in, so printing inches to one decimal would show two readings
         * that genuinely differ as the same number, and a trend built out of 0.5 cm steps
         * would appear to stand still. The printed precision has to be able to carry the
         * precision the app actually stores.
         */
        public static String len(double cm) {
            return lenNum(cm) + " " + lenUnit();
        }

        /** The same number WITHOUT the unit word — for the places that print the unit
         *  separately (a stepper's label, a chart's axis, a headline with its own suffix). */
        public static String lenNum(double cm) {
            if (S_IN.equals(sizeUnit))
                return String.format(Locale.US, "%.2f", cm / CM_PER_IN);
            return String.format(Locale.US, "%.1f", cm);
        }

        /**
         * A length DIFFERENCE, with an explicit sign — "+0.40 cm", "−0.16 in".
         *
         * Unlike {@link #d}, NO SIGN FLIP: centimetres and inches measure the same thing in
         * the same direction, so a gain is a gain in both. The only reason this exists apart
         * from {@link #len} is the explicit "+", so a caller never has to guess whether a
         * bare magnitude meant growth or loss.
         *
         * Two decimals in cm here (not one, as {@link #len} uses): a delta is routinely
         * smaller than a reading, and Summary#cm has always printed changes to two.
         */
        public static String lenDelta(double cm) {
            double m = Math.abs(cm);
            String n = S_IN.equals(sizeUnit)
                ? String.format(Locale.US, "%.2f", m / CM_PER_IN)
                : String.format(Locale.US, "%.2f", m);
            return (cm >= 0 ? "+" : "−") + n + " " + lenUnit();
        }

        /** THE INVERSE OF {@link #len}, for a length the user TYPED — a number in the
         *  display unit, back in the centimetres everything stores. The sign is dropped:
         *  a body measurement is a magnitude. */
        public static double typedCm(double shown) {
            double m = Math.abs(shown);
            return S_IN.equals(sizeUnit) ? m * CM_PER_IN : m;
        }

        /* ------------------------------------------------------------- LOAD -------------
         *
         * THE LOAD UNIT - "lb" or "kg" (owner, 2026-09-30). Storage, every rule and every cap
         * stay in POUNDS (Traction, Plan, Scale); this only picks how a load is drawn, the
         * contract {@link #unit} and {@link #sizeUnit} have. Kept in step with
         * {@link Model#loadUnit} by whoever changes it. Every user-visible load goes through
         * {@link #load} (Traction#settingLb / #boundLb / #boundLbShort call it), so a card, a
         * routine's name, a note and a warning cannot print different units.
         *
         * Pounds here, before any model is read, because that is what every screen printed
         * before this existed; a loaded model sets it (SessionActivity#onCreate).
         */
        public static final String L_LB = "lb", L_KG = "kg";
        public static final double LB_PER_KG = 2.2046226218;

        public static String loadUnit = L_LB;

        public static boolean isLoadUnit(String u) { return L_LB.equals(u) || L_KG.equals(u); }

        /** The load unit that goes with a size unit: inches -> pounds, centimetres -> kilos. */
        public static String defaultLoadUnit(String sizeUnit) {
            return S_IN.equals(sizeUnit) ? L_LB : L_KG;
        }

        public static boolean loadInKg() { return L_KG.equals(loadUnit); }

        /** The word printed after a load. */
        public static String loadWord() { return loadInKg() ? L_KG : L_LB; }

        /** A load, given in pounds, in the selected unit WITHOUT the word: one decimal - in kilos
         *  always ("5.0", "5.4": the device walk read "5 kg" beside "5.4 kg"), in pounds with no
         *  trailing ".0" on a whole number ("11.8", "12"), as it always was. */
        public static String loadNum(double lb) {
            double v = loadInKg() ? lb / LB_PER_KG : lb;
            double r = Math.round(v * 10.0) / 10.0;
            if (loadInKg()) return String.format(Locale.US, "%.1f", r);
            long whole = (long) r;
            return (Math.abs(r - whole) < 1e-9) ? String.valueOf(whole) : String.valueOf(r);
        }

        /** A load, given in pounds, as it is drawn: "11.8 lb" or "5.4 kg". */
        public static String load(double lb) { return loadNum(lb) + " " + loadWord(); }

        /** One step of a load stepper, in POUNDS: half a pound, or half a kilo in kilos. */
        public static double loadStepLb() { return loadInKg() ? 0.5 * LB_PER_KG : 0.5; }

        /* ------------------------------------------------------------- VOLUME (S9) ---
         *
         * ALWAYS cm3, never converted against sizeUnit above. Cubic inches is not a unit
         * anybody asked to read a body measurement in, and S9's own spec (round6-
         * options.html) states the formula in cm3 — there is exactly one volume unit,
         * the same way there is exactly one pressure unit family and one size one; this
         * is its own small family of one rather than a third branch bolted onto len()'s.
         */

        /** The word printed after a volume estimate. */
        public static String volUnit() { return "cm³"; }

        /** A volume estimate WITHOUT the unit word — for the chart axis and captions that
         *  print the unit once rather than after every number. One decimal, the same
         *  precision {@link #lenNum}'s cm branch uses — the two readings a volume is
         *  estimated from are never carried to more than that themselves. */
        public static String volNum(double cm3) {
            return String.format(Locale.US, "%.1f", cm3);
        }

        /** A volume estimate, with its unit — "171.9 cm³". */
        public static String vol(double cm3) {
            return volNum(cm3) + " " + volUnit();
        }
    }

    /* --------------------------------------------------------- meas cadence */

    /**
     * How often the app should ask for a measurement. mode is one of "every",
     * "sessions", "hours", "off". sinceN/sinceH are progress counters — sessions or
     * hours accumulated since the last logged reading — owned by whatever screen logs
     * measurements; a fresh install starts at zero, nothing is due yet.
     */
    public static final class Meas {
        public String mode = "sessions";
        public int n = 5;
        public int hours = 10;
        public int sinceN = 0;
        public double sinceH = 0;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("mode", mode); o.put("n", n); o.put("hours", hours);
            o.put("sinceN", sinceN); o.put("sinceH", sinceH);
            return o;
        }

        /**
         * Reads back only via optString()+parse for the double field: the desktop
         * org.json shim (desktoptest/org/json) has no optDouble(String), so this stays
         * on the lowest-common-denominator surface both JSONObject implementations
         * share, rather than forking behaviour between the phone and the desktop test.
         */
        /* THE CADENCE AS SETTINGS DRAWS IT (measurement polish, item 9): one segmented
         * control of four - Sessions, Hours, Week, Never - where there were five radio
         * buttons. "Every session" is no longer its own choice: it is Sessions with an
         * interval of 1, and the caption says so. The STORED modes are unchanged, so the
         * question `due` asks at START is exactly what it was: an interval of 1 is stored as
         * "every" (which asks even straight after a reading logged on its own) and anything
         * longer as "sessions". Nothing to migrate - every stored mode maps to a segment. */
        public static final int CAD_SESSIONS = 0, CAD_HOURS = 1, CAD_WEEK = 2, CAD_NEVER = 3;

        /** Which segment `mode` is drawn as. An unknown mode reads as Sessions, the same
         *  default clampAll rewrites it to. */
        public int cadenceSegment() {
            if ("hours".equals(mode)) return CAD_HOURS;
            if (MeasLog.MEAS_WEEK_14.equals(mode)) return CAD_WEEK;
            if ("off".equals(mode)) return CAD_NEVER;
            return CAD_SESSIONS;
        }

        /** The Sessions interval as shown: 1 for "every", else `n`. */
        public int everySessions() {
            return "every".equals(mode) ? 1 : Math.max(1, Math.min(20, n));
        }

        /** A tap on a segment. Sessions comes back as "every" when its interval is 1. */
        public void chooseSegment(int seg) {
            if (seg == CAD_HOURS) mode = "hours";
            else if (seg == CAD_WEEK) mode = MeasLog.MEAS_WEEK_14;
            else if (seg == CAD_NEVER) mode = "off";
            else mode = n <= 1 ? "every" : "sessions";
        }

        /** The Sessions stepper: 1 to 20, and 1 is "every". Stepping from "every" starts
         *  from 1, never from whatever `n` was left at before. */
        public void stepSessions(int delta) {
            int v = everySessions() + delta;
            v = v < 1 ? 1 : (v > 20 ? 20 : v);
            n = v;
            mode = v == 1 ? "every" : "sessions";
        }

        public static Meas fromJson(JSONObject o) {
            Meas m = new Meas();
            if (o == null) return m;
            m.mode = o.optString("mode", "sessions");
            m.n = o.optInt("n", 5);
            m.hours = o.optInt("hours", 10);
            m.sinceN = o.optInt("sinceN", 0);
            try { m.sinceH = Double.parseDouble(o.optString("sinceH", "0")); }
            catch (NumberFormatException e) { m.sinceH = 0; }
            return m;
        }
    }

    /* --------------------------------------------------------------- reading */

    /**
     * One logged body measurement — length and girth in cm, taken at a moment in time.
     * `holdKpa`/`holdSec` record the standardisation hold that preceded it: BOTH null
     * when no hold ran, never a lone 0.0 standing in for "no hold" — a 0.0 pressure
     * means no measurement, never atmospheric, the same rule that governs live
     * telemetry (see Proto.parse). "Same pressure, same duration" is the stated
     * comparability protocol, so recording only the pressure would make half of it
     * unfalsifiable.
     */
    public static final class Reading {
        /** The at-rest state axis (Task 19). ONLY meaningful for an at-rest reading
         *  (holdKpa == null): a standardised reading records the measured vacuum instead
         *  and carries no state. Two at-rest readings in different states are NOT
         *  comparable — a run of soft readings then a run of hard ones is state, not
         *  growth. `unknown` is the migration value for every reading logged before this
         *  axis existed, and is not-comparable-to-either rather than a quietly-assumed
         *  match (the "a missing value is not agreement" precedent, defect #5). */
        /**
         * A4 - WHICH CYLINDER THIS READING WAS TAKEN IN, by index into the cylinder list,
         * or -1 when it was not recorded.
         *
         * A measurement is only comparable with another taken in the SAME cylinder: a
         * different bore changes what a girth reading means, and a different length changes
         * how much tissue is inside it. The app knew which cylinder was active and never
         * wrote it onto the reading, so a trend could mix two silently - and a silent mix is
         * worse than a gap, because a gap is visible.
         *
         * -1 IS NOT 0, and that is why it is not an int defaulting to zero: every reading
         * saved before this existed was taken in SOME cylinder and nobody knows which.
         * Recording those as cylinder 0 would be inventing a fact.
         */
        public int cylinder = -1;

        /**
         * WHICH cylinder, by identity rather than by position.
         *
         * {@link #cylinder} is an INDEX, captured when the reading was taken. Deleting a
         * cylinder shifts every later index, so an old reading's index silently came to name
         * a different tube — and these readings are grouped and compared BY cylinder, so that
         * is a wrong comparison, not a wrong caption.
         *
         * Backfilled from the index by {@link Model#linkReadingCylinders} on load, while the
         * list is still in the order the index was written against. That reconstruction is
         * exact for every rack that has never had a cylinder deleted; where one already has,
         * the association was destroyed before this field existed and cannot be recovered —
         * the id simply stops any FURTHER drift. Empty = not recorded.
         */
        public String cylinderId = "";

        public static final String STATE_HARD = "hard";
        public static final String STATE_SOFT = "soft";
        public static final String STATE_UNKNOWN = "unknown";

        /** How far apart two OBSERVED vacuums may be and still count as the same actual
         *  vacuum, in kPa. The whole point of the standardisation hold is that two
         *  readings were taken at the same ACTUAL vacuum; the pump may sit a little below
         *  the commanded setpoint, so comparability is judged on the delivered pressure
         *  within this band, never on equality of the commanded int (Task 19 change 1).
         *  Below the ~2 kPa gap the addendum warns silently passed as "comparable" under
         *  the old exact-commanded test.
         *
         *  0.10: the delivered pressure compared here is the one at the END OF THE
         *  STANDARDISATION COUNT (StdMoment), not at Save - the pump coasts during the two
         *  minutes, and drift alone could put two readings of one protocol over this band. */
        public static final double COMPARABLE_TOLERANCE_KPA = 1.0;

        public String id, label, note;
        public long ts;                    // epoch millis when logged
        public double len, gir;            // cm
        public Double holdKpa;             // COMMANDED setpoint; null = at rest, no hold ran
        public Integer holdSec;            // commanded hold duration; null = no hold ran
        /** The vacuum the pump ACTUALLY reported at capture, from telemetry — NOT the
         *  commanded setpoint (Task 19 change 1). null = UNKNOWN: no fresh reading existed
         *  when the measurement was taken. Unknown is never agreement — it makes a
         *  standardised pair not-directly-comparable. Only meaningful for a standardised
         *  reading; an at-rest reading is judged on its state, not on this. Never
         *  backfilled from holdKpa: writing the commanded number here would manufacture a
         *  measurement that was never taken, the exact defect this task exists to fix.
         *
         *  0.10 - TAKEN AT THE STANDARDISED MOMENT (StdMoment): the vacuum reported at the
         *  end of the 30 s standardisation count; the one at Save only when no fresh sample
         *  existed then. {@link #observedAt} says which. */
        public Double observedKpa;
        /** When {@link #observedKpa} was taken: StdMoment.AT_COUNT_END or AT_SAVE. NULL for
         *  every reading saved before 0.10 (their value was taken at Save and is kept as it
         *  is), for one with no observed vacuum, and for at-rest readings. Written only when
         *  set, so an older reading is saved exactly as it was. */
        public String observedAt;
        /** The at-rest state (STATE_HARD / STATE_SOFT / STATE_UNKNOWN). Only meaningful
         *  when holdKpa == null. Defaults to unknown — required (never defaulted to a
         *  guess) at capture for a real at-rest reading; editable afterwards. */
        public String state = STATE_UNKNOWN;

        /**
         * WHEN IN A SESSION THIS READING WAS TAKEN — unknown, before, or after.
         *
         * PHASE_UNKNOWN (0) is the migration value for every reading logged before this
         * axis existed, and the default for a standalone reading whose "Pre / Post / —"
         * chip was left alone. It is NOT a third kind of reading: the trend draws unknown
         * readings on the PRE line, because for the entire history that predates this field
         * every logged reading WAS a session baseline or a standalone at-rest reading —
         * that is what "pre" has always meant here. Giving them a line of their own would
         * split one continuous history into two on the day of an update, which would look
         * like the measurements changed when only the app did.
         *
         * A post reading is the one genuinely new thing: it is taken with the session's
         * swelling still present, so it is NOT comparable to a pre reading as a measure of
         * growth, and the chart keeps the two apart for exactly that reason.
         */
        public static final int PHASE_UNKNOWN = 0;
        public static final int PHASE_PRE = 1;
        public static final int PHASE_POST = 2;

        public int phase = PHASE_UNKNOWN;

        /**
         * HOW THIS READING WAS MEASURED — the measurement METHOD (the protocol tag), a
         * different question from `phase` (when it was taken) and from `state` (what
         * condition the body was in). The tags coexist: a reading is both a BPEL and a pre.
         *
         * Every reading is exactly one of: STANDARDIZED (captured under the in-pump
         * standardisation hold, at controlled pressure), one of the at-rest LENGTH
         * protocols {BPEL, BPSSL, BPSL, NBPEL, NBPSL}, or one of the at-rest GIRTH
         * protocols {MSEG, MSSG}. The TAG is the series a reading is drawn on, and it is
         * DECOUPLED from holdKpa — holdKpa does not decide the tag.
         *
         * C8: AND THE TAG IS NOT THE CLAIM "THIS WAS STANDARDISED". This doc used to say it
         * was ("standardised-ness is exactly method==0") while comparable() judged it by
         * holdKpa - two rules for one word. Method 0 also carries a reading whose hold was
         * skipped or ran out (filed with no hold), a Std row logged at rest, and every
         * reading from before this axis. So whether a reading WAS standardised is one test, a hold
         * recorded on it ({@link #isStandardised}), and every comparability decision asks
         * that; the tag says which line it is drawn on.
         *
         * METHOD_STANDARDIZED (0) is ALSO the migration value: a reading logged before this
         * axis existed has no "method" key and comes back STANDARDIZED. For the whole
         * history that predates this field every logged reading was either taken under a
         * hold or was the app's single baseline path, so treating that history as one
         * comparable standardised line is the least-surprising default — a legacy-
         * standardised assumption, stated here rather than left implicit. There is no
         * separate LEGACY sentinel: the taxonomy has none.
         *
         * METHOD_STANDARDIZED is stamped by the hold path itself — the only path that knows
         * the hold ran — and it is never inferred from a reading taken at rest with no such
         * claim. (The log door's at-rest sheet used to offer it as a plain tag too; that is
         * gone - Meas#LOG_ROWS - so a new Std reading always comes through the hold. Older
         * logs keep the ones filed that way.)
         */
        public static final int METHOD_STANDARDIZED = 0;
        public static final int METHOD_BPEL  = 1;   // length
        public static final int METHOD_BPSSL = 2;   // length
        public static final int METHOD_BPSL  = 3;   // length
        public static final int METHOD_NBPEL = 4;   // length
        public static final int METHOD_NBPSL = 5;   // length
        public static final int METHOD_MSEG  = 6;   // girth
        /** SOFT girth. RENAMED from MSFL to MSSG in the three-goal round — the stored int
         *  is UNCHANGED (still 7), so every reading already on disk keeps its tag and no
         *  migration is needed or written for it. Only the NAME the user reads moved
         *  (see {@link #methodLabel}); "MSFL" appears nowhere on screen any more. */
        public static final int METHOD_MSSG  = 7;   // girth

        public int method = METHOD_STANDARDIZED;

        /**
         * S13 (b) (the owner's decision, 2026-09-26): WHICH PRE READING THIS POST READING IS
         * THE OTHER HALF OF - the id of the baseline its own session took.
         *
         * Pairing used to take the newest pre and the newest post of each day, so a second
         * measured session replaced the first, and a session with no baseline of its own was
         * paired with the morning's. The after-reading now records its own session's baseline
         * (SessionActivity#logAfterReading), and {@link Meas#prePostPct} pairs by it.
         *
         * "" - no link: every reading saved before this existed, and a reading logged on its
         * own from the Log door. Those pair by the day as they always did, so an old history
         * reads exactly as before. {@link #PAIR_NONE}: a post taken in a session that logged
         * no baseline of its own - it pairs with nothing.
         */
        public String pairOf = "";
        /** {@link #pairOf} for a post whose session took no baseline of its own. */
        public static final String PAIR_NONE = "-";

        /** Whether `m` is one of the at-rest LENGTH protocols (1..5). */
        public static boolean methodIsLength(int m) {
            return m >= METHOD_BPEL && m <= METHOD_NBPSL;
        }

        /** Whether `m` is one of the at-rest GIRTH protocols (6, 7). */
        public static boolean methodIsGirth(int m) {
            return m == METHOD_MSEG || m == METHOD_MSSG;
        }

        /** Whether `m` is one of the at-rest protocols (a length 1..5 or a girth 6,7) — a
         *  real measurement method, as opposed to the STANDARDIZED tag (0) or an unknown
         *  int from a newer save. */
        public static boolean isAtRestMethod(int m) {
            return methodIsLength(m) || methodIsGirth(m);
        }

        /** The hard/soft at-rest STATE implied by an at-rest measurement method: the erect
         *  protocols (BPEL, NBPEL, MSEG) are hard, the flaccid/stretched ones (BPSSL, BPSL,
         *  NBPSL, MSSG) are soft. STANDARDIZED — and anything not an at-rest method — carries
         *  no at-rest state. This is how a NEW reading gets its state now that the log/measure
         *  screens tag the METHOD (which already encodes erect/soft) instead of asking a
         *  redundant hard/soft question: the reading is stamped with the state its method
         *  implies. (C8: that state no longer makes two DIFFERENT methods comparable - see
         *  {@link #comparable} - so a new reading is never matched to an old one by it.) */
        public static String stateForMethod(int m) {
            switch (m) {
                case METHOD_BPEL: case METHOD_NBPEL: case METHOD_MSEG:
                    return STATE_HARD;
                case METHOD_BPSSL: case METHOD_BPSL: case METHOD_NBPSL: case METHOD_MSSG:
                    return STATE_SOFT;
                default:
                    return STATE_UNKNOWN;
            }
        }

        /**
         * WHETHER `m` ACTUALLY MEASURES a length / a girth — the fix for the defect
         * {@link Meas#buildLogReadings}'s own doc used to claim was impossible: `len`/`gir`
         * are primitive `double`, so the metric a method never asked for is left at the
         * type's zero default, indistinguishable from a real 0.0 cm reading unless a
         * caller checks the METHOD before trusting the field. This is that check, stated
         * once so every reader gates on the same rule instead of five different ad-hoc
         * ones (or none at all).
         *
         * STANDARDIZED (0) measures BOTH — the hold path (and, in older logs, the at-rest
         * sheet's two Std rows folded into one reading) always intends both
         * numbers, exactly the assumption {@link Meas#methodOnMetric} already made before
         * this method existed; that method now delegates here so the rule lives in one
         * place. An at-rest LENGTH protocol measures only length; an at-rest GIRTH
         * protocol only girth. Do NOT read this as "the field is guaranteed non-zero" —
         * it answers "does this method's protocol call for this metric", which is the
         * question every DISPLAY and DELTA site actually needs answered; it does not (and
         * given `len`/`gir` stay primitive `double` by design, cannot) detect the separate,
         * narrower case of a Std reading saved with only one of its two rows filled, or a
         * hand-edited reading whose off-axis field was never touched from its zero default.
         */
        public static boolean methodMeasuresLength(int m) {
            return m == METHOD_STANDARDIZED || methodIsLength(m);
        }

        /** The girth counterpart of {@link #methodMeasuresLength} — see that method's doc
         *  for the full rule and its known limits. */
        public static boolean methodMeasuresGirth(int m) {
            return m == METHOD_STANDARDIZED || methodIsGirth(m);
        }

        /** Instance form of {@link #methodMeasuresLength}, over this reading's own
         *  `method` — what every display/delta consumer should call rather than reading
         *  `len` directly. */
        public boolean measuredLength() { return methodMeasuresLength(method); }

        /** Instance form of {@link #methodMeasuresGirth} — see {@link #measuredLength}. */
        public boolean measuredGirth() { return methodMeasuresGirth(method); }

        /** The method's short name, for chips, legends and captions. Anything outside the
         *  known set is shown as the standardised default — an unrecognised int from a
         *  newer save is treated as method 0, never guessed into a specific protocol. */
        public static String methodLabel(int method) {
            switch (method) {
                case METHOD_BPEL:  return "BPEL";
                case METHOD_BPSSL: return "BPSSL";
                case METHOD_BPSL:  return "BPSL";
                case METHOD_NBPEL: return "NBPEL";
                case METHOD_NBPSL: return "NBPSL";
                case METHOD_MSEG:  return "MSEG";
                case METHOD_MSSG:  return "MSSG";
                default:           return "Standardised";   // polish PR-13: one name, as Compare says it
            }
        }

        /**
         * WHAT EACH METHOD MEANS, AND HOW TO TAKE IT - the one table (measurement polish,
         * item 7). The codes appeared everywhere with no explanation anywhere in the app;
         * every screen that explains one now reads it from here, so a change of wording is
         * one edit in one place.
         *
         * The meanings are the owner's, approved as written (2026-09-24); MethodMeaningTest
         * pins them. Index = the method's stored int (0 = Std .. 7 = MSSG); each row is
         * { meaning, how to take it }.
         */
        private static final String[][] METHOD_WORDS = {
            /* 0 Std   */ { "Standardised: measured during the pump's hold at a set pressure",
                            "Choose “Standardised” on Log a reading, or keep the hold "
                            + "on for sessions. The pump pulls to your hold pressure and keeps "
                            + "it there while you measure length and girth." },
            /* 1 BPEL  */ { "Erect, pressed to the bone",
                            "Erect. Lay a rigid ruler along the top, press it into the fat pad "
                            + "until it meets the bone, and read at the tip." },
            /* 2 BPSSL */ { "Stretched, pressed to the bone",
                            "Soft. Hold the head and stretch it straight out from the body, as "
                            + "far as is comfortable. Press the ruler to the bone on top and read "
                            + "at the tip." },
            /* 3 BPSL  */ { "Soft, pressed to the bone",
                            "Soft and not stretched. Press the ruler to the bone on top and "
                            + "read at the tip." },
            /* 4 NBPEL */ { "Erect, not pressed",
                            "Erect. Rest the ruler on top where the shaft meets the body, "
                            + "without pressing in, and read at the tip." },
            /* 5 NBPSL */ { "Soft, not pressed",
                            "Soft and not stretched. Rest the ruler on top without pressing "
                            + "in, and read at the tip." },
            /* 6 MSEG  */ { "Around the middle, erect",
                            "Erect. Wrap a soft tape once around the middle of the shaft, snug "
                            + "but not squeezing, and read where it meets." },
            /* 7 MSSG  */ { "Around the middle, soft",
                            "Soft. Wrap a soft tape once around the middle of the shaft, snug "
                            + "but not squeezing, and read where it meets." },
        };

        /** What `method` means in plain words ("Erect, pressed to the bone"), from
         *  {@link #METHOD_WORDS}. An int outside the table reads as Std, the same rule
         *  {@link #methodLabel} keeps, so the code and its meaning never disagree. */
        public static String methodMeaning(int method) {
            return METHOD_WORDS[method >= 0 && method < METHOD_WORDS.length ? method : 0][0];
        }

        /** How to take `method`, one or two plain sentences, from {@link #METHOD_WORDS}. */
        public static String methodHowTo(int method) {
            return METHOD_WORDS[method >= 0 && method < METHOD_WORDS.length ? method : 0][1];
        }

        public boolean photo, edited;
        /** The view captures, per Task 7 and Task 19 — null when that view was never
         *  taken. Three independent slots (Front, Side, Top), not one: folding them into a
         *  single "photo" object is what let the prototype's SHOT.note bleed a Front note
         *  onto Side (see Photo's own doc comment). Compare pairs photos of the SAME view
         *  only; all three are optional. */
        public Photo photoFront, photoSide, photoTop;

        /** The keys of this reading's record that this version does not know - written by a
         *  newer app - kept exactly as read and written back after the fields (JsonKeep),
         *  so a newer backup opened here loses nothing. Null when there were none. */
        public JSONObject unknown;

        public Reading() { }

        /** An independent staged copy for the reading editor (Task 8) — mirrors Set.copy():
         *  editing the copy must never move the original until Save explicitly commits it,
         *  and Back/cancel can just drop the reference without undoing anything (bug class:
         *  edits must be staged, not written live). Same id — this is the same reading,
         *  mid-edit, not a duplicate. photoFront/photoSide are shared by reference
         *  deliberately: the editor never changes which photo files a reading points to,
         *  only its numbers/note/hold state — see Meas.changed(), which checks exactly the
         *  fields the editor can actually touch. */
        public Reading copy() {
            Reading c = new Reading();
            c.id = id; c.label = label; c.note = note; c.ts = ts;
            c.len = len; c.gir = gir;
            c.holdKpa = holdKpa; c.holdSec = holdSec;
            c.observedKpa = observedKpa; c.state = state;
            c.observedAt = observedAt;
            // The phase travels with the copy. The editor cannot change it (it is not one
            // of Meas#changed's fields), so a staged copy that dropped it would silently
            // move a post reading onto the pre line the moment its note was edited.
            c.phase = phase;
            // The method travels with the copy for the same reason the phase does: the
            // editor cannot change it, so a staged copy that dropped it would silently
            // retag a BPEL reading the moment its note was edited.
            c.method = method;
            // ...and so does the session link (S13 b): an edited note must not unpair it.
            c.pairOf = pairOf;
            c.photo = photo; c.edited = edited;
            c.photoFront = photoFront; c.photoSide = photoSide; c.photoTop = photoTop;
            // ...and the keys a newer app wrote: an edited note must not drop them.
            c.unknown = unknown;
            return c;
        }

        /**
         * One captured view (front or side) of a reading — Task 7. Everything here is
         * captured AT SHUTTER TIME and never mutated afterward: `kpa` is the pressure
         * Session actually had commanded the instant the shutter fired (see
         * Session#heldKpa), and `note` is this view's own text, kept completely
         * separate from the other view's — the prototype's SHOT.note is a single
         * string both views append onto, so a note typed for Front literally appears
         * on Side's saved reading; that bug is fixed here by construction (two Photo
         * objects, two `note` fields, never concatenated across them — see Shot.java,
         * which is what actually enforces this at capture time).
         */
        public static final class Photo {
            public String path;    // absolute file path under getExternalFilesDir(Pictures) —
                                    // private to the device; never written to the session log.
            public String note;
            /** The vacuum the pump REPORTED when the capture was armed - null when there was
             *  no fresh reading, which is the usual case at rest but also a hold whose vacuum
             *  was not measured. So it does NOT say whether a hold was on: {@link #held}
             *  does (point 19). */
            public Double kpa;
            public long ts;

            /**
             * POINT 19 - WHETHER A HOLD WAS OUTSTANDING when this photo was taken: TRUE
             * under a hold, FALSE at rest, NULL when unknown. A photo is the photo of one
             * method, at rest or standardised, and the two are compared only with their own
             * kind - so the fact is recorded on the photo itself rather than borrowed from
             * whichever reading it is attached to.
             *
             * MIGRATION null: photos written before this was recorded carry no such fact,
             * and an import has no shutter. Readers take the reading's own kind then
             * (Compare#photoHeld) - which is what every screen did before, and all the
             * saved data can support: a photo filed on the wrong kind of reading before
             * this existed cannot be told apart from the data, so it is left as it is.
             */
            public Boolean held;
            /**
             * THE OWNER'S RULE - A PHOTO IS GROUPED BY HOW IT WAS TAKEN; THE NUMBERS KEEP THEIR
             * OWN KIND. TRUE when this photo was taken during a SERVED hold - the count
             * completed, the pump holding at the hold pressure, inside its two minutes (the
             * review label's own verdict, PhotoTruth#TAKEN_STANDARDISED) - so it is compared
             * with the standardised photos at {@link #stdKpa}, even when its reading's
             * numbers were saved at rest because the hold ended or vented before Save. FALSE
             * when it was taken otherwise: at rest, or under a hold whose count had not
             * completed or whose two minutes had passed (kept on its own). NULL when unknown.
             *
             * MIGRATION null: photos written before this was recorded, and imports (no
             * shutter). Readers then derive it from the reading as before (Compare#photoKind):
             * held on a standardised reading is standardised at that reading's hold pressure.
             */
            public Boolean std;
            /** The pressure the SERVED hold was holding (commanded, kPa) when this photo was
             *  taken - what its standardised group is compared at. Null unless {@link #std}. */
            public Double stdKpa;
            /** The pressure the hold was holding (commanded, kPa) when this photo was taken
             *  under ANY hold - served or not - so photos held outside the count are grouped
             *  by pressure too, never paired across pressures (the data-integrity review,
             *  item 4). Null at rest, for an import, and for photos taken before it was kept. */
            public Double holdKpa;

            /**
             * TRUE when this image was IMPORTED from the phone's own photo library rather
             * than taken through the app's capture flow. The file is the same kind of file
             * either way — copied through the identical downscale/orient/save path into
             * app-private storage — so nothing downstream has to treat it differently. What
             * it changes is what the picture can be trusted to MEAN: a captured photo went
             * through the level guide and carries the vacuum the pump actually delivered at
             * the shutter, while an imported one was taken at an unknown angle, at an
             * unknown time, under an unknown pressure. That is worth a badge and is not
             * worth a separate code path, which is exactly why this is one boolean.
             *
             * MIGRATION false: every photo written before importing existed came from the
             * camera, so the absent key is not merely a safe default, it is the truth.
             */
            public boolean fromGallery;
            /** Phone tilt/turn at the shutter, degrees — null for imports and old data. */
            public Double tiltDeg, turnDeg;

            /**
             * THE ALIGN TRANSFORM THIS PHOTO WAS SAVED WITH — provenance, NOT a pending
             * operation. The align stage bakes zoom/rotation/pan into the PIXELS before
             * saveFinal() writes the file, so the stored JPEG is already the aligned image
             * and nothing downstream re-applies these. They are recorded so a later screen
             * (and a later human) can answer "how much was this one pushed around", and so
             * a per-view default profile can be compared against what a photo actually
             * used. Re-applying them would double the transform, which is exactly why this
             * comment exists next to the fields rather than in a design note somewhere.
             *
             * Units are {@link Align.T}'s: zoom is a multiplier on the fit scale, rot is
             * clockwise degrees, pan is FRAME PIXELS.
             *
             * MIGRATION null, in all four: a photo written before the align stage recorded
             * its transform has no honest value here, and 1/0/0/0 would be a claim ("saved
             * unaligned") the file cannot support — it may well have been aligned, we just
             * did not write it down. Null is the truth: unknown.
             */
            public Double editZoom, editRot, editPanX, editPanY;

            /** The keys of this photo's record that this version does not know - written by
             *  a newer app - kept exactly as read and written back after the fields
             *  (JsonKeep). Null when there were none. A new photo starts with none. */
            public JSONObject unknown;

            public JSONObject toJson() throws JSONException {
                JSONObject o = new JSONObject();
                o.put("path", path == null ? "" : path);
                o.put("note", note == null ? "" : note);
                o.put("kpa", kpa == null ? "" : String.valueOf(kpa.doubleValue()));
                o.put("ts", String.valueOf(ts));
                o.put("gal", fromGallery);
                o.put("tilt", tiltDeg == null ? "" : String.valueOf(tiltDeg.doubleValue()));
                o.put("turn", turnDeg == null ? "" : String.valueOf(turnDeg.doubleValue()));
                // The align transform, encoded the way every nullable double in this file
                // is: "" for absent, so "unknown" and "1.0/0/0/0" stay different facts.
                o.put("ez", editZoom == null ? "" : String.valueOf(editZoom.doubleValue()));
                o.put("er", editRot  == null ? "" : String.valueOf(editRot.doubleValue()));
                o.put("ex", editPanX == null ? "" : String.valueOf(editPanX.doubleValue()));
                o.put("ey", editPanY == null ? "" : String.valueOf(editPanY.doubleValue()));
                // "" for unknown, the way every nullable fact in this file is written.
                o.put("held", held == null ? "" : (held.booleanValue() ? "1" : "0"));
                o.put("std", std == null ? "" : (std.booleanValue() ? "1" : "0"));
                o.put("skpa", stdKpa == null ? "" : String.valueOf(stdKpa.doubleValue()));
                o.put("hkpa", holdKpa == null ? "" : String.valueOf(holdKpa.doubleValue()));
                JsonKeep.putBack(o, unknown);   // a newer app's keys, after ours
                return o;
            }

            public static Photo fromJson(JSONObject src) {
                // Read through a Reader so the keys this version never asks for are kept.
                JsonKeep.Reader o = JsonKeep.reader(src);
                if (o == null) return null;
                Photo p = new Photo();
                p.path = o.optString("path", "");
                p.note = o.optString("note", "");
                String k = o.optString("kpa", "");
                if (k.length() > 0) {
                    try { p.kpa = Double.valueOf(Double.parseDouble(k)); }
                    catch (NumberFormatException e) { p.kpa = null; }
                } else {
                    p.kpa = null;
                }
                try { p.ts = Long.parseLong(o.optString("ts", "0")); }
                catch (NumberFormatException e) { p.ts = 0; }
                p.fromGallery = o.optBoolean("gal", false);
                p.tiltDeg = parseNullableDouble(o.optString("tilt", ""));
                p.turnDeg = parseNullableDouble(o.optString("turn", ""));
                // MIGRATION: absent on every photo written before the align transform was
                // recorded. Null, never 1/0/0/0 — see the fields' doc.
                p.editZoom = parseNullableDouble(o.optString("ez", ""));
                p.editRot  = parseNullableDouble(o.optString("er", ""));
                p.editPanX = parseNullableDouble(o.optString("ex", ""));
                p.editPanY = parseNullableDouble(o.optString("ey", ""));
                // MIGRATION: absent on every photo written before point 19 - null, unknown.
                String h = o.optString("held", "");
                p.held = "1".equals(h) ? Boolean.TRUE : "0".equals(h) ? Boolean.FALSE : null;
                // MIGRATION: absent on every photo written before the owner's rule - null,
                // unknown; Compare#photoKind then takes the reading's kind, as before.
                String sd = o.optString("std", "");
                p.std = "1".equals(sd) ? Boolean.TRUE : "0".equals(sd) ? Boolean.FALSE : null;
                p.stdKpa = parseNullableDouble(o.optString("skpa", ""));
                // MIGRATION: absent before the review's item 4 - null, pressure not recorded.
                p.holdKpa = parseNullableDouble(o.optString("hkpa", ""));
                p.unknown = o.unasked();
                return p;
            }
        }

        /* ------------------------------------------------------- comparability class */

        /** The three comparability classes (Task 19), plus the two "unknown" buckets that
         *  are not comparable to anything, including their own kind. A standardised
         *  reading with an unknown observed vacuum is CLASS_STANDARDISED_UNKNOWN; an
         *  at-rest reading with no recorded state is CLASS_AT_REST_UNKNOWN. */
        public static final int CLASS_STANDARDISED         = 0;
        public static final int CLASS_STANDARDISED_UNKNOWN = 1;
        public static final int CLASS_AT_REST_HARD         = 2;
        public static final int CLASS_AT_REST_SOFT         = 3;
        public static final int CLASS_AT_REST_UNKNOWN      = 4;

        /**
         * C8 - WHETHER THIS READING WAS STANDARDISED: a hold is recorded on it. The one test
         * every comparability decision asks ({@link #classOf}, {@link #comparable}, {@link
         * #comparabilityReason}, and the trend's hollow dots via Meas#unstdRuns).
         *
         * Not the method tag. Method 0 is also a reading whose hold was skipped or ran out
         * (filed with no hold), a Std row logged at rest, and every reading saved before the
         * method axis - none of which was taken under a served hold. And a hold
         * recorded on an at-rest method (the reading editor allows it) is still a claim
         * that pressure was on, which is not "at rest".
         */
        public static boolean isStandardised(Reading r) {
            return r != null && r.holdKpa != null;
        }

        /** The series a reading is drawn on and compared within: its at-rest method, or 0
         *  (Std) for everything else - the same fold {@link #methodLabel} makes, so an
         *  unknown int from a newer save is compared as the line it is drawn on. */
        static int tagOf(Reading r) {
            return isAtRestMethod(r.method) ? r.method : METHOD_STANDARDIZED;
        }

        /** Which class a reading belongs to. A held reading ({@link #isStandardised}) is
         *  standardised — judged on its OBSERVED vacuum, so an unknown observed makes it
         *  the not-comparable STANDARDISED_UNKNOWN. An at-rest reading is judged on its
         *  hard/soft state, so an unknown state makes it the not-comparable
         *  AT_REST_UNKNOWN. */
        public static int classOf(Reading r) {
            if (r == null) return CLASS_AT_REST_UNKNOWN;
            if (isStandardised(r))
                return r.observedKpa == null ? CLASS_STANDARDISED_UNKNOWN : CLASS_STANDARDISED;
            // C8: a real at-rest method carries its state in its name (stateForMethod), and
            // comparable() matches it on the method whatever the stored field says - so it is
            // never the UNKNOWN class that comparable() would then contradict.
            String st = isAtRestMethod(r.method) ? stateForMethod(r.method) : r.state;
            if (STATE_HARD.equals(st)) return CLASS_AT_REST_HARD;
            if (STATE_SOFT.equals(st)) return CLASS_AT_REST_SOFT;
            return CLASS_AT_REST_UNKNOWN;
        }

        /**
         * Whether two readings are directly comparable (Task 19). Three classes:
         *   - standardised: judged on the OBSERVED delivered vacuum within
         *     COMPARABLE_TOLERANCE_KPA — NOT on equality of the commanded setpoint, which
         *     is a configured constant and would make nothing comparable once observed is
         *     a real measurement. An unknown observed vacuum on either side is not
         *     agreement (defect #5's lesson): the pair is not comparable, full stop.
         *   - at rest, hard  /  at rest, soft: judged on the state. Two at-rest readings
         *     in different states are not comparable; an unknown state is not comparable
         *     to anything, including another unknown.
         * A standardised reading and an at-rest reading are never comparable — different
         * classes, the trend must not plot them as one line.
         *
         * C8 - AND NEVER ACROSS METHODS. Two readings compare only within one tag ({@link
         * #tagOf}: the at-rest method, or Std). The hard/soft STATE decides only between two
         * readings that have no method at all - those saved before the method axis. It used
         * to be the fallback for ANY two at-rest readings whose methods differed, so BPEL
         * matched NBPEL, BPSSL matched BPSL and NBPSL, and BPEL even matched MSEG: the
         * since-last strip, the row deltas and the trend's joined lines crossed protocols
         * under "taken at rest under matching conditions" (study problem 7). Every delta and
         * every line break in the app goes through this one method (Meas#periodPair,
         * Meas#sincePair, Meas#drawableEdges, Compare), so they now agree by construction;
         * ComparableClassTest pins it.
         */
        public static boolean comparable(Reading a, Reading b) {
            if (a == null || b == null) return false;
            boolean aStd = isStandardised(a), bStd = isStandardised(b);
            if (aStd != bStd) return false;                 // standardised vs at rest
            if (tagOf(a) != tagOf(b)) return false;         // C8: different methods
            if (aStd) {
                // Standardised: the delivered vacuum decides, within tolerance. Unknown
                // observed (null) is never a match — not even unknown against unknown.
                if (a.observedKpa == null || b.observedKpa == null) return false;
                return Math.abs(a.observedKpa.doubleValue() - b.observedKpa.doubleValue())
                       <= COMPARABLE_TOLERANCE_KPA;
            }
            // At rest: the same at-rest METHOD (1..7) makes a pair directly comparable —
            // the method encodes erect/soft, and the log/measure screens tag it rather than
            // asking a hard/soft state (see stateForMethod). The tags are equal by here, so
            // a real method on one side means the same real method on the other.
            if (isAtRestMethod(a.method)) return true;
            // Neither has a method: a legacy reading, saved before the method axis, lines up
            // with another by the state it recorded. Unknown is never a match.
            if (!isKnownState(a.state) || !isKnownState(b.state)) return false;
            return a.state.equals(b.state);
        }

        static boolean isKnownState(String s) {
            return STATE_HARD.equals(s) || STATE_SOFT.equals(s);
        }

        /**
         * A plain-language sentence naming why a pair IS or IS NOT directly comparable —
         * the real reason, never a generic "not comparable": different class, a different
         * observed vacuum, an unknown observed vacuum, a different method, or an unknown
         * one. Built on the same facts {@link #comparable} decides on, so the words and
         * the verdict can never drift apart.
         *
         * The at-rest branch below MIRRORS {@link #comparable}'s own branch order exactly
         * (method-match fast path, then the legacy state fallback) rather than re-deriving
         * the answer a second way — that is what keeps this method's doc promise true. The
         * two are worded no more specifically than each branch can safely claim: the
         * method-match branch names the shared method because {@link #isAtRestMethod} plus
         * an equality check guarantees it is real; every other at-rest branch stays generic
         * (no hard/soft, no method name), because by the time control reaches it the method
         * axis has already failed to decide the pair — naming a method there would either
         * be wrong (a legacy method-0 reading has none) or misleading (two different real
         * methods can still land in this branch, via the state fallback).
         */
        public static String comparabilityReason(Reading a, Reading b) {
            if (a == null || b == null) return "One of these readings is missing.";
            boolean aStd = isStandardised(a), bStd = isStandardised(b);
            if (aStd != bStd) {
                // STUDY-19 R20: two equal ways of measuring, each compared with its own.
                return "One is standardised and one is at rest: two ways of measuring, each "
                     + "compared with its own kind, so the trend draws them apart.";
            }
            // C8 - mirrors comparable()'s method test, in the same place.
            if (tagOf(a) != tagOf(b))
                return "These were taken different ways (" + methodLabel(tagOf(a)) + " and "
                     + methodLabel(tagOf(b)) + "), so they are not directly comparable — "
                     + "different protocols are different measurements, and the trend draws "
                     + "each on its own line.";
            if (aStd) {
                if (a.observedKpa == null || b.observedKpa == null)
                    return "One of these has no recorded vacuum from the pump at capture, so "
                         + "the pressure it was actually taken at is unknown — and unknown is "
                         + "not the same as a match.";
                double diff = Math.abs(a.observedKpa.doubleValue() - b.observedKpa.doubleValue());
                if (diff <= COMPARABLE_TOLERANCE_KPA)
                    return "Both were held at the same actual vacuum ("
                         + Fmt.p(a.observedKpa.doubleValue()) + " and "
                         + Fmt.p(b.observedKpa.doubleValue())
                         + ", within tolerance), so they are directly comparable.";
                return "These were held at different actual vacuums ("
                     + Fmt.p(a.observedKpa.doubleValue()) + " vs "
                     + Fmt.p(b.observedKpa.doubleValue())
                     + "), so they are not directly comparable — the hold did not reach the "
                     + "same pressure both times.";
            }
            // At rest — the method-match fast path FIRST, exactly mirroring comparable(): a
            // same real at-rest method is comparable regardless of what its (possibly stale)
            // state field says, so the text must agree before it ever looks at state. By
            // here the tags are equal, so what follows is two readings with no method.
            if (isAtRestMethod(a.method)) {
                return "Both were taken as " + methodLabel(a.method) + ", so they are "
                     + "directly comparable to each other.";
            }
            boolean aKnown = isKnownState(a.state), bKnown = isKnownState(b.state);
            if (!aKnown || !bKnown)
                return "An at-rest reading with no recorded state is not comparable to "
                     + "another — the state is unknown, not assumed to match.";
            if (a.state.equals(b.state))
                return "Both were taken at rest under matching conditions, so they are "
                     + "directly comparable to each other.";
            return "These were taken at rest under different conditions, so they are not "
                 + "directly comparable — that difference is condition, not tissue change.";
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id == null ? "" : id);
            o.put("label", label == null ? "" : label);
            o.put("ts", String.valueOf(ts));
            o.put("len", String.valueOf(len));
            o.put("cyl", cylinder);
            o.put("cylId", cylinderId == null ? "" : cylinderId);
            o.put("gir", String.valueOf(gir));
            o.put("note", note == null ? "" : note);
            o.put("holdKpa", holdKpa == null ? "" : String.valueOf(holdKpa.doubleValue()));
            o.put("holdSec", holdSec == null ? -1 : holdSec.intValue());
            // Observed (delivered) vacuum, same empty-string-means-unknown encoding as
            // holdKpa: a missing key on an OLD file reads back as null (unknown), never
            // as 0.0 — 0.0 is no measurement, never a pressure.
            o.put("observedKpa", observedKpa == null ? "" : String.valueOf(observedKpa.doubleValue()));
            // 0.10: when it was taken - written only when known (StdMoment).
            if (observedAt != null && observedAt.length() > 0) o.put("okAt", observedAt);
            o.put("state", state == null ? STATE_UNKNOWN : state);
            o.put("phase", phase);
            o.put("method", method);
            // S13 (b): written only when there is a link, so every reading without one is
            // saved exactly as it was before the link existed.
            if (pairOf != null && pairOf.length() > 0) o.put("pair", pairOf);
            o.put("photo", photo);
            o.put("edited", edited);
            // Wrapped in a one-element array, not put as a bare nested object — same
            // reason as Meas/Std above: the desktop org.json shim has optJSONArray(String)
            // but no optJSONObject(String), so this is the shape both readers can decode
            // with the same code.
            if (photoFront != null) {
                JSONArray pf = new JSONArray();
                pf.put(photoFront.toJson());
                o.put("photoFront", pf);
            }
            if (photoSide != null) {
                JSONArray ps = new JSONArray();
                ps.put(photoSide.toJson());
                o.put("photoSide", ps);
            }
            if (photoTop != null) {
                JSONArray pt = new JSONArray();
                pt.put(photoTop.toJson());
                o.put("photoTop", pt);
            }
            JsonKeep.putBack(o, unknown);   // a newer app's keys, after ours
            return o;
        }

        /**
         * `ts`/`len`/`gir` are read back via optString()+parse, not optInt/optDouble:
         * the desktop org.json shim has neither optLong nor optDouble (see Meas's
         * fromJson note above), so this stays on the surface both readers share —
         * the desktop self-test then actually exercises the same code path the phone
         * runs, rather than a parallel one that only looks equivalent.
         */
        public static Reading fromJson(JSONObject src) {
            Reading r = new Reading();
            // Read through a Reader so the keys this version never asks for are kept.
            JsonKeep.Reader o = JsonKeep.reader(src);
            if (o == null) return r;
            r.id = o.optString("id");
            r.label = o.optString("label", "");
            try { r.ts = Long.parseLong(o.optString("ts", "0")); }
            catch (NumberFormatException e) { r.ts = 0; }
            // A4 - absent means -1: taken in some cylinder, and nobody knows which.
            r.cylinder = o.optInt("cyl", -1);
            // Absent on every file written before cylinders had ids; Model#linkReadingCylinders
            // backfills it from the index once the cylinder list is loaded.
            r.cylinderId = o.optString("cylId", "");
            try { r.len = Double.parseDouble(o.optString("len", "0")); }
            catch (NumberFormatException e) { r.len = 0; }
            try { r.gir = Double.parseDouble(o.optString("gir", "0")); }
            catch (NumberFormatException e) { r.gir = 0; }
            r.note = o.optString("note", "");
            String hk = o.optString("holdKpa", "");
            if (hk.length() > 0) {
                try { r.holdKpa = Double.valueOf(Double.parseDouble(hk)); }
                catch (NumberFormatException e) { r.holdKpa = null; }
            } else {
                r.holdKpa = null;
            }
            int hs = o.optInt("holdSec", -1);
            r.holdSec = (hs < 0) ? null : Integer.valueOf(hs);
            // Absent on every reading logged before Task 19: no "observedKpa" key ->
            // "" -> null (UNKNOWN), NOT backfilled from holdKpa; no "state" key ->
            // STATE_UNKNOWN. Both migrate to not-comparable-to-either, never a guess.
            String ok = o.optString("observedKpa", "");
            if (ok.length() > 0) {
                try { r.observedKpa = Double.valueOf(Double.parseDouble(ok)); }
                catch (NumberFormatException e) { r.observedKpa = null; }
            } else {
                r.observedKpa = null;
            }
            // MIGRATION: absent on every reading saved before 0.10 - null, the value above
            // kept exactly as it was stored (it was taken at Save).
            String okAt = o.optString("okAt", "");
            r.observedAt = okAt.length() > 0 ? okAt : null;
            r.state = o.optString("state", STATE_UNKNOWN);
            // MIGRATION: absent in every reading logged before the pre/post axis existed —
            // PHASE_UNKNOWN, which the trend draws on the PRE line. See the field's doc for
            // why that continuity is the right default rather than a third line.
            r.phase = o.optInt("phase", PHASE_UNKNOWN);
            // MIGRATION: absent in every reading logged before the method axis existed —
            // METHOD_STANDARDIZED (0). See the field's doc for the legacy-standardised
            // assumption this default encodes; there is no separate legacy sentinel.
            r.method = o.optInt("method", METHOD_STANDARDIZED);
            // MIGRATION: absent on every reading saved before S13 (b) - no link, pairs by day.
            r.pairOf = o.optString("pair", "");
            r.photo = o.optBoolean("photo");
            r.edited = o.optBoolean("edited");
            JSONArray pfA = o.optJSONArray("photoFront");
            r.photoFront = (pfA != null && pfA.length() > 0)
                          ? Photo.fromJson(pfA.optJSONObject(0)) : null;
            JSONArray psA = o.optJSONArray("photoSide");
            r.photoSide = (psA != null && psA.length() > 0)
                         ? Photo.fromJson(psA.optJSONObject(0)) : null;
            JSONArray ptA = o.optJSONArray("photoTop");
            r.photoTop = (ptA != null && ptA.length() > 0)
                        ? Photo.fromJson(ptA.optJSONObject(0)) : null;
            r.unknown = o.unasked();
            return r;
        }
    }

    /* -------------------------------------------------------- then-vs-now pick */

    /**
     * WHICH TWO PHOTOS the Progress "then vs now" card pairs — the user's choice, persisted.
     *
     * WHAT IT REPLACES. The card showed the FIRST and the LATEST photo of the most-
     * photographed view and nothing else. That is the right default and the wrong only
     * option: once a library is a year deep, "first" is a photo taken under different
     * lighting, at a different distance, before the user knew how to frame one — so the
     * card's headline comparison is against the worst photo in the library, and the
     * comparison a person actually wants ("me now against six months ago") cannot be asked
     * for at all.
     *
     * THE REPRESENTATION. A mode plus an id per side, never two nullable ids: a mode says
     * WHY a photo is on the card, so "six months ago" keeps meaning six months ago as time
     * passes and new photos arrive, rather than freezing onto whichever photo happened to
     * be nearest six months ago on the day the choice was made. An explicitly PICKED photo
     * is the one case where an id is the whole meaning, and it carries one.
     *
     * MIGRATION. Absent from every save written before this existed — {@link #fromJson}
     * (null) is FIRST versus LATEST, which is exactly what the card did before, so an
     * upgrading phone's card does not move. A mode string that is not one of the constants
     * below (a hand-edited file) falls back to the same defaults rather than resolving to
     * nothing: an unreadable preference must degrade to the documented behaviour, never to
     * an empty card.
     */
    public static final class ThenNowPick {
        /** LEFT ("then") modes. The three "ago" modes are resolved against the clock at
         *  render time by {@link Compare#resolvePick}, never stored as a date. */
        public static final String LEFT_FIRST = "first";
        public static final String LEFT_1Y    = "1y";
        public static final String LEFT_6M    = "6m";
        public static final String LEFT_3M    = "3m";
        public static final String LEFT_PICK  = "pick";
        /** RIGHT ("now") modes. Only two: the latest photo, or one named outright. */
        public static final String RIGHT_LATEST = "latest";
        public static final String RIGHT_PICK   = "pick";

        /** How many days back each "ago" mode aims at. Whole days of 86400000 ms, the same
         *  convention MeasLog.window and Compare.daysBetween already use — so "6 months
         *  ago" means the same span here as a 6M period does on the Progress screen. */
        public static final int DAYS_1Y = 365, DAYS_6M = 182, DAYS_3M = 91;

        public String left = LEFT_FIRST;
        public String leftId;                 // meaningful only when left == LEFT_PICK
        public String right = RIGHT_LATEST;
        public String rightId;                // meaningful only when right == RIGHT_PICK

        public ThenNowPick copy() {
            ThenNowPick p = new ThenNowPick();
            p.left = left; p.leftId = leftId; p.right = right; p.rightId = rightId;
            return p;
        }

        /** True when this is the untouched default — first versus latest. What the card
         *  shipped as, and what an old save migrates to. */
        public boolean isDefault() {
            return LEFT_FIRST.equals(left) && RIGHT_LATEST.equals(right);
        }

        /** The days-back an "ago" mode names, or -1 when this mode is not an "ago" one. */
        public static int daysAgo(String mode) {
            if (LEFT_1Y.equals(mode)) return DAYS_1Y;
            if (LEFT_6M.equals(mode)) return DAYS_6M;
            if (LEFT_3M.equals(mode)) return DAYS_3M;
            return -1;
        }

        /** The chooser's own label for a mode — the words on the button, so the card's
         *  caption and the chooser row can never name the same choice differently. */
        public static String leftLabel(String mode) {
            if (LEFT_1Y.equals(mode)) return "1 year ago";
            if (LEFT_6M.equals(mode)) return "6 months ago";
            if (LEFT_3M.equals(mode)) return "3 months ago";
            if (LEFT_PICK.equals(mode)) return "pick a photo…";
            return "first";
        }

        public static String rightLabel(String mode) {
            return RIGHT_PICK.equals(mode) ? "pick a photo…" : "latest";
        }

        private static String leftOr(String mode) {
            if (LEFT_FIRST.equals(mode) || LEFT_1Y.equals(mode) || LEFT_6M.equals(mode)
                    || LEFT_3M.equals(mode) || LEFT_PICK.equals(mode)) return mode;
            return LEFT_FIRST;
        }

        private static String rightOr(String mode) {
            return RIGHT_PICK.equals(mode) ? RIGHT_PICK : RIGHT_LATEST;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("l", left == null ? LEFT_FIRST : left);
            o.put("lid", leftId == null ? "" : leftId);
            o.put("r", right == null ? RIGHT_LATEST : right);
            o.put("rid", rightId == null ? "" : rightId);
            return o;
        }

        /** A null object — an old save with no "thenNow" key at all — is the DEFAULT, which
         *  is the behaviour the card already had. An empty id string reads back as null,
         *  the same "" -means-absent encoding every optional id in this file uses. */
        public static ThenNowPick fromJson(JSONObject o) {
            ThenNowPick p = new ThenNowPick();
            if (o == null) return p;
            p.left = leftOr(o.optString("l", LEFT_FIRST));
            p.right = rightOr(o.optString("r", RIGHT_LATEST));
            String lid = o.optString("lid", "");
            p.leftId = lid.length() == 0 ? null : lid;
            String rid = o.optString("rid", "");
            p.rightId = rid.length() == 0 ? null : rid;
            return p;
        }
    }

    /* -------------------------------------------------------------- meas log */

    /**
     * The baseline measurement log, NEWEST FIRST — ~25 sites across this app (the chart,
     * the goal maths, the readings list, Compare, Gallery, the CSV/PDF export) trust that
     * ordering rather than re-deriving it themselves.
     *
     * TASK 4: this used to be true only because every write happened to occur in real
     * time (insertion order and ts order were the same thing by construction). A custom-
     * dated log entry, or an edited ts on a saved reading, falsifies that — so the
     * invariant is now ENFORCED, not assumed: {@link #resort} runs after every write that
     * could disturb it — {@link #log}, {@link #fromJson} (a restored backup or a hand-
     * edited model.json can carry any order at all), and the reading editor's ts commit
     * (SaveMeasTap) — rather than being left as a standing assumption about who calls
     * what. The sort is STABLE and descending by ts ONLY, no secondary key (see
     * {@link #resort}'s own doc for why a second key would be actively dangerous here).
     */
    public static final class MeasLog {
        public final List<Reading> all = new ArrayList<Reading>();

        /** Descending by ts, nothing else — the ONE comparator every write path shares.
         *  No secondary key on purpose: at(), latestPre(), PhotoCalendar.cells()' "newest
         *  wins", Compare.thenVsNow()'s "now", and Gallery.byDay()'s within-day order all
         *  resolve a tie (a same-ts multi-protocol sitting) by first-index, and a second
         *  key would silently re-target every one of them. Named, not a lambda (no
         *  lambdas anywhere in this app). */
        private static final class NewestFirst implements Comparator<Reading> {
            @Override public int compare(Reading a, Reading b) {
                return Long.compare(b.ts, a.ts);
            }
        }

        /** Restores newest-first-by-ts order — called after any write that could have
         *  disturbed it. STABLE (java.util.Collections.sort / TimSort is guaranteed
         *  stable), which is not a nicety here: a multi-protocol sitting files N readings
         *  sharing one identical ts (see Meas#buildLogReadings), and an UNSTABLE sort (or
         *  a secondary key) would make their relative order — which one "wins" a same-ts
         *  tie in at(), latestPre(), etc. — re-shuffle on every load, since the list is
         *  re-read from JSON and re-sorted every time the app starts. That would be a
         *  worse bug than insertion-order-as-ordering ever was: today's order is at least
         *  deterministic. */
        public void resort() {
            Collections.sort(all, new NewestFirst());
        }

        /** The most recent reading, or null when nothing has been logged yet —
         *  mirrors the prototype's baseNow(). A log holding exactly one reading must
         *  return it, not be mistaken for empty (bug class: a count is not a value —
         *  a truthy check on .length is the edge this guards). */
        public Reading latest() { return all.isEmpty() ? null : all.get(0); }

        /**
         * THE NEWEST READING THAT IS NOT A POST-SESSION ONE — the "where am I now" baseline,
         * as every screen that asks that question has always meant it.
         *
         * WHY THIS EXISTS. Post readings are taken minutes after a session, with the
         * swelling still present, so they are systematically larger than the same body
         * measures cold. The instant post readings started being logged, plain {@link
         * #latest} would have become "the post reading from the last session" for most
         * users — and every consumer of it (the after-measurement's "before", the log-a-
         * reading steppers' seed, "Last logged", the goal's distance-to-go) would silently
         * have started answering from the swollen number. That is not a chart bug; it is
         * every number in the app quietly moving up on the day of an update.
         *
         * So the split is explicit: the CHART reads the whole log and separates the two
         * lines itself, and everything that means "my current measurement" reads this.
         * PHASE_UNKNOWN counts as pre here for the same continuity reason the chart draws
         * it on the pre line — see {@link Reading#PHASE_UNKNOWN}.
         *
         * Null when the log holds nothing but post readings, which is the honest answer:
         * there is no cold baseline to speak from yet.
         */
        public Reading latestPre() {
            for (int i = 0; i < all.size(); i++)
                if (all.get(i).phase != Reading.PHASE_POST) return all.get(i);
            return null;
        }

        /** The reading logged at exactly `ts`, or null when no such reading exists (any
         *  more). A Sess records WHICH baseline its after-delta was measured against;
         *  the summary's before/after card looks that reading up HERE rather than
         *  trusting that latest() is still the same one — a reading logged or deleted
         *  since would otherwise be shown as the "before". Null means "baseline no
         *  longer available", never a silent fallback to whichever reading is newest
         *  now. 0 (the "not recorded" value) finds nothing.
         *
         *  NOT A UNIQUE KEY (TASK 4 / PD-5): a multi-protocol sitting files N readings
         *  sharing one identical ts (Meas#buildLogReadings) — this returns whichever one
         *  happens to sit first, which is "correct" only by the accident that the writer
         *  (attachAssessment, via latestPre()) and this reader agree on "first" the same
         *  way. It is also NOT safe across a ts edit: correcting one member of a sitting's
         *  date does not move it out of a tie it was never guaranteed to win in the first
         *  place. {@link Sess#afterBaseId} is the identity-safe replacement — see its own
         *  doc — and every current caller prefers it, falling back to this method only
         *  for a session filed before that field existed. Prefer {@link #byId} for any
         *  NEW lookup that needs to survive a reading's ts changing. */
        public Reading at(long ts) {
            if (ts <= 0) return null;
            for (int i = 0; i < all.size(); i++)
                if (all.get(i).ts == ts) return all.get(i);
            return null;
        }

        /** The reading with exactly this id, or null when none exists (logged or
         *  deleted since) — the identity-safe counterpart to {@link #at}. `id` is unique
         *  per reading (assigned once at capture, in SessionActivity, and never re-
         *  assigned by the editor's copy()), so unlike {@link #at} this is safe to hold
         *  across a ts edit: {@link Sess#afterBaseId} uses this, not {@link #at}, for
         *  exactly that reason (Task 4 / PD-5). */
        public Reading byId(String id) {
            if (id == null || id.length() == 0) return null;
            for (int i = 0; i < all.size(); i++)
                if (id.equals(all.get(i).id)) return all.get(i);
            return null;
        }

        /** Readings whose age is at most `days`, inclusive of the boundary — a
         *  reading exactly `days` old counts as inside the window. Uses the real
         *  clock; see the (days, now) overload for a deterministic variant. */
        public List<Reading> window(int days) {
            return window(days, System.currentTimeMillis());
        }

        /** Same filter, against an explicit reference time instead of the wall
         *  clock, so the inclusive boundary can be asserted deterministically —
         *  without this, a test would race System.currentTimeMillis() moving
         *  between setting a reading's age and this method computing its own "now". */
        public List<Reading> window(int days, long now) {
            List<Reading> out = new ArrayList<Reading>();
            long cutoff = now - (long) days * 86400000L;
            for (int i = 0; i < all.size(); i++)
                if (all.get(i).ts >= cutoff) out.add(all.get(i));
            return out;
        }

        /** {length delta, girth delta} = newest minus oldest reading inside the
         *  window (both newest-first, so index 0 and the last index). {0,0} when
         *  the window holds fewer than two readings — there is nothing to diff yet. */
        public double[] delta(int days) { return delta(days, System.currentTimeMillis()); }

        public double[] delta(int days, long now) {
            List<Reading> w = window(days, now);
            if (w.size() < 2) return new double[]{0, 0};
            Reading newest = w.get(0), oldest = w.get(w.size() - 1);
            return new double[]{ newest.len - oldest.len, newest.gir - oldest.gir };
        }

        /**
         * Whether a measurement is due, per the cadence config — mirrors the
         * prototype's measDue(): "off" never asks, "every" always asks,
         * "sessions"/"hours" ask once the matching progress counter has caught up
         * with its target (>=, not ==, so a counter that overshoots the target —
         * e.g. after being left unattended — still reads as due, not skipped).
         */
    /** The cadence mode the length tier needs: the 1st and 4th session of a training week.
     *  A MODE the user can choose, never one the app switches to on their behalf - the
     *  length track needing pairs is a reason to offer this, not a licence to impose it. */
        public static final String MEAS_WEEK_14 = "week14";

        public boolean due(Meas cfg) { return due(cfg, -1); }

        /**
         * @param sessionsThisWeek how many sessions have already been logged in the current
         *        training week, or -1 when the caller cannot say. Only {@link #MEAS_WEEK_14}
         *        reads it; every other mode ignores it, so the one-argument form above stays
         *        correct for every caller that has no week to count.
         */
        public boolean due(Meas cfg, int sessionsThisWeek) {
            if (cfg == null) return false;
            if ("off".equals(cfg.mode))      return false;
            if ("every".equals(cfg.mode))    return true;
            if ("sessions".equals(cfg.mode)) return cfg.sinceN >= cfg.n;
            if (MEAS_WEEK_14.equals(cfg.mode)) {
                /* THE FIRST AND FOURTH SESSION OF A TRAINING WEEK.
                 *
                 * The length signals are differences between a session's before and after,
                 * so they need PAIRS, and they need them at comparable points in a week -
                 * a pair from a fresh Monday and one from a fourth consecutive day are not
                 * measuring the same tissue. Asking every session buries the signal in
                 * noise; asking every N sessions lands wherever the count happens to fall.
                 *
                 * Unknown week position is NOT due: a caller that cannot count the week has
                 * no business claiming this is the first session of it.
                 */
                return sessionsThisWeek == 0 || sessionsThisWeek == 3;
            }
            return cfg.sinceH >= cfg.hours;                       // "hours"
        }

        /**
         * Inserts newest-first and resets the cadence counters on `cfg` — a logged
         * reading is the new baseline "since" is measured from. `cfg` is passed in
         * rather than captured at construction: Model.fromJson() replaces `meas`
         * wholesale on every load, so a reference stored once at construction could
         * end up resetting a Meas object that is no longer the one actually
         * installed on the Model.
         */
        public void log(Reading r, Meas cfg) {
            all.add(0, r);
            resort();
            if (cfg != null) { cfg.sinceN = 0; cfg.sinceH = 0; }
        }

        public JSONArray toJson() throws JSONException {
            JSONArray a = new JSONArray();
            for (int i = 0; i < all.size(); i++) a.put(all.get(i).toJson());
            return a;
        }

        /** A restored backup or a hand-edited model.json can carry readings in any
         *  order at all (Task 4) — this used to trust the file verbatim on the
         *  documented assumption that whatever wrote it always wrote newest-first;
         *  resort() makes that true rather than assumed. */
        public static MeasLog fromJson(JSONArray a) {
            MeasLog log = new MeasLog();
            if (a != null) for (int i = 0; i < a.length(); i++)
                log.all.add(Reading.fromJson(a.optJSONObject(i)));
            log.resort();
            return log;
        }
    }

    /* ------------------------------------------------------ standardisation */

    /**
     * A single short pull to a fixed pressure, run right before a measurement (both
     * ends of a session), so the tissue is in the same state every time it is measured
     * or photographed. Computes nothing — it just makes the tape measure comparable.
     */
    public static final class Std {
        public boolean on = true;
        public int kpa = 20;
        public int sec = 30;
        /** "Photo during the hold": the camera opens as a served hold hands over to its
         *  capture screen (SessionActivity#photoDuringHold). OFF unless the person turns it
         *  on (the owner's decision, 2026-09-24); existing installs are switched off once -
         *  see {@link Model#stdPhotoOffDone}. */
        public boolean photo = false;
        public int release = 5;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("on", on); o.put("kpa", kpa); o.put("sec", sec);
            o.put("photo", photo); o.put("release", release);
            return o;
        }

        public static Std fromJson(JSONObject o) {
            Std s = new Std();
            if (o == null) return s;
            s.on = o.optBoolean("on", true);
            s.kpa = o.optInt("kpa", 20);
            s.sec = o.optInt("sec", 30);
            s.photo = o.optBoolean("photo", false);
            s.release = o.optInt("release", 5);
            return s;
        }
    }

    /* ----------------------------------------------- tissue adaptation assessment */

    /**
     * The per-routine TISSUE ADAPTATION ASSESSMENT (Task 15): an identical short pull
     * run before and after the work, so the two rise curves are comparable. Comparing
     * them yields tau — see Tau.java, which owns every number derived from it.
     *
     * NOT the standardisation hold (Model.Std). That one returns nothing; it
     * standardises the USER so a tape measure is repeatable. This one measures the pump
     * curve and returns a number. Confusing them means reading a pump measurement as a
     * tissue measurement, so they are deliberately separate types with separate copy.
     *
     * ONE set of numbers drives BOTH ends by construction — there is no separate
     * before/after configuration — because a tau comparison across two different
     * stimuli is meaningless.
     *
     * `kpa` is WHOLE kPa like every other pressure in this model. The prototype's
     * stepper keeps a tenth (`Math.round(x*10)/10`), but Proto.addPreset puts whole kPa
     * on the wire: storing 23.4 would print a pressure the pump can never be commanded
     * to, which is the same bug class as a printed range that is not the range enforced.
     * The RANGE is the prototype's exactly — 5 kPa to min(57, ceiling).
     */
    /**
     * A CYLINDER YOU ACTUALLY OWN (F18).
     *
     * The app knew the pressure it commanded and nothing about the vessel that pressure was
     * applied to, so the standardised photo's alignment guide had to be a generic tube and a
     * change of cylinder silently broke a photo series that looked continuous.
     *
     * The BORE is the inside diameter \u2014 the number that matters, and the only one a photo
     * can be scaled against, because it is the one object of known size in the frame.
     * Usable length bounds the guide's height. Both in CENTIMETRES internally, like every
     * other body measurement in this model; the display unit converts at the edge.
     *
     * Kept as a short list with one active rather than a single set of numbers: people own
     * two or three and switch between them, and a reading that cannot say which one it was
     * taken in cannot be compared with one taken in another.
     */
    public static final class Cylinder {
        /**
         * A STABLE IDENTITY, because position is not one.
         *
         * Cylinders were addressed by their index in {@link Model#cylinders}. Deleting one
         * shifts every index after it, so a reading (or a stage, or a pressure rule) that
         * said "cylinder 1" silently came to mean a different tube — the exact failure this
         * class's own note about comparing readings is there to prevent. An id survives a
         * deletion: the reference becomes MISSING, which the app can see and say something
         * about, rather than quietly resolving to the wrong cylinder.
         *
         * Empty on every file written before ids existed; {@link Model#ensureCylinderIds} mints
         * one for each on load, in list order, so an upgrading rack keeps its identities.
         */
        public String id = "";
        public String label = "";
        public double boreCm;
        public double lengthCm;

        /**
         * WHAT THE PERSON USES IT FOR - {@link #ROLE_GIRTH} or {@link #ROLE_LENGTH} (the owner,
         * 2026-09-30). A cylinder marked for length is the length track's: it pulls straight
         * away, and its bore is what the load and the pressure are converted at
         * (Traction#loadLbAtBore). It used to be read off the size against a logged erect
         * girth (Traction#fit), so nothing pulled until a girth was logged and a tube could
         * stop pulling as the girth moved; the fit is now a warning only (Model#tractionFit).
         *
         * MIGRATION: absent on every file written before it existed - a cylinder whose name
         * says "length" is a length cylinder, everything else girth (#defaultRole), which is
         * what the first-run setup's quick names write.
         */
        public String role = ROLE_GIRTH;

        public static final String ROLE_GIRTH = "girth", ROLE_LENGTH = "length";

        /** TRANSIENT: this cylinder's role was read from its name on load, the file having none
         *  (#fromJson) - so the upgrade may still mark it for length by the old pulling rule
         *  (Model#roleFromOldPullRule). Never saved: the role it ends with is. */
        public boolean roleFromName;

        public Cylinder() { }
        public Cylinder(String label, double boreCm, double lengthCm) {
            this.label = label == null ? "" : label;
            this.boreCm = boreCm;
            this.lengthCm = lengthCm;
            this.role = defaultRole(this.label);
        }

        /** The role a cylinder with this name and no saved role gets: "length" anywhere in the
         *  name (any case) is a length cylinder, anything else girth. */
        public static String defaultRole(String label) {
            return label != null && label.toLowerCase(Locale.US).contains("length")
                ? ROLE_LENGTH : ROLE_GIRTH;
        }

        public boolean isLength() { return ROLE_LENGTH.equals(role); }

        /** Bounded to what a real cylinder can be, so a typo cannot draw an absurd guide. */
        public void clamp() {
            if (label == null) label = "";
            if (!ROLE_GIRTH.equals(role) && !ROLE_LENGTH.equals(role)) role = defaultRole(label);
            if (boreCm < 1.0) boreCm = 1.0;
            if (boreCm > 15.0) boreCm = 15.0;
            if (lengthCm < 5.0) lengthCm = 5.0;
            if (lengthCm > 60.0) lengthCm = 60.0;
        }

        private static double parseD(String v, double fallback) {
            if (v == null || v.length() == 0) return fallback;
            try { return Double.parseDouble(v); }
            catch (NumberFormatException e) { return fallback; }
        }

        public String line() {
            return Fmt.len(boreCm) + " bore \u00b7 " + Fmt.len(lengthCm) + " long";
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", label);
            o.put("bore", String.valueOf(boreCm));
            o.put("len", String.valueOf(lengthCm));
            o.put("role", role);
            return o;
        }

        public static Cylinder fromJson(JSONObject o) {
            Cylinder c = new Cylinder();
            // Absent on every file written before ids existed. Left empty here and minted by
            // Model#ensureCylinderIds once the whole list is loaded, so the ids it hands out
            // cannot collide with one already in the file.
            c.id = o.optString("id", "");
            c.label = o.optString("label", "");
            // optString + parse, not optDouble: the desktop org.json shim has no
            // optDouble, and the self-test must exercise the SAME path the phone runs
            // rather than a parallel one that only looks equivalent (see Reading#fromJson).
            c.boreCm = parseD(o.optString("bore", ""), 5.0);
            c.lengthCm = parseD(o.optString("len", ""), 23.0);
            // Absent before roles existed: read from the name (#defaultRole) - and, once the
            // whole file is read, from the old pulling rule as well (Model#roleFromOldPullRule).
            String saved = o.optString("role", "");
            c.roleFromName = saved.length() == 0;
            c.role = c.roleFromName ? defaultRole(c.label) : saved;
            c.clamp();
            return c;
        }
    }

    public static final class Assess {
        public static final String WHEN_BEFORE = "before";
        public static final String WHEN_AFTER  = "after";
        public static final String WHEN_BOTH   = "both";

        /**
         * M4 - OFF BY DEFAULT FOR NEW ROUTINES ONLY (the owner's decision on item 13).
         *
         * A routine made from now on - in Library, by the Trainer, a manual run, the hardware
         * self-test, a fresh install's samples - starts with the test off: two extra pulls and
         * about 1:30 a session are the person's to choose. A routine that already exists keeps
         * what it had: fromJson reads a saved block (a file, a backup, a share code) exactly as
         * it was written, and a routine saved before the test existed loads as it always did.
         * Every guard on the pulls that do run - the ceiling clamp at write time, the arming
         * rules, the vent, STOP - is untouched.
         */
        public boolean on = false;
        public int kpa = 20;
        public int sp = 60;
        public int dur = 45;
        public String when = WHEN_BOTH;

        public Assess() { }

        /** Defaults for a routine that has never had one, respecting the ceiling —
         *  the prototype's newAssess(). */
        public static Assess fresh(int ceilKpa) {
            Assess a = new Assess();
            a.kpa = Math.min(20, ceilKpa);
            a.clamp(ceilKpa);
            return a;
        }

        public Assess copy() {
            Assess c = new Assess();
            c.on = on; c.kpa = kpa; c.sp = sp; c.dur = dur; c.when = when;
            return c;
        }

        /**
         * The prototype's asF() clamps, applied at WRITE time and not only at edit
         * time: a stored pressure can become over-ceiling because the CEILING moved,
         * not because anyone typed it. Model#clampAll calls this for every routine,
         * exactly as it already does for every set and for the standardisation hold.
         */
        public void clamp(int ceilKpa) {
            kpa = Math.max(5, Math.min(Math.min(57, ceilKpa), kpa));
            sp  = Math.max(5, Math.min(100, sp));
            dur = Math.max(15, Math.min(180, dur));
            if (!WHEN_BEFORE.equals(when) && !WHEN_AFTER.equals(when)) when = WHEN_BOTH;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("on", on); o.put("kpa", kpa); o.put("sp", sp);
            o.put("dur", dur); o.put("when", when);
            return o;
        }

        /** A null object — a routine saved before this existed — takes the defaults,
         *  which is what makes an old file load rather than lose its routines.
         *
         *  M4: EXACTLY AS SAVED. "on" is always written, so a saved block loads with its own
         *  setting; only a routine made new starts off (the field's default). A routine
         *  saved before the test existed, or a block with no "on", loads ON - what it always
         *  loaded as - because an upgrade switches nothing off. A "v" key an interim build
         *  wrote is ignored. */
        public static Assess fromJson(JSONObject o) {
            Assess a = new Assess();
            a.on = true;                         // M4: no block at all loads as it always did
            if (o == null) return a;
            a.on = o.optBoolean("on", true);
            a.kpa = o.optInt("kpa", 20);
            a.sp = o.optInt("sp", 60);
            a.dur = o.optInt("dur", 45);
            a.when = o.optString("when", WHEN_BOTH);
            return a;
        }
    }

    /* ---------------------------------------------------------------- stage */

    /**
     * A stage is a named, coloured GROUP of sets inside a routine — grouping only, it
     * changes nothing the pump does. It organises the routine and colours the run
     * screen; mirrors the prototype's stage objects ({name, c, sets}).
     *
     * `colour` is a plain ARGB int, not an android resource id or a CSS custom-property
     * name — Model stays free of android.* imports so the desktop self-test can exercise
     * it. The three values below are the prototype's --st-warm/--st-work/--st-cool.
     */
    public static final class Stage {
        public String name;
        public int colour;
        public final List<String> setIds = new ArrayList<String>();

        /**
         * THIS STAGE IS A REST — "wait here, with the cuff vented, for this long".
         *
         * REST MOVED UP A LEVEL. It used to be a flavour of SET ({@link Set#rest}), which
         * put it in the library alongside the things that actually command pressure and
         * made "add a two-minute gap between the warm-up and the work" a trip through the
         * set picker. A rest is a property of the ROUTINE'S SHAPE, not a program the pump
         * can run, so it now sits where the shape is: a stage, in the same ordered list, so
         * reorder, delete and every rail that already draws stages get it for free.
         *
         * A rest stage holds NO SETS — {@link #setIds} stays empty and the editor offers no
         * way to put one in. Its only two facts are its name and {@link #restSec}, and
         * {@link Model#plan} expands it into exactly one rest preset.
         *
         * MIGRATION: false. There is no shape of old data that could have meant "rest
         * stage", so there is nothing to infer. Sets saved with {@link Set#rest} keep
         * working exactly as they did — the set picker simply no longer offers to make
         * new ones.
         */
        public boolean rest;
        /** How long a rest stage waits, in seconds. Meaningless while {@link #rest} is
         *  false. Bounded 30..3600 by {@link #clampRest}, the same bounds a rest SET's
         *  duration has always had, so the two kinds of rest cannot disagree about what a
         *  legal length is. */
        public int restSec = 120;

        /**
         * STAGE H TASK 2 — THIS STAGE IS THE FATIGUE BLOCK (the guidance, L3+): a
         * separately-counted block whose frames must NOT feed the Net-TUP gate metric
         * (plan round-3 ruling; {@link Plan#fatigueBlockPresent} is the engine's own
         * query for which LEVELS carry one). Set by Task 4's mint-generation on a
         * trainer-originated routine's fatigue stage; read at the per-frame recording
         * call site ({@code SessionActivity#recordHoldFrame}) exactly the way {@link
         * #rest}/{@link #colour} are already read there, and consumed by {@link
         * Session#noteHoldFrame}'s Net-TUP accumulator. Meaningless — and always false —
         * on a stage nothing marked, which is every stage in the app before this field
         * existed and every stage in a routine the user built themselves.
         *
         * MIGRATION: false. There is no shape of old data that could have meant "this
         * is the fatigue block" — nothing to infer, same rule {@link #rest}'s own doc
         * states for itself.
         */
        public boolean fatigueBlock;

        /**
         * A RETENTION STAGE - the shallow hold after the work, at roughly 3-5 inHg, held at
         * about the pressure a natural erection sits at rather than let go to nothing.
         *
         * IT MUST NOT FEED NET TUP, and that is the whole reason it is a flag rather than
         * just another stage colour. Net counts every frame at or above the LEVEL FLOOR, and
         * L1's floor is 5 inHg - exactly the top of the retention band. Without this, a
         * ten-minute retention would add ten minutes of "net at pressure" to a session that
         * did no more work, and the gate that decides when you move up would be reading a
         * number the plan never asked you to earn.
         *
         * The mechanism already existed for the fatigue block, which is excluded for the
         * same kind of reason (the guide: fatigue "does not feed the gate metric"), so this
         * joins it rather than growing a second parallel exclusion - see {@link #outOfNet}.
         *
         * MIGRATION: false. No routine written before this had one.
         */
        public boolean retention;

        /**
         * WHICH CYLINDER THIS STAGE IS RUN IN - "" meaning any, which is the truth about every
         * stage written before the rack existed and about every stage that genuinely does not
         * care.
         *
         * A length session swaps tubes partway through (traction blocks in the [L] tube, the
         * expansion coda in the [G] one), so "which cylinder" is a property of the STAGE, not
         * of the routine. An id rather than an index, for the same reason readings carry one:
         * deleting a cylinder must leave this naming something MISSING, never silently
         * resolving to whichever tube moved into that position.
         */
        public String cylinderId = "";

        /**
         * A TIMED STAGE THAT COMMANDS NOTHING - the tunica release that opens a length
         * session, where the work is done by hand and the app's only job is to count the
         * time.
         *
         * IT MUST NEVER REACH THE PUMP. That is the whole point of the flag rather than a
         * zero-pressure set: a set with no pressure still walks the write path, still opens
         * a preset table, still has a value somebody could edit upward. A manual stage has
         * no path to {@link Proto} at all, and WiringCheck holds that open.
         *
         * MIGRATION: false. Nothing written before this could have meant it.
         */
        public boolean manual;

        /**
         * A TRACTION STAGE - a length HOLD, at whatever pressure produces the governed load.
         *
         * Traction sets are holds, not cycles: the drop-and-repump is a girth technique, and
         * a cycled traction set is a girth set wearing a length label. WiringCheck holds
         * that open too (a traction stage's low-hold must be zero).
         *
         * Out of net by rule - see {@link #outOfNet}.
         *
         * MIGRATION: false, like every flag beside it.
         */
        public boolean traction;

        /**
         * THIS STAGE DOES NOT END ON A CLOCK - it ends when the user says it has.
         *
         * The changeover between the traction tube and the girth tube is the only stage in
         * the app whose completion the app cannot observe. It commands nothing, so there is
         * no telemetry that says the swap happened, and advancing on a timer means commanding
         * the expansion coda's pressure into whichever cylinder is fitted - which is exactly
         * the thing the stage exists to prevent.
         *
         * ONLY LEGAL ON A REST. Every reader below tests `rest && awaitAck`, so a flag set on
         * a stage that commands something can never gate a run; that is asserted rather than
         * clamped, because a clamp would silently repair a routine that is wrong.
         *
         * MIGRATION: false. No stage written before this waited for anything, and a routine
         * saved before the length tier decodes to exactly today's behaviour.
         */
        public boolean awaitAck;

        /**
         * A RAMP'S CLIMB THE PERSON DOES NOT COUNT (0.10, the owner's ramp decisions): the
         * holds a Ramped block climbs through before it reaches the working pressure, laid
         * out as a stage of their own when "only holds at the working pressure count" is
         * chosen (Model#rampCountClimb off). They command pressure like any work - the pump
         * does exactly what it would - but they are not training volume by that choice, so
         * net leaves them out, exactly as it leaves out a warm-up (see {@link #outOfNet}).
         * With the climb counted (the default) the climb sits in the block's own work stage
         * and this is never set.
         *
         * MIGRATION: false. No stage written before this was a climb anybody left uncounted.
         */
        public boolean climb;

        /**
         * THIS STAGE ENDS WHEN THE PERSON TAPS DONE (the owner's decision, 2026-10-07): the
         * changeover from its start ({@link #awaitAck}), and every other by-hand rest - the
         * tunica release - once its time, now a guide, has run out (ByHand#waitsAfterClock).
         * Derived, not stored, so a routine saved before it waits the same way and no saved
         * field changes.
         */
        public boolean awaitsDone() { return rest && (awaitAck || manual); }

        /** Whether this stage's frames are excluded from NET TUP. Gross still counts them:
         *  the cuff was sealed and the time was real. One predicate, so a third kind of
         *  excluded stage cannot be added to one caller and forgotten at another. */
        public boolean outOfNet() {
        /* A WARM-UP AND A COOL-DOWN ARE NOT TRAINING VOLUME either, and until now only the
         * pressure kept them out of net rather than a rule. A prime hold at 4 inHg sits
         * below every level floor and was excluded by arithmetic; a RAMP warm-up climbs to
         * the working pressure and its last steps counted, so the same routine's warm-up
         * fed the gate or did not depending on which shape it happened to be.
         *
         * Stated as a rule instead: preparation and recovery are real time under real
         * pressure that the plan did not ask for as work, so gross counts them and net does
         * not - the same standing the fatigue block and the retention hold already have. */
        /* AND LENGTH WORK IS NOT GIRTH VOLUME. Net TUP, the yield gate and every number
         * built on them are the GIRTH engine's; traction time is real time under real
         * pressure that the girth plan never asked for, and letting it in would move a
         * girth user's gate because they did some pulling. A manual stage commands nothing
         * at all, so there is no pressure for net to count in the first place. */
        return fatigueBlock || retention || traction || manual || climb
            || colour == STAGE_WARM || colour == STAGE_COOL;
    }

        public Stage() { }

        public static Stage of(String name, int colour, String[] ids) {
            Stage s = new Stage();
            s.name = name; s.colour = colour;
            for (int i = 0; i < ids.length; i++) s.setIds.add(ids[i]);
            return s;
        }

        /** A REST stage: a name, a duration, and nothing else. Its colour is
         *  {@link Model#STAGE_REST} so every rail and list in the app already draws it
         *  apart from the stages that command something, without any of them being taught
         *  what a rest is. */
        public static Stage restOf(String name, int sec) {
            Stage s = new Stage();
            s.name = (name == null || name.trim().isEmpty()) ? "Rest" : name;
            s.colour = STAGE_REST;
            s.rest = true;
            s.restSec = sec;
            s.clampRest();
            return s;
        }

        /** A rest stage holds no sets and has one bounded number. Applied at WRITE time —
         *  including on load, which is a write — like every other clamp in this file. */
        public void clampRest() {
            if (!rest) return;
            setIds.clear();
            colour = STAGE_REST;
            restSec = restSec < 30 ? 30 : (restSec > 3600 ? 3600 : restSec);
        }

        /** The one-line summary a stage row prints. A rest has one fact about it; a normal
         *  stage's line is built by the screen from its set count and duration. */
        public String restLine() { return "vented — " + Fmt.t(restSec); }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name); o.put("colour", colour);
            o.put("rest", rest);
            o.put("restSec", restSec);
            o.put("fatigue", fatigueBlock);
            o.put("retention", retention);
            o.put("cyl", cylinderId == null ? "" : cylinderId);
            o.put("manual", manual);
            o.put("traction", traction);
            o.put("ack", awaitAck);
            o.put("climb", climb);
            JSONArray a = new JSONArray();
            for (int i = 0; i < setIds.size(); i++) a.put(setIds.get(i));
            o.put("sets", a);
            return o;
        }

        public static Stage fromJson(JSONObject o) {
            Stage s = new Stage();
            s.name = o.optString("name", "Stage");
            s.colour = o.optInt("colour", STAGE_WORK);
            // MIGRATION: absent on every stage ever saved — false, which is what all of
            // them are. See the field's own note.
            s.rest = o.optBoolean("rest", false);
            s.restSec = o.optInt("restSec", 120);
            // MIGRATION (Stage H Task 2): absent on every stage saved before the trainer's
            // fatigue block existed — false, the truth about every one of them.
            s.fatigueBlock = o.optBoolean("fatigue", false);
            // MIGRATION: false - no routine written before retention existed has one.
            s.retention = o.optBoolean("retention", false);
            // MIGRATION: "" - no stage written before the rack existed names a cylinder, and
            // "any" is the truth about every one of them.
            s.cylinderId = o.optString("cyl", "");
            // MIGRATION: false on every stage written before the length tier - there is no shape
            // of old data that could have meant either, the same rule `rest` states for itself.
            s.manual = o.optBoolean("manual", false);
            s.traction = o.optBoolean("traction", false);
            // MIGRATION: false - nothing written before this waited for anything.
            s.awaitAck = o.optBoolean("ack", false);
            // MIGRATION: false - nothing written before this left a climb uncounted.
            s.climb = o.optBoolean("climb", false);
            JSONArray a = o.optJSONArray("sets");
            if (a != null) for (int i = 0; i < a.length(); i++) s.setIds.add(a.optString(i));
            s.clampRest();
            return s;
        }
    }

    /** The prototype's :root custom properties --st-warm / --st-work / --st-cool. */
    public static final int STAGE_WARM = 0xFFC77B3C;
    public static final int STAGE_WORK = 0xFF3E7BC4;
    public static final int STAGE_COOL = 0xFF4F9E85;
    /** A REST stage's colour — the app's dim grey, deliberately not one of the three
     *  above: the other stages are kinds of WORK, and a rest is the absence of it. Every
     *  rail, tile and list that already colours a stage from Stage#colour therefore draws
     *  a rest apart without knowing rests exist. */
    public static final int STAGE_REST = 0xFF5C6575;

    /** STAGE H TASK 2 — the sentinel for {@link Routine#trainerTrack}: not one of
     *  {@link Plan}'s {@code TRACK_*} ids (which start at 1), so it can never collide
     *  with a real track marker. "This routine counts toward no track" — the default
     *  for every routine, trainer-marked or not, until something tags it otherwise. */
    public static final int TRAINER_TRACK_NONE = 0;

    /* -------------------------------------------------------------- routine */

    public static final class Routine {
        public String id, name;
        public final List<Stage> stages = new ArrayList<Stage>();
        public int runs, done;
        /** Shown in Today's favourites strip — the prototype's r.star. Separate from
         *  Model#selected on purpose: several routines can be starred (they are the
         *  shortlist Today offers), exactly one is selected (the one START runs). */
        public boolean star;
        /** The tissue adaptation assessment for this routine. Never null: a routine
         *  saved before it existed loads with the defaults, so no caller has to guard
         *  against absence and no screen has to invent a "no assessment block" state. */
        public Assess assess = new Assess();
        /** THE PER-ROUTINE OPT-OUT for the "save what you ran" offer. When true nothing is
         *  offered after a run of this routine — no card, no quiet line — and History keeps
         *  the door open for anyone who changes their mind. Default false: the offer is the
         *  feature, and a routine saved before this existed never asked to be silenced. Set
         *  only from the card's own "don't ask for this routine again" link, behind a
         *  confirm. */
        public boolean noSaveOffer;

        /**
         * Q2 - THE NET THIS ROUTINE WAS PRESCRIBED TO DELIVER, stamped at birth beside
         * {@link #trainerLevel} and for the same reason.
         *
         * Under-delivery used to compare every recent session's net against the CURRENT
         * prescription's target, which is wrong the moment the two differ - and they differ
         * in two ordinary situations. A SPLIT half delivers half the net and was being judged
         * against the whole. A REDUCED session - the return-from-a-week-off pressure, which
         * sits below the level floor by design - delivers no net at all and was being judged
         * against a full target it was never asked to reach. Three of those in a row tripped
         * the step-back, so following the precaution correctly demoted you.
         *
         * 0 MEANS UNKNOWN, which is every routine written before this existed and every
         * routine the user built by hand. An unknown target falls back to the old comparison,
         * so nothing about an existing history changes on the upgrade.
         *
         * A TARGET OF EXACTLY 0 IS A REAL ANSWER and not the same as unknown: it means "this
         * session is not expected to produce net", which is precisely what a reduced session
         * is. Those are excluded from the window rather than counted as failures - see
         * TrainerTab#recentTrackedNets. NEGATIVE is unknown, which is why the two are not
         * both spelled zero: the difference decides whether a session can cost you a level.
         */
        public double netTargetMin = -1.0;

        /**
         * 0.10 - THE MINUTES OF THIS ROUTINE'S CLIMBING HOLDS THAT SIT UNDER THE LINE NET
         * COUNTS FROM (the owner's decision): a Ramped block climbs from 80 % of the day's
         * working pressure, and a counted climb hold under the level's counting line is run
         * and named but counts nothing, is not made up, and is not in {@link #netTargetMin}.
         * They are still the plan's prescribed holds, so the level's net tests read a session
         * that delivered its target as the plan's minutes: the pressure steps and the gates
         * credit these at the rate the session delivered its own target
         * (TrainerTab#creditedNet). Copied to the session at filing (Sess#climbUnderLineMin).
         *
         * 0 for every other routine, and MIGRATION: 0 - a routine written before this had no
         * uncounted climb holds, or made them up in full.
         */
        public double climbUnderLineMin = 0.0;

        /**
         * DOES THIS ROUTINE PLAY THIS SET? The one question the run lock needs answered.
         *
         * A run used to block EVERY delete in the library, which is far wider than the
         * danger it exists for: deleting a set in some other routine cannot touch what is
         * playing. This is how the lock asks the narrow question instead of the broad one.
         *
         * A rest stage has no set behind it, so an empty id can never match - otherwise a
         * routine with a rest in it would claim to use every set whose id had gone missing.
         */
        public boolean usesSet(String setId) {
            if (setId == null || setId.length() == 0) return false;
            for (int i = 0; i < stages.size(); i++) {
                List<String> ids = stages.get(i).setIds;
                for (int j = 0; j < ids.size(); j++)
                    if (setId.equals(ids.get(j))) return true;
            }
            return false;
        }

        /** THE COLOUR MARK, exactly as {@link Set#mark} \u2014 same palette, same rules,
         *  same migration. A routine and the sets inside it are marked independently: a
         *  person may want the routine in one group and borrow a set that lives in
         *  another, and forcing them to agree would make the mark mean "belongs to"
         *  rather than "I put it here". */
        public int mark;

        /**
         * STAGE H TASK 2 — WHICH TRACK THIS ROUTINE COUNTS TOWARD, if any: one of
         * {@link Plan}'s {@code TRACK_*} ids ({@code TRACK_GIRTH_INTERVAL},
         * {@code TRACK_GIRTH_TRADITIONAL}, {@code TRACK_LENGTH}, {@code TRACK_FEEDER}), or
         * {@link #TRAINER_TRACK_NONE} — never a plain boolean, because a routine has to say
         * WHICH track it feeds, not merely that it feeds one (plan §7 ruling (a)). Two
         * origins land on the identical field: a routine Task 4's mint-generation builds
         * FOR the trainer (marked at birth), and a routine the USER built themselves that
         * they tag via the editor's "Counts toward: none / girth / length" row (round-2
         * ruling — "manual routines: unmarked by default... letting a user's own routine
         * feed a track's gates"). Both mechanisms are this ONE field; there is no second,
         * trainer-only marker.
         *
         * MIGRATION: {@link #TRAINER_TRACK_NONE}. Every routine saved before this feature
         * existed is exactly that — a routine nobody has tagged, off-plan, feeding no
         * track's gates — never inferred from the routine's name or shape.
         *
         * SHARE CODES STRIP THIS (round-2 ruling: "a foreign mint must never feed another
         * user's gates"). {@link #toShareCode} never writes it and {@link #fromShareCode}
         * never reads it, so a routine landing on someone else's phone through a share code
         * always decodes at the {@code new Routine()} default — see both methods' own doc.
         */
        public int trainerTrack = TRAINER_TRACK_NONE;

        /**
         * WHICH LEVEL THIS ROUTINE WAS MINTED FOR (audit A8). 0 means unknown \u2014 a routine
         * the user built themselves, or any mint written before this field existed.
         *
         * A track's level moves; a routine's does not. Net TUP is scored against a FLOOR,
         * and the floor is a property of the level the work was prescribed at. Reading the
         * track's CURRENT level instead meant that re-running a superseded mint after a
         * level-up scored it against a floor it was never built to meet \u2014 and nothing
         * retires or hides an old mint, so it stays in Routines, selectable and runnable.
         * The wrong number then fed yield, under-delivery and the level gates themselves.
         *
         * MIGRATION: 0. An old mint honestly does not know, and 0 makes the attribution
         * fall back to the track's current level, which is exactly what it did before.
         */
        public int trainerLevel = 0;

        /**
         * HOW FAR THIS ROUTINE'S WORK SITS FROM THE PLAN'S OWN FIGURE, kPa, before the day's
         * cut - the person's offset and the Program's gentle or firm, as they landed after the
         * limits (0.10, the personal scale; RxBuild stamps it at birth beside
         * {@link #trainerLevel}). Negative is work below the plan: the line net time counts
         * from moves down by it (Model#scoringFloorKpa), so a session run on the person's
         * lower scale still counts. A session copies it (Sess#scaleKpa) so the Level 1 gate
         * reads what was pulled on the plan's scale.
         *
         * MIGRATION: 0 - every routine minted before this ran on the plan's own figure, or on
         * a bias the old line already counted (the band floor); a hand-built one has no plan.
         */
        public int trainerScaleKpa = 0;

        /**
         * D2 / F8 - HOW THIS ROUTINE DIFFERS FROM THE PRESCRIPTION IT WAS MINTED FROM, or ""
         * when it is exactly what the plan asked for.
         *
         * A mint used to be take-it-or-leave-it: save the routine whole, or do not. A
         * prescription that was nearly right had to be saved and THEN edited - and at that
         * moment the app lost track of the difference, because an edited routine looks
         * identical to one the plan never wrote. The plan then went on scoring sessions
         * against a prescription the routine no longer matched, and nothing anywhere said so.
         *
         * So an adjustment is allowed, and it is RECORDED. Not to police it - the whole point
         * is that a person may know something the plan does not - but so that every screen
         * reading this routine can say "12 sets, not the 14 the plan asked for" rather than
         * quietly presenting it as the plan's own work.
         *
         * PROSE, not a diff structure. It is written once at the moment of the change, read
         * by humans, and never computed against - and a structured form would invite exactly
         * the re-derivation that would let it disagree with what was actually saved.
         *
         * MIGRATION: "". Every routine minted before this existed was saved unchanged, which
         * is what empty means.
         */
        public String mintedAs = "";

        /**
         * VERSION HISTORY (Stage E Task 6 / T10+) — a bounded record of what this
         * routine's STAGE/SET SHAPE used to be, one entry per structural edit that
         * changed it. "Shape" means the same thing {@link AsRunSnapshot} already means
         * by it: stage names, rest flags/durations, which sets sit where, and each of
         * those sets' own values, all frozen at the instant the edit replaced them —
         * NOT a live reference, so a version keeps reading true after the sets it
         * pointed at are edited elsewhere or deleted.
         *
         * Deliberately scoped to STRUCTURE, not to a Set's own numeric fields: sets are
         * library objects, shared by id across every routine that uses them ("Sets are
         * linked, not copied" — the picker's own note), edited from one shared editor
         * that is not attributed to any particular routine (entering it clears whatever
         * routine-editing snapshot was open — see SessionActivity#snapshotSet). Trying
         * to version a routine on a Set's own edits would mean one tap on a widely-
         * reused set silently cutting a new version into every routine that happens to
         * reference it — a behaviour nothing in this app's "shared, not copied" design
         * signs up for. So a version is cut only by edits made directly to THIS
         * routine's shape: adding/removing/reordering a stage, adding/removing/
         * reordering a set inside one, or changing a rest stage's duration — never by a
         * plain rename (of the routine or a stage) or a stage recolour, which the rest
         * of this codebase already treats as decoration that "changes nothing the pump
         * does" (see Stage#colour's own doc and AsRunSnapshot's "colour is deliberately
         * NOT snapshotted").
         *
         * Bounded at {@link #MAX_VERSIONS}: the oldest entry is dropped once a new one
         * would push the list past it. `history` holds only PAST shapes — the routine's
         * OWN stages/sets right now are the current, unarchived shape, timestamped by
         * `versionSince` and counted by `versionRuns` until the next edit archives it.
         */
        public final List<Version> history = new ArrayList<Version>();
        /** How many past shapes {@link #history} keeps before the oldest is dropped. */
        public static final int MAX_VERSIONS = 5;
        /** When the CURRENT (unarchived) shape became current, epoch ms. 0 means this
         *  routine has never had a version-tracked edit yet — the honest state for every
         *  routine saved before this feature existed and for one nobody has touched
         *  since. Stamped to "now" the first time a version is archived (see
         *  Model#archiveRoutineVersion): this app never invents a creation date it did
         *  not actually observe, so a routine's true origin before that stamp is simply
         *  not claimed. */
        public long versionSince;
        /** Completed runs of this routine counted against the CURRENT shape — reset to
         *  0 each time a version is archived, frozen into that archived Version's own
         *  `runs` at the moment it is superseded. Incremented alongside {@link #done}
         *  at the same real completion site (SessionActivity#finishSession); see that
         *  method for why an aborted or manual run must never touch either count. */
        public int versionRuns;

        /** One PAST shape this routine used to be — see {@link #history}'s own doc for
         *  what "shape" means and why only structural edits cut a new one. */
        public static final class Version {
            /** The frozen stage/set shape, reusing the exact structure the as-run
             *  recording already trusts for this ("what did this routine look like at
             *  instant X, independent of what its sets say now"). Never null on a
             *  version this app itself created; may be null only if a saved file was
             *  hand-edited or corrupted, in which case the row is skipped on display
             *  rather than shown with invented values.
             *
             *  CAPTURED AT `until`, NOT NECESSARILY TRUE FOR THE WHOLE `since`..`until`
             *  SPAN. A linked Set's own numeric fields can be edited independently — via
             *  the Set editor, cutting no version of its own, by the same scoping
             *  decision {@link Routine#history}'s own doc explains — at any point during
             *  a shape's lifetime, and this snapshot only ever reads those fields LIVE,
             *  at the instant the STRUCTURAL edit that finally archives it fires (see
             *  Model#archiveRoutineVersion). So if a linked Set is edited partway through
             *  a version's date range, the frozen detail reflects the value as of
             *  `until`, not necessarily what was actually commanded for the earlier part
             *  of that range. `since`/`until`/`runs` are always exact — only the frozen
             *  numeric detail's applicability across its own date range can be stale for
             *  the portion before such an edit. An inherent consequence of versioning
             *  structure rather than Set values (deliberate, not a bug), stated here
             *  rather than left for a reader to discover the hard way. */
            public AsRunSnapshot snapshot;
            /** When this shape BECAME current, epoch ms — the start of the date range a
             *  History row prints. Equal to `until` on a shape that was archived within
             *  the same instant it became current (the very first archive after this
             *  feature reaches an existing, unedited routine — see `versionSince`'s own
             *  note on why an earlier start is never invented). */
            public long since;
            /** When this shape was SUPERSEDED (the edit that archived it), epoch ms —
             *  the end of the date range a History row prints. */
            public long until;
            /** Runs completed while this shape was current — frozen from {@link
             *  #versionRuns} at archive time. */
            public int runs;

            public JSONObject toJson() throws JSONException {
                JSONObject o = new JSONObject();
                // Through a string, like every other epoch-ms long in this file (see
                // Set#fromRun's own note) — the desktop org.json shim has no optLong,
                // and forking the two readers is how a field ends up meaning one thing
                // on the phone and another in the test.
                o.put("since", String.valueOf(since)); o.put("until", String.valueOf(until));
                o.put("runs", runs);
                // Wrapped in a one-element array for the same reason toJson()'s own
                // "assess" is: the desktop org.json shim exposes optJSONArray(String)
                // but not optJSONObject(String), so this is the one shape both the
                // phone and the self-test decode with the same code.
                JSONArray snapArr = new JSONArray();
                if (snapshot != null) snapArr.put(snapshot.toJson());
                o.put("snap", snapArr);
                return o;
            }

            public static Version fromJson(JSONObject o) {
                Version v = new Version();
                if (o == null) return v;
                v.since = parseLong(o.optString("since", "0"));
                v.until = parseLong(o.optString("until", "0"));
                v.runs = o.optInt("runs", 0);
                JSONArray snapArr = o.optJSONArray("snap");
                v.snapshot = AsRunSnapshot.fromJson(
                    snapArr != null && snapArr.length() > 0 ? snapArr.optJSONObject(0) : null);
                return v;
            }
        }

        public Routine() { }

        /**
         * An independent duplicate — the routine editor's Duplicate, matching the
         * prototype's dupRoutine(). Stages are DEEP-copied (a shared Stage would let an
         * edit to one routine silently rewrite the other), while the SET IDS inside them
         * are shared by design: a routine holds ids, not copies, and duplicating a
         * routine must not fork the library.
         *
         * runs/done reset to zero because a copy has not been run, and `star` is not
         * inherited because Today's favourites strip would otherwise show two identically
         * named entries with no way to tell them apart.
         *
         * NOT INHERITED EITHER (Stage E final review, Finding 3, pinned in SelfTest):
         * {@link #history}, {@link #versionSince} and {@link #versionRuns} — the new
         * Routine() this method builds never touches any of the three, so a copy always
         * starts with an empty history and version-1 (unstamped, zero-run) status, the
         * same "has not been run yet" logic runs/done already follow. A copy is a NEW
         * routine that happens to start with the same shape; it did not live through the
         * edits that cut the source's own past versions, and crediting it with them would
         * misattribute someone else's structural-edit history to a routine that never had
         * one of its own.
         *
         * ALSO NOT INHERITED (Stage H Task 2): {@link #trainerTrack}. A duplicate is a NEW
         * routine a person made from an old one — it starts unmarked (TRAINER_TRACK_NONE),
         * the new Routine() default every other un-inherited field above already takes,
         * never silently feeding a track's gates because the routine it was copied from did.
         */
        public Routine copy(String newId) {
            Routine c = new Routine();
            c.id = newId;
            c.name = name + " copy";
            for (int i = 0; i < stages.size(); i++) {
                Stage src = stages.get(i);
                String[] ids = src.setIds.toArray(new String[src.setIds.size()]);
                Stage cs = Stage.of(src.name, src.colour, ids);
                // A rest stage duplicates AS a rest — copying only the name, the colour and
                // an (empty) id list would silently turn the copy's two-minute gap into an
                // empty stage that runs nothing.
                cs.rest = src.rest;
                cs.restSec = src.restSec;
                // Wave 3a (D12): EVERY stage flag rides along. Dropping awaitAck turned
                // the swap gate into a timed rest that commands the coda into whichever
                // tube is fitted; dropping traction/fatigueBlock fed pulling time into
                // Net TUP; dropping the cylinder lost which tube the stage runs in.
                cs.awaitAck = src.awaitAck;
                cs.traction = src.traction;
                cs.fatigueBlock = src.fatigueBlock;
                cs.manual = src.manual;
                cs.retention = src.retention;
                cs.climb = src.climb;
                cs.cylinderId = src.cylinderId;
                cs.clampRest();
                c.stages.add(cs);
            }
            Assess a = new Assess();
            if (assess != null) {
                a.on = assess.on; a.kpa = assess.kpa; a.sp = assess.sp;
                a.dur = assess.dur; a.when = assess.when;
            }
            c.assess = a;
            return c;
        }

        /**
         * THE "READY TO PROGRESS?" CARD's one-tap action (T1 A) — an independent
         * duplicate of this routine whose stages point at brand-new, independently-
         * owned {@link Set} copies, each with its pull targets raised by {@link
         * Set#STEP_UP_KPA} and reclamped to `model`'s own ceiling (see {@link
         * Set#stepUp}), rather than {@link #copy}'s own SHARED-set-ids behaviour.
         *
         * WHY THIS CANNOT REUSE {@link #copy}. The routine editor's Duplicate
         * deliberately shares set ids with the original — "a routine holds ids, not
         * copies, and duplicating a routine must not fork the library" ({@link #copy}'s
         * own doc). Sharing is safe there because nothing about a plain duplicate
         * claims any VALUE changed. This method's entire point is that the values DID
         * change — the offer is "here is what this routine would look like a step
         * deeper" — so if it shared ids, the moment the suggested copy's set was edited
         * (or simply played), it would silently rewrite the very routine the streak was
         * measured against, and a user who never even opened the step-up copy would
         * still see their original routine's numbers move underneath them.
         *
         * NEWLY-MINTED SETS ARE ADDED TO `model.sets` here, as a necessary side effect
         * — a routine whose stages reference ids the library does not hold cannot
         * resolve them ({@link #set}), so they have to exist before this method can
         * hand back a routine that actually plans and runs at all. This mirrors {@link
         * #newSetId}'s own "necessary side effect" (the id counter it advances) rather
         * than inventing a new pattern; adding the RESULT ROUTINE itself to {@link
         * #routines} stays the CALLER's job, exactly as it already is for {@link #copy}
         * at its one existing call site (DupRoutineTap: {@code
         * model.routines.add(r.copy(id))}) — this method's own call site mirrors that
         * shape: {@code model.routines.add(r.stepUpCopy(id, model))}.
         *
         * DEDUPLICATED BY OLD SET ID: a set referenced by more than one stage (or more
         * than once within one stage) is bumped exactly ONCE and every reference is
         * repointed at that SAME new id — two stages sharing one "warm up" set come out
         * sharing one BUMPED "warm up" set, never two independently-bumped copies that
         * could drift apart on what was, a moment ago, one program.
         *
         * A DANGLING set id (a stage referencing a set the library no longer has —
         * {@link #plan} already treats this as "skip it") is carried across UNCHANGED:
         * there is nothing to bump, and inventing a set out of nothing here would be a
         * second, silent kind of migration this method has no business performing.
         *
         * A REST stage holds no set ids at all ({@link Stage#clampRest}), so it copies
         * through exactly as {@link #copy} already copies one — nothing here has to
         * special-case it. `star` and `noSaveOffer` are left at their `new Routine()`
         * defaults (unstarred, offer on), and `runs`/`done` start at zero — the exact
         * same choices {@link #copy} already makes, for the exact same reasons stated
         * on that method's own doc. Also NOT inherited, for the identical reason {@link
         * #copy}'s own doc now states explicitly (Stage E final review, Finding 3):
         * {@link #history}, {@link #versionSince} and {@link #versionRuns} — this method
         * builds its own `new Routine()` too, so the stepped-up copy starts with an
         * empty history and version-1 status just like an ordinary Duplicate does, never
         * the source's own archived past or live run count. Same reasoning again for
         * {@link #trainerTrack} (Stage H Task 2): the stepped-up copy starts unmarked.
         */
        public Routine stepUpCopy(String newId, Model model) {
            Routine c = new Routine();
            c.id = newId;
            c.name = name + " (stepped up)";
            Map<String, String> dup = new LinkedHashMap<String, String>();
            for (int i = 0; i < stages.size(); i++) {
                Stage src = stages.get(i);
                String[] oldIds = src.setIds.toArray(new String[src.setIds.size()]);
                String[] newIds = new String[oldIds.length];
                for (int j = 0; j < oldIds.length; j++) {
                    String oldSetId = oldIds[j];
                    String mapped = dup.get(oldSetId);
                    if (mapped == null) {
                        Set orig = model.set(oldSetId);
                        if (orig == null) {
                            mapped = oldSetId;   // dangling — nothing to bump
                        } else {
                            String sid = model.newSetId();
                            Set bumped = orig.stepUp(sid, model.ceilKpa);
                            model.sets.add(bumped);
                            mapped = sid;
                        }
                        dup.put(oldSetId, mapped);
                    }
                    newIds[j] = mapped;
                }
                Stage cs = Stage.of(src.name, src.colour, newIds);
                cs.rest = src.rest;
                cs.restSec = src.restSec;
                // Wave 3a (D12): same full flag carry as copy() — see its note.
                cs.awaitAck = src.awaitAck;
                cs.traction = src.traction;
                cs.fatigueBlock = src.fatigueBlock;
                cs.manual = src.manual;
                cs.retention = src.retention;
                cs.climb = src.climb;
                cs.cylinderId = src.cylinderId;
                cs.clampRest();
                c.stages.add(cs);
            }
            Assess a = new Assess();
            if (assess != null) {
                a.on = assess.on; a.kpa = assess.kpa; a.sp = assess.sp;
                a.dur = assess.dur; a.when = assess.when;
            }
            c.assess = a;
            return c;
        }

        /** Convenience matching the prototype's newRoutine(): one stage named "Work"
         *  holding the given set ids. Used by seed() and by the pre-stage JSON
         *  migration in fromJson below. */
        public static Routine of(String id, String name, String[] ids) {
            Routine r = new Routine();
            r.id = id; r.name = name;
            r.stages.add(Stage.of("Work", STAGE_WORK, ids));
            return r;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name);
            o.put("runs", runs); o.put("done", done);
            o.put("star", star);
            o.put("nosave", noSaveOffer);
            o.put("netTargetMin", String.valueOf(netTargetMin));
            if (climbUnderLineMin > 0.0) o.put("climbUnder", String.valueOf(climbUnderLineMin));
            // Stage H Task 2. Deliberately absent from toShareCode's own JSON shape below
            // (a separate, hand-built object) — see that method's doc for why.
            o.put("trainerTrack", trainerTrack);
            o.put("trainerLevel", trainerLevel);
            if (trainerScaleKpa != 0) o.put("trainerScale", trainerScaleKpa);
            o.put("mintedAs", mintedAs == null ? "" : mintedAs);
            JSONArray a = new JSONArray();
            for (int i = 0; i < stages.size(); i++) a.put(stages.get(i).toJson());
            o.put("stages", a);
            /* Wrapped in a one-element array for the same reason meas/std are (see
               Model#toJson): the desktop org.json shim exposes optJSONArray(String)
               but not optJSONObject(String), so this is the one shape both the phone
               and the self-test decode with the same code. */
            JSONArray asA = new JSONArray();
            asA.put(assess.toJson());
            o.put("assess", asA);
            // Version history (Task 6 / T10+) — see Version's own toJson for why the
            // epoch-ms fields go out as strings.
            o.put("versionSince", String.valueOf(versionSince));
            o.put("versionRuns", versionRuns);
            JSONArray histA = new JSONArray();
            for (int i = 0; i < history.size(); i++) histA.put(history.get(i).toJson());
            o.put("history", histA);
            return o;
        }

        /**
         * Reads the current shape ("stages": [...]) when present. A routine saved
         * before stages existed has a bare "sets": [...] array and no "stages" key at
         * all — that migrates to a single stage named "Work" holding the same ids, in
         * the same order, dangling ids included (plan() is what skips those, not the
         * migration). See SelfTest for the assertion this can actually fail on.
         */
        public static Routine fromJson(JSONObject o) {
            Routine r = new Routine();
            r.id = o.optString("id"); r.name = o.optString("name", "Routine");
            r.runs = o.optInt("runs"); r.done = o.optInt("done");
            r.star = o.optBoolean("star");     // absent in every pre-Today save — not starred
            // Absent in every save written before the offer existed — false, i.e. the offer
            // is on. Silencing a feature nobody has been shown yet is not a migration.
            r.noSaveOffer = o.optBoolean("nosave");
            // NEGATIVE = unknown, which is every routine written before Q2 existed.
            try { r.netTargetMin = Double.parseDouble(o.optString("netTargetMin", "")); }
            catch (NumberFormatException e) { r.netTargetMin = -1.0; }
            // Stage H Task 2, MIGRATION: absent on every routine saved before the trainer
            // existed — TRAINER_TRACK_NONE, the honest "counts toward nothing" state for a
            // routine this feature never touched.
            r.trainerTrack = o.optInt("trainerTrack", TRAINER_TRACK_NONE);
            r.trainerLevel = o.optInt("trainerLevel", 0);
            // MIGRATION (0.10): 0 - on the plan's own scale. Held to the offset's reach and
            // the Program's one step, so a hand-edited file cannot drop the line to nothing.
            r.trainerScaleKpa = Scale.clampScaleKpa(o.optInt("trainerScale", 0));
            // MIGRATION (0.10): 0 - no uncounted climb holds. Held to [0, CLIMB_UNDER_MAX_MIN]
            // so a hand-edited file cannot credit a session with more than a day's work.
            r.climbUnderLineMin = clampClimbUnder(parseDoubleOr(o.optString("climbUnder", ""), 0.0));
            // MIGRATION: "" - every routine minted before this was saved unchanged.
            r.mintedAs = o.optString("mintedAs", "");
            // Absent in every save written before Task 15. Assess.fromJson(null) is the
            // migration: the routine loads and takes the documented defaults, rather
            // than the whole file failing to parse and falling back to seed().
            JSONArray assessArr = o.optJSONArray("assess");
            r.assess = Assess.fromJson(assessArr != null && assessArr.length() > 0
                                        ? assessArr.optJSONObject(0) : null);
            JSONArray stagesArr = o.optJSONArray("stages");
            if (stagesArr != null) {
                for (int i = 0; i < stagesArr.length(); i++)
                    r.stages.add(Stage.fromJson(stagesArr.optJSONObject(i)));
            } else {
                JSONArray legacy = o.optJSONArray("sets");
                if (legacy != null) {
                    List<String> ids = new ArrayList<String>();
                    for (int i = 0; i < legacy.length(); i++) ids.add(legacy.optString(i));
                    r.stages.add(Stage.of("Work", STAGE_WORK, ids.toArray(new String[ids.size()])));
                }
                // else: neither "stages" nor "sets" present — nothing to migrate, the
                // routine simply starts with zero stages.
            }
            // Version history (Task 6 / T10+). Absent on every routine saved before
            // this existed — versionSince 0 and an empty history are exactly the truth
            // about those routines: nothing has been archived yet, and this app never
            // invents an origin date it did not observe (see versionSince's own note).
            r.versionSince = parseLong(o.optString("versionSince", "0"));
            r.versionRuns = o.optInt("versionRuns", 0);
            JSONArray histArr = o.optJSONArray("history");
            if (histArr != null)
                for (int i = 0; i < histArr.length(); i++)
                    r.history.add(Version.fromJson(histArr.optJSONObject(i)));
            return r;
        }

        /* --------------------------------------------------- share code (S19) */

        /** The prefix on every share code this app produces, and the only thing
         *  {@link #fromShareCode} trusts before it tries to decode anything at all: text
         *  that does not start with it is rejected in one step, with one clear reason,
         *  rather than being run through base64 and JSON parsing that might coincidentally
         *  half-succeed on unrelated pasted text and hand back a nonsense routine. The
         *  trailing digit is the payload's format version. */
        private static final String SHARE_PREFIX = "PD-ROUTINE-1:";

        /**
         * VERSION 2: the same JSON, DEFLATED before base64.
         *
         * v1 wrote the document as plain text, and a routine's payload is unusually
         * compressible \u2014 every embedded set repeats the same eighteen key names, and a mint
         * repeats near-identical sets several times over. Measured on real routines this is
         * the difference between about 2,800 characters and about 360, and unlike shortening
         * the field names it does not turn key order or omission into a correctness hazard:
         * the decoded bytes are byte-for-byte the v1 document, so exactly one reader parses
         * both.
         *
         * V1 IS STILL READ. A code someone saved or sent before this existed decodes exactly
         * as it always did \u2014 the prefix says which envelope it is in, and nothing about the
         * document inside changed.
         */
        private static final String SHARE_PREFIX_V2 = "PD-ROUTINE-2:";

        /** WAVE-4 WRAP: the packed envelope's prefix — short on purpose, the length IS
         *  the feature. See {@link #toShareCode} for the format. */
        private static final String SHARE_PREFIX_V3 = "PD3:";

        /** M4: the packed version an interim build wrote (84fddb5 to this change). Read,
         *  never written - the stream is version 1's; see {@link #toShareCode}. */
        static final int SHARE_PACKED_VERSION_INTERIM = 2;

        /**
         * The stage colours a v3 code writes as a 4-bit palette index instead of 32 raw
         * bits. MIRRORS Look.BLOCKS BY VALUE: Model stays free of every android-adjacent
         * import (test.sh's whole premise), so the six block colours are restated here.
         * Drift is harmless by construction — a colour not in this list simply rides
         * raw, and an index always decodes to what the SENDER meant at encode time
         * because both ends share this same array. SelfTest pins the mirror.
         */
        static final int[] SHARE_COLOURS_V3 = {
            STAGE_WORK, STAGE_COOL, STAGE_REST,
            0xFF5B9CFF, 0xFFF2A0D4, 0xFF7FD3FF, 0xFFC77DFF, 0xFF9AA8FF, 0xFFCFE3FF };

        /**
         * A ROUTINE, MADE PORTABLE: {@link #SHARE_PREFIX} followed by base64 of a small
         * JSON document holding this routine's name, its assessment settings and its
         * stages — with every stage's referenced {@link Set}s embedded IN FULL (every
         * field {@link Set#toJson} writes) rather than by id. A `Stage#setIds` entry is a
         * reference into THIS library — meaningless on whatever phone the share sheet
         * hands the text to, which has its own library and its own ids — so the only way
         * to hand someone a working routine is to hand them the sets it actually needs,
         * not a pointer to shelves they cannot see.
         *
         * A set referenced by two different stages, or twice in the same one — {@link
         * Preset#pos} exists because that is a real, supported shape — is embedded once
         * PER OCCURRENCE rather than pooled and referenced by index. That costs a little
         * length on the rare routine that reuses a set, in exchange for a flat wire shape
         * with no id-pool indirection to get wrong; {@link #fromShareCode} and {@link
         * Model#importShareCode} mirror the choice on the way back in. Measured on a
         * 4-stage routine with 4 distinct sets (one stage repeats one of them, for 5
         * embedded occurrences — see SelfTest's share-code coverage) the resulting string
         * is about 1700 characters — comfortably inside what a share sheet, a note or a
         * paste field handles without complaint, so the terser custom encoding this task
         * allowed for turned out not to be needed.
         *
         * `model` is read-only here, used only to resolve each stage's set ids against
         * {@link Model#set} — an id that no longer resolves (the set was deleted from the
         * library after being placed in this stage) is silently skipped, the same
         * dangling-id tolerance {@link Model#plan} already has, rather than failing the
         * whole share over one stale reference.
         *
         * STAGE H — {@link #trainerTrack} IS DELIBERATELY NEVER WRITTEN HERE (plan round-2
         * ruling: "share codes strip trainerTrack — a foreign mint must never feed another
         * user's gates"). This method hand-builds its own JSON shape rather than calling
         * {@link #toJson}, so the marker is stripped simply by never being one of the keys
         * put below — there is no field to null out. {@link Stage#fatigueBlock} similarly
         * never appears in a stage's own share shape below, for the identical reason: a
         * fatigue marker is meaningless (and potentially misleading, feeding a Net-TUP
         * exclusion the receiving phone never asked for) outside the trainer plan that
         * placed it.
         */
        /**
         * WAVE-4 WRAP — THE PACKED ENVELOPE. New codes are v3: {@link #SHARE_PREFIX_V3}
         * + base64 of a BIT-PACKED stream, ~70–90 characters for a typical routine
         * against v2's ~360. Every field rides at its clamped width (pressures 6 bits,
         * holds 8, speed 7, durations 12, names as length-prefixed UTF-8), so the code
         * is short because nothing is wasted, not because anything is dropped — names
         * and marks still travel. What deliberately does NOT travel, same as v2's
         * boundary rules: trainerTrack, fatigue/retention markers, and the personal
         * star/fromRun/fromPlan facts (import resets them anyway; v3 simply never
         * writes them). A routine that exceeds the packed format's structural caps
         * (15 stages, 15 sets in one stage) falls back to the v2 envelope rather than
         * silently truncating — correctness over length. v1 and v2 codes import
         * forever; the prefix says which parser runs.
         *
         * M4 - THE TISSUE RESPONSE TEST COMES IN AS THE CODE SAYS. A share code is an existing
         * routine, and existing routines keep their setting (the owner's decision), so the
         * on/off bit is read as written whenever the code was made. An interim build wrote
         * packed version 2 (identical otherwise) and read version-1 codes as off; version 1
         * is written again, so a code made now imports on every build, and a version-2 code
         * still decodes, its bit kept.
         */
        public String toShareCode(Model model) throws JSONException {
            if (stages.size() > 15) return toShareCodeV2(model);
            for (int i = 0; i < stages.size(); i++) {
                int n = 0;
                Stage st = stages.get(i);
                for (int j = 0; j < st.setIds.size(); j++)
                    if (model.set(st.setIds.get(j)) != null) n++;
                if (n > 15) return toShareCodeV2(model);
            }
            BitWriter w = new BitWriter();
            w.write(4, 1);                                   // packed-format version
            writeNameV3(w, name, "Routine");
            w.write(3, markOf(mark));
            Assess as = assess == null ? new Assess() : assess;
            w.write(1, as.on ? 1 : 0);
            w.write(6, bounded(as.kpa, 0, 63));
            w.write(7, bounded(as.sp, 0, 100));
            w.write(8, bounded(as.dur, 0, 255));
            w.write(2, Assess.WHEN_BEFORE.equals(as.when) ? 0
                     : Assess.WHEN_AFTER.equals(as.when) ? 1 : 2);
            w.write(4, stages.size());
            for (int i = 0; i < stages.size(); i++) {
                Stage st = stages.get(i);
                writeNameV3(w, st.name, "Stage");
                int idx = -1;
                for (int c = 0; c < SHARE_COLOURS_V3.length; c++)
                    if (SHARE_COLOURS_V3[c] == st.colour) { idx = c; break; }
                if (idx >= 0) { w.write(1, 1); w.write(4, idx); }
                else {
                    w.write(1, 0);
                    w.write(16, (st.colour >>> 16) & 0xFFFF);
                    w.write(16, st.colour & 0xFFFF);
                }
                w.write(1, st.rest ? 1 : 0);
                w.write(12, bounded(st.restSec, 0, 4095));
                List<Set> resolved = new ArrayList<Set>();
                for (int j = 0; j < st.setIds.size(); j++) {
                    Set s = model.set(st.setIds.get(j));
                    if (s != null) resolved.add(s);          // dangling ids skip, as in v2
                }
                w.write(4, resolved.size());
                for (int j = 0; j < resolved.size(); j++) writeSetV3(w, resolved.get(j));
            }
            return SHARE_PREFIX_V3 + b64Encode(w.toBytes());
        }

        private static void writeSetV3(BitWriter w, Set s) {
            writeNameV3(w, s.name, "Set");
            w.write(3, markOf(s.mark));
            w.write(1, s.rest ? 1 : 0);
            if (s.rest) { w.write(12, bounded(s.dur, 0, 4095)); return; }
            w.write(1, s.ramp ? 1 : 0);
            w.write(6, bounded(s.up, 0, 63));
            w.write(6, bounded(s.lo, 0, 63));
            w.write(8, bounded(s.uh, 0, 255));
            w.write(8, bounded(s.lh, 0, 255));
            w.write(7, bounded(s.sp, 0, 100));
            if (s.ramp) {
                w.write(6, bounded(s.up2, 0, 63));
                w.write(6, bounded(s.lo2, 0, 63));
                w.write(8, bounded(s.uh2, 0, 255));
                w.write(8, bounded(s.lh2, 0, 255));
                w.write(7, bounded(s.sp2, 0, 100));
                w.write(7, bounded(s.steps, 0, 127));
            }
            w.write(12, bounded(s.dur, 0, 4095));
        }

        /** Length-prefixed UTF-8, 63 bytes max — trimmed by CHARACTER until it fits, so
         *  a multibyte name is never cut mid-codepoint into replacement-glyph soup. */
        private static void writeNameV3(BitWriter w, String name, String fallback) {
            String n = name == null || name.length() == 0 ? fallback : name;
            byte[] b = n.getBytes(StandardCharsets.UTF_8);
            while (b.length > 63) {
                n = n.substring(0, n.length() - 1);
                b = n.getBytes(StandardCharsets.UTF_8);
            }
            w.write(6, b.length);
            for (int i = 0; i < b.length; i++) w.write(8, b[i] & 0xFF);
        }

        private static int bounded(int v, int lo, int hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        /** The v2 (compressed-JSON) writer, kept callable so its round trip stays
         *  pinned — old codes must import forever, so the test needs a way to MAKE one. */
        public String toShareCodeV2(Model model) throws JSONException {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("name", name);
            o.put("mark", mark);
            JSONArray stagesArr = new JSONArray();
            for (int i = 0; i < stages.size(); i++) {
                Stage st = stages.get(i);
                JSONObject so = new JSONObject();
                so.put("name", st.name);
                so.put("colour", st.colour);
                so.put("rest", st.rest);
                so.put("restSec", st.restSec);
                JSONArray setsArr = new JSONArray();
                for (int j = 0; j < st.setIds.size(); j++) {
                    Set s = model.set(st.setIds.get(j));
                    // Each set's own mark rides along inside toJson(), deliberately: a
                    // mark is part of what the author built and it steals nothing on the
                    // receiving phone, because it never meant anything but itself. The
                    // sender's `imported` rides too and is overwritten at the boundary \u2014
                    // see importShareCode.
                    if (s != null) setsArr.put(s.toJson());
                }
                so.put("sets", setsArr);
                stagesArr.put(so);
            }
            o.put("stages", stagesArr);
            // Wrapped in a one-element array for the same reason toJson()'s own "assess"
            // is: the desktop org.json shim exposes optJSONArray(String) but not
            // optJSONObject(String), so this is the one shape both the phone and the
            // self-test decode with the same code.
            JSONArray assessArr = new JSONArray();
            assessArr.put(assess.toJson());
            o.put("assess", assessArr);
            return SHARE_PREFIX_V2
                 + b64Encode(deflate(o.toString().getBytes(StandardCharsets.UTF_8)));
        }

        /**
         * The DECODED SHAPE of a share code, before it has been imported anywhere: the
         * routine exactly as encoded (its stages' `setIds` still hold the SENDING
         * device's ids, meaningless in any other library) plus every embedded set, once
         * per occurrence, in the exact order {@link #toShareCode} wrote them and {@link
         * #fromShareCode} read them back. This is deliberately NOT yet a routine fit to
         * drop into a library — see {@link Model#importShareCode}, which mints a fresh,
         * locally-unique id for the routine and for every one of these sets and rewrites
         * the stage references onto them: a routine pasted in from someone else's phone
         * is new data for THIS library, not a reference into it.
         */
        public static final class Decoded {
            public Routine routine;
            /** One entry per embedded set occurrence, in the same order the stages that
             *  reference them were walked — see {@link Model#importShareCode} for why
             *  that order, not the ids themselves, is what ties each entry back to the
             *  stage slot it belongs in. */
            public final List<Set> sets = new ArrayList<Set>();
        }

        /**
         * The inverse of {@link #toShareCode}. Throws {@link JSONException} on anything
         * that is not one of this app's own routine codes — a boundary this app has to
         * validate for real, since the input is pasted text a person typed or copied from
         * who knows where: a missing/garbled {@link #SHARE_PREFIX}, invalid base64, text
         * that will not parse as JSON, JSON that parses but is not shaped like a routine
         * (the "v" check and the "stages" key check below), or a "stages" array whose
         * entries are all unparseable garbage, each fail with one clear reason rather than
         * silently producing an empty or wrong routine. A routine whose "stages" array is
         * simply EMPTY is not one of those failures — see the comment further down for why
         * that shape is real, not corrupt. Callers (the Library "Add from code" dialog, and
         * SelfTest) are expected to catch this and show ONE message — never let it reach
         * the user as a crash.
         */
        public static Decoded fromShareCode(String code) throws JSONException {
            if (code == null) throw new JSONException("empty code");
            String trimmed = code.trim();
            // WHICH ENVELOPE, decided by the prefix and nothing else. v3 is the packed
            // stream; v1/v2 are the JSON document, plain or deflated \u2014 every one of them
            // keeps importing forever.
            if (trimmed.startsWith(SHARE_PREFIX_V3)) {
                byte[] packed;
                try { packed = b64Decode(trimmed.substring(SHARE_PREFIX_V3.length())); }
                catch (RuntimeException e) { throw new JSONException("corrupt share code"); }
                return fromShareCodeV3(packed);
            }
            boolean zipped = trimmed.startsWith(SHARE_PREFIX_V2);
            if (!zipped && !trimmed.startsWith(SHARE_PREFIX))
                throw new JSONException("not a routine share code");
            String payload = trimmed.substring(
                (zipped ? SHARE_PREFIX_V2 : SHARE_PREFIX).length());
            byte[] raw;
            try {
                raw = b64Decode(payload);
                if (zipped) raw = inflate(raw);
            } catch (RuntimeException e) {
                throw new JSONException("corrupt share code");
            }
            String json = new String(raw, StandardCharsets.UTF_8);
            JSONObject o = new JSONObject(json);
            if (o.optInt("v", -1) != 1)
                throw new JSONException("unrecognised share code version");
            JSONArray stagesArr = o.optJSONArray("stages");
            if (stagesArr == null) throw new JSONException("share code has no stages");
            Decoded d = new Decoded();
            Routine r = new Routine();
            r.name = o.optString("name", "Routine");
            // Absent on every v1 code written before marks existed \u2014 0, unmarked, which
            // is what those routines are.
            r.mark = markOf(o.optInt("mark", 0));
            JSONArray assessArr = o.optJSONArray("assess");
            r.assess = Assess.fromJson(assessArr != null && assessArr.length() > 0
                                        ? assessArr.optJSONObject(0) : null);
            // Stage H — trainerTrack starts at its new-Routine() default and is never read
            // from `o` below: toShareCode() never writes the key (see that method's own
            // doc), so there is nothing here TO read, but the assignment is made explicit
            // — belt and braces against a future edit to this method that starts trusting
            // an untrusted "trainerTrack" key a hand-crafted paste could add.
            r.trainerTrack = TRAINER_TRACK_NONE;
            for (int i = 0; i < stagesArr.length(); i++) {
                JSONObject so = stagesArr.optJSONObject(i);
                if (so == null) continue;
                Stage st = new Stage();
                st.name = so.optString("name", "Stage");
                st.colour = so.optInt("colour", STAGE_WORK);
                st.rest = so.optBoolean("rest", false);
                st.restSec = so.optInt("restSec", 120);
                // Same belt-and-braces strip as trainerTrack above, for the per-stage
                // fatigue marker: toShareCode() never writes "fatigue" either.
                st.fatigueBlock = false;
                st.retention = false;
                JSONArray setsArr = so.optJSONArray("sets");
                if (setsArr != null) {
                    for (int j = 0; j < setsArr.length(); j++) {
                        JSONObject setObj = setsArr.optJSONObject(j);
                        if (setObj == null) continue;
                        Set s = Set.fromJson(setObj);
                        d.sets.add(s);
                        st.setIds.add(s.id);
                    }
                }
                st.clampRest();
                r.stages.add(st);
            }
            // A GENUINELY EMPTY "stages":[] is not corruption — Model#removeStage has no
            // "keep at least one" guard (the same as the legacy JSON migration above it in
            // this file, which "simply starts with zero stages" rather than treating that
            // as an error), so a real routine reaching this share code can legitimately
            // have none. What IS corruption is a NON-EMPTY array where every entry failed
            // to parse as a stage object at all — that shape never comes out of {@link
            // #toShareCode}, and letting it through as a silent zero-stage routine would
            // hide exactly the kind of garbled input this method exists to catch.
            if (stagesArr.length() > 0 && r.stages.isEmpty())
                throw new JSONException("share code's stages did not parse");
            d.routine = r;
            return d;
        }

        /** The v3 reader — the exact mirror of {@link #toShareCode}'s writes, field for
         *  field. Every structural surprise (truncated stream, unknown packed version)
         *  throws the same JSONException the JSON path throws, so the Library's one
         *  "corrupt share code" message covers all three envelopes. Values are NOT
         *  clamped here — importShareCode's clampAll() is the boundary, same as v2. */
        private static Decoded fromShareCodeV3(byte[] packed) throws JSONException {
            BitReader rd = new BitReader(packed);
            int version = rd.read(4);
            // 2 is an interim build's (see toShareCode); the stream is the same.
            if (version != 1 && version != SHARE_PACKED_VERSION_INTERIM)
                throw new JSONException("unrecognised share code version");
            Decoded d = new Decoded();
            Routine r = new Routine();
            r.name = readNameV3(rd, "Routine");
            r.mark = markOf(rd.read(3));
            Assess as = new Assess();
            as.on = rd.read(1) == 1;             // M4: as the code says, either version
            as.kpa = rd.read(6);
            as.sp = rd.read(7);
            as.dur = rd.read(8);
            int when = rd.read(2);
            as.when = when == 0 ? Assess.WHEN_BEFORE
                    : when == 1 ? Assess.WHEN_AFTER : Assess.WHEN_BOTH;
            r.assess = as;
            r.trainerTrack = TRAINER_TRACK_NONE;   // the same strip the v2 reader makes
            int nStages = rd.read(4);
            int minted = 0;
            for (int i = 0; i < nStages; i++) {
                Stage st = new Stage();
                st.name = readNameV3(rd, "Stage");
                if (rd.read(1) == 1) {
                    int idx = rd.read(4);
                    st.colour = idx < SHARE_COLOURS_V3.length
                              ? SHARE_COLOURS_V3[idx] : STAGE_WORK;
                } else {
                    st.colour = (rd.read(16) << 16) | rd.read(16);
                }
                st.rest = rd.read(1) == 1;
                st.restSec = rd.read(12);
                st.fatigueBlock = false;
                st.retention = false;
                int nSets = rd.read(4);
                for (int j = 0; j < nSets; j++) {
                    // Placeholder ids: importShareCode ties sets to slots BY ORDER and
                    // mints real ids at the boundary, so these only have to be non-null.
                    Set s = readSetV3(rd, "shr" + (minted++));
                    d.sets.add(s);
                    st.setIds.add(s.id);
                }
                st.clampRest();
                r.stages.add(st);
            }
            d.routine = r;
            return d;
        }

        private static Set readSetV3(BitReader rd, String id) throws JSONException {
            String name = readNameV3(rd, "Set");
            int mark = markOf(rd.read(3));
            boolean rest = rd.read(1) == 1;
            Set s;
            if (rest) {
                s = Set.fixed(id, name, 1, 0, 5, 5, 60, rd.read(12));
                s.rest = true;
            } else {
                boolean ramp = rd.read(1) == 1;
                int up = rd.read(6), lo = rd.read(6);
                int uh = rd.read(8), lh = rd.read(8), sp = rd.read(7);
                if (ramp) {
                    int up2 = rd.read(6), lo2 = rd.read(6);
                    int uh2 = rd.read(8), lh2 = rd.read(8), sp2 = rd.read(7);
                    int steps = rd.read(7);
                    int dur = rd.read(12);
                    s = Set.ramp(id, name, up, lo, uh, lh, sp,
                                 up2, lo2, uh2, lh2, sp2, steps, dur);
                } else {
                    s = Set.fixed(id, name, up, lo, uh, lh, sp, rd.read(12));
                }
            }
            s.mark = mark;
            return s;
        }

        private static String readNameV3(BitReader rd, String fallback) throws JSONException {
            int len = rd.read(6);
            byte[] b = new byte[len];
            for (int i = 0; i < len; i++) b[i] = (byte) rd.read(8);
            String s = new String(b, StandardCharsets.UTF_8);
            return s.length() == 0 ? fallback : s;
        }

        /** MSB-first bit stream writer for the v3 envelope. Widths up to 16 bits per
         *  call — the format never needs more (a raw colour goes as two 16s). */
        private static final class BitWriter {
            private final java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream();
            private int cur, nbits;
            void write(int bits, int value) {
                for (int i = bits - 1; i >= 0; i--) {
                    cur = (cur << 1) | ((value >>> i) & 1);
                    if (++nbits == 8) { out.write(cur); cur = 0; nbits = 0; }
                }
            }
            byte[] toBytes() {
                if (nbits > 0) { out.write(cur << (8 - nbits)); cur = 0; nbits = 0; }
                return out.toByteArray();
            }
        }

        /** The mirror. Running past the end throws — a cut-short paste is corruption,
         *  never a silent zero. */
        private static final class BitReader {
            private final byte[] in;
            private int at, cur, nbits;
            BitReader(byte[] in) { this.in = in; }
            int read(int bits) throws JSONException {
                int v = 0;
                for (int i = 0; i < bits; i++) {
                    if (nbits == 0) {
                        if (at >= in.length) throw new JSONException("share code cut short");
                        cur = in[at++] & 0xFF; nbits = 8;
                    }
                    v = (v << 1) | ((cur >>> --nbits) & 1);
                }
                return v;
            }
        }

        /* ---- minimal base64, deliberately hand-rolled --------------------------------
         * java.util.Base64 is not part of the Android platform below API 26, and this
         * app's minSdkVersion is 24 (AndroidManifest.xml) — using it here would compile
         * fine and then throw on exactly the older devices this app still supports.
         * android.util.Base64 is not available to the desktop self-test: Model.java
         * carries no `import android`, which is what lets test.sh compile and run it on a
         * bare JVM (see test.sh's own comment on how it picks its sources) — an
         * android.* import here would break that. A few dozen lines avoid depending on
         * either. */

        /** Deflate, zlib-wrapped (the default) so the header states its own format. */
        private static byte[] deflate(byte[] rawBytes) {
            java.util.zip.Deflater z =
                new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try {
                z.setInput(rawBytes);
                z.finish();
                byte[] buf = new byte[4096];
                while (!z.finished()) {
                    int n = z.deflate(buf);
                    if (n <= 0) break;
                    out.write(buf, 0, n);
                }
            } finally {
                z.end();
            }
            return out.toByteArray();
        }

        /** The reverse. Anything that is not valid deflate throws the same
         *  IllegalArgumentException b64Decode throws, so one catch upstairs covers both and
         *  the person sees one honest "corrupt share code" rather than two different words
         *  for the same thing. */
        private static byte[] inflate(byte[] zipped) {
            java.util.zip.Inflater z = new java.util.zip.Inflater();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try {
                z.setInput(zipped);
                byte[] buf = new byte[4096];
                while (!z.finished()) {
                    int n = z.inflate(buf);
                    if (n == 0) {
                        if (z.needsInput() || z.needsDictionary()) break;
                    } else {
                        out.write(buf, 0, n);
                    }
                }
            } catch (java.util.zip.DataFormatException e) {
                throw new IllegalArgumentException("not deflate data");
            } finally {
                z.end();
            }
            return out.toByteArray();
        }

        private static final char[] B64_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

        /** TEST-ONLY. The self-test builds a share code by hand - one no sending app would
         *  ever produce - to prove that what arrives is clamped on THIS phone rather than
         *  trusted because the sender said so. Nothing outside the harness uses it. */
        static String b64EncodeForTest(byte[] data) { return b64Encode(data); }

        private static String b64Encode(byte[] data) {
            StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
            int i = 0;
            for (; i + 3 <= data.length; i += 3) {
                int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
                sb.append(B64_CHARS[(n >>> 18) & 0x3F]).append(B64_CHARS[(n >>> 12) & 0x3F])
                  .append(B64_CHARS[(n >>> 6) & 0x3F]).append(B64_CHARS[n & 0x3F]);
            }
            int rem = data.length - i;
            if (rem == 1) {
                int n = (data[i] & 0xFF) << 16;
                sb.append(B64_CHARS[(n >>> 18) & 0x3F]).append(B64_CHARS[(n >>> 12) & 0x3F])
                  .append('=').append('=');
            } else if (rem == 2) {
                int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
                sb.append(B64_CHARS[(n >>> 18) & 0x3F]).append(B64_CHARS[(n >>> 12) & 0x3F])
                  .append(B64_CHARS[(n >>> 6) & 0x3F]).append('=');
            }
            return sb.toString();
        }

        /** Throws {@link IllegalArgumentException} on anything that is not valid base64 —
         *  caught and turned into the one clear {@link JSONException} {@link
         *  #fromShareCode} reports, rather than left to propagate as a different
         *  exception type its caller was not told to expect. */
        private static byte[] b64Decode(String s) {
            // Whitespace stripped defensively — a pasted code may pick up line breaks or
            // leading/trailing spaces from an email client, a notes app or a chat bubble.
            StringBuilder clean = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!Character.isWhitespace(c)) clean.append(c);
            }
            s = clean.toString();
            int len = s.length();
            if (len == 0 || len % 4 != 0) throw new IllegalArgumentException("bad base64 length");
            int pad = 0;
            if (s.charAt(len - 1) == '=') pad++;
            if (len >= 2 && s.charAt(len - 2) == '=') pad++;
            int outLen = (len / 4) * 3 - pad;
            byte[] out = new byte[outLen];
            int oi = 0;
            for (int i = 0; i < len; i += 4) {
                int b0 = b64Val(s.charAt(i));
                int b1 = b64Val(s.charAt(i + 1));
                char c2 = s.charAt(i + 2);
                char c3 = s.charAt(i + 3);
                int b2 = c2 == '=' ? 0 : b64Val(c2);
                int b3 = c3 == '=' ? 0 : b64Val(c3);
                int n = (b0 << 18) | (b1 << 12) | (b2 << 6) | b3;
                if (oi < outLen) out[oi++] = (byte) ((n >>> 16) & 0xFF);
                if (oi < outLen) out[oi++] = (byte) ((n >>> 8) & 0xFF);
                if (oi < outLen) out[oi++] = (byte) (n & 0xFF);
            }
            return out;
        }

        private static int b64Val(char c) {
            if (c >= 'A' && c <= 'Z') return c - 'A';
            if (c >= 'a' && c <= 'z') return c - 'a' + 26;
            if (c >= '0' && c <= '9') return c - '0' + 52;
            if (c == '+') return 62;
            if (c == '/') return 63;
            throw new IllegalArgumentException("bad base64 character");
        }
    }

    /* -------------------------------------------------------- session history */

    /**
     * WHAT THE ROUTINE LOOKED LIKE WHEN THE RUN STARTED — the routine id, and each stage's
     * name and set ids, frozen at that instant.
     *
     * Filed alongside the session so a save card reopened from History days later can ask
     * the only question that matters before it offers to UPDATE the routine: is the routine
     * still the one that was run? A routine edited since must not have a run spliced back
     * into it against positions that have moved, and a routine deleted since can only be
     * saved as something new. Without a snapshot both cases are unanswerable, and the app
     * would answer them by guessing.
     *
     * The colour of a stage is deliberately NOT snapshotted: it is decoration, it does not
     * change what was run, and a recoloured stage must not read as an edited one.
     *
     * Null for a manual run (there is no routine) and for every session filed before this
     * existed — never an empty snapshot, which would claim a routine with no stages had
     * been run.
     */
    public static final class AsRunSnapshot {
        public String routineId = "";
        public final List<StageSnap> stages = new ArrayList<StageSnap>();

        /** One stage as it stood at run start: its name, the set ids it held, in order, and
         *  — parallel to setIds — what each of those sets SAID at that instant. */
        public static final class StageSnap {
            public String name = "";
            /** Whether the stage was a REST, and for how long. Recorded like every other
             *  fact about what was planned: a rest stage whose duration was edited since is
             *  as much an edit of the routine as a set moved, and {@link #matchesStructure}
             *  has to be able to see it. Absent on every recording written before rest
             *  stages existed — false / 0, which is exactly what those runs held. */
            public boolean rest;
            public int restSec;
            public final List<String> setIds = new ArrayList<String>();
            /** One entry per setIds entry (or EMPTY on a recording written before set values
             *  were snapshotted — see {@link SetSnap}). */
            public final List<SetSnap> sets = new ArrayList<SetSnap>();

            /** The values of the set at position `pos`, or null when this snapshot does not
             *  carry them (an old recording, or a set that was already gone at run start). */
            public SetSnap setAt(int pos) {
                if (pos < 0 || pos >= sets.size()) return null;
                SetSnap ss = sets.get(pos);
                return ss != null && ss.known ? ss : null;
            }
        }

        /**
         * ONE SET, AS IT READ AT RUN START — the values, not a reference.
         *
         * The set ids alone answered "is this still the same routine?" but not "is this
         * still the same set?": editing a set in the Library after a run rewrote what that
         * run's card claimed had been planned, so an untouched occurrence read as "adjusted",
         * one block split into two, and Update spliced the run back in and silently reverted
         * the Library edit. What was planned is a fact about the run, so the run carries it.
         *
         * These are the set's OWN values, NOT clamped by any ceiling: the ceiling in force at
         * run start is recorded separately (AsRun#ceilKpa) and applied on top, so a ceiling
         * that moved since is never mistaken for an edited set.
         *
         * `known` is false for a position whose set could not be read at run start (already
         * deleted): the position is recorded, its values are not invented.
         */
        public static final class SetSnap {
            public boolean known;
            public String id = "", name = "";
            public boolean ramp;
            /** Whether the position held a REST rather than a commanding set. Recorded like
             *  every other value here, so {@link #toSet} rebuilds a rest as a rest instead
             *  of as a fixed set that happens to be full of zeros. */
            public boolean rest;
            public int up, lo, uh, lh, sp, up2, lo2, uh2, lh2, sp2, steps, dur;

            public static SetSnap of(Set s) {
                SetSnap k = new SetSnap();
                if (s == null) return k;
                k.known = true;
                k.id = s.id == null ? "" : s.id; k.name = s.name == null ? "" : s.name;
                k.ramp = s.ramp; k.rest = s.rest;
                k.up = s.up; k.lo = s.lo; k.uh = s.uh; k.lh = s.lh; k.sp = s.sp;
                k.up2 = s.up2; k.lo2 = s.lo2; k.uh2 = s.uh2; k.lh2 = s.lh2; k.sp2 = s.sp2;
                k.steps = s.steps; k.dur = s.dur;
                return k;
            }

            /** This set as a Set again, for the derivations that want one (a ladder, a
             *  duration). Never handed to the Library: it is a reading, not a row. */
            public Set toSet() {
                if (rest) return Set.restOf(id, name, dur);
                Set s = ramp ? Set.ramp(id, name, up, lo, uh, lh, sp, up2, lo2, uh2, lh2, sp2, steps, dur)
                             : Set.fixed(id, name, up, lo, uh, lh, sp, dur);
                // A fixed set's end values are seeded from its start values by Set.fixed; a
                // snapshot of one keeps whatever the library row held, so a later flip to
                // ramp compares like for like.
                if (!ramp) { s.up2 = up2; s.lo2 = lo2; s.uh2 = uh2; s.lh2 = lh2; s.sp2 = sp2; s.steps = steps; }
                return s;
            }

            /** Whether the set as it stands now says exactly what it said at run start.
             *  The NAME is deliberately not compared — renaming a set does not change what
             *  was run, exactly as a stage's colour does not. */
            public boolean sameValues(Set s) {
                if (s == null) return false;
                if (rest != s.rest) return false;
                if (rest) return dur == s.dur;   // a rest has exactly one value to compare
                return ramp == s.ramp && up == s.up && lo == s.lo && uh == s.uh && lh == s.lh
                    && sp == s.sp && up2 == s.up2 && lo2 == s.lo2 && uh2 == s.uh2
                    && lh2 == s.lh2 && sp2 == s.sp2 && steps == s.steps && dur == s.dur;
            }

            public JSONObject toJson() throws JSONException {
                JSONObject o = new JSONObject();
                o.put("id", id); o.put("name", name); o.put("ramp", ramp);
                o.put("rest", rest);
                o.put("up", up); o.put("lo", lo); o.put("uh", uh); o.put("lh", lh);
                o.put("sp", sp); o.put("dur", dur);
                o.put("up2", up2); o.put("lo2", lo2); o.put("sp2", sp2); o.put("steps", steps);
                o.put("uh2", uh2); o.put("lh2", lh2);
                o.put("known", known);
                return o;
            }

            public static SetSnap fromJson(JSONObject o) {
                SetSnap k = new SetSnap();
                if (o == null) return k;
                k.known = o.optBoolean("known", true);
                k.id = o.optString("id", ""); k.name = o.optString("name", "");
                k.ramp = o.optBoolean("ramp");
                k.rest = o.optBoolean("rest", false);
                k.up = o.optInt("up", 0); k.lo = o.optInt("lo", 0);
                k.uh = o.optInt("uh", 0); k.lh = o.optInt("lh", 0);
                k.sp = o.optInt("sp", 0); k.dur = o.optInt("dur", 0);
                k.up2 = o.optInt("up2", k.up); k.lo2 = o.optInt("lo2", k.lo);
                k.sp2 = o.optInt("sp2", k.sp); k.steps = o.optInt("steps", 4);
                k.uh2 = o.optInt("uh2", k.uh); k.lh2 = o.optInt("lh2", k.lh);
                return k;
            }
        }

        /** The snapshot of a routine as it stands right now, with each stage's sets read out
         *  of `m` at the same instant. Null in, null out — a manual run has no routine and
         *  must not be given an invented one. A null Model records the structure alone. */
        public static AsRunSnapshot of(Routine r, Model m) {
            if (r == null) return null;
            AsRunSnapshot s = new AsRunSnapshot();
            s.routineId = r.id == null ? "" : r.id;
            for (int i = 0; i < r.stages.size(); i++) {
                Stage src = r.stages.get(i);
                StageSnap st = new StageSnap();
                st.name = src.name == null ? "" : src.name;
                st.rest = src.rest;
                st.restSec = src.restSec;
                st.setIds.addAll(src.setIds);
                if (m != null)
                    for (int j = 0; j < src.setIds.size(); j++)
                        st.sets.add(SetSnap.of(m.set(src.setIds.get(j))));
                s.stages.add(st);
            }
            return s;
        }

        /** Whether the routine's STRUCTURE as it stands now still matches this snapshot —
         *  same stage count, same stage names, same set ids in the same order. A deleted
         *  routine (null) never matches: there is nothing left to update. */
        public boolean matchesStructure(Routine r) {
            if (r == null) return false;
            if (!routineId.equals(r.id == null ? "" : r.id)) return false;
            if (r.stages.size() != stages.size()) return false;
            for (int i = 0; i < stages.size(); i++) {
                StageSnap a = stages.get(i);
                Stage b = r.stages.get(i);
                if (!a.name.equals(b.name == null ? "" : b.name)) return false;
                if (!a.setIds.equals(b.setIds)) return false;
                // A rest stage's ONE value. Compared only when the recording actually knows
                // it was a rest: a snapshot written before rest stages existed carries
                // rest=false for a stage that is a rest NOW, and treating that as a
                // difference would label every old run "edited" the moment a rest is added
                // — but a rest added since IS a structural change, so the flags must match.
                if (a.rest != b.rest) return false;
                if (a.rest && a.restSec != 0 && a.restSec != b.restSec) return false;
            }
            return true;
        }

        /** The structure AND every set's values. A set edited in the Library since the run
         *  is as much an edit of the routine as a stage moved: splicing the run back in
         *  would revert that edit without ever mentioning it, so Update is off and the card
         *  says the routine was edited. A snapshot that carries no set values (an old
         *  recording) answers on the structure alone — the only honest answer available. */
        public boolean matches(Routine r, Model m) {
            if (!matchesStructure(r)) return false;
            if (m == null) return true;
            for (int i = 0; i < stages.size(); i++) {
                StageSnap a = stages.get(i);
                for (int j = 0; j < a.setIds.size(); j++) {
                    SetSnap ss = a.setAt(j);
                    if (ss == null) continue;               // nothing recorded: nothing to contradict
                    if (!ss.sameValues(m.set(a.setIds.get(j)))) return false;
                }
            }
            return true;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("rid", routineId);
            JSONArray a = new JSONArray();
            for (int i = 0; i < stages.size(); i++) {
                StageSnap st = stages.get(i);
                JSONObject so = new JSONObject();
                so.put("name", st.name);
                so.put("rest", st.rest);
                so.put("restSec", st.restSec);
                JSONArray ids = new JSONArray();
                for (int j = 0; j < st.setIds.size(); j++) ids.put(st.setIds.get(j));
                so.put("sets", ids);
                JSONArray vals = new JSONArray();
                for (int j = 0; j < st.sets.size(); j++) vals.put(st.sets.get(j).toJson());
                so.put("setv", vals);
                a.put(so);
            }
            o.put("stages", a);
            return o;
        }

        /** Null in, null out — the absence of a snapshot is itself the record. */
        public static AsRunSnapshot fromJson(JSONObject o) {
            if (o == null) return null;
            AsRunSnapshot s = new AsRunSnapshot();
            s.routineId = o.optString("rid", "");
            JSONArray a = o.optJSONArray("stages");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject so = a.optJSONObject(i);
                if (so == null) continue;
                StageSnap st = new StageSnap();
                st.name = so.optString("name", "");
                // MIGRATION: absent on every recording written before rest stages existed.
                // false / 0 — "this stage was not a rest, and no duration was recorded" —
                // which is the truth about all of them.
                st.rest = so.optBoolean("rest", false);
                st.restSec = so.optInt("restSec", 0);
                JSONArray ids = so.optJSONArray("sets");
                if (ids != null) for (int j = 0; j < ids.length(); j++) st.setIds.add(ids.optString(j));
                // MIGRATION: absent on every recording written before set values were
                // snapshotted. An empty list is exactly "this snapshot knows the structure
                // and nothing else", which is what those recordings are — matches() then
                // answers on the structure alone and blocks() reads the live set.
                JSONArray vals = so.optJSONArray("setv");
                if (vals != null) for (int j = 0; j < vals.length(); j++)
                    st.sets.add(SetSnap.fromJson(vals.optJSONObject(j)));
                s.stages.add(st);
            }
            return s;
        }
    }

    /**
     * One session, as it actually happened — the row History files and the numbers the
     * summary screen shows. Everything here is a RECORD OF DELIVERY, never a copy of
     * the routine's configuration: `durSec` is the elapsed the run screen displayed,
     * `peakKpa` is the deepest vacuum telemetry actually reported (null when the run
     * produced no real reading at all), `doseKpaS` is the integrated delivered dose,
     * and `presetsDone` is how many presets were actually started. The prototype read
     * the first three off the routine instead and presented them under a card headed
     * "Delivered" (defects #06/#07/#08); nothing in this class can be filled in from a
     * routine that never ran.
     *
     * `routineName` is a SNAPSHOT taken at filing time so a session filed against a
     * routine that is later renamed or deleted still reads as what was actually run —
     * the id is kept alongside it only to link back when the routine still exists.
     */
    public static final class Sess {
        public String id;
        public String routineId, routineName;
        public long ts;                 // epoch millis at filing
        public long durSec;             // the SAME elapsed the summary displayed
        public boolean completed;       // false = stopped early (an attempt)
        /** True when this was a MANUAL run (Task 18) — an ephemeral cycle, not a routine.
         *  It is filed to History like any other session so the record says what happened,
         *  but Summary#of and the streak deliberately EXCLUDE it: a manual cycle is not a
         *  completion of a routine that was never run, so it must not move the streak, the
         *  session counts or the completion percentage. Absent (false) on every session
         *  filed before this existed, which is the truth about them. */
        public boolean manual;
        public String tag = "";         // Summary.tag() — what actually happened
        public Double peakKpa;          // null = the run produced no real reading
        public double doseKpaS;         // integrated above Session.DOSE_FLOOR_KPA
        public int presetsDone, presetsPlanned;
        /** The after-session measurement deltas in cm, or null when no after-reading
         *  was logged for this session. Kept as NUMBERS, not a pre-formatted string,
         *  so every screen can colour them by their own sign — defect #30's root
         *  cause was the summary re-printing a flat string in a hardcoded green. */
        public Double afterLenCm, afterGirCm;
        /** Whether the after-reading was taken under the same hold conditions as the
         *  baseline it is diffed against. False with deltas present means "measured,
         *  but not like-for-like" — never silently shown as a clean improvement. */
        public boolean afterComparable;

        /* ---- STOP AT TARGET (trainer-v3 Phase 3 item 1) ------------------------------ *
         *  What a mid-run girth check found, kept on the SESSION rather than in the
         *  reading log: these are transient figures taken through a cylinder mid-session,
         *  not measurements of a body at rest, and letting them into the log would put them
         *  on a trend chart beside readings taken under completely different conditions.   */

        /** The girth this session started from, cm - 0 when none was known. */
        public double startGirthCm;
        /** The SET at which the level's yield target was first met, 1-based - 0 meaning it
         *  was never checked or never met. Absent (0) on every session filed before the
         *  mid-run check existed, which is the truth about all of them. */
        public int targetHitAtSet;
        /** How many sets the session was PLANNED to run, so the fraction that decides
         *  "early" can be computed later from the record rather than re-derived from a
         *  prescription that may since have changed. 0 = unknown. */
        public int setsPlanned;

        /**
         * HOW THE SESSION FELT - 0 never asked, 1 green, 2 orange, 3 red.
         *
         * 0 IS THE MIGRATION TRUTH and not a fourth colour: every session filed before the
         * question existed was never asked it, and reading "never asked" as "green" would
         * invent a report from every one of them.
         */
        public int signal;
        public static final int SIGNAL_NONE = 0, SIGNAL_GREEN = 1,
                                SIGNAL_ORANGE = 2, SIGNAL_RED = 3;

        /** How far into the planned sets the target was hit, 0..1 - NaN when it was not hit
         *  or the plan is unknown. The one place that division is written. */
        public double targetHitFraction() {
            if (targetHitAtSet <= 0 || setsPlanned <= 0) return Double.NaN;
            return (double) targetHitAtSet / (double) setsPlanned;
        }

        /** Q2 - the net this session's routine was PRESCRIBED to deliver, copied from the
         *  routine at filing time so it survives the routine being re-minted or deleted.
         *  null = unknown (every session filed before this existed, and every off-plan run);
         *  0.0 = "no net was asked for", which is what a reduced prescription means. */
        public Double netTargetMin;

        /**
         * 0.10 - HOW FAR THIS SESSION'S ROUTINE SAT FROM THE PLAN'S FIGURE, kPa
         * (Routine#trainerScaleKpa), copied at filing like {@link #netTargetMin} so it
         * survives the routine being re-minted. The Level 1 gate reads the session's peak on
         * the plan's scale (Scale#onPlanScaleKpa): an offset or the Program's bias neither
         * earns nor withholds a level.
         *
         * MIGRATION: 0 - every session filed before this ran on the plan's own scale, as far
         * as the gate ever knew.
         */
        public int scaleKpa;

        /**
         * 0.10 - THE MINUTES OF THIS SESSION'S ROUTINE'S CLIMBING HOLDS UNDER THE COUNTING LINE
         * (Routine#climbUnderLineMin), copied at filing like {@link #netTargetMin}: run, not
         * counted and not in the target. The level's net tests credit them at the rate this
         * session delivered its target (TrainerTab#creditedNet), so a Ramped session that did
         * all it was asked reads as the plan's minutes.
         *
         * MIGRATION: 0 - no credit; a session filed before this had no uncounted climb holds,
         * or its routine made them up in full.
         */
        public double climbUnderLineMin;

        /**
         * 0.10 - WHAT A BOTH-TRACKS DAY TOOK OUT OF THIS RUN, as {@link RunShape.Built#code}
         * wrote it at START: "w" (warm-up skipped, the person's answer), "t" (no tissue
         * test, the day's second session), "g5:10.0" (five girth sets and the ten minutes of
         * net they were asked for, given to the day's length session), "x" (expansion only,
         * the person's choice). "" for a run exactly as saved.
         *
         * A RECORD, and read for two things: whether a length session really pulled (S09's
         * gap follows only a pull), and the net the girth track credits back when the day's
         * length session ran (TrainerTab#recentTrackedNets).
         *
         * MIGRATION: "" - every session filed before this ran as its routine was saved.
         */
        public String shape = "";

        /**
         * 0.10 (S06) - A LENGTH RUN THAT DELIVERED ITS EXPANSION: every planned cycle of its
         * last expansion stage recorded, none skipped (RunShape#expansionDelivered), decided
         * at filing from the recording. Only such a session earns a girth session of its day
         * the credit for the sets it gave up (TrainerTab#recentTrackedNets) - a run stopped in
         * the tunica release did none of that work (the 0.10 safety review).
         *
         * MIGRATION: false. A session filed before this was never judged, and no credit is
         * owed to it: only a girth session shaped by 0.10 can ask for one.
         */
        public boolean expansionDone;

        /**
         * t10 O8 - THIS SESSION RAN ON A REDUCED DAY OF THE RETURN after a deload: the taper's
         * cut was in force on its day (Deload#reducedOn), stamped at filing. A length reading
         * taken on such a day still counts (O5: the traction blocks pull their full load) and
         * counts for "this block reached 2 %", but not toward the one-week low streak
         * (Meas#strainMissDays).
         *
         * MIGRATION: false. A session filed before this was not recorded as one, and its
         * reading stays in the streak, as it always was.
         */
        public boolean returnDay;

        /**
         * B3 - WHAT THIS SESSION'S OWN ROUTINE PLANNED, in the two counts the app times a
         * session by, copied from the routine at filing time for the same reason
         * {@link #netTargetMin} is: a summary reopened from History must compare the run
         * with the routine AS IT WAS, not as it has since been edited, re-minted or deleted.
         *
         * {@code plannedSec} is the CLOCK plan - every preset, rests and warm-up included,
         * plus the after-assessment pull - which is exactly the span {@link #durSec} is
         * measured over ({@link PlannedTime#clockSec}). 0 = unknown.
         *
         * {@code plannedTupSec} is the UNDER-PRESSURE plan - the hold half of every set
         * outside a rest or an out-of-net stage, which is exactly what {@link #netTupSec}
         * can count ({@link PlannedTime#underPressureSec}). null = unknown; 0.0 is a real
         * answer (a routine with no work in it).
         *
         * MIGRATION: 0 / null on every session filed before B3. The summary then says
         * nothing about a plan it never recorded - except that a Trainer session's
         * {@link #netTargetMin} already IS its routine's under-pressure plan, so that one
         * still compares like with like.
         */
        public long plannedSec;
        public Double plannedTupSec;

        /**
         * C5 - WHAT THE LIVE SUMMARY KNEW, FILED WITH THE SESSION IT BELONGS TO.
         *
         * The summary is drawn from this record whether the run has just ended or the row is
         * reopened from History. A few of its figures used to be read from the Activity
         * instead, where the run that last filed had left them - so a reopened summary showed
         * the LIVE run's "cycles 39 of 39 planned" on a session that ran 8 of 14, and set its
         * own peak against another session's commanded one. They are copied here at filing
         * time, the one moment they are all known, and both summaries read them from here.
         *
         * {@code cyclesPlanned} / {@code cyclesDone}: the cycles the plan commanded and the
         * ones the recording says were delivered, both counted by the one rule
         * (SessionActivity#plannedCycles / #deliveredCycles). 0 / -1 = not recorded.
         *
         * {@code cmdPeakKpa}: the deepest pressure the pump was asked for, over the same
         * stretch the observed peak spans ({@link Summary#commandedPeakKpa}); null = not
         * recorded. {@code afterPullKpa} and {@code carriedInKpa} are the parts of it that came
         * from the after-assessment pull and from what a phase before the run left on the
         * cuff ({@code carriedFromPull}: the before-pull, else the seal check), and
         * {@code noReadSamples} counts the readings that carried no pressure and were left out
         * of the dose. The Noticed card names each, so none is charged to the routine's sets.
         *
         * {@code noPeakWhy}: with no peak, why there is none ({@link Summary#NO_PEAK_NEVER_RAN}
         * and its siblings); 0 = not recorded.
         *
         * MIGRATION: absent on every session filed before C5 - "not recorded" throughout,
         * which the summary answers by comparing with nothing rather than with another
         * session's figures.
         */
        public int cyclesPlanned;
        public int cyclesDone = -1;
        /**
         * C6 - HOW LONG "AT PRESSURE ONLY" HELD THIS RUN'S SETS STILL, in seconds: the time the
         * set clock added waiting for pressure (and past a set's planned drops). -1 = not
         * recorded (every session filed before C6); 0 = it held nothing, which is every run by
         * the clock.
         *
         * WHY IT IS FILED. A set the clock lengthens goes on cycling, so such a run delivers
         * more cycles than it planned - legitimately. The cycles row has to say so rather
         * than read as an over-run, and whether it happened is a fact about THIS run, not
         * about the Set timing setting as it stands when the summary is reopened.
         *
         * AND {@code cyclesPlanned} IS THE PLAN AS THE RUN STARTED (SessionActivity's
         * cyclesPlannedAtStart). It was counted over the run's live plan at filing, which the
         * at-pressure clock and +30 s lengthen as the run goes, so a 7-cycle routine filed
         * "13 of 13 planned". Sessions filed before C6 keep the figure they were filed with.
         */
        public int tupPausedSec = -1;
        /**
         * D2 - HOW MANY SETS OF THIS RUN ENDED AT THEIR TIME LIMIT (TupClock.SetLimit): timed
         * at pressure, the clock stopped waiting for them and they ended without their
         * planned time at pressure. -1 = not recorded (every session filed before D2); 0 =
         * none, which is every run by the clock.
         *
         * WHY IT IS FILED. The run screen says it in one sentence as each such set ends - and
         * the last set of a routine ends straight into the summary and its count question,
         * which draw over that sentence. So the Delivered card says it too, from the record,
         * and a summary reopened from History says it of its own run.
         */
        public int setsAtLimit = -1;
        /**
         * HOW MANY TIMES THIS SESSION WAS REJOINED after the app closed mid-run, and RESUMED
         * after STOP (the owner's pick on the leftovers, option A; RunParts). A rejoined run is
         * one session - its parts' time, time at pressure, dose and cycles on this one record -
         * and the summary says so in one line. 0 on every session filed before it, and on every
         * session that ran in one part; written only when not 0, so those records are as they
         * were byte for byte.
         */
        public int rejoins, resumes;
        /**
         * (the safety review of the in-run Hold's limit) WHY A STOPPED RUN STOPPED ON ITS OWN -
         * RunStopReason's WHY_*: the Hold's limit (with the limit it ran to, stopLimSec) or the
         * two-hour stop; WHY_NONE for everything else, and for every session filed before it.
         * The fact is filed and the words are RunStopReason's, so the summary - live or
         * reopened from History - says it in one line that stays, where it used to be a
         * three-second snackbar a locked phone never showed.
         */
        public int stopWhy = RunStopReason.WHY_NONE;
        public int stopLimSec;
        /**
         * E2-4 (by hand, 2026-10-07): THE RUN STOPPED AT THE START CHECK AFTER ITS BY-HAND
         * STEP, and this is how long it ran - the minutes by hand - in seconds; 0 for every
         * other session. The summary says that (ByHand#stoppedAtCheck) instead of "ran exactly
         * to plan" or a peak set against nothing asked. Written only when not 0, so every
         * other record is as it was byte for byte.
         *
         * MIGRATION: absent on every session filed before it - 0, the truth about them.
         */
        public long byHandStopSec;
        public Double cmdPeakKpa;
        public int afterPullKpa, carriedInKpa;
        public boolean carriedFromPull;
        public int noReadSamples;
        public int noPeakWhy;

        /** This session was run against the SIMULATED pump - no cuff, no radio, nothing
         *  under pressure. False on every session filed before the simulator existed, which
         *  is the truth about them: they were all real. */
        public boolean sim;
        /** WHICH baseline the deltas above were measured against: that reading's
         *  timestamp, and whether it was logged for THIS session.
         *
         *  FINAL REVIEW, Important. The summary captioned every delta "measured against
         *  the baseline logged before this session". It is diffed against
         *  measLog.latest() — the newest reading in the whole log — and the DEFAULT
         *  cadence is "every 5 sessions", so four sessions out of five log no baseline at
         *  all and the newest reading is routinely days or weeks old. Model.Reading
         *  #comparable() looks at hold pressure and hold seconds and never at time, so a
         *  weeks-old baseline passes as fully like-for-like and the caption presented a
         *  multi-session accumulated change as this session's acute one — the exact
         *  opposite of what the note two lines below it tells the user to believe.
         *
         *  Recorded on the RECORD rather than derived on the screen so the History row
         *  and the summary cannot disagree, and so a row can still be read honestly
         *  later. 0 / false on every session filed before this existed, which the copy
         *  handles by claiming nothing about when that baseline was taken. */
        public long afterBaseTs;
        public boolean afterBaseThisSession;
        /** TASK 4 / PD-5: the SAME baseline as afterBaseTs, recorded by reading id
         *  instead of ts. ts is not a unique key — a multi-protocol sitting files N
         *  readings sharing one identical ts (Meas#buildLogReadings) — so a ts-only
         *  lookup (MeasLog#at) can silently resolve to a SIBLING of the reading this
         *  session actually measured against once any member of that sitting's ts is
         *  edited. id IS unique and never changes when a reading's ts is corrected
         *  (Reading#copy() carries it verbatim; SaveMeasTap never rewrites it), so every
         *  reader resolves through {@link MeasLog#byId} first and falls back to
         *  {@link MeasLog#at}(afterBaseTs) only for a session filed before this field
         *  existed (null/"" here). afterBaseTs is kept alongside it — never removed —
         *  both as that legacy fallback and because callers that only want to know WHEN
         *  the baseline was (not resolve the object) still read it directly. */
        public String afterBaseId;

        /** The TISSUE ADAPTATION ASSESSMENT this session produced (Task 15) — the rise
         *  curve tau before the work and after it, in seconds, or null where tau was
         *  not computable. Null is never 0.0: a refusal is not a rise that took no
         *  time. `tauBeforeWhy`/`tauAfterWhy` carry Tau's reason code so the summary
         *  and History can say WHY there is no number instead of showing a blank; ""
         *  means that end never ran at all. */
        public Double tauBeforeSec, tauAfterSec;
        public String tauBeforeWhy = "", tauAfterWhy = "";
        /** What each pull was measured FROM and what it reached — the observed start
         *  pressure and the top, in kPa, or null where that end produced no
         *  measurement. Recorded because "an identical short pull at both ends" is a
         *  claim about the pulls, not only about their configuration: Tau#comparablePulls
         *  asks these before any Delta tau is shown, so a pair whose two ends actually
         *  behaved differently is refused instead of averaged into a percentage.
         *  Absent on every session filed before this was recorded, which correctly
         *  withdraws the Delta tau from rows computed under maths that never gated the
         *  starting pressure. */
        public Double tauBeforeP0Kpa, tauAfterP0Kpa;
        public Double tauBeforePeakKpa, tauAfterPeakKpa;
        /** The STIMULUS the taus above were measured under, snapshotted at filing time.
         *  tau scales with pump rate, so without this a later trend cannot tell whether
         *  two numbers are comparable — and would plot them on one line as if they
         *  were. 0 means no assessment was configured for this session. */
        public int assessKpa, assessSp, assessDurSec;

        /* ---- what was RUN, and what was SAVED out of it (the save-what-you-ran card) ---- */

        /** Whether an as-run recording exists for this session in asrun.json. False on
         *  every session filed before the recorder existed and on any run whose rows were
         *  trimmed away, which is the honest answer: the card cannot be reopened for a run
         *  nothing was recorded of. Never inferred from the presence of the file. */
        public boolean hasAsRun;
        /** Whether the recording DIFFERS from the plan (AsRun.differs of its blocks),
         *  computed ONCE at filing time and persisted — so the History list can label its
         *  rows without loading and re-deriving every recording on every render (up to 40
         *  loads per tap, on a 90-day log). False on every session filed before this field
         *  existed, which under-offers ("no door") rather than fabricating a difference;
         *  false whenever hasAsRun is false. Never re-derived by the UI. */
        public boolean differs;
        /** The ids of the sets this run's save card actually created — empty when nothing
         *  was saved (the overwhelming majority of sessions). Kept so a reopened card can
         *  show those blocks as "already saved as …" instead of offering to save them a
         *  second time, and so History can say "saved ▸". */
        public final List<String> savedSetIds = new ArrayList<String>();
        /** The routine the save created or updated, or null when the save produced sets
         *  only (or nothing at all). Null, never "": an empty id would resolve to no
         *  routine and read as a routine that has since been deleted. */
        public String savedRoutineId;
        /** When the save happened, epoch millis; 0 = never saved. Separate from ts because
         *  a card may be reopened and committed from History long after the run. */
        public long savedAt;

        /** HOW IT FELT — 0 none, 1 easy, 2 fine, 3 tough. 0 on every session filed before
         *  the chips existed and on every session nobody answered, which are the same
         *  thing: no claim about how it felt. Never a default of "fine". */
        public int feel;
        /** The free-text note left on the summary. "" is no note — never null, so no screen
         *  has to guard before measuring its length. */
        public String note = "";

        /** The ABSOLUTE after-session measurements in cm (length and girth), or null where
         *  no after-reading was logged. afterLenCm/afterGirCm above are the DELTAS; these
         *  are the readings those deltas came from, recorded so the summary can show the
         *  two numbers side by side with the change beneath rather than a bare difference
         *  whose ends the screen would have to go and re-derive. */
        public Double afterLenAbsCm, afterGirAbsCm;

        /** The routine as it stood when this run STARTED — see {@link AsRunSnapshot}. Null
         *  for a manual run and for every session filed before it existed. */
        public AsRunSnapshot asRunSnapshot;

        /**
         * TASK 5 (T14+) — "Time at target": counted seconds within band ÷ eligible hold
         * seconds, computed ONCE at finishSession from the run's own per-frame recording
         * (see Session#holdEfficiencyPct / Session#noteHoldFrame) and persisted here —
         * never re-derived later, because the raw frames it came from are never
         * persisted (an in-memory, run-scoped recording, deliberately: see Session's own
         * "Stage E — task 5" class doc for why no new disk structure holds them).
         *
         * NULL IS A SENTINEL, NOT AN ABSENCE OF DATA THE SAME WAY peakKpa's null is: it
         * means EITHER "filed before this existed" OR "this run never reached 60 s of
         * eligible hold time" (T14+'s own guard) OR "this run never tracked at all"
         * (aborted at the seal check) — every one of those reads honestly as "—", never
         * as a fabricated 0%, which is the one thing T14+'s spec explicitly forbids.
         */
        public Double holdEfficiencyPct;

        /**
         * TASK 5 (T14+), FIX ROUND 1 — the SAME figure, per STAGE, so a session reopened
         * from History (not just the one this process just filed) can show "also
         * per-stage in the expanded view" too — the first cut of this task left per-stage
         * live-only, off the still-resident Session instance, which meant a reopened
         * session showed nothing at all past the process that ran it. This is still the
         * only new persisted STRUCTURE this task adds beyond holdEfficiencyPct itself:
         * the raw per-frame recording stays exactly as in-memory/run-scoped as the class
         * doc above describes — only the small, already-computed RESULT array is kept.
         *
         * Indexed by {@link Model.Preset#stageIdx} (the same key {@link AsRun.Block
         * #stageIdx} is stamped from), one entry per stage the routine had AT RUN TIME
         * (see SessionActivity's own fileSession — sized from stageDurMs.length, computed
         * once per attempt in beginRunFlow and frozen for the run's duration, so a later
         * edit to the routine can never resize a past session's own array). Each entry is
         * independently nullable: null means that ONE stage never reached 60 s of
         * eligible hold time (a rest-only or ramp-only stage never will), read back as
         * "—" exactly like the whole-session figure — never a fabricated 0% and never a
         * silently blank row. Empty (never populated) on every session filed before this
         * existed, or one that never tracked at all — the same two cases
         * holdEfficiencyPct's own null already covers, read the same honest way.
         */
        public final List<Double> holdEfficiencyByStagePct = new ArrayList<Double>();

        /**
         * STAGE H TASK 2 — NET / GROSS TIME UNDER PRESSURE, the guide's own delivered-
         * volume figures, computed ONCE at finishSession from the SAME per-frame recording
         * holdEfficiencyPct's own doc describes (see {@link Session#noteHoldFrame}'s
         * extended per-frame pass) — never re-derived later, for the identical reason: the
         * raw frames are never persisted.
         *
         * NET is the time the measured pressure sat at/above the ACTIVE track's level
         * floor ({@link Plan#floorKpa}), excluding no-reading frames and frames whose
         * stage is the trainer's fatigue block ({@link Stage#fatigueBlock} — guide: fatigue
         * "does not feed the gate metric"). GROSS is the same run's real tracked time
         * regardless of the floor or a fatigue marker — "real sealed time" — the honest
         * denominator net/gross is shown against as a diagnostic (plan misuse rule; no
         * gate reads the ratio itself).
         *
         * NULL, NOT 0.0, ON TWO DIFFERENT ABSENCES — both real, neither the same claim:
         * (1) this session was filed before Task 2 existed, or never tracked at all (same
         * two cases {@link #holdEfficiencyPct}'s own null already covers); (2) THE ROUTINE
         * THIS SESSION RAN WAS NOT TRAINER-MARKED ({@link Routine#trainerTrack} ==
         * TRAINER_TRACK_NONE, or the marker is FEEDER — round-2 feeder ruling (a) excludes
         * it from the Net-TUP gate counters outright). A session that genuinely delivered
         * zero seconds under floor while ON a marked track still files 0.0 here — that is
         * a real, countable fact, never confused with "this session doesn't feed a track
         * at all", which is what null means.
         */
        public Double netTupSec, grossTupSec;

        /**
         * PASS H - WHICH LOCAL DAY THIS SESSION HAPPENED ON, decided once, when it happened.
         * 0 when it was never recorded.
         *
         * Everything that counts days - the streak, a week's trained days, the gate that
         * advances a level - re-derived the day from the timestamp with
         * Calendar.getInstance(), which uses whatever timezone the phone is in AT THE MOMENT
         * OF THE READ. That is right for a phone that stays put and wrong the moment one
         * does not: fly east and a session logged at 23:50 becomes the next day, fly west
         * and two sessions collapse onto one. A streak breaks, or a week that qualified
         * stops qualifying, and nothing on screen explains why.
         *
         * A session happened on the day it was for the person at the time. That is a fact
         * about the session, so it is stored on the session rather than recomputed from a
         * clock that has since moved - the same reasoning that stamps the trainer LEVEL onto
         * a routine rather than re-scoring old sessions against a level reached later.
         *
         * MIGRATION: 0, and every reader falls back to deriving it. That is exactly what
         * those readers did before this existed, so an old record behaves as it always has
         * and only new ones gain the guarantee.
         */
        public int dayKey;

        /**
         * F3b - THE TRACK THE PERSON EXPLICITLY COUNTED THIS SESSION TOWARD, or
         * {@link #TRAINER_TRACK_NONE}.
         *
         * A one-off - a set tried from the library, a manual cycle - runs no routine, so
         * {@link Routine#trainerTrack} has nothing to say about it and the work vanishes
         * from the plan's view of the week. The ruling was to ALWAYS ASK afterwards rather
         * than to guess, and this is that answer.
         *
         * THE SAFEGUARD IS WHAT THIS FIELD DELIBERATELY DOES NOT DO. A counted session
         * adds its Net TUP to the week's delivered total and NOTHING ELSE: it is not a
         * trained day, it does not fill a tracked-session slot, and it never enters
         * {@link TrainerTab#recentTrackedNetsMin}, which is the per-session series the
         * level gates are judged on. Without that separation a two-minute one-off would
         * enter the gate series as a session that delivered two minutes and drag the
         * metric down - the person would be punished for logging honest extra work.
         *
         * MIGRATION: NONE. Nobody has answered a question that did not exist, and the
         * shape of an old session cannot be read as an answer to it.
         */
        public int countedTrack = TRAINER_TRACK_NONE;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id == null ? "" : id);
            o.put("rid", routineId == null ? "" : routineId);
            o.put("countedTrack", countedTrack);
            o.put("rname", routineName == null ? "" : routineName);
            // Longs and doubles go through strings: the desktop org.json shim has
            // neither optLong nor optDouble (see Reading.fromJson's note), so this
            // keeps the phone and the self-test on the same code path.
            o.put("ts", String.valueOf(ts));
            o.put("dur", String.valueOf(durSec));
            o.put("done", completed);
            o.put("man", manual);
            o.put("tag", tag == null ? "" : tag);
            // Doubles as strings, ints as ints - the shape both readers decode.
            o.put("startGir", String.valueOf(startGirthCm));
            o.put("hitAtSet", targetHitAtSet);
            o.put("setsPlanned", setsPlanned);
            o.put("signal", signal);
            o.put("peak", peakKpa == null ? "" : String.valueOf(peakKpa.doubleValue()));
            o.put("dose", String.valueOf(doseKpaS));
            o.put("pd", presetsDone);
            o.put("pp", presetsPlanned);
            o.put("dlen", afterLenCm == null ? "" : String.valueOf(afterLenCm.doubleValue()));
            o.put("dgir", afterGirCm == null ? "" : String.valueOf(afterGirCm.doubleValue()));
            o.put("acmp", afterComparable);
            if (netTargetMin != null) o.put("netTgt", String.valueOf(netTargetMin));
            if (scaleKpa != 0) o.put("scale", scaleKpa);
            if (climbUnderLineMin > 0.0) o.put("climbUnder", String.valueOf(climbUnderLineMin));
            if (shape != null && shape.length() > 0) o.put("shape", shape);
            if (expansionDone) o.put("expDone", true);
            if (returnDay) o.put("retDay", true);   // t10 O8: only when true
            if (rejoins > 0) o.put("rejn", rejoins);  // RunParts: only when not 0
            if (resumes > 0) o.put("resm", resumes);
            // B3. Long and nullable double through strings, like every figure beside them.
            o.put("plSec", String.valueOf(plannedSec));
            o.put("plTup", plannedTupSec == null ? "" : String.valueOf(plannedTupSec.doubleValue()));
            // C5. Ints as ints, the nullable peak through a string like every figure here.
            o.put("cycPl", cyclesPlanned);
            o.put("cycDid", cyclesDone);
            o.put("tupP", tupPausedSec);          // C6
            o.put("tupLim", setsAtLimit);         // D2
            // (the safety review) only when there is a reason, so every other record is as
            // it was byte for byte.
            if (stopWhy != RunStopReason.WHY_NONE) {
                o.put("stWhy", stopWhy);
                o.put("stLim", stopLimSec);
            }
            if (byHandStopSec > 0) o.put("bhChk", byHandStopSec);
            o.put("cmdPk", cmdPeakKpa == null ? "" : String.valueOf(cmdPeakKpa.doubleValue()));
            o.put("aPull", afterPullKpa);
            o.put("carIn", carriedInKpa);
            if (carriedFromPull) o.put("carPull", true);
            o.put("noRd", noReadSamples);
            o.put("npWhy", noPeakWhy);
            if (sim) o.put("sim", true);
            o.put("abts", String.valueOf(afterBaseTs));
            o.put("absame", afterBaseThisSession);
            // "" is the on-disk spelling of null, the same absence convention every
            // nullable id/string in this class uses (see svrid below).
            o.put("abid", afterBaseId == null ? "" : afterBaseId);
            o.put("taub", tauBeforeSec == null ? "" : String.valueOf(tauBeforeSec.doubleValue()));
            o.put("taua", tauAfterSec == null ? "" : String.valueOf(tauAfterSec.doubleValue()));
            o.put("taubw", tauBeforeWhy == null ? "" : tauBeforeWhy);
            o.put("tauaw", tauAfterWhy == null ? "" : tauAfterWhy);
            o.put("taub0", tauBeforeP0Kpa == null ? "" : String.valueOf(tauBeforeP0Kpa.doubleValue()));
            o.put("taua0", tauAfterP0Kpa == null ? "" : String.valueOf(tauAfterP0Kpa.doubleValue()));
            o.put("taubpk", tauBeforePeakKpa == null ? "" : String.valueOf(tauBeforePeakKpa.doubleValue()));
            o.put("tauapk", tauAfterPeakKpa == null ? "" : String.valueOf(tauAfterPeakKpa.doubleValue()));
            o.put("askpa", assessKpa); o.put("assp", assessSp); o.put("asdur", assessDurSec);
            o.put("hasar", hasAsRun);
            o.put("asrunDiff", differs);
            JSONArray saved = new JSONArray();
            for (int i = 0; i < savedSetIds.size(); i++) saved.put(savedSetIds.get(i));
            o.put("svsets", saved);
            // "" is the on-disk spelling of null here, read back as null below — the same
            // absence convention the nullable doubles use.
            o.put("svrid", savedRoutineId == null ? "" : savedRoutineId);
            o.put("svat", String.valueOf(savedAt));
            o.put("feel", feel);
            o.put("note", note == null ? "" : note);
            o.put("alenabs", afterLenAbsCm == null ? "" : String.valueOf(afterLenAbsCm.doubleValue()));
            o.put("agirabs", afterGirAbsCm == null ? "" : String.valueOf(afterGirAbsCm.doubleValue()));
            // Wrapped in a one-element array, and the KEY IS OMITTED when there is no
            // snapshot: the desktop org.json shim has optJSONArray but no
            // optJSONObject(String) (see Model#toJson), and an absent key is how "manual
            // run, or filed before this existed" is spelled on disk.
            if (asRunSnapshot != null) {
                JSONArray snapA = new JSONArray();
                snapA.put(asRunSnapshot.toJson());
                o.put("asrs", snapA);
            }
            // Task 5 (T14+). Through a string, null = "", the same nullable-double
            // convention every other optional figure on this record already uses.
            o.put("holdeff", holdEfficiencyPct == null ? ""
                : String.valueOf(holdEfficiencyPct.doubleValue()));
            // Task 5 (T14+), fix round 1: one string per stage, same "" = null
            // convention, in an array — the org.json shim's JSONArray has no notion of a
            // null ELEMENT (see JSONArray#put), so the per-entry sentinel is spelled the
            // identical way the scalar figure just above spells it.
            JSONArray holdEffSt = new JSONArray();
            for (int i = 0; i < holdEfficiencyByStagePct.size(); i++) {
                Double v = holdEfficiencyByStagePct.get(i);
                holdEffSt.put(v == null ? "" : String.valueOf(v.doubleValue()));
            }
            o.put("holdeffst", holdEffSt);
            // Stage H Task 2. Same nullable-double-through-a-string convention as holdeff
            // just above — "" is the on-disk spelling of null, here meaning either "never
            // tracked" or "this session's routine was not a marked trainer track" (see the
            // fields' own doc for why those are the two real absences, never a fabricated
            // 0.0 for either).
            o.put("dayKey", dayKey);
            o.put("netTup", netTupSec == null ? "" : String.valueOf(netTupSec.doubleValue()));
            o.put("grossTup", grossTupSec == null ? ""
                : String.valueOf(grossTupSec.doubleValue()));
            return o;
        }

        public static Sess fromJson(JSONObject o) {
            Sess s = new Sess();
            if (o == null) return s;
            s.id = o.optString("id");
            s.routineId = o.optString("rid", "");
            // MIGRATION: NONE — nobody answered a question that did not exist.
            s.countedTrack = o.optInt("countedTrack", TRAINER_TRACK_NONE);
            s.routineName = o.optString("rname", "");
            s.ts = parseLong(o.optString("ts", "0"));
            s.durSec = parseLong(o.optString("dur", "0"));
            s.completed = o.optBoolean("done");
            // Absent (false) on every session filed before Task 18 — a routine run, which is
            // the truth about them. Never inferred from anything else.
            s.manual = o.optBoolean("man");
            s.tag = o.optString("tag", "");
            // MIGRATION: 0 and 0.0 on every session filed before the mid-run check existed -
            // which is exactly "it was never checked", the truth about all of them. A
            // targetHitFraction of NaN follows from it, and NaN is the deload rule's own
            // "no data", so an upgrading user proposes nothing on their history.
            try { s.startGirthCm = Double.parseDouble(o.optString("startGir", "0")); }
            catch (NumberFormatException e) { s.startGirthCm = 0; }
            s.targetHitAtSet = o.optInt("hitAtSet", 0);
            s.setsPlanned = o.optInt("setsPlanned", 0);
            s.signal = o.optInt("signal", Sess.SIGNAL_NONE);
            // MIGRATION: the tag is persisted as the literal display string, so the
            // Task 14 rewording of "no assessment" (which meant no AFTER-MEASUREMENT)
            // to "no after-measurement" would otherwise reinterpret every row already
            // filed — leaving old sessions labelled with a word that now means the
            // tissue adaptation assessment instead. Mapped forward here so History
            // reads consistently across the upgrade; nothing ever writes the old
            // spelling again.
            if (Summary.LEGACY_TAG_NO_ASSESSMENT.equals(s.tag)) s.tag = Summary.TAG_NO_AFTER;
            s.peakKpa = parseNullableDouble(o.optString("peak", ""));
            Double dose = parseNullableDouble(o.optString("dose", "0"));
            s.doseKpaS = dose == null ? 0 : dose.doubleValue();
            s.presetsDone = o.optInt("pd");
            s.presetsPlanned = o.optInt("pp");
            s.afterLenCm = parseNullableDouble(o.optString("dlen", ""));
            s.afterGirCm = parseNullableDouble(o.optString("dgir", ""));
            s.afterComparable = o.optBoolean("acmp");
            // ABSENT MEANS UNKNOWN, not zero: a session filed before Q2 existed never
            // recorded what it was asked for, and guessing would put a made-up target into
            // the one comparison that can cost somebody a level.
            s.sim = o.optBoolean("sim", false);
            String ntg = o.optString("netTgt", "");
            if (ntg.length() > 0) {
                try { s.netTargetMin = Double.valueOf(Double.parseDouble(ntg)); }
                catch (NumberFormatException e) { s.netTargetMin = null; }
            }
            // 0.10 MIGRATION: 0 - on the plan's own scale (Sess#scaleKpa). Held to the same
            // reach as a routine's (review 2, finding 6): the Level 1 gate reads a session's
            // pulls back through it, and a hand-edited file must not drop the line to nothing.
            s.scaleKpa = Scale.clampScaleKpa(o.optInt("scale", 0));
            // 0.10 MIGRATION: 0 - no uncounted climb holds to credit (Sess#climbUnderLineMin);
            // held to [0, CLIMB_UNDER_MAX_MIN] like the routine's.
            s.climbUnderLineMin = clampClimbUnder(parseDoubleOr(o.optString("climbUnder", ""), 0.0));
            // 0.10 MIGRATION: "" - a session filed before a day could shape a run ran as
            // its routine was saved.
            s.shape = o.optString("shape", "");
            s.expansionDone = o.optBoolean("expDone", false);
            // t10 O8 MIGRATION: false - not recorded as a reduced return day.
            s.returnDay = o.optBoolean("retDay", false);
            // MIGRATION (RunParts): absent on every session filed before it, and on every
            // session that ran in one part - none. A negative count is none.
            s.rejoins = Math.max(0, o.optInt("rejn", 0));
            s.resumes = Math.max(0, o.optInt("resm", 0));
            // B3 MIGRATION: absent on every session filed before it - 0 and null, "the plan
            // was not recorded", which the summary answers by comparing with nothing.
            s.plannedSec = parseLong(o.optString("plSec", "0"));
            s.plannedTupSec = parseNullableDouble(o.optString("plTup", ""));
            // C5 MIGRATION: absent on every session filed before it - "not recorded", so a
            // reopened summary states what the record holds and compares nothing else.
            s.cyclesPlanned = o.optInt("cycPl", 0);
            s.cyclesDone = o.optInt("cycDid", -1);
            // C6 MIGRATION: absent before C6 - "not recorded", so the cycles row states the
            // counts the session was filed with and gives no reason it cannot know.
            s.tupPausedSec = o.optInt("tupP", -1);
            // D2 MIGRATION: absent before D2 - "not recorded", so a reopened summary says
            // nothing about a limit that did not exist when it ran.
            s.setsAtLimit = o.optInt("tupLim", -1);
            // MIGRATION: absent before the safety review of the in-run Hold's limit, and on
            // every session no limit stopped - no reason, and none is guessed.
            s.stopWhy = o.optInt("stWhy", RunStopReason.WHY_NONE);
            s.stopLimSec = o.optInt("stLim", 0);
            // MIGRATION (E2-4): absent before it, and on every run not stopped at the start
            // check after a step by hand - 0.
            s.byHandStopSec = Math.max(0L, o.optLong("bhChk", 0L));
            s.cmdPeakKpa = parseNullableDouble(o.optString("cmdPk", ""));
            s.afterPullKpa = o.optInt("aPull", 0);
            s.carriedInKpa = o.optInt("carIn", 0);
            s.carriedFromPull = o.optBoolean("carPull", false);
            s.noReadSamples = o.optInt("noRd", 0);
            s.noPeakWhy = o.optInt("npWhy", 0);
            // MIGRATION: absent on every row filed before the final review. 0 means "which
            // baseline is not recorded", which the summary says by claiming nothing about
            // its age — never by guessing that it was this session's.
            s.afterBaseTs = parseLong(o.optString("abts", "0"));
            s.afterBaseThisSession = o.optBoolean("absame");
            // MIGRATION: absent on every row filed before Task 4 (PD-5) — null, which
            // every reader treats as "fall back to the legacy ts-based lookup", never as
            // "no baseline was recorded" (that claim is afterBaseTs == 0, unchanged).
            String abid = o.optString("abid", "");
            s.afterBaseId = abid.length() == 0 ? null : abid;
            // Absent in every session filed before Task 15: no tau, no stimulus, and
            // "" for both reasons — which reads as "the assessment never ran", the
            // truth about a session recorded before the feature existed.
            s.tauBeforeSec = parseNullableDouble(o.optString("taub", ""));
            s.tauAfterSec = parseNullableDouble(o.optString("taua", ""));
            s.tauBeforeWhy = o.optString("taubw", "");
            s.tauAfterWhy = o.optString("tauaw", "");
            s.tauBeforeP0Kpa = parseNullableDouble(o.optString("taub0", ""));
            s.tauAfterP0Kpa = parseNullableDouble(o.optString("taua0", ""));
            s.tauBeforePeakKpa = parseNullableDouble(o.optString("taubpk", ""));
            s.tauAfterPeakKpa = parseNullableDouble(o.optString("tauapk", ""));
            s.assessKpa = o.optInt("askpa"); s.assessSp = o.optInt("assp");
            s.assessDurSec = o.optInt("asdur");
            // MIGRATION, all of it: absent on every session filed before the
            // save-what-you-ran card existed. No recording, nothing saved, no feel, no
            // note, no absolute after-readings and no routine snapshot — which is exactly
            // what those runs were. Nothing here is ever inferred from another field.
            s.hasAsRun = o.optBoolean("hasar");
            // MIGRATION: absent on every session filed before the label was persisted —
            // false, so an old row shows no door rather than a fabricated difference.
            s.differs = o.optBoolean("asrunDiff");
            JSONArray saved = o.optJSONArray("svsets");
            if (saved != null) for (int i = 0; i < saved.length(); i++) {
                String id = saved.optString(i);
                if (id != null && id.length() > 0) s.savedSetIds.add(id);
            }
            String svrid = o.optString("svrid", "");
            s.savedRoutineId = svrid.length() == 0 ? null : svrid;
            s.savedAt = parseLong(o.optString("svat", "0"));
            s.feel = o.optInt("feel");
            s.note = o.optString("note", "");
            s.afterLenAbsCm = parseNullableDouble(o.optString("alenabs", ""));
            s.afterGirAbsCm = parseNullableDouble(o.optString("agirabs", ""));
            JSONArray snapA = o.optJSONArray("asrs");
            s.asRunSnapshot = AsRunSnapshot.fromJson(
                    snapA != null && snapA.length() > 0 ? snapA.optJSONObject(0) : null);
            // MIGRATION: absent on every session filed before Task 5 — null, which reads
            // as "—", the honest answer for a run this feature never measured.
            s.holdEfficiencyPct = parseNullableDouble(o.optString("holdeff", ""));
            // MIGRATION, same rule: absent on every session filed before fix round 1 —
            // an empty list, which every stage's own lookup reads back as "—" exactly
            // like the whole-session figure's own absence does.
            JSONArray holdEffSt = o.optJSONArray("holdeffst");
            if (holdEffSt != null)
                for (int i = 0; i < holdEffSt.length(); i++)
                    s.holdEfficiencyByStagePct.add(parseNullableDouble(holdEffSt.optString(i)));
            // Stage H Task 2 MIGRATION: absent on every session filed before this existed —
            // null, the honest "this run predates Net/Gross TUP" answer, read exactly like
            // holdEfficiencyPct's own absence above (never a fabricated 0.0).
            s.dayKey = o.optInt("dayKey", 0);      // 0 = derive it, as every reader did
            s.netTupSec = parseNullableDouble(o.optString("netTup", ""));
            s.grossTupSec = parseNullableDouble(o.optString("grossTup", ""));
            return s;
        }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    /** A stored double, or `dflt` for one that is absent, unreadable or not a number. */
    /** The most uncounted climb minutes a routine or a session may carry (Routine
     *  #climbUnderLineMin): far over any Ramped session's climb, and a bound on what a
     *  hand-edited file can credit. */
    public static final double CLIMB_UNDER_MAX_MIN = 60.0;

    public static double clampClimbUnder(double v) {
        return Math.max(0.0, Math.min(CLIMB_UNDER_MAX_MIN, v));
    }

    static double parseDoubleOr(String s, double dflt) {
        if (s == null || s.length() == 0) return dflt;
        try {
            double v = Double.parseDouble(s);
            return Double.isNaN(v) || Double.isInfinite(v) ? dflt : v;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** "" (or unparseable) means ABSENT, which for a peak or a delta means "never
     *  measured" — never 0.0, which would be a claim that something WAS measured and
     *  came out at zero. Same rule as Proto.Sample's 0.0 = no reading. */
    private static Double parseNullableDouble(String s) {
        if (s == null || s.length() == 0) return null;
        try { return Double.valueOf(Double.parseDouble(s)); }
        catch (NumberFormatException e) { return null; }
    }

    /** The filed sessions, NEWEST FIRST — the same ordering convention MeasLog uses,
     *  so nothing downstream has to re-sort. Every derived Today/History number
     *  (streak, best, week, total time) is computed from this list by Summary, rather
     *  than kept as a separate running counter that could drift away from the records
     *  it claims to summarise. */
    public static final class SessLog {
        public final List<Sess> all = new ArrayList<Sess>();

        public void file(Sess s) { if (s != null) all.add(0, s); }

        public JSONArray toJson() throws JSONException {
            JSONArray a = new JSONArray();
            for (int i = 0; i < all.size(); i++) a.put(all.get(i).toJson());
            return a;
        }

        public static SessLog fromJson(JSONArray a) {
            SessLog log = new SessLog();
            if (a != null) for (int i = 0; i < a.length(); i++)
                log.all.add(Sess.fromJson(a.optJSONObject(i)));
            return log;
        }
    }

    /* --------------------------------------------------------------- known pump */

    /**
     * A pump the user has chosen to REMEMBER (T9, named pump memory) — its BLE address,
     * the name they gave it, and when this app last actually connected to it. Addresses
     * are stored UPPERCASE — the case every real BluetoothDevice#getAddress() already
     * returns — so a lookup can never miss a match on case alone; {@link #rememberPump} is
     * the one place an incoming address gets normalised, so every entry already on this
     * list is canonical.
     *
     * `lastConnected` starts at 0 — REMEMBERED is not the same claim as CONNECTED, and a
     * pump just named but not yet linked has nothing to report. It only ever moves
     * forward, from SessionActivity's onState hook, on the RISING edge into "Connected" —
     * the identical evidence-over-claim rule {@link PumpLink}'s own header comment already
     * states for the link itself.
     */
    public static final class KnownPump {
        public String address = "";
        public String name = "";
        public long lastConnected = 0L;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("addr", address);
            o.put("name", name);
            // A long, through a string — the desktop org.json shim test.sh runs against
            // has no optLong, the same reason Reading#ts and Sess#ts go out this way.
            o.put("last", String.valueOf(lastConnected));
            return o;
        }

        public static KnownPump fromJson(JSONObject o) {
            KnownPump p = new KnownPump();
            if (o == null) return p;
            // Normalised on the way IN too, not just by rememberPump() on the way out — a
            // hand-edited file, or a foreign one, is not obliged to have written it
            // upper-case, and every comparison elsewhere (knownPump, the dedup below in
            // Model#fromJson, PumpLink's own scan-time lookup) assumes every entry already
            // is.
            p.address = PumpMatch.norm(o.optString("addr", ""));
            p.name = o.optString("name", "");
            try { p.lastConnected = Long.parseLong(o.optString("last", "0")); }
            catch (NumberFormatException e) { p.lastConnected = 0L; }
            return p;
        }
    }

    /* ----------------------------------------------------------- the whole */

    public final List<Set> sets = new ArrayList<Set>();
    public final List<Routine> routines = new ArrayList<Routine>();
    public int ceilKpa = 40;
    public String selected;

    /** Display unit — "kPa", "inHg" or "cmHg". Storage is always kPa; this only picks how
     *  it is shown (see {@link Fmt}). */
    public String unit = "inHg";

    /**
     * DISPLAY UNIT FOR BODY SIZE — "cm" or "in". Storage is always centimetres; this only
     * picks how a length or a girth is drawn (see {@link Fmt#len}).
     *
     * A SEPARATE AXIS from {@link #unit} on purpose: reading vacuum in inHg and measuring
     * yourself in centimetres is a perfectly ordinary combination, and one setting driving
     * both would make it impossible to ask for.
     *
     * MIGRATION: "cm", which is what every screen printed before this existed — so an
     * upgrading phone's numbers do not move.
     */
    public String sizeUnit = "cm";

    /**
     * DISPLAY UNIT FOR A LOAD - "lb" or "kg" (owner, 2026-09-30). Storage and every rule stay
     * in pounds; this only picks how a load is drawn (see {@link Fmt#load}). A third axis
     * beside {@link #unit} and {@link #sizeUnit}, set in Settings \u203a Units and the first-run
     * setup's units step.
     *
     * DEFAULT FOLLOWS THE SIZE UNIT: inches -> lb, centimetres -> kg. MIGRATION: a file from
     * before it gets that default from its own sizeUnit.
     */
    public String loadUnit = Fmt.L_KG;

    /* ==================================================================================
     * STAGE H TASK 2 — THE TRAINER'S PERSISTED STATE (data plumbing only; the engine is
     * Plan.java from Task 1, the Trainer tab UI is Task 3, suggestion/mint generation is
     * Task 4, the outside-tab hooks are Task 5). Every field below is 2-arg-default-safe
     * on load (Stage G PD-7 lesson) and migration-SelfTested against a key-absent fixture
     * — see Migrate.java's own "STAGE H TASK 2" section.
     * ================================================================================== */

    /** Whether the person has ever completed onboarding. False on a fresh install and on
     *  every save written before this feature existed — neither has enrolled. */
    public boolean trainerEnrolled = false;

    /**
     * F6 - THE LEVEL THE STANDALONE WARM-UP OFFER WAS LAST ANSWERED AT.
     *
     * NO LONGER READ. The offer it governed is gone: it added a ramp to whichever routine
     * happened to be SELECTED, which is not necessarily the one the plan had just written,
     * so a prescription could arrive with no way into its own working pressure while an
     * unrelated routine got a warm-up nobody asked for. A prescribed routine now opens with
     * its own warm-up, built with it.
     *
     * KEPT, STILL WRITTEN AND STILL READ FROM DISK, because deleting it would silently drop
     * the key from every saved model - and if this decision is ever revisited, a person who
     * declined the offer at L3 should not be asked again as though they never had been.
     */
    public int trainerWarmupOfferedLevel = 0;
    /** Epoch ms of FIRST enrolment — 0 = never enrolled. Anchors the plan-wide deload
     *  cadence and the month-index calculation (plan §7 concurrency ruling: "one
     *  plan-wide deload cadence anchored at first enrollment"). Recalibrate does not move
     *  this — round-2 ruling: "leaving the plan... re-enroll = recalibrate" describes a
     *  reposition, not a fresh clock. */
    /**
     * THE LAST CHANGE THE PLAN MADE TO A ROUTINE, WAITING TO BE MENTIONED.
     *
     * The plan used to change its mind and then wait, holding an offer on a tab that might
     * not be opened for a fortnight - so a person could train for three weeks on a routine
     * the plan had already superseded, and nothing anywhere said so.
     *
     * A prescription is now applied to the routine it already wrote, and this is the record
     * of that: what changed, in figures, and the routine as it was BEFORE, so the change can
     * be undone by somebody who disagrees with it.
     *
     * ONE AT A TIME. A newer change replaces an older unmentioned one, because two notices
     * describing the same routine a week apart is a queue nobody asked for - and the newer
     * one is the true state of things either way.
     *
     * MIGRATION: null. Nothing was applied before this existed, so there is nothing to
     * mention.
     */
    public static final class PlanNotice {
        public int track;
        public long ts;
        /** Shown once on Today, then it stops interrupting and lives as a chip. */
        public boolean shown;
        /** Cleared when the new shape has actually been run - after that it is history,
         *  not a pending change, and Undo would be rewriting the past. */
        public boolean ran;
        public String routineId = "";
        /** "6 → 7 sets" - what a person needs to read in one glance. */
        public String headline = "";
        /** The fuller sentence, with what did NOT move. */
        public String detail = "";
        /** The routine exactly as it was, as JSON, so Undo restores rather than re-mints. */
        public String prevRoutine = "";
        /** FIX11 F1: the sets that routine plays, as a JSON array (PlanUndo#setsJson) - kept
         *  because the rewrite deletes them. MIGRATION: absent in an older notice ("" - Undo then
         *  restores only when every set is still there, PlanUndo#restore). */
        public String prevSets = "";
        /** FIX11 F1: the signature the routine was minted under, for the hold lengths it was
         *  built with (PlanUndo#restoreSettings). MIGRATION: absent - "", nothing set back. */
        public String prevSig = "";
        public int weekIndex;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("track", track);
            o.put("ts", String.valueOf(ts));
            o.put("shown", shown);
            o.put("ran", ran);
            o.put("rid", routineId == null ? "" : routineId);
            o.put("head", headline == null ? "" : headline);
            o.put("detail", detail == null ? "" : detail);
            o.put("prev", prevRoutine == null ? "" : prevRoutine);
            if (prevSets != null && prevSets.length() > 0) o.put("prevSets", prevSets);
            if (prevSig != null && prevSig.length() > 0) o.put("prevSig", prevSig);
            o.put("wk", weekIndex);
            return o;
        }

        public static PlanNotice fromJson(JSONObject o) {
            if (o == null) return null;
            PlanNotice n = new PlanNotice();
            n.track = o.optInt("track", 0);
            n.ts = parseLong(o.optString("ts", "0"));
            n.shown = o.optBoolean("shown", false);
            n.ran = o.optBoolean("ran", false);
            n.routineId = o.optString("rid", "");
            n.headline = o.optString("head", "");
            n.detail = o.optString("detail", "");
            n.prevRoutine = o.optString("prev", "");
            n.prevSets = o.optString("prevSets", "");
            n.prevSig = o.optString("prevSig", "");
            n.weekIndex = o.optInt("wk", 0);
            return n;
        }
    }

    /** The one unmentioned (or recently mentioned) plan change. Null when there is none. */
    public PlanNotice planNotice;

    /**
     * A RECALIBRATION ASKED FOR DURING A RUN, WAITING FOR THE RUN TO END.
     *
     * Recalibrating rewrites the whole plan - level, position, working pressure - and the
     * routine playing right now was minted from the OLD one. It never touches the pump, so
     * by the app's own lock rule it has no business being refused; but a session that
     * started under one plan should not be filed against another.
     *
     * So the request is TAKEN and HELD. The app says plainly that it is waiting, the run
     * finishes and files against the plan it began under, and the recalibration is then
     * offered - never launched unasked, because onboarding is several screens and arriving
     * in it uninvited on the far side of a summary would read as the app losing its place.
     *
     * MIGRATION: false. Nobody who has not asked is waiting.
     */
    public boolean pendingRecalibrate = false;

    public long trainerEnrolledAt = 0L;

    /**
     * HOW LONG THE PERSON HAD BEEN PUMPING WHEN THEY LAST ANSWERED — the setup wizard's
     * own first question ("months pumping in total"), kept.
     *
     * It used to be asked, used once to pick a starting level and week, and then thrown
     * away: from the next screen on, the plan's month was months-since-enrolment, which
     * is 0 on the day you enrol however long you have been training. Reported from a
     * device: six months answered, and the app went on saying "first month" and holding
     * the pressure at the beginner's cap.
     *
     * MIGRATION: 0, with {@link #trainerMonthsAt} 0 too, which reads as "never answered"
     * and leaves the old months-since-enrolment behaviour exactly as it was.
     */
    public int trainerMonthsPumping = 0;

    /** WHEN that answer was given, so the months since can be added to it. Separate from
     *  {@link #trainerEnrolledAt} because a recalibration re-answers the question without
     *  re-enrolling. MIGRATION: 0 — see the field above. */
    public long trainerMonthsAt = 0L;

    /**
     * THE GIRTH LEVEL THE PERSON HAS BEEN TOLD ABOUT.
     *
     * Levels change inside {@link Plan#evaluate}, which runs while a session is being filed
     * - the one moment nothing may take the screen. So the graduation is not announced when
     * it happens; it is announced the next time Today is opened, and this is what remembers
     * that it has not been announced yet.
     *
     * MIGRATION: 0, which is not a level. It means UNKNOWN rather than "level zero", and the
     * first render adopts the current level silently - an upgrading user who is already deep
     * into Level 2 must not be congratulated for reaching it months ago.
     */
    /**
     * HOW YOU WOULD LIKE THE PLAN'S ROUTINES SHAPED.
     *
     * The plan owns the NUMBERS - sets, hold, pressure, and the net target they add up to.
     * These own the SHAPE: how long the warm-up is, whether there is a retention hold at the
     * end, how often the rests fall and how long they are. Every one of them is written into
     * the routine the mint produces and none of them changes what the plan asks of you.
     *
     * THAT SEPARATION IS THE SAFETY PROPERTY. The moment a shape setting could lower the
     * pressure, the set count or the hold, it would be a way to quietly under-train while
     * the plan went on scoring you as on target. So the mint reads these for the stages
     * around the work and never for the work itself - with ONE deliberate exception,
     * {@link #rxHoldSec}, which trades hold length against set count to reach the SAME net.
     *
     * MIGRATION: every value is the constant the mint used before it read any of them, so
     * an upgrading user's next prescription is byte-identical to their last.
     */
    public int rxWarmMin = 4;         // Mint's own 240 s
    public int rxWarmSteps = 4;       // Mint's own 4 (ramp mode only)

    /**
     * HOW THE WARM-UP IS SHAPED - a PRIME hold by default, a ramp if you prefer.
     *
     * PRIME is this app's own petechiae-reduction shape: a flat hold at about 4 inHg, held
     * for the warm-up's length, then ONE eased cycle at your working pressure less
     * {@link #rxEaseHg} before the work proper begins - roughly the guidance's warm-up of a
     * few minutes held at about 4 inHg, and its first rep pumped a few hg under the working
     * pressure, in one stage. S20: those two rules used to be presented as citations; they
     * could not be found in the guidance, unlike the constants Plan.java's own header table
     * checks rule by rule. Treat this as this codebase's synthesis (Plan#TAG_INFERRED
     * territory) rather than a verified citation until the guidance turns out to state it.
     *
     * RAMP is what the app did before: a climb from about half your working pressure up to
     * it. Kept because it is what existing routines were built with, but it is the wrong
     * shape for somebody who marks easily: a ramp spends most of its length ABOVE the prime
     * pressure, which is the pressure the advice is trying to keep you at.
     *
     * THE EASED CYCLE LIVES IN THE WARM-UP STAGE, not the work stage, and that placement is
     * the whole reason this is safe to add. Put in the work stage it would either replace a
     * prescribed set - quietly training less than the plan asked - or add one the plan has
     * to score. In the warm-up it is preparation: excluded from net, counted by nothing,
     * changing no target.
     *
     * MIGRATION: prime, 4 inHg, 3 hg of easing. A change of shape for everybody, which is
     * what was asked for - the previous default gave Level 1 no warm-up at all.
     */
    public boolean rxWarmRamp = false;
    public double rxPrimeKpa = 13.55;   // 4.0 inHg
    public double rxEaseHg = 3.0;

    /**
     * THE RAMPS (0.10, the owner's decisions) - how the Ramped work shape ("ramp in each set")
     * climbs, on girth and on length's expansion. One set of settings shared by both tracks,
     * as every other shape setting on this page is (three copies would be three places for
     * them to disagree about one prescription). RxBuild#rampPlan reads them.
     *
     *   START - the first block's climb starts at this share of the day's commanded working
     *       pressure (after the offset, the bias and any lighter-day cut). 60-95 %, 80 %.
     *   SHORT CLIMB - a block after a rest climbs only this many steps, the last of them at
     *       the working pressure: 2 is one step under it, then it. 0 (or 1) starts at the
     *       working pressure. 0-3, 2.
     *   STEP - at most this much higher each climbing hold, 0.3-1.0 inHg (1.0 the most), 1.0.
     *       Held to the whole kPa the wire takes, never over it: 1.0 inHg is 3 kPa a hold.
     *   LIGHTER DAYS - a taper or cylinder day keeps its ramp, climbing to the lighter day's
     *       pressure. On.
     *   COUNT THE CLIMB - the climb's holds are part of the block's holds and count as work,
     *       the make-up rule as before. Off: only holds at the working pressure count - the
     *       climb comes in front of the block's holds, out of net, and nothing is made up for
     *       it. On.
     *
     * MIGRATION: the defaults. A file from before this loads with them, which changes the
     * ramp an existing Ramped routine runs (the owner's decision); the plan rewrites it with a
     * notice that says so, and Undo.
     */
    public int rampStartPct = RAMP_START_PCT_DEFAULT;
    public int rampShortSteps = RAMP_SHORT_STEPS_DEFAULT;
    public double rampStepHg = RAMP_STEP_HG_MAX;
    public boolean rampLighterDays = true;
    public boolean rampCountClimb = true;

    public static final int RAMP_START_PCT_MIN = 60, RAMP_START_PCT_MAX = 95,
                            RAMP_START_PCT_DEFAULT = 80;
    public static final int RAMP_SHORT_STEPS_MAX = 3, RAMP_SHORT_STEPS_DEFAULT = 2;
    public static final double RAMP_STEP_HG_MIN = 0.3, RAMP_STEP_HG_MAX = 1.0;

    /**
     * THE GENTLE WARM-UP (0.10, the owner's own rule for this app - not the guidance's): with
     * "I mark or bruise easily" on, the warm-up starts at `gentleWarmStartKpa` and
     * `gentleWarmSpeedPct` and climbs rep by rep - at most `gentleWarmStepHg` a rep - to the
     * working pressure (the first work hold's), its speed rising evenly to the work's own.
     * RxBuild#gentleWarmStage builds it.
     *
     *   START - 2.0 inHg up to the working pressure (a start at or over it starts at it),
     *       4.0 inHg by default. Stored in kPa like the prime.
     *   SPEED - 40-100 %, 60 %. Never faster than the work it leads into.
     *   STEP - 0.3-1.0 inHg a rep (1.0 the most), 1.0.
     *
     * MIGRATION: the defaults - they reach nobody until "I mark or bruise easily" is on.
     */
    public double gentleWarmStartKpa = GENTLE_START_KPA_DEFAULT;
    public int gentleWarmSpeedPct = GENTLE_SPEED_PCT_DEFAULT;
    public double gentleWarmStepHg = RAMP_STEP_HG_MAX;

    /** 4.0 inHg, as the prime's own default. */
    public static final double GENTLE_START_KPA_DEFAULT = 13.55;
    /** 2.0 inHg - the lowest the gentle warm-up may start. */
    public static final double GENTLE_START_KPA_MIN = 6.77;
    /** The highest a stored start may be: 15 inHg, the absolute limit. The build starts at the
     *  working pressure wherever this is at or over it. */
    public static final double GENTLE_START_KPA_MAX = 50.8;
    public static final int GENTLE_SPEED_PCT_MIN = 40, GENTLE_SPEED_PCT_MAX = 100,
                            GENTLE_SPEED_PCT_DEFAULT = 60;

    /**
     * WHEN GIRTH FOLLOWS LENGTH (t10 R-07 / R-63, the owner's decision, 1 Oct 2026) - what
     * takes the place of girth's fatigue block on a both-tracks day whose length session ran
     * first. The Trainer page's "When girth follows length" row, shown when both tracks are on.
     *
     *   {@link #R4_WARM} - "2-minute ramped warm-up": short holds climbing to the work, not
     *       counted (the default).
     *   {@link #R4_SETS} - "Ramped first sets (3)": the first Plan#R4_RAMP_SETS work holds
     *       climb to the work, and count.
     *   {@link #R4_NONE} - "Nothing": no fatigue block and nothing in its place.
     *
     * MIGRATION: R4_WARM, for an old file as for a new setup - the owner's default either way.
     * A value outside the three is read as the default (clampRxShape).
     */
    public int girthAfterLength = GIRTH_AFTER_LENGTH_DEFAULT;
    public static final int R4_WARM = 0, R4_SETS = 1, R4_NONE = 2;
    public static final int GIRTH_AFTER_LENGTH_DEFAULT = R4_WARM;

    /**
     * LENGTH LOAD AFTER MONTH 3 (t10 R-46 / R-63, the owner's decision, 1 Oct 2026) - how the
     * length load moves while the strain sets are still under 12. The Trainer page's row,
     * shown with a length cylinder only.
     *
     *   {@link #LENGTH_LOAD_SLOW} - "Slow calendar load step": +0.5 lb every 2 length training
     *       weeks (the default).
     *   {@link #LENGTH_LOAD_AFTER12} - "Load only after 12 strain sets": as the plan did before.
     *
     * MIGRATION: LENGTH_LOAD_SLOW for an old file too - the owner's default, said on the
     * upgrade card (R-61). A value outside the two is read as the default (clampRxShape).
     */
    public int lengthLoadMode = LENGTH_LOAD_DEFAULT;
    public static final int LENGTH_LOAD_SLOW = 0, LENGTH_LOAD_AFTER12 = 1;
    public static final int LENGTH_LOAD_DEFAULT = LENGTH_LOAD_SLOW;

    /**
     * NOT SAVED - a scratch model's instruction to build a Ramped routine the way builds before
     * the 0.10 ramp settings did (a climb from the level's floor over every hold of the block).
     * Set only by SavedMint#applyShapeTag, for a signature minted before the ramp settings were
     * part of it, so a routine the plan saved then is recognised as the plan's and rewritten -
     * with a notice - rather than mistaken for the person's edit.
     */
    public transient boolean legacyRamp = false;

    /**
     * NOT SAVED - a scratch model's instruction to build a trainer routine the way builds before
     * t10 did (the trainer's rules of 1 Oct 2026): the old warm-up (a prime and an eased cycle,
     * the climb, or the short prime, RxBuild#legacyWarmupStage), no P2 carry, no two-hour trim,
     * traditional rests from rxRestSecTrad (120 s / 180 s), and the hybrid's holds without the
     * time cap. Set only by SavedMint#applyShapeTag, for a signature with no
     * {@link #T10_BUILD_TOKEN}, so a routine the plan saved before t10 is recognised as the
     * plan's and rewritten - with a notice - rather than mistaken for the person's edit.
     */
    public transient boolean legacyT10 = false;

    /**
     * t10-K (MonthBreak, B3) - THE GENTLE WEEK AFTER THE MONTH-12 BREAK GIVES EVERYBODY THE
     * GENTLE WARM-UP, "I mark or bruise easily" or not (#gentleWarmFor). Never saved: put on
     * the live model by MonthBreak#settle for the day it is, and on a scratch model by the
     * signature it rebuilds (MonthBreak#fromSig - the gentle warm-up's own token says it).
     */
    public transient boolean breakMarks = false;
    /** t10-K (B3) - the gentle week's girth figure a signature records, on a scratch model a
     *  routine is rebuilt in (MonthBreak#fromSig); -1 = not rebuilt from one: the day decides
     *  (MonthBreak#girthKpaAt). Never saved. */
    public transient int breakSigKpa = -1;

    /** The shape token every trainer routine built by the t10 rules carries (#rxShapeTag). */
    public static final String T10_BUILD_TOKEN = "V10";

    /**
     * R5 - WHICH TRACK LEADS on a day that runs both.
     *
     * A real preference, not an implementation detail. Girth first is the harder work while
     * you are fresh; length first is the longer, gentler one while you are still patient
     * enough to sit through it. The app has no basis for OVERRIDING it, so it asks once and
     * then stops asking - {@link UpNext} reads this and nothing re-prompts mid-day.
     *
     * LENGTH FIRST IS THE DEFAULT, and it is a reading of the guidance, not only the user's
     * own preference (S20: this comment used to credit it to the user alone). The guidance
     * states the order for a combined day outright - length first, then girth - and
     * separately puts length in the morning with girth as supplemental
     * evening work. The setting still stays, and the app still asks rather than locking it:
     * the other order is just as defensible on a day that starts late, and a MIGRATION-true
     * install that has never chosen gets the guidance's own order rather than a coin flip.
     */
    public boolean rxLengthFirst = true;

    /**
     * A2 - REMIND ME ABOUT THE TRACK I HAVE NOT RUN YET.
     *
     * A both-tracks day is two sessions, and the second is easy to lose to an evening. The
     * screen keeps offering it, but a screen you do not open says nothing.
     *
     * IT IS GATED BY THE MASTER REMINDER SWITCH ({@link Schedule#remind}), which is itself
     * opt-in - so on a fresh install nothing fires, and this is a question about WHICH
     * reminders you get rather than whether you get any. That is why it defaults on: someone
     * who has already said yes to reminders has said yes to being reminded.
     *
     * IT FIRES ONCE, three hours after the first track is filed, and only if the other is
     * still outstanding at that moment - re-asked at fire time from the session log, never
     * trusted from when the alarm was set. Nothing fires late in the evening: a reminder to
     * train that arrives at eleven is not a reminder, it is a reproach.
     */
    public boolean remindOtherTrack = true;

    /**
     * 0.10 (S07) - SAY SO WHEN A DAY GOES PAST NINETY MINUTES.
     *
     * The guidance asks for under ninety minutes of session time when length and girth are
     * combined, and every limit in the app was per session. The day's total
     * is always shown on a both-tracks day; this is only whether the one-line advisory is -
     * the owner's decision of 2026-09-26 is that the person can turn it off. It never stops
     * anything.
     *
     * MIGRATION true: an upgrade shows the advisory, which is a line of text and the
     * source's own figure; somebody who does not want it turns it off where it appears.
     */
    public boolean dayBudgetAdvisory = true;

    /**
     * 0.10 (S09) - THE PULL WHOSE NUMBNESS WAS SAID TO HAVE CLEARED: the id of the session
     * the "After the pull" card was answered "It cleared" for, or "".
     *
     * The card used to record nothing for that answer, and nothing needed it. The soft gap
     * after a pull does: forty minutes, or until the person says it cleared - whichever is
     * first (the owner's decision of 2026-09-26). Keyed by the session, so an answer about
     * this morning's pull says nothing about this afternoon's.
     *
     * MIGRATION "": no answer has been recorded, so the forty minutes are what count.
     */
    public String pullClearedSessId = "";

    /**
     * THE SIMULATED PUMP - a developer's switch, off on every install and every upgrade.
     *
     * Five things could only ever be checked with a cuff attached: the warm-up's shape, a
     * both-tracks day flipping over, the resume card's two answers, a reduced session's
     * actual commanded pressure, and the rests - the one that bit before. None of them is
     * about Bluetooth and all of them were gated behind it.
     *
     * ON, {@link PumpLink} answers its own writes instead of a radio. Everything above the
     * transport runs unmodified, which is the point: the write queue, the ack pairing, the
     * vent watch and the stop retry are the parts worth exercising, and a simulator that
     * bypassed them would only prove itself.
     *
     * A SIMULATED SESSION IS FILED LIKE A REAL ONE. It has to be, or the plan behaviour
     * these tests exist to check would not happen - but it carries {@link Sess#sim}, so a
     * history can never quietly contain runs that never happened.
     *
     * IT PERSISTS, because a test that ends when the app is backgrounded is not a test of
     * anything a session does. The banner is what stops it being forgotten.
     */
    public boolean simPump = false;

    /**
     * Q1 - "I MARK EASILY". Off by default, and the default is the point: the petechiae
     * guidance is addressed to people who bruise, and enforcing it on everybody would slow
     * down people it was never written for. The app cannot know which you are, so it asks.
     *
     * ON, two things become part of the prescription rather than advice printed beside it: a
     * week or more away ARMS THE RETURN TAPER by itself ({@link Deload#arm}), and a larger
     * cylinder runs {@link Plan#BIG_CYLINDER_HG} under for as long as it is the one you are
     * in. The taper itself is not gated on this — reporting a deload arms it for anybody;
     * only the automatic, detected-gap arming still waits for this statement about your body.
     *
     * THE WARM-UP AND THE EASED FIRST CYCLE ARE NOT GATED ON THIS. They are already in every
     * routine for everybody, because neither reduces the work the plan asked for - they are
     * preparation, excluded from net, changing no target.
     */
    public boolean marksEasily = false;

    /** Q1 - the cuff currently in use is the larger of the two. A per-session equipment fact,
     *  not a preference: it changes the prescription AND the pressure net counts from, since
     *  the floor is a proxy for tissue stress and a wider cylinder moves the proxy. */
    public boolean bigCylinder = false;

    /**
     * THE OFFER, ACCEPTED. The rack can now DERIVE that the cylinder in use is oversize for
     * the measured girth ({@link #activeCylinderOversize}), which is the petechiae rule's own
     * condition. The app offers the {@link Plan#BIG_CYLINDER_HG} reduction when it sees that;
     * this is the user having said yes.
     *
     * WHY A SECOND FIELD AND NOT A REUSE OF {@link #bigCylinder}. bigCylinder is a self-report
     * that only bites when {@link #marksEasily} is also on. Deriving the same reduction and
     * hanging it off that gate would mean an accepted offer silently did nothing for anyone
     * who never called themselves a marker; folding the derivation INTO bigCylinder instead
     * would hand a 2 hg reduction to every existing user with a wide tube who had never asked
     * for one. A separate, explicitly-accepted flag changes nothing for anybody until they
     * answer — which is why it defaults false, the truth about every file written before it.
     *
     * DERIVED CONDITION, STORED ANSWER: the reduction applies only while the rack still
     * derives oversize, so growing out of the tube retires it without anyone toggling
     * anything back.
     */
    public boolean oversizeAccepted = false;

    /**
     * THE OFFER, DECLINED - and declined DURABLY.
     *
     * The app's standing rule is that "not now" never means "ask again tomorrow", so a refused
     * reduction has to be remembered or the card becomes a nag. Cleared by
     * {@link #syncOversizeAcceptance} the moment the rack stops deriving oversize, so a
     * genuinely different situation - a new tube, a re-measure - is allowed to ask once more.
     * It is the SAME question being re-asked that the rule forbids, not a new one.
     */
    public boolean oversizeDeclined = false;

    /**
     * THE GENTLE RETURN, one step per TRAINING DAY.
     *
     * `returnStep` is the step the next training day runs: -1 is off, 0 and 1 are the taper's
     * cuts, and {@link Plan#RETURN_TAPER_STEPS} means "finished — the next session is back at
     * full pressure" (the card says so, then the session itself closes it).
     *
     * `returnDayRun` is the day number a step has already run on, so every other session that
     * day shares its pressure; `returnHeld` is a "stay one more day" tapped after running, so
     * tomorrow repeats instead of advancing; `returnLastRun` is the step that most recently
     * ran, which is the one the stay button names and repeats. `returnFromMs` is when steps
     * start being used up — the end of a deload week, so a light session inside the week
     * still runs gently without spending a step.
     *
     * ASK {@link Deload}, never these fields: the rules are its, and reading them raw is how
     * two screens come to disagree about what today runs at.
     */
    public int returnStep = -1;
    public long returnDayRun = 0L;
    public boolean returnHeld = false;
    public int returnLastRun = -1;
    public long returnFromMs = 0L;

    /** The gap the taper was armed for — the last session before it. Kept so the automatic
     *  arming does not re-arm the same gap after it has been ended or run out. */
    public long returnAnchorMs = 0L;

    /** A manual run can have the same prime hold in front of it. OFF by default: a manual
     *  cycle is a deliberate one-off and putting four minutes in front of it uninvited
     *  would be the app deciding what the run is for. */
    public boolean rxManualWarm = false;

    /**
     * R7 - SESSION SHAPES YOU KEEP.
     *
     * The "How you run it" controls are a dozen numbers that took real thought to settle
     * on, and the app remembered exactly one arrangement of them. Trying a longer retention
     * hold for a fortnight meant writing the old numbers down on paper first, and coming
     * back meant typing them all in again from that paper. People stop experimenting, which
     * is the opposite of what a screen full of dials is for.
     *
     * A SHAPE IS A SNAPSHOT, NOT A LINK. Applying one copies its numbers into the live
     * settings and then has nothing further to do with them: editing a dial afterwards does
     * not silently rewrite the saved shape, and deleting a shape does not disturb the
     * session you are running. A live binding would mean a routine changing shape underneath
     * somebody because they moved a slider on another screen.
     *
     * IT DOES NOT CAPTURE THE PRESCRIPTION. Pressure, sets and level belong to the plan and
     * are not the user's to pin; a shape is how you run what the plan prescribed, which is
     * exactly the set of things the plan does not decide.
     */
    public static final class Shape {
        public String id = "", name = "";
        public int warmMin, warmSteps, retentionMin, restSec, setsPerBlock, holdSec;
        public boolean warmRamp, manualWarm, retention, split, splitWarmBoth, resumeWarm,
                       lengthFirst;
        public double primeKpa, easeHg, retentionKpa;

        public static Shape capture(Model m, String id, String name) {
            Shape s = new Shape();
            s.id = id; s.name = name;
            s.warmMin = m.rxWarmMin;           s.warmSteps = m.rxWarmSteps;
            s.warmRamp = m.rxWarmRamp;         s.primeKpa = m.rxPrimeKpa;
            s.easeHg = m.rxEaseHg;             s.manualWarm = m.rxManualWarm;
            s.retention = m.rxRetention;       s.retentionMin = m.rxRetentionMin;
            s.retentionKpa = m.rxRetentionKpa; s.restSec = m.rxRestSec;
            s.setsPerBlock = m.rxSetsPerBlock; s.holdSec = m.rxHoldSec;
            s.split = m.rxSplit;               s.splitWarmBoth = m.rxSplitWarmBoth;
            s.resumeWarm = m.rxResumeWarm;     s.lengthFirst = m.rxLengthFirst;
            return s;
        }

        public void applyTo(Model m) {
            m.rxWarmMin = warmMin;             m.rxWarmSteps = warmSteps;
            m.rxWarmRamp = warmRamp;           m.rxPrimeKpa = primeKpa;
            m.rxEaseHg = easeHg;               m.rxManualWarm = manualWarm;
            m.rxRetention = retention;         m.rxRetentionMin = retentionMin;
            m.rxRetentionKpa = retentionKpa;   m.rxRestSec = restSec;
            m.rxSetsPerBlock = setsPerBlock;   m.rxHoldSec = holdSec;
            m.rxSplit = split;                 m.rxSplitWarmBoth = splitWarmBoth;
            boolean leadChanged = m.rxLengthFirst != lengthFirst;
            m.rxResumeWarm = resumeWarm;       m.rxLengthFirst = lengthFirst;
            // A shape written by an older or hand-edited file is still bounded by the same
            // rules the dials are: applying one can never put the app somewhere its own
            // controls could not reach.
            m.clampRxShape();
            /* t10 review D, F2 - "LENGTH FIRST" LEADS AN "ALTERNATE ON MY DAYS" WEEK, so the
             * week is told at once (LongDays#follow), not at the next start. A shape that
             * changes it is the person choosing the lead (Schedule#leadWith); one that leaves
             * it as it was leaves an upgrader's own stored lead alone (O2). The caller saves
             * as a schedule edit (the reminders). */
            LongDays.follow(m);
            if (leadChanged && m.sched != null) m.sched.leadWith(m.rxLengthFirst);
        }

        /**
         * WHAT THIS SHAPE DOES, AS SEPARATE FACTS - the list {@link #summary} joins.
         *
         * A list because two things print it now and they need it in different forms: the
         * saved-shape rows print one sentence, and the door into the shape room (Ui#doorRow)
         * prints it on a line that may have to WRAP, and must wrap between facts rather than
         * inside one. Keeping the facts separate is what lets the door do that without a second
         * wording that could drift from this one.
         */
        public String[] summaryParts() { return summaryParts(null); }

        /**
         * t10 device walk M2 - THE WARM-UP FACT IS THE ONE THE ROUTINE GETS. It used to print
         * this shape's own "prime 4 min" / "climb 4 min" (#warmMin, #warmRamp), which R-01 left
         * saved but unread: every trainer routine now warms up with P2's five-minute climb, or
         * the gentle warm-up for somebody who marks easily, or none (RxBuild#warmupStage). So
         * the fact is asked of the builder for `m`, the model the shape is in force in
         * (RxBuild#warmUpFact); with no model there is no warm-up fact, rather than a stale one.
         */
        public String[] summaryParts(Model m) {
            List<String> p = new ArrayList<String>();
            if (m != null) p.add(RxBuild.warmUpFact(m));
            if (holdSec > 0) p.add(holdSec + "s holds");
            p.add(setsPerBlock + " per block");
            p.add("rest " + Fmt.t(restSec));
            if (retention) p.add("retention " + retentionMin + " min");
            if (split) p.add("split");
            return p.toArray(new String[0]);
        }

        /** A one-line description of what this shape does, for the row that offers it. The
         *  parts above, joined - the wording is unchanged, only its source moved. */
        public String summary() { return summary(null); }

        /** #summary with the warm-up the routine gets in `m` (#summaryParts(Model)). */
        public String summary(Model m) {
            String[] p = summaryParts(m);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) b.append("  ·  ");
                b.append(p[i]);
            }
            return b.toString();
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name);
            o.put("warmMin", warmMin);   o.put("warmSteps", warmSteps);
            o.put("warmRamp", warmRamp); o.put("manualWarm", manualWarm);
            o.put("retention", retention); o.put("retMin", retentionMin);
            o.put("restSec", restSec);   o.put("perBlock", setsPerBlock);
            o.put("holdSec", holdSec);   o.put("split", split);
            o.put("splitWarmBoth", splitWarmBoth); o.put("resumeWarm", resumeWarm);
            o.put("lengthFirst", lengthFirst);
            // Doubles through strings, like every other double in this file - the desktop
            // org.json shim has no optDouble.
            o.put("primeKpa", String.valueOf(primeKpa));
            o.put("easeHg", String.valueOf(easeHg));
            o.put("retKpa", String.valueOf(retentionKpa));
            return o;
        }

        public static Shape fromJson(JSONObject o) {
            Shape s = new Shape();
            if (o == null) return s;
            s.id = o.optString("id", ""); s.name = o.optString("name", "");
            s.warmMin = o.optInt("warmMin", 4);   s.warmSteps = o.optInt("warmSteps", 4);
            s.warmRamp = o.optBoolean("warmRamp"); s.manualWarm = o.optBoolean("manualWarm");
            s.retention = o.optBoolean("retention"); s.retentionMin = o.optInt("retMin", 5);
            s.restSec = o.optInt("restSec", Mint.REST_SEC);
            s.setsPerBlock = o.optInt("perBlock", Mint.SETS_PER_BLOCK);
            s.holdSec = o.optInt("holdSec", 0);   s.split = o.optBoolean("split");
            s.splitWarmBoth = o.optBoolean("splitWarmBoth");
            s.resumeWarm = o.optBoolean("resumeWarm");
            s.lengthFirst = o.optBoolean("lengthFirst");
            s.primeKpa = dbl(o, "primeKpa", 13.55);
            s.easeHg = dbl(o, "easeHg", 3.0);
            s.retentionKpa = dbl(o, "retKpa", 13.5);
            return s;
        }

        private static double dbl(JSONObject o, String k, double dflt) {
            try { return Double.parseDouble(o.optString(k, "")); }
            catch (NumberFormatException e) { return dflt; }
        }
    }

    /** The saved shapes, newest last. Empty on every file written before R7, which is the
     *  truth: nobody has saved one. */
    public final List<Shape> shapes = new ArrayList<Shape>();

    /** How many shapes may be kept. A cap, so a list meant to hold three or four favourites
     *  cannot become an unscrollable history of every fiddle. */
    public static final int SHAPES_MAX = 12;

    public Shape shape(String id) {
        if (id == null) return null;
        for (int i = 0; i < shapes.size(); i++)
            if (id.equals(shapes.get(i).id)) return shapes.get(i);
        return null;
    }
    public boolean rxRetention = false;
    public int rxRetentionMin = 5;
    public double rxRetentionKpa = 13.5;   // ~4 inHg, the middle of the 3-5 band
    public int rxRestSec = 180;       // Mint.REST_SEC

    /**
     * Q4 - TRADITIONAL GIRTH'S OWN REST.
     *
     * The guide gives traditional girth 2-5 minutes between sets where interval work gets 3,
     * and one setting covered both - so somebody running both styles had to remember to move
     * the number when they switched, which is exactly the kind of thing nobody remembers.
     *
     * Its own number, read only on the traditional track. Changing one does not move the
     * other. MIGRATION is the same 3 minutes the single setting defaulted to, so nobody's
     * traditional routine changes shape on the upgrade.
     */
    /** The value nobody chose - what seedTraditionalRest is allowed to replace, and the one
     *  it must never replace anything else with. */
    public static final int REST_SEC_TRAD_DEFAULT = Mint.REST_SEC;
    public int rxRestSecTrad = REST_SEC_TRAD_DEFAULT;

    /** The rest a given track runs. One question, asked in the two places that build a
     *  routine, so the mint and the settings row cannot disagree about it. */
    /**
     * THE REST BETWEEN BLOCKS, in seconds - the user's own setting, seeded from the level.
     *
     * The spec fixes traditional rest at L1 = 120 s and L2+ = 180 s, and Mint#restSecFor
     * states exactly that. This method is what the routine builder actually reads, and the
     * SETTING is what it returns: the plan OFFERS a figure, it does not switch one somebody
     * chose. The seeding happens once, at enrolment, and only onto an untouched default -
     * see seedTraditionalRest.
     */
    public int restSecFor(int track) {
        /* t10 R-08 (the owner's R3, 1 Oct 2026): TRADITIONAL RESTS ARE 30 s at every level, the
         * Rests picker still scaling them (short max(30, 20) = 30 s, long 45 s). The saved
         * rxRestSecTrad is kept as it is and no longer read here. */
        int base = track == Plan.TRACK_GIRTH_TRADITIONAL
            ? (legacyT10 ? rxRestSecTrad : Plan.TRAD_REST_SEC) : rxRestSec;
        // Wave 3b: the Program's rest picker scales the track's own figure — short is
        // two-thirds, long half again, floored and capped where the steppers are.
        Program p = programFor(track);
        if (p == null || p.rest == Program.REST_STANDARD) return base;
        return p.rest == Program.REST_SHORT ? Math.max(30, base * 2 / 3)
                                            : Math.min(600, base * 3 / 2);
    }

    /**
     * Puts the LEVEL's traditional rest into the setting, but only while the setting is still
     * at the value nobody chose.
     *
     * An untouched install then runs the spec's own 120 s at L1 and 180 s above it, and
     * somebody who has moved that stepper keeps what they moved it to - which is the whole
     * difference between seeding a default and overriding a decision. Returns true when it
     * changed something, so the caller knows whether to save.
     */
    public boolean seedTraditionalRest(int level) {
        int want = Mint.restSecFor(Plan.TRACK_GIRTH_TRADITIONAL, level);
        if (rxRestSecTrad == want) return false;
        if (rxRestSecTrad != REST_SEC_TRAD_DEFAULT) return false;   // theirs, not ours
        rxRestSecTrad = want;
        return true;
    }
    public int rxSetsPerBlock = 5;    // Mint.SETS_PER_BLOCK
    public int rxHoldSec = 0;         // 0 = the level's own default

    /* R11-5 - HOLD LENGTHS (the owner's pick, option A): Trainer › What it writes › Hold
     * lengths offers each stage the guidance gives a range for, inside that range, and the plan
     * keeps its minutes at pressure (a shorter hold runs more of them). The fatigue block's
     * holds are 30 to 60 s, and 30 s for everyone by default - the owner asked; the block was
     * 10 x 45 s, and keeps its 7.5 minutes in whole holds (Mint#fatigueHolds). The interval
     * work hold is rxHoldSec (1 to 3 min from Level 3, Mint#holdSecWanted) and the rest between
     * blocks rxRestSec (3 to 5 min). The length strain holds and traditional's 5-minute holds
     * have no range in the guidance and are not offered. */
    public static final int FATIGUE_HOLD_DEFAULT_SEC = 30;
    /** The fatigue hold's choices, seconds: the guidance's 30-60 s. */
    public static final int[] FATIGUE_HOLD_CHOICES = { 30, 45, 60 };
    /** The interval work hold's choices, seconds: the guidance's 1-3 min (from Level 3). */
    public static final int[] WORK_HOLD_CHOICES = { 60, 90, 120, 150, 180 };
    /** The rest between blocks' choices, seconds: the guidance's 3-5 min. */
    public static final int[] BLOCK_REST_CHOICES = { 180, 210, 240, 270, 300 };
    /** The fatigue block's hold, seconds (30-60). MIGRATION: absent -> 30 s, for everyone. */
    public int rxFatigueHoldSec = FATIGUE_HOLD_DEFAULT_SEC;

    /** R11-3 - the setup's answer, per track: how long the person's current session of it is,
     *  in minutes (Placement). Placement#NOT_ANSWERED (-1) when it was not answered - placed by
     *  the months, as before; 0 "I don't do this yet". Kept so a setup run again opens on it
     *  and an upgrader's position is kept unless they answer it (TrainerOnboard#positionKept).
     *  MIGRATION: absent -> not answered. */
    public int trainerGirthSessMin = Placement.NOT_ANSWERED;
    public int trainerLengthSessMin = Placement.NOT_ANSWERED;

    /**
     * R1 - PRESCRIBE THE SESSION IN TWO PARTS, to be run morning and evening.
     *
     * Ten sets at two minutes is forty minutes with the warm-up, which is why people skip
     * days rather than shorten them. Split, the plan writes TWO routines - part 1 and part
     * 2 - each carrying half the work and half the net target.
     *
     * WHAT THE DAY COUNTING DOES, AND WHY NOTHING HAD TO CHANGE FOR IT. A week counts in
     * training DAYS, and the app has always derived those from the day each session
     * stamped. So two parts run on one day are one day, automatically; two parts run on
     * different days are two, automatically. Both are the honest reading and neither needed
     * a special case - which is the strongest argument that this is the right shape for the
     * feature.
     *
     * THE SECOND HALF IS THE ONE THAT MOVES. Part 1 always carries the warm-up. Part 2
     * carries one too unless you say otherwise, because a second session hours later is
     * starting from cold again; and only the LAST part carries the retention hold, since
     * retention belongs at the end of the work rather than in the middle of it.
     *
     * MIGRATION: false / true - unsplit, and warming up in both halves if it is ever
     * turned on. Nobody's existing prescription changes.
     */
    public boolean rxSplit = false;
    public boolean rxSplitWarmBoth = true;

    /**
     * R8 - WHAT IS LEFT OF A SESSION THAT ENDED EARLY, and the day it was left on.
     *
     * Not a split decided in advance: a session that stopped, and the cycles it did not
     * deliver. The app already records exactly what ran (deliveredCycles, the as-run rows),
     * so what remains is known rather than estimated.
     *
     * IT EXPIRES THE SAME DAY, and that bound is the feature. A remainder offered three
     * days later is a different session wearing yesterday's name - the tissue has recovered,
     * the plan has moved on, and finishing it would be starting something new while calling
     * it continuing. {@link #resumeDayKey} is compared against today and nothing else.
     *
     * MIGRATION: empty / 0 - no session filed before this left a remainder, and inventing
     * one would offer somebody a routine for work they may well have chosen to stop.
     */
    public String resumeRoutineId = "";
    public int resumeDayKey = 0;
    public int resumeCycles = 0;

    /* THIS MODEL IN MEMORY, AND HOW MANY TIMES IT HAS CHANGED - BuildCache's key. Neither is
     * saved: a model read back from the file is a new model with its own id, so a copy can never
     * be answered from the original's cache. */
    private static final java.util.concurrent.atomic.AtomicLong INSTANCES =
        new java.util.concurrent.atomic.AtomicLong();
    /** Which model this is, for as long as it is in memory. */
    public final long instanceId = INSTANCES.incrementAndGet();
    /** Moved by {@link #changed}: every save calls it, so no answer remembered before a change
     *  that was saved is served after it. */
    public int changeGen = 0;

    /** Something about this model changed (Store#save calls it on every save). */
    public void changed() { changeGen++; }

    /** R8 (your note) - a resumed session warms up again before picking up, unless you say
     *  otherwise. Hours have passed and the tissue is cold; the warm-up is a stage like any
     *  other, so it can still be skipped mid-run by the Skip control. */
    public boolean rxResumeWarm = true;

    /** Every shape value bounded at the one place they are read back, so a hand-edited file
     *  cannot prescribe a forty-minute warm-up or a rest of two seconds. */
    public void clampRxShape() {
        rxWarmMin      = rxWarmMin < 0 ? 0 : (rxWarmMin > 10 ? 10 : rxWarmMin);
        rxWarmSteps    = rxWarmSteps < 2 ? 2 : (rxWarmSteps > 8 ? 8 : rxWarmSteps);
        // 2 to 6 inHg: the band the prime hold is described in, either side of 4.
        rxPrimeKpa     = rxPrimeKpa < 6.8 ? 6.8 : (rxPrimeKpa > 20.4 ? 20.4 : rxPrimeKpa);
        // Easing by more than 5 hg would be a different session, not a way into this one.
        rxEaseHg       = rxEaseHg < 0 ? 0 : (rxEaseHg > 5.0 ? 5.0 : rxEaseHg);
        rxRetentionMin = rxRetentionMin < 1 ? 1 : (rxRetentionMin > 20 ? 20 : rxRetentionMin);
        // 3-5 inHg is the band the retention hold is described in: 10.16 to 16.93 kPa.
        rxRetentionKpa = rxRetentionKpa < 10.0 ? 10.0
                       : (rxRetentionKpa > 17.0 ? 17.0 : rxRetentionKpa);
        // R11-5: 3 to 5 minutes, the guidance's range (it was 2 to 5: a 2-minute rest
        // chosen before is read as 3).
        rxRestSec      = rxRestSec < 180 ? 180 : (rxRestSec > 300 ? 300 : rxRestSec);
        rxFatigueHoldSec = rxFatigueHoldSec < 30 ? 30
                         : (rxFatigueHoldSec > 60 ? 60 : rxFatigueHoldSec);
        // t10 R-08: traditional's own figure may be 30 s (Plan#TRAD_REST_SEC), the R3 rest.
        rxRestSecTrad  = rxRestSecTrad < Plan.TRAD_REST_SEC ? Plan.TRAD_REST_SEC
                       : (rxRestSecTrad > 300 ? 300 : rxRestSecTrad);
        rxSetsPerBlock = rxSetsPerBlock < 2 ? 2 : (rxSetsPerBlock > 10 ? 10 : rxSetsPerBlock);
        if (rxHoldSec != 0)
            rxHoldSec  = rxHoldSec < 60 ? 60 : (rxHoldSec > 180 ? 180 : rxHoldSec);
        // The ramps and the gentle warm-up (0.10): their own ranges. A step is a tenth of an
        // inHg, so a value between tenths - hand-edited, or a float's last bit - is put on one.
        rampStartPct   = rampStartPct < RAMP_START_PCT_MIN ? RAMP_START_PCT_MIN
                       : (rampStartPct > RAMP_START_PCT_MAX ? RAMP_START_PCT_MAX : rampStartPct);
        rampShortSteps = rampShortSteps < 0 ? 0
                       : (rampShortSteps > RAMP_SHORT_STEPS_MAX ? RAMP_SHORT_STEPS_MAX
                                                                : rampShortSteps);
        rampStepHg     = tenthHg(rampStepHg);
        gentleWarmStepHg = tenthHg(gentleWarmStepHg);
        if (Double.isNaN(gentleWarmStartKpa)) gentleWarmStartKpa = GENTLE_START_KPA_DEFAULT;
        gentleWarmStartKpa = gentleWarmStartKpa < GENTLE_START_KPA_MIN ? GENTLE_START_KPA_MIN
            : (gentleWarmStartKpa > GENTLE_START_KPA_MAX ? GENTLE_START_KPA_MAX
                                                         : gentleWarmStartKpa);
        gentleWarmSpeedPct = gentleWarmSpeedPct < GENTLE_SPEED_PCT_MIN ? GENTLE_SPEED_PCT_MIN
            : (gentleWarmSpeedPct > GENTLE_SPEED_PCT_MAX ? GENTLE_SPEED_PCT_MAX
                                                         : gentleWarmSpeedPct);
        // The two t10 choices are names, not quantities: a value that is none of them is
        // read as the default rather than as the nearest end.
        if (girthAfterLength < R4_WARM || girthAfterLength > R4_NONE)
            girthAfterLength = GIRTH_AFTER_LENGTH_DEFAULT;
        if (lengthLoadMode < LENGTH_LOAD_SLOW || lengthLoadMode > LENGTH_LOAD_AFTER12)
            lengthLoadMode = LENGTH_LOAD_DEFAULT;
    }

    /** A ramp or warm-up step in inHg, on a tenth and inside 0.3-1.0 (NaN is the most). */
    static double tenthHg(double hg) {
        if (Double.isNaN(hg)) return RAMP_STEP_HG_MAX;
        double t = Math.round(hg * 10.0) / 10.0;
        return t < RAMP_STEP_HG_MIN ? RAMP_STEP_HG_MIN : (t > RAMP_STEP_HG_MAX ? RAMP_STEP_HG_MAX : t);
    }

    public int lastSeenGirthLevel = 0;

    /**
     * WHEN THE GRADUATION WAS ANNOUNCED - the chip's whole lifetime.
     *
     * The sheet is shown once and cannot be shown again, so dismissing it used to discard the
     * news permanently: the pressure, the sets and the target had all moved and Today never
     * mentioned it again. This is what lets a chip carry it afterwards, exactly as the
     * plan-change notice already does for a routine edit.
     *
     * It ends by being OUTLIVED rather than by being cleared: the chip is drawn while no
     * session has been filed since this instant, so running the new level once retires it
     * with nothing to write. At that point it is history rather than news.
     *
     * MIGRATION: 0 - nothing has been announced, so no chip.
     */
    public long levelUpAnnouncedMs = 0L;

    /**
     * A LEVEL THAT CLOSED WITHOUT A CLOSING MEASUREMENT, and when it closed.
     *
     * The graduation sheet offers a measurement and "Later" is a real answer - but a sheet
     * cannot be shown twice, so without this "Later" would quietly mean "never". The row on
     * Progress is the trail that makes the button honest.
     *
     * It is deliberately NARROW: it says one thing, that THIS level ended with no reading
     * taken during it. It is not a measurement-cadence reminder and never speaks about any
     * other gap.
     *
     * IT LEAVES ON ITS OWN, two ways: a girth reading filed after {@link #unmeasuredLevelMs}
     * answers it, and the next graduation overwrites it whether or not one ever was. A
     * request that outlives its window stops being a request and becomes a grievance.
     *
     * MIGRATION: 0 / 0. Zero is not a level, so an old file has no unanswered request - which
     * is the truth about it: nothing was ever asked.
     */
    public int unmeasuredLevel = 0;
    public long unmeasuredLevelMs = 0L;

    /** Girth style — {@link Plan#TRACK_GIRTH_INTERVAL} or
     *  {@link Plan#TRACK_GIRTH_TRADITIONAL}, MUTUALLY EXCLUSIVE (plan §7 revised-tracks
     *  ruling: "girth style is interval XOR traditional"). Defaults to interval, the
     *  guide's primary track and the one the week tables in {@link Plan} are keyed to. */
    public int trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
    /** Wave 3b (Q8): the girth track may be OFF entirely — a length-only plan. The
     *  style above keeps its value so re-enabling restores the choice. MIGRATION:
     *  true, which is every existing install. */
    public boolean trainerGirthOn = true;
    /** Wave 3b (Q7a): the HYBRID girth style — the guidance's traditional-plus-interval
     *  mix, offered from L3. Rides on the interval track's engine; the mint opens
     *  with one traditional long hold before the interval blocks. Signature-folded. */
    public boolean trainerGirthHybrid = false;
    /** Wave 3b (Q9): feeder sessions are OPT-IN, asked at setup, default yes. */
    public boolean trainerFeederOptIn = true;
    /**
     * S16 (the owner's decision, 2026-09-26): WHETHER THE FEEDER IS OFFERED ON REST DAYS.
     *
     * The guidance differs with itself: its girth routines place feeder sets on training
     * days, while elsewhere it allows light pumping at reduced pressure on rest days. FALSE
     * - training days only - is the default, and
     * that includes every file saved before this existed: the owner chose the default, and
     * the app had been offering the feeder on rest days without saying which reading it
     * followed. The card names both ({@link TrainerTab#feederDaysNote}).
     */
    public boolean feederRestDays = false;

    /**
     * S16 FOLLOW-UP (the owner's decision, 2026-09-26): WHETHER THE ONE QUESTION ABOUT
     * {@link #feederRestDays} HAS BEEN SETTLED - true means nothing is owed.
     *
     * 0.10 made training days only the default for every file, so a trainer who had been
     * offered the feeder on rest days stopped being offered it unannounced. Such a person is
     * asked once, on the first launch after the update (TrainerTab#FEEDER_DAYS_ASK_TEXT), and
     * the answer sets the switch ({@link #answerFeederDays}).
     *
     * OWED ONLY BY A FILE WHOSE DATA PREDATES THE SWITCH - no feederRestDays key, and no
     * marker - AND WHOSE PLAN HAS THE FEEDER IN IT (enrolled, feeders not turned down at
     * setup), decided once, on the load that first reads it ({@link #fromJson}). Every other
     * file, and every new install, starts settled: they get the default without a question,
     * and somebody who enrols later is starting a plan under the new default. Every save
     * writes the marker, so the question outlives a save made before it is answered and
     * never comes back after.
     */
    public boolean feederDaysAsked = true;
    /** The JSON key {@link #feederDaysAsked} is saved under. */
    public static final String FEEDER_DAYS_ASKED_KEY = "feederDaysAsked";

    /** Whether the one question is owed now - the launch asks this, and nothing else. */
    public boolean feederDaysAskDue() {
        return !feederDaysAsked && trainerEnrolled && trainerFeederOptIn;
    }

    /** The answer: sets the switch, and settles the question for good. */
    public void answerFeederDays(boolean restDays) {
        feederRestDays = restDays;
        feederDaysAsked = true;
    }

    /**
     * t10 R-61 (A7): WHETHER THE ONE "THE PLAN CHANGED - RUN THE TRAINER SETUP AGAIN" CARD HAS
     * BEEN SETTLED - true means nothing is owed. The template is {@link #feederDaysAsked}.
     *
     * The trainer's rules changed in t10, and the setup now asks three new things (Long
     * training days, the length load after month 3, what follows a length session). Somebody
     * whose trainer was set up before is shown one card asking them to run the setup again.
     *
     * OWED ONLY BY A FILE WITH NO MARKER WHOSE TRAINER IS SET UP, decided on the load that
     * first reads it ({@link #fromJson}). Every other file, and every new install, starts
     * settled; a new setup is under the new rules. Every save writes the marker, so the card
     * outlives a save made before it is answered and never comes back after.
     */
    public boolean planT10Seen = true;
    /** The JSON key {@link #planT10Seen} is saved under. */
    public static final String PLAN_CHANGED_T10_KEY = "planT10Seen";

    /**
     * HOW MANY TIMES THE CARD WAS PUT OFF WITH "Later" (R-61): each one hides it until the
     * next app start, at most {@link #PLAN_T10_LATER_MAX} times; after that it stays a row in
     * "Where I am" rather than a card. MIGRATION: 0. Held to 0..PLAN_T10_LATER_MAX (clampAll).
     */
    public int planT10Laters = 0;
    public static final int PLAN_T10_LATER_MAX = 3;

    /** Whether the upgrade card (or, after three "Later"s, its row) is owed now. */
    public boolean planChangedT10Due() {
        return !planT10Seen && trainerEnrolled;
    }

    /** Whether it is still owed as a CARD: owed, and put off fewer than three times. */
    public boolean planChangedT10Card() {
        return planChangedT10Due() && planT10Laters < PLAN_T10_LATER_MAX;
    }

    /** "Later": one more put-off, never past the most. */
    public void answerPlanT10Later() {
        if (planT10Laters < PLAN_T10_LATER_MAX) planT10Laters++;
    }

    /** "Run the setup" (or any full setup): settled for good. The card carries the week-B
     *  line too (PlanCards#WEEKS_NOW_COUNT), so that is said with it. */
    public void answerPlanT10Setup() {
        planT10Seen = true;
        weeksBSeen = true;
    }

    /**
     * WEEK B (the owner's decision of 2026-10-03): WHETHER THE ONE-LINE NOTICE "Weeks with 2 full
     * sessions per track now count toward your plan." HAS BEEN SEEN - true means nothing is owed.
     * Shown once on the Trainer, dismissed with "Got it", to a person whose trainer was set up
     * before the rule changed and who is not still owed the upgrade card (which says it).
     *
     * MIGRATION: absent key - owed only by a file whose trainer is set up (running or paused);
     * every other file, and every new install, starts settled. Every save writes the key.
     */
    public boolean weeksBSeen = true;
    public static final String WEEKS_B_SEEN_KEY = "weeksBSeen";

    /** Whether the week-B notice shows now: owed, the plan running, and the upgrade card (which
     *  carries the same line) not owed instead. */
    public boolean weeksBNoticeDue() {
        return !weeksBSeen && trainerEnrolled && !planChangedT10Due();
    }
    /** Whether the OPTIONAL, CONCURRENT length track is active (plan §7: "length track is
     *  optional and concurrent with either girth style"). Off by default — opt-in, like
     *  every other trainer field. */
    public boolean trainerLengthOn = false;

    /* ---- THE THREE PRESSURE ANSWERS, and the switch that decides the beginner caps ----
     *
     * Person-level, not per-track: "how hard am I willing to go" and "how far should the
     * cuff fall between holds" are facts about the person, and a second copy per track is
     * a second answer to one question. Plan#startCapKpa is where the first two are spent;
     * Mint#workSet is where the third becomes a set.
     */

    /** Has this person never pumped before? The ONLY thing that turns the beginner caps on
     *  ({@link Plan#startCapKpa}). True by default, which is the conservative answer for a
     *  fresh install and the one a person who skips the question should get — the app used
     *  to apply those caps to everybody with a new PLAN, which is not the same fact. */
    public boolean rxNewToPumping = true;

    /** The most this person is willing to be prescribed, in kPa, or 0 for "not stated".
     *  Caps the girth track and the feeder (length has its own, {@link #rxLengthMaxKpa}).
     *  Never above {@link Plan#ABSOLUTE_CAP_KPA} or the device ceiling, both of which apply
     *  regardless of what is stored here. */
    public double rxWorkMaxKpa = 0;

    /** THE LENGTH TRACK'S OWN "MOST YOU WILL GO TO", kPa, or 0 for "not stated" - the same
     *  answer as {@link #rxWorkMaxKpa}, asked of length work. One answer served both tracks,
     *  so somebody who runs length at -10 with a most of -12 and girth at -9 with a most of
     *  -11 had length stopped at girth's -11. A file from before it was asked takes the shared
     *  answer (fromJson), so nothing it prescribes changes until it is answered. */
    public double rxLengthMaxKpa = 0;

    /** The pressure the cuff falls back to between holds, in whole kPa — the person's own
     *  preference in place of {@link Mint#DROP_KPA}. Default 3 (one inHg), the trainer's own.
     *  Above the usual {@link Mint#DROP_MAX_KPA} it is the person's call, asked once (owner,
     *  2026-09-30); those seconds never count toward net or dose whatever this is - they are
     *  left out by phase. {@link Mint#clampDropKpa} holds it to what may be saved, and each
     *  hold's pull holds it 1.0 inHg under that pull (Mint#dropFor). */
    public int rxDropKpa = 3;

    /**
     * THE LOWERED DROP ALREADY SAID (t10 R-62, A8) - the whole-kPa drop the "Your drop between
     * holds was lowered" notice last announced, so the same lowered value is said once and a
     * different one is said again. {@link #DROP_LOWERED_NONE} (-1) = nothing said yet.
     *
     * MIGRATION: -1 - no notice was ever shown before this existed. A value outside a drop's
     * reach (0 to the 57 kPa wire limit) reads as -1 (clampAll): the notice is owed again,
     * once, rather than silenced by a hand-edited number.
     */
    public int dropLoweredSaidKpa = DROP_LOWERED_NONE;
    public static final int DROP_LOWERED_NONE = -1;
    /** The girth track's own progression position — see {@link TrainerTrackState}. Girth
     *  and length progress INDEPENDENTLY (plan §7 concurrency ruling (a)), which is why
     *  each track gets its own instance rather than one shared position. */
    public TrainerTrackState trainerGirth = new TrainerTrackState();
    /** The length track's own progression position, independent of girth's (see
     *  {@link #trainerGirth}'s own doc). Meaningless while {@link #trainerLengthOn} is
     *  false, same convention {@link #restSec}-style "meaningless while off" fields
     *  already follow elsewhere in this class. */
    public TrainerTrackState trainerLength = new TrainerTrackState();

    /** PLAN-WIDE status — normal running, a currently-frozen deload week, a stepped-back
     *  plan, or an active safety flag (plan §7 concurrency ruling (c): "safety state is
     *  plan-wide — a RED steps back all tracks", and round-2: "one plan-wide deload
     *  cadence"). Deliberately ONE field for the whole plan, never per-track, matching
     *  that ruling — a track never shows a DIFFERENT plan-wide status from another. */
    public static final int TRAINER_STATE_NORMAL = 0;
    public static final int TRAINER_STATE_FROZEN_DELOAD = 1;
    public static final int TRAINER_STATE_STEP_BACK = 2;
    public static final int TRAINER_STATE_SAFETY_FLAG = 3;
    public int trainerState = TRAINER_STATE_NORMAL;

    /**
     * STAGE H TASK 5 — {@link Plan#safetyClears}'s two inputs, persisted so the pre-run
     * readiness ask's "All good" report can actually answer them: WHEN the flag was raised
     * (0 = not active) and whether NUMBNESS was among the reported symptoms — the one
     * override that additionally needs {@link Plan#NUMBNESS_OFF_MS} elapsed before an
     * "all good" report clears it (plan round-3 ruling). Both are written together whenever
     * the readiness ask's override toggles raise {@link #TRAINER_STATE_SAFETY_FLAG}, and
     * cleared together the moment {@link Plan#safetyClears} says the flag may lift — never
     * on silence, never by a timer alone. MIGRATION: 0 / false — a pre-Task-5 plan has no
     * flag active, the honest starting state.
     */
    public long trainerSafetyFlagAt = 0L;
    public boolean trainerSafetyNumbness = false;

    /**
     * STAGE H TASK 4 — the plan-wide deload cadence anchor and first-cycle flag (round-2:
     * "one plan-wide deload cadence anchored at first enrollment"; Task 1's {@link
     * Plan.Inputs#firstDeloadPending} caller contract). {@link #trainerLastDeloadMs} is when
     * the most recent deload week STARTED (0 = none taken yet): the engine's accumulated
     * training weeks are counted only AFTER it, and the seven days from it are the deload
     * week itself. {@link #trainerFirstDeloadTaken} flips true the first time a deload is
     * consumed, so every subsequent cycle uses the 3-week threshold instead of the first
     * cycle's 4 ({@link Plan#deloadDue}). MIGRATION: 0 / false — a pre-Task-4 plan has taken
     * no deload, the honest starting state (first cycle needs 4 training weeks).
     */
    public long trainerLastDeloadMs = 0L;
    public boolean trainerFirstDeloadTaken = false;

    /**
     * WHEN THE RECORDED DELOAD ENDED, exclusive. 0 means "seven days after it started", which
     * is what a tap deload is and what every file written before a deload could be REPORTED
     * contains. Ask {@link Deload#endMs} rather than reading this: it is the one place the
     * legacy default lives.
     */
    public long trainerLastDeloadEndMs = 0L;

    /** The start of the training gap the "was that a deload?" prompt has already been
     *  answered about, so it asks once per stretch rather than every time Today is drawn.
     *  MIGRATION: 0 — nothing has been answered. */
    public long deloadAskAnchorMs = 0L;

    /** t10 REAL-1: the day ({@link Summary#dayNumber}) the due cadence deload's question was
     *  last put - the dialog shown, or "Not now" - so it is asked once a morning, not at every
     *  draw (TrainerTab#deloadStartAskDue). MIGRATION: 0 - never asked. */
    public long deloadDueAskedDay = 0L;

    /**
     * t10-K - THE MONTH-12 BREAK (MonthBreak): its first day (local midnight, the day after it
     * was taken) and its return day (the date, or the "Come back now" tap), epoch millis. Both
     * tracks rest in between. MIGRATION: 0 - no break was ever taken (B6), which is the truth
     * about every file written before this one: the old girth break paused the plan instead
     * (it resumes as it always did) and the old length rest is the track's own restUntilMs.
     * Never negative (clampAll).
     */
    public long breakFromMs = 0L;
    public long breakUntilMs = 0L;
    /** t10-K - the break's changes have been made (MonthBreak#start: the length load's cut
     *  and its target, the clocks). MIGRATION: false - none made. */
    public boolean breakStarted = false;

    /**
     * EVERY RECENT DELOAD WINDOW, {start, end exclusive}, oldest first - not only the last.
     *
     * The pressure clock ({@link TrainerTrackState#pressureSinceMs}) can run across more
     * than one deload, and a deload week must never count toward a pressure step however
     * many light sessions were run in it (the owner's decision, 2026-09-26). The single
     * trainerLastDeloadMs forgets every window but the newest, so the windows are kept here
     * as {@link Deload#remember} records them. Newest {@link #DELOAD_WINDOWS_KEPT} kept.
     *
     * MIGRATION: empty. The newest window is still known from trainerLastDeloadMs, and
     * {@link Deload#touches} asks both; older ones on an upgraded file are simply unknown.
     */
    public final List<long[]> deloadWindows = new ArrayList<long[]>();
    public static final int DELOAD_WINDOWS_KEPT = 12;

    /** STAGE H TASK 4 — the FEEDER track's own last-mint idempotency fields (feeder is its
     *  own routine, separate from girth/length — round-2 feeder ruling). Same shape and
     *  meaning as {@link TrainerTrackState#lastMintId}/{@code lastMintSig}, but held on the
     *  Model because feeder has no {@link TrainerTrackState} of its own (its position is
     *  derived from girth). MIGRATION: empty. */
    public String trainerFeederMintId = "";
    public String trainerFeederMintSig = "";

    /**
     * STAGE H TASK 2 — ONE PAST DECISION {@link Plan#evaluate} produced, kept for the
     * Trainer tab's history list (Task 4): which track it was for, the fired action/tag/
     * rule/reason copied VERBATIM from the {@link Plan.Decision} that fired (plan misuse
     * rule: "decisions are events, computed at evaluation time, stored in history — never
     * retroactively rewritten" — so this is a frozen copy, never a live re-derivation),
     * when it fired, and whether the person acted on the suggestion it carried.
     */
    public static final class TrainerDecision {
        public int track;                  // Plan.TRACK_*
        public int action = Plan.ACTION_HOLD;
        public int tag = Plan.TAG_SOURCE;
        public String rule = "";
        public String reason = "";
        public long ts;
        public static final int STATE_PENDING = 0, STATE_ACCEPTED = 1, STATE_IGNORED = 2;
        public int state = STATE_PENDING;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("track", track);
            o.put("action", action);
            o.put("tag", tag);
            o.put("rule", rule == null ? "" : rule);
            o.put("reason", reason == null ? "" : reason);
            o.put("ts", String.valueOf(ts));
            o.put("state", state);
            return o;
        }
        public static TrainerDecision fromJson(JSONObject o) {
            TrainerDecision d = new TrainerDecision();
            if (o == null) return d;
            d.track = o.optInt("track", 0);
            d.action = o.optInt("action", Plan.ACTION_HOLD);
            d.tag = o.optInt("tag", Plan.TAG_SOURCE);
            d.rule = o.optString("rule", "");
            d.reason = o.optString("reason", "");
            d.ts = parseLong(o.optString("ts", "0"));
            d.state = o.optInt("state", STATE_PENDING);
            return d;
        }
    }

    /** How many past decisions {@link #trainerDecisions} keeps before the oldest is
     *  dropped (round-2 ruling: "decision history bounded, last 50, drop oldest") — the
     *  same bounded-list shape {@link Routine#MAX_VERSIONS} already establishes for
     *  version history, reused here rather than a second convention. */
    public static final int MAX_TRAINER_DECISIONS = 50;
    /** NEWEST FIRST — the same ordering convention {@link SessLog} already uses, so
     *  nothing downstream has to re-sort. Populated ONLY through
     *  {@link #addTrainerDecision}, the one place the bound is enforced; nothing else
     *  should append directly. */
    public final List<TrainerDecision> trainerDecisions = new ArrayList<TrainerDecision>();

    /** File a new decision and enforce the bound in one place — the newest entry goes to
     *  the front (matching {@link SessLog#file}'s own newest-first insert), and the
     *  OLDEST is dropped once the list would exceed {@link #MAX_TRAINER_DECISIONS}. */
    public void addTrainerDecision(TrainerDecision d) {
        if (d == null) return;
        trainerDecisions.add(0, d);
        while (trainerDecisions.size() > MAX_TRAINER_DECISIONS)
            trainerDecisions.remove(trainerDecisions.size() - 1);
    }

    /** REMINDER TOGGLES (plan §7b: "per-type toggles in the tab — training-day ·
     *  tracking-day · decision-ready · deload-start"). Task 3 wires the actual alarm/
     *  notification firing through the existing {@code ReminderReceiver}/alarm idiom
     *  (plan §7b: "riding the existing ReminderReceiver/alarm idiom"); this task persists
     *  only the CHOICE, off by default like every other opt-in reminder in this class
     *  ({@link #measRemind}, {@link #discreetNotifications}) — a notification nobody
     *  asked for is not a default anyone is entitled to set for them. */
    public boolean trainerRemindTrainingDay = false;
    public boolean trainerRemindTrackingDay = false;
    public boolean trainerRemindDecisionReady = false;
    public boolean trainerRemindDeloadStart = false;

    /**
     * STAGE H TASK 2 — one TRACK's own progression position: which level/week of the
     * guide's table it sits at and the pressure currently prescribed. Girth and length
     * each keep their own instance ({@link #trainerGirth}/{@link #trainerLength}) since
     * they progress independently. Defaults mirror {@link Plan.Inputs}' own "no data"
     * starting point — L1, week 0, the L1 floor pressure — a fresh enrolment's honest
     * starting position, never an invented mid-plan one.
     */
    public static final class TrainerTrackState {
        public int level = Plan.L1;
        public int weekIndex = 0;
        public double pressureKpa = Plan.L1_FLOOR_KPA;

        /**
         * STAGE H TASK 4 — IDEMPOTENCY: the id and prescription-signature of the LAST
         * routine Task 4's mint produced for this track, plus when. The suggestion card
         * compares the CURRENT prescription's signature ({@link Mint#signature}) against
         * {@link #lastMintSig}: equal (and the routine still resolves) → the prescription
         * has not changed, so no new mint is offered and a second Save opens the existing
         * one ("already saved"); different → the prescription changed, so a fresh mint is
         * offered (round-2 "mints are not weekly — only when the prescription CHANGES", and
         * the audit ruling "save is idempotent per track-week"). Signature, not name,
         * because the user may rename a mint (the marker is a track-id flag). {@link
         * #lastMintMs} also carries the length track's "months since last nudge" clock (the
         * length card's quiet monthly pressure creep, {@link Plan#evaluate}'s length branch
         * reading trainingWeeksAtPressure as months-since-nudge).
         *
         * MIGRATION: empty / 0 — no mint has been produced for a pre-Task-4 track, which is
         * the honest state (an empty signature never matches, so the first evaluation offers
         * the starting routine).
         */
        /** R1 - the SECOND half's routine, when the prescription was split. Empty when it
         *  was not, which is also the migration value: no prescription written before the
         *  split existed has a second part, and inventing one would put a routine in
         *  somebody's library that the plan never wrote. */
        public String lastMintId2 = "";

        public String lastMintId = "";
        public String lastMintSig = "";
        public long lastMintMs = 0L;

        /**
         * WHEN THE WORKING PRESSURE LAST CHANGED - the clock a pressure step counts from
         * (the owner's decision, 2026-09-26).
         *
         * It used to be {@link #lastMintMs}, which every routine rewrite moves: a taper step
         * after a deload, a week-table set step, a shape change, a save. So the "3 counting
         * weeks at this pressure" a step needs restarted with every gentle-return day, and
         * a compliant beginner saw no step before month 4. This moves ONLY when the pressure
         * the plan prescribes moves: a raise, a level-up, the user's own change, setup and
         * recalibrate - through {@link #setWorkingPressure} or {@link #restartPressureClock}.
         *
         * MIGRATION: the file's lastMintMs, copied once on load, so an upgrading track keeps
         * the clock it had (never an earlier one) and only stops being reset by rewrites.
         */
        public long pressureSinceMs = 0L;

        /**
         * THE ONE WAY THE WORKING PRESSURE IS WRITTEN. A different whole kPa restarts the
         * pressure clock; the same figure written again (a rewrite that moved only the
         * sets or the day's taper step) does not.
         */
        public void setWorkingPressure(double kpa, long nowMs) {
            if (Math.round(kpa) != Math.round(pressureKpa) || pressureSinceMs <= 0L)
                pressureSinceMs = nowMs;
            pressureKpa = kpa;
        }

        /** Restarts the pressure clock without moving the pressure - a level crossing, where
         *  the level's routine and cap change even when the figure does not. */
        public void restartPressureClock(long nowMs) { pressureSinceMs = nowMs; }

        /**
         * THE PERSON'S OWN SCALE FOR THIS TRACK - "plan ± x", kPa (the owner's decision,
         * 0.10). Every prescription for the track is the plan's own figure
         * ({@link #pressureKpa}, which stays the plan's and goes on progressing exactly as it
         * did) plus this. Set in the Trainer tab's "My pressure" card, or by the setup when a
         * working-pressure answer is above or below the plan's figure for the level. The
         * track's top moves with it; the hard limits never do (Scale).
         *
         * MIGRATION: 0 - the plan's own figure, which is what every older file prescribed.
         */
        public double offsetKpa = 0.0;

        /**
         * THE HIGHEST OFFSET THE PERSON HAS CONFIRMED going above the track's usual top, kPa -
         * the warned-once state (Scale#needsWarning): an offset at or under it is not asked
         * again, a higher one is. Only ever rises.
         *
         * MIGRATION: 0 - nothing has been warned, so the first offset above zero is asked.
         */
        public double warnedOffsetKpa = 0.0;

        /**
         * THE SETS YIELD HAS ADDED OR TAKEN, KEPT (the owner's decision, 2026-09-26) - added to
         * the table's or the level's own set count. It used to be worked out afresh at every
         * look, so an extra set vanished the moment the run of low readings ended, which is
         * exactly when it had started to work. Moved only by {@link Mint#commitYield} (a yield
         * change the plan applied or you saved) and by a level crossing, setup, recalibrate, a
         * break or a change of style. Never takes the set count under the table's row or the
         * level's minimum (Mint#yieldFloorSets).
         *
         * MIGRATION: 0 - nothing kept, which is what every older file effectively had.
         */
        public int yieldSets = 0;

        /**
         * WHEN THE YIELD STREAK LAST RESTARTED: only readings from sessions after this count
         * toward the next three-in-a-row, so an adjustment needs three NEW readings before
         * another. MIGRATION: 0 - every reading counts, as before.
         */
        public long yieldSinceMs = 0L;

        /**
         * STAGE H TASK 4 — the STABLE ANCHOR for calendar week-table advancement. {@link
         * #weekIndex} advances after enrollment as training weeks accumulate (so the guide's
         * scheduled set increases actually reach the user), but the advance is recomputed
         * each evaluation as {@code weekBaseIndex + trainingWeeksSince(weekBaseMs)} — so this
         * pair, set once at onboarding/recalibrate and never moved by the advance itself, is
         * what keeps the recomputation from double-advancing (a running total written back
         * into weekIndex would be re-added to on every render). MIGRATION: 0 — a track saved
         * before this existed falls back to reading {@link #weekIndex}/enrollment as the base
         * (see SessionActivity#advanceWeekIndexes), so an upgrading user still advances from
         * where they are, never from zero.
         */
        public int weekBaseIndex = 0;
        public long weekBaseMs = 0L;

        /**
         * 0.10 - THE WEEK POSITION GROWS TRADITIONAL GIRTH'S HOLDS (Plan#traditionalSets). True
         * on every track made since; MIGRATION: false on a track saved before, whose traditional
         * week was never counted - Mint#startWeekGrowth starts its level's growth once, from the
         * level's first week (never from where its old anchor would put it), then sets it.
         */
        public boolean weekGrowth = true;

        /**
         * 0.10 - THE HALF START (the owner's decision, Plan#traditionalHalfStart): a traditional
         * track whose routine predates the growth BUILDS UP to its level's count above Level 1.
         * The build-up's count at {@link #buildWeek}; it adds one hold every
         * Plan#TRAD_BUILD_WEEKS_PER_HOLD counted weeks of {@link #weekIndex} from there
         * (Mint#buildUpSets) and caps the level's count until it reaches it. 0 = no build-up -
         * every track made since, and every Level 1 one. MIGRATION: absent = 0; an older track
         * is given one by Mint#startWeekGrowth.
         */
        public int buildSets = 0;
        /** The week position {@link #buildSets} is counted from (0 or 1; 0 carries a counted
         *  week that had not yet added a hold across a level crossing). MIGRATION: 0. */
        public int buildWeek = 0;
        /** Whether the plan-change notice has said this track's build-up: false from the start
         *  of one until the first rewrite that says it (SessionActivity#applyPlanTo). MIGRATION:
         *  true - nothing to say. */
        public boolean buildSaid = true;

        /** STAGE H TASK 4 (fix round 3) — the CARRIED set count for a table-less level (L3/L4),
         *  set when a level-up "carries the achieved volume" (the guidance "L2→L3: achieved volume
         *  carries"). 0 means "no carry — use the level's milestone-derived base". Only read
         *  for L3/L4 (interval levels with no week table); L1/L2 read their table row instead.
         *  MIGRATION: 0. */
        public int carriedSets = 0;

        /* ---- THE LENGTH TIER'S OWN STATE (trainer-v3 Phase 2) -------------------------- *
         *  Only the length track reads these. A girth track carries them, unread, rather
         *  than growing a second state class for one track - the same way it carries
         *  carriedSets, which only the interval levels read.                                */

        /**
         * The governed traction LOAD, pounds. What the tissue actually feels; the pressure
         * that produces it is derived from this and the user's girth at command time, never
         * the other way round.
         *
         * MIGRATION: {@link Plan#LENGTH_LOAD_START_LB}, the ladder's own starting rung. A
         * file written before the length tier has never run traction, so the honest value is
         * "not started yet", which is exactly where the ladder starts everyone.
         */
        public double loadLb = Plan.LENGTH_LOAD_START_LB;

        /** How many strain sets the length session runs, 2 up to
         *  {@link Plan#LENGTH_STRAIN_SETS_MAX}. MIGRATION: the starting count, for the same
         *  reason as the load. */
        public int strainSets = Plan.LENGTH_STRAIN_SETS_START;
        /** R11-3 - training weeks the length calendar's strain sets start from (Placement
         *  #strainCalendarWeeks): a setup that placed the track at N strain sets has its
         *  calendar go on from N, not from the first two. 0 for a setup placed by the months.
         *  MIGRATION: absent -> 0, the calendar as before. */
        public int strainCalWeeks = 0;

        /**
         * WHEN THE LENGTH WORK LAST CHANGED - the strain debounce's clock. Only readings
         * taken after this count toward the week of low strain the ladder waits for
         * (Meas#strainMissDays), so a strain set or a load step needs a fresh week measured
         * on the new work before the next one - the length twin of {@link #yieldSinceMs}.
         * Without it the week that earned one set went on asking, and every training morning
         * offered another. Moved only by {@link #setStrainSets} and {@link #setLoadLb}.
         *
         * MIGRATION: 0 - every reading counts, as before.
         */
        public long strainSinceMs = 0L;

        /** THE ONE WAY THE STRAIN SET COUNT IS WRITTEN: a different count restarts the strain
         *  debounce's clock ({@link #strainSinceMs}); the same count written again does not. */
        public void setStrainSets(int n, long nowMs) {
            if (n != strainSets) strainSinceMs = nowMs;
            strainSets = n;
        }

        /** And the load's: a different load restarts the same clock - a heavier pull (or a
         *  lighter one after a confirmed high reading) is new work, read afresh. It is also
         *  when the load last moved ({@link #loadMovedMs}). */
        public void setLoadLb(double lb, long nowMs) {
            if (Math.abs(lb - loadLb) > 1e-9) { strainSinceMs = nowMs; loadMovedMs = nowMs; }
            loadLb = lb;
        }

        /** t10 R-44 - restarts the strain clock without moving the work: a girth-focus block
         *  handing length back, where only readings from then on should count. */
        public void restartStrainClock(long nowMs) { strainSinceMs = nowMs; }

        /**
         * WHEN A GIRTH-FOCUS BLOCK ENDS, epoch millis - 0 meaning there is no block.
         *
         * A BLOCK, NOT A SWITCH. While this is in the future the traction work pauses and
         * the expansion coda grows; when it passes, the track hands itself back with nothing
         * asked of the user. Storing the END rather than a flag is what makes the return
         * automatic: there is no second event that has to fire, and an app that never opens
         * for a month still comes back to a track that is no longer in focus.
         *
         * MIGRATION: 0 - no block, which is the truth about every file written before one
         * could be proposed.
         */
        public long focusBlockUntilMs = 0L;

        /**
         * WHEN A FINISHED BLOCK'S RESULT WAS LAST SHOWN, epoch millis - 0 meaning never.
         *
         * A block that ends silently is a block nobody learns anything from: the whole point
         * of running one is to find out whether the tissue filled, and that answer has to
         * arrive once, on its own, without the user having to go looking for it. This marks
         * it as delivered so it is said once rather than on every render for ever.
         *
         * MIGRATION: 0 - no block has ever ended, which is the truth about every file written
         * before one could be started.
         */
        public long focusBlockSeenMs = 0L;

        /** When the block that is running (or the one that just ended) began - derived from
         *  its end and the fixed length, so there is no second date to keep in step. */
        public long focusBlockStartMs() {
            return focusBlockUntilMs <= 0 ? 0L
                : focusBlockUntilMs - Plan.GIRTH_FOCUS_WEEKS * 7L * 86400000L;
        }

        /** A block has run and finished, and its result has not been shown yet. */
        public boolean focusBlockJustEnded(long nowMs) {
            return focusBlockUntilMs > 0 && focusBlockUntilMs <= nowMs
                && focusBlockSeenMs < focusBlockUntilMs;
        }

        /**
         * HOW MANY WEEKS THIS TRACK HAS BEEN ASKED TO REPEAT - a count, subtracted from the
         * advance, never a rewind of {@link #weekIndex} itself.
         *
         * The advance is recomputed every time from {@link #weekBaseIndex} and
         * {@link #weekBaseMs}, so it is idempotent: subtracting here can be applied on every
         * render for the rest of time and the answer does not move. Decrementing weekIndex
         * instead would be applied once per render, and a week would walk backwards while
         * somebody watched.
         *
         * MIGRATION: 0 - nothing has been repeated, which is the truth about every file
         * written before a week could be.
         */
        public int weekRepeats = 0;

        /**
         * WHICH WEEKS WERE CHARGED A REPEAT, so a deload reported afterwards can give them
         * back. Each entry is {weekStartMs, repeats added}. Without this the count was a
         * total with no account behind it, and "that week was a deload" could only be
         * believed, never acted on. Newest {@link Plan#MISS_CHARGES_KEPT} kept.
         */
        public final List<long[]> missCharges = new ArrayList<long[]>();

        /** The Monday of the last week the miss policy has already judged - so a week is
         *  judged ONCE and a repeat cannot be added again on the next render. MIGRATION: 0,
         *  which reads as "no week judged yet"; the first check after an upgrade looks at
         *  the week just gone and nothing older, so no history is retroactively penalised. */
        public long missCheckedWeekMs = 0L;

        /**
         * A DATED REST FOR THIS TRACK ALONE - the length ladder's month-12 fork, third arm.
         *
         * The guide's answer at that crossing is three-way: continue, a girth-focus block,
         * or four to six weeks off. Only the third had no mechanism of its own: it borrowed
         * the girth track's break, which sets trainerEnrolled false and stops the WHOLE
         * plan. A length decision stopping girth work is the app stopping something nobody
         * named - which is the one thing this app does not do.
         *
         * DATED, so it ends on its own with nothing to remember and no second event that has
         * to fire. Ending it is an OFFER, never a resumption: the track simply starts
         * proposing again, and resting longer than the date is the user's to take by not
         * running.
         *
         * MIGRATION: 0, and 0 means "no rest was ever taken". That is the truth about every
         * file written before this existed, not merely a convenient default - a track cannot
         * have been resting under a mechanism that did not exist.
         */
        public long restUntilMs = 0L;

        /**
         * WHEN THE DATED REST BEGAN, epoch millis (t10 O7) - 0 when the start was never kept.
         * Kept so a rest ended early ("Come back to girth now" moves {@link #restUntilMs} to
         * the tap) still knows which weeks it touched: the miss policy forgives exactly those
         * (TrainerTab#girthRestTouchesWeek), never the weeks before it.
         *
         * MIGRATION: 0 - a rest saved before this counts its weeks back from its end, as it
         * always did. Never negative (clampAll).
         */
        public long restFromMs = 0L;

        /** Whether a dated rest is running at this instant. The one predicate, for the same
         *  reason {@link #inGirthFocus} is one. */
        public boolean resting(long nowMs) {
            return restUntilMs > 0 && restUntilMs > nowMs;
        }

        /** Whether a girth-focus block is running at this instant. The one predicate, so no
         *  caller has to remember which way the comparison goes. */
        public boolean inGirthFocus(long nowMs) {
            return focusBlockUntilMs > 0 && focusBlockUntilMs > nowMs;
        }

        /* ---- t10: THE GIRTH TRACK'S NEW STATE (lane B) --------------------------------- *
         *  Read only on the girth track; a length track carries them unread, as above.      */

        /**
         * R-23 (C4/A4) - A LOW-YIELD ADD IS WAITING TO BE JUDGED: set when a low-yield add is
         * accepted, cleared by an in-window reading, a level-up or the answer to the offer
         * (a week off, or 4 weeks of length focus). While it is set, three more lows bring the
         * offer instead of another add. MIGRATION: false - nothing pending.
         */
        public boolean addPending = false;

        /**
         * R-25 - THE HOLDS YIELD HAS ADDED TO A HYBRID ROUTINE'S OWN 5-MINUTE HOLDS (not the
         * hidden interval count). Read by RxBuild#hybridRx. MIGRATION: 0 - the hybrid's
         * fixed holds, as before. Held to 0..{@link #HYBRID_YIELD_MAX} (clampAll).
         */
        public int hybridYield = 0;
        /** The most {@link #hybridYield} may be: the hybrid's own top count of holds. */
        public static final int HYBRID_YIELD_MAX = 8;

        /**
         * R-27 (R2) - THE HIGH-WATER MARK OF HOLDS ALREADY CONVERTED TO PRESSURE at the
         * time-under-pressure cap, so the same holds never convert twice. Reset at a level
         * change. MIGRATION: 0 - nothing converted. Never negative (clampAll).
         */
        public int r2ExHolds = 0;

        /**
         * R-27 (R2) - THE PRESSURE A CONVERSION STILL OWES, whole kPa, riding on the next
         * regular steps (at most +3 kPa a morning) and dropped at the top. MIGRATION: 0.
         * Held to 0 and the 15 inHg absolute limit (clampAll).
         */
        public int r2PendKpa = 0;

        /* ---- t10: THE LENGTH TRACK'S NEW STATE (lane C) -------------------------------- *
         *  Read only on the length track; a girth track carries them unread.                */

        /** R-40 (option D) - a D2 strain set was added in this deload block. Reset when a
         *  deload starts. MIGRATION: false. */
        public boolean dAddedInBlock = false;

        /** R-40 (option D) - the D3 offer (a week off, or a girth block) was made in this
         *  block: once a block. Reset when a deload starts. MIGRATION: false. */
        public boolean dOfferedInBlock = false;

        /**
         * R-45 (L1) - THE MONTH-3 HAND-OVER TO 6 STRAIN SETS HAS HAPPENED (or is not owed).
         * MIGRATION: true when the file's trainer is set up and is at month 3 or later on the
         * load that first reads it (Model#fromJson), so an upgrader's sets are not stepped by
         * surprise - the upgrade card covers it; false otherwise, and on a new track.
         */
        public boolean handedOver = false;

        /** R-43 (A9) - the "this passes 12 lb before month 12" warning has been said, once.
         *  MIGRATION: false. */
        public boolean warned12 = false;

        /**
         * R-46 (L2) - THE LENGTH TRAINING-WEEK COUNT AT THE LAST SLOW LOAD STEP
         * (TrainerTab#accumulatedTrainingWeeks), {@link #SLOW_LOAD_NONE} = no step yet.
         * MIGRATION: -1. Never under -1 (clampAll).
         */
        public int slowLoadWeeks = SLOW_LOAD_NONE;
        public static final int SLOW_LOAD_NONE = -1;
        /** R11-4 - the answer to "Step up now?" (GainBrake#ANSWER_*) and the clock of the step
         *  it answered (GainBrake#clockKey): applying that step starts a new clock, so the next
         *  one is asked afresh. MIGRATION: absent -> -1 / none, nothing answered. */
        public long brakeKey = -1L;
        public int brakeAnswer = GainBrake.ANSWER_NONE;

        /** R-40 (C10) - when the last confirmed-high cut was taken, epoch millis; at most one
         *  cut per 7 days. MIGRATION: 0 - none. Never negative (clampAll). */
        public long lastCutMs = 0L;

        /** R-40 - confirmed-high cuts in a row; after 2 the card asks about slippage or the
         *  measuring. MIGRATION: 0. Never negative (clampAll). */
        public int cutsInRow = 0;

        /**
         * t10 REAL-11 - WHEN THE LOAD LAST MOVED, epoch millis ({@link #setLoadLb}, whatever
         * moved it). A load that has moved since the last length session takes no further step
         * until a session has run at it (Plan.Inputs#loadMovedSinceSession): one load change
         * between two sessions. Unlike {@link #strainSinceMs} nothing puts it back.
         * MIGRATION: 0 - not known, so nothing waits. Never negative (clampAll).
         */
        public long loadMovedMs = 0L;

        /**
         * t10 parity run 2, O-1 - WHEN THE PLAN LAST CHANGED THIS TRACK'S WORK ITSELF, epoch
         * millis: a volume step, a cut, the fallback, the time cap's conversion, a pressure step
         * (Mint#commitYield, where a plan change reaches the routine). Girth only: one change a
         * girth morning (Plan.Inputs#girthChangedToday) - every Trainer render asks the plan
         * again, and the next answer used to land the same morning. An offer the person answered
         * does not set it. MIGRATION: 0 - none, so nothing waits. Never negative (clampAll).
         */
        public long planChangeMs = 0L;

        /**
         * Parity run 3, OPEN-2a - THE BLOCK THE OPTION-D FLAGS BELONG TO (#dAddedInBlock,
         * #dOfferedInBlock): its start (LengthTrack#blockStartAt) when one was last set. They
         * are read only in that block, so a set added in the days before a week off booked for
         * later does not carry into the block after it. MIGRATION: 0 - not known, the flags
         * read as they are (Deload#remember still clears them). Never negative (clampAll).
         */
        public long dBlockMs = 0L;

        /**
         * t10-K (B4/B5) - THE CLIMB-BACK TARGET, pounds: the length load before the month-12
         * break, kept while the load comes back from three quarters of it (MonthBreak). 0 = no
         * climb back - reached, ended by a confirmed cut, or never taken. MIGRATION: 0 (B6: an
         * old length rest ends on its date with nothing to climb back to). Held to 0..15 lb
         * (clampAll).
         */
        public double climbTargetLb = 0.0;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("level", level);
            o.put("week", weekIndex);
            // Through a string, like every other double in this class — the desktop
            // org.json shim has no optDouble (see Meas.fromJson's own note).
            o.put("pressureKpa", String.valueOf(pressureKpa));
            o.put("lastMintId", lastMintId == null ? "" : lastMintId);
            o.put("lastMintId2", lastMintId2 == null ? "" : lastMintId2);
            o.put("lastMintSig", lastMintSig == null ? "" : lastMintSig);
            o.put("lastMintMs", String.valueOf(lastMintMs));
            o.put("pressureSince", String.valueOf(pressureSinceMs));
            o.put("yieldSets", yieldSets);
            o.put("yieldSince", String.valueOf(yieldSinceMs));
            o.put("weekBaseIndex", weekBaseIndex);
            o.put("weekBaseMs", String.valueOf(weekBaseMs));
            o.put("weekGrowth", weekGrowth);
            o.put("buildSets", buildSets);
            o.put("buildWeek", buildWeek);
            o.put("buildSaid", buildSaid);
            o.put("carriedSets", carriedSets);
            // A double, so a string - the desktop shim has no optDouble, and this file's
            // rule is that both readers decode one shape.
            o.put("loadLb", String.valueOf(loadLb));
            o.put("strainSets", strainSets);
            if (strainCalWeeks != 0) o.put("strainCalWk", strainCalWeeks);
            o.put("strainSince", String.valueOf(strainSinceMs));
            o.put("focusUntil", String.valueOf(focusBlockUntilMs));
            o.put("focusSeen", String.valueOf(focusBlockSeenMs));
            o.put("weekRepeats", weekRepeats);
            JSONArray mcs = new JSONArray();
            for (int i = 0; i < missCharges.size(); i++) {
                long[] c = missCharges.get(i);
                mcs.put(c[0] + ":" + c[1]);
            }
            o.put("missCharges", mcs);
            o.put("missChecked", String.valueOf(missCheckedWeekMs));
            o.put("restUntil", String.valueOf(restUntilMs));
            o.put("restFrom", String.valueOf(restFromMs));
            // Doubles through strings, as every other double in this class.
            o.put("offsetKpa", String.valueOf(offsetKpa));
            o.put("warnedOffset", String.valueOf(warnedOffsetKpa));
            // t10 - the girth track's and the length track's new state.
            o.put("addPending", addPending);
            o.put("hybridYield", hybridYield);
            o.put("r2ExHolds", r2ExHolds);
            o.put("r2PendKpa", r2PendKpa);
            o.put("dAdded", dAddedInBlock);
            o.put("dOffered", dOfferedInBlock);
            o.put("handedOver", handedOver);
            o.put("warned12", warned12);
            o.put("slowLoadWk", slowLoadWeeks);
            if (brakeAnswer != GainBrake.ANSWER_NONE) {
                o.put("brkKey", brakeKey);
                o.put("brkAns", brakeAnswer);
            }
            o.put("lastCut", String.valueOf(lastCutMs));
            o.put("cutsInRow", cutsInRow);
            o.put("loadMoved", String.valueOf(loadMovedMs));
            o.put("planChange", String.valueOf(planChangeMs));
            o.put("dBlock", String.valueOf(dBlockMs));
            // t10-K - a double, so a string, as every other double in this class.
            o.put("climbTo", String.valueOf(climbTargetLb));
            return o;
        }
        public static TrainerTrackState fromJson(JSONObject o) {
            TrainerTrackState s = new TrainerTrackState();
            if (o == null) return s;
            s.level = o.optInt("level", Plan.L1);
            s.weekIndex = o.optInt("week", 0);
            try {
                s.pressureKpa = Double.parseDouble(o.optString("pressureKpa", ""));
            } catch (NumberFormatException e) {
                s.pressureKpa = Plan.L1_FLOOR_KPA;
            }
            // MIGRATION (Stage H Task 4): absent on every track saved before the mint
            // existed — empty/0, the honest "nothing minted yet" state.
            s.lastMintId = o.optString("lastMintId", "");
            s.lastMintId2 = o.optString("lastMintId2", "");
            s.lastMintSig = o.optString("lastMintSig", "");
            s.lastMintMs = parseLong(o.optString("lastMintMs", "0"));
            // MIGRATION (program fixes, 2026-09-26): absent before the pressure clock was
            // its own - the clock it replaces, copied once. See the field's doc.
            s.pressureSinceMs = o.has("pressureSince")
                ? parseLong(o.optString("pressureSince", "0")) : s.lastMintMs;
            // MIGRATION (program fixes): no yield sets kept, every reading in the streak.
            s.yieldSets = o.optInt("yieldSets", 0);
            s.yieldSinceMs = parseLong(o.optString("yieldSince", "0"));
            s.weekBaseIndex = o.optInt("weekBaseIndex", 0);
            s.weekBaseMs = parseLong(o.optString("weekBaseMs", "0"));
            // MIGRATION (0.10): absent before traditional girth grew by the week - its week
            // position never counted; Mint#startWeekGrowth starts it once.
            s.weekGrowth = o.optBoolean("weekGrowth", false);
            // MIGRATION (0.10, the half start): absent before it - no build-up, nothing to say.
            // A track with no weekGrowth gets one from Mint#startWeekGrowth.
            s.buildSets = Math.max(0, o.optInt("buildSets", 0));
            s.buildWeek = Math.max(0, o.optInt("buildWeek", 0));
            s.buildSaid = o.optBoolean("buildSaid", true);
            s.carriedSets = o.optInt("carriedSets", 0);
            // MIGRATION: the ladder's own starting values, and no focus block. See the
            // fields' own doc for why those are the truth about a pre-length-tier file
            // rather than merely convenient defaults.
            try {
                s.loadLb = Double.parseDouble(o.optString("loadLb", ""));
            } catch (NumberFormatException e) {
                s.loadLb = Plan.LENGTH_LOAD_START_LB;
            }
            s.strainSets = o.optInt("strainSets", Plan.LENGTH_STRAIN_SETS_START);
            s.strainCalWeeks = Math.max(0, o.optInt("strainCalWk", 0));
            // MIGRATION (0.10): absent before the strain clock - every reading counts.
            s.strainSinceMs = parseLong(o.optString("strainSince", "0"));
            s.focusBlockUntilMs = parseLong(o.optString("focusUntil", "0"));
            // MIGRATION: 0 - no block has ever ended.
            s.focusBlockSeenMs = parseLong(o.optString("focusSeen", "0"));
            // MIGRATION: 0 - no rest was ever taken. See the field's own doc.
            s.restUntilMs = parseLong(o.optString("restUntil", "0"));
            // MIGRATION (t10 O7): 0 - the start of an older rest was never kept.
            s.restFromMs = parseLong(o.optString("restFrom", "0"));
            s.weekRepeats = o.optInt("weekRepeats", 0);
            // MIGRATION: absent on every file written before a report could refund a charge.
            // An empty ledger is the honest state — those charges cannot be given back.
            JSONArray mcs = o.optJSONArray("missCharges");
            if (mcs != null) for (int i = 0; i < mcs.length() && i < Plan.MISS_CHARGES_KEPT; i++) {
                String cell = mcs.optString(i);
                int colon = cell.indexOf(':');
                if (colon <= 0) continue;
                long ws = parseLong(cell.substring(0, colon));
                long add = parseLong(cell.substring(colon + 1));
                if (ws > 0L && add > 0L) s.missCharges.add(new long[]{ ws, add });
            }
            s.missCheckedWeekMs = parseLong(o.optString("missChecked", "0"));
            // MIGRATION (0.10, the personal scale): absent on every older file - no offset
            // (the plan's own figure, which is what those files prescribed) and nothing warned.
            s.offsetKpa = parseScale(o.optString("offsetKpa", "0"));
            s.warnedOffsetKpa = Math.max(0.0, parseScale(o.optString("warnedOffset", "0")));
            // MIGRATION (t10): absent on every older track - nothing pending, nothing added,
            // nothing converted or owed, no cut, no slow step, nothing warned. handedOver is
            // decided by Model#fromJson from the months (it needs the whole file). Bounds are
            // clampAll's.
            s.addPending = o.optBoolean("addPending", false);
            s.hybridYield = o.optInt("hybridYield", 0);
            s.r2ExHolds = o.optInt("r2ExHolds", 0);
            s.r2PendKpa = o.optInt("r2PendKpa", 0);
            s.dAddedInBlock = o.optBoolean("dAdded", false);
            s.dOfferedInBlock = o.optBoolean("dOffered", false);
            s.handedOver = o.optBoolean("handedOver", false);
            s.warned12 = o.optBoolean("warned12", false);
            s.slowLoadWeeks = o.optInt("slowLoadWk", SLOW_LOAD_NONE);
            s.brakeKey = o.optLong("brkKey", -1L);
            int ans = o.optInt("brkAns", GainBrake.ANSWER_NONE);
            s.brakeAnswer = ans == GainBrake.ANSWER_WAIT || ans == GainBrake.ANSWER_STEP_UP
                ? ans : GainBrake.ANSWER_NONE;
            s.lastCutMs = parseLong(o.optString("lastCut", "0"));
            s.cutsInRow = o.optInt("cutsInRow", 0);
            s.loadMovedMs = parseLong(o.optString("loadMoved", "0"));
            s.planChangeMs = parseLong(o.optString("planChange", "0"));
            s.dBlockMs = parseLong(o.optString("dBlock", "0"));
            // MIGRATION (t10-K): absent before the month-12 break's climb back - none.
            try { s.climbTargetLb = Double.parseDouble(o.optString("climbTo", "0")); }
            catch (NumberFormatException e) { s.climbTargetLb = 0.0; }
            return s;
        }

        /** An offset read from a file: held to the stepper's reach, garbage and NaN as none. */
        private static double parseScale(String v) {
            try { return Scale.clampOffset(Double.parseDouble(v)); }
            catch (NumberFormatException e) { return 0.0; }
        }
    }

    /* ---- the measurement reminder --------------------------------------------------- */

    /**
     * A SECOND REMINDER, for MEASURING rather than training. Opt-in, like the training one:
     * false on a fresh install and false on every old file, because a notification nobody
     * asked for is not a default anyone is entitled to set for them.
     *
     * WHEN IT FIRES. The measurement cadence ({@link Meas}) counts SESSIONS and HOURS, not
     * calendar days — it has no notion of "Tuesday" and cannot name one. So the alarm is
     * checked at this hour EVERY day and only notifies when the cadence itself says a
     * reading is due ({@link MeasLog#due}), which is the honest reading of "on the
     * measurement cadence's due day": the cadence decides, the clock only picks the moment
     * of day it is asked. With the cadence off, nothing ever fires.
     */
    public boolean measRemind = false;
    /** The local hour and minute the measurement reminder is checked at, 24h. Bounded by
     *  clampAll, so a hand-edited file cannot print an hour of 30 against a row that
     *  claims 0–23. */
    public int measRemindHour = 19, measRemindMin = 30;

    /** HAPTIC TICKS on the run screen (default ON). Three short buzzes counting a preset
     *  out — 3, 2, 1 — a longer one when the preset changes, and one when the run ends.
     *  It is a preference, so it is persisted; it defaults to TRUE on load, so a phone
     *  that saved its settings before this existed gets the feature rather than a silent
     *  screen it never asked to turn off. It gates FEEDBACK only: nothing about what is
     *  sent to the pump, and nothing about the countdown itself, reads this flag. */
    public boolean hapticTicks = true;

    /** RUN COLOURS (0.10 run-screen redesign, Settings › Run colours). Whether the run is
     *  coloured by the kind of step playing (default ON - the owner's pick); where the colour
     *  shows (RunLook#WHERE_LINE, the status line only, by default); and one colour per step
     *  kind, in RunLook's kind order. STOP is not a kind and is never among them. Loaded
     *  through RunLook#sanitize, so a file can never put STOP red, or two kinds in one colour,
     *  on the run screen. MIGRATION: absent in every older file - the defaults. */
    public boolean runColourOn = true;
    public int runColourWhere = RunLook.WHERE_LINE;
    public int[] runColours = RunLook.DEFAULT.clone();

    /** VIBRATE BEFORE THE PULL (default OFF): one short buzz as a rest enters its last ten
     *  seconds before a pull. Feedback only; nothing sent to the pump reads it. MIGRATION:
     *  absent in every older file - off, the feature's own default. */
    public boolean pullBuzz = false;

    /** WHERE THE RUN SCREEN'S − / + CONTROLS SIT (0.10 final, Settings › On the run screen):
     *  {@link #RUN_STRIP_PINNED} - in the fixed footer, above the Pause / Skip row, always in
     *  view (the default, the owner's pick) - or {@link #RUN_STRIP_UNDER_CHART}, in the
     *  scrolling page right after the pressure chart, which then keeps its full height.
     *  Or {@link #RUN_STRIP_RAMP_FIRST} (0.10, owner decision): on a ramp's step and in the
     *  warm-up the chart comes first with the controls right under it; every other step keeps
     *  them pinned (QuickAdjust#chartFirst / #stripInPage decide, purely).
     *  Where they are drawn only; nothing sent to the pump reads it. MIGRATION: absent in
     *  every older file - pinned; a 0 or 1 keeps its meaning (2 is new, so no old file holds
     *  it). An unknown value loads as pinned. */
    public int runStripWhere = RUN_STRIP_PINNED;
    public static final int RUN_STRIP_PINNED = 0, RUN_STRIP_UNDER_CHART = 1,
                            RUN_STRIP_RAMP_FIRST = 2;

    /** A stored "where the − / + controls sit", taken only when it is one of the three. */
    public static int clampRunStripWhere(int w) {
        return w == RUN_STRIP_UNDER_CHART || w == RUN_STRIP_RAMP_FIRST ? w : RUN_STRIP_PINNED;
    }

    /** TONE CUES on the run screen (S12, default OFF). The same three moments the haptic
     *  ticks mark — a preset's last three seconds, a preset changing, the run ending —
     *  through a GENERATED tone (ToneGenerator, never an audio file) on STREAM_MUSIC, so
     *  it follows the media volume rather than the ringer's. Default OFF, unlike the
     *  haptic ticks: a buzz in the hand is easy to miss and a quiet nicety to default on;
     *  an audible beep is not, and this app is body-adjacent, often used somewhere a
     *  sudden tone is unwelcome even with the ringer on. It is a preference, so it is
     *  persisted. It gates FEEDBACK only — nothing about what is sent to the pump, and
     *  nothing about the countdown itself, reads this flag — and even while it is ON, the
     *  Android side (Tone#shouldSound) still asks the ringer mode and the interruption
     *  filter before every single cue, since Do Not Disturb does not by itself duck an
     *  app's own media stream. */
    public boolean toneCues = false;

    /** SOUND ON A CHOSEN REPETITION of the routine (default OFF, which is
     *  {@link RepCue#OFF}). One int: OFF, {@link RepCue#LAST}, or the repetition number
     *  itself see RepCue for what a repetition is here and why the value is a single
     *  field. It is a preference, so it is persisted, and it is clamped on load, since a
     *  save file is hand-editable.
     *
     *  IT DOES NOT READ {@link #toneCues}: the three tone cues mark time, and somebody who
     *  wants only "this is the last one" should not have to take the other three with it.
     *  Like both of those flags it gates FEEDBACK only nothing about what is sent to the
     *  pump, and nothing about the countdown or the plan, reads it. */
    public int repCueAt = RepCue.OFF;

    /* ---- when the app offers to save what you actually ran ------------------------- */
    /* The thresholds a run has to CROSS before the save card is put in front of anyone.
     * Under them the summary says one quiet line with a "save ▸" that unfolds the same
     * card, so nothing is ever hidden — the threshold decides how loudly it is offered,
     * never whether it is available. Settings exposes all three. */

    /** How far a block's pressure must differ from the planned set before the card is
     *  shown, as an ABSOLUTE kPa magnitude (storage is always kPa; the stepper renders it
     *  through Fmt and stores kPa back, so switching display unit never moves the stored
     *  threshold). Default 2.0 kPa. Zero means "any change at all asks". */
    public double offerPressureKpa = 2.0;
    /** The same bar for the two non-pressure axes: speed in absolute PERCENTAGE POINTS,
     *  and upper hold as a RELATIVE percentage (which additionally has to move at least
     *  5 s, so a 20 % change to a 3-second hold does not ask). Default 20. */
    public int offerHoldSpeedPct = 20;
    /** Whether skipping part of a run counts as a difference worth offering to save.
     *  Default true: a skip is the most deliberate change a person can make mid-run. */
    public boolean offerOnSkip = true;

    /** THE COACHING LINE on the run screen — one sentence about what is coming next,
     *  derived each tick from the plan. Default OFF, because it is the kind of help that
     *  is welcome to some people and noise to everyone else, and it shares a screen with a
     *  live pressure readout. It goes in the `nextSub` slot and NEVER replaces the
     *  countdown.
     *
     *  (0.10 final) NO LONGER READ: the sentence lived under the run screen's upcoming list,
     *  which is gone (one place per job - Coming steps), and so is its Settings row. Kept,
     *  and saved, so a file that carries it round-trips unchanged. */
    public boolean coachLine = false;

    /** KEEP THE SCREEN ON DURING A RUN. Default TRUE, and true on load for a phone that
     *  saved its settings before this existed: the run screen is a live instrument — a
     *  countdown, a pressure trace and the STOP button — and a display that sleeps
     *  mid-run hides the one control that vents the cuff behind a lock screen. It is a
     *  preference because the battery cost is real and some people run with the phone
     *  face-down; it gates the window flag ONLY, never anything the pump is sent. */
    public boolean keepScreenOn = true;

    /** DISCREET NOTIFICATIONS on the run screen (T7/T17, dist/round7-options.html
     *  decision "T17 A"). Default OFF, following privacyBlur's own reasoning (below, in
     *  the "privacy blur" section): turning a genuinely useful run notification into a
     *  neutral one is a change a person must choose, never one an update makes for them.
     *
     *  When on, the ongoing run notification's title becomes "Session running" and its body
     *  only the time left ("12:30 left"), regardless of the routine's name, the preset or
     *  the reading, and on the lock screen it reads "Contents hidden" (0.10: incognito's
     *  "Discreet notifications", Incognito#NOTIFICATIONS; it was "Reminder" / "Timer
     *  running" before the owner's incognito decisions) — see Session#runNotificationTitle/
     *  runNotificationText and Incognito#runText for the words, which live there (pure,
     *  desktop-testable) rather than in RunService (which carries `import android` and
     *  cannot be exercised by the self-test). PRESENTATION ONLY, same as privacyBlur:
     *  nothing about what is sent to the pump reads this flag, and neither does anything
     *  about the countdown, the plan or the STOP/HOLD actions themselves. */
    public boolean discreetNotifications = false;

    /* ---- incognito (0.10, Settings › Privacy) ----------------------------------------
     *
     * Every feature has its own switch; the master turns the person's chosen set on and off
     * together (Incognito, which owns the rule). Two of the features predate incognito and
     * keep their own fields and keys: discreetNotifications (above) and secureWindow (below,
     * "hide from recent apps"). ALL OFF BY DEFAULT and on every migration - the real icon and
     * the real words - because each of these changes what a person sees of their own app,
     * which is a choice they make, never one an update makes for them. PRESENTATION ONLY:
     * nothing the pump is sent, and nothing about how a run stops, reads any of them. */

    /** The master switch, "Incognito mode". */
    public boolean incognito = false;
    /** The features the master turns on and off (Incognito's bits); 0 = not chosen yet. */
    public int incognitoSet = 0;
    /** The home-screen icon and name are a disguise (a launcher activity-alias each). */
    public boolean disguiseIcon = false;
    /** WHICH disguise (Incognito#DISGUISES): "Fitness log", "Habits" or "Notes". Kept while
     *  the switch is off, so turning it back on brings the one chosen. Saved as a word
     *  ("disguiseAs"); a save without it - every one before the choice existed - is
     *  "Fitness log", the only disguise there was. */
    public int disguiseAs = Incognito.DISGUISE_DEFAULT;
    /** Safety notices read neutral words on the lock screen, the plain ones once unlocked. */
    public boolean neutralSafetyNotices = false;
    /** A double-tap on the top bar leaves to the home screen. */
    public boolean quickHide = false;
    /** What quick hide does during a run (Incognito#QH_*): C, leave and STOP, by default. */
    public int quickHideAction = Incognito.QH_DEFAULT;
    /** Reminders say only "Reminder". */
    public boolean discreetReminders = false;
    /** The whole-app lock, on through incognito (AppLock#isLocked reads it). */
    public boolean incognitoLock = false;
    /** The home-screen widget is taken off the home screen (its provider disabled). */
    public boolean hideWidget = false;

    /* ---- the seal check, as a SETTING rather than a hardcoded step ------------------ */

    /** WHETHER THE SEAL CHECK RUNS BEFORE A ROUTINE. Default FALSE — the deliberate user
     *  decision recorded here, not an accident of migration: a routine run is the path
     *  people take every day, and a fixed 45-second hold in front of it every time was the
     *  most-skipped screen in the app. Manual mode keeps its OWN per-run toggle (it is a
     *  one-off cycle, often at pressures the person has just typed), so this flag is about
     *  routine runs only.
     *
     *  It is a setting, so it is persisted, and it defaults to false on load as well: a
     *  phone upgrading into this gets the same behaviour a fresh install does rather than
     *  inheriting a check it never chose. Turning it on is one switch in Settings ›
     *  Session › Seal check. */
    public boolean sealBeforeRoutine = false;
    /** The pressure the seal check holds while it watches the coast. Default 20 kPa — the
     *  figure the check used when it was hardcoded, so nothing moves for anyone who turns
     *  the check on and changes nothing. Bounded 5 .. min(57, ceiling) by clampAll: the
     *  ceiling binds it exactly as it binds every other commanded pressure, and the printed
     *  range in Settings is that same expression. */
    public int sealCheckKpa = 20;
    /** How long the hold is COMMANDED for, in seconds — the wire value, not the length of
     *  the measurement (the coast window starts at the plateau and is a constant in
     *  SessionActivity). Default 45 s, the old hardcoded figure. Bounded 10..120: under
     *  10 s the hold can end before the cuff has even plateaued, and past 120 s the check
     *  is longer than several of the sets it precedes. */
    /** The shortest seal-check hold that can still produce a valid measurement — see
     *  clampAll, which is where the arithmetic behind it is written out. Public because
     *  the settings row has to label the range with the same number it is clamped to. */
    public static final int SEAL_CHECK_HOLD_MIN_S = 40;

    public int sealCheckHoldS = 45;

    /* ---- privacy blur ---------------------------------------------------------------- */

    /**
     * BLUR PHOTOS AND MEASUREMENTS UNTIL TAPPED. Default FALSE, and false on load for a
     * phone that saved its settings before this existed: turning a user's own numbers into
     * dots is a change they must choose, never one a migration makes for them.
     *
     * PRESENTATION ONLY. Nothing downstream of this flag touches a Reading, a Photo, a file
     * or an export — it decides how a value is DRAWN and nothing else. That is the whole
     * contract: a screenshot-safe screen, not a lock. The reveal is per item and lasts only
     * until the screen is re-rendered (the app rebuilds a screen on every tap), so a reveal
     * can never leak into the next screen the way a persisted "unblurred" flag would.
     */
    public boolean privacyBlur = false;

    /* ---- app lock (Task 6, S16+/S17) -------------------------------------------------
     *
     * A DIFFERENT KIND OF PRIVACY CONTROL from the blur above: privacyBlur decides how a
     * value is DRAWN, still fully present the instant the screen re-renders; this decides
     * whether a screen's content is BUILT AT ALL until a biometric/device-PIN challenge
     * has been passed. See AppLock.java for the scope constants and the decision logic
     * (isLocked) these six fields feed, and SessionActivity's own "TASK 6" section for the
     * actual BiometricPrompt/KeyguardManager challenge, which needs an Activity and so
     * cannot live in this pure file.
     *
     * DEFAULT JUDGMENT CALL (documented, per this project's own precedent for a decision
     * the locked mock states only as drawn switch state, not in words — see Task 3/4's
     * reports for the same situation with S7/S8/S9): the S16+ mock draws six rows —
     * "App lock" (master), "Whole app", "Photos & compare", "Measurements & goals",
     * "Session history", "Settings & data export" — with the master's own switch drawn ON
     * and the five below it at off/on/on/off/off. Read as a literal fresh-install default,
     * master-ON would mean a phone that has NEVER opened this app's Settings starts
     * challenging biometric on Photos and Measurements the first time either is opened —
     * a security feature nobody opted into, sprung on a stranger to the app, and on a
     * device that may not even have a fingerprint or PIN set up (see
     * SessionActivity#refuseAppLockWithoutDeviceSecurity's own doc for what that would do
     * to a switch nobody could ever satisfy). Every OTHER opt-in feature in this Settings
     * screen (tone cues, the coaching line, the measurement reminder) defaults OFF for
     * exactly this reason — an upgrade or a fresh install must not spring new behaviour on
     * someone who never asked for it. appLockOn therefore defaults FALSE here, and the
     * five rows below take the mock's exact drawn states as the configuration ALREADY
     * waiting under it — the moment a person turns App lock on, they see precisely the
     * mock's own picture (Photos and Measurements guarded, Whole app/Sessions/Settings
     * not) rather than a second round of choices before the feature does anything.
     */
    public boolean appLockOn = false;

    /**
     * WHERE THE FIRST-RUN SETUP STANDS: 0 not started, 1 in progress, 2 done.
     *
     * Only {@link #seed()} sets 0, so only a brand-new install ever sees the setup. The field
     * itself defaults to 2 and so does a file with no "firstRun" key: every phone that already
     * has a model.json has, by definition, been set up (or set itself up in Settings), and
     * showing it a wizard over its own data would be the one wrong outcome here. 1 is written
     * the moment the setup begins, so closing the app half way resumes it instead of
     * restarting it; see {@link FirstRun}.
     */
    public int firstRun = FirstRun.DONE;
    /** "Whole app" — gates the COLD OPEN only (the very first content this Activity shows
     *  after a fresh launch), never re-checked for the rest of that process's lifetime.
     *  See AppLock#isLocked's own doc for why this scope's re-lock rule differs from the
     *  four below it, and SessionActivity#onCreate's own comment for the RunService bypass
     *  that keeps a live run's STOP reachable even if this is on. Off by default (S16+
     *  mock) — it is the strictest of the six and supersedes the four area rows below it
     *  (their own toggles keep their state but stop mattering) whenever it is on, so it
     *  defaulting on would make three other defaults moot from the first launch. */
    public boolean appLockWholeApp = false;
    /** "Photos & compare" — Progress's PHOTOS tab, a single full-size photo, and the
     *  Compare screen (both doors into it — see openCompare()/OpenThenNowTap). On by
     *  default (S16+ mock): photographs are the most identifying thing this app stores. */
    public boolean appLockPhotos = true;
    /** "Measurements & goals" — Progress's TRENDS tab and the full readings list
     *  (renderMeasHist()). On by default (S16+ mock), the same reasoning as photos: body
     *  measurements are the reason this app asks for a lock at all. */
    public boolean appLockMeasurements = true;
    /** "Session history" — Progress's SESSIONS tab (the filed "Recent" list). Off by
     *  default (S16+ mock) — what pressures were run and for how long is less
     *  identifying than a photograph or a body measurement. */
    public boolean appLockSessions = false;
    /** "Settings & data export" — the Settings screen itself and the Export sheet
     *  (reached from Progress › Export, not from Settings — see showExport()'s own doc for
     *  why the door lives there while the scope name still says "Settings"). Off by
     *  default (S16+ mock). */
    public boolean appLockSettings = false;

    /* ---- screen privacy (Task 3, Stage G) ---------------------------------------------
     *
     * FLAG_SECURE shipped UNCONDITIONALLY from Stage D task 6 (S17 A) onward — every window
     * this Activity ever drew was screenshot/recording-blocked, no toggle, no scoping. A
     * user asked for a way to turn that off (screenshots of a routine's numbers for their
     * own records, screen recording a session for a partner watching remotely — legitimate
     * uses FLAG_SECURE has no exception for). This field is the toggle; see
     * SessionActivity#applySecureWindow for where it is actually applied to the Window,
     * which needs an Activity and so cannot live in this pure file.
     *
     * DEFAULTS FALSE, at the owner's explicit request. It defaulted TRUE for two releases on
     * the reasoning that "keep what already shipped" beats "spring a new behaviour on an
     * upgrading phone" - which is the right rule for a change nobody asked for, and the
     * wrong one for a change the person whose phone it is has asked for by name.
     *
     * WHAT TURNING IT OFF COSTS, stated plainly rather than buried: with FLAG_SECURE off,
     * the app's windows appear in screenshots, in screen recordings, and in the system's own
     * recent-apps thumbnail. That last one is the surprising one - it means a measurement or
     * a session can be visible in the app switcher to anyone who picks the phone up. Nothing
     * about the app lock changes; a locked scope is still locked.
     *
     * THE MIGRATION DEFAULT MOVES WITH IT. Leaving fromJson at true would mean the setting
     * read "off" on a phone that already had the app while the window was still protected -
     * a control that does not control anything, which is worse than either state.
     */
    public boolean secureWindow = false;

    /* ---- the goal ------------------------------------------------------------------- */

    /**
     * THE GOAL, IN CENTIMETRES — null when none is set, which is the migration value and
     * the fresh-install value alike. Nullable rather than 0.0 because a goal of zero is not
     * a goal, and a zero standing in for "unset" would draw a goal line at the bottom of
     * every chart for every user who never set one.
     *
     * The RATE is capped where the goal is SAVED, not here — see {@link #capGoal}. Storing
     * an already-capped number means the chart, the caption and the stepper all read the
     * same value, instead of each re-deriving a cap and drifting apart.
     *
     * THREE GOALS, ONE PER METHOD — the user-approved redesign that replaced a single
     * (value, method, metric) triple and its two pickers. The old shape asked the user to
     * state a number and then, separately, which protocol it was measured in and which
     * metric the chart should open on; three of the four combinations it could express were
     * ones nobody sets, and the one goal it could hold meant the chart drew a goal line on
     * exactly one series and nothing on the others. These are the three targets people
     * actually keep, each pinned to the ONE protocol it is stated in, so no picker is
     * needed to say what a number means and every plotted series can carry its own line:
     *
     *   goalBpsslCm — LENGTH, measured BPSSL (bone-pressed stretched)
     *   goalMsegCm  — GIRTH,  measured MSEG  (erect)
     *   goalMssgCm  — GIRTH,  measured MSSG  (soft; the method formerly labelled MSFL)
     *
     * Null is "no goal of this kind", the migration value and the fresh-install value
     * alike — for the same reason as before: a goal of zero is not a goal, and a zero
     * standing in for "unset" would draw a line across the floor of every chart.
     */
    public Double goalBpsslCm = null;
    public Double goalMsegCm  = null;
    public Double goalMssgCm  = null;

    /**
     * THE GOAL A GIVEN METHOD IS MEASURED AGAINST, or null when that method has none —
     * the ONE place the method→goal mapping is stated, so the Settings card, the chart's
     * per-series goal lines, the pace caption and the cap all read the same table.
     *
     * Pure and total: every other method (STANDARDIZED, BPEL, BPSL, NBPEL, NBPSL, and any
     * unknown int from a newer save) answers null, which every caller already reads as
     * "no goal line for this series". That is deliberate rather than a gap — a target is a
     * number stated in one protocol, and inventing a BPEL goal out of a BPSSL one would be
     * restating a number the user never chose in a measurement they never made.
     */
    public Double goalForMethod(int method) {
        switch (method) {
            case Reading.METHOD_BPSSL: return goalBpsslCm;
            case Reading.METHOD_MSEG:  return goalMsegCm;
            case Reading.METHOD_MSSG:  return goalMssgCm;
            default:                   return null;
        }
    }

    /** Whether `method`'s goal is a LENGTH goal (as opposed to a girth one) — the flag
     *  {@link #capGoal} and {@link Meas#goalBaselineCm} both need, derived from the method
     *  rather than passed around beside it so the two can never disagree. */
    public static boolean goalMethodIsLength(int method) {
        return method == Reading.METHOD_BPSSL;
    }

    /** The three goal-bearing methods, in the order the Settings card lists them. Stated
     *  once so the card, the chart and the re-cap pass iterate the same set. */
    public static final int[] GOAL_METHODS = {
        Reading.METHOD_BPSSL, Reading.METHOD_MSEG, Reading.METHOD_MSSG
    };

    /** The Settings row label for each goal — the same wording the help screen uses. */
    public static String goalLabel(int method) {
        switch (method) {
            case Reading.METHOD_BPSSL: return "BPSSL length goal";
            case Reading.METHOD_MSEG:  return "MSEG erect girth goal";
            case Reading.METHOD_MSSG:  return "MSSG soft girth goal";
            default:                   return "goal";
        }
    }

    /** Puts `method`'s goal — the setter half of {@link #goalForMethod}, so the mapping is
     *  written in exactly two places and both are here. A method with no goal slot is a
     *  no-op rather than a silent write to the wrong one. */
    public void setGoalForMethod(int method, Double cm) {
        switch (method) {
            case Reading.METHOD_BPSSL: goalBpsslCm = cm; break;
            case Reading.METHOD_MSEG:  goalMsegCm  = cm; break;
            case Reading.METHOD_MSSG:  goalMssgCm  = cm; break;
            default: break;
        }
    }

    /** True when at least one of the three goals is set — what the Settings card asks
     *  before offering "Clear all goals". */
    /** A nullable double as the empty string or its digits - the one encoding every
     *  nullable double in this file is written with. */
    private static String nd(Double d) {
        return d == null ? "" : String.valueOf(d.doubleValue());
    }

    public boolean anyGoalSet() {
        return goalBpsslCm != null || goalMsegCm != null || goalMssgCm != null;
    }

    /* ---- the camera's level reference ------------------------------------------------ */

    /**
     * THE INCLINATION REFERENCE PHOTOS ARE TAKEN AT, in {@link Level}'s degrees — null in
     * both axes when the user has never set one, which is the migration value and the
     * fresh-install value alike.
     *
     * NULL IS NOT ZERO here either, even though the DEFAULT target is 0/0 (upright and
     * unturned). The distinction is what the pre-capture screen says out loud: "the
     * default" versus "the angle you set", and whether a Reset is offered at all. Storing a
     * calibrated 0.0 as null would silently turn a deliberate choice back into a default.
     *
     * WHY IT IS PERSISTED. Its whole job is to make the NEXT photo, weeks later, repeat the
     * angle of this one. A reference that lived only for the session would guide one
     * capture and then quietly stop being the thing the chain is anchored to.
     */
    /* ---- F16, the guided start ------------------------------------------------------- */

    /**
     * F16 - START WHEN THE CUFF IS ACTUALLY SEALED, judged from what the person does.
     *
     * GLOBAL, as ruled, and it FULLY REPLACES THE SEAL CHECK: when this is on, a run does
     * not run the seal check, whatever {@link #sealBeforeRoutine} says. The two answer the
     * same question and answer it differently, and offering both at once would mean two
     * gates in front of every run, each with its own verdict.
     *
     * The difference that matters is what each COMMANDS. The seal check drives the pump to
     * a pressure and measures the coast; the guided start commands NOTHING. It watches
     * telemetry while the person pulls by hand, and starts the routine when the pressure
     * has held at or above the target for {@link #GUIDED_START_DWELL_MS}. A gate that
     * commands nothing cannot fail unsafely, which is why it can be the default answer to
     * "is it sealed" for people who do not want a machine-driven check first.
     *
     * MIGRATION: false. Every run before this existed used whatever gate it was set to.
     */
    /** X1 - ON BY DEFAULT since the seal check was withdrawn. It is now the only thing
     *  standing between a cuff that will not seal and a routine that runs anyway, so a fresh
     *  install gets it; an existing install is switched on once by the withdrawal migration.
     *  Turning it off is a decision somebody makes, not a default they inherit. */
    public boolean guidedStart = true;

    /**
     * WHO DOES THE PULLING during the guided start.
     *
     * The feature shipped assuming a HAND PUMP: the screen said "pump by hand until the
     * pressure reaches that number", and its safety story was that nothing was ever
     * commanded. That is a coherent design for a hand bulb and an impossible instruction on
     * an electric pump, which is the only kind this app can talk to at all - so on the
     * hardware it drives, the guided start could not be completed by following it.
     *
     * TRUE (the default) means the app commands the pull itself, exactly as the seal check
     * already does, and the dwell gate is unchanged: the routine still starts only once
     * telemetry has SEEN the cuff hold the target, never because a command was sent. That
     * gate was always the point; who produced the pressure never was.
     *
     * FALSE keeps the original behaviour for a hand bulb.
     *
     * MIGRATION: TRUE, and this is the one place in this file where a default is not the
     * literal truth about old data. Old installs behaved as FALSE - but FALSE is the
     * behaviour that could not be carried out on this app's own hardware, so restoring it
     * silently would preserve a broken screen rather than a user's choice. Anyone who does
     * pump by hand can say so in Settings, once, and it persists.
     */
    public boolean guidedStartAssist = true;

    /**
     * F12 - WHAT A SET'S DURATION IS A DURATION OF.
     *
     * MODE A (false, and what the app has always done): the clock. A two-minute set runs
     * for two minutes of wall time, whatever the pressure did in them.
     *
     * MODE B (true): time under pressure. The countdown only advances while telemetry says
     * the cuff is at or above the commanded target - a slow pull, a leak or a reseat
     * halfway through do not eat the set. What was asked for was two minutes AT PRESSURE,
     * and mode A quietly delivered less than that whenever anything went wrong.
     *
     * C6 - "AT PRESSURE" IS THE NET'S OWN TEST where there is a net: in a run the plan
     * scores, a set the net counts is timed from the line net is counted from (the level's
     * floor less {@link #tupCountPct}), not the 5 % control band under the commanded target,
     * and the set's PLANNED drops move its clock (a drop is never at pressure, and was
     * being owed again as time under pressure). So a set that finishes has delivered its
     * share of the routine's planned time under pressure - the figure the summary compares
     * against - rather than about half of it. Elsewhere a set keeps its own target. The rule
     * is {@link TupClock}, pinned against the simulated pump by AtPressureTimingTest.
     *
     * D2 - A TIME LIMIT PER SET, where this said "no per-set cap, as ruled". The final safety
     * review found that release-blocking: a pump that coasts under the counting line held a
     * Level 1 set open for hours (840 s of hold time in 2.4 - 7 h), and one that settled
     * below it never finished a set - only the two-hour stop did. So the clock lengthens a
     * set to at most max(planned, min(2 x planned, Plan#SET_ADVISORY_SEC)) of wall time
     * ({@link TupClock.SetLimit}); the set then ends at its deadline and the run screen says
     * it did not get its time at pressure. Still never AS THOUGH it had been delivered: the
     * net counts only what was, and {@link #TUP_STALL_NUDGE_MS} still speaks while it waits.
     *
     * MIGRATION: false. Every session before this ran on the clock, and reading an old
     * session's duration as anything else would rewrite what those runs were.
     */
    /**
     * HOW FAR BELOW THE MANDATED LEVEL A READING STILL COUNTS as time under pressure, as a
     * percentage of that level.
     *
     * Net TUP is the plan's own delivered-volume figure, and it is judged against the
     * track's LEVEL FLOOR - the pressure the guide mandates for the level being trained.
     * A real pump does not sit exactly on a number: it settles a little under, breathes
     * around the target and dips when the tissue gives, so a raw comparison discounts
     * seconds that were, to any honest reading, delivered.
     *
     * IT USED TO BORROW {@link Session#bandKpa}'s 5%, which is the app's one definition of
     * "is the pump where it was told to be". That is a CONTROL question. This is an
     * ACCOUNTING question - does this second count as work - and a person training against
     * a mandated level is entitled to answer it themselves. The two having the same answer
     * was a coincidence, not a design; every screen asking the control question still goes
     * through atOrAbove() unchanged.
     *
     * C6 - THE AT-PRESSURE SET CLOCK ASKS THIS QUESTION, NOT THE CONTROL ONE, in a run the
     * plan scores. What it decides is whether a second was delivered work, and asking the
     * control band instead let a set run its whole time "at pressure" and file half of it as
     * net. So with Set timing at "At pressure only" this figure also decides how long those
     * sets run: a stricter line is a longer set, and Settings says so.
     *
     * LITERAL, with no hidden absolute floor: 0 means "at or above the level, exactly",
     * which is a setting somebody may genuinely want, and a percentage that quietly became
     * half a kPa at low levels would not be the number on screen.
     *
     * MIGRATION: 2.0. This is the second place in this file where a default is not the
     * literal truth about old data - old runs were counted at 5% - and it is stated as such
     * for the same reason: the figure is recomputed only for runs from here on, every past
     * session's netTupSec is already filed and is not touched, and 2% is the tighter, more
     * conservative claim about delivered work. A default that overstates is the one a
     * training metric may not have.
     */
    public double tupCountPct = 2.0;

    /** The smallest and largest counting tolerance the stepper offers. 0 is "exactly at or
     *  above the level"; 20% of a level floor is already several kPa and is as loose as this
     *  figure may honestly get. */
    public static final double TUP_COUNT_PCT_MIN = 0.0, TUP_COUNT_PCT_MAX = 20.0;

    /** The tolerance in kPa for a given mandated level - the one conversion, so no screen
     *  and no metric can apply the percentage differently. */
    public double tupCountTolKpa(double floorKpa) {
        double pct = tupCountPct < TUP_COUNT_PCT_MIN ? TUP_COUNT_PCT_MIN
                   : (tupCountPct > TUP_COUNT_PCT_MAX ? TUP_COUNT_PCT_MAX : tupCountPct);
        return Math.abs(floorKpa) * pct / 100.0;
    }

    /**
     * The actual pressure at which counting starts - what the run chart draws and what the
     * Settings row prints, so the line and the number cannot disagree.
     *
     * TAKES THE LEVEL FLOOR, NOT AN ALREADY-REDUCED ONE. It applies {@link #netFloorKpa}
     * itself; handing it a net floor subtracts the reduction twice. Two display sites did
     * exactly that and printed a threshold a whole reduction below where counting really
     * started.
     *
     * THE TOLERANCE COMES OFF THE NET FLOOR, because that is the pair scoring uses -
     * netGrossTupSec(net, tupCountTolKpa(net)). Taking it off the LEVEL floor instead made
     * the drawn line disagree with the score by reductionKpa * pct/100: a session held just
     * above the line could still count zero seconds. The disagreement was invisible until a
     * reduction was active, which is the only time the two bases differ.
     */
    public double tupCountFloorKpa(double levelFloorKpa) {
        double net = netFloorKpa(levelFloorKpa);
        return net - tupCountTolKpa(net);
    }

    /**
     * Q1 - THE LEVEL FLOOR, MOVED DOWN BY THE CYLINDER.
     *
     * The floor is a stand-in for tissue stress, and a wider cylinder produces the same
     * stress at less pressure - which is exactly why the guidance says to run 2 hg under in
     * one. If the prescription drops and the floor does not, every session in the larger
     * cylinder scores zero net and no gate ever opens: the app would be asking you to follow
     * a rule and then refusing to count the sessions where you followed it.
     *
     * SO THE TWO MOVE TOGETHER, and only together. Nothing here changes what the pump does;
     * it changes the line above which time is counted, by the same amount the prescription
     * moved. Off by default: it moves only while {@link #reductionKpa} is cutting something,
     * which is a taper day or one of the cylinder reductions.
     */
    public double netFloorKpa(double levelFloorKpa) {
        return levelFloorKpa - reductionKpa();
    }

    /** The same line, for a caller that can name the instant rather than take the clock —
     *  the harness and anything scoring a session that was run at a known time. */
    public double netFloorKpa(double levelFloorKpa, long nowMs) {
        return levelFloorKpa - reductionKpa(nowMs);
    }

    /**
     * C6 - THE FLOOR A RUN OF `r` STARTED AT `atMs` IS SCORED AGAINST, in kPa, or NaN when
     * the plan does not score that routine (no Trainer track marker, or a feeder).
     *
     * ONE DERIVATION, three readers. SessionActivity#fileSession scores the net against it,
     * the at-pressure set clock times a set against the line counted from it (TupClock), and
     * the plan's time under pressure is counted from that same line (PlannedTime). It was
     * written out inside fileSession alone, and a clock that asked a different question from
     * the net is how a set that ran its full time at pressure came out at half its net.
     *
     * The level is the one STAMPED ON THE ROUTINE, the track's current one only where the
     * routine predates the stamp (audit A8); the instant is the run's START, so a session
     * crossing midnight on a taper day is scored by the day it began.
     */
    public double scoringFloorKpa(Routine r, long atMs) {
        if (r == null) return Double.NaN;
        TrainerTrackState st = null;
        if (r.trainerTrack == Plan.TRACK_GIRTH_INTERVAL
                || r.trainerTrack == Plan.TRACK_GIRTH_TRADITIONAL)
            st = trainerGirth;
        else if (r.trainerTrack == Plan.TRACK_LENGTH)
            st = trainerLength;
        if (st == null) return Double.NaN;
        int level = r.trainerLevel > 0 ? r.trainerLevel : st.level;
        return netFloorKpa(scaledLevelFloorKpa(r, level), atMs);
    }

    /**
     * THE LEVEL FLOOR ON THE ROUTINE'S OWN SCALE (0.10): the level's floor, moved down by as
     * far as the routine's work sits under the plan's figure (Routine#trainerScaleKpa,
     * Scale#countingShiftKpa) - never up. What every line counted for `r` starts from, so the
     * line the run chart draws and the line the session is scored against are one line.
     */
    public static double scaledLevelFloorKpa(Routine r, int level) {
        double floor = Plan.floorKpa(level);
        return r == null ? floor : floor - Scale.countingShiftKpa(r.trainerScaleKpa);
    }

    /** How far under the prescribed pressure a session run at `nowMs` goes, in kPa. The cuts
     *  are NOT added together: a return day in a larger cylinder is 4 hg under, not 6. */
    public double reductionKpa(long nowMs) {
        return reductionHg(nowMs) * Fmt.KPA_PER_INHG;
    }

    /** The same, in inHg - what a sentence about it prints, without a round trip through kPa
     *  that would turn "2 hg" into "2.0 hg". A taper step in force outranks the cylinder;
     *  once none is, the cylinder's own cut is what applies. */
    public double reductionHg(long nowMs) {
        double hg = Deload.cutHg(this, Summary.dayNumber(nowMs));
        return hg > 0.0 ? hg : cylinderCutHg();
    }

    /**
     * THE CYLINDER'S OWN CUT, in inHg, whatever the taper is doing - 0 when neither cylinder
     * rule applies. {@link #reductionKpa} applies it only on a day no taper step outranks it;
     * a screen that says "full pressure" once the taper is done has to ask this first, or it
     * tells somebody in a larger cylinder the pump is at full pressure while it runs 2 hg under.
     */
    public double cylinderCutHg() {
        // THE LEGACY SELF-REPORTED PAIR, still gated on marksEasily: this is guidance
        // addressed to people who mark, and widening it would slow down people it was
        // never written for.
        if (marksEasily && bigCylinder) return Plan.BIG_CYLINDER_HG;
        // THE DERIVED-AND-ACCEPTED ONE. Not gated, because the user was shown this
        // specific reduction for this specific cylinder and said yes.
        if (oversizeAccepted) return Plan.BIG_CYLINDER_HG;
        return 0.0;
    }

    public double reductionKpa() { return reductionKpa(System.currentTimeMillis()); }

    /** Whether a day pulls slower as well as lower — true exactly while a taper step is in
     *  force, never for the cylinder cuts, which are about the tube and not about coming back. */
    public boolean gentleNow(long nowMs) {
        return Deload.cutHg(this, Summary.dayNumber(nowMs)) > 0.0;
    }

    /**
     * Retires an accepted oversize reduction once the rack stops deriving one — grown into
     * the tube, swapped to a narrower one, or re-measured. Returns true when it changed
     * something, so the caller knows to persist.
     *
     * The ANSWER is stored; the CONDITION is derived. Without this the stored yes would keep
     * applying a reduction the rack no longer justifies, which is the stale-label failure the
     * whole derived-fit design exists to avoid.
     */
    public boolean syncOversizeAcceptance(double girthCm) {
        if (activeCylinderOversize(girthCm)) return false;
        // The condition is gone, so BOTH answers to it are spent: an acceptance must not keep
        // reducing a prescription the rack no longer justifies, and a refusal must not silence
        // a question that now concerns a different cylinder.
        boolean changed = oversizeAccepted || oversizeDeclined;
        oversizeAccepted = false;
        oversizeDeclined = false;
        return changed;
    }

    /**
     * Whether the app should OFFER the oversize reduction: the rack derives it, and the user
     * has neither accepted nor refused it yet. Asking is all this authorises - the answer is
     * {@link #oversizeAccepted}, and nothing is applied without one.
     */
    public boolean oversizeOfferable(double girthCm) {
        if (oversizeAccepted || oversizeDeclined) return false;
        return activeCylinderOversize(girthCm);
    }

    /**
     * Whether the ACCEPTED oversize reduction is the one actually in force - i.e. it is not
     * being outranked by a taper step. The card that states the adjustment reads this, so
     * what is shown and what {@link #reductionKpa} applies cannot drift apart.
     */
    public boolean oversizeReductionInForce() {
        return oversizeReductionInForce(System.currentTimeMillis());
    }

    /** The same question for a named instant - what the harness asks, since a taper step is
     *  a fact about a day. */
    public boolean oversizeReductionInForce(long nowMs) {
        return oversizeAccepted && !gentleNow(nowMs);
    }

    /**
     * A WORKING PRESSURE AS THE READER NEEDS IT WHILE A REDUCTION IS ON: the plan's own figure
     * and the one that will actually be commanded, together - "8.6 -> 6.6 inHg".
     *
     * Before this, screens showed the PLAN's figure and a separate card stated the adjustment,
     * so the biggest number in front of you was not the one the pump would reach and nothing
     * anywhere printed the number that it would. Showing only the reduced figure would fix that
     * and lose the plan's own number, which progression is still tracked against; showing both
     * keeps each legible and neither able to be misread alone.
     *
     * THE SECOND FIGURE IS COMPUTED THE WAY THE MINT COMPUTES IT - the same floor and the same
     * rounding to a whole kPa that {@link Mint#reduce} applies - so the pair on screen is the
     * pressure that will actually be sent, never a display-side approximation of it that could
     * disagree with the wire by a kPa.
     *
     * With no reduction in force it returns the plain single figure, unchanged, so every call
     * site reads the same either way and a user who is not running reduced sees no difference.
     */
    public String commandedPair(double planKpa) {
        double cut = reductionKpa();
        if (cut <= 0.0) return Fmt.p(planKpa);
        int lowered = (int) Math.round(Math.max(Mint.MIN_REDUCED_KPA, planKpa - cut));
        if (lowered >= planKpa) return Fmt.p(planKpa);
        return Fmt.p(planKpa) + " \u2192 " + Fmt.p(lowered);
    }

    /** Whether the CURRENT prescription is a reduced one - the question the routine mint and
     *  the Today card both ask, so neither can disagree with the other about it. */
    public boolean reducedNow() { return reductionKpa() > 0.0; }

    /**
     * WHETHER THE CUFF IS HELD AT THE STANDARDISED MEASUREMENT PRESSURE when a routine ends,
     * instead of being vented to nothing.
     *
     * A routine used to finish by venting fully, and only then offer the after measurement.
     * By the time the offer was found and accepted, the thing being measured had already
     * changed - and if the standardisation hold was on, the pump had to pull all the way
     * back up again, so the cuff went down and up for no reason.
     *
     * ON, the run's last preset is followed straight by a hold at {@link Std#kpa} - the same
     * pressure and the same armStdHold() machinery an after-session measurement would have
     * commanded anyway, with its own link watchdog and its own vent-on-exit rule. The
     * summary opens with the cuff already at the measurement pressure.
     *
     * IT IS BOUNDED, and that is not negotiable. {@link #endHoldMaxSec} vents it whatever
     * else is happening, because the alternative is an unbounded hold on a person waiting
     * for them to remember. Leaving the app vents it too - the hold has never been covered
     * by the run service's background rule (see SessionActivity#onStop) and this does not
     * change that.
     *
     * MIGRATION: false. Holding pressure after a routine has ended is a decision, and no
     * saved model made it.
     */
    /**
     * X1 — THE SEAL CHECK HAS BEEN WITHDRAWN FROM THE NORMAL APP, and this records that the
     * withdrawal has happened on this install.
     *
     * The check itself is not deleted: every screen, the coast measurement and the decay
     * report are intact, and {@link #sealBeforeRoutine} still drives them. What changed is
     * that the only place that flag can now be SET is Developer options.
     *
     * WHY A ONE-TIME MIGRATION AND NOT JUST A HIDDEN FLAG. An install that already had the
     * check turned on would otherwise keep running it before every routine, with no visible
     * setting anywhere to see it or turn it off — a behaviour the user could no longer
     * reach. So the first load after this change turns it off once, and turns the GUIDED
     * START on in the same breath, because that is what the withdrawal was agreed on: the
     * guided start asks the same question the seal check asks — is this cuff actually
     * sealed — and answers it by waiting for the cuff to really reach and hold the target
     * before a routine begins. The gate moves; it does not disappear.
     *
     * Both are ordinary settings afterwards. Somebody who wants no gate at all turns the
     * guided start off, and that is then their own decision rather than a silent default.
     */
    public boolean sealWithdrawn = true;

    /**
     * "PHOTO DURING THE HOLD" IS OFF UNLESS THE PERSON TURNS IT ON (the owner's decision,
     * 2026-09-24), and this records that an existing install has been switched off, once.
     *
     * The switch was stored ON by default for as long as it did nothing, so every install
     * saved before 0.9.0 carries a true nobody chose; once the switch began opening the camera
     * after every served hold, that true would have started doing something unasked. So the
     * first load of a model saved WITHOUT this marker turns {@link Std#photo} off and sets the
     * marker ({@link #fromJson}); every save writes it. The marker is the whole of the rule's
     * memory: a model saved by this build carries it true, so turning the switch back on is
     * the person's own choice from then on, kept through every later save, load, backup and
     * restore. A backup made before 0.9.0 has no marker and is switched off once when it is
     * restored, exactly as the phone it came from would have been.
     *
     * The same shape as {@link #sealWithdrawn} - and read with the same default, "not yet",
     * which is the truth about every file written before it existed.
     */
    public boolean stdPhotoOffDone = true;

    /** The JSON key {@link #stdPhotoOffDone} is saved under. */
    public static final String STD_PHOTO_OFF_KEY = "stdPhotoOff";

    /**
     * WHETHER THE APP HAS ASKED FOR THE NOTIFICATION PERMISSION FOR RUNS AND HOLDS (the
     * release review; NotifyAsk). Asked once, before the first run or hold, and never again
     * from there after an answer - either answer; Settings is where the person asks again.
     *
     * MIGRATION: false. No model saved before this asked, so an existing install without the
     * permission is asked once too, at its next START or hold - one that already has it (from
     * a reminder switch) never is, because the decision asks only while it is missing.
     */
    public boolean notifyAsked = false;

    public boolean endHoldForMeasure = false;

    /**
     * P1 - HOW LONG ANY HOLD MAY LAST BEFORE IT VENTS ITSELF. One limit, both holds.
     *
     * This began as the end-of-run hold's own bound. The STANDARDISATION hold - the older and
     * far commoner one - had none at all: its only protection was the link watchdog, which
     * fires when telemetry stops, not when time passes. And it is the hold somebody spends
     * LONGEST in, because after its dwell completes the cuff stays at pressure while two
     * measurements are typed, a note is added and the camera is opened, which leaves the app
     * entirely. Two holds, two safety models, and the unbounded one was the long one.
     *
     * So the bound moved into {@link SessionActivity#armStdHold}, the single place every hold
     * of either kind is armed - the same reasoning that already puts cancelVentWatch() and
     * the link watchdog there, and the reason a future caller cannot forget it.
     *
     * The JSON key is still "endHoldMaxSec": it is the same number it always was and a saved
     * file should not have to be rewritten for a rename.
     */
    public int holdMaxSec = 300;
    public static final int END_HOLD_MIN_SEC = 60, END_HOLD_MAX_SEC = 900;

    /**
     * (the safety review of the in-run Hold's limit) THE LIMIT IS A WHOLE HALF-MINUTE, held to
     * its range. The run's Hold holds 255 s at the pull and then 1 s a kPa lower; a limit of
     * exactly 255 s put its StopWork on the pump's own step down, so a lost stop could read as
     * a vent. Settings has always stepped in 30 s, so only a hand-edited or restored file could
     * set 255 - and every load and every clampAll now rounds, so no such value survives.
     */
    public static int roundHoldMaxSec(int sec) {
        int s = Math.max(END_HOLD_MIN_SEC, Math.min(END_HOLD_MAX_SEC, sec));
        s = (int) Math.round(s / 30.0) * 30;
        return Math.max(END_HOLD_MIN_SEC, Math.min(END_HOLD_MAX_SEC, s));
    }

    public boolean tupTiming = false;

    /**
     * F15b - WHETHER PER-REPETITION OVERRIDES MAY BE CREATED. Off until the seam test says
     * they can be; it has not yet been run on hardware (release checklist H16).
     *
     * An overridden set reaches the pump as one preset PER REPETITION rather than one
     * preset repeated. Whether the device plays a run of adjacent presets as one continuous
     * set, or breaks between them, is a hardware fact this app cannot settle by reasoning -
     * and if it breaks, a routine would play differently from the one on screen, which is
     * the one failure a routine editor may never have.
     *
     * So the feature ships complete and OFF, with the switch naming what has to be true
     * before it is turned on. That is the honest shape for a feature gated on a test that
     * has not been run: not a stub, not a silent risk, a working thing behind a stated
     * condition.
     *
     * IT DOES NOT GATE PLAYBACK. A set that already HAS overrides plays them whatever this
     * says - see Set#ladder's own note. Otherwise flipping a preference would change the
     * shape of a routine somebody had already built and tested.
     *
     * MIGRATION: false.
     */
    public boolean repOverridesEnabled = false;

    /** How long a set may fail to reach pressure before the run screen says so. Two
     *  minutes: long enough not to fire on a slow pull or a reseat, short enough that a
     *  set that is silently going nowhere does not stay silent. It is a NUDGE, not a
     *  timeout - nothing is ended, skipped or commanded by it. */
    public static final long TUP_STALL_NUDGE_MS = 120000L;

    /** The pressure the guided start waits for, in kPa. 5 inHg is the ruling's own figure -
     *  deep enough that a leaking cuff cannot hold it, shallow enough to reach by hand in
     *  seconds. Stored in kPa like every other setpoint; the screen prints it through
     *  {@link Fmt#p} so it reads in whatever unit the person uses. */
    public static final int GUIDED_START_KPA = 17;

    /** How long the pressure must STAY there. Two seconds: long enough that a pull passing
     *  through the target on its way somewhere else does not trigger a start, short enough
     *  that it does not feel like a second gate. */
    public static final long GUIDED_START_DWELL_MS = 2000L;

    /** How long the app waits before asking what to do. Thirty seconds: past this, either
     *  the seal is not happening or the person walked away, and both deserve a question
     *  rather than a screen that waits forever. */
    public static final long GUIDED_START_WINDOW_MS = 30000L;

    public Double calibPitch = null;
    public Double calibRoll = null;

    /**
     * ANG - ONE REFERENCE PER VIEW AND PER MODE, four in all.
     *
     * The single global pair above was wrong the moment there was more than one kind of
     * photo. POV and side are a quarter turn apart by construction, so a phone held right
     * for one is held wrong for the other, and the shared reference meant calibrating for
     * either silently broke the other. At rest and standardised differ again: a
     * standardised shot is taken with a cylinder in the frame and the phone further back,
     * and the angle that repeats well for one does not repeat well for the other.
     *
     * FOUR SLOTS, each nullable in both axes, and null still means "never set" rather than
     * "set to zero" - the same distinction {@link #calibPitch} was careful about, for the
     * same reason: the pre-capture screen says "the default" or "the angle you set", and
     * offers Reset only for the second.
     *
     * MIGRATION IS THE OLD PAIR ITSELF. An unset slot falls back to {@link #calibPitch} /
     * {@link #calibRoll}, so someone who calibrated once before this existed keeps exactly
     * the behaviour they had until they set a specific one. The old pair is never written
     * again - it is a fallback, not a fifth slot - so it stays a true record of what was
     * calibrated back when there was only one thing to calibrate.
     */
    public Double angPovRestP, angPovRestR, angPovStdP, angPovStdR;
    public Double angSideRestP, angSideRestR, angSideStdP, angSideStdR;

    /** Which of the four slots a (view, mode) pair names. 0 POV/rest, 1 POV/std,
     *  2 side/rest, 3 side/std. Any view that is not SIDE reads as POV: the only other
     *  stored view is the legacy Top, which left the capture flow and can never reach
     *  here, and answering "POV" for an unknown view is the same fallback the label
     *  helper already makes. */
    private static int angSlot(String view, boolean standardised) {
        boolean side = Shot.SIDE.equals(view);
        return (side ? 2 : 0) + (standardised ? 1 : 0);
    }

    /** The pitch reference for one view and mode, or the legacy global one, or null. */
    public Double calibPitchFor(String view, boolean standardised) {
        switch (angSlot(view, standardised)) {
            case 0: return angPovRestP  != null ? angPovRestP  : calibPitch;
            case 1: return angPovStdP   != null ? angPovStdP   : calibPitch;
            case 2: return angSideRestP != null ? angSideRestP : calibPitch;
            default: return angSideStdP != null ? angSideStdP  : calibPitch;
        }
    }

    /** The roll reference for one view and mode. Falls back on the PITCH slot's presence,
     *  not its own: a reference is a PAIR, banked in one action, and reading pitch from
     *  the new slot while roll came from the old one would describe an angle nobody ever
     *  held the phone at. */
    public Double calibRollFor(String view, boolean standardised) {
        switch (angSlot(view, standardised)) {
            case 0: return angPovRestP  != null ? angPovRestR  : calibRoll;
            case 1: return angPovStdP   != null ? angPovStdR   : calibRoll;
            case 2: return angSideRestP != null ? angSideRestR : calibRoll;
            default: return angSideStdP != null ? angSideStdR  : calibRoll;
        }
    }

    /** True when THIS slot has its own reference, as opposed to borrowing the legacy one.
     *  What the pre-capture screen asks before offering Reset - resetting a borrowed
     *  reference would clear a slot that was never set and change nothing. */
    public boolean calibSetFor(String view, boolean standardised) {
        switch (angSlot(view, standardised)) {
            case 0: return angPovRestP != null;
            case 1: return angPovStdP != null;
            case 2: return angSideRestP != null;
            default: return angSideStdP != null;
        }
    }

    /** Bank or clear one slot. Both axes together, always - see calibRollFor. */
    public void setCalibFor(String view, boolean standardised, Double pitch, Double roll) {
        switch (angSlot(view, standardised)) {
            case 0: angPovRestP = pitch;  angPovRestR = roll;  break;
            case 1: angPovStdP = pitch;   angPovStdR = roll;   break;
            case 2: angSideRestP = pitch; angSideRestR = roll; break;
            default: angSideStdP = pitch; angSideStdR = roll;  break;
        }
    }

    /** How a slot is named on screen - "POV, standardised". */
    public static String angSlotLabel(String view, boolean standardised) {
        return Shot.label(Shot.SIDE.equals(view) ? Shot.SIDE : Shot.FRONT)
             + ", " + (standardised ? "standardised" : "at rest");
    }

    /* ---- the per-view align (edit) profile -------------------------------------------- */

    /**
     * A SAVED ALIGN TRANSFORM FOR ONE VIEW — "the framing I always use for POV". Four
     * nullable doubles in {@link Align.T}'s units (zoom is a multiplier on the fit scale,
     * rot is clockwise degrees, pan is frame pixels).
     *
     * NULL IS NOT ZERO, for the reason the level reference states: a profile that has never
     * been saved must not read as "saved, at the identity", or the align stage would show a
     * green match the moment it opened and the user would have been told they had matched a
     * default they never chose. A profile with ANY null component is not a profile —
     * {@link Align#matchesProfile} refuses it outright rather than filling the gap in.
     *
     * Its ONLY job is the green frame on the align stage. Nothing re-applies it to a photo:
     * the user still aligns by hand; the profile just tells them when they have landed where
     * they landed last time.
     */
    public static final class EditProfile {
        public Double zoom, rot, panX, panY;

        public static EditProfile of(double zoom, double rot, double panX, double panY) {
            EditProfile p = new EditProfile();
            p.zoom = Double.valueOf(zoom); p.rot = Double.valueOf(rot);
            p.panX = Double.valueOf(panX); p.panY = Double.valueOf(panY);
            return p;
        }

        /** True only when all four components are present — the one gate every reader uses,
         *  so "half a profile" cannot be treated as a profile anywhere. */
        public boolean complete() {
            return zoom != null && rot != null && panX != null && panY != null;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("z", zoom == null ? "" : String.valueOf(zoom.doubleValue()));
            o.put("r", rot  == null ? "" : String.valueOf(rot.doubleValue()));
            o.put("x", panX == null ? "" : String.valueOf(panX.doubleValue()));
            o.put("y", panY == null ? "" : String.valueOf(panY.doubleValue()));
            return o;
        }

        public static EditProfile fromJson(JSONObject o) {
            if (o == null) return null;
            EditProfile p = new EditProfile();
            p.zoom = parseNullableDouble(o.optString("z", ""));
            p.rot  = parseNullableDouble(o.optString("r", ""));
            p.panX = parseNullableDouble(o.optString("x", ""));
            p.panY = parseNullableDouble(o.optString("y", ""));
            return p;
        }
    }

    /**
     * The saved profile PER VIEW, keyed by the LOWER-CASE view key ("front", "side") —
     * the same key Compare.PHOTO_VIEWS and Gallery use, so one spelling reaches all of them.
     * Empty on a fresh install and on every file written before this existed: the migration
     * value is "no profile for any view", which is the truth.
     */
    public final Map<String, EditProfile> editProfiles =
            new LinkedHashMap<String, EditProfile>();

    /** The saved profile for a view, or null when there is none — the null-safe read every
     *  caller uses instead of touching the map. A stored-but-incomplete profile reads null
     *  too: half a profile is not a profile (EditProfile#complete). */
    public EditProfile editProfile(String view) {
        if (view == null) return null;
        EditProfile p = editProfiles.get(view.toLowerCase(Locale.US));
        return p != null && p.complete() ? p : null;
    }

    /** Saves (or, with null, clears) the profile for a view. */
    public void setEditProfile(String view, EditProfile p) {
        if (view == null) return;
        String k = view.toLowerCase(Locale.US);
        if (p == null) editProfiles.remove(k); else editProfiles.put(k, p);
    }

    /* GONE, in the three-goal round: `goalMethod` and `goalMetricGirth`. Both existed only
     * to say which single (metric, method) the ONE goal belonged to and which chart to open
     * on it. With a goal PER METHOD (goalBpsslCm/goalMsegCm/goalMssgCm) the question has no
     * subject left: every plotted series carries its own goal line through
     * {@link #goalForMethod}, and the chart opens on length because that is where the
     * length goal is, not because a stored flag says so. Their keys are still READ on load
     * — see fromJson — but only to place an old single goal into the new three; nothing
     * writes them again. */

    /**
     * THE TIME HORIZON THE GOAL IS GIVEN, in months — one of {@link #GOAL_HORIZON_CHOICES}.
     * A goal without one is only half a goal: "16 cm" says where, never by when, so nothing
     * can say whether today is ahead or behind. The horizon does two jobs, and they are the
     * same job seen twice: it bounds how large the goal may be ({@link #capGoal}), and it
     * sets the slope the line is drawn at ({@link #goalSlopePerYear}), so the target lands
     * exactly at the horizon's end.
     *
     * 12 on a fresh install and on every save written before this existed — a year is the
     * period the per-year cap was already stated in, so an old goal keeps the exact line it
     * was drawn with.
     */
    public int goalHorizonMonths = 12;

    /**
     * S10+'s FORECAST-SOURCE CHOICE (round6-options.html's amendment — "Goal projection
     * uses: smoothed (default, steadier) or raw (reacts faster)", Settings > Goals) —
     * which number the ACTUAL side of every M1-projection comparison reads off a method's
     * own reading history: the newest cold reading UN-touched (false, "raw") or that same
     * reading run through {@link Meas#smoothed}'s rolling average (true, "smoothed").
     *
     * WHAT THIS DOES NOT TOUCH. The projection LINE itself — its anchor and its slope,
     * {@link #goalLineAt}/{@link #goalProjectionCmAt} — is unaffected, and that is a
     * DESIGN CHOICE, not a mathematical dead end. The anchor a goal line is drawn from
     * ({@link Meas#goalAnchorOfMethod}) is deliberately WINDOW-scoped: SessionActivity's
     * goalInstrumentCard runs it over the period-filtered window Progress has selected
     * (three months by default), the SAME scoping an earlier review round pinned for
     * `g.anchorCm` there — kept apart, on purpose, from the whole-log baseline
     * {@link Meas#goalBaselineCm} draws on for a different figure. A user with more than
     * that window's worth of history genuinely DOES have earlier readings a smoothing
     * pass could reach past the window for; the reason this setting does not reach for
     * them is that doing so would give the anchor a different scope than the window the
     * rest of that chart is drawn against — a second, competing notion of "anchor" S10+
     * never asked for, not a value that cannot be computed. So this setting stays scoped
     * to what it actually names: the ACTUAL reading side of the comparison. What it
     * redirects is the OTHER side — the trend badge's `actualCm` and the M1 card's PACE
     * ACTUAL number — resolved by the caller (SessionActivity) before either figure is
     * computed, never inside goalProjectionCmAt itself, which has no reading of its own
     * to redirect.
     *
     * Defaults true (smoothed) — on a fresh install and on every save written before this
     * existed, matching the mock's own stated default.
     */
    public boolean goalProjectionSmoothed = true;

    /**
     * S10+'s CHART LINE CHOICE (round6-options.html's amendment — the Trends chart's own
     * chip: "smoothed (7-reading rolling average, raw shown as faint dots behind) or raw
     * only") — whether the Trends chart's plotted line (and the S9 volume mini chart under
     * it, sharing this ONE control rather than growing a second toggle nobody asked for:
     * both charts are one screen's two pictures of the same reading history) is drawn as
     * the raw per-reading value (false) or {@link Meas#smoothed}'s 7-reading rolling
     * average (true, the default), with the raw points always still drawn — faint, behind
     * the smoothed line — so smoothing never hides which readings a line is actually built
     * from.
     *
     * Defaults true (smoothed), matching the mock's own selected chip and
     * {@link #goalProjectionSmoothed}'s identical default beside it.
     */
    public boolean chartSmoothed = true;

    public Meas meas = new Meas();
    public Std std = new Std();

    /** The cylinders this person owns, and which one is in use (F18). Empty until they say;
     *  an empty list means "not told yet", which the photo guide reads as "use the generic
     *  outline" rather than guessing a size. */
    public final List<Cylinder> cylinders = new ArrayList<Cylinder>();
    public int activeCylinder = 0;
    /** Monotonic id source for {@link Cylinder#id}. Persisted, and never lowered by a
     *  deletion, so an id is never handed out twice. */
    public int nextCylinderId = 1;

    /** The cylinder in use, or null when none has been described. */
    /**
     * COLLAPSE A MINT THAT WAS WRITTEN THE OLD WAY (F1, migration).
     *
     * Mints used to place the same one-cycle set into a stage N times, so the editor drew N
     * identical rows for one instruction. The mint now writes a single duration-filled set
     * \u2014 but a routine already in the library keeps the shape it was born with, and it will
     * not be re-minted: the trainer compares the PRESCRIPTION's signature, sees no change,
     * and offers to open the existing routine rather than build another. Without this, the
     * only way to get the new shape was to delete the routine and let the suggestion
     * re-offer, which is a strange thing to ask of someone whose routine is not broken.
     *
     * BEHAVIOUR-PRESERVING BY CONSTRUCTION. A fixed set repeats its own hold/drop cycle for
     * its duration, so N adjacent references to a set of one cycle and one reference to a
     * set of N cycles command exactly the same profile \u2014 the second simply re-arms the
     * device fewer times. Only ADJACENT identical references are merged, and only their
     * durations are summed; nothing else about the set changes.
     *
     * TRAINER-MINTED ROUTINES ONLY. A routine someone built themselves may repeat a set
     * deliberately, and those repeats are their structure to keep \u2014 collapsing them would
     * be this method deciding it knows better. A mint's repeats were never a choice.
     *
     * Idempotent: a routine already in the new shape has no adjacent duplicates to merge, so
     * running it again does nothing. Returns how many routines it changed.
     */
    public int collapseMintedStages() {
        int changed = 0;
        for (int ri = 0; ri < routines.size(); ri++) {
            Routine r = routines.get(ri);
            if (r == null || r.trainerTrack == TRAINER_TRACK_NONE) continue;
            boolean touched = false;
            for (int si = 0; si < r.stages.size(); si++) {
                Stage st = r.stages.get(si);
                if (st == null || st.rest || st.setIds.size() < 2) continue;
                for (int i = 0; i + 1 < st.setIds.size(); ) {
                    String a = st.setIds.get(i), b = st.setIds.get(i + 1);
                    Set sa = set(a);
                    if (a == null || !a.equals(b) || sa == null || sa.ramp || sa.rest) { i++; continue; }
                    // One reference absorbs the next one's time, and the duplicate goes.
                    sa.dur = sa.dur + sa.dur;
                    sa.clamp(ceilKpa);
                    st.setIds.remove(i + 1);
                    touched = true;
                }
            }
            if (touched) changed++;
        }
        return changed;
    }

    /**
     * ONE-TIME IN-PLACE CORRECTION OF TRAINER MINTS' ASSESSMENTS (wave 1 §4). Every
     * trainer mint was born with the default Assess — 20 kPa, "both", in the first
     * commanding stage's tube: for a length mint that is 5-7 lb in the traction tube
     * against a 2.5 lb plan, cold. Rulings: cap at the routine's OWN work pressure;
     * length mints check AFTER only (the girth tube is mounted only after the swap);
     * existing mints corrected in place, keeping id, name, history and signatures
     * (mint signatures are computed from the rx, never from the assessment).
     *
     * Applied to trainer mints unconditionally: min() never raises and "after" is a
     * fixed point, so the pass is idempotent, and a mint whose assessment someone has
     * since lowered by hand is never raised back. Hand-built routines
     * (trainerTrack == NONE) are never touched. Returns how many routines changed.
     */
    public int capMintAssessments() {
        int changed = 0;
        for (int i = 0; i < routines.size(); i++) {
            Routine r = routines.get(i);
            if (r == null || r.trainerTrack == TRAINER_TRACK_NONE || r.assess == null)
                continue;
            boolean touched = false;
            int wp = workPeakKpa(r);
            if (wp > 0 && r.assess.kpa > wp) { r.assess.kpa = wp; touched = true; }
            if (r.trainerTrack == Plan.TRACK_LENGTH
                    && !Assess.WHEN_AFTER.equals(r.assess.when)) {
                r.assess.when = Assess.WHEN_AFTER;
                touched = true;
            }
            if (touched) changed++;
        }
        return changed;
    }

    /**
     * ONE-TIME IN-PLACE RESHAPE OF SAVED LENGTH MINTS (wave 3a, owner rulings). The old
     * builder wrote each traction block as per-hold sets interleaved with rest SETS, put
     * a 30 s rest stage between the two blocks, and appended a 10-minute retention hold.
     * The rulings: one set per block with the gap a release to 0 inside the repetition;
     * no inter-block rest; no retention in a length session; saved routines converted in
     * place, keeping id, name and history. Idempotent: an already-reshaped stage holds
     * no rest sets and exactly the chunked shape this writes. Orphaned fromPlan sets no
     * routine still uses are removed. Returns how many routines changed.
     */
    public int reshapeTractionStages() {
        int changed = 0;
        for (int ri = 0; ri < routines.size(); ri++) {
            Routine r = routines.get(ri);
            if (r == null || r.trainerTrack != Plan.TRACK_LENGTH) continue;
            boolean touched = false;
            // (c) the retention pump hold goes.
            for (int si = r.stages.size() - 1; si >= 0; si--) {
                if (r.stages.get(si).retention) { r.stages.remove(si); touched = true; }
            }
            // (b) a rest stage BETWEEN two traction stages goes.
            for (int si = r.stages.size() - 2; si >= 1; si--) {
                Stage st = r.stages.get(si);
                if (st.rest && !st.manual
                        && r.stages.get(si - 1).traction && r.stages.get(si + 1).traction) {
                    r.stages.remove(si);
                    touched = true;
                }
            }
            // (a) each traction stage's [hold, rest, hold, …] collapses to one set per
            // block (chunked at the hour, whole reps per chunk).
            for (int si = 0; si < r.stages.size(); si++) {
                Stage st = r.stages.get(si);
                if (st == null || !st.traction) continue;
                int holds = 0, rests = 0, gapSec = 0;
                Set first = null;
                boolean already = false;
                for (int j = 0; j < st.setIds.size(); j++) {
                    Set s = set(st.setIds.get(j));
                    if (s == null) continue;
                    if (s.rest) { rests++; if (gapSec == 0) gapSec = s.dur; }
                    else {
                        holds++;
                        if (first == null) first = s;
                        if (s.lh > 0 && s.lo == 0) already = true;
                    }
                }
                // Already the new shape, or nothing to collapse.
                if (first == null || (rests == 0 && (holds == 1 || already))) continue;
                if (gapSec == 0)
                    gapSec = st.fatigueBlock ? Mint.TRACTION_FATIGUE_REST_SEC
                                             : Mint.TRACTION_STRAIN_REST_SEC;
                int cycle = Math.max(1, first.uh + gapSec);
                int perSet = Math.max(1, 3600 / cycle);
                String base = st.fatigueBlock ? "Fatigue hold" : "Strain hold";
                st.setIds.clear();
                int made = 0, part = 0;
                while (made < holds) {
                    int take = Math.min(perSet, holds - made);
                    part++;
                    Set ns = Set.fixed(newSetId(),
                        base + (part > 1 ? " " + part : "") + " ×" + take,
                        first.up, 0, first.uh, gapSec, first.sp, take * cycle);
                    ns.fromPlan = true;
                    ns.clamp(ceilKpa);
                    sets.add(ns);
                    st.setIds.add(ns.id);
                    made += take;
                }
                touched = true;
            }
            if (touched) changed++;
        }
        if (changed > 0) {
            // The collapsed stages left their per-hold and rest sets behind: remove every
            // fromPlan set no routine references any more.
            for (int i = sets.size() - 1; i >= 0; i--) {
                Set s = sets.get(i);
                if (s != null && s.fromPlan && usedIn(s.id) == 0) sets.remove(i);
            }
        }
        return changed;
    }

    /**
     * The ACTIVE index, or -1 when there is no rack at all.
     *
     * -1 IS NOT 0. activeCylinder defaults to 0 and is never clamped against an empty list, so
     * a reading taken before the user has described a single cylinder used to be stamped
     * "cylinder 0" - a tube that does not exist. That was inert while nothing resolved it;
     * with {@link #linkReadingCylinders} it became permanent fiction, because the day the
     * first cylinder is added, index 0 starts resolving and every one of those readings is
     * stamped with an identity it was never taken in.
     */
    public int activeCylinderIndex() {
        if (cylinders.isEmpty()) return -1;
        int i = activeCylinder;
        if (i < 0 || i >= cylinders.size()) i = 0;
        return i;
    }

    public Cylinder cylinder() {
        int i = activeCylinderIndex();
        return i < 0 ? null : cylinders.get(i);
    }

    /** The cylinder with this id, or null when it has been deleted. Null is the POINT: a
     *  reference to a tube that is gone must read as gone, never resolve to whichever
     *  cylinder happens to sit at that position now. */
    public Cylinder cylinderById(String id) {
        if (id == null || id.length() == 0) return null;
        for (int i = 0; i < cylinders.size(); i++)
            if (id.equals(cylinders.get(i).id)) return cylinders.get(i);
        return null;
    }

    /** The active cylinder's identity, or "" when there is no rack. Stamped onto a reading
     *  AT WRITE TIME so it never depends on the load-time backfill, which can only be as
     *  correct as the index it reconstructs from. */
    /**
     * WHICH CYLINDER SUITS THIS KIND OF WORK, as an id - "" when nothing in the rack does,
     * or when no girth has been measured to judge against.
     *
     * A length session swaps tubes partway through: the traction blocks want one that PULLS
     * and the expansion coda wants one that fits for girth, so the answer depends on the
     * block and not on which cylinder happens to be active. Derived at read time from the
     * measured girth, never stored - as girth grows a traction tube drifts toward a girth
     * fit, and a stored answer would go on naming a tube that no longer pulls.
     *
     * The ACTIVE cylinder wins any tie: if the one the user says they are using suits the
     * work, that is the one the routine names, rather than the app quietly preferring
     * another tube in the rack.
     */
    /**
     * THE GIRTH EVERY DERIVED CYLINDER AND LOAD LINE IS MEASURED AGAINST - the most recent
     * reading that actually carries one, or 0 when none does.
     *
     * The log is descending by ts, so the first reading that HAS a girth is the newest; one
     * logged with length only is not evidence of girth. 0 means "not measured yet", which
     * every caller renders as a prompt to measure rather than as a fit or a load.
     *
     * Lives here rather than on the Activity because the pure routine builder needs the same
     * number the screens show: two derivations of "the current girth" is exactly the kind of
     * pair that drifts, and this one decides what the pump is told to do.
     */
    /**
     * HOW MANY SETS ONE PRESET IS - which is NOT one, and that was a real defect.
     *
     * RxBuild deliberately packs a work block into ONE Set whose duration is N hold-and-drop
     * cycles ("one set that runs for the whole block, not N references to a one-rep set"),
     * and a fixed set expands to a single preset. So counting presets counts BLOCKS: an L2
     * routine prescribing ten sets has four presets, two of them warm-up.
     *
     * A set is a CYCLE, counted through {@link Manual#cycles} - the same primitive the
     * upcoming list already counts repetitions with, so the two cannot disagree.
     *
     * OUT-OF-NET STAGES ARE NOT SETS: the warm-up ramps in, the fatigue block is separately
     * counted by the plan's own rule, retention closes, traction is length work. None is what
     * a yield target measures, and counting them would put the denominator partly outside the
     * thing being measured.
     *
     * PURE and static so the desktop harness can drive it over a real minted routine - the
     * arithmetic that decides what a card claims and whether a deload rule can fire should
     * not need a cuff to check.
     */
    public static int setsInPreset(Routine r, Preset p) {
        if (p == null || p.rest || p.cyclePart) return 0;
        Stage st = (r != null && p.stageIdx >= 0 && p.stageIdx < r.stages.size())
            ? r.stages.get(p.stageIdx) : null;
        if (st != null && st.outOfNet()) return 0;
        return Manual.cycles(p.uh, p.lh, (int) (p.durMs / 1000));
    }

    /** The whole routine's planned set count, by the same rule. */
    public static int plannedSets(Routine r, List<Preset> plan) {
        int n = 0;
        if (plan == null) return 0;
        for (int i = 0; i < plan.size(); i++) n += setsInPreset(r, plan.get(i));
        return n;
    }

    /**
     * HOW MANY SESSIONS THE CURRENT TRAINING WEEK ALREADY HOLDS - the figure the 1st-and-4th
     * cadence counts against.
     *
     * PURE AND ON THE MODEL because two different processes need it: the Activity, deciding
     * whether to ask for a measurement before a run, and ReminderReceiver, deciding whether
     * the daily alarm has anything to say. The receiver was calling the one-argument due(),
     * which passes -1, and MEAS_WEEK_14 answers -1 with "not due" - so the measurement
     * reminder could NEVER fire for anybody on that cadence while the settings row went on
     * saying it was on. A second count in the receiver would have fixed the symptom and left
     * the two free to disagree; one derivation cannot.
     */
    public int sessionsThisTrainingWeek(long now) {
        long weekStart = TrainerTab.mondayStartMs(now);
        int n = 0;
        for (int i = 0; i < sessLog.all.size(); i++) {
            Sess ss = sessLog.all.get(i);
            if (ss.ts >= weekStart && ss.ts <= now) n++;
        }
        return n;
    }

    /**
     * S13 (a) (the owner's decision, 2026-09-26): THE SAME COUNT, OF THE TRACK BEING STARTED.
     *
     * The 1st-and-4th cadence counted every session of the week, so with two sessions a day
     * the "4th session" landed on day 2's girth session: the length pairs it exists to collect
     * came from the wrong sessions. The guidance asks for length measurements once or twice
     * a week, and the strain is measured at the start of that session's work - the count
     * that places them is the track's own.
     *
     * `track` is the routine's {@link Routine#trainerTrack}. Both girth styles are the girth
     * track; length and the feeder are each their own; a manual cycle belongs to no track.
     * A routine of your own ({@link #TRAINER_TRACK_NONE}) keeps the old all-sessions count -
     * the decision is about the plan's tracks, and nothing else changes.
     */
    public int sessionsThisTrainingWeek(long now, int track) {
        if (track == TRAINER_TRACK_NONE) return sessionsThisTrainingWeek(now);
        long weekStart = TrainerTab.mondayStartMs(now);
        boolean girth = TrainerTab.isGirthTrack(track);
        int n = 0;
        for (int i = 0; i < sessLog.all.size(); i++) {
            Sess ss = sessLog.all.get(i);
            if (ss == null || ss.manual || ss.ts < weekStart || ss.ts > now) continue;
            Routine r = routine(ss.routineId);
            if (r == null) continue;
            boolean same = girth ? TrainerTab.isGirthTrack(r.trainerTrack)
                                 : r.trainerTrack == track;
            if (same) n++;
        }
        return n;
    }

    /**
     * THE STRETCHED LENGTH the strain check measures against - the most recent BPSSL reading,
     * or 0 when none exists.
     *
     * BPSSL specifically, for the same reason girthForTraction takes MSEG only: strain is a
     * percentage, and a percentage between two different protocols is a growth figure wearing
     * a swelling figure's label. The length signals the ladder reads are already stated in
     * BPSSL, so the mid-run check has to be too or the mid-session number and the
     * end-of-session one would be measuring different things.
     */
    public double stretchedLengthNowCm() {
        for (int k = 0; k < measLog.all.size(); k++) {
            Reading r = measLog.all.get(k);
            if (r.len > 0 && r.method == Reading.METHOD_BPSSL) return r.len;
        }
        return 0;
    }

    public double girthForTraction() {
        /* ERECT ONLY, and this is a load-governing input rather than a display one.
         *
         * The area a vacuum acts on is C^2/4pi, so the girth that produces the force is the
         * girth the tissue is AT under load - the erect one. A soft measurement (MSSG) is a
         * real reading of a real thing and roughly a centimetre smaller, and feeding it here
         * does not merely under-report: kpaForLb inverts through that area, so a soft girth
         * makes the app command a HIGHER pressure to reach the same nominal pounds. The
         * figure on the card would be right and the pull would be harder than it says.
         *
         * A reading taken by an unspecified method is not evidence of an erect one, so it is
         * skipped too. 0 means "not measured that way yet", which every caller already
         * renders as a prompt to measure rather than as a fit or a load.
         */
        for (int k = 0; k < measLog.all.size(); k++) {
            Reading r = measLog.all.get(k);
            if (r.gir > 0 && r.method == Reading.METHOD_MSEG) return r.gir;
        }
        return 0;
    }

    /**
     * THE FIT BAND THAT DECIDES WHETHER THIS TRACK PULLS - asked of the tube the session
     * would actually use, which is the best-fitting one in the RACK, not the active one.
     *
     * The engine used to ask `Traction.fit(cylinder().boreCm, girth)` - the ACTIVE cylinder -
     * while the routine builder, the load card, the lane caption and the mint summary all
     * asked cylinderForWork(true, girth), which scans the whole rack. On the ordinary
     * two-tube setup the length tier is built around - a girth tube active, a length tube
     * beside it - those two answers DISAGREE, and the disagreement was total: the app minted
     * and ran a full traction session at the governed load while the ladder's very first
     * rung read "no traction cylinder for your girth: this runs as expansion only" and
     * returned. Every rung below it - the whole load ladder, the strain and fatigue windows,
     * the divergence rule, the month gates - was unreachable, so the load sat at its
     * starting rung forever and no card ever offered a change.
     *
     * One method, so the engine and the builder cannot answer it differently again.
     * (Since 2026-09-30 that one method is #lengthPulls - a cylinder MARKED for length - and
     * the fit band only warns: see #tractionFit.)
     */
    /**
     * WHAT SHAPE THE LENGTH SESSION WOULD BE BUILT IN, as a short stable string.
     *
     * {@link Mint#signature} is computed from the PRESCRIPTION - track, level, sets,
     * pressure, hold, fatigue - and a length prescription does not mention the rack. So the
     * whole traction session could appear, vanish, change tube, change load or change set
     * count without the signature moving a character, and the app would go on believing the
     * saved routine was the current one. The failure is silent and total: the lane says
     * "pulling up to 2.6 lb", the ladder scores strain sets, and the routine the person
     * actually runs is the plain expansion one minted before they ever measured a girth.
     *
     * EMPTY when the track is not pulling, which is what makes this safe to fold into an
     * existing signature: every girth mint, every feeder mint and every length mint on a
     * rack with no cylinder that pulls produces a byte-identical signature to the one before
     * this existed, so nothing re-offers that should not. A rack that DOES pull changes the
     * signature exactly once - and re-offering there is the correct answer, because the
     * saved routine is the wrong shape.
     *
     * IT NAMES THE INPUTS, NOT THE OUTPUT. Rebuilding the routine to compare it would mint a
     * routine on every render; the six things {@link RxBuild#tractionRoutineFromRx} reads
     * that the prescription does not carry are cheap, and two sessions that agree on all six
     * are the same session.
     */
    public String tractionShapeTag(long now) {
        // A cylinder marked for length pulls with no girth logged (owner, 2026-09-30).
        if (!lengthPulls()) return "";
        double gir = girthForTraction();
        String lTube = lengthCylinderId();
        TrainerTrackState st = trainerLength;
        boolean focus = st.inGirthFocus(now);
        /* THE COMMANDED PRESSURE, not the requested load. The command is rounded to a whole
         * kPa and clamped to the ceiling, so two different loads can produce the same pull -
         * and re-offering an identical session because a stored double moved by a hundredth
         * of a pound would be noise. */
        // The pull as commanded: the load with the length offset, within the pull's hard
        // limit (Scale#pullKpa) - with neither, exactly the figure this tag always carried.
        int strainKpa = Scale.pullKpa(this, now);
        return "T" + strainKpa + ":" + Math.max(1, st.strainSets) + ":" + (focus ? 1 : 0)
             + ":" + lTube + ":" + girthCylinderForCoda(gir);
    }

    /**
     * WHAT SHAPE THE PRESCRIPTION WOULD BE BUILT IN, as a short stable string.
     *
     * The same failure {@link #tractionShapeTag} exists to close, in a second place. The Shape
     * screen offers ten preferences and {@link RxBuild#routineFromRx} reads every one of them
     * - the warm-up's length, steps, ramp, prime and ease; the retention hold and its pressure
     * and length; the hold each cycle runs for; how many sets fall between rests. None of them
     * appear in the PRESCRIPTION, so none of them moved {@link Mint#signature}: changing one
     * left the stored signature identical, {@link Mint#alreadyMinted} kept answering "already
     * this prescription", and the routine you already had was opened unchanged. The setting
     * was written, saved and shown back to you, and reached nothing.
     *
     * This is the shape of the fix {@link SessionActivity#remintAfterReductionChange} makes for
     * the reduction, made permanent rather than per-handler: the tag is part of the identity,
     * so a shape change re-mints on its own and every LATER progression is minted in the shape
     * you chose - which is the part a hand-edited routine could never do.
     *
     * EMPTY WHEN NOTHING HAS BEEN MOVED, and that is what makes it safe to fold in. An install
     * sitting on all ten defaults produces a byte-identical signature to the one before this
     * existed, so nothing re-offers that should not. Somebody who HAS moved a stepper sees one
     * re-offer, once - and that re-offer is the correct answer, because the routine they are
     * holding is not the one their settings describe.
     *
     * REST IS DELIBERATELY ABSENT. {@link Mint#signature} excludes restSec by rule - "rest is
     * recovery, not a gate input, and folding it in would re-mint on a cosmetic-only change" -
     * and rxRestSec / rxRestSecTrad are that same number. rxRestSecTrad is also seeded per
     * level by {@link #seedTraditionalRest}, so folding it in would re-mint on a level change
     * that the level itself already accounts for. Only what the routine COMMANDS is here.
     *
     * ONLY WHAT CAN REACH THIS TRACK'S BUILDER IS IN IT, and that is not a refinement - it is
     * the difference between the tag working and the tag lying. {@link RxBuild#routineFromRx}
     * diverts a length track that pulls into {@link RxBuild#tractionRoutineFromRx} ABOVE the
     * girth body, and that shape takes its holds from constants and from persisted strain
     * state, never from rx.holdSec; block spacing is read for the interval track alone, by
     * its own documented rule; and {@link RxBuild#retentionStage} returns null for the feeder.
     * A preference that cannot change a track's routine must not move that track's signature,
     * or the app offers a "new" routine that is byte-for-byte the one already held.
     *
     * ALL THE RELEVANT ONES OR NONE, never the moved ones only: emitting a subset would let
     * two different settings produce one string, and a signature that collides is worse than
     * no signature. Which ones are relevant is decided by the track, not by what was touched.
     */
    public String rxShapeTag(int track, int level) {
        // Doubles as whole hundredths, so a stored value that drifts in the last bit of a
        // float cannot re-offer an identical session - the same reasoning tractionShapeTag
        // applies to a load in pounds.
        /* t10 fix (merge open item 3) - THE OLD WARM-UP STEPPERS NO LONGER REACH A PLAN
         * ROUTINE: the t10 build warms up with P2 or the gentle warm-up (RxBuild#warmupStage)
         * and reads none of them; they shape a manual run's warm-up only (RxBuild#manualWarmUp).
         * So they are stated at their defaults here - moving one re-signed every plan routine,
         * a rewrite and a notice for an identical build. The layout is kept, so every tag reads
         * back the same way (SavedMint#applyShapeTag). A routine saved before t10 is rebuilt
         * the old way (#legacyT10), which did read them, and its tag says them as they were. */
        boolean warmRead = legacyT10;
        int warmMin = warmRead ? rxWarmMin : 4;
        boolean warmRamp = warmRead && rxWarmRamp;
        int warmSteps = warmRead ? rxWarmSteps : 4;
        int prime = warmRead ? (int) Math.round(rxPrimeKpa * 100.0) : 1355;
        int ease  = warmRead ? (int) Math.round(rxEaseHg  * 100.0) : 300;
        int retK  = (int) Math.round(rxRetentionKpa * 100.0);
        boolean moved = false;
        StringBuilder sb = new StringBuilder("S");

        /* THE FEEDER HAS NO WARM-UP AND NO RETENTION HOLD, by the builders' own first lines:
         * warmupStage and retentionStage both return null for it. Eight of the ten steppers
         * cannot reach a feeder routine, so none of them may move a feeder signature. */
        if (track != Plan.TRACK_FEEDER) {
            moved = warmMin != 4;
            sb.append(warmMin);
            if (warmMin > 0) {                 // no warm-up: nothing about its shape matters
                moved = moved || warmRamp || prime != 1355 || ease != 300;
                sb.append(':').append(warmRamp ? 1 : 0)
                  .append(':').append(prime).append(':').append(ease);
                if (warmRamp) {                // steps shape the ramp and only the ramp
                    moved = moved || warmSteps != 4;
                    sb.append(':').append(warmSteps);
                }
            }
            moved = moved || rxRetention;
            sb.append(":R").append(rxRetention ? 1 : 0);
            if (rxRetention) {                 // its pressure and length exist only when it does
                moved = moved || retK != 1350 || rxRetentionMin != 5;
                sb.append(':').append(retK).append(':').append(rxRetentionMin);
            }
        }
        /* "EACH HOLD" IS THE INTERVAL TRACK'S OWN RANGE, FROM L3 UP - Mint#holdSecWanted
         * returns the track's base hold for every other track and for L1/L2 ("no range to
         * honour"), so the stepper reaches nothing there and must move nothing there. */
        if (track == Plan.TRACK_GIRTH_INTERVAL && level >= Plan.L3) {
            moved = moved || rxHoldSec != 0;
            sb.append(":H").append(rxHoldSec);
        }
        /* R11-5 - THE FATIGUE BLOCK'S HOLD, wherever there is a fatigue block (girth, from
         * Level 3). Always stated there, so a signature minted before it existed (10 x 45 s,
         * SavedMint#applyShapeTag rebuilds that) is told apart and the plan rewrites the
         * routine with its notice. */
        if ((track == Plan.TRACK_GIRTH_INTERVAL || track == Plan.TRACK_GIRTH_TRADITIONAL)
                && Plan.fatigueBlockPresent(level)) {
            moved = true;
            sb.append(":F").append(rxFatigueHoldSec);
        }
        // Block spacing, likewise the interval track's own range by its own documented rule.
        if (track == Plan.TRACK_GIRTH_INTERVAL) {
            moved = moved || rxSetsPerBlock != 5;
            sb.append(":B").append(rxSetsPerBlock);
        }
        // Wave 3b: the hybrid style is part of the girth shape.
        if (trainerGirthHybrid && (track == Plan.TRACK_GIRTH_INTERVAL
                                || track == Plan.TRACK_GIRTH_TRADITIONAL)) {
            moved = true;
            sb.append(":HY");
            /* ...AND, WHERE THE HYBRID RUNS (interval from Level 3), THE HOLDS IT RUNS (t10 fix,
             * review B F5): its steps move its own holds, never the prescription the rest of the
             * signature records, so without them a step that changes what runs never reached
             * the saved routine - and the rebuild then read it as an edit. The count run, not
             * the count kept: a kept hold the time cap does not run changes nothing to rewrite
             * (RxBuild#hybridHoldsRun; SavedMint#applyShapeTag reads it back). */
            if (track == Plan.TRACK_GIRTH_INTERVAL && level >= Plan.L3)
                sb.append(RxBuild.hybridHoldsRun(this, track, level));
        }
        // Wave 3b: the track's Program is part of the shape. Empty while default, so
        // no existing signature moves on upgrade.
        Program prog = programFor(track);
        String ptag = prog == null ? "" : prog.tag();
        if (ptag.length() > 0) { moved = true; sb.append(':').append(ptag); }
        /* 0.10 - THE RAMP SETTINGS, wherever the track's work is Ramped (and only there: they
         * reach no other shape). Always stated for a Ramped track, defaults included, so a
         * signature minted before they existed is told apart (SavedMint#applyShapeTag builds it
         * the old way) and the plan rewrites that routine with a notice. */
        String rtag = rampTag(track);
        if (rtag.length() > 0) { moved = true; sb.append(':').append(rtag); }
        // ...and the gentle warm-up, wherever "I mark or bruise easily" builds it.
        String gtag = gentleWarmTag(track);
        if (gtag.length() > 0) { moved = true; sb.append(':').append(gtag); }
        /* t10 - THE BUILD ITSELF CHANGED (the trainer's rules of 1 Oct 2026: the P2 warm-up and
         * its carry, the two-hour trim, 30 s traditional rests, the hybrid under the time cap).
         * Always stated for every track a warm-up is built for, so a signature minted before it
         * is told apart (SavedMint#applyShapeTag builds it the old way, Model#legacyT10) and the
         * plan rewrites that routine with a notice. The feeder's build did not change. */
        if (track != Plan.TRACK_FEEDER) { moved = true; sb.append(':').append(T10_BUILD_TOKEN); }
        return moved ? sb.toString() : "";
    }

    /** The ramp settings as a signature token - "Y80.2.100.1.1" (start %, short climb, step in
     *  hundredths of an inHg, lighter days, climb counted) - or "" where the track's work is not
     *  Ramped. */
    public String rampTag(int track) {
        Program prog = programFor(track);
        if (prog == null || prog.work != Program.WORK_RAMP_IN_SET) return "";
        return "Y" + rampStartPct + "." + rampShortSteps + "."
            + Math.round(rampStepHg * 100.0) + "." + (rampLighterDays ? 1 : 0) + "."
            + (rampCountClimb ? 1 : 0);
    }

    /**
     * Whether this track's warm-up is the gentle one: "I mark or bruise easily" on, and a
     * warm-up being built at all (a Program warm-up that is not "none").
     *
     * t10 fix (review A F2): THE WARM-UP LENGTH IS THE MANUAL RUN'S NOW. A plan routine warms
     * up with P2 or, for somebody who marks, the gentle one (K3), whatever #rxWarmMin says -
     * a length of 0 used to hand a person who marks P2 and its carry instead. Only a routine
     * saved before t10, rebuilt the old way (#legacyT10), still reads it: then a length of 0
     * was no warm-up at all.
     */
    public boolean gentleWarmFor(int track) {
        // t10-K (B3): the gentle week after the month-12 break warms everybody up gently.
        if (!(marksEasily || breakMarks) || track == Plan.TRACK_FEEDER) return false;
        if (legacyT10 && rxWarmMin <= 0) return false;
        Program prog = programFor(track);
        return prog == null || prog.warm != Program.WARM_NONE;
    }

    /** The gentle warm-up as a signature token - "G1355.60.100" (start in hundredths of a kPa,
     *  speed %, step in hundredths of an inHg) - or "" where it is not built. */
    public String gentleWarmTag(int track) {
        if (!gentleWarmFor(track)) return "";
        return "G" + Math.round(gentleWarmStartKpa * 100.0) + "." + gentleWarmSpeedPct + "."
            + Math.round(gentleWarmStepHg * 100.0);
    }

    /**
     * THE WHOLE SHAPE OF A MINT, rack and preferences together - the one string every
     * evaluation hands {@link Mint#signature}, so the engine cannot fold in one tag and
     * forget the other. Empty when neither has anything to declare.
     *
     * The rack decides the shape AND which preferences can reach it, so it is asked once
     * here and the answer is handed to both.
     */
    public String mintShapeTag(int track, int level, long now) {
        String rack = track == Plan.TRACK_LENGTH ? tractionShapeTag(now) : "";
        String shape = rxShapeTag(track, level);
        // THE TAPER IS PART OF THE SHAPE while it is in force, so a day that moves to the next
        // step changes the signature and syncPlanRoutines rewrites the saved routine at the
        // new pressure — without anybody tapping anything. Empty when no taper is armed, so
        // every other user's signature is unchanged.
        String ret = Deload.stepTag(Deload.cutHg(this, Summary.dayNumber(now)));
        /* THE PERSON'S OFFSET IS PART OF THE SHAPE (0.10): a new offset changes the signature,
         * so the plan rewrites its routines like any plan change - notice and Undo - and a
         * saved routine is rebuilt at the offset it was built with (SavedMint). The offset
         * that APPLIES, so a new person's month two brings theirs in the same way. Empty with
         * none, so every signature from before it is unchanged. */
        String off = Scale.offsetTag(Scale.appliedOffsetKpa(this, track,
                                                            TrainerTab.monthIndexNow(this, now)));
        StringBuilder b = new StringBuilder();
        if (rack.length() > 0) b.append(rack);
        if (shape.length() > 0) { if (b.length() > 0) b.append('|'); b.append(shape); }
        if (ret.length() > 0) { if (b.length() > 0) b.append('|'); b.append(ret); }
        if (off.length() > 0) { if (b.length() > 0) b.append('|'); b.append(off); }
        // t10-K (B3): the gentle week's girth figure - empty outside it, so nothing else moves.
        String mb = MonthBreak.tag(this, track, now);
        if (mb.length() > 0) { if (b.length() > 0) b.append('|'); b.append(mb); }
        return b.toString();
    }

    /**
     * HOW THE LENGTH CYLINDER FITS THIS GIRTH - a Traction FIT_* band, for the WARNING only
     * (owner, 2026-09-30). FIT_UNKNOWN with no girth or no length cylinder. Whether the track
     * pulls is {@link #lengthPulls}: a cylinder marked for length pulls whatever this says.
     */
    public int tractionFit(double girthCm) {
        if (girthCm <= 0) return Traction.FIT_UNKNOWN;
        Cylinder c = lengthCylinder();
        return c == null ? Traction.FIT_UNKNOWN : Traction.fit(c.boreCm, girthCm);
    }

    /**
     * THE LENGTH TRACK'S CYLINDER: the active one if it is marked for length, else the first
     * one that is; null when none is. One method, so the builder, the ladder, the cards and
     * the conversion all name the same tube.
     */
    public Cylinder lengthCylinder() {
        Cylinder active = cylinder();
        if (active != null && active.isLength()) return active;
        for (int i = 0; i < cylinders.size(); i++)
            if (cylinders.get(i) != null && cylinders.get(i).isLength()) return cylinders.get(i);
        return null;
    }

    /**
     * THE ROLE MIGRATION'S SECOND HALF (review I2, the controller's ruling 2026-09-30). Before
     * roles existed a tube pulled when it fit the traction band against the logged erect girth,
     * whatever its name: the one Model#cylinderForWork(true, girth) returned - the active one if
     * it pulls, else the first that does. Reading roles from the name alone demoted every
     * existing user who pulls in a "Blue" or a "4.5 tube": the length track went expansion-only
     * and their saved traction routine ran with no load limit (review I1). So that cylinder is
     * marked for length too, in addition to every name that says "length" - only on a file
     * that had no roles (Cylinder#roleFromName), never over a role the person set.
     */
    void roleFromOldPullRule() {
        double gir = girthForTraction();
        if (gir <= 0 || cylinders.isEmpty()) return;
        Cylinder pick = null;
        Cylinder active = cylinder();
        if (active != null && Traction.pulls(Traction.fit(active.boreCm, gir))) pick = active;
        for (int i = 0; pick == null && i < cylinders.size(); i++) {
            Cylinder c = cylinders.get(i);
            if (c != null && Traction.pulls(Traction.fit(c.boreCm, gir))) pick = c;
        }
        if (pick != null && pick.roleFromName) pick.role = Cylinder.ROLE_LENGTH;
    }

    /** Its id, or "" with no length cylinder. */
    public String lengthCylinderId() {
        Cylinder c = lengthCylinder();
        return c == null || c.id == null ? "" : c.id;
    }

    /** Its bore in cm - what every length load and pressure converts at
     *  (Traction#loadLbAtBore) - or 0 with no length cylinder. */
    public double lengthBoreCm() {
        Cylinder c = lengthCylinder();
        return c == null ? 0.0 : c.boreCm;
    }

    /**
     * DOES THE LENGTH TRACK PULL? Whenever a cylinder is marked for length (owner,
     * 2026-09-30): straight away, with no erect girth logged, and whatever the fit check says
     * once one is. The builder (RxBuild#build), the ladder's first rung (Plan.Inputs
     * #lengthTube), the shape tag and every card ask this.
     */
    public boolean lengthPulls() { return lengthCylinder() != null; }

    /**
     * THE FIT WARNING for the length cylinder against the logged erect girth: "" when there is
     * no girth, no length cylinder, or it is a traction fit. It warns and nothing more - the
     * cylinder still pulls, converted at its bore.
     */
    public String lengthFitWarning() {
        return lengthFitWarning(tractionFit(girthForTraction()));
    }

    /** The same words for a band already worked out. */
    public static String lengthFitWarning(int band) {
        if (band == Traction.FIT_TOO_TIGHT)
            return "Your length cylinder is tighter than a traction fit for your logged girth "
                + "(\u201c" + Traction.fitLabel(band) + "\u201d) and may not seal. It still "
                + "pulls, by its bore.";
        if (band == Traction.FIT_GIRTH_IDEAL || band == Traction.FIT_GIRTH_OVERSIZE
                || band == Traction.FIT_TOO_LOOSE)
            return "Your length cylinder is looser than a traction fit for your logged girth "
                + "(\u201c" + Traction.fitLabel(band) + "\u201d). It still pulls, by its bore.";
        return "";
    }

    /**
     * THE CYLINDER GIRTH WORK RUNS IN - a girth routine's, and a length session's expansion
     * coda (review M4, the controller's ruling 2026-09-30): a cylinder marked for girth whenever
     * one is listed - the active one if it is one, else the first that fits the logged girth
     * (#cylinderForWork), else the first marked for girth. "" only when none is marked for
     * girth: the work then runs in the active cylinder, the one fallback. It used to be the
     * active one whatever it was, so the Settings "Used for" row - which edits the active tube
     * - made marking a tube Length the tube girth work ran in.
     */
    public String girthCylinderForCoda(double girthCm) {
        Cylinder active = cylinder();
        if (active != null && !active.isLength()) return active.id;
        String fit = cylinderForWork(false, girthCm);
        if (fit.length() > 0) return fit;
        for (int i = 0; i < cylinders.size(); i++)
            if (cylinders.get(i) != null && !cylinders.get(i).isLength())
                return cylinders.get(i).id;
        return "";
    }

    /**
     * THE CYLINDER FOR A KIND OF WORK. Traction: the length cylinder (#lengthCylinder), marked
     * by the person - the girth is not consulted. Girth work: the one whose bore fits the
     * girth, as it always was.
     */
    public String cylinderForWork(boolean wantsTraction, double girthCm) {
        if (wantsTraction) return lengthCylinderId();
        if (girthCm <= 0 || cylinders.isEmpty()) return "";
        Cylinder active = cylinder();
        if (active != null && suitsWork(active, wantsTraction, girthCm)) return active.id;
        for (int i = 0; i < cylinders.size(); i++) {
            Cylinder c = cylinders.get(i);
            if (suitsWork(c, wantsTraction, girthCm)) return c.id;
        }
        return "";
    }

    private boolean suitsWork(Cylinder c, boolean wantsTraction, double girthCm) {
        if (c == null) return false;
        // Traction never reaches here (#cylinderForWork answers it from the role). And a
        // cylinder MARKED for length is not a girth tube whatever its fit: the coda's "swap
        // to your girth cylinder" must not name the tube the pulls just ran in.
        if (c.isLength()) return false;
        int band = Traction.fit(c.boreCm, girthCm);
        return band == Traction.FIT_GIRTH_IDEAL || band == Traction.FIT_GIRTH_OVERSIZE;
    }

    public String activeCylinderId() {
        Cylinder c = cylinder();
        return c == null || c.id == null ? "" : c.id;
    }

    /**
     * An id no cylinder has EVER been given, from a counter that only ever goes up.
     *
     * Scanning the current rack for a free "cylN" recycles a deleted cylinder's id onto the
     * next one created - and a reading still holding that id then resolves to a tube it was
     * never taken in. That is the very repointing ids were introduced to stop, moved from
     * index-shift to id-reuse, and worse than the index version because an id is treated as
     * authoritative. The counter is persisted; deleting never lowers it.
     */
    private String freshCylinderId() {
        for (;;) {
            String cand = "cyl" + (nextCylinderId++);
            if (cylinderById(cand) == null) return cand;
        }
    }

    /** Mints an id for every cylinder that has none — the migration for a rack saved before
     *  ids existed, and the backstop for any path that adds a cylinder without one. Called
     *  after the whole list is loaded so a minted id cannot collide with one in the file. */
    public void ensureCylinderIds() {
        // Lift the counter above every minted id already present FIRST, so a file whose
        // counter is absent (or was hand-edited backwards) can never mint a duplicate.
        for (int i = 0; i < cylinders.size(); i++) {
            String id = cylinders.get(i).id;
            if (id == null || !id.startsWith("cyl")) continue;
            try {
                int n = Integer.parseInt(id.substring(3));
                if (n >= nextCylinderId) nextCylinderId = n + 1;
            } catch (NumberFormatException e) { /* not a minted id - leave the counter be */ }
        }
        for (int i = 0; i < cylinders.size(); i++) {
            Cylinder c = cylinders.get(i);
            if (c.id == null || c.id.length() == 0) c.id = freshCylinderId();
        }
    }

    /**
     * Backfills each reading's cylinder IDENTITY from the index it was written with.
     *
     * Runs once on load, while the list is still in the order that index was captured
     * against, so it is exact for every rack that has never deleted a cylinder. Where one
     * already has, the association was destroyed before ids existed and cannot be recovered —
     * this stops any further drift rather than pretending to undo it. Readings that never
     * recorded a cylinder (index -1) stay empty: "nobody knows which" is a fact, not a gap
     * to fill.
     */
    /**
     * DROPS THE PER-RUN ROUTINE SNAPSHOT off sessions older than `cutoff`, and returns how
     * many it dropped.
     *
     * The recordings themselves live outside model.json precisely because - Store's own
     * words - "model.json is rewritten whole on essentially every tap; folding a growing
     * per-run log into it would make the biggest file in the app the one written most
     * often", and they are trimmed to ninety days at filing so they cannot grow without
     * bound. The SNAPSHOT is a copy of the routine's shape and it sits INSIDE the Sess, in
     * model.json, and nothing has ever trimmed it: one per run, for ever, in the file
     * rewritten on every tap.
     *
     * It is dropped against the same cutoff as the recording it describes, so a snapshot
     * only ever goes when the recording it belongs to goes with it.
     */
    public int trimAsRunSnapshots(long cutoff) {
        int n = 0;
        for (int i = 0; i < sessLog.all.size(); i++) {
            Sess s = sessLog.all.get(i);
            if (s != null && s.ts < cutoff && s.asRunSnapshot != null) {
                s.asRunSnapshot = null;
                n++;
            }
        }
        return n;
    }

    public void linkReadingCylinders() {
        for (int i = 0; i < measLog.all.size(); i++) {
            Reading r = measLog.all.get(i);
            if (r.cylinderId != null && r.cylinderId.length() > 0) continue;
            if (r.cylinder < 0 || r.cylinder >= cylinders.size()) continue;
            r.cylinderId = cylinders.get(r.cylinder).id;
        }
    }

    /**
     * Whether the ACTIVE cylinder derives as oversize for this girth — the condition the app
     * OFFERS the {@link Plan#BIG_CYLINDER_HG} reduction on.
     *
     * Derived, never stored: as girth grows the same tube stops being oversize, and a stored
     * flag would keep applying a reduction the rack no longer justifies. Deciding is all this
     * does — it never applies anything. {@link #oversizeAccepted} is the user's answer.
     */
    public boolean activeCylinderOversize(double girthCm) {
        Cylinder c = cylinder();
        if (c == null) return false;
        return Traction.oversize(Traction.fit(c.boreCm, girthCm));
    }

    /** THE TRAINING SCHEDULE — which weekdays this user trains, at what hour, and whether
     *  a reminder fires. The streak is counted over THESE days (Summary#of), so this is
     *  not a preference sitting beside the numbers, it is part of how they are derived.
     *  Absent in every save written before it existed: {@link Schedule#fromJson}(null) is
     *  all seven days at 19:00 with reminders off, which reproduces the old
     *  consecutive-calendar-day streak exactly. */
    public Schedule sched = new Schedule();

    /** Milestone ids already SHOWN to the user (Milestones#ids). Persisted so the
     *  one-time card appears once and not on every return to Today. Absent in every old
     *  save — an empty list, which means a user upgrading may see the milestones they
     *  have already earned once. That is the honest failure direction: showing an earned
     *  milestone once is a small surprise, silently marking unearned ones as seen would
     *  hide them forever. */
    public final List<String> seenMilestones = new ArrayList<String>();

    /**
     * "GOAL REACHED" one-time-card ids already SHOWN (S6+, round6-options.html's
     * amendment — "Shown once on the measurement log that crossed the goal, then
     * archived"). The SAME "shown once, stays true" contract {@link #seenMilestones}
     * keeps, keyed differently: an entry here is not a milestone id but {@link
     * #goalReachedKey}'s (method, goal value) identity, because a goal can be RE-SET
     * after being reached — the user raises the target and keeps going — and reaching
     * that new number is a fresh moment worth its own card, not a repeat of the one
     * already shown for the old number. Absent on every save written before this
     * existed: an empty list, the truth for an install that has never crossed a goal.
     */
    public final List<String> goalReachedShown = new ArrayList<String>();

    /** The stable key one "GOAL REACHED" crossing is shown/marked-seen under — see
     *  {@link #goalReachedShown}. `goalCm` is part of the identity, not just `method`:
     *  see that field's own doc for why a re-set goal must get a fresh key. */
    public static String goalReachedKey(int method, double goalCm) {
        return method + ":" + goalCm;
    }

    /**
     * "READY TO PROGRESS?" one-time-card keys already SHOWN (T1 A,
     * dist/round7-options.html — "after 5 clean ≥95% completions, one-tap suggested
     * step-up"). The SAME "shown once, stays true" contract {@link #seenMilestones}/
     * {@link #goalReachedShown} already keep, keyed by {@link #stepUpKey} rather than a
     * bare routine id: a routine's clean streak can BREAK and re-form, and a fresh
     * streak is a fresh moment worth its own offer — the identical reasoning {@link
     * #goalReachedShown}'s own doc gives for keying on (method, goal value) rather than
     * on method alone. Absent on every save written before this existed — an empty
     * list, the truth for an install that has never run one routine five times clean.
     */
    public final List<String> stepUpShown = new ArrayList<String>();

    /** The stable key one "ready to progress" streak is shown/marked-seen under — see
     *  {@link #stepUpShown}. `streakStartTs` ({@code Progression#cleanStreakStartTs}) is
     *  part of the identity, not just `routineId`: the same routine can qualify again
     *  after a break and five fresh clean runs, and that later streak needs its own key
     *  so the card is offered again for it — the same reasoning {@link #goalReachedKey}
     *  already states for a re-set goal. */
    public static String stepUpKey(String routineId, long streakStartTs) {
        return routineId + ":" + streakStartTs;
    }

    /** setupDone's step ids (Stage D task 1 — the "Get set up" checklist card). The first
     *  three are the locked round5-options.html #n1 row set, verbatim: connect / run the
     *  starter routine / log a baseline measurement. SETUP_BATTERY is a fourth, added by
     *  Stage E task 3 (T18, dist/round7-options.html — "guided exemption in setup"): that
     *  task's own Global Constraints direct it to hook battery-optimisation exemption into
     *  THIS card rather than build a second, parallel first-run flow, which is a deliberate
     *  exception to #n1's "no fourth concept" scope note in {@link
     *  SessionActivity#showSetupPopup}'s own doc — that note was about a goal/Templates
     *  picker, a product-scope addition; T18 is a safety nudge with its own separate spec,
     *  not a new front door into the app's other features. */
    public static final String SETUP_CONNECT     = "connect";
    public static final String SETUP_SESSION     = "session";
    public static final String SETUP_MEASUREMENT = "measurement";
    public static final String SETUP_BATTERY     = "battery";
    /**
     * SETUP_CYLINDER is a fifth, added with the cylinder rack (trainer-v3 Phase 1: "onboarding
     * step between measurements and schedule"). It sits where that spec puts it - AFTER the
     * baseline measurement, because the rack's derived lines are all read against a measured
     * girth and mean nothing without one - and, like SETUP_BATTERY before it, hooks into THIS
     * checklist rather than building a second, parallel first-run flow.
     *
     * It is not a new front door into another feature, which is what showSetupPopup's "no
     * fourth concept" scope note refuses: the app cannot honestly compare two readings taken in
     * different cylinders, or say what a pressure does to the tissue, until it knows the tube.
     * That makes it setup, in the same sense the baseline measurement is.
     */
    public static final String SETUP_CYLINDER    = "cylinder";

    /** The seeded Starter Routine's id (S1 B, {@link #seed}) — the "Run the starter
     *  routine" checklist row selects this specific routine before calling confirmStart,
     *  so the tap actually starts the routine the row names rather than whatever else
     *  happens to be selected. Named once here rather than as a literal in both places. */
    public static final String SEED_STARTER_ROUTINE_ID = "r3";

    /** Setup-checklist step ids already LATCHED true — the "Get set up" checklist shows as
     *  a popup on cold start until every step is done (SessionActivity#showSetupPopup; an
     *  inline Today card before Task 3, Stage G — see that method's own doc for why only the
     *  presentation changed). Each id is added the moment its underlying signal first reads
     *  true (the pump has connected, a session exists, a measurement is logged) and is never
     *  removed again, even if the user later disconnects, deletes every session or every
     *  reading — the same "shown once, stays true" contract {@link #seenMilestones} already
     *  keeps, chosen so a WELCOME checklist cannot flicker a step back to unchecked under the
     *  user's feet. Absent in every save written before the checklist existed, which is the
     *  truth about those installs: none of the three steps have been marked done yet. */
    public final List<String> setupDone = new ArrayList<String>();
    /** Whether the user dismissed the "Get set up" popup before every step was done.
     *  Independent of {@link #setupDone} — dismissing hides the popup for good even with
     *  steps outstanding — and is never un-set on its own: a popup the user already
     *  dismissed once must not reappear unasked. Absent (false) on every save before the
     *  checklist existed. */
    public boolean setupDismissed;

    /**
     * RETENTION TRACKING, off unless asked for. A daily total in minutes, per calendar day.
     *
     * OPT-IN because it is a second thing to remember every day, and an app that starts
     * counting something on your behalf has decided for you that it matters. MIGRATION:
     * false and empty, which is the truth about every install that has never seen it.
     */
    /**
     * THE POST-SESSION COCKRING TIMER, minutes - 0 meaning off, which is the default and the
     * migration value.
     *
     * IT LIVES ON THE SUMMARY because the guidance puts the ring on straight after pumping,
     * and the summary screen IS that moment: it is what the app puts in front of somebody
     * the instant the cuff has vented. A card anywhere else would be about a different time.
     *
     * Hard-capped at {@link #COCKRING_MAX_MIN}. A ring is a restriction and a timer that
     * could be set to an hour is a timer that will be.
     */
    public int rxCockringMin;
    public static final int COCKRING_MAX_MIN = 40;

    /** What the timer is seeded with when somebody first turns it on - ten minutes at L1,
     *  twenty above it. Turning it ON is the choice; the starting figure is only a sensible
     *  place to start from, and it is theirs to change. */
    public static int cockringDefaultMin(int level) {
        return level <= Plan.L1 ? 10 : 20;
    }

    /**
     * L4 OPTION B - shears during the rest, rather than a rest that is only a rest.
     *
     * OFF, and only meaningful at L4: it is an intensification of an already long programme
     * and it belongs to people who have run one. It adds a LINE to the rest card and nothing
     * else - no set, no pressure, no change to what the pump does - because the shear is
     * done by hand and the app's part in it is saying when.
     */
    public boolean rxSupersetRests;

    public boolean retentionOn;
    /** Day key (PhotoCalendar#dayKey) to minutes logged that day, newest first. Bounded, so
     *  a log that is never pruned cannot grow without limit in a file that is read whole. */
    public final List<int[]> retentionDays = new ArrayList<int[]>();
    public static final int RETENTION_DAYS_KEPT = 120;

    /** Minutes logged on `dayKey` - 0 when nothing was. */
    public int retentionMinutesOn(long dayKey) {
        for (int i = 0; i < retentionDays.size(); i++)
            if (retentionDays.get(i)[0] == (int) dayKey) return retentionDays.get(i)[1];
        return 0;
    }

    /** Adds minutes to a day, creating its row if needed, and prunes the tail. Additive
     *  rather than absolute: the card offers "+15 min" taps, and somebody who wore it three
     *  times in a day should be able to say so without arithmetic. */
    public void addRetentionMinutes(long dayKey, int minutes) {
        if (minutes == 0) return;
        for (int i = 0; i < retentionDays.size(); i++) {
            if (retentionDays.get(i)[0] != (int) dayKey) continue;
            int v = retentionDays.get(i)[1] + minutes;
            retentionDays.get(i)[1] = v < 0 ? 0 : (v > 1440 ? 1440 : v);
            return;
        }
        if (minutes < 0) return;                      // nothing logged, nothing to take off
        retentionDays.add(0, new int[]{ (int) dayKey, minutes > 1440 ? 1440 : minutes });
        while (retentionDays.size() > RETENTION_DAYS_KEPT)
            retentionDays.remove(retentionDays.size() - 1);
    }

    /** The setup checklist's "every step done" rule, pulled out as a pure static so it is
     *  testable without an Activity (SessionActivity#showSetupPopup/#setupSignals are the
     *  only callers, but the AND itself carries no Android dependency — Task 3, Stage G).
     *  Parameter order — connect, session, measurement, cylinder, battery — matches the
     *  popup's row order and SessionActivity#setupSignals' returned array order. */
    public static boolean setupAllDone(boolean connectDone, boolean sessionDone,
                                        boolean measurementDone, boolean cylinderDone,
                                        boolean batteryDone) {
        return connectDone && sessionDone && measurementDone && cylinderDone && batteryDone;
    }

    /** Which two photos the Progress "then vs now" card pairs — see {@link ThenNowPick}.
     *  Absent in every save written before the chooser existed, and its absence is the old
     *  behaviour exactly: first versus latest. */
    public ThenNowPick thenNow = new ThenNowPick();

    public MeasLog measLog = new MeasLog();
    public SessLog sessLog = new SessLog();

    /** Pumps this user has told the app to remember (T9) — matched by BLE address on
     *  every scan, the strongest signal among them auto-picked over a device never seen
     *  before. Absent in every save written before this existed: an empty list, exactly
     *  the truth for an install that has never named a pump.
     *
     *  FIX ROUND, CORRECTING A FALSE CLAIM this doc comment made in the first round: it used
     *  to say the very next pump found "still prompts once, precisely the behaviour every
     *  install already had" — that is not what a pre-T9 install did. The old code connected
     *  to the first matching scan result with ZERO prompt. An empty list here now reproduces
     *  that exactly: {@link PumpMatch#shouldPromptUnknown} treats an empty known-address set
     *  as its own silent-connect-and-remember case ({@link PumpLink#decide}), so the very
     *  FIRST pump this install ever sees is remembered without ever asking; only the SECOND
     *  distinct device it sees onward gets the naming prompt. */
    public final List<KnownPump> knownPumps = new ArrayList<KnownPump>();

    /** Case/whitespace-insensitive lookup — the one place every scan-time comparison and
     *  every Settings row goes through, so "AA:BB…" and a hand-typed "aa:bb…" can never
     *  register as two different pumps. */
    public KnownPump knownPump(String address) {
        String a = PumpMatch.norm(address);
        for (int i = 0; i < knownPumps.size(); i++)
            if (knownPumps.get(i).address.equals(a)) return knownPumps.get(i);
        return null;
    }

    /** Every remembered address, normalised — what {@link PumpLink#setKnownAddresses}
     *  compares scan results against. A fresh copy on every call: PumpLink never holds a
     *  reference that could go stale against a rename/forget made after it was handed
     *  out. */
    public java.util.Set<String> knownPumpAddresses() {
        java.util.Set<String> out = new java.util.HashSet<String>();
        for (int i = 0; i < knownPumps.size(); i++) out.add(knownPumps.get(i).address);
        return out;
    }

    /**
     * Remember a pump under `name` — the "Remember" choice on the one-time naming prompt,
     * and Settings' Rename. An address already on the list is RENAMED IN PLACE rather than
     * duplicated, so this can never leave two rows for the same pump racing each other in
     * the RSSI comparison. A blank/whitespace-only name is never stored — the row falls
     * back to a generic label instead, the same "never save an Untitled nobody actually
     * typed" rule {@link Routine} rename already follows for an empty field.
     */
    public KnownPump rememberPump(String address, String name) {
        String a = PumpMatch.norm(address);
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) n = "My pump";
        KnownPump p = knownPump(a);
        if (p == null) {
            p = new KnownPump();
            p.address = a;
            knownPumps.add(p);
        }
        p.name = n;
        return p;
    }

    /** Forget a pump — Settings' "Forget". Returns false, touching nothing, when the
     *  address was never remembered. Does not touch the live link: forgetting a pump that
     *  happens to be connected right now does not disconnect it, only stops it being
     *  auto-picked on a FUTURE scan. */
    public boolean forgetPump(String address) {
        String a = PumpMatch.norm(address);
        for (int i = 0; i < knownPumps.size(); i++)
            if (knownPumps.get(i).address.equals(a)) { knownPumps.remove(i); return true; }
        return false;
    }

    /** The last MANUAL-run values (Task 18), persisted as a setting so a manual cycle is
     *  there next time — ephemeral means "not a saved SET", not "forget what you typed". It
     *  lives here rather than in {@link #sets} precisely because it is NOT a library set: it
     *  never appears in Sets, is never referenced by a routine, and "Save as a set" is what
     *  turns it into one. Fixed mode always (manual mode exposes no ramp). */
    public Set manual = Manual.defaults(40);

    /** Ad-hoc sets resolvable by {@link #plan}/{@link #set} that are NOT in the library —
     *  populated only with the manual run's ephemeral set for the duration of that run, so
     *  the ephemeral routine's stage can resolve it and the whole existing plan/duration/peak
     *  machinery works unchanged. Never persisted (absent from {@link #toJson}); a routine run
     *  leaves it empty, so no library screen or routine can ever see what is in it. */
    public final List<Set> adhoc = new ArrayList<Set>();

    public Set set(String id) {
        for (int i = 0; i < sets.size(); i++)
            if (sets.get(i).id.equals(id)) return sets.get(i);
        // The ephemeral manual set, if a manual run is in flight — checked AFTER the library
        // so a real set of the same id (there is none: manual's id is not `s`-prefixed) could
        // never be shadowed.
        for (int i = 0; i < adhoc.size(); i++)
            if (adhoc.get(i).id.equals(id)) return adhoc.get(i);
        return null;
    }

    /** The counter half of {@link #newSetId}. Static and monotonic for the life of the
     *  process, so two ids minted in the same millisecond still differ. ATOMIC because it
     *  is process-wide state: `setIdSeq++` is a read, an add and a write, so two threads
     *  minting ids at once can read the same value and hand back the same id — the exact
     *  collision this counter was added to prevent. getAndIncrement() is one indivisible
     *  step, so every caller gets a number no other caller can also get. */
    private static final java.util.concurrent.atomic.AtomicInteger setIdSeq =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * A set id nothing else is using: "s" + epoch millis + "-" + a monotonic counter.
     *
     * The convention until now was "s" + System.currentTimeMillis() at three separate call
     * sites, which is unique only as long as no two sets are created within the same
     * millisecond. That held while every set was made by a human tapping "New set"; it
     * stops holding the moment the save-what-you-ran card commits a run's blocks in ONE
     * loop, where four sets are minted microseconds apart. Two sets sharing an id is not a
     * cosmetic collision: {@link #set} returns the first match, so the routine that was
     * meant to point at the second block silently plays the first, and deleting one set
     * takes the other's place in every routine with it.
     *
     * The counter alone would repeat across a process restart, and the clock alone repeats
     * within a millisecond; together they only collide if the clock goes BACKWARDS onto a
     * millisecond this process already used — so the result is checked against the library
     * as well, and bumped until it is genuinely free. The check goes through {@link #set},
     * which also sees the ephemeral manual set, so a run in flight cannot be shadowed
     * either.
     */
    public String newSetId() {
        String id;
        do {
            id = "s" + System.currentTimeMillis() + "-" + setIdSeq.getAndIncrement();
        } while (set(id) != null);
        return id;
    }

    /** The routine-side twin of {@link #newSetId}: "r" + epoch millis + "-" + the same
     *  monotonic counter, re-checked against {@link #routines}. Used by the save-what-you-ran
     *  card's "New routine", which mints a routine in the same breath as the sets it holds;
     *  the bare "r" + millis convention the routine editor's Duplicate uses is left alone. */
    public String newRoutineId() {
        String id;
        do {
            id = "r" + System.currentTimeMillis() + "-" + setIdSeq.getAndIncrement();
        } while (routine(id) != null);
        return id;
    }

    public Routine routine(String id) {
        for (int i = 0; i < routines.size(); i++)
            if (routines.get(i).id.equals(id)) return routines.get(i);
        return null;
    }

    /**
     * Imports a share code (S19) into THIS library. Decodes it first — {@link
     * Routine#fromShareCode} does the actual validation and throws {@link JSONException}
     * on anything that is not one of this app's own routine codes — then mints a fresh,
     * LOCALLY unique id for the routine and for every embedded set and rewrites the
     * stage references onto them. {@link #newSetId}/{@link #newRoutineId} are the same
     * collision-checked "an id nothing else is using" makers the SET editor's Duplicate
     * tap already uses for a set id, and the save-what-you-ran card's {@code AsRun#commit}
     * already uses for both — so a pasted routine can never collide with, or silently
     * shadow, anything already in this library. (The ROUTINE editor's own Duplicate mints
     * its id the older bare "r"+millis way instead; that is a separate, narrower
     * convention {@link #newRoutineId}'s own note explains, not one this method follows.)
     *
     * The remap is POSITIONAL, not by id: {@link Routine.Decoded#sets} and each stage's
     * (still sender-side) {@code setIds} were built by {@link Routine#fromShareCode} by
     * walking the same stages in the same order, so re-walking the stages here and
     * consuming one freshly-minted id per {@code setIds} entry lines back up with {@code
     * decoded.sets} in that same flat order — deliberately NOT a Map<oldId, newId>, which
     * would collapse two occurrences that happened to share a sender-side id (a stage may
     * legitimately hold the same set twice in a row — see {@link Preset#pos}) onto a
     * single imported set, silently turning two independent library rows into one.
     *
     * `fromRun` is explicitly cleared on every imported set — the same discipline {@link
     * Set#copy} already documents: an imported set was not produced by a run on THIS
     * device, and the field means "the session timestamp this set was saved out of";
     * carrying a foreign device's timestamp through would be a false provenance line on a
     * library row, not a harmless leftover.
     *
     * Nothing is added to {@link #sets}/{@link #routines} until decoding has fully
     * succeeded, so a malformed code leaves this library completely unchanged rather than
     * half-imported. {@link #clampAll} is run before returning — an import is a WRITE
     * like any other, and the sending device's ceiling may not be this one's (see
     * clampAll's own "a load is a write" rule) — so an imported set or assessment can
     * never sit in the library above what this phone's ceiling actually enforces.
     *
     * Returns the new {@link Routine}, already added to {@link #routines} (and its sets
     * to {@link #sets}). The caller (the Library "Add from code" dialog) is expected to
     * catch {@link JSONException} and show ONE clear message via Ui.snack — never let a
     * pasted string reach the user as a crash.
     */
    public Routine importShareCode(String code) throws JSONException {
        Routine.Decoded d = Routine.fromShareCode(code);
        String[] newSetIds = new String[d.sets.size()];
        for (int i = 0; i < newSetIds.length; i++) newSetIds[i] = newSetId();
        int k = 0;
        for (int i = 0; i < d.routine.stages.size(); i++) {
            Stage st = d.routine.stages.get(i);
            for (int j = 0; j < st.setIds.size(); j++) st.setIds.set(j, newSetIds[k++]);
        }
        for (int i = 0; i < d.sets.size(); i++) {
            Set s = d.sets.get(i);
            s.id = newSetIds[i];
            s.fromRun = 0L;
            // O4 \u2014 THE BOUNDARY. This is the one place a foreign set becomes one of
            // this library's, so it is the one place that fact is recorded. The sender's
            // own value for this field said something about THEIR library, not this one.
            s.imported = true;
        }
        // Stage H — THE ACTUAL BOUNDARY where a foreign routine enters THIS library, so
        // this is the one place the strip is enforced no matter how it got here (round-2
        // ruling: "a foreign mint must never feed another user's gates"). fromShareCode()
        // already leaves trainerTrack/fatigueBlock at their new-object defaults (see its
        // own doc), so this line changes nothing today — it exists so that if either of
        // THOSE defaults is ever loosened, the marker is still stripped exactly here,
        // where it would otherwise start silently feeding this phone's own trainer gates.
        d.routine.trainerTrack = TRAINER_TRACK_NONE;
        d.routine.id = newRoutineId();
        sets.addAll(d.sets);
        routines.add(d.routine);
        clampAll();
        return d.routine;
    }

    /**
     * The routine id of the most recently RUN session (Stage D task 1 — the "Start last
     * routine" app shortcut's target). There is no per-routine "last run" timestamp field
     * on {@link Routine} — {@link Sess#ts} plus {@link Sess#routineId} already say exactly
     * this, and {@link SessLog#file} always inserts newest-first, so a single forward walk
     * of {@link #sessLog}'s list finds it without a second stored fact to keep in sync.
     *
     * Skips MANUAL cycles ({@link Sess#manual}) — a one-off manual run names no routine to
     * restart — and any {@link Sess#routineId} whose routine has since been deleted, so the
     * shortcut never selects a dead id. Null when nothing in the log qualifies (a fresh
     * install, or a history of manual runs only).
     */
    public String lastRunRoutineId() {
        for (int i = 0; i < sessLog.all.size(); i++) {
            Sess s = sessLog.all.get(i);
            if (s.manual) continue;
            if (s.routineId == null || s.routineId.length() == 0) continue;
            if (routine(s.routineId) != null) return s.routineId;
        }
        return null;
    }

    /** How many routines reference this set id. Routines hold ids, not copies, so this
     *  is always current — no separate bookkeeping to keep in sync. Mirrors the
     *  prototype's usedIn(). */
    public int usedIn(String setId) {
        int n = 0;
        for (int i = 0; i < routines.size(); i++) {
            Routine r = routines.get(i);
            boolean hit = false;
            for (int j = 0; j < r.stages.size(); j++)
                if (r.stages.get(j).setIds.contains(setId)) hit = true;
            if (hit) n++;
        }
        return n;
    }

    /* ================================================ §6 "Used in" — occurrences ==== */

    /**
     * One OCCURRENCE of a set inside a routine — a routine holds ids, not copies, and the
     * same id can sit twice in one stage and again in another, so "used in" is a list of
     * places, not a yes/no per routine. {@link #usedIn} keeps counting ROUTINES (that is
     * what the library row's "in N" has always meant); this is the finer answer the
     * Used-in screen lists row by row.
     *
     * `position` is 1-BASED and `of` is the total number of sets in that stage — they are
     * the "position i of n" the screen prints, so no caller re-derives either and gets an
     * off-by-one between the words and the removal they command.
     */
    public static final class Usage {
        public String routineId;
        public String routineName;
        public int stageIdx;
        public String stageName;
        /** 1-based position within the stage's set list — what the UI prints as "i". */
        public int position;
        /** How many sets that stage holds in total — the "of n". */
        public int of;
    }

    /**
     * Every occurrence of `setId`, one entry per occurrence, ordered exactly as the
     * routines play: routines in model order, stages in routine order, positions in stage
     * order. A set appearing twice in one stage yields two entries with the same stageIdx
     * and different positions.
     */
    public List<Usage> usageOf(String setId) {
        List<Usage> out = new ArrayList<Usage>();
        for (int i = 0; i < routines.size(); i++) {
            Routine r = routines.get(i);
            for (int j = 0; j < r.stages.size(); j++) {
                Stage st = r.stages.get(j);
                for (int k = 0; k < st.setIds.size(); k++) {
                    if (!st.setIds.get(k).equals(setId)) continue;
                    Usage u = new Usage();
                    u.routineId = r.id;
                    u.routineName = r.name;
                    u.stageIdx = j;
                    u.stageName = st.name;
                    u.position = k + 1;
                    u.of = st.setIds.size();
                    out.add(u);
                }
            }
        }
        return out;
    }

    /** Appends `setId` at the END of the named stage — the Used-in screen's "add to a
     *  stage". Duplicates are allowed on purpose (a stage may repeat a set); what is NOT
     *  allowed is a dangling id, so a set the library does not hold is refused. Returns
     *  false, touching nothing, when the routine, the stage or the set is missing. */
    public boolean addToStage(String routineId, int stageIdx, String setId) {
        Routine r = routine(routineId);
        if (r == null || stageIdx < 0 || stageIdx >= r.stages.size()) return false;
        if (set(setId) == null) return false;
        r.stages.get(stageIdx).setIds.add(setId);
        return true;
    }

    /**
     * Removes ONE occurrence — the 1-based `position` within the stage, the same number
     * {@link Usage#position} carries, so a Usage row can be handed straight back here.
     *
     * Returns  0 — removed, the stage still has content;
     *          1 — removed AND the stage is now EMPTY. This method never deletes the
     *              stage itself: an empty stage must not be left behind silently, so the
     *              CALLER decides — remove the stage too ({@link #removeStage}), or put a
     *              replacement in ({@link #addToStage}) — and the UI asks the person
     *              which, before or after calling this;
     *         -1 — not found (bad routine/stage/position): nothing was touched.
     */
    public int removeOccurrence(String routineId, int stageIdx, int position) {
        Routine r = routine(routineId);
        if (r == null || stageIdx < 0 || stageIdx >= r.stages.size()) return -1;
        Stage st = r.stages.get(stageIdx);
        if (position < 1 || position > st.setIds.size()) return -1;
        st.setIds.remove(position - 1);
        return st.setIds.isEmpty() ? 1 : 0;
    }

    /** Removes a whole stage — the "Remove set and stage" answer to the only-set sheet,
     *  and the only sanctioned way to clean up after removeOccurrence returned 1. The
     *  sets it referenced stay in the library untouched (ids, not copies — the same rule
     *  the stage editor's ✕ follows). Returns false, touching nothing, when the routine
     *  or the stage index is not there. */
    public boolean removeStage(String routineId, int stageIdx) {
        Routine r = routine(routineId);
        if (r == null || stageIdx < 0 || stageIdx >= r.stages.size()) return false;
        r.stages.remove(stageIdx);
        return true;
    }

    /**
     * REORDER A STAGE inside its routine — the routine editor's ▲▼. `dir` is a DIRECTION,
     * not an offset: anything negative means "one place earlier", anything positive means
     * "one place later", so a caller cannot smuggle a jump of three past the bounds check
     * by passing 3.
     *
     * Returns false, touching NOTHING, when the routine is missing, when `idx` is not a
     * stage, when `dir` is 0, or when the move would land off either end — the last case
     * being the ▲ on the first row and the ▼ on the last. The UI disables those buttons,
     * but a no-op at the edge is stated here as well, because a disabled button is a
     * decision the screen makes and this is a decision the model makes.
     *
     * A swap, not a remove-and-insert: everything else in the list keeps its position,
     * which is what "move this one" means and what SelfTest pins.
     */
    public boolean moveStage(String routineId, int idx, int dir) {
        Routine r = routine(routineId);
        if (r == null || dir == 0) return false;
        if (idx < 0 || idx >= r.stages.size()) return false;
        int to = idx + (dir < 0 ? -1 : 1);
        if (to < 0 || to >= r.stages.size()) return false;
        Stage tmp = r.stages.get(idx);
        r.stages.set(idx, r.stages.get(to));
        r.stages.set(to, tmp);
        return true;
    }

    /**
     * REORDER ONE SET inside a stage — the stage editor's ▲▼, and the same contract as
     * {@link #moveStage} one level down: `pos` is a 0-BASED index into the stage's setIds
     * (NOT {@link Usage#position}, which is 1-based — the two are never handed to each
     * other), `dir` is a direction, the move is a swap, and every refusal mutates nothing.
     *
     * Dangling ids are moved like any other entry: the stage editor skips them when it
     * draws, but the list is what it is, and a reorder must not quietly drop one.
     */
    public boolean moveSetInStage(String routineId, int stageIdx, int pos, int dir) {
        Routine r = routine(routineId);
        if (r == null || dir == 0) return false;
        if (stageIdx < 0 || stageIdx >= r.stages.size()) return false;
        List<String> ids = r.stages.get(stageIdx).setIds;
        if (pos < 0 || pos >= ids.size()) return false;
        int to = pos + (dir < 0 ? -1 : 1);
        if (to < 0 || to >= ids.size()) return false;
        String tmp = ids.get(pos);
        ids.set(pos, ids.get(to));
        ids.set(to, tmp);
        return true;
    }

    /**
     * THE SNAPSHOT-BEFORE-COMMIT HOOK for Routine version history (Task 6 / T10+). Every
     * SessionActivity listener that changes a routine's stage/set SHAPE — adds, removes
     * or reorders a stage; adds, removes or reorders a set inside one; changes a rest
     * stage's duration — calls this with `pre`, a {@link AsRunSnapshot} of the routine
     * taken with {@link AsRunSnapshot#of} BEFORE that edit was applied, immediately
     * AFTER confirming the edit actually happened (a moveStage/moveSetInStage/removeStage
     * call returned true, or an unconditional mutation ran).
     *
     * That ordering — capture early, commit late — is deliberate and is what keeps this
     * correct against {@link #moveStage}/{@link #moveSetInStage}/{@link #removeStage}'s
     * own "every refusal mutates nothing" contract: those three report success only
     * through their return value, by which point the mutation (if any) has already
     * happened, so archiving cannot itself run first and be told afterwards whether to
     * keep what it captured. A version is therefore never cut for a refused, no-op edit
     * (the disabled ▲/▼/✕ buttons already stop most of those from ever reaching a
     * listener; this is the model-level backstop the file's own "everything the model
     * refuses, the model itself must refuse" convention already keeps at every one of
     * those three call sites).
     *
     * `pre` may be null (a defensive caller, or `r` freshly created) — a no-op, exactly
     * as `r` being null is: nothing to archive is not an error here, it is silence.
     *
     * `pre` is read LIVE, right up to the instant this runs — see {@link
     * Routine.Version#snapshot}'s own doc for what that means for a version whose
     * linked Sets were independently edited (no version cut, by design) partway through
     * its own date range: the frozen detail this archives reflects the Set's values as
     * of THIS call, not necessarily what they were for the whole span back to the
     * shape's `since`.
     */
    public void archiveRoutineVersion(Routine r, AsRunSnapshot pre) {
        if (r == null || pre == null) return;
        long now = System.currentTimeMillis();
        Routine.Version v = new Routine.Version();
        v.snapshot = pre;
        // The FIRST version this routine ever archives has no earlier boundary to point
        // at — versionSince is still 0 for every routine saved before this feature, and
        // for one nobody has edited since. `since == until` on that one entry states
        // exactly what is known: this shape existed at least until this instant, and no
        // earlier start is claimed (see versionSince's own doc).
        v.since = r.versionSince == 0 ? now : r.versionSince;
        v.until = now;
        v.runs = r.versionRuns;
        r.history.add(v);
        while (r.history.size() > Routine.MAX_VERSIONS) r.history.remove(0);
        r.versionSince = now;
        r.versionRuns = 0;
    }

    /**
     * {@link #plan}'s twin for a FROZEN shape: the presets a routine would have sent
     * back when `snap` was captured, resolved from the snapshot's own {@link
     * AsRunSnapshot.SetSnap} values rather than from {@link #set} — so a version keeps
     * reading true after the library sets it once pointed at have been edited or
     * deleted. Mirrors `plan()`'s exact walk (stages in order, rest stages through the
     * same {@link Set#restOf}/{@link Set#ladder} machinery, a dangling/unknown position
     * skipped rather than invented) so the two can never quietly disagree about what
     * "the plan" means. Not clamped against today's ceiling, for the same reason {@link
     * AsRunSnapshot.SetSnap} itself is not: these are historical values, not a claim
     * about what this phone would enforce now.
     */
    public static List<Preset> planFromSnapshot(AsRunSnapshot snap) {
        List<Preset> out = new ArrayList<Preset>();
        if (snap == null) return out;
        for (int i = 0; i < snap.stages.size(); i++) {
            AsRunSnapshot.StageSnap st = snap.stages.get(i);
            if (st.rest) {
                List<Preset> rl = Set.restOf("stage-rest:" + i,
                        st.name == null || st.name.length() == 0 ? "Rest" : st.name,
                        st.restSec).ladder();
                for (int k = 0; k < rl.size(); k++) {
                    Preset p = rl.get(k);
                    p.stageIdx = i; p.ordinal = k; p.pos = 0;
                }
                out.addAll(rl);
                continue;
            }
            for (int j = 0; j < st.setIds.size(); j++) {
                AsRunSnapshot.SetSnap ss = st.setAt(j);
                if (ss == null) continue;          // unrecorded/unknown — not invented
                Set s = ss.toSet();
                List<Preset> lad = s.ladder();
                for (int k = 0; k < lad.size(); k++) {
                    Preset p = lad.get(k);
                    p.stageIdx = i; p.setId = s.id; p.ordinal = k; p.pos = j;
                }
                out.addAll(lad);
            }
        }
        return out;
    }

    /**
     * "Restore as copy" — reconstructs the routine as `history.get(idx)` recorded it and
     * hands back an INDEPENDENT new routine, exactly like {@link Routine#copy} makes:
     * the CURRENT routine (`routineId`) is never touched. Mirrors {@link
     * #importShareCode}'s own shape for the same reason that method mints fresh ids
     * rather than reusing the recorded ones — a version's captured set ids are foreign
     * to what the library holds NOW (the very set they once named may since have
     * different values, or be gone) — so every set the snapshot remembers is written out
     * as a brand-new library row under a freshly-minted id, and the reconstructed stages
     * are rewired onto those, before {@link Routine#copy} deep-copies the result under
     * its own new routine id.
     *
     * Returns null (mutating nothing) when the routine, the index or the version's own
     * snapshot cannot resolve — the same "never half-restore" discipline {@link
     * #importShareCode} keeps for a malformed share code.
     */
    public Routine restoreRoutineVersion(String routineId, int idx) {
        Routine live = routine(routineId);
        if (live == null || idx < 0 || idx >= live.history.size()) return null;
        AsRunSnapshot snap = live.history.get(idx).snapshot;
        if (snap == null) return null;
        Routine draft = new Routine();
        draft.name = live.name;
        // The snapshot never carried the assessment (shape only), so the restored copy takes
        // the LIVE routine's current assessment rather than a fresh Routine's defaults.
        draft.assess = live.assess.copy();
        for (int i = 0; i < snap.stages.size(); i++) {
            AsRunSnapshot.StageSnap ss = snap.stages.get(i);
            Stage st = new Stage();
            st.name = ss.name == null || ss.name.length() == 0 ? "Stage" : ss.name;
            // Colour was never part of the snapshot (AsRunSnapshot deliberately does not
            // carry it — decoration, not shape) — a restored stage takes the ordinary
            // "born here" default, exactly what a freshly added stage gets.
            st.colour = STAGE_COOL;
            st.rest = ss.rest;
            st.restSec = ss.restSec;
            if (!ss.rest) {
                for (int j = 0; j < ss.setIds.size(); j++) {
                    AsRunSnapshot.SetSnap sv = ss.setAt(j);
                    if (sv == null) continue;      // unrecorded position — nothing to restore
                    String nid = newSetId();
                    Set fresh = sv.toSet();
                    fresh.id = nid;
                    fresh.fromRun = 0L;             // a restore is not a run — see Set#copy
                    sets.add(fresh);
                    st.setIds.add(nid);
                }
            }
            st.clampRest();
            draft.stages.add(st);
        }
        // Routine#copy is reused verbatim for the deep-copy-under-a-new-id machinery it
        // already gives every other duplicate in this app — its own "+ copy" suffix is
        // then overwritten with a name that says which version this came from, so the
        // result reads as a restore rather than an ordinary duplicate.
        Routine restored = draft.copy(newRoutineId());
        restored.name = live.name + " (v" + (idx + 1) + " restored)";
        routines.add(restored);
        clampAll();
        return restored;
    }

    /**
     * Replaces every occurrence of `fromId` with `toId`, across every routine and stage,
     * and returns how many occurrences were acted on.
     *
     * Two documented edges:
     *   - replacing a set WITH ITSELF is a no-op: returns 0, mutates nothing — there is
     *     no "replace" to perform and no undo to offer;
     *   - when `toId` is ALREADY in a stage that holds `fromId`, the fromId occurrence is
     *     REMOVED rather than rewritten — a replace must not duplicate the replacement
     *     within a stage. The removed occurrence still counts in the returned total,
     *     because it was acted on (the caller's toast says "replaced in N", and a row
     *     that vanished because its replacement was already there was handled, not
     *     skipped). A stage can never be emptied this way: removal only happens when
     *     `toId` is present in that same stage.
     *
     * A `toId` the library does not hold is refused (returns 0, nothing mutated) — the
     * same no-dangling-ids rule addToStage enforces.
     */
    public int replaceEverywhere(String fromId, String toId) {
        if (fromId == null || toId == null || fromId.equals(toId)) return 0;
        if (set(toId) == null) return 0;
        int n = 0;
        for (int i = 0; i < routines.size(); i++) {
            Routine r = routines.get(i);
            for (int j = 0; j < r.stages.size(); j++) {
                List<String> ids = r.stages.get(j).setIds;
                for (int k = 0; k < ids.size(); ) {
                    if (!ids.get(k).equals(fromId)) { k++; continue; }
                    if (ids.contains(toId)) {
                        ids.remove(k);          // already there — removing, not duplicating
                    } else {
                        ids.set(k, toId);
                        k++;
                    }
                    n++;
                }
            }
        }
        return n;
    }

    /** Appends an EMPTY stage to the routine and returns its index — the stage picker's
     *  "+ new stage". Colour and fallback name mirror the routine editor's own Add stage
     *  (STAGE_COOL, "Stage N"), so a stage born here looks like a stage born there.
     *  Returns -1, touching nothing, when the routine is missing. */
    public int newStage(String routineId, String name) {
        Routine r = routine(routineId);
        if (r == null) return -1;
        Stage st = new Stage();
        st.name = (name == null || name.trim().isEmpty())
                ? "Stage " + (r.stages.size() + 1) : name;
        st.colour = STAGE_COOL;
        r.stages.add(st);
        return r.stages.size() - 1;
    }

    /** Deletes a set, but refuses (returns false, leaves everything untouched) if any
     *  routine still references it — mirrors the prototype's delSet(). The caller
     *  should call usedIn() first to word the refusal message with the count; this
     *  re-checks internally so the safety holds even if a caller forgets to. */
    public boolean deleteSet(String id) {
        if (usedIn(id) > 0) return false;
        for (int i = 0; i < sets.size(); i++)
            if (sets.get(i).id.equals(id)) { sets.remove(i); return true; }
        return false;
    }

    /**
     * Deletes a routine, and re-points anything that referenced it — mirrors the
     * prototype's delRoutine(). Returns false (touching nothing) if the id is not there.
     *
     * The SETS it used are deliberately untouched: a routine holds ids, not copies, and
     * a set belongs to the library and to every other routine using it — the same rule
     * the stage editor's ✕ follows one level down.
     *
     * `selected` is re-picked HERE rather than by the caller, so no screen can be left
     * pointing at a routine that no longer exists (a deleted item must not leave a
     * phantom). With nothing left it becomes empty, not the dead id and not null:
     * routine("") resolves to nothing, which is exactly the empty state Today draws.
     * Session HISTORY is not rewritten — each filed row snapshotted the routine's name
     * when it ran, and a deleted routine still leaves an honest record.
     */
    /** Wave 4 item 10B: the sets the Quick-run sheet lists as RECENT — most recent
     *  first, capped, only ids that still resolve to a library set are shown. */
    public final List<String> recentQuickSetIds = new ArrayList<String>();
    public static final int RECENT_QUICK_MAX = 5;

    /** Records a set as just-run for the Quick-run sheet: moved to the front,
     *  de-duplicated, capped at {@link #RECENT_QUICK_MAX}. */
    public void noteQuickRun(String setId) {
        if (setId == null || setId.length() == 0 || set(setId) == null) return;
        recentQuickSetIds.remove(setId);
        recentQuickSetIds.add(0, setId);
        while (recentQuickSetIds.size() > RECENT_QUICK_MAX)
            recentQuickSetIds.remove(recentQuickSetIds.size() - 1);
    }

    public boolean deleteRoutine(String id) {
        int found = -1;
        for (int i = 0; i < routines.size(); i++)
            if (routines.get(i).id.equals(id)) { found = i; break; }
        if (found < 0) return false;
        routines.remove(found);
        if (routine(selected) == null)
            selected = routines.isEmpty() ? "" : routines.get(0).id;
        // ITEM 10 (wave 4): the routine's app-made sets go with it — see gcOrphanSets.
        gcOrphanSets();
        return true;
    }

    /**
     * ITEM 10 (wave 4): auto-clean of APP-MADE orphan sets. A fromPlan set no routine
     * references any more is litter — the plan re-mints anything it ever needs — so it
     * is deleted wherever routines are reshaped (routine delete, load, a save that
     * re-points a stage). A USER'S OWN set is NEVER deleted here: unused, it is the
     * "Not in any routine" section of the set browser, theirs to keep or drop.
     */
    public int gcOrphanSets() {
        int n = 0;
        for (int i = sets.size() - 1; i >= 0; i--) {
            Set s = sets.get(i);
            if (s.fromPlan && usedIn(s.id) == 0) { sets.remove(i); n++; }
        }
        return n;
    }

    /** Every preset a routine will send, in play order: stages in order, then the sets
     *  within each stage in order. Dangling ids are skipped. Every returned Preset is
     *  stamped with the stage index it came from (see Preset#stageIdx) — the run
     *  screen's stage rail and Session's live-stage/phase maths are driven from this
     *  exact stamp, so "which stage is running" can never disagree with "what preset
     *  is playing" (they are the same walk). */
    /**
     * THE WORKING CYCLES A ROUTINE RUNS - what Today prints, and the number a person
     * recognises as "how many".
     *
     * NOT PRESETS. A preset is a wire slot: a ramp expands into one per step and a fixed
     * block of ten cycles stays a single slot, so the preset count of a prescribed routine
     * (five) is an artefact of how the warm-up happens to be drawn. NOT SETS either, since
     * the mint writes one set of N cycles rather than N sets of one.
     *
     * A CYCLE IS HOLD PLUS DROP, counted through {@link Manual#cycles} - the same rule the
     * run screen's planned and delivered counts use, so Today, the countdown and the summary
     * cannot quote three different numbers for one routine.
     *
     * THE WARM-UP AND COOL-DOWN ARE EXCLUDED, which is the whole reason this is not simply
     * "every cycle". The Trainer names a prescription as "10 x 2.0min" and means the work;
     * counting the warm-up's four ramp steps as well would have Today say fourteen where the
     * Trainer says ten, about the same routine, one tab apart, with nothing on either
     * screen explaining the gap. A fatigue block is NOT excluded - it is work, it is
     * prescribed as work, and it is not an easing-in.
     *
     * A routine with no work stage at all falls back to every non-rest cycle it has, because
     * for a hand-built routine of one unnamed stage "no work" would be a worse answer than
     * a generous one.
     */
    public int workCycles(Routine r) {
        if (r == null) return 0;
        int work = 0, any = 0;
        List<Preset> pl = plan(r);
        for (int i = 0; i < pl.size(); i++) {
            Preset p = pl.get(i);
            // A STITCH CHUNK IS NOT A CYCLE. See Preset#cyclePart: a hold past the wire's
            // reach becomes several presets of one repetition, and counting them counted a
            // five-minute hold as two cycles.
            if (p == null || p.rest || p.cyclePart) continue;
            int n = Manual.cycles(p.uh, p.lh, (int) (p.durMs / 1000));
            any += n;
            int colour = p.stageIdx >= 0 && p.stageIdx < r.stages.size()
                       ? r.stages.get(p.stageIdx).colour : STAGE_WORK;
            if (colour != STAGE_WARM && colour != STAGE_COOL) work += n;
        }
        return work > 0 ? work : any;
    }

    /**
     * THE SET THAT CARRIES A ROUTINE'S WORK: of the sets in its work stages, the one that
     * runs the most cycles. For a minted routine that is the interval block its name
     * describes ("10x2min @ -5.0 inHg"), not the warm-up's ramp, the retention hold, a
     * single traditional hold or the fatigue block that opens an L3+ session. Today's resume
     * card reads its hold and pressure from here to say what the cycles still owed are.
     *
     * The stages are chosen by the rule {@link #workCycles} uses (warm-up and cool-down are
     * not work), and the fatigue block is left out too: it is work, but it is the finisher
     * run before the work, and its short holds are not what "2 min each" is about. Cycles are
     * counted per SET across every reference to it, so a set listed ten times beats one
     * listed once. Ties go to the first, which is the order the run plays them. Null when
     * the routine has no work set at all.
     */
    public Set mainWorkSet(Routine r) {
        if (r == null) return null;
        java.util.LinkedHashMap<String, Integer> cycles = new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < r.stages.size(); i++) {
            Stage st = r.stages.get(i);
            if (st == null || st.rest || st.fatigueBlock) continue;
            if (st.colour == STAGE_WARM || st.colour == STAGE_COOL || st.colour == STAGE_REST)
                continue;
            for (int k = 0; k < st.setIds.size(); k++) {
                Set s = set(st.setIds.get(k));
                if (s == null || s.rest) continue;
                Integer had = cycles.get(s.id);
                cycles.put(s.id, (had == null ? 0 : had) + Manual.cycles(s.uh, s.lh, s.dur));
            }
        }
        Set best = null;
        int most = 0;
        for (java.util.Map.Entry<String, Integer> e : cycles.entrySet()) {
            if (e.getValue() > most) { most = e.getValue(); best = set(e.getKey()); }
        }
        return best;
    }

    public List<Preset> plan(Routine r) {
        List<Preset> out = new ArrayList<Preset>();
        if (r == null) return out;
        for (int i = 0; i < r.stages.size(); i++) {
            Stage st = r.stages.get(i);
            // A REST STAGE EMITS ONE REST PRESET, through the SAME Set#ladder machinery a
            // rest set has always used — not a second, parallel way of building one. So the
            // preset that comes out is byte-identical to the one a rest set produces
            // (all five wire values zero, Preset#rest set, one plan index), and every
            // consumer downstream — playPreset's rest branch, uploadBatch's placeholder
            // write, the countdown, the stage rail, the as-run recorder — is untouched by
            // the existence of rest STAGES as well.
            //
            // The synthetic set id names the stage it came from and cannot collide with a
            // library id (those are "s"-prefixed): it exists so the as-run row has an
            // identity to carry, exactly as a rest set's does. Nothing resolves it through
            // Model#set — a REST row carries no values and contributes to no block.
            if (st.rest) {
                st.clampRest();
                List<Preset> rl = Set.restOf("stage-rest:" + i,
                        st.name == null ? "Rest" : st.name, st.restSec).ladder();
                for (int k = 0; k < rl.size(); k++) {
                    Preset p = rl.get(k);
                    p.stageIdx = i;
                    p.awaitAck = st.awaitAck;
                    p.manual = st.manual;
                    p.setId = "stage-rest:" + i;
                    p.ordinal = k;
                    p.pos = 0;
                    RunEdit.asBuilt(p);
                }
                out.addAll(rl);
                continue;
            }
            for (int j = 0; j < st.setIds.size(); j++) {
                Set s = set(st.setIds.get(j));
                if (s == null) continue;              // a deleted set is not a phantom step
                s.clamp(ceilKpa);
                // A REST SET EXPANDS LIKE ANY OTHER — one preset, stamped with the same
                // stage/set/position identity. Its preset carries zeros and Preset#rest;
                // nothing here special-cases it, which is the point: the plan's indices,
                // the batch arithmetic and every screen that walks the plan stay exactly
                // as they were, and only the one place that ARMS the pump reads the mark.
                List<Preset> lad = s.ladder();
                // The SAME walk stamps stage, set and position-in-the-ladder — see
                // Preset#setId. A fixed set has one preset, so its ordinal is 0 by
                // construction rather than by a special case.
                for (int k = 0; k < lad.size(); k++) {
                    Preset p = lad.get(k);
                    p.stageIdx = i;
                    p.setId = s.id;
                    p.ordinal = k;
                    p.pos = j;
                    // ...and what it was BUILT as, before any edit can write into it
                    // (Preset#builtHoldOnly: the drop controls lock on this).
                    RunEdit.asBuilt(p);
                }
                out.addAll(lad);
            }
        }
        return out;
    }

    /** Each stage's total commanded time in ms, indexed the same way r.stages is —
     *  summed straight from plan()'s flattened, stage-stamped preset list, never a
     *  second independent walk of the routine that could quietly disagree with what
     *  plan() actually sends (that duplication is exactly defect #25's root cause).
     *  A stage with no resolving sets (all ids dangling, or none added) is 0, so it
     *  can never be mistaken for "live" by Session.liveStageIndex(). */
    public long[] stageDurationsMs(Routine r) {
        long[] out = new long[r == null ? 0 : r.stages.size()];
        List<Preset> p = plan(r);
        for (int i = 0; i < p.size(); i++) {
            int si = p.get(i).stageIdx;
            if (si >= 0 && si < out.length) out[si] += p.get(i).durMs;
        }
        return out;
    }

    public long durationMs(Routine r) {
        long t = 0;
        List<Preset> p = plan(r);
        for (int i = 0; i < p.size(); i++) t += p.get(i).durMs;
        return t;
    }

    /**
     * The highest pressure this routine will command — the prototype's routPeak(), and
     * the figure the START consent dialog, the seal check's "next:" line, Today's
     * Selected card and the routine editor's tile all state.
     *
     * The TISSUE ADAPTATION ASSESSMENT is folded in, because it is a pull this routine
     * commands: its pressure is a per-routine field and it can exceed every set in the
     * plan (a routine holding only "Gentle Warm" peaks at 14 kPa while the default
     * assessment pulls to 20, and the editor permits up to the ceiling). Task 15
     * threaded the assessment through routineSec() and left this on the plan alone, so
     * the one line in the app that says "This RUNS THE PUMP" understated the pressure
     * being consented to. Through Tau#commandedKpa, so this is the same number the pull
     * is actually armed with.
     *
     * The seal check and the standardisation hold are deliberately NOT folded in: they
     * are session-scoped, not properties of a routine: the seal check's pressure is its own
     * setting (default 20 kPa, 5 to min(57, ceiling), clamped to the ceiling when sent),
     * whatever any routine says.
     */
    public int peak(Routine r) {
        int pk = 0;
        List<Preset> p = plan(r);
        for (int i = 0; i < p.size(); i++) if (p.get(i).up > pk) pk = p.get(i).up;
        int a = Tau.commandedKpa(r, ceilKpa, pk);
        if (a > pk) pk = a;
        return pk;
    }

    /* =============================== THE PROGRAM — wave 3b, F1-B's five pickers ======
     *
     * One per enrolled track (girth and length separately, owner ruling Q6; the feeder
     * is fixed and has none). The Program is the named SHAPE of the plan's routines:
     * each picker sets the mint's construction, folds into the mint signature (so a
     * change rewrites the plan's routines at once, with the Today notice and Undo), and
     * defaults to exactly today's behaviour so every existing install migrates as a
     * no-op — a default Program contributes NOTHING to the signature.
     */
    public static final class Program {
        public static final int WARM_NONE = 0, WARM_SHORT = 1, WARM_STANDARD = 2,
                                WARM_RAMP = 3;
        public static final int WORK_FIXED = 0, WORK_RAMP_IN_SET = 1, WORK_ASCENDING = 2,
                                WORK_PYRAMID = 3;
        public static final int PRESS_GENTLE = 0, PRESS_STANDARD = 1, PRESS_FIRM = 2;
        public static final int REST_SHORT = 0, REST_STANDARD = 1, REST_LONG = 2;
        public static final int FAT_STANDARD = 0, FAT_EXTENDED = 1, FAT_OFF = 2;

        public int warm = WARM_STANDARD;
        public int work = WORK_FIXED;
        public int pressure = PRESS_STANDARD;
        public int rest = REST_STANDARD;
        public int fatigue = FAT_STANDARD;

        public boolean isDefault() {
            return warm == WARM_STANDARD && work == WORK_FIXED
                && pressure == PRESS_STANDARD && rest == REST_STANDARD
                && fatigue == FAT_STANDARD;
        }

        /** The signature fragment — "" while default, so existing mints re-sign only
         *  when a picker actually moved (the rxShapeTag rule). */
        public String tag() {
            return isDefault() ? ""
                 : "P" + warm + work + pressure + rest + fatigue;
        }

        /** Does this choice leave the guidance's plan (owner ruling Q5 — allowed, said)? */
        public boolean leavesTheGuidance() {
            return warm == WARM_NONE || fatigue == FAT_OFF;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("warm", warm); o.put("work", work); o.put("press", pressure);
            o.put("rest", rest); o.put("fat", fatigue);
            return o;
        }

        public static Program fromJson(JSONObject o) {
            Program p = new Program();
            if (o == null) return p;   // MIGRATION: absent = today's behaviour
            p.warm = c5(o.optInt("warm", WARM_STANDARD), WARM_RAMP);
            p.work = c5(o.optInt("work", WORK_FIXED), WORK_PYRAMID);
            p.pressure = c5(o.optInt("press", PRESS_STANDARD), PRESS_FIRM);
            p.rest = c5(o.optInt("rest", REST_STANDARD), REST_LONG);
            p.fatigue = c5(o.optInt("fat", FAT_STANDARD), FAT_OFF);
            return p;
        }

        private static int c5(int v, int hi) { return v < 0 ? 0 : (v > hi ? hi : v); }
    }

    /** The girth track's Program, and the length track's. Never null. */
    public Program programGirth = new Program();
    public Program programLength = new Program();

    /** The Program governing a track's mints — null for the feeder (fixed, Q6). */
    public Program programFor(int track) {
        if (track == Plan.TRACK_FEEDER) return null;
        return track == Plan.TRACK_LENGTH ? programLength : programGirth;
    }

    /** Wave 3a (D6): a stage's set count as a PERSON means it — the work, not the
     *  legacy rest-set filler some saved mints still carry between holds. */
    public int workSetCount(Stage st) {
        int n = 0;
        for (int i = 0; i < st.setIds.size(); i++) {
            Set s = set(st.setIds.get(i));
            if (s != null && !s.rest) n++;
        }
        return n;
    }

    /** 1-based position of the set at `pos` among the stage's WORK sets (a rest set
     *  reports the position of the work it follows). */
    public int workSetPos(Stage st, int pos) {
        int n = 0;
        for (int i = 0; i <= pos && i < st.setIds.size(); i++) {
            Set s = set(st.setIds.get(i));
            if (s != null && !s.rest) n++;
        }
        return Math.max(1, n);
    }

    /** Wave 3a: the words that state the check pull BESIDE a work peak, when the
     *  tissue-adaptation assessment commands deeper than the routine's own work —
     *  empty when it does not. The card's PEAK means the work (owner ruling); the
     *  deepest thing the pump does is still stated, just under its own name. */
    public String checkPullSuffix(Routine r) {
        int a = Tau.commandedKpa(r, ceilKpa, workPeakKpa(r));
        return a > workPeakKpa(r) ? "  ·  check pull " + Fmt.p(a) : "";
    }

    /** The plan-only WORK peak — {@link #peak} without the assessment folded in. What
     *  the routine's own presets command; the figure the wave-1 cap binds to. */
    public int workPeakKpa(Routine r) {
        int pk = 0;
        List<Preset> p = plan(r);
        for (int i = 0; i < p.size(); i++) if (p.get(i).up > pk) pk = p.get(i).up;
        return pk;
    }

    /**
     * The safety ceiling clamps every set (both setpoints, both ends of a ramp), the
     * standardisation hold pressure AND every routine's assessment pressure — dropping
     * the ceiling must re-clamp everything that is already stored immediately, not wait
     * for the next edit of each field. EVERY routine, not just the one open in the
     * editor: the prototype's bumpK() had to be fixed for exactly that.
     */
    public void clampAll() {
        // The hold limit: in range, and a whole half-minute (roundHoldMaxSec).
        holdMaxSec = roundHoldMaxSec(holdMaxSec);
        // A HAND-EDITED FILE CANNOT SET AN HOUR. Applied at write time like every other
        // clamp here - including on load, which is a write.
        if (rxCockringMin < 0) rxCockringMin = 0;
        if (rxCockringMin > COCKRING_MAX_MIN) rxCockringMin = COCKRING_MAX_MIN;
        // THE CEILING CLAMPS ITSELF FIRST. Every bound below is derived from it, so a
        // ceiling outside its own range poisons all of them. SessionActivity's stepper
        // has always clamped it to [7,57]; fromJson read `o.optInt("ceil", 40)` raw, so
        // a corrupted, hand-edited or downgraded save file was accepted verbatim.
        //
        // Not a safety hole — Proto.addPreset caps every setpoint at 57 on the wire, so
        // the pump cannot be commanded past the hardware limit whatever the file says.
        // What it corrupts is what the app TELLS the user: the printed ranges, the
        // stepper bounds and the "min(57, ceiling)" copy would all quote a ceiling that
        // is not the one enforced. That is "a printed range must be the range actually
        // enforced", arriving through the load path instead of the edit path. This
        // codebase's rule is clamp at WRITE time, and a load is a write.
        ceilKpa = ci(ceilKpa, 7, 57);
        // Likewise the display unit: Fmt treats ANY string that is not "kPa" as inHg, so
        // a garbage value silently selects inHg rather than being noticed.
        if (!Fmt.isUnit(unit)) unit = "inHg";
        // Same rule for the SIZE unit: Fmt.len treats anything that is not "in" as
        // centimetres, so a garbage value would silently select cm rather than being
        // noticed and corrected.
        if (!Fmt.isSizeUnit(sizeUnit)) sizeUnit = "cm";
        // The load unit (2026-09-30): a garbage one gets the size unit's default.
        if (!Fmt.isLoadUnit(loadUnit)) loadUnit = Fmt.defaultLoadUnit(sizeUnit);
        // Each cylinder's "used for" (Cylinder#role): anything but girth or length is read
        // from its name, as a file from before roles were is.
        for (int i = 0; i < cylinders.size(); i++) {
            Cylinder c = cylinders.get(i);
            if (c != null && !Cylinder.ROLE_GIRTH.equals(c.role)
                    && !Cylinder.ROLE_LENGTH.equals(c.role))
                c.role = Cylinder.defaultRole(c.label);
        }
        // The measurement reminder's clock, bounded exactly as the schedule's is — a load
        // is a write, and a printed range must be the range actually enforced.
        measRemindHour = ci(measRemindHour, 0, 23);
        measRemindMin = ci(measRemindMin, 0, 59);
        // A reminder for a cadence that never comes due is not a reminder. Turned off
        // rather than left true-but-inert, so the settings row says what is actually true —
        // the same rule Schedule#clamp applies to a reminder with no training day.
        if ("off".equals(meas.mode)) measRemind = false;

        for (int i = 0; i < sets.size(); i++) {
            sets.get(i).clamp(ceilKpa);
            // Every stored ramp in whole cycles - a plan-written one too (Set#clamp leaves
            // those to the plan while it builds them); already whole, it is untouched.
            sets.get(i).snapRamp();
        }
        // The manual cycle is clamped at write time like everything else — a stored value can
        // become over-ceiling because the CEILING moved, not because the user typed it, so a
        // load (which is a write) must re-clamp it. Never null: an old file without it loads
        // the defaults so no manual screen has to guard against absence.
        if (manual == null) manual = Manual.defaults(ceilKpa);
        // manual.ramp is a REAL persisted user choice now (manual mode exposes Fixed and
        // Ramp), so it is honoured on load — the old "never resurrect a ramp" force-to-false
        // dated from when manual was fixed-only and made a chosen ramp vanish on restart.
        manual.clamp(ceilKpa);
        int cap = Math.min(57, ceilKpa);
        // Both ends now. This used to clamp std.kpa only from ABOVE, so a stored 0 was
        // kept and then printed against a row labelled "5 to ...".
        std.kpa = ci(std.kpa, 5, Math.max(5, cap));
        std.sec = ci(std.sec, 10, 60);
        std.release = ci(std.release, 0, Math.min(57, std.kpa));
        // A load is a write, so the schedule is bounded here too — an hour of 30 would
        // otherwise be printed against a row that claims 0–23, and a reminder left on
        // with no day selected would claim an alarm that can never fire.
        if (sched == null) sched = Schedule.allDays();
        // t10 R-60: the two facts the week's Long training days reads (Schedule#follow).
        sched.follow(trainerEnrolled && trainerGirthOn && trainerLengthOn, rxLengthFirst);
        sched.clamp();
        meas.hours = ci(meas.hours, 1, 50);
        meas.n = ci(meas.n, 1, 20);
        // THE SAVE-OFFER THRESHOLDS. Both arrived with Task 1 and were the only new
        // persisted settings clampAll did not bound, so fromJson accepted whatever the
        // file said — and this codebase's rule is clamp at WRITE time, with a load counting
        // as a write.
        //
        // The pressure one is the dangerous shape: it is compared as `|delta| >=
        // threshold`, and a NaN makes EVERY such comparison false while an Infinity makes
        // every one of them false too — the save-offer silently never appears again, with
        // nothing on any screen saying why, and anything printing it says "NaN kPa". A
        // non-finite value is not a value out of range, it is no value at all, so it goes
        // back to the feature's own default of 2.0 rather than being folded onto whichever
        // bound it happens to sit past.
        if (Double.isNaN(offerPressureKpa) || Double.isInfinite(offerPressureKpa))
            offerPressureKpa = 2.0;
        offerPressureKpa = cd(offerPressureKpa, Fmt.OFFER_MIN_KPA, Fmt.OFFER_MAX_KPA);
        // The percentage one has no NaN to worry about, only a range: 0 would ask about a
        // change of nothing, and above 50 the bar is higher than any change a person is
        // likely to make deliberately, so the offer would effectively be off without
        // saying so. 5..50, the same span the other bounded settings state outright.
        offerHoldSpeedPct = ci(offerHoldSpeedPct, 5, 50);
        // THE SEAL CHECK'S OWN TWO NUMBERS, bounded here for the same reason std.kpa is:
        // they are COMMANDED pressures and durations, the ceiling can move under them, and
        // a load is a write. `cap` is min(57, ceiling), the same cap the standardisation
        // hold uses, so the seal check can never be commanded above the ceiling the user
        // set — and the range Settings prints is this expression, not a copy of it.
        sealCheckKpa = ci(sealCheckKpa, 5, Math.max(5, cap));
        /* THE FLOOR IS THE APP'S OWN WORST CASE, not a round number. SessionActivity's
         * seal check waits up to SEAL_CHECK_CEILING_MS (25 s) for a plateau and then
         * measures for SEAL_CHECK_COAST_MS (10 s) from it, so a commanded hold shorter
         * than 35 s can end inside the measurement. The preset's two setpoints are equal,
         * so it does not stop there - it REPEATS, and the way back up is a PULL. A coast
         * window containing a re-pull measures the motor, not the seal, and the direction
         * of the error is the dangerous one: a leaking cuff reads as one that holds.
         *
         * 40 rather than 35: the backstop adds SEAL_CHECK_BACKSTOP_MARGIN_MS on top, and a
         * floor that only just clears the worst case is a floor that stops clearing it the
         * next time one of those constants moves. */
        sealCheckHoldS = ci(sealCheckHoldS, SEAL_CHECK_HOLD_MIN_S, 120);
        /* THE PRESSURE ANSWERS. The maximum is either 0 ("not stated", which drops out of
         * every min) or a real figure inside the band the documents describe: never under
         * the 4 hg below which there is nothing to prescribe, never over the absolute 15 hg
         * that nothing in this app exceeds. The drop is held to what may be saved
         * (Mint#DROP_PREF_MAX_KPA) - past the usual Mint#DROP_MAX_KPA as the person's own
         * call, never silently brought back down to it (owner, 2026-09-30); each hold's pull
         * holds it lower where it is built (Mint#dropFor). */
        if (rxWorkMaxKpa > 0)
            rxWorkMaxKpa = cd(rxWorkMaxKpa, Plan.L1_BAND_LO_KPA, Plan.ABSOLUTE_CAP_KPA);
        else rxWorkMaxKpa = 0;
        // Length's own maximum, held exactly as girth's is.
        if (rxLengthMaxKpa > 0)
            rxLengthMaxKpa = cd(rxLengthMaxKpa, Plan.L1_BAND_LO_KPA, Plan.ABSOLUTE_CAP_KPA);
        else rxLengthMaxKpa = 0;
        // 0.10 - the personal scale: each offset inside its stepper's reach, a warned value
        // never negative (Scale). What a prescription can reach is the hard limits' either way.
        trainerGirth.offsetKpa = Scale.clampOffset(trainerGirth.offsetKpa);
        trainerLength.offsetKpa = Scale.clampOffset(trainerLength.offsetKpa);
        trainerGirth.warnedOffsetKpa = Math.max(0.0, Scale.clampOffset(trainerGirth.warnedOffsetKpa));
        trainerLength.warnedOffsetKpa = Math.max(0.0, Scale.clampOffset(trainerLength.warnedOffsetKpa));
        rxDropKpa = Mint.clampDropKpa(rxDropKpa);
        if (dropLoweredSaidKpa < 0 || dropLoweredSaidKpa > 57) dropLoweredSaidKpa = DROP_LOWERED_NONE;
        planT10Laters = ci(planT10Laters, 0, PLAN_T10_LATER_MAX);
        /* FIVE MODES EXIST; THE FIFTH WAS MISSING FROM THIS LIST.
         *
         * MEAS_WEEK_14 - "the 1st and 4th session of a training week" - is offered on the
         * settings screen and written by the radio, and clampAll runs on the same tap. It
         * was not in the accepted set, so it was rewritten to "sessions" before the screen
         * had finished redrawing: the option could be tapped, never selected. */
        if (!"every".equals(meas.mode) && !"sessions".equals(meas.mode)
                && !"hours".equals(meas.mode) && !"off".equals(meas.mode)
                && !MeasLog.MEAS_WEEK_14.equals(meas.mode))
            meas.mode = "sessions";
        for (int i = 0; i < routines.size(); i++) {
            Routine r = routines.get(i);
            if (r.assess == null) r.assess = new Assess();
            r.assess.clamp(ceilKpa);
            // A rest stage's one number, bounded at write time like everything else — and a
            // load is a write. This also enforces the "a rest stage holds no sets" invariant
            // against a hand-edited file that put one in.
            for (int j = 0; j < r.stages.size(); j++) r.stages.get(j).clampRest();
        }
        // STAGE H TASK 2 — the trainer's own enum-shaped fields, bounded here for the same
        // reason every other setting in this method is: a load is a write, and a
        // hand-edited or corrupted file must not be able to park an int outside what the
        // field actually means. Girth style falls back to INTERVAL (the default) rather
        // than a nonsense track id; the plan-wide state falls back to NORMAL; each track's
        // own level is bounded to the engine's real L1..L4 span.
        if (trainerGirthStyle != Plan.TRACK_GIRTH_INTERVAL
                && trainerGirthStyle != Plan.TRACK_GIRTH_TRADITIONAL)
            trainerGirthStyle = Plan.TRACK_GIRTH_INTERVAL;
        if (trainerState < TRAINER_STATE_NORMAL || trainerState > TRAINER_STATE_SAFETY_FLAG)
            trainerState = TRAINER_STATE_NORMAL;
        if (trainerGirth == null) trainerGirth = new TrainerTrackState();
        if (trainerLength == null) trainerLength = new TrainerTrackState();
        trainerGirth.level = ci(trainerGirth.level, Plan.L1, Plan.L4);
        trainerLength.level = ci(trainerLength.level, Plan.L1, Plan.L4);
        // The strain clock is a time or nothing: a hand-edited negative is "every reading".
        if (trainerLength.strainSinceMs < 0L) trainerLength.strainSinceMs = 0L;
        if (trainerGirth.strainSinceMs < 0L) trainerGirth.strainSinceMs = 0L;
        // t10 - the tracks' new counts and clocks, each inside what it means.
        clampT10(trainerGirth);
        clampT10(trainerLength);
        // t10 REAL-1: a day the deload question was put, or never.
        if (deloadDueAskedDay < 0L) deloadDueAskedDay = 0L;
        // t10-K: a break is two dates in order, or none at all.
        if (breakFromMs <= 0L || breakUntilMs <= breakFromMs) {
            breakFromMs = 0L; breakUntilMs = 0L; breakStarted = false;
        }
    }

    /** t10 - one track's new state held to what each field means: a hand-edited file cannot
     *  add a dozen hybrid holds, owe a pressure past 15 inHg, or date a cut before 1970. */
    private static void clampT10(TrainerTrackState t) {
        t.hybridYield = ci(t.hybridYield, 0, TrainerTrackState.HYBRID_YIELD_MAX);
        if (t.r2ExHolds < 0) t.r2ExHolds = 0;
        t.r2PendKpa = ci(t.r2PendKpa, 0, (int) Math.floor(Plan.ABSOLUTE_CAP_KPA));
        if (t.slowLoadWeeks < TrainerTrackState.SLOW_LOAD_NONE)
            t.slowLoadWeeks = TrainerTrackState.SLOW_LOAD_NONE;
        if (t.lastCutMs < 0L) t.lastCutMs = 0L;
        if (t.cutsInRow < 0) t.cutsInRow = 0;
        if (t.restFromMs < 0L) t.restFromMs = 0L;
        if (t.loadMovedMs < 0L) t.loadMovedMs = 0L;
        if (t.planChangeMs < 0L) t.planChangeMs = 0L;
        if (t.dBlockMs < 0L) t.dBlockMs = 0L;
        // t10-K: a climb-back target is a load the plan can pull, or none.
        if (!(t.climbTargetLb > 0.0)) t.climbTargetLb = 0.0;
        if (t.climbTargetLb > Scale.LOAD_HARD_MAX_LB) t.climbTargetLb = Scale.LOAD_HARD_MAX_LB;
    }

    /** The same integer clamp Set.clamp and SessionActivity's clampI use, kept here so
     *  clampAll states its bounds in one style. */
    private static int ci(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** {@link #ci} for a double. Callers must have ruled NaN out first — NaN fails both
     *  comparisons and would be returned unchanged, which is precisely the value that must
     *  not survive a clamp. */
    private static double cd(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /**
     * How long this routine actually takes: its presets plus whatever the assessment
     * adds — the prototype's routDur(). ONE derivation, so the routines list, the
     * editor, the start confirmation and the seal check's "next:" line can never
     * disagree about the length of the same routine.
     */
    public long routineSec(Routine r) {
        return durationMs(r) / 1000 + Tau.assessDur(r);
    }

    /**
     * A brand-new routine, carrying the assessment defaults the CEILING actually
     * permits — the prototype's newRoutine() and newAssess() as one call, and the only
     * caller of {@link Assess#fresh}.
     *
     * The ceiling is applied here, at birth, rather than left to the next clampAll():
     * the routine editor prints the assessment pressure the instant it opens, and a
     * value that has to be corrected later is a value that was printed wrong once. That
     * is the same rule the addendum states for the steppers — the printed range must be
     * the range actually enforced — one level up.
     */
    public Routine newRoutine(String id, String name) {
        Routine r = new Routine();
        r.id = id; r.name = name;
        r.assess = Assess.fresh(ceilKpa);
        return r;
    }

    /* ================================================ THE ONE-BYTE PHASE LIMIT ========
     *
     * hold-at-max and drop-time go onto the wire as ONE BYTE each (see Proto#addPreset,
     * which clamps 0-255), so 255 s — 4:15 — is not a policy this app chose, it is the
     * whole range the hardware can be told about. The editors now span that entire range
     * rather than a smaller invented one; what they must never do is accept a bigger
     * number silently and quietly store something else, so a typed value above it gets
     * this sentence rather than a shrug.
     */
    public static final int HOLD_MAX_S = 255;

    /** The message for a hold/drop time typed above what one byte can carry, or null when
     *  the value fits. Pure, so the wording is pinned by the self-test rather than living
     *  only inside a listener. */
    public static String holdOverHint(double typedSec) {
        return holdOverHint(typedSec, HOLD_MAX_S);
    }

    /** Wave 2: the hint knows the SHAPE's real cap. Where the set stitches (capSec is an
     *  hour), a long typed hold is answered with what will happen — "sent as N presets" —
     *  not a false wire limit; over the hour, or on a shape genuinely bound to one byte
     *  (a ramp step, any drop time), the honest refusal stays. */
    public static String holdOverHint(double typedSec, int capSec) {
        if (typedSec <= HOLD_MAX_S) return null;
        if (capSec > HOLD_MAX_S) {
            if (typedSec > capSec) return "an hour is the most one hold can be";
            int n = (int) Math.ceil(typedSec / HOLD_MAX_S);
            return Fmt.t((long) typedSec) + " — sent as " + n + " presets";
        }
        return "the pump's limit is 4:15 per phase";
    }

    /** Wave 2: the hold ± step grows with the value — 1 s under a minute, 5 s under five
     *  minutes, 30 s above — so 5:00 is taps, not sixty seconds of auto-repeat. */
    public static int holdNudgeSec(int currentSec, int dir) {
        int base = dir < 0 ? currentSec - 1 : currentSec;
        int step = base < 60 ? 1 : base < 300 ? 5 : 30;
        return dir * step;
    }

    /* ============================================== DURATION SNAPPED TO WHOLE CYCLES ==
     *
     * A FIXED set repeats one pull/drop cycle until its duration runs out, and a duration
     * that is not a whole number of cycles ends the set part-way through one: the last
     * repetition is cut wherever the clock lands. The set editor's stepper therefore moves
     * in whole cycles, and a typed duration is floored onto one.
     *
     * FLOOR, never round up: a person who types 3:10 into a 3:00-cycle set asked for at
     * most 3:10, and giving them 6:00 is twice the pressure-time they asked for. The floor
     * is one whole cycle — a set that runs no cycles runs nothing.
     *
     * The one edge with no answer: uh + lh == 0 is not a cycle at all (nothing is held,
     * nothing is dropped), so there is nothing to snap to and the duration is returned
     * untouched rather than divided by zero.
     *
     * THE 30-SECOND FLOOR. Set#clamp bounds every duration to 30..3600, and it is the
     * authority — so where flooring would land under 30 s this climbs back to the first
     * whole cycle at or above 30 instead, and the two rules agree rather than each undoing
     * the other on alternate taps. RAMP sets are snapped by their own rule, not this one
     * (Set#rampCycles): every step whole cycles of its own hold and drop, at least one.
     * "The steps divide the duration evenly" was once taken to mean that; it does not.
     */
    /**
     * A GENERATED NAME FOLLOWS THE VALUE IT WAS GENERATED FROM. Returns the name this set
     * should now carry, given what its pull WAS.
     *
     * The only name this app generates from a pressure is the warm-up's
     * ({@link RxBuild#primeName}), and the test is exact: the name is replaced only when it
     * is still, character for character, what would have been generated for {@code
     * wasUpKpa}. A typed name, an edited one, or a generated name from a build that spelled
     * it differently all fail that test and are left alone — a name somebody chose is
     * theirs, and the rename dialog is where it changes.
     *
     * Pure and static so the harness can hold it to that: the dangerous half of this is not
     * "does it rename" but "does it ever rename something it should not".
     */
    public static String renamedIfGenerated(String name, int wasUpKpa, int nowUpKpa) {
        if (name == null) return null;
        if (wasUpKpa == nowUpKpa) return name;
        return name.equals(RxBuild.primeName(wasUpKpa)) ? RxBuild.primeName(nowUpKpa) : name;
    }

    public static int snapDurToCycle(int dur, int uh, int lh) {
        int cyc = uh + lh;
        if (cyc <= 0) return dur;                       // not a cycle — nothing to snap to
        if (dur < 0) dur = 0;
        int n = dur / cyc;
        if (n < 1) n = 1;
        int out = n * cyc;
        // Climb to the first whole cycle Set#clamp will not have to move.
        while (out < 30) out += cyc;
        return out;
    }

    /* ======================================================= THE ESTIMATED DOSE =======
     *
     * What a set is PREDICTED to deliver, in the same kPa·s a run actually measures — so
     * the editor can say roughly how much work a shape is before anyone runs it, and two
     * sets can be compared without doing the arithmetic by hand.
     *
     * It is the same integral Session#dose takes of the real trace, applied to the
     * COMMANDED shape instead of the measured one: only pressure above
     * Session.DOSE_FLOOR_KPA counts (a 10 kPa floor — below it there is contact but no
     * meaningful load), and only the fraction of each cycle actually spent at the pull
     * counts, which is uh / (uh + lh).
     *
     * IT IS AN ESTIMATE AND THE LABEL SAYS SO. It assumes the pump reaches its setpoint
     * instantly and holds it exactly; a real cuff takes seconds to climb and leaks a
     * little at the top, so the delivered figure is always somewhat lower. This is a
     * comparison between two plans, never a claim about a run.
     *
     * A RAMP is the sum of its steps' doses, each step being the same integral over that
     * step's own share of the duration — which is exactly the MEAN of the per-step
     * whole-duration doses, since ladder() gives every step an equal share.
     */
    public static double estDoseKpaS(Set s) {
        if (s == null) return 0;
        if (!s.ramp) return stepDose(s.up, s.uh, s.lh, s.dur);
        double total = 0;
        List<Preset> lad = s.ladder();
        for (int i = 0; i < lad.size(); i++) {
            Preset p = lad.get(i);
            total += stepDose(p.up, p.uh, p.lh, p.durMs / 1000.0);
        }
        return total;
    }

    /** One constant-setpoint stretch's dose: the above-floor pressure, times the fraction
     *  of the cycle spent holding it, times how long the stretch lasts. */
    private static double stepDose(int up, int uh, int lh, double durSec) {
        double above = up - Session.DOSE_FLOOR_KPA;
        if (above <= 0 || durSec <= 0) return 0;
        int cyc = uh + lh;
        // No cycle at all (both zero) means the pull is simply held for the whole stretch.
        double frac = cyc <= 0 ? 1.0 : (double) uh / cyc;
        return above * frac * durSec;
    }

    /** The estimated dose of a whole ROUTINE — every set of every stage, in order, summed.
     *  A stage id that resolves to nothing contributes nothing rather than guessing. */
    public double estDoseKpaS(Routine r) {
        if (r == null) return 0;
        double total = 0;
        for (int i = 0; i < r.stages.size(); i++) {
            Stage st = r.stages.get(i);
            for (int j = 0; j < st.setIds.size(); j++) total += estDoseKpaS(set(st.setIds.get(j)));
        }
        return total;
    }

    /* ------------------------------------------------------------------ goals */

    /**
     * THE FASTEST GAIN A GOAL MAY DESCRIBE. One inch of length and half an inch of girth
     * per YEAR — the user's own stated rule, converted once and kept here so the stepper,
     * the chart and the toast all cap against the same two numbers instead of three copies
     * that can drift.
     *
     * These are a CAP ON THE GOAL, not a prediction and not a promise. Nothing in this app
     * can make tissue change at any particular rate; what it can do is refuse to draw a
     * target line that would have someone chasing a number no honest year produces, and
     * then reading their real progress as failure against it.
     */
    public static final double MAX_LEN_GAIN_CM_PER_YEAR = 2.54;   // 1.00 in
    public static final double MAX_GIR_GAIN_CM_PER_YEAR = 1.27;   // 0.50 in

    /**
     * THE LIFETIME CEILING ON A GAIN, independent of any horizon: three inches of length,
     * an inch and a half of girth, over the baseline. The per-year rate is a statement
     * about a year; this is a statement about the whole enterprise. Without it a long
     * enough horizon would license any target at all — four years at an inch a year is
     * four inches — and a target nothing produces is exactly what the cap exists to refuse.
     */
    public static final double MAX_LEN_GAIN_CM_LIFETIME = 7.62;   // 3.00 in
    public static final double MAX_GIR_GAIN_CM_LIFETIME = 3.81;   // 1.50 in

    /** The horizons a goal may be given, in months — six months, a year, two, three. Kept
     *  here so the chips, the migration and the clamp all read one list. */
    public static final int[] GOAL_HORIZON_CHOICES = { 6, 12, 24, 36 };

    /** The nearest offered horizon to `months`, or the 12-month default when it is not one
     *  of them — what a hand-edited or future-written save is read as. */
    public static int clampHorizon(int months) {
        for (int i = 0; i < GOAL_HORIZON_CHOICES.length; i++)
            if (GOAL_HORIZON_CHOICES[i] == months) return months;
        return 12;
    }

    /** The per-year rate a goal of this metric may climb at. */
    public static double maxRatePerYear(boolean isLength) {
        return isLength ? MAX_LEN_GAIN_CM_PER_YEAR : MAX_GIR_GAIN_CM_PER_YEAR;
    }

    /**
     * The goal actually SAVED, given what the user typed, the baseline they are measuring
     * from and the TIME HORIZON they gave themselves — the typed value when it is
     * reachable, otherwise exactly the bound.
     *
     * TWO CAPS, THE SMALLER WINS.
     *   - the HORIZON cap: `baseline + rate * horizon`, where rate is 2.54 cm/yr of length
     *     or 1.27 cm/yr of girth. A BPEL baseline of 10 in over two years bounds at 12 in.
     *   - the LIFETIME cap: `baseline + 7.62 cm` (length) / `3.81 cm` (girth), whatever the
     *     horizon says. At 48 months the horizon would allow 10.16 cm of length; this
     *     refuses anything past 7.62.
     *
     * WHY IT CLAMPS RATHER THAN REFUSES. A refusal leaves the stepper sitting on a number
     * the app will not keep, and the user has to guess what it would accept. Clamping
     * answers the question — "the most this can be is this" — and the caller says so in a
     * toast, so nothing is changed silently.
     *
     * A goal BELOW the baseline is never capped: that is not a gain, and a cap on growth
     * has nothing to say about it. `baselineCm <= 0` means there is no reading to measure a
     * gain FROM, so the typed value is returned unchanged — both caps are stated as a gain
     * OVER a baseline, and inventing one from zero would pin every first-ever goal to the
     * cap itself.
     */
    public static double capGoal(double goal, double baselineCm, boolean isLength,
                                 int horizonMonths) {
        if (Double.isNaN(goal) || Double.isInfinite(goal)) return baselineCm;
        if (baselineCm <= 0) return goal;
        double months = horizonMonths <= 0 ? 0 : (double) horizonMonths;
        double byHorizon = maxRatePerYear(isLength) * (months / 12.0);
        double lifetime = isLength ? MAX_LEN_GAIN_CM_LIFETIME : MAX_GIR_GAIN_CM_LIFETIME;
        double gain = byHorizon < lifetime ? byHorizon : lifetime;
        double bound = baselineCm + gain;
        return goal > bound ? bound : goal;
    }

    /**
     * THE SLOPE THE GOAL LINE IS DRAWN AT, per year: the rate that lands exactly ON the
     * goal at the END of the horizon, `(goal - baseline) / horizonYears`.
     *
     * WHY DERIVED AND NOT THE CAP. The cap is a bound on what may be PROMISED; drawing at
     * the cap when the goal is nearer than the cap allows would have the line reach the
     * target early and then run flat, saying "on pace" months before the horizon and
     * "behind" to anyone reading the flat stretch. {@link #capGoal} guarantees this slope
     * is never steeper than the per-year rate, so the picture is bounded by construction.
     *
     * Zero when there is no gain to climb or no horizon to climb it over — the callers
     * ({@link #goalLineAt}, {@link #goalReachedAt}) already read a non-positive rate as
     * "no line to draw".
     */
    public static double goalSlopePerYear(double baseCm, double goalCm, int horizonMonths) {
        if (horizonMonths <= 0 || goalCm <= baseCm) return 0;
        return (goalCm - baseCm) / ((double) horizonMonths / 12.0);
    }

    /* ------------------------------------------------- the goal as a SLOPED trend line */

    /**
     * A year, in milliseconds — 365.25 days, so the slope does not step every fourth year.
     * Named here because {@link #goalLineAt} and {@link #goalReachedAt} must divide by the
     * same number or the line and its caption would disagree about when it lands.
     */
    public static final long YEAR_MS = 31557600000L;

    /**
     * THE GOAL AS A LINE THROUGH TIME, at instant `t` — the value the capped trajectory
     * holds, anchored at the BASELINE measurement (`baseTs`, `baseCm`) and climbing at
     * `maxPerYearCm` until it reaches `goalCm`, after which it is flat.
     *
     * WHY A SLOPE AND NOT A HORIZONTAL LINE. A flat line at the goal says only "here is a
     * number you are not at". It is the same distance away on the day it is set as it is
     * eleven months later, so it can tell nobody whether they are on pace — which is the one
     * question a target is for. A line that starts where the user actually started and
     * climbs at the fastest rate this app is willing to describe answers it by construction:
     * the trend above the line is ahead, below it is behind.
     *
     * THE SLOPE IS THE CAP, never a rate derived from a deadline, because there is no
     * deadline to derive one from — a goal here is a value, not a date. {@link #capGoal}
     * already refuses to STORE a goal further than one capped year above the current
     * reading; this draws the same rule as a picture.
     *
     * EDGES, all of them deliberate:
     *   - `t` before the baseline is the BASELINE value, not an extrapolation backwards.
     *     The trajectory begins where the measuring began; inventing a smaller "you" for
     *     the weeks before the first reading would be drawing data that does not exist.
     *   - a goal at or below the baseline is not a gain, so there is no slope to draw and
     *     the goal's own value is returned flat. The caller decides whether to draw it at
     *     all (it does not — see the callers' "already reached" wording).
     *   - a non-positive rate cannot climb, so the line stays at the baseline rather than
     *     dividing by zero.
     */
    public static double goalLineAt(long t, long baseTs, double baseCm,
                                    double goalCm, double maxPerYearCm) {
        if (goalCm <= baseCm) return goalCm;
        if (maxPerYearCm <= 0) return baseCm;
        if (t <= baseTs) return baseCm;
        double v = baseCm + maxPerYearCm * ((double) (t - baseTs) / (double) YEAR_MS);
        return v > goalCm ? goalCm : v;
    }

    /**
     * WHEN the capped trajectory reaches the goal — the instant {@link #goalLineAt} first
     * returns `goalCm` — so the caption can say "on pace: <date>" with a date that is the
     * same line the chart drew, rather than a second estimate of it.
     *
     * `baseTs` when the goal is already at or below the baseline (it was reached at the
     * baseline), and -1 when no rate can carry it there.
     */
    public static long goalReachedAt(long baseTs, double baseCm,
                                     double goalCm, double maxPerYearCm) {
        if (goalCm <= baseCm) return baseTs;
        if (maxPerYearCm <= 0) return -1;
        return baseTs + (long) Math.ceil((goalCm - baseCm) / maxPerYearCm * (double) YEAR_MS);
    }

    /** WHOLE MONTHS between two instants — S6+'s "GOAL REACHED" card's "· N months" line,
     *  how long the goal took from its anchor reading to the reading that reached it. A
     *  plain elapsed-time divide (a year over twelve, {@link #YEAR_MS}), not a calendar
     *  Jan-31-to-Feb-28 walk: the card states how long a goal took in round months, never
     *  a calendar anniversary. Floored, and never negative — `toTs` at or before `fromTs`
     *  is 0 months, the same "nothing to compare yet" zero this file's other elapsed-time
     *  readouts ({@link #actualRatePerYear}) already return rather than a negative or a
     *  divide-by-zero. */
    public static int elapsedMonths(long fromTs, long toTs) {
        if (toTs <= fromTs) return 0;
        return (int) ((toTs - fromTs) / (YEAR_MS / 12L));
    }

    /**
     * THE M1 PROJECTION'S VALUE, at instant `t` — today's one-line indirection over
     * {@link #goalLineAt}, so a call site asking "what does the projection say right
     * now" names that question directly instead of reading it off "the trend line".
     *
     * WHY THIS EXISTS SEPARATELY FROM goalLineAt, AND HOW S10+ RESOLVED IT. S10+'s
     * forecast-source setting ({@link #goalProjectionSmoothed}, round6-options.html's
     * amendment) turned out NOT to redirect anything inside this method: the LINE stays
     * exactly the fixed plan goalLineAt already draws. NOT because the anchor could not
     * be smoothed — for the call site that anchors over a period-filtered window rather
     * than the whole log, earlier readings genuinely do exist outside that window for a
     * smoothing pass to reach for — but because the anchor is deliberately WINDOW-scoped
     * (see {@link #goalProjectionSmoothed}'s own doc for the full reasoning), and reaching
     * past that window to smooth it would give the anchor a different scope than the rest
     * of that same chart is drawn against. What the setting actually redirects is the
     * OTHER side of every comparison this projection feeds — the trend badge's `actualCm`
     * and the M1 card's PACE ACTUAL — and that redirect happens in the CALLER
     * (SessionActivity), before either figure is computed, because this method has no
     * reading of its own to redirect. It still
     * remains the one place every caller asks "what does the projection say right now",
     * which is why the indirection was kept even though the branch never landed here.
     */
    public static double goalProjectionCmAt(long t, long baseTs, double baseCm,
                                            double goalCm, double maxPerYearCm) {
        return goalLineAt(t, baseTs, baseCm, goalCm, maxPerYearCm);
    }

    /* ------------------------------------------------- the S6+ trend badge ------------ */

    /** Ahead of the M1 projection by more than the noise band. */
    public static final int TREND_AHEAD = 1;
    /** At or above the projection, within the noise band — "on trend". */
    public static final int TREND_ON = 0;
    /** Below the projection. */
    public static final int TREND_BELOW = -1;

    /**
     * HOW FAR ABOVE THE PROJECTION A READING MUST LAND to read as "ahead of trend"
     * rather than merely "on trend" (S6+) — comfortably above the single decimal a
     * length or girth reading is ever DRAWN to ({@link Fmt#lenNum}, one decimal in cm),
     * so two readings that only differ in the app's own rounding can never be told
     * apart as "ahead" and "on"; comfortably below the gain a genuine multi-week trend
     * produces, so the badge is not so wide that nothing short of a huge jump ever
     * reads as ahead. The same "clear of ordinary noise, short of a real signal" shape
     * {@link Session#VENT_FALL_NOISE_FLOOR_KPA} already states for the pressure trace,
     * stated here for the cm domain the goal instrument actually draws in (length or
     * girth — never kPa; the M1 instrument has no pressure axis).
     */
    public static final double GOAL_TREND_NOISE_CM = 0.3;

    /**
     * THE TREND BADGE'S OWN CLASSIFICATION (S6+) — a reading of `actualCm` against the
     * M1 projection's `projectedCm` for that SAME date: strictly above the projection by
     * more than `noiseBandCm` is {@link #TREND_AHEAD}; at or above it, within the band,
     * is {@link #TREND_ON}; anything below the projection is {@link #TREND_BELOW}.
     *
     * Pure, and takes the noise band as a parameter rather than reading {@link
     * #GOAL_TREND_NOISE_CM} itself, so the three boundaries this method draws can be
     * asserted at values SelfTest chooses independently of whatever the constant happens
     * to be — the usual "the number and the decision that uses it are pinned
     * separately" discipline this file already keeps for {@link #capGoal}'s two rate
     * constants.
     */
    public static int goalTrendState(double actualCm, double projectedCm, double noiseBandCm) {
        double diff = actualCm - projectedCm;
        if (diff > noiseBandCm) return TREND_AHEAD;
        if (diff >= 0) return TREND_ON;
        return TREND_BELOW;
    }

    /* ---------------------------------------- the M1 goal instrument (Stage B task 12) */
    /* Both of these are read-outs of the SAME anchor/goal/rate the trend line above already
     * derives — no new slope, anchor or cap arithmetic, just two more things to ask of it:
     * how far along a fraction reads, and how fast the readings themselves have actually
     * moved (as opposed to how fast the PLAN says they must). */

    /**
     * HOW FAR ALONG, as a fraction of the goal — `(currentCm - baselineCm) / (goalCm -
     * baselineCm)`, clamped to [0, 1]. This is not new math so much as a new NAME for an
     * old ratio: {@link #goalLineAt} already turns exactly this fraction into a y-position
     * on the chart every time it draws; the M1 instrument's progress bar and its header
     * percentage just read the same fraction back as a number instead of a pixel.
     *
     * `goalCm <= baselineCm` has no span to divide by — the same degenerate case {@link
     * #goalSlopePerYear} already reads as "no slope to climb" — and answers 1 here (already
     * there) rather than a divide-by-zero, because a goal that is not above its baseline is
     * not a gain still being chased.
     */
    public static double goalProgressFraction(double currentCm, double baselineCm,
                                               double goalCm) {
        double span = goalCm - baselineCm;
        if (span <= 0) return 1;
        double f = (currentCm - baselineCm) / span;
        if (f < 0) return 0;
        if (f > 1) return 1;
        return f;
    }

    /**
     * THE RATE ACTUALLY BEING ACHIEVED, per year — the empirical mirror of {@link
     * #goalSlopePerYear}'s "rate required". That method answers how fast the PLAN must
     * climb to land on the goal at the end of the horizon; this answers how fast the
     * READINGS THEMSELVES have climbed between two of them, `(toCm - fromCm)` over the
     * elapsed time in years. Feed it the same anchor the projection is drawn from
     * ({@link Meas#goalAnchorOfMethod}) and the newest reading of that method, and the M1
     * instrument's "PACE NEED · ACTUAL" row puts the two side by side without either one
     * re-deriving the anchor on its own.
     *
     * Zero when there is no elapsed time to divide by — `toTs` at or before `fromTs`, the
     * same "nothing to compare yet" zero {@link #goalSlopePerYear} returns for a zero
     * horizon, never a divide-by-zero.
     */
    public static double actualRatePerYear(long fromTs, double fromCm,
                                           long toTs, double toCm) {
        long dt = toTs - fromTs;
        if (dt <= 0) return 0;
        return (toCm - fromCm) / ((double) dt / (double) YEAR_MS);
    }

    /* ----------------------------------------------------------------- seed */

    /** What a brand-new install starts with. Nothing here is required to be kept. */
    public static Model seed() {
        Model m = new Model();
        m.firstRun = FirstRun.NOT_STARTED;   // the one place a setup is ever owed
        // One example per distinct behaviour the pump can run, so a fresh install shows what
        // each mode is rather than an empty library. All well inside the default 40 kPa
        // ceiling. (These ship only on a FRESH install; an upgrade keeps the user's own data.)
        //   Gentle Hold    — a steady low pull, held: the warm-up / beginner shape.
        m.sets.add(Set.fixed("s1", "Gentle Hold",     14,  7,  40,  8,  60, 240));
        //   Endurance Hold — a firm pull held long: the steady-state endurance shape.
        m.sets.add(Set.fixed("s2", "Endurance Hold",  30,  4, 150, 15,  90, 420));
        //   Pulse          — rhythmic pull then release, short holds: the interval shape.
        m.sets.add(Set.fixed("s3", "Pulse",           26, 12,   4,  3, 100, 240));
        //   Progressive Ramp — climbs from a start pair to a deeper end over several presets.
        m.sets.add(Set.ramp ("s4", "Progressive Ramp",18,  9,  30,  5,  70,
                                                      34, 10, 45, 5, 100,  5, 360));
        m.routines.add(Routine.of("r1", "Warm + Build",  new String[]{"s1", "s4"}));
        m.routines.add(Routine.of("r2", "Pulse Session", new String[]{"s1", "s3"}));

        // THE SETUP-CHECKLIST SEED (Stage D task 1, decision S1 B — "first-run seed only":
        // the checklist seeds one beginner routine into the library, no Templates section
        // and no picker). A THIRD, dedicated routine rather than pointing the checklist at
        // r1/r2 above: those two are "one example per distinct pump behaviour" (the bullets
        // above), and a user who renames or deletes one of them mid-tour must not silently
        // break what the checklist card promises is there. Gentler and shorter than even
        // Gentle Hold, on purpose — this is the very first thing a brand-new user runs.
        m.sets.add(Set.fixed("s5", "Starter Pull", 10, 5, 30, 8, 50, 180));
        m.routines.add(Routine.of(SEED_STARTER_ROUTINE_ID, "Starter Routine", new String[]{"s5"}));

        m.selected = "r1";
        m.routines.get(0).star = true;      // Today opens with something in Favourites
        m.clampAll();
        return m;
    }

    /* ----------------------------------------------------------------- json */

    /**
     * THE TOP-LEVEL KEYS OF model.json THIS VERSION DOES NOT KNOW (0.10) - written by a newer
     * app, say in a backup restored here - kept exactly as read and written back with the
     * fields (JsonKeep), so the next save does not drop them. Null when there were none.
     */
    public JSONObject unknown;

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            // A newer app's keys go in FIRST, and every field below overwrites its own key:
            // the same result as putting them back last (no key this version writes is
            // ever among them - JsonKeep keeps only keys the loader never asked for).
            JsonKeep.putBack(o, unknown);
            o.put("ceil", ceilKpa);
            o.put("selected", selected == null ? "" : selected);
            // Wave 4 item 10B: the Quick-run sheet's recents — most recent first, capped.
            JSONArray rq = new JSONArray();
            for (int i = 0; i < recentQuickSetIds.size(); i++) rq.put(recentQuickSetIds.get(i));
            o.put("recentQ", rq);
            o.put("unit", unit);
            o.put("sizeUnit", sizeUnit);
            o.put("loadUnit", loadUnit);
            o.put("measRemind", measRemind);
            o.put("measRemindH", measRemindHour);
            o.put("measRemindM", measRemindMin);
            o.put("haptic", hapticTicks);
            o.put("runColourOn", runColourOn);
            o.put("runColourWhere", runColourWhere);
            JSONArray rcol = new JSONArray();
            for (int i = 0; i < runColours.length; i++) rcol.put(RunLook.hex(runColours[i]));
            o.put("runColours", rcol);
            o.put("pullBuzz", pullBuzz);
            o.put("runStripWhere", runStripWhere);
            o.put("tone", toneCues);
            o.put("repCue", repCueAt);
            // Through a string, like every other double here: the desktop org.json shim has
            // no optDouble (see Meas.fromJson's note).
            o.put("offerKpa", String.valueOf(offerPressureKpa));
            o.put("offerPct", offerHoldSpeedPct);
            o.put("offerSkip", offerOnSkip);
            o.put("coach", coachLine);
            o.put("keepAwake", keepScreenOn);
            o.put("discreetNotif", discreetNotifications);
            o.put("incog", incognito);
            o.put("incogSet", incognitoSet);
            o.put("disguise", disguiseIcon);
            o.put("disguiseAs", Incognito.disguiseKey(disguiseAs));
            o.put("neutralSafety", neutralSafetyNotices);
            o.put("quickHide", quickHide);
            o.put("quickHideDo", quickHideAction);
            o.put("discreetRemind", discreetReminders);
            o.put("incogLock", incognitoLock);
            o.put("hideWidget", hideWidget);
            o.put("sealBefore", sealBeforeRoutine);
            o.put("sealKpa", sealCheckKpa);
            o.put("sealHold", sealCheckHoldS);
            o.put("rxNew", rxNewToPumping);
            // A STRING, like every other double this file stores (see TrainerTrackState's
            // own pressureKpa): the desktop harness's JSON shim has no optDouble, and a
            // figure that only round-trips on Android is a figure the tests cannot check.
            o.put("rxMax", String.valueOf(rxWorkMaxKpa));
            o.put("rxMaxL", String.valueOf(rxLengthMaxKpa));
            o.put("rxDrop", rxDropKpa);
            o.put("dropSaid", dropLoweredSaidKpa);
            o.put("blur", privacyBlur);
            o.put("lockOn", appLockOn);
            o.put("firstRun", firstRun);
            o.put("lockWhole", appLockWholeApp);
            o.put("lockPhotos", appLockPhotos);
            o.put("lockMeas", appLockMeasurements);
            o.put("lockSessions", appLockSessions);
            o.put("lockSettings", appLockSettings);
            o.put("secureWindow", secureWindow);
            // Nullable doubles go out as strings with "" for absent — the same encoding
            // Reading#holdKpa uses, and for the same reason: the desktop org.json shim has
            // no optDouble, and a numeric 0 would be indistinguishable from "no goal set".
            // THE THREE GOALS, one key each. New key names on purpose: a phone that
            // downgrades reads no "goalLen"/"goalGir" and comes up with no goal at all,
            // which is honest, where reusing the old keys would have an older build draw a
            // BPSSL number as a STANDARDIZED goal line.
            o.put("goalBpssl", goalBpsslCm == null ? "" : String.valueOf(goalBpsslCm.doubleValue()));
            o.put("goalMseg",  goalMsegCm  == null ? "" : String.valueOf(goalMsegCm.doubleValue()));
            o.put("goalMssg",  goalMssgCm  == null ? "" : String.valueOf(goalMssgCm.doubleValue()));
            // The camera's level reference, encoded exactly as the goals are: "" for absent,
            // so "never calibrated" and "calibrated to zero" stay different facts.
            o.put("calibPitch", calibPitch == null ? "" : String.valueOf(calibPitch.doubleValue()));
            o.put("calibRoll", calibRoll == null ? "" : String.valueOf(calibRoll.doubleValue()));
            // ANG - through strings like the pair above, and for the same reason: the
            // desktop org.json shim has no optDouble, and a forked reader is how a field
            // starts meaning one thing on the phone and another in the test.
            o.put("angPovRestP", nd(angPovRestP));   o.put("angPovRestR", nd(angPovRestR));
            o.put("angPovStdP",  nd(angPovStdP));    o.put("angPovStdR",  nd(angPovStdR));
            o.put("angSideRestP", nd(angSideRestP)); o.put("angSideRestR", nd(angSideRestR));
            o.put("angSideStdP",  nd(angSideStdP));  o.put("angSideStdR",  nd(angSideStdR));
            // The per-view align profiles, as an ARRAY of {v:<view>, ...} rather than an
            // object keyed by view: the desktop org.json shim test.sh runs against has no
            // keys()/optJSONObject(String), and a persistence format the self-test cannot
            // load is a format nothing checks. Always written (possibly empty).
            JSONArray eps = new JSONArray();
            for (Map.Entry<String, EditProfile> e : editProfiles.entrySet()) {
                if (e.getValue() == null) continue;
                JSONObject ep = e.getValue().toJson();
                ep.put("v", e.getKey());
                eps.put(ep);
            }
            o.put("editProfiles", eps);
            // goalMethod / goalMetricGirth are deliberately NOT written any more — see
            // the note where the fields used to be. The horizon survives untouched: it is
            // shared by all three goals and is still the one "by when".
            o.put("goalHorizonMonths", goalHorizonMonths);
            o.put("goalProjectionSmoothed", goalProjectionSmoothed);
            o.put("chartSmoothed", chartSmoothed);
            JSONArray a = new JSONArray();
            for (int i = 0; i < sets.size(); i++) a.put(sets.get(i).toJson());
            o.put("sets", a);
            JSONArray b = new JSONArray();
            for (int i = 0; i < routines.size(); i++) b.put(routines.get(i).toJson());
            o.put("routines", b);
            JSONArray kp = new JSONArray();
            for (int i = 0; i < knownPumps.size(); i++) kp.put(knownPumps.get(i).toJson());
            o.put("knownPumps", kp);
            /* meas/std are wrapped in a one-element array rather than put as a bare
               nested object: the desktop org.json shim exposes optJSONArray(String) but
               not optJSONObject(String), so this is the shape both readers can decode
               with the same code — see Meas.fromJson's note. */
            JSONArray measA = new JSONArray();
            measA.put(meas.toJson());
            o.put("meas", measA);
            JSONArray stdA = new JSONArray();
            stdA.put(std.toJson());
            o.put("std", stdA);
            // F18 — the cylinders, and which is in use.
            JSONArray cylA = new JSONArray();
            for (int i = 0; i < cylinders.size(); i++) cylA.put(cylinders.get(i).toJson());
            o.put("cyl", cylA);
            o.put("cylActive", activeCylinder);
            o.put("cylNextId", nextCylinderId);
            // The manual cycle, wrapped in a one-element array for the same shim reason as
            // meas/std. NOT the adhoc list — that is transient and belongs to a run in flight.
            JSONArray manA = new JSONArray();
            manA.put(manual.toJson());
            o.put("manual", manA);
            JSONArray schedA = new JSONArray();
            schedA.put(sched.toJson());
            o.put("sched", schedA);
            JSONArray seenA = new JSONArray();
            for (int i = 0; i < seenMilestones.size(); i++) seenA.put(seenMilestones.get(i));
            o.put("seenMs", seenA);
            JSONArray goalShownA = new JSONArray();
            for (int i = 0; i < goalReachedShown.size(); i++) goalShownA.put(goalReachedShown.get(i));
            o.put("goalReachedShown", goalShownA);
            JSONArray stepUpShownA = new JSONArray();
            for (int i = 0; i < stepUpShown.size(); i++) stepUpShownA.put(stepUpShown.get(i));
            o.put("stepUpShown", stepUpShownA);
            o.put("setupDismissed", setupDismissed);
            o.put("cockringMin", rxCockringMin);
            o.put("supersetRests", rxSupersetRests);
            o.put("retentionOn", retentionOn);
            // "dayKey:minutes" strings, not nested arrays: the desktop shim's JSONArray has
            // optString(int) and optJSONObject(int) but no optJSONArray(int), and this
            // file's rule is that BOTH readers decode one shape. A pair of small ints is
            // the one case where a string is genuinely simpler than a wrapper object.
            JSONArray retA = new JSONArray();
            for (int i = 0; i < retentionDays.size(); i++)
                retA.put(retentionDays.get(i)[0] + ":" + retentionDays.get(i)[1]);
            o.put("retentionDays", retA);
            JSONArray setupA = new JSONArray();
            for (int i = 0; i < setupDone.size(); i++) setupA.put(setupDone.get(i));
            o.put("setupDone", setupA);
            // WHO WROTE THIS FILE, not what is in it. Always true, never read as a setting -
            // its only job is to be ABSENT in every save written before the cylinder step
            // existed, which is what the step's migration keys on. See fromJson.
            o.put("setupCyl", true);
            // Wrapped in a one-element array for the same shim reason as meas/std/sched:
            // the desktop org.json has optJSONArray(String) but no optJSONObject(String).
            JSONArray tnA = new JSONArray();
            tnA.put(thenNow.toJson());
            o.put("thenNow", tnA);
            o.put("measLog", measLog.toJson());
            o.put("sessions", sessLog.toJson());
            // Stage H Task 2 — the trainer's persisted state. See the fields' own doc
            // (above, near sizeUnit) for what each means; wrapped-in-array shapes follow
            // the same "no optJSONObject(String) in the desktop shim" rule meas/std/sched
            // already state.
            o.put("guidedStart", guidedStart);
            o.put("guidedAssist", guidedStartAssist);
            o.put("tupTiming", tupTiming);
            o.put("endHoldForMeasure", endHoldForMeasure);
            o.put("sealWithdrawn", sealWithdrawn);
            o.put(STD_PHOTO_OFF_KEY, stdPhotoOffDone);
            o.put("notifyAsked", notifyAsked);
            o.put("endHoldMaxSec", holdMaxSec);
            // A DOUBLE, so it is written as a STRING: the desktop org.json shim has no
            // optDouble, and this file's own rule is that both readers decode one shape.
            o.put("tupCountPct", String.valueOf(tupCountPct));
            o.put("repOverrides", repOverridesEnabled);
            o.put("trainerOn", trainerEnrolled);
            o.put("trainerWarmupLvl", trainerWarmupOfferedLevel);
            o.put("trainerAt", String.valueOf(trainerEnrolledAt));
            o.put("trainerMonths", trainerMonthsPumping);
            o.put("trainerMonthsAt", String.valueOf(trainerMonthsAt));
            o.put("lastSeenGirthLevel", lastSeenGirthLevel);
            o.put("rxWarmMin", rxWarmMin);
            // Through one-element ARRAYS: the desktop org.json shim exposes
            // optJSONArray but not optJSONObject — the same shape Assess uses.
            o.put("progG", new JSONArray().put(programGirth.toJson()));
            o.put("progL", new JSONArray().put(programLength.toJson()));
            o.put("rxWarmSteps", rxWarmSteps);
            o.put("rxWarmRamp", rxWarmRamp);
            o.put("rxPrimeKpa", String.valueOf(rxPrimeKpa));
            o.put("rxEaseHg", String.valueOf(rxEaseHg));
            o.put("rxManualWarm", rxManualWarm);
            o.put("rxLengthFirst", rxLengthFirst);
            o.put("remindOtherTrack", remindOtherTrack);
            o.put("dayBudgetAdvisory", dayBudgetAdvisory);
            o.put("pullCleared", pullClearedSessId == null ? "" : pullClearedSessId);
            o.put("simPump", simPump);
            o.put("marksEasily", marksEasily);
            o.put("bigCylinder", bigCylinder);
            o.put("oversizeAccepted", oversizeAccepted);
            o.put("oversizeDeclined", oversizeDeclined);
            o.put("returnStep", returnStep);
            o.put("returnDayRun", String.valueOf(returnDayRun));
            o.put("returnHeld", returnHeld);
            o.put("returnLastRun", returnLastRun);
            o.put("returnFrom", String.valueOf(returnFromMs));
            o.put("reduceAnchor", String.valueOf(returnAnchorMs));
            JSONArray shp = new JSONArray();
            for (int i = 0; i < shapes.size(); i++) shp.put(shapes.get(i).toJson());
            o.put("shapes", shp);
            o.put("rxRetention", rxRetention);
            o.put("rxRetentionMin", rxRetentionMin);
            o.put("rxRetentionKpa", String.valueOf(rxRetentionKpa));
            o.put("rxRestSec", rxRestSec);
            o.put("rxRestSecTrad", rxRestSecTrad);
            o.put("rxSetsPerBlock", rxSetsPerBlock);
            o.put("rxHoldSec", rxHoldSec);
            o.put("rxFatHold", rxFatigueHoldSec);
            o.put("gSessMin", trainerGirthSessMin);
            o.put("lSessMin", trainerLengthSessMin);
            o.put("rxSplit", rxSplit);
            o.put("resumeRoutineId", resumeRoutineId == null ? "" : resumeRoutineId);
            o.put("resumeDayKey", resumeDayKey);
            o.put("resumeCycles", resumeCycles);
            o.put("rxResumeWarm", rxResumeWarm);
            o.put("rxSplitWarmBoth", rxSplitWarmBoth);
            o.put("rampStartPct", rampStartPct);
            o.put("rampShortSteps", rampShortSteps);
            o.put("rampStepHg", String.valueOf(rampStepHg));
            o.put("rampLighterDays", rampLighterDays);
            o.put("rampCountClimb", rampCountClimb);
            o.put("gentleWarmStartKpa", String.valueOf(gentleWarmStartKpa));
            o.put("gentleWarmSpeedPct", gentleWarmSpeedPct);
            o.put("gentleWarmStepHg", String.valueOf(gentleWarmStepHg));
            o.put("r4Mode", girthAfterLength);
            o.put("lenLoad", lengthLoadMode);
            o.put("levelUpAnnouncedMs", String.valueOf(levelUpAnnouncedMs));
            o.put("unmeasuredLevel", unmeasuredLevel);
            o.put("unmeasuredLevelMs", String.valueOf(unmeasuredLevelMs));
            o.put("pendRecal", pendingRecalibrate);
            // The one-element array is the desktop shim's only way to carry a nested
            // object; the same wrapper every other nested record here uses.
            if (planNotice != null) {
                JSONArray pn = new JSONArray();
                pn.put(planNotice.toJson());
                o.put("planNotice", pn);
            }
            o.put("trainerGirthStyle", trainerGirthStyle);
            o.put("trainerGirthOn", trainerGirthOn);
            o.put("trainerGirthHybrid", trainerGirthHybrid);
            o.put("trainerFeederOptIn", trainerFeederOptIn);
            o.put("feederRestDays", feederRestDays);
            o.put(FEEDER_DAYS_ASKED_KEY, feederDaysAsked);
            o.put(PLAN_CHANGED_T10_KEY, planT10Seen);
            o.put(WEEKS_B_SEEN_KEY, weeksBSeen);
            o.put("planT10Later", planT10Laters);
            o.put("trainerLengthOn", trainerLengthOn);
            JSONArray tGirthA = new JSONArray();
            tGirthA.put(trainerGirth.toJson());
            o.put("trainerGirth", tGirthA);
            JSONArray tLenA = new JSONArray();
            tLenA.put(trainerLength.toJson());
            o.put("trainerLength", tLenA);
            o.put("trainerState", trainerState);
            o.put("trainerSafetyFlagAt", String.valueOf(trainerSafetyFlagAt));
            o.put("trainerSafetyNumbness", trainerSafetyNumbness);
            JSONArray tDecA = new JSONArray();
            for (int i = 0; i < trainerDecisions.size(); i++)
                tDecA.put(trainerDecisions.get(i).toJson());
            o.put("trainerDecisions", tDecA);
            o.put("trainerRemindTrain", trainerRemindTrainingDay);
            o.put("trainerRemindTrack", trainerRemindTrackingDay);
            o.put("trainerRemindDecision", trainerRemindDecisionReady);
            o.put("trainerRemindDeload", trainerRemindDeloadStart);
            o.put("trainerLastDeload", String.valueOf(trainerLastDeloadMs));
            o.put("trainerLastDeloadEnd", String.valueOf(trainerLastDeloadEndMs));
            JSONArray dwA = new JSONArray();
            for (int i = 0; i < deloadWindows.size(); i++)
                dwA.put(deloadWindows.get(i)[0] + ":" + deloadWindows.get(i)[1]);
            o.put("deloadWindows", dwA);
            o.put("deloadAskAnchor", String.valueOf(deloadAskAnchorMs));
            o.put("deloadDueAsked", String.valueOf(deloadDueAskedDay));
            // t10-K - the month-12 break.
            o.put("mbFrom", String.valueOf(breakFromMs));
            o.put("mbUntil", String.valueOf(breakUntilMs));
            o.put("mbStarted", breakStarted);
            o.put("trainerFirstDeloadTaken", trainerFirstDeloadTaken);
            o.put("trainerFeederMintId", trainerFeederMintId == null ? "" : trainerFeederMintId);
            o.put("trainerFeederMintSig",
                trainerFeederMintSig == null ? "" : trainerFeederMintSig);
            return o.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static Model fromJson(String s) {
        try {
            // A Reader: every key asked for below is this version's; the rest are kept.
            JsonKeep.Reader o = new JsonKeep.Reader(s);
            Model m = new Model();
            m.ceilKpa = o.optInt("ceil", 40);
            m.selected = o.optString("selected", null);
            // MIGRATION: absent before the Quick-run sheet existed — empty means no
            // recents yet, which is exactly what a fresh install shows.
            JSONArray rq = o.optJSONArray("recentQ");
            if (rq != null) for (int i = 0; i < rq.length(); i++) {
                String rid = rq.optString(i);   // one-arg form — the desktop shim's own
                if (rid.length() > 0) m.recentQuickSetIds.add(rid);
            }
            m.unit = o.optString("unit", "inHg");
            // MIGRATION: absent in every save written before the size unit and the
            // measurement reminder existed. Centimetres, because that is what every screen
            // already printed — an upgrade must not silently restate a person's readings in
            // a different unit — and the reminder OFF, because a notification is opt-in and
            // an update is not a request for one. clampAll() (below) bounds the clock.
            m.sizeUnit = o.optString("sizeUnit", "cm");
            // Absent before 2026-09-30: the size unit's default (inches -> lb, cm -> kg).
            m.loadUnit = o.optString("loadUnit", Fmt.defaultLoadUnit(m.sizeUnit));
            m.measRemind = o.optBoolean("measRemind", false);
            m.measRemindHour = o.optInt("measRemindH", 19);
            m.measRemindMin = o.optInt("measRemindM", 30);
            // Absent in every save written before haptics existed — default ON, which is
            // the feature's own default. A missing key means "never chose", not "off".
            m.hapticTicks = o.optBoolean("haptic", true);
            // MIGRATION (0.10 run colours): absent in every older file - coloured by step, on
            // the status line only, the default colours, and no buzz before a pull. A stored
            // set is taken only if every colour in it may stand (RunLook#sanitize).
            m.runColourOn = o.optBoolean("runColourOn", true);
            m.runColourWhere = RunLook.clampWhere(o.optInt("runColourWhere", RunLook.WHERE_LINE));
            JSONArray rcolIn = o.optJSONArray("runColours");
            int[] rcolRead = null;
            if (rcolIn != null && rcolIn.length() == RunLook.KINDS) {
                rcolRead = new int[RunLook.KINDS];
                for (int i = 0; i < RunLook.KINDS && rcolRead != null; i++) {
                    Integer c = RunLook.parseHex(rcolIn.optString(i, ""));
                    if (c == null) rcolRead = null; else rcolRead[i] = c.intValue();
                }
            }
            m.runColours = RunLook.sanitize(rcolRead);
            m.pullBuzz = o.optBoolean("pullBuzz", false);
            // MIGRATION (0.10 final): absent in every older file - the − / + strip pinned
            // above the run screen's buttons, the owner's default.
            m.runStripWhere = clampRunStripWhere(o.optInt("runStripWhere", RUN_STRIP_PINNED));
            // MIGRATION: absent in every save written before tone cues existed. Default
            // FALSE, which is the feature's own default (unlike haptics) — a missing key
            // means "never chose", and the only safe direction for a NEW audible cue is
            // off until asked for.
            m.toneCues = o.optBoolean("tone", false);
            // MIGRATION: absent in every save written before the repetition cue existed,
            // and OFF is the feature's own default for the same reason the tone cues take
            // it a missing key means "never chose", and a new audible cue stays quiet
            // until it is asked for. Clamped, because this one carries a NUMBER: a
            // hand-edited 400 is not a repetition any routine reaches, and RepCue#clampPref
            // reads anything it cannot name as "no cue chosen" rather than as a surprise.
            m.repCueAt = RepCue.clampPref(o.optInt("repCue", RepCue.OFF));
            // MIGRATION: absent in every save written before the save-what-you-ran offer.
            // Each takes the feature's OWN default — 2.0 kPa, 20 %, skips do ask — so an
            // upgraded phone behaves exactly like a fresh install rather than inheriting a
            // zero it never chose (0.0 kPa means "ask about any change at all", which is
            // the loudest possible setting to give someone silently). The coaching line
            // stays OFF, which is its default and the only safe direction for a line that
            // shares a screen with the live readout.
            try { m.offerPressureKpa = Double.parseDouble(o.optString("offerKpa", "2.0")); }
            catch (NumberFormatException e) { m.offerPressureKpa = 2.0; }
            m.offerHoldSpeedPct = o.optInt("offerPct", 20);
            m.offerOnSkip = o.optBoolean("offerSkip", true);
            m.coachLine = o.optBoolean("coach", false);
            // MIGRATION: absent in every save written before the run screen could hold
            // the display awake. Defaults TRUE — the feature's own default — so an
            // upgrading phone gets the same run screen a fresh install does rather than
            // a display that sleeps over a live cuff because of a key that was never there.
            m.keepScreenOn = o.optBoolean("keepAwake", true);
            // MIGRATION: absent in every save written before "Discreet notifications"
            // (T17) existed. Defaults FALSE, for the same reason privacyBlur's own
            // migration two blocks down does: turning the notification's real name and
            // numbers into a neutral "Session running" is a choice someone makes, never one
            // an update makes for them.
            m.discreetNotifications = o.optBoolean("discreetNotif", false);
            // MIGRATION: incognito (0.10). Absent in every older save: everything OFF, the real
            // icon, quick hide's choice at its default (C) - see the fields' own note.
            m.incognito = o.optBoolean("incog", false);
            m.incognitoSet = o.optInt("incogSet", 0) & Incognito.ALL;
            m.disguiseIcon = o.optBoolean("disguise", false);
            // MIGRATION: which disguise (0.10, three of them). Absent in every save before the
            // choice: "Fitness log", the one there was - a person with it on sees no change.
            // A word this app does not know (a newer app's disguise) is the default too.
            m.disguiseAs = Incognito.disguiseFromKey(o.optString("disguiseAs", ""));
            m.neutralSafetyNotices = o.optBoolean("neutralSafety", false);
            m.quickHide = o.optBoolean("quickHide", false);
            m.quickHideAction = Incognito.quickHideChoice(
                o.optInt("quickHideDo", Incognito.QH_DEFAULT));
            m.discreetReminders = o.optBoolean("discreetRemind", false);
            m.incognitoLock = o.optBoolean("incogLock", false);
            m.hideWidget = o.optBoolean("hideWidget", false);
            // THE SEAL-CHECK MIGRATION. Absent in every save written before the check
            // became a setting. The switch takes its documented default of FALSE, which is
            // a real behaviour change for an upgrading phone and is the decision on record:
            // the check is opt-in for routine runs. The two numbers take the figures the
            // check used while it was hardcoded, so turning it on reproduces exactly what
            // it always did. clampAll() (below) then bounds both.
            m.sealBeforeRoutine = o.optBoolean("sealBefore", false);
            m.sealCheckKpa = o.optInt("sealKpa", 20);
            m.sealCheckHoldS = o.optInt("sealHold", 45);
            /* A FILE WRITTEN BEFORE THESE EXISTED gets the defaults, and the default for
             * "new to pumping" is TRUE - which reproduces exactly what such a file already
             * experienced, since the beginner caps were unconditional before this. Nobody's
             * prescription changes by upgrading; it changes when they answer. */
            m.rxNewToPumping = o.optBoolean("rxNew", true);
            try { m.rxWorkMaxKpa = Double.parseDouble(o.optString("rxMax", "")); }
            catch (Exception ignored) { m.rxWorkMaxKpa = 0; }
            /* LENGTH'S OWN MAXIMUM, absent from every file written before it was asked: such a
             * file had one answer for both tracks, so length keeps it - nothing it prescribes
             * changes on the upgrade. An unreadable value falls back the same way. */
            String lenMax = o.optString("rxMaxL", "");
            try {
                m.rxLengthMaxKpa = lenMax.length() == 0 ? m.rxWorkMaxKpa
                                                        : Double.parseDouble(lenMax);
            } catch (Exception ignored) { m.rxLengthMaxKpa = m.rxWorkMaxKpa; }
            m.rxDropKpa = o.optInt("rxDrop", 3);
            // MIGRATION (t10 R-62): nothing said. clampAll bounds a hand-edited value.
            m.dropLoweredSaidKpa = o.optInt("dropSaid", DROP_LOWERED_NONE);
            // MIGRATION: absent in every save written before the privacy blur and the goal
            // existed. The blur defaults FALSE — turning someone's own measurements into
            // dots is a choice they make, never one an update makes for them — and both
            // goals default to ABSENT rather than to a zero, because a goal of 0.0 cm is
            // not a goal and would draw a goal line across the floor of every chart.
            m.privacyBlur = o.optBoolean("blur", false);
            // MIGRATION: absent in every save written before app lock (Task 6) existed.
            // Every key takes its field's own documented default (see the fields' own
            // doc for why appLockOn is FALSE rather than the mock's literal drawn switch
            // state) — an upgrading phone gets exactly what a fresh install gets, never a
            // lock a person already using this app never asked for.
            m.appLockOn = o.optBoolean("lockOn", false);
            // MIGRATION: absent in every file written before the first-run setup existed, and
            // those phones are all set up already - so absent means DONE, never "not started".
            m.firstRun = Math.max(FirstRun.NOT_STARTED,
                Math.min(FirstRun.DONE, o.optInt("firstRun", FirstRun.DONE)));
            m.appLockWholeApp = o.optBoolean("lockWhole", false);
            m.appLockPhotos = o.optBoolean("lockPhotos", true);
            m.appLockMeasurements = o.optBoolean("lockMeas", true);
            m.appLockSessions = o.optBoolean("lockSessions", false);
            m.appLockSettings = o.optBoolean("lockSettings", false);
            // MIGRATION: absent -> FALSE, changed deliberately and at the owner's request
            // (see the field's own note). This DOES turn screenshot and recording protection
            // off on a phone that already has the app, which is exactly what was asked for;
            // it is called out here rather than left to be discovered because the previous
            // comment in this spot argued the opposite case at length and a reader who
            // remembers it deserves to see that the reversal was deliberate.
            //
            // The two-argument form is still what must be used - the DEFAULT changed, not
            // the requirement to state one. A one-argument optBoolean would happen to give
            // the same answer today and would silently follow the platform if that ever
            // changed.
            m.secureWindow = o.optBoolean("secureWindow", false);
            // THE THREE GOALS. Absent on a fresh install and on every save older than this
            // round; null then, for the reason stated on the fields.
            m.goalBpsslCm = parseNullableDouble(o.optString("goalBpssl", ""));
            m.goalMsegCm  = parseNullableDouble(o.optString("goalMseg", ""));
            m.goalMssgCm  = parseNullableDouble(o.optString("goalMssg", ""));
            // MIGRATION FROM THE SINGLE GOAL. An older file carries at most one length goal
            // and one girth goal, plus the method they were stated in. Only applied when
            // this file has NONE of the new keys, so a re-save can never resurrect an old
            // number over a newer one the user has since set.
            if (m.goalBpsslCm == null && m.goalMsegCm == null && m.goalMssgCm == null) {
                Double oldLen = parseNullableDouble(o.optString("goalLen", ""));
                Double oldGir = parseNullableDouble(o.optString("goalGir", ""));
                int oldMethod = o.optInt("goalMethod", Reading.METHOD_STANDARDIZED);
                // THE OLD LENGTH GOAL BECOMES THE BPSSL GOAL — whatever method it was
                // stated in. Stated plainly because it is a real reinterpretation: BPSSL is
                // the only length goal the new model holds, so the alternative to keeping
                // the number is DROPPING it, and silently deleting a target someone set is
                // worse than restating it in the one length protocol that survives. Where
                // the old method WAS BPSSL (oldMethod == METHOD_BPSSL) this is exact.
                m.goalBpsslCm = oldLen;
                // THE OLD GIRTH GOAL BECOMES THE MSEG (erect) GOAL by default — the girth
                // target people state — except where the old method was already the soft
                // one (7), in which case it lands where it actually belongs.
                if (oldMethod == Reading.METHOD_MSSG) m.goalMssgCm = oldGir;
                else                                  m.goalMsegCm = oldGir;
            }
            // MIGRATION: absent before the level bubble — no reference angle, so the
            // pre-capture dial opens on its documented default (upright, unturned) and
            // says so, rather than claiming the user chose it.
            m.calibPitch = parseNullableDouble(o.optString("calibPitch", ""));
            m.calibRoll = parseNullableDouble(o.optString("calibRoll", ""));
            // MIGRATION: absent on every save written before the four slots existed, which
            // leaves each null and each falling back to the pair above - exactly the
            // behaviour that save was written under.
            m.angPovRestP = parseNullableDouble(o.optString("angPovRestP", ""));
            m.angPovRestR = parseNullableDouble(o.optString("angPovRestR", ""));
            m.angPovStdP  = parseNullableDouble(o.optString("angPovStdP", ""));
            m.angPovStdR  = parseNullableDouble(o.optString("angPovStdR", ""));
            m.angSideRestP = parseNullableDouble(o.optString("angSideRestP", ""));
            m.angSideRestR = parseNullableDouble(o.optString("angSideRestR", ""));
            m.angSideStdP  = parseNullableDouble(o.optString("angSideStdP", ""));
            m.angSideStdR  = parseNullableDouble(o.optString("angSideStdR", ""));
            // MIGRATION: absent before per-view profiles existed — no profile for any view,
            // so the align stage's frame is never green until the user saves one.
            JSONArray eps = o.optJSONArray("editProfiles");
            if (eps != null) for (int i = 0; i < eps.length(); i++) {
                JSONObject ep = eps.optJSONObject(i);
                if (ep == null) continue;
                String k = ep.optString("v", "");
                EditProfile p = EditProfile.fromJson(ep);
                // Half a profile is discarded, not stored — see EditProfile#complete.
                if (k.length() > 0 && p != null && p.complete())
                    m.editProfiles.put(k.toLowerCase(Locale.US), p);
            }
            // Absent in every save written before the horizon existed: 12 months, the
            // period the per-year cap was already stated in, so an old goal keeps its line.
            m.goalHorizonMonths = clampHorizon(o.optInt("goalHorizonMonths", 12));
            // Absent in every save written before S10+ existed: true (smoothed) for both —
            // the mock's own stated default, on a fresh install and on an upgrade alike.
            m.goalProjectionSmoothed = o.optBoolean("goalProjectionSmoothed", true);
            m.chartSmoothed = o.optBoolean("chartSmoothed", true);
            JSONArray a = o.optJSONArray("sets");
            if (a != null) for (int i = 0; i < a.length(); i++)
                m.sets.add(Set.fromJson(a.optJSONObject(i)));
            JSONArray b = o.optJSONArray("routines");
            if (b != null) for (int i = 0; i < b.length(); i++)
                m.routines.add(Routine.fromJson(b.optJSONObject(i)));
            // Absent in every save written before T9 existed — an empty list, the truth
            // for an install that has never remembered a pump. A duplicate address (a
            // hand-edited or foreign file) is folded onto the FIRST entry rather than kept
            // as two rows that would otherwise tie in the RSSI comparison.
            JSONArray kp = o.optJSONArray("knownPumps");
            if (kp != null) for (int i = 0; i < kp.length(); i++) {
                KnownPump p = KnownPump.fromJson(kp.optJSONObject(i));
                if (p.address.length() == 0) continue;
                if (m.knownPump(p.address) != null) continue;
                m.knownPumps.add(p);
            }
            // Did this file ever contain a library at all? toJson ALWAYS writes both
            // arrays, even when they are empty; its own failure path writes "{}", which
            // has neither. So the presence of either key is what separates "the user
            // emptied the library" from "there is nothing here to read" — see the
            // seed() decision at the bottom of this method.
            boolean hasLibraryShape = a != null || b != null;
            JSONArray measA = o.optJSONArray("meas");
            m.meas = Meas.fromJson(measA != null && measA.length() > 0
                                    ? measA.optJSONObject(0) : null);
            JSONArray stdA = o.optJSONArray("std");
            m.std = Std.fromJson(stdA != null && stdA.length() > 0
                                  ? stdA.optJSONObject(0) : null);
            // F18. Absent in every save written before cylinders existed, which is
            // exactly "nobody has described one" — the guide falls back to its generic
            // outline rather than inventing a size.
            JSONArray cylA = o.optJSONArray("cyl");
            if (cylA != null)
                for (int i = 0; i < cylA.length(); i++) {
                    JSONObject co = cylA.optJSONObject(i);
                    if (co != null) m.cylinders.add(Cylinder.fromJson(co));
                }
            m.activeCylinder = o.optInt("cylActive", 0);
            // Absent before ids existed; ensureCylinderIds lifts it above whatever the file
            // already carries, so the default cannot cause a duplicate.
            m.nextCylinderId = o.optInt("cylNextId", 1);
            // Straight after the list, so a minted id cannot collide with one already in the
            // file, and every later reference has identities to resolve against.
            m.ensureCylinderIds();
            // Absent in every save written before Task 18 — loads the manual defaults, so an
            // old file gains a manual cycle rather than failing to parse. Forced to fixed and
            // its id fixed to Manual.ID, so a hand-edited file cannot make the manual set a
            // ramp or give it a colliding id. clampAll() (below) then clamps it.
            JSONArray manA = o.optJSONArray("manual");
            if (manA != null && manA.length() > 0) {
                m.manual = Set.fromJson(manA.optJSONObject(0));
                m.manual.id = Manual.ID;   // ramp mode preserved: it is the user's choice
            }
            // THE SCHEDULE MIGRATION. Absent in every save written before the schedule
            // existed, and Schedule.fromJson(null) is deliberately ALL SEVEN DAYS at 19:00
            // with reminders off — under an all-days schedule the rebased streak reduces
            // exactly to the old consecutive-calendar-day rule, so an old file's streak
            // does not move on the upgrade, and no notification is turned on for anyone
            // who never asked for one. SelfTest pins both halves of that.
            JSONArray schedA = o.optJSONArray("sched");
            m.sched = Schedule.fromJson(schedA != null && schedA.length() > 0
                                         ? schedA.optJSONObject(0) : null);
            JSONArray seenA = o.optJSONArray("seenMs");
            if (seenA != null) for (int i = 0; i < seenA.length(); i++) {
                String id = seenA.optString(i);
                if (id != null && id.length() > 0 && !m.seenMilestones.contains(id))
                    m.seenMilestones.add(id);
            }
            // Absent in every save written before the S6+ "GOAL REACHED" card existed —
            // an empty list, which is the truth about an install that has never crossed
            // a goal under this build.
            JSONArray goalShownA = o.optJSONArray("goalReachedShown");
            if (goalShownA != null) for (int i = 0; i < goalShownA.length(); i++) {
                String key = goalShownA.optString(i);
                if (key != null && key.length() > 0 && !m.goalReachedShown.contains(key))
                    m.goalReachedShown.add(key);
            }
            // Absent in every save written before the T1 "Ready to progress?" card
            // existed — an empty list, the truth about an install that has never run
            // one routine five times clean under this build.
            JSONArray stepUpShownA = o.optJSONArray("stepUpShown");
            if (stepUpShownA != null) for (int i = 0; i < stepUpShownA.length(); i++) {
                String key = stepUpShownA.optString(i);
                if (key != null && key.length() > 0 && !m.stepUpShown.contains(key))
                    m.stepUpShown.add(key);
            }
            // Absent in every save written before the "Get set up" card existed — false and
            // empty, i.e. none of the three steps are latched done and the card has not
            // been dismissed, which is the truth about an install that has never seen it.
            m.setupDismissed = o.optBoolean("setupDismissed");
            // MIGRATION: off and empty on every save written before retention tracking
            // existed, which is the truth about an install that has never been asked.
            // MIGRATION: both off on every save written before either existed. A timer
            // nobody set is not a timer, and an intensification nobody chose is not one
            // either.
            m.rxCockringMin = o.optInt("cockringMin", 0);
            m.rxSupersetRests = o.optBoolean("supersetRests", false);
            m.retentionOn = o.optBoolean("retentionOn", false);
            JSONArray retA = o.optJSONArray("retentionDays");
            if (retA != null) for (int i = 0; i < retA.length(); i++) {
                String one = retA.optString(i);
                int at = one == null ? -1 : one.indexOf(':');
                if (at <= 0) continue;
                try {
                    m.retentionDays.add(new int[]{
                        Integer.parseInt(one.substring(0, at)),
                        Integer.parseInt(one.substring(at + 1)) });
                } catch (NumberFormatException e) { /* a hand-edited row is dropped, not guessed */ }
            }
            JSONArray setupA = o.optJSONArray("setupDone");
            if (setupA != null) for (int i = 0; i < setupA.length(); i++) {
                String id = setupA.optString(i);
                if (id != null && id.length() > 0 && !m.setupDone.contains(id))
                    m.setupDone.add(id);
            }
            // AN INSTALL THAT HAD ALREADY FINISHED SETUP HAS FINISHED IT. The checklist closes
            // when every step is latched and never re-opens; adding a fifth step would have
            // re-opened it on the next cold start of every such install, which is precisely the
            // "must not reappear unasked" this class already promises. The truth about that old
            // data is that setup was complete, so the new step is complete in it too. An install
            // still mid-checklist (any of the four outstanding) is genuinely still setting up
            // and simply gains a row.
            //
            // GATED ON WHO WROTE THE FILE, not on what the file contains. Keying only on "all
            // four latched" reads the same on a save this build wrote a moment ago, so anybody
            // who finished the other four steps BEFORE describing a cylinder had the cylinder
            // step silently ticked on their next launch - the step vanished for exactly the
            // people it exists for. Caught on the emulator doing precisely that. "setupCyl" is
            // absent only in saves written before this step existed, which is the one question
            // the migration actually wants answered.
            boolean writtenBeforeCylinderStep = !o.optBoolean("setupCyl", false);
            if (writtenBeforeCylinderStep
                    && !m.setupDone.contains(SETUP_CYLINDER)
                    && m.setupDone.contains(SETUP_CONNECT)
                    && m.setupDone.contains(SETUP_SESSION)
                    && m.setupDone.contains(SETUP_MEASUREMENT)
                    && m.setupDone.contains(SETUP_BATTERY)) {
                m.setupDone.add(SETUP_CYLINDER);
            }
            // THE THEN-VS-NOW MIGRATION. No "thenNow" key on any save written before the
            // chooser existed, and ThenNowPick.fromJson(null) is FIRST versus LATEST — the
            // pair the card has always shown. An upgrading phone's Progress screen is
            // therefore byte-identical to what it showed yesterday, which is the only
            // acceptable default for a preference the user has never been asked about.
            JSONArray tnA = o.optJSONArray("thenNow");
            m.thenNow = ThenNowPick.fromJson(tnA != null && tnA.length() > 0
                                              ? tnA.optJSONObject(0) : null);
            m.measLog = MeasLog.fromJson(o.optJSONArray("measLog"));
            // The cylinder roles' second half, now that the erect girth is known (review I2).
            m.roleFromOldPullRule();
            // Absent in every save written before session history existed — an empty
            // log, never a fabricated one. Today then shows a streak of 0, which is
            // the truth about a phone that has filed nothing.
            m.sessLog = SessLog.fromJson(o.optJSONArray("sessions"));
            // STAGE H TASK 2 MIGRATION. Absent in every save written before the trainer
            // existed — every field takes its own documented default (see the fields'
            // own doc, above near sizeUnit): not enrolled, never enrolled-at, interval
            // girth style, length off, both tracks at a fresh L1/week-0/floor-pressure
            // position, plan state NORMAL, no decision history, every reminder off. An
            // upgrading phone gets exactly what a fresh install gets — nobody is silently
            // enrolled in a training plan by an app update.
            // MIGRATION: false - every run before this existed used the gate it was set to.
            m.guidedStart = o.optBoolean("guidedStart", false);
            // TRUE by default - see the field's own note on why this one default is not
            // the literal truth about old data.
            m.guidedStartAssist = o.optBoolean("guidedAssist", true);
            // MIGRATION: false - every session before this ran on the clock.
            m.tupTiming = o.optBoolean("tupTiming", false);
            // MIGRATION: false / 300 - no saved model ever chose to hold after a routine.
            m.endHoldForMeasure = o.optBoolean("endHoldForMeasure", false);
            // X1 — THE ONE-TIME WITHDRAWAL. Absent means it has not happened yet, which is
            // the truth about every model saved before this existed. Applied here rather
            // than at a call site so it runs exactly once per install, on the first load,
            // whatever screen that load happens to be for.
            // ABSENT MEANS AN OLD FILE, which is the only case the withdrawal has to
            // migrate. A model this build saved carries the key as true, so the flip runs
            // exactly once per install and a developer who turns the check back ON in
            // Developer options keeps it - the first version of this read the flag with the
            // wrong default and quietly undid that choice on every load.
            m.sealWithdrawn = o.optBoolean("sealWithdrawn", false);
            if (!m.sealWithdrawn) {
                m.sealBeforeRoutine = false;
                m.guidedStart = true;
                m.sealWithdrawn = true;
            }
            // "PHOTO DURING THE HOLD" GOES OFF ONCE - see stdPhotoOffDone. Absent means a file
            // written before the marker existed (or a backup from then); read with the
            // "not yet" default, so it runs exactly once per model and never over a choice
            // made after it. Here, after `std` is read, so it acts on the stored value.
            m.stdPhotoOffDone = o.optBoolean(STD_PHOTO_OFF_KEY, false);
            if (!m.stdPhotoOffDone) {
                m.std.photo = false;
                m.stdPhotoOffDone = true;
            }
            // MIGRATION: false - no model saved before this asked (see notifyAsked).
            m.notifyAsked = o.optBoolean("notifyAsked", false);
            m.holdMaxSec = roundHoldMaxSec(o.optInt("endHoldMaxSec", 300));
            // Absent means 2.0 - see the field's own note on why this default is not the
            // literal truth about old data.
            try {
                String tcp = o.optString("tupCountPct", "");
                if (tcp.length() > 0) m.tupCountPct = Double.parseDouble(tcp);
            } catch (NumberFormatException ignored) { }
            // MIGRATION: false - the seam test has not yet been run on hardware (H16).
            m.repOverridesEnabled = o.optBoolean("repOverrides", false);
            m.trainerEnrolled = o.optBoolean("trainerOn", false);
            // MIGRATION: 0 - nobody answered an offer that did not exist.
            m.trainerWarmupOfferedLevel = o.optInt("trainerWarmupLvl", 0);
            m.trainerEnrolledAt = parseLong(o.optString("trainerAt", "0"));
            // MIGRATION: 0/0 — a file saved before the answer was kept has never answered,
            // and TrainerTab#monthIndexNow falls back to months-since-enrolment for it.
            m.trainerMonthsPumping = o.optInt("trainerMonths", 0);
            m.trainerMonthsAt = parseLong(o.optString("trainerMonthsAt", "0"));
            // 0 = never announced. See the field's own migration note: it is deliberately
            // not a level, so a file saved before this existed adopts silently.
            m.lastSeenGirthLevel = o.optInt("lastSeenGirthLevel", 0);
            // MIGRATION: each default is the constant the mint used before it read any of
            // these, so an upgrading user's next prescription matches their last exactly.
            m.rxWarmMin = o.optInt("rxWarmMin", 4);
            // Wave 3b: absent on every install from before the Program existed — the
            // default Program, which is byte-for-byte today's behaviour.
            JSONArray pgA = o.optJSONArray("progG");
            JSONArray plA = o.optJSONArray("progL");
            m.programGirth = Program.fromJson(
                pgA != null && pgA.length() > 0 ? pgA.optJSONObject(0) : null);
            m.programLength = Program.fromJson(
                plA != null && plA.length() > 0 ? plA.optJSONObject(0) : null);
            m.rxWarmSteps = o.optInt("rxWarmSteps", 4);
            // MIGRATION: prime, not ramp. Deliberate — the shape changed for everybody,
            // because the old default gave Level 1 no warm-up at all.
            m.rxWarmRamp = o.optBoolean("rxWarmRamp", false);
            try { m.rxPrimeKpa = Double.parseDouble(o.optString("rxPrimeKpa", "")); }
            catch (NumberFormatException e) { m.rxPrimeKpa = 13.55; }
            try { m.rxEaseHg = Double.parseDouble(o.optString("rxEaseHg", "")); }
            catch (NumberFormatException e) { m.rxEaseHg = 3.0; }
            m.rxManualWarm = o.optBoolean("rxManualWarm", false);
            // MIGRATION false: girth first, the order the tracks have always been offered
            // in, so nobody's both-tracks day reverses on the upgrade.
            m.rxLengthFirst = o.optBoolean("rxLengthFirst", true);
            m.remindOtherTrack = o.optBoolean("remindOtherTrack", true);
            // 0.10 MIGRATION: the advisory on (see the field), and no numbness answer kept.
            m.dayBudgetAdvisory = o.optBoolean("dayBudgetAdvisory", true);
            m.pullClearedSessId = o.optString("pullCleared", "");
            // OFF on every upgrade, without exception. A file that has never heard of the
            // simulator has never been run against one.
            m.simPump = o.optBoolean("simPump", false);
            // MIGRATION false/0: nobody has said they mark easily, nobody is in a larger
            // cylinder, and no reduction is outstanding. Anything else would put somebody
            // under a reduced prescription they never asked for.
            m.marksEasily = o.optBoolean("marksEasily", false);
            m.bigCylinder = o.optBoolean("bigCylinder", false);
            // FALSE is the truth about every file written before the rack could derive an
            // oversize fit: nobody had been offered the reduction, so nobody had accepted it.
            m.oversizeAccepted = o.optBoolean("oversizeAccepted", false);
            m.oversizeDeclined = o.optBoolean("oversizeDeclined", false);
            if (o.has("returnStep")) {
                m.returnStep = o.optInt("returnStep", -1);
                m.returnDayRun = parseLong(o.optString("returnDayRun", "0"));
                m.returnHeld = o.optBoolean("returnHeld", false);
                m.returnLastRun = o.optInt("returnLastRun", -1);
                m.returnFromMs = parseLong(o.optString("returnFrom", "0"));
            } else if (o.optInt("reduceLeft", 0) > 0) {
                // MIGRATION: a file written before the taper existed, with a reduction still
                // outstanding. One session at 4 hg under IS the taper's first step, so it
                // becomes one rather than being dropped on the floor.
                m.returnStep = 0;
            }
            try { m.returnAnchorMs = Long.parseLong(o.optString("reduceAnchor", "0")); }
            catch (NumberFormatException e) { m.returnAnchorMs = 0L; }
            JSONArray shp = o.optJSONArray("shapes");
            if (shp != null) for (int i = 0; i < shp.length() && i < SHAPES_MAX; i++) {
                Shape sh = Shape.fromJson(shp.optJSONObject(i));
                if (sh.id.length() > 0) m.shapes.add(sh);
            }
            m.rxRetention = o.optBoolean("rxRetention", false);
            m.rxRetentionMin = o.optInt("rxRetentionMin", 5);
            try {
                m.rxRetentionKpa = Double.parseDouble(o.optString("rxRetentionKpa", ""));
            } catch (NumberFormatException e) {
                m.rxRetentionKpa = 13.5;
            }
            m.rxRestSec = o.optInt("rxRestSec", 180);
            m.rxRestSecTrad = o.optInt("rxRestSecTrad", 180);
            m.rxSetsPerBlock = o.optInt("rxSetsPerBlock", 5);
            m.rxHoldSec = o.optInt("rxHoldSec", 0);
            // R11-5: absent before hold lengths - 30 s for everyone (the owner's default).
            m.rxFatigueHoldSec = o.optInt("rxFatHold", FATIGUE_HOLD_DEFAULT_SEC);
            // R11-3: absent before placement by minutes - not answered.
            m.trainerGirthSessMin = Math.max(Placement.NOT_ANSWERED,
                o.optInt("gSessMin", Placement.NOT_ANSWERED));
            m.trainerLengthSessMin = Math.max(Placement.NOT_ANSWERED,
                o.optInt("lSessMin", Placement.NOT_ANSWERED));
            m.rxSplit = o.optBoolean("rxSplit", false);
            m.resumeRoutineId = o.optString("resumeRoutineId", "");
            m.resumeDayKey = o.optInt("resumeDayKey", 0);
            m.resumeCycles = o.optInt("resumeCycles", 0);
            m.rxResumeWarm = o.optBoolean("rxResumeWarm", true);
            m.rxSplitWarmBoth = o.optBoolean("rxSplitWarmBoth", true);
            // MIGRATION (0.10): absent on every file from before the ramp and gentle warm-up
            // settings - the owner's defaults, which is what those files now run (see the
            // fields' own notes). Garbage reads as the default; clampRxShape bounds the rest.
            m.rampStartPct = o.optInt("rampStartPct", RAMP_START_PCT_DEFAULT);
            m.rampShortSteps = o.optInt("rampShortSteps", RAMP_SHORT_STEPS_DEFAULT);
            m.rampStepHg = parseDoubleOr(o.optString("rampStepHg", ""), RAMP_STEP_HG_MAX);
            m.rampLighterDays = o.optBoolean("rampLighterDays", true);
            m.rampCountClimb = o.optBoolean("rampCountClimb", true);
            m.gentleWarmStartKpa = parseDoubleOr(o.optString("gentleWarmStartKpa", ""),
                                                 GENTLE_START_KPA_DEFAULT);
            m.gentleWarmSpeedPct = o.optInt("gentleWarmSpeedPct", GENTLE_SPEED_PCT_DEFAULT);
            m.gentleWarmStepHg = parseDoubleOr(o.optString("gentleWarmStepHg", ""),
                                               RAMP_STEP_HG_MAX);
            // MIGRATION (t10): absent on every older file - the owner's defaults, the same as a
            // new setup's (see the fields' own notes); clampRxShape reads garbage as them too.
            m.girthAfterLength = o.optInt("r4Mode", GIRTH_AFTER_LENGTH_DEFAULT);
            m.lengthLoadMode = o.optInt("lenLoad", LENGTH_LOAD_DEFAULT);
            m.clampRxShape();
            // Both absent on every file saved before the graduation existed - 0 in each case,
            // meaning "nothing announced" and "nothing asked". See the fields' own notes.
            m.levelUpAnnouncedMs = parseLong(o.optString("levelUpAnnouncedMs", "0"));
            m.unmeasuredLevel = o.optInt("unmeasuredLevel", 0);
            m.unmeasuredLevelMs = parseLong(o.optString("unmeasuredLevelMs", "0"));
            // MIGRATION: false - nobody who has not asked is waiting.
            m.pendingRecalibrate = o.optBoolean("pendRecal", false);
            // MIGRATION: null - nothing was applied before this existed.
            JSONArray pn = o.optJSONArray("planNotice");
            if (pn != null && pn.length() > 0)
                m.planNotice = PlanNotice.fromJson(pn.optJSONObject(0));
            m.trainerGirthStyle = o.optInt("trainerGirthStyle", Plan.TRACK_GIRTH_INTERVAL);
            m.trainerGirthOn = o.optBoolean("trainerGirthOn", true);
            m.trainerGirthHybrid = o.optBoolean("trainerGirthHybrid", false);
            m.trainerFeederOptIn = o.optBoolean("trainerFeederOptIn", true);
            // MIGRATION: false, training days only - the owner's default (S16, 2026-09-26),
            // for an old file too.
            m.feederRestDays = o.optBoolean("feederRestDays", false);
            // S16 FOLLOW-UP - see feederDaysAsked. Absent marker: owed only when the file
            // predates the switch itself and its plan has the feeder in it.
            m.feederDaysAsked = o.has(FEEDER_DAYS_ASKED_KEY)
                ? o.optBoolean(FEEDER_DAYS_ASKED_KEY, true)
                : o.has("feederRestDays") || !(m.trainerEnrolled && m.trainerFeederOptIn);
            // t10 R-61 - see planT10Seen. Absent marker: owed only by a trainer set up before -
            // a PAUSED one too (t10 fix, review D0 row 4): a pause is trainerEnrolled false
            // with its enrolment kept, and the card shows once the plan is resumed.
            boolean hadTrainer = m.trainerEnrolled || m.trainerEnrolledAt > 0L;
            m.planT10Seen = o.has(PLAN_CHANGED_T10_KEY)
                ? o.optBoolean(PLAN_CHANGED_T10_KEY, true)
                : !hadTrainer;
            m.planT10Laters = o.optInt("planT10Later", 0);
            // Week B - see weeksBSeen. Absent: owed only by a trainer set up before.
            m.weeksBSeen = o.has(WEEKS_B_SEEN_KEY)
                ? o.optBoolean(WEEKS_B_SEEN_KEY, true)
                : !hadTrainer;
            m.trainerLengthOn = o.optBoolean("trainerLengthOn", false);
            JSONArray tGirthA = o.optJSONArray("trainerGirth");
            m.trainerGirth = TrainerTrackState.fromJson(tGirthA != null && tGirthA.length() > 0
                                                          ? tGirthA.optJSONObject(0) : null);
            JSONArray tLenA = o.optJSONArray("trainerLength");
            JSONObject tLenO = tLenA != null && tLenA.length() > 0 ? tLenA.optJSONObject(0) : null;
            m.trainerLength = TrainerTrackState.fromJson(tLenO);
            // MIGRATION (t10 R-45): a length track saved before the month-3 hand-over existed
            // is handed over already when its trainer is set up and at month 3 or later now,
            // so the upgrade steps nobody's strain sets by surprise (the upgrade card says it).
            // A paused trainer likewise (hadTrainer above): its length track comes back at the
            // same month it would have reached.
            if (tLenO == null || !tLenO.has("handedOver"))
                m.trainerLength.handedOver = hadTrainer
                    && TrainerTab.monthIndexNow(m, System.currentTimeMillis())
                       >= Plan.LENGTH_METRICS_FROM_MONTH;
            m.trainerState = o.optInt("trainerState", TRAINER_STATE_NORMAL);
            // MIGRATION (Stage H Task 5): absent on every pre-Task-5 file — 0/false, no flag
            // active, the honest starting state.
            m.trainerSafetyFlagAt = parseLong(o.optString("trainerSafetyFlagAt", "0"));
            m.trainerSafetyNumbness = o.optBoolean("trainerSafetyNumbness", false);
            JSONArray tDecA = o.optJSONArray("trainerDecisions");
            if (tDecA != null) for (int i = 0; i < tDecA.length(); i++)
                m.trainerDecisions.add(TrainerDecision.fromJson(tDecA.optJSONObject(i)));
            m.trainerRemindTrainingDay = o.optBoolean("trainerRemindTrain", false);
            m.trainerRemindTrackingDay = o.optBoolean("trainerRemindTrack", false);
            m.trainerRemindDecisionReady = o.optBoolean("trainerRemindDecision", false);
            m.trainerRemindDeloadStart = o.optBoolean("trainerRemindDeload", false);
            // MIGRATION (Stage H Task 4): absent on every pre-Task-4 file — 0/false/empty.
            m.trainerLastDeloadMs = parseLong(o.optString("trainerLastDeload", "0"));
            m.trainerLastDeloadEndMs = parseLong(o.optString("trainerLastDeloadEnd", "0"));
            // MIGRATION (program fixes): absent before every window was kept - empty.
            JSONArray dwIn = o.optJSONArray("deloadWindows");
            if (dwIn != null) for (int i = 0; i < dwIn.length(); i++) {
                String cell = dwIn.optString(i);
                int colon = cell.indexOf(':');
                if (colon <= 0) continue;
                long ws = parseLong(cell.substring(0, colon));
                long we = parseLong(cell.substring(colon + 1));
                if (ws > 0L && we > ws) m.deloadWindows.add(new long[]{ ws, we });
            }
            while (m.deloadWindows.size() > DELOAD_WINDOWS_KEPT) m.deloadWindows.remove(0);
            m.deloadAskAnchorMs = parseLong(o.optString("deloadAskAnchor", "0"));
            m.deloadDueAskedDay = parseLong(o.optString("deloadDueAsked", "0"));
            // MIGRATION (t10-K): absent on every older file - no month-12 break taken.
            m.breakFromMs = parseLong(o.optString("mbFrom", "0"));
            m.breakUntilMs = parseLong(o.optString("mbUntil", "0"));
            m.breakStarted = o.optBoolean("mbStarted", false);
            m.trainerFirstDeloadTaken = o.optBoolean("trainerFirstDeloadTaken", false);
            m.trainerFeederMintId = o.optString("trainerFeederMintId", "");
            m.trainerFeederMintSig = o.optString("trainerFeederMintSig", "");
            // Seed ONLY when the file never described a library — a brand-new install
            // whose model.json does not exist yet (Store.load's own catch covers that
            // too), or a save that failed serialization and wrote "{}".
            //
            // It must NOT seed merely because both lists came back empty: deleting your
            // last routine and last set is a deliberate act, and re-seeding it silently
            // resurrected five sets and three routines on the next launch — AND, because
            // seed() is a whole fresh Model, silently reset the safety ceiling to 40 kPa
            // and the display unit to inHg at the same time. Everything the user had
            // configured went with it. Task 12 (persistence across a kill) is where that
            // has to hold; see SelfTest#task12FirstRun, which pins the emptied store, the
            // settings that must survive alongside it, and the "{}" case that still seeds.
            if (!hasLibraryShape && m.sets.isEmpty() && m.routines.isEmpty()) return seed();
            // AFTER the readings are loaded (the cylinders were, far above): the backfill
            // needs both sides present, and it must run while the list is still in the order
            // each reading's index was captured against.
            m.linkReadingCylinders();
            m.clampAll();
            // "reduceLeft" is read only when the taper's keys are absent (the migration
            // above): a key of this version's all the same, never a newer app's.
            m.unknown = o.unasked("reduceLeft");
            return m;
        } catch (JSONException e) {
            return seed();
        }
    }
}
