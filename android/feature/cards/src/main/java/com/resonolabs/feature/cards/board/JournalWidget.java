package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.resonolabs.runtime.host.JournalClient;
import com.resonolabs.ui.design.SamTheme;

import org.json.JSONObject;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Heptabase journal at a glance: connected / needs reconnect / not connected, today's sent and
 * queued entries, and a big Note button that opens the keyboard bar; the typed words go to
 * {@code POST /v1/journal/notes} verbatim. When not connected it only says where to connect.
 */
public final class JournalWidget implements BoardWidget {
    private static final int SETTINGS = 0, NOTE = 1;
    private static final float PAD = 22f;
    private static final float HEADER = 54f;
    private static final float BODY = 76f;
    private static final float BUTTON_W = 132f;
    private static final float BUTTON_H = 54f;
    private static final long FEEDBACK_MS = 4_500L;
    private static final int ACCENT = SamTheme.VIOLET;

    private final BoardHost host;
    private final JournalClient client = new JournalClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GlassPanel glass = new GlassPanel();
    private final OrbGlyph headerOrb = new OrbGlyph();
    private final RectF rect = new RectF();
    private final RectF button = new RectF();
    private final List<Integer> focus = new ArrayList<>();
    private final Runnable feedbackExpired = this::feedbackExpired;
    private LinearGradient buttonFill;
    private JournalStatus status;
    private boolean loaded;
    private boolean failed;
    private boolean inFlight;
    private boolean saving;
    private String fixture = "";
    private float width;
    private String title = "";
    private String detail = "";
    private int titleColor = SamTheme.INK;
    private String chip = "";
    private int chipColor = SamTheme.MUTED;
    private float chipWidth;
    private String feedback = "";
    private int feedbackColor = SamTheme.MUTED;
    private long feedbackUntil;

    public JournalWidget(BoardHost host) {
        this.host = host;
    }

    @Override public String id() { return "journal"; }

    // ------------------------------------------------------------- data

    @Override public long refreshIntervalMs() { return 30_000L; }

    @Override public void onShow() {
        String state = host.fixtureState(id());
        if (state.isEmpty()) {
            state = switch (host.fixtureMode()) {
                case FULL -> "connected";
                case EMPTY -> "empty";
                case UNCONFIGURED -> "disconnected";
                default -> "";
            };
        }
        if (!state.equals(fixture)) {
            fixture = state;
            loaded = false;
            status = null;
            host.widgetChanged(this);
        }
    }

    @Override public void refresh() {
        if (!fixture.isEmpty()) {
            apply(BoardFixtures.journal(fixture, System.currentTimeMillis(), ZoneId.systemDefault()));
            return;
        }
        if (inFlight) return;
        inFlight = true;
        client.status(host.context(), new JournalClient.Callback() {
            @Override public void onResult(JSONObject value) {
                inFlight = false;
                apply(value);
            }

            @Override public void onFailure(int httpStatus, String code) {
                inFlight = false;
                failed = true;
                if (!loaded || status == null) host.widgetChanged(JournalWidget.this);
            }
        });
    }

    private void apply(JSONObject value) {
        status = JournalStatus.from(value);
        loaded = true;
        failed = false;
        host.widgetChanged(this);
    }

    /** True when the Note button is offered (the composer may open). */
    public boolean canWrite() {
        return status != null && status.canWrite();
    }

    /** The user's typed note, sent verbatim (never rewritten). */
    public void submit(String text) {
        String words = text == null ? "" : text.trim();
        if (words.isEmpty() || saving) return;
        saving = true;
        showFeedback("Saving…", SamTheme.MUTED, 30_000L);
        if (!fixture.isEmpty()) {
            JSONObject result = BoardFixtures.journalNote(fixture, System.currentTimeMillis(), ZoneId.systemDefault());
            handler.postDelayed(() -> noteSaved(result), 450L);
            return;
        }
        client.addNote(host.context(), words, new JournalClient.Callback() {
            @Override public void onResult(JSONObject value) { noteSaved(value); }

            @Override public void onFailure(int httpStatus, String code) {
                saving = false;
                showFeedback(JournalStatus.noteFailed(httpStatus, code), SamTheme.RED, FEEDBACK_MS);
                refresh();
            }
        });
    }

    private void noteSaved(JSONObject result) {
        saving = false;
        boolean sent = "sent".equals(result.isNull("state") ? "" : result.optString("state", ""));
        showFeedback(JournalStatus.noteSaved(result), sent ? BoardPaint.SUCCESS : SamTheme.AMBER, FEEDBACK_MS);
        refresh();
    }

