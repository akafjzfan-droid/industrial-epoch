package blackstart;

import arc.Core;
import arc.func.Cons;
import arc.math.geom.Point2;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.MultiPacker;
import mindustry.mod.Mod;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.modules.PowerModule;

import java.util.Arrays;

/** 现实电力(代号 Blackstart)主类。 */
public class BlackstartMod extends Mod {

    public BlackstartMod() {
        Log.info("[blackstart] 载入(现实电力 · 桥版本)");
    }

    @Override
    public void init() {
        NativeGeneratorRetrofit.install();
        NativeNodeRetrofit.install();
        NativeNodeZones.install();
        EngineeringFlowLimits.install();
        NativeZoneUi.install();
        // 预内容初始化(设置、全局注册铺垫)。当前为空。
        // T1(I1)追加:可观测层骨架批的事件挂载与 HUD 按钮挂载,整包集中在 BlackstartView.register() 内
        // (BlackstartMod 不留散落逻辑);无头服务端在 register 内部判 Vars.headless 早退。
        BlackstartView.register();
        // T5(I4)追加:客户端事件环挂载(纯数据;无头下必须真实生效——装具 bs-lab-events 依赖它)。
        BlackstartEvents.install();
        // T2(T2-G0)追加:链路结算内核挂载(注册表 + Trigger.update 15Hz 节流 + 脏标记)。
        // 与 BlackstartEvents.install() 同区;无头服务端同样必须真实生效(装具 bs-lab-t2 依赖)。
        BlackstartLinks.install();
        // T4(014)追加:安全网结算内核挂载(岛聚合 + 分类 + 缺口率 + 岛级状态机);无头必须真实生效(装具 bs-zones 依赖)。
        // 必须在 BlackstartLinks.install() 之后调用(T2 先结算,SafetyNet 同帧读最新快照)。
        SafetyNet.install();
        BlackstartContinuity.install();
        BlackstartDynamics.install();
    }

    @Override
    public void loadContent() {
        BlackstartBlocks.load();
    }

    @Override
    public void packSprites(MultiPacker packer) {
        // S0 headless 审计(2026-10-06):无头服务端没有贴图管线(BlackstartSprites 会碰 Pixmap),
        // 防御式早退;客户端行为不变(Vars.headless 在桌面端恒 false)。
        if(Vars.headless) return;
        BlackstartSprites.pack(packer);
    }

    /**
     * S1 实验版临时探针:注册客户端命令 bs-status / bs-set。
     * bs-status 打印每个网关节点的连线、电力图状态与当前旗标;
     * bs-set 现场翻转网关静态方块实例上的布尔旗标(免重编译重装)。
     * 一次性调试用,spike 结论落地后随实验版一并撤掉。
     *
     * S3 追加(2026-10-05):bs-dump / bs-snap / bs-link 三个自管链路探针;
     * 旧命令 bs-status / bs-set 的逻辑与输出格式【一字未动】。
     */
    @Override
    public void registerClientCommands(CommandHandler handler) {
        Cons<String[]> training = args -> BlackstartPanels.showTraining();
        handler.register("bs-training", "", BlackstartText.get("blackstart.training.title", "Black-start exercise"), training);
        Cons<String[]> blackbox = args -> BlackstartPanels.showBlackbox();
        handler.register("bs-blackbox", "", BlackstartText.get("blackstart.blackbox.command", "View and copy the local event blackbox"), blackbox);
        // 先落成显式 Cons<String[]> 变量再传入:避免 lambda 内联时与其它函数式接口重载产生歧义。
        Cons<String[]> runner = args -> printGatewayStatus();
        handler.register("bs-status", "", "S1 探针:打印网关节点连线与电力图状态", runner);

        // S1b 增补:现场翻字段命令,避免为改一个 boolean 反复重编译重装。
        Cons<String[]> setter = args -> setGatewayFlag(args);
        handler.register("bs-set", "<字段> <true|false>", "S1 探针:翻转网关旗标", setter);

        // ---- S3 载体探针(自管链路 modLinks;power.links 只作只读对照)----
        Cons<String[]> dumper = args -> printGatewayDump();
        handler.register("bs-dump", "", "S3 探针:打印网关自管链路/rev/config 与原生 links 对照", dumper);

        Cons<String[]> snapper = args -> printGatewaySnapshot();
        handler.register("bs-snap", "", "S3 探针:全图网关状态指纹(modlinks 排序后稳定 hash)", snapper);

        // 参数串每个 token 必须包 <...> 或 [...] —— 裸 token 会让 Command$Command 构造器在
        // register() 这一刻直接抛 IllegalArgumentException("Malformed param ..."),模块加载失败;
        // 这是注册硬要求,不是风格选择(2026-10-05 复核更正,verify:probes#1)。
        // 本命令五个参数全部用【可选】形态 []:缺参时 runner 仍必达(收到 0..5 个实参),
        // 由 linkGateway 的 parts.length<5 守卫打印 [bs] 用法行 —— 若用必选 <> 形态,常规缺参会被
        // 引擎 fewArguments 提前拦截,只发玩家聊天、不进日志,自动化测试在无头日志里抓不到(verify:probes#0)。
        // >5 个实参仍由引擎 manyArguments 拦(接受)。bs-set 的 <字段> <true|false> 保持原样。
        Cons<String[]> linker = args -> linkGateway(args);
        handler.register("bs-link", "[x1] [y1] [x2] [y2] [add|del]", "S3 探针:对网关增/删一条自管链路", linker);

        // T1(I1)追加:`bs-view` 电网视图开关(全量/仅模组/关)。参数串全可选 [](理由同上 bs-link);
        // 用法行由 BlackstartView.command 自己打(缺参时 runner 仍必达,用法行进日志)。
        // 既有五条注册语句一字未动,本条只追加在末尾。
        Cons<String[]> viewer = args -> BlackstartView.command(args);
        handler.register("bs-view", "[on|off|only|debug|rescan|island|hover|badges|events|others|lowfx|readout|preview] [on|off]", "T1 探针:电网视图开关(全量/仅模组/关)", viewer);

        // T2(G3,012)追加:链路调参 / 开断探针。参数串照 bs-link 全可选 [](客户端缺参也必达 handler,
        // 用法行进日志);server 端同一套 handler 在 registerServerCommands 再注册一次(无头服务端无 Vars.ui)。
        Cons<String[]> tuner = args -> handleBsTune(args);
        handler.register("bs-tune", "[键] [值]", "T2 探针:链路参数调校(rate/loss/settle/sto)", tuner);

        Cons<String[]> opener = args -> handleBsOpen(args);
        handler.register("bs-open", "[x1] [y1] [x2] [y2] [open|close]", "T2 探针:链路开断位(close=分闸/F=0, open=复通爬坡)", opener);

        // T2(G4,013)追加:档序 / 配额探针。参数串照 bs-link 全可选 [](客户端缺参也必达 handler,
        // 用法行进日志);token 内不得含空格(Arc CommandHandler 按空格 split 后逐 token 校验 [ ])。
        // server 端同一套 handler 在 registerServerCommands 再注册一次。
        Cons<String[]> tierer = args -> handleBsTier(args);
        handler.register("bs-tier", "[x1] [y1] [x2] [y2] [1|2|3]", "T2 探针:置链路档序 tier(1 低 3 高)", tierer);

        Cons<String[]> granter = args -> handleBsGrant(args);
        handler.register("bs-grant", "[x1] [y1] [x2] [y2] [0..1]", "T2 探针:置链路配额 grant(0–1)", granter);

        // T4(014)追加:岛/区分类与岛状态全量 dump。参数串 ""(无参,照 bs-dump 先例);
        // 命令层只调用 SafetyNet.dump(),逻辑在内核;server 端同一套在 registerServerCommands 再注册。
        Cons<String[]> zoner = args -> SafetyNet.dump();
        handler.register("bs-zones", "", "T4 探针:全量 dump 岛/区分类与岛状态", zoner);

        // T3 016:全可选形态,缺参必达共享 handler。
        Cons<String[]> protector = args -> handleBsProt(args);
        handler.register("bs-prot", "[open|close|tier] [x] [y] [v]", "保护装置:dump / open|close x y / tier x y v", protector);

        // M7(契约玩家层)追加:区域覆盖便捷入口面板。参数串 ""(无参,照 bs-zones 先例);
        // 逻辑/守卫在 BlackstartPanels.showZones(headless / 无 UI 早退);只读门 = SafetyNet 公开面。
        Cons<String[]> zonePanel = args -> BlackstartPanels.showZones();
        handler.register("bs-zone-panel", "", "区域调度:列出岛/区档位与状态,可手动写四档", zonePanel);
    }

