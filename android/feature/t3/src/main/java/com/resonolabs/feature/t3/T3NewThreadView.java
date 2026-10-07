package com.resonolabs.feature.t3;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;

import java.util.ArrayList;
import java.util.List;

/**
 * New thread: pick a project (defaults to the most recently active one), then Type or Talk the
 * first prompt. Wheel walks projects then the two buttons; center key picks / presses.
 */
final class T3NewThreadView extends View {
    interface Actions {
        void back();
        void create(T3Model.Project project, String text);
        void talk(T3Model.Project project);
    }

    private static final float W = 480f;
    private static final float H = 640f;
    private static final float LIST_TOP = 132f;
    private static final float LIST_BOTTOM = 548f;
    private static final float ROW_H = 58f;
    private static final float ROW_GAP = 8f;
    private static final RectF BACK = new RectF(16f, 18f, 68f, 70f);
    private static final RectF TYPE = new RectF(16f, 568f, 236f, 624f);
    private static final RectF TALK = new RectF(244f, 568f, 464f, 624f);

    private final Activity activity;
    private final T3Surface surface = new T3Surface();
    private final FluidOrb orb = new FluidOrb().setColor(SamTheme.ORB_BLUE).setEnergy(0.8f).setSpeed(1.6f);
    private final T3Toast toast;
    private final Actions actions;
    private final RectF rect = new RectF();
    private final List<T3Model.Project> projects = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private int selected;
    /** 0..n-1 project rows, n = Type, n+1 = Talk. */
    private int focus;
    private float scroll;
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private boolean creating;
    private String creatingText = "";

    T3NewThreadView(Activity activity, T3Toast toast, Actions actions) {
        super(activity);
        this.activity = activity;
        this.toast = toast;
        this.actions = actions;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("New T3 thread");
    }

    void open(List<T3Model.Project> choices, T3Model.Project preferred) {
        projects.clear();
        names.clear();
        if (choices != null) projects.addAll(choices);
        if (projects.isEmpty()) projects.add(new T3Model.Project("", "Most recent project"));
        selected = 0;
        if (preferred != null) {
            for (int i = 0; i < projects.size(); i++) if (projects.get(i).id.equals(preferred.id)) selected = i;
        }
        for (T3Model.Project project : projects) {
            names.add(surface.ellipsize(project.title, 360f, 18f, T3Surface.MEDIUM));
        }
        focus = projects.size(); // Type: the default project is usually right.
        scroll = 0f;
        creating = false;
        reveal(selected);
        invalidate();
    }

    void creating(boolean busy, String text) {
        creating = busy;
        creatingText = text == null ? "" : "“" + surface.ellipsize(text, 380f, 15f, T3Surface.REGULAR) + "”";
        invalidate();
    }

    private T3Model.Project project() {
        return projects.isEmpty() ? null : projects.get(Math.max(0, Math.min(selected, projects.size() - 1)));
    }

    private void type() {
        T3Model.Project project = project();
        if (project == null || creating) return;
        String where = project.id.isEmpty() ? "What should it do?" : "What should it do in " + project.title + "?";
        T3Composer.open(activity, where, "Start", value -> actions.create(project(), value));
    }

    private void talk() {
        if (!creating && project() != null) actions.talk(project());
    }

    // ---- input -----------------------------------------------------------------------------

    boolean onInput(UiInputIntent intent) {
        if (intent == UiInputIntent.BACK) return false;
        if (creating) return true;
        int last = projects.size() + 1;
        switch (intent) {
            case NEXT -> focus = Math.min(last, focus + 1);
            case PREVIOUS -> focus = Math.max(0, focus - 1);
            case ACTIVATE -> {
                if (focus < projects.size()) {
                    selected = focus;
                    focus = projects.size();
                } else if (focus == projects.size()) {
                    type();
                } else {
                    talk();
                }
            }
            default -> { }
        }
        if (focus < projects.size()) reveal(focus);
        invalidate();
        return true;
    }

    private void reveal(int index) {
        float top = index * (ROW_H + ROW_GAP);
        float bottom = top + ROW_H;
        float view = LIST_BOTTOM - LIST_TOP;
        if (top < scroll) scroll = top;
        else if (bottom > scroll + view) scroll = bottom - view;
        scroll = Math.max(0f, Math.min(maxScroll(), scroll));
    }

