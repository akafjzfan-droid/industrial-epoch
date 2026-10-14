package blackstart;

import arc.Core;
import arc.Events;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.meta.BlockGroup;

import java.util.Comparator;
import java.util.Locale;

/**
 * T4 结算内核(静态类):岛聚合 + 自动分类 + 缺口率 + 岛级状态机
 * (NORMAL/WATCH/SHEDDING/RESTORING;甩/复动作面 015 接入)。
 *
 * 职责:按 {@link BlackstartLinks#dirtyEpoch} 比对在脏拍重建岛集(与 T2 同一搬运链路谓词),
 * 给岛内每个区按自动分类规则定档(防御 3 / 生产 2 / 可弃 1,区内取最高、兜底 2),
 * 从 T2 按岛回执({@link BlackstartLinks.Stats})算缺口率 r,并驱动岛级状态机 NORMAL↔WATCH→SHEDDING↔RESTORING
 * (预警/甩/复文案落 bundle,浮窗/日志各 1 行)。动作面:甩/复已接(015,仅动作边缘恰调 setGrant);tier 写回不做(T3/T4 回归后议)。
 *
 * 红线遵守(红线 20/21/23;按 D61 修订):
 *   · 稳态零扫描:epoch 未变不重建、不遍历注册表/链路/consumers/graph.all;区锚与分类只在
 *     重建拍(dirtyEpoch 变化)或进入 WATCH 刷新拍重算(对齐自愈例外见 D41)。例外:甩/复动作边缘拍
 *     (仅动作路径枚举本岛成员链路)与 SHEDDING/RESTORING 稀有态的拍内 zones 遍历。
 *   · 零写入:除甩/复动作路径恰调 setGrant 外,对 T2 零写入(无 setTier/openLink/tune*、不直写 dirty);
 *     对世界零写入(不 setNet、不碰方块);只读 dirtyEpoch/registry/modLinks/linkOpen/Stats。
 *   · δ 口径:一切持续时长用 Time.delta 累加(60 = 1 秒),禁墙钟。
 *
 * 出处:// T4 设计§1.3/§1.5/§1.6/§1.7/§1.8 / // 合本§二-2.3-①③④⑤。
 *
 * 头注②(headless 反转纪律):本类禁用任何 UI / 绘制 / 渲染 / 贴图 / 应用单例类(照 BlackstartEvents 头注②);
 * {@link #install()} 【无】headless 早退,无头下也必须真实生效(装具 bs-zones 依赖它)。
 */
public final class SafetyNet {

    // ===== 常量(设计 §1.6;本批只落用到的)=====

    /** 结算节拍(tick;6 = 10Hz)。 */
    private static final float SETTLE_PERIOD = 6f;
    /** 预警缺口率阈值。 */
    private static final float R_WATCH = 0.08f;
    /** 连续超阈才进 WATCH 的时长(ticks;1.5s)。 */
    private static final float T_WATCH = 1.5f * 60f;
    /** 连续低于阈值才回 NORMAL 的时长(ticks;3s)。 */
    private static final float T_CALM = 3f * 60f;
    /** 除法保护极小量(与 BlackstartLinks 同值同口径;此处 SafetyNet 自有私有常量)。 */
    private static final float EPS = 0.0001f;

    // 018:稳定度秒级规格;只有 τ 的积分把 tick 转秒,原控制律常量不变。
    private static final float T_TAU = 4f, K_R = 1f, K_STEP = 2f, TAU_WATCH = 0.35f;
    private static final float TOPOLOGY_PULSE = 0.35f, CUT_PULSE = 0.35f, RESTORE_PULSE = 0.15f;
    private static final float BAND_YELLOW_UP = 0.40f, BAND_YELLOW_DOWN = 0.32f;
    private static final float BAND_RED_UP = 0.75f, BAND_RED_DOWN = 0.65f;
    public static final float TIE_WINDOW = 0.3f;

    // ===== 015 甩/复常量(设计 §1.6;单区「秒」一律 ×60f 存 tick 值)=====

    /** 甩负荷缺口率阈值(D50/D51)。 */
    private static final float R_SHED = 0.15f;
    /** 从进 WATCH 起算到第一刀(ticks;4s)。 */
    private static final float T_SHED = 4f * 60f;
    /** 逐档下切步进(ticks;2s)。 */
    private static final float T_STEP = 2f * 60f;
    /** 防御档前警告等待(ticks;3s)。 */
    private static final float T_DEFENSE_WARN = 3f * 60f;
    /** 恢复盈余阈值(D50/D53)。 */
    private static final float R_RECOVER = 0.05f;
    /** 盈余持续(ticks;5s)。 */
    private static final float T_RECOVER = 5f * 60f;
    /** 恢复步进(ticks;1.5s)。 */
    private static final float T_RESTORE_STEP = 1.5f * 60f;
    /** 恢复预演算余量(D53)。 */
    private static final float R_MARGIN = 0.02f;
    /** 单区冷却(ticks;10s)。 */
    private static final float T_ZONE_COOL = 10f * 60f;
    /** 振荡窗(ticks;10min;long,D59)。 */
    private static final long OSC_WINDOW = 600L * 60L;
    /** 窗内翻转阈值(含当次;D54)。 */
    private static final int OSC_FLIPS = 3;
    /** 退避序列(秒;D54)。 */
    private static final int[] BACKOFF_SEC = {30, 60, 120, 300};

    /** 岛状态枚举(NORMAL/WATCH/SHEDDING/RESTORING;D51/D53)。 */
    private static final byte NORMAL = 0, WATCH = 1, SHEDDING = 2, RESTORING = 3;

    /** 区状态枚举(独立一域,与岛态隔离禁混用;D54)。 */
    private static final byte Z_NORMAL = 0, Z_SHED = 1, Z_BACKOFF = 2;

    /** WATCH 预警字面回退模板(zh;无头 Core.bundle == null 时用;M4:{4} = 岛 "x,y")。 */
    private static final String WARN_FALLBACK = "[电网] 供电缺口 {0}({1}/{2}),持续 {3} 秒将开始按档停电(防御最后) (岛 {4})";

    /** 甩负荷字面回退模板(zh;取值 = 设计 §3.2 文案区,D58;M4:{2} = 岛 "x,y")。 */
    private static final String CUT_FALLBACK = "[电网] 缺口未恢复:已停止向分区 {0}({1}档)供电 (岛 {2})";
    /** 防御档警告字面回退模板(zh;D58;M4:{1} = 岛 "x,y")。 */
    private static final String DEFENSE_FALLBACK = "[电网] 仅剩防御档:缺口不恢复将在 {0} 秒后停防御 (岛 {1})";
    /** 复电字面回退模板(zh;D58;M4:{1} = 岛 "x,y")。 */
    private static final String RESTORE_FALLBACK = "[电网] 盈余恢复:正在逐区复电:分区 {0} (岛 {1})";
    /** 振荡锁定字面回退模板(zh;D58;M4:{2} = 岛 "x,y")。 */
    private static final String LOCKED_FALLBACK = "[电网] 分区 {0} 反复启停,已暂停自动复电({1} 秒后重试) (岛 {2})";
    /** 卸无可卸字面回退模板(zh;D58;M4:{1} = 岛 "x,y")。 */
    private static final String NOTHING_FALLBACK = "[电网] 缺口 {0},但已无可甩分区(建议扩容或手动调度) (岛 {1})";

