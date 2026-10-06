package org.openpump;

import java.util.Locale;

/**
 * THE LEVEL BUBBLE'S ARITHMETIC — how far the phone is tilted, whether that is close
 * enough to the angle the user calibrated, and where the bubble dot belongs inside its
 * circle. Pure: no android import, so test.sh compiles and executes every line here, and
 * the on-screen widget (CameraScreen's LevelView) owns nothing but a Canvas and a
 * SensorEventListener.
 *
 * WHY IT EXISTS. A reference photo is only comparable to the last one if it was taken from
 * the same inclination. The align stage can fix framing after the fact, but it cannot undo
 * foreshortening: a tube photographed with the phone tipped ten degrees further back is a
 * measurably different shape on the sensor, and no amount of panning corrects that. The
 * bubble puts that one number in front of the user BEFORE the shutter, which is the only
 * moment it can still be fixed.
 *
 * THE ANGLES, and the convention every caller must use. The accelerometer's vector is the
 * device's own axes: +x right along the short edge, +y up along the long edge, +z out of
 * the screen. At rest it reads the reaction to gravity, so a phone lying FLAT, face up,
 * reads (0, 0, +9.81) and a phone stood upright in portrait reads (0, +9.81, 0).
 *
 *   pitch — how far the phone's TOP EDGE is raised from horizontal, signed. 0 is FLAT on a
 *           table face up, +90 is stood upright in portrait, -90 is upside down. This is
 *           the ordinary aerospace-style pitch, and it is what the earlier convention
 *           (0 = upright) got wrong in the only way a user could see: a phone lying flat on
 *           a table read "+87°" with the bubble pegged at the rim, which is the opposite of
 *           what an inclinometer is for.
 *   roll  — how far the phone is rotated about its own long axis, signed. 0 is face up and
 *           unrotated, -90 is turned a quarter clockwise into landscape, ±180 is face down.
 *
 * WHAT THE SCREEN SHOWS IS NEVER THE RAW ANGLE. Photos are not taken at one blessed angle;
 * they are taken at whatever angle THIS user shoots from — flat over the subject, at 45°,
 * or upright. So the bubble and the readout are the DEVIATION from the reference the user
 * calibrated ({@link #delta}), and a phone matching its saved angle centres the bubble
 * whether that angle is flat, 45° or upright. Before any reference is set there is nothing
 * to deviate from, so the readout shows the ABSOLUTE angles and asks for one to be set —
 * see {@link #readout} and {@link #verdict}.
 */
public final class Level {

    private Level() { }

    /** Within this many degrees of the target, in BOTH axes, the bubble reads green. Two
     *  degrees is about the tightest a hand-held phone can actually be held, and loose
     *  enough that the readout is not permanently red for someone doing it right. */
    public static final float TOL_DEG = 2f;

    /** The tilt, in degrees, that puts the bubble on the RIM of its circle. Beyond it the
     *  bubble stops at the rim rather than leaving the dial — a bubble that has slid off
     *  the screen tells the user nothing about which way to correct. */
    public static final float SPAN_DEG = 15f;

    /** The low-pass weight applied to each raw accelerometer sample. Raw accelerometer
     *  output on a hand-held phone jitters by whole degrees several times a second; at
     *  0.15 the readout settles in about a third of a second and stops shivering, which is
     *  the difference between a guide and a distraction. */
    public static final float ALPHA = 0.15f;

    /**
     * PITCH from one accelerometer vector — how far the phone's TOP EDGE is raised above
     * horizontal. Flat on a table face up reads 0, upright portrait +90, upside down -90,
     * and a phone tipped half way back from upright reads 45.
     *
     * Measured against the LENGTH of the other two axes rather than against one of them, so
     * rolling the phone onto its side does not change the pitch it reports — one bubble can
     * only show two axes at once if they are independent.
     *
     * A zero-length vector (free fall, or a sensor that has not reported yet) reads 0 rather
     * than NaN: the honest "no tilt known" that leaves the bubble where it was.
     */
    public static float pitchDeg(float ax, float ay, float az) {
        double rest = Math.sqrt((double) ax * ax + (double) az * az);
        if (rest == 0 && ay == 0) return 0f;
        return (float) Math.toDegrees(Math.atan2(ay, rest));
    }

    /**
     * ROLL from one accelerometer vector — how far the phone is turned about its long axis.
     * Flat face up reads 0; turned a quarter clockwise (gravity out along +x) reads -90;
     * turned the other way +90; face down ±180, which is a real distinction a face-up-only
     * formula cannot make and the reason this one uses the signed z rather than its length.
     */
    public static float rollDeg(float ax, float ay, float az) {
        if (ax == 0 && az == 0) return 0f;
        return (float) Math.toDegrees(Math.atan2(-ax, az));
    }

