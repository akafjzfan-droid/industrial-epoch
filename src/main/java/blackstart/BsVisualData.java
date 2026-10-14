package blackstart;

/**
 * 机制层 → 视觉层的只读数据面(v0 合并版:T1 §5.2 草案 + T3 §4.3 增补 + 合本 §2.2 受限态)。
 *
 * 归属与纪律(照 T1 §5.1):
 *   · 方向:T1 只读;接口缺席 = 降级(样式「未知态」),不是错误。
 *   · T1 侧只做 instanceof + 读,不反射、不猜测字段。
 *   · 017:网关链路读数与图级限流已实装;两装置提供运行态,T1 消费。
 *   · 数值纪律:未知 = NaN;状态未知 = -1;非该设备 = -1;布尔未知 = false。
 *
 * T1 渲染映射(017 已实装):state 1 → 琥珀脉冲;2 → 灰蓝虚线;3 → 红闪 + 事件;
 * 4 → 端点环比;5 → 琥珀虚线;6 → 同网(视觉同正常,悬浮区分);未知 → 常宽实线、悬浮显示「—」。
 *
 * 归属注:本文件由 T1 提供空骨架,T2 / T3 在各自方块 Build 类上 `implements`(照 T1 §5.1)。
 */
public interface BsVisualData {

    // —— 链路(T2):索引与 modLinks 存储顺序对齐 ——
    /** 当前搬运功率;未知 = NaN。 */
    float bsLinkFlow(int i);
    /** 额定;未知 = NaN。 */
    float bsLinkCapacity(int i);
    /** 损耗百分比(显示值,如 3.0 = 3%);未知 = NaN。 */
    float bsLinkLossPct(int i);
    /** 0 正常 / 1 饱和(软限流)/ 2 分闸 / 3 跳闸 / 4 重合冷却 / 5 受限(配额压 0 / tier 饿着)/ 6 同网;未知 = -1。 */
    int bsLinkState(int i);

    // —— 保护装置(T3;非该方块返回缺省) ——
    /** 0 合 / 2 手动分 / 3 跳闸锁定 / 4 重合冷却(5 已弃用);非保护方块 = -1。 */
    int bsSwitchState();
    /** 0..1 重合进度;仅 4 态(重合冷却)有效,其余 NaN。 */
    float bsRecloseProgress();
    /** 剩余重合次数;分区开关恒 0;未知 = -1。 */
    int bsRecloseLeft();
    /** 0 断路器 / 1 分区开关;非装置 = -1。 */
    int bsDeviceKind();

    // —— 图级(T2) ——
    /** 本图有 ≥1 条入链处于 1(饱和)或 5(受限)。 */
    boolean bsGraphThrottled();
}
