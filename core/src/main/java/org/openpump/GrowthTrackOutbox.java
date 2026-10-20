package org.openpump;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * WHAT THE SYNC WORKS FROM (bug 8): an immutable snapshot taken ON THE MAIN THREAD from the
 * Model and the as-run recordings, handed to the GrowthTrack worker. The worker never calls
 * Store: Store's statics (the load-failed latch, the as-run cache) are single-threaded, and a
 * background read could orphan a recording or silently stop every later save.
 *
 * It carries, for the connection it was taken for:
 *  - every session id still on the phone, so a session deleted (or removed by a restore)
 *    while it waited is withdrawn and never uploaded (bug 6);
 *  - the sessions held back by the switches (GrowthTrackSettings), withdrawn from the queue
 *    while their switch is off;
 *  - for each other marked, unsettled session, its payload already drafted, or the reason it
 *    cannot be sent.
 *
 * Also the pure helpers the app needs around it: the filing marker, the connect page's
 * "N of M earlier sessions can be sent", adopting those earlier sessions for an account, and
 * one session's status line.
 */
public final class GrowthTrackOutbox {
    /** How the app reads one recording - on the main thread, inside {@link #take}. */
    public interface AsRunLookup { AsRun asRun(Model.Sess s); }

    /** One marked session, drafted. */
    public static final class Item {
        /** stableId of the record's own id (what the app shows status for), and the id it is
         *  sent under (its first part's, for a resumed or rejoined session). */
        public final String localId, externalId;
        /** Rises each time the record is replaced by a longer one (resumed / rejoined). */
        public final int revision;
        /** Null when it cannot be sent; then {@link #failure} says why. */
        final JSONObject payload;
        public final String failure;
        Item(String localId, String externalId, int revision, JSONObject payload, String failure) {
            this.localId = localId; this.externalId = externalId; this.revision = revision;
            this.payload = payload; this.failure = failure;
        }
        public boolean sendable() { return payload != null; }

        /** Drafts one record. Never throws for a bad record: the reason is the result. */
        public static Item of(Model.Sess s, AsRun run, Model model) {
            String local = localId(s), external = null;
            int revision = s == null ? 0 : Math.max(0, s.resumes) + Math.max(0, s.rejoins);
            try { external = GrowthTrackProtocol.stableId(GrowthTrackSession.syncId(s)); }
            catch (RuntimeException e) { return new Item(local, null, revision, null, "a finished record has no stable identifier"); }
            try {
                JSONObject payload = GrowthTrackSession.draft(s, run, model).payload();
                GrowthTrackClient.validateSession(payload);
                if (GrowthTrackClient.envelopeBytes(new JSONArray().put(payload)) > GrowthTrackProtocol.MAX_BYTES)
                    throw new IllegalArgumentException("The recording exceeds GrowthTrack's request size limit");
                return new Item(local, external, revision, payload, null);
            } catch (IllegalArgumentException | JSONException e) {
                return new Item(local, external, revision, null, e.getMessage() == null ? "Invalid recording" : e.getMessage());
            }
        }
    }

    public final String connectionId;
    /** Sending allowed at all (the master switch). Off: nothing is sent, nothing disconnects. */
    public final boolean sendingOn;
    /** stableIds of every session on the phone (own id and the id it is sent under). */
    public final Set<String> live;
    /** stableIds held back by a switch; their waiting payloads are withdrawn. */
    public final Set<String> heldBack;
    public final List<Item> items;

    private GrowthTrackOutbox(String connectionId, boolean sendingOn, Set<String> live, Set<String> heldBack, List<Item> items) {
        this.connectionId = connectionId; this.sendingOn = sendingOn;
        this.live = Collections.unmodifiableSet(live); this.heldBack = Collections.unmodifiableSet(heldBack);
        this.items = Collections.unmodifiableList(items);
    }

    /**
     * MAIN THREAD. The snapshot for one connection. `settled` is the client's last word on
     * which records are finished (GrowthTrackClient#settled: localId -> revision), so a phone
     * with a long history drafts only what is still open; null drafts everything marked.
     */
    public static GrowthTrackOutbox take(Model m, String connectionId, AsRunLookup lookup, Map<String, Integer> settled) {
        Set<String> live = new HashSet<String>(), held = new HashSet<String>();
        List<Item> items = new ArrayList<Item>();
        GrowthTrackSettings settings = m == null || m.gtSend == null ? new GrowthTrackSettings() : m.gtSend;
        String conn = connectionId == null ? "" : connectionId;
        if (m != null) for (Model.Sess s : m.sessLog.all) {
            if (s == null) continue;
            String local = localId(s);
            if (local != null) live.add(local);
            String external = externalId(s);
            if (external != null) live.add(external);
            if (conn.isEmpty() || !conn.equals(s.growthTrackConnectionId) || local == null) continue;
            int revision = Math.max(0, s.resumes) + Math.max(0, s.rejoins);
            Integer done = settled == null ? null : settled.get(local);
            if (done != null && done >= revision) continue;
            AsRun run = lookup == null ? null : lookup.asRun(s);
            if (settings.refusal(s, run, m) != null) { held.add(local); if (external != null) held.add(external); continue; }
            items.add(Item.of(s, run, m));
        }
        return new GrowthTrackOutbox(conn, settings.on, live, held, items);
    }

