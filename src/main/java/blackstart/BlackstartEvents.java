package blackstart;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.WorldLoadEvent;

/**
 * T5(I4)数据地基 + I5 扩展:0.1 客户端【本地】事件环(唯一 schema 出处,T5 §9.1 / §9.2 / §9.3)。
 *
 * 纪律:
 *   · 本环 = 每端本地事件环,【不落盘】。单机/主机由本地生产者入环;0.5 起联机客户端另经
 *     {@link #pushRemote} 权威回放宿主事件段(BlackstartTelemetry 帧尾),与本地同键事件
 *     在去抖窗口内合并(count 取 max),不再宣称"各端各自生成、内容相同"。
 *   · 读法 = 从新到旧({@link #get(int)} 的 i=0 是最新)。
 *   · 线程归属:引擎主循环单线程读写在生产端(push / clear),UI 端只读;
 *     渲染遍历现有 Entry 对象,零分配。
 *   · 生产者(018 实况):19 项 Kind 全部有生产者。BlackstartView 观察拓扑/供电/建拆沿;
 *     PARAM 来自参数写入,LIMIT 来自限流观察,装置 config/保护状态机产生手动开合/跳闸/重合沿;
 *     SHED 自本批由 SafetyNet 的防御警告与切档动作接入,TIE_FAIL 为本批合闸窗口拒绝沿。
 *   · {@link #seq()}:单调条目序号(语义见方法注;clearOnWorldChange 不重置)。
 *   · schema 于 I4 冻结;I5 只【追加】5 个 Kind(见枚举尾注),原 13 项名字/顺序/语义一字不动。
 *
 * 头注②(headless 反转纪律):本类禁用任何 UI / 绘制 / 渲染 / 贴图 / 应用单例类(与 BlackstartLab 头部同款纪律);
 * {@link #install()} 【无】headless 早退,无头下也必须真实生效(装具 bs-lab-events / bs-lab-evtest 依赖它)。
 */
public final class BlackstartEvents {

    /** 20 类事件(13 项 I4 冻结 + I5 追加 5 项 + 018 追加 TIE_FAIL + 0.5 追加 SYNC_REJECT;键规则见 {@link #bundleKey(Kind)})。 */
    public enum Kind {
        LIMIT,          // 软限流(接近额定)
        TRIP,           // 跳闸(断路器 / 结算)
        RECLOSE,        // 自动重合成功
        RECLOSE_FAIL,   // 重合失败 / 锁定
        SHED,           // 按类别甩负荷(带类别)
        LINK_ADD, LINK_DEL,
        BREAKER_OPEN, BREAKER_CLOSE,   // 手动开合
        ZONE_SET,       // 分区级别变更
        PARAM,          // 全局参数变更
        ISLAND_SPLIT, ISLAND_MERGE,    // 岛拓扑事件(0.1 可选,若结算不产则不出)
        // ---- I5 追加 5 项(设计裁定 D1;追加在末尾,原 13 项名字/顺序/语义一字不动)----
        SAT_WARN,       // 供给不足(采样进入预警沿)
        SAT_CRIT,       // 失电(采样进入危急沿)
        SAT_RECOVER,    // 供电恢复(采样进入恢复沿)
        NODE_BUILT,     // 工程节点建成
        NODE_BROKEN,    // 工程节点被拆/被毁
        TIE_FAIL,       // 018:合闸窗口拒绝(仅追加,既有 18 项顺序不动)
        SYNC_REJECT     // 0.5:aux=SyncPolicy.Reason ordinal; value=local frequency Hz, NaN if absent
    }

    /** 环内条目(字段冻结;value 可 NaN = 无)。 */
    public static final class Entry {
        public long tick;      // Vars.state.tick(双精度对局时长,60tick/s;相对时间显示 =(now-tick)/60 秒)
        public Kind kind;
        public short count = 1;// 去抖聚合计数(§9.3)
        public int x, y;       // 主位置(块 / 岛锚点);无关 = -1
        public int aux;        // 次要值:类别 id / 级别 / 岛 id 等(Kind 解释)
        public float value;    // 数值:负载率 / 损耗 / 功率等(可 NaN = 无)
    }

    /** 环容量(建议位;满 = 覆盖最旧,黑匣子语义)。 */
    public static final int CAPACITY = 256;

    /** 去抖窗口(tick);同一 (kind,x,y) 在窗口内重复 → 只累加 count 并刷新。 */
    public static final long DEBOUNCE_TICKS = 30;

    /** 环体预分配 CAPACITY 个 Entry；0.5 解释旁表在事件边缘另行捕获。 */
    private static final Entry[] ring = new Entry[CAPACITY];

    /** head = 下一写槽;size = 存活条目数。 */
    private static int head, size;

    /** 单调条目序号(I5 追加):push 的「新建条目」路径 +1;去抖合并【不】增;清环【不】重置。 */
    private static long seq;

    /** install() 幂等守卫。 */
    private static boolean installed;

    static{
        for(int i = 0; i < CAPACITY; i++) ring[i] = new Entry();
    }

    private BlackstartEvents(){
    }

