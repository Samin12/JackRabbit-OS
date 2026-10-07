package com.resonolabs.feature.cards.board;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tap-to-complete with an undo window. A tap checks the task immediately; the runtime write is sent
 * only after {@link #UNDO_WINDOW_MS} (or when the board hides), so a mistaken tap can be undone
 * with a second tap. Completed ids stay hidden until the runtime stops listing them as open, which
 * avoids a flicker when a refresh races the write. Pure Java.
 */
public final class TaskCompletions {
    public static final long UNDO_WINDOW_MS = 2600L;

    private final Map<String, Long> pending = new LinkedHashMap<>();
    private final Set<String> committing = new HashSet<>();
    private final Set<String> completed = new HashSet<>();

    /** Returns true when the task is now checked (pending), false when this tap undid it. */
    public boolean toggle(String taskId, long now) {
        if (committing.contains(taskId) || completed.contains(taskId)) return true;
        if (pending.remove(taskId) != null) return false;
        pending.put(taskId, now + UNDO_WINDOW_MS);
        return true;
    }

    public boolean isPending(String taskId) { return pending.containsKey(taskId); }

    /** Checked: pending, being written, or written. */
    public boolean isChecked(String taskId) {
        return pending.containsKey(taskId) || committing.contains(taskId) || completed.contains(taskId);
    }

    /** Written (or being written) and therefore no longer shown as an open row. */
    public boolean isHidden(String taskId) {
        return committing.contains(taskId) || completed.contains(taskId);
    }

    /** Pending tasks whose undo window elapsed; they move to "committing". */
    public List<String> due(long now) {
        List<String> result = new ArrayList<>();
        Iterator<Map.Entry<String, Long>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                result.add(entry.getKey());
                committing.add(entry.getKey());
                iterator.remove();
            }
        }
        return result;
    }

    /** Every pending task, regardless of its window (the board is going away). */
    public List<String> flush() {
        List<String> result = new ArrayList<>(pending.keySet());
        committing.addAll(result);
        pending.clear();
        return result;
    }

    public void committed(String taskId) {
        committing.remove(taskId);
        completed.add(taskId);
    }

    /** The write failed: the row returns as open. */
    public void failed(String taskId) {
        committing.remove(taskId);
    }

    /** Earliest undo deadline, or {@link Long#MAX_VALUE} when nothing is pending. */
    public long nextDeadline() {
        long next = Long.MAX_VALUE;
        for (long deadline : pending.values()) next = Math.min(next, deadline);
        return next;
    }

    /** A fresh open-task list arrived: forget written ids the runtime no longer lists. */
    public void reconcile(Collection<String> openTaskIds) {
        completed.retainAll(new HashSet<>(openTaskIds));
        pending.keySet().retainAll(new HashSet<>(openTaskIds));
    }

    public boolean hasPending() { return !pending.isEmpty(); }
}