    /**
     * THE FILING MARKER: the connection a session filed now belongs to, or "" when it must
     * never be sent - a practice run (simulated), not connected, or switched off under the
     * settings it was filed with.
     */
    public static String markerFor(Model.Sess s, String connectionId, Model m, AsRun run) {
        if (s == null || s.sim || connectionId == null || connectionId.isEmpty()) return "";
        GrowthTrackSettings settings = m == null || m.gtSend == null ? new GrowthTrackSettings() : m.gtSend;
        return settings.refusal(s, run, m) == null ? connectionId : "";
    }

    /** "Already on this phone": M earlier real sessions, N of them sendable under the current
     *  switches. Earlier = filed before this connection, with no account's marker (a session
     *  marked for another account is never offered: it is that account's). */
    public static final class Earlier {
        public final int sendable, total;
        final List<Model.Sess> sessions;
        Earlier(int sendable, int total, List<Model.Sess> sessions) { this.sendable = sendable; this.total = total; this.sessions = sessions; }
        /** The radio's count, "N of M can be sent". */
        public String line() { return sendable + " of " + total + " can be sent"; }
    }

    /** MAIN THREAD (it reads recordings). */
    public static Earlier earlier(Model m, AsRunLookup lookup) {
        List<Model.Sess> ok = new ArrayList<Model.Sess>();
        int total = 0;
        if (m == null) return new Earlier(0, 0, ok);
        GrowthTrackSettings settings = m.gtSend == null ? new GrowthTrackSettings() : m.gtSend;
        for (Model.Sess s : m.sessLog.all) {
            if (s == null || s.sim || (s.growthTrackConnectionId != null && !s.growthTrackConnectionId.isEmpty())) continue;
            total++;
            AsRun run = lookup == null ? null : lookup.asRun(s);
            if (settings.refusal(s, run, m) != null) continue;
            if (Item.of(s, run, m).sendable()) ok.add(s);
        }
        return new Earlier(ok.size(), total, ok);
    }

    /** MAIN THREAD. "Also send earlier ones": marks the sendable earlier sessions for this
     *  connection; the caller saves the Model, and the next sync sends them. Returns how many. */
    public static int adoptEarlier(Model m, String connectionId, AsRunLookup lookup) {
        if (connectionId == null || connectionId.isEmpty()) return 0;
        Earlier e = earlier(m, lookup);
        for (Model.Sess s : e.sessions) s.growthTrackConnectionId = connectionId;
        return e.sessions.size();
    }

    /** One session's line in "Sent and waiting sessions". */
    public static final class Status {
        public static final int SENT = 1, WAITING = 2, NOT_SENT = 3;
        public final int state; public final String text;
        Status(int state, String text) { this.state = state; this.text = text; }
    }
    /** Pure: the record, the client's word on it (GrowthTrackClient#state) and the switches. */
    public static Status statusOf(Model.Sess s, GrowthTrackClient.Snapshot snap, GrowthTrackClient.SessionState known, Model m) {
        if (s == null) return new Status(Status.NOT_SENT, "Not sent");
        if (s.sim) return new Status(Status.NOT_SENT, "Not sent: practice run");
        String receipt = known == null ? "" : known.receipt;
        boolean queued = known != null && known.queued;
        if (receipt.startsWith("Received") && !queued) return new Status(Status.SENT, "Sent");
        String marker = s.growthTrackConnectionId == null ? "" : s.growthTrackConnectionId;
        if (marker.isEmpty()) return new Status(Status.NOT_SENT, "Not sent: filed while not connected or switched off");
        if (snap == null || !marker.equals(snap.connectionId)) return new Status(Status.NOT_SENT, "Not sent: belongs to an earlier connection");
        GrowthTrackSettings settings = m == null || m.gtSend == null ? new GrowthTrackSettings() : m.gtSend;
        String held = settings.refusal(s, null, m);
        if (held != null && !queued) return new Status(Status.NOT_SENT, "Not sent: " + held);
        if (queued) return new Status(Status.WAITING, "Waiting");
        if (receipt.startsWith("Not synced: ")) return new Status(Status.NOT_SENT, "Not sent: " + receipt.substring("Not synced: ".length()));
        if (receipt.startsWith("Needs review")) return new Status(Status.NOT_SENT, "Not sent: " + receipt);
        return new Status(Status.WAITING, "Waiting");
    }

    static String localId(Model.Sess s) {
        try { return s == null ? null : GrowthTrackProtocol.stableId(s.id); } catch (RuntimeException e) { return null; }
    }
    static String externalId(Model.Sess s) {
        try { return s == null ? null : GrowthTrackProtocol.stableId(GrowthTrackSession.syncId(s)); } catch (RuntimeException e) { return null; }
    }
}
