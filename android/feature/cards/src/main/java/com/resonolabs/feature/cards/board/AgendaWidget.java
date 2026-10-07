package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.format.DateFormat;

import com.resonolabs.runtime.host.CalendarEventClient;
import com.resonolabs.ui.design.SamTheme;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Up next": today's remaining events and tomorrow, inline. The running event gets a Now panel
 * with a progress sliver; each row shows its time, title, relative start and location, with a
 * color dot per calendar. Rows open the event detail; the header and "+N more" open the full
 * Calendar page.
 */
public final class AgendaWidget implements BoardWidget {
    private static final int MAX_ROWS = 4;
    private static final int HEADER = 0, NOW = 1, UPCOMING = 2, ALL_DAY = 3, SECTION = 4, FOOTER = 5,
            EMPTY = 6, UNCONFIGURED = 7, STATUS = 8, LATER = 9;
    private static final float PAD = 22f;

    private final BoardHost host;
    private final CalendarEventClient client = new CalendarEventClient();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GlassPanel glass = new GlassPanel();
    private final OrbGlyph headerOrb = new OrbGlyph();
    private final RectF rect = new RectF();
    private final List<Item> items = new ArrayList<>();
    private final List<Item> focusable = new ArrayList<>();
    private final Map<String, JSONObject> raw = new HashMap<>();
    private JSONArray rawEvents = new JSONArray();
    private boolean loaded;
    private boolean failed;
    private boolean configured = true;
    private float width;
    private String summary = "";
    private float summaryWidth;
    private Map<String, Integer> calendarColors = new HashMap<>();

    private static final class Item {
        int type;
        float top;
        float height;
        String title = "";
        String time = "";
        String meridiem = "";
        String accent = "";
        String detail = "";
        String tag = "";
        float tagWidth;
        float accentWidth;
        int color = SamTheme.ORB_PALE;
        int paleColor = SamTheme.ORB_PALE;
        float progress;
        boolean separator;
        JSONObject event;
    }

    public AgendaWidget(BoardHost host) {
        this.host = host;
    }

    @Override public String id() { return "agenda"; }

    // ------------------------------------------------------------- data

    @Override public long refreshIntervalMs() { return 60_000L; }

    @Override public void refresh() {
        BoardFixtures.Mode mode = host.fixtureMode();
        if (mode != BoardFixtures.Mode.OFF) {
            apply(BoardFixtures.calendar(mode, System.currentTimeMillis(), ZoneId.systemDefault()));
            return;
        }
        client.loadUpcoming(host.context(), new CalendarEventClient.Callback() {
            @Override public void onEvents(JSONObject value) { apply(value); }
            @Override public void onFailure() {
                failed = true;
                if (!loaded) host.widgetChanged(AgendaWidget.this);
            }
        });
    }

    private void apply(JSONObject value) {
        JSONArray events = value.optJSONArray("events");
        rawEvents = events == null ? new JSONArray() : events;
        configured = value.optBoolean("configured", true);
        loaded = true;
        failed = false;
        host.widgetChanged(this);
    }

    /** The same upcoming projection the widget shows (for the full page in fixture mode). */
    public JSONObject snapshot() {
        JSONObject value = new JSONObject();
        try { value.put("events", rawEvents).put("configured", configured); } catch (Exception ignored) { }
        return value;
    }

    // ------------------------------------------------------------- layout

