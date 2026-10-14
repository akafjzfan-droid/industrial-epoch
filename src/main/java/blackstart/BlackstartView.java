package blackstart;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.core.Renderer;
import mindustry.core.World;
import mindustry.game.EventType;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.TileChangeEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Tex;
import mindustry.graphics.Drawf;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.power.PowerNode;

/**
 * T1 可观测层 · P3 读数层批(增量 I3):在 I2「原版电网可视化」之上,补两块【只读读数】——
 *   ⑦ 悬浮读数(drawHover):鼠标命中 网关 / 电力建筑 / mod 链路线段 ⇒ 光标旁 ≤4 行读数块。
 *      s% / L / 分量 #k 读现役 20Hz `Comp` 采样面;b% 走 live(分母 `getTotalBatteryCapacity()` = 修 F4,
 *      分子不 clamp);017 的 T2 流量/额定/损耗与 T3 开断读 BsVisualData,未知值仍显示 `—`。
 *      018 装置并网行经 BlackstartProtection 只读预检通道,不扩接口。
 *   ⑧ 消费侧角标(drawBadges):引擎实时判据(含 `shouldConsumePower` 空闲排除 = 修 F12、`buffered` 排除)
 *      ⇒ 建筑上方实心圆点 + 短文本(缺电/停电);同屏 >64 ⇒ 整屏本帧切「分量计数角标」。
 *   两段均为【只读派生】、挂 postDraw(合本 C-1:需压世界之上者;批内调用序即层序),
 *   不新增采样环 / 不新增事件 / 不新增重建逻辑;电池分母照 F4、空闲机器照 F12 排除。
 *   本批零改动 I1/I2 面:rebuild()/sampleStatus()/drawGridLayer()/drawSegments() 函数体一字不动。
 *
 * ── 历史(增量 I2)────────────────────────────────────────────────────
 * T1 可观测层 · P2 电网层批(增量 I2):在 I1「mod 自管链路描边」之上,把【原版电网】画出来。
 *
 * ── 本批交付(任务-006 v2 A1–A7)────────────────────────────────────
 *   ① 原版链路按【图级负载率】染色(分量基色为底、按 t 向 #E69F00 过渡;缺电红化/红闪)
 *   ② 线宽按图级负载微调 w = 2.0*(0.6+0.8*t)(诚实限制:原版不建模逐线流量)
 *   ③ 按【分量】(PowerGraph 分组)给每条线 / 每个成员建筑上分量色(黄金角散列 + Okabe-Ito 锚避让)
 *   ④ 分量处于 WARN/CRIT/恢复时做孤岛高亮(z=65 色块/包围盒)
 *   另有 I1 遗留且被设计 §2.3-⑤ 点名、本批一并补修的【⑤ 修正项恰 2 处】(见 drawSegments)。
 *
 * ── 性质(不变量,逐条与 I1 相同且加严)──────────────────────────────
 *   · 纯客户端:所有钩子先判 Vars.headless / Vars.ui;无头服务端结构性不注册任何钩子。
 *   · 零写入(2026-10-06 提速批后【一处已获批破例】):power.links / modLinks / 任何引擎状态一律【只读】——
 *     links 只许 .size / .get(i);不调用 PowerGraph 任何非只读方法(reflow/update/add/remove/clear/...);
 *     不覆写任何原版方块方法;不碰 mixin / 字节码;不 new 任何存档/协议对象。
 *     ⚠ 唯一破例(D1/M1;L0 升级):suppress 默认档在视图开着时写 Renderer.laserOpacity = 0,
 *     由当前自绘层替换原版电力连线;preserve 档交原版绘线并关闭重复自绘线层。
 *     关视图/换图/退出与切换 preserve 立即恢复用户 lasersopacity;不写用户原版设置。
 *     suppress 档仍连带压制 BeamNode 与摆放预览线;preserve 档保留它们。
 *   · 零协议 / 零持久化:视图偏好只进本机 Core.settings(不进存档、不进联机、不上云)。
 *   · 线程归属(I5 补注):引擎主循环单线程读写 —— 事件钩子 / 20Hz 采样 / 重建 / 两渲染钩子 /
 *     命令 / HUD 回调同为该线程;不加锁、不建线程、不用并发容器;跨帧缓存只在重建 / 采样点
 *     整体替换或按身份结转(Comp 采样面按 PowerGraph 对象身份结转,见上「引擎对象跨帧纪律」)。
 *   · 帧内零副作用:drawOver / 采样 / 绘制 / postDraw 内不 emit、不写 Core.settings、不读文件;
 *     副作用只允许出现在命令路径 / 事件回调 / rebuild / persist。**唯一例外 = 上条 laserOpacity 破例(已获批)。**
 *
 * ── 引擎事实(本批 javap 亲验;任务-006 §2)──────────────────────────
 *   · PowerGraph【无 links 字段】⇒ 原版链路数据只在节点的 power.links 上(IntSeq)。
 *   · power.links 元素 = Point2.pack 打包的【对端建筑锚点格】坐标;取对端 = Vars.world.build(x,y)。
 *   · 画线复刻规则(PowerNode$PowerNodeBuild.draw() 字节码):
 *       先判 linkValid(self, other);两端都是节点时 other.id >= self.id 跳过(只在 id 大侧画);
 *       端点 = 两端方块世界中心 x/y;laserOpacity==0 原版整体不画。
 *   · Drawf.square(x,y,size,Color) / Drawf.dashRect(Color,x,y,w,h) 均为【带色重载】(javap 实证);
 *     Fill.rect(ffff) 为【中心语义】(内部 Draw.rect(white, x,y,w,h));Draw.reset() 不重置 z。
 *
 * ── 钩子分层(修 C-1;I1 已定,本批沿用)──────────────────────────────
 *   · Trigger.drawOver(帧中,@733):承载【本就属于底层】的要素 —— 本批的 ①②③④。
 *     此段绘制【可能被随后 flush 的建筑盖住,这是预期的底层效果】(垫底),不是 bug。
 *   · Trigger.postDraw(帧末,@782):承载【需压在世界之上】的要素 —— I1 的 ⑤mod 描边 / 方块描边。
 *   两层共享同一份当帧缓存:重建与 20Hz 采样都搬进 drawOver 头部,postDraw 只读缓存绘制。
 *
 * ── 注册表纪律 ────────────────────────────────────────────────────────
 *   网关方块 update=false ⇒ 不在 Groups.build。本层注册表 = tile 双层扫描 + 事件增量,
 *   永不依赖 Groups.build。⚠ 将来若有人把网关 update 改成 true,必须回查本层是否仍成立。
 *
 * ── 引擎对象跨帧纪律 ──────────────────────────────────────────────────
 *   Comp 允许缓存 PowerGraph / Building 引用(采样失效检测兜底),rebuild 时【整体替换、不留旧引用】。
 *   每次采样做【五查】失效检测,任一不成立 ⇒ dirty=TOPO,下一重建修正。
 *   ⚠ 2026-10-06 提速批起,A3 旧口径「TOPO 重建整体重建采样面(不跨重建保留 sat/state)」已翻案:
 *     rebuild() 按 PowerGraph【对象身份】结转采样面与状态机(合并保留端 ⇒ 同图顺延;拆/断链新建 ⇒ 回默认),
 *     消「建造期孤岛高亮瞬灭 / RECOV 2s hold 被打断」(I1 注里「分级保留给 P2」由此兑现)。
 *
 * ── 冻结的调试行格式(中枢抓取;主行前缀一次冻结,只许尾追)────────────
 *   [bs] view on=<bool> only=<bool> gw=<n> seg=<n> drawn=<n> rms=<int> mismatch=<0|1>
 *        comp=<n> warn=<n> crit=<n> recov=<n> empty=<n> island=<bool> ms=<x.xx>
 *   [bs] view colors n=<int> c0=<x>,<y>:<rrggbb> c1=…        (仅 debug 第二行)
 *
 * 生命周期:BlackstartMod.init() → register()(挂事件 + 两绘制钩子 + 惰性挂 HUD)。
 * 无头(headless)零副作用:register() 首行早退(结构性不注册),两层绘制首行亦各留防御早退。
 */
public class BlackstartView {

    // ============================================================
    // 常量总表(全部集中类头,带注释)
    // ============================================================

    // ---- 本机偏好键(Core.settings;不注册全局 defaults,不写文件)----
    /** 总开关;关 ⇒ 全部【绘制】零成本(画布不碰);I5 起观察(4Hz 重建 + 20Hz 采样 + 链路 diff)
     *  仍照跑以产事件(§4-B0 生产者与视图开关解耦;与 I1「关 ⇒ 重建也省」的差异已登记)。 */
    private static final String KEY_ENABLED = "bs-view-enabled";
    /** 视图模式:0=FULL(全量)、1=ONLY(只看模组链路)。 */
    private static final String KEY_MODE = "bs-view-mode";
    /** FULL 模式下是否乘 Renderer.laserOpacity(ONLY 模式强制不跟随)。
     *  ⚠ M1 替换(视图开着压 laserOpacity=0)期间本项实际无差别(恒走 1f 特例);关视图即恢复语义。 */
    private static final String KEY_FOLLOW_LASER = "bs-view-follow-laser";
    /** I2 新增:④孤岛高亮开关(含高亮方块/包围盒);关 ⇒ ④零绘制(①②③照常)。 */
    private static final String KEY_ISLAND = "bs-view-island";
    /** I3 新增:⑦悬浮读数开关;关 ⇒ 悬浮零绘制零计算。 */
    private static final String KEY_HOVER = "bs-view-hover";
    /** I3 新增:⑧消费侧角标开关;关 ⇒ 角标零绘制(遍历也跳过)。 */
    private static final String KEY_BADGES = "bs-view-badges";
    private static final String KEY_BADGE_SCALE = "bs-view-badge-scale";
    /** I5 新增:⑨事件流开关(事件面板显示门的一部分;关 ⇒ 面板隐藏、零 seq 检测)。 */
    private static final String KEY_EVENTS = "bs-view-events";
    /** I5 新增:显示其他队伍电网(默认 false = 只画本地队分量;PvP 防情报泄漏)。 */
    private static final String KEY_OTHERS = "bs-view-others";
    /** I5 新增:低配模式(全元素 LOD 升一档)。 */
    private static final String KEY_LOWFX = "bs-view-lowfx";
    /** I5 新增:配色模式 0=混合(默认)/ 1=纯负载 / 2=纯分色。 */
    private static final String KEY_COLOR_MODE = "bs-view-color-mode";
    /** I5 新增:事件面板位置 0=右下(默认)/ 1=左下 / 2=右上 / 3=左上。 */
    private static final String KEY_EVENT_POS = "bs-view-event-pos";
    /** I5 新增:事件面板展开态 memo(true=展开,默认;false=折成小图标)。 */
    private static final String KEY_PANEL = "bs-view-panel";
    /** I6 新增:设置面板自由位置(拖过才 true;坐标 = 面板左下角,UI 像素,左下原点)。 */
    private static final String KEY_SET_FREE = "bs-view-set-free";
    private static final String KEY_SET_X = "bs-view-set-x";
    private static final String KEY_SET_Y = "bs-view-set-y";
    /** I6 新增:事件面板自由位置(拖过才 true;false = 按 eventPos 四角贴角)。 */
    private static final String KEY_EVENT_FREE = "bs-view-event-free";
    private static final String KEY_EVENT_X = "bs-view-event-x";
    private static final String KEY_EVENT_Y = "bs-view-event-y";
    /** I7 新增:按钮行自由位置(拖过才 true;坐标 = 行左下角,UI 像素,左下原点)。 */
    private static final String KEY_ROW_FREE = "bs-view-row-free";
    private static final String KEY_ROW_X = "bs-view-row-x";
    private static final String KEY_ROW_Y = "bs-view-row-y";
    /** I8 新增:悬浮字号(本机显示偏好;默认 = HOVER_SCALE_BASE 2.75;设置面板滑条调 [1.5, 5.0] 步 0.25)。 */
    private static final String KEY_HOVER_SCALE = "bs-view-hover-scale";
    /** M7 新增:同屏读数开关(灯旁 档位词/频率/rAll/tau 文本;默认 true;关 ⇒ 只停绘制不停采样)。 */
    private static final String KEY_READOUT = "bs-view-readout";
    private static final String KEY_READOUT_SCALE = "bs-view-readout-scale";
    /** M7 新增:建造预览开关(自绘摆放足印 + 区域覆盖提示;默认 true;替换被 laserOpacity=0 连带压灭的原版预览线)。 */
    private static final String KEY_PREVIEW = "bs-view-preview";
    /** L0: suppress retains the current replacement layer; preserve uses the engine's native lines. */
    private static final String KEY_VANILLA_LINES = "bs-view-vanilla-lines";

    // ---- 视图模式 ----
    private static final int MODE_FULL = 0;
    private static final int MODE_ONLY = 1;

    // ---- 脏标记:0 干净 / 1 TOPO / 2 ALL ----
    // I2 起两者重建动作仍相同(本批按 A3 口径整体重建采样面);分级保留给后续增量。
    private static final int DIRTY_NONE = 0;
    private static final int DIRTY_TOPO = 1;
    private static final int DIRTY_ALL = 2;

    // ---- 节流与寿命(重建闸:仅脏且 ≥250ms,至多 4Hz;采样 20Hz)----
    /** 重建节流:仅脏标记置位后、且距上次 ≥250ms 才做一次(2026-10-06 O3 拍板 1000→250;
     *  1600 节点实测单次重建 rms 3–4ms,闸值须远大于单次耗时)。 */
    private static final long REBUILD_INTERVAL_MS = 250L;
    /** 状态采样节流 20Hz(50ms;2026-10-06 提速批 250→50,数值类延迟均值 125→25ms)。
     *  口径换算:三 streak 常量同批 ×5 —— WARN/SAFE/RECOV 的【最大】窗口保持 500/500/750ms 不变,
     *  【最早触发】从 (N-1)·T 变为 (N′-1)·T′ = 450/450/700ms(原 250/250/500ms):判定更细、更晚一拍。 */
    private static final long SAMPLE_INTERVAL_MS = 50L;
    /** 放置强闪寿命(ms);超期即不再参与绘制,重建时顺带剪掉。 */
    private static final long FLASH_LIFE_MS = 1500L;
    /** 强闪表上限(网关 pos → 时间戳);满则挤掉最旧一条。 */
    private static final int FLASH_CAP = 64;
    /** 网关描边呼吸周期(s)。 */
    private static final float BREATH_PERIOD_S = 2f;
    private static final float BREATH_ALPHA_MIN = 0.25f;
    private static final float BREATH_ALPHA_MAX = 0.45f;
    /** 放置强闪的起始 alpha(1.5s 内衰减回呼吸基线)。 */
    private static final float FLASH_ALPHA = 0.85f;

    // ---- 线条与几何 ----
    /** 透明度基准(线层);FULL + 跟随开关开 且 laserOpacity>0 时再乘 Renderer.laserOpacity。 */
    private static final float BASE_ALPHA = 0.55f;
    /** mod 链路内芯缺省线宽;017 有有效流量/额定时按比例计算。 */
    private static final float LINE_WIDTH = 2.0f;
    /** 外层暗色壳的额外线宽(壳宽 = w + 1.6)。 */
    private static final float SHELL_EXTRA_WIDTH = 1.6f;
    /** 悬挂虚线的 dash 段数(Lines.dashLine 的 segments 参数)。 */
    private static final int DASH_SEGMENTS = 6;
    /** 端点小方块锚记半径参数(Drawf.square 的 size;正方形描边用)。 */
    private static final float ENDPOINT_SIZE = 3f;
    /** 网关方块描边半径参数(1 格 = 8px;size=3 时描边约 8.5px,正好套住方块)。 */
    private static final float GATEWAY_OUTLINE_SIZE = 3f;
    /** 视口剔除余量(世界像素):只做粗剔除,不追求像素级。 */
    private static final float CULL_MARGIN = 24f;
    /** 悬挂端小叉的臂长(世界像素)。 */
    private static final float CROSS_ARM = 3f;

    // ---- I2 z 值(显式钉值;任务-006 A6/A7)----
    /** ③ 微染(原版地面单位 60 之上、④/①之下;中枢裁定 65−0.1)。 */
    private static final float Z_WASH = 64.9f;
    /** ④ 孤岛高亮(设计 §2.3-④ 原文 z=65)。 */
    private static final float Z_ISLAND = 65.0f;
    /** ① 原版线染色(引擎 PowerNode.powerLayer=Layer.power=70.0;本层 +0.1 压其上有据)。 */
    private static final float Z_LINE = 70.1f;
    /** ⑤ mod 链路(设计 §2.3-⑤ 字面 z=70.2,与 ① 拉开层次;I1 修正项 b)。 */
    private static final float Z_MOD = 70.2f;

    // ---- I2 状态机常量(阈值 = 设计 §2.3-① 原文 + 中枢裁定)----
    private static final int ST_NORMAL = 0;
    private static final int ST_WARN = 1;
    private static final int ST_CRIT = 2;
    private static final int ST_RECOV = 3;
    /** WARN 进入阈:sat < 0.98。 */
    private static final float SAT_WARN = 0.98f;
    /** CRIT 进入阈:sat < 0.5。 */
    private static final float SAT_CRIT = 0.5f;
    /** WARN→NORMAL 出口:sat ≥ 0.98 且 load ≤ 0.90。 */
    private static final float SAT_SAFE = 0.98f;
    /** CRIT→RECOVERING 阈:sat ≥ 0.995。 */
    private static final float SAT_RECOV = 0.995f;
    /** 重载阈:load > 0.90 ⇒ WARN。 */
    private static final float LOAD_WARN = 0.90f;
    /** 判等容差(中枢裁定;sat≈0 / 电池≈0 之用)。 */
    private static final float EPS = 1e-3f;
    /** NORMAL 下连续 sat<0.98 达 10 次 ⇒ WARN(20Hz 下最大窗仍 500ms;原 2 次@4Hz)。 */
    private static final int WARN_STREAK = 10;
    /** WARN 下连续 safe 达 10 次 ⇒ NORMAL(同换算)。 */
    private static final int SAFE_STREAK = 10;
    /** CRIT 下连续 sat≥0.995 达 15 次 ⇒ RECOVERING(最大窗仍 750ms;原 3 次@4Hz)。 */
    private static final int RECOV_STREAK = 15;
    /** RECOVERING 持续 ≥2000ms 且 sat≥0.98 ⇒ NORMAL。 */
    private static final long RECOV_HOLD_MS = 2000L;

    // ---- I2 绘制上限(常量集中;单位见注释)----
    /** ③ 微染全屏上限:计数单位 = 剔除后绘制的【方块数】(中枢裁定;像素级预算留 I5)。 */
    private static final int WASH_CAP = 1500;
    /** ④ 高亮方块全屏上限(合本 §三-2;包围盒不受此限)。 */
    private static final int HILITE_CAP = 1024;
    /** 成员数超此值的分量只画包围盒(合本 §三-2)。 */
    private static final int BIG_COMP = 512;
    /** ③ 微染基色透明度。 */
    private static final float WASH_ALPHA = 0.08f;
    /** CRIT 红闪周期(s);① 线色脉冲用。 */
    private static final float PULSE_PERIOD_CRIT = 0.7f;

    // ---- D4 ① 线色负载档(离散化 + 档间迟滞;2026-10-06 用户拍板「候选①三档」)----
    /** 档 1 下界(load;= 负载 ramp 1/3 处:0.5 + 0.5/3)。 */
    private static final float LOAD_BAND1 = 0.6667f;
    /** 档 2 下界(load;= 负载 ramp 2/3 处:0.5 + 2×0.5/3)。 */
    private static final float LOAD_BAND2 = 0.8333f;
    /** 档间迟滞死区(load 空间 ±0.02):升档需越上界+H、降档需破下界−H;治「骑线跳色」式交替。 */
    private static final float LOAD_BAND_HYST = 0.02f;

    // ---- I2 分量色算法常量(A4;全部集中此处,便于整体校准)----
    /** 黄金角(度)。 */
    private static final float HUE_GOLDEN = 137.508f;
    /**
     * I6:分量色固定起点色相(度)= 1 号电网基色(蓝)。色序 = 起点 + 黄金角步进
     * (hue(i) = 216 + i×137.508;hsl(216,0.55,0.72) ≈ #90B0DF)。
     * 离线验证(含下述避让 + 兜底,复刻 float 语义):前 12 项两两 ≥20°、离 5 状态锚 ≥9.5°;
     * 前 24 项两两 ≥8.1°、离锚 ≥6.5°;≥25 项属理论极端,按兜底扫描结果如实退化。
     */
    private static final float COMP_HUE_START = 216f;
    /** Okabe-Ito 锚避让阈值(度;中枢裁定 6°;CRIT/WARN 锚仅隔 15°)。 */
    private static final float ANCHOR_GUARD_DEG = 6f;
    /** 撞色消解阈值(度;与已分配分量色的最小间隔)。 */
    private static final float DUP_GUARD_DEG = 8f;
    /** 锚避让最大推步数(用尽即接受当前值,确定性保留)。 */
    private static final int ANCHOR_GUARD_TRIES = 8;
    /** 撞色消解最大推步数(用尽即接受当前值,确定性保留)。 */
    private static final int DUP_GUARD_TRIES = 12;
    /** 分量色饱和度 / 亮度(A4:hsl(h,0.55,0.72))。 */
    private static final float COMP_SAT = 0.55f;
    private static final float COMP_LIGHT = 0.72f;
    /**
     * 状态锚色相 5 个(中枢裁定;实算 163.67/201.63,其余 4.07/26.48/41.48 取一位小数)——
     * #FF5A4E CRIT-闪 / #D55E00 CRIT / #E69F00 WARN / #009E73 恢复 / #56B4E9 正常蓝。
     */
    private static final float[] ANCHOR_HUES = {4.1f, 26.5f, 41.5f, 163.7f, 201.6f};

    // ---- I2 ms= 自计时环(A7)----
    /** 最近 60 帧环槽数。 */
    private static final int MS_RING = 60;

    // ---- 颜色(Okabe-Ito 锚;常量 Color 只读,不改其字段)----
    /** 外层暗色壳 #0B0F14。 */
    private static final Color SHELL_COLOR = new Color(0x0b0f14ff);
    /** 内芯正常色 = 模组青 #57C7FF(与贴图同色系)。 */
    private static final Color CORE_COLOR = Color.valueOf("57c7ff");
    /** 悬挂/离线灰 #8A93A0。 */
    private static final Color DANGLE_COLOR = Color.valueOf("8a93a0");
    /** WARN 橙 #E69F00。 */
    private static final Color WARN_COLOR = new Color(0xe69f00ff);
    /** CRIT 朱红 #D55E00。 */
    private static final Color CRIT_COLOR = new Color(0xd55e00ff);
    /** CRIT 红闪 #FF5A4E。 */
    private static final Color CRIT_FLASH_COLOR = new Color(0xff5a4eff);
    /** 恢复青绿 #009E73。 */
    private static final Color RECOV_COLOR = new Color(0x009e73ff);
    /** I5 纯负载配色线基色 / 事件类别「拓扑·节点」色 = Okabe-Ito 天蓝 #56B4E9(D7/E1)。 */
    private static final Color LOAD_BASE_COLOR = Color.valueOf("56b4e9");

    // ---- I3 读数层:⑦ 悬浮常量(全局口径 = 屏幕像素恒定,世界值 = 屏值 / ds;ds=getDisplayScale();
    // I8 起 gap/edge/lineH/pad 与字号上限均随本机字号 hoverScale ×k 联动,k = hoverScale/HOVER_SCALE_BASE)----
    /** 命中类型(debug `hit=` 与悬浮缓存 identity)。 */
    private static final int HOVER_KIND_NONE = 0;
    private static final int HOVER_KIND_BUILDING = 1;
    private static final int HOVER_KIND_SEG = 2;
    /** 悬浮块最多行数(超出忽略)。 */
    private static final int HOVER_MAX_LINES = 4;
    /** 网关对端最多三条;超出用 hover.more 合并到末行。 */
    private static final int HOVER_PEER_MAX = 3;
    /** 光标右移(屏 px;基准值,I8 起实际用量 = 本值 × k,k = hoverScale/HOVER_SCALE_BASE)。 */
    private static final float HOVER_GAP_PX = 34f;
    /** 距右缘/上缘/下缘翻侧阈(屏 px;基准值,同 ×k)。 */
    private static final float HOVER_EDGE_PX = 8f;
    /** 行距(屏 px);块高 ≈ 行数 × 40k px(屏;基准值,同 ×k —— 字号变而行距不随会致行重叠,故必须联动)。 */
    /** 块内边距(屏 px;基准值,同 ×k)。 */
    private static final float HOVER_PAD_PX = 19f;
    /** 文本基准 scale(与 ds 反比 ⇒ 屏幕恒定):现行公式(I8 起)= clamp(hoverScale/ds, 0.2f, HOVER_SCALE_MAX × k)、k = hoverScale/本值;I7.3 口径(k = 1)= clamp(2.75f/ds, 0.2f, 4.0f)。
     *  I7 放大批:0.85 → 1.3(×1.53,用户「节点的详情面板太小了」)。
     *  I7.2 再放大批:1.3 → 2.2(×1.69;I7 装机后用户仍报「看不清,还是很小」;对标 ≈ 原版建筑信息面板正文),
     *  同批陪跑常量 ×1.69 取整:行距 19→32 / 边距 9→15 / 翻侧阈 260→440 / 光标距 16→27。
     *  I7.3 再放大批:2.2 → 2.75(×1.25;I7.2 装机后用户报「能看清了,不过还是偏小,再大点」——可读性已达标,
     *  本轮为舒适度增量、不再大步跳),同批陪跑常量 ×1.25 取整:行距 32→40 / 边距 15→19 / 翻侧阈 440→550 / 光标距 27→34。
     *  I8 滑条批:本值降为【默认值 + 倍率分母】—— 运行时字号 = 本机偏好 hoverScale(读回缺省即本常量),
     *  设置面板「悬浮字号」滑条调 [1.5, 5.0] 步 0.25;绘制公式 = clamp(hoverScale/ds, 0.2f, HOVER_SCALE_MAX × k),
     *  k = hoverScale/本值。 */
    private static final float HOVER_SCALE_BASE = 2.75f;
    private static final float HOVER_SCALE_MIN = 0.2f;
    /** I7:1.5 → 2.0;I7.2:2.0 → 3.0;I7.3:3.0 → 4.0。悬浮字仅 lodBand=0(ds ≥ 0.75;lodBand()≥1 即 clearHover,块整体
     *  不画)可见 ⇒ I7.3 口径(k = 1)悬浮实际用量 ≤ 2.75/0.75 ≈ 3.67、角标(同门)≤ 1.0,4.0 在可见档内不触发(I8 滑条口径 = hoverScale/0.75 ≤ 6.667,恒 < 4.0k);
     *  抬至 4.0 是 BASE=2.75 的必要配套(若沿用 3.0,ds∈[0.75, 0.917) 被夹 ⇒ 失去屏上尺寸恒定);
     *  I8:悬浮侧上限随 k 联动(实际生效 = 本常量 × k)⇒ 夹制区 ds < 2.75/4.0 = 0.6875 对全部滑条档位恒定
     *  (与可见档起点 0.75 的 9.09% 余量不变);角标侧不随滑条变(用本常量原值,见角标处注明)。
     *  两处共享本对常数(MIN 原值共用;MAX 悬浮侧 ×k、角标侧原值)。
     *  I7.1b 复核补注(M13):下限 0.2 的命中口径 = ds > 2.75/0.2 = 13.75(默认缩放 ds 上界 6.0 ⇒ 永不触发;上界来历见下行 I8 段)。⚠ 待验项(未改,避免
     *  盲改文本度量):M13 读字节码称 Drawf.text 内部除 Scl.scl(1f) ⇒ 高界面缩放下屏上字号 ∝ 1/uiscale
     *  反而变小(疑应补 ×Scl.scl(1f),vanilla 惯用 Scl.scl(4f)/ds)——本公式与 I7/I7.2/I7.3 放大批均按默认
     *  缩放实测过,高缩放档未核;留待游戏内 A/B(拉高界面缩放看悬浮字)再定。
     *  I8:命中口径改 = ds > hoverScale/0.2(滑条全域 7.5 … 25.0);默认口径恒不触发(ds 上界 = maxScale() = Mathf.round(Scl.scl(maxZoomInGame)),全默认 = 6.0),
     *  但 maxZoomInGame = 6.0 × 倍率设置 maxzoomingamemultiplier(100–200%,默认 1.0)、Scl 乘积含 uiscale(25–300%):任一拨 125% ⇒ 上界 ≥ 8 > 7.5,
     *  满放大下 1.5 档起 MIN 夹制可生效(字 = 0.2×ds 随放大增长、背离屏恒定;偏大,方向无害),随 M13 真机 A/B 一并核。 */
    private static final float HOVER_SCALE_MAX = 4.0f;
    /** I8:设置滑条可调范围与步进(该字号;档位 = 1.5 / 1.75 / … / 5.0 共 15 档)。
     *  范围论证:下限 1.5(基准的 0.545×;低于 I7 首档 1.3 无意义);上限 5.0(基准的 1.818×,超 I7.3 一档半)。
     *  读回归一化:NaN ⇒ HOVER_SCALE_BASE;越界 ⇒ clamp 到本区间。 */
    private static final float HOVER_SCALE_USER_MIN = 1.5f;
    private static final float HOVER_SCALE_USER_MAX = 5.0f;
    private static final float HOVER_SCALE_USER_STEP = 0.25f;
    /** 底板黑 α。 */
    private static final float HOVER_BG_ALPHA = 0.55f;
    /** 悬浮/角标 z(T1 §4.2 字面 z=118;postDraw 批次 Draw.sort(false) ⇒ 批内调用序即层序)。 */
    private static final float Z_HOVER = 118f;
    /** 链路命中阈值(屏 px;世界阈值 = 6/ds)。 */
    private static final float HOVER_HIT_PX = 6f;
    /** 悬浮读数重算节流(ms;5Hz;目标变化 ⇒ 立即重算)。 */
    private static final long HOVER_REFRESH_MS = 200L;

    // ---- I3 读数层:⑧ 角标常量(屏幕像素口径)----
    /** 同屏单体角标上限;超出 ⇒ 整屏本帧切「分量计数角标」。 */
    private static final int BADGE_CAP = 64;
    /** 圆点半径(屏 px;直径 7px)。 */
    private static final float BADGE_DOT_R_PX = 3.5f;
    /** 旧文本基准 scale = clamp(0.75f/ds, 0.2f, 4.0f)。角标/同屏读数在此基础上乘独立字号倍率;
     *  建造预览仍用原值,两处字号均不随悬浮字号滑条变化。 */
    private static final float BADGE_TEXT_SCALE_BASE = 0.75f;
    /** 两处本机倍率独立于悬浮字号;旧基础 clamp 之后乘倍率,保证高 zoom 下滑条不被下限吞掉。 */
    private static final float LABEL_SCALE_DEFAULT = 1.25f;
    private static final float LABEL_SCALE_MIN = 0.5f, LABEL_SCALE_MAX = 2.5f, LABEL_SCALE_STEP = 0.05f;
    private static final float LABEL_PAD_PX = 2f, LABEL_BG_ALPHA = 0.45f;
    /** 缺电阈:status < 0.98(屏恒定无关;T1 §2.4)。 */
    private static final float BADGE_STARVE = 0.98f;
    /** 危急阈:status < 0.35(T1 §2.4)。 */
    private static final float BADGE_CRIT = 0.35f;
    /** 角标 LOD 门:ds < 0.45 ⇒ 全关。 */
    private static final float BADGE_LOD_MIN = 0.45f;
    /** 角标文本 LOD 门:ds ≥ 0.75 才画字(0.45–0.75 只留点)。 */
    private static final float BADGE_TEXT_LOD = 0.75f;
    /** 危急脉冲周期(s);相位取模防 sinTable f2i 溢出饱和 ⇒ 长跑脉冲冻结(取模坑同 ① 原版线染色 PULSE_PERIOD_CRIT 段)。 */
    private static final float BADGE_PULSE_PERIOD = 0.7f;
    /** 危急脉冲 α:0.6 + 0.4 × wave。 */
    private static final float BADGE_CRIT_A0 = 0.6f;
    private static final float BADGE_CRIT_A1 = 0.4f;
    /** 圆点相对建筑半高(sz*4)之上再抬的固定世界像素(§A4 字面)。 */
    private static final float BADGE_DOT_LIFT = 6f;
    /** 角标文本相对圆点的横向偏移(屏 px)。 */
    private static final float BADGE_TEXT_GAP_PX = 6f;

