package com.resonolabs.feature.t3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable views of the CONTRACTS §2 payloads. Parsing is lenient: bad items are skipped. */
final class T3Model {
    private T3Model() {}

    static final class Project {
        final String id;
        final String title;

        Project(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    /** {@code ThreadSummary}. */
    static final class Summary {
        final String id;
        final String projectId;
        final String projectTitle;
        final String title;
        final String status;
        final String statusLabel;
        final long updatedAt;
        final long completedAt;
        final boolean unread;
        final String model;
        final String phase;
        /** 0..1, or negative when unknown. */
        final double progress;

        Summary(String id, String projectId, String projectTitle, String title, String status,
                String statusLabel, long updatedAt, long completedAt, boolean unread, String model,
                String phase, double progress) {
            this.id = id;
            this.projectId = projectId;
            this.projectTitle = projectTitle;
            this.title = title;
            this.status = status;
            this.statusLabel = statusLabel;
            this.updatedAt = updatedAt;
            this.completedAt = completedAt;
            this.unread = unread;
            this.model = model;
            this.phase = phase;
            this.progress = progress;
        }

        static Summary from(JSONObject value) {
            if (value == null) return null;
            String id = text(value, "id");
            if (id.isEmpty()) return null;
            String title = text(value, "title");
            double progress = value.isNull("progress") ? -1d : value.optDouble("progress", -1d);
            if (Double.isNaN(progress)) progress = -1d;
            if (progress > 1d && progress <= 100d) progress = progress / 100d;
            return new Summary(id, text(value, "projectId"), text(value, "projectTitle"),
                    title.isEmpty() ? "Untitled thread" : title,
                    text(value, "status").isEmpty() ? T3Status.DONE : text(value, "status"),
                    text(value, "statusLabel"),
                    T3Time.parse(text(value, "updatedAt")), T3Time.parse(text(value, "completedAt")),
                    value.optBoolean("unread", false), text(value, "model"), text(value, "phase"),
                    Math.min(1d, progress));
        }

        Summary withStatus(String nextStatus, String nextLabel, String nextPhase) {
            return new Summary(id, projectId, projectTitle, title, nextStatus, nextLabel, updatedAt,
                    completedAt, unread, model, nextPhase, -1d);
        }

        Summary seen() {
            return new Summary(id, projectId, projectTitle, title, status, statusLabel, updatedAt,
                    completedAt, false, model, phase, progress);
        }

        String label() {
            return T3Status.label(status, statusLabel);
        }

        int color() {
            return T3Status.color(status, unread);
        }
    }

    static final class Counts {
        final int needsYou;
        final int working;
        final int done;
        final int error;

        Counts(int needsYou, int working, int done, int error) {
            this.needsYou = needsYou;
            this.working = working;
            this.done = done;
            this.error = error;
        }

        static Counts of(List<Summary> threads) {
            int needs = 0, working = 0, done = 0, error = 0;
            for (Summary thread : threads) {
                if (T3Status.needsYou(thread.status)) needs++;
                else if (T3Status.working(thread.status)) working++;
                else if (T3Status.ERROR.equals(thread.status)) error++;
                else done++;
            }
            return new Counts(needs, working, done, error);
        }
    }

    /** {@code GET /v1/t3/threads}. */
    static final class Snapshot {
        final boolean connected;
        final long revision;
        final long updatedAt;
        final Counts counts;
        final List<Project> projects;
        final List<Summary> threads;

        Snapshot(boolean connected, long revision, long updatedAt, Counts counts,
                 List<Project> projects, List<Summary> threads) {
            this.connected = connected;
            this.revision = revision;
            this.updatedAt = updatedAt;
            this.counts = counts;
            this.projects = projects;
            this.threads = threads;
        }

        static Snapshot from(JSONObject value) {
            List<Summary> threads = new ArrayList<>();
            JSONArray items = value.optJSONArray("threads");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    Summary summary = Summary.from(items.optJSONObject(i));
                    if (summary != null) threads.add(summary);
                }
            }
            List<Project> projects = new ArrayList<>();
            JSONArray projectItems = value.optJSONArray("projects");
            if (projectItems != null) {
                for (int i = 0; i < projectItems.length(); i++) {
                    JSONObject project = projectItems.optJSONObject(i);
                    if (project == null || text(project, "id").isEmpty()) continue;
                    String title = text(project, "title");
                    projects.add(new Project(text(project, "id"), title.isEmpty() ? "Project" : title));
                }
            }
            JSONObject counts = value.optJSONObject("counts");
            Counts derived = Counts.of(threads);
            Counts resolved = counts == null ? derived : new Counts(
                    counts.optInt("needsYou", derived.needsYou), counts.optInt("working", derived.working),
                    counts.optInt("done", derived.done), counts.optInt("error", derived.error));
            return new Snapshot(value.optBoolean("connected", true), value.optLong("revision", -1L),
                    T3Time.parse(text(value, "updatedAt")), resolved,
                    Collections.unmodifiableList(projects), Collections.unmodifiableList(threads));
        }

