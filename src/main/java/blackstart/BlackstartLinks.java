package blackstart;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGraph;

import java.util.Arrays;
import java.util.Comparator;

/**
 * T2 链路控制内核(静态类):注册表 + 结算循环 + 单链搬运/损耗核算。
 *
 * 职责:持有全图网关的运行时注册表,按 15Hz(4 tick)节流解算「定向搬运链路」的吞吐 F,
 * 并把结果写回各网关自有实例的申报面(需求申报 {@code linkDrawCmd} / 出力申报 {@code linkInjCmd})
 * 与每链路平行数组({@code linkFlow}/{@code linkState})。绝不合并电网、绝不触碰 power.links /
 * PowerGraph 结构、绝不写他方方块。
 *
 * 结算循环 = 设计 §1.6 单拍步骤 0–7 的【G1 子集】:
 * <pre>
 *   0 注册表整理(剔无效 + 需求激活 A 路) → 1 测量(扣自家申报 → P* / N* → surplus/deficit)
 *   → 2 期望(want = min(R, deficit/(1−l)) × grant) → 3 源预算 scaleS
 *   → 4 目标预算 scaleT(到货口径 Σwant×scaleS×(1−l),合本 F2)→ 5 终值 F = clamp(want×scaleS×scaleT, 0, R) + 状态判定
 *   → 6 申报回写(先全清零,再逐条累加) → 7 遥测(lastSettleNanos)
 * </pre>
 * 本批【不做】:斜率限制 / 死区 / 滞回(归 G3)、tier 级联 / T4 tier·grant 接口(归 G4);
 * grant 直接读平行数组 {@code linkGrant} 的默认值 1f;scaleS/scaleT 与 storageAllow 全公式已落。
 *
 * 012(T2-G2 尾 + G3)追加:G3 控制品质【已落】—— 斜率限制(D18)/ 流量死区(D19)/
 * 需求侧开搬·停搬滞回(D20)/ 状态 2 拍去抖(D21)/ 开断步(D22),控制律见 // T2 设计§1.7、状态机见 §1.10;
 * 类尾新增「探针辅助」区(tuneRate/tuneLoss/tuneSettle/tuneSto + openLink,命令层只管解析/校验/回显)。
 *
 * 稳态零分配纪律(D15):settle() 路径上不 new 任何集合/数组/lambda —— 图累加器
 * ({@link #graphAcc} + {@link Acc} 对象池)、链路 scratch({@link #linkScratch})、
 * 比较器({@link #BY_POS})全部复用/静态。复杂度 O(G + L + 本拍涉及图的成员数);
 * 稳态绝不碰 tile 网格,只做 O(1) 的 {@code Vars.world.build(packed)} 查询。
 *
 * 第 7 写点纪律(设计 §1.11):GatewayNodeBuild 的每链路平行数组由
 * {@code GatewayNodeBuild.syncLinkRuntime()} 维护,该方法挂在 modLinks 既有 6 个写点
 * (ensureModLink / replaceModLinks / pruneModLinks / onRemoved / onDestroyed / read)之后;
 * 本类只读这些数组,从不直接改 modLinks。
 *
 * 修正出处(《合本》§二-2.2 / §二-2.0-F2):① 旗标双翻/动态需求(见 BlackstartBlocks)
 * ② 需求激活 A 路(本类步骤 0)③ δ 折算(步骤 1)④ 源预算健康门自算 + getTotalBatteryCapacity(步骤 1)
 * ⑤ 守恒折算 inj = F×(1−l)×lastCov(步骤 6)F2 目标预算到货口径 Σwant×scaleS×(1−l)(步骤 4)
 * ⑨ sync 禁令(见 GatewayNode 类头)⑩ 状态枚举本批三值 0/1/−1(见 {@link #settle()})。
 *
 * 【保留】需求激活 B 路(update=true)逃生口:若 A 路被复核否决,可改为让引擎自己跑
 * updateConsumption() 维护 shouldConsumePower(代价 = 网关进 Groups.build、探针 u= 翻 true)。
 * 本批按 D-T2-1 裁定走 A 路。
 *
 * 013(T2-G4)追加:G4【已落】—— 步骤 2/3 之间插入 tier 档序级联,原步骤 3(源预算)与步骤 4(目标预算)
 * 合并为「步骤 3:档序级联 + 两级比例公平」(每档 t∈{3,2,1} 内先源图预算、再目标图预算;两级「可用额度」
 * 跨档扣减 —— 高档吃剩的才是低档的)。全 tier=2 时仅一轮 ⇒ scaleST/scaleTT 与原图级公式逐位等价。
 * 状态值域扩 5=受限(系数口径 grant×scaleS×scaleT<0.95 且配额前期望 ask>0 ⇒ 免疫斜率爬升瞬态)
 * 与 6=同网(原同图 0);步骤 7 落按岛 facade 统计表(键=岛键,禁裸 graphId);类尾探针辅助区追加
 * setTier/setGrant 内核与 getTier/getGrant 只读访问器(命令层只解析/校验/回显)。
 * 014:死区修法(013 真机发现)+ dirtyEpoch 订阅面。
 *
 * 状态观测语义【只记档】(017 实装 BsVisualData.bsGraphThrottled 时照此):本图有 ≥1 条入链处于
 * 1(饱和)或 5(受限)—— 本批不做渲染/UI。
 */
public final class BlackstartLinks {

    /**
     * 结算节拍(tick;默认 4 = 15Hz;设计 §1.12 要求 10–20Hz 内)。
     * 收编修正(中枢,2026-10-08):去 final —— §0.1-D3 冻结口径为 {@code static int SETTLE_TICKS = 4}
     * (非 final),012 的 bs-tune settle【2,6】需可写;final 下 tick 从内联常量变动态读,行为不变。
     */
    public static int SETTLE_TICKS = 4;

    /** 拓扑/参数脏标记:任一 modLinks 写点 / 注册表变化置位 ⇒ 下一 tick 立即结算,不等节流。 */
    public static boolean dirty = true;

    /** 埋点:上一拍 settle() 耗时(ns);空注册表快路径置 0(探针 bs-lab-t2 读)。 */
    public static long lastSettleNanos;

    /** 除法保护与健康门用极小量。 */
    private static final float EPS = 0.0001f;

    /** install() 幂等守卫。 */
    private static boolean installed;

    /** Time.delta 累积(60fps 时 delta=1)。 */
    private static float acc;

    /**
     * 上一拍实际累积的 tick 数(012/D18;下限 1)。tick() 在 acc 清零前存值,settle() 用它算斜率步长
     * {@code ΔFmax = R × linkSlew × settleDtTicks / 60}。默认 4 = SETTLE_TICKS 初值。
     */
    private static float settleDtTicks = 4f;

    /** 网关运行时注册表(创建真源 = GatewayNodeBuild.created();每拍剔无效)。 */
    static final Seq<GatewayNode.GatewayNodeBuild> registry = new Seq<>();

    /** 确定性排序:按 pos()(packed 坐标)。static 缓存,禁每拍 new lambda。 */
    private static final Comparator<GatewayNode.GatewayNodeBuild> BY_POS =
        (a, b) -> Integer.compare(a.pos(), b.pos());

    /** 本拍图累加器(键 = PowerGraph 对象身份);每拍 clear 后经对象池复用。 */
    private static final ObjectMap<PowerGraph, Acc> graphAcc = new ObjectMap<>();

    /** 017:本拍去抖后入链限流真值,随 graphAcc 生命周期清零;只读 O(1)。 */
    public static boolean graphThrottled(PowerGraph graph){
        if(graph == null || registry.isEmpty()) return false;
        Acc a = graphAcc.get(graph);
        return a != null && a.throttled;
    }

    /** Acc 对象池(稳态复用,禁每拍 new)。 */
    private static final Seq<Acc> accPool = new Seq<>();

    /** 每链路 scratch(按 owner 注册表序 × modLinks 序线性填充;对象复用)。 */
    private static final Seq<Link> linkScratch = new Seq<>();

    /** 本拍有效链路数(<= linkScratch.size;超出的陈旧对象不读)。 */
    private static int linkCount;
    /** Standard transport dispatch; reuses primitive arrays between physical frames. */
    private static final DirectedFlowSolver flowSolver = new DirectedFlowSolver();
    private static final Seq<Acc> flowGraphs = new Seq<>();

