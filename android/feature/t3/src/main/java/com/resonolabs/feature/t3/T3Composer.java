package com.resonolabs.feature.t3;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import com.resonolabs.feature.compose.ComposeSheet;
import com.resonolabs.ui.design.SamTheme;

/**
 * Text entry for replies, "Other…" answers and new-thread prompts: the shared
 * {@link ComposeSheet} (type with the AOSP keyboard or tap the mic and dictate; the keyboard's
 * Send key, the sheet's send button or a finished dictation followed by Send deliver the text;
 * ✕, Cancel or Back discards it).
 */
final class T3Composer {
    /** The runtime refuses longer messages (MAX_MESSAGE_CHARS in domains/t3/service.py). */
    private static final int T3_MAX_MESSAGE_CHARS = 6000;

    interface Submit {
        void text(String value);
    }

    private T3Composer() {}

    static Dialog open(Activity activity, String hint, String action, Submit submit) {
        return ComposeSheet.open(activity, new ComposeSheet.Options()
                .title(hint)
                .action(action)
                .maxLength(T3_MAX_MESSAGE_CHARS), submit::text);
    }

    /** Read-only full text (an approval command, a long question, a code block), scrollable. */
    static void reveal(Activity activity, String title, String body, boolean monospace) {
        TextView text = new TextView(activity);
        text.setText(body);
        text.setTextColor(SamTheme.INK);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, monospace ? 12f : 15f);
        if (monospace) text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setPadding(dp(activity, 18), dp(activity, 6), dp(activity, 18), dp(activity, 6));
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(text);
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroller)
                .setPositiveButton("Close", null)
                .show();
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
