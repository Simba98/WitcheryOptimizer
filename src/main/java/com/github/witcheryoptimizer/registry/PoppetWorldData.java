package com.github.witcheryoptimizer.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

final class PoppetWorldData extends WorldSavedData {

    static final String NAME = "witcheryoptimizer_poppets";
    final List<PendingConsumption> pending = new ArrayList<>();
    final List<NBTTagCompound> cached = new ArrayList<>();

    PoppetWorldData() {
        super(NAME);
    }

    PoppetWorldData(String name) {
        super(name);
    }

    static PoppetWorldData get(World world) {
        PoppetWorldData data = (PoppetWorldData) world.mapStorage.loadData(PoppetWorldData.class, NAME);
        if (data == null) {
            data = new PoppetWorldData();
            world.mapStorage.setData(NAME, data);
        }
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound root) {
        pending.clear();
        NBTTagList pendingList = root.getTagList("Pending", 10);
        for (int i = 0; i < pendingList.tagCount(); i++)
            pending.add(PendingConsumption.read(pendingList.getCompoundTagAt(i)));
        cached.clear();
        NBTTagList cacheList = root.getTagList("Cache", 10);
        for (int i = 0; i < cacheList.tagCount(); i++) cached.add(cacheList.getCompoundTagAt(i));
    }

    @Override
    public void writeToNBT(NBTTagCompound root) {
        NBTTagList pendingList = new NBTTagList();
        for (PendingConsumption value : pending) pendingList.appendTag(value.write());
        root.setTag("Pending", pendingList);
        NBTTagList cacheList = new NBTTagList();
        for (NBTTagCompound value : cached) cacheList.appendTag(value.copy());
        root.setTag("Cache", cacheList);
    }

    void replacePending(Collection<PendingConsumption> values) {
        pending.clear();
        pending.addAll(values);
        markDirty();
    }

    void replaceCache(Collection<CachedPoppet> values) {
        cached.clear();
        for (CachedPoppet value : values) cached.add(value.write());
        markDirty();
    }
}