    /** T3 回执平铺对象池与按 target 复用的 collector;容量增长才分配,内容到下一拍有效。 */
    private static final Seq<ServingLink> servingScratch = new Seq<>();
    private static final Seq<Seq<ServingLink>> servingCollectors = new Seq<>();
    private static int[] servingTargets = new int[0];
    private static int servingUsed;

    public static final class ServingLink {
        public int ownerPos;
        public float flow, capacity, loss;
        public byte state;
    }

    /** 无命中返回 null;不同 target 的 collector 相互独立,读者不得修改或跨拍保存内容。 */
    public static Seq<ServingLink> servingOf(int packedTargetPos){
        if(registry.isEmpty()) clearServing();
        for(int i = 0; i < servingUsed; i++){
            if(servingTargets[i] == packedTargetPos) return servingCollectors.get(i);
        }
        return null;
    }

    private static void clearServing(){
        for(int i = 0; i < servingUsed; i++) servingCollectors.get(i).clear();
        servingUsed = 0;
    }

    /** 第 7 步:从有效 scratch 发布终值、额定、损耗量与去抖后状态,不重算控制律。 */
    private static void publishServing(){
        int devices = 0;
        for(int i = 0; i < registry.size; i++){
            GatewayNode.GatewayNodeBuild b = registry.get(i);
            if(b instanceof BreakerNode.BreakerNodeBuild || b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild) devices++;
        }
        clearServing();
        if(devices == 0) return;
        int used = 0;
        for(int k = 0; k < linkCount; k++){
            Link L = linkScratch.get(k);
            if(L.target == null || L.state == -1 || L.index >= L.owner.linkState.length) continue;
            ServingLink s;
            if(used < servingScratch.size) s = servingScratch.get(used);
            else{ s = new ServingLink(); servingScratch.add(s); }
            used++;
            s.ownerPos = L.owner.pos();
            s.flow = L.f;
            s.capacity = L.rating;
            s.loss = L.f * (1f - L.oneMinusL); // 与 T2 到货 F×oneMinusL 同源的损耗量。
            s.state = L.owner.linkState[L.index];
            int target = L.target.pos();
            int slot = 0;
            while(slot < servingUsed && servingTargets[slot] != target) slot++;
            if(slot == servingUsed){
                if(servingTargets.length <= slot) servingTargets = Arrays.copyOf(servingTargets, Math.max(8, (slot + 1) * 2));
                if(servingCollectors.size <= slot) servingCollectors.add((Seq<ServingLink>)new Seq<ServingLink>());
                servingTargets[slot] = target;
                servingUsed++;
            }
            servingCollectors.get(slot).add(s);
        }
    }

    private BlackstartLinks(){
    }

    /** 挂载结算循环(幂等):Trigger.update 在 Logic.update() 最头部 fire(N7),与引擎同帧闭环。 */
    public static void install(){
        if(installed) return;
        installed = true;
        Events.run(EventType.Trigger.update, ()->{if(!BlackstartFusionPolicy.active())tick();});
    }

    /**
     * 节流入口(照设计 §1.6 逐字):菜单/暂停不跑(不加 isEditor 门);脏标记旁路节流。
     */
    static void tick(){
        if(Vars.state == null || !Vars.state.isPlaying()) return;
        acc += Time.delta;
        // Native fuel and battery reserves can change between legacy settlements.
        // Standard mode refreshes the physical budget for every simulation frame.
        if(!BlackstartFusionPolicy.active() && !dirty && acc < SETTLE_TICKS) return;
        // 012(D18)dt 传递:本拍实际累积 tick 数(acc 清零前存值;下限 1,防 δ=0 帧)
        settleDtTicks = Math.max(acc, BlackstartFusionPolicy.active()?0f:1f);
        acc = 0f;
        dirty = false;
        settle();
        BlackstartContinuity.linksSettled();
    }

    /**
     * 注册一个网关(幂等;经 {@code GatewayNodeBuild.registered} 簿记,见 D4)。
     * 真源 = GatewayNodeBuild.created()(N8;每条创建路径都过),placed() 只留置脏。
     */
    public static void register(GatewayNode.GatewayNodeBuild gw){
        if(gw == null || gw.registered) return;
        gw.registered = true;
        registry.add(gw);
        markDirty();
    }

    /** 注销一个网关(置 false 并按对象 identity 移除)。 */
    public static void unregister(GatewayNode.GatewayNodeBuild gw){
        if(gw == null) return;
        gw.registered = false;
        for(int i = 0; i < registry.size; i++){
            if(registry.get(i) == gw){
                registry.remove(i);
                break;
            }
        }
        markDirty();
    }

    /**
     * 复核B-F1:本世界同坐标的建筑是否仍是该网关。
     * 换图时旧 Tiles 被整块丢弃、onRemoved 不再来,旧 build 的 {@code tile.build == this} 恒真 ⇒
     * isValid() 兜不住跨世界幽灵;此判据 O(1) 且不依赖世界事件(无时序问题)。
     */
    private static boolean stillInWorld(GatewayNode.GatewayNodeBuild gw){
        if(Vars.world == null || gw.tile == null) return false;
        int px = gw.tile.x, py = gw.tile.y;
        return px >= 0 && py >= 0 && px < Vars.world.width() && py < Vars.world.height()
            && Vars.world.build(px, py) == gw;
    }

