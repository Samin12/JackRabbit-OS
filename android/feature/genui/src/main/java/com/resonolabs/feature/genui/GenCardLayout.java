package com.resonolabs.feature.genui;

import android.graphics.Paint;

import java.util.Locale;

/**
 * Everything the renderer needs to draw one card, computed once per
 * (card revision, mode, width, height budget): wrapped lines, ellipsized strings, y
 * positions, visible row counts, "+N more", and hit rects. All coordinates are relative to
 * the card's top-left. {@link GenCardRenderer} only iterates these prepared arrays.
 */
public final class GenCardLayout {
    public static final int MODE_CARD = 0;
    public static final int MODE_EXPANDED = 1;
    public static final int MODE_PILL = 2;
    /** A pill in a list (Cards > Live): no leading verb button, the whole row opens the card. */
    public static final int MODE_ROW = 3;

    static final float PAD_X = 20f;
    static final float PAD_TOP = 18f;
    static final float GAP = 12f;
    static final float PAD_BOTTOM = 18f;
    static final float ACTION_H = 48f;
    static final float ACTION_GAP = 10f;
    static final float ACTIONS_TOP_GAP = 14f;
    static final float MORE_H = 24f;
    static final float CLOSE_HIT = 52f;
    static final float RADIUS = 26f;
    public static final float PILL_HEIGHT = 68f;
    static final int CARD_TEXT_LINES = 3;
    static final int EXPANDED_TEXT_LINES = 12;

    /** Per block prepared geometry. */
    static final class Box {
        GenBlock block;
        float top;
        float height;
        // text
        String[] lines;
        float lineHeight;
        Paint textPaint;
        int textColor;
        // rows
        int rows;
        /** First visible row (live step lists keep their newest rows when trimmed). */
        int firstRow;
        /** Smallest row height (overflow fitting); rows with a detail line are taller. */
        float rowHeight;
        /** Per-row heights (all rows) and visible slot tops relative to {@link #top} (rows + 1). */
        float[] rowHeights;
        float[] rowTops;
        String[] rowTitle;
        String[] rowDetail;
        String[] rowTrailing;
        float[] rowTitleWidth;
        // stat
        float valueSize;
        float valueWidth;
        float valueBaseline;
        boolean deltaBelow;
        String delta;
        // kv
        String[] keys;
        String[] vals;
        float columnWidth;
        // progress
        String percent;
        String label;
        float barTop;
        String[] steps;
        // bars
        String barValue;
        float barValueWidth;
        int labelStep = 1;
        // weather
        String condition;
        String hiLo;
        String place;
        float tempWidth;
    }

    // ---- cache key ----
    GenCard card;
    int revision = Integer.MIN_VALUE;
    int mode = -1;
    float budget;
    boolean pillVerb;

    // ---- frame ----
    public float width;
    public float height;
    /** Natural (unclamped) height of the card content. */
    public float measuredHeight;

    // ---- header ----
    boolean eyebrowRow;
    String eyebrow = "";
    float eyebrowWidth;
    String tag = "";
    int tagColor;
    boolean tagLive;
    float eyebrowX;
    float headerCenterY;
    String title = "";
    float titleBaseline;
    String subtitle = "";
    float subtitleBaseline;
    public float closeLeft;
    public float closeTop;
    public float closeRight;
    public float closeBottom;
    float closeCx;
    float closeCy;
    boolean showClose = true;

    // ---- body ----
    Box[] boxes = new Box[0];
    int boxCount;
    float bodyTop;
    float bodyBottom;
    public float contentHeight;
    String more;
    float moreTop;
    public boolean overflow;

    // ---- actions ----
    int actionCount;
    final float[] actionLeft = new float[GenSchema.HOST_ACTIONS];
    final float[] actionRight = new float[GenSchema.HOST_ACTIONS];
    final String[] actionLabel = new String[GenSchema.HOST_ACTIONS];

    // ---- pill ----
    String verb;
    float verbWidth;
    float textLeft;
    float textWidth;
    float trailingWidth;
    boolean pillTimer;
    String pillTrailing;

    // ---- stale ----
    String staleText;
    long staleMinute = -1L;

