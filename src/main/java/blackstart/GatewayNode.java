package blackstart;

import arc.math.geom.Point2;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.modules.PowerModule;

/**
 * 工程节点(网关)。
 *
 * 设计全文见 docs/构想-v0.md §5.3;机制见 docs/S1-机制定稿-20261005.md。
 *
 * S1:以 PowerNode 作连线端点 —— 靠 connectedPower=false 拒**节点型入边**;
 *     出边与机器类入边另见 S1 定稿(docs/S1-机制定稿-20261005.md);要全封自动边需再配 autolink=false。
 *     (旧注释「拒一切入边」已按 2026-10-05 定稿更正。)
 *
 * S3:新增【自管链路】字段 modLinks(与 power.links 完全无关 —— 红线),
 *     走两条原生通道往返:
 *       · 存档     = Building 序列化覆写(version / write / read);
 *       · 蓝图/联机 = config 槽(覆写 config(),并接管 Integer / Point2[] 两个处理器)。
 *     全文见 docs/S3-结论书-草案-20261005.md §3(方案 c)。
 *
 * S3.1(2026-10-05,三项合版 (a) 对端残留清理 + (b) ensure/手动交互 + (c) changeTeam 剪枝;
 *      全文见 docs/S31-设计-合本-20261005.md,不变量 I1-I8 见其 §2.1):
 *   · 通道契约(I5/I6):Integer = 幂等 ensure(只增不减;机器原格拆→重建经此通道,必须幂等);
 *     Point2[] = 权威整表替换(一切删除意图的唯一表达,不得依赖 Integer 通道语义)。
 *   · 不变量面(I1-I3):网关 power.links 反序列化收尾后恒空(I1)、全图无对端指入现存网关 pos 的残留边(I2)、
 *     网关与原生图分离(I3);唯一红线例外 = 合本 §2.3 的「反序列化路径两类写入」
 *     (read() 自表归一化 + afterReadAll 对端残留清理)。
 *   · 生命周期(I7/I8):写点封闭 6 处(ensureModLink / replaceModLinks / pruneModLinks / onRemoved /
 *     onDestroyed / read),消费侧一律过 modLinkValid;读档收尾见 afterReadAll,真实队换队剪枝见 changeTeam。
 *
 * T2(2026-10-08,T2-G0+G1 批;设计 §1.11 / 合本 §二-2.2):本类新增【申报面】——网关自 T2 起
 * 额外成为其所在原生图的申报端(双通道:取电=需求申报、发电=出力申报;两图永不合并):
 *   · Block 级参数字段:linkRating / linkLoss / allowStorageExport / storageReserveFrac;
 *   · Build 级运行时字段(linkInjCmd / linkDrawCmd / 每链路平行数组 / registered)——【纯运行时:
 *     不持久化、不进 config、不广播】(P2:重建不存;write()/read() 布局零字节变化,MOD_REV 仍 =1);
 *   · 第 7 写点纪律:每链路平行数组由 syncLinkRuntime() 维护,挂在既有 6 个 modLinks 写点
 *     (ensureModLink / replaceModLinks / pruneModLinks / onRemoved / onDestroyed / read)之后;
 *   · ⚠ sync 禁令(合本 §2.2-⑨):本类 block.sync 必须保持 false,禁改 —— 大表带宽 +
 *     read() 带副作用,禁被 readSync 高频调用;
 *   · ⚠ update 旗标保持 false(需求激活走 BlackstartLinks 幂等写 shouldConsumePower A 路,合本 §2.2-②)。
 *   结算内核(注册表/节流/公式)见 blackstart.BlackstartLinks;设计全文 T2-链路控制核心.md。
 */
public class GatewayNode extends PowerNode {
    private static final mindustry.world.meta.Stat LINK_CAPACITY=new mindustry.world.meta.Stat("blackstartlinkcapacity",mindustry.world.meta.StatCat.power);

    @Override public void setStats(){
        super.setStats();
        if(!(this instanceof BreakerNode) && !(this instanceof ZoneSwitchNode))
            stats.add(LINK_CAPACITY,linkRating*60f,mindustry.world.meta.StatUnit.powerSecond);
    }

    /**
     * 自管链路数据的序列化版本(revision)。
     * 作为建筑 chunk 的首字节写进存档;读档时 revision < MOD_REV 视为「没有本 mod 段的旧档」。
     */
    public static final byte MOD_REV = 1;

    /**
     * 自管链路条数上限(2026-10-05 复核后修复新增;依据 verify:config#2 / verify:serialization#2)。
     * 由来:蓝图/联机载荷走 TypeIO 的 Point2[] 通道,长度硬上限 255(超限直接抛 ArcRuntimeException,
     * 蓝图保存失败);同时 255 < 65535,保证存档 write.s(modLinks.size) 永不截断 ——
     * 顺带消掉「>65535 条时条数截断、元素照写」的同款流错位。
     * 不变量:任何路径(read 回读含在内)之后 modLinks.size 都 ≤ MAX_MOD_LINKS。
     */
    public static final int MAX_MOD_LINKS = 255;

    // ============================================================
    // T2 Block 级参数(设计 §1.11 / §1.12;合本 §二-2.2)
    // 纯编译期默认位 + 探针显示;结算口径另有权威来源(见各字段注)
    // ============================================================

    /** 额定吞吐/tick(源侧取电上限;显示 = ×60/s)。§1.12 校准锚:钍堆 15、差速 18、蒸汽 5.5、冲击 130。 */
    public float linkRating = 30f;

    /**
     * 斜率上限(单位 = ×R/秒;范围 0.25–4,默认 1)。
     * 每结算拍终值趋近步长 {@code ΔFmax = R × linkSlew × dtTick / 60}(升/降同速,差小于 ΔFmax 精确落位)。
     * 本批(012)仅留档 + 注释,【不进 bs-tune】(接线归 0.1 面板轮)。出处 // T2 设计§1.12。
     */
    public float linkSlew = 1f;

