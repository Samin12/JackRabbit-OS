package com.resonolabs.feature.cards;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.ReSonoTheme;
import com.resonolabs.ui.input.UiInputIntent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native rolodex for the real dynamic Card catalog. Imported HTML runs elsewhere. */
final class CardsDeckView extends View {
    interface Activation { void open(JSONObject item); }

    private static final float WIDTH = 480f;
    private static final float HEIGHT = 640f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final FluidOrb orb = new FluidOrb();
    private final Activation activation;
    private JSONArray items = new JSONArray();
    private int index;
    private float downY;
    private float settleOffset;

    CardsDeckView(android.content.Context context, Activation activation) {
        super(context);
        this.activation = activation;
        setFocusable(true);
        setContentDescription("Creation cards");
        showCatalog(new JSONObject());
    }

    void showCatalog(JSONObject catalog) {
        JSONArray remote = catalog.optJSONArray("cards");
        if (remote == null) remote = catalog.optJSONArray("creations");
        items = new JSONArray();
        try { items.put(new JSONObject().put("sourceType","builtin_calendar").put("title","Calendar").put("description","Upcoming events from your connected calendars.").put("accent","#ff5ca8")); } catch (Exception ignored) {}
        try { items.put(new JSONObject().put("sourceType","builtin_tasks").put("title","Tasks").put("description","Your open tasks, available to Voice.").put("accent","#ffd166")); } catch (Exception ignored) {}
        if (remote != null) for (int i=0;i<remote.length();i++) items.put(remote.opt(i));
        index = Math.min(index, Math.max(0, items.length() - 1));
        invalidate();
    }

