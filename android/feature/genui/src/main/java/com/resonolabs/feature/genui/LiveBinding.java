package com.resonolabs.feature.genui;

/** Declares that a card's body is owned by an on-device live source. */
public final class LiveBinding {
    public enum Type {
        TIMER("timer"), CALENDAR_NEXT("calendar-next"), TASKS("tasks"),
        BACKGROUND_RUN("background-run"), T3_THREAD("t3-thread");

        public final String wire;

        Type(String wire) { this.wire = wire; }

        public static Type of(Object value) {
            for (Type type : values()) if (type.wire.equals(value)) return type;
            return null;
        }
    }

    public final Type type;
    public final int durationSec;
    public final String runId;
    public final String threadId;

    public LiveBinding(Type type, int durationSec, String runId, String threadId) {
        this.type = type;
        this.durationSec = durationSec;
        this.runId = runId;
        this.threadId = threadId;
    }

    /** Pollable sources compete for the shared 3-slot polling budget; timers never poll. */
    public boolean polls() {
        return type != Type.TIMER;
    }
}