    /**
     * 单拍结算(步骤 0–7)。首尾夹 System.nanoTime 落 lastSettleNanos。
     * 空注册表 = 唯一特判(记 0 后 return);非空但零链路照常走完(步骤本身极便宜,避免漏清零)。
     */
    private static void settle(){
        long t0 = System.nanoTime();

        // ---- 步骤 0:注册表整理(倒序剔除无效;存活者需求激活 A 路,幂等 field store)----
        for(int i = registry.size - 1; i >= 0; i--){
            GatewayNode.GatewayNodeBuild gw = registry.get(i);
            // D4 剔除谓词;复核B-F1 加固:isValid() 只查 tile.build==this 与 dead 位,换图时
            // 旧 Tiles 被整块丢弃且 onRemoved/onDestroyed 都不再触发 ⇒ 幽灵网关恒「有效」。
            // 补 O(1) 世界归属判定(坐标越界即出界)。
            if(!gw.isValid() || !stillInWorld(gw)){
                unregister(gw);                       // 剔出即注销(D4 / 修 F-1)
            }else{
                gw.shouldConsumePower = true;         // D5:需求激活 A 路(唯一新触达面)
            }
        }
        if(registry.isEmpty()){
            clearAccs();                             // 017:空表拍同样清掉旧图限流记忆
            lastSettleNanos = 0L;                     // 空表快路径
            linkCount = 0;                            // 复核B-F3:同步归零,防陈旧链路数被将来读取
            islandView.clear();                       // 013(D35):空表同步清空岛快照(statsCount() 归 0)
            return;
        }
        registry.sort(BY_POS);                        // 每拍解算前按 pos 排序(确定性)

        // ---- 步骤 1:测量 ----
        final float delta = Time.delta;               // 修③:每拍只读一次

        clearAccs();
        for(int i = 0; i < registry.size; i++){
            GatewayNode.GatewayNodeBuild gw = registry.get(i);
            PowerGraph g = gw.power == null ? null : gw.power.graph;
            if(g == null) continue;                   // graph null 视同离线,跳过测量(D6)
            Acc a = accOf(g);
            if(a.rep == null) a.rep = gw;
            a.sumInj += gw.linkInjCmd * gw.delta();   // 修③+复核A/B-F2:逐网关 delta()=Time.delta×timeScale,
            a.sumDraw += gw.linkDrawCmd * gw.delta(); // 折算成【每帧能量】,与引擎 getPowerProduced/Needed 同口径
        }
        for(Acc a : graphAcc.values()){
            PowerGraph g = a.graph;
            float p = g.getPowerProduced();
            float n = g.getPowerNeeded();
            a.produced = p;
            a.needed = n;
            a.pStar = p - a.sumInj;                   // 扣掉自家申报(两边皆每帧口径)
            a.nStar = n - a.sumDraw;
            // 复核A-F1:以上为【每帧】量,want / rating / 两级预算为【每 tick】量(60fps 归一),
            // 在此一次折算。缺此折算时非封顶链路稳态到货 = 需求×δ(真机高帧率下静默欠供)。
            if(delta > 0f){                           // δ=0 帧(载入首帧等)不折算,避免 0/0
                a.pStar /= delta;
                a.nStar /= delta;
            }

            // 源预算储能外送:健康门自算(修④,不用 getSatisfaction);底仓分母 = getTotalBatteryCapacity(修 F4)
            float storageAllow = 0f;
            GatewayNode.GatewayNodeBuild rep = a.rep;
            if(rep != null && rep.block instanceof GatewayNode gn && gn.allowStorageExport){
                boolean healthy = n == 0f || p / Math.max(n, EPS) >= 0.99f;
                if(healthy){
                    storageAllow = Math.max(0f, g.getBatteryStored() - gn.storageReserveFrac * g.getTotalBatteryCapacity());
                }
            }
            a.surplus = Math.max(0f, a.pStar - a.nStar) + storageAllow;
            a.deficit = Math.max(0f, a.nStar - a.pStar);
            if(BlackstartFusionPolicy.active()){
                GatewayNode policy=rep!=null && rep.block instanceof GatewayNode gn?gn:null;
                var budget=NativeExportBudget.read(g,policy!=null && policy.allowStorageExport,
                    policy==null?1f:policy.storageReserveFrac,delta);
                a.pStar=budget.production();a.nStar=budget.demand();
                a.surplus=budget.export();a.deficit=Math.max(0f,a.nStar-a.pStar);
            }
        }

        // ---- 步骤 2:期望(逐条分类 → want)----
        for(Acc a : graphAcc.values()){
            a.sumWant = 0f;
            a.sumGet = 0f;
            a.scaleS = 1f;
            a.scaleT = 1f;
        }
        linkCount = 0;
        for(int gi = 0; gi < registry.size; gi++){
            GatewayNode.GatewayNodeBuild gw = registry.get(gi);
            PowerGraph ownGraph = gw.power == null ? null : gw.power.graph;
            float oneMinusL=Math.max(1f-((GatewayNode)gw.block).effectiveLinkLoss(),EPS);
            float rating = gw.block instanceof GatewayNode gn ? gn.linkRating : 30f;
            if(!(rating >= 0f)) rating = 30f;         // 复核A-F5:负/NaN rating 先接地(负值会让步骤 5 钳出负 f);0 保留 = 关断语义
            float slew = gw.block instanceof GatewayNode gn ? gn.linkSlew : 1f;   // 012(D18):斜率上限同源 block 级
            if(!(slew > 0f)) slew = 1f;               // 防御:非法/NaN slew 接地回默认 1(单位 ×R/秒)
            int n = gw.modLinks.size;
            for(int i = 0; i < n; i++){
                Link L = linkAt(linkCount++);
                L.reset();
                L.owner = gw;
                L.index = i;
                L.src = ownGraph;
                L.rating = rating;
                L.slew = slew;
                L.oneMinusL = oneMinusL;

                int packed = gw.modLinks.get(i);
                Building other = Vars.world.build(packed);   // O(1);绝不扫网格

                if(other == null || ownGraph == null){
                    L.state = -1;                            // 悬挂 / owner 离线(rawState=-1,经 D21 管道)
                    continue;
                }
                if(other instanceof GatewayNode.GatewayNodeBuild peer){
                    // 对端是网关:先过消费侧唯一谓词(存在/同队/power 非空),再判图
                    if(!gw.modLinkValid(packed, gw.team) || !peer.isValid()){
                        L.state = -1;                        // 跨队 / 无 power / 已失效 → 离线·悬挂
                        continue;
                    }
                    PowerGraph dst = peer.power == null ? null : peer.power.graph;
                    L.target = peer;
                    L.dst = dst;
                    L.userCapacity=rating*EngineeringFlowLimits.fraction(gw,peer);
                    if(dst == null){
                        L.state = -1;
                        continue;
                    }
                    // 012(D22)开断步:链路关断 → F=0、rawState=2,不参与 sumWant/sumGet/任何预算。
                    // 判定优先级照 D21:离线(-1)优先于本档;同图(0)让位给开断(2)。
                    if(i < gw.linkOpen.length && !gw.linkOpen[i]){
                        L.state = 2;
                        continue;
                    }
                    if(!BlackstartProtection.linkGateOpen(gw, peer)){
                        L.state = (byte)BlackstartProtection.linkGateReason(gw, peer);
                        continue; // T3 闸关:不置 transport,不耗预算,F=0。
                    }
                    if(dst == ownGraph){
                        L.state = 6;                          // 013(D34):同网报 6(原同图报 0)
                        continue;
                    }
                    // ---- 搬运链路 ----
                    Acc dstAcc = graphAcc.get(dst);
                    if(dstAcc == null){
                        // 防御(理论不可达:合法 peer 必经步骤 1 入图):目标图未测量 ⇒ 保守判离线,不静默给 0。
                        L.state = -1;
                        continue;
                    }
                    L.transport = true;
                    L.gi = gi;                                // 013(D35):岛统计定位用(registry 序)
                    // 013(D32):读平行数组 tier/grant(缺省 tier=2 / grant=1f;写入端已钳,这里防御再钳一次)
                    int tier = i < gw.linkTier.length ? gw.linkTier[i] : 2;
                    if(tier < 1) tier = 1; else if(tier > 3) tier = 3;
                    float grant = i < gw.linkGrant.length ? gw.linkGrant[i] : 1f;
                    if(!(grant >= 0f)) grant = 0f; else if(grant > 1f) grant = 1f;   // NaN 也接地
                    L.tier = tier;
                    L.grant = grant;
                    float raw = dstAcc.deficit / oneMinusL;   // 钳位前需求(供状态判定;threshold 只压 want,不压它)
                    L.rawDemand = raw;
                    // 012(D20;缺额开搬/停搬阈见 // T2 设计§1.12):在搬/未搬两档滞回,只压 want 不压 rawDemand
                    float prev = i < gw.linkFlow.length ? gw.linkFlow[i] : 0f;
                    float thresholdNeed=BlackstartFusionPolicy.active()?dstAcc.nStar:dstAcc.needed;
                    float threshold = (prev > EPS)
                        ? Math.max(0.25f, 0.01f * thresholdNeed)     // 在搬档
                        : Math.max(0.5f, 0.02f * thresholdNeed);     // 未搬档
                    // 013(D32)拆分 ask/want(数学等价改造):ask = 配额前期望,want = ask × grant
                    L.ask = (dstAcc.deficit >= threshold) ? Math.min(rating, raw) : 0f;
                    L.want = Math.min(L.ask * grant,L.userCapacity);
                    if(L.want > 0f){
                        Acc srcAcc = graphAcc.get(ownGraph);
                        if(srcAcc != null) srcAcc.sumWant += L.want;   // 固定累加序 = 注册表序 × modLinks 序
                        // 013 起 Acc.sumWant 不再参与判定(留档);两级公平改用逐档 scratch sumWantT
                    }
                }else{
                    L.state = 0;                              // 对端非网关 = 记录条目:零处理(rawState=0 恒定)
                }
            }
        }
        if(BlackstartFusionPolicy.active()){
            solveStandard();
        }else{
        // 013(D32):「步骤 3:档序级联 + 两级比例公平」整体替换 012 的步骤 3(源预算)+ 步骤 4(目标预算)。
        // 出处 // T2 设计§1.8 / // 合本§二-2.2-⑥。语义:每档 t∈{3,2,1} 内「先源图预算、再目标图预算」;
        // 两级「可用额度」跨档扣减 —— 高档吃剩的才是低档的(availS/availT 跨档递减)。
        // 退化性(零回归论证):全 tier=2 时仅 t=2 一轮,sumWantT/availS ≡ 012 步骤 3 的 sumWant/surplus、
        // scaleST 公式与累加序不变;sumGetT ≡ 012 步骤 4 的 sumGet(到货口径 Σwant×scaleS×(1−l)),
        // scaleTT 公式不变 ⇒ 默认配置数值与 012 逐位一致。
        // 确定性:求和/赋值遍历一律「注册表序 × modLinks 序」;graphAcc 只做 O(1) 查找与逐图自身 scale 计算
        // (不跨图累加、顺序无关);交换建链顺序 ⇒ 每链 F/状态集合不变(死区落点受墙钟 dt 抖动,不钉逐位)。
        // 写入范围不变量(冻结):L.scaleS/L.scaleT 只在该链所属档的轮次(t==L.tier)写入;
        // 未参与本轮档的链(want=0/非搬运链/他档链)任何轮次都不得改写其 scaleS/scaleT(取 reset 默认)。
        for(Acc a : graphAcc.values()){
            a.availS = Math.max(0f, a.surplus);               // 初始化:每源图可用额度
            a.availT = Math.max(0f, a.deficit);               // 初始化:每目标图可用额度
        }
        for(int t = 3; t >= 1; t--){
            // (a) 清逐图 scratch
            for(Acc a : graphAcc.values()){
                a.sumWantT = 0f;
                a.sumGetT = 0f;
                a.scaleST = 1f;
                a.scaleTT = 1f;
            }
            // (b) 本档逐链 want 汇聚到源图(注册表序 × modLinks 序)
            for(int k = 0; k < linkCount; k++){
                Link L = linkScratch.get(k);
                if(!L.transport || L.want <= 0f || L.tier != t) continue;
                Acc srcAcc = graphAcc.get(L.src);
                if(srcAcc != null) srcAcc.sumWantT += L.want;
            }
            // (c) 逐源图:源预算(可用额度不足才匀分)
            for(Acc a : graphAcc.values()){
                if(a.sumWantT > 0f && a.sumWantT > a.availS) a.scaleST = a.availS / a.sumWantT;
            }
            // (d) 同序扫链:取源档系数,算到货口径贡献并汇聚到目标图(与 (f) 同集)
            for(int k = 0; k < linkCount; k++){
                Link L = linkScratch.get(k);
                if(!L.transport || L.want <= 0f || L.tier != t) continue;
                Acc srcAcc = graphAcc.get(L.src);
                L.scaleS = srcAcc == null ? 1f : srcAcc.scaleST;
                float contrib = L.want * L.scaleS * L.oneMinusL;   // 到货口径(合本 §2.2-F2)
                L.contrib = contrib;
                Acc dstAcc = graphAcc.get(L.dst);
                if(dstAcc != null) dstAcc.sumGetT += contrib;
            }
            // (e) 逐目标图:目标预算(到货口径)
            for(Acc a : graphAcc.values()){
                if(a.sumGetT > 0f && a.sumGetT > a.availT) a.scaleTT = a.availT / a.sumGetT;
            }
            // (f) 同序扫链:写目标档系数 + 跨档扣减两级可用额度(contrib 重算 = 同式同序 ⇒ 同值)
            for(int k = 0; k < linkCount; k++){
                Link L = linkScratch.get(k);
                if(!L.transport || L.want <= 0f || L.tier != t) continue;
                Acc dstAcc = graphAcc.get(L.dst);
                L.scaleT = dstAcc == null ? 1f : dstAcc.scaleTT;
                Acc srcAcc = graphAcc.get(L.src);
                if(srcAcc != null) srcAcc.availS = Math.max(0f, srcAcc.availS - L.want * L.scaleS);
                if(dstAcc != null) dstAcc.availT = Math.max(0f, dstAcc.availT - L.contrib * L.scaleT);
            }
        }

        // ---- 步骤 5:终值 + 斜率/死区(012/D18+D19)+ 原始状态判定(D21 管道输入)----
        for(int k = 0; k < linkCount; k++){
            Link L = linkScratch.get(k);
            if(!L.transport) continue;                    // 非搬运链路 F=0(开断/离线/同网/记录),不经斜率
            // 013(D32/D38):L.scaleT 已由步骤 3 级联逐链写入,此处不再回读 Acc.scaleT。
            float fStar = L.want * L.scaleS * L.scaleT;   // F*(钳位前终值)
            if(fStar < 0f) fStar = 0f;
            if(fStar > L.rating) fStar = L.rating;

            // 012(D18 斜率 + D19 死区;控制律见 // T2 设计§1.7):顺序 = 先死区判定 → 再斜率趋近。
            // F_prev = 上一拍稳定输出(本拍覆写前;新链/载入/开断期间 = 0)。
            float fPrev = L.index < L.owner.linkFlow.length ? L.owner.linkFlow[L.index] : 0f;
            fPrev=BlackstartContinuity.linkBaseline(L.owner,L.target,fPrev,fStar);
            float dDead = Math.max(0.5f, 0.05f * L.rating);
            float dFmax = L.rating * L.slew * settleDtTicks / 60f;
            // 014 修复(013 真机发现):fPrev≈0 时死区捕获会把 fStar≤dDead 的链永久钉死在 0
            // (grant 0→1 也救不回);加 fPrev > EPS 前提 ⇒ 未起步链走斜率分支限速起步。
            if(fStar > 0f && fPrev > EPS && Math.abs(fStar - fPrev) <= dDead){
                L.f = fPrev;                              // 死区保持(吸收微抖,不动)
            }else{
                float d = fStar - fPrev;                  // F*=0(撤负载)不走死区,直接进斜率,保证降到底
                if(d > dFmax) d = dFmax;
                if(d < -dFmax) d = -dFmax;
                L.f = fPrev + d;                          // 差小于 ΔFmax 时精确落位,不夹残值
            }
            // Standard mode treats the current allocation as a hard upper bound.
            // Falling fuel, reserve exhaustion or a removed grant cannot be overdrawn by slew/deadband.
            if(BlackstartFusionPolicy.active())L.f=Math.min(L.f,fStar);
            L.f=Math.min(L.f,L.userCapacity);
            // D21 原始状态判定(饱和条件照 011 不变;去抖由步骤 6 管道统一承担)
            // 013(D33):值域扩 5=受限(系数口径 = grant×scaleS×scaleT < 0.95,且配额前期望 ask>0
            // ⇒ 稳态 F<want×0.95、免疫斜率爬升瞬态);grant=0 ⇒ 0 ⇒ 5(配额压 0);低档饿/多源匀分同理。
            // 优先级(冻结):饱和(1) > 受限(5) > 正常(0)。
            boolean saturated = L.rawDemand > L.rating * 1.05f && L.f >= L.rating * 0.95f;
            L.state = (byte)(saturated ? 1
                : ((L.ask > EPS && L.grant * L.scaleS * L.scaleT < 0.95f) ? 5 : 0));
        }

        } // Legacy compatibility control, including its historical deadband, ends here.
        // ---- 步骤 6:申报回写(先全部注册网关清零,再逐条累加;防链路移除后残留)----
        for(int i = 0; i < registry.size; i++){
            GatewayNode.GatewayNodeBuild gw = registry.get(i);
            gw.linkDrawCmd = 0f;
            gw.linkInjCmd = 0f;
        }
        for(int k = 0; k < linkCount; k++){
            Link L = linkScratch.get(k);
            GatewayNode.GatewayNodeBuild owner = L.owner;
            // 防御:平行数组不变量由第 7 写点维护(复核B-F2:双数组同查;012 扩到去抖两数组)
            if(L.index < owner.linkFlow.length && L.index < owner.linkState.length
                && L.index < owner.linkStateRaw.length && L.index < owner.linkHold.length){
                owner.linkFlow[L.index] = L.f;
                // D9 状态字节(011 口径,保留):1=饱和(钳位前口径)、0=正常、-1=离线·悬挂;
                // 012(D21)起值域扩 2=分闸,并由下列管道 2 拍确认后写 linkState;
                // 013(D33/D34)再扩 5=受限(系数口径)、6=同网(原同图 0)。
                // 012(D21)状态 2 拍去抖管道:raw = L.state(步骤 2/5 各分支已带出);连续 2 拍才翻转 linkState
                byte raw = L.state;
                if(raw == owner.linkStateRaw[L.index]){
                    if(owner.linkHold[L.index] < 2) owner.linkHold[L.index]++;
                }else{
                    owner.linkStateRaw[L.index] = raw;
                    owner.linkHold[L.index] = 1;
                }
                if(owner.linkHold[L.index] >= 2) owner.linkState[L.index] = raw;
            }
            // 017:只读 D21 发布后的状态;不改变控制律、去抖或链路字段语义。
            if(L.index < owner.linkState.length && L.dst != null){
                int state = owner.linkState[L.index];
                if(state == 1 || state == 5){
                    Acc target = graphAcc.get(L.dst);
                    if(target != null) target.throttled = true;
                }
            }
            if(L.transport && L.f > 0f && L.target != null){
                owner.linkDrawCmd += L.f;                 // 需求申报(源侧取电)
                // Standard dispatch already balances THIS frame's intrinsic production and
                // every relay's arrivals/local demand. Old coverage must not shrink only the
                // arriving leg after the matching outgoing leg has been budgeted.
                float cov = BlackstartFusionPolicy.active()?1f:lastCov(L.src);
                L.target.linkInjCmd += L.f * L.oneMinusL * cov;   // 出力申报(到货 = F×(1−l)×lastCov)
            }
        }

        // ---- 步骤 7:遥测 + 对外统计表(013/G4/D35 按岛 facade;原「对外统计表归 G4」注释已由本实装取代)----
        buildIslandStats();
        publishServing(); // T3 当拍回执在 D21 去抖与终值发布之后。
        lastSettleNanos = System.nanoTime() - t0;

        // 复核A-F4 附注:裁掉本拍未用的 scratch 尾部引用(链路数缩减时不滞留旧网关/旧图)
        for(int k = linkCount; k < linkScratch.size; k++){
            Link stale = linkScratch.get(k);
            if(stale.owner != null || stale.target != null || stale.src != null || stale.dst != null){
                stale.reset();
            }
        }
        // T3 第 8 步:当拍回执驱动状态机,电平闸位下一结算拍生效。
        BlackstartProtection.pass();
        // 既有统计与 scratch 收尾已完成,不增加节拍或写链路开断位。
    }