    /**
     * 每跳损耗比例(编译期默认位,0–0.04)。
     * ⚠ 结算损耗的【唯一权威】= {@code BlackstartParams.getFloat("blackstart.loss", 0.03f)}(合本 §2.2-⑦);
     * 本字段只留档 + 探针显示,结算不许读它。
     */
    public float linkLoss = 0.03f;

    /** Grade loss relative to the world's existing ordinary-line loss setting. */
    public float linkLossScale = 1f;
    /** Negative retains legacy links; positive limits managed links in tile units. */
    public float managedLinkRange = -1f;
    public int managedMaxLinks=MAX_MOD_LINKS;
    private int managedLinkLimit(){return Math.max(0,Math.min(MAX_MOD_LINKS,managedMaxLinks));}

    public float effectiveLinkLoss(){
        float value=BlackstartParams.getFloat(BlackstartParams.KEY_LOSS,BlackstartParams.DEFAULT_LOSS);
        if(!Float.isFinite(value))value=BlackstartParams.DEFAULT_LOSS;
        value=Math.max(0f,Math.min(.99f,value));
        float multiplier=Float.isFinite(linkLossScale)?Math.max(0f,linkLossScale):1f;
        return Math.min(.99f,value*multiplier);
    }

    /** 储能外送开关(默认 true;采纳 T4 契约:岛盈余 S 必须含电池可行放电)。 */
    public boolean allowStorageExport = true;

    /** 储能外送底仓比例(默认 25%:外送不动底仓;分母 = getTotalBatteryCapacity(),修 F4)。 */
    public float storageReserveFrac = 0.25f;

    /**
     * S3.1(a) 对端残留清理的跨实例门闩(2026-10-05;合本 §3.1)。
     *
     * Building 实例每次读档都会新建,跨实例协调只能挂外层 static。`read()` 在 super.read 之后置位,
     * `afterReadAll()` 由【首个回调的网关】消费并复位 ⇒ 每次世界加载恰清一次。
     * MP 运行期 BlockSnapshot(readSync)会把 flag 置回 true,但该会话无 afterReadAll,
     * 只会被下一次读档之前消费、而那一次也会重新置位 ⇒ 无可观察差异。
     */
    private static boolean residueCleanupArmed = false;

    /**
     * S3.1(a) 对端残留迁移清理(合本 §3.2):读档收尾调一次全局两阶段清理。
     *
     * 背景(F6):空壳时代引擎写的镜像边把「现存网关 pos」留在对端 power.links 里;map 区读完的
     * endMapLoad 已全量并图,此后不再自动建图 ⇒ 必须显式拆回并重建。
     *
     * 阶段 0:tile 双层扫描 + isCenter 去重收集全部网关 pos/引用;
     * 阶段 1:同款扫描,把所有建筑 power.links 中等于现存网关 pos 的条目删净(下标循环 removeIndex、
     *         命中不递增 ⇒ 重复全删),并登记受影响对端与旧 graphID;
     * 阶段 2:先对全部网关、再对受影响对端显式 `new PowerGraph().reflow(x); pg.update()`。
     *
     * 约束:必须一次扫全、全部删净后才允许任何 reflow(残留边会把网关拖回重建图);
     * 判据只看 gwPos.contains(entry),不查队伍/对端存活;死目标条目保留(口径外、对图无影响);
     * 序列抄 vanilla 删支(reflow + update),**绝不用 updatePowerGraph/updateProximity 替代**
     * (它们只合不分,会把网关并回原生图)。
     *
     * @return 一行摘要(理论不可能为 null:触发者本身也是网关);无网关时 return null。
     */
    static String cleanupPeerResidues(){
        // ---- 阶段 0:收集全部网关(tile 双层扫描 + isCenter 去重;F5)----
        IntSet gwPos = new IntSet();
        Seq<Building> gateways = new Seq<>();
        for(int x = 0; x < Vars.world.width(); x++){
            for(int y = 0; y < Vars.world.height(); y++){
                Tile t = Vars.world.tile(x, y);
                if(t == null || t.build == null || !t.isCenter()) continue;
                Building b = t.build;
                if(b.block instanceof GatewayNode){
                    gwPos.add(b.pos());
                    gateways.add(b);
                }
            }
        }
        if(gateways.isEmpty()) return null;

        // ---- 阶段 1:清扫对端残留(节点/机器/网关一视同仁;power==null 跳过)----
        IntSet staleGraphs = new IntSet();
        Seq<Building> affected = new Seq<>();
        int removed = 0;
        for(int x = 0; x < Vars.world.width(); x++){
            for(int y = 0; y < Vars.world.height(); y++){
                Tile t = Vars.world.tile(x, y);
                if(t == null || t.build == null || !t.isCenter()) continue;
                Building b = t.build;
                PowerModule power = b.power;
                if(power == null || power.links.size == 0) continue;
                IntSeq links = power.links;
                boolean hit = false;
                for(int i = 0; i < links.size; ){
                    if(gwPos.contains(links.get(i))){
                        links.removeIndex(i);   // 下标删:命中即删、不递增 ⇒ 重复条目全删
                        removed++;
                        hit = true;
                    }else{
                        i++;
                    }
                }
                if(hit){
                    staleGraphs.add(power.graph.getID());
                    affected.add(b);
                }
            }
        }

        // ---- 阶段 2:显式重建(先网关后对端;对端按 staleGraphs(graphID)去重)----
        int reflows = 0;
        for(Building g : gateways){
            PowerGraph pg = new PowerGraph();
            pg.reflow(g);
            pg.update();
            reflows++;
        }
        for(Building b : affected){
            if(b.power == null) continue;
            if(staleGraphs.contains(b.power.graph.getID())){
                PowerGraph pg = new PowerGraph();
                pg.reflow(b);
                pg.update();
                reflows++;
            }
        }
        return "gw=" + gateways.size + " removed=" + removed + " affected=" + affected.size + " reflow=" + reflows;
    }

