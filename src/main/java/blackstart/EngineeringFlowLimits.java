package blackstart;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import java.util.Objects;
import java.util.TreeMap;

/** Host-owned per-line manual capacity, independent of protection grants and building serialization. */
public final class EngineeringFlowLimits {
    public static final String KEY="blackstart.flowlimits.v1";
    private static final int MAX_EDGES=1024,MAX_TEXT=60000,MAX_RULE_BYTES=60000;
    private record Entry(Building owner,Building peer,int team,String ownerBlock,String peerBlock,int percent){
        boolean live(){return valid(owner,peer)&&owner.team.id==team&&owner.block.name.equals(ownerBlock)&&peer.block.name.equals(peerBlock);}
    }
    private static final TreeMap<Long,Entry> entries=new TreeMap<>();
    private static String raw;
    private static boolean installed,dirty;
    private EngineeringFlowLimits(){}
    private static long key(Building a,Building b){return ((long)a.pos()<<32)|(b.pos()&0xffffffffL);}
    private static boolean host(){return Vars.net==null || !Vars.net.client();}
    private static boolean live(Building b){return b!=null&&!b.dead&&b.tile!=null&&b.block!=null&&b.team!=null&&b.power!=null&&Vars.world!=null&&Vars.world.build(b.pos())==b;}
    static boolean valid(Building a,Building b){return a instanceof GatewayNode.GatewayNodeBuild owner&&b instanceof GatewayNode.GatewayNodeBuild
        &&live(a)&&live(b)&&a!=b&&a.team==b.team&&owner.modLinks.contains(b.pos())&&owner.modLinkValid(b.pos(),a.team);}
    public static boolean canManage(Building a,Building b){return valid(a,b)&&host()&&Vars.state!=null&&Vars.state.isGame()&&!Vars.state.isEditor()
        &&(Vars.player==null||Vars.player.team()==a.team);}
    public static void install(){
        if(installed)return;installed=true;
        Events.on(EventType.ResetEvent.class,e->clear());Events.on(EventType.WorldLoadEvent.class,e->{clear();refresh();});
        // SaveWriteEvent precedes SaveVersion metadata: prune invalid incoming endpoints
        // even when a paused replacement is saved before the next simulation/update tick.
        Events.on(EventType.SaveWriteEvent.class,e->tick());
        Events.run(EventType.Trigger.update,EngineeringFlowLimits::tick);
    }
    public static void clear(){entries.clear();raw=null;dirty=false;}
    public static void refresh(){
        String value=BlackstartParams.get(KEY,"");if(Objects.equals(value,raw))return;
        var previous=new TreeMap<>(entries);raw=value;entries.clear();
        if(value.isEmpty()||value.length()>MAX_TEXT||!value.startsWith("1\n"))return;
        String[] rows=value.split("\n");if(rows.length>MAX_EDGES+1)return;
        for(int i=1;i<rows.length;i++)try{
            String[] f=rows[i].split(",",-1);if(f.length!=6)continue;
            Building a=Vars.world==null?null:Vars.world.build(Integer.parseInt(f[0])),b=Vars.world==null?null:Vars.world.build(Integer.parseInt(f[1]));
            int team=Integer.parseInt(f[2]),p=Integer.parseInt(f[5]);
            if(!valid(a,b)||a.team.id!=team||!a.block.name.equals(f[3])||!b.block.name.equals(f[4])||p<0||p>=100)continue;
            Entry old=previous.get(key(a,b));if(old!=null&&(old.owner!=a||old.peer!=b||!old.live())){dirty=true;continue;}
            entries.put(key(a,b),new Entry(a,b,team,f[3],f[4],p));
        }catch(IllegalArgumentException ignored){ /* Malformed rows cannot limit unrelated buildings. */ }
    }
    public static int percent(Building a,Building b){
        refresh();if(!valid(a,b))return 100;Entry e=entries.get(key(a,b));
        return e!=null&&e.owner==a&&e.peer==b&&e.live()?e.percent:100;
    }
    static float fraction(Building a,Building b){return percent(a,b)/100f;}
    public static boolean set(Building a,Building b,int percent){
        if(percent<0||percent>100||!canManage(a,b))return false;refresh();
        // Work on a candidate. Failed validation/writes never mutate the runtime limit.
        var candidate=new TreeMap<Long,Entry>();entries.forEach((k,v)->{if(v.live())candidate.put(k,v);});
        long k=key(a,b);if(percent==100)candidate.remove(k);else candidate.put(k,new Entry(a,b,a.team.id,a.block.name,b.block.name,percent));
        if(!persist(candidate))return false;entries.clear();entries.putAll(candidate);dirty=false;BlackstartLinks.markDirty();return true;
    }
    /** Explicit line removal invalidates an entry even when immediately reconnected while paused. */
    static void removed(GatewayNode.GatewayNodeBuild owner,int peer){
        long k=((long)owner.pos()<<32)|(peer&0xffffffffL);Entry e=entries.get(k);
        if(e!=null&&e.owner==owner){entries.remove(k);dirty=true;
            if(host()&&Vars.state!=null&&Vars.state.isGame()&&!Vars.state.isEditor()&&persist(entries))dirty=false;
        }
    }
    public static void tick(){
        if(Vars.state==null||!Vars.state.isGame()||Vars.state.isEditor())return;refresh();
        dirty|=entries.values().removeIf(e->!e.live());if(dirty&&host()&&persist(entries))dirty=false;
    }
    private static boolean persist(TreeMap<Long,Entry> candidate){
        if(!host()||Vars.state==null||Vars.state.rules==null||candidate.size()>MAX_EDGES)return false;
        StringBuilder text=new StringBuilder("1\n");
        for(Entry e:candidate.values())if(e.live())text.append(e.owner.pos()).append(',').append(e.peer.pos()).append(',').append(e.team).append(',')
            .append(e.ownerBlock).append(',').append(e.peerBlock).append(',').append(e.percent).append('\n');
        String value=text.toString();if(value.length()>MAX_TEXT)return false;
        if(value.equals(BlackstartParams.get(KEY,"")))return true;
        var rules=Vars.state.rules.copy();rules.tags.put(KEY,value);String json=mindustry.io.JsonIO.write(rules);long bytes=0;
        for(int i=0;i<json.length();i++){char c=json.charAt(i);bytes+=c>=1&&c<=127?1:c<=2047?2:3;if(bytes>MAX_RULE_BYTES)return false;}
        if(!BlackstartParams.set(KEY,value))return false;raw=value;return true;
    }
}
