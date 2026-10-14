package blackstart;

import mindustry.content.Items;
import mindustry.type.Category;
import mindustry.type.ItemStack;

/**
 * 内容注册。
 *
 * v8 事实(字节码实证):Content 构造器自己会调 Vars.content.handleContent(this)
 * 完成注册 —— 不要手动 blocks().add(),那样会重复注册。
 * 名字同样由 MappableContent 构造器自动加 mod 前缀:
 *   传入 "gateway-node" + mod 名 "blackstart" → 运行时全名 "blackstart-gateway-node"。
 * bundle 键与 packSprites 贴图名都必须用这个全名。
 */
public class BlackstartBlocks {

    public static GatewayNode gatewayNode;
    public static GatewayNode highVoltageNode,ultraVoltageNode;
    public static BreakerNode breakerNode;
    public static ZoneSwitchNode zoneSwitchNode;
    public static ControlledGenerator starterGenerator, auxiliaryGenerator;
    public static ControlledStorage followingStorage, formingStorage;
    public static TrainingLoad trainingLoad;

    public static void load() {
        gatewayNode = new GatewayNode("gateway-node");
        gatewayNode.requirements(Category.power, ItemStack.with(
            Items.copper, 10,
            Items.lead, 8
        ));
        gatewayNode.health = 80;
        gatewayNode.size = 1;
        // connectedPower=false 只拦入边的**目标侧**节点型连线(手动/节点放置);网关自身出边与机器类自动入边**不受管**,
        // 要全封自动边需配 autolink=false。详见 docs/引擎接触面清单.md §2 与 docs/S1-机制定稿-20261005.md
        gatewayNode.connectedPower = false;

        // ---- T2(合本 §二-2.2-①,修 F1/C-2):旗标双翻 + 动态需求,网关成为原生图申报端 ----
        // N3:outputsPower && consumesPower && !buffered → producers + consumers 双边入图;
        // 已复核不打开新自动连线、不改变相邻并图(设计 §1.5.2 边界),唯一实际生效 = N3 成员归属。
        gatewayNode.outputsPower = true;
        gatewayNode.consumesPower = true;
        // N6:consumePowerDynamic.requestedPower(b) 直接返回 lambda 值(不经 shouldConsume 门、不乘 delta;
        // delta 由 PowerGraph.getPowerNeeded() 统一乘)。Block.consume 会同步置 consPower(字节码实证)。
        gatewayNode.consumePowerDynamic(gatewayNode.linkRating,
            b -> ((GatewayNode.GatewayNodeBuild)b).linkDrawCmd);
        // N10:consumesPower=true 会让 Block.setBars 多一条 power 状态条 —— 显式关闭,避免双状态条。
        gatewayNode.enableDrawStatus = false;

        breakerNode = new BreakerNode("breaker-node");
        breakerNode.requirements(Category.power, ItemStack.with(Items.copper, 15, Items.lead, 10, Items.silicon, 5));
        breakerNode.health = 120; breakerNode.size = 1;
        breakerNode.connectedPower = false; breakerNode.autolink = false;
        breakerNode.consumesPower = false;    // 无自身耗电;linkDrawCmd 恒 0
        breakerNode.outputsPower = true;      // §0.3-10 裁定 B:接住 T2 到货申报(producers 入图)
        breakerNode.enableDrawStatus = false; // 照网关 N10:显式关多余 power 状态条
        breakerNode.sync = true;              // 否则 writeSync/readSync 是死代码(合本 V-T3 修法①)

        zoneSwitchNode = new ZoneSwitchNode("zone-switch");
        zoneSwitchNode.requirements(Category.power, ItemStack.with(Items.copper, 10, Items.lead, 8));
        zoneSwitchNode.health = 100; zoneSwitchNode.size = 1;
        zoneSwitchNode.connectedPower = false; zoneSwitchNode.autolink = false;
        zoneSwitchNode.consumesPower = false;    // 无自身耗电;linkDrawCmd 恒 0
        zoneSwitchNode.outputsPower = true;      // §0.3-10 裁定 B:接住 T2 到货申报(producers 入图)
        zoneSwitchNode.enableDrawStatus = false; // 照网关 N10:显式关多余 power 状态条
        zoneSwitchNode.sync = true;

        starterGenerator = new ControlledGenerator("starter-generator",600,0,3,0,150,1);
        starterGenerator.requirements(Category.power,ItemStack.with(Items.copper,60,Items.lead,40,Items.silicon,20));
        starterGenerator.size=2;starterGenerator.health=260;
        auxiliaryGenerator = new ControlledGenerator("auxiliary-generator",3600,120,8,360,720,2);
        auxiliaryGenerator.requirements(Category.power,ItemStack.with(Items.copper,180,Items.lead,120,Items.silicon,80,Items.titanium,60));
        auxiliaryGenerator.size=3;auxiliaryGenerator.health=600;
        followingStorage = new ControlledStorage("following-storage",240000,1800,3600,false);
        followingStorage.requirements(Category.power,ItemStack.with(Items.copper,80,Items.lead,100,Items.silicon,60));
        followingStorage.size=2;followingStorage.health=300;
        formingStorage = new ControlledStorage("forming-storage",120000,600,1800,true);
        formingStorage.requirements(Category.power,ItemStack.with(Items.copper,100,Items.lead,100,Items.silicon,100,Items.titanium,40));
        formingStorage.size=2;formingStorage.health=350;
        // Fusion bridge until the industrial campaign has its own research tree.
        // Unlocks only these existing devices; material costs and planet rules still apply.
        for(var block:new mindustry.world.Block[]{gatewayNode,breakerNode,zoneSwitchNode,
            starterGenerator,auxiliaryGenerator,followingStorage,formingStorage})block.alwaysUnlocked=true;
        trainingLoad=new TrainingLoad("training-load");
        trainingLoad.requirements(Category.power,ItemStack.with(Items.copper,20,Items.lead,20));
        trainingLoad.buildVisibility=mindustry.world.meta.BuildVisibility.sandboxOnly;trainingLoad.health=120;
        // Append after ALL legacy content so old IDs and saves retain their layout.
        highVoltageNode=transmission("high-voltage-node",150f,60f,.5f,2,320,
            ItemStack.with(Items.copper,100,Items.lead,80,Items.silicon,60,Items.titanium,40));
        ultraVoltageNode=transmission("ultra-voltage-node",600f,120f,.26666667f,3,800,
            ItemStack.with(Items.copper,250,Items.lead,200,Items.silicon,180,Items.titanium,120,Items.surgeAlloy,60));
    }

    private static GatewayNode transmission(String name,float rating,float range,float lossScale,int size,int health,ItemStack[] costs){
        GatewayNode node=new GatewayNode(name);node.requirements(Category.power,costs);
        node.size=size;node.health=health;node.linkRating=rating;node.linkLossScale=lossScale;
        node.linkLoss=.03f*lossScale;node.managedLinkRange=range;node.laserRange=range;
        node.maxNodes=6;node.managedMaxLinks=6;node.connectedPower=false;node.autolink=false;node.alwaysUnlocked=true;
        node.outputsPower=true;node.consumesPower=true;node.enableDrawStatus=false;
        node.consumePowerDynamic(rating,b->((GatewayNode.GatewayNodeBuild)b).linkDrawCmd);
        return node;
    }
}
