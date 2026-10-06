package org.openpump;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Photo capture for a measurement reading, per Task 7 and proto/pump-console.html's
 * #v-camera / #v-photo.
 *
 * AS OF THE VIEWFINDER REWORK there are TWO capture paths and the screen decides between
 * them: an IN-APP camera2 live preview with the level bubble and the alignment silhouette
 * drawn over the frame being shot (see "the in-app live viewfinder" below), and — whenever
 * that cannot be brought up or fails at any point — the SYSTEM camera intent described in
 * the paragraph that follows, which is retained unchanged as the fallback. Everything
 * downstream of the shutter is shared by both, so a photo taken either way is the same
 * photo as far as review, align, save, Compare and history are concerned.
 *
 * The system path, for reference: it drives the SYSTEM camera
 * (MediaStore.ACTION_IMAGE_CAPTURE via startActivityForResult) instead of an in-app
 * camera2 live preview: the earlier custom preview rendered black and captured nothing on
 * the user's real device, and a preview cannot be debugged without that device, so the
 * device's own camera app now takes the shot and returns it. Everything DOWNSTREAM of the
 * shutter is unchanged — which view (Front/Side/Top) a photo belongs to, the per-view note,
 * the delivered-pressure stamp, the on-disk name, and how a photo becomes part of a
 * reading — so Compare/history read exactly the same files back.
 *
 * WHAT WAS DROPPED AND HAS NOW COME BACK: the tubular alignment guide + ghost overlay were
 * drawn OVER the live preview, and the system camera cannot be overlaid, so they were lost.
 * They now live on a POST-CAPTURE align stage instead — showAlign(), between review and
 * save — where the still can be panned, pinched and slightly rotated under the same fixed
 * cylinder guide and under a ghost of the previous photo of the same view, and the
 * transform is baked into the saved pixels. Aligning against a still is more precise than
 * aligning against a viewfinder that moves while you judge it, and it needs no overlay on
 * anything the system camera owns. The geometry is Align.java (pure, asserted); this class
 * owns only the Canvas and the touch handling. The Shot state machine's #16/#18 fixes are
 * untouched by the new stage — nothing is committed until Save, exactly as before, and the
 * pending view, the pressure stamp and the per-view note are read at the same moments they
 * always were. armShutter() still binds
 * the capture to the live view the instant we commit to shooting it, commit() banks against
 * that armed view and never against whatever `view` reads later, and the live view only ever
 * advances through confirmAdvance() once the next capture has actually been launched.
 *
 * PRIVACY. The committed photo is written by saveFinal() to
 * getExternalFilesDir(Pictures)/reading-&lt;id&gt;-&lt;view&gt;.jpg (app-private external
 * storage). The camera writes into a SEPARATE scratch file served by CaptureProvider under
 * its OWN authority and its OWN containment (CapturePaths) — never through LogProvider, which
 * serves only log files — so the "photos are never in an exported log" promise is untouched.
 * Neither this class nor SessionActivity's log() ever writes a photo path into the session
 * log.
 */
public final class CameraScreen {

    /** Notified once the whole capture attempt for a Shot is over — every intended view
     *  was used, or the user skipped/cancelled/discarded. The caller re-renders its own
     *  screen; this class never navigates on its own. */
    public interface Callback {
        void onCameraDone(boolean tookAny);
    }

    /** Supplies the pressure the pump ACTUALLY reported (from telemetry) at the moment we
     *  commit to taking the photo, or null when there is no fresh reading. The DELIVERED
     *  vacuum, never the commanded setpoint — the same value the reading itself stores, so
     *  the photo stamp cannot claim a pressure the pump was never confirmed to be at.
     *  Implemented by SessionActivity, which owns the live sample. */
    public interface ObservedKpa { Double observedKpaNow(); }

    /** The standardisation hold as it stands now - the host owns it, this screen only asks,
     *  at the moment a capture is armed (point 19: a photo is labelled by what was true when
     *  it was taken). */
    public interface HoldNow {
        /** HoldWindow#state for the hold as it stands (M1's one verdict) - the only fact a
         *  photo's kind is taken from: standardised once the count finishes (invariant 88). */
        int holdWindowState();
    }

    /**
     * Launches an activity-for-result AS AN OWN LAUNCH. The host (SessionActivity) sets its
     * give-up-suppression flag (ownLaunchPending) so that leaving to the system camera is
     * not mistaken by onStop() for the user walking away. Only SessionActivity can set that
     * private flag, and only it should decide the launch is a deliberate one, so the launch
     * itself lives there; CameraScreen builds the intent and grants the write.
     *
     * H1 - AND IT OWNS THE DOOR. The phone's camera app and the photo picker are other apps:
     * a hold on the cuff is vented, and the vent confirmed, before either opens (ventFirst,
     * SessionActivity's H1 note). This screen asks the door BEFORE it arms a shot, so a photo
     * is never armed under the hold and taken after its vent.
     */
    public interface CaptureLauncher {
        void launchCaptureIntent(Intent intent, int requestCode);
        /** False: no hold on the cuff, launch now. True: launch nothing now - `then` runs
         *  once the hold's vent is confirmed, `cancelled` if the person keeps the hold. */
        boolean ventFirst(String opens, String extra, Runnable then, Runnable cancelled);
    }

    /** The CAMERA permission request code, routed back here by SessionActivity's
     *  onRequestPermissionsResult (see onPermissionResult). */
    public static final int REQ_CAMERA_PERM = 4242;

    /** startActivityForResult request code for the system camera. */
    public static final int REQ_IMAGE_CAPTURE = 4343;

    /** startActivityForResult request code for picking an existing image out of the phone's
     *  photo library. Distinct from the capture code so a result can never be mistaken for
     *  the other kind — the two paths differ in what the image is allowed to CLAIM. */
    public static final int REQ_PICK_IMAGE = 4344;

    private final Activity activity;
    private final ViewGroup body;
    private final Model model;
    private final Session session;
    private final ObservedKpa observed;
    private final CaptureLauncher launcher;
    private final HoldNow holdNow;

    private Shot shot;
    private String readingId;
    private Callback callback;

    /** The captured frame, decoded upright, held only in memory until Use writes it to the
     *  reading's own file — "nothing is committed until Use" (the scratch file the camera
     *  wrote is not the reading's photo). */
    private Bitmap pendingBitmap;
    private Double pendingHeldKpa;     // COMMANDED at launch (null = at rest); decides held-vs-rest only
    private Double pendingObservedKpa; // DELIVERED at launch from telemetry (null = unknown); the number shown/stored
    /** Point 19 - what the photo is being taken as (PhotoTruth#takenState), sampled when
     *  the capture is ARMED with the two above: the hold's state then - standardised only once
     *  its count has finished. What the review label says, by what was true when it was taken. */
    private int pendingTaken = PhotoTruth.TAKEN_AT_REST;

    /** Whether the image now under review was IMPORTED rather than shot. Set only by
     *  onPickResult, cleared by every arm — so a photo taken after an import can never
     *  inherit the badge, which is the same per-view discipline the note follows. */
    private boolean pendingFromGallery;
    private Double pendingTiltDeg, pendingTurnDeg;

    /** The same 350 ms re-entry discipline SessionActivity's navGuard() uses — the class of
     *  race defects #16/#18 came from, kept on the review screen's own taps. */
    private long lastNav = 0;

    private EditText noteInput;

    /* --------------------------------------------------------------- align stage */

    /** The note typed on the review screen, banked when Use is tapped — showAlign()
     *  replaces the body (and the EditText with it), so it cannot be read later. */
    private String pendingNote = "";

    /** The align stage's live state: the user's transform, the ghost's opacity, the ghost
     *  bitmap (null when there is no earlier photo of this view) and the date it came from.
     *  All cleared by releaseAlign(). */
    private final Align.T alignT = Align.identity();
    private float ghostOpacity = Align.DEFAULT_GHOST;
    private Bitmap ghostBitmap;
    private String ghostDayLabel;

    /**
     * HOW FAR THE USER HAS TURNED THIS PHOTO, cumulatively: 0 / 90 / 180 / 270.
     *
     * This is a READOUT, not the storage. Each tap of the rotate button turns the PIXELS
     * of `pendingBitmap` a quarter turn immediately (see {@link #rotateQuarter}), so by the
     * time anything is saved the bitmap is already the right way up and every reader —
     * gallery, Compare, then-vs-now, the next capture's ghost, an export — sees an upright
     * file with no orientation field to honour and no chance of one reader forgetting to.
     * This field only exists so the readout and the spoken name can say what was done.
     * Align's own class doc states the decision and why it is this one.
     */
    private int alignQuarter = 0;

    /**
     * WHICH WAY THE GUIDE LIES: false = portrait (the tube upright), true = landscape (the
     * tube on its side, base to the right). A photo framed with the phone turned sideways
     * had nothing to align against at all before this — the guide described a portrait
     * shot and the ghost sat upright underneath it, so the two references contradicted each
     * other and the honest move was to skip aligning, which is how a chain drifts. The
     * toggle turns BOTH: {@link Align#tubeRect} and friends lay the silhouette down, and
     * {@link Align#ghostQuarter} turns the ghost when its own shape disagrees with the
     * chosen framing.
     */
    private boolean alignLandscape = false;
    /** THE GHOST OVERRIDE. Null means "the default": Align.referenceFor's most recent
     *  EARLIER photo of this view, which is what the screen has always ghosted against and
     *  what it still opens on. Non-null is a reading the user picked by date on the Compare
     *  calendar, and it is deliberately cleared every time the Align stage is entered — the
     *  default is the right answer for the next photo too, and a sticky override would
     *  silently re-anchor a whole chain to one old date. */
    private String ghostOverrideId;
    /** The month the ghost picker is showing; null until it is first opened. */
    private PhotoCalendar.Month ghostMonth;
    private AlignView alignView;
    private TextView alignReadout;
    /** The precision-mode HUD chip: "1.24× · -3.2°", shown only while a long-press on the
     *  preview has the one-finger fine zoom/rotate drag active. Built alongside alignView in
     *  showAlign() (a sibling in the same FrameLayout) and released with it. */
    private TextView precisionHud;

    /* ------------------------------------------------------------- level (pre-capture) */

    /**
     * THE PRE-CAPTURE SCREEN'S LIVE TILT. The system camera owns the viewfinder and cannot
     * be overlaid (the same constraint that moved the align guide to a post-capture stage),
     * so the bubble lives on the screen BEFORE the shutter: get level, then shoot.
     *
     * `levelSeeded` exists because the first accelerometer sample must be taken WHOLE. Feed
     * it through the low-pass from a standing start and the readout climbs to the truth over
     * about a second, which looks exactly like a phone drifting in the hand.
     */
    private SensorManager sensors;
    private Sensor accel;
    private LevelListener levelListener;
    private float levelPitch, levelRoll;
    private boolean levelSeeded;
    private LevelView levelView;
    private TextView levelReadout, levelVerdict, levelRefLine;

    /** The pre-capture screen's primary: the circular shutter. Held because the ring is the
     *  "now" cue — {@link #paintShutter} fills it lime the moment the bubble goes green, so
     *  the signal to shoot is on the control that shoots rather than only on the dial.
     *  `shutterLevel` keeps the pulse to the TRANSITION into level, not to every sample. */
    private Button shutterBtn;
    private boolean shutterLevel;
    /** The shutter button paintShutter last painted, so a state is painted once per button. */
    private Button shutterPainted;

    /* --------------------------------------------------- the in-app camera2 preview */

    /** The live viewfinder's own state. All of it is torn down by
     *  {@link #releasePreview()}, which every way off this screen calls. */
    private TextureView previewView;
    private GuideOverlay guideOverlay;
    private CameraManager camMgr;
    private CameraDevice camDevice;
    private CameraCaptureSession camSession;
    private ImageReader jpegReader;
    private HandlerThread camThread;
    private Handler camHandler;
    private Surface previewSurface;
    private int sensorOrientation = 90;
    private int bufferW, bufferH;

    /** The one-way switch to the system camera app. Set by {@link #fallBack} the first time
     *  anything about the live preview fails, so the REST of that visit to the screen goes
     *  through the system camera app rather than retrying a camera that just refused.
     *  Cleared by {@link #showPreCapture}, i.e. on every fresh arrival at the pre-capture
     *  screen (back from the system camera, a Retake, a cancelled pick) — a one-way switch
     *  is how the screen ended up permanently black after the first fallback. */
    private boolean cameraFellBack;

    /** CAMERA is asked for AT MOST ONCE per pre-capture screen. A denial that re-asked on
     *  every resume would be a dialog the user cannot get past to reach "From gallery". */
    private boolean askedCameraPerm;

    /** Guards the async device open against a screen that has already been left — the exact
     *  discipline CameraGate's class doc states, reused here. */
    private final CameraGate gate = new CameraGate();

    /** True between the shutter tap and the JPEG arriving: a second tap in that window
     *  would capture into a scratch file the first one is still writing. */
    private boolean capturing;

    /** The still's floor, on its long edge. See PreviewGeom#bestCaptureIndex. */
    private static final int CAPTURE_MIN_LONG_EDGE = 1080;

    /** The preview buffer's ceiling, on its long edge — a viewfinder that is cropped into a
     *  square the width of the screen has nothing to do with more. */
    private static final int PREVIEW_MAX_LONG_EDGE = 1920;

    public CameraScreen(Activity activity, ViewGroup body, Model model, Session session,
                        ObservedKpa observed, CaptureLauncher launcher, HoldNow holdNow) {
        this.activity = activity;
        this.body = body;
        this.model = model;
        this.session = session;
        this.observed = observed;
        this.launcher = launcher;
        this.holdNow = holdNow;
    }

    /** The root of whichever capture screen this class drew last - pre-capture, review,
     *  align or the ghost picker. See {@link #isShowing}. */
    private View shownRoot;

    /** H1 - that screen's own step back: the control in its header (pre-capture's back,
     *  review's Retake, align's back, the ghost picker's back). Set beside shownRoot by each
     *  of the four, so the system Back key takes the same step (see {@link #back}). */
    private View.OnClickListener backStep;

    /**
     * H1 - THE SYSTEM BACK KEY INSIDE THE CAPTURE FLOW.
     *
     * The flow draws over its capture screen without changing the screen on record, so
     * SessionActivity answered Back for the capture screen underneath - and a capture screen
     * has no parent: with the hold already vented Back LEFT THE APP from the camera, and with
     * it still on it offered to vent it. Back here is the flow's own step back instead, the
     * one its header offers, and every one of those stays in the app: one stage back, or
     * (from the pre-capture screen) out of the flow onto the capture screen with whatever
     * was already taken. True when the flow is on screen and took the step.
     */
    public boolean back() {
        if (!isShowing() || backStep == null) return false;
        backStep.onClick(null);
        return true;
    }

    /**
     * C8 - WHETHER THE CAPTURE FLOW IS WHAT THE SCREEN SHOWS RIGHT NOW.
     *
     * The flow draws into the same body as the capture screen that opened it and never
     * changes the Activity's current screen, so "which screen is this" cannot tell a baseline
     * from the camera drawn over it. The hold limit needs to: redrawing the baseline under a
     * camera throws the photo in hand away. Open AND still attached - any other render
     * clears the body, which detaches this root, so a flow abandoned without handing back
     * does not read as showing forever.
     */
    public boolean isShowing() {
        return callback != null && shownRoot != null && shownRoot.getParent() == body;
    }

    /**
     * Opens the capture flow for `readingId` (reserved before the reading is saved) on
     * `which` (Shot.FRONT/SIDE/TOP). `shot` is owned by the caller (SessionActivity) and
     * reset once per baseline attempt, not by this class. Opens on the PRE-CAPTURE screen
     * for `which` — the level bubble and the choice between shooting and importing — rather
     * than firing the camera straight off, because both of those decisions have to be made
     * before the system camera takes the foreground and stops being ours to draw on.
     */
    public void open(String readingId, Shot shot, String which, Callback cb) {
        this.readingId = readingId;
        this.shot = shot;
        this.callback = cb;
        shot.cancelPending();
        showPreCapture(which);
    }

    /* --------------------------------------------------------- pre-capture (level) */

    /**
     * THE SCREEN BEFORE THE SHUTTER: a live level bubble, the two ways to fill this slot
     * (shoot one, or import one), and the calibration controls for the angle the bubble is
     * measured against.
     *
     * WHY THE BUBBLE IS HERE AND NOT ON A VIEWFINDER. Capture is
     * MediaStore.ACTION_IMAGE_CAPTURE — the device's own camera app, in its own task, which
     * this app cannot draw over. So the guide runs on the last screen that IS ours: level
     * yourself, then tap, and the camera opens with the phone already at the reference
     * angle. It is a weaker guarantee than an overlay (the phone can move in the second
     * between the tap and the shutter) and an honest one — and it is the only one available
     * without owning the preview, which this app tried and could not make work on the user's
     * real device.
     *
     * WHY THE SLOT'S SECOND OPTION LIVES HERE TOO. Every photo slot in the app funnels
     * through open() -> here, so one screen gives all of them the gallery option and the
     * level guide at once. A pair of tiny buttons per slot would have had to be added to
     * each screen that draws a slot row, and a chooser dialog would have been a second modal
     * in front of a screen this flow needs anyway.
     */
    /**
     * RE-ENTERING THE PRE-CAPTURE SCREEN. Every arrival here that is not itself a preview
     * failure is a fresh chance for the viewfinder: coming back from the system camera app,
     * from a Retake, from a cancelled pick. The fall-back switch is therefore cleared here
     * and only here — without this the FIRST failure left every later visit staring at a
     * dead black rectangle, because nothing ever re-opened the camera again.
     * {@link FallBack} rebuilds through {@link #buildPreCapture} instead, so the one
     * rebuild that must NOT retry the camera does not.
     */
    /* ===================== ANG - THE REFERENCE BELONGS TO THE SHOT, NOT THE PHONE =====
     *
     * There was ONE reference angle for every photo this app takes, and it could not have
     * been right: POV and side are a quarter turn apart by construction, so a phone held
     * correctly for one is held wrong for the other, and calibrating for either silently
     * broke the other. At rest and standardised differ again - a standardised shot is
     * taken with the cylinder in frame and the phone further back.
     *
     * Four slots now, chosen by the view being captured and whether a hold is running.
     * Which mode this is comes from pendingHeldKpa, the same field the review screen
     * already uses to decide held-versus-rest; there is no second, independent guess.
     */
    private boolean stdMode() {
        /* ASK THE SOURCE, NOT THE COPY THAT DOES NOT EXIST YET.
         *
         * pendingHeldKpa is assigned when a capture is ARMED. The guidance dial and the
         * level readout run on the PRE-capture screen, before any of that - so this was
         * null every time it was consulted there, and the two "standardised" reference
         * angles could never be read or written: half the calibration this screen offers
         * was unreachable. Once a shot IS armed the armed value is the fact about that
         * shot, so it still wins. */
        if (pendingHeldKpa != null) return true;
        return session != null && session.heldKpa() != null;
    }

