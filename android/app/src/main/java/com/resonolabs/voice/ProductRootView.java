package com.resonolabs.voice;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.resonolabs.feature.voice.VoicePageView;
import com.resonolabs.feature.settings.SettingsPanelView;
import com.resonolabs.feature.settings.ManagementPairingSource;
import com.resonolabs.feature.cards.CardsPageView;
import com.resonolabs.runtime.host.ManagementOpenAiSource;
import com.resonolabs.feature.camera.CameraHandoffPage;
import com.resonolabs.hardware.motor.R1MotorServiceClient;
import com.resonolabs.ui.input.HardwareInputRouter;
import com.resonolabs.ui.input.UiInputIntent;
import com.resonolabs.feature.backgroundrun.BackgroundRunPanelView;
import com.resonolabs.runtime.host.RuntimeBackgroundRunClient;
import com.resonolabs.runtime.host.RuntimeCreationImportClient;
import com.resonolabs.feature.creationimport.CreationImportView;

final class ProductRootView extends FrameLayout {
    private final VoicePageView voice;
    private final SettingsPanelView settings;
    private final CardsPageView cards;
    private final ProductChromeView chrome;
    private final R1MotorServiceClient motor;
    private final CameraHandoffPage camera;
    private final BackgroundRunPanelView runner;
    private final CreationImportView creationImport;
    private final ControlCenterView controlCenter;
    private boolean settingsOpen;
    private boolean cardsOpen;
    private boolean cameraOpen;
    private boolean cameraHandoffOpen;
    private boolean cardContentOpen;
    private boolean runnerOpen;
    private boolean creationImportOpen;
    private float gestureDownX;
    private float gestureDownY;
    private boolean horizontalGesture;
    private boolean pullGesture;

    ProductRootView(
            Activity activity,
            Runnable restart,
            ManagementPairingSource managementPairing,
            ManagementOpenAiSource openAiSource,
            RuntimeBackgroundRunClient backgroundRuns,
            RuntimeCreationImportClient creationImports
    ) {
        super(activity);
        motor = new R1MotorServiceClient(activity);
        voice = new VoicePageView(activity, this::openCameraHandoff);
        camera = new CameraHandoffPage(activity, motor, voice, this::returnFromCamera);
        camera.setVisibility(GONE);
        cards = new CardsPageView(activity, this::openVoice, this::showCreation);
        cards.setVisibility(GONE);
        chrome = new ProductChromeView(activity, this::openSettings, this::openVoice,
                this::openCards, this::openRunner);
        runner = new BackgroundRunPanelView(activity, backgroundRuns, chrome::showRuns,
                this::closeRunner);
        runner.setVisibility(GONE);
        creationImport = new CreationImportView(activity, motor, creationImports, this::closeCreationImport);
        creationImport.setVisibility(GONE);
        settings = new SettingsPanelView(activity, this::closeSettings, restart,
                this::openCreationImport, managementPairing, openAiSource);
        settings.setVisibility(GONE);
        addView(voice, match());
        addView(cards, match());
        addView(camera, match());
        addView(runner, match());
        addView(creationImport, match());
        LayoutParams chromeParams = new LayoutParams(LayoutParams.MATCH_PARENT, (int) ProductChromeView.HEIGHT);
        addView(chrome, chromeParams);
        addView(settings, match());
        controlCenter = new ControlCenterView(activity, this::openSettingsFromControls, this::controlCenterClosed);
        addView(controlCenter, match());
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("SamRabbit HOME");
        runner.start();
    }

    private LayoutParams match() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    private void openSettings() {
        settingsOpen = true;
        voice.setVisibility(GONE);
        cards.setVisibility(GONE);
        cards.stop();
        chrome.setVisibility(GONE);
        settings.setVisibility(VISIBLE);
        settings.requestFocus();
    }

