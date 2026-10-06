package org.openpump;

/**
 * Q5 - A PUMP MADE OF ARITHMETIC.
 *
 * Ten device tests have been queued for months and none of them can run. An emulator has no
 * Bluetooth, so the app cannot be driven end to end anywhere but on the desk with the real
 * cuff attached - which means every change is either tested by hand or not tested at all,
 * and the ones that were not tested by hand are the ones that produced the rest-period bug,
 * the dead re-arm after an inserted rest, and the stitched-hold miscount.
 *
 * THIS IS THE HALF THAT CAN BE TRUSTED WITHOUT HARDWARE. Feed it the bytes the app writes,
 * tick it forward, and it answers with the frames the device answers with: acks, work-mode
 * dumps, and telemetry in the device's own ASCII CSV. It is PURE - no Android import - so
 * test.sh compiles it and the desktop self-test can drive a whole routine through it in
 * milliseconds and assert what came out.
 *
 * IT IS A MODEL OF THE FIRMWARE'S BEHAVIOUR, not of its internals, and it models the
 * behaviours the app has actually been bitten by:
 *
 *   THE SLOT TABLE holds nine presets. Add APPENDS and returns an ack; Delete COMPACTS, so
 *   deleting slot 0 moves everything down - the app's own table-rewrite path depends on
 *   that and had no way to test it.
 *
 *   STOP VENTS. It is a release, not a pause, at the measured 4.68 kPa/s. The pump pulling
 *   through a rest was a stop that was ISSUED AND IGNORED, and nothing noticed for weeks
 *   because nothing could watch a stop happen without a cuff in the room.
 *
 *   A LONG UPPER HOLD COASTS. The firmware drifts down during one (13.1 -> 9.0 kPa in the
 *   build-17 log) rather than holding the setpoint exactly, which is why a hold looks
 *   under-delivered on the chart and why the tolerance band exists at all. HOW FAST is
 *   unmeasured (release checklist H14); {@link #COAST_KPA_PER_S} is this simulator's own
 *   choice, not a measurement.
 *
 *   TELEMETRY IS SIGNED, DECI-kPa, AND ~4.16 Hz. All three have caused bugs. The sign in
 *   particular: the wire sends a negative decimal and the app takes the magnitude, so a
 *   simulator that emitted positives would silently agree with a broken parser.
 *
 * WHAT IT IS NOT: a transport. Standing it in for {@link PumpLink} means giving the app a
 * seam where the BLE object is, and that seam runs through the vent-confirmation wiring the
 * WiringCheck invariants guard. That is its own change, deliberately not bundled with this
 * one - a simulator nobody has driven yet is not something to hand the stop path.
 */
public final class SimPump {

    /** Measured on hardware: StopWork releases at this rate. */
    public static final double VENT_KPA_PER_S = 4.68;
    /** How fast the pump pulls down toward an upper setpoint, at 100% speed. */
    public static final double PULL_KPA_PER_S_AT_FULL = 3.2;
    /** How fast it releases toward a lower setpoint between cycles. */
    public static final double DROP_KPA_PER_S = 5.0;
    /** The coast during a long upper hold - the firmware does not hold exactly. A SIMULATOR
     *  CHOICE, not a measurement: the real rate is unmeasured (release checklist H14). At
     *  this rate a 255 s hold drifts about 3.6 kPa, the size of the one drift on record. */
    public static final double COAST_KPA_PER_S = 0.014;
    /** Telemetry cadence, ~4.16 Hz. */
    public static final int TELEM_PERIOD_MS = 240;

    /**
     * HOW FAR THE SIMULATOR ADVANCES WHEN ITS TIMER FIRES: the real monotonic time since the
     * last firing, never negative, and at most {@link #MAX_REAL_STEP_MS}.
     *
     * PumpLink's timer runs on the UI thread. It advanced a fixed {@link #TELEM_PERIOD_MS}
     * per firing, and under load the timer fired about every 420 ms - so the simulator ran at
     * about 0.57x real time and a 120 s hold took about 211 s. Advancing by the time that
     * actually passed keeps it on the wall clock. The cap is for a long stall (a debugger, a
     * frozen UI thread): a simulator that jumped minutes in one step would skip a whole hold
     * the app never saw. The simulator only - nothing sent to a real pump reads this.
     */
    public static long realStepMs(long lastMs, long nowMs) {
        long d = nowMs - lastMs;
        return d <= 0 ? 0 : Math.min(d, MAX_REAL_STEP_MS);
    }

