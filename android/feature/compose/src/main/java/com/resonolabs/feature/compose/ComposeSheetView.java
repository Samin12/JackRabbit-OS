package com.resonolabs.feature.compose;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.GlassPainter;
import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;

import java.util.function.BooleanSupplier;

/**
 * The sheet's single view: Canvas-drawn chrome in the 480x640 logical space around one real
 * EditText (needed for the AOSP keyboard). Two layouts, picked from the keyboard inset:
 *
 * <pre>
 * keyboard up (compact)                 keyboard down (full)
 * ┌───────────────────────────────┐     ┌───────────────────────────────┐
 * │ ✕  Reply to “Fix login”       │     │ ░░░░░░░░░ scrim ░░░░░░░░░░░░░ │
 * │ ┌──────────────────┐ ┌──────┐ │     │╭─────────── ── ──────────────╮│
 * │ │ field            │ │  🎤  │ │     ││ Reply to “Fix login”          ││
 * │ │                  │ ├──────┤ │     ││ ┌───────────────────────────┐ ││
 * │ └──────────────────┘ │ Send │ │     ││ │ field (live dictation)    │ ││
 * ╰──────────────────────┴──────┴─╯     ││ └───────────────────────────┘ ││
 * │ keyboard                      │     ││        Listening…             ││
 *                                       ││          ( orb )              ││
 *                                       ││ [Cancel]  [⌨ Type]  [ Send ]  ││
 * </pre>
 */
@SuppressLint("ViewConstructor")
final class ComposeSheetView extends FrameLayout implements DictationSession.Listener {
    private static final String LOG_TAG = "SamCompose";
    private static final float W = 480f;
    private static final float H = 640f;
    private static final float SHEET_TOP = 44f;
    /** Secret fields have one line and no mic: a shorter bottom sheet. */
    private static final float SECRET_SHEET_TOP = 300f;
    private static final float DEFAULT_IME = 400f;

    private static final int NONE = -1;
    private static final int MIC = 0;
    private static final int CANCEL = 1;
    private static final int TYPE = 2;
    private static final int SEND = 3;
    private static final int[] WHEEL = {MIC, CANCEL, TYPE, SEND};

    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    /** Keyboard height (logical px) seen last, to lay out before the next keyboard reports in. */
    private static float lastImeHeight = DEFAULT_IME;

    private final Activity activity;
    private final Dialog dialog;
    private final ComposeSheet.Options options;
    private final ComposeSheet.Submit submit;
    private final BooleanSupplier voiceLive;
    private final DictationSession dictation;
    private final EditText field;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint measurePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final GlassPainter glass = new GlassPainter();
    private final FluidOrb orb = new FluidOrb().setColor(SamTheme.ORB_BLUE).setSpeed(1.5f);
    private final ForegroundColorSpan liveWords = new ForegroundColorSpan(SamTheme.ORB_PALE);
    private final InputMethodManager keyboard;

    private final RectF sheet = new RectF();
    private final RectF fieldBox = new RectF();
    private final RectF micBox = new RectF();
    private final RectF cancelBox = new RectF();
    private final RectF typeBox = new RectF();
    private final RectF sendBox = new RectF();
    private final RectF scratch = new RectF();
    private float micCx;
    private float micCy;
    private float micR;
    private RadialGradient micFill;
    private float micFillKey;

    private boolean compact;
    private float imeHeight;
    private boolean keyboardRequested;
    private float fieldTextSize;
    private float titleWidth;
    private String title = "";
    private int pressed = NONE;
    private int focus = MIC;
    private boolean focusVisible;
    private boolean closed;
    private boolean finished;

    private String before = "";
    private String after = "";
    private boolean dictationApplied;
    private boolean clipped;
    private boolean pendingSend;
    private boolean pendingKeyboard;
    private boolean applyingText;
    private String message = "";
    private String status = "";
    /** {@link #status} fitted to the full sheet width / the compact title row (computed off the draw path). */
    private String statusShown = "";
    private String compactLine = "";
    private int statusColor = SamTheme.MUTED;
    private long countdownSecond = -1L;
    private int counterLength = -1;
    private String counterText = "";
    private String countdown = "";
    private float level;

    private float downX;
    private float downY;
    private boolean edgeTracking;
    private boolean edgeSwipe;