    public boolean matches(GenCard card, int mode, float width, float budget) {
        return this.card == card && revision == card.revision && this.mode == mode
                && this.width == width && this.budget == budget;
    }

    /** Rebuilds when the card revision, mode or size budget changed. Returns true if rebuilt. */
    boolean ensure(GenFonts fonts, GenCard card, int mode, float width, float budget) {
        if (matches(card, mode, width, budget)) return false;
        this.card = card;
        this.revision = card.revision;
        this.mode = mode;
        this.width = width;
        this.budget = budget;
        staleMinute = -1L;
        if (mode == MODE_PILL || mode == MODE_ROW) buildPill(fonts);
        else buildCard(fonts);
        return true;
    }

    /** Recomputes "Updated 3m ago" at most once a minute. */
    String staleText(long now) {
        long minutes = Math.max(0L, (now - card.liveUpdatedAt) / 60_000L);
        if (card.liveUpdatedAt <= 0L) minutes = -2L;
        if (minutes != staleMinute) {
            staleMinute = minutes;
            staleText = minutes == -2L ? "Offline" : minutes == 0 ? "Offline • updated just now"
                    : "Offline • updated " + minutes + "m ago";
        }
        return staleText;
    }

    // ------------------------------------------------------------------ card

    private void buildCard(GenFonts fonts) {
        boolean expanded = mode == MODE_EXPANDED;
        float inner = width - 2f * PAD_X;
        buildEyebrow(fonts, inner);
        // header
        if (eyebrowRow) {
            headerCenterY = PAD_TOP + 8f;
            closeCy = headerCenterY;
            titleBaseline = 62f;
        } else {
            closeCy = 40f;
            headerCenterY = closeCy;
            titleBaseline = 47f;
        }
        closeCx = width - 26f;
        closeLeft = width - CLOSE_HIT;
        closeTop = 0f;
        closeRight = width;
        closeBottom = Math.max(CLOSE_HIT, closeCy + 22f);
        float titleRoom = eyebrowRow ? inner : inner - 34f;
        title = GenText.ellipsize(card.displayTitle(), fonts.title, titleRoom);
        String sub = card.displaySubtitle();
        if (card.liveNote != null && (sub == null || sub.isEmpty())) sub = card.liveNote;
        subtitle = sub == null ? "" : GenText.ellipsize(sub, fonts.subtitle, inner);
        float y;
        if (!subtitle.isEmpty() || card.state == GenCard.State.STALE) {
            subtitleBaseline = titleBaseline + 22f;
            y = subtitleBaseline + 6f;
        } else {
            subtitleBaseline = 0f;
            y = titleBaseline + 8f;
        }
        bodyTop = y;

        // actions occupy the bottom
        actionCount = Math.min(card.actions.size(), GenSchema.HOST_ACTIONS);
        float actionsBlock = actionCount > 0 ? ACTIONS_TOP_GAP + ACTION_H : 0f;
        float limit;
        if (expanded) limit = Float.MAX_VALUE;
        else limit = budget - PAD_BOTTOM - actionsBlock;

        if (boxes.length < card.body.size()) {
            Box[] grown = new Box[card.body.size()];
            System.arraycopy(boxes, 0, grown, 0, boxes.length);
            boxes = grown;
        }
        boxCount = 0;
        overflow = false;
        more = null;
        int hiddenRows = 0;
        int hiddenBlocks = 0;
        boolean olderHidden = false;
        for (int index = 0; index < card.body.size(); index++) {
            GenBlock block = card.body.get(index);
            Box box = boxes[boxCount] != null ? boxes[boxCount] : (boxes[boxCount] = new Box());
            float gap = boxCount == 0 ? 8f : GAP;
            measure(fonts, box, block, inner, expanded ? EXPANDED_TEXT_LINES : CARD_TEXT_LINES);
            if (y + gap + box.height <= limit) {
                box.top = y + gap;
                y = box.top + box.height;
                boxCount++;
                continue;
            }
            // Doesn't fit: show what we can, then "+N more · tap to expand".
            overflow = true;
            float room = limit - y - gap - MORE_H;
            if ((block.type == GenBlock.Type.LIST || block.type == GenBlock.Type.CHECKLIST)
                    && room >= box.rowHeight) {
                int total = box.rows;
                boolean tail = card.live != null && block.type == GenBlock.Type.LIST;
                int fit = 0;
                float used = 0f;
                while (fit < total) {
                    float next = box.rowHeights[tail ? total - 1 - fit : fit];
                    if (used + next > room) break;
                    used += next;
                    fit++;
                }
                hiddenRows += total - fit;
                if (fit > 0) {
                    // Live progress lists: the newest (active) steps matter most.
                    if (tail) olderHidden = true;
                    setVisibleRows(box, tail ? total - fit : 0, fit);
                    box.top = y + gap;
                    y = box.top + box.height;
                    boxCount++;
                }
            } else if (block.type == GenBlock.Type.TEXT && room >= box.lineHeight) {
                int fit = Math.min(box.rows, (int) (room / box.lineHeight));
                String[] all = box.lines;
                String[] kept = new String[fit];
                System.arraycopy(all, 0, kept, 0, fit);
                if (fit < all.length) {
                    kept[fit - 1] = GenText.ellipsize(all[fit - 1] + " " + all[fit], box.textPaint, inner);
                }
                box.lines = kept;
                box.rows = fit;
                box.height = fit * box.lineHeight;
                box.top = y + gap;
                y = box.top + box.height;
                boxCount++;
            } else {
                hiddenBlocks++;
            }
            for (int rest = index + 1; rest < card.body.size(); rest++) {
                GenBlock hidden = card.body.get(rest);
                if (hidden.type == GenBlock.Type.LIST || hidden.type == GenBlock.Type.CHECKLIST) {
                    hiddenRows += hidden.rowCount();
                } else if (hidden.type != GenBlock.Type.DIVIDER) {
                    hiddenBlocks++;
                }
            }
            break;
        }
        if (overflow) {
            int hidden = hiddenRows + hiddenBlocks;
            String what = olderHidden && hiddenBlocks == 0 ? " earlier" : " more";
            more = (hidden > 0 ? "+" + hidden + what : "More") + "  •  tap to expand";
            moreTop = y + 4f;
            y = moreTop + MORE_H - 4f;
        }
        float contentEnd = y;
        contentHeight = contentEnd - bodyTop;
        measuredHeight = contentEnd + actionsBlock + PAD_BOTTOM;

        if (expanded) {
            height = budget;
            bodyBottom = actionCount > 0 ? actionTop() - 10f : height - 14f;
        } else {
            height = Math.min(budget, Math.max(measuredHeight, 0f));
            bodyBottom = contentEnd;
        }
        layoutActions(fonts, inner);
    }

