package blackstart;

import arc.math.geom.Point2;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opt-in sandbox fixture: existing power equipment supplies two physically separate native branches. */
public final class BlackstartFusionDemo {
    public static final String ORIGIN_TAG="blackstart.fusion.demo.origin.v1";
    public static final int WIDTH=40,HEIGHT=26;
    public record Placement(int x,int y,Block block,int rotation){
        public Placement(int x,int y,Block block){this(x,y,block,0);}
    }
    private BlackstartFusionDemo(){}

    public static Point2 origin(){
        String value=BlackstartParams.get(ORIGIN_TAG,null);
        if(value==null)return null;
        try{
            String[] parts=value.split(",");
            if(parts.length!=2)return null;
            int x=Integer.parseInt(parts[0]),y=Integer.parseInt(parts[1]);
            if(Vars.world==null || x<0 || y<0 || x>Vars.world.width()-WIDTH || y>Vars.world.height()-HEIGHT)return null;
            return new Point2(x,y);
        }catch(NumberFormatException ex){return null;}
    }
    public static List<Placement> layout(){
        var p=new ArrayList<Placement>();
        p.add(new Placement(4,10,BlackstartBlocks.starterGenerator));
        for(int x=4;x<=12;x++)p.add(new Placement(x,12,Blocks.powerNode));
        p.add(new Placement(13,12,BlackstartBlocks.gatewayNode));
        p.add(new Placement(22,3,BlackstartBlocks.breakerNode));
        for(int x=23;x<=28;x++)p.add(new Placement(x,3,Blocks.powerNode));
        for(int y=4;y<=6;y++)p.add(new Placement(26,y,Blocks.powerNode));
        p.add(new Placement(29,3,BlackstartBlocks.zoneSwitchNode));
        p.add(new Placement(26,8,Blocks.surgeSmelter));
        p.add(new Placement(22,6,Blocks.vault));p.add(new Placement(22,10,Blocks.vault));
        p.add(new Placement(30,6,Blocks.vault));p.add(new Placement(30,10,Blocks.vault));
        for(int x:new int[]{24,28})for(int y:new int[]{7,9})p.add(new Placement(x,y,Blocks.unloader));
        p.add(new Placement(26,10,Blocks.conveyor,1));p.add(new Placement(26,11,Blocks.container));
        p.add(new Placement(22,18,BlackstartBlocks.breakerNode));
        for(int x=23;x<=30;x++)p.add(new Placement(x,18,Blocks.powerNode));
        p.add(new Placement(31,18,BlackstartBlocks.zoneSwitchNode));
        p.add(new Placement(25,19,Blocks.mendProjector));
        p.add(new Placement(29,19,Blocks.mendProjector));
        p.add(new Placement(27,23,Blocks.thoriumWallLarge));
        return List.copyOf(p);
    }
    public static boolean emptyArea(int x,int y){
        if(Vars.world==null || x<0 || y<0 || x>Vars.world.width()-WIDTH || y>Vars.world.height()-HEIGHT)return false;
        for(int xx=x;xx<x+WIDTH;xx++)for(int yy=y;yy<y+HEIGHT;yy++){
            var tile=Vars.world.tile(xx,yy);
            if(tile==null || tile.build!=null || tile.block()!=Blocks.air || tile.floor().isLiquid)return false;
        }
        return true;
    }
    private static boolean allowed(){
        return Vars.state!=null && Vars.state.isPlaying() && !Vars.state.isEditor()
            && Vars.state.rules.infiniteResources && !Vars.state.isCampaign()
            && (Vars.net==null || !Vars.net.client());
    }
    /** Returns a result key. It neither saves the world nor places anything outside an empty sandbox rectangle. */
    public static String create(){
        if(!allowed())return "sandboxOnly";
        if(origin()!=null)return "exists";
        for(int y=2;y<=Vars.world.height()-HEIGHT;y+=4)for(int x=2;x<=Vars.world.width()-WIDTH;x+=4){
            if(emptyArea(x,y)){buildAt(x,y);return "created";}
        }
        return "noSpace";
    }
    public static void buildAt(int x,int y){
        if(!allowed() || origin()!=null || !emptyArea(x,y))throw new IllegalStateException("mixed-base demo requires an unused empty sandbox area");
        // Physical adjacency only: placed() would let native nodes auto-link the two branches.
        for(Placement p:layout())Vars.world.tile(x+p.x,y+p.y).setNet(p.block,Team.sharded,p.rotation);
        var generator=(ControlledGenerator.ControlledGeneratorBuild)Vars.world.build(x+4,y+10);
        generator.items.add(Items.coal,10);generator.configureAny(800);
        var factory=Vars.world.build(x+26,y+8);
        // Finite sandbox supplies. No item source, infinite electrical source, or recurring refill.
        for(var item:new mindustry.type.Item[]{Items.copper,Items.lead,Items.titanium,Items.silicon})factory.items.add(item,factory.block.itemCapacity);
        supply(x,y,22,6,24,7,Items.copper);supply(x,y,22,10,24,9,Items.lead);
        supply(x,y,30,6,28,7,Items.titanium);supply(x,y,30,10,28,9,Items.silicon);
        Building wall=Vars.world.build(x+27,y+23);
        wall.health=wall.maxHealth()*0.15f;wall.healthChanged();
        var gateway=(GatewayNode.GatewayNodeBuild)Vars.world.build(x+13,y+12);
        gateway.configureAny(Point2.pack(x+22,y+3));gateway.configureAny(Point2.pack(x+22,y+18));
        Vars.world.build(x+22,y+3).configureAny(false);Vars.world.build(x+22,y+18).configureAny(false);
        Vars.world.build(x+29,y+3).configureAny(3); // factory: discardable, initially connected locally
        Vars.world.build(x+31,y+18).configureAny(7); // native repair: defense priority
        if(!BlackstartParams.set(ORIGIN_TAG,x+","+y))throw new IllegalStateException("demo origin could not be recorded");
    }
    private static void supply(int x,int y,int storeX,int storeY,int unloadX,int unloadY,mindustry.type.Item item){
        Building store=Vars.world.build(x+storeX,y+storeY);
        store.items.add(item,store.block.itemCapacity);
        Vars.world.build(x+unloadX,y+unloadY).configureAny(item);
    }
    /** An explicit sandbox test action, never a timer or automatic damage source. */
    public static String damageWall(){
        if(!allowed())return "sandboxOnly";
        Point2 p=origin();if(p==null)return "noScene";
        Building wall=at(p,27,23);
        if(wall==null || wall.block!=Blocks.thoriumWallLarge || wall.team!=Team.sharded)return "changed";
        wall.health=wall.maxHealth()*0.15f;wall.healthChanged();
        return "damaged";
    }
    private static Building at(Point2 p,int x,int y){return Vars.world==null?null:Vars.world.build(p.x+x,p.y+y);}
    private static float supply(Building b){return b==null || b.power==null?0:Math.max(0,Math.min(1,b.power.status));}
    /** Live observations only; inventory is not a lifetime production counter and full/input-starved factories stop normally. */
    public static String status(){
        Point2 p=origin();
        if(p==null)return BlackstartText.get("blackstart.fusion.demo.empty","No mixed-base demo. The sandbox host can create one in an empty area.");
        Building source=at(p,4,10),factory=at(p,26,8),repair=at(p,25,19),wall=at(p,27,23),output=at(p,26,11);
        if(!(source instanceof ControlledGenerator.ControlledGeneratorBuild generator) || factory==null || factory.block!=Blocks.surgeSmelter
            || repair==null || repair.block!=Blocks.mendProjector || wall==null || wall.block!=Blocks.thoriumWallLarge || output==null || output.block!=Blocks.container)
            return String.format(Locale.ROOT,BlackstartText.get("blackstart.fusion.demo.changed","Demo buildings changed. Check the equipment near origin (%d,%d)."),p.x,p.y);
        boolean client=Vars.net!=null && Vars.net.client();
        String local=client?BlackstartText.get("blackstart.fusion.demo.local","Local estimate; the host is authoritative.\n"):"";
        String phase=client?BlackstartText.get("blackstart.fusion.demo.remotePhase","see generator panel for host phase"):
            BlackstartText.get(generator.machine().running()?"blackstart.fusion.demo.running":"blackstart.fusion.demo.stopped",generator.machine().running()?"running":"not running");
        return local+String.format(Locale.ROOT,BlackstartText.get("blackstart.fusion.demo.status","Origin (%d,%d) | Generator %s, %.0f power/s\nFactory power %.0f%%, output container alloy %d; defense power %.0f%%, wall health %.0f/%.0f\nSupplies are finite. Missing inputs or full inventory stop the factory. Container inventory is not lifetime production."),
            p.x,p.y,phase,generator.getPowerProduction()*60,
            supply(factory)*100,output.items.get(Items.surgeAlloy),supply(repair)*100,wall.health,wall.maxHealth());
    }
}
