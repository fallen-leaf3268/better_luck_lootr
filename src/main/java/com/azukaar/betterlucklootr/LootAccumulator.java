package com.azukaar.betterlucklootr;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.IntSupplier;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;

public final class LootAccumulator {
    private static final ThreadLocal<FillTarget> FILL_TARGET = new ThreadLocal<>();
    private final LootAccumulatorCore<StackFingerprint> core;
    private StackFingerprint recentFingerprint;
    private FillTarget contributionTarget;
    private Map<StackFingerprint, Long> protectedCounts;
    private List<LootAccumulatorCore.Snapshot<StackFingerprint>> materializedSnapshot;

    public LootAccumulator(int slotLimit) {
        this.core = new LootAccumulatorCore<>(slotLimit);
    }

    public boolean offer(ItemStack stack, int maxAllowed) {
        if (stack == null || stack.isEmpty()) return false;
        StackFingerprint key = fingerprint(stack);
        return offer(key, stack, stack.getCount(), maxAllowed);
    }

    StackFingerprint fingerprint(ItemStack stack) {
        return StackFingerprint.reuseOrCreate(stack, recentFingerprint);
    }

    public boolean offer(StackFingerprint key, ItemStack stack, int amount, int maxAllowed) {
        return offerAccepted(key, stack, amount, maxAllowed, null) > 0;
    }

    int offerAccepted(StackFingerprint key, ItemStack stack, int amount, int maxAllowed, IntSupplier maxAdditional) {
        if (key == null || stack == null || stack.isEmpty() || amount <= 0 || maxAllowed <= 0) return 0;
        int accepted = core.offerWithPayload(key, amount, maxAllowed, () -> {
            ItemStack reference = stack.copy();
            reference.setCount(1);
            return new LootAccumulatorCore.Payload(reference, Math.max(1, stack.getMaxStackSize()));
        }, maxAdditional);
        if (accepted > 0) recentFingerprint = key;
        return accepted;
    }

    public int increment(StackFingerprint key, int amount, int maxAllowed) {
        return core.increment(key, amount, maxAllowed);
    }

    public int count(StackFingerprint key) {
        return core.count(key);
    }

    public int typeCount() {
        return core.typeCount();
    }

    public int estimatedSlots() {
        return core.estimatedSlots();
    }

    public boolean isFull() {
        return core.isFull();
    }

    public void restrictSlots(int limit) {
        core.restrictSlots(limit);
    }

    public int remainingCapacity(StackFingerprint key, int maxAllowed) {
        return core.remainingCapacity(key, maxAllowed);
    }

    public static int targetSlots(LootContext context) {
        FillTarget target = FILL_TARGET.get();
        return target != null && target.context == context ? target.slots : -1;
    }

    public static <T> T withTarget(Container container, LootContext context, Supplier<T> generation) {
        int slots = 0;
        for (int index = 0; index < container.getContainerSize(); index++) {
            if (container.getItem(index).isEmpty()) slots++;
        }
        FillTarget previous = FILL_TARGET.get();
        FILL_TARGET.set(new FillTarget(context, slots));
        try {
            return generation.get();
        } finally {
            if (previous == null) FILL_TARGET.remove();
            else FILL_TARGET.set(previous);
        }
    }

    public static ObjectArrayList<ItemStack> generateForFill(Container container, LootContext context,
                                                            Supplier<ObjectArrayList<ItemStack>> generation) {
        return withTarget(container, context, () -> {
            FillTarget target = FILL_TARGET.get();
            target.actualFill = true;
            ObjectArrayList<ItemStack> loot = generation.get();
            if (target.contributions == null) return loot;
            int slots = 0;
            for (int index = 0; index < container.getContainerSize(); index++) {
                if (container.getItem(index).isEmpty()) slots++;
            }
            int requiredSlots = 0;
            boolean fits = true;
            for (ItemStack stack : loot) {
                if (stack.isEmpty()) continue;
                if (++requiredSlots > slots || stack.getCount() > Math.min(container.getMaxStackSize(), stack.getMaxStackSize())) {
                    fits = false;
                    break;
                }
            }
            if (fits) return loot;
            return prioritizeForCapacity(loot, target.contributions, slots, container.getMaxStackSize());
        });
    }

    void captureOriginal(LootContext context) {
        FillTarget target = FILL_TARGET.get();
        if (target == null || target.context != context || !target.actualFill) return;
        Map<StackFingerprint, Long> captured = new LinkedHashMap<>();
        for (LootAccumulatorCore.Snapshot<StackFingerprint> entry : core.snapshot()) {
            Contribution previous = target.contributions == null ? null : target.contributions.get(entry.key());
            captured.put(entry.key(), protectedCount(entry.count(), previous));
        }
        protectedCounts = captured;
        contributionTarget = target;
    }

    void recordOutput(LootContext context) {
        if (protectedCounts == null || materializedSnapshot == null
                || contributionTarget != FILL_TARGET.get() || contributionTarget.context != context) return;
        Map<StackFingerprint, Contribution> contributions = new LinkedHashMap<>();
        for (LootAccumulatorCore.Snapshot<StackFingerprint> entry : materializedSnapshot) {
            long protectedCount = Math.min(entry.count(), protectedCounts.getOrDefault(entry.key(), 0L));
            contributions.put(entry.key(), new Contribution(entry.count(), entry.count() - protectedCount));
        }
        contributionTarget.contributions = contributions;
    }