    public GatewayNode(String name) {
        super(name);

        // ---- S3 载荷通道:接管父类注册的两个 config 处理器 ----
        // PowerNode.<init> 把 Integer / Point2[] 注册成「写 power.links」的处理器
        // (Block.config → configurations.put,后注册覆盖先注册)。这里【两个都必须接管】,
        // 否则蓝图粘贴 / 拖线交互 / 联机事件包任一入口都会写进 power.links,
        // 直接踩「自管链路绝不复用 power.links」的红线。
        // 签名照抄 v8 源:public <T, E extends Building> void config(Class<T> type, Cons2<E, T> config)
        config(Integer.class, (Building entity, Integer value) -> {
            if(entity instanceof GatewayNodeBuild gw){
                // S3.1(b):Integer 通道重定义为幂等 ensure(只增不减)——机器原格拆→重建经此通道,
                // 旧 toggle 的「已含=删」会静默丢掉那条入边(合本 §4.1)。
                gw.ensureModLink(value);
            }
        });

        config(Point2[].class, (Building entity, Point2[] value) -> {
            if(entity instanceof GatewayNodeBuild gw){
                gw.replaceModLinks(value);
            }
        });
    }

    /**
     * 网关的 Building 实现(v8 内嵌 Build 惯例)。
     *
     * ⚠ 这里【故意不声明任何构造器】—— 这是本任务最硬的一个坑,不是疏漏:
     *
     * v8 把 `Block.newBuilding()` 改成了 final,改为在 `Block(String)` 构造器里调
     * `initBuilding()` 反射发现内嵌 Build 类(见 v160.5 源 Block.java):
     *
     *     Class<?> type = Structs.find(current.getDeclaredClasses(),
     *                                   t -> Building.class.isAssignableFrom(t) && !t.isInterface());
     *     Constructor<? extends Building> cons =
     *         type.getDeclaredConstructor(type.getDeclaringClass());
     *     buildType = () -> cons.newInstance(this);
     *
     * `type.getDeclaringClass()` 是 GatewayNode ⇒ 它要的是一个【参数恰好只有 GatewayNode 一个】的构造器,
     * 也就是内层类的【隐式构造器】(内层类会把外层实例当作构造器的第一个参数)。
     *
     * 若照字面手写 `public GatewayNodeBuild(GatewayNode block){ ... }`,javac 会在最前面再补一个
     * 外层实例参数 ⇒ 实际签名为 (GatewayNode, GatewayNode) ⇒ getDeclaredConstructor(GatewayNode.class)
     * 抛 NoSuchMethodException。【失败后果分两种,别记错】(2026-10-05 复核更正,verify:serialization#7):
     *   · 构造器签名不符(本行情形):异常直接跳出 initBuilding 的建设循环 ⇒ buildType 保持 null ⇒
     *     兜底为【裸 mindustry.gen.Building::create】—— 连原版 PowerNodeBuild 的 placed/config 都没有;
     *   · 内嵌类整条继承链都找不到(本类不适用,仅理论上溯):上溯到 PowerNode 层命中原版
     *     PowerNode$PowerNodeBuild(旧空壳 jar 走的就是这条)。
     * 两种情形本类覆写都静默失效(不报错)。所以「内嵌 Build 类不得声明任何构造器」是硬红线。
     *
     * 原版先例(同一形状:子方块的内嵌 Build 继承父方块的内嵌 Build,且不声明构造器):
     *   ItemTurret.ItemTurretBuild extends TurretBuild   (ItemTurret extends Turret)
     * 本类完全照抄该形式。详见 改动说明.md「关键决策」。
     */
    public class GatewayNodeBuild extends PowerNodeBuild implements BsVisualData {

        /** 自管链路:元素 = `Point2.pack` 的【绝对】格坐标。顺序 = 存储顺序(不保证有序)。 */
        public IntSeq modLinks = new IntSeq();

        // ============================================================
        // T2 运行时字段(设计 §1.11 / 合本 §二-2.2;【纯运行时:不持久化、不进 config、不广播】)
        // ============================================================

        /** 出力申报(→ getPowerProduction();引擎每帧读)。 */
        public float linkInjCmd;
        /** 需求申报(→ consPower 动态 lambda;引擎每帧读)。 */
        public float linkDrawCmd;

        // 每链路平行数组。不变量:length == modLinks.size(由 syncLinkRuntime() 维护)
        /** 当前 F(上一拍解算结果;索引 = modLinks 存储顺序)。 */
        public float[] linkFlow = new float[0];
        /** 关断位(默认 true=闭合;G5 开断批才消费)。 */
        public boolean[] linkOpen = new boolean[0];
        /** T4 配额(默认 1;G4 批才接 setGrant)。 */
        public float[] linkGrant = new float[0];
        /** T4 档序(默认 2;G4 批才接 setTier)。 */
        public int[] linkTier = new int[0];
        /** 冻结的每链路状态(0/1/−1;T1/T4 读;本批三值,见合本 §2.2-⑩)。 */
        public byte[] linkState = new byte[0];
        /** 本拍原始状态判定(−1/0/1/2;D21 去抖管道的输入;纯运行时,不持久化不进 config 不广播)。 */
        public byte[] linkStateRaw = new byte[0];
        /** 连续相同原始状态的计数(0..2;D21 连续 2 拍才翻转 linkState;纯运行时)。 */
        public byte[] linkHold = new byte[0];