    /**
     * S0 装具(2026-10-06):无头服务端命令注册。
     *
     * 无头服务端【没有 Vars.ui】,bs-* 客户端命令(registerClientCommands)在无头下不可达;
     * ServerControl 在启动收尾会回调本方法(v160.5 Mod 基类已有空实现,签名见其源码)。
     * 这里只注册一条:`bs-lab-smoke <jsonPath>` → BlackstartLab.smoke(args[0])。
     * 参数串照既有惯例用必选 `<>`:服务端控制台不受客户端 fewArguments 的聊天拦截问题影响
     * (对比 registerClientCommands 里 bs-link 全可选 [](2026-10-05 复核:客户端缺参只发聊天不进日志))。
     * runner 收到的 String[] 不含命令名(与 bs-set/bs-link 同一字节码实证);仍加长度守卫防御。
     * 既有 registerClientCommands 与全部 bs-* 逻辑、输出格式【一字未动】。
     */
    @Override
    public void registerServerCommands(CommandHandler handler) {
        Cons<String[]> labSmoke = args -> {
            if(args.length < 1){
                emit("[bs-lab] 用法: bs-lab-smoke <jsonPath> [case] [seq]");
                return;
            }
            BlackstartLab.smoke(args[0], args.length > 1 ? args[1] : null, args.length > 2 ? args[2] : null);
        };
        handler.register("bs-lab-smoke", "<jsonPath> [case] [seq]", "S0 装具:采集运行时事实并写 JSON", labSmoke);

        // I4(T5-I0)追加:事件环导出 / 参数写入门 / 装具自测(纯数据,无头路径)。
        Cons<String[]> labEvents = args -> {
            if(args.length < 1){ emit("[bs-lab] 用法: bs-lab-events <jsonPath>"); return; }
            BlackstartLab.events(args[0]);
        };
        handler.register("bs-lab-events", "<jsonPath>", "I4 装具:导出事件环 JSON", labEvents);

        Cons<String[]> labSetparam = args -> {
            if(args.length < 2){ emit("[bs-lab] 用法: bs-lab-setparam <key> <value>"); return; }
            BlackstartLab.setParam(args[0], args[1]);
        };
        handler.register("bs-lab-setparam", "<key> <value>", "I4 装具:写 rules.tags 参数(BlackstartParams.set)", labSetparam);

        Cons<String[]> labEvtest = args -> {
            if(args.length < 1){ emit("[bs-lab] 用法: bs-lab-evtest <jsonPath>"); return; }
            BlackstartLab.evtest(args[0]);
        };
        handler.register("bs-lab-evtest", "<jsonPath>", "I4 装具:事件环 push/去抖自测并导出", labEvtest);

        // T2(G0+G1)追加:链路验收装具(无头服务端唯一入口;ops: build / link / probe)。
        // 参数串照 server 惯例用必选 <> + 可选 [](服务端控制台不受客户端 fewArguments 聊天拦截影响)。
        Cons<String[]> labT2 = args -> {
            if(args.length < 1){
                emit("[bs-lab] 用法: bs-lab-t2 build <kLoad> <kOwn> [nosrc] | link <on|off> | probe <jsonPath>");
                return;
            }
            BlackstartLab.t2(args);
        };
        handler.register("bs-lab-t2", "<op> [a] [b] [c] [d]", "T2 装具:链路验收(build/link/probe)", labT2);

        // 018:新装具入口必须在无头服务端注册;判据/格式均在 Lab,此处只转调。
        Cons<String[]> labTie = args -> BlackstartLab.tie(args);
        handler.register("bs-lab-tie", "<x1> <y1> <x2> <y2>", "018 装具:两点岛态并网窗口探针", labTie);

        // T2(G3,012)追加:与客户端同源的两条链路探针(无头服务端唯一可达面)。
        // handler 逻辑共用(handleBsTune / handleBsOpen),不复制;参数串全可选 [] 与 client 端一致。
        Cons<String[]> tunerS = args -> handleBsTune(args);
        handler.register("bs-tune", "[键] [值]", "T2 探针:链路参数调校(rate/loss/settle/sto)", tunerS);

        Cons<String[]> openerS = args -> handleBsOpen(args);
        handler.register("bs-open", "[x1] [y1] [x2] [y2] [open|close]", "T2 探针:链路开断位(close=分闸/F=0, open=复通爬坡)", openerS);

        // T2(G4,013)追加:与客户端同源的两条档序/配额探针(无头服务端唯一可达面)。
        // handler 逻辑共用(handleBsTier / handleBsGrant),不复制;参数串全可选 [] 与 client 端一致。
        Cons<String[]> tiererS = args -> handleBsTier(args);
        handler.register("bs-tier", "[x1] [y1] [x2] [y2] [1|2|3]", "T2 探针:置链路档序 tier(1 低 3 高)", tiererS);

        Cons<String[]> granterS = args -> handleBsGrant(args);
        handler.register("bs-grant", "[x1] [y1] [x2] [y2] [0..1]", "T2 探针:置链路配额 grant(0–1)", granterS);

        // T4(014)追加:与客户端同源的岛/区 dump 探针(无头服务端唯一可达面)。
        // handler 逻辑共用(SafetyNet.dump),不复制;参数串 "" 与 client 端一致。
        Cons<String[]> zonerS = args -> SafetyNet.dump();
        handler.register("bs-zones", "", "T4 探针:全量 dump 岛/区分类与岛状态", zonerS);

        Cons<String[]> protectorS = args -> handleBsProt(args);
        handler.register("bs-prot", "[open|close|tier] [x] [y] [v]", "保护装置:dump / open|close x y / tier x y v", protectorS);
    }

