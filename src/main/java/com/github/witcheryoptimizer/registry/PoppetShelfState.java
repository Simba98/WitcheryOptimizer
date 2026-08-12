package com.github.witcheryoptimizer.registry;

import java.util.UUID;

public interface PoppetShelfState {

    UUID witcheryoptimizer$getShelfId();

    void witcheryoptimizer$setShelfId(UUID id);

    boolean witcheryoptimizer$hasPersistentShelfId();

    void witcheryoptimizer$setPersistentShelfId(boolean persistent);

    long witcheryoptimizer$getDiskMirrorVersion();

    void witcheryoptimizer$setDiskMirrorVersion(long version);

    String witcheryoptimizer$getCustomName();

    void witcheryoptimizer$setCustomName(String name);

    void witcheryoptimizer$detach();
}