        /** 注册簿记(BlackstartLinks 用;幂等守卫)。 */
        public boolean registered;

        /**
         * 平行数组的旧 packed 序镜像(syncLinkRuntime 的匹配基准)。
         * 每次 sync 后刷新为当前 modLinks 序;read/清表时同步清空。
         */
        private final IntSeq linkPacked = new IntSeq();

        // ============================================================
        // T2 生命周期覆写 + 申报面(设计 §1.11;合本 §二-2.2-②/⑥)
        // ============================================================

        // 017:只读视觉面;先以 modLinks.size 判界,不改运行数组和序列化布局。
        @Override public float bsLinkFlow(int i){
            return i >= 0 && i < modLinks.size && i < linkFlow.length ? linkFlow[i] : Float.NaN;
        }
        @Override public float bsLinkCapacity(int i){
            return i >= 0 && i < modLinks.size ? linkRating : Float.NaN;
        }
        @Override public float bsLinkLossPct(int i){
            return i >= 0 && i < modLinks.size ? effectiveLinkLoss() * 100f : Float.NaN;
        }
        @Override public int bsLinkState(int i){
            return i >= 0 && i < modLinks.size && i < linkState.length ? linkState[i] : -1;
        }
        @Override public int bsSwitchState(){ return -1; }
        @Override public float bsRecloseProgress(){ return Float.NaN; }
        @Override public int bsRecloseLeft(){ return -1; }
        @Override public int bsDeviceKind(){ return -1; }
        @Override public boolean bsGraphThrottled(){
            return BlackstartLinks.graphThrottled(power == null ? null : power.graph);
        }

        /**
         * N8:Building.created() 在 init(...)末尾调用,【每条建筑创建路径都过】(放置完成 /
         * 读档 setBlock→changeBuild→init / 蓝图静态放置 / 载荷落位)⇒ T2 注册表的唯一真源。
         * 合本 §2.2-⑥(修 F7/B3/C4-):统一 created() 单一真源;placed() 只留置脏。
         */
        @Override
        public void created(){
            super.created();
            BlackstartLinks.register(this);
        }

        /**
         * 出力申报(T2 设计 §1.5.1 / 合本 §2.2-①):引擎求和 getPowerProduced() 时读本值。
         * 母类链(PowerNodeBuild / Building)无此覆写,默认返回 0 —— 本覆写即新增语义。
         */
        @Override
        public float getPowerProduction(){
            return linkInjCmd;
        }

        // ============================================================
        // 存档通道:Building 序列化覆写
        // 链路:[version byte = version()][writeBase + write()];读侧 [revision][readBase + read()]
        // (v160.5 SaveVersion.writeMap / readMap;base 与 revision 是两个独立的版本号)
        // ============================================================

        @Override
        public byte version(){
            return MOD_REV;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            // 字节布局照抄 PowerModule::write 的前半段:short 条数 + 每条一个 int packed 坐标。
            // 不写 config 那套(power.links 的 status/power 段一律不碰)。
            // 条数上界 255(MAX_MOD_LINKS)⇒ short 恒不截断;而引擎对 chunk 尾部多余字节【不做跳过】
            // (SaveFileReader 只按读者现取现读;未知实体那处 Reads.skip 是「chunk 剩余须由读者自消费」
            // 的约定证据)——本段字节只能由同 revision 的读者读回。【发布纪律】(verify:serialization#1):
            // jar 只能升级不能回滚;回滚前先备份新档;联机必须同版。
            write.s(modLinks.size);
            for(int i = 0; i < modLinks.size; i++){
                write.i(modLinks.get(i));
            }
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            // read 内【只落坐标,不解算】—— read 阶段全图建筑未必齐,解算挂 afterReadAll。
            // ⚠ MOD_REV 升级纪律(2026-10-05 复核叮嘱,verify:serialization#6):`revision < MOD_REV`
            //   是【丢弃门】—— MOD_REV 升到 2 时不得沿用这一句,否则 rev=1 档整段 modLinks 被静默丢光。
            //   升级写法:if(revision < 1){ rev0 分支 }else{ 按 rev1 布局读 };rev>=2 再在尾部续读新增段。
            modLinks.clear();
            // T2(D10):read = 第 7 写点 —— 平行数组整体清空(镜像表同时清空);读完后按新表重建默认值。
            clearLinkRuntime();
            BlackstartLinks.markDirty();
            // S3.1(a):每次反序列化(read/readAll/readSync)置位;由 afterReadAll 的【首个网关】消费。
            residueCleanupArmed = true;
            // S3.1(a) 自表归一化(任意 revision;红线例外扩展·需独立复核;合本 §3.3):
            // 网关自身 power.links 非空即清 —— 空表零写入(干净档不产生写)。本段先于 endMapLoad,
            // 自表根本不会被并图;原 rev<1 迁移清理被本段涵盖(rev<1 分支的 clear 原样保留,幂等)。
            if(power != null && power.links.size > 0){
                int n = power.links.size;
                power.links.clear();
                BlackstartMod.emit("[bs] read (" + tileX() + "," + tileY() + ") self-links 残留清理 n=" + n);
            }
            if(revision < MOD_REV){
                // revision 0 = 本覆写存在之前写出的存档:没有 modLinks 字节段。
                // 但同一建筑的 readBase 刚把旧档里【原生 power.links】原样读回(PowerModule.read 回填;
                // 旧空壳 jar 时代引擎自动出边/机器入边写进去的)——不清掉的话网关会带着旧边重新并入
                // 原生电力图,S3 隔离前提(P1)在旧档上失效(verify:serialization 附加发现 1 / redline#1 / config#1)。
                // S3.1(a) 起:自表归一化对【任意 revision】生效(见上方 4A 段),本行属幂等保留
                // (rev<1 档必已被上面清空);mod 对 power.links 的写入例外口径见合本 §2.3。
                if(power != null) power.links.clear();
                return;
            }
            int amount = read.s() & 0xffff;
            for(int i = 0; i < amount; i++){
                int packed = read.i();
                // 超上限条目【照读不落表】——必须消费完流内字节,否则流错位(同 write 侧纪律)。
                if(modLinks.size < managedLinkLimit()){
                    modLinks.add(packed);
                }
            }
            // T2(D10):按读回后的 modLinks 重建平行数组(全默认值;read 路径不保留旧运行态)。
            syncLinkRuntime();
        }

