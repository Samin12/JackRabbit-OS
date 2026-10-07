package com.resonolabs.feature.genui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.resonolabs.ui.design.GlassPainter;

/**
 * Draws one card (or its compact pill) into a given rect in the orb/glass language. Reusable
 * by any 480x640 logical canvas (Voice overlay, Cards &gt; Live, a widgets board). Draw
 * methods never allocate: text comes prepared from {@link GenCardLayout}; clocks and counters
 * are written into reused char buffers; gradients are cached by {@link GlassPainter}.
 */
public final class GenCardRenderer {
    public static final float PILL_RADIUS = 34f;

    final GenFonts fonts = new GenFonts();
    final GenIcons icons = new GenIcons();
    final GlassPainter glass = new GlassPainter();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final char[] clock = new char[12];
    private final char[] counter = new char[8];

    /** Ensures {@code layout} matches the card (rebuilding only on change) and returns it. */
    public GenCardLayout layout(GenCardLayout layout, GenCard card, int mode, float width, float budget) {
        GenCardLayout target = layout != null ? layout : new GenCardLayout();
        target.ensure(fonts, card, mode, width, budget);
        return target;
    }

    public GenIcons icons() {
        return icons;
    }

    public GlassPainter glass() {
        return glass;
    }

    // ------------------------------------------------------------------ card

    /**
     * @param index  0-based position for the "2/3" counter; ignored when {@code total < 2}
     * @param scroll body scroll offset (expanded mode)
     */
    public void drawCard(Canvas canvas, GenCardLayout l, float x, float y, long now, int index, int total,
                         float scroll) {
        GenCard card = l.card;
        boolean stale = card.state == GenCard.State.STALE;
        int accent = stale ? GenColors.desaturate(card.accent.color, 0.8f) : card.accent.color;
        int accentText = stale ? GenColors.MUTED : card.accent.text;
        float w = l.width;
        float h = l.height;
        canvas.save();
        canvas.translate(x, y);

        // frame: solid panel (legible over the orb glow), accent wash, pale hairline
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GenColors.withAlpha(GenColors.PANEL, 248));
        canvas.drawRoundRect(0f, 0f, w, h, GenCardLayout.RADIUS, GenCardLayout.RADIUS, paint);
        float wash = Math.min(h, 150f);
        glass.fillVertical(canvas, paint, 0f, 0f, w, wash, GenCardLayout.RADIUS,
                GenColors.withAlpha(accent, 40), GenColors.withAlpha(accent, 0), wash);
        glass.draw(canvas, paint, 0f, 0f, w, h, GenCardLayout.RADIUS, true);
        drawPulse(canvas, card, accent, w, h, GenCardLayout.RADIUS, now);

        drawHeader(canvas, l, card, accentText, w, now, index, total);

