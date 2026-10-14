package blackstart;

import arc.Core;
import arc.math.geom.Point2;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGraph;

/**
 * T3 分区开关:原生子图的优先级覆盖与总闸,不拥有出线。
 * 编码=(tier<<1)|closed,命令态走存档/config;电平式闸位与档聚合在 Protection。
 * applyCommand 零事件;无运行态,快照沿用父类;内嵌 Build 不声明构造器。
 */
public class ZoneSwitchNode extends GatewayNode {
    public ZoneSwitchNode(String name){
        super(name);
        // 本块 Integer 键替换父类 ensure 处理器,只表达档位与开合命令。
        config(Integer.class, (Building e, Integer v) -> {
            if(Vars.net != null && Vars.net.client()) return;
            if(e instanceof ZoneSwitchNodeBuild z){
                if((v < 0 || v > 7 || (v & 1) != 0) && !z.closed && !(Vars.net != null && Vars.net.client())
                    && BlackstartProtection.tieVerdict(z.tileX(), z.tileY()) > 0){
                    BlackstartProtection.tieFail(z);
                    return;
                }
                int oldTier = z.tier;
                boolean oldClosed = z.closed;
                z.applyCommand(v);
                if(oldTier != z.tier){
                    BlackstartEvents.push(BlackstartEvents.Kind.ZONE_SET, z.tileX(), z.tileY(), z.tier, Float.NaN);
                }
                if(oldClosed != z.closed){
                    BlackstartEvents.push(z.closed ? BlackstartEvents.Kind.BREAKER_CLOSE
                        : BlackstartEvents.Kind.BREAKER_OPEN, z.tileX(), z.tileY(), 0, Float.NaN);
                }
            }
        });
        // 裁定 C:只接管单点哨兵命令,真实链路数组静默忽略。
        config(Point2[].class, (Building e, Point2[] v) -> {
            if(Vars.net != null && Vars.net.client()) return;
            if(v == null || v.length != 1 || v[0] == null) return;
            int cmd = BlackstartProtection.decodePointCommand(v[0].x, v[0].y);
            if(cmd == Integer.MIN_VALUE) return;
            if(e instanceof ZoneSwitchNodeBuild z){
                if((cmd < 0 || cmd > 7 || (cmd & 1) != 0) && !z.closed && !(Vars.net != null && Vars.net.client())
                    && BlackstartProtection.tieVerdict(z.tileX(), z.tileY()) > 0){
                    BlackstartProtection.tieFail(z);
                    return;
                }
                int oldTier = z.tier;
                boolean oldClosed = z.closed;
                z.applyCommand(cmd);
                if(oldTier != z.tier){
                    BlackstartEvents.push(BlackstartEvents.Kind.ZONE_SET, z.tileX(), z.tileY(), z.tier, Float.NaN);
                }
                if(oldClosed != z.closed){
                    BlackstartEvents.push(z.closed ? BlackstartEvents.Kind.BREAKER_CLOSE
                        : BlackstartEvents.Kind.BREAKER_OPEN, z.tileX(), z.tileY(), 0, Float.NaN);
                }
            }
        });
    }

    /** 内嵌 Build 沿用 v8 隐式外层实例构造器约定。 */
    public class ZoneSwitchNodeBuild extends GatewayNodeBuild implements BsVisualData {
        public int tier = 0;
        public boolean closed = true;
        public float warnAt = Float.NEGATIVE_INFINITY;
        public float tieWarnAt = Float.NEGATIVE_INFINITY;

        @Override public byte version(){ return MOD_REV; }

        @Override public void write(Writes w){
            super.write(w);
            w.i(commandValue());
        }

        @Override public void read(Reads r, byte rev){
            super.read(r, rev);
            // 与父类丢弃门同源;MOD_REV 升级时须按各旧 revision 布局分支读取。
            if(rev < MOD_REV) return;
            applyCommand(r.i());
        }

        @Override public Point2[] config(){
            return new Point2[]{ new Point2(commandValue(), BlackstartProtection.CONFIG_SENTINEL_Y) };
        }