    /** The view the dial and the level readout are currently guiding — the slot being
     *  filled. Falls back to POV for the same reason every other caller here does: it is
     *  the app's default view, and an unknown view is not evidence of a side shot. */
    private String refView() { return shot == null ? Shot.FRONT : shot.view(); }

    private Double refPitch(String view) {
        return model == null ? null : model.calibPitchFor(view, stdMode());
    }

    private Double refRoll(String view) {
        return model == null ? null : model.calibRollFor(view, stdMode());
    }

    private void showPreCapture(final String view) {
        cameraFellBack = false;
        buildPreCapture(view);
    }

    private void buildPreCapture(final String view) {
        body.removeAllViews();
        // Nothing is armed until the user actually chooses how to fill the slot: arriving
        // here after a Retake must not leave the previous capture pending.
        shot.cancelPending();

        LinearLayout root = Ui.col(activity);
        Ui.header(activity, root, Gallery.viewLabel(view.toLowerCase(Locale.US)) + " photo",
                  new PreCaptureBackTap());

        // POINT 19 - THE PERMISSION IS OFF, AND THE SCREEN SAYS SO. Once the app has asked
        // and been refused (or the phone refuses without asking), neither the viewfinder nor
        // the phone's own camera app can take the photo: the system refuses
        // ACTION_IMAGE_CAPTURE to an app that declares CAMERA without holding it. The toast
        // that said "using the phone's camera app" was untrue, and the shutter under it led
        // nowhere. A card says what is true and offers the two ways that work.
        permCardShown = askedCameraPerm && !hasCameraPermission();
        if (permCardShown) {
            root.addView(permissionCard(view));      // it carries its own margins
            Button skipOff = Ui.flat(activity, root, "Skip photos");
            skipOff.setOnClickListener(new SkipTap());
            shownRoot = root;
            body.addView(root, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            shot.confirmAdvance(view);
            return;
        }

        // THE VIEWFINDER, when this app can own one: the live preview, the alignment
        // silhouette ghosted over it, and the level dial in the corner — all three on the
        // frame the shutter is about to freeze, which is the only place a guide can still
        // change the photo. When it cannot (no camera, no permission, or camera2 failing on
        // this device) `live` is false, the dial gets the space to itself and the shutter
        // becomes the system camera app exactly as before.
        final boolean live = liveCapturePossible();

        FrameLayout stage = new FrameLayout(activity);
        int side = activity.getResources().getDisplayMetrics().widthPixels
                 - Ui.dp(activity, 28);          // body's horizontal padding
        if (live) {
            stage.setBackgroundColor(0xFF07090C);
            previewView = new TextureView(activity);
            stage.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            guideOverlay = new GuideOverlay(activity);
            stage.addView(guideOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        levelView = new LevelView(activity);
        // THE DIAL IS FURNITURE, NOT THE SUBJECT. Over a live frame it is a small corner
        // instrument (72dp, top-RIGHT, translucent track) so it reads at a glance without
        // covering the thing being photographed; with no preview to sit on it is the only
        // thing on the stage and takes all of it.
        FrameLayout.LayoutParams dialLp = live
            ? new FrameLayout.LayoutParams(Ui.dp(activity, 72), Ui.dp(activity, 72))
            : new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                                           FrameLayout.LayoutParams.MATCH_PARENT);
        if (live) {
            dialLp.gravity = Gravity.TOP | Gravity.END;
            dialLp.rightMargin = Ui.dp(activity, 10);
            dialLp.topMargin = Ui.dp(activity, 10);
        }
        levelView.setCompact(live);
        stage.addView(levelView, dialLp);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, live ? (side * 4 / 3) : Ui.dp(activity, 190));
        stageLp.topMargin = Ui.dp(activity, 6);
        root.addView(stage, stageLp);

        // THE NUMBERS BELONG ON THE FRAME THEY DESCRIBE. Over a live preview the tilt/turn
        // readout and its verdict are a small overlay chip in the bottom-LEFT corner of the
        // stage — two lines that were eating the page now cost the picture nothing. With no
        // preview they stay where they were, centred under the dial.
        levelReadout = new TextView(activity);
        levelVerdict = new TextView(activity);
        if (live) {
            LinearLayout chip = new LinearLayout(activity);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setBackground(Ui.roundRect(activity, 0xB307090C, Look.R_CTRL));
            chip.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 5),
                            Ui.dp(activity, 8), Ui.dp(activity, 5));
            levelReadout.setTextSize(Look.SP_FIELD_LABEL);
            levelReadout.setTypeface(android.graphics.Typeface.MONOSPACE);
            levelVerdict.setTextSize(Look.SP_MICRO);
            chip.addView(levelReadout);
            chip.addView(levelVerdict);
            FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            chipLp.gravity = Gravity.BOTTOM | Gravity.START;
            chipLp.leftMargin = Ui.dp(activity, 10);
            chipLp.bottomMargin = Ui.dp(activity, 10);
            stage.addView(chip, chipLp);
        } else {
            levelReadout.setTextSize(Look.SP_LABEL);
            levelReadout.setGravity(Gravity.CENTER);
            levelReadout.setPadding(0, Ui.dp(activity, 6), 0, 0);
            root.addView(levelReadout, wrapLp());
            levelVerdict.setTextSize(Look.SP_FIELD_LABEL);
            levelVerdict.setGravity(Gravity.CENTER);
            levelVerdict.setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 8));
            root.addView(levelVerdict, wrapLp());
        }

        // ONE LINE INSTEAD OF A PARAGRAPH. What the bubble is for, in the width of the
        // screen; the reference angle it is measured against is the line under it.
        TextView hint = new TextView(activity);
        // PR-27: the verdict in words, not by the bubble's hue alone.
        hint.setText("Match your saved angle — within ±" + (int) Level.TOL_DEG
            + "° counts as level");
        hint.setTextColor(Ui.DIM);
        hint.setTextSize(Look.SP_MICRO);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 2));
        root.addView(hint, wrapLp());

        levelRefLine = new TextView(activity);
        levelRefLine.setTextColor(Ui.DIM);
        levelRefLine.setTextSize(Look.SP_MICRO);
        levelRefLine.setGravity(Gravity.CENTER);
        levelRefLine.setPadding(Ui.dp(activity, 2), 0, 0, Ui.dp(activity, 6));
        levelRefLine.setText(Level.referenceLine(refPitch(view), refRoll(view)));
        root.addView(levelRefLine, wrapLp());

        // WHICH SLOT IS BEING FILLED, said once, right where the eye already is on its way
        // to the shutter.
        TextView viewChip = Ui.microLabel(activity, root,
            Gallery.viewLabel(view.toLowerCase(Locale.US)), Ui.DIM);
        viewChip.setGravity(Gravity.CENTER);
        viewChip.setLayoutParams(wrapLp());

        // THE SHUTTER IS THE SCREEN'S ONE PRIMARY: a 64dp circle, white ring, lime fill,
        // centred. Everything else on this screen is a small flat control, because
        // everything else is a detour. The tap is the SHUTTER when the preview above is
        // live and the system camera app when it is not; both land on the same review
        // screen.
        View.OnClickListener firstTap = live ? (View.OnClickListener) new ShutterTap(view)
                                             : (View.OnClickListener) new ShootTap(view);
        LinearLayout shutterRow = new LinearLayout(activity);
        shutterRow.setOrientation(LinearLayout.HORIZONTAL);
        shutterRow.setGravity(Gravity.CENTER);
        shutterBtn = new Button(activity);
        shutterBtn.setText("");
        shutterBtn.setContentDescription(live ? "Shutter — take the photo"
                                              : "Take photo with the phone's camera app");
        shutterBtn.setOnTouchListener(new Ui.Press());
        shutterBtn.setOnClickListener(firstTap);
        LinearLayout.LayoutParams shutLp = new LinearLayout.LayoutParams(
            Ui.dp(activity, 64), Ui.dp(activity, 64));
        shutLp.topMargin = Ui.dp(activity, 4);
        shutLp.bottomMargin = Ui.dp(activity, 8);
        shutterRow.addView(shutterBtn, shutLp);
        root.addView(shutterRow, wrapLp());
        paintShutter(Level.level(levelPitch, levelRoll, refPitch(view), refRoll(view)));

        Button pick = Ui.flat(activity, root, "▣ From gallery");
        pick.setOnClickListener(new PickTap(view));

        // ANG - the button names the slot it is about, so it can never be read as "set the
        // angle for photos" when it means "set it for this one kind of photo".
        String slotName = Model.angSlotLabel(view, stdMode());
        Button calib = Ui.flat(activity, root, "Set current angle as the "
            + slotName + " reference");
        calib.setContentDescription("Set the angle the phone is at right now as the "
            + "reference for " + slotName + " photos. The other views and the other mode "
            + "keep their own.");
        calib.setOnClickListener(new CalibrateTap());

        if (model != null && model.calibSetFor(view, stdMode())) {
            Button reset = Ui.flat(activity, root, "Clear the reference angle");
            reset.setContentDescription("Forget the reference angle; the dial then shows "
                + "the phone's own angle until a new one is set");
            reset.setOnClickListener(new CalibrateResetTap());
        }

        Button skip = Ui.flat(activity, root, "Skip photos");
        skip.setOnClickListener(new SkipTap());

        shownRoot = root;
        backStep = new PreCaptureBackTap();
        body.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // #18, applied one screen earlier: the live view moves only once the screen that
        // NAMES it is actually on the display, never in anticipation of it.
        shot.confirmAdvance(view);
        startLevel();
        if (live) startPreview();
    }

    /* ---------------------------------------------------------------- the sensor */

    /** Registers the accelerometer for the pre-capture screen. Silently does nothing on a
     *  phone without one — the dial then reads a flat 0/0 and the buttons still work, which
     *  is better than refusing to let someone take a photo. */
    private void startLevel() {
        if (levelListener != null) return;                 // already running
        try {
            if (sensors == null)
                sensors = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
            if (sensors == null) return;
            if (accel == null) accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel == null) return;
            levelSeeded = false;
            levelListener = new LevelListener();
            sensors.registerListener(levelListener, accel, SensorManager.SENSOR_DELAY_UI);
        } catch (Exception e) {
            levelListener = null;
        }
        updateLevel();
    }

    /**
     * Unregisters the accelerometer. Called from EVERY way off the pre-capture screen —
     * launching the camera, launching the picker, going back, finishing — and from the
     * Activity's pause hook, so a sensor can never be left streaming into a screen that is
     * not on the display. Idempotent: an unregister with nothing registered is a no-op.
     */
    private void stopLevel() {
        unregisterSensor();
        // The viewfinder goes with the screen it belongs to: every caller of stopLevel() is
        // leaving the pre-capture screen, and a camera left open behind another screen is a
        // device no other app can have.
        releasePreview();
        previewView = null;
        guideOverlay = null;
        levelView = null;
        levelReadout = null;
        levelVerdict = null;
        levelRefLine = null;
        shutterBtn = null;
        shutterLevel = false;
    }

    /** The sensor half alone — the listener goes, the screen's views stay. This is what a
     *  PAUSE wants: the app in the background must not keep the accelerometer streaming,
     *  but the pre-capture screen is still the screen the user is coming back to, and
     *  onResume() re-registers against the very views that are still on it. */
    private void unregisterSensor() {
        if (sensors != null && levelListener != null) {
            try { sensors.unregisterListener(levelListener); } catch (Exception ignored) { }
        }
        levelListener = null;
    }

    /** The one place the dial, the numbers, the verdict and the spoken name are refreshed
     *  together — the staleness rule the align readout follows, for the same reason: a
     *  green dial under a "not level" sentence is worse than either alone. */
    private void updateLevel() {
        levelDrawnAt = android.os.SystemClock.uptimeMillis();
        // ANG — the slot being filled, not one global angle for every kind of photo.
        Double cp = refPitch(refView());
        Double cr = refRoll(refView());
        boolean ok = Level.level(levelPitch, levelRoll, cp, cr);
        // (the final review's emulator check) ONLY WHAT CHANGED IS REDRAWN. Every sample
        // re-set both lines (a fresh text layout each), the description and the shutter's
        // drawable; on a loaded phone that kept the main thread so busy that a Back press waited
        // 39 s and Android offered to close the app. Text is set when it differs, the shutter
        // is painted when its state does (paintShutter), and samples are drawn at LEVEL_UI_MS.
        if (levelReadout != null) {
            setIfChanged(levelReadout, Level.readout(levelPitch, levelRoll, cp, cr));
            levelReadout.setTextColor(ok ? Ui.GOOD : Ui.TEXT);
        }
        if (levelVerdict != null) {
            setIfChanged(levelVerdict, Level.verdict(levelPitch, levelRoll, cp, cr));
            levelVerdict.setTextColor(ok ? Ui.GOOD : Ui.DIM);
        }
        if (levelView != null) {
            String name = Level.dialName(levelPitch, levelRoll, cp, cr);
            if (!name.contentEquals(String.valueOf(levelView.getContentDescription())))
                levelView.setContentDescription(name);
            levelView.invalidate();
        }
        paintShutter(ok);
    }

    /** The level's lines are redrawn at most this often: ~12 a second is smooth to the eye and
     *  leaves the main thread free for input (Back above all) however fast samples arrive. */
    private static final long LEVEL_UI_MS = 80L;
    private long levelDrawnAt;

    private static void setIfChanged(TextView v, CharSequence text) {
        if (!android.text.TextUtils.equals(v.getText(), text)) v.setText(text);
    }

    /**
     * THE "NOW" CUE. The shutter's fill goes solid lime with a white ring the instant the
     * phone is inside tolerance and drops back to a dim lime when it leaves, with one short
     * pulse on the way IN — the moment worth catching is the transition, and a ring that
     * pulsed on every accelerometer sample would be a flicker rather than a signal. Nothing
     * about what the tap DOES changes with the colour; the button is live either way,
     * because a photo at the wrong angle is still better than no photo.
     */
    private void paintShutter(boolean ok) {
        if (shutterBtn == null) { shutterLevel = false; shutterPainted = null; return; }
        // Painted once per state and per button: a new drawable on every sample was a relayout
        // on every sample.
        if (shutterPainted == shutterBtn && ok == shutterLevel) return;
        shutterPainted = shutterBtn;
        android.graphics.drawable.GradientDrawable g =
            new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(ok ? Ui.ACCENT : Ui.ACCENT_DIM);
        g.setStroke(Ui.dp(activity, 4), ok ? 0xFFFFFFFF : 0x66FFFFFF);
        shutterBtn.setBackground(g);
        if (ok && !shutterLevel) {
            try {
                shutterBtn.setScaleX(1f); shutterBtn.setScaleY(1f);
                shutterBtn.animate().scaleX(1.08f).scaleY(1.08f).setDuration(110)
                          .withEndAction(new ShutterSettle()).start();
            } catch (Throwable ignored) { }
        }
        shutterLevel = ok;
    }

    /** The second half of the shutter's pulse — back to rest. A named Runnable because this
     *  source is Java 8 without lambdas. */
    private final class ShutterSettle implements Runnable {
        @Override public void run() {
            if (shutterBtn != null)
                shutterBtn.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
        }
    }

    private final class LevelListener implements SensorEventListener {
        @Override public void onSensorChanged(SensorEvent e) {
            if (e == null || e.values == null || e.values.length < 3) return;
            float p = Level.pitchDeg(e.values[0], e.values[1], e.values[2]);
            float r = Level.rollDeg(e.values[0], e.values[1], e.values[2]);
            if (!levelSeeded) { levelPitch = p; levelRoll = r; levelSeeded = true; }
            else { levelPitch = Level.smooth(levelPitch, p); levelRoll = Level.smooth(levelRoll, r); }
            // Every sample is smoothed in; the screen is redrawn at LEVEL_UI_MS (updateLevel).
            if (android.os.SystemClock.uptimeMillis() - levelDrawnAt >= LEVEL_UI_MS) updateLevel();
        }
        @Override public void onAccuracyChanged(Sensor s, int accuracy) { }
    }

    /**
     * THE DIAL. A ring, a centre target ring at the tolerance, crosshairs, and a bubble that
     * sits where {@link Level#bubble} puts it — green inside tolerance, plain outside. Every
     * number it draws with comes from Level; this view owns pixels and nothing else.
     */
    private final class LevelView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** Corner-instrument mode: a translucent disc behind the ring so the dial stays
         *  readable over a bright frame without hiding what is under it, and thinner ink
         *  because at 72dp the full-size strokes read as a blob. */
        private boolean compact;

        void setCompact(boolean c) {
            compact = c;
            ring.setAlpha(c ? 90 : 120);
            invalidate();
        }

        LevelView(Activity act) {
            super(act);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(act, 2));
            ring.setColor(Ui.TEXT);
            ring.setAlpha(120);
            cross.setStyle(Paint.Style.STROKE);
            cross.setStrokeWidth(Ui.dp(act, 1));
            cross.setColor(Ui.DIM);
            dot.setStyle(Paint.Style.FILL);
            track.setStyle(Paint.Style.FILL);
            track.setColor(0x8807090C);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f, cy = h / 2f;
            float radius = Math.min(w, h) / 2f - Ui.dp(activity, compact ? 6 : 10);
            if (radius <= 0) return;
            if (compact) c.drawCircle(cx, cy, radius + Ui.dp(activity, 4), track);

            Double cp = refPitch(refView());
            Double cr = refRoll(refView());
            boolean ok = Level.level(levelPitch, levelRoll, cp, cr);

            c.drawCircle(cx, cy, radius, ring);
            // The INNER ring is the tolerance itself, drawn to scale — so "close enough" is
            // a place on the dial rather than a number the user has to hold in their head.
            c.drawCircle(cx, cy, radius * (Level.TOL_DEG / Level.SPAN_DEG), cross);
            c.drawLine(cx - radius, cy, cx + radius, cy, cross);
            c.drawLine(cx, cy - radius, cx, cy + radius, cross);

            float bx = cx + radius * Level.bubble(Level.delta(levelRoll, cr));
            // +pitch means the phone's back has tipped away from the user, and the bubble
            // falls the way the phone leans: down the screen.
            float by = cy + radius * Level.bubble(Level.delta(levelPitch, cp));
            dot.setColor(ok ? Ui.GOOD : Ui.TEXT);
            c.drawCircle(bx, by, Ui.dp(activity, compact ? 7 : 11), dot);
        }
    }

    /* ================================================== the in-app live viewfinder ==
     *
     * WHY THIS CAME BACK. The system-camera hop is reliable and it is also the reason the
     * alignment guide and the level bubble could not be on the frame being shot: another
     * app owns that screen. Everything this app can say about framing was therefore said
     * either BEFORE the shutter (a bubble on the previous screen, which the phone can move
     * away from in the second it takes to tap) or AFTER it (the align stage, which can pan a
     * photo but cannot un-tilt one). Owning the preview is the only place both guides land
     * on the frame that is actually captured.
     *
     * WHY IT FAILED THE FIRST TIME, and what is different. The earlier attempt showed a
     * black rectangle on the user's device. The two ways that happens with a TextureView are
     * both handled here explicitly:
     *   - the SurfaceTexture was already available before the listener was attached, so
     *     onSurfaceTextureAvailable never fired and nothing ever opened the camera --
     *     startPreview() checks isAvailable() as well as attaching the listener;
     *   - the camera was opened and the session driven on the main thread, where a blocked
     *     callback silently never delivers -- every camera2 callback here runs on a
     *     dedicated HandlerThread, and only the three things that touch views (the
     *     transform, the toast, the review screen) hop back to the UI thread.
     *
     * AND IF IT FAILS ANYWAY: fallBack(). Any CameraAccessException, missing back camera,
     * refused permission, or session-configuration failure switches this screen to the
     * system-camera intent -- which is still here, unchanged -- with a toast saying so. The
     * one outcome that is not allowed is the one the user got last time: a dead screen.
     */

    /** Whether to even build a viewfinder: not after a fallback, and not on a phone with no
     *  back camera to open. Cheap enough to ask on every screen build. */
    private boolean liveCapturePossible() {
        if (cameraFellBack) return false;
        return backCameraId() != null;
    }

    /** The first back-facing camera's id, or null when there is none (or the camera service
     *  will not answer, which is the same thing as far as this screen is concerned). */
    private String backCameraId() {
        try {
            if (camMgr == null)
                camMgr = (CameraManager) activity.getSystemService(Activity.CAMERA_SERVICE);
            if (camMgr == null) return null;
            String[] ids = camMgr.getCameraIdList();
            for (int i = 0; i < ids.length; i++) {
                CameraCharacteristics ch = camMgr.getCameraCharacteristics(ids[i]);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing.intValue() == CameraCharacteristics.LENS_FACING_BACK)
                    return ids[i];
            }
            return ids.length > 0 ? ids[0] : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean hasCameraPermission() {
        try {
            return activity.checkSelfPermission("android.permission.CAMERA")
                == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Brings the viewfinder up: permission first, then the background thread, then the
     * surface. Attaching the listener AND asking isAvailable() is deliberate -- see the
     * section doc; a TextureView that is already available never calls the listener, and
     * that alone is a black screen.
     */
    private void startPreview() {
        if (previewView == null || cameraFellBack) return;
        if (!hasCameraPermission()) {
            if (askedCameraPerm) { permissionRefused(); return; }
            askedCameraPerm = true;
            try {
                activity.requestPermissions(
                    new String[]{ "android.permission.CAMERA" }, REQ_CAMERA_PERM);
            } catch (Throwable t) {
                fallBack("Could not ask for the camera");
            }
            return;
        }
        try {
            if (camThread == null) {
                camThread = new HandlerThread("cam2");
                camThread.start();
                camHandler = new Handler(camThread.getLooper());
            }
            previewView.setSurfaceTextureListener(new SurfaceWatcher());
            if (previewView.isAvailable()) openCamera();
        } catch (Throwable t) {
            fallBack("Could not start the preview");
        }
    }

    /** The TextureView's own lifecycle. onSurfaceTextureDestroyed returns true: the texture
     *  is ours to release, and the camera is torn down with it. */
    private final class SurfaceWatcher implements TextureView.SurfaceTextureListener {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            applyTransform();
        }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            releasePreview();
            return true;
        }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
    }

    /** Opens the back camera onto the background handler. Every failure route ends in
     *  fallBack() rather than a return that would leave a black rectangle. */
    private void openCamera() {
        if (camDevice != null || cameraFellBack || previewView == null) return;
        String id = backCameraId();
        if (id == null) { fallBack("No camera on this phone"); return; }
        try {
            CameraCharacteristics ch = camMgr.getCameraCharacteristics(id);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so.intValue();
            StreamConfigurationMap map =
                ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { fallBack("This camera reports no preview sizes"); return; }
            if (!chooseSizes(map)) { fallBack("This camera offers no usable size"); return; }
            int token = gate.start();
            camMgr.openCamera(id, new DeviceCallback(token), camHandler);
        } catch (CameraAccessException e) {
            fallBack("The camera is not available");
        } catch (SecurityException e) {
            // Not a camera failure: the permission went away. The phone's camera app is
            // refused too, so this is the permission card, not the fall-back.
            activity.runOnUiThread(new PermissionRefused());
        } catch (Throwable t) {
            fallBack("Could not open the camera");
        }
    }

    /** Picks the preview buffer and the still size out of what this camera offers, using
     *  the pure arithmetic in PreviewGeom (asserted on the desktop, where no camera exists).
     *  False when there is nothing to choose. */
    private boolean chooseSizes(StreamConfigurationMap map) {
        android.util.Size[] prev = map.getOutputSizes(SurfaceTexture.class);
        if (prev == null || prev.length == 0) return false;
        boolean swap = PreviewGeom.swapsDimensions(sensorOrientation, displayRotationDeg());
        int[] pw = new int[prev.length], ph = new int[prev.length];
        for (int i = 0; i < prev.length; i++) { pw[i] = prev[i].getWidth(); ph[i] = prev[i].getHeight(); }
        int pi = PreviewGeom.bestPreviewIndex(pw, ph, PREVIEW_MAX_LONG_EDGE,
            previewView.getWidth(), previewView.getHeight(), swap);
        if (pi < 0) return false;
        bufferW = pw[pi]; bufferH = ph[pi];

        android.util.Size[] jpg = map.getOutputSizes(ImageFormat.JPEG);
        if (jpg == null || jpg.length == 0) return false;
        int[] jw = new int[jpg.length], jh = new int[jpg.length];
        for (int i = 0; i < jpg.length; i++) { jw[i] = jpg[i].getWidth(); jh[i] = jpg[i].getHeight(); }
        int ji = PreviewGeom.bestCaptureIndex(jw, jh, CAPTURE_MIN_LONG_EDGE);
        if (ji < 0) return false;
        if (jpegReader != null) { try { jpegReader.close(); } catch (Throwable ignored) { } }
        jpegReader = ImageReader.newInstance(jw[ji], jh[ji], ImageFormat.JPEG, 2);
        jpegReader.setOnImageAvailableListener(new JpegReady(), camHandler);
        return true;
    }

    /** The device open's landing. The gate token is what makes a LATE callback harmless:
     *  a screen already left has bumped the generation, so this closes and leaves. */
    private final class DeviceCallback extends CameraDevice.StateCallback {
        private final int token;
        DeviceCallback(int t) { token = t; }
        @Override public void onOpened(CameraDevice device) {
            gate.clearPending();
            if (!gate.isCurrent(token) || previewView == null) {
                try { device.close(); } catch (Throwable ignored) { }
                return;
            }
            camDevice = device;
            createSession();
        }
        @Override public void onDisconnected(CameraDevice device) {
            gate.clearPending();
            try { device.close(); } catch (Throwable ignored) { }
            camDevice = null;
            if (gate.isCurrent(token)) fallBack("Another app took the camera");
        }
        @Override public void onError(CameraDevice device, int error) {
            gate.clearPending();
            try { device.close(); } catch (Throwable ignored) { }
            camDevice = null;
            if (gate.isCurrent(token)) fallBack("The camera reported an error");
        }
    }

    /** Binds the chosen buffer to the texture and asks for the two-surface session: the
     *  preview the user watches and the JPEG reader the shutter writes into. */
    private void createSession() {
        try {
            SurfaceTexture st = previewView == null ? null : previewView.getSurfaceTexture();
            if (st == null || camDevice == null) { fallBack("The preview surface went away"); return; }
            st.setDefaultBufferSize(bufferW, bufferH);
            previewSurface = new Surface(st);
            List<Surface> targets = new ArrayList<Surface>();
            targets.add(previewSurface);
            if (jpegReader != null) targets.add(jpegReader.getSurface());
            camDevice.createCaptureSession(targets, new SessionCallback(), camHandler);
        } catch (CameraAccessException e) {
            fallBack("The camera is not available");
        } catch (Throwable t) {
            fallBack("Could not start the preview");
        }
    }

    private final class SessionCallback extends CameraCaptureSession.StateCallback {
        @Override public void onConfigured(CameraCaptureSession session) {
            camSession = session;
            startRepeating();
            activity.runOnUiThread(new ApplyTransform());
        }
        @Override public void onConfigureFailed(CameraCaptureSession session) {
            fallBack("The camera would not configure");
        }
    }

    /** The repeating preview request: continuous autofocus, because a viewfinder the user
     *  has to tap to focus is a viewfinder that photographs a blur when they forget. */
    private void startRepeating() {
        try {
            if (camDevice == null || camSession == null || previewSurface == null) return;
            CaptureRequest.Builder b = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            b.set(CaptureRequest.CONTROL_AF_MODE,
                  Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE));
            camSession.setRepeatingRequest(b.build(), null, camHandler);
        } catch (CameraAccessException e) {
            fallBack("The camera stopped responding");
        } catch (Throwable t) {
            fallBack("The camera stopped responding");
        }
    }

    /** The aspect-correcting transform, on the UI thread where a View's matrix belongs.
     *  Identity-safe: an unmeasured view yields scale 1 rather than a collapsed preview. */
    private final class ApplyTransform implements Runnable {
        @Override public void run() { applyTransform(); }
    }

    private void applyTransform() {
        if (previewView == null) return;
        int vw = previewView.getWidth(), vh = previewView.getHeight();
        if (vw <= 0 || vh <= 0 || bufferW <= 0 || bufferH <= 0) return;
        int rot = displayRotationDeg();
        boolean swap = PreviewGeom.swapsDimensions(sensorOrientation, rot);
        int dispW = swap ? bufferH : bufferW;
        int dispH = swap ? bufferW : bufferH;
        Matrix m = new Matrix();
        float cx = vw / 2f, cy = vh / 2f;
        m.postScale((float) PreviewGeom.coverScaleX(vw, vh, dispW, dispH),
                    (float) PreviewGeom.coverScaleY(vw, vh, dispW, dispH), cx, cy);
        int correction = PreviewGeom.previewCorrectionDeg(rot);
        if (correction != 0) m.postRotate(correction, cx, cy);
        previewView.setTransform(m);
    }

    private int displayRotationDeg() {
        try {
            int r = activity.getWindowManager().getDefaultDisplay().getRotation();
            if (r == Surface.ROTATION_90) return 90;
            if (r == Surface.ROTATION_180) return 180;
            if (r == Surface.ROTATION_270) return 270;
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /* ------------------------------------------------------------------ the shutter */

    private final class ShutterTap implements View.OnClickListener {
        private final String view;
        ShutterTap(String v) { view = v; }
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            capture(view);
        }
    }

    /**
     * THE IN-APP SHUTTER. Arms exactly as the intent path does -- same live view (#18), same
     * pressures sampled at the same moment, same cleared note and align stage -- then asks
     * for one still. If anything refuses, it falls back to the system camera app with the
     * arming intact, so the tap still produces a photo.
     */
    private void capture(String view) {
        if (capturing) return;
        if (camDevice == null || camSession == null || jpegReader == null) {
            fallBackCapture(view, "The camera is not ready");
            return;
        }
        armForShutter(view);
        capturing = true;
        File staging = stagingFile();
        if (staging != null && staging.exists()) staging.delete();
        try {
            CaptureRequest.Builder b =
                camDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(jpegReader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE,
                  Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE));
            // The EXIF the still is stamped with, so Photos.decodeOriented bakes the same
            // upright rotation into the pixels that the system camera's own EXIF produced.
            b.set(CaptureRequest.JPEG_ORIENTATION,
                  Integer.valueOf((sensorOrientation + displayRotationDeg()) % 360));
            camSession.stopRepeating();
            camSession.capture(b.build(), null, camHandler);
        } catch (Throwable t) {
            capturing = false;
            fallBackCapture(view, "The shutter did not fire");
        }
    }

    /** A shutter that could not fire is still a tap that must produce a photo: switch this
     *  screen to the system camera app and launch it straight away, rather than rebuilding
     *  the screen and asking the user to tap a second time. */
    private void fallBackCapture(String view, String why) {
        cameraFellBack = true;
        releasePreview();
        previewView = null;
        guideOverlay = null;
        toast(why + " — using your phone's camera app instead");
        launchCapture(view);
    }

    /** The JPEG's arrival, on the camera thread: bytes to the same scratch file the system
     *  camera writes, then the UI thread takes it into the unchanged review-align-save
     *  pipeline. */
    private final class JpegReady implements ImageReader.OnImageAvailableListener {
        @Override public void onImageAvailable(ImageReader reader) {
            Image img = null;
            boolean wrote = false;
            try {
                img = reader.acquireLatestImage();
                if (img != null) wrote = writeStaging(img);
            } catch (Throwable ignored) {
            } finally {
                if (img != null) { try { img.close(); } catch (Throwable ignored) { } }
            }
            activity.runOnUiThread(new Captured(wrote));
        }
    }

    private boolean writeStaging(Image img) {
        FileOutputStream out = null;
        try {
            File f = stagingFile();
            if (f == null) return false;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            out = new FileOutputStream(f);
            out.write(bytes);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) { try { out.close(); } catch (Throwable ignored) { } }
        }
    }

    /** Back on the UI thread with the still on disk. The camera is released BEFORE the
     *  review screen is built: the preview is not coming back until the user retakes, and a
     *  camera left open behind a review screen is a device other apps cannot have. */
    private final class Captured implements Runnable {
        private final boolean wrote;
        Captured(boolean w) { wrote = w; }
        @Override public void run() {
            capturing = false;
            String view = shot == null ? null : shot.pendingView();
            releasePreview();
            // The viewfinder's views go with the hardware: the review screen replaces them,
            // and a surviving reference would have onResume() re-open the camera against a
            // TextureView that is no longer on any screen.
            previewView = null;
            guideOverlay = null;
            if (!wrote || view == null) {
                deleteStaging();
                if (view != null) fallBackCapture(view, "The photo did not come through");
                return;
            }
            Bitmap bmp = loadCaptured(null);
            deleteStaging();
            if (bmp == null) {
                fallBackCapture(view, "The photo did not decode");
                return;
            }
            takeArrived(bmp);
            showReview();
        }
    }

    /* ---------------------------------------------------------- teardown / fallback */

    /**
     * RELEASES EVERYTHING, in the order that cannot strand a callback: the gate first (so a
     * device open still in flight closes itself on arrival), then the session, the device,
     * the reader, the surface and finally the background thread. Idempotent -- it is called
     * from the shutter, from every exit off the screen, from onPause and from the texture's
     * own destruction.
     */
    private void releasePreview() {
        gate.reset();
        capturing = false;
        if (camSession != null) {
            try { camSession.stopRepeating(); } catch (Throwable ignored) { }
            try { camSession.close(); } catch (Throwable ignored) { }
            camSession = null;
        }
        if (camDevice != null) {
            try { camDevice.close(); } catch (Throwable ignored) { }
            camDevice = null;
        }
        if (jpegReader != null) {
            try { jpegReader.close(); } catch (Throwable ignored) { }
            jpegReader = null;
        }
        if (previewSurface != null) {
            try { previewSurface.release(); } catch (Throwable ignored) { }
            previewSurface = null;
        }
        if (camThread != null) {
            try { camThread.quitSafely(); } catch (Throwable ignored) { }
            camThread = null;
            camHandler = null;
        }
    }

    /** THE ONE THING THAT MUST NEVER BE A DEAD SCREEN. Any preview failure lands here: the
     *  hardware is released, the screen is switched to the system-camera path for as long as
     *  it is up, and it is rebuilt so the shutter button becomes "Take photo" -- with a toast
     *  saying which way the photo is now being taken and why. */
    private void fallBack(String why) {
        activity.runOnUiThread(new FallBack(why));
    }

    private final class FallBack implements Runnable {
        private final String why;
        FallBack(String w) { why = w; }
        @Override public void run() {
            if (cameraFellBack) return;              // already switched; one toast is enough
            cameraFellBack = true;
            releasePreview();
            previewView = null;
            guideOverlay = null;
            toast(why + " — using your phone's camera app instead");
            // Only rebuild if the pre-capture screen is still the screen: a failure that
            // arrives after the user has moved on must not yank them back to it.
            // buildPreCapture, not showPreCapture: this rebuild is the ONE that must keep
            // the fall-back switch set, or the screen would re-open the camera that just
            // failed and loop.
            if (levelView != null && shot != null) buildPreCapture(shot.view());
        }
    }

    /**
     * THE ALIGNMENT SILHOUETTE, GHOSTED OVER THE LIVE FRAME. The same cylinder the align
     * stage draws (Align's geometry, one source for both), at a low alpha so it guides
     * without competing with the subject: get the tube inside it now and the align stage
     * afterwards has almost nothing left to correct.
     */
    private final class GuideOverlay extends View {
        private final Paint ghostLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        GuideOverlay(Activity act) {
            super(act);
            ghostLine.setStyle(Paint.Style.STROKE);
            ghostLine.setStrokeWidth(Ui.dp(act, 2));
            ghostLine.setColor(Ui.TEXT);
            ghostLine.setAlpha(70);
            setContentDescription("Alignment guide over the live camera. "
                + "Fit the tube inside the outline.");
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float[] tube = Align.tubeRect(w, h, false);
            box.set(tube[0], tube[1], tube[2], tube[3]);
            float rad = Math.min(Align.tubeRadius(w, h, false),
                                 Align.tubeCross(w, h, false) * 0.18f);
            c.drawRoundRect(box, rad, rad, ghostLine);
            float[] fl = Align.flangeRect(w, h, false);
            c.drawRect(fl[0], fl[1], fl[2], fl[3], ghostLine);
            float[] rim = Align.rimRect(w, h, false);
            c.drawRect(rim[0], rim[1], rim[2], rim[3], ghostLine);
            float[] ax = Align.axisLine(w, h, false);
            c.drawLine(ax[0], ax[1], ax[2], ax[3], ghostLine);
        }
    }

    /* ------------------------------------------------------- pre-capture taps */

    private final class ShootTap implements View.OnClickListener {
        private final String view;
        ShootTap(String v) { view = v; }
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            launchCapture(view);
        }
    }

    private final class PickTap implements View.OnClickListener {
        private final String view;
        PickTap(String v) { view = v; }
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            launchPick(view);
        }
    }

    /** Whether the pre-capture screen is showing the permission card - so a return from the
     *  app's settings with the permission now granted rebuilds it with the viewfinder. */
    private boolean permCardShown;

    /** Opens this app's page in the system settings, where the camera permission is turned
     *  on. Offered only when no hold is outstanding (see PhotoTruth#permissionLine), and
     *  launched through the host like the camera itself, so the trip out is the app's own
     *  errand rather than the user leaving. Nothing is returned; onResume() looks again. */
    public static final int REQ_APP_SETTINGS = 4345;

    /**
     * POINT 19 - the card the pre-capture screen shows in place of the viewfinder and the
     * shutter while the camera permission is off: what is true, and the two ways forward
     * that work - App settings and Gallery - as two outlined buttons side by side. Under a
     * hold only Gallery is offered: the settings leave the app, which vents the pump.
     */
    private LinearLayout permissionCard(String view) {
        boolean holding = session != null && session.heldKpa() != null;
        LinearLayout card = Ui.col(activity);
        card.setBackground(Ui.roundRect(activity, Ui.SURF, Look.R_CARD));
        int pad = Ui.dp(activity, Look.S5);
        card.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(activity);
        t.setText(PhotoTruth.PERMISSION_TITLE);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(Look.SP_HEADING);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(t);
        TextView line = new TextView(activity);
        line.setText(PhotoTruth.permissionLine(holding));
        line.setTextColor(Ui.DIM);
        line.setTextSize(Look.SP_LABEL);
        line.setPadding(0, Ui.dp(activity, 4), 0, 0);
        card.addView(line);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (!holding) {
            LinearLayout settings = outlinedIconButton("App settings", R.drawable.ic_tab_settings);
            settings.setContentDescription("Open this app's settings to turn the camera on");
            settings.setOnClickListener(new AppSettingsTap());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(activity, 48), 1f);
            lp.rightMargin = Ui.dp(activity, 10);
            row.addView(settings, lp);
        }
        LinearLayout gallery = outlinedIconButton("Gallery", R.drawable.ic_image);
        gallery.setContentDescription("Pick a " + Gallery.viewLabel(view)
            + " photo from the phone's gallery");
        gallery.setOnClickListener(new PickTap(view));
        row.addView(gallery, new LinearLayout.LayoutParams(0, Ui.dp(activity, 48), 1f));
        LinearLayout.LayoutParams rowLp = wrapLp();
        rowLp.topMargin = Ui.dp(activity, 12);
        card.addView(row, rowLp);

        // The card is the screen's content, so it keeps the stage's place: a little air
        // under the header, and room above Skip.
        LinearLayout.LayoutParams cardLp = wrapLp();
        cardLp.topMargin = Ui.dp(activity, 6);
        cardLp.bottomMargin = Ui.dp(activity, Look.S4);
        card.setLayoutParams(cardLp);
        return card;
    }

    /** An outlined button with an icon before its words, the two centred together: no
     *  fill, the control ring, the words in TEXT - a secondary action that is one of a
     *  pair. A row rather than a Button, because a Button's compound drawable sits at its
     *  edge, not beside centred words. One control to a screen reader: its own
     *  description (set by the caller), the Button role, and the icon decorative. */
    private LinearLayout outlinedIconButton(String label, int iconRes) {
        LinearLayout b = new LinearLayout(activity);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.ctrlRect(activity, android.graphics.Color.TRANSPARENT, Look.R_CTRL));
        b.setPadding(Ui.dp(activity, 10), 0, Ui.dp(activity, 10), 0);
        b.setMinimumHeight(Ui.dp(activity, 48));
        b.setClickable(true);
        b.setFocusable(true);
        b.setAccessibilityDelegate(new ButtonRole());
        ImageView ic = Ui.iconView(activity, iconRes, Ui.TEXT, 18);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(
            Ui.dp(activity, 18), Ui.dp(activity, 18));
        icLp.rightMargin = Ui.dp(activity, 8);
        b.addView(ic, icLp);
        TextView t = new TextView(activity);
        t.setText(label);
        t.setTextSize(Look.SP_CHIP);
        t.setTextColor(Ui.TEXT);
        t.setSingleLine(true);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        b.addView(t);
        b.setOnTouchListener(new Ui.Press());
        return b;
    }

    /** Announces a clickable row as a button, which is what it is. */
    private static final class ButtonRole extends View.AccessibilityDelegate {
        @Override public void onInitializeAccessibilityNodeInfo(View host,
                android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(Button.class.getName());
        }
    }

    private final class AppSettingsTap implements View.OnClickListener, Runnable {
        /** H1 - the same request, made again once a hold's vent is confirmed. */
        @Override public void run() { if (isShowing()) onClick(null); }
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            // H1 - the phone's Settings is another app: a hold on the cuff vents first.
            if (launcher.ventFirst(HoldHandOff.SETTINGS, null, this, null)) return;
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + activity.getPackageName()));
                launcher.launchCaptureIntent(i, REQ_APP_SETTINGS);
            } catch (Exception e) {
                // PR-25: NEVER THE APP'S NAME - this toast showed it in disguise mode too.
                toast("Open your phone's Settings › Apps › this app › Permissions to turn "
                    + "the camera on.");
            }
        }
    }

    /** The permission was refused - asked just now, or refused without asking. The camera
     *  hardware is let go, and the pre-capture screen is drawn again, which now shows the
     *  permission card instead of a viewfinder and a shutter that cannot work. */
    private void permissionRefused() {
        askedCameraPerm = true;
        cameraFellBack = false;
        releasePreview();
        previewView = null;
        guideOverlay = null;
        if (shot != null && callback != null) buildPreCapture(shot.view());
    }

    /** Banks the angle the phone is at RIGHT NOW as the reference. Saved immediately: its
     *  whole point is to still be there for a photo taken weeks from now, and a reference
     *  that only survived if the user later saved something else would silently not be. */
    private final class CalibrateTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (model == null) return;
            String view = shot == null ? Shot.FRONT : shot.view();
            // ANG - into THIS slot. The legacy pair is never written again: it is the
            // fallback for slots nobody has set, and overwriting it would silently move
            // the reference for every one of them.
            model.setCalibFor(view, stdMode(),
                Double.valueOf(levelPitch), Double.valueOf(levelRoll));
            Store.save(activity, model);
            toast(Model.angSlotLabel(view, stdMode()) + " reference set");
            showPreCapture(view);                 // the Reset button appears with it
        }
    }

    private final class CalibrateResetTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (model == null) return;
            String view = shot == null ? Shot.FRONT : shot.view();
            model.setCalibFor(view, stdMode(), null, null);
            Store.save(activity, model);
            toast(Model.angSlotLabel(view, stdMode()) + " reference cleared");
            showPreCapture(view);
        }
    }

    /** Backing out of the pre-capture screen ends the attempt for this slot, keeping
     *  whatever other views were already captured — the same contract Skip has. */
    private final class PreCaptureBackTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            finish();
        }
    }

    /* ------------------------------------------------------------- launch (system camera) */

    /**
     * Commits to shooting `view`: samples the delivered pressure NOW (telemetry is known
     * fresh here — the app is in the foreground with the link live; by the time the external
     * camera returns it may be stale, which is why the stamp is taken at launch, not at
     * result), arms the Shot against `view`, then hands a write-granted
     * ACTION_IMAGE_CAPTURE intent to the host to launch. If no camera app exists, or the
     * launch fails, it returns cleanly to the caller's screen rather than crashing.
     */
    /**
     * COMMITTING TO FILLING `view`, whichever way the image is about to arrive. Shared by
     * the camera launch and the gallery pick so the two cannot drift on what "armed" means:
     * the same live view (#18), the same cleared align stage and note, the same pressures
     * sampled at the same moment, the same armShutter() (#16). The one thing it sets that
     * the two paths then disagree about is `pendingFromGallery`, cleared here and set only
     * by a pick that actually produced an image.
     */
    private void armFor(String view) {
        stopLevel();
        armPending(view);
    }

    /**
     * THE SHUTTER'S ARMING. Identical to {@link #armFor} in everything it records, and
     * different in the one thing that matters to an in-app still: it does NOT call
     * stopLevel(), which releases the camera. armFor() is written for the paths that LEAVE
     * this screen (the system camera, the picker), where dropping the device is right; the
     * in-app shutter is still standing on the screen and needs the very session it is about
     * to fire a request on. Only the sensor and the dial go here — the camera stays until
     * the JPEG has landed.
     */
    private void armForShutter(String view) {
        unregisterSensor();
        levelView = null;
        levelReadout = null;
        levelVerdict = null;
        levelRefLine = null;
        armPending(view);
    }

    private void armPending(String view) {
        // The live view IS `view` from this instant — confirmAdvance() is the only thing
        // that moves it (#18), and armShutter() then binds the pending capture to it (#16).
        shot.confirmAdvance(view);
        // A new capture starts with a clean align stage and a clean note: neither the
        // transform nor the text typed for the PREVIOUS view may travel onto this one (the
        // note-per-view rule Shot's class doc exists to enforce, applied one step earlier).
        releaseAlign();
        pendingNote = "";
        pendingFromGallery = false;
        pendingHeldKpa = session.heldKpa();
        pendingObservedKpa = observed != null ? observed.observedKpaNow() : null;
        pendingTaken = PhotoTruth.takenState(pendingHeldKpa != null,
            holdNow == null ? (pendingHeldKpa != null ? HoldWindow.SHORT : HoldWindow.AT_REST)
                            : holdNow.holdWindowState());
        // The angle the frame was shot from — the smoothed level at this instant. An
        // import overwrites these with null (armGallery path sets pendingFromGallery).
        pendingTiltDeg = levelSeeded ? Double.valueOf((double) levelPitch) : null;
        pendingTurnDeg = levelSeeded ? Double.valueOf((double) levelRoll) : null;
        shot.armShutter();
    }

    private void launchCapture(String view) {
        // This app declares CAMERA for its own viewfinder, and the system refuses
        // ACTION_IMAGE_CAPTURE to an app that declares it without holding it — so the
        // fallback needs the same grant the preview does. Ask once, then let the user
        // choose again; "From gallery" needs no camera at all and is still on the screen.
        if (!hasCameraPermission()) {
            if (!askedCameraPerm) {
                askedCameraPerm = true;
                try {
                    activity.requestPermissions(
                        new String[]{ "android.permission.CAMERA" }, REQ_CAMERA_PERM);
                    return;
                } catch (Throwable ignored) { }
            }
            // POINT 19: the card, not a toast over a shutter that cannot work.
            shot.cancelPending();
            permissionRefused();
            return;
        }
        // H1 - THE PHONE'S CAMERA APP IS ANOTHER APP. Under a hold it opens only once the
        // pump has vented and said so, and asked BEFORE armFor, so the shot is armed at rest.
        if (launcher.ventFirst(HoldHandOff.CAMERA, HoldHandOff.AT_REST,
                               new CaptureAgain(view, false), new KeepHoldHere(view))) return;
        armFor(view);

        // Clear any stale scratch bytes from an aborted capture before the camera writes.
        File staging = stagingFile();
        if (staging != null && staging.exists()) staging.delete();

        Uri out = Uri.parse(CapturePaths.contentUri());
        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        capture.putExtra(MediaStore.EXTRA_OUTPUT, out);
        capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                       | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (capture.resolveActivity(activity.getPackageManager()) == null) {
            toast("No camera app found");
            shot.cancelPending();
            finish();
            return;
        }
        // The intent flag alone does not reliably reach a URI carried in EXTRA_OUTPUT, so
        // grant the write to every camera package that can handle this explicitly.
        grantToResolvers(capture, out);
        try {
            launcher.launchCaptureIntent(capture, REQ_IMAGE_CAPTURE);
        } catch (Exception e) {
            toast("Could not open the camera");
            shot.cancelPending();
            finish();
        }
    }

    /**
     * THE OTHER WAY TO FILL A SLOT: pick an image the phone already has.
     *
     * ACTION_OPEN_DOCUMENT with CATEGORY_OPENABLE and image/*, falling back to ACTION_PICK
     * where no document provider answers (some minimal ROMs). NO persistable permission is
     * taken: the very next thing that happens to the picked Uri is that its bytes are copied
     * into this app's own private folder, so the read grant only has to outlive one
     * openInputStream. Keeping a long-lived grant on the user's photo library would be a
     * standing capability this feature does not need, and a promise ("photos stay in this
     * app") this feature would be quietly weakening.
     */
    private void launchPick(String view) {
        // H1 - the photo picker is another app too: the same door, asked before anything arms.
        if (launcher.ventFirst(HoldHandOff.PHOTOS, HoldHandOff.AT_REST,
                               new CaptureAgain(view, true), new KeepHoldHere(view))) return;
        armFor(view);
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (pick.resolveActivity(activity.getPackageManager()) == null) {
            pick = new Intent(Intent.ACTION_PICK);
            pick.setType("image/*");
            if (pick.resolveActivity(activity.getPackageManager()) == null) {
                toast("No photo app found");
                shot.cancelPending();
                showPreCapture(view);
                return;
            }
        }
        try {
            launcher.launchCaptureIntent(pick, REQ_PICK_IMAGE);
        } catch (Exception e) {
            toast("Could not open your photos");
            shot.cancelPending();
            showPreCapture(view);
        }
    }

    /**
     * H1 - THE REQUEST, MADE AGAIN once the hold's vent is confirmed: the same shot, and
     * only if its screen is still the one showing. A person who moved on while the pump
     * vented is not dragged into the camera app. On the way back through the door there is
     * no hold any more, so it launches - armed at rest, which is what the photo now is.
     */
    private final class CaptureAgain implements Runnable {
        private final String view;
        private final boolean pick;
        CaptureAgain(String v, boolean p) { view = v; pick = p; }
        @Override public void run() {
            if (!isShowing() || shot == null || !view.equals(shot.view())) return;
            if (pick) launchPick(view); else launchCapture(view);
        }
    }

    /**
     * H1 - the person kept the hold. The door armed nothing; a shutter that could not fire
     * had armed its shot and released the preview on its way to the camera app
     * (fallBackCapture), so that shot goes and the pre-capture screen is drawn again - on the
     * camera-app path, since the in-app camera has just failed. Nothing is sent to the pump.
     */
    private final class KeepHoldHere implements Runnable {
        private final String view;
        KeepHoldHere(String v) { view = v; }
        @Override public void run() {
            if (!isShowing() || shot == null) return;
            shot.cancelPending();
            if (levelView == null) buildPreCapture(view);
        }
    }

    /**
     * Routed from SessionActivity.onActivityResult for {@link #REQ_PICK_IMAGE}. Copies the
     * picked image THROUGH THE CAPTURE PATH — into the same scratch file the camera writes,
     * then back out through {@link Photos#decodeOriented}, which downscales it and bakes any
     * EXIF rotation into the pixels — and hands the result to the same review screen. From
     * here on there is no second code path: the same review, the same align stage, the same
     * saveFinal(), the same on-disk name. Only the badge differs.
     *
     * A cancelled pick returns to the pre-capture screen rather than ending the attempt: the
     * user backing out of a file browser meant "not that one", not "no photo at all".
     */
    public void onPickResult(int resultCode, Intent data) {
        String view = shot == null ? null : shot.pendingView();
        if (view == null) { finish(); return; }

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            shot.cancelPending();
            deleteStaging();
            showPreCapture(view);
            return;
        }
        Bitmap bmp = importPicked(data.getData());
        deleteStaging();
        if (bmp == null) {
            toast("Could not read that image — try another");
            shot.cancelPending();
            showPreCapture(view);
            return;
        }
        takeArrived(bmp);
        pendingFromGallery = true;
        showReview();
    }

    /** The copy itself: picked Uri -> the staging file -> an upright, downscaled bitmap.
     *  Null on any failure (an unreadable Uri, a file that is not really an image, a full
     *  disk), which every caller already renders as "could not read that image". */
    private Bitmap importPicked(Uri src) {
        File staging = stagingFile();
        if (staging == null) return null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = activity.getContentResolver().openInputStream(src);
            if (in == null) return null;
            File dir = staging.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            out = new FileOutputStream(staging);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
        }
        return Photos.decodeOriented(staging.getAbsolutePath());
    }

    private void grantToResolvers(Intent capture, Uri out) {
        try {
            List<android.content.pm.ResolveInfo> res =
                activity.getPackageManager().queryIntentActivities(capture, 0);
            for (int i = 0; i < res.size(); i++) {
                String pkg = res.get(i).activityInfo.packageName;
                activity.grantUriPermission(pkg, out,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Exception ignored) { }
    }

    /**
     * Routed from SessionActivity.onActivityResult. Turns the system camera's result into
     * the review screen, handling every honest outcome:
     *   - RESULT_OK with the scratch file written → decode it upright (EXIF baked in) and
     *     review it;
     *   - RESULT_OK but an OEM camera returned only a thumbnail in the "data" extra and
     *     ignored EXTRA_OUTPUT → review that thumbnail instead;
     *   - RESULT_OK with neither → say so and return cleanly;
     *   - RESULT_CANCELED / null → the user backed out; discard the pending capture and
     *     return to the caller's screen with whatever was already taken intact.
     * Never crashes on a cancelled or null result.
     */
    public void onCaptureResult(int resultCode, Intent data) {
        revokeGrant();
        String view = shot == null ? null : shot.pendingView();
        if (view == null) { finish(); return; }        // stray result, nothing armed

        if (resultCode != Activity.RESULT_OK) {
            shot.cancelPending();
            deleteStaging();
            finish();
            return;
        }

        Bitmap bmp = loadCaptured(data);
        deleteStaging();
        if (bmp == null) {
            toast("Could not capture the photo — try again");
            shot.cancelPending();
            finish();
            return;
        }
        takeArrived(bmp);
        showReview();
    }

    /** The captured image: the scratch file first (decoded upright, so any EXIF rotation the
     *  camera stamped is baked into the pixels), else the OEM thumbnail fallback. Null if the
     *  camera produced neither. */
    private Bitmap loadCaptured(Intent data) {
        File staging = stagingFile();
        if (staging != null && staging.exists() && staging.length() > 0) {
            Bitmap b = Photos.decodeOriented(staging.getAbsolutePath());
            if (b != null) return b;
        }
        if (data != null && data.getExtras() != null) {
            Object thumb = data.getExtras().get("data");
            if (thumb instanceof Bitmap) return (Bitmap) thumb;
        }
        return null;
    }

    /* ----------------------------------------------------------- review screen */

    private void showReview() {
        body.removeAllViews();
        stopLevel();

        LinearLayout root = Ui.col(activity);

        // POINT 19 - the view by its LABEL ("POV"), never the stored key "Front".
        final String viewName = Gallery.viewLabel(shot.pendingView());
        // PR-26: ONE RETAKE / USE PAIR - the foot's. The header is the app's own, its ‹
        // the retake (as the hardware key is, through backStep below).
        Ui.header(activity, root, viewName + " · review", new RetakeTap());

        // POINT 19 - WHAT THE PHOTO IS, and only that, by what was true when it was taken
        // (pendingTaken, sampled at arming from M1's HoldWindow verdict and the live count).
        // The label is PhotoTruth's (pure, pinned): "At rest"; "Standardised · −5.9 inHg"
        // while the count runs or inside its served two minutes; "Held at … · for less than
        // 30 s" / "· after the two minutes" outside them; "Venting · …" while the vent is
        // being confirmed; "▣ Imported" for an image with no shutter. It used to read "Taken
        // at …" in green whenever ANY hold was outstanding, a skipped one included, and to
        // speak an at-rest shot as "not comparable to standardised ones": two equal methods,
        // each compared with its own kind, so one neutral ink for all of them.
        String[] label = PhotoTruth.reviewLabel(pendingFromGallery, pendingTaken,
            pendingObservedKpa, model == null ? 30 : model.std.sec);

        ImageView img = new ImageView(activity);
        img.setContentDescription("The " + viewName
            + (pendingFromGallery ? " image just imported. " : " photo just taken. ")
            + label[1]);
        img.setImageBitmap(pendingBitmap);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(0xFF07090C);
        root.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ONE ROW, NOT A CAPTION AND A CARD. Which view this is and what the shot is are two
        // facts, so they are two chips on one line; the full sentence is still spoken.
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 6));
        infoPill(info, viewName, Ui.DIM, "this photo fills the " + viewName + " view");
        infoPill(info, label[0], Ui.TEXT, label[1]);
        root.addView(info, wrapLp());

        // THE NOTE IS OPTIONAL, so it costs one line until it is wanted. The field is built
        // either way — collapsed it is merely GONE — so Use reads the same EditText whether
        // or not the user ever opened it, and a note carried back from the align stage or
        // from an earlier capture of this view opens the field rather than hiding itself.
        final LinearLayout noteBox = new LinearLayout(activity);
        noteBox.setOrientation(LinearLayout.VERTICAL);

        TextView noteLabel = new TextView(activity);
        noteLabel.setText("Note · optional — " + viewName + " only");
        noteLabel.setTextColor(Ui.DIM);
        noteLabel.setTextSize(Look.SP_MICRO);
        noteLabel.setPadding(Ui.dp(activity, 2), Ui.dp(activity, 6), 0, Ui.dp(activity, 2));
        noteBox.addView(noteLabel, wrapLp());

        // A real label association, not merely a TextView sitting above the field.
        // setLabelFor needs the input to have an id, and views built in code have none
        // until one is generated.
        noteInput = new EditText(activity);
        noteInput.setId(View.generateViewId());
        noteLabel.setLabelFor(noteInput.getId());
        // Pre-fill with THIS view's own previously-typed note (a retake of an
        // already-captured view), never the other view's — the note-per-view rule this
        // whole review screen exists to enforce.
        // Coming back from the align stage, `pendingNote` is what the user typed a moment
        // ago and has not committed yet; on a fresh review it is empty and the committed
        // note for THIS view (a retake of an already-captured view) is the right prefill —
        // never the other view's, which is the whole point of the per-view map.
        noteInput.setText(pendingNote != null && pendingNote.length() > 0
            ? pendingNote : shot.noteFor(shot.pendingView()));
        noteInput.setHint("lighting, angle, time of day, anything worth remembering");
        noteInput.setTextColor(Ui.TEXT);
        noteInput.setBackgroundColor(Ui.SURF);
        noteInput.setSingleLine(true);
        noteInput.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 8), Ui.dp(activity, 10), Ui.dp(activity, 8));
        noteBox.addView(noteInput, wrapLp());
        root.addView(noteBox, wrapLp());

        final Button addNote = Ui.flat(activity, root, "Add a note");          // PR-27
        addNote.setContentDescription("Add a note for the " + viewName + " photo only");
        addNote.setOnClickListener(new AddNoteTap(noteBox, addNote));
        boolean hasNote = noteInput.getText() != null && noteInput.getText().length() > 0;
        noteBox.setVisibility(hasNote ? View.VISIBLE : View.GONE);
        addNote.setVisibility(hasNote ? View.GONE : View.VISIBLE);

        // RETAKE AND USE ARE THE SAME SIZE because they are the same decision seen from two
        // sides; only the colour says which one commits. Discarding is neither, so it is a
        // small link under them rather than a third button of equal weight.
        Button[] pair = Ui.row(activity, root, new String[]{"↺ Retake", "✓ Use this"},
            new View.OnClickListener[]{ new RetakeTap(), new UseTap() });
        if (pair != null && pair.length > 1 && pair[1] != null) {
            pair[1].setBackground(Ui.roundRect(activity, Ui.ACCENT, Look.R_CTRL));
            pair[1].setTextColor(Ui.labelOn(Ui.ACCENT));
        }
        Button discard = Ui.flat(activity, root, "Discard and skip photos");
        discard.setTextColor(Ui.DIM);
        discard.setTextSize(Look.SP_FIELD_LABEL);
        discard.setOnClickListener(new SkipTap());

        shownRoot = root;
        backStep = new RetakeTap();
        body.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** One chip on the review screen's info row: a small pill on the card surface whose ink
     *  carries the state and whose SPOKEN name carries the full sentence the old card
     *  printed — the shortening is visual only. */
    private void infoPill(LinearLayout row, String text, int fg, String said) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(Look.SP_MICRO);
        t.setContentDescription(A11y.collapse(said));
        t.setBackground(Ui.roundRect(activity, Ui.SURF, Look.R_PILL));
        t.setPadding(Ui.dp(activity, 9), Ui.dp(activity, 5), Ui.dp(activity, 9), Ui.dp(activity, 5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(activity, 6);
        row.addView(t, lp);
    }

    /** Opens the collapsed note field and takes the "+ add note" line away with it. */
    private final class AddNoteTap implements View.OnClickListener {
        private final View box, opener;
        AddNoteTap(View b, View o) { box = b; opener = o; }
        @Override public void onClick(View v) {
            box.setVisibility(View.VISIBLE);
            opener.setVisibility(View.GONE);
            if (noteInput != null) noteInput.requestFocus();
        }
    }

    /* ------------------------------------------------------------ align screen */

    /**
     * The ALIGN stage: the just-taken photo, full-bleed in a square-ish frame, under a fixed
     * cylinder guide and — when an earlier photo of this SAME view exists — a ghost of it.
     * The user pans, pinches and slightly rotates the new photo until its tube sits where
     * the old one's did; Save bakes that transform into the pixels and hands the result to
     * the ordinary save path.
     *
     * WHY A GHOST AND A GUIDE, not one or the other. The guide is an ABSOLUTE target — it
     * says the same thing on the very first photo, when there is nothing to compare against,
     * so photo one is already framed like photo twenty. The ghost is a RELATIVE target — it
     * is the only thing that can catch a slow drift in distance across months, which a fixed
     * outline cannot, because a tube that is 8 % further away still fits inside a generous
     * outline. Both, or the chain drifts.
     *
     * The ghost is chosen by Align.referenceFor among this view's photos TAKEN THE SAME WAY
     * (ghostCandidates: at rest, or under a hold - point 19), so the photo you align to is
     * one framed the way this one is. There is no amber anywhere on this screen: nothing
     * here commands the pump.
     */
    /**
     * ARRIVING at the align stage from the shutter. The reset lives here rather than in
     * showAlign() because showAlign() is now also the way BACK from the ghost picker, and
     * resetting there would throw away the alignment the user had already done just because
     * they went to look at a date.
     */
    private void enterAlign() {
        alignT.zoom = 1f; alignT.rotDeg = 0f; alignT.panX = 0f; alignT.panY = 0f;
        ghostOpacity = Align.DEFAULT_GHOST;
        // The ghost choice resets on ARRIVAL: the default — the most recent earlier photo of
        // this view — is the right anchor for the next photo too, and a sticky override
        // would silently re-anchor a whole chain to one old date.
        ghostOverrideId = null;
        ghostMonth = null;
        // The turn and the framing reset on ARRIVAL too, and for the same reason the ghost
        // choice does: they are about THIS photo. A sticky quarter turn would silently
        // rotate the next capture — which arrives from the camera already upright — a
        // quarter turn wrong, and a sticky landscape guide would ask the next portrait shot
        // to be aligned sideways. The photo itself is not re-rotated here: `pendingBitmap`
        // is a fresh decode of a fresh capture at this point, so 0 is the truth about it.
        alignQuarter = 0;
        alignLandscape = false;
        showAlign();
    }

    private void showAlign() {
        body.removeAllViews();
        stopLevel();
        loadGhost();

        final String view = shot.pendingView();
        final String viewLower = view == null ? "front" : view.toLowerCase(Locale.US);

        LinearLayout root = Ui.col(activity);

        LinearLayout hdr = Ui.header(activity, root, "Align", new AlignBackTap());
        Button save = smallButton("Save");
        save.setTextColor(Ui.GOOD);
        save.setContentDescription("Save the aligned photo");
        save.setOnClickListener(new AlignSaveTap());
        hdr.addView(save);

        // The frame: the photo, the guide and the ghost, all on one Canvas. It takes the
        // layout weight so it is as close to square as the screen allows without the
        // controls below being pushed off.
        FrameLayout wrap = new FrameLayout(activity);
        wrap.setBackgroundColor(0xFF07090C);
        alignView = new AlignView(activity);
        alignView.photo = pendingBitmap;
        alignView.ghost = ghostBitmap;
        alignView.t = alignT;
        alignView.ghostOpacity = ghostOpacity;
        alignView.landscape = alignLandscape;
        alignView.setContentDescription(frameDescription(viewLower));
        wrap.addView(alignView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // THE PRECISION-MODE HUD: hidden until a long-press on the preview above starts the
        // one-finger fine zoom/rotate drag (round-2 C8). Same translucent-chip treatment the
        // pre-capture tilt/turn readout uses, so it reads as the same kind of floating
        // instrument rather than a new visual language for this screen.
        precisionHud = new TextView(activity);
        precisionHud.setVisibility(View.GONE);
        precisionHud.setTextColor(Ui.TEXT);
        precisionHud.setTypeface(android.graphics.Typeface.MONOSPACE);
        precisionHud.setTextSize(Look.SP_CAPTION);
        precisionHud.setBackground(Ui.roundRect(activity, Look.HUD_BG, Look.R_CTRL));
        precisionHud.setPadding(Ui.dp(activity, Look.S4), Ui.dp(activity, Look.S3),
                                Ui.dp(activity, Look.S4), Ui.dp(activity, Look.S3));
        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hudLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hudLp.topMargin = Ui.dp(activity, Look.S4);
        wrap.addView(precisionHud, hudLp);
        // EXPLICIT height, not weight. `root` lives inside the body ScrollView, which gives
        // its child unbounded height — so a weight-1/height-0 child measures to ZERO pixels
        // and the photo frame collapsed to nothing (the black gap the user photographed:
        // toolbar and text present, no photo, no guide, gestures invisible). A square the
        // width of the screen is what a viewfinder wants anyway.
        int side = activity.getResources().getDisplayMetrics().widthPixels
                 - Ui.dp(activity, 28);   // body's horizontal padding
        LinearLayout.LayoutParams frameLp =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, side);
        root.addView(wrap, frameLp);

        // What is underneath, said in words. A ghost is a faint image; a user who cannot
        // see it (or cannot see at all) still needs to know which date is being matched.
        LinearLayout refRow = new LinearLayout(activity);
        refRow.setOrientation(LinearLayout.HORIZONTAL);
        refRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView refLine = new TextView(activity);
        refLine.setText(ghostBitmap != null
            ? Align.referenceLine(ghostDayLabel)
            : Align.noReferenceLine(Gallery.viewLabel(viewLower)));
        refLine.setTextColor(ghostBitmap != null ? Ui.ACCENT : Ui.DIM);
        refLine.setTextSize(Look.SP_FIELD_LABEL);
        refLine.setPadding(Ui.dp(activity, 2), Ui.dp(activity, 6), 0, Ui.dp(activity, 4));
        refRow.addView(refLine, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // "change ›" — the ghost DATE is now a choice, not a fact. The line always said
        // which date was underneath; what it could not say was that a different one was
        // available, so a user wanting to align against a specific earlier photo had no way
        // to ask. It opens the SAME calendar Compare uses (CalendarGrid over PhotoCalendar),
        // dotted on the dates that have a photo of THIS view — so the photo you can align
        // to is exactly the photo you could compare to. Only shown when there is something
        // to change: with no earlier photo of this view at all, there is no picker to open.
        if (Compare.withPhotos(model.measLog, viewLower).size() > 0) {
            Button change = smallButton("Change ›");                           // PR-27
            change.setContentDescription("Change which date the ghost is, on a calendar");
            change.setOnClickListener(new GhostPickTap());
            refRow.addView(change);
        }
        root.addView(refRow, wrapLp());

        // The ghost slider exists only when there IS a ghost — a slider that moves nothing
        // is worse than no slider.
        if (ghostBitmap != null) root.addView(ghostRow(), wrapLp());

        root.addView(controlRow(), wrapLp());

        alignReadout = new TextView(activity);
        alignReadout.setTextColor(Ui.DIM);
        alignReadout.setTextSize(Look.SP_MICRO);
        alignReadout.setPadding(Ui.dp(activity, 2), 0, 0, Ui.dp(activity, 4));
        root.addView(alignReadout, wrapLp());
        updateAlignReadout();

        // SAVE AS DEFAULT FOR THIS VIEW. Records the transform on screen as this view's
        // profile — it changes NOTHING about this photo (which is still saved by Save
        // above, with its own baked pixels); its whole effect is that the NEXT photo of
        // this view turns the frame green when it is framed the same way. Separated from
        // Save on purpose: "this is my framing from now on" is a different decision from
        // "keep this photo", and one button doing both would silently re-anchor the chain
        // every time someone saved a photo they had nudged.
        Button asDefault = Ui.flat(activity, root, "Save as default for this view");
        asDefault.setContentDescription("Save the current framing as the default for the "
            + Shot.label(view == null ? Shot.FRONT : view) + " view");
        asDefault.setOnClickListener(new SaveProfileTap());

        Button skip = Ui.flat(activity, root, "Skip align — save as taken");
        skip.setContentDescription("Skip align, save the photo exactly as taken");
        skip.setOnClickListener(new AlignSkipTap());

        Ui.noteInfo(activity, root,
            "Drag to move, pinch to zoom, two fingers to rotate.", "Align",
            "Drag to move, pinch to zoom, two fingers to rotate a "
            + "little — or use the buttons. Saving bakes what you see into the photo, so "
            + "Compare lines up later. Nothing is saved until you tap Save.");

        shownRoot = root;
        backStep = new AlignBackTap();
        body.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** The ghost's opacity slider. 48dp tall so the thumb is a legal touch target, and named
     *  for what it changes rather than for the bare percentage a SeekBar announces itself. */
    private LinearLayout ghostRow() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(activity);
        label.setText("Overlay");                                              // PR-27
        label.setTextColor(Ui.DIM);
        label.setTextSize(Look.SP_CAPTION);
        r.addView(label);

        SeekBar sb = new SeekBar(activity);
        sb.setMax(100);
        sb.setProgress(Math.round(ghostOpacity * 100f));
        sb.setContentDescription("Opacity of the previous photo underneath");
        sb.setMinimumHeight(Ui.dp(activity, 48));
        sb.setOnSeekBarChangeListener(new GhostSeekListener());
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(activity, 10);
        r.addView(sb, lp);
        return r;
    }

    /**
     * Just Reset. Zoom and rotate are FINGER gestures on the photo — pinch and two-finger
     * twist, like a camera app — per the user's explicit ask; the +/-/rotate buttons were
     * removed as clutter. Reset stays because it has no gesture equivalent and is exactly
     * what you reach for after a bad pinch. The readout underneath states the current
     * zoom/rotation in words, which is also what a screen reader gets.
     */
    private LinearLayout controlRow() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);

        // ROTATE. A quarter turn per tap, four taps back to where it started — the coarse
        // turn the fine +/-20 degree gesture deliberately cannot do, and the only fix for a
        // photo the camera handed back on its side. Named with the turn it will PRODUCE so
        // a screen-reader user knows what a tap does rather than only where they are.
        Button rot = smallButton("⟳ Rotate");
        rot.setContentDescription("Rotate the photo a quarter turn right. Currently "
            + Align.quarterName(alignQuarter) + ".");
        rot.setOnClickListener(new QuarterTurnTap());
        LinearLayout.LayoutParams rotLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 48));
        rotLp.rightMargin = Ui.dp(activity, 6);
        r.addView(rot, rotLp);

        // FRAMING. Which way the guide lies, and with it which way the ghost is turned.
        Button framing = smallButton(alignLandscape ? "▭ Landscape" : "▯ Portrait");
        framing.setContentDescription(Align.framingToggleName(alignLandscape));
        framing.setOnClickListener(new FramingTap());
        LinearLayout.LayoutParams frLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 48));
        frLp.rightMargin = Ui.dp(activity, 6);
        r.addView(framing, frLp);

        Button reset = smallButton("Reset");
        reset.setContentDescription("Reset the alignment to the photo as taken");
        reset.setOnClickListener(new AlignResetTap());
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 48));
        resetLp.rightMargin = Ui.dp(activity, 6);
        r.addView(reset, resetLp);

        // MATCH (round-2 C8). Snaps zoom/rotation/pan straight to this view's SAVED default
        // profile — the same "Save as default for this view" button below records — so a
        // user who nudged the framing by hand on the LAST photo of this view does not have
        // to eyeball their way back to it by pinch on every later one. Reuses
        // updateAlignReadout()'s existing Align.matchesProfile check for the green frame
        // rather than a second copy of that comparison: after the snap the live transform
        // and the saved profile are (up to float rounding) the same numbers, so the very
        // same tolerance check that lights the frame for a hand-held match lights it here.
        Button match = new Button(activity);
        match.setText("⌖ Match");
        match.setAllCaps(false);
        match.setTextSize(Look.SP_FIELD_LABEL);
        match.setTextColor(Ui.GOOD);
        match.setMinHeight(Ui.dp(activity, 48));
        match.setMinWidth(Ui.dp(activity, 48));
        match.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 6), Ui.dp(activity, 10), Ui.dp(activity, 6));
        match.setBackground(Ui.roundRect(activity, Ui.SURFHI, Look.R_CTRL));
        match.setContentDescription("Snap zoom, rotation and pan to the saved default "
            + "framing for this view");
        match.setOnTouchListener(new Ui.Press());
        match.setOnClickListener(new MatchProfileTap());
        LinearLayout.LayoutParams matchLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(activity, 48));
        r.addView(match, matchLp);
        return r;
    }

    /**
     * ⌖ MATCH's tap: reads this view's saved {@link Model.EditProfile} (the same accessor
     * the green frame and the readout already use) and copies its four numbers straight onto
     * the live transform, so the photo jumps to exactly the framing "Save as default for this
     * view" last recorded — no hand-held pinch/twist/drag required to land inside tolerance.
     * With no saved profile yet there is nothing to snap to; the tap says so and changes
     * nothing, rather than silently doing nothing at all.
     *
     * PAN is clamped against the LIVE frame's own measured size (alignView's, not the size
     * the profile happened to be saved under) — the same clamp a drag or Reset goes through —
     * so a profile saved on a differently-sized frame can never leave the transform outside
     * the range this screen could otherwise reach.
     */
    private final class MatchProfileTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            Model.EditProfile p = savedProfileForPendingView();
            if (p == null || !p.complete()) {
                toast("No saved framing for this view yet");
                return;
            }
            alignT.zoom = Align.clampZoom(p.zoom.floatValue());
            alignT.rotDeg = Align.clampRot(p.rot.floatValue());
            int fw = alignView == null ? 0 : alignView.getWidth();
            int fh = alignView == null ? 0 : alignView.getHeight();
            alignT.panX = Align.clampPan(p.panX.floatValue(), fw);
            alignT.panY = Align.clampPan(p.panY.floatValue(), fh);
            if (alignView != null) alignView.invalidate();
            // The exact same green-frame check every other change on this screen goes
            // through — reused, not re-derived, so "matched" can never mean something
            // slightly different here than it does after a hand-held gesture.
            updateAlignReadout();
        }
    }

    /** The align frame's spoken name, with the turn and the framing appended — built in one
     *  place so the frame, the readout and the two buttons cannot describe the same state
     *  in three different ways. */
    private String frameDescription(String viewLower) {
        return Align.frameName(Gallery.viewLabel(viewLower), ghostBitmap != null,
                               alignT.zoom, alignT.rotDeg)
             + " Photo " + Align.quarterName(alignQuarter) + ", "
             + Align.framingName(alignLandscape) + " guide."
             + (Align.matchesProfile(alignT, savedProfileForPendingView())
                ? " Matches your saved framing for this view." : "");
    }

    /** The saved default profile for the view currently being aligned, or null when this
     *  view has none. One accessor so the border, the readout and the spoken name can never
     *  disagree about which profile is being matched against. */
    private Model.EditProfile savedProfileForPendingView() {
        if (model == null || shot == null) return null;
        String v = shot.pendingView();
        return v == null ? null : model.editProfile(v);
    }

    /** The one place the zoom/rotation readout and the frame's spoken name are refreshed,
     *  so a change made by a button and a change made by a gesture say the same thing. The
     *  frame's description is rebuilt alongside the text it describes, never mutated
     *  independently of it — the staleness rule A11y#value states. */
    private void updateAlignReadout() {
        boolean match = Align.matchesProfile(alignT, savedProfileForPendingView());
        if (alignReadout != null) {
            alignReadout.setText("Zoom " + Align.pct(alignT.zoom)
                + "  ·  rotation " + Align.degrees(alignT.rotDeg)
                + "  ·  " + Align.quarterName(alignQuarter)
                + "  ·  " + Align.framingName(alignLandscape) + " guide"
                // The green border, said in words. A colour is not a readout.
                + (match ? "  ·  matches your saved framing" : ""));
            alignReadout.setTextColor(match ? Ui.GOOD : Ui.DIM);
        }
        if (alignView != null && alignView.profileMatch != match) {
            alignView.profileMatch = match;
            alignView.invalidate();     // the border changed, not only the words
        }
        if (alignView != null) {
            String view = shot == null || shot.pendingView() == null
                ? "front" : shot.pendingView().toLowerCase(Locale.US);
            alignView.setContentDescription(frameDescription(view));
        }
    }

    /** Reveals the precision-mode HUD and gives it its first reading — called once, the
     *  instant AlignView's long-press timer fires. */
    private void showPrecisionHud() {
        if (precisionHud == null) return;
        precisionHud.setVisibility(View.VISIBLE);
        updatePrecisionHud();
    }

    /** Hides the HUD — called on every precision-mode exit path (finger up, cancelled, or a
     *  second finger taking over), so it can never be left on screen after the drag it was
     *  reporting on has ended. */
    private void hidePrecisionHud() {
        if (precisionHud != null) precisionHud.setVisibility(View.GONE);
    }

    /** The live "1.24× · -3.2°" readout, refreshed on every precision-drag MOVE. A no-op
     *  while the HUD is hidden, so a stray late call cannot flash it back on with a stale
     *  number. */
    private void updatePrecisionHud() {
        if (precisionHud == null || precisionHud.getVisibility() != View.VISIBLE) return;
        precisionHud.setText(String.format(Locale.US, "%.2f× · %.1f°",
            alignT.zoom, alignT.rotDeg));
    }

    /**
     * ONE QUARTER TURN, applied to the PIXELS. `pendingBitmap` is replaced by a rotated
     * copy, so from this instant the align frame, the baked save, the file on disk and
     * every screen that later reads that file all see one photo the right way up — there is
     * no orientation field to carry and nothing downstream that has to remember to apply
     * one. See {@link Align}'s quarter-turn section for the storage decision in full.
     *
     * The source bitmap is deliberately NOT recycled, for the reason
     * {@link #releasePendingBitmap} documents: a view may still hold the old reference for
     * the remainder of this frame, and a recycled bitmap in a draw pass kills the process.
     * These are <= 1000 px decodes; the old one is collected once nothing points at it.
     *
     * A failed allocation leaves the photo exactly as it was and says so. Losing a capture
     * over a rotation the user can simply skip would be the worse failure.
     */
    private void rotateQuarter() {
        if (pendingBitmap == null) { toast("Nothing to rotate yet"); return; }
        Bitmap src = pendingBitmap;
        try {
            Matrix m = new Matrix();
            m.postRotate(90);
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (out == null) { toast("Could not rotate the photo"); return; }
            pendingBitmap = out;
        } catch (Exception e) {
            toast("Could not rotate the photo");
            return;
        } catch (OutOfMemoryError e) {
            toast("Could not rotate the photo");
            return;
        }
        alignQuarter = Align.nextQuarter(alignQuarter);
        if (alignView != null) {
            alignView.photo = pendingBitmap;
            alignView.invalidate();
        }
        updateAlignReadout();
    }

    /**
     * Decodes the ghost: the most recent EARLIER reading with a photo for the view being
     * captured. Compare.withPhotos gives the candidates (already same-view-only, already
     * newest-first) and Align.referenceFor picks among them — including refusing the
     * reading currently being captured for, so a RETAKE ghosts against the previous
     * session's photo rather than against the shot it is replacing.
     */
    /**
     * POINT 19 - THE PHOTOS A GHOST MAY COME FROM: this view, TAKEN THE SAME WAY as the one
     * being aligned - under a hold, or at rest. A standardised shot is framed with the
     * cylinder in view and the phone further back (which is why the reference angle is kept
     * per mode), so an at-rest ghost under a standardised capture lined it up against the
     * wrong framing, and the other way round. The picker below offers the same list.
     */
    private List<Model.Reading> ghostCandidates(String viewKey) {
        return Compare.withPhotosTaken(model.measLog, viewKey, pendingHeldKpa != null);
    }

    private void loadGhost() {
        releaseGhost();
        if (model == null || shot == null) return;
        String view = shot.pendingView();
        if (view == null) return;
        String viewKey = view.toLowerCase(Locale.US);
        List<Model.Reading> ps = ghostCandidates(viewKey);
        // The DEFAULT is unchanged: Align.referenceFor's most recent earlier photo of this
        // view. An override only ever replaces WHICH reading is ghosted, and only when it
        // still resolves to one of the same candidates — a picked reading deleted since is
        // not a phantom, it falls back to the default (the same no-phantom rule
        // PhotoCalendar.prune applies to Compare's pair).
        Model.Reading ref = null;
        if (ghostOverrideId != null)
            for (int i = 0; i < ps.size(); i++)
                if (ghostOverrideId.equals(ps.get(i).id)) { ref = ps.get(i); break; }
        if (ref == null)
            ref = Align.referenceFor(ps, System.currentTimeMillis(), readingId);
        if (ref == null) return;
        Model.Reading.Photo p = Compare.photoOf(ref, viewKey);
        if (p == null || p.path == null) return;
        Bitmap ghostRaw = Photos.decodeOriented(p.path);
        // THE PRIVACY BLUR REACHES THE GHOST TOO (Model#privacyBlur). The ghost is a
        // previous photograph of the user, drawn over a live camera preview — the single
        // most exposed image the app puts on screen, and the one most likely to be on
        // screen somewhere other than a locked bathroom door.
        //
        // A blurred ghost still does its job: it is an ALIGNMENT aid, and what alignment
        // needs is the silhouette and where the frame sat, which a pixelated copy keeps.
        // The fine detail it removes is the part that was never what the overlay was for.
        // There is no 👁 here on purpose — this screen's controls belong to a live camera
        // with a shutter, and a reveal control on it would be one more thing to hit by
        // mistake while holding the phone at arm's length. Turn the setting off to align
        // against a sharp reference.
        if (ghostRaw != null && model != null && model.privacyBlur) {
            Bitmap blurred = Photos.pixelate(ghostRaw);
            if (blurred != ghostRaw) ghostRaw.recycle();
            ghostRaw = blurred;
        }
        ghostBitmap = ghostRaw;
        if (ghostBitmap != null) {
            ghostDayLabel = new SimpleDateFormat("MMM d", Locale.US)
                .format(new java.util.Date(ref.ts));
        }
    }

    /**
     * THE GHOST DATE PICKER. Compare's calendar, opened from the Align screen's ghost line:
     * the months this view has photos in, dotted on the dates that carry one, and a tap
     * swaps the ghost to that date's photo and returns to Align.
     *
     * It is the SAME grid Compare draws — CalendarGrid over PhotoCalendar — because a second
     * calendar would be free to disagree with the first about which dates are even
     * available. The only difference is the question: one date rather than a pair, so the
     * selected cell carries "●" instead of an A/B letter.
     */
    private void showGhostPicker() {
        final String view = shot.pendingView();
        final String viewKey = view == null ? "front" : view.toLowerCase(Locale.US);
        List<Model.Reading> ps = ghostCandidates(viewKey);
        if (ghostMonth == null)
            ghostMonth = PhotoCalendar.initialMonth(ps, System.currentTimeMillis());

        body.removeAllViews();
        stopLevel();
        LinearLayout root = Ui.col(activity);
        Ui.header(activity, root, "Ghost date", new GhostPickBackTap());

        root.addView(CalendarGrid.card(activity, ps, ghostMonth, new GhostRoles(),
            new GhostTaps(),
            "Tap a dotted date to ghost against that " + Gallery.viewLabel(viewKey)
            + " photo."), wrapLp());

        Ui.noteInfo(activity, root,
            "By default the ghost is the most recent earlier photo of this view, taken the "
            + "same way.", "Ghost",
            "By default the ghost is the most recent earlier photo of "
            + "this view taken the same way — at rest, or under a hold — which is what keeps "
            + "the chain from drifting one photo at a time. Picking a date here changes it "
            + "for this photo only.");

        if (ghostOverrideId != null) {
            Button reset = Ui.flat(activity, root, "Back to the most recent (default)");
            reset.setOnClickListener(new GhostResetTap());
        }
        shownRoot = root;
        backStep = new GhostPickBackTap();
        body.addView(root);
    }

    /** One date at a time, so the selected cell carries "●" rather than an A/B letter. */
    private final class GhostRoles implements CalendarGrid.Roles {
        @Override public String roleOf(String readingId) {
            return readingId != null && readingId.equals(ghostedId()) ? "●" : null;
        }
    }

    /** Which reading is ACTUALLY ghosted right now — the override if it resolves, otherwise
     *  whatever the default picked. Asked rather than remembered, so the tick in the grid
     *  can never mark a date the frame is not actually showing. */
    private String ghostedId() {
        if (shot == null || model == null) return null;
        String view = shot.pendingView();
        if (view == null) return null;
        String viewKey = view.toLowerCase(Locale.US);
        List<Model.Reading> ps = ghostCandidates(viewKey);
        if (ghostOverrideId != null)
            for (int i = 0; i < ps.size(); i++)
                if (ghostOverrideId.equals(ps.get(i).id)) return ghostOverrideId;
        Model.Reading ref = Align.referenceFor(ps, System.currentTimeMillis(), readingId);
        return ref == null ? null : ref.id;
    }

    private final class GhostTaps implements CalendarGrid.Taps {
        @Override public void onDay(String id) {
            ghostOverrideId = id;
            // Straight back to Align: the point of the picker is the ghost, and the ghost is
            // only visible on the frame. showAlign() reloads it through loadGhost().
            showAlign();
        }
        @Override public void onMonth(int dir) {
            String view = shot.pendingView();
            String viewKey = view == null ? "front" : view.toLowerCase(Locale.US);
            List<Model.Reading> ps = ghostCandidates(viewKey);
            PhotoCalendar.Month next =
                PhotoCalendar.stepMonth(ps, ghostMonth, dir, System.currentTimeMillis());
            if (next.equals(ghostMonth)) return;
            ghostMonth = next;
            showGhostPicker();
        }
    }

    private final class GhostPickTap implements View.OnClickListener {
        @Override public void onClick(View v) { showGhostPicker(); }
    }

    private final class GhostPickBackTap implements View.OnClickListener {
        @Override public void onClick(View v) { showAlign(); }
    }

    private final class GhostResetTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            ghostOverrideId = null;
            showAlign();
        }
    }

    private void releaseGhost() {
        // Clear the VIEW's reference first — after this nothing that draws can reach the
        // bitmap, recycled or not. The same ordering CompareScreen#releaseBitmaps uses, and
        // for the same reason: a draw pass must never see a recycled bitmap.
        if (alignView != null) alignView.ghost = null;
        if (ghostBitmap != null) { ghostBitmap.recycle(); ghostBitmap = null; }
        ghostDayLabel = null;
    }

    /** Everything the align stage owns, dropped. The PHOTO is not recycled here — it is
     *  `pendingBitmap`, which releasePendingBitmap() owns and deliberately does not recycle
     *  (see its own note about the still-attached view). Only the ghost is ours. */
    private void releaseAlign() {
        releaseGhost();
        if (alignView != null) { alignView.photo = null; alignView = null; }
        alignReadout = null;
        precisionHud = null;
    }

    /**
     * Renders the aligned photo into a NEW bitmap the size of the frame the user was
     * looking at, through the very Matrix the frame drew with — so the file on disk is
     * exactly what was on screen. That bitmap then goes through the ordinary save path;
     * nothing downstream knows or needs to know that a transform happened.
     *
     * Returns the ORIGINAL bitmap unchanged when there is nothing to bake (the transform is
     * the identity) or when the render cannot be allocated. Both are honest fallbacks: an
     * un-aligned photo is a photo, and failing the save outright over an alignment the user
     * may not even have touched would lose the capture.
     */
    private Bitmap bakeAligned() {
        if (pendingBitmap == null) return null;
        if (alignView == null) return pendingBitmap;
        int fw = alignView.getWidth(), fh = alignView.getHeight();
        if (fw <= 0 || fh <= 0) return pendingBitmap;
        if (alignT.isIdentity()) {
            // A cover-fit identity transform still CROPS to the frame's aspect, which is a
            // real change to the pixels — but it is one the user did not ask for, so an
            // untouched align stage saves the original frame exactly as Skip align does.
            return pendingBitmap;
        }
        try {
            Bitmap out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawColor(0xFF07090C);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            c.drawBitmap(pendingBitmap, alignMatrix(fw, fh), p);
            return out;
        } catch (Exception e) {
            return pendingBitmap;
        } catch (OutOfMemoryError e) {
            return pendingBitmap;
        }
    }

    /**
     * THE COMPOSITION, and the one place it is built. The order below must stay identical
     * to the contract stated in Align's class doc, which is what Align.mapX/mapY assert:
     *
     *     translate(-bmpW/2, -bmpH/2) -> scale(fit * zoom) -> rotate(rotDeg)
     *       -> translate(frameW/2 + panX, frameH/2 + panY)
     *
     * The assertions cannot see this method — they pin the arithmetic, not the Matrix — so
     * this is the code to re-read first when the rendered result and the asserted geometry
     * disagree. Both the on-screen draw and the baked save call THIS method, so they cannot
     * drift apart from each other.
     */
    private Matrix alignMatrix(int frameW, int frameH) {
        Matrix m = new Matrix();
        if (pendingBitmap == null) return m;
        int bw = pendingBitmap.getWidth(), bh = pendingBitmap.getHeight();
        float s = Align.totalScale(bw, bh, frameW, frameH, alignT.zoom);
        m.postTranslate(-bw / 2f, -bh / 2f);
        m.postScale(s, s);
        m.postRotate(Align.clampRot(alignT.rotDeg));
        m.postTranslate(frameW / 2f + alignT.panX, frameH / 2f + alignT.panY);
        return m;
    }

    private final class AlignSaveTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            Bitmap baked = bakeAligned();
            // The transform is BAKED into `baked` above; the profile handed alongside it is
            // provenance only — a record of how far the photo was pushed, never something
            // a later screen re-applies (see Model.Reading.Photo#editZoom). Read before
            // commitPending, which releases the align state.
            commitPending(baked, Align.profileOf(alignT));
        }
    }

    /** Save as taken. Deliberately the SAME commit path with the untransformed bitmap —
     *  not a second save route that could drift from the first. */
    private final class AlignSkipTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            // No transform recorded: nothing was applied, and writing the identity here
            // would claim "aligned, at 1×" where the truth is "align was skipped".
            commitPending(pendingBitmap, null);
        }
    }

    /** Back out of align to the review screen. The capture is NOT discarded — the note
     *  survives (it was banked at Use and is re-shown by showReview), and the transform is
     *  dropped, because a half-finished alignment carried back into a screen that cannot
     *  show it would be invisible state. */
    private final class AlignBackTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            releaseAlign();
            showReview();
        }
    }

    /** "Save as default for this view" — persists the transform on screen as this view's
     *  profile and refreshes the frame, which is now green by construction (profileOf and
     *  matchesProfile clamp the same way, so a just-saved profile always matches). */
    private final class SaveProfileTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            String view = shot == null ? null : shot.pendingView();
            if (view == null) { toast("Nothing to save — take a photo first"); return; }
            model.setEditProfile(view, Align.profileOf(alignT));
            Store.save(activity, model);
            updateAlignReadout();
            toast(Shot.label(view) + " framing saved as the default");
        }
    }

    private final class AlignResetTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            alignT.zoom = 1f; alignT.rotDeg = 0f; alignT.panX = 0f; alignT.panY = 0f;
            if (alignView != null) alignView.invalidate();
            updateAlignReadout();
            toast("Alignment reset");
        }
    }

    private final class QuarterTurnTap implements View.OnClickListener {
        @Override public void onClick(View v) { rotateQuarter(); }
    }

    /**
     * Flips the guide (and with it the ghost's turn) between portrait and landscape.
     * showAlign() is re-run rather than only invalidating the frame, because the toggle's
     * own LABEL and spoken name have to change with it — a toggle still reading "Portrait"
     * over a landscape guide is exactly the kind of stale description A11y#value forbids.
     * The transform survives: the framing is a question about the GUIDE, and throwing away
     * an alignment because the user looked at it the other way round would be a trap.
     */
    private final class FramingTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            alignLandscape = !alignLandscape;
            showAlign();
        }
    }

    private final class ZoomTap implements View.OnClickListener {
        private final int dir;
        ZoomTap(int d) { dir = d; }
        @Override public void onClick(View v) {
            alignT.zoom = Align.steppedZoom(alignT.zoom, dir);
            if (alignView != null) alignView.invalidate();
            updateAlignReadout();
        }
    }

    private final class RotateTap implements View.OnClickListener {
        private final int dir;
        RotateTap(int d) { dir = d; }
        @Override public void onClick(View v) {
            alignT.rotDeg = Align.steppedRot(alignT.rotDeg, dir);
            if (alignView != null) alignView.invalidate();
            updateAlignReadout();
        }
    }

    private final class GhostSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
            if (!fromUser) return;
            ghostOpacity = Align.clampGhost(progress / 100f);
            if (alignView != null) { alignView.ghostOpacity = ghostOpacity; alignView.invalidate(); }
        }
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }

    /**
     * The align frame's Canvas and its gestures. Draws, bottom to top: the ghost (cover-fit
     * to the frame, at the chosen opacity), the new photo through the transform Matrix, then
     * the cylinder guide on top so the guide is never hidden by the thing being aligned.
     *
     * DEVICE-ONLY, and honestly so: nothing about what these pixels look like is verified by
     * test.sh. What IS verified is every number this class asks Align for — the fit, the
     * clamps, the composed mapping, the guide's edges and radius, the gesture arithmetic —
     * so a defect here is a defect in the drawing or the wiring, not in the geometry.
     *
     * Gestures: one finger pans; a ScaleGestureDetector handles the pinch; a second pointer
     * going down records the two-pointer angle, and the rotation is the normalised delta
     * from it (Align.rotationDelta), added to the rotation the gesture STARTED from — so a
     * rotation clamped at the limit does not keep accumulating invisible degrees that unwind
     * when the user turns back.
     */
    private final class AlignView extends View {
        Bitmap photo, ghost;
        Align.T t;
        float ghostOpacity = Align.DEFAULT_GHOST;
        /** Which way the guide lies — the screen's alignLandscape, copied in at build time
         *  so the draw pass reads one field rather than reaching back into the host. */
        boolean landscape = false;
        /** TRUE while the live transform is within tolerance of this view's SAVED default
         *  profile (Align#matchesProfile). Drawn as a green frame border — the same green,
         *  and the same "you are where you should be" meaning, the level bubble uses, so
         *  the two indicators on the capture path do not speak two visual languages.
         *  Recomputed on every transform change, so it is live while a finger is down. */
        boolean profileMatch = false;

        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint ghostPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint();
        private final android.graphics.RectF guideRect = new android.graphics.RectF();
        private final android.graphics.Rect gSrc = new android.graphics.Rect();
        private final android.graphics.Rect gDst = new android.graphics.Rect();
        private final ScaleGestureDetector scale;

        private float lastX, lastY;
        private float gestureStartAngle, gestureStartRot;
        private boolean rotating;

        /* ---- one-finger long-press precision zoom/rotate (round-2 C8) --------------
         * ADDITIVE to the gesture handling above, not a competing listener: everything
         * below is read and written from inside THIS SAME onTouchEvent, alongside the
         * pinch/twist state it already tracks, so there is exactly one place that decides
         * what a touch means. See onTouchEvent's own comment for the coexistence argument
         * in full. */

        /** Where ACTION_DOWN landed — the long-press timer's own anchor, kept separate
         *  from lastX/lastY (which the ordinary one-finger PAN keeps sliding forward every
         *  MOVE) so "has the finger travelled far enough to mean a pan, not a hold" is
         *  measured from the touch's true start rather than from wherever panning last
         *  updated it to. */
        private float downX, downY;
        /** The system's own tap-vs-drag distance, read once per view (device DPI, not a
         *  guessed constant) — the same threshold every other Android view uses to decide
         *  a MOVE is a deliberate drag rather than finger wobble, so the long press is
         *  cancelled by exactly the movement that would already cancel a click elsewhere
         *  in this app. */
        private final int touchSlop;
        /** TRUE from the moment the ~400ms hold lands until the finger lifts or a second
         *  finger arrives. While true, ACTION_MOVE with one pointer drives fine zoom/
         *  rotation instead of the pan branch below — the two are mutually exclusive
         *  outcomes of the same single-finger MOVE, never both applied to one event. */
        private boolean precisionMode;
        /** The transform's zoom/rotation at the instant precision mode was entered — the
         *  drag below is always measured from THIS pair, never accumulated move-to-move,
         *  for the identical reason gestureStartAngle/gestureStartRot are: an accumulated
         *  value can bank degrees or zoom past the clamp that silently unwind on the way
         *  back. */
        private float precisionStartX, precisionStartY, precisionStartZoom, precisionStartRot;
        private final LongPressCheck longPressCheck = new LongPressCheck();

        AlignView(Activity act) {
            super(act);
            bgPaint.setColor(0xFF07090C);
            // A LIGHT stroke, not a state colour: the guide is a drawn target, not a
            // reading and not a pump state. Amber in particular would be a lie — nothing on
            // this screen commands the pump.
            guidePaint.setColor(Ui.TEXT);
            guidePaint.setAlpha(140);
            guidePaint.setStyle(Paint.Style.STROKE);
            guidePaint.setStrokeWidth(Ui.dp(act, 2));
            borderPaint.setColor(Ui.GOOD);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Ui.dp(act, 3));
            scale = new ScaleGestureDetector(act, new PinchListener());
            touchSlop = ViewConfiguration.get(act).getScaledTouchSlop();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            c.drawRect(0, 0, w, h, bgPaint);

            // ORDER MATTERS, and it was backwards: the new photo is the SOLID BASE the user is
            // moving, drawn first; the ghost is the TRANSLUCENT OVERLAY on top, so the user
            // lines the new tube up against a see-through reference. The previous order drew
            // the ghost underneath and the opaque new photo on top, so the ghost was hidden
            // everywhere the new photo covered it (the user's screenshot: a solid floor photo
            // with the reference only peeking out at the edges).
            if (photo != null && !photo.isRecycled()) {
                c.drawBitmap(photo, alignMatrix(w, h), imgPaint);
            }
            if (ghost != null && !ghost.isRecycled()) {
                ghostPaint.setAlpha(Align.ghostAlpha(ghostOpacity));
                drawCover(c, ghost, w, h, ghostPaint);
            }
            drawGuide(c, w, h);
            // LAST, on top of everything, so nothing can cover it: the frame goes green the
            // instant the transform lands within tolerance of the saved default for this
            // view, and grey again the instant it leaves. The readout underneath says the
            // same thing in words, for anyone who cannot use the colour.
            if (profileMatch) {
                float in = borderPaint.getStrokeWidth() / 2f;
                c.drawRect(in, in, w - in, h - in, borderPaint);
            }
        }

        /**
         * The ghost fills the frame the same way an un-transformed new photo would, so
         * "the tube was here last time" means the same thing in both images — and it is
         * turned a quarter when its own shape disagrees with the chosen framing, so a
         * landscape guide is never laid over a portrait reference. Align.ghostQuarter is
         * the one place that decision is made, and it is asserted.
         *
         * The turn is a canvas rotation about the frame's centre rather than a rotated
         * copy of the bitmap: the ghost is redrawn on every invalidate (the opacity slider
         * alone can fire dozens a second), and allocating a rotated bitmap per draw is how
         * a smooth slider becomes a stuttering one.
         */
        private void drawCover(Canvas c, Bitmap bmp, int w, int h, Paint p) {
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            if (bw <= 0 || bh <= 0) return;
            int quarter = Align.ghostQuarter(bw, bh, landscape);
            if (quarter != 0) { c.save(); c.rotate(quarter, w / 2f, h / 2f); }
            float destAR = (float) w / h, srcAR = (float) bw / bh;
            int sw, sh, sx, sy;
            if (srcAR > destAR) { sh = bh; sw = Math.round(bh * destAR); sx = (bw - sw) / 2; sy = 0; }
            else                { sw = bw; sh = Math.round(bw / destAR); sx = 0; sy = (bh - sh) / 2; }
            gSrc.set(sx, sy, sx + sw, sy + sh);
            gDst.set(0, 0, w, h);
            c.drawBitmap(bmp, gSrc, gDst, p);
            if (quarter != 0) c.restore();
        }

        /** The cylinder: a tube with true semicircular ends, centred, lying whichever way
         *  the framing toggle says. Every coordinate comes from Align — now from its
         *  orientation-aware rects — so the shape is the asserted one in BOTH framings and
         *  this method holds no geometry of its own. */
        private void drawGuide(Canvas c, int w, int h) {
            float[] tube = Align.tubeRect(w, h, landscape);
            guideRect.set(tube[0], tube[1], tube[2], tube[3]);
            // A PUMP silhouette, not a pill: straight side edges (the two lines the user
            // centres against), a slight end curve, a wider base flange and a cap rim so it
            // reads as the cylinder. Corner radius is small so the long edges stay
            // unmistakably straight rather than dissolving into semicircles.
            float r = Math.min(Align.tubeRadius(w, h, landscape),
                               Align.tubeCross(w, h, landscape) * 0.18f);
            c.drawRoundRect(guideRect, r, r, guidePaint);
            // Base flange — the seal skirt: a wider band across the tube's base end.
            float[] fl = Align.flangeRect(w, h, landscape);
            c.drawRect(fl[0], fl[1], fl[2], fl[3], guidePaint);
            // Cap rim — a narrow band across the far end.
            float[] rim = Align.rimRect(w, h, landscape);
            c.drawRect(rim[0], rim[1], rim[2], rim[3], guidePaint);
            // The centre line: the tube's axis, which is what "square to the lens" is
            // judged against. Drawn inside the tube only, so it does not read as a crop mark.
            float[] ax = Align.axisLine(w, h, landscape);
            c.drawLine(ax[0], ax[1], ax[2], ax[3], guidePaint);
        }

        /**
         * WHY THE ADDITIONS BELOW CANNOT FIGHT THE TWO-FINGER GESTURE (round-2 C8, and the
         * same rigor the Task 7 touch-dispatch bug needed): every branch this method can
         * take is still decided in ONE place, by ACTION type and pointer count, exactly as
         * before — nothing here adds a second OnTouchListener or a second GestureDetector
         * competing for the same events. The long-press timer is plain Handler
         * postDelayed/removeCallbacks on this View, which runs on the same UI thread as
         * onTouchEvent itself, so a pending callback and a touch event can never be "in
         * flight" at once — removeCallbacks called from inside onTouchEvent is guaranteed
         * to prevent a still-pending LongPressCheck from firing later; there is no race to
         * reason about.
         *
         *   - ACTION_DOWN always arms the timer (only a fresh gesture, all fingers
         *     previously up, reaches this case).
         *   - ACTION_POINTER_DOWN (a second finger arrives) is the handoff point: it
         *     disarms the timer AND exits precision mode FIRST, before doing exactly what
         *     it already did — record the two-pointer start angle and set `rotating`. So a
         *     second finger, whether it arrives before the long-press has fired (timer
         *     just never fires) or after (precision mode ends the instant the finger
         *     lands), always resolves to the ordinary two-finger pinch/twist from that
         *     point on. `t.rotDeg`/`t.zoom` are left exactly where the one-finger drag (or
         *     nothing) put them, so the two-finger gesture picks up from there with no
         *     jump — the same continuity gestureStartRot already gives a plain pinch that
         *     starts mid-rotation.
         *   - ACTION_MOVE branches on pointer count FIRST, same as before: pointerCount>=2
         *     always takes the rotating branch, completely unchanged, regardless of
         *     whether precision mode was ever entered. Only the pointerCount==1 branch is
         *     new-shaped, and it is a binary choice — pan OR precision-drag, decided by
         *     `precisionMode` — never both for one event.
         *   - ACTION_POINTER_UP (back down to one finger) is unchanged: it does not
         *     re-arm the long-press timer, so precision mode can only be entered from a
         *     fresh all-fingers-up ACTION_DOWN, never mid-gesture.
         *   - ACTION_UP/ACTION_CANCEL disarm the timer and exit precision mode
         *     unconditionally, so no state or pending callback survives past the finger(s)
         *     lifting.
         */
        @Override public boolean onTouchEvent(MotionEvent e) {
            scale.onTouchEvent(e);
            if (t == null) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    lastX = e.getX(); lastY = e.getY();
                    downX = e.getX(); downY = e.getY();
                    removeCallbacks(longPressCheck);
                    postDelayed(longPressCheck, Look.MS_LONG_PRESS);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    // THE HANDOFF: whatever the one-finger state was — plain pan, an
                    // armed-but-not-yet-fired timer, or live precision mode — a second
                    // finger always means "this is now the two-finger gesture", so both
                    // are cleared before that gesture's own start-state is recorded below.
                    removeCallbacks(longPressCheck);
                    if (precisionMode) exitPrecisionMode();
                    if (e.getPointerCount() >= 2) {
                        gestureStartAngle = Align.angleDeg(e.getX(0), e.getY(0), e.getX(1), e.getY(1));
                        gestureStartRot = t.rotDeg;
                        rotating = true;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (rotating && e.getPointerCount() >= 2) {
                        float now = Align.angleDeg(e.getX(0), e.getY(0), e.getX(1), e.getY(1));
                        // Added to the rotation the GESTURE started from, never accumulated
                        // onto the current one: accumulating past the clamp banks degrees
                        // that silently unwind when the user rotates back.
                        t.rotDeg = Align.clampRot(
                            gestureStartRot + Align.rotationDelta(gestureStartAngle, now));
                    } else if (!scale.isInProgress() && e.getPointerCount() == 1) {
                        if (precisionMode) {
                            // FINE ZOOM/ROTATE, from the point the long press landed — see
                            // Align.precisionZoom/precisionRot for the arithmetic, pinned
                            // by SelfTest.
                            t.zoom = Align.precisionZoom(
                                precisionStartZoom, e.getY() - precisionStartY, getHeight());
                            t.rotDeg = Align.precisionRot(
                                precisionStartRot, e.getX() - precisionStartX);
                            updatePrecisionHud();
                        } else {
                            // ORDINARY PAN, byte-for-byte the pre-existing behaviour. A
                            // move past touch-slop from the DOWN point means this is a
                            // drag, not a hold, so the pending long press is cancelled —
                            // exactly what stops a long PAN from suddenly turning into
                            // precision mode partway through once 400ms have passed.
                            if (Math.hypot(e.getX() - downX, e.getY() - downY) > touchSlop) {
                                removeCallbacks(longPressCheck);
                            }
                            t.panX = Align.clampPan(t.panX + (e.getX() - lastX), getWidth());
                            t.panY = Align.clampPan(t.panY + (e.getY() - lastY), getHeight());
                            lastX = e.getX(); lastY = e.getY();
                        }
                    }
                    invalidate();
                    updateAlignReadout();
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    // The remaining finger becomes the pan anchor, or the photo leaps by
                    // whatever the gap between the two pointers was. Deliberately does NOT
                    // re-arm the long-press timer: precision mode is only ever entered from
                    // a fresh all-fingers-up press, never mid-gesture.
                    rotating = false;
                    lastX = e.getX(0); lastY = e.getY(0);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    rotating = false;
                    removeCallbacks(longPressCheck);
                    if (precisionMode) exitPrecisionMode();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return true;
            }
        }

        /** Fires ~400ms after a one-finger ACTION_DOWN that nothing has cancelled since —
         *  no touch-slop drag, no second finger, no lift. Posted from ACTION_DOWN and
         *  reliably cancelled by removeCallbacks everywhere else in onTouchEvent, so by the
         *  time this actually runs the single finger that armed it is still the only thing
         *  down. Named class per this file's no-lambda rule, same as every other listener
         *  here. */
        private final class LongPressCheck implements Runnable {
            @Override public void run() {
                if (t == null || precisionMode) return;
                precisionMode = true;
                precisionStartX = lastX; precisionStartY = lastY;
                precisionStartZoom = t.zoom; precisionStartRot = t.rotDeg;
                showPrecisionHud();
            }
        }

        /** The one place precision mode is turned off — called from every exit path
         *  (finger up, finger cancelled, a second finger arriving) so the HUD can never be
         *  left showing after the drag it belongs to has ended. */
        private void exitPrecisionMode() {
            precisionMode = false;
            hidePrecisionHud();
        }

        private final class PinchListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (t == null) return false;
                t.zoom = Align.clampZoom(t.zoom * d.getScaleFactor());
                invalidate();
                updateAlignReadout();
                return true;
            }
        }
    }

    private final class RetakeTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            releasePendingBitmap();
            // Back to the SAME view's pre-capture screen rather than straight to the camera:
            // the level guide belongs in front of every attempt, not only the first, and a
            // retake is also where someone decides to import instead after all.
            showPreCapture(shot.view());
            toast("Retake");
        }
    }

    /**
     * "Use this" no longer saves — it hands off to the ALIGN stage, which owns Save. The
     * note is banked HERE, off the review screen's own EditText, because showAlign()
     * replaces the whole body and the field with it; taking it later would read a detached
     * view (or, worse, the NEXT capture's empty field). Nothing else about the pending
     * capture is touched: the armed view, the commanded/delivered pressures and the bitmap
     * all stay exactly as they are until commitPending() runs.
     */
    private final class UseTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            String pendingView = shot.pendingView();
            if (pendingView == null || pendingBitmap == null) {
                // #16's other edge: a stray Use with nothing armed since the last
                // commit/retake must not silently bank anything.
                toast("Nothing to use — take a photo first");
                return;
            }
            pendingNote = noteInput != null ? noteInput.getText().toString().trim() : "";
            enterAlign();
        }
    }

    /**
     * The ONE place a reviewed capture becomes a file and a committed photo. `toSave` is
     * either `pendingBitmap` itself (Skip align) or the aligned render of it (Save) — the
     * transform is baked into the pixels before this point, so everything from here down is
     * byte-for-byte the path it always was: the same saveFinal(), the same on-disk name, the
     * same EXIF stamp, the same shot.commit() against the ARMED view, the same delivered
     * pressure, the same per-view note, the same auto-advance. Downstream (Compare,
     * history, the reading record) cannot tell an aligned photo from an unaligned one, which
     * is the point: this feature changes the pixels, not the data model.
     */
    private void commitPending(Bitmap toSave, Model.EditProfile editUsed) {
        String pendingView = shot.pendingView();
        if (pendingView == null || toSave == null) {
            toast("Nothing to use — take a photo first");
            return;
        }
        String path = saveFinal(toSave, pendingView);
        if (path == null) { toast("Could not save the photo — try again"); return; }
        // EVERY FACT THE COMMIT NEEDS, TAKEN ONCE, BEFORE ANYTHING IS RELEASED (the emulator
        // pass, c81dcae): releasePendingBitmap() clears the commanded pressure, and the commit
        // used to read it AFTER the release - so a photo under a hold stored no hold pressure.
        // The snapshot keeps the delivered vacuum, the note, where the image came from, held
        // or at rest, the label's verdict and the hold's pressure; the commit derives
        // standardised and the rest from it alone (CaptureFacts, WiringCheck invariant 125).
        CaptureFacts facts = pendingFacts();
        releaseAlign();
        releasePendingBitmap();
        // Banks against `pendingView` — Shot.commit() reads its OWN pending field,
        // never shot.view() — so the photo lands under the view it was actually framed
        // for (#16).
        String committed = facts.commitTo(shot, path, editUsed);
        if (committed == null) { toast("Nothing to use — take a photo first"); return; }

        // Auto-advance to the next uncaptured view in Front -> Side -> Top order, or
        // finish once nothing later remains (all three optional).
        String next = shot.nextToShoot(committed);
        if (next != null) {
            toast(PhotoTruth.savedAdvance(committed, next));
            // #18: the live view advances only as the next attempt actually starts — the
            // pre-capture screen does it, and commit() never touched it. Every view gets
            // the level guide, not only the first.
            showPreCapture(next);
        } else {
            toast("Photo saved");
            finish();
        }
    }

    private final class SkipTap implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (!navGuard()) return;
            finish();
            toast("Photos skipped");
        }
    }

    private void finish() {
        stopLevel();
        deleteStaging();
        releaseAlign();
        releasePendingBitmap();
        Callback cb = callback;
        boolean any = shot != null && shot.tookAny();
        callback = null;
        if (cb != null) cb.onCameraDone(any);
    }

    /** Writes the reviewed (already-upright) bitmap to the reading's OWN file — the exact
     *  on-disk name Compare/history read back — and stamps it EXIF-NORMAL. This is the one
     *  place a committed photo is written; the camera's scratch file is never it. */
    private String saveFinal(Bitmap bmp, String view) {
        try {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (dir == null) dir = activity.getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, Shot.filename(readingId, view));
            FileOutputStream out = new FileOutputStream(f);
            try { bmp.compress(Bitmap.CompressFormat.JPEG, 90, out); }
            finally { out.close(); }
            // compress() writes no EXIF block, and these pixels are already upright (the
            // camera's EXIF rotation was baked in when the scratch file was decoded), so say
            // so — every reader then defaults correctly.
            Photos.stampUpright(f);
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private File stagingFile() {
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) return null;
        return new File(dir, CapturePaths.STAGING_NAME);
    }

    /** Best-effort removal of the camera's scratch file — it is a throwaway that never
     *  carries committed data (its distinct name can never collide with a reading photo),
     *  so deleting it leaves no uncommitted body photo lingering on disk. */
    private void deleteStaging() {
        try {
            File f = stagingFile();
            if (f != null && f.exists()) f.delete();
        } catch (Exception ignored) { }
    }

    private void revokeGrant() {
        try {
            activity.revokeUriPermission(Uri.parse(CapturePaths.contentUri()),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }
    }

    /**
     * POINT 19 - THE IMAGE HAS ARRIVED FOR THE CAPTURE THAT WAS ARMED. Only the bitmap is
     * swapped: the facts sampled when the capture was armed - whether a hold was on, the
     * vacuum the pump reported - belong to this photo and must reach the review label and
     * the commit. The three arrival paths (the in-app shutter, the phone's camera app and
     * the gallery) used to call releasePendingBitmap() here, which also dropped those
     * facts: a photo taken under a hold was then labelled "At rest", stored with no
     * pressure, and recorded as taken at rest. WiringCheck invariant 83 holds the three to
     * this.
     */
    private void takeArrived(Bitmap bmp) {
        pendingBitmap = bmp;
    }

    /** Drops the pending capture. Deliberately does NOT recycle(): unlike the old live-preview
     *  flow, which synchronously rebuilt the screen (removeAllViews) in the same tap before any
     *  redraw, Use/Retake here launch the external camera with the review's ImageView still
     *  attached — recycling the bitmap it holds would crash the pause-transition redraw. The
     *  reference is dropped instead; the small (<=1000px) bitmap is GC'd once the ImageView is
     *  detached by the next showReview()/finish(). */
    /** The pending capture's facts as ONE snapshot (CaptureFacts) - what commitPending
     *  commits from, taken before any release clears the fields it is built from. */
    private CaptureFacts pendingFacts() {
        return new CaptureFacts(pendingFromGallery, pendingHeldKpa, pendingObservedKpa,
            pendingTaken, pendingTiltDeg, pendingTurnDeg, pendingNote);
    }

    private void releasePendingBitmap() {
        pendingBitmap = null;
        pendingFromGallery = false;
        pendingHeldKpa = null;
        pendingObservedKpa = null;
        pendingTaken = PhotoTruth.TAKEN_AT_REST;
    }

    private boolean navGuard() {
        long now = System.currentTimeMillis();
        if (now - lastNav < 350) return false;
        lastNav = now;
        return true;
    }

    /* ---------------------------- Activity-lifecycle hooks kept for SessionActivity wiring */

    /** PAUSE/DESTROY. Everything the screen holds that another app might want, or that a
     *  backgrounded app must not keep: the accelerometer, the camera (device, session,
     *  reader, background thread) and the align stage's ghost bitmap. Called by
     *  SessionActivity.onPause()/onDestroy() regardless of which screen is showing. */
    public void releaseCamera() {
        // The accelerometer first: a backgrounded app must not keep a sensor streaming.
        // Only the LISTENER goes — the pre-capture screen itself survives a pause, and
        // onResume() below registers again against the views still sitting on it.
        unregisterSensor();
        // There IS one thing to release now: the align stage's ghost bitmap. A backgrounded
        // app must not keep a decoded photo pinned, and CompareScreen#stop() has exactly
        // this contract for exactly this reason. The pending capture itself is deliberately
        // left alone — it is uncommitted work the user comes back to.
        releaseGhost();
        // The camera hardware, fully: onResume() re-opens it against the SAME TextureView,
        // which is still on the screen the user is coming back to.
        releasePreview();
    }

    /** Re-registers the accelerometer AND re-opens the camera when the PRE-CAPTURE screen is
     *  what the user is coming back to (levelView still built means it is). The preview was
     *  fully released on pause, so this is a fresh open against the surviving TextureView —
     *  which may already be available, the case startPreview() handles explicitly. */
    public void onResume() {
        // POINT 19 - back from the app's settings with the camera now allowed: the card
        // gives way to the viewfinder. Still refused, the card simply stays.
        if (permCardShown && isShowing() && hasCameraPermission() && shot != null) {
            showPreCapture(shot.view());
            return;
        }
        if (levelView != null) startLevel();
        if (previewView != null && !cameraFellBack) startPreview();
    }

    /** The CAMERA answer, routed here by SessionActivity.onRequestPermissionsResult. Granted
     *  brings the live viewfinder up. Refused shows the permission card (point 19): the old
     *  fall-back to the system camera app assumed it owned its own permission, but the
     *  system refuses ACTION_IMAGE_CAPTURE to an app that declares CAMERA without holding
     *  it, so the card offers what does work - the app's settings, and the gallery. */
    public void onPermissionResult(int[] grantResults) {
        boolean granted = grantResults != null && grantResults.length > 0
                       && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            // Asked from the no-viewfinder path, a grant rebuilds the screen so the shutter
            // is live; from the viewfinder path the preview simply starts.
            if (previewView == null && shot != null && callback != null) showPreCapture(shot.view());
            else startPreview();
        } else {
            permissionRefused();
        }
    }

    /** {@link #permissionRefused}, posted from the camera callbacks that learn of it. */
    private final class PermissionRefused implements Runnable {
        @Override public void run() { permissionRefused(); }
    }

    /* --------------------------------------------------------------------- widgets */

    private LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button smallButton(String text) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(Look.SP_FIELD_LABEL);
        b.setTextColor(Ui.TEXT);
        b.setBackgroundColor(Ui.SURF);
        b.setMinHeight(Ui.dp(activity, 48));
        b.setMinWidth(Ui.dp(activity, 48));
        b.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 6), Ui.dp(activity, 10), Ui.dp(activity, 6));
        // This pre-existing helper had never gotten Press — the ⌖ Match button built
        // beside these 7 (MatchProfileTap, a one-off outside this factory) got it
        // explicitly, which is what made the gap visible. Set here so every control this
        // factory builds inherits it, same as the rest of the app's touch targets.
        b.setOnTouchListener(new Ui.Press());
        return b;
    }

    private void toast(String s) {
        Ui.say(activity, s, false);
    }
}
