package com.azukaar.betterlucklootr;

import java.util.function.BooleanSupplier;
import java.util.function.Function;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.LootContext;

public final class BonusRollScope {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private BonusRollScope() {
    }

    public static Scope open(LootConfigSnapshot snapshot) {
        return open(snapshot, () -> true, item -> CandidateDecision.ALLOW);
    }

    public static Scope open(LootConfigSnapshot snapshot, BooleanSupplier capacity,
                             Function<Item, CandidateDecision> candidateEvaluator) {
        return open(snapshot, capacity, candidateEvaluator, null, true);
    }

    public static Scope open(LootConfigSnapshot snapshot, BooleanSupplier capacity,
                             Function<Item, CandidateDecision> candidateEvaluator,
                             LootContext context, boolean canPreselect) {
        State previous = CURRENT.get();
        CURRENT.set(new State(snapshot, capacity, candidateEvaluator, context, canPreselect));
        return new Scope(previous);
    }

    public static boolean canPreselect() {
        State state = CURRENT.get();
        return state != null && state.canPreselect;
    }

    public static boolean isBonusContext(LootContext context) {
        State state = CURRENT.get();
        return state != null && context != null && state.context == context;
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    public static LootConfigSnapshot snapshot() {
        State state = CURRENT.get();
        return state == null ? null : state.snapshot;
    }

    public static boolean hasCapacity() {
        State state = CURRENT.get();
        return state == null || state.capacity.getAsBoolean();
    }

    public static CandidateDecision evaluate(Item item) {
        State state = CURRENT.get();
        return state == null ? CandidateDecision.ALLOW : state.candidateEvaluator.apply(item);
    }

    public enum CandidateDecision {
        ALLOW,
        BLACKLIST,
        DEDUP_LIMIT
    }

    private record State(LootConfigSnapshot snapshot, BooleanSupplier capacity,
                         Function<Item, CandidateDecision> candidateEvaluator,
                         LootContext context, boolean canPreselect) {
    }

    public static final class Scope implements AutoCloseable {
        private final State previous;
        private boolean closed;

        private Scope(State previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
