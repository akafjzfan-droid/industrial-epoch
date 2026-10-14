package blackstart;

import arc.Events;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGraph;
import java.util.*;

/** Server-authoritative bridge between physical device commands and immutable island observations. */
public final class BlackstartDynamics {
    public enum ReferenceKind { DYNAMIC, LEGACY_COMPAT, NONE, EQUIVALENT }
    public record IslandDynamicsSnapshot(int island,long epoch,long sampleTick,SyncPolicy.ReferenceState reference,
                                         ReferenceKind kind,double frequencyHz,double availablePower,
                                         double demand,double actualPower) {
        public SyncPolicy.Snapshot sync(){return new SyncPolicy.Snapshot(island,epoch,sampleTick,reference,frequencyHz,availablePower);}
    }
    private static final List<Building> devices=new ArrayList<>();
    private static final IdentityHashMap<PowerGraph,IslandDynamicsSnapshot> byGraph=new IdentityHashMap<>();
    private static final Map<Integer,IslandDynamicsSnapshot> priorMembers=new HashMap<>();
    private static final IdentityHashMap<ControlledGenerator.ControlledGeneratorBuild,Double> setpoints=new IdentityHashMap<>();
    private static final IdentityHashMap<Building,Long> incarnation=new IdentityHashMap<>();
    private static long nextIncarnation;
    private static final double CONTROL_SECONDS=1d/60;
    private static final IdentityHashMap<Building,double[]> plans=new IdentityHashMap<>();
    private static double controlRemaining;
    private static boolean installed;
    private static long worldEpoch;
    private static boolean lastFusion;
    private BlackstartDynamics(){}
    public static void register(Building building){if(!devices.contains(building))devices.add(building);}
    public static void unregister(Building building){devices.remove(building);setpoints.remove(building);}
    public static void clear(){devices.clear();byGraph.clear();priorMembers.clear();setpoints.clear();incarnation.clear();plans.clear();controlRemaining=0;nextIncarnation=0;worldEpoch++;PowerGraphMeasurements.clearBatteryLedger();BlackstartSync.clear();}
    public static IslandDynamicsSnapshot snapshotAt(int packedPosition){
        if(Vars.net!=null && Vars.net.client())return BlackstartTelemetry.snapshotAt(packedPosition,BlackstartTelemetry.now());
        Building b=Vars.world==null?null:Vars.world.build(packedPosition);
        return b==null || b.power==null?null:byGraph.get(b.power.graph);
    }
    static List<Building> observationNodes(){
        TreeMap<Integer,Building> nodes=new TreeMap<>();
        for(Building b:devices)if(live(b))nodes.put(b.pos(),b);
        for(var b:BlackstartLinks.registry)if(live(b))nodes.put(b.pos(),b);
        if(BlackstartFusionPolicy.active()){
            Set<PowerGraph> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            for(Building b:NativeGridRegistry.nodes())if(seen.add(b.power.graph))nodes.put(b.pos(),b);
        }
        return new ArrayList<>(nodes.values());
    }
    public static void install(){
        if(installed)return;installed=true;
        BlackstartContinuity.install();
        NativeGridRegistry.install();
        BlackstartTraining.install();
        BlackstartTelemetry.install();
        Events.on(EventType.ResetEvent.class,e->clear());
        Events.on(EventType.WorldLoadEvent.class,e->{
            clear();
            if(Vars.world!=null)for(var tile:Vars.world.tiles){
                Building b=tile.build;
                if(b!=null && tile.isCenter() && (b instanceof ControlledGenerator.ControlledGeneratorBuild || b instanceof ControlledStorage.ControlledStorageBuild))register(b);
            }
        });
        Events.run(EventType.Trigger.beforeGameUpdate,()->{if(authority()){
            prepare(Time.delta/60d);
            // Budget the current paid commands before the native engine consumes them.
            if(BlackstartFusionPolicy.active())BlackstartLinks.tick();
        }});
        Events.run(EventType.Trigger.afterGameUpdate,()->{if(authority())finish(Time.delta/60d);});
    }
    private static boolean authority(){return Vars.state!=null && Vars.state.isPlaying() && !Vars.state.isEditor() && (Vars.net==null || !Vars.net.client());}
    private static boolean live(Building b){return b!=null && b.tile!=null && !b.dead && Vars.world!=null && Vars.world.build(b.pos())==b && b.power!=null && b.power.graph!=null;}

