package com.azukaar.betterlucklootr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.azukaar.betterlucklootr.mixin.LootItemAccessor;
import com.azukaar.betterlucklootr.mixin.LootPoolEntryContainerAccessor;
import com.azukaar.betterlucklootr.mixin.LootPoolSingletonContainerAccessor;
import com.azukaar.betterlucklootr.mixin.TagEntryAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntry;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.TagEntry;

public final class PoolPickCache {
    public static final PoolPickCache UNELIGIBLE = new PoolPickCache(null, null, null, null, true);
    public final LootPoolEntry[] flatEntries;
    public final Item[] items;
    public final int[] baseWeights;
    public final int[] qualities;
    public final boolean ineligible;
    public final boolean qualityAllZero;
    public final long[] cachedPrefix;
    public final int[] cachedActiveIdx;
    public final long cachedTotal;
    private final Distribution baseDistribution;
    private volatile LuckDistribution luckDistribution;
    private final LootPoolEntry[] runtimeEntries;
    private final Item[] runtimeItems;
    private final int[] runtimeWeights;
    private final int[] runtimeQualities;
    private final EntryDependency[] dependencies;

    private PoolPickCache(LootPoolEntry[] flatEntries, Item[] items, int[] baseWeights, int[] qualities, boolean ineligible) {
        this(flatEntries, items, baseWeights, qualities, ineligible, new EntryDependency[0]);
    }

    private PoolPickCache(LootPoolEntry[] flatEntries, Item[] items, int[] baseWeights, int[] qualities,
                          boolean ineligible, EntryDependency[] dependencies) {
        this.flatEntries = flatEntries == null ? null : flatEntries.clone();
        this.items = items == null ? null : items.clone();
        this.baseWeights = baseWeights == null ? null : baseWeights.clone();
        this.qualities = qualities == null ? null : qualities.clone();
        this.runtimeEntries = flatEntries == null ? null : flatEntries.clone();
        this.runtimeItems = items == null ? null : items.clone();
        this.runtimeWeights = baseWeights == null ? null : baseWeights.clone();
        this.runtimeQualities = qualities == null ? null : qualities.clone();
        this.dependencies = dependencies;
        this.ineligible = ineligible;
        if (ineligible) {
            this.qualityAllZero = false;
            this.cachedPrefix = null;
            this.cachedActiveIdx = null;
            this.cachedTotal = 0;
            this.baseDistribution = null;
            return;
        }
        boolean allZero = true;
        for (int q : qualities) {
            if (q != 0) { allZero = false; break; }
        }
        this.qualityAllZero = allZero;
        {
            int activeCount = 0;
            for (int i = 0; i < baseWeights.length; i++) {
                int w = LootGenerationPolicy.effectiveWeight(baseWeights[i], qualities[i], 0.0F);
                if (w > 0) activeCount++;
            }
            int[] activeIdx = new int[activeCount];
            long[] prefix = new long[activeCount];
            int idx = 0;
            long sum = 0;
            for (int i = 0; i < baseWeights.length; i++) {
                int weight = LootGenerationPolicy.effectiveWeight(baseWeights[i], qualities[i], 0.0F);
                if (weight > 0) {
                    activeIdx[idx] = i;
                    sum += weight;
                    prefix[idx] = sum;
                    idx++;
                }
            }
            this.cachedActiveIdx = activeIdx;
            this.cachedPrefix = prefix;
            this.cachedTotal = sum;
            this.baseDistribution = new Distribution(activeIdx.clone(), prefix.clone(), sum);
        }
    }

    private static final ConcurrentHashMap<Item, LootPoolEntry> ITEM_ENTRY_CACHE = new ConcurrentHashMap<>();

    public static LootPoolEntry createItemEntry(Item item) {
        LootPoolEntry cached = ITEM_ENTRY_CACHE.get(item);
        if (cached != null) return cached;
        LootPoolEntryContainer container = LootItem.lootTableItem(item).build();
        LootPoolEntry[] expanded = new LootPoolEntry[1];
        if (!container.expand(null, entry -> expanded[0] = entry) || expanded[0] == null) return null;
        LootPoolEntry previous = ITEM_ENTRY_CACHE.putIfAbsent(item, expanded[0]);
        return previous == null ? expanded[0] : previous;
    }

