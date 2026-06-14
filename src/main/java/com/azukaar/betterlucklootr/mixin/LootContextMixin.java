package com.azukaar.betterlucklootr.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.azukaar.betterlucklootr.BLModConfig;

import net.minecraft.world.level.storage.loot.LootContext;

@Mixin(LootContext.class)
public class LootContextMixin {

    @Inject(method = "getLuck", at = @At("RETURN"), cancellable = true)
    private void capLuckForVanilla(CallbackInfoReturnable<Float> cir) {
        int cap = BLModConfig.maxEffectiveLuck();
        if (cap > 0) {
            float val = cir.getReturnValue();
            if (val > cap) cir.setReturnValue((float) cap);
        }
    }
}
