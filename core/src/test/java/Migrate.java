import org.openpump.Deload;
import org.openpump.Model;
import org.openpump.Traction;
public class Migrate {
    // `throws Exception` only for the org.json constructor below, which is checked.
    public static void main(String[] a) throws Exception {
        // EXACTLY what build 30 (pre-stage) wrote to model.json
        String old = "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"r1\","
          + "\"sets\":[{\"id\":\"s1\",\"name\":\"Gentle Warm\",\"ramp\":false,\"up\":14,"
          + "\"lo\":7,\"uh\":20,\"lh\":5,\"sp\":60,\"dur\":180,\"up2\":14,\"lo2\":7,"
          + "\"sp2\":60,\"steps\":4}],"
          + "\"routines\":[{\"id\":\"r1\",\"name\":\"My Routine\",\"runs\":7,\"done\":5,"
          + "\"sets\":[\"s1\",\"s1\"]}]}";
        Model m = Model.fromJson(old);
        Model.Routine r = m.routine("r1");
        System.out.println("routine found      : " + (r != null));
        if (r == null) { System.out.println("FAIL: routine lost"); System.exit(1); }
        System.out.println("name preserved     : " + r.name);
        System.out.println("runs/done preserved: " + r.runs + "/" + r.done);
        System.out.println("stages             : " + r.stages.size());
        if (r.stages.size() > 0) {
            System.out.println("stage name         : " + r.stages.get(0).name);
            System.out.println("set ids in stage   : " + r.stages.get(0).setIds);
        }
        System.out.println("plan() presets     : " + m.plan(r).size());
        System.out.println("selected preserved : " + m.selected);
        System.out.println("sets preserved     : " + m.sets.size());
        // THE SCHEDULE MIGRATION, on the same old file. It has no "sched" key at all, so
        // this is the real on-disk case: every day must train (the only default under which
        // the schedule-based streak reproduces the old calendar-day one), the hour must be
        // the 19:00 default, and reminders must be OFF — an upgrade that switched on a
        // notification nobody asked for would be a defect the self-test cannot see, because
        // it happens on the phone, on load, once.
        System.out.println("schedule days      : " + m.sched.mask());
        System.out.println("schedule hour      : " + m.sched.hhmm());
        System.out.println("reminders          : " + (m.sched.remind ? "ON" : "off"));
        System.out.println("milestones seen    : " + m.seenMilestones.size());
        boolean schedOk = "1111111".equals(m.sched.mask()) && m.sched.hour == 19
                       && m.sched.minute == 0 && !m.sched.remind
                       && m.seenMilestones.isEmpty();
        if (!schedOk) System.out.println("FAIL: schedule migration default is wrong");

        // THE SAVE-WHAT-YOU-RAN MIGRATION, on the same old file. It has none of the new
        // keys — no offer thresholds, no per-routine opt-out, no run stamp on a set — so
        // this is the real on-disk case for a phone upgrading into the feature. Every
        // default must be the FEATURE'S OWN: 2.0 kPa and 20 % (a 0.0 threshold would mean
        // "ask about any change at all", the loudest possible setting to hand someone
        // silently), skips do ask, the coaching line stays off, no routine has opted out
        // of an offer it has never been shown, and a set built by hand years ago did not
        // come out of a run.
        System.out.println("offer threshold    : " + m.offerPressureKpa + " kPa / "
                           + m.offerHoldSpeedPct + "%");
        System.out.println("offer on skip      : " + (m.offerOnSkip ? "ON" : "off"));
        System.out.println("coaching line      : " + (m.coachLine ? "ON" : "off"));
        System.out.println("routine opt-out    : " + (r.noSaveOffer ? "OPTED OUT" : "offers"));
        System.out.println("set fromRun        : " + m.set("s1").fromRun);
        boolean offerOk = Math.abs(m.offerPressureKpa - 2.0) < 1e-9
                       && m.offerHoldSpeedPct == 20 && m.offerOnSkip && !m.coachLine
                       && !r.noSaveOffer && m.set("s1").fromRun == 0L;
        if (!offerOk) System.out.println("FAIL: save-offer migration default is wrong");

        // THE SEAL-CHECK MIGRATION, on the same old file. It has no "sealBefore"/"sealKpa"/
        // "sealHold" keys at all, so this is the real on-disk case for a phone upgrading
        // into the seal check becoming a setting. The switch must come up OFF — that is the
        // recorded decision, and an upgrade that silently kept a 45-second hold in front of
        // every routine run would be exactly the kind of thing this probe exists to catch —
        // while the two numbers take the figures the check used while it was hardcoded, so
        // turning it on reproduces the old behaviour byte for byte.
        System.out.println("seal before routine: " + (m.sealBeforeRoutine ? "ON" : "off"));
        System.out.println("seal target/hold   : " + m.sealCheckKpa + " kPa / "
                           + m.sealCheckHoldS + " s");
        boolean sealOk = !m.sealBeforeRoutine && m.sealCheckKpa == 20 && m.sealCheckHoldS == 45;
        if (!sealOk) System.out.println("FAIL: seal-check migration default is wrong");

        // THE KEEP-THE-SCREEN-ON MIGRATION, on the same old file. It has no "keepAwake"
        // key, so this is the real on-disk case for a phone upgrading into the setting. It
        // must come up ON — the feature's own default, and the safe direction: the run
        // screen is where STOP lives, and an upgrade that silently let the display sleep
        // over a live cuff would put the vent behind a lock screen for someone who never
        // chose that. A missing key means "never asked", not "off".
        System.out.println("keep screen on     : " + (m.keepScreenOn ? "ON" : "off"));
        boolean awakeOk = m.keepScreenOn;
        if (!awakeOk) System.out.println("FAIL: keep-screen-on migration default is wrong");

        // THE THEN-VS-NOW MIGRATION, on the same old file. It has no "thenNow" key at all,
        // so this is the real on-disk case for a phone upgrading into the Progress card's
        // source chooser. The card must come up showing exactly what it showed yesterday —
        // the FIRST photo against the LATEST — because that is the only acceptable default
        // for a preference the user has never been asked about, and because a card that
        // silently started comparing a different pair after an update would look like the
        // measurements had changed. Both ids must be ABSENT: a mode of "pick" with no id, or
        // an id of "", would resolve to a reading that does not exist.
        System.out.println("then-vs-now left   : " + m.thenNow.left + "/" + m.thenNow.leftId);
        System.out.println("then-vs-now right  : " + m.thenNow.right + "/" + m.thenNow.rightId);
        System.out.println("then-vs-now default: " + (m.thenNow.isDefault() ? "yes" : "NO"));
        boolean thenNowOk = m.thenNow.isDefault()
                         && Model.ThenNowPick.LEFT_FIRST.equals(m.thenNow.left)
                         && Model.ThenNowPick.RIGHT_LATEST.equals(m.thenNow.right)
                         && m.thenNow.leftId == null && m.thenNow.rightId == null;
        if (!thenNowOk) System.out.println("FAIL: then-vs-now migration default is wrong");

        // AND IT SURVIVES A ROUND TRIP. The migration above only proves an OLD file loads;
        // this proves a choice made on the new build is still there after a save and a
        // reload, which is the other half of "persisted" and the half a missing toJson key
        // would silently fail.
        m.thenNow.left = Model.ThenNowPick.LEFT_6M;
        m.thenNow.right = Model.ThenNowPick.RIGHT_PICK;
        m.thenNow.rightId = "r-42";
        Model reloaded = Model.fromJson(m.toJson());
        System.out.println("then-vs-now saved  : " + reloaded.thenNow.left + " / "
                           + reloaded.thenNow.right + " " + reloaded.thenNow.rightId);
        boolean thenNowRoundTrip =
               Model.ThenNowPick.LEFT_6M.equals(reloaded.thenNow.left)
            && Model.ThenNowPick.RIGHT_PICK.equals(reloaded.thenNow.right)
            && "r-42".equals(reloaded.thenNow.rightId)
            && reloaded.thenNow.leftId == null;
        if (!thenNowRoundTrip) System.out.println("FAIL: then-vs-now does not survive a save");
        m.thenNow = new Model.ThenNowPick();      // leave the model as the probe found it

        // A SESSION from that era — the old file has no "sessions" key at all, so the
        // probe reads one straight through Sess.fromJson. No as-run recording, nothing
        // saved, no feel, no note, no absolute after-readings and no routine snapshot:
        // all absences, none of them ever inferred from another field. A saved routine id
        // of "" would resolve to a routine that no longer exists, so absence is null.
        Model.Sess oldSess = Model.Sess.fromJson(
            new org.json.JSONObject("{\"id\":\"x\",\"ts\":\"1755000000000\",\"dur\":\"600\","
                                  + "\"done\":true,\"tag\":\"no after-measurement\"}"));
        System.out.println("sess hasAsRun      : " + oldSess.hasAsRun);
        System.out.println("sess differs       : " + oldSess.differs);
        System.out.println("sess savedSetIds   : " + oldSess.savedSetIds.size());
        System.out.println("sess savedRoutineId: " + oldSess.savedRoutineId);
        System.out.println("sess savedAt/feel  : " + oldSess.savedAt + "/" + oldSess.feel);
        System.out.println("sess note          : \"" + oldSess.note + "\"");
        System.out.println("sess after abs     : " + oldSess.afterLenAbsCm + "/" + oldSess.afterGirAbsCm);
        System.out.println("sess as-run snap   : " + oldSess.asRunSnapshot);
        boolean sessOk = !oldSess.hasAsRun && !oldSess.differs && oldSess.savedSetIds.isEmpty()
                      && oldSess.savedRoutineId == null && oldSess.savedAt == 0L && oldSess.feel == 0
                      && "".equals(oldSess.note) && oldSess.afterLenAbsCm == null
                      && oldSess.afterGirAbsCm == null && oldSess.asRunSnapshot == null;
        if (!sessOk) System.out.println("FAIL: old session migration default is wrong");

        // AN AS-RUN RECORDING from before the run carried its own plan: no "ceil" on the
        // recording and no "setv" on any stage of its snapshot. Both absences must read as
        // "not recorded" — the ceiling falls back to the Model's current one and the
        // snapshot judges on the structure alone — so a recording written by yesterday's
        // build still opens its card, and cannot be made to claim a set edit it never saw.
        String oldRun = "{\"rid\":\"r1\",\"rname\":\"My Routine\",\"manual\":false,"
          + "\"ts\":\"1755000000000\","
          + "\"snap\":[{\"rid\":\"r1\",\"stages\":[{\"name\":\"Work\",\"sets\":[\"s1\",\"s1\"]}]}],"
          + "\"rows\":[{\"k\":0,\"t0\":\"0\",\"t1\":\"120000\",\"st\":0,\"pos\":0,\"ord\":0,"
          + "\"sid\":\"s1\",\"up\":14,\"lo\":7,\"uhr\":20,\"lh\":5,\"sp\":60}]}";
        org.openpump.AsRun oldAsRun = org.openpump.AsRun.fromJson(new org.json.JSONObject(oldRun));
        System.out.println("as-run ceiling     : " + oldAsRun.ceilKpa
                           + " -> falls back to " + oldAsRun.runCeilKpa(m));
        System.out.println("as-run snap setv   : "
                           + oldAsRun.snap.stages.get(0).sets.size()
                           + " (setAt -> " + oldAsRun.snap.stages.get(0).setAt(0) + ")");
        System.out.println("as-run rows        : " + oldAsRun.rows.size());
        boolean structureOnlyMatches = oldAsRun.snap.matches(r, m);
        int wasUp = m.set("s1").up;
        m.set("s1").up = wasUp + 9;                 // a set edited since that run
        boolean stillMatches = oldAsRun.snap.matches(r, m);
        m.set("s1").up = wasUp;
        System.out.println("as-run snap matches: " + structureOnlyMatches
                           + " / after a set edit " + stillMatches);
        System.out.println("as-run blocks      : " + org.openpump.AsRun.blocks(oldAsRun, m).size());
        boolean runOk = oldAsRun.ceilKpa == 0 && oldAsRun.runCeilKpa(m) == m.ceilKpa
                     && oldAsRun.snap != null && oldAsRun.snap.stages.get(0).sets.isEmpty()
                     && oldAsRun.snap.stages.get(0).setAt(0) == null
                     && structureOnlyMatches && stillMatches
                     && org.openpump.AsRun.blocks(oldAsRun, m).size() == 1;
        if (!runOk) System.out.println("FAIL: old as-run recording migration default is wrong");

        // FEATURE ROUND B, on the same old file. It has no "blur", no "goalLen"/"goalGir",
        // no "rest" on its one set and no "phase" on any reading - so this is the real
        // on-disk case for a phone upgrading into the privacy blur, the goal line, rests
        // and the pre/post axis. Every default must be the ABSENT one:
        //
        //   - the blur OFF, because turning a person's own measurements into dots is a
        //     choice they make and never one an update makes for them;
        //   - all three goals ABSENT rather than 0.0, which would draw a goal line across
        //     the floor of every chart and caption it "0.0 cm to go" forever;
        //   - the set NOT a rest - there is no shape of old data that could have meant
        //     "rest", so there is nothing here to infer;
        //   - every reading UNKNOWN-phase, which the trend draws on the PRE line. That is
        //     the continuity decision on record: every reading logged before this axis
        //     existed was a cold one, and splitting one history into two lines on the day
        //     of an update would look like the measurements had changed.
        System.out.println("privacy blur       : " + (m.privacyBlur ? "ON" : "off"));
        System.out.println("goals              : " + m.goalBpsslCm + " / "
                           + m.goalMsegCm + " / " + m.goalMssgCm);
        System.out.println("set is a rest      : " + m.set("s1").rest);
        Model.Reading oldReading = Model.Reading.fromJson(
            new org.json.JSONObject("{\"id\":\"m1\",\"ts\":\"1755000000000\","
                                  + "\"len\":\"15.5\",\"gir\":\"12.5\"}"));
        System.out.println("reading phase      : " + oldReading.phase
                           + (oldReading.phase == Model.Reading.PHASE_UNKNOWN
                                ? " (unknown -> the PRE line)" : " (WRONG)"));
        boolean roundBOk = !m.privacyBlur && m.goalBpsslCm == null
                        && m.goalMsegCm == null && m.goalMssgCm == null
                        && !m.set("s1").rest
                        && oldReading.phase == Model.Reading.PHASE_UNKNOWN;
        if (!roundBOk) System.out.println("FAIL: feature round B migration default is wrong");

        // THE MEASUREMENT METHOD AXIS, on the same old file. It has no "goalMethod", and its
        // readings have no "method", so this is the real on-disk case for a phone upgrading
        // into the method selector. The defaults must be the ABSENT ones:
        //
        //   - every old reading STANDARDIZED (0) — the legacy-standardised assumption: for
        //     the whole history that predates this axis a reading was either taken under a
        //     hold or was the app's single baseline path, so the pre-axis history reads as
        //     one comparable standardised line. There is no separate legacy sentinel.
        //   (The goal's own method is no longer a field at all — the three-goal round
        //   replaced it with one goal PER method; see THE THREE-GOAL MIGRATION below.)
        System.out.println("reading method     : " + oldReading.method + " ("
                           + Model.Reading.methodLabel(oldReading.method)
                           + (oldReading.method == Model.Reading.METHOD_STANDARDIZED
                                ? " -> legacy-standardised)" : " WRONG)"));
        boolean methodOk = oldReading.method == Model.Reading.METHOD_STANDARDIZED;
        if (!methodOk) System.out.println("FAIL: method migration default is wrong");
        // THE RENAME IS A LABEL, NOT A VALUE. Method 7 was MSFL and is now MSSG; the int on
        // disk is untouched, so a reading saved as 7 years ago still loads as 7 and simply
        // reads out under the new name. A migration that had renumbered it would silently
        // re-file every soft-girth reading as something else.
        Model.Reading msgOld = Model.Reading.fromJson(
            new org.json.JSONObject("{\"id\":\"m7\",\"ts\":\"1755000000007\","
                                  + "\"gir\":\"12.0\",\"method\":7}"));
        System.out.println("method 7 label     : " + Model.Reading.methodLabel(msgOld.method)
                           + " (stored " + msgOld.method + ")");
        boolean mssgOk = msgOld.method == Model.Reading.METHOD_MSSG
                      && "MSSG".equals(Model.Reading.methodLabel(7));
        if (!mssgOk) System.out.println("FAIL: method 7 did not survive the MSFL->MSSG rename");

        // AND BOTH SURVIVE A ROUND TRIP - the other half of "persisted".
        Model.Reading bpsl = Model.Reading.fromJson(
            new org.json.JSONObject("{\"id\":\"m2\",\"ts\":\"1755000000001\","
                                  + "\"len\":\"16.0\",\"gir\":\"12.0\",\"method\":3}"));
        Model.Reading bpslBack = Model.Reading.fromJson(bpsl.toJson());
        System.out.println("method saved       : " + Model.Reading.methodLabel(bpslBack.method));
        boolean methodRoundTrip = bpslBack.method == Model.Reading.METHOD_BPSL;
        if (!methodRoundTrip) System.out.println("FAIL: the method does not survive a save");

        // THE GOALS' SHARED TIME HORIZON. An old file carries none: it migrates to 12
        // months, the period the per-year cap was already stated in, so an old goal keeps
        // the exact line it was drawn with. (`goalMetricGirth` — which metric the chart
        // opened on — is gone with the single goal; the chart opens on length.)
        System.out.println("goal horizon       : " + m.goalHorizonMonths + " months");
        boolean horizonOk = m.goalHorizonMonths == 12;
        if (!horizonOk) System.out.println("FAIL: the goal horizon migration default is wrong");
        m.goalHorizonMonths = 24;
        Model hzTrip = Model.fromJson(m.toJson());
        System.out.println("horizon saved      : " + hzTrip.goalHorizonMonths + " months");
        boolean horizonRoundTrip = hzTrip.goalHorizonMonths == 24;
        if (!horizonRoundTrip)
            System.out.println("FAIL: the goal horizon does not survive a save");
        m.goalHorizonMonths = 12;

        /* ================= THE THREE-GOAL MIGRATION, the round's real on-disk risk =======
         *
         * The field migration this round has to get right is not an absent key — it is a
         * PRESENT one that no longer exists. An old save carries "goalLen", "goalGir" and
         * "goalMethod"; the model now carries goalBpssl / goalMseg / goalMssg and no method
         * at all. A load that simply ignored the old keys would silently delete a target
         * someone set, on the day of an update, with nothing on screen to say so.
         *
         * What must happen, and is asserted below on files built to look exactly like the
         * old ones:
         *   - an old LENGTH goal becomes the BPSSL goal, whatever method it was stated in
         *     (BPSSL is the only length goal the new model holds — the alternative to
         *     restating it is dropping it);
         *   - an old GIRTH goal becomes the MSEG goal, unless the old method was the soft
         *     girth one (7), in which case it lands on MSSG where it belongs;
         *   - a file with NO old goals produces no goals rather than zeros.
         */
        String oldGoalFile = "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],"
          + "\"goalLen\":\"18.5\",\"goalGir\":\"13.25\",\"goalMethod\":2}";
        Model oldGoals = Model.fromJson(oldGoalFile);
        System.out.println("old BPSSL goal     : " + oldGoals.goalBpsslCm);
        System.out.println("old MSEG goal      : " + oldGoals.goalMsegCm);
        System.out.println("old MSSG goal      : " + oldGoals.goalMssgCm);
        boolean oldGoalOk = oldGoals.goalBpsslCm != null
                         && Math.abs(oldGoals.goalBpsslCm.doubleValue() - 18.5) < 1e-9
                         && oldGoals.goalMsegCm != null
                         && Math.abs(oldGoals.goalMsegCm.doubleValue() - 13.25) < 1e-9
                         && oldGoals.goalMssgCm == null;
        if (!oldGoalOk)
            System.out.println("FAIL: the old single goal did not migrate to the three");

        // The SOFT-GIRTH variant: the same old file, stated in method 7.
        Model oldSoft = Model.fromJson("{\"ceil\":40,\"sets\":[],\"routines\":[],"
                                     + "\"goalGir\":\"11.0\",\"goalMethod\":7}");
        System.out.println("old soft girth     : mseg " + oldSoft.goalMsegCm
                           + " / mssg " + oldSoft.goalMssgCm);
        boolean oldSoftOk = oldSoft.goalMsegCm == null && oldSoft.goalMssgCm != null
                         && Math.abs(oldSoft.goalMssgCm.doubleValue() - 11.0) < 1e-9;
        if (!oldSoftOk)
            System.out.println("FAIL: a soft-girth goal did not migrate to MSSG");

        // A NEW file wins over the legacy keys, so a re-save can never resurrect an old
        // number over one the user has since set. (Both key families present at once is
        // exactly what one save-after-upgrade would produce if toJson still wrote the old
        // ones — it does not, and this pins that the reader would ignore them anyway.)
        Model bothKeys = Model.fromJson("{\"sets\":[],\"routines\":[],"
                                      + "\"goalLen\":\"18.5\",\"goalMethod\":2,"
                                      + "\"goalBpssl\":\"20.0\"}");
        System.out.println("new key wins       : " + bothKeys.goalBpsslCm);
        boolean bothOk = bothKeys.goalBpsslCm != null
                      && Math.abs(bothKeys.goalBpsslCm.doubleValue() - 20.0) < 1e-9;
        if (!bothOk) System.out.println("FAIL: a legacy goal key overrode a current one");

        // AND THE NEW FIELDS SURVIVE A ROUND TRIP. The migration above only proves an old
        // file still loads; this is the other half of "persisted", and the half a missing
        // toJson key fails silently. The goal is set to a value with a fraction that a
        // string round trip would lose if it were written as an int.
        m.privacyBlur = true;
        m.goalBpsslCm = Double.valueOf(17.25);
        m.goalMsegCm  = Double.valueOf(13.5);
        m.goalMssgCm  = Double.valueOf(11.75);
        Model roundB = Model.fromJson(m.toJson());
        System.out.println("blur saved         : " + (roundB.privacyBlur ? "ON" : "off"));
        System.out.println("goals saved        : " + roundB.goalBpsslCm + " / "
                           + roundB.goalMsegCm + " / " + roundB.goalMssgCm);
        boolean roundBTrip = roundB.privacyBlur
                          && roundB.goalBpsslCm != null && roundB.goalMsegCm != null
                          && roundB.goalMssgCm != null
                          && Math.abs(roundB.goalBpsslCm.doubleValue() - 17.25) < 1e-9
                          && Math.abs(roundB.goalMsegCm.doubleValue() - 13.5) < 1e-9
                          && Math.abs(roundB.goalMssgCm.doubleValue() - 11.75) < 1e-9;
        if (!roundBTrip) System.out.println("FAIL: round B does not survive a save");
        // ...and CLEARING a goal must read back as absent, not as a zero. This is the
        // direction the "" encoding exists for, and the one an optDouble default would
        // quietly get wrong.
        m.goalBpsslCm = null; m.goalMsegCm = null; m.goalMssgCm = null;
        m.privacyBlur = false;
        Model roundBCleared = Model.fromJson(m.toJson());
        System.out.println("goals cleared      : " + roundBCleared.goalBpsslCm + " / "
                           + roundBCleared.goalMsegCm + " / " + roundBCleared.goalMssgCm);
        boolean roundBCleared0 = roundBCleared.goalBpsslCm == null
                              && roundBCleared.goalMsegCm == null
                              && roundBCleared.goalMssgCm == null
                              && !roundBCleared.privacyBlur;
        if (!roundBCleared0) System.out.println("FAIL: a cleared goal does not save as absent");

        // FEATURE ROUND C, on the same old file. It has no "sizeUnit", no "measRemind" and
        // no "rest" key on its one stage — so this is the real on-disk case for a phone
        // upgrading into the cm/in size unit, the measurement reminder and rest stages.
        // Every default must be the ABSENT one:
        //
        //   - the size unit CENTIMETRES, because that is what every screen already printed;
        //     an upgrade that silently restated a person's readings in inches would look
        //     like the measurements had changed when only the app did;
        //   - the measurement reminder OFF, at the documented 19:30 it would use if turned
        //     on. A notification is opt-in, and an update is not a request for one;
        //   - the stage NOT a rest, with no rest duration in force. There is no shape of
        //     old data that could have meant "rest stage", so there is nothing to infer —
        //     and a routine whose first stage silently became a two-minute wait would add
        //     time to a run nobody asked for.
        //
        // The PRESSURE unit is checked too: the file says "inHg" and cmHg's arrival must
        // not move it.
        System.out.println("size unit          : " + m.sizeUnit);
        System.out.println("pressure unit      : " + m.unit);
        System.out.println("meas reminder      : " + (m.measRemind ? "ON" : "off")
                           + " at " + m.measRemindHour + ":" + m.measRemindMin);
        System.out.println("stage is a rest    : " + r.stages.get(0).rest
                           + " (" + r.stages.get(0).restSec + " s default)");
        System.out.println("plan() unchanged   : " + m.plan(r).size() + " presets");
        boolean roundCOk = "cm".equals(m.sizeUnit) && "inHg".equals(m.unit)
                        && !m.measRemind && m.measRemindHour == 19 && m.measRemindMin == 30
                        && !r.stages.get(0).rest && r.stages.get(0).restSec == 120
                        && m.plan(r).size() == 2;
        if (!roundCOk) System.out.println("FAIL: feature round C migration default is wrong");

        // AND THE NEW FIELDS SURVIVE A ROUND TRIP — the other half of "persisted", and the
        // half a missing toJson key fails silently. A REST STAGE is added to the migrated
        // routine and must come back as one, with its duration, rather than as an empty
        // stage that runs nothing.
        m.sizeUnit = "in";
        m.unit = "cmHg";
        m.measRemind = true;
        m.measRemindHour = 7; m.measRemindMin = 5;
        r.stages.add(Model.Stage.restOf("Breather", 240));
        Model roundC = Model.fromJson(m.toJson());
        Model.Routine rc = roundC.routine("r1");
        System.out.println("units saved        : " + roundC.unit + " / " + roundC.sizeUnit);
        System.out.println("reminder saved     : " + (roundC.measRemind ? "ON" : "off")
                           + " at " + roundC.measRemindHour + ":" + roundC.measRemindMin);
        System.out.println("rest stage saved   : " + rc.stages.get(1).rest
                           + " for " + rc.stages.get(1).restSec + " s");
        System.out.println("rest emits preset  : " + roundC.plan(rc).size() + " presets, "
                           + "index 2 rest=" + roundC.plan(rc).get(2).rest);
        boolean roundCTrip = "cmHg".equals(roundC.unit) && "in".equals(roundC.sizeUnit)
                          && roundC.measRemind && roundC.measRemindHour == 7
                          && roundC.measRemindMin == 5
                          && rc.stages.size() == 2 && rc.stages.get(1).rest
                          && rc.stages.get(1).restSec == 240
                          && rc.stages.get(1).setIds.isEmpty()
                          && roundC.plan(rc).size() == 3 && roundC.plan(rc).get(2).rest;
        if (!roundCTrip) System.out.println("FAIL: round C does not survive a save");
        // Leave the model as the probe found it — the shared assertions below still speak
        // about the MIGRATED routine, and a stage added here would fail them.
        r.stages.remove(1);
        m.sizeUnit = "cm"; m.unit = "inHg"; m.measRemind = false;

        // FEATURE ROUND D, on the same old file. It has no "calibPitch"/"calibRoll" keys,
        // and its readings carry no "gal" flag on any photo — so this is the real on-disk
        // case for a phone upgrading into the level bubble and imported photos.
        //
        //   - the level reference must be ABSENT in both axes, not 0.0. The default TARGET
        //     is 0/0 (upright, unturned), but "never set" and "set to upright" are different
        //     facts: one offers a Reset and says "the angle you set", the other says "the
        //     default". A migration that wrote zeros would silently claim the user had
        //     calibrated;
        //   - every existing photo must read fromGallery FALSE. That is not merely a safe
        //     default, it is the truth: importing did not exist when those files were
        //     written, so every one of them came out of the camera. The badge appearing on
        //     a historic photo after an update would be the app inventing provenance.
        System.out.println("level reference    : "
            + (m.calibPitch == null ? "unset" : String.valueOf(m.calibPitch))
            + " / " + (m.calibRoll == null ? "unset" : String.valueOf(m.calibRoll)));
        org.openpump.Model.Reading oldPhoto = new org.openpump.Model.Reading();
        oldPhoto.id = "gal-mig"; oldPhoto.ts = 1000L; oldPhoto.len = 14; oldPhoto.gir = 11;
        oldPhoto.photo = true;
        oldPhoto.photoFront = org.openpump.Model.Reading.Photo.fromJson(
            new org.json.JSONObject("{\"path\":\"/p/front.jpg\",\"note\":\"\",\"kpa\":\"20.0\",\"ts\":\"1000\"}"));
        System.out.println("old photo imported : " + oldPhoto.photoFront.fromGallery);
        boolean roundDOk = m.calibPitch == null && m.calibRoll == null
                        && !oldPhoto.photoFront.fromGallery;
        if (!roundDOk) System.out.println("FAIL: feature round D migration default is wrong");

        // AND BOTH SURVIVE A ROUND TRIP — the other half of "persisted". A reference set to
        // ZERO is the case that catches a "null means default, so write nothing" shortcut:
        // it must come back CALIBRATED at 0.0, not unset.
        m.calibPitch = Double.valueOf(0.0);
        m.calibRoll = Double.valueOf(-3.5);
        oldPhoto.photoFront.fromGallery = true;
        Model roundD = Model.fromJson(m.toJson());
        org.openpump.Model.Reading.Photo pTrip =
            org.openpump.Model.Reading.Photo.fromJson(oldPhoto.photoFront.toJson());
        System.out.println("reference saved    : " + roundD.calibPitch + " / " + roundD.calibRoll);
        System.out.println("imported saved     : " + pTrip.fromGallery);
        boolean roundDTrip = roundD.calibPitch != null && roundD.calibPitch.doubleValue() == 0.0
                          && roundD.calibRoll != null && roundD.calibRoll.doubleValue() == -3.5
                          && pTrip.fromGallery;
        if (!roundDTrip) System.out.println("FAIL: round D does not survive a save");
        m.calibPitch = null; m.calibRoll = null;   // leave the model as the probe found it

        // FEATURE ROUND B (the second one), on the same old file. It has no "editProfiles"
        // key, and its photos carry no "ez"/"er"/"ex"/"ey" align-transform keys — so this is
        // the real on-disk case for a phone upgrading into per-view align defaults and
        // recorded photo provenance.
        //
        //   - NO SAVED PROFILE FOR ANY VIEW. Not an identity profile: the align stage turns
        //     its frame GREEN when the live transform matches the saved one, so a migration
        //     that wrote 1/0/0/0 would show every user a green frame on the very first photo
        //     after the update, telling them they had reproduced a framing they had never
        //     chosen;
        //   - every existing photo's align transform reads NULL in all four components. The
        //     file may well have been aligned by hand — we simply never wrote down by how
        //     much, and "unknown" is the only honest value. 1/0/0/0 would claim "saved
        //     unaligned", which the file cannot support.
        System.out.println("edit profiles      : "
            + (m.editProfile("front") == null && m.editProfile("side") == null
               ? "none (correct)" : "SET"));
        org.openpump.Model.Reading.Photo oldEdit = org.openpump.Model.Reading.Photo.fromJson(
            new org.json.JSONObject("{\"path\":\"/p/front.jpg\",\"note\":\"\",\"ts\":\"1000\"}"));
        System.out.println("old photo transform: "
            + (oldEdit.editZoom == null ? "unknown" : String.valueOf(oldEdit.editZoom))
            + " / " + (oldEdit.editRot == null ? "unknown" : String.valueOf(oldEdit.editRot))
            + " / " + (oldEdit.editPanX == null ? "unknown" : String.valueOf(oldEdit.editPanX))
            + " / " + (oldEdit.editPanY == null ? "unknown" : String.valueOf(oldEdit.editPanY)));
        boolean roundB2Ok = m.editProfile("front") == null && m.editProfile("side") == null
                         && oldEdit.editZoom == null && oldEdit.editRot == null
                         && oldEdit.editPanX == null && oldEdit.editPanY == null;
        if (!roundB2Ok) System.out.println("FAIL: align-profile migration default is wrong");

        // AND BOTH SURVIVE A ROUND TRIP. A profile saved at the IDENTITY is the case that
        // catches a "the identity means unset, so write nothing" shortcut: it must come back
        // SAVED at 1/0/0/0, not absent — the user did choose that framing.
        m.setEditProfile("front", org.openpump.Model.EditProfile.of(1.0, 0.0, 0.0, 0.0));
        m.setEditProfile("side", org.openpump.Model.EditProfile.of(1.35, -2.5, 9.0, -4.0));
        oldEdit.editZoom = Double.valueOf(1.35); oldEdit.editRot = Double.valueOf(-2.5);
        oldEdit.editPanX = Double.valueOf(9.0);  oldEdit.editPanY = Double.valueOf(-4.0);
        Model roundB2 = Model.fromJson(m.toJson());
        org.openpump.Model.Reading.Photo eTrip =
            org.openpump.Model.Reading.Photo.fromJson(oldEdit.toJson());
        System.out.println("profiles saved     : "
            + (roundB2.editProfile("front") == null ? "front LOST" : "front ok")
            + ", " + (roundB2.editProfile("side") == null ? "side LOST" : "side ok"));
        System.out.println("transform saved    : " + eTrip.editZoom + " / " + eTrip.editRot
            + " / " + eTrip.editPanX + " / " + eTrip.editPanY);
        boolean roundB2Trip =
               roundB2.editProfile("front") != null
            && roundB2.editProfile("front").zoom.doubleValue() == 1.0
            && roundB2.editProfile("front").rot.doubleValue() == 0.0
            && roundB2.editProfile("side") != null
            && roundB2.editProfile("side").zoom.doubleValue() == 1.35
            && roundB2.editProfile("side").rot.doubleValue() == -2.5
            && roundB2.editProfile("side").panX.doubleValue() == 9.0
            && roundB2.editProfile("side").panY.doubleValue() == -4.0
            && roundB2.editProfile("top") == null
            && eTrip.editZoom != null && eTrip.editZoom.doubleValue() == 1.35
            && eTrip.editRot != null && eTrip.editRot.doubleValue() == -2.5
            && eTrip.editPanX != null && eTrip.editPanX.doubleValue() == 9.0
            && eTrip.editPanY != null && eTrip.editPanY.doubleValue() == -4.0;
        if (!roundB2Trip) System.out.println("FAIL: align profiles do not survive a save");
        m.editProfiles.clear();          // leave the model as the probe found it

        // POINT 19, on the same old file: its photos carry no "held" key - nothing recorded
        // whether a hold was on when each was taken. That must read back UNKNOWN (null), and
        // the readers then take the reading's own kind (Compare.photoHeld), which is exactly
        // what every screen showed before the update: an at-rest reading's photo stays at
        // rest, a standardised reading's stays held. Not a guess written into the file.
        org.openpump.Model.Reading.Photo oldTaken = org.openpump.Model.Reading.Photo.fromJson(
            new org.json.JSONObject("{\"path\":\"/p/front.jpg\",\"note\":\"\",\"kpa\":\"\",\"ts\":\"1000\"}"));
        org.openpump.Model.Reading restR = new org.openpump.Model.Reading();
        restR.method = org.openpump.Model.Reading.METHOD_BPEL;
        restR.photoFront = oldTaken;
        org.openpump.Model.Reading stdR = new org.openpump.Model.Reading();
        stdR.holdKpa = Double.valueOf(20.0);
        stdR.photoFront = oldTaken;
        boolean photoTakenOldOk = oldTaken.held == null
            && !org.openpump.Compare.photoHeld(restR, oldTaken)
            && org.openpump.Compare.photoHeld(stdR, oldTaken);
        System.out.println("old photo taken    : " + (oldTaken.held == null ? "unknown" : "SET")
            + " (at rest on an at-rest reading: " + !org.openpump.Compare.photoHeld(restR, oldTaken)
            + ", held on a standardised one: " + org.openpump.Compare.photoHeld(stdR, oldTaken) + ")");
        if (!photoTakenOldOk) System.out.println("FAIL: an old photo's kind moved on upgrade");
        // And both recorded answers survive a save - FALSE is the case a "write only when
        // true" shortcut would lose.
        oldTaken.held = Boolean.FALSE;
        org.openpump.Model.Reading.Photo takenRest =
            org.openpump.Model.Reading.Photo.fromJson(oldTaken.toJson());
        oldTaken.held = Boolean.TRUE;
        org.openpump.Model.Reading.Photo takenHeld =
            org.openpump.Model.Reading.Photo.fromJson(oldTaken.toJson());
        boolean photoTakenTripOk = Boolean.FALSE.equals(takenRest.held)
                                && Boolean.TRUE.equals(takenHeld.held);
        System.out.println("photo taken saved  : " + (photoTakenTripOk ? "ok" : "BROKEN"));
        if (!photoTakenTripOk) System.out.println("FAIL: how a photo was taken did not survive a save");

        // THE OWNER'S RULE (a photo is grouped by how it was taken): a photo taken during a
        // served hold records it on itself - "std" and the hold pressure "skpa". Every photo
        // written before carries neither key. That must read back UNKNOWN (null), and the
        // readers then derive it from the reading exactly as before: held on a standardised
        // reading is standardised, at that reading's hold pressure; held on an at-rest one is
        // kept on its own. Nothing is guessed into the file.
        org.openpump.Model.Reading.Photo oldStd = org.openpump.Model.Reading.Photo.fromJson(
            new org.json.JSONObject("{\"path\":\"/p/front.jpg\",\"note\":\"\",\"kpa\":\"\","
                + "\"ts\":\"1000\",\"held\":\"1\"}"));
        org.openpump.Model.Reading stdAt20 = new org.openpump.Model.Reading();
        stdAt20.holdKpa = Double.valueOf(20.0);
        stdAt20.photoFront = oldStd;
        org.openpump.Model.Reading heldOnRest = new org.openpump.Model.Reading();
        heldOnRest.method = org.openpump.Model.Reading.METHOD_BPEL;
        heldOnRest.photoFront = oldStd;
        String oldStdKey = org.openpump.Compare.methodKey(stdAt20, "front");
        String oldHeldKey = org.openpump.Compare.methodKey(heldOnRest, "front");
        boolean photoStdOldOk = oldStd.std == null && oldStd.stdKpa == null
            && org.openpump.Compare.stdKey(20.0).equals(oldStdKey)
            && org.openpump.Compare.KEY_HELD.equals(oldHeldKey);
        System.out.println("old photo std      : " + (oldStd.std == null ? "unknown" : "SET")
            + " (held on a standardised reading: " + oldStdKey + ", held on an at-rest one: "
            + oldHeldKey + ")");
        if (!photoStdOldOk) System.out.println("FAIL: an old photo's group moved on upgrade");
        oldStd.std = Boolean.TRUE;
        oldStd.stdKpa = Double.valueOf(20.0);
        org.openpump.Model.Reading.Photo stdTrip =
            org.openpump.Model.Reading.Photo.fromJson(oldStd.toJson());
        oldStd.std = Boolean.FALSE;
        oldStd.stdKpa = null;
        org.openpump.Model.Reading.Photo notStdTrip =
            org.openpump.Model.Reading.Photo.fromJson(oldStd.toJson());
        boolean photoStdTripOk = Boolean.TRUE.equals(stdTrip.std)
            && stdTrip.stdKpa != null && stdTrip.stdKpa.doubleValue() == 20.0
            && Boolean.FALSE.equals(notStdTrip.std) && notStdTrip.stdKpa == null;
        System.out.println("photo std saved    : " + (photoStdTripOk ? "ok" : "BROKEN"));
        if (!photoStdTripOk) System.out.println("FAIL: a standardised photo's own record did not survive a save");

        // STAGE H TASK 2, on the SAME old file (build 30, pre-stage) — it has none of the
        // trainer keys at all, so this is the real on-disk case for a phone upgrading into
        // the trainer feature. Every default must be the ABSENT one: not enrolled, never
        // enrolled-at, interval girth style (the default, not "unset"), length off, both
        // tracks at a fresh L1/week-0/floor-pressure position, plan state NORMAL, no
        // decision history, every reminder off — nobody is silently opted into a training
        // plan, a track, or a notification by an app update.
        System.out.println("trainer enrolled  : " + (m.trainerEnrolled ? "ON" : "off")
                           + " at " + m.trainerEnrolledAt);
        // 0 is NOT a level: an old file has never been told about a graduation, and the
        // first Today adopts whatever level it is on in silence rather than congratulating
        // somebody for a level they reached long before this existed.
        System.out.println("last seen level    : " + m.lastSeenGirthLevel
                           + (m.lastSeenGirthLevel == 0 ? " (never announced)" : " WRONG"));
        // Neither the chip nor the unanswered-measurement row exists on an old file: nothing
        // was ever announced, so nothing can be outstanding.
        System.out.println("level up chip      : announced=" + m.levelUpAnnouncedMs
                           + (m.levelUpAnnouncedMs == 0 ? " (none)" : " WRONG"));
        System.out.println("unmeasured level   : " + m.unmeasuredLevel + " at "
                           + m.unmeasuredLevelMs
                           + (m.unmeasuredLevel == 0 && m.unmeasuredLevelMs == 0
                              ? " (nothing asked)" : " WRONG"));
        // The shape defaults ARE the constants the mint used before it read any of them,
        // so an upgrading user's next prescription is byte-identical to their last.
        System.out.println("rx shape          : warm=" + m.rxWarmMin + "min/"
                           + m.rxWarmSteps + " rest=" + m.rxRestSec + "s block="
                           + m.rxSetsPerBlock + " hold=" + m.rxHoldSec
                           + " retention=" + (m.rxRetention ? "ON WRONG" : "off")
                           + ((m.rxWarmMin == 4 && m.rxWarmSteps == 4 && m.rxRestSec == 180
                               && m.rxSetsPerBlock == 5 && m.rxHoldSec == 0)
                              ? " (the mint's own)" : " WRONG"));
        /* THE FIELDS THIS SESSION ADDED, checked the same way: a legacy file carries none
         * of these keys, and the value it decodes to has to be the truth about that file.
         *
         * rxLengthFirst is the one deliberate exception and it is called out rather than
         * hidden: the default CHANGED, because a file written before the setting existed has
         * never chosen and the honest default for "never chose" is the one the app's owner
         * asked for. Every other default here is "nothing was set, so nothing happens". */
        System.out.println("rx shape 2        : trad-rest=" + m.rxRestSecTrad
                           + " lengthFirst=" + (m.rxLengthFirst ? "ON (new default)" : "off")
                           + ((m.rxRestSecTrad == 180 && m.rxLengthFirst)
                              ? " (as intended)" : " WRONG"));
        System.out.println("petechiae rules   : marksEasily="
                           + (m.marksEasily ? "ON WRONG" : "off")
                           + " bigCylinder=" + (m.bigCylinder ? "ON WRONG" : "off")
                           + " taper=" + (Deload.armed(m) ? "ARMED" : "off")
                           + " anchor=" + m.returnAnchorMs
                           + ((!m.marksEasily && !m.bigCylinder && !Deload.armed(m)
                               && m.returnAnchorMs == 0L)
                              ? " (nothing enforced)" : " WRONG"));
        System.out.println("reduction applied : " + m.reductionKpa() + " kPa"
                           + (m.reductionKpa() == 0.0 ? " (none)" : " WRONG"));
        System.out.println("other-track remind: " + (m.remindOtherTrack ? "on" : "OFF")
                           + " (gated by sched.remind=" + m.sched.remind + ")");
        System.out.println("saved shapes      : " + m.shapes.size()
                           + (m.shapes.isEmpty() ? " (none saved)" : " WRONG"));
        System.out.println("weekly planner    : plan[0..6]="
                           + m.sched.plan[0] + m.sched.plan[1] + m.sched.plan[2]
                           + m.sched.plan[3] + m.sched.plan[4] + m.sched.plan[5]
                           + m.sched.plan[6] + " override=" + m.sched.overrideDayKey
                           + ((m.sched.plan[0] == org.openpump.Schedule.PLAN_ANY
                               && m.sched.overrideDayKey == 0)
                              ? " (the plan's own choice, every day)" : " WRONG"));
        System.out.println("trainer girth style: " + m.trainerGirthStyle
                           + (m.trainerGirthStyle == org.openpump.Plan.TRACK_GIRTH_INTERVAL
                                ? " (interval)" : " WRONG"));
        System.out.println("trainer length on  : " + (m.trainerLengthOn ? "ON" : "off"));
        System.out.println("trainer girth state: L" + m.trainerGirth.level + " wk"
                           + m.trainerGirth.weekIndex + " @ " + m.trainerGirth.pressureKpa + " kPa");
        System.out.println("trainer length state: L" + m.trainerLength.level + " wk"
                           + m.trainerLength.weekIndex);
        System.out.println("trainer state      : " + m.trainerState
                           + (m.trainerState == org.openpump.Model.TRAINER_STATE_NORMAL
                                ? " (normal)" : " WRONG"));
        System.out.println("trainer decisions  : " + m.trainerDecisions.size());
        System.out.println("trainer reminders  : train=" + m.trainerRemindTrainingDay
                           + " track=" + m.trainerRemindTrackingDay
                           + " decision=" + m.trainerRemindDecisionReady
                           + " deload=" + m.trainerRemindDeloadStart);
        System.out.println("routine trainerTrack: " + r.trainerTrack
                           + (r.trainerTrack == org.openpump.Model.TRAINER_TRACK_NONE
                                ? " (none)" : " WRONG"));
        System.out.println("stage fatigueBlock : " + r.stages.get(0).fatigueBlock);
        boolean stageHTask2Ok = !m.trainerEnrolled && m.trainerEnrolledAt == 0L
            && m.trainerGirthStyle == org.openpump.Plan.TRACK_GIRTH_INTERVAL
            && !m.trainerLengthOn
            && m.trainerGirth.level == org.openpump.Plan.L1 && m.trainerGirth.weekIndex == 0
            && Math.abs(m.trainerGirth.pressureKpa - org.openpump.Plan.L1_FLOOR_KPA) < 1e-9
            && m.trainerLength.level == org.openpump.Plan.L1
            && m.trainerState == org.openpump.Model.TRAINER_STATE_NORMAL
            && m.trainerDecisions.isEmpty()
            && !m.trainerRemindTrainingDay && !m.trainerRemindTrackingDay
            && !m.trainerRemindDecisionReady && !m.trainerRemindDeloadStart
            && r.trainerTrack == org.openpump.Model.TRAINER_TRACK_NONE
            && !r.stages.get(0).fatigueBlock;
        if (!stageHTask2Ok) System.out.println("FAIL: Stage H Task 2 migration default is wrong");

        // STAGE H TASK 4, same pre-stage file — none of the mint/idempotency/deload-anchor
        // keys exist, so every default must be the ABSENT one: nothing minted (empty ids and
        // signatures, 0 mint time), the week-table anchor unset, no deload taken (so the first
        // deload still needs 4 training weeks, not 3), no feeder mint.
        System.out.println("trainer mint girth : id='" + m.trainerGirth.lastMintId
                           + "' sig='" + m.trainerGirth.lastMintSig
                           + "' ms=" + m.trainerGirth.lastMintMs
                           + " base=" + m.trainerGirth.weekBaseIndex + "/" + m.trainerGirth.weekBaseMs);
        System.out.println("trainer deload/feed : lastDeload=" + m.trainerLastDeloadMs
                           + " firstTaken=" + m.trainerFirstDeloadTaken
                           + " feederId='" + m.trainerFeederMintId
                           + "' feederSig='" + m.trainerFeederMintSig + "'");
        boolean stageHTask4Ok = "".equals(m.trainerGirth.lastMintId)
            && "".equals(m.trainerGirth.lastMintSig) && m.trainerGirth.lastMintMs == 0L
            && m.trainerGirth.weekBaseIndex == 0 && m.trainerGirth.weekBaseMs == 0L
            && m.trainerGirth.carriedSets == 0
            && m.trainerLastDeloadMs == 0L && !m.trainerFirstDeloadTaken
            && "".equals(m.trainerFeederMintId) && "".equals(m.trainerFeederMintSig);
        if (!stageHTask4Ok) System.out.println("FAIL: Stage H Task 4 migration default is wrong");

        // ...and the new Task 4 fields survive a round trip, on the real migrated model.
        m.trainerGirth.lastMintId = "rM"; m.trainerGirth.lastMintSig = "1|1|10|17|120|0";
        m.trainerGirth.lastMintMs = 7L; m.trainerGirth.weekBaseIndex = 3; m.trainerGirth.weekBaseMs = 9L;
        m.trainerGirth.carriedSets = 13;
        m.trainerLastDeloadMs = 11L; m.trainerFirstDeloadTaken = true;
        m.trainerFeederMintId = "rF"; m.trainerFeederMintSig = "4|3|5|25|120|0";
        Model task4Trip = Model.fromJson(m.toJson());
        boolean stageHTask4Trip = "rM".equals(task4Trip.trainerGirth.lastMintId)
            && "1|1|10|17|120|0".equals(task4Trip.trainerGirth.lastMintSig)
            && task4Trip.trainerGirth.lastMintMs == 7L
            && task4Trip.trainerGirth.weekBaseIndex == 3 && task4Trip.trainerGirth.weekBaseMs == 9L
            && task4Trip.trainerGirth.carriedSets == 13
            && task4Trip.trainerLastDeloadMs == 11L && task4Trip.trainerFirstDeloadTaken
            && "rF".equals(task4Trip.trainerFeederMintId)
            && "4|3|5|25|120|0".equals(task4Trip.trainerFeederMintSig);
        if (!stageHTask4Trip) System.out.println("FAIL: Stage H Task 4 fields do not survive a save");
        // 0.10 - "ADJUST FIRST..." IS RECORDED IN THE SIGNATURE (the owner's ruling): one more
        // '|' segment in the same string, so the file's shape does not move. A signature saved
        // before it has no such segment and reads as no adjustment, unchanged; one with it
        // survives a save and reads back as the sets and the person's own pressure.
        boolean adjustOldOk = org.openpump.Mint.adjustOf(task4Trip.trainerGirth.lastMintSig) == null
            && "1|1|10|17|120|0".equals(
                   org.openpump.Mint.withoutAdjust(task4Trip.trainerGirth.lastMintSig));
        m.trainerGirth.lastMintSig = "1|2|10|27|120|0|RET40|ADJ8:24";
        Model adjustBack = Model.fromJson(m.toJson());
        org.openpump.Mint.Adjust adjustRead =
            org.openpump.Mint.adjustOf(adjustBack.trainerGirth.lastMintSig);
        boolean adjustTripOk = "1|2|10|27|120|0|RET40|ADJ8:24".equals(
                adjustBack.trainerGirth.lastMintSig)
            && adjustRead != null && adjustRead.sets == 8 && adjustRead.ownKpa
            && adjustRead.kpa == 24
            && Deload.stepHg(adjustBack.trainerGirth.lastMintSig) == 4.0;
        System.out.println("adjusted save sig  : old " + (adjustOldOk ? "no adjustment" : "WRONG")
            + ", after a save " + (adjustTripOk ? "kept" : "LOST"));
        if (!adjustOldOk || !adjustTripOk)
            System.out.println("FAIL: an adjusted save's signature does not load or survive");
        // leave the model as the probe found it
        m.trainerGirth.lastMintId = ""; m.trainerGirth.lastMintSig = ""; m.trainerGirth.lastMintMs = 0L;
        m.trainerGirth.weekBaseIndex = 0; m.trainerGirth.weekBaseMs = 0L; m.trainerGirth.carriedSets = 0;
        m.trainerLastDeloadMs = 0L; m.trainerFirstDeloadTaken = false;
        m.trainerFeederMintId = ""; m.trainerFeederMintSig = "";

        // STAGE H TASK 5, same pre-stage file — no safety-flag keys exist either, so both
        // default to the honest "no flag active" state: 0 / false.
        System.out.println("trainer safety flag: at=" + m.trainerSafetyFlagAt
                           + " numbness=" + m.trainerSafetyNumbness);
        boolean stageHTask5Ok = m.trainerSafetyFlagAt == 0L && !m.trainerSafetyNumbness;
        if (!stageHTask5Ok) System.out.println("FAIL: Stage H Task 5 migration default is wrong");

        // ...and the two new fields survive a round trip, on the real migrated model.
        m.trainerState = org.openpump.Model.TRAINER_STATE_SAFETY_FLAG;
        m.trainerSafetyFlagAt = 13L; m.trainerSafetyNumbness = true;
        Model task5Trip = Model.fromJson(m.toJson());
        boolean stageHTask5Trip = task5Trip.trainerState
                == org.openpump.Model.TRAINER_STATE_SAFETY_FLAG
            && task5Trip.trainerSafetyFlagAt == 13L && task5Trip.trainerSafetyNumbness;
        if (!stageHTask5Trip) System.out.println("FAIL: Stage H Task 5 fields do not survive a save");
        // leave the model as the probe found it
        m.trainerState = org.openpump.Model.TRAINER_STATE_NORMAL;
        m.trainerSafetyFlagAt = 0L; m.trainerSafetyNumbness = false;

        // THE MISSED-WEEK CHARGE LEDGER (reported-deload Task 3) — a girth track saved after
        // weekRepeats existed but before a report could refund one has no "missCharges" key
        // at all. That must decode safely: the repeats already charged are not invented away
        // and the ledger behind them is simply empty — the honest "these cannot be given
        // back", never a thrown exception and never a fabricated charge.
        Model.TrainerTrackState oldGirth = Model.TrainerTrackState.fromJson(
            new org.json.JSONObject("{\"weekRepeats\":3}"));
        System.out.println("old girth ledger   : weekRepeats=" + oldGirth.weekRepeats
                           + " missCharges=" + oldGirth.missCharges.size());
        boolean missChargesOldOk = oldGirth.weekRepeats == 3 && oldGirth.missCharges.isEmpty();
        if (!missChargesOldOk) System.out.println("FAIL: missCharges migration default is wrong");

        // ...and, for the pair, a file that DOES carry the ledger comes back with its entries
        // intact on the real migrated model — the other half of "persisted".
        m.trainerGirth.missCharges.add(new long[]{ 123456L, 2L });
        Model missChargesTrip = Model.fromJson(m.toJson());
        System.out.println("girth ledger saved : " + missChargesTrip.trainerGirth.missCharges.size()
                           + " charge(s)");
        boolean missChargesTripOk = missChargesTrip.trainerGirth.missCharges.size() == 1
            && missChargesTrip.trainerGirth.missCharges.get(0)[0] == 123456L
            && missChargesTrip.trainerGirth.missCharges.get(0)[1] == 2L;
        if (!missChargesTripOk) System.out.println("FAIL: missCharges does not survive a save");
        m.trainerGirth.missCharges.clear();      // leave the model as the probe found it

        // A SESSION from that same era, reusing oldSess above (its JSON has no "netTup"/
        // "grossTup" keys) — both must read null, the honest "this run predates Net/Gross
        // TUP" answer, never a fabricated 0.0.
        System.out.println("sess netTup/grossTup: " + oldSess.netTupSec + " / " + oldSess.grossTupSec);
        boolean tupOk = oldSess.netTupSec == null && oldSess.grossTupSec == null;
        if (!tupOk) System.out.println("FAIL: old session's Net/Gross TUP migration default is wrong");

        // B3 - THE ROUTINE'S OWN PLAN ON THE RECORD. The same era's session has no "plSec" /
        // "plTup" keys: 0 and null, "the plan was not recorded", which the summary answers
        // by comparing with nothing rather than with a made-up plan. And both survive a save.
        System.out.println("sess plan clock/tup: " + oldSess.plannedSec + " / " + oldSess.plannedTupSec);
        boolean planOldOk = oldSess.plannedSec == 0L && oldSess.plannedTupSec == null;
        if (!planOldOk) System.out.println("FAIL: old session's planned-time migration default is wrong");
        Model.Sess planSess = Model.Sess.fromJson(oldSess.toJson());
        planSess.plannedSec = 665L;
        planSess.plannedTupSec = Double.valueOf(360.0);
        Model.Sess planBack = Model.Sess.fromJson(planSess.toJson());
        boolean planTripOk = planBack.plannedSec == 665L
            && planBack.plannedTupSec != null && planBack.plannedTupSec.doubleValue() == 360.0;
        System.out.println("sess plan round trip: " + (planTripOk ? "ok" : "BROKEN"));
        if (!planTripOk) System.out.println("FAIL: the planned times do not survive a save");

        // C5 - WHAT THE LIVE SUMMARY KNEW, ON THE RECORD. The same era's session has none of
        // "cycPl" / "cycDid" / "cmdPk" / "aPull" / "carIn" / "carPull" / "noRd" / "npWhy":
        // it must read "not recorded" throughout (0, -1, null, 0, 0, false, 0, 0), so a
        // summary reopened from History compares nothing rather than borrowing the last live
        // run's figures. And each survives a save.
        System.out.println("sess cycles/cmd peak: " + oldSess.cyclesDone + " of "
                           + oldSess.cyclesPlanned + " / " + oldSess.cmdPeakKpa);
        boolean liveOldOk = oldSess.cyclesPlanned == 0 && oldSess.cyclesDone == -1
            && oldSess.cmdPeakKpa == null && oldSess.afterPullKpa == 0
            && oldSess.carriedInKpa == 0 && !oldSess.carriedFromPull
            && oldSess.noReadSamples == 0 && oldSess.noPeakWhy == 0;
        if (!liveOldOk) System.out.println("FAIL: old session's C5 summary figures default wrongly");
        Model.Sess liveSess = Model.Sess.fromJson(oldSess.toJson());
        liveSess.cyclesPlanned = 14; liveSess.cyclesDone = 8;
        liveSess.cmdPeakKpa = Double.valueOf(24.0);
        liveSess.afterPullKpa = 20; liveSess.carriedInKpa = 22; liveSess.carriedFromPull = true;
        liveSess.noReadSamples = 3; liveSess.noPeakWhy = 2;
        Model.Sess liveBack = Model.Sess.fromJson(liveSess.toJson());
        boolean liveTripOk = liveBack.cyclesPlanned == 14 && liveBack.cyclesDone == 8
            && liveBack.cmdPeakKpa != null && liveBack.cmdPeakKpa.doubleValue() == 24.0
            && liveBack.afterPullKpa == 20 && liveBack.carriedInKpa == 22
            && liveBack.carriedFromPull && liveBack.noReadSamples == 3
            && liveBack.noPeakWhy == 2;
        System.out.println("sess C5 round trip : " + (liveTripOk ? "ok" : "BROKEN"));
        if (!liveTripOk) System.out.println("FAIL: the C5 summary figures do not survive a save");

        // C6 - HOW LONG THE AT-PRESSURE CLOCK HELD THE SETS. Absent ("tupP") on every session
        // filed before it: "not recorded" (-1), never 0, which would claim the clock held
        // nothing. And it survives a save.
        boolean heldOldOk = oldSess.tupPausedSec == -1;
        if (!heldOldOk) System.out.println("FAIL: old session's C6 held-time default is not -1");
        Model.Sess heldSess = Model.Sess.fromJson(oldSess.toJson());
        heldSess.tupPausedSec = 1375;
        Model.Sess heldBack = Model.Sess.fromJson(heldSess.toJson());
        boolean heldTripOk = heldBack.tupPausedSec == 1375;
        System.out.println("sess C6 round trip : " + (heldTripOk ? "ok" : "BROKEN")
                           + " (old reads " + oldSess.tupPausedSec + ")");
        if (!heldTripOk) System.out.println("FAIL: the C6 held time does not survive a save");

        // C7 - THE SAME CLOCK'S HELD TIME, PER AS-RUN ROW ("clk"). Absent on every row written
        // before it: nothing held (0), which is how those rows have always been read - a set
        // the clock lengthened in an old recording still reads as it did, because its held
        // time was never recorded and cannot be recovered. And it survives a save.
        boolean clkOldOk = oldAsRun.rows.get(0).clockHeldMs == 0L;
        if (!clkOldOk) System.out.println("FAIL: an old as-run row's C7 held time is not 0");
        org.openpump.AsRun clkRun = org.openpump.AsRun.fromJson(oldAsRun.toJson());
        clkRun.rows.get(0).clockHeldMs = 45000L;
        org.openpump.AsRun clkBack = org.openpump.AsRun.fromJson(clkRun.toJson());
        boolean clkTripOk = clkBack.rows.get(0).clockHeldMs == 45000L;
        System.out.println("as-run C7 round trip: " + (clkTripOk ? "ok" : "BROKEN")
                           + " (old reads " + oldAsRun.rows.get(0).clockHeldMs + ")");
        if (!clkTripOk) System.out.println("FAIL: the C7 held time does not survive a save");
        // D2 - HOW MANY SETS REACHED THEIR TIME LIMIT. Absent ("tupLim") on every session
        // filed before it: "not recorded" (-1), so a reopened summary says nothing it cannot
        // know. And it survives a save.
        boolean limitOldOk = oldSess.setsAtLimit == -1;
        if (!limitOldOk) System.out.println("FAIL: old session's D2 limit count is not -1");
        Model.Sess limitSess = Model.Sess.fromJson(oldSess.toJson());
        limitSess.setsAtLimit = 2;
        boolean limitTripOk = Model.Sess.fromJson(limitSess.toJson()).setsAtLimit == 2;
        System.out.println("sess D2 round trip : " + (limitTripOk ? "ok" : "BROKEN")
                           + " (old reads " + oldSess.setsAtLimit + ")");
        if (!limitTripOk) System.out.println("FAIL: the D2 limit count does not survive a save");
        // THE STOP'S REASON (the safety review of the in-run Hold's limit). Absent ("stWhy") on
        // every session filed before it: no reason, and the summary says none. The Hold's
        // limit, with the limit it ran to, survives a save.
        boolean stopWhyOldOk = oldSess.stopWhy == org.openpump.RunStopReason.WHY_NONE
            && org.openpump.RunStopReason.line(oldSess.stopWhy, oldSess.stopLimSec) == null;
        if (!stopWhyOldOk) System.out.println("FAIL: an old session reads a stop reason");
        Model.Sess whySess = Model.Sess.fromJson(oldSess.toJson());
        whySess.stopWhy = org.openpump.RunStopReason.WHY_HOLD_LIMIT;
        whySess.stopLimSec = 270;
        Model.Sess whyBack = Model.Sess.fromJson(whySess.toJson());
        boolean stopWhyTripOk = whyBack.stopWhy == org.openpump.RunStopReason.WHY_HOLD_LIMIT
            && whyBack.stopLimSec == 270;
        System.out.println("sess stop reason   : " + (stopWhyTripOk ? "ok" : "BROKEN")
                           + " (old reads " + oldSess.stopWhy + ")");
        if (!stopWhyTripOk) System.out.println("FAIL: the stop's reason does not survive a save");
        // E2-4 (by hand): A RUN STOPPED AT THE START CHECK AFTER ITS BY-HAND STEP. Absent
        // ("bhChk") on every session filed before it: 0, and the summary says nothing of it;
        // the minutes by hand survive a save, and a 0 is not written at all.
        boolean byHandOldOk = oldSess.byHandStopSec == 0L;
        if (!byHandOldOk) System.out.println("FAIL: an old session reads a by-hand stop");
        Model.Sess handSess = Model.Sess.fromJson(oldSess.toJson());
        boolean byHandQuiet = false;
        try { byHandQuiet = !handSess.toJson().has("bhChk"); } catch (Exception e) { }
        handSess.byHandStopSec = 320L;
        boolean byHandTripOk = byHandOldOk && byHandQuiet
            && Model.Sess.fromJson(handSess.toJson()).byHandStopSec == 320L;
        System.out.println("sess by-hand stop  : " + (byHandTripOk ? "ok" : "BROKEN")
                           + " (old reads " + oldSess.byHandStopSec + ")");
        if (!byHandTripOk) System.out.println("FAIL: the by-hand stop does not survive a save");

        // AND THE NEW FIELDS SURVIVE A ROUND TRIP — the other half of "persisted", proven
        // once more here on the REAL on-disk migrated model/routine (SelfTest's own
        // stageHTask2() already covers this in depth on fresh objects; this is the same
        // proof on the object this probe has been carrying since the top of the file).
        m.trainerEnrolled = true;
        m.trainerEnrolledAt = 42L;
        m.trainerState = org.openpump.Model.TRAINER_STATE_STEP_BACK;
        r.trainerTrack = org.openpump.Plan.TRACK_LENGTH;
        r.stages.get(0).fatigueBlock = true;
        Model stageHTrip = Model.fromJson(m.toJson());
        Model.Routine rTrip = stageHTrip.routine("r1");
        System.out.println("trainer trip       : enrolled=" + stageHTrip.trainerEnrolled
                           + " at=" + stageHTrip.trainerEnrolledAt
                           + " state=" + stageHTrip.trainerState
                           + " track=" + rTrip.trainerTrack
                           + " fatigue=" + rTrip.stages.get(0).fatigueBlock);
        boolean stageHTripOk = stageHTrip.trainerEnrolled && stageHTrip.trainerEnrolledAt == 42L
            && stageHTrip.trainerState == org.openpump.Model.TRAINER_STATE_STEP_BACK
            && rTrip.trainerTrack == org.openpump.Plan.TRACK_LENGTH
            && rTrip.stages.get(0).fatigueBlock;
        if (!stageHTripOk) System.out.println("FAIL: Stage H Task 2 fields do not survive a save");
        // leave the model/routine as the probe found them
        m.trainerEnrolled = false; m.trainerEnrolledAt = 0L;
        m.trainerState = org.openpump.Model.TRAINER_STATE_NORMAL;
        r.trainerTrack = org.openpump.Model.TRAINER_TRACK_NONE;
        r.stages.get(0).fatigueBlock = false;

        // THE CHECK-PULL CORRECTION (wave 1 §4), on a saved file whose trainer mint
        // still carries the untouched default assessment (no "assess" keys at all →
        // kpa 20, when "both"). The real on-disk case: a phone upgrading into the cap.
        String minted = "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[{\"id\":\"t1\","
          + "\"name\":\"Expansion\",\"ramp\":false,\"up\":13,\"lo\":8,\"uh\":60,\"lh\":10,"
          + "\"sp\":60,\"dur\":300,\"up2\":13,\"lo2\":8,\"sp2\":60,\"steps\":4}],"
          + "\"routines\":[{\"id\":\"tr1\",\"name\":\"Trainer length\",\"runs\":3,"
          + "\"done\":3,\"trainerTrack\":" + org.openpump.Plan.TRACK_LENGTH + ","
          + "\"sets\":[\"t1\"]}]}";
        Model tm = Model.fromJson(minted);
        Model.Routine tr = tm.routine("tr1");
        int corrected = tm.capMintAssessments();
        System.out.println("mint cap changed   : " + corrected);
        System.out.println("mint assess kpa    : " + tr.assess.kpa
                           + " (work peak " + tm.workPeakKpa(tr) + ")");
        System.out.println("mint assess when   : " + tr.assess.when);
        boolean capOk = corrected == 1 && tr.assess.kpa == tm.workPeakKpa(tr)
                     && "after".equals(tr.assess.when) && tr.runs == 3
                     && "Trainer length".equals(tr.name)
                     && tm.capMintAssessments() == 0;
        if (!capOk) System.out.println("FAIL: mint check-pull correction is wrong");

        // THE TRACTION RESHAPE (wave 3a), on a saved length mint in the OLD shape:
        // per-hold sets interleaved with rest sets, a rest stage between the blocks,
        // and a retention hold at the end.
        StringBuilder oldSets = new StringBuilder();
        StringBuilder fatIds = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            if (i > 1) {
                oldSets.append(",{\"id\":\"g" + i + "\",\"name\":\"Rest\",\"rest\":true,"
                    + "\"fromPlan\":true,\"up\":0,\"lo\":0,\"uh\":0,\"lh\":0,\"sp\":0,"
                    + "\"dur\":10,\"up2\":0,\"lo2\":0,\"sp2\":0,\"steps\":2},");
                fatIds.append(",\"g" + i + "\",");
            }
            oldSets.append("{\"id\":\"h" + i + "\",\"name\":\"Fatigue holds " + i + "\","
                + "\"fromPlan\":true,\"ramp\":false,\"up\":8,\"lo\":7,\"uh\":60,\"lh\":0,"
                + "\"sp\":75,\"dur\":60,\"up2\":8,\"lo2\":7,\"sp2\":75,\"steps\":2}");
            fatIds.append("\"h" + i + "\"");
        }
        String lengthOld = "{\"ceil\":40,\"unit\":\"inHg\","
          + "\"sets\":[" + oldSets
          + ",{\"id\":\"ret1\",\"name\":\"Retention\",\"fromPlan\":true,\"ramp\":false,"
          + "\"up\":13,\"lo\":12,\"uh\":255,\"lh\":0,\"sp\":60,\"dur\":600,"
          + "\"up2\":13,\"lo2\":12,\"sp2\":60,\"steps\":2}"
          + ",{\"id\":\"sh1\",\"name\":\"Strain holds 1\",\"fromPlan\":true,\"ramp\":false,"
          + "\"up\":8,\"lo\":7,\"uh\":300,\"lh\":0,\"sp\":75,\"dur\":300,"
          + "\"up2\":8,\"lo2\":7,\"sp2\":75,\"steps\":2}],"
          + "\"routines\":[{\"id\":\"lr1\",\"name\":\"Trainer length\",\"runs\":4,"
          + "\"done\":4,\"trainerTrack\":" + org.openpump.Plan.TRACK_LENGTH + ","
          + "\"stages\":[{\"name\":\"Fatigue holds\",\"traction\":true,\"fatigue\":true,"
          + "\"sets\":[" + fatIds + "]},"
          + "{\"name\":\"Rest\",\"rest\":true,\"restSec\":30,\"sets\":[]},"
          + "{\"name\":\"Strain holds\",\"traction\":true,\"sets\":[\"sh1\"]},"
          + "{\"name\":\"Retention\",\"retention\":true,\"sets\":[\"ret1\"]}]}]}";
        Model lm = Model.fromJson(lengthOld);
        Model.Routine lr = lm.routine("lr1");
        int stagesBefore = lr.stages.size();
        int reshaped = lm.reshapeTractionStages();
        System.out.println("traction reshape   : " + reshaped + " routine(s), stages "
                           + stagesBefore + " -> " + lr.stages.size());
        Model.Set fatSet = lm.set(lr.stages.get(0).setIds.get(0));
        System.out.println("fatigue set        : " + (fatSet == null ? "MISSING"
            : fatSet.name + " up=" + fatSet.up + " lo=" + fatSet.lo + " uh=" + fatSet.uh
              + " lh=" + fatSet.lh + " dur=" + fatSet.dur + " reps=" + fatSet.repCount()));
        boolean reshapeOk = reshaped == 1
                     && lr.stages.size() == 2                       // rest + retention gone
                     && lr.stages.get(0).setIds.size() == 1
                     && fatSet != null && fatSet.lo == 0 && fatSet.lh == 10
                     && fatSet.uh == 60 && fatSet.repCount() == 3
                     && lr.runs == 4 && "Trainer length".equals(lr.name)
                     && lm.set("g2") == null && lm.set("h1") == null   // orphans removed
                     && lm.reshapeTractionStages() == 0;               // idempotent
        if (!reshapeOk) System.out.println("FAIL: traction reshape migration is wrong");

