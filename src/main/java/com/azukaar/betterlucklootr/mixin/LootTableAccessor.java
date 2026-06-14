package com.azukaar.betterlucklootr.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.function.BiFunction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;

@Mixin(LootTable.class)
public interface LootTableAccessor {
    @Accessor
    List<LootPool> getPools();

    @Accessor
    BiFunction<ItemStack, LootContext, ItemStack> getCompositeFunction();
}
