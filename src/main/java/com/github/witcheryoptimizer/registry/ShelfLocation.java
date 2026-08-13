package com.github.witcheryoptimizer.registry;

import net.minecraft.nbt.NBTTagCompound;

public final class ShelfLocation implements Comparable<ShelfLocation> {

    public final int dimension;
    public final int x;
    public final int y;
    public final int z;

    public ShelfLocation(int dimension, int x, int y, int z) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Dimension", dimension);
        tag.setInteger("X", x);
        tag.setInteger("Y", y);
        tag.setInteger("Z", z);
        return tag;
    }

    public static ShelfLocation read(NBTTagCompound tag) {
        StrictNbt.require(tag, "Dimension", 3);
        StrictNbt.require(tag, "X", 3);
        StrictNbt.require(tag, "Y", 3);
        StrictNbt.require(tag, "Z", 3);
        ShelfLocation location = new ShelfLocation(
            tag.getInteger("Dimension"),
            tag.getInteger("X"),
            tag.getInteger("Y"),
            tag.getInteger("Z"));
        if (location.y < 0 || location.y > 255)
            throw new IllegalStateException("Shelf Y outside 0..255: " + location.y);
        return location;
    }

    @Override
    public int compareTo(ShelfLocation other) {
        int result = Integer.compare(dimension, other.dimension);
        if (result == 0) result = Integer.compare(x, other.x);
        if (result == 0) result = Integer.compare(y, other.y);
        if (result == 0) result = Integer.compare(z, other.z);
        return result;
    }

    @Override
    public boolean equals(Object value) {
        if (!(value instanceof ShelfLocation)) return false;
        ShelfLocation other = (ShelfLocation) value;
        return dimension == other.dimension && x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        int result = dimension;
        result = 31 * result + x;
        result = 31 * result + y;
        return 31 * result + z;
    }

    @Override
    public String toString() {
        return dimension + ":" + x + "," + y + "," + z;
    }
}
