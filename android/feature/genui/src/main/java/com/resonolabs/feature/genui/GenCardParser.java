package com.resonolabs.feature.genui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Validates and clamps model-authored card JSON (genui.md section 3.4). Never rejects for
 * size: strings are ellipsized, counts truncated, unknown enums defaulted, and every
 * trim is reported back to the model in {@code trimmed}. Rejects only for invalid JSON,
 * payloads over 16 KB, a missing/empty id, a missing title, or (in the controller) an
 * unknown id. Pure Java + org.json so it runs in JVM unit tests.
 */
public final class GenCardParser {
    private static final int MAX_NOTES = 10;
    private static final String ELLIPSIS = "…";

    private GenCardParser() {}

    // ------------------------------------------------------------------ results

    public static final class ParseResult {
        public final boolean ok;
        public final String error;
        public final GenCard card;
        public final List<String> trimmed;

        ParseResult(boolean ok, String error, GenCard card, List<String> trimmed) {
            this.ok = ok;
            this.error = error;
            this.card = card;
            this.trimmed = trimmed;
        }
    }

    public static final class UpdateResult {
        public final boolean ok;
        public final String error;
        public final List<String> changed;
        public final List<String> trimmed;
        /** True when the update touched anything visible (bump the revision). */
        public final boolean visible;
        public final boolean liveBodyIgnored;

        UpdateResult(boolean ok, String error, List<String> changed, List<String> trimmed,
                     boolean visible, boolean liveBodyIgnored) {
            this.ok = ok;
            this.error = error;
            this.changed = changed;
            this.trimmed = trimmed;
            this.visible = visible;
            this.liveBodyIgnored = liveBodyIgnored;
        }
    }

    public static final class DismissRequest {
        public final boolean ok;
        public final String error;
        public final String id;
        public final boolean all;
        public final boolean includeTimers;

        DismissRequest(boolean ok, String error, String id, boolean all, boolean includeTimers) {
            this.ok = ok;
            this.error = error;
            this.id = id;
            this.all = all;
            this.includeTimers = includeTimers;
        }
    }

    /** Collected trim notes, capped so tool outputs stay tiny. */
    static final class Notes {
        final ArrayList<String> items = new ArrayList<>();
        private int dropped;

        void add(String note) {
            if (items.size() < MAX_NOTES) items.add(note);
            else dropped++;
        }

        List<String> list() {
            if (dropped > 0 && items.size() == MAX_NOTES) {
                items.set(MAX_NOTES - 1, "+" + (dropped + 1) + " more trims");
                dropped = 0;
            }
            return items;
        }
    }

    // ------------------------------------------------------------------ entry points