        /**
         * 收尾钩子:entities 区域读完(全图建筑齐)后由 SaveVersion 回调。
         * v160.5 `readWorldEntities` 末尾确实挂了三处 each(Groups.all / Groups.unit / state.allBuildings),
         * 但建筑只进 state.allBuildings 一路(Building.add() 只做 Groups.build.addIndex;本题 update=false
         * 连 Groups.build 都不进;preview 载入整段跳过)⇒【每座网关恰好回调 1 次,preview 0 次】。
         * (2026-10-05 复核更正:此前「可能多行回调、按坐标去重」的说法对建筑不成立,verify:serialization#4。)
         *
         * 这里只做「坐标 → Building」解算与合法性校验(唯一谓词 = modLinkValid:非自身 / World.build 非空 /
         * 同队 / power 非空),然后 emit 一行计数。解算结果【不落盘、不缓存】—— Building 引用属运行时
         * 派生状态(P2:重建不存),本任务也不消费这些引用。
         *
         * S3.1 起本钩子承担两件事(合本 §7;交叠点单次编辑成形):
         *   · (a) 对端残留清理的静态门闩由【首个回调的网关】消费,并复位(仅一次/每次读档);
         *   · (c) 计数:去掉旧 seen 去重,逐条改走 modLinkValid(消费侧统一口径;历史数值不可直接对比)。
         * 注意:本钩子仍【只计数不剪枝】—— 对端物化/换块后的悬挂坐标由 S4 消费方负责校验
         * (verify:serialization#3:modLinks 单侧存储无镜像,保证不了对端永远有效)。
         */
        @Override
        public void afterReadAll(){
            // 基类实现为空(mindustry.gen.Building 实机字节码:afterReadAll() 只有 `0: return`),
            // 仍按 v8 钩子惯例先调父类,防将来基类补行为。
            super.afterReadAll();

            // (a) 对端残留清理:门闩消费必须在最前、且仅首网关一次(合本 §3.1)。
            if(residueCleanupArmed){
                residueCleanupArmed = false;
                String summary = cleanupPeerResidues();
                if(summary != null) BlackstartMod.emit("[bs] residue-cleanup " + summary);
            }

            // (c) 逐条计数(去 seen 去重;唯一谓词 modLinkValid ⇒ 与探针/剪枝口径一致)。
            int ok = 0;
            for(int i = 0; i < modLinks.size; i++){
                if(modLinkValid(modLinks.get(i), team)) ok++;
            }
            BlackstartMod.emit("[bs] afterReadAll (" + tileX() + "," + tileY() + ") 解算 " + ok + "/" + modLinks.size + " 成功");

            // T2(D11 / 合本 §2.2-⑥):读档收尾置脏 —— 下一拍立即重算(不等节流)。
            BlackstartLinks.markDirty();
        }

        // ============================================================
        // 放置扫描覆写(2026-10-05 复核后修复新增;修蓝图 RT 翻转,verify:config#0 / redline#0)
        // ============================================================

        /**
         * 覆写原版 PowerNodeBuild.placed():把两道守卫从 power.links 换成 modLinks。
         *
         * 原版守卫 `net.client()` + `power.links.size > 0` + 逐候选 `!power.links.contains(...)` 全读
         * power.links —— 网关此表恒空,守卫全部失效。于是【配置先落地、placed 后扫描】的建造顺序
         * (蓝图粘贴:ConstructBlock.constructed 先 constructFinish → configured 应用我们的
         * Point2[] 整表替换,之后才 tile.build.placed())会把刚还原的链路逐条 toggle 删除,
         * 再补几个自动候选 —— 蓝图 RT 判负且随建造顺序抖动。
         *
         * 覆写后语义(与原版节点对齐):
         *   · 配置已填表(modLinks 非空)→ 早退,保住 config 还原的链路(等价原版 links.size>0 守卫);
         *   · 空表新建 → 自动扫描候选(getPotentialLinks 内部自带 autolink 开关判断,原样沿用);
         *   · 二次 placed()(derelict 修复等)→ 非空即早退,幂等。
         * 不调 super:PowerNodeBuild 版会用 power.links 守卫重扫;Building 版对本块
         * (consumesPower=outputsPower=false)本就无操作,整链跳过无副作用。
         *
         * S3.1(b) 后:机器入边经 ensure 幂等(旧 toggle「已含=删」已根治),机器原格拆→重建不再丢边;
         * 本早退的作用 = 不追加自动边 / 尊重已存拓扑(保链接缝)。
         */
        @Override
        public void placed(){
            if(Vars.net.client()) return;
            if(modLinks.size > 0) return;

            getPotentialLinks(tile, team, (Building other) -> {
                if(!modLinks.contains(other.pos())){
                    configureAny(other.pos());
                }
            });
        }

        // ============================================================
        // 蓝图 / 联机载荷通道:config 槽
        // ============================================================

        /**
         * config() = 自管链路的【相对】坐标(与父类 PowerNodeBuild.config() 同构)。
         * 蓝图采集 / TileConfig 事件包 / 服务端拒绝后的纠偏回发都读它,所以必须 O(小)且反映真值。
         * 注意:只读 modLinks,绝不读 power.links。
         */
        @Override
        public Point2[] config(){
            Point2[] out = new Point2[modLinks.size];
            for(int i = 0; i < out.length; i++){
                out[i] = Point2.unpack(modLinks.get(i)).sub(tileX(), tileY());
            }
            return out;
        }