        @Override public boolean onConfigureBuildTapped(Building other){
            if(other == this) return false;
            if(Vars.ui != null){
                float now = Vars.state == null ? 0f : (float)Vars.state.tick;
                if(now < warnAt || now - warnAt >= 600f){
                    warnAt = now;
                    BlackstartView.showNotice(Core.bundle.get("blackstart.prot.noOutEdge"));
                }
            }
            return true;
        }

        /** 命令态落值与 epoch 刷新;零事件,读档可安全复用。 */
        public void applyCommand(int value){
            if(value < 0 || value > 7){
                BlackstartMod.emit("[bs] zoneswitch 非法编码 " + value + " " + BlackstartText.get("blackstart.text.flow", ">") + " fallback(2,closed)");
                tier = 2; closed = true;
            }else{
                tier = (value >> 1) & 0x3; closed = (value & 1) != 0;
            }
            BlackstartLinks.markDirty();
        }

        public int commandValue(){ return (tier << 1) | (closed ? 1 : 0); }

        @Override public void buildConfiguration(Table table){
            table.label(() -> Core.bundle.get("block.blackstart-zone-switch.name") + " "
                + zoneAnchor()).colspan(4).row();
            table.label(() -> Core.bundle.get(closed ? "blackstart.prot.breakerState.closed"
                : "blackstart.prot.zoneOffline")).colspan(4).row();
            String[] keys = {"auto", "discard", "prod", "defense"};
            for(int i = 0; i < keys.length; i++){
                final int selected = i;
                TextButton button = table.button(Core.bundle.get("blackstart.prot.tier." + keys[i]),
                    () -> configure((selected << 1) | (closed ? 1 : 0))).size(100f, 40f).get();
                button.update(() -> button.setChecked(tier == selected));
            }
            table.row();
            table.label(() -> {
                int n = otherSwitches();
                return n > 0 ? Core.bundle.get("blackstart.prot.multiSwitch").replace("{0}", Integer.toString(n)) : "";
            }).colspan(4).row();
            table.add(Core.bundle.get("blackstart.prot.tierAuto")).colspan(4).row();
            table.button(Core.bundle.get("blackstart.prot.breakerState.closed"),
                () -> configure((tier << 1) | 1)).colspan(2).size(200f, 40f);
            table.button(Core.bundle.get("blackstart.prot.breakerState.open"),
                () -> configure(tier << 1)).colspan(2).size(200f, 40f);
        }

        /** 面板显示锚=本图全部建筑最小 packed,不参与逻辑。 */
        private String zoneAnchor(){
            PowerGraph graph = power == null ? null : power.graph;
            int anchor = pos();
            if(graph != null && graph.all != null){
                for(int i = 0; i < graph.all.size; i++){
                    Building b = graph.all.get(i);
                    if(b != null && b.pos() < anchor) anchor = b.pos();
                }
            }
            return "(" + (anchor >> 16) + "," + (anchor & 0xffff) + ")";
        }

        private int otherSwitches(){
            PowerGraph graph = power == null ? null : power.graph;
            if(graph == null) return 0;
            int count = 0;
            for(int i = 0; i < BlackstartLinks.registry.size; i++){
                GatewayNodeBuild b = BlackstartLinks.registry.get(i);
                if(b != this && b instanceof ZoneSwitchNodeBuild && b.power != null && b.power.graph == graph) count++;
            }
            return count;
        }

        @Override public float bsLinkFlow(int i){ return Float.NaN; }
        @Override public float bsLinkCapacity(int i){ return Float.NaN; }
        @Override public float bsLinkLossPct(int i){ return Float.NaN; }
        @Override public int bsLinkState(int i){ return -1; }
        @Override public int bsSwitchState(){ return closed ? 0 : 2; }
        @Override public float bsRecloseProgress(){ return Float.NaN; }
        @Override public int bsRecloseLeft(){ return 0; }
        @Override public int bsDeviceKind(){ return 1; }
        // 017:转发本实例所在图的当拍入链限流真值。
        @Override public boolean bsGraphThrottled(){ return BlackstartLinks.graphThrottled(power == null ? null : power.graph); }
    }
}