    @Override public float measure(float width, long nowMs) {
        this.width = width;
        items.clear();
        focusable.clear();
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
        AgendaText text = new AgendaText(zone, DateFormat.is24HourFormat(host.context()), Locale.getDefault());
        List<AgendaEvent> events = new ArrayList<>();
        raw.clear();
        for (int i = 0; i < rawEvents.length(); i++) {
            JSONObject item = rawEvents.optJSONObject(i);
            if (item == null) continue;
            String id = jsonText(item, "eventId");
            AgendaEvent event = AgendaEvent.of(id.isEmpty() ? "e" + i : id, jsonText(item, "title"),
                    jsonText(item, "startsAt"), jsonText(item, "endsAt"), item.optBoolean("allDay"),
                    jsonText(item, "location"), jsonText(item, "calendar"), zone);
            if (event == null) continue;
            raw.put(event.id, item);
            events.add(event);
        }

        List<String> calendars = new ArrayList<>();
        for (AgendaEvent event : events) calendars.add(event.calendar);
        calendarColors = BoardPaint.calendarColors(calendars);
        float y = 0f;
        Item header = add(HEADER, y, 58f);
        y += header.height;
        Agenda agenda = null;
        if (!loaded) {
            Item status = add(STATUS, y, 64f);
            status.title = failed ? "Calendar unavailable right now" : "Loading calendar…";
            y += status.height;
        } else if (!configured) {
            Item block = add(UNCONFIGURED, y, 74f);
            block.title = "Connect a calendar";
            block.detail = "in Settings → Management";
            y += block.height;
        } else {
            agenda = Agenda.build(events, now, MAX_ROWS);
            if (agenda.todayEmpty()) {
                Item empty = add(EMPTY, y, 0f);
                empty.title = "No events today";
                empty.detail = agenda.tomorrowCount > 0 ? "" : agenda.later != null ? "Nothing tomorrow either"
                        : "Nothing coming up";
                empty.height = empty.detail.isEmpty() ? 50f : 76f;
                y += empty.height;
            }
            Item previous = null;
            for (Agenda.Section section : agenda.sections) {
                if (!section.today) {
                    Item label = add(SECTION, y, 42f);
                    label.title = "TOMORROW";
                    label.detail = text.day(section.day);
                    y += label.height;
                    previous = null;
                }
                for (Agenda.Row row : section.rows) {
                    Item item = row(row, text, now, y, width);
                    item.separator = previous != null && previous.type != NOW && item.type != NOW;
                    y += item.height;
                    previous = item;
                }
            }
            if (agenda.later != null) {
                Item label = add(SECTION, y, 42f);
                label.title = "NEXT";
                label.detail = "";
                y += label.height;
                Item later = add(LATER, y, 70f);
                fillTimed(later, agenda.later, text, now, width, text.day(agenda.later.start.atZone(zone).toLocalDate()));
                y += later.height;
            }
            if (agenda.hidden > 0) {
                Item footer = add(FOOTER, y, 54f);
                footer.title = "+" + agenda.hidden + " more";
                footer.separator = true;
                y += footer.height;
            }
        }
        y += 8f;
        summary = !loaded || !configured || agenda == null ? ""
                : agenda.todayCount > 0 ? agenda.todayCount + " today"
                : agenda.tomorrowCount > 0 ? agenda.tomorrowCount + " tomorrow" : "Free";
        summaryWidth = BoardPaint.width(paint, summary, 15f, BoardPaint.REGULAR);
        glass.size(width, y, 28f);
        headerOrb.set(31f, 30f, 7f, BoardPaint.CALENDAR_ACCENT, 2.6f);
        return y;
    }

    /** The runtime sends absent fields as JSON null, which Android's optString turns into "null". */
    private static String jsonText(JSONObject item, String key) {
        return item.isNull(key) ? "" : item.optString(key);
    }

