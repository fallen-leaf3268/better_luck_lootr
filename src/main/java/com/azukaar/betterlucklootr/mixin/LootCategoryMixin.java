package com.azukaar.betterlucklootr.mixin;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Pseudo
@Mixin(targets = "dev.shadowsoffire.apotheosis.adventure.loot.LootCategory", remap = false)
public class LootCategoryMixin {

    @Unique
    private static final Map<Item, Object> CATEGORY_CACHE = new Object2ObjectOpenHashMap<>();

    @Inject(method = "forItem", at = @At("HEAD"), cancellable = true, remap = false)
    private static void betterlucklootr$cacheForItem(ItemStack item, CallbackInfoReturnable<Object> cir) {
        if (item.isEmpty()) return;
        Object cached = CATEGORY_CACHE.get(item.getItem());
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "forItem", at = @At("RETURN"), cancellable = true, remap = false)
    private static void betterlucklootr$cacheResult(ItemStack item, CallbackInfoReturnable<Object> cir) {
        if (!item.isEmpty()) {
            CATEGORY_CACHE.put(item.getItem(), cir.getReturnValue());
        }
    }
}
