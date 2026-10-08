package com.resonolabs.runtime.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Device-only client for the runtime's Heptabase journal routes (CONTRACTS §3):
 * {@code GET /v1/journal/status} and {@code POST /v1/journal/notes {"text"}}. The runtime owns
 * every call to Heptabase; this class only talks to loopback. Callbacks arrive on the main
 * thread and never after {@link #close()}.
 */
public final class JournalClient implements AutoCloseable {
    public interface Callback {
        void onResult(JSONObject value);

        /** {@code httpStatus} 0 = runtime not reachable; {@code code} is the runtime's error code. */
        void onFailure(int httpStatus, String code);
    }

    private static final String BASE = "http://127.0.0.1:8765";
    private static final int STATUS_TIMEOUT_MS = 4_000;
    /** The runtime waits up to ~5 s for Heptabase before answering "queued". */
    private static final int NOTE_TIMEOUT_MS = 20_000;
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sam-journal");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();

    /** {@code GET /v1/journal/status}. */
    public void status(Context context, Callback callback) {
        submit(context, "GET", "/v1/journal/status", null, STATUS_TIMEOUT_MS, callback);
    }

    /** {@code POST /v1/journal/notes}: the user's own words, verbatim. */
    public void addNote(Context context, String text, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("text", text == null ? "" : text);
        } catch (Exception ignored) {
            // A String value cannot fail to encode.
        }
        submit(context, "POST", "/v1/journal/notes", body, NOTE_TIMEOUT_MS, callback);
    }

    private void submit(Context context, String method, String path, JSONObject body, int readTimeout,
                        Callback callback) {
        if (closed.get()) return;
        Context application = context.getApplicationContext();
        try {
            worker.execute(() -> perform(application, method, path, body, readTimeout, callback));
        } catch (RejectedExecutionException ignored) {
            // Closed concurrently; callbacks are suppressed after close.
        }
    }

    private void perform(Context context, String method, String path, JSONObject body, int readTimeout,
                         Callback callback) {
        HttpURLConnection connection = null;
        try {
            String token = new RuntimeSecretStore(context).loadLocalApiToken();
            connection = (HttpURLConnection) new URL(BASE + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(1_500);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            JSONObject value = parse(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 200 && status < 300) {
                if (!closed.get()) main.post(() -> { if (!closed.get()) callback.onResult(value); });
                return;
            }
            JSONObject error = value.optJSONObject("error");
            String code = error == null || error.isNull("code") ? "" : error.optString("code", "");
            fail(callback, status, code);
        } catch (ConnectException refused) {
            fail(callback, 0, "runtime_unavailable");
        } catch (java.net.SocketTimeoutException timeout) {
            fail(callback, 0, "runtime_timeout");
        } catch (Exception error) {
            fail(callback, 0, "runtime_unavailable");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject parse(InputStream source) {
        if (source == null) return new JSONObject();
        try (InputStream input = source) {
            String text = new String(input.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void fail(Callback callback, int status, String code) {
        if (!closed.get()) main.post(() -> { if (!closed.get()) callback.onFailure(status, code); });
    }

    /** Lets a note already being sent finish (the runtime queues it either way); callbacks stop. */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) worker.shutdown();
    }
}
