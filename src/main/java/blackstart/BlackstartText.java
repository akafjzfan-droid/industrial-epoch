package blackstart;

import arc.Core;

/** Shared bundle fallback for diagnostics that can also reach the player HUD. */
public final class BlackstartText {
    private BlackstartText() {}
    public static String get(String key,String fallback) {
        return Core.bundle==null ? fallback : Core.bundle.get(key,fallback);
    }
}
