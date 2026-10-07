package com.resonolabs.feature.compose;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Microphone-only WebRTC peer for dictation. Same plumbing as the voice session's
 * NativeVoicePeer (microphone track + "oai-events" data channel), minus everything about
 * playback: the speaker stays muted, the audio mode and speakerphone are left alone, and only a
 * transient audio focus is held while the microphone is open. All callbacks run on the main
 * thread; {@link #close()} releases the microphone and is idempotent.
 */
final class DictationPeer {
    private static final String LOG_TAG = "SamDictation";

    interface Listener {
        void onOffer(String sdp);
        void onLive();
        void onEvent(String json);
        void onFailure(String reason);
    }

    private static final Object FACTORY_LOCK = new Object();
    private static boolean initialized;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JavaAudioDeviceModule audioDevice;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private DataChannel dataChannel;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private boolean offerDelivered;
    private boolean live;
    private boolean captureStopped;
    private boolean closed;
    /** Smoothed microphone level 0..1, written on the audio thread. */
    private volatile float level;

    DictationPeer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        try {
            synchronized (FACTORY_LOCK) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                            .builder(context).setEnableInternalTracer(false).createInitializationOptions());
                    initialized = true;
                }
            }
            audioManager = context.getSystemService(AudioManager.class);
            if (audioManager != null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(change -> { })
                        .build();
                audioManager.requestAudioFocus(focus);
            }
            audioDevice = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(
                            JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                    .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                    .setSamplesReadyCallback(this::measure)
                    .createAudioDeviceModule();
            audioDevice.setSpeakerMute(true);
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDevice).createPeerConnectionFactory();
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory.createPeerConnection(config, new PeerObserver());
            if (peer == null) throw new IllegalStateException("peer creation failed");
            audioSource = factory.createAudioSource(new MediaConstraints());
            audioTrack = factory.createAudioTrack("sam-dictation-microphone", audioSource);
            peer.addTrack(audioTrack, Collections.singletonList("sam-dictation"));
            DataChannel.Init init = new DataChannel.Init();
            init.ordered = true;
            dataChannel = peer.createDataChannel("oai-events", init);
            if (dataChannel == null) throw new IllegalStateException("data channel creation failed");
            dataChannel.registerObserver(new DataObserver());
            peer.createOffer(new OfferObserver(), new MediaConstraints());
        } catch (Exception error) {
            Log.w(LOG_TAG, "dictation peer start failed", error);
            fail("peer-start-failed");
        }
    }

    void applyAnswer(String sdp) {
        if (closed || peer == null) return;
        peer.setRemoteDescription(new SimpleSdpObserver() {
            @Override public void onSetFailure(String error) {
                main.post(() -> fail("answer-rejected"));
            }
        }, new SessionDescription(SessionDescription.Type.ANSWER, sdp));
    }

    boolean send(JSONObject event) {
        if (closed || dataChannel == null || dataChannel.state() != DataChannel.State.OPEN) return false;
        byte[] bytes = event.toString().getBytes(StandardCharsets.UTF_8);
        return dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(bytes), false));
    }

    /** Stops sending speech (the track sends silence) without ending the call. */
    void muteMicrophone() {
        if (closed) return;
        if (audioTrack != null) audioTrack.setEnabled(false);
        level = 0f;
    }

    /**
     * Releases the microphone itself (stops the OS capture) while the call stays open, so words
     * already heard are still transcribed. A muted track keeps the capture running; this is for
     * when someone else needs the microphone now (a voice session starting).
     */
    void stopCapture() {
        if (closed || captureStopped) return;
        captureStopped = true;
        muteMicrophone();
        if (peer != null) peer.setAudioRecording(false);
        Log.i(LOG_TAG, "dictation capture stopped early; words in flight still arrive");
    }

    float level() {
        return closed ? 0f : level;
    }

    void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        level = 0f;
        if (audioTrack != null) audioTrack.setEnabled(false);
        if (dataChannel != null) {
            dataChannel.unregisterObserver();
            dataChannel.close();
            dataChannel.dispose();
        }
        if (peer != null) {
            peer.close();
            peer.dispose();
        }
        if (audioTrack != null) audioTrack.dispose();
        if (audioSource != null) audioSource.dispose();
        if (factory != null) factory.dispose();
        if (audioDevice != null) audioDevice.release();
        if (audioManager != null && focus != null) audioManager.abandonAudioFocusRequest(focus);
        Log.i(LOG_TAG, "dictation peer closed; microphone released");
    }

    private void measure(JavaAudioDeviceModule.AudioSamples samples) {
        byte[] data = samples.getData();
        if (data == null || data.length < 2) return;
        long sum = 0L;
        int count = data.length / 2;
        for (int index = 0; index + 1 < data.length; index += 2) {
            int sample = (short) ((data[index] & 0xff) | (data[index + 1] << 8));
            sum += (long) sample * sample;
        }
        double rms = Math.sqrt(sum / (double) Math.max(1, count)) / 32768.0;
        // ~-50 dBFS .. -10 dBFS mapped to 0..1, then smoothed (fast attack, slow release).
        float target = (float) Math.max(0.0, Math.min(1.0, (20.0 * Math.log10(Math.max(rms, 1e-6)) + 50.0) / 40.0));
        float current = level;
        level = target > current ? current + (target - current) * 0.6f : current + (target - current) * 0.12f;
    }

    private void fail(String reason) {
        if (closed) return;
        Log.w(LOG_TAG, "dictation peer failure reason=" + reason);
        close();
        listener.onFailure(reason);
    }

    private void deliverOffer() {
        if (closed || peer == null || offerDelivered) return;
        SessionDescription local = peer.getLocalDescription();
        if (local == null || local.description == null || local.description.isBlank()) {
            fail("local-sdp-missing");
            return;
        }
        offerDelivered = true;
        listener.onOffer(local.description);
    }

    private final class OfferObserver extends SimpleSdpObserver {
        @Override public void onCreateSuccess(SessionDescription offer) {
            main.post(() -> {
                if (closed || peer == null) return;
                peer.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() {
                        // Gathering COMPLETE usually comes first; this is the fallback.
                        main.postDelayed(DictationPeer.this::deliverOffer, 800L);
                    }

                    @Override public void onSetFailure(String error) {
                        main.post(() -> fail("local-sdp-rejected"));
                    }
                }, offer);
            });
        }

        @Override public void onCreateFailure(String error) {
            main.post(() -> fail("offer-failed"));
        }
    }

    private final class PeerObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            if (state == PeerConnection.IceConnectionState.FAILED) main.post(() -> fail("ice-failed"));
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) { }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) main.post(DictationPeer.this::deliverOffer);
        }
        @Override public void onIceCandidate(IceCandidate candidate) { }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }
        @Override public void onAddStream(MediaStream stream) { }
        @Override public void onRemoveStream(MediaStream stream) { }
        @Override public void onDataChannel(DataChannel channel) { }
        @Override public void onRenegotiationNeeded() { }
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            // Nothing should play: dictation calls never answer, and the speaker is muted.
            if (receiver.track() != null) receiver.track().setEnabled(false);
        }
    }

    private final class DataObserver implements DataChannel.Observer {
        @Override public void onBufferedAmountChange(long previousAmount) { }

        @Override public void onStateChange() {
            DataChannel.State state = dataChannel == null ? null : dataChannel.state();
            main.post(() -> {
                if (closed) return;
                if (state == DataChannel.State.OPEN && !live) {
                    live = true;
                    listener.onLive();
                } else if (state == DataChannel.State.CLOSED) {
                    fail("data-channel-closed");
                }
            });
        }

        @Override public void onMessage(DataChannel.Buffer buffer) {
            if (buffer.binary || buffer.data.remaining() > 262_144) return;
            ByteBuffer source = buffer.data.slice();
            byte[] bytes = new byte[source.remaining()];
            source.get(bytes);
            String json = new String(bytes, StandardCharsets.UTF_8);
            main.post(() -> {
                if (!closed) listener.onEvent(json);
            });
        }
    }

    private abstract static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription description) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String error) { }
        @Override public void onSetFailure(String error) { }
    }
}