        /* THE MONTHS ANSWER — a file saved before the wizard's answer was kept. It has
         * neither key, which must read as "never answered" and leave the plan's month
         * exactly where it was: months since enrolment. Anything else would move an
         * existing user's caps and gates on upgrade, which is the one thing a migration
         * may never do. */
        String noMonths = "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"r1\","
          + "\"sets\":[{\"id\":\"s1\",\"name\":\"Warm\",\"up\":14,\"lo\":7,"
          + "\"uh\":20,\"lh\":5,\"sp\":60,\"dur\":180}],"
          + "\"routines\":[{\"id\":\"r1\",\"name\":\"R\",\"sets\":[\"s1\"]}],"
          + "\"trainerOn\":true,\"trainerAt\":\"1700000000000\"}";
        Model mm = Model.fromJson(noMonths);
        long threeMonthsOn = 1700000000000L + 3L * 31L * 24 * 60 * 60 * 1000L;
        int monthsNoAnswer = org.openpump.TrainerTab.monthIndexNow(mm, threeMonthsOn);
        System.out.println("months, no answer  : " + monthsNoAnswer + " (want 3)"
            + "  enrolledAt=" + mm.trainerEnrolledAt);
        boolean monthsOldOk = mm.trainerMonthsPumping == 0 && mm.trainerMonthsAt == 0L
                           && monthsNoAnswer == 3;
        if (!monthsOldOk) System.out.println("FAIL: an old file's month moved on upgrade");
        // And a file saved WITH the answer carries it back, with its date.
        Model mw = new Model();
        mw.trainerMonthsPumping = 6;
        mw.trainerMonthsAt = 1700000000000L;
        Model mwBack = Model.fromJson(mw.toJson());
        boolean monthsTrip = mwBack.trainerMonthsPumping == 6
                          && mwBack.trainerMonthsAt == 1700000000000L
                          && org.openpump.TrainerTab.monthIndexNow(mwBack, threeMonthsOn) == 9;
        System.out.println("months round trip  : " + (monthsTrip ? "ok" : "BROKEN"));
        if (!monthsTrip) System.out.println("FAIL: the months answer did not survive a save");