    // ---- I5 ⑨ 事件面板常量(§5-C2/C4;全部冻结)----
    /** 面板可见行数上限。 */
    /** 内存显示队列上限(超出丢最旧)。 */
    private static final int EVENT_QUEUE_CAP = 24;
    /** 条目寿命(tick;12s @60tick/s)。 */
    private static final long EVENT_LIFE_TICKS = 720L;
    /** 末段淡出时长(tick;2s)。 */
    private static final long EVENT_FADE_TICKS = 120L;
    /** 显示侧令牌桶:容量与回充速率(条/s;D14 主防线)。 */
    private static final float EVENT_TOKEN_MAX = 4f;
    private static final float EVENT_TOKEN_RATE = 4f;
    /** 面板/折叠按钮尺寸与宽度(屏 px;T1 §4.3 宽 ~300)。 */
    private static final float EVENT_FOLD_W = 64f, EVENT_FOLD_H = 32f;
    /** I6:事件面板四角贴角离屏边距(替代原 fillParent margin 4f;消费处经 Scl.scl,与旧 margin 缩放一致)。 */
    private static final float EVENT_PANEL_MARGIN = 4f;

    // ---- I6 浮动面板拖动(设置 / 事件共用)----
    /** 拖动进入阈值(px;按下后位移和须超过,防点按钮手抖误拖)。仅 I6 两块浮动面板用。
     *  I7.1b 复核补注:维持 6f 曼哈顿是**有意的不对称**——面板受理面经命中链上溯过滤(起点到
     *  hitTarget 间任一环带监听者即拒),受理的都是面板背面/Label 等无点击语义的面,6f 只等价
     *  「窗口更早跟手」,不存在行那套「吞点击+误移+静默落盘」三重误伤。⚠ 若未来给面板受理面加
     *  可点元素,必须同批对齐为 14f/max 或把可点元素移出受理面(否则斜向 ≈4.3px 即吞点击+移窗)。 */
    private static final float PANEL_DRAG_THRESHOLD = 6f;
    /** I7.1 按钮行拖动进入阈值(px;判据 max(|dx|,|dy|) > 阈值)。14 为**本模块自选参数**(与按钮
     *  160×40 及两种误伤面的容错配比)。
     *  I7.1b 订正:原文「与 arc tapSquare=14 互补」不成立——desktop.jar 字节码实证 tapSquareSize
     *  仅被 inTapSquare() 查询,touchDragged/touchUp 全链不因位移取消 clicked();「>14px 吞点击」
     *  完全由 rowSuppressClick 自造,14 无引擎保证。调参时勿据此推出错误不变式。
     *  原行复用 6f+曼哈顿和:斜向 ≈4.3px 即触发,(6,14]px「手抖带」点按被吞 + 行误移 + 误转
     *  自由位落盘(复核 [8] 确证)。 */
    private static final float ROW_DRAG_THRESHOLD = 14f;
    /** 设置面板默认位置(未拖过时):左上角 x、自屏幕顶距(≈按钮行 40 + 上下 margin + 4)。
     *  消费处经 Scl.scl(按钮行 Cell/Table 随 UI 比例缩放)⇒ 任意比例下与旧「表格第二行」同位。 */
    private static final float SET_PANEL_DEFAULT_X = 8f;
    private static final float SET_PANEL_DEFAULT_TOP = 56f;

    // ---- 018:派生状态与防御警告;状态缓存继续供文字使用,不另画实心状态灯 ----
    private static final long LAMP_HOLD_MS = 3000L, LAMP_FADE_MS = 1000L;
    private static final float READOUT_LIFT = 10f, DEFENSE_TICKS = 180f;
    /** M7:灯旁读数(屏幕恒定 px:词-频率横向间距 / 行距;文本基准与角标同源 0.75)。 */
    private static final float LAMP_READOUT_GAP_PX = 6f, LAMP_READOUT_LINE_PX = 12f;
    private static int[] lampPos = {}, lampBand = {};
    private static long[] lampGreenAt = {};
    private static float[] lampAlpha = {};
    /** M7:灯旁读数缓存(与四灯数组同点生长/压缩;采样段量化比较,稳态零重建零分配)。 */
    private static String[] lampFreqText = {}, lampRiskText = {};
    private static int[] lampFreqQ = {}, lampTauQ = {};
    /** D-4x Fix C:rAll 量化值域改 long(原 int 版在 |rAll|≥2.15e6 时按 JLS 饱和到哨兵位,读数冻结)。 */
    private static long[] lampRAllQ = {};
    private static boolean[] lampFreqCompat = {};
    private static boolean[] lampFreqEquivalent = {};
    private static int lampCount;
    private static long defenseSeq = BlackstartEvents.seq();
    /** M4:防御横幅升级 —— 多岛队列(≤4;锚=打包位,同锚 dedup 截止取 max,满槽弃最晚)。 */
    private static final int DEFENSE_SLOTS = 4;
    private static final int[] defenseAnchor = new int[DEFENSE_SLOTS];
    private static final float[] defenseDeadline = new float[DEFENSE_SLOTS];
    private static int defenseCount, defenseShown = -1;
    /** 渲染文本缓存:仅(显示槽,剩余秒)变化时重建正文;稳态零重建零分配。 */
    private static int defenseCacheSlot = -1, defenseCacheSec = -1;
    private static String defenseCacheText = "";
    private static Table defenseRoot;
    private static Label defenseLabel;

    // ---- 命令 ----
    private static final String USAGE = "[bs] 用法: bs-view <on|off|only|debug|rescan|island|hover|badges|events|others|lowfx|readout|preview [on|off]|lines suppress|preserve|color 0|1|2|evpos 0|1|2|3|setpos|rowpos>";

    // ============================================================
    // 运行态(全部本机内存;零持久化)
    // ============================================================

    private static boolean enabled = true;
    private static int mode = MODE_FULL;
    private static boolean followLaser = true;
    private static boolean preserveVanillaLines;
    private static boolean laserOverridden;
    private static float savedLaserOpacity;
    /** I2:④孤岛高亮开关。 */
    private static boolean islandOn = true;
    /** I3:⑦悬浮开关(默认 true;关 ⇒ 零绘制零计算)。 */
    private static boolean hoverOn = true;
    /** I8:悬浮字号(本机显示偏好;绘制侧经 k = hoverScale/HOVER_SCALE_BASE 联动全部悬浮几何;读回缺省 = 2.75)。 */
    private static float hoverScale = HOVER_SCALE_BASE;
    /** I3:⑧角标开关(默认 true;关 ⇒ 遍历也跳过)。 */
    private static boolean badgesOn = true;
    private static float badgeScale = LABEL_SCALE_DEFAULT;
    /** I5:⑨事件流开关(默认 true;关 ⇒ 面板隐藏、零 seq 检测)。 */
    private static boolean eventsOn = true;
    /** I5:显示他人电网(默认 false = 只画本地队分量;D8)。 */
    private static boolean othersOn = false;
    /** I5:低配模式(默认 false;全元素 LOD 升一档;D9)。 */
    private static boolean lowfxOn = false;
    /** M7:同屏读数(默认 true;只停绘制不停采样,缓存常新)。 */
    private static boolean readoutOn = true;
    private static float readoutScale = LABEL_SCALE_DEFAULT;
    /** M7:建造预览(默认 true;视图开着期间接管原版被压灭的摆放预览 + 区域覆盖提示)。 */
    private static boolean previewOn = true;
    /** I5:配色模式 0 混合(默认)/ 1 纯负载 / 2 纯分色(D7)。 */
    private static int colorMode = 0;
    /** I5:事件面板位置 0 右下(默认)/ 1 左下 / 2 右上 / 3 左上。 */
    private static int eventPos = 0;
    /** I5:事件面板折叠态(false = 展开,默认;memo bs-view-panel)。 */
    private static boolean panelFolded = false;
    /** I5:设置面板展开态(session-only,不落盘)。 */
    private static boolean settingsOpen = false;
    /** I6:设置面板自由位置(拖过才 true;false = 每帧贴默认位「按钮行下方左侧」,屏变自动跟随)。 */
    private static boolean setPosFree = false;
    private static float setPosX, setPosY;
    /** I6:事件面板自由位置(拖过才 true;false = 按 eventPos 四角贴角)。 */
    private static boolean eventFree = false;
    private static float eventFreeX, eventFreeY;

    /** 脏标记(0/1/2);初值 ALL,保证第一帧就能建出缓存。 */
    private static int dirty = DIRTY_ALL;
    private static long lastRebuildMs;
    /** 上次采样时刻(ms;20Hz 节流)。 */
    private static long lastSampleMs;
    /** 上次重建耗时(ms;debug 行 rms)。 */
    private static long rebuildRms;

    /** 缓存:线段表(整体替换,不留旧引用)。 */
    private static Seq<Seg> segs = new Seq<>();
    /** 缓存:原版链路线段表(I2;整体替换)。 */
    private static Seq<SegO> segO = new Seq<>();
    /** 缓存:分量表(按 minPos 升序;整体替换)。 */
    private static Comp[] comps = new Comp[0];
    /** 缓存:网关锚点(世界坐标)、packed pos、自算 valid 数、countValidModLinks() 对照数。 */
    private static float[] gwX = {};
    private static float[] gwY = {};
    private static int[] gwPos = {};
    private static int[] gwValid = {};
    private static int[] gwRef = {};
    /** 017:方块观察缓存,只在重建/采样段写,绘图只读。 */
    private static Building[] gwBuild = {};
    private static int[] gwState = {};
    private static float[] gwTripAt = {};
    private static final float TRIP_FLASH_TICKS = 0.7f * 60f;
    private static final float LIMIT_PERIOD_TICKS = 60f;
    private static final String[] LINK_STATE_KEYS = {
        "blackstart.linkState.0", "blackstart.linkState.1", "blackstart.linkState.2",
        "blackstart.linkState.3", "blackstart.linkState.4", "blackstart.linkState.5", "blackstart.linkState.6"
    };
    private static int gwCount;
    private static int segCount;
    /** A3 列出的缓存字段「是否已建」。缓存有效性的权威判据仍是 dirty + lastRebuildMs,
     *  本标志只作状态记录(供后续增量/诊断读取);首帧前恒 false。 */
    private static boolean built;
    /** 上一帧实际绘制的线段数(视口剔除 + I5 他人电网过滤后;debug 行 drawn)。 */
    private static int drawn;
    /** 自算 valid 与 countValidModLinks() 不一致的网关数 ⇒ 写入为 0/1(冻结格式 mismatch=<0|1>)。 */
    private static int mismatch;

    // ---- I5 ⑨ 事件生产者:快照(全部主线程;换图清 null)----
    /** mod 链路快照(规范化无向键,升序去重;null = 首帧未建 ⇒ 只记不产)。 */
    private static long[] linksSnap;
    /** 上次链路扫描时刻(ms;1Hz 节流;换图清 0)。 */
    private static long lastLinkScanMs;
    /** 链路扫描工作数组(1Hz 路径按需增长复用,非渲染路径)。 */
    private static long[] linkKeys = new long[0];
    /** 岛身份快照(每 comp 一条 long;编码见 islandId();null = 首帧未建 ⇒ 只记不产)。 */
    private static long[] islandSnap;

    /** ms= 自计时环(帧内绘制段 = drawOver 的 drawGridLayer + postDraw 的 drawSegments / drawBadges / drawHover;
     *  仅 enabled 帧;不含 rebuild/sample——二者按 ≤4Hz/20Hz 摊销,不计入本环)。
     *  ⚠ I3/A6 扩权:读数层两段已计入(语义 = 本层帧内总耗时);I2 两段计时代码不变。 */
    private static final long[] msRing = new long[MS_RING];
    private static int msRingHead;
    private static int msRingCount;
    private static long msRingSumNs;
    /** 本帧累计纳秒(在 drawOver 累 drawGridLayer 段、在 draw() 累 drawSegments/drawBadges/drawHover 段后推环)。 */
    private static long msFrameNs;

    /** 放置强闪:pos → 时间戳(平行数组,零装箱;上限 FLASH_CAP)。 */
    private static final int[] flashPos = new int[FLASH_CAP];
    private static final long[] flashAt = new long[FLASH_CAP];
    private static int flashCount;

    // ---- I3 读数层运行态(全部本机内存;零持久化)----
    /** 上一帧悬浮命中类型(0 无 / 1 建筑或网关 / 2 链路);debug `hit=`。 */
    private static int hoverHit;
    /** 上一帧绘制的缺电 / 危急角标元素数(单体各计 1、计数角标各计 1);debug `starc=/critc=`。 */
    private static int starc;
    private static int critc;

    /** 悬浮读数缓存:行文本、该行是否走悬挂灰、行数。 */
    private static final String[] hoverLines = new String[HOVER_MAX_LINES];
    private static final boolean[] hoverLineDangle = new boolean[HOVER_MAX_LINES];
    private static int hoverLineCount;
    /** 缓存行长最大参考宽(世界单位 @textScale=1;绘制期乘实际 scale ⇒ 与缩放解耦)。 */
    private static float hoverWRef;
    /** 当前悬浮目标 identity(建筑 = `b.pos()`;线段 = segs 下标)+ 上次重算时刻。 */
    private static int hoverTargetKind = HOVER_KIND_NONE;
    private static int hoverTargetKey = -1;
    private static long hoverCalcMs = -HOVER_REFRESH_MS;
    /** 文本测宽复用 layout(静态复用 ⇒ 帧内零分配;setText 内部自清 runs)。 */
    private static final GlyphLayout hoverLayout = new GlyphLayout();
    /** 角标/同屏读数共用布局,同时量底板;不逐帧创建 layout 或拼接字符串。 */
    private static final GlyphLayout labelLayout = new GlyphLayout();

    /** ⑧ 每 comp 命中计数槽(仅分量数增长(重建)时再分配 ⇒ 帧内零分配)。 */
    private static int[] compStarve = {};
    private static int[] compCrit = {};
    private static int[] compLimit = {};
    /** ⑧ 计数模式角标文本缓存(与 comp 计数槽同点生长;key=0 无效;word 引用核验语言重载)。 */
    private static String[] badgeTextCache = {};
    private static int[] badgeTextKey = {};
    private static String[] badgeTextWord = {};
    /** ⑧ 单体模式 64 槽暂存(平行数组,零装箱)。 */
    private static final float[] badgeX = new float[BADGE_CAP];
    private static final float[] badgeY = new float[BADGE_CAP];
    private static final int[] badgeTier = new int[BADGE_CAP];

    /** HUD 按钮(最小形态;全设置面板属 I5)。 */
    private static TextButton hudButton;
    /** HUD 文案的当前状态键(enabled|mode 打包);不变则连字符串都不重建。 */
    private static int hudStateKey = Integer.MIN_VALUE;

    // ---- I7 按钮行可拖动(free/x/y 三值持久化;其余全 session-only)----
    /** 按钮行容器(hudGroup 顶层子节点;默认 fillParent 钉左上,拖过 / 读回偏好才转自由位)。 */
    private static Table hudRow;
    /** 行是否已切自由态(拖过才 true;坐标 = 行左下角,UI 像素,左下原点)。 */
    private static boolean rowFree;
    private static float rowFreeX, rowFreeY;
    /** 本次按下是否被行监听受理(轮询拖动状态机;arming 见 RowDrag)。 */
    private static boolean rowArmed;
    private static boolean rowDragging;
    /** 本次按下已进化成拖动 ⇒ 吞掉其后的 clicked();下次按下清零(点击抑制,独立于 focus 机制)。 */
    private static boolean rowSuppressClick;
    /** 按下点的 stage 坐标(touchDown 实测)与开拖时的行基线(hudGroup 系左下角)。 */
    private static float rowPressX, rowPressY, rowStartX, rowStartY;

    // ---- I5 ⑨ 事件面板运行态(全部主线程;除折叠 memo 外零持久化)----
    /** 显示队列(新→旧;元素只拷 Entry 值,不持环引用 —— 环对象会复用)。 */
    private static final Seq<Line> eventQueue = new Seq<>();
    /** 上次已消费的 seq(单调不归零;换图只对齐)。 */
    private static long lastSeq;
    /** 令牌桶当前令牌数与上次回充时刻(ms)。 */
    private static float eventTokens = EVENT_TOKEN_MAX;
    private static long lastTokenMs;
    /** 无令牌可入的待报条数(挂账;下帧令牌一到先补一条合成行 ⇒ 信息不丢;换图清 0)。 */
    private static int pendingMerged;
    /** 行序列需重建标志(入队 / 出队 / 换图时置位)。 */
    private static boolean eventRowsDirty = true;
    /** 面板控件(惰性挂载;headless / 无 UI 时恒 null)。 */
    private static Table eventRoot, eventBox;
    private static TextButton eventFoldButton;
    private static Label eventEmptyLabel;
    /** I6:面板内容尺寸脏(行数 / 折叠态变化 ⇒ 位置应用前先 pack,避免每帧全树重排)。 */
    private static boolean eventSizeDirty = true;
    private static float eventAreaWidth=-1,eventAreaHeight=-1,eventUiScale=-1;
    private static float eventWidth,eventBudget,eventPad,eventFoldHeight;
    private static float eventFreeTop=Float.NaN;
    private static boolean lastEventVisible=true;
    private static TextButton measuredFoldButton;
    private static String measuredFoldWord;
    private static int measuredNoticeCount=-1;
    private static float measuredFoldWidth,measuredFoldHeight,measuredFoldFontX,measuredFoldFontY;

    // ---- I5 ④ 设置面板运行态(session-only;文本缓存照 updateHudText 纪律)----
    private static Table settingsBody;
    private static TextButton setViewBtn, setIslandBtn, setBadgesBtn, setEventsBtn,
        setHoverBtn, setOthersBtn, setFollowBtn, setLowfxBtn, setColorBtn, setPosBtn,
        setReadoutBtn, setPreviewBtn, setDebugToastsBtn, setVanillaLinesBtn;
    private static String setVanillaLinesText;
    private static Slider lossSlider;
    private static Label lossValueLabel;
    private static Slider hoverScaleSlider;
    private static Label hoverScaleValueLabel;
    private static int setViewKey = Integer.MIN_VALUE;
    private static int setColorKey = Integer.MIN_VALUE;
    private static int setPosKey = Integer.MIN_VALUE;
    /** I5:7 个 toggle 的按钮与状态镜像(updateSettingsPanel 每帧回写);M7 扩到 9(追加 readout[7]/preview[8]);
     *  M4 扩到 10(追加 debugToasts[9];状态源 = Core.settings,归 BlackstartMod,非 View 字段)。 */
    private static final TextButton[] setToggleBtns = new TextButton[10];
    private static final boolean[] setToggleState = new boolean[10];
    private static float lossLabelShown = -1f;
    /** 滑条上次已提交值(0–4 百分数;防重复提交 / 外部改动回读)。 */
    private static float lastLossSubmitted = Float.NaN;
    /** 程序性 setValue(外部值回读)抑制标志:`moved` 回调会在 setValue 内同步触发,须屏蔽以免无输入写入 / 入环。 */
    private static boolean lossSyncing;
    /** I8:字号滑条上次已提交值(防重复提交);本项无外部写者 ⇒ 无 setValue 回读、无需 lossSyncing 类抑制标志。 */
    private static float lastHoverScaleSubmitted = Float.NaN;

    /** 私有构造:全静态工具类(照 BlackstartLab 风格,不允许实例化)。 */
    private BlackstartView(){
    }

    /**
     * 线段缓存项(I1;mod 自管链路):两端世界坐标 + 悬挂标记 + 归属网关下标。
     * 017:源端/目标端引用随重建替换;读数先核对源端链路身份,防止索引漂移。
     */
    private static class Seg {
        float x1, y1, x2, y2;
        boolean dang;
        /** 归属网关在 gwX/gwY 里的下标(供后续按网关归组/悬浮读数;本批仅占位)。 */
        int gw;
        GatewayNode.GatewayNodeBuild source;
        Building target;
        int linkIndex, peerPos;
        int lastState = -1;
        float tripAt = Float.NEGATIVE_INFINITY;
    }

    /**
     * 原版链路线段缓存项(I2):两端世界坐标 + 归属分量下标。
     * 端点 = 两端方块世界坐标中心 x/y(多格方块由引擎居中)。
     */
    private static class SegO {
        float x1, y1, x2, y2;
        int comp;
    }

    /**
     * 分量(I2;一个 PowerGraph 一份)。
     * 采样面字段 sat/load/need/loadBand/state/... 由 20Hz sampleStatus 写;其余由 rebuild 写。
     * ⚠ 引擎对象(g/rep/members)跨帧缓存,失效由采样五查兜底;rebuild 整体替换
     * (采样面字段不随替换清零:按 g 对象身份结转,见类头「引擎对象跨帧纪律」)。
     */
    private static class Comp {
        PowerGraph g;
        Building rep;
        Building[] members;
        int count, minPos;
        /** 仅 rebuild 期间使用的成员填充游标(填充完毕即 members 满)。 */
        int fill;
        float minX, minY, maxX, maxY;
        Color color;
        // ---- 采样面 ----
        float sat = 1f, load = 0f, need = 0f;
        /** ① 线色负载档 0/1/2(−1=未定;D4 迟滞状态,updateLoadBand 写)。 */
        int loadBand = -1;
        int state = ST_NORMAL;
        long stateSinceMs;
        int warnStreak, safeStreak, recovStreak;
        boolean dead;                                    // 预留:dead ⊆ CRIT 不可单独观测,本批无读取方(收编⑤ 登记)
    }

    /**
     * I5 ⑨ 事件面板显示条目:只拷 {@link BlackstartEvents.Entry} 的【值】,绝不持有 Entry 引用
     * (环对象会复用,持引用会读出污染数据)。时间 / 正文在入队时格式化好(事件率路径允许分配)。
     */
    private static class Line {
        long bornTick;                       // 入队时 Vars.state.tick(对局内时长;换图归零)
        BlackstartEvents.Kind kind;
        short count;
        int x, y, aux;
        float value;
        boolean synth;                       // 合成折叠行(…另有 N 条)
        boolean notice;                      // Player/debug notices bypass overlay/event visibility gates.
        long bornMs;
        String timeText;                     // HH:mm:ss(入队时算好)
        String baseText;                     // 正文(入队时格式化好;×N 仅在 count>1 时追加)
        Table row;                           // 该行 Table(惰性建;重建行序列时复用)
        Image swatch;                        // 类别色块
        Label timeLabel, textLabel;          // 时间 / 正文(α 淡出作用点)
    }

    // ============================================================
    // 注册(唯一入口,由 BlackstartMod.init() 调用一次)
    // ============================================================

    /**
     * 事件挂载 + 两绘制钩子 + HUD 按钮挂载。所有 Events.on/run 都写在本方法内
     * (BlackstartMod 不留散落逻辑)。
     *
     * 无头硬门(红线 5):本层是纯客户端视觉层,Vars.headless 时【一个钩子都不挂】——
     * 比「每个回调各自判空」更强:无头服务端连回调都不会被调用,零副作用是结构性保证。
     * (drawOver()/draw() 首行仍保留 Vars.headless 早退作纵深防御。)
     */
    public static void register(){
        if(Vars.headless) return;

        // 本机偏好:读回上次会话的视图态(设置对象可能尚未就绪时用默认值)。
        if(Core.settings != null){
            enabled = Core.settings.getBool(KEY_ENABLED, true);
            mode = Core.settings.getInt(KEY_MODE, MODE_FULL);
            followLaser = Core.settings.getBool(KEY_FOLLOW_LASER, true);
            preserveVanillaLines = "preserve".equals(Core.settings.getString(KEY_VANILLA_LINES, "suppress"));
            islandOn = Core.settings.getBool(KEY_ISLAND, true);
            hoverOn = Core.settings.getBool(KEY_HOVER, true);
            badgesOn = Core.settings.getBool(KEY_BADGES, true);
            badgeScale = sanitizeLabelScale(Core.settings.getFloat(KEY_BADGE_SCALE, LABEL_SCALE_DEFAULT));
            // I5 新增偏好(6 键之 5;第 6 键 = 折叠 memo,见 tryMountHud)
            eventsOn = Core.settings.getBool(KEY_EVENTS, true);
            othersOn = Core.settings.getBool(KEY_OTHERS, false);
            lowfxOn = Core.settings.getBool(KEY_LOWFX, false);
            colorMode = Core.settings.getInt(KEY_COLOR_MODE, 0);
            eventPos = Core.settings.getInt(KEY_EVENT_POS, 0);
            panelFolded = !Core.settings.getBool(KEY_PANEL, true);
            // I6 新增偏好(6 键):两个浮动面板的自由位置(free=拖过;x/y = 面板左下角,UI 像素,左下原点)
            setPosFree = Core.settings.getBool(KEY_SET_FREE, false);
            setPosX = Core.settings.getFloat(KEY_SET_X, 0f);
            setPosY = Core.settings.getFloat(KEY_SET_Y, 0f);
            eventFree = Core.settings.getBool(KEY_EVENT_FREE, false);
            eventFreeX = Core.settings.getFloat(KEY_EVENT_X, 0f);
            eventFreeY = Core.settings.getFloat(KEY_EVENT_Y, 0f);
            // I7 新增偏好(3 键):按钮行自由位置
            rowFree = Core.settings.getBool(KEY_ROW_FREE, false);
            rowFreeX = Core.settings.getFloat(KEY_ROW_X, 0f);
            rowFreeY = Core.settings.getFloat(KEY_ROW_Y, 0f);
            // I8 新增偏好(1 键):悬浮字号(滑条值域外的手改文件也归一化;NaN 先于 clamp 处理,clamp(NaN) 不设防)
            hoverScale = Core.settings.getFloat(KEY_HOVER_SCALE, HOVER_SCALE_BASE);
            if(Float.isNaN(hoverScale)) hoverScale = HOVER_SCALE_BASE;
            hoverScale = Mathf.clamp(hoverScale, HOVER_SCALE_USER_MIN, HOVER_SCALE_USER_MAX);
            // M7 新增偏好(2 键):同屏读数 / 建造预览(bool,无归一化)
            readoutOn = Core.settings.getBool(KEY_READOUT, true);
            readoutScale = sanitizeLabelScale(Core.settings.getFloat(KEY_READOUT_SCALE, LABEL_SCALE_DEFAULT));
            previewOn = Core.settings.getBool(KEY_PREVIEW, true);
            if(mode != MODE_ONLY) mode = MODE_FULL;   // 非法值归一化
            if(colorMode < 0 || colorMode > 2) colorMode = 0;
            if(eventPos < 0 || eventPos > 3) eventPos = 0;
        }

        defenseSeq = BlackstartEvents.seq(); // 018:初始化只对齐,不回放历史沿

        // WorldLoad / Reset ⇒ 全量脏(读档/换图后缓存必然失效)。
        // I5 追加「换图清面」:链路 / 岛快照清 null;事件面板队列清 + lastSeq【对齐当前 seq】
        // (不是归零 —— seq 单调不重置,归零会把上一张图的旧事件重新判成新事件)。既有 DIRTY_ALL 一字不动。
        Events.on(WorldLoadEvent.class, e -> {
            dirty = DIRTY_ALL;
            onWorldChange();
        });
        Events.on(ResetEvent.class, e -> {
            dirty = DIRTY_ALL;
            onWorldChange();
        });
        Events.on(EventType.StateChangeEvent.class, e -> {
            // GameState.set fires this event before storing its new state.
            if(e.to == mindustry.core.GameState.State.menu) applyVanillaLinePolicy(false);
        });

        // 建成/拆除(ConstructFinish / DeconstructFinish 客户端同路径触发)⇒ TOPO 脏。
        // I2 起过滤从「仅网关」扩为「网关 或 block.hasPower」;强闪仍只记网关。
        Events.on(BlockBuildEndEvent.class, e -> {
            if(e.tile == null) return;
            Block b = e.tile.block();
            if(b == null) return;
            boolean gw = b == BlackstartBlocks.gatewayNode;
            if(!gw && !b.hasPower) return;
            dirty = DIRTY_TOPO;
            if(gw && !e.breaking) markFlash(Point2.pack(e.tile.x, e.tile.y));
            // I5 ⑨ 生产者:仅网关 ⇒ 节点建成 / 被拆(hasPower 类不产;不做队过滤,全报)
            if(gw && !e.breaking && !Vars.headless && Vars.ui != null
                && Vars.state != null && Vars.state.isGame() && Vars.player != null
                && e.team == Vars.player.team() && Core.settings != null
                && !Core.settings.getBool("bs-tip-first-gateway", false)){
                showNotice(Core.bundle.get("blackstart.tip.first_gateway"));
                Core.settings.put("bs-tip-first-gateway", true);
            }
            if(gw){
                BlackstartEvents.push(e.breaking ? BlackstartEvents.Kind.NODE_BROKEN
                    : BlackstartEvents.Kind.NODE_BUILT, e.tile.x, e.tile.y, 0, Float.NaN);
            }
        });

        // 战损拆毁(引擎 Building.killed():先 fire 本事件,此后才 tile.remove() ⇒ 此刻 block 仍是网关)。
        // 这一路【不触发】BlockBuildEndEvent,缺它则被打掉的网关会残影(修 C-7)。I2 起同样含 hasPower。
        Events.on(BlockDestroyEvent.class, e -> {
            if(e.tile == null) return;
            Block b = e.tile.block();
            if(b == null) return;
            if(b == BlackstartBlocks.gatewayNode || b.hasPower) dirty = DIRTY_TOPO;
            // I5 ⑨ 生产者:战损拆毁也报节点损毁(与 BlockBuildEnd(breaking) 同格时由环去抖合并 count)
            if(b == BlackstartBlocks.gatewayNode){
                BlackstartEvents.push(BlackstartEvents.Kind.NODE_BROKEN, e.tile.x, e.tile.y, 0, Float.NaN);
            }
        });

        // 兜底:TileChangeEvent 必须【去过滤】——它在 setBlock(air) 之后派发,
        // 此时 tile.block() 已是 air,任何「按当前块判断」的过滤都会把拆毁滤掉。
        // 代价 = 一次 int store;换来全路径完备。
        Events.on(TileChangeEvent.class, e -> dirty = DIRTY_TOPO);

        // 配置通道(2026-10-06 提速批 ⓑ,结 I5 半笔):玩家手动连线/双击重连
        // (PowerNodeBuild.onConfigureBuildTapped)、mod bs-link add/del(GatewayNodeBuild.addModLink/
        // removeModLink)、网关手动连 —— 只改 links、不动方块,不经上面三个事件;但都经
        // Building.configure/configureAny → Call.tileConfig → InputHandler.tileConfig 末尾
        // fire 本事件(引擎实证:InputHandler.java:732)。同样镜像「网关 或 hasPower」过滤;
        // 非电力配置(分类器等其他方块配置)不置脏。
        Events.on(EventType.ConfigEvent.class, e -> {
            if(e.tile == null) return;
            Block b = e.tile.block;
            if(b == null) return;
            if(b == BlackstartBlocks.gatewayNode || b.hasPower) dirty = DIRTY_TOPO;
        });

        // 两个绘制钩子:drawOver(帧中,垫底要素 ①②③④)/ postDraw(帧末,I1 的 ⑤)。
        Events.run(EventType.Trigger.drawOver, BlackstartView::drawOver);
        Events.run(EventType.Trigger.postDraw, BlackstartView::draw);

        // HUD 按钮:单人/沙盒没有聊天框,命令不可达,这是唯一入口(修 C-15)。
        tryMountHud();
    }

    // ============================================================
    // 每帧管线:drawOver(重建 + 采样 + ①②③④)
    // ============================================================