    // ===== 静态单例面 =====

    /** install() 幂等守卫。 */
    private static boolean installed;
    /** Time.delta 累积(60fps 时 delta=1)。 */
    private static float acc;
    /** 本侧已重建过的脏纪元(初值 -1 ⇒ 首次结算恒重建)。 */
    private static long lastEpoch = -1L;
    /** 对齐自愈:下一拍强制重建(D41;stats 缺失 / 区序错位时置位)。 */
    private static boolean forceRebuild;

    /** 岛集(按岛键升序)。 */
    private static final Seq<Island> islands = new Seq<>();

    // ===== 全局计数器(§1.7;015 字段 I2 已接入)=====

    private static long settles;
    private static long watchEntries;
    private static long warnEmits;
    private static double rSum;
    private static long rSamples;
    private static long shedTotal;      // 015:I2 已接入(仅状态机切区自增;D60)
    private static long restoreTotal;   // 015:I2 已接入(仅状态机复区自增;D60)
    private static long defenseCut;     // 015:I2 已接入(切档 3 时另计;D60)

    // ===== 重建 scratch(稳态零使用;重建/刷新路径允许分配)=====

    private static final Seq<GatewayNode.GatewayNodeBuild> sortedGw = new Seq<>();
    private static final Seq<PowerGraph> liveGraphs = new Seq<>();
    private static int[] ufParent = new int[0];
    private static int[] ufIsland = new int[0];     // sortedGw 序 → 岛序号(-1 未分配)
    private static int[] rootIsland = new int[0];   // root → 岛序号(-1 未分配;仅根位置有效)

    /** 确定性排序:按 pos()(packed 坐标)。 */
    private static final Comparator<GatewayNode.GatewayNodeBuild> BY_POS =
        (a, b) -> Integer.compare(a.pos(), b.pos());
    /** 岛键升序。 */
    private static final Comparator<Island> BY_KEY =
        (a, b) -> Long.compare(a.key, b.key);
    /** 区锚升序。 */
    private static final Comparator<ZoneInfo> BY_ANCHOR =
        (a, b) -> Long.compare(a.anchor, b.anchor);

    private SafetyNet(){
    }

    /** 挂载结算循环(幂等);由 BlackstartMod.init() 在 BlackstartLinks.install() 之后调用。 */
    public static void install(){
        if(installed) return;
        installed = true;
        Events.run(EventType.Trigger.update, SafetyNet::tick);
        Events.on(EventType.WorldLoadEvent.class, e -> clearWorld());
        Events.on(EventType.ResetEvent.class, e -> clearWorld());
    }

    /** 018:世界边界丢弃岛态与当拍暂存,首个重建按首次建岛处理。 */
    private static void clearWorld(){
        islands.clear();
        sortedGw.clear();
        liveGraphs.clear();
        acc = 0f;
        lastEpoch = -1L;
        forceRebuild = false;
    }

    /** 节流入口(照 T2;菜单/暂停早退;独立 10Hz 节流,不吃 T2 的 dirty)。 */
    static void tick(){
        if(Vars.state == null || !Vars.state.isPlaying()) return;
        acc += Time.delta;
        if(acc < SETTLE_PERIOD) return;
        float dt = Math.max(acc, 1f);
        acc = 0f;
        settle(dt);
    }

    /** 单拍结算(每 10Hz 一拍)。 */
    private static void settle(float dt){
        long now = (long)Vars.state.tick;                      // 015:D59 tick 绝对值口径(settle 顶部取一次)
        // ① 纪元变化 / 对齐自愈 → 重建岛集
        long epoch = BlackstartLinks.dirtyEpoch;
        if(epoch != lastEpoch || forceRebuild){
            rebuild();
            lastEpoch = epoch;
            forceRebuild = false;
        }
        // ② 岛集空 → 返回
        if(islands.isEmpty()) return;
        // ③ 结算计数
        settles++;
        // ④ 逐岛刷新 S/D/r/rAll + 逐区 inflow/need(D50/D41;索引对齐)
        for(Island isl : islands){
            BlackstartLinks.Stats st = BlackstartLinks.stats(isl.key);
            if(st == null){
                forceRebuild = true;   // 对齐自愈:本侧岛不在 T2 快照 → 下拍重建
                continue;              // 保旧值
            }
            if(isl.zones.size != st.perZoneInflow.length){
                forceRebuild = true;   // 对齐自愈:区序错位 → 下拍重建(仍按 min() 对齐刷新)
            }
            isl.S = st.S;
            isl.D = st.D;
            // 合本 §二-2.3-②:r 只用于预警/甩负荷(014 原样;钳 0)
            float r = st.D > EPS ? Math.max(0f, (st.D - st.S) / st.D) : 0f;
            isl.r = r;
            isl.rAll = st.D > EPS ? (st.D - st.S) / st.D : 0f;  // 015:有符号,不钳(负 = 盈余;D50)
            float liveNeed = 0f;
            float[] inflow = st.perZoneInflow;
            float[] needArr = st.perZoneNeed;
            for(int z = 0; z < isl.zones.size; z++){
                ZoneInfo zi = isl.zones.get(z);
                float inf = z < inflow.length ? inflow[z] : 0f;
                zi.inflow = inf;
                zi.netImporter = inf > EPS;
                zi.need = z < needArr.length ? needArr[z] : 0f;                 // 015:④ 每拍刷新
                if(zi.state == Z_BACKOFF && now >= zi.backoffUntil) zi.state = Z_SHED;  // 015:到期归一(不计翻转;D54)
                if(zi.state == Z_NORMAL) liveNeed += zi.need;                   // 015:liveNeed = Σ 在供区 need
            }
            isl.liveNeed = liveNeed;
            rSum += r;
            rSamples++;
        }
        if(BlackstartContinuity.stabilizing()){
            // Absolute action clocks must also stop while measurements settle.
            for(Island isl:islands){isl.stateSince+=(long)dt;isl.stepSince+=(long)dt;isl.rPrev=isl.r;isl.topologyPending=0;}
            return;
        }
        // 018:积分独立逐岛执行,不寄生在 Stats 缺失会 continue 的取数段。
        float dtSec = dt / 60f;
        for(int k = 0; k < islands.size; k++){
            Island isl = islands.get(k);
            isl.sideProd = 0f;
            for(int z = 0; z < isl.zones.size; z++){
                PowerGraph g = isl.zones.get(z).graph; // 区已在 rebuild 按图身份去重
                if(g != null) isl.sideProd += g.getPowerProduced();
            }
            float g = clamp01(K_R * Math.max(0f, isl.r) + K_STEP * Math.abs(isl.r - isl.rPrev));
            isl.tau = clamp01(isl.tau + (g - isl.tau) * Math.min(1f, dtSec / T_TAU));
        }
        // 018:无条件覆盖所有岛;逐岛身份暂存同拍消费即清,状态机当拍可见。
        for(int k = 0; k < islands.size; k++){
            Island isl = islands.get(k);
            isl.tau = clamp01(isl.tau + isl.topologyPending);
            isl.topologyPending = 0f;
            isl.rPrev = isl.r;
        }
        // ⑤ 岛级状态机(逐岛;dt 累加计时;进入边缘动作一次;D51/D53)
        for(Island isl : islands){
            boolean watchSignal = isl.r >= R_WATCH || isl.tau >= TAU_WATCH;
            switch(isl.state){
                case NORMAL: {
                    if(watchSignal) isl.enterT += dt; else isl.enterT = 0f;
                    if(isl.enterT >= T_WATCH){
                        isl.state = WATCH;
                        isl.stateSince = now;
                        isl.enterT = 0f;
                        isl.calmT = 0f;
                        refreshZones(isl);
                        watchEntries++;
                        emitWarn(isl);
                    }
                    break;
                }
                case WATCH: {
                    if(!watchSignal){
                        isl.calmT += dt;
                        if(isl.calmT >= T_CALM){
                            isl.state = NORMAL;   // 静默;无文案
                            isl.enterT = 0f;
                            isl.calmT = 0f;
                            resetActionState(isl);                     // 015
                        }
                    }else{
                        isl.calmT = 0f;
                        // D51:缺口门用 rAll(有符号),进态后立即切第一刀
                        if(isl.rAll >= R_SHED && (float)(now - isl.stateSince) >= T_SHED){
                            isl.state = SHEDDING;
                            isl.stepSince = now;
                            isl.defenseWarned = false;
                            isl.nothingEmitted = false;
                            isl.recovT = 0f;
                            cutTier(isl, now);
                        }
                    }
                    break;
                }
                case SHEDDING: {
                    if(isl.rAll <= -R_RECOVER){
                        isl.recovT += dt;
                        if(isl.recovT >= T_RECOVER){
                            isl.state = RESTORING;
                            isl.stepSince = now;
                            isl.recovT = 0f;
                        }
                    }else{
                        isl.recovT = 0f;
                        float rOp = isl.liveNeed > EPS ? (isl.liveNeed - isl.S) / isl.liveNeed : isl.rAll;   // 015:切档门(全切光回落 rAll;D62 修-015a)
                        if(rOp >= R_SHED && (float)(now - isl.stepSince) >= T_STEP){
                            cutTier(isl, now);
                        }
                    }
                    break;
                }
                case RESTORING: {
                    if(isl.rAll >= R_SHED){
                        isl.state = SHEDDING;             // 回落;stepSince 不动 ⇒ 下拍即可补切
                        isl.defenseWarned = false;        // 015:防御档可能已在这轮恢复过 ⇒ 警告须重放
                    }else if(!anyShedZone(isl)){
                        isl.state = NORMAL;
                        resetActionState(isl);
                    }else if((float)(now - isl.stepSince) >= T_RESTORE_STEP){
                        restoreNext(isl, now);
                    }
                    break;
                }
            }
            int prevBand = isl.band;
            isl.band = isl.state == SHEDDING || isl.tau >= (prevBand == 2 ? BAND_RED_DOWN : BAND_RED_UP) ? 2
                : isl.state == RESTORING ? 3
                : isl.state == WATCH || isl.tau >= (prevBand >= 1 ? BAND_YELLOW_DOWN : BAND_YELLOW_UP) ? 1 : 0;
        }
    }