    private static void solveStandard(){
        flowGraphs.clear();
        // Registry is position-sorted. Number graphs by their first live gateway, not hash order.
        for(int i=0;i<registry.size;i++){
            var gw=registry.get(i);PowerGraph g=gw.power==null?null:gw.power.graph;
            if(g==null)continue;                     // 2026-10-14 崩溃回归:graph null 视同离线跳过(graphAcc.get(null) 抛)
            Acc a=graphAcc.get(g);
            if(a!=null && a.flowIndex<0){a.flowIndex=flowGraphs.size;flowGraphs.add(a);}
        }
        if(flowGraphs.isEmpty())return;
        flowSolver.begin(flowGraphs.size,linkCount);
        for(int i=0;i<flowGraphs.size;i++){Acc a=flowGraphs.get(i);flowSolver.node(i,a.surplus,a.deficit);}
        for(int k=0;k<linkCount;k++){
            // 2026-10-14 崩溃回归(真机 solveStandard:600 "key cannot be null"):非搬运条目(记录/悬挂/离线)
            // src/dst 合法为 null,而 arc ObjectMap.get(null) 抛异常;null 键 ⇒ index 0 + 容量 0(容量 0 边在
            // DirectedFlowSolver 全路径被跳过,语义与修复前一致,只是不再崩)。
            Link l=linkScratch.get(k);Acc s=l.src==null?null:graphAcc.get(l.src),d=l.dst==null?null:graphAcc.get(l.dst);
            flowSolver.edge(k,s==null?0:s.flowIndex,d==null?0:d.flowIndex,
                l.transport && l.grant>0?l.userCapacity:0,l.oneMinusL,l.tier);
        }
        flowSolver.prepare();
        for(int k=0;k<linkCount;k++){
            Link l=linkScratch.get(k);if(!l.transport)continue;
            float bound=l.userCapacity;
            float previous=l.index<l.owner.linkFlow.length?l.owner.linkFlow[l.index]:0;
            previous=BlackstartContinuity.linkBaseline(l.owner,l.target,previous,bound);
            double cap=Math.min(bound,Math.max(0,previous)+l.rating*l.slew*settleDtTicks/60d);
            l.frameCapacity=(float)cap;flowSolver.capacity(k,l.grant>0?cap:0);
        }
        flowSolver.solve();
        boolean partialGrant=false;
        for(int k=0;k<linkCount;k++){
            Link l=linkScratch.get(k);if(!l.transport)continue;
            // A first-path request is diagnostic, not a hard transport ceiling. The actual
            // path solution includes extra losses when a bottleneck requires a longer route.
            l.rawDemand=(float)Math.max(flowSolver.flow(k),flowSolver.request(graphAcc.get(l.dst).flowIndex)/l.oneMinusL);
            l.ask=Math.min(l.rating,l.rawDemand);l.want=l.ask*l.grant;
            if(l.grant>0&&l.grant<1){partialGrant=true;flowSolver.capacity(k,Math.min(l.frameCapacity,l.want));}
        }
        if(partialGrant)flowSolver.solve();
        for(int k=0;k<linkCount;k++){
            Link l=linkScratch.get(k);if(!l.transport)continue;l.f=(float)flowSolver.flow(k);
            l.scaleS=l.want>EPS?Math.min(1,l.f/l.want):1;l.scaleT=1;
            boolean saturated=l.rawDemand>l.rating*1.05f && l.f>=l.rating*.95f;
            l.state=(byte)(saturated?1:l.ask>EPS && l.grant*l.scaleS<.95f?5:0);
        }
        flowGraphs.clear(); // No extra graph references survive this settlement.
    }

