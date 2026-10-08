package com.azukaar.betterlucklootr;

import com.mojang.serialization.Codec;

import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

@Mod(BetterLuckLootr.MODID)
@Mod.EventBusSubscriber(modid = BetterLuckLootr.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class BetterLuckLootr
{
    public static final String MODID = "better_luck_lootr";

    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLOBAL_LOOT_MODIFIER_SERIALIZERS =
        DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, MODID);

    public static final Supplier<Codec<BetterLuckLootModifier>> LUCK_MODIFIER =
            GLOBAL_LOOT_MODIFIER_SERIALIZERS.register("luck_modifier", () -> BetterLuckLootModifier.CODEC);

    public static final DeferredRegister<LootItemConditionType> LOOT_CONDITIONS =
        DeferredRegister.create(ResourceKey.createRegistryKey(ResourceLocation.tryParse("minecraft:loot_condition_type")), MODID);

    public static final Supplier<LootItemConditionType> LOOT_TABLE_MATCH =
        LOOT_CONDITIONS.register("loot_table_match", () -> new LootItemConditionType(new LootTableMatchCondition.Serializer()));

    public static final DeferredRegister<Attribute> ATTRIBUTES =
        DeferredRegister.create(ForgeRegistries.ATTRIBUTES, MODID);

    public static final Supplier<Attribute> LOOT_RICHNESS =
        ATTRIBUTES.register("loot_richness", () -> new RangedAttribute("attribute.better_luck_lootr.loot_richness", 0.0, 0.0, 500.0).setSyncable(true));

    public BetterLuckLootr(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();
        GLOBAL_LOOT_MODIFIER_SERIALIZERS.register(modEventBus);
        LOOT_CONDITIONS.register(modEventBus);
        ATTRIBUTES.register(modEventBus);

        context.registerConfig(ModConfig.Type.COMMON, BLModConfig.serverSpec);
    }

    @SubscribeEvent
    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, LOOT_RICHNESS.get());
    }

}
