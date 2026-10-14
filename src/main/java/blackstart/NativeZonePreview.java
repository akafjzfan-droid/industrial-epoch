package blackstart;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Building;

/** Read-only traversal of the native graph with one explicit laser edge omitted. */
public final class NativeZonePreview {
    private NativeZonePreview(){}
    /** On an open line, affected is the separate peer component that would be joined. */
    public record Preview(boolean connected,boolean bypass,int[] affected,int sources,int batteries){}

    public static Preview analyze(Building node,int peerPosition){
        if(!live(node))return empty();
        Building peer=Vars.world.build(peerPosition);
        if(!live(peer) || peer==node || peer.pos()!=peerPosition || peer.team!=node.team)return empty();
        boolean connected=node.power.links.contains(peerPosition) && peer.power.links.contains(node.pos());
        Set<Building> members=component(peer,node,peer,connected);
        boolean bypass=members.contains(node);
        if(bypass)return new Preview(connected,true,new int[0],0,0);
        int sources=0,batteries=0;
        int[] affected=new int[members.size()];int index=0;
        for(Building member:members){
            affected[index++]=member.pos();
            boolean buffered=member.block.consPower!=null && member.block.consPower.buffered;
            if(buffered)batteries++;
            else if(member.block.outputsPower)sources++;
        }
        java.util.Arrays.sort(affected);
        return new Preview(connected,false,affected,sources,batteries);
    }

    private static Set<Building> component(Building start,Building a,Building b,boolean omit){
        Set<Building> seen=new LinkedHashSet<>();
        ArrayDeque<Building> pending=new ArrayDeque<>();pending.add(start);seen.add(start);
        Seq<Building> neighbors=new Seq<>();
        while(!pending.isEmpty()){
            Building current=pending.removeFirst();
            current.getPowerConnections(neighbors);
            for(Building next:neighbors){
                if(!live(next) || next.team!=start.team)continue;
                if(omit && ((current==a && next==b)||(current==b && next==a))
                    && !conductiveNeighbor(current,next))continue;
                if(seen.add(next))pending.addLast(next);
            }
        }
        return seen;
    }

    /** Building.getPowerConnections suppresses duplicate adjacency while a laser exists.
     * Re-evaluate only that pair using the same native predicate, without removing links.
     * Two non-output consumers need conductivePower; never infer it from proximity alone. */
    private static boolean conductiveNeighbor(Building a,Building b){
        return a.proximity.contains(b) && a.conductsTo(b) && b.conductsTo(a)
            && !(a.block.consumesPower && b.block.consumesPower
                && !a.block.outputsPower && !b.block.outputsPower
                && !a.block.conductivePower && !b.block.conductivePower);
    }
    private static boolean live(Building b){
        return b!=null && b.power!=null && b.tile!=null && Vars.world!=null && Vars.world.build(b.pos())==b;
    }
    private static Preview empty(){return new Preview(false,false,new int[0],0,0);}
}
