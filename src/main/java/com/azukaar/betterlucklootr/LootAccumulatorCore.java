package com.azukaar.betterlucklootr;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.function.IntSupplier;

public final class LootAccumulatorCore<K> {
    public static final int HARD_SLOT_LIMIT = 4096;
    private int slotLimit;
    private final Map<K, CountEntry> counts = new LinkedHashMap<>();
    private int estimatedSlots;

    public LootAccumulatorCore(int slotLimit) {
        this.slotLimit = slotLimit >= 0 ? Math.min(slotLimit, HARD_SLOT_LIMIT) : HARD_SLOT_LIMIT;
    }

    public void restrictSlots(int limit) {
        if (limit >= 0) slotLimit = Math.min(slotLimit, limit);
    }

    public int remainingCapacity(K key, int maxAllowed) {
        CountEntry entry = counts.get(key);
        if (entry == null || maxAllowed <= entry.count) return 0;
        int oldSlots = slotsFor(entry.count, entry.maxStackSize);
        long room = (long) (slotLimit - estimatedSlots + oldSlots) * entry.maxStackSize - entry.count;
        return (int) Math.max(0, Math.min(room, (long) maxAllowed - entry.count));
    }

    public boolean offer(K key, int amount, int maxStackSize, int maxAllowed) {
        if (key == null || amount <= 0 || maxStackSize <= 0 || maxAllowed <= 0) return false;
        CountEntry entry = counts.get(key);
        return accept(key, entry, amount, maxStackSize, maxAllowed, null) > 0;
    }

    int offerWithPayload(K key, int amount, int maxAllowed, Supplier<Payload> factory) {
        return offerWithPayload(key, amount, maxAllowed, factory, null);
    }

    int offerWithPayload(K key, int amount, int maxAllowed, Supplier<Payload> factory, IntSupplier maxAdditional) {
        if (key == null || amount <= 0 || maxAllowed <= 0) return 0;
        CountEntry entry = counts.get(key);
        Payload payload = null;
        if (entry == null) {
            if (isFull()) return 0;
            payload = factory.get();
            if (payload == null || payload.maxStackSize <= 0) return 0;
        }
        if (maxAdditional != null) {
            amount = Math.min(amount, maxAdditional.getAsInt());
            if (amount <= 0) return 0;
        }
        if (payload != null || maxAdditional != null) entry = counts.get(key);
        return accept(key, entry, amount, entry == null ? payload.maxStackSize : entry.maxStackSize,
            maxAllowed, payload == null ? null : payload.value);
    }

    int increment(K key, int amount, int maxAllowed) {
        if (key == null || amount <= 0 || maxAllowed <= 0) return 0;
        CountEntry entry = counts.get(key);
        return entry == null ? 0 : accept(key, entry, amount, entry.maxStackSize, maxAllowed, null);
    }

    private int accept(K key, CountEntry entry, int amount, int maxStackSize, int maxAllowed, Object payload) {
        if (entry != null) maxStackSize = entry.maxStackSize;
        int current = entry == null ? 0 : entry.count;
        int oldSlots = slotsFor(current, maxStackSize);
        int slotsAvailableForType = slotLimit - (estimatedSlots - oldSlots);
        if (slotsAvailableForType <= 0) return 0;
        long maxCountBySlots = (long) slotsAvailableForType * maxStackSize;
        long requested = (long) current + amount;
        int next = (int) Math.min(Integer.MAX_VALUE,
            Math.min(requested, Math.min((long) maxAllowed, maxCountBySlots)));
        if (next <= current) return 0;

        if (entry == null) {
            counts.put(key, new CountEntry(next, maxStackSize, payload));
        } else {
            entry.count = next;
        }
        estimatedSlots += slotsFor(next, maxStackSize) - oldSlots;
        return next - current;
    }

    public int count(K key) {
        CountEntry entry = counts.get(key);
        return entry == null ? 0 : entry.count;
    }

    public int maxStackSize(K key) {
        CountEntry entry = counts.get(key);
        return entry == null ? 0 : entry.maxStackSize;
    }

    public int typeCount() {
        return counts.size();
    }

    public int estimatedSlots() {
        return estimatedSlots;
    }

    public boolean isFull() {
        return estimatedSlots >= slotLimit;
    }

    public List<K> keys() {
        return List.copyOf(counts.keySet());
    }

    Object payload(K key) {
        CountEntry entry = counts.get(key);
        return entry == null ? null : entry.payload;
    }

    List<Snapshot<K>> snapshot() {
        List<Snapshot<K>> result = new ArrayList<>(counts.size());
        for (Map.Entry<K, CountEntry> value : counts.entrySet()) {
            CountEntry entry = value.getValue();
            result.add(new Snapshot<>(value.getKey(), entry.count, entry.maxStackSize, entry.payload));
        }
        return List.copyOf(result);
    }

    record Payload(Object value, int maxStackSize) {
    }

    record Snapshot<K>(K key, int count, int maxStackSize, Object payload) {
    }

    private static int slotsFor(int count, int maxStackSize) {
        return count <= 0 ? 0 : (count - 1) / maxStackSize + 1;
    }

    private static final class CountEntry {
        private int count;
        private final int maxStackSize;
        private final Object payload;

        private CountEntry(int count, int maxStackSize, Object payload) {
            this.count = count;
            this.maxStackSize = maxStackSize;
            this.payload = payload;
        }
    }
}
