package com.azukaar.betterlucklootr;

import java.util.ArrayList;
import java.util.List;


import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = BetterLuckLootr.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class BLModConfig {
    public static class Server
    {
        public static ForgeConfigSpec.ConfigValue<List<? extends String>> bonus_blacklisted_items;
        public static ForgeConfigSpec.ConfigValue<List<? extends String>> global_blacklisted_items;
        public static ForgeConfigSpec.BooleanValue carbon_mode;
        public static ForgeConfigSpec.DoubleValue carbon_multiplier;
        public static ForgeConfigSpec.IntValue carbon_types_per_pick_min;
        public static ForgeConfigSpec.IntValue carbon_types_per_pick_max;
        public static ForgeConfigSpec.ConfigValue<List<? extends String>> curve;
        public static ForgeConfigSpec.ConfigValue<List<? extends String>> no_stack_items;
        public static ForgeConfigSpec.IntValue max_bonus_rolls;
        public static ForgeConfigSpec.IntValue full_rolls;
        public static ForgeConfigSpec.IntValue no_stack_count;
        public static ForgeConfigSpec.IntValue step3_min_picks;
        public static ForgeConfigSpec.IntValue step3_max_picks;
        public static ForgeConfigSpec.IntValue step3_pick_amount;
        public static ForgeConfigSpec.IntValue step3_mode;
        public static ForgeConfigSpec.IntValue max_effective_luck;
        public static ForgeConfigSpec.ConfigValue<String> nbt_scope;
        public static ForgeConfigSpec.ConfigValue<List<? extends String>> nbt_segments;
        public static ForgeConfigSpec.IntValue dedup_compensation_mode;
        public static ForgeConfigSpec.IntValue dedup_reroll_max;
        public static ForgeConfigSpec.BooleanValue compatibility_mode;
        Server(ForgeConfigSpec.Builder builder) {
            builder.push("curve");
            curve = builder
                .comment("幸运曲线 [\"当段幸运值上限,roll一次所需幸运值,roll的概率\"]",
                         "例: \"50,10,1.0\" = 幸运0-50区间，每10幸运=1次roll(100%触发)")
                .defineList("values", List.of(
                    "50,5,1.0",
                    "200,10,0.75",
                    "500,20,0.5",
                    "5000,35,0.5"
                ), entry -> {
                    String[] parts = entry.toString().split(",");
                    if (parts.length != 3) return false;
                    try {
                        Integer.parseInt(parts[0].trim());
                        Integer.parseInt(parts[1].trim());
                        Float.parseFloat(parts[2].trim());
                        return true;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                });
            builder.pop();

            builder.push("rolls");
            full_rolls = builder
                .comment("完整 roll 次数 (超过次数则在现有池子随机抽取)")
                .defineInRange("full_rolls", 10, 1, 500);
            max_bonus_rolls = builder
                .comment("总抽取上限 (完整roll + 池子随机抽取)")
                .defineInRange("max_bonus_rolls", 500, 1, 100000);
            builder.pop();

            builder.push("dedup");
            no_stack_items = builder
                .comment("去重配置 - 格式: \"id,数量\"、\"id::,数量\"、\"#tag,数量\" 或 \"@modid,数量\"",
                         "例: \"minecraft:enchanted_book,3\" = 附魔书最多保留3份",
                         "\"minecraft:wooden::,5\" = 所有木制物品前缀匹配最多5份",
                         "\"#minecraft:music_discs,5\" = 所有音乐唱片最多保留5份",
                         "\"@born_in_chaos_v1,2\" = 该模组所有物品最多保留2份",
                         "不指定数量则使用全局 no_stack_count")
                .defineList("items", List.of(), entry -> {
                    String s = entry.toString();
                    return LootConfigSnapshot.DedupRuleSet.isValidEntry(s);
                });
            no_stack_count = builder
                .comment("全局去重保留份数 (默认1 = 仅保留1份)")
                .defineInRange("no_stack_count", 1, 0, 64);
            dedup_compensation_mode = builder
                .comment("去重补偿模式 - 当物品达到去重上限时的处理方式",
                         "1 = 安全简单池内有界重选 (不增加完整 Roll 总次数)",
                         "2 = Step3阶段补偿 (将丢弃次数加成到poolPicks数量上)")
                .defineInRange("dedup_compensation_mode", 1, 1, 2);
            dedup_reroll_max = builder
                .comment("模式1单次roll最大重试次数 (防止无限循环)")
                .defineInRange("dedup_reroll_max", 5, 1, 100);
            builder.pop();

            builder.push("bonus_blacklist");
            bonus_blacklisted_items = builder
                .comment("额外战利品黑名单 - 仅过滤本模组生成的额外物品",
                         "不会影响原版战利品表生成的物品",
                         "支持格式:",
                         "  \"modid:item_id\" = 精确物品",
                         "  \"modid:prefix::\" = 前缀匹配 (如 \"minecraft:wooden::\")",
                         "  \"#modid:tag_id\" = 整个标签",
                         "  \"@modid\" = 整个模组")
                .defineList("items", List.of(), entry -> {
                    String s = entry.toString();
                    if (s.startsWith("@")) {
                        return s.length() > 1;
                    } else if (s.startsWith("#")) {
                        return ResourceLocation.isValidResourceLocation(s.substring(1));
                    } else if (s.endsWith("::")) {
                        String prefix = s.substring(0, s.length() - 2);
                        return !prefix.isEmpty() && ResourceLocation.isValidResourceLocation(prefix);
                    } else {
                        return ResourceLocation.isValidResourceLocation(s);
                    }
                });
            builder.pop();

            builder.push("global_blacklist");
            global_blacklisted_items = builder
                .comment("全局黑名单 - 过滤所有战利品（原版+本模组额外物品）",
                         "支持格式:",
                         "  \"modid:item_id\" = 精确物品",
                         "  \"modid:prefix::\" = 前缀匹配 (如 \"minecraft:wooden::\")",
                         "  \"#modid:tag_id\" = 整个标签",
                         "  \"@modid\" = 整个模组")
                .defineList("items", List.of(
                    "minecraft:filled_map"
                ), entry -> {
                    String s = entry.toString();
                    if (s.startsWith("@")) {
                        return s.length() > 1;
                    } else if (s.startsWith("#")) {
                        return ResourceLocation.isValidResourceLocation(s.substring(1));
                    } else if (s.endsWith("::")) {
                        String prefix = s.substring(0, s.length() - 2);
                        return !prefix.isEmpty() && ResourceLocation.isValidResourceLocation(prefix);
                    } else {
                        return ResourceLocation.isValidResourceLocation(s);
                    }
                });
            builder.pop();

            builder.push("step3");
            step3_min_picks = builder
                .comment("普通模式Step3每轮最少抽取次数")
                .defineInRange("min_picks", 2, 1, 100);
            step3_max_picks = builder
                .comment("普通模式Step3每轮最多抽取次数")
                .defineInRange("max_picks", 5, 1, 100);
            step3_pick_amount = builder
                .comment("普通模式Step3每次抽中增加的数量")
                .defineInRange("pick_amount", 1, 1, 64);
            step3_mode = builder
                .comment("Step3选取模式",
                         "1 = 按权重加权选取",
                         "2 = 均匀随机选取不同种类")
                .defineInRange("mode", 1, 1, 2);
            builder.pop();

            builder.push("carbon_mode");
            carbon_mode = builder
                .comment("晕碳模式 - 开启后poolPicks阶段改为倍乘（抽中翻倍而非+1）")
                .define("enabled", false);
            carbon_multiplier = builder
                .comment("晕碳倍率 - 每次抽中后翻几倍")
                .defineInRange("multiplier", 2.0, 1.0, 100.0);
            carbon_types_per_pick_min = builder
                .comment("晕碳模式每轮最少选取物品种类数")
                .defineInRange("types_per_pick_min", 1, 1, 100);
            carbon_types_per_pick_max = builder
                .comment("晕碳模式每轮最多选取物品种类数")
                .defineInRange("types_per_pick_max", 3, 1, 100);
            builder.pop();

            builder.push("luck");
            max_effective_luck = builder
                .comment("幸运上限 - 限制所有 LootContext 返回的幸运值（quality加成等）",
                         "可能影响第三方模组及钓鱼等非宝箱场景的幸运计算",
                         "本模组额外预算仍使用玩家完整幸运值计算，不受此上限影响",
                         "0 = 不限制")
                .defineInRange("max_effective_luck", 0, 0, 100000);
            builder.pop();

            builder.push("nbt");
            nbt_scope = builder
                .comment("NBT过滤作用域",
                         "\"global\" = 过滤所有战利品（原版+BLL额外）",
                         "\"bonus_only\" = 仅过滤BLL额外战利品")
                .define("scope", "global", value -> value instanceof String scope
                    && ("global".equalsIgnoreCase(scope.trim()) || "bonus_only".equalsIgnoreCase(scope.trim())));
            nbt_segments = builder
                .comment("NBT过滤规则，默认留空不过滤；任一规则命中即过滤",
                         "字段示例: Enchantments、StoredEnchantments、AttributeModifiers；空列表不命中",
                         "路径示例: display.Name、display.Lore；仅检查对应字段，不搜索字符串内容",
                         "例: \"minecraft:protection\" = 有保护附魔的物品/书都过滤",
                         "附魔ID仅在Enchantments或StoredEnchantments列表内精确匹配",
                         "精确条件可填写JSON字符串: item限定物品ID，all中的条件同时成立",
                         "条件: path字段路径，op为exists/eq/gt/gte/lt/lte，value为字符串/数字/布尔值",
                         "列表条件: path加any条件数组，同一个列表元素必须满足全部条件",
                         "JSON示例与宝石/附魔等级配置见README；无效JSON规则整条停用并告警")
                .defineList("segments", List.of(), entry -> entry instanceof String rule && !rule.trim().isEmpty());
            builder.pop();

            builder.push("compatibility");
            compatibility_mode = builder
                .comment("兼容优化模式 - 根据容器空余槽位限制额外战利品数量",
                         "优先保证物品种类多样性，而非堆叠数量")
                .define("enabled", true);
            builder.pop();
        }

        public List<CurveSegment> getCurve() {
            List<? extends String> raw;
            try {
                raw = curve.get();
            } catch (Exception e) {
                return defaultCurve();
            }
            List<CurveSegment> parsed = new ArrayList<>();
            for (String entry : raw) {
                try {
                    String[] parts = entry.split(",");
                    if (parts.length != 3) continue;
                    parsed.add(new CurveSegment(
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Float.parseFloat(parts[2].trim())
                    ));
                } catch (Exception ignored) {
                }
            }
            List<CurveSegment> sanitized = LootConfigSnapshot.sanitizeCurve(parsed);
            return sanitized.isEmpty() ? defaultCurve() : sanitized;
        }

        private static List<CurveSegment> defaultCurve() {
            return List.of(
                new CurveSegment(50, 5, 1.0f),
                new CurveSegment(200, 10, 0.75f),
                new CurveSegment(500, 20, 0.5f),
                new CurveSegment(5000, 35, 0.5f));
        }

        public int getDedupLimit(Item item) {
            return BLModConfig.snapshot().dedupRules().limit(item);
        }

        public boolean isBonusBlacklisted(Item item) {
            return BLModConfig.snapshot().bonusBlacklist().matches(item);
        }

        public boolean isBonusBlacklisted(ItemStack stack) {
            return BLModConfig.snapshot().isBonusBlacklisted(stack);
        }

        public boolean isGlobalBlacklisted(ItemStack stack) {
            return BLModConfig.snapshot().isGlobalBlacklisted(stack);
        }

        public boolean isNbtFiltered(ItemStack stack) {
            return BLModConfig.snapshot().isNbtFiltered(stack);
        }

        public boolean isGlobalBlacklisted(Item item) {
            return BLModConfig.snapshot().globalBlacklist().matches(item);
        }

    }

    public record CurveSegment(int limit, int divisor, float probability) {}

    public static boolean carbonMode() {
        try { return SERVER.carbon_mode.get(); }
        catch (Exception e) { return false; }
    }

    public static double carbonMultiplier() {
        try { return SERVER.carbon_multiplier.get(); }
        catch (Exception e) { return 2.0; }
    }

    public static int carbonTypesPerPickMin() {
        try { return SERVER.carbon_types_per_pick_min.get(); }
        catch (Exception e) { return 1; }
    }

    public static int carbonTypesPerPickMax() {
        try { return SERVER.carbon_types_per_pick_max.get(); }
        catch (Exception e) { return 3; }
    }

    public static int step3MinPicks() {
        try { return SERVER.step3_min_picks.get(); }
        catch (Exception e) { return 2; }
    }

    public static int step3MaxPicks() {
        try { return SERVER.step3_max_picks.get(); }
        catch (Exception e) { return 5; }
    }

    public static int step3PickAmount() {
        try { return SERVER.step3_pick_amount.get(); }
        catch (Exception e) { return 1; }
    }

    public static int step3Mode() {
        try { return SERVER.step3_mode.get(); }
        catch (Exception e) { return 1; }
    }

    public static int maxEffectiveLuck() {
        try { return SERVER.max_effective_luck.get(); }
        catch (Exception e) { return 0; }
    }

    public static String nbtScope() {
        try { return LootConfigSnapshot.normalizeNbtScope(SERVER.nbt_scope.get()); }
        catch (Exception e) { return "global"; }
    }

    public static int dedupCompensationMode() {
        try { return SERVER.dedup_compensation_mode.get(); }
        catch (Exception e) { return 1; }
    }

    public static int dedupRerollMax() {
        try { return SERVER.dedup_reroll_max.get(); }
        catch (Exception e) { return 5; }
    }

    public static boolean compatibilityMode() {
        try { return SERVER.compatibility_mode.get(); }
        catch (Exception e) { return true; }
    }

    private static volatile LootConfigSnapshot cachedSnapshot;

    public static LootConfigSnapshot snapshot() {
        if (!serverSpec.isLoaded()) return LootConfigSnapshot.capture();
        LootConfigSnapshot current = cachedSnapshot;
        if (current != null) return current;
        synchronized (BLModConfig.class) {
            current = cachedSnapshot;
            if (current == null) {
                current = LootConfigSnapshot.capture();
                cachedSnapshot = current;
            }
            return current;
        }
    }

    static synchronized void invalidateSnapshot() {
        cachedSnapshot = null;
    }

    private static void invalidateIfServerSpec(ModConfigEvent event) {
        if (event.getConfig().getSpec() == serverSpec) invalidateSnapshot();
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        invalidateIfServerSpec(event);
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        invalidateIfServerSpec(event);
    }

    @SubscribeEvent
    public static void onConfigUnloading(ModConfigEvent.Unloading event) {
        invalidateIfServerSpec(event);
    }

    public static final ForgeConfigSpec serverSpec;
    public static final Server SERVER;

    static {
        Pair<Server, ForgeConfigSpec> specPair;
        try {
            specPair = new ForgeConfigSpec.Builder().configure(Server::new);
        } catch (Exception e) {
            throw new RuntimeException("[BetterLuckLootr] 配置文件构建失败", e);
        }
        serverSpec = specPair.getRight();
        SERVER = specPair.getLeft();
    }
}
