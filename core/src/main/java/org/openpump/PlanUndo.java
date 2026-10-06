package org.openpump;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * FIX11 F1 - "UNDO THIS CHANGE" PUTS BACK A ROUTINE THAT RUNS.
 *
 * The plan's rewrite notice kept the routine as it was (its stages, as JSON) so Undo could put
 * it back. But a stage holds set IDS, and the rewrite had already deleted the sets those ids
 * named: the plan-written sets nothing references any more go with the shape that used them
 * (SavedMint#rewrite, Model#gcOrphanSets). Undo put back the stages and nothing they pointed
 * at, so the routine came back with no holds at all - 0 cycles, every block 0:00, and START
 * offering to run the pump with it (device check EMU12 F1). Every Level 3+ girth routine saw
 * this notice on the round-11 upgrade.
 *
 * NOW THE SETS ARE KEPT WITH THE NOTICE: {@link #setsJson} copies every set the routine plays
 * when the notice is written, before the rewrite deletes any of them, and {@link #restore}
 * puts back each one that is gone before it puts the stages back. A routine that would still
 * name a set that is not there - a notice written by an older build, which kept no sets - is
 * NOT restored at all: Undo says it can no longer be undone and leaves the working routine as
 * it is, rather than replacing it with one that cannot run.
 *
 * AND THE SETTING THE CHANGE CAME FROM GOES BACK WITH IT ({@link #restoreSettings}): a rewrite
 * made because a hold length moved (What it writes › Hold lengths, or the round-11 upgrade's
 * 45 s → 30 s fatigue holds) put the 45-second routine back while the setting stayed at 30 s,
 * so the plan offered the change again at once. The hold lengths the old routine was built
 * with are read from the signature it was minted under (Model#rxShapeTag's H and F tokens)
 * and set again. Only the hold lengths: they are the settings a rewrite notice can come from
 * that the signature states exactly.
 *
 * Pure: the app's Undo tap calls it and then decides what the plan's signature should say.
 */
public final class PlanUndo {
    private PlanUndo() { }

    /** {@link #restore}'s answers. */
    public static final int RESTORED = 0, NOTHING = 1, GONE = 2, INCOMPLETE = 3;

    /** What Undo says when the old routine's sets are gone (a notice from an older build). */
    public static final String CANNOT_UNDO_SETS = "That change can no longer be undone: the "
        + "routine it replaced is missing its sets. Your routine stays as the plan wrote it.";

    /**
     * Every set `r` plays, as a JSON array - written into the notice with the routine, before
     * the rewrite deletes any of them. "" when there is nothing to keep or it cannot be
     * written (Undo then restores only when nothing is missing).
     */
    public static String setsJson(Model m, Model.Routine r) {
        if (m == null || r == null) return "";
        try {
            JSONArray out = new JSONArray();
            java.util.List<String> seen = new java.util.ArrayList<String>();
            for (int i = 0; i < r.stages.size(); i++) {
                java.util.List<String> ids = r.stages.get(i).setIds;
                for (int j = 0; j < ids.size(); j++) {
                    String id = ids.get(j);
                    if (id == null || seen.contains(id)) continue;
                    seen.add(id);
                    Model.Set s = m.set(id);
                    if (s != null) out.put(s.toJson());
                }
            }
            return out.length() == 0 ? "" : out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Puts the routine the notice was written about back as it was: its sets first (each one
     * that is gone, from {@link Model.PlanNotice#prevSets}), then its stages, level, scale,
     * target and climb minutes, then the hold lengths it was built with. Returns
     * {@link #RESTORED}; {@link #NOTHING} when the notice kept no routine; {@link #GONE} when
     * the routine itself was deleted; {@link #INCOMPLETE} - and nothing changed - when a set
     * the old routine plays is neither in the library nor kept with the notice.
     */
    public static int restore(Model m, Model.PlanNotice n) {
        if (m == null || n == null || n.prevRoutine == null || n.prevRoutine.length() == 0)
            return NOTHING;
        Model.Routine back;
        try {
            back = Model.Routine.fromJson(new JSONObject(n.prevRoutine));
        } catch (Exception e) {
            back = null;
        }
        if (back == null) return NOTHING;
        Model.Routine live = m.routine(n.routineId);
        if (live == null) return GONE;
        // The sets first, so nothing below can point at one that is not there.
        java.util.List<Model.Set> added = new java.util.ArrayList<Model.Set>();
        if (n.prevSets != null && n.prevSets.length() > 0) {
            try {
                JSONArray a = new JSONArray(n.prevSets);
                for (int i = 0; i < a.length(); i++) {
                    Model.Set s = Model.Set.fromJson(a.getJSONObject(i));
                    if (s == null || s.id == null || s.id.length() == 0) continue;
                    if (m.set(s.id) != null) continue;      // still there: the live one stays
                    m.sets.add(s);
                    added.add(s);
                }
            } catch (Exception e) {
                // An unreadable copy restores nothing of its own; the check below decides.
            }
        }
        if (missingSets(m, back) > 0) {
            for (int i = 0; i < added.size(); i++) m.sets.remove(added.get(i));
            return INCOMPLETE;
        }
        live.stages.clear();
        for (int i = 0; i < back.stages.size(); i++) live.stages.add(back.stages.get(i));
        live.trainerLevel = back.trainerLevel;
        live.trainerScaleKpa = back.trainerScaleKpa;
        // The target and the uncounted climb minutes belong to the stages put back.
        live.netTargetMin = back.netTargetMin;
        live.climbUnderLineMin = back.climbUnderLineMin;
        restoreSettings(m, n.prevSig);
        // The sets the undone shape used are litter now, as after any reshape.
        m.gcOrphanSets();
        return RESTORED;
    }

    /** How many of the set ids `r`'s stages name are not in the library. */
    public static int missingSets(Model m, Model.Routine r) {
        if (m == null || r == null) return 0;
        int n = 0;
        for (int i = 0; i < r.stages.size(); i++) {
            java.util.List<String> ids = r.stages.get(i).setIds;
            for (int j = 0; j < ids.size(); j++) if (m.set(ids.get(j)) == null) n++;
        }
        return n;
    }

    /**
     * The hold lengths the routine minted under `sig` was built with, set again: the fatigue
     * hold where the track has a fatigue block (girth, from Level 3 - a signature from before
     * the setting existed built it at 45 s, SavedMint#applyShapeTag), the work hold where the
     * interval track reads it (Level 3 up). Nothing for an empty or unreadable signature.
     */
    public static void restoreSettings(Model m, String sig) {
        if (m == null || sig == null || sig.length() == 0) return;
        Mint.Rx rx = SavedMint.rxOf(sig);
        if (rx == null) return;
        int t = rx.track, level = rx.level;
        boolean girth = t == Plan.TRACK_GIRTH_INTERVAL || t == Plan.TRACK_GIRTH_TRADITIONAL;
        try {
            if (girth && Plan.fatigueBlockPresent(level)) {
                String f = SavedMint.shapeToken(sig, 'F');
                m.rxFatigueHoldSec = f == null ? Mint.HOLD_FATIGUE_SEC
                                               : Integer.parseInt(f.substring(1));
            }
            if (t == Plan.TRACK_GIRTH_INTERVAL && level >= Plan.L3) {
                String h = SavedMint.shapeToken(sig, 'H');
                m.rxHoldSec = h == null ? 0 : Integer.parseInt(h.substring(1));
            }
        } catch (RuntimeException e) {
            return;                              // a token cut short: leave the settings alone
        }
        m.clampRxShape();
    }
}
