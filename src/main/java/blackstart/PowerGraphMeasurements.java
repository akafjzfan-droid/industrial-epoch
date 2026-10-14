package blackstart;

import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.sandbox.PowerSource;
import java.util.IdentityHashMap;

/** Intrinsic power measurements: gateway transport is a separate signed term, never a source. */
public final class PowerGraphMeasurements {
    public record Reading(double demand,double legacyPower,double controlledPower,double storagePower,
                          double linkNetDraw,double batteryNet,double referenceBase,boolean unknownSource) {}
    private static final IdentityHashMap<PowerGraph,Double> lastBatteryStored=new IdentityHashMap<>();
    private static final IdentityHashMap<PowerGraph,Long> lastBatteryTick=new IdentityHashMap<>();
    private PowerGraphMeasurements() {}
    public static Reading read(PowerGraph graph){
        return BlackstartFusionPolicy.active()?readStandard(graph):readCompat(graph);
    }

    private static Reading readStandard(PowerGraph graph){
        double demand=0,legacy=0,controlled=0,storage=0,linkNet=0,base=0;boolean unknown=false;
        if(graph==null)return new Reading(0,0,0,0,0,0,0,true);
        for(Building b:graph.all){
            if(b==null || b.block==null)continue;
            double timeScale=b.timeScale();
            if(!validMagnitude(timeScale)){unknown=true;continue;}
            double scale=60d*timeScale;
            if(b instanceof GatewayNode.GatewayNodeBuild gateway){
                double draw=gateway.linkDrawCmd,injection=gateway.linkInjCmd;
                if(!validMagnitude(draw) || !validMagnitude(injection)){unknown=true;continue;}
                linkNet+=(draw-injection)*scale;
                continue;
            }
            double request=0;
            if(b.shouldConsumePower && b.block.consPower!=null && !b.block.consPower.buffered)
                request=b.block.consPower.requestedPower(b);
            double production=b.getPowerProduction();
            // Math.max is not validation: NaN survives clamping, and NaN > 0 is false.
            // Reject the whole device observation before any of its terms are accumulated.
            if(!validMagnitude(request) || !validMagnitude(production)){unknown=true;continue;}
            double output=production*scale;
            demand+=request*scale;
            if(b instanceof ControlledGenerator.ControlledGeneratorBuild machine){
                controlled+=output;
                if(b.enabled && machine.machine().running()){
                    double rating=((ControlledGenerator)b.block).ratedPower;
                    if(validMagnitude(rating))base+=rating;else unknown=true;
                }
            }else if(b instanceof ControlledStorage.ControlledStorageBuild battery){
                storage+=output;
                if(battery.referenceReady()){
                    double rating=((ControlledStorage)b.block).dischargeLimit;
                    if(validMagnitude(rating))base+=rating;else unknown=true;
                }
            }else if(output>0){
                if((b.block instanceof PowerGenerator || b.block instanceof PowerSource) && b.block.minfo.mod==null)
                    legacy+=output;
                else unknown=true;
            }
        }
        // Also contain aggregate overflow; none of the numeric channels can carry NaN/Inf.
        if(!Double.isFinite(demand) || !Double.isFinite(legacy) || !Double.isFinite(controlled)
            || !Double.isFinite(storage) || !Double.isFinite(linkNet) || !Double.isFinite(base))unknown=true;
        return new Reading(finiteOrZero(demand),finiteOrZero(legacy),finiteOrZero(controlled),
            finiteOrZero(storage),finiteOrZero(linkNet),0,finiteOrZero(base),unknown);
    }

    private static boolean validMagnitude(double value){return Double.isFinite(value) && value>=0;}
    private static double finiteOrZero(double value){return Double.isFinite(value)?value:0;}

    /** Historical ideal-reference path, including its original clamping semantics. */
    private static Reading readCompat(PowerGraph graph){
        double demand=0,legacy=0,controlled=0,storage=0,linkNet=0,base=0;boolean unknown=false;
        if(graph==null)return new Reading(0,0,0,0,0,0,0,true);
        for(Building b:graph.all){
            if(b==null || b.block==null)continue;
            double scale=60d*Math.max(0,b.timeScale());
            if(b instanceof GatewayNode.GatewayNodeBuild gateway){
                linkNet+=(gateway.linkDrawCmd-gateway.linkInjCmd)*scale;
                continue;
            }
            if(b.shouldConsumePower && b.block.consPower!=null && !b.block.consPower.buffered)
                demand+=Math.max(0,b.block.consPower.requestedPower(b))*scale;
            double output=Math.max(0,b.getPowerProduction())*scale;
            if(b instanceof ControlledGenerator.ControlledGeneratorBuild machine){
                controlled+=output;
                if(b.enabled && machine.machine().running())base+=((ControlledGenerator)b.block).ratedPower;
            }else if(b instanceof ControlledStorage.ControlledStorageBuild battery){
                storage+=output;
                if(battery.referenceReady())base+=((ControlledStorage)b.block).dischargeLimit;
            }else if(output>0){
                if((b.block instanceof PowerGenerator || b.block instanceof PowerSource) && b.block.minfo.mod==null)
                    legacy+=output;
                else unknown=true;
            }
        }
        return new Reading(demand,legacy,controlled,storage,linkNet,0,base,unknown);
    }

    /**
     * D-36(a) 混合能量账本:原版 buffered 电池桶的净充放功率(正=充电、负=放电),
     * 以两次采样间实际游戏时间差分(帧率无关;同一游戏 tick 内重复调用自然为 0)。
     * 只读引擎账本({@code getBatteryStored()}),不移动任何能量。
     */
    public static double batteryNet(PowerGraph graph,long tick){
        if(graph==null)return 0;
        double stored=graph.getBatteryStored();
        Double prev=lastBatteryStored.put(graph,stored);
        Long prevTick=lastBatteryTick.put(graph,tick);
        if(prev==null || prevTick==null)return 0;
        double seconds=(tick-prevTick)/60d;
        if(!(seconds>0))return 0;
        return (stored-prev)/seconds;
    }

    public static void clearBatteryLedger(){
        lastBatteryStored.clear();
        lastBatteryTick.clear();
    }

    /** 丢弃已解体/空的图的基线,防长局账本无界增长。 */
    public static void pruneBatteryLedger(){
        lastBatteryStored.keySet().removeIf(g->g.all.size==0);
        lastBatteryTick.keySet().removeIf(g->g.all.size==0);
    }
}