    private void openRunner() {
        runnerOpen = true;
        voice.setVisibility(GONE); cards.setVisibility(GONE); chrome.setVisibility(GONE);
        runner.setVisibility(VISIBLE); runner.opened(); runner.requestFocus();
    }

    private void closeRunner() {
        runnerOpen = false; runner.setVisibility(GONE); chrome.setVisibility(VISIBLE);
        if (cardsOpen) { cards.setVisibility(VISIBLE); cards.start(); cards.requestFocus(); }
        else { voice.setVisibility(VISIBLE); voice.requestFocus(); }
    }

    private void closeSettings() {
        settingsOpen = false;
        settings.setVisibility(GONE);
        chrome.setVisibility(VISIBLE);
        if (cardsOpen) {
            cards.setVisibility(VISIBLE);
            cards.start();
            cards.requestFocus();
        } else {
            voice.setVisibility(VISIBLE);
            voice.requestFocus();
        }
    }

    private void openCreationImport() {
        creationImportOpen = true;
        settings.setVisibility(GONE);
        creationImport.setVisibility(VISIBLE);
        creationImport.start();
        creationImport.requestFocus();
    }

    private void closeCreationImport() {
        creationImportOpen = false;
        creationImport.stop();
        creationImport.setVisibility(GONE);
        settings.setVisibility(VISIBLE);
        settings.requestFocus();
    }

    private void openCards() {
        cardsOpen = true;
        chrome.showCards(true);
        voice.setVisibility(GONE);
        cards.setVisibility(VISIBLE);
        cards.start();
        cards.requestFocus();
    }

    private void openVoice() {
        cardsOpen = false;
        chrome.showCards(false);
        cards.stop();
        cards.setVisibility(GONE);
        voice.setVisibility(VISIBLE);
        voice.requestFocus();
    }

    private void showCreation(boolean visible) {
        cardContentOpen = visible;
        chrome.setVisibility(visible ? GONE : VISIBLE);
    }

    private void openCameraHandoff() {
        cameraOpen = true;
        cameraHandoffOpen = true;
        voice.setVisibility(GONE); cards.setVisibility(GONE); chrome.setVisibility(GONE);
        camera.setVisibility(VISIBLE); camera.startHandoff(); camera.requestFocus();
    }

    private void openCamera() {
        if (settingsOpen || cameraOpen) return;
        cameraOpen = true;
        voice.setVisibility(GONE); cards.setVisibility(GONE); chrome.setVisibility(GONE);
        camera.setVisibility(VISIBLE); camera.startPreview(); camera.requestFocus();
    }