        /* THE TISSUE RESPONSE TEST KEEPS ITS SETTING ON UPGRADE (M4, the owner's decision on
         * item 13: off by default for NEW routines only). An existing routine loads exactly as
         * it was saved - on stays on, off stays off, settings intact - including a block an
         * interim build wrote with a "v": 2 marker. A routine saved before the test existed
         * (no block at all) loads as it always did, which is on. Nothing an upgrade loads is
         * switched off; only routines made from now on start off (AssessDefaultTest). */
        String assessOld = "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"a1\","
          + "\"sets\":[{\"id\":\"s1\",\"name\":\"Warm\",\"up\":14,\"lo\":7,"
          + "\"uh\":20,\"lh\":5,\"sp\":60,\"dur\":180}],"
          + "\"routines\":[{\"id\":\"a1\",\"name\":\"Pulse\",\"sets\":[\"s1\"],"
          + "\"assess\":[{\"on\":true,\"kpa\":17,\"sp\":55,\"dur\":60,\"when\":\"after\"}]},"
          + "{\"id\":\"a2\",\"name\":\"Chosen off\",\"sets\":[\"s1\"],"
          + "\"assess\":[{\"on\":false,\"kpa\":20,\"sp\":60,\"dur\":45,\"when\":\"both\"}]},"
          + "{\"id\":\"a3\",\"name\":\"New build, on\",\"sets\":[\"s1\"],"
          + "\"assess\":[{\"on\":true,\"kpa\":20,\"sp\":60,\"dur\":45,\"when\":\"both\",\"v\":2}]},"
          + "{\"id\":\"a4\",\"name\":\"Before the test\",\"sets\":[\"s1\"]}]}";
        Model am = Model.fromJson(assessOld);
        Model.Routine a1 = am.routine("a1"), a2 = am.routine("a2"),
                      a3 = am.routine("a3"), a4 = am.routine("a4");
        System.out.println("tissue test, old on: " + (a1 == null ? "LOST"
            : (a1.assess.on ? "ON" : "off") + " " + a1.assess.kpa + " kPa " + a1.assess.sp
              + "% " + a1.assess.dur + " s " + a1.assess.when));
        System.out.println("tissue test, old off: " + (a2 == null ? "LOST" : a2.assess.on ? "ON" : "off"));
        System.out.println("tissue test, new on : " + (a3 == null ? "LOST" : a3.assess.on ? "ON" : "off"));
        System.out.println("tissue test, no block: " + (a4 == null ? "LOST" : a4.assess.on ? "ON" : "off"));
        boolean assessOldOk = a1 != null && a1.assess.on && a1.assess.kpa == 17
                           && a1.assess.sp == 55 && a1.assess.dur == 60
                           && "after".equals(a1.assess.when)
                           && a2 != null && !a2.assess.on
                           && a4 != null && a4.assess.on
                           && a1.stages.size() == 1 && "Pulse".equals(a1.name);
        if (!assessOldOk) System.out.println("FAIL: an old routine's tissue test did not load "
                                             + "as it was saved, or lost its settings");
        boolean assessNewOk = a3 != null && a3.assess.on;
        if (!assessNewOk) System.out.println("FAIL: a tissue test turned on by an interim "
                                             + "build did not load on");
        // AND A CHOICE MADE NOW SURVIVES A SAVE, both ways: the person turns the old
        // routine's test off and the other one on, and both hold after a reload.
        a1.assess.on = false;
        a2.assess.on = true;
        Model amBack = Model.fromJson(am.toJson());
        Model.Routine a1Back = amBack.routine("a1");
        boolean assessTripOk = a1Back != null && !a1Back.assess.on && a1Back.assess.kpa == 17
                            && a1Back.assess.dur == 60 && amBack.routine("a2").assess.on
                            && amBack.routine("a4").assess.on;
        System.out.println("tissue test round trip: " + (assessTripOk ? "ok" : "BROKEN"));
        if (!assessTripOk) System.out.println("FAIL: a tissue test setting does not "
                                              + "survive a save");

