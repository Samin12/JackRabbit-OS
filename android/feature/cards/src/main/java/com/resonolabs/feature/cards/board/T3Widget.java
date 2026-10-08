package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.resonolabs.feature.t3.T3Glance;
import com.resonolabs.runtime.host.T3Client;
import com.resonolabs.ui.design.SamTheme;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * T3 Code at a glance: "2 need you · 1 working" and up to three threads (needs you first, then
 * working, failed and fresh results) with the T3 tab's status orbs. Polls {@code /v1/t3/threads}
 * every 5 s while the board is shown and skips all work when {@code revision} is unchanged.
 * Hidden until T3 is paired; re-pair, unreachable and runtime-starting states read like the T3
 * tab's. A row opens that thread in the T3 tab, the header and "+N more" open the tab.
 */
public final class T3Widget implements BoardWidget {
    enum State { LOADING, READY, HIDDEN, REAUTH, UNREACHABLE, RUNTIME_DOWN }

    private static final int MAX_ROWS = 3;
    private static final int LIST_LIMIT = 20;
    private static final int HEADER = 0, THREAD = 1, FOOTER = 2, STATUS = 3;
    private static final float PAD = 22f;
    private static final float ROW = 68f;
    private static final float ORB_X = 40f;
    private static final float TEXT_X = 68f;
    private static final long VISIBLE_POLL_MS = 5_000L;
    private static final long HIDDEN_POLL_MS = 30_000L;
    /** ~25 fps for the "working" arc; only while such a row is on screen. */
    private static final long SPIN_FRAME_MS = 40L;

    private final BoardHost host;
    private final T3Client client = new T3Client();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GlassPanel glass = new GlassPanel();
    private final OrbGlyph headerOrb = new OrbGlyph();
    private final StatusOrb orb = new StatusOrb();
    private final RectF rect = new RectF();
    private final List<Item> items = new ArrayList<>();
    private final List<Item> focusable = new ArrayList<>();
    private final List<T3Glance.Part> headline = new ArrayList<>();
    private final float[] headlineX = new float[8];
    private State state = State.LOADING;
    private T3Glance glance;
    private long revision = -1L;
    private boolean inFlight;
    private boolean statusInFlight;
    private boolean fixture;
    private String fixtureState = "";
    private float width;
    private boolean spinning;
    private String headerNote = "";

    private static final class Item {
        int type;
        float top;
        float height;
        String key = "";
        String title = "";
        String label = "";
        String meta = "";
        float labelWidth;
        T3Glance.Row row;
        boolean tinted;
        boolean separator;
        boolean spinning;
        boolean background;
    }

    public T3Widget(BoardHost host) {
        this.host = host;
    }

    @Override public String id() { return "t3"; }

    // ------------------------------------------------------------- data

    @Override public long refreshIntervalMs() {
        return state == State.HIDDEN ? HIDDEN_POLL_MS : VISIBLE_POLL_MS;
    }

    @Override public void onShow() {
        BoardFixtures.Mode mode = host.fixtureMode();
        String override = host.fixtureState(id());
        boolean nextFixture = mode != BoardFixtures.Mode.OFF || !override.isEmpty();
        if (nextFixture != fixture || !override.equals(fixtureState)) {
            fixture = nextFixture;
            fixtureState = override;
            revision = -1L;
            glance = null;
            state = State.LOADING;
            host.widgetChanged(this);
        }
    }

