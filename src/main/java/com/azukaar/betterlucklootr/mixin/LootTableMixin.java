package com.azukaar.betterlucklootr.mixin;

import com.azukaar.betterlucklootr.LootAccumulator;
import com.azukaar.betterlucklootr.BetterLuckLootModifier;
import com.google.common.collect.ImmutableCollection;
import java.util.Collection;
import java.util.Map;
import com.azukaar.betterlucklootr.BetterLuckLootModifier.EntryWeightCache;
import com.azukaar.betterlucklootr.BetterLuckLootModifier.EntryWeightCacheHolder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifierManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LootTable.class)
public abstract class LootTableMixin implements EntryWeightCacheHolder {
    @Unique
    private volatile EntryWeightCache betterlucklootr$entryWeightCache;

    @Override
    public EntryWeightCache betterlucklootr$getEntryWeightCache() {
        return betterlucklootr$entryWeightCache;
    }

    @Override
    public void betterlucklootr$setEntryWeightCache(EntryWeightCache cache) {
        betterlucklootr$entryWeightCache = cache;
    }

    @Redirect(method = "fill", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"), require = 1)
    private ObjectArrayList<ItemStack> betterlucklootr$fillTarget(LootTable table, LootContext context,
                                                                Container container, LootParams params, long seed) {
        return LootAccumulator.generateForFill(container, context,
            () -> ((LootTableAccessor) table).betterlucklootr$getRandomItems(context));
    }

    @Mixin(value = LootModifierManager.class, remap = false)
    public abstract static class ModifierOrder {
        @Unique
        private volatile Map.Entry<Collection<IGlobalLootModifier>, Collection<IGlobalLootModifier>> betterlucklootr$modifierOrder;

        @Inject(method = "getAllLootMods", at = @At("RETURN"), cancellable = true, remap = false)
        private void betterlucklootr$prioritizeBonus(CallbackInfoReturnable<Collection<IGlobalLootModifier>> callback) {
            Collection<IGlobalLootModifier> source = callback.getReturnValue();
            if (!(source instanceof ImmutableCollection<?>)) {
                callback.setReturnValue(BetterLuckLootModifier.prioritizeModifiers(source));
                return;
            }
            var cached = betterlucklootr$modifierOrder;
            if (cached == null || cached.getKey() != source) {
                cached = Map.entry(source, BetterLuckLootModifier.prioritizeModifiers(source));
                betterlucklootr$modifierOrder = cached;
            }
            callback.setReturnValue(cached.getValue());
        }
    }
}
