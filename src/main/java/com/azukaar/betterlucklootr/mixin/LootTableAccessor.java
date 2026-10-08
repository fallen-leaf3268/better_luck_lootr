package com.azukaar.betterlucklootr.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

@Mixin(LootTable.class)
public interface LootTableAccessor {
    @Accessor
    List<LootPool> getPools();

    @Accessor
    LootItemFunction[] getFunctions();

    @Invoker("getRandomItems")
    ObjectArrayList<ItemStack> betterlucklootr$getRandomItems(LootContext context);
}
