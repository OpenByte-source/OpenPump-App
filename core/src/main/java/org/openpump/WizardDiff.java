package org.openpump;

import java.util.List;

/**
 * THE REVIEW WIZARD'S DIFF-ROW WORDS (Stage C task 4) — the pure decision logic behind
 * the step panel's per-value rows (dist/final-design.html section 3, the locked mock's
 * "<s>−4.1</s> → −6.2 DEEPER" / "40/5 → 30/5 s SHORTER" / "SPEED · CARRIED — 60→75% ·
 * 3:46" rows). SessionActivity renders; every DECISION — which word a changed value
 * wears, which colour class the word's pill is filled with, and whether a value CARRIED
 * in from an earlier adjustment rather than being changed here — is taken in this file,
 * so SelfTest pins it on the desktop.
 *
 * THE LOCKED COLOUR SEMANTICS, from the design programme, non-negotiable:
 *
 *   - AMBER pill ("DEEPER") ONLY when the ran value means MORE PRESSURE than planned.
 *     This is a VACUUM pump: pressures are stored as positive kPa magnitudes and a
 *     LARGER magnitude is a DEEPER vacuum pull (Model.Fmt.d's own doc — "larger kPa
 *     magnitude = deeper vacuum"; the mercury units merely display that same depth as a
 *     more negative number). So ran > planned on a pressure field IS "more pressure",
 *     and is the ONE case {@link #colour} may answer AMBER — amber is the pump-under-
 *     command colour, and a pill is the only place the card lets it claim a value.
 *   - BLUE pill for every other change: time ("SHORTER"/"LONGER"), speed ("FASTER"/
 *     "SLOWER"), and a pressure change TOWARD zero ("SHALLOWER" — a shallower pull is
 *     less pressure, so amber would be a lie there).
 *   - An unchanged value gets NO pill and no word: {@link #word} answers "" and
 *     {@link #colour} answers NONE, and the row renders plain.
 *
 * Pure on purpose — no android import — so test.sh auto-discovers it.
 */
public final class WizardDiff {

    private WizardDiff() { }

    /** The four fields a step's diff rows show, in row order. HOLD is the PAIR (uh, lh)
     *  — the mock's one "HOLD ↑/↓" row — compared as a total when a word is chosen
     *  (40/5 → 30/5 is SHORTER because 45 s of cycle became 35 s). */
    public static final int PULL = 0, DROP = 1, HOLD = 2, SPEED = 3;

    /** Colour classes, not colours: the renderer maps AMBER to Look.COMMANDED ink on the
     *  Look.CMD_DIM fill and BLUE to Look.BLOCKS[0] ink on the Look.BLUE_DIM fill, so the
     *  pure layer never holds an ARGB. */
    public static final int NONE = 0, AMBER = 1, BLUE = 2;

    /** Whether the field is a PRESSURE — the only fields whose pill may ever be amber. */
    public static boolean pressureField(int field) {
        return field == PULL || field == DROP;
    }

    /**
     * The pill word for a (field, planned, ran) pair, or "" for an unchanged value.
     * Pressures are kPa magnitudes (deeper = larger, see the class doc); HOLD takes the
     * pair's TOTAL seconds (uh + lh) for both arguments; SPEED takes percent.
     *
     * The words are the mock's own: DEEPER/SHALLOWER for pressure, SHORTER/LONGER for
     * the hold pair, FASTER/SLOWER for speed. A HOLD pair whose components swapped but
     * whose total is unchanged (40/5 → 5/40) gets NO word — neither "shorter" nor
     * "longer" would be true of it, and the row's own struck-through components already
     * show exactly what moved.
     */
    public static String word(int field, int planned, int ran) {
        if (planned == ran) return "";
        if (pressureField(field)) return ran > planned ? "DEEPER" : "SHALLOWER";
        if (field == HOLD)        return ran < planned ? "SHORTER" : "LONGER";
        return ran > planned ? "FASTER" : "SLOWER";
    }

    /**
     * The pill's colour class for the same pair. AMBER exactly when a PRESSURE field ran
     * DEEPER than planned — the locked amber-only-for-more-pressure rule is this method's
     * whole shape, and SelfTest pins it as a sweep over every field and direction rather
     * than trusting the if-order.
     */
    public static int colour(int field, int planned, int ran) {
        if (planned == ran) return NONE;
        return pressureField(field) && ran > planned ? AMBER : BLUE;
    }

