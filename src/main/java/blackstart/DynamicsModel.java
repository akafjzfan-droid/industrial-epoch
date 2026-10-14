package blackstart;

/** Pure seconds-based active-power model. No Arc, rendering, world or network state. */
public final class DynamicsModel {
    public static final double NOMINAL_HZ = 50;
    private DynamicsModel() {}

    /** Inertial frequency response. Imbalance is actual injection minus actual demand/loss. */
    public static double frequencyStep(double frequencyHz, double imbalance, double basePower,
                                       double inertiaSeconds, double seconds) {
        finite(imbalance, "imbalance"); nonnegative(basePower, "basePower");
        nonnegative(seconds, "seconds"); positive(inertiaSeconds, "inertiaSeconds");
        if (basePower == 0) return Double.NaN;
        finite(frequencyHz, "frequencyHz");
        return frequencyHz + NOMINAL_HZ * imbalance * seconds / (2 * inertiaSeconds * basePower);
    }

    /** Exact first-order response, bounded by the physical ramp limit. */
    public static double governorStep(double actualPower, double targetPower, double responseSeconds,
                                      double rampPerSecond, double seconds) {
        finite(actualPower, "actualPower"); finite(targetPower, "targetPower");
        positive(responseSeconds, "responseSeconds"); nonnegative(rampPerSecond, "rampPerSecond");
        nonnegative(seconds, "seconds");
        double change = (targetPower - actualPower) * -Math.expm1(-seconds / responseSeconds);
        double limit = rampPerSecond * seconds;
        return actualPower + Math.max(-limit, Math.min(limit, change));
    }

    static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
    static void nonnegative(double value, String name) {
        finite(value, name);
        if (value < 0) throw new IllegalArgumentException(name + " must be nonnegative");
    }
    static void positive(double value, String name) {
        finite(value, name);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
}