    /**
     * 帧中入口(Trigger.drawOver,@733)。顺序(I5 起调整):
     *   1) headless 早退  2) world 判空(同清 ms 环,防滞留)
     *   3) 观察:脏 + 节流重建(含 observeIslands)  4) 20Hz 采样 + 1Hz 链路 diff
     *   5) 总开关关 ⇒ 只停绘制(观察照跑、事件仍入环 —— B0 解耦),清 ms 环并早退
     *   6) 总开关开 ⇒ 压原版线透明度 + drawGridLayer(①②③④) + 段耗时累入本帧累计器。
     * 逐帧路径无任何 tile 循环;唯一例外 = 第 3 步重建自含一次 tile 双层扫描(仅脏 + ≥250ms)。
     * ⚠ 与 I1 的差异(登记):关总开关不再省掉重建 / 采样(为满足 I5 B0「生产者与视图开关解耦」),
     *   只省掉绘制;drawOver 首段仍只有两次布尔 / 判空 + 节流比较。
     */
    private static void drawOver(){
        if(Vars.headless) return;                 // 1) 无头零副作用(drawOver 在无头服务端也会 fire)
        if(Vars.world == null || Vars.state == null || !Vars.state.isGame()){
            applyVanillaLinePolicy(false);
            resetMs();                            //    防 debug hit/starc/critc/ms 滞留上一局旧值
            return;
        }

        long now = Time.millis();                 // 3) I5:观察提到总开关之前(B0:关视图也产事件)
        if(dirty != DIRTY_NONE && now - lastRebuildMs >= REBUILD_INTERVAL_MS){
            rebuild();
            lastRebuildMs = now;
            lastSampleMs = 0L;                    // O1:换新 Comp 后本帧立即补采样(去「假中性空窗」一拍)
            dirty = DIRTY_NONE;
        }

        if(now - lastSampleMs >= SAMPLE_INTERVAL_MS){   // 4) 20Hz 采样(只读缓存分量)
            sampleStatus(now);
            lastSampleMs = now;
            observeLinks(now);                          // I5 ⑨:链路增删 diff(函数内 1Hz 自门)
        }

        observeLamps(now);                         // 018:计时/显隐缓存仅在观察段更新
        observeDefense();                          // 独立游标,不经事件面板消费路径

        if(!enabled){                             // 5) 总开关关 ⇒ 只停绘制;ms 环复位(读 0.00)
            applyVanillaLinePolicy(false);
            resetMs();
            return;
        }

        // L0: preserve lets PowerNode/BeamNode and placement previews use the engine renderer.
        // The replacement PowerNode layer is disabled in that mode to avoid drawing each link twice.
        applyVanillaLinePolicy(true);

        long g0 = System.nanoTime();              // 6) 绘制垫底层并计时
        drawGridLayer();
        msFrameNs += System.nanoTime() - g0;
    }

    // ============================================================
    // 每帧管线:postDraw(I1 的 ⑤ mod 描边;帧闭合点)
    // ============================================================

    /**
     * 帧末入口(Trigger.postDraw,@782)。顺序写死:
     *   1) headless 早退  2) HUD 惰性挂载(唯一插在 enabled 早退之前的一句)  3) 总开关早退
     *   4) world 判空  5) drawSegments(⑤)  6) drawBadges(I3 ⑧)  7) drawHover(I3 ⑦);
     *   五~七段各自计时段耗时、累入本帧累计器,末段之后推环 = ms 帧闭合点(I3/A6 扩权:读数层两段计入)。
     *
     * B3 授权的惰性挂载必须排在 enabled 早退之前 —— 若上次会话把视图关着(偏好写进
     * Core.settings),而 register() 时 hudGroup 尚未就绪,排在 enabled 之后会让按钮永不出现。
     */
    private static void draw(){
        if(Vars.headless) return;                 // 1)
        if(hudButton == null) tryMountHud();      // 2)
        updateSettingsPanel();                    // 2.5) I5:设置面板不随总开关早退(按键随时可达)
        updateHudRow();                           // 2.5) I7:按钮行拖动轮询(同上,不随总开关早退)
        if(!enabled){                             // 3)
            drawn = 0;
            updateEventPanel();
            return;
        }
        if(Vars.world == null || Vars.state == null || !Vars.state.isGame()) return; // 4)

        long s0 = System.nanoTime();              // 5)
        drawSegments();
        msFrameNs += System.nanoTime() - s0;

        // I3(A2):读数层两段,顺序写死(⑧角标 → ⑦悬浮;postDraw 批次调用序即层序 ⇒ 悬浮压最上)。
        // M7 追加两段(⑨同屏读数 → ⑩建造预览),插在角标与悬浮之间:读数压角标之上、悬浮仍压最上。
        long b0 = System.nanoTime();
        drawBadges();
        msFrameNs += System.nanoTime() - b0;
        long r0 = System.nanoTime();              // ⑨ M7:灯旁同屏读数
        drawLampReadouts();
        msFrameNs += System.nanoTime() - r0;
        long p0 = System.nanoTime();              // ⑩ M7:建造预览 + 区域提示
        drawPreview();
        msFrameNs += System.nanoTime() - p0;
        long h0 = System.nanoTime();
        drawHover();
        msFrameNs += System.nanoTime() - h0;

        pushFrameNs(msFrameNs);                   // 帧闭合点:推入 60 槽环并清零本帧累计器(位置不变)
        msFrameNs = 0L;

        updateEventPanel();                       // I5 ⑨:事件面板刷新(UI 段,序在 drawHover 之后;不计入 ms 环)
    }

    // ============================================================
    // ms= 自计时(A7)
    // ============================================================

    /** 推入一帧的绘制耗时纳秒(60 槽环 + O(1) 和)。 */
    private static void pushFrameNs(long ns){
        if(ns < 0L) ns = 0L;
        if(msRingCount < MS_RING){
            msRing[msRingHead] = ns;
            msRingHead = (msRingHead + 1) % MS_RING;
            msRingCount++;
            msRingSumNs += ns;
        }else{
            msRingSumNs -= msRing[msRingHead];
            msRing[msRingHead] = ns;
            msRingHead = (msRingHead + 1) % MS_RING;
            msRingSumNs += ns;
        }
    }

    /** 已累积帧的均值(ms);总开关关或尚无帧 ⇒ 0.00。 */
    private static double msMeanMs(){
        return msRingCount == 0 ? 0.0 : (msRingSumNs / (double)msRingCount) / 1_000_000.0;
    }

    /** 清空 ms 环与本帧累计器(总开关关时每帧调用 ⇒ debug 读 0.00)。 */
    private static void resetMs(){
        msRingHead = 0;
        msRingCount = 0;
        msRingSumNs = 0L;
        msFrameNs = 0L;
        // I3(A5):未画帧统计位清零落点 —— 总开关关 ⇒ draw()/drawHover()/drawBadges() 均不被调用,
        // 在此随环一起清,防 debug hit/starc/critc 滞留旧值(两函数首行亦各清一次,双保险)。
        hoverHit = HOVER_KIND_NONE;
        starc = 0;
        critc = 0;
    }

    // ============================================================
    // 重建(唯一 tile 双层扫描;O(建筑数),仅脏且 ≥250ms)
    // ============================================================

    /**
     * 重建派生缓存:一次 tile 双层扫描,产出 ①网关锚点+mod 线段表(I1)②电力成员表+分量表+原版线段表(I2)。
     * 只做这一件事 —— 不在扫描里做绘制;每次整体替换,不留旧引用。
     */
    private static void rebuild(){
        long startNs = System.nanoTime();

        // 单次扫描同时收集:网关(I1)与电力建筑(I2;含网关自身)。
        // 成员锚点格判定 b.tileX()==x && b.tileY()==y(多格方块只在锚点格命中,否则重复 size² 次)。
        Seq<Building> gws = new Seq<>();
        Seq<Building> powerBuilds = new Seq<>();
        for(int x = 0; x < Vars.world.width(); x++){
            for(int y = 0; y < Vars.world.height(); y++){
                Tile tile = Vars.world.tile(x, y);
                if(tile == null) continue;
                Block tb = tile.block();
                if(tb == null) continue;
                Building b = tile.build;

                if(tb instanceof GatewayNode){
                    // A2:build==null / 未接管的 Build(不可达的瞬态)⇒ 记 0 条 —— 不建锚点、不产线段。
                    if(b instanceof GatewayNode.GatewayNodeBuild && b.tileX() == x && b.tileY() == y){
                        gws.add(b);
                    }
                }

                if(tb.hasPower && b != null && b.power != null && b.power.graph != null
                    && b.tileX() == x && b.tileY() == y){
                    powerBuilds.add(b);
                }
            }
        }

        // ---- I2:分量分组(临时 ObjectMap → 按稳定键升序 Comp[];此后一律只走 Comp[])----
        ObjectMap<PowerGraph, Comp> map = new ObjectMap<>();
        for(int i = 0; i < powerBuilds.size; i++){
            Building b = powerBuilds.get(i);
            PowerGraph g = b.power.graph;
            Comp c = map.get(g);
            if(c == null){
                c = new Comp();
                c.g = g;
                map.put(g, c);
            }
            c.count++;
            int p = b.pos();
            if(c.rep == null || Integer.compareUnsigned(p, c.minPos) < 0){
                c.rep = b;
                c.minPos = p;
            }
        }

        Seq<Comp> list = new Seq<>();
        for(Comp c : map.values()) list.add(c);          // 仅此处迭代 ObjectMap(取值入数组),非遍历语义
        list.sort((a, b) -> Integer.compareUnsigned(a.minPos, b.minPos));
        Comp[] arr = list.toArray(Comp.class);

        for(int i = 0; i < arr.length; i++){              // 分配成员槽 + 包围盒初值
            Comp c = arr[i];
            c.members = new Building[c.count];
            c.fill = 0;
            c.minX = Float.MAX_VALUE;
            c.minY = Float.MAX_VALUE;
            c.maxX = -Float.MAX_VALUE;
            c.maxY = -Float.MAX_VALUE;
        }
        for(int i = 0; i < powerBuilds.size; i++){        // 第二趟填充成员 + 成员脚印并集
            Building b = powerBuilds.get(i);
            Comp c = map.get(b.power.graph);
            if(c == null) continue;                       // 理论不可达(第一趟已插入)
            c.members[c.fill++] = b;
            float half = b.block.size * 4f;               // 成员世界中心 ± size*4
            if(b.x - half < c.minX) c.minX = b.x - half;
            if(b.y - half < c.minY) c.minY = b.y - half;
            if(b.x + half > c.maxX) c.maxX = b.x + half;
            if(b.y + half > c.maxY) c.maxY = b.y + half;
        }

        // 2026-10-06 提速批 ⓐ(O4 务实版):采样面按 PowerGraph【对象身份】跨重建结转。
        // 引擎实证:合并时保留一端对象(PowerGraph.merge,被并端消失)、拆/断链时新建对象 ——
        // 故同图对象 ⇒ 观测延续;新图/拆分端 ⇒ 无匹配回默认(语义正确:新拓扑从零采样)。
        // 不结转则每次重建清零 ⇒ 建造期孤岛高亮瞬灭、RECOV 2s hold 被打断。
        // 旧表仅本次重建内使用,不落字段、不保留引用。
        ObjectMap<PowerGraph, Comp> oldByGraph = new ObjectMap<>();
        for(int i = 0; i < comps.length; i++) oldByGraph.put(comps[i].g, comps[i]);
        for(int i = 0; i < arr.length; i++){
            Comp nc = arr[i];
            Comp oc = oldByGraph.get(nc.g);
            if(oc == null) continue;
            nc.sat = oc.sat;
            nc.load = oc.load;
            nc.loadBand = oc.loadBand;                   // D4:档位随采样面同图结转(迟滞记忆连续)
            nc.need = oc.need;
            nc.state = oc.state;
            nc.stateSinceMs = oc.stateSinceMs;
            nc.warnStreak = oc.warnStreak;
            nc.safeStreak = oc.safeStreak;
            nc.recovStreak = oc.recovStreak;
            nc.dead = oc.dead;
        }

        assignColors(arr);                                // A4:分量色(确定性)

        // ---- I2:原版线段表(引擎 PowerNodeBuild.draw() 同款:linkValid 复检 + id 去重)----
        Seq<SegO> outO = new Seq<>();
        for(int ci = 0; ci < arr.length; ci++){
            Comp c = arr[ci];
            for(int mi = 0; mi < c.members.length; mi++){
                Building m = c.members[mi];
                if(!(m.block instanceof PowerNode)) continue;   // 只有节点类建筑持有 power.links
                if(m.team == Team.derelict) continue;           // 引擎同款:derelict 网不产线(linkValid 同队 ⇒ m 侧过滤即覆盖两端;收编④)
                PowerNode pn = (PowerNode)m.block;
                if(m.power == null) continue;
                IntSeq links = m.power.links;                   // 只读:.size / .get(i)
                for(int j = 0; j < links.size; j++){
                    int pos = links.get(j);
                    int ox = Point2.x(pos), oy = Point2.y(pos); // 元素 = 对端锚点格打包坐标
                    Building other = Vars.world.build(ox, oy);  // 越界自带 null
                    if(other == null || other.power == null) continue;
                    if(!pn.linkValid(m, other)) continue;       // 引擎同款复检(public 只读谓词)
                    // 引擎同款去重:两端都是节点时只在 id 大侧画;对端是机器必画。
                    if(other.block instanceof PowerNode && other.id >= m.id) continue;
                    SegO s = new SegO();
                    s.x1 = m.x; s.y1 = m.y;                     // 端点 = 方块世界坐标中心
                    s.x2 = other.x; s.y2 = other.y;
                    s.comp = ci;
                    outO.add(s);
                }
            }
        }
        segO = outO;

        // ---- I1:网关注册表 + mod 线段表(结构与判定逐字节沿用;D-4x 仅修正端点取心 center/cellCenter)----
        int n = gws.size;
        float[] nx = new float[n], ny = new float[n];
        int[] npos = new int[n], nvalid = new int[n], nref = new int[n];
        Seq<Seg> out = new Seq<>();
        Building[] nb = new Building[n];
        int[] ns = new int[n];
        float[] nt = new float[n];
        java.util.Arrays.fill(ns, -1);
        java.util.Arrays.fill(nt, Float.NEGATIVE_INFINITY);
        int mism = 0;

        for(int i = 0; i < n; i++){
            GatewayNode.GatewayNodeBuild gw = (GatewayNode.GatewayNodeBuild)gws.get(i);
            int tileX = gw.tileX(), tileY = gw.tileY();
            nx[i] = center(tileX, gw.block);
            ny[i] = center(tileY, gw.block);
            npos[i] = gw.pos();
            nb[i] = gw;
            // 同实例结转进入沿记忆;拓扑重建不重复 LIMIT、不重启红闪。
            for(int old = 0; old < gwBuild.length; old++){
                if(gwBuild[old] == gw){ ns[i] = gwState[old]; nt[i] = gwTripAt[old]; break; }
            }

            IntSeq ml = gw.modLinks;      // 只读(size / get);红线:绝不写这张表,也绝不写 power.links
            int ok = 0;
            for(int j = 0; j < ml.size; j++){
                int packed = ml.get(j);
                Point2 p = Point2.unpack(packed);
                Building other = (packed == gw.pos()) ? null : Vars.world.build(packed);   // 越界自带 null

                // 悬挂判定:与 GatewayNodeBuild.modLinkValid 同款四查
                // (非自身 / World.build 非空 / 同队 / power 非空);T1 侧自实现,不改 GatewayNode。
                boolean valid = packed != gw.pos() && other != null
                    && other.team == gw.team && other.power != null;
                if(valid) ok++;

                Seg s = new Seg();
                s.x1 = nx[i];
                s.y1 = ny[i];
                // 对端已建成 ⇒ 用真实方块中心(含多格尺寸);对端缺失(悬挂)⇒ 只能按格心近似。
                s.x2 = other != null ? center(other.tileX(), other.block) : cellCenter(p.x);
                s.y2 = other != null ? center(other.tileY(), other.block) : cellCenter(p.y);
                s.dang = !valid;
                s.gw = i;
                s.source = gw;
                s.target = valid ? other : null;
                s.linkIndex = j;
                s.peerPos = packed;
                for(int old = 0; old < segs.size; old++){
                    Seg prev = segs.get(old);
                    if(prev.source == gw && prev.peerPos == packed){
                        s.lastState = prev.lastState; s.tripAt = prev.tripAt; break;
                    }
                }
                out.add(s);
            }

            nvalid[i] = ok;
            nref[i] = gw.countValidModLinks();      // 现役 public 方法:只读对照
            if(ok != nref[i]) mism++;
        }

        // 整体替换(不留旧引用)
        segs = out;
        comps = arr;
        gwX = nx; gwY = ny; gwPos = npos; gwValid = nvalid; gwRef = nref;
        gwBuild = nb; gwState = ns; gwTripAt = nt;
        clearHover();                             // 索引可能变动,下一次悬浮立即刷新
        gwCount = n;
        segCount = out.size;
        mismatch = mism > 0 ? 1 : 0;
        built = true;

        observeIslands();                                 // I5 ⑨:岛身份比对(comps 已更新;首帧只记不产)

        // 强闪表顺带剪枝(跳过已过期条目)
        pruneFlashes();

        rebuildRms = (System.nanoTime() - startNs) / 1_000_000L;
    }

    // ============================================================
    // A4 分量色(确定性、跨读档/reflow 稳定)
    // ============================================================

    /**
     * 为 Comp[] 分配基色。分配顺序 = 数组升序(minPos);已分配集合只含此前分量。
     * I6 起色序【固定】:1 号(下标 0)= COMP_HUE_START 蓝,顺延黄金角步进 —— 与事件「N 号电网」
     * (emitSatEdge 的 ci+1)同序 ⇒ 「号 → 色」全场景可预期;拆/建电网致排序前移时色随编号同步
     * 漂移,这正是「N 号 = 固定色」的本义(旧方案 = FNV-1a(minPos) 哈希起点,色与编号无关,已移除)。
     * 链:固定起点 → 锚避让(6°,≤8 推)→ 撞色消解(8° / 锚 6°,≤12 次)→ 12° 密扫描兜底(≤30 步;
     * 治黄金角轨道自撞,≥22 项才可能出现)→ HSL(0.55,0.72)。
     * 验证数据(含全链复刻):前 12 项两两 ≥20°、离锚 ≥9.5°;前 24 项两两 ≥8.1°、离锚 ≥6.5°。
     */
    private static void assignColors(Comp[] arr){
        float[] used = new float[arr.length];
        int assigned = 0;
        for(int i = 0; i < arr.length; i++){
            Comp c = arr[i];
            float hue = ((COMP_HUE_START + i * HUE_GOLDEN) % 360f + 360f) % 360f;

            // ① 锚避让:与任一锚色相差 < 6° 则 +12° 重试(≤8 次;用尽即接受)
            int guard = 0;
            while(guard < ANCHOR_GUARD_TRIES && nearAnchor(hue)){
                hue = (hue + 12f) % 360f;
                guard++;
            }

            // ② 撞色消解:与锚 < 6° 或与任一已分配 hue < 8° 则继续推;锚命中优先走 +12° 分支
            int tries = 0;
            while(tries < DUP_GUARD_TRIES && (nearAnchor(hue) || nearAssigned(hue, used, assigned))){
                boolean anchorHit = nearAnchor(hue);
                hue = (hue + (anchorHit ? 12f : HUE_GOLDEN)) % 360f;
                tries++;
            }

            // ③ 密扫描兜底:12 次推步仍冲突(黄金角轨道自撞;≥22 项才可能出现)⇒ 12° 扫描(≤30 步,
            //    覆盖全环 ⇒ 项数 < 30 时必有空位);仍冲突 ⇒ 接受(如实,理论极端)。
            int scan = 0;
            while(scan < 30 && (nearAnchor(hue) || nearAssigned(hue, used, assigned))){
                hue = (hue + 12f) % 360f;
                scan++;
            }

            used[assigned++] = hue;
            c.color = hsl(hue, COMP_SAT, COMP_LIGHT);
        }
    }

    /** 与任一状态锚色相相差是否 < ANCHOR_GUARD_DEG。 */
    private static boolean nearAnchor(float hue){
        for(int i = 0; i < ANCHOR_HUES.length; i++){
            if(hueDist(hue, ANCHOR_HUES[i]) < ANCHOR_GUARD_DEG) return true;
        }
        return false;
    }

    /** 与任一已分配分量色相差是否 < DUP_GUARD_DEG。 */
    private static boolean nearAssigned(float hue, float[] used, int n){
        for(int i = 0; i < n; i++){
            if(hueDist(hue, used[i]) < DUP_GUARD_DEG) return true;
        }
        return false;
    }