    /**
     * 遍历全图 tile,挑出本 mod 的网关节点并逐行输出连线/图状态。
     *
     * S2 改动:不再遍历 Groups.build —— 网关继承的原版 PowerNode 构造器把 update 置 false,
     * 而建筑进 Groups.build 的唯一入口 Building.init(...) 要求 block.update 为真,
     * 所以网关永远不在 Groups.build,旧写法永远「无网关节点」。命令级手动触发,非每帧,
     * 全图双层循环无性能顾虑。
     */
    private void printGatewayStatus() {
        int found = 0;

        for(int x = 0; x < Vars.world.width(); x++){
            for(int y = 0; y < Vars.world.height(); y++){
                Tile tile = Vars.world.tile(x, y);
                if(tile == null || tile.block() != BlackstartBlocks.gatewayNode) continue;
                found++;

                Building b = tile.build;

                StringBuilder line = new StringBuilder();
                line.append("[bs] (").append(x).append(',').append(y).append(") u=");

                // 防御:tile.build 为 null 时该行照打(u 取静态方块实例),
                // links 段写 build=null,并跳过靠后的 graph/sat/cp/autolink 字段。
                if(b == null){
                    line.append(BlackstartBlocks.gatewayNode.update).append(" links=[build=null]");
                    emit(line.toString());
                    continue;
                }

                // u= 读 b.block.update:自证「为什么这类方块不在 Groups.build」。
                line.append(b.block.update).append(" links=[");

                PowerModule power = b.power;
                if(power != null && power.links != null){
                    IntSeq links = power.links;
                    for(int i = 0; i < links.size; i++){
                        if(i > 0) line.append(',');
                        // links 元素是打包坐标,unpack 后是绝对格坐标
                        Point2 p = Point2.unpack(links.get(i));
                        line.append('(').append(p.x).append(',').append(p.y).append(')');
                    }
                }
                line.append("] graph=");

                PowerGraph graph = power == null ? null : power.graph;
                if(graph != null){
                    line.append(graph.getID()).append(" sat=").append(graph.getSatisfaction());
                }else{
                    line.append("null sat=null");
                }

                // S1b 增补:追加当前旗标值,便于与 bs-set 配合现场观察。
                // 两个值直接读静态方块实例:过滤条件已保证该方块就是 BlackstartBlocks.gatewayNode;
                // autolink 在 b.block(静态类型 Block)上没有,故不走 b.block。
                line.append(" cp=").append(BlackstartBlocks.gatewayNode.connectedPower)
                    .append(" autolink=").append(BlackstartBlocks.gatewayNode.autolink);

                String text = line.toString();
                emit(text);
            }
        }

        if(found == 0){
            emit("[bs] 无网关节点");
        }
    }