    public static PoolPickCache build(LootPoolEntryContainer[] entries) {
        if (entries == null || entries.length == 0) return UNELIGIBLE;
        EntryDependency[] dependencies = new EntryDependency[entries.length];
        for (int index = 0; index < entries.length; index++) dependencies[index] = new EntryDependency(entries[index]);
        return build(entries, dependencies);
    }

    private static PoolPickCache build(LootPoolEntryContainer[] entries, EntryDependency[] dependencies) {

        List<LootPoolEntry> expEntries = new ArrayList<>();
        List<Item> expItems = new ArrayList<>();
        List<Integer> expWeights = new ArrayList<>();
        List<Integer> expQualities = new ArrayList<>();

        for (LootPoolEntryContainer container : entries) {
            LootPoolEntryContainerAccessor containerAccessor = (LootPoolEntryContainerAccessor) container;
            if (containerAccessor.getConditions().length > 0) return ineligible(dependencies);
            if (container.getClass() == TagEntry.class) {
                TagEntry tagEntry = (TagEntry) container;
                TagEntryAccessor tagAcc = (TagEntryAccessor) tagEntry;
                LootPoolSingletonContainerAccessor tagSacc = (LootPoolSingletonContainerAccessor) tagEntry;
                if (!LootGenerationPolicy.isFastPoolEntry(false, true, tagAcc.getExpand(), false,
                        tagSacc.getFunctions().length > 0)) return ineligible(dependencies);
                TagKey<Item> tag = tagAcc.getTag();
                var optTag = BuiltInRegistries.ITEM.getTag(tag);
                if (optTag.isEmpty()) return ineligible(dependencies);

                int weight = tagSacc.getWeight();
                int quality = tagSacc.getQuality();

                for (var holder : optTag.get()) {
                    Item item = holder.value();
                    LootPoolEntry entry = createItemEntry(item);
                    if (entry == null) return ineligible(dependencies);
                    expEntries.add(entry);
                    expItems.add(item);
                    expWeights.add(weight);
                    expQualities.add(quality);
                }
            } else if (container.getClass() == LootItem.class) {
                LootItem lootItem = (LootItem) container;
                LootPoolSingletonContainerAccessor sacc = (LootPoolSingletonContainerAccessor) lootItem;
                if (!LootGenerationPolicy.isFastPoolEntry(true, false, false, false,
                        sacc.getFunctions().length > 0)) return ineligible(dependencies);
                expEntries.add(sacc.getEntry());
                expItems.add(((LootItemAccessor) lootItem).getItem());
                expWeights.add(sacc.getWeight());
                expQualities.add(sacc.getQuality());
            } else {
                return ineligible(dependencies);
            }
        }

        if (expEntries.isEmpty()) return ineligible(dependencies);

        int n = expEntries.size();
        LootPoolEntry[] flatEntries = expEntries.toArray(new LootPoolEntry[n]);
        Item[] items = expItems.toArray(new Item[n]);
        int[] weights = new int[n];
        int[] qualities = new int[n];
        for (int i = 0; i < n; i++) {
            weights[i] = expWeights.get(i);
            qualities[i] = expQualities.get(i);
        }
        return new PoolPickCache(flatEntries, items, weights, qualities, false, dependencies);
    }

    private static PoolPickCache ineligible(EntryDependency[] dependencies) {
        return new PoolPickCache(null, null, null, null, true, dependencies);
    }

    public boolean matches(LootPoolEntryContainer[] entries) {
        if (entries == null) return dependencies.length == 0;
        if (entries.length != dependencies.length) return false;
        for (int index = 0; index < entries.length; index++) {
            if (!dependencies[index].matches(entries[index])) return false;
        }
        return true;
    }

    public Item item(int index) {
        return runtimeItems[index];
    }

    public LootPoolEntry entry(int index) {
        return runtimeEntries[index];
    }

    private static final class EntryDependency {
        private final LootPoolEntryContainer source;
        private final Object[] conditions;
        private final Object[] functions;
        private final int weight;
        private final int quality;
        private final LootPoolEntry entry;
        private final Item item;
        private final TagKey<Item> tag;
        private final boolean expand;
        private final Item[] members;

