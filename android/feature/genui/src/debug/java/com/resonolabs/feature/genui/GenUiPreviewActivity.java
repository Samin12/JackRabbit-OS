package com.resonolabs.feature.genui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.resonolabs.ui.input.HardwareInputRouter;
import com.resonolabs.ui.input.UiInputIntent;

import java.nio.charset.StandardCharsets;

/**
 * Debug-only GenUI preview (src/debug; never in release builds).
 *
 * <pre>
 * adb shell am start -n com.resonolabs.voice.engineering/com.resonolabs.feature.genui.GenUiPreviewActivity --ei scene 2
 * adb shell am start -n com.resonolabs.voice.engineering/com.resonolabs.feature.genui.GenUiPreviewActivity --es json '{"id":"x","title":"Hi"}'
 * </pre>
 * Wheel = cycle cards (or scenes when the card layer doesn't use it), tap top-left/right of the
 * chrome = previous/next scene, tap the orb/controls = cycle idle/listening/speaking, BACK =
 * card back behavior, then exit.
 */
public final class GenUiPreviewActivity extends Activity {
    private static final String TAG = "GenUiPreview";
    private static GenUiPreviewActivity active;
    private GenUiPreviewView view;

    /** The resumed preview, if any (the debug receiver routes ad-hoc cards to it). */
    static GenUiPreviewActivity active() {
        return active;
    }

    GenUiPreviewView view() {
        return view;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        view = new GenUiPreviewView(this);
        setContentView(view);
        view.showScene(getIntent().getIntExtra("scene", 0));
        handleExtras(getIntent());
        view.requestFocus();
        immersive();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra("scene")) view.showScene(intent.getIntExtra("scene", 0));
        handleExtras(intent);
    }

    private void handleExtras(Intent intent) {
        String json = intent.getStringExtra("json");
        String encoded = intent.getStringExtra("b64");
        if (json == null && encoded != null) {
            json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        }
        if (json == null) return;
        GenUiController controller = view.controller();
        controller.onResponseCreated();
        Log.i(TAG, "adhoc show_card -> " + controller.execute(GenUiTools.SHOW_CARD, json, controller.store().now()));
    }

    @Override protected void onResume() {
        super.onResume();
        active = this;
        immersive();
    }

    @Override protected void onPause() {
        if (active == this) active = null;
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (view != null) view.close();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        UiInputIntent intent = HardwareInputRouter.keyIntent(event.getKeyCode());
        if (intent != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) view.input(intent);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        UiInputIntent intent = HardwareInputRouter.motionIntent(event);
        if (intent != null) return view.input(intent);
        return super.onGenericMotionEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!view.input(UiInputIntent.BACK)) finish();
    }

    private void immersive() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