    @Override public void refresh() {
        if (fixture) {
            applyFixture();
            return;
        }
        if (inFlight) return;
        inFlight = true;
        client.pollThreads(host.context(), LIST_LIMIT, glance == null ? -1L : revision, new T3Client.PollCallback() {
            @Override public void onChanged(JSONObject value) {
                inFlight = false;
                T3Glance next = T3Glance.from(value, MAX_ROWS);
                if (!next.connected) {
                    // Unpaired or waiting for re-pairing: never show a stale list.
                    glance = null;
                    revision = -1L;
                    checkStatus();
                    return;
                }
                glance = next;
                revision = next.revision;
                // Before its first sync the runtime lists nothing even when the Mac is unreachable.
                if (next.threadCount == 0) checkStatus();
                else show(State.READY, "");
            }

            @Override public void onUnchanged() {
                inFlight = false;
                if (state != State.READY && glance != null) show(State.READY, "");
            }

            @Override public void onFailure(T3Client.Failure failure) {
                inFlight = false;
                if (failure.routeMissing() || failure.notConnected()) {
                    glance = null;
                    revision = -1L;
                    show(State.HIDDEN, "");
                } else if (failure.httpStatus == 409 && "t3_reauth_required".equals(failure.code)) {
                    glance = null;
                    revision = -1L;
                    show(State.REAUTH, "");
                } else if (failure.runtimeUnavailable()) {
                    show(glance != null ? State.READY : State.RUNTIME_DOWN, glance != null ? "Runtime offline" : "");
                } else {
                    checkStatus();
                }
            }
        });
    }

    /** Asks /v1/t3/status why the list is empty or failing (like the T3 tab). */
    private void checkStatus() {
        if (statusInFlight) return;
        statusInFlight = true;
        client.status(host.context(), new T3Client.Callback() {
            @Override public void onResult(JSONObject value) {
                statusInFlight = false;
                String health = value.isNull("healthState") ? "" : value.optString("healthState", "");
                boolean connected = value.optBoolean("connected", false);
                switch (health) {
                    case "reauth" -> show(State.REAUTH, "");
                    case "failed" -> show(glance != null && !glance.rows.isEmpty() ? State.READY : State.UNREACHABLE,
                            glance != null && !glance.rows.isEmpty() ? "Can't reach T3" : "");
                    case "ready" -> show(connected && glance != null ? State.READY : connected ? State.LOADING
                            : State.HIDDEN, "");
                    default -> show(State.HIDDEN, "");
                }
            }

            @Override public void onFailure(T3Client.Failure failure) {
                statusInFlight = false;
                if (failure.runtimeUnavailable()) show(glance != null ? State.READY : State.RUNTIME_DOWN, "");
                else if (failure.routeMissing() || failure.notConnected()) show(State.HIDDEN, "");
                else show(glance != null ? State.READY : State.UNREACHABLE, glance != null ? "Can't reach T3" : "");
            }
        });
    }

    private void show(State next, String note) {
        if (next == state && note.equals(headerNote) && next != State.READY) return;
        state = next;
        headerNote = note;
        host.widgetChanged(this);
    }

    private void applyFixture() {
        switch (fixtureState) {
            case "reauth" -> { glance = null; show(State.REAUTH, ""); return; }
            case "unreachable" -> { glance = null; show(State.UNREACHABLE, ""); return; }
            case "down", "starting" -> { glance = null; show(State.RUNTIME_DOWN, ""); return; }
            case "hidden", "unconfigured" -> { glance = null; show(State.HIDDEN, ""); return; }
            default -> { }
        }
        JSONObject value = BoardFixtures.t3(host.fixtureMode(), System.currentTimeMillis());
        if (value == null) {
            glance = null;
            show(State.HIDDEN, "");
            return;
        }
        T3Glance next = T3Glance.from(value, MAX_ROWS);
        if (glance != null && next.revision == glance.revision && state == State.READY) return;
        glance = next;
        revision = next.revision;
        show(State.READY, "");
    }

    // ------------------------------------------------------------- layout

