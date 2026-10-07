package com.resonolabs.feature.t3;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One T3 thread: header (back, title, status pill), the conversation (user bubbles right,
 * assistant text left, code as monospace panels, long messages collapsed), a pinned approval or
 * question card, quick replies, and the Type / Talk / Stop composer. The wheel scrolls the
 * conversation; scrolling past the end walks focus through the controls; the center key
 * activates the focused control, or Talk when nothing is focused.
 */
final class T3ThreadView extends View {
    interface Actions {
        void back();
        void send(T3Model.Summary thread, String text);
        void talk(T3Model.Summary thread);
        void stop(T3Model.Summary thread);
        void approve(T3Model.Summary thread, T3Model.Approval approval, String decision);
        void answer(T3Model.Summary thread, T3Model.Input input, JSONObject answers);
    }

    private static final float W = 480f;
    private static final float H = 640f;
    private static final float HEADER_BOTTOM = 86f;
    private static final float VIEW_TOP = 90f;
    private static final float COMPOSER_TOP = 572f;
    private static final float COMPOSER_BOTTOM = 626f;
    private static final float TEXT_SIZE = 16f;
    private static final float LINE = 22.5f;
    private static final float CODE_SIZE = 13f;
    private static final float CODE_LINE = 18f;
    private static final int COLLAPSE_OVER = 18;
    private static final int COLLAPSED_LINES = 10;
    private static final long HANDLED_HIDE_MS = 20_000L;
    private static final long OPTIMISTIC_MS = 8_000L;
    private static final RectF BACK = new RectF(16f, 18f, 68f, 70f);

    private enum Kind { TEXT, BUBBLE, CODE, QUOTE_BAR, BULLET_DOT, MORE, TYPING, NOTICE }

    /** One precomputed drawable in content coordinates (y grows down from the first message). */
    private static final class Piece {
        final Kind kind;
        final RectF rect = new RectF();
        String text = "";
        float size = TEXT_SIZE;
        Typeface face = T3Surface.REGULAR;
        int color = SamTheme.INK;
        String messageId = "";
        boolean pending;
        String full = "";

        Piece(Kind kind) {
            this.kind = kind;
        }
    }

    /** A focusable / tappable control in screen coordinates. */
    private static final class Control {
        final RectF rect = new RectF();
        final Runnable action;
        final String label;
        final int style;

        Control(float left, float top, float right, float bottom, String label, int style, Runnable action) {
            rect.set(left, top, right, bottom);
            this.label = label;
            this.style = style;
            this.action = action;
        }
    }

    private static final int STYLE_GLASS = 0;
    private static final int STYLE_PRIMARY = 1;
    private static final int STYLE_DANGER = 2;
    private static final int STYLE_CHIP = 3;
    private static final int STYLE_CHIP_ON = 4;
    private static final int STYLE_APPROVE = 5;

    private final Activity activity;
    private final T3Surface surface = new T3Surface();
    private final T3Toast toast;
    private final Actions actions;
    private final OverScroller scroller;
    private final RectF rect = new RectF();
    private final List<Piece> pieces = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
    private final Map<String, Long> handled = new HashMap<>();
    private final List<T3Model.Message> localMessages = new ArrayList<>();
    private final Map<String, Object> answers = new HashMap<>();
    private final Set<String> selections = new LinkedHashSet<>();

    private T3Model.Summary summary;
    private T3Model.Detail detail;
    private String fingerprint = "";
    private boolean loadFailed;
    private long optimisticUntil;
    private T3Model.Summary optimistic;

    // header
    private String titleText = "";
    private String pillText = "";
    private String metaText = "";
    private float pillWidth;
    private int statusColor = SamTheme.MUTED;

    // pending card
    private T3Model.Approval cardApproval;
    private T3Model.Input cardInput;
    private int questionIndex;
    private String cardInputId = "";
    private final RectF card = new RectF();
    private final RectF cardText = new RectF();
    private String cardHeader = "";
    private List<String> cardLines = new ArrayList<>();
    private boolean cardMono;
    private boolean cardTruncated;
    private String cardFull = "";

    // scrolling
    private float contentHeight;
    private float viewBottom = COMPOSER_TOP - 8f;
    private float scroll;
    private float scrollTarget;
    private boolean follow = true;
    private int focus = -1;
    private VelocityTracker velocity;
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private long layoutAt;

    T3ThreadView(Activity activity, T3Toast toast, Actions actions) {
        super(activity);
        this.activity = activity;
        this.toast = toast;
        this.actions = actions;
        this.scroller = new OverScroller(activity);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("T3 thread");
    }

    // ---- data ------------------------------------------------------------------------------

    /** Opens a thread with what the list already knows; the detail arrives with the next poll. */
    void open(T3Model.Summary thread) {
        summary = thread;
        detail = null;
        fingerprint = "";
        loadFailed = false;
        expanded.clear();
        localMessages.clear();
        answers.clear();
        selections.clear();
        cardInputId = "";
        questionIndex = 0;
        optimistic = null;
        optimisticUntil = 0L;
        follow = true;
        focus = -1;
        scroll = 0f;
        scrollTarget = 0f;
        scroller.forceFinished(true);
        relayout();
        invalidate();
    }

    String threadId() {
        return summary == null ? "" : summary.id;
    }

    T3Model.Summary summary() {
        return current();
    }

    boolean working() {
        T3Model.Summary thread = current();
        return thread != null && T3Status.working(thread.status);
    }