        /**
         * Integer 处理器目标:S3.1(b) 起 = 幂等 ensure-present(绝不删除;合本 §4.1)。
         * 调用来源:本类覆写的 placed() 自动出边 / 引擎机器侧 Building.placed 的 λ 入边 /
         * onConfigureBuildTapped() 手动「连」 / addModLink / bs-link add —— 都经 configure 或 configureAny 落到这里。
         * ⚠ 勿改回 toggle:机器原格拆→重建会经此通道,此处必须幂等,否则那条入边被静默反删(合本 §0.1 (b))。
         * 删除意图一律走 Point2[] 整表替换(removeModLink),本方法【无任何删除语句】(I5)。
         */
        public void ensureModLink(int packed){
            if(!validModPos(packed)) return;
            if(!modLinks.contains(packed) && modLinks.size < managedLinkLimit()) modLinks.addUnique(packed);
            // T2(D10):第 7 写点 —— 重建/保留平行数组 + 置脏
            syncLinkRuntime();
            BlackstartLinks.markDirty();
        }

        /**
         * Point2[] 处理器目标:整表替换(相对坐标 → 绝对 pack);非法项与重复项丢弃,超上限截断。
         *
         * ⚠ 关键耦合(S3.1(b),合本 §4.3 / I5-I6;方法体与 S3 版一字未改):
         *   · 本方法是【删除意图的唯一表达】—— ensure 之后 Integer 通道只增不减;
         *     若照 vanilla 结构改成「先经 Integer 删旧、再 re-add」,删旧阶段在 ensure 下全 no-op
         *     ⇒ 整表替换退化为并集,断链失效。
         *   · 不得被换回 vanilla λ1;两个 config handler 必须【成对为 mod 版】。
         *   · 「删除 X」不会误伤悬挂表项 Y(validModPos 保留悬挂;EV19 已实证)。
         */
        public void replaceModLinks(Point2[] points){
            modLinks.clear();
            if(points == null){
                // T2(D10):第 7 写点(null = 全清)—— 平行数组同步清空 + 置脏
                syncLinkRuntime();
                BlackstartLinks.markDirty();
                return;
            }
            for(Point2 p : points){
                if(p == null) continue;
                if(modLinks.size >= managedLinkLimit()) break; // Also enforce the grade's physical port count.
                int ax = p.x + tileX(), ay = p.y + tileY();
                // pack 前显式范围校验:Point2.pack 对越界坐标做 16 位截断,会静默折叠到另一格
                // (verify:probes#4 附加发现:pack(65537,3)==pack(1,3))——蓝图 JSON 越界项直接丢。
                if(ax < 0 || ay < 0 || ax >= Vars.world.width() || ay >= Vars.world.height()) continue;
                int packed = Point2.pack(ax, ay);
                if(!validModPos(packed)) continue;
                modLinks.addUnique(packed);
            }
            // T2(D10):第 7 写点 —— 按 packed 坐标匹配保留存活条目的运行时值 + 置脏
            syncLinkRuntime();
            BlackstartLinks.markDirty();
        }

        // ============================================================
        // 手动交互重做(B3):覆写原版三分支(【用户已定①】自点击 = 复刻原版开关)
        // ============================================================

        /**
         * 覆写原版 PowerNodeBuild.onConfigureBuildTapped(合本 §4.2;签名照抄实机字节码)。
         *
         * 原版三分支(字节码实证,desktop.jar 160.5 PowerNode$PowerNodeBuild):
         *   ① linkValid → configure(Integer.valueOf(other.pos())); return false(不 deselect);
         *   ② other == this:门读 power.links.size(网关恒 0 ⇒ 现状永不清空)→ 空则扫描、非空清空;deselect;return false;
         *   ③ 其余 return true(configurable → 切面板 / 非 configurable → 关面板)。
         *
         * S3.1(b) 改动:
         *   ① 目标已在自管表 → configure(余表)表达「断」;未在 → configure(Integer) 走 ensure 表达「连」;
         *   ② 自点门改读 modLinks.size(【用户已定①】):空 → 逐字复刻原版扫描全连;非空 → 全清;
         *   ③ 原样。
         *
         * 边界:①判定基于本地 modLinks 现值(客户端翻译 = 无来源分流的落点);表满 255 静默 no-op(R6);
         * 点断仍须过 linkValid(异队/出射程用自点清空兜底,原版同款限制);②扫描用 configure(手势走权限门)。
         * 不调 super:原版会写 power.links(红线)。
         */
        @Override
        public boolean onConfigureBuildTapped(Building other){
            if(modManualLinkAccept(this, other)){                 // ① 合法目标:连/断
                if(modLinks.contains(other.pos())){
                    configure(remainingWithout(other.pos()));                // 断:Point2[] 余表替换
                }else{
                    configure(Integer.valueOf(other.pos()));                 // 连:Integer ensure
                }
                return false;                                                // 不 deselect(照抄原版主分支)
            }
            if(other == this){                                               // ② 自点:门改读 modLinks.size【用户已定①】
                if(modLinks.size == 0){
                    Seq<Point2> collected = new Seq<>();
                    getPotentialLinks(tile, team, (Building cand) -> {
                        if(!PowerNode.insulated(this, cand) && collected.size < ((PowerNode)block).maxNodes){
                            collected.add(new Point2(cand.tileX() - tileX(), cand.tileY() - tileY()));
                        }
                    });
                    configure(collected.toArray(Point2.class));              // 空 → 扫描全连
                }else{
                    configure(new Point2[0]);                                // 非空 → 全清
                }
                deselect(); return false;
            }
            return true;                                                     // ③ 其余:configurable→切面板/非 configurable→关面板
        }

