package com.resonolabs.feature.genui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;

/**
 * Debug-only stand-in for the Voice page: same background, orb, status text, chrome and
 * controls geometry as {@code VoicePageView}, wired to a {@link GenCardOverlay} exactly the way
 * wave 2 will wire the real page (see INTEGRATION.md). Scenes live in {@link GenUiPreviewScenes}.
 */
final class GenUiPreviewView extends View implements GenUiController.Host {
    private static final String TAG = "GenUiPreview";
    private static final float WIDTH = 480f;
    private static final float HEIGHT = 640f;
    private static final float BAR_Y = 588f;
    private static final float[] BAR_X = {64f, 152f, 240f, 328f, 416f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FluidOrb orb = new FluidOrb();
    private final RectF rect = new RectF();
    private GenUiPreviewScenes.Scene scene;
    private int sceneIndex;
    private GenCardStore store;
    private LiveSourceRegistry registry;
    private GenUiController controller;
    private GenCardOverlay overlay;
    private GenUiPreviewScenes.Session session = GenUiPreviewScenes.Session.LIVE;
    private boolean immersive;
    private float orbRadius = 96f;
    private float orbY;
    private String banner;
    private long bannerAt;
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private boolean overlayGesture;

    GenUiPreviewView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("GenUI preview");
    }

    GenUiController controller() {
        return controller;
    }

    int sceneIndex() {
        return sceneIndex;
    }

    int sceneCount() {
        return GenUiPreviewScenes.ALL.length;
    }

    GenUiPreviewScenes.Scene scene() {
        return scene;
    }

    String sceneName() {
        return scene == null ? "" : scene.name;
    }

    void showScene(int index) {
        close();
        sceneIndex = Math.floorMod(index, GenUiPreviewScenes.ALL.length);
        scene = GenUiPreviewScenes.ALL[sceneIndex];
        session = scene.session;
        store = GenCardStore.inMemory(new GenCardStore.SystemClockSource(), new GenCardStore.MainScheduler());
        registry = new LiveSourceRegistry(getContext(), store, new GenUiPreviewSources(scene.realSources));
        controller = new GenUiController(store, registry, this);
        overlay = new GenCardOverlay(controller);
        controller.onSessionStarted("preview");
        for (String card : scene.cards) {
            controller.onResponseCreated();
            String output = controller.execute(GenUiTools.SHOW_CARD, card, store.now());
            Log.i(TAG, "scene " + scene.name + " show -> " + output);
        }
        tweak();
        Log.i(TAG, "scene " + sceneIndex + " " + scene.name);
        invalidate();
    }

    /** Scene-specific state that the tools can't express (mid-countdown timers, expanded mode). */
    private void tweak() {
        long now = store.now();
        GenCard timer = store.find("timer-pasta");
        if (timer != null && timer.timerBlock() != null) {
            GenBlock block = timer.timerBlock();
            block.endsAt = now + 299_000L;
            block.totalMs = 540_000L;
            registry.onTimerChanged(timer);
            store.liveChanged(timer);
            if ("timer-done".equals(scene.name)) store.timerDone(timer);
        }
        if ("expanded".equals(scene.name)) overlay.expand(store.front());
        if ("live-deck".equals(scene.name)) controller.onSessionEnded();
        for (GenCard card : store.activeCards()) card.arrivedAt = 0L;
    }

    void close() {
        if (registry != null) registry.close();
        if (controller != null) controller.close();
        registry = null;
        controller = null;
    }

    void nextScene(int direction) {
        showScene(sceneIndex + direction);
    }

    boolean input(UiInputIntent intent) {
        if (overlay != null && overlay.onInput(intent)) {
            invalidate();
            return true;
        }
        if (intent == UiInputIntent.NEXT || intent == UiInputIntent.PREVIOUS) {
            nextScene(intent == UiInputIntent.NEXT ? 1 : -1);
            return true;
        }
        if (intent == UiInputIntent.ACTIVATE) {
            cycleSession();
            return true;
        }
        return false;
    }