    private void buildEyebrow(GenFonts fonts, float inner) {
        String main = card.eyebrow == null ? "" : card.eyebrow.toUpperCase(Locale.ROOT);
        tag = "";
        tagLive = false;
        tagColor = card.accent.text;
        if (card.live != null) {
            if (card.isTimer()) {
                GenBlock timer = card.timerBlock();
                if (timer != null && timer.done) setTag("DONE", GenColors.SUCCESS);
                else if (timer != null && timer.paused) setTag("PAUSED", GenColors.AMBER);
                else {
                    setTag("LIVE", card.accent.text);
                    tagLive = true;
                }
            } else if (card.state == GenCard.State.STALE) {
                setTag("OFFLINE", GenColors.MUTED);
            } else if (card.livePaused) {
                setTag("PAUSED", GenColors.MUTED);
            } else if (card.terminal) {
                if (card.liveStatus == GenSchema.STATUS_ERROR) setTag("FAILED", GenColors.RED);
                else if (card.liveStatus == GenSchema.STATUS_WARN) setTag("STOPPED", GenColors.AMBER);
                else setTag("DONE", GenColors.SUCCESS);
            } else if (card.liveNote != null) {
                setTag("WAITING", GenColors.MUTED);
            } else {
                setTag("LIVE", card.accent.text);
                tagLive = true;
            }
        } else if (card.pinned) {
            setTag("PINNED", GenColors.MUTED);
        }
        eyebrowRow = !main.isEmpty() || card.icon >= 0 || !tag.isEmpty();
        eyebrowX = PAD_X + (card.icon >= 0 ? 22f : 0f);
        float room = inner - (eyebrowX - PAD_X) - 64f;
        if (!tag.isEmpty()) room -= fonts.eyebrow.measureText(tag) + (tagLive ? 26f : 18f);
        eyebrow = GenText.ellipsize(main, fonts.eyebrow, Math.max(40f, room));
        eyebrowWidth = eyebrow.isEmpty() ? 0f : fonts.eyebrow.measureText(eyebrow);
    }