    /**
     * S3 探针 A1:bs-dump —— 全图网关一行,在 bs-status 行格式的【尾部】追加三个自管链路字段:
     *   rev=<MOD_REV>                 mod 数据版本(序列化覆写写的那个 revision)
     *   modlinks=[(x,y),...]          自管链路的【绝对】格坐标,顺序 = 存储顺序
     *   config=[(dx,dy),...]          由 config() 派生、【相对】坐标(蓝图/联机实际载荷)
     * 原 links=(power.links)保留作只读对照 —— 红线允许「只读展示」。
     *
     * S3.1 追加(D1,合本 §8;既有字段一字不动,行尾按固定顺序续接):
     *   valid=k/n                     消费侧 modLinkValid 通过数 / 表长;非 GatewayNodeBuild 行 = n/a
     *   residue=r from=[(x,y),...]    全图 power.links 中等于本网关 pos 的条目总数(含重复)与命中表主锚点
     *   gmembers=m peers=[(x,y),...]  与本网关 power.graph 同对象的【其他】建筑数与其坐标
     * 列表 >8 条截断并在末尾补 '+'。实现 = 一次 tile 扫描预聚合(手动命令,O(G×N) 可接受)。
     *
     * 扫描骨架与 printGatewayStatus 完全一致(双层 tile 扫描,不遍历 Groups.build)。
     * 为不动旧命令逻辑,这里是独立副本而不是抽公共方法。
     */
    private void printGatewayDump() {
        int found = 0;

        // ---- S3.1 预聚合源:一次 tile 扫描收集全部中心建筑(isCenter 去重;F5)----
        Seq<Building> all = new Seq<>();
        for(int x = 0; x < Vars.world.width(); x++){
            for(int y = 0; y < Vars.world.height(); y++){
                Tile t = Vars.world.tile(x, y);
                if(t == null || t.build == null || !t.isCenter()) continue;
                all.add(t.build);
            }
        }

        for(int x = 0; x < Vars.world.width(); x++){
            for(int y = 0; y < Vars.world.height(); y++){
                Tile tile = Vars.world.tile(x, y);
                if(tile == null || tile.block() != BlackstartBlocks.gatewayNode) continue;
                found++;

                Building b = tile.build;

                StringBuilder line = new StringBuilder();
                line.append("[bs] (").append(x).append(',').append(y).append(") u=");

                if(b == null){
                    line.append(BlackstartBlocks.gatewayNode.update)
                        .append(" links=[build=null] rev=").append(GatewayNode.MOD_REV)
                        .append(" modlinks=[build=null] config=[build=null]");
                    line.append(" valid=n/a");
                    appendResidueFields(line, all, Point2.pack(x, y), null);
                    appendLinkFields(line, null);                       // T2(D12):行尾追加
                    emit(line.toString());
                    continue;
                }

                line.append(b.block.update).append(" links=[");

                PowerModule power = b.power;
                if(power != null && power.links != null){
                    IntSeq links = power.links;
                    for(int i = 0; i < links.size; i++){
                        if(i > 0) line.append(',');
                        Point2 p = Point2.unpack(links.get(i));
                        line.append('(').append(p.x).append(',').append(p.y).append(')');
                    }
                }
                line.append("] graph=");

                PowerGraph graph = power == null ? null : power.graph;
                if(graph != null){
                    line.append(graph.getID()).append(" sat=").append(graph.getSatisfaction());
                }else{
                    line.append("null sat=null");
                }

                line.append(" cp=").append(BlackstartBlocks.gatewayNode.connectedPower)
                    .append(" autolink=").append(BlackstartBlocks.gatewayNode.autolink);

                // ---- S3 追加段 ----
                line.append(" rev=").append(GatewayNode.MOD_REV);

                line.append(" modlinks=[");
                if(b instanceof GatewayNode.GatewayNodeBuild gw){
                    IntSeq modLinks = gw.modLinks;
                    for(int i = 0; i < modLinks.size; i++){
                        if(i > 0) line.append(',');
                        Point2 p = Point2.unpack(modLinks.get(i));
                        line.append('(').append(p.x).append(',').append(p.y).append(')');
                    }
                }else{
                    // 理论上不会走到:内嵌 Build 发现失败(无嵌入类 → 上溯原版 PowerNode$PowerNodeBuild;
                    // 构造器签名不符 → 兜底裸 Building::create)。两条路都够不到本类的 GatewayNodeBuild。
                    line.append("n/a");
                }
                line.append(']');

                line.append(" config=[");
                Object cfg = b.config();
                if(cfg instanceof Point2[] points){
                    for(int i = 0; i < points.length; i++){
                        if(i > 0) line.append(',');
                        line.append('(').append(points[i].x).append(',').append(points[i].y).append(')');
                    }
                }else{
                    line.append(cfg == null ? "null" : "n/a");
                }
                line.append(']');

                // ---- S3.1 追加段:valid / residue / from / gmembers / peers(固定顺序)----
                line.append(" valid=");
                if(b instanceof GatewayNode.GatewayNodeBuild gw){
                    line.append(gw.countValidModLinks()).append('/').append(gw.modLinks.size);
                }else{
                    line.append("n/a");
                }
                appendResidueFields(line, all, b.pos(), b);
                appendLinkFields(line, b);                              // T2(D12):行尾追加

                emit(line.toString());
            }
        }

        if(found == 0){
            emit("[bs] 无网关节点");
        }
    }

    /**
     * S3.1 追加字段:residue=r from=[…] gmembers=m peers=[…](合本 §8;D1)。
     * self 为 null 时(build=null 行)只能算 residue/from;gmembers=0、peers=[](不崩)。
     *
     * @param all    一次 tile 扫描收集的全部中心建筑(isCenter 去重)
     * @param packed 本网关的绝对 pack 坐标
     * @param self   本网关 Building;build=null 行传 null
     */
    private static void appendResidueFields(StringBuilder line, Seq<Building> all, int packed, Building self){
        // residue:全图所有建筑 power.links 中等于本网关 pos 的条目总数(含重复);from = 命中表主锚点去重
        int residue = 0;
        IntSet fromPos = new IntSet();
        Seq<Point2> from = new Seq<>();
        for(int i = 0; i < all.size; i++){
            Building b = all.get(i);
            PowerModule power = b.power;
            if(power == null) continue;
            IntSeq links = power.links;
            for(int j = 0; j < links.size; j++){
                if(links.get(j) == packed){
                    residue++;
                    if(fromPos.add(b.pos())) from.add(new Point2(b.tileX(), b.tileY()));
                }
            }
        }

        // gmembers/peers:与本网关 power.graph 同对象的【其他】建筑;判据 = peers 只应出现 4 邻贴边成员
        int gmembers = 0;
        Seq<Point2> peers = new Seq<>();
        if(self != null && self.power != null && self.power.graph != null){
            for(int i = 0; i < all.size; i++){
                Building b = all.get(i);
                if(b == self || b.power == null || b.power.graph != self.power.graph) continue;
                gmembers++;
                peers.add(new Point2(b.tileX(), b.tileY()));
            }
        }

        line.append(" residue=").append(residue).append(" from=");
        appendPoints(line, from, 8);
        line.append(" gmembers=").append(gmembers).append(" peers=");
        appendPoints(line, peers, 8);
    }

    /** 追加 [(x,y),…];超过 limit 条截断并在末尾补 '+'。 */
    private static void appendPoints(StringBuilder line, Seq<Point2> points, int limit){
        line.append('[');
        int n = Math.min(points.size, limit);
        for(int i = 0; i < n; i++){
            if(i > 0) line.append(',');
            Point2 p = points.get(i);
            line.append('(').append(p.x).append(',').append(p.y).append(')');
        }
        if(points.size > limit) line.append(",+");
        line.append(']');
    }

