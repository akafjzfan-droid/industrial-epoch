package blackstart;

import mindustry.Vars;
import mindustry.gen.Building;
import java.util.IdentityHashMap;
import java.util.Locale;

/** Event-time evidence alongside the frozen ring schema. Bounded by the same 256 entry identities. */
public final class ProtectionExplanation {
    public record Detail(BlackstartEvents.Kind kind,int x,int y,int aux,float value,Building device,
                         int zoneAnchor,int zoneMembers,int island,long world,double threshold,
                         double duration,double recloseDelay,int recloseMax,String syncReason,
                         long cause){}   // cause = TIE_FAIL 原始因由字(M4);0/-1 = 无/未判定,其余 Kind 恒 0
    public record Text(String measured,String device,String affected,String recovery){}
    private static final IdentityHashMap<BlackstartEvents.Entry,Detail> details=new IdentityHashMap<>();
    private static long world;
    private ProtectionExplanation(){}
    public static void clear(){details.clear();world++;}
    public static Detail get(BlackstartEvents.Entry entry){return details.get(entry);}
    static void capture(BlackstartEvents.Entry entry){
        switch(entry.kind){
            case TRIP,RECLOSE,RECLOSE_FAIL,SHED,BREAKER_OPEN,BREAKER_CLOSE,TIE_FAIL,SYNC_REJECT -> {}
            default -> {details.remove(entry);return;}
        }
        Building b=Vars.world==null?null:Vars.world.build(entry.x,entry.y);
        int position=(entry.x<<16)|(entry.y&65535),anchor=position,count=0;
        if(b!=null && b.power!=null && b.power.graph!=null){
            for(Building member:b.power.graph.all)if(member!=null && member.tile!=null){anchor=Math.min(anchor,member.pos());count++;}
        }
        BreakerNode block=b!=null && b.block instanceof BreakerNode breaker?breaker:null;
        long cause=entry.kind==BlackstartEvents.Kind.TIE_FAIL?BlackstartProtection.tieCause(entry.x,entry.y):0L;
        String reason=entry.kind==BlackstartEvents.Kind.TIE_FAIL?BlackstartProtection.tieCauseText(cause):"";
        details.put(entry,new Detail(entry.kind,entry.x,entry.y,entry.aux,entry.value,b,anchor,count,
            b==null?-1:SafetyNet.islandAt(position),world,block==null?Double.NaN:block.tripLoadFrac,
            block==null?Double.NaN:block.tripDelaySec,block==null?Double.NaN:block.recloseDelaySec,
            block==null?0:block.recloseMax,reason,cause));
    }
    /**
     * 权威旁表注入(M4):联机客户端回放宿主事件段时调用(见 {@link BlackstartEvents#pushRemote})。
     * device 由宿主随帧携带的 block/team id 在本端世界按 (x,y) 解析;world 记本端当前代数,
     * 使 {@link #canLocate} 在本端成立;syncReason 留空、由 describe 从 cause 现推(宿主语言串不过网)。
     */
    static void applyRemote(BlackstartEvents.Entry entry,long cause,int deviceBlock,int deviceTeam,
                            int zoneAnchor,int zoneMembers,int island,double threshold,double duration,
                            double recloseDelay,int recloseMax){
        Building device=null;
        if(deviceBlock>=0 && deviceTeam>=0 && Vars.world!=null){
            Building b=Vars.world.build(entry.x,entry.y);
            if(b!=null && b.block!=null && b.team!=null && b.block.id==deviceBlock && b.team.id==deviceTeam) device=b;
        }
        details.put(entry,new Detail(entry.kind,entry.x,entry.y,entry.aux,entry.value,device,zoneAnchor,
            zoneMembers,island,world,threshold,duration,recloseDelay,recloseMax,"",cause));
    }
    /** 无旁表条目的回放路径:清掉 Entry 对象复用前的历史 Detail,防跨环串烧。 */
    static void removeRemote(BlackstartEvents.Entry entry){details.remove(entry);}
    public static boolean canLocate(Detail d){return d!=null && d.world==world && d.device!=null && Vars.world!=null && Vars.world.build(d.x,d.y)==d.device;}
    private static String text(String suffix,String fallback,Object...args){return String.format(Locale.ROOT,BlackstartText.get("blackstart.explain."+suffix,fallback),args);}
    public static Text describe(Detail d){
        if(d==null)return new Text("","","","");
        String measured=switch(d.kind){
            case TRIP -> text("trip","Load %.1f%% exceeded %.1f%% for %.1fs",d.value,d.threshold*100,d.duration);
            case SHED -> text("shed","Demand deficit %.1f%%; tier %d",d.value,d.aux);
            case TIE_FAIL -> {
                // 宿主路径 syncReason 已填;客户端回放路径 syncReason 为空 → 从 cause 现推(同源同参)。
                String reason=d.syncReason.isEmpty()?BlackstartProtection.tieCauseText(d.cause):d.syncReason;
                yield reason.isEmpty()?text("syncUnknown","Fresh synchronism observation required"):reason;
            }
            case SYNC_REJECT -> text("syncMeasured","Local frequency %s Hz; %s",Float.isFinite(d.value)?String.format(Locale.ROOT,"%.3f",d.value):"-",
                BlackstartText.get("blackstart.sync."+(d.aux>=0 && d.aux<SyncPolicy.Reason.values().length?SyncPolicy.Reason.values()[d.aux]:SyncPolicy.Reason.UNKNOWN).name().toLowerCase(Locale.ROOT),"UNKNOWN"));
            case RECLOSE -> text("reclosed","Cooldown ended; synchronism check passed");
            case RECLOSE_FAIL -> text("locked","Reclose failed or permitted attempt exhausted");
            default -> text("manual","Manual switch command");
        };
        String device=d.kind==BlackstartEvents.Kind.SHED?text("automatic","Automatic grid protection at (%d,%d)",d.x,d.y)
            :text("device","Device (%d,%d)",d.x,d.y);
        String affected=(d.kind==BlackstartEvents.Kind.SHED && d.aux==0) || d.kind==BlackstartEvents.Kind.TIE_FAIL
            || d.kind==BlackstartEvents.Kind.SYNC_REJECT?text("noCut","No additional zone disconnected by this event")
            :text("zone","Direct zone (%d,%d), %d buildings; alternate supply paths may remain",d.zoneAnchor>>16,d.zoneAnchor&65535,d.zoneMembers);
        String recovery=switch(d.kind){
            case SHED -> text("shedRestore","Sustain at least 5%% surplus for 5s; restored load must leave 2%% supply margin; wait for anti-flapping lockout if active");
            case TRIP -> text("tripRestore","Reduce link load; after %.1fs at most %d automatic reclose, subject to synchronism; if locked, close manually after correction",d.recloseDelay,d.recloseMax);
            case TIE_FAIL,SYNC_REJECT -> text("tieRestore","Correct the displayed cause; keep live islands within 49.7–50.3 Hz and their difference at most 0.3 Hz for 1s");
            case RECLOSE_FAIL -> text("lockRestore","Reduce load and correct synchronism, then close manually to reset the lockout");
            case BREAKER_OPEN -> text("manualRestore","Close manually after checking source capacity, load and synchronism");
            default -> text("restored","Path enabled; verify actual supply and watch for repeated overload");
        };
        return new Text(measured,device,affected,recovery);
    }
}