    void showDetail(T3Model.Detail next) {
        loadFailed = false;
        if (next == null) return;
        if (optimistic != null && (T3Status.working(next.thread.status)
                || SystemClock.uptimeMillis() > optimisticUntil)) {
            optimistic = null;
        }
        summary = next.thread;
        dropEchoedLocalMessages(next);
        String nextPrint = next.fingerprint() + "|" + localMessages.size();
        boolean first = detail == null;
        detail = next;
        if (!first && nextPrint.equals(fingerprint)) {
            relayoutHeader();
            invalidate();
            return;
        }
        fingerprint = nextPrint;
        relayout();
        if (first) {
            scroll = maxScroll();
            scrollTarget = scroll;
        }
        invalidate();
    }

    void showLoadFailure() {
        if (detail == null) {
            loadFailed = true;
            relayout();
            invalidate();
        }
    }

    /** Optimistic user bubble + "working" header right after a send. */
    void addLocalMessage(String text) {
        localMessages.add(new T3Model.Message("local:" + SystemClock.uptimeMillis(), true, text,
                System.currentTimeMillis(), false, true));
        markWorking("Sending…");
        follow = true;
        fingerprint = "";
        relayout();
        scrollTarget = maxScroll();
        invalidate();
    }

    /** A send failed: forget optimistic bubbles and status. */
    void dropLocalMessages() {
        localMessages.clear();
        optimistic = null;
        fingerprint = "";
        relayout();
        invalidate();
    }

    void markWorking(String phase) {
        T3Model.Summary base = summary;
        if (base == null) return;
        optimistic = base.withStatus(T3Status.WORKING, "Working", phase);
        optimisticUntil = SystemClock.uptimeMillis() + OPTIMISTIC_MS;
        relayoutHeader();
        relayoutControls();
        invalidate();
    }

    void markStopped() {
        optimistic = null;
        if (summary != null) summary = summary.withStatus(T3Status.DONE, "Stopping…", "");
        relayout();
        invalidate();
    }

    void markHandled(String requestId) {
        handled.put(requestId, SystemClock.uptimeMillis());
        relayout();
        invalidate();
    }

    void unmarkHandled(String requestId) {
        handled.remove(requestId);
        relayout();
        invalidate();
    }

    private T3Model.Summary current() {
        if (optimistic != null && SystemClock.uptimeMillis() <= optimisticUntil) return optimistic;
        return summary;
    }

    private void dropEchoedLocalMessages(T3Model.Detail next) {
        if (localMessages.isEmpty()) return;
        List<T3Model.Message> keep = new ArrayList<>();
        for (T3Model.Message local : localMessages) {
            boolean echoed = false;
            for (T3Model.Message message : next.messages) {
                if (message.user && message.text.trim().equals(local.text.trim())) {
                    echoed = true;
                    break;
                }
            }
            boolean stale = System.currentTimeMillis() - local.createdAt > 60_000L;
            if (!echoed && !stale) keep.add(local);
        }
        localMessages.clear();
        localMessages.addAll(keep);
    }

    // ---- layout ----------------------------------------------------------------------------

    private void relayout() {
        layoutAt = System.currentTimeMillis();
        relayoutHeader();
        relayoutControls();
        relayoutMessages();
    }

