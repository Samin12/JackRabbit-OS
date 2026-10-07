package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class ManagementHttpRequestTest {
    @Test
    public void deleteRequestReachesManagementProxy() throws Exception {
        ManagementHttpRequest request = read(
                "DELETE /v1/management/creations/weather-app HTTP/1.1\r\n"
                        + "Host: 192.168.1.196:8443\r\n"
                        + "X-CSRF-Token: token\r\n\r\n");

        assertEquals("DELETE", request.method());
        assertEquals("/v1/management/creations/weather-app", request.path());
    }

    @Test
    public void unsupportedMutationMethodRemainsRejected() {
        assertThrows(IOException.class, () -> read(
                "PATCH /v1/management/creations/weather-app HTTP/1.1\r\n"
                        + "Host: 192.168.1.196:8443\r\n\r\n"));
    }

    @Test
    public void queryIsKeptSeparateFromThePath() throws Exception {
        ManagementHttpRequest request = read(
                "GET /v1/heptabase/oauth/callback?code=abc-123&state=xyz_9&iss=https%3A%2F%2Fapi.heptabase.com"
                        + " HTTP/1.1\r\nHost: 192.168.1.186:8443\r\n\r\n");

        assertEquals("/v1/heptabase/oauth/callback", request.path());
        assertEquals("code=abc-123&state=xyz_9&iss=https%3A%2F%2Fapi.heptabase.com", request.query());
        assertEquals("", read("GET /v1/management/status HTTP/1.1\r\nHost: h\r\n\r\n").query());
        assertEquals("", read("GET /v1/x?a=<b> HTTP/1.1\r\nHost: h\r\n\r\n").query());
    }

    private static ManagementHttpRequest read(String request) throws IOException {
        return ManagementHttpRequest.read(new ByteArrayInputStream(
                request.getBytes(StandardCharsets.US_ASCII)));
    }
}