        /** T3 §2.4 家族谓词:仅玩家手动交互入口;λ17/λ22 自动通道原判不动(D-T3-1)。 */
        private static boolean modManualLinkAccept(GatewayNodeBuild self, Building other){
            return other != null && other != self && other.team == self.team && other.power != null
                && self.withinManagedRange(other.pos())
                && (other.block instanceof GatewayNode || ((GatewayNode)self.block).linkValid(self, other));
        }

        // ============================================================
        // 自管表内部 API(B10):服务端权威 —— 经 configureAny 走原版广播,单机/服务器同一路径
        // ============================================================

        /** 增一条自管链路(Integer ensure;非法 / 已含 / 超上限则不动)。守卫保留 = 省一个包。 */
        public void addModLink(int packed){
            if(!validModPos(packed) || modLinks.contains(packed)) return;
            configureAny(packed);
        }

        /** 删一条自管链路(不含则不动)。S3.1(b):删除意图改由 Point2[] 余表整表替换表达。 */
        public void removeModLink(int packed){
            if(!modLinks.contains(packed)) return;
            configureAny(remainingWithout(packed));
        }

        /**
         * 生成「去掉 packed 之后」的余表(相对坐标数组)—— 删除意图的唯一表达(合本 §4.3)。
         * 顺序 = 原表顺序,仅剔除 packed;空余表 = 空数组(等价全清)。
         */
        private Point2[] remainingWithout(int packed){
            IntSeq keep = new IntSeq();
            for(int i = 0; i < modLinks.size; i++){
                int v = modLinks.get(i);
                if(v != packed) keep.add(v);
            }
            Point2[] out = new Point2[keep.size];
            for(int i = 0; i < keep.size; i++){
                Point2 p = Point2.unpack(keep.get(i));
                out[i] = new Point2(p.x - tileX(), p.y - tileY());
            }
            return out;
        }

        /** 清空自管表(经 Point2[] 处理器走广播;空表早退)。 */
        public void clearModLinks(){
            if(modLinks.size == 0) return;
            configureAny(new Point2[0]);
        }

        /**
         * 自管链路的最小合法性:在本图内、且不是自己。
         * 这里【故意不套 linkValid】—— linkValid 是原版电力语义(节点型/射程/目标 cp),
         * 自管链路是 mod 自己的拓扑;对端是否已建成、是否同队留给 afterReadAll 与消费方判断。
         * 与消费侧 modLinkValid 域不同:本口径允许悬挂/未建成(保蓝图先后建造)。
         */
        private boolean validModPos(int packed){
            if(packed == pos()) return false;
            Point2 p = Point2.unpack(packed);
            return Vars.world.tile(p.x, p.y) != null && withinManagedRange(packed);
        }

        private boolean withinManagedRange(int packed){
            if(!(managedLinkRange>=0f))return true;
            Point2 p=Point2.unpack(packed);
            double dx=p.x-tileX(),dy=p.y-tileY();
            return dx*dx+dy*dy<=managedLinkRange*managedLinkRange;
        }

        // ============================================================
        // 生命周期剪枝(C 组,c):真实队 changeTeam 时原地压缩;derelict 不剪(保链)【用户已定②】
        // ============================================================

        /**
         * 覆写 Building.changeTeam(Team, boolean)(合本 §5.1)。
         * 1 参版仅虚分派转发 ⇒ 只覆写 2 参版即可全拦(F13);读档不调 changeTeam。
         * 换到【真实队】才剪;残骸化(team == Team.derelict)不剪 = 保链【用户已定②】。
         * 剪枝在 super.changeTeam 之前做:谓词用显式传入的新队 team,与旧 this.team 无关。
         */
        @Override
        public void changeTeam(Team team, boolean flag){
            Team old = this.team;
            if(old != team){
                BlackstartLinks.markDirty();   // 014:换队置脏(对端 modLinkValid 可能失效 ⇒ 岛划分改变)
                if(team != Team.derelict) pruneModLinks(team);
            }
            super.changeTeam(team, flag);
        }

        /**
         * 剪枝:原地压缩(保留 modLinkValid 通过者);只动本表、不广播
         * —— handler 自 (b) 起 = ensure,无删除能力(prune 是本地管理写点,I7)。
         */
        private void pruneModLinks(Team team){
            if(team == null) return;                       // 防御(验证修正)
            int n = modLinks.size; if(n == 0) return;
            int w = 0;
            for(int i = 0; i < n; i++){
                int packed = modLinks.get(i);
                if(modLinkValid(packed, team)) modLinks.set(w++, packed);
            }
            if(w != n){
                modLinks.truncate(w);
                BlackstartMod.emit("[bs] prune (" + tileX() + "," + tileY() + ") " + n + "->" + w + " team=" + team.id);
            }
            // T2(D10):第 7 写点 —— 剪枝后同步平行数组(按 packed 匹配保留存活者)+ 置脏
            syncLinkRuntime();
            BlackstartLinks.markDirty();
        }

        /**
         * 消费侧有效性(唯一语义判定;与写入口径 validModPos 故意不同:后者允许悬挂/未建成,保蓝图先后建造)。
         * 判据:非自身 / World.build 非空(自带越界检查)/ 同队 / power 非空。零分配。
         *
         * T2(2026-10-08):可见性 private → package-private(仅此一字),供 BlackstartLinks 的
         * 搬运链路分类复用现役谓词(合本 §2.2 未改语义;设计 §1.2「搬运链路」判定复用本条)。
         */
        boolean modLinkValid(int packed, Team team){
            if(packed == pos()) return false;
            Building other = Vars.world.build(packed);     // 越界 → null(自带四界检查)
            if(other == null) return false;
            if(other.team != team) return false;
            if(other.power == null) return false;
            if(!withinManagedRange(packed))return false;
            return true;
        }