    private void relayoutHeader() {
        T3Model.Summary thread = current();
        if (thread == null) return;
        titleText = surface.ellipsize(thread.title, 462f - 82f, 19f, T3Surface.MEDIUM);
        String label = thread.label();
        if (T3Status.working(thread.status) && !thread.phase.isEmpty()) label = thread.phase;
        pillText = surface.ellipsize(label, 190f, 13f, T3Surface.MEDIUM);
        pillWidth = surface.measure(pillText, 13f, T3Surface.MEDIUM) + 34f;
        statusColor = T3Status.color(thread.status, true);
        StringBuilder meta = new StringBuilder();
        if (!thread.projectTitle.isEmpty()) meta.append(thread.projectTitle);
        String when = T3Time.relative(System.currentTimeMillis(), thread.updatedAt);
        if (!when.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(when);
        metaText = surface.ellipsize(meta.toString(), 462f - 92f - pillWidth, 13.5f, T3Surface.REGULAR);
    }

    private List<T3Model.Approval> openApprovals() {
        List<T3Model.Approval> out = new ArrayList<>();
        if (detail == null) return out;
        long now = SystemClock.uptimeMillis();
        for (T3Model.Approval approval : detail.approvals) {
            Long at = handled.get(approval.requestId);
            if (at == null || now - at > HANDLED_HIDE_MS) out.add(approval);
        }
        return out;
    }

    private List<T3Model.Input> openInputs() {
        List<T3Model.Input> out = new ArrayList<>();
        if (detail == null) return out;
        long now = SystemClock.uptimeMillis();
        for (T3Model.Input input : detail.inputs) {
            Long at = handled.get(input.requestId);
            if (at == null || now - at > HANDLED_HIDE_MS) out.add(input);
        }
        return out;
    }

    /** Builds the pinned card, quick replies and composer; sets {@link #viewBottom}. */
    private void relayoutControls() {
        String focusedLabel = focus >= 0 && focus < controls.size() ? controls.get(focus).label : null;
        controls.clear();
        cardApproval = null;
        cardInput = null;
        card.setEmpty();
        T3Model.Summary thread = current();
        if (thread == null) return;
        boolean working = T3Status.working(thread.status);
        float bottom = COMPOSER_TOP - 10f;

        List<T3Model.Approval> approvals = openApprovals();
        List<T3Model.Input> inputs = openInputs();
        if (!approvals.isEmpty()) {
            bottom = layoutApproval(approvals.get(0), approvals.size(), bottom);
        } else if (!inputs.isEmpty()) {
            bottom = layoutQuestion(inputs.get(0), inputs.size(), bottom);
        } else if (!working && detail != null) {
            bottom = layoutQuickReplies(thread, bottom);
        }
        viewBottom = bottom - 6f;

        // Composer row.
        if (working) {
            controls.add(new Control(16f, COMPOSER_TOP, 156f, COMPOSER_BOTTOM, "Type", STYLE_GLASS, this::type));
            controls.add(new Control(164f, COMPOSER_TOP, 316f, COMPOSER_BOTTOM, "Talk", STYLE_PRIMARY,
                    () -> actions.talk(current())));
            controls.add(new Control(324f, COMPOSER_TOP, 464f, COMPOSER_BOTTOM, "Stop", STYLE_DANGER,
                    () -> actions.stop(current())));
        } else {
            controls.add(new Control(16f, COMPOSER_TOP, 236f, COMPOSER_BOTTOM, "Type", STYLE_GLASS, this::type));
            controls.add(new Control(244f, COMPOSER_TOP, 464f, COMPOSER_BOTTOM, "Talk", STYLE_PRIMARY,
                    () -> actions.talk(current())));
        }
        if (focusedLabel != null) {
            focus = -1;
            for (int i = 0; i < controls.size(); i++) if (controls.get(i).label.equals(focusedLabel)) focus = i;
        } else if (focus >= controls.size()) {
            focus = controls.size() - 1;
        }
    }

    private float layoutApproval(T3Model.Approval approval, int total, float bottom) {
        cardApproval = approval;
        cardMono = true;
        cardHeader = "APPROVAL" + (total > 1 ? "  ·  1 OF " + total : "") + "  ·  " + approval.title().toUpperCase(java.util.Locale.ROOT);
        cardHeader = surface.ellipsize(cardHeader, 400f, 12.5f, T3Surface.MEDIUM);
        String body = approval.detail.isEmpty() ? "No details provided." : approval.detail;
        cardFull = body;
        List<String> lines = surface.wrap(body, 408f, CODE_SIZE, T3Surface.MONO, true);
        cardTruncated = lines.size() > 3;
        cardLines = new ArrayList<>(lines.subList(0, Math.min(3, lines.size())));
        if (cardTruncated) {
            String last = cardLines.get(2);
            cardLines.set(2, surface.ellipsize(last + "…", 380f, CODE_SIZE, T3Surface.MONO));
        }
        float height = 16f + 18f + 10f + cardLines.size() * CODE_LINE + (cardTruncated ? 18f : 0f) + 12f + 48f + 14f;
        float top = bottom - height;
        card.set(16f, top, 464f, bottom);
        cardText.set(card.left, top + 40f, card.right, top + 46f + cardLines.size() * CODE_LINE + (cardTruncated ? 18f : 0f));
        List<T3Model.Option> buttons = approval.buttons();
        float buttonTop = bottom - 14f - 48f;
        float gap = 8f;
        float width = (card.width() - 28f - gap * (buttons.size() - 1)) / Math.max(1, buttons.size());
        float x = card.left + 14f;
        for (T3Model.Option option : buttons) {
            int style = option.decision.equals("accept") ? STYLE_APPROVE
                    : option.decision.equals("decline") || option.decision.equals("cancel") ? STYLE_GLASS : STYLE_GLASS;
            final String decision = option.decision;
            controls.add(new Control(x, buttonTop, x + width, buttonTop + 48f, option.label, style,
                    () -> actions.approve(current(), approval, decision)));
            x += width + gap;
        }
        return top - 4f;
    }

    private float layoutQuestion(T3Model.Input input, int total, float bottom) {
        cardInput = input;
        cardMono = false;
        if (!input.requestId.equals(cardInputId)) {
            cardInputId = input.requestId;
            questionIndex = 0;
            answers.clear();
            selections.clear();
        }
        questionIndex = Math.min(questionIndex, input.questions.size() - 1);
        T3Model.Question question = input.questions.get(questionIndex);
        StringBuilder header = new StringBuilder("QUESTION");
        if (input.questions.size() > 1) header.append("  ·  ").append(questionIndex + 1).append(" OF ").append(input.questions.size());
        else if (total > 1) header.append("  ·  1 OF ").append(total);
        if (!question.header.isEmpty() && !"question".equalsIgnoreCase(question.header)) {
            header.append("  ·  ").append(question.header.toUpperCase(java.util.Locale.ROOT));
        }
        cardHeader = surface.ellipsize(header.toString(), 400f, 12.5f, T3Surface.MEDIUM);
        String body = question.question.isEmpty() ? "The agent is waiting for your answer." : question.question;
        cardFull = body;
        List<String> lines = surface.wrap(body, 412f, 15.5f, T3Surface.MEDIUM, false);
        cardTruncated = lines.size() > 3;
        cardLines = new ArrayList<>(lines.subList(0, Math.min(3, lines.size())));
        if (cardTruncated) cardLines.set(2, surface.ellipsize(cardLines.get(2) + "…", 400f, 15.5f, T3Surface.MEDIUM));

        // Chips flow: options, then Other… / Send.
        List<String> labels = new ArrayList<>(question.options);
        List<Runnable> runs = new ArrayList<>();
        List<Integer> styles = new ArrayList<>();
        for (String option : question.options) {
            runs.add(() -> choose(question, option));
            styles.add(question.multiSelect && selections.contains(option) ? STYLE_CHIP_ON : STYLE_CHIP);
        }
        if (question.allowCustom || question.options.isEmpty()) {
            labels.add("Other…");
            runs.add(() -> other(question));
            styles.add(STYLE_CHIP);
        }
        if (question.multiSelect && !selections.isEmpty()) {
            labels.add("Send " + selections.size());
            runs.add(() -> commit(question, new ArrayList<>(selections)));
            styles.add(STYLE_APPROVE);
        }
        float innerRight = 464f - 14f;
        List<float[]> placed = new ArrayList<>();
        float x = 30f;
        int row = 0;
        for (int i = 0; i < labels.size(); i++) {
            float extra = styles.get(i) == STYLE_CHIP_ON ? 18f : 0f;
            String shown = surface.ellipsize(labels.get(i), innerRight - 30f - 36f - extra, 15f, T3Surface.MEDIUM);
            float width = surface.measure(shown, 15f, T3Surface.MEDIUM) + 36f + extra;
            if (x + width > innerRight && x > 30f) {
                row++;
                x = 30f;
            }
            placed.add(new float[]{x, row, width});
            labels.set(i, shown);
            x += width + 8f;
        }
        int rowsUsed = row + 1;
        float chipsHeight = rowsUsed * 44f + (rowsUsed - 1) * 8f;
        float textHeight = cardLines.size() * 21f + (cardTruncated ? 4f : 0f);
        float height = 16f + 18f + 10f + textHeight + 14f + chipsHeight + 14f;
        float maxHeight = bottom - VIEW_TOP - 110f;
        if (height > maxHeight && cardLines.size() > 1) {
            cardLines = new ArrayList<>(cardLines.subList(0, 1));
            cardLines.set(0, surface.ellipsize(cardLines.get(0) + "…", 400f, 15.5f, T3Surface.MEDIUM));
            cardTruncated = true;
            textHeight = 21f;
            height = 16f + 18f + 10f + textHeight + 14f + chipsHeight + 14f;
        }
        float top = bottom - height;
        card.set(16f, top, 464f, bottom);
        cardText.set(card.left, top + 40f, card.right, top + 46f + textHeight);
        float chipsTop = bottom - 14f - chipsHeight;
        for (int i = 0; i < placed.size(); i++) {
            float[] p = placed.get(i);
            float chipTop = chipsTop + p[1] * 52f;
            controls.add(new Control(p[0], chipTop, p[0] + p[2], chipTop + 44f, labels.get(i), styles.get(i), runs.get(i)));
        }
        return top - 4f;
    }

    private float layoutQuickReplies(T3Model.Summary thread, float bottom) {
        String[] replies = T3Status.ERROR.equals(thread.status)
                ? new String[]{"Try again", "Continue", "Stop"}
                : new String[]{"Continue", "Looks good", "Stop"};
        float top = bottom - 44f;
        float x = 16f;
        for (String reply : replies) {
            float width = surface.measure(reply, 15f, T3Surface.MEDIUM) + 38f;
            controls.add(new Control(x, top, x + width, top + 44f, reply, STYLE_CHIP,
                    () -> actions.send(current(), reply)));
            x += width + 8f;
        }
        return top - 2f;
    }

    private void relayoutMessages() {
        pieces.clear();
        float y = 14f;
        T3Model.Summary thread = current();
        List<T3Model.Message> messages = new ArrayList<>();
        if (detail != null) messages.addAll(detail.messages);
        messages.addAll(localMessages);
        int lastAssistant = -1;
        for (int i = 0; i < messages.size(); i++) if (!messages.get(i).user) lastAssistant = i;
        for (int i = 0; i < messages.size(); i++) {
            T3Model.Message message = messages.get(i);
            boolean collapsible = i != lastAssistant && i != messages.size() - 1 && !expanded.contains(message.id);
            y = message.user ? layoutUser(message, y, collapsible) : layoutAssistant(message, y, collapsible);
        }
        if (detail == null) {
            Piece notice = new Piece(Kind.NOTICE);
            notice.text = loadFailed ? "Couldn't load this thread. Retrying…" : "Loading conversation…";
            notice.color = loadFailed ? T3Status.RED : SamTheme.MUTED;
            notice.rect.set(16f, y + 40f, 464f, y + 84f);
            pieces.add(notice);
            y += 100f;
        } else if (messages.isEmpty()) {
            Piece notice = new Piece(Kind.NOTICE);
            notice.text = "No messages yet";
            notice.color = SamTheme.MUTED;
            notice.rect.set(16f, y + 40f, 464f, y + 84f);
            pieces.add(notice);
            y += 100f;
        }
        if (thread != null && T3Status.working(thread.status)) {
            Piece typing = new Piece(Kind.TYPING);
            typing.text = thread.phase.isEmpty() ? "Working…" : thread.phase;
            typing.rect.set(24f, y, 464f, y + 30f);
            pieces.add(typing);
            y += 40f;
        } else if (thread != null && T3Status.ERROR.equals(thread.status)) {
            Piece notice = new Piece(Kind.NOTICE);
            notice.text = thread.label() + " · reply to try again";
            notice.color = T3Status.RED;
            notice.rect.set(16f, y + 4f, 464f, y + 48f);
            pieces.add(notice);
            y += 60f;
        }
        contentHeight = y + 8f;
        float max = maxScroll();
        if (follow) scrollTarget = max;
        scrollTarget = Math.min(scrollTarget, max);
        scroll = Math.min(scroll, max);
    }

    private float layoutUser(T3Model.Message message, float y, boolean collapsible) {
        List<String> lines = surface.wrap(message.text.trim(), 318f, TEXT_SIZE, T3Surface.REGULAR, false);
        boolean collapsed = collapsible && lines.size() > 12;
        if (collapsed) lines = new ArrayList<>(lines.subList(0, 8));
        float widest = 0f;
        for (String line : lines) widest = Math.max(widest, surface.measure(line, TEXT_SIZE, T3Surface.REGULAR));
        float height = lines.size() * LINE + 20f;
        Piece bubble = new Piece(Kind.BUBBLE);
        bubble.rect.set(462f - widest - 34f, y, 462f, y + height);
        bubble.pending = message.pending;
        pieces.add(bubble);
        for (int i = 0; i < lines.size(); i++) {
            Piece text = new Piece(Kind.TEXT);
            text.text = lines.get(i);
            text.rect.set(bubble.rect.left + 17f, y + 27f + i * LINE, 462f, y + 27f + i * LINE);
            text.color = message.pending ? SamTheme.withAlpha(SamTheme.INK, 170) : SamTheme.INK;
            pieces.add(text);
        }
        y += height;
        if (collapsed) y = addMore(message, y + 6f, true);
        if (message.pending) {
            Piece status = new Piece(Kind.TEXT);
            status.text = "Sending…";
            status.size = 12.5f;
            status.color = SamTheme.MUTED;
            status.rect.set(462f - surface.measure("Sending…", 12.5f, T3Surface.REGULAR), y + 16f, 462f, y + 16f);
            pieces.add(status);
            y += 18f;
        }
        return y + 14f;
    }

    private float layoutAssistant(T3Model.Message message, float y, boolean collapsible) {
        List<T3Markdown.Block> blocks = T3Markdown.parse(message.text);
        int budget = collapsible ? COLLAPSED_LINES : Integer.MAX_VALUE;
        int total = 0;
        if (collapsible) {
            for (T3Markdown.Block block : blocks) total += estimateLines(block);
            if (total <= COLLAPSE_OVER) budget = Integer.MAX_VALUE;
        }
        int used = 0;
        boolean cut = false;
        T3Markdown.Kind previous = null;
        for (T3Markdown.Block block : blocks) {
            if (used >= budget) {
                cut = true;
                break;
            }
            if (previous != null && previous != T3Markdown.Kind.GAP && block.kind != T3Markdown.Kind.GAP
                    && (block.kind != previous || block.kind == T3Markdown.Kind.CODE)) {
                y += 6f;
            }
            switch (block.kind) {
                case GAP -> y += 10f;
                case TEXT, HEADING -> {
                    boolean heading = block.kind == T3Markdown.Kind.HEADING;
                    float size = heading ? 17f : TEXT_SIZE;
                    Typeface face = heading ? T3Surface.MEDIUM : T3Surface.REGULAR;
                    if (heading) y += 4f;
                    for (String line : surface.wrap(block.text, 430f, size, face, false)) {
                        if (used >= budget) { cut = true; break; }
                        y += LINE;
                        Piece text = new Piece(Kind.TEXT);
                        text.text = line;
                        text.size = size;
                        text.face = face;
                        text.rect.set(26f, y - 6f, 456f, y - 6f);
                        pieces.add(text);
                        used++;
                    }
                }
                case BULLET -> {
                    float indent = block.level * 16f;
                    boolean firstLine = true;
                    for (String line : surface.wrap(block.text, 412f - indent, TEXT_SIZE, T3Surface.REGULAR, false)) {
                        if (used >= budget) { cut = true; break; }
                        y += LINE;
                        if (firstLine) {
                            Piece dot = new Piece(Kind.BULLET_DOT);
                            dot.rect.set(31f + indent, y - 11.5f, 31f + indent, y - 11.5f);
                            pieces.add(dot);
                            firstLine = false;
                        }
                        Piece text = new Piece(Kind.TEXT);
                        text.text = line;
                        text.rect.set(44f + indent, y - 6f, 456f, y - 6f);
                        pieces.add(text);
                        used++;
                    }
                }
                case QUOTE -> {
                    float start = y;
                    for (String line : surface.wrap(block.text, 414f, TEXT_SIZE, T3Surface.REGULAR, false)) {
                        if (used >= budget) { cut = true; break; }
                        y += LINE;
                        Piece text = new Piece(Kind.TEXT);
                        text.text = line;
                        text.color = SamTheme.withAlpha(SamTheme.INK, 190);
                        text.rect.set(40f, y - 6f, 456f, y - 6f);
                        pieces.add(text);
                        used++;
                    }
                    Piece bar = new Piece(Kind.QUOTE_BAR);
                    bar.rect.set(26f, start + 4f, 29f, y + 2f);
                    pieces.add(bar);
                }
                case CODE -> {
                    List<String> lines = surface.wrap(block.text, 408f, CODE_SIZE, T3Surface.MONO, true);
                    int room = budget == Integer.MAX_VALUE ? lines.size() : Math.max(2, budget - used);
                    List<String> shown = lines.size() > room ? lines.subList(0, room) : lines;
                    Piece panel = new Piece(Kind.CODE);
                    panel.full = block.text;
                    float top = y + 4f;
                    panel.rect.set(18f, top, 462f, top + shown.size() * CODE_LINE + 20f);
                    pieces.add(panel);
                    for (int i = 0; i < shown.size(); i++) {
                        Piece text = new Piece(Kind.TEXT);
                        text.text = shown.get(i);
                        text.size = CODE_SIZE;
                        text.face = T3Surface.MONO;
                        text.color = 0xFFCFE0FF;
                        text.rect.set(32f, top + 23f + i * CODE_LINE, 452f, top + 23f + i * CODE_LINE);
                        pieces.add(text);
                    }
                    y = panel.rect.bottom + 2f;
                    used += Math.max(2, (shown.size() + 1) / 2);
                    if (shown.size() < lines.size()) cut = true;
                }
            }
            previous = block.kind;
            if (cut) break;
        }
        if (message.streaming) {
            Piece typing = new Piece(Kind.TYPING);
            typing.text = "";
            typing.rect.set(26f, y + 6f, 200f, y + 30f);
            pieces.add(typing);
            y += 30f;
        }
        if (cut) y = addMore(message, y + 10f, false);
        return y + 18f;
    }

    private static int estimateLines(T3Markdown.Block block) {
        if (block.kind == T3Markdown.Kind.GAP) return 0;
        if (block.kind == T3Markdown.Kind.CODE) return (block.text.split("\n", -1).length + 1) / 2 + 1;
        return 1 + block.text.length() / 52;
    }

    private float addMore(T3Model.Message message, float y, boolean right) {
        Piece more = new Piece(Kind.MORE);
        more.text = "Show more";
        more.messageId = message.id;
        float width = surface.measure(more.text, 14f, T3Surface.MEDIUM) + 40f;
        if (right) more.rect.set(462f - width, y, 462f, y + 36f);
        else more.rect.set(24f, y, 24f + width, y + 36f);
        pieces.add(more);
        return y + 40f;
    }

    private float maxScroll() {
        return Math.max(0f, contentHeight - (viewBottom - VIEW_TOP));
    }

    // ---- actions ---------------------------------------------------------------------------

    private void type() {
        T3Model.Summary thread = current();
        if (thread == null) return;
        T3Composer.open(activity, "Message · " + thread.title, "Reply to the agent", "Send",
                value -> actions.send(current(), value));
    }

    private void choose(T3Model.Question question, String option) {
        if (question.multiSelect) {
            if (!selections.remove(option)) selections.add(option);
            relayoutControls();
            invalidate();
            return;
        }
        commit(question, option);
    }

    private void other(T3Model.Question question) {
        String prompt = question.question.isEmpty() ? "Your answer" : question.question;
        T3Composer.open(activity, prompt, "Type your answer", "Answer", value -> {
            if (question.multiSelect) {
                List<String> values = new ArrayList<>(selections);
                values.add(value);
                commit(question, values);
            } else {
                commit(question, value);
            }
        });
    }

    private void commit(T3Model.Question question, Object value) {
        T3Model.Input input = cardInput;
        if (input == null) return;
        answers.put(question.id, value);
        selections.clear();
        if (questionIndex + 1 < input.questions.size()) {
            questionIndex++;
            relayoutControls();
            relayoutMessages();
            invalidate();
            return;
        }
        JSONObject body = new JSONObject();
        try {
            for (Map.Entry<String, Object> entry : answers.entrySet()) {
                Object answer = entry.getValue();
                if (answer instanceof List<?> list) {
                    JSONArray array = new JSONArray();
                    for (Object item : list) array.put(String.valueOf(item));
                    body.put(entry.getKey(), array);
                } else {
                    body.put(entry.getKey(), String.valueOf(answer));
                }
            }
        } catch (Exception ignored) {
            return;
        }
        answers.clear();
        questionIndex = 0;
        actions.answer(current(), input, body);
    }

    // ---- input -----------------------------------------------------------------------------

    boolean onInput(UiInputIntent intent) {
        switch (intent) {
            case BACK -> {
                return false;
            }
            case NEXT -> {
                if (focus < 0) {
                    if (scrollTarget < maxScroll() - 1f) scrollBy(70f);
                    else if (!controls.isEmpty()) focus = 0;
                } else {
                    focus = Math.min(controls.size() - 1, focus + 1);
                }
            }
            case PREVIOUS -> {
                if (focus > 0) focus--;
                else if (focus == 0) focus = -1;
                else scrollBy(-70f);
            }
            case ACTIVATE -> {
                if (focus >= 0 && focus < controls.size()) controls.get(focus).action.run();
                else if (current() != null) actions.talk(current());
            }
        }
        invalidate();
        return true;
    }

    private void scrollBy(float delta) {
        scroller.forceFinished(true);
        scrollTarget = Math.max(0f, Math.min(maxScroll(), scrollTarget + delta));
        follow = scrollTarget >= maxScroll() - 4f;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() * W / Math.max(1f, getWidth());
        float y = event.getY() * H / Math.max(1f, getHeight());
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true);
                downX = x;
                downY = y;
                lastY = y;
                dragging = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                boolean inContent = downY >= VIEW_TOP && downY <= viewBottom;
                if (!dragging && inContent && Math.abs(y - downY) > 12f) dragging = true;
                if (dragging) {
                    scroll = Math.max(0f, Math.min(maxScroll(), scroll - (y - lastY)));
                    scrollTarget = scroll;
                    follow = scroll >= maxScroll() - 4f;
                    invalidate();
                }
                lastY = y;
            }
            case MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    float vy = velocity.getYVelocity() * H / Math.max(1f, getHeight());
                    scroller.fling(0, Math.round(scroll), 0, Math.round(-vy), 0, 0, 0, Math.round(maxScroll()));
                    invalidate();
                } else if (Math.abs(x - downX) < 16f && Math.abs(y - downY) < 16f) {
                    tap(x, y);
                }
                recycleVelocity();
            }
            case MotionEvent.ACTION_CANCEL -> recycleVelocity();
            default -> { }
        }
        return true;
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        dragging = false;
    }

    private void tap(float x, float y) {
        if (hit(BACK, x, y, 10f)) {
            actions.back();
            return;
        }
        for (int i = 0; i < controls.size(); i++) {
            Control control = controls.get(i);
            if (hit(control.rect, x, y, 3f)) {
                focus = -1;
                control.action.run();
                invalidate();
                return;
            }
        }
        if (!card.isEmpty() && cardTruncated && hit(cardText, x, y, 4f)) {
            T3Composer.reveal(activity, cardApproval != null ? cardApproval.title() : "Question", cardFull, cardMono);
            return;
        }
        if (y >= VIEW_TOP && y <= viewBottom) {
            float contentY = y - VIEW_TOP + scroll;
            for (Piece piece : pieces) {
                if (piece.kind == Kind.MORE && hit(piece.rect, x, contentY, 6f)) {
                    expanded.add(piece.messageId);
                    follow = false;
                    relayoutMessages();
                    invalidate();
                    return;
                }
                if (piece.kind == Kind.CODE && hit(piece.rect, x, contentY, 0f)) {
                    T3Composer.reveal(activity, "Code", piece.full, true);
                    return;
                }
            }
        }
        if (y < HEADER_BOTTOM && x > BACK.right + 4f && current() != null) {
            T3Composer.reveal(activity, "Thread", current().title, false);
        }
    }

    private static boolean hit(RectF rect, float x, float y, float slop) {
        return x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop;
    }

    // ---- drawing ---------------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        long now = System.currentTimeMillis();
        if (now - layoutAt > 30_000L) relayoutHeader();
        if (optimistic != null && SystemClock.uptimeMillis() > optimisticUntil) {
            optimistic = null;
            relayout();
        }
        boolean animating = false;
        if (scroller.computeScrollOffset()) {
            scroll = Math.max(0f, Math.min(maxScroll(), scroller.getCurrY()));
            scrollTarget = scroll;
            follow = scroll >= maxScroll() - 4f;
            animating = true;
        } else if (Math.abs(scrollTarget - scroll) > 0.5f) {
            scroll += (scrollTarget - scroll) * 0.3f;
            animating = true;
        } else {
            scroll = scrollTarget;
        }
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        surface.background(canvas, W, H, 240f, 40f, 260f, statusColor);
        animating |= drawMessages(canvas, now);
        drawHeader(canvas, now);
        animating |= working();
        drawCard(canvas);
        drawControls(canvas);
        animating |= toast.draw(canvas, surface, Math.max(VIEW_TOP + 40f, viewBottom - 30f));
        canvas.restore();
        if (animating && isShown()) postInvalidateOnAnimation();
        else if (isShown()) postInvalidateDelayed(1_000L);
    }

    private void drawHeader(Canvas canvas, long now) {
        surface.glass(canvas, BACK, 26f, false);
        surface.chevronLeft(canvas, BACK.centerX() - 1f, BACK.centerY(), SamTheme.INK);
        surface.text(canvas, titleText, 82f, 40f, 19f, SamTheme.INK, Paint.Align.LEFT, T3Surface.MEDIUM);
        rect.set(82f, 52f, 82f + pillWidth, 76f);
        surface.tinted(canvas, rect, 12f, statusColor, 34, 110);
        T3Model.Summary thread = current();
        boolean spinning = thread != null && T3Status.working(thread.status);
        surface.statusOrb(canvas, rect.left + 13f, rect.centerY(), 4.2f, statusColor, false, now);
        if (spinning) {
            surface.paint.setStyle(Paint.Style.STROKE);
            surface.paint.setStrokeWidth(1.6f);
            surface.paint.setColor(SamTheme.withAlpha(statusColor, 200));
            float pulse = 6.5f + 1.5f * (float) Math.sin(now / 220.0);
            canvas.drawCircle(rect.left + 13f, rect.centerY(), pulse, surface.paint);
            surface.paint.setStyle(Paint.Style.FILL);
        }
        surface.text(canvas, pillText, rect.left + 24f, 68.5f, 13f, SamTheme.INK, Paint.Align.LEFT, T3Surface.MEDIUM);
        surface.text(canvas, metaText, rect.right + 10f, 68.5f, 13.5f, SamTheme.MUTED, Paint.Align.LEFT,
                T3Surface.REGULAR);
        surface.paint.setColor(SamTheme.LINE);
        canvas.drawRect(16f, HEADER_BOTTOM, 464f, HEADER_BOTTOM + 1f, surface.paint);
    }

    private boolean drawMessages(Canvas canvas, long now) {
        boolean animating = false;
        canvas.save();
        canvas.clipRect(0f, VIEW_TOP, W, viewBottom);
        float offset = VIEW_TOP - scroll;
        for (Piece piece : pieces) {
            float top = piece.rect.top + offset;
            float bottom = piece.rect.bottom + offset;
            if (piece.kind == Kind.TEXT) {
                if (top - piece.size - 4f > viewBottom || top + 6f < VIEW_TOP) continue;
            } else if (top > viewBottom || bottom < VIEW_TOP) {
                continue;
            }
            switch (piece.kind) {
                case TEXT -> surface.text(canvas, piece.text, piece.rect.left, top, piece.size, piece.color,
                        Paint.Align.LEFT, piece.face);
                case BUBBLE -> {
                    rect.set(piece.rect.left, top, piece.rect.right, bottom);
                    if (piece.pending) surface.stroke(canvas, rect, 20f, 1.4f, SamTheme.withAlpha(SamTheme.ORB_PALE, 110));
                    else surface.glass(canvas, rect, 20f, true);
                }
                case CODE -> {
                    rect.set(piece.rect.left, top, piece.rect.right, bottom);
                    surface.solid(canvas, rect, 14f, 0xFF070A10);
                    surface.stroke(canvas, rect, 14f, 1.2f, SamTheme.LINE);
                }
                case QUOTE_BAR -> {
                    rect.set(piece.rect.left, top, piece.rect.right, bottom);
                    surface.solid(canvas, rect, 1.5f, SamTheme.withAlpha(SamTheme.ORB_PALE, 120));
                }
                case BULLET_DOT -> {
                    surface.paint.setColor(SamTheme.ORB_PALE);
                    canvas.drawCircle(piece.rect.left, top, 3f, surface.paint);
                }
                case MORE -> {
                    rect.set(piece.rect.left, top, piece.rect.right, bottom);
                    surface.glass(canvas, rect, 18f, false);
                    surface.text(canvas, piece.text, rect.centerX(), rect.centerY() + 5f, 14f, SamTheme.ORB_PALE,
                            Paint.Align.CENTER, T3Surface.MEDIUM);
                }
                case TYPING -> {
                    surface.typing(canvas, piece.rect.left + 6f, top + 15f, T3Status.BLUE, now);
                    if (!piece.text.isEmpty()) {
                        surface.text(canvas, piece.text, piece.rect.left + 46f, top + 20f, 14f, SamTheme.MUTED,
                                Paint.Align.LEFT, T3Surface.REGULAR);
                    }
                    animating = true;
                }
                case NOTICE -> {
                    rect.set(piece.rect.left, top, piece.rect.right, bottom);
                    if (piece.color == T3Status.RED) surface.tinted(canvas, rect, 18f, T3Status.RED, 22, 90);
                    surface.text(canvas, piece.text, rect.centerX(), rect.centerY() + 5.5f, 15f,
                            piece.color == T3Status.RED ? SamTheme.INK : piece.color, Paint.Align.CENTER,
                            T3Surface.REGULAR);
                }
            }
        }
        canvas.restore();
        surface.fadeEdges(canvas, 0f, W, VIEW_TOP, viewBottom, 14f);
        if (maxScroll() > 0f && !follow && scrollTarget < maxScroll() - 40f) drawJumpHint(canvas);
        return animating;
    }

    /** Small "newer below" hint when the reader scrolled up. */
    private void drawJumpHint(Canvas canvas) {
        float cy = viewBottom - 20f;
        rect.set(222f, cy - 14f, 258f, cy + 14f);
        surface.solid(canvas, rect, 14f, SamTheme.withAlpha(SamTheme.PANEL_RAISED, 235));
        surface.stroke(canvas, rect, 14f, 1.2f, SamTheme.LINE);
        surface.paint.setStyle(Paint.Style.STROKE);
        surface.paint.setStrokeWidth(2.2f);
        surface.paint.setStrokeCap(Paint.Cap.ROUND);
        surface.paint.setColor(SamTheme.ORB_PALE);
        canvas.drawLine(233f, cy - 3f, 240f, cy + 4f, surface.paint);
        canvas.drawLine(240f, cy + 4f, 247f, cy - 3f, surface.paint);
        surface.paint.setStrokeCap(Paint.Cap.BUTT);
        surface.paint.setStyle(Paint.Style.FILL);
    }

    private void drawCard(Canvas canvas) {
        if (card.isEmpty()) return;
        surface.tinted(canvas, card, 22f, T3Status.AMBER, 20, 110);
        surface.paint.setColor(T3Status.AMBER);
        canvas.drawCircle(card.left + 22f, card.top + 24f, 4f, surface.paint);
        surface.label(canvas, cardHeader, card.left + 34f, card.top + 29f, T3Status.AMBER, Paint.Align.LEFT);
        float y = card.top + 40f;
        if (cardMono) {
            for (String line : cardLines) {
                y += CODE_LINE;
                surface.text(canvas, line, card.left + 18f, y, CODE_SIZE, 0xFFE9EEF8, Paint.Align.LEFT, T3Surface.MONO);
            }
        } else {
            for (String line : cardLines) {
                y += 21f;
                surface.text(canvas, line, card.left + 18f, y, 15.5f, SamTheme.INK, Paint.Align.LEFT, T3Surface.MEDIUM);
            }
        }
        if (cardTruncated && cardMono) {
            surface.text(canvas, "Tap to see all", card.left + 18f, y + 17f, 12.5f, SamTheme.ORB_PALE,
                    Paint.Align.LEFT, T3Surface.MEDIUM);
        }
    }

    private void drawControls(Canvas canvas) {
        for (int i = 0; i < controls.size(); i++) {
            Control control = controls.get(i);
            RectF r = control.rect;
            float radius = Math.min(r.height() / 2f, 24f);
            switch (control.style) {
                case STYLE_PRIMARY -> surface.primary(canvas, r, radius);
                case STYLE_DANGER -> surface.tinted(canvas, r, radius, T3Status.RED, 34, 120);
                case STYLE_APPROVE -> surface.solid(canvas, r, radius, T3Status.AMBER);
                case STYLE_CHIP_ON -> surface.tinted(canvas, r, radius, SamTheme.ORB_PALE, 60, 200);
                default -> surface.glass(canvas, r, radius, false);
            }
            int ink = control.style == STYLE_APPROVE ? SamTheme.BACKGROUND : SamTheme.INK;
            boolean composer = r.top >= COMPOSER_TOP - 1f;
            if (composer) {
                float textWidth = surface.measure(control.label, 18f, T3Surface.MEDIUM);
                float glyphX = r.centerX() - textWidth / 2f - 14f;
                float textX = glyphX + 16f;
                switch (control.label) {
                    case "Type" -> surface.pencil(canvas, glyphX, r.centerY(), ink);
                    case "Talk" -> surface.mic(canvas, glyphX, r.centerY(), ink);
                    case "Stop" -> surface.stopSquare(canvas, glyphX, r.centerY(), T3Status.RED);
                    default -> { }
                }
                surface.text(canvas, control.label, textX, r.centerY() + 6.5f, 18f, ink, Paint.Align.LEFT,
                        T3Surface.MEDIUM);
            } else {
                float size = control.style >= STYLE_CHIP ? 15f : 17f;
                if (control.style == STYLE_CHIP_ON) {
                    surface.check(canvas, r.left + 16f, r.centerY(), SamTheme.INK);
                    surface.text(canvas, control.label, r.centerX() + 8f, r.centerY() + 5.5f, size, ink,
                            Paint.Align.CENTER, T3Surface.MEDIUM);
                } else {
                    surface.text(canvas, control.label, r.centerX(), r.centerY() + (size > 16f ? 6f : 5.5f), size, ink,
                            Paint.Align.CENTER, T3Surface.MEDIUM);
                }
            }
            if (i == focus) surface.focus(canvas, r, radius);
        }
    }
}
