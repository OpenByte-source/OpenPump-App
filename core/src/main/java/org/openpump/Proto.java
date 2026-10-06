package org.openpump;

/**
 * The ZD21 wire protocol, spoken by the Epic Hydro PE Pump. Pure functions — no Android,
 * no state — so the parts most likely to be wrong are the parts easiest to check.
 *
 * Every frame is  0x66 0x2A <opcode> [payload].
 * Add, Delete and Start are acknowledged as <opcode> 0x01. List is answered by the table
 * dump, not by an ack. Whether EVERY Stop is acknowledged is unknown (release checklist
 * H15), so nothing here ever waits for one: only telemetry evidences a vent.
 * Telemetry arrives unprompted at ~4.16 Hz once the CCCD on FFF4 is written, as ASCII
 * CSV:  #<mode>,<deciKpa>,<speedCode>,<intelligent>
 *
 * Confirmed against hardware (build 17 log, 2026-08-16):
 *   - pressure is DECI-kPa: the raw 240 is 24.0 kPa, not 240 (atmospheric ~101 kPa is
 *     the hard ceiling, so 240 kPa of vacuum is impossible)
 *   - StopWork VENTS — it is a release, not a pause — at about 4.68 kPa/s in that log.
 *     The rate has not been re-measured since (the hardware validation's step 3 measures
 *     it); release checklist H10, passed on the owner's log, confirms that STOP vents and
 *     that telemetry shows it, not the rate
 *   - the firmware coasts during a long upper hold (13.1 -> 9.0 kPa in that log). How
 *     fast is UNMEASURED: release checklist H14 measures it
 *   - the slot table holds 9 presets; Add APPENDS, Delete COMPACTS
 */
public final class Proto {

    private Proto() { }

    public static final int OP_DELETE  = 0x2A;   // delete one preset (index) — compacts
    public static final int OP_ADD     = 0x2B;   // add a preset              — appends
    public static final int OP_START   = 0x2C;   // start a preset (slot)
    public static final int OP_STOP    = 0x2D;   // stop                      — VENTS
    public static final int OP_LIST    = 0x29;   // list the presets
    public static final int OP_TELEM   = 0x23;   // telemetry (ASCII '#')
    public static final int OP_TIME    = 0x25;   // set time (never sent)

    public static final int SLOTS      = 9;      // the device's preset table size

    /* ---------------------------------------------------------------- speed */

    /**
     * The speed codec. NEVER put a raw percentage on the wire — the device wants a
     * 0..255 code (docs/protocols/zd21.md). With this rounding every whole percentage
     * encodes to a code that {@link #speedPct} decodes back to the same percentage.
     */
    public static int speedCode(int pct) {
        if (pct < 0)   pct = 0;
        if (pct > 100) pct = 100;
        return (pct * 255 + 50) / 100;
    }

    /** Inverse, for decoding what the device echoes back. */
    public static int speedPct(int code) {
        if (code < 0)   code = 0;
        if (code > 255) code = 255;
        return (code * 100 + 127) / 255;
    }

    /* --------------------------------------------------------------- frames */

    public static byte[] cmd(int op) {
        return new byte[]{0x66, 0x2A, (byte) op};
    }

    /**
     * Append a preset. Pressures are WHOLE kPa on the wire (telemetry is deci-kPa;
     * the setpoints are not). Holds are seconds, 0..255.
     */
    /** The longest hold ONE PRESET can carry: the field is a single byte. Named here rather
     *  than written as 255 wherever it binds, because it is a fact about the WIRE and a set's
     *  own limits are a different question - see Model.Set#stitchedHold, which builds a longer
     *  hold out of several presets rather than letting this number decide what a set may be. */
    public static final int WIRE_HOLD_MAX = 255;

    public static byte[] addPreset(int speedPct, int upperKpa, int upperHoldS,
                                   int lowerKpa, int lowerHoldS) {
        return new byte[]{0x66, 0x2A, (byte) OP_ADD,
                (byte) speedCode(speedPct),
                (byte) clamp(upperKpa, 0, 57),
                (byte) clamp(upperHoldS, 0, 255),
                (byte) clamp(lowerKpa, 0, 57),
                (byte) clamp(lowerHoldS, 0, 255)};
    }

    public static byte[] startSlot(int slot) {
        return new byte[]{0x66, 0x2A, (byte) OP_START, (byte) clamp(slot, 0, SLOTS - 1)};
    }

