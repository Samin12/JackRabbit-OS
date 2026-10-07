package com.resonolabs.runtime.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-polls the runtime's generic announcement outbox
 * ({@code GET /v1/host/announcements/next?after=<cursor>&wait=25}, CONTRACTS §2) on one
 * dedicated thread and hands each new item to the main thread; {@link #ack} reports what the
 * app did with it ({@code voice}, {@code notification} or {@code seen}).
 *
 * <p>The first call (no {@code after}) establishes the cursor. The runtime answers it with the
 * recent unacknowledged items (from before this app process started, e.g. a T3 thread that
 * finished while HOME restarted); only those younger than {@link #REPLAY_WINDOW_MS} are
 * delivered, older history is dropped. Errors back off 2/5/10/30/60 s. {@link #close()} aborts
 * the in-flight request.
 */
public final class RuntimeAnnouncementClient implements AutoCloseable {
    public interface Listener {
        /** Main thread. {@code announcement} = {@code {"id","kind","title","text","payload","createdAt"}}. */
        void onAnnouncement(JSONObject announcement);
    }

    private static final String LOG_TAG = "SamAnnounce";
    private static final String BASE = "http://127.0.0.1:8765/v1/host/announcements/";
    static final int WAIT_SECONDS = 25;
    /** Must exceed the 25 s server-side wait. */
    static final int READ_TIMEOUT_MS = 40_000;
    private static final long[] BACKOFF_MS = {2_000L, 5_000L, 10_000L, 30_000L, 60_000L};
    /** Unacknowledged items this recent are still news when the app (re)starts. */
    static final long REPLAY_WINDOW_MS = 10L * 60L * 1000L;
    private static final int MAX_BODY_BYTES = 256 * 1024;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object sleeper = new Object();
    private final Thread thread;
    private final ExecutorService acks = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "sam-announce-ack");
        worker.setDaemon(true);
        return worker;
    });
    private volatile HttpURLConnection current;
    /** Last delivered id; -1 until the first sync established it. Poll thread only. */
    private long cursor = -1L;

    public RuntimeAnnouncementClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        thread = new Thread(this::loop, "sam-announcements");
        thread.setDaemon(true);
        thread.start();
    }

    /** {@code POST /v1/host/announcements/{id}/ack} (fire and forget). Ignores ids <= 0. */
    public void ack(long id, String channel) {
        if (closed.get() || id <= 0L) return;
        try {
            acks.execute(() -> post(id, channel));
        } catch (RejectedExecutionException ignored) {
            // closed concurrently
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        HttpURLConnection connection = current;
        if (connection != null) connection.disconnect();
        synchronized (sleeper) {
            sleeper.notifyAll();
        }
        thread.interrupt();
        acks.shutdownNow();
    }

    // ------------------------------------------------------------------ poll loop

    private void loop() {
        int failures = 0;
        while (!closed.get()) {
            try {
                if (cursor < 0L) {
                    JSONObject first = get("next?wait=0", 5_000);
                    cursor = Math.max(0L, first.optLong("cursor", 0L));
                    JSONArray pending = first.optJSONArray("announcements");
                    long now = System.currentTimeMillis();
                    int replayed = 0;
                    int total = pending == null ? 0 : pending.length();
                    for (int index = 0; index < total; index++) {
                        JSONObject item = pending.optJSONObject(index);
                        if (item == null || item.optLong("id", 0L) <= 0L
                                || !replayable(item.optString("createdAt", ""), now)) continue;
                        deliver(item);
                        replayed++;
                    }
                    Log.i(LOG_TAG, "synced at cursor " + cursor + " (" + replayed + " of " + total
                            + " unacknowledged item(s) replayed)");
                } else {
                    JSONObject body = get("next?after=" + cursor + "&wait=" + WAIT_SECONDS, READ_TIMEOUT_MS);
                    JSONArray items = body.optJSONArray("announcements");
                    long reported = body.optLong("cursor", cursor);
                    for (int index = 0; items != null && index < items.length(); index++) {
                        JSONObject item = items.optJSONObject(index);
                        if (item == null || item.optLong("id", 0L) <= cursor) continue;
                        deliver(item);
                    }
                    cursor = nextCursor(cursor, reported, items == null ? 0 : items.length());
                }
                failures = 0;
            } catch (Exception error) {
                if (closed.get()) break;
                long delay = backoffMs(failures++);
                if (failures == 1 || failures % 10 == 0) {
                    Log.w(LOG_TAG, "poll failed (" + error.getClass().getSimpleName() + "); retry in " + delay + " ms");
                }
                sleep(delay);
            }
        }
    }

    /**
     * Cursor after a poll. Items advance it to their newest id (reported by the runtime). With
     * no items the runtime echoes our cursor, or, if our cursor is ahead of its newest id
     * (its database was reset), its newest id: take that so new items are not skipped.
     */
    static long nextCursor(long current, long reported, int itemCount) {
        if (reported < 0L) return current;
        if (itemCount > 0) return Math.max(current, reported);
        return reported;
    }

    /** An unacknowledged item from before this process started is replayed only while recent. */
    static boolean replayable(String createdAt, long nowMillis) {
        if (createdAt == null || createdAt.isBlank()) return false;
        try {
            long created = java.time.Instant.parse(createdAt.trim()).toEpochMilli();
            return nowMillis - created <= REPLAY_WINDOW_MS && created - nowMillis <= 60_000L;
        } catch (java.time.format.DateTimeParseException invalid) {
            return false;
        }
    }

    static long backoffMs(int failures) {
        return BACKOFF_MS[Math.max(0, Math.min(BACKOFF_MS.length - 1, failures))];
    }

    private void deliver(JSONObject item) {
        if (closed.get()) return;
        main.post(() -> {
            if (!closed.get()) listener.onAnnouncement(item);
        });
    }

    private void sleep(long millis) {
        synchronized (sleeper) {
            if (closed.get()) return;
            try {
                sleeper.wait(millis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ http

    private JSONObject get(String path, int readTimeout) throws Exception {
        HttpURLConnection connection = open(path, readTimeout);
        current = connection;
        try {
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalStateException("http " + status);
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(input.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8));
            }
        } finally {
            current = null;
            connection.disconnect();
        }
    }

    private void post(long id, String channel) {
        HttpURLConnection connection = null;
        try {
            connection = open(id + "/ack", 5_000);
            byte[] body = new JSONObject().put("channel", channel).toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != 200) Log.w(LOG_TAG, "ack " + id + " " + channel + " -> http " + status);
        } catch (Exception error) {
            Log.w(LOG_TAG, "ack " + id + " failed: " + error.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, int readTimeout) throws Exception {
        String token = new RuntimeSecretStore(context).loadLocalApiToken();
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setConnectTimeout(1_500);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }
}
