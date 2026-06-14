package com.azukaar.betterlucklootr.mixin;

import net.minecraft.world.level.storage.loot.entries.LootPoolEntry;
import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LootPoolSingletonContainer.class)
public interface LootPoolSingletonContainerAccessor {
    @Accessor
    LootPoolEntry getEntry();

    @Accessor
    int getWeight();

    @Accessor
    int getQuality();

    @Accessor
    net.minecraft.world.level.storage.loot.functions.LootItemFunction[] getFunctions();
}
