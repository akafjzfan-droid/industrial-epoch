package blackstart;

import arc.Events;
import arc.files.Fi;
import arc.math.geom.Point2;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.Version;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.gen.Building;
import mindustry.io.SaveIO;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.modules.PowerModule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * S0 装具(2026-10-06):blackstart 无头冒烟载荷。
 *
 * 入口 = {@link #smoke(String)},由 BlackstartMod.registerServerCommands 注册的
 * `bs-lab-smoke <jsonPath>` 调用(无头服务端没有 Vars.ui,bs-* 客户端命令到不了这里)。
 *
 * 职责:采集本次加载的运行时事实,按固定 schema `blackstart.lab.v2` 写 JSON 到 jsonPath。
 *
 * T5(I4,2026-10-07)扩展:schema 升 v2(v1 十字段一字不动,根追加八字段:`case`/`seq`/
 * `breakers`/`zones`/`params`/`snap`/`timing`/`asserts`);新增事件环导出 {@link #events(String)}、
 * 参数写入门 {@link #setParam(String, String)}、装具自测 {@link #evtest(String)}。
 * 既有 helper 一字未动;新代码同样不引用 UI/Draw 系(无头安全)。
 * 纪律:
 *   · 无论成败【都】写 JSON —— 整个采集包 try/catch(Throwable),失败时把 error 填上照写;
 *   · 先写 jsonPath + ".tmp",再 moveTo;moveTo 不可靠时退化为「确认 tmp 成功后直写最终文件」,
 *     不允许出现「打了 done 但最终文件不在」;
 *   · 最后一行固定日志 `[bs-lab] done <path>` —— 中枢的同步锚;
 *   · 全程不引用 Vars.ui / Draw / Renderer / Texture / Core.app(无头安全)。
 *
 * 扫描骨架照抄 BlackstartMod.printGatewayStatus / printGatewayDump:全图 tile 双层扫描,
 * 不遍历 Groups.build(网关 update=false,本就不在 Groups.build 里)。
 *
 * T2(G0+G1,2026-10-08)追加:`bs-lab-t2` = T2 链路验收装具(ops: build/link/probe),
 * 锚点坐标与步骤语义见 {@link #t2(String[])} / {@link #t2Probe(String)} 方法注释。
 */
public class BlackstartLab {

    /** 018:只读最近一次结算岛态,δ=rAll、源判据与并网预检使用同一冻结门/窗口。 */
    public static void tie(String[] args){
        String usage = "[bs-lab] 用法: bs-lab-tie <x1> <y1> <x2> <y2>";
        if(args == null || args.length != 4){ BlackstartMod.emit(usage); return; }
        int x1, y1, x2, y2;
        try{
            x1 = Integer.parseInt(args[0]); y1 = Integer.parseInt(args[1]);
            x2 = Integer.parseInt(args[2]); y2 = Integer.parseInt(args[3]);
        }catch(NumberFormatException e){ BlackstartMod.emit(usage); return; }
        // 先按完整 tile 坐标查世界,避免 packed 的16位截断把越界点变成另一建筑。
        Building a = Vars.world == null ? null : Vars.world.build(x1, y1);
        Building b = Vars.world == null ? null : Vars.world.build(x2, y2);
        int ka = a == null ? -1 : SafetyNet.islandAt(a.pos());
        int kb = b == null ? -1 : SafetyNet.islandAt(b.pos());
        float da = ka < 0 ? Float.NaN : SafetyNet.islandRAll(ka);
        float db = kb < 0 ? Float.NaN : SafetyNet.islandRAll(kb);
        boolean srcA = ka >= 0 && SafetyNet.islandSourced(ka), srcB = kb >= 0 && SafetyNet.islandSourced(kb);
        String verdict = ka < 0 || kb < 0 || !srcA || !srcB ? "NA"
            : Math.abs(da) > SafetyNet.TIE_WINDOW || Math.abs(db) > SafetyNet.TIE_WINDOW ? "BAD" : "OK";
        BlackstartMod.emit(String.format(java.util.Locale.ROOT,
            "[bs-lab] tie a=(%d,%d) b=(%d,%d) dA=%.3f srcA=%d dB=%.3f srcB=%d verdict=%s",
            x1, y1, x2, y2, da, srcA ? 1 : 0, db, srcB ? 1 : 0, verdict));
    }

    /** 顶层 schema 标识(中枢验收脚本按它解析)。 */
    public static final String SCHEMA = "blackstart.lab.v2";

    /** 1 参重载:转调 3 参(case=null/seq=null ⇒ 默认 "smoke" / 1);旧调用方行为不变。 */
    public static void smoke(String jsonPath){
        smoke(jsonPath, null, null);
    }

    /**
     * 采集并落盘。jsonPath 由调用方给出(中枢传 runs/<ts>/smoke.json 的绝对路径)。
     * caseName 为 null → "smoke";seqText 为 null / 解析失败 → 1(静默兜底,不抛)。
     */
    public static void smoke(String jsonPath, String caseName, String seqText){
        // ---- 新八字段的 case/seq(缺省归一化;解析失败不抛)----
        String caseValue = caseName == null ? "smoke" : caseName;
        int seqValue = 1;
        if(seqText != null){
            try{
                seqValue = Integer.parseInt(seqText);
            }catch(NumberFormatException ignored){
                seqValue = 1;
            }
        }

        // ---- 采集容器:先落局部变量,异常时保留「已采到的部分」,未采到的记 null ----
        boolean headless = false;
        int build = 0;
        String versionString = null;
        String dataDir = null;
        String cwd = null;
        Jval world = null;                 // null = Vars.world 未加载 / 未采集到
        Jval blockFlags = null;
        Jval gates = Jval.newArray();
        Jval powerNodes = Jval.newArray();
        Jval params = Jval.newObject();    // 采集失败时为空对象(不伪造)
        Jval error = null;

        try{
            headless = Vars.headless;
            versionString = Version.buildString();
            build = versionCode(versionString);
            dataDir = Vars.dataDirectory == null ? null : Vars.dataDirectory.absolutePath();
            cwd = System.getProperty("user.dir");
            params = BlackstartParams.dump();

            GatewayNode gateway = BlackstartBlocks.gatewayNode;
            if(gateway == null){
                // 内容未加载(理论到不了)也照 schema 走:旗标记 null,不伪造。
                blockFlags = null;
            }else{
                blockFlags = Jval.newObject()
                    .put("update", gateway.update)
                    .put("connectedPower", gateway.connectedPower)
                    .put("autolink", gateway.autolink);
            }

            if(Vars.world == null){
                // 未加载世界:world 整个记 null,并跳过全部扫描(gates/powerNodes 保持空数组)。
                world = null;
            }else{
                world = Jval.newObject()
                    .put("width", Vars.world.width())
                    .put("height", Vars.world.height());
                scanWorld(gates, powerNodes);
            }
        }catch(Throwable t){
            error = Jval.newObject()
                .put("type", t.getClass().getName())
                .put("message", String.valueOf(t.getMessage()))
                .put("stack", stackTrace(t));
        }

        // ---- 组装(字段名/类型照 schema 定死;读不到的记 null)----
        Jval root = Jval.newObject();
        root.put("schema", SCHEMA);
        root.put("headless", headless);
        root.put("build", build);
        // 附加字段(非 schema 必需,中枢解析按名取、不会读到):记录 build=1605 的来源版本串,
        // 便于事后核对「1605 是 160.5 的版本码」而不是臆造值。详见 改动说明.md「与任务书的偏差」。
        putString(root, "versionString", versionString);
        putString(root, "dataDir", dataDir);
        putString(root, "cwd", cwd);
        root.put("world", world == null ? Jval.NULL : world);
        root.put("blockFlags", blockFlags == null ? Jval.NULL : blockFlags);
        root.put("gates", gates);
        root.put("powerNodes", powerNodes);
        root.put("error", error == null ? Jval.NULL : error);

        // ---- I4 新增八字段(v1 十字段在前,一行未动;以下为 v2 骨架)----
        root.put("case", caseValue);
        root.put("seq", seqValue);
        root.put("breakers", Jval.newArray());        // I13 前恒空(断路器未落地)
        root.put("zones", Jval.newArray());           // I13 前恒空(分区开关未落地)
        root.put("params", params);                   // 真数据:全部 blackstart.* rules.tags
        root.put("snap", Jval.NULL);                  // RT 批接入,占位
        root.put("timing", Jval.NULL);                // RT/压测批接入,占位
        root.put("asserts", Jval.newArray());         // I15 接

        // ---- 落盘:tmp → moveTo,失败退化直写;done 行是无条件最后一行 ----
        try{
            writeJson(jsonPath, root.toString(Jval.Jformat.plain));
        }catch(Throwable t){
            Log.err("[bs-lab] 写文件失败 " + jsonPath + " : " + t);
        }finally{
            Log.info("[bs-lab] done " + jsonPath);
        }
    }

    /**
     * I4 装具:导出客户端事件环为 JSON(schema `blackstart.lab.events.v1`;同款原子落盘 +
     * `[bs-lab] done` 锚)。entries 从新到旧(i=0..size-1);`key` = `BlackstartEvents.bundleKey(kind)`
     * (冻结键规则的运行时物证);value 为 NaN → 写 JSON null,否则写数字。
     * 整体 try/catch(Throwable) 照 smoke 的 error 模式;无论成败都写文件。
     */
    public static void events(String jsonPath){
        int cap = BlackstartEvents.CAPACITY;
        int size = 0;
        Jval entries = Jval.newArray();
        Jval error = null;

        try{
            size = BlackstartEvents.size();
            for(int i = 0; i < size; i++){
                BlackstartEvents.Entry e = BlackstartEvents.get(i);
                if(e == null) continue;
                Jval one = Jval.newObject();
                one.put("tick", e.tick);
                if(e.kind == null){
                    one.put("kind", Jval.NULL);
                    one.put("key", Jval.NULL);
                }else{
                    one.put("kind", e.kind.name());
                    one.put("key", BlackstartEvents.bundleKey(e.kind));
                }
                one.put("count", (int)e.count);
                one.put("x", e.x);
                one.put("y", e.y);
                one.put("aux", e.aux);
                if(Float.isNaN(e.value)){
                    one.put("value", Jval.NULL);
                }else{
                    one.put("value", e.value);
                }
                entries.add(one);
            }
        }catch(Throwable t){
            error = Jval.newObject()
                .put("type", t.getClass().getName())
                .put("message", String.valueOf(t.getMessage()))
                .put("stack", stackTrace(t));
        }

        Jval root = Jval.newObject();
        root.put("schema", "blackstart.lab.events.v1");
        root.put("cap", cap);
        root.put("size", size);
        root.put("entries", entries);
        root.put("error", error == null ? Jval.NULL : error);

        try{
            writeJson(jsonPath, root.toString(Jval.Jformat.plain));
        }catch(Throwable t){
            Log.err("[bs-lab] 写文件失败 " + jsonPath + " : " + t);
        }finally{
            Log.info("[bs-lab] done " + jsonPath);
        }
    }

    /**
     * I4 装具:参数写入门(转调 {@link BlackstartParams#set(String, String)})。
     * 回读并打一行(逐字格式);不写文件;set 内部自己的 WARN 照常出。
     */
    public static void setParam(String key, String value){
        boolean ok = BlackstartParams.set(key, value);
        String now = BlackstartParams.get(key, "-");
        Log.info("[bs-lab] setparam " + key + "=" + value + " ok=" + ok + " now=" + now);
    }

    /**
     * I5 装具自测(升级 I4 版):20 Kind 覆盖 + seq 语义 + 去抖合并 + 越界读法 + BsVisualData 契约。
     * 序列:清环 → 20 Kind 各一条(坐标唯一) → 补 NaN 条 TRIP(99,99) → 合并条 LIMIT(0,1)(同第 1 条)。
     * 断言 a–h 逐条 PASS/FAIL 打印(风格照现役装具);末尾照写事件 JSON(供中枢物证)。
     */
    public static void evtest(String jsonPath){
        BlackstartEvents.clearOnWorldChange();
        long seq0 = BlackstartEvents.seq();

        int i = 0;
        for(BlackstartEvents.Kind k : BlackstartEvents.Kind.values()){
            BlackstartEvents.push(k, i, i + 1, i, i);
            i++;
        }
        BlackstartEvents.push(BlackstartEvents.Kind.TRIP, 99, 99, 0, Float.NaN);
        BlackstartEvents.push(BlackstartEvents.Kind.LIMIT, 0, 1, 0, 5f);

        evAssert("a) Kind.values().length == 20", BlackstartEvents.Kind.values().length == 20,
            "n=" + BlackstartEvents.Kind.values().length);
        evAssert("b) size() == 21", BlackstartEvents.size() == 21, "size=" + BlackstartEvents.size());
        evAssert("c) seq() - seq0 == 21", BlackstartEvents.seq() - seq0 == 21L,
            "d=" + (BlackstartEvents.seq() - seq0));

        for(BlackstartEvents.Kind k : BlackstartEvents.Kind.values()){
            Log.info("[bs-lab] evtest key " + BlackstartEvents.bundleKey(k));
        }

        BlackstartEvents.Entry first = BlackstartEvents.get(BlackstartEvents.size() - 1);
        evAssert("e) LIMIT(0,1) count == 2 && value == 5",
            first != null && first.kind == BlackstartEvents.Kind.LIMIT
                && first.count == 2 && first.value == 5f,
            first == null ? "null" : ("count=" + first.count + " value=" + first.value));

        BlackstartEvents.Entry newest = BlackstartEvents.get(0);
        evAssert("f) TRIP(99,99) value is NaN",
            newest != null && newest.kind == BlackstartEvents.Kind.TRIP && Float.isNaN(newest.value),
            newest == null ? "null" : ("kind=" + newest.kind + " value=" + newest.value));

        evAssert("g) get(-1)==null && get(size)==null && get(0)!=null",
            BlackstartEvents.get(-1) == null && BlackstartEvents.get(BlackstartEvents.size()) == null
                && BlackstartEvents.get(0) != null, null);

        java.lang.reflect.Method[] methods = BsVisualData.class.getDeclaredMethods();
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for(java.lang.reflect.Method m : methods){
            names.add(m.getName());
            Log.info("[bs-lab] evtest bsVisualData method " + m.getName());   // h) 9 名逐一打印
        }
        String expected = "[bsDeviceKind, bsGraphThrottled, bsLinkCapacity, bsLinkFlow, "
            + "bsLinkLossPct, bsLinkState, bsRecloseLeft, bsRecloseProgress, bsSwitchState]";
        boolean h1 = BsVisualData.class.isInterface();
        boolean h2 = methods.length == 9;
        boolean h3 = names.toString().equals(expected);
        evAssert("h) BsVisualData interface + 9 methods", h1 && h2 && h3,
            "interface=" + h1 + " n=" + methods.length + " names=" + names);

        events(jsonPath);
    }

    /** evtest 断言输出(风格照现役装具:行首 [bs-lab])。 */
    private static void evAssert(String name, boolean ok, String detail){
        Log.info("[bs-lab] evtest " + (ok ? "PASS" : "FAIL") + " " + name
            + (detail == null ? "" : " [" + detail + "]"));
    }

    // ============================================================
    // T2 装具 bs-lab-t2(链路验收;语义冻结 = 任务-011 §0.1-D13)
    // 固定锚点(区外既有设施 (30,30)/(12,34)/(24,24)/核心区(39..43,39..43) 一律不碰):
    //   A 站: powerSource@(40,0) [nosrc 时跳过] + 网关@(41,0) + kOwn 个 mender
    //         kOwn 位固定序 = (40,1)(41,1)(42,1)(43,1)(44,1)(45,1)(46,1)(47,1)(40,2)(41,2)
    //         + node 补网 (39,1)(39,2)(42..47,2)(39..47,3):引擎实测「纯耗电方块互不相连」,
    //         无网时 kOwn 仅 2 个贴源/网关者入图;有网后 10 个全入图(node 零耗电零产能,不动数学)
    //   B 站: 网关@(33,10) + node 列 x∈{34,37,40,43,46} × y=11..31 + 底行 y=32 全 node
    //         + kLoad 个 mender 行主序填其余格(容量 10 列 × 21 行 = 210)
    //   清区: x∈[33,47] × y∈[0,32](先校验后动手;参数非法一律不摆)
    // ============================================================

    private static final int T2_X0 = 33, T2_X1 = 47, T2_Y0 = 0, T2_Y1 = 32;
    private static final int T2_AX = 41, T2_AY = 0;
    private static final int T2_SRCX = 40, T2_SRCY = 0;
    private static final int T2_BX = 33, T2_BY = 10;
    private static final int T2_OWN_MAX = 10;
    private static final int T2_LOAD_MAX = 210;   // 10 列 × 21 行(node 网格摆场容量)
    // ---- 012(D26)装具三 op 的坐标/容量常量(注释画清 A/B 站布局)----
    // PS(40,0) / GA 网关(41,0) / kOwn 10 格((40..47,1) + (40,2)(41,2)) /
    // A 电池位 12 格 = (42..47,0) 6 格 + (42..47,2) 6 格(行主序;row2 与 011 补网 node 同位,见下);
    // B 网关(33,10) / B 电池行 y=9(x=33..47,15 格) / B 负载区 y=11..31(node 列 x∈{34,37,40,43,46} 除外)
    private static final int T2_LOAD_KMAX = 220;   // load 参数上限(实际负载位 210;>210 时填满)
    private static final int T2_ABAT_MAX = 12;     // A 站电池位 12
    private static final int T2_BBAT_MAX = 15;     // B 站电池位 15
    private static final int T2_BBAT_Y = 9;        // B 电池行(网关 (33,10) 正上方一行)
    private static final int[] T2_ABAT_X = {42, 43, 44, 45, 46, 47, 42, 43, 44, 45, 46, 47};
    private static final int[] T2_ABAT_Y = { 0,  0,  0,  0,  0,  0,  2,  2,  2,  2,  2,  2};

    // ---- 013(D37)arb 摆场常量 ----
    // A 区清区范围 x∈[33,47]×y∈[0,8](含 RTG 下排 2×2 的 y=8);B 区网关锚点(0→33,10 / 1→37,10 / 2→40,10)。
    private static final int T2_ARB_CLEAR_Y1 = 8;
    private static final int[] T2_ARB_GWX = {33, 37, 40};
    private static final int T2_ARB_GWY = 10;
    // RTG 锚点 8 处(2×2 占锚点右上方各格:anchor..anchor+1);上排 y=3 贴 node 行 y=5,下排 y=7 贴 y=6。
    private static final int[] T2_ARB_RTGX = {34, 38, 42, 46, 34, 38, 42, 46};
    private static final int[] T2_ARB_RTGY = { 3,  3,  3,  3,  7,  7,  7,  7};
    private static final int T2_ARB_RTG_N = 8;

    // ---- 014(T4)装具常量 ----
    // cls 场四站 y 锚(T/F/M/L = 塔区/工厂区/档1区/无电兜底);清区 x∈[10,11]×y∈[10,42](避开既有设施 (12,34))。
    private static final int[] T4_CLS_YS = {10, 20, 30, 40};
    // watch 场(48×48 重设计;fix-014:脊 16 全 node + RTG 2×2 侧排):岛1 横排 GW(18,37) x∈[18,33],
    // 岛2 纵排(转置同构)GW(38,32) y∈[32,47];脊柱长 16;node 列/行偏移 +0/+3/+6/+9/+12/+15;槽 50=10 偏移×5。
    // RTG 是 2×2 多方块(size=2;踩其 footprint 任一格=整块清除!)⇒ 侧排 3 台基座间距 2 防互踩:
    // 岛1 (19,36)(21,36)(23,36) / 岛2 (37,33)(37,35)(37,37);3×4.5=13.5 ⇒ 岛1 M45 r=0 / M50 D=15 r=0.10(≥R_WATCH 0.08)。
    // 吸链余量(字节码实证:圆=laserRange 6×8px vs 格 hitbox):岛2 GW(38,32)↔岛1 脊东端(33,38) 56.9px>48 为最紧对,余 ~1.1 格。
    private static final int T4_WATCH_X1 = 18, T4_WATCH_Y1 = 37;   // 岛1(横排)网关锚
    private static final int T4_WATCH_X2 = 38, T4_WATCH_Y2 = 32;   // 岛2(纵排)网关锚
    private static final int T4_WATCH_SPAN = 16;
    private static final int T4_WATCH_SLOTS = 50;
    private static final int T4_WATCH_M1 = 45;   // 岛1 默认(3RTG=13.5 ⇒ r≈0)
    private static final int T4_WATCH_M2 = 40;   // 岛2 默认
    private static final int[] T4_WATCH_SLOTOFF = {1, 2, 4, 5, 7, 8, 10, 11, 13, 14};
    // grant 场(D48):A 单 RTG(34,4,贴行 y=5;供给 4.5 ⇒ fStar≈1.455<1.5 落死区);
    // kload 复用 T2_OWN 位(上限 T2_OWN_MAX);清区 A y∈[0,8] / B 站 y∈[11,16](网关行 y=10 不动)。
    private static final int T4_GRANT_RTGX = 34, T4_GRANT_RTGY = 4;
    // perf 场(48×48 重设计):6 组 8 gw,组锚 {x, y, 双站标志},2 列(x=10/29)×3 行(y=0/14/28),站距 8>6。
    // 清区 x∈[10,47]×y∈[0,47]:段 5 跑在全部 T4 段之后,清光 cls/watch/grant/遗留(注销的旧岛不进 diff,
    // 新键只增 perf 岛 ⇒ run014 段5「新增岛==回显」断言稳过)。双站组 = 第 1、4 组(gx 与 gx+11)。
    private static final int T4_PERF_CX0 = 10, T4_PERF_CX1 = 47, T4_PERF_CY1 = 47;
    private static final int T4_PERF_M = 40;
    private static final int[][] T4_PERF_SITES = {
        {10,  0, 1}, {29,  0, 0},
        {10, 14, 0}, {29, 14, 1},
        {10, 28, 0}, {29, 28, 0},
    };
    // t4shed 场(015;T4-3/4/6/7/8 校准):四站竖排,gw 居右列(x0+8),成员/节点居左 8 列。
    // x0=0 对地图本体净(基线 5 处 ≥9 格),但与 t4cls 列(同一会话内可为 x=10)仅 2 格 ⇒ 校准/全序列用 x0=39 净场列(与一切既有结构 ≥9 格,与建造顺序无关)。
    // 015cal4 几何重修(v5;字节码实证后替换 cal3 排布,gw 从 Y+4 行移到 Y+0):
    //   站型 L8 = 行[Y..Y+6]:节点行 Y+1/3/5 整行 9 枚,成员行 Y+0/2/4/6 ×8 槽 = 32。
    //   站型 L9 = 行[Y..Y+8]:多节点行 Y+7 与成员行 Y+8 ⇒ 40 槽(仅 Z1=40 灯用)。
    //   连通双保险:成员每格 4 邻某节点行(邻接并图 onProximityAdded→updatePowerGraph 恒成立);
    //     且 placed()→getNodeLinks 连线路(成员 6 格内数十节点、每节点 maxNodes=3 链槽)。
    //   摆序:gw(x0+8,Y) →(钻/RTG)→ 逐偶行 dy[非末行先节点行 Y+dy+1 → 成员行 Y+dy];全格先查空(t.build!=null 跳过)。
    //   gw 在 Y 行靠 4 邻节点 (x0+8,Y+1) 入网(connectedPower=false ⇒ 自身 placed() 不放线;作为候选被扫时落 ensureModLink,非 power.links)。
    // 跨站隔离(红线):相邻站间隙恒 7 格 = 56px > 节点射程 6 格 = 48px(linkValid overlaps 半径 = candidate.laserRange×8)
    //   ⇒ 四站 vanilla 图互不相连;Y={0,13,28,41} 即由此推得(L9 站 reach 到 Y+8,间距 13 不够)。
    // 区锚(实测口径 = 区内 graph.all 建筑 Point2.pack 最小者,x 主序)= Z0(39,1,RTG)/Z1(39,13,灯)/Z2(39,31,节点)/Z3(39,41,mender);
    //   岛键 = 四 gw min pack = SRC gw(x0+8,0)。
    // 015cal3 修复保留:全站走 t4SetPlaced(setNet 后补 Building.placed())——setNet 全链无 placed(),范围连线永不生成。
    // 每块耗电 = 字节码实证(Blocks$365/$272/$190 构造器 consumePower 直读):illuminator 0.05 / laserDrill 1.1 / mender 0.3
    // (mender 0.3 另经 014e 实测复证:grant 岛 D=5.40=18×0.3、watch 岛 D=1.50=5×0.3)。
    // 场景 freeze:Z1=40×0.05=2.00(可弃)/ Z2=2×1.1=2.20(生产)/ Z3=7×0.3=2.10(防御)⇒ D_all=6.30;
    // SRC RTG 三槽 2×2 底左锚 (x0, Y+1+2i):off=0 台 / base=1 台(4.5 − 3.0 = +1.5)/ boost=3 台(13.5 − 3.0 = +10.5)。
    // 修-015cal2:M_SRC 12→10 —— 12 时 S_boost=9.9 < Z1 恢复预演算 (7.9+2.0)×1.02=10.098 ⇒ restoreTotal 永卡 2;
    // 10 时 S_boost=10.5,三区预演算 5.202/7.446/9.486 全过(T4-4 可达 restoreTotal=3;T4-3 各门不受影响)。
    private static final int T4_SHED_X0 = 0, T4_SHED_X1 = 8;     // 站列范围(含)
    private static final int[] T4_SHED_Y = {0, 13, 28, 41};      // 四站基行:0=SRC / 1=Z1 / 2=Z2 / 3=Z3
    private static final int T4_SHED_GWX = 8;                    // gw x(=X1;行 = Y+0)
    private static final int T4_SHED_RTG_DY0 = 1;                // RTG 槽 0 底左锚行偏移(槽 i 锚 (x0, Y+DY0+2i))
    private static final int T4_SHED_RTG_SLOTS = 3;              // RTG 槽数(= 满发=boost 档)
    private static final int T4_SHED_RTG_MAX = 3;
    private static final int T4_SHED_M_SRC = 10;                 // SRC 自载 mender(10×0.3=3.0;1 RTG 4.5 ⇒ +1.5;修-015cal2)
    private static final int T4_SHED_N1_DEF = 40;                // Z1 默认台数(illuminator 0.05 ⇒ 2.00;L9 满槽)
    private static final int T4_SHED_N2_DEF = 2;                 // Z2 默认台数(laserDrill 1.1 ⇒ 2.20)
    private static final int T4_SHED_N3_DEF = 7;                 // Z3 默认台数(mender 0.3 ⇒ 2.10)
    private static final int T4_SHED_CAP = 40;                   // 1×1 成员槽上限(L9 满槽 5×8;L8=32)
    private static final int T4_SHED_SOLO_X0 = 20, T4_SHED_SOLO_Y0 = 1;   // solo 独立站(B4 区;L8;gw=(28,1);清区 (20,0)-(28,8))
    private static final int T4_SHED_SOLO_M = 7;                 // solo 默认 mender 数(T4-8 边界:7×0.3=2.1 自洽)

    /** bs-lab-t2 入口:按 op 分发(build / link / probe)。 */
    // 012(D26)追加:load / battery / sample 三分支(既有 build/link/probe 分支一字不动)。
    // 013(D37)追加:arb(摆场)/ zstats(按岛统计出口)两分支(既有分支行不动)。
    public static void t2(String[] args){
        String op = args[0];
        if("build".equals(op)){
            t2Build(args);
        }else if("link".equals(op)){
            t2Link(args);
        }else if("probe".equals(op)){
            if(args.length < 2){
                Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 probe <jsonPath>");
                return;
            }
            t2Probe(args[1]);
        }else if("load".equals(op)){
            t2Load(args);
        }else if("battery".equals(op)){
            t2Battery(args);
        }else if("sample".equals(op)){
            t2Sample(args);
        }else if("arb".equals(op)){
            t2Arb(args);
        }else if("zstats".equals(op)){
            if(args.length < 2){
                Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 zstats <jsonPath>");
                return;
            }
            t2Zstats(args[1]);
        }else if("t4cls".equals(op)){
            t4Cls();
        }else if("t4rec".equals(op)){
            t4Rec(args);
        }else if("t4watch".equals(op)){
            t4Watch();
        }else if("t4menders".equals(op)){
            t4Menders(args);
        }else if("t4grant".equals(op)){
            t4Grant(args);
        }else if("t4perf".equals(op)){
            t4Perf();
        }else if("t4map".equals(op)){
            t4Map();
        }else if("t4shed".equals(op)){
            t4Shed(args);
        }else if("prot".equals(op)){
            prot(args);
        }else if("bpcreate".equals(op)){
            bpCreate(args);
        }else if("bpclear".equals(op)){
            bpCache = null;
            Log.info("[bs-lab] bp.clear ok");
        }else if("bprotate".equals(op)){
            bpRotate(args);
        }else if("bpser".equals(op)){
            bpSerialize();
        }else if("bpplace".equals(op)){
            bpPlace(args);
        }else if("bpdump".equals(op)){
            bpDump(args);
        }else if("bvis".equals(op)){
            bpVisual(args);
        }else{
            Log.info("[bs-lab] t2 ERROR 未知 op '" + op + "'(期望 build|link|probe|load|battery|sample|arb|zstats)");
        }
    }

    // 017:七个无路径 op 只按冻结表回显,不写 JSON/done;既有带路径 op 保持原纪律。
    private static Schematic bpCache;

    /** 命令期解析整数;不进入帧回调,不修改既有 op 的参数/输出面。 */
    private static int[] bpInts(String[] args, int count, String op){
        if(args.length != count + 1){
            Log.info("[bs-lab] " + op + " ERROR 参数数量错误");
            return null;
        }
        int[] values = new int[count];
        try{
            for(int i = 0; i < count; i++) values[i] = Integer.parseInt(args[i + 1]);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] " + op + " ERROR 参数非整数");
            return null;
        }
        return values;
    }

    /** 两角归一化并裁到世界;双层扫描按建筑身份去重,多格块只报一次。 */
    private static int[] bpArea(String[] args, String op){
        int[] v = bpInts(args, 4, op);
        if(v == null) return null;
        if(Vars.world == null || Vars.world.width() == 0 || Vars.world.height() == 0){
            Log.info("[bs-lab] " + op + " ERROR 世界未加载");
            return null;
        }
        int x1 = Math.max(0, Math.min(v[0], v[2]));
        int y1 = Math.max(0, Math.min(v[1], v[3]));
        int x2 = Math.min(Vars.world.width() - 1, Math.max(v[0], v[2]));
        int y2 = Math.min(Vars.world.height() - 1, Math.max(v[1], v[3]));
        if(x1 > x2 || y1 > y2){
            Log.info("[bs-lab] " + op + " ERROR 区域在世界外");
            return null;
        }
        v[0] = x1; v[1] = y1; v[2] = x2; v[3] = y2;
        return v;
    }

    /** headless 的 create 同样过滤 wasVisible;仅命令期置可见,命令期局部兜底实例(§0.3-18),不回写全局。 */
    private static void bpCreate(String[] args){
        int[] v = bpArea(args, "bp.create");
        if(v == null) return;
        for(int x = v[0]; x <= v[2]; x++){
            for(int y = v[1]; y <= v[3]; y++){
                Building b = Vars.world.build(x, y);
                if(b != null) b.wasVisible = true;
            }
        }
        // 无头/dedicated 下 Vars.schematics 恒为 null(引擎仅客户端路径构造,§0.3-18);命令期局部兜底,不回写全局
        Schematics sch = Vars.schematics != null ? Vars.schematics : new Schematics();
        bpCache = sch.create(v[0], v[1], v[2], v[3]);
        Log.info("[bs-lab] bp.create w=" + bpCache.width + " h=" + bpCache.height + " tiles=" + bpCache.tiles.size);
    }

    private static boolean bpReady(String op){
        if(bpCache != null) return true;
        Log.info("[bs-lab] " + op + " ERROR 缓图为空");
        return false;
    }

    private static void bpRotate(String[] args){
        int[] v = bpInts(args, 1, "bp.rotate");
        if(v == null || !bpReady("bp.rotate")) return;
        bpCache = Schematics.rotate(bpCache, v[0]);
        Log.info("[bs-lab] bp.rotate times=" + v[0] + " w=" + bpCache.width + " h=" + bpCache.height
            + " tiles=" + bpCache.tiles.size);
    }

    /** 内存流往返;read 成功后才替换缓图,失败沿冻结的 err 行返回。 */
    private static void bpSerialize(){
        if(bpCache == null){
            Log.info("[bs-lab] bp.ser err=缓图为空");
            return;
        }
        try(ByteArrayOutputStream out = new ByteArrayOutputStream()){
            Schematics.write(bpCache, out);
            try(ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray())){
                bpCache = Schematics.read(in);
            }
            Log.info("[bs-lab] bp.ser ok tiles=" + bpCache.tiles.size);
        }catch(Exception e){
            // err 保持单行,异常消息不另行侵入既有 op 日志。
            String msg = String.valueOf(e.getMessage()).replace('\n', ' ').replace('\r', ' ');
            Log.info("[bs-lab] bp.ser err=" + msg);
        }
    }

    /** place 的 x/y 为蓝图中心:字节码偏移 x-width/2、y-height/2;不补 placed()。 */
    private static void bpPlace(String[] args){
        int[] v = bpInts(args, 2, "bp.place");
        if(v == null || !bpReady("bp.place")) return;
        if(Vars.world == null || Vars.world.width() == 0 || Vars.world.height() == 0){
            Log.info("[bs-lab] bp.place ERROR 世界未加载");
            return;
        }
        int x0 = v[0] - bpCache.width / 2, y0 = v[1] - bpCache.height / 2;
        Schematics.place(bpCache, v[0], v[1], Team.sharded);
        int placed = 0;
        IntSet seen = new IntSet();
        for(Schematic.Stile tile : bpCache.tiles){
            Building b = Vars.world.build(x0 + tile.x, y0 + tile.y);
            if(b != null && b.block == tile.block && b.team == Team.sharded
                && b.tileX() == x0 + tile.x && b.tileY() == y0 + tile.y && seen.add(b.pos())) placed++;
        }
        Log.info("[bs-lab] bp.place x=" + v[0] + " y=" + v[1] + " placed=" + placed + " bbox="
            + x0 + "," + y0 + "," + (x0 + bpCache.width - 1) + "," + (y0 + bpCache.height - 1));
    }

    private static void bpDump(String[] args){
        int[] v = bpArea(args, "bp.dump");
        if(v == null) return;
        IntSet seen = new IntSet();
        int count = 0;
        for(int x = v[0]; x <= v[2]; x++){
            for(int y = v[1]; y <= v[3]; y++){
                Building b = Vars.world.build(x, y);
                if(b == null || !seen.add(b.pos())) continue;
                Log.info("[bs-lab] bp.ent x=" + b.tileX() + " y=" + b.tileY() + " block=" + b.block.name
                    + " rot=" + Math.floorMod(b.rotation, 4) + " cfg=" + bpConfig(b.config()));
                count++;
            }
        }
        Log.info("[bs-lab] bp.dump n=" + count);
    }

    /** 原样编码 config() 返回值:网关相对向量、装置哨兵均不做坐标转换。 */
    private static String bpConfig(Object config){
        if(config instanceof Point2[] points){
            StringBuilder out = new StringBuilder("P[");
            for(int i = 0; i < points.length; i++){
                if(points[i] == null) return "-";
                if(i > 0) out.append(';');
                out.append('(').append(points[i].x).append(',').append(points[i].y).append(')');
            }
            return out.append(']').toString();
        }
        if(config instanceof Boolean value) return value ? "B:1" : "B:0";
        if(config instanceof Integer value) return "I:" + value;
        return "-";
    }

    private static String bpFloat(float value){
        return Float.isNaN(value) ? "nan" : Float.toString(value);
    }

    /** 接口 9 项直接读;不反射字段,链路序号以 modLinks.size 为边界。 */
    private static void bpVisual(String[] args){
        int[] v = bpInts(args, 2, "bvis");
        if(v == null) return;
        Building b = Vars.world == null ? null : Vars.world.build(v[0], v[1]);
        if(!(b instanceof BsVisualData data)){
            Log.info("[bs-lab] bvis none");
            return;
        }
        Log.info("[bs-lab] bvis class=" + b.getClass().getSimpleName() + " kind=" + data.bsDeviceKind()
            + " switch=" + data.bsSwitchState() + " reclose=" + bpFloat(data.bsRecloseProgress())
            + " recloseLeft=" + data.bsRecloseLeft() + " throttled=" + data.bsGraphThrottled());
        if(b instanceof GatewayNode.GatewayNodeBuild gw){
            for(int i = 0; i < gw.modLinks.size; i++){
                int state = data.bsLinkState(i);
                Log.info("[bs-lab] bvis.link i=" + i + " flow=" + bpFloat(data.bsLinkFlow(i))
                    + " cap=" + bpFloat(data.bsLinkCapacity(i)) + " loss=" + bpFloat(data.bsLinkLossPct(i))
                    + " state=" + (state < 0 ? "-" : Integer.toString(state)));
            }
        }
    }

    /** build <kLoad> <kOwn> [nosrc]:先校验后动手,再清区 + 摆场。 */
    private static void t2Build(String[] args){
        if(args.length < 3){
            Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 build <kLoad> <kOwn> [nosrc]");
            return;
        }
        int kLoad, kOwn;
        try{
            kLoad = Integer.parseInt(args[1]);
            kOwn = Integer.parseInt(args[2]);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] t2 ERROR 参数非整数 kLoad=" + args[1] + " kOwn=" + args[2]);
            return;
        }
        boolean nosrc = args.length > 3 && "nosrc".equals(args[3]);

        // 先校验后动手(禁止「能摆多少算多少」)
        if(args.length > 3 && !"nosrc".equals(args[3])){
            Log.info("[bs-lab] t2 ERROR 第 4 参只接受 nosrc: " + args[3]);
            return;
        }
        if(kLoad < 0 || kLoad > T2_LOAD_MAX){
            Log.info("[bs-lab] t2 ERROR kLoad 越界(0.." + T2_LOAD_MAX + "): " + kLoad);
            return;
        }
        if(kOwn < 0 || kOwn > T2_OWN_MAX){
            Log.info("[bs-lab] t2 ERROR kOwn 越界(0.." + T2_OWN_MAX + "): " + kOwn);
            return;
        }
        if(Vars.world == null){
            Log.info("[bs-lab] t2 ERROR 世界未加载");
            return;
        }

        // 1) 清区(只清区内 build != null 的 tile;区外一律不碰)
        for(int x = T2_X0; x <= T2_X1; x++){
            for(int y = T2_Y0; y <= T2_Y1; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }

        // 2) 摆场(A 站 → B 站;全部 Team.sharded)
        if(!nosrc) t2Set(T2_SRCX, T2_SRCY, Blocks.powerSource);
        t2Set(T2_AX, T2_AY, BlackstartBlocks.gatewayNode);
        for(int k = 0; k < kOwn; k++){
            t2Set(t2OwnX(k), t2OwnY(k), Blocks.mender);
        }
        // A 站补网(见区头注):(39,1)(39,2) 竖链 + (42..47,2) 横链 + (39..47,3) 横链 ⇒ 全站一连通;
        // (42..47,1) 经 (4x,2) 入网,(40,2) 经 (39,2)/(40,3)、(41,2) 经 (41,3) 入网;
        // (40,1) 另有源、(41,1) 另有网关(nosrc 时 (41,1) 只连网关,与旧行为一致)
        t2Set(39, 1, Blocks.powerNode);
        t2Set(39, 2, Blocks.powerNode);
        for(int x = 42; x <= 47; x++) t2Set(x, 2, Blocks.powerNode);
        for(int x = 39; x <= 47; x++) t2Set(x, 3, Blocks.powerNode);
        t2Set(T2_BX, T2_BY, BlackstartBlocks.gatewayNode);
        // B 站负载区:node 列(3 格周期)网格化,各列经底行 y=32 互连 ⇒ kLoad 个 mender 全入同一图;
        // 摆序无碍:引擎每格落成即与既有邻块并图,后续邻块再并,最终单图收敛(已按 4 向逐格验过)
        int placed = 0;
        for(int y = 11; y <= 31; y++){
            for(int x = T2_X0; x <= T2_X1; x++){
                if(t2BNodeCol(x)){
                    t2Set(x, y, Blocks.powerNode);
                }else if(placed < kLoad){
                    t2Set(x, y, Blocks.mender);
                    placed++;
                }
            }
        }
        for(int x = T2_X0; x <= T2_X1; x++){
            t2Set(x, 32, Blocks.powerNode);
        }

        Log.info("[bs-lab] t2 build ok kLoad=" + kLoad + " kOwn=" + kOwn + " src=" + (nosrc ? 0 : 1));
    }

    /** link <on|off>:on = A 网关 ensureModLink(packed(33,10))(幂等);off = clearModLinks()。 */
    private static void t2Link(String[] args){
        if(args.length < 2){
            Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 link <on|off>");
            return;
        }
        String mode = args[1];
        Building b = Vars.world == null ? null : Vars.world.build(Point2.pack(T2_AX, T2_AY));
        if(!(b instanceof GatewayNode.GatewayNodeBuild gw)){
            Log.info("[bs-lab] t2 ERROR 未找到 A 网关 build @(" + T2_AX + "," + T2_AY + ")");
            return;
        }
        int packed = Point2.pack(T2_BX, T2_BY);
        if("on".equals(mode)){
            gw.ensureModLink(packed);
        }else if("off".equals(mode)){
            gw.clearModLinks();
        }else{
            Log.info("[bs-lab] t2 ERROR link 参数须为 on|off: " + mode);
            return;
        }
        Log.info("[bs-lab] t2 link " + mode + " ok modlinks=" + gw.modLinks.size);
    }

    // ============================================================
    // 012(D26)装具三 op:load / battery / sample
    // ============================================================

    /**
     * load &lt;k&gt;:把 B 站 mender 数调整为 k(k∈[0,220])—— 行主序遍历 y=11..31 × x=33..47(与 build 同序,
     * 跳过 node 列这一结构性网格):负载位 i&lt;k 的空格补 mender、i≥k 的多余 mender 清成空。
     * 只动本站在册负载位,其他类型建筑一律不碰;不动 (33,10) 网关与 A 站。
     * 用途:H4 撤负载 / H7 阶跃(load 200)/ 抖动(load 50↔51)。
     */
    private static void t2Load(String[] args){
        if(args.length < 2){
            Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 load <k>");
            return;
        }
        int k;
        try{
            k = Integer.parseInt(args[1]);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] t2 ERROR 参数非整数 k=" + args[1]);
            return;
        }
        if(k < 0 || k > T2_LOAD_KMAX){
            Log.info("[bs-lab] t2 ERROR k 越界(0.." + T2_LOAD_KMAX + "): " + k);
            return;
        }
        if(Vars.world == null){
            Log.info("[bs-lab] t2 ERROR 世界未加载");
            return;
        }

        int slot = 0;
        for(int y = 11; y <= 31; y++){
            for(int x = T2_X0; x <= T2_X1; x++){
                if(t2BNodeCol(x)) continue;                     // node 列:结构性网格,不是负载位,不碰
                Tile t = Vars.world.tile(x, y);
                if(t == null){ slot++; continue; }
                boolean isMender = t.build != null && t.build.block == Blocks.mender;
                if(slot < k){
                    // 空格 → mender;已是 mender → 保持;其他类型建筑不碰
                    if(t.build == null) t.setNet(Blocks.mender, Team.sharded, 0);
                }else{
                    // 多退:多余的 mender 清成空;其他类型建筑不碰
                    if(isMender) t.setNet(Blocks.air, Team.derelict, 0);
                }
                slot++;
            }
        }
        Log.info("[bs-lab] t2 load ok k=" + k);
    }

    /**
     * battery &lt;a|b&gt; &lt;k&gt;:把 A/B 站原版 {@code Blocks.battery} 数调整为 k(多退少补)。
     * A 站 12 位 = (42..47,0) + (42..47,2)(行主序,避开全部 kOwn 位);B 站 15 位 = y=9 行 x=33..47。
     * 目标格已有【非 battery】建筑不碰 —— 唯一例外:A 站 row2 的 6 位在 011 修复轮里是补网 powerNode,
     * 那是本装具的设计底衬(underlay):放置电池前允许替换该 node,退电池时还原该 node,保证 mesh 往返不丢。
     */
    private static void t2Battery(String[] args){
        if(args.length < 3){
            Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 battery <a|b> <k>");
            return;
        }
        String side = args[1];
        int k;
        try{
            k = Integer.parseInt(args[2]);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] t2 ERROR 参数非整数 k=" + args[2]);
            return;
        }
        if(!"a".equals(side) && !"b".equals(side)){
            Log.info("[bs-lab] t2 ERROR side 须为 a|b: " + side);
            return;
        }
        int max = "a".equals(side) ? T2_ABAT_MAX : T2_BBAT_MAX;
        if(k < 0 || k > max){
            Log.info("[bs-lab] t2 ERROR k 越界(" + side + " 0.." + max + "): " + k);
            return;
        }
        if(Vars.world == null){
            Log.info("[bs-lab] t2 ERROR 世界未加载");
            return;
        }

        boolean isA = "a".equals(side);
        for(int i = 0; i < max; i++){
            int x = isA ? T2_ABAT_X[i] : (T2_X0 + i);
            int y = isA ? T2_ABAT_Y[i] : T2_BBAT_Y;
            boolean mesh = isA && T2_ABAT_Y[i] == 2;              // A row2 = 011 补网 node 位
            Block under = mesh ? Blocks.powerNode : Blocks.air;   // 底衬(退电池时还原)
            Tile t = Vars.world.tile(x, y);
            if(t == null) continue;
            boolean isBattery = t.build != null && t.build.block == Blocks.battery;
            if(i < k){
                if(isBattery) continue;                           // 已是 battery → 保持
                // 空位 / 本槽底衬(mesh node)→ 放电池;其他类型建筑不碰
                if(t.build == null || t.build.block == under){
                    t.setNet(Blocks.battery, Team.sharded, 0);
                }
            }else{
                // 多退:电池 → 还原底衬(空地 air / mesh 位 powerNode);其他类型建筑不碰
                if(isBattery){
                    t.setNet(under, mesh ? Team.sharded : Team.derelict, 0);
                }
            }
        }
        Log.info("[bs-lab] t2 battery ok side=" + side + " k=" + k);
    }

    // ============================================================
    // 013(D37)arb 场 + zstats 出口
    // arb 场(A 站重摆):RTG×8(2×2,满燃料)= 36/tick 全进 A 图;A 网关(41,0) 三链指向 B 网关。
    // 连通性(中枢已验):上排 RTG 占据 y=3..4 贴 node 行 y=5;下排 RTG 占据 y=7..8 贴 node 行 y=6;
    // 两 node 行纵邻互连;脊柱 (40,0..4) 贴网关 (41,0) 与 y=5 行 ⇒ 8×4.5=36/tick 全进 A 图。
    // 摆法经实机校准(锚点 setNet,2×2 占锚点右上方各格;见 夜间报告.md)。
    // ============================================================

    /** arb <build|link|drop> [..]:先校验后动手;既有 op 行为零变化。 */
    private static void t2Arb(String[] args){
        if(args.length < 2){
            Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 arb <build|link|drop> [...]");
            return;
        }
        String sub = args[1];
        if("build".equals(sub)){
            t2ArbBuild();
        }else if("link".equals(sub)){
            t2ArbLink(args);
        }else if("drop".equals(sub)){
            t2ArbDrop(args);
        }else{
            Log.info("[bs-lab] t2 ERROR 未知 arb op '" + sub + "'(期望 build|link|drop)");
        }
    }

    /**
     * arb build:①清 A 区 x∈[33,47]×y∈[0,8];②摆网关 (41,0) + node 脊柱 (40,0..4) + node 行 y=5/y=6 全铺;
     * ③RTG 锚点 8 处(2×2,占锚点右上方各格)并补满燃料 thorium×10(itemCapacity=10);④确保 B 区
     * (37,10)(40,10) 为网关(已是网关则跳过,不重建;(33,10) 由既有 build 提供)。幂等可重跑。
     * 回显 `[bs-lab] t2 arb build ok rtg=8 gwB=3`。
     */
    private static void t2ArbBuild(){
        if(Vars.world == null){
            Log.info("[bs-lab] t2 ERROR 世界未加载");
            return;
        }

        // ① 清 A 区(只清区内 build != null 的 tile;区外一律不碰)
        for(int x = T2_X0; x <= T2_X1; x++){
            for(int y = T2_Y0; y <= T2_ARB_CLEAR_Y1; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }

        // ② 网关先落(此时 A 区已空 ⇒ placed() 扫描不到候选,modLinks 保持空),再摆 node 网
        t2Set(T2_AX, T2_AY, BlackstartBlocks.gatewayNode);
        for(int y = 0; y <= 4; y++) t2Set(T2_SRCX, y, Blocks.powerNode);      // 脊柱 (40,0..4)
        for(int x = T2_X0; x <= T2_X1; x++) t2Set(x, 5, Blocks.powerNode);    // 上 node 行
        for(int x = T2_X0; x <= T2_X1; x++) t2Set(x, 6, Blocks.powerNode);    // 下 node 行

        // ③ RTG ×8 + 补满燃料(单枚 840 tick≈14 s,10 枚≈140 s 覆盖整条验收序列)
        int rtg = 0;
        for(int k = 0; k < T2_ARB_RTG_N; k++){
            int x = T2_ARB_RTGX[k], y = T2_ARB_RTGY[k];
            Tile t = Vars.world.tile(x, y);
            if(t == null) continue;
            t.setNet(Blocks.rtgGenerator, Team.sharded, 0);
            Building b = t.build;
            if(b != null && b.block == Blocks.rtgGenerator){
                b.items.add(Items.thorium, 10);                              // 幂等:重跑同样补满
                rtg++;
            }
        }

        // ④ B 区网关(已是网关则跳过,不重建)
        int gwB = 0;
        for(int k = 0; k < T2_ARB_GWX.length; k++){
            int x = T2_ARB_GWX[k];
            Tile t = Vars.world.tile(x, T2_ARB_GWY);
            if(t == null) continue;
            if(!(t.build instanceof GatewayNode.GatewayNodeBuild)){
                t.setNet(BlackstartBlocks.gatewayNode, Team.sharded, 0);
            }
            if(Vars.world.build(Point2.pack(x, T2_ARB_GWY)) instanceof GatewayNode.GatewayNodeBuild) gwB++;
        }

        Log.info("[bs-lab] t2 arb build ok rtg=" + rtg + " gwB=" + gwB);
    }

    /**
     * arb link [seq]:seq 缺省 "012";字符 0→(33,10) / 1→(37,10) / 2→(40,10),按 seq 顺序对 A 网关
     * ensureModLink(幂等)——「交换建链顺序」由 seq 实现。seq 非法(非 0/1/2 或有重复)⇒ 报错且不动手。
     * 清链复用既有 `link off`(clearModLinks,不加新语义)。
     * 回显 `[bs-lab] t2 arb link ok seq=<seq> modlinks=<n>`。
     */
    private static void t2ArbLink(String[] args){
        String seq = args.length > 2 ? args[2] : "012";
        boolean[] seen = new boolean[3];
        for(int i = 0; i < seq.length(); i++){
            char c = seq.charAt(i);
            if(c < '0' || c > '2'){
                Log.info("[bs-lab] t2 ERROR arb link seq 非法(仅 0/1/2): " + seq);
                return;
            }
            int d = c - '0';
            if(seen[d]){
                Log.info("[bs-lab] t2 ERROR arb link seq 重复: " + seq);
                return;
            }
            seen[d] = true;
        }
        Building b = Vars.world == null ? null : Vars.world.build(Point2.pack(T2_AX, T2_AY));
        if(!(b instanceof GatewayNode.GatewayNodeBuild gw)){
            Log.info("[bs-lab] t2 ERROR 未找到 A 网关 build @(" + T2_AX + "," + T2_AY + ")");
            return;
        }
        for(int i = 0; i < seq.length(); i++){
            int d = seq.charAt(i) - '0';
            gw.ensureModLink(Point2.pack(T2_ARB_GWX[d], T2_ARB_GWY));
        }
        Log.info("[bs-lab] t2 arb link ok seq=" + seq + " modlinks=" + gw.modLinks.size);
    }

    /** arb drop &lt;i&gt;:i∈{0,1,2} → 对应 B 网关 tile 物理拆除(setNet(air))。回显 `[bs-lab] t2 arb drop ok i=<i>`。 */
    private static void t2ArbDrop(String[] args){
        if(args.length < 3){
            Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 arb drop <0|1|2>");
            return;
        }
        int i;
        try{
            i = Integer.parseInt(args[2]);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] t2 ERROR 参数非整数 i=" + args[2]);
            return;
        }
        if(i < 0 || i > 2){
            Log.info("[bs-lab] t2 ERROR arb drop i 越界(0..2): " + i);
            return;
        }
        if(Vars.world == null){
            Log.info("[bs-lab] t2 ERROR 世界未加载");
            return;
        }
        Tile t = Vars.world.tile(T2_ARB_GWX[i], T2_ARB_GWY);
        if(t == null){
            Log.info("[bs-lab] t2 ERROR arb drop 目标越界: (" + T2_ARB_GWX[i] + "," + T2_ARB_GWY + ")");
            return;
        }
        t.setNet(Blocks.air, Team.derelict, 0);
        Log.info("[bs-lab] t2 arb drop ok i=" + i);
    }

    /**
     * zstats &lt;jsonPath&gt;:dump 按岛统计(BlackstartLinks.Stats facade)。schema 键名冻结:
     * {@code {"schema":"blackstart.lab.t2.zstats","tick":N,"count":n,"islands":[{"key","S","D",
     * "zoneInflow","linkFlow","delivered"}...],"at":"..."}}。落盘照 probe 先例(原子写 + `[bs-lab] done`)。
     * (019 起 "zoneInflow" 的值为全链聚合到货;共享区在多岛条目中各出现一次且同值。)
     */
    private static void t2Zstats(String jsonPath){
        int count = BlackstartLinks.statsCount();
        Jval islands = Jval.newArray();
        for(int i = 0; i < count; i++){
            long key = BlackstartLinks.statsKeyAt(i);
            BlackstartLinks.Stats s = BlackstartLinks.stats(key);
            if(s == null) continue;
            Jval zi = Jval.newArray();
            for(int z = 0; z < s.perZoneInflow.length; z++) zi.add(s.perZoneInflow[z]);
            Jval lf = Jval.newArray();
            for(int z = 0; z < s.perLinkFlow.length; z++) lf.add(s.perLinkFlow[z]);
            Jval dl = Jval.newArray();
            for(int z = 0; z < s.deliveredFlow.length; z++) dl.add(s.deliveredFlow[z]);
            islands.add(Jval.newObject()
                .put("key", key)
                .put("S", s.S)
                .put("D", s.D)
                .put("zoneInflow", zi)
                .put("linkFlow", lf)
                .put("delivered", dl));
        }

        Jval root = Jval.newObject();
        root.put("schema", "blackstart.lab.t2.zstats");
        root.put("tick", Vars.state == null ? 0L : (long)Vars.state.tick);
        root.put("count", count);
        root.put("islands", islands);
        root.put("at", t2Timestamp());

        try{
            writeJson(jsonPath, root.toString(Jval.Jformat.plain));
        }catch(Throwable t){
            Log.err("[bs-lab] 写文件失败 " + jsonPath + " : " + t);
        }finally{
            Log.info("[bs-lab] done " + jsonPath);
        }
    }

    // ============================================================
    // 014(T4)装具 op:map / cls / rec / watch / menders / grant / perf
    // 目标:为 T4 安全网 headless 验收(run014.py)建场;全部幂等可重跑。
    // 几何约束出自 SafetyNet 实码:岛键 = 岛内网关 min packed pos;区锚 = graph.all 内
    // min packed pos;同图双网关不并岛 ⇒ perf 场连通必须走跨图链;消费者不接力 ⇒
    // 每 mender 直贴 node。本类只被 bs-lab-t2 命令调用,零 UI 依赖(无头安全)。
    // ============================================================

    /**
     * map:诊断探针(首跑校准用)——回显地图宽高 + 全图统计(网关坐标清单/RTG/mender/
     * powerSource/powerNode 计数)+ 关键坐标占用点检 + 全图占用 dump
     * (occ=有 build 的格,blk=非空但无 build 的方块格;各截前 250 条,计数为全量)。零写入。
     */
    private static void t4Map(){
        if(Vars.world == null){
            Log.info("[bs-lab] t4map ERROR 世界未加载");
            return;
        }
        int w = Vars.world.width(), h = Vars.world.height();
        int gw = 0, rtg = 0, mender = 0, src = 0, node = 0;
        StringBuilder gws = new StringBuilder();
        for(int x = 0; x < w; x++){
            for(int y = 0; y < h; y++){
                Tile t = Vars.world.tile(x, y);
                if(t == null || t.build == null) continue;
                Block b = t.build.block;
                if(b == BlackstartBlocks.gatewayNode){
                    gw++;
                    if(gw <= 64) gws.append(" (").append(x).append(",").append(y).append(")");
                }else if(b == Blocks.rtgGenerator){
                    rtg++;
                }else if(b == Blocks.mender){
                    mender++;
                }else if(b == Blocks.powerSource){
                    src++;
                }else if(b == Blocks.powerNode){
                    node++;
                }
            }
        }
        StringBuilder key = new StringBuilder();
        int[][] pts = {{40, 0}, {41, 0}, {33, 10}, {37, 10}, {40, 10}, {10, 10}, {18, 37}, {38, 32}, {10, 0}};
        for(int[] p : pts){
            Tile t = Vars.world.tile(p[0], p[1]);
            String name = (t == null || t.build == null) ? "air" : t.build.block.name;
            key.append(" (").append(p[0]).append(",").append(p[1]).append(")=").append(name);
        }
        StringBuilder occ = new StringBuilder();
        StringBuilder blk = new StringBuilder();
        int occN = 0, blkN = 0;
        for(int x = 0; x < w; x++){
            for(int y = 0; y < h; y++){
                Tile t = Vars.world.tile(x, y);
                if(t == null) continue;
                if(t.build != null){
                    if(occN < 250){
                        if(occN > 0) occ.append(' ');
                        occ.append('(').append(x).append(',').append(y).append(")=").append(t.build.block.name);
                    }
                    occN++;
                }else if(t.block() != Blocks.air){
                    if(blkN < 250){
                        if(blkN > 0) blk.append(' ');
                        blk.append('(').append(x).append(',').append(y).append(")=").append(t.block().name);
                    }
                    blkN++;
                }
            }
        }
        Log.info("[bs-lab] t4map ok w=" + w + " h=" + h + " gw=" + gw + " rtg=" + rtg
            + " mender=" + mender + " src=" + src + " node=" + node);
        Log.info("[bs-lab] t4map gates:" + gws);
        Log.info("[bs-lab] t4map key:" + key);
        Log.info("[bs-lab] t4map occ n=" + occN + ":" + occ);
        Log.info("[bs-lab] t4map blk n=" + blkN + ":" + blk);
    }

    /**
     * shed:四站甩/复场(T4-3/4/6/7/8;015 中枢追加,测试侧)。子命令:
     * build [n1.n2.n3] [x0] —— 清 x∈[x0,x0+8]×y∈[0,47] 后摆四站(SRC 固定 10 mender+1 RTG;Z1 illuminator×n1 /
     * Z2 laserDrill×n2(3×3 槽 x∈{0,3,6})/ Z3 mender×n3;缺省 {@link #T4_SHED_N1_DEF}.{@link #T4_SHED_N2_DEF}.{@link #T4_SHED_N3_DEF},
     * x0 缺省 {@link #T4_SHED_X0};015 校准:x0=39 供 T4-7 净场列,避开 x∈[0,8] 列的翻转台账)
     * + 三链(SRC gw → 各 Z gw)+ markDirty;
     * src off|base|boost [x0] —— SRC 站重摆 0/1/3 台 RTG(每台 thorium 10 满燃料,兼作补燃料;x0 缺省 {@link #T4_SHED_X0});
     * solo [m] —— (20,1) 起独立站(gw+3 node+m 个 mender,无链;T4-8 边界,缺省 7);
     * save [name] —— SaveIO.write 写 Vars.saveDirectory/&lt;name&gt;.msav(T4-7 往返;缺省 t4shed)。
     * n1.n2.n3 收敛为单 token:lab 命令层注册上限 = op+3 token(同 t4rec 注)。
     */
    private static void t4Shed(String[] args){
        if(args.length < 2){
            Log.info("[bs-lab] t4shed ERROR 用法: bs-lab-t2 t4shed <build [n1.n2.n3] [x0]|src <off|base|boost> [x0]|solo [m]|save [name]>");
            return;
        }
        String op = args[1];
        if("build".equals(op)){
            int n1 = T4_SHED_N1_DEF, n2 = T4_SHED_N2_DEF, n3 = T4_SHED_N3_DEF;
            int x0 = T4_SHED_X0;
            if(args.length >= 3){
                int[] v = t4ShedParse3(args[2]);
                if(v == null){
                    Log.info("[bs-lab] t4shed ERROR build 参数须为 n1.n2.n3(如 40.2.7): " + args[2]);
                    return;
                }
                n1 = v[0]; n2 = v[1]; n3 = v[2];
            }
            if(args.length >= 4){
                try{
                    x0 = Integer.parseInt(args[3]);
                }catch(NumberFormatException e){
                    Log.info("[bs-lab] t4shed ERROR build x0 非整数: " + args[3]);
                    return;
                }
            }
            t4ShedBuild(n1, n2, n3, x0);
        }else if("src".equals(op)){
            if(args.length < 3){
                Log.info("[bs-lab] t4shed ERROR 用法: bs-lab-t2 t4shed src <off|base|boost> [x0]");
                return;
            }
            int x0 = T4_SHED_X0;
            if(args.length >= 4){
                try{
                    x0 = Integer.parseInt(args[3]);
                }catch(NumberFormatException e){
                    Log.info("[bs-lab] t4shed ERROR src x0 非整数: " + args[3]);
                    return;
                }
            }
            t4ShedSrc(args[2], x0);
        }else if("solo".equals(op)){
            int m = T4_SHED_SOLO_M;
            if(args.length >= 3){
                try{
                    m = Integer.parseInt(args[2]);
                }catch(NumberFormatException e){
                    Log.info("[bs-lab] t4shed ERROR solo 参数非整数: " + args[2]);
                    return;
                }
            }
            t4ShedSolo(m);
        }else if("save".equals(op)){
            t4ShedSave(args.length >= 3 ? args[2] : "t4shed");
        }else{
            Log.info("[bs-lab] t4shed ERROR 未知子命令 '" + op + "'(期望 build|src|solo|save)");
        }
    }

    /** "n1.n2.n3" → 三整数;格式错/非整数返回 null。 */
    private static int[] t4ShedParse3(String s){
        String[] p = s.split("\\.");
        if(p.length != 3) return null;
        try{
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])};
        }catch(NumberFormatException e){
            return null;
        }
    }

    /** 清矩形(只清 build != null 的 tile;惯例同 t4Cls)。 */
    private static void t4ShedClear(int x0, int y0, int x1, int y1){
        for(int x = x0; x <= x1; x++){
            for(int y = y0; y <= y1; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }
    }

    /** 站内可摆检查:目标格存在且空(build==null)。 */
    private static boolean t4ShedEmpty(int x, int y){
        Tile t = Vars.world.tile(x, y);
        return t != null && t.build == null;
    }

    /** SRC 保留带:reserveRtg 时 x∈{x0,x0+1} && y∈[y0,y0+6] 全跳过(RTG 三槽 2×2 底左锚占地)。 */
    private static boolean t4ShedReserved(boolean reserveRtg, int x0, int y0, int x, int y){
        return reserveRtg && x <= x0 + 1 && y <= y0 + 6;
    }

    /** 落一枚站内节点(保留带/已占格跳过)。 */
    private static void t4ShedNode(boolean reserveRtg, int x0, int y0, int x, int y){
        if(t4ShedReserved(reserveRtg, x0, y0, x, y)) return;
        if(!t4ShedEmpty(x, y)) return;
        t4SetPlaced(x, y, Blocks.powerNode);
    }

    /**
     * 摆一站(015cal4 v5 几何;站基 (x0,y0),域 9 宽:L8 = 行 Y..Y+6 / L9 = 行 Y..Y+8):
     *   gw(x0+8,y0+0) 先落 → [size≥3:钻 3 槽,锚 = 3×3 中心 (x0+1,y0+1)/(x0+5,y0+1)/(x0+1,y0+4)]
     *   → 逐偶行 dy:非末行先摆节点行 y0+dy+1(x0..x0+8 整行),再摆成员行 y0+dy(x0..x0+7)。
     * 成员每格 4 邻某节点行(邻接并图路径恒成立);且 6 格内有数十节点、每节点 3 链槽
     * (placed()→getNodeLinks 连线路)⇒ 入网双保险(机制全链见 015cal4 javap 实证)。
     * reserveRtg = SRC:x∈{x0,x0+1}×y∈[y0,y0+6] 全跳过(RTG 槽位,见 t4ShedRtg)。
     * deep = L9(仅 Z1:dy 0..8 ⇒ 40 成员槽);L8:dy 0..6 ⇒ 32 槽。
     * 全部走 t4SetPlaced;每格先查空(钻/RTG 占地自动跳过,钻区节点行照摆留见证节点)。
     * 钻区(015cal5)额外两件:slot 3×3 足迹铺铜矿 overlay(dominantItem 来源)+ (x0+3,y0+2) 放
     *   itemVoid 排料口(邻 slot0/slot1 足迹,双钻共用)保持空仓 —— 钻耗电走 vanilla 需求口径
     *   DrillBuild.shouldConsume() = items.total<cap && enabled && dominantItem!=null(字节码实证):
     *   无矿不申报、满仓不申报。缺这两件时钻 need=0,D 少 2.20、tier2 那一刀整段消失(015cal4 实测)。
     * 序:先 void 后钻(钻 placed 时 proximity 已含 void,dump 直投);矿 overlay 后铺,再显式
     *   onProximityUpdate() 重算 countOre(setOverlay 自带回调,但重建轮 overlay 已同值早退,需兜底)。
     * 返回实摆成员数(钻区 = 实摆钻数)。
     */
    private static int t4ShedFill(int x0, int y0, Block blk, int n, boolean reserveRtg, boolean deep){
        if(n <= 0) return 0;
        if(t4ShedEmpty(x0 + T4_SHED_GWX, y0)){
            t4SetPlaced(x0 + T4_SHED_GWX, y0, BlackstartBlocks.gatewayNode);
        }
        boolean big = blk.size >= 3;
        int placed = 0;
        if(big){
            if(blk == Blocks.laserDrill && t4ShedEmpty(x0 + 3, y0 + 2)){
                t4SetPlaced(x0 + 3, y0 + 2, Blocks.itemVoid);  // 双钻共用排料口(邻 slot0 (x0+2,y0+2) 与 slot1 (x0+4,y0+2) 足迹)
            }
            int[][] slots = {{1, 1}, {5, 1}, {1, 4}};
            for(int i = 0; i < slots.length && placed < n; i++){
                int bx = x0 + slots[i][0], by = y0 + slots[i][1];
                if(!t4ShedEmpty(bx, by)) continue;
                t4SetPlaced(bx, by, blk);
                if(t2IsBlock(bx, by, blk)){
                    placed++;
                    if(blk == Blocks.laserDrill){
                        for(int x = bx - 1; x <= bx + 1; x++){          // 3×3 足迹铺铜矿(hardness 0 ≤ laserDrill tier)
                            for(int y = by - 1; y <= by + 1; y++){
                                Tile t = Vars.world.tile(x, y);
                                if(t != null) t.setOverlay(Blocks.oreCopper);
                            }
                        }
                        Building db = Vars.world.build(Point2.pack(bx, by));
                        if(db != null) db.onProximityUpdate();          // 重算 countOre → dominantItem
                    }
                }
            }
        }
        int lastDy = deep ? 8 : 6;
        for(int dy = 0; dy <= lastDy && (big || placed < n); dy += 2){
            if(dy <= lastDy - 2){
                for(int x = x0; x <= x0 + T4_SHED_X1; x++){
                    t4ShedNode(reserveRtg, x0, y0, x, y0 + dy + 1);
                }
            }
            if(big) continue;                                  // 钻区成员 = 钻本身,1×1 成员行不摆
            for(int x = x0; x <= x0 + T4_SHED_X1 - 1 && placed < n; x++){
                if(t4ShedReserved(reserveRtg, x0, y0, x, y0 + dy)) continue;
                if(!t4ShedEmpty(x, y0 + dy)) continue;
                t4SetPlaced(x, y0 + dy, blk);
                placed++;
            }
        }
        return placed;
    }

    /** SRC 站 RTG 重摆:先清三槽 2×2 足迹(底左锚 (x0,y0+DY0+2i)),再按 n 摆前 n 槽(每台 thorium 10=满燃料)。返回实测台数。 */
    private static int t4ShedRtg(int x0, int y0, int n){
        for(int i = 0; i < T4_SHED_RTG_SLOTS; i++){
            int ax = x0, ay = y0 + T4_SHED_RTG_DY0 + i * 2;
            for(int x = ax; x <= ax + 1; x++){
                for(int y = ay; y <= ay + 1; y++){
                    Tile t = Vars.world.tile(x, y);
                    if(t != null && t.build != null){
                        t.setNet(Blocks.air, Team.derelict, 0);
                    }
                }
            }
        }
        int placed = 0;
        for(int i = 0; i < T4_SHED_RTG_SLOTS && placed < n; i++){
            int ax = x0, ay = y0 + T4_SHED_RTG_DY0 + i * 2;
            t4SetPlaced(ax, ay, Blocks.rtgGenerator);
            Building b = Vars.world.build(Point2.pack(ax, ay));
            if(b != null && b.block == Blocks.rtgGenerator){
                b.items.add(Items.thorium, 10);
                placed++;
            }
        }
        return placed;
    }

    /** build:四站 + 三链;回显实摆数(成员/RTG/网关/链)。x0 = 站列左界(缺省 0;015 校准:x0=39 净场列)。 */
    private static void t4ShedBuild(int n1, int n2, int n3, int x0){
        if(Vars.world == null){
            Log.info("[bs-lab] t4shed ERROR 世界未加载");
            return;
        }
        t4ShedClear(x0, 0, x0 + T4_SHED_X1, 47);
        int mSrc = t4ShedFill(x0, T4_SHED_Y[0], Blocks.mender, T4_SHED_M_SRC, true, false);
        int z1 = t4ShedFill(x0, T4_SHED_Y[1], Blocks.illuminator, n1, false, true);
        int z2 = t4ShedFill(x0, T4_SHED_Y[2], Blocks.laserDrill, n2, false, false);
        int z3 = t4ShedFill(x0, T4_SHED_Y[3], Blocks.mender, n3, false, false);
        int rtg = t4ShedRtg(x0, T4_SHED_Y[0], 1);
        int links = 0;
        Building srcGw = Vars.world.build(Point2.pack(x0 + T4_SHED_GWX, T4_SHED_Y[0]));
        if(srcGw instanceof GatewayNode.GatewayNodeBuild gw){
            for(int i = 1; i < 4; i++){
                gw.ensureModLink(Point2.pack(x0 + T4_SHED_GWX, T4_SHED_Y[i]));
                links = gw.modLinks.size;
            }
        }
        int gwN = 0;
        for(int i = 0; i < 4; i++){
            if(t2IsBlock(x0 + T4_SHED_GWX, T4_SHED_Y[i], BlackstartBlocks.gatewayNode)) gwN++;
        }
        BlackstartLinks.markDirty();
        Log.info("[bs-lab] t4shed build ok z1=" + z1 + " z2=" + z2 + " z3=" + z3
            + " src=" + mSrc + " rtg=" + rtg + " gw=" + gwN + " links=" + links + " x0=" + x0);
    }

    /** src:SRC 档位 off=0 / base=1 / boost=3 台 RTG;x0 = 站列左界(缺省 0)。 */
    private static void t4ShedSrc(String mode, int x0){
        if(Vars.world == null){
            Log.info("[bs-lab] t4shed ERROR 世界未加载");
            return;
        }
        int n;
        if("off".equals(mode)){
            n = 0;
        }else if("base".equals(mode)){
            n = 1;
        }else if("boost".equals(mode)){
            n = T4_SHED_RTG_MAX;
        }else{
            Log.info("[bs-lab] t4shed ERROR src 档位须为 off|base|boost: " + mode);
            return;
        }
        int rtg = t4ShedRtg(x0, T4_SHED_Y[0], n);
        BlackstartLinks.markDirty();
        Log.info("[bs-lab] t4shed src ok mode=" + mode + " rtg=" + rtg + " x0=" + x0);
    }

    /** solo:独立单站(无链;T4-8 边界)。 */
    private static void t4ShedSolo(int m){
        if(Vars.world == null){
            Log.info("[bs-lab] t4shed ERROR 世界未加载");
            return;
        }
        t4ShedClear(T4_SHED_SOLO_X0, T4_SHED_SOLO_Y0 - 1, T4_SHED_SOLO_X0 + T4_SHED_X1, T4_SHED_SOLO_Y0 + 8);
        int placed = t4ShedFill(T4_SHED_SOLO_X0, T4_SHED_SOLO_Y0, Blocks.mender, m, false, false);
        int gwN = t2IsBlock(T4_SHED_SOLO_X0 + T4_SHED_GWX, T4_SHED_SOLO_Y0, BlackstartBlocks.gatewayNode) ? 1 : 0;
        BlackstartLinks.markDirty();
        Log.info("[bs-lab] t4shed solo ok m=" + placed + " gw=" + gwN);
    }

    /** save:写 Vars.saveDirectory/&lt;name&gt;.msav(名限 [A-Za-z0-9_-]{1,32})。 */
    private static void t4ShedSave(String name){
        if(Vars.world == null){
            Log.info("[bs-lab] t4shed ERROR 世界未加载");
            return;
        }
        if(!name.matches("[A-Za-z0-9_\\-]{1,32}")){
            Log.info("[bs-lab] t4shed ERROR save 名只允许 [A-Za-z0-9_-]{1,32}: " + name);
            return;
        }
        try{
            Fi f = Vars.saveDirectory.child(name + ".msav");
            SaveIO.write(f);
            Log.info("[bs-lab] t4shed save ok " + name + " path=" + f.absolutePath());
        }catch(Exception e){
            Log.info("[bs-lab] t4shed save ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ============================================================
    // PROT 收编场(T3 保护装置;016 收编链 P1-P12,§8 授权收编轮自加)
    // ============================================================
    // 四站布局(x0=2;gw 列 = x0+8 = 10;站距按 T4 不变量「站内容底行+7 = 下一站起点」):
    //   A y=2  普通网关 GA(10,2)+powerSource(2,2)+节点行(y=3)      —— 无限源上游站
    //   B y=10 断路器 BR(10,10)+40 mender(D=40×0.3=12.0)+节点行 y=11/13/15/17
    //   C y=25 分区开关 ZS1(10,25)/ZS2(9,25)+2 mender+节点行(y=26)  —— 主/备双网桥
    //   D y=33 普通网关 DW(10,33)+2 mender+节点行(y=34)             —— 远端负荷侧
    // 链(ensureModLink,transport):GA→BR、GA→ZS1、ZS1→DW。清区 x∈[1,11]×y∈[1,40]。
    // ⚠ 数值依据(BlackstartLinks 步骤 5 死区,016 首跑实测更正):fStar=min(want×scaleS×scaleT, rating);
    //   dDead=max(0.5, 0.05×rating);停点随进近方向滞回。基线 rate=30 实测停 11.5~12.4
    //   (恒低于 0.95×30=28.5 ⇒ 不跳);跳闸档 rate=10 实测自 0 爬升停 9.8、自 30 侧降停 10.4
    //   (均 ≥0.95×10 ⇒ 饱和 ⇒ marker 后 300 拍跳闸)。旧注「F 持 10.5」为预推值,已废。
    private static final int PROT_X0 = 2;         // 场左缘(内容列 x0..x0+8)
    private static final int PROT_Y0 = 2;         // A 站行(GA)
    private static final int PROT_BY0 = 10;       // B 站行(BR)
    private static final int PROT_CY0 = 25;       // C 站行(ZS1/ZS2)
    private static final int PROT_DY0 = 33;       // D 站行(DW)
    private static final int PROT_GWX = 8;        // gw 列偏移(同 T4_SHED_GWX)
    private static final int PROT_CLEAR_X1 = 11;  // 清区右界(宽环,擦直方图残存)
    private static final int PROT_CLEAR_Y1 = 40;  // 清区上界

    /** prot 收编测试面(§8 授权):build / param &lt;key&gt; &lt;value&gt; / manual / save &lt;name&gt;。 */
    private static void prot(String[] args){
        if(args.length < 2){
            Log.info("[bs-lab] prot ERROR 用法: prot build|param <key> <v>|manual|save <name>");
            return;
        }
        String sub = args[1];
        if("build".equals(sub)){
            protBuild();
        }else if("param".equals(sub)){
            if(args.length < 4){
                Log.info("[bs-lab] prot ERROR 用法: prot param <key> <value>");
                return;
            }
            protParam(args[2], args[3]);
        }else if("manual".equals(sub)){
            protManual();
        }else if("save".equals(sub)){
            protSave(args.length >= 3 ? args[2] : "prot");
        }else{
            Log.info("[bs-lab] prot ERROR 未知子命令 '" + sub + "'(期望 build|param|manual|save)");
        }
    }

    /** PROT 节点行:y 行 x∈[x0..x0+8] 整行铺 powerNode(已占格跳过)。 */
    private static void protRow(int x0, int y){
        for(int x = x0; x <= x0 + T4_SHED_X1; x++){
            if(t4ShedEmpty(x, y)) t4SetPlaced(x, y, Blocks.powerNode);
        }
    }

    /** PROT 成员段:x∈[x0..x0+n-1] 铺 blk(已占格跳过),返回铺成数。 */
    private static int protMemberN(int x0, int y, int n, Block blk){
        int placed = 0;
        for(int i = 0; i < n; i++){
            int x = x0 + i;
            if(t4ShedEmpty(x, y)){
                t4SetPlaced(x, y, blk);
                placed++;
            }
        }
        return placed;
    }

    /** build:清区 → 四站 → 三条链 → 点检回显(build ok + tables 两行)。 */
    private static void protBuild(){
        if(Vars.world == null){
            Log.info("[bs-lab] prot ERROR 世界未加载");
            return;
        }
        t4ShedClear(PROT_X0 - 1, PROT_Y0 - 1, PROT_CLEAR_X1, PROT_CLEAR_Y1);

        // A 站:普通网关 + 源 + 节点行
        t4SetPlaced(PROT_X0 + PROT_GWX, PROT_Y0, BlackstartBlocks.gatewayNode);
        t4SetPlaced(PROT_X0, PROT_Y0, Blocks.powerSource);
        protRow(PROT_X0, PROT_Y0 + 1);

        // B 站:断路器 + 5 行成员(40 mender)+ 4 行节点(深度型,照 t4ShedFill)
        t4SetPlaced(PROT_X0 + PROT_GWX, PROT_BY0, BlackstartBlocks.breakerNode);
        int members = 0;
        for(int dy = 0; dy <= 8; dy += 2){
            if(dy <= 6) protRow(PROT_X0, PROT_BY0 + dy + 1);
            members += protMemberN(PROT_X0, PROT_BY0 + dy, 8, Blocks.mender);
        }

        // C 站:双分区开关(ZS1 主,先落)+ 2 mender + 节点行(y=26,正交邻 ZS1/ZS2 并图;
        // 缺行则 mender 孤立、开关不落区内、zoneCut 永不命中 —— 016 首跑实证,补行修复)
        t4SetPlaced(PROT_X0 + PROT_GWX, PROT_CY0, BlackstartBlocks.zoneSwitchNode);
        t4SetPlaced(PROT_X0 + 7, PROT_CY0, BlackstartBlocks.zoneSwitchNode);
        members += protMemberN(PROT_X0, PROT_CY0, 2, Blocks.mender);
        protRow(PROT_X0, PROT_CY0 + 1);

        // D 站:普通网关 + 2 mender + 节点行(y=34,正交邻 DW 并图;同上补行修复)
        t4SetPlaced(PROT_X0 + PROT_GWX, PROT_DY0, BlackstartBlocks.gatewayNode);
        members += protMemberN(PROT_X0, PROT_DY0, 2, Blocks.mender);
        protRow(PROT_X0, PROT_DY0 + 1);

        // 链:GA→BR、GA→ZS1、ZS1→DW(幂等 ensure)
        Building ga = Vars.world.build(Point2.pack(PROT_X0 + PROT_GWX, PROT_Y0));
        Building zs1 = Vars.world.build(Point2.pack(PROT_X0 + PROT_GWX, PROT_CY0));
        if(ga instanceof GatewayNode.GatewayNodeBuild){
            ((GatewayNode.GatewayNodeBuild)ga).ensureModLink(Point2.pack(PROT_X0 + PROT_GWX, PROT_BY0));
            ((GatewayNode.GatewayNodeBuild)ga).ensureModLink(Point2.pack(PROT_X0 + PROT_GWX, PROT_CY0));
        }
        if(zs1 instanceof GatewayNode.GatewayNodeBuild){
            ((GatewayNode.GatewayNodeBuild)zs1).ensureModLink(Point2.pack(PROT_X0 + PROT_GWX, PROT_DY0));
        }
        BlackstartLinks.markDirty();

        // 点检回显(实测:网关格数 / 成员数 / 源数 + 三网关自管表现状)
        int gw = 0;
        if(t2IsBlock(PROT_X0 + PROT_GWX, PROT_Y0, BlackstartBlocks.gatewayNode)) gw++;
        if(t2IsBlock(PROT_X0 + PROT_GWX, PROT_BY0, BlackstartBlocks.breakerNode)) gw++;
        if(t2IsBlock(PROT_X0 + PROT_GWX, PROT_CY0, BlackstartBlocks.zoneSwitchNode)) gw++;
        if(t2IsBlock(PROT_X0 + 7, PROT_CY0, BlackstartBlocks.zoneSwitchNode)) gw++;
        if(t2IsBlock(PROT_X0 + PROT_GWX, PROT_DY0, BlackstartBlocks.gatewayNode)) gw++;
        int src = t2IsBlock(PROT_X0, PROT_Y0, Blocks.powerSource) ? 1 : 0;
        Log.info("[bs-lab] prot build ok gw=" + gw + " m=" + members + " src=" + src);
        Log.info("[bs-lab] prot tables " + protTables(ga, zs1, Vars.world.build(Point2.pack(PROT_X0 + PROT_GWX, PROT_DY0))));
    }

    /** 自管表快照文本:`ga=[(x,y),...] zs1=... dw=...`(P12 前就读;空表 = [])。 */
    private static String protTables(Building ga, Building zs1, Building dw){
        return "ga=" + protTable(ga) + " zs1=" + protTable(zs1) + " dw=" + protTable(dw);
    }

    private static String protTable(Building b){
        if(!(b instanceof GatewayNode.GatewayNodeBuild)) return "n/a";
        GatewayNode.GatewayNodeBuild g = (GatewayNode.GatewayNodeBuild)b;
        StringBuilder sb = new StringBuilder("[");
        for(int i = 0; i < g.modLinks.size; i++){
            Point2 p = Point2.unpack(g.modLinks.get(i));
            if(i > 0) sb.append(',');
            sb.append('(').append(p.x).append(',').append(p.y).append(')');
        }
        return sb.append(']').toString();
    }

    /** param &lt;key&gt; &lt;value&gt;:块级参数点改(收编面仅 recloseMax;P6 用)。 */
    private static void protParam(String key, String value){
        if(!"recloseMax".equals(key)){
            Log.info("[bs-lab] prot param ERROR 未知键 '" + key + "'(期望 recloseMax)");
            return;
        }
        int v;
        try{
            v = Integer.parseInt(value);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] prot param ERROR 值非整数: " + value);
            return;
        }
        int old = BlackstartBlocks.breakerNode.recloseMax;
        BlackstartBlocks.breakerNode.recloseMax = v;
        Log.info("[bs-lab] prot param " + key + " " + old + " -> " + v);
    }

    /** manual:P12 手动交互面七步(GA↔DW 家族加/断、GA↔自节点加/断、远目标拒、DW/GA 自点)。 */
    private static void protManual(){
        if(Vars.world == null){
            Log.info("[bs-lab] prot ERROR 世界未加载");
            return;
        }
        Building gaB = Vars.world.build(Point2.pack(PROT_X0 + PROT_GWX, PROT_Y0));
        Building dwB = Vars.world.build(Point2.pack(PROT_X0 + PROT_GWX, PROT_DY0));
        if(!(gaB instanceof GatewayNode.GatewayNodeBuild) || !(dwB instanceof GatewayNode.GatewayNodeBuild)){
            Log.info("[bs-lab] prot manual ERROR GA/DW 缺失,先 prot build");
            return;
        }
        GatewayNode.GatewayNodeBuild ga = (GatewayNode.GatewayNodeBuild)gaB;
        GatewayNode.GatewayNodeBuild dw = (GatewayNode.GatewayNodeBuild)dwB;
        Building node93 = Vars.world.build(Point2.pack(PROT_X0 + PROT_GWX - 1, PROT_Y0 + 1));  // (9,3) A 站自节点
        Building far = Vars.world.build(Point2.pack(PROT_X0, PROT_BY0 + 8));                   // (2,18) B 站成员(距 GA 远)
        Log.info("[bs-lab] prot manual tables " + protTables(gaB, Vars.world.build(Point2.pack(PROT_X0 + PROT_GWX, PROT_CY0)), dwB));
        protTap("ga->dw+", ga, dw);      // 家族近目标:加
        protTap("ga->dw-", ga, dw);      // 已含 ⇒ 余表替换:断
        protTap("ga->n93+", ga, node93); // 原生近节点:加
        protTap("ga->n93-", ga, node93); // 原生近节点:断
        protTap("ga->far", ga, far);     // 原生远目标:linkValid 假 ⇒ ret=true 不变
        protTap("dw->dw", dw, dw);       // 自点:DW 表空 ⇒ 扫描支(候选亦空时 0->0)
        protTap("ga->ga", ga, ga);       // 自点:GA 表非空 ⇒ 清空(终态,无需还原)
        Log.info("[bs-lab] prot manual done");
    }

    /** 单步 tap:记 n0→n1 与返回;异常吞成 ERR=(deselect 等客户端路径无头下如实记录)。 */
    private static void protTap(String label, GatewayNode.GatewayNodeBuild owner, Building other){
        int n0 = owner.modLinks.size;
        boolean ret = false;
        String err = null;
        try{
            ret = owner.onConfigureBuildTapped(other);
        }catch(Throwable t){
            err = t.getClass().getSimpleName() + ":" + t.getMessage();
        }
        Log.info("[bs-lab] prot manual " + label + " ret=" + ret + " modLinks=" + n0 + "->" + owner.modLinks.size
            + (err == null ? "" : " ERR=" + err));
    }

    /** save:写 Vars.saveDirectory/&lt;name&gt;.msav(名限同 t4shed;prot1 供 boot2/3 跨档验装置态)。 */
    private static void protSave(String name){
        if(Vars.world == null){
            Log.info("[bs-lab] prot ERROR 世界未加载");
            return;
        }
        if(!name.matches("[A-Za-z0-9_\\-]{1,32}")){
            Log.info("[bs-lab] prot ERROR save 名只允许 [A-Za-z0-9_-]{1,32}: " + name);
            return;
        }
        try{
            Fi f = Vars.saveDirectory.child(name + ".msav");
            SaveIO.write(f);
            Log.info("[bs-lab] prot save ok " + name + " path=" + f.absolutePath());
        }catch(Exception e){
            Log.info("[bs-lab] prot save ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * cls:四站分类场(T4-1)。T(10,10) 网关+源+电池+arc / F(10,20) +laserDrill /
     * M(10,30) +illuminator / L(10,40) +microProcessor(无电 ⇒ members=0 兜底 tier2);
     * 链 gw1→gw2→gw3→gw4(ensureModLink,默认开)。清区 x∈[10,11]×y∈[10,42]。
     * 回显 `[bs-lab] t4cls ok gw=4 links=3`(均为实测:网关点检数 / 前三网关 modLinks 合计)。
     */
    private static void t4Cls(){
        if(Vars.world == null){
            Log.info("[bs-lab] t4cls ERROR 世界未加载");
            return;
        }
        // ① 清区(只清 build != null 的 tile)
        for(int x = 10; x <= 11; x++){
            for(int y = 10; y <= 42; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }
        // ② 四站:网关先落(区内已空 ⇒ placed() 扫描不到候选),再源/被测块/电池
        for(int i = 0; i < 4; i++){
            int gy = T4_CLS_YS[i];
            t2Set(10, gy, BlackstartBlocks.gatewayNode);
            t2Set(10, gy + 1, Blocks.powerSource);
        }
        t2Set(10, 12, Blocks.arc);             // T 塔区(PowerTurret ⇒ tier3)
        t2Set(11, 11, Blocks.battery);         // T 电池(buffered ⇒ 分类排除)
        t2Set(10, 22, Blocks.laserDrill);      // F 工厂区(Drill ⇒ tier2)
        t2Set(10, 32, Blocks.illuminator);     // M 档1区(LightBlock ⇒ tier1)
        t2Set(10, 42, Blocks.microProcessor);  // L 无电(LogicBlock ⇒ members=0,兜底 tier2)
        // ③ 链 gw1→gw2→gw3→gw4(幂等 ensure;新网关空表 ⇒ 恰 3 条)
        int links = 0;
        for(int i = 0; i < 3; i++){
            Building b = Vars.world.build(Point2.pack(10, T4_CLS_YS[i]));
            if(b instanceof GatewayNode.GatewayNodeBuild gw){
                gw.ensureModLink(Point2.pack(10, T4_CLS_YS[i + 1]));
                links += gw.modLinks.size;
            }
        }
        int gwN = 0;
        for(int i = 0; i < 4; i++){
            if(t2IsBlock(10, T4_CLS_YS[i], BlackstartBlocks.gatewayNode)) gwN++;
        }
        BlackstartLinks.markDirty();
        Log.info("[bs-lab] t4cls ok gw=" + gwN + " links=" + links);
    }

    /**
     * rec &lt;x&gt; &lt;y&gt; &lt;side&gt;:给 (x,y) 处网关注册表追加一条机器类记录条目
     * (对端 = 相邻格,side∈{n,s,e,w};n=y-1 s=y+1 e=x+1 w=x-1,须为非网关机器格)
     * ——B7 岛数不变量用例(记录条目不参与连通)。回显 `[bs-lab] t4rec ok modlinks=<n>`。
     * (lab 命令层参数上限 op+3,无法传四坐标,故用方向式。)
     */
    private static void t4Rec(String[] args){
        if(args.length < 4){
            Log.info("[bs-lab] t4rec ERROR 用法: bs-lab-t2 t4rec <x> <y> <n|s|e|w>");
            return;
        }
        int x, y;
        try{
            x = Integer.parseInt(args[1]);
            y = Integer.parseInt(args[2]);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] t4rec ERROR 参数非整数: " + args[1] + " " + args[2]);
            return;
        }
        String side = args[3];
        int tx = x, ty = y;
        if(side.equals("n")) ty = y - 1;
        else if(side.equals("s")) ty = y + 1;
        else if(side.equals("e")) tx = x + 1;
        else if(side.equals("w")) tx = x - 1;
        else{
            Log.info("[bs-lab] t4rec ERROR 方向须为 n/s/e/w: " + side);
            return;
        }
        Building b = Vars.world == null ? null : Vars.world.build(Point2.pack(x, y));
        if(!(b instanceof GatewayNode.GatewayNodeBuild gw)){
            Log.info("[bs-lab] t4rec ERROR (" + x + "," + y + ") 处无网关");
            return;
        }
        gw.ensureModLink(Point2.pack(tx, ty));
        BlackstartLinks.markDirty();
        Log.info("[bs-lab] t4rec ok modlinks=" + gw.modLinks.size);
    }

    /**
     * watch:两岛建场(T4-2,48×48 重设计;fix-014:脊 16 全 node + RTG 2×2 侧排)。岛1 横排 GW(18,37)
     * x∈[18,33]×y∈[36,43];岛2 纵排(转置同构)GW(38,32) x∈[37,44]×y∈[32,47]。脊柱长 16 全 node;
     * node 列/行偏移 +0/+3/+6/+9/+12/+15 ×5;mender 槽 50(10/行×5)。RTG 2×2 侧排 3 台/岛(基座间距 2,防
     * footprint 互踩自毁):岛1 (19,36)(21,36)(23,36) / 岛2 (37,33)(37,35)(37,37),3×4.5=13.5。默认岛1=45
     * (13.5:1.5 r=0)/ 岛2=40。清区=两岛各自包络(含 RTG 侧排)。回显 `[bs-lab] t4watch ok islands=<网关点检数>`。
     */
    private static void t4Watch(){
        if(Vars.world == null){
            Log.info("[bs-lab] t4watch ERROR 世界未加载");
            return;
        }
        // 清区:岛1 横排 [X1,X1+15]×[Y1-1,Y1+6](y-1 容 RTG 侧排);岛2 纵排 [X2-1,X2+6]×[Y2,Y2+15](互不越界)
        for(int x = T4_WATCH_X1; x <= T4_WATCH_X1 + T4_WATCH_SPAN - 1; x++){
            for(int y = T4_WATCH_Y1 - 1; y <= T4_WATCH_Y1 + 6; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }
        for(int x = T4_WATCH_X2 - 1; x <= T4_WATCH_X2 + 6; x++){
            for(int y = T4_WATCH_Y2; y <= T4_WATCH_Y2 + T4_WATCH_SPAN - 1; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }
        int gwN = 0;
        int[] basesX = {T4_WATCH_X1, T4_WATCH_X2};
        int[] basesY = {T4_WATCH_Y1, T4_WATCH_Y2};
        int[] ms = {T4_WATCH_M1, T4_WATCH_M2};
        for(int isle = 0; isle < 2; isle++){
            int bx = basesX[isle], by = basesY[isle];
            t2Set(bx, by, BlackstartBlocks.gatewayNode);
            for(int d = 0; d < T4_WATCH_SPAN; d++){
                int sx = isle == 0 ? bx + d : bx + 1;   // 脊柱:岛1 横走 / 岛2 竖走
                int sy = isle == 0 ? by + 1 : by + d;
                t2Set(sx, sy, Blocks.powerNode);        // fix-014:脊全 node(RTG 移出脊线,防 2×2 footprint 互踩)
            }
            // RTG 2×2 侧排(基座间距 2 ⇒ footprint 相错不叠;旧案嵌脊 d=1..3 时相邻 footprint 互压,
            // 后续 setNet 踩格即把整块连同全部 footprint 格铲光 ⇒ 静默自毁,已由间距切断)
            for(int k = 0; k < 3; k++){
                int rx = isle == 0 ? bx + 1 + k * 2 : bx - 1;
                int ry = isle == 0 ? by - 1 : by + 1 + k * 2;
                Tile t = Vars.world.tile(rx, ry);
                if(t == null) continue;
                t.setNet(Blocks.rtgGenerator, Team.sharded, 0);
                Building b = t.build;
                if(b != null && b.block == Blocks.rtgGenerator) b.items.add(Items.thorium, 10);
            }
            for(int ci = 0; ci < 6; ci++){
                int c = ci * 3;
                for(int e = 2; e <= 6; e++){
                    if(isle == 0) t2Set(bx + c, by + e, Blocks.powerNode);   // 岛1:列竖走
                    else t2Set(bx + e, by + c, Blocks.powerNode);            // 岛2:行横走(转置)
                }
            }
            t4SetMenders(isle + 1, ms[isle]);
            if(t2IsBlock(bx, by, BlackstartBlocks.gatewayNode)) gwN++;
        }
        BlackstartLinks.markDirty();
        Log.info("[bs-lab] t4watch ok islands=" + gwN);
    }

    /** 岛 isle(1|2)的 mender 槽数调整为 n(多退少补;只动槽内 mender 格,其他建筑不碰)。
     *  槽位映射:岛1(横排)=(X1+SLOTOFF[k], Y1+2+r);岛2(纵排,转置)=(X2+2+r, Y2+SLOTOFF[k]);r∈[0,5)、k∈[0,10)。 */
    private static void t4SetMenders(int isle, int n){
        int slot = 0;
        for(int r = 0; r < 5; r++){
            for(int k = 0; k < 10; k++){
                int x = isle == 1 ? T4_WATCH_X1 + T4_WATCH_SLOTOFF[k] : T4_WATCH_X2 + 2 + r;
                int y = isle == 1 ? T4_WATCH_Y1 + 2 + r : T4_WATCH_Y2 + T4_WATCH_SLOTOFF[k];
                Tile t = Vars.world.tile(x, y);
                if(t == null){ slot++; continue; }
                boolean isMender = t.build != null && t.build.block == Blocks.mender;
                if(slot < n){
                    if(t.build == null) t.setNet(Blocks.mender, Team.sharded, 0);
                }else{
                    if(isMender) t.setNet(Blocks.air, Team.derelict, 0);
                }
                slot++;
            }
        }
    }

    /**
     * menders &lt;isle&gt; &lt;n&gt;:isle∈{1,2},n∈[0,50];岛内 mender 多退少补 + markDirty;
     * 回显 `[bs-lab] t4menders ok n=<实测槽内计数>`。
     */
    private static void t4Menders(String[] args){
        if(args.length < 3){
            Log.info("[bs-lab] t4menders ERROR 用法: bs-lab-t2 t4menders <1|2> <n>");
            return;
        }
        int isle, n;
        try{
            isle = Integer.parseInt(args[1]);
            n = Integer.parseInt(args[2]);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] t4menders ERROR 参数非整数");
            return;
        }
        if(isle != 1 && isle != 2){
            Log.info("[bs-lab] t4menders ERROR isle 须为 1|2: " + isle);
            return;
        }
        if(n < 0 || n > T4_WATCH_SLOTS){
            Log.info("[bs-lab] t4menders ERROR n 越界(0.." + T4_WATCH_SLOTS + "): " + n);
            return;
        }
        if(Vars.world == null){
            Log.info("[bs-lab] t4menders ERROR 世界未加载");
            return;
        }
        t4SetMenders(isle, n);
        BlackstartLinks.markDirty();
        int cnt = 0;
        for(int r = 0; r < 5; r++){
            for(int k = 0; k < 10; k++){
                int x = isle == 1 ? T4_WATCH_X1 + T4_WATCH_SLOTOFF[k] : T4_WATCH_X2 + 2 + r;
                int y = isle == 1 ? T4_WATCH_Y1 + 2 + r : T4_WATCH_Y2 + T4_WATCH_SLOTOFF[k];
                if(t2IsBlock(x, y, Blocks.mender)) cnt++;
            }
        }
        Log.info("[bs-lab] t4menders ok n=" + cnt);
    }

    /**
     * grant [kload]:D48 场。A 图 = GW(41,0) + spine(40,0..4) + node 行 y=5/6 全铺
     * + 补网 (41..47,2) + 旁路列 x=39(y=0..4)+ 挂钩 (41,3)/(42,3)(014 修复:kOwn 顶掉脊柱/补网
     * node 会使 A 图裂、RTG 被隔离;kload=0 时纯 node 无数值影响) + 单 RTG(34,4)(供给 4.5 ⇒ fStar≈1.455<1.5 落死区)
     * + 可选 kload 个 mender(kOwn 位,0.3/枚);B 三网关 (33/37/40,10)(已是网关则跳过)
     * + 每站 node 列 x=gx×y=11..16 + mender 列 x=gx+1(6 枚/站);三链 A→B。
     * 清区:A x∈[33,47]×y∈[0,8](含潜在 (40,0) 遗留源);B 站 x∈[33,47]×y∈[11,16]。
     * 回显 `[bs-lab] t4grant ok links=<A 网关 modLinks 实测> rtg=<实测>`。
     */
    private static void t4Grant(String[] args){
        int kload = 0;
        if(args.length > 1){
            try{
                kload = Integer.parseInt(args[1]);
            }catch(NumberFormatException e){
                Log.info("[bs-lab] t4grant ERROR 参数非整数 kload=" + args[1]);
                return;
            }
            if(kload < 0 || kload > T2_OWN_MAX){
                Log.info("[bs-lab] t4grant ERROR kload 越界(0.." + T2_OWN_MAX + "): " + kload);
                return;
            }
        }
        if(Vars.world == null){
            Log.info("[bs-lab] t4grant ERROR 世界未加载");
            return;
        }
        // ① 清 A 区(y ≤ 8;不碰 y=9/10)
        for(int x = T2_X0; x <= T2_X1; x++){
            for(int y = 0; y <= T2_ARB_CLEAR_Y1; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }
        // ② 清 B 站上部(y=11..16;网关行 y=10 不动)
        for(int x = T2_X0; x <= T2_X1; x++){
            for(int y = 11; y <= 16; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }
        // ③ A 站
        // 014 修复(e5 场实测:A 图三裂、RTG 被隔离):kOwn 位 (40,1)/(40,2) 摆 mender 会顶掉
        // 脊柱 node,而 mender 间不导通 ⇒ 脊柱 y=1/2 断开、GW 分量只剩 2 枚贴源/网关 mender;
        // 同理 (41,2) 顶掉补网 node 会切断 y=1 排 (42..47) 与补网行的联系。修复 = 两条纯 node 连线:
        // 旁路列 x=39(y=0..4,绕开 (40,1)/(40,2)) + 挂钩 (41,3)/(42,3)(脊柱 (40,3)→(41,3)→(42,3)→(42,2)
        // 接回补网行)。纯 node 无产无耗 ⇒ kload=0 基线数值不变(仅场内 +6 node)。
        t2Set(T2_AX, T2_AY, BlackstartBlocks.gatewayNode);
        for(int y = 0; y <= 4; y++) t2Set(T2_SRCX, y, Blocks.powerNode);
        for(int y = 0; y <= 4; y++) t2Set(T2_SRCX - 1, y, Blocks.powerNode);   // 014 修复:旁路列
        for(int x = T2_X0; x <= T2_X1; x++) t2Set(x, 5, Blocks.powerNode);
        for(int x = T2_X0; x <= T2_X1; x++) t2Set(x, 6, Blocks.powerNode);
        for(int x = 41; x <= 47; x++) t2Set(x, 2, Blocks.powerNode);   // 补网底衬(kOwn 位全入图)
        t2Set(41, 3, Blocks.powerNode);   // 014 修复:补网挂钩(脊柱 (40,3) ↔ 补网行)
        t2Set(42, 3, Blocks.powerNode);   // 014 修复:(41,2) 为 kOwn 位 → 经 (42,3) 接 (42,2)
        int rtg = 0;
        Tile tr = Vars.world.tile(T4_GRANT_RTGX, T4_GRANT_RTGY);
        if(tr != null){
            tr.setNet(Blocks.rtgGenerator, Team.sharded, 0);
            Building b = tr.build;
            if(b != null && b.block == Blocks.rtgGenerator){
                b.items.add(Items.thorium, 10);
                rtg++;
            }
        }
        for(int k = 0; k < kload; k++) t2Set(t2OwnX(k), t2OwnY(k), Blocks.mender);
        // ④ B 三网关 + 站体
        for(int g = 0; g < T2_ARB_GWX.length; g++){
            int gx = T2_ARB_GWX[g];
            if(!(Vars.world.build(Point2.pack(gx, T2_ARB_GWY)) instanceof GatewayNode.GatewayNodeBuild)){
                t2Set(gx, T2_ARB_GWY, BlackstartBlocks.gatewayNode);
            }
            for(int y = 11; y <= 16; y++){
                t2Set(gx, y, Blocks.powerNode);
                t2Set(gx + 1, y, Blocks.mender);
            }
        }
        // ⑤ 三链
        int links = 0;
        Building ab = Vars.world.build(Point2.pack(T2_AX, T2_AY));
        if(ab instanceof GatewayNode.GatewayNodeBuild gwa){
            for(int g = 0; g < T2_ARB_GWX.length; g++){
                gwa.ensureModLink(Point2.pack(T2_ARB_GWX[g], T2_ARB_GWY));
            }
            links = gwa.modLinks.size;
        }
        BlackstartLinks.markDirty();
        Log.info("[bs-lab] t4grant ok links=" + links + " rtg=" + rtg);
    }

    /**
     * perf:性能场(T4-9,48×48 重设计)。6 组 8 gw:2 列(x=10/29)×3 行(y=0/14/28),站距 8>6;
     * 双站组 = 第 1、4 组(gx 与 gx+11);每站 node 行 y=+1 长 12 + node 列(间隔 3)×y=+2..+6 + mender 40;
     * 7 链跨图:p1→q、p2→q、q→r、r→s1、s1→t、s2→t、t→u(同图双网关不并岛 ⇒ 必须跨图链)。
     * 清区 x∈[10,47]×y∈[0,47](段 5 跑在全部 T4 段之后:清光 cls/watch/grant/遗留再建)。
     * 回显 `[bs-lab] t4perf ok gw=8 islands=1 zones=6`(gw 为实测点检;islands/zones 为设计字面)。
     */
    private static void t4Perf(){
        if(Vars.world == null){
            Log.info("[bs-lab] t4perf ERROR 世界未加载");
            return;
        }
        for(int x = T4_PERF_CX0; x <= T4_PERF_CX1; x++){
            for(int y = 0; y <= T4_PERF_CY1; y++){
                Tile t = Vars.world.tile(x, y);
                if(t != null && t.build != null){
                    t.setNet(Blocks.air, Team.derelict, 0);
                }
            }
        }
        for(int[] s : T4_PERF_SITES){
            int gx = s[0], gy = s[1];
            t2Set(gx, gy, BlackstartBlocks.gatewayNode);
            if(s[2] == 1) t2Set(gx + 11, gy, BlackstartBlocks.gatewayNode);
            for(int dx = 0; dx < 12; dx++) t2Set(gx + dx, gy + 1, Blocks.powerNode);
            for(int ci = 0; ci < 4; ci++){
                int cx = gx + ci * 3;
                for(int dy = 2; dy <= 6; dy++) t2Set(cx, gy + dy, Blocks.powerNode);
            }
            int placed = 0;
            for(int dy = 2; dy <= 6 && placed < T4_PERF_M; dy++){
                for(int dx = 0; dx < 12 && placed < T4_PERF_M; dx++){
                    if(dx % 3 == 0) continue;   // node 列
                    t2Set(gx + dx, gy + dy, Blocks.mender);
                    placed++;
                }
            }
        }
        int gwN = 0;
        for(int[] s : T4_PERF_SITES){
            if(t2IsBlock(s[0], s[1], BlackstartBlocks.gatewayNode)) gwN++;
            if(s[2] == 1 && t2IsBlock(s[0] + 11, s[1], BlackstartBlocks.gatewayNode)) gwN++;
        }
        t4Ensure(10,  0, 29,  0);   // p1→q
        t4Ensure(21,  0, 29,  0);   // p2→q(双站第二网关,同图后置幂等)
        t4Ensure(29,  0, 10, 14);   // q→r
        t4Ensure(10, 14, 29, 14);   // r→s1
        t4Ensure(29, 14, 10, 28);   // s1→t
        t4Ensure(40, 14, 10, 28);   // s2→t(双站第二网关)
        t4Ensure(10, 28, 29, 28);   // t→u
        BlackstartLinks.markDirty();
        Log.info("[bs-lab] t4perf ok gw=" + gwN + " islands=1 zones=6");
    }

    /** (x,y) 处网关对 (tx,ty) ensureModLink(缺网关则静默跳过;perf 链辅助)。 */
    private static void t4Ensure(int x, int y, int tx, int ty){
        Building b = Vars.world == null ? null : Vars.world.build(Point2.pack(x, y));
        if(b instanceof GatewayNode.GatewayNodeBuild gw){
            gw.ensureModLink(Point2.pack(tx, ty));
        }
    }

    /**
     * sample &lt;jsonPath&gt; &lt;everyTicks&gt; &lt;count&gt;:从命令起每 everyTicks 个 tick 采一条,采满 count 条
     * → writeJson 原子落盘 + `[bs-lab] done &lt;path&gt;` + 自卸。
     *
     * 参数:everyTicks∈[1,60]、count∈[1,1200]。重复命令 = 先卸旧槽再挂新(防重入);中途世界退出
     * (isPlaying false)→ 直接落盘已采部分 + done。
     *
     * 监听实现(arc Events 亲验签名见 改动说明):本 arc 版本 {@code Events.on(Class,Cons)} 返回 void、
     * {@code Events.remove(Class,Cons)} 只按 Class 键;而 Trigger.update 由 {@code Events.fire(Enum)} 按
     * enum 常量键派发,故【无法】用公开 API 卸载 enum 触发器监听。等效实现 = 派发器只挂一次,
     * 用 {@link #t2Sampler} 单槽控制有效性:自卸 / 换采样器都是置/换槽,派发器随即空转返回。
     */
    private static void t2Sample(String[] args){
        if(args.length < 4){
            Log.info("[bs-lab] t2 ERROR 用法: bs-lab-t2 sample <jsonPath> <everyTicks> <count>");
            return;
        }
        String path = args[1];
        int every, count;
        try{
            every = Integer.parseInt(args[2]);
            count = Integer.parseInt(args[3]);
        }catch(NumberFormatException e){
            Log.info("[bs-lab] t2 ERROR 参数非整数 everyTicks=" + args[2] + " count=" + args[3]);
            return;
        }
        if(every < 1 || every > 60){
            Log.info("[bs-lab] t2 ERROR everyTicks 越界(1..60): " + every);
            return;
        }
        if(count < 1 || count > 1200){
            Log.info("[bs-lab] t2 ERROR count 越界(1..1200): " + count);
            return;
        }
        if(Vars.world == null || Vars.state == null){
            Log.info("[bs-lab] t2 ERROR 世界未加载");
            return;
        }

        if(t2Sampler != null){                                   // 先卸旧监听再挂新(防重入)
            Log.info("[bs-lab] t2 sample 旧采样器已卸载(丢弃 " + t2Sampler.taken + " 条)");
            t2Sampler = null;
        }
        t2Sampler = new T2Sampler(path, every, count, Vars.state.tick);
        t2EnsureHook();
        Log.info("[bs-lab] t2 sample start every=" + every + " count=" + count + " path=" + path);
    }

    /**
     * 挂载采样派发器(幂等、全程只挂一次)。arc Events 对 enum 触发器无卸载面(见 {@link #t2Sample} 注),
     * 故派发器常驻;有效性由 {@link #t2Sampler} 槽位控制。
     */
    private static void t2EnsureHook(){
        if(t2SamplerHooked) return;
        t2SamplerHooked = true;
        Events.run(EventType.Trigger.update, BlackstartLab::t2SampleTick);
    }

    /** 采样派发器(每 tick):世界退出 → 落盘已采部分;到点 → 采一条;采满 → 落盘 + 自卸。 */
    private static void t2SampleTick(){
        T2Sampler s = t2Sampler;
        if(s == null) return;
        if(Vars.state == null || !Vars.state.isPlaying()){
            t2SampleFinish(s);                                   // 中途世界退出:直接落盘已采部分 + done
            return;
        }
        double now = Vars.state.tick;
        while(t2Sampler == s && now >= s.nextTick){
            t2SampleOne(s);
            s.taken++;
            s.nextTick += s.every;
            if(s.taken >= s.count){
                t2SampleFinish(s);
                return;
            }
        }
    }

    /** 采一条:固定坐标 (41,0)/(33,10) 取 build(null 安全 → 该条对应字段填 -1)。 */
    private static void t2SampleOne(T2Sampler s){
        Building ab = Vars.world == null ? null : Vars.world.build(Point2.pack(T2_AX, T2_AY));
        Building bb = Vars.world == null ? null : Vars.world.build(Point2.pack(T2_BX, T2_BY));
        int state = -1;
        float f = -1f, sa = -1f, sb = -1f, st = -1f, da = -1f, ia = -1f, db = -1f, ib = -1f;
        if(ab instanceof GatewayNode.GatewayNodeBuild ga){
            PowerGraph ag = ga.power == null ? null : ga.power.graph;
            sa = ag == null ? -1f : ag.getSatisfaction();
            da = ga.linkDrawCmd;
            ia = ga.linkInjCmd;
            int idx = ga.modLinks.indexOf(Point2.pack(T2_BX, T2_BY));
            if(idx >= 0){
                f = idx < ga.linkFlow.length ? ga.linkFlow[idx] : -1f;
                state = idx < ga.linkState.length ? ga.linkState[idx] : -1;
            }
        }
        if(bb instanceof GatewayNode.GatewayNodeBuild gb){
            PowerGraph bg = gb.power == null ? null : gb.power.graph;
            sb = bg == null ? -1f : bg.getSatisfaction();
            st = bg == null ? -1f : bg.getBatteryStored();
            db = gb.linkDrawCmd;
            ib = gb.linkInjCmd;
        }
        Jval one = Jval.newObject();
        one.put("t", (long)Vars.state.tick);
        one.put("f", f).put("s", state)
            .put("sa", sa).put("sb", sb).put("st", st)
            .put("da", da).put("ia", ia).put("db", db).put("ib", ib);
        s.ticks.add(one);
    }

    /** 落盘 + `[bs-lab] done` 行 + 自卸(t2Sampler 置 null)。 */
    private static void t2SampleFinish(T2Sampler s){
        if(t2Sampler != s) return;
        t2Sampler = null;                                        // 自卸:派发器随即空转
        Jval root = Jval.newObject();
        root.put("schema", "blackstart.lab.t2.sample");
        root.put("ticks", s.ticks);
        root.put("at", t2Timestamp());
        try{
            writeJson(s.path, root.toString(Jval.Jformat.plain));
        }catch(Throwable t){
            Log.err("[bs-lab] 写文件失败 " + s.path + " : " + t);
        }finally{
            Log.info("[bs-lab] done " + s.path);
        }
    }

    /** 采样监听单活跃槽(可卸载 = 置/换槽;派发器常驻,见 {@link #t2Sample})。 */
    private static T2Sampler t2Sampler;
    /** 采样派发器是否已挂载(arc Events 无卸载面 ⇒ 全程只挂一次)。 */
    private static boolean t2SamplerHooked;

    /** 采样器状态(装具 = 测试面;允许每拍读两格 tile,不违反「结算稳态不碰 tile 网格」纪律)。 */
    private static final class T2Sampler {
        final String path;
        final int every;
        final int count;
        final Jval ticks = Jval.newArray();
        double nextTick;
        int taken;

        T2Sampler(String path, int every, int count, double startTick){
            this.path = path;
            this.every = every;
            this.count = count;
            this.nextTick = startTick;
        }
    }

    /**
     * probe &lt;jsonPath&gt;:无状态(按固定坐标定位 (41,0)/(33,10),不依赖 build 缓存)。
     * 读取 + Java 侧自洽断言(6 条)+ writeJson 原子落盘;末行 [bs-lab] done &lt;path&gt; 为中枢同步锚。
     */
    private static void t2Probe(String jsonPath){
        Building ab = Vars.world == null ? null : Vars.world.build(Point2.pack(T2_AX, T2_AY));
        Building bb = Vars.world == null ? null : Vars.world.build(Point2.pack(T2_BX, T2_BY));
        if(!(ab instanceof GatewayNode.GatewayNodeBuild ga) || !(bb instanceof GatewayNode.GatewayNodeBuild gb)){
            Log.info("[bs-lab] t2 ERROR 网关缺失 A=" + (ab == null ? "null" : ab.block.name)
                + " B=" + (bb == null ? "null" : bb.block.name));
            return;
        }

        // ---- 读取 ----
        float rate = BlackstartBlocks.gatewayNode.linkRating;
        float loss = BlackstartParams.getFloat(BlackstartParams.KEY_LOSS, BlackstartParams.DEFAULT_LOSS);
        int packed = Point2.pack(T2_BX, T2_BY);
        int idx = ga.modLinks.indexOf(packed);
        boolean present = idx >= 0;
        boolean open = present && idx < ga.linkOpen.length && ga.linkOpen[idx];
        float f = present && idx < ga.linkFlow.length ? ga.linkFlow[idx] : 0f;
        int state = present && idx < ga.linkState.length ? ga.linkState[idx] : 0;
        Building peer = Vars.world.build(packed);
        boolean valid = peer instanceof GatewayNode.GatewayNodeBuild && ga.modLinkValid(packed, ga.team);

        PowerGraph ag = ga.power == null ? null : ga.power.graph;
        PowerGraph bg = gb.power == null ? null : gb.power.graph;
        float aGP = ag == null ? -1f : ag.getPowerProduced();
        float aGN = ag == null ? -1f : ag.getPowerNeeded();
        float aSat = ag == null ? -1f : ag.getSatisfaction();
        float bGP = bg == null ? -1f : bg.getPowerProduced();
        float bGN = bg == null ? -1f : bg.getPowerNeeded();
        float bSat = bg == null ? -1f : bg.getSatisfaction();

        boolean hasSrc = t2IsBlock(T2_SRCX, T2_SRCY, Blocks.powerSource);
        int kOwn = 0;
        for(int k = 0; k < T2_OWN_MAX; k++){
            if(t2IsBlock(t2OwnX(k), t2OwnY(k), Blocks.mender)) kOwn++;
        }

        Jval flows = Jval.newArray();
        int flowCount = 0;
        // 013(D37):③ 改多链 Σ 口径 —— owner 级 linkDrawCmd = ΣF(步骤 6 逐链累加,T2 核心本就是 ΣF)。
        // 累加与 flows 同一循环、同一组条目;非搬运/离线/关断/同网条目其 linkFlow 恒 0,
        // 全条目求和 ≡ 全搬运链求和(012 单链场下与旧式逐位等价)。
        float sumF = 0f;
        for(int i = 0; i < ga.modLinks.size; i++){
            int p = ga.modLinks.get(i);
            if(!(Vars.world.build(p) instanceof GatewayNode.GatewayNodeBuild)) continue;   // 记录条目跳过
            Point2 pp = Point2.unpack(p);
            float ff = i < ga.linkFlow.length ? ga.linkFlow[i] : 0f;
            int ss = i < ga.linkState.length ? ga.linkState[i] : 0;
            sumF += ff;
            flows.add(Jval.newArray().add(pp.x).add(pp.y).add(ff).add(ss));
            flowCount++;
        }

        // ---- Java 侧迷你断言(只查自洽,不查场景数学)----
        boolean a1 = Float.isFinite(rate) && Float.isFinite(loss)
            && Float.isFinite(ga.linkDrawCmd) && Float.isFinite(ga.linkInjCmd)
            && Float.isFinite(gb.linkDrawCmd) && Float.isFinite(gb.linkInjCmd)
            && Float.isFinite(f)
            && (ag == null || (Float.isFinite(aGP) && Float.isFinite(aGN) && Float.isFinite(aSat)))
            && (bg == null || (Float.isFinite(bGP) && Float.isFinite(bGN) && Float.isFinite(bSat)));
        boolean a2 = f <= rate + 0.01f;
        boolean a3 = !present || Math.abs(ga.linkDrawCmd - sumF) <= 0.02f;
        boolean a4 = !present || (gb.linkInjCmd >= 0f && gb.linkInjCmd <= f + 0.02f);
        boolean a5 = present || (ga.linkDrawCmd == 0f && gb.linkInjCmd == 0f && flowCount == 0);
        // 012:state=2(分闸)合法;013(D33/D34)再扩 5=受限 / 6=同网
        boolean a6 = !present || state == 0 || state == 1 || state == -1 || state == 2
            || state == 5 || state == 6;

        t2Assert("finite", a1, "rate=" + rate + " loss=" + loss + " f=" + f);
        t2Assert("f<=rate", a2, "f=" + f + " rate=" + rate);
        t2Assert("present_draw_eq_f", a3, "present=" + present + " draw=" + ga.linkDrawCmd + " sumF=" + sumF);
        t2Assert("present_inj_range", a4, "present=" + present + " inj=" + gb.linkInjCmd + " f=" + f);
        t2Assert("absent_zero", a5, "present=" + present + " a.draw=" + ga.linkDrawCmd
            + " b.inj=" + gb.linkInjCmd + " flows=" + flowCount);
        t2Assert("state_enum", a6, "present=" + present + " state=" + state);

        // ---- 组装 JSON(schema 键名冻结)----
        long tick = Vars.state == null ? 0L : (long)Vars.state.tick;
        Jval root = Jval.newObject();
        root.put("schema", "blackstart.lab.t2");
        root.put("tick", tick);
        root.put("settleNanos", BlackstartLinks.lastSettleNanos);
        root.put("rate", rate);
        root.put("loss", loss);
        root.put("link", Jval.newObject()
            .put("present", present)
            .put("open", open)
            .put("f", f)
            .put("state", state)
            .put("valid", valid));
        root.put("a", Jval.newObject()
            .put("x", T2_AX).put("y", T2_AY)
            .put("hasSrc", hasSrc).put("kOwn", kOwn)
            .put("draw", ga.linkDrawCmd).put("inj", ga.linkInjCmd)
            .put("gP", aGP).put("gN", aGN).put("sat", aSat));
        root.put("b", Jval.newObject()
            .put("x", T2_BX).put("y", T2_BY)
            .put("draw", gb.linkDrawCmd).put("inj", gb.linkInjCmd)
            .put("gP", bGP).put("gN", bGN).put("sat", bSat));
        root.put("flows", flows);

        Jval asserts = Jval.newArray();
        asserts.add(Jval.newArray().add("finite").add(a1));
        asserts.add(Jval.newArray().add("f<=rate").add(a2));
        asserts.add(Jval.newArray().add("present_draw_eq_f").add(a3));
        asserts.add(Jval.newArray().add("present_inj_range").add(a4));
        asserts.add(Jval.newArray().add("absent_zero").add(a5));
        asserts.add(Jval.newArray().add("state_enum").add(a6));
        root.put("asserts", asserts);
        root.put("at", t2Timestamp());

        try{
            writeJson(jsonPath, root.toString(Jval.Jformat.plain));
        }catch(Throwable t){
            Log.err("[bs-lab] 写文件失败 " + jsonPath + " : " + t);
        }finally{
            Log.info("[bs-lab] done " + jsonPath);
        }
    }

    /** t2 断言输出(行首 [bs-lab];PASS 只报名,FAIL 带实测值)。 */
    private static void t2Assert(String name, boolean ok, String detail){
        if(ok){
            Log.info("[bs-lab] t2 pass " + name);
        }else{
            Log.info("[bs-lab] t2 FAIL " + name + " " + (detail == null ? "" : detail));
        }
    }

    /** kOwn 第 k 位的 x(固定序:前 8 个 y=1 排 x=40..47,后 2 个 y=2 排 x=40..41)。 */
    private static int t2OwnX(int k){
        return k < 8 ? 40 + k : 40 + (k - 8);
    }

    /** kOwn 第 k 位的 y。 */
    private static int t2OwnY(int k){
        return k < 8 ? 1 : 2;
    }

    /** B 站 node 列判据:x ∈ {34,37,40,43,46}(3 格周期;每个 mender 格都紧邻某 node 列,4 向)。 */
    private static boolean t2BNodeCol(int x){
        return x >= 34 && x <= 46 && (x - 34) % 3 == 0;
    }

    /** 现场摆一格(setNet 走引擎正常创建路径,会触发 created() 注册)。 */
    private static void t2Set(int x, int y, Block block){
        Tile t = Vars.world.tile(x, y);
        if(t != null) t.setNet(block, Team.sharded, 0);
    }

    /**
     * t2Set + 复刻真机放置完成步(015cal3 断链修复)。
     * 根因(字节码实证):Tile.setNet 全链 setNet→Call.setTile→Tile.setTile→setBlock→changeBuild→changed→
     * Building.init→created() —— 无任何 placed() 调用;范围连线只由 placed() 产生:
     *   真机链 = ConstructBlock.constructed → Call.constructFinish → 随即 tile.build.placed():
     *   节点(PowerNodeBuild):getPotentialLinks→configureAny,按 linkValid/同队/槽位建范围连线(双向落链+并图);
     *   其余带电力块(Building 基类):getNodeLinks 让射程内还有槽位的节点反向 configureAny 接它。
     * t4shed 场(设计意图=真机可建的范围网;015cal4 起 gw 居 (x0+8,Y),区锚=区内最小 pack,见 t4shed 常量块注释)
     * 必须走这条链;t2x/t4cls 等其它场保持 raw setNet(冻结基线)。
     */
    private static void t4SetPlaced(int x, int y, Block block){
        Tile t = Vars.world.tile(x, y);
        if(t == null) return;
        t.setNet(block, Team.sharded, 0);
        Building b = t.build;
        if(b != null) b.placed();
    }

    /** (x,y) 处 build 的方块就是 block?(现场计数用) */
    private static boolean t2IsBlock(int x, int y, Block block){
        Tile t = Vars.world.tile(x, y);
        return t != null && t.build != null && t.build.block == block;
    }

    /** 本地时间戳(yyyy-MM-dd HH:mm:ss;ROOT locale 与默认时区)。 */
    private static String t2Timestamp(){
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
            .format(new java.util.Date());
    }

    /**
     * 版本码(int)。任务书期望 1605,而 v160.5 的 {@code Version.buildString()} == "160.5":
     * 去掉小数点拼成整数即 1605。任一步读不到就退回 {@code Version.build}(实测 160)。
     * 注意:v160.5 里【没有】mindustry.Version 这个类,真实类是 mindustry.core.Version
     * (desktop.jar / server-release.jar 双份 jar tf + javap 实证,见 改动说明.md)。
     */
    private static int versionCode(String buildString){
        if(buildString != null){
            StringBuilder digits = new StringBuilder();
            for(int i = 0; i < buildString.length(); i++){
                char c = buildString.charAt(i);
                if(c >= '0' && c <= '9') digits.append(c);
            }
            if(digits.length() > 0){
                try{
                    return Integer.parseInt(digits.toString());
                }catch(NumberFormatException ignored){
                    // 数字串过长等极端情形:落到下面的原值兜底。
                }
            }
        }
        return Version.build;
    }

    /** 全图 tile 双层扫描:网关与原生电力方块各记一条。 */
    private static void scanWorld(Jval gates, Jval powerNodes){
        int width = Vars.world.width();
        int height = Vars.world.height();
        for(int x = 0; x < width; x++){
            for(int y = 0; y < height; y++){
                Tile tile = Vars.world.tile(x, y);
                if(tile == null) continue;
                Block block = tile.block();
                if(block == null) continue;

                if(block == BlackstartBlocks.gatewayNode){
                    gates.add(gateEntry(tile, x, y));
                }else if(block == Blocks.powerNode || block == Blocks.powerSource){
                    powerNodes.add(powerEntry(tile, x, y, block));
                }
            }
        }
    }

    /** 网关一条:build/links/graph/sat/rev/modlinks/config/valid。 */
    private static Jval gateEntry(Tile tile, int x, int y){
        Jval out = Jval.newObject();
        out.put("x", x);
        out.put("y", y);

        Building build = tile.build;
        out.put("build", build == null ? "null" : "ok");
        // build=null 时 links 记 null(schema 约定);非 ok 才如此,ok 但 power 缺失记空数组。
        out.put("links", build == null ? Jval.NULL : linksOf(build));
        out.put("graph", graphId(build));
        out.put("sat", satisfaction(build));
        out.put("rev", (int)GatewayNode.MOD_REV);

        Jval modlinks = Jval.newArray();
        if(build instanceof GatewayNode.GatewayNodeBuild gw){
            IntSeq ml = gw.modLinks;
            for(int i = 0; i < ml.size; i++) modlinks.add(pair(ml.get(i)));
        }
        out.put("modlinks", modlinks);

        Jval config = Jval.newArray();
        if(build != null && build.config() instanceof Point2[] points){
            for(int i = 0; i < points.length; i++){
                Point2 p = points[i];
                if(p == null) continue;
                config.add(Jval.newArray().add(p.x).add(p.y));
            }
        }
        out.put("config", config);

        if(build instanceof GatewayNode.GatewayNodeBuild gw){
            out.put("valid", gw.countValidModLinks() + "/" + gw.modLinks.size);
        }else{
            out.put("valid", "n/a");
        }
        return out;
    }

    /** 原生电力方块一条:block 名 + links/graph/sat。 */
    private static Jval powerEntry(Tile tile, int x, int y, Block block){
        Jval out = Jval.newObject();
        out.put("x", x);
        out.put("y", y);
        out.put("block", block.name);

        Building build = tile.build;
        if(build == null){
            out.put("links", Jval.NULL);
            out.put("graph", -1);
            out.put("sat", -1f);
        }else{
            out.put("links", linksOf(build));
            out.put("graph", graphId(build));
            out.put("sat", satisfaction(build));
        }
        return out;
    }

    /** power.links 逐条 Point2.unpack → 绝对格坐标对;power 缺失记空数组。 */
    private static Jval linksOf(Building build){
        Jval arr = Jval.newArray();
        PowerModule power = build.power;
        if(power != null && power.links != null){
            IntSeq links = power.links;
            for(int i = 0; i < links.size; i++) arr.add(pair(links.get(i)));
        }
        return arr;
    }

    /** 打包坐标 → [x,y](绝对格坐标)。 */
    private static Jval pair(int packed){
        Point2 p = Point2.unpack(packed);
        return Jval.newArray().add(p.x).add(p.y);
    }

    /** 电力图 ID;无图 -1。 */
    private static int graphId(Building build){
        if(build == null || build.power == null || build.power.graph == null) return -1;
        return build.power.graph.getID();
    }

    /** 电力图满足度;无图 -1。 */
    private static float satisfaction(Building build){
        if(build == null || build.power == null || build.power.graph == null) return -1f;
        return build.power.graph.getSatisfaction();
    }

    /** String 字段写入:null 显式写 JSON null(不用 put(name,(String)null),避免依赖其判空行为)。 */
    private static void putString(Jval object, String name, String value){
        if(value == null){
            object.put(name, Jval.NULL);
        }else{
            object.put(name, value);
        }
    }

    /**
     * 落盘。返回前保证「已尝试写最终文件」:
     *   1) tmp 写成功 → moveTo 原子替换;
     *   2) moveTo 抛 / tmp 写失败 → 直接写最终文件兜底(最终写失败才向上抛,由上层记 err)。
     */
    private static void writeJson(String jsonPath, String json){
        Fi target = new Fi(jsonPath);
        Fi tmp = new Fi(jsonPath + ".tmp");

        // 目标目录不存在时兜底创建(中枢给的 run 目录通常已存在,这里只做防御)。
        try{
            Fi parent = target.parent();
            if(parent != null) parent.mkdirs();
        }catch(Throwable ignored){
            // 目录创建失败不致命:下面的写操作会给出真实错误。
        }

        boolean tmpOk = false;
        try{
            tmp.writeString(json, false);
            tmpOk = true;
        }catch(Throwable t){
            Log.err("[bs-lab] 临时文件写入失败,转直接写最终文件: " + t);
        }

        if(tmpOk){
            try{
                tmp.moveTo(target);
                return;
            }catch(Throwable t){
                Log.err("[bs-lab] moveTo 失败,转直接写最终文件: " + t);
            }
        }

        target.writeString(json, false);
    }

    /** Throwable → 栈字符串(截断 4000 字符,照 schema)。 */
    private static String stackTrace(Throwable t){
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String text = sw.toString();
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }
}