    /**
     * 重建岛集(D41;仅脏/决策时)。谓词与 BlackstartLinks.settle() 步骤 2 + buildIslandStats 逐条一致。
     * 岛键 = 岛内全部网关 packed pos 升序最小者;区锚 ZoneKey = 该区全部建筑 packed pos 升序最小者。
     */
    private static void rebuild(){
        int n = BlackstartLinks.registry.size;
        if(n == 0){
            islands.clear();
            return;
        }

        // 1) 复制注册表到本地并排序(确定性;禁原地改共享 Seq)
        sortedGw.clear();
        for(int i = 0; i < n; i++) sortedGw.add(BlackstartLinks.registry.get(i));
        sortedGw.sort(BY_POS);
        n = sortedGw.size;

        // 2) 本拍「已测量」图集合(⑥;等价 T2 step1 的 graphAcc 键集 = 注册网关的非空 graph 去重)
        liveGraphs.clear();
        for(int i = 0; i < n; i++){
            PowerGraph g = graphOf(sortedGw.get(i));
            if(g != null && !liveContains(g)) liveGraphs.add(g);
        }

        // 3) 并查集(谓词 = T2 搬运链路谓词,逐条一致;记录条目/离线/关断/同网/未测量/不在注册表均不并岛)
        ensureUf(n);
        for(int i = 0; i < n; i++){
            ufParent[i] = i;
            ufIsland[i] = -1;
            rootIsland[i] = -1;
        }
        for(int gi = 0; gi < n; gi++){
            GatewayNode.GatewayNodeBuild gw = sortedGw.get(gi);
            PowerGraph ownGraph = graphOf(gw);
            if(ownGraph == null) continue;
            for(int li = 0; li < gw.modLinks.size; li++){
                int packed = gw.modLinks.get(li);
                Building other = Vars.world.build(packed);        // O(1)
                if(other == null) continue;                       // 悬挂(-1)
                // 机器类/原版节点的「记录条目」不参与连通(修 B7);实码对非网关对端记 state=0 零处理
                if(!(other instanceof GatewayNode.GatewayNodeBuild peer)) continue;
                if(!gw.modLinkValid(packed, gw.team) || !peer.isValid()) continue;  // 跨队/无 power/失效(-1)
                PowerGraph dst = graphOf(peer);
                if(dst == null) continue;                         // (-1)
                if(!linkLive(gw, li, peer)) continue;
                if(dst == ownGraph) continue;                     // 同图(6)
                if(!liveContains(dst)) continue;                  // ⑥目标图本拍未测量 ⇒ 保守离线(-1)
                int ti = registryIndexOf(peer);                   // ⑦对端必须在本侧注册表
                if(ti < 0) continue;
                ufUnion(gi, ti);
            }
        }

        // 4) 分配岛序号(sortedGw 已按 pos 升序 ⇒ 首次出现序 = 岛键升序)
        int islandN = 0;
        for(int i = 0; i < n; i++){
            int r = ufFind(i);
            if(rootIsland[r] < 0) rootIsland[r] = islandN++;
            ufIsland[i] = rootIsland[r];
        }

        // 5) 组装岛(跳过幽灵网关)+ 区收集 + 对象存活复用
        Seq<Island> next = new Seq<>();                           // 仅脏/决策时(重建拍)
        for(int isl = 0; isl < islandN; isl++){
            Seq<GatewayNode.GatewayNodeBuild> members = new Seq<>();   // 仅脏/决策时(重建拍)
            long key = Long.MAX_VALUE;
            for(int i = 0; i < n; i++){
                if(ufIsland[i] != isl) continue;
                GatewayNode.GatewayNodeBuild gw = sortedGw.get(i);
                if(!gw.isValid() || !stillInWorld(gw)) continue;  // 幽灵网关守卫(D41)
                members.add(gw);
                int p = gw.pos();
                if(p < key) key = p;
            }
            if(members.isEmpty()) continue;                       // 全幽灵 → 丢弃空岛

            Seq<ZoneInfo> zones = new Seq<>();                    // 仅脏/决策时(重建拍)
            for(int mi = 0; mi < members.size; mi++){
                PowerGraph g = graphOf(members.get(mi));
                if(g == null) continue;
                if(zoneHasGraph(zones, g)) continue;              // 同区多网关去重(对象身份)
                long zk = zoneKeyOf(g);
                ZoneInfo zi = reuseZone(zk);                      // 按 ZoneKey 复用(跨岛);无 → 新建
                zi.anchor = zk;
                zi.graph = g;                                     // 刷新 graph 引用(reflow 会换对象)
                classifyZone(zi);
                applyOverride(zi);
                // 015:删去 need/state 清零 —— 状态与账本跨重建保留(D56;区对象按 ZoneKey 复用)
                zi.netImporter = false;
                zi.inflow = 0f;
                zones.add(zi);
            }
            zones.sort(BY_ANCHOR);                                // ZoneKey 升序(与 perZoneInflow 对齐)

            // 015:读档采纳 pass(D56;谓词与 writeGrants 逐条同源,读 getGrant)。
            // 邻区 state==Z_NORMAL 且其全部入链 grant ≤ 0(>0 或 -1 哨兵均不采纳)⇒ 采纳为 Z_SHED。
            // 采纳不记 flip、不计数、不落 lastFlipAt(仅脏/决策时,重建拍)。
            for(int zz = 0; zz < zones.size; zz++){
                ZoneInfo zi = zones.get(zz);
                if(zi.state != Z_NORMAL) continue;
                int links = 0;
                boolean allZero = true;
                for(int m = 0; m < members.size && allZero; m++){
                    GatewayNode.GatewayNodeBuild gw = members.get(m);
                    for(int li = 0; li < gw.modLinks.size; li++){
                        int packed = gw.modLinks.get(li);
                        Building peer = Vars.world.build(packed);
                        if(!(peer instanceof GatewayNode.GatewayNodeBuild)) continue;
                        if(!gw.modLinkValid(packed, gw.team) || !peer.isValid()) continue;
                        if(li >= gw.linkOpen.length || !linkLive(gw, li, peer)) continue;
                        if(gw.power == null || peer.power == null || peer.power.graph == null) continue;
                        if(peer.power.graph == gw.power.graph) continue;
                        if(peer.power.graph != zi.graph) continue;
                        links++;
                        float g = BlackstartLinks.getGrant(gw.pos(), packed);
                        if(!(g <= 0f) || g < 0f){ allZero = false; break; }   // >0 / -1 哨兵 ⇒ 不采纳(D56+§6-6)
                    }
                }
                if(links > 0 && allZero) zi.state = Z_SHED;              // 采纳:不记 flip、不计数
            }

            Island island = reuseIsland(members);
            island.key = key;
            island.members.clear();
            island.members.addAll(members);
            island.zones.clear();
            island.zones.addAll(zones);
            next.add(island);
        }
        next.sort(BY_KEY);

        islands.clear();
        islands.addAll(next);
    }