    /**
     * T2(D12)bs-dump 行尾追加字段(格式冻结:只许尾追,既有字段一个字节不动):
     *   rate=&lt;%.2f&gt; loss=&lt;%.4f&gt; cmd draw=&lt;%.2f&gt; inj=&lt;%.2f&gt; flows=[(x,y,F %.2f,s),…|+]
     *
     * · rate = 方块级 linkRating;loss = BlackstartParams 权威值(命令级每行读一次,无性能顾虑);
     * · cmd = 【字面分隔标记词】(设计原文口径,不是等号字段,别改名别加 '=');
     * · flows 按 modLinks 序,只列对端 build {@code instanceof GatewayNodeBuild} 的条目
     *   (含同图/悬挂,状态字节已表达;记录条目跳过不列;空 → flows=[];超 8 条列前 8 并尾部补 '+');
     * · b == null(build=null 行)无运行时字段:draw/inj 记 0、flows=[](行格式仍完整)。
     */
    private static void appendLinkFields(StringBuilder line, Building b){
        float rate = BlackstartBlocks.gatewayNode.linkRating;
        float loss = BlackstartParams.getFloat(BlackstartParams.KEY_LOSS, BlackstartParams.DEFAULT_LOSS);
        line.append(" rate=").append(fmt(rate, 2))
            .append(" loss=").append(fmt(loss, 4))
            .append(" cmd draw=");
        if(!(b instanceof GatewayNode.GatewayNodeBuild gw)){
            line.append("0.00 inj=0.00 flows=[]");
            return;
        }
        line.append(fmt(gw.linkDrawCmd, 2)).append(" inj=").append(fmt(gw.linkInjCmd, 2)).append(" flows=[");
        int listed = 0;
        boolean truncated = false;
        for(int i = 0; i < gw.modLinks.size; i++){
            int packed = gw.modLinks.get(i);
            // 只列对端为网关的条目(搬运链路目标);机器/原版节点的记录条目跳过。
            if(!(Vars.world.build(packed) instanceof GatewayNode.GatewayNodeBuild)) continue;
            if(listed >= 8){
                truncated = true;
                break;
            }
            if(listed > 0) line.append(',');
            Point2 p = Point2.unpack(packed);
            float f = i < gw.linkFlow.length ? gw.linkFlow[i] : 0f;
            int s = i < gw.linkState.length ? gw.linkState[i] : 0;
            line.append('(').append(p.x).append(',').append(p.y).append(',').append(fmt(f, 2)).append(',').append(s).append(')');
            listed++;
        }
        if(truncated) line.append('+');
        line.append(']');
    }

    /** 定点小数格式化(locale 无关 ROOT:'%.Nf');探针命令级用,非稳态路径。 */
    private static String fmt(float v, int decimals){
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
    }

    /**
     * S3 探针 A2:bs-snap —— 全图网关状态指纹,一行:
     *     [bs] SNAP n=<网关数> hash=<十六进制>
     *
     * 每座网关收集:(坐标) + modLinks【排序后】的集合 —— 排序保证「modLinks 书写顺序不同必须同 hash」。
     * 全部拼成规范串后做 FNV-1a(64 位)。同一张图同一状态 ⇒ 同 hash(供存档/蓝图/联机前后对拍)。
     * 无网关时 n=0 hash=0。
     */
    private void printGatewaySnapshot() {
        int count = 0;
        long hash = 0xcbf29ce484222325L; // FNV-1a 64 位 offset basis

        for(int x = 0; x < Vars.world.width(); x++){
            for(int y = 0; y < Vars.world.height(); y++){
                Tile tile = Vars.world.tile(x, y);
                if(tile == null || tile.block() != BlackstartBlocks.gatewayNode) continue;
                count++;

                StringBuilder canon = new StringBuilder();
                canon.append(x).append(',').append(y).append('|');

                Building b = tile.build;
                if(b instanceof GatewayNode.GatewayNodeBuild gw){
                    int[] sorted = new int[gw.modLinks.size];
                    for(int i = 0; i < sorted.length; i++){
                        sorted[i] = gw.modLinks.get(i);
                    }
                    Arrays.sort(sorted);
                    for(int packed : sorted){
                        canon.append(packed).append(',');
                    }
                }else{
                    // build=null 或未接管的 Build:用固定标记,保证同状态同 hash。
                    canon.append(b == null ? "build=null" : "build=vanilla");
                }

                hash = fnv1a(hash, canon.toString());
            }
        }

        if(count == 0){
            emit("[bs] SNAP n=0 hash=0");
            return;
        }
        emit("[bs] SNAP n=" + count + " hash=" + Long.toHexString(hash));
    }

    /** FNV-1a 64 位,逐 char 累积;稳定、无分配、与 JDK 版本无关。 */
    private static long fnv1a(long hash, String text) {
        for(int i = 0; i < text.length(); i++){
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L; // FNV prime
        }
        return hash;
    }