    /**
     * HOW LONG a changed value of block {@code i} had been IN FORCE by that block's end,
     * in ms — {@code > 0} exactly when the value CARRIED in from an adjustment made
     * before this block, 0 when it was changed here (or not changed at all). The mock's
     * row 3 is this case: "SPEED · CARRIED — 60→75% · 3:46" — the 75 % was not this
     * block's own adjustment, it was already playing when the block began, and 3:46 is
     * the elapsed time since the adjustment that set it.
     *
     * WHAT "CARRIED" IS IN THE RECORDING. A live adjustment writes an OVERRIDE row for
     * the preset it lands on AND for every later step of the stage it carries into
     * (AsRun.Row's kind doc), and AsRun.blocks splits value runs so each block starts
     * where its values began playing. So the block the adjustment was MADE in has a
     * predecessor that does not replay its tuple (the on-plan part it split from, or a
     * different stage), while a block the adjustment CARRIED into follows an override
     * block of the same stage still holding the same value. Per FIELD, because one
     * adjustment can re-touch the pull while the speed it set two presets ago keeps
     * carrying — the mock's own panel: PULL/HOLD adjusted here (pills), SPEED carried.
     *
     * The chain walks back through consecutive FIXED override blocks of the same stage
     * that hold the field's value (skipped placeholders — which carry PLANNED values,
     * nothing in force — are stepped over), and the duration runs from the chain's first
     * block, the adjustment itself, to this block's end: elapsed session time, exactly
     * what the ADJ @ caption's clock reads.
     *
     * Answers 0 (never a note) for: a block without an OVERRIDE row (a mid-run EDIT is
     * the user reshaping the plan, not a carried command), a RAMP block (a carried
     * override replays one FIXED tuple; a ramp is the plan's own ladder), a skipped
     * placeholder, a field equal to its plan (nothing changed, nothing to caption), and
     * a block with no plan (no "changed" without one).
     */
    public static long carriedMs(List<AsRun.Block> blocks, int i, int field) {
        if (blocks == null || i < 0 || i >= blocks.size()) return 0L;
        AsRun.Block b = blocks.get(i);
        if (b.skipped || !b.hasOverride || b.kind != AsRun.FIXED) return 0L;
        if (!b.hasPlan || !changedVsPlan(b, field)) return 0L;
        int j = prevValueBlock(blocks, i);
        if (j < 0 || !inChain(blocks.get(j), b, field)) return 0L;
        // Walk to the chain's first block — the adjustment the value carried from.
        int k = j;
        while (true) {
            int q = prevValueBlock(blocks, k);
            if (q < 0 || !inChain(blocks.get(q), b, field)) break;
            k = q;
        }
        long ms = b.t1 - blocks.get(k).t0;
        return ms > 0 ? ms : 0L;
    }

    /** Whether {@code p} keeps block {@code b}'s value of {@code field} in force — a
     *  FIXED override block of the same stage holding the same value. */
    private static boolean inChain(AsRun.Block p, AsRun.Block b, int field) {
        return p.hasOverride && p.kind == AsRun.FIXED && p.stageIdx == b.stageIdx
            && fieldEq(p, b, field);
    }

    /** The nearest earlier VALUE block — skipped placeholders hold planned values, not
     *  values in force, so they neither match nor break a chain. */
    private static int prevValueBlock(List<AsRun.Block> blocks, int i) {
        for (int j = i - 1; j >= 0; j--) if (!blocks.get(j).skipped) return j;
        return -1;
    }

    /** Whether the field's value differs from the block's PLANNED step. HOLD is the pair:
     *  either component moving is a change (the row shows both). */
    private static boolean changedVsPlan(AsRun.Block b, int field) {
        switch (field) {
            case PULL:  return b.up != b.pUp;
            case DROP:  return b.lo != b.pLo;
            case HOLD:  return b.uh != b.pUh || b.lh != b.pLh;
            default:    return b.sp != b.pSp;
        }
    }

    /** Whether two blocks hold the same value of the field. HOLD is the pair: a carried
     *  hold is BOTH seconds unchanged — one component re-adjusted makes the row this
     *  block's own change, not a carried one. */
    private static boolean fieldEq(AsRun.Block a, AsRun.Block b, int field) {
        switch (field) {
            case PULL:  return a.up == b.up;
            case DROP:  return a.lo == b.lo;
            case HOLD:  return a.uh == b.uh && a.lh == b.lh;
            default:    return a.sp == b.sp;
        }
    }
}