        /* "PHOTO DURING THE HOLD" GOES OFF ONCE ON THE UPDATE (the owner's decision,
         * 2026-09-24: off unless the person turns it on). The switch did nothing while it was
         * stored ON by default, so every existing install carries a true nobody chose. The
         * real on-disk case: a file with the std block and photo true, and no marker. It must
         * load OFF, with the hold otherwise untouched, and carry the marker from then on - so
         * a person who turns it back on keeps it through every later save and load, and a
         * backup made after that restores it on. */
        String photoOld = "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"r1\","
          + "\"sets\":[{\"id\":\"s1\",\"name\":\"Warm\",\"up\":14,\"lo\":7,"
          + "\"uh\":20,\"lh\":5,\"sp\":60,\"dur\":180}],"
          + "\"routines\":[{\"id\":\"r1\",\"name\":\"R\",\"sets\":[\"s1\"]}],"
          + "\"std\":[{\"on\":true,\"kpa\":23,\"sec\":40,\"photo\":true,\"release\":6}]}";
        Model pm = Model.fromJson(photoOld);
        System.out.println("photo during hold, old true: " + (pm.std.photo ? "ON" : "off")
            + "  hold " + (pm.std.on ? "on" : "OFF") + " " + pm.std.kpa + " kPa " + pm.std.sec
            + " s release " + pm.std.release);
        boolean photoOffOldOk = !pm.std.photo && pm.std.on && pm.std.kpa == 23
                             && pm.std.sec == 40 && pm.std.release == 6
                             && new org.json.JSONObject(pm.toJson())
                                    .optBoolean(Model.STD_PHOTO_OFF_KEY, false);
        if (!photoOffOldOk) System.out.println("FAIL: an existing install's photo during the "
                                               + "hold did not go off once, or took the hold "
                                               + "settings with it");
        // Turned back on, it stays on: saved, reloaded twice, and restored from a backup.
        pm.std.photo = true;
        Model pm2 = Model.fromJson(Model.fromJson(pm.toJson()).toJson());
        boolean photoOnTripOk = pm2.std.photo && Model.fromJson(pm2.toJson()).std.photo;
        System.out.println("photo during hold, turned on again: "
            + (photoOnTripOk ? "stays on" : "SWITCHED OFF AGAIN"));
        if (!photoOnTripOk) System.out.println("FAIL: the one-time switch-off ran again over "
                                               + "the person's own choice");

        // A REAL OLD FILE, NOT HAND-WRITTEN JSON (the data-integrity review, item 6). The
        // cases above each probe one key in a string written for the probe. This is a
        // model.json that main's own MakeDemo wrote, with a reading of every kind added
        // through main's own Model (the reviewer's Gen.java): a standardised pre and a post
        // at another vacuum with photos, an at-rest batch sharing one photo, a Std row with
        // no hold, settings a 0.1.0 user may have chosen - "Photo during the hold" on
        // among them. Upgraded and saved, EVERYTHING it held must come back as it was;
        // the one change allowed is that switch going off (the owner's one-time decision,
        // 2026-09-24). New keys may be added; nothing may be lost or altered.
        String fixture;
        java.io.InputStream fin = Migrate.class.getResourceAsStream("/migrate/main-every-kind.json");
        if (fin == null) { System.out.println("FAIL: the old-format fixture is missing"); System.exit(1); }
        try {
            java.io.ByteArrayOutputStream fbuf = new java.io.ByteArrayOutputStream();
            byte[] fb = new byte[8192];
            for (int n; (n = fin.read(fb)) > 0; ) fbuf.write(fb, 0, n);
            fixture = new String(fbuf.toByteArray(), "UTF-8");
        } finally { fin.close(); }
        Model fm = Model.fromJson(fixture);
        String upgraded = fm.toJson();
        java.util.List<String> lost = new java.util.ArrayList<String>();
        jsonSurvives(new org.json.JSONObject(fixture), new org.json.JSONObject(upgraded), "", lost);
        boolean onlyPhotoOff = lost.size() == 1 && lost.get(0).equals("/std[0]/photo: true -> false");
        java.util.List<String> again = new java.util.ArrayList<String>();
        jsonSurvives(new org.json.JSONObject(upgraded),
                     new org.json.JSONObject(Model.fromJson(upgraded).toJson()), "", again);
        boolean fixtureOk = onlyPhotoOff && !fm.std.photo && fm.measLog.all.size() == 11
            && again.isEmpty();
        System.out.println("old file, every kind: " + fm.measLog.all.size() + " readings, "
            + (lost.isEmpty() ? "nothing changed" : "changed: " + lost));
        if (!fixtureOk) System.out.println("FAIL: a real old file did not survive the upgrade "
                                           + "(allowed: only std.photo true -> false)");

        /* A NEWER APP'S KEYS SURVIVE (0.10, keep unknown fields). The same real file, with
         * keys this version has never heard of added at the top level, inside a reading and
         * inside that reading's photo - the shape a backup from a newer app has. Loaded and
         * saved, twice, every one of them must come back with its value unchanged, and the
         * file must otherwise survive exactly as the old one does. Its own exit, so it cannot
         * be dropped from a conjunction by accident. */
        org.json.JSONObject newer = new org.json.JSONObject(fixture);
        newer.put("zFutureTop", new org.json.JSONObject(
            "{\"a\":[1,\"two\",{\"b\":true}],\"c\":\"text\"}"));
        newer.put("zFutureFlag", false);
        org.json.JSONArray nlog = newer.optJSONArray("measLog");
        org.json.JSONObject nr = null, np = null;
        for (int i = 0; nlog != null && i < nlog.length() && np == null; i++) {
            org.json.JSONObject cand = nlog.optJSONObject(i);
            org.json.JSONArray pf = cand == null ? null : cand.optJSONArray("photoFront");
            if (pf != null && pf.length() > 0) { nr = cand; np = pf.optJSONObject(0); }
        }
        boolean newerOk = nr != null && np != null;
        if (newerOk) {
            nr.put("zFutureReading", new org.json.JSONArray("[\"x\",2.5,null]"));
            np.put("zFuturePhoto", "kept");
            String once = Model.fromJson(newer.toString()).toJson();
            String twice = Model.fromJson(once).toJson();
            java.util.List<String> newerLost = new java.util.ArrayList<String>();
            jsonSurvives(newer, new org.json.JSONObject(twice), "", newerLost);
            newerOk = newerLost.size() == 1
                && newerLost.get(0).equals("/std[0]/photo: true -> false");
            System.out.println("newer file's keys  : " + (newerOk ? "all kept" : "LOST " + newerLost));
        } else {
            System.out.println("newer file's keys  : the fixture has no reading with a photo");
        }
        if (!newerOk) {
            System.out.println("FAIL: keys a newer app wrote were dropped on save");
            System.out.println("MIGRATION BROKEN");
            System.exit(1);
        }

        /* THE VACUUM AT THE STANDARDISED MOMENT (0.10). Every standardised reading in the
         * real old file was filed with the vacuum at Save and no "okAt": each keeps its
         * stored value exactly, loads with observedAt unknown, and is saved without an
         * "okAt" - as it was. A new reading's "count" survives a save. Its own exit. */
        boolean momentOk = true;
        int stdSeen = 0;
        for (int i = 0; i < fm.measLog.all.size(); i++) {
            Model.Reading mr = fm.measLog.all.get(i);
            if (mr.observedKpa == null) continue;
            stdSeen++;
            if (mr.observedAt != null || mr.toJson().has("okAt")) momentOk = false;
        }
        org.json.JSONArray oldLog = new org.json.JSONObject(fixture).optJSONArray("measLog");
        for (int i = 0; oldLog != null && i < oldLog.length(); i++) {
            String stored = oldLog.optJSONObject(i).optString("observedKpa", "");
            Double now = fm.measLog.all.get(i).observedKpa;
            if (stored.length() > 0 && (now == null || now.doubleValue() != Double.parseDouble(stored)))
                momentOk = false;
        }
        Model.Reading fresh = new Model.Reading();
        fresh.id = "m1"; fresh.holdKpa = Double.valueOf(20); fresh.holdSec = Integer.valueOf(30);
        fresh.observedKpa = Double.valueOf(19.8); fresh.observedAt = org.openpump.StdMoment.AT_COUNT_END;
        momentOk = momentOk && stdSeen > 0 && org.openpump.StdMoment.AT_COUNT_END.equals(
            Model.Reading.fromJson(fresh.toJson()).observedAt);
        System.out.println("vacuum's moment    : " + stdSeen + " old standardised reading(s) "
            + (momentOk ? "kept as stored" : "CHANGED"));
        if (!momentOk) {
            System.out.println("FAIL: an old reading's recorded vacuum or its save changed");
            System.out.println("MIGRATION BROKEN");
            System.exit(1);
        }

        /* THE NOTIFICATION ASK (release review). No file saved before it has asked, so an old
         * file reads "not asked" - an install without the permission is asked once at its next
         * START or hold - and "asked" survives a save, so it is never asked twice. */
        Model nm = Model.fromJson(old);
        boolean notifyOldOk = !nm.notifyAsked;
        nm.notifyAsked = true;
        boolean notifyTripOk = Model.fromJson(nm.toJson()).notifyAsked;
        System.out.println("notification asked, old file: " + (notifyOldOk ? "no" : "YES")
            + ", after a save: " + (notifyTripOk ? "yes" : "LOST"));
        if (!notifyOldOk || !notifyTripOk)
            System.out.println("FAIL: the notification ask's one fact does not load or survive");