    private void cycleSession() {
        session = switch (session) {
            case IDLE -> GenUiPreviewScenes.Session.LIVE;
            case LIVE -> GenUiPreviewScenes.Session.RESPONDING;
            case RESPONDING -> GenUiPreviewScenes.Session.IDLE;
        };
        if (session == GenUiPreviewScenes.Session.IDLE) controller.onSessionEnded();
        invalidate();
    }

    private boolean live() {
        return session != GenUiPreviewScenes.Session.IDLE;
    }

    // ------------------------------------------------------------------ Host

    @Override public boolean sendUserText(String text) {
        Log.i(TAG, "sendUserText: " + text);
        if (!live()) return false;
        flash("You: " + text);
        return true;
    }

    @Override public boolean sendSystemNote(String text, boolean respond) {
        Log.i(TAG, "sendSystemNote(respond=" + respond + "): " + text);
        flash(text);
        return live();
    }

    @Override public void open(String page) {
        Log.i(TAG, "open: " + page);
        flash("Open " + page);
    }

    @Override public void startSessionWith(String text) {
        Log.i(TAG, "startSessionWith: " + text);
        session = GenUiPreviewScenes.Session.LIVE;
        flash("Start voice: " + text);
    }

    @Override public void invalidateUi() {
        postInvalidate();
    }

    @Override public void setImmersive(boolean immersive) {
        this.immersive = immersive;
    }

    @Override public void onTimerFinished(GenCard card) {
        Log.i(TAG, "timer finished: " + card.id);
    }