    /**
     * S3 探针 A3:bs-link <x1> <y1> <x2> <y2> add|del —— 对 (x1,y1) 处的网关增/删一条指向 (x2,y2) 的自管链路。
     *
     * 参数不合法(缺参 / 非数字 / 操作符不是 add|del / x1y1 不是网关 / x2y2 越界)一律打印用法行。
     * 命令注册串已改全可选(见 registerClientCommands 注释):缺参也必达本方法,用法行进日志。
     * 内部走 GatewayNodeBuild.addModLink / removeModLink(即经 configureAny 广播,单机/服务器同一路径)。
     * S3.1(b) 起:del 经 removeModLink 走 Point2[] 余表替换;add 仍走 Integer ensure(只增不减)。
     *
     * 参数约定同 bs-set:runner 收到的 String[] 【只含参数,不含命令名】。
     */
    private void linkGateway(String[] args) {
        String usage = "[bs] 用法: bs-link <x1> <y1> <x2> <y2> add|del";

        // 归一化(与 bs-set 同一套,见 setGatewayFlag)。边界(verify:probes#3):全角空格只有落在
        // 已交付 token【内部】时才容错;它当分隔符(『4　add』)或紧跟命令名时到不了 runner——
        // 引擎只按半角空格切 token,那两种输入会分别触发 fewArguments / unknownCommand。
        String[] parts = String.join(" ", args).replace('　', ' ').trim().split("\\s+");
        if(parts.length < 5){
            emit(usage);
            return;
        }

        int x1, y1, x2, y2;
        try{
            x1 = Integer.parseInt(parts[0]);
            y1 = Integer.parseInt(parts[1]);
            x2 = Integer.parseInt(parts[2]);
            y2 = Integer.parseInt(parts[3]);
        }catch(NumberFormatException e){
            emit(usage);
            return;
        }

        String op = parts[4];
        if(!"add".equals(op) && !"del".equals(op)){
            emit(usage);
            return;
        }

        Tile tile = Vars.world.tile(x1, y1);
        if(tile == null || tile.block() != BlackstartBlocks.gatewayNode
            || !(tile.build instanceof GatewayNode.GatewayNodeBuild gw)){
            emit(usage);
            return;
        }

        // pack 前显式范围校验:Point2.pack 对越界坐标做 16 位截断(如 65537→1、65536→0),
        // 会静默指向另一格并报成功(verify:probes#4)。x1/y1 走 world.tile 天然免疫,这里只看目标。
        if(x2 < 0 || y2 < 0 || x2 >= Vars.world.width() || y2 >= Vars.world.height()){
            emit(usage);
            return;
        }

        int before = gw.modLinks.size;
        int packed = Point2.pack(x2, y2);
        if("add".equals(op)){
            gw.addModLink(packed);
        }else{
            gw.removeModLink(packed);
        }

        // 回显带 before->after:no-op(重复 add / del 不存在项 / 目标非法 / 超上限)一眼可辨(verify:probes#5)。
        emit("[bs] bs-link " + op + " (" + x1 + "," + y1 + ") " + BlackstartText.get("blackstart.text.flow", ">") + " (" + x2 + "," + y2
            + ") modlinks=" + before + "->" + gw.modLinks.size);
    }

    /** bs-tune 用法行(逐字冻结,任务-012 D25;进日志供中枢抓行)。 */
    private static final String BS_TUNE_USAGE =
        "[bs] bs-tune 用法: bs-tune <rate|loss|settle|sto> <值>  (范围:rate 0.1–255 / loss 0–0.04 / settle 2–6 / sto 0.1–0.5)";

    /** bs-open 用法行(逐字冻结,任务-012 D25)。 */
    private static final String BS_OPEN_USAGE =
        "[bs] bs-open 用法: bs-open <x1> <y1> <x2> <y2> <open|close>";

    /**
     * T2(G3,012)探针:bs-tune &lt;rate|loss|settle|sto&gt; &lt;值&gt; —— 链路参数调校。
     *
     * 命令层只做:参数解析 / 范围校验 / 调用内核({@link BlackstartLinks#tuneRate} 等) / 回显;
     * 写值与置脏都在内核,handler 不放内核逻辑。参数串在注册处全可选 [](缺参也必达本方法,用法行进日志)。
     * 值解析失败 → 用法行且不写;范围校验失败 → 超出范围行且不写。回显格式逐字冻结(任务-012 D25):
     * rate/sto 用 %.2f、loss 用 %.4f、settle 用整数;`→` 是 U+2192。
     */
    private void handleBsTune(String[] args){
        // 归一化照 linkGateway(全角空格容错;runner 收到的 String[] 不含命令名)。
        String[] parts = String.join(" ", args).replace('　', ' ').trim().split("\\s+");
        if(parts.length < 2){
            emit(BS_TUNE_USAGE);
            return;
        }
        String key = parts[0];
        String raw = parts[1];

        if("settle".equals(key)){
            int v;
            try{
                v = Integer.parseInt(raw);
            }catch(NumberFormatException e){
                emit(BS_TUNE_USAGE);
                return;
            }
            if(v < 2 || v > 6){
                emit("[bs] bs-tune settle 超出范围 [2,6]: " + raw);
                return;
            }
            int old = BlackstartLinks.tuneSettle(v);
            emit("[bs] bs-tune settle " + old + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + v);
            return;
        }

        float v;
        try{
            v = Float.parseFloat(raw);
        }catch(NumberFormatException e){
            emit(BS_TUNE_USAGE);
            return;
        }
        if(!Float.isFinite(v)){                       // "NaN"/"Infinity" 解析成功但非有限值 ⇒ 当坏值
            emit(BS_TUNE_USAGE);
            return;
        }

        if("rate".equals(key)){
            if(v < 0.1f || v > 255f){
                emit("[bs] bs-tune rate 超出范围 [0.1,255]: " + raw);
                return;
            }
            float old = BlackstartLinks.tuneRate(v);
            emit("[bs] bs-tune rate " + fmt(old, 2) + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + fmt(v, 2));
        }else if("sto".equals(key)){
            if(v < 0.1f || v > 0.5f){
                emit("[bs] bs-tune sto 超出范围 [0.1,0.5]: " + raw);
                return;
            }
            float old = BlackstartLinks.tuneSto(v);
            emit("[bs] bs-tune sto " + fmt(old, 2) + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + fmt(v, 2));
        }else if("loss".equals(key)){
            if(v < 0f || v > 0.04f){
                emit("[bs] bs-tune loss 超出范围 [0,0.04]: " + raw);
                return;
            }
            float old = BlackstartLinks.tuneLoss(v);
            if(Float.isNaN(old)){                     // set 被拒(从端 / 无世界 / 键前缀)
                emit("[bs] bs-tune FAIL loss 写入被拒(见日志)");
                return;
            }
            emit("[bs] bs-tune loss " + fmt(old, 4) + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + fmt(v, 4));
        }else{
            emit(BS_TUNE_USAGE);
        }
    }

