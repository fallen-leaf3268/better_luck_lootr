package com.azukaar.betterlucklootr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.io.IOException;
import java.io.StringReader;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;

public record LootConfigSnapshot(
        List<BLModConfig.CurveSegment> curve,
        int fullRolls,
        int maxBonusRolls,
        int noStackCount,
        int dedupCompensationMode,
        int dedupRerollMax,
        boolean compatibilityMode,
        int step3MinPicks,
        int step3MaxPicks,
        int step3PickAmount,
        int step3Mode,
        boolean carbonMode,
        double carbonMultiplier,
        int carbonTypesPerPickMin,
        int carbonTypesPerPickMax,
        String nbtScope,
        ItemRuleSet bonusBlacklist,
        ItemRuleSet globalBlacklist,
        DedupRuleSet dedupRules,
        NbtRuleSet nbtRules) {

    public LootConfigSnapshot {
        curve = sanitizeCurve(curve);
        nbtScope = normalizeNbtScope(nbtScope);
    }

    public static LootConfigSnapshot capture() {
        int noStackCount = safeInt(BLModConfig.Server.no_stack_count, 1);
        return new LootConfigSnapshot(
            BLModConfig.SERVER.getCurve(),
            safeInt(BLModConfig.Server.full_rolls, 10),
            safeInt(BLModConfig.Server.max_bonus_rolls, 500),
            noStackCount,
            BLModConfig.dedupCompensationMode(),
            BLModConfig.dedupRerollMax(),
            BLModConfig.compatibilityMode(),
            BLModConfig.step3MinPicks(),
            BLModConfig.step3MaxPicks(),
            BLModConfig.step3PickAmount(),
            BLModConfig.step3Mode(),
            BLModConfig.carbonMode(),
            BLModConfig.carbonMultiplier(),
            BLModConfig.carbonTypesPerPickMin(),
            BLModConfig.carbonTypesPerPickMax(),
            BLModConfig.nbtScope(),
            ItemRuleSet.parse(safeStrings(BLModConfig.Server.bonus_blacklisted_items)),
            ItemRuleSet.parse(safeStrings(BLModConfig.Server.global_blacklisted_items)),
            DedupRuleSet.parse(safeStrings(BLModConfig.Server.no_stack_items), noStackCount),
            NbtRuleSet.parse(safeStrings(BLModConfig.Server.nbt_segments)));
    }

    static LootConfigSnapshot forTest(List<BLModConfig.CurveSegment> curve, int fullRolls, int maxBonusRolls) {
        return new LootConfigSnapshot(curve, fullRolls, maxBonusRolls, 1, 1, 5, true,
            2, 5, 1, 1, false, 2.0, 1, 3, "global",
            ItemRuleSet.EMPTY, ItemRuleSet.EMPTY, DedupRuleSet.EMPTY, NbtRuleSet.EMPTY);
    }

    public boolean isBonusBlacklisted(ItemStack stack) {
        return bonusBlacklist.matches(stack.getItem()) || isNbtFiltered(stack);
    }

    public boolean isGlobalBlacklisted(ItemStack stack) {
        return globalBlacklist.matches(stack.getItem())
            || "global".equals(nbtScope) && isNbtFiltered(stack);
    }

    public boolean isBonusOutputFiltered(ItemStack stack) {
        return bonusBlacklist.matches(stack.getItem())
            || globalBlacklist.matches(stack.getItem())
            || isNbtFiltered(stack);
    }

    public boolean isStaticallyBlacklisted(Item item) {
        return bonusBlacklist.matches(item) || globalBlacklist.matches(item);
    }

    public int limitFor(ItemStack stack) {
        Integer configured = dedupRules.findLimit(stack.getItem());
        if (configured != null) return configured;
        return stack.isStackable() ? Integer.MAX_VALUE : noStackCount;
    }

    public int staticLimitFor(Item item) {
        Integer configured = dedupRules.findLimit(item);
        if (configured != null) return configured;
        return item.getMaxStackSize() > 1 ? Integer.MAX_VALUE : noStackCount;
    }

    static List<BLModConfig.CurveSegment> sanitizeCurve(List<BLModConfig.CurveSegment> raw) {
        if (raw == null) return List.of();
        List<BLModConfig.CurveSegment> sorted = new ArrayList<>();
        for (BLModConfig.CurveSegment segment : raw) {
            if (segment == null || segment.limit() <= 0 || segment.divisor() <= 0
                    || !Float.isFinite(segment.probability())
                    || segment.probability() < 0.0f || segment.probability() > 1.0f) continue;
            sorted.add(segment);
        }
        sorted.sort(Comparator.comparingInt(BLModConfig.CurveSegment::limit));
        List<BLModConfig.CurveSegment> result = new ArrayList<>();
        int previousLimit = 0;
        for (BLModConfig.CurveSegment segment : sorted) {
            if (segment.limit() <= previousLimit) continue;
            result.add(segment);
            previousLimit = segment.limit();
        }
        return List.copyOf(result);
    }

    static String normalizeNbtScope(String raw) {
        if (raw == null) return "global";
        String scope = raw.trim().toLowerCase(Locale.ROOT);
        return "bonus_only".equals(scope) ? scope : "global";
    }

    public boolean isNbtFiltered(ItemStack stack) {
        return !nbtRules.isEmpty() && stack.hasTag() && nbtRules.matches(stack);
    }

    public static final class NbtRuleSet {
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
        private static final Pattern JSON_TOKEN = Pattern.compile(
            "\"(?:[^\"\\\\\\x00-\\x1f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*+\""
                + "|true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|[{}\\[\\],:]|[ \\t\\r\\n]+");
        static final NbtRuleSet EMPTY = new NbtRuleSet(List.of(), Set.of(), List.of());
        private final List<List<String>> paths;
        private final Set<String> enchantmentIds;
        private final List<IndexedRule> generalRules;
        private final Map<ResourceLocation, List<IndexedRule>> itemRules;

        private NbtRuleSet(List<List<String>> paths, Set<String> enchantmentIds, List<StructuredRule> structuredRules) {
            this.paths = paths.stream().map(List::copyOf).toList();
            this.enchantmentIds = Set.copyOf(enchantmentIds);
            List<IndexedRule> general = new ArrayList<>();
            Map<ResourceLocation, List<IndexedRule>> byItem = new HashMap<>();
            for (int index = 0; index < structuredRules.size(); index++) {
                StructuredRule rule = structuredRules.get(index);
                IndexedRule indexed = new IndexedRule(index, rule.conditions());
                if (rule.itemId() == null) general.add(indexed);
                else byItem.computeIfAbsent(rule.itemId(), ignored -> new ArrayList<>()).add(indexed);
            }
            byItem.replaceAll((id, rules) -> List.copyOf(rules));
            this.generalRules = List.copyOf(general);
            this.itemRules = Map.copyOf(byItem);
        }

        public static NbtRuleSet parse(Iterable<? extends String> raw) {
            List<List<String>> paths = new ArrayList<>();
            Set<String> enchantmentIds = new HashSet<>();
            List<StructuredRule> structuredRules = new ArrayList<>();
            int ruleIndex = 0;
            for (String entry : raw) {
                ruleIndex++;
                if (entry == null) continue;
                String value = entry.trim();
                if (value.isEmpty()) continue;
                if (value.startsWith("{")) {
                    try {
                        structuredRules.add(parseStructuredRule(value));
                    } catch (IllegalArgumentException exception) {
                        LOGGER.warn("[BetterLuckLootr] Disabled invalid nbt.segments rule #{}: {}",
                            ruleIndex, exception.getMessage());
                    }
                } else if (value.indexOf(':') >= 0) {
                    ResourceLocation id = ResourceLocation.tryParse(value);
                    if (id != null) enchantmentIds.add(id.toString());
                } else {
                    List<String> path = List.of(value.split("\\.", -1));
                    if (path.stream().noneMatch(String::isEmpty)) paths.add(path);
                }
            }
            return paths.isEmpty() && enchantmentIds.isEmpty() && structuredRules.isEmpty()
                ? EMPTY : new NbtRuleSet(paths, enchantmentIds, structuredRules);
        }

        public boolean isEmpty() {
            return paths.isEmpty() && enchantmentIds.isEmpty() && generalRules.isEmpty() && itemRules.isEmpty();
        }

        public boolean matches(CompoundTag root) {
            if (matchesSimple(root)) return true;
            for (IndexedRule rule : generalRules) {
                if (rule.conditions().matches(root)) return true;
            }
            return false;
        }

        private boolean matches(ItemStack stack) {
            CompoundTag root = stack.getTag();
            if (matchesSimple(root)) return true;
            ResourceLocation itemId = itemRules.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem());
            List<IndexedRule> specific = itemId == null ? List.of() : itemRules.getOrDefault(itemId, List.of());
            int generalIndex = 0;
            int specificIndex = 0;
            while (generalIndex < generalRules.size() || specificIndex < specific.size()) {
                IndexedRule rule;
                if (specificIndex == specific.size() || generalIndex < generalRules.size()
                        && generalRules.get(generalIndex).position() < specific.get(specificIndex).position()) {
                    rule = generalRules.get(generalIndex++);
                } else {
                    rule = specific.get(specificIndex++);
                }
                if (rule.conditions().matches(root)) return true;
            }
            return false;
        }

        private boolean matchesSimple(CompoundTag root) {
            for (List<String> path : paths) {
                Tag current = root;
                for (String key : path) {
                    if (!(current instanceof CompoundTag compound)) {
                        current = null;
                        break;
                    }
                    current = compound.get(key);
                }
                if (current == null || current instanceof ListTag list && list.isEmpty()) continue;
                String key = path.get(path.size() - 1);
                if ((key.equals("Enchantments") || key.equals("StoredEnchantments"))
                        && !(current instanceof ListTag)) continue;
                return true;
            }
            return !enchantmentIds.isEmpty()
                && (matchesEnchantments(root.get("Enchantments")) || matchesEnchantments(root.get("StoredEnchantments")));
        }

        private boolean matchesEnchantments(Tag tag) {
            if (!(tag instanceof ListTag list) || list.getElementType() != Tag.TAG_COMPOUND) return false;
            for (int index = 0; index < list.size(); index++) {
                CompoundTag enchantment = list.getCompound(index);
                if (enchantmentIds.contains(enchantment.getString("id"))) return true;
            }
            return false;
        }

        private record StructuredRule(ResourceLocation itemId, ConditionGroup conditions) {}
        private record IndexedRule(int position, ConditionGroup conditions) {}

        private interface TagCheck {
            boolean matches(Tag tag);
        }

        private record PathChecks(List<String> path, List<TagCheck> checks) {
            boolean matches(CompoundTag root) {
                Tag value = root;
                for (String key : path) {
                    if (!(value instanceof CompoundTag compound)) return false;
                    value = compound.get(key);
                }
                if (value == null) return false;
                for (TagCheck check : checks) {
                    if (!check.matches(value)) return false;
                }
                return true;
            }
        }

        private record ConditionGroup(List<PathChecks> paths) {
            boolean matches(CompoundTag root) {
                for (PathChecks path : paths) {
                    if (!path.matches(root)) return false;
                }
                return true;
            }
        }

        private static StructuredRule parseStructuredRule(String text) {
            var tokens = JSON_TOKEN.matcher(text);
            int end = 0;
            while (end < text.length()) {
                tokens.region(end, text.length());
                if (!tokens.lookingAt()) throw new IllegalArgumentException("Invalid JSON token at " + end);
                end = tokens.end();
            }
            JsonElement parsed;
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                parsed = readJson(reader, 0);
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON data");
            } catch (IOException exception) {
                throw new IllegalArgumentException("Invalid JSON: " + exception.getMessage(), exception);
            }
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Expected an object");
            JsonObject object = parsed.getAsJsonObject();
            requireKeys(object, Set.of("item", "all"));
            ResourceLocation item = null;
            if (object.has("item")) {
                item = ResourceLocation.tryParse(requireString(object.get("item")));
                if (item == null) throw new IllegalArgumentException("Invalid item ID");
            }
            return new StructuredRule(item, parseConditions(object.get("all"), 0));
        }

        private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
            if (depth > 96) throw new IllegalArgumentException("JSON nesting exceeds 96");
            return switch (reader.peek()) {
                case BEGIN_OBJECT -> {
                    reader.beginObject();
                    JsonObject object = new JsonObject();
                    while (reader.hasNext()) {
                        String key = reader.nextName();
                        if (object.has(key)) throw new IllegalArgumentException("Duplicate JSON key: " + key);
                        object.add(key, readJson(reader, depth + 1));
                    }
                    reader.endObject();
                    yield object;
                }
                case BEGIN_ARRAY -> {
                    reader.beginArray();
                    JsonArray array = new JsonArray();
                    while (reader.hasNext()) array.add(readJson(reader, depth + 1));
                    reader.endArray();
                    yield array;
                }
                case STRING -> new JsonPrimitive(reader.nextString());
                case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
                case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
                case NULL -> {
                    reader.nextNull();
                    yield JsonNull.INSTANCE;
                }
                default -> throw new IllegalArgumentException("Unexpected JSON token");
            };
        }

        private static ConditionGroup parseConditions(JsonElement element, int depth) {
            if (depth > 32 || element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
                throw new IllegalArgumentException("Expected nonempty conditions with nesting at most 32");
            }
            Map<List<String>, List<TagCheck>> grouped = new LinkedHashMap<>();
            for (JsonElement child : element.getAsJsonArray()) {
                if (!child.isJsonObject()) throw new IllegalArgumentException("Condition must be an object");
                JsonObject condition = child.getAsJsonObject();
                requireKeys(condition, Set.of("path", "op", "value", "any"));
                List<String> path = parsePath(condition.get("path"));
                TagCheck check;
                if (condition.has("any")) {
                    if (condition.has("op") || condition.has("value")) {
                        throw new IllegalArgumentException("any cannot be combined with op or value");
                    }
                    ConditionGroup nested = parseConditions(condition.get("any"), depth + 1);
                    check = tag -> {
                        if (!(tag instanceof ListTag list) || list.getElementType() != Tag.TAG_COMPOUND) return false;
                        for (int index = 0; index < list.size(); index++) {
                            if (nested.matches(list.getCompound(index))) return true;
                        }
                        return false;
                    };
                } else {
                    String operator = condition.has("op") ? requireString(condition.get("op")) : "exists";
                    check = parseValueCheck(operator, condition.get("value"), path.get(path.size() - 1));
                }
                grouped.computeIfAbsent(path, ignored -> new ArrayList<>()).add(check);
            }
            return new ConditionGroup(grouped.entrySet().stream()
                .map(entry -> new PathChecks(entry.getKey(), List.copyOf(entry.getValue()))).toList());
        }

        private static List<String> parsePath(JsonElement element) {
            List<String> path = new ArrayList<>();
            if (element != null && element.isJsonArray()) {
                for (JsonElement key : element.getAsJsonArray()) path.add(requireString(key));
            } else {
                path.addAll(List.of(requireString(element).split("\\.", -1)));
            }
            if (path.isEmpty() || path.stream().anyMatch(String::isEmpty)) {
                throw new IllegalArgumentException("Path must contain nonempty keys");
            }
            return List.copyOf(path);
        }

        private static TagCheck parseValueCheck(String operator, JsonElement value, String key) {
            if (operator.equals("exists")) {
                if (value != null) throw new IllegalArgumentException("exists does not accept value");
                return tag -> (!(tag instanceof ListTag list) || !list.isEmpty())
                    && (!(key.equals("Enchantments") || key.equals("StoredEnchantments")) || tag instanceof ListTag);
            }
            if (!Set.of("eq", "gt", "gte", "lt", "lte").contains(operator)
                    || value == null || !value.isJsonPrimitive()) {
                throw new IllegalArgumentException("Expected eq/gt/gte/lt/lte and a scalar value");
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                BigDecimal expected = new BigDecimal(primitive.getAsString());
                Long integral;
                try { integral = expected.longValueExact(); }
                catch (ArithmeticException ignored) { integral = null; }
                Long expectedLong = integral;
                return tag -> {
                    if (!(tag instanceof NumericTag number)) return false;
                    int comparison;
                    if (number.getId() <= Tag.TAG_LONG) {
                        comparison = expectedLong != null ? Long.compare(number.getAsLong(), expectedLong)
                            : BigDecimal.valueOf(number.getAsLong()).compareTo(expected);
                    } else {
                        double actual = number.getAsDouble();
                        if (!Double.isFinite(actual)) return false;
                        comparison = new BigDecimal(actual).compareTo(expected);
                    }
                    return switch (operator) {
                        case "eq" -> comparison == 0;
                        case "gt" -> comparison > 0;
                        case "gte" -> comparison >= 0;
                        case "lt" -> comparison < 0;
                        case "lte" -> comparison <= 0;
                        default -> false;
                    };
                };
            }
            if (!operator.equals("eq")) throw new IllegalArgumentException("Ordering requires a numeric value");
            if (primitive.isBoolean()) {
                byte expected = (byte) (primitive.getAsBoolean() ? 1 : 0);
                return tag -> tag.getId() == Tag.TAG_BYTE && ((NumericTag) tag).getAsByte() == expected;
            }
            String expected = primitive.getAsString();
            return tag -> tag instanceof StringTag string && expected.equals(string.getAsString());
        }

        private static String requireString(JsonElement value) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Expected a string");
            }
            return value.getAsString();
        }

        private static void requireKeys(JsonObject object, Set<String> allowed) {
            for (String key : object.keySet()) {
                if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown key: " + key);
            }
        }
    }

    private static int safeInt(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static List<String> safeStrings(ForgeConfigSpec.ConfigValue<List<? extends String>> value) {
        try {
            return value.get().stream().map(String::valueOf).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    public static final class ItemRuleSet {
        static final ItemRuleSet EMPTY = new ItemRuleSet(Set.of(), Set.of(), Set.of(), Set.of());
        private final Set<String> ids;
        private final Set<String> prefixes;
        private final Set<String> mods;
        private final Set<TagKey<Item>> tags;

        private ItemRuleSet(Set<String> ids, Set<String> prefixes, Set<String> mods, Set<TagKey<Item>> tags) {
            this.ids = Set.copyOf(ids);
            this.prefixes = Set.copyOf(prefixes);
            this.mods = Set.copyOf(mods);
            this.tags = Set.copyOf(tags);
        }

        public static ItemRuleSet parse(List<? extends String> raw) {
            Set<String> ids = new HashSet<>();
            Set<String> prefixes = new HashSet<>();
            Set<String> mods = new HashSet<>();
            Set<TagKey<Item>> tags = new HashSet<>();
            for (String entry : raw) {
                if (entry == null) continue;
                String value = entry.trim().toLowerCase(Locale.ROOT);
                if (value.startsWith("@") && value.length() > 1) {
                    String namespace = normalizeNamespace(value.substring(1));
                    if (namespace != null) mods.add(namespace);
                } else if (value.startsWith("#")) {
                    ResourceLocation id = normalizedLocation(value.substring(1));
                    if (id != null) tags.add(TagKey.create(Registries.ITEM, id));
                } else if (value.endsWith("::")) {
                    ResourceLocation id = normalizedLocation(value.substring(0, value.length() - 2));
                    if (id != null) prefixes.add(id.toString());
                } else {
                    ResourceLocation id = normalizedLocation(value);
                    if (id != null) ids.add(id.toString());
                }
            }
            return new ItemRuleSet(ids, prefixes, mods, tags);
        }

        public boolean matches(Item item) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            return id != null && (matchesLocation(id) || matchesTag(item));
        }

        public boolean matchesId(String rawId) {
            ResourceLocation location = normalizedLocation(rawId);
            return location != null && matchesLocation(location);
        }

        private boolean matchesLocation(ResourceLocation location) {
            String id = location.toString();
            if (ids.contains(id)) return true;
            if (mods.contains(location.getNamespace())) return true;
            for (String prefix : prefixes) {
                if (id.startsWith(prefix)) return true;
            }
            return false;
        }

        private boolean matchesTag(Item item) {
            for (TagKey<Item> tag : tags) {
                if (item.builtInRegistryHolder().is(tag)) return true;
            }
            return false;
        }
    }

    public static final class DedupRuleSet {
        static final DedupRuleSet EMPTY = new DedupRuleSet(Map.of(), Map.of(), Map.of(), Map.of());
        private final Map<String, Integer> ids;
        private final Map<String, Integer> prefixes;
        private final Map<String, Integer> mods;
        private final Map<TagKey<Item>, Integer> tags;

        private DedupRuleSet(Map<String, Integer> ids, Map<String, Integer> prefixes,
                             Map<String, Integer> mods, Map<TagKey<Item>, Integer> tags) {
            this.ids = Map.copyOf(ids);
            this.prefixes = Map.copyOf(prefixes);
            this.mods = Map.copyOf(mods);
            this.tags = Map.copyOf(tags);
        }

        public static DedupRuleSet parse(List<? extends String> raw, int defaultLimit) {
            Map<String, Integer> ids = new HashMap<>();
            Map<String, Integer> prefixes = new HashMap<>();
            Map<String, Integer> mods = new HashMap<>();
            Map<TagKey<Item>, Integer> tags = new HashMap<>();
            for (String entry : raw) {
                if (entry == null) continue;
                String rule = entry.trim().toLowerCase(Locale.ROOT);
                int limit = defaultLimit;
                int comma = rule.lastIndexOf(',');
                if (comma > 0) {
                    try {
                        limit = Integer.parseInt(rule.substring(comma + 1).trim());
                        rule = rule.substring(0, comma).trim();
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                }
                if (limit < 0) limit = defaultLimit;
                if (rule.startsWith("@") && rule.length() > 1) {
                    String namespace = normalizeNamespace(rule.substring(1));
                    if (namespace != null) mods.put(namespace, limit);
                } else if (rule.startsWith("#")) {
                    ResourceLocation id = normalizedLocation(rule.substring(1));
                    if (id != null) tags.put(TagKey.create(Registries.ITEM, id), limit);
                } else if (rule.endsWith("::")) {
                    ResourceLocation id = normalizedLocation(rule.substring(0, rule.length() - 2));
                    if (id != null) prefixes.put(id.toString(), limit);
                } else {
                    ResourceLocation id = normalizedLocation(rule);
                    if (id != null) ids.put(id.toString(), limit);
                }
            }
            return new DedupRuleSet(ids, prefixes, mods, tags);
        }

        static boolean isValidEntry(String raw) {
            DedupRuleSet parsed = parse(List.of(raw), 1);
            return !parsed.ids.isEmpty() || !parsed.prefixes.isEmpty()
                || !parsed.mods.isEmpty() || !parsed.tags.isEmpty();
        }

        public int limit(Item item) {
            Integer limit = findLimit(item);
            return limit == null ? 0 : limit;
        }

        private Integer findLimit(Item item) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) return null;
            Integer byId = findLimit(id);
            if (byId != null) return byId;
            Integer tagLimit = null;
            for (Map.Entry<TagKey<Item>, Integer> entry : tags.entrySet()) {
                if (item.builtInRegistryHolder().is(entry.getKey())) {
                    tagLimit = tagLimit == null ? entry.getValue() : Math.min(tagLimit, entry.getValue());
                }
            }
            return tagLimit;
        }

        public int limitForId(String rawId) {
            ResourceLocation location = normalizedLocation(rawId);
            Integer limit = location == null ? null : findLimit(location);
            return limit == null ? 0 : limit;
        }

        private Integer findLimit(ResourceLocation location) {
            String id = location.toString();
            Integer exact = ids.get(id);
            if (exact != null) return exact;
            Map.Entry<String, Integer> longestPrefix = null;
            for (Map.Entry<String, Integer> entry : prefixes.entrySet()) {
                if (id.startsWith(entry.getKey())
                        && (longestPrefix == null || entry.getKey().length() > longestPrefix.getKey().length())) {
                    longestPrefix = entry;
                }
            }
            if (longestPrefix != null) return longestPrefix.getValue();
            return mods.get(location.getNamespace());
        }

        public boolean hasRuleForId(String rawId) {
            ResourceLocation location = normalizedLocation(rawId);
            return location != null && findLimit(location) != null;
        }

        public boolean hasRule(Item item) {
            return findLimit(item) != null;
        }
    }

    private static ResourceLocation normalizedLocation(String raw) {
        if (raw == null) return null;
        return ResourceLocation.tryParse(raw.trim().toLowerCase(Locale.ROOT));
    }

    private static String normalizeNamespace(String raw) {
        ResourceLocation probe = normalizedLocation(raw + ":probe");
        return probe == null ? null : probe.getNamespace();
    }
}
