package blackstart;

import mindustry.Vars;
import mindustry.gen.Building;
import java.util.HashMap;
import java.util.Map;

/** Only the authoritative update advances stable windows; all consumers read this cache. */
public final class BlackstartSync {
    private record Cached(Building first, Building second, long firstEpoch, long secondEpoch,
                          long sampledTick, SyncPolicy.Decision decision) {}
    private static final SyncPolicy policy=new SyncPolicy();
    private static final Map<Long,Cached> decisions=new HashMap<>();
    public record PairDecision(int first,int second,SyncPolicy.Decision decision){}
    static java.util.List<PairDecision> observations(){
        java.util.List<PairDecision> rows=new java.util.ArrayList<>();
        for(Cached c:decisions.values())rows.add(new PairDecision(c.first.pos(),c.second.pos(),pair(c.first.pos(),c.second.pos())));
        rows.sort(java.util.Comparator.comparingInt(PairDecision::first).thenComparingInt(PairDecision::second));return rows;
    }
    private BlackstartSync(){}
    public static void clear(){decisions.clear();policy.clear();}
    private static long key(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    private static long tick(){return Vars.state==null?0:(long)Vars.state.tick;}
    public static void refresh(){
        if(Vars.net!=null && Vars.net.client())return;
        decisions.clear();
        if(Vars.world==null)return;
        long now=tick();
        for(var gateway:BlackstartLinks.registry){
            if(gateway==null || gateway.tile==null || Vars.world.build(gateway.pos())!=gateway)continue;
            for(int i=0;i<gateway.modLinks.size;i++){
                Building other=Vars.world.build(gateway.modLinks.get(i));
                if(!(other instanceof GatewayNode.GatewayNodeBuild) || other.tile==null || !gateway.modLinkValid(other.pos(),gateway.team))continue;
                int a=Math.min(gateway.pos(),other.pos()),b=Math.max(gateway.pos(),other.pos());
                long key=key(a,b);if(decisions.containsKey(key))continue;
                var first=BlackstartDynamics.snapshotAt(a);var second=BlackstartDynamics.snapshotAt(b);
                var decision=policy.observe(first==null?null:first.sync(),second==null?null:second.sync(),now);
                decisions.put(key,new Cached(Vars.world.build(a),Vars.world.build(b),first==null?0:first.epoch(),second==null?0:second.epoch(),now,decision));
            }
        }
    }
    public static SyncPolicy.Decision pair(int a,int b){
        if(Vars.net!=null && Vars.net.client())return BlackstartTelemetry.pair(a,b,BlackstartTelemetry.now());
        Cached cached=decisions.get(key(a,b));long now=tick();
        if(cached==null || Vars.world==null || now<cached.sampledTick || now-cached.sampledTick>SyncPolicy.MAX_SAMPLE_AGE_TICKS)return unknown();
        int first=Math.min(a,b),second=Math.max(a,b);
        if(Vars.world.build(first)!=cached.first || Vars.world.build(second)!=cached.second)return unknown();
        var sa=BlackstartDynamics.snapshotAt(first);var sb=BlackstartDynamics.snapshotAt(second);
        if(sa==null || sb==null || sa.epoch()!=cached.firstEpoch || sb.epoch()!=cached.secondEpoch)return unknown();
        return cached.decision;
    }
    private static SyncPolicy.Decision unknown(){return new SyncPolicy.Decision(false,SyncPolicy.Reason.UNKNOWN,Double.NaN,SyncPolicy.HOLD_TICKS);}
}
