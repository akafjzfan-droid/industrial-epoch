package blackstart;

import java.util.HashMap;
import java.util.Map;

/** Authoritative synchronism logic. Querying UI must consume its Decision, not call observe. */
public final class SyncPolicy {
    public static final double BAND_HZ = 0.3, DIFFERENCE_HZ = 0.3;
    public static final long HOLD_TICKS = 60, MAX_SAMPLE_AGE_TICKS = 6;
    private static final double NUMERIC_EPSILON = 1e-9;
    public enum ReferenceState { UNENERGIZED, STARTING, LIVE, UNKNOWN }
    public enum Reason { SAME_ISLAND, DEAD_CONNECTION, ENERGIZE, STABLE, UNKNOWN, STARTING,
                         NO_CAPACITY, FREQUENCY, DIFFERENCE, STABILIZING }
    public record Snapshot(int island, long epoch, long sampleTick, ReferenceState reference,
                           double frequencyHz, double availablePower) {}
    public record Decision(boolean allowed, Reason reason, double differenceHz, long remainingTicks) {}
    private record Pair(int first, int second) {}
    private static final class Window {
        long firstEpoch, secondEpoch, since, last;
        Window(long a,long b,long tick) { firstEpoch=a; secondEpoch=b; since=last=tick; }
    }
    private final Map<Pair,Window> windows = new HashMap<>();
    private long lastTick = Long.MIN_VALUE;

    public Decision observe(Snapshot a, Snapshot b, long tick) {
        if (tick < lastTick) windows.clear();
        lastTick = tick;
        windows.entrySet().removeIf(e -> tick - e.getValue().last > 120);
        if (a == null || b == null) {
            // A missing identity cannot safely be associated with one cached pair.
            windows.clear();
            return reject(Reason.UNKNOWN);
        }
        if (a.island > b.island) { Snapshot swap=a; a=b; b=swap; }
        Pair pair = new Pair(a.island, b.island);
        Decision immediate = immediate(a,b,tick);
        if (immediate != null) { windows.remove(pair); return immediate; }
        Window window = windows.get(pair);
        if (window == null || window.firstEpoch != a.epoch || window.secondEpoch != b.epoch
            || tick-window.last > MAX_SAMPLE_AGE_TICKS) {
            window = new Window(a.epoch,b.epoch,tick); windows.put(pair,window);
        }
        window.last = tick;
        long remaining = Math.max(0,HOLD_TICKS-(tick-window.since));
        return new Decision(remaining == 0, remaining == 0 ? Reason.STABLE : Reason.STABILIZING,
            Math.abs(a.frequencyHz-b.frequencyHz),remaining);
    }

    private static Decision immediate(Snapshot a,Snapshot b,long tick) {
        if (a.island < 0 || b.island < 0 || stale(a,tick) || stale(b,tick)
            || a.reference == null || b.reference == null
            || a.reference == ReferenceState.UNKNOWN || b.reference == ReferenceState.UNKNOWN) return reject(Reason.UNKNOWN);
        if (a.island == b.island) return new Decision(true,Reason.SAME_ISLAND,0,0);
        if (a.reference == ReferenceState.STARTING || b.reference == ReferenceState.STARTING) return reject(Reason.STARTING);
        boolean deadA = a.reference == ReferenceState.UNENERGIZED, deadB = b.reference == ReferenceState.UNENERGIZED;
        if (deadA && deadB) return new Decision(true,Reason.DEAD_CONNECTION,Double.NaN,0);
        if (deadA || deadB) {
            Snapshot source = deadA ? b : a;
            if (!Double.isFinite(source.frequencyHz)) return reject(Reason.UNKNOWN);
            if (!Double.isFinite(source.availablePower) || source.availablePower <= 0) return reject(Reason.NO_CAPACITY);
            return new Decision(true,Reason.ENERGIZE,Double.NaN,0);
        }
        if (!Double.isFinite(a.frequencyHz) || !Double.isFinite(b.frequencyHz)) return reject(Reason.UNKNOWN);
        if (Math.abs(a.frequencyHz-DynamicsModel.NOMINAL_HZ) > BAND_HZ+NUMERIC_EPSILON
            || Math.abs(b.frequencyHz-DynamicsModel.NOMINAL_HZ) > BAND_HZ+NUMERIC_EPSILON) return reject(Reason.FREQUENCY);
        double difference = Math.abs(a.frequencyHz-b.frequencyHz);
        if (difference > DIFFERENCE_HZ+NUMERIC_EPSILON) return new Decision(false,Reason.DIFFERENCE,difference,HOLD_TICKS);
        return null;
    }
    private static boolean stale(Snapshot value,long tick) {
        return value.sampleTick > tick || tick-value.sampleTick > MAX_SAMPLE_AGE_TICKS;
    }
    private static Decision reject(Reason reason) { return new Decision(false,reason,Double.NaN,HOLD_TICKS); }
    /** World/load boundary; permits never survive it. */
    public void clear() { windows.clear(); lastTick=Long.MIN_VALUE; }
}
