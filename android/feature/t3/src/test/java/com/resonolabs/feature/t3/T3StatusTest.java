package com.resonolabs.feature.t3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class T3StatusTest {
    @Test
    public void statusMapsToOrbTone() {
        assertEquals(T3Status.Tone.ATTENTION, T3Status.tone("needs-approval", false));
        assertEquals(T3Status.Tone.ATTENTION, T3Status.tone("needs-input", true));
        assertEquals(T3Status.Tone.WORKING, T3Status.tone("working", false));
        assertEquals(T3Status.Tone.ERROR, T3Status.tone("error", false));
        assertEquals(T3Status.Tone.FRESH, T3Status.tone("done", true));
        assertEquals(T3Status.Tone.QUIET, T3Status.tone("done", false));
        assertEquals(T3Status.Tone.QUIET, T3Status.tone(null, true));
    }

    @Test
    public void tonesHaveDistinctColors() {
        assertEquals(T3Status.AMBER, T3Status.color("needs-approval", false));
        assertEquals(T3Status.BLUE, T3Status.color("working", false));
        assertEquals(T3Status.GREEN, T3Status.color("done", true));
        assertEquals(T3Status.QUIET, T3Status.color("done", false));
        assertEquals(T3Status.RED, T3Status.color("error", true));
        assertEquals(5, java.util.Set.of(T3Status.AMBER, T3Status.BLUE, T3Status.GREEN, T3Status.QUIET,
                T3Status.RED).size());
    }

    @Test
    public void runtimeLabelWinsOverDefaults() {
        assertEquals("Waiting on you", T3Status.label("needs-input", " Waiting on you "));
        assertEquals("Needs approval", T3Status.label("needs-approval", null));
        assertEquals("Has a question", T3Status.label("needs-input", ""));
        assertEquals("Working", T3Status.label("working", "  "));
        assertEquals("Failed", T3Status.label("error", null));
        assertEquals("Done", T3Status.label("done", null));
        assertEquals("Idle", T3Status.label("mystery", null));
    }

    @Test
    public void rankFollowsContractOrder() {
        assertTrue(T3Status.rank("needs-approval") < T3Status.rank("needs-input"));
        assertTrue(T3Status.rank("needs-input") < T3Status.rank("working"));
        assertTrue(T3Status.rank("working") < T3Status.rank("error"));
        assertTrue(T3Status.rank("error") < T3Status.rank("done"));
        assertTrue(T3Status.rank("done") < T3Status.rank(null));
        assertTrue(T3Status.needsYou("needs-input"));
        assertFalse(T3Status.needsYou("working"));
    }
}