        private EntryDependency(LootPoolEntryContainer source) {
            this.source = source;
            conditions = ((LootPoolEntryContainerAccessor) source).getConditions().clone();
            if (source instanceof LootPoolSingletonContainer) {
                var accessor = (LootPoolSingletonContainerAccessor) source;
                functions = accessor.getFunctions().clone();
                weight = accessor.getWeight();
                quality = accessor.getQuality();
                entry = accessor.getEntry();
            } else {
                functions = null;
                weight = quality = 0;
                entry = null;
            }
            item = source instanceof LootItem ? ((LootItemAccessor) source).getItem() : null;
            if (source instanceof TagEntry) {
                var accessor = (TagEntryAccessor) source;
                tag = accessor.getTag();
                expand = accessor.getExpand();
                var holders = BuiltInRegistries.ITEM.getTag(tag).orElse(null);
                members = holders == null ? new Item[0] : new Item[holders.size()];
                for (int index = 0; index < members.length; index++) members[index] = holders.get(index).value();
            } else {
                tag = null;
                expand = false;
                members = null;
            }
        }

        private boolean matches(LootPoolEntryContainer current) {
            if (source != current || !sameElements(conditions,
                    ((LootPoolEntryContainerAccessor) current).getConditions())) return false;
            if (functions != null) {
                var accessor = (LootPoolSingletonContainerAccessor) current;
                if (weight != accessor.getWeight() || quality != accessor.getQuality() || entry != accessor.getEntry()
                        || !sameElements(functions, accessor.getFunctions())) return false;
            }
            if (current instanceof LootItem && item != ((LootItemAccessor) current).getItem()) return false;
            if (tag != null) {
                var accessor = (TagEntryAccessor) current;
                if (tag != accessor.getTag() || expand != accessor.getExpand()) return false;
                var holders = BuiltInRegistries.ITEM.getTag(tag).orElse(null);
                if (members.length != (holders == null ? 0 : holders.size())) return false;
                for (int index = 0; index < members.length; index++) {
                    if (members[index] != holders.get(index).value()) return false;
                }
            }
            return true;
        }

        private static boolean sameElements(Object[] saved, Object[] current) {
            if (saved.length != current.length) return false;
            for (int index = 0; index < saved.length; index++) if (saved[index] != current[index]) return false;
            return true;
        }
    }

    public Distribution distribution(float luck) {
        if (luck == 0.0F || qualityAllZero && Float.isFinite(luck)) return baseDistribution;
        int luckBits = Float.floatToRawIntBits(luck);
        LuckDistribution cached = luckDistribution;
        if (cached != null && cached.luckBits == luckBits) return cached.distribution;
        Distribution distribution = buildDistribution(luck);
        luckDistribution = new LuckDistribution(luckBits, distribution);
        return distribution;
    }

    private Distribution buildDistribution(float luck) {
        int[] active = new int[runtimeWeights.length];
        long[] prefix = new long[runtimeWeights.length];
        int count = 0;
        long total = 0;
        for (int i = 0; i < runtimeWeights.length; i++) {
            int weight = LootGenerationPolicy.effectiveWeight(runtimeWeights[i], runtimeQualities[i], luck);
            if (weight <= 0) continue;
            active[count] = i;
            total += weight;
            prefix[count] = total;
            count++;
        }
        return new Distribution(Arrays.copyOf(active, count), Arrays.copyOf(prefix, count), total);
    }

    private static final class LuckDistribution {
        private final int luckBits;
        private final Distribution distribution;

        private LuckDistribution(int luckBits, Distribution distribution) {
            this.luckBits = luckBits;
            this.distribution = distribution;
        }
    }

    public static final class Distribution {
        private final int[] activeEntryIndexes;
        private final long[] prefix;
        private final long total;

        private Distribution(int[] activeEntryIndexes, long[] prefix, long total) {
            this.activeEntryIndexes = activeEntryIndexes;
            this.prefix = prefix;
            this.total = total;
        }

        public int count() {
            return activeEntryIndexes.length;
        }

        public long total() {
            return total;
        }

        public int entryIndex(int position) {
            return activeEntryIndexes[position];
        }

        public int choose(long target) {
            int low = 0;
            int high = prefix.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (prefix[middle] <= target) low = middle + 1;
                else high = middle;
            }
            return low;
        }
    }
}
