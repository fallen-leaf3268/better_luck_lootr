package com.azukaar.betterlucklootr.mixin;

import com.azukaar.betterlucklootr.BetterLuckLootModifier;
import com.azukaar.betterlucklootr.BLModConfig;
import com.azukaar.betterlucklootr.PoolPickCache;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntry;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.LootTableReference;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.functions.ExplorationMapFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

@Mixin(LootPool.class)
public abstract class LootPoolMixin {

    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    @Unique
    private volatile PoolPickCache betterlucklootr$cache;

    @Unique
    private volatile PoolPickCache.CondCache betterlucklootr$condCache;

    @Unique
    private volatile long betterlucklootr$condCacheGen;

    @Unique
    private PoolPickCache betterlucklootr$getCache() {
        PoolPickCache cache = this.betterlucklootr$cache;
        if (cache == null) {
            LootPoolEntryContainer[] entries = ((LootPoolAccessor) this).getEntries();
            cache = PoolPickCache.build(entries);
            this.betterlucklootr$cache = cache;
        }
        return cache;
    }

    @Inject(method = "addRandomItems", at = @At("HEAD"), cancellable = true, require = 0)
    private void betterlucklootr$fastAddRandomItems(Consumer<ItemStack> consumer, LootContext context, CallbackInfo ci) {
        int[] stats = BetterLuckLootModifier.POOL_STATS.get();
        PoolPickCache cache = betterlucklootr$getCache();
        if (cache.ineligible) {
            long t = System.nanoTime();
            boolean result = betterlucklootr$tryConditionalFastPath(consumer, context, ci);
            long dt = (System.nanoTime() - t) / 1_000_000;
            if (dt > 5) LOGGER.debug("[BLL-TIMING] addRandomItems(cond) {}ms result={}", dt, result);
            if (result) {
                stats[0]++;
            } else {
                stats[1]++;
            }
            return;
        }

        LootPoolAccessor acc = (LootPoolAccessor) this;
        if (!acc.getCompositeCondition().test(context)) {
            stats[2]++;
            return;
        }

        Consumer<ItemStack> decorated = LootItemFunction.decorate(acc.getCompositeFunction(), consumer, context);
        int rolls = acc.getRolls().getInt(context)
            + Mth.floor(acc.getBonusRolls().getFloat(context) * context.getLuck());
        if (rolls <= 0) {
            stats[3]++;
            ci.cancel();
            return;
        }

        long t = System.nanoTime();
        if (cache.qualityAllZero) {
            for (int i = 0; i < rolls; i++) {
                betterlucklootr$pickFromCached(decorated, context, cache);
            }
        } else {
            for (int i = 0; i < rolls; i++) {
                betterlucklootr$pickWithLuck(decorated, context, cache);
            }
        }
        long dt = (System.nanoTime() - t) / 1_000_000;
        if (dt > 5) LOGGER.debug("[BLL-TIMING] addRandomItems(fast) {}ms rolls={} entries={}", dt, rolls, cache.flatEntries.length);
        stats[0]++;
        ci.cancel();
    }