    private Item row(Agenda.Row row, AgendaText text, ZonedDateTime now, float y, float width) {
        AgendaEvent event = row.event();
        if (row.kind == Agenda.Kind.ALL_DAY) {
            Item item = add(ALL_DAY, y, 56f);
            item.color = BoardPaint.calendarColor(calendarColors, event.calendar);
            item.paleColor = OrbGlyph.pale(item.color);
            item.tag = "ALL DAY";
            item.tagWidth = BoardPaint.eyebrowWidth(paint, item.tag, 11f) + 18f;
            StringBuilder titles = new StringBuilder();
            for (AgendaEvent each : row.events) { if (titles.length() > 0) titles.append(" · "); titles.append(each.title); }
            item.title = BoardPaint.fit(paint, titles.toString(), width - PAD - (PAD + item.tagWidth + 12f), 18f, BoardPaint.MEDIUM);
            item.event = row.events.size() == 1 ? raw.get(event.id) : null;
            return item;
        }
        if (row.kind == Agenda.Kind.NOW) {
            Item item = add(NOW, y, 126f);
            item.color = BoardPaint.calendarColor(calendarColors, event.calendar);
            item.paleColor = OrbGlyph.pale(item.color);
            item.tag = "NOW";
            item.tagWidth = BoardPaint.eyebrowWidth(paint, item.tag, 12f) + 22f;
            item.accent = RelativeTime.left(event.end.toEpochMilli() - now.toInstant().toEpochMilli());
            item.title = BoardPaint.fit(paint, event.title, width - 2f * (PAD + 4f), 22f, BoardPaint.MEDIUM);
            String detail = text.range(event) + (event.location.isEmpty() ? "" : " · " + event.location);
            item.detail = BoardPaint.fit(paint, detail, width - 2f * (PAD + 4f), 16f, BoardPaint.REGULAR);
            item.progress = Agenda.progress(event, now.toInstant());
            item.event = raw.get(event.id);
            return item;
        }
        Item item = add(UPCOMING, y, 70f);
        String accent = row.today ? RelativeTime.until(event.start.toEpochMilli() - now.toInstant().toEpochMilli()) : "";
        fillTimed(item, event, text, now, width, accent);
        return item;
    }

    private void fillTimed(Item item, AgendaEvent event, AgendaText text, ZonedDateTime now, float width, String accent) {
        item.color = BoardPaint.calendarColor(calendarColors, event.calendar);
        item.paleColor = OrbGlyph.pale(item.color);
        String[] clock = event.allDay ? new String[]{"All", "day"} : text.clock(event.start);
        item.time = clock[0];
        item.meridiem = clock[1];
        float left = 120f;
        float available = width - PAD - left;
        item.title = BoardPaint.fit(paint, event.title, available, 20f, BoardPaint.MEDIUM);
        item.accent = accent;
        item.accentWidth = accent.isEmpty() ? 0f : BoardPaint.width(paint, accent, 15f, BoardPaint.MEDIUM);
        String place = !event.location.isEmpty() ? event.location : event.calendar;
        String detail = place.isEmpty() ? "" : (accent.isEmpty() ? place : " · " + place);
        item.detail = BoardPaint.fit(paint, detail, Math.max(0f, available - item.accentWidth), 15f, BoardPaint.REGULAR);
        item.event = raw.get(event.id);
    }

