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
    private android.widget.FrameLayout root;
    private LiveCardsPageView deck;

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
        root = new android.widget.FrameLayout(this);
        root.addView(view);
        setContentView(root);
        showScene(getIntent().getIntExtra("scene", 0));
        handleExtras(getIntent());
        view.requestFocus();
        immersive();
        // Android 16 / targetSdk 36 routes BACK through the dispatcher, not onBackPressed().
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> { if (!input(UiInputIntent.BACK)) finish(); });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasExtra("scene")) showScene(intent.getIntExtra("scene", 0));
        handleExtras(intent);
    }

    /** Scenes render in the fake Voice page, except "live-deck" which shows Cards &gt; Live. */
    void showScene(int index) {
        if (deck != null) {
            deck.close();
            root.removeView(deck);
            deck = null;
        }
        view.showScene(index);
        if ("live-deck".equals(view.sceneName())) {
            deck = new LiveCardsPageView(this, view.controller());
            root.addView(deck);
            deck.start();
            deck.requestFocus();
            view.setVisibility(View.GONE);
        } else {
            view.setVisibility(View.VISIBLE);
            view.requestFocus();
        }
    }

    private boolean input(UiInputIntent intent) {
        if (deck != null) {
            if (deck.onInput(intent)) return true;
            if (intent == UiInputIntent.NEXT || intent == UiInputIntent.PREVIOUS) {
                showScene(view.sceneIndex() + (intent == UiInputIntent.NEXT ? 1 : -1));
                return true;
            }
            return false;
        }
        return view.input(intent);
    }

    private void handleExtras(Intent intent) {
        String json = intent.getStringExtra("json");
        String encoded = intent.getStringExtra("b64");
        if (json == null && encoded != null) {
            try {
                json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalid) {
                Log.w(TAG, "adhoc b64 is not valid base64");
            }
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

    /** Leaving the preview ends it, so no scripted or real live source keeps polling behind HOME. */
    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override protected void onDestroy() {
        if (deck != null) deck.close();
        if (view != null) view.close();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        UiInputIntent intent = HardwareInputRouter.keyIntent(event.getKeyCode());
        if (intent != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) input(intent);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        UiInputIntent intent = HardwareInputRouter.motionIntent(event);
        if (intent != null) return input(intent);
        return super.onGenericMotionEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!input(UiInputIntent.BACK)) finish();
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
