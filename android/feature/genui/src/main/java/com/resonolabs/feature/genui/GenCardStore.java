package com.resonolabs.feature.genui;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide card state (main thread only).
 *
 * <ul>
 *   <li><b>stack</b>: the Voice-page stack, newest first, at most {@value #MAX_STACK}; one
 *       front card plus peeks. Eviction: oldest ephemeral first, then oldest terminal live
 *       card; pinned and running live cards are never deleted by eviction, only moved to
 *       the deck-only <b>stash</b>.</li>
 *   <li><b>stash</b>: live/pinned cards that are off the Voice stack but still in Cards &gt; Live.</li>
 *   <li><b>recent</b>: ring of the last {@value #MAX_RECENT} retired cards, kept 24 h.</li>
 * </ul>
 *
 * Persists to {@code files/genui/cards.json} (debounced 1 s, written on a worker thread).
 */
public final class GenCardStore {
    public interface Listener {
        void onCardsChanged();

        default void onCardEvent(int event, GenCard card) { }
    }

    public interface Clock {
        long elapsed();

        long wall();
    }

    public interface Persistence {
        String load();

        void save(String json);
    }

    public interface Scheduler {
        void schedule(Runnable runnable, long delayMs);

        void cancel(Runnable runnable);
    }

    public static final class PutResult {
        public final int stackSize;
        public final List<String> evicted;
        public final List<String> notes;

        PutResult(int stackSize, List<String> evicted, List<String> notes) {
            this.stackSize = stackSize;
            this.evicted = evicted;
            this.notes = notes;
        }
    }

    public static final int EVENT_SHOWN = 1;
    public static final int EVENT_DISMISSED = 2;
    public static final int EVENT_TIMER_DONE = 3;
    public static final int EVENT_UPDATED = 4;

    public static final int MAX_STACK = 5;
    public static final int MAX_PINNED = 6;
    public static final int MAX_RECENT = 10;
    public static final long LIVE_LINGER_MS = 120_000L;
    public static final long LIVE_MAX_MS = 8L * 60L * 60L * 1000L;
    public static final long TIMER_DONE_KEEP_MS = 10L * 60L * 1000L;
    public static final long RECENT_KEEP_MS = 24L * 60L * 60L * 1000L;
    static final long SAVE_DEBOUNCE_MS = 1_000L;
    private static final int MAX_USER_DISMISSED = 24;

    private static GenCardStore instance;

    private final Clock clock;
    private final Persistence persistence;
    private final Scheduler scheduler;
    private final ArrayList<GenCard> stack = new ArrayList<>();
    private final ArrayList<GenCard> stash = new ArrayList<>();
    private final ArrayList<GenCard> recent = new ArrayList<>();
    private final ArrayList<String> userDismissed = new ArrayList<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Runnable saveNow = this::saveNow;
    private final Runnable tickNow = () -> tick(now());
    private int front;
    private long nextDeadline = Long.MAX_VALUE;

    GenCardStore(Clock clock, Persistence persistence, Scheduler scheduler) {
        this.clock = clock;
        this.persistence = persistence;
        this.scheduler = scheduler;
        if (persistence != null) restore(persistence.load());
    }

    /** The process singleton, persisted under the app's files directory. */
    public static synchronized GenCardStore get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            instance = new GenCardStore(new SystemClockSource(),
                    new FilePersistence(new File(new File(app.getFilesDir(), "genui"), "cards.json")),
                    new MainScheduler());
        }
        return instance;
    }

    /** A private, unpersisted store (tests and the debug preview). */
    public static GenCardStore inMemory(Clock clock, Scheduler scheduler) {
        return new GenCardStore(clock, null, scheduler);
    }

    public long now() {
        return clock.elapsed();
    }

    // ------------------------------------------------------------------ listeners

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        Listener[] snapshot = listeners.toArray(new Listener[0]);
        for (Listener listener : snapshot) listener.onCardsChanged();
        requestSave();
        scheduleTick();
    }

    private void notifyEvent(int event, GenCard card) {
        Listener[] snapshot = listeners.toArray(new Listener[0]);
        for (Listener listener : snapshot) listener.onCardEvent(event, card);
    }

    // ------------------------------------------------------------------ reads (allocation-free)

    public int stackSize() {
        return stack.size();
    }

    /** Card at {@code index} positions behind the front (0 = front), wrapping. */
    public GenCard stackCard(int index) {
        if (stack.isEmpty()) return null;
        return stack.get(Math.floorMod(front + index, stack.size()));
    }

    public GenCard front() {
        return stackCard(0);
    }

    public int frontIndex() {
        return stack.isEmpty() ? 0 : Math.floorMod(front, stack.size());
    }

    public GenCard find(String id) {
        if (id == null) return null;
        for (int index = 0; index < stack.size(); index++) if (stack.get(index).id.equals(id)) return stack.get(index);
        for (int index = 0; index < stash.size(); index++) if (stash.get(index).id.equals(id)) return stash.get(index);
        return null;
    }

    public boolean inStack(GenCard card) {
        return stack.contains(card);
    }

    public boolean wasDismissedByUser(String id) {
        return userDismissed.contains(id);
    }

    public int pinnedCount() {
        int count = 0;
        for (GenCard card : stack) if (card.pinned) count++;
        for (GenCard card : stash) if (card.pinned) count++;
        return count;
    }

    /** Every live or pinned card (stack and deck-only), for Cards &gt; Live and live sources. */
    public List<GenCard> liveAndPinned() {
        ArrayList<GenCard> out = new ArrayList<>();
        for (GenCard card : stack) if (card.live != null || card.pinned) out.add(card);
        for (GenCard card : stash) if (card.live != null || card.pinned) out.add(card);
        return out;
    }

    /** Stack plus stash: every card that is not retired. */
    public List<GenCard> activeCards() {
        ArrayList<GenCard> out = new ArrayList<>(stack.size() + stash.size());
        out.addAll(stack);
        out.addAll(stash);
        return out;
    }

    public List<GenCard> recent() {
        return new ArrayList<>(recent);
    }

    public int deckCount() {
        return liveAndPinned().size() + recent.size();
    }

    // ------------------------------------------------------------------ writes

    /** show_card: replaces any card with the same id and puts the new card in front. */
    public PutResult put(GenCard card) {
        long now = now();
        ArrayList<String> notes = new ArrayList<>();
        GenCard existing = find(card.id);
        if (existing != null) {
            stack.remove(existing);
            stash.remove(existing);
            if (existing.originSessionId != null && card.originSessionId == null) {
                card.originSessionId = existing.originSessionId;
            }
        }
        removeById(recent, card.id);
        userDismissed.remove(card.id);
        if (card.pinned && pinnedCount() >= MAX_PINNED) {
            card.pinned = false;
            notes.add("pinned: limit " + MAX_PINNED + " reached, not pinned");
        }
        if (card.arrivedAt == 0L) card.arrivedAt = now;
        stack.add(0, card);
        front = 0;
        ArrayList<String> evicted = new ArrayList<>();
        while (stack.size() > MAX_STACK) {
            GenCard victim = evictionVictim(card);
            if (victim == null) break;
            stack.remove(victim);
            if (victim.live != null && victim.isRunningLive() || victim.pinned) {
                stash.add(0, victim);
            } else {
                retire(victim, now);
            }
            evicted.add(victim.id);
        }
        notifyChanged();
        notifyEvent(EVENT_SHOWN, card);
        return new PutResult(stack.size(), evicted, notes);
    }

    private GenCard evictionVictim(GenCard keep) {
        for (int pass = 0; pass < 4; pass++) {
            for (int index = stack.size() - 1; index >= 0; index--) {
                GenCard card = stack.get(index);
                if (card == keep) continue;
                boolean match = switch (pass) {
                    case 0 -> card.isEphemeral();
                    case 1 -> card.live != null && !card.isRunningLive() && !card.pinned;
                    case 2 -> card.pinned && !card.isRunningLive();
                    default -> true;
                };
                if (match) return card;
            }
        }
        return null;
    }

    /** After update_card or a local edit: bumps the revision; {@code touch} resets the ttl clock. */
    public void changed(GenCard card, boolean touch) {
        long now = now();
        card.revision++;
        if (touch) {
            card.updatedAt = now;
            if (stack.size() > 1 && front() != card) card.pulseAt = now;
            if (stash.remove(card)) {
                // The model is talking about a deck-only card again: bring it back on screen.
                stack.add(0, card);
                front = 0;
                card.arrivedAt = now;
            }
        }
        notifyChanged();
        notifyEvent(EVENT_UPDATED, card);
    }

    /** A live source wrote new content; no ttl reset, no pulse. */
    public void liveChanged(GenCard card) {
        card.revision++;
        card.liveUpdatedAt = now();
        notifyChanged();
    }

    public void timerDone(GenCard card) {
        GenBlock timer = card.timerBlock();
        if (timer == null || timer.done) return;
        long now = now();
        timer.done = true;
        timer.paused = false;
        card.terminal = true;
        card.terminalAt = now;
        card.state = GenCard.State.DONE;
        card.revision++;
        card.pulseAt = now;
        if (stash.remove(card)) {
            stack.add(0, card);
            front = 0;
        } else {
            bringToFront(card);
        }
        notifyChanged();
        notifyEvent(EVENT_TIMER_DONE, card);
    }

    /** Removes a card. User dismissals are remembered so update_card can say so. */
    public boolean dismiss(String id, boolean byUser) {
        GenCard card = find(id);
        if (card == null) return false;
        removeCard(card, byUser);
        notifyChanged();
        notifyEvent(EVENT_DISMISSED, card);
        return true;
    }

    /** dismiss_card all=true: clears the Voice stack (running timers stay unless asked). */
    public List<String> dismissAll(boolean includeTimers) {
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<GenCard> snapshot = new ArrayList<>(stack);
        for (GenCard card : snapshot) {
            if (card.isTimer() && card.isRunningLive() && !includeTimers) continue;
            if (card.pinned) {
                stack.remove(card);
                stash.add(0, card);
            } else {
                removeCard(card, false);
            }
            ids.add(card.id);
        }
        if (!ids.isEmpty()) {
            clampFront();
            notifyChanged();
        }
        return ids;
    }

    private void removeCard(GenCard card, boolean byUser) {
        long now = now();
        int index = stack.indexOf(card);
        if (index >= 0) {
            stack.remove(index);
            if (index < front) front--;
        }
        stash.remove(card);
        clampFront();
        if (byUser) {
            userDismissed.remove(card.id);
            userDismissed.add(card.id);
            while (userDismissed.size() > MAX_USER_DISMISSED) userDismissed.remove(0);
        } else if (!card.isTimer()) {
            retire(card, now);
        }
    }

    public void cycle(int direction) {
        if (stack.size() < 2) return;
        front = Math.floorMod(front + direction, stack.size());
        stack.get(front).arrivedAt = 0L;
        notifyChanged();
    }

    public void bringToFront(GenCard card) {
        int index = stack.indexOf(card);
        if (index >= 0) front = index;
    }

    /** Pin/unpin from the deck. */
    public boolean setPinned(GenCard card, boolean pinned) {
        if (pinned && !card.pinned && pinnedCount() >= MAX_PINNED) return false;
        card.pinned = pinned;
        card.revision++;
        notifyChanged();
        return true;
    }

    /** Session end: ephemeral cards leave the Voice stack for Recent (genui.md 4.4). */
    public void onSessionEnded() {
        long now = now();
        boolean changed = false;
        for (int index = stack.size() - 1; index >= 0; index--) {
            GenCard card = stack.get(index);
            if (card.isEphemeral()) {
                stack.remove(index);
                retire(card, now);
                changed = true;
            } else {
                card.presentation = 0;
            }
        }
        clampFront();
        if (changed) notifyChanged();
    }

    /** Applies lifetimes. Returns true if anything changed. Safe to call every frame. */
    public boolean tick(long now) {
        if (now < nextDeadline) return false;
        boolean changed = false;
        for (int index = stack.size() - 1; index >= 0; index--) {
            GenCard card = stack.get(index);
            if (expired(card, now)) {
                stack.remove(index);
                if (index < front) front--;
                if (!card.isTimer()) retire(card, now);
                changed = true;
            }
        }
        for (int index = stash.size() - 1; index >= 0; index--) {
            GenCard card = stash.get(index);
            if (expired(card, now)) {
                stash.remove(index);
                if (!card.isTimer()) retire(card, now);
                changed = true;
            }
        }
        for (int index = recent.size() - 1; index >= 0; index--) {
            if (now - recent.get(index).retiredAt >= RECENT_KEEP_MS) {
                recent.remove(index);
                changed = true;
            }
        }
        clampFront();
        nextDeadline = Long.MAX_VALUE;
        if (changed) notifyChanged();
        else scheduleTick();
        return changed;
    }

    private boolean expired(GenCard card, long now) {
        if (card.pinned) return false;
        if (card.live == null) return now - card.updatedAt >= card.ttlMs;
        if (card.isTimer()) {
            GenBlock timer = card.timerBlock();
            return timer != null && timer.done && now - card.terminalAt >= TIMER_DONE_KEEP_MS
                    || now - card.createdAt >= LIVE_MAX_MS + TIMER_DONE_KEEP_MS && (timer == null || timer.done);
        }
        if (card.terminal) return now - card.terminalAt >= LIVE_LINGER_MS;
        return now - card.createdAt >= LIVE_MAX_MS;
    }

    private long deadline(GenCard card) {
        if (card.pinned) return Long.MAX_VALUE;
        if (card.live == null) return card.updatedAt + card.ttlMs;
        if (card.isTimer()) {
            GenBlock timer = card.timerBlock();
            return timer != null && timer.done ? card.terminalAt + TIMER_DONE_KEEP_MS : Long.MAX_VALUE;
        }
        if (card.terminal) return card.terminalAt + LIVE_LINGER_MS;
        return card.createdAt + LIVE_MAX_MS;
    }

    private void scheduleTick() {
        long deadline = Long.MAX_VALUE;
        for (GenCard card : stack) deadline = Math.min(deadline, deadline(card));
        for (GenCard card : stash) deadline = Math.min(deadline, deadline(card));
        for (GenCard card : recent) deadline = Math.min(deadline, card.retiredAt + RECENT_KEEP_MS);
        nextDeadline = deadline;
        if (scheduler == null) return;
        scheduler.cancel(tickNow);
        if (deadline != Long.MAX_VALUE) {
            scheduler.schedule(tickNow, Math.max(50L, deadline - now() + 20L));
        }
    }

    private void retire(GenCard card, long now) {
        if (card.retiredAt == 0L) card.retiredAt = now;
        card.presentation = 0;
        removeById(recent, card.id);
        recent.add(0, card);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
    }

    private void clampFront() {
        if (stack.isEmpty()) front = 0;
        else front = Math.floorMod(Math.min(front, stack.size() - 1), stack.size());
    }

    private static void removeById(List<GenCard> cards, String id) {
        for (int index = cards.size() - 1; index >= 0; index--) {
            if (cards.get(index).id.equals(id)) cards.remove(index);
        }
    }

    // ------------------------------------------------------------------ summary

    /**
     * One "[Screen]" line for the session-start system note, or "" when nothing is shown,
     * e.g. {@code [Screen] Cards on screen: timer-pasta (Pasta, 4:12 left), groceries (Grocery list, 2/6 checked).}
     */
    public String screenSummary() {
        long now = now();
        ArrayList<GenCard> shown = new ArrayList<>(stack);
        for (GenCard card : stash) if (card.live != null && card.isRunningLive()) shown.add(card);
        if (shown.isEmpty()) return "";
        StringBuilder out = new StringBuilder("[Screen] Cards on screen: ");
        int count = 0;
        for (GenCard card : shown) {
            if (count == 6) {
                out.append(", +").append(shown.size() - 6).append(" more");
                break;
            }
            if (count > 0) out.append(", ");
            out.append(card.id).append(" (").append(card.displayTitle());
            String status = statusOf(card, now);
            if (!status.isEmpty()) out.append(", ").append(status);
            out.append(')');
            count++;
        }
        return out.append('.').toString();
    }

    static String statusOf(GenCard card, long now) {
        if (card.isTimer()) {
            GenBlock timer = card.timerBlock();
            if (timer == null) return "";
            if (timer.done) return "done";
            String left = GenTimers.brief(GenTimers.remaining(timer, now)) + " left";
            return timer.paused ? left + ", paused" : left;
        }
        if (card.live != null) {
            if (card.terminal) return card.liveStatus == GenSchema.STATUS_ERROR ? "failed" : "finished";
            return card.liveNote != null ? card.liveNote.toLowerCase(Locale.ROOT) : "running";
        }
        for (GenBlock block : card.body) {
            if (block.type == GenBlock.Type.CHECKLIST && block.items != null) {
                int checked = 0;
                for (GenRow row : block.items) if (row.checked) checked++;
                return checked + "/" + block.items.length + " checked";
            }
        }
        return card.pinned ? "pinned" : "";
    }

    // ------------------------------------------------------------------ persistence

    String serialize() {
        long offset = clock.wall() - clock.elapsed();
        JSONObject root = new JSONObject();
        JSONArray cards = new JSONArray();
        try {
            for (GenCard card : stack) cards.put(GenCardCodec.cardToJson(card, offset).put("where", "stack"));
            for (GenCard card : stash) cards.put(GenCardCodec.cardToJson(card, offset).put("where", "stash"));
            for (GenCard card : recent) cards.put(GenCardCodec.cardToJson(card, offset).put("where", "recent"));
            root.put("version", 1);
            root.put("cards", cards);
        } catch (Exception ignored) { }
        return root.toString();
    }

    void restore(String json) {
        if (json == null || json.isBlank()) return;
        try {
            JSONObject root = new JSONObject(json);
            if (root.optInt("version", 0) != 1) return;
            JSONArray cards = root.optJSONArray("cards");
            if (cards == null) return;
            long now = clock.elapsed();
            long offset = clock.wall() - now;
            for (int index = 0; index < cards.length(); index++) {
                JSONObject item = cards.optJSONObject(index);
                GenCard card = GenCardCodec.cardFromJson(item, offset, now);
                if (card == null) continue;
                String where = item.optString("where", "stack");
                if ("recent".equals(where)) {
                    if (card.retiredAt == 0L) card.retiredAt = now;
                    if (recent.size() < MAX_RECENT) recent.add(card);
                } else if (card.isEphemeral()) {
                    // The session that showed it is over: it belongs in Recent now.
                    retire(card, now);
                } else if ("stash".equals(where)) {
                    stash.add(card);
                } else if (stack.size() < MAX_STACK) {
                    stack.add(card);
                } else {
                    stash.add(card);
                }
            }
            front = 0;
            scheduleTick();
        } catch (Exception ignored) {
            // A corrupt file must never take the Voice page down; start empty.
            stack.clear();
            stash.clear();
            recent.clear();
        }
    }

    private void requestSave() {
        if (persistence == null) return;
        if (scheduler == null) {
            saveNow();
            return;
        }
        scheduler.cancel(saveNow);
        scheduler.schedule(saveNow, SAVE_DEBOUNCE_MS);
    }

    private void saveNow() {
        if (persistence != null) persistence.save(serialize());
    }

    // ------------------------------------------------------------------ Android plumbing

    static final class SystemClockSource implements Clock {
        @Override public long elapsed() {
            return android.os.SystemClock.elapsedRealtime();
        }

        @Override public long wall() {
            return System.currentTimeMillis();
        }
    }

    static final class MainScheduler implements Scheduler {
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

        @Override public void schedule(Runnable runnable, long delayMs) {
            handler.postDelayed(runnable, delayMs);
        }

        @Override public void cancel(Runnable runnable) {
            handler.removeCallbacks(runnable);
        }
    }

    /** Atomic temp-file + rename writes on one worker thread; failures only log. */
    static final class FilePersistence implements Persistence {
        private final File file;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "genui-store");
            thread.setDaemon(true);
            return thread;
        });

        FilePersistence(File file) {
            this.file = file;
        }

        @Override public String load() {
            try {
                if (!file.isFile() || file.length() > 1_000_000L) return null;
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception error) {
                android.util.Log.w("GenUi", "card store unreadable; starting empty");
                return null;
            }
        }

        @Override public void save(String json) {
            worker.execute(() -> {
                try {
                    File dir = file.getParentFile();
                    if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
                    File temp = new File(dir, file.getName() + ".next");
                    try (FileOutputStream output = new FileOutputStream(temp)) {
                        output.write(json.getBytes(StandardCharsets.UTF_8));
                        output.getFD().sync();
                    }
                    if (!temp.renameTo(file)) android.util.Log.w("GenUi", "card store rename failed");
                } catch (Exception error) {
                    android.util.Log.w("GenUi", "card store write failed: " + error.getClass().getSimpleName());
                }
            });
        }
    }
}