    public static void prepare(double seconds){
        DynamicsModel.nonnegative(seconds,"seconds");if(seconds==0 || (Vars.net!=null && Vars.net.client()))return;
        BlackstartContinuity.beforeUpdate();
        boolean fusion=BlackstartFusionPolicy.active();
        if(fusion!=lastFusion){byGraph.clear();priorMembers.clear();controlRemaining=0;plans.clear();lastFusion=fusion;}
        if(fusion)NativeGridRegistry.sampleBatteries();
        devices.removeIf(b->!live(b));setpoints.keySet().removeIf(b->!devices.contains(b));
        incarnation.keySet().removeIf(b->!live(b));
        plans.keySet().removeIf(b->!live(b));
        for(Building b:devices){
            double[] plan=plans.get(b);if(plan==null)continue;
            if(b instanceof ControlledGenerator.ControlledGeneratorBuild gen)gen.publishFrameOutput(plan[0]);
            else if(b instanceof ControlledStorage.ControlledStorageBuild storage)storage.publishFrameOutput(plan[0],plan[1],CONTROL_SECONDS);
        }
        devices.sort(Comparator.comparingInt(Building::pos));
        List<PowerGraph> graphs=new ArrayList<>();
        for(Building b:devices)if(!graphs.contains(b.power.graph))graphs.add(b.power.graph);
        for(var gw:BlackstartLinks.registry)if(live(gw) && !graphs.contains(gw.power.graph))graphs.add(gw.power.graph);
        if(fusion){
            Set<PowerGraph> seen=Collections.newSetFromMap(new IdentityHashMap<>());seen.addAll(graphs);
            for(Building b:NativeGridRegistry.nodes())if(seen.add(b.power.graph))graphs.add(b.power.graph);
        }
        graphs.sort(Comparator.comparingInt(BlackstartDynamics::anchor));
        int[] parent=new int[graphs.size()];for(int i=0;i<parent.length;i++)parent[i]=i;
        for(var gw:BlackstartLinks.registry){
            if(!live(gw))continue;
            for(int i=0;i<gw.modLinks.size;i++){
                Building peer=Vars.world.build(gw.modLinks.get(i));
                // T2 treats non-gateway links as records only; they do not carry power.
                if(!(peer instanceof GatewayNode.GatewayNodeBuild) || !live(peer) || !gw.modLinkValid(peer.pos(),gw.team))continue;
                if(i<gw.linkOpen.length && !gw.linkOpen[i] || !BlackstartProtection.linkGateOpen(gw,peer))continue;
                // A zero import grant removes this source of reference from the receiving zone.
                // SafetyNet's own island membership and frozen rAll bookkeeping are unchanged.
                if(i<gw.linkGrant.length && !(gw.linkGrant[i]>0))continue;
                int a=graphs.indexOf(gw.power.graph),b=graphs.indexOf(peer.power.graph);
                if(a>=0 && b>=0)parent[root(parent,b)]=root(parent,a);
            }
        }
        Map<Integer,List<PowerGraph>> groups=new TreeMap<>();
        for(int i=0;i<graphs.size();i++)groups.computeIfAbsent(root(parent,i),k->new ArrayList<>()).add(graphs.get(i));
        // A control step reserves its electrical energy before any part is advertised to the engine.
        // Fractional rendering frames share that paid command; long frames receive its time average.
        IdentityHashMap<Building,double[]> delivered=new IdentityHashMap<>();
        double remaining=seconds;
        while(remaining>1e-12){
            if(controlRemaining<=1e-12){
                byGraph.clear();Map<Integer,IslandDynamicsSnapshot> nextMembers=new HashMap<>();
                for(List<PowerGraph> group:groups.values())stepIsland(group,CONTROL_SECONDS,nextMembers);
                priorMembers.clear();priorMembers.putAll(nextMembers);
                for(Building b:devices)plans.put(b,new double[]{b.getPowerProduction()*60d,b instanceof ControlledStorage.ControlledStorageBuild s?s.chargeRequest():0});
                controlRemaining=CONTROL_SECONDS;
            }
            double slice=Math.min(remaining,controlRemaining);
            for(Building b:devices){
                double[] plan=plans.get(b);if(plan==null)continue;
                double[] amount=delivered.computeIfAbsent(b,k->new double[2]);amount[0]+=plan[0]*slice;amount[1]+=plan[1]*slice;
            }
            remaining-=slice;controlRemaining-=slice;
        }
        for(Building b:devices){
            double[] amount=delivered.getOrDefault(b,new double[2]);
            if(b instanceof ControlledGenerator.ControlledGeneratorBuild gen)gen.publishFrameOutput(amount[0]/seconds);
            else if(b instanceof ControlledStorage.ControlledStorageBuild storage)storage.publishFrameOutput(amount[0]/seconds,amount[1]/seconds,seconds);
        }
        BlackstartSync.refresh();
    }
    private static void stepIsland(List<PowerGraph> graphs,double seconds,Map<Integer,IslandDynamicsSnapshot> next){
        List<Building> members=new ArrayList<>();
        for(PowerGraph graph:graphs)for(Building b:graph.all)if(b!=null && b.tile!=null)members.add(b);
        members.sort(Comparator.comparingInt(Building::pos));
        if(members.isEmpty())return;
        // D-36(a): 原版 buffered 电池净充放按游戏时间差分,逐控制步并入本岛能量平衡(唯一采样点)。
        long tick=Vars.state==null?0:(long)Vars.state.tick;
        double batteryNet=0;
        boolean fusion=BlackstartFusionPolicy.active();
        for(PowerGraph graph:graphs)batteryNet+=fusion?NativeGridRegistry.batteryNet(graph):PowerGraphMeasurements.batteryNet(graph,tick);
        PowerGraphMeasurements.pruneBatteryLedger();
        int id=members.get(0).pos();long epoch=worldEpoch+0xcbf29ce484222325L;
        for(Building b:members){
            long birth=incarnation.computeIfAbsent(b,k->++nextIncarnation);
            epoch=(epoch^b.pos())*0x100000001b3L;epoch=(epoch^birth)*0x100000001b3L;
        }
        double oldFrequency=50,weighted=0,totalWeight=0;
        for(Building b:members){
            IslandDynamicsSnapshot old=priorMembers.get(b.pos());
            if(old!=null && Double.isFinite(old.frequencyHz)){
                double weight=b.block instanceof ControlledGenerator g?g.ratedPower:b.block instanceof ControlledStorage s && s.forming?s.dischargeLimit:0;
                if(fusion && b.block.minfo.mod==null && b.block instanceof mindustry.world.blocks.power.PowerGenerator g)weight=Math.max(1,g.powerProduction*60d);
                if(fusion && b.block instanceof mindustry.world.blocks.sandbox.PowerSource)weight=1;
                weighted+=old.frequencyHz*weight;totalWeight+=weight;
            }
        }
        if(totalWeight>0)oldFrequency=weighted/totalWeight;
        PowerGraphMeasurements.Reading before=sum(graphs,batteryNet);
        boolean reference=before.legacyPower()>0 || before.referenceBase()>0;
        double deficit=Math.max(0,before.demand()+Math.max(0,before.linkNetDraw())-before.legacyPower()-before.controlledPower()+(fusion?before.batteryNet():-before.batteryNet()));
        for(Building b:members)if(b instanceof ControlledStorage.ControlledStorageBuild storage){
            storage.prepareFrame(seconds,reference,deficit);
            deficit=Math.max(0,deficit-storage.getPowerProduction()*60d);
        }
        boolean nativeMixed=fusion && members.stream().anyMatch(b->b.block.minfo.mod==null &&
            (b.block instanceof mindustry.world.blocks.power.PowerGenerator || b.block instanceof mindustry.world.blocks.sandbox.PowerSource));
        double automaticRating=0,manualPower=0;
        for(Building b:members)if(b instanceof ControlledGenerator.ControlledGeneratorBuild gen && gen.machine().running()){
            if(gen.dispatchPermille()<0)automaticRating+=((ControlledGenerator)b.block).ratedPower;
            else manualPower+=((ControlledGenerator)b.block).ratedPower*gen.dispatchPermille()/1000d;
        }
        var dispatchReading=sum(graphs,batteryNet);
        double residual=Math.max(0,dispatchReading.demand()+Math.max(0,dispatchReading.linkNetDraw())-
            dispatchReading.legacyPower()-dispatchReading.storagePower()-manualPower);
        for(Building b:members)if(b instanceof ControlledGenerator.ControlledGeneratorBuild generator){
            ControlledGenerator block=(ControlledGenerator)b.block;
            boolean wasRunning=generator.machine().running();
            double scheduled=generator.dispatchPermille()<0?setpoints.getOrDefault(generator,0d):block.ratedPower*generator.dispatchPermille()/1000d;
            if(nativeMixed && generator.dispatchPermille()<0)scheduled=residual*block.ratedPower/Math.max(block.ratedPower,automaticRating);
            double target=scheduled-block.ratedPower*((oldFrequency-50)/50)/0.05;
            generator.prepareFrame(seconds,target);
            if(!wasRunning && generator.machine().running()){
                double totalRating=0;
                for(Building other:members)if(other instanceof ControlledGenerator.ControlledGeneratorBuild gen && gen.machine().running())totalRating+=((ControlledGenerator)other.block).ratedPower;
                setpoints.put(generator,before.demand()*block.ratedPower/Math.max(block.ratedPower,totalRating));
            }else if(!generator.machine().running())setpoints.remove(generator);
        }
        // D-36(e): 有机组在 STARTING 且本岛尚无参考时,发布启动态而非 UNENERGIZED(激活 SyncPolicy 既有 STARTING 拒绝分支)。
        boolean starting=false;
        for(Building b:members)
            if(b instanceof ControlledGenerator.ControlledGeneratorBuild machine && machine.machine().phase()==MachineModel.Phase.STARTING){starting=true;break;}
        PowerGraphMeasurements.Reading reading=sum(graphs,batteryNet);
        // D-36(b): 构网储能预付最后一拍后 E=0,该拍它仍在支撑本岛 —— 不得在同一拍撤参考。
        double referenceBase=Math.max(before.referenceBase(),reading.referenceBase());
        ReferenceKind kind=reading.legacyPower()>0?(fusion?ReferenceKind.EQUIVALENT:ReferenceKind.LEGACY_COMPAT):referenceBase>0?ReferenceKind.DYNAMIC:ReferenceKind.NONE;
        double f=Double.NaN;
        SyncPolicy.ReferenceState state=SyncPolicy.ReferenceState.UNENERGIZED;
        if(fusion && reading.unknownSource()){kind=ReferenceKind.NONE;state=SyncPolicy.ReferenceState.UNKNOWN;}
        else if(kind==ReferenceKind.LEGACY_COMPAT){f=50;state=SyncPolicy.ReferenceState.LIVE;}
        else if(kind==ReferenceKind.EQUIVALENT){
            // Count supply where it actually arrives. An upstream surplus cannot mask
            // a downstream shortage behind a capacity-limited transport link.
            double served=0;
            for(PowerGraph graph:graphs){
                var local=PowerGraphMeasurements.read(graph);
                double localSupply=local.legacyPower()+local.controlledPower()+local.storagePower()-
                    local.linkNetDraw()-NativeGridRegistry.batteryNet(graph);
                served+=Math.min(local.demand(),Math.max(0,localSupply));
            }
            f=EquivalentGridModel.frequencyStep(oldFrequency,served,reading.demand(),0,seconds);
            state=SyncPolicy.ReferenceState.LIVE;
        }
        else if(kind==ReferenceKind.DYNAMIC){
            f=DynamicsModel.frequencyStep(oldFrequency,reading.controlledPower()+reading.storagePower()-reading.demand()-Math.max(0,reading.linkNetDraw())-reading.batteryNet(),referenceBase,4,seconds);
            state=Double.isFinite(f) && f>0?SyncPolicy.ReferenceState.LIVE:SyncPolicy.ReferenceState.UNKNOWN;
        }else if(reading.unknownSource())state=SyncPolicy.ReferenceState.UNKNOWN;
        else if(starting)state=SyncPolicy.ReferenceState.STARTING;
        var snapshot=new IslandDynamicsSnapshot(id,epoch,tick,state,kind,f,reading.legacyPower()+referenceBase,reading.demand(),reading.legacyPower()+reading.controlledPower()+reading.storagePower());
        for(PowerGraph graph:graphs)byGraph.put(graph,snapshot);
        for(Building b:members)next.put(b.pos(),snapshot);
    }
    private static PowerGraphMeasurements.Reading sum(List<PowerGraph> graphs,double batteryNet){
        double demand=0,legacy=0,controlled=0,storage=0,loss=0,base=0;boolean unknown=false;
        for(PowerGraph graph:graphs){var r=PowerGraphMeasurements.read(graph);demand+=r.demand();legacy+=r.legacyPower();controlled+=r.controlledPower();storage+=r.storagePower();loss+=r.linkNetDraw();base+=r.referenceBase();unknown|=r.unknownSource();}
        return new PowerGraphMeasurements.Reading(demand,legacy,controlled,storage,loss,batteryNet,base,unknown);
    }
    public static void finish(double seconds){
        if(Vars.net!=null && Vars.net.client())return;
        for(Building b:devices)if(live(b) && b instanceof ControlledStorage.ControlledStorageBuild storage)storage.finishFrame(seconds);
        BlackstartContinuity.afterUpdate(seconds*60d);
    }
    static double savedSetpoint(ControlledGenerator.ControlledGeneratorBuild b){return setpoints.getOrDefault(b,0d);}
    static void restoreDynamics(Building b,double frequency,double setpoint){
        lastFusion=BlackstartFusionPolicy.active();
        if(Double.isFinite(frequency) && frequency>0 && frequency<=100)
            priorMembers.put(b.pos(),new IslandDynamicsSnapshot(b.pos(),0,0,SyncPolicy.ReferenceState.LIVE,
                ReferenceKind.DYNAMIC,frequency,0,0,0));
        if(b instanceof ControlledGenerator.ControlledGeneratorBuild gen && gen.machine().running()
            && Double.isFinite(setpoint) && setpoint>=0 && setpoint<=1e12)setpoints.put(gen,setpoint);
    }
    private static int root(int[] parent,int i){while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i];}return i;}
    private static int anchor(PowerGraph graph){int min=Integer.MAX_VALUE;for(Building b:graph.all)if(b!=null && b.tile!=null)min=Math.min(min,b.pos());return min;}
}
