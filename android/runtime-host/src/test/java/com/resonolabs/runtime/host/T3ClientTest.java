package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class T3ClientTest {
    @Test
    public void pathSegmentsKeepProviderIdsReadable() {
        assertEquals("8ce4b73c-8eb3-425e-b36a-e8cc6b94c5d6",
                T3Client.segment("8ce4b73c-8eb3-425e-b36a-e8cc6b94c5d6"));
        assertEquals("codex-async:31e4ac27:call_GfVn", T3Client.segment("codex-async:31e4ac27:call_GfVn"));
    }

    @Test
    public void pathSegmentsEscapeSeparatorsAndUnicode() {
        assertEquals("a%2Fb%3Fc%23d%25", T3Client.segment("a/b?c#d%"));
        assertEquals("a%20b", T3Client.segment("a b"));
        assertEquals("%C3%A9", T3Client.segment("é"));
        assertEquals("", T3Client.segment(null));
    }

    @Test
    public void failureClassification() {
        assertTrue(new T3Client.Failure(404, "not_found", "Not found.").routeMissing());
        assertFalse(new T3Client.Failure(404, "thread_not_found", "").routeMissing());
        assertTrue(new T3Client.Failure(409, "t3_not_connected", "").notConnected());
        assertTrue(new T3Client.Failure(0, "runtime_unavailable", "").runtimeUnavailable());
        assertEquals("t3-409:t3_not_connected", new T3Client.Failure(409, "t3_not_connected", "").toString());
    }
}