    private void returnFromCamera() {
        cameraOpen = false;
        cameraHandoffOpen = false;
        camera.setVisibility(GONE); chrome.setVisibility(cardContentOpen ? GONE : VISIBLE);
        if (cardsOpen) {
            cards.setVisibility(VISIBLE); cards.start(); cards.requestFocus();
        } else {
            voice.setVisibility(VISIBLE); voice.requestFocus();
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (controlCenter.isOpen() || settingsOpen || runnerOpen || creationImportOpen) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                gestureDownX = event.getX();
                gestureDownY = event.getY();
                horizontalGesture = false;
                pullGesture = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                float dx = event.getX() - gestureDownX;
                float dy = event.getY() - gestureDownY;
                float scale = getHeight() / 640f;
                if (!cameraOpen && gestureDownY <= 130f * scale && dy >= 48f * scale
                        && dy > Math.abs(dx) * 1.4f) {
                    pullGesture = true;
                    return true;
                }
                if (Math.abs(dx) >= 42f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    horizontalGesture = true;
                    return true;
                }
            }
            default -> { }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (pullGesture) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                pullGesture = false;
                openControlCenter();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pullGesture = false;
            }
            return true;
        }
        if (!horizontalGesture) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - gestureDownX;
            if (dx <= -72f && !cameraOpen) openCamera();
            else if (dx >= 72f && cameraOpen) {
                camera.stop();
                returnFromCamera();
            }
            horizontalGesture = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            horizontalGesture = false;
        }
        return true;
    }

    boolean onHardwareKey(KeyEvent event) {
        UiInputIntent intent = HardwareInputRouter.keyIntent(event.getKeyCode());
        if (intent == null) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) dispatch(intent);
        return true;
    }

    boolean onHardwareMotion(MotionEvent event) {
        UiInputIntent intent = HardwareInputRouter.motionIntent(event);
        if (intent == null) return false;
        dispatch(intent);
        return true;
    }

    void openControlCenter() {
        if (controlCenter.isOpen() || cameraOpen || creationImportOpen) return;
        controlCenter.bringToFront();
        controlCenter.show();
    }

    private void openSettingsFromControls() {
        if (runnerOpen) closeRunner();
        if (!settingsOpen) openSettings();
    }

    private void controlCenterClosed() {
        if (settingsOpen) settings.requestFocus();
        else if (runnerOpen) runner.requestFocus();
        else if (cardsOpen) cards.requestFocus();
        else voice.requestFocus();
    }

    boolean navigateBack() {
        if (controlCenter.isOpen()) return controlCenter.onInput(UiInputIntent.BACK);
        if (creationImportOpen) return creationImport.onInput(UiInputIntent.BACK);
        if (runnerOpen) return runner.onInput(UiInputIntent.BACK);
        if (cameraOpen) {
            camera.stop();
            returnFromCamera();
            return true;
        }
        if (settingsOpen) return settings.onInput(UiInputIntent.BACK);
        if (cardsOpen) return cards.onInput(UiInputIntent.BACK);
        return true;
    }

    /**
     * Side-button double press (see SideButtonGesture). A live session is stopped from wherever
     * the user is, even mid-reply; otherwise every panel/overlay is closed, Voice is shown and a
     * session starts. Returns true when a session was started.
     */
    boolean toggleVoiceFromSideButton() {
        if (voice.isInSession()) {
            stopVoiceSession();
            return false;
        }
        showVoicePage();
        voice.toggleSession();
        return true;
    }

    /** Cold start through the alias: the gesture can only mean "start". */
    boolean startVoiceFromSideButton() {
        return !voice.isInSession() && toggleVoiceFromSideButton();
    }

    /**
     * The screen went off (single side-button press). While asleep the HOME process drops to
     * TOP_SLEEPING without microphone capability, so a live session would keep running deaf.
     * Returns true when a session was ended.
     */
    boolean stopVoiceForScreenOff() {
        if (!voice.isInSession()) return false;
        stopVoiceSession();
        return true;
    }

    private void stopVoiceSession() {
        // The camera hand-off belongs to the session being ended; leave it with the session.
        if (cameraOpen && cameraHandoffOpen) { camera.stop(); returnFromCamera(); }
        voice.toggleSession();
    }

    private void showVoicePage() {
        if (controlCenter.isOpen()) controlCenter.hide();
        if (creationImportOpen) closeCreationImport();
        if (runnerOpen) closeRunner();
        if (cameraOpen) { camera.stop(); returnFromCamera(); }
        if (settingsOpen) closeSettings();
        if (cardsOpen) {
            // Unwind calendar/tasks/creation first so the chrome comes back together with Voice.
            for (int depth = 0; depth < 4 && cardContentOpen; depth++) cards.onInput(UiInputIntent.BACK);
            if (cardsOpen) openVoice();
        }
    }

    private void dispatch(UiInputIntent intent) {
        if (controlCenter.isOpen()) controlCenter.onInput(intent);
        else if (creationImportOpen) creationImport.onInput(intent);
        else if (runnerOpen) runner.onInput(intent);
        else if (settingsOpen) settings.onInput(intent);
        else if (cardsOpen) cards.onInput(intent);
        else voice.onInput(intent);
    }

    void close() {
        voice.close();
        cards.close();
        camera.close();
        creationImport.close();
        runner.stop();
        motor.close();
    }
}