    /** 上一拍源图覆盖率:clamp(lastProduced / max(lastNeeded, EPS), 0, 1);needed=0 → 1(修⑤)。 */
    private static float lastCov(PowerGraph g){
        if(g == null) return 1f;
        float ln = g.getLastPowerNeeded();
        if(!Float.isFinite(ln) || ln <= EPS) return 1f;   // 复核A-F4:NaN 纵深防御
        float c = g.getLastPowerProduced() / ln;
        if(!Float.isFinite(c) || c < 0f) return 0f;
        if(c > 1f) return 1f;
        return c;
    }

    /** 把本拍累加器全部归还对象池并清空映射(稳态复用,不 new)。 */
    private static void clearAccs(){
        for(Acc a : graphAcc.values()){
            a.reset();
            accPool.add(a);
        }
        graphAcc.clear();
    }

    /** 取(必要时建)某图的累加器;对象来自池,首次不足才 new。 */
    private static Acc accOf(PowerGraph g){
        Acc a = graphAcc.get(g);
        if(a == null){
            a = accPool.size > 0 ? accPool.pop() : new Acc();
            a.reset();
            a.graph = g;
            graphAcc.put(g, a);
        }
        return a;
    }

    /** 取第 n 条链路 scratch(按需增长,之后复用)。 */
    private static Link linkAt(int n){
        if(n < linkScratch.size) return linkScratch.get(n);
        Link l = new Link();
        linkScratch.add(l);
        return l;
    }

    /**
     * 图级累加器(纯运行时 scratch):sumInj/sumDraw 为申报聚合(步骤 1 第一遍),
     * pStar/nStar/surplus/deficit 为扣自家后的口径;sumWant/sumGet/scaleS/scaleT 供两级预算。
     *
     * 013(G4/D32)追加:availS/availT = 两级「可用额度」(跨档递减);sumWantT/sumGetT/scaleST/scaleTT
     * = 逐档 scratch(每档 (a) 清零后重算)。原 sumWant/sumGet/scaleS/scaleT 013 起【闲置留档,不删】
     * (sumWant 仍在步骤 2 累加供对照;两级公平已改走 sumWantT/sumGetT/scaleST/scaleTT)。
     */
    private static final class Acc {
        PowerGraph graph;
        boolean throttled;                           // 017:本图至少一条入链状态为1/5
        GatewayNode.GatewayNodeBuild rep;
        int flowIndex;
        float sumInj, sumDraw;
        float produced, needed, pStar, nStar;
        float surplus, deficit;
        float sumWant, sumGet, scaleS, scaleT;        // 012 口径:013 起闲置留档(见上注)
        float availS, availT;                          // 013(D32):两级可用额度(跨档扣减)
        float sumWantT, sumGetT, scaleST, scaleTT;     // 013(D32):逐档 scratch

