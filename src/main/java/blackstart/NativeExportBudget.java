package blackstart;

import mindustry.world.blocks.power.PowerGraph;

/** Current intrinsic generation, with a time-bounded native battery export allowance. Units: power/tick. */
public final class NativeExportBudget {
    public record Budget(float production,float demand,float export){}
    private NativeExportBudget(){}
    public static Budget read(PowerGraph graph,boolean allowStorage,float reserve,float horizonTicks){
        var r=PowerGraphMeasurements.read(graph);
        double p=(r.legacyPower()+r.controlledPower()+r.storagePower())/60d,n=r.demand()/60d;
        if(!Double.isFinite(p) || !Double.isFinite(n) || r.unknownSource())return new Budget(0,(float)Math.max(0,Double.isFinite(n)?n:0),0);
        double battery=0;
        if(allowStorage && p>=n && graph!=null){
            double stored=graph.getBatteryStored(),capacity=graph.getTotalBatteryCapacity();
            float floor=Float.isFinite(reserve)?Math.max(0,Math.min(1,reserve)):1;
            float ticks=Float.isFinite(horizonTicks)?Math.max(1,horizonTicks):Float.MAX_VALUE;
            if(Double.isFinite(stored) && Double.isFinite(capacity))battery=Math.max(0,stored-floor*capacity)/ticks;
        }
        return new Budget((float)p,(float)n,(float)(Math.max(0,p-n)+battery));
    }
}
