package com.resonolabs.feature.genui;

/**
 * A card button. Exactly one effect: say, open, timer or dismiss. {@link Kind#HOST} buttons only
 * exist on app-built cards (e.g. a T3 announcement's Approve/Deny); the model can never author
 * them, and the Voice page's {@link GenUiController.Host#onHostAction} decides what they do.
 */
public final class GenAction {
    public enum Style { PRIMARY, SECONDARY, DANGER }
    public enum Kind { SAY, OPEN, TIMER, DISMISS, HOST }

    public final String label;
    public final Style style;
    public final Kind kind;
    /** say text, open page, timer op or host action; null for dismiss. */
    public final String arg;

    public GenAction(String label, Style style, Kind kind, String arg) {
        this.label = label;
        this.style = style;
        this.kind = kind;
        this.arg = arg;
    }
}