        /* THE HOLD LIMIT IS A WHOLE HALF-MINUTE (the safety review of the in-run Hold's limit).
         * Settings steps it in 30 s, so only a hand-edited or restored file can hold 255 - the
         * one value that put the Hold's StopWork on the pump's own step down. It loads rounded
         * (255 -> 270), an old file without the key still reads 300, and the rounded value
         * survives a save. */
        org.json.JSONObject odd = new org.json.JSONObject(old);
        odd.put("endHoldMaxSec", 255);
        int oddLoads = Model.fromJson(odd.toString()).holdMaxSec;
        boolean holdLimitOddOk = oddLoads == 270;
        org.json.JSONObject noKey = new org.json.JSONObject(old);
        noKey.remove("endHoldMaxSec");
        boolean holdLimitOldOk = Model.fromJson(noKey.toString()).holdMaxSec == 300;
        boolean holdLimitTripOk =
            Model.fromJson(Model.fromJson(odd.toString()).toJson()).holdMaxSec == 270;
        System.out.println("hold limit 255 loads as: " + oddLoads + ", absent reads 300: "
            + (holdLimitOldOk ? "yes" : "NO") + ", after a save: "
            + (holdLimitTripOk ? "270" : "CHANGED"));
        if (!holdLimitOddOk || !holdLimitOldOk || !holdLimitTripOk)
            System.out.println("FAIL: the hold limit does not load as a whole half-minute");

        /* THE TRAINER STUDY'S PLAN GROUP (the owner's decisions, 2026-09-26).
         *
         * S16: whether the feeder is offered on rest days is a setting, and its default -
         * training days only - is the owner's, for an old file too: the app
         * had offered the feeder on rest days without saying which reading it followed. The
         * person's "rest days too" survives a save.
         *
         * S13 b: a post reading now names its own session's baseline. A reading saved before
         * that has no link, pairs by the day exactly as it did, and is saved back without the
         * new key; a link survives a save. S11, S12 and S15 store nothing new. */
        Model sd = Model.fromJson(old);
        boolean feederDaysOldOk = !sd.feederRestDays;
        sd.feederRestDays = true;
        boolean feederDaysTripOk = Model.fromJson(sd.toJson()).feederRestDays;
        Model.Reading oldPost = Model.Reading.fromJson(new org.json.JSONObject(
            "{\"id\":\"m1\",\"ts\":\"1788440800000\",\"len\":\"12.0\",\"gir\":\"0\","
            + "\"phase\":2,\"method\":2}"));
        boolean pairOldOk = "".equals(oldPost.pairOf) && !oldPost.toJson().has("pair");
        for (int i = 0; i < fm.measLog.all.size(); i++)
            if (!"".equals(fm.measLog.all.get(i).pairOf)) pairOldOk = false;
        oldPost.pairOf = "m0";
        boolean pairTripOk = "m0".equals(Model.Reading.fromJson(oldPost.toJson()).pairOf);
        System.out.println("feeder on rest days, old file: " + (feederDaysOldOk ? "no" : "YES")
            + ", after a save: " + (feederDaysTripOk ? "yes" : "LOST")
            + "; old readings unlinked: " + (pairOldOk ? "yes" : "NO")
            + ", a link after a save: " + (pairTripOk ? "kept" : "LOST"));
        if (!feederDaysOldOk || !feederDaysTripOk || !pairOldOk || !pairTripOk)
            System.out.println("FAIL: the trainer study's plan group does not load or survive");

        /* S16 FOLLOW-UP (the owner's decision, 2026-09-26): an existing trainer is asked ONCE
         * whether the feeder runs on rest days. A 0.9.0 file with a plan that has the feeder
         * owes the question - and still owes it after a save made before it is answered; the
         * answer sets the switch and the marker, and both survive. A file with no plan owes
         * nothing, and neither does a new install. */
        org.json.JSONObject trainerOld = new org.json.JSONObject(old);
        trainerOld.put("trainerOn", true);
        Model fa = Model.fromJson(trainerOld.toString());
        boolean askOldOk = fa.feederDaysAskDue() && !fa.feederRestDays
            && Model.fromJson(fa.toJson()).feederDaysAskDue()
            && !Model.fromJson(old).feederDaysAskDue() && !Model.seed().feederDaysAskDue();
        fa.answerFeederDays(true);
        Model faBack = Model.fromJson(fa.toJson());
        boolean askTripOk = !faBack.feederDaysAskDue() && faBack.feederRestDays;
        System.out.println("feeder question, old trainer file: " + (askOldOk ? "owed once" : "WRONG")
            + ", after the answer: " + (askTripOk ? "settled, switch kept" : "ASKED AGAIN"));
        if (!askOldOk || !askTripOk)
            System.out.println("FAIL: the feeder question is not owed once, or not settled");

        /* 0.10 - THE SAME DAY. An old file has no day-budget key (the advisory reads on - it is
         * one line, the source's own figure, and the person can turn it off), no numbness
         * answer kept for the gap after a pull, and no session that a day ever shaped. All three
         * survive a save. */
        Model same = Model.fromJson(old);
        Model.Sess sameSess = new Model.Sess();
        sameSess.id = "sess1"; sameSess.ts = 1788440800000L; sameSess.routineId = "r1";
        boolean sameDayOldOk = same.dayBudgetAdvisory && "".equals(same.pullClearedSessId)
            && "".equals(Model.Sess.fromJson(sameSess.toJson()).shape)
            && !Model.Sess.fromJson(sameSess.toJson()).expansionDone;
        same.dayBudgetAdvisory = false;
        same.pullClearedSessId = "sess1";
        sameSess.shape = "w,t,g5:10.0";
        sameSess.expansionDone = true;
        same.sessLog.file(sameSess);
        Model sameBack = Model.fromJson(same.toJson());
        boolean sameDayTripOk = !sameBack.dayBudgetAdvisory
            && "sess1".equals(sameBack.pullClearedSessId)
            && sameBack.sessLog.all.size() > 0
            && "w,t,g5:10.0".equals(sameBack.sessLog.all.get(0).shape)
            && sameBack.sessLog.all.get(0).expansionDone;
        System.out.println("same day, old file: "
            + (sameDayOldOk ? "advisory on, nothing kept (as intended)" : "WRONG") + ", after a save: "
            + (sameDayTripOk ? "kept" : "LOST"));
        if (!sameDayOldOk || !sameDayTripOk)
            System.out.println("FAIL: the same-day fields do not load or survive");

        /* PROGRAM FIXES (2026-09-26) - THE PRESSURE CLOCK, THE DELOAD WINDOWS AND THE KEPT
         * YIELD SETS. A file written before them has a lastMintMs and no "pressureSince": the
         * clock starts where the old one stood (copied once, never earlier); there are no kept
         * deload windows (the newest is still known from trainerLastDeload); no yield sets are
         * kept and every reading is in the streak (0 / 0). All survive a save. */
        Model pc = Model.fromJson(old);
        pc.trainerGirth.lastMintMs = 1788440800000L;
        org.json.JSONObject pcJson = new org.json.JSONObject(pc.toJson());
        pcJson.getJSONArray("trainerGirth").getJSONObject(0).remove("pressureSince");
        pcJson.getJSONArray("trainerGirth").getJSONObject(0).remove("yieldSets");
        pcJson.getJSONArray("trainerGirth").getJSONObject(0).remove("yieldSince");
        pcJson.remove("deloadWindows");
        Model pcOld = Model.fromJson(pcJson.toString());
        boolean clockOldOk = pcOld.trainerGirth.pressureSinceMs == 1788440800000L
            && pcOld.deloadWindows.isEmpty()
            && pcOld.trainerGirth.yieldSets == 0 && pcOld.trainerGirth.yieldSinceMs == 0L
            && Model.fromJson(old).trainerGirth.pressureSinceMs == 0L;
        pcOld.trainerGirth.pressureSinceMs = 1788500000000L;
        pcOld.trainerGirth.yieldSets = 2; pcOld.trainerGirth.yieldSinceMs = 1788510000000L;
        org.openpump.Deload.remember(pcOld, 1788600000000L, 1788604800000L);
        Model pcBack = Model.fromJson(pcOld.toJson());
        boolean clockTripOk = pcBack.trainerGirth.pressureSinceMs == 1788500000000L
            && pcBack.trainerGirth.lastMintMs == 1788440800000L
            && pcBack.deloadWindows.size() == 1
            && pcBack.deloadWindows.get(0)[0] == 1788600000000L
            && pcBack.deloadWindows.get(0)[1] == 1788604800000L
            && pcBack.trainerGirth.yieldSets == 2
            && pcBack.trainerGirth.yieldSinceMs == 1788510000000L;
        System.out.println("pressure clock, old file: "
            + (clockOldOk ? "starts at the last mint, no windows or yield sets kept" : "WRONG")
            + ", after a save: " + (clockTripOk ? "kept" : "LOST"));
        if (!clockOldOk || !clockTripOk)
            System.out.println("FAIL: the pressure clock or the deload windows do not load or survive");

        /* THE STRAIN CLOCK (0.10). A length track written before it has no "strainSince": 0,
         * every reading in the strain debounce, as before. A step taken survives a save, and
         * a hand-edited negative is held to 0. */
        Model strClkBase = Model.fromJson(old);
        org.json.JSONObject strClkJson = new org.json.JSONObject(strClkBase.toJson());
        strClkJson.getJSONArray("trainerLength").getJSONObject(0).remove("strainSince");
        Model strClkOld = Model.fromJson(strClkJson.toString());
        boolean strainClockOldOk = strClkOld.trainerLength.strainSinceMs == 0L
            && Model.fromJson(old).trainerLength.strainSinceMs == 0L;
        strClkOld.trainerLength.setStrainSets(strClkOld.trainerLength.strainSets + 1, 1788520000000L);
        Model strClkBack = Model.fromJson(strClkOld.toJson());
        strClkJson.getJSONArray("trainerLength").getJSONObject(0).put("strainSince", "-7");
        boolean strainClockTripOk = strClkBack.trainerLength.strainSinceMs == 1788520000000L
            && Model.fromJson(strClkJson.toString()).trainerLength.strainSinceMs == 0L;
        System.out.println("strain clock, old file: "
            + (strainClockOldOk ? "every reading counts" : "WRONG")
            + ", after a save: " + (strainClockTripOk ? "kept" : "LOST"));
        if (!strainClockOldOk || !strainClockTripOk)
            System.out.println("FAIL: the strain clock does not load, survive or hold");

        /* INCOGNITO (0.10). The old file has none of its keys: every switch comes up OFF, the
         * master off with nothing chosen, the real icon, and quick hide's choice at its default
         * (C, leave and STOP). After a save every switch, the chosen set and the choice
         * survive. */
        Model inc = Model.fromJson(old);
        boolean incognitoOldOk = !inc.incognito && inc.incognitoSet == 0 && !inc.disguiseIcon
            && !inc.discreetNotifications && !inc.neutralSafetyNotices && !inc.secureWindow
            && !inc.quickHide && !inc.discreetReminders && !inc.incognitoLock && !inc.hideWidget
            && inc.quickHideAction == org.openpump.Incognito.QH_STOP;
        org.openpump.Incognito.setMaster(inc, true);
        org.openpump.Incognito.setFeature(inc, org.openpump.Incognito.RECENTS, true);
        inc.quickHideAction = org.openpump.Incognito.QH_LEAVE;
        Model incBack = Model.fromJson(inc.toJson());
        boolean incognitoTripOk = incBack.incognito
            && org.openpump.Incognito.mask(incBack) == org.openpump.Incognito.ALL
            && incBack.incognitoSet == inc.incognitoSet
            && incBack.quickHideAction == org.openpump.Incognito.QH_LEAVE;
        System.out.println("incognito, old file: " + (incognitoOldOk ? "all off, real icon" : "WRONG")
            + ", after a save: " + (incognitoTripOk ? "kept" : "LOST"));
        if (!incognitoOldOk || !incognitoTripOk)
            System.out.println("FAIL: the incognito switches do not load or survive");

        /* RUN COLOURS (0.10 run-screen redesign). The old file has none of the keys: the run is
         * coloured by step, on the status line only, in the default colours, with no buzz
         * before a pull. After a save a chosen preset, "where" and the buzz survive; a file
         * that puts STOP red on a step loads the defaults instead (RunLook#sanitize). */
        Model rcm = Model.fromJson(old);
        boolean runColOldOk = rcm.runColourOn && rcm.runColourWhere == org.openpump.RunLook.WHERE_LINE
            && java.util.Arrays.equals(rcm.runColours, org.openpump.RunLook.DEFAULT) && !rcm.pullBuzz;
        rcm.runColourOn = false;
        rcm.runColourWhere = org.openpump.RunLook.WHERE_STRONG;
        rcm.runColours = org.openpump.RunLook.preset(1);
        rcm.pullBuzz = true;
        org.json.JSONObject rcJson = new org.json.JSONObject(rcm.toJson());
        Model rcBack = Model.fromJson(rcJson.toString());
        boolean runColTripOk = !rcBack.runColourOn
            && rcBack.runColourWhere == org.openpump.RunLook.WHERE_STRONG
            && java.util.Arrays.equals(rcBack.runColours, org.openpump.RunLook.COLOUR_BLIND)
            && rcBack.pullBuzz;
        org.json.JSONArray redArr = rcJson.getJSONArray("runColours");
        redArr.put(1, "#F0564C");
        Model rcRed = Model.fromJson(rcJson.toString());
        boolean runColRedOk = java.util.Arrays.equals(rcRed.runColours, org.openpump.RunLook.DEFAULT);
        System.out.println("run colours, old file: " + (runColOldOk ? "on, status line, defaults" : "WRONG")
            + ", after a save: " + (runColTripOk ? "kept" : "LOST")
            + ", STOP red on a step: " + (runColRedOk ? "refused, defaults" : "LOADED"));
        if (!runColOldOk || !runColTripOk || !runColRedOk)
            System.out.println("FAIL: the run colours do not load, survive or refuse red");

        /* WHERE THE − / + CONTROLS SIT (0.10 final). The old file has no key: pinned above the
         * buttons. After a save "under the chart" survives, and a value that is neither loads
         * as pinned. */
        Model sw = Model.fromJson(old);
        boolean stripOldOk = sw.runStripWhere == Model.RUN_STRIP_PINNED;
        sw.runStripWhere = Model.RUN_STRIP_UNDER_CHART;
        org.json.JSONObject swJson = new org.json.JSONObject(sw.toJson());
        Model swBack = Model.fromJson(swJson.toString());
        boolean stripTripOk = swBack.runStripWhere == Model.RUN_STRIP_UNDER_CHART;
        swJson.put("runStripWhere", 7);
        boolean stripOddOk = Model.fromJson(swJson.toString()).runStripWhere
            == Model.RUN_STRIP_PINNED;
        // "Chart first on ramps" (0.10) is a third stored value in the same key: it survives a
        // save, and a file that holds an old 0 or 1 still loads as it did.
        sw.runStripWhere = Model.RUN_STRIP_RAMP_FIRST;
        boolean stripRampOk = Model.fromJson(sw.toJson()).runStripWhere
            == Model.RUN_STRIP_RAMP_FIRST;
        swJson.put("runStripWhere", 0);
        boolean strip0Ok = Model.fromJson(swJson.toString()).runStripWhere == Model.RUN_STRIP_PINNED;
        swJson.put("runStripWhere", 1);
        boolean strip1Ok = Model.fromJson(swJson.toString()).runStripWhere
            == Model.RUN_STRIP_UNDER_CHART;
        System.out.println("run strip, old file: " + (stripOldOk ? "pinned" : "WRONG")
            + ", after a save: " + (stripTripOk ? "kept" : "LOST")
            + ", an odd value: " + (stripOddOk ? "pinned" : "LOADED")
            + ", chart first on ramps: " + (stripRampOk ? "kept" : "LOST")
            + ", old 0 and 1: " + (strip0Ok && strip1Ok ? "unchanged" : "CHANGED"));
        if (!stripRampOk || !strip0Ok || !strip1Ok)
            System.out.println("FAIL: the third strip answer does not survive, or an old one changed");
        if (!stripOldOk || !stripTripOk || !stripOddOk)
            System.out.println("FAIL: where the − / + controls sit does not load or survive");

        /* THE FIRST-RUN SETUP (0.10). The old file has no "firstRun": it loads as DONE (2), because
         * every phone that has a model.json is already set up and must never meet the wizard. Only
         * the seed is NOT STARTED (0). After a save each of the three states survives, and a
         * value outside them loads inside them. */
        Model frOld = Model.fromJson(old);
        boolean firstRunOldOk = frOld.firstRun == org.openpump.FirstRun.DONE
            && !org.openpump.FirstRun.shouldShow(frOld, false, false);
        boolean firstRunSeedOk = Model.seed().firstRun == org.openpump.FirstRun.NOT_STARTED;
        Model frMid = Model.seed();
        org.openpump.FirstRun.begin(frMid);
        Model frBack = Model.fromJson(frMid.toJson());
        boolean firstRunTripOk = frBack.firstRun == org.openpump.FirstRun.IN_PROGRESS
            && "1010100".equals(frBack.sched.mask()) && frBack.sched.hour == 19
            && !frBack.sched.remind;
        org.json.JSONObject frJson = new org.json.JSONObject(frMid.toJson());
        frJson.put("firstRun", 7);
        boolean firstRunOddOk = Model.fromJson(frJson.toString()).firstRun
            == org.openpump.FirstRun.DONE;
        System.out.println("first run, old file: " + (firstRunOldOk ? "done" : "WRONG")
            + ", seed: " + (firstRunSeedOk ? "not started" : "WRONG")
            + ", in progress after a save: " + (firstRunTripOk ? "kept" : "LOST")
            + ", an odd value: " + (firstRunOddOk ? "done" : "LOADED"));
        if (!firstRunOldOk || !firstRunSeedOk || !firstRunTripOk || !firstRunOddOk)
            System.out.println("FAIL: the first-run state does not load or survive");

        /* THE UNFINISHED RUN'S SNAPSHOT (rc9b, device check EMU9b N1). It is written to its own
         * preferences file, not model.json - five new keys beside the old index: the step's
         * as-built place (src), the steps past it (ahead), its block, the plan's built size,
         * and the skips (two lists, "3,65537"). A snapshot written before them reads -1 / ""
         * for each and must rejoin as it always did, by the index alone. The new ones survive
         * the write; an odd list keeps only its whole numbers, an odd count reads 0. */
        org.openpump.RunRejoin.Spot snapOld = org.openpump.RunRejoin.Spot.read(6, -1, 0, -1, -1, "", "");
        boolean snapOldOk = !snapOld.known() && snapOld.legacyIdx == 6
            && snapOld.skipped.isEmpty() && snapOld.taken.isEmpty();
        org.openpump.RunRejoin.Spot snapNew = org.openpump.RunRejoin.Spot.read(3, 8, 1,
            org.openpump.ComingSteps.key(3, 0), 10, "3,65537", "1,2");
        org.openpump.RunRejoin.Spot snapBack = org.openpump.RunRejoin.Spot.read(snapNew.legacyIdx,
            snapNew.src, snapNew.ahead, snapNew.block, snapNew.built, snapNew.skippedCsv(),
            snapNew.takenCsv());
        boolean snapTripOk = snapBack.known() && snapBack.src == 8 && snapBack.ahead == 1
            && snapBack.block == 3 && snapBack.built == 10
            && "3,65537".equals(snapBack.skippedCsv()) && "1,2".equals(snapBack.takenCsv());
        org.openpump.RunRejoin.Spot snapOdd = org.openpump.RunRejoin.Spot.read(2, 5, -4, 3, 10,
            "x,,7", "1;2");
        boolean snapOddOk = snapOdd.ahead == 0 && "7".equals(snapOdd.skippedCsv())
            && snapOdd.taken.isEmpty();
        System.out.println("run snapshot, old keys: " + (snapOldOk ? "index only" : "WRONG")
            + ", new keys after a write: " + (snapTripOk ? "kept" : "LOST")
            + ", odd values: " + (snapOddOk ? "cleaned" : "LOADED"));
        if (!snapOldOk || !snapTripOk || !snapOddOk)
            System.out.println("FAIL: the run snapshot does not read back");

        /* ONE SESSION FOR A REJOINED RUN (RunParts, the owner's pick on the leftovers, option
         * A). Two new saved things. The snapshot's "parts" key: absent on a snapshot written
         * before it, which reads as no earlier part (the rejoin files its own part, as it
         * always did); kept through a write; an odd one reads as none. And the session's
         * "rejn"/"resm" counts: absent on every session filed before them - none - and
         * written only when not 0, so an old record is byte for byte as it was; kept through
         * a save; a negative count reads as none. */
        boolean partsOldOk = org.openpump.RunParts.fromCode("") == null
            && org.openpump.RunParts.fromCode(null) == null;
        org.openpump.RunParts partsNew = org.openpump.RunParts.sofar(null, 1000L, 307_000L,
            Double.valueOf(200.0), Double.valueOf(250.0), 1000.0, Double.valueOf(30.0), 4);
        partsNew.sessId = "sess42";
        partsNew.resumes = 1;
        org.openpump.RunParts partsBack = org.openpump.RunParts.fromCode(partsNew.code());
        boolean partsTripOk = partsBack != null && partsBack.startTs == 1000L
            && partsBack.playedMs == 307_000L && Math.abs(partsBack.netSec - 200.0) < 1e-6
            && partsBack.cycles == 4 && partsBack.resumes == 1 && "sess42".equals(partsBack.sessId);
        boolean partsOddOk = org.openpump.RunParts.fromCode("p1;x;1;;;;;;;;") == null
            && org.openpump.RunParts.fromCode("p0;1;2;3;4;5;6;7;8;9;s") == null;
        boolean joinOldOk = oldSess.rejoins == 0 && oldSess.resumes == 0
            && !oldSess.toJson().has("rejn") && !oldSess.toJson().has("resm");
        Model.Sess joinSess = Model.Sess.fromJson(oldSess.toJson());
        joinSess.rejoins = 2; joinSess.resumes = 1;
        Model.Sess joinBack = Model.Sess.fromJson(joinSess.toJson());
        boolean joinTripOk = joinBack.rejoins == 2 && joinBack.resumes == 1;
        org.json.JSONObject joinOddJ = joinSess.toJson();
        joinOddJ.put("rejn", -3);
        boolean joinOddOk = Model.Sess.fromJson(joinOddJ).rejoins == 0;
        System.out.println("rejoined run, snapshot parts old: " + (partsOldOk ? "none" : "WRONG")
            + ", after a write: " + (partsTripOk ? "kept" : "LOST")
            + ", odd: " + (partsOddOk ? "none" : "LOADED")
            + "; session counts old: " + (joinOldOk ? "none, unwritten" : "WRONG")
            + ", after a save: " + (joinTripOk ? "kept" : "LOST")
            + ", odd: " + (joinOddOk ? "none" : "LOADED"));
        if (!partsOldOk || !partsTripOk || !partsOddOk || !joinOldOk || !joinTripOk || !joinOddOk)
            System.out.println("FAIL: a rejoined run's saved parts do not load or survive");

