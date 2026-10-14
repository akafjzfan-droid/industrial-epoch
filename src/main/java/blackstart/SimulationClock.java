package blackstart;

import java.util.Objects;
import java.util.function.LongConsumer;

/** Carries fractional engine ticks; simulation consumers always receive one 1/60s step. */
public final class SimulationClock {
    private double pending;
    private long tick;
    public void advance(double elapsedTicks,LongConsumer step) {
        DynamicsModel.nonnegative(elapsedTicks,"elapsedTicks"); Objects.requireNonNull(step,"step");
        pending+=elapsedTicks;
        while(pending+1e-9>=1) {
            step.accept(tick);
            tick++; pending-=1;
            if(pending<0 && pending>-1e-9) pending=0;
        }
    }
    public long tick(){return tick;}
    public void reset(){pending=0;tick=0;}
}
