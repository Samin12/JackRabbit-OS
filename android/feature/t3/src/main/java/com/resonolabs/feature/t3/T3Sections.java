package com.resonolabs.feature.t3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Groups thread summaries into the list's three sections and builds the header headline. */
final class T3Sections {
    enum Kind { NEEDS_YOU, WORKING, RECENT }

    static final class Section {
        final Kind kind;
        final String title;
        final List<T3Model.Summary> threads;

        Section(Kind kind, String title, List<T3Model.Summary> threads) {
            this.kind = kind;
            this.title = title;
            this.threads = threads;
        }
    }

    /** One colored piece of the header line ("2 need you", "1 working"). */
    static final class Part {
        final String text;
        final int color;

        Part(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    private static final Comparator<T3Model.Summary> ORDER = (a, b) -> {
        int rank = Integer.compare(T3Status.rank(a.status), T3Status.rank(b.status));
        return rank != 0 ? rank : Long.compare(b.updatedAt, a.updatedAt);
    };

    private T3Sections() {}

    static Kind kindOf(String status) {
        if (T3Status.needsYou(status)) return Kind.NEEDS_YOU;
        if (T3Status.working(status)) return Kind.WORKING;
        return Kind.RECENT;
    }

    /** Non-empty sections in display order; each sorted by status rank then most recent first. */
    static List<Section> build(List<T3Model.Summary> threads) {
        List<T3Model.Summary> needs = new ArrayList<>();
        List<T3Model.Summary> working = new ArrayList<>();
        List<T3Model.Summary> recent = new ArrayList<>();
        for (T3Model.Summary thread : threads) {
            switch (kindOf(thread.status)) {
                case NEEDS_YOU -> needs.add(thread);
                case WORKING -> working.add(thread);
                case RECENT -> recent.add(thread);
            }
        }
        needs.sort(ORDER);
        working.sort(ORDER);
        recent.sort(ORDER);
        List<Section> sections = new ArrayList<>();
        if (!needs.isEmpty()) sections.add(new Section(Kind.NEEDS_YOU, "Needs you", needs));
        if (!working.isEmpty()) sections.add(new Section(Kind.WORKING, "Working", working));
        if (!recent.isEmpty()) sections.add(new Section(Kind.RECENT, "Recent", recent));
        return sections;
    }

    /** "2 need you · 1 working · 1 failed", or "All caught up". */
    static List<Part> headline(T3Model.Counts counts) {
        List<Part> parts = new ArrayList<>();
        if (counts.needsYou > 0) {
            parts.add(new Part(counts.needsYou + (counts.needsYou == 1 ? " needs you" : " need you"),
                    T3Status.AMBER));
        }
        if (counts.working > 0) parts.add(new Part(counts.working + " working", T3Status.BLUE));
        if (counts.error > 0) parts.add(new Part(counts.error + " failed", T3Status.RED));
        if (parts.isEmpty()) parts.add(new Part("All caught up", 0xFFF5F8FF));
        return parts;
    }

    static String headlineText(T3Model.Counts counts) {
        StringBuilder out = new StringBuilder();
        for (Part part : headline(counts)) {
            if (out.length() > 0) out.append(" · ");
            out.append(part.text);
        }
        return out.toString();
    }
}
