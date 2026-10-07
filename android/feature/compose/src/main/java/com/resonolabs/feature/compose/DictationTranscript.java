package com.resonolabs.feature.compose;

import java.util.ArrayList;
import java.util.List;

/**
 * The words of one dictation, built from Realtime input-transcription events. Server VAD cuts
 * the speech into items; each item streams {@code delta}s and ends with a {@code completed}
 * transcript that replaces them (or {@code failed}, which keeps what streamed). Items keep the
 * order in which the server first named them, since completions of different items can arrive
 * out of order. Pure Java, main thread only.
 */
public final class DictationTranscript {
    private static final class Segment {
        final String itemId;
        final StringBuilder partial = new StringBuilder();
        String settled;
        boolean done;

        Segment(String itemId) {
            this.itemId = itemId;
        }

        String text() {
            return done ? settled : partial.toString();
        }
    }

    private final List<Segment> segments = new ArrayList<>();

    /** Server VAD heard speech start: this item will carry the words. */
    public void onSpeechStarted(String itemId) {
        segment(itemId, null);
    }

    /** The audio buffer was committed into {@code itemId}, right after {@code previousItemId}. */
    public void onCommitted(String itemId, String previousItemId) {
        segment(itemId, previousItemId);
    }

    /** Streamed words; returns true when the visible text changed. */
    public boolean onDelta(String itemId, String delta) {
        if (delta == null || delta.isEmpty()) return false;
        Segment segment = segment(itemId, null);
        if (segment == null || segment.done) return false;
        segment.partial.append(delta);
        return true;
    }

    /** The final words of an item; replaces its streamed text. Returns true when text changed. */
    public boolean onCompleted(String itemId, String transcript) {
        Segment segment = segment(itemId, null);
        if (segment == null) return false;
        String before = segment.text();
        segment.settled = transcript == null ? "" : transcript.trim();
        segment.done = true;
        return !before.equals(segment.settled);
    }

    /** Transcription failed for an item: keep whatever streamed so far and stop waiting for it. */
    public void onFailed(String itemId) {
        Segment segment = segment(itemId, null);
        if (segment == null || segment.done) return;
        segment.settled = segment.partial.toString().trim();
        segment.done = true;
    }

    /** Items heard but not yet transcribed to the end. */
    public int pending() {
        int count = 0;
        for (Segment segment : segments) if (!segment.done) count++;
        return count;
    }

    /** Stop waiting for every unfinished item (time is up); keeps their streamed words. */
    public void settleAll() {
        for (Segment segment : segments) {
            if (segment.done) continue;
            segment.settled = segment.partial.toString().trim();
            segment.done = true;
        }
    }

    public boolean isEmpty() {
        return text().isEmpty();
    }

    /** Everything dictated so far, items joined by single spaces. */
    public String text() {
        StringBuilder out = new StringBuilder();
        for (Segment segment : segments) {
            String words = collapse(segment.text());
            if (words.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(words);
        }
        return out.toString();
    }

    public void clear() {
        segments.clear();
    }

    private Segment segment(String itemId, String previousItemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        for (Segment segment : segments) if (segment.itemId.equals(itemId)) return segment;
        Segment created = new Segment(itemId);
        int at = segments.size();
        if (previousItemId != null && !previousItemId.isEmpty()) {
            for (int index = 0; index < segments.size(); index++) {
                if (segments.get(index).itemId.equals(previousItemId)) {
                    at = index + 1;
                    break;
                }
            }
        }
        segments.add(at, created);
        return created;
    }

    private static String collapse(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