    /**
     * 入环一条事件。时钟 = {@code Vars.state.tick}(无世界记 0)。
     *
     * 去抖聚合:扫描【存活条目】(自 {@code head-1} 沿环往回共 size 步;清环后 size=0 ⇒ 不扫描,
     * 残留槽位不参与去抖);存在同 (kind,x,y) 且窗口内(且未时钟回退)的条目 → count 自增
     * (饱和于 Short.MAX_VALUE,防回绕)、tick/aux/value 刷新为新值,直接返回。
     * 否则取 ring[head] 复用对象逐字段赋值(count 重置 1),head 前进取模,size 至 CAPACITY 封顶。
     * 零日志、零异常;value 允许 NaN。
     */
    public static void push(Kind k, int x, int y, int aux, float value){
        long now = Vars.state == null ? 0L : (long)Vars.state.tick;

        for(int i = 0; i < size; i++){
            int idx = (head - 1 - i + 2 * CAPACITY) % CAPACITY;
            Entry e = ring[idx];
            if(e.kind == k && e.x == x && e.y == y && now - e.tick <= DEBOUNCE_TICKS && now >= e.tick){
                if(e.count < Short.MAX_VALUE) e.count++;
                e.tick = now;
                e.aux = aux;
                e.value = value;
                ProtectionExplanation.capture(e);
                return;
            }
        }

        Entry e = ring[head];
        e.tick = now;
        e.kind = k;
        e.count = 1;
        e.x = x;
        e.y = y;
        e.aux = aux;
        e.value = value;
        ProtectionExplanation.capture(e);
        seq++;                                 // I5:仅「新建条目」路径自增(去抖合并路径不增)
        head = (head + 1) % CAPACITY;
        if(size < CAPACITY) size++;
    }

    /**
     * 权威回放一条宿主事件(M4 遥测帧尾事件段;仅联机客户端调用)。
     *
     * 环语义镜像 {@link #push}(同 (kind,x,y) 且在去抖窗口内 → 合并:count 取 max、tick/aux/value 刷新、
     * seq【不】增;否则复用 ring[head] 新建条目,count 用【宿主侧】去抖聚合值、seq++)。两处差异:
     *   · 【不】调 {@link ProtectionExplanation#capture} —— 本地世界态对宿主事件无义;旁表由
     *     {@link ProtectionExplanation#applyRemote}/{@link ProtectionExplanation#removeRemote} 显式注入;
     *   · 传入 tick 先钳制为 min(tick, 本端 now) —— 宿主时钟可超前本端(握手中途/掉帧),钳制防未来时间戳。
     *
     * @return 落点 Entry(新插或合并),供调用方对位注入旁表。
     */
    static Entry pushRemote(Kind k, long tick, int x, int y, int aux, float value, short count){
        long now = Vars.state == null ? 0L : (long)Vars.state.tick;
        if(tick < 0L) tick = 0L;
        if(tick > now) tick = now;

        for(int i = 0; i < size; i++){
            int idx = (head - 1 - i + 2 * CAPACITY) % CAPACITY;
            Entry e = ring[idx];
            if(e.kind == k && e.x == x && e.y == y && now - e.tick <= DEBOUNCE_TICKS && now >= e.tick){
                if(count > e.count) e.count = count;
                if(tick > e.tick) e.tick = tick;   // 只前进:远端回放不把本地条目的刷新时间拨回
                e.aux = aux;
                e.value = value;
                return e;
            }
        }

        Entry e = ring[head];
        e.tick = tick;
        e.kind = k;
        e.count = count;
        e.x = x;
        e.y = y;
        e.aux = aux;
        e.value = value;
        seq++;
        head = (head + 1) % CAPACITY;
        if(size < CAPACITY) size++;
        return e;
    }

    /**
     * 单调条目序号(I5 追加):每次「新建条目」+1,去抖合并不增,{@link #clearOnWorldChange()}
     * 【不】重置 —— 面板据此跨换图检测新条目(换图时把 lastSeq 对齐到当前值,而非归零)。
     */
    public static long seq(){
        return seq;
    }

    /**
     * i = 0 最新,从新到旧。{@code i < 0 || i >= size} → 返回 null(不抛)。
     */
    public static Entry get(int i){
        if(i < 0 || i >= size) return null;
        int idx = (head - 1 - i + 2 * CAPACITY) % CAPACITY;
        return ring[idx];
    }

    /** 存活条目数。 */
    public static int size(){
        return size;
    }

    /** 读档 / 换图清空(防跨局串烧);对象保留待复用。 */
    public static void clearOnWorldChange(){
        size = 0;
        head = 0;
        ProtectionExplanation.clear();
    }

    /**
     * 冻结键规则:`"blackstart.event." + Kind.name().toLowerCase(Locale.ROOT)`。
     * Locale.ROOT 防土耳其 i 坑——键必须全 ASCII(数据冻结要求)。
     */
    public static String bundleKey(Kind k){
        return "blackstart.event." + k.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 挂载清环监听(幂等)。注册 {@link WorldLoadEvent} 与 {@link ResetEvent} → {@link #clearOnWorldChange()};
     * 【无】headless 早退(头部②)。由 {@code BlackstartMod.init()} 调用。
     */
    public static void install(){
        if(installed) return;
        installed = true;
        Events.on(WorldLoadEvent.class, e -> clearOnWorldChange());
        Events.on(ResetEvent.class, e -> clearOnWorldChange());
    }
}
