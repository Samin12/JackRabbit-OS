package com.resonolabs.voice;

import android.app.Activity;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.ReSonoTheme;
import com.resonolabs.ui.input.UiInputIntent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Pull-down Control Center: the R1-sized replacement for the system notification shade. */
final class ControlCenterView extends View {
    private static final float WIDTH = 480f;
    private static final float HEIGHT = 640f;
    private static final RectF[] TILES = {
            new RectF(24f, 128f, 234f, 214f), new RectF(246f, 128f, 456f, 214f),
            new RectF(24f, 224f, 234f, 310f), new RectF(246f, 224f, 456f, 310f)};
    private static final RectF BRIGHTNESS = new RectF(24f, 326f, 456f, 378f);
    private static final RectF VOLUME = new RectF(24f, 390f, 456f, 442f);
    private static final RectF CLEAR = new RectF(368f, 452f, 456f, 486f);

    private final Activity activity;
    private final Runnable openSettings;
    private final Runnable onClosed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FluidOrb orb = new FluidOrb().setEnergy(0.2f).setSpeed(0.6f);
    private final SimpleDateFormat time = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final SimpleDateFormat date = new SimpleDateFormat("EEEE, MMM d", Locale.getDefault());
    private boolean open;
    private int focus = -1;
    private int dragging = -1;
    private float downX;
    private float downY;
    private float dragOffset;
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { invalidate(); }
    };

    ControlCenterView(Activity activity, Runnable openSettings, Runnable onClosed) {
        super(activity);
        this.activity = activity;
        this.openSettings = openSettings;
        this.onClosed = onClosed;
        setVisibility(GONE);
        setFocusable(true);
        setContentDescription("Control Center. Wi-Fi, Bluetooth, Do Not Disturb, brightness, volume and notifications.");
        NotificationFeed.observe(this::invalidate);
    }

    boolean isOpen() {
        return open;
    }

    void show() {
        if (open) return;
        open = true;
        focus = -1;
        setVisibility(VISIBLE);
        setTranslationY(-getHeightOrScreen());
        animate().translationY(0f).setDuration(240L).start();
        IntentFilter filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
        activity.registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED);
        requestFocus();
        invalidate();
    }

    void hide() {
        if (!open) return;
        open = false;
        try { activity.unregisterReceiver(stateReceiver); } catch (IllegalArgumentException ignored) { }
        animate().translationY(-getHeightOrScreen()).setDuration(200L)
                .withEndAction(() -> { setVisibility(GONE); onClosed.run(); }).start();
    }

    private float getHeightOrScreen() {
        return getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
    }

    boolean onInput(UiInputIntent intent) {
        switch (intent) {
            case BACK -> hide();
            case NEXT -> { focus = Math.min(5, focus + 1); invalidate(); }
            case PREVIOUS -> { focus = Math.max(0, focus - 1); invalidate(); }
            case ACTIVATE -> activateFocus();
            default -> { return false; }
        }
        return true;
    }

    private void activateFocus() {
        if (focus >= 0 && focus < 4) toggleTile(focus);
        else if (focus == 4) adjustBrightness(brightnessFraction() < 0.99f ? 0.1f : -1f);
        else if (focus == 5) adjustVolume(volumeFraction() < 0.99f ? 0.1f : -1f);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / WIDTH, getHeight() / HEIGHT);
        canvas.translate(0f, Math.min(0f, dragOffset));
        ReSonoTheme.background(canvas, paint, WIDTH, HEIGHT, 400f, 60f, 220f, ReSonoTheme.ORB_BLUE);
        orb.draw(canvas, 418f, 68f + orb.bob(3f), 30f);
        Date now = new Date();
        ReSonoTheme.text(canvas, paint, time.format(now), 28f, 82f, 52f, ReSonoTheme.INK,
                Paint.Align.LEFT, true);
        ReSonoTheme.text(canvas, paint, date.format(now) + "  ·  " + batteryLabel(), 30f, 112f, 16f,
                ReSonoTheme.MUTED, Paint.Align.LEFT, false);

        drawTile(canvas, 0, "Wi‑Fi", wifiEnabled() ? wifiName() : "Off", wifiEnabled());
        drawTile(canvas, 1, "Bluetooth", bluetoothEnabled() ? "On" : "Off", bluetoothEnabled());
        boolean dnd = doNotDisturb();
        drawTile(canvas, 2, "Do Not Disturb", dnd ? "On" : "Off", dnd);
        drawTile(canvas, 3, "Settings", "All options", false);
        drawSlider(canvas, BRIGHTNESS, 4, "Brightness", brightnessFraction(), false);
        drawSlider(canvas, VOLUME, 5, "Volume", volumeFraction(), true);
        drawNotifications(canvas);

        paint.setColor(ReSonoTheme.withAlpha(ReSonoTheme.INK, 90));
        canvas.drawRoundRect(212f, 620f, 268f, 625f, 3f, 3f, paint);
        canvas.restore();
        if (open && isShown()) postInvalidateDelayed(33L);
    }

    private void drawTile(Canvas canvas, int index, String label, String state, boolean active) {
        RectF rect = TILES[index];
        if (active) {
            paint.setColor(ReSonoTheme.BACKGROUND);
            paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    ReSonoTheme.ORB_BLUE, ReSonoTheme.withAlpha(ReSonoTheme.ORB_PALE, 255),
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, 22f, 22f, paint);
            paint.setShader(null);
            if (focus == index) {
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.5f); paint.setColor(ReSonoTheme.INK);
                canvas.drawRoundRect(rect, 22f, 22f, paint); paint.setStyle(Paint.Style.FILL);
            }
        } else {
            ReSonoTheme.glass(canvas, paint, rect, 22f, focus == index);
        }
        drawTileIcon(canvas, index, rect.left + 32f, rect.top + 30f, active);
        String shown = state.length() > 16 ? state.substring(0, 15) + "…" : state;
        ReSonoTheme.text(canvas, paint, label, rect.left + 18f, rect.top + 64f, 17f,
                active ? ReSonoTheme.BACKGROUND : ReSonoTheme.INK, Paint.Align.LEFT, true);
        ReSonoTheme.text(canvas, paint, shown, rect.left + 18f, rect.top + 80f, 13f,
                active ? ReSonoTheme.withAlpha(ReSonoTheme.BACKGROUND, 190) : ReSonoTheme.MUTED,
                Paint.Align.LEFT, false);
    }

    private void drawTileIcon(Canvas canvas, int index, float cx, float cy, boolean active) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(active ? ReSonoTheme.BACKGROUND : ReSonoTheme.INK);
        switch (index) {
            case 0 -> {
                for (int arc = 0; arc < 3; arc++) {
                    float r = 5f + arc * 6f;
                    canvas.drawArc(cx - r, cy + 6f - r, cx + r, cy + 6f + r, 225f, 90f, false, paint);
                }
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy + 6f, 2.4f, paint);
            }
            case 1 -> {
                canvas.drawLine(cx, cy - 12f, cx, cy + 12f, paint);
                canvas.drawLine(cx, cy - 12f, cx + 7f, cy - 6f, paint);
                canvas.drawLine(cx + 7f, cy - 6f, cx - 7f, cy + 6f, paint);
                canvas.drawLine(cx, cy + 12f, cx + 7f, cy + 6f, paint);
                canvas.drawLine(cx + 7f, cy + 6f, cx - 7f, cy - 6f, paint);
            }
            case 2 -> {
                canvas.drawArc(cx - 11f, cy - 11f, cx + 11f, cy + 11f, 300f, 280f, false, paint);
                canvas.drawArc(cx - 2f, cy - 15f, cx + 16f, cy + 3f, 100f, 160f, false, paint);
            }
            default -> {
                canvas.drawCircle(cx, cy, 4.5f, paint);
                for (int tooth = 0; tooth < 8; tooth++) {
                    double angle = Math.PI / 4.0 * tooth;
                    canvas.drawLine(cx + (float) Math.cos(angle) * 8f, cy + (float) Math.sin(angle) * 8f,
                            cx + (float) Math.cos(angle) * 11.5f, cy + (float) Math.sin(angle) * 11.5f, paint);
                }
                canvas.drawCircle(cx, cy, 8f, paint);
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSlider(Canvas canvas, RectF rect, int index, String label, float fraction, boolean speaker) {
        ReSonoTheme.glass(canvas, paint, rect, 26f, focus == index);
        float fill = rect.left + Math.max(52f, rect.width() * fraction);
        paint.setColor(ReSonoTheme.BACKGROUND);
        paint.setShader(new LinearGradient(rect.left, 0f, fill, 0f, ReSonoTheme.ORB_BLUE,
                ReSonoTheme.ORB_PALE, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect.left, rect.top, fill, rect.bottom, 26f, 26f, paint);
        paint.setShader(null);
        float cx = rect.left + 26f;
        float cy = rect.centerY();
        paint.setColor(ReSonoTheme.INK);
        if (speaker) {
            canvas.drawRect(cx - 9f, cy - 4f, cx - 4f, cy + 4f, paint);
            android.graphics.Path cone = new android.graphics.Path();
            cone.moveTo(cx - 4f, cy - 4f); cone.lineTo(cx + 3f, cy - 10f);
            cone.lineTo(cx + 3f, cy + 10f); cone.lineTo(cx - 4f, cy + 4f); cone.close();
            canvas.drawPath(cone, paint);
        } else {
            canvas.drawCircle(cx, cy, 5.5f, paint);
            paint.setStrokeWidth(2.2f);
            for (int ray = 0; ray < 8; ray++) {
                double angle = Math.PI / 4.0 * ray;
                canvas.drawLine(cx + (float) Math.cos(angle) * 8.5f, cy + (float) Math.sin(angle) * 8.5f,
                        cx + (float) Math.cos(angle) * 11.5f, cy + (float) Math.sin(angle) * 11.5f, paint);
            }
        }
        ReSonoTheme.text(canvas, paint, label, rect.left + 50f, cy + 6f, 16f, ReSonoTheme.INK,
                Paint.Align.LEFT, true);
        ReSonoTheme.text(canvas, paint, Math.round(fraction * 100f) + "%", rect.right - 18f, cy + 6f, 15f,
                ReSonoTheme.INK, Paint.Align.RIGHT, false);
    }

    private void drawNotifications(Canvas canvas) {
        List<NotificationFeed.Item> items = NotificationFeed.items();
        ReSonoTheme.text(canvas, paint, "Notifications", 28f, 476f, 17f, ReSonoTheme.INK,
                Paint.Align.LEFT, true);
        if (items.isEmpty()) {
            ReSonoTheme.text(canvas, paint, "You're all caught up", 240f, 548f, 16f,
                    ReSonoTheme.MUTED, Paint.Align.CENTER, false);
            return;
        }
        ReSonoTheme.text(canvas, paint, "Clear", CLEAR.right - 4f, 476f, 15f, ReSonoTheme.ORB_PALE,
                Paint.Align.RIGHT, true);
        for (int row = 0; row < Math.min(2, items.size()); row++) {
            NotificationFeed.Item item = items.get(row);
            RectF rect = new RectF(24f, 490f + row * 62f, 456f, 544f + row * 62f);
            ReSonoTheme.glass(canvas, paint, rect, 18f, false);
            String head = item.app() + (item.title().isEmpty() ? "" : " · " + item.title());
            ReSonoTheme.text(canvas, paint, ellipsize(head, 38), rect.left + 16f, rect.top + 23f, 14f,
                    ReSonoTheme.INK, Paint.Align.LEFT, true);
            ReSonoTheme.text(canvas, paint, ellipsize(item.text(), 46), rect.left + 16f, rect.top + 43f, 13f,
                    ReSonoTheme.MUTED, Paint.Align.LEFT, false);
        }
        if (items.size() > 2) {
            ReSonoTheme.text(canvas, paint, "+" + (items.size() - 2) + " more", 240f, 612f, 13f,
                    ReSonoTheme.MUTED, Paint.Align.CENTER, false);
        }
    }

    private static String ellipsize(String value, int max) {
        String flat = value == null ? "" : value.replace('\n', ' ').trim();
        return flat.length() > max ? flat.substring(0, max - 1) + "…" : flat;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() * WIDTH / Math.max(1f, getWidth());
        float y = event.getY() * HEIGHT / Math.max(1f, getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; dragOffset = 0f;
                dragging = BRIGHTNESS.contains(x, y) ? 4 : VOLUME.contains(x, y) ? 5 : -1;
                if (dragging >= 0) setSlider(dragging, x);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (dragging >= 0) setSlider(dragging, x);
                else { dragOffset = y - downY; invalidate(); }
            }
            case MotionEvent.ACTION_UP -> {
                float dy = y - downY;
                if (dragging >= 0) { dragging = -1; return true; }
                dragOffset = 0f;
                if (dy < -70f) { hide(); return true; }
                if (Math.abs(dy) < 20f && Math.abs(x - downX) < 20f) tap(x, y);
                invalidate();
            }
            case MotionEvent.ACTION_CANCEL -> { dragging = -1; dragOffset = 0f; invalidate(); }
            default -> { }
        }
        return true;
    }

    private void tap(float x, float y) {
        for (int index = 0; index < TILES.length; index++) {
            if (TILES[index].contains(x, y)) { toggleTile(index); return; }
        }
        if (CLEAR.contains(x, y) && !NotificationFeed.items().isEmpty()) NotificationFeed.dismissAll();
        else if (y >= 600f) hide();
    }

    private void toggleTile(int index) {
        switch (index) {
            case 0 -> {
                WifiManager wifi = activity.getSystemService(WifiManager.class);
                if (wifi != null) {
                    try { wifi.setWifiEnabled(!wifi.isWifiEnabled()); } catch (SecurityException ignored) { }
                }
            }
            case 1 -> {
                BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (adapter != null) {
                    try {
                        if (adapter.isEnabled()) adapter.disable(); else adapter.enable();
                    } catch (SecurityException ignored) { }
                }
            }
            case 2 -> {
                NotificationManager notifications = activity.getSystemService(NotificationManager.class);
                if (notifications != null) {
                    try {
                        notifications.setInterruptionFilter(doNotDisturb()
                                ? NotificationManager.INTERRUPTION_FILTER_ALL
                                : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                    } catch (SecurityException ignored) { }
                }
            }
            default -> {
                hide();
                openSettings.run();
            }
        }
        invalidate();
    }

    private void setSlider(int index, float x) {
        RectF rect = index == 4 ? BRIGHTNESS : VOLUME;
        float fraction = Math.max(0f, Math.min(1f, (x - rect.left) / rect.width()));
        if (index == 4) setBrightness(fraction); else setVolume(fraction);
        invalidate();
    }

    private void adjustBrightness(float delta) {
        setBrightness(delta < 0f ? 0.1f : Math.min(1f, brightnessFraction() + delta));
    }

    private void adjustVolume(float delta) {
        setVolume(delta < 0f ? 0f : Math.min(1f, volumeFraction() + delta));
    }

    private void setBrightness(float fraction) {
        try {
            Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    Math.max(8, Math.round(fraction * 255f)));
        } catch (SecurityException ignored) { }
    }

    private float brightnessFraction() {
        return Settings.System.getInt(activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
    }

    private void setVolume(float fraction) {
        AudioManager audio = activity.getSystemService(AudioManager.class);
        if (audio == null) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(fraction * max), 0);
    }

    private float volumeFraction() {
        AudioManager audio = activity.getSystemService(AudioManager.class);
        if (audio == null) return 0f;
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private boolean wifiEnabled() {
        WifiManager wifi = activity.getSystemService(WifiManager.class);
        return wifi != null && wifi.isWifiEnabled();
    }

    @SuppressWarnings("deprecation")
    private String wifiName() {
        WifiManager wifi = activity.getSystemService(WifiManager.class);
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        String ssid = info == null ? null : info.getSSID();
        if (ssid == null || ssid.isBlank() || "<unknown ssid>".equals(ssid)) return "On";
        return ssid.replace("\"", "");
    }

    private boolean bluetoothEnabled() {
        BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try { return adapter != null && adapter.isEnabled(); } catch (SecurityException ignored) { return false; }
    }

    private boolean doNotDisturb() {
        NotificationManager notifications = activity.getSystemService(NotificationManager.class);
        return notifications != null
                && notifications.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    private String batteryLabel() {
        BatteryManager battery = activity.getSystemService(BatteryManager.class);
        int level = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        boolean charging = battery != null && battery.isCharging();
        return level < 0 ? "" : level + "%" + (charging ? " charging" : "");
    }
}
