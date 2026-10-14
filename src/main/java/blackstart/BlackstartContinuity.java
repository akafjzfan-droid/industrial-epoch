package blackstart;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.io.*;
import java.io.*;
import java.util.*;

/** Host-only complete-save extension. No building protocol, inventory or stored energy is altered. */
public final class BlackstartContinuity {
    static final String CHUNK="blackstart-continuity";
    private static final int SCHEMA=1,MAX_BYTES=16*1024*1024,MAX_RECORDS=65536,MAX_VALUES=2048;
    private static boolean installed,loaded;
    private static long scope,pendingScope;
    private static Object pendingWorld,pendingState,pendingTiles;
    private static List<Entry> pending=List.of();
    private static final Set<Building> awaiting=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final IdentityHashMap<Building,IdentityHashMap<Building,Float>> baselines=new IdentityHashMap<>();
    private static boolean measured;
    private static double settleTicks;
    private static int captureBytes;
    private record Entry(int kind,int pos,String block,int team,double[] values,Building instance,long policyRevision,double[] initial){}
    private record Key(int kind,int pos,String block,int team){}
    private static Key key(Entry e){return new Key(e.kind,e.pos,e.block,e.team);}
    private BlackstartContinuity(){}
    public static void install(){
        if(installed)return;installed=true;
        SaveVersion.addCustomChunk(CHUNK,new Chunk());
        Events.on(EventType.ResetEvent.class,e->reset());
        Events.on(EventType.WorldLoadEvent.class,e->reset());
        Events.on(EventType.SaveLoadEvent.class,e->{loaded=!e.isMap && host() && !Vars.state.isEditor();if(!loaded)discard();});
        Events.run(EventType.Trigger.beforeGameUpdate,BlackstartContinuity::beforeUpdate);
    }
    private static boolean host(){return Vars.state!=null && (Vars.net==null || !Vars.net.client());}
    private static void discard(){pending=List.of();loaded=false;pendingWorld=pendingState=pendingTiles=null;}
    private static void reset(){scope++;discard();awaiting.clear();baselines.clear();measured=false;settleTicks=0;BlackstartProtection.resetClock();}
    private static boolean pendingHere(){return loaded && host() && !Vars.state.isEditor() && pendingScope==scope
        && pendingWorld==Vars.world && pendingState==Vars.state && pendingTiles==Vars.world.tiles;}
    /** Commands made after load supersede pending state, including off/on within one paused frame. */
    static void command(Building b){
        if(pending.isEmpty())return;
        pending=pending.stream().filter(e->e.instance!=b || (b instanceof BreakerNode.BreakerNodeBuild && e.kind!=3)).toList();
    }
    /** Called before a successful link write. Only this target's commanded field supersedes the save. */
    static void linkCommand(GatewayNode.GatewayNodeBuild owner,int target,int field,double value){
        if(!pendingHere() || field<1 || field>3)return;
        int index=owner.modLinks.indexOf(target);if(index<0)return;
        int zone=-1;
        if(field==3){
            Building peer=Vars.world.build(target);
            if(peer!=null && peer.power!=null && peer.power.graph!=null){
                zone=Integer.MAX_VALUE;
                for(Building member:peer.power.graph.all)if(member!=null && member.pos()>=0)zone=Math.min(zone,member.pos());
            }
        }
        List<Entry> updated=new ArrayList<>(pending.size());
        for(Entry e:pending){
            // A grant command changes only its receiving zone's adoption of grants. Rebuild reads
            // all restored incoming grants; unrelated zone backoff/timers and island state survive.
            if(field==3 && e.kind==7 && e.pos==zone)continue;
            if(e.kind==4 && e.instance==owner && matching(e)!=null && e.values.length%5==0 && index*4+field<e.initial.length){
                double[] values=e.values.clone(),initial=e.initial.clone();
                for(int p=0;p<values.length;p+=5)if(values[p]==target)values[p+field]=value;
                initial[index*4+field]=value;
                e=new Entry(e.kind,e.pos,e.block,e.team,values,e.instance,e.policyRevision,initial);
            }
            updated.add(e);
        }
        pending=updated;
    }
    private static double[] commandState(int kind,Building b){
        if(b==null)return new double[0];
        if(kind==2 && b instanceof ControlledGenerator.ControlledGeneratorBuild g)
            return new double[]{b.enabled?1:0,g.machine().requested()?1:0,g.dispatchPermille(),g.machine().phase().ordinal(),g.machine().elapsed(),g.machine().output()};
        if(kind==3 && b instanceof BreakerNode.BreakerNodeBuild br)
            return new double[]{br.closed?1:0,br.run,br.used,br.overSec,br.coolSec,br.successSec};
        if(kind==4 && b instanceof GatewayNode.GatewayNodeBuild gw){
            double[] v=new double[gw.modLinks.size*4];
            for(int i=0;i<gw.modLinks.size;i++){int p=i*4;v[p]=gw.modLinks.get(i);v[p+1]=gw.linkOpen[i]?1:0;v[p+2]=gw.linkTier[i];v[p+3]=gw.linkGrant[i];}
            return v;
        }
        return new double[]{b.enabled?1:0};
    }
    static boolean awaitingMeasurement(Building b){return awaiting.contains(b);}
    static boolean stabilizing(){return host() && Vars.state.isPlaying() && settleTicks>0;}
    static void afterUpdate(double ticks){
        if(!host() || !Vars.state.isPlaying() || Vars.state.isEditor())return;
        awaiting.clear();
        if(!measured && !baselines.isEmpty()){measured=true;BlackstartLinks.markDirty();}
        if(Double.isFinite(ticks) && ticks>0)settleTicks=Math.max(0,settleTicks-ticks);
    }
    static float linkBaseline(Building owner,Building target,float previous,float budget){
        if(!measured)return previous;
        var peers=baselines.get(owner);Float saved=peers==null?null:peers.remove(target);
        return saved==null?previous:Math.min(saved,Math.max(0,budget));
    }
    static void linksSettled(){if(measured){baselines.clear();measured=false;}}
    static boolean integer(double v,int low,int high){return Double.isFinite(v) && v>=low && v<=high && v==(int)v;}
    static void beforeUpdate(){
        if(!host() || Vars.state.isEditor()){discard();awaiting.clear();baselines.clear();measured=false;settleTicks=0;return;}
        if(!Vars.state.isPlaying() || !loaded)return;
        List<Entry> entries=pendingHere()?pending.stream().filter(e->matching(e)!=null).toList():List.of();
        discard();if(entries.isEmpty())return;
        BlackstartLinks.registry.removeAll(b->b.tile==null || Vars.world.build(b.pos())!=b);
        Set<Building> peers=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Entry e:entries)if(e.kind==8 && e.values.length==0)peers.add(e.instance);
        int applied=0;
        for(Entry e:entries){
            Building b=e.instance;double[] v=e.values;
            if(e.kind==1 && v.length==1 && v[0]>=0 && v[0]<=1 && e.policyRevision==policyRevision(b)){if(NativeGeneratorRetrofit.restore(b,(float)v[0]))applied++;}
            else if(e.kind==2 && b instanceof ControlledGenerator.ControlledGeneratorBuild g && v.length==5 && b.enabled
                && v[3]>=0 && v[3]<=100 && v[4]>=0 && v[4]<=1e12
                && integer(v[0],0,4) && g.machine().restoreState((int)v[0],v[1],v[2])){
                awaiting.add(b);BlackstartDynamics.restoreDynamics(b,v[3],v[4]);applied++;
            }else if(e.kind==3 && b instanceof BreakerNode.BreakerNodeBuild br && v.length==5){
                BreakerNode block=(BreakerNode)b.block;
                if((v[0]==0 || v[0]==2 || v[0]==3 || v[0]==4) && integer(v[1],0,block.recloseMax)
                    && v[2]>=0 && v[2]<=block.tripDelaySec && v[3]>=0 && v[3]<=block.recloseDelaySec
                    && v[4]>=0 && v[4]<=block.recloseSuccessSec && (br.closed?v[0]!=2:v[0]==2)){
                    br.run=(byte)v[0];br.used=(int)v[1];br.overSec=(float)v[2];br.coolSec=(float)v[3];br.successSec=(float)v[4];applied++;
                }
            }else if(e.kind==4 && b instanceof GatewayNode.GatewayNodeBuild gw && v.length%5==0){
                for(int p=0;p<v.length;p+=5){
                    int pos=(int)v[p],i=gw.modLinks.indexOf(pos);Building peer=Vars.world.build(pos);
                    if(!integer(v[p],0,Integer.MAX_VALUE) || i<0 || i>=gw.linkGrant.length || peer==null || !peers.contains(peer) || peer.team!=b.team || !gw.modLinkValid(pos,b.team)
                        || !integer(v[p+1],0,1) || !integer(v[p+2],1,3) || v[p+3]<0 || v[p+3]>1 || v[p+4]<0 || v[p+4]>((GatewayNode)gw.block).linkRating)continue;
                    gw.linkOpen[i]=v[p+1]==1;gw.linkTier[i]=(byte)v[p+2];gw.linkGrant[i]=(float)v[p+3];
                    baselines.computeIfAbsent(gw,k->new IdentityHashMap<>()).put(peer,(float)v[p+4]);applied++;
                }
            }else if(e.kind==5 && v.length==1){BlackstartDynamics.restoreDynamics(b,v[0],0);}
        }
        BlackstartLinks.markDirty();SafetyNet.rebuildForRestore();
        for(Entry e:entries)if(e.kind==6 || e.kind==7)SafetyNet.restoreRun(e.pos,e.kind==7,e.values);
        if(applied>0)settleTicks=120;
    }
    private static Building matching(Entry e){
        Building b=Vars.world.build(e.pos);
        return b!=null && b==e.instance && !b.dead && b.block.name.equals(e.block) && b.team.id==e.team
            && (e.kind!=1 || e.policyRevision==policyRevision(b)) && Arrays.equals(e.initial,commandState(e.kind,b))?b:null;
    }
    private static String sector(){var s=Vars.state.rules.sector;return s==null?"":s.planet.name+":"+s.id;}
    private static long policyRevision(Building b){var p=NativeGeneratorRetrofit.profile(b);return p==null?0:BlackstartFusionPolicy.revision(p.device());}
    private static void add(List<Entry> list,int kind,Building b,double... values){
        if(b==null || b.dead || list.size()>=MAX_RECORDS || values.length>MAX_VALUES)return;
        for(double v:values)if(!Double.isFinite(v))return;
        int bytes=32+b.block.name.length()*3+values.length*8;
        if(captureBytes+bytes>MAX_BYTES)return;captureBytes+=bytes;
        list.add(new Entry(kind,b.pos(),b.block.name,b.team.id,values,b,policyRevision(b),commandState(kind,b)));
    }
    private static List<Entry> capture(){
        List<Entry> out=new ArrayList<>();
        captureBytes=64+sector().length()*3;
        for(var tile:Vars.world.tiles){
            Building b=tile.build;if(b==null || !tile.isCenter())continue;
            if(NativeGeneratorRetrofit.adapted(b))add(out,1,b,NativeGeneratorRetrofit.rampFraction(b));
            var snap=BlackstartDynamics.snapshotAt(b.pos());double f=snap!=null && Double.isFinite(snap.frequencyHz()) && snap.frequencyHz()>0 && snap.frequencyHz()<=100?snap.frequencyHz():0;
            if(b instanceof ControlledGenerator.ControlledGeneratorBuild g)add(out,2,b,g.machine().phase().ordinal(),g.machine().elapsed(),g.machine().output(),f,BlackstartDynamics.savedSetpoint(g));
            else if(f>0)add(out,5,b,f);
            if(b instanceof BreakerNode.BreakerNodeBuild br){var block=(BreakerNode)b.block;add(out,3,b,br.run,br.used,Math.min(br.overSec,block.tripDelaySec),Math.max(0,br.coolSec),Math.min(br.successSec,block.recloseSuccessSec));}
            if(b instanceof GatewayNode.GatewayNodeBuild gw){
                double[] v=new double[gw.modLinks.size*5];for(int i=0;i<gw.modLinks.size;i++){
                    int p=i*5;v[p]=gw.modLinks.get(i);v[p+1]=gw.linkOpen[i]?1:0;v[p+2]=gw.linkTier[i];v[p+3]=gw.linkGrant[i];v[p+4]=gw.linkFlow[i];
                    add(out,8,Vars.world.build(gw.modLinks.get(i)));
                }add(out,4,b,v);
            }
        }
        SafetyNet.saveRuns(false).forEach((p,v)->add(out,6,Vars.world.build(p),v));
        SafetyNet.saveRuns(true).forEach((p,v)->add(out,7,Vars.world.build(p),v));
        if(!pendingHere())return out;
        // A paused save observes pending mechanical state without applying it or paying resources.
        var merged=new LinkedHashMap<Key,Entry>();for(Entry e:out)merged.put(key(e),e);
        Set<Building> peers=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Entry e:pending)if(e.kind==8 && e.values.length==0 && matching(e)!=null)peers.add(e.instance);
        for(Entry e:pending){
            Building b=matching(e);if(b==null || ((e.kind==1 || e.kind==2) && !b.enabled))continue;
            if(e.kind==4 && b instanceof GatewayNode.GatewayNodeBuild gw){
                Entry current=merged.get(key(e));if(current==null || e.values.length%5!=0)continue;
                double[] v=current.values.clone();
                for(int p=0;p<e.values.length;p+=5){
                    int pos=(int)e.values[p],i=gw.modLinks.indexOf(pos);Building peer=Vars.world.build(pos);
                    if(integer(e.values[p],0,Integer.MAX_VALUE) && i>=0 && i*5+4<v.length && peers.contains(peer)
                        && peer.team==b.team && gw.modLinkValid(pos,b.team))System.arraycopy(e.values,p,v,i*5,5);
                }
                e=new Entry(e.kind,e.pos,e.block,e.team,v,b,e.policyRevision,e.initial);
            }
            merged.put(key(e),e);
        }
        // Pending must not bypass the same bounded writer limits.
        out.clear();captureBytes=64+sector().length()*3;
        for(Entry e:merged.values())add(out,e.kind,e.instance,e.values);
        return out;
    }
    static final class Chunk implements SaveFileReader.CustomChunk {
        @Override public boolean writeNet(){return false;}
        @Override public boolean shouldWrite(){return host() && Vars.world!=null && !Vars.state.isEditor() && !Vars.state.isMenu();}
        @Override public void write(DataOutput out)throws IOException{
            out.writeInt(SCHEMA);out.writeInt(Vars.world.width());out.writeInt(Vars.world.height());out.writeUTF(sector());
            var entries=capture();out.writeInt(entries.size());
            for(Entry e:entries){out.writeByte(e.kind);out.writeInt(e.pos);out.writeUTF(e.block);out.writeInt(e.team);out.writeInt(e.values.length);for(double v:e.values)out.writeDouble(v);}
        }
        @Override public void read(DataInput in)throws IOException{parse(in);}
        @Override public void read(DataInput in,int length)throws IOException{
            discard();
            if(length<0)return;
            if(length>MAX_BYTES){int left=length;while(left>0){int skipped=in.skipBytes(left);if(skipped==0){in.readByte();skipped=1;}left-=skipped;}return;}
            byte[] bytes=new byte[length];in.readFully(bytes);
            try{parse(new DataInputStream(new ByteArrayInputStream(bytes)));}catch(IOException | RuntimeException damaged){discard();}
        }
        private void parse(DataInput in)throws IOException{
            discard();if(in.readInt()!=SCHEMA)return;
            int w=in.readInt(),h=in.readInt();String s=in.readUTF();int count=in.readInt();
            if(w!=Vars.world.width() || h!=Vars.world.height() || !s.equals(sector()) || count<0 || count>MAX_RECORDS || !host() || Vars.state.isEditor())return;
            var entries=new LinkedHashMap<Key,Entry>();
            for(int i=0;i<count;i++){
                int kind=in.readUnsignedByte(),pos=in.readInt();String block=in.readUTF();int team=in.readInt(),n=in.readInt();
                if(n<0 || n>MAX_VALUES || block.length()>256)throw new IOException("invalid continuity record");
                double[] v=new double[n];boolean finite=true;for(int j=0;j<n;j++){v[j]=in.readDouble();finite&=Double.isFinite(v[j]) && Math.abs(v[j])<=1e12;}
                Building b=Vars.world.build(pos);
                Entry e=new Entry(kind,pos,block,team,v,b,policyRevision(b),commandState(kind,b));
                Entry previous=entries.putIfAbsent(key(e),e);
                // Identical records (including shared-peer kind 8) are idempotent; conflicting state is damaged.
                if(!finite || (previous!=null && !Arrays.equals(previous.values,v))){discard();return;}
            }
            pending=new ArrayList<>(entries.values());pendingScope=scope;pendingWorld=Vars.world;pendingState=Vars.state;pendingTiles=Vars.world.tiles;
        }
    }
}
