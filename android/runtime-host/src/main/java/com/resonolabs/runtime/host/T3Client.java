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
 * Authenticated device-only client for the runtime's {@code /v1/t3/*} routes (CONTRACTS §2).
 * The runtime owns every LAN call to the T3 Code server; this class only talks to loopback.
 *
 * <p>Reads (status, list, detail) run on one worker and writes on another so a slow dispatch
 * never delays the list poll. Callbacks always arrive on the main thread and never after
 * {@link #close()}.
 */
public final class T3Client implements AutoCloseable {
    /** Result of one runtime call. */
    public interface Callback {
        void onResult(JSONObject value);
        void onFailure(Failure failure);
    }

    /** Result of {@link #pollThreads}: only called with a body when the revision moved. */
    public interface PollCallback {
        void onChanged(JSONObject value);
        void onUnchanged();
        void onFailure(Failure failure);
    }

    /**
     * Transport or runtime error. {@code httpStatus == 0} means no HTTP answer: the runtime is
     * not running ({@link #runtimeUnavailable()}) or it accepted the call but did not answer in
     * time ({@link #timedOut()}).
     */
    public static final class Failure {
        public final int httpStatus;
        public final String code;
        public final String message;

        public Failure(int httpStatus, String code, String message) {
            this.httpStatus = httpStatus;
            this.code = code == null ? "" : code;
            this.message = message == null ? "" : message;
        }

        /** True for the runtime's own route-level 404 (no /v1/t3 routes installed). */
        public boolean routeMissing() {
            return httpStatus == 404 && ("not_found".equals(code) || code.isEmpty());
        }

        public boolean notConnected() {
            return httpStatus == 409 && "t3_not_connected".equals(code);
        }

        /** The runtime could not be reached at all (not running / restarting). */
        public boolean runtimeUnavailable() {
            return httpStatus == 0 && !timedOut();
        }

        /** The runtime is up but did not answer in time (usually waiting on T3 Code itself). */
        public boolean timedOut() {
            return httpStatus == 0 && TIMEOUT.equals(code);
        }

        @Override public String toString() {
            return "t3-" + httpStatus + (code.isEmpty() ? "" : ":" + code);
        }
    }

    private static final String BASE = "http://127.0.0.1:8765";
    private static final String TIMEOUT = "runtime_timeout";
    /**
     * Above the runtime's own T3 Code timeouts (6-8 s per request to T3 Code, plus waiting for an
     * in-flight sync), so a slow or asleep Mac comes back as the runtime's error, not ours.
     */
    private static final int READ_TIMEOUT_MS = 15_000;
    /** Writes may chain a pending re-check and a dispatch to T3 Code. */
    private static final int WRITE_TIMEOUT_MS = 30_000;
    private static final int MAX_BODY_BYTES = 512 * 1024;

    private final ExecutorService reads = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sam-t3-read");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService writes = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sam-t3-write");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();

    /** {@code GET /v1/t3/status}. */
    public void status(Context context, Callback callback) {
        read(context, "/v1/t3/status", callback);
    }

    /** {@code GET /v1/t3/threads?limit=N}. */
    public void threads(Context context, int limit, Callback callback) {
        read(context, "/v1/t3/threads?limit=" + clamp(limit, 1, 100), callback);
    }

    /**
     * Cheap change detection over {@code GET /v1/t3/threads}: the body is only handed to the
     * caller when {@code revision} differs from {@code knownRevision} (pass -1 to force).
     */
    public void pollThreads(Context context, int limit, long knownRevision, PollCallback callback) {
        threads(context, limit, new Callback() {
            @Override public void onResult(JSONObject value) {
                long revision = value.optLong("revision", Long.MIN_VALUE);
                if (knownRevision >= 0 && revision == knownRevision) callback.onUnchanged();
                else callback.onChanged(value);
            }

            @Override public void onFailure(Failure failure) {
                callback.onFailure(failure);
            }
        });
    }

    /** {@code GET /v1/t3/threads/{id}?turns=N}. */
    public void thread(Context context, String threadId, int turns, Callback callback) {
        read(context, threadPath(threadId) + "?turns=" + clamp(turns, 1, 20), callback);
    }

    /** {@code POST /v1/t3/threads} → {@code 202 {"threadId"}}. Null fields are omitted. */
    public void createThread(Context context, String text, String title, String projectId,
                             String project, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("text", text == null ? "" : text);
            if (title != null && !title.isBlank()) body.put("title", title);
            if (projectId != null && !projectId.isBlank()) body.put("projectId", projectId);
            if (project != null && !project.isBlank()) body.put("project", project);
        } catch (Exception error) {
            deliver(callback, new Failure(400, "invalid_request", "Could not encode request."));
            return;
        }
        write(context, "/v1/t3/threads", body, callback);
    }

    /** {@code POST /v1/t3/threads/{id}/messages}. */
    public void sendMessage(Context context, String threadId, String text, Callback callback) {
        write(context, threadPath(threadId) + "/messages", object("text", text), callback);
    }

    /** {@code POST /v1/t3/threads/{id}/approvals/{requestId}} with accept|acceptForSession|decline|cancel. */
    public void respondApproval(Context context, String threadId, String requestId, String decision,
                                Callback callback) {
        write(context, threadPath(threadId) + "/approvals/" + segment(requestId),
                object("decision", decision), callback);
    }

    /** {@code POST /v1/t3/threads/{id}/inputs/{requestId}} with {@code {"answers":{qid: str|[str]}}}. */
    public void respondInput(Context context, String threadId, String requestId, JSONObject answers,
                             Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("answers", answers == null ? new JSONObject() : answers);
        } catch (Exception error) {
            deliver(callback, new Failure(400, "invalid_request", "Could not encode answers."));
            return;
        }
        write(context, threadPath(threadId) + "/inputs/" + segment(requestId), body, callback);
    }

    /** {@code POST /v1/t3/threads/{id}/interrupt}. */
    public void interrupt(Context context, String threadId, Callback callback) {
        write(context, threadPath(threadId) + "/interrupt", new JSONObject(), callback);
    }

    /** {@code POST /v1/t3/threads/{id}/seen} (clears {@code unread}). */
    public void markSeen(Context context, String threadId, Callback callback) {
        write(context, threadPath(threadId) + "/seen", new JSONObject(), callback);
    }

    private void read(Context context, String path, Callback callback) {
        submit(reads, context, "GET", path, null, READ_TIMEOUT_MS, callback);
    }

    private void write(Context context, String path, JSONObject body, Callback callback) {
        submit(writes, context, "POST", path, body, WRITE_TIMEOUT_MS, callback);
    }

    private void submit(ExecutorService worker, Context context, String method, String path,
                        JSONObject body, int readTimeout, Callback callback) {
        if (closed.get()) return;
        Context application = context.getApplicationContext();
        try {
            worker.execute(() -> perform(application, method, path, body, readTimeout, callback));
        } catch (RejectedExecutionException ignored) {
            // Closed concurrently; callbacks are suppressed after close by contract.
        }
    }

    private void perform(Context context, String method, String path, JSONObject body,
                         int readTimeout, Callback callback) {
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
            deliver(callback, new Failure(status,
                    error == null ? "" : error.optString("code", ""),
                    error == null ? "" : error.optString("message", "")));
        } catch (ConnectException refused) {
            deliver(callback, new Failure(0, "runtime_unavailable", "SamRabbit runtime is not running."));
        } catch (java.net.SocketTimeoutException timeout) {
            deliver(callback, new Failure(0, TIMEOUT, "SamRabbit runtime did not answer."));
        } catch (Exception error) {
            deliver(callback, new Failure(0, "runtime_unavailable", "SamRabbit runtime is unavailable."));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject parse(InputStream source) {
        if (source == null) return new JSONObject();
        try (InputStream input = source) {
            byte[] bytes = input.readNBytes(MAX_BODY_BYTES);
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void deliver(Callback callback, Failure failure) {
        if (!closed.get()) main.post(() -> { if (!closed.get()) callback.onFailure(failure); });
    }

    private static JSONObject object(String key, String value) {
        JSONObject body = new JSONObject();
        try {
            body.put(key, value == null ? "" : value);
        } catch (Exception ignored) {
            // A String key/value pair cannot fail to encode.
        }
        return body;
    }

    private static String threadPath(String threadId) {
        return "/v1/t3/threads/" + segment(threadId);
    }

    /**
     * Percent-encodes one path segment. Ids are UUIDs or provider ids such as
     * {@code codex-async:<uuid>:call_x}; ':' is a legal pchar and stays readable, while
     * '/', '?', '#', '%' and anything non-ASCII are escaped.
     */
    static String segment(String value) {
        String source = value == null ? "" : value;
        StringBuilder out = new StringBuilder(source.length() + 8);
        for (byte raw : source.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xff;
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == ':';
            if (plain) out.append((char) c);
            else out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
        }
        return out.toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            reads.shutdownNow();
            writes.shutdownNow();
        }
    }
}