    /**
     * D44 自动分类(仅重建/刷新时,稳态零扫描):遍历该区 graph.consumers(实码可用;javap 实证 `public final Seq<Building> consumers`),
     * 显式排除网关系方块;参与判据 = consPower != null && !buffered && power != null
     * (consumers 归组口径,再防御一遍);档位:turrets/projectors=3;drills/units/hasItems=2;其余=1;
     * 区内取最高;兜底 2(仅「无任何参与建筑/无法分类」,D44)。members 与 c3/c2/c1 随分类一起填(供 dump;T4-1 手工核对用)。
     *
     * 修-015cal(中枢校准退修):初值由 2 改 0、循环后按 members==0 回落 2 —— 原「初值 2 只升不降」
     * 使 tier1(可弃)不可达,与 D44/T4-3/T4-4 验收锚(逐档 1→2→3 切、恢复序 3→2→1)矛盾;
     * 修正后仅「纯 c1 区」由 2 变 1,其余组合逐位不变(无成员区仍 = 2)。
     */
    private static void classifyZone(ZoneInfo zi){
        PowerGraph g = zi.graph;
        zi.tier = 0;                        // 修-015cal:初值 0(区内取最高;无参与建筑时下方回落兜底 2;D44)
        zi.members = 0;
        zi.c3 = 0;
        zi.c2 = 0;
        zi.c1 = 0;
        if(g == null){
            zi.tier = 2;                    // 兜底 2(离线图无参与建筑)
            return;
        }
        Seq<Building> src = g.consumers;     // 实码可用;== consPower != null && !buffered 归组口径
        if(src == null) src = g.all;        // 纵深防御:极端环境退 all(同过滤器)
        for(int i = 0; i < src.size; i++){
            Building b = src.get(i);
            if(b instanceof GatewayNode.GatewayNodeBuild) continue;   // 显式排除网关系方块(修 F1/C-2/C-11)
            if(b.block == null || b.block.consPower == null || b.block.consPower.buffered || b.power == null) continue;
            int t;
            BlockGroup grp = b.block.group;
            if(grp == BlockGroup.turrets || grp == BlockGroup.projectors){
                t = 3;                       // 防御
            }else if(grp == BlockGroup.drills || grp == BlockGroup.units || b.block.hasItems){
                t = 2;                       // 生产
            }else{
                t = 1;                       // 可弃(装饰性耗电、加热器等)
            }
            zi.members++;
            if(t == 3) zi.c3++; else if(t == 2) zi.c2++; else zi.c1++;
            if(t > zi.tier) zi.tier = t;
        }
        if(zi.members == 0){
            zi.tier = 2;                    // 兜底 2(无任何参与建筑/无法分类,如空消费者区;D44)
        }
    }

    /** WATCH 进入边缘时无条件重算一次分类(D44;决策前刷新)。 */
    private static void refreshZones(Island isl){
        for(int z = 0; z < isl.zones.size; z++){
            classifyZone(isl.zones.get(z));
            applyOverride(isl.zones.get(z));
        }
    }

    /**
     * WATCH 预警文案(D47;仅 NORMAL→WATCH 进入边缘恰一行;禁每拍打印)。
     * 参数:pct = Math.round(r*100) + "%";s/d = "%.2f" 的 S/D;第四参 = (int)(T_SHED/60f)(秒常量计算值,015 起)。
     */
    private static void emitWarn(Island isl){
        String pct = Math.round(isl.r * 100f) + "%";
        String s = fmt(isl.S, 2);
        String d = fmt(isl.D, 2);
        Point2 p = Point2.unpack((int)isl.key);
        // 014:占位 = {n}(引擎实码口径);取词判空回退字面模板(无头 Core.bundle == null;先例 BlackstartView.java:3067-3069 同法)。
        // 替换用逐串 replace —— 对模板内容零解析风险(不用 Core.bundle.format/String.format)。
        // M4:{4} = 岛 "x,y";"(岛 )"后缀文本随模板走,可本地化。
        String tpl = Core.bundle == null ? WARN_FALLBACK
            : Core.bundle.get("blackstart.shed.warn", WARN_FALLBACK);
        String text = tpl.replace("{0}", pct).replace("{1}", s).replace("{2}", d)
            .replace("{3}", Integer.toString((int)(T_SHED / 60f)))    // 015:"4" → 计算值(值不变)
            .replace("{4}", p.x + "," + p.y);
        BlackstartMod.emitPlayer(text);                               // M4:玩家面出口(浮字恒发)
        warnEmits++;
    }

    // ============================================================
    // 015 甩/复动作组(D51–D55;全部只在动作边缘/稀有态调用,稳态零进入)
    // ============================================================