    @Unique
    private boolean betterlucklootr$tryConditionalFastPath(Consumer<ItemStack> consumer, LootContext context, CallbackInfo ci) {
        long t0 = System.nanoTime();
        LootPoolAccessor acc = (LootPoolAccessor) this;
        if (!acc.getCompositeCondition().test(context)) {
            BetterLuckLootModifier.POOL_STATS.get()[2]++;
            return false;
        }

        long currentGen = PoolPickCache.COND_CACHE_GEN.get();
        PoolPickCache.CondCache cc;
        boolean cacheHit;
        if (this.betterlucklootr$condCacheGen == currentGen && (cc = this.betterlucklootr$condCache) != null) {
            cacheHit = true;
        } else {
            cacheHit = false;
            cc = betterlucklootr$buildCondCache(acc, context);
            if (cc == null) return false;
            this.betterlucklootr$condCache = cc;
            this.betterlucklootr$condCacheGen = currentGen;
        }
        long tCache = System.nanoTime();

        if (cc.isEmpty()) {
            ci.cancel();
            return true;
        }

        LootPoolEntry[] passing = cc.entries;
        int[] weights = cc.weights;
        int[] prefix = cc.prefix;
        int sum = cc.totalWeight;
        int count = passing.length;

        int expansionRolls = betterlucklootr$calcExpansionRolls(acc, context);
        long tExp = System.nanoTime();

        Consumer<ItemStack> decorated = LootItemFunction.decorate(acc.getCompositeFunction(), consumer, context);
        int rolls = acc.getRolls().getInt(context)
            + Mth.floor(acc.getBonusRolls().getFloat(context) * context.getLuck());
        if (expansionRolls > rolls) {
            rolls = expansionRolls;
        }
        if (rolls <= 0) {
            BetterLuckLootModifier.POOL_STATS.get()[3]++;
            ci.cancel();
            return true;
        }

        long tLoop = System.nanoTime();
        if (count == 1) {
            LootPoolEntry e = passing[0];
            for (int i = 0; i < rolls; i++) {
                betterlucklootr$createAndCache(e, decorated, context);
            }
        } else {
            for (int i = 0; i < rolls; i++) {
                int r = context.getRandom().nextInt(sum);
                int lo = 0, hi = count;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (prefix[mid] <= r) lo = mid + 1;
                    else hi = mid;
                }
                LootPoolEntry e = passing[lo];
                betterlucklootr$createAndCache(e, decorated, context);
            }
        }
        long tEnd = System.nanoTime();

        long dtCache = (tCache - t0) / 1_000_000;
        long dtExp = (tExp - tCache) / 1_000_000;
        long dtLoop = (tEnd - tLoop) / 1_000_000;
        long dtTotal = (tEnd - t0) / 1_000_000;
        if (dtTotal > 5) {
            StringBuilder sb = new StringBuilder();
            LootPoolEntryContainer[] raw = acc.getEntries();
            for (int i = 0; i < Math.min(raw.length, 5); i++) {
                if (i > 0) sb.append(", ");
                sb.append(raw[i].getClass().getSimpleName());
                if (raw[i] instanceof LootTableReference lt) {
                    LootTableReferenceAccessor lta = (LootTableReferenceAccessor) lt;
                    sb.append("(").append(lta.getName()).append(")");
                }
            }
            if (raw.length > 5) sb.append("...+" + (raw.length - 5));
            LOGGER.debug("[BLL-TIMING] addRandomItems(cond) total={}ms cache={}ms{} exp={}ms loop={}ms rolls={} entries={} rawTypes=[{}]",
                dtTotal, dtCache, cacheHit ? "(hit)" : "(miss)", dtExp, dtLoop, rolls, count, sb.toString());
        }

