package com.azukaar.betterlucklootr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;

import com.azukaar.betterlucklootr.mixin.LootItemAccessor;
import com.azukaar.betterlucklootr.mixin.LootPoolAccessor;

import com.azukaar.betterlucklootr.mixin.LootPoolSingletonContainerAccessor;
import com.azukaar.betterlucklootr.mixin.LootTableAccessor;
import com.azukaar.betterlucklootr.mixin.TagEntryAccessor;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class BetterLuckLootModifier extends LootModifier {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Codec<BetterLuckLootModifier> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        LootModifier.LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter(m -> m.conditions)
    ).apply(inst, BetterLuckLootModifier::new));

    public BetterLuckLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    public static final ThreadLocal<int[]> POOL_STATS = ThreadLocal.withInitial(() -> new int[4]);
    private static final ThreadLocal<Integer> RECURSION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_RECURSION = 3;
    private static final ThreadLocal<Boolean> IN_BONUS = ThreadLocal.withInitial(() -> false);

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }

    private static int safeGet(ForgeConfigSpec.IntValue spec, int fallback) {
        try { return spec.get(); }
        catch (Exception e) { return fallback; }
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        long tTotal = System.nanoTime();
        int depth = RECURSION_DEPTH.get();
        if (depth >= MAX_RECURSION) {
            return generatedLoot;
        }
        RECURSION_DEPTH.set(depth + 1);

        if (IN_BONUS.get()) {
            RECURSION_DEPTH.set(depth);
            return generatedLoot;
        }

        try {
            float luckLevel = getLuckLevel(context);
            ResourceLocation lootTableLoc = context.getQueriedLootTableId();

            if (luckLevel <= 0 || lootTableLoc == null) {
                return generatedLoot;
            }

            long tFilter = System.nanoTime();
            var blIter = generatedLoot.iterator();
            while (blIter.hasNext()) {
                ItemStack s = blIter.next();
                if (s != null && !s.isEmpty() && BLModConfig.SERVER.isGlobalBlacklisted(s)) {
                    blIter.remove();
                }
            }

            Object2IntOpenHashMap<ResourceLocation> origDedupTracker = new Object2IntOpenHashMap<>(32);
            origDedupTracker.defaultReturnValue(0);
            int keepCount = BLModConfig.Server.no_stack_count.get();
            var dedupIter = generatedLoot.iterator();
            while (dedupIter.hasNext()) {
                ItemStack s = dedupIter.next();
                if (s == null || s.isEmpty()) continue;
                int cfgLimit = BLModConfig.SERVER.getDedupLimit(s.getItem());
                boolean limited = !s.isStackable() || cfgLimit > 0;
                if (!limited) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
                if (key == null) continue;
                int effLimit = cfgLimit > 0 ? cfgLimit : keepCount;
                int cur = origDedupTracker.getInt(key);
                int remaining = effLimit - cur;
                if (remaining <= 0) {
                    dedupIter.remove();
                    continue;
                }
                int toTake = Math.min(s.getCount(), remaining);
                origDedupTracker.put(key, cur + toTake);
                if (toTake < s.getCount()) {
                    s.setCount(toTake);
                }
            }
            long tFilterDone = System.nanoTime();

            int pulls = calculatePulls(luckLevel);
            if (pulls <= 0) {
                return generatedLoot;
            }

            long tCompat = System.nanoTime();
            int availableSlots = -1;
            if (BLModConfig.compatibilityMode()) {
                BlockEntity be = context.getParamOrNull(LootContextParams.BLOCK_ENTITY);
                if (be == null) {
                    Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
                    if (origin != null)
                        be = context.getLevel().getBlockEntity(BlockPos.containing(origin));
                }
                Entity entity = context.getParamOrNull(LootContextParams.THIS_ENTITY);
                int[] lbcSize = callLBCGetOrCreateSize(be, entity);
                if (lbcSize != null) {
                    availableSlots = lbcSize[0] * lbcSize[1];
                } else {
                    Container preTarget = getTargetContainer(context);
                    if (preTarget != null) {
                        int size = preTarget.getContainerSize();
                        int available = 0;
                        for (int i = 0; i < size; i++) {
                            if (preTarget.getItem(i).isEmpty()) available++;
                        }
                        availableSlots = Math.max(1, available);
                    } else {
                        availableSlots = BLModConfig.compatibilityDefaultSlots();
                    }
                }
            }
            long tCompatDone = System.nanoTime();

            int slotsForBonus = availableSlots > 0 ? Math.max(0, availableSlots - generatedLoot.size()) : -1;

            long tBonus = System.nanoTime();
            List<ItemStack> bonus = generateBonus(lootTableLoc, context, pulls, generatedLoot, slotsForBonus);
            long tBonusDone = System.nanoTime();

            List<ItemStack> merged = mergeItemLists(generatedLoot, bonus);
            if (availableSlots > 0 && merged.size() > availableSlots) {
                merged = new ArrayList<>(merged.subList(0, availableSlots));
            }
            long tTotalDone = System.nanoTime();

            LOGGER.debug("[BLL-TIMING] doApply total={}ms filter={}ms compat={}ms bonus={}ms slots={} lootSize={} bonusSize={} pulls={}",
                (tTotalDone - tTotal) / 1_000_000,
                (tFilterDone - tFilter) / 1_000_000,
                (tCompatDone - tCompat) / 1_000_000,
                (tBonusDone - tBonus) / 1_000_000,
                availableSlots, generatedLoot.size(), bonus.size(), pulls);

            return new ObjectArrayList<>(merged);
        } catch (Exception e) {
            return generatedLoot;
        } finally {
            RECURSION_DEPTH.set(depth);
            PoolPickCache.invalidateCondCaches();
        }
    }

    private static float getLuckLevel(LootContext context) {
        if (context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player) {
            float playerLuck = player.getLuck();
            if (playerLuck > 0) return playerLuck;
        }
        float ctxLuck = context.getLuck();
        return Math.max(0, ctxLuck);
    }

    private int calculatePulls(float luckLevel) {
        int pulls = 0;
        float remaining = luckLevel;
        int prevLimit = 0;

        for (BLModConfig.CurveSegment seg : BLModConfig.SERVER.getCurve()) {
            int limit = seg.limit();
            int divisor = seg.divisor();
            float probability = seg.probability();

            float segRange = limit - prevLimit;
            float luckInSeg = Math.min(remaining, segRange);
            int segPulls = divisor <= 0 ? 0 : (int) Math.floor(luckInSeg / divisor);

            int segResult = 0;
            if (probability >= 1.0f) {
                segResult = segPulls;
            } else if (segPulls > 0) {
                float expected = segPulls * probability;
                int base = (int) expected;
                segResult = base + (ThreadLocalRandom.current().nextFloat() < expected - base ? 1 : 0);
            }
            pulls += segResult;

            remaining -= luckInSeg;
            prevLimit = limit;
            if (remaining <= 0) break;
        }
        return pulls;
    }

    private void doRawRoll(List<LootPool> pools, Consumer<ItemStack> decorated, LootContext ctx) {
        long t = System.nanoTime();
        for (int pi = 0; pi < pools.size(); pi++) {
            pools.get(pi).addRandomItems(decorated, ctx);
        }
        long dt = (System.nanoTime() - t) / 1_000_000;
        if (dt > 5) {
            LOGGER.debug("[BLL-TIMING] doRawRoll {}ms pools={}", dt, pools.size());
        }
    }

    private List<ItemStack> generateBonus(ResourceLocation lootTableLoc, LootContext context, int maxPulls, List<ItemStack> originalLoot, int availableSlots) {
        long tGen = System.nanoTime();
        ServerLevel level = (ServerLevel) context.getLevel();
        LootTable table = level.getServer().getLootData().getLootTable(lootTableLoc);
        LootParams params = buildBonusParams(context);

        int totalBonus = Math.min(maxPulls, safeGet(BLModConfig.Server.max_bonus_rolls, 500));
        if (availableSlots > 0 && totalBonus > availableSlots) {
            totalBonus = availableSlots;
        }
        int fullRolls = Math.min(totalBonus, safeGet(BLModConfig.Server.full_rolls, 10));
        int poolPicks = totalBonus - fullRolls;
        if (context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player) {
            var attr = player.getAttribute(BetterLuckLootr.LOOT_RICHNESS.get());
            if (attr != null) poolPicks += (int) attr.getValue();
        }
        if (availableSlots > 0) {
            if (fullRolls > availableSlots) fullRolls = availableSlots;
            if (poolPicks > availableSlots) poolPicks = availableSlots;
        }
        int keepCount = safeGet(BLModConfig.Server.no_stack_count, 1);

        IN_BONUS.set(true);
        try {
            List<ItemStack> result = generateBonusInner(lootTableLoc, table, params, context, fullRolls, poolPicks, keepCount, originalLoot, availableSlots);
            long tGenDone = System.nanoTime();
            LOGGER.debug("[BLL-TIMING] generateBonus total={}ms innerOnly={}ms",
                (tGenDone - tGen) / 1_000_000,
                (tGenDone - tGen) / 1_000_000);
            return result;
        } finally {
            IN_BONUS.set(false);
        }
    }

    private List<ItemStack> generateBonusInner(ResourceLocation lootTableLoc, LootTable table, LootParams params, LootContext context,
            int fullRolls, int poolPicks, int keepCount, List<ItemStack> originalLoot, int availableSlots) {
        long tInner = System.nanoTime();
        Object2IntOpenHashMap<ResourceLocation> origDedup = new Object2IntOpenHashMap<>(32);
        origDedup.defaultReturnValue(0);
        for (ItemStack s : originalLoot) {
            if (s == null || s.isEmpty()) continue;
            int cfgLimit = BLModConfig.SERVER.getDedupLimit(s.getItem());
            boolean limited = !s.isStackable() || cfgLimit > 0;
            if (!limited) continue;
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (key == null) continue;
            int effLimit = cfgLimit > 0 ? cfgLimit : keepCount;
            origDedup.put(key, Math.min(origDedup.getInt(key) + s.getCount(), effLimit));
        }

        Object2IntOpenHashMap<ResourceLocation> counts = new Object2IntOpenHashMap<>(64);
        Object2ObjectOpenHashMap<ResourceLocation, ItemStack> refs = new Object2ObjectOpenHashMap<>(64);
        counts.defaultReturnValue(0);
        LootTableAccessor tableAcc = (LootTableAccessor) table;
        List<LootPool> pools = tableAcc.getPools();
        long tWeights = System.nanoTime();
        Object2IntOpenHashMap<ResourceLocation> entryWeights = buildEntryWeights(pools);
        long tWeightsDone = System.nanoTime();
        LootContext rollCtx = new LootContext.Builder(params)
            .withOptionalRandomSeed(ThreadLocalRandom.current().nextLong())
            .create(lootTableLoc);
        boolean mode1 = BLModConfig.dedupCompensationMode() == 1;
        int rerollMax = BLModConfig.dedupRerollMax();
        int[] missedPicks = new int[1];
        int[] nbtDropped = new int[1];
        boolean[] addedThisRoll = new boolean[1];
        Consumer<ItemStack> rollConsumer = LootItemFunction.decorate(
            tableAcc.getCompositeFunction(),
            s -> {
                if (s == null || s.isEmpty()) return;
                if (BLModConfig.SERVER.isBonusBlacklisted(s.getItem())) return;
                if (BLModConfig.SERVER.isGlobalBlacklisted(s.getItem())) return;
                if (BLModConfig.SERVER.isBonusBlacklisted(s) || BLModConfig.SERVER.isGlobalBlacklisted(s)) {
                    nbtDropped[0]++;
                    return;
                }
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
                if (key == null) return;
                int cfgLimit = BLModConfig.SERVER.getDedupLimit(s.getItem());
                boolean limited = !s.isStackable() || cfgLimit > 0;
                int effLimit = limited ? (cfgLimit > 0 ? cfgLimit : keepCount) : Integer.MAX_VALUE;
                int cur = counts.getInt(key);
                int orig = origDedup.getInt(key);
                int remaining = limited ? Math.max(0, effLimit - orig - cur) : Integer.MAX_VALUE;
                if (remaining <= 0) {
                    if (!mode1) missedPicks[0]++;
                    return;
                }
                addedThisRoll[0] = true;
                int toAdd = Math.min(s.getCount(), remaining);
                counts.put(key, cur + toAdd);
                if (!refs.containsKey(key)) refs.put(key, s);
            },
            rollCtx);

        if (availableSlots > 0 && fullRolls > availableSlots) {
            fullRolls = availableSlots;
        }

        int skippedRolls = 0;
        int actualRolls = 0;
        long tFullRolls = System.nanoTime();
        for (int i = 0; i < fullRolls; i++) {
            if (availableSlots > 0 && estimatedStacks(counts, refs) >= availableSlots) {
                break;
            }
            int retries = 0;

            do {
                addedThisRoll[0] = false;
                doRawRoll(pools, rollConsumer, rollCtx);
                retries++;
            } while (mode1 && !addedThisRoll[0] && retries < rerollMax);

            actualRolls++;
            long rollMs = (System.nanoTime() - tFullRolls) / 1_000_000;

            if (rollMs > 100 && i < fullRolls - 1) {
                skippedRolls = fullRolls - (i + 1);
                poolPicks += skippedRolls;
                break;
            }
        }
        if (!mode1) poolPicks += missedPicks[0];
        int nbtComp = nbtDropped[0];
        for (int c = 0; c < nbtComp; c++) {
            if (availableSlots > 0 && estimatedStacks(counts, refs) >= availableSlots) break;
            int retries = 0;
            do {
                addedThisRoll[0] = false;
                doRawRoll(pools, rollConsumer, rollCtx);
                retries++;
            } while (mode1 && !addedThisRoll[0] && retries < rerollMax);
            actualRolls++;
        }
        long tFullRollsDone = System.nanoTime();

        long tPoolPicks = System.nanoTime();
        int poolPickRounds = 0;
        if (poolPicks > 0 && !refs.isEmpty()) {
            int totalW = 0;
            for (var entry : refs.object2ObjectEntrySet()) {
                int c = counts.getInt(entry.getKey());
                if (c > 0) totalW += c;
            }

            if (totalW > 0) {
                boolean carbon = BLModConfig.carbonMode();
                double carbonMult = BLModConfig.carbonMultiplier();
                int addAmt = BLModConfig.step3PickAmount();
                boolean weighted = BLModConfig.step3Mode() == 1;
                ThreadLocalRandom rng = ThreadLocalRandom.current();

                ArrayList<ResourceLocation> typeList = new ArrayList<>(refs.size());
                for (var entry : refs.object2ObjectEntrySet()) {
                    int c = counts.getInt(entry.getKey());
                    if (c > 0) {
                        typeList.add(entry.getKey());
                    }
                }
                int numTypes = typeList.size();
                if (numTypes > 0) {
                    int minT = carbon ? BLModConfig.carbonTypesPerPickMin() : BLModConfig.step3MinPicks();
                    int maxT = carbon ? BLModConfig.carbonTypesPerPickMax() : BLModConfig.step3MaxPicks();
                    if (maxT < minT) maxT = minT;
                    for (int i = 0; i < poolPicks; i++) {
                        if (availableSlots > 0 && estimatedStacks(counts, refs) >= availableSlots) {
                            break;
                        }
                        poolPickRounds++;
                        int toPick = Math.min(minT + rng.nextInt(maxT - minT + 1), numTypes);
                        if (weighted) {
                            int[] effWeights = new int[numTypes];
                            int wSum = 0;
                            for (int j = 0; j < numTypes; j++) {
                                ResourceLocation k = typeList.get(j);
                                int w = entryWeights.containsKey(k) ? entryWeights.getInt(k) : counts.getInt(k);
                                if (availableSlots > 0 && w > 0) {
                                    int c = counts.getInt(k);
                                    ItemStack ref = refs.get(k);
                                    int ms = ref != null ? ref.getMaxStackSize() : 64;
                                    int curStacks = (c + ms - 1) / ms;
                                    double slotShare = (double) curStacks / availableSlots;
                                    w = Math.max(1, (int) (w * Math.max(0.05, 1.0 - slotShare)));
                                }
                                effWeights[j] = w;
                                wSum += w;
                            }
                            boolean[] picked = new boolean[numTypes];
                            for (int t = 0; t < toPick; t++) {
                                if (wSum <= 0) break;
                                int target = rng.nextInt(wSum);
                                int cum = 0;
                                for (int j = 0; j < numTypes; j++) {
                                    if (picked[j]) continue;
                                    cum += effWeights[j];
                                    if (target < cum) {
                                        picked[j] = true;
                                        wSum -= effWeights[j];
                                        ResourceLocation key = typeList.get(j);
                                        int c = counts.getInt(key);
                                        ItemStack ref = refs.get(key);
                                        int cfgLimit = BLModConfig.SERVER.getDedupLimit(ref.getItem());
                                        boolean limited = !ref.isStackable() || cfgLimit > 0;
                                        if (limited) {
                                            int effLimit = cfgLimit > 0 ? cfgLimit : keepCount;
                                            int orig = origDedup.getInt(key);
                                            if (c + orig >= effLimit) break;
                                        }
                                        int newCount;
                                        if (carbon) {
                                            newCount = (int) Math.round(c * carbonMult);
                                        } else {
                                            newCount = c + addAmt;
                                        }
                                        if (newCount <= c) break;
                                        if (availableSlots > 0 && wouldOverflow(counts, refs, key, newCount, availableSlots)) {
                                            break;
                                        }
                                        counts.put(key, newCount);
                                        break;
                                    }
                                }
                            }
                        } else {
                            Collections.shuffle(typeList, rng);
                            for (int t = 0; t < toPick; t++) {
                                ResourceLocation key = typeList.get(t);
                                int c = counts.getInt(key);
                                if (c <= 0) continue;
                                ItemStack ref = refs.get(key);
                                int cfgLimit = BLModConfig.SERVER.getDedupLimit(ref.getItem());
                                boolean limited = !ref.isStackable() || cfgLimit > 0;
                                if (limited) {
                                    int effLimit = cfgLimit > 0 ? cfgLimit : keepCount;
                                    int orig = origDedup.getInt(key);
                                    if (c + orig >= effLimit) continue;
                                }
                                int newCount;
                                if (carbon) {
                                    newCount = (int) Math.round(c * carbonMult);
                                } else {
                                    newCount = c + addAmt;
                                }
                                if (newCount <= c) continue;
                                if (availableSlots > 0 && wouldOverflow(counts, refs, key, newCount, availableSlots)) {
                                    continue;
                                }
                                counts.put(key, newCount);
                            }
                        }
                    }
                }
            }
        }
        long tPoolPicksDone = System.nanoTime();

        List<ItemStack> bonus = new ArrayList<>();
        for (var entry : refs.object2ObjectEntrySet()) {
            ResourceLocation key = entry.getKey();
            ItemStack ref = entry.getValue();
            int total = counts.getInt(key);
            if (total <= 0) continue;
            int maxStack = ref.getMaxStackSize();
            while (total > 0) {
                int count = Math.min(total, maxStack);
                ItemStack stack = ref.copy();
                stack.setCount(count);
                bonus.add(stack);
                total -= count;
            }
        }

        if (availableSlots > 0 && bonus.size() > availableSlots) {
            bonus.subList(availableSlots, bonus.size()).clear();
        }

        long tInnerDone = System.nanoTime();

        LOGGER.debug("[BLL-TIMING] generateBonusInner total={}ms weights={}ms fullRolls={}ms rollsDone={} poolPicks={}ms poolPickRounds={} buildItems={}ms types={} nbtDrop={}",
            (tInnerDone - tInner) / 1_000_000,
            (tWeightsDone - tWeights) / 1_000_000,
            (tFullRollsDone - tFullRolls) / 1_000_000,
            actualRolls,
            (tPoolPicksDone - tPoolPicks) / 1_000_000,
            poolPickRounds,
            (tInnerDone - tPoolPicksDone) / 1_000_000,
            refs.size(),
            nbtDropped[0]);

        return mergeStackList(bonus);
    }

    private LootParams buildBonusParams(LootContext context) {
        LootParams.Builder builder = new LootParams.Builder((ServerLevel) context.getLevel())
            .withLuck(0.0f);

        copyParam(context, builder, LootContextParams.THIS_ENTITY);
        copyParam(context, builder, LootContextParams.KILLER_ENTITY);
        copyParam(context, builder, LootContextParams.TOOL);
        copyParam(context, builder, LootContextParams.BLOCK_ENTITY);
        copyParam(context, builder, LootContextParams.BLOCK_STATE);
        copyParam(context, builder, LootContextParams.ORIGIN);

        return builder.create(LootContextParamSets.CHEST);
    }

    @SuppressWarnings("unchecked")
    private static <T> void copyParam(LootContext context, LootParams.Builder builder,
                                       net.minecraft.world.level.storage.loot.parameters.LootContextParam<T> param) {
        if (context.hasParam(param)) {
            builder.withParameter(param, context.getParamOrNull(param));
        }
    }

    private static ObjectArrayList<ItemStack> mergeStackList(List<ItemStack> items) {
        return mergeItemLists(items, List.of());
    }

    private static ObjectArrayList<ItemStack> mergeItemLists(List<ItemStack> priority, List<ItemStack> secondary) {
        Object2IntOpenHashMap<ResourceLocation> counts = new Object2IntOpenHashMap<>(64);
        Object2ObjectOpenHashMap<ResourceLocation, ItemStack> refs = new Object2ObjectOpenHashMap<>(64);
        List<ItemStack> unstackable = new ArrayList<>(16);

        for (ItemStack s : priority) accumulate(counts, refs, unstackable, s);
        for (ItemStack s : secondary) accumulate(counts, refs, unstackable, s);

        ObjectArrayList<ItemStack> result = new ObjectArrayList<>(counts.size() + unstackable.size());
        for (var entry : counts.object2IntEntrySet()) {
            ResourceLocation key = entry.getKey();
            int total = entry.getIntValue();
            ItemStack ref = refs.get(key);
            if (ref == null || total <= 0) continue;
            int maxStack = ref.getMaxStackSize();

            while (total > 0) {
                int count = Math.min(total, maxStack);
                ItemStack stack = ref.copy();
                stack.setCount(count);
                result.add(stack);

                total -= count;
            }
        }
        result.addAll(unstackable);

        return result;
    }

    private static void accumulate(Object2IntOpenHashMap<ResourceLocation> counts,
                                    Object2ObjectOpenHashMap<ResourceLocation, ItemStack> refs,
                                    List<ItemStack> unstackable, ItemStack s) {
        if (s == null || s.isEmpty()) return;
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(s.getItem());
        if (key == null || !s.isStackable() || s.hasTag()) {
            unstackable.add(s);
            return;
        }
        if (!refs.containsKey(key)) refs.put(key, s);
        counts.addTo(key, s.getCount());
    }

    private static int[] callLBCGetOrCreateSize(BlockEntity be, Entity entity) {
        try {
            Class<?> lbc = Class.forName("com.lootrbigchest.LootrBigChest");
            java.lang.reflect.Method method = lbc.getMethod("getOrCreateSize", BlockEntity.class, Entity.class);
            return (int[]) method.invoke(null, be, entity);
        } catch (Exception ignored) {
            return readLBCSizeDirect(be, entity);
        }
    }

    private static int[] readLBCSizeDirect(BlockEntity be, Entity entity) {
        try {
            CompoundTag data = null;
            if (be != null) {
                data = be.getPersistentData();
            } else if (entity != null) {
                data = entity.getPersistentData();
            }
            if (data != null && data.contains("LBCRows")) {
                return new int[]{data.getInt("LBCRows"), data.getInt("LBCCols")};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Container getTargetContainer(LootContext context) {
        BlockEntity be = context.getParamOrNull(LootContextParams.BLOCK_ENTITY);
        if (be instanceof Container c) return c;
        Entity entity = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        if (entity instanceof Container c) return c;
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin != null) {
            BlockPos pos = BlockPos.containing(origin);
            BlockEntity originBe = context.getLevel().getBlockEntity(pos);
            if (originBe instanceof Container c) return c;
        }
        return null;
    }

    private static int estimatedStacks(Object2IntOpenHashMap<ResourceLocation> counts,
                                        Object2ObjectOpenHashMap<ResourceLocation, ItemStack> refs) {
        int total = 0;
        for (var entry : refs.object2ObjectEntrySet()) {
            int c = counts.getInt(entry.getKey());
            if (c <= 0) continue;
            int maxStack = entry.getValue().getMaxStackSize();
            total += (c + maxStack - 1) / maxStack;
        }
        return total;
    }

    private static boolean wouldOverflow(Object2IntOpenHashMap<ResourceLocation> counts,
                                          Object2ObjectOpenHashMap<ResourceLocation, ItemStack> refs,
                                          ResourceLocation key, int newCount, int availableSlots) {
        if (availableSlots <= 0) return false;
        int c = counts.getInt(key);
        int maxStack = refs.get(key).getMaxStackSize();
        int oldStacks = (c + maxStack - 1) / maxStack;
        int newStacks = (newCount + maxStack - 1) / maxStack;
        return estimatedStacks(counts, refs) - oldStacks + newStacks > availableSlots;
    }

    private static Object2IntOpenHashMap<ResourceLocation> buildEntryWeights(List<LootPool> pools) {
        Object2IntOpenHashMap<ResourceLocation> weights = new Object2IntOpenHashMap<>();
        weights.defaultReturnValue(0);
        for (LootPool pool : pools) {
            LootPoolEntryContainer[] entries = ((LootPoolAccessor) pool).getEntries();
            for (LootPoolEntryContainer container : entries) {
                if (!(container instanceof LootPoolSingletonContainer singleton)) continue;
                int w = ((LootPoolSingletonContainerAccessor) singleton).getWeight();
                if (w <= 0) continue;
                if (container instanceof LootItem lootItem) {
                    Item item = ((LootItemAccessor) lootItem).getItem();
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                    if (key != null) weights.addTo(key, w);
                } else if (container instanceof TagEntry tagEntry) {
                    TagKey<Item> tag = ((TagEntryAccessor) tagEntry).getTag();
                    var optTag = BuiltInRegistries.ITEM.getTag(tag);
                    if (optTag.isPresent()) {
                        for (var holder : optTag.get()) {
                            ResourceLocation key = BuiltInRegistries.ITEM.getKey(holder.value());
                            if (key != null) weights.addTo(key, w);
                        }
                    }
                }
            }
        }
        return weights;
    }

}
