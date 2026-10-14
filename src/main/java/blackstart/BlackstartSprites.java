package blackstart;

import arc.graphics.Color;
import arc.graphics.Pixmap;
import mindustry.Vars;
import mindustry.graphics.MultiPacker;
import mindustry.mod.Mods;

/** 程序化贴图(不依赖外部 png,桥阶段占位用)。外部 png 存在时让位。 */
public class BlackstartSprites {

    /** sprites/<name>.png 存在时,引擎会自动以 blackstart-<name> 装载同名贴图;此时跳过程序化绘制,避免同名双 add。 */
    private static boolean external(String name) {
        if (Vars.mods == null) return false;
        Mods.LoadedMod mod = Vars.mods.getMod(BlackstartMod.class);
        if (mod == null) mod = Vars.mods.getMod("blackstart");
        if (mod == null || mod.root == null) return false;
        return mod.root.child("sprites").child(name + ".png").exists();
    }

    public static void pack(MultiPacker packer) {
        if (!external("gateway-node")) {
            Pixmap p = new Pixmap(32, 32);

            Color base = Color.valueOf("22262e");
            Color edge = Color.valueOf("57c7ff");
            Color core = Color.valueOf("9be7ff");

            p.fill(base);

            int e = edge.rgba8888();
            p.fillRect(0, 0, 32, 3, e);    // 上边
            p.fillRect(0, 29, 32, 3, e);   // 下边
            p.fillRect(0, 0, 3, 32, e);    // 左边
            p.fillRect(29, 0, 3, 32, e);   // 右边
            p.fillCircle(16, 16, 7, core.rgba8888());

            // 贴图名 = 运行时全名(mod 前缀 + 构造名),与 block.name 一致
            packer.add(MultiPacker.PageType.main, "blackstart-gateway-node", p);
            p.dispose();
        }

        // T3 两装置沿用同几何,只替换边框/核心色。
        if (!external("breaker-node")) {
            Pixmap breaker = new Pixmap(32, 32);
            breaker.fill(Color.valueOf("22262e"));
            int be = Color.valueOf("ff7043").rgba8888();
            breaker.fillRect(0, 0, 32, 3, be);
            breaker.fillRect(0, 29, 32, 3, be);
            breaker.fillRect(0, 0, 3, 32, be);
            breaker.fillRect(29, 0, 3, 32, be);
            breaker.fillCircle(16, 16, 7, Color.valueOf("ffd8c2").rgba8888());
            packer.add(MultiPacker.PageType.main, "blackstart-breaker-node", breaker);
            breaker.dispose();
        }

        if (!external("zone-switch")) {
            Pixmap zone = new Pixmap(32, 32);
            zone.fill(Color.valueOf("22262e"));
            int ze = Color.valueOf("ffd54f").rgba8888();
            zone.fillRect(0, 0, 32, 3, ze);
            zone.fillRect(0, 29, 32, 3, ze);
            zone.fillRect(0, 0, 3, 32, ze);
            zone.fillRect(29, 0, 3, 32, ze);
            zone.fillCircle(16, 16, 7, Color.valueOf("fff6cc").rgba8888());
            packer.add(MultiPacker.PageType.main, "blackstart-zone-switch", zone);
            zone.dispose();
        }

        device(packer,"starter-generator",64,"79c86b",false);
        device(packer,"auxiliary-generator",96,"d8a15d",false);
        if (!external("following-storage")) device(packer,"following-storage",64,"62a5d9",true);
        if (!external("forming-storage")) device(packer,"forming-storage",64,"be8de3",true);
        device(packer,"training-load",32,"dd7777",true);
        transmission(packer,"high-voltage-node",64,"6dd3a8",2);
        transmission(packer,"ultra-voltage-node",96,"c699ff",3);
    }
    private static void transmission(MultiPacker packer,String name,int size,String accent,int tiers){
        Pixmap p=new Pixmap(size,size);p.fill(Color.valueOf("22262e"));
        int color=Color.valueOf(accent).rgba8888(),steel=Color.valueOf("91a3b5").rgba8888();
        p.fillRect(size/2-3,6,6,size-12,steel);
        for(int i=0;i<tiers;i++){
            int y=size/4+i*size/(tiers+2);
            p.fillRect(size/5,y,size*3/5,4,steel);
            p.fillCircle(size/5,y+2,4,color);p.fillCircle(size*4/5,y+2,4,color);
        }
        p.fillCircle(size/2,size-12,5,color);
        packer.add(MultiPacker.PageType.main,"blackstart-"+name,p);p.dispose();
    }
    private static void device(MultiPacker packer,String name,int size,String accent,boolean storage){
        Pixmap pixmap=new Pixmap(size,size);pixmap.fill(Color.valueOf("22262e"));
        int color=Color.valueOf(accent).rgba8888();
        pixmap.fillRect(3,3,size-6,4,color);pixmap.fillRect(3,size-7,size-6,4,color);
        pixmap.fillRect(3,3,4,size-6,color);pixmap.fillRect(size-7,3,4,size-6,color);
        if(storage){for(int i=0;i<3;i++)pixmap.fillRect(size/4,size/4+i*size/6,size/2,size/10,color);}
        else{pixmap.fillCircle(size/2,size/2,size/5,color);pixmap.fillCircle(size/2,size/2,size/10,Color.valueOf("22262e").rgba8888());}
        packer.add(MultiPacker.PageType.main,"blackstart-"+name,pixmap);pixmap.dispose();
    }
}
