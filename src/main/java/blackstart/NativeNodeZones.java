package blackstart;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Rules;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.power.PowerNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** World-scoped zone labels and remembered open native laser connections. */
public final class NativeNodeZones {
    public static final String KEY="blackstart.nativezones.v1";
    private static final int MAX_NODES=512,MAX_EDGES=1024,MAX_TEXT=60000,MAX_RULE_BYTES=60000;
    public record Link(int peerPosition,boolean connected){}
    private static final class Mark {
        final Building building;final int team;final String block;
        String name;int tier;
        Mark(Building b,String name,int tier){building=b;team=b.team.id;block=b.block.name;this.name=name;this.tier=tier;}
        boolean live(){return same(building,team,block);}
    }
    private static final class Edge {
        final Building a,b;final int team;final String blockA,blockB;
        Edge(Building first,Building second){a=first.pos()<second.pos()?first:second;b=a==first?second:first;
            team=a.team.id;blockA=a.block.name;blockB=b.block.name;}
        boolean live(){return same(a,team,blockA) && same(b,team,blockB);}
    }
    private static final TreeMap<Integer,Mark> marks=new TreeMap<>();
    private static final TreeMap<Long,Edge> opens=new TreeMap<>();
    private static Rules rules;
    private static String raw;
    private static boolean installed;
    private static boolean dirty;
    private NativeNodeZones(){}
    private static long key(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    private static boolean host(){return Vars.net==null || !Vars.net.client();}
    private static boolean live(Building b){return b!=null && !b.dead && b.tile!=null && b.block!=null && b.team!=null
        && b.power!=null && Vars.world!=null && Vars.world.build(b.pos())==b;}
    private static boolean same(Building b,int team,String block){return live(b) && b.team.id==team && b.block.name.equals(block);}
    public static boolean supported(Building b){return live(b) && NativeNodeRetrofit.adapted(b);}
    public static boolean canManage(Building b){return supported(b) && host() && Vars.state!=null && Vars.state.isPlaying()
        && !Vars.state.isEditor() && (Vars.player==null || Vars.player.team()==b.team);}
    public static void install(){
        if(installed)return;installed=true;
        Events.on(EventType.ResetEvent.class,e->clear());
        Events.on(EventType.WorldLoadEvent.class,e->{clear();refresh();});
        Events.run(EventType.Trigger.update,NativeNodeZones::tick);
    }
    public static void clear(){marks.clear();opens.clear();raw=null;rules=null;dirty=false;}
    /** A Rules copy is not a world load: keep the identity of existing records. */
    public static void refresh(){
        Rules current=Vars.state==null?null:Vars.state.rules;
        String value=BlackstartParams.get(KEY,"");
        if(Objects.equals(raw,value)){rules=current;return;}
        var previousMarks=new TreeMap<>(marks);var previousEdges=new TreeMap<>(opens);
        rules=current;raw=value;marks.clear();opens.clear();
        if(current==null || value.isEmpty() || value.length()>MAX_TEXT)return;
        String[] rows=value.split("\n");if(rows.length==0 || !rows[0].equals("1"))return;
        for(int i=1;i<rows.length;i++){
            try{
                String[] f=rows[i].split(",",-1);
                if(f.length==6 && f[0].equals("N") && marks.size()<MAX_NODES){
                    Building b=Vars.world==null?null:Vars.world.build(Integer.parseInt(f[1]));
                    int team=Integer.parseInt(f[2]),tier=Integer.parseInt(f[4]);
                    if(!supported(b) || !same(b,team,f[3]) || tier<0 || tier>3 || f[5].length()>256)continue;
                    Mark previous=previousMarks.get(b.pos());
                    if(previous!=null && (previous.building!=b || !previous.live())){dirty=true;continue;}
                    String name=new String(Base64.getUrlDecoder().decode(f[5]),StandardCharsets.UTF_8);
                    if(!name.equals(cleanName(name)))continue;
                    marks.put(b.pos(),new Mark(b,name,tier));
                }else if(f.length==6 && f[0].equals("E") && opens.size()<MAX_EDGES){
                    Building a=Vars.world==null?null:Vars.world.build(Integer.parseInt(f[1]));
                    Building b=Vars.world==null?null:Vars.world.build(Integer.parseInt(f[2]));
                    int team=Integer.parseInt(f[3]);
                    if(a==b || !same(a,team,f[4]) || !same(b,team,f[5]) || (!supported(a) && !supported(b)))continue;
                    Edge previous=previousEdges.get(key(a.pos(),b.pos()));
                    if(previous!=null && !previous.live()){dirty=true;continue;}
                    opens.put(key(a.pos(),b.pos()),new Edge(a,b));
                }
            }catch(IllegalArgumentException ignored){ /* A corrupt row cannot authorize a connection. */ }
        }
    }
    private static boolean prune(){
        boolean a=marks.values().removeIf(m->!m.live());
        boolean b=opens.values().removeIf(e->!e.live());dirty|=a||b;return a||b;
    }
    public static void tick(){
        if(Vars.state==null || !Vars.state.isPlaying() || Vars.state.isEditor())return;
        refresh();prune();
        if(!host())return;
        // Native auto-linking or a normal click must not silently undo an explicit open.
        for(Edge e:opens.values()){
            Building owner=supported(e.a)?e.a:e.b,peer=owner==e.a?e.b:e.a;
            if(owner.power.links.contains(peer.pos()))owner.configureAny(peer.pos());
        }
        if(dirty)persist();
    }
    public static String name(Building b){refresh();Mark m=b==null?null:marks.get(b.pos());return m!=null && m.building==b && m.live()?m.name:"";}
    public static int tier(Building b){refresh();Mark m=b==null?null:marks.get(b.pos());return m!=null && m.building==b && m.live()?m.tier:0;}
    public static int priority(PowerGraph graph){
        refresh();int result=0;if(graph!=null)for(Mark m:marks.values())if(m.live() && m.building.power.graph==graph)result=Math.max(result,m.tier);
        return result;
    }
    private static String cleanName(String value){
        if(value==null)return "";
        // No color markup, embedded lines or control characters in a node label.
        value=value.replaceAll("[\\p{Cntrl}\\[\\]]","").strip();
        int end=value.offsetByCodePoints(0,Math.min(32,value.codePointCount(0,value.length())));return value.substring(0,end);
    }
    public static boolean setName(Building b,String value){return set(b,cleanName(value),tier(b));}
    public static boolean setTier(Building b,int tier){return tier>=0 && tier<=3 && set(b,name(b),tier);}
    private static boolean set(Building b,String name,int tier){
        if(!canManage(b))return false;refresh();prune();if(dirty)persist();
        if(!marks.containsKey(b.pos()) && marks.size()>=MAX_NODES)return false;
        Mark previous=marks.get(b.pos());
        if(name.isEmpty() && tier==0)marks.remove(b.pos());else marks.put(b.pos(),new Mark(b,name,tier));
        if(!persist()){if(previous==null)marks.remove(b.pos());else marks.put(b.pos(),previous);return false;}
        BlackstartLinks.markDirty();return true;
    }
    public static List<Link> links(Building node){
        refresh();TreeMap<Integer,Boolean> peers=new TreeMap<>();if(!supported(node))return List.of();
        for(int i=0;i<node.power.links.size;i++){
            int pos=node.power.links.get(i);Building b=Vars.world.build(pos);
            if(live(b) && b.team==node.team)peers.put(pos,true);
        }
        for(Edge edge:opens.values())if(edge.live()){
            if(edge.a==node)peers.put(edge.b.pos(),false);else if(edge.b==node)peers.put(edge.a.pos(),false);
        }
        List<Link> result=new ArrayList<>();peers.forEach((pos,on)->result.add(new Link(pos,on)));return List.copyOf(result);
    }
    public static boolean setLinkClosed(Building node,int peerPosition,boolean closed){
        if(!canManage(node))return false;refresh();prune();if(dirty)persist();
        Building peer=Vars.world==null?null:Vars.world.build(peerPosition);
        if(peer==node || !live(peer) || peer.team!=node.team)return false;
        long k=key(node.pos(),peerPosition);Edge remembered=opens.get(k);
        boolean connected=node.power.links.contains(peerPosition);
        if(!closed){
            if(remembered!=null && remembered.live())return true;
            if(!connected || opens.size()>=MAX_EDGES)return false;
            opens.put(k,new Edge(node,peer));
            if(!persist()){opens.remove(k);return false;}
            node.configure(peerPosition);
            if(node.power.links.contains(peerPosition)){opens.remove(k);dirty=true;persist();return false;}
            BlackstartLinks.markDirty();return true;
        }
        if(remembered==null || !remembered.live())return false;
        PowerNode block=(PowerNode)node.block;
        if(!connected && (!block.linkValid(node,peer) || node.power.links.size>=block.maxNodes))return false;
        opens.remove(k);
        if(!persist()){opens.put(k,remembered);return false;}
        if(!connected)node.configure(peerPosition);
        if(!node.power.links.contains(peerPosition)){opens.put(k,remembered);dirty=true;persist();return false;}
        BlackstartLinks.markDirty();return true;
    }
    private static boolean persist(){
        if(!host() || Vars.state==null || Vars.state.rules==null)return false;
        StringBuilder text=new StringBuilder("1\n");
        for(Mark m:marks.values())if(m.live())text.append("N,").append(m.building.pos()).append(',').append(m.team).append(',')
            .append(m.block).append(',').append(m.tier).append(',').append(Base64.getUrlEncoder().withoutPadding().encodeToString(m.name.getBytes(StandardCharsets.UTF_8))).append('\n');
        for(Edge e:opens.values())if(e.live())text.append("E,").append(e.a.pos()).append(',').append(e.b.pos()).append(',')
            .append(e.team).append(',').append(e.blockA).append(',').append(e.blockB).append('\n');
        String value=text.toString();
        if(value.equals(BlackstartParams.get(KEY,""))){dirty=false;return true;}
        if(value.length()>MAX_TEXT)return false;
        // SaveIO stores the ENTIRE rules JSON with writeUTF (65535 modified UTF-8 bytes).
        // Keep headroom below that and the network's 100000-byte cap, including other mods' tags.
        Rules candidate=Vars.state.rules.copy();candidate.tags.put(KEY,value);
        String json=mindustry.io.JsonIO.write(candidate);long bytes=0;
        for(int i=0;i<json.length();i++){
            char c=json.charAt(i);bytes+=c>=1 && c<=127?1:c<=2047?2:3;
            if(bytes>MAX_RULE_BYTES)return false;
        }
        if(!BlackstartParams.set(KEY,value))return false;
        rules=Vars.state.rules;raw=value;dirty=false;return true;
    }
}
