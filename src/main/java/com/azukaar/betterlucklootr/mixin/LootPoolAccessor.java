package com.azukaar.betterlucklootr.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;

@Mixin(LootPool.class)
public interface LootPoolAccessor {
    @Accessor
    LootPoolEntryContainer[] getEntries();

    @Accessor
    LootItemCondition[] getConditions();

    @Accessor
    LootItemFunction[] getFunctions();

    @Accessor
    Predicate<LootContext> getCompositeCondition();

    @Accessor
    BiFunction<ItemStack, LootContext, ItemStack> getCompositeFunction();

    @Accessor
    NumberProvider getRolls();

    @Accessor
    NumberProvider getBonusRolls();
}
