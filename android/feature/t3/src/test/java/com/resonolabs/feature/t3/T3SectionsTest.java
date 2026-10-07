package com.resonolabs.feature.t3;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class T3SectionsTest {
    private static T3Model.Summary thread(String id, String status, long updatedAt) {
        return new T3Model.Summary(id, "p", "Proj", "T " + id, status, "", updatedAt, 0L, false, "", "", -1d);
    }

    @Test
    public void groupsIntoNeedsYouWorkingRecentInOrder() {
        List<T3Model.Summary> threads = new ArrayList<>();
        threads.add(thread("done-old", "done", 10));
        threads.add(thread("input", "needs-input", 50));
        threads.add(thread("work", "working", 40));
        threads.add(thread("err", "error", 5));
        threads.add(thread("approval", "needs-approval", 1));
        threads.add(thread("done-new", "done", 90));
        List<T3Sections.Section> sections = T3Sections.build(threads);
        assertEquals(3, sections.size());
        assertEquals(T3Sections.Kind.NEEDS_YOU, sections.get(0).kind);
        assertEquals("approval", sections.get(0).threads.get(0).id); // approvals before questions
        assertEquals("input", sections.get(0).threads.get(1).id);
        assertEquals("work", sections.get(1).threads.get(0).id);
        assertEquals("Recent", sections.get(2).title);
        assertEquals("err", sections.get(2).threads.get(0).id); // errors lead Recent
        assertEquals("done-new", sections.get(2).threads.get(1).id);
        assertEquals("done-old", sections.get(2).threads.get(2).id);
    }

    @Test
    public void emptySectionsAreOmitted() {
        List<T3Model.Summary> threads = new ArrayList<>();
        threads.add(thread("a", "done", 1));
        List<T3Sections.Section> sections = T3Sections.build(threads);
        assertEquals(1, sections.size());
        assertEquals(T3Sections.Kind.RECENT, sections.get(0).kind);
        assertEquals(0, T3Sections.build(new ArrayList<>()).size());
    }

    @Test
    public void headlineCountsWhatMatters() {
        assertEquals("2 need you · 1 working", T3Sections.headlineText(new T3Model.Counts(2, 1, 9, 0)));
        assertEquals("1 needs you", T3Sections.headlineText(new T3Model.Counts(1, 0, 3, 0)));
        assertEquals("3 working · 1 failed", T3Sections.headlineText(new T3Model.Counts(0, 3, 0, 1)));
        assertEquals("All caught up", T3Sections.headlineText(new T3Model.Counts(0, 0, 7, 0)));
        assertEquals(T3Status.AMBER, T3Sections.headline(new T3Model.Counts(2, 1, 0, 0)).get(0).color);
    }
}
