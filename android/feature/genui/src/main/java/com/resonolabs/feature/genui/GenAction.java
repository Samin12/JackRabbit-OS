package com.resonolabs.feature.genui;

/** A card button. Exactly one effect: say, open, timer or dismiss. */
public final class GenAction {
    public enum Style { PRIMARY, SECONDARY, DANGER }
    public enum Kind { SAY, OPEN, TIMER, DISMISS }

    public final String label;
    public final Style style;
    public final Kind kind;
    /** say text, open page, or timer op; null for dismiss. */
    public final String arg;

    public GenAction(String label, Style style, Kind kind, String arg) {
        this.label = label;
        this.style = style;
        this.kind = kind;
        this.arg = arg;
    }
}
