package blackstart;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGraph;
import java.util.*;

/** Event-maintained native power participants. Never changes graphs or battery charge. */
public final class NativeGridRegistry {
    private static final TreeMap<Integer,Building> nodes=new TreeMap<>();
    private static final Collection<Building> view=Collections.unmodifiableCollection(nodes.values());
    private static final IdentityHashMap<Building,BatterySample> batteries=new IdentityHashMap<>();
    private static boolean installed;
    private static final class BatterySample { double stored,tick,power; BatterySample(double e,double t){stored=e;tick=t;} }
    private NativeGridRegistry(){}
    public static void install(){
        if(installed)return;installed=true;
        Events.on(EventType.ResetEvent.class,e->clear());
        Events.on(EventType.WorldLoadEvent.class,e->refreshWorld());
        Events.on(EventType.TileChangeEvent.class,e->{if(e.tile!=null)register(e.tile.build);});
        Events.on(EventType.BlockBuildEndEvent.class,e->{if(e.tile!=null)register(e.tile.build);});
    }
    public static void clear(){nodes.clear();batteries.clear();}
    public static void refreshWorld(){
        clear();if(Vars.world==null || Vars.world.tiles==null)return;
        for(var tile:Vars.world.tiles)if(tile.isCenter())register(tile.build);
    }
    public static void register(Building b){
        if(b!=null && b.tile!=null && b.block!=null && b.power!=null && b.block.minfo.mod==null
            && !(b instanceof GatewayNode.GatewayNodeBuild)
            && !(b instanceof ControlledGenerator.ControlledGeneratorBuild)
            && !(b instanceof ControlledStorage.ControlledStorageBuild))nodes.put(b.pos(),b);
    }
    private static boolean live(Building b){return Vars.world!=null && !b.dead && b.tile!=null && b.power!=null
        && b.power.graph!=null && Vars.world.build(b.pos())==b;}
    public static Collection<Building> nodes(){
        nodes.values().removeIf(b->!live(b));
        return view;
    }
    /** Positive = charging. Per-building identity avoids false discharge when a graph splits/merges. */
    static void sampleBatteries(){
        double tick=Vars.state==null?0:Vars.state.tick;
        batteries.keySet().removeIf(b->!live(b) || !b.enabled);
        for(Building b:nodes()){
            if(!b.enabled || b.block.consPower==null || !b.block.consPower.buffered)continue;
            double stored=(double)b.power.status*b.block.consPower.capacity;
            if(!Double.isFinite(stored) || stored<0){batteries.remove(b);continue;}
            BatterySample s=batteries.get(b);
            if(s==null || tick<s.tick){batteries.put(b,new BatterySample(stored,tick));continue;}
            if(tick==s.tick)continue;
            s.power=(stored-s.stored)*60d/(tick-s.tick);s.stored=stored;s.tick=tick;
        }
    }
    static double batteryNet(PowerGraph graph){
        double result=0;
        for(Building b:graph.batteries){BatterySample s=batteries.get(b);if(s!=null && b.enabled && b.power!=null && b.power.graph==graph)result+=s.power;}
        return result;
    }
}
