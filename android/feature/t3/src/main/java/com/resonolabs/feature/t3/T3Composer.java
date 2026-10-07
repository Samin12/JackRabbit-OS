package com.resonolabs.feature.t3;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.resonolabs.ui.design.SamTheme;

/**
 * Text entry with the AOSP keyboard. On the R1 the keyboard plus its nav strip cover the bottom
 * ~75% of the 640px screen, so instead of a centered AlertDialog (whose field and buttons end up
 * under the keyboard) this is one compact bar pinned to the top: [✕] [field] [send]. The IME's
 * Send key also sends; ✕ or Back cancels.
 */
final class T3Composer {
    interface Submit {
        void text(String value);
    }

    private T3Composer() {}

    static Dialog open(Activity activity, String hint, String action, Submit submit) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 6), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(SamTheme.PANEL_RAISED);
        float radius = dp(activity, 18);
        panel.setCornerRadii(new float[]{0, 0, 0, 0, radius, radius, radius, radius});
        panel.setStroke(Math.max(1, dp(activity, 1) / 2), SamTheme.withAlpha(SamTheme.ORB_PALE, 70));
        bar.setBackground(panel);

        TextView cancel = new TextView(activity);
        cancel.setText("✕");
        cancel.setTextColor(SamTheme.MUTED);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        cancel.setGravity(Gravity.CENTER);
        cancel.setContentDescription("Cancel");
        bar.addView(cancel, new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 40)));

        EditText field = new EditText(activity);
        field.setHint(hint);
        field.setTextColor(SamTheme.INK);
        field.setHintTextColor(SamTheme.MUTED);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setMinLines(1);
        field.setMaxLines(2);
        field.setHorizontallyScrolling(false);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        // Raw input type without MULTI_LINE keeps a Send key on the keyboard while still wrapping.
        field.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        GradientDrawable well = new GradientDrawable();
        well.setColor(Color.argb(22, 200, 220, 255));
        well.setCornerRadius(dp(activity, 14));
        field.setBackground(well);
        field.setPadding(dp(activity, 12), dp(activity, 7), dp(activity, 12), dp(activity, 7));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        fieldParams.setMargins(dp(activity, 2), 0, dp(activity, 6), 0);
        bar.addView(field, fieldParams);

        TextView send = new TextView(activity);
        send.setText("↑");
        send.setTextColor(SamTheme.INK);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        send.setTypeface(T3Surface.MEDIUM);
        send.setGravity(Gravity.CENTER);
        send.setContentDescription(action);
        GradientDrawable sendBackground = new GradientDrawable();
        sendBackground.setShape(GradientDrawable.OVAL);
        sendBackground.setColor(SamTheme.ORB_BLUE);
        send.setBackground(sendBackground);
        send.setAlpha(0.4f);
        bar.addView(send, new LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 38)));

        dialog.setContentView(bar);
        Runnable deliver = () -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) return;
            dialog.dismiss();
            submit.text(value);
        };
        cancel.setOnClickListener(ignored -> dialog.cancel());
        send.setOnClickListener(ignored -> deliver.run());
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable value) {
                send.setAlpha(value.toString().isBlank() ? 0.4f : 1f);
            }
        });
        field.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                deliver.run();
                return true;
            }
            return false;
        });

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.TOP);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.65f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnShowListener(ignored -> {
            field.requestFocus();
            field.postDelayed(() -> {
                InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }, 160L);
        });
        dialog.show();
        return dialog;
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
