package blackstart;

import arc.Events;
import arc.math.geom.Point2;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import java.util.*;

/** A bounded sandbox exercise, placed only after verifying every tile is empty. */
public final class BlackstartTraining {
    public static final String ORIGIN_TAG="blackstart.training.origin.v1";
    public static final int WIDTH=48,HEIGHT=18;
    public record Placement(int x,int y,Block block){}
    private static TrainingModel model=new TrainingModel();
    private static TrainingModel.Status status;
    private static boolean installed;
    private BlackstartTraining(){}
    public static void install(){
        if(installed)return;installed=true;
        Events.on(EventType.ResetEvent.class,e->reset());Events.on(EventType.WorldLoadEvent.class,e->reset());
        Events.run(EventType.Trigger.afterGameUpdate,BlackstartTraining::update);
    }
    public static void reset(){model=new TrainingModel();status=null;}
    public static Point2 origin(){
        if(Vars.net!=null && Vars.net.client())return BlackstartTelemetry.trainingOrigin();
        String value=Vars.state==null?null:Vars.state.rules.tags.get(ORIGIN_TAG);
        if(value==null)return null;
        try{String[] parts=value.split(",");return parts.length==2?new Point2(Integer.parseInt(parts[0]),Integer.parseInt(parts[1])):null;}
        catch(NumberFormatException ex){return null;}
    }
    public static TrainingModel.Status status(){return Vars.net!=null && Vars.net.client()?BlackstartTelemetry.trainingStatus():status;}
    public static List<Placement> layout(){
        List<Placement> p=new ArrayList<>();
        p.add(new Placement(4,4,BlackstartBlocks.starterGenerator));p.add(new Placement(10,4,BlackstartBlocks.auxiliaryGenerator));
        for(int x=4;x<=13;x++)p.add(new Placement(x,6,Blocks.powerNode));
        p.add(new Placement(7,7,Blocks.battery));
        p.add(new Placement(14,6,BlackstartBlocks.gatewayNode));p.add(new Placement(12,7,BlackstartBlocks.zoneSwitchNode));
        p.add(new Placement(18,7,BlackstartBlocks.breakerNode));
        for(int x=19;x<=26;x++)p.add(new Placement(x,7,Blocks.powerNode));
        p.add(new Placement(20,8,BlackstartBlocks.trainingLoad));p.add(new Placement(23,8,BlackstartBlocks.trainingLoad));
        p.add(new Placement(26,8,BlackstartBlocks.zoneSwitchNode));
        p.add(new Placement(38,6,BlackstartBlocks.breakerNode));p.add(new Placement(39,6,Blocks.powerNode));p.add(new Placement(40,6,Blocks.powerSource));
        return List.copyOf(p);
    }
    public static boolean emptyArea(int x,int y){
        if(Vars.world==null || x<0 || y<0 || x+WIDTH>Vars.world.width() || y+HEIGHT>Vars.world.height())return false;
        for(int xx=x;xx<x+WIDTH;xx++)for(int yy=y;yy<y+HEIGHT;yy++){
            var tile=Vars.world.tile(xx,yy);if(tile==null || tile.build!=null || tile.block()!=Blocks.air || tile.floor().isLiquid)return false;
        }
        return true;
    }
    /** Returns a bundle suffix, never overwrites a base or saves a file. */
    public static String create(){
        if(Vars.state==null || !Vars.state.isPlaying() || !Vars.state.rules.infiniteResources || Vars.net!=null && Vars.net.client())return "sandboxOnly";
        if(origin()!=null)return "exists";
        for(int y=2;y+HEIGHT<Vars.world.height();y+=4)for(int x=2;x+WIDTH<Vars.world.width();x+=4)if(emptyArea(x,y)){
            buildAt(x,y);return "created";
        }
        return "noSpace";
    }
    public static void buildAt(int x,int y){
        if(Vars.state==null || !Vars.state.rules.infiniteResources || Vars.net!=null && Vars.net.client() || !emptyArea(x,y))throw new IllegalStateException("exercise requires empty sandbox area");
        // Raw setNet preserves only physical adjacency; invoking placed() would auto-link separate stations.
        for(Placement p:layout())Vars.world.tile(x+p.x,y+p.y).setNet(p.block,Team.sharded,0);
        var starter=(ControlledGenerator.ControlledGeneratorBuild)Vars.world.build(x+4,y+4);
        var main=(ControlledGenerator.ControlledGeneratorBuild)Vars.world.build(x+10,y+4);
        starter.items.add(Items.coal,10);main.items.add(Items.coal,10);
        var gateway=(GatewayNode.GatewayNodeBuild)Vars.world.build(x+14,y+6);
        gateway.configureAny(Point2.pack(x+18,y+7));gateway.configureAny(Point2.pack(x+38,y+6));
        ((BreakerNode.BreakerNodeBuild)Vars.world.build(x+18,y+7)).configureAny(false);
        ((BreakerNode.BreakerNodeBuild)Vars.world.build(x+38,y+6)).configureAny(false);
        ((ZoneSwitchNode.ZoneSwitchNodeBuild)Vars.world.build(x+12,y+7)).configureAny(7);
        ((ZoneSwitchNode.ZoneSwitchNodeBuild)Vars.world.build(x+26,y+8)).configureAny(3);
        if(!BlackstartParams.set(ORIGIN_TAG,x+","+y))throw new IllegalStateException("exercise origin rejected");
        reset();
    }
    private static Building at(Point2 p,int x,int y){return Vars.world==null?null:Vars.world.build(p.x+x,p.y+y);}
    public static void update(){
        if(Vars.state==null || !Vars.state.isPlaying() || Vars.net!=null && Vars.net.client())return;
        Point2 p=origin();if(p==null){status=null;return;}
        if(!(at(p,4,4) instanceof ControlledGenerator.ControlledGeneratorBuild starter)
            || !(at(p,10,4) instanceof ControlledGenerator.ControlledGeneratorBuild main)
            || !(at(p,14,6) instanceof GatewayNode.GatewayNodeBuild gateway)
            || !(at(p,38,6) instanceof BreakerNode.BreakerNodeBuild tie)) {status=null;return;}
        int batches=0;double load=0;
        for(int x:new int[]{20,23})if(at(p,x,8) instanceof TrainingLoad.TrainingLoadBuild bank && bank.enabled && bank.demand()>0){
            // A batch means a connected, actually powered load, not a promise of 100% satisfaction.
            // The inherited link controller deliberately retains its 5%-of-rating deadband.
            load+=bank.demand();if(bank.power!=null && bank.power.status>0)batches++;
        }
        var snapshot=BlackstartDynamics.snapshotAt(starter.pos());var peer=BlackstartDynamics.snapshotAt(tie.pos());
        boolean reference=snapshot!=null && snapshot.reference()==SyncPolicy.ReferenceState.LIVE;
        double required=main.machine().requested()?((ControlledGenerator)main.block).auxiliaryPower:0;
        double delivered=required*(main.power==null?0:Math.max(0,Math.min(1,main.power.status)));
        double available=(starter.machine().running()?((ControlledGenerator)starter.block).ratedPower:0)+(main.machine().running()?((ControlledGenerator)main.block).ratedPower:0);
        boolean interconnected=tie.closed && snapshot!=null && peer!=null && snapshot.island()==peer.island();
        status=model.observe(new TrainingModel.Facts(starter.machine().running(),reference,delivered,required,
            main.machine().running(),batches,load,Math.max(0,available-required),((GatewayNode)gateway.block).linkRating*60d,
            BlackstartSync.pair(gateway.pos(),tie.pos()).allowed(),interconnected));
    }
}