        void reset(){
            graph = null;
            flowIndex = -1;
            throttled = false;
            rep = null;
            sumInj = sumDraw = 0f;
            produced = needed = pStar = nStar = 0f;
            surplus = deficit = 0f;
            sumWant = sumGet = 0f;
            scaleS = scaleT = 1f;
            availS = availT = 0f;
            sumWantT = sumGetT = 0f;
            scaleST = scaleTT = 1f;
        }
    }

    /** 单链路 scratch(纯运行时;每拍按注册表序 × modLinks 序重填,对象复用)。 */
    private static final class Link {
        GatewayNode.GatewayNodeBuild owner;
        GatewayNode.GatewayNodeBuild target;
        int index;
        int gi;                                       // 013(D35):owner 的 registry 序(岛统计定位)
        int tier;                                     // 013(D32):档序(读值钳 [1,3];默认 2)
        PowerGraph src, dst;
        float rating;
        float userCapacity;
        float frameCapacity;
        float slew = 1f;                              // 012(D18):owner block 级斜率上限快照
        float rawDemand;                              // 钳位前需求(状态判定用)
        float ask;                                    // 013(D32):配额前期望
        float grant;                                  // 013(D32):配额(默认 1f)
        float want, scaleS, scaleT, f;
        float contrib;                                // 013(D32):本档到货口径贡献(步骤 3(f) 扣额度用)
        float oneMinusL = 1f;
        byte state;

        /** 搬运链路 = true;记录条目 / 离线 / 同网均 false。 */
        boolean transport;

        void reset(){
            owner = null;
            target = null;
            index = 0;
            gi = 0;
            tier = 2;
            src = dst = null;
            rating = 30f;
            userCapacity = 30f;
            slew = 1f;
            rawDemand = ask = 0f;
            grant = 1f;
            want = f = 0f;
            scaleS = scaleT = 1f;
            contrib = 0f;
            oneMinusL = 1f;
            state = 0;
            transport = false;
        }
    }

    // ===== 探针辅助(012) =====
    // (T2 设计§3.3 / 任务-012 D23/D24)
    // 命令层(BlackstartMod 的 handler)只做:参数解析 / 范围校验 / 调用 / 回显;
    // 内核逻辑(写值 + 置脏)都在这里,不许放 handler。
    // 均为命令路径(非稳态),允许 String 装箱/分配 —— 与 D15 结算稳态零分配纪律不冲突。
    // ============================================================

    /** 014 订阅面:脏纪元。markDirty() = dirty=true + dirtyEpoch++(T4 SafetyNet 每拍比对重建岛)。 */
    public static long dirtyEpoch = 0L;

    /** 统一置脏入口(全仓唯一 `dirty = true` 写点;纪元与 dirty 恒成对)。 */
    public static void markDirty(){
        dirty = true;
        dirtyEpoch++;
    }

    /**
     * bs-tune rate:写 block 级额定吞吐(全图共享)、置脏,返回旧值供回显。
     * 范围 [0.1,255] 由命令层校验(内核直接写)。
     */
    public static float tuneRate(float v){
        float old = BlackstartBlocks.gatewayNode.linkRating;
        BlackstartBlocks.gatewayNode.linkRating = v;
        markDirty();
        return old;
    }

    /**
     * bs-tune loss:经 {@link BlackstartParams#set(String, String)} 唯一写入门写 rules.tags、置脏,
     * 返回旧值;写被拒(set 返回 false)时返回 {@link Float#NaN}。
     */
    public static float tuneLoss(float v){
        float old = BlackstartParams.getFloat(BlackstartParams.KEY_LOSS, BlackstartParams.DEFAULT_LOSS);
        if(!BlackstartParams.set(BlackstartParams.KEY_LOSS, String.valueOf(v))) return Float.NaN;
        markDirty();
        return old;
    }

    /**
     * bs-tune settle:写结算节拍 SETTLE_TICKS、置脏(下一拍立即生效),返回旧值。
     * 范围 [2,6] 由命令层校验(内核直接写)。
     */
    public static int tuneSettle(int v){
        int old = SETTLE_TICKS;
        SETTLE_TICKS = v;
        markDirty();
        return old;
    }

    /**
     * bs-tune sto:写 block 级储能外送底仓比例、置脏,返回旧值。
     * 范围 [0.1,0.5] 由命令层校验(内核直接写)。allowStorageExport(布尔)本批不进命令。
     */
    public static float tuneSto(float v){
        float old = BlackstartBlocks.gatewayNode.storageReserveFrac;
        BlackstartBlocks.gatewayNode.storageReserveFrac = v;
        markDirty();
        return old;
    }

    /**
     * bs-open:开断/闭合 (x1,y1) 处网关指向 pack(x2,y2) 的自管链路。
     * pack 用游戏公式 {@code (x<<16)|(y&0xFFFF)}(x 在高位);找不到网关/链路条目如实返回。
     *
     * 词义(收编裁定 2026-10-08,依 T2 设计 §6.2 D5 冻结行「close 时 F 立即 0、状态=2;open 后爬坡回升」
     *  + 任务-012 D24 字面 `!open?false:true`(= open),权威序 设计原文 > 任务书):
     *   open=true  ⇒ linkOpen=true(闭合/导通):恢复后从 F_prev=0 按斜率爬升(D18);
     *   open=false ⇒ linkOpen=false(分闸)    :步骤 2 的 !linkOpen 分支 F=0、rawState=2(D22)。
     * (任务书 D22「close 恢复后」为措辞滑坡,按「恢复后」读;dsh 偏差 1 的 !open 翻转已驳回。)
     *
     * @return 0 = ok(已置 dirty,下一拍立即重算);1 = (x1,y1) 处无网关节点;2 = 无此链路条目
     */
    public static int openLink(int x1, int y1, int x2, int y2, boolean open){
        if(Vars.world == null) return 1;
        Tile tile = Vars.world.tile(x1, y1);                       // 越界 → null(自带四界检查)
        if(tile == null || !(tile.build instanceof GatewayNode.GatewayNodeBuild gw)) return 1;
        int packed = (x2 << 16) | (y2 & 0xFFFF);                   // 游戏 pack 公式(x 在高位)
        int idx = gw.modLinks.indexOf(packed);
        if(idx < 0 || idx >= gw.linkOpen.length) return 2;
        BlackstartContinuity.linkCommand(gw,packed,1,open?1:0);
        gw.linkOpen[idx] = open;                                   // open=true → 闭合(通);close → 分闸(F=0/state=2)
        markDirty();
        return 0;
    }

    /**
     * bs-tier:置 (ownerPos 处网关)→ targetPacked 条目的档序 tier(内核直接写平行数组 + 置脏)。
     * ownerPos / targetPacked 均为游戏 pack 坐标({@code (x<<16)|(y&0xFFFF)};x 在高位)。
     * tier 钳 [1,3](防御;命令层已校验)。
     *
     * @return 0 = ok(已置 dirty,下一拍生效)/ 1 = ownerPos 处无网关 / 2 = targetPacked 无此链路条目
     */
    public static int setTier(int ownerPos, int targetPacked, int tier){
        if(Vars.world == null) return 1;
        Building b = Vars.world.build(ownerPos);                   // 越界 → null(自带四界检查)
        if(!(b instanceof GatewayNode.GatewayNodeBuild gw)) return 1;
        int idx = gw.modLinks.indexOf(targetPacked);
        if(idx < 0 || idx >= gw.linkTier.length) return 2;
        if(tier < 1) tier = 1; else if(tier > 3) tier = 3;         // 内核钳制兜底
        BlackstartContinuity.linkCommand(gw,targetPacked,2,tier);
        gw.linkTier[idx] = tier;
        markDirty();
        return 0;
    }