        /* WHICH DISGUISE (0.10, three of them). A save from before the choice existed has the
         * disguise switch and no "disguiseAs": with the switch on, the app stays "Fitness log",
         * the only disguise there was. A choice survives a save, as a word. */
        Model disOld = Model.fromJson(old.replaceFirst("\\{", "{\"disguise\":true,"));
        boolean disguiseOldOk = disOld.disguiseIcon
            && org.openpump.Incognito.identity(disOld) == org.openpump.Incognito.FITNESS_LOG;
        org.openpump.Incognito.choose(disOld, org.openpump.Incognito.NOTES);
        Model disBack = Model.fromJson(disOld.toJson());
        boolean disguiseTripOk = org.openpump.Incognito.identity(disBack) == org.openpump.Incognito.NOTES
            && disOld.toJson().indexOf("\"disguiseAs\":\"notes\"") >= 0;
        System.out.println("disguise, old file with it on: "
            + (disguiseOldOk ? "Fitness log" : "WRONG")
            + ", a choice after a save: " + (disguiseTripOk ? "kept" : "LOST"));
        if (!disguiseOldOk || !disguiseTripOk)
            System.out.println("FAIL: the disguise choice does not load or survive");

        /* 0.10 - THE PERSONAL SCALE. A file from before it has no offset, nothing warned, no
         * routine scale and no session scale: all load as 0 - the plan's own figure, which is
         * what those files prescribed, and nothing the person has been asked about. Each
         * survives a save; a hand-edited offset past its reach, or garbage, is held to it. */
        Model scOld = Model.fromJson(old);
        Model.Routine scR = scOld.routine("r1");
        boolean scaleOldOk = scOld.trainerGirth.offsetKpa == 0.0
            && scOld.trainerLength.offsetKpa == 0.0
            && scOld.trainerGirth.warnedOffsetKpa == 0.0
            && scOld.trainerLength.warnedOffsetKpa == 0.0
            && scR != null && scR.trainerScaleKpa == 0
            && old.indexOf("offsetKpa") < 0 && old.indexOf("trainerScale") < 0;
        scOld.trainerGirth.offsetKpa = 2.0 * org.openpump.Plan.HG;
        scOld.trainerGirth.warnedOffsetKpa = 2.0 * org.openpump.Plan.HG;
        scOld.trainerLength.offsetKpa = -1.5;
        if (scR != null) scR.trainerScaleKpa = -7;
        Model.Sess scS = new Model.Sess();
        scS.id = "scale1"; scS.ts = 5L; scS.scaleKpa = 3;
        scOld.sessLog.all.add(scS);
        Model scBack = Model.fromJson(scOld.toJson());
        Model.Sess scSBack = null;
        for (int i = 0; i < scBack.sessLog.all.size(); i++)
            if ("scale1".equals(scBack.sessLog.all.get(i).id)) scSBack = scBack.sessLog.all.get(i);
        boolean scaleTripOk = Math.abs(scBack.trainerGirth.offsetKpa - 2.0 * org.openpump.Plan.HG) < 1e-9
            && Math.abs(scBack.trainerGirth.warnedOffsetKpa - 2.0 * org.openpump.Plan.HG) < 1e-9
            && Math.abs(scBack.trainerLength.offsetKpa + 1.5) < 1e-9
            && scBack.routine("r1") != null && scBack.routine("r1").trainerScaleKpa == -7
            && scSBack != null && scSBack.scaleKpa == 3;
        String hostile = scOld.toJson().replace("\"offsetKpa\":\"" + String.valueOf(2.0 * org.openpump.Plan.HG) + "\"",
                                                "\"offsetKpa\":\"999\"")
                                       .replace("\"offsetKpa\":\"-1.5\"", "\"offsetKpa\":\"nope\"");
        Model scOdd = Model.fromJson(hostile);
        boolean scaleOddOk = Math.abs(scOdd.trainerGirth.offsetKpa
                                      - org.openpump.Scale.OFFSET_MAX_KPA) < 1e-9
            && scOdd.trainerLength.offsetKpa == 0.0
            && !hostile.equals(scOld.toJson());
        System.out.println("personal scale     : old " + (scaleOldOk ? "plan's own, none warned" : "WRONG")
            + ", after a save " + (scaleTripOk ? "kept" : "LOST")
            + ", a hand-edited offset " + (scaleOddOk ? "held to its reach" : "NOT HELD"));
        if (!scaleOldOk || !scaleTripOk || !scaleOddOk)
            System.out.println("FAIL: the personal scale does not load, survive or hold");

        /* REVIEW 2 (finding 6) - A SESSION'S SCALE IS HELD AS A ROUTINE'S IS. A hand-edited
         * backup with a session scale of -999 read every pull of it 999 kPa higher at the
         * Level 1 gate's 8 inHg check and dropped its counting line to nothing; it loads held
         * to the scale's reach, both ways, as a routine's scale always did. An ordinary value
         * loads as it was (above). */
        String scHand = scOld.toJson().replace("\"scale\":3", "\"scale\":-999")
                                      .replace("\"trainerScale\":-7", "\"trainerScale\":999");
        Model scHandBack = Model.fromJson(scHand);
        Model.Sess scHandS = null;
        for (int i = 0; i < scHandBack.sessLog.all.size(); i++)
            if ("scale1".equals(scHandBack.sessLog.all.get(i).id))
                scHandS = scHandBack.sessLog.all.get(i);
        boolean sessScaleOk = !scHand.equals(scOld.toJson())
            && scHandS != null && scHandS.scaleKpa == -org.openpump.Scale.SCALE_REACH_KPA
            && scHandBack.routine("r1") != null
            && scHandBack.routine("r1").trainerScaleKpa == org.openpump.Scale.SCALE_REACH_KPA;
        System.out.println("session scale      : a hand-edited -999 / routine 999 "
            + (sessScaleOk ? "held to the scale's reach" : "NOT HELD"));
        if (!sessScaleOk)
            System.out.println("FAIL: a hand-edited session scale is not held to its reach");

        /* 0.10 - THE RAMPS AND THE GENTLE WARM-UP. A file from before them has none of their keys:
         * each loads as the owner's default (80 %, 2 steps, 1.0 inHg, lighter days keep the ramp,
         * the climb counted; 4.0 inHg, 60 %, 1.0 inHg), which is what those files now run. Every
         * value survives a save, a hand-edited one is held to its range, and a stage's "climb"
         * mark loads false on an old stage and survives a save. */
        Model rpOld = Model.fromJson(old);
        boolean rampOldOk = rpOld.rampStartPct == 80 && rpOld.rampShortSteps == 2
            && Math.abs(rpOld.rampStepHg - 1.0) < 1e-9 && rpOld.rampLighterDays
            && rpOld.rampCountClimb
            && Math.abs(rpOld.gentleWarmStartKpa - 13.55) < 1e-9
            && rpOld.gentleWarmSpeedPct == 60 && Math.abs(rpOld.gentleWarmStepHg - 1.0) < 1e-9
            && old.indexOf("rampStart") < 0 && old.indexOf("gentleWarm") < 0
            && rpOld.routine("r1") != null && !rpOld.routine("r1").stages.get(0).climb
            && !rpOld.legacyRamp;
        rpOld.rampStartPct = 65; rpOld.rampShortSteps = 3; rpOld.rampStepHg = 0.5;
        rpOld.rampLighterDays = false; rpOld.rampCountClimb = false;
        rpOld.gentleWarmStartKpa = 10.0; rpOld.gentleWarmSpeedPct = 45;
        rpOld.gentleWarmStepHg = 0.7;
        rpOld.routine("r1").stages.get(0).climb = true;
        Model rpBack = Model.fromJson(rpOld.toJson());
        boolean rampTripOk = rpBack.rampStartPct == 65 && rpBack.rampShortSteps == 3
            && Math.abs(rpBack.rampStepHg - 0.5) < 1e-9 && !rpBack.rampLighterDays
            && !rpBack.rampCountClimb && Math.abs(rpBack.gentleWarmStartKpa - 10.0) < 1e-9
            && rpBack.gentleWarmSpeedPct == 45 && Math.abs(rpBack.gentleWarmStepHg - 0.7) < 1e-9
            && rpBack.routine("r1").stages.get(0).climb
            && rpBack.routine("r1").stages.get(0).outOfNet();
        org.json.JSONObject rpJson = new org.json.JSONObject(rpOld.toJson());
        rpJson.put("rampStartPct", 999);
        rpJson.put("rampShortSteps", -3);
        rpJson.put("rampStepHg", "nope");
        rpJson.put("gentleWarmStartKpa", "1");
        rpJson.put("gentleWarmSpeedPct", 5);
        rpJson.put("gentleWarmStepHg", "0.05");
        Model rpOdd = Model.fromJson(rpJson.toString());
        boolean rampOddOk = rpOdd.rampStartPct == 95 && rpOdd.rampShortSteps == 0
            && Math.abs(rpOdd.rampStepHg - 1.0) < 1e-9
            && Math.abs(rpOdd.gentleWarmStartKpa - Model.GENTLE_START_KPA_MIN) < 1e-9
            && rpOdd.gentleWarmSpeedPct == 40 && Math.abs(rpOdd.gentleWarmStepHg - 0.3) < 1e-9;
        System.out.println("ramps and gentle warm-up: old " + (rampOldOk ? "the defaults" : "WRONG")
            + ", after a save " + (rampTripOk ? "kept" : "LOST")
            + ", hand-edited " + (rampOddOk ? "held to their ranges" : "NOT HELD"));
        if (!rampOldOk || !rampTripOk || !rampOddOk)
            System.out.println("FAIL: the ramp or gentle warm-up settings do not load, survive or hold");

        /* 0.10 - A RAMP'S UNCOUNTED CLIMB MINUTES (Routine/Sess#climbUnderLineMin). A file from
         * before them has none: its routines and sessions load with 0 - nothing to credit,
         * which is what they ran (no climb hold under the line, or one made up in full). Each
         * survives a save; a hand-edited figure, garbage or out of range, is held to
         * [0, CLIMB_UNDER_MAX_MIN] so a file cannot credit a session with a day's work. */
        Model cuOld = Model.fromJson(old);
        boolean cuOldOk = old.indexOf("climbUnder") < 0
            && cuOld.routine("r1") != null && cuOld.routine("r1").climbUnderLineMin == 0.0;
        for (int i = 0; i < cuOld.sessLog.all.size(); i++)
            if (cuOld.sessLog.all.get(i).climbUnderLineMin != 0.0) cuOldOk = false;
        cuOld.routine("r1").climbUnderLineMin = 2.0;
        Model.Sess cuS = new Model.Sess();
        cuS.id = "climb1"; cuS.ts = 6L; cuS.climbUnderLineMin = 4.0;
        cuOld.sessLog.all.add(cuS);
        Model cuBack = Model.fromJson(cuOld.toJson());
        Model.Sess cuSBack = null;
        for (int i = 0; i < cuBack.sessLog.all.size(); i++)
            if ("climb1".equals(cuBack.sessLog.all.get(i).id)) cuSBack = cuBack.sessLog.all.get(i);
        boolean cuTripOk = cuBack.routine("r1").climbUnderLineMin == 2.0
            && cuSBack != null && cuSBack.climbUnderLineMin == 4.0;
        String cuHostile = cuOld.toJson().replace("\"climbUnder\":\"2.0\"", "\"climbUnder\":\"999\"")
                                         .replace("\"climbUnder\":\"4.0\"", "\"climbUnder\":\"nope\"");
        Model cuOdd = Model.fromJson(cuHostile);
        Model.Sess cuSOdd = null;
        for (int i = 0; i < cuOdd.sessLog.all.size(); i++)
            if ("climb1".equals(cuOdd.sessLog.all.get(i).id)) cuSOdd = cuOdd.sessLog.all.get(i);
        boolean cuOddOk = !cuHostile.equals(cuOld.toJson())
            && cuOdd.routine("r1").climbUnderLineMin == Model.CLIMB_UNDER_MAX_MIN
            && cuSOdd != null && cuSOdd.climbUnderLineMin == 0.0;
        System.out.println("uncounted climb    : old " + (cuOldOk ? "none" : "WRONG")
            + ", after a save " + (cuTripOk ? "kept" : "LOST")
            + ", hand-edited " + (cuOddOk ? "held to its range" : "NOT HELD"));
        if (!cuOldOk || !cuTripOk || !cuOddOk)
            System.out.println("FAIL: the uncounted climb minutes do not load, survive or hold");

        /* 0.10 - A RAMP IN WHOLE CYCLES A STEP (the owner's decision). The saved FORMAT is
         * unchanged; the VALUES a file from before the rule holds are corrected on load, because
         * a load is a write (Model#clampAll -> Set#clamp / Set#snapRamp): a ramp's steps used to
         * be dur / steps, never whole cycles - the screenshot's 8:42 in two steps of a 37 s
         * hold + 5 s drop ran 4:21 a step, each ending 9 s into a hold, and nine steps of a 4:10
         * hold in 15:00 never reached a drop. Each loads as whole cycles of each step's own hold
         * and drop, floored, at least one a step: 2 x 6 x 42 s (8:24), 9 x 1 x 255 s (38:15); the
         * manual cycle's ramp too. A plan-written ramp that was whole stays exactly as it was, a
         * fixed set is untouched, and the corrected figures survive a save. */
        String wcOld = "{\"ceil\":40,\"unit\":\"inHg\",\"selected\":\"r1\",\"sets\":["
          + "{\"id\":\"s1\",\"name\":\"Old ramp\",\"ramp\":true,\"up\":27,\"lo\":18,"
          + "\"uh\":37,\"lh\":5,\"sp\":75,\"dur\":522,\"up2\":35,\"lo2\":18,\"sp2\":75,\"steps\":2},"
          + "{\"id\":\"s2\",\"name\":\"Short steps\",\"ramp\":true,\"up\":27,\"lo\":18,"
          + "\"uh\":250,\"lh\":5,\"sp\":75,\"dur\":900,\"up2\":35,\"lo2\":18,\"sp2\":75,\"steps\":9},"
          + "{\"id\":\"s3\",\"name\":\"Climb\",\"ramp\":true,\"fromPlan\":true,\"up\":20,\"lo\":8,"
          + "\"uh\":60,\"lh\":5,\"sp\":75,\"dur\":390,\"up2\":26,\"lo2\":8,\"sp2\":75,\"steps\":3},"
          + "{\"id\":\"s4\",\"name\":\"Fixed\",\"ramp\":false,\"up\":20,\"lo\":8,"
          + "\"uh\":30,\"lh\":5,\"sp\":75,\"dur\":210,\"up2\":20,\"lo2\":8,\"sp2\":75,\"steps\":4}],"
          + "\"manual\":[{\"id\":\"manual\",\"name\":\"Manual run\",\"ramp\":true,\"up\":27,"
          + "\"lo\":18,\"uh\":37,\"lh\":5,\"sp\":75,\"dur\":522,\"up2\":35,\"lo2\":18,\"sp2\":75,"
          + "\"steps\":2}],"
          + "\"routines\":[{\"id\":\"r1\",\"name\":\"R\",\"sets\":[\"s1\",\"s2\",\"s3\",\"s4\"]}]}";
        Model wc = Model.fromJson(wcOld);
        boolean wcWhole = true;
        java.util.List<Model.Preset> wcPlan = wc.plan(wc.routine("r1"));
        for (int i = 0; i < wcPlan.size(); i++) {
            Model.Preset p = wcPlan.get(i);
            long cyc = Math.max(1, p.uh + p.lh) * 1000L;
            if (p.durMs < cyc || p.durMs % cyc != 0) wcWhole = false;
        }
        boolean wcOldOk = wc.set("s1").dur == 2 * 6 * 42 && wc.set("s1").steps == 2
            && wc.set("s2").dur == 9 * 255 && wc.set("s2").steps == 9
            && wc.set("s3").dur == 390 && wc.set("s4").dur == 210
            && wc.manual.ramp && wc.manual.dur == 2 * 6 * 42 && wcWhole
            && wcOld.indexOf("\"dur\":504") < 0;
        Model wcBack = Model.fromJson(wc.toJson());
        boolean wcTripOk = wcBack.set("s1").dur == 504 && wcBack.set("s2").dur == 2295
            && wcBack.set("s3").dur == 390 && wcBack.manual.dur == 504;
        System.out.println("ramp whole cycles  : old " + (wcOldOk ? "snapped on load" : "NOT WHOLE")
            + ", after a save " + (wcTripOk ? "kept" : "LOST"));
        if (!wcOldOk || !wcTripOk)
            System.out.println("FAIL: an old ramp does not load in whole cycles a step, or does not keep them");

        /* 0.10 - TRADITIONAL GIRTH GROWS BY THE WEEK (the owner's decision). A girth track saved
         * before has no "weekGrowth": its traditional week position was never counted (it sat at
         * 0, the anchor at enrolment). It loads marked so, and starts its level's growth once,
         * from its first week now (Mint#startWeekGrowth) - 3 holds at Level 1, never a jump to
         * where the weeks since enrolment would put it - and the mark survives a save. */
        String tgOld = "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],\"trainerOn\":true,"
          + "\"trainerGirthStyle\":2,\"trainerGirth\":[{\"level\":1,\"week\":0,"
          + "\"pressureKpa\":\"20.0\",\"weekBaseIndex\":0,\"weekBaseMs\":\"1000\","
          + "\"yieldSets\":1}]}";
        Model tg = Model.fromJson(tgOld);
        boolean tgOldOk = !tg.trainerGirth.weekGrowth && tg.trainerGirth.weekIndex == 0
            && tg.trainerGirth.weekBaseMs == 1000L && tg.trainerGirth.yieldSets == 1;
        boolean tgStart = org.openpump.Mint.startWeekGrowth(tg.trainerGirthStyle, tg.trainerGirth, 5000L)
            && tg.trainerGirth.weekIndex == 1 && tg.trainerGirth.weekBaseIndex == 1
            && tg.trainerGirth.weekBaseMs == 5000L
            && org.openpump.Mint.totalSets(org.openpump.Plan.TRACK_GIRTH_TRADITIONAL, tg.trainerGirth) == 4
            && !org.openpump.Mint.startWeekGrowth(tg.trainerGirthStyle, tg.trainerGirth, 9000L);
        Model tgBack = Model.fromJson(tg.toJson());
        boolean tgTripOk = tgBack.trainerGirth.weekGrowth && tgBack.trainerGirth.weekIndex == 1
            && tgBack.trainerGirth.weekBaseMs == 5000L && new Model().trainerGirth.weekGrowth;
        System.out.println("traditional weeks  : old " + (tgOldOk ? "uncounted" : "WRONG")
            + ", started " + (tgStart ? "once at week 1" : "WRONG") + ", after a save "
            + (tgTripOk ? "kept" : "LOST"));
        if (!tgOldOk || !tgStart || !tgTripOk)
            System.out.println("FAIL: an old traditional track does not start its growth once at its first week");

        /* 0.10 - THE HALF START (the owner's decision). An older traditional track above Level 1
         * has no build-up fields: it loads with none and nothing to say, and starting its growth
         * gives it one - Level 3, 2 + 1 kept yield: from half the level's 8, 4 holds, the yield
         * folded in - which a save keeps, notice still owed. A track saved by the growth's first
         * build (weekGrowth, no build fields) never gets one. */
        String hsOld = "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],\"trainerOn\":true,"
          + "\"trainerGirthStyle\":2,\"trainerGirth\":[{\"level\":3,\"week\":0,"
          + "\"pressureKpa\":\"30.0\",\"weekBaseIndex\":0,\"weekBaseMs\":\"1000\","
          + "\"yieldSets\":1}]}";
        Model hs = Model.fromJson(hsOld);
        boolean hsOldOk = !hs.trainerGirth.weekGrowth && hs.trainerGirth.buildSets == 0
            && hs.trainerGirth.buildWeek == 0 && hs.trainerGirth.buildSaid;
        boolean hsStart = org.openpump.Mint.startWeekGrowth(hs.trainerGirthStyle, hs.trainerGirth, 5000L)
            && hs.trainerGirth.buildSets == 4 && hs.trainerGirth.buildWeek == 1
            && !hs.trainerGirth.buildSaid && hs.trainerGirth.yieldSets == 0
            && org.openpump.Mint.totalSets(org.openpump.Plan.TRACK_GIRTH_TRADITIONAL, hs.trainerGirth) == 4;
        Model hsBack = Model.fromJson(hs.toJson());
        boolean hsTripOk = hsBack.trainerGirth.buildSets == 4 && hsBack.trainerGirth.buildWeek == 1
            && !hsBack.trainerGirth.buildSaid
            && org.openpump.Mint.totalSets(org.openpump.Plan.TRACK_GIRTH_TRADITIONAL, hsBack.trainerGirth) == 4;
        Model hsNew = Model.fromJson(hsOld.replace("\"yieldSets\":1", "\"yieldSets\":0,\"weekGrowth\":true"));
        boolean hsNewOk = hsNew.trainerGirth.weekGrowth && hsNew.trainerGirth.buildSets == 0
            && !org.openpump.Mint.startWeekGrowth(hsNew.trainerGirthStyle, hsNew.trainerGirth, 5000L)
            && hsNew.trainerGirth.buildSets == 0;
        System.out.println("half start         : old " + (hsOldOk ? "none yet" : "WRONG")
            + ", started " + (hsStart ? "at 4 of 8" : "WRONG") + ", after a save "
            + (hsTripOk ? "kept" : "LOST") + ", a newer track " + (hsNewOk ? "none" : "WRONG"));
        if (!hsOldOk || !hsStart || !hsTripOk || !hsNewOk)
            System.out.println("FAIL: an old traditional track above Level 1 does not get its half start, or does not keep it");