    /** The most one timer firing may advance the simulator, in ms - see {@link #realStepMs}. */
    public static final long MAX_REAL_STEP_MS = 2_000;

    /** One stored preset - what {@link Proto#addPreset} put on the wire. */
    public static final class Slot {
        public final int speedPct, upperKpa, upperHoldS, lowerKpa, lowerHoldS;
        Slot(int sp, int up, int uh, int lo, int lh) {
            speedPct = sp; upperKpa = up; upperHoldS = uh; lowerKpa = lo; lowerHoldS = lh;
        }
    }

    private final java.util.List<Slot> slots = new java.util.ArrayList<Slot>();
    private final java.util.List<byte[]> outbox = new java.util.ArrayList<byte[]>();

    private double kpa = 0.0;          // current vacuum magnitude
    /** The table index of the entry being run, or -1: idle, or the entry was deleted under
     *  it. `current` is what the pump is actually cycling, a copy: deleting its entry does
     *  not stop it (H13 - the owner's journal of 22 Sep). */
    private int running = -1;
    private Slot current = null;
    private boolean venting = false;
    private double phaseSec = 0.0;     // elapsed inside the current phase
    /** Whether the pull to the upper setpoint is still running. Latched: the device pulls
     *  once and then holds the valve, so what follows is a coast, not a top-up. */
    private boolean pulling = true;

    private boolean atUpper = false;   // which half of the cycle
    private long clockMs = 0L;
    private long lastTelemMs = -TELEM_PERIOD_MS;

    /** Every frame the app has written, in order - so a test can assert what was SENT as
     *  well as what came back. The app's own logs cannot be asserted; this can. */
    public final java.util.List<byte[]> written = new java.util.ArrayList<byte[]>();

    public double pressureKpa() { return kpa; }
    public boolean isRunning()  { return current != null; }

    /** The preset it is running, or null: what a test compares the screen with. */
    public Slot running()       { return current; }

    /**
     * A TEST HOOK: WHICH ANSWERS THE PUMP FAILS TO SEND - while it still does what it was told,
     * as the real pump does. The owner's journal of 22 Sep has 343 STARTs and 319 answers; the
     * guided start's STARTs pulled the cuff to 17 kPa with no answer, and answers went missing
     * in bursts (5 STARTs, 3 answers; 2 and 1; 2 and 0). The command is carried out whatever
     * this says; only the two-byte answer is lost. Null (the default): every answer is sent.
     */
    public interface ReplyLoss {
        /** Is the answer to this opcode (`status` 0x01 or Proto#REFUSED) lost? */
        boolean lose(int op, int status);
    }

    private ReplyLoss replyLoss;

    public void setReplyLoss(ReplyLoss r) { replyLoss = r; }

    /**
     * A TEST HOOK: WHEN AN ANSWER IS SENT - LATE, as the real pump's sometimes are (26 to 1033
     * ms after the START in the owner's journal of 22 Sep). The command is carried out at once;
     * only its two-byte answer waits. ANSWERS KEEP THEIR ORDER: one is never sent before an
     * earlier one, as a pump that answers its frames one at a time cannot. Null (the default):
     * every answer at once.
     */
    public interface ReplyDelay {
        /** How long after the command is carried out its answer (`status` 0x01 or
         *  Proto#REFUSED) is sent, in ms. */
        long delayMs(int op, int status);
    }

    private ReplyDelay replyDelay;

    public void setReplyDelay(ReplyDelay d) { replyDelay = d; }

    /**
     * A TEST HOOK: THE PUMP'S EXPLICIT NO TO A START IT COULD CARRY OUT - `2C FD`, and nothing
     * changes, exactly as for an empty slot. The journal's refusals are all explained by a
     * table that held something other than what the app believed; this lets a test refuse any
     * START, so what the app does with an FD can be checked wherever the FD lands. Null (the
     * default): only a START of an empty slot is refused.
     */
    public interface Refusal {
        /** Does the pump refuse this START of stored entry `slot`? */
        boolean refuse(int slot);
    }

    private Refusal refusal;

    public void setRefusal(Refusal r) { refusal = r; }

    /**
     * A TEST HOOK: A TABLE THAT IS NOT WHAT WAS WRITTEN. An Add answered and not stored - as the
     * owner's pump did (the journal of 22 Sep: its STARTs, every refusal included, are explained
     * only by a table one entry short of what the app counted). Every entry after it sits one
     * index lower than the app believes, so a START of an index runs the entry after the one it
     * meant, or finds the slot empty and is refused (`2C FD`). Nothing but a refusal, or a table
     * written again, shows it. Null (the default): every Add with room is stored.
     */
    public interface AddLoss {
        /** Is this Add (the whole frame) answered and not stored? */
        boolean lose(byte[] frame);
    }