    private float maxScroll() {
        float content = projects.size() * (ROW_H + ROW_GAP);
        return Math.max(0f, content - (LIST_BOTTOM - LIST_TOP));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() * W / Math.max(1f, getWidth());
        float y = event.getY() * H / Math.max(1f, getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = x;
                downY = y;
                lastY = y;
                dragging = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.abs(y - downY) > 12f && downY >= LIST_TOP && downY <= LIST_BOTTOM) dragging = true;
                if (dragging) {
                    scroll = Math.max(0f, Math.min(maxScroll(), scroll - (y - lastY)));
                    invalidate();
                }
                lastY = y;
            }
            case MotionEvent.ACTION_UP -> {
                if (!dragging && Math.abs(x - downX) < 16f && Math.abs(y - downY) < 16f) tap(x, y);
                dragging = false;
            }
            default -> { }
        }
        return true;
    }

    private void tap(float x, float y) {
        if (hit(BACK, x, y, 10f)) {
            actions.back();
            return;
        }
        if (creating) return;
        if (hit(TYPE, x, y, 4f)) {
            focus = projects.size();
            type();
            return;
        }
        if (hit(TALK, x, y, 4f)) {
            focus = projects.size() + 1;
            talk();
            return;
        }
        if (y >= LIST_TOP && y <= LIST_BOTTOM) {
            int index = (int) ((y - LIST_TOP + scroll) / (ROW_H + ROW_GAP));
            if (index >= 0 && index < projects.size()) {
                selected = index;
                focus = projects.size();
                invalidate();
            }
        }
    }

    private static boolean hit(RectF r, float x, float y, float slop) {
        return x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop;
    }

    // ---- drawing ---------------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        surface.background(canvas, W, H, 240f, 600f, 300f, SamTheme.ORB_BLUE);
        surface.glass(canvas, BACK, 26f, false);
        surface.chevronLeft(canvas, BACK.centerX() - 1f, BACK.centerY(), SamTheme.INK);
        surface.text(canvas, "New thread", 82f, 41f, 21f, SamTheme.INK, Paint.Align.LEFT, T3Surface.MEDIUM);
        surface.text(canvas, "Pick a project, then type or talk", 82f, 66f, 14f, SamTheme.MUTED,
                Paint.Align.LEFT, T3Surface.REGULAR);
        surface.label(canvas, "PROJECT", 26f, 118f, SamTheme.MUTED, Paint.Align.LEFT);

        canvas.save();
        canvas.clipRect(0f, LIST_TOP - 4f, W, LIST_BOTTOM);
        for (int i = 0; i < projects.size(); i++) {
            float top = LIST_TOP + i * (ROW_H + ROW_GAP) - scroll;
            if (top > LIST_BOTTOM || top + ROW_H < LIST_TOP - 4f) continue;
            rect.set(16f, top, 464f, top + ROW_H);
            boolean chosen = i == selected;
            if (chosen) surface.tinted(canvas, rect, 20f, SamTheme.ORB_BLUE, 40, 150);
            else surface.glass(canvas, rect, 20f, false);
            float cy = top + ROW_H / 2f;
            if (chosen) {
                surface.paint.setColor(SamTheme.ORB_PALE);
                canvas.drawCircle(46f, cy, 12f, surface.paint);
                surface.check(canvas, 46f, cy, SamTheme.BACKGROUND);
            } else {
                surface.paint.setStyle(Paint.Style.STROKE);
                surface.paint.setStrokeWidth(2f);
                surface.paint.setColor(SamTheme.withAlpha(SamTheme.MUTED, 170));
                canvas.drawCircle(46f, cy, 11f, surface.paint);
                surface.paint.setStyle(Paint.Style.FILL);
            }
            surface.text(canvas, names.get(i), 72f, cy + 6.5f, 18f, chosen ? SamTheme.INK
                    : SamTheme.withAlpha(SamTheme.INK, 210), Paint.Align.LEFT, T3Surface.MEDIUM);
            if (i == focus) surface.focus(canvas, rect, 20f);
        }
        canvas.restore();
        surface.fadeEdges(canvas, 0f, W, LIST_TOP - 4f, LIST_BOTTOM, 12f, scroll > 1f, scroll < maxScroll() - 1f);

        surface.glass(canvas, TYPE, 28f, false);
        float typeText = surface.measure("Type", 19f, T3Surface.MEDIUM);
        surface.pencil(canvas, TYPE.centerX() - typeText / 2f - 14f, TYPE.centerY(), SamTheme.INK);
        surface.text(canvas, "Type", TYPE.centerX() - typeText / 2f + 4f, TYPE.centerY() + 7f, 19f, SamTheme.INK,
                Paint.Align.LEFT, T3Surface.MEDIUM);
        surface.primary(canvas, TALK, 28f);
        float talkText = surface.measure("Talk", 19f, T3Surface.MEDIUM);
        surface.mic(canvas, TALK.centerX() - talkText / 2f - 14f, TALK.centerY(), SamTheme.INK);
        surface.text(canvas, "Talk", TALK.centerX() - talkText / 2f + 4f, TALK.centerY() + 7f, 19f, SamTheme.INK,
                Paint.Align.LEFT, T3Surface.MEDIUM);
        if (focus == projects.size()) surface.focus(canvas, TYPE, 28f);
        if (focus == projects.size() + 1) surface.focus(canvas, TALK, 28f);

        boolean animating = false;
        if (creating) {
            surface.solid(canvas, rect(0f, 0f, W, H), 0f, SamTheme.withAlpha(SamTheme.BACKGROUND, 215));
            orb.draw(canvas, 240f, 280f + orb.bob(4f), 46f);
            surface.text(canvas, "Starting thread…", 240f, 372f, 22f, SamTheme.INK, Paint.Align.CENTER, T3Surface.MEDIUM);
            surface.text(canvas, creatingText, 240f, 402f, 15f, SamTheme.MUTED, Paint.Align.CENTER, T3Surface.REGULAR);
            animating = true;
        }
        animating |= toast.draw(canvas, surface, 520f);
        canvas.restore();
        if (animating && isShown()) postInvalidateDelayed(33L);
    }

    private RectF rect(float left, float top, float right, float bottom) {
        rect.set(left, top, right, bottom);
        return rect;
    }
}
