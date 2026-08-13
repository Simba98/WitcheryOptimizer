package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collection;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ShelfLocationStrictTypeTest {

    @Parameterized.Parameters(name = "{0}:{1}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(
            new Object[][] { { "Dimension", 0 }, { "Dimension", 1 }, { "Dimension", 2 }, { "Dimension", 3 },
                { "Dimension", 4 }, { "Dimension", 5 }, { "X", 0 }, { "X", 1 }, { "X", 2 }, { "X", 3 }, { "X", 4 },
                { "X", 5 }, { "Y", 0 }, { "Y", 1 }, { "Y", 2 }, { "Y", 3 }, { "Y", 4 }, { "Y", 5 }, { "Z", 0 },
                { "Z", 1 }, { "Z", 2 }, { "Z", 3 }, { "Z", 4 }, { "Z", 5 } });
    }

    private final String field;
    private final int variant;

    public ShelfLocationStrictTypeTest(String field, int variant) {
        this.field = field;
        this.variant = variant;
    }

    @Test
    public void rejectsEveryLooseNumericOrMissingCoordinateType() {
        NBTTagCompound tag = new ShelfLocation(0, 1, 64, 2).write();
        switch (variant) {
            case 0:
                tag.removeTag(field);
                break;
            case 1:
                tag.setByte(field, (byte) 1);
                break;
            case 2:
                tag.setShort(field, (short) 1);
                break;
            case 3:
                tag.setLong(field, 1L);
                break;
            case 4:
                tag.setString(field, "1");
                break;
            default:
                tag.setBoolean(field, true);
        }
        try {
            ShelfLocation.read(tag);
            fail("loose coordinate type must be rejected");
        } catch (IllegalStateException expected) {}
    }
}
