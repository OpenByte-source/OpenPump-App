package org.openpump;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * V1 - DEBUG BUILDS ONLY: LOSE THE NEXT STOPWORK ON ITS WAY TO THE SIMULATOR, to check by hand
 * that the vent watch does not confirm a stop the pump never received.
 *
 *     adb shell run-as org.openpump touch files/sim-lose-next-stop
 *
 * The next StopWork PumpLink writes to the SIMULATOR is recorded as sent (the app did write it)
 * and never delivered: no vent, no ack, the pressure left where it was - what a radio dropping a
 * WRITE_TYPE_NO_RESPONSE frame does on hardware. The flag file is removed, so exactly one stop is
 * lost.
 *
 * PUMP-REFUSAL - AND, THE SAME WAY, THE NEXT ADD, to see by hand what the app does when the pump
 * refuses a live change:
 *
 *     adb shell run-as org.openpump touch files/sim-lose-next-add
 *
 * The live change's Add is lost, so its START finds an empty entry and the simulator answers
 * `2C FD` - the real pump's refusal (the owner's journal of 22 Sep: a table one entry short of
 * what the app counted, and the override's START refused). The app must leave what was in force,
 * undo the screen and say so. Exactly one Add is lost.
 *
 * ITS SAFETY REVIEW - AND AN ANSWER LOST ON THE WAY BACK, THE COMMAND DONE: the real pump often
 * carries a START out without answering it (343 STARTs, 319 answers in the same journal).
 *
 *     adb shell "run-as org.openpump sh -c 'echo 1 > files/sim-lose-start-answers'"
 *
 * The next N `2C` answers from the simulator (the file holds N; empty means 1) are lost after
 * the simulator has done the START. The app must take that as unknown and converge on what was
 * confirmed (LiveLink), so the screen and the pump agree.
 *
 * It lives in app/src/debug for the same reason the diagnostic console does: a release APK must
 * not carry a way to lose a stop, even a simulated one. PumpLink reaches it by class-name string
 * behind BuildConfig.DEBUG, and only from the simulator's paths, so a real pump never passes
 * through it; SimPump itself vents on every StopWork it receives. SimStopLossIsDebugOnlyTest and
 * WiringCheck invariant 91 pin that.
 */
public final class SimStopLoss {

    static final String FLAG = "sim-lose-next-stop";
    static final String ADD_FLAG = "sim-lose-next-add";
    static final String ANSWER_FLAG = "sim-lose-start-answers";

    /**
     * Called by PumpLink, reflectively and only in a debug build, with each frame bound for the
     * simulator. Returns true when it takes the frame - a StopWork or an Add, with its flag file
     * present - so that it never reaches the simulator.
     */
    public static boolean takes(Context ctx, byte[] frame) {
        if (ctx == null || frame == null || frame.length < 3) return false;
        int op = frame[2] & 0xFF;
        String flag = op == Proto.OP_STOP ? FLAG : op == Proto.OP_ADD ? ADD_FLAG : null;
        if (flag == null) return false;
        File f = new File(ctx.getFilesDir(), flag);
        return f.exists() && f.delete();
    }

    /**
     * Called by PumpLink, reflectively and only in a debug build, with each frame the simulator
     * sends back. Returns true when it loses it - a START's answer, with ANSWER_FLAG present and
     * its count not used up - the simulator having already done the START.
     */
    public static boolean answerLost(Context ctx, byte[] frame) {
        if (ctx == null || frame == null || frame.length != 2
                || (frame[0] & 0xFF) != Proto.OP_START) return false;
        File f = new File(ctx.getFilesDir(), ANSWER_FLAG);
        if (!f.exists()) return false;
        int left = 1;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[16];
            int n = in.read(b);
            String s = n <= 0 ? "" : new String(b, 0, n, StandardCharsets.US_ASCII).trim();
            if (!s.isEmpty()) left = Integer.parseInt(s);
        } catch (IOException | NumberFormatException e) {
            left = 1;
        }
        if (left <= 1) {
            f.delete();
        } else {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(String.valueOf(left - 1).getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                f.delete();
            }
        }
        return left >= 1;
    }

    private SimStopLoss() { }
}
