package com.azukaar.betterlucklootr;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class LootTableFilter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Field TABLE_POOLS;
    private static final Field POOL_ENTRIES;
    private static final Field LOOT_ITEM_ITEM;
    private static final Field TAG_ENTRY_TAG;
    private static final Field SINGLETON_FUNCTIONS;

    static {
        TABLE_POOLS = access(LootTable.class, "pools", List.class);
        POOL_ENTRIES = access(LootPool.class, "entries", LootPoolEntryContainer[].class);
        LOOT_ITEM_ITEM = access(LootItem.class, "item", Item.class);
        TAG_ENTRY_TAG = access(TagEntry.class, "tag", TagKey.class);
        SINGLETON_FUNCTIONS = access(LootPoolSingletonContainer.class, "functions", LootItemFunction[].class);
        LOGGER.info("[BLL-FILTER] Reflection fields: TABLE_POOLS={} POOL_ENTRIES={} LOOT_ITEM_ITEM={} TAG_ENTRY_TAG={} SINGLETON_FUNCTIONS={}",
            TABLE_POOLS != null, POOL_ENTRIES != null, LOOT_ITEM_ITEM != null, TAG_ENTRY_TAG != null, SINGLETON_FUNCTIONS != null);
    }

    private static Field access(Class<?> clazz, String name, Class<?> type) {
        try {
            Field f = clazz.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            }
            return null;
        }
    }

    @SubscribeEvent
    public static void onLootTableLoad(LootTableLoadEvent event) {
        long t = System.nanoTime();
        if (filterGlobalBlacklist(event.getTable())) {
            long dt = (System.nanoTime() - t) / 1_000_000;
            if (dt > 5) LOGGER.debug("[BLL-FILTER] filtered {} in {}ms", event.getName(), dt);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean filterGlobalBlacklist(LootTable table) {
        if (TABLE_POOLS == null || POOL_ENTRIES == null) return false;

        List<LootPool> pools = getField(TABLE_POOLS, table);
        if (pools == null || pools.isEmpty()) return false;

        boolean anyChanged = false;
        List<LootPool> newPools = new ArrayList<>(pools.size());

        for (LootPool pool : pools) {
            LootPoolEntryContainer[] entries = getField(POOL_ENTRIES, pool);
            if (entries == null || entries.length == 0) {
                newPools.add(pool);
                continue;
            }

            boolean poolChanged = false;
            List<LootPoolEntryContainer> filtered = new ArrayList<>();

            for (LootPoolEntryContainer entry : entries) {
                if (entry instanceof LootItem itemEntry) {
                    Item item = getField(LOOT_ITEM_ITEM, itemEntry);
                    if (item != null && BLModConfig.SERVER.isGlobalBlacklisted(item)) {
                        poolChanged = true;
                        continue;
                    }
                    LootItemFunction[] funcs = getField(SINGLETON_FUNCTIONS, itemEntry);
                    if (funcs != null) {
                        boolean hasExpMap = false;
                        for (LootItemFunction f : funcs) {
                            if (f instanceof ExplorationMapFunction) { hasExpMap = true; break; }
                        }
                        if (hasExpMap) {
                            poolChanged = true;
                            LOGGER.debug("[BLL-FILTER] LootTableLoad: skip ExplorationMapFunction item={}", item);
                            continue;
                        }
                    }
                    filtered.add(entry);
                } else if (entry instanceof TagEntry tagEntry) {
                    TagKey<Item> tag = getField(TAG_ENTRY_TAG, tagEntry);
                    if (tag == null) {
                        filtered.add(entry);
                        continue;
                    }

                    List<LootPoolEntryContainer> expanded = expandTag(tagEntry, tag);
                    if (expanded != null) {
                        filtered.addAll(expanded);
                        poolChanged = true;
                    } else {
                        filtered.add(entry);
                    }
                } else {
                    filtered.add(entry);
                }
            }

            if (filtered.isEmpty()) {
                anyChanged = true;
                continue;
            }

            if (poolChanged) {
                anyChanged = true;
                try {
                    POOL_ENTRIES.set(pool, filtered.toArray(new LootPoolEntryContainer[0]));
                } catch (Exception e) {
                }
            }
            newPools.add(pool);
        }

        if (!anyChanged) return false;

        try {
            TABLE_POOLS.set(table, newPools);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 展开 TagEntry，移除内含的 global blacklist 物品。
     * 返回 null 表示无法展开（构造器不可用），调用方应保留原 TagEntry。
     */
    private static List<LootPoolEntryContainer> expandTag(TagEntry tagEntry, TagKey<Item> tag) {
        if (BetterLuckLootr.LOOT_ITEM_CTOR == null) return null;

        var optTag = BuiltInRegistries.ITEM.getTag(tag);
        if (optTag.isEmpty()) return null;

        List<Item> blacklisted = new ArrayList<>();
        List<Item> keep = new ArrayList<>();
        for (var holder : optTag.get()) {
            if (BLModConfig.SERVER.isGlobalBlacklisted(holder.value())) {
                blacklisted.add(holder.value());
            } else {
                keep.add(holder.value());
            }
        }

        if (blacklisted.isEmpty()) return null; // 无需展开

        List<LootPoolEntryContainer> expanded = new ArrayList<>(keep.size());
        for (Item item : keep) {
            try {
                LootItem li = BetterLuckLootr.LOOT_ITEM_CTOR.newInstance(item, 1, 0,
                    new LootItemFunction[0], new LootItemCondition[0]);
                expanded.add(li);
            } catch (Exception e) {
                return null;
            }
        }

        return expanded;
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Field field, Object target) {
        if (field == null) return null;
        try {
            return (T) field.get(target);
        } catch (Exception e) {
            return null;
        }
    }
}
