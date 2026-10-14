package blackstart;

import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.gen.Call;

/**
 * T5(I4)数据地基:`rules.tags` 全局参数通道的【单点封装】(合本 §二-4-2 / §二-6 / §三-5;
 * T5 §3.5 通道细则 C)。
 *
 * 四条纪律(写死,它处不得违反):
 *   1. `rules.tags` 的 `blackstart.*` 键【唯一写入门】在本类 {@link #set(String, String)};
 *      其它任何地方禁止直写 tags。
 *   2. 值一律 String(float 用 {@code String.valueOf});解析失败 fail-safe 回默认值 + WARN,
 *      不抛异常。
 *   3. rules 属【对局】——换图 / 换战役扇区 / 编辑器重设规则会重置,【不随蓝图】;
 *      从端不可改(UI 置灰由 I5 做,本类自查拒绝)。
 *   4. 线程归属:单机 / 主机逻辑主循环单线程访问(引擎主循环);不引入自建线程、不加锁。
 *
 * 本批是 0.1 第一个获批写入面:全文对引擎状态的写入只在 {@link #set(String, String)}
 * (参数与相关策略epoch共同写入规则表,只广播一次)。get/dump/getFloat 全部只读、零副作用
 * (getFloat 的 fail-safe WARN 只写日志,不写引擎状态)。
 */
public final class BlackstartParams {

    /** 0.1 唯一生产键:mod 链路每跳损耗(值如 "0.03")。 */
    public static final String KEY_LOSS = "blackstart.loss";

    /**
     * 默认每跳损耗 = 3%(合本 §三-1 / §三-5;P6 拍板 3%;0.1 唯一生产键;
     * 预留 `blackstart.settleHz` 等将来加)。
     */
    public static final float DEFAULT_LOSS = 0.03f;

    /** 键名前缀纪律:`rules.tags` 对地图作者 / 其他 mod 开放写作,只认自己的前缀。 */
    private static final String PREFIX = "blackstart.";

    /** getFloat 坏值 WARN 的同键去重(防将来 15Hz 读键刷屏);只增不减,量级 = 键数。 */
    private static final ObjectSet<String> warnedKeys = new ObjectSet<>();

    private BlackstartParams(){
    }

    /**
     * 读一个 String 参数;缺键 / 无世界 → 返回 {@code def}。零副作用、零日志。
     */
    public static String get(String key, String def){
        if(Vars.state == null || Vars.state.rules == null || Vars.state.rules.tags == null) return def;
        String value = Vars.state.rules.tags.get(key);
        return value == null ? def : value;
    }

    /**
     * 读一个 float 参数;缺键 → {@code def};解析失败 fail-safe 返回 {@code def} 并 WARN
     * (含键名与坏值,同键只警一次)。不抛异常。
     */
    public static float getFloat(String key, float def){
        String raw = get(key, null);
        if(raw == null) return def;
        try{
            return Float.parseFloat(raw);
        }catch(NumberFormatException e){
            if(warnedKeys.add(key)){
                Log.warn("[bs] param " + key + " 值非法 '" + raw + "',回退默认 " + def);
            }
            return def;
        }
    }

    /**
     * 写一个 String 参数(rules.tags 唯一写入门)。守卫序逐条,失败返回 false + WARN,不抛异常:
     *   a. key/value 为 null 或缺 `blackstart.` 前缀 → 拒(键前缀纪律);
     *   b. {@code Vars.state == null} → 拒(世界未加载);
     *   c. 从端({@code Vars.net.client()})→ 拒(rules 由房主设置)。
     * 通过后:写入规则表 → 走引擎规则广播(服务端专用门;单机无 server 时为 no-op)→ 日志 → true。
     */
    public static boolean set(String key, String value){
        if(key == null || value == null || !key.startsWith(PREFIX)){
            Log.warn("[bs] param 拒绝写入:键必须以 '" + PREFIX + "' 开头(" + key + ")");
            return false;
        }
        if(Vars.state == null){
            Log.warn("[bs] param 拒绝写入:世界尚未加载(" + key + ")");
            return false;
        }
        if(Vars.net != null && Vars.net.client()){
            Log.warn("[bs] param 拒绝写入:从端不可改,参数由房主设置(" + key + ")");
            return false;
        }
        String epochKey = BlackstartFusionPolicy.epochKey(key);
        String previous = get(key, BlackstartFusionPolicy.MODE_KEY.equals(key) ? "standard" : "true");
        Vars.state.rules.tags.put(key, value);
        if(epochKey != null && !value.equals(previous)){
            long epoch = BlackstartFusionPolicy.epoch(epochKey);
            // Wrap only after exhausting the nonnegative long range; never freeze toggles.
            Vars.state.rules.tags.put(epochKey, Long.toString(epoch == Long.MAX_VALUE ? 1 : epoch + 1));
        }
        Call.setRules(Vars.state.rules);
        Log.info("[bs] param " + key + "=" + value);
        return true;
    }

    /**
     * 转储全部 `blackstart.*` 参数为【新】Jval 对象,按 key 升序(确定性,供 JSON 对比);
     * 无键 / 无世界 → 空对象 {@code {}}。零副作用。
     */
    public static Jval dump(){
        Jval out = Jval.newObject();
        if(Vars.state == null || Vars.state.rules == null || Vars.state.rules.tags == null) return out;

        StringMap tags = Vars.state.rules.tags;
        Seq<String> keys = tags.keys().toSeq();
        Seq<String> picked = new Seq<>();
        for(int i = 0; i < keys.size; i++){
            String k = keys.get(i);
            if(k != null && k.startsWith(PREFIX)) picked.add(k);
        }
        picked.sort();

        for(int i = 0; i < picked.size; i++){
            String k = picked.get(i);
            String v = tags.get(k);
            if(v == null){
                out.put(k, Jval.NULL);
            }else{
                out.put(k, v);
            }
        }
        return out;
    }
}
