package org.openpump;

/**
 * SHOULD A GENERATED TONE ACTUALLY SOUND RIGHT NOW — the whole decision behind S12's run
 * screen cues, kept PURE (no `import android`) so test.sh compiles it and SelfTest can
 * pin its edges, the same split {@link Haptic} already draws between "which mark" (pure)
 * and "make the device buzz" (the Android-only effect, owned by SessionActivity#buzz).
 * The Android side owns only the ToneGenerator call — SessionActivity#playTone.
 *
 * THE THREE TIME MOMENTS ARE HAPTIC'S THREE MOMENTS, restated for the ear rather than the
 * hand: a preset's last three seconds, a preset actually changing, and the run ending. A
 * FOURTH kind of cue was added later and is not one of them - {@link RepCue}'s repetition
 * cue marks a place in the WORK rather than a moment in the clock, carries its own
 * preference, and fires from its own site. Everything below about the three is about the
 * three; nothing below is a claim that no other cue may exist.
 * S12's own wording is "stage change · last 3 s · done" — no fourth moment, and no
 * narrower "only while holding" reading of "last 3 s" than {@link Haptic} already gives
 * that phrase (Haptic's own countdown is "the last three seconds of A PRESET", not of a
 * specific control called HOLD; nothing in the decision text singles that control out).
 * So a TIME tone plays at EXACTLY the transitions {@link Haptic#markFor} and the two
 * preset-change/run-end buzz sites already fire on — never a fourth time site, never a
 * narrower reading of the same three. The repetition cue is not a fourth reading of them:
 * it is a different question, asked in {@link RepCue} and answered at its own site.
 *
 * WHY A SEPARATE GATE FROM HAPTICS. A tone is audible past the room the phone is in; a
 * buzz is not. So tone cues carry two conditions haptics never needed: their OWN
 * preference (default OFF — a buzz in the hand is easy to miss and a soft nicety to
 * default on; a beep is not, and this is a body-adjacent medical-style app, not a game)
 * and a live check that the phone is not currently asking for quiet. {@link #shouldSound}
 * is that whole decision, asked fresh on every cue rather than cached: the ringer and the
 * interruption filter can both change between one cue and the next while a routine plays
 * for tens of minutes.
 *
 * WHY NOT TRUST STREAM_MUSIC ALONE. ToneGenerator plays through whichever stream it is
 * constructed on, and STREAM_MUSIC is the RIGHT stream for a UI cue (never the alarm or
 * notification stream — this is feedback about a routine the user started, not an alert
 * demanding attention). But Android's Do Not Disturb is built around the RINGER and
 * NOTIFICATION streams; it does not, by itself, duck or mute an app's own media stream —
 * a podcast or a video keeps playing through DND exactly because the user is already
 * engaged with it. A generated cue the user did not ask to hear at that exact instant is
 * not "already engaged with", so this app does not assume DND leaves it alone. It asks.
 */
public final class Tone {
    private Tone() { }

    /** The three cue kinds — Haptic's three buzz shapes (TICK/CHANGE/END), restated as
     *  named constants so a caller states WHICH moment rather than which duration. The
     *  Android side (SessionActivity#playTone) maps each to its own ToneGenerator type
     *  and length; nothing here needs to know either. */
    public static final int STAGE_CHANGE = 0;
    public static final int LAST_3S = 1;
    public static final int DONE = 2;

    /** A FOURTH MOMENT, and the only one that is not about time: the routine reaching the
     *  repetition the user asked to be told about ({@link RepCue}). It is listed here with
     *  the other three because the Android side maps every cue through one switch, but it
     *  is gated by its OWN preference, never by Model#toneCues {@link RepCue}'s class doc
     *  says why. It is deliberately ToneGenerator's PROMPT rather than a longer version of
     *  the preset-change beep: "you have reached the repetition you asked about" should not
     *  sound like a bigger "the preset changed". */
    public static final int REP_CUE = 3;

    /** AudioManager.RINGER_MODE_NORMAL, restated as a literal so this pure class needs no
     *  android import — see the class doc for why nothing here imports android. Stable
     *  since API 1: RINGER_MODE_SILENT=0, RINGER_MODE_VIBRATE=1, RINGER_MODE_NORMAL=2,
     *  and no Android release has ever renumbered them (SelfTest pins the literal against
     *  this comment, not the other way round — see Migrate-style tests below). */
    public static final int RINGER_MODE_NORMAL = 2;

    /** NotificationManager.INTERRUPTION_FILTER_ALL, restated for the same reason — "no
     *  restriction of any kind is active": not Priority-only, not Alarms-only, not None.
     *  Stable since API 23. */
    public static final int INTERRUPTION_FILTER_ALL = 1;

    /**
     * THE WHOLE DECISION. Three independent reasons a cue stays silent, asked as three
     * separate inputs rather than one pre-folded boolean, so a future change to any one
     * of them cannot silently start answering for another (the same reasoning
     * {@link RunEdit#debouncedApplyAllowed} and this file's sibling {@link Haptic} both
     * give for keeping their own gates as named, separately-testable questions):
     *
     *   - {@code prefEnabled} — the user's OWN Settings toggle (Model#toneCues). Default
     *     OFF; a run that never turned this on must never sound regardless of anything
     *     else below.
     *   - {@code ringerMode} — RINGER_MODE_NORMAL only. A ringer set to vibrate or silent
     *     is the user's own, general "do not make noise right now" — a narrower,
     *     app-specific DND toggle must not override a broader one the user already set.
     *   - {@code interruptionFilter} — INTERRUPTION_FILTER_ALL only. Do Not Disturb in
     *     any of its other states (Priority, Alarms, None, or the platform's own
     *     "unknown" answer) means the phone is actively asking for quiet, and a
     *     STREAM_MUSIC tone is not automatically covered by that request — see the class
     *     doc — so this app covers it itself, conservatively: anything other than ALL is
     *     treated as "quiet was requested", never assumed harmless.
     *
     * All three must clear for a sound to play. Any one of them failing is a reason to
     * stay silent on its own; this is an AND, never a majority vote.
     */
    public static boolean shouldSound(boolean prefEnabled, int ringerMode, int interruptionFilter) {
        return prefEnabled
            && ringerMode == RINGER_MODE_NORMAL
            && interruptionFilter == INTERRUPTION_FILTER_ALL;
    }
}