    private void setTag(String value, int color) {
        tag = value;
        tagColor = color;
    }

    private void layoutActions(GenFonts fonts, float inner) {
        if (actionCount == 0) return;
        float each = (inner - (actionCount - 1) * ACTION_GAP) / actionCount;
        for (int index = 0; index < actionCount; index++) {
            actionLeft[index] = PAD_X + index * (each + ACTION_GAP);
            actionRight[index] = actionLeft[index] + each;
            actionLabel[index] = GenText.ellipsize(card.actions.get(index).label, fonts.action, each - 20f);
        }
    }

    private void measure(GenFonts fonts, Box box, GenBlock block, float inner, int textLines) {
        box.block = block;
        box.rows = 0;
        box.firstRow = 0;
        box.height = 0f;
        switch (block.type) {
            case TEXT -> {
                if (block.style == GenSchema.STYLE_LEAD) {
                    box.textPaint = fonts.lead;
                    box.lineHeight = 25f;
                    box.textColor = GenColors.INK;
                } else if (block.style == GenSchema.STYLE_MUTED) {
                    box.textPaint = fonts.muted;
                    box.lineHeight = 21f;
                    box.textColor = GenColors.MUTED;
                } else {
                    box.textPaint = fonts.body;
                    box.lineHeight = 22f;
                    box.textColor = GenColors.INK;
                }
                // Wrap one extra line so trimming can ellipsize honestly; lines[rows] (if any)
                // keeps the first hidden line for partial-fit trimming in buildCard.
                String[] lines = GenText.wrap(block.text, box.textPaint, inner, textLines + 1);
                if (lines.length > textLines) {
                    box.lines = new String[textLines + 1];
                    System.arraycopy(lines, 0, box.lines, 0, textLines + 1);
                    box.lines[textLines - 1] = GenText.ellipsize(lines[textLines - 1] + " " + lines[textLines],
                            box.textPaint, inner);
                    box.rows = textLines;
                } else {
                    box.lines = lines;
                    box.rows = lines.length;
                }
                box.height = box.rows * box.lineHeight;
            }
            case STAT -> {
                float labelH = block.label != null ? 21f : 0f;
                box.label = block.label == null ? null : GenText.ellipsize(block.label, fonts.statLabel, inner);
                box.valueSize = GenText.fitSize(block.value, fonts.statValue, 44f, 26f, inner);
                fonts.statValue.setTextSize(box.valueSize);
                box.valueWidth = Math.min(inner, fonts.statValue.measureText(block.value));
                fonts.statValue.setTextSize(44f);
                box.valueBaseline = labelH + box.valueSize * 0.9f;
                box.delta = null;
                box.deltaBelow = false;
                float height = box.valueBaseline + 8f;
                if (block.delta != null) {
                    float arrow = block.trend == GenSchema.TREND_UP || block.trend == GenSchema.TREND_DOWN ? 16f : 0f;
                    float deltaWidth = fonts.delta.measureText(block.delta) + arrow;
                    if (box.valueWidth + 14f + deltaWidth <= inner) {
                        box.delta = block.delta;
                    } else {
                        box.deltaBelow = true;
                        box.delta = GenText.ellipsize(block.delta, fonts.delta, inner - arrow);
                        height += 22f;
                    }
                }
                box.height = height;
            }
            case KV -> {
                int columns = Math.min(block.columns, Math.max(1, block.keys.length));
                box.columnWidth = (inner - (columns - 1) * 16f) / columns;
                int count = block.keys.length;
                if (box.keys == null || box.keys.length < count) {
                    box.keys = new String[count];
                    box.vals = new String[count];
                }
                for (int index = 0; index < count; index++) {
                    box.keys[index] = GenText.ellipsize(block.keys[index], fonts.kvKey, box.columnWidth - 6f);
                    box.vals[index] = GenText.ellipsize(block.vals[index], fonts.kvValue, box.columnWidth - 6f);
                }
                box.rows = (count + columns - 1) / columns;
                box.height = box.rows * 48f - 6f;
            }
            case LIST, CHECKLIST -> {
                boolean checklist = block.type == GenBlock.Type.CHECKLIST;
                box.rowHeight = 44f;
                int count = block.items.length;
                box.rowHeights = new float[count];
                for (int index = 0; index < count; index++) {
                    box.rowHeights[index] = !checklist && block.items[index].detail != null ? 52f : 44f;
                }
                box.rowTitle = new String[count];
                box.rowDetail = new String[count];
                box.rowTrailing = new String[count];
                box.rowTitleWidth = new float[count];
                for (int index = 0; index < count; index++) {
                    GenRow row = block.items[index];
                    float left = checklist ? 34f : row.status >= 0 ? 20f : row.icon >= 0 ? 30f : 0f;
                    float trailingWidth = 0f;
                    if (!checklist && row.trailing != null) {
                        box.rowTrailing[index] = GenText.ellipsize(row.trailing, fonts.trailing, inner * 0.4f);
                        trailingWidth = fonts.trailing.measureText(box.rowTrailing[index]) + 12f;
                    }
                    box.rowTitle[index] = GenText.ellipsize(row.title, fonts.rowTitle, inner - left - trailingWidth);
                    box.rowTitleWidth[index] = fonts.rowTitle.measureText(box.rowTitle[index]);
                    if (row.detail != null) {
                        box.rowDetail[index] = GenText.ellipsize(row.detail, fonts.rowDetail, inner - left - trailingWidth);
                    }
                }
                setVisibleRows(box, 0, count);
            }
            case PROGRESS -> {
                box.label = block.label == null ? null
                        : GenText.ellipsize(block.label, fonts.progressLabel, inner - 56f);
                box.percent = block.indeterminate() ? null : Math.round(block.progress * 100f) + "%";
                boolean head = box.label != null || box.percent != null;
                box.barTop = head ? 24f : 4f;
                float height = box.barTop + 6f;
                int steps = block.steps == null ? 0 : block.steps.length;
                if (steps > 0) {
                    box.steps = new String[steps];
                    float each = steps == 1 ? inner : inner / steps;
                    for (int index = 0; index < steps; index++) {
                        Paint paint = index == block.step ? fonts.stepCurrent : fonts.stepLabel;
                        box.steps[index] = GenText.ellipsize(block.steps[index], paint, each - 4f);
                    }
                    height += 24f;
                } else {
                    box.steps = null;
                }
                box.height = height + 2f;
            }
            case TIMER -> box.height = 112f;
            case BARS -> {
                boolean labels = block.barLabels != null && block.barLabels.length > 0;
                box.barValue = null;
                if (block.highlight >= 0) {
                    float value = block.bars[block.highlight];
                    String text = value == Math.rint(value) && Math.abs(value) < 1e9
                            ? Long.toString((long) value) : String.format(Locale.US, "%.1f", value);
                    box.barValue = block.unit == null ? text
                            : isCurrency(block.unit) ? block.unit + text : text + " " + block.unit;
                    box.barValueWidth = fonts.barValue.measureText(box.barValue);
                }
                box.labelStep = 1;
                if (labels) {
                    int n = block.bars.length;
                    float slot = (inner - (n - 1) * barGap(n)) / n + barGap(n);
                    float widest = 0f;
                    for (String label : block.barLabels) widest = Math.max(widest, fonts.barLabel.measureText(label));
                    while (box.labelStep < n && widest > slot * box.labelStep - 4f) box.labelStep++;
                }
                box.height = labels ? 116f : 98f;
            }
            case WEATHER -> {
                String temp = block.temp == null ? "" : block.temp;
                box.tempWidth = fonts.weatherTemp.measureText(temp);
                String name = GenSchema.CONDITIONS[block.condition].replace('-', ' ');
                box.condition = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                StringBuilder hiLo = new StringBuilder();
                if (block.hi != null) hiLo.append("H ").append(block.hi);
                if (block.lo != null) hiLo.append(hiLo.length() > 0 ? "   L " : "L ").append(block.lo);
                float metaRoom = inner - 60f - box.tempWidth;
                box.place = block.place == null ? null : GenText.ellipsize(block.place, fonts.weatherMeta, inner * 0.36f);
                float placeWidth = box.place == null ? 0f : fonts.weatherMeta.measureText(box.place) + 10f;
                box.condition = GenText.ellipsize(box.condition, fonts.weatherCondition, metaRoom - placeWidth);
                box.hiLo = GenText.ellipsize(hiLo.toString(), fonts.weatherMeta, metaRoom);
                box.height = block.hourT != null && block.hourT.length > 0 ? 130f : 54f;
            }
            case DIVIDER -> box.height = 1f;
        }
    }