    private AddLoss addLoss;

    public void setAddLoss(AddLoss a) { addLoss = a; }

    /**
     * A TEST HOOK: THE LINK'S PACE. With a frame time above zero, a frame is carried out that
     * long after the one before it, not the moment it is written - so a START written behind a
     * table rewrite (the Deletes and the Adds) reaches the pump well after it was written, as it
     * does over the real link, and whatever the app did meanwhile has already happened.
     * #setOnCarriedOut is told as each one is. Zero (the default): every frame at once.
     */
    public void setFrameMs(long ms) { frameMs = Math.max(0L, ms); }

    /** Told as each frame is carried out (#setFrameMs) - a test's write-done. */
    public interface CarriedOut { void carriedOut(byte[] frame); }

    public void setOnCarriedOut(CarriedOut c) { onCarriedOut = c; }

    /** Frames written and not yet carried out. */
    public int inFlight() { return inbound.size(); }

    /** Answers held back (#setReplyDelay), not yet sent. */
    public int answersHeld() { return held.size(); }

    private long frameMs = 0L;
    private CarriedOut onCarriedOut;
    private final java.util.ArrayDeque<byte[]> inbound = new java.util.ArrayDeque<byte[]>();
    private long nextFrameAt = 0L;
    /** Answers waiting for their time, in the order they will be sent: {dueMs, frame}. */
    private final java.util.ArrayDeque<Object[]> held = new java.util.ArrayDeque<Object[]>();
    private long lastHeldDue = 0L;
    public boolean isVenting()  { return venting; }
    public int slotCount()      { return slots.size(); }
    public Slot slot(int i)     { return (i >= 0 && i < slots.size()) ? slots.get(i) : null; }

    /**
     * Hand it a frame the app wrote. Returns immediately; anything the device would answer
     * with is queued for {@link #drain}, because the real device answers on its own
     * notification channel rather than as a return value, and a simulator that returned
     * answers directly would let a test pass code that could never work on hardware.
     */
    public void write(byte[] frame) {
        if (frame == null || frame.length < 3) return;
        written.add(frame.clone());
        if (frameMs > 0) {
            if (inbound.isEmpty()) nextFrameAt = clockMs + frameMs;
            inbound.addLast(frame.clone());
            return;
        }
        carryOut(frame);
    }

