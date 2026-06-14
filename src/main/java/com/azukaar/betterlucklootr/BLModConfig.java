package com.azukaar.betterlucklootr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;

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
        public static ForgeConfigSpec.IntValue nbt_limit_bytes;
        public static ForgeConfigSpec.IntValue dedup_compensation_mode;
        public static ForgeConfigSpec.IntValue dedup_reroll_max;
        public static ForgeConfigSpec.BooleanValue compatibility_mode;
        private volatile List<? extends String> cachedRawBonusBlacklist;
        private volatile Set<String> cachedBonusBlacklistIds = Set.of();
        private volatile Set<String> cachedBonusBlacklistPrefixes = Set.of();
        private volatile Set<TagKey<Item>> cachedBonusBlacklistTags = Set.of();
        private volatile Set<String> cachedBonusBlacklistMods = Set.of();

        private volatile List<? extends String> cachedRawGlobalBlacklist;
        private volatile Set<String> cachedGlobalBlacklistIds = Set.of();
        private volatile Set<String> cachedGlobalBlacklistPrefixes = Set.of();
        private volatile Set<TagKey<Item>> cachedGlobalBlacklistTags = Set.of();
        private volatile Set<String> cachedGlobalBlacklistMods = Set.of();

        private volatile List<? extends String> cachedRawCurve;
        private volatile List<CurveSegment> cachedCurve = List.of();

        private volatile List<? extends String> cachedRawNoStack;
        private volatile Map<String, Integer> cachedNoStackIds = Map.of();
        private volatile Map<String, Integer> cachedNoStackPrefixes = Map.of();
        private volatile Map<TagKey<Item>, Integer> cachedNoStackTags = Map.of();
        private volatile Map<String, Integer> cachedNoStackMods = Map.of();

        private volatile String cachedNbtScope = "global";
        private volatile List<? extends String> cachedRawNbtSegments;
        private volatile Set<String> cachedNbtSegments = Set.of();

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
                    return parseDedupEntry(s) != null;
                });
            no_stack_count = builder
                .comment("全局去重保留份数 (默认1 = 仅保留1份)")
                .defineInRange("no_stack_count", 1, 0, 64);
            dedup_compensation_mode = builder
                .comment("去重补偿模式 - 当物品达到去重上限时的处理方式",
                         "1 = 返回重roll (重新抽取以发现更多物品种类)",
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
                .comment("原版幸运上限 - 限制幸运对原版战利品的影响（quality加成等）",
                         "仅限制原版战利品表使用的幸运值，不影响本模组的额外roll次数",
                         "本模组额外战利品仍使用玩家完整幸运值计算roll次数",
                         "0 = 不限制")
                .defineInRange("max_effective_luck", 0, 0, 100000);
            builder.pop();

            builder.push("nbt");
            nbt_scope = builder
                .comment("NBT过滤作用域",
                         "\"global\" = 过滤所有战利品（原版+BLL额外）",
                         "\"bonus_only\" = 仅过滤BLL额外战利品")
                .define("scope", "global");
            nbt_segments = builder
                .comment("NBT段落匹配规则 - 物品NBT字符串包含列表中任一段落即过滤",
                         "例: \"minecraft:protection\" = 有保护附魔的物品/书都过滤",
                         "匹配对象为物品完整NBT字符串，跨所有物品生效")
                .defineList("segments", List.of(), entry -> {
                    String s = entry.toString();
                    return !s.isEmpty();
                });
            nbt_limit_bytes = builder
                .comment("NBT大小限制 (字节)，0=无限制",
                         "拥有超过此大小NBT的物品将被跳过")
                .defineInRange("limit_bytes", 0, 0, 102400);
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
                return List.of(
                    new CurveSegment(50, 5, 1.0f),
                    new CurveSegment(200, 10, 0.75f),
                    new CurveSegment(500, 20, 0.5f),
                    new CurveSegment(5000, 35, 0.5f)
                );
            }
            if (!Objects.equals(raw, cachedRawCurve)) {
                cachedRawCurve = raw;
                List<CurveSegment> parsed = new ArrayList<>();
                for (String entry : raw) {
                    try {
                        String[] parts = entry.toString().split(",");
                        parsed.add(new CurveSegment(
                            Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()),
                            Float.parseFloat(parts[2].trim())
                        ));
                    } catch (Exception e) {
                    }
                }
                if (parsed.isEmpty()) {
                    parsed.add(new CurveSegment(99999, 50, 0.5f));
                }
                cachedCurve = Collections.unmodifiableList(parsed);
            }
            return cachedCurve;
        }

        private static DedupEntry parseDedupEntry(String raw) {
            String s = raw.toString();
            DedupType type;
            String idPart;
            if (s.startsWith("@")) {
                type = DedupType.MOD;
                idPart = s.substring(1);
            } else if (s.startsWith("#")) {
                type = DedupType.TAG;
                idPart = s.substring(1);
            } else if (s.endsWith("::")) {
                type = DedupType.PREFIX;
                idPart = s.substring(0, s.length() - 2);
            } else {
                type = DedupType.ITEM;
                idPart = s;
            }

            int lastComma = idPart.lastIndexOf(',');
            if (lastComma > 0) {
                String id = idPart.substring(0, lastComma);
                try {
                    int count = Integer.parseInt(idPart.substring(lastComma + 1).trim());
                    if (type == DedupType.MOD) {
                        return id.isEmpty() ? null : new DedupEntry(type, id, count);
                    }
                    if (type == DedupType.PREFIX) {
                        if (id.isEmpty() || !ResourceLocation.isValidResourceLocation(id)) return null;
                        return new DedupEntry(type, id.toLowerCase(), count);
                    }
                    if (!ResourceLocation.isValidResourceLocation(id)) return null;
                    return new DedupEntry(type, id, count);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            // no count
            if (type == DedupType.MOD) {
                return idPart.isEmpty() ? null : new DedupEntry(type, idPart, -1);
            }
            String id = type == DedupType.PREFIX ? idPart.toLowerCase() : idPart;
            if (!ResourceLocation.isValidResourceLocation(idPart)) return null;
            return new DedupEntry(type, id, -1);
        }

        private void refreshNoStack() {
            List<? extends String> raw = no_stack_items.get();
            if (!Objects.equals(raw, cachedRawNoStack)) {
                cachedRawNoStack = raw;
                Map<String, Integer> ids = new HashMap<>();
                Map<String, Integer> prefixes = new HashMap<>();
                Map<TagKey<Item>, Integer> tags = new HashMap<>();
                Map<String, Integer> mods = new HashMap<>();
                for (String entry : raw) {
                    DedupEntry de = parseDedupEntry(entry);
                    if (de == null) continue;
                    int count = de.count > 0 ? de.count : no_stack_count.get();
                    switch (de.type) {
                        case TAG -> tags.put(TagKey.create(Registries.ITEM, rl(de.id)), count);
                        case MOD -> mods.put(de.id, count);
                        case PREFIX -> prefixes.put(de.id, count);
                        default -> ids.put(de.id, count);
                    }
                }
                cachedNoStackIds = Collections.unmodifiableMap(ids);
                cachedNoStackPrefixes = Collections.unmodifiableMap(prefixes);
                cachedNoStackTags = Collections.unmodifiableMap(tags);
                cachedNoStackMods = Collections.unmodifiableMap(mods);
            }
        }

        public int getDedupLimit(Item item) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) return 0;

            refreshNoStack();

            Integer limit = cachedNoStackIds.get(itemId.toString());
            if (limit != null) return limit;

            Integer modLimit = cachedNoStackMods.get(itemId.getNamespace());
            if (modLimit != null) return modLimit;

            String idStr = itemId.toString();
            for (var entry : cachedNoStackPrefixes.entrySet()) {
                if (idStr.startsWith(entry.getKey())) return entry.getValue();
            }

            for (var entry : cachedNoStackTags.entrySet()) {
                if (item.builtInRegistryHolder().is(entry.getKey())) return entry.getValue();
            }
            return 0;
        }

        private void refreshBonusBlacklist() {
            List<? extends String> raw = bonus_blacklisted_items.get();
            if (!Objects.equals(raw, cachedRawBonusBlacklist)) {
                cachedRawBonusBlacklist = raw;
                Set<String> ids = new HashSet<>();
                Set<String> prefixes = new HashSet<>();
                Set<TagKey<Item>> tags = new HashSet<>();
                Set<String> mods = new HashSet<>();
                for (String entry : raw) {
                    String s = entry.toString();
                    if (s.startsWith("@")) {
                        mods.add(s.substring(1));
                    } else if (s.startsWith("#")) {
                        TagKey<Item> t = TagKey.create(Registries.ITEM, rl(s.substring(1)));
                        if (t != null) tags.add(t);
                    } else if (s.endsWith("::")) {
                        prefixes.add(s.substring(0, s.length() - 2).toLowerCase());
                    } else {
                        ids.add(s.toLowerCase());
                    }
                }
                cachedBonusBlacklistIds = Collections.unmodifiableSet(ids);
                cachedBonusBlacklistPrefixes = Collections.unmodifiableSet(prefixes);
                cachedBonusBlacklistTags = Collections.unmodifiableSet(tags);
                cachedBonusBlacklistMods = Collections.unmodifiableSet(mods);
            }
        }

        public boolean isBonusBlacklisted(Item item) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) return false;
            refreshBonusBlacklist();
            String id = key.toString().toLowerCase();
            if (cachedBonusBlacklistIds.contains(id)) return true;
            if (cachedBonusBlacklistMods.contains(key.getNamespace())) return true;
            for (String prefix : cachedBonusBlacklistPrefixes) {
                if (id.startsWith(prefix)) return true;
            }
            for (TagKey<Item> tag : cachedBonusBlacklistTags) {
                if (item.builtInRegistryHolder().is(tag)) return true;
            }
            return false;
        }

        public boolean isBonusBlacklisted(ItemStack stack) {
            if (isBonusBlacklisted(stack.getItem())) return true;
            return isNbtFiltered(stack);
        }

        public boolean isGlobalBlacklisted(ItemStack stack) {
            if (isGlobalBlacklisted(stack.getItem())) return true;
            refreshNbtFilter();
            if (!"global".equals(cachedNbtScope)) return false;
            return isNbtFiltered(stack);
        }

        private void refreshNbtFilter() {
            List<? extends String> raw = nbt_segments.get();
            if (!Objects.equals(raw, cachedRawNbtSegments)) {
                cachedRawNbtSegments = raw;
                cachedNbtScope = nbt_scope.get();
                Set<String> segments = new HashSet<>();
                for (String entry : raw) {
                    String s = entry.toString();
                    if (!s.isEmpty()) segments.add(s);
                }
                cachedNbtSegments = Collections.unmodifiableSet(segments);
            }
        }

        public boolean isNbtFiltered(ItemStack stack) {
            if (!stack.hasTag() || stack.getTag().isEmpty()) return false;
            refreshNbtFilter();
            if (cachedNbtSegments.isEmpty() && nbt_limit_bytes.get() <= 0) return false;
            int limit = nbt_limit_bytes.get();
            String nbtStr = null;
            if (limit > 0) {
                nbtStr = stack.getTag().toString();
                if (nbtStr.length() > limit) return true;
            }
            if (!cachedNbtSegments.isEmpty()) {
                if (nbtStr == null) nbtStr = stack.getTag().toString();
                for (String seg : cachedNbtSegments) {
                    if (nbtStr.contains(seg)) return true;
                }
            }
            return false;
        }

        private void refreshGlobalBlacklist() {
            List<? extends String> raw = global_blacklisted_items.get();
            if (!Objects.equals(raw, cachedRawGlobalBlacklist)) {
                cachedRawGlobalBlacklist = raw;
                Set<String> ids = new HashSet<>();
                Set<String> prefixes = new HashSet<>();
                Set<TagKey<Item>> tags = new HashSet<>();
                Set<String> mods = new HashSet<>();
                for (String entry : raw) {
                    String s = entry.toString();
                    if (s.startsWith("@")) {
                        mods.add(s.substring(1));
                    } else if (s.startsWith("#")) {
                        TagKey<Item> t = TagKey.create(Registries.ITEM, rl(s.substring(1)));
                        if (t != null) tags.add(t);
                    } else if (s.endsWith("::")) {
                        prefixes.add(s.substring(0, s.length() - 2).toLowerCase());
                    } else {
                        ids.add(s.toLowerCase());
                    }
                }
                cachedGlobalBlacklistIds = Collections.unmodifiableSet(ids);
                cachedGlobalBlacklistPrefixes = Collections.unmodifiableSet(prefixes);
                cachedGlobalBlacklistTags = Collections.unmodifiableSet(tags);
                cachedGlobalBlacklistMods = Collections.unmodifiableSet(mods);
            }
        }

        public boolean isGlobalBlacklisted(Item item) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key == null) return false;
            refreshGlobalBlacklist();
            String id = key.toString().toLowerCase();
            if (cachedGlobalBlacklistIds.contains(id)) return true;
            if (cachedGlobalBlacklistMods.contains(key.getNamespace())) return true;
            for (String prefix : cachedGlobalBlacklistPrefixes) {
                if (id.startsWith(prefix)) return true;
            }
            for (TagKey<Item> tag : cachedGlobalBlacklistTags) {
                if (item.builtInRegistryHolder().is(tag)) return true;
            }
            return false;
        }


        private static ResourceLocation rl(String id) {
            return ResourceLocation.tryParse(id);
        }

    }

    public record CurveSegment(int limit, int divisor, float probability) {}
    private enum DedupType { ITEM, PREFIX, TAG, MOD }
    private record DedupEntry(DedupType type, String id, int count) {}

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

    public static int nbtLimitBytes() {
        try { return SERVER.nbt_limit_bytes.get(); }
        catch (Exception e) { return 0; }
    }

    public static String nbtScope() {
        try { return SERVER.nbt_scope.get(); }
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

    public static int compatibilityDefaultSlots() {
        return 27;
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
