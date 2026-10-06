package org.openpump;

/**
 * ONE DECISION CARD AT A TIME (polish TR-1).
 *
 * The Trainer opened on an upgrader's phone with two lime primaries stacked: "The plan changed
 * — run the trainer setup again" directly above "Were you on a deload?" - two questions and two
 * buttons shouting for the same eye, and Today showed the deload card behind the update sheet.
 * The owner's rule: the most urgent card shows, marked "1 of 2"; the others wait as one-line
 * rows.
 *
 * THE ORDER IS FIXED: safety, then the deload question, then the due deload, then the upgrade.
 * It is a property of the PLAN, not of the order a screen happens to draw its cards in - the
 * upgrade card is drawn first on the Trainer and still yields to a deload card drawn below it.
 * So this is computed once, up front, from the same tests each card already asks, and every
 * card asks the one question {@link #decisionShownAbove}: is a more urgent card showing? If it
 * is, draw yourself as a one-line row; if not, you are the card.
 *
 * Pure, so the order is pinned in DecisionQueueTest. It decides nothing about the plan and
 * hides nothing: a waiting card is still reachable as its row.
 *
 * THE SHARED INTERFACE (the lanes' contract): the Trainer (TrainerScreen) and Today and the
 * deload cards (SessionActivity) each build {@code DecisionQueue.at(model, now,
 * TrainerScreen.planT10LaterThisRun)} and ask it; neither keeps a flag of its own.
 */
public final class DecisionQueue {

    /** The kinds, in priority order - a lower number shows first. */
    public static final int SAFETY = 0, DELOAD_ASK = 1, DELOAD_DUE = 2, UPGRADE = 3;
    static final int KINDS = 4;

    private final boolean[] waiting = new boolean[KINDS];

    /** An empty queue: nothing waiting. Screens use {@link #at}; tests set kinds directly. */
    public DecisionQueue() { }

    /**
     * The queue as the plan stands at `nowMs`, from the tests the cards themselves ask:
     * the safety hold (Model#trainerState), "Were you on a deload?" (Deload#askGapStartMs),
     * the due deload (TrainerTab#cadenceDeloadDue) and the upgrade card
     * (PlanCards#upgradeCardShown, with the Trainer's "Later" for this run).
     */
    public static DecisionQueue at(Model m, long nowMs, boolean upgradeLaterThisRun) {
        DecisionQueue q = new DecisionQueue();
        if (m == null) return q;
        q.waiting[SAFETY] = m.trainerState == Model.TRAINER_STATE_SAFETY_FLAG;
        q.waiting[DELOAD_ASK] = Deload.askGapStartMs(m, nowMs) > 0L;
        q.waiting[DELOAD_DUE] = TrainerTab.cadenceDeloadDue(m, nowMs);
        q.waiting[UPGRADE] = PlanCards.upgradeCardShown(m, upgradeLaterThisRun);
        return q;
    }

    /** Marks a kind waiting or not - for tests, and for a screen that knows better. */
    public DecisionQueue set(int kind, boolean isWaiting) {
        if (kind >= 0 && kind < KINDS) waiting[kind] = isWaiting;
        return this;
    }

    /** Whether this kind's card has anything to ask at all. */
    public boolean waiting(int kind) {
        return kind >= 0 && kind < KINDS && waiting[kind];
    }

    /** How many cards are waiting. */
    public int count() {
        int n = 0;
        for (int i = 0; i < KINDS; i++) if (waiting[i]) n++;
        return n;
    }

    /** The kind drawn as the full card, or -1 when nothing waits. */
    public int first() {
        for (int i = 0; i < KINDS; i++) if (waiting[i]) return i;
        return -1;
    }

    /**
     * THE ONE QUESTION EVERY DECISION CARD ASKS: is a more urgent card showing above me?
     * True means "draw yourself as a one-line row"; false means "you are the card" (or you
     * have nothing to ask - check {@link #waiting} first, as every card already does).
     */
    public boolean decisionShownAbove(int kind) {
        int f = first();
        return f >= 0 && f < kind;
    }

    /**
     * The micro-label over the full card when others wait: "1 OF 2". Empty when this kind is
     * not the full card or is the only one waiting - a lone card needs no count. Already in
     * capitals, as Ui.microLabel draws it.
     */
    public String position(int kind) {
        int n = count();
        if (n < 2 || first() != kind) return "";
        return "1 OF " + n;
    }
}
