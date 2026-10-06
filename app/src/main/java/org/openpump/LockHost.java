package org.openpump;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;

/**
 * (incognito, 0.10; the owner, 2026-09-28: "make sure that logo also shows when the pin is
 * asked and not the original app logo") THE LOCK ASKS UNDER THE DISGUISE.
 *
 * The app lock asks through the phone's own fingerprint prompt or its PIN, pattern or password
 * screen. Newer Android draws the ASKING app's icon and name on both, and it takes them from
 * the activity that asked - not from anything the app can set at runtime (the prompt's own
 * logo setters are for system apps only). Asked from SessionActivity, that is the real logo
 * and "OpenPump" over a phone whose home screen says "Notes".
 *
 * So while a disguise is on, the prompt is asked from here instead: one subclass per disguise,
 * each declared in the manifest with that disguise's own icon and name
 * (Incognito#DISGUISE_LOCK_HOSTS, IncognitoShell#lockHost, OneScreenManifestTest). It is
 * translucent and draws nothing; it asks, answers SessionActivity through its result, and
 * finishes. It lives in the screen's own task, above it, only for as long as the question is
 * up.
 *
 * WHAT IT MAY DO (WiringCheck invariant 233): ask, and answer. It touches no pump, no service
 * and no model; it decides nothing about what is unlocked (SessionActivity#unlockGranted does,
 * on RESULT_OK). The phone's lock screen is another app, and a hold on the cuff must be vented
 * before another app takes the phone (H1): SessionActivity asks that door (ventFirst) before it
 * starts this in either mode, and "Use PIN instead" comes back to SessionActivity to ask the
 * door again rather than opening the lock screen from here.
 */
public abstract class LockHost extends Activity {

    /** The prompt's title, "Unlock Whole app" - the scope, never the app's name. */
    static final String EXTRA_TITLE = "org.openpump.LOCK_TITLE";
    /** MODE_BIOMETRIC or MODE_CREDENTIAL. */
    static final String EXTRA_MODE = "org.openpump.LOCK_MODE";
    /** The fingerprint prompt, with "Use PIN instead". */
    static final int MODE_BIOMETRIC = 0;
    /** The phone's own PIN, pattern or password screen. */
    static final int MODE_CREDENTIAL = 1;

    /** The person chose "Use PIN instead": SessionActivity asks for the PIN (through the door). */
    static final int RESULT_WANT_PIN = RESULT_FIRST_USER;
    /** The phone has nothing to challenge with: never a dead end, the scope unlocks. */
    static final int RESULT_NO_LOCK = RESULT_FIRST_USER + 1;

    private static final int REQ_CREDENTIAL = 1;

    private android.os.CancellationSignal cancel;
    private boolean answered;

    /** Fitness log's host - the manifest gives it that disguise's icon and name. */
    public static final class FitnessLog extends LockHost { }
    /** Habits' host. */
    public static final class Habits extends LockHost { }
    /** Notes' host. */
    public static final class Notes extends LockHost { }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setResult(RESULT_CANCELED);
        // Recreated (the process was ended while it asked): the question it was started for
        // went with it. The screen's own lock card still has its Unlock.
        if (b != null) { answer(RESULT_CANCELED); return; }
        Intent in = getIntent();
        String title = in == null ? null : in.getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) title = "Unlock";
        int mode = in == null ? MODE_CREDENTIAL : in.getIntExtra(EXTRA_MODE, MODE_CREDENTIAL);
        if (mode == MODE_BIOMETRIC && android.os.Build.VERSION.SDK_INT >= 28) askBiometric(title);
        else askCredential(title);
    }

    /** The platform fingerprint prompt, as SessionActivity#showBiometricPrompt asks it. */
    @android.annotation.TargetApi(28)
    private void askBiometric(String title) {
        try {
            java.util.concurrent.Executor exec = getMainExecutor();
            cancel = new android.os.CancellationSignal();
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                .setTitle(title)
                .setSubtitle("Use your fingerprint, or PIN instead")
                .setNegativeButton("Use PIN instead", exec, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        answer(RESULT_WANT_PIN);
                    }
                })
                .build();
            prompt.authenticate(cancel, exec, new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(
                        BiometricPrompt.AuthenticationResult r) {
                    answer(RESULT_OK);
                }
                // Dismissed, locked out, no hardware now: not unlocked. The screen's lock card
                // is still there with its own Unlock.
                @Override public void onAuthenticationError(int code, CharSequence msg) {
                    answer(RESULT_CANCELED);
                }
                // One bad match: the prompt says "try again" itself and keeps listening.
            });
        } catch (Exception e) {
            answer(RESULT_CANCELED);
        }
    }

    /** The phone's PIN, pattern or password screen, as SessionActivity#showCredentialFallback
     *  asks it - reached here only after SessionActivity asked the hold's door. */
    private void askCredential(String title) {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent ask = km == null || !km.isDeviceSecure()
            ? null : km.createConfirmDeviceCredentialIntent(title, null);
        if (ask == null) { answer(RESULT_NO_LOCK); return; }
        ask.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        try {
            startActivityForResult(ask, REQ_CREDENTIAL);
        } catch (Exception e) {
            answer(RESULT_CANCELED);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CREDENTIAL)
            answer(resultCode == RESULT_OK ? RESULT_OK : RESULT_CANCELED);
    }

    /** The one answer, once. */
    private void answer(int result) {
        if (answered) return;
        answered = true;
        setResult(result);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        // Finished from outside (a way into the app clears what is above the screen): the
        // fingerprint prompt goes with it rather than answering no one.
        if (cancel != null && !cancel.isCanceled()) {
            try { cancel.cancel(); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