        /* 0.10 - LENGTH'S OWN "MOST YOU WILL GO TO" (Model#rxLengthMaxKpa). A file from before it
         * has one "rxMax" for both tracks and no "rxMaxL": length loads with that shared answer,
         * so nothing it prescribes changes on the upgrade, and a file with no maximum keeps none.
         * The two survive a save apart; a garbage "rxMaxL" falls back to the shared answer, and
         * one past 15 inHg is held to it, as girth's is. */
        String mlOld = "{\"ceil\":43,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],"
          + "\"rxMax\":\"37.0\"}";
        Model ml = Model.fromJson(mlOld);
        boolean maxLOldOk = ml.rxWorkMaxKpa == 37.0 && ml.rxLengthMaxKpa == 37.0
            && Model.fromJson(old).rxLengthMaxKpa == 0.0
            && org.openpump.Scale.hardKpa(ml, org.openpump.Plan.TRACK_LENGTH, 6)
               == org.openpump.Scale.hardKpa(ml, org.openpump.Plan.TRACK_GIRTH_INTERVAL, 6);
        ml.rxLengthMaxKpa = 41.0;
        Model mlBack = Model.fromJson(ml.toJson());
        boolean maxLTripOk = mlBack.rxWorkMaxKpa == 37.0 && mlBack.rxLengthMaxKpa == 41.0;
        Model mlOdd = Model.fromJson(mlOld.replace("}", ",\"rxMaxL\":\"nope\"}"));
        Model mlBig = Model.fromJson(mlOld.replace("}", ",\"rxMaxL\":\"999\"}"));
        boolean maxLOddOk = mlOdd.rxLengthMaxKpa == 37.0
            && Math.abs(mlBig.rxLengthMaxKpa - org.openpump.Plan.ABSOLUTE_CAP_KPA) < 1e-9;
        System.out.println("length's maximum   : old " + (maxLOldOk ? "the shared answer" : "WRONG")
            + ", after a save " + (maxLTripOk ? "kept apart" : "LOST")
            + ", a hand-edited one " + (maxLOddOk ? "held" : "NOT HELD"));
        if (!maxLOldOk || !maxLTripOk || !maxLOddOk)
            System.out.println("FAIL: length's own maximum does not load as the shared one, survive or hold");

        /* 2026-09-30 - WHAT A CYLINDER IS USED FOR (Model.Cylinder#role). A file from before it
         * has no "role": a cylinder whose name says "length" (any case) loads as a length
         * cylinder, everything else as girth - so the owner's "Length cylinder" pulls on the
         * upgrade. A role survives a save; a garbage one falls back to the name. */
        String roleOld = "{\"ceil\":43,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],"
          + "\"cyl\":[{\"id\":\"cyl1\",\"label\":\"Girth cylinder\",\"bore\":\"5.0\",\"len\":\"23.0\"},"
          + "{\"id\":\"cyl2\",\"label\":\"my LENGTH tube\",\"bore\":\"4.5\",\"len\":\"23.0\"},"
          + "{\"id\":\"cyl3\",\"label\":\"Main cylinder\",\"bore\":\"5.5\",\"len\":\"23.0\"}]}";
        Model ro = Model.fromJson(roleOld);
        boolean roleOldOk = ro.cylinders.size() == 3
            && "girth".equals(ro.cylinders.get(0).role)
            && "length".equals(ro.cylinders.get(1).role)
            && "girth".equals(ro.cylinders.get(2).role)
            && "cyl2".equals(ro.lengthCylinderId()) && ro.lengthPulls()
            && Math.abs(ro.lengthBoreCm() - 4.5) < 1e-9;
        ro.cylinders.get(2).role = "length";
        Model roBack = Model.fromJson(ro.toJson());
        boolean roleTripOk = "length".equals(roBack.cylinders.get(2).role)
            && "length".equals(roBack.cylinders.get(1).role)
            && "girth".equals(roBack.cylinders.get(0).role);
        Model roOdd = Model.fromJson(roleOld.replace("\"label\":\"Main cylinder\",",
            "\"label\":\"Main cylinder\",\"role\":\"banana\","));
        boolean roleOddOk = "girth".equals(roOdd.cylinders.get(2).role);
        System.out.println("cylinder roles     : old " + (roleOldOk ? "from the name" : "WRONG")
            + ", after a save " + (roleTripOk ? "kept" : "LOST")
            + ", a hand-edited one " + (roleOddOk ? "read from the name" : "NOT HELD"));
        if (!roleOldOk || !roleTripOk || !roleOddOk)
            System.out.println("FAIL: a cylinder's role does not load from its name, survive or hold");

        /* REVIEW I2 (2026-09-30) - AND FROM THE OLD PULLING RULE. Before roles, a tube pulled
         * when it fit the traction band against the logged erect girth, whatever its name (the
         * one Model#cylinderForWork(true, girth) returned). An old file with a 4.5 cm tube
         * called "Blue" and an erect girth that makes it a traction fit loads with Blue marked
         * for length - it pulled yesterday and pulls today. A file with no erect girth, and a
         * role the person set, are left as they are. */
        Model blue = new Model();
        blue.ceilKpa = 43;
        Model.Cylinder bG = new Model.Cylinder("Wide one", 5.5, 23.0); bG.id = "cyl1";
        Model.Cylinder bB = new Model.Cylinder("Blue", 4.5, 23.0); bB.id = "cyl2";
        blue.cylinders.add(bG);
        blue.cylinders.add(bB);
        blue.activeCylinder = 0;
        Model.Reading bRd = new Model.Reading();
        bRd.ts = 1_700_000_000_000L; bRd.gir = 14.0; bRd.method = Model.Reading.METHOD_MSEG;
        blue.measLog.all.add(bRd);
        String blueNew = blue.toJson();
        String blueOld = blueNew.replaceAll("\"role\":\"[a-z]+\",", "")
                                .replaceAll(",\"role\":\"[a-z]+\"", "");
        boolean blueFits = Traction.pulls(Traction.fit(4.5, 14.0))
            && !Traction.pulls(Traction.fit(5.5, 14.0));
        Model blueUp = Model.fromJson(blueOld);
        boolean blueOk = blueFits && blueOld.indexOf("\"role\"") < 0
            && "length".equals(blueUp.cylinderById("cyl2").role)
            && "girth".equals(blueUp.cylinderById("cyl1").role)
            && "cyl2".equals(blueUp.lengthCylinderId()) && blueUp.lengthPulls();
        // No erect girth: the name alone, as before.
        Model blueNoGirth = Model.fromJson(blueOld.replace("\"gir\":", "\"girX\":"));
        boolean blueNoGirthOk = "girth".equals(blueNoGirth.cylinderById("cyl2").role);
        // A role the person set is theirs: Blue marked girth stays girth.
        Model blueSet = Model.fromJson(blueNew);
        boolean blueSetOk = "girth".equals(blueSet.cylinderById("cyl2").role);
        Model blueTrip = Model.fromJson(blueUp.toJson());
        boolean blueTripOk = "length".equals(blueTrip.cylinderById("cyl2").role);
        System.out.println("cylinder roles, old: a traction fit " + (blueOk ? "marked for length" : "NOT MARKED")
            + ", no girth " + (blueNoGirthOk ? "by name" : "WRONG")
            + ", a set role " + (blueSetOk ? "kept" : "OVERWRITTEN")
            + ", after a save " + (blueTripOk ? "kept" : "LOST"));
        if (!blueOk || !blueNoGirthOk || !blueSetOk || !blueTripOk)
            System.out.println("FAIL: an old pulling tube is not marked for length on the upgrade");

        /* 2026-09-30 - THE LOAD UNIT (Model#loadUnit). A file from before it has none: it
         * follows the file's own size unit - inches -> lb, centimetres (or none) -> kg. A
         * chosen one survives a save; a garbage one falls back to the size unit's. */
        String luBase = "{\"ceil\":43,\"unit\":\"inHg\",\"sets\":[],\"routines\":[]";
        Model luIn = Model.fromJson(luBase + ",\"sizeUnit\":\"in\"}");
        Model luCm = Model.fromJson(luBase + ",\"sizeUnit\":\"cm\"}");
        Model luNone = Model.fromJson(luBase + "}");
        boolean loadOldOk = "lb".equals(luIn.loadUnit) && "kg".equals(luCm.loadUnit)
            && "kg".equals(luNone.loadUnit)
            && Model.fromJson(old).loadUnit.equals(
                   Model.Fmt.defaultLoadUnit(Model.fromJson(old).sizeUnit));
        luCm.loadUnit = "lb";
        boolean loadTripOk = "lb".equals(Model.fromJson(luCm.toJson()).loadUnit)
            && "lb".equals(Model.fromJson(luIn.toJson()).loadUnit);
        Model luOdd = Model.fromJson(luBase + ",\"sizeUnit\":\"in\",\"loadUnit\":\"stone\"}");
        boolean loadOddOk = "lb".equals(luOdd.loadUnit);
        System.out.println("load unit          : old " + (loadOldOk ? "from the size unit" : "WRONG")
            + ", after a save " + (loadTripOk ? "kept" : "LOST")
            + ", a hand-edited one " + (loadOddOk ? "the size unit's" : "NOT HELD"));
        if (!loadOldOk || !loadTripOk || !loadOddOk)
            System.out.println("FAIL: the load unit does not default from the size unit, survive or hold");

        /* t10 (the owner's decisions, 30 Sep - 1 Oct 2026) - THE NEW SAVED FIELDS, one case each.
         * An old file has none of their keys. It loads with the owner's defaults: girth after
         * length "2-minute ramped warm-up", the slow length load step, no lowered drop said,
         * every track's new state empty (slow step -1), and Long training days from the week
         * it has ("GLGLGL-" Mon-Sat -> SPLIT, Tue/Thu/Sat alternating -> ALTERNATE, all the
         * plan's choice -> COMBINED). A trainer set up before owes the upgrade card and, at
         * month 3 or later, is handed over already; a file with no trainer owes nothing. Every
         * value survives a save, and a hand-edited one is held. */
        String t10Old = "{\"ceil\":40,\"unit\":\"inHg\",\"sets\":[],\"routines\":[],"
          + "\"trainerOn\":true,\"trainerMonths\":7,\"trainerLengthOn\":true,"
          + "\"trainerGirth\":[{\"level\":3,\"week\":0,\"pressureKpa\":\"30.0\"}],"
          + "\"trainerLength\":[{\"level\":2,\"week\":0,\"pressureKpa\":\"34.0\"}],"
          + "\"sched\":[{\"days\":\"1111110\",\"plan\":\"1212120\",\"hour\":19,\"min\":0}]}";
        Model t10 = Model.fromJson(t10Old);
        Model t10Plain = Model.fromJson(old);
        Model.TrainerTrackState t10G = t10.trainerGirth, t10L = t10.trainerLength;
        Model t10Alt = Model.fromJson(t10Old.replace("\"days\":\"1111110\",\"plan\":\"1212120\"",
                                                     "\"days\":\"0101010\",\"plan\":\"0102010\""));
        boolean t10OldOk = t10.girthAfterLength == Model.R4_WARM
            && t10.lengthLoadMode == Model.LENGTH_LOAD_SLOW
            && t10.dropLoweredSaidKpa == -1
            && t10.sched.longDays == org.openpump.Schedule.LONG_SPLIT
            && t10Alt.sched.longDays == org.openpump.Schedule.LONG_ALTERNATE
            && t10Plain.sched.longDays == org.openpump.Schedule.LONG_COMBINED
            && t10.planChangedT10Due() && !t10Plain.planChangedT10Due()
            && t10L.handedOver && !t10Plain.trainerLength.handedOver
            && !t10G.addPending && t10G.hybridYield == 0 && t10G.r2ExHolds == 0
            && t10G.r2PendKpa == 0 && !t10L.dAddedInBlock && !t10L.dOfferedInBlock
            && !t10L.warned12 && t10L.slowLoadWeeks == -1 && t10L.lastCutMs == 0L
            && t10L.cutsInRow == 0
            && t10Old.indexOf("r4Mode") < 0 && t10Old.indexOf("longDays") < 0;
        /* t10 fix (review D0 row 4): a trainer PAUSED when the upgrade came (trainerOn false,
         * its enrolment kept) owes the card too - shown once it is resumed - and is handed over
         * at month 3 like a running one. */
        Model t10Paused = Model.fromJson(t10Old.replace("\"trainerOn\":true",
            "\"trainerOn\":false,\"trainerAt\":\"1767225600000\""));
        boolean t10PausedOk = !t10Paused.planT10Seen && !t10Paused.planChangedT10Due()
            && t10Paused.trainerLength.handedOver;
        t10Paused.trainerEnrolled = true;
        t10PausedOk = t10PausedOk && t10Paused.planChangedT10Due();
        t10OldOk = t10OldOk && t10PausedOk;
        t10.girthAfterLength = Model.R4_SETS;
        t10.lengthLoadMode = Model.LENGTH_LOAD_AFTER12;
        t10.dropLoweredSaidKpa = 26;
        t10.sched.longDays = org.openpump.Schedule.LONG_CAP90;
        t10.answerPlanT10Later();
        t10G.addPending = true; t10G.hybridYield = 1; t10G.r2ExHolds = 2; t10G.r2PendKpa = 3;
        t10L.dAddedInBlock = true; t10L.dOfferedInBlock = true; t10L.handedOver = false;
        t10L.warned12 = true; t10L.slowLoadWeeks = 4; t10L.lastCutMs = 1788520000000L;
        t10L.cutsInRow = 1;
        Model t10Back = Model.fromJson(t10.toJson());
        boolean t10TripOk = t10Back.girthAfterLength == Model.R4_SETS
            && t10Back.lengthLoadMode == Model.LENGTH_LOAD_AFTER12
            && t10Back.dropLoweredSaidKpa == 26
            && t10Back.sched.longDays == org.openpump.Schedule.LONG_CAP90
            && t10Back.planChangedT10Due() && t10Back.planT10Laters == 1
            && t10Back.trainerGirth.addPending && t10Back.trainerGirth.hybridYield == 1
            && t10Back.trainerGirth.r2ExHolds == 2 && t10Back.trainerGirth.r2PendKpa == 3
            && t10Back.trainerLength.dAddedInBlock && t10Back.trainerLength.dOfferedInBlock
            && !t10Back.trainerLength.handedOver && t10Back.trainerLength.warned12
            && t10Back.trainerLength.slowLoadWeeks == 4
            && t10Back.trainerLength.lastCutMs == 1788520000000L
            && t10Back.trainerLength.cutsInRow == 1;
        t10Back.answerPlanT10Setup();
        t10TripOk = t10TripOk && !Model.fromJson(t10Back.toJson()).planChangedT10Due();
        org.json.JSONObject t10Json = new org.json.JSONObject(t10.toJson());
        t10Json.put("r4Mode", 9);
        t10Json.put("lenLoad", 5);
        t10Json.put("dropSaid", -40);
        t10Json.put("planT10Later", 99);
        t10Json.getJSONArray("sched").getJSONObject(0).put("longDays", 9);
        t10Json.getJSONArray("trainerGirth").getJSONObject(0).put("hybridYield", 99);
        t10Json.getJSONArray("trainerGirth").getJSONObject(0).put("r2PendKpa", -3);
        t10Json.getJSONArray("trainerLength").getJSONObject(0).put("slowLoadWk", -8);
        t10Json.getJSONArray("trainerLength").getJSONObject(0).put("lastCut", "-1");
        Model t10Odd = Model.fromJson(t10Json.toString());
        boolean t10OddOk = t10Odd.girthAfterLength == Model.R4_WARM
            && t10Odd.lengthLoadMode == Model.LENGTH_LOAD_SLOW
            && t10Odd.dropLoweredSaidKpa == -1 && t10Odd.planT10Laters == Model.PLAN_T10_LATER_MAX
            && t10Odd.sched.longDays == org.openpump.Schedule.LONG_COMBINED
            && t10Odd.trainerGirth.hybridYield == Model.TrainerTrackState.HYBRID_YIELD_MAX
            && t10Odd.trainerGirth.r2PendKpa == 0
            && t10Odd.trainerLength.slowLoadWeeks == -1 && t10Odd.trainerLength.lastCutMs == 0L;
        System.out.println("t10 saved fields   : old " + (t10OldOk ? "the defaults, card owed (paused too), handed over" : "WRONG")
            + ", after a save " + (t10TripOk ? "kept" : "LOST")
            + ", hand-edited " + (t10OddOk ? "held" : "NOT HELD"));
        if (!t10OldOk || !t10TripOk || !t10OddOk)
            System.out.println("FAIL: the t10 fields do not load, survive or hold");

        /* Week B (2026-10-03) - THE ONE-TIME "2 FULL SESSIONS NOW COUNT" NOTICE. A file without
         * "weeksBSeen" owes it only when its trainer is set up; a plain old file and a new
         * install owe nothing. It waits while the upgrade card (which says it) is owed, answering
         * that card settles both, and "Got it" survives a save. */
        Model wbOld = Model.fromJson(t10Old);
        Model wbPlain = Model.fromJson(old);
        boolean wbOldOk = !wbOld.weeksBSeen && wbPlain.weeksBSeen && new Model().weeksBSeen
            && !wbOld.weeksBNoticeDue();                    // the upgrade card is owed first
        wbOld.answerPlanT10Setup();
        wbOldOk = wbOldOk && wbOld.weeksBSeen;
        Model wbDone = Model.fromJson(t10Old);
        wbDone.planT10Seen = true;                           // answered the card in an older build
        boolean wbTripOk = wbDone.weeksBNoticeDue()
            && Model.fromJson(wbDone.toJson()).weeksBNoticeDue();
        wbDone.weeksBSeen = true;                            // "Got it"
        wbTripOk = wbTripOk && !Model.fromJson(wbDone.toJson()).weeksBNoticeDue()
            && Model.fromJson(wbDone.toJson()).weeksBSeen;
        System.out.println("week-B notice      : old " + (wbOldOk ? "owed, after the card" : "WRONG")
            + ", after a save " + (wbTripOk ? "kept" : "LOST"));
        if (!wbOldOk || !wbTripOk)
            System.out.println("FAIL: the week-B notice flag does not load or survive");

        /* R11-5 (2026-10-04) - HOLD LENGTHS. A file without "rxFatHold" loads with 30 s fatigue
         * holds (the owner's default, for everyone); a rest of 2 or 2.5 minutes chosen before
         * loads as 3 (the guidance's 3 to 5); a choice survives a save; a hand-edited one is held
         * to the range. */
        Model hlOld = Model.fromJson(old);
        org.json.JSONObject hlTwo = new org.json.JSONObject(new Model().toJson());
        hlTwo.remove("rxFatHold");
        hlTwo.put("rxRestSec", 120);
        Model hlTwoBack = Model.fromJson(hlTwo.toString());
        boolean holdsOldOk = hlOld.rxFatigueHoldSec == 30 && hlTwoBack.rxFatigueHoldSec == 30
            && hlTwoBack.rxRestSec == 180 && new Model().rxFatigueHoldSec == 30;
        Model hlMine = new Model();
        hlMine.rxFatigueHoldSec = 45;
        hlMine.rxRestSec = 270;
        Model hlBack = Model.fromJson(hlMine.toJson());
        boolean holdsTripOk = hlBack.rxFatigueHoldSec == 45 && hlBack.rxRestSec == 270;
        org.json.JSONObject hlOdd = new org.json.JSONObject(new Model().toJson());
        hlOdd.put("rxFatHold", 5);
        boolean holdsOddOk = Model.fromJson(hlOdd.toString()).rxFatigueHoldSec == 30;
        System.out.println("hold lengths       : old " + (holdsOldOk ? "30 s, rest 3 min" : "WRONG")
            + ", after a save " + (holdsTripOk ? "kept" : "LOST")
            + ", hand-edited " + (holdsOddOk ? "held" : "NOT HELD"));
        if (!holdsOldOk || !holdsTripOk || !holdsOddOk)
            System.out.println("FAIL: the hold lengths do not load, survive or hold");

