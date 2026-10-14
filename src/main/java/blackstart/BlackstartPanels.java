package blackstart;

import arc.Core;
import arc.math.geom.Point2;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.power.PowerGraph;
import java.util.Locale;

/** Player-facing views are isolated from headless event storage and simulation. */
public final class BlackstartPanels {
    private BlackstartPanels() {}

    /** World rules are host-owned; the editor always retains native behavior. */
    static boolean canEditNativeFusion(){
        return Vars.state!=null && Vars.state.rules!=null && Vars.state.isGame() && !Vars.state.isEditor()
            && (Vars.net==null || !Vars.net.client());
    }

    public static void showNativeFusion(){
        if(Vars.headless || Vars.ui==null)return;
        BaseDialog dialog=new BaseDialog(BlackstartText.get("blackstart.nativeFusion.title","Native fusion"));
        Table content=new Table();
        content.defaults().growX().left();
        for(String section:new String[]{"intro","startup","resources","frequency","limits"}){
            content.add(BlackstartText.get("blackstart.nativeFusion."+section,section)).wrap().left().padBottom(10).row();
        }
        content.label(()->BlackstartText.get("blackstart.nativeFusion.current","Current mode: ")
            +BlackstartText.get(BlackstartFusionPolicy.active()?"blackstart.nativeFusion.standard":"blackstart.nativeFusion.compat",
                BlackstartFusionPolicy.active()?"Standard":"Compatibility")).wrap().left().row();
        content.label(()->canEditNativeFusion()?BlackstartText.get("blackstart.nativeFusion.host","Host settings · saved with this world")
            :BlackstartText.get("blackstart.nativeFusion.readonly","Read-only · only the host can change settings during play; editor uses compatibility")).wrap().left().padBottom(10).row();
        Table modes=new Table();
        for(String mode:new String[]{"standard","compat"}){
            TextButton button=modes.button(BlackstartText.get("blackstart.nativeFusion."+mode,mode),()->{
                if(canEditNativeFusion())BlackstartFusionPolicy.setMode(mode);
            }).growX().minHeight(40).get();
            button.getLabel().setWrap(true);modes.row();
            button.update(()->button.setDisabled(!canEditNativeFusion()
                || BlackstartFusionPolicy.active()==mode.equals("standard")));
        }
        content.add(modes).left().padBottom(12).row();
        for(NativeGenerationProfile profile:NativeGeneratorRetrofit.profiles()){
            String device=profile.device();
            TextButton button=content.button("",()->{
                if(canEditNativeFusion())BlackstartFusionPolicy.setNativeEnabled(device,!BlackstartFusionPolicy.nativeEnabled(device));
            }).growX().minHeight(42).get();
            button.getLabel().setWrap(true);
            button.update(()->{
                button.setText(BlackstartText.get("blackstart.nativeFusion."+device,device)+" · "
                    +BlackstartText.get(BlackstartFusionPolicy.nativeEnabled(device)?"blackstart.nativeFusion.on":"blackstart.nativeFusion.off",
                        BlackstartFusionPolicy.nativeEnabled(device)?"Adaptation on":"Native behavior"));
                button.setDisabled(!canEditNativeFusion() || !BlackstartFusionPolicy.active());
            });
            content.row();
            content.add(BlackstartText.get(profile.descriptionKey(),profile.description())).wrap().left().padBottom(8).row();
        }
        var pane=dialog.cont.pane(content).growX();dialog.cont.row();
        dialog.addCloseButton();ResponsiveUi.bindDialog(dialog,pane,480f,.65f);dialog.show();
    }