        // body
        boolean expanded = l.mode == GenCardLayout.MODE_EXPANDED;
        boolean squeezed = !expanded && h < l.measuredHeight - 0.5f; // mid-animation
        if (expanded || squeezed) {
            canvas.save();
            float bottom = expanded ? l.bodyBottom
                    : l.actionCount > 0 ? l.actionTop() - 6f : h - 10f;
            canvas.clipRect(0f, l.bodyTop, w, bottom);
            if (expanded) canvas.translate(0f, -scroll);
        }
        float inner = w - 2f * GenCardLayout.PAD_X;
        for (int box = 0; box < l.boxCount; box++) {
            drawBox(canvas, l.boxes[box], card, GenCardLayout.PAD_X, inner, accent, accentText, now);
        }
        if (l.more != null) {
            fonts.more.setColor(GenColors.withAlpha(GenColors.ORB_PALE, 210));
            canvas.drawText(l.more, GenCardLayout.PAD_X, l.moreTop + 15f, fonts.more);
        }
        if (expanded || squeezed) canvas.restore();
        if (expanded) {
            float viewport = l.bodyBottom - l.bodyTop;
            float maxScroll = l.contentHeight - viewport;
            // Soft edges where content scrolls under the header / actions.
            int panel = GenColors.withAlpha(GenColors.PANEL, 248);
            int clear = GenColors.withAlpha(GenColors.PANEL, 0);
            if (scroll > 1f) {
                glass.fillVertical(canvas, paint, 2f, l.bodyTop, w - 2f, l.bodyTop + 20f, 0f, panel, clear, 20f);
            }
            if (maxScroll > 1f && scroll < maxScroll - 1f) {
                glass.fillVertical(canvas, paint, 2f, l.bodyBottom - 28f, w - 2f, l.bodyBottom, 0f, clear, panel, 28f);
            }
            if (l.contentHeight > viewport + 1f) {
                float track = viewport - 12f;
                float thumb = Math.max(28f, track * viewport / l.contentHeight);
                float travel = track - thumb;
                float max = l.contentHeight - viewport;
                float top = l.bodyTop + 6f + (max <= 0f ? 0f : travel * Math.min(1f, scroll / max));
                paint.setColor(GenColors.withAlpha(GenColors.ORB_PALE, 36));
                canvas.drawRoundRect(w - 7f, l.bodyTop + 6f, w - 4f, l.bodyTop + 6f + track, 1.5f, 1.5f, paint);
                paint.setColor(GenColors.withAlpha(GenColors.ORB_PALE, 150));
                canvas.drawRoundRect(w - 7f, top, w - 4f, top + thumb, 1.5f, 1.5f, paint);
            }
        }
        drawActions(canvas, l, card);
        canvas.restore();
    }

    private void drawHeader(Canvas canvas, GenCardLayout l, GenCard card, int accentText, float w, long now,
                            int index, int total) {
        float cy = l.headerCenterY;
        if (l.eyebrowRow) {
            float baseline = cy + 4.3f;
            if (card.icon >= 0) {
                icons.draw(canvas, paint, card.icon, GenCardLayout.PAD_X + 7.5f, cy, 15f, accentText, 1.7f);
            }
            float tx = l.eyebrowX;
            if (!l.eyebrow.isEmpty()) {
                fonts.eyebrow.setColor(accentText);
                canvas.drawText(l.eyebrow, tx, baseline, fonts.eyebrow);
                tx += l.eyebrowWidth + 7f;
            }
            if (!l.tag.isEmpty()) {
                if (!l.eyebrow.isEmpty()) {
                    paint.setColor(GenColors.withAlpha(GenColors.MUTED, 200));
                    canvas.drawCircle(tx + 1.5f, cy, 1.6f, paint);
                    tx += 9f;
                }
                if (l.tagLive) {
                    float pulse = 0.55f + 0.45f * (float) Math.sin(now / 260.0);
                    paint.setColor(GenColors.withAlpha(GenColors.SUCCESS, Math.round(255 * pulse)));
                    canvas.drawCircle(tx + 3.5f, cy, 3.5f, paint);
                    tx += 12f;
                }
                fonts.eyebrow.setColor(l.tagColor);
                canvas.drawText(l.tag, tx, baseline, fonts.eyebrow);
            }
        }
        if (total > 1) {
            int n = writeCounter(index + 1, total);
            fonts.counter.setColor(GenColors.MUTED);
            fonts.counter.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(counter, 0, n, w - 50f, l.closeCy + 4.3f, fonts.counter);
            fonts.counter.setTextAlign(Paint.Align.LEFT);
        }
        if (l.showClose) {
            icons.drawClose(canvas, paint, l.closeCx, l.closeCy, 18f, GenColors.withAlpha(GenColors.INK, 170), 1.9f);
        }
        fonts.title.setColor(GenColors.INK);
        canvas.drawText(l.title, GenCardLayout.PAD_X, l.titleBaseline, fonts.title);
        if (card.state == GenCard.State.STALE) {
            fonts.subtitle.setColor(GenColors.AMBER);
            canvas.drawText(l.staleText(now), GenCardLayout.PAD_X, l.subtitleBaseline, fonts.subtitle);
        } else if (!l.subtitle.isEmpty()) {
            fonts.subtitle.setColor(GenColors.MUTED);
            canvas.drawText(l.subtitle, GenCardLayout.PAD_X, l.subtitleBaseline, fonts.subtitle);
        }
    }

    private void drawPulse(Canvas canvas, GenCard card, int accent, float w, float h, float radius, long now) {
        long age = now - card.pulseAt;
        if (card.pulseAt <= 0L || age < 0L || age > 900L) return;
        float strength = 1f - age / 900f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setColor(GenColors.withAlpha(accent, Math.round(220 * strength)));
        canvas.drawRoundRect(1.25f, 1.25f, w - 1.25f, h - 1.25f, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawActions(Canvas canvas, GenCardLayout l, GenCard card) {
        for (int index = 0; index < l.actionCount; index++) {
            GenAction action = card.actions.get(index);
            float left = l.actionLeft[index];
            float right = l.actionRight[index];
            float top = l.actionTop();
            float bottom = top + GenCardLayout.ACTION_H;
            float radius = GenCardLayout.ACTION_H / 2f;
            int text;
            switch (action.style) {
                case PRIMARY -> {
                    paint.setColor(GenColors.INK);
                    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
                    text = GenColors.BACKGROUND;
                }
                case DANGER -> {
                    paint.setColor(GenColors.DANGER_FILL);
                    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
                    text = GenColors.INK;
                }
                default -> {
                    paint.setColor(GenColors.withAlpha(GenColors.PANEL_RAISED, 255));
                    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
                    glass.draw(canvas, paint, left, top, right, bottom, radius, false);
                    text = GenColors.INK;
                }
            }
            fonts.action.setColor(text);
            fonts.action.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(l.actionLabel[index], (left + right) / 2f, top + 30f, fonts.action);
            fonts.action.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ------------------------------------------------------------------ blocks

    private void drawBox(Canvas canvas, GenCardLayout.Box box, GenCard card, float x, float inner, int accent,
                         int accentText, long now) {
        GenBlock block = box.block;
        float top = box.top;
        switch (block.type) {
            case TEXT -> {
                Paint text = box.textPaint;
                text.setColor(box.textColor);
                float size = text.getTextSize();
                for (int line = 0; line < box.rows && line < box.lines.length; line++) {
                    canvas.drawText(box.lines[line], x, top + size + line * box.lineHeight - 1f, text);
                }
            }
            case STAT -> drawStat(canvas, box, block, x, top);
            case KV -> {
                int columns = Math.min(block.columns, Math.max(1, block.keys.length));
                fonts.kvKey.setColor(GenColors.MUTED);
                fonts.kvValue.setColor(GenColors.INK);
                for (int index = 0; index < block.keys.length; index++) {
                    float cx = x + (index % columns) * (box.columnWidth + 16f);
                    float cy = top + (index / columns) * 48f;
                    canvas.drawText(box.keys[index], cx, cy + 13f, fonts.kvKey);
                    canvas.drawText(box.vals[index], cx, cy + 37f, fonts.kvValue);
                }
            }
            case LIST -> drawList(canvas, box, block, x, inner, now);
            case CHECKLIST -> drawChecklist(canvas, box, block, x, inner, accent);
            case PROGRESS -> drawProgress(canvas, box, block, card, x, inner, accent, now);
            case TIMER -> drawTimer(canvas, block, x, top, accent, accentText, now);
            case BARS -> drawBars(canvas, box, block, x, inner, top, accent);
            case WEATHER -> drawWeather(canvas, box, block, x, inner, top);
            case DIVIDER -> {
                paint.setColor(GenColors.LINE);
                canvas.drawRect(x, top, x + inner, top + 1f, paint);
            }
        }
    }

    private void drawStat(Canvas canvas, GenCardLayout.Box box, GenBlock block, float x, float top) {
        if (box.label != null) {
            fonts.statLabel.setColor(GenColors.MUTED);
            canvas.drawText(box.label, x, top + 14f, fonts.statLabel);
        }
        fonts.statValue.setTextSize(box.valueSize);
        fonts.statValue.setColor(GenColors.INK);
        canvas.drawText(block.value, x, top + box.valueBaseline, fonts.statValue);
        fonts.statValue.setTextSize(44f);
        if (box.delta == null) return;
        int color = block.trend == GenSchema.TREND_UP ? GenColors.SUCCESS
                : block.trend == GenSchema.TREND_DOWN ? GenColors.RED : GenColors.MUTED;
        float dx = box.deltaBelow ? x : x + box.valueWidth + 14f;
        float baseline = box.deltaBelow ? top + box.valueBaseline + 24f : top + box.valueBaseline - 3f;
        if (block.trend == GenSchema.TREND_UP || block.trend == GenSchema.TREND_DOWN) {
            icons.drawTriangle(canvas, paint, block.trend == GenSchema.TREND_UP, dx + 5f, baseline - 5.5f, 11f, color);
            dx += 16f;
        }
        fonts.delta.setColor(color);
        canvas.drawText(box.delta, dx, baseline, fonts.delta);
    }

    private void drawList(Canvas canvas, GenCardLayout.Box box, GenBlock block, float x, float inner, long now) {
        for (int slot = 0; slot < box.rows; slot++) {
            int row = box.firstRow + slot;
            GenRow item = block.items[row];
            float rt = box.top + slot * box.rowHeight;
            if (slot > 0) {
                paint.setColor(GenColors.HAIRLINE);
                canvas.drawRect(x, rt, x + inner, rt + 1f, paint);
            }
            boolean detail = box.rowDetail[row] != null;
            float titleBaseline = detail ? rt + 23f : rt + box.rowHeight / 2f + 6f;
            float left = x;
            if (item.status >= 0) {
                int color = GenColors.status(item.status);
                if (item.status == GenSchema.STATUS_ACTIVE) {
                    float pulse = 0.5f + 0.5f * (float) Math.sin(now / 240.0);
                    paint.setColor(GenColors.withAlpha(color, 60));
                    canvas.drawCircle(x + 5f, titleBaseline - 6f, 5f + 3f * pulse, paint);
                }
                paint.setColor(color);
                canvas.drawCircle(x + 5f, titleBaseline - 6f, 4.5f, paint);
                left = x + 20f;
            } else if (item.icon >= 0) {
                icons.draw(canvas, paint, item.icon, x + 9f, titleBaseline - 6f, 18f, GenColors.ORB_PALE, 1.7f);
                left = x + 30f;
            }
            if (box.rowTrailing[row] != null) {
                fonts.trailing.setColor(GenColors.MUTED);
                fonts.trailing.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(box.rowTrailing[row], x + inner, titleBaseline, fonts.trailing);
                fonts.trailing.setTextAlign(Paint.Align.LEFT);
            }
            fonts.rowTitle.setColor(item.status == GenSchema.STATUS_IDLE ? GenColors.withAlpha(GenColors.INK, 200) : GenColors.INK);
            canvas.drawText(box.rowTitle[row], left, titleBaseline, fonts.rowTitle);
            if (detail) {
                fonts.rowDetail.setColor(GenColors.MUTED);
                canvas.drawText(box.rowDetail[row], left, rt + 41f, fonts.rowDetail);
            }
        }
    }

    private void drawChecklist(Canvas canvas, GenCardLayout.Box box, GenBlock block, float x, float inner, int accent) {
        for (int row = 0; row < box.rows; row++) {
            GenRow item = block.items[row];
            float rt = box.top + row * box.rowHeight;
            if (row > 0) {
                paint.setColor(GenColors.HAIRLINE);
                canvas.drawRect(x, rt, x + inner, rt + 1f, paint);
            }
            float boxTop = rt + 11f;
            if (item.checked) {
                paint.setColor(accent);
                canvas.drawRoundRect(x, boxTop, x + 22f, boxTop + 22f, 7f, 7f, paint);
                icons.drawCheck(canvas, paint, x + 11f, boxTop + 11f, 20f, GenColors.INK, 2.4f);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f);
                paint.setColor(GenColors.withAlpha(GenColors.ORB_PALE, 150));
                canvas.drawRoundRect(x + 0.9f, boxTop + 0.9f, x + 21.1f, boxTop + 21.1f, 6.5f, 6.5f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            fonts.rowTitle.setColor(item.checked ? GenColors.MUTED : GenColors.INK);
            canvas.drawText(box.rowTitle[row], x + 34f, rt + 28.5f, fonts.rowTitle);
            if (item.checked) {
                paint.setColor(GenColors.withAlpha(GenColors.MUTED, 220));
                canvas.drawRect(x + 34f, rt + 22f, x + 34f + box.rowTitleWidth[row], rt + 23.4f, paint);
            }
        }
    }

    private void drawProgress(Canvas canvas, GenCardLayout.Box box, GenBlock block, GenCard card, float x,
                              float inner, int accent, long now) {
        float top = box.top;
        if (box.label != null) {
            fonts.progressLabel.setColor(GenColors.withAlpha(GenColors.INK, 215));
            canvas.drawText(box.label, x, top + 14f, fonts.progressLabel);
        }
        if (box.percent != null) {
            fonts.percent.setColor(GenColors.INK);
            fonts.percent.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(box.percent, x + inner, top + 14f, fonts.percent);
            fonts.percent.setTextAlign(Paint.Align.LEFT);
        }
        float barTop = top + box.barTop;
        int fill = card.liveStatus == GenSchema.STATUS_ERROR ? GenColors.RED
                : card.terminal && card.liveStatus == GenSchema.STATUS_OK ? GenColors.SUCCESS : accent;
        paint.setColor(GenColors.withAlpha(GenColors.ORB_PALE, 36));
        canvas.drawRoundRect(x, barTop, x + inner, barTop + 6f, 3f, 3f, paint);
        int steps = box.steps == null ? 0 : box.steps.length;
        float fraction = block.progress;
        if (fraction < 0f && steps > 1 && block.step >= 0) fraction = block.step / (float) (steps - 1);
        paint.setColor(fill);
        if (fraction >= 0f) {
            canvas.drawRoundRect(x, barTop, x + Math.max(6f, inner * fraction), barTop + 6f, 3f, 3f, paint);
        } else if (!card.terminal && card.state != GenCard.State.STALE) {
            float phase = (now % 1500L) / 1500f;
            float segment = inner * 0.28f;
            float start = x - segment + (inner + segment) * phase;
            float left = Math.max(x, start);
            float right = Math.min(x + inner, start + segment);
            if (right > left + 1f) canvas.drawRoundRect(left, barTop, right, barTop + 6f, 3f, 3f, paint);
        }
        for (int index = 0; index < steps; index++) {
            boolean current = index == block.step;
            Paint label = current ? fonts.stepCurrent : fonts.stepLabel;
            label.setColor(current ? GenColors.INK : GenColors.MUTED);
            float sx;
            if (steps == 1 || index == 0) {
                label.setTextAlign(Paint.Align.LEFT);
                sx = x;
            } else if (index == steps - 1) {
                label.setTextAlign(Paint.Align.RIGHT);
                sx = x + inner;
            } else {
                label.setTextAlign(Paint.Align.CENTER);
                sx = x + inner * index / (steps - 1);
            }
            canvas.drawText(box.steps[index], sx, barTop + 26f, label);
            label.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawTimer(Canvas canvas, GenBlock block, float x, float top, int accent, int accentText, long now) {
        float cx = x + 54f;
        float cy = top + 56f;
        float radius = 49f;
        long remaining = GenTimers.remaining(block, now);
        float fraction = block.done ? 1f : GenTimers.fraction(block, now);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(GenColors.withAlpha(GenColors.ORB_PALE, 34));
        canvas.drawCircle(cx, cy, radius, paint);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        int ring = block.paused ? GenColors.withAlpha(GenColors.MUTED, 200) : accent;
        if (block.done) {
            float pulse = 0.6f + 0.4f * (float) Math.sin(now / 180.0);
            ring = GenColors.withAlpha(accent, Math.round(255 * pulse));
        }
        paint.setColor(ring);
        if (fraction > 0.002f) canvas.drawArc(arc, -90f, 360f * fraction, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        if (block.done) icons.draw(canvas, paint, GenSchema.ICON_CHECK, cx, cy, 30f, accentText, 3f);
        else if (block.paused) icons.drawPause(canvas, paint, cx, cy, 26f, GenColors.ORB_PALE);
        else icons.draw(canvas, paint, GenSchema.ICON_TIMER, cx, cy, 28f, GenColors.ORB_PALE, 2f);

        float tx = x + 124f;
        Paint time = fonts.timer;
        if (block.done) {
            time.setColor(accentText);
            canvas.drawText("Done", tx, top + 66f, time);
        } else {
            int n = GenTimers.format(remaining, clock);
            time.setColor(block.paused ? GenColors.withAlpha(GenColors.INK, 150) : GenColors.INK);
            canvas.drawText(clock, 0, n, tx, top + 66f, time);
        }
        fonts.timerLabel.setColor(block.paused ? GenColors.AMBER : GenColors.MUTED);
        String label = block.done ? "Timer finished" : block.paused ? "Paused" : block.label != null ? block.label : "remaining";
        canvas.drawText(label, tx + 2f, top + 92f, fonts.timerLabel);
    }

    private void drawBars(Canvas canvas, GenCardLayout.Box box, GenBlock block, float x, float inner, float top,
                          int accent) {
        int count = block.bars.length;
        float gap = GenCardLayout.barGap(count);
        float width = (inner - (count - 1) * gap) / count;
        float max = 0f;
        for (float value : block.bars) max = Math.max(max, value);
        if (max <= 0f) max = 1f;
        float baseline = top + 92f;
        float area = 70f;
        float radius = Math.min(6f, width / 2f);
        boolean highlight = block.highlight >= 0;
        for (int index = 0; index < count; index++) {
            float height = Math.max(4f, area * block.bars[index] / max);
            float left = x + index * (width + gap);
            if (highlight) {
                paint.setColor(index == block.highlight ? GenColors.INK : GenColors.withAlpha(GenColors.MUTED, 130));
            } else {
                paint.setColor(GenColors.withAlpha(accent, 200));
            }
            canvas.drawRoundRect(left, baseline - height, left + width, baseline, radius, radius, paint);
        }
        if (box.barValue != null) {
            float center = x + block.highlight * (width + gap) + width / 2f;
            float half = box.barValueWidth / 2f;
            center = Math.max(x + half, Math.min(x + inner - half, center));
            float barTop = baseline - Math.max(4f, area * block.bars[block.highlight] / max);
            fonts.barValue.setColor(GenColors.INK);
            fonts.barValue.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(box.barValue, center, barTop - 7f, fonts.barValue);
            fonts.barValue.setTextAlign(Paint.Align.LEFT);
        }
        if (block.barLabels != null && block.barLabels.length > 0) {
            fonts.barLabel.setTextAlign(Paint.Align.CENTER);
            int labels = Math.min(count, block.barLabels.length);
            for (int index = 0; index < labels; index++) {
                boolean keep = index % box.labelStep == 0 || index == block.highlight;
                if (!keep) continue;
                fonts.barLabel.setColor(index == block.highlight ? GenColors.INK : GenColors.MUTED);
                canvas.drawText(block.barLabels[index], x + index * (width + gap) + width / 2f, top + 112f, fonts.barLabel);
            }
            fonts.barLabel.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawWeather(Canvas canvas, GenCardLayout.Box box, GenBlock block, float x, float inner, float top) {
        icons.drawWeather(canvas, paint, block.condition, x + 22f, top + 26f, 46f);
        float tx = x + 54f;
        if (block.temp != null) {
            fonts.weatherTemp.setColor(GenColors.INK);
            canvas.drawText(block.temp, tx, top + 44f, fonts.weatherTemp);
        }
        float mx = tx + box.tempWidth + 12f;
        fonts.weatherCondition.setColor(GenColors.INK);
        canvas.drawText(box.condition, mx, top + 21f, fonts.weatherCondition);
        if (!box.hiLo.isEmpty()) {
            fonts.weatherMeta.setColor(GenColors.MUTED);
            canvas.drawText(box.hiLo, mx, top + 43f, fonts.weatherMeta);
        }
        if (box.place != null) {
            fonts.weatherMeta.setColor(GenColors.MUTED);
            fonts.weatherMeta.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(box.place, x + inner, top + 21f, fonts.weatherMeta);
            fonts.weatherMeta.setTextAlign(Paint.Align.LEFT);
        }
        int hours = block.hourT == null ? 0 : block.hourT.length;
        if (hours == 0) return;
        paint.setColor(GenColors.HAIRLINE);
        canvas.drawRect(x, top + 62f, x + inner, top + 63f, paint);
        fonts.hourLabel.setTextAlign(Paint.Align.CENTER);
        fonts.hourTemp.setTextAlign(Paint.Align.CENTER);
        for (int index = 0; index < hours; index++) {
            float hx = x + (index + 0.5f) * inner / hours;
            fonts.hourLabel.setColor(GenColors.MUTED);
            canvas.drawText(block.hourT[index], hx, top + 84f, fonts.hourLabel);
            icons.drawWeather(canvas, paint, block.hourCondition[index], hx, top + 102f, 22f);
            fonts.hourTemp.setColor(GenColors.INK);
            canvas.drawText(block.hourTemp[index], hx, top + 128f, fonts.hourTemp);
        }
        fonts.hourLabel.setTextAlign(Paint.Align.LEFT);
        fonts.hourTemp.setTextAlign(Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------ pill

    /** Compact one-line pill (Live Activity / Hark Action Button). */
    public void drawPill(Canvas canvas, GenCardLayout l, float x, float y, long now) {
        GenCard card = l.card;
        boolean stale = card.state == GenCard.State.STALE;
        int accent = stale ? GenColors.desaturate(card.accent.color, 0.8f) : card.accent.color;
        float w = l.width;
        float h = GenCardLayout.PILL_HEIGHT;
        float mid = h / 2f;
        GenBlock timer = card.isTimer() ? card.timerBlock() : null;
        canvas.save();
        canvas.translate(x, y);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GenColors.withAlpha(GenColors.PANEL, 240));
        canvas.drawRoundRect(0f, 0f, w, h, PILL_RADIUS, PILL_RADIUS, paint);
        glass.fillVertical(canvas, paint, 0f, 0f, w, h, PILL_RADIUS,
                GenColors.withAlpha(accent, 34), GenColors.withAlpha(accent, 6), h);
        glass.draw(canvas, paint, 0f, 0f, w, h, PILL_RADIUS, true);
        if (timer != null && timer.done) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 180.0);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setColor(GenColors.withAlpha(accent, Math.round(110 + 140 * pulse)));
            canvas.drawRoundRect(1.25f, 1.25f, w - 1.25f, h - 1.25f, PILL_RADIUS, PILL_RADIUS, paint);
            paint.setStyle(Paint.Style.FILL);
        } else {
            drawPulse(canvas, card, accent, w, h, PILL_RADIUS, now);
        }

        if (l.verb != null) {
            paint.setColor(GenColors.INK);
            canvas.drawRoundRect(14f, mid - 20f, 14f + l.verbWidth, mid + 20f, 20f, 20f, paint);
            fonts.pillVerb.setColor(GenColors.BACKGROUND);
            fonts.pillVerb.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(l.verb, 14f + l.verbWidth / 2f, mid + 5.5f, fonts.pillVerb);
            fonts.pillVerb.setTextAlign(Paint.Align.LEFT);
        } else {
            float cx = 12f + 22f;
            paint.setColor(GenColors.withAlpha(accent, 60));
            canvas.drawCircle(cx, mid, 22f, paint);
            if (timer != null) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                arc.set(cx - 20.5f, mid - 20.5f, cx + 20.5f, mid + 20.5f);
                paint.setColor(timer.paused ? GenColors.MUTED : accent);
                float fraction = GenTimers.fraction(timer, now);
                if (fraction > 0.002f) canvas.drawArc(arc, -90f, 360f * fraction, false, paint);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStyle(Paint.Style.FILL);
            }
            int icon = card.icon >= 0 ? card.icon : card.live != null ? GenSchema.ICON_BOLT : -1;
            if (icon >= 0) icons.draw(canvas, paint, icon, cx, mid, 20f, GenColors.ORB_PALE, 1.9f);
            else {
                paint.setColor(GenColors.ORB_PALE);
                canvas.drawCircle(cx, mid, 4.5f, paint);
            }
        }
        boolean hasSubtitle = !l.subtitle.isEmpty() || stale;
        fonts.pillTitle.setColor(GenColors.INK);
        canvas.drawText(l.title, l.textLeft, hasSubtitle ? mid - 3f : mid + 6f, fonts.pillTitle);
        if (stale) {
            fonts.pillSubtitle.setColor(GenColors.AMBER);
            canvas.drawText(l.staleText(now), l.textLeft, mid + 16f, fonts.pillSubtitle);
        } else if (hasSubtitle) {
            fonts.pillSubtitle.setColor(timer != null && timer.paused ? GenColors.AMBER : GenColors.MUTED);
            canvas.drawText(l.subtitle, l.textLeft, mid + 16f, fonts.pillSubtitle);
        }
        float trailingRight = w - (l.showClose ? 44f : 22f);
        fonts.pillTrailing.setTextAlign(Paint.Align.RIGHT);
        if (l.pillTimer && timer != null) {
            int n = GenTimers.format(GenTimers.remaining(timer, now), clock);
            fonts.pillTrailing.setColor(timer.paused ? GenColors.withAlpha(GenColors.INK, 150) : GenColors.INK);
            canvas.drawText(clock, 0, n, trailingRight, mid + 8.5f, fonts.pillTrailing);
        } else if (l.pillTrailing != null) {
            fonts.pillTrailing.setColor(GenColors.INK);
            canvas.drawText(l.pillTrailing, trailingRight, mid + 8.5f, fonts.pillTrailing);
        }
        fonts.pillTrailing.setTextAlign(Paint.Align.LEFT);
        if (l.showClose) {
            icons.drawClose(canvas, paint, l.closeCx, l.closeCy, 15f, GenColors.withAlpha(GenColors.INK, 150), 1.8f);
        }
        // live work in progress: a quiet shimmer along the bottom edge
        if (card.live != null && (card.live.type == LiveBinding.Type.T3_THREAD
                || card.live.type == LiveBinding.Type.BACKGROUND_RUN)
                && card.isRunningLive() && !stale && !card.livePaused && card.liveNote == null) {
            float phase = (now % 1600L) / 1600f;
            float span = w - 80f;
            float start = 40f + span * phase - 40f;
            float left = Math.max(40f, start);
            float right = Math.min(w - 40f, start + 64f);
            if (right > left + 1f) {
                paint.setColor(GenColors.withAlpha(accent, 220));
                canvas.drawRoundRect(left, h - 6f, right, h - 3.5f, 1.25f, 1.25f, paint);
            }
        }
        canvas.restore();
    }

    // ------------------------------------------------------------------ stack peeks

    /** A card peeking behind the front card (only its top edge shows). */
    public void drawPeek(Canvas canvas, GenCard card, float left, float top, float right, float bottom, int depth,
                         long now) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GenColors.withAlpha(GenColors.PANEL_RAISED, 245 - depth * 55));
        canvas.drawRoundRect(left, top, right, bottom, 22f, 22f, paint);
        glass.draw(canvas, paint, left, top, right, bottom, 22f, false);
        int accent = card.accent.color;
        long age = now - card.pulseAt;
        int alpha = card.pulseAt > 0L && age >= 0L && age < 1_200L ? Math.round(230 * (1f - age / 1200f)) : 70 - depth * 18;
        paint.setColor(GenColors.withAlpha(accent, alpha));
        float inset = 30f;
        canvas.drawRoundRect(left + inset, top + 2.5f, right - inset, top + 4.5f, 1f, 1f, paint);
    }

    private int writeCounter(int index, int total) {
        int n = 0;
        if (index >= 10) counter[n++] = (char) ('0' + (index / 10) % 10);
        counter[n++] = (char) ('0' + index % 10);
        counter[n++] = '/';
        if (total >= 10) counter[n++] = (char) ('0' + (total / 10) % 10);
        counter[n++] = (char) ('0' + total % 10);
        return n;
    }
}
