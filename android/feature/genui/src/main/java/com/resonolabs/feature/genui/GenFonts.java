package com.resonolabs.feature.genui;

import android.graphics.Paint;
import android.graphics.Typeface;

/** Pre-configured text paints shared by layout (measuring) and renderer (drawing). */
final class GenFonts {
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    final Paint eyebrow = text(12f, true);
    final Paint counter = text(12f, false);
    final Paint title = text(22f, true);
    final Paint subtitle = text(15f, false);
    final Paint body = text(16f, false);
    final Paint muted = text(15f, false);
    final Paint lead = text(18f, false);
    final Paint rowTitle = text(17f, false);
    final Paint rowDetail = text(13f, false);
    final Paint trailing = text(15f, false);
    final Paint kvKey = text(13f, false);
    final Paint kvValue = text(19f, true);
    final Paint statLabel = text(14f, false);
    final Paint statValue = text(44f, true);
    final Paint delta = text(15f, true);
    final Paint progressLabel = text(14f, false);
    final Paint percent = text(14f, true);
    final Paint stepLabel = text(13f, false);
    final Paint stepCurrent = text(13f, true);
    final Paint timer = tabular(text(50f, true));
    final Paint timerLabel = text(15f, false);
    final Paint barLabel = text(12f, false);
    final Paint barValue = tabular(text(12f, true));
    final Paint weatherTemp = text(46f, true);
    final Paint weatherCondition = text(15f, false);
    final Paint weatherMeta = text(14f, false);
    final Paint hourLabel = text(12f, false);
    final Paint hourTemp = text(14f, false);
    final Paint action = text(16f, true);
    final Paint more = text(13f, false);
    final Paint pillTitle = text(17f, true);
    final Paint pillSubtitle = text(13f, false);
    final Paint pillTrailing = tabular(text(24f, true));
    final Paint pillVerb = text(16f, true);

    GenFonts() {
        eyebrow.setLetterSpacing(0.07f);
    }

    private static Paint text(float size, boolean medium) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(medium ? MEDIUM : REGULAR);
        paint.setTextSize(size);
        paint.setColor(GenColors.INK);
        return paint;
    }

    private static Paint tabular(Paint paint) {
        paint.setFontFeatureSettings("tnum");
        return paint;
    }
}
