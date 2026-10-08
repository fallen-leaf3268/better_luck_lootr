package com.azukaar.betterlucklootr;

public final class LootRollBudget {
    private LootRollBudget() {
    }

    public static Plan plan(int bonusPulls, int configuredFullRolls, int maxBonusRolls, int richness) {
        int capped = Math.max(0, Math.min(bonusPulls, maxBonusRolls));
        int extraFullRolls = Math.min(capped, Math.max(0, configuredFullRolls - 1));
        int incrementPulls = (int) Math.min(Integer.MAX_VALUE,
            (long) capped - extraFullRolls + Math.max(0, richness));
        return new Plan(extraFullRolls, incrementPulls, capped);
    }

    public record Plan(int extraFullRolls, int incrementPulls, int totalBonusPulls) {
        public int remainingIncrements(int attemptedRolls, int missedRolls, int compensationMode) {
            long remaining = (long) incrementPulls + Math.max(0, extraFullRolls - Math.max(0, attemptedRolls));
            if (compensationMode == 2) remaining += Math.min(Math.max(0, attemptedRolls), Math.max(0, missedRolls));
            return (int) Math.min(Integer.MAX_VALUE, remaining);
        }
    }
}
