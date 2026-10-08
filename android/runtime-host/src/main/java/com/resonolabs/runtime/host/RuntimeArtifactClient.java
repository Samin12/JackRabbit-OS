package com.resonolabs.runtime.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches the R1 rendering of a Mac-generated UI from the runtime
 * ({@code GET /v1/ui/artifacts/<id>/image}, a JPEG of at most 150 KB proxied from the Mac
 * bridge; CONTRACTS-WAVE3 §4/§5). One request at a time on its own thread; results on the main
 * thread. A runtime without the route answers 404 ({@link Callback#onFailure} "not_found").
 */
public final class RuntimeArtifactClient implements AutoCloseable {
    public interface Callback {
        /** Main thread. */
        void onImage(byte[] bytes, String mime);

        /** Main thread. {@code reason} = not_found, too_large, not_image, http_<n> or unavailable. */
        void onFailure(String reason);
    }

    private static final String LOG_TAG = "SamArtifacts";
    private static final String BASE = "http://127.0.0.1:8765/v1/ui/artifacts/";
    /** Contract: ≤ 150 KB; leave room for a slightly larger rendering, never more than a blob. */
    static final int MAX_IMAGE_BYTES = 400_000;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sam-ui-artifacts");
        thread.setDaemon(true);
        return thread;
    });

    public RuntimeArtifactClient(Context context) {
        this.context = context.getApplicationContext();
    }

    /** True for ids the bridge can mint (letters, digits, - and _; at most 80). */
    public static boolean validId(String artifactId) {
        if (artifactId == null || artifactId.isEmpty() || artifactId.length() > 80) return false;
        for (int index = 0; index < artifactId.length(); index++) {
            char c = artifactId.charAt(index);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    public void fetchImage(String artifactId, Callback callback) {
        if (!validId(artifactId)) {
            deliverFailure(callback, "invalid_id");
            return;
        }
        try {
            worker.execute(() -> request(artifactId, callback));
        } catch (RejectedExecutionException ignored) {
            // closed
        }
    }

    private void request(String artifactId, Callback callback) {
        HttpURLConnection connection = null;
        try {
            String token = new RuntimeSecretStore(context).loadLocalApiToken();
            connection = (HttpURLConnection) new URL(BASE + artifactId + "/image").openConnection();
            connection.setConnectTimeout(1_500);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "image/jpeg, image/*");
            int status = connection.getResponseCode();
            if (status != 200) {
                deliverFailure(callback, status == 404 ? "not_found" : "http_" + status);
                return;
            }
            String mime = connection.getContentType();
            mime = mime == null ? "image/jpeg" : mime.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (!mime.startsWith("image/")) {
                deliverFailure(callback, "not_image");
                return;
            }
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                bytes = input.readNBytes(MAX_IMAGE_BYTES + 1);
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                deliverFailure(callback, "too_large");
                return;
            }
            if (bytes.length == 0) {
                deliverFailure(callback, "not_image");
                return;
            }
            final String finalMime = mime;
            if (!closed.get()) main.post(() -> { if (!closed.get()) callback.onImage(bytes, finalMime); });
        } catch (Exception error) {
            Log.w(LOG_TAG, "artifact image failed: " + error.getClass().getSimpleName());
            deliverFailure(callback, "unavailable");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void deliverFailure(Callback callback, String reason) {
        if (!closed.get()) main.post(() -> { if (!closed.get()) callback.onFailure(reason); });
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) worker.shutdownNow();
    }
}
