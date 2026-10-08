package com.resonolabs.feature.genui;

/**
 * One card body block. A single final class with nullable typed fields (no subclass zoo),
 * so update_card patches can merge by id trivially. Only the fields of {@link #type} are used.
 */
public final class GenBlock {
    public enum Type {
        TEXT("text"), STAT("stat"), KV("kv"), LIST("list"), CHECKLIST("checklist"),
        PROGRESS("progress"), TIMER("timer"), BARS("bars"), WEATHER("weather"), DIVIDER("divider"),
        /** Host-only (app-built cards): a stored picture, see {@link GenImages}. Never model-authored. */
        IMAGE("image");

        public final String wire;

        Type(String wire) { this.wire = wire; }

        public static Type of(Object value) {
            for (Type type : values()) if (type.wire.equals(value)) return type;
            return null;
        }
    }

    public Type type;
    public String id;

    // text
    public String text;
    public int style = GenSchema.STYLE_BODY;

    // stat (label is shared with progress and timer)
    public String value;
    public String label;
    public String delta;
    public int trend = GenSchema.TREND_NONE;

    // kv
    public String[] keys;
    public String[] vals;
    public int columns = 1;

    // list / checklist
    public GenRow[] items;
    /** If set, tapping a checklist row sends this text (with %s = row text) instead of toggling. */
    public String rowSay;

    // progress: progress < 0 means indeterminate
    public float progress = -1f;
    public String[] steps;
    public int step = -1;

    // timer (elapsedRealtime clock)
    public long endsAt;
    public long totalMs;
    public boolean paused;
    public long pausedRemainingMs;
    public boolean done;

    // bars
    public float[] bars;
    public String[] barLabels;
    public String unit;
    public int highlight = -1;

    // weather
    public String temp;
    public int condition = GenSchema.CONDITION_CLOUDY;
    public String hi;
    public String lo;
    public String place;
    public String[] hourT;
    public String[] hourTemp;
    public int[] hourCondition;

    // image (trusted/host cards only)
    /** {@link GenImages} ref ({@code sha256:<hex>}). */
    public String ref;
    /** Short description (pill summary, accessibility, transcripts). */
    public String alt;
    /** height / width of the picture (layout before the thumbnail is decoded). */
    public float aspect = 0.75f;

    public GenBlock(Type type) {
        this.type = type;
    }

    public int rowCount() {
        return items == null ? 0 : items.length;
    }

    public boolean indeterminate() {
        return progress < 0f;
    }
}
