package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.resonolabs.runtime.host.TaskClient;
import com.resonolabs.ui.design.SamTheme;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Up to five open tasks inline. The circle completes a task (with a short undo window: tap the row
 * again), the text opens its detail, the header and "+N more" open the full Tasks page.
 */
public final class TasksWidget implements BoardWidget {
    private static final int MAX_ROWS = 5;
    private static final int HEADER = 0, TASK = 1, FOOTER = 2, EMPTY = 3, STATUS = 4;
    private static final float PAD = 22f;
    private static final float ROW = 60f;
    private static final float CIRCLE_X = 40f;
    private static final float TEXT_X = 70f;
    private static final long CHECK_ANIMATION_MS = 260L;

    private final BoardHost host;
    private final TaskClient client = new TaskClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GlassPanel glass = new GlassPanel();
    private final GlassPanel undoPill = new GlassPanel();
    private final OrbGlyph headerOrb = new OrbGlyph();
    private final RectF rect = new RectF();
    private final TaskCompletions completions = new TaskCompletions();
    private final Map<String, Long> checkedAt = new HashMap<>();
    private final List<Item> items = new ArrayList<>();
    private final List<Item> focusable = new ArrayList<>();
    private List<JSONObject> tasks = new ArrayList<>();
    private boolean loaded;
    private boolean failed;
    private long errorUntil;
    private long lastCheckAt = Long.MIN_VALUE / 2;
    private float width;
    private String summary = "";

    private static final class Item {
        int type;
        float top;
        float height;
        String id = "";
        String text = "";
        float textWidth;
        boolean checked;
        boolean pending;
        JSONObject task;
    }

    private final Runnable commitDue = this::commitDue;

    public TasksWidget(BoardHost host) {
        this.host = host;
    }

    @Override public String id() { return "tasks"; }

    // ------------------------------------------------------------- data

    @Override public long refreshIntervalMs() { return 15_000L; }

    @Override public void refresh() {
        BoardFixtures.Mode mode = host.fixtureMode();
        if (mode != BoardFixtures.Mode.OFF) { apply(BoardFixtures.tasks(mode)); return; }
        client.loadActive(host.context(), new TaskClient.Callback() {
            @Override public void onTasks(JSONObject value) { apply(value); }
            @Override public void onFailure() {
                failed = true;
                if (!loaded) host.widgetChanged(TasksWidget.this);
            }
        });
    }

