package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ManagementRuntimeProxyTest {
    @Test
    public void heptabaseManagementAndCallbackRoutesAreForwarded() {
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase"));
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/connect/start"));
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/settings"));
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/disconnect"));
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/oauth/import"));
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/heptabase/oauth/callback"));
    }

    @Test
    public void macBridgeRoutesAreForwardedWithRoomForTheMacToAnswer() {
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/bridge"));
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/bridge/check"));
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/bridge/disconnect"));
        assertTrue(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/test-entry"));
        assertFalse(ManagementRuntimeProxy.isAllowed("/v1/management/heptabase/bridge/other"));
        assertEquals(25_000, ManagementRuntimeProxy.readTimeoutMillis("/v1/management/heptabase/bridge"));
        assertEquals(25_000, ManagementRuntimeProxy.readTimeoutMillis("/v1/management/heptabase/bridge/check"));
        assertEquals(15_000, ManagementRuntimeProxy.readTimeoutMillis("/v1/management/heptabase/test-entry"));
        assertEquals(8_000, ManagementRuntimeProxy.readTimeoutMillis("/v1/management/heptabase/bridge/disconnect"));
    }

    @Test
    public void deviceJournalRoutesStayLoopbackOnly() {
        assertFalse(ManagementRuntimeProxy.isAllowed("/v1/journal/status"));
        assertFalse(ManagementRuntimeProxy.isAllowed("/v1/journal/notes"));
        assertFalse(ManagementRuntimeProxy.isAllowed("/v1/heptabase/oauth/callback/extra"));
    }

    @Test
    public void networkBoundHeptabaseRoutesGetLongerTimeouts() {
        assertEquals(45_000, ManagementRuntimeProxy.readTimeoutMillis("/v1/heptabase/oauth/callback"));
        assertEquals(30_000, ManagementRuntimeProxy.readTimeoutMillis("/v1/management/heptabase/connect/start"));
        assertEquals(3_000, ManagementRuntimeProxy.readTimeoutMillis("/v1/management/heptabase"));
    }
}