    public static JSONObject parseArguments(String raw) throws IllegalArgumentException {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) text = "{}";
        if (text.getBytes(StandardCharsets.UTF_8).length > GenSchema.MAX_ARGS_BYTES) {
            throw new IllegalArgumentException("Card is too large (max 16 KB). Send fewer rows.");
        }
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            throw new IllegalArgumentException("Arguments are not valid JSON.");
        }
    }

    public static ParseResult parseShow(String raw, long now) {
        JSONObject json;
        try {
            json = parseArguments(raw);
        } catch (IllegalArgumentException error) {
            return new ParseResult(false, error.getMessage(), null, List.of());
        }
        return parseShow(json, now);
    }

    public static ParseResult parseShow(JSONObject json, long now) {
        return parseCard(json, now, false);
    }

    /** {@code trusted} accepts internal fields (timer clocks, row prompts) written by the codec. */
    static ParseResult parseCard(JSONObject json, long now, boolean trusted) {
        Notes notes = new Notes();
        String id = normalizeId(value(json, "id"));
        if (id.isEmpty()) {
            return new ParseResult(false, "id is required (letters, digits, - or _).", null, List.of());
        }
        String title = clean(value(json, "title"), GenSchema.TITLE, notes, "title");
        if (title == null) {
            return new ParseResult(false, "title is required.", null, List.of());
        }
        GenCard card = new GenCard();
        card.id = id;
        card.title = title;
        card.subtitle = clean(value(json, "subtitle"), GenSchema.SUBTITLE, notes, "subtitle");
        card.eyebrow = clean(value(json, "eyebrow"), GenSchema.EYEBROW, notes, "eyebrow");
        Object size = value(json, "size");
        card.size = "compact".equals(size) ? GenCard.Size.COMPACT : GenCard.Size.CARD;
        card.icon = icon(value(json, "icon"), notes, "icon");
        card.accent = accent(value(json, "accent"), notes);
        card.pinned = bool(value(json, "pinned"));
        card.ttlMs = ttl(value(json, "ttlSec"), notes) * 1000L;
        card.createdAt = now;
        card.updatedAt = now;
        card.arrivedAt = now;

        card.live = live(value(json, "live"), notes);
        parseBody(card.body, value(json, "body"), "body", notes, now, trusted);
        parseActions(card.actions, value(json, "actions"), notes);
        normalizeLive(card, notes, now);
        return new ParseResult(true, null, card, notes.list());
    }

    /**
     * Applies an update_card payload in place. Top-level fields replace; {@code body}
     * replaces all blocks; {@code patch} shallow-merges into blocks by id (row arrays
     * replace wholesale, except that a checklist/list patch whose rows all match existing
     * rows by text updates just those rows); {@code remove} deletes; {@code append} adds.
     */
    public static UpdateResult applyUpdate(GenCard card, String raw, long now) {
        JSONObject json;
        try {
            json = parseArguments(raw);
        } catch (IllegalArgumentException error) {
            return new UpdateResult(false, error.getMessage(), List.of(), List.of(), false, false);
        }
        return applyUpdate(card, json, now);
    }

    public static UpdateResult applyUpdate(GenCard card, JSONObject json, long now) {
        Notes notes = new Notes();
        ArrayList<String> changed = new ArrayList<>();
        boolean visible = false;

        if (json.has("title")) {
            String title = clean(value(json, "title"), GenSchema.TITLE, notes, "title");
            if (title != null && !title.equals(card.title)) {
                card.title = title;
                changed.add("title");
                visible = true;
            }
        }
        if (json.has("subtitle")) {
            card.subtitle = clean(value(json, "subtitle"), GenSchema.SUBTITLE, notes, "subtitle");
            changed.add("subtitle");
            visible = true;
        }
        if (json.has("eyebrow")) {
            card.eyebrow = clean(value(json, "eyebrow"), GenSchema.EYEBROW, notes, "eyebrow");
            changed.add("eyebrow");
            visible = true;
        }
        if (json.has("accent")) {
            card.accent = accent(value(json, "accent"), notes);
            changed.add("accent");
            visible = true;
        }
        if (json.has("icon")) {
            card.icon = icon(value(json, "icon"), notes, "icon");
            changed.add("icon");
            visible = true;
        }
        if (json.has("size")) {
            card.size = "compact".equals(value(json, "size")) ? GenCard.Size.COMPACT : GenCard.Size.CARD;
            card.presentation = 0;
            changed.add("size");
            visible = true;
        }
        if (json.has("ttlSec")) {
            card.ttlMs = ttl(value(json, "ttlSec"), notes) * 1000L;
            changed.add("ttlSec");
        }
        if (json.has("pinned")) {
            card.pinned = bool(value(json, "pinned"));
            changed.add("pinned");
            visible = true;
        }
        if (json.has("actions")) {
            card.actions.clear();
            parseActions(card.actions, value(json, "actions"), notes);
            changed.add("actions");
            visible = true;
        }

        boolean bodyEdit = json.has("body") || json.has("patch") || json.has("append") || json.has("remove");
        boolean liveIgnored = false;
        if (bodyEdit && card.live != null && card.live.type != LiveBinding.Type.TIMER) {
            liveIgnored = true;
            notes.add("body edits ignored: live card updates itself");
        } else if (bodyEdit) {
            GenBlock runningTimer = card.isTimer() ? card.timerBlock() : null;
            if (json.has("body")) {
                ArrayList<GenBlock> blocks = new ArrayList<>();
                parseBody(blocks, value(json, "body"), "body", notes, now, false);
                card.body.clear();
                card.body.addAll(blocks);
                changed.add("body");
            }
            JSONArray patches = array(value(json, "patch"));
            if (patches != null) {
                for (int index = 0; index < patches.length(); index++) {
                    JSONObject patch = object(patches.opt(index));
                    if (patch == null) continue;
                    String blockId = clean(value(patch, "id"), GenSchema.BLOCK_ID, null, null);
                    GenBlock block = card.findBlock(blockId);
                    if (block == null) {
                        notes.add("patch '" + blockId + "' not found");
                        continue;
                    }
                    GenBlock merged = merge(block, patch, "patch[" + index + "]", notes, now);
                    if (merged == null) {
                        notes.add("patch '" + blockId + "' dropped (invalid)");
                        continue;
                    }
                    card.body.set(card.body.indexOf(block), merged);
                    changed.add("patch:" + blockId);
                }
            }
            JSONArray remove = array(value(json, "remove"));
            if (remove != null) {
                for (int index = 0; index < remove.length(); index++) {
                    String blockId = clean(remove.opt(index), GenSchema.BLOCK_ID, null, null);
                    GenBlock block = card.findBlock(blockId);
                    if (block != null) {
                        card.body.remove(block);
                        changed.add("remove:" + blockId);
                    } else if (blockId != null) {
                        notes.add("remove '" + blockId + "' not found");
                    }
                }
            }
            Object append = value(json, "append");
            if (append != null) {
                ArrayList<GenBlock> extra = new ArrayList<>();
                parseBody(extra, append, "append", notes, now, false);
                int room = GenSchema.BODY - card.body.size();
                if (extra.size() > room) {
                    notes.add("body " + (card.body.size() + extra.size()) + "->" + GenSchema.BODY);
                }
                for (int index = 0; index < extra.size() && index < Math.max(0, room); index++) {
                    card.body.add(extra.get(index));
                }
                changed.add("append:" + Math.min(extra.size(), Math.max(0, room)));
            }
            if (runningTimer != null) keepTimerClock(card, runningTimer, notes);
            visible = true;
            normalizeLive(card, notes, now);
        }
        return new UpdateResult(true, null, changed, notes.list(), visible, liveIgnored);
    }

    public static DismissRequest parseDismiss(String raw) {
        JSONObject json;
        try {
            json = parseArguments(raw);
        } catch (IllegalArgumentException error) {
            return new DismissRequest(false, error.getMessage(), null, false, false);
        }
        String id = normalizeId(value(json, "id"));
        boolean all = bool(value(json, "all"));
        boolean timers = bool(value(json, "includeTimers"));
        if (id.isEmpty() && !all) {
            return new DismissRequest(false, "Give the card id, or all=true to clear the screen.",
                    null, false, false);
        }
        return new DismissRequest(true, null, id.isEmpty() ? null : id, all, timers);
    }

    // ------------------------------------------------------------------ card parts

    static void parseBody(List<GenBlock> out, Object raw, String path, Notes notes, long now,
                          boolean trusted) {
        JSONArray blocks = array(raw);
        if (blocks == null) {
            JSONObject single = object(raw);
            if (single == null) return;
            blocks = new JSONArray().put(single);
        }
        if (blocks.length() > GenSchema.BODY) {
            notes.add(path + " " + blocks.length() + "->" + GenSchema.BODY);
        }
        for (int index = 0; index < blocks.length() && out.size() < GenSchema.BODY; index++) {
            GenBlock block = parseBlock(object(blocks.opt(index)), path + "[" + index + "]",
                    notes, now, trusted);
            if (block != null) out.add(block);
        }
    }

    static GenBlock parseBlock(JSONObject json, String path, Notes notes, long now, boolean trusted) {
        if (json == null) {
            notes.add(path + " dropped (not an object)");
            return null;
        }
        Object rawType = value(json, "type");
        GenBlock.Type type = GenBlock.Type.of(rawType);
        if (type == null) {
            notes.add(path + " dropped (unknown type" + (rawType instanceof String s ? " '"
                    + clip(s, 16) + "'" : "") + ")");
            return null;
        }
        GenBlock block = new GenBlock(type);
        block.id = clean(value(json, "id"), GenSchema.BLOCK_ID, null, null);
        switch (type) {
            case TEXT -> {
                block.text = clean(value(json, "text"), GenSchema.TEXT, notes, path + ".text");
                if (block.text == null) {
                    notes.add(path + " dropped (empty text)");
                    return null;
                }
                int style = GenSchema.indexOf(GenSchema.TEXT_STYLES, value(json, "style"));
                block.style = style < 0 ? GenSchema.STYLE_BODY : style;
            }
            case STAT -> {
                String statValue = clean(value(json, "value"), GenSchema.VALUE, notes, path + ".value");
                block.value = statValue == null ? "–" : statValue;
                block.label = clean(value(json, "label"), GenSchema.LABEL, notes, path + ".label");
                block.delta = clean(value(json, "delta"), GenSchema.DELTA, notes, path + ".delta");
                block.trend = GenSchema.indexOf(GenSchema.TRENDS, value(json, "trend"));
            }
            case KV -> {
                if (!parsePairs(block, value(json, "pairs"), path, notes)) {
                    notes.add(path + " dropped (no pairs)");
                    return null;
                }
                block.columns = number(value(json, "columns"), 1) == 2 ? 2 : 1;
            }
            case LIST, CHECKLIST -> {
                block.items = rows(value(json, "items"), type == GenBlock.Type.CHECKLIST, path, notes);
                if (block.items.length == 0) {
                    notes.add(path + " dropped (empty " + type.wire + ")");
                    return null;
                }
                if (trusted) block.rowSay = clean(value(json, "rowSay"), GenSchema.SAY, null, null);
            }
            case PROGRESS -> {
                Object progress = value(json, "progress");
                if (progress == null) progress = value(json, "value");
                if (bool(value(json, "indeterminate")) || !(progress instanceof Number
                        || progress instanceof String)) {
                    block.progress = -1f;
                } else {
                    double fraction = number(progress, Double.NaN);
                    if (Double.isNaN(fraction)) block.progress = -1f;
                    else {
                        // Accept whole 2..100 percentages from the model as a courtesy.
                        if (fraction >= 2.0 && fraction <= 100.0) fraction = fraction / 100.0;
                        block.progress = (float) Math.max(0.0, Math.min(1.0, fraction));
                    }
                }
                block.label = clean(value(json, "label"), GenSchema.LABEL, notes, path + ".label");
                block.steps = strings(value(json, "steps"), GenSchema.STEPS, GenSchema.STEP_LABEL,
                        path + ".steps", notes);
                int step = (int) number(value(json, "step"), -1);
                block.step = block.steps.length == 0 ? -1
                        : Math.max(-1, Math.min(block.steps.length - 1, step));
            }
            case TIMER -> {
                block.label = clean(value(json, "label"), GenSchema.LABEL, notes, path + ".label");
                if (trusted && json.has("endsAt")) {
                    block.endsAt = (long) number(value(json, "endsAt"), now);
                    block.totalMs = (long) number(value(json, "totalMs"), 0);
                    block.paused = bool(value(json, "paused"));
                    block.pausedRemainingMs = (long) number(value(json, "pausedRemainingMs"), 0);
                    block.done = bool(value(json, "done"));
                } else {
                    double duration = number(value(json, "durationSec"), Double.NaN);
                    if (!Double.isNaN(duration)) {
                        GenTimers.start(block, now, durationSeconds(duration, notes, path) * 1000L);
                    } else {
                        block.totalMs = 0L; // filled from live.durationSec or dropped
                    }
                }
            }
            case BARS -> {
                JSONArray values = array(value(json, "values"));
                int count = values == null ? 0 : values.length();
                if (count > GenSchema.BARS) notes.add(path + ".values " + count + "->" + GenSchema.BARS);
                float[] bars = new float[Math.min(count, GenSchema.BARS)];
                int n = 0;
                for (int index = 0; index < bars.length; index++) {
                    double bar = number(values.opt(index), Double.NaN);
                    if (!Double.isNaN(bar) && !Double.isInfinite(bar)) bars[n++] = (float) Math.max(0.0, bar);
                }
                if (n == 0) {
                    notes.add(path + " dropped (no values)");
                    return null;
                }
                block.bars = n == bars.length ? bars : java.util.Arrays.copyOf(bars, n);
                block.barLabels = strings(value(json, "labels"), GenSchema.BARS, GenSchema.BAR_LABEL,
                        path + ".labels", notes);
                block.unit = clean(value(json, "unit"), GenSchema.UNIT, notes, path + ".unit");
                int highlight = (int) number(value(json, "highlight"), -1);
                block.highlight = highlight >= 0 && highlight < n ? highlight : -1;
            }
            case WEATHER -> {
                block.temp = clean(value(json, "temp"), GenSchema.TEMP, notes, path + ".temp");
                int condition = GenSchema.indexOf(GenSchema.CONDITIONS, value(json, "condition"));
                block.condition = condition < 0 ? GenSchema.CONDITION_CLOUDY : condition;
                block.hi = clean(value(json, "hi"), GenSchema.TEMP, notes, path + ".hi");
                block.lo = clean(value(json, "lo"), GenSchema.TEMP, notes, path + ".lo");
                block.place = clean(value(json, "place"), GenSchema.PLACE, notes, path + ".place");
                JSONArray hours = array(value(json, "hours"));
                int count = hours == null ? 0 : hours.length();
                if (count > GenSchema.HOURS) notes.add(path + ".hours " + count + "->" + GenSchema.HOURS);
                ArrayList<JSONObject> kept = new ArrayList<>();
                for (int index = 0; index < count && kept.size() < GenSchema.HOURS; index++) {
                    JSONObject hour = object(hours.opt(index));
                    if (hour != null) kept.add(hour);
                }
                block.hourT = new String[kept.size()];
                block.hourTemp = new String[kept.size()];
                block.hourCondition = new int[kept.size()];
                for (int index = 0; index < kept.size(); index++) {
                    JSONObject hour = kept.get(index);
                    block.hourT[index] = orEmpty(clean(value(hour, "t"), GenSchema.HOUR_T, null, null));
                    block.hourTemp[index] = orEmpty(clean(value(hour, "temp"), GenSchema.HOUR_TEMP, null, null));
                    int hourCondition = GenSchema.indexOf(GenSchema.CONDITIONS, value(hour, "condition"));
                    block.hourCondition[index] = hourCondition < 0 ? GenSchema.CONDITION_CLOUDY : hourCondition;
                }
                if (block.temp == null && kept.isEmpty()) {
                    notes.add(path + " dropped (no temp)");
                    return null;
                }
            }
            case DIVIDER -> { }
        }
        return block;
    }

    private static boolean parsePairs(GenBlock block, Object raw, String path, Notes notes) {
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<String> vals = new ArrayList<>();
        JSONArray pairs = array(raw);
        int total = 0;
        if (pairs != null) {
            total = pairs.length();
            for (int index = 0; index < pairs.length() && keys.size() < GenSchema.PAIRS; index++) {
                JSONObject pair = object(pairs.opt(index));
                if (pair == null) continue;
                String key = clean(value(pair, "k"), GenSchema.KV_KEY, null, null);
                String val = clean(value(pair, "v"), GenSchema.KV_VALUE, null, null);
                if (key == null && val == null) continue;
                keys.add(orEmpty(key));
                vals.add(orEmpty(val));
            }
        } else {
            JSONObject map = object(raw);
            if (map != null) {
                Iterator<String> names = map.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    total++;
                    if (keys.size() >= GenSchema.PAIRS) continue;
                    String key = clean(name, GenSchema.KV_KEY, null, null);
                    String val = clean(value(map, name), GenSchema.KV_VALUE, null, null);
                    if (key == null && val == null) continue;
                    keys.add(orEmpty(key));
                    vals.add(orEmpty(val));
                }
            }
        }
        if (total > GenSchema.PAIRS) notes.add(path + ".pairs " + total + "->" + GenSchema.PAIRS);
        block.keys = keys.toArray(new String[0]);
        block.vals = vals.toArray(new String[0]);
        return !keys.isEmpty();
    }

    private static GenRow[] rows(Object raw, boolean checklist, String path, Notes notes) {
        JSONArray items = array(raw);
        if (items == null) return new GenRow[0];
        if (items.length() > GenSchema.ITEMS) {
            notes.add(path + ".items " + items.length() + "->" + GenSchema.ITEMS);
        }
        ArrayList<GenRow> rows = new ArrayList<>();
        int shortened = 0;
        for (int index = 0; index < items.length() && rows.size() < GenSchema.ITEMS; index++) {
            Object item = items.opt(index);
            GenRow row = new GenRow();
            JSONObject json = object(item);
            if (json == null) {
                // Accept bare strings: ["Milk", "Eggs"].
                String text = clean(item, checklist ? GenSchema.CHECK_TEXT : GenSchema.ROW_TITLE, null, null);
                if (text == null) continue;
                row.title = text;
                rows.add(row);
                continue;
            }
            Object primary = checklist ? value(json, "text") : value(json, "title");
            if (primary == null || (primary instanceof String s && s.isBlank())) {
                primary = checklist ? value(json, "title") : value(json, "text");
            }
            int limit = checklist ? GenSchema.CHECK_TEXT : GenSchema.ROW_TITLE;
            String title = clean(primary, limit, null, null);
            if (title == null) continue;
            if (title.endsWith(ELLIPSIS)) shortened++;
            row.title = title;
            row.checked = bool(value(json, "checked"));
            if (!checklist) {
                row.detail = clean(value(json, "detail"), GenSchema.ROW_DETAIL, null, null);
                row.trailing = clean(value(json, "trailing"), GenSchema.ROW_TRAILING, null, null);
                row.status = GenSchema.indexOf(GenSchema.STATUSES, value(json, "status"));
                row.icon = GenSchema.indexOf(GenSchema.ICONS, value(json, "icon"));
            }
            row.ref = clean(value(json, "ref"), GenSchema.REF, null, null);
            rows.add(row);
        }
        if (shortened > 0) notes.add(path + ".items: " + shortened + " long rows shortened");
        return rows.toArray(new GenRow[0]);
    }

    static void parseActions(List<GenAction> out, Object raw, Notes notes) {
        JSONArray actions = array(raw);
        if (actions == null) return;
        if (actions.length() > GenSchema.ACTIONS) {
            notes.add("actions " + actions.length() + "->" + GenSchema.ACTIONS);
        }
        for (int index = 0; index < actions.length() && out.size() < GenSchema.ACTIONS; index++) {
            JSONObject json = object(actions.opt(index));
            String path = "actions[" + index + "]";
            if (json == null) continue;
            String label = clean(value(json, "label"), GenSchema.ACTION_LABEL, notes, path + ".label");
            if (label == null) {
                notes.add(path + " dropped (no label)");
                continue;
            }
            Object styleRaw = value(json, "style");
            GenAction.Style style = "primary".equals(styleRaw) ? GenAction.Style.PRIMARY
                    : "danger".equals(styleRaw) ? GenAction.Style.DANGER : GenAction.Style.SECONDARY;
            GenAction action = null;
            int effects = 0;
            String say = clean(value(json, "say"), GenSchema.SAY, notes, path + ".say");
            if (say != null) {
                action = new GenAction(label, style, GenAction.Kind.SAY, say);
                effects++;
            }
            int open = GenSchema.indexOf(GenSchema.OPEN_PAGES, value(json, "open"));
            if (open >= 0) {
                if (action == null) action = new GenAction(label, style, GenAction.Kind.OPEN, GenSchema.OPEN_PAGES[open]);
                effects++;
            }
            int timer = GenSchema.indexOf(GenSchema.TIMER_OPS, value(json, "timer"));
            if (timer >= 0) {
                if (action == null) action = new GenAction(label, style, GenAction.Kind.TIMER, GenSchema.TIMER_OPS[timer]);
                effects++;
            }
            if (bool(value(json, "dismiss"))) {
                if (action == null) action = new GenAction(label, style, GenAction.Kind.DISMISS, null);
                effects++;
            }
            if (action == null) {
                notes.add(path + " dropped (needs say, open, timer or dismiss)");
                continue;
            }
            if (effects > 1) notes.add(path + ": kept " + action.kind.name().toLowerCase(Locale.ROOT) + " only");
            out.add(action);
        }
    }

    private static LiveBinding live(Object raw, Notes notes) {
        JSONObject json = object(raw);
        if (json == null) return null;
        LiveBinding.Type type = LiveBinding.Type.of(value(json, "type"));
        if (type == null) {
            notes.add("live dropped (unknown type)");
            return null;
        }
        int duration = 0;
        double durationRaw = number(value(json, "durationSec"), Double.NaN);
        if (!Double.isNaN(durationRaw)) duration = durationSeconds(durationRaw, notes, "live");
        String runId = clean(value(json, "runId"), GenSchema.REF, null, null);
        String threadId = clean(value(json, "threadId"), GenSchema.REF, null, null);
        if (type == LiveBinding.Type.BACKGROUND_RUN && runId == null) {
            notes.add("live dropped (background-run needs runId)");
            return null;
        }
        if (type == LiveBinding.Type.T3_THREAD && threadId == null) {
            notes.add("live dropped (t3-thread needs threadId)");
            return null;
        }
        return new LiveBinding(type, duration, runId, threadId);
    }

    /**
     * Timer consistency: a live timer always owns exactly one timer block (created from
     * live.durationSec when absent) and a timer block always implies a live timer, so the
     * done tone and +1m actions work everywhere. Timer blocks with no duration are dropped.
     */
    static void normalizeLive(GenCard card, Notes notes, long now) {
        GenBlock timer = null;
        for (int index = 0; index < card.body.size(); index++) {
            GenBlock block = card.body.get(index);
            if (block.type != GenBlock.Type.TIMER) continue;
            if (block.totalMs <= 0L && block.endsAt == 0L) {
                if (card.live != null && card.live.type == LiveBinding.Type.TIMER && card.live.durationSec > 0) {
                    GenTimers.start(block, now, card.live.durationSec * 1000L);
                } else {
                    card.body.remove(index--);
                    notes.add("timer dropped (needs durationSec)");
                    continue;
                }
            }
            if (timer == null) timer = block;
            else {
                card.body.remove(index--);
                notes.add("extra timer dropped (one per card)");
            }
        }
        if (card.live != null && card.live.type == LiveBinding.Type.TIMER && timer == null) {
            if (card.live.durationSec > 0) {
                GenBlock block = new GenBlock(GenBlock.Type.TIMER);
                block.id = "timer";
                GenTimers.start(block, now, card.live.durationSec * 1000L);
                if (card.body.size() >= GenSchema.BODY) card.body.remove(card.body.size() - 1);
                card.body.add(0, block);
            } else {
                card.live = null;
                notes.add("live timer dropped (needs durationSec)");
            }
        } else if (timer != null && card.live == null) {
            card.live = new LiveBinding(LiveBinding.Type.TIMER,
                    (int) Math.min(GenSchema.DURATION_MAX_SEC, timer.totalMs / 1000L), null, null);
        }
        if (card.live != null && card.live.type == LiveBinding.Type.TIMER && card.icon < 0) {
            card.icon = GenSchema.ICON_TIMER;
        }
    }

    /**
     * Body edits on a live timer card (body replace, remove, a timer block without a new
     * durationSec) keep the running countdown instead of restarting it from the full duration.
     */
    private static void keepTimerClock(GenCard card, GenBlock previous, Notes notes) {
        GenBlock timer = card.timerBlock();
        if (timer == null) {
            if (card.body.size() >= GenSchema.BODY) card.body.remove(card.body.size() - 1);
            card.body.add(0, previous);
            notes.add("timer kept (live timer card)");
            return;
        }
        if (timer != previous && timer.totalMs <= 0L && timer.endsAt == 0L) {
            timer.endsAt = previous.endsAt;
            timer.totalMs = previous.totalMs;
            timer.paused = previous.paused;
            timer.pausedRemainingMs = previous.pausedRemainingMs;
            timer.done = previous.done;
        }
    }

    private static GenBlock merge(GenBlock block, JSONObject patch, String path, Notes notes, long now) {
        // Smart row merge: ticking a few checklist rows must not delete the others.
        if ((block.type == GenBlock.Type.CHECKLIST || block.type == GenBlock.Type.LIST)
                && patch.has("items") && !patch.has("type") && block.items != null) {
            JSONArray rows = array(value(patch, "items"));
            GenRow[] mergedRows = rows != null && rows.length() > 0 && rows.length() < block.items.length
                    ? mergeRows(block, rows) : null;
            if (mergedRows != null) {
                GenRow[] original = block.items;
                block.items = mergedRows;
                JSONObject withRows = GenCardCodec.blockToJson(block);
                block.items = original;
                return mergeInto(withRows, block, patch, true, path, notes, now);
            }
        }
        return mergeInto(GenCardCodec.blockToJson(block), block, patch, false, path, notes, now);
    }

    private static GenBlock mergeInto(JSONObject base, GenBlock block, JSONObject patch, boolean rowsMerged,
                                      String path, Notes notes, long now) {
        for (Iterator<String> keys = patch.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if ("id".equals(key) || (rowsMerged && "items".equals(key))) continue;
            try {
                base.put(key, patch.opt(key));
            } catch (Exception ignored) { }
        }
        // A new durationSec restarts the timer; otherwise keep its running clock.
        if (block.type == GenBlock.Type.TIMER && patch.has("durationSec")) base.remove("endsAt");
        // A numeric progress patch ends an indeterminate bar (the base still says indeterminate).
        if (block.type == GenBlock.Type.PROGRESS && !patch.has("indeterminate")
                && (patch.has("progress") || patch.has("value"))) {
            base.remove("indeterminate");
            if (!patch.has("progress")) base.remove("progress");
        }
        return parseBlock(base, path, notes, now, true);
    }

    /** Returns a copy of the rows with patched rows (matched by text) updated, or null if any is unknown. */
    private static GenRow[] mergeRows(GenBlock block, JSONArray patchRows) {
        boolean checklist = block.type == GenBlock.Type.CHECKLIST;
        int[] targets = new int[patchRows.length()];
        for (int index = 0; index < patchRows.length(); index++) {
            JSONObject row = object(patchRows.opt(index));
            Object text = row == null ? patchRows.opt(index)
                    : (checklist ? value(row, "text") : value(row, "title"));
            if (text == null && row != null) text = checklist ? value(row, "title") : value(row, "text");
            String wanted = clean(text, GenSchema.CHECK_TEXT, null, null);
            int match = -1;
            for (int existing = 0; wanted != null && existing < block.items.length; existing++) {
                if (block.items[existing].title.equalsIgnoreCase(wanted)) {
                    match = existing;
                    break;
                }
            }
            if (match < 0) return null;
            targets[index] = match;
        }
        GenRow[] next = new GenRow[block.items.length];
        for (int index = 0; index < next.length; index++) next[index] = block.items[index].copy();
        for (int index = 0; index < patchRows.length(); index++) {
            JSONObject row = object(patchRows.opt(index));
            if (row == null) continue;
            GenRow target = next[targets[index]];
            if (row.has("checked")) target.checked = bool(value(row, "checked"));
            if (!checklist) {
                if (row.has("detail")) target.detail = clean(value(row, "detail"), GenSchema.ROW_DETAIL, null, null);
                if (row.has("trailing")) target.trailing = clean(value(row, "trailing"), GenSchema.ROW_TRAILING, null, null);
                if (row.has("status")) target.status = GenSchema.indexOf(GenSchema.STATUSES, value(row, "status"));
                if (row.has("icon")) target.icon = GenSchema.indexOf(GenSchema.ICONS, value(row, "icon"));
            }
        }
        return next;
    }

    // ------------------------------------------------------------------ scalars

    /** Lowercase, keep only [a-z0-9-_], at most 40 chars. */
    public static String normalizeId(Object value) {
        String text = scalar(value);
        if (text == null) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), GenSchema.ID));
        String lower = text.toLowerCase(Locale.ROOT);
        for (int index = 0; index < lower.length() && out.length() < GenSchema.ID; index++) {
            char ch = lower.charAt(index);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_') out.append(ch);
        }
        return out.toString();
    }

    /**
     * Collapses whitespace and truncates to {@code limit} chars with an ellipsis. Returns
     * null for missing/blank values. Adds a note when {@code path} is given and it truncates.
     */
    static String clean(Object value, int limit, Notes notes, String path) {
        String text = scalar(value);
        if (text == null) return null;
        String collapsed = collapse(text);
        if (collapsed.isEmpty()) return null;
        if (collapsed.length() <= limit) return collapsed;
        if (notes != null && path != null) notes.add(path + " " + collapsed.length() + "->" + limit + " chars");
        return clip(collapsed, limit);
    }

    static String clip(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Math.max(0, limit - 1);
        // Never split a surrogate pair.
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    static String collapse(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                space = out.length() > 0;
            } else {
                if (space) out.append(' ');
                space = false;
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String scalar(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof String text) return text;
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (number == Math.rint(number) && Math.abs(number) < 1e15) return Long.toString((long) number);
            return Double.toString(number);
        }
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return null;
    }

    static Object value(JSONObject json, String key) {
        if (json == null) return null;
        Object value = json.opt(key);
        return value == JSONObject.NULL ? null : value;
    }

    static JSONObject object(Object value) {
        return value instanceof JSONObject json ? json : null;
    }

    static JSONArray array(Object value) {
        return value instanceof JSONArray json ? json : null;
    }

    static boolean bool(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text) return "true".equalsIgnoreCase(text.trim());
        if (value instanceof Number number) return number.intValue() != 0;
        return false;
    }

    static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String[] strings(Object raw, int maxCount, int maxLength, String path, Notes notes) {
        JSONArray array = array(raw);
        if (array == null) return new String[0];
        if (array.length() > maxCount) notes.add(path + " " + array.length() + "->" + maxCount);
        int count = Math.min(maxCount, array.length());
        String[] out = new String[count];
        for (int index = 0; index < count; index++) {
            out[index] = orEmpty(clean(array.opt(index), maxLength, null, null));
        }
        return out;
    }

    private static int icon(Object raw, Notes notes, String path) {
        if (raw == null) return -1;
        int icon = GenSchema.indexOf(GenSchema.ICONS, raw);
        if (icon < 0) notes.add(path + " unknown, none used");
        return icon;
    }

    private static GenCard.Accent accent(Object raw, Notes notes) {
        if (raw == null) return GenCard.Accent.BLUE;
        GenCard.Accent accent = GenCard.Accent.of(raw);
        if (accent == null) {
            notes.add("accent unknown, blue used");
            return GenCard.Accent.BLUE;
        }
        return accent;
    }

    private static long ttl(Object raw, Notes notes) {
        if (raw == null) return GenSchema.TTL_DEFAULT_SEC;
        double value = number(raw, Double.NaN);
        if (Double.isNaN(value)) return GenSchema.TTL_DEFAULT_SEC;
        long seconds = Math.round(value);
        long clamped = Math.max(GenSchema.TTL_MIN_SEC, Math.min(GenSchema.TTL_MAX_SEC, seconds));
        if (clamped != seconds) notes.add("ttlSec " + seconds + "->" + clamped);
        return clamped;
    }

    private static int durationSeconds(double raw, Notes notes, String path) {
        long seconds = Math.round(raw);
        long clamped = Math.max(GenSchema.DURATION_MIN_SEC, Math.min(GenSchema.DURATION_MAX_SEC, seconds));
        if (clamped != seconds) notes.add(path + ".durationSec " + seconds + "->" + clamped);
        return (int) clamped;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
