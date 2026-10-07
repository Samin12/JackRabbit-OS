package com.resonolabs.feature.genui;

/** One list or checklist row. Checklist rows use {@link #title} as their text. */
public final class GenRow {
    public String title = "";
    public String detail;
    public String trailing;
    public int status = GenSchema.STATUS_NONE;
    public int icon = -1;
    public boolean checked;
    /** Opaque id from a live source (e.g. a task id); never shown. */
    public String ref;

    public GenRow copy() {
        GenRow row = new GenRow();
        row.title = title;
        row.detail = detail;
        row.trailing = trailing;
        row.status = status;
        row.icon = icon;
        row.checked = checked;
        row.ref = ref;
        return row;
    }
}
