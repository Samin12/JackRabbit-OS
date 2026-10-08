package com.resonolabs.runtime.host;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Live, observe-only mirror of the Voice conversation to the on-device runtime (CONTRACTS-WAVE3
 * hop 1): {@code POST /v1/voice/conversation/events} (batched JSON) and
 * {@code POST /v1/voice/conversation/blobs} (raw image bytes, named by their sha256).
 *
 * <p>Everything runs on this client's own single thread, so a slow or missing runtime never
 * holds up the UI thread, tool calls or finalize (those use {@link RuntimeVoiceClient}'s
 * worker). Events wait in a bounded memory queue ({@link ConversationSyncQueue}); blobs are
 * uploaded before the events that name them. A route the runtime does not have (404) is
 * dropped quietly and logged once; the queue is not persisted (the runtime lives on the same
 * device). Sync must never affect the conversation itself: every failure ends here.
 */
public final class ConversationSyncClient implements AutoCloseable {
    static final String EVENTS_PATH = "/v1/voice/conversation/events";
    static final String BLOBS_PATH = "/v1/voice/conversation/blobs";
    private static final String BASE = "http://127.0.0.1:8765";
    private static final String LOG_TAG = "SamConversationSync";
    /** After a 404 the route is assumed missing for this long (events are dropped meanwhile). */
    static final long MISSING_ROUTE_MS = 60_000L;
    static final int MAX_BLOBS = 6;
    static final int MAX_BLOB_BYTES = 400_000;
    static final int MAX_BLOB_ATTEMPTS = 6;

    /** HTTP to the runtime (status code, or -1 when nothing came back). */
    interface Transport {
        int post(String path, String contentType, byte[] body, String conversationId) throws IOException;
    }

    /** The sync thread. */
    interface Scheduler {
        void execute(Runnable task);

        Object schedule(Runnable task, long delayMs);

        void cancel(Object handle);

        void shutdown();
    }

    interface Clock {
        long now();
    }

    interface Logger {
        void info(String line);
    }

    private static final class Blob {
        final byte[] bytes;
        final String mime;
        final String conversationId;
        int attempts;

        Blob(byte[] bytes, String mime, String conversationId) {
            this.bytes = bytes;
            this.mime = mime;
            this.conversationId = conversationId;
        }
    }

    private final Transport transport;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Logger logger;
    // ---- sync thread only ----
    private final ConversationSyncQueue queue = new ConversationSyncQueue();
    private final ArrayDeque<Blob> blobs = new ArrayDeque<>();
    private Object pump;
    private long pumpAt = Long.MAX_VALUE;
    private long nextAttemptAt;
    private long eventsMissingUntil;
    private long blobsMissingUntil;
    private boolean eventsMissingLogged;
    private boolean blobsMissingLogged;
    private boolean draining;
    private volatile boolean closed;

    public ConversationSyncClient(Context context) {
        this(new HttpTransport(context.getApplicationContext()), new ThreadScheduler(),
                System::currentTimeMillis, line -> Log.i(LOG_TAG, line));
    }

    ConversationSyncClient(Transport transport, Scheduler scheduler, Clock clock, Logger logger) {
        this.transport = transport;
        this.scheduler = scheduler;
        this.clock = clock;
        this.logger = logger;
    }

