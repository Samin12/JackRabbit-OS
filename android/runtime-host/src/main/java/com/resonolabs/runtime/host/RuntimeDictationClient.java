package com.resonolabs.runtime.host;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Speech-to-text calls for typed fields: {@code POST /v1/voice/dictation/calls} trades the
 * WebRTC offer for OpenAI's answer. The runtime opens a session that only transcribes the
 * microphone (no reply, no tools). Callbacks arrive on the main thread, never after close().
 */
public final class RuntimeDictationClient implements AutoCloseable {
    private static final String LOG_TAG = "SamDictation";

    public interface Callback {
        /** {@code mode} is the session shape OpenAI accepted ("transcription" or "realtime"). */
        void onAnswer(String sdp, String sessionId, String mode);
        /** {@code reason} is a runtime error code, optionally followed by ":" and its message. */
        void onFailure(String reason);
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sam-runtime-dictation");
        thread.setDaemon(true);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();

    public void createCall(Context context, String offerSdp, Callback callback) {
        Context application = context.getApplicationContext();
        worker.execute(() -> request(application, offerSdp, callback));
    }

    private void request(Context context, String offerSdp, Callback callback) {
        HttpURLConnection connection = null;
        try {
            String token = new RuntimeSecretStore(context).loadLocalApiToken();
            connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:8765/v1/voice/dictation/calls").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1500);
            // The runtime may try more than one session shape on first use.
            connection.setReadTimeout(45_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.getOutputStream().write(new JSONObject().put("sdp", offerSdp).toString()
                    .getBytes(StandardCharsets.UTF_8));
            int status = connection.getResponseCode();
            InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            JSONObject payload = new JSONObject(new String(
                    source == null ? new byte[0] : source.readNBytes(524_288), StandardCharsets.UTF_8));
            if (status != 200) {
                JSONObject error = payload.optJSONObject("error");
                String code = error == null ? "dictation-failed" : error.optString("code", "dictation-failed");
                String message = error == null ? "" : error.optString("message", "");
                Log.w(LOG_TAG, "dictation call rejected status=" + status + " code=" + code);
                deliver(() -> callback.onFailure(message.isBlank() ? code : code + ":" + message));
                return;
            }
            String answer = payload.optString("sdp", "");
            if (!answer.startsWith("v=0")) {
                deliver(() -> callback.onFailure("answer-invalid"));
                return;
            }
            String sessionId = payload.optString("sessionId", "");
            String mode = payload.optString("mode", "");
            Log.i(LOG_TAG, "dictation call accepted mode=" + mode + " model="
                    + payload.optString("transcriptionModel", ""));
            deliver(() -> callback.onAnswer(answer, sessionId, mode));
        } catch (Exception error) {
            Log.w(LOG_TAG, "dictation call failed", error);
            deliver(() -> callback.onFailure("runtime-unavailable"));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void deliver(Runnable runnable) {
        if (!closed.get()) main.post(() -> {
            if (!closed.get()) runnable.run();
        });
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) worker.shutdownNow();
    }
}