    /** 色相环最短距离(度)。 */
    private static float hueDist(float a, float b){
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    /** 自实现 HSL→RGB(标准公式;不依赖 arc API)。h ∈ [0,360)。 */
    private static Color hsl(float h, float s, float l){
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float hp = h / 60f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r = 0f, g = 0f, b = 0f;
        if(hp < 1f){ r = c; g = x; }
        else if(hp < 2f){ r = x; g = c; }
        else if(hp < 3f){ g = c; b = x; }
        else if(hp < 4f){ g = x; b = c; }
        else if(hp < 5f){ r = x; b = c; }
        else { r = c; b = x; }
        float m = l - c / 2f;
        return new Color(r + m, g + m, b + m, 1f);
    }

    /** 格坐标 → 世界中心坐标(引擎口径:中心 = tile*8 + block.offset ⇔ Building.x/y = tile.drawx();
     *  1x1 即格心)。D-4x Fix A 同族修复:原 tile*8 + size*4 对 size=1/2 偏 +4、size=3 偏 +12,
     *  与同屏 b.x/b.y 路径(节点连线)自相矛盾(复核 verify:ui abf2 旁注)。 */
    static float center(int tileCoord, Block block){
        return tileCoord * Vars.tilesize + block.offset;
    }

    /** 只有格坐标时(对端缺失)按 1 格近似取格心(格心 = tile*8;Tile.worldx 口径,非 tile*8+4)。 */
    static float cellCenter(int tileCoord){
        return tileCoord * Vars.tilesize;
    }

    // ============================================================
    // A5 采样与状态机(20Hz;只读缓存分量)
    // ============================================================

    /**
     * 20Hz 采样:逐 comp 做失效检测 + 读图级只读面 + 状态机(带迟滞)。
     * 【绝不】在采样里扫 tile / 重建;只读缓存分量。
     */
    private static void sampleStatus(long now){
        for(int ci = 0; ci < comps.length; ci++){
            Comp c = comps[ci];

            // 失效检测(五查):任一不成立 ⇒ dirty=TOPO(下一重建修正),跳过该 comp。
            if(c.rep == null || !c.rep.isValid() || c.rep.power == null
                || c.rep.power.graph != c.g || memberBroken(c)){
                dirty = DIRTY_TOPO;
                continue;
            }

            float sat = Mathf.clamp(num(c.g.getSatisfaction()), 0f, 1f);
            float prod = num(c.g.getPowerProduced());
            float need = num(c.g.getPowerNeeded());
            float stored = Mathf.clamp(num(c.g.getBatteryStored()), 0f, 1f);
            float load = Math.max(need / Math.max(prod, EPS), 0f);   // 下限 0,不封顶

            c.sat = sat;
            c.need = need;
            c.load = load;
            updateLoadBand(c, load);                 // D4:① 线色负载档(迟滞;含首定)

            // 空网门(中枢裁定):need ≤ 0 ⇒ 恒 NORMAL,不评估任何告警态(空网≠故障),绝不终止整个采样。
            if(need <= 0f){
                c.sat = sat;
                c.need = 0f;
                c.load = 0f;
                updateLoadBand(c, 0f);               // 空网 load=0 ⇒ 迟滞入口单点覆盖(必落档 0)
                c.dead = false;
                c.warnStreak = 0;
                c.safeStreak = 0;
                c.recovStreak = 0;
                if(c.state != ST_NORMAL) enterState(c, ST_NORMAL, now, ci);
                continue;
            }

            // 状态机(阈值同设计 §2.3-①;迟滞/出口 = 中枢裁定)
            switch(c.state){
                case ST_NORMAL -> {
                    if(sat < SAT_WARN) c.warnStreak++; else c.warnStreak = 0;
                    if(c.warnStreak >= WARN_STREAK || load > LOAD_WARN) enterState(c, ST_WARN, now, ci);
                }
                case ST_WARN -> {
                    if(sat < SAT_CRIT){
                        enterState(c, ST_CRIT, now, ci);
                    }else{
                        boolean safe = sat >= SAT_SAFE && load <= LOAD_WARN;
                        if(safe) c.safeStreak++; else c.safeStreak = 0;
                        if(c.safeStreak >= SAFE_STREAK) enterState(c, ST_NORMAL, now, ci);
                    }
                }
                case ST_CRIT -> {
                    if(sat >= SAT_RECOV) c.recovStreak++; else c.recovStreak = 0;
                    if(c.recovStreak >= RECOV_STREAK) enterState(c, ST_RECOV, now, ci);
                }
                case ST_RECOV -> {
                    if(sat < SAT_CRIT){
                        enterState(c, ST_CRIT, now, ci);
                    }else if(sat < SAT_WARN){
                        enterState(c, ST_WARN, now, ci);
                    }else if(now - c.stateSinceMs >= RECOV_HOLD_MS){
                        enterState(c, ST_NORMAL, now, ci);
                    }
                }
                default -> enterState(c, ST_NORMAL, now, ci);
            }

            // dead 每采样重算(仅在 CRIT 下参与渲染;sat≈0 且电池≈0)
            c.dead = c.state == ST_CRIT && sat <= EPS && stored <= EPS;
        }
        observeVisualStates();                    // 017:维持 drawOver 既有观察段,不迁移
    }

    /** 017:20Hz 观察进入沿;稳定态零对象分配,事件仅在本观察段入环。 */
    private static void observeVisualStates(){
        for(int i = 0; i < segs.size; i++){
            Seg seg = segs.get(i);
            BsVisualData data = linkData(seg);
            int state = data == null ? -1 : data.bsLinkState(seg.linkIndex);
            if(state != seg.lastState){
                if(state == 3) seg.tripAt = Time.time;
                if(state == 1 || state == 5){
                    BlackstartEvents.push(BlackstartEvents.Kind.LIMIT,
                        seg.source.tileX(), seg.source.tileY(), 0, Float.NaN);
                }
                seg.lastState = state;
            }
        }
        for(int i = 0; i < gwBuild.length; i++){
            Building b = gwBuild[i];
            int state = b instanceof BsVisualData data ? data.bsSwitchState() : -1;
            if(state == 3 && gwState[i] != 3) gwTripAt[i] = Time.time;
            gwState[i] = state;
        }
    }

    /** 链路读数先核对缓存身份,配置重排后的下一次重建前降级未知。 */
    private static BsVisualData linkData(Seg seg){
        Building b = seg.source;
        if(b == null || !b.isValid() || seg.linkIndex < 0 || seg.linkIndex >= seg.source.modLinks.size
            || seg.source.modLinks.get(seg.linkIndex) != seg.peerPos) return null;
        return b instanceof BsVisualData data ? data : null;
    }

    /** mod 正常态和方块描边共用现有分量色;无分量时回退模组青。 */
    private static Color componentColor(Building b){
        if(b != null && b.power != null){
            for(int i = 0; i < comps.length; i++){
                if(comps[i].g == b.power.graph && comps[i].color != null) return comps[i].color;
            }
        }
        return CORE_COLOR;
    }

    /**
     * ① 线色负载档更新(D4 方案①「三档离散 + 档间迟滞」,用户拍板 2026-10-06):
     * 三档 0/1/2、档界 0.6667 / 0.8333(负载 ramp 均分三份),施密特迟滞 ——
     * 升档需 load > 上界+H、降档需 load < 下界−H:骑线 ±2% 抖动不再换色(灭「交替」);
     * 一旦越界即精确定档(可跨多档一拍直达)——保「响应快」体感。
     * band < 0 = 首次(新建分量):裸界直定,无迟滞。
     */
    private static void updateLoadBand(Comp c, float load){
        int b = c.loadBand;
        if(b < 0){
            c.loadBand = bandOf(load);
        }else if(b < 2 && load > bandEdge(b) + LOAD_BAND_HYST){
            c.loadBand = bandOf(load);
        }else if(b > 0 && load < bandEdge(b - 1) - LOAD_BAND_HYST){
            c.loadBand = bandOf(load);
        }
    }

    /** 裸界定档:load ≥ 0.8333 → 2;≥ 0.6667 → 1;否则 0。 */
    private static int bandOf(float load){
        return load >= LOAD_BAND2 ? 2 : (load >= LOAD_BAND1 ? 1 : 0);
    }

    /** 第 i 条档界(i=0: 0.6667;i=1: 0.8333)。 */
    private static float bandEdge(int i){
        return i == 0 ? LOAD_BAND1 : LOAD_BAND2;
    }

    /** NaN→0(其余原样;inf 不特殊处理,下界 clamp 在调用处)。 */
    private static float num(float v){
        return Float.isNaN(v) ? 0f : v;
    }

    /** 成员 tile 失效检测(A5 第五查):任一成员 m.tile==null 或 m.tile.build != m。 */
    private static boolean memberBroken(Comp c){
        Building[] ms = c.members;
        for(int i = 0; i < ms.length; i++){
            Building m = ms[i];
            if(m.tile == null || m.tile.build != m) return true;
        }
        return false;
    }

    /**
     * 状态迁移:记 state 与 stateSinceMs;按 A5「除该状态继续使用的计数器外全部清零」——
     * NORMAL 保留 warnStreak / WARN 保留 safeStreak / CRIT 保留 recovStreak / RECOVERING 全清。
     * I5:在 {@code c.state = s;} 处以 3 行取 old / 赋值 / 比沿(供给事件),不改 s 的计算;
     * `ci` 仅为事件 aux(comp 的 1-based 序号),与状态机数值无关。
     */
    private static void enterState(Comp c, int s, long now, int ci){
        int prevState = c.state;                                 // I5 ⑨:取 old
        c.state = s;
        c.stateSinceMs = now;
        if(prevState != s) emitSatEdge(prevState, s, ci, c);     // I5 ⑨:赋值后比沿
        switch(s){
            case ST_NORMAL -> {
                c.safeStreak = 0;
                c.recovStreak = 0;
            }
            case ST_WARN -> {
                c.warnStreak = 0;
                c.recovStreak = 0;
            }
            case ST_CRIT -> {
                c.warnStreak = 0;
                c.safeStreak = 0;
            }
            default -> {
                c.warnStreak = 0;
                c.safeStreak = 0;
                c.recovStreak = 0;
            }
        }
    }

    // ============================================================
    // I5 ⑨ 事件生产者(本地观察者;全部主线程;只经 BlackstartEvents.push 入环)
    // ============================================================

    /** 换图清面:链路 / 岛快照清 null;事件面板队列清 + lastSeq【对齐】当前 seq。 */
    private static void onWorldChange(){
        applyVanillaLinePolicy(false);
        clearHover();
        linksSnap = null;
        islandSnap = null;
        lastLinkScanMs = 0L;
        eventQueue.clear();
        eventRowsDirty = true;
        pendingMerged = 0;
        lastSeq = BlackstartEvents.seq();
        lampCount = 0;
        defenseSeq = BlackstartEvents.seq();
        defenseCount = 0;
        defenseShown = -1;
        defenseCacheSlot = -1;
        defenseCacheSec = -1;
        if(defenseRoot != null) defenseRoot.visible = false;
        if(eventRoot != null) eventRoot.visible = false;   // 下一帧由 updateEventPanel 重判显示门
    }

    /** 018:按 rep 身份结转,消失立即剔除;数组仅在观察段容量不足时生长。 */
    private static void observeLamps(long now){
        int n = SafetyNet.islandCount();
        for(int i = lampCount - 1; i >= 0; i--){
            boolean live = false;
            for(int k = 0; k < n; k++) if(SafetyNet.islandRepPos(k) == lampPos[i]){ live = true; break; }
            if(live) continue;
            int tail = lampCount - i - 1;
            System.arraycopy(lampPos, i + 1, lampPos, i, tail);
            System.arraycopy(lampBand, i + 1, lampBand, i, tail);
            System.arraycopy(lampGreenAt, i + 1, lampGreenAt, i, tail);
            System.arraycopy(lampAlpha, i + 1, lampAlpha, i, tail);
            // M7:读数缓存随槽压缩(串与量化值同槽搬移;不重排、不重建)
            System.arraycopy(lampFreqText, i + 1, lampFreqText, i, tail);
            System.arraycopy(lampRiskText, i + 1, lampRiskText, i, tail);
            System.arraycopy(lampFreqQ, i + 1, lampFreqQ, i, tail);
            System.arraycopy(lampRAllQ, i + 1, lampRAllQ, i, tail);
            System.arraycopy(lampTauQ, i + 1, lampTauQ, i, tail);
            System.arraycopy(lampFreqCompat, i + 1, lampFreqCompat, i, tail);
            System.arraycopy(lampFreqEquivalent, i + 1, lampFreqEquivalent, i, tail);
            lampCount--;
        }
        if(lampPos.length < n){
            int cap = Math.max(n, Math.max(8, lampPos.length * 2));
            lampPos = java.util.Arrays.copyOf(lampPos, cap);
            lampBand = java.util.Arrays.copyOf(lampBand, cap);
            lampGreenAt = java.util.Arrays.copyOf(lampGreenAt, cap);
            lampAlpha = java.util.Arrays.copyOf(lampAlpha, cap);
            lampFreqText = java.util.Arrays.copyOf(lampFreqText, cap);
            lampRiskText = java.util.Arrays.copyOf(lampRiskText, cap);
            lampFreqQ = java.util.Arrays.copyOf(lampFreqQ, cap);
            lampRAllQ = java.util.Arrays.copyOf(lampRAllQ, cap);
            lampTauQ = java.util.Arrays.copyOf(lampTauQ, cap);
            lampFreqCompat = java.util.Arrays.copyOf(lampFreqCompat, cap);
            lampFreqEquivalent = java.util.Arrays.copyOf(lampFreqEquivalent, cap);
        }
        for(int k = 0; k < n; k++){
            int rep = SafetyNet.islandRepPos(k), band = SafetyNet.islandBand(k), index = -1;
            for(int i = 0; i < lampCount; i++) if(lampPos[i] == rep){ index = i; break; }
            if(index < 0){
                index = lampCount++;
                lampPos[index] = rep;
                lampBand[index] = band;
                lampGreenAt[index] = -1L; // 新绿岛不回放消隐
                // M7:新槽哨兵 ⇒ 首帧强制重建读数串(0 是合法量化值,不能当"未建"用)
                // D-4x Fix C:rAllQ 哨兵用 Long.MIN_VALUE(原 int 版与饱和值同位 ⇒ 读数冻结;long 饱和界不可达)
                lampFreqQ[index] = Integer.MIN_VALUE;
                lampRAllQ[index] = Long.MIN_VALUE;
                lampTauQ[index] = Integer.MIN_VALUE;
                lampFreqCompat[index] = false;
                lampFreqEquivalent[index] = false;
                lampFreqText[index] = null;
                lampRiskText[index] = null;
            }else if(band == 0 && lampBand[index] != 0){
                lampGreenAt[index] = now;
            }
            lampBand[index] = band;
            lampAlpha[index] = band != 0 ? 1f : lampGreenAt[index] < 0L ? 0f
                : Mathf.clamp(1f - (now - lampGreenAt[index] - LAMP_HOLD_MS) / (float)LAMP_FADE_MS);
            // M7:读数采样(量化比较,仅变化时重建串 ⇒ 稳态零分配)。开关只停绘制不停采样。
            sampleLampReadout(index, k, rep);
        }
    }

    // ============================================================
    // M7 ⑨⑩:同屏读数 + 建造预览(观察在 drawOver 段,绘制在 postDraw 段;全只读派生)
    // ============================================================

    /** M7:快照频率可用性判决(纯函数,测试接缝)。不可用 ⇒ NaN —— 无参考 / 非 LIVE / 非法值一律不可用,
     *  绝不把"不可用"折叠成 0 Hz(D-39:频率读数走遥测权威,客户端 snapshotAt 自动走 telemetry)。 */
    static double usableFrequency(BlackstartDynamics.IslandDynamicsSnapshot snap){
        if(snap == null || snap.kind() == BlackstartDynamics.ReferenceKind.NONE
            || snap.reference() != SyncPolicy.ReferenceState.LIVE
            || !Double.isFinite(snap.frequencyHz()) || snap.frequencyHz() <= 0d) return Double.NaN;
        return snap.frequencyHz();
    }

    /** M7:频率读数文本(纯函数)。NaN ⇒ 明示"频率不可用";LEGACY_COMPAT 追加参考标注。 */
    static String readoutFrequencyText(double hz, boolean legacyCompat){
        if(Double.isNaN(hz)) return bundle("blackstart.readout.freqNone", "频率不可用");
        return fmt1((float)hz) + " Hz" + (legacyCompat ? " " + bundle("blackstart.readout.compat", "原版兼容参考") : "");
    }

    /** Snapshot-only frequency; clients never substitute a local graph or nominal 50 Hz. */
    static String nativeFrequencyText(BlackstartDynamics.IslandDynamicsSnapshot snap){
        double hz=usableFrequency(snap);
        if(!Double.isFinite(hz))return bundle("blackstart.hover.vanilla.freqNone","Frequency: Unavailable");
        String text=readoutFrequencyText(hz,snap.kind()==BlackstartDynamics.ReferenceKind.LEGACY_COMPAT);
        return snap.kind()==BlackstartDynamics.ReferenceKind.EQUIVALENT
            ? bundle("blackstart.readout.equivalent","Equivalent")+" "+text : text;
    }

    /** M7:风险读数文本(纯函数)。rAll 有符号不钳制(超 ±100% 如实输出,不加 ≥/≤);tau 两位;NaN ⇒ `—`。 */
    static String readoutRiskText(float rAll, float tau){
        String r = Float.isNaN(rAll) ? "—" : String.format(java.util.Locale.ROOT, "%+.1f%%", rAll * 100f);
        return "rAll " + r + " · tau " + fmt2(tau);
    }

    /** M7:三态灯档位词(纯函数;band 0..3;非法值按 0)。 */
    static String readoutBandWord(int band){
        return band == 1 ? bundle("blackstart.readout.band.1", "警戒")
            : band == 2 ? bundle("blackstart.readout.band.2", "危急")
            : band == 3 ? bundle("blackstart.readout.band.3", "恢复中")
            : bundle("blackstart.readout.band.0", "正常");
    }

    /** M7:区档位词(0=自动;复用既有 blackstart.prot.tier.* 键,与分区开关面板同词)。 */
    static String zoneTierWord(int tier){
        return tier == 1 ? bundle("blackstart.prot.tier.discard", "可弃")
            : tier == 2 ? bundle("blackstart.prot.tier.prod", "生产")
            : tier == 3 ? bundle("blackstart.prot.tier.defense", "防御")
            : bundle("blackstart.prot.tier.auto", "自动");
    }

    /** M7:区档位词 + 来源标注(手动 = 开关面板/调度面板写过档;自动 = 分类值或未覆盖)。
     *  override=false 且 tier>0 ⇒ "生产(自动)"(自动分类所得,非用户所选);tier≤0 ⇒ 纯"自动"。 */
    static String zoneTierSourceWord(int tier, boolean override){
        if(tier <= 0) return zoneTierWord(0);
        return zoneTierWord(tier) + "(" + bundle(override ? "blackstart.zones.manual" : "blackstart.prot.tier.auto",
            override ? "手动" : "自动") + ")";
    }

    /** M7 D-4x Fix C:rAll 量化(×1000,long 值域;纯函数)。NaN ⇒ Long.MIN_VALUE(哨兵)。原 int 版
     *  Math.round(rAll*1000f) 在 rAll ≤ -2147483.648 时按 JLS 饱和到 Integer.MIN_VALUE,与"未建"哨兵
     *  同位 ⇒ 量化全等判定永真、该灯读数冻结(复核 verify:runtime a7841cc9:储能外送 S/D≥2,147,484.65
     *  可达)。long 饱和界 ±9.2e18,正常 rAll 不可达。 */
    static long rAllQuant(float rAll){
        return Float.isNaN(rAll) ? Long.MIN_VALUE : Math.round((double)rAll * 1000d);
    }

    /** M7:逐灯读数采样(观察段,20Hz;开关只停绘制不停采样)。频率走快照可用性判决;rAll/tau 走本端
     *  SafetyNet(D-39:频率=遥测权威;比值/τ 指标为本端推算)。量化(0.1Hz / 0.1% / 0.01)未变 ⇒ 不重建串。 */
    private static void sampleLampReadout(int index, int k, int rep){
        BlackstartDynamics.IslandDynamicsSnapshot snap = BlackstartDynamics.snapshotAt(rep);
        double f = usableFrequency(snap);
        boolean compat = !Double.isNaN(f) && snap.kind() == BlackstartDynamics.ReferenceKind.LEGACY_COMPAT;
        boolean equivalent = !Double.isNaN(f) && snap.kind() == BlackstartDynamics.ReferenceKind.EQUIVALENT;
        float rAll = SafetyNet.islandRAll(k), tau = SafetyNet.islandTau(k);
        int freqQ = Double.isNaN(f) ? Integer.MIN_VALUE : (int)Math.round(f * 10d);
        long rAllQ = rAllQuant(rAll);
        int tauQ = Float.isNaN(tau) ? Integer.MIN_VALUE : Math.round(tau * 100f);
        if(lampFreqQ[index] == freqQ && lampRAllQ[index] == rAllQ
            && lampTauQ[index] == tauQ && lampFreqCompat[index] == compat && lampFreqEquivalent[index] == equivalent) return;
        lampFreqQ[index] = freqQ;
        lampRAllQ[index] = rAllQ;
        lampTauQ[index] = tauQ;
        lampFreqCompat[index] = compat;
        lampFreqEquivalent[index] = equivalent;
        lampFreqText[index] = equivalent ? nativeFrequencyText(snap) : readoutFrequencyText(f, compat);
        lampRiskText[index] = readoutRiskText(rAll, tau);
    }

    /** M7 ⑨:同屏读数(postDraw;只读缓存,不另画状态灯)。行 1 = 档位词(band 色)+ 频率(白);
     *  行 2 = rAll/tau(白 0.85)。随 lampAlpha 消隐;LOD 档 ≥1 不画字(同角标文本口径)。
     *  门:视图开 + 读数开 + 世界/相机在 + 非移动端 + 未暂停 + 聊天/控制台未开 + LOD 档 0。 */
    private static void drawLampReadouts(){
        if(Vars.headless) return;                              // 纵深防御(与既有风格一致)
        if(!enabled || !readoutOn) return;
        if(Vars.world == null || Vars.renderer == null || Core.camera == null) return;
        if(Vars.mobile) return;
        if(Vars.state == null || Vars.state.isPaused()) return;
        if(Vars.ui != null && ((Vars.ui.chatfrag != null && Vars.ui.chatfrag.shown())
            || (Vars.ui.consolefrag != null && Vars.ui.consolefrag.shown()))) return;
        if(lodBand() != 0) return;                             // LOD(D9):仅档 0 画字
        float ds = Vars.renderer.getDisplayScale();
        float ts = labelTextScale(ds, readoutScale);
        float unit = ts / BADGE_TEXT_SCALE_BASE;
        Rect view = Core.camera.bounds(Tmp.r1);
        Draw.z(Z_HOVER);
        for(int i = 0; i < lampCount; i++){
            float alpha = lampAlpha[i];
            String freq = lampFreqText[i], risk = lampRiskText[i];
            if(alpha <= 0f || freq == null || risk == null) continue;
            Building b = Vars.world.build(lampPos[i]);
            if(b == null || (!othersOn && b.team != localTeam() && b.team != Team.derelict)) continue;
            float x = b.x, y = b.y + READOUT_LIFT;
            if(!pointInView(x, y, view.x, view.y, view.width, view.height)) continue;
            int band = lampBand[i];
            if(band < 0 || band > 3) band = 0;
            String word = readoutBandWord(band);
            float gap = LAMP_READOUT_GAP_PX * unit;
            float x0 = x + gap;
            Tmp.c1.set(band == 2 ? CRIT_COLOR : band == 1 ? WARN_COLOR : RECOV_COLOR).a(alpha);
            float wordW = drawLabelText(word, x0, y, Tmp.c1, ts, LABEL_PAD_PX * unit);
            Tmp.c1.set(Color.white).a(alpha);
            drawLabelText(freq, x0 + wordW + gap, y, Tmp.c1, ts, LABEL_PAD_PX * unit);
            Tmp.c1.set(Color.white).a(0.85f * alpha);
            drawLabelText(risk, x0, y - LAMP_READOUT_LINE_PX * unit, Tmp.c1, ts, LABEL_PAD_PX * unit);
        }
        Draw.reset();
    }

    /** M7:建筑 → 岛内区序号(沿区锚取图、对象身份匹配;无匹配 ⇒ -1;有界 = 区数)。 */
    private static int zoneIndexFor(Building b, int k){
        if(b == null || b.power == null || b.power.graph == null || k < 0) return -1;
        int zones = SafetyNet.islandZoneCount(k);
        for(int z = 0; z < zones; z++){
            int anchor = SafetyNet.islandZoneAnchorAt(k, z);
            if(anchor < 0) continue;
            Building ab = Vars.world.build(anchor);
            if(ab != null && ab.power != null && ab.power.graph == b.power.graph) return z;
        }
        return -1;
    }

    /** M7 D-4x Fix A:引擎口径锚格(纯函数)—— InputHandler.tileX/tileY 同式:toTile(mouseWorld − block.offset)。
     *  偶数尺寸(offset=4)与裸 toTile(mouse) 不同,必须带偏置才能与原版幽灵、实际落点同锚。 */
    static int previewAnchorTile(float mousePx, float offset){
        return World.toTile(mousePx - offset);
    }

    /** M7 D-4x Fix A:足印左下角(世界单位;纯函数)—— Block.bounds / BuildPlan.drawx / Build.validPlace-
     *  IgnoreUnits 同式:足印中心 = tile*8 + offset、边长 = size*8 ⇒ 左下 = tile*8 + offset − size*8/2。
     *  原缺陷把格心(tile*8)当左下角 ⇒ 框整体偏 (size*4 − offset)(1x1/2x2 半格、3x3 一格半)。 */
    static float footprintCornerPx(int tile, float size, float offset, float ts8){
        return tile * ts8 + offset - size * ts8 / 2f;
    }

    /** M7 ⑩:建造预览(postDraw;守卫与悬浮同构但无 LOD 门 —— 功能性要素恒开)。足印描边
     *  (Lines.rect,(x,y)=左下角;粗细 = Lines.stroke)+ 光标右上区域提示。单一强调色,不做红绿有效性
     *  判定(rotation/team 参数歧义,免误报)。锚定与引擎幽灵同源(018 字节码实证):锚格 =
     *  toTile(mouseWorld − block.offset)(InputHandler.tileX);原版幽灵走 InputHandler.drawPlan(isPlacing
     *  分支),laserOpacity=0 只压连线/光束(Block 唯一读取点在 lambda$drawPotentialLinks$2)不压幽灵
     *  —— 本描边是叠加的落点强调,错位即与原版幽灵同屏对打。
     *  区域提示口径(只报可确证的):足印处有建筑 ⇒ 按建筑身份定岛/区(区锚取图对象身份匹配);
     *  有建筑无岛 ⇒ "无电网";空地块 ⇒ 只画足印不报区域(不做事先邻接预判,实际归属由引擎建成时定)。 */
    private static void drawPreview(){
        if(Vars.headless) return;                              // 纵深防御(与既有风格一致)
        if(!enabled || !previewOn) return;
        if(Vars.world == null || Vars.renderer == null) return;
        if(Vars.mobile) return;
        if(Vars.state == null || Vars.state.isPaused()) return;
        if(Vars.ui != null && ((Vars.ui.chatfrag != null && Vars.ui.chatfrag.shown())
            || (Vars.ui.consolefrag != null && Vars.ui.consolefrag.shown()))) return;
        Block block = Vars.control == null || Vars.control.input == null ? null : Vars.control.input.block;
        if(block == null) return;                              // 未在摆放 ⇒ 不画

        Vec2 m = Core.input.mouseWorld();
        int tx = previewAnchorTile(m.x, block.offset), ty = previewAnchorTile(m.y, block.offset);
        float ts8 = Vars.tilesize;
        float w = block.size * ts8;
        Draw.z(Z_HOVER);
        Lines.stroke(1.5f);
        Draw.color(CORE_COLOR, 0.9f);
        Lines.rect(footprintCornerPx(tx, block.size, block.offset, ts8),
            footprintCornerPx(ty, block.size, block.offset, ts8), w, w);

        Building b = Vars.world.buildWorld(m.x, m.y);
        if(b != null){
            int k = SafetyNet.islandAt(b.pos());
            String hint;
            if(k < 0){
                hint = bundle("blackstart.preview.offGrid", "无电网");
            }else{
                int z = zoneIndexFor(b, k);
                hint = z < 0 ? bundle("blackstart.preview.zone", "区域") + " —"
                    : bundle("blackstart.preview.zone", "区域") + " "
                        + zoneTierSourceWord(SafetyNet.islandZoneTierAt(k, z), SafetyNet.islandZoneOverrideAt(k, z));
            }
            float ds = Vars.renderer.getDisplayScale();
            float ts = Mathf.clamp(BADGE_TEXT_SCALE_BASE / ds, HOVER_SCALE_MIN, HOVER_SCALE_MAX);
            Tmp.c1.set(Color.white).a(0.9f);
            Drawf.text(hint, m.x + LAMP_READOUT_GAP_PX / ds, m.y + LAMP_READOUT_LINE_PX / ds, Tmp.c1, ts, Align.left);
        }
        Draw.reset();
    }

    /** 独立观察游标:关/折叠事件面板也消费新防御沿;M4 起多岛队列(≤4),正文仅缓存失效时重建。 */
    private static void observeDefense(){
        if(!enabled || Vars.world == null || Vars.state == null){
            if(defenseRoot != null) defenseRoot.visible = false;
            return;
        }
        long seq = BlackstartEvents.seq();
        if(seq > defenseSeq){
            int take = (int)Math.min(seq - defenseSeq, BlackstartEvents.size());
            for(int i = 0; i < take; i++){                 // i=0 最新 → 旧;M4:全部新沿入队(不再只取最新一条)
                BlackstartEvents.Entry e = BlackstartEvents.get(i);
                if(e == null) break;
                if(e.kind != BlackstartEvents.Kind.SHED || e.aux != 0) continue;
                enqueueDefense(Point2.pack(e.x, e.y), e.tick + DEFENSE_TICKS);
            }
        }
        defenseSeq = seq;
        // 每帧修剪:窗口已过,或该位置所属岛已不再缺口(M4:缺口恢复即撤横幅)→ 出队(尾替空洞)。
        for(int s = 0; s < defenseCount; s++){
            if((double)defenseDeadline[s] <= Vars.state.tick || !SafetyNet.defensePendingAt(defenseAnchor[s])){
                defenseCount--;
                defenseAnchor[s] = defenseAnchor[defenseCount];
                defenseDeadline[s] = defenseDeadline[defenseCount];
                s--;
            }
        }
        // 显示槽 = 最早截止(最紧迫);同刻先入队者先显示。
        int shown = -1;
        for(int s = 0; s < defenseCount; s++){
            if(shown < 0 || defenseDeadline[s] < defenseDeadline[shown]) shown = s;
        }
        defenseShown = shown;
        int seconds = shown < 0 ? 0
            : Math.max(1, Math.min(3, (int)Math.ceil((defenseDeadline[shown] - Vars.state.tick) / 60d)));
        if(defenseCacheSlot != shown || defenseCacheSec != seconds){
            defenseCacheSlot = shown;
            defenseCacheSec = seconds;
            defenseCacheText = shown < 0 ? "" : buildDefenseText(shown, seconds);
        }
        if(defenseRoot == null) return;
        defenseRoot.visible = shown >= 0;
        if(shown < 0) return;
        if(!defenseLabel.textEquals(defenseCacheText)){
            defenseLabel.setText(defenseCacheText);
            defenseRoot.pack();
        }
        float wave = (1f - Mathf.cos((Time.time % 42f) * Mathf.PI2 / 42f)) * 0.5f;
        defenseLabel.setColor(CRIT_FLASH_COLOR.r, CRIT_FLASH_COLOR.g, CRIT_FLASH_COLOR.b, 0.6f + 0.4f * wave);
        defenseRoot.setPosition(Math.max(0f, (panelAreaW() - defenseRoot.getWidth()) * 0.5f),
            Math.max(0f, panelAreaH() - defenseRoot.getHeight() - Scl.scl(12f)));
    }

    /** M4 入队:同锚合并(截止取 max,旧沿不缩短新沿),满槽替换最晚截止(保最紧迫 4 条)。 */
    private static void enqueueDefense(int packedAnchor, float deadline){
        for(int s = 0; s < defenseCount; s++){
            if(defenseAnchor[s] == packedAnchor){
                if(deadline > defenseDeadline[s]) defenseDeadline[s] = deadline;
                return;
            }
        }
        if(defenseCount < DEFENSE_SLOTS){
            defenseAnchor[defenseCount] = packedAnchor;
            defenseDeadline[defenseCount] = deadline;
            defenseCount++;
            return;
        }
        int latest = 0;
        for(int s = 1; s < DEFENSE_SLOTS; s++) if(defenseDeadline[s] > defenseDeadline[latest]) latest = s;
        defenseAnchor[latest] = packedAnchor;
        defenseDeadline[latest] = deadline;
    }

    /** M4 正文:defense 模板 {0}=剩余秒/{1}=岛 "x,y";多岛时接 defense.more "+{0}"(另计岛数)。 */
    private static String buildDefenseText(int slot, int seconds){
        String template = bundle("blackstart.shed.defense", "[电网] 仅剩防御档:缺口不恢复将在 {0} 秒后停防御 (岛 {1})");
        Point2 p = Point2.unpack(defenseAnchor[slot]);
        String text = template.replace("{0}", Integer.toString(seconds)).replace("{1}", p.x + "," + p.y);
        if(defenseCount > 1){
            String more = bundle("blackstart.defense.more", "+{0}");
            text = text + " " + more.replace("{0}", Integer.toString(defenseCount - 1));
        }
        return text;
    }

    /** BACKOFF 仅走冻结区级门;框 6×6、弧半径 2.5、75% 圆弧留缺口。 */
    private static void drawLockBadges(){
        Rect view = Core.camera.bounds(Tmp.r1);
        Draw.z(Z_HOVER);
        Draw.color(WARN_COLOR);
        Lines.stroke(1f);
        for(int k = 0; k < SafetyNet.islandCount(); k++){
            for(int z = 0; z < SafetyNet.islandZoneCount(k); z++){
                if(SafetyNet.islandZoneStateAt(k, z) != 2) continue;
                int pos = SafetyNet.islandZoneAnchorAt(k, z);
                Building b = Vars.world.build(pos);
                if(b == null || (!othersOn && b.team != localTeam() && b.team != Team.derelict)) continue;
                float x = b.x + 6f, y = b.y + 6f;
                if(!pointInView(x, y, view.x, view.y, view.width, view.height)) continue;
                Lines.rect(x - 3f, y - 3f, 6f, 6f);
                Lines.arc(x, y + 3f, 2.5f, 0.75f, 0f);
            }
        }
        Draw.reset();
    }

    /** 采样进入沿 ⇒ SAT_WARN / SAT_CRIT / SAT_RECOVER(仅三个目标态;零新迟滞 / 零新阈值)。 */
    private static void emitSatEdge(int prev, int s, int ci, Comp c){
        if(prev == s) return;
        BlackstartEvents.Kind k;
        if(s == ST_WARN) k = BlackstartEvents.Kind.SAT_WARN;
        else if(s == ST_CRIT) k = BlackstartEvents.Kind.SAT_CRIT;
        else if(s == ST_RECOV) k = BlackstartEvents.Kind.SAT_RECOVER;
        else return;
        BlackstartEvents.push(k, Point2.x(c.minPos), Point2.y(c.minPos), ci + 1, c.sat);
    }

    /**
     * 链路增删(1Hz diff)。快照 = 全网关 modLinks 的规范化无向键(A = min(自身,对端)、
     * B = max(自身,对端),键 = (A&lt;&lt;32)|B)升序去重数组;新中未见 ⇒ LINK_ADD,旧中未存 ⇒ LINK_DEL。
     * 首个快照只记不产;低于 1Hz 的「加了又删」净零变化不产(登记);网关被毁连带多跳 DEL = 事实直报。
     */
    private static void observeLinks(long now){
        if(now - lastLinkScanMs < 1000L) return;
        lastLinkScanMs = now;

        int n = 0;
        for(int i = 0; i < gwCount; i++){
            int gwPack = gwPos[i];
            Building gwb = Vars.world.build(gwPack);        // 只读;越界自带 null
            if(!(gwb instanceof GatewayNode.GatewayNodeBuild gw)) continue;
            IntSeq ml = gw.modLinks;                        // 只读:.size / .get
            for(int j = 0; j < ml.size; j++){
                int peer = ml.get(j);
                if(peer == gwPack) continue;
                int a = Math.min(gwPack, peer);
                int b = Math.max(gwPack, peer);
                if(n >= linkKeys.length){
                    linkKeys = java.util.Arrays.copyOf(linkKeys, Math.max(8, linkKeys.length * 2));
                }
                linkKeys[n++] = ((long)a << 32) | (b & 0xffffffffL);
            }
        }

        n = sortDedup(linkKeys, n);
        if(linksSnap == null){                              // 首快照:只记不产
            linksSnap = java.util.Arrays.copyOf(linkKeys, n);
            return;
        }

        long[] old = linksSnap;                             // 归并比对(两侧均已升序去重)
        int oi = 0, ni = 0;
        while(oi < old.length || ni < n){
            long ov = oi < old.length ? old[oi] : Long.MAX_VALUE;
            long nv = ni < n ? linkKeys[ni] : Long.MAX_VALUE;
            if(ov == nv){
                oi++; ni++;
            }else if(nv < ov){
                int aa = (int)(nv >>> 32), bb = (int)nv;
                BlackstartEvents.push(BlackstartEvents.Kind.LINK_ADD,
                    Point2.x(aa), Point2.y(aa), bb, Float.NaN);
                ni++;
            }else{
                int aa = (int)(ov >>> 32), bb = (int)ov;
                BlackstartEvents.push(BlackstartEvents.Kind.LINK_DEL,
                    Point2.x(aa), Point2.y(aa), bb, Float.NaN);
                oi++;
            }
        }

        linksSnap = java.util.Arrays.copyOf(linkKeys, n);
    }

    /** 原地排序 + 去重,返回唯一元素数(纯 JVM;1Hz 路径允许分配)。 */
    private static int sortDedup(long[] a, int n){
        if(n <= 1) return n;
        java.util.Arrays.sort(a, 0, n);
        int w = 1;
        for(int i = 1; i < n; i++){
            if(a[i] != a[w - 1]) a[w++] = a[i];
        }
        return w;
    }

    /**
     * 岛分裂 / 合并(rebuild 后身份比对)。身份编码 = 每 comp 一条 long:
     * (minPos &lt;&lt; 32) | ((count &amp; 0xFFFF) &lt;&lt; 16) | posHash16;posHash = 逐成员 packed 坐标
     * 混合后 XOR 累加(顺序无关)。多重集差:|消失|==1 且 |新出|&gt;=2 ⇒ SPLIT;|消失|&gt;=2 且
     * |新出|==1 ⇒ MERGE;其余(1↔1 / 多对多 / 只增只减)不产。首快照只记不产;换图清 null。
     */
    private static void observeIslands(){
        int n = comps.length;
        long[] ids = new long[n];
        for(int i = 0; i < n; i++) ids[i] = islandId(comps[i]);
        java.util.Arrays.sort(ids);

        if(islandSnap == null){                             // 首快照:只记不产
            islandSnap = ids;
            return;
        }

        long[] old = islandSnap;
        int gone = 0, added = 0;
        int minAddedPos = Integer.MAX_VALUE;
        int oi = 0, ni = 0;
        while(oi < old.length || ni < n){
            long ov = oi < old.length ? old[oi] : Long.MAX_VALUE;
            long nv = ni < n ? ids[ni] : Long.MAX_VALUE;
            if(ov == nv){
                oi++; ni++;
            }else if(nv < ov){
                added++;
                int mp = (int)(nv >>> 32);
                if(Integer.compareUnsigned(mp, minAddedPos) < 0) minAddedPos = mp;
                ni++;
            }else{
                gone++;
                oi++;
            }
        }

        if(gone == 1 && added >= 2){
            BlackstartEvents.push(BlackstartEvents.Kind.ISLAND_SPLIT,
                Point2.x(minAddedPos), Point2.y(minAddedPos), added, Float.NaN);
        }else if(gone >= 2 && added == 1){
            BlackstartEvents.push(BlackstartEvents.Kind.ISLAND_MERGE,
                Point2.x(minAddedPos), Point2.y(minAddedPos), gone, Float.NaN);
        }

        islandSnap = ids;
    }

    /** 每 comp 一条身份 primitive(编码见 {@link #observeIslands()})。 */
    private static long islandId(Comp c){
        int ph = 0;
        Building[] ms = c.members;
        for(int i = 0; i < ms.length; i++){
            int p = Point2.pack(ms[i].tileX(), ms[i].tileY());
            int h = p * 0x9E3779B1;
            h ^= h >>> 15;
            ph ^= h;                                        // XOR 累加 ⇒ 顺序无关
        }
        ph = (ph ^ (ph >>> 16)) & 0xFFFF;
        return ((long)c.minPos << 32) | ((long)(c.count & 0xFFFF) << 16) | (long)ph;
    }

    // ============================================================
    // A6 drawGridLayer(①原版线 / ③微染 / ④孤岛高亮;drawOver 段)
    // ============================================================

    /**
     * 垫底层绘制。顺序写死:③微染(z64.9)→ ④孤岛(z65.0)→ ①原版线(z70.1)。
     * 透明度:仅【线层】乘 followLink;微染与④为环境底色,不乘。
     * ONLY 模式:首行 return(①②③④全隐;I1 的 ⑤ 不受影响)。
     * ⚠ C-1:本段绘制可能被随后 flush 的建筑盖住 —— 这是【预期的底层效果】。
     */
    private static void drawGridLayer(){
        if(mode == MODE_ONLY) return;
        if(comps.length == 0) return;

        int band = lodBand();                     // I5(D9):统一 LOD 档(0 全量 / 1 轻 / 2 最轻)

        Rect view = Core.camera.bounds(Tmp.r1);
        float vx = view.x, vy = view.y, vw = view.width, vh = view.height;

        // ① 的 follow 特例:laserOpacity==0 时本层仍显示(设计 §2.3-①/§8.4 字面要求)
        float followLink = (mode == MODE_FULL && followLaser && Renderer.laserOpacity > 0f)
            ? Mathf.clamp(Renderer.laserOpacity, 0f, 1f)
            : 1f;

        if(band < 2){
            // ---- ③ 分量内建筑微染(z=64.9;先视口剔除,全屏方块数超 WASH_CAP 提前停)----
            Draw.z(Z_WASH);
            int washDrawn = 0;
            wash:
            for(int ci = 0; ci < comps.length; ci++){
                Comp c = comps[ci];
                if(c.color == null || !compShown(c) || !compInView(c, vx, vy, vw, vh)) continue;
                for(int mi = 0; mi < c.members.length; mi++){
                    Building m = c.members[mi];
                    if(!pointInView(m.x, m.y, vx, vy, vw, vh)) continue;
                    if(washDrawn >= WASH_CAP) break wash;
                    float sz = m.block.size * 8f;                 // 中心语义,Fill.rect
                    Draw.color(colorMode == 1 ? LOAD_BASE_COLOR : c.color, WASH_ALPHA);   // E1:纯负载走同色系
                    Fill.rect(m.x, m.y, sz, sz);
                    washDrawn++;
                }
            }
            Draw.reset();
        }

        // ---- ④ 孤岛高亮(z=65.0;仅 state>NORMAL 的分量;islandOn 关 ⇒ 零绘制;LOD 不门)----
        if(islandOn){
            Draw.z(Z_ISLAND);
            drawIslands(vx, vy, vw, vh);
            Draw.reset();
        }

        if(band < 2 && redrawVanillaLines()){
            // ---- ① 原版线染色(z=70.1;逐帧只遍历缓存 + 视口剔除)----
            // 相位先取模:Mathf.sin/cos 是查表实现,实参 x*2607.5945 的 f2i 在 x≥~823566 rad 时
            // 溢出饱和到 sinTable[16383] → 动画冻结(Time.time 长跑必然触发)。取模后实参 ≤2π,精度足够。
            Draw.z(Z_LINE);
            float pulse = 0.7f + 0.3f * Mathf.sin((Time.time % PULSE_PERIOD_CRIT) * Mathf.PI2 / PULSE_PERIOD_CRIT);
            for(int i = 0; i < segO.size; i++){
                SegO s = segO.get(i);
                if(!segmentInViewO(s, vx, vy, vw, vh)) continue;
                Comp c = comps[s.comp];
                if(c.color == null || !compShown(c)) continue;

                // D4 方案①:负载档离散 ⇒ t∈{0, 0.5, 1}(档界与迟滞见 updateLoadBand),t 只驱动【颜色】。
                // band<0(仅「重建后本帧未采样」的理论空窗)⇒ 连续映射兜底,如实不撒谎。
                float t = c.loadBand < 0 ? Mathf.clamp((c.load - 0.5f) / 0.5f, 0f, 1f) : c.loadBand * 0.5f;   // 图级负载映射
                float alpha = BASE_ALPHA * followLink;

                // E1(D7)配色模式:0 混合(现状一字不改)/ 1 纯负载 / 2 纯分色(负载只走宽度 / 脉冲)
                if(colorMode == 1){
                    Tmp.c1.set(LOAD_BASE_COLOR).lerp(WARN_COLOR, t);   // 纯负载:全图同色系,叠负载偏移
                }else if(colorMode == 2){
                    Tmp.c1.set(c.color);                               // 纯分色
                }else{
                    Tmp.c1.set(c.color).lerp(WARN_COLOR, t);           // 混合(默认)
                }
                // 状态色优先:缺电红化 / 红闪三模式全保留(现构一字不动)
                if(c.need > 0f && c.sat < SAT_WARN){
                    Tmp.c1.lerp(CRIT_COLOR, 0.6f);
                    alpha *= pulse;
                }
                if(c.need > 0f && c.sat <= EPS){
                    Tmp.c1.set(CRIT_FLASH_COLOR);                          // 同脉冲(上面已乘)
                }

                // I6(用户拍板):线宽统一 —— 恒定 LINE_WIDTH(与 ⑤ mod 链路同宽),不再随负载档变化。
                // 代价如实记录:纯分色(colorMode 2)的负载不再有宽度载体(读数移到悬浮 / 事件层)。
                float w = LINE_WIDTH;
                Draw.color(Tmp.c1, alpha);
                Lines.stroke(w);
                Lines.line(s.x1, s.y1, s.x2, s.y2);
            }
            Draw.reset();
        }
    }

    /**
     * ④ 孤岛高亮。遍历顺序 = CRIT(含 dead) 全部 → WARN → RECOVERING,同级按 minPos 升序
     * (comps 已按 minPos 升序 ⇒ 三趟即可)。>BIG_COMP 的分量只画包围盒(且不受 HILITE_CAP 限制);
     * 高亮方块全屏计数(剔除后)达 HILITE_CAP 即停止画剩余方块。
     */
    private static void drawIslands(float vx, float vy, float vw, float vh){
        int drawnSquares = 0;
        for(int pass = 0; pass < 3; pass++){
            int want = pass == 0 ? ST_CRIT : (pass == 1 ? ST_WARN : ST_RECOV);
            float period = pass == 0 ? 0.7f : (pass == 1 ? 1.2f : 2.0f);
            float lo = pass == 0 ? 0.12f : (pass == 1 ? 0.10f : 0.08f);
            float hi = pass == 0 ? 0.30f : (pass == 1 ? 0.22f : 0.16f);
            Color base = pass == 0 ? CRIT_COLOR : (pass == 1 ? WARN_COLOR : RECOV_COLOR);

            // 相位先取模(同 ①:避开 Mathf.cos 查表 f2i 溢出饱和)
            float wave = (1f - Mathf.cos((Time.time % period) * Mathf.PI2 / period)) * 0.5f;
            float a = Mathf.lerp(lo, hi, wave);
            Tmp.c2.set(base).a(a);

            for(int ci = 0; ci < comps.length; ci++){
                Comp c = comps[ci];
                if(c.state != want) continue;
                if(!compShown(c)) continue;                  // E2(D8):他人电网过滤(默认只画本地队)
                if(!compInView(c, vx, vy, vw, vh)) continue;

                if(c.members.length > BIG_COMP){
                    // 大分量只画包围盒(包围盒本身已过视口剔除;不受 HILITE_CAP 限制)
                    Drawf.dashRect(Tmp.c2, c.minX, c.minY, c.maxX - c.minX, c.maxY - c.minY);
                }else{
                    for(int mi = 0; mi < c.members.length; mi++){
                        if(drawnSquares >= HILITE_CAP) break;
                        Building m = c.members[mi];
                        if(!pointInView(m.x, m.y, vx, vy, vw, vh)) continue;
                        // 半径 = 半格 +1px(引擎同款 size*8f/2f+1f);Drawf.square 带色重载
                        Drawf.square(m.x, m.y, m.block.size * 4f + 1f, Tmp.c2);
                        drawnSquares++;
                    }
                }
            }
        }
    }

    // ============================================================
    // 绘制(postDraw 段;调用顺序即叠放顺序)
    // ============================================================

    /**
     * 遍历缓存 + 视口剔除 + 绘制。绝不在这里扫 tile、绝不重建。
     * 绘制顺序:mod 链路(外暗壳 → 内芯 → 悬挂小叉 → 网关端锚记)→ 网关方块描边。
     */
    private static void drawSegments(){
        Draw.z(Z_MOD);                                // ⑤ 修正项 b
        Rect view = Core.camera.bounds(Tmp.r1);
        float vx = view.x, vy = view.y, vw = view.width, vh = view.height;

        // 跟随原版激光透明度:FULL + 跟随开关开 才乘;ONLY 强制不跟随
        // (ONLY 的意义就是「替代视图」——玩家把原版激光调没时用它)。
        float follow = (mode == MODE_FULL && followLaser && Renderer.laserOpacity > 0f)
            ? Mathf.clamp(Renderer.laserOpacity, 0f, 1f)
            : 1f;
        float coreAlpha = BASE_ALPHA * follow;

        int visible = 0;
        for(int i = 0; i < segs.size; i++){
            Seg s = segs.get(i);
            if(!segmentInView(s, vx, vy, vw, vh)) continue;   // 粗剔除:两端都在界外即跳过
            if(!segShown(s)) continue;                        // E2(D8):他人电网过滤(默认只画本地队)
            visible++;                                        // drawn = 实绘线段数(视口剔除 + 队过滤后)

            BsVisualData data = linkData(s);
            int state = data == null ? -1 : data.bsLinkState(s.linkIndex);
            float flow = data == null ? Float.NaN : data.bsLinkFlow(s.linkIndex);
            float cap = data == null ? Float.NaN : data.bsLinkCapacity(s.linkIndex);
            float width = Float.isFinite(flow) && Float.isFinite(cap) && cap > 0f
                ? 1.2f + 3.2f * Mathf.clamp(flow / cap, 0f, 1f) : LINE_WIDTH;
            boolean dashed = s.dang || state == 2 || state == 3 || state == 4 || state == 5;
            Color color = s.dang ? DANGLE_COLOR : state == 1 || state == 5 ? WARN_COLOR
                : state == 2 || state == 3 || state == 4 ? DANGLE_COLOR
                : state == 0 || state == 6 ? componentColor(s.source) : CORE_COLOR;
            float alpha = coreAlpha;
            if(!s.dang && state == 1){
                float wave = (1f - Mathf.cos((Time.time % LIMIT_PERIOD_TICKS) * Mathf.PI2 / LIMIT_PERIOD_TICKS)) * 0.5f;
                alpha *= 0.6f + 0.4f * wave;
            }
            if(!s.dang && state == 3 && Time.time - s.tripAt >= 0f && Time.time - s.tripAt < TRIP_FLASH_TICKS){
                color = CRIT_FLASH_COLOR;               // 进入沿亮色替换内芯,线型仍为虚线
            }
            // ① 外层暗色壳:#0B0F14、alpha 0.85×基准、线宽 w+1.6(实线/虚线同型)
            Draw.color(SHELL_COLOR, coreAlpha * 0.85f);
            Lines.stroke(width + SHELL_EXTRA_WIDTH);
            strokeModLink(s, dashed);

            // ② 内芯:正常 = 模组青实线;悬挂 = 灰虚线(dash 段数 6)
            Draw.color(color, alpha);
            Lines.stroke(width);
            strokeModLink(s, dashed);
            if(!s.dang && state == 4 && s.target instanceof BsVisualData target){
                drawRecloseRing(s.x2, s.y2, s.target.block.size, target.bsRecloseProgress(), follow);
            }

            // ③ 悬挂:在对端缺失端画小叉(两条短线,同悬挂灰)。
            //    A5 的「小叉 / 半透明问号方块」二选一 —— 本批取【小叉】(问号方块属 P3 悬浮层)。
            if(s.dang){
                Draw.color(DANGLE_COLOR, coreAlpha);
                Lines.stroke(1.5f);
                Lines.line(s.x2 - CROSS_ARM, s.y2 - CROSS_ARM, s.x2 + CROSS_ARM, s.y2 + CROSS_ARM);
                Lines.line(s.x2 - CROSS_ARM, s.y2 + CROSS_ARM, s.x2 + CROSS_ARM, s.y2 - CROSS_ARM);
            }

            // ④ 网关端小方块锚记(Drawf.square 内含 Lines.stroke(颜色) 且末尾 Draw.reset(),
            //    故放在本条线段绘制的最后一步,不做其后依赖 Draw 状态的绘制)
            Tmp.c1.set(componentColor(s.source)).a(coreAlpha);
            Drawf.square(s.x1, s.y1, ENDPOINT_SIZE, Tmp.c1);
        }

        // 网关方块描边(ONLY 模式也画):呼吸 alpha 0.25–0.45(周期 2s)+ 放置强闪 1.5s 衰减
        for(int i = 0; i < gwCount; i++){
            if(!pointInView(gwX[i], gwY[i], vx, vy, vw, vh)) continue;
            float a = breathAlpha();
            long age = flashAge(i);
            if(age >= 0){
                a = Mathf.lerp(FLASH_ALPHA, a, age / (float)FLASH_LIFE_MS);
            }
            Building b = gwBuild[i];
            if(!othersOn && b.team != localTeam() && b.team != Team.derelict) continue;
            boolean device = b.block instanceof BreakerNode || b.block instanceof ZoneSwitchNode;
            int state = device && b instanceof BsVisualData data ? data.bsSwitchState() : -1;
            Color color = state == 2 || state == 4 ? DANGLE_COLOR
                : state == 3 ? CRIT_COLOR : componentColor(b);
            if(state == 2 || state == 4) a = BREATH_ALPHA_MAX;
            if(state == 3){
                a = 0.65f;
                if(Time.time - gwTripAt[i] >= 0f && Time.time - gwTripAt[i] < TRIP_FLASH_TICKS){
                    color = CRIT_FLASH_COLOR; a = FLASH_ALPHA;
                }
            }
            Tmp.c2.set(color).a(a * follow);
            Drawf.square(gwX[i], gwY[i], GATEWAY_OUTLINE_SIZE * b.block.size, Tmp.c2);
            if(state == 4 && b instanceof BsVisualData data){
                drawRecloseRing(gwX[i], gwY[i], b.block.size, data.bsRecloseProgress(), follow);
            }
            if(hoverOn && hoverTargetKind == HOVER_KIND_BUILDING && hoverTargetKey == b.pos()){
                Drawf.dashCircle(gwX[i], gwY[i], b.block.size * 4f + 3f, color);
            }
        }

        Draw.reset();
        drawn = visible;
    }

    /** 按当前悬挂标记画实线或虚线(颜色/线宽由调用方先设好)。 */
    private static void strokeModLink(Seg s, boolean dashed){
        if(dashed){
            Lines.dashLine(s.x1, s.y1, s.x2, s.y2, DASH_SEGMENTS);
        }else{
            Lines.line(s.x1, s.y1, s.x2, s.y2);
        }
    }

    /** 017:目标端底环暗 + 进度弧亮;NaN 只画底环,半径=size×2.5+3。 */
    private static void drawRecloseRing(float x, float y, int size, float progress, float alpha){
        float radius = size * 2.5f + 3f;
        Draw.color(DANGLE_COLOR, 0.25f * alpha);
        Lines.stroke(1.2f);
        Lines.circle(x, y, radius);
        if(Float.isFinite(progress)){
            Draw.color(CORE_COLOR, 0.85f * alpha);
            Lines.arc(x, y, radius, Mathf.clamp(progress, 0f, 1f), 90f);
        }
    }

    /** 呼吸基线的中心点(GATEWAY_OUTLINE_SIZE 的方形描边半径约 8.5px,套住 1 格方块)。 */
    private static float breathAlpha(){
        // 相位先取模(同 ①:避开 Mathf.sin 查表 f2i 溢出饱和)
        float wave = 0.5f + 0.5f * Mathf.sin((Time.time % BREATH_PERIOD_S) * Mathf.PI2 / BREATH_PERIOD_S);
        return Mathf.lerp(BREATH_ALPHA_MIN, BREATH_ALPHA_MAX, wave);
    }

    /** 线段视口剔除(两端都在界外才跳过;边界线段保留 = 粗剔除)。 */
    private static boolean segmentInView(Seg s, float vx, float vy, float vw, float vh){
        float minX = Math.min(s.x1, s.x2), maxX = Math.max(s.x1, s.x2);
        float minY = Math.min(s.y1, s.y2), maxY = Math.max(s.y1, s.y2);
        return maxX >= vx - CULL_MARGIN && minX <= vx + vw + CULL_MARGIN
            && maxY >= vy - CULL_MARGIN && minY <= vy + vh + CULL_MARGIN;
    }

    /** 原版线段的视口剔除(与 segmentInView 同款)。 */
    private static boolean segmentInViewO(SegO s, float vx, float vy, float vw, float vh){
        float minX = Math.min(s.x1, s.x2), maxX = Math.max(s.x1, s.x2);
        float minY = Math.min(s.y1, s.y2), maxY = Math.max(s.y1, s.y2);
        return maxX >= vx - CULL_MARGIN && minX <= vx + vw + CULL_MARGIN
            && maxY >= vy - CULL_MARGIN && minY <= vy + vh + CULL_MARGIN;
    }

    /** 点视口剔除(网关描边/角标/高亮用)。 */
    private static boolean pointInView(float x, float y, float vx, float vy, float vw, float vh){
        return x >= vx - CULL_MARGIN && x <= vx + vw + CULL_MARGIN
            && y >= vy - CULL_MARGIN && y <= vy + vh + CULL_MARGIN;
    }

    /** 分量包围盒视口剔除(③/④ 迭代成员前的粗剔除)。 */
    private static boolean compInView(Comp c, float vx, float vy, float vw, float vh){
        return c.maxX >= vx - CULL_MARGIN && c.minX <= vx + vw + CULL_MARGIN
            && c.maxY >= vy - CULL_MARGIN && c.minY <= vy + vh + CULL_MARGIN;
    }

    // ---- I5 ⑥ 视觉开关 helper(D8 他人电网 / D9 LOD;全部只读)----

    /** 本地队 = 玩家所在队;无玩家(观战 / 未入场)回落 sharded(D8)。 */
    private static Team localTeam(){
        return Vars.player != null ? Vars.player.team() : Team.sharded;
    }

    /**
     * 该分量是否显示(D8)。othersOn 开 ⇒ 全显;否则只显示本地队分量。
     * derelict(中立残骸)不算「他人」,恒显 —— 保单机默认态 no-op(单机分量非 sharded 即 derelict)。
     */
    private static boolean compShown(Comp c){
        if(othersOn) return true;
        Building rep = c.rep != null ? c.rep : (c.members.length > 0 ? c.members[0] : null);
        if(rep == null) return true;
        return rep.team == localTeam() || rep.team == Team.derelict;
    }

    /** mod 链路段是否显示(D8):按归属网关的队判定(悬挂 / 取不到网关 ⇒ 显)。 */
    private static boolean segShown(Seg s){
        if(othersOn) return true;
        if(s.gw < 0 || s.gw >= gwCount || Vars.world == null) return true;
        Building gwb = Vars.world.build(gwPos[s.gw]);
        if(gwb == null) return true;
        return gwb.team == localTeam() || gwb.team == Team.derelict;
    }

    /**
     * 统一 LOD 档(I5 裁定 D9):0 = 全量(ds ≥ 0.75)/ 1 = 轻(0.45 ≤ ds &lt; 0.75)/
     * 2 = 最轻(ds &lt; 0.45);lowfx ⇒ 档 +1(封顶 2)。HUD 面板(设置 / 事件)不受此门。
     */
    private static int lodBand(){
        if(Vars.renderer == null) return 0;
        float ds = Vars.renderer.getDisplayScale();
        int band = ds >= BADGE_TEXT_LOD ? 0 : (ds >= BADGE_LOD_MIN ? 1 : 2);
        return (lowfxOn && band < 2) ? band + 1 : band;
    }

    // ============================================================
    // 放置强闪
    // ============================================================

    /** 记录一次放置强闪(pos = Point2.pack 绝对坐标)。上限 64,满则挤掉最旧一条。 */
    private static void markFlash(int pos){
        if(flashCount >= FLASH_CAP){
            System.arraycopy(flashPos, 1, flashPos, 0, FLASH_CAP - 1);
            System.arraycopy(flashAt, 1, flashAt, 0, FLASH_CAP - 1);
            flashCount = FLASH_CAP - 1;
        }
        flashPos[flashCount] = pos;
        flashAt[flashCount] = Time.millis();
        flashCount++;
    }

    /** 查询该网关的强闪年龄(ms);无强闪或已过期返回 -1。 */
    private static long flashAge(int gwIndex){
        int pos = gwPos[gwIndex];
        for(int i = 0; i < flashCount; i++){
            if(flashPos[i] == pos){
                long age = Time.millis() - flashAt[i];
                return age < FLASH_LIFE_MS ? age : -1L;
            }
        }
        return -1L;
    }

    /** 原地压缩掉过期条目(重建时调用)。 */
    private static void pruneFlashes(){
        long now = Time.millis();
        int w = 0;
        for(int i = 0; i < flashCount; i++){
            if(now - flashAt[i] < FLASH_LIFE_MS){
                flashPos[w] = flashPos[i];
                flashAt[w] = flashAt[i];
                w++;
            }
        }
        flashCount = w;
    }

    // ============================================================
    // 开关 / 命令 / HUD
    // ============================================================

    /**
     * 视图轮换(FULL → ONLY → OFF → FULL);供 HUD 按钮调用。
     * 每次切换落 Core.settings(本机偏好),并置脏让缓存尽快刷新。
     */
    public static void cycleMode(){
        if(!enabled){
            enabled = true;
            mode = MODE_FULL;
        }else if(mode == MODE_FULL){
            mode = MODE_ONLY;
        }else{
            enabled = false;
        }
        if(!enabled) applyVanillaLinePolicy(false);
        persist();
        dirty = DIRTY_ALL;
        updateHudText();
    }

    /**
     * `bs-view <on|off|only|debug|rescan|island|hover|badges|events|others|lowfx [on|off]|color 0|1|2|evpos 0|1|2|3>`
     * 实现(参数全可选;runner 收到的数组不含命令名)。
     * 归一化照抄 bs-set:String.join + 全角空格容错 + split;长度判断与分派一律基于归一化后的 parts。
     *
     * 行为冻结(I1):on/off/only/rescan 的 emit 文本与状态变更逐字节不变;debug 主行前缀不变(只许尾追)。
     * 新增(I2/I3):island / hover / badges 单 token = 查询(零状态变更)、双 token on/off = 设置(写偏好 + 置脏)。
     * 新增(I5):events / others / lowfx 同构;color 0|1|2、evpos 0|1|2|3 双 token 设置、单 token 查询。
     * 可达性变化:新增第二可选参后,`bs-view on off` 等双 token 输入从「引擎层拒绝」变为「进 runner 打 USAGE」。
     */
    public static void command(String[] args){
        String[] raw = args == null ? new String[0] : args;
        String[] parts = String.join(" ", raw).replace('　', ' ').trim().split("\\s+");
        String sub = parts.length == 0 ? "" : parts[0];
        if(sub.isEmpty()){
            BlackstartMod.emit(USAGE);
            return;
        }
        if("lines".equals(sub)){
            if(parts.length == 1){
                BlackstartMod.emit("[bs] view lines=" + (preserveVanillaLines ? "preserve" : "suppress"));
            }else if(parts.length == 2 && ("suppress".equals(parts[1]) || "preserve".equals(parts[1]))){
                preserveVanillaLines = "preserve".equals(parts[1]);
                if(preserveVanillaLines) applyVanillaLinePolicy(false);
                persist();
                BlackstartMod.emit("[bs] view lines=" + parts[1]);
            }else BlackstartMod.emit(USAGE);
            return;
        }

        // 新子命令 island:长度 1 = 查询;长度 2 且取值 on/off = 设置;其余 ⇒ USAGE(零状态变更)。
        if("island".equals(sub)){
            if(parts.length == 1){
                BlackstartMod.emit("[bs] view island=" + islandOn);
                return;
            }
            if(parts.length == 2 && ("on".equals(parts[1]) || "off".equals(parts[1]))){
                islandOn = "on".equals(parts[1]);
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view island=" + islandOn);
                return;
            }
            BlackstartMod.emit(USAGE);
            return;
        }

        // I3 新增子命令 hover / badges:与 island 逐行同构(1 = 查询;2 且 on/off = 设置;其余 ⇒ USAGE)。
        if("hover".equals(sub) || "badges".equals(sub)){
            boolean isHover = "hover".equals(sub);
            if(parts.length == 1){
                BlackstartMod.emit("[bs] view " + sub + "=" + (isHover ? hoverOn : badgesOn));
                return;
            }
            if(parts.length == 2 && ("on".equals(parts[1]) || "off".equals(parts[1]))){
                boolean v = "on".equals(parts[1]);
                if(isHover) hoverOn = v; else badgesOn = v;
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view " + sub + "=" + (isHover ? hoverOn : badgesOn));
                return;
            }
            BlackstartMod.emit(USAGE);
            return;
        }

        // I5 新增子命令 events / others / lowfx:与前两批逐行同构(1 = 查询;2 且 on/off = 设置;其余 ⇒ USAGE)。
        if("events".equals(sub) || "others".equals(sub) || "lowfx".equals(sub)){
            boolean isEvents = "events".equals(sub);
            boolean isOthers = "others".equals(sub);
            if(parts.length == 1){
                BlackstartMod.emit("[bs] view " + sub + "=" + (isEvents ? eventsOn : (isOthers ? othersOn : lowfxOn)));
                return;
            }
            if(parts.length == 2 && ("on".equals(parts[1]) || "off".equals(parts[1]))){
                boolean v = "on".equals(parts[1]);
                if(isEvents) eventsOn = v; else if(isOthers) othersOn = v; else lowfxOn = v;
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view " + sub + "=" + (isEvents ? eventsOn : (isOthers ? othersOn : lowfxOn)));
                return;
            }
            BlackstartMod.emit(USAGE);
            return;
        }

        // I5 新增子命令 color:1 = 查询;2 且 0|1|2 = 设置(其余 ⇒ USAGE,零状态变更)。
        if("color".equals(sub)){
            if(parts.length == 1){
                BlackstartMod.emit("[bs] view color=" + colorMode);
                return;
            }
            if(parts.length == 2 && ("0".equals(parts[1]) || "1".equals(parts[1]) || "2".equals(parts[1]))){
                colorMode = Integer.parseInt(parts[1]);
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view color=" + colorMode);
                return;
            }
            BlackstartMod.emit(USAGE);
            return;
        }

        // I5 新增子命令 evpos:1 = 查询;2 且 0|1|2|3 = 设置(其余 ⇒ USAGE,零状态变更)。
        if("evpos".equals(sub)){
            if(parts.length == 1){
                BlackstartMod.emit("[bs] view evpos=" + eventPos);
                return;
            }
            if(parts.length == 2 && ("0".equals(parts[1]) || "1".equals(parts[1])
                || "2".equals(parts[1]) || "3".equals(parts[1]))){
                eventPos = Integer.parseInt(parts[1]);
                eventFree = false;                          // I6:命令 = 选择贴角,顺带复位自由位(与设置面板 cycler 同款)
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view evpos=" + eventPos);
                return;
            }
            BlackstartMod.emit(USAGE);
            return;
        }

        // I6 复核修复轮:新增子命令 setpos(无参)= 复位设置面板自由位,回「按钮行下方」默认锚位
        // (与 evpos 对事件面板的复位语义对称;纯复位,不设坐标)。
        if("setpos".equals(sub)){
            if(parts.length == 1){
                setPosFree = false;
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view setpos=reset");
                return;
            }
            BlackstartMod.emit(USAGE);
            return;
        }

        // I7 新增子命令 rowpos(无参)= 复位按钮行自由位,回「左上角」默认锚位
        // (与 setpos 对设置面板的复位语义对称;纯复位,不设坐标)。
        if("rowpos".equals(sub)){
            if(parts.length == 1){
                rowFree = false;
                rowArmed = false;
                rowDragging = false;
                rowSuppressClick = false;
                if(hudRow != null){
                    hudRow.setFillParent(true);        // 回钉左上(fillParent 布局在下次 validate 生效)
                    hudRow.setPosition(0f, 0f);        // I7.1:fillParent 只改尺寸不改位置 —— 不显式归零,行留在旧自由位,按钮飞出屏(复核 [0]/[5]/[12] P1)
                    hudRow.touchable = Touchable.childrenOnly;   // I7.1:回钉态禁整箱命中(自由态才 enabled,见 enterRowFree)
                    hudRow.invalidateHierarchy();
                }
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view rowpos=reset");
                return;
            }
            BlackstartMod.emit(USAGE);
            return;
        }

        // M7 新增子命令 readout / preview:与 hover/badges 逐行同构(1 = 查询;2 且 on/off = 设置;其余 ⇒ USAGE)。
        if("readout".equals(sub) || "preview".equals(sub)){
            boolean isReadout = "readout".equals(sub);
            if(parts.length == 1){
                BlackstartMod.emit("[bs] view " + sub + "=" + (isReadout ? readoutOn : previewOn));
                return;
            }
            if(parts.length == 2 && ("on".equals(parts[1]) || "off".equals(parts[1]))){
                boolean v = "on".equals(parts[1]);
                if(isReadout) readoutOn = v; else previewOn = v;
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view " + sub + "=" + (isReadout ? readoutOn : previewOn));
                return;
            }
            BlackstartMod.emit(USAGE);
            return;
        }

        // 原五子命令仅接受单 token(多 token ⇒ USAGE,零状态变更)。
        if(parts.length != 1){
            BlackstartMod.emit(USAGE);
            return;
        }

        switch(sub){
            case "on" -> {
                enabled = true;
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view on=" + enabled);
            }
            case "off" -> {
                enabled = false;
                applyVanillaLinePolicy(false);
                persist();
                BlackstartMod.emit("[bs] view on=" + enabled);
            }
            case "only" -> {
                enabled = true;
                mode = MODE_ONLY;
                persist();
                dirty = DIRTY_ALL;
                BlackstartMod.emit("[bs] view on=" + enabled + " only=" + (mode == MODE_ONLY));
            }
            case "rescan" -> {
                dirty = DIRTY_ALL;
                lastRebuildMs = 0L;       // 立即允许重建(下一帧)
                BlackstartMod.emit("[bs] view rescan");
            }
            case "debug" -> {
                BlackstartMod.emit(debugLine());        // 主行(前缀冻结 + 尾追 7 字段)
                BlackstartMod.emit(debugColorsLine());  // 第二行(colors;新冻结)
            }
            default -> BlackstartMod.emit(USAGE);
        }
        updateHudText();
    }

    /** 冻结主行(前缀字段顺序与拼写不许改;以后只许尾追)。I2 尾追 7 字段。 */
    private static String debugLine(){
        String head = "[bs] view on=" + enabled
            + " only=" + (mode == MODE_ONLY)
            + " gw=" + gwCount
            + " seg=" + segCount
            + " drawn=" + drawn
            + " rms=" + (int)rebuildRms
            + " mismatch=" + mismatch;

        int warn = 0, crit = 0, recov = 0, empty = 0;
        for(int i = 0; i < comps.length; i++){
            Comp c = comps[i];
            switch(c.state){
                case ST_WARN -> warn++;
                case ST_CRIT -> crit++;
                case ST_RECOV -> recov++;
                default -> { }
            }
            if(c.need <= 0f) empty++;
        }

        return head
            + " comp=" + comps.length
            + " warn=" + warn
            + " crit=" + crit
            + " recov=" + recov
            + " empty=" + empty
            + " island=" + islandOn
            + " ms=" + String.format(java.util.Locale.ROOT, "%.2f", msMeanMs())
            // I3 尾追 5 字段(既有字段段逐字节不变;新增只在 `ms=` 之后)
            + " hover=" + hoverOn
            + " badges=" + badgesOn
            + " hit=" + hoverHit
            + " starc=" + starc
            + " critc=" + critc
            // I5 尾追 6 字段(既有字段段逐字节不变;顺序照任务 §6-D4)
            + " events=" + eventsOn
            + " others=" + othersOn
            + " lowfx=" + lowfxOn
            + " color=" + colorMode
            + " evpos=" + eventPos
            + " seq=" + BlackstartEvents.seq()
            // M7 尾追 2 字段(既有字段段逐字节不变)
            + " readout=" + readoutOn
            + " preview=" + previewOn
            + " lines=" + (preserveVanillaLines ? "preserve" : "suppress");
    }

    /**
     * 冻结第二行(仅 debug):按 minPos 升序、最多 16 条,超出尾缀 ` ...`。
     * `<x>,<y>` = 稳定键格坐标;`<rrggbb>` 取法写死:String.format("%06x", c.rgba8888() >>> 8)(小写、无 #、无 alpha)。
     */
    private static String debugColorsLine(){
        StringBuilder sb = new StringBuilder("[bs] view colors n=").append(comps.length);
        int max = Math.min(comps.length, 16);
        for(int i = 0; i < max; i++){
            Comp c = comps[i];
            int pos = c.minPos;
            int rgb = (c.color == null ? 0 : c.color.rgba8888()) >>> 8;
            sb.append(" c").append(i).append('=')
                .append(Point2.x(pos)).append(',').append(Point2.y(pos)).append(':')
                .append(String.format(java.util.Locale.ROOT, "%06x", rgb));
        }
        if(comps.length > 16) sb.append(" ...");
        return sb.toString();
    }

    /** 偏好落盘(本机 Core.settings;不进存档、不进联机)。 */
    private static void persist(){
        if(Core.settings == null) return;
        Core.settings.put(KEY_ENABLED, enabled);
        Core.settings.putInt(KEY_MODE, mode);
        Core.settings.put(KEY_FOLLOW_LASER, followLaser);
        Core.settings.put(KEY_VANILLA_LINES, preserveVanillaLines ? "preserve" : "suppress");
        Core.settings.put(KEY_ISLAND, islandOn);
        Core.settings.put(KEY_HOVER, hoverOn);
        Core.settings.put(KEY_BADGES, badgesOn);
        Core.settings.putFloat(KEY_BADGE_SCALE, badgeScale);
        // I5 追加 5 键(折叠 memo bs-view-panel 在折叠回调处单独写,见事件面板)
        Core.settings.put(KEY_EVENTS, eventsOn);
        Core.settings.put(KEY_OTHERS, othersOn);
        Core.settings.put(KEY_LOWFX, lowfxOn);
        Core.settings.putInt(KEY_COLOR_MODE, colorMode);
        Core.settings.putInt(KEY_EVENT_POS, eventPos);
        // I6 追加 6 键:两面板自由位置(free + 左下角坐标;拖拽松手 / 位置 cycler 时落盘)
        Core.settings.put(KEY_SET_FREE, setPosFree);
        Core.settings.putFloat(KEY_SET_X, setPosX);
        Core.settings.putFloat(KEY_SET_Y, setPosY);
        Core.settings.put(KEY_EVENT_FREE, eventFree);
        Core.settings.putFloat(KEY_EVENT_X, eventFreeX);
        Core.settings.putFloat(KEY_EVENT_Y, eventFreeY);
        // I7 追加 3 键:按钮行自由位置(拖动松手 / rowpos 复位时落盘)
        Core.settings.put(KEY_ROW_FREE, rowFree);
        Core.settings.putFloat(KEY_ROW_X, rowFreeX);
        Core.settings.putFloat(KEY_ROW_Y, rowFreeY);
        // I8 追加 1 键:悬浮字号(滑条提交时落盘)
        Core.settings.putFloat(KEY_HOVER_SCALE, hoverScale);
        // M7 追加 2 键:同屏读数 / 建造预览(切换时落盘)
        Core.settings.put(KEY_READOUT, readoutOn);
        Core.settings.putFloat(KEY_READOUT_SCALE, readoutScale);
        Core.settings.put(KEY_PREVIEW, previewOn);
    }

    /**
     * HUD 按钮挂载。挂 Vars.ui.hudGroup:
     *   · headless / Vars.ui == null / hudGroup == null 一律早退;
     *   · 采用「register() 先试一次 + 首次 draw 惰性重试」方案 —— UI 可能晚于 mod init 就绪;
     *   · Table 默认 touchable=childrenOnly,全屏铺满不会吃掉世界点击。
     * I5:第一行追加「设置」按钮(session-only 展开态);事件面板另挂顶层子节点(头部④)。
     * I6:设置面板体也独立成顶层节点(留挂 table 第二行会被 fillParent 布局钉死,无法自由拖动);
     * 未拖过时由 updateSettingsPanelPos 每帧摆到按钮行下方 = 旧「第二行 colspan 2」的视觉位。
     * I7:行本体(原局部 table)升为静态 hudRow + RowDrag 监听 ⇒ 整行可拖;默认仍 fillParent 钉左上
     * (零视觉变化),拖过 / 读回偏好才转自由位(见 updateHudRow / RowDrag)。
     */
    private static void tryMountHud(){
        if(hudButton != null) return;
        if(Vars.headless || Vars.ui == null || Vars.ui.hudGroup == null) return;

        TextButton button = new TextButton(hudText(), Styles.defaultt);
        button.clicked(() -> { if(!rowSuppressClick) cycleMode(); });        // I7:拖过的按下吞点击

        TextButton openBtn = new TextButton(bundle("blackstart.panel.open", "设置"), Styles.defaultt);
        openBtn.clicked(() -> { if(!rowSuppressClick) settingsOpen = !settingsOpen; });

        hudRow = new Table();
        hudRow.setFillParent(true);
        hudRow.top().left();
        hudRow.margin(4f);
        hudRow.add(button).size(160f, 40f).pad(4f)
            .tooltip(bundle("blackstart.view.onlyMod.tip",
                "显示电网叠加层;设置中可选择压制或保留原版线,关闭即还原"));
        hudRow.add(openBtn).size(64f, 40f).pad(4f);
        hudRow.addListener(new RowDrag());          // I7:行拖动受理(arming;移动接管见 updateHudRow)

        Vars.ui.hudGroup.addChild(hudRow);

        // I7:上次会话拖过 ⇒ 直接落自由位(挂载点未跑布局也安全:pack 独立、坐标走存储值)
        if(rowFree) applyRowFreeStored();

        // I6:设置面板独立顶层节点 + 拖动监听(未拖过时由 updateSettingsPanelPos 每帧摆到按钮行下方)
        settingsBody = buildSettingsBody();
        settingsBody.pack();
        settingsBody.addListener(new PanelDrag(settingsBody, settingsBody, false));
        Vars.ui.hudGroup.addChild(settingsBody);

        hudButton = button;
        updateHudText();
        mountEventPanel();
        defenseRoot = new Table();
        // M4:横幅可点击 → 相机跳到当前显示岛锚(无头判空;显示槽越界静默)。
        defenseRoot.touchable = Touchable.enabled;
        defenseRoot.addListener(new ClickListener(){
            @Override
            public void clicked(InputEvent event, float x, float y){
                if(Vars.control == null || Vars.control.input == null) return;
                if(defenseShown < 0 || defenseShown >= defenseCount) return;
                Point2 p = Point2.unpack(defenseAnchor[defenseShown]);
                Vars.control.input.panCamera(new Vec2(p.x * Vars.tilesize, p.y * Vars.tilesize));
            }
        });
        defenseRoot.setBackground(Styles.black6);
        defenseRoot.visible = false;
        defenseLabel = new Label("", Styles.outlineLabel);
        defenseRoot.add(defenseLabel).pad(6f);
        Vars.ui.hudGroup.addChild(defenseRoot);
    }

    // ============================================================
    // I5 ④ 全设置面板(HUD 自绘;展开态 session-only)
    // ============================================================

    /** 构建设置面板体(17 行:9 toggle + 3 cycler + 2 滑条 + 3 入口按钮〔黑匣子/演习/区域调度〕;不含标题行);
     *  控件回调统一 persist + dirty(I8 悬浮字号滑条例外:只 persist —— 本机显示偏好,绘制侧每帧活读、无缓存需置脏)。 */
    private static Table buildSettingsBody(){
        Table t = new Table();
        t.setBackground(Styles.black6);
        t.touchable = Touchable.enabled;                    // D1:挡住穿点到世界
        t.visible = settingsOpen;

        t.add(new Label(bundle("blackstart.panel.title", "设置"), Styles.outlineLabel))
            .colspan(2).left().pad(4f);
        t.row();

        setViewBtn = new TextButton(stateText(), Styles.defaultt);
        setViewBtn.clicked(BlackstartView::cycleMode);      // 调现役 cycleMode(FULL→ONLY→OFF)
        settingsRow(t, "blackstart.panel.view", "电网视图", setViewBtn);

        TextButton blackboxButton = new TextButton(bundle("blackstart.blackbox.open", "Open"), Styles.defaultt);
        blackboxButton.clicked(BlackstartPanels::showBlackbox);
        settingsRow(t, "blackstart.blackbox.title", "Grid blackbox", blackboxButton);
        TextButton trainingButton = new TextButton(bundle("blackstart.blackbox.open", "Open"), Styles.defaultt);
        trainingButton.clicked(BlackstartPanels::showTraining);
        settingsRow(t, "blackstart.training.title", "Black-start exercise", trainingButton);
        TextButton fusionButton = new TextButton(bundle("blackstart.blackbox.open", "Open"), Styles.defaultt);
        fusionButton.clicked(BlackstartPanels::showFusion);
        settingsRow(t, "blackstart.panel.fusion", "Mixed-base drill", fusionButton);
        TextButton nativeFusionButton = new TextButton(bundle("blackstart.blackbox.open", "Open"), Styles.defaultt);
        nativeFusionButton.clicked(BlackstartPanels::showNativeFusion);
        settingsRow(t, "blackstart.nativeFusion.title", "Native fusion", nativeFusionButton);

        // M7 追加入口行:区域调度(区域覆盖便捷入口;与提示/预览同屏可见 = 同一设置面板内互见)
        TextButton zonesButton = new TextButton(bundle("blackstart.blackbox.open", "Open"), Styles.defaultt);
        zonesButton.clicked(BlackstartPanels::showZones);
        settingsRow(t, "blackstart.zones.title", "Zone dispatch", zonesButton);

        setIslandBtn = toggleButton(islandOn, () -> { islandOn = !islandOn; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.island", "孤岛高亮", setIslandBtn);
        setToggleBtns[0] = setIslandBtn;

        setBadgesBtn = toggleButton(badgesOn, () -> { badgesOn = !badgesOn; persist(); dirty = DIRTY_ALL; });
        settingsScaleRow(t, "blackstart.panel.badges", "方块角标", setBadgesBtn, true);
        setToggleBtns[1] = setBadgesBtn;

        setEventsBtn = toggleButton(eventsOn, () -> { eventsOn = !eventsOn; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.events", "事件流", setEventsBtn);
        setToggleBtns[2] = setEventsBtn;

        setHoverBtn = toggleButton(hoverOn, () -> { hoverOn = !hoverOn; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.hover", "悬浮读数", setHoverBtn);
        setToggleBtns[3] = setHoverBtn;

        // 第 6 行:悬浮字号滑条(1.5–5.0,步 0.25;紧贴悬浮读数 = 其可调参数)。本机显示偏好 ⇒ 从端同样可调、
        // 不置灰(与损耗滑条相反);无外部写者 ⇒ 无回读块。setValue 在 moved 挂载前:初始化零回调(同损耗行)。
        t.add(new Label(bundle("blackstart.panel.hoverScale", "悬浮字号"), Styles.outlineLabel)).left().pad(3f);
        lastHoverScaleSubmitted = hoverScale;
        hoverScaleSlider = new Slider(HOVER_SCALE_USER_MIN, HOVER_SCALE_USER_MAX, HOVER_SCALE_USER_STEP,
            false, Styles.defaultSlider);
        hoverScaleSlider.setValue(hoverScale);
        hoverScaleSlider.moved(BlackstartView::submitHoverScale);
        hoverScaleValueLabel = new Label(fmt2(hoverScale), Styles.outlineLabel);
        Table hoverScaleCell = new Table();
        hoverScaleCell.add(hoverScaleSlider).width(120f).padRight(4f);
        hoverScaleCell.add(hoverScaleValueLabel).width(48f).left();
        t.add(hoverScaleCell).left().pad(3f);
        t.row();

        TextButton flowButton = new TextButton(bundle("blackstart.flow.entry", "Adjust"), Styles.defaultt);
        flowButton.clicked(EngineeringFlowUi::show);
        settingsRow(t, "blackstart.flow.title", "Engineering line flow", flowButton);

        setOthersBtn = toggleButton(othersOn, () -> { othersOn = !othersOn; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.others", "显示他人电网", setOthersBtn);
        setToggleBtns[4] = setOthersBtn;

        setFollowBtn = toggleButton(followLaser, () -> { followLaser = !followLaser; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.followLaser", "跟随激光淡出", setFollowBtn);
        setToggleBtns[5] = setFollowBtn;
        setVanillaLinesBtn = new TextButton(vanillaLinesText(), Styles.defaultt);
        setVanillaLinesBtn.clicked(() -> {
            preserveVanillaLines = !preserveVanillaLines;
            if(preserveVanillaLines) applyVanillaLinePolicy(false);
            persist();
        });
        settingsRow(t, "blackstart.panel.vanillaLines", "Native power lines", setVanillaLinesBtn);

        setLowfxBtn = toggleButton(lowfxOn, () -> { lowfxOn = !lowfxOn; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.lowfx", "低配模式", setLowfxBtn);
        setToggleBtns[6] = setLowfxBtn;

        // M7 追加 2 行 toggle(本机显示偏好,与角标/悬浮同构)
        setReadoutBtn = toggleButton(readoutOn, () -> { readoutOn = !readoutOn; persist(); dirty = DIRTY_ALL; });
        settingsScaleRow(t, "blackstart.panel.readout", "同屏读数", setReadoutBtn, false);
        setToggleBtns[7] = setReadoutBtn;

        setPreviewBtn = toggleButton(previewOn, () -> { previewOn = !previewOn; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.preview", "建造预览", setPreviewBtn);
        setToggleBtns[8] = setPreviewBtn;

        // M4 追加 1 行 toggle:调试提示浮字开关。状态源 = Core.settings(bs-view-debug-toasts,归 BlackstartMod):
        // 只过滤诊断面浮字(emit),玩家面浮字(emitPlayer)不受门控;不涉绘制 ⇒ 不置脏。
        setDebugToastsBtn = toggleButton(BlackstartMod.debugToasts(), () -> BlackstartMod.setDebugToasts(!BlackstartMod.debugToasts()));
        settingsRow(t, "blackstart.panel.debugToasts", "调试提示", setDebugToastsBtn);
        setToggleBtns[9] = setDebugToastsBtn;

        setColorBtn = new TextButton(colorModeText(), Styles.defaultt);
        setColorBtn.clicked(() -> { colorMode = (colorMode + 1) % 3; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.colorMode", "配色", setColorBtn);

        setPosBtn = new TextButton(eventPosText(), Styles.defaultt);
        // I6:顺延四角;顺手清自由位 —— 拖过之后的复位入口(点一下即回贴角模式)
        setPosBtn.clicked(() -> { eventFree = false; eventPos = (eventPos + 1) % 4; persist(); dirty = DIRTY_ALL; });
        settingsRow(t, "blackstart.panel.eventPos", "事件位置", setPosBtn);

        // 第 12 行:损耗滑条(0–4%,步 0.5;写 BlackstartParams 唯一门)+ 值 Label(D3)
        t.add(new Label(bundle("blackstart.panel.loss", "链路损耗"), Styles.outlineLabel)).left().pad(3f);
        float v0 = Mathf.clamp(BlackstartParams.getFloat(BlackstartParams.KEY_LOSS,
            BlackstartParams.DEFAULT_LOSS) * 100f, 0f, 4f);
        lastLossSubmitted = v0;
        lossLabelShown = v0;
        lossSlider = new Slider(0f, 4f, 0.5f, false, Styles.defaultSlider);
        lossSlider.setValue(v0);
        lossSlider.moved(BlackstartView::submitLoss);
        lossValueLabel = new Label(fmtLossPct(v0), Styles.outlineLabel);
        Table lossCell = new Table();
        if(Vars.net != null && Vars.net.client()){          // 从端置灰(读值照显;D3)
            lossSlider.touchable = Touchable.disabled;
            lossCell.add(lossSlider).width(120f).padRight(4f)
                .tooltip(bundle("blackstart.panel.loss.hostOnly", "由房主设置"));
        }else{
            lossCell.add(lossSlider).width(120f).padRight(4f);
        }
        lossCell.add(lossValueLabel).width(48f).left();
        t.add(lossCell).left().pad(3f);
        t.row();

        return t;
    }

    /** 设置行(左 Label + 右控件)。 */
    private static void settingsRow(Table t, String labelKey, String labelFallback, TextButton btn){
        t.add(new Label(bundle(labelKey, labelFallback), Styles.outlineLabel)).left().pad(3f);
        t.add(btn).size(96f, 32f).left().pad(3f);
        t.row();
    }

    /** 原开关同行加独立字号滑条,不增加设置面板高度。客户端同样可以调本机显示。 */
    private static void settingsScaleRow(Table t, String key, String fallback, TextButton toggle, boolean badge){
        float initial = badge ? badgeScale : readoutScale;
        Slider slider = new Slider(LABEL_SCALE_MIN, LABEL_SCALE_MAX, LABEL_SCALE_STEP, false, Styles.defaultSlider);
        slider.setValue(initial);
        Label valueLabel = new Label(labelScaleText(initial), Styles.outlineLabel);
        slider.moved(value -> {
            float clean = sanitizeLabelScale(value);
            float current = badge ? badgeScale : readoutScale;
            if(Math.abs(clean - current) < LABEL_SCALE_STEP * 0.5f) return;
            if(badge) badgeScale = clean; else readoutScale = clean;
            persist();
            valueLabel.setText(labelScaleText(clean));
        });
        Table controls = new Table();
        controls.add(toggle).size(48f, 32f).padRight(6f);
        controls.add(slider).width(100f).padRight(4f).tooltip(bundle(
            badge ? "blackstart.panel.badgeScale" : "blackstart.panel.readoutScale",
            badge ? "角标字号" : "同屏读数字号"));
        controls.add(valueLabel).width(48f).left();
        t.add(new Label(bundle(key, fallback), Styles.outlineLabel)).left().pad(3f);
        t.add(controls).left().pad(3f);
        t.row();
    }

    private static float sanitizeLabelScale(float value){
        if(!Float.isFinite(value)) return LABEL_SCALE_DEFAULT;
        float bounded = Mathf.clamp(value, LABEL_SCALE_MIN, LABEL_SCALE_MAX);
        return LABEL_SCALE_MIN + Math.round((bounded - LABEL_SCALE_MIN) / LABEL_SCALE_STEP) * LABEL_SCALE_STEP;
    }

    private static String labelScaleText(float value){
        return Math.round(value * 100f) + "%";
    }

    /** 先保留旧字号地板再乘倍率:默认在所有 zoom 都放大 25%,不在滑条后再次 clamp。 */
    private static float labelTextScale(float ds, float multiplier){
        return Mathf.clamp(BADGE_TEXT_SCALE_BASE / ds, HOVER_SCALE_MIN, HOVER_SCALE_MAX) * multiplier;
    }

    /** toggle 控件(D2:一律 TextButton 文本开/关,不用 Toggle / CheckBox)。 */
    private static TextButton toggleButton(boolean initial, Runnable onClick){
        TextButton b = new TextButton(initial ? bundle("blackstart.panel.on", "开")
            : bundle("blackstart.panel.off", "关"), Styles.defaultt);
        b.clicked(onClick);
        return b;
    }

    /** 损耗滑条提交(值已被步进吸附):写唯一门 + 入环 PARAM + 刷值 Label。程序性同步不回此。 */
    private static void submitLoss(float v){
        if(lossSyncing) return;                              // 外部值回读触发的 setValue:非用户输入,不写不产
        if(Math.abs(v - lastLossSubmitted) < 0.05f) return;
        lastLossSubmitted = v;
        BlackstartParams.set(BlackstartParams.KEY_LOSS, String.valueOf(v / 100f));
        BlackstartEvents.push(BlackstartEvents.Kind.PARAM, -1, -1, 0, v);
        lossLabelShown = v;
        if(lossValueLabel != null) lossValueLabel.setText(fmtLossPct(v));
    }

    /** 悬浮字号滑条提交(值已被步进吸附):只更新本机显示偏好 + 落盘 + 刷值 Label。
     *  不 push 事件、不置脏(绘制 drawHoverPanel 每帧活读 hoverScale,无缓存要刷);无外部写者 ⇒ 无回读。 */
    private static void submitHoverScale(float v){
        if(Math.abs(v - lastHoverScaleSubmitted) < HOVER_SCALE_USER_STEP * 0.5f) return;
        lastHoverScaleSubmitted = v;
        hoverScale = v;
        persist();
        if(hoverScaleValueLabel != null) hoverScaleValueLabel.setText(fmt2(v));
    }

    /** 设置面板每帧驱动(不随总开关早退;外部命令改设置后面板立即跟手)。 */
    private static void updateSettingsPanel(){
        if(settingsBody == null) return;
        if(settingsBody.visible != settingsOpen) settingsBody.visible = settingsOpen;
        if(!settingsOpen) return;
        updateSettingsPanelPos();                            // I6:每帧跟位(屏变跟随 / 自由位 clamp)

        if(setViewBtn != null){
            int k = (enabled ? 2 : 0) | (mode == MODE_ONLY ? 1 : 0);
            if(k != setViewKey){ setViewKey = k; setViewBtn.setText(stateText()); }
        }
        refreshToggle(0, islandOn);
        refreshToggle(1, badgesOn);
        refreshToggle(2, eventsOn);
        refreshToggle(3, hoverOn);
        refreshToggle(4, othersOn);
        refreshToggle(5, followLaser);
        refreshToggle(6, lowfxOn);
        // M7:同屏读数 / 建造预览
        refreshToggle(7, readoutOn);
        refreshToggle(8, previewOn);
        // M4:调试提示(状态源 Core.settings;面板每帧回写 ⇒ 外部改动立即跟手)
        refreshToggle(9, BlackstartMod.debugToasts());
        String vanillaText = vanillaLinesText();
        if(setVanillaLinesBtn != null && !vanillaText.equals(setVanillaLinesText)){
            setVanillaLinesText = vanillaText;
            setVanillaLinesBtn.setText(vanillaText);
        }

        if(setColorBtn != null && setColorKey != colorMode){
            setColorKey = colorMode;
            setColorBtn.setText(colorModeText());
        }
        int posKeyWant = eventPos | (eventFree ? 8 : 0);     // I6:自由位并入 key(拖完按钮文本立刻变「自由」)
        if(setPosBtn != null && setPosKey != posKeyWant){
            setPosKey = posKeyWant;
            setPosBtn.setText(eventPosText());
        }

        // 损耗:外部(命令 / 其它端)改动回读;拖拽中不回写滑条,防抢手
        if(lossSlider != null){
            float cur = Mathf.clamp(BlackstartParams.getFloat(BlackstartParams.KEY_LOSS,
                BlackstartParams.DEFAULT_LOSS) * 100f, 0f, 4f);
            if(Math.abs(cur - lossLabelShown) >= 0.05f){
                lossLabelShown = cur;
                if(lossValueLabel != null) lossValueLabel.setText(fmtLossPct(cur));
            }
            if(!lossSlider.isDragging() && Math.abs(cur - lossSlider.getValue()) >= 0.05f){
                lastLossSubmitted = cur;
                lossSyncing = true;                          // 抑制 setValue 内同步触发的 moved 回调
                lossSlider.setValue(cur);
                lossSyncing = false;
            }
            boolean isClient = Vars.net != null && Vars.net.client();   // 角色可能中途变化:每帧廉价同步
            Touchable want = isClient ? Touchable.disabled : Touchable.enabled;
            if(lossSlider.touchable != want) lossSlider.touchable = want;
        }
    }

    /** toggle 文本 compare-and-set(状态不变不重建字符串)。 */
    private static void refreshToggle(int idx, boolean v){
        TextButton b = setToggleBtns[idx];
        if(b == null || setToggleState[idx] == v) return;
        setToggleState[idx] = v;
        b.setText(v ? bundle("blackstart.panel.on", "开") : bundle("blackstart.panel.off", "关"));
    }

    /** 配色模式词(`blackstart.panel.color.mix/load/band`)。 */
    private static String colorModeText(){
        if(colorMode == 1) return bundle("blackstart.panel.color.load", "纯负载");
        if(colorMode == 2) return bundle("blackstart.panel.color.band", "纯分色");
        return bundle("blackstart.panel.color.mix", "混合");
    }

    private static String vanillaLinesText(){
        return preserveVanillaLines ? bundle("blackstart.view.lines.preserve", "Preserve")
            : bundle("blackstart.view.lines.suppress", "Suppress");
    }

    /** Only the historical suppress mode draws our replacement native line layer. */
    private static boolean redrawVanillaLines(){
        return !preserveVanillaLines;
    }

    /** Own only the transient renderer override. Never write the engine preference. */
    private static void applyVanillaLinePolicy(boolean active){
        if(active && !preserveVanillaLines){
            if(!laserOverridden) savedLaserOpacity = Renderer.laserOpacity;
            laserOverridden = true;
            Renderer.laserOpacity = 0f;
        }else if(laserOverridden){
            // Renderer.update reads lasersopacity/100 each frame. Honor slider changes made while suppressed.
            Renderer.laserOpacity = Core.settings != null && Core.settings.has("lasersopacity")
                ? Core.settings.getInt("lasersopacity") / 100f : savedLaserOpacity;
            laserOverridden = false;
        }
    }

    /** 事件位置词(`blackstart.panel.pos.br/bl/tr/tl`;I6:拖过 = 「自由」)。 */
    private static String eventPosText(){
        if(eventFree) return bundle("blackstart.panel.pos.free", "自由");
        switch(eventPos){
            case 1: return bundle("blackstart.panel.pos.bl", "左下");
            case 2: return bundle("blackstart.panel.pos.tr", "右上");
            case 3: return bundle("blackstart.panel.pos.tl", "左上");
            default: return bundle("blackstart.panel.pos.br", "右下");
        }
    }

    /** 损耗百分数文本(ROOT locale;0–4 保留一位)。 */
    private static String fmtLossPct(float v){
        return String.format(java.util.Locale.ROOT, "%.1f%%", v);
    }

    // ============================================================
    // I5 ⑨ 事件面板(HUD 自绘;全批唯一新挂 hudGroup 顶层节点)
    // ============================================================

    /** 事件面板挂载(照 tryMountHud 的惰性模式;headless / 无 UI 硬早退)。I6:自坐标浮动窗(去 fillParent)。 */
    private static void mountEventPanel(){
        if(eventRoot != null) return;
        if(Vars.headless || Vars.ui == null || Vars.ui.hudGroup == null) return;

        eventRoot = new Table();
        eventRoot.touchable = Touchable.childrenOnly;        // 不挡世界操作(C1);I6:无 fillParent,尺寸 = 内容
        eventRoot.visible = false;                           // 首帧由 updateEventPanel 点亮

        eventBox = new Table();
        eventBox.setBackground(Styles.black6);
        eventBox.touchable = Touchable.enabled;              // I6:整箱可拖(监听挂箱体;文字/色块经命中链上溯也算箱体面)
        eventBox.addListener(new PanelDrag(eventBox, eventRoot, true));

        eventFoldButton = new TextButton(bundle("blackstart.events.folded", "事件"), Styles.defaultt);
        eventFoldButton.clicked(BlackstartView::togglePanelFold);
        eventRoot.update(BlackstartView::updateEventPanel);

        layoutEventRoot();                                   // I6:按折叠态组树(折叠 = box 退树 ⇒ 尺寸真收缩)
        Vars.ui.hudGroup.addChild(eventRoot);                // 头部④:唯一新挂顶层子节点
        updateEventPanelPos();                               // I6:首帧即摆到目标位(替代 applyEventPos 对齐法)
        lastSeq = BlackstartEvents.seq();                    // 挂载前的历史不算新条目
    }

    /** 折叠切换 + memo(默认展开;memo 键 bs-view-panel)。I6:折叠 = 重组子级树(体积真收缩)+ 立即跟位。 */
    private static void togglePanelFold(){
        panelFolded = !panelFolded;
        if(Core.settings != null) Core.settings.put(KEY_PANEL, !panelFolded);
        layoutEventRoot();
        updateEventPanelPos();
    }

    /**
     * I6:按折叠态重组 eventRoot 子级。展开 = box + 折叠按钮;折叠 = 仅按钮(尺寸真实收缩,
     * 不靠 visible=false 占位)。置 eventSizeDirty ⇒ 下次跟位前 pack。
     */
    private static void layoutEventRoot(){
        if(eventRoot == null) return;
        updateEventDimensions();
        boolean structureChanged=eventFoldButton.parent!=eventRoot
            || (panelFolded ? eventBox.parent==eventRoot : eventBox.parent!=eventRoot);
        if(structureChanged){
            eventRoot.clearChildren();
            if(!panelFolded){eventRoot.add(eventBox);eventRoot.row();}
            eventRoot.add(eventFoldButton);
        }
        if(!panelFolded){
            eventRoot.getCell(eventBox).minSize(0).width(ResponsiveUi.units(eventWidth)).left().pad(ResponsiveUi.units(eventPad));
        }
        updateEventFoldLabel();
        eventRoot.getCell(eventFoldButton).minSize(0).size(ResponsiveUi.units(Math.min(Scl.scl(EVENT_FOLD_W),eventWidth)), ResponsiveUi.units(eventFoldHeight)).left().pad(ResponsiveUi.units(eventPad));
        eventRoot.invalidateHierarchy();
        eventSizeDirty = true;
    }

    private static void updateEventFoldLabel(){
        if(eventFoldButton==null)return;
        int pending=0;for(Line line:eventQueue)if(line.notice)pending++;
        String word=bundle("blackstart.events.folded","事件");
        Font font=eventFoldButton.getLabel().getStyle().font;
        float foldWidth=Math.min(Scl.scl(EVENT_FOLD_W),eventWidth);
        if(measuredFoldButton==eventFoldButton&&measuredNoticeCount==pending&&word.equals(measuredFoldWord)
            &&measuredFoldWidth==foldWidth&&measuredFoldHeight==eventFoldHeight
            &&measuredFoldFontX==font.getData().scaleX&&measuredFoldFontY==font.getData().scaleY)return;
        measuredFoldButton=eventFoldButton;measuredNoticeCount=pending;measuredFoldWord=word;
        measuredFoldWidth=foldWidth;measuredFoldHeight=eventFoldHeight;
        measuredFoldFontX=font.getData().scaleX;measuredFoldFontY=font.getData().scaleY;
        String label=word+(pending==0?"":" ("+pending+")");
        if(!eventFoldButton.getLabel().textEquals(label))eventFoldButton.setText(label);
        eventFoldButton.margin(0);for(var cell:eventFoldButton.getCells())cell.minSize(0).pad(0);
        GlyphLayout measure=new GlyphLayout();measure.setText(font,label);
        eventFoldButton.getLabel().setFontScale(Math.min(1f,Math.min(eventFoldHeight/Math.max(1f,font.getLineHeight()+2f),foldWidth/Math.max(1f,measure.width))));
    }

    private static void updateEventDimensions(){
        float budget=Math.max(1f,panelAreaH()*.25f);
        float margin=Math.min(Scl.scl(EVENT_PANEL_MARGIN),budget*.04f);
        eventBudget=Math.max(0f,budget-margin*2);
        eventPad=Math.min(Scl.scl(2f),eventBudget*.02f);
        eventWidth=Math.max(1f,Math.min(Scl.scl(360f),panelAreaW()-margin*2-eventPad*2));
        eventFoldHeight=Math.min(Scl.scl(EVENT_FOLD_H),eventBudget*.25f);
    }

    /**
     * I6:事件面板跟位(clamp 到可用区内;坐标 = 面板左下角,UI 像素,左下原点)。
     * 非自由(未拖过):按 eventPos 四角贴角(EVENT_PANEL_MARGIN,经 Scl 与旧 margin(4f) 缩放等价);
     * Bottom anchors use the top of the lower quarter. Free placement preserves its current top as rows change.
     * 尺寸脏时先 pack(行数 / 折叠变化后,低频);坐标变化才 setPosition(避免每帧重排)。
     */
    private static void updateEventPanelPos(){
        if(eventRoot == null) return;
        if(eventAreaWidth!=panelAreaW()||eventAreaHeight!=panelAreaH()||eventUiScale!=Scl.scl(1f)){
            eventAreaWidth=panelAreaW();eventAreaHeight=panelAreaH();eventUiScale=Scl.scl(1f);
            rebuildEventRows();layoutEventRoot();
        }
        if(eventSizeDirty){
            eventRoot.pack();
            eventSizeDirty = false;
        }
        float w = eventRoot.getWidth(), h = eventRoot.getHeight();
        float W = panelAreaW(), H = panelAreaH();
        float m = Math.min(Scl.scl(EVENT_PANEL_MARGIN),panelAreaH()*.01f);
        float px = m, py = m;
        if(eventFree){
            px = eventFreeX;
            if(!Float.isFinite(eventFreeTop))eventFreeTop=eventFreeY+h;
            py = eventFreeTop-h;
        }else switch(eventPos){
            case 1 -> { px = m; py = m+eventBudget-h; }      // Lower quarter, fixed top anchor.
            case 2 -> { px = W - w - m; py = H - h - m; }    // 右上
            case 3 -> { px = m; py = H - h - m; }            // 左上
            default -> { px = W - w - m; py = m+eventBudget-h; }
        }
        px = clampPanelX(px, w, W);
        py = clampPanelY(py, h, H);
        if(eventFree){eventFreeTop=py+h;eventFreeY=py;}else eventFreeTop=Float.NaN;
        if(eventRoot.x != px || eventRoot.y != py) eventRoot.setPosition(px, py);
    }

    // ---- I6 浮动面板共用(设置 / 事件:跟位 / 拖动)----

    /**
     * I6:设置面板跟位(clamp 到可用区内;坐标同上)。
     * 非自由:按钮行下方默认位(SET_PANEL_DEFAULT_X / 自顶 SET_PANEL_DEFAULT_TOP,经 Scl 与旧
     * 「表格第二行」在任意 UI 比例下同位 ⇒ 屏变自动跟随);自由:保持 setPosX/Y。
     * I7:行已自由(rowFree)时,默认位改为跟行——左缘对齐按钮左缘(rowFreeX + hudButton.x)、
     * 紧贴行底(rowFreeY − h),与钉左上时横竖关系同构(8 单位视觉缝一致);否则行拖走后
     * 按「设置」,面板仍在左上旧位弹出。rowpos 复位/面板自身拖过(sPosFree)都会回到各自分支。
     * I7.1:行拖到低位(rowFreeY < h)时「行底」为负 ⇒ clamp 压到 0,面板整块压住按钮行,且面板
     * 后挂先命中 ⇒ 行不可拖、按钮不可点(复核 [1] 确证)⇒ 改为翻到行上方;上方也放不下(矮屏)
     * 才退回保底贴顶(在案行为)。
     * 尺寸静态(无动态内容),不需要 pack。
     * 已知项(复核在案,物理无解):可用区高 H 不足(高 uiscale + 矮窗口,H≲916@200%)时
     * clampPanelY 下界压到 0 ⇒ py=0、面板顶越过按钮行并向上裁切——放不下就是放不下,取
     * 「保底下部、顶部裁切」;面板仍可横拖让开按钮行自救(非锁死)。
     */
    private static void updateSettingsPanelPos(){
        if(settingsBody == null) return;
        float w = settingsBody.getWidth(), h = settingsBody.getHeight();
        float W = panelAreaW(), H = panelAreaH();
        float px, py;
        if(setPosFree){
            px = setPosX;
            py = setPosY;
        }else if(rowFree && hudButton != null){
            px = rowFreeX + hudButton.x;                     // I7:左缘对齐按钮左缘(活体值,免 Scl 失配)
            py = rowFreeY - h;                               // I7:紧贴行底(与钉左上默认位同构)
            if(py < 0f){                                     // I7.1:行拖到低位、下方放不下 ⇒ 翻到行上方(防盖行,复核 [1])
                float above = rowFreeY + hudRow.getHeight(); // 行高:自由态 = pack 后活体高(≈56)
                py = (above + h <= H) ? above : Math.max(0f, H - h); // 上方也放不下(矮屏)⇒ 保底贴顶(在案行为)
            }
        }else{
            px = Scl.scl(SET_PANEL_DEFAULT_X);
            py = H - Scl.scl(SET_PANEL_DEFAULT_TOP) - h;
        }
        px = clampPanelX(px, w, W);
        py = clampPanelY(py, h, H);
        if(settingsBody.x != px || settingsBody.y != py) settingsBody.setPosition(px, py);
    }

    /**
     * I6:拖动落点(isEvent ? 事件面板 : 设置面板;置 free + 存坐标 + 立即跟位)。
     * 入库前先 clamp(复核修复轮):存「显示值」而非原始指针值 —— 否则顶出屏的越界量被记住,
     * 回拉出现「等于越界量」的死区,且换分辨率/窗口变大后浮窗跳到用户从未见过的位置。
     */
    private static void dragPanel(boolean isEvent, float x, float y){
        if(isEvent){
            eventFree = true;
            eventFreeX = clampPanelX(x, eventRoot.getWidth(), panelAreaW());
            eventFreeY = clampPanelY(y, eventRoot.getHeight(), panelAreaH());
            eventFreeTop=eventFreeY+eventRoot.getHeight();
            updateEventPanelPos();
        }else{
            setPosFree = true;
            setPosX = clampPanelX(x, settingsBody.getWidth(), panelAreaW());
            setPosY = clampPanelY(y, settingsBody.getHeight(), panelAreaH());
            updateSettingsPanelPos();
        }
    }

    /** I6:x 向 clamp(面板永不丢出屏;屏比面板小时贴 0)。 */
    private static float clampPanelX(float v, float w, float screenW){
        return Mathf.clamp(v, 0f, Math.max(0f, screenW - w));
    }

    /** I6:y 向 clamp(同上)。 */
    private static float clampPanelY(float v, float h, float screenH){
        return Mathf.clamp(v, 0f, Math.max(0f, screenH - h));
    }

    /**
     * I6:浮窗可用区宽。hudGroup 为 Scene.root 的 fillParent 子节点,其尺寸即「可用区」——
     * 已扣 Scene 边距(安全区 insets + UI 边缘留白 uiEdgePadding);浮窗 x/y 与它同坐标系,直接用作
     * 贴角 / clamp 的两端(hudGroup 略小于整屏时,贴角像素差即边距,与旧 fillParent+margin 行为一致)。
     * 首次布局前(=0)回退整屏 − Scene 边距。
     */
    private static float panelAreaW(){
        if(Vars.ui == null || Vars.ui.hudGroup == null) return Core.graphics.getWidth();
        float w = Vars.ui.hudGroup.getWidth();
        if(w > 0f) return w;
        return Core.graphics.getWidth() - Core.scene.marginLeft - Core.scene.marginRight;
    }

    /** I6:浮窗可用区高(同上)。 */
    private static float panelAreaH(){
        if(Vars.ui == null || Vars.ui.hudGroup == null) return Core.graphics.getHeight();
        float h = Vars.ui.hudGroup.getHeight();
        if(h > 0f) return h;
        return Core.graphics.getHeight() - Core.scene.marginTop - Core.scene.marginBottom;
    }

    /**
     * I6:浮动面板拖动(设置 / 事件共用)。仅左键 + 仅主触点(桌面 = 鼠标;多指不参与,状态不被搅);
     * 按下后位移和 > PANEL_DRAG_THRESHOLD 才开拖(防点按钮手抖误拖)。松手才 persist(零磁盘 IO)。
     * 命中与坐标语义(arc 字节码实证,两处曾踩坑):
     *   · Element 默认 touchable=enabled 且 Label / Image 未覆写 ⇒ 行内文字 / 色块也是合法命中目标,
     *     「targetActor == 面板本体」过滤会把大半个面板变成拖动死区;
     *   · 故改为沿命中链上溯:e.targetActor 起、抵达 hitTarget 前任一环带输入监听者(按钮 / 滑条等
     *     交互控件)⇒ 拒绝;一路无监听直抵 hitTarget(文字、色块、箱体空白)⇒ 受理。touchDown 冒泡
     *     不因 handled 而止(只认 event.stop()),故控件上的按下也会抵达本监听 —— 该上溯即为其过滤器。
     *   · 基线取 posRoot(positioning root)的 x/y:落点写回的必须是 posRoot 在 hudGroup 系的坐标,
     *     而 hitTarget 若是 posRoot 的子级(事件面板 eventBox ⊂ eventRoot),其 x/y 是父内局部值,不可混用;
     *   · touchDragged / touchUp 由 Scene 按 touch focus 直投监听者(不走 fire,无冒泡)⇒ 只有自家
     *     受理过的按下才会引来回调;armed 只覆盖游戏内取消路径(clearChildren / cancelTouchFocus 会
     *     补发合成 touchUp,已实证)。**已知缺口(复核在案,低概率、无干净修法)**:窗口失焦且系统吞掉
     *     抬起(SDL2 的 Alt-Tab 持键往返路径)时 Scene 无失焦清理、focus 残留 ⇒ 此后仅移动鼠标会
     *     继续拖动,直到下一次点击抬起自愈。
     */
    private static class PanelDrag extends InputListener{
        private final Element hitTarget;                 // 受理命中根(设置 = settingsBody;事件 = eventBox)
        private final Element posRoot;                   // 被定位元素(落点写回其坐标系;事件 = eventRoot)
        private final boolean isEvent;
        private float downX, downY, startX, startY, startTop;
        private boolean armed;                           // 已受理的自家按下(主触点)按住中
        private boolean dragging;

        PanelDrag(Element hitTarget, Element posRoot, boolean isEvent){
            this.hitTarget = hitTarget;
            this.posRoot = posRoot;
            this.isEvent = isEvent;
        }

        @Override
        public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode button){
            if(button != KeyCode.mouseLeft) return false;
            if(pointer != 0) return false;
            armed = false;                               // 任何未被受理的按下 = 状态自清(防 stale)
            dragging = false;
            if(e.targetActor == null) return false;
            // 命中链上溯:抵达 hitTarget 前遇到交互控件(带监听者)⇒ 拒绝;一路被动(文字/色块/空白)⇒ 受理
            Element el = e.targetActor;
            while(el != null && el != hitTarget){
                if(el.getListeners().size > 0 || el.getCaptureListeners().size > 0) return false;
                el = el.parent;
            }
            if(el != hitTarget) return false;
            armed = true;
            downX = e.stageX;
            downY = e.stageY;
            startX = posRoot.x;
            startY = posRoot.y;
            startTop = posRoot.getTop();
            return true;                                 // 捕获 touch focus:拖动 / 松手都回到本监听
        }

        @Override
        public void touchDragged(InputEvent e, float x, float y, int pointer){
            if(!armed || pointer != 0) return;           // 只认自家按下引出的拖动(外来冒泡忽略)
            if(!dragging){
                if(Math.abs(e.stageX - downX) + Math.abs(e.stageY - downY) <= PANEL_DRAG_THRESHOLD) return;
                dragging = true;
            }
            float panelY=isEvent ? startTop+(e.stageY-downY)-posRoot.getHeight() : startY+(e.stageY-downY);
            dragPanel(isEvent, startX + (e.stageX - downX), panelY);
        }

        @Override
        public void touchUp(InputEvent e, float x, float y, int pointer, KeyCode button){
            if(!armed || pointer != 0) return;
            if(dragging) persist();
            dragging = false;
            armed = false;
        }
    }

    // ============================================================
    // I7 按钮行拖动(事件 arming + 每帧轮询驱动;设计取舍见 RowDrag)
    // ============================================================

    /**
     * 每帧轮询(挂 draw() 2.5 段,与设置面板同区:不随总开关早退)。状态机:
     *   armed & 按下 → max(|dx|,|dy|) > ROW_DRAG_THRESHOLD(14;自选参数,订正见常数注释)⇒ 接管(开拖 + 吞点击 + 首拖转自由态);
     *   armed & 抬起 → 落手:拖过才 persist(拖动期零磁盘 IO);
     *   非 armed & 自由态 → 跟位 clamp(窗口尺寸变化时保显示内;镜像 updateSettingsPanelPos)。
     * 坐标:按下点 = touchDown 的 e.stageX/e.stageY(拖动期在激活帧被重锚为当时轮询点,见激活分支);轮询点 = screenToStageCoordinates(mouseX(), mouseY())
     * —— 与 Scene 把原始事件转成 stage 坐标走的是同一条 unproject 管线(字节码实证,Scene 六处调用点),
     * 增量按构造精确,与 I6 面板拖动(已实证跟手)完全同系;不依赖 touch focus 直投(touchDown 返
     * false 不夺焦,拿不到 touchDragged/touchUp)。
     * ⚠ 已知缺口(与 PanelDrag U3 同类,在案):系统吞掉抬起(SDL2 Alt-Tab 持键往返)时轮询仍见按下
     * ⇒ 行暂随鼠标;自愈于下一次点击(新 touchDown 复位状态)。
     * I7.1b 复核终审(替 I7.1 原「同属该缺口,不另修」粗注,逐条登记):
     *   · [2]/M03(回窗首击未落行 ⇒ 陈旧按下点算 dx ⇒ 行整段瞬移,随后一次落盘写错位):M03 终审
     *     【可达,P2】——本版已修「激活瞬移」(见激活分支:开拖时重锚按下点,v2 勘正读取时序);
     *     残余「按住漂移期行随光标 + 一次错误落盘」可见、可自愈(再拖/rowpos 纠正),登记搁置。
     *     另 [2] 主诉「本意点世界的点击恰落到行按钮上 ⇒ 真触发 cycleMode/面板开合 + 落盘」:
     *     该路径位移 ≤14px 不激活、touchDown 反清 suppress ⇒ 点击照常生效,属既有语义,登记不修。
     *   · [3](钉态 8px 缝死区+按下穿世界,普通点击即触发,与吞抬起无关):F4 只覆盖自由态;钉态
     *     不能整箱 enabled(行 fillParent=整屏箱体会吞全屏世界点击);根治 = 钉态改 pack+每帧钉位
     *     (复核建议列为下一轮取舍项),本版不动。
     *   · [9](位移+抬起挤进同一轮询帧间隙 ⇒ 拖动被执行成点按)/[10](残旗落盘):F3 已把可达面从
     *     >6px 收窄到 >14px;未修,INFO 搁置。
     *   · F1 尾注(低危):拖动进行中下发 rowpos 会清 rowSuppressClick——若松手时指针恰落回位后的
     *     按钮上,可补一次 clicked(一次模式切换/面板开合,自愈);登记不修。
     */
    private static void updateHudRow(){
        if(Vars.headless || hudRow == null) return;
        boolean down = Core.input.keyDown(KeyCode.mouseLeft);
        if(rowArmed && down){
            Core.scene.screenToStageCoordinates(Tmp.v1.set(Core.input.mouseX(), Core.input.mouseY()));
            float dx = Tmp.v1.x - rowPressX;
            float dy = Tmp.v1.y - rowPressY;
            if(!rowDragging && Math.max(Math.abs(dx), Math.abs(dy)) > ROW_DRAG_THRESHOLD){
                rowDragging = true;
                rowSuppressClick = true;                  // 本次按下已进化成拖 ⇒ 吞随后的 clicked()
                rowPressX = Tmp.v1.x;                     // I7.1b v2(M03 终审修):激活时重锚按下点——⚠必须在 enterRowFree
                rowPressY = Tmp.v1.y;                     // 之前读:Tmp.v1 此处仍是本帧轮询点(上方刚写入),而 enterRowFree
                                                          // 开头即把 Tmp.v1 覆写成按钮角坐标(对抗复核:放其后读到的不是
                                                          // 指针,钉态首拖反成「行角钉光标」整段瞬移,比无修更差)。
                if(!rowFree) enterRowFree();              // 首拖:对齐视觉位后转自由态(不跳变)
                rowStartX = hudRow.x;                     // 起点 = 转自由态后的行位(与锚点同帧配对,增量才同系)
                rowStartY = hudRow.y;
                dx = 0f; dy = 0f;                         // 增量从激活点起算;本帧 dx 归零 = 行不跳(死区不再被追补)
            }
            if(rowDragging){
                rowFreeX = clampPanelX(rowStartX + dx, hudRow.getWidth(), panelAreaW());
                rowFreeY = clampPanelY(rowStartY + dy, hudRow.getHeight(), panelAreaH());
                hudRow.setPosition(rowFreeX, rowFreeY);
            }
            return;
        }
        if(rowArmed){                                     // 抬起:落手
            rowArmed = false;
            if(rowDragging){
                rowDragging = false;
                persist();                                // 松手才落盘(镜像 PanelDrag)
            }
            return;
        }
        if(rowFree){                                      // 自由态静态跟位(窗口变化保显示内)
            float nx = clampPanelX(rowFreeX, hudRow.getWidth(), panelAreaW());
            float ny = clampPanelY(rowFreeY, hudRow.getHeight(), panelAreaH());
            if(rowFreeX != nx || rowFreeY != ny || hudRow.x != nx || hudRow.y != ny){
                rowFreeX = nx;
                rowFreeY = ny;
                hudRow.setPosition(nx, ny);
            }
        }
    }

    /** I7 首拖接管:切自由态,并把行摆回当前视觉位(按钮左下角不动 ⇒ 无跳变)。 */
    private static void enterRowFree(){
        Tmp.v1.set(0f, 0f);
        hudButton.localToStageCoordinates(Tmp.v1);            // 按钮左下角(舞台系)
        Vars.ui.hudGroup.stageToLocalCoordinates(Tmp.v1);     // → hudGroup 局部系(= 行坐标基系)
        hudRow.setFillParent(false);
        hudRow.pack();                                        // 活体尺寸(pack 内部 validate ⇒ 子级位就绪)
        hudRow.touchable = Touchable.enabled;                 // I7.1:自由态整箱可拖(复核 [3]/[6]:childrenOnly 下 8px 缝是拖动死区且按下穿到世界)
        rowFree = true;
        rowFreeX = clampPanelX(Tmp.v1.x - hudButton.x, hudRow.getWidth(), panelAreaW());
        rowFreeY = clampPanelY(Tmp.v1.y - hudButton.y, hudRow.getHeight(), panelAreaH());
        hudRow.setPosition(rowFreeX, rowFreeY);
    }

    /** I7 启动复位路径:切自由态并落到存储坐标(未跑过布局也安全:pack 独立、坐标走存储值)。 */
    private static void applyRowFreeStored(){
        hudRow.setFillParent(false);
        hudRow.pack();
        hudRow.touchable = Touchable.enabled;                 // I7.1:同 enterRowFree(自由态整箱可拖)
        rowFreeX = clampPanelX(rowFreeX, hudRow.getWidth(), panelAreaW());
        rowFreeY = clampPanelY(rowFreeY, hudRow.getHeight(), panelAreaH());
        hudRow.setPosition(rowFreeX, rowFreeY);
    }

    /**
     * I7 按钮行拖动受理层(arming 专用;移动 / 落手判定全在 updateHudRow 轮询)。与 PanelDrag 的差异及缘由:
     *   · 行内是【交互控件】(两个按钮):PanelDrag 的「命中链遇监听者即拒」过滤会把按钮按下全拒掉,
     *     而按钮必须既能点又能拖 ⇒ 本监听【无条件受理】行子树内的左键主触点按下(冒泡抵达即算);
     *   · touchDown 返回 false、不夺 touch focus:按钮 ClickListener 的焦点与 clicked() 语义原样保留
     *     (普通点按 = 原行为不变);代价是拿不到 touchDragged/touchUp 直投 ⇒ 移动与落手判改轮询,
     *     完全不依赖 focus 机制,也绕开 cancelTouchFocus / clearChildren 的不确定性;
     *   · 点击抑制走 rowSuppressClick 旗标(两按钮回调处查;按下清零、开拖置位)—— 与 click 分发时序无关
     *     (触及接管帧即置位,抬起帧 click 先到 / 后到都覆盖)。按住拖动期间按钮显按压高亮(焦点仍在
     *     按钮) = 预期观感。
     */
    private static class RowDrag extends InputListener{
        @Override
        public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode button){
            if(button != KeyCode.mouseLeft) return false;
            if(pointer != 0) return false;
            rowArmed = true;                                  // 状态机复位 + 受理(移动接管在轮询)
            rowDragging = false;
            rowSuppressClick = false;
            rowPressX = e.stageX;                             // 按下点走 Scene 实测 stage 坐标
            rowPressY = e.stageY;
            return false;                                     // 不夺焦点:按钮点按语义原样保留
        }
    }

    /** Refresh the single notice/event container; observe window dimensions even while folded. */
    private static void updateEventPanel(){
        if(Vars.headless || eventRoot == null) return;
        boolean eventVisible=enabled&&eventsOn&&Vars.world!=null&&Vars.state!=null;
        if(eventVisible!=lastEventVisible){lastEventVisible=eventVisible;eventRowsDirty=true;}
        boolean notices=false;
        long realNow=Time.millis();
        for(int i=eventQueue.size-1;i>=0;i--){
            Line line=eventQueue.get(i);
            if(line.notice&&realNow-line.bornMs>10000L){eventQueue.remove(i);eventRowsDirty=true;}
            else if(line.notice)notices=true;
        }
        if(!eventVisible && !notices){
            if(eventRoot.visible) eventRoot.visible = false;
            return;                                          // 零更新、零 seq 检测
        }
        if(!eventRoot.visible) eventRoot.visible = true;
        updateEventPanelPos();                               // I6:跟位(尺寸脏先 pack;坐标变化才 setPosition)
        updateEventFoldLabel();

        if(panelFolded) return;                              // I6:折叠 = box 已退树(布局即收缩),本帧到此为止

        if(!eventVisible){
            if(eventRowsDirty){rebuildEventRows();eventRowsDirty=false;}
            updateEventPanelPos();return;
        }

        // 令牌桶回充(Δms 走现役时间源 Time.millis;容量 4、4 条/s)
        long nowMs = Time.millis();
        if(lastTokenMs == 0L) lastTokenMs = nowMs;
        long dms = nowMs - lastTokenMs;
        if(dms > 0L){
            eventTokens = Math.min(EVENT_TOKEN_MAX, eventTokens + dms / 1000f * EVENT_TOKEN_RATE);
            lastTokenMs = nowMs;
        }

        // 挂账合成行优先补报(令牌一到先入一条;信息不丢,且每帧行数仍 ≤ 令牌数)
        if(pendingMerged > 0 && eventTokens >= 1f){
            eventTokens -= 1f;
            enqueueMerged(pendingMerged);
            pendingMerged = 0;
        }

        // 新条目检测(seq 单调不重置;跨度大只取最近 64)
        long s = BlackstartEvents.seq();
        if(s != lastSeq){
            if(s < lastSeq){
                lastSeq = s;                                 // 防御:异常回退 ⇒ 对齐且不产行
            }else{
                int take = (int)Math.min(s - lastSeq, 64L);
                // C4 修正:溢出时【预留 1 枚令牌】给合成折叠行。原字面写法(先让单条吃光令牌、再判
                // 「merged>0 && token>=1」)恒不可达(循环内令牌只减不增)⇒ 合成行永不出现。
                int avail = (int)eventTokens;                // 本帧整数令牌(0..4)
                boolean overflow = take > avail;
                int lines = overflow ? Math.max(0, avail - 1) : take;
                int merged = 0;
                for(int i = take - 1, shown = 0; i >= 0; i--){   // 旧→新入队(保序)
                    BlackstartEvents.Entry e = BlackstartEvents.get(i);
                    if(e == null) break;
                    if(shown < lines){ eventTokens -= 1f; shown++; enqueueLine(e); }
                    else merged++;
                }
                if(merged > 0){
                    if(eventTokens >= 1f){ eventTokens -= 1f; enqueueMerged(merged); }
                    else pendingMerged += merged;            // 无令牌 ⇒ 挂账,下帧补报
                }
                lastSeq = s;
            }
        }

        // 寿命(按 tick;暂停 ⇒ tick 冻结 ⇒ 自动冻住)
        long tick = (long)Vars.state.tick;
        boolean removed = false;
        for(int i = eventQueue.size - 1; i >= 0; i--){
            if(!eventQueue.get(i).notice && tick - eventQueue.get(i).bornTick > EVENT_LIFE_TICKS){
                eventQueue.remove(i);
                removed = true;
            }
        }
        if(removed || eventRowsDirty){
            rebuildEventRows();
            eventRowsDirty = false;
        }

        // 淡出 α(末 2s)作用到可见行
        int vis = eventQueue.size;
        for(int i = 0; i < vis; i++){
            Line ln = eventQueue.get(i);
            if(ln.textLabel==null || !eventRoot.isAscendantOf(ln.textLabel))continue;
            long age = ln.notice ? Math.max(0L,(Time.millis()-ln.bornMs)*60/1000) : tick - ln.bornTick;
            float a = 1f;
            if(age > EVENT_LIFE_TICKS - EVENT_FADE_TICKS){
                a = Mathf.clamp(1f - (age - (EVENT_LIFE_TICKS - EVENT_FADE_TICKS))
                    / (float)EVENT_FADE_TICKS, 0f, 1f);
            }
            applyLineAlpha(ln, a);
        }

        // count 刷新:环内最新条目与队首同 (kind,x,y) 且 count 变 ⇒ 只重建队首文本(低频)
        BlackstartEvents.Entry newest = BlackstartEvents.get(0);
        if(newest != null && eventQueue.size > 0){
            Line head = eventQueue.get(0);
            if(!head.synth && head.kind == newest.kind && head.x == newest.x && head.y == newest.y
                && head.count != newest.count){
                head.count = newest.count;
                head.baseText = formatEvent(newest.kind, newest.x, newest.y, newest.aux, newest.value);
                if(head.textLabel != null) head.textLabel.setText(displayRowText(head));
                eventRowsDirty=true;
            }
        }
    }

    /** 一条真实事件入队(调用方已占 token)。 */
    private static void enqueueLine(BlackstartEvents.Entry e){
        Line ln = new Line();
        ln.bornTick = e.tick;
        ln.kind = e.kind;
        ln.count = e.count;
        ln.x = e.x; ln.y = e.y; ln.aux = e.aux; ln.value = e.value;
        ln.synth = false;
        ln.timeText = formatEventTime(e.tick);
        ln.baseText = formatEvent(e.kind, e.x, e.y, e.aux, e.value);
        pushLine(ln);
    }

    /** 合成折叠行(灰;…另有 N 条)。 */
    private static void enqueueMerged(int n){
        Line ln = new Line();
        ln.bornTick = Vars.state == null ? 0L : (long)Vars.state.tick;
        ln.synth = true;
        ln.count = (short)Math.min(n, Short.MAX_VALUE);
        ln.timeText = formatEventTime(ln.bornTick);
        ln.baseText = bundle("blackstart.events.merged", "…另有 {0} 条")
            .replace("{0}", Integer.toString(n));
        pushLine(ln);
    }

    /** 新→旧入队;超上限丢最旧(尾)。 */
    private static void pushLine(Line ln){
        eventQueue.insert(0, ln);
        if(eventQueue.size > EVENT_QUEUE_CAP) eventQueue.remove(EVENT_QUEUE_CAP);
        eventRowsDirty = true;
    }

    /** One notice container shared by emit, player actions and native-zone feedback. */
    public static void showNotice(String text){
        if(text==null || text.isBlank() || Vars.headless || Vars.ui==null)return;
        mountEventPanel();
        Line line=new Line();line.notice=true;line.synth=true;line.bornMs=Time.millis();
        line.bornTick=Vars.state==null?0L:(long)Vars.state.tick;
        line.timeText=formatEventTime(line.bornTick);line.baseText=text;pushLine(line);
        if(eventRoot!=null){rebuildEventRows();layoutEventRoot();updateEventPanelPos();eventRoot.visible=true;}
    }

    /** 可见行序列重建(仅入队 / 出队 / 换图时调用;行 Table 惰性建后复用)。 */
    private static void rebuildEventRows(){
        if(eventBox == null) return;
        updateEventDimensions();
        eventBox.clearChildren();
        eventSizeDirty = true;                               // I6:行数变 ⇒ 尺寸可能变 ⇒ 跟位前 pack
        float remaining=Math.max(0f,eventBudget-eventFoldHeight-eventPad*4-eventBox.getMarginTop()-eventBox.getMarginBottom());
        float rowWidth=Math.max(1,eventWidth-eventBox.getMarginLeft()-eventBox.getMarginRight());
        int shown=0;
        for(int i=0;i<eventQueue.size;i++){
            Line ln=eventQueue.get(i);
            if(!ln.notice && (!enabled || !eventsOn))continue;
            buildRow(ln);
            ln.textLabel.setWidth(rowWidth);
            ln.row.getCell(ln.textLabel).width(ResponsiveUi.units(rowWidth));
            ln.row.invalidateHierarchy();ln.row.setWidth(rowWidth);ln.row.validate();
            float rowHeight=ln.row.getPrefHeight();
            if(shown>0 && rowHeight+eventPad*2>remaining)break;
            float height=Math.min(rowHeight,Math.max(0,remaining-eventPad*2));
            if(height<=0)break;
            if(rowHeight>height){
                arc.scene.ui.ScrollPane.ScrollPaneStyle style=Styles.smallPane!=null?Styles.smallPane:new arc.scene.ui.ScrollPane.ScrollPaneStyle();
                arc.scene.ui.ScrollPane scroll=new arc.scene.ui.ScrollPane(ln.row,style);
                float bar=Math.max(style.vScroll==null?0:style.vScroll.getMinWidth(),style.vScrollKnob==null?0:style.vScrollKnob.getMinWidth());
                float contentWidth=Math.max(1,rowWidth-bar);
                ln.textLabel.setWidth(contentWidth);ln.row.getCell(ln.textLabel).width(ResponsiveUi.units(contentWidth));ln.row.invalidateHierarchy();
                scroll.setScrollingDisabled(true,false);scroll.setFadeScrollBars(false);
                eventBox.add(scroll).minSize(0).size(ResponsiveUi.units(rowWidth),ResponsiveUi.units(height)).pad(ResponsiveUi.units(eventPad)).row();
            }else eventBox.add(ln.row).minSize(0).size(ResponsiveUi.units(rowWidth),ResponsiveUi.units(height)).pad(ResponsiveUi.units(eventPad)).row();
            remaining-=height+eventPad*2;shown++;
        }
        if(shown == 0 && remaining>0){
            if(eventEmptyLabel == null){
                eventEmptyLabel = new Label(bundle("blackstart.events.empty", "暂无事件"), Styles.outlineLabel);
                eventEmptyLabel.setColor(DANGLE_COLOR);          // C7:空态一行灰字
            }
            eventEmptyLabel.setWrap(true);
            eventBox.add(eventEmptyLabel).minSize(0).width(ResponsiveUi.units(rowWidth)).maxHeight(ResponsiveUi.units(remaining)).left();
        }
    }

    /** One independently wrapped row containing time and category-coloured text. */
    private static void buildRow(Line ln){
        if(ln.row!=null)return;
        Color cat = eventColor(ln);
        Table row = new Table();
        ln.textLabel = new Label(displayRowText(ln), Styles.outlineLabel);
        ln.textLabel.setColor(cat);
        ln.textLabel.setWrap(true);
        row.add(ln.textLabel).minWidth(0).left().top();
        ln.row = row;
    }

    /** 正文(×N 仅 count>1 且非合成行时追加;低频路径)。 */
    private static String rowText(Line ln){
        return (!ln.synth && ln.count > 1) ? ln.baseText + " ×" + ln.count : ln.baseText;
    }

    private static String displayRowText(Line ln){return ln.timeText+"  "+rowText(ln);}

    /** α 作用到行内全部 actor 的 color.a(色块 / 时间 / 正文)。 */
    private static void applyLineAlpha(Line ln, float a){
        Color cat = eventColor(ln);
        if(ln.swatch != null) ln.swatch.setColor(cat.r, cat.g, cat.b, a);
        if(ln.timeLabel != null) ln.timeLabel.setColor(1f, 1f, 1f, a);
        if(ln.textLabel != null) ln.textLabel.setColor(cat.r, cat.g, cat.b, a);
    }

    /** 类别色表(C6 冻结;合成折叠行 = 灰)。 */
    private static Color eventColor(Line ln){
        if(ln.synth || ln.kind == null) return DANGLE_COLOR;
        return switch(ln.kind){
            case LIMIT, SAT_WARN, SHED -> WARN_COLOR;
            case TRIP, RECLOSE_FAIL, SAT_CRIT -> CRIT_COLOR;
            case RECLOSE, SAT_RECOVER -> RECOV_COLOR;
            case LINK_ADD, LINK_DEL, ISLAND_SPLIT, ISLAND_MERGE, ZONE_SET, NODE_BUILT -> LOAD_BASE_COLOR;
            default -> DANGLE_COLOR;
        };
    }

    /**
     * 正文装配(C6 冻结表;取词照现役 bundle helper,占位用 String.replace;缺值降级 "?")。
     * 本函数只在入队 / count 刷新(低频)调用 ⇒ 允许 String.format。
     */
    private static String formatEvent(BlackstartEvents.Kind k, int x, int y, int aux, float value){
        if(k == null) return "?";
        String s = bundle(BlackstartEvents.bundleKey(k), k.name());
        switch(k){
            case PARAM -> s = s.replace("{0}", fmtPct1(value));
            case LINK_ADD, LINK_DEL -> s = s.replace("{0}", Integer.toString(x))
                .replace("{1}", Integer.toString(y))
                .replace("{2}", Integer.toString(Point2.x(aux)))
                .replace("{3}", Integer.toString(Point2.y(aux)));
            case ISLAND_SPLIT, ISLAND_MERGE -> s = s.replace("{0}", Integer.toString(aux));
            case SAT_WARN -> s = s.replace("{0}", Integer.toString(aux)).replace("{1}", fmtPct0(value));
            case SAT_CRIT, SAT_RECOVER -> s = s.replace("{0}", Integer.toString(aux));
            case NODE_BUILT, NODE_BROKEN -> s = s.replace("{0}", Integer.toString(x))
                .replace("{1}", Integer.toString(y));
            default -> s = s.replace("{0}", "(" + x + "," + y + ")");
        }
        return s;
    }

    /** 损耗百分数(%.1f%%;NaN ⇒ "?")。 */
    private static String fmtPct1(float v){
        return Float.isNaN(v) ? "?" : String.format(java.util.Locale.ROOT, "%.1f%%", v);
    }

    /** 满足率整数百分数(round(v×100);NaN ⇒ "?")。 */
    private static String fmtPct0(float v){
        return Float.isNaN(v) ? "?" : Long.toString(Math.round(v * 100f));
    }

    /** 对局内时钟 HH:mm:ss(小时可超 24 不升位;tick = 60/s)。 */
    private static String formatEventTime(long tick){
        long sec = tick / 60L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
            sec / 3600L, (sec / 60L) % 60L, sec % 60L);
    }

    /**
     * 按钮文本随状态刷新。状态键不变时【连字符串都不重建】——
     * 保证总开关关掉后每帧只剩一次状态比较(配合 draw 首段的早退)。
     */
    private static void updateHudText(){
        if(hudButton == null) return;
        int key = (enabled ? 2 : 0) | (mode == MODE_ONLY ? 1 : 0);
        if(key == hudStateKey) return;
        hudStateKey = key;
        hudButton.setText(hudText());
    }

    /** HUD 文案:`blackstart.view.hud` = 「电网视图:{0}」/「Grid overlay: {0}」。 */
    private static String hudText(){
        String pattern = bundle("blackstart.view.hud", "电网视图:{0}");
        return pattern.replace("{0}", stateText());
    }

    /** 当前态词(`blackstart.view.state.full/only/off`)。 */
    private static String stateText(){
        if(!enabled) return bundle("blackstart.view.state.off", "关");
        if(mode == MODE_ONLY) return bundle("blackstart.view.state.only", "仅模组链路");
        return bundle("blackstart.view.state.full", "全量");
    }

    /** bundle 取词(缺键回退字面量;mod bundle 会被 DataBundleLoader 并进 Core.bundle)。 */
    private static String bundle(String key, String fallback){
        return Core.bundle == null ? fallback : Core.bundle.get(key, fallback);
    }

    // ============================================================
    // I3 ⑦ 悬浮读数(drawHover;postDraw 段,只读派生)
    // ============================================================

    /**
     * ⑦ 悬浮读数。命中每帧做,读数(引擎值)只在「目标变化」或 ≥200ms(5Hz)时重算 —— 缓存 = 行文本 + 参考宽。
     * 命中优先级:建筑(且 `b.power != null`;非电力建筑不显示 = 本批裁定)> 最近 mod 链路线段(屏幕 6px 阈值)。
     * 逐帧成本:一次 `buildWorld`(O(1));未命中建筑时至多一趟 `segs` 线性扫描(典型 <50 条)。绝不扫 tile。
     * 隐藏条件(逐条):关视图/关悬浮、无世界、移动端、暂停、聊天或控制台打开、ds < 0.75(LOD)。
     * ⚠ ONLY 模式【照画】(T1 ⑥:ONLY = mod 链路 + mod 方块描边 + 悬浮)。
     */
    private static void drawHover(){
        hoverHit = HOVER_KIND_NONE;                        // A5:任何早退前清零(未画帧 = 0)
        if(Vars.headless) return;                          // 纵深防御(与既有风格一致)
        if(!enabled || !hoverOn){ clearHover(); return; }
        if(Vars.world == null || Vars.renderer == null) return;
        if(Vars.mobile){ clearHover(); return; }
        if(Vars.state == null || Vars.state.isPaused()){ clearHover(); return; }
        if(Vars.ui != null && ((Vars.ui.chatfrag != null && Vars.ui.chatfrag.shown())
            || (Vars.ui.consolefrag != null && Vars.ui.consolefrag.shown()))){ clearHover(); return; }
        float ds = Vars.renderer.getDisplayScale();
        if(lodBand() >= 1){ clearHover(); return; }          // LOD(D9):档 ≥1(含 lowfx 升档)关悬浮

        // ---- 命中(每帧做;mouseWorld() 返回 Input 内部复用向量,当帧用完即弃,勿跨帧保存)----
        Vec2 m = Core.input.mouseWorld();
        Building b = Vars.world.buildWorld(m.x, m.y);
        int kind = HOVER_KIND_NONE, key = -1;
        if(b != null && b.power != null){
            kind = HOVER_KIND_BUILDING;                    // 建筑 > 链路(网关同属此支,hit 报 1)
            key = b.pos();
        }else{
            int best = -1;
            float minD = HOVER_HIT_PX / ds;                // 屏幕恒定 6px ⇒ 世界阈值 6/ds
            for(int i = 0; i < segs.size; i++){
                float d = distToSeg(m.x, m.y, segs.get(i));
                if(d <= minD){
                    minD = d;
                    best = i;
                }
            }
            if(best >= 0){
                kind = HOVER_KIND_SEG;
                key = best;
            }
        }

        if(kind == HOVER_KIND_NONE){                       // 未命中 ⇒ 隐藏(缓存一并清)
            clearHover();
            return;
        }

        long now = Time.millis();
        boolean changed = kind != hoverTargetKind || key != hoverTargetKey;
        if(changed || now - hoverCalcMs >= HOVER_REFRESH_MS){
            hoverTargetKind = kind;
            hoverTargetKey = key;
            hoverCalcMs = now;
            if(kind == HOVER_KIND_BUILDING) calcHoverBuilding(b);
            else calcHoverSeg(key);
        }
        hoverHit = kind;
        if(hoverLineCount <= 0) return;
        drawHoverPanel(m, ds);
    }

    /** 隐藏态:清缓存与目标 identity(下次出现必重算)。 */
    private static void clearHover(){
        hoverLineCount = 0;
        hoverTargetKind = HOVER_KIND_NONE;
        hoverTargetKey = -1;
    }

    /**
     * 重算「建筑 / 网关」读数(≤4 行;引擎值只在此处读)。
     * 行 1 = 本地化名 + (tx,ty)[+ 网关尾缀 ` · 链路 k/n`];网关行 2/3 = 对端(≤2 条,超出尾缀 ` +N`);
     * 网关对端行含017 T2/T3 实装读数;设备模板见 calcHoverDevice;非网关行2为分量/满足率/负载,行3为电池。
     */
    private static void calcHoverBuilding(Building b){
        hoverLineCount = 0;
        hoverWRef = 0f;
        int tx = b.tileX(), ty = b.tileY();
        String title = b.block == null ? "—" : b.block.localizedName;

        // 分量 #k:按 c.g == b.power.graph 身份查表(与 ① 线色 / ④ 孤岛 / debug 同一份 20Hz 数据);
        // 查不到(新建筑重建前瞬态)⇒ k 显示 `—`,不崩。
        Comp c = null;
        int k = 0;
        if(b.power != null && b.power.graph != null){
            for(int i = 0; i < comps.length; i++){
                if(comps[i].g == b.power.graph){
                    c = comps[i];
                    k = i + 1;
                    break;
                }
            }
        }

        if(b.block instanceof BreakerNode || b.block instanceof ZoneSwitchNode){
            addHoverLine(title+" · "+bundle("blackstart.hover.position","Position")+" ("+tx+","+ty+")",false);
            calcHoverDevice(b);
        }else if(b.block instanceof GatewayNode && !(b instanceof GatewayNode.GatewayNodeBuild)){
            // 017c:块类型符合而 Build 尚未接管,保留未知模板,不从空缓存推断零链路。
            addHoverLine(bundle("blackstart.hover.links", "Links") + " —/—", false);
        }else if(b.block instanceof GatewayNode){
            int n = 0, valid = 0;
            for(int i = 0; i < segs.size; i++){
                Seg seg = segs.get(i);
                if(seg.source == b){ n++; if(!seg.dang) valid++; }
            }
            addHoverLine(title+" · "+bundle("blackstart.hover.position","Position")+" ("+tx+","+ty+") · "+bundle("blackstart.hover.links", "Links") + " " + valid + "/" + n, false);
            int shown = 0;
            for(int i = 0; i < segs.size && shown < HOVER_PEER_MAX; i++){
                Seg seg = segs.get(i);
                if(seg.source != b) continue;
                BsVisualData data = linkData(seg);
                float dx = seg.x2 - seg.x1, dy = seg.y2 - seg.y1;
                addHoverLine(linkOverview(seg,data), seg.dang);
                shown++;
            }
            if(n > shown) appendToLastHoverLine(" · " + moreText(n - shown));
        }else if(b.block != null && b.block.minfo.mod == null
            && VanillaPowerObservation.kind(b) != VanillaPowerObservation.Kind.NETWORK){
            calcHoverVanilla(b, title + " (" + tx + "," + ty + ")");
        }else{
            addHoverLine(title + " (" + tx + "," + ty + ")", false);
            // s% / L 走 Comp 采样面(与 ①④ 同源;查不到 ⇒ `—`)
            String kStr = k > 0 ? ("#" + k) : "—";
            String sat = c == null ? "—" : (Math.round(c.sat * 100f) + "%");
            String load = c == null ? "—" : fmt2(c.load);
            addHoverLine(bundle("blackstart.hover.comp", "Component") + " " + kStr + " · "
                + bundle("blackstart.hover.sat", "Satisfaction") + " " + sat + " · "
                + bundle("blackstart.hover.load", "Load") + " " + load, false);
            addHoverLine(bundle("blackstart.hover.battery", "Battery") + " " + batteryPct(b), false);
            if(b.block!=null && b.block.minfo.mod==null)
                addHoverLine(nativeFrequencyText(BlackstartDynamics.snapshotAt(b.pos())),false);
        }
        measureHoverLines();
    }

    /** Native per-device values are sampled only for the current target at the existing 5 Hz hover cadence. */
    private static void calcHoverVanilla(Building b, String title){
        VanillaPowerObservation.Snapshot s = VanillaPowerObservation.read(b);
        String kind = switch(s.kind()){
            case GENERATOR -> bundle("blackstart.hover.vanilla.generator", "Generator");
            case BUFFERED -> bundle("blackstart.hover.vanilla.battery", "Battery / buffer");
            case LOAD -> bundle("blackstart.hover.vanilla.load", "Load");
            default -> bundle("blackstart.hover.vanilla.unknownSource", "Unclassified source");
        };
        addHoverLine(title + " · " + kind, false);
        if(s.kind() == VanillaPowerObservation.Kind.BUFFERED){
            addHoverLine(bundle("blackstart.hover.vanilla.charge", "Charge") + " "
                + (Float.isFinite(s.charge()) ? fmt1(s.charge() * 100f) + "%" : "—"), false);
            addHoverLine(bundle("blackstart.hover.vanilla.stored", "Stored") + " " + observedNumber(s.stored())
                + " · " + bundle("blackstart.hover.vanilla.capacity", "Capacity") + " " + observedNumber(s.capacity()), false);
        }else{
            if(s.kind() != VanillaPowerObservation.Kind.LOAD){
                addHoverLine(bundle("blackstart.hover.vanilla.output", "Output") + " " + observedPower(s.output())
                    + " · " + bundle("blackstart.hover.vanilla.rated", "Base rating") + " " + observedPower(s.rated()), false);
            }
            if(b.block.consumesPower && b.block.consPower != null){
                addHoverLine(bundle("blackstart.hover.vanilla.demand", "Demand") + " " + observedPower(s.demand())
                    + " · " + bundle("blackstart.hover.vanilla.delivered", "Delivered") + " " + observedPower(s.delivered()), false);
            }
        }
        String frequency=nativeFrequencyText(BlackstartDynamics.snapshotAt(b.pos()));
        if(NativeGeneratorRetrofit.adapted(b)){
            float ramp=Vars.net!=null && Vars.net.client()?Float.NaN:NativeGeneratorRetrofit.rampFraction(b);
            frequency+=" · "+bundle("blackstart.hover.vanilla.startup","Startup factor")+" "
                +(Float.isFinite(ramp)?fmt1(Mathf.clamp(ramp)*100f)+"%":"—");
        }
        // All device kinds use at most title + two value rows + this combined fourth row.
        addHoverLine(frequency+" · "+(Vars.net != null && Vars.net.client()
            ? bundle("blackstart.hover.vanilla.client", "Device values: Local estimate · not host-authoritative; frequency: host snapshot")
            : bundle("blackstart.hover.vanilla.local", "Read-only device observation")), false);
    }

    private static String observedNumber(float value){
        return Float.isFinite(value) ? fmt1(value) : "—";
    }

    private static String observedPower(float value){
        return Float.isFinite(value) ? fmt1(value) + "/s" : "—";
    }

    /**
     * 重算「mod 链路线段」读数(≤4 行)。
     * 行1为链路两端格坐标/悬挂尾缀;行2为距离及017 T2流量/额定/损耗;行3为T3开断实况,未知显示 `—`。
     */
    private static void calcHoverSeg(int idx){
        hoverLineCount = 0;
        hoverWRef = 0f;
        if(idx < 0 || idx >= segs.size) return;            // 表重建后下标越界 ⇒ 视为未命中,不崩
        Seg s = segs.get(idx);

        int x1 = s.source.tileX(), y1 = s.source.tileY();
        int x2 = Point2.x(s.peerPos), y2 = Point2.y(s.peerPos);
        BsVisualData data = linkData(s);

        String line1 = bundle("blackstart.hover.links", "Links") + " (" + x1 + "," + y1 + ") " + BlackstartText.get("blackstart.text.flow", ">") + " (" + x2 + "," + y2 + ")";
        if(s.dang) line1 += " · " + bundle("blackstart.hover.dangling", "Dangling");
        addHoverLine(line1, s.dang);

        float dx = s.x2 - s.x1, dy = s.y2 - s.y1;
        addHoverLine(linkOverview(s,data),false);
        measureHoverLines();
    }

    /** 017:设备模板先于父类网关;条件信息合并一行,进线最多两行。 */
    private static void calcHoverDevice(Building b){
        BsVisualData data = b instanceof BsVisualData d ? d : null;
        int state = data == null ? -1 : data.bsSwitchState();
        String word = switch(state){
            case 0 -> bundle("blackstart.prot.breakerState.closed", "Closed");
            case 2 -> b.block instanceof ZoneSwitchNode ? bundle("blackstart.prot.zoneOffline", "Open")
                : bundle("blackstart.prot.breakerState.open", "Open");
            case 3 -> bundle("blackstart.prot.breakerState.locked", "Locked");
            case 4 -> bundle("blackstart.prot.breakerState.cooling", "Cooling");
            default -> "—";
        };
        String condition=bundle("blackstart.hover.state", "State") + ": " + word;
        int tie = BlackstartProtection.tieVerdict(b.tileX(), b.tileY());
        if(tie >= 0){
            String value = tie == 0 ? bundle("blackstart.tie.allowed", "Allowed") : bundle("blackstart.tie.denied", "Not ready") + "(" + BlackstartProtection.tieExplainOrUnknown(b.tileX(), b.tileY()) + ")";
            condition+=" · "+bundle("blackstart.hover.tie", "并网 {0}").replace("{0}", value);
        }
        int left = data == null ? -1 : data.bsRecloseLeft();
        if(state == 4){
            float progress = data.bsRecloseProgress();
            condition += " · "+bundle("blackstart.hover.cooling", "Cooling ({0}% left)").replace("{0}",
                Float.isFinite(progress) ? Integer.toString(Math.round((1f - Mathf.clamp(progress)) * 100f)) : "—");
        }
        if(state == 3 || left < 1){
            String reclose = bundle("blackstart.hover.reclose", "Reclose: {0} left")
                .replace("{0}", left < 0 ? "—" : Integer.toString(left));
            condition = condition.isEmpty() ? reclose : condition + " · " + reclose;
        }
        if(!condition.isEmpty()) addHoverLine(condition, false);
        int n = 0;
        for(int i = 0; i < segs.size; i++) if(segs.get(i).target == b) n++;
        int shown = 0;
        for(int i = 0; i < segs.size && shown < 2 && hoverLineCount < HOVER_MAX_LINES; i++){
            Seg seg = segs.get(i);
            if(seg.target != b) continue;
            BsVisualData source = linkData(seg);
            addHoverLine(linkOverview(seg,source), false);
            shown++;
        }
        if(n > shown) appendToLastHoverLine(" · " + moreText(n - shown));
    }

    /** 悬浮格式化只走既有 5Hz 缓存重算链,稳定绘图帧不构造文本。 */
    private static String linkFlowText(BsVisualData data, int i){
        return bundle("blackstart.hover.flow","Flow")+" "+observedPower(data == null ? Float.NaN : data.bsLinkFlow(i)*60f)
            +" · "+bundle("blackstart.hover.rating","Rating")+" "+observedPower(data == null ? Float.NaN : data.bsLinkCapacity(i)*60f);
    }

    private static String linkOverview(Seg seg,BsVisualData data){
        float dx=seg.x2-seg.x1,dy=seg.y2-seg.y1;
        return bundle("blackstart.hover.position","Position")+" ("+seg.source.tileX()+","+seg.source.tileY()+") > ("+Point2.x(seg.peerPos)+","+Point2.y(seg.peerPos)+") · "
            +bundle("blackstart.hover.dist","Dist")+" "+fmt1(Mathf.sqrt(dx*dx+dy*dy)/Vars.tilesize)+" · "+linkFlowText(data,seg.linkIndex)
            +" · "+bundle("blackstart.hover.loss","Loss")+" "+lossText(data,seg.linkIndex)+" · "+bundle("blackstart.hover.state","State")+" "+linkStateWord(data,seg.linkIndex);
    }

    private static String lossText(BsVisualData data, int i){
        float loss = data == null ? Float.NaN : data.bsLinkLossPct(i);
        return Float.isFinite(loss) ? fmt1(loss) + "%" : "—";
    }

    private static String linkStateWord(BsVisualData data, int i){
        int state = data == null ? -1 : data.bsLinkState(i);
        return state >= 0 && state < LINK_STATE_KEYS.length ? bundle(LINK_STATE_KEYS[state], "—") : "—";
    }

    private static String moreText(int n){
        return bundle("blackstart.hover.more", "...plus {0} more").replace("{0}", Integer.toString(n));
    }

    /** 电池 % = 绝对储能 / `getTotalBatteryCapacity()`(修 F4;分子【不 clamp】);分母 ≤0(含 NaN)⇒ `—`。 */
    private static String batteryPct(Building b){
        if(b.power == null || b.power.graph == null) return "—";
        float cap = b.power.graph.getTotalBatteryCapacity();
        if(!(cap > 0f)) return "—";
        float stored = b.power.graph.getBatteryStored();
        if(Float.isNaN(stored)) return "—";
        return Math.round(stored / cap * 100f) + "%";
    }

    /** 追加一行(超过 HOVER_MAX_LINES 忽略;行数上限由调用方保证)。 */
    private static void addHoverLine(String text, boolean dangle){
        if(hoverLineCount >= HOVER_MAX_LINES) return;
        hoverLines[hoverLineCount] = text;
        hoverLineDangle[hoverLineCount] = dangle;
        hoverLineCount++;
    }

    /** 末行尾缀(网关对端 >2 时的 ` +N`;不新增行)。 */
    private static void appendToLastHoverLine(String suffix){
        if(hoverLineCount <= 0) return;
        hoverLines[hoverLineCount - 1] = hoverLines[hoverLineCount - 1] + suffix;
    }

    /** 量各行参考宽并取最大(textScale=1 参考口径;绘制期乘实际 scale)。 */
    private static void measureHoverLines(){
        float maxRef = 0f;
        for(int i = 0; i < hoverLineCount; i++){
            float w = measureTextRef(hoverLines[i]);
            if(w > maxRef) maxRef = w;
        }
        hoverWRef = maxRef;
    }

    /**
     * 用 `Fonts.outline` 量一行文本的参考宽(世界单位 @textScale=1)。
     * ⚠ 量宽前必须把字体 data scale 设成与 `Drawf.text` 内部一致(`0.25f / Scl.scl(1f) * scale`),
     *   否则宽度差约 4.7 倍;量完还原进入时的 scale,不覆盖其他共享字体使用者。
     */
    private static float measureTextRef(String s){
        Font font = Fonts.outline;
        if(font == null || s == null) return 0f;
        float scaleX = font.getData().scaleX, scaleY = font.getData().scaleY;
        try{
            font.getData().setScale(0.25f / Scl.scl(1f));
            hoverLayout.setText(font, s);
            return hoverLayout.width;
        }finally{
            font.getData().setScale(scaleX, scaleY);
        }
    }

    /** Drawf.text 同口径布局;底板按实际字形边界画,恢复共享字体 scale/color/integer。
     *  layout/cache 重用,返回实际宽度供同一行的下一段定位;文本由现役缓存提供。 */
    private static float drawLabelText(String text, float x, float y, Color color, float scale, float pad){
        Font font = Fonts.outline;
        if(font == null || text == null) return 0f;
        Font.FontData data = font.getData();
        float oldScaleX = data.scaleX, oldScaleY = data.scaleY;
        Color oldColor = font.getColor();
        float oldR = oldColor.r, oldG = oldColor.g, oldB = oldColor.b, oldA = oldColor.a;
        boolean oldInteger = font.usesIntegerPositions();
        try{
            font.setUseIntegerPositions(false);
            data.setScale(0.25f / Scl.scl(1f) * scale);
            font.setColor(color);
            labelLayout.setText(font, text);
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            for(int ri = 0; ri < labelLayout.runs.size; ri++){
                GlyphLayout.GlyphRun run = labelLayout.runs.get(ri);
                for(int gi = 0; gi < run.glyphs.size; gi++){
                    Font.Glyph glyph = run.glyphs.get(gi);
                    if(glyph.height == 0) continue;
                    float bottom = data.ascent + run.y + glyph.yoffset * data.scaleY;
                    low = Math.min(low, bottom);
                    high = Math.max(high, bottom + glyph.height * data.scaleY);
                }
            }
            if(Float.isFinite(low) && Float.isFinite(high)){
                Draw.color(0f, 0f, 0f, LABEL_BG_ALPHA * color.a);
                Fill.rect(x + labelLayout.width * 0.5f, y + (low + high) * 0.5f,
                    labelLayout.width + pad * 2f, high - low + pad * 2f);
                Draw.color();
            }
            font.getCache().clear();
            font.getCache().addText(labelLayout, x, y);
            if(color.a < 1f) font.getCache().setAlphas(color.a);
            font.getCache().draw();
            return labelLayout.width;
        }finally{
            data.setScale(oldScaleX, oldScaleY);
            font.setColor(oldR, oldG, oldB, oldA);
            font.setUseIntegerPositions(oldInteger);
        }
    }

    /** 点到线段的最短距离(世界像素;零分配)。 */
    private static float distToSeg(float px, float py, Seg s){
        float dx = s.x2 - s.x1, dy = s.y2 - s.y1;
        float len2 = dx * dx + dy * dy;
        if(len2 <= 1e-6f){
            float ax = px - s.x1, ay = py - s.y1;
            return Mathf.sqrt(ax * ax + ay * ay);
        }
        float t = Mathf.clamp(((px - s.x1) * dx + (py - s.y1) * dy) / len2, 0f, 1f);
        float qx = s.x1 + t * dx, qy = s.y1 + t * dy;
        float rx = px - qx, ry = py - qy;
        return Mathf.sqrt(rx * rx + ry * ry);
    }

    /** Wrap cached rows with the actual drawing font scale, then clamp the complete panel and glyphs to the camera. */
    private static void drawHoverPanel(Vec2 m, float ds){
        if(Fonts.outline==null)return;
        float unit=hoverScale/HOVER_SCALE_BASE/Math.max(.0001f,ds);
        float gap=HOVER_GAP_PX*unit,edge=HOVER_EDGE_PX/Math.max(.0001f,ds);
        Rect view=Core.camera.bounds(Tmp.r1);
        edge=Math.min(edge,Math.min(view.width,view.height)*.04f);
        float scale=Mathf.clamp(hoverScale/ds,HOVER_SCALE_MIN,HOVER_SCALE_MAX*(hoverScale/HOVER_SCALE_BASE));
        HoverLayout layout=HoverLayout.fit(Fonts.outline,hoverLines,hoverLineCount,
            .25f/Scl.scl(1f)*scale,Math.max(.001f,view.width-edge*2),Math.max(.001f,view.height-edge*2),HOVER_PAD_PX*unit);
        float x=m.x+gap;
        if(x+layout.width>view.x+view.width-edge)x=m.x-gap-layout.width;
        x=Mathf.clamp(x,view.x+edge,view.x+view.width-edge-layout.width);
        float top=m.y+gap;
        if(top-layout.height<view.y+edge)top=m.y+gap+layout.height;
        top=Mathf.clamp(top,view.y+edge+layout.height,view.y+view.height-edge);
        Draw.z(Z_HOVER);Draw.color(0f,0f,0f,HOVER_BG_ALPHA);
        Fill.rect(x+layout.width*.5f,top-layout.height*.5f,layout.width,layout.height);
        layout.draw(Fonts.outline,x,top,hoverLineDangle,DANGLE_COLOR);Draw.reset();
    }

    // ============================================================
    // I3 ⑧ 消费侧角标(drawBadges;postDraw 段,只读派生)
    // ============================================================

    /**
     * ⑧ 消费侧角标。判据 = 合本 F12 定稿串(引擎实时字段:含 `shouldConsumePower` 空闲排除、`buffered` 排除)。
     * 每帧【一趟】`comps → members` 遍历(逐成员先视口剔除;成本与 ③ 微染同款;绝不两遍成员),命中即入
     * 64 槽暂存表并累计 per-comp 计数:total ≤ 64 ⇒ 单体模式(与建筑一一对应);
     * total > 64 ⇒【整屏本帧切换】计数模式(每 comp 一枚 `词 × N`,画在其包围盒中心)。
     * 门:enabled && badgesOn && MODE_FULL && !Vars.mobile && ds ≥ 0.45(ONLY 隐角标 = T1 ⑥;后两条本批裁定)。
     * 017:分量内任一接口成员报告限流,整个分量的非危急消费者显示蓝灰限流角标。
     */
    private static void drawBadges(){
        starc = 0;
        critc = 0;                                         // A5:任何早退前清零(未画帧 = 0)
        if(Vars.headless) return;
        if(!enabled || !badgesOn) return;
        if(mode != MODE_FULL) return;
        if(Vars.world == null || Vars.renderer == null) return;
        if(Vars.mobile) return;
        float ds = Vars.renderer.getDisplayScale();
        if(lodBand() >= 2) return;                         // LOD(D9):档 2 全关角标(含 lowfx 升档)
        float badgeUnit = labelTextScale(ds, badgeScale) / BADGE_TEXT_SCALE_BASE;
        drawLockBadges();                                  // 018:随同一角标门,不依赖消费者命中
        if(comps.length == 0) return;

        // per-comp 计数槽:仅当分量数增长(重建)时再分配 ⇒ 帧内零分配
        if(compStarve.length < comps.length){
            compStarve = new int[comps.length];
            compCrit = new int[comps.length];
            compLimit = new int[comps.length];
            badgeTextCache = new String[comps.length];   // 文本缓存与计数槽同点生长
            // N2 措辞修正(I5):数组生长那一刻新槽的「未画」标记 = badgeTextCache[ci] == null
            // (以及 badgeTextWord[ci] == null);绘制处的实际三条件判定见下方
            // `badgeTextCache[ci] == null || badgeTextKey[ci] != key || badgeTextWord[ci] != word`;
            // 已画分量的失效判定 = 与 badgeTextKey[ci] / badgeTextWord[ci] 比对(不等才重建)。
            // 「key == 0」只是 int 数组默认值,不是判定依据(对已画 comp 不可达)。
            badgeTextKey = new int[comps.length];
            badgeTextWord = new String[comps.length];
        }

        Rect view = Core.camera.bounds(Tmp.r1);
        float vx = view.x, vy = view.y, vw = view.width, vh = view.height;

        int staged = 0, total = 0;
        for(int ci = 0; ci < comps.length; ci++){
            Comp c = comps[ci];
            compStarve[ci] = 0;
            compCrit[ci] = 0;
            compLimit[ci] = 0;
            boolean throttled = false;
            for(int mi = 0; mi < c.members.length; mi++){
                if(c.members[mi] instanceof BsVisualData data && data.bsGraphThrottled()){
                    throttled = true; break;             // 整个分量的消费者共用限流标志
                }
            }
            Building[] ms = c.members;
            for(int mi = 0; mi < ms.length; mi++){
                Building b = ms[mi];
                if(!pointInView(b.x, b.y, vx, vy, vw, vh)) continue;   // 先视口剔除(现役剔除写法 + CULL_MARGIN)
                if(!badgeHit(b)) continue;
                boolean crit = badgeCritical(b, c);                    // need/sat 取本 comp 采样面(唯一口径)
                int tier = crit ? 2 : throttled && b.power.status < BADGE_STARVE ? 1 : 0;
                if(tier == 2) compCrit[ci]++; else if(tier == 1) compLimit[ci]++; else compStarve[ci]++;
                total++;
                if(staged < BADGE_CAP){                                 // 暂存表满后只计数(供切换判据)
                    badgeX[staged] = b.x;
                    badgeY[staged] = b.y + b.block.size * 4f
                        + Math.max(BADGE_DOT_LIFT * badgeScale, (BADGE_DOT_R_PX + LABEL_PAD_PX) * badgeUnit);
                    badgeTier[staged] = tier;
                    staged++;
                }
            }
        }
        if(total == 0) return;

        Draw.z(Z_HOVER);
        if(total <= BADGE_CAP){
            for(int i = 0; i < staged; i++){                            // 单体模式:一一对应
                int tier = badgeTier[i];
                drawBadgeMark(badgeX[i], badgeY[i], tier, badgeWord(tier), ds);
                if(tier == 2) critc++; else starc++;
            }
        }else{
            for(int ci = 0; ci < comps.length; ci++){                   // 计数模式:每 comp 一枚(计数角标各计 1)
                int ns = compStarve[ci] + compLimit[ci], nc = compCrit[ci];
                if(ns + nc == 0) continue;
                Comp c = comps[ci];
                int tier = nc > 0 ? 2 : compLimit[ci] > 0 ? 1 : 0;
                String word = badgeWord(tier);
                int key = ((ns + nc) << 2) | tier;            // 缓存键:计数<<2 | 三档优先级
                if(badgeTextCache[ci] == null || badgeTextKey[ci] != key || badgeTextWord[ci] != word){
                    badgeTextCache[ci] = word + " × " + (ns + nc);      // 仅键变(计数/色型/语言)才分配
                    badgeTextKey[ci] = key;
                    badgeTextWord[ci] = word;
                }
                // 包围盒中心近似 T1 的「岛质心」(现役 Comp 只缓存 min/max;本批裁定,已列必报项)
                drawBadgeMark((c.minX + c.maxX) * 0.5f, (c.minY + c.maxY) * 0.5f,
                    tier, badgeTextCache[ci], ds);
                if(tier == 2) critc++; else starc++;
            }
        }
        Draw.reset();
    }

    /** ⑧ 判据(合本 F12 定稿串 + 前置空引用守卫;短路顺序照写;status 为 NaN ⇒ 不标)。 */
    private static boolean badgeHit(Building b){
        if(b.power == null) return false;                     // 前置守卫(本批加;合本串隐含)
        if(b.block == null || b.block.consPower == null) return false;
        if(b.block.consPower.buffered) return false;          // 电池 / 缓冲消费者不标
        if(!b.block.consumesPower) return false;
        if(!b.shouldConsumePower) return false;               // F12:空闲机器排除(字段直读,不是方法)
        float st = b.power.status;
        if(Float.isNaN(st)) return false;
        return st < BADGE_STARVE;                             // 0.98
    }

    /** ⑧ 危急判定:status < 0.35,或所属 comp `sat == 0`。sat 取 Comp 采样面。 */
    private static boolean badgeCritical(Building b, Comp c){
        if(b.power.status < BADGE_CRIT) return true;
        return c != null && c.sat == 0f;
    }

    /** ⑧ 角标短文本用词(A4 定稿串;单体/计数两模式同源;`Core.bundle.get` 返回稳定实例 ⇒ 零分配)。 */
    private static String badgeWord(int tier){
        return tier == 2 ? bundle("blackstart.badge.critical", "Blackout")
            : tier == 1 ? bundle("blackstart.badge.throttled", "Throttled")
            : bundle("blackstart.badge.starved", "No power");
    }

    /**
     * 画一枚角标:实心圆点与可选短文本随独立字号倍率同步缩放,间距和底板沿用相同缩放口径。
     * 缺电 = 琥珀恒 α1;危急 = 红闪 `0.6 + 0.4×wave`(周期 0.7s,相位【取模】防长跑冻结)。
     * 动态 α 一律经 `Tmp.c1` 派生 ⇒ 绝不改写 `CRIT_FLASH_COLOR` 常量字段(否则污染 ① 线色 / ④ 孤岛)。
     */
    private static void drawBadgeMark(float x, float dotY, int tier, String text, float ds){
        float ts = labelTextScale(ds, badgeScale);
        float unit = ts / BADGE_TEXT_SCALE_BASE;
        Tmp.c1.set(tier == 2 ? CRIT_FLASH_COLOR : tier == 1 ? DANGLE_COLOR : WARN_COLOR);
        if(tier == 2){
            float wave = (1f - Mathf.cos((Time.time % BADGE_PULSE_PERIOD) * Mathf.PI2 / BADGE_PULSE_PERIOD)) * 0.5f;
            Tmp.c1.a(BADGE_CRIT_A0 + BADGE_CRIT_A1 * wave);
        }
        Draw.color(Tmp.c1);
        Fill.circle(x, dotY, BADGE_DOT_R_PX * unit);
        if(text != null && lodBand() == 0){                   // LOD(D9):仅档 0 画字(档 1 只留点)
            drawLabelText(text, x + BADGE_TEXT_GAP_PX * unit, dotY, Tmp.c1, ts, LABEL_PAD_PX * unit);
        }
    }

    /** `%.1f`(ROOT locale,防区域小数点);NaN ⇒ `—`。 */
    private static String fmt1(float v){
        return Float.isNaN(v) ? "—" : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** `%.2f`(ROOT locale);NaN ⇒ `—`。 */
    private static String fmt2(float v){
        return Float.isNaN(v) ? "—" : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