    public static void showFusion(){
        if(Vars.headless || Vars.ui==null)return;
        BaseDialog dialog=new BaseDialog(BlackstartText.get("blackstart.fusion.title","Mixed-base power drill"));
        Table content=new Table();
        content.add(BlackstartText.get("blackstart.fusion.intro","Use an empty sandbox area to supply real native production and defense through separate branches. Materials are finite.")).wrap().width(680).left().row();
        content.add(BlackstartText.get("blackstart.fusion.steps","Start the source, connect both feeders, then isolate the factory while keeping the repair branch supplied. Reconnect the factory and observe production resume.")).wrap().width(680).left().padTop(12).row();
        content.label(BlackstartFusionDemo::status).wrap().width(680).left().padTop(12).row();
        dialog.cont.pane(content).colspan(2).width(740).height(420).row();
        dialog.cont.button(BlackstartText.get("blackstart.fusion.create","Create mixed-base drill"),()->{
            String result=BlackstartFusionDemo.create();
            BlackstartView.showNotice(BlackstartText.get("blackstart.fusion."+result,result));
        }).width(300);
        dialog.cont.button(BlackstartText.get("blackstart.fusion.focus","Locate power source"),()->{
            var p=BlackstartFusionDemo.origin();
            if(p==null || Vars.control==null || Vars.control.input==null)return;
            Vars.control.input.panCamera(new arc.math.geom.Vec2((p.x+4)*Vars.tilesize,(p.y+10)*Vars.tilesize));
            dialog.hide();
        }).width(300);
        dialog.cont.row();
        dialog.cont.button(BlackstartText.get("blackstart.fusion.damage","Damage the demo wall again (sandbox)"),()->{
            String result=BlackstartFusionDemo.damageWall();
            BlackstartView.showNotice(BlackstartText.get("blackstart.fusion."+result,result));
        }).colspan(2).width(600);
        dialog.addCloseButton();dialog.show();
    }
    public static void showTraining(){
        if(Vars.headless || Vars.ui==null)return;
        BaseDialog dialog=new BaseDialog(BlackstartText.get("blackstart.training.title","Black-start exercise"));
        dialog.cont.add(BlackstartText.get("blackstart.training.intro","Create the exercise in an empty sandbox area. Operate the real machines and switches; the lesson observes their state.")).wrap().width(720).row();
        dialog.cont.label(()->{
            var status=BlackstartTraining.status();var origin=BlackstartTraining.origin();
            if(origin==null)return BlackstartText.get("blackstart.training.noScene","No exercise in this world");
            if(status==null)return BlackstartText.get("blackstart.training.wait","Waiting for a valid scene observation");
            int step=Math.min(6,status.completed());var f=status.facts();
            return String.format(Locale.ROOT,BlackstartText.get("blackstart.training.progress","Completed %d/7 · origin (%d,%d)"),status.completed(),origin.x,origin.y)+"\n"
                +BlackstartText.get("blackstart.training.step."+step,"Step "+(step+1))+"\n"
                +String.format(Locale.ROOT,BlackstartText.get("blackstart.training.conditions","Auxiliary %.0f/%.0f · load %.0f · available %.0f · line %.0f power/s · batches %d"),f.auxiliaryDelivered(),f.auxiliaryRequired(),f.load(),f.available(),f.linkCapacity(),f.loadBatches())+"\n"
                +BlackstartText.get("blackstart.training.failure."+status.failure().name().toLowerCase(Locale.ROOT),status.failure().name())+"\n"
                +BlackstartText.get("blackstart.training.action."+step,"Operate the highlighted device");
        }).wrap().width(720).row();
        dialog.cont.button(BlackstartText.get("blackstart.training.create","Create sandbox exercise"),()->{
            String result=BlackstartTraining.create();BlackstartView.showNotice(BlackstartText.get("blackstart.training."+result,result));
        }).width(300).row();
        dialog.cont.button(BlackstartText.get("blackstart.training.focus","Locate current step"),()->{
            var p=BlackstartTraining.origin();var status=BlackstartTraining.status();if(p==null || status==null || Vars.control==null)return;
            int step=Math.min(6,status.completed());int x=step<2?4:step<4?10:step==4?20:step==5?10:38;
            int y=step<4 || step==5?4:step==4?8:6;
            Vars.control.input.panCamera(new arc.math.geom.Vec2((p.x+x)*Vars.tilesize,(p.y+y)*Vars.tilesize));dialog.hide();
        }).width(300);
        dialog.addCloseButton();dialog.show();
    }
    public static void showBlackbox() {
        if(Vars.headless || Vars.ui==null) return;
        BaseDialog dialog=new BaseDialog(BlackstartText.get("blackstart.blackbox.title","Blackbox"));
        boolean[] diagnostics={false};
        Table rows=new Table();
        Runnable refresh=() -> {
            rows.clearChildren();
            rows.add(BlackstartText.get("blackstart.blackbox.scope","Local session; cleared on map/load. Newest first.")).wrap().width(700).row();
            int explained=0;
            for(BlackstartBlackbox.Row row:BlackstartBlackbox.snapshot()) {
                var detail=row.explanation();
                if(detail!=null){
                    explained++;
                    var explanation=ProtectionExplanation.describe(detail);
                    rows.add(String.format(Locale.ROOT,BlackstartText.get("blackstart.explain.heading","%.1fs · %s · count %d"),row.tick()/60d,explanation.device(),row.count())).left().width(700).wrap().row();
                    rows.add(BlackstartText.get("blackstart.explain.measured","Observed: ")+explanation.measured()).left().width(700).wrap().row();
                    rows.add(BlackstartText.get("blackstart.explain.affected","Affected: ")+explanation.affected()).left().width(700).wrap().row();
                    rows.add(BlackstartText.get("blackstart.explain.recovery","Recovery: ")+explanation.recovery()).left().width(700).wrap().row();
                    if(ProtectionExplanation.canLocate(detail))rows.button(BlackstartText.get("blackstart.explain.locate","Locate device"),()->{
                        if(ProtectionExplanation.canLocate(detail) && Vars.control!=null && Vars.control.input!=null){
                            Vars.control.input.panCamera(new arc.math.geom.Vec2(detail.x()*Vars.tilesize,detail.y()*Vars.tilesize));dialog.hide();
                        }
                    }).left().row();
                }
                if(diagnostics[0]){
                    String value=Float.isFinite(row.value()) ? String.format(Locale.ROOT,"%.2f",row.value()) : "-";
                    rows.add(String.format(Locale.ROOT,BlackstartText.get("blackstart.blackbox.row","%.1fs  %s  (%d,%d)  count=%d  aux=%d  value=%s"),
                        row.tick()/60d,row.kind(),row.x(),row.y(),row.count(),row.aux(),value)).left().width(700).wrap().row();
                }
            }
            if(BlackstartEvents.size()==0) rows.add(BlackstartText.get("blackstart.events.empty","No events")).row();
            // M4 空态:环内有事件、但无旁表可解释且诊断显示关 ⇒ 明示原因与出路(不再整屏只余 scope 行)。
            else if(explained==0 && !diagnostics[0]) rows.add(BlackstartText.get("blackstart.blackbox.noExplained","No explained events yet; enable diagnostics to show raw events")).row();
        };
        refresh.run();
        dialog.cont.pane(rows).width(760).height(450).row();
        dialog.cont.button(BlackstartText.get("blackstart.blackbox.refresh","Refresh"),refresh).width(220);
        dialog.cont.button(BlackstartText.get("blackstart.blackbox.copy","Copy JSON"),()->Core.app.setClipboardText(BlackstartBlackbox.exportJson())).width(220);
        dialog.cont.row();dialog.cont.check(BlackstartText.get("blackstart.blackbox.diagnostics","Show diagnostic fields and all raw events"),false,value->{diagnostics[0]=value;refresh.run();}).colspan(2);
        dialog.addCloseButton();dialog.show();
    }