    /** What the pump does with a frame, the moment it reaches it. */
    private void carryOut(byte[] frame) {
        if (onCarriedOut != null) onCarriedOut.carriedOut(frame.clone());
        if ((frame[0] & 0xFF) != 0x66 || (frame[1] & 0xFF) != 0x2A) return;
        int op = frame[2] & 0xFF;
        switch (op) {
            case Proto.OP_ADD:
                /* AN ACK IS "I STORED IT", AND A FULL TABLE STORES NOTHING.
                 *
                 * This acked whatever it was sent - a short frame, or an Add against a table
                 * already holding Proto.SLOTS presets - so the app's table-rewrite believed
                 * every preset above the eighth had landed and ran a routine whose later
                 * sets did not exist on the device. No refusal of an Add has been seen (the
                 * pump's only one on record is `2C FD`, a Start of an empty slot): silence is
                 * what a device that did not accept a write leaves behind here, and silence is
                 * what the app's write timeout is there to catch. */
                if (frame.length < 8 || slots.size() >= Proto.SLOTS) break;
                /* AN ALL-ZERO PRESET IS NOT STORED - as the real pump does (the owner's journal
                 * of 22 Sep: its STARTs are explained, every refusal included, only by a table
                 * that never held the app's Rest placeholder `66 2A 2B 00 00 00 00 00`). The
                 * Add is answered like any other (that journal never shows `2B FD`); it simply
                 * takes no entry, so every entry after it sits one index lower than the app
                 * that wrote it believed. PumpRefusalTest. */
                if ((frame[3] | frame[4] | frame[5] | frame[6] | frame[7]) == 0) { ack(op); break; }
                if (addLoss != null && addLoss.lose(frame.clone())) { ack(op); break; }
                slots.add(new Slot(Proto.speedPct(frame[3] & 0xFF),
                                   frame[4] & 0xFF, frame[5] & 0xFF,
                                   frame[6] & 0xFF, frame[7] & 0xFF));
                ack(op);
                break;
            case Proto.OP_DELETE:
                // COMPACTS. Deleting slot 0 moves every other preset down one, which is the
                // behaviour the app's table-rewrite depends on and could never test.
                if (frame.length >= 4) {
                    int idx = frame[3] & 0xFF;
                    if (idx >= 0 && idx < slots.size()) {
                        slots.remove(idx);
                        // DELETING THE ENTRY IT IS RUNNING DOES NOT STOP IT. The real pump
                        // keeps cycling that step (release check H13, answered by the owner's
                        // journal of 22 Sep: a table rewrite deleted the running step's entry
                        // and the cuff went on cycling 24/19 kPa for 54 s). This used to vent,
                        // so on the simulator a "This set" edit of a ramp vented the cuff.
                        if (running == idx) running = -1;
                        else if (running > idx) running--;
                    }
                }
                ack(op);
                break;
            case Proto.OP_START:
                if (frame.length >= 4) {
                    int s = frame[3] & 0xFF;
                    if (s >= 0 && s < slots.size() && refusal != null && refusal.refuse(s)) {
                        refuse(op);                // an explicit no: nothing changes
                        break;
                    }
                    if (s >= 0 && s < slots.size()) {
                        running = s; current = slots.get(s); venting = false;
                        phaseSec = 0.0; atUpper = true; pulling = true;
                    } else {
                        /* A START OF AN EMPTY SLOT IS REFUSED: `2C FD`, and nothing changes -
                         * the pump goes on with whatever it was running. As the real pump
                         * answered 23 times in the owner's journal (Proto#REFUSED). */
                        refuse(op);
                        break;
                    }
                } else { refuse(op); break; }
                ack(op);
                break;
            case Proto.OP_STOP:
                // EVERY STOPWORK THIS RECEIVES VENTS IT (WiringCheck invariant 91). A stop lost
                // on its way is modelled by not delivering it - the debug build's SimStopLoss,
                // or a test that never writes it - never by a simulator that ignores one.
                stopNow();
                ack(op);
                break;
            case Proto.OP_LIST:
                dumpSlots();
                break;
            default:
                ack(op);
                break;
        }
    }

    private void stopNow() {
        running = -1;
        current = null;
        // A STOP IS A RELEASE, NOT A PAUSE. Leaving the pressure where it was would model a
        // pump that holds vacuum with its motor off, and the whole reason the rest-period
        // bug went unseen is that nobody could watch this happen.
        venting = kpa > 0.0;
    }

    private void ack(int op) {
        if (replyLoss != null && replyLoss.lose(op, 0x01)) return;   // done, not answered
        answer(new byte[]{ (byte) op, 0x01 });
    }

    /** Sends an answer now, or holds it for its delay - never ahead of one held before it. */
    private void answer(byte[] a) {
        long d = replyDelay == null ? 0L : Math.max(0L, replyDelay.delayMs(a[0] & 0xFF, a[1] & 0xFF));
        if (d == 0L && held.isEmpty()) { outbox.add(a); return; }
        long due = Math.max(clockMs + d, lastHeldDue);
        lastHeldDue = due;
        held.addLast(new Object[]{ Long.valueOf(due), a });
    }

    /** The pump's explicit NO (Proto#REFUSED). */
    private void refuse(int op) {
        if (replyLoss != null && replyLoss.lose(op, Proto.REFUSED)) return;
        answer(new byte[]{ (byte) op, (byte) Proto.REFUSED });
    }

