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
import com.resonolabs.feature.t3.T3PageView;
import com.resonolabs.feature.compose.ComposeSheet;

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
    private final T3PageView t3;
    private boolean t3Open;
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
    /** Swipe right from the left edge = Back (Android's own gestures and nav bar are switched off). */
    private boolean edgeBackGesture;

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
        // Text fields dictate through their own mic; never while a voice session holds it.
        ComposeSheet.setVoiceSessionProbe(voice::isInSession);
        camera = new CameraHandoffPage(activity, motor, voice, this::returnFromCamera);
        camera.setVisibility(GONE);
        cards = new CardsPageView(activity, this::openVoice, this::showCreation);
        cards.setVisibility(GONE);
        t3 = new T3PageView(activity, new T3PageView.Host() {
            @Override public void talkToThread(String threadId, String title) {
                ProductRootView.this.talkToThread(threadId, title);
            }

            @Override public void talkToNewThread(String projectId, String projectTitle) {
                ProductRootView.this.talkToNewThread(projectId, projectTitle);
            }

            @Override public void openSettings() {
                ProductRootView.this.openSettings();
            }

            @Override public void showChrome(boolean visible) {
                if (t3Open && !settingsOpen && !runnerOpen && !cameraOpen) {
                    chrome.setVisibility(visible ? VISIBLE : GONE);
                }
            }

            @Override public void needsYou(int count) {
                chrome.showT3Badge(count);
            }
        });
        t3.setVisibility(GONE);
        chrome = new ProductChromeView(activity, this::openSettings, this::openVoice,
                this::openCards, this::openT3, this::openRunner);
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
        addView(t3, match());
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
        t3.stop(); // Hidden: only the slow badge poll runs until the T3 tab opens.
    }

    private LayoutParams match() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    private void openSettings() {
        settingsOpen = true;
        voice.setVisibility(GONE);
        cards.setVisibility(GONE);
        cards.stop();
        hideT3();
        chrome.setVisibility(GONE);
        settings.setVisibility(VISIBLE);
        settings.requestFocus();
    }

    private void openRunner() {
        runnerOpen = true;
        voice.setVisibility(GONE); cards.setVisibility(GONE); hideT3(); chrome.setVisibility(GONE);
        runner.setVisibility(VISIBLE); runner.opened(); runner.requestFocus();
    }

    private void closeRunner() {
        runnerOpen = false; runner.setVisibility(GONE); chrome.setVisibility(VISIBLE);
        restoreTab();
    }

    private void closeSettings() {
        settingsOpen = false;
        settings.setVisibility(GONE);
        chrome.setVisibility(VISIBLE);
        restoreTab();
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

    private void openT3() {
        if (t3Open) return;
        if (cardsOpen) {
            cardsOpen = false;
            cards.stop();
            cards.setVisibility(GONE);
        }
        t3Open = true;
        chrome.showTab(ProductChromeView.TAB_T3);
        voice.setVisibility(GONE);
        showT3();
    }

    private void closeT3() {
        if (!t3Open) return;
        t3Open = false;
        t3.stop();
        t3.setVisibility(GONE);
        chrome.setVisibility(VISIBLE);
    }

    /** Shows the selected T3 tab; the chrome stays hidden while a thread is open. */
    private void showT3() {
        t3.setVisibility(VISIBLE);
        t3.start();
        t3.requestFocus();
        chrome.setVisibility(t3.detailOpen() ? GONE : VISIBLE);
    }

    /** Hide (but keep selected) the T3 tab while an overlay page owns the screen. */
    private void hideT3() {
        if (!t3Open) return;
        t3.stop();
        t3.setVisibility(GONE);
    }

    /** Re-show whichever top-level tab was selected before an overlay page opened. */
    private void restoreTab() {
        if (cardsOpen) {
            cards.setVisibility(VISIBLE);
            cards.start();
            cards.requestFocus();
        } else if (t3Open) {
            showT3();
        } else {
            voice.setVisibility(VISIBLE);
            voice.requestFocus();
        }
    }

    /**
     * T3 "Talk": switch to Voice and tell the model that what the user says next is for this
     * thread (sent with t3_send_message unless they say otherwise).
     */
    void talkToThread(String threadId, String title) {
        String name = title == null || title.isBlank() ? "Untitled thread" : title.trim();
        openVoice();
        voice.startSessionWithNote("Host note (from the R1's T3 tab, not the user's words): the user is looking at "
                + "T3 Code thread \u201c" + name + "\u201d (id " + threadId + ") and tapped Talk. Treat what "
                + "they say next as a message for that thread and send it with t3_send_message, passing thread=\""
                + threadId + "\", unless they clearly ask for something else; use t3_respond with the same thread "
                + "for its pending approvals or questions. Confirm in a few words once it is sent. Right now, "
                + "briefly ask what they want to tell that thread, then wait.");
    }

    /** T3 "Talk" on the new-thread screen: what the user says next becomes the first prompt. */
    void talkToNewThread(String projectId, String projectTitle) {
        // t3_new_thread takes the project by name ("project"), not by id.
        boolean known = projectId != null && !projectId.isBlank()
                && projectTitle != null && !projectTitle.isBlank();
        String where = known
                ? "project \u201c" + projectTitle.trim() + "\u201d"
                : "their most recently active project";
        String how = known
                ? "create it with t3_new_thread, passing the prompt and project=\"" + projectTitle.trim()
                        + "\" unless they name another project"
                : "create it with t3_new_thread, passing the prompt and no project unless they name one";
        openVoice();
        voice.startSessionWithNote("Host note (from the R1's T3 tab, not the user's words): the user wants to start "
                + "a new T3 Code thread in " + where + ". Treat what they say next as the prompt and " + how
                + ", then confirm in one short sentence. Right now, briefly ask what the new thread should do, "
                + "then wait.");
    }

    private void openCards() {
        closeT3();
        cardsOpen = true;
        chrome.showTab(ProductChromeView.TAB_CARDS);
        voice.setVisibility(GONE);
        cards.setVisibility(VISIBLE);
        cards.start();
        cards.requestFocus();
    }

    private void openVoice() {
        closeT3();
        cardsOpen = false;
        chrome.showTab(ProductChromeView.TAB_VOICE);
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
        voice.setVisibility(GONE); cards.setVisibility(GONE); hideT3(); chrome.setVisibility(GONE);
        camera.setVisibility(VISIBLE); camera.startHandoff(); camera.requestFocus();
    }

    private void openCamera() {
        if (settingsOpen || cameraOpen) return;
        cameraOpen = true;
        voice.setVisibility(GONE); cards.setVisibility(GONE); hideT3(); chrome.setVisibility(GONE);
        camera.setVisibility(VISIBLE); camera.startPreview(); camera.requestFocus();
    }

    private void returnFromCamera() {
        cameraOpen = false;
        cameraHandoffOpen = false;
        camera.setVisibility(GONE); chrome.setVisibility(cardContentOpen ? GONE : VISIBLE);
        restoreTab();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            gestureDownX = event.getX();
            gestureDownY = event.getY();
            horizontalGesture = false;
            pullGesture = false;
            edgeBackGesture = false;
        }
        if (action == MotionEvent.ACTION_MOVE && gestureDownX <= 24f * getWidth() / 480f) {
            float dx = event.getX() - gestureDownX;
            float dy = event.getY() - gestureDownY;
            if (dx >= 36f * getWidth() / 480f && dx > Math.abs(dy) * 1.2f) {
                edgeBackGesture = true;
                return true;
            }
        }
        if (controlCenter.isOpen() || settingsOpen || runnerOpen || creationImportOpen) return false;
        switch (action) {
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
        if (edgeBackGesture) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                edgeBackGesture = false;
                if (action == MotionEvent.ACTION_UP
                        && event.getX() - gestureDownX >= 72f * getWidth() / 480f) navigateBack();
            }
            return true;
        }
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
            if (cameraOpen) {
                if (dx >= 72f) {
                    camera.stop();
                    returnFromCamera();
                }
            } else if (dx <= -72f) {
                cycleTab(1);
            } else if (dx >= 72f) {
                cycleTab(-1);
            }
            horizontalGesture = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            horizontalGesture = false;
        }
        return true;
    }

    /** Horizontal swipes page through the tabs and wrap around: Voice → Cards → T3 → Voice. */
    private void cycleTab(int step) {
        if (cardContentOpen) return;   // a card page (Calendar, a creation) keeps the swipe for itself
        int current = t3Open ? 2 : cardsOpen ? 1 : 0;
        switch (Math.floorMod(current + step, 3)) {
            case 1 -> openCards();
            case 2 -> openT3();
            default -> openVoice();
        }
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
        else if (t3Open) t3.requestFocus();
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
        if (t3Open) {
            if (!t3.onInput(UiInputIntent.BACK)) openVoice();
            return true;
        }
        // Voice is the visible page: BACK closes its transcript, then ends a live session.
        return voice.onInput(UiInputIntent.BACK);
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
        if (t3Open) openVoice();
    }

    private void dispatch(UiInputIntent intent) {
        if (controlCenter.isOpen()) controlCenter.onInput(intent);
        else if (creationImportOpen) creationImport.onInput(intent);
        else if (runnerOpen) runner.onInput(intent);
        else if (settingsOpen) settings.onInput(intent);
        else if (cardsOpen) cards.onInput(intent);
        else if (t3Open) {
            if (!t3.onInput(intent) && intent == UiInputIntent.BACK) openVoice();
        }
        else voice.onInput(intent);
    }

    void close() {
        voice.close();
        cards.close();
        t3.close();
        camera.close();
        creationImport.close();
        runner.stop();
        motor.close();
    }
}