        Summary find(String threadId) {
            for (Summary thread : threads) if (thread.id.equals(threadId)) return thread;
            return null;
        }

        /** The project to preselect for a new thread: the most recently updated thread's project. */
        Project defaultProject() {
            Summary newest = null;
            for (Summary thread : threads) {
                if (thread.projectId.isEmpty()) continue;
                if (newest == null || thread.updatedAt > newest.updatedAt) newest = thread;
            }
            if (newest != null) {
                for (Project project : projects) if (project.id.equals(newest.projectId)) return project;
                return new Project(newest.projectId,
                        newest.projectTitle.isEmpty() ? "Project" : newest.projectTitle);
            }
            return projects.isEmpty() ? null : projects.get(0);
        }

        /** Projects for the picker: listed ones plus any only seen on threads, most recent first. */
        List<Project> pickerProjects() {
            List<Project> out = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            List<Summary> byRecency = new ArrayList<>(threads);
            byRecency.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            for (Summary thread : byRecency) {
                if (thread.projectId.isEmpty() || seen.contains(thread.projectId)) continue;
                Project listed = null;
                for (Project project : projects) if (project.id.equals(thread.projectId)) listed = project;
                out.add(listed != null ? listed : new Project(thread.projectId,
                        thread.projectTitle.isEmpty() ? "Project" : thread.projectTitle));
                seen.add(thread.projectId);
            }
            for (Project project : projects) {
                if (!seen.contains(project.id)) {
                    out.add(project);
                    seen.add(project.id);
                }
            }
            return out;
        }
    }

    static final class Message {
        final String id;
        final boolean user;
        final String text;
        final long createdAt;
        final boolean streaming;
        /** Locally sent and not yet echoed by the runtime. */
        final boolean pending;

        Message(String id, boolean user, String text, long createdAt, boolean streaming, boolean pending) {
            this.id = id;
            this.user = user;
            this.text = text;
            this.createdAt = createdAt;
            this.streaming = streaming;
            this.pending = pending;
        }
    }

    static final class Option {
        final String decision;
        final String label;

        Option(String decision, String label) {
            this.decision = decision;
            this.label = label;
        }
    }

    static final class Approval {
        final String requestId;
        final String kind;
        final String detail;
        final List<Option> options;

        Approval(String requestId, String kind, String detail, List<Option> options) {
            this.requestId = requestId;
            this.kind = kind;
            this.detail = detail;
            this.options = options;
        }

        /** What the card header says for each T3 request kind. */
        String title() {
            return switch (kind) {
                case "command" -> "Run this command?";
                case "file-read" -> "Read these files?";
                case "file-change" -> "Apply these changes?";
                case "mcp-elicitation" -> "Tool needs input";
                case "permission" -> "Grant permission?";
                default -> "Approval needed";
            };
        }

        /** Buttons to render, left to right: Deny, optional Always, Approve. */
        List<Option> buttons() {
            List<Option> out = new ArrayList<>();
            boolean listed = !options.isEmpty();
            if (!listed || has("decline")) out.add(new Option("decline", "Deny"));
            else if (has("cancel")) out.add(new Option("cancel", "Cancel"));
            if (has("acceptForSession")) out.add(new Option("acceptForSession", "Always"));
            else if (has("acceptAlways")) out.add(new Option("acceptAlways", "Always"));
            if (!listed || has("accept")) out.add(new Option("accept", "Approve"));
            return out;
        }

        private boolean has(String decision) {
            for (Option option : options) if (decision.equals(option.decision)) return true;
            return false;
        }
    }

    static final class Question {
        final String id;
        final String header;
        final String question;
        final List<String> options;
        final boolean allowCustom;
        final boolean multiSelect;

        Question(String id, String header, String question, List<String> options, boolean allowCustom,
                 boolean multiSelect) {
            this.id = id;
            this.header = header;
            this.question = question;
            this.options = options;
            this.allowCustom = allowCustom;
            this.multiSelect = multiSelect;
        }
    }

    static final class Input {
        final String requestId;
        final List<Question> questions;

        Input(String requestId, List<Question> questions) {
            this.requestId = requestId;
            this.questions = questions;
        }
    }

    /** {@code GET /v1/t3/threads/{id}}. */
    static final class Detail {
        final Summary thread;
        final List<Message> messages;
        final List<Approval> approvals;
        final List<Input> inputs;
        final String activeTurnId;

        Detail(Summary thread, List<Message> messages, List<Approval> approvals, List<Input> inputs,
               String activeTurnId) {
            this.thread = thread;
            this.messages = messages;
            this.approvals = approvals;
            this.inputs = inputs;
            this.activeTurnId = activeTurnId;
        }

