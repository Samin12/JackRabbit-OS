package com.resonolabs.voice;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.ReSonoTheme;
import com.resonolabs.runtime.host.BackgroundRunSnapshot;

import java.util.List;

/** One persistent native owner for product identity and Voice/Cards navigation. */
final class ProductChromeView extends View {
    static final float WIDTH = 480f;
    static final float HEIGHT = 100f;
    private static final RectF SEGMENTS = new RectF(130f, 28f, 350f, 76f);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FluidOrb runnerOrb = new FluidOrb().setEnergy(0.8f).setSpeed(2f);
    private final Runnable openSettings;
    private final Runnable openVoice;
    private final Runnable openCards;
    private final Runnable openRunner;
    private boolean cardsActive;
    private boolean runnerVisible;
    private boolean runnerActive;
    private float indicator;

    ProductChromeView(Context context, Runnable openSettings, Runnable openVoice,
                      Runnable openCards, Runnable openRunner) {
        super(context);
        this.openSettings = openSettings;
        this.openVoice = openVoice;
        this.openCards = openCards;
        this.openRunner = openRunner;
        setContentDescription("Navigation. Voice tab, Cards tab, and settings. Pull down for controls.");
    }

    void showRuns(List<BackgroundRunSnapshot> runs) {
        runnerVisible = !runs.isEmpty();
        runnerActive = runs.stream().anyMatch(BackgroundRunSnapshot::active);
        invalidate();
    }

    void showCards(boolean active) {
        cardsActive = active;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / WIDTH, getHeight() / HEIGHT);
        paint.setColor(ReSonoTheme.withAlpha(ReSonoTheme.INK, 60));
        canvas.drawRoundRect(222f, 8f, 258f, 12f, 2f, 2f, paint);

        ReSonoTheme.glass(canvas, paint, SEGMENTS, 24f, false);
        float target = cardsActive ? 1f : 0f;
        indicator += (target - indicator) * 0.3f;
        if (Math.abs(target - indicator) < 0.01f) indicator = target;
        float half = SEGMENTS.width() / 2f;
        float left = SEGMENTS.left + 4f + indicator * half;
        paint.setColor(ReSonoTheme.withAlpha(ReSonoTheme.INK, 235));
        canvas.drawRoundRect(left, SEGMENTS.top + 4f, left + half - 8f, SEGMENTS.bottom - 4f,
                20f, 20f, paint);
        ReSonoTheme.text(canvas, paint, "Voice", SEGMENTS.left + half / 2f + 2f, 59f, 18f,
                cardsActive ? ReSonoTheme.MUTED : ReSonoTheme.BACKGROUND, Paint.Align.CENTER, true);
        ReSonoTheme.text(canvas, paint, "Cards", SEGMENTS.right - half / 2f - 2f, 59f, 18f,
                cardsActive ? ReSonoTheme.BACKGROUND : ReSonoTheme.MUTED, Paint.Align.CENTER, true);

        ReSonoTheme.glass(canvas, paint, new RectF(404f, 28f, 452f, 76f), 24f, false);
        drawGear(canvas, 428f, 52f);
        if (runnerVisible) {
            ReSonoTheme.glass(canvas, paint, new RectF(28f, 28f, 76f, 76f), 24f, runnerActive);
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
        if (runnerVisible && x <= 96f) openRunner.run();
        else if (x >= 384f) openSettings.run();
        else if (x >= SEGMENTS.left - 10f && x < SEGMENTS.centerX()) openVoice.run();
        else if (x >= SEGMENTS.centerX() && x <= SEGMENTS.right + 10f) openCards.run();
        invalidate();
        return true;
    }

    private void drawGear(Canvas canvas, float cx, float cy) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ReSonoTheme.INK);
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