    @Override public float measure(float width, long nowMs) {
        this.width = width;
        items.clear();
        focusable.clear();
        headline.clear();
        spinning = false;
        if (state == State.HIDDEN) return 0f;
        float y = 0f;
        Item header = add(HEADER, y, 58f, "h");
        y += header.height;
        if (state == State.READY && glance != null) {
            if (headerNote.isEmpty()) headline.addAll(glance.headline());
            Item previous = null;
            for (T3Glance.Row row : glance.rows) {
                Item item = add(THREAD, y, ROW, "t:" + row.id);
                item.row = row;
                item.tinted = row.needsYou;
                item.separator = previous != null && !previous.tinted && !item.tinted;
                item.background = row.working && isBackground(row.label);
                item.spinning = row.working && !item.background;
                spinning |= item.spinning;
                float right = width - PAD - (row.unread && !row.working && !row.needsYou ? 18f : 0f);
                item.title = BoardPaint.fit(paint, row.title, right - TEXT_X, 18.5f, BoardPaint.MEDIUM);
                String label = glance.lastOnly ? "Last · " + row.label : row.label;
                item.label = BoardPaint.fit(paint, label, 210f, 14f, BoardPaint.MEDIUM);
                item.labelWidth = BoardPaint.width(paint, item.label, 14f, BoardPaint.MEDIUM);
                String meta = row.meta(nowMs);
                item.meta = meta.isEmpty() ? "" : BoardPaint.fit(paint, "  ·  " + meta,
                        Math.max(0f, right - TEXT_X - item.labelWidth), 14f, BoardPaint.REGULAR);
                y += item.height;
                previous = item;
            }
            if (glance.rows.isEmpty()) {
                Item empty = add(STATUS, y, 64f, "s");
                empty.title = "No threads yet";
                empty.label = "Start one in the T3 tab or ask Voice.";
                y += empty.height;
            }
            if (glance.more > 0) {
                Item footer = add(FOOTER, y, 52f, "more");
                footer.title = "+" + glance.more + " more";
                footer.separator = true;
                y += footer.height;
            }
        } else {
            Item status = add(STATUS, y, 74f, "s");
            switch (state) {
                case REAUTH -> { status.title = "Pair T3 Code again"; status.label = "in Settings → Management"; }
                case UNREACHABLE -> { status.title = "Can't reach T3 Code"; status.label = "Is your Mac awake and on this Wi-Fi?"; }
                case RUNTIME_DOWN -> { status.title = "Starting up…"; status.label = "Waiting for the SamRabbit runtime"; }
                default -> { status.title = "Loading threads…"; status.label = ""; status.height = 58f; }
            }
            y += status.height;
        }
        y += 8f;
        layoutHeadline(width);
        glass.size(width, y, 28f);
        int accent = glance != null && glance.needsYou > 0 && state == State.READY ? T3Glance.AMBER
                : state == State.REAUTH ? T3Glance.AMBER : state == State.UNREACHABLE ? T3Glance.RED : SamTheme.ORB_BLUE;
        headerOrb.set(31f, 30f, 7f, accent, 2.6f);
        return y;
    }

    /** "Background work" / "Monitoring": live but nothing to watch; drawn still to save battery. */
    private static boolean isBackground(String label) {
        return "Background work".equals(label) || "Monitoring".equals(label);
    }

    private void layoutHeadline(float width) {
        float right = width - 42f;
        float separator = BoardPaint.width(paint, " · ", 15f, BoardPaint.MEDIUM);
        while (headline.size() > 1) {
            float total = 0f;
            for (int i = 0; i < headline.size(); i++) total += BoardPaint.width(paint, headline.get(i).text, 15f, BoardPaint.MEDIUM);
            total += separator * (headline.size() - 1);
            if (total <= right - 150f) break;
            headline.remove(headline.size() - 1);
        }
        float total = 0f;
        for (int i = 0; i < headline.size() && i < headlineX.length; i++) {
            total += BoardPaint.width(paint, headline.get(i).text, 15f, BoardPaint.MEDIUM);
        }
        total += separator * Math.max(0, headline.size() - 1);
        float x = right - total;
        for (int i = 0; i < headline.size() && i < headlineX.length; i++) {
            headlineX[i] = x;
            x += BoardPaint.width(paint, headline.get(i).text, 15f, BoardPaint.MEDIUM) + separator;
        }
    }