    private Item add(int type, float top, float height) {
        Item item = new Item();
        item.type = type;
        item.top = top;
        item.height = height;
        items.add(item);
        if (type == HEADER || type == NOW || type == UPCOMING || type == ALL_DAY || type == FOOTER || type == LATER) {
            focusable.add(item);
        }
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
                canvas.drawRect(PAD, item.top, width - PAD, item.top + 1f, paint);
            }
            switch (item.type) {
                case HEADER -> drawHeader(canvas);
                case NOW -> drawNow(canvas, item);
                case UPCOMING, LATER -> drawTimed(canvas, item);
                case ALL_DAY -> drawAllDay(canvas, item);
                case SECTION -> {
                    BoardPaint.eyebrow(canvas, paint, item.title, PAD, item.top + 29f, 13f, SamTheme.MUTED, Paint.Align.LEFT);
                    BoardPaint.text(canvas, paint, item.detail, width - PAD, item.top + 29f, 14f, SamTheme.MUTED,
                            Paint.Align.RIGHT, BoardPaint.REGULAR);
                }
                case FOOTER -> {
                    BoardPaint.text(canvas, paint, item.title, PAD, item.top + 33f, 16f, SamTheme.ORB_PALE,
                            Paint.Align.LEFT, BoardPaint.MEDIUM);
                    BoardPaint.text(canvas, paint, "Calendar", width - 42f, item.top + 33f, 15f, SamTheme.MUTED,
                            Paint.Align.RIGHT, BoardPaint.REGULAR);
                    chevron(canvas, width - 28f, item.top + 28f);
                }
                case EMPTY -> {
                    BoardPaint.text(canvas, paint, item.title, PAD, item.top + 31f, 19f, SamTheme.INK,
                            Paint.Align.LEFT, BoardPaint.MEDIUM);
                    if (!item.detail.isEmpty()) {
                        BoardPaint.text(canvas, paint, item.detail, PAD, item.top + 56f, 15f, SamTheme.MUTED,
                                Paint.Align.LEFT, BoardPaint.REGULAR);
                    }
                }
                case UNCONFIGURED -> drawUnconfigured(canvas, item);
                case STATUS -> BoardPaint.text(canvas, paint, item.title, PAD, item.top + 36f, 16f, SamTheme.MUTED,
                        Paint.Align.LEFT, BoardPaint.REGULAR);
                default -> { }
            }
        }
    }

    private void drawHeader(Canvas canvas) {
        headerOrb.draw(canvas, paint);
        BoardPaint.eyebrow(canvas, paint, "UP NEXT", 47f, 36f, 14f, SamTheme.withAlpha(SamTheme.INK, 230), Paint.Align.LEFT);
        if (!summary.isEmpty()) {
            BoardPaint.text(canvas, paint, summary, width - 42f, 36f, 15f, SamTheme.MUTED, Paint.Align.RIGHT,
                    BoardPaint.REGULAR);
        }
        chevron(canvas, width - 28f, 31f);
    }

    private void drawNow(Canvas canvas, Item item) {
        float top = item.top;
        rect.set(10f, top + 2f, width - 10f, top + item.height - 8f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(item.color, 30));
        canvas.drawRoundRect(rect, 22f, 22f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(SamTheme.withAlpha(item.color, 96));
        canvas.drawRoundRect(rect, 22f, 22f, paint);
        paint.setStyle(Paint.Style.FILL);
        float x = PAD + 4f;
        rect.set(x, top + 16f, x + item.tagWidth, top + 40f);
        paint.setColor(item.color);
        canvas.drawRoundRect(rect, 12f, 12f, paint);
        BoardPaint.eyebrow(canvas, paint, item.tag, rect.centerX() + 1f, top + 33f, 12f, SamTheme.BACKGROUND, Paint.Align.CENTER);
        BoardPaint.text(canvas, paint, item.accent, rect.right + 10f, top + 34f, 15f, item.paleColor,
                Paint.Align.LEFT, BoardPaint.MEDIUM);
        BoardPaint.text(canvas, paint, item.title, x, top + 71f, 22f, SamTheme.INK, Paint.Align.LEFT, BoardPaint.MEDIUM);
        BoardPaint.text(canvas, paint, item.detail, x, top + 95f, 16f, SamTheme.withAlpha(SamTheme.INK, 175),
                Paint.Align.LEFT, BoardPaint.REGULAR);
        float barTop = top + item.height - 24f;
        float right = width - x;
        paint.setColor(SamTheme.withAlpha(SamTheme.INK, 34));
        canvas.drawRoundRect(x, barTop, right, barTop + 5f, 2.5f, 2.5f, paint);
        paint.setColor(item.color);
        float fill = Math.max(5f, (right - x) * item.progress);
        canvas.drawRoundRect(x, barTop, x + fill, barTop + 5f, 2.5f, 2.5f, paint);
    }

    private void drawTimed(Canvas canvas, Item item) {
        float top = item.top;
        BoardPaint.text(canvas, paint, item.time, PAD, top + 34f, 20f, SamTheme.INK, Paint.Align.LEFT, BoardPaint.MEDIUM);
        if (!item.meridiem.isEmpty()) {
            BoardPaint.text(canvas, paint, item.meridiem, PAD + 1f, top + 54f, 13f, SamTheme.MUTED, Paint.Align.LEFT,
                    BoardPaint.MEDIUM);
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(item.color);
        canvas.drawCircle(104f, top + 28f, 5f, paint);
        BoardPaint.text(canvas, paint, item.title, 120f, top + 35f, 20f, SamTheme.INK, Paint.Align.LEFT, BoardPaint.MEDIUM);
        float x = 120f;
        if (!item.accent.isEmpty()) {
            BoardPaint.text(canvas, paint, item.accent, x, top + 57f, 15f, SamTheme.ORB_PALE, Paint.Align.LEFT,
                    BoardPaint.MEDIUM);
            x += item.accentWidth;
        }
        if (!item.detail.isEmpty()) {
            BoardPaint.text(canvas, paint, item.detail, x, top + 57f, 15f, SamTheme.MUTED, Paint.Align.LEFT,
                    BoardPaint.REGULAR);
        }
    }

    private void drawAllDay(Canvas canvas, Item item) {
        float top = item.top;
        rect.set(PAD, top + 16f, PAD + item.tagWidth, top + 40f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(item.color, 78));
        canvas.drawRoundRect(rect, 12f, 12f, paint);
        BoardPaint.eyebrow(canvas, paint, item.tag, rect.centerX() + 1f, top + 32f, 11f, SamTheme.INK, Paint.Align.CENTER);
        BoardPaint.text(canvas, paint, item.title, rect.right + 12f, top + 34f, 18f, SamTheme.INK, Paint.Align.LEFT,
                BoardPaint.MEDIUM);
    }

    private void drawUnconfigured(Canvas canvas, Item item) {
        float top = item.top;
        float left = PAD;
        rect.set(left, top + 12f, left + 40f, top + 52f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(BoardPaint.CALENDAR_ACCENT, 40));
        canvas.drawRoundRect(rect, 11f, 11f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(OrbGlyph.pale(BoardPaint.CALENDAR_ACCENT));
        canvas.drawRoundRect(left + 9f, top + 22f, left + 31f, top + 43f, 4f, 4f, paint);
        canvas.drawLine(left + 9f, top + 29f, left + 31f, top + 29f, paint);
        canvas.drawLine(left + 15f, top + 18f, left + 15f, top + 24f, paint);
        canvas.drawLine(left + 25f, top + 18f, left + 25f, top + 24f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        BoardPaint.text(canvas, paint, item.title, left + 56f, top + 30f, 19f, SamTheme.INK, Paint.Align.LEFT, BoardPaint.MEDIUM);
        BoardPaint.text(canvas, paint, item.detail, left + 56f, top + 54f, 15f, SamTheme.MUTED, Paint.Align.LEFT,
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

    // ------------------------------------------------------------- input

    @Override public int focusCount() { return focusable.size(); }

    @Override public void focusBounds(int index, RectF out) {
        Item item = focusable.get(index);
        if (item.type == HEADER) out.set(6f, 4f, width - 6f, item.height - 2f);
        else if (item.type == NOW) out.set(10f, item.top + 2f, width - 10f, item.top + item.height - 8f);
        else out.set(6f, item.top + 2f, width - 6f, item.top + item.height - 2f);
    }

    @Override public boolean onTap(float x, float y) {
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (y >= item.top && y < item.top + item.height) return act(item);
        }
        host.openCalendar();
        return true;
    }

    @Override public boolean activate(int index) {
        return index >= 0 && index < focusable.size() && act(focusable.get(index));
    }

    private boolean act(Item item) {
        if (item.type == STATUS && failed) { refresh(); return true; }
        if ((item.type == NOW || item.type == UPCOMING || item.type == LATER || item.type == ALL_DAY) && item.event != null) {
            host.openCalendarEvent(item.event);
            return true;
        }
        host.openCalendar();
        return true;
    }

    @Override public void close() { client.close(); }
}