    boolean onInput(UiInputIntent input) {
        if (input == UiInputIntent.PREVIOUS) move(-1);
        else if (input == UiInputIntent.NEXT) move(1);
        else if (input == UiInputIntent.ACTIVATE) activate();
        else return false;
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float y = event.getY() * HEIGHT / Math.max(1f, getHeight());
        float x = event.getX() * WIDTH / Math.max(1f, getWidth());
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downY = y;
            return true;
        }
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float delta = y - downY;
        if (Math.abs(delta) > 55f) move(delta < 0f ? 1 : -1);
        else if (y >= 560f && x <= 120f) move(-1);
        else if (y >= 560f && x >= 360f) move(1);
        else if (y >= 130f && y <= 530f) activate();
        return true;
    }

    private void move(int direction) {
        if (items.length() == 0) return;
        index = (index + direction + items.length()) % items.length();
        ValueAnimator animator = ValueAnimator.ofFloat(direction * 28f, 0f);
        animator.setDuration(260L);
        animator.addUpdateListener(value -> {
            settleOffset = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void activate() {
        JSONObject item = items.optJSONObject(index);
        if (item == null) return;
        String source = item.optString("sourceType", "local_archive");
        if ("builtin_calendar".equals(source) || "builtin_tasks".equals(source)) { activation.open(item); return; }
        String entry = "rabbit_qr_link".equals(source)
                ? item.optString("entryUrl", "") : item.optString("entryAsset", "");
        if (("rabbit_qr_link".equals(source) && entry.startsWith("https://"))
                || (("local_archive".equals(source) || "plugin_card".equals(source)) && entry.startsWith("/v1/creations/"))) {
            activation.open(item);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / WIDTH, getHeight() / HEIGHT);
        JSONObject front = items.optJSONObject(index);
        int accent = accentOf(front);
        ReSonoTheme.background(canvas, paint, WIDTH, HEIGHT, 240f, 250f, 280f, accent);
        if (items.length() == 0) {
            ReSonoTheme.text(canvas, paint, "No cards yet", 240f, 330f, 24f,
                    ReSonoTheme.INK, Paint.Align.CENTER, true);
            ReSonoTheme.text(canvas, paint, "Import one from R1 management.", 240f, 362f, 17f,
                    ReSonoTheme.MUTED, Paint.Align.CENTER, false);
        } else {
            for (int depth = Math.min(2, items.length() - 1); depth >= 1; depth--) {
                drawBackCard(canvas, depth);
            }
            drawFrontCard(canvas, front, accent);
            drawDots(canvas);
        }
        drawArrow(canvas, 60f, false);
        drawArrow(canvas, 420f, true);
        canvas.restore();
        if (isShown() && items.length() > 0) postInvalidateDelayed(33L);
    }

    private void drawBackCard(Canvas canvas, int depth) {
        float inset = 36f + depth * 18f;
        RectF rect = new RectF(inset, 124f - depth * 14f, WIDTH - inset, 500f);
        paint.setColor(ReSonoTheme.withAlpha(ReSonoTheme.PANEL_RAISED, 255 - depth * 70));
        canvas.drawRoundRect(rect, 28f, 28f, paint);
        ReSonoTheme.glass(canvas, paint, rect, 28f, false);
    }

    private void drawFrontCard(Canvas canvas, JSONObject item, int accent) {
        if (item == null) return;
        float offset = settleOffset;
        RectF rect = new RectF(36f, 130f + offset, WIDTH - 36f, 524f + offset);
        paint.setColor(ReSonoTheme.PANEL);
        canvas.drawRoundRect(rect, 28f, 28f, paint);
        ReSonoTheme.glass(canvas, paint, rect, 28f, true);
        orb.setColor(accent).setEnergy(0.25f).setSpeed(0.7f);
        orb.draw(canvas, 240f, 252f + offset + orb.bob(4f), 68f);
        String sourceType = item.optString("sourceType");
        String kind = "builtin_calendar".equals(sourceType) ? "Calendar"
                : "builtin_tasks".equals(sourceType) ? "Tasks"
                : "plugin_card".equals(sourceType) ? "App" : "Creation";
        String title = item.optString("title", "Creation").trim();
        if (title.isEmpty()) title = "Creation";
        if (!kind.equalsIgnoreCase(title)) {
            ReSonoTheme.text(canvas, paint, kind.toUpperCase(java.util.Locale.ROOT), 240f, 362f + offset,
                    13f, ReSonoTheme.withAlpha(accent, 230), Paint.Align.CENTER, true);
        }
        if (title.length() > 22) title = title.substring(0, 21) + "…";
        ReSonoTheme.text(canvas, paint, title, 240f, 398f + offset, 30f,
                ReSonoTheme.INK, Paint.Align.CENTER, true);
        drawDescription(canvas, item.optString("description", ""), 240f, 432f + offset, 340f);
        ReSonoTheme.text(canvas, paint, "Tap to open", 240f, 500f + offset, 15f,
                ReSonoTheme.withAlpha(ReSonoTheme.INK, 150), Paint.Align.CENTER, false);
    }

    private int accentOf(JSONObject item) {
        if (item == null) return ReSonoTheme.ORB_BLUE;
        String source = item.optString("sourceType");
        if ("builtin_calendar".equals(source)) return ReSonoTheme.PINK;
        if ("builtin_tasks".equals(source)) return ReSonoTheme.AMBER;
        try { return Color.parseColor(item.optString("accent", "#1A73F2")); }
        catch (IllegalArgumentException ignored) { return ReSonoTheme.ORB_BLUE; }
    }

    private void drawDescription(Canvas canvas, String value, float centerX, float y, float width) {
        paint.setTextSize(17f);
        String remaining = value == null ? "" : value.trim();
        for (int line = 0; line < 2 && !remaining.isEmpty(); line++) {
            int count = paint.breakText(remaining, true, width, null);
            if (count < remaining.length()) {
                int space = remaining.lastIndexOf(' ', Math.max(0, count - 1));
                if (space > 0) count = space;
            }
            String text = remaining.substring(0, Math.max(1, count)).trim();
            if (line == 1 && count < remaining.length()) text += "…";
            ReSonoTheme.text(canvas, paint, text, centerX, y + line * 24f, 17f,
                    ReSonoTheme.MUTED, Paint.Align.CENTER, false);
            remaining = remaining.substring(Math.min(remaining.length(), Math.max(1, count))).trim();
        }
    }

    private void drawDots(Canvas canvas) {
        int count = Math.min(items.length(), 8);
        float spacing = 16f;
        float start = 240f - (count - 1) * spacing / 2f;
        for (int dot = 0; dot < count; dot++) {
            boolean active = dot == index;
            paint.setColor(active ? ReSonoTheme.INK : ReSonoTheme.withAlpha(ReSonoTheme.MUTED, 120));
            if (active) canvas.drawRoundRect(start + dot * spacing - 9f, 549f, start + dot * spacing + 9f, 555f, 3f, 3f, paint);
            else canvas.drawCircle(start + dot * spacing, 552f, 3f, paint);
        }
    }

    private void drawArrow(Canvas canvas, float centerX, boolean next) {
        ReSonoTheme.glass(canvas, paint, new RectF(centerX - 28f, 568f, centerX + 28f, 624f), 28f, false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ReSonoTheme.INK);
        float dir = next ? 1f : -1f;
        canvas.drawLine(centerX - 4f * dir, 586f, centerX + 5f * dir, 596f, paint);
        canvas.drawLine(centerX + 5f * dir, 596f, centerX - 4f * dir, 606f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }
}
