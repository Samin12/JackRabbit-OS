package com.resonolabs.feature.genui;

/**
 * Limits and enum vocabularies shared by the parser, the codec and the renderer.
 * Must match {@code runtime/sam_runtime/tools/genui_tools.json} and the reference
 * validator in the GenUI research prototype ({@code render_cards.py}).
 */
public final class GenSchema {
    private GenSchema() {}

    // ---- string limits (characters, after whitespace collapse) ----
    public static final int ID = 40;
    public static final int BLOCK_ID = 32;
    public static final int TITLE = 48;
    public static final int SUBTITLE = 64;
    public static final int EYEBROW = 24;
    public static final int TEXT = 280;
    public static final int LABEL = 40;
    public static final int VALUE = 16;
    public static final int DELTA = 16;
    public static final int ACTION_LABEL = 18;
    public static final int SAY = 200;
    public static final int ROW_TITLE = 48;
    public static final int ROW_DETAIL = 64;
    public static final int ROW_TRAILING = 14;
    public static final int CHECK_TEXT = 64;
    public static final int KV_KEY = 24;
    public static final int KV_VALUE = 28;
    public static final int STEP_LABEL = 14;
    public static final int BAR_LABEL = 6;
    public static final int UNIT = 8;
    public static final int TEMP = 6;
    public static final int PLACE = 28;
    public static final int HOUR_T = 4;
    public static final int HOUR_TEMP = 5;
    public static final int REF = 80;
    public static final int IMAGE_ALT = 120;
    public static final float IMAGE_ASPECT_MIN = 0.2f;
    public static final float IMAGE_ASPECT_MAX = 3f;

    // ---- count limits ----
    public static final int BODY = 6;
    public static final int ITEMS = 8;
    public static final int PAIRS = 6;
    public static final int ACTIONS = 2;
    /** App-built (trusted) cards may carry one more button, e.g. Open + Deny + Approve. */
    public static final int HOST_ACTIONS = 3;
    public static final int BARS = 12;
    public static final int HOURS = 5;
    public static final int STEPS = 4;
    public static final int MAX_ARGS_BYTES = 16 * 1024;

    // ---- number ranges ----
    public static final int TTL_DEFAULT_SEC = 600;
    public static final int TTL_MIN_SEC = 10;
    public static final int TTL_MAX_SEC = 86_400;
    public static final int DURATION_MIN_SEC = 1;
    public static final int DURATION_MAX_SEC = 86_400;

    // ---- vocabularies (index = int code stored on the model) ----
    public static final String[] ICONS = {
            "calendar", "clock", "timer", "check", "task", "code", "mail", "bolt", "pin",
            "cart", "plane", "car", "music", "weather", "chart", "warning", "star", "home"};
    public static final int ICON_CALENDAR = 0;
    public static final int ICON_CLOCK = 1;
    public static final int ICON_TIMER = 2;
    public static final int ICON_CHECK = 3;
    public static final int ICON_TASK = 4;
    public static final int ICON_CODE = 5;
    public static final int ICON_BOLT = 7;
    public static final int ICON_WEATHER = 13;
    public static final int ICON_CHART = 14;
    public static final int ICON_WARNING = 15;

    public static final String[] CONDITIONS = {
            "sunny", "clear-night", "partly-cloudy", "cloudy", "rain", "storm", "snow", "fog", "wind"};
    public static final int CONDITION_CLOUDY = 3;

    public static final String[] STATUSES = {"ok", "warn", "error", "active", "idle"};
    public static final int STATUS_NONE = -1;
    public static final int STATUS_OK = 0;
    public static final int STATUS_WARN = 1;
    public static final int STATUS_ERROR = 2;
    public static final int STATUS_ACTIVE = 3;
    public static final int STATUS_IDLE = 4;

    public static final String[] TEXT_STYLES = {"body", "muted", "lead"};
    public static final int STYLE_BODY = 0;
    public static final int STYLE_MUTED = 1;
    public static final int STYLE_LEAD = 2;

    public static final String[] TRENDS = {"up", "down", "flat"};
    public static final int TREND_NONE = -1;
    public static final int TREND_UP = 0;
    public static final int TREND_DOWN = 1;
    public static final int TREND_FLAT = 2;

    public static final String[] OPEN_PAGES = {"calendar", "tasks", "cards", "runs", "transcript"};
    public static final String[] TIMER_OPS = {"add1m", "add5m", "pause", "resume"};

    public static int indexOf(String[] table, Object value) {
        if (!(value instanceof String text)) return -1;
        for (int index = 0; index < table.length; index++) {
            if (table[index].equals(text)) return index;
        }
        return -1;
    }

    public static String nameOf(String[] table, int index) {
        return index >= 0 && index < table.length ? table[index] : null;
    }
}
