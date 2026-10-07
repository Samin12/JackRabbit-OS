package com.resonolabs.feature.genui;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JSON form of normalized cards, used for persistence ({@code files/genui/cards.json}) and
 * for update_card block merges. Persisted times are wall-clock ms; in memory they use the
 * elapsedRealtime base, so {@code offset = wallNow - elapsedNow} converts between them and
 * timers survive a reboot correctly.
 */
public final class GenCardCodec {
    private GenCardCodec() {}

    public static JSONObject blockToJson(GenBlock block) {
        return blockToJson(block, 0L);
    }

    static JSONObject blockToJson(GenBlock block, long offset) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", block.type.wire);
            if (block.id != null) json.put("id", block.id);
            switch (block.type) {
                case TEXT -> {
                    json.put("text", block.text);
                    json.put("style", GenSchema.nameOf(GenSchema.TEXT_STYLES, block.style));
                }
                case STAT -> {
                    json.put("value", block.value);
                    putOpt(json, "label", block.label);
                    putOpt(json, "delta", block.delta);
                    putOpt(json, "trend", GenSchema.nameOf(GenSchema.TRENDS, block.trend));
                }
                case KV -> {
                    JSONArray pairs = new JSONArray();
                    for (int index = 0; index < block.keys.length; index++) {
                        pairs.put(new JSONObject().put("k", block.keys[index]).put("v", block.vals[index]));
                    }
                    json.put("pairs", pairs);
                    json.put("columns", block.columns);
                }
                case LIST, CHECKLIST -> {
                    boolean checklist = block.type == GenBlock.Type.CHECKLIST;
                    JSONArray items = new JSONArray();
                    for (GenRow row : block.items) {
                        JSONObject item = new JSONObject();
                        item.put(checklist ? "text" : "title", row.title);
                        if (checklist || row.checked) item.put("checked", row.checked);
                        putOpt(item, "detail", row.detail);
                        putOpt(item, "trailing", row.trailing);
                        putOpt(item, "status", GenSchema.nameOf(GenSchema.STATUSES, row.status));
                        putOpt(item, "icon", GenSchema.nameOf(GenSchema.ICONS, row.icon));
                        putOpt(item, "ref", row.ref);
                        items.put(item);
                    }
                    json.put("items", items);
                    putOpt(json, "rowSay", block.rowSay);
                }
                case PROGRESS -> {
                    if (block.indeterminate()) json.put("indeterminate", true);
                    else json.put("progress", (double) block.progress);
                    putOpt(json, "label", block.label);
                    if (block.steps != null && block.steps.length > 0) {
                        json.put("steps", strings(block.steps));
                        if (block.step >= 0) json.put("step", block.step);
                    }
                }
                case TIMER -> {
                    putOpt(json, "label", block.label);
                    json.put("endsAt", block.endsAt + offset);
                    json.put("totalMs", block.totalMs);
                    json.put("paused", block.paused);
                    json.put("pausedRemainingMs", block.pausedRemainingMs);
                    json.put("done", block.done);
                }
                case BARS -> {
                    JSONArray values = new JSONArray();
                    for (float bar : block.bars) values.put((double) bar);
                    json.put("values", values);
                    if (block.barLabels != null && block.barLabels.length > 0) json.put("labels", strings(block.barLabels));
                    putOpt(json, "unit", block.unit);
                    if (block.highlight >= 0) json.put("highlight", block.highlight);
                }
                case WEATHER -> {
                    putOpt(json, "temp", block.temp);
                    json.put("condition", GenSchema.nameOf(GenSchema.CONDITIONS, block.condition));
                    putOpt(json, "hi", block.hi);
                    putOpt(json, "lo", block.lo);
                    putOpt(json, "place", block.place);
                    if (block.hourT != null && block.hourT.length > 0) {
                        JSONArray hours = new JSONArray();
                        for (int index = 0; index < block.hourT.length; index++) {
                            hours.put(new JSONObject().put("t", block.hourT[index])
                                    .put("temp", block.hourTemp[index])
                                    .put("condition", GenSchema.nameOf(GenSchema.CONDITIONS, block.hourCondition[index])));
                        }
                        json.put("hours", hours);
                    }
                }
                case DIVIDER -> { }
            }
        } catch (Exception ignored) {
            // org.json only throws for NaN/Infinity, which the parser never produces.
        }
        return json;
    }

    public static JSONObject actionToJson(GenAction action) {
        JSONObject json = new JSONObject();
        try {
            json.put("label", action.label);
            if (action.style != GenAction.Style.SECONDARY) {
                json.put("style", action.style.name().toLowerCase(java.util.Locale.ROOT));
            }
            switch (action.kind) {
                case SAY -> json.put("say", action.arg);
                case OPEN -> json.put("open", action.arg);
                case TIMER -> json.put("timer", action.arg);
                case DISMISS -> json.put("dismiss", true);
            }
        } catch (Exception ignored) { }
        return json;
    }

    /** Full card JSON; {@code offset} = wallNow - elapsedNow (0 keeps elapsed times). */
    public static JSONObject cardToJson(GenCard card, long offset) {
        JSONObject json = new JSONObject();
        try {
            json.put("id", card.id);
            json.put("title", card.title);
            putOpt(json, "subtitle", card.subtitle);
            putOpt(json, "eyebrow", card.eyebrow);
            json.put("size", card.size == GenCard.Size.COMPACT ? "compact" : "card");
            putOpt(json, "icon", GenSchema.nameOf(GenSchema.ICONS, card.icon));
            json.put("accent", card.accent.wire());
            json.put("pinned", card.pinned);
            json.put("ttlSec", card.ttlMs / 1000L);
            JSONArray body = new JSONArray();
            for (GenBlock block : card.body) body.put(blockToJson(block, offset));
            json.put("body", body);
            JSONArray actions = new JSONArray();
            for (GenAction action : card.actions) actions.put(actionToJson(action));
            json.put("actions", actions);
            if (card.live != null) {
                JSONObject live = new JSONObject().put("type", card.live.type.wire);
                if (card.live.durationSec > 0) live.put("durationSec", card.live.durationSec);
                putOpt(live, "runId", card.live.runId);
                putOpt(live, "threadId", card.live.threadId);
                json.put("live", live);
            }
            json.put("createdAt", card.createdAt + offset);
            json.put("updatedAt", card.updatedAt + offset);
            json.put("state", card.state.name().toLowerCase(java.util.Locale.ROOT));
            if (card.originSessionId != null) {
                json.put("origin", new JSONObject().put("sessionId", card.originSessionId));
            }
            putOpt(json, "liveTitle", card.liveTitle);
            putOpt(json, "liveSubtitle", card.liveSubtitle);
            putOpt(json, "liveTrailing", card.liveTrailing);
            putOpt(json, "liveNote", card.liveNote);
            if (card.liveStatus >= 0) json.put("liveStatus", GenSchema.nameOf(GenSchema.STATUSES, card.liveStatus));
            if (card.terminal) {
                json.put("terminal", true);
                json.put("terminalAt", card.terminalAt + offset);
            }
            if (card.retiredAt != 0L) json.put("retiredAt", card.retiredAt + offset);
        } catch (Exception ignored) { }
        return json;
    }

    /** Restores a persisted card; returns null if it no longer validates. */
    public static GenCard cardFromJson(JSONObject json, long offset, long now) {
        if (json == null) return null;
        JSONArray body = json.optJSONArray("body");
        if (body != null) {
            for (int index = 0; index < body.length(); index++) {
                JSONObject block = body.optJSONObject(index);
                if (block != null && "timer".equals(block.opt("type")) && block.has("endsAt")) {
                    try {
                        block.put("endsAt", block.optLong("endsAt") - offset);
                    } catch (Exception ignored) { }
                }
            }
        }
        GenCardParser.ParseResult parsed = GenCardParser.parseCard(json, now, true);
        if (!parsed.ok) return null;
        GenCard card = parsed.card;
        card.createdAt = json.optLong("createdAt", now + offset) - offset;
        card.updatedAt = json.optLong("updatedAt", now + offset) - offset;
        card.arrivedAt = 0L;
        String state = json.optString("state", "active");
        card.state = "done".equals(state) ? GenCard.State.DONE
                : "stale".equals(state) ? GenCard.State.STALE : GenCard.State.ACTIVE;
        JSONObject origin = json.optJSONObject("origin");
        if (origin != null) card.originSessionId = text(origin, "sessionId");
        card.liveTitle = text(json, "liveTitle");
        card.liveSubtitle = text(json, "liveSubtitle");
        card.liveTrailing = text(json, "liveTrailing");
        card.liveNote = text(json, "liveNote");
        card.liveStatus = GenSchema.indexOf(GenSchema.STATUSES, json.opt("liveStatus"));
        card.terminal = json.optBoolean("terminal", false);
        if (card.terminal) card.terminalAt = json.optLong("terminalAt", now + offset) - offset;
        if (json.has("retiredAt")) card.retiredAt = json.optLong("retiredAt") - offset;
        return card;
    }

    private static String text(JSONObject json, String key) {
        Object value = json.opt(key);
        return value instanceof String text && !text.isEmpty() ? text : null;
    }

    private static JSONArray strings(String[] values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static void putOpt(JSONObject json, String key, Object value) throws Exception {
        if (value != null) json.put(key, value);
    }
}