    /** D51 切档:按档 1→2→3 找最低档有候选的档;档 3 先警告再等满 T_DEFENSE_WARN 才切。 */
    private static void cutTier(Island isl, long now){
        int t = 0;
        for(int cand = 1; cand <= 3; cand++){
            if(hasCutCandidate(isl, cand, now)){ t = cand; break; }    // 最低档优先
        }
        if(t == 0){ nothingCheck(isl); return; }                       // D52:卸无可卸
        if(t == 3){
            if(!isl.defenseWarned){
                emitDefense(isl);                                      // 仅动作边缘
                isl.defenseWarned = true;
                isl.stepSince = now;
                return;
            }
            if((float)(now - isl.stepSince) < T_DEFENSE_WARN) return;
        }
        for(int z = 0; z < isl.zones.size; z++){
            ZoneInfo zi = isl.zones.get(z);
            if(zi.tier != t || zi.state != Z_NORMAL || !zi.netImporter) continue;
            if((float)(now - zi.lastFlipAt) < T_ZONE_COOL) continue;
            writeGrants(isl, zi, 0f);                                  // 仅动作边缘(D55 动作路径)
            zi.state = Z_SHED;
            recordFlip(isl, zi, now);
            shedTotal++;
            if(t == 3) defenseCut++;
            isl.tau = clamp01(isl.tau + CUT_PULSE);               // 018:动作原位、积分后足额
            emitCut(isl, zi, t);                                       // 仅动作边缘
        }
        isl.stepSince = now;
    }

    /** 该档是否存在可切候选(区级:tier / 状态 / 净进口 / 冷却;D51)。 */
    private static boolean hasCutCandidate(Island isl, int tier, long now){
        for(int z = 0; z < isl.zones.size; z++){
            ZoneInfo zi = isl.zones.get(z);
            if(zi.tier == tier && zi.state == Z_NORMAL && zi.netImporter
                && (float)(now - zi.lastFlipAt) >= T_ZONE_COOL) return true;
        }
        return false;
    }

    /** D52 卸无可卸:缺口仍在且岛内已有甩/退避区 ⇒ 恰发一次 nothing;否则静默。 */
    private static void nothingCheck(Island isl){
        if(isl.nothingEmitted) return;
        if(isl.rAll < R_SHED) return;
        if(!anyShedZone(isl)) return;
        emitNothing(isl);                                              // 仅动作边缘
        isl.nothingEmitted = true;
    }

    /** D53 恢复一区:候选 = Z_SHED 且冷却已过;档降序 → 同档 need 升序;预演算不过直接停。 */
    private static void restoreNext(Island isl, long now){
        int best = -1;
        for(int z = 0; z < isl.zones.size; z++){
            ZoneInfo zi = isl.zones.get(z);
            if(zi.state != Z_SHED) continue;
            if((float)(now - zi.lastFlipAt) < T_ZONE_COOL) continue;
            if(best < 0){
                best = z;
            }else{
                ZoneInfo b = isl.zones.get(best);
                if(zi.tier > b.tier || (zi.tier == b.tier && zi.need < b.need)) best = z;   // 档降序→need 升序
            }
        }
        if(best < 0) return;                                           // 无候选:静默
        ZoneInfo zi = isl.zones.get(best);
        if(!(isl.S >= (isl.liveNeed + zi.need) * (1f + R_MARGIN))) return;   // 预演算不过:停在原地
        writeGrants(isl, zi, 1f);                                      // 仅动作边缘(D55 动作路径)
        zi.state = Z_NORMAL;
        recordFlip(isl, zi, now);                                      // state==NORMAL ⇒ 只记账不转退避
        restoreTotal++;
        isl.tau = clamp01(isl.tau + RESTORE_PULSE);               // 018:动作原位、积分后足额
        emitRestore(isl, zi);                                          // 仅动作边缘
        isl.stepSince = now;
    }

    /** D54 翻转台账(切/复各记一次;采纳与 BACKOFF→SHED 到期归一不计翻转)。 */
    private static void recordFlip(Island isl, ZoneInfo zi, long now){
        if(now - zi.windowStart > OSC_WINDOW){
            zi.windowStart = now;
            zi.flipsInWindow = 1;
            zi.backoffIndex = 0;
        }else{
            zi.flipsInWindow++;
        }
        zi.lastFlipAt = now;
        if(zi.state == Z_SHED && zi.flipsInWindow >= OSC_FLIPS){
            int dur = BACKOFF_SEC[Math.min(zi.backoffIndex, 3)];
            zi.backoffIndex = Math.min(zi.backoffIndex + 1, 3);
            zi.state = Z_BACKOFF;
            zi.backoffUntil = now + dur * 60L;
            emitLocked(isl, zi, dur);                                  // 仅动作边缘
        }
    }

    /** 岛内是否存在 Z_SHED / Z_BACKOFF 区(D52/D53 判据)。 */
    private static boolean anyShedZone(Island isl){
        for(int z = 0; z < isl.zones.size; z++){
            byte s = isl.zones.get(z).state;
            if(s == Z_SHED || s == Z_BACKOFF) return true;
        }
        return false;
    }

    /** 动作态复位(回 NORMAL 时清理;不碰区级账本/冷却/退避)。 */
    private static void resetActionState(Island isl){
        isl.shedTier = 0;                 // 保留字段,本批不消费
        isl.defenseWarned = false;
        isl.nothingEmitted = false;
        isl.recovT = 0f;
    }

    /**
     * D55 动作面唯一写通道:把本岛成员网关指向目标区(zi.graph)的全部有效入链 grant 置 v。
     * 枚举谓词与采纳 pass(rebuild 内)逐条同源;setGrant 的 rc 忽略(不分支、不中断、不重试)。
     * 仅动作边缘调用(切/复),稳态零进入。
     */
    private static void writeGrants(Island isl, ZoneInfo zi, float v){
        for(int m = 0; m < isl.members.size; m++){
            GatewayNode.GatewayNodeBuild gw = isl.members.get(m);
            for(int li = 0; li < gw.modLinks.size; li++){
                int packed = gw.modLinks.get(li);
                Building peer = Vars.world.build(packed);
                if(!(peer instanceof GatewayNode.GatewayNodeBuild)) continue;
                if(!gw.modLinkValid(packed, gw.team) || !peer.isValid()) continue;
                if(li >= gw.linkOpen.length || !linkLive(gw, li, peer)) continue;
                if(gw.power == null || peer.power == null || peer.power.graph == null) continue;
                if(peer.power.graph == gw.power.graph) continue;
                if(peer.power.graph != zi.graph) continue;
                BlackstartLinks.setGrant(gw.pos(), packed, v);          // rc 忽略(D55);仅动作边缘
            }
        }
    }

