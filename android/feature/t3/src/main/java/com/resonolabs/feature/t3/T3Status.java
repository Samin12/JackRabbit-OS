package com.resonolabs.feature.t3;

/**
 * Status vocabulary from CONTRACTS §2 mapped to what the R1 shows: an orb tone, a short label
 * and a sort rank. Colors are literal ARGB so the mapping is testable off-device.
 */
final class T3Status {
    static final String NEEDS_APPROVAL = "needs-approval";
    static final String NEEDS_INPUT = "needs-input";
    static final String WORKING = "working";
    static final String ERROR = "error";
    static final String DONE = "done";

    /** What the status orb looks like. */
    enum Tone { ATTENTION, WORKING, FRESH, QUIET, ERROR }

    static final int AMBER = 0xFFFFC45C;
    static final int BLUE = 0xFF5CA2FF;
    static final int GREEN = 0xFF5FD69B;
    static final int QUIET = 0xFF6F7A8E;
    static final int RED = 0xFFFF6B6B;

    private T3Status() {}

    static Tone tone(String status, boolean unread) {
        if (status == null) return Tone.QUIET;
        return switch (status) {
            case NEEDS_APPROVAL, NEEDS_INPUT -> Tone.ATTENTION;
            case WORKING -> Tone.WORKING;
            case ERROR -> Tone.ERROR;
            default -> unread ? Tone.FRESH : Tone.QUIET;
        };
    }

    static int color(Tone tone) {
        return switch (tone) {
            case ATTENTION -> AMBER;
            case WORKING -> BLUE;
            case FRESH -> GREEN;
            case ERROR -> RED;
            case QUIET -> QUIET;
        };
    }

    static int color(String status, boolean unread) {
        return color(tone(status, unread));
    }

    /** The runtime's label wins; otherwise a short, glanceable default. */
    static String label(String status, String statusLabel) {
        if (statusLabel != null && !statusLabel.isBlank()) return statusLabel.trim();
        if (status == null) return "Idle";
        return switch (status) {
            case NEEDS_APPROVAL -> "Needs approval";
            case NEEDS_INPUT -> "Has a question";
            case WORKING -> "Working";
            case ERROR -> "Failed";
            case DONE -> "Done";
            default -> "Idle";
        };
    }

    static boolean needsYou(String status) {
        return NEEDS_APPROVAL.equals(status) || NEEDS_INPUT.equals(status);
    }

    static boolean working(String status) {
        return WORKING.equals(status);
    }

    /** Contract order: needs-approval, needs-input, working, error, done. */
    static int rank(String status) {
        if (status == null) return 5;
        return switch (status) {
            case NEEDS_APPROVAL -> 0;
            case NEEDS_INPUT -> 1;
            case WORKING -> 2;
            case ERROR -> 3;
            case DONE -> 4;
            default -> 5;
        };
    }
}