        static Detail from(JSONObject value) {
            Summary thread = Summary.from(value.optJSONObject("thread"));
            if (thread == null) return null;
            List<Message> messages = new ArrayList<>();
            JSONArray items = value.optJSONArray("messages");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String role = text(item, "role");
                    if (!"user".equals(role) && !"assistant".equals(role)) continue;
                    String body = raw(item, "text");
                    boolean streaming = item.optBoolean("streaming", false);
                    if (body.isBlank() && !streaming) continue;
                    String id = text(item, "id");
                    messages.add(new Message(id.isEmpty() ? role + ":" + i : id, "user".equals(role), body,
                            T3Time.parse(text(item, "createdAt")), streaming, false));
                }
            }
            List<Approval> approvals = new ArrayList<>();
            List<Input> inputs = new ArrayList<>();
            JSONObject pending = value.optJSONObject("pending");
            if (pending != null) {
                JSONArray approvalItems = pending.optJSONArray("approvals");
                if (approvalItems != null) {
                    for (int i = 0; i < approvalItems.length(); i++) {
                        JSONObject item = approvalItems.optJSONObject(i);
                        if (item == null || text(item, "requestId").isEmpty()) continue;
                        List<Option> options = new ArrayList<>();
                        JSONArray optionItems = item.optJSONArray("options");
                        if (optionItems != null) {
                            for (int o = 0; o < optionItems.length(); o++) {
                                JSONObject option = optionItems.optJSONObject(o);
                                if (option == null || text(option, "decision").isEmpty()) continue;
                                options.add(new Option(text(option, "decision"), text(option, "label")));
                            }
                        }
                        approvals.add(new Approval(text(item, "requestId"), text(item, "kind"),
                                text(item, "detail"), options));
                    }
                }
                JSONArray inputItems = pending.optJSONArray("inputs");
                if (inputItems != null) {
                    for (int i = 0; i < inputItems.length(); i++) {
                        JSONObject item = inputItems.optJSONObject(i);
                        if (item == null || text(item, "requestId").isEmpty()) continue;
                        List<Question> questions = new ArrayList<>();
                        JSONArray questionItems = item.optJSONArray("questions");
                        if (questionItems != null) {
                            for (int q = 0; q < questionItems.length(); q++) {
                                JSONObject question = questionItems.optJSONObject(q);
                                if (question == null) continue;
                                List<String> options = new ArrayList<>();
                                JSONArray optionItems = question.optJSONArray("options");
                                if (optionItems != null) {
                                    for (int o = 0; o < optionItems.length(); o++) {
                                        Object option = optionItems.opt(o);
                                        String label = option instanceof JSONObject
                                                ? text((JSONObject) option, "label")
                                                : option == null ? "" : String.valueOf(option).trim();
                                        if (!label.isEmpty()) options.add(label);
                                    }
                                }
                                String id = text(question, "id");
                                questions.add(new Question(id.isEmpty() ? String.valueOf(q) : id,
                                        text(question, "header"), text(question, "question"),
                                        options, question.optBoolean("allowCustom", true),
                                        question.optBoolean("multiSelect", false)));
                            }
                        }
                        if (!questions.isEmpty()) inputs.add(new Input(text(item, "requestId"), questions));
                    }
                }
            }
            return new Detail(thread, Collections.unmodifiableList(messages),
                    Collections.unmodifiableList(approvals), Collections.unmodifiableList(inputs),
                    text(value, "activeTurnId"));
        }

        /** Stable fingerprint so polls that change nothing do not relayout. */
        String fingerprint() {
            StringBuilder out = new StringBuilder();
            out.append(thread.status).append('|').append(thread.statusLabel).append('|')
                    .append(thread.phase).append('|').append(thread.title).append('|').append(thread.unread);
            for (Message message : messages) {
                out.append('|').append(message.id).append(':').append(message.text.length())
                        .append(':').append(message.text.hashCode()).append(message.streaming ? 's' : '-');
            }
            for (Approval approval : approvals) out.append("|a:").append(approval.requestId);
            for (Input input : inputs) out.append("|i:").append(input.requestId);
            return out.toString();
        }
    }

    /** {@code GET /v1/t3/status}. */
    static final class Connection {
        final boolean connected;
        final String healthState;
        final String label;
        final String serverUrl;
        final String detail;

        Connection(boolean connected, String healthState, String label, String serverUrl, String detail) {
            this.connected = connected;
            this.healthState = healthState;
            this.label = label;
            this.serverUrl = serverUrl;
            this.detail = detail;
        }

        static Connection from(JSONObject value) {
            return new Connection(value.optBoolean("connected", false),
                    text(value, "healthState").isEmpty() ? "unconfigured" : text(value, "healthState"),
                    text(value, "label"), text(value, "serverUrl"), text(value, "detail"));
        }
    }

    /**
     * {@code optString} that maps JSON null and missing keys to "". Android's
     * {@code optString(key, "")} returns the string "null" for a JSON null (the JVM reference
     * jar used by the unit tests does not), so parsing never calls optString directly.
     */
    static String text(JSONObject value, String key) {
        return raw(value, key).trim();
    }

    /** Like {@link #text} but keeps surrounding whitespace (message bodies). */
    static String raw(JSONObject value, String key) {
        if (value == null || value.isNull(key)) return "";
        Object raw = value.opt(key);
        return raw == null ? "" : String.valueOf(raw);
    }
}