    /** 甩负荷文案(取词判空回退 → 逐串 replace → 岛坐标进模板;仅动作边缘)。 */
    private static void emitCut(Island isl, ZoneInfo zi, int tier){
        Point2 zp = Point2.unpack((int)zi.anchor);                      // 仅动作边缘
        Point2 p = Point2.unpack((int)isl.key);                         // 仅动作边缘
        BlackstartEvents.push(BlackstartEvents.Kind.SHED, zp.x, zp.y, tier, isl.r * 100f);
        String tpl = Core.bundle == null ? CUT_FALLBACK                // 仅动作边缘(取词)
            : Core.bundle.get("blackstart.shed.cut", CUT_FALLBACK);
        String text = tpl.replace("{0}", "(" + zp.x + "," + zp.y + ")").replace("{1}", tierWord(tier))
            .replace("{2}", p.x + "," + p.y);                          // 仅动作边缘(拼接;M4:{2}=岛)
        BlackstartMod.emitPlayer(text);                                // 仅动作边缘(M4:玩家面出口)
    }

    /** 防御档警告文案(仅动作边缘恰一次;M4:{1} = 岛 "x,y")。 */
    private static void emitDefense(Island isl){
        Point2 p = Point2.unpack((int)isl.key);                         // 仅动作边缘
        BlackstartEvents.push(BlackstartEvents.Kind.SHED, p.x, p.y, 0, isl.r * 100f);
        String tpl = Core.bundle == null ? DEFENSE_FALLBACK            // 仅动作边缘(取词)
            : Core.bundle.get("blackstart.shed.defense", DEFENSE_FALLBACK);
        String text = tpl.replace("{0}", Integer.toString((int)(T_DEFENSE_WARN / 60f)))
            .replace("{1}", p.x + "," + p.y);                          // 仅动作边缘(拼接;M4:{1}=岛)
        BlackstartMod.emitPlayer(text);                                // 仅动作边缘(M4:玩家面出口)
    }

    /** 复电文案(仅动作边缘;M4:{1} = 岛 "x,y")。 */
    private static void emitRestore(Island isl, ZoneInfo zi){
        Point2 zp = Point2.unpack((int)zi.anchor);                      // 仅动作边缘
        Point2 p = Point2.unpack((int)isl.key);                         // 仅动作边缘
        String tpl = Core.bundle == null ? RESTORE_FALLBACK            // 仅动作边缘(取词)
            : Core.bundle.get("blackstart.shed.restore", RESTORE_FALLBACK);
        String text = tpl.replace("{0}", "(" + zp.x + "," + zp.y + ")")
            .replace("{1}", p.x + "," + p.y);                          // 仅动作边缘(拼接;M4:{1}=岛)
        BlackstartMod.emitPlayer(text);                                // 仅动作边缘(M4:玩家面出口)
    }

    /** 振荡锁定文案(仅动作边缘;dur = 退避秒;M4:{2} = 岛 "x,y")。 */
    private static void emitLocked(Island isl, ZoneInfo zi, int dur){
        Point2 zp = Point2.unpack((int)zi.anchor);                      // 仅动作边缘
        Point2 p = Point2.unpack((int)isl.key);                         // 仅动作边缘
        String tpl = Core.bundle == null ? LOCKED_FALLBACK             // 仅动作边缘(取词)
            : Core.bundle.get("blackstart.shed.locked", LOCKED_FALLBACK);
        String text = tpl.replace("{0}", "(" + zp.x + "," + zp.y + ")").replace("{1}", Integer.toString(dur))
            .replace("{2}", p.x + "," + p.y);                          // 仅动作边缘(拼接;M4:{2}=岛)
        BlackstartMod.emitPlayer(text);                                // 仅动作边缘(M4:玩家面出口)
    }

    /** 卸无可卸文案(仅动作边缘恰一次;{0} = 缺口百分比;M4:{1} = 岛 "x,y")。 */
    private static void emitNothing(Island isl){
        Point2 p = Point2.unpack((int)isl.key);                         // 仅动作边缘
        String tpl = Core.bundle == null ? NOTHING_FALLBACK            // 仅动作边缘(取词)
            : Core.bundle.get("blackstart.shed.nothing", NOTHING_FALLBACK);
        String text = tpl.replace("{0}", Math.round(isl.rAll * 100f) + "%")
            .replace("{1}", p.x + "," + p.y);                          // 仅动作边缘(拼接;M4:{1}=岛)
        BlackstartMod.emitPlayer(text);                                // 仅动作边缘(M4:玩家面出口)
    }

    /** 档位词(取词判空回退字面;D58)。 */
    private static String tierWord(int t){
        String def = t == 3 ? "防御" : t == 2 ? "生产" : "可弃";
        return Core.bundle == null ? def : Core.bundle.get("blackstart.tier." + t, def);
    }

    /** bs-zones 全量 dump(D46 格式逐字冻结;015 增 state 值化 + zonex 行;命令层只调用,逻辑在此)。 */
    public static void dump(){
        long now = Vars.state == null ? 0L : (long)Vars.state.tick;    // 015:zonex backoff 剩余秒用(防御:菜单/无头早期 state 可空)
        int totalZones = 0;
        for(Island isl : islands) totalZones += isl.zones.size;
        BlackstartMod.emit("[bs] zones: epoch=" + BlackstartLinks.dirtyEpoch + " islands=" + islands.size
            + " zones=" + totalZones + " settles=" + settles);
        for(Island isl : islands){
            Point2 p = Point2.unpack((int)isl.key);
            BlackstartMod.emit("[bs] island (" + p.x + "," + p.y + ") state="
                + (isl.state == SHEDDING ? "SHEDDING" : isl.state == RESTORING ? "RESTORING" : isl.state == WATCH ? "WATCH" : "NORMAL")
                + " tau=" + fmt(isl.tau, 2) + " band=" + isl.band + " src=" + (isl.sideProd > 0f ? 1 : 0)
                + " r=" + fmt(isl.r, 4) + " S=" + fmt(isl.S, 2) + " D=" + fmt(isl.D, 2)
                + " zones=" + isl.zones.size);
            for(ZoneInfo zi : isl.zones){
                Point2 zp = Point2.unpack((int)zi.anchor);
                BlackstartMod.emit("[bs] zone (" + zp.x + "," + zp.y + ") tier=" + zi.tier
                    + " src=auto members=" + zi.members + " c3=" + zi.c3 + " c2=" + zi.c2 + " c1=" + zi.c1
                    + " net=" + (zi.netImporter ? 1 : 0) + " inflow=" + fmt(zi.inflow, 2) + " state="
                    + (zi.state == Z_SHED ? "SHED" : zi.state == Z_BACKOFF ? "BACKOFF" : "NORMAL"));
                // 015:D57 zonex 独立行(紧跟区行;backoff = 仅 BACKOFF 时 ceil 剩余秒,否则 0)
                BlackstartMod.emit("[bs] zonex (" + zp.x + "," + zp.y + ") need=" + fmt(zi.need, 2)
                    + " flips=" + zi.flipsInWindow + " backoff="
                    + (zi.state == Z_BACKOFF && zi.backoffUntil > now ? (zi.backoffUntil - now + 59) / 60 : 0) + "s");
            }
        }
        float avgR = rSamples > 0 ? (float)(rSum / rSamples) : 0f;
        BlackstartMod.emit("[bs] counters: watchEntries=" + watchEntries + " warnEmits=" + warnEmits
            + " avgR=" + fmt(avgR, 4) + " shedTotal=" + shedTotal + " restoreTotal=" + restoreTotal
            + " defenseCut=" + defenseCut);
    }

