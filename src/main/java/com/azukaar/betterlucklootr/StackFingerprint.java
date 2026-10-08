package com.azukaar.betterlucklootr;

import java.util.Objects;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public final class StackFingerprint {
    private final Object itemIdentity;
    private final CompoundTag tag;
    private final int hash;

    private StackFingerprint(Object itemIdentity, CompoundTag tag) {
        this.itemIdentity = itemIdentity;
        this.tag = tag;
        this.hash = 31 * System.identityHashCode(itemIdentity) + Objects.hashCode(tag);
    }

    public static StackFingerprint of(ItemStack stack) {
        CompoundTag data = savedData(stack);
        return new StackFingerprint(stack.getItem(), data == null ? null : data.copy());
    }

    static StackFingerprint reuseOrCreate(ItemStack stack, StackFingerprint previous) {
        CompoundTag data = savedData(stack);
        Object itemIdentity = stack.getItem();
        if (previous != null && previous.itemIdentity == itemIdentity && Objects.equals(previous.tag, data)) {
            return previous;
        }
        return new StackFingerprint(itemIdentity, data == null ? null : data.copy());
    }

    private static CompoundTag savedData(ItemStack stack) {
        CompoundTag data = stack.save(new CompoundTag());
        data.remove("Count");
        data.remove("id");
        return data.isEmpty() ? null : data;
    }

    static StackFingerprint ofParts(Object itemIdentity, CompoundTag tag) {
        return new StackFingerprint(itemIdentity, tag == null ? null : tag.copy());
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof StackFingerprint other)) return false;
        return itemIdentity == other.itemIdentity && Objects.equals(tag, other.tag);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