    /** {@code "sha256:<hex>"} of the bytes: the blob id every hop uses. */
    public static String blobId(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes == null ? new byte[0] : bytes);
            StringBuilder out = new StringBuilder(7 + digest.length * 2).append("sha256:");
            for (byte value : digest) {
                out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Queues one event (any thread; the JSON must not change afterwards). It must carry
     * {@code conversationId} and {@code type}; {@code sessionId} may be empty while connecting.
     * {@code urgent} sends it (and everything before it) right away: session ends, images.
     */
    public void emit(JSONObject event, boolean urgent) {
        if (event == null || closed) return;
        long queuedAt = clock.now();
        run(() -> {
            String conversationId = event.optString("conversationId", "");
            if (conversationId.isEmpty()) return;
            queue.add(new ConversationSyncQueue.Entry(conversationId, event.optString("sessionId", ""),
                    event.optString("type", ""), event.optString("messageId", null), event.toString(),
                    urgent, queuedAt));
            kick();
        });
    }

    /**
     * Queues image bytes for {@code POST /v1/voice/conversation/blobs} and returns their blob id
     * (computed here, so the {@code image} event can name it at once). Null when refused (too
     * large or not an image).
     */
    public String uploadBlob(byte[] bytes, String mime, String conversationId) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BLOB_BYTES || mime == null
                || !mime.startsWith("image/") || conversationId == null || conversationId.isEmpty()) {
            return null;
        }
        String id = blobId(bytes);
        if (closed) return id;
        run(() -> {
            blobs.addLast(new Blob(bytes, mime, conversationId));
            while (blobs.size() > MAX_BLOBS) blobs.pollFirst();
            kick();
        });
        return id;
    }

    /** Sends whatever waits now (session end). */
    public void flush() {
        run(() -> {
            queue.forceFlush();
            kick();
        });
    }

    /** Sends what is queued once (no retries), then stops the thread. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            scheduler.execute(() -> {
                draining = true;
                cancelPump();
                pump();
            });
        } catch (RejectedExecutionException ignored) {
            // already shut down
        }
        scheduler.shutdown();
    }

    private void run(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException ignored) {
            // closed concurrently
        }
    }

    // ------------------------------------------------------------------ sync thread

    private void kick() {
        long now = clock.now();
        long delay;
        if (!blobs.isEmpty()) {
            delay = 0L;
        } else {
            delay = queue.flushDelay(now);
            if (delay < 0L) return;
        }
        delay = Math.max(delay, nextAttemptAt - now);
        schedulePump(delay);
    }

    private void schedulePump(long delayMs) {
        long at = clock.now() + Math.max(0L, delayMs);
        if (pump != null && pumpAt <= at) return;
        cancelPump();
        pumpAt = at;
        pump = scheduler.schedule(this::pumpTask, Math.max(0L, delayMs));
    }

    private void cancelPump() {
        if (pump != null) scheduler.cancel(pump);
        pump = null;
        pumpAt = Long.MAX_VALUE;
    }

    private void pumpTask() {
        pump = null;
        pumpAt = Long.MAX_VALUE;
        pump();
    }

    /** One delivery pass: blobs first, then every due batch. Reschedules itself as needed. */
    void pump() {
        long now = clock.now();
        if (!draining && now < nextAttemptAt) {
            schedulePump(nextAttemptAt - now);
            return;
        }
        if (!sendBlobs(now)) return;
        while (true) {
            long delay = queue.flushDelay(clock.now());
            if (delay < 0L) return;
            if (delay > 0L && !draining) {
                schedulePump(delay);
                return;
            }
            if (clock.now() < eventsMissingUntil) {
                queue.clear();
                queue.takeDropped();
                return;
            }
            ConversationSyncQueue.Batch batch = queue.nextBatch();
            if (batch == null) return;
            int status = post(EVENTS_PATH, "application/json",
                    ConversationSyncQueue.body(batch).getBytes(StandardCharsets.UTF_8), batch.conversationId);
            if (status >= 200 && status < 300) {
                queue.complete(batch, true);
                nextAttemptAt = 0L;
            } else if (status == 404) {
                queue.complete(batch, false);
                queue.clear();
                queue.takeDropped();
                eventsMissingUntil = clock.now() + MISSING_ROUTE_MS;
                if (!eventsMissingLogged) {
                    eventsMissingLogged = true;
                    logger.info("runtime has no " + EVENTS_PATH + " (404); conversation events are dropped");
                }
                return;
            } else if (status == 400 || status == 413 || status == 422) {
                queue.complete(batch, false);
                logger.info("conversation events rejected (http " + status + ", " + batch.size() + " dropped)");
            } else {
                queue.retry(batch);
                if (draining) {
                    queue.clear();
                    return;
                }
                long backoff = queue.backoffMs();
                nextAttemptAt = clock.now() + backoff;
                if (queue.failures() == 1 || queue.failures() % 10 == 0) {
                    logger.info("conversation events failed (" + (status < 0 ? "no answer" : "http " + status)
                            + "); retry in " + backoff + " ms");
                }
                schedulePump(backoff);
                return;
            }
        }
    }

    /** False when a blob must be retried later (the pump was rescheduled). */
    private boolean sendBlobs(long now) {
        while (!blobs.isEmpty()) {
            if (now < blobsMissingUntil) {
                blobs.clear();
                return true;
            }
            Blob blob = blobs.peekFirst();
            int status = post(BLOBS_PATH, blob.mime, blob.bytes, blob.conversationId);
            if (status >= 200 && status < 300) {
                blobs.pollFirst();
            } else if (status == 404) {
                blobs.clear();
                blobsMissingUntil = clock.now() + MISSING_ROUTE_MS;
                if (!blobsMissingLogged) {
                    blobsMissingLogged = true;
                    logger.info("runtime has no " + BLOBS_PATH + " (404); images are not mirrored");
                }
                return true;
            } else if (status == 400 || status == 413 || status == 415 || status == 422
                    || ++blob.attempts >= MAX_BLOB_ATTEMPTS || draining) {
                blobs.pollFirst();
                logger.info("conversation image dropped (" + (status < 0 ? "no answer" : "http " + status) + ")");
            } else {
                long backoff = ConversationSyncQueue.backoffMs(blob.attempts);
                nextAttemptAt = clock.now() + backoff;
                schedulePump(backoff);
                return false;
            }
        }
        return true;
    }

    private int post(String path, String contentType, byte[] body, String conversationId) {
        try {
            return transport.post(path, contentType, body, conversationId);
        } catch (IOException | RuntimeException error) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ test hooks

    int queuedEvents() {
        return queue.size();
    }

    int queuedBlobs() {
        return blobs.size();
    }

    // ------------------------------------------------------------------ Android plumbing

    private static final class HttpTransport implements Transport {
        private final Context context;

        HttpTransport(Context context) {
            this.context = context;
        }

        @Override public int post(String path, String contentType, byte[] body, String conversationId)
                throws IOException {
            String token;
            try {
                token = new RuntimeSecretStore(context).loadLocalApiToken();
            } catch (Exception unavailable) {
                throw new IOException("local token unavailable");
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(1_500);
                connection.setReadTimeout(8_000);
                connection.setUseCaches(false);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setRequestProperty("Accept", "application/json");
                if (conversationId != null && !conversationId.isEmpty()) {
                    connection.setRequestProperty("X-SAM-Conversation", conversationId);
                }
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                int status = connection.getResponseCode();
                InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (source != null) {
                    try (InputStream input = source) {
                        input.readNBytes(16_384); // drain (small JSON answer) so the socket closes cleanly
                    }
                }
                return status;
            } finally {
                connection.disconnect();
            }
        }
    }

    private static final class ThreadScheduler implements Scheduler {
        private final ScheduledThreadPoolExecutor executor;

        ThreadScheduler() {
            executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "sam-conversation-sync");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }

        @Override public void execute(Runnable task) {
            executor.execute(task);
        }

        @Override public Object schedule(Runnable task, long delayMs) {
            return executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }

        @Override public void cancel(Object handle) {
            if (handle instanceof ScheduledFuture<?> future) future.cancel(false);
        }

        @Override public void shutdown() {
            executor.shutdown();
        }
    }
}