    /** One low-pass step: the running value moved a fraction {@link #ALPHA} of the way
     *  towards the newest sample. The FIRST sample must be taken as-is by the caller (it
     *  tracks a "seeded" flag), or the readout crawls up from zero for a second and the
     *  bubble appears to drift on its own. */
    public static float smooth(float prev, float sample) {
        return prev + ALPHA * (sample - prev);
    }

    /** The calibrated target, or 0. Null means the user has never set one; the readout
     *  then shows the absolute angle, which IS the deviation from 0, and asks for a
     *  reference rather than pretending 0 is one. */
    public static float target(Double calibrated) {
        return calibrated == null ? 0f : (float) calibrated.doubleValue();
    }

    /** How far off target this axis is, signed: positive means further than the target in
     *  the axis's own positive direction. */
    public static float delta(float now, Double calibrated) {
        return now - target(calibrated);
    }

    /** GREEN OR NOT: within {@link #TOL_DEG} of the reference in BOTH axes. Both, never
     *  either — a photo taken at the right tilt but rotated a quarter is not the same photo.
     *  With NO reference set nothing is level: there is no angle to be level TO, and a dial
     *  that went green at flat would be telling a user who shoots at 45° they were right. */
    public static boolean level(float pitch, float roll, Double calibPitch, Double calibRoll) {
        return calibrated(calibPitch, calibRoll)
            && Math.abs(delta(pitch, calibPitch)) <= TOL_DEG
            && Math.abs(delta(roll, calibRoll)) <= TOL_DEG;
    }

    /**
     * Where the bubble sits on one axis, as a fraction of the dial's radius in -1..1.
     * {@link #SPAN_DEG} of error reaches the rim and anything worse stays pinned there.
     * The view maps this to pixels; nothing about the mapping lives in the view.
     */
    public static float bubble(float deltaDeg) {
        float f = deltaDeg / SPAN_DEG;
        if (f > 1f) return 1f;
        if (f < -1f) return -1f;
        return f;
    }

    /** One angle, printed with a sign and one decimal: "+1.4°", "-0.3°", "0.0°". */
    public static String deg(float v) {
        // -0.04 must not print as "-0.0": a sign on a zero reads as a direction the user
        // is meant to correct in, and there is none.
        String s = String.format(Locale.US, "%.1f", Math.abs(v));
        String sign = "0.0".equals(s) ? "" : (v < 0 ? "-" : "+");
        return sign + s + "°";
    }

    /** The numeric readout under the dial: both axes, always both, so a user who is level
     *  in one and not the other can see which one to fix. Once a reference exists the
     *  numbers are DELTAS from it and say so with a Δ — "tilt +45°" and "Δ tilt +0.0°" are
     *  different facts about the same phone, and only the second one means "ready". */
    public static String readout(float pitch, float roll, Double calibPitch, Double calibRoll) {
        String d = calibrated(calibPitch, calibRoll) ? "Δ " : "";
        return d + "tilt " + deg(delta(pitch, calibPitch))
             + "  ·  " + d + "turn " + deg(delta(roll, calibRoll));
    }

    /** The one-line verdict beside the dial. Says the tolerance out loud when off, because
     *  "how close is close enough" is otherwise invisible. */
    public static String verdict(float pitch, float roll, Double calibPitch, Double calibRoll) {
        if (!calibrated(calibPitch, calibRoll))
            return "Set your angle once — hold the phone how you shoot and set it as the "
                 + "reference; the bubble then guides you back to it.";
        return level(pitch, roll, calibPitch, calibRoll)
            ? "Level — within " + trim(TOL_DEG) + "° of your reference"
            : "Not level yet — aim for within " + trim(TOL_DEG) + "°";
    }

    /** Whether a reference angle has actually been set, as opposed to the 0/0 default.
     *  Both axes are stored together, so either one present means calibrated. */
    public static boolean calibrated(Double calibPitch, Double calibRoll) {
        return calibPitch != null || calibRoll != null;
    }

    /** What the reference line says: the angle that was banked, or that the default is in
     *  force. Named in the same terms the readout uses, so "tilt" means one thing here. */
    public static String referenceLine(Double calibPitch, Double calibRoll) {
        if (!calibrated(calibPitch, calibRoll))
            return "No reference yet — the numbers above are the phone's own angle. "
                 + "Set your angle once, flat or upright or anything between.";
        return "Reference: tilt " + deg(target(calibPitch))
             + ", turn " + deg(target(calibRoll)) + " — the angle you set.";
    }

    /** The dial's spoken name — a circle with a dot in it says nothing to a screen reader,
     *  and this screen's whole purpose is that one number. */
    public static String dialName(float pitch, float roll, Double calibPitch, Double calibRoll) {
        return "Level guide. " + readout(pitch, roll, calibPitch, calibRoll) + ". "
             + verdict(pitch, roll, calibPitch, calibRoll) + ".";
    }

    /** "2" rather than "2.0" for a whole-number tolerance. */
    private static String trim(float v) {
        return v == Math.round(v) ? String.valueOf(Math.round(v))
                                  : String.format(Locale.US, "%.1f", v);
    }
}
