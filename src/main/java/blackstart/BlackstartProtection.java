package blackstart;

import arc.Core;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGraph;

import java.util.Locale;

/**
 * T3 静态入口:电平式闸位、原生子图总闸/档聚合、结算拍断路器状态机与探针辅助。
 * 结算/查询零稳态分配;不写链路开断位或原生电力结构,只迁移本族装置运行态。
 * 命令辅助统一走 configure,事件仍由装置 config handler 发出。
 */
public final class BlackstartProtection {
    // 裁定 C 冻结:装置 config() 读出哨兵 y。
    public static final int CONFIG_SENTINEL_Y = Short.MIN_VALUE;
    private static double lastTick = Double.NaN;

    private BlackstartProtection(){
    }
    static void resetClock(){lastTick=Double.NaN;}

    /** 017:蓝图旋转后的四种哨兵形态,依冻结判形顺序还原;未命中静默返回MIN_VALUE。 */
    public static int decodePointCommand(int x, int y){
        if(y == CONFIG_SENTINEL_Y) return x;
        if(y == -CONFIG_SENTINEL_Y) return -x;
        if(x == -CONFIG_SENTINEL_Y) return y;
        if(x == CONFIG_SENTINEL_Y) return -y;
        return Integer.MIN_VALUE;
    }

    public static boolean linkGateOpen(Building owner, Building target){
        return linkGateReason(owner, target) == 0;
    }

    public static int linkGateReason(Building owner, Building target){
        int reason = deviceReason(owner);
        if(reason != 0) return reason;
        reason = deviceReason(target);
        if(reason != 0) return reason;
        if(zoneCut(graphOf(owner))) return 2;
        if(zoneCut(graphOf(target))) return 2;
        return 0;
    }

