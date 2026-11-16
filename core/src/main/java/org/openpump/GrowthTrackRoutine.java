package org.openpump;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Strict import boundary. Parsed targets are NOT measured telemetry or pump commands. */
public final class GrowthTrackRoutine {
    public static final String CONTRACT = "openpump-pattern-v1";
    public final String id, name;
    public final List<Step> steps;
    private GrowthTrackRoutine(String id, String name, List<Step> steps) {
        this.id=id; this.name=name; this.steps=Collections.unmodifiableList(steps);
    }
    public static final class Phase {
        public final double seconds, pressureInhg, tensionLbs;
        public final Integer reachSeconds;
        Phase(JSONObject p) throws org.json.JSONException {
            seconds=number(p,"duration_seconds",1,7200);
            if(seconds!=Math.rint(seconds)) throw bad("Fractional phase duration");
            java.util.Iterator<String> keys=p.keys();
            while(keys.hasNext()) {
                String key=keys.next();
                if(!key.matches("duration_seconds|pressure_inhg|tension_lbs|reach_seconds"))
                    throw bad("Unsupported phase field: "+key);
            }
            pressureInhg=p.has("pressure_inhg")?number(p,"pressure_inhg",0,29.92):0;
            tensionLbs=p.has("tension_lbs")?number(p,"tension_lbs",0,30):0;
            if(p.has("reach_seconds")) {
                double r=number(p,"reach_seconds",1,600);
                if(r!=Math.rint(r)) throw bad("Fractional reach time");
                reachSeconds=(int)r;
            } else reachSeconds=null;
        }
    }
    public static final class Step {
        public final String method, name, exerciseType;
        public final boolean confirmation;
        public final double seconds, restSeconds;
        public final List<Phase> phases;
        Step(JSONObject s) throws org.json.JSONException {
            // Fail closed on unnegotiated step controls. Extensible descriptive data
            // belongs in metadata, whose contents never affect execution.
            java.util.Iterator<String> fields=s.keys();
            while(fields.hasNext()) {
                String field=fields.next();
                if(!field.matches("position|method_slug|exercise_name|exercise_type|sets|rest_seconds_between_sets|work_seconds_per_set|role|pattern|category|notes|metadata"))
                    throw bad("Unsupported step field: "+field);
            }
            method=s.getString("method_slug"); name=s.getString("exercise_name");
            exerciseType=s.getString("exercise_type");
            String expected=method.startsWith("length_pumping")||method.equals("hanging")||method.equals("extending")?"length":"girth";
            if(!expected.equals(exerciseType)) throw bad("Exercise classification mismatch");
            if(!method.matches("static_pumping|interval_pumping|rip|milking|length_pumping|length_pumping_static|length_pumping_interval|length_pumping_rip|length_pumping_hp_milking|sleeved_pumping|hanging|extending")) throw bad("Unsupported exercise");
            if(number(s,"sets",1,1)!=1) throw bad("Unsupported set grouping");
            restSeconds=number(s,"rest_seconds_between_sets",0,3600);
            confirmation=s.has("role")&&"confirm".equals(s.getString("role"));
            if(s.has("role")&&!confirmation) throw bad("Unsupported role");
            seconds=number(s,"work_seconds_per_set",confirmation?0:0.001,86400);
            List<Phase> result=new ArrayList<>();
            if(confirmation) {
                if(seconds!=0||restSeconds!=0||s.has("pattern")) throw bad("Invalid confirmation step");
            } else {
                JSONArray a=s.getJSONArray("pattern");
                if(a.length()==0||a.length()>256) throw bad("Invalid phase count");
                for(int i=0;i<a.length();i++) result.add(new Phase(a.getJSONObject(i)));
            }
            phases=Collections.unmodifiableList(result);
        }
    }
    public static GrowthTrackRoutine parse(JSONObject root) throws org.json.JSONException {
        if(!"gt-routine".equals(root.getString("format"))||number(root,"schema_version",1,1)!=1||!CONTRACT.equals(root.getString("execution_contract"))) throw bad("Unsupported routine contract");
        JSONArray caps=root.getJSONArray("required_capabilities");
        boolean pattern=false, confirm=false;
        for(int i=0;i<caps.length();i++) {
            String cap=caps.getString(i);
            if("pattern".equals(cap)) pattern=true;
            else if("confirm".equals(cap)) confirm=true;
            else throw bad("Unsupported capability");
        }
        if(!pattern) throw bad("Missing pattern capability");
        JSONObject r=root.getJSONObject("routine");
        String id=r.getString("id"), name=r.getString("name");
        java.util.UUID.fromString(id);
        if(name.trim().isEmpty()||name.length()>120) throw bad("Invalid name");
        JSONArray a=r.getJSONArray("steps");
        if(a.length()==0||a.length()>500) throw bad("Invalid step count");
        List<Step> steps=new ArrayList<>(); double duration=0;
        for(int i=0;i<a.length();i++) {
            JSONObject json=a.getJSONObject(i);
            if(number(json,"position",1,500)!=i+1) throw bad("Invalid step order");
            Step step=new Step(json);
            if(step.confirmation&&!confirm) throw bad("Missing confirmation capability");
            duration+=step.seconds+(i<a.length()-1&&!step.confirmation?step.restSeconds:0);
            steps.add(step);
        }
        if(duration>86400) throw bad("Routine exceeds 24 hours");
        return new GrowthTrackRoutine(id,name,steps);
    }
    private static double number(JSONObject o,String key,double min,double max) throws org.json.JSONException {
        Object value=o.get(key);
        if(!(value instanceof Number)) throw bad("Invalid numeric field: "+key);
        double n=((Number)value).doubleValue();
        if(!Double.isFinite(n)||n<min||n>max) throw bad("Out-of-range field: "+key);
        return n;
    }
    private static IllegalArgumentException bad(String text) { return new IllegalArgumentException(text); }
}
