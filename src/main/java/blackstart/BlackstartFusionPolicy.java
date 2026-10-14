package blackstart;

import mindustry.Vars;
import java.util.HashMap;
import java.util.Map;

/** Per-world fusion rules. All writes retain the existing host-only rule channel. */
public final class BlackstartFusionPolicy {
    public static final String MODE_KEY = "blackstart.fusion.mode";
    private static final String NATIVE_PREFIX = "blackstart.fusion.native.";
    private static final String EPOCH_PREFIX = "blackstart.fusion.epoch.";
    private static long modeRevision;
    private static final Map<String,Long> deviceRevisions = new HashMap<>();

    private BlackstartFusionPolicy(){}

    public static boolean active(){
        return Vars.state != null && Vars.state.rules != null && !Vars.state.rules.editor
            && "standard".equals(BlackstartParams.get(MODE_KEY, "standard"));
    }

    public static boolean nativeEnabled(String device){
        return known(device) && active()
            && "true".equals(BlackstartParams.get(NATIVE_PREFIX + device, "true"));
    }

    public static boolean setMode(String mode){
        String value = "standard".equals(mode) ? "standard" : "compat";
        String previous = BlackstartParams.get(MODE_KEY, "standard");
        if(!BlackstartParams.set(MODE_KEY, value)) return false;
        if(!value.equals(previous)) modeRevision++;
        return true;
    }

    public static boolean setNativeEnabled(String device, boolean enabled){
        if(!known(device)) return false;
        String value = Boolean.toString(enabled);
        String previous = BlackstartParams.get(NATIVE_PREFIX + device, "true");
        if(!BlackstartParams.set(NATIVE_PREFIX + device, value)) return false;
        if(!value.equals(previous)){
            deviceRevisions.merge(device, 1L, Long::sum);
        }
        return true;
    }

    private static boolean known(String device){
        if(device == null) return false;
        return switch(device){
            case "combustion", "steam", "differential", "rtg", "solar", "largeSolar", "thermal",
                "thorium", "impact", "turbine", "chemical", "pyrolysis", "flux", "neoplasia" -> true;
            default -> false;
        };
    }

    /** Used only by the unique parameter write door; unrelated tags have no epoch. */
    static String epochKey(String parameter){
        if(MODE_KEY.equals(parameter)) return EPOCH_PREFIX + "mode";
        if(parameter != null && parameter.startsWith(NATIVE_PREFIX)){
            String device = parameter.substring(NATIVE_PREFIX.length());
            if(known(device)) return EPOCH_PREFIX + device;
        }
        return null;
    }

    /** Older worlds and malformed metadata safely default to the initial epoch. */
    static long epoch(String key){
        try{
            long value = Long.parseLong(BlackstartParams.get(key, "0"));
            return value >= 0 ? value : 0;
        }catch(NumberFormatException invalid){return 0;}
    }

    // Broadcast epochs catch client off/on packets even when no update occurs between them.
    // Local revisions retain same-tick host changes without any new native save bytes.
    static long revision(String device){
        return modeRevision + deviceRevisions.getOrDefault(device, 0L)
            + epoch(EPOCH_PREFIX + "mode") + epoch(EPOCH_PREFIX + device);
    }
}
