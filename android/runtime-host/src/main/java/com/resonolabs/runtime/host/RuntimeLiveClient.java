package com.resonolabs.runtime.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authenticated device-only reader for GenUI live snapshots:
 * {@code GET /v1/live/{type}/{id}} (e.g. {@code t3-thread}). Definitive "not available"
 * answers (404 unknown thread, 409 not connected, 401/403) are reported separately from
 * transport failures so live cards can show a calm "unavailable" state instead of retrying hot.
 */
public final class RuntimeLiveClient implements AutoCloseable {
    public interface Callback {
        void onSnapshot(JSONObject snapshot);

        /** HTTP 4xx with the runtime's {@code {"error":{"code","message"}}} body (code may be empty). */
        void onUnavailable(int status, String code);

        /** Runtime unreachable, timeout, 5xx, or malformed body. */
        void onFailure();
    }

    private static final String BASE = "http://127.0.0.1:8765/v1/live/";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();

    public void load(Context context, String type, String id, Callback callback) {
        Context app = context.getApplicationContext();
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String token = new RuntimeSecretStore(app).loadLocalApiToken();
                String path = URLEncoder.encode(type, StandardCharsets.UTF_8) + "/"
                        + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
                connection = (HttpURLConnection) new URL(BASE + path).openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(4000);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Accept", "application/json");
                int status = connection.getResponseCode();
                if (status == 200) {
                    try (InputStream input = connection.getInputStream()) {
                        JSONObject value = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        post(() -> callback.onSnapshot(value));
                    }
                    return;
                }
                if (status >= 400 && status < 500) {
                    String code = "";
                    try (InputStream error = connection.getErrorStream()) {
                        if (error != null) {
                            JSONObject body = new JSONObject(new String(error.readAllBytes(), StandardCharsets.UTF_8));
                            JSONObject detail = body.optJSONObject("error");
                            if (detail != null) code = detail.optString("code", "");
                        }
                    } catch (Exception ignored) { }
                    String finalCode = code;
                    post(() -> callback.onUnavailable(status, finalCode));
                    return;
                }
                post(callback::onFailure);
            } catch (Exception ignored) {
                post(callback::onFailure);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void post(Runnable runnable) {
        if (!closed.get()) main.post(() -> { if (!closed.get()) runnable.run(); });
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) worker.shutdownNow();
    }
}
