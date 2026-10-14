package blackstart;

import arc.func.Prov;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.content.Blocks;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.blocks.power.PowerNode;

/** Adds a configuration panel without changing native wires, config types or save bytes. */
public final class NativeNodeRetrofit {
    private NativeNodeRetrofit(){}
    public static void install(){
        for(Block block:new Block[]{Blocks.powerNode,Blocks.powerNodeLarge,Blocks.surgeTower}){
            if(!(block instanceof PowerNode node) || block.minfo.mod!=null)continue;
            Prov<Building> original=block.buildType;
            if(original instanceof Factory f && f.node==node)continue;
            if(original==null || original.getClass().getNestHost()!=Block.class
                || original.get().getClass()!=PowerNode.PowerNodeBuild.class){
                Log.warn("[bs] native zone adapter skipped foreign factory: @",block.name);continue;
            }
            block.buildType=new Factory(node);
        }
    }
    public static boolean adapted(Building b){return b instanceof NodeBuild;}
    private static final class Factory implements Prov<Building>{
        final PowerNode node;Factory(PowerNode node){this.node=node;}
        public Building get(){return new NodeBuild(node);}
    }
    private static final class NodeBuild extends PowerNode.PowerNodeBuild{
        NodeBuild(PowerNode node){node.super();}
        @Override public void buildConfiguration(Table table){super.buildConfiguration(table);NativeZoneUi.addEntry(table,this);}
    }
}
