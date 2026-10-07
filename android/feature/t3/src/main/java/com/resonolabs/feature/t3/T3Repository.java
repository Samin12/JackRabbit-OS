package com.resonolabs.feature.t3;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.resonolabs.runtime.host.T3Client;

import org.json.JSONObject;

/**
 * The T3 tab's single data source. Normally a thin parser over {@link T3Client}; in a debuggable
 * build with {@code debug.sam.t3.fake=1} it falls back to {@link T3FakeBackend} when the runtime
 * answers 404 for the T3 routes (they are not installed yet). Everything is delivered on the
 * main thread.
 */
final class T3Repository implements AutoCloseable {
    private static final String LOG_TAG = "SamT3";
    private static final String FAKE_PROPERTY = "debug.sam.t3.fake";
    private static final long FAKE_LATENCY_MS = 140L;

    interface Result<T> {
        void ok(T value);
        void fail(T3Client.Failure failure);
    }

    interface SnapshotResult {
        void changed(T3Model.Snapshot snapshot);
        void unchanged();
        void fail(T3Client.Failure failure);
    }

    private final Context context;
    private final T3Client client = new T3Client();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Demo backend; kept across re-probes so its state survives tab switches. */
    private T3FakeBackend demo;
    /** Non-null while serving demo data. */
    private T3FakeBackend fake;
    private boolean closed;

    T3Repository(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean fakeMode() {
        return fake != null;
    }

    /** Try the real routes again on the next call (called when the tab becomes visible). */
    void reprobe() {
        fake = null;
    }

    void status(Result<T3Model.Connection> result) {
        if (fake != null) {
            later(() -> result.ok(T3Model.Connection.from(fake.status(System.currentTimeMillis()))));
            return;
        }
        client.status(context, new T3Client.Callback() {
            @Override public void onResult(JSONObject value) {
                result.ok(T3Model.Connection.from(value));
            }

            @Override public void onFailure(T3Client.Failure failure) {
                if (enterFakeIfMissing(failure)) status(result);
                else result.fail(failure);
            }
        });
    }

    void threads(long knownRevision, SnapshotResult result) {
        if (fake != null) {
            later(() -> {
                JSONObject value = fake.threads(System.currentTimeMillis(), 40);
                if (knownRevision >= 0 && value.optLong("revision") == knownRevision) result.unchanged();
                else result.changed(T3Model.Snapshot.from(value));
            });
            return;
        }
        client.pollThreads(context, 40, knownRevision, new T3Client.PollCallback() {
            @Override public void onChanged(JSONObject value) {
                result.changed(T3Model.Snapshot.from(value));
            }

            @Override public void onUnchanged() {
                result.unchanged();
            }

            @Override public void onFailure(T3Client.Failure failure) {
                if (enterFakeIfMissing(failure)) threads(-1, result);
                else result.fail(failure);
            }
        });
    }

    void thread(String threadId, Result<T3Model.Detail> result) {
        if (fake != null) {
            later(() -> {
                JSONObject value = fake.thread(System.currentTimeMillis(), threadId);
                T3Model.Detail detail = value == null ? null : T3Model.Detail.from(value);
                if (detail == null) result.fail(new T3Client.Failure(404, "thread_not_found", "Thread not found."));
                else result.ok(detail);
            });
            return;
        }
        client.thread(context, threadId, 3, new T3Client.Callback() {
            @Override public void onResult(JSONObject value) {
                T3Model.Detail detail = T3Model.Detail.from(value);
                if (detail == null) result.fail(new T3Client.Failure(502, "invalid_response", "Thread unreadable."));
                else result.ok(detail);
            }

            @Override public void onFailure(T3Client.Failure failure) {
                if (enterFakeIfMissing(failure)) thread(threadId, result);
                else result.fail(failure);
            }
        });
    }

    void create(String text, String projectId, Result<String> result) {
        if (fake != null) {
            later(() -> result.ok(fake.create(System.currentTimeMillis(), text, projectId)));
            return;
        }
        client.createThread(context, text, null, projectId, null, new T3Client.Callback() {
            @Override public void onResult(JSONObject value) {
                String threadId = T3Model.text(value, "threadId");
                if (threadId.isEmpty()) result.fail(new T3Client.Failure(502, "invalid_response", "No thread id."));
                else result.ok(threadId);
            }

            @Override public void onFailure(T3Client.Failure failure) {
                result.fail(failure);
            }
        });
    }

    void send(String threadId, String text, Result<Boolean> result) {
        if (fake != null) {
            later(() -> done(result, fake.send(System.currentTimeMillis(), threadId, text)));
            return;
        }
        client.sendMessage(context, threadId, text, ack(result));
    }

    void approve(String threadId, String requestId, String decision, Result<Boolean> result) {
        if (fake != null) {
            later(() -> done(result, fake.approve(System.currentTimeMillis(), threadId, requestId, decision)));
            return;
        }
        client.respondApproval(context, threadId, requestId, decision, ack(result));
    }

    void answer(String threadId, String requestId, JSONObject answers, Result<Boolean> result) {
        if (fake != null) {
            later(() -> done(result, fake.answer(System.currentTimeMillis(), threadId, requestId, answers)));
            return;
        }
        client.respondInput(context, threadId, requestId, answers, ack(result));
    }

    void interrupt(String threadId, Result<Boolean> result) {
        if (fake != null) {
            later(() -> done(result, fake.interrupt(System.currentTimeMillis(), threadId)));
            return;
        }
        client.interrupt(context, threadId, ack(result));
    }

    void seen(String threadId) {
        if (fake != null) {
            fake.seen(threadId);
            return;
        }
        client.markSeen(context, threadId, new T3Client.Callback() {
            @Override public void onResult(JSONObject value) { }

            @Override public void onFailure(T3Client.Failure failure) {
                Log.d(LOG_TAG, "mark seen failed: " + failure);
            }
        });
    }

    private T3Client.Callback ack(Result<Boolean> result) {
        return new T3Client.Callback() {
            @Override public void onResult(JSONObject value) {
                result.ok(Boolean.TRUE);
            }

            @Override public void onFailure(T3Client.Failure failure) {
                result.fail(failure);
            }
        };
    }

    private static void done(Result<Boolean> result, boolean ok) {
        if (ok) result.ok(Boolean.TRUE);
        else result.fail(new T3Client.Failure(404, "request_not_found", "That request is no longer pending."));
    }

    private boolean enterFakeIfMissing(T3Client.Failure failure) {
        if (closed || fake != null || !failure.routeMissing() || !fakeAllowed()) return false;
        if (demo == null) {
            Log.i(LOG_TAG, "runtime has no /v1/t3 routes; serving demo data (" + FAKE_PROPERTY + "=1)");
            demo = new T3FakeBackend(System.currentTimeMillis());
        }
        fake = demo;
        return true;
    }

    private void later(Runnable action) {
        main.postAtTime(() -> { if (!closed) action.run(); }, SystemClock.uptimeMillis() + FAKE_LATENCY_MS);
    }

    private boolean fakeAllowed() {
        boolean debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return debuggable && "1".equals(systemProperty(FAKE_PROPERTY));
    }

    private static String systemProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class, String.class).invoke(null, key, "");
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception reflectionBlocked) {
            try {
                Process process = new ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start();
                try (java.io.InputStream input = process.getInputStream()) {
                    return new String(input.readNBytes(64), java.nio.charset.StandardCharsets.UTF_8).trim();
                } finally {
                    process.destroy();
                }
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        client.close();
    }
}
