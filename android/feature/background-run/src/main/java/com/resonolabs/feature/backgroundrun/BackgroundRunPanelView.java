package com.resonolabs.feature.backgroundrun;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.runtime.host.BackgroundRunSnapshot;
import com.resonolabs.runtime.host.RuntimeBackgroundRunClient;
import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.ReSonoTheme;
import com.resonolabs.ui.input.UiInputIntent;
import com.resonolabs.ui.input.UiInputTarget;

import java.util.List;
import java.util.function.Consumer;

/** Tight 480x640 live run view; canonical history remains in management. */
public final class BackgroundRunPanelView extends View implements UiInputTarget {
    private static final float W = 480f, H = 640f;
    private final RuntimeBackgroundRunClient client;
    private final Consumer<List<BackgroundRunSnapshot>> observer;
    private final Runnable close;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FluidOrb orb = new FluidOrb();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<BackgroundRunSnapshot> runs = List.of();
    private boolean started;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!started) return;
            client.load(getContext(), values -> {
                runs = values;
                observer.accept(values);
                invalidate();
                handler.postDelayed(this, 1500L);
            });
        }
    };

    public BackgroundRunPanelView(Context context, RuntimeBackgroundRunClient client,
                                  Consumer<List<BackgroundRunSnapshot>> observer, Runnable close) {
        super(context); this.client = client; this.observer = observer; this.close = close;
        setContentDescription("Background run activity");
    }

    public void start() { if (!started) { started = true; handler.post(poll); } }
    public void stop() { started = false; handler.removeCallbacks(poll); }

    public void opened() {
        if (runs.isEmpty()) return;
        BackgroundRunSnapshot item = runs.get(0);
        if (!item.active()) client.acknowledge(getContext(), item.runId(), () -> { });
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        BackgroundRunSnapshot run = runs.isEmpty() ? null : runs.get(0);
        boolean active = run != null && run.active();
        boolean failed = run != null && ("failed".equals(run.state()) || "cancelled".equals(run.state()));
        int accent = failed ? ReSonoTheme.RED : ReSonoTheme.ORB_BLUE;
        orb.setColor(accent)
                .setEnergy(active ? 0.65f : 0.1f)
                .setSpeed(active ? 1.4f : 0.4f);
        float orbY = (run == null ? 270f : 160f) + (active ? orb.bob(4f) : 0f);
        float orbR = run == null ? 62f : active ? 56f : 44f;
        ReSonoTheme.background(canvas, paint, W, H, 240f, orbY, 240f, accent);

        drawBack(canvas);
        ReSonoTheme.text(canvas, paint, "Runner", 66f, 54f, 30f, ReSonoTheme.INK, Paint.Align.LEFT, true);
        if (run == null) {
            orb.draw(canvas, 240f, orbY, orbR);
            ReSonoTheme.text(canvas, paint, "Nothing running", 240f, 396f, 26f,
                    ReSonoTheme.INK, Paint.Align.CENTER, true);
            ReSonoTheme.text(canvas, paint, "Background runs will show up here", 240f, 428f, 17f,
                    ReSonoTheme.MUTED, Paint.Align.CENTER, false);
            canvas.restore(); return;
        }
        statePill(canvas, friendlyState(run.state()), active ? ReSonoTheme.ORB_PALE
                : failed ? ReSonoTheme.RED : ReSonoTheme.ORB_PALE);
        orb.draw(canvas, 240f, orbY, orbR);

        drawWrapped(canvas, run.objective(), 240f, 270f, 420f, 22f, 28f, ReSonoTheme.INK,
                2, Paint.Align.CENTER, true);

        float fraction = Math.max(0f, Math.min(1f, run.fraction()));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ReSonoTheme.withAlpha(ReSonoTheme.INK, 26));
        canvas.drawRoundRect(36f, 324f, 444f, 330f, 3f, 3f, paint);
        if (fraction > 0f) {
            paint.setShader(new LinearGradient(36f, 0f, 444f, 0f, ReSonoTheme.ORB_PALE, accent,
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(36f, 324f, 36f + 408f * fraction, 330f, 3f, 3f, paint);
            paint.setShader(null);
        }
        ReSonoTheme.text(canvas, paint, run.label(), 36f, 358f, 17f,
                failed ? ReSonoTheme.RED : ReSonoTheme.ORB_PALE, Paint.Align.LEFT, true);
        ReSonoTheme.text(canvas, paint, run.modelTurns() + " turns · " + run.toolCalls() + " tools",
                444f, 358f, 15f, ReSonoTheme.MUTED, Paint.Align.RIGHT, false);
        drawWrapped(canvas, run.activity(), 36f, 386f, 408f, 16f, 22f, ReSonoTheme.MUTED,
                1, Paint.Align.LEFT, false);

        boolean hasOutcome = !run.outcome().isBlank();
        float panelBottom = hasOutcome ? 548f : 622f;
        if (!run.timeline().isEmpty()) {
            RectF panel = new RectF(18f, 406f, 462f, panelBottom);
            ReSonoTheme.glass(canvas, paint, panel, 20f, false);
            int fit = Math.max(1, (int) ((panelBottom - 420f) / 30f));
            List<BackgroundRunSnapshot.TimelineEntry> entries = run.timeline();
            int from = Math.max(0, entries.size() - fit);
            float y = 438f;
            for (int i = from; i < entries.size(); i++) {
                boolean latest = i == entries.size() - 1;
                paint.setColor(latest ? ReSonoTheme.ORB_PALE : ReSonoTheme.withAlpha(ReSonoTheme.MUTED, 150));
                canvas.drawCircle(40f, y - 5f, latest ? 4f : 3f, paint);
                if (i < entries.size() - 1) {
                    paint.setColor(ReSonoTheme.LINE);
                    canvas.drawRect(39.5f, y + 1f, 40.5f, y + 19f, paint);
                }
                drawWrapped(canvas, entries.get(i).label(), 58f, y, 390f, 15f, 20f,
                        latest ? ReSonoTheme.INK : ReSonoTheme.MUTED, 1, Paint.Align.LEFT, false);
                y += 30f;
            }
        }
        if (hasOutcome) drawWrapped(canvas, run.outcome(), 240f, 580f, 420f, 17f, 23f,
                failed ? ReSonoTheme.RED : ReSonoTheme.INK, 2, Paint.Align.CENTER, false);
        canvas.restore();
        if (active && isShown()) postInvalidateDelayed(33L);
    }

    private void drawBack(Canvas canvas) {
        ReSonoTheme.glass(canvas, paint, new RectF(10f, 22f, 54f, 66f), 22f, false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ReSonoTheme.INK);
        canvas.drawLine(36f, 34f, 27f, 44f, paint);
        canvas.drawLine(27f, 44f, 36f, 54f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void statePill(Canvas canvas, String label, int color) {
        paint.setTextSize(15f);
        float width = paint.measureText(label) + 34f;
        RectF pill = new RectF(456f - width, 27f, 456f, 61f);
        ReSonoTheme.glass(canvas, paint, pill, 17f, false);
        paint.setColor(color); canvas.drawCircle(pill.left + 15f, 44f, 4f, paint);
        ReSonoTheme.text(canvas, paint, label, pill.left + 25f, 49f, 15f, ReSonoTheme.INK,
                Paint.Align.LEFT, true);
    }

    private static String friendlyState(String state) {
        if (state == null || state.isBlank()) return "Running";
        return switch (state) {
            case "completed" -> "Done";
            case "failed" -> "Failed";
            case "cancelled" -> "Cancelled";
            case "queued", "pending" -> "Queued";
            case "running" -> "Running";
            default -> Character.toUpperCase(state.charAt(0)) + state.substring(1).replace('_', ' ');
        };
    }

    private void drawWrapped(Canvas canvas, String text, float x, float y, float width,
                             float size, float lineHeight, int color, int maxLines,
                             Paint.Align align, boolean bold) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        String remaining = text == null ? "" : text.trim();
        for (int line = 0; line < maxLines && !remaining.isEmpty(); line++) {
            int cut = paint.breakText(remaining, true, width, null);
            if (cut < remaining.length()) {
                int space = remaining.lastIndexOf(' ', cut); if (space > 0) cut = space;
            }
            cut = Math.max(1, cut);
            String value = remaining.substring(0, cut).trim();
            if (line == maxLines - 1 && cut < remaining.length()) value += "…";
            ReSonoTheme.text(canvas, paint, value, x, y + line * lineHeight, size,
                    color, align, bold);
            remaining = remaining.substring(cut).trim();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float x = event.getX() * W / Math.max(1f, getWidth());
        float y = event.getY() * H / Math.max(1f, getHeight());
        if (x < 90f && y < 82f) close.run();
        return true;
    }

    @Override public boolean onInput(UiInputIntent intent) {
        if (intent == UiInputIntent.BACK) { close.run(); return true; }
        return true;
    }
}