    public static byte[] deleteSlot(int index) {
        return new byte[]{0x66, 0x2A, (byte) OP_DELETE, (byte) clamp(index, 0, SLOTS - 1)};
    }

    public static byte[] stop()  { return cmd(OP_STOP); }
    public static byte[] list()  { return cmd(OP_LIST); }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /* ------------------------------------------------------------ telemetry */

    /** One decoded telemetry sample. pressure is kPa (already divided by 10). */
    public static final class Sample {
        public final String mode;
        public final double kpa;
        public final int speedPct;
        public final boolean intelligent;
        /** True when the device reported 0.0 — that means NO MEASUREMENT, not ambient. */
        public final boolean noReading;

        Sample(String mode, double kpa, int speedPct, boolean intel) {
            this.mode = mode; this.kpa = kpa; this.speedPct = speedPct;
            this.intelligent = intel;
            this.noReading = (kpa == 0.0);
        }
    }

    /**
     * Parse a notification. Returns null if it is not telemetry.
     *
     * A 0.0 reading means the device is not measuring. It must never be integrated into
     * a dose, and a slope must never be taken across it — that was a real bug in the
     * PC-side analyser and it produced 383 kPa/s.
     */
    public static Sample parse(byte[] v) {
        if (v == null || v.length < 3) return null;
        String s = new String(v);
        int hash = s.indexOf('#');
        if (hash < 0) return null;
        String[] p = s.substring(hash + 1).trim().split(",");
        if (p.length < 2) return null;
        try {
            String mode = p[0].trim();
            // deci-kPa, and SIGNED ON THE WIRE. This is a vacuum: the device transmits
            // the reading as a NEGATIVE decimal (`#AUTO,-189,179,0` is 18.9 kPa of
            // vacuum). The app is a magnitude-only instrument (docs/protocols/zd21.md,
            // "Telemetry"). Dropping the abs()
            // here inverts every downstream comparison at once: Trace.isGap reads the
            // whole run as a gap, RunEdit.canHoldAt refuses every hold,
            // Session.peakValidKpa's MAX returns the SHALLOWEST sample, and
            // Validate.overCeiling can never trip — the ceiling abort goes dead.
            // Math.abs(-0.0) == 0.0, so the noReading sentinel survives unchanged.
            double kpa = Math.abs(Double.parseDouble(p[1].trim()) * 0.1);   // deci-kPa
            /* AND IT HAS TO BE A NUMBER THE REST OF THE APP CAN LIVE WITH. parseDouble
             * accepts "1e400" and "NaN" and hands back infinity or NaN, which is not a
             * NumberFormatException and so used to travel: into the dose integral, the
             * observed peak, the trace and every comparison against the ceiling — where
             * NaN is false against all of them. A frame that says that is not telemetry. */
            if (Double.isNaN(kpa) || Double.isInfinite(kpa)) return null;
            int spd = (p.length > 2) ? speedPct(Integer.parseInt(p[2].trim())) : -1;
            boolean intel = (p.length > 3) && !"0".equals(p[3].trim());
            return new Sample(mode, kpa, spd, intel);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Is this frame an ack for the given opcode? */
    public static boolean isAck(byte[] v, int op) {
        return v != null && v.length >= 2
            && (v[0] & 0xFF) == op && (v[1] & 0xFF) == 0x01;
    }

    /**
     * THE PUMP SAYS NO: `<opcode> FD`.
     *
     * The protocol notes used to say the ZD21 had never been seen to refuse anything, only to
     * stay silent. That was wrong. The owner's journal of 22 Sep has 23 STARTs answered
     * `2C FD`, every one of them a START of a slot the pump's table did not hold. Replaying
     * that journal's table writes against a pump that does not store an all-zero preset
     * predicts all 23 and every one of the 257 `2C 01`s (PumpRefusalTest). A refusal is an
     * explicit answer: the command did nothing, and the pump carries on as it was. Every
     * place that reads an ack reads this beside it (WiringCheck invariant 142).
     */
    public static final int REFUSED = 0xFD;

    /** Is this frame the pump refusing the given opcode? */
    public static boolean isRefusal(byte[] v, int op) {
        return v != null && v.length >= 2
            && (v[0] & 0xFF) == op && (v[1] & 0xFF) == REFUSED;
    }

    public static String hex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
