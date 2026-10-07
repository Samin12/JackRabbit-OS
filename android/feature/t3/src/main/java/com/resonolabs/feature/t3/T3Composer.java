package com.resonolabs.feature.t3;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.resonolabs.ui.design.SamTheme;

/**
 * Text entry with the AOSP keyboard, following SettingsPanelView.connectOpenAiFromSettings:
 * an AlertDialog holding an EditText, keyboard forced visible. The IME action key sends.
 */
final class T3Composer {
    interface Submit {
        void text(String value);
    }

    private T3Composer() {}

    static AlertDialog open(Activity activity, String title, String hint, String action, Submit submit) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setTextColor(SamTheme.INK);
        field.setHintTextColor(SamTheme.MUTED);
        field.setTextSize(19f);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setMinLines(2);
        field.setMaxLines(4);
        field.setHorizontallyScrolling(false);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // Raw input type without MULTI_LINE keeps a Send key on the keyboard while still wrapping.
        field.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        field.setPadding(22, 16, 22, 16);

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(28, 8, 28, 4);
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(SamTheme.MUTED);
        heading.setTextSize(15f);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sheet.addView(heading);
        sheet.addView(field, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(sheet);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroller)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, (ignored, which) -> deliver(field, submit))
                .create();
        field.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                if (!field.getText().toString().isBlank()) {
                    deliver(field, submit);
                    dialog.dismiss();
                }
                return true;
            }
            return false;
        });
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                            | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            field.requestFocus();
            field.postDelayed(() -> {
                InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }, 160L);
        });
        dialog.show();
        return dialog;
    }

    /** Read-only full text (an approval command or a long question), scrollable. */
    static void reveal(Activity activity, String title, String body, boolean monospace) {
        TextView text = new TextView(activity);
        text.setText(body);
        text.setTextColor(SamTheme.INK);
        text.setTextSize(monospace ? 14f : 17f);
        if (monospace) text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextIsSelectable(false);
        text.setPadding(32, 12, 32, 12);
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(text);
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(scroller)
                .setPositiveButton("Close", null)
                .show();
    }

    private static void deliver(EditText field, Submit submit) {
        String value = field.getText().toString().trim();
        if (!value.isEmpty()) submit.text(value);
    }
}
