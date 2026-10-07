package com.resonolabs.feature.genui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * Debug-only ad-hoc card injection (src/debug; never in release builds).
 *
 * <pre>
 * adb shell "am broadcast -p com.resonolabs.voice.engineering -a com.resonolabs.genui.DEBUG_SHOW --es json '{\"id\":\"x\",\"title\":\"Hi\"}'"
 * adb shell am broadcast -p com.resonolabs.voice.engineering -a com.resonolabs.genui.DEBUG_SHOW --es b64 $(printf '%s' "$JSON" | base64)
 * adb shell am broadcast -p com.resonolabs.voice.engineering -a com.resonolabs.genui.DEBUG_DISMISS --es json '{"all":true}'
 * adb shell am broadcast -p com.resonolabs.voice.engineering -a com.resonolabs.genui.DEBUG_SCENE --ei scene 3
 * </pre>
 * Routed to the open preview if there is one; otherwise to the process card store (the one the
 * Voice page will show once integrated). The tool output is returned as the broadcast result.
 */
public final class GenUiDebugReceiver extends BroadcastReceiver {
    private static final String TAG = "GenUiDebug";
    private static GenUiController processController;

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction() == null ? "" : intent.getAction();
        GenUiPreviewActivity preview = GenUiPreviewActivity.active();
        if (action.endsWith("DEBUG_SCENE")) {
            if (preview != null) preview.view().showScene(intent.getIntExtra("scene", 0));
            return;
        }
        String tool = action.endsWith("DEBUG_UPDATE") ? GenUiTools.UPDATE_CARD
                : action.endsWith("DEBUG_DISMISS") ? GenUiTools.DISMISS_CARD : GenUiTools.SHOW_CARD;
        String json = intent.getStringExtra("json");
        String encoded = intent.getStringExtra("b64");
        if (json == null && encoded != null) {
            try {
                json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalid) {
                json = null;
            }
        }
        if (json == null) json = "{}";
        GenUiController controller = preview != null ? preview.view().controller() : processController(context);
        controller.onResponseCreated();
        String output = controller.execute(tool, json, controller.store().now());
        Log.i(TAG, tool + " -> " + output);
        setResultData(output);
    }

    private static synchronized GenUiController processController(Context context) {
        if (processController == null) {
            processController = new GenUiController(context.getApplicationContext(), new GenUiController.Host() {
                @Override public boolean sendUserText(String text) {
                    Log.i(TAG, "sendUserText: " + text);
                    return false;
                }

                @Override public boolean sendSystemNote(String text, boolean respond) {
                    Log.i(TAG, "sendSystemNote: " + text);
                    return false;
                }

                @Override public void open(String page) {
                    Log.i(TAG, "open: " + page);
                }

                @Override public void startSessionWith(String text) {
                    Log.i(TAG, "startSessionWith: " + text);
                }

                @Override public void invalidateUi() { }

                @Override public void setImmersive(boolean immersive) { }
            });
        }
        return processController;
    }
}
