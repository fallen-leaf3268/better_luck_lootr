package com.azukaar.betterlucklootr;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.Serializer;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemConditionType;

import java.util.regex.Pattern;

public class LootTableMatchCondition implements LootItemCondition {
    private final Pattern pattern;

    public LootTableMatchCondition(String patternString) {
        this.pattern = Pattern.compile(patternString);
    }

    @Override
    public LootItemConditionType getType() {
        return BetterLuckLootr.LOOT_TABLE_MATCH.get();
    }

    @Override
    public boolean test(LootContext lootContext) {
        ResourceLocation lootTableId = lootContext.getQueriedLootTableId();
        return lootTableId != null && pattern.matcher(lootTableId.toString()).matches();
    }

    public static class Serializer implements net.minecraft.world.level.storage.loot.Serializer<LootTableMatchCondition> {
        @Override
        public void serialize(JsonObject json, LootTableMatchCondition condition, JsonSerializationContext context) {
            json.addProperty("loot_table_id", condition.pattern.pattern());
        }

        @Override
        public LootTableMatchCondition deserialize(JsonObject json, JsonDeserializationContext context) {
            String pattern = GsonHelper.getAsString(json, "loot_table_id");
            return new LootTableMatchCondition(pattern);
        }
    }
}