        /** 消费侧计数(bs-dump `valid=k/n` 用;只读,不剪枝)。 */
        public int countValidModLinks(){
            int ok = 0;
            for(int i = 0; i < modLinks.size; i++) if(modLinkValid(modLinks.get(i), team)) ok++;
            return ok;
        }

        // ============================================================
        // T2 运行时平行数组维护(设计 §1.11;第 7 写点纪律)
        // 不变量:linkFlow/linkOpen/linkGrant/linkTier/linkState 的 length == modLinks.size。
        // 012(D21)追加:linkStateRaw/linkHold 同为平行数组,挂同一 syncLinkRuntime() 维护(初值 raw=0/hold=0)。
        // syncLinkRuntime() 挂在 modLinks 既有 6 个写点之后;按 packed 坐标匹配保留存活条目的
        // 运行时值,新条目取默认(F=0 / open=true / grant=1f / tier=2 / state=0)。纯运行时,不落盘。
        // ============================================================

        /**
         * 按当前 modLinks 重建平行数组。旧序 = {@link #linkPacked} 镜像(上一次 sync 后的 modLinks 序);
         * 匹配经 packed 坐标(与顺序无关),因此 ensure 追加 / prune 原地压缩 / replace 整表替换都正确。
         * 写点触发,非每拍路径,故允许数组分配(O(n²) 匹配,n ≤ 255)。
         */
        private void syncLinkRuntime(){
            int n = modLinks.size;
            float[] nFlow = new float[n];
            boolean[] nOpen = new boolean[n];
            float[] nGrant = new float[n];
            int[] nTier = new int[n];
            byte[] nState = new byte[n];
            byte[] nStateRaw = new byte[n];              // 012(D21):原始判定 / 去抖计数
            byte[] nHold = new byte[n];

            float[] oFlow = linkFlow;
            boolean[] oOpen = linkOpen;
            float[] oGrant = linkGrant;
            int[] oTier = linkTier;
            byte[] oState = linkState;
            byte[] oStateRaw = linkStateRaw;
            byte[] oHold = linkHold;
            int oldLen = linkPacked.size;
            for(int k=0;k<oldLen;k++)if(!modLinks.contains(linkPacked.get(k)))EngineeringFlowLimits.removed(this,linkPacked.get(k));
            // 防御:七数组理论等长;取最小长度,避免将来单数组被外部改长度时越界。
            int oldArr = Math.min(Math.min(oFlow.length, oOpen.length),
                Math.min(oGrant.length, Math.min(oTier.length,
                    Math.min(oState.length, Math.min(oStateRaw.length, oHold.length)))));

            for(int i = 0; i < n; i++){
                int packed = modLinks.get(i);
                int j = -1;
                for(int k = 0; k < oldLen; k++){
                    if(linkPacked.get(k) == packed){
                        j = k;
                        break;
                    }
                }
                if(j >= 0 && j < oldArr){
                    nFlow[i] = oFlow[j];
                    nOpen[i] = oOpen[j];
                    nGrant[i] = oGrant[j];
                    nTier[i] = oTier[j];
                    nState[i] = oState[j];
                    nStateRaw[i] = oStateRaw[j];
                    nHold[i] = oHold[j];
                }else{
                    nFlow[i] = 0f;
                    nOpen[i] = true;
                    nGrant[i] = 1f;
                    nTier[i] = 2;
                    nState[i] = 0;
                    nStateRaw[i] = 0;
                    nHold[i] = 0;
                }
            }

            linkFlow = nFlow;
            linkOpen = nOpen;
            linkGrant = nGrant;
            linkTier = nTier;
            linkState = nState;
            linkStateRaw = nStateRaw;
            linkHold = nHold;

            linkPacked.clear();
            for(int i = 0; i < n; i++) linkPacked.add(modLinks.get(i));
        }

        /** 平行数组整体清空(读档 read 路径用;linkPacked 镜像同步清空)。 */
        private void clearLinkRuntime(){
            linkFlow = new float[0];
            linkOpen = new boolean[0];
            linkGrant = new float[0];
            linkTier = new int[0];
            linkState = new byte[0];
            linkStateRaw = new byte[0];                  // 012(D21):去抖管道两数组同清,保平行不变量
            linkHold = new byte[0];
            linkPacked.clear();
        }

        // ============================================================
        // 清理覆写(B11;S3.1 D3 口径更正)
        // 原版对 power.links 的【拆除链】是双向清理:Tile.preChanged → powerGraphRemoved 反删对端条目;
        // 而 derelict 修复链(beginPlace)只清自身侧;modLinks 没有任何引擎清理渠道 ⇒ 只能自管:
        //   · 运行期删除意图 → Point2[] 整表替换(交互 onConfigureBuildTapped / 探针 bs-link del);
        //   · 生命周期写点  → onRemoved / onDestroyed(此处)+ 真实队换队剪枝(changeTeam)。
        // I7 的 6 个命名写点见类头注释。
        // ============================================================

        @Override
        public void onRemoved(){
            super.onRemoved();
            modLinks.clear();
            // T2(D10/D11):第 7 写点 —— 清空平行数组 + 置脏 + 注销注册表
            syncLinkRuntime();
            BlackstartLinks.markDirty();
            BlackstartLinks.unregister(this);
        }

        @Override
        public void onDestroyed(){
            super.onDestroyed();
            modLinks.clear();
            // T2(D10/D11):第 7 写点 —— 清空平行数组 + 置脏 + 注销注册表
            syncLinkRuntime();
            BlackstartLinks.markDirty();
            BlackstartLinks.unregister(this);
        }
    }
}