    private void showFeedback(String text, int color, long durationMs) {
        feedback = text;
        feedbackColor = color;
        feedbackUntil = SystemClock.uptimeMillis() + durationMs;
        handler.removeCallbacks(feedbackExpired);
        handler.postDelayed(feedbackExpired, durationMs + 50L);
        host.widgetChanged(this);
    }

    private void feedbackExpired() {
        if (saving) return;
        feedback = "";
        host.widgetChanged(this);
    }

    // ------------------------------------------------------------- layout

    @Override public float measure(float width, long nowMs) {
        this.width = width;
        focus.clear();
        boolean writable = status != null && status.canWrite();
        if (!loaded || status == null) {
            title = failed ? "Journal unavailable right now" : "Loading journal…";
            detail = "";
            titleColor = SamTheme.MUTED;
            chip = "";
        } else if (status.state == JournalStatus.State.DISCONNECTED) {
            title = "Connect Heptabase";
            detail = "in Settings → Management";
            titleColor = SamTheme.INK;
            chip = status.chip();
            chipColor = SamTheme.MUTED;
            focus.add(SETTINGS);
        } else if (status.state == JournalStatus.State.RECONNECT) {
            title = "Reconnect Heptabase";
            detail = "in Settings → Management";
            titleColor = SamTheme.AMBER;
            chip = status.reconnectChip();
            chipColor = SamTheme.AMBER;
            focus.add(SETTINGS);
            focus.add(NOTE);
        } else {
            title = "Today's journal";
            detail = status.todayLine();
            titleColor = SamTheme.INK;
            chip = status.chip();
            chipColor = BoardPaint.SUCCESS;
            focus.add(NOTE);
        }
        float textRight = writable ? width - PAD - BUTTON_W - 14f : width - PAD;
        float textLeft = status != null && status.state == JournalStatus.State.DISCONNECTED ? PAD + 56f : PAD;
        title = BoardPaint.fit(paint, title, textRight - textLeft, 19f, BoardPaint.MEDIUM);
        detail = BoardPaint.fit(paint, detail, textRight - textLeft, 15f, BoardPaint.REGULAR);
        feedback = BoardPaint.fit(paint, feedback, textRight - textLeft, 15f, BoardPaint.MEDIUM);
        chipWidth = chip.isEmpty() ? 0f : BoardPaint.width(paint, chip, 14f, BoardPaint.MEDIUM);
        float height = HEADER + BODY + 6f;
        button.set(width - PAD - BUTTON_W + 6f, HEADER + (BODY - BUTTON_H) / 2f - 4f, width - PAD + 6f,
                HEADER + (BODY + BUTTON_H) / 2f - 4f);
        if (buttonFill == null) {
            buttonFill = new LinearGradient(0f, button.top, 0f, button.bottom, SamTheme.withAlpha(SamTheme.ORB_BLUE, 250),
                    SamTheme.withAlpha(SamTheme.ORB_BLUE, 205), Shader.TileMode.CLAMP);
        }
        glass.size(width, height, 28f);
        headerOrb.set(31f, 28f, 7f, ACCENT, 2.6f);
        return height;
    }

    // ------------------------------------------------------------- drawing

    @Override public void draw(Canvas canvas, long nowMs) {
        glass.draw(canvas, paint, false);
        headerOrb.draw(canvas, paint);
        BoardPaint.eyebrow(canvas, paint, "JOURNAL", 47f, 34f, 14f, SamTheme.withAlpha(SamTheme.INK, 230), Paint.Align.LEFT);
        if (!chip.isEmpty()) {
            float right = width - PAD;
            BoardPaint.text(canvas, paint, chip, right, 34f, 14f, chipColor == BoardPaint.SUCCESS ? SamTheme.MUTED : chipColor,
                    Paint.Align.RIGHT, BoardPaint.MEDIUM);
            paint.setColor(chipColor);
            canvas.drawCircle(right - chipWidth - 10f, 29f, 4f, paint);
        }
        boolean disconnected = status != null && status.state == JournalStatus.State.DISCONNECTED;
        float left = disconnected ? PAD + 56f : PAD;
        float base = HEADER;
        if (disconnected) drawBook(canvas, PAD, base + 6f);
        boolean showFeedback = !feedback.isEmpty() && SystemClock.uptimeMillis() < feedbackUntil;
        BoardPaint.text(canvas, paint, title, left, base + 26f, 19f, titleColor, Paint.Align.LEFT, BoardPaint.MEDIUM);
        if (showFeedback) {
            BoardPaint.text(canvas, paint, feedback, left, base + 50f, 15f, feedbackColor, Paint.Align.LEFT,
                    BoardPaint.MEDIUM);
        } else if (!detail.isEmpty()) {
            BoardPaint.text(canvas, paint, detail, left, base + 50f, 15f, SamTheme.MUTED, Paint.Align.LEFT,
                    BoardPaint.REGULAR);
        }
        if (status != null && status.canWrite()) drawButton(canvas);
    }