    /** Shows rows [first, first + count) and lays out their slot tops. */
    static void setVisibleRows(Box box, int first, int count) {
        box.firstRow = first;
        box.rows = count;
        if (box.rowTops == null || box.rowTops.length < count + 1) box.rowTops = new float[count + 1];
        float y = 0f;
        for (int slot = 0; slot < count; slot++) {
            box.rowTops[slot] = y;
            y += box.rowHeights[first + slot];
        }
        box.rowTops[count] = y;
        box.height = y;
    }

    static boolean isCurrency(String unit) {
        return unit.length() <= 2 && "$€£¥₹₩".indexOf(unit.charAt(0)) >= 0;
    }

    static float barGap(int count) {
        return count > 8 ? 5f : 8f;
    }

    // ------------------------------------------------------------------ pill

    private void buildPill(GenFonts fonts) {
        height = PILL_HEIGHT;
        measuredHeight = PILL_HEIGHT;
        boolean timer = card.isTimer() && card.timerBlock() != null;
        GenBlock timerBlock = timer ? card.timerBlock() : null;
        boolean timerDone = timerBlock != null && timerBlock.done;
        pillTimer = timer && !timerDone;
        verb = null;
        if (timerDone) {
            verb = "Stop";
        } else if (mode == MODE_PILL && !timer && card.liveTrailing == null && !card.actions.isEmpty()
                && card.live == null) {
            verb = card.actions.get(0).label;
        }
        float left;
        if (verb != null) {
            verb = GenText.ellipsize(verb, fonts.pillVerb, 120f);
            verbWidth = Math.max(64f, fonts.pillVerb.measureText(verb) + 32f);
            left = 14f + verbWidth + 14f;
        } else {
            verbWidth = 0f;
            left = 12f + 44f + 14f;
        }
        textLeft = left;
        pillTrailing = null;
        trailingWidth = 0f;
        if (pillTimer) {
            trailingWidth = fonts.pillTrailing.measureText(timerBlock.totalMs >= 3_600_000L ? "0:00:00" : "00:00") + 18f;
        } else if (card.liveTrailing != null) {
            pillTrailing = GenText.ellipsize(card.liveTrailing, fonts.pillTrailing, 110f);
            trailingWidth = fonts.pillTrailing.measureText(pillTrailing) + 18f;
        } else {
            GenBlock progress = firstOf(GenBlock.Type.PROGRESS);
            if (progress != null && !progress.indeterminate()) {
                pillTrailing = Math.round(progress.progress * 100f) + "%";
                trailingWidth = fonts.pillTrailing.measureText(pillTrailing) + 18f;
            }
        }
        // Running timers have no X (tap expands to the card with Cancel); everything else does.
        showClose = !pillTimer;
        float right = width - (showClose ? 44f : 18f) - trailingWidth;
        textWidth = Math.max(40f, right - left);
        closeCx = width - 26f;
        closeCy = PILL_HEIGHT / 2f;
        closeLeft = width - CLOSE_HIT;
        closeTop = 0f;
        closeRight = width;
        closeBottom = PILL_HEIGHT;
        if (timerDone) {
            title = GenText.ellipsize(card.displayTitle() + " timer is done", fonts.pillTitle, textWidth);
            subtitle = "";
        } else {
            title = GenText.ellipsize(card.displayTitle(), fonts.pillTitle, textWidth);
            String sub = card.displaySubtitle();
            if (sub == null && timerBlock != null) sub = timerBlock.paused ? "Paused" : timerBlock.label;
            if (sub == null && card.liveNote != null) sub = card.liveNote;
            if (sub == null) {
                GenBlock progress = firstOf(GenBlock.Type.PROGRESS);
                if (progress != null) sub = progress.label;
            }
            if (sub == null) sub = summary(card);
            subtitle = sub == null ? "" : GenText.ellipsize(sub, fonts.pillSubtitle, textWidth);
        }
        actionCount = 0;
        boxCount = 0;
        overflow = false;
    }