    private static int deviceReason(Building b){
        if(b instanceof BreakerNode.BreakerNodeBuild br){
            if(br.run == 3) return 3;
            if(br.run == 4) return 4;
            if(!br.closed) return 2;
        }else if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z && !z.closed){
            return 2;
        }
        return 0;
    }

    private static PowerGraph graphOf(Building b){
        return b == null || b.power == null ? null : b.power.graph;
    }

    public static boolean zoneCut(PowerGraph graph){
        if(graph == null) return false;
        for(int i = 0; i < BlackstartLinks.registry.size; i++){
            GatewayNode.GatewayNodeBuild b = BlackstartLinks.registry.get(i);
            if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z
                && z.power != null && z.power.graph == graph && !z.closed) return true;
        }
        return false;
    }

    public static int zoneOverrideTier(PowerGraph graph){
        if(graph == null) return 0;
        int tier = NativeNodeZones.priority(graph);
        for(int i = 0; i < BlackstartLinks.registry.size; i++){
            GatewayNode.GatewayNodeBuild b = BlackstartLinks.registry.get(i);
            if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z
                && z.power != null && z.power.graph == graph && z.tier > tier) tier = z.tier;
        }
        return tier;
    }

    /** Shared first rejection. High word keeps the legacy rAll payload, never Hz. */
    static int tieVerdict(int tileX, int tileY){
        long cause = tieCause(tileX, tileY);
        return cause == -1L ? -1 : (int)(cause & 15L);
    }

    static int tieWords(int tileX, int tileY){
        long cause = tieCause(tileX, tileY);
        return cause == -1L ? 0 : (int)((cause >>> 4) & 15L);
    }

    static float tieRejectAbsDelta(int tileX, int tileY){
        long cause = tieCause(tileX, tileY);
        return cause == -1L || cause == 0L ? Float.NaN : Float.intBitsToFloat((int)(cause >>> 32));
    }

    /** 合闸因由原始字(M4 起包内可见:ProtectionExplanation.capture 取因由、旁表经遥测过网)。 */
    static long tieCause(int tileX, int tileY){
        Building self = deviceAt(tileX, tileY);
        boolean candidate = false;
        if(self instanceof BreakerNode.BreakerNodeBuild){
                // A command can arrive before the first settlement receipt exists.
                for(var gw : BlackstartLinks.registry){
                    int index = -1;
                    for(int i = 0; i < gw.modLinks.size; i++){
                        if(gw.modLinks.get(i) == self.pos()){ index = i; break; }
                    }
                    if(!tieCandidate(gw, self, index, self)) continue;
                    candidate = true;
                    long cause = pairCause(self, gw);
                    if(cause != 0L) return cause;
                }
        }else if(self instanceof ZoneSwitchNode.ZoneSwitchNodeBuild){
            PowerGraph graph = graphOf(self);
            for(int f = 0; f < BlackstartLinks.registry.size; f++){
                GatewayNode.GatewayNodeBuild gw = BlackstartLinks.registry.get(f);
                for(int i = 0; i < gw.modLinks.size; i++){
                    Building peer = Vars.world.build(gw.modLinks.get(i));
                    if(!(peer instanceof GatewayNode.GatewayNodeBuild)) continue;
                    PowerGraph a = graphOf(gw), b = graphOf(peer);
                    if(a == b || ((a == graph) == (b == graph))) continue;
                    if(!tieCandidate(gw, peer, i, self)) continue;
                    candidate = true;
                    long cause = a == graph ? pairCause(gw, peer) : pairCause(peer, gw);
                    if(cause != 0L) return cause;
                }
            }
        }
        return candidate ? 0L : -1L;
    }

    private static boolean tieCandidate(GatewayNode.GatewayNodeBuild gw, Building peer, int i, Building self){
        if(gw.tile == null || Vars.world.build(gw.pos()) != gw || !gw.modLinkValid(peer.pos(), gw.team)) return false;
        if(linkGateReason(gw, peer) == 0) return false;
        if(i < 0 || i >= gw.linkOpen.length || !gw.linkOpen[i]) return false;
        if(deviceReason(gw) != 0 && gw != self) return false;
        if(deviceReason(peer) != 0 && peer != self) return false;
        return !zoneCutExcluding(graphOf(gw), self) && !zoneCutExcluding(graphOf(peer), self);
    }

    private static boolean zoneCutExcluding(PowerGraph graph, Building self){
        if(graph == null) return false;
        for(int i = 0; i < BlackstartLinks.registry.size; i++){
            GatewayNode.GatewayNodeBuild b = BlackstartLinks.registry.get(i);
            if(b == self) continue;
            if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z
                && z.power != null && z.power.graph == graph && !z.closed) return true;
        }
        return false;
    }

    private static long pairCause(Building self, Building peer){
        SyncPolicy.Decision decision = BlackstartSync.pair(self.pos(), peer.pos());
        if(decision.allowed()) return 0L;
        var first = BlackstartDynamics.snapshotAt(self.pos());
        var second = BlackstartDynamics.snapshotAt(peer.pos());
        double fa = first == null ? Double.NaN : first.frequencyHz();
        double fb = second == null ? Double.NaN : second.frequencyHz();
        int words = (fa < 50-SyncPolicy.BAND_HZ ? 1 : fa > 50+SyncPolicy.BAND_HZ ? 2 : 0)
            | (fb < 50-SyncPolicy.BAND_HZ ? 4 : fb > 50+SyncPolicy.BAND_HZ ? 8 : 0);
        // Preserve TIE_FAIL's old dimensionless data; no old violation is represented by NaN.
        int a = SafetyNet.islandAt(self.pos()), b = SafetyNet.islandAt(peer.pos());
        float da = a < 0 ? Float.NaN : SafetyNet.islandRAll(a), db = b < 0 ? Float.NaN : SafetyNet.islandRAll(b);
        boolean badA = Math.abs(da) > SafetyNet.TIE_WINDOW, badB = Math.abs(db) > SafetyNet.TIE_WINDOW;
        float delta = badA || badB ? Math.max(badA ? Math.abs(da) : 0f, badB ? Math.abs(db) : 0f) : Float.NaN;
        return ((long)Float.floatToIntBits(delta) << 32) | ((long)(decision.reason().ordinal()+1) << 8) | (words << 4) | 1L;
    }

    static String tieExplanation(int x, int y){
        return tieCauseText(tieCause(x,y));
    }

    /** 玩家面因由短句(M4):哨兵/空因由(cause 0/-1)时以 tie.unknownReason 兜底,不留空括号。 */
    static String tieExplainOrUnknown(int x, int y){
        String text = tieExplanation(x, y);
        return text.isEmpty() ? BlackstartText.get("blackstart.tie.unknownReason", "原因未判定") : text;
    }

    /**
     * 因由字 → 玩家短句(原因词 + 方向词;cause 0/-1 → 空串)。
     * 从 tieExplanation 抽出(M4):客户端无原始 cause 生成链,仅凭随帧携带的 cause 现推同一句;
     * 宿主路径 tieCauseText(tieCause(x,y)) 与原 tieExplanation 逐字等价。
     */
    static String tieCauseText(long cause){
        if(cause == 0 || cause == -1) return "";
        int reason = (int)((cause >>> 8) & 255)-1;
        SyncPolicy.Reason value = reason >= 0 && reason < SyncPolicy.Reason.values().length ? SyncPolicy.Reason.values()[reason] : SyncPolicy.Reason.UNKNOWN;
        String text = BlackstartText.get("blackstart.sync." + value.name().toLowerCase(Locale.ROOT), value.name());
        String direction = tieWordText((int)((cause >>> 4) & 15));
        return direction.isEmpty() ? text : text + " (" + direction + ")";
    }

    /** 仅命令/5Hz 悬浮路径转词,绘制帧不分配。 */
    static String tieWordText(int words){
        StringBuilder text = new StringBuilder();
        for(int i = 0; i < 4; i++){
            if((words & (1 << i)) == 0) continue;
            if(text.length() > 0) text.append('·');
            String key = i == 0 ? "selfSlow" : i == 1 ? "selfFast" : i == 2 ? "peerSlow" : "peerFast";
            String fallback = i == 0 ? "本端偏慢" : i == 1 ? "本端偏快" : i == 2 ? "对端偏慢" : "对端偏快";
            text.append(Core.bundle == null ? fallback : Core.bundle.get("blackstart.tie." + key, fallback));
        }
        return text.toString();
    }

    static void tieFail(Building b){
        BlackstartEvents.push(BlackstartEvents.Kind.TIE_FAIL, b.tileX(), b.tileY(), 0,
            tieRejectAbsDelta(b.tileX(), b.tileY()) * 100f);
        recordSyncRejection(b);
        tieWarning(b, false);
    }

    private static void recordSyncRejection(Building b){
        long cause=tieCause(b.tileX(),b.tileY());
        int reason=(int)((cause>>>8)&255)-1;
        if(cause<=0 || reason<0 || reason>=SyncPolicy.Reason.values().length)reason=SyncPolicy.Reason.UNKNOWN.ordinal();
        var snapshot=BlackstartDynamics.snapshotAt(b.pos());
        BlackstartEvents.push(BlackstartEvents.Kind.SYNC_REJECT,b.tileX(),b.tileY(),reason,
            snapshot==null?Float.NaN:(float)snapshot.frequencyHz());
    }

    private static void tieWarning(Building b, boolean hold){
        float now = Vars.state == null ? 0f : (float)Vars.state.tick;
        float at = b instanceof BreakerNode.BreakerNodeBuild br ? br.tieWarnAt
            : ((ZoneSwitchNode.ZoneSwitchNodeBuild)b).tieWarnAt;
        if(!(now < at || now - at >= 600f)) return;
        if(b instanceof BreakerNode.BreakerNodeBuild br) br.tieWarnAt = now;
        else ((ZoneSwitchNode.ZoneSwitchNodeBuild)b).tieWarnAt = now;
        // M4:{0} = 因由短句(空因由兜底 tie.unknownReason),{1} = 装置 "x,y";"(装置 )"后缀文本随模板走,可本地化。
        String fallback = hold ? "[电网] 窗口不匹配,放弃自动重合({0}) (装置 {1})"
            : "[电网] 合闸失败:两侧不在窗口({0}),平衡后再并网 (装置 {1})";
        String template = Core.bundle == null ? fallback : Core.bundle.get("blackstart.tie." + (hold ? "hold" : "fail"), fallback);
        // M4:玩家面出口(浮字恒发;日志行 `[bs] ` 前缀由 emitPlayer 补,F1 例外见台账 D-46)。
        BlackstartMod.emitPlayer(template.replace("{0}", tieExplainOrUnknown(b.tileX(), b.tileY()))
            .replace("{1}", b.tileX() + "," + b.tileY()));
    }

    /** T2 当拍回执发布后恰一次调用;全部计时用对局 tick 差,无新节拍。 */
    static void pass(){
        if(Vars.net != null && Vars.net.client()) return;
        if(Vars.state == null) return;
        double now = Vars.state.tick;
        float dt = Double.isNaN(lastTick) ? 0f : (float)Math.max(0d, (now - lastTick) / 60d);
        boolean device = false;
        for(int i = 0; i < BlackstartLinks.registry.size; i++){
            GatewayNode.GatewayNodeBuild gw = BlackstartLinks.registry.get(i);
            if(gw instanceof ZoneSwitchNode.ZoneSwitchNodeBuild) device = true;
            if(!(gw instanceof BreakerNode.BreakerNodeBuild b)) continue;
            device = true;
            BreakerNode block = (BreakerNode)b.block;
            Seq<BlackstartLinks.ServingLink> feed = BlackstartLinks.servingOf(b.pos());
            boolean overloaded = false;
            float load = 0f;
            if(feed != null){
                for(int f = 0; f < feed.size; f++){
                    BlackstartLinks.ServingLink s = feed.get(f);
                    if(s.state == 1 && s.flow >= block.tripLoadFrac * s.capacity){
                        overloaded = true;
                        load = Math.max(load, s.flow / s.capacity);
                    }
                }
            }
            if(overloaded) b.overSec += dt;
            else b.overSec = Math.max(0f, b.overSec - block.resetRateMul * dt);
            if(b.run == 0 && b.closed && b.overSec >= block.tripDelaySec){
                b.overSec = 0f;
                b.successSec = 0f;
                BlackstartEvents.push(BlackstartEvents.Kind.TRIP, b.tileX(), b.tileY(), 0, load * 100f);
                if(block.recloseMax >= 1 && b.used < block.recloseMax){
                    b.run = 4;
                    b.coolSec = block.recloseDelaySec;
                }else{
                    b.run = 3;
                    BlackstartEvents.push(BlackstartEvents.Kind.RECLOSE_FAIL, b.tileX(), b.tileY(), 0, Float.NaN);
                }
            }
            if(b.run == 4){
                b.coolSec -= dt;
                if(b.coolSec <= 0f){
                    if(!(Vars.net != null && Vars.net.client()) && tieVerdict(b.tileX(), b.tileY()) > 0){
                        int reason=(int)((tieCause(b.tileX(),b.tileY())>>>8)&255)-1;
                        if(BlackstartContinuity.stabilizing() && (reason==SyncPolicy.Reason.UNKNOWN.ordinal()
                            || reason==SyncPolicy.Reason.STABILIZING.ordinal())){
                            // Cooling still expires; a missing fresh permit cannot authorize closure.
                            // Defer only the failure verdict, bounded by the load window.
                            b.coolSec=0;b.successSec=0;continue;
                        }
                        b.run = 3;
                        b.coolSec = 0f;
                        b.successSec = 0f;
                        BlackstartEvents.push(BlackstartEvents.Kind.RECLOSE_FAIL, b.tileX(), b.tileY(), 0, Float.NaN);
                        tieWarning(b, true);
                        recordSyncRejection(b);
                    }else{
                        b.run = 0;
                        b.used++;
                        b.coolSec = 0f;
                        b.successSec = 0f;
                        BlackstartEvents.push(BlackstartEvents.Kind.RECLOSE, b.tileX(), b.tileY(), b.used, Float.NaN);
                    }
                }
            }
            if(b.run == 0){
                b.successSec += dt;
                if(b.used > 0 && b.successSec >= block.recloseSuccessSec) b.used = 0;
            }else{
                b.successSec = 0f;
            }
        }
        lastTick = device ? now : Double.NaN;
    }

    /** 坐标读取保留 world.tile 的越界语义;仅命令路径使用。 */
    static Building deviceAt(int x, int y){
        if(Vars.world == null) return null;
        Tile tile = Vars.world.tile(x, y);
        Building b = tile == null ? null : tile.build;
        return b instanceof BreakerNode.BreakerNodeBuild || b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild ? b : null;
    }

    static Object commandValue(Building b){
        if(b instanceof BreakerNode.BreakerNodeBuild br) return br.closed;
        if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z) return z.commandValue();
        return null;
    }

    static void commandClosed(Building b, boolean closed){
        if(b instanceof BreakerNode.BreakerNodeBuild) b.configure(closed);
        else if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z) b.configure((z.tier << 1) | (closed ? 1 : 0));
    }

    static void commandTier(Building b, int tier){
        if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z){
            // 域外交内核 fallback/WARN;避免整数移位溢出折回合法编码。
            b.configure(tier >= 0 && tier <= 3 ? (tier << 1) | (z.closed ? 1 : 0) : -1);
        }
    }

    static void dump(){
        int count = 0;
        for(int i = 0; i < BlackstartLinks.registry.size; i++){
            String line = dumpLine(BlackstartLinks.registry.get(i));
            if(line != null){
                BlackstartMod.emit(line);
                count++;
            }
        }
        if(count == 0) BlackstartMod.emit("[bs] PROT none");
    }

    /** 探针格式冻结;字符串分配只发生在手动命令路径。 */
    static String dumpLine(Building b){
        if(b instanceof BreakerNode.BreakerNodeBuild br){
            BreakerNode block = (BreakerNode)br.block;
            StringBuilder line = new StringBuilder("[bs] PROT (");
            line.append(b.tileX()).append(',').append(b.tileY()).append(") kind=breaker state=")
                .append(br.run).append(" closed=").append(br.closed).append(" overload=")
                .append(fmt(br.overSec)).append('/').append(fmt(block.tripDelaySec))
                .append(" reclose=").append(br.used).append('/').append(block.recloseMax)
                .append(" cool=").append(fmt(br.coolSec)).append(" feed=[");
            Seq<BlackstartLinks.ServingLink> feed = BlackstartLinks.servingOf(b.pos());
            if(feed != null){
                for(int i = 0; i < Math.min(4, feed.size); i++){
                    if(i > 0) line.append(',');
                    BlackstartLinks.ServingLink s = feed.get(i);
                    line.append('(').append(s.ownerPos >> 16).append(',').append(s.ownerPos & 0xffff)
                        .append(',').append(fmt(s.flow)).append(',').append(fmt(s.capacity))
                        .append(',').append(s.state).append(')');
                }
            }
            return line.append(']').toString();
        }
        if(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild z){
            PowerGraph graph = graphOf(b);
            StringBuilder line = new StringBuilder("[bs] PROT (");
            line.append(b.tileX()).append(',').append(b.tileY()).append(") kind=zone tier=")
                .append(z.tier).append(" closed=").append(z.closed).append(" zone=[");
            int count = 0;
            if(graph != null){
                for(int i = 0; i < BlackstartLinks.registry.size; i++){
                    Building gw = BlackstartLinks.registry.get(i);
                    if(graphOf(gw) != graph) continue;
                    if(count++ > 0) line.append(',');
                    line.append('(').append(gw.tileX()).append(',').append(gw.tileY()).append(')');
                }
            }
            return line.append("] cut=").append(zoneCut(graph)).append(" n=").append(count).toString();
        }
        return null;
    }

    private static String fmt(float v){ return String.format(Locale.ROOT, "%.1f", v); }
}