    private void drawButton(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setShader(buttonFill);
        float radius = BUTTON_H / 2f;
        canvas.drawRoundRect(button, radius, radius, paint);
        paint.setShader(null);
        if (saving) {
            paint.setColor(SamTheme.withAlpha(SamTheme.BACKGROUND, 90));
            canvas.drawRoundRect(button, radius, radius, paint);
        }
        float cx = button.left + 38f;
        float cy = button.centerY();
        pencil(canvas, cx, cy, SamTheme.INK);
        BoardPaint.text(canvas, paint, "Note", cx + 20f, cy + 7f, 20f, SamTheme.INK, Paint.Align.LEFT, BoardPaint.MEDIUM);
    }

    private void pencil(Canvas canvas, float cx, float cy, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        canvas.drawLine(cx - 7f, cy + 7f, cx + 5f, cy - 5f, paint);
        canvas.drawLine(cx + 5f, cy - 5f, cx + 8f, cy - 2f, paint);
        canvas.drawLine(cx + 8f, cy - 2f, cx - 4f, cy + 10f, paint);
        canvas.drawLine(cx - 4f, cy + 10f, cx - 8f, cy + 11f, paint);
        canvas.drawLine(cx - 8f, cy + 11f, cx - 7f, cy + 7f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Small open-book mark for the "Connect Heptabase" block. */
    private void drawBook(Canvas canvas, float left, float top) {
        rect.set(left, top + 4f, left + 40f, top + 44f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(ACCENT, 40));
        canvas.drawRoundRect(rect, 11f, 11f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(OrbGlyph.pale(ACCENT));
        float cx = left + 20f;
        canvas.drawLine(cx, top + 15f, cx, top + 34f, paint);
        canvas.drawLine(cx, top + 15f, cx - 10f, top + 13f, paint);
        canvas.drawLine(cx - 10f, top + 13f, cx - 10f, top + 32f, paint);
        canvas.drawLine(cx - 10f, top + 32f, cx, top + 34f, paint);
        canvas.drawLine(cx, top + 15f, cx + 10f, top + 13f, paint);
        canvas.drawLine(cx + 10f, top + 13f, cx + 10f, top + 32f, paint);
        canvas.drawLine(cx + 10f, top + 32f, cx, top + 34f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public long redrawDelayMs(long nowMs) {
        if (feedback.isEmpty()) return -1L;
        long left = feedbackUntil - SystemClock.uptimeMillis();
        return left > 0L ? left + 20L : -1L;
    }

    // ------------------------------------------------------------- input

    @Override public int focusCount() { return focus.size(); }

    @Override public void focusBounds(int index, RectF out) {
        if (focus.get(index) == NOTE) {
            out.set(button.left - 6f, button.top - 6f, button.right + 6f, button.bottom + 6f);
        } else {
            float right = status != null && status.canWrite() ? button.left - 8f : width - 6f;
            out.set(6f, HEADER - 4f, right, HEADER + BODY);
        }
    }

    @Override public float focusRadius(int index) {
        return focus.get(index) == NOTE ? BUTTON_H / 2f + 6f : 20f;
    }

    @Override public String focusKey(int index) {
        return focus.get(index) == NOTE ? "note" : "settings";
    }

    @Override public boolean onTap(float x, float y) {
        if (status == null || !loaded) {
            if (failed) refresh();
            return true;
        }
        if (status.canWrite() && x >= button.left - 14f && y >= button.top - 14f && y <= button.bottom + 14f) {
            openNote();
            return true;
        }
        if (status.state != JournalStatus.State.CONNECTED) host.openSettings();
        return true;
    }

    @Override public boolean activate(int index) {
        if (index < 0 || index >= focus.size()) return false;
        if (focus.get(index) == NOTE) openNote();
        else host.openSettings();
        return true;
    }

    private void openNote() {
        if (saving) return;
        host.openJournalNote();
    }

    @Override public void close() {
        handler.removeCallbacksAndMessages(null);
        client.close();
    }
}
