package com.resonolabs.feature.compose;

/**
 * Places dictated words at the cursor so typing and talking mix: the text before the cursor,
 * the words, the text after it. Adds one space where words would otherwise touch, never
 * rewrites the words themselves (they are the user's own), and respects a character limit by
 * cutting the dictated part, never the typed text. Pure Java.
 */
public final class DictationText {
    /** The field's new text and where the cursor goes (right after the dictated words). */
    public static final class Splice {
        public final String text;
        public final int cursor;
        /** Start and end of the dictated words inside {@link #text} (empty when nothing fit). */
        public final int start;
        public final int end;
        /** True when the character limit cut the dictated words. */
        public final boolean clipped;

        Splice(String text, int cursor, int start, int end, boolean clipped) {
            this.text = text;
            this.cursor = cursor;
            this.start = start;
            this.end = end;
            this.clipped = clipped;
        }
    }

    private DictationText() {}

    /**
     * @param before    text before the cursor (or selection) when dictation started
     * @param dictated  the words heard so far
     * @param after     text after the cursor (or selection)
     * @param maxLength character limit of the field, or {@code <= 0} for none
     */
    public static Splice splice(String before, String dictated, String after, int maxLength) {
        String head = before == null ? "" : before;
        String tail = after == null ? "" : after;
        String words = dictated == null ? "" : dictated.trim();
        if (words.isEmpty()) return new Splice(head + tail, head.length(), head.length(), head.length(), false);
        String lead = needsSpaceBefore(head, words) ? " " : "";
        String trail = needsSpaceAfter(tail) ? " " : "";
        boolean clipped = false;
        if (maxLength > 0) {
            int room = maxLength - head.length() - tail.length() - lead.length() - trail.length();
            if (room < words.length()) {
                clipped = true;
                words = room <= 0 ? "" : clip(words, room);
                if (words.isEmpty()) {
                    return new Splice(head + tail, head.length(), head.length(), head.length(), true);
                }
            }
        }
        String text = head + lead + words + trail + tail;
        int start = head.length() + lead.length();
        int end = start + words.length();
        return new Splice(text, end, start, end, clipped);
    }

    /** Characters left before the limit ({@code Integer.MAX_VALUE} without one). */
    public static int remaining(int length, int maxLength) {
        return maxLength <= 0 ? Integer.MAX_VALUE : Math.max(0, maxLength - length);
    }

    /** Shows the counter once the text is within 20% of the limit. */
    public static boolean nearLimit(int length, int maxLength) {
        return maxLength > 0 && length >= maxLength * 0.8f;
    }

    static boolean needsSpaceBefore(String head, String words) {
        if (head.isEmpty()) return false;
        char last = head.charAt(head.length() - 1);
        if (Character.isWhitespace(last)) return false;
        if ("([{“‘\"'/-".indexOf(last) >= 0) return false;
        char first = words.charAt(0);
        return ".,!?;:)]}”’".indexOf(first) < 0;
    }

    static boolean needsSpaceAfter(String tail) {
        if (tail.isEmpty()) return false;
        char first = tail.charAt(0);
        if (Character.isWhitespace(first)) return false;
        return ".,!?;:)]}”’\"'".indexOf(first) < 0;
    }

    /** Cut to at most {@code room} characters, at a word boundary when one is close. */
    private static String clip(String words, int room) {
        if (words.length() <= room) return words;
        String cut = words.substring(0, room);
        int space = cut.lastIndexOf(' ');
        if (space >= room / 2) cut = cut.substring(0, space);
        return cut.trim();
    }
}