    private static long protectedCount(long count, Contribution contribution) {
        if (contribution == null) return count;
        return Math.min(count, contribution.total() - contribution.bonus())
            + Math.max(0L, count - contribution.total());
    }

    private static ObjectArrayList<ItemStack> prioritizeForCapacity(ObjectArrayList<ItemStack> loot,
            Map<StackFingerprint, Contribution> contributions, int slots, int containerMaxStackSize) {
        Map<StackFingerprint, FinalGroup> groups = new LinkedHashMap<>();
        StackFingerprint previous = null;
        for (ItemStack stack : loot) {
            if (stack.isEmpty()) continue;
            StackFingerprint key = StackFingerprint.reuseOrCreate(stack, previous);
            previous = key;
            int maxStackSize = Math.max(1, Math.min(containerMaxStackSize, stack.getMaxStackSize()));
            FinalGroup group = groups.get(key);
            if (group == null) {
                group = new FinalGroup(stack, maxStackSize);
                groups.put(key, group);
            }
            group.maxStackSize = Math.min(group.maxStackSize, maxStackSize);
            group.total += stack.getCount();
        }
        long protectedSlots = 0;
        for (Map.Entry<StackFingerprint, FinalGroup> entry : groups.entrySet()) {
            FinalGroup group = entry.getValue();
            group.selected = protectedCount(group.total, contributions.get(entry.getKey()));
            protectedSlots += slotsFor(group.selected, group.maxStackSize);
        }
        if (protectedSlots > slots) {
            ObjectArrayList<ItemStack> retained = new ObjectArrayList<>(loot.size());
            previous = null;
            for (ItemStack stack : loot) {
                if (stack.isEmpty()) continue;
                StackFingerprint key = StackFingerprint.reuseOrCreate(stack, previous);
                previous = key;
                FinalGroup group = groups.get(key);
                int keep = (int) Math.min(stack.getCount(), group.selected);
                if (keep > 0) {
                    ItemStack kept = stack.copy();
                    kept.setCount(keep);
                    retained.add(kept);
                    group.selected -= keep;
                }
            }
            return retained;
        }
        long freeSlots = slots - protectedSlots;
        for (FinalGroup group : groups.values()) {
            long occupied = slotsFor(group.selected, group.maxStackSize);
            group.selected = Math.min(group.total, (occupied + freeSlots) * group.maxStackSize);
            freeSlots -= slotsFor(group.selected, group.maxStackSize) - occupied;
        }
        ObjectArrayList<ItemStack> retained = new ObjectArrayList<>(slots - (int) freeSlots);
        for (FinalGroup group : groups.values()) {
            while (group.selected > 0) {
                ItemStack kept = group.reference.copy();
                int count = (int) Math.min(group.selected, group.maxStackSize);
                kept.setCount(count);
                retained.add(kept);
                group.selected -= count;
            }
        }
        return retained;
    }

    private static long slotsFor(long count, int maxStackSize) {
        return (count + maxStackSize - 1L) / maxStackSize;
    }

    private record Contribution(long total, long bonus) {}

    private static final class FinalGroup {
        private final ItemStack reference;
        private int maxStackSize;
        private long total;
        private long selected;

        private FinalGroup(ItemStack reference, int maxStackSize) {
            this.reference = reference;
            this.maxStackSize = maxStackSize;
        }
    }

    private static final class FillTarget {
        private final LootContext context;
        private final int slots;
        private boolean actualFill;
        private Map<StackFingerprint, Contribution> contributions;

        private FillTarget(LootContext context, int slots) {
            this.context = context;
            this.slots = slots;
        }
    }

    public List<StackFingerprint> keys() {
        return core.keys();
    }

    public ItemStack reference(StackFingerprint key) {
        ItemStack reference = (ItemStack) core.payload(key);
        return reference == null ? ItemStack.EMPTY : reference.copy();
    }

    public List<ItemStack> toStacks() {
        return materialize();
    }

    void recordDiagnostics(LootDiagnosticCollector diagnostics) {
        for (LootAccumulatorCore.Snapshot<StackFingerprint> entry : core.snapshot()) {
            ItemStack reference = (ItemStack) entry.payload();
            if (reference == null || reference.isEmpty()) continue;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(reference.getItem());
            diagnostics.recordFinal(itemId == null ? "<unregistered>" : itemId.toString(),
                entry.count(), entry.maxStackSize(), entry.key().hashCode());
        }
    }

    ObjectArrayList<ItemStack> materialize() {
        ObjectArrayList<ItemStack> result = new ObjectArrayList<>(core.estimatedSlots());
        List<LootAccumulatorCore.Snapshot<StackFingerprint>> snapshot = core.snapshot();
        for (LootAccumulatorCore.Snapshot<StackFingerprint> entry : snapshot) {
            ItemStack reference = (ItemStack) entry.payload();
            int remaining = entry.count();
            int maxStackSize = entry.maxStackSize();
            while (remaining > 0) {
                ItemStack stack = reference.copy();
                int count = Math.min(remaining, maxStackSize);
                stack.setCount(count);
                result.add(stack);
                remaining -= count;
            }
        }
        if (protectedCounts != null) materializedSnapshot = snapshot;
        return result;
    }
}