    /** One glanceable line from the body for title-only pills ("64° • Rain", "2 of 6 done"). */
    static String summary(GenCard card) {
        for (int index = 0; index < card.body.size(); index++) {
            GenBlock block = card.body.get(index);
            switch (block.type) {
                case WEATHER -> {
                    String condition = GenSchema.CONDITIONS[block.condition].replace('-', ' ');
                    condition = Character.toUpperCase(condition.charAt(0)) + condition.substring(1);
                    return block.temp != null ? block.temp + " • " + condition : condition;
                }
                case STAT -> {
                    return block.label != null ? block.value + " • " + block.label : block.value;
                }
                case CHECKLIST -> {
                    int done = 0;
                    for (GenRow row : block.items) if (row.checked) done++;
                    return done + " of " + block.items.length + " done";
                }
                case LIST -> {
                    GenRow first = block.items[0];
                    String more = block.items.length > 1 ? " +" + (block.items.length - 1) : "";
                    return first.title + more;
                }
                case TEXT -> {
                    return block.text;
                }
                case KV -> {
                    return block.keys[0] + " " + block.vals[0];
                }
                case PROGRESS -> {
                    if (!block.indeterminate()) return Math.round(block.progress * 100f) + "%";
                }
                default -> { }
            }
        }
        return null;
    }