    /** M7:区域覆盖便捷入口(优先档手动调度)。与 ZoneSwitchNode 方块面板同源:同一配置通道
     *  `configure((tier<<1)|closed)`;档位在 SafetyNet 重建拍/巡检进入时经 applyOverride 生效。
     *  只读侧全走 SafetyNet 公开门;行显示另按活覆盖档即时解算(D-4x Fix B:快照滞后一拍不显旧值)。
     *  岛/区索引序 = 结算后的岛键/区锚升序。 */
    public static void showZones(){
        if(Vars.headless || Vars.ui==null)return;
        BaseDialog dialog=new BaseDialog(BlackstartText.get("blackstart.zones.title","Zone dispatch"));
        Table rows=new Table();
        Runnable[] refresh=new Runnable[1];    // 自引用(lambda 内写档后刷新)用单元素数组持有
        refresh[0]=() -> {
            rows.clearChildren();
            rows.add(BlackstartText.get("blackstart.zones.help","Same config channel as the zone-switch block panel; a tier applies when the grid rebuilds or is next inspected.")).wrap().width(700).row();
            int islands=SafetyNet.islandCount();
            boolean any=false;
            for(int k=0;k<islands;k++){
                int zones=SafetyNet.islandZoneCount(k);
                if(zones<=0)continue;
                any=true;
                int rep=SafetyNet.islandRepPos(k);
                rows.add(String.format(Locale.ROOT,BlackstartText.get("blackstart.zones.island","Island %d (%d,%d) · %s"),
                    k,Point2.x(rep),Point2.y(rep),BlackstartView.readoutBandWord(SafetyNet.islandBand(k)))).left().width(700).wrap().row();
                for(int z=0;z<zones;z++){
                    int anchor=SafetyNet.islandZoneAnchorAt(k,z);
                    int switches=zoneSwitchCount(anchor);
                    int tier=SafetyNet.islandZoneTierAt(k,z);
                    boolean override=SafetyNet.islandZoneOverrideAt(k,z);
                    int state=SafetyNet.islandZoneStateAt(k,z);
                    // F2 修复(D-4x):SafetyNet 快照只在 settle 重建时刷新 ⇒ 点档后同步 refresh 必读旧值
                    //(复核 verify:ui a1849 全链实证)。显示改走活通道,点后即时可见;无覆盖回落分类快照。
                    ZoneRowDisplay disp=zoneRowDisplay(tier,override,liveZoneOverrideTier(anchor));
                    tier=disp.tier();
                    override=disp.override();
                    String line=String.format(Locale.ROOT,BlackstartText.get("blackstart.zones.zone","Zone (%d,%d) · %s · %s · %d buildings · %d switches"),
                        Point2.x(anchor),Point2.y(anchor),BlackstartView.zoneTierSourceWord(tier,override),
                        BlackstartText.get("blackstart.zones.state."+state,Integer.toString(state)),
                        SafetyNet.islandZoneMembersAt(k,z),switches);
                    Table zoneRow=new Table();
                    zoneRow.add(line).left().padRight(8f);
                    if(switches<=0){
                        zoneRow.add(BlackstartText.get("blackstart.zones.noSwitch","No writable zone switch in this zone")).left();
                    }else{
                        int cur=override?tier:0;
                        for(int t=0;t<4;t++){
                            final int want=t;
                            TextButton btn=zoneRow.button(BlackstartView.zoneTierWord(t),()->{
                                int n=applyZoneTier(anchor,want);
                                BlackstartMod.emit("[bs] zone set ("+Point2.x(anchor)+","+Point2.y(anchor)+") tier="+want+" switches="+n);
                                if(n>0)refresh[0].run();
                            }).size(84f,32f).padRight(2f).get();
                            if(t==cur)btn.setDisabled(true);
                        }
                    }
                    rows.add(zoneRow).left().width(700).wrap().row();
                }
            }
            if(!any)rows.add(BlackstartText.get("blackstart.zones.empty","No classified zones in this world")).row();
        };
        refresh[0].run();
        dialog.cont.pane(rows).width(760).height(450).row();
        dialog.cont.button(BlackstartText.get("blackstart.blackbox.refresh","Refresh"),refresh[0]).width(220);
        dialog.addCloseButton();dialog.show();
    }
    /** M7:锚点所在图的分区开关数(0 ⇒ 本区无档可写;锚建筑不存在/无电同样 0)。 */
    static int zoneSwitchCount(int anchorPos){
        if(Vars.world==null)return 0;
        Building zb=Vars.world.build(anchorPos);
        if(zb==null || zb.power==null || zb.power.graph==null)return 0;
        PowerGraph graph=zb.power.graph;
        int n=0;
        for(var gw:BlackstartLinks.registry){
            if(gw instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z && z.power!=null && z.power.graph==graph)n++;
        }
        return n;
    }
    /** M7:对锚点所在图批量写档。复用 Protection.commandTier 同一配置通道(closed 位原样保留 ⇒ 动档不动闸,
     *  永不触发拒合);返回实际写入的开关数;0 = 无图 / 无开关。 */
    static int applyZoneTier(int anchorPos,int tier){
        if(Vars.world==null)return 0;
        Building zb=Vars.world.build(anchorPos);
        if(zb==null || zb.power==null || zb.power.graph==null)return 0;
        PowerGraph graph=zb.power.graph;
        int n=0;
        for(var gw:BlackstartLinks.registry){
            if(gw instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z && z.power!=null && z.power.graph==graph){
                BlackstartProtection.commandTier(z,tier);
                n++;
            }
        }
        return n;
    }
    /** F2 修复(D-4x):行显示解算(纯函数,测试接缝)。liveOv = 锚图活覆盖档(0=无覆盖)。规则:
     *  有活覆盖 ⇒ 立即按 (liveOv,手动) 显示(与下次重建 applyOverride 将写入的值同源同参);
     *  无活覆盖而快照仍为覆盖态 ⇒ 立即按 (0,自动) 显示(刚点了"自动",快照滞后一拍,
     *  下次重建收敛于分类值);其余 ⇒ 分类快照原值。 */
    record ZoneRowDisplay(int tier,boolean override){}
    static ZoneRowDisplay zoneRowDisplay(int cachedTier,boolean cachedOverride,int liveOv){
        if(liveOv!=0)return new ZoneRowDisplay(liveOv,true);
        if(cachedOverride)return new ZoneRowDisplay(0,false);
        return new ZoneRowDisplay(cachedTier,false);
    }
    /** 活通道:锚图当前开关覆盖档(与 SafetyNet.applyOverride 的 zoneOverrideTier 同源同参;0=无覆盖;
     *  锚建筑缺失/无电同样 0 —— 与 zoneSwitchCount/applyZoneTier 同降级)。 */
    static int liveZoneOverrideTier(int anchorPos){
        if(Vars.world==null)return 0;
        Building zb=Vars.world.build(anchorPos);
        if(zb==null || zb.power==null || zb.power.graph==null)return 0;
        return BlackstartProtection.zoneOverrideTier(zb.power.graph);
    }
}
