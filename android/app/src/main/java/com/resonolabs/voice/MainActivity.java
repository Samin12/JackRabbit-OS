package com.resonolabs.voice;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import com.resonolabs.ui.power.DisplayPolicy;
import com.resonolabs.runtime.host.RuntimeHealthClient;
import com.resonolabs.runtime.host.RuntimeManagementClient;
import com.resonolabs.runtime.host.RuntimeService;
import com.resonolabs.feature.settings.ManagementPairingState;
import com.resonolabs.runtime.host.RuntimeBackgroundRunClient;
import com.resonolabs.runtime.host.RuntimeCreationImportClient;

public final class MainActivity extends Activity {
    private ProductRootView root;
    private RuntimeHealthClient runtimeHealth;
    private RuntimeManagementClient runtimeManagement;
    private RuntimeBackgroundRunClient backgroundRuns;
    private RuntimeCreationImportClient creationImports;
    private final SideButtonGesture sideButton = new SideButtonGesture();
    /** Cold start through the side-button alias: start Voice once the runtime answers. */
    private boolean sideButtonStartPending;
    private boolean screenOffRegistered;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (root == null) return;
            // SCREEN_OFF reaches HOME ~1 s after the panel goes off. If a press already woke the
            // screen again (e.g. a quick double press that just started a new session) the mic
            // is usable again: ending the session now would kill the one the user asked for.
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null && power.isInteractive()) {
                Log.i(SideButtonGesture.LOG_TAG, "screen off (stale: screen is on again) -> ignored");
                return;
            }
            if (root.stopVoiceForScreenOff()) {
                Log.i(SideButtonGesture.LOG_TAG, "screen off -> voice stopped");
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SystemSetupState.markComplete(this);
        RuntimeService.start(this);
        runtimeHealth = new RuntimeHealthClient();
        runtimeManagement = new RuntimeManagementClient();
        backgroundRuns = new RuntimeBackgroundRunClient();
        creationImports = new RuntimeCreationImportClient();
        runtimeHealth.checkUntilReady(this, health -> {
            android.util.Log.i("SamRuntime", "HOME boundary status=" + health.status());
            if (sideButtonStartPending && root != null) {
                sideButtonStartPending = false;
                boolean started = root.startVoiceFromSideButton();
                Log.i(SideButtonGesture.LOG_TAG, "double press (cold start) -> "
                        + (started ? "voice started" : "already in session"));
            }
        });
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        root = new ProductRootView(
                this,
                this::confirmRestart,
                callback -> runtimeManagement.loadPairing(
                        this,
                        pairing -> callback.accept(
                                new ManagementPairingState(
                                        pairing.status(),
                                        pairing.code(),
                                        pairing.address(),
                                        pairing.expiresAt())
                        )
                ),
                runtimeManagement,
                backgroundRuns,
                creationImports);
        setContentView(root);
        // A fresh launch whose intent is the alias = double press while HOME was not running.
        // (A recreated activity keeps the old intent; it must not toggle again.)
        if (state == null && SideButtonGesture.isToggle(getIntent())) sideButtonStartPending = true;
        SideButtonGesture.checkFrameworkPolicy(this);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED);
        screenOffRegistered = true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 41);
        }
        // Android 16 routes Back through OnBackInvokedDispatcher; without this the HOME
        // activity is finished and recreated, dropping the user back on Voice.
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> { if (root != null) root.navigateBack(); });
        NotificationFeed.ensureEnabled(this);
        disableSystemShade();
        DisplayPolicy.apply(getWindow());
        installFullscreenPolicy();
        enterProductFullscreen();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (SideButtonGesture.isToggle(intent)) onSideButtonDoublePress();
    }

    private void onSideButtonDoublePress() {
        if (root == null) return;
        if (!sideButton.accept(SystemClock.elapsedRealtime())) {
            Log.i(SideButtonGesture.LOG_TAG, "double press ignored (duplicate delivery)");
            return;
        }
        // Posted so it runs after the resume that accompanies this intent: the microphone is
        // then opened from a TOP process (the press may have just woken the screen).
        root.post(() -> {
            boolean started = root.toggleVoiceFromSideButton();
            Log.i(SideButtonGesture.LOG_TAG, "double press -> " + (started ? "voice started" : "voice stopped"));
        });
    }

    @Override protected void onDestroy() {
        if (screenOffRegistered) {
            unregisterReceiver(screenOff);
            screenOffRegistered = false;
        }
        if (root != null) root.close();
        if (runtimeHealth != null) runtimeHealth.close();
        if (runtimeManagement != null) runtimeManagement.close();
        if (backgroundRuns != null) backgroundRuns.close();
        if (creationImports != null) creationImports.close();
        super.onDestroy();
    }

    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle("Restart R1?")
                .setMessage("SamRabbit will restart and return to HOME.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restart", (ignored, which) -> {
                    PowerManager power = getSystemService(PowerManager.class);
                    if (power != null) power.reboot("sam-settings");
                })
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        DisplayPolicy.applyInputPolicy(getWindow());
        enterProductFullscreen();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            DisplayPolicy.applyInputPolicy(getWindow());
            enterProductFullscreen();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (root != null && root.onHardwareKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (root != null && root.onHardwareMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        if (root != null) root.navigateBack();
    }

    private void enterProductFullscreen() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /** The stock shade is unusable at 480x640; the HOME Control Center replaces it. */
    private void disableSystemShade() {
        try {
            Object statusBar = getSystemService("statusbar");
            int disableExpand = 0x00010000;
            statusBar.getClass().getMethod("disable", int.class).invoke(statusBar, disableExpand);
        } catch (ReflectiveOperationException | RuntimeException error) {
            android.util.Log.w("SamChrome", "system shade stays enabled: " + (error.getCause() != null ? error.getCause() : error));
        }
    }

    private void installFullscreenPolicy() {
        View decor = getWindow().getDecorView();
        decor.setOnApplyWindowInsetsListener((view, insets) -> {
            int bars = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
            if (insets.isVisible(WindowInsets.Type.statusBars()) && root != null) {
                // A swipe from the top edge revealed the system bar: answer with our Control Center.
                view.post(root::openControlCenter);
            }
            if (insets.isVisible(bars)) view.post(this::enterProductFullscreen);
            return insets;
        });
        decor.setOnSystemUiVisibilityChangeListener(visibility -> {
            int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            if ((visibility & hidden) != hidden) decor.post(this::enterProductFullscreen);
        });
    }
}