    // 018:机制岛只读门;索引序沿用结算后的岛键/区锚升序,不分配、不迁移。
    public static int islandCount(){ return islands.size; }
    public static int islandRepPos(int k){ return validIsland(k) ? (int)islands.get(k).key : -1; }
    public static int islandState(int k){ return validIsland(k) ? islands.get(k).state : -1; }
    public static float islandTau(int k){ return validIsland(k) ? islands.get(k).tau : Float.NaN; }
    public static int islandBand(int k){ return validIsland(k) ? islands.get(k).band : -1; }
    public static boolean islandSourced(int k){ return validIsland(k) && islands.get(k).sideProd > 0f; }
    public static float islandRAll(int k){ return validIsland(k) ? islands.get(k).rAll : Float.NaN; }
    public static int islandZoneCount(int k){ return validIsland(k) ? islands.get(k).zones.size : -1; }
    public static int islandZoneAnchorAt(int k, int z){
        return validZone(k, z) ? (int)islands.get(k).zones.get(z).anchor : -1;
    }
    public static int islandZoneStateAt(int k, int z){
        return validZone(k, z) ? islands.get(k).zones.get(z).state : -1;
    }
    // M7:区档位/来源/栋数只读门(越界 → -1/false/-1;不写、不迁移;分类与覆盖规则一行不改)。
    public static int islandZoneTierAt(int k, int z){
        return validZone(k, z) ? islands.get(k).zones.get(z).tier : -1;
    }
    public static boolean islandZoneOverrideAt(int k, int z){
        return validZone(k, z) && islands.get(k).zones.get(z).override;
    }
    public static int islandZoneMembersAt(int k, int z){
        return validZone(k, z) ? islands.get(k).zones.get(z).members : -1;
    }
    public static int islandAt(int packedPos){
        if(Vars.world == null) return -1;
        Building b = Vars.world.build(packedPos);
        if(b == null || b.power == null || b.power.graph == null) return -1;
        // 网关先按成员身份定位;普通建筑按已缓存的区图身份归属。
        for(int k = 0; k < islands.size; k++){
            Island isl = islands.get(k);
            for(int m = 0; m < isl.members.size; m++) if(isl.members.get(m) == b) return k;
        }
        for(int k = 0; k < islands.size; k++){
            Island isl = islands.get(k);
            for(int z = 0; z < isl.zones.size; z++) if(isl.zones.get(z).graph == b.power.graph) return k;
        }
        return -1;
    }

    // M4:防御横幅只读门 —— 该位置所属岛当前仍处「缺口 ≥ 甩负荷阈值」状态(横幅存活判据;
    // 越界/无归属/已恢复 → false;只读,不写、不迁移,分类与覆盖规则一行不改)。
    public static boolean defensePendingAt(int packedPos){
        int k = islandAt(packedPos);
        return k >= 0 && islands.get(k).rAll >= R_SHED;
    }
    private static boolean validIsland(int k){ return k >= 0 && k < islands.size; }
    private static boolean validZone(int k, int z){
        return validIsland(k) && z >= 0 && z < islands.get(k).zones.size;
    }
    private static float clamp01(float v){ return Math.max(0f, Math.min(1f, v)); }

    // ============================================================
    // 辅助(对象身份判图 / 幽灵守卫 / 二分定位 / 锚计算 / 存活复用)
    // ============================================================

    /** 幽灵网关守卫:内联复刻 BlackstartLinks.stillInWorld(D41;勿凭记忆改条件)。 */
    private static boolean stillInWorld(GatewayNode.GatewayNodeBuild gw){
        if(Vars.world == null || gw.tile == null) return false;
        int px = gw.tile.x, py = gw.tile.y;
        return px >= 0 && py >= 0 && px < Vars.world.width() && py < Vars.world.height()
            && Vars.world.build(px, py) == gw;
    }

    /** T3 016 接入:分区档覆盖(聚合在 T3 内完成;AUTO/无开关 ⇒ 保持自动分类)。 */
    private static void applyOverride(ZoneInfo zi){
        int ov = BlackstartProtection.zoneOverrideTier(zi.graph);
        if(ov != 0){ zi.tier = ov; zi.override = true; }
        else{ zi.override = false; }
    }

    /** T3 016 裁定 A:链路导通判据 = linkOpen 平价 gate(装置不写 linkOpen ⇒ 闸关须在此合并)。 */
    private static boolean linkLive(GatewayNode.GatewayNodeBuild gw, int li, Building peer){
        if(li < gw.linkOpen.length && !gw.linkOpen[li]) return false;
        return BlackstartProtection.linkGateOpen(gw, peer);
    }

    /** 网关所在原生子图;无 power / 无 graph → null(离线)。 */
    private static PowerGraph graphOf(GatewayNode.GatewayNodeBuild gw){
        return gw.power == null ? null : gw.power.graph;
    }

    /** 对象身份包含(Seq.contains 走 equals,这里必须按 == 判图身份)。 */
    private static boolean liveContains(PowerGraph g){
        for(int i = 0; i < liveGraphs.size; i++){
            if(liveGraphs.get(i) == g) return true;
        }
        return false;
    }

    /** 区槽对象身份查找(与 BlackstartLinks.zoneSlot 同纪律;零依赖任何 Map 迭代序)。 */
    private static boolean zoneHasGraph(Seq<ZoneInfo> zones, PowerGraph g){
        for(int i = 0; i < zones.size; i++){
            if(zones.get(i).graph == g) return true;
        }
        return false;
    }

