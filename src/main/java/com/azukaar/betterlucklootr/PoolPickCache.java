package com.azukaar.betterlucklootr;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.azukaar.betterlucklootr.mixin.LootItemAccessor;
import com.azukaar.betterlucklootr.mixin.LootPoolEntryContainerAccessor;
import com.azukaar.betterlucklootr.mixin.LootPoolSingletonContainerAccessor;
import com.azukaar.betterlucklootr.mixin.TagEntryAccessor;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntry;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

public final class PoolPickCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final PoolPickCache UNELIGIBLE = new PoolPickCache(null, null, null, true);
    public static final AtomicLong COND_CACHE_GEN = new AtomicLong(0);

    public static void invalidateCondCaches() {
        COND_CACHE_GEN.incrementAndGet();
    }

    public static class CondCache {
        public final LootPoolEntry[] entries;
        public final int[] weights;
        public final int[] prefix;
        public final int totalWeight;

        public CondCache(LootPoolEntry[] entries, int[] weights) {
            this.entries = entries;
            this.weights = weights;
            this.prefix = new int[entries.length];
            int sum = 0;
            for (int i = 0; i < entries.length; i++) {
                sum += weights[i];
                this.prefix[i] = sum;
            }
            this.totalWeight = sum;
        }

        public boolean isEmpty() { return entries.length == 0; }
    }

    public final LootPoolEntry[] flatEntries;
    public final int[] baseWeights;
    public final int[] qualities;
    public final boolean ineligible;
    public final boolean qualityAllZero;
    public final int[] cachedPrefix;
    public final int[] cachedActiveIdx;
    public final int cachedTotal;

    private PoolPickCache(LootPoolEntry[] flatEntries, int[] baseWeights, int[] qualities, boolean ineligible) {
        this.flatEntries = flatEntries;
        this.baseWeights = baseWeights;
        this.qualities = qualities;
        this.ineligible = ineligible;
        if (ineligible) {
            this.qualityAllZero = false;
            this.cachedPrefix = null;
            this.cachedActiveIdx = null;
            this.cachedTotal = 0;
            return;
        }
        boolean allZero = true;
        for (int q : qualities) {
            if (q != 0) { allZero = false; break; }
        }
        this.qualityAllZero = allZero;
        if (allZero) {
            int activeCount = 0;
            for (int w : baseWeights) {
                if (w > 0) activeCount++;
            }
            int[] activeIdx = new int[activeCount];
            int[] prefix = new int[activeCount];
            int idx = 0, sum = 0;
            for (int i = 0; i < baseWeights.length; i++) {
                if (baseWeights[i] > 0) {
                    activeIdx[idx] = i;
                    sum += baseWeights[i];
                    prefix[idx] = sum;
                    idx++;
                }
            }
            this.cachedActiveIdx = activeIdx;
            this.cachedPrefix = prefix;
            this.cachedTotal = sum;
        } else {
            this.cachedActiveIdx = null;
            this.cachedPrefix = null;
            this.cachedTotal = 0;
        }
    }

    private static final ConcurrentHashMap<Item, LootPoolEntry> ITEM_ENTRY_CACHE = new ConcurrentHashMap<>();

    public static LootPoolEntry createItemEntry(Item item) {
        LootPoolEntry cached = ITEM_ENTRY_CACHE.get(item);
        if (cached != null) return cached;
        if (BetterLuckLootr.LOOT_ITEM_CTOR == null) return null;
        try {
            LootItem li = BetterLuckLootr.LOOT_ITEM_CTOR.newInstance(item, 1, 0,
                new LootItemFunction[0], new LootItemCondition[0]);
            LootPoolSingletonContainerAccessor sacc = (LootPoolSingletonContainerAccessor) li;
            LootPoolEntry entry = sacc.getEntry();
            ITEM_ENTRY_CACHE.put(item, entry);
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    public static PoolPickCache build(LootPoolEntryContainer[] entries) {
        if (entries == null || entries.length == 0) return UNELIGIBLE;

        List<LootPoolEntry> expEntries = new ArrayList<>();
        List<Integer> expWeights = new ArrayList<>();
        List<Integer> expQualities = new ArrayList<>();

        for (LootPoolEntryContainer container : entries) {
            if (container instanceof TagEntry tagEntry) {
                TagEntryAccessor tagAcc = (TagEntryAccessor) tagEntry;
                TagKey<Item> tag = tagAcc.getTag();
                var optTag = BuiltInRegistries.ITEM.getTag(tag);
                if (optTag.isEmpty() || BetterLuckLootr.LOOT_ITEM_CTOR == null) return UNELIGIBLE;

                LootPoolSingletonContainerAccessor tagSacc = (LootPoolSingletonContainerAccessor) tagEntry;
                int weight = tagSacc.getWeight();
                int quality = tagSacc.getQuality();

                for (var holder : optTag.get()) {
                    Item item = holder.value();
                    if (BLModConfig.SERVER.isGlobalBlacklisted(item)) continue;
                    try {
                        LootItem li = BetterLuckLootr.LOOT_ITEM_CTOR.newInstance(item, 1, 0,
                            new LootItemFunction[0], new LootItemCondition[0]);
                        LootPoolSingletonContainerAccessor liSacc = (LootPoolSingletonContainerAccessor) li;
                        expEntries.add(liSacc.getEntry());
                        expWeights.add(weight);
                        expQualities.add(quality);
                    } catch (Exception e) {
                        // skip this tag item
                    }
                }
            } else {
                if (!(container instanceof LootPoolSingletonContainer)) return UNELIGIBLE;
                LootPoolEntryContainerAccessor acc = (LootPoolEntryContainerAccessor) container;
                if (acc.getConditions().length > 0) return UNELIGIBLE;
                if (container instanceof LootItem li && BLModConfig.SERVER.isGlobalBlacklisted(((LootItemAccessor)li).getItem())) continue;
                LootPoolSingletonContainer singleton = (LootPoolSingletonContainer) container;
                LootPoolSingletonContainerAccessor sacc = (LootPoolSingletonContainerAccessor) singleton;
                boolean skip = false;
                for (LootItemFunction f : sacc.getFunctions()) {
                    if (f instanceof ExplorationMapFunction) { skip = true; break; }
                }
                if (skip) {
                    String itemName = container instanceof LootItem li ? String.valueOf(BuiltInRegistries.ITEM.getKey(((LootItemAccessor)li).getItem())) : "?";
                    LOGGER.debug("[BLL-FILTER] PoolPickCache.build: skip ExplorationMapFunction item={}", itemName);
                    continue;
                }
                expEntries.add(sacc.getEntry());
                expWeights.add(sacc.getWeight());
                expQualities.add(sacc.getQuality());
            }
        }

        if (expEntries.isEmpty()) return UNELIGIBLE;

        int n = expEntries.size();
        LootPoolEntry[] flatEntries = expEntries.toArray(new LootPoolEntry[n]);
        int[] weights = new int[n];
        int[] qualities = new int[n];
        for (int i = 0; i < n; i++) {
            weights[i] = expWeights.get(i);
            qualities[i] = expQualities.get(i);
        }
        return new PoolPickCache(flatEntries, weights, qualities, false);
    }
}
