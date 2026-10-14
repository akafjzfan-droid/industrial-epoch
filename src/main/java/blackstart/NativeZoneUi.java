package blackstart;

import java.util.ArrayList;
import java.util.List;
import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.geom.Point2;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.BaseDialog;

/** Optional native node management, leaving ordinary node taps and configuration intact. */
public final class NativeZoneUi {
    private static boolean installed;
    private static Building previewNode,previewPeer;
    private static mindustry.game.Team previewTeam;
    private static Object previewWorld;
    private static long previewUntil;
    private static final List<Building> highlighted=new ArrayList<>();
    private NativeZoneUi(){}

    public static void install(){
        if(Vars.headless || installed)return;
        installed=true;
        Events.run(EventType.Trigger.draw,NativeZoneUi::drawPreview);
        Events.on(EventType.WorldLoadEvent.class,e->clearPreview());
        Events.on(EventType.ResetEvent.class,e->clearPreview());
    }

    public static void addEntry(Table table,Building node){
        if(Vars.headless || !NativeNodeZones.supported(node))return;
        table.row();
        table.button(text("entry"),()->show(node)).height(44f);
    }

    public static void show(Building node){
        if(Vars.headless || !viewable(node))return;
        BaseDialog dialog=new BaseDialog(text("title"));
        Table body=new Table();body.defaults().pad(6f).growX().minWidth(0);
        var pane=dialog.cont.pane(body);
        body.label(()->node.block.localizedName+" "+coordinates(node.pos())).wrap().left().row();
        body.label(()->text(NativeNodeZones.canManage(node)?"host":"readonly")).wrap().left().row();
        Table help=new Table();help.defaults().growX();
        help.add(text("scope")).wrap().left().row();help.add(text("blueprint")).wrap().left().row();
        arc.scene.ui.layout.Collapser explanation=new arc.scene.ui.layout.Collapser(help,true);
        body.button(text("help"),()->explanation.setCollapsed(!explanation.isCollapsed())).minWidth(0).wrapLabel(true).minHeight(36f).row();
        body.add(explanation).growX().row();
        body.add(text("name")).wrap().left().row();
        TextField name=body.field(NativeNodeZones.name(node),s->{}).minWidth(0).wrapLabel(true).minHeight(42f).get();
        name.setMaxLength(32);name.update(()->name.setDisabled(!NativeNodeZones.canManage(node)));body.row();
        TextButton save=body.button(text("save"),()->{
            if(!NativeNodeZones.setName(node,name.getText()))notice("failed");
            else name.setText(NativeNodeZones.name(node));
        }).minWidth(0).wrapLabel(true).minHeight(42f).get();
        save.update(()->save.setDisabled(!NativeNodeZones.canManage(node)));body.row();
        body.add(text("priority")).wrap().left().row();
        body.table(tiers->{
            for(int i=0;i<4;i++){
                final int selected=i;
                TextButton button=tiers.button(text("tier."+i),()->{
                    if(!NativeNodeZones.setTier(node,selected))notice("failed");
                }).growX().minWidth(0).wrapLabel(true).minHeight(44f).pad(2f).get();
                button.update(()->{
                    button.setChecked(NativeNodeZones.tier(node)==selected);
                    button.setDisabled(!NativeNodeZones.canManage(node));
                });
                if(i%2==1)tiers.row();
            }
        }).row();
        body.add(text("links")).wrap().left().row();
        Table rows=new Table();rows.defaults().pad(4f).minWidth(0);body.add(rows).growX().row();
        Runnable refresh=()->fillLinks(rows,node,dialog);
        refresh.run();
        body.button(text("refresh"),refresh).minWidth(0).wrapLabel(true).minHeight(42f).row();
        dialog.addCloseButton();
        ResponsiveUi.bindDialog(dialog,pane,760f,.8f);
        dialog.update(()->{if(!viewable(node))dialog.hide();});
        dialog.show();
    }

