package com.resonolabs.voice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Debug-only voice test hooks (src/debug; never in release builds). {@code -p} is required.
 *
 * <pre>
 * adb shell am broadcast -p com.resonolabs.voice.engineering -a com.resonolabs.voice.DEBUG_SAY --es text "What's on my calendar tomorrow?" [--ez quiet true]
 * adb shell am broadcast -p com.resonolabs.voice.engineering -a com.resonolabs.voice.DEBUG_ANNOUNCE --es b64 $(printf '%s' "$JSON" | base64)
 * adb shell am broadcast -p com.resonolabs.voice.engineering -a com.resonolabs.voice.DEBUG_STATE
 * </pre>
 * DEBUG_SAY sends the text as the user's turn in the live session (conversation.item.create
 * user input_text + response.create), starting a session first if none is live (the text then
 * replaces the connect greeting); {@code --ez quiet true} mutes the microphone and speaker for
 * that session first (no sound, no echo). DEBUG_ANNOUNCE routes a synthetic runtime announcement exactly
 * like a real one (ids &lt;= 0 are never acknowledged). The result data is the HOME state.
 */
public final class VoiceDebugReceiver extends BroadcastReceiver {
    private static final String TAG = "SamVoiceDebug";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction() == null ? "" : intent.getAction();
        ProductRootView root = MainActivity.activeRoot();
        if (root == null) {
            setResultData("no-home");
            return;
        }
        if (action.endsWith("DEBUG_SAY")) {
            String text = extra(intent, "text");
            if (text == null || text.isBlank()) {
                setResultData("missing --es text");
                return;
            }
            boolean quiet = intent.getBooleanExtra("quiet", false);
            Log.i(TAG, "DEBUG_SAY (" + text.length() + " chars" + (quiet ? ", quiet" : "") + ")");
            root.debugSay(text, quiet);
        } else if (action.endsWith("DEBUG_ANNOUNCE")) {
            String json = extra(intent, "json");
            try {
                root.debugAnnounce(new JSONObject(json == null ? "{}" : json));
            } catch (Exception invalid) {
                setResultData("invalid json");
                return;
            }
        }
        setResultData(root.debugState());
    }

    /** {@code --es <name>} or base64 in {@code --es b64} (for text with quotes or spaces). */
    private static String extra(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        if (value != null) return value;
        String encoded = intent.getStringExtra("b64");
        if (encoded == null) return null;
        try {
            return new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
