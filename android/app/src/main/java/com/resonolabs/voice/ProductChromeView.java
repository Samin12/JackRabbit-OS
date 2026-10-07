package com.resonolabs.voice;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.runtime.host.BackgroundRunSnapshot;

import java.util.List;

/** One persistent native owner for product identity and Voice / Cards / T3 navigation. */
final class ProductChromeView extends View {
    static final float WIDTH = 480f;
    static final float HEIGHT = 100f;
    static final int TAB_VOICE = 0;
    static final int TAB_CARDS = 1;
    static final int TAB_T3 = 2;
    private static final RectF SEGMENTS = new RectF(100f, 28f, 380f, 76f);
    private static final String[] LABELS = {"Voice", "Cards", "T3"};
    private static final RectF GEAR = new RectF(404f, 28f, 452f, 76f);
    private static final RectF RUNNER = new RectF(28f, 28f, 76f, 76f);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FluidOrb runnerOrb = new FluidOrb().setEnergy(0.8f).setSpeed(2f);
    private final RectF pill = new RectF();
    private final Runnable openSettings;
    private final Runnable[] openTabs;
    private final Runnable openRunner;
    private int activeTab = TAB_VOICE;
    private boolean runnerVisible;
    private boolean runnerActive;
    private int t3Badge;
    private float indicator;

    ProductChromeView(Context context, Runnable openSettings, Runnable openVoice,
                      Runnable openCards, Runnable openT3, Runnable openRunner) {
        super(context);
        this.openSettings = openSettings;
        this.openTabs = new Runnable[]{openVoice, openCards, openT3};
        this.openRunner = openRunner;
        setContentDescription("Navigation. Voice, Cards and T3 tabs, and settings. Pull down for controls.");
    }

    void showRuns(List<BackgroundRunSnapshot> runs) {
        runnerVisible = !runs.isEmpty();
        runnerActive = runs.stream().anyMatch(BackgroundRunSnapshot::active);
        invalidate();
    }

    /** Selects {@link #TAB_VOICE}, {@link #TAB_CARDS} or {@link #TAB_T3}; the pill slides there. */
    void showTab(int tab) {
        activeTab = Math.max(0, Math.min(LABELS.length - 1, tab));
        invalidate();
    }

    /** Amber dot on the T3 label while threads wait on the user (hidden on the T3 tab itself). */
    void showT3Badge(int needsYou) {
        if (t3Badge == needsYou) return;
        t3Badge = Math.max(0, needsYou);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / WIDTH, getHeight() / HEIGHT);
        paint.setColor(SamTheme.withAlpha(SamTheme.INK, 60));
        canvas.drawRoundRect(222f, 8f, 258f, 12f, 2f, 2f, paint);

        SamTheme.glass(canvas, paint, SEGMENTS, 24f, false);
        float target = activeTab;
        indicator += (target - indicator) * 0.3f;
        if (Math.abs(target - indicator) < 0.01f) indicator = target;
        float third = SEGMENTS.width() / LABELS.length;
        float left = SEGMENTS.left + 4f + indicator * third;
        paint.setColor(SamTheme.withAlpha(SamTheme.INK, 235));
        pill.set(left, SEGMENTS.top + 4f, left + third - 8f, SEGMENTS.bottom - 4f);
        canvas.drawRoundRect(pill, 20f, 20f, paint);
        for (int tab = 0; tab < LABELS.length; tab++) {
            // Labels cross-fade from muted to dark as the white pill slides under them.
            float under = Math.max(0f, 1f - Math.abs(indicator - tab));
            float cx = SEGMENTS.left + third * tab + third / 2f;
            SamTheme.text(canvas, paint, LABELS[tab], cx, 59f, 18f,
                    blend(SamTheme.MUTED, SamTheme.BACKGROUND, under), Paint.Align.CENTER, true);
            if (tab == TAB_T3 && t3Badge > 0 && activeTab != TAB_T3) {
                float dotX = cx + paint.measureText(LABELS[tab]) / 2f + 8f;
                paint.setColor(SamTheme.withAlpha(SamTheme.AMBER, 70));
                canvas.drawCircle(dotX, 40f, 8f, paint);
                paint.setColor(SamTheme.AMBER);
                canvas.drawCircle(dotX, 40f, 4.5f, paint);
            }
        }

        SamTheme.glass(canvas, paint, GEAR, 24f, false);
        drawGear(canvas, 428f, 52f);
        if (runnerVisible) {
            SamTheme.glass(canvas, paint, RUNNER, 24f, runnerActive);
            runnerOrb.setSpeed(runnerActive ? 2f : 0.4f).setEnergy(runnerActive ? 0.9f : 0.1f);
            runnerOrb.draw(canvas, 52f, 52f, 13f);
        }
        canvas.restore();
        if (runnerActive || indicator != target) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float x = event.getX() * WIDTH / Math.max(1f, getWidth());
        float y = event.getY() * HEIGHT / Math.max(1f, getHeight());
        if (y > 96f) return true;
        if (runnerVisible && x <= 90f) openRunner.run();
        else if (x >= 390f) openSettings.run();
        else if (x >= SEGMENTS.left - 8f && x <= SEGMENTS.right + 8f) {
            int tab = (int) ((x - SEGMENTS.left) / (SEGMENTS.width() / LABELS.length));
            openTabs[Math.max(0, Math.min(LABELS.length - 1, tab))].run();
        }
        invalidate();
        return true;
    }

    private static int blend(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    private void drawGear(Canvas canvas, float cx, float cy) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(SamTheme.INK);
        canvas.drawCircle(cx, cy, 5f, paint);
        for (int tooth = 0; tooth < 8; tooth++) {
            double angle = Math.PI / 4.0 * tooth;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            canvas.drawLine(cx + cos * 9f, cy + sin * 9f, cx + cos * 12.5f, cy + sin * 12.5f, paint);
        }
        canvas.drawCircle(cx, cy, 9f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }
}