    /**
     * T2(G3,012)探针:bs-open &lt;x1&gt; &lt;y1&gt; &lt;x2&gt; &lt;y2&gt; &lt;open|close&gt; —— 开断(close)/复通(open)一条自管链路
     * (词义裁定见 {@link BlackstartLinks#openLink})。
     * 命令层只做解析 / 调用 {@link BlackstartLinks#openLink} / 回显;三回显分支格式逐字冻结(任务-012 D25)。
     */
    private void handleBsOpen(String[] args){
        String[] parts = String.join(" ", args).replace('　', ' ').trim().split("\\s+");
        if(parts.length < 5){
            emit(BS_OPEN_USAGE);
            return;
        }
        int x1, y1, x2, y2;
        try{
            x1 = Integer.parseInt(parts[0]);
            y1 = Integer.parseInt(parts[1]);
            x2 = Integer.parseInt(parts[2]);
            y2 = Integer.parseInt(parts[3]);
        }catch(NumberFormatException e){
            emit(BS_OPEN_USAGE);
            return;
        }
        String op = parts[4];
        boolean open;
        if("open".equals(op)){
            open = true;
        }else if("close".equals(op)){
            open = false;
        }else{
            emit(BS_OPEN_USAGE);
            return;
        }

        int result = BlackstartLinks.openLink(x1, y1, x2, y2, open);
        if(result == 1){
            emit("[bs] bs-open FAIL (" + x1 + "," + y1 + ") 处无网关节点");
        }else if(result == 2){
            emit("[bs] bs-open FAIL (" + x1 + "," + y1 + ")" + BlackstartText.get("blackstart.text.flow", ">") + "(" + x2 + "," + y2 + ") 无此链路条目");
        }else{
            emit("[bs] bs-open " + op + " (" + x1 + "," + y1 + ")" + BlackstartText.get("blackstart.text.flow", ">") + "(" + x2 + "," + y2 + ") ok");
        }
    }

    /** bs-tier 用法行(逐字冻结,任务-013 D36)。 */
    private static final String BS_TIER_USAGE =
        "[bs] bs-tier 用法: bs-tier <x1> <y1> <x2> <y2> <1|2|3>";

    /** bs-grant 用法行(逐字冻结,任务-013 D36)。 */
    private static final String BS_GRANT_USAGE =
        "[bs] bs-grant 用法: bs-grant <x1> <y1> <x2> <y2> <0–1 值>";

    /**
     * T2(G4,013)探针:bs-tier &lt;x1&gt; &lt;y1&gt; &lt;x2&gt; &lt;y2&gt; &lt;1|2|3&gt; —— 置自管链路档序。
     *
     * 命令层只做:参数解析 / 范围校验 / 调用内核({@link BlackstartLinks#setTier}) / 回显;
     * 写值与置脏都在内核,handler 不放内核逻辑。旧值来源(冻结) = 调用内核【之前】先经只读访问器
     * {@link BlackstartLinks#getTier} 读(读旧值不置脏;写完再读会拿到新值,故必须写前读)。
     * 参数串在注册处全可选 [](缺参也必达本方法,用法行进日志)。回显格式逐字冻结;`→` 是 U+2192。
     */
    private void handleBsTier(String[] args){
        if(args == null || args.length < 5){              // 首行长度守卫
            emit(BS_TIER_USAGE);
            return;
        }
        // 归一化照 linkGateway(全角空格容错;runner 收到的 String[] 不含命令名)。
        String[] parts = String.join(" ", args).replace('　', ' ').trim().split("\\s+");
        if(parts.length < 5){
            emit(BS_TIER_USAGE);
            return;
        }
        int x1, y1, x2, y2, tier;
        try{
            x1 = Integer.parseInt(parts[0]);
            y1 = Integer.parseInt(parts[1]);
            x2 = Integer.parseInt(parts[2]);
            y2 = Integer.parseInt(parts[3]);
            tier = Integer.parseInt(parts[4]);
        }catch(NumberFormatException e){
            emit(BS_TIER_USAGE);
            return;
        }
        if(tier < 1 || tier > 3){
            emit("[bs] bs-tier 超出范围 [1,3]: " + parts[4]);
            return;
        }
        int ownerPos = Point2.pack(x1, y1);
        int targetPacked = Point2.pack(x2, y2);
        int old = BlackstartLinks.getTier(ownerPos, targetPacked);   // 写前读旧值(唯一来源)
        int rc = BlackstartLinks.setTier(ownerPos, targetPacked, tier);
        if(rc == 1){
            emit("[bs] bs-tier FAIL (" + x1 + "," + y1 + ") 处无网关节点");
        }else if(rc == 2){
            emit("[bs] bs-tier FAIL (" + x1 + "," + y1 + ")" + BlackstartText.get("blackstart.text.flow", ">") + "(" + x2 + "," + y2 + ") 无此链路条目");
        }else{
            emit("[bs] bs-tier (" + x1 + "," + y1 + ")" + BlackstartText.get("blackstart.text.flow", ">") + "(" + x2 + "," + y2 + ") " + old + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + tier);
        }
    }

    /**
     * T2(G4,013)探针:bs-grant &lt;x1&gt; &lt;y1&gt; &lt;x2&gt; &lt;y2&gt; &lt;0..1&gt; —— 置自管链路配额。
     *
     * 命令层只做:参数解析 / 范围校验 / 调用内核({@link BlackstartLinks#setGrant}) / 回显;
     * 旧值同 bs-tier 走写前只读访问器 {@link BlackstartLinks#getGrant}。
     * 范围校验写 {@code !(v>=0 && v<=1)} ⇒ NaN 也被拒;负值在命令层拒(内核钳制兜底)。
     * 回显格式逐字冻结:grant 旧/新值 %.2f。
     */
    private void handleBsGrant(String[] args){
        if(args == null || args.length < 5){              // 首行长度守卫
            emit(BS_GRANT_USAGE);
            return;
        }
        String[] parts = String.join(" ", args).replace('　', ' ').trim().split("\\s+");
        if(parts.length < 5){
            emit(BS_GRANT_USAGE);
            return;
        }
        int x1, y1, x2, y2;
        float grant;
        try{
            x1 = Integer.parseInt(parts[0]);
            y1 = Integer.parseInt(parts[1]);
            x2 = Integer.parseInt(parts[2]);
            y2 = Integer.parseInt(parts[3]);
            grant = Float.parseFloat(parts[4]);
        }catch(NumberFormatException e){
            emit(BS_GRANT_USAGE);
            return;
        }
        if(!(grant >= 0f && grant <= 1f)){                // NaN 也被拒
            emit("[bs] bs-grant 超出范围 [0,1]: " + parts[4]);
            return;
        }
        int ownerPos = Point2.pack(x1, y1);
        int targetPacked = Point2.pack(x2, y2);
        float old = BlackstartLinks.getGrant(ownerPos, targetPacked);   // 写前读旧值(唯一来源)
        int rc = BlackstartLinks.setGrant(ownerPos, targetPacked, grant);
        if(rc == 1){
            emit("[bs] bs-grant FAIL (" + x1 + "," + y1 + ") 处无网关节点");
        }else if(rc == 2){
            emit("[bs] bs-grant FAIL (" + x1 + "," + y1 + ")" + BlackstartText.get("blackstart.text.flow", ">") + "(" + x2 + "," + y2 + ") 无此链路条目");
        }else{
            emit("[bs] bs-grant (" + x1 + "," + y1 + ")" + BlackstartText.get("blackstart.text.flow", ">") + "(" + x2 + "," + y2 + ") "
                + fmt(old, 2) + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + fmt(grant, 2));
        }
    }

