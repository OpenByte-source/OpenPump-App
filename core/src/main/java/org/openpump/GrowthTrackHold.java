package org.openpump;

import java.util.HashMap;
import java.util.Map;

/**
 * WAIT UNTIL FINAL (the owner's decision, 10 Nov 2026). GrowthTrack keeps the first copy of a
 * session it is sent: the same id sent again is answered "already present" and nothing on it
 * changes - no sets added, no duration or completion updated. STOP filed its part and synced at
 * once, so a Resume (or a rejoin after the app was lost) made a whole session GrowthTrack never
 * took, while the phone said "Sent".
 *
 * So a filed session that can still be continued is HELD: not drafted, not sent, and withdrawn
 * from the queue should it already be there (GrowthTrackOutbox#take). Once that chance is over it
 * goes on the next pass, once, whole. The ways back, and what decides each:
 *  - RESUME: STOP's offer (ResumeOffer#held) - from the filing, through "Count this toward
 *    training?", until the offer's time is up. In memory: with the screen gone no Resume can
 *    come, and the part is final.
 *  - REJOIN: the run snapshot carries a filed part (a screen destroyed mid-run files one) and a
 *    cold open would still offer it ({@link #snapshot}); or that offer is up on screen.
 *  - RUNNING: a resumed or rejoined run is playing now; its earlier part is replaced when it files.
 * Nothing here can keep a session forever: each way back ends by itself (the offer's time, the
 * snapshot's age, the process), and a lost hold reads as final.
 *
 * Logging only: nothing here reaches the pump, and STOP never waits on it. Pure.
 */
public final class GrowthTrackHold {
    private GrowthTrackHold() { }

    public static final int NONE = 0, RESUME = 1, REJOIN = 2, RUNNING = 3;

    /** The row's words in "Sent and waiting sessions", or null when not held. */
    public static String words(int hold) {
        switch (hold) {
            case RESUME: return "Waiting · can still be resumed";
            case REJOIN: return "Waiting · can still be rejoined";
            case RUNNING: return "Waiting · sends when the run ends";
            default: return null;
        }
    }

    /**
     * The filed part a run snapshot would bring back, or null: only while a cold open would
     * still offer it (SessionActivity#offerRunSnapshot's own tests - a step, not a manual run,
     * its routine still there, started and not older than `maxAgeMs`).
     */
    public static String snapshot(Model m, String routineId, int idx, boolean manual, long started,
                                  String partsCode, long now, long maxAgeMs) {
        if (routineId == null || idx < 0 || manual) return null;
        if (m == null || m.routine(routineId) == null) return null;
        if (started <= 0 || now - started > maxAgeMs) return null;
        RunParts parts = RunParts.fromCode(partsCode);
        return parts == null || parts.sessId == null || parts.sessId.isEmpty() ? null : parts.sessId;
    }

    /** Session id -> why it is held, from each way back (null or "" for one not in play). A
     *  part being continued right now says so over any other. */
    public static Map<String, Integer> of(String running, String offered, String asked, String snapshot) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        put(out, snapshot, REJOIN);
        put(out, asked, REJOIN);
        put(out, offered, RESUME);
        put(out, running, RUNNING);
        return out;
    }

    /** One record's hold in `holds` (null: none). */
    public static int kind(Map<String, Integer> holds, Model.Sess s) {
        Integer k = holds == null || s == null || s.id == null ? null : holds.get(s.id);
        return k == null ? NONE : k.intValue();
    }

    private static void put(Map<String, Integer> out, String id, int why) {
        if (id != null && !id.isEmpty()) out.put(id, why);
    }
}
