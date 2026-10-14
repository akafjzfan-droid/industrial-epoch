package blackstart;

/** Bounded aggregate underfrequency surrogate; not a native machine inertia/governor simulation. */
public final class EquivalentGridModel {
    private EquivalentGridModel(){}
    public static double frequencyStep(double previous,double supply,double demand,double batteryNet,double seconds){
        DynamicsModel.nonnegative(supply,"supply");DynamicsModel.nonnegative(demand,"demand");
        DynamicsModel.finite(batteryNet,"batteryNet");DynamicsModel.nonnegative(seconds,"seconds");
        double served=Math.max(0,supply-batteryNet);
        double shortage=demand>0?Math.max(0,Math.min(1,(demand-served)/demand)):0;
        double target=50-5*shortage;
        if(!Double.isFinite(previous))previous=50;
        previous=Math.max(45,Math.min(50,previous));
        return target+(previous-target)*Math.exp(-seconds/2d);
    }
}