    private final Runnable keyboardTimeout = () -> {
        if (keyboardRequested && imeHeight <= 0f) {
            keyboardRequested = false;
            relayout();
        }
    };

    ComposeSheetView(Activity activity, Dialog dialog, ComposeSheet.Options options, ComposeSheet.Submit submit,
                     BooleanSupplier voiceLive) {
        super(activity);
        this.activity = activity;
        this.dialog = dialog;
        this.options = options;
        this.submit = submit;
        this.voiceLive = voiceLive;
        this.dictation = options.secret ? null : new DictationSession(activity, voiceLive, this);
        this.keyboard = activity.getSystemService(InputMethodManager.class);
        setWillNotDraw(false);
        setClipChildren(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription(options.title.isEmpty() ? "Compose" : options.title);

        field = new EditText(activity);
        field.setBackground(null);
        field.setTextColor(SamTheme.INK);
        field.setHintTextColor(SamTheme.withAlpha(SamTheme.MUTED, 200));
        field.setHighlightColor(SamTheme.withAlpha(SamTheme.ORB_BLUE, 120));
        field.setTypeface(REGULAR);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setIncludeFontPadding(true);
        GradientDrawable cursor = new GradientDrawable();
        cursor.setColor(SamTheme.ORB_PALE);
        cursor.setSize(Math.max(2, Math.round(2 * getResources().getDisplayMetrics().density)), 1);
        field.setTextCursorDrawable(cursor);
        if (options.secret) {
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        } else {
            // Wraps like a multi-line field but keeps the keyboard's action key (Send) instead of Enter.
            field.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
            field.setHorizontallyScrolling(false);
            field.setMinLines(1);
            field.setMaxLines(Integer.MAX_VALUE);
            field.setVerticalScrollBarEnabled(true);
            field.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        }
        // AOSP LatinIME: hide its voice key ("nm" = legacy name); there is no recognizer behind it.
        field.setPrivateImeOptions("nm,com.android.inputmethod.latin.noMicrophoneKey");
        field.setImeActionLabel(options.action, options.secret ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_SEND);
        field.setHint(!options.hint.isEmpty() ? options.hint
                : options.secret ? "" : "Type, or tap the mic and talk");
        if (options.maxLength > 0) field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(options.maxLength)});
        if (!options.initialText.isEmpty()) {
            field.setText(options.initialText);
            field.setSelection(field.length());
        }
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) {
                if (!applyingText && !message.isEmpty() && (dictation == null || !dictation.active())) {
                    message = "";
                    updateStatus();
                }
                invalidate();
            }
        });
        field.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                deliver();
                return true;
            }
            return false;
        });
        field.setOnTouchListener((view, event) -> {
            if (dictation == null || !dictation.active()) return false;
            // Touching the field while dictating: stop listening, then hand over to the keyboard.
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                pendingKeyboard = true;
                stopListening();
            }
            return true;
        });
        field.setOnFocusChangeListener((view, hasFocus) -> invalidate());
        addView(field, new LayoutParams(0, 0));

        setOnApplyWindowInsetsListener((view, insets) -> {
            float scaleY = getHeight() > 0 ? getHeight() / H : 1f;
            float ime = insets.getInsets(WindowInsets.Type.ime()).bottom / scaleY;
            if (Math.abs(ime - imeHeight) >= 1f) {
                imeHeight = ime;
                if (ime > 0f) {
                    lastImeHeight = ime;
                    keyboardRequested = false;
                }
                Log.i(LOG_TAG, "keyboard inset=" + Math.round(ime));
                relayout();
            }
            return insets;
        });
        focus = options.secret ? TYPE : MIC;
        updateStatus();
    }

    // ---- lifecycle (from ComposeSheet) --------------------------------------------------------

    void onShown() {
        if (options.voiceFirst && dictation != null) {
            postDelayed(this::startListening, 250L);
        } else {
            keyboardRequested = true;
            relayout();
            postDelayed(this::showKeyboard, 160L);
        }
    }

    void onHostPaused() {
        if (dictation != null && dictation.active()) dictation.pause();
    }

    void onCancelled() {
        finished = true;
        if (dictation != null) dictation.cancel();
        hideKeyboard();
    }

    void onClosed() {
        closed = true;
        removeCallbacks(keyboardTimeout);
        if (dictation != null) dictation.cancel();
    }

    /** The wheel and center key drive the big-sheet buttons; with the keyboard up they edit text. */
    boolean wantsHardwareKeys() {
        return !compact;
    }

    void onInput(UiInputIntent intent) {
        switch (intent) {
            case NEXT, PREVIOUS -> {
                int index = 0;
                for (int i = 0; i < WHEEL.length; i++) if (WHEEL[i] == focus) index = i;
                for (int step = 0; step < WHEEL.length; step++) {
                    index = Math.floorMod(index + (intent == UiInputIntent.NEXT ? 1 : -1), WHEEL.length);
                    if (WHEEL[index] != MIC || dictation != null) break;
                }
                focus = WHEEL[index];
                focusVisible = true;
            }
            case ACTIVATE -> activate(focus);
            case BACK -> dialog.cancel();
        }
        invalidate();
    }

    // ---- actions ------------------------------------------------------------------------------

    private void activate(int control) {
        message = "";
        switch (control) {
            case MIC -> {
                if (dictation == null) return;
                if (dictation.active()) stopListening();
                else startListening();
            }
            case CANCEL -> dialog.cancel();
            case TYPE -> {
                if (dictation != null && dictation.active()) {
                    pendingKeyboard = true;
                    stopListening();
                } else {
                    showKeyboard();
                }
            }
            case SEND -> deliver();
            default -> { }
        }
        updateStatus();
        invalidate();
    }

    private void startListening() {
        if (dictation == null || closed || finished || dictation.active()) return;
        if (!dictation.available()) {
            message = "Voice chat is on. End it to dictate.";
            updateStatus();
            invalidate();
            return;
        }
        Editable text = field.getText();
        int start = field.getSelectionStart();
        int end = field.getSelectionEnd();
        if (start < 0 || end < 0) start = end = text.length();
        int low = Math.min(start, end);
        int high = Math.max(start, end);
        before = text.subSequence(0, low).toString();
        after = text.subSequence(high, text.length()).toString();
        dictationApplied = false;
        clipped = false;
        pendingSend = false;
        pendingKeyboard = false;
        message = "";
        field.setShowSoftInputOnFocus(false);
        hideKeyboard();
        if (!dictation.start()) {
            field.setShowSoftInputOnFocus(true);
            message = "Couldn't start dictation.";
        }
        focus = MIC;
        updateStatus();
        relayout();
    }

    private void stopListening() {
        if (dictation == null) return;
        DictationMachine.Phase phase = dictation.phase();
        if (phase == DictationMachine.Phase.CONNECTING || phase == DictationMachine.Phase.LISTENING) dictation.stop();
        updateStatus();
        invalidate();
    }

    private void deliver() {
        if (finished) return;
        if (dictation != null && dictation.active()) {
            // Send once the last words are transcribed.
            pendingSend = true;
            stopListening();
            return;
        }
        // Secrets are handed over exactly as typed (a password may start or end with a space).
        String value = options.secret ? field.getText().toString() : field.getText().toString().trim();
        if (value.isEmpty()) {
            message = options.secret ? "" : "Type something or tap the mic.";
            updateStatus();
            invalidate();
            return;
        }
        finished = true;
        hideKeyboard();
        dialog.dismiss();
        submit.text(value);
    }

    private void showKeyboard() {
        if (closed || finished) return;
        field.setShowSoftInputOnFocus(true);
        field.requestFocus();
        keyboardRequested = true;
        relayout();
        if (keyboard != null) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        removeCallbacks(keyboardTimeout);
        postDelayed(keyboardTimeout, 1500L);
    }

    private void hideKeyboard() {
        keyboardRequested = false;
        removeCallbacks(keyboardTimeout);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    // ---- dictation callbacks ------------------------------------------------------------------

    @Override public void onDictationPhase(DictationMachine.Phase phase) {
        updateStatus();
        relayout();
    }

    @Override public void onDictationText(String words) {
        applyWords(words, true);
    }

    @Override public void onDictationEnded(String words, DictationMachine.Stop reason, String failure) {
        if (!words.isEmpty() || dictationApplied) applyWords(words, false);
        else field.getText().removeSpan(liveWords);
        field.setShowSoftInputOnFocus(true);
        message = switch (reason) {
            case FAILED -> failure.isEmpty() ? "Dictation stopped." : failure;
            case NO_SPEECH -> "Didn't catch that. Tap the mic to try again.";
            case MAX_DURATION -> "That's the 1-minute limit. Tap the mic to go on.";
            case VOICE_SESSION -> "Voice chat started, so dictation stopped.";
            default -> clipped ? "That's the " + options.maxLength + "-character limit." : "";
        };
        dictationApplied = false;
        if (pendingSend && !finished) {
            pendingSend = false;
            pendingKeyboard = false;
            deliver();
            return;
        }
        if (pendingKeyboard) {
            pendingKeyboard = false;
            showKeyboard();
        } else if (!field.getText().toString().isBlank()) {
            focus = SEND;
        }
        updateStatus();
        relayout();
    }

    private void applyWords(String words, boolean live) {
        DictationText.Splice splice = DictationText.splice(before, words, after, options.maxLength);
        clipped = splice.clipped;
        applyingText = true;
        Editable text = field.getText();
        text.removeSpan(liveWords);
        text.replace(0, text.length(), splice.text);
        if (live && splice.end > splice.start && splice.end <= text.length()) {
            text.setSpan(liveWords, splice.start, splice.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        field.setSelection(Math.min(splice.cursor, text.length()));
        applyingText = false;
        dictationApplied = true;
        field.post(() -> field.bringPointIntoView(Math.min(field.getSelectionEnd(), field.length())));
    }

    private void updateStatus() {
        DictationMachine.Phase phase = dictation == null ? DictationMachine.Phase.IDLE : dictation.phase();
        statusColor = SamTheme.MUTED;
        switch (phase) {
            case CONNECTING -> status = "Starting the mic…";
            case LISTENING -> {
                status = "Listening…";
                statusColor = SamTheme.ORB_PALE;
            }
            case FINISHING -> status = pendingSend ? "Finishing, then sending…" : "Finishing…";
            default -> {
                if (!message.isEmpty()) {
                    status = message;
                    statusColor = SamTheme.AMBER;
                } else if (dictation == null) {
                    status = "";
                } else if (voiceLive.getAsBoolean()) {
                    status = "Voice chat is on. End it to dictate.";
                } else if (field.getText().toString().isBlank()) {
                    status = "Tap the mic and talk";
                } else {
                    status = "Tap the mic to add more";
                }
            }
        }
        countdownSecond = -1L;
        statusShown = TextUtils.ellipsize(status, textPaint(16f), 440f, TextUtils.TruncateAt.END).toString();
        compactLine = TextUtils.ellipsize(status, textPaint(15f), 288f, TextUtils.TruncateAt.END).toString();
    }

    // ---- layout ---------------------------------------------------------------------------------

    private void relayout() {
        boolean listening = dictation != null && dictation.active();
        boolean nextCompact = !listening && (imeHeight > 0f || keyboardRequested);
        if (nextCompact != compact) {
            compact = nextCompact;
            pressed = NONE;
        }
        computeRects();
        requestLayout();
        invalidate();
    }

    private void computeRects() {
        if (compact) {
            float keyboardTop = H - (imeHeight > 0f ? imeHeight : lastImeHeight);
            float bottom = Math.max(120f, keyboardTop - 6f);
            sheet.set(0f, -40f, W, bottom);
            cancelBox.set(4f, 2f, 56f, 50f);
            float column = options.secret ? 0f : 1f;
            float buttonH = Math.max(44f, Math.min(58f, (bottom - 18f - 8f) / 2f));
            sendBox.set(366f, 10f, 470f, 10f + buttonH);
            if (column > 0f) {
                micBox.set(366f, 10f, 470f, 10f + buttonH);
                sendBox.set(366f, micBox.bottom + 8f, 470f, micBox.bottom + 8f + buttonH);
            } else {
                micBox.setEmpty();
            }
            float fieldBottom = Math.max(options.secret ? 104f : 54f + 52f, bottom - 10f);
            if (options.secret) {
                // One masked line: the sheet hugs it instead of reaching down to the keyboard.
                fieldBottom = Math.min(fieldBottom, 112f);
                sheet.bottom = Math.max(sendBox.bottom, fieldBottom) + 14f;
            }
            fieldBox.set(10f, 52f, 356f, fieldBottom);
            typeBox.setEmpty();
            titleWidth = 356f - 62f;
        } else {
            sheet.set(0f, options.secret ? SECRET_SHEET_TOP : SHEET_TOP, W, H + 40f);
            float fieldTop = sheet.top + 68f;
            float fieldBottom = options.secret ? fieldTop + 64f : 300f;
            fieldBox.set(20f, fieldTop, 460f, fieldBottom);
            micCx = 240f;
            micCy = 436f;
            micR = 56f;
            micBox.set(micCx - micR - 18f, micCy - micR - 18f, micCx + micR + 18f, micCy + micR + 18f);
            if (options.secret) micBox.setEmpty();
            cancelBox.set(20f, 554f, 150f, 610f);
            typeBox.set(160f, 554f, 320f, 610f);
            sendBox.set(330f, 554f, 460f, 610f);
            titleWidth = 424f;
        }
        String raw = options.title;
        title = raw.isEmpty() ? "" : TextUtils.ellipsize(raw, textPaint(compact ? 15f : 17f), titleWidth,
                TextUtils.TruncateAt.END).toString();
        float size = compact ? 21f : 24f;
        if (size != fieldTextSize && getWidth() > 0) {
            fieldTextSize = size;
            field.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * getWidth() / W);
        }
    }

    private TextPaint textPaint(float size) {
        measurePaint.setTypeface(REGULAR);
        measurePaint.setTextSize(size);
        return measurePaint;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        fieldTextSize = 0f;
        computeRects();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        float sx = width / W;
        float sy = height / H;
        int fieldWidth = Math.max(1, Math.round(fieldBox.width() * sx));
        int fieldHeight = Math.max(1, Math.round(fieldBox.height() * sy));
        field.measure(MeasureSpec.makeMeasureSpec(fieldWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(fieldHeight, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float sx = getWidth() / W;
        float sy = getHeight() / H;
        int padX = Math.round(14f * sx);
        int padY = Math.round((options.secret ? 16f : 10f) * sy);
        field.setPadding(padX, padY, padX, padY);
        field.layout(Math.round(fieldBox.left * sx), Math.round(fieldBox.top * sy),
                Math.round(fieldBox.right * sx), Math.round(fieldBox.bottom * sy));
    }

    // ---- drawing ------------------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        if (getWidth() == 0) return;
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(compact ? 150 : 170, 2, 3, 6));
        canvas.drawRect(0f, 0f, W, H, paint);
        if (compact) drawCompact(canvas);
        else drawFull(canvas);
        canvas.restore();
        if (dictation != null && dictation.active()) postInvalidateOnAnimation();
    }

    private void drawSheet(Canvas canvas, float radius) {
        glass.fillVertical(canvas, paint, sheet.left, sheet.top, sheet.right, sheet.bottom, radius,
                SamTheme.PANEL_RAISED, SamTheme.PANEL, Math.max(1f, sheet.height()));
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(SamTheme.withAlpha(SamTheme.ORB_PALE, 60));
        canvas.drawRoundRect(sheet.left + 0.6f, sheet.top + 0.6f, sheet.right - 0.6f, sheet.bottom - 0.6f,
                radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawCompact(Canvas canvas) {
        drawSheet(canvas, 22f);
        // ✕
        boolean cancelPressed = pressed == CANCEL;
        if (cancelPressed) {
            paint.setColor(SamTheme.withAlpha(SamTheme.INK, 26));
            canvas.drawCircle(cancelBox.centerX(), cancelBox.centerY(), 22f, paint);
        }
        drawCross(canvas, cancelBox.centerX(), cancelBox.centerY(), 8f, cancelPressed ? SamTheme.INK : SamTheme.MUTED);
        // context line (or the latest message)
        boolean showMessage = !message.isEmpty();
        SamTheme.text(canvas, paint, showMessage ? compactLine : title, 60f, 32f, 15f,
                showMessage ? SamTheme.AMBER : SamTheme.MUTED, Paint.Align.LEFT, false);
        drawCounter(canvas, 352f, 32f);
        glass.draw(canvas, paint, fieldBox, 16f, field.hasFocus());
        if (!micBox.isEmpty()) {
            // The mic reads as the voice way in: blue-tinted glass with a pale edge.
            float radius = micBox.height() / 2f;
            glass.fillVertical(canvas, paint, micBox.left, micBox.top, micBox.right, micBox.bottom, radius,
                    SamTheme.withAlpha(SamTheme.ORB_BLUE, 120), SamTheme.withAlpha(SamTheme.ORB_BLUE, 60),
                    micBox.height());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(SamTheme.withAlpha(SamTheme.ORB_PALE, 150));
            canvas.drawRoundRect(micBox, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            if (pressed == MIC) {
                paint.setColor(SamTheme.withAlpha(SamTheme.INK, 34));
                canvas.drawRoundRect(micBox, radius, radius, paint);
            }
            drawMicGlyph(canvas, micBox.centerX(), micBox.centerY(), Math.min(30f, micBox.height() * 0.5f), SamTheme.INK);
        }
        drawPill(canvas, sendBox, options.action, true, focusVisible && focus == SEND, pressed == SEND, canSend());
    }

    private void drawFull(Canvas canvas) {
        drawSheet(canvas, 28f);
        paint.setColor(SamTheme.withAlpha(SamTheme.MUTED, 110));
        canvas.drawRoundRect(222f, sheet.top + 9f, 258f, sheet.top + 13f, 2f, 2f, paint);
        if (!title.isEmpty()) {
            SamTheme.text(canvas, paint, title, 26f, sheet.top + 50f, 17f, SamTheme.MUTED, Paint.Align.LEFT, false);
        }
        drawCounter(canvas, 454f, sheet.top + 50f);
        boolean listening = dictation != null && dictation.active();
        glass.draw(canvas, paint, fieldBox, 18f, listening || field.hasFocus());
        if (!options.secret) {
            String line = status;
            if (dictation != null && dictation.phase() == DictationMachine.Phase.LISTENING) {
                long remaining = dictation.remainingMs();
                if (remaining <= 10_000L) {
                    long seconds = (remaining + 999L) / 1000L;
                    if (seconds != countdownSecond) {
                        countdownSecond = seconds;
                        countdown = "Listening… 0:" + (seconds < 10 ? "0" : "") + seconds + " left";
                    }
                    line = countdown;
                }
            }
            SamTheme.text(canvas, paint, line == countdown ? line : statusShown, W / 2f, 336f, 16f, statusColor,
                    Paint.Align.CENTER, false);
            drawMic(canvas);
        } else {
            SamTheme.text(canvas, paint, message.isEmpty() ? "Typed only. Never dictated." : statusShown,
                    W / 2f, fieldBox.bottom + 40f, 15f, message.isEmpty() ? SamTheme.MUTED : SamTheme.AMBER,
                    Paint.Align.CENTER, false);
        }
        drawPill(canvas, cancelBox, "Cancel", false, focusVisible && focus == CANCEL, pressed == CANCEL, true);
        drawPill(canvas, typeBox, "Type", false, focusVisible && focus == TYPE, pressed == TYPE, true);
        drawKeyboardGlyph(canvas, typeBox.left + 34f, typeBox.centerY());
        drawPill(canvas, sendBox, options.action, true, focusVisible && focus == SEND, pressed == SEND, canSend());
    }

    private void drawCounter(Canvas canvas, float right, float baseline) {
        if (options.maxLength <= 0) return;
        int length = field.length();
        if (!DictationText.nearLimit(length, options.maxLength)) return;
        int color = length >= options.maxLength ? SamTheme.RED
                : length >= options.maxLength * 0.95f ? SamTheme.AMBER : SamTheme.MUTED;
        if (length != counterLength) {
            counterLength = length;
            counterText = length + "/" + options.maxLength;
        }
        SamTheme.text(canvas, paint, counterText, right, baseline, 13f, color, Paint.Align.RIGHT, false);
    }

    private boolean canSend() {
        return field.length() > 0 && !field.getText().toString().isBlank();
    }

    private void drawPill(Canvas canvas, RectF box, String label, boolean primary, boolean focused, boolean down,
                          boolean enabled) {
        float radius = box.height() / 2f;
        if (primary) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(SamTheme.withAlpha(SamTheme.ORB_BLUE, enabled ? 255 : 90));
            canvas.drawRoundRect(box, radius, radius, paint);
            if (focused) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f);
                paint.setColor(SamTheme.ORB_PALE);
                scratch.set(box.left - 4f, box.top - 4f, box.right + 4f, box.bottom + 4f);
                canvas.drawRoundRect(scratch, radius + 4f, radius + 4f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        } else {
            glass.draw(canvas, paint, box, radius, focused);
        }
        if (down) {
            paint.setShader(null);
            paint.setColor(SamTheme.withAlpha(SamTheme.INK, 34));
            canvas.drawRoundRect(box, radius, radius, paint);
        }
        if (label == null) return;
        boolean withGlyph = box == typeBox;
        float x = withGlyph ? box.centerX() + 14f : box.centerX();
        SamTheme.text(canvas, paint, label, x, box.centerY() + 6.5f, 18f,
                enabled ? SamTheme.INK : SamTheme.withAlpha(SamTheme.INK, 120), Paint.Align.CENTER, true);
    }

    private void drawMic(Canvas canvas) {
        DictationMachine.Phase phase = dictation.phase();
        long now = SystemClock.uptimeMillis();
        boolean down = pressed == MIC;
        if (focusVisible && focus == MIC) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            paint.setColor(SamTheme.withAlpha(SamTheme.ORB_PALE, 200));
            canvas.drawCircle(micCx, micCy, micR + 12f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        switch (phase) {
            case LISTENING, FINISHING -> {
                boolean listening = phase == DictationMachine.Phase.LISTENING;
                level += ((listening ? dictation.level() : 0f) - level) * 0.35f;
                if (listening) {
                    float speech = dictation.speechActive() ? 1f : 0.45f;
                    for (int ring = 0; ring < 2; ring++) {
                        float t = ((now % 1600L) / 1600f + ring * 0.5f) % 1f;
                        float radius = micR * (1.04f + t * (0.42f + level * 0.35f));
                        paint.setShader(null);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(2f + level * 2f);
                        paint.setColor(SamTheme.withAlpha(SamTheme.ORB_PALE, Math.round((1f - t) * 110f * speech)));
                        canvas.drawCircle(micCx, micCy, radius, paint);
                    }
                    paint.setStyle(Paint.Style.FILL);
                }
                orb.setEnergy(listening ? 0.3f + level * 0.7f : 0.1f);
                orb.draw(canvas, micCx, micCy, micR * (listening ? 0.94f + level * 0.08f : 0.86f));
                paint.setShader(null);
                if (listening) {
                    // Stop: a dark disc with a rounded square, legible on the bright orb.
                    paint.setColor(SamTheme.withAlpha(SamTheme.BACKGROUND, down ? 200 : 150));
                    canvas.drawCircle(micCx, micCy, micR * 0.36f, paint);
                    paint.setColor(SamTheme.INK);
                    float half = micR * 0.13f;
                    canvas.drawRoundRect(micCx - half, micCy - half, micCx + half, micCy + half, 3f, 3f, paint);
                } else {
                    drawSpinner(canvas, now, SamTheme.withAlpha(SamTheme.INK, 200));
                }
            }
            case CONNECTING -> {
                drawMicButton(canvas, down, 200);
                drawSpinner(canvas, now, SamTheme.ORB_PALE);
            }
            default -> {
                boolean blocked = voiceLive.getAsBoolean();
                drawMicButton(canvas, down, blocked ? 70 : 255);
                if (blocked) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeWidth(4f);
                    paint.setColor(SamTheme.INK);
                    canvas.drawLine(micCx - 22f, micCy - 22f, micCx + 22f, micCy + 22f, paint);
                    paint.setStrokeCap(Paint.Cap.BUTT);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    private void drawMicButton(Canvas canvas, boolean down, int alpha) {
        if (micFill == null || micFillKey != micCx * 1000f + micCy + micR) {
            micFillKey = micCx * 1000f + micCy + micR;
            micFill = new RadialGradient(micCx - micR * 0.3f, micCy - micR * 0.4f, micR * 1.5f,
                    SamTheme.CYAN, SamTheme.ORB_BLUE, Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(SamTheme.withAlpha(SamTheme.ORB_BLUE, Math.round(alpha * 0.22f)));
        canvas.drawCircle(micCx, micCy, micR + 10f, paint);
        paint.setColor(Color.argb(alpha, 0, 0, 0));
        paint.setShader(micFill);
        canvas.drawCircle(micCx, micCy, down ? micR - 3f : micR, paint);
        paint.setShader(null);
        drawMicGlyph(canvas, micCx, micCy, micR * 0.82f, SamTheme.withAlpha(SamTheme.INK, alpha));
    }

    private void drawSpinner(Canvas canvas, long now, int color) {
        float sweepStart = (now % 1000L) / 1000f * 360f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3.5f);
        paint.setColor(color);
        scratch.set(micCx - micR - 9f, micCy - micR - 9f, micCx + micR + 9f, micCy + micR + 9f);
        canvas.drawArc(scratch, sweepStart, 80f, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Microphone: capsule, cradle arc, stem and base; {@code size} = overall height. */
    private void drawMicGlyph(Canvas canvas, float cx, float cy, float size, int color) {
        float u = size;
        paint.setShader(null);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        float capsuleW = u * 0.30f;
        scratch.set(cx - capsuleW / 2f, cy - u * 0.48f, cx + capsuleW / 2f, cy + u * 0.10f);
        canvas.drawRoundRect(scratch, capsuleW / 2f, capsuleW / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, u * 0.075f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        scratch.set(cx - u * 0.27f, cy - u * 0.28f, cx + u * 0.27f, cy + u * 0.26f);
        canvas.drawArc(scratch, 0f, 180f, false, paint);
        canvas.drawLine(cx, cy + u * 0.26f, cx, cy + u * 0.44f, paint);
        canvas.drawLine(cx - u * 0.16f, cy + u * 0.46f, cx + u * 0.16f, cy + u * 0.46f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawKeyboardGlyph(Canvas canvas, float cx, float cy) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(SamTheme.INK);
        scratch.set(cx - 14f, cy - 10f, cx + 14f, cy + 10f);
        canvas.drawRoundRect(scratch, 4f, 4f, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 4; col++) {
                canvas.drawCircle(cx - 8.4f + col * 5.6f, cy - 4.5f + row * 5f, 1.4f, paint);
            }
        }
        canvas.drawRoundRect(cx - 7f, cy + 4.2f, cx + 7f, cy + 6.4f, 1f, 1f, paint);
    }

    private void drawCross(Canvas canvas, float cx, float cy, float half, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.6f);
        paint.setColor(color);
        canvas.drawLine(cx - half, cy - half, cx + half, cy + half, paint);
        canvas.drawLine(cx - half, cy + half, cx + half, cy - half, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---- touch --------------------------------------------------------------------------------

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        float sx = getWidth() / W;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = event.getX();
                downY = event.getY();
                edgeTracking = downX <= 24f * sx;
                edgeSwipe = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (edgeTracking) {
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (dx >= 16f * sx && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        edgeSwipe = true;
                        return true;
                    }
                }
            }
            default -> { }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float sx = getWidth() / W;
        float sy = getHeight() / H;
        float x = event.getX() / sx;
        float y = event.getY() / sy;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = event.getX();
                downY = event.getY();
                edgeTracking = downX <= 24f * sx;
                edgeSwipe = false;
                pressed = hit(x, y);
                focusVisible = false;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (edgeTracking && !edgeSwipe && event.getX() - downX >= 16f * sx) {
                    edgeSwipe = true;
                    pressed = NONE;
                    invalidate();
                }
                if (pressed != NONE && hit(x, y) != pressed) {
                    pressed = NONE;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                int control = pressed;
                pressed = NONE;
                if (edgeSwipe) {
                    edgeSwipe = false;
                    if (event.getX() - downX >= 72f * sx) dialog.cancel();
                    invalidate();
                    return true;
                }
                if (control != NONE && hit(x, y) == control) {
                    focus = control;
                    activate(control);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                pressed = NONE;
                edgeSwipe = false;
                invalidate();
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    private int hit(float x, float y) {
        if (compact) {
            if (contains(cancelBox, x, y, 4f)) return CANCEL;
            if (!micBox.isEmpty() && contains(micBox, x, y, 4f)) return MIC;
            if (contains(sendBox, x, y, 4f)) return SEND;
            return NONE;
        }
        if (!micBox.isEmpty()) {
            float dx = x - micCx;
            float dy = y - micCy;
            if (dx * dx + dy * dy <= (micR + 20f) * (micR + 20f)) return MIC;
        }
        if (contains(cancelBox, x, y, 6f)) return CANCEL;
        if (contains(typeBox, x, y, 6f)) return TYPE;
        if (contains(sendBox, x, y, 6f)) return SEND;
        return NONE;
    }

    private static boolean contains(RectF box, float x, float y, float slop) {
        return x >= box.left - slop && x <= box.right + slop && y >= box.top - slop && y <= box.bottom + slop;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        onClosed();
    }
}
