package blackstart;

import arc.Core;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A manual upper bound, never a demand command or a replacement for automatic protection. */
public final class EngineeringFlowUi {
    private EngineeringFlowUi(){}
    private static String text(String key){return BlackstartText.get("blackstart.flow."+key,switch(key){
        case "title"->"Engineering line flow";case "entry"->"Adjust";case "empty"->"No engineering nodes in this team";
        case "help"->"Set each outgoing line's upper capacity. Actual flow follows demand and available generation, with losses and protection. Drag to preview; Apply saves this world. Blueprints do not copy these settings.";
        case "host"->"Host control · saved with this world";case "readonly"->"Read-only · the host sets limits";
        case "choose"->"Choose node";case "refresh"->"Refresh nodes and lines";case "noLines"->"This node has no outgoing engineering lines";
        case "apply"->"Apply";case "failed"->"Cannot apply: check host permission, live endpoints and world-rule storage space";
        case "current"->"Actual %s/s · saved cap %.0f%% (%.1f/s) · rated %.1f/s";
        case "draft"->"Proposed cap %.0f%% · %.1f/s";default->key;
    });}
    private static String coordinates(Building b){return "("+b.tileX()+","+b.tileY()+")";}
    private static boolean visible(Building b){return b instanceof GatewayNode.GatewayNodeBuild&&!b.dead&&b.tile!=null&&Vars.world!=null
        &&Vars.world.build(b.pos())==b&&(Vars.player==null||Vars.player.team()==b.team);}
    public static void show(){
        if(Vars.headless||Vars.ui==null||Vars.state==null||!Vars.state.isGame()||Vars.state.isEditor())return;
        BaseDialog dialog=new BaseDialog(text("title"));Table body=new Table();body.defaults().pad(6f).growX().minWidth(0);
        var pane=dialog.cont.pane(body);body.add(text("help")).wrap().left().row();
        body.label(()->text(Vars.net==null||!Vars.net.client()?"host":"readonly")).wrap().left().row();
        Table chooser=new Table(),rows=new Table();body.add(chooser).growX().row();body.add(rows).growX().row();
        GatewayNode.GatewayNodeBuild[] selected={null};
        if(Vars.control!=null&&Vars.control.input!=null&&Vars.control.input.config!=null
            &&Vars.control.input.config.getSelected() instanceof GatewayNode.GatewayNodeBuild gw&&visible(gw))selected[0]=gw;
        Runnable refresh=()->{
            chooser.clearChildren();List<GatewayNode.GatewayNodeBuild> nodes=new ArrayList<>();
            for(var gw:BlackstartLinks.registry)if(visible(gw))nodes.add(gw);nodes.sort(Comparator.comparingInt(Building::pos));
            if(nodes.isEmpty()){rows.clearChildren();chooser.add(text("empty")).wrap().growX();return;}
            if(selected[0]==null||!nodes.contains(selected[0]))selected[0]=nodes.get(0);
            chooser.add(text("choose")).colspan(2).wrap().growX().left().row();
            for(int i=0;i<nodes.size();i++){
                var node=nodes.get(i);TextButton select=chooser.button(node.block.localizedName+" "+coordinates(node),Styles.defaultt,()->{
                    selected[0]=node;populate(rows,node);
                }).growX().minWidth(0).wrapLabel(true).minHeight(40f).get();
                select.update(()->{select.setChecked(selected[0]==node);select.setDisabled(!visible(node));});if(i%2==1)chooser.row();
            }
            populate(rows,selected[0]);
        };
        refresh.run();body.button(text("refresh"),Styles.defaultt,refresh).growX().minWidth(0).wrapLabel(true).minHeight(44f).row();
        dialog.addCloseButton();ResponsiveUi.bindDialog(dialog,pane,760f,.8f);Object tiles=Vars.world.tiles;
        dialog.update(()->{if(Vars.world==null||Vars.world.tiles!=tiles||Vars.state==null||!Vars.state.isGame())dialog.hide();});dialog.show();
    }
    static void populate(Table rows,GatewayNode.GatewayNodeBuild owner){
        rows.clearChildren();rows.defaults().growX().minWidth(0).pad(5f);
        if(!visible(owner)){rows.add(text("empty")).wrap().row();return;}
        rows.add(owner.block.localizedName+" "+coordinates(owner)).wrap().left().row();int count=0;
        for(int i=0;i<owner.modLinks.size;i++){
            Building peer=Vars.world.build(owner.modLinks.get(i));if(!EngineeringFlowLimits.valid(owner,peer))continue;count++;
            float rated=((GatewayNode)owner.block).linkRating*60;
            rows.add(coordinates(owner)+" > "+coordinates(peer)).wrap().left().row();
            rows.label(()->{
                int p=EngineeringFlowLimits.percent(owner,peer);float f=actual(owner,peer);
                return String.format(Locale.ROOT,text("current"),Float.isFinite(f)?String.format(Locale.ROOT,"%.1f",f*60):"—",(float)p,rated*p/100,rated);
            }).wrap().left().row();
            Slider slider=new Slider(0,100,1,false,Styles.defaultSlider);slider.setValue(EngineeringFlowLimits.percent(owner,peer));
            slider.update(()->slider.setDisabled(!EngineeringFlowLimits.canManage(owner,peer)));
            rows.add(slider).growX().minWidth(0).minHeight(36f).row();
            rows.label(()->String.format(Locale.ROOT,text("draft"),slider.getValue(),rated*slider.getValue()/100)).wrap().left().row();
            TextButton apply=rows.button(text("apply"),Styles.defaultt,()->{
                if(!EngineeringFlowLimits.set(owner,peer,Math.round(slider.getValue())))BlackstartView.showNotice(text("failed"));
            }).growX().minWidth(0).wrapLabel(true).minHeight(44f).padBottom(12f).get();
            apply.update(()->apply.setDisabled(!EngineeringFlowLimits.canManage(owner,peer)));rows.row();
        }
        if(count==0)rows.add(text("noLines")).wrap().left().row();
    }
    private static float actual(GatewayNode.GatewayNodeBuild owner,Building peer){
        if(!EngineeringFlowLimits.valid(owner,peer))return Float.NaN;
        if(Vars.net!=null&&Vars.net.client())return BlackstartTelemetry.flowAt(owner,peer,BlackstartTelemetry.now());
        int i=owner.modLinks.indexOf(peer.pos());return i>=0&&i<owner.linkFlow.length?owner.linkFlow[i]:Float.NaN;
    }
}