    /** registry 序索引定位(transport 链对端;零分配;二分 + 对象同一校验)。 */
    private static int registryIndexOf(GatewayNode.GatewayNodeBuild gw){
        int p = gw.pos();
        int lo = 0, hi = sortedGw.size - 1;
        while(lo <= hi){
            int mid = (lo + hi) >>> 1;
            GatewayNode.GatewayNodeBuild m = sortedGw.get(mid);
            int mp = m.pos();
            if(mp == p) return m == gw ? mid : -1;
            if(mp < p) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    /** 区锚 ZoneKey = 该区(原生子图)全部建筑 packed pos 升序最小者(遍历 graph.all;与 T2 D35 同口径;仅重建/刷新时)。 */
    private static long zoneKeyOf(PowerGraph g){
        if(g == null) return Long.MAX_VALUE;
        long mk = Long.MAX_VALUE;
        Seq<Building> all = g.all;
        if(all != null){
            for(int i = 0; i < all.size; i++){
                Building b = all.get(i);
                if(b == null) continue;
                long bp = b.pos();
                if(bp >= 0L && bp < mk) mk = bp;
            }
        }
        return mk;
    }

    /** 按 ZoneKey 复用旧 ZoneInfo(跨岛搜索;无 → 新建)。 */
    private static ZoneInfo reuseZone(long zk){
        for(int i = 0; i < islands.size; i++){
            Island old = islands.get(i);
            for(int z = 0; z < old.zones.size; z++){
                ZoneInfo zi = old.zones.get(z);
                if(zi.anchor == zk) return zi;
            }
        }
        return new ZoneInfo();                                    // 仅脏/决策时(复用未命中 → 新建)
    }

    /** 按网关集合逐对象相等复用旧 Island(无 → 新建 NORMAL)。 */
    private static Island reuseIsland(Seq<GatewayNode.GatewayNodeBuild> members){
        for(int i = 0; i < islands.size; i++){
            Island old = islands.get(i);
            if(sameMembers(old, members)) return old;
        }
        Island fresh = new Island();                              // 仅脏/决策时(复用未命中 → 新建)
        if(!islands.isEmpty()) fresh.topologyPending = TOPOLOGY_PULSE;
        return fresh;
    }

    /** 网关集合逐对象相等(两表均按 pos 升序 ⇒ 逐位 == 即可)。 */
    private static boolean sameMembers(Island old, Seq<GatewayNode.GatewayNodeBuild> members){
        if(old.members.size != members.size) return false;
        for(int i = 0; i < members.size; i++){
            if(old.members.get(i) != members.get(i)) return false;
        }
        return true;
    }

    /** 并查集/岛分配 scratch 扩容(稳态不变则不分配)。 */
    private static void ensureUf(int n){
        if(ufParent.length < n){
            ufParent = new int[n];
            ufIsland = new int[n];
            rootIsland = new int[n];
        }
    }

    private static int ufFind(int x){
        while(ufParent[x] != x){
            ufParent[x] = ufParent[ufParent[x]];       // 路径减半
            x = ufParent[x];
        }
        return x;
    }

    private static void ufUnion(int a, int b){
        int ra = ufFind(a), rb = ufFind(b);
        if(ra != rb) ufParent[rb] = ra;
    }

    /** 定点小数格式化(locale 无关 ROOT;'%.Nf')。 */
    private static String fmt(float v, int decimals){
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }
    static java.util.Map<Integer,double[]> saveRuns(boolean zones){
        var out=new java.util.TreeMap<Integer,double[]>();
        for(Island i:islands){
            if(!zones)out.put((int)i.key,new double[]{memberHash(i),i.state,i.stateSince,i.stepSince,i.enterT,i.calmT,i.recovT,i.tau,i.band,i.defenseWarned?1:0,i.nothingEmitted?1:0});
            else for(ZoneInfo z:i.zones)out.put((int)z.anchor,new double[]{z.state,z.lastFlipAt,z.backoffUntil,z.flipsInWindow,z.backoffIndex,z.windowStart});
        }
        return out;
    }
    static void rebuildForRestore(){rebuild();lastEpoch=BlackstartLinks.dirtyEpoch;forceRebuild=false;}
    private static int memberHash(Island i){int h=1;for(var b:i.members)h=31*h+java.util.Objects.hash(b.pos(),b.block.name,b.team.id);return h;}
    static void restoreRun(int pos,boolean zones,double[] v){
        if(zones){
            if(v.length!=6 || !BlackstartContinuity.integer(v[0],0,2) || !BlackstartContinuity.integer(v[3],0,1000000)
                || !BlackstartContinuity.integer(v[4],0,3) || v[1]<0 || v[2]<0 || v[5]<0)return;
            for(Island i:islands)for(ZoneInfo z:i.zones)if(z.anchor==pos){
                z.state=(byte)v[0];z.lastFlipAt=(long)v[1];z.backoffUntil=(long)v[2];z.flipsInWindow=(int)v[3];z.backoffIndex=(int)v[4];z.windowStart=(long)v[5];
                if(z.state!=Z_NORMAL && i.state==NORMAL)i.state=SHEDDING;
            }
        }else{
            if(v.length!=11 || !BlackstartContinuity.integer(v[1],0,3) || v[2]<0 || v[3]<0
                || v[4]<0 || v[4]>T_WATCH || v[5]<0 || v[5]>T_CALM || v[6]<0 || v[6]>T_RECOVER
                || v[7]<0 || v[7]>1 || !BlackstartContinuity.integer(v[8],0,3)
                || !BlackstartContinuity.integer(v[9],0,1) || !BlackstartContinuity.integer(v[10],0,1))return;
            for(Island i:islands)if(i.key==pos && memberHash(i)==v[0]){
                i.state=(byte)v[1];i.stateSince=(long)v[2];i.stepSince=(long)v[3];i.enterT=(float)v[4];i.calmT=(float)v[5];i.recovT=(float)v[6];
                i.tau=(float)v[7];i.band=(int)v[8];i.defenseWarned=v[9]==1;i.nothingEmitted=v[10]==1;
            }
        }
    }

    // ============================================================
    // 数据结构(§1.7;字段语义冻结)
    // ============================================================

    /**
     * 岛(§1.7):key = 岛键(岛内网关 packed pos 升序最小者);members 保 == 逐对象比较能力;
     * zones 按 ZoneKey 升序;state(NORMAL/WATCH/SHEDDING/RESTORING);stateSince = 进 WATCH 的 tick;
     * stepSince = 上次切/复动作 tick(015 启用);shedTier 本批不用(卸无可卸判据改用区状态直判,覆盖读档采纳),保留;
     * defenseWarned = 本轮防御警告已发(015 启用);nothingEmitted = 本轮 nothing 已发(015 启用);
     * S/D/r 由结算④刷新(014 原样);rAll(有符号)/liveNeed(在供区 need 和)/recovT 015 新增;enterT/calmT 内部计时。
     */
    private static final class Island {
        long key;
        final Seq<GatewayNode.GatewayNodeBuild> members = new Seq<>();
        final Seq<ZoneInfo> zones = new Seq<>();
        byte state = NORMAL;
        long stateSince;
        long stepSince;        // 015:上次切/复动作 tick(stepSince)
        int shedTier;          // 本批不用,保留
        boolean defenseWarned; // 015:本轮防御警告已发
        boolean nothingEmitted;// 015:本轮 nothing 已发
        float S, D, r;
        float tau, rPrev, sideProd; // 018:稳定度、上拍缺口、原始在产源
        int band;                  // 018:0绿/1黄/2红/3恢复中
        float topologyPending;     // 018:只在未命中新岛登记,同拍消费即清
        float rAll;            // 015:(D−S)/max(D,EPS) 有符号(负 = 盈余)
        float liveNeed;        // 015:Σ Z_NORMAL 区 need
        float recovT;          // 015:盈余持续计时(dt 秒制)
        float enterT, calmT;
    }

    /**
     * 区(§1.7):anchor = ZoneKey;graph 引用(存活判据用);tier 由 D44 分类填;
     * override 来源 T3 zoneOverrideTier(016 已接入);members/c3/c2/c1 随分类填;
     * netImporter/inflow 每拍刷新;need = ④ 每拍刷新(来自 perZoneNeed);state(Z_NORMAL/Z_SHED/Z_BACKOFF);
     * lastFlipAt/backoffUntil/flipsInWindow/backoffIndex 为 015 翻转台账;windowStart 015 新增(振荡窗起点)。
     */
    private static final class ZoneInfo {
        long anchor;
        PowerGraph graph;
        int tier = 2;
        boolean override;         // 来源 T3 zoneOverrideTier(016 已接入)
        int members, c3, c2, c1;
        boolean netImporter;
        float inflow;
        float need;               // 015:④ 每拍刷新(来自 perZoneNeed)
        byte state = Z_NORMAL;    // 015:Z_NORMAL/Z_SHED/Z_BACKOFF
        long lastFlipAt;          // 015:上次翻转 tick(冷却判据)
        long backoffUntil;        // 015:BACKOFF 到期 tick
        int flipsInWindow;        // 015:窗内翻转数
        int backoffIndex;         // 015:退避档序
        long windowStart;         // 015:振荡窗起点 tick
    }
}