    private void apply(JSONObject value) {
        JSONArray list = value.optJSONArray("tasks");
        List<JSONObject> next = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject task = list.optJSONObject(i);
                if (task == null || !"open".equals(task.optString("status", "open"))) continue;
                next.add(task);
                ids.add(task.optString("taskId"));
            }
        }
        completions.reconcile(ids);
        checkedAt.keySet().retainAll(new java.util.HashSet<>(ids));
        tasks = next;
        loaded = true;
        failed = false;
        host.widgetChanged(this);
    }

    /** Open tasks as the widget currently knows them (for the full page in fixture mode). */
    public JSONObject snapshot() {
        JSONArray list = new JSONArray();
        for (JSONObject task : tasks) if (!completions.isHidden(task.optString("taskId"))) list.put(task);
        JSONObject value = new JSONObject();
        try { value.put("tasks", list); } catch (Exception ignored) { }
        return value;
    }

    private void toggle(Item item) {
        long now = SystemClock.uptimeMillis();
        boolean checked = completions.toggle(item.id, now);
        if (checked) { checkedAt.put(item.id, now); lastCheckAt = now; } else checkedAt.remove(item.id);
        scheduleCommit();
        host.widgetChanged(this);
    }

    private void scheduleCommit() {
        handler.removeCallbacks(commitDue);
        long next = completions.nextDeadline();
        if (next != Long.MAX_VALUE) handler.postAtTime(commitDue, next);
    }

    private void commitDue() {
        commit(completions.due(SystemClock.uptimeMillis()));
        scheduleCommit();
    }

    private void commit(List<String> ids) {
        if (ids.isEmpty()) return;
        BoardFixtures.Mode mode = host.fixtureMode();
        for (String id : ids) {
            if (mode != BoardFixtures.Mode.OFF) {
                BoardFixtures.complete(id);
                completions.committed(id);
                continue;
            }
            client.complete(host.context(), id, new TaskClient.CompletionCallback() {
                @Override public void onCompleted(JSONObject value) {
                    completions.committed(id);
                    host.widgetChanged(TasksWidget.this);
                }
                @Override public void onFailure() {
                    completions.failed(id);
                    checkedAt.remove(id);
                    errorUntil = SystemClock.uptimeMillis() + 3500L;
                    host.widgetChanged(TasksWidget.this);
                    handler.postDelayed(() -> host.widgetChanged(TasksWidget.this), 3600L);
                }
            });
        }
        host.widgetChanged(this);
    }

    @Override public void onHide() {
        handler.removeCallbacks(commitDue);
        commit(completions.flush());
    }

    @Override public void close() {
        onHide();
        client.close();
    }

    // ------------------------------------------------------------- layout

    @Override public float measure(float width, long nowMs) {
        this.width = width;
        items.clear();
        focusable.clear();
        float y = 0f;
        Item header = add(HEADER, y, 58f);
        y += header.height;
        int open = 0;
        for (JSONObject task : tasks) if (!completions.isHidden(task.optString("taskId"))) open++;
        if (!loaded) {
            Item status = add(STATUS, y, 64f);
            status.text = failed ? "Tasks unavailable right now" : "Loading tasks…";
            y += status.height;
        } else if (open == 0) {
            Item empty = add(EMPTY, y, 76f);
            empty.text = "All clear";
            y += empty.height;
        } else {
            int shown = 0;
            for (JSONObject task : tasks) {
                String id = task.optString("taskId");
                if (completions.isHidden(id)) continue;
                if (shown == MAX_ROWS) break;
                Item item = add(TASK, y, ROW);
                item.id = id;
                item.task = task;
                item.pending = completions.isPending(id);
                item.checked = completions.isChecked(id);
                float right = width - PAD - (item.pending ? 92f : 0f);
                item.text = BoardPaint.fit(paint, task.optString("text", "Task"), right - TEXT_X, 19f, BoardPaint.MEDIUM);
                item.textWidth = BoardPaint.width(paint, item.text, 19f, BoardPaint.MEDIUM);
                y += item.height;
                shown++;
            }
            if (open > shown) {
                Item footer = add(FOOTER, y, 54f);
                footer.text = "+" + (open - shown) + " more";
                y += footer.height;
            }
        }
        y += 8f;
        summary = !loaded ? "" : open == 0 ? "" : open + " open";
        glass.size(width, y, 28f);
        undoPill.size(78f, 34f, 17f);
        headerOrb.set(31f, 30f, 7f, BoardPaint.TASKS_ACCENT, 2.6f);
        return y;
    }

    private Item add(int type, float top, float height) {
        Item item = new Item();
        item.type = type;
        item.top = top;
        item.height = height;
        items.add(item);
        if (type == HEADER || type == TASK || type == FOOTER) focusable.add(item);
        return item;
    }

    // ------------------------------------------------------------- drawing

    @Override public void draw(Canvas canvas, long nowMs) {
        glass.draw(canvas, paint, false);
        long uptime = SystemClock.uptimeMillis();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            switch (item.type) {
                case HEADER -> drawHeader(canvas, uptime);
                case TASK -> drawTask(canvas, item, i > 0 && items.get(i - 1).type == TASK, uptime);
                case FOOTER -> {
                    separator(canvas, item.top);
                    BoardPaint.text(canvas, paint, item.text, PAD, item.top + 33f, 16f, SamTheme.ORB_PALE,
                            Paint.Align.LEFT, BoardPaint.MEDIUM);
                    BoardPaint.text(canvas, paint, "All tasks", width - 42f, item.top + 33f, 15f, SamTheme.MUTED,
                            Paint.Align.RIGHT, BoardPaint.REGULAR);
                    chevron(canvas, width - 28f, item.top + 28f);
                }
                case EMPTY -> {
                    BoardPaint.text(canvas, paint, item.text, PAD, item.top + 31f, 19f, SamTheme.INK,
                            Paint.Align.LEFT, BoardPaint.MEDIUM);
                    BoardPaint.text(canvas, paint, "Ask Voice to add a task.", PAD, item.top + 56f, 15f,
                            SamTheme.MUTED, Paint.Align.LEFT, BoardPaint.REGULAR);
                }
                case STATUS -> BoardPaint.text(canvas, paint, item.text, PAD, item.top + 36f, 16f, SamTheme.MUTED,
                        Paint.Align.LEFT, BoardPaint.REGULAR);
                default -> { }
            }
        }
    }

    private void drawHeader(Canvas canvas, long uptime) {
        headerOrb.draw(canvas, paint);
        BoardPaint.eyebrow(canvas, paint, "TASKS", 47f, 36f, 14f, SamTheme.withAlpha(SamTheme.INK, 230), Paint.Align.LEFT);
        if (uptime < errorUntil) {
            BoardPaint.text(canvas, paint, "Couldn't save", width - 42f, 36f, 15f, SamTheme.RED, Paint.Align.RIGHT,
                    BoardPaint.MEDIUM);
        } else if (!summary.isEmpty()) {
            BoardPaint.text(canvas, paint, summary, width - 42f, 36f, 15f, SamTheme.MUTED, Paint.Align.RIGHT,
                    BoardPaint.REGULAR);
        }
        chevron(canvas, width - 28f, 31f);
    }

    private void drawTask(Canvas canvas, Item item, boolean separated, long uptime) {
        if (separated) separator(canvas, item.top);
        float cy = item.top + item.height / 2f;
        Long at = checkedAt.get(item.id);
        float t = !item.checked ? 0f : at == null ? 1f : Math.min(1f, (uptime - at) / (float) CHECK_ANIMATION_MS);
        float eased = 1f - (1f - t) * (1f - t);
        paint.setShader(null);
        if (item.checked) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BoardPaint.TASKS_ACCENT);
            canvas.drawCircle(CIRCLE_X, cy, 6f + 7.5f * eased, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(SamTheme.BACKGROUND);
            float first = Math.min(1f, eased * 2f);
            float second = Math.max(0f, eased * 2f - 1f);
            canvas.drawLine(CIRCLE_X - 5.5f, cy + 0.5f, CIRCLE_X - 5.5f + 3.5f * first, cy + 0.5f + 4f * first, paint);
            if (second > 0f) canvas.drawLine(CIRCLE_X - 2f, cy + 4.5f, CIRCLE_X - 2f + 8f * second, cy + 4.5f - 9f * second, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            paint.setColor(SamTheme.withAlpha(SamTheme.MUTED, 210));
            canvas.drawCircle(CIRCLE_X, cy, 13f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        BoardPaint.text(canvas, paint, item.text, TEXT_X, cy + 7f, 19f,
                item.checked ? SamTheme.MUTED : SamTheme.INK, Paint.Align.LEFT, BoardPaint.MEDIUM);
        if (item.checked) {
            paint.setColor(SamTheme.withAlpha(SamTheme.MUTED, 220));
            canvas.drawRect(TEXT_X, cy - 0.5f, TEXT_X + item.textWidth * eased, cy + 1.5f, paint);
        }
        if (item.pending) {
            canvas.save();
            canvas.translate(width - PAD - 78f, cy - 17f);
            undoPill.draw(canvas, paint, false);
            BoardPaint.text(canvas, paint, "Undo", 39f, 23f, 15f, SamTheme.INK, Paint.Align.CENTER, BoardPaint.MEDIUM);
            canvas.restore();
        }
    }

    private void separator(Canvas canvas, float y) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.LINE);
        canvas.drawRect(TEXT_X, y, width - PAD, y + 1f, paint);
    }

    private void chevron(Canvas canvas, float cx, float cy) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(SamTheme.MUTED);
        canvas.drawLine(cx - 3f, cy - 6f, cx + 3f, cy, paint);
        canvas.drawLine(cx + 3f, cy, cx - 3f, cy + 6f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean animating(long nowMs) {
        return SystemClock.uptimeMillis() - lastCheckAt < CHECK_ANIMATION_MS + 20L;
    }

    // ------------------------------------------------------------- input

    @Override public int focusCount() { return focusable.size(); }

    @Override public void focusBounds(int index, RectF out) {
        Item item = focusable.get(index);
        if (item.type == HEADER) out.set(6f, 4f, width - 6f, item.height - 2f);
        else out.set(6f, item.top + 1f, width - 6f, item.top + item.height - 1f);
    }

    @Override public boolean onTap(float x, float y) {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (y < item.top || y >= item.top + item.height) continue;
            if (item.type == TASK) {
                // The circle (left 76 px) completes; a checked row anywhere undoes; the text opens detail.
                if (x < TEXT_X + 6f || item.pending) toggle(item);
                else if (!item.checked) host.openTask(item.task);
                return true;
            }
            if (item.type == STATUS && failed) { refresh(); return true; }
            host.openTasks();
            return true;
        }
        host.openTasks();
        return true;
    }

    /** Wheel ACTIVATE on a task toggles its completion (same undo window). */
    @Override public boolean activate(int index) {
        if (index < 0 || index >= focusable.size()) return false;
        Item item = focusable.get(index);
        if (item.type == TASK) toggle(item);
        else host.openTasks();
        return true;
    }
}
