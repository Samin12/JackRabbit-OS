package com.resonolabs.feature.genui;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Fake clock/host shared by the GenUI JVM tests. */
final class GenTestSupport {
    private GenTestSupport() {}

    static final class FakeClock implements GenCardStore.Clock {
        long elapsed = 1_000_000L;
        long wall = 1_790_000_000_000L;

        @Override public long elapsed() {
            return elapsed;
        }

        @Override public long wall() {
            return wall;
        }

        void advance(long ms) {
            elapsed += ms;
            wall += ms;
        }
    }

    static final class FakeHost implements GenUiController.Host {
        final List<String> userTexts = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> opened = new ArrayList<>();
        final List<String> started = new ArrayList<>();
        final List<String> finished = new ArrayList<>();
        boolean live = true;
        boolean immersive;
        int invalidations;

        @Override public boolean sendUserText(String text) {
            if (!live) return false;
            userTexts.add(text);
            return true;
        }

        @Override public boolean sendSystemNote(String text, boolean respond) {
            notes.add(text);
            return live;
        }

        @Override public void open(String page) {
            opened.add(page);
        }

        @Override public void startSessionWith(String text) {
            started.add(text);
        }

        @Override public void invalidateUi() {
            invalidations++;
        }

        @Override public void setImmersive(boolean immersive) {
            this.immersive = immersive;
        }

        @Override public void onTimerFinished(GenCard card) {
            finished.add(card.id);
        }
    }

    /** In-memory persistence that captures the last saved JSON. */
    static final class MemoryPersistence implements GenCardStore.Persistence {
        String saved;

        @Override public String load() {
            return saved;
        }

        @Override public void save(String json) {
            saved = json;
        }
    }

    static JSONObject examples() throws Exception {
        try (InputStream input = GenTestSupport.class.getResourceAsStream("/genui/examples.json")) {
            return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static String repeat(char ch, int count) {
        return String.valueOf(ch).repeat(count);
    }
}
