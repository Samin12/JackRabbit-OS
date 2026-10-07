package com.resonolabs.feature.genui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.resonolabs.runtime.host.CalendarEventClient;
import com.resonolabs.ui.design.GlassPainter;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Glanceable chips under the orb on the idle Voice page: the next calendar event (from
 * {@code /v1/calendar/upcoming}, parsed like the calendar-next live source) and T3 Code
 * "N need you" / "N working" (counts pushed in by the shell from {@code /v1/t3/threads}). Up to
 * two chips side by side; a chip with nothing to say is hidden, and with nothing at all the
 * page keeps its normal hint line.
 *
 * <p>Polls only while it is being drawn (a 15 s tick that pauses 3 s after the last frame), so
 * a hidden Voice page or a sleeping screen costs nothing. Texts are fitted when data changes,
 * never in {@link #draw}.
 */
public final class IdleGlance implements AutoCloseable {
    public static final String CALENDAR = "calendar";
    public static final String T3 = "t3";
    public static final float TOP = 464f;
    public static final float HEIGHT = 56f;
    private static final float GAP = 12f;
    private static final float LEFT = 24f;
    private static final float RIGHT = 456f;
    private static final float SINGLE_WIDTH = 312f;
    private static final long TICK_MS = 15_000L;
    private static final long CALENDAR_REFRESH_MS = 60_000L;
    private static final long PAUSE_AFTER_MS = 3_000L;
    /** Events further out than this are not "next" enough for the idle page. */
    static final long HORIZON_MS = 24L * 60L * 60L * 1000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    /** One chip's text (pure; see {@link #calendarChip} and {@link #t3Chip}). */
    public static final class ChipText {
        public final String title;
        public final String subtitle;
        /** T3: something waits on the user (amber); otherwise in progress. */
        public final boolean attention;

        ChipText(String title, String subtitle, boolean attention) {
            this.title = title;
            this.subtitle = subtitle;
            this.attention = attention;
        }
    }

    private static final class Chip {
        String kind;
        ChipText text;
        String title = "";
        String subtitle = "";
        float left;
        float right;
    }

    private final Context context;
    private final Runnable invalidate;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final GlassPainter glass = new GlassPainter();
    private final GenIcons icons = new GenIcons();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = text(15.5f, true);
    private final Paint subtitlePaint = text(12.5f, false);
    private final RectF arc = new RectF();
    private final Chip[] chips = {new Chip(), new Chip()};
    private final Runnable tick = this::tick;
    private CalendarEventClient client;
    private ArrayList<CalendarNextSource.Event> events = new ArrayList<>();
    private boolean calendarConfigured;
    private boolean calendarInFlight;
    private long calendarFetchedAt = Long.MIN_VALUE / 2;
    private int needsYou;
    private int working;
    private int count;
    private long lastDrawn = Long.MIN_VALUE / 2;
    private boolean ticking;
    private boolean closed;

    public IdleGlance(Context context, Runnable invalidate) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.invalidate = invalidate;
    }

    /** T3 counts from the shell's thread poll (0/0 hides the chip). */
    public void setT3(int needsYou, int working) {
        if (this.needsYou == needsYou && this.working == working) return;
        this.needsYou = Math.max(0, needsYou);
        this.working = Math.max(0, working);
        rebuild();
    }

    public boolean hasChips() {
        return count > 0;
    }

    /** {@link #CALENDAR}, {@link #T3} or null. */
    public String chipAt(float x, float y) {
        if (y < TOP - 6f || y > TOP + HEIGHT + 6f) return null;
        for (int index = 0; index < count; index++) {
            Chip chip = chips[index];
            if (x >= chip.left - 4f && x <= chip.right + 4f) return chip.kind;
        }
        return null;
    }

    /** Draws the chips (call only when the idle page shows them) and keeps the data fresh. */
    public void draw(Canvas canvas, long nowElapsed) {
        lastDrawn = SystemClock.uptimeMillis();
        if (!ticking && !closed) {
            ticking = true;
            handler.post(tick);
        }
        for (int index = 0; index < count; index++) drawChip(canvas, chips[index], nowElapsed);
    }

    /** Call while the idle page is visible but the chips are not drawn (e.g. no data yet). */
    public void keepFresh() {
        lastDrawn = SystemClock.uptimeMillis();
        if (!ticking && !closed) {
            ticking = true;
            handler.post(tick);
        }
    }

    @Override public void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (client != null) client.close();
        client = null;
    }

    // ------------------------------------------------------------------ data

    private void tick() {
        if (closed) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastDrawn > PAUSE_AFTER_MS) {
            ticking = false;
            return;
        }
        if (now - calendarFetchedAt >= CALENDAR_REFRESH_MS) fetchCalendar();
        rebuild();
        handler.postDelayed(tick, TICK_MS);
    }

    private void fetchCalendar() {
        if (calendarInFlight) return;
        calendarInFlight = true;
        calendarFetchedAt = SystemClock.uptimeMillis();
        if (client == null) client = new CalendarEventClient();
        client.loadUpcoming(context, new CalendarEventClient.Callback() {
            @Override public void onEvents(JSONObject value) {
                calendarInFlight = false;
                if (closed) return;
                calendarConfigured = value.optBoolean("configured", true);
                events = CalendarNextSource.parse(value.optJSONArray("events"));
                rebuild();
            }

            @Override public void onFailure() {
                calendarInFlight = false;
            }
        });
    }

    private void rebuild() {
        ChipText calendar = calendarConfigured
                ? calendarChip(events, System.currentTimeMillis(), ZoneId.systemDefault()) : null;
        ChipText t3 = t3Chip(needsYou, working);
        int next = 0;
        // T3 first when it needs the user: that is the one to act on.
        if (t3 != null && t3.attention) next = put(next, T3, t3);
        if (calendar != null) next = put(next, CALENDAR, calendar);
        if (t3 != null && !t3.attention) next = put(next, T3, t3);
        boolean changed = next != count;
        for (int index = 0; index < next && !changed; index++) changed = chips[index].text == null;
        count = next;
        place();
        if (changed || count > 0) invalidate.run();
    }

    private int put(int index, String kind, ChipText text) {
        Chip chip = chips[index];
        chip.kind = kind;
        chip.text = text;
        return index + 1;
    }

    private void place() {
        if (count == 1) {
            chips[0].left = 240f - SINGLE_WIDTH / 2f;
            chips[0].right = 240f + SINGLE_WIDTH / 2f;
        } else if (count == 2) {
            float half = (RIGHT - LEFT - GAP) / 2f;
            chips[0].left = LEFT;
            chips[0].right = LEFT + half;
            chips[1].left = RIGHT - half;
            chips[1].right = RIGHT;
        }
        for (int index = 0; index < count; index++) {
            Chip chip = chips[index];
            float room = chip.right - chip.left - 58f - 14f;
            chip.title = GenText.ellipsize(chip.text.title, titlePaint, room);
            chip.subtitle = GenText.ellipsize(chip.text.subtitle, subtitlePaint, room);
        }
    }

    /**
     * Next timed event that is on now or starts within {@link #HORIZON_MS}; all-day events are
     * skipped (they would read "now" all day). Null when there is nothing worth a chip.
     */
    static ChipText calendarChip(List<CalendarNextSource.Event> events, long nowWall, ZoneId zone) {
        if (events == null) return null;
        for (CalendarNextSource.Event event : events) {
            if (event.allDay || event.end <= nowWall || event.start - nowWall > HORIZON_MS) continue;
            String clock = clock(event.start, nowWall, zone);
            String subtitle;
            if (event.start <= nowWall) {
                subtitle = "Now · until " + TIME.format(Instant.ofEpochMilli(event.end).atZone(zone));
            } else {
                subtitle = "In " + CalendarNextSource.relative(event, nowWall) + " · " + clock;
            }
            return new ChipText(event.title, subtitle, false);
        }
        return null;
    }

    /** "2 need you" (amber) or "3 working"; null when T3 has nothing going on. */
    public static ChipText t3Chip(int needsYou, int working) {
        if (needsYou > 0) {
            String title = needsYou + (needsYou == 1 ? " needs you" : " need you");
            String subtitle = working > 0 ? "T3 Code · " + working + " working" : "T3 Code";
            return new ChipText(title, subtitle, true);
        }
        if (working > 0) return new ChipText(working + " working", "T3 Code", false);
        return null;
    }

    private static String clock(long millis, long nowWall, ZoneId zone) {
        ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate();
        if (time.toLocalDate().equals(today)) return TIME.format(time);
        return "Tmrw " + TIME.format(time);
    }

    // ------------------------------------------------------------------ draw

    private void drawChip(Canvas canvas, Chip chip, long now) {
        float top = TOP;
        float bottom = TOP + HEIGHT;
        float mid = TOP + HEIGHT / 2f;
        boolean t3 = T3.equals(chip.kind);
        boolean attention = chip.text.attention;
        int accent = t3 ? (attention ? GenColors.AMBER : GenColors.CYAN) : GenColors.ORB_PALE;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GenColors.withAlpha(GenColors.PANEL, 200));
        canvas.drawRoundRect(chip.left, top, chip.right, bottom, HEIGHT / 2f, HEIGHT / 2f, paint);
        glass.draw(canvas, paint, chip.left, top, chip.right, bottom, HEIGHT / 2f, false);
        float cx = chip.left + 30f;
        paint.setColor(GenColors.withAlpha(accent, 48));
        canvas.drawCircle(cx, mid, 18f, paint);
        if (t3 && !attention) {
            // Working: a slow spinning arc around the glyph.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.4f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(accent);
            arc.set(cx - 16.5f, mid - 16.5f, cx + 16.5f, mid + 16.5f);
            canvas.drawArc(arc, (now % 1400L) * 360f / 1400f, 100f, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
        }
        icons.draw(canvas, paint, t3 ? GenSchema.ICON_CODE : GenSchema.ICON_CALENDAR, cx, mid, 17f,
                t3 && attention ? GenColors.AMBER : GenColors.ORB_PALE, 1.8f);
        if (attention) {
            paint.setColor(GenColors.BACKGROUND);
            canvas.drawCircle(cx + 13f, mid - 13f, 5.5f, paint);
            paint.setColor(GenColors.AMBER);
            canvas.drawCircle(cx + 13f, mid - 13f, 4f, paint);
        }
        float x = chip.left + 58f;
        titlePaint.setColor(attention ? GenColors.lift(GenColors.AMBER, 0.2f) : GenColors.INK);
        canvas.drawText(chip.title, x, mid - 2f, titlePaint);
        subtitlePaint.setColor(GenColors.MUTED);
        canvas.drawText(chip.subtitle, x, mid + 15f, subtitlePaint);
    }

    /** True while a chip animates (the working spinner). */
    public boolean animating() {
        for (int index = 0; index < count; index++) {
            if (T3.equals(chips[index].kind) && !chips[index].text.attention) return true;
        }
        return false;
    }

    private static Paint text(float size, boolean medium) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(medium ? GenFonts.MEDIUM : GenFonts.REGULAR);
        paint.setTextSize(size);
        paint.setColor(GenColors.INK);
        return paint;
    }
}