    /**
     * bs-grant:置 (ownerPos 处网关)→ targetPacked 条目的配额 grant(内核直接写平行数组 + 置脏)。
     * grant 钳 [0,1](NaN → 0,防御;命令层已校验)。
     *
     * @return 0 = ok / 1 = ownerPos 处无网关 / 2 = targetPacked 无此链路条目
     */
    public static int setGrant(int ownerPos, int targetPacked, float grant){
        if(Vars.world == null) return 1;
        Building b = Vars.world.build(ownerPos);
        if(!(b instanceof GatewayNode.GatewayNodeBuild gw)) return 1;
        int idx = gw.modLinks.indexOf(targetPacked);
        if(idx < 0 || idx >= gw.linkGrant.length) return 2;
        if(!(grant >= 0f)) grant = 0f; else if(grant > 1f) grant = 1f;
        BlackstartContinuity.linkCommand(gw,targetPacked,3,grant);
        gw.linkGrant[idx] = grant;
        markDirty();
        return 0;
    }

    /**
     * 回显辅助(只读;handler 读旧值的唯一来源;读旧值【不】置脏)。
     *
     * @return ownerPos 处网关对 targetPacked 条目的当前 tier;无网关/无条目 → -1
     */
    public static int getTier(int ownerPos, int targetPacked){
        if(Vars.world == null) return -1;
        Building b = Vars.world.build(ownerPos);
        if(!(b instanceof GatewayNode.GatewayNodeBuild gw)) return -1;
        int idx = gw.modLinks.indexOf(targetPacked);
        if(idx < 0 || idx >= gw.linkTier.length) return -1;
        return gw.linkTier[idx];
    }

    /**
     * 回显辅助(只读;读旧值【不】置脏)。
     *
     * @return ownerPos 处网关对 targetPacked 条目的当前 grant;无网关/无条目 → -1f(grant 合法域 [0,1],负值为哨兵)
     */
    public static float getGrant(int ownerPos, int targetPacked){
        if(Vars.world == null) return -1f;
        Building b = Vars.world.build(ownerPos);
        if(!(b instanceof GatewayNode.GatewayNodeBuild gw)) return -1f;
        int idx = gw.modLinks.indexOf(targetPacked);
        if(idx < 0 || idx >= gw.linkGrant.length) return -1f;
        return gw.linkGrant[idx];
    }

    // ============================================================
    // 按岛统计 facade(013/G4/D35;字段名/键/序冻结)
    // 岛定义 = 注册表网关经 transport 链的连通分量(链双向视作连通;记录条目/离线/关断链不并岛;
    //          每个网关恰属一岛,含孤单网关)。
    // 岛键 = 岛内全部网关 packed pos 升序最小者(禁裸 graphId —— graph 跨 reflow 重编号)。
    // 逐区锚(ZoneKey)= 该区(原生子图)全部建筑 packed pos 升序最小者(勿用网关最小 pos)。
    // 计算时机 = 步骤 7;只读结算已有数据(含原生图成员表 graph.all),不扫 tile 网格、不读新坐标。
    // 快照对象与数组按岛缓存复用(岛集/尺寸未变时不重建 ⇒ 稳态零分配)。
    // ============================================================

    /** 本拍岛快照视图(按岛键升序;对象来自 {@link #islandPool},数组跨拍复用)。 */
    private static final Seq<Stats> islandView = new Seq<>();

    /** 岛快照对象池(跨拍复用;岛数缩减不回收对象,增长才新建)。 */
    private static final Seq<Stats> islandPool = new Seq<>();

    /** 并查集父数组(按 registry 序;每拍复用)。 */
    private static int[] ufParent = new int[0];
    /** registry 序网关 → 岛序号(-1 = 未分配)。 */
    private static int[] islandOfGw = new int[0];
    /** registry 序根 → 岛序号(-1 = 未分配;仅根位置有效)。 */
    private static int[] rootIsland = new int[0];

    /** 全局区(原生子图)槽表:去重后的 PowerGraph;zoneUsed = 本拍有效槽数。 */
    private static final Seq<PowerGraph> zoneGraph = new Seq<>();
    private static int zoneUsed;
    private static float[] zoneS = new float[0];
    private static float[] zoneD = new float[0];
    private static long[] zoneKey = new long[0];
    /** 岛内区去重时间戳(每拍清零后再用 stamp=岛序号+1)。 */
    private static int[] zoneStamp = new int[0];
    /** 岛内区槽排序缓冲 / 岛内搬运链排序缓冲(复用)。 */
    private static int[] zoneBuf = new int[0];
    private static int[] linkBuf = new int[0];

    /** 本拍岛数(按岛键升序;快照由步骤 7 刷新;空注册表 → 0)。 */
    public static int statsCount(){
        return islandView.size;
    }

    /** i∈[0,statsCount) 的岛键(升序);越界 → -1。 */
    public static long statsKeyAt(int i){
        if(i < 0 || i >= islandView.size) return -1L;
        return islandView.get(i).key;
    }

    /** 按岛键取本拍快照;无此岛 → null。 */
    public static Stats stats(long islandKey){
        for(int i = 0; i < islandView.size; i++){
            Stats s = islandView.get(i);
            if(s.key == islandKey) return s;
        }
        return null;
    }

    /**
     * 岛快照(013/G4/D35;字段名/键/序冻结)。
     * S/D = 岛内 Σ intrinsic surplus / Σ deficit(步骤 1 口径,已扣源侧保护/含储能可行放电;
     * 不含链路申报自身)。
     * perZoneInflow 序 = ZoneKey 升序(同区多网关合并一条);值 = 全部 transport 链按 dst 区聚合的
     * 到货 ΣF×(1−l)(019:含非本岛链;共享区在两个岛条目中同值出现);perZoneNeed 与 perZoneInflow 同序
     * (015;= 该区 zoneD 缺口口径,供 T4 逐区需求);perLinkFlow 序 = (ownerPos, targetPacked)
     * 升序(非搬运链不列入);deliveredFlow 与 perLinkFlow 同序,= F×(1−l)(不带 cov)。
     */
    public static final class Stats {
        public long key;
        public float S, D;
        public float[] perZoneInflow = new float[0];
        public float[] perZoneNeed = new float[0];   // 015:逐区需求(= zoneD 口径缺口);序 = ZoneKey 升序
        public float[] perLinkFlow = new float[0];
        public float[] deliveredFlow = new float[0];
    }

    /** 并查集/岛分配 scratch 扩容(稳态不变则不分配)。 */
    private static void ensureIslandScratch(int n){
        if(ufParent.length < n){
            ufParent = new int[n];
            islandOfGw = new int[n];
            rootIsland = new int[n];
        }
    }

    /** 全局区表数组扩容(013 复核修:本扩容发生在步骤 4 写循环【中途】,必须搬运旧值——零填充重建会抹掉本拍已写槽,致该拍 S/D 缺项、ZoneKey 序失效)。 */
    private static void ensureZoneArrays(int n){
        if(zoneS.length < n){
            zoneS = Arrays.copyOf(zoneS, n);
            zoneD = Arrays.copyOf(zoneD, n);
            zoneKey = Arrays.copyOf(zoneKey, n);
            zoneStamp = Arrays.copyOf(zoneStamp, n);
        }
    }

    /** 岛内区槽排序缓冲扩容(013 复核修:写循环中途调用,同 ensureZoneArrays 须搬运旧值)。 */
    private static void ensureZoneBuf(int n){
        if(zoneBuf.length < n) zoneBuf = Arrays.copyOf(zoneBuf, n);
    }

    /** 岛内搬运链排序缓冲扩容(013 复核修:同上)。 */
    private static void ensureLinkBuf(int n){
        if(linkBuf.length < n) linkBuf = Arrays.copyOf(linkBuf, n);
    }

    /**
     * registry 序索引定位(transport 链对端;零分配)。registry 每拍已在步骤 0 按 pos 升序排序,
     * 故按 packed pos 二分即可;命中后再做对象同一性校验(同 pos 一建筑,校验为防御)。
     */
    private static int registryIndexOf(GatewayNode.GatewayNodeBuild gw){
        int p = gw.pos();
        int lo = 0, hi = registry.size - 1;
        while(lo <= hi){
            int mid = (lo + hi) >>> 1;
            GatewayNode.GatewayNodeBuild m = registry.get(mid);
            int mp = m.pos();
            if(mp == p) return m == gw ? mid : -1;
            if(mp < p) lo = mid + 1; else hi = mid - 1;
        }
        return -1;
    }