        /* R11-3 (2026-10-04) - PLACED BY THE SESSION'S LENGTH. A file without "gSessMin" /
         * "lSessMin" loads them not answered (placed by the months, an upgrader's position kept),
         * and a length track without "strainCalWk" has its strain calendar from 0, as before; the
         * answers and the offset survive a save; a hand-edited negative is not answered / 0. */
        Model plOld = Model.fromJson(old);
        boolean placeOldOk = plOld.trainerGirthSessMin == -1 && plOld.trainerLengthSessMin == -1
            && plOld.trainerLength.strainCalWeeks == 0;
        Model plMine = new Model();
        plMine.trainerGirthSessMin = 20;
        plMine.trainerLengthSessMin = 0;
        plMine.trainerLength.strainCalWeeks = 12;
        Model plBack = Model.fromJson(plMine.toJson());
        boolean placeTripOk = plBack.trainerGirthSessMin == 20 && plBack.trainerLengthSessMin == 0
            && plBack.trainerLength.strainCalWeeks == 12;
        org.json.JSONObject plOdd = new org.json.JSONObject(plMine.toJson());
        plOdd.put("gSessMin", -40);
        boolean placeOddOk = Model.fromJson(plOdd.toString()).trainerGirthSessMin == -1;
        System.out.println("session placement  : old " + (placeOldOk ? "not answered" : "WRONG")
            + ", after a save " + (placeTripOk ? "kept" : "LOST")
            + ", hand-edited " + (placeOddOk ? "held" : "NOT HELD"));
        if (!placeOldOk || !placeTripOk || !placeOddOk)
            System.out.println("FAIL: the session-length answers do not load, survive or hold");

        /* R11-4 (2026-10-04) - THE BRAKE'S ANSWER. A file without "brkKey" / "brkAns" has nothing
         * answered (the offer is asked when the step is due); an answer and the step it answered
         * survive a save; an unknown answer reads as none. */
        Model brOld = Model.fromJson(old);
        boolean brakeOldOk = brOld.trainerGirth.brakeKey == -1L
            && brOld.trainerGirth.brakeAnswer == org.openpump.GainBrake.ANSWER_NONE
            && brOld.trainerLength.brakeAnswer == org.openpump.GainBrake.ANSWER_NONE;
        Model brMine = new Model();
        brMine.trainerLength.brakeKey = 7_000_000_001L;
        brMine.trainerLength.brakeAnswer = org.openpump.GainBrake.ANSWER_STEP_UP;
        Model brBack = Model.fromJson(brMine.toJson());
        boolean brakeTripOk = brBack.trainerLength.brakeKey == 7_000_000_001L
            && brBack.trainerLength.brakeAnswer == org.openpump.GainBrake.ANSWER_STEP_UP
            && brBack.trainerGirth.brakeAnswer == org.openpump.GainBrake.ANSWER_NONE;
        String brOddJson = brMine.toJson().replace("\"brkAns\":2", "\"brkAns\":9");
        boolean brakeOddOk = Model.fromJson(brOddJson).trainerLength.brakeAnswer
            == org.openpump.GainBrake.ANSWER_NONE;
        System.out.println("gain brake answer  : old " + (brakeOldOk ? "none" : "WRONG")
            + ", after a save " + (brakeTripOk ? "kept" : "LOST")
            + ", hand-edited " + (brakeOddOk ? "held" : "NOT HELD"));
        if (!brakeOldOk || !brakeTripOk || !brakeOddOk)
            System.out.println("FAIL: the brake's answer does not load, survive or hold");

        /* FIX11 F1 (2026-10-04) - THE UNDO RECORD KEEPS ITS SETS. A plan notice saved before it
         * has no "prevSets" / "prevSig": both load "", and Undo restores only a routine whose
         * sets are all still there (PlanUndo#restore); a notice's kept sets and signature
         * survive a save; a notice with nothing kept writes neither key. */
        org.openpump.Model.PlanNotice puOld = new org.openpump.Model.PlanNotice();
        puOld.routineId = "r1";
        puOld.prevRoutine = "{\"id\":\"r1\"}";
        org.json.JSONObject puOldJ = puOld.toJson();
        org.openpump.Model.PlanNotice puOldBack = org.openpump.Model.PlanNotice.fromJson(puOldJ);
        boolean undoOldOk = !puOldJ.has("prevSets") && !puOldJ.has("prevSig")
            && "".equals(puOldBack.prevSets) && "".equals(puOldBack.prevSig);
        Model puM = Model.fromJson(old);
        org.openpump.Model.PlanNotice puMine = new org.openpump.Model.PlanNotice();
        puMine.routineId = "r1";
        puMine.prevRoutine = "{\"id\":\"r1\"}";
        puMine.prevSets = "[{\"id\":\"s9\"}]";
        puMine.prevSig = "1|3|10|30|120|1|S4:0:1355:300:R0:H0:F45:B5";
        puM.planNotice = puMine;
        Model puBack = Model.fromJson(puM.toJson());
        boolean undoTripOk = puBack.planNotice != null
            && puMine.prevSets.equals(puBack.planNotice.prevSets)
            && puMine.prevSig.equals(puBack.planNotice.prevSig);
        System.out.println("undo keeps its sets: old " + (undoOldOk ? "none kept, no keys" : "WRONG")
            + ", after a save " + (undoTripOk ? "kept" : "LOST"));
        if (!undoOldOk || !undoTripOk)
            System.out.println("FAIL: the undo record's sets do not load or survive");

        /* t10 O8 - A SESSION ON A REDUCED RETURN DAY. A session filed before it has no "retDay"
         * key and loads as a full day (its length reading stays in the low streak, as it always
         * was); a full day writes no key, so every other record is as it was byte for byte;
         * a reduced day survives a save. */
        Model.Sess retSess = new Model.Sess();
        retSess.id = "sessR"; retSess.ts = 1788440800000L; retSess.routineId = "r1";
        boolean retOldOk = !Model.Sess.fromJson(retSess.toJson()).returnDay
            && !retSess.toJson().has("retDay");
        retSess.returnDay = true;
        Model retM = Model.fromJson(old);
        retM.sessLog.file(retSess);
        Model retBack = Model.fromJson(retM.toJson());
        boolean retTripOk = retBack.sessLog.all.size() > 0
            && retBack.sessLog.all.get(0).returnDay;
        System.out.println("reduced return day : old " + (retOldOk ? "a full day, no key" : "WRONG")
            + ", after a save " + (retTripOk ? "kept" : "LOST"));
        if (!retOldOk || !retTripOk)
            System.out.println("FAIL: the reduced return day does not load or survive");

        /* t10 O7 - THE GIRTH REST KEEPS ITS START ("restFrom"), so a rest ended early still
         * says which weeks it touched. An older file has none (0: its rest counts back from
         * its end, as before); a kept start survives a save; a hand-edited negative is none. */
        Model o7 = Model.fromJson(t10Old);
        boolean o7OldOk = o7.trainerGirth.restFromMs == 0L && o7.trainerLength.restFromMs == 0L
            && t10Old.indexOf("restFrom") < 0;
        o7.trainerGirth.restFromMs = 1788520000000L;
        o7.trainerGirth.restUntilMs = 1788600000000L;
        Model o7Back = Model.fromJson(o7.toJson());
        boolean o7TripOk = o7Back.trainerGirth.restFromMs == 1788520000000L
            && o7Back.trainerGirth.restUntilMs == 1788600000000L
            && o7Back.trainerLength.restFromMs == 0L;
        org.json.JSONObject o7Json = new org.json.JSONObject(o7.toJson());
        o7Json.getJSONArray("trainerGirth").getJSONObject(0).put("restFrom", "-5");
        boolean o7OddOk = Model.fromJson(o7Json.toString()).trainerGirth.restFromMs == 0L;
        System.out.println("t10 girth rest start: old " + (o7OldOk ? "none" : "WRONG")
            + ", after a save " + (o7TripOk ? "kept" : "LOST")
            + ", hand-edited " + (o7OddOk ? "held" : "NOT HELD"));
        if (!o7OldOk || !o7TripOk || !o7OddOk)
            System.out.println("FAIL: the girth rest's start does not load, survive or hold");

        /* t10 REAL-11 - WHEN THE LENGTH LOAD LAST MOVED ("loadMoved"), so a second load step
         * waits for a session at the first. An older file has none (0: nothing waits); a
         * moved load is stamped and survives a save; a hand-edited negative is none. */
        Model lmv = Model.fromJson(t10Old);
        boolean lmOldOk = lmv.trainerLength.loadMovedMs == 0L && lmv.trainerGirth.loadMovedMs == 0L
            && t10Old.indexOf("loadMoved") < 0;
        lmv.trainerLength.setLoadLb(lmv.trainerLength.loadLb + 0.5, 1788520000000L);
        Model lmvBack = Model.fromJson(lmv.toJson());
        boolean lmTripOk = lmvBack.trainerLength.loadMovedMs == 1788520000000L
            && lmvBack.trainerGirth.loadMovedMs == 0L;
        org.json.JSONObject lmvJson = new org.json.JSONObject(lmv.toJson());
        lmvJson.getJSONArray("trainerLength").getJSONObject(0).put("loadMoved", "-5");
        boolean lmOddOk = Model.fromJson(lmvJson.toString()).trainerLength.loadMovedMs == 0L;
        System.out.println("t10 length load moved: old " + (lmOldOk ? "none" : "WRONG")
            + ", after a save " + (lmTripOk ? "kept" : "LOST")
            + ", hand-edited " + (lmOddOk ? "held" : "NOT HELD"));
        if (!lmOldOk || !lmTripOk || !lmOddOk)
            System.out.println("FAIL: the length load's move time does not load, survive or hold");

        /* t10 parity run 2, O-1 - WHEN THE PLAN LAST CHANGED THE GIRTH WORK ("planChange"), so
         * the next change waits for the next morning. An older file has none (0: nothing
         * waits); a change is stamped and survives a save; a hand-edited negative is none. */
        Model gpc = Model.fromJson(t10Old);
        boolean gpcOldOk = gpc.trainerGirth.planChangeMs == 0L
            && gpc.trainerLength.planChangeMs == 0L
            && t10Old.indexOf("planChange") < 0;
        gpc.trainerGirth.planChangeMs = 1788520000000L;
        Model gpcBack = Model.fromJson(gpc.toJson());
        boolean gpcTripOk = gpcBack.trainerGirth.planChangeMs == 1788520000000L
            && gpcBack.trainerLength.planChangeMs == 0L;
        org.json.JSONObject gpcJson = new org.json.JSONObject(gpc.toJson());
        gpcJson.getJSONArray("trainerGirth").getJSONObject(0).put("planChange", "-5");
        boolean gpcOddOk = Model.fromJson(gpcJson.toString()).trainerGirth.planChangeMs == 0L;
        System.out.println("t10 girth plan change: old " + (gpcOldOk ? "none" : "WRONG")
            + ", after a save " + (gpcTripOk ? "kept" : "LOST")
            + ", hand-edited " + (gpcOddOk ? "held" : "NOT HELD"));
        if (!gpcOldOk || !gpcTripOk || !gpcOddOk)
            System.out.println("FAIL: the girth plan change time does not load, survive or hold");

        /* Parity run 3, OPEN-2a - THE BLOCK THE OPTION-D FLAGS BELONG TO ("dBlock"). An older
         * file has none (0: the flags read as they are); a block survives a save; a
         * hand-edited negative is none. */
        Model db = Model.fromJson(t10Old);
        boolean dbOldOk = db.trainerLength.dBlockMs == 0L && t10Old.indexOf("dBlock") < 0;
        db.trainerLength.dBlockMs = 1788520000000L;
        boolean dbTripOk = Model.fromJson(db.toJson()).trainerLength.dBlockMs == 1788520000000L;
        org.json.JSONObject dbJson = new org.json.JSONObject(db.toJson());
        dbJson.getJSONArray("trainerLength").getJSONObject(0).put("dBlock", "-5");
        boolean dbOddOk = Model.fromJson(dbJson.toString()).trainerLength.dBlockMs == 0L;
        System.out.println("t10 option-D block  : old " + (dbOldOk ? "none" : "WRONG")
            + ", after a save " + (dbTripOk ? "kept" : "LOST")
            + ", hand-edited " + (dbOddOk ? "held" : "NOT HELD"));
        if (!dbOldOk || !dbTripOk || !dbOddOk)
            System.out.println("FAIL: the option-D block does not load, survive or hold");

        /* t10 REAL-1 - THE DAY THE DUE DELOAD WAS ASKED ABOUT ("deloadDueAsked"). An older file
         * has none (0: never asked, so a due deload is asked the first morning); a day survives
         * a save; a hand-edited negative is never asked. */
        Model dq = Model.fromJson(t10Old);
        boolean dqOldOk = dq.deloadDueAskedDay == 0L && t10Old.indexOf("deloadDueAsked") < 0;
        dq.deloadDueAskedDay = 20727L;
        boolean dqTripOk = Model.fromJson(dq.toJson()).deloadDueAskedDay == 20727L;
        org.json.JSONObject dqJson = new org.json.JSONObject(dq.toJson());
        dqJson.put("deloadDueAsked", "-3");
        Model dqOdd = Model.fromJson(dqJson.toString());
        dqOdd.clampAll();
        boolean dqOddOk = dqOdd.deloadDueAskedDay == 0L;
        System.out.println("t10 deload asked   : old " + (dqOldOk ? "never" : "WRONG")
            + ", after a save " + (dqTripOk ? "kept" : "LOST")
            + ", hand-edited " + (dqOddOk ? "held" : "NOT HELD"));
        if (!dqOldOk || !dqTripOk || !dqOddOk)
            System.out.println("FAIL: the deload question's day does not load, survive or hold");

        /* t10-K - THE MONTH-12 BREAK ("mbFrom", "mbUntil", "mbStarted") and the length track's
         * climb-back target ("climbTo"). An older file has none (0: no break ever taken, B6 - its
         * old girth pause and old length rest are kept as they were); a break and a target
         * survive a save; a hand-edited break out of order is none, and a target past 15 lb is
         * held there. */
        Model mb = Model.fromJson(t10Old);
        boolean mbOldOk = mb.breakFromMs == 0L && mb.breakUntilMs == 0L && !mb.breakStarted
            && mb.trainerLength.climbTargetLb == 0.0 && mb.trainerGirth.climbTargetLb == 0.0
            && t10Old.indexOf("mbFrom") < 0 && t10Old.indexOf("climbTo") < 0
            && !org.openpump.MonthBreak.taken(mb);
        Model mbRest = Model.fromJson(t10Old.replace("\"level\":2,\"week\":0,",
            "\"level\":2,\"week\":0,\"restUntil\":\"1788600000000\","));
        boolean mbRestOk = mbRest.trainerLength.restUntilMs == 1788600000000L
            && !org.openpump.MonthBreak.taken(mbRest) && mbRest.trainerLength.climbTargetLb == 0.0;
        mb.breakFromMs = 1788520000000L;
        mb.breakUntilMs = 1790939200000L;
        mb.breakStarted = true;
        mb.trainerLength.climbTargetLb = 14.7;
        Model mbBack = Model.fromJson(mb.toJson());
        boolean mbTripOk = mbBack.breakFromMs == 1788520000000L
            && mbBack.breakUntilMs == 1790939200000L && mbBack.breakStarted
            && mbBack.trainerLength.climbTargetLb == 14.7
            && mbBack.trainerGirth.climbTargetLb == 0.0;
        org.json.JSONObject mbJson = new org.json.JSONObject(mb.toJson());
        mbJson.put("mbUntil", "1788000000000");
        mbJson.getJSONArray("trainerLength").getJSONObject(0).put("climbTo", "99");
        Model mbOdd = Model.fromJson(mbJson.toString());
        mbOdd.clampAll();
        boolean mbOddOk = mbOdd.breakFromMs == 0L && mbOdd.breakUntilMs == 0L
            && !mbOdd.breakStarted
            && mbOdd.trainerLength.climbTargetLb == org.openpump.Scale.LOAD_HARD_MAX_LB;
        System.out.println("t10-K month-12 break: old " + (mbOldOk && mbRestOk ? "none" : "WRONG")
            + ", after a save " + (mbTripOk ? "kept" : "LOST")
            + ", hand-edited " + (mbOddOk ? "held" : "NOT HELD"));
        if (!mbOldOk || !mbRestOk || !mbTripOk || !mbOddOk)
            System.out.println("FAIL: the month-12 break does not load, survive or hold");

        boolean ok = capOk && reshapeOk && roundB2Ok && roundB2Trip
                  && mbOldOk && mbRestOk && mbTripOk && mbOddOk
                  && dqOldOk && dqTripOk && dqOddOk
                  && t10OldOk && t10TripOk && t10OddOk
                  && retOldOk && retTripOk
                  && o7OldOk && o7TripOk && o7OddOk
                  && lmOldOk && lmTripOk && lmOddOk
                  && gpcOldOk && gpcTripOk && gpcOddOk
                  && dbOldOk && dbTripOk && dbOddOk
                  && loadOldOk && loadTripOk && loadOddOk
                  && roleOldOk && roleTripOk && roleOddOk
                  && blueOk && blueNoGirthOk && blueSetOk && blueTripOk
                  && maxLOldOk && maxLTripOk && maxLOddOk
                  && wcOldOk && wcTripOk
                  && tgOldOk && tgStart && tgTripOk
                  && hsOldOk && hsStart && hsTripOk && hsNewOk
                  && rampOldOk && rampTripOk && rampOddOk
                  && cuOldOk && cuTripOk && cuOddOk
                  && roundDOk && roundDTrip
                  && roundCOk && roundCTrip && thenNowOk && thenNowRoundTrip
                  && roundBOk && roundBTrip && roundBCleared0
                  && methodOk && methodRoundTrip && horizonOk && horizonRoundTrip
                  && mssgOk && oldGoalOk && oldSoftOk && bothOk
                  && runOk && r.stages.size() == 1 && r.stages.get(0).setIds.size() == 2
                  && m.plan(r).size() == 2 && r.runs == 7 && "My Routine".equals(r.name)
                  && schedOk && offerOk && sessOk && sealOk && awakeOk
                  && stageHTask2Ok && tupOk && stageHTripOk
                  && stageHTask4Ok && stageHTask4Trip
                  && adjustOldOk && adjustTripOk
                  && scaleOldOk && scaleTripOk && scaleOddOk
                  // THE TWO PROBES THAT COULD PRINT FAIL AND STILL EXIT 0. Every other
                  // flag in this file is in this conjunction; these two were computed,
                  // printed on failure, and then not consulted - so a broken Stage H
                  // Task 5 migration would have said FAIL on the console and let test.sh
                  // sail past it with a green exit code.
                  && stageHTask5Ok && stageHTask5Trip
                  && missChargesOldOk && missChargesTripOk
                  && monthsOldOk && monthsTrip
                  && planOldOk && planTripOk
                  && liveOldOk && liveTripOk
                  && heldOldOk && heldTripOk
                  && clkOldOk && clkTripOk
                  && limitOldOk && limitTripOk
                  && stopWhyOldOk && stopWhyTripOk && byHandTripOk
                  && assessOldOk && assessNewOk && assessTripOk
                  && photoTakenOldOk && photoTakenTripOk && photoStdOldOk && photoStdTripOk
                  && photoOffOldOk && photoOnTripOk && fixtureOk
                  && notifyOldOk && notifyTripOk
                  && holdLimitOddOk && holdLimitOldOk && holdLimitTripOk
                  && feederDaysOldOk && feederDaysTripOk && pairOldOk && pairTripOk
                  && askOldOk && askTripOk
                  && sameDayOldOk && sameDayTripOk
                  && clockOldOk && clockTripOk
                  && strainClockOldOk && strainClockTripOk
                  && incognitoOldOk && incognitoTripOk && disguiseOldOk && disguiseTripOk
                  && runColOldOk && runColTripOk && runColRedOk
                  && stripOldOk && stripTripOk && stripOddOk
                  && firstRunOldOk && firstRunSeedOk && firstRunTripOk && firstRunOddOk
                  && snapOldOk && snapTripOk && snapOddOk
                  && partsOldOk && partsTripOk && partsOddOk
                  && joinOldOk && joinTripOk && joinOddOk
                  && holdsOldOk && holdsTripOk && holdsOddOk
                  && placeOldOk && placeTripOk && placeOddOk
                  && brakeOldOk && brakeTripOk && brakeOddOk;
        System.out.println(ok ? "MIGRATION OK" : "MIGRATION BROKEN");
        System.exit(ok ? 0 : 1);
    }

    /** Every key and value of `old` is in `now`, unchanged - recursively, arrays by index.
     *  Keys `now` adds are allowed (a newer version writes more); anything missing or
     *  altered is reported as "path: old -> new". Numbers compare by value, so 20 and 20.0
     *  are the same number. */
    static void jsonSurvives(Object old, Object now, String path, java.util.List<String> out) {
        if (old instanceof org.json.JSONObject) {
            if (!(now instanceof org.json.JSONObject)) { out.add(path + ": " + old + " -> " + now); return; }
            org.json.JSONObject o = (org.json.JSONObject) old, n = (org.json.JSONObject) now;
            java.util.Iterator<String> ks = o.keys();
            while (ks.hasNext()) {
                String k = ks.next();
                if (!n.has(k)) { out.add(path + "/" + k + ": missing"); continue; }
                jsonSurvives(o.opt(k), n.opt(k), path + "/" + k, out);
            }
            return;
        }
        if (old instanceof org.json.JSONArray) {
            if (!(now instanceof org.json.JSONArray)
                    || ((org.json.JSONArray) now).length() != ((org.json.JSONArray) old).length()) {
                out.add(path + ": length changed"); return;
            }
            org.json.JSONArray o = (org.json.JSONArray) old, n = (org.json.JSONArray) now;
            for (int i = 0; i < o.length(); i++) jsonSurvives(o.opt(i), n.opt(i), path + "[" + i + "]", out);
            return;
        }
        if (old instanceof Number && now instanceof Number) {
            if (((Number) old).doubleValue() != ((Number) now).doubleValue())
                out.add(path + ": " + old + " -> " + now);
            return;
        }
        if (old == null ? now != null : !old.equals(now)) out.add(path + ": " + old + " -> " + now);
    }
}