    /**
     * The work-mode dump the hardware answers OP_LIST with: opcode, then each preset's five
     * bytes in slot order.
     *
     * THERE IS NO COUNT BYTE. This wrote one, so every record it returned was shifted one
     * position and a parser reading the real device's frames read this one wrong - the
     * simulator disagreeing with the protocol it exists to stand in for, which is the one
     * thing a simulator must never do. A table of no presets is a one-byte frame, which is
     * exactly what "no presets" looks like on the wire.
     */
    private void dumpSlots() {
        byte[] b = new byte[1 + slots.size() * 5];
        b[0] = (byte) Proto.OP_LIST;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            int at = 1 + i * 5;
            b[at]     = (byte) Proto.speedCode(s.speedPct);
            b[at + 1] = (byte) s.upperKpa;
            b[at + 2] = (byte) s.upperHoldS;
            b[at + 3] = (byte) s.lowerKpa;
            b[at + 4] = (byte) s.lowerHoldS;
        }
        outbox.add(b);
    }

    /**
     * Advance the pump by {@code ms} milliseconds, emitting telemetry at the device's own
     * cadence. Time is handed in rather than read from a clock so a whole forty-minute
     * routine runs in a test in microseconds, and so nothing here is flaky.
     */
    public void tick(long ms) {
        long end = clockMs + Math.max(0L, ms);
        while (clockMs < end) {
            long step = Math.min(TELEM_PERIOD_MS, end - clockMs);
            // Frames and held answers land at their own instants, not a telemetry period late.
            if (!inbound.isEmpty()) step = Math.max(0L, Math.min(step, nextFrameAt - clockMs));
            if (!held.isEmpty())
                step = Math.max(0L, Math.min(step, ((Long) held.peekFirst()[0]).longValue() - clockMs));
            advance(step / 1000.0);
            clockMs += step;
            while (!inbound.isEmpty() && nextFrameAt <= clockMs) {
                carryOut(inbound.pollFirst());
                nextFrameAt += frameMs;
            }
            while (!held.isEmpty() && ((Long) held.peekFirst()[0]).longValue() <= clockMs)
                outbox.add((byte[]) held.pollFirst()[1]);
            if (clockMs - lastTelemMs >= TELEM_PERIOD_MS) {
                lastTelemMs = clockMs;
                outbox.add(telemetry());
            }
        }
    }


    private void advance(double sec) {
        if (venting) {
            kpa = Math.max(0.0, kpa - VENT_KPA_PER_S * sec);
            if (kpa <= 0.0) venting = false;
            return;
        }
        if (current == null) return;
        Slot s = current;
        phaseSec += sec;
        if (atUpper) {
            double pull = PULL_KPA_PER_S_AT_FULL * (s.speedPct / 100.0);
            /* THE PULL IS LATCHED, AS THE FIRMWARE'S IS.
             *
             * This re-pulled the moment the reading fell more than 0.05 kPa below the
             * setpoint - so the coast it exists to model could never exceed a twentieth of a
             * kPa, and the drift the tolerance band is written for was invisible in every
             * test. The device pulls ONCE to the setpoint and then holds the valve; the one
             * hardware log on record shows a drift of about 4 kPa during a hold, at a rate
             * nobody has measured (H14). */
            if (pulling && kpa < s.upperKpa) {
                kpa = Math.min(s.upperKpa, kpa + pull * sec);
                if (kpa >= s.upperKpa) pulling = false;
            } else {
                // AT THE SETPOINT IT COASTS, which is what the hardware log shows. A
                // simulator that pinned the setpoint would make every hold look perfect and
                // hide the exact drift the tolerance band exists for.
                kpa = Math.max(0.0, kpa - COAST_KPA_PER_S * sec);
            }
            if (phaseSec >= s.upperHoldS) { atUpper = false; phaseSec = 0.0; }
        } else {
            if (kpa > s.lowerKpa + 0.05) kpa = Math.max(s.lowerKpa, kpa - DROP_KPA_PER_S * sec);
            if (phaseSec >= s.lowerHoldS) { atUpper = true; phaseSec = 0.0; pulling = true; }
        }
    }

    /**
     * One telemetry frame in the device's ASCII CSV.
     *
     * NEGATIVE ON THE WIRE, deci-kPa, exactly as the hardware sends it. This is not a
     * detail: the app takes the magnitude, and a simulator that emitted positives would
     * agree just as happily with a parser that had lost the abs() - so the one bug this
     * frame could catch is the one it would hide.
     */
    private byte[] telemetry() {
        int deci = (int) Math.round(kpa * 10.0);
        String mode = current != null ? "AUTO" : "IDLE";
        int spd = current != null ? Proto.speedCode(current.speedPct) : 0;
        String s = "#" + mode + "," + (deci == 0 ? "0" : "-" + deci) + "," + spd + ",0";
        return s.getBytes();
    }

    /** Everything the device would have notified since the last call, oldest first. */
    public java.util.List<byte[]> drain() {
        java.util.List<byte[]> out = new java.util.ArrayList<byte[]>(outbox);
        outbox.clear();
        return out;
    }

    /** The telemetry samples out of {@link #drain}, already parsed - the shape a listener
     *  actually consumes. Non-telemetry frames are dropped, which is what the app does with
     *  them on the sample path too. */
    public java.util.List<Proto.Sample> drainSamples() {
        java.util.List<Proto.Sample> out = new java.util.ArrayList<Proto.Sample>();
        java.util.List<byte[]> raw = drain();
        for (int i = 0; i < raw.size(); i++) {
            Proto.Sample s = Proto.parse(raw.get(i));
            if (s != null) out.add(s);
        }
        return out;
    }
}