        ci.cancel();
        return true;
    }

    @Unique
    private static int betterlucklootr$calcExpansionRolls(LootPoolAccessor acc, LootContext context) {
        LootPoolEntryContainer[] containers = acc.getEntries();
        int expansionRolls = 0;
        for (LootPoolEntryContainer c : containers) {
            if (c instanceof LootTableReference) {
                LootTableReferenceAccessor ltAcc = (LootTableReferenceAccessor) c;
                LootTable subTable = context.getResolver().getLootTable(ltAcc.getName());
                List<LootPool> subPools = ((LootTableAccessor) subTable).getPools();
                for (LootPool subPool : subPools) {
                    LootPoolAccessor subAcc = (LootPoolAccessor) subPool;
                    LootPoolEntryContainer[] subEntries = subAcc.getEntries();
                    boolean hasEntries = false;
                    for (LootPoolEntryContainer se : subEntries) {
                        if (se instanceof LootPoolSingletonContainer) { hasEntries = true; break; }
                    }
                    if (hasEntries) {
                        expansionRolls += subAcc.getRolls().getInt(context);
                    }
                }
            }
        }
        return expansionRolls;
    }

    @Unique
    private PoolPickCache.CondCache betterlucklootr$buildCondCache(LootPoolAccessor acc, LootContext context) {
        LootPoolEntryContainer[] containers = acc.getEntries();
        int n = containers.length;
        if (n == 0) return new PoolPickCache.CondCache(new LootPoolEntry[0], new int[0]);

        ArrayList<LootPoolEntry> passingList = new ArrayList<>(n * 4);
        ArrayList<Integer> weightsList = new ArrayList<>(n * 4);

        for (LootPoolEntryContainer c : containers) {
            if (!(c instanceof LootPoolSingletonContainer)) return null;
            if (c instanceof TagEntry tagEntry) {
                LootPoolEntryContainerAccessor cacc = (LootPoolEntryContainerAccessor) c;
                if (cacc.getConditions().length > 0) return null;
                LootPoolSingletonContainerAccessor fsacc = (LootPoolSingletonContainerAccessor) c;
                LootItemFunction[] funcs = fsacc.getFunctions();
                for (LootItemFunction f : funcs) {
                    if (f instanceof ExplorationMapFunction) return null;
                }
                if (funcs.length > 0) return null;
                TagEntryAccessor tagAcc = (TagEntryAccessor) tagEntry;
                TagKey<Item> tag = tagAcc.getTag();
                var optTag = BuiltInRegistries.ITEM.getTag(tag);
                if (optTag.isEmpty()) return null;
                int weight = fsacc.getWeight();
                for (var holder : optTag.get()) {
                    Item item = holder.value();
                    if (BLModConfig.SERVER.isGlobalBlacklisted(item)) continue;
                    LootPoolEntry entry = PoolPickCache.createItemEntry(item);
                    if (entry == null) return null;
                    passingList.add(entry);
                    weightsList.add(Math.max(1, weight));
                }
                continue;
            }
            if (c instanceof LootItem li && BLModConfig.SERVER.isGlobalBlacklisted(((LootItemAccessor)li).getItem())) continue;
            LootPoolSingletonContainerAccessor sacc = (LootPoolSingletonContainerAccessor) c;
            boolean skip = false;
            for (LootItemFunction f : sacc.getFunctions()) {
                if (f instanceof ExplorationMapFunction) { skip = true; break; }
            }
            if (skip) {
                String itemName = c instanceof LootItem li ? String.valueOf(BuiltInRegistries.ITEM.getKey(((LootItemAccessor)li).getItem())) : "?";
                LOGGER.debug("[BLL-FILTER] buildCondCache: skip ExplorationMapFunction item={}", itemName);
                continue;
            }

            if (c instanceof LootTableReference) {
                LootTableReferenceAccessor ltAcc = (LootTableReferenceAccessor) c;
                LootTable subTable = context.getResolver().getLootTable(ltAcc.getName());
                for (LootPool subPool : ((LootTableAccessor) subTable).getPools()) {
                    LootPoolAccessor subAcc = (LootPoolAccessor) subPool;
                    if (subAcc.getConditions().length > 0) return null;
                    for (LootPoolEntryContainer se : subAcc.getEntries()) {
                        if (!(se instanceof LootPoolSingletonContainer)) return null;
                        if (se instanceof TagEntry seTag) {
                            LootPoolEntryContainerAccessor seCacc = (LootPoolEntryContainerAccessor) se;
                            if (seCacc.getConditions().length > 0) return null;
                            LootPoolSingletonContainerAccessor seSacc = (LootPoolSingletonContainerAccessor) se;
                            LootItemFunction[] seFuncs = seSacc.getFunctions();
                            boolean hasExp = false;
                            for (LootItemFunction f : seFuncs) {
                                if (f instanceof ExplorationMapFunction) { hasExp = true; break; }
                            }
                            if (hasExp || seFuncs.length > 0) return null;
                            TagEntryAccessor seTagAcc = (TagEntryAccessor) seTag;
                            var seOptTag = BuiltInRegistries.ITEM.getTag(seTagAcc.getTag());
                            if (seOptTag.isEmpty()) return null;
                            int seWeight = seSacc.getWeight();
                            for (var holder : seOptTag.get()) {
                                Item item = holder.value();
                                if (BLModConfig.SERVER.isGlobalBlacklisted(item)) continue;
                                LootPoolEntry entry = PoolPickCache.createItemEntry(item);
                                if (entry == null) return null;
                                passingList.add(entry);
                                weightsList.add(Math.max(1, seWeight));
                            }
                            continue;
                        }
                        LootPoolEntryContainerAccessor seAcc = (LootPoolEntryContainerAccessor) se;
                        if (seAcc.getConditions().length > 0) continue;
                        if (se instanceof LootItem li && BLModConfig.SERVER.isGlobalBlacklisted(((LootItemAccessor)li).getItem())) continue;
                        LootPoolSingletonContainerAccessor seSacc = (LootPoolSingletonContainerAccessor) se;
                        boolean skipSe = false;
                        for (LootItemFunction f : seSacc.getFunctions()) {
                            if (f instanceof ExplorationMapFunction) { skipSe = true; break; }
                        }
                        if (skipSe) {
                            String seItemName = se instanceof LootItem li ? String.valueOf(BuiltInRegistries.ITEM.getKey(((LootItemAccessor)li).getItem())) : "?";
                            LOGGER.debug("[BLL-FILTER] buildCondCache(LTR): skip ExplorationMapFunction item={}", seItemName);
                            continue;
                        }
                        passingList.add(seSacc.getEntry());
                        weightsList.add(Math.max(1, seSacc.getWeight()));
                    }
                }
                continue;
            }

            passingList.add(sacc.getEntry());
            weightsList.add(sacc.getWeight());
        }

        int count = passingList.size();
        if (count == 0) return new PoolPickCache.CondCache(new LootPoolEntry[0], new int[0]);

        LootPoolEntry[] passing = passingList.toArray(new LootPoolEntry[0]);
        int[] weights = new int[count];
        for (int i = 0; i < count; i++) {
            weights[i] = weightsList.get(i);
        }

        return new PoolPickCache.CondCache(passing, weights);
    }

    @Inject(method = "addRandomItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void betterlucklootr$fastPick(Consumer<ItemStack> consumer, LootContext context, CallbackInfo ci) {
        int[] stats = BetterLuckLootModifier.POOL_STATS.get();
        PoolPickCache cache = betterlucklootr$getCache();
        if (cache.ineligible) {
            stats[1]++;
            return;
        }

        if (cache.qualityAllZero) {
            betterlucklootr$pickFromCached(consumer, context, cache);
        } else {
            betterlucklootr$pickWithLuck(consumer, context, cache);
        }
        stats[0]++;
        ci.cancel();
    }

    @Unique
    private static void betterlucklootr$pickFromCached(Consumer<ItemStack> consumer, LootContext context, PoolPickCache cache) {
        int total = cache.cachedTotal;
        if (total <= 0) return;
        int[] activeIdx = cache.cachedActiveIdx;
        int[] prefix = cache.cachedPrefix;
        if (activeIdx.length == 1) {
            betterlucklootr$createAndCache(cache.flatEntries[activeIdx[0]], consumer, context);
            return;
        }
        int r = context.getRandom().nextInt(total);
        int lo = 0, hi = activeIdx.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (prefix[mid] <= r) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        betterlucklootr$createAndCache(cache.flatEntries[activeIdx[lo]], consumer, context);
    }

    @Unique
    private static void betterlucklootr$createAndCache(LootPoolEntry entry, Consumer<ItemStack> consumer, LootContext context) {
        long t = System.nanoTime();
        entry.createItemStack(consumer, context);
        long dt = (System.nanoTime() - t) / 1_000_000;
        if (dt > 50) LOGGER.debug("[BLL-TIMING] createItemStack slow {}ms entryClass={}", dt, entry.getClass().getName());
    }

    @Unique
    private static void betterlucklootr$pickWithLuck(Consumer<ItemStack> consumer, LootContext context, PoolPickCache cache) {
        LootPoolEntry[] entries = cache.flatEntries;
        int n = entries.length;
        float luck = context.getLuck();
        int[] activeIdx = new int[n];
        int[] prefix = new int[n];
        int count = 0;
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int w = entries[i].getWeight(luck);
            if (w > 0) {
                activeIdx[count] = i;
                sum += w;
                prefix[count] = sum;
                count++;
            }
        }
        if (count == 0) return;
        if (count == 1) {
            betterlucklootr$createAndCache(entries[activeIdx[0]], consumer, context);
            return;
        }
        int r = context.getRandom().nextInt(sum);
        int lo = 0, hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (prefix[mid] <= r) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        betterlucklootr$createAndCache(entries[activeIdx[lo]], consumer, context);
    }
}
