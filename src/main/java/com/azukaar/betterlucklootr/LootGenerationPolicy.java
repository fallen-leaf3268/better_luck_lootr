package com.azukaar.betterlucklootr;

public final class LootGenerationPolicy {
    private LootGenerationPolicy() {
    }

    public static boolean shouldContinueFullRolls(int attemptedRolls, int maxExtraRolls, int usedSlots, int slotLimit) {
        return attemptedRolls < maxExtraRolls && (slotLimit < 0 || usedSlots < slotLimit);
    }

    public static int nextCount(int current, boolean carbonMode, double carbonMultiplier, int addAmount) {
        if (current <= 0) return 0;
        long candidate;
        if (carbonMode) {
            double multiplied = current * carbonMultiplier;
            if (!Double.isFinite(multiplied) || multiplied > current * 256.0) {
                candidate = (long) current + addAmount;
            } else {
                candidate = Math.round(multiplied);
            }
        } else {
            candidate = (long) current + addAmount;
        }
        if (candidate <= current) return current;
        return (int) Math.min(Integer.MAX_VALUE, candidate);
    }

    public static boolean isFastPoolEntry(boolean lootItem, boolean tagEntry, boolean tagExpanded,
                                          boolean hasConditions, boolean hasUnsafeFunctions) {
        if (hasConditions || hasUnsafeFunctions) return false;
        return lootItem || tagEntry && tagExpanded;
    }

    public static int candidateAttempts(int compensationMode, int rerollMax) {
        return compensationMode == 1 ? Math.max(1, rerollMax + 1) : 1;
    }

    public static boolean shouldContinueIncrements(int completedRounds, int maxRounds,
                                                   int activeTypes, boolean previousRoundProgressed,
                                                   boolean anyGrowableCandidate) {
        return completedRounds < maxRounds && activeTypes > 0
            && (completedRounds == 0 || previousRoundProgressed || anyGrowableCandidate);
    }

    public static int effectiveWeight(int baseWeight, int quality, float luck) {
        return Math.max((int) Math.floor((float) baseWeight + (float) quality * luck), 0);
    }
}
