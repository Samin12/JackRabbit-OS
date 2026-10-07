package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.text.format.DateFormat;

import com.resonolabs.ui.design.SamTheme;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Big time, date and battery at the top of the board. No glass: it is the board's headline. */
public final class ClockWidget implements BoardWidget {
    private static final float HEIGHT = 112f;
    private final BoardHost host;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final OrbGlyph orb = new OrbGlyph();
    private final RectF battery = new RectF();
    private float width;
    private String time = "";
    private String meridiem = "";
    private String date = "";
    private String batteryText = "";
    private float timeWidth;
    private float batteryTextWidth;
    private int batteryLevel = -1;
    private boolean charging;

    public ClockWidget(BoardHost host) {
        this.host = host;
        clockPaint.setTypeface(BoardPaint.LIGHT);
        clockPaint.setFontFeatureSettings("tnum");
        clockPaint.setTextAlign(Paint.Align.LEFT);
        clockPaint.setColor(SamTheme.INK);
        readBattery();
    }

    @Override public String id() { return "clock"; }

    @Override public float measure(float width, long nowMs) {
        this.width = width;
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault());
        Locale locale = Locale.getDefault();
        boolean use24 = DateFormat.is24HourFormat(host.context());
        time = DateTimeFormatter.ofPattern(use24 ? "H:mm" : "h:mm", locale).format(now);
        meridiem = use24 ? "" : DateTimeFormatter.ofPattern("a", locale).format(now);
        date = DateTimeFormatter.ofPattern("EEEE, MMMM d", locale).format(now);
        clockPaint.setTextSize(70f);
        timeWidth = clockPaint.measureText(time);
        batteryText = batteryLevel < 0 ? "" : batteryLevel + "%";
        batteryTextWidth = BoardPaint.width(paint, batteryText, 16f, BoardPaint.MEDIUM);
        orb.set(width - 34f, 50f, 19f, SamTheme.ORB_BLUE, 2.3f);
        return HEIGHT;
    }

    @Override public void draw(Canvas canvas, long nowMs) {
        canvas.drawText(time, 6f, 74f, clockPaint);
        if (!meridiem.isEmpty()) {
            BoardPaint.text(canvas, paint, meridiem, 6f + timeWidth + 8f, 74f, 20f,
                    SamTheme.withAlpha(SamTheme.INK, 170), Paint.Align.LEFT, BoardPaint.MEDIUM);
        }
        BoardPaint.text(canvas, paint, date, 9f, 104f, 18f, SamTheme.MUTED, Paint.Align.LEFT, BoardPaint.REGULAR);
        orb.draw(canvas, paint);
        if (batteryLevel >= 0) drawBattery(canvas);
    }

    private void drawBattery(Canvas canvas) {
        float right = width - 6f;
        BoardPaint.text(canvas, paint, batteryText, right, 104f, 16f,
                batteryLevel <= 15 && !charging ? SamTheme.RED : SamTheme.MUTED, Paint.Align.RIGHT, BoardPaint.MEDIUM);
        float bodyRight = right - batteryTextWidth - 10f;
        battery.set(bodyRight - 25f, 92f, bodyRight, 105f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(SamTheme.withAlpha(SamTheme.MUTED, 220));
        canvas.drawRoundRect(battery, 3.5f, 3.5f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(bodyRight + 1.5f, 96f, bodyRight + 3.5f, 101f, 1f, 1f, paint);
        float level = Math.max(0.08f, Math.min(1f, batteryLevel / 100f));
        int fill = charging ? BoardPaint.SUCCESS : batteryLevel <= 15 ? SamTheme.RED : SamTheme.INK;
        paint.setColor(SamTheme.withAlpha(fill, 230));
        canvas.drawRoundRect(battery.left + 2.5f, battery.top + 2.5f,
                battery.left + 2.5f + (battery.width() - 5f) * level, battery.bottom - 2.5f, 1.5f, 1.5f, paint);
        if (charging) {
            paint.setColor(SamTheme.BACKGROUND);
            float cx = battery.centerX();
            canvas.drawLine(cx + 2f, battery.top + 1f, cx - 2.5f, battery.centerY() + 0.5f, paint);
        }
    }

    @Override public int focusCount() { return 0; }
    @Override public void focusBounds(int index, RectF out) { out.setEmpty(); }
    @Override public boolean onTap(float x, float y) { return false; }
    @Override public boolean activate(int index) { return false; }
    @Override public long refreshIntervalMs() { return 60_000L; }

    @Override public void refresh() {
        int before = batteryLevel;
        boolean wasCharging = charging;
        readBattery();
        if (before != batteryLevel || wasCharging != charging) host.widgetChanged(this);
    }

    private void readBattery() {
        try {
            BatteryManager manager = host.context().getSystemService(BatteryManager.class);
            if (manager == null) return;
            int level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            batteryLevel = level >= 0 && level <= 100 ? level : -1;
            charging = manager.isCharging();
        } catch (RuntimeException ignored) {
            batteryLevel = -1;
        }
    }
}
