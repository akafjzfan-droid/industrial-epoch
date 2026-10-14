package blackstart;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.world.blocks.power.PowerGraph;
import java.io.*;
import java.util.*;

/**
 * Host-produced observation stream. Never invokes building read(), changes native graphs, or serializes saves.
 *
 * M4:帧尾追加【事件段】——宿主 seq 水位 + 最新 ≤{@link #MAX_EVENTS} 条事件环条目(新→旧,含旁表随帧)。
 * 客户端 apply 全量严格解码后,在状态 commit【之后】按水位增量逆序回放({@link BlackstartEvents#pushRemote});
 * 首帧对齐不回放,水位回退重对齐;本地环/旁表随即与宿主权威一致。
 */
public final class BlackstartTelemetry {
    private static final String REQUEST="bs05-fusion-observe-hello",STREAM="bs05-fusion-observe";
    private static final int MAGIC=0x42534632,MAX_NODES=4096,MAX_PAIRS=8192,MAX_EVENTS=64,MAX_FLOWS=8192;
    private record FlowRow(int owner,int peer,int ownerBlock,int peerBlock,int team,float flow){}
    private record BoundFlow(FlowRow row,Building owner,Building peer){}
    private static final Map<Long,BoundFlow> remoteFlows=new HashMap<>();
    private static long flowKey(int a,int b){return ((long)a<<32)|(b&0xffffffffL);}
    private record Node(int position,int block,int team,BlackstartDynamics.IslandDynamicsSnapshot snapshot,
                        int kind,boolean closed,int flags,int tier,float cool,float over){}
    private static final Map<Integer,Node> remote=new HashMap<>();
    private static final Map<Integer,Building> remoteIdentity=new HashMap<>();
    private static final IdentityHashMap<PowerGraph,IdentityHashMap<Building,Long>> remoteGraphs=new IdentityHashMap<>();
    private static final Map<Long,SyncPolicy.Decision> remotePairs=new HashMap<>();
    private static final TelemetryChannel channel=new TelemetryChannel();
    private static long remoteAt=-1,epoch,sequence,nonce,lastHello=-1;
    private static long lastEventSeq=-1;   // 客户端已回放的宿主事件水位;哨兵 -1 = 从未对齐(首帧对齐不回放)
    private static double lastSendTick=Double.NEGATIVE_INFINITY;
    private static boolean installed;
    private static TrainingModel.Status remoteTraining;
    private static arc.math.geom.Point2 remoteOrigin;
    private BlackstartTelemetry(){}
    public static long now(){return System.nanoTime()/1_000_000L;}
    private static long key(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    private static boolean client(){return Vars.net!=null && Vars.net.client();}
    public static void clear(){
        remote.clear();remoteIdentity.clear();remoteGraphs.clear();remotePairs.clear();remoteFlows.clear();remoteAt=-1;epoch++;sequence=0;
        remoteTraining=null;remoteOrigin=null;lastEventSeq=-1;
        nonce=java.util.concurrent.ThreadLocalRandom.current().nextLong();channel.begin(nonce);
        lastHello=-1;lastSendTick=Double.NEGATIVE_INFINITY;
    }
    public static void install(){
        if(installed)return;installed=true;clear();
        if(Vars.netServer!=null)Vars.netServer.addPacketHandler(REQUEST,(player,text)->{
            if(Vars.net==null || !Vars.net.server() || player==null || player.con==null || text==null || text.length()>24)return;
            try{long requested=Long.parseLong(text);Call.clientPacketReliable(player.con,STREAM,"H|"+requested+"|"+epoch);}catch(NumberFormatException ignored){}
        });
        if(Vars.netClient!=null)Vars.netClient.addPacketHandler(STREAM,BlackstartTelemetry::receive);
        Events.on(EventType.ResetEvent.class,e->clear());
        Events.on(EventType.WorldLoadEvent.class,e->clear());
        Events.run(EventType.Trigger.afterGameUpdate,BlackstartTelemetry::update);
    }
    private static void receive(String text){
        if(!client() || Vars.state==null || !Vars.state.isPlaying() || text==null)return;
        if(text.startsWith("H|") && text.length()<80){
            try{String[] f=text.split("\\|");if(f.length==3)channel.bind(Long.parseLong(f[1]),Long.parseLong(f[2]));}catch(NumberFormatException ignored){}
        }else{
            byte[] frame=channel.accept(text,now());if(frame!=null)apply(frame,now());
        }
    }
    private static void update(){
        if(Vars.state==null || !Vars.state.isPlaying() || Vars.net==null || !Vars.net.active())return;
        long time=now();
        if(client()){
            if(!channel.bound() && (lastHello<0 || time-lastHello>=1000)){lastHello=time;Call.serverPacketReliable(REQUEST,Long.toString(nonce));}
        }else if(Vars.net.server() && (Vars.state.tick<lastSendTick || Vars.state.tick-lastSendTick>=6)){
            lastSendTick=Vars.state.tick;
            for(String packet:TelemetryChannel.fragment(capture(),epoch,++sequence))Call.clientPacketReliable(STREAM,packet);
        }
    }
    public static byte[] capture(){
        try{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeInt(MAGIC);List<Building> nodes=BlackstartDynamics.observationNodes();
            int count=Math.min(MAX_NODES,nodes.size());out.writeInt(count);
            for(int i=0;i<count;i++){
                Building b=nodes.get(i);out.writeInt(b.pos());out.writeInt(b.block.id);out.writeInt(b.team.id);
                var s=BlackstartDynamics.snapshotAt(b.pos());out.writeBoolean(s!=null);
                if(s!=null){out.writeInt(s.island());out.writeLong(s.epoch());out.writeLong(s.sampleTick());out.writeByte(s.reference().ordinal());out.writeByte(s.kind().ordinal());out.writeDouble(s.frequencyHz());out.writeDouble(s.availablePower());out.writeDouble(s.demand());out.writeDouble(s.actualPower());}
                if(b instanceof BreakerNode.BreakerNodeBuild br){out.writeByte(1);out.writeBoolean(br.closed);out.writeInt(br.writeRunFlags());out.writeInt(0);out.writeFloat(br.coolSec);out.writeFloat(br.overSec);}
                else if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z){out.writeByte(2);out.writeBoolean(z.closed);out.writeInt(0);out.writeInt(z.tier);out.writeFloat(0);out.writeFloat(0);}
                else out.writeByte(0);
            }
            var pairs=BlackstartSync.observations();count=Math.min(MAX_PAIRS,pairs.size());out.writeInt(count);
            for(int i=0;i<count;i++){
                var p=pairs.get(i);var d=p.decision();out.writeInt(p.first());out.writeInt(p.second());out.writeBoolean(d.allowed());out.writeByte(d.reason().ordinal());out.writeDouble(d.differenceHz());out.writeLong(d.remainingTicks());
            }
            var lesson=BlackstartTraining.status();var origin=BlackstartTraining.origin();out.writeBoolean(lesson!=null && origin!=null);
            if(lesson!=null && origin!=null){
                out.writeInt(origin.x);out.writeInt(origin.y);out.writeByte(lesson.completed());out.writeByte(lesson.failure().ordinal());var f=lesson.facts();
                out.writeBoolean(f.sourceRunning());out.writeBoolean(f.reference());out.writeDouble(f.auxiliaryDelivered());out.writeDouble(f.auxiliaryRequired());out.writeBoolean(f.mainRunning());out.writeInt(f.loadBatches());out.writeDouble(f.load());out.writeDouble(f.available());out.writeDouble(f.linkCapacity());out.writeBoolean(f.synchronism());out.writeBoolean(f.interconnected());
            }
            // M4 事件段(帧尾):宿主事件水位 + 最新 ≤MAX_EVENTS 条(新→旧);旁表随条目携带(无旁表 28B、有旁表 84B)。
            out.writeLong(BlackstartEvents.seq());
            int events=Math.min(MAX_EVENTS,BlackstartEvents.size());out.writeInt(events);
            for(int i=0;i<events;i++){
                BlackstartEvents.Entry e=BlackstartEvents.get(i);
                out.writeByte(e.kind.ordinal());out.writeLong(e.tick);out.writeShort(e.count);
                out.writeInt(e.x);out.writeInt(e.y);out.writeInt(e.aux);out.writeFloat(e.value);
                ProtectionExplanation.Detail d=ProtectionExplanation.get(e);
                out.writeBoolean(d!=null);
                if(d!=null){
                    Building device=d.device();
                    out.writeLong(d.cause());
                    out.writeInt(device==null||device.block==null?-1:device.block.id);
                    out.writeInt(device==null||device.team==null?-1:device.team.id);
                    out.writeInt(d.zoneAnchor());out.writeInt(d.zoneMembers());out.writeInt(d.island());
                    out.writeDouble(d.threshold());out.writeDouble(d.duration());out.writeDouble(d.recloseDelay());
                    out.writeInt(d.recloseMax());
                }
            }
            List<FlowRow> flows=new ArrayList<>();
            for(var owner:BlackstartLinks.registry){
                if(flows.size()>=MAX_FLOWS)break;
                for(int i=0;i<owner.modLinks.size && flows.size()<MAX_FLOWS;i++){
                    Building peer=Vars.world==null?null:Vars.world.build(owner.modLinks.get(i));
                    if(!EngineeringFlowLimits.valid(owner,peer)||i>=owner.linkFlow.length)continue;
                    float f=owner.linkFlow[i];if(!Float.isFinite(f)||f<0)continue;
                    flows.add(new FlowRow(owner.pos(),peer.pos(),owner.block.id,peer.block.id,owner.team.id,f));
                }
            }
            out.writeInt(flows.size());for(FlowRow f:flows){out.writeInt(f.owner);out.writeInt(f.peer);out.writeInt(f.ownerBlock);out.writeInt(f.peerBlock);out.writeInt(f.team);out.writeFloat(f.flow);}
            out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    public static boolean apply(byte[] frame,long time){
        if(!client() || frame==null || frame.length>TelemetryChannel.MAX_PARTS*TelemetryChannel.PART_CHARS*3/4)return false;
        try{
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame));if(in.readInt()!=MAGIC)return false;
            int count=bounded(in.readInt(),MAX_NODES);Map<Integer,Node> next=new HashMap<>();
            for(int i=0;i<count;i++){
                int position=in.readInt(),block=in.readInt(),team=in.readInt();BlackstartDynamics.IslandDynamicsSnapshot s=null;
                if(in.readBoolean()){
                    int island=in.readInt();long version=in.readLong(),tick=in.readLong();
                    var reference=SyncPolicy.ReferenceState.values()[bounded(in.readUnsignedByte(),SyncPolicy.ReferenceState.values().length-1)];
                    var kind=BlackstartDynamics.ReferenceKind.values()[bounded(in.readUnsignedByte(),BlackstartDynamics.ReferenceKind.values().length-1)];
                    double frequency=in.readDouble(),available=nonnegative(in.readDouble()),demand=nonnegative(in.readDouble()),actual=nonnegative(in.readDouble());
                    if(reference==SyncPolicy.ReferenceState.LIVE && (!Double.isFinite(frequency) || frequency<=0))throw new IOException("invalid frequency");
                    s=new BlackstartDynamics.IslandDynamicsSnapshot(island,version,tick,reference,kind,frequency,available,demand,actual);
                }
                int kind=bounded(in.readUnsignedByte(),2);boolean closed=false;int flags=0,tier=0;float cool=0,over=0;
                if(kind>0){closed=in.readBoolean();flags=bounded(in.readInt(),15);tier=bounded(in.readInt(),3);cool=(float)nonnegative(in.readFloat());over=(float)nonnegative(in.readFloat());}
                if(next.put(position,new Node(position,block,team,s,kind,closed,flags,tier,cool,over))!=null)throw new IOException("duplicate position");
            }
            count=bounded(in.readInt(),MAX_PAIRS);Map<Long,SyncPolicy.Decision> pairs=new HashMap<>();
            for(int i=0;i<count;i++){
                int a=in.readInt(),b=in.readInt();boolean allowed=in.readBoolean();var reason=SyncPolicy.Reason.values()[bounded(in.readUnsignedByte(),SyncPolicy.Reason.values().length-1)];
                double diff=in.readDouble();long remaining=in.readLong();if(remaining<0 || remaining>SyncPolicy.HOLD_TICKS)throw new IOException("invalid hold");
                pairs.put(key(a,b),new SyncPolicy.Decision(allowed,reason,diff,remaining));
            }
            TrainingModel.Status lesson=null;arc.math.geom.Point2 origin=null;
            if(in.readBoolean()){
                origin=new arc.math.geom.Point2(in.readInt(),in.readInt());int completed=bounded(in.readUnsignedByte(),7);var failure=TrainingModel.Failure.values()[bounded(in.readUnsignedByte(),TrainingModel.Failure.values().length-1)];
                var facts=new TrainingModel.Facts(in.readBoolean(),in.readBoolean(),nonnegative(in.readDouble()),nonnegative(in.readDouble()),in.readBoolean(),bounded(in.readInt(),2),nonnegative(in.readDouble()),nonnegative(in.readDouble()),nonnegative(in.readDouble()),in.readBoolean(),in.readBoolean());
                lesson=new TrainingModel.Status(completed,failure,facts);
            }
            // M4 事件段:全量严格解码(kind 0..Kinds-1、tick≥0、count≥1),先于任何状态突变。
            long watermark=in.readLong();int events=bounded(in.readInt(),MAX_EVENTS);EventRow[] rows=new EventRow[events];
            for(int i=0;i<events;i++){
                int kind=bounded(in.readUnsignedByte(),BlackstartEvents.Kind.values().length-1);
                long tick=in.readLong();if(tick<0)throw new IOException("invalid event tick");
                short ecount=in.readShort();if(ecount<1)throw new IOException("invalid event count");
                int ex=in.readInt(),ey=in.readInt(),eaux=in.readInt();float evalue=in.readFloat();
                boolean hasDetail=in.readBoolean();
                if(hasDetail){
                    long cause=in.readLong();int deviceBlock=in.readInt(),deviceTeam=in.readInt();
                    int zoneAnchor=in.readInt(),zoneMembers=in.readInt(),island=in.readInt();
                    double threshold=in.readDouble(),duration=in.readDouble(),recloseDelay=in.readDouble();
                    int recloseMax=in.readInt();
                    rows[i]=new EventRow(kind,tick,ecount,ex,ey,eaux,evalue,true,cause,deviceBlock,deviceTeam,zoneAnchor,zoneMembers,island,threshold,duration,recloseDelay,recloseMax);
                }else{
                    rows[i]=new EventRow(kind,tick,ecount,ex,ey,eaux,evalue,false,0,-1,-1,0,0,-1,0,0,0,0);
                }
            }
            int flowCount=bounded(in.readInt(),MAX_FLOWS);Map<Long,FlowRow> flowRows=new HashMap<>();
            for(int i=0;i<flowCount;i++){
                FlowRow row=new FlowRow(in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),(float)nonnegative(in.readFloat()));
                if(flowRows.put(flowKey(row.owner,row.peer),row)!=null)throw new IOException("duplicate directed flow");
            }
            if(in.available()!=0)return false;
            // Decode every field before exposing or mutating any state.
            remote.clear();remote.putAll(next);remotePairs.clear();remotePairs.putAll(pairs);remoteAt=time;
            remoteFlows.clear();
            for(FlowRow row:flowRows.values()){
                Building a=Vars.world==null?null:Vars.world.build(row.owner),b=Vars.world==null?null:Vars.world.build(row.peer);
                if(EngineeringFlowLimits.valid(a,b)&&a.block.id==row.ownerBlock&&b.block.id==row.peerBlock&&a.team.id==row.team)
                    remoteFlows.put(flowKey(row.owner,row.peer),new BoundFlow(row,a,b));
            }
            remoteIdentity.clear();remoteGraphs.clear();
            for(Node row:next.values()){
                Building b=matching(row);if(b==null)continue;
                remoteIdentity.put(row.position,b);
                if(b.power!=null && b.power.graph!=null && !remoteGraphs.containsKey(b.power.graph)){
                    IdentityHashMap<Building,Long> members=new IdentityHashMap<>();
                    for(Building member:b.power.graph.all)members.put(member,memberType(member));
                    remoteGraphs.put(b.power.graph,members);
                }
            }
            remoteTraining=lesson;remoteOrigin=origin;
            for(Node row:next.values()){
                Building b=matching(row);if(b==null)continue;
                if(row.kind==1 && b instanceof BreakerNode.BreakerNodeBuild br){br.closed=row.closed;br.applyRunFlags((byte)row.flags);br.coolSec=row.cool;br.overSec=row.over;}
                else if(row.kind==2 && b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z){z.closed=row.closed;z.tier=row.tier;}
            }
            replayEvents(watermark,events,rows);
            return true;
        }catch(IOException | IllegalArgumentException ex){return false;}
    }
    /** 事件段条目(解码暂存;全帧通过后才注入,不提前暴露)。 */
    private record EventRow(int kind,long tick,short count,int x,int y,int aux,float value,boolean hasDetail,
                            long cause,int deviceBlock,int deviceTeam,int zoneAnchor,int zoneMembers,int island,
                            double threshold,double duration,double recloseDelay,int recloseMax){}
    /**
     * 水位增量回放:首帧(从未对齐)/水位回退 → 重对齐【不回放】;增量>0 → 取 min(delta, 携带条数)
     * 的【最新】若干条,自旧到新(i=take-1→0)经 {@link BlackstartEvents#pushRemote} 注入,
     * 旁表随条目走 applyRemote/removeRemote;末对齐水位。增量 ≤0 不动。
     */
    private static void replayEvents(long watermark,int events,EventRow[] rows){
        if(lastEventSeq<0 || watermark<lastEventSeq){lastEventSeq=watermark;return;}
        long delta=watermark-lastEventSeq;
        if(delta<=0)return;
        int take=(int)Math.min(delta,(long)events);
        for(int i=take-1;i>=0;i--){
            EventRow r=rows[i];
            var entry=BlackstartEvents.pushRemote(BlackstartEvents.Kind.values()[r.kind],r.tick,r.x,r.y,r.aux,r.value,r.count);
            if(r.hasDetail){
                ProtectionExplanation.applyRemote(entry,r.cause,r.deviceBlock,r.deviceTeam,r.zoneAnchor,r.zoneMembers,r.island,r.threshold,r.duration,r.recloseDelay,r.recloseMax);
            }else{
                ProtectionExplanation.removeRemote(entry);
            }
        }
        lastEventSeq=watermark;
    }
    private static int bounded(int value,int maximum)throws IOException{if(value<0 || value>maximum)throw new IOException("invalid count");return value;}
    private static double nonnegative(double value)throws IOException{if(!Double.isFinite(value) || value<0)throw new IOException("invalid power");return value;}
    private static Building matching(Node row){
        Building b=Vars.world==null?null:Vars.world.build(row.position);
        return b!=null && b.block!=null && b.team!=null && b.block.id==row.block && b.team.id==row.team?b:null;
    }
    private static boolean fresh(long time){return remoteAt>=0 && time>=remoteAt && time-remoteAt<=TelemetryChannel.MAX_AGE_MILLIS;}
    /** A host observation, never a write to client simulation arrays. Missing/stale is NaN. */
    public static float flowAt(Building owner,Building peer,long time){
        if(!fresh(time)||!EngineeringFlowLimits.valid(owner,peer))return Float.NaN;
        BoundFlow f=remoteFlows.get(flowKey(owner.pos(),peer.pos()));
        return f!=null&&f.owner==owner&&f.peer==peer&&owner.block.id==f.row.ownerBlock&&peer.block.id==f.row.peerBlock
            &&owner.team.id==f.row.team?f.row.flow:Float.NaN;
    }
    public static TrainingModel.Status trainingStatus(){return fresh(now())?remoteTraining:null;}
    public static arc.math.geom.Point2 trainingOrigin(){return fresh(now()) && remoteOrigin!=null?new arc.math.geom.Point2(remoteOrigin.x,remoteOrigin.y):null;}
    private static long memberType(Building b){
        return b==null || b.block==null || b.team==null?-1:((long)b.block.id<<32)|(b.team.id&0xffffffffL);
    }
    /** A frame belongs to the exact local graph membership observed when it was received. */
    private static boolean graphUnchanged(PowerGraph graph){
        var members=remoteGraphs.get(graph);
        if(members==null || members.size()!=graph.all.size)return false;
        for(Building b:graph.all){
            Long type=members.get(b);
            if(b==null || type==null || type!=memberType(b) || b.dead || b.tile==null || b.power==null
                || b.power.graph!=graph || Vars.world.build(b.pos())!=b)return false;
        }
        return true;
    }
    public static BlackstartDynamics.IslandDynamicsSnapshot snapshotAt(int position,long time){
        if(!fresh(time))return null;
        Building target=Vars.world==null?null:Vars.world.build(position);
        if(target==null || target.power==null || target.power.graph==null || !graphUnchanged(target.power.graph))return null;
        Node direct=remote.get(position);
        if(direct!=null && (remoteIdentity.get(position)!=target || matching(direct)!=target))return null;
        // One native representative per graph keeps the stream bounded. Conflicting anchors
        // after a local merge wait for a fresh host frame instead of guessing an island.
        BlackstartDynamics.IslandDynamicsSnapshot found=null;
        for(Building member:target.power.graph.all){
            Node row=remote.get(member.pos());
            if(row==null || row.snapshot==null || remoteIdentity.get(member.pos())!=member || matching(row)!=member)continue;
            if(found!=null && (found.island()!=row.snapshot.island() || found.epoch()!=row.snapshot.epoch()))return null;
            found=row.snapshot;
        }
        return direct==null?found:direct.snapshot;
    }
    public static SyncPolicy.Decision pair(int a,int b,long time){
        SyncPolicy.Decision d=remotePairs.get(key(a,b));
        if(d!=null && snapshotAt(a,time)!=null && snapshotAt(b,time)!=null)return d;
        return new SyncPolicy.Decision(false,SyncPolicy.Reason.UNKNOWN,Double.NaN,SyncPolicy.HOLD_TICKS);
    }
}
