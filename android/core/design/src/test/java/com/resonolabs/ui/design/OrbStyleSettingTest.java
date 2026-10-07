package com.resonolabs.ui.design;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OrbStyleSettingTest {
    private final class MemoryStore implements OrbStyleSetting.Store {
        String value;
        int writes;

        MemoryStore(String value) {
            this.value = value;
        }

        @Override public String read() {
            return value;
        }

        @Override public void write(String next) {
            value = next;
            writes++;
        }
    }

    @Before public void reset() {
        OrbStyleSetting.resetForTest();
    }

    @After public void cleanUp() {
        OrbStyleSetting.resetForTest();
    }

    @Test public void parseRoundTripsEveryStyleAndDefaultsToFluid() {
        for (OrbStyle style : OrbStyle.values()) assertEquals(style, OrbStyle.parse(style.key()));
        assertEquals(OrbStyle.PIXEL_HEAD, OrbStyle.parse(" pixel_head "));
        assertEquals(OrbStyle.FLUID, OrbStyle.parse(null));
        assertEquals(OrbStyle.FLUID, OrbStyle.parse(""));
        assertEquals(OrbStyle.FLUID, OrbStyle.parse("PIXEL_HEAD"));
        assertEquals(OrbStyle.FLUID, OrbStyle.parse("rainbow"));
    }

    @Test public void keysAndLabelsAreStable() {
        assertEquals("fluid", OrbStyle.FLUID.key());
        assertEquals("pixel_head", OrbStyle.PIXEL_HEAD.key());
        assertEquals("Orb", OrbStyle.FLUID.label());
        assertEquals("Pixel head", OrbStyle.PIXEL_HEAD.label());
    }

    @Test public void startsAsFluidWithNothingStored() {
        OrbStyleSetting.attach(new MemoryStore(null));
        assertEquals(OrbStyle.FLUID, OrbStyleSetting.current());
    }

    @Test public void loadsThePersistedStyle() {
        OrbStyleSetting.attach(new MemoryStore("pixel_head"));
        assertEquals(OrbStyle.PIXEL_HEAD, OrbStyleSetting.current());
    }

    @Test public void corruptValueFallsBackToFluid() {
        OrbStyleSetting.attach(new MemoryStore("sparkles"));
        assertEquals(OrbStyle.FLUID, OrbStyleSetting.current());
    }

    @Test public void firstStoreWins() {
        OrbStyleSetting.attach(new MemoryStore("pixel_head"));
        MemoryStore later = new MemoryStore("fluid");
        OrbStyleSetting.attach(later);
        assertEquals(OrbStyle.PIXEL_HEAD, OrbStyleSetting.current());
        OrbStyleSetting.apply(OrbStyle.FLUID);
        assertEquals(0, later.writes);
    }

    @Test public void applyPersistsAndNotifiesOnlyRealChanges() {
        MemoryStore store = new MemoryStore(null);
        OrbStyleSetting.attach(store);
        List<OrbStyle> heard = new ArrayList<>();
        OrbStyleSetting.Listener listener = heard::add;
        OrbStyleSetting.addListener(listener);
        OrbStyleSetting.addListener(listener);

        OrbStyleSetting.apply(OrbStyle.PIXEL_HEAD);
        OrbStyleSetting.apply(OrbStyle.PIXEL_HEAD);
        assertEquals(OrbStyle.PIXEL_HEAD, OrbStyleSetting.current());
        assertEquals("pixel_head", store.value);
        assertEquals(1, store.writes);
        assertEquals(List.of(OrbStyle.PIXEL_HEAD), heard);

        OrbStyleSetting.apply(null);
        assertEquals(OrbStyle.FLUID, OrbStyleSetting.current());
        assertEquals("fluid", store.value);
        assertEquals(List.of(OrbStyle.PIXEL_HEAD, OrbStyle.FLUID), heard);

        OrbStyleSetting.removeListener(listener);
        OrbStyleSetting.apply(OrbStyle.PIXEL_HEAD);
        assertEquals(2, heard.size());
    }

    @Test public void applyBeforeAnyStoreStillSwitchesTheProcess() {
        List<OrbStyle> heard = new ArrayList<>();
        OrbStyleSetting.addListener(heard::add);
        OrbStyleSetting.apply(OrbStyle.PIXEL_HEAD);
        assertEquals(OrbStyle.PIXEL_HEAD, OrbStyleSetting.current());
        assertTrue(heard.contains(OrbStyle.PIXEL_HEAD));
    }

    @Test public void resetClearsState() {
        OrbStyleSetting.attach(new MemoryStore("pixel_head"));
        OrbStyleSetting.resetForTest();
        assertEquals(OrbStyle.FLUID, OrbStyleSetting.current());
        MemoryStore fresh = new MemoryStore(null);
        OrbStyleSetting.attach(fresh);
        assertNull(fresh.value);
    }
}
