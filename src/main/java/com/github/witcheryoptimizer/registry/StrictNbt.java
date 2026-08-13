package com.github.witcheryoptimizer.registry;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

final class StrictNbt {

    private StrictNbt() {}

    static void require(NBTTagCompound tag, String key, int type) {
        if (!tag.hasKey(key, type))
            throw new IllegalStateException("Missing or invalid NBT " + key + " (type " + type + ")");
    }

    static boolean optionalPair(NBTTagCompound tag, String first, String second, int type) {
        boolean a = tag.hasKey(first);
        boolean b = tag.hasKey(second);
        if (a != b) throw new IllegalStateException("Partial NBT pair " + first + "/" + second);
        if (a) {
            require(tag, first, type);
            require(tag, second, type);
        }
        return a;
    }

    static NBTTagList list(NBTTagCompound tag, String key, int elementType) {
        require(tag, key, 9);
        NBTTagList list = tag.getTagList(key, elementType);
        if (list.tagCount() > 0 && list.func_150303_d() != elementType)
            throw new IllegalStateException("Invalid NBT list element type for " + key);
        return list;
    }

    static long nonnegativeLong(NBTTagCompound tag, String key) {
        require(tag, key, 4);
        long value = tag.getLong(key);
        if (value < 0) throw new IllegalStateException("Negative NBT " + key);
        return value;
    }

    static int nonnegativeInt(NBTTagCompound tag, String key) {
        require(tag, key, 3);
        int value = tag.getInteger(key);
        if (value < 0) throw new IllegalStateException("Negative NBT " + key);
        return value;
    }
}
