package blackstart;

import arc.Core;
import arc.math.geom.Point2;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.gen.Building;

/**
 * T3 断路器:受电侧端点,手动闸 + 过载跳闸 + 有限自动重合。
 * 闸位每拍由 BlackstartProtection 查询;applyCommand 只迁移状态,事件仅从 config/状态机发出。
 * 存档只存命令态,运行态由 writeSync/readSync 覆写对兜底;内嵌 Build 不声明构造器。
 */
public class BreakerNode extends GatewayNode {
    public float tripLoadFrac = 0.95f;
    public float tripDelaySec = 5f;
    public float resetRateMul = 2f;
    public int recloseMax = 1;
    public float recloseDelaySec = 10f;
    public float recloseSuccessSec = 30f;

    public BreakerNode(String name){
        super(name);
        config(Boolean.class, (Building e, Boolean v) -> {
            if(Vars.net != null && Vars.net.client()) return;
            if(e instanceof BreakerNodeBuild b){
                if(v && !(b.closed && b.run == 0) && !(Vars.net != null && Vars.net.client())
                    && BlackstartProtection.tieVerdict(b.tileX(), b.tileY()) > 0){
                    BlackstartProtection.tieFail(b);
                    return;
                }
                boolean old = b.closed;
                b.applyCommand(v);
                if(old != b.closed){
                    BlackstartEvents.push(b.closed ? BlackstartEvents.Kind.BREAKER_CLOSE
                        : BlackstartEvents.Kind.BREAKER_OPEN, b.tileX(), b.tileY(), 0, Float.NaN);
                }
            }
        });
        // 裁定 C:只接管单点哨兵命令,真实链路数组静默忽略。
        config(Point2[].class, (Building e, Point2[] v) -> {
            if(Vars.net != null && Vars.net.client()) return;
            if(v == null || v.length != 1 || v[0] == null) return;
            int cmd = BlackstartProtection.decodePointCommand(v[0].x, v[0].y);
            if(cmd == Integer.MIN_VALUE) return;
            if(e instanceof BreakerNodeBuild b){
                if(cmd != 0 && !(b.closed && b.run == 0) && !(Vars.net != null && Vars.net.client())
                    && BlackstartProtection.tieVerdict(b.tileX(), b.tileY()) > 0){
                    BlackstartProtection.tieFail(b);
                    return;
                }
                boolean old = b.closed;
                b.applyCommand(cmd != 0);
                if(old != b.closed){
                    BlackstartEvents.push(b.closed ? BlackstartEvents.Kind.BREAKER_CLOSE
                        : BlackstartEvents.Kind.BREAKER_OPEN, b.tileX(), b.tileY(), 0, Float.NaN);
                }
            }
        });
    }

    /** 内嵌 Build 沿用 v8 隐式外层实例构造器约定。 */
    public class BreakerNodeBuild extends GatewayNodeBuild implements BsVisualData {
        public boolean closed = true;
        public byte run = 0; // 0 合闸 / 2 分闸 / 3 锁定 / 4 冷却
        public float overSec, coolSec, successSec;
        public int used;
        public float warnAt = Float.NEGATIVE_INFINITY;
        public float tieWarnAt = Float.NEGATIVE_INFINITY;

        @Override public byte version(){ return MOD_REV; }

        @Override public void write(Writes w){
            super.write(w);
            w.bool(closed);
        }

        @Override public void read(Reads r, byte rev){
            super.read(r, rev);
            // 与父类丢弃门同源;MOD_REV 升级时须按各旧 revision 布局分支读取。
            if(rev < MOD_REV) return;
            applyCommand(r.bool());
        }

        @Override public void writeSync(Writes w){
            super.writeSync(w);
            w.bool(closed);
            w.b(writeRunFlags());
            w.f(coolSec);
            w.f(overSec);
        }

        @Override public void readSync(Reads r, byte rev){
            super.readSync(r, rev);
            closed = r.bool();
            applyRunFlags(r.b());
            coolSec = r.f();
            overSec = r.f();
        }

        /** bit0–1:run(0/2/3/4);bit2–3:已用次数(0–2)。 */
        public byte writeRunFlags(){
            int code = run == 2 ? 1 : run == 3 ? 2 : run == 4 ? 3 : 0;
            return (byte)(code | ((used & 3) << 2));
        }

        public void applyRunFlags(byte flags){
            int code = flags & 3;
            run = (byte)(code == 1 ? 2 : code == 2 ? 3 : code == 3 ? 4 : 0);
            used = (flags >> 2) & 3;
        }

        @Override public Point2[] config(){
            return new Point2[]{ new Point2(closed ? 1 : 0, BlackstartProtection.CONFIG_SENTINEL_Y) };
        }

        @Override public boolean configTapped(){
            configure(!(closed && run == 0));
            return false;
        }

        @Override public boolean onConfigureBuildTapped(Building other){
            if(other == this){
                configTapped();
                deselect();
                return false;
            }
            if(Vars.ui != null){
                float now = Vars.state == null ? 0f : (float)Vars.state.tick;
                if(now < warnAt || now - warnAt >= 600f){
                    warnAt = now;
                    BlackstartView.showNotice(Core.bundle.get("blackstart.prot.noOutEdge"));
                }
            }
            return true;
        }

        /** 纯状态迁移,零事件;读档可安全复用。 */
        public void applyCommand(boolean v){
            BlackstartContinuity.command(this);
            closed = v; used = 0; coolSec = 0f; overSec = 0f; successSec = 0f; run = v ? (byte)0 : (byte)2;
        }

        @Override public float bsLinkFlow(int i){ return Float.NaN; }
        @Override public float bsLinkCapacity(int i){ return Float.NaN; }
        @Override public float bsLinkLossPct(int i){ return Float.NaN; }
        @Override public int bsLinkState(int i){ return -1; }
        @Override public int bsSwitchState(){ return run; }
        @Override public float bsRecloseProgress(){ return run == 4 ? coolSec / recloseDelaySec : Float.NaN; }
        @Override public int bsRecloseLeft(){ return run == 3 ? 0 : Math.max(0, recloseMax - used); }
        @Override public int bsDeviceKind(){ return 0; }
        // 017:转发本实例所在图的当拍入链限流真值。
        @Override public boolean bsGraphThrottled(){ return BlackstartLinks.graphThrottled(power == null ? null : power.graph); }
    }
}
