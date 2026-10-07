package com.resonolabs.feature.cards.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class TaskCompletionsTest {
    @Test public void secondTapInsideTheWindowUndoes() {
        TaskCompletions completions = new TaskCompletions();
        assertTrue(completions.toggle("a", 1_000L));
        assertTrue(completions.isChecked("a"));
        assertFalse(completions.isHidden("a"));
        assertFalse(completions.toggle("a", 2_000L));
        assertFalse(completions.isChecked("a"));
        assertEquals(List.of(), completions.due(1_000L + TaskCompletions.UNDO_WINDOW_MS));
    }

    @Test public void writeIsSentOnlyAfterTheWindowAndThenHidesTheRow() {
        TaskCompletions completions = new TaskCompletions();
        completions.toggle("a", 0L);
        completions.toggle("b", 500L);
        assertEquals(TaskCompletions.UNDO_WINDOW_MS, completions.nextDeadline());
        assertEquals(List.of(), completions.due(TaskCompletions.UNDO_WINDOW_MS - 1));
        assertEquals(List.of("a"), completions.due(TaskCompletions.UNDO_WINDOW_MS));
        assertTrue(completions.isHidden("a"));
        assertTrue("a tap while writing cannot undo", completions.toggle("a", TaskCompletions.UNDO_WINDOW_MS + 1));
        completions.committed("a");
        assertTrue(completions.isHidden("a"));
        assertEquals(500L + TaskCompletions.UNDO_WINDOW_MS, completions.nextDeadline());
    }

    @Test public void failedWriteBringsTheRowBack() {
        TaskCompletions completions = new TaskCompletions();
        completions.toggle("a", 0L);
        completions.due(TaskCompletions.UNDO_WINDOW_MS);
        completions.failed("a");
        assertFalse(completions.isChecked("a"));
        assertFalse(completions.isHidden("a"));
    }

    @Test public void flushSendsEverythingPendingWhenTheBoardHides() {
        TaskCompletions completions = new TaskCompletions();
        completions.toggle("a", 0L);
        completions.toggle("b", 0L);
        assertEquals(List.of("a", "b"), completions.flush());
        assertFalse(completions.hasPending());
        assertEquals(Long.MAX_VALUE, completions.nextDeadline());
    }

    @Test public void reconcileForgetsIdsTheRuntimeNoLongerListsAsOpen() {
        TaskCompletions completions = new TaskCompletions();
        completions.toggle("a", 0L);
        completions.due(TaskCompletions.UNDO_WINDOW_MS);
        completions.committed("a");
        completions.reconcile(List.of("a", "b"));   // a refresh raced the write: still hidden
        assertTrue(completions.isHidden("a"));
        completions.reconcile(List.of("b"));
        assertFalse(completions.isHidden("a"));
        completions.toggle("gone", 0L);
        completions.reconcile(List.of("b"));        // completed elsewhere (e.g. by Voice)
        assertFalse(completions.isPending("gone"));
    }
}