    private Item add(int type, float top, float height, String key) {
        Item item = new Item();
        item.type = type;
        item.top = top;
        item.height = height;
        item.key = key;
        items.add(item);
        boolean actionable = type != STATUS || state == State.REAUTH || state == State.UNREACHABLE
                || (state == State.READY && glance != null);
        if (actionable) focusable.add(item);
        return item;
    }

    // ------------------------------------------------------------- drawing

    @Override public void draw(Canvas canvas, long nowMs) {
        glass.draw(canvas, paint, false);
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.separator) {
                paint.setShader(null);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(SamTheme.LINE);
                canvas.drawRect(item.type == FOOTER ? PAD : TEXT_X, item.top, width - PAD, item.top + 1f, paint);
            }
            switch (item.type) {
                case HEADER -> drawHeader(canvas);
                case THREAD -> drawThread(canvas, item, nowMs);
                case FOOTER -> {
                    BoardPaint.text(canvas, paint, item.title, PAD, item.top + 32f, 16f, SamTheme.ORB_PALE,
                            Paint.Align.LEFT, BoardPaint.MEDIUM);
                    BoardPaint.text(canvas, paint, "All threads", width - 42f, item.top + 32f, 15f, SamTheme.MUTED,
                            Paint.Align.RIGHT, BoardPaint.REGULAR);
                    chevron(canvas, width - 28f, item.top + 27f);
                }
                case STATUS -> drawStatus(canvas, item);
                default -> { }
            }
        }
    }

    private void drawHeader(Canvas canvas) {
        headerOrb.draw(canvas, paint);
        BoardPaint.eyebrow(canvas, paint, "T3 CODE", 47f, 36f, 14f, SamTheme.withAlpha(SamTheme.INK, 230), Paint.Align.LEFT);
        if (!headerNote.isEmpty()) {
            BoardPaint.text(canvas, paint, headerNote, width - 42f, 36f, 15f, SamTheme.AMBER, Paint.Align.RIGHT,
                    BoardPaint.MEDIUM);
        } else {
            for (int i = 0; i < headline.size() && i < headlineX.length; i++) {
                T3Glance.Part part = headline.get(i);
                if (i > 0) {
                    BoardPaint.text(canvas, paint, " · ", headlineX[i] - 0.5f, 36f, 15f, SamTheme.MUTED, Paint.Align.RIGHT,
                            BoardPaint.MEDIUM);
                }
                BoardPaint.text(canvas, paint, part.text, headlineX[i], 36f, 15f, part.color, Paint.Align.LEFT,
                        BoardPaint.MEDIUM);
            }
        }
        chevron(canvas, width - 28f, 31f);
    }

    private void drawThread(Canvas canvas, Item item, long now) {
        T3Glance.Row row = item.row;
        float top = item.top;
        float cy = top + item.height / 2f;
        if (item.tinted) {
            rect.set(10f, top + 4f, width - 10f, top + item.height - 4f);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(SamTheme.withAlpha(T3Glance.AMBER, 22));
            canvas.drawRoundRect(rect, 20f, 20f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.3f);
            paint.setColor(SamTheme.withAlpha(T3Glance.AMBER, 76));
            canvas.drawRoundRect(rect, 20f, 20f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        orb.draw(canvas, paint, ORB_X, cy, 8f, row.color, item.spinning, now);
        if (item.background) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f);
            paint.setColor(SamTheme.withAlpha(row.color, 110));
            canvas.drawCircle(ORB_X, cy, 13f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        BoardPaint.text(canvas, paint, item.title, TEXT_X, top + 30f, 18.5f,
                row.quiet ? SamTheme.withAlpha(SamTheme.INK, 196) : SamTheme.INK, Paint.Align.LEFT, BoardPaint.MEDIUM);
        BoardPaint.text(canvas, paint, item.label, TEXT_X, top + 52f, 14f, row.quiet ? SamTheme.MUTED : row.color,
                Paint.Align.LEFT, BoardPaint.MEDIUM);
        if (!item.meta.isEmpty()) {
            BoardPaint.text(canvas, paint, item.meta, TEXT_X + item.labelWidth, top + 52f, 14f, SamTheme.MUTED,
                    Paint.Align.LEFT, BoardPaint.REGULAR);
        }
        if (row.working && row.progress >= 0d) {
            float right = width - PAD;
            paint.setColor(SamTheme.withAlpha(SamTheme.INK, 26));
            canvas.drawRoundRect(TEXT_X, top + 60f, right, top + 62.5f, 1.25f, 1.25f, paint);
            paint.setColor(SamTheme.withAlpha(T3Glance.BLUE, 220));
            canvas.drawRoundRect(TEXT_X, top + 60f, TEXT_X + (float) ((right - TEXT_X) * row.progress), top + 62.5f,
                    1.25f, 1.25f, paint);
        }
        if (row.unread && !row.working && !row.needsYou) {
            paint.setColor(row.failed ? T3Glance.RED : T3Glance.GREEN);
            canvas.drawCircle(width - PAD - 4f, top + 25f, 4f, paint);
        }
    }

    private void drawStatus(Canvas canvas, Item item) {
        float top = item.top;
        if (item.label.isEmpty()) {
            BoardPaint.text(canvas, paint, item.title, PAD, top + 34f, 16f, SamTheme.MUTED, Paint.Align.LEFT,
                    BoardPaint.REGULAR);
            return;
        }
        int color = state == State.REAUTH ? T3Glance.AMBER : state == State.UNREACHABLE ? T3Glance.RED
                : state == State.RUNTIME_DOWN ? SamTheme.MUTED : SamTheme.ORB_BLUE;
        if (state == State.READY) {
            BoardPaint.text(canvas, paint, item.title, PAD, top + 28f, 18f, SamTheme.INK, Paint.Align.LEFT,
                    BoardPaint.MEDIUM);
            BoardPaint.text(canvas, paint, item.label, PAD, top + 51f, 15f, SamTheme.MUTED, Paint.Align.LEFT,
                    BoardPaint.REGULAR);
            return;
        }
        orb.draw(canvas, paint, ORB_X, top + 34f, 9f, color, false, 0L);
        BoardPaint.text(canvas, paint, item.title, TEXT_X, top + 30f, 19f, SamTheme.INK, Paint.Align.LEFT,
                BoardPaint.MEDIUM);
        BoardPaint.text(canvas, paint, item.label, TEXT_X, top + 54f, 15f, SamTheme.MUTED, Paint.Align.LEFT,
                BoardPaint.REGULAR);
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

    @Override public long redrawDelayMs(long nowMs) {
        return spinning ? SPIN_FRAME_MS : -1L;
    }

    // ------------------------------------------------------------- input

    @Override public int focusCount() { return focusable.size(); }

    @Override public void focusBounds(int index, RectF out) {
        Item item = focusable.get(index);
        if (item.type == HEADER) out.set(6f, 4f, width - 6f, item.height - 2f);
        else if (item.tinted) out.set(8f, item.top + 2f, width - 8f, item.top + item.height - 2f);
        else out.set(6f, item.top + 1f, width - 6f, item.top + item.height - 1f);
    }

    @Override public String focusKey(int index) {
        return index >= 0 && index < focusable.size() ? focusable.get(index).key : null;
    }

    @Override public boolean onTap(float x, float y) {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (y >= item.top && y < item.top + item.height) return act(item);
        }
        host.openT3();
        return true;
    }

    @Override public boolean activate(int index) {
        return index >= 0 && index < focusable.size() && act(focusable.get(index));
    }

    private boolean act(Item item) {
        if (item.type == THREAD && item.row != null && !fixture) {
            host.openT3Thread(item.row.id);
            return true;
        }
        if (item.type == STATUS && state == State.REAUTH) {
            host.openSettings();
            return true;
        }
        if (item.type == STATUS && state == State.UNREACHABLE) refresh();
        host.openT3();
        return true;
    }

    @Override public void close() { client.close(); }
}
