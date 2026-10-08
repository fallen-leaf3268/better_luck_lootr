package com.azukaar.betterlucklootr.mixin;

import java.util.function.Consumer;

import com.azukaar.betterlucklootr.BonusRollScope;
import com.azukaar.betterlucklootr.PoolPickCache;
import com.mojang.logging.LogUtils;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntry;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LootPool.class)
public abstract class LootPoolMixin {
    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    @Unique
    private volatile PoolPickCache betterlucklootr$cache;

    @Unique
    private PoolPickCache betterlucklootr$getCache() {
        PoolPickCache cache = betterlucklootr$cache;
        var entries = ((LootPoolAccessor) this).getEntries();
        if (cache == null || !cache.matches(entries)) {
            cache = PoolPickCache.build(entries);
            betterlucklootr$cache = cache;
        }
        return cache;
    }

    @Inject(method = "addRandomItems", at = @At("HEAD"), cancellable = true, require = 0)
    private void betterlucklootr$fastAddRandomItems(Consumer<ItemStack> consumer, LootContext context, CallbackInfo callback) {
        if (!BonusRollScope.isBonusContext(context)) return;
        if (!BonusRollScope.hasCapacity()) {
            callback.cancel();
            return;
        }
        if (!BonusRollScope.canPreselect()) return;
        LootPoolAccessor accessor = (LootPoolAccessor) this;
        if (accessor.getFunctions().length > 0) return;
        PoolPickCache cache = betterlucklootr$getCache();
        if (cache.ineligible) return;
        PoolPickCache.Distribution distribution = cache.distribution(context.getLuck());
        if (distribution.total() > Integer.MAX_VALUE) return;
        if (!accessor.getCompositeCondition().test(context)) {
            callback.cancel();
            return;
        }
        int rolls = accessor.getRolls().getInt(context)
            + Mth.floor(accessor.getBonusRolls().getFloat(context) * context.getLuck());
        if (rolls <= 0) {
            callback.cancel();
            return;
        }
        Consumer<ItemStack> decorated = LootItemFunction.decorate(accessor.getCompositeFunction(), consumer, context);
        for (int roll = 0; roll < rolls && BonusRollScope.hasCapacity(); roll++) {
            betterlucklootr$pick(decorated, context, cache, distribution);
        }
        callback.cancel();
    }

    @Inject(method = "addRandomItems", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/level/storage/loot/LootPool;addRandomItem(Ljava/util/function/Consumer;Lnet/minecraft/world/level/storage/loot/LootContext;)V"),
        cancellable = true, require = 0)
    private void betterlucklootr$stopFullPool(Consumer<ItemStack> consumer, LootContext context, CallbackInfo callback) {
        if (BonusRollScope.isBonusContext(context) && !BonusRollScope.hasCapacity()) callback.cancel();
    }

    @Inject(method = "addRandomItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void betterlucklootr$fastPick(Consumer<ItemStack> consumer, LootContext context, CallbackInfo callback) {
        if (!BonusRollScope.isBonusContext(context)) return;
        if (!BonusRollScope.hasCapacity()) {
            callback.cancel();
            return;
        }
        if (!BonusRollScope.canPreselect()) return;
        LootPoolAccessor accessor = (LootPoolAccessor) this;
        if (accessor.getFunctions().length > 0) return;
        PoolPickCache cache = betterlucklootr$getCache();
        if (cache.ineligible) return;
        PoolPickCache.Distribution distribution = cache.distribution(context.getLuck());
        if (distribution.total() > Integer.MAX_VALUE) return;
        betterlucklootr$pick(consumer, context, cache, distribution);
        callback.cancel();
    }

    @Unique
    private static void betterlucklootr$pick(Consumer<ItemStack> consumer, LootContext context,
                                             PoolPickCache cache, PoolPickCache.Distribution distribution) {
        if (distribution.total() <= 0) return;
        int attempts = candidateAttempts();
        for (int attempt = 0; attempt < attempts; attempt++) {
            int selected = distribution.count() == 1 ? 0
                : distribution.choose(context.getRandom().nextInt((int) distribution.total()));
            int entryIndex = distribution.entryIndex(selected);
            BonusRollScope.CandidateDecision decision = BonusRollScope.evaluate(cache.item(entryIndex));
            if (decision == BonusRollScope.CandidateDecision.BLACKLIST) return;
            if (decision == BonusRollScope.CandidateDecision.DEDUP_LIMIT) continue;
            betterlucklootr$create(cache.entry(entryIndex), consumer, context);
            return;
        }
    }

    @Unique
    private static int candidateAttempts() {
        var snapshot = BonusRollScope.snapshot();
        return snapshot == null ? 1
            : com.azukaar.betterlucklootr.LootGenerationPolicy.candidateAttempts(
                snapshot.dedupCompensationMode(), snapshot.dedupRerollMax());
    }

    @Unique
    private static void betterlucklootr$create(LootPoolEntry entry, Consumer<ItemStack> consumer, LootContext context) {
        if (!BonusRollScope.hasCapacity()) return;
        if (!LOGGER.isDebugEnabled()) {
            entry.createItemStack(consumer, context);
            return;
        }
        long started = System.nanoTime();
        entry.createItemStack(consumer, context);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        if (elapsedMs > 50) LOGGER.debug("[BLL-TIMING] createItemStack slow {}ms entryClass={}", elapsedMs, entry.getClass().getName());
    }
}
