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
 * "Up next": today's remaining events and tomorrow's, inline and dense, so the day reads without
 * opening anything. A running event gets a compact Now panel with a progress sliver; each row
 * shows its time, a color dot per calendar, the title and "in 25 min · Zoom" (meeting links are
 * named, never shown raw). Up to {@link #MAX_ROWS} rows, {@link #TOMORROW_RESERVE} of them kept
 * for tomorrow when it has events. Rows open the event detail; the header and "+N more" open
 * the full Calendar page.
 */
public final class AgendaWidget implements BoardWidget {
    private static final int MAX_ROWS = 7;
    private static final int TOMORROW_RESERVE = 3;
    private static final int HEADER = 0, NOW = 1, UPCOMING = 2, ALL_DAY = 3, SECTION = 4, FOOTER = 5,
            EMPTY = 6, UNCONFIGURED = 7, STATUS = 8, LATER = 9;
    private static final float PAD = 22f;
    private static final float HEADER_H = 52f;
    private static final float ROW_H = 56f;
    /** Rows that open something stay at least 48 px tall (the R1 touch-target floor). */
    private static final float ALL_DAY_H = 48f;
    private static final float FOOTER_H = 48f;
    private static final float NOW_H = 90f;
    private static final float SECTION_H = 34f;
    private static final float DOT_X = 90f;
    private static final float TITLE_X = 104f;
    private static final float TITLE_SIZE = 18f;
    /** Long titles shrink to this before they are ellipsized. */
    private static final float TITLE_MIN_SIZE = 16f;

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
    private Map<String, Integer> calendarColors = new HashMap<>();

    private static final class Item {
        int type;
        float top;
        float height;
        String title = "";
        float titleSize = TITLE_SIZE;
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
            String place = AgendaPlace.label(jsonText(item, "location"), jsonText(item, "description"));
            AgendaEvent event = AgendaEvent.of(id.isEmpty() ? "e" + i : id, AgendaTitle.compact(jsonText(item, "title")),
                    jsonText(item, "startsAt"), jsonText(item, "endsAt"), item.optBoolean("allDay"),
                    place, jsonText(item, "calendar"), zone);
            if (event == null) continue;
            raw.put(event.id, item);
            events.add(event);
        }

        java.util.Set<String> calendars = new java.util.HashSet<>();
        for (AgendaEvent event : events) calendars.add(event.calendar.toLowerCase(Locale.ROOT));
        calendarColors = BoardPaint.calendarColors(calendars);
        // With one calendar its name (often an email address) says nothing; with several it tells them apart.
        boolean manyCalendars = calendars.size() > 1;
        float y = 0f;
        Item header = add(HEADER, y, HEADER_H);
        y += header.height;
        Agenda agenda = null;
        if (!loaded) {
            Item status = add(STATUS, y, 60f);
            status.title = failed ? "Calendar unavailable right now" : "Loading calendar…";
            y += status.height;
        } else if (!configured) {
            Item block = add(UNCONFIGURED, y, 74f);
            block.title = "Connect a calendar";
            block.detail = "in Settings → Management";
            y += block.height;
        } else {
            agenda = Agenda.build(events, now, MAX_ROWS, TOMORROW_RESERVE);
            if (agenda.todayEmpty()) {
                Item empty = add(EMPTY, y, 0f);
                empty.title = "Nothing more today";
                empty.detail = agenda.tomorrowCount > 0 ? "" : agenda.later != null ? "Nothing tomorrow either"
                        : "Nothing coming up";
                empty.height = empty.detail.isEmpty() ? 42f : 66f;
                y += empty.height;
            }
            Item previous = null;
            for (Agenda.Section section : agenda.sections) {
                if (!section.today) {
                    Item label = add(SECTION, y, SECTION_H);
                    label.title = "TOMORROW";
                    label.detail = text.day(section.day);
                    label.separator = !agenda.todayEmpty();
                    y += label.height;
                    previous = null;
                }
                for (Agenda.Row row : section.rows) {
                    Item item = row(row, text, now, y, width, manyCalendars);
                    item.separator = previous != null && previous.type != NOW && item.type != NOW;
                    y += item.height;
                    previous = item;
                }
            }
            if (agenda.later != null) {
                Item label = add(SECTION, y, SECTION_H);
                label.title = "NEXT";
                label.detail = text.day(agenda.later.start.atZone(zone).toLocalDate());
                label.separator = true;
                y += label.height;
                Item later = add(LATER, y, ROW_H);
                fillTimed(later, agenda.later, text, width, "", false, manyCalendars);
                y += later.height;
            }
            if (agenda.hidden > 0) {
                Item footer = add(FOOTER, y, FOOTER_H);
                footer.title = "+" + agenda.hidden + " more";
                footer.separator = true;
                y += footer.height;
            }
        }
        y += 8f;
        summary = !loaded || !configured || agenda == null ? "" : agenda.summary();
        summary = BoardPaint.fit(paint, summary, width - 42f - 160f, 15f, BoardPaint.REGULAR);
        glass.size(width, y, 28f);
        headerOrb.set(31f, 27f, 7f, BoardPaint.CALENDAR_ACCENT, 2.6f);
        return y;
    }

    /** The runtime sends absent fields as JSON null, which Android's optString turns into "null". */
    private static String jsonText(JSONObject item, String key) {
        return item.isNull(key) ? "" : item.optString(key);
    }

    private Item row(Agenda.Row row, AgendaText text, ZonedDateTime now, float y, float width, boolean manyCalendars) {
        AgendaEvent event = row.event();
        if (row.kind == Agenda.Kind.ALL_DAY) {
            Item item = add(ALL_DAY, y, ALL_DAY_H);
            item.color = BoardPaint.calendarColor(calendarColors, event.calendar);
            item.paleColor = OrbGlyph.pale(item.color);
            item.tag = "ALL DAY";
            item.tagWidth = BoardPaint.eyebrowWidth(paint, item.tag, 10.5f) + 16f;
            StringBuilder titles = new StringBuilder();
            for (AgendaEvent each : row.events) { if (titles.length() > 0) titles.append(" · "); titles.append(each.title); }
            fitTitle(item, titles.toString(), width - PAD - (PAD + item.tagWidth + 12f), 17f, 15.5f);
            item.event = row.events.size() == 1 ? raw.get(event.id) : null;
            return item;
        }
        if (row.kind == Agenda.Kind.NOW) {
            Item item = add(NOW, y, NOW_H);
            item.color = BoardPaint.calendarColor(calendarColors, event.calendar);
            item.paleColor = OrbGlyph.pale(item.color);
            item.tag = "NOW";
            item.tagWidth = BoardPaint.eyebrowWidth(paint, item.tag, 11f) + 18f;
            item.accent = RelativeTime.left(event.end.toEpochMilli() - now.toInstant().toEpochMilli());
            item.accentWidth = BoardPaint.width(paint, item.accent, 14f, BoardPaint.MEDIUM);
            float titleLeft = PAD + 4f + item.tagWidth + 10f;
            fitTitle(item, event.title, width - PAD - 6f - item.accentWidth - 12f - titleLeft, 19f, 16.5f);
            String place = place(event, manyCalendars);
            String detail = text.range(event) + (place.isEmpty() ? "" : " · " + place);
            item.detail = BoardPaint.fit(paint, detail, width - 2f * (PAD + 4f), 15f, BoardPaint.REGULAR);
            item.progress = Agenda.progress(event, now.toInstant());
            item.event = raw.get(event.id);
            return item;
        }
        Item item = add(UPCOMING, y, ROW_H);
        if (row.today) {
            fillTimed(item, event, text, width,
                    RelativeTime.until(event.start.toEpochMilli() - now.toInstant().toEpochMilli()), true, manyCalendars);
        } else {
            // Tomorrow: how long it runs, as plain detail ("1 hr · Zoom"), not an urgent accent.
            String length = event.end.isAfter(event.start)
                    ? RelativeTime.span(RelativeTime.minutesCeil(event.end.toEpochMilli() - event.start.toEpochMilli())) : "";
            fillTimed(item, event, text, width, length, false, manyCalendars);
        }
        return item;
    }

    private void fillTimed(Item item, AgendaEvent event, AgendaText text, float width, String lead, boolean accentLead,
                           boolean manyCalendars) {
        item.color = BoardPaint.calendarColor(calendarColors, event.calendar);
        item.paleColor = OrbGlyph.pale(item.color);
        String[] clock = event.allDay ? new String[]{"All", "day"} : text.clock(event.start);
        item.time = clock[0];
        item.meridiem = clock[1];
        float available = width - PAD - TITLE_X;
        fitTitle(item, event.title, available, TITLE_SIZE, TITLE_MIN_SIZE);
        String place = place(event, manyCalendars);
        String meta = lead;
        if (!place.isEmpty()) meta = meta.isEmpty() ? place : meta + " · " + place;
        // The lead ("in 25 min") in the accent tint, the rest muted; split after fitting.
        String fitted = BoardPaint.fit(paint, meta, available, 14f, BoardPaint.REGULAR);
        if (accentLead && !lead.isEmpty() && fitted.startsWith(lead)) {
            item.accent = lead;
            item.detail = fitted.substring(lead.length());
        } else {
            item.accent = "";
            item.detail = fitted;
        }
        item.accentWidth = item.accent.isEmpty() ? 0f : BoardPaint.width(paint, item.accent, 14f, BoardPaint.MEDIUM);
        item.event = raw.get(event.id);
    }

    /** Shrinks a long title towards {@code min} before ellipsizing it. */
    private void fitTitle(Item item, String title, float available, float size, float min) {
        float current = size;
        while (current > min && BoardPaint.width(paint, title, current, BoardPaint.MEDIUM) > available) current -= 0.5f;
        item.titleSize = current;
        item.title = BoardPaint.fit(paint, title, available, current, BoardPaint.MEDIUM);
    }

    private static String place(AgendaEvent event, boolean manyCalendars) {
        if (!event.location.isEmpty()) return event.location;
        return manyCalendars ? event.calendar : "";
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
                    BoardPaint.eyebrow(canvas, paint, item.title, PAD, item.top + 24f, 12.5f, SamTheme.MUTED, Paint.Align.LEFT);
                    BoardPaint.text(canvas, paint, item.detail, width - PAD, item.top + 24f, 14f, SamTheme.MUTED,
                            Paint.Align.RIGHT, BoardPaint.REGULAR);
                }
                case FOOTER -> {
                    BoardPaint.text(canvas, paint, item.title, PAD, item.top + 31f, 16f, SamTheme.ORB_PALE,
                            Paint.Align.LEFT, BoardPaint.MEDIUM);
                    BoardPaint.text(canvas, paint, "Calendar", width - 42f, item.top + 31f, 15f, SamTheme.MUTED,
                            Paint.Align.RIGHT, BoardPaint.REGULAR);
                    chevron(canvas, width - 28f, item.top + 26f);
                }
                case EMPTY -> {
                    BoardPaint.text(canvas, paint, item.title, PAD, item.top + 27f, 17f, SamTheme.withAlpha(SamTheme.INK, 210),
                            Paint.Align.LEFT, BoardPaint.MEDIUM);
                    if (!item.detail.isEmpty()) {
                        BoardPaint.text(canvas, paint, item.detail, PAD, item.top + 50f, 15f, SamTheme.MUTED,
                                Paint.Align.LEFT, BoardPaint.REGULAR);
                    }
                }
                case UNCONFIGURED -> drawUnconfigured(canvas, item);
                case STATUS -> BoardPaint.text(canvas, paint, item.title, PAD, item.top + 34f, 16f, SamTheme.MUTED,
                        Paint.Align.LEFT, BoardPaint.REGULAR);
                default -> { }
            }
        }
    }

    private void drawHeader(Canvas canvas) {
        headerOrb.draw(canvas, paint);
        BoardPaint.eyebrow(canvas, paint, "UP NEXT", 47f, 33f, 14f, SamTheme.withAlpha(SamTheme.INK, 230), Paint.Align.LEFT);
        if (!summary.isEmpty()) {
            BoardPaint.text(canvas, paint, summary, width - 42f, 33f, 15f, SamTheme.MUTED, Paint.Align.RIGHT,
                    BoardPaint.REGULAR);
        }
        chevron(canvas, width - 28f, 28f);
    }

    private void drawNow(Canvas canvas, Item item) {
        float top = item.top;
        rect.set(10f, top + 3f, width - 10f, top + item.height - 6f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(item.color, 30));
        canvas.drawRoundRect(rect, 20f, 20f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(SamTheme.withAlpha(item.color, 96));
        canvas.drawRoundRect(rect, 20f, 20f, paint);
        paint.setStyle(Paint.Style.FILL);
        float x = PAD + 4f;
        rect.set(x, top + 15f, x + item.tagWidth, top + 37f);
        paint.setColor(item.color);
        canvas.drawRoundRect(rect, 11f, 11f, paint);
        BoardPaint.eyebrow(canvas, paint, item.tag, rect.centerX() + 1f, top + 30.5f, 11f, SamTheme.BACKGROUND,
                Paint.Align.CENTER);
        BoardPaint.text(canvas, paint, item.title, rect.right + 10f, top + 33f, item.titleSize, SamTheme.INK,
                Paint.Align.LEFT, BoardPaint.MEDIUM);
        BoardPaint.text(canvas, paint, item.accent, width - PAD - 6f, top + 32f, 14f, item.paleColor,
                Paint.Align.RIGHT, BoardPaint.MEDIUM);
        BoardPaint.text(canvas, paint, item.detail, x, top + 59f, 15f, SamTheme.withAlpha(SamTheme.INK, 175),
                Paint.Align.LEFT, BoardPaint.REGULAR);
        float barTop = top + 70f;
        float right = width - x;
        paint.setColor(SamTheme.withAlpha(SamTheme.INK, 34));
        canvas.drawRoundRect(x, barTop, right, barTop + 4f, 2f, 2f, paint);
        paint.setColor(item.color);
        float fill = Math.max(4f, (right - x) * item.progress);
        canvas.drawRoundRect(x, barTop, x + fill, barTop + 4f, 2f, 2f, paint);
    }

    private void drawTimed(Canvas canvas, Item item) {
        float top = item.top;
        BoardPaint.text(canvas, paint, item.time, PAD, top + 26f, 18f, SamTheme.INK, Paint.Align.LEFT, BoardPaint.MEDIUM);
        if (!item.meridiem.isEmpty()) {
            BoardPaint.text(canvas, paint, item.meridiem, PAD + 1f, top + 44f, 12f, SamTheme.MUTED, Paint.Align.LEFT,
                    BoardPaint.MEDIUM);
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(item.color);
        canvas.drawCircle(DOT_X, top + 20f, 4.5f, paint);
        BoardPaint.text(canvas, paint, item.title, TITLE_X, top + 26f, item.titleSize, SamTheme.INK, Paint.Align.LEFT,
                BoardPaint.MEDIUM);
        float x = TITLE_X;
        if (!item.accent.isEmpty()) {
            BoardPaint.text(canvas, paint, item.accent, x, top + 45f, 14f, SamTheme.ORB_PALE, Paint.Align.LEFT,
                    BoardPaint.MEDIUM);
            x += item.accentWidth;
        }
        if (!item.detail.isEmpty()) {
            BoardPaint.text(canvas, paint, item.detail, x, top + 45f, 14f, SamTheme.MUTED, Paint.Align.LEFT,
                    BoardPaint.REGULAR);
        }
    }

    private void drawAllDay(Canvas canvas, Item item) {
        float top = item.top;
        rect.set(PAD, top + 13f, PAD + item.tagWidth, top + 35f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(item.color, 78));
        canvas.drawRoundRect(rect, 11f, 11f, paint);
        BoardPaint.eyebrow(canvas, paint, item.tag, rect.centerX() + 1f, top + 28f, 10.5f, SamTheme.INK, Paint.Align.CENTER);
        BoardPaint.text(canvas, paint, item.title, rect.right + 12f, top + 30f, item.titleSize, SamTheme.INK,
                Paint.Align.LEFT, BoardPaint.MEDIUM);
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
        else if (item.type == NOW) out.set(10f, item.top + 3f, width - 10f, item.top + item.height - 6f);
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