    private static void fillLinks(Table rows,Building node,BaseDialog dialog){
        rows.clearChildren();
        List<NativeNodeZones.Link> links=NativeNodeZones.links(node);
        if(links.isEmpty()){rows.add(text("empty")).wrap().growX();return;}
        for(NativeNodeZones.Link link:links){
            int position=link.peerPosition();Building peer=Vars.world.build(position);
            String label=(peer==null?text("missing"):peer.block.localizedName)+" "+coordinates(position);
            rows.add(label+"\n"+text(link.connected()?"connected":"open")).colspan(2).growX().wrap().left().row();
            TextButton preview=rows.button(text("preview"),()->{
                if(!samePeer(node,peer,position)){notice("missing");return;}
                NativeZonePreview.Preview result=NativeZonePreview.analyze(node,position);
                markPreview(node,peer,result);dialog.hide();
                BlackstartView.showNotice(describe(result));
            }).growX().minWidth(0).wrapLabel(true).minHeight(48f).get();
            preview.update(()->preview.setDisabled(!samePeer(node,peer,position)));
            TextButton change=rows.button(text(link.connected()?"disconnect":"reconnect"),()->{
                if(!samePeer(node,peer,position)
                    || !NativeNodeZones.setLinkClosed(node,position,!link.connected()))notice("failed");
                clearPreview();fillLinks(rows,node,dialog);
            }).growX().minWidth(0).wrapLabel(true).minHeight(48f).get();
            change.update(()->change.setDisabled(!NativeNodeZones.canManage(node)||!samePeer(node,peer,position)));
            rows.row();
        }
    }

    private static String describe(NativeZonePreview.Preview p){
        String result;
        if(p.bypass())result=text(p.connected()?"bypass":"alreadyJoined");
        else result=Core.bundle.format("blackstart.nativeZone."+(p.connected()?"split":"join"),p.affected().length);
        if(!p.bypass())result+="\n"+Core.bundle.format("blackstart.nativeZone.resources",p.sources(),p.batteries());
        return result+"\n"+text("previewHint");
    }

    private static void markPreview(Building node,Building peer,NativeZonePreview.Preview result){
        clearPreview();previewNode=node;previewPeer=peer;previewTeam=node.team;previewWorld=Vars.world;
        previewUntil=Time.millis()+10000;
        // Only a real prospective split marks buildings. Closing previews identify the target line.
        if(result.connected() && !result.bypass())for(int pos:result.affected()){
            Building member=Vars.world.build(pos);if(member!=null)highlighted.add(member);
        }
    }

    private static void drawPreview(){
        if(previewNode==null)return;
        if(Time.millis()>=previewUntil || Vars.world!=previewWorld || !samePeer(previewNode,previewPeer,previewPeer.pos())
            || previewNode.team!=previewTeam){clearPreview();return;}
        float priorZ=Draw.z();Draw.z(Layer.overlayUI);Draw.color(Pal.accent);Lines.stroke(1.3f);
        Lines.dashLine(previewNode.x,previewNode.y,previewPeer.x,previewPeer.y,
            Math.max(2,Math.min(40,(int)(previewNode.dst(previewPeer)/8f))));
        for(Building member:highlighted){
            if(!live(member) || member.team!=previewTeam)continue;
            float half=member.block.size*Vars.tilesize/2f+1.5f,corner=Math.min(4f,half*.55f);
            for(int sx=-1;sx<=1;sx+=2)for(int sy=-1;sy<=1;sy+=2){
                float x=member.x+sx*half,y=member.y+sy*half;
                Lines.line(x,y,x-sx*corner,y);Lines.line(x,y,x,y-sy*corner);
            }
        }
        Draw.reset();Draw.z(priorZ);
    }

    private static boolean live(Building b){return b!=null && b.tile!=null && Vars.world!=null && Vars.world.build(b.pos())==b;}
    private static boolean viewable(Building node){
        return Vars.state!=null && !Vars.state.isMenu() && live(node) && NativeNodeZones.supported(node)
            && Vars.player!=null && Vars.player.team()==node.team;
    }
    private static boolean samePeer(Building node,Building peer,int position){
        return viewable(node) && live(peer) && peer.pos()==position && peer.power!=null && peer.team==node.team;
    }
    private static void clearPreview(){previewNode=null;previewPeer=null;previewTeam=null;previewWorld=null;highlighted.clear();}
    private static String text(String key){return Core.bundle.get("blackstart.nativeZone."+key);}
    private static String coordinates(int position){return "("+Point2.x(position)+","+Point2.y(position)+")";}
    private static void notice(String key){BlackstartView.showNotice(text(key));}
}