    private void flash(String text) {
        banner = text;
        bannerAt = SystemClock.uptimeMillis();
        invalidate();
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() * WIDTH / Math.max(1f, getWidth());
        float y = event.getY() * HEIGHT / Math.max(1f, getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = x;
                downY = y;
                lastY = y;
                dragging = false;
                overlayGesture = overlay != null && overlay.contains(x, y);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (overlayGesture && (dragging || Math.abs(y - downY) > 12f)) {
                    dragging = true;
                    overlay.onDrag(lastY - y);
                    invalidate();
                }
                lastY = y;
            }
            case MotionEvent.ACTION_UP -> {
                if (overlay != null) overlay.onDragEnd();
                if (!dragging && Math.abs(x - downX) < 24f && Math.abs(y - downY) < 24f) tap(x, y);
                dragging = false;
            }
            default -> { }
        }
        return true;
    }

    private void tap(float x, float y) {
        if (!immersive && y < 100f) {
            nextScene(x < 240f ? -1 : 1);
            return;
        }
        if (overlay.onTap(x, y)) {
            invalidate();
            return;
        }
        if (y >= BAR_Y - 40f) {
            cycleSession();
            return;
        }
        if (y <= overlay.stackTop()) cycleSession();
    }

    // ------------------------------------------------------------------ draw

    @Override protected void onDraw(Canvas canvas) {
        if (overlay == null) return;
        long now = store.now();
        canvas.save();
        canvas.scale(getWidth() / WIDTH, getHeight() / HEIGHT);
        overlay.layout(live(), false);
        GenCardOverlay.Dock dock = overlay.dock();

        float base = switch (session) {
            case IDLE -> 96f;
            case LIVE -> 104f;
            case RESPONDING -> 112f;
        };
        float target = Math.min(base, overlay.orbRadiusCap());
        orbRadius += (target - orbRadius) * 0.14f;
        float center = overlay.orbCenter();
        orbY = orbY == 0f ? center : orbY + (center - orbY) * 0.14f;
        orb.setColor(SamTheme.ORB_BLUE)
                .setEnergy(session == GenUiPreviewScenes.Session.RESPONDING ? 1f : live() ? 0.6f : 0.15f)
                .setSpeed(session == GenUiPreviewScenes.Session.RESPONDING ? 1.9f : live() ? 1.1f : 0.6f);
        boolean small = dock == GenCardOverlay.Dock.CARDS || dock == GenCardOverlay.Dock.EXPANDED;
        float y = orbY + orb.bob(small ? 2f : 5f);
        float glow = dock == GenCardOverlay.Dock.EXPANDED ? 70f : small ? 150f : 250f;
        SamTheme.background(canvas, paint, WIDTH, HEIGHT, 240f, y, glow, orb.color());
        orb.draw(canvas, 240f, y, orbRadius);

        String status = switch (session) {
            case IDLE -> "Tap to talk";
            case LIVE -> "Listening";
            case RESPONDING -> "Speaking";
        };
        String detail = switch (session) {
            case IDLE -> "Tap the orb or press the side button";
            case LIVE -> "I’m listening";
            case RESPONDING -> "Tap the orb to stop it talking";
        };
        switch (dock) {
            case NONE -> {
                SamTheme.text(canvas, paint, status, 240f, 440f, 28f, SamTheme.INK, Paint.Align.CENTER, true);
                SamTheme.text(canvas, paint, detail, 240f, 474f, 17f, SamTheme.MUTED, Paint.Align.CENTER, false);
            }
            case COMPACT -> {
                SamTheme.text(canvas, paint, status, 240f, 404f, 28f, SamTheme.INK, Paint.Align.CENTER, true);
                SamTheme.text(canvas, paint, detail, 240f, 436f, 17f, SamTheme.MUTED, Paint.Align.CENTER, false);
            }
            case CARDS, EXPANDED -> SamTheme.text(canvas, paint, status, overlay.statusX(orbRadius), y + 5f, 14f,
                    SamTheme.MUTED, Paint.Align.LEFT, false);
        }
        overlay.draw(canvas, now);
        if (!immersive) drawChrome(canvas);
        drawControls(canvas);
        drawBanner(canvas);
        canvas.restore();
        if (isShown()) postInvalidateDelayed(33L);
    }

    private void drawChrome(Canvas canvas) {
        paint.setColor(SamTheme.withAlpha(SamTheme.INK, 60));
        canvas.drawRoundRect(222f, 8f, 258f, 12f, 2f, 2f, paint);
        rect.set(130f, 28f, 350f, 76f);
        SamTheme.glass(canvas, paint, rect, 24f, false);
        paint.setColor(SamTheme.withAlpha(SamTheme.INK, 235));
        canvas.drawRoundRect(134f, 32f, 236f, 72f, 20f, 20f, paint);
        SamTheme.text(canvas, paint, "Voice", 187f, 59f, 18f, SamTheme.BACKGROUND, Paint.Align.CENTER, true);
        SamTheme.text(canvas, paint, "Cards", 293f, 59f, 18f, SamTheme.MUTED, Paint.Align.CENTER, true);
        rect.set(404f, 28f, 452f, 76f);
        SamTheme.glass(canvas, paint, rect, 24f, false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(SamTheme.INK);
        canvas.drawCircle(428f, 52f, 5f, paint);
        for (int tooth = 0; tooth < 8; tooth++) {
            double angle = Math.PI / 4.0 * tooth;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            canvas.drawLine(428f + cos * 9f, 52f + sin * 9f, 428f + cos * 12.5f, 52f + sin * 12.5f, paint);
        }
        canvas.drawCircle(428f, 52f, 9f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawControls(Canvas canvas) {
        round(canvas, 0, false, false);
        line(canvas, BAR_X[0]);
        if (!live()) {
            rect.set(112f, BAR_Y - 30f, 456f, BAR_Y + 30f);
            SamTheme.glass(canvas, paint, rect, 30f, false);
            mic(canvas, 160f, SamTheme.ORB_PALE);
            SamTheme.text(canvas, paint, "Start talking", 300f, BAR_Y + 6f, 18f, SamTheme.INK, Paint.Align.CENTER, true);
            return;
        }
        round(canvas, 1, false, false);
        mic(canvas, BAR_X[1], SamTheme.INK);
        round(canvas, 2, false, false);
        paint.setColor(session == GenUiPreviewScenes.Session.RESPONDING ? SamTheme.INK : SamTheme.withAlpha(SamTheme.INK, 80));
        canvas.drawRoundRect(BAR_X[2] - 8f, BAR_Y - 8f, BAR_X[2] + 8f, BAR_Y + 8f, 3f, 3f, paint);
        round(canvas, 3, false, false);
        speaker(canvas, BAR_X[3]);
        round(canvas, 4, true, false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(SamTheme.BACKGROUND);
        canvas.drawLine(BAR_X[4] - 9f, BAR_Y - 9f, BAR_X[4] + 9f, BAR_Y + 9f, paint);
        canvas.drawLine(BAR_X[4] + 9f, BAR_Y - 9f, BAR_X[4] - 9f, BAR_Y + 9f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void round(Canvas canvas, int slot, boolean white, boolean selected) {
        float cx = BAR_X[slot];
        rect.set(cx - 30f, BAR_Y - 30f, cx + 30f, BAR_Y + 30f);
        if (white) {
            paint.setColor(SamTheme.INK);
            canvas.drawOval(rect, paint);
        } else {
            SamTheme.glass(canvas, paint, rect, 30f, selected);
        }
    }

    private void line(Canvas canvas, float cx) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(SamTheme.INK);
        canvas.drawLine(cx - 11f, BAR_Y - 6f, cx + 11f, BAR_Y - 6f, paint);
        canvas.drawLine(cx - 11f, BAR_Y + 1f, cx + 11f, BAR_Y + 1f, paint);
        canvas.drawLine(cx - 11f, BAR_Y + 8f, cx + 3f, BAR_Y + 8f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void mic(Canvas canvas, float cx, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        canvas.drawRoundRect(cx - 5.5f, BAR_Y - 13f, cx + 5.5f, BAR_Y + 3f, 5.5f, 5.5f, paint);
        canvas.drawArc(cx - 10f, BAR_Y - 7f, cx + 10f, BAR_Y + 9f, 0f, 180f, false, paint);
        canvas.drawLine(cx, BAR_Y + 9f, cx, BAR_Y + 13f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void speaker(Canvas canvas, float cx) {
        paint.setColor(SamTheme.INK);
        canvas.drawRect(cx - 12f, BAR_Y - 5f, cx - 6f, BAR_Y + 5f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - 6f, BAR_Y - 5f, cx + 1f, BAR_Y - 11f, paint);
        canvas.drawLine(cx - 6f, BAR_Y + 5f, cx + 1f, BAR_Y + 11f, paint);
        canvas.drawLine(cx + 1f, BAR_Y - 11f, cx + 1f, BAR_Y + 11f, paint);
        canvas.drawArc(cx - 4f, BAR_Y - 8f, cx + 10f, BAR_Y + 8f, -50f, 100f, false, paint);
        canvas.drawArc(cx - 4f, BAR_Y - 14f, cx + 16f, BAR_Y + 14f, -50f, 100f, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawBanner(Canvas canvas) {
        if (banner == null) return;
        long age = SystemClock.uptimeMillis() - bannerAt;
        if (age > 2_600L) {
            banner = null;
            return;
        }
        rect.set(20f, 8f, 460f, 40f);
        paint.setColor(SamTheme.withAlpha(SamTheme.PANEL_RAISED, 245));
        canvas.drawRoundRect(rect, 16f, 16f, paint);
        SamTheme.glass(canvas, paint, rect, 16f, true);
        String text = banner.length() > 58 ? banner.substring(0, 57) + "…" : banner;
        SamTheme.text(canvas, paint, text, 240f, 29f, 13f, SamTheme.INK, Paint.Align.CENTER, false);
    }
}