    /** 全局区槽查找(对象同一性线性扫;零分配,不依赖任何 Map 迭代序);无 → -1。 */
    private static int zoneSlot(PowerGraph g){
        for(int i = 0; i < zoneUsed; i++){
            if(zoneGraph.get(i) == g) return i;
        }
        return -1;
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

    /** 搬运链排序判据:(ownerPos, targetPacked) 升序。 */
    private static boolean linkBefore(int a, int b){
        Link x = linkScratch.get(a), y = linkScratch.get(b);
        int xo = x.owner.pos(), yo = y.owner.pos();
        if(xo != yo) return xo < yo;
        return x.target.pos() < y.target.pos();
    }

    /**
     * 步骤 7 尾:按岛聚合构建对外统计快照(D35)。全程只读本拍结算结果 + 原生图成员表,
     * 不扫 tile 网格、不读新坐标;遍历序 = registry 序(已按 pos 升序)⇒ 岛序号天然按岛键升序。
     */
    private static void buildIslandStats(){
        islandView.clear();
        int n = registry.size;
        if(n == 0) return;

        ensureIslandScratch(n);
        for(int i = 0; i < n; i++){
            ufParent[i] = i;
            islandOfGw[i] = -1;
            rootIsland[i] = -1;
        }

        // 1) 并查集:仅搬运链(transport)并岛,链双向视作连通(记录/离线/关断/同网链不并岛)
        for(int k = 0; k < linkCount; k++){
            Link L = linkScratch.get(k);
            if(!L.transport || L.target == null) continue;
            int ti = registryIndexOf(L.target);
            if(ti >= 0) ufUnion(L.gi, ti);
        }

        // 2) 分配岛序号(registry 已按 pos 升序 ⇒ 首次出现序 = 该岛最小网关 pos ⇒ 岛键升序)
        int islandN = 0;
        for(int i = 0; i < n; i++){
            int r = ufFind(i);
            if(rootIsland[r] < 0) rootIsland[r] = islandN++;
            islandOfGw[i] = rootIsland[r];
        }

        // 3) 取/复位岛快照对象(池化)+ 岛键 = 岛内网关 packed pos 最小者
        for(int isl = 0; isl < islandN; isl++){
            Stats st;
            if(isl < islandPool.size){
                st = islandPool.get(isl);
            }else{
                st = new Stats();
                islandPool.add(st);
            }
            st.key = Long.MAX_VALUE;
            st.S = 0f;
            st.D = 0f;
            islandView.add(st);
        }
        for(int i = 0; i < n; i++){
            long p = registry.get(i).pos();
            Stats st = islandView.get(islandOfGw[i]);
            if(p < st.key) st.key = p;
        }

        // 4) 全局区表:去重后的原生图 → 槽;ZoneKey = graph.all 全部建筑 packed pos 最小者
        for(int s = 0; s < zoneUsed; s++) zoneStamp[s] = 0;   // 复用槽的时间戳清零(先于 zoneUsed 归零)
        zoneUsed = 0;
        for(int i = 0; i < n; i++){
            PowerGraph g = registry.get(i).power == null ? null : registry.get(i).power.graph;
            if(g == null) continue;
            if(zoneSlot(g) >= 0) continue;
            ensureZoneArrays(zoneUsed + 1);
            int slot = zoneUsed++;
            if(slot < zoneGraph.size) zoneGraph.set(slot, g); else zoneGraph.add(g);
            Acc a = graphAcc.get(g);
            zoneS[slot] = a == null ? 0f : a.surplus;
            zoneD[slot] = a == null ? 0f : a.deficit;
            long mk = Long.MAX_VALUE;
            Seq<Building> all = g.all;
            if(all != null){
                for(int m = 0; m < all.size; m++){
                    Building bd = all.get(m);
                    if(bd == null) continue;
                    long bp = bd.pos();
                    if(bp >= 0L && bp < mk) mk = bp;
                }
            }
            zoneKey[slot] = mk;
        }
        // 4b) 裁尾(013 复核修):清掉本拍未用槽的旧图引用——与 settle() 尾部 scratch 裁剪同纪律,
        // 不滞留上一张图的 PowerGraph(及其 all 建筑表);稳态(容量==用量)零迭代、零分配。
        for(int s = zoneUsed; s < zoneGraph.size; s++) zoneGraph.set(s, null);

        // 5) 逐岛:区去重(按 ZoneKey 升序)+ 到货聚合 + 搬运链(按 owner/target 升序)
        for(int isl = 0; isl < islandN; isl++){
            Stats st = islandView.get(isl);
            int stamp = isl + 1;

            // 5a) 岛内 distinct 区(同区多网关合并一条;插入排序按 ZoneKey 升序)
            int zc = 0;
            for(int i = 0; i < n; i++){
                if(islandOfGw[i] != isl) continue;
                PowerGraph g = registry.get(i).power == null ? null : registry.get(i).power.graph;
                if(g == null) continue;
                int so = zoneSlot(g);
                if(so < 0 || zoneStamp[so] == stamp) continue;
                zoneStamp[so] = stamp;
                st.S += zoneS[so];
                st.D += zoneD[so];
                ensureZoneBuf(zc + 1);
                int j = zc++;
                zoneBuf[j] = so;
                while(j > 0 && zoneKey[zoneBuf[j - 1]] > zoneKey[zoneBuf[j]]){
                    int tmp = zoneBuf[j - 1]; zoneBuf[j - 1] = zoneBuf[j]; zoneBuf[j] = tmp;
                    j--;
                }
            }
            if(st.perZoneInflow.length != zc) st.perZoneInflow = new float[zc];   // 尺寸未变不重建
            for(int z = 0; z < zc; z++) st.perZoneInflow[z] = 0f;
            if(st.perZoneNeed.length != zc) st.perZoneNeed = new float[zc];       // 015:尺寸未变不重建(同 perZoneInflow 纪律)
            for(int z = 0; z < zc; z++) st.perZoneNeed[z] = zoneD[zoneBuf[z]];    // 015:zoneBuf 已保 ZoneKey 升序 ⇒ 与 SafetyNet zones 序对齐

            // 5b) 岛内搬运链(transport;按 (ownerPos,targetPacked) 升序;插入排序)
            int lc = 0;
            for(int k = 0; k < linkCount; k++){
                Link L = linkScratch.get(k);
                if(!L.transport || L.target == null) continue;
                if(islandOfGw[L.gi] != isl) continue;
                ensureLinkBuf(lc + 1);
                int j = lc++;
                linkBuf[j] = k;
                while(j > 0 && linkBefore(linkBuf[j], linkBuf[j - 1])){
                    int tmp = linkBuf[j - 1]; linkBuf[j - 1] = linkBuf[j]; linkBuf[j] = tmp;
                    j--;
                }
            }
            if(st.perLinkFlow.length != lc) st.perLinkFlow = new float[lc];
            if(st.deliveredFlow.length != lc) st.deliveredFlow = new float[lc];

            // 5c) 逐链:linkFlow = F;delivered = F×(1−l)(本岛链;到货累加移至 5d 全链聚合)
            for(int q = 0; q < lc; q++){
                Link L = linkScratch.get(linkBuf[q]);
                st.perLinkFlow[q] = L.f;
                st.deliveredFlow[q] = L.f * L.oneMinusL;
            }

            // 5d) 到货全链聚合(019):遍历全部 transport 链,按 dst 图落本岛 zoneBuf 槽。
            //     共享区(同 ZoneKey 跨岛复用)两侧读法一致 ⇒ netImporter 与岛序无关。
            for(int k = 0; k < linkCount; k++){
                Link L = linkScratch.get(k);
                if(!L.transport || L.target == null) continue;
                PowerGraph dg = L.target.power == null ? null : L.target.power.graph;
                if(dg == null) continue;
                int so = zoneSlot(dg);
                if(so < 0) continue;
                float deliv = L.f * L.oneMinusL;
                for(int z = 0; z < zc; z++){
                    if(zoneBuf[z] == so){
                        st.perZoneInflow[z] += deliv;
                        break;
                    }
                }
            }
        }
    }
}
