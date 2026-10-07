package com.resonolabs.runtime.host;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class ManagementRuntimeProxy {
    private static final Set<String> ROUTES = Set.of(
            "/v1/management/pair",
            "/v1/management/status",
            "/v1/management/profile",
            "/v1/management/restart",
            "/v1/management/openai",
            "/v1/management/openai/connect",
            "/v1/management/openai/disconnect",
            "/v1/management/openai/models",
            "/v1/management/openai/provider",
            "/v1/management/openai/refresh",
            "/v1/management/openai/access",
            "/v1/host/openai",
            "/v1/host/openai/connect",
            "/v1/host/openai/disconnect",
            "/v1/host/openai/models",
            "/v1/host/openai/refresh",
            "/v1/host/openai/access",
            "/v1/host/openai/provider",
            "/v1/management/openai/subscription",
            "/v1/management/openai/subscription/start",
            "/v1/management/openai/subscription/poll",
            "/v1/management/openai/subscription/disconnect",
            "/v1/management/mail/accounts",
            "/v1/management/calendar/accounts",
            "/v1/management/connections",
            "/v1/management/skills",
            "/v1/management/skills/preflight",
            "/v1/management/skills/confirm",
            "/v1/management/plugins",
            "/v1/management/plugins/preflight",
            "/v1/management/plugins/confirm",
            "/v1/management/mcp/connections",
            "/v1/management/mcp/imports/preflight",
            "/v1/management/mcp/imports/confirm",
            "/v1/management/creations",
            "/v1/management/creations/preflight",
            "/v1/management/creations/confirm",
            "/v1/management/tools",
            "/v1/management/background-agent",
            "/v1/management/text/turns",
            "/v1/management/memory",
            "/v1/management/memory/search",
            "/v1/management/memory/reindex",
            "/v1/management/memory/sessions",
            "/v1/management/t3",
            "/v1/management/t3/connect",
            "/v1/management/t3/disconnect",
            "/v1/management/heptabase",
            "/v1/management/heptabase/connect/start",
            "/v1/management/heptabase/settings",
            "/v1/management/heptabase/disconnect",
            "/v1/management/heptabase/retry",
            "/v1/management/heptabase/oauth/import",
            "/v1/management/heptabase/bridge",
            "/v1/management/heptabase/bridge/check",
            "/v1/management/heptabase/bridge/disconnect",
            "/v1/management/heptabase/test-entry",
            // OAuth redirect target: authenticated by its single-use state, not by a session.
            "/v1/heptabase/oauth/callback");
    /** Routes that receive the request query string (everything else is forwarded path-only). */
    private static final Set<String> QUERY_ROUTES = Set.of("/v1/heptabase/oauth/callback");
    private static final Set<String> ROUTE_PREFIXES = Set.of(
            "/v1/management/mail/accounts/",
            "/v1/management/calendar/accounts/",
            "/v1/management/connections/",
            "/v1/management/skills/",
            "/v1/management/plugins/",
            "/v1/management/mcp/connections/",
            "/v1/management/creations/",
            "/v1/management/background-agent/",
            "/v1/management/memory/",
            "/v1/management/memory/sessions/");
    private final String localApiToken;

    ManagementRuntimeProxy(String localApiToken) {
        this.localApiToken = localApiToken;
    }

    ManagementHttpResponse forward(ManagementHttpRequest request) {
        if (!isAllowed(request.path())) return ManagementHttpResponse.text(404, "Not found.");
        String host = request.header("host");
        if (host == null || host.length() > 255 || !host.matches("[A-Za-z0-9.\\-\\[\\]:]+")) {
            return ManagementHttpResponse.text(400, "Invalid host.");
        }
        HttpURLConnection connection = null;
        try {
            String target = QUERY_ROUTES.contains(request.path()) && !request.query().isEmpty()
                    ? request.path() + "?" + request.query()
                    : request.path();
            connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:8765" + target).openConnection();
            connection.setRequestMethod(request.method());
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(readTimeoutMillis(request.path()));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + localApiToken);
            connection.setRequestProperty("X-SAM-Forwarded-Origin", "https://" + host);
            copyRequestHeader(request, connection, "Origin");
            copyRequestHeader(request, connection, "Cookie");
            copyRequestHeader(request, connection, "Content-Type");
            copyRequestHeader(request, connection, "X-CSRF-Token");
            copyRequestHeader(request, connection, "X-SAM-Skill-Filename");
            copyRequestHeader(request, connection, "X-SAM-Plugin-Filename");
            copyRequestHeader(request, connection, "X-SAM-Creation-Filename");
            copyRequestHeader(request, connection, "X-SAM-Agent-Audience");
            if (request.body().length > 0) {
                connection.setDoOutput(true);
                connection.getOutputStream().write(request.body());
            }
            int status = connection.getResponseCode();
            InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] body = source == null ? new byte[0] : source.readNBytes(65536);
            Map<String, String> headers = new LinkedHashMap<>();
            String cookie = connection.getHeaderField("Set-Cookie");
            if (cookie != null) headers.put("Set-Cookie", cookie);
            return new ManagementHttpResponse(
                    status,
                    connection.getContentType() == null
                            ? "application/json; charset=utf-8"
                            : connection.getContentType(),
                    Map.copyOf(headers),
                    body);
        } catch (Exception ignored) {
            return ManagementHttpResponse.text(503, "Runtime unavailable.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static boolean isAllowed(String path) {
        if (ROUTES.contains(path)) return true;
        if (path.length() <= "/v1/management/memory/".length()) return false;
        for (String prefix : ROUTE_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    static int readTimeoutMillis(String path) {
        if (path.equals("/v1/management/text/turns")) return 65_000;
        if (path.equals("/v1/heptabase/oauth/callback")) return 45_000;
        if (path.equals("/v1/management/heptabase/connect/start")
                || path.equals("/v1/management/heptabase/disconnect")
                || path.equals("/v1/management/heptabase/oauth/import")) return 30_000;
        // Mac bridge: /health runs the Heptabase CLI on the Mac (R1 waits up to 15 s); the test
        // entry waits up to 5 s for delivery.
        if (path.equals("/v1/management/heptabase/bridge")
                || path.equals("/v1/management/heptabase/bridge/check")) return 25_000;
        if (path.equals("/v1/management/heptabase/test-entry")) return 15_000;
        if (path.equals("/v1/management/heptabase/bridge/disconnect")) return 8_000;
        if (path.endsWith("/finalize")) return 65_000;
        if (path.equals("/v1/management/memory/reindex")) return 35_000;
        if (path.equals("/v1/management/t3/connect")) return 20_000;
        if (path.equals("/v1/management/t3") || path.equals("/v1/management/t3/disconnect")) return 8_000;
        if (path.startsWith("/v1/management/mail/accounts")) return 610_000;
        if (path.startsWith("/v1/management/calendar/accounts")) return 65_000;
        if (path.equals("/v1/management/openai/subscription/start")
                || path.equals("/v1/management/openai/subscription/poll")) return 35_000;
        if (path.equals("/v1/management/openai")
                || path.equals("/v1/management/openai/connect")
                || path.equals("/v1/management/openai/models")
                || path.equals("/v1/management/openai/refresh")
                || path.equals("/v1/management/openai/access")
                || path.equals("/v1/host/openai")
                || path.equals("/v1/host/openai/connect")
                || path.equals("/v1/host/openai/models")
                || path.equals("/v1/host/openai/refresh")
                || path.equals("/v1/host/openai/access")
                || path.equals("/v1/host/openai/provider")) return 30_000;
        return 3_000;
    }

    private static void copyRequestHeader(
            ManagementHttpRequest request,
            HttpURLConnection connection,
            String name) {
        String value = request.header(name);
        if (value != null) connection.setRequestProperty(name, value);
    }
}