    /** T3 016 命令层只解析/校验/调用/回显;写前读取旧命令值。 */
    private void handleBsProt(String[] args){
        if(args.length == 0){
            BlackstartProtection.dump();
            return;
        }
        String usage = "[bs] bs-prot 用法: bs-prot [open|close x y|tier x y v]";
        String[] parts = String.join(" ", args).replace('　', ' ').trim().split("\\s+");
        String op = parts[0];
        boolean tier = "tier".equals(op);
        if((!tier && !"open".equals(op) && !"close".equals(op)) || parts.length != (tier ? 4 : 3)){
            emit(usage);
            return;
        }
        int x, y, value = 0;
        try{
            x = Integer.parseInt(parts[1]);
            y = Integer.parseInt(parts[2]);
            if(tier) value = Integer.parseInt(parts[3]);
        }catch(NumberFormatException e){
            emit(usage);
            return;
        }
        Building b = BlackstartProtection.deviceAt(x, y);
        if(b == null){
            emit("[bs] bs-prot FAIL (" + x + "," + y + ") 处无保护装置");
            return;
        }
        if(tier && !(b instanceof ZoneSwitchNode.ZoneSwitchNodeBuild)){
            emit("[bs] bs-prot FAIL (" + x + "," + y + ") 处非分区开关");
            return;
        }
        Object old = BlackstartProtection.commandValue(b);
        if(tier) BlackstartProtection.commandTier(b, value);
        else BlackstartProtection.commandClosed(b, "close".equals(op));
        emit("[bs] bs-prot " + op + " (" + x + "," + y + ") " + old + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + BlackstartProtection.commandValue(b));
    }

    /**
     * S1b 增补:现场翻转网关静态方块实例上的布尔旗标。
     * 直接赋给 BlackstartBlocks.gatewayNode 的字段,已放置建筑读的是 block 共享实例,故立即生效。
     *
     * 2026-10-05 修正:Arc CommandHandler 传给 runner 的数组【不含命令名】
     * (字节码实证:handleMessage 只把解析出的参数塞进 Seq 再 toArray)。
     * 原实现按「args[0]=命令名」写了 args[1]/args[2] 与 length<3 守卫,
     * 而本命令只声明两个参数 ⇒ 守卫恒真,bs-set 在游戏里从未生效过(01:14 截图实锤)。
     * 顺手容错全角空格(中文输入法常打出)。
     */
    private void setGatewayFlag(String[] args) {
        String usage = "[bs] 用法: bs-set <connectedPower|autolink> <true|false>";

        String[] parts = String.join(" ", args).replace('　', ' ').trim().split("\\s+");
        if(parts.length < 2){
            emit(usage);
            return;
        }

        String field = parts[0];
        String rawValue = parts[1];

        // 值不是严格的 "true"/"false"
        if(!"true".equals(rawValue) && !"false".equals(rawValue)){
            emit(usage);
            return;
        }
        boolean value = "true".equals(rawValue);

        if("connectedPower".equals(field)){
            boolean old = BlackstartBlocks.gatewayNode.connectedPower;
            BlackstartBlocks.gatewayNode.connectedPower = value;
            emit("[bs] connectedPower: " + old + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + value);
        }else if("autolink".equals(field)){
            boolean old = BlackstartBlocks.gatewayNode.autolink;
            BlackstartBlocks.gatewayNode.autolink = value;
            emit("[bs] autolink: " + old + " " + BlackstartText.get("blackstart.text.flow", ">") + " " + value);
        }else{
            // 字段不识别
            emit(usage);
            return;
        }
    }

    /** M4:调试提示浮字开关键(Core.settings;只增不改 ⇒ 旧 bs-view-* 键名/语义零变动,§9.4)。 */
    private static final String KEY_DEBUG_TOASTS = "bs-view-debug-toasts";

    /** 调试提示是否开启(默认 true ⇒ 关闭前浮字投递与旧版逐字节一致;Core.settings 未就绪按默认)。 */
    static boolean debugToasts(){
        return Core.settings == null || Core.settings.getBool(KEY_DEBUG_TOASTS, true);
    }

    /** 写调试提示开关(设置面板入口;Core.settings 未就绪静默)。 */
    static void setDebugToasts(boolean value){
        if(Core.settings != null) Core.settings.put(KEY_DEBUG_TOASTS, value);
    }

    /**
     * 调试/探针双通道输出:控制台日志恒打 + 游戏内浮字(受调试提示开关门控,默认开 ⇒ 行为不变)。
     *
     * S3 起改为 static 且判空:afterReadAll 钩子在读档时触发,无头服务端(Vars.ui == null)
     * 也要能走同一出口 —— 不判空会在读档路径上 NPE。
     * M4 起浮字出口加 debugToasts() 门(诊断面可过滤);玩家面提示请走 emitPlayer。
     */
    static void emit(String text) {
        Log.info(text);
        if(Vars.ui != null && debugToasts()){
            // Shared bounded notice container; debug gate remains owned by this outlet.
            BlackstartView.showNotice(text);
        }
    }

    /**
     * M4 玩家面输出:游戏内浮字恒发(无门),日志行补 `[bs] ` 锚点前缀。
     * 对迁移前的 `emit("[bs] " + text)` 调用形态:日志侧逐字节等价;浮字侧去掉 `[bs] ` 调试前缀(F1 例外,台账 D-46)。
     */
    static void emitPlayer(String text) {
        Log.info("[bs] " + text);
        if(Vars.ui != null){
            BlackstartView.showNotice(text);
        }
    }
}
