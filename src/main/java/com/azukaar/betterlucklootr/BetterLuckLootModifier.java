package com.azukaar.betterlucklootr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import com.azukaar.betterlucklootr.mixin.LootItemAccessor;
import com.azukaar.betterlucklootr.mixin.LootPoolAccessor;
import com.azukaar.betterlucklootr.mixin.LootPoolSingletonContainerAccessor;
import com.azukaar.betterlucklootr.mixin.LootTableAccessor;
import com.azukaar.betterlucklootr.mixin.TagEntryAccessor;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import org.slf4j.Logger;

public class BetterLuckLootModifier extends LootModifier {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<Integer> RECURSION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final int MAX_RECURSION = 3;
    private static final int DIAGNOSTIC_MAX_ENTRIES = 256;
    private static final int DIAGNOSTIC_MAX_VARIANTS = 32;
    private static final int DIAGNOSTIC_MAX_EVENTS = 4096;
    private static final LootDiagnosticGate DIAGNOSTIC_GATE = new LootDiagnosticGate();

    private static final AtomicLong NEXT_ERROR_LOG = new AtomicLong();
    public static final Codec<BetterLuckLootModifier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        LootModifier.LOOT_CONDITIONS_CODEC.fieldOf("conditions").forGetter(modifier -> modifier.conditions)
    ).apply(instance, BetterLuckLootModifier::new));

    public BetterLuckLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    public static Collection<IGlobalLootModifier> prioritizeModifiers(Collection<IGlobalLootModifier> source) {
        boolean otherSeen = false;
        boolean needsOrdering = false;
        for (IGlobalLootModifier modifier : source) {
            if (modifier instanceof BetterLuckLootModifier) {
                if (otherSeen) {
                    needsOrdering = true;
                    break;
                }
            } else {
                otherSeen = true;
            }
        }
        if (!needsOrdering) return source;
        List<IGlobalLootModifier> ordered = new ArrayList<>(source.size());
        for (IGlobalLootModifier modifier : source) {
            if (modifier instanceof BetterLuckLootModifier) ordered.add(modifier);
        }
        for (IGlobalLootModifier modifier : source) {
            if (!(modifier instanceof BetterLuckLootModifier)) ordered.add(modifier);
        }
        return List.copyOf(ordered);
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC;
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        int depth = RECURSION_DEPTH.get();
        if (depth >= MAX_RECURSION) return generatedLoot;
        RECURSION_DEPTH.set(depth + 1);
        boolean timing = LOGGER.isDebugEnabled();
        long started = timing ? System.nanoTime() : 0;
        LootDiagnosticCollector diagnostics = null;
        ObjectArrayList<ItemStack> filteredOriginal = new ObjectArrayList<>(generatedLoot);
        LootAccumulator completed = null;
        try {
            LootConfigSnapshot snapshot = BLModConfig.snapshot();
            ResourceLocation lootTableId = context.getQueriedLootTableId();
            float luck = getLuckLevel(context);
            int richness = 0;
            if (context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player) {
                var attribute = player.getAttribute(BetterLuckLootr.LOOT_RICHNESS.get());
                if (attribute != null) richness = Math.max(0, (int) attribute.getValue());
            }
            LootDiagnosticCollector pendingDiagnostics = lootTableId != null && (luck > 0 || richness > 0)
                    && LOGGER.isDebugEnabled() && DIAGNOSTIC_GATE.isPending()
                ? new LootDiagnosticCollector(DIAGNOSTIC_MAX_ENTRIES, DIAGNOSTIC_MAX_VARIANTS, DIAGNOSTIC_MAX_EVENTS)
                : null;
            int inputStacks = generatedLoot.size();
            long inputCount = pendingDiagnostics == null ? 0 : totalCount(generatedLoot);
            filterOriginal(filteredOriginal, snapshot, pendingDiagnostics);
            if (lootTableId == null || luck <= 0 && richness <= 0) return filteredOriginal;
            if (exceedsMaterializationLimit(filteredOriginal)) return filteredOriginal;
            LootRollBudget.Plan plan = LootRollBudget.plan(
                calculatePulls(luck, snapshot), snapshot.fullRolls(), snapshot.maxBonusRolls(), richness);
            if (plan.totalBonusPulls() <= 0 && plan.incrementPulls() <= 0) return filteredOriginal;

            int slotLimit = resolveSlotLimit(context, snapshot);
            LootAccumulator accumulator = new LootAccumulator(-1);
            Object2IntOpenHashMap<ResourceLocation> itemTotals = new Object2IntOpenHashMap<>();
            itemTotals.defaultReturnValue(0);
            for (ItemStack stack : filteredOriginal) {
                addStack(accumulator, itemTotals, stack, snapshot, pendingDiagnostics);
            }
            accumulator.restrictSlots(slotLimit);
            accumulator.captureOriginal(context);
            completed = accumulator;
            ServerLevel level = (ServerLevel) context.getLevel();
            LootTable table = level.getServer().getLootData().getLootTable(lootTableId);
            LootContext rollContext = new LootContext.Builder(context)
                .withOptionalRandomSeed(ThreadLocalRandom.current().nextLong())
                .create(null);
            LootTableAccessor tableAccessor = (LootTableAccessor) table;
            LootDiagnosticCollector activeDiagnostics = pendingDiagnostics != null && DIAGNOSTIC_GATE.tryAcquire()
                ? pendingDiagnostics : null;
            diagnostics = activeDiagnostics;
            if (activeDiagnostics != null) {
                LOGGER.debug("[BLL-DIAG] BEGIN table={} carbon={} multiplier={} fullRolls={} extraFullRolls={} incrementPulls={} slotLimit={} inputStacks={} inputCount={}",
                    lootTableId, snapshot.carbonMode(), snapshot.carbonMultiplier(), snapshot.fullRolls(),
                    plan.extraFullRolls(), plan.incrementPulls(), slotLimit, inputStacks, inputCount);
            }
            boolean[] addedThisRoll = new boolean[1];
            Consumer<ItemStack> consumer = stack -> {
                if (isFilteredBonus(stack, snapshot) || !stack.isItemEnabled(level.enabledFeatures())) {
                    recordDrop(activeDiagnostics, "BONUS_FILTER", stack, stack == null ? 0 : stack.getCount());
                    return;
                }
                if (addStack(accumulator, itemTotals, stack, snapshot, activeDiagnostics)) {
                    addedThisRoll[0] = true;
                }
            };

            int attemptedRolls = 0;
            int missedRolls = 0;
            try (var ignored = BonusRollScope.open(snapshot,
                    () -> !accumulator.isFull(),
                    item -> evaluateCandidate(item, itemTotals, snapshot, activeDiagnostics),
                    rollContext, canPreselect(tableAccessor))) {
                while (LootGenerationPolicy.shouldContinueFullRolls(
                        attemptedRolls, plan.extraFullRolls(), accumulator.estimatedSlots(), slotLimit)
                        && !accumulator.isFull()) {
                    addedThisRoll[0] = false;
                    table.getRandomItemsRaw(rollContext, consumer);
                    attemptedRolls++;
                    if (!addedThisRoll[0]) missedRolls++;
                }
            }

            int incrementPulls = plan.remainingIncrements(attemptedRolls, missedRolls, snapshot.dedupCompensationMode());
            applyIncrementsLazy(accumulator, itemTotals, () -> getEntryWeights(table), incrementPulls, snapshot, activeDiagnostics);

            if (timing) {
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                LOGGER.debug("[BLL-TIMING] total={}ms extraFullRolls={} incrementPulls={} types={} slots={}/{}",
                    elapsedMs, attemptedRolls, incrementPulls, accumulator.typeCount(), accumulator.estimatedSlots(), slotLimit);
            }
            ObjectArrayList<ItemStack> output = accumulator.materialize();
            emitDiagnostics(activeDiagnostics, accumulator, output, attemptedRolls, incrementPulls);
            accumulator.recordOutput(context);
            return output;
        } catch (Exception exception) {
            if (diagnostics != null) {
                LOGGER.debug("[BLL-DIAG] END status=failed table={} exception={}",
                    context.getQueriedLootTableId(), exception.getClass().getName());
            }
            ObjectArrayList<ItemStack> output = filteredOriginal;
            if (completed != null) {
                try {
                    ObjectArrayList<ItemStack> recovered = completed.materialize();
                    completed.recordOutput(context);
                    output = recovered;
                } catch (Exception recoveryException) {
                    if (recoveryException != exception) exception.addSuppressed(recoveryException);
                }
            }
            long now = System.nanoTime();
            long next = NEXT_ERROR_LOG.get();
            if ((next == 0 || now - next >= 0) && NEXT_ERROR_LOG.compareAndSet(next, now + 30_000_000_000L)) {
                LOGGER.error("[BetterLuckLootr] Failed to generate bonus loot for {} (similar errors limited to once per 30s)",
                    context.getQueriedLootTableId(), exception);
            }
            return output;
        } finally {
            if (depth == 0) RECURSION_DEPTH.remove();
            else RECURSION_DEPTH.set(depth);
        }
    }

    private static void filterOriginal(List<ItemStack> generatedLoot, LootConfigSnapshot snapshot,
                                       LootDiagnosticCollector diagnostics) {
        Object2IntOpenHashMap<ResourceLocation> totals = new Object2IntOpenHashMap<>();
        totals.defaultReturnValue(0);
        int size = generatedLoot.size();
        int read = 0;
        int write = 0;
        try {
            while (read < size) {
                ItemStack stack = generatedLoot.get(read);
                if (stack == null || stack.isEmpty()) {
                    read++;
                    continue;
                }
                if (snapshot.isGlobalBlacklisted(stack)) {
                    recordDrop(diagnostics, "GLOBAL_BLACKLIST", stack, stack.getCount());
                    read++;
                    continue;
                }
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemId != null) {
                    int limit = snapshot.limitFor(stack);
                    int remaining = limit == Integer.MAX_VALUE ? stack.getCount() : limit - totals.getInt(itemId);
                    if (remaining <= 0) {
                        recordDrop(diagnostics, "ITEM_LIMIT", stack, stack.getCount());
                        read++;
                        continue;
                    }
                    if (stack.getCount() > remaining) {
                        recordDrop(diagnostics, "ITEM_LIMIT", stack, stack.getCount() - remaining);
                        stack = stack.copy();
                        stack.setCount(remaining);
                    }
                    addTotal(totals, itemId, stack.getCount());
                }
                if (write != read || generatedLoot.get(read) != stack) generatedLoot.set(write, stack);
                write++;
                read++;
            }
        } finally {
            if (write != read) {
                while (read < size) generatedLoot.set(write++, generatedLoot.get(read++));
            } else {
                write = size;
            }
            if (write < size) generatedLoot.subList(write, size).clear();
        }
    }

    private static boolean isFilteredBonus(ItemStack stack, LootConfigSnapshot snapshot) {
        return stack == null || stack.isEmpty() || snapshot.isBonusOutputFiltered(stack);
    }

    private static BonusRollScope.CandidateDecision evaluateCandidate(
            Item item, Object2IntOpenHashMap<ResourceLocation> itemTotals, LootConfigSnapshot snapshot,
            LootDiagnosticCollector diagnostics) {
        if (item == null || snapshot.isStaticallyBlacklisted(item)) {
            recordCandidateDrop(diagnostics, "PRESELECT_BLACKLIST", item);
            return BonusRollScope.CandidateDecision.BLACKLIST;
        }
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId == null) {
            recordCandidateDrop(diagnostics, "PRESELECT_BLACKLIST", item);
            return BonusRollScope.CandidateDecision.BLACKLIST;
        }
        int limit = snapshot.staticLimitFor(item);
        if (limit == Integer.MAX_VALUE || itemTotals.getInt(itemId) < limit) {
            return BonusRollScope.CandidateDecision.ALLOW;
        }
        recordCandidateDrop(diagnostics, "PRESELECT_ITEM_LIMIT", item);
        return BonusRollScope.CandidateDecision.DEDUP_LIMIT;
    }

    private static boolean addStack(LootAccumulator accumulator,
                                    Object2IntOpenHashMap<ResourceLocation> itemTotals,
                                    ItemStack stack,
                                    LootConfigSnapshot snapshot,
                                    LootDiagnosticCollector diagnostics) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null) return false;
        int itemLimit = snapshot.limitFor(stack);
        int currentItemTotal = itemTotals.getInt(itemId);
        int remaining = itemLimit == Integer.MAX_VALUE ? stack.getCount() : itemLimit - currentItemTotal;
        if (remaining <= 0) {
            recordDrop(diagnostics, "ITEM_LIMIT", stack, stack.getCount());
            return false;
        }
        int originalCount = stack.getCount();
        int requested = Math.min(originalCount, remaining);
        StackFingerprint key = accumulator.fingerprint(stack);
        int added = accumulator.offerAccepted(key, stack, requested, Integer.MAX_VALUE,
            itemLimit == Integer.MAX_VALUE ? null : () -> itemLimit - itemTotals.getInt(itemId));
        int permitted = diagnostics == null || itemLimit == Integer.MAX_VALUE ? requested
            : Math.min(requested, Math.max(0, itemLimit - itemTotals.getInt(itemId)));
        if (added > 0) addTotal(itemTotals, itemId, added);
        if (diagnostics != null) {
            if (originalCount > permitted) {
                diagnostics.recordDrop("ITEM_LIMIT", itemId.toString(), originalCount - permitted, key::hashCode);
            }
            if (added > 0) {
                diagnostics.recordCapacityRemainder(itemId.toString(), permitted, added, key::hashCode);
            } else if (permitted > 0) {
                diagnostics.recordDrop(accumulator.isFull() ? "SLOT_LIMIT" : "ACCUMULATOR_REJECTED",
                    itemId.toString(), permitted, key::hashCode);
            }
        }
        return added > 0;
    }

    private static void addTotal(Object2IntOpenHashMap<ResourceLocation> totals, ResourceLocation id, int amount) {
        totals.put(id, (int) Math.min(Integer.MAX_VALUE, (long) totals.getInt(id) + amount));
    }

    private static boolean exceedsMaterializationLimit(List<ItemStack> stacks) {
        long slots = 0;
        for (ItemStack stack : stacks) {
            slots += (stack.getCount() - 1L) / Math.max(1, stack.getMaxStackSize()) + 1;
            if (slots > LootAccumulatorCore.HARD_SLOT_LIMIT) return true;
        }
        return false;
    }

    private static boolean canPreselect(LootTableAccessor table) {
        if (table.getFunctions().length != 0) return false;
        for (LootPool pool : table.getPools()) {
            LootPoolAccessor accessor = (LootPoolAccessor) pool;
            if (accessor.getFunctions().length != 0) return false;
            for (LootPoolEntryContainer entry : accessor.getEntries()) {
                if (entry.getClass() != LootItem.class && entry.getClass() != TagEntry.class) return false;
            }
        }
        return true;
    }

    private static void recordDrop(LootDiagnosticCollector diagnostics, String reason,
                                   ItemStack stack, int count) {
        if (diagnostics == null || stack == null || stack.isEmpty()) return;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        diagnostics.recordDrop(reason, itemId == null ? "<unregistered>" : itemId.toString(),
            count, () -> diagnosticHash(stack));
    }

    private static void recordCandidateDrop(LootDiagnosticCollector diagnostics, String reason, Item item) {
        if (diagnostics == null) return;
        ResourceLocation itemId = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
        diagnostics.recordDrop(reason, itemId == null ? "<unregistered>" : itemId.toString(), 0, 0);
    }

    private static int diagnosticHash(ItemStack stack) {
        return stack.hasTag() ? stack.getTag().hashCode() : 0;
    }

    private static long totalCount(List<ItemStack> stacks) {
        long total = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) total += stack.getCount();
        }
        return total;
    }

    private static void emitDiagnostics(LootDiagnosticCollector diagnostics,
                                        LootAccumulator accumulator,
                                        List<ItemStack> output,
                                        int attemptedRolls,
                                        int incrementPulls) {
        if (diagnostics == null) return;
        accumulator.recordDiagnostics(diagnostics);
        for (String line : diagnostics.renderDropLines()) {
            LOGGER.debug("[BLL-DIAG] {}", line);
        }
        List<LootDiagnosticCollector.ItemSummary> items = diagnostics.finalItems();
        List<String> lines = diagnostics.renderFinalLines();
        for (int index = 0; index < lines.size(); index++) {
            LOGGER.debug("[BLL-DIAG] {}", lines.get(index));
            if (items.get(index).maxStackSize() == 1) {
                LOGGER.debug("[BLL-DIAG] SPECIAL {}", lines.get(index));
            }
        }
        LOGGER.debug("[BLL-DIAG] END status=ok attemptedFullRolls={} incrementPulls={} outputStacks={} outputCount={} outputTypes={} slots={} full={} dropEvents={} droppedCount={} truncatedEntries={} truncatedEvents={}",
            attemptedRolls, incrementPulls, output.size(), totalCount(output), accumulator.typeCount(),
            accumulator.estimatedSlots(), accumulator.isFull(), diagnostics.dropEvents(),
            diagnostics.droppedCount(), diagnostics.truncatedEntries(), diagnostics.truncatedEvents());
    }

    private static void applyIncrements(LootAccumulator accumulator,
                                        Object2IntOpenHashMap<ResourceLocation> itemTotals,
                                        Object2IntOpenHashMap<ResourceLocation> entryWeights,
                                        int rounds,
                                        LootConfigSnapshot snapshot,
                                        LootDiagnosticCollector diagnostics) {
        applyIncrementsLazy(accumulator, itemTotals, () -> entryWeights, rounds, snapshot, diagnostics);
    }

    private static void applyIncrementsLazy(LootAccumulator accumulator,
                                            Object2IntOpenHashMap<ResourceLocation> itemTotals,
                                            Supplier<Object2IntOpenHashMap<ResourceLocation>> weightSource,
                                            int rounds,
                                            LootConfigSnapshot snapshot,
                                            LootDiagnosticCollector diagnostics) {
        if (rounds <= 0 || accumulator.typeCount() == 0) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<StackFingerprint> candidates = accumulator.keys();
        List<StackFingerprint> keys = new ArrayList<>(candidates.size());
        ItemStack[] references = new ItemStack[candidates.size()];
        for (StackFingerprint key : candidates) {
            ItemStack reference = accumulator.reference(key);
            if (isFilteredBonus(reference, snapshot)) continue;
            references[keys.size()] = reference;
            keys.add(key);
        }
        int typeCount = keys.size();
        if (typeCount == 0) return;
        Object2IntOpenHashMap<ResourceLocation> entryWeights = snapshot.step3Mode() == 1 ? weightSource.get() : null;
        ResourceLocation[] itemIds = new ResourceLocation[typeCount];
        int[] weights = snapshot.step3Mode() == 1 ? new int[typeCount] : null;
        int[] order = new int[typeCount];
        int minTypes = snapshot.carbonMode() ? snapshot.carbonTypesPerPickMin() : snapshot.step3MinPicks();
        int maxTypes = snapshot.carbonMode() ? snapshot.carbonTypesPerPickMax() : snapshot.step3MaxPicks();
        maxTypes = Math.max(minTypes, maxTypes);
        int[] swappedWith = new int[Math.min(typeCount, maxTypes)];
        for (int index = 0; index < typeCount; index++) {
            itemIds[index] = BuiltInRegistries.ITEM.getKey(references[index].getItem());
            if (weights != null) weights[index] = itemIds[index] == null || entryWeights == null
                ? 1 : Math.max(1, entryWeights.getInt(itemIds[index]));
            order[index] = index;
        }
        WeightedOrder weighted = weights != null && typeCount >= 128 ? new WeightedOrder(order, weights) : null;
        boolean previousRoundProgressed = true;
        boolean anyGrowableCandidate = true;
        for (int round = 0; LootGenerationPolicy.shouldContinueIncrements(
                round, rounds, typeCount, previousRoundProgressed, anyGrowableCandidate); round++) {
            int pickCount = Math.min(typeCount, minTypes + random.nextInt(maxTypes - minTypes + 1));
            boolean progressed = false;
            int completedPicks = 0;
            for (int pick = 0; pick < pickCount; pick++) {
                int selectedPosition = weighted != null && pick + 1 < typeCount
                    ? weighted.choose(random.nextLong(weighted.total()))
                    : choosePosition(order, pick, typeCount, weights, snapshot.step3Mode(), random);
                swappedWith[pick] = selectedPosition;
                if (weighted == null) swap(order, pick, selectedPosition);
                else weighted.take(pick, selectedPosition);
                completedPicks++;
                int index = order[pick];
                StackFingerprint key = keys.get(index);
                ItemStack reference = references[index];
                if (reference.isEmpty()) continue;
                ResourceLocation itemId = itemIds[index];
                if (itemId == null) continue;
                int itemLimit = snapshot.limitFor(reference);
                int itemRemaining = itemLimit == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : itemLimit - itemTotals.getInt(itemId);
                if (itemRemaining <= 0) {
                    recordDrop(diagnostics, "ITEM_LIMIT", reference, 0);
                    continue;
                }
                int current = accumulator.count(key);
                int next = LootGenerationPolicy.nextCount(current, snapshot.carbonMode(),
                    snapshot.carbonMultiplier(), snapshot.step3PickAmount());
                int wanted = Math.min(itemRemaining, next - current);
                if (wanted <= 0) continue;
                int maxForFingerprint = current > Integer.MAX_VALUE - wanted ? Integer.MAX_VALUE : current + wanted;
                int added = accumulator.increment(key, wanted, maxForFingerprint);
                if (added > 0) {
                    addTotal(itemTotals, itemId, added);
                    progressed = true;
                }
            }
            for (int pick = completedPicks - 1; pick >= 0; pick--) {
                if (weighted == null) swap(order, pick, swappedWith[pick]);
                else weighted.restore(pick, swappedWith[pick]);
            }
            previousRoundProgressed = progressed;
            anyGrowableCandidate = progressed || hasGrowableCandidate(
                keys, references, itemIds, accumulator, itemTotals, snapshot);
        }
    }

    private static boolean hasGrowableCandidate(List<StackFingerprint> keys,
                                                ItemStack[] references,
                                                ResourceLocation[] itemIds,
                                                LootAccumulator accumulator,
                                                Object2IntOpenHashMap<ResourceLocation> itemTotals,
                                                LootConfigSnapshot snapshot) {
        for (int index = 0; index < keys.size(); index++) {
            ItemStack reference = references[index];
            ResourceLocation itemId = itemIds[index];
            if (reference.isEmpty() || itemId == null) continue;
            int itemLimit = snapshot.limitFor(reference);
            if (itemLimit != Integer.MAX_VALUE && itemTotals.getInt(itemId) >= itemLimit) continue;
            int current = accumulator.count(keys.get(index));
            if (current >= Integer.MAX_VALUE) continue;
            int next = LootGenerationPolicy.nextCount(current, snapshot.carbonMode(),
                snapshot.carbonMultiplier(), snapshot.step3PickAmount());
            if (next <= current) continue;
            int maxAllowed = itemLimit == Integer.MAX_VALUE ? Integer.MAX_VALUE
                : (int) Math.min(Integer.MAX_VALUE, (long) current + itemLimit - itemTotals.getInt(itemId));
            if (accumulator.remainingCapacity(keys.get(index), maxAllowed) > 0) return true;
        }
        return false;
    }

    private static int choosePosition(int[] order, int start, int size, int[] weights,
                                      int mode, ThreadLocalRandom random) {
        if (mode != 1 || start + 1 == size) return start + random.nextInt(size - start);
        long totalWeight = 0;
        for (int position = start; position < size; position++) {
            totalWeight += weights[order[position]];
        }
        long target = random.nextLong(totalWeight);
        for (int position = start; position < size; position++) {
            target -= weights[order[position]];
            if (target < 0) return position;
        }
        return size - 1;
    }

    private static void swap(int[] values, int first, int second) {
        if (first == second) return;
        int value = values[first];
        values[first] = values[second];
        values[second] = value;
    }

    static final class WeightedOrder {
        private final int[] order;
        private final int[] weights;
        private final long[] tree;
        private long total;

        WeightedOrder(int[] order, int[] weights) {
            this.order = order;
            this.weights = weights;
            this.tree = new long[order.length + 1];
            for (int index = 1; index < tree.length; index++) {
                long weight = weights[order[index - 1]];
                total += weight;
                tree[index] += weight;
                int parent = index + (index & -index);
                if (parent < tree.length) tree[parent] += tree[index];
            }
        }

        long total() {
            return total;
        }

        int choose(long target) {
            if (target < 0 || target >= total) throw new IllegalArgumentException("Weight target out of range");
            int position = 0;
            for (int step = Integer.highestOneBit(order.length); step != 0; step >>= 1) {
                int next = position + step;
                if (next < tree.length && tree[next] <= target) {
                    position = next;
                    target -= tree[next];
                }
            }
            return position;
        }

        void take(int start, int selected) {
            long firstWeight = weights[order[start]];
            if (selected != start) add(selected, firstWeight - weights[order[selected]]);
            add(start, -firstWeight);
            swap(order, start, selected);
        }

        void restore(int start, int selected) {
            swap(order, start, selected);
            long firstWeight = weights[order[start]];
            add(start, firstWeight);
            if (selected != start) add(selected, (long) weights[order[selected]] - firstWeight);
        }

        private void add(int position, long amount) {
            total += amount;
            for (int index = position + 1; index < tree.length; index += index & -index) tree[index] += amount;
        }
    }

    private static float getLuckLevel(LootContext context) {
        if (context.getParamOrNull(LootContextParams.THIS_ENTITY) instanceof ServerPlayer player) {
            float playerLuck = player.getLuck();
            if (playerLuck > 0) return playerLuck;
        }
        return Math.max(0, context.getLuck());
    }

    private static int calculatePulls(float luck, LootConfigSnapshot snapshot) {
        int pulls = 0;
        float remaining = luck;
        int previousLimit = 0;
        for (BLModConfig.CurveSegment segment : snapshot.curve()) {
            int range = segment.limit() - previousLimit;
            float luckInSegment = Math.min(remaining, range);
            int attempts = (int) Math.floor(luckInSegment / segment.divisor());
            if (segment.probability() >= 1.0f) {
                pulls = (int) Math.min(snapshot.maxBonusRolls(), (long) pulls + attempts);
            } else if (attempts > 0) {
                float expected = attempts * segment.probability();
                int guaranteed = (int) expected;
                pulls = (int) Math.min(snapshot.maxBonusRolls(), (long) pulls + guaranteed
                    + (ThreadLocalRandom.current().nextFloat() < expected - guaranteed ? 1 : 0));
            }
            remaining -= luckInSegment;
            previousLimit = segment.limit();
            if (remaining <= 0 || pulls >= snapshot.maxBonusRolls()) break;
        }
        return pulls;
    }

    private static int resolveSlotLimit(LootContext context, LootConfigSnapshot snapshot) {
        return snapshot.compatibilityMode() ? LootAccumulator.targetSlots(context) : -1;
    }

    private static Object2IntOpenHashMap<ResourceLocation> buildEntryWeights(List<LootPool> pools) {
        Object2IntOpenHashMap<ResourceLocation> weights = new Object2IntOpenHashMap<>();
        weights.defaultReturnValue(0);
        for (LootPool pool : pools) {
            for (LootPoolEntryContainer container : ((LootPoolAccessor) pool).getEntries()) {
                if (!(container instanceof LootPoolSingletonContainer singleton)) continue;
                int weight = ((LootPoolSingletonContainerAccessor) singleton).getWeight();
                if (weight <= 0) continue;
                if (container instanceof LootItem lootItem) {
                    Item item = ((LootItemAccessor) lootItem).getItem();
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    if (id != null) addTotal(weights, id, weight);
                } else if (container instanceof TagEntry tagEntry) {
                    TagKey<Item> tag = ((TagEntryAccessor) tagEntry).getTag();
                    BuiltInRegistries.ITEM.getTag(tag).ifPresent(holders -> holders.forEach(holder -> {
                        ResourceLocation id = BuiltInRegistries.ITEM.getKey(holder.value());
                        if (id != null) addTotal(weights, id, weight);
                    }));
                }
            }
        }
        return weights;
    }

    private static Object2IntOpenHashMap<ResourceLocation> getEntryWeights(LootTable table) {
        List<LootPool> pools = ((LootTableAccessor) table).getPools();
        if (!(table instanceof EntryWeightCacheHolder holder)) return buildEntryWeights(pools);
        EntryWeightCache cache = holder.betterlucklootr$getEntryWeightCache();
        if (cache == null || !cache.matches(pools)) {
            cache = new EntryWeightCache(pools);
            holder.betterlucklootr$setEntryWeightCache(cache);
        }
        return cache.weights;
    }

    public interface EntryWeightCacheHolder {
        EntryWeightCache betterlucklootr$getEntryWeightCache();
        void betterlucklootr$setEntryWeightCache(EntryWeightCache cache);
    }

    public static final class EntryWeightCache {
        private final WeightPool[] pools;
        private final Map<TagKey<Item>, List<Item>> tagMembers;
        private final Object2IntOpenHashMap<ResourceLocation> weights;

        private EntryWeightCache(List<LootPool> sourcePools) {
            pools = new WeightPool[sourcePools.size()];
            tagMembers = new LinkedHashMap<>();
            weights = new Object2IntOpenHashMap<>();
            weights.defaultReturnValue(0);
            for (int poolIndex = 0; poolIndex < pools.length; poolIndex++) {
                LootPool pool = sourcePools.get(poolIndex);
                LootPoolEntryContainer[] entries = ((LootPoolAccessor) pool).getEntries();
                WeightSource[] sources = new WeightSource[entries.length];
                for (int index = 0; index < entries.length; index++) {
                    LootPoolEntryContainer entry = entries[index];
                    int weight = entry instanceof LootPoolSingletonContainer singleton
                        ? ((LootPoolSingletonContainerAccessor) singleton).getWeight() : 0;
                    Object identity = weightIdentity(entry);
                    sources[index] = new WeightSource(entry, weight, identity);
                    if (weight <= 0) continue;
                    if (identity instanceof Item item) {
                        addWeight(item, weight);
                    } else if (identity instanceof TagKey<?> rawTag) {
                        @SuppressWarnings("unchecked") TagKey<Item> tag = (TagKey<Item>) rawTag;
                        List<Item> members = tagMembers.computeIfAbsent(tag, key -> BuiltInRegistries.ITEM.getTag(key)
                            .map(holders -> holders.stream().map(net.minecraft.core.Holder::value).toList()).orElse(List.of()));
                        for (Item item : members) addWeight(item, weight);
                    }
                }
                pools[poolIndex] = new WeightPool(pool, sources);
            }
        }

        private void addWeight(Item item, int weight) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) addTotal(weights, id, weight);
        }

        private boolean matches(List<LootPool> current) {
            if (current.size() != pools.length) return false;
            for (int poolIndex = 0; poolIndex < pools.length; poolIndex++) {
                WeightPool saved = pools[poolIndex];
                LootPool pool = current.get(poolIndex);
                if (pool != saved.pool()) return false;
                LootPoolEntryContainer[] entries = ((LootPoolAccessor) pool).getEntries();
                if (entries.length != saved.sources().length) return false;
                for (int index = 0; index < entries.length; index++) {
                    WeightSource source = saved.sources()[index];
                    LootPoolEntryContainer entry = entries[index];
                    if (entry != source.entry()) return false;
                    int weight = entry instanceof LootPoolSingletonContainer singleton
                        ? ((LootPoolSingletonContainerAccessor) singleton).getWeight() : 0;
                    if (weight != source.weight() || weightIdentity(entry) != source.identity()) return false;
                }
            }
            for (var tag : tagMembers.entrySet()) {
                var currentMembers = BuiltInRegistries.ITEM.getTag(tag.getKey()).orElse(null);
                List<Item> saved = tag.getValue();
                if ((currentMembers == null ? 0 : currentMembers.size()) != saved.size()) return false;
                for (int index = 0; index < saved.size(); index++) {
                    if (currentMembers.get(index).value() != saved.get(index)) return false;
                }
            }
            return true;
        }

        private static Object weightIdentity(LootPoolEntryContainer entry) {
            if (entry instanceof LootItem item) return ((LootItemAccessor) item).getItem();
            if (entry instanceof TagEntry tag) return ((TagEntryAccessor) tag).getTag();
            return null;
        }

        private record WeightPool(LootPool pool, WeightSource[] sources) {}
        private record WeightSource(LootPoolEntryContainer entry, int weight, Object identity) {}
    }
}