    private GenBlock firstOf(GenBlock.Type type) {
        for (int index = 0; index < card.body.size(); index++) {
            if (card.body.get(index).type == type) return card.body.get(index);
        }
        return null;
    }

    // ------------------------------------------------------------------ hit testing

    /** Index of the action pill at (x, y) in card coordinates, or -1. */
    /** Actions are anchored to the card bottom (the card may be taller than its content). */
    float actionTop() {
        return height - PAD_BOTTOM - ACTION_H;
    }

    public int actionAt(float x, float y) {
        if (mode == MODE_PILL || mode == MODE_ROW || actionCount == 0) return -1;
        float actionTop = actionTop();
        if (y < actionTop - 4f || y > actionTop + ACTION_H + 6f) return -1;
        for (int index = 0; index < actionCount; index++) {
            if (x >= actionLeft[index] - 4f && x <= actionRight[index] + 4f) return index;
        }
        return -1;
    }

    public boolean closeAt(float x, float y) {
        return showClose && x >= closeLeft && x <= closeRight && y >= closeTop && y <= closeBottom;
    }

    /** Pill: hit on the leading verb pill. */
    public boolean verbAt(float x, float y) {
        return (mode == MODE_PILL || mode == MODE_ROW) && verb != null && x <= 14f + verbWidth + 8f && y >= 6f && y <= PILL_HEIGHT - 6f;
    }

    /**
     * Checklist/list row at (x, y) in card coordinates ({@code scroll} for expanded mode).
     * Returns {@code boxIndex << 8 | row}, or -1.
     */
    public int rowAt(float x, float y, float scroll) {
        if (mode == MODE_PILL || mode == MODE_ROW) return -1;
        if (y < bodyTop || y > bodyBottom) return -1;
        float contentY = y + scroll;
        for (int index = 0; index < boxCount; index++) {
            Box box = boxes[index];
            if (box.block.type != GenBlock.Type.CHECKLIST && box.block.type != GenBlock.Type.LIST) continue;
            if (contentY >= box.top && contentY < box.top + box.height) {
                float local = contentY - box.top;
                int slot = 0;
                while (slot < box.rows - 1 && local >= box.rowTops[slot + 1]) slot++;
                return index << 8 | (box.firstRow + slot);
            }
        }
        return -1;
    }

    public boolean moreAt(float x, float y) {
        return overflow && more != null && y >= moreTop - 10f && y <= moreTop + MORE_H + 10f;
    }

    GenBlock boxBlock(int boxIndex) {
        return boxIndex >= 0 && boxIndex < boxCount ? boxes[boxIndex].block : null;
    }
}