final class LootDiagnosticGate {
    private final AtomicBoolean pending = new AtomicBoolean(true);

    boolean isPending() {
        return pending.get();
    }

    boolean tryAcquire() {
        return pending.compareAndSet(true, false);
    }
}

final class LootDiagnosticCollector {
    private final int maxEntries;
    private final int maxVariants;
    private final int maxEvents;
    private final Map<DropKey, MutableDrop> drops = new LinkedHashMap<>();
    private final Map<String, MutableItem> finalItems = new LinkedHashMap<>();
    private int truncatedEntries;
    private int processedEvents;
    private int truncatedEvents;

    LootDiagnosticCollector(int maxEntries, int maxVariants) {
        this(maxEntries, maxVariants, Integer.MAX_VALUE);
    }

    LootDiagnosticCollector(int maxEntries, int maxVariants, int maxEvents) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxVariants = Math.max(1, maxVariants);
        this.maxEvents = Math.max(1, maxEvents);
    }

    void recordDrop(String reason, String itemId, int count, int variantHash) {
        recordDrop(reason, itemId, count, () -> variantHash);
    }

    void recordDrop(String reason, String itemId, int count, IntSupplier variantHash) {
        if (processedEvents >= maxEvents) {
            truncatedEvents++;
            return;
        }
        processedEvents++;
        DropKey key = new DropKey(reason, itemId);
        MutableDrop existing = drops.get(key);
        if (existing == null) {
            if (drops.size() + finalItems.size() >= maxEntries) {
                truncatedEntries++;
                return;
            }
            existing = new MutableDrop(reason, itemId);
            drops.put(key, existing);
        }
        existing.events++;
        existing.totalCount += Math.max(0, count);
        if (existing.hashSamples < maxVariants) {
            existing.hashSamples++;
            existing.variantHashes.add(variantHash.getAsInt());
        }
    }

    void recordCapacityRemainder(String itemId, int requested, int accepted, IntSupplier variantHash) {
        int remainder = Math.max(0, requested - accepted);
        if (remainder > 0) recordDrop("SLOT_LIMIT", itemId, remainder, variantHash);
    }

    void recordFinal(String itemId, int count, int maxStackSize, int variantHash) {
        MutableItem existing = finalItems.get(itemId);
        if (existing == null) {
            if (drops.size() + finalItems.size() >= maxEntries) {
                truncatedEntries++;
                return;
            }
            existing = new MutableItem(itemId, maxStackSize);
            finalItems.put(itemId, existing);
        }
        existing.totalCount += Math.max(0, count);
        if (existing.variantHashes.size() < maxVariants) existing.variantHashes.add(variantHash);
    }

    List<DropSummary> drops() {
        List<DropSummary> result = new ArrayList<>(drops.size());
        for (MutableDrop value : drops.values()) {
            result.add(new DropSummary(value.reason, value.itemId, value.totalCount,
                value.events, value.variantHashes.size(), List.copyOf(value.variantHashes)));
        }
        return List.copyOf(result);
    }

    List<ItemSummary> finalItems() {
        List<ItemSummary> result = new ArrayList<>(finalItems.size());
        for (MutableItem value : finalItems.values()) {
            result.add(new ItemSummary(value.itemId, value.totalCount, value.variantHashes.size(),
                value.maxStackSize, List.copyOf(value.variantHashes)));
        }
        return List.copyOf(result);
    }

    List<String> renderDropLines() {
        List<String> result = new ArrayList<>(drops.size());
        for (DropSummary value : drops()) {
            result.add("DROP reason=" + value.reason + " item=" + value.itemId
                + " events=" + value.events + " total=" + value.totalCount
                + " variants=" + value.variantCount + " hashes=" + value.variantHashes);
        }
        return List.copyOf(result);
    }

    List<String> renderFinalLines() {
        List<String> result = new ArrayList<>(finalItems.size());
        for (ItemSummary value : finalItems()) {
            result.add("ITEM item=" + value.itemId + " total=" + value.totalCount
                + " variants=" + value.variantCount + " maxStack=" + value.maxStackSize
                + " special=" + (value.maxStackSize == 1) + " hashes=" + value.variantHashes);
        }
        return List.copyOf(result);
    }

    int dropEvents() {
        int total = 0;
        for (MutableDrop value : drops.values()) total += value.events;
        return total;
    }

    long droppedCount() {
        long total = 0;
        for (MutableDrop value : drops.values()) total += value.totalCount;
        return total;
    }

    int truncatedEntries() {
        return truncatedEntries;
    }

    int truncatedEvents() {
        return truncatedEvents;
    }

    record DropSummary(String reason, String itemId, long totalCount, int events,
                       int variantCount, List<Integer> variantHashes) {
    }

    record ItemSummary(String itemId, long totalCount, int variantCount,
                       int maxStackSize, List<Integer> variantHashes) {
    }

    private record DropKey(String reason, String itemId) {
    }

    private static final class MutableDrop {
        private final String reason;
        private final String itemId;
        private final Set<Integer> variantHashes = new LinkedHashSet<>();
        private long totalCount;
        private int events;
        private int hashSamples;

        private MutableDrop(String reason, String itemId) {
            this.reason = reason;
            this.itemId = itemId;
        }
    }

    private static final class MutableItem {
        private final String itemId;
        private final int maxStackSize;
        private final Set<Integer> variantHashes = new LinkedHashSet<>();
        private long totalCount;

        private MutableItem(String itemId, int maxStackSize) {
            this.itemId = itemId;
            this.maxStackSize = maxStackSize;
        }
    }
}
