package com.resonolabs.feature.genui;

import com.resonolabs.runtime.host.TaskClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Open tasks as a checklist. Tapping a row never completes it locally: it asks the model
 * ("Mark task 'X' as done"), which runs the normal confirm flow for writes.
 */
final class TasksSource extends PollingLiveSource {
    static final String ROW_SAY = "Mark task '%s' as done";
    private TaskClient client;

    TasksSource(LiveSourceRegistry registry, GenCard card) {
        super(registry, card, 15_000L, 60_000L);
    }

    @Override protected void fetch(int token) {
        if (client == null) client = new TaskClient();
        client.loadActive(registry.context(), new TaskClient.Callback() {
            @Override public void onTasks(JSONObject value) {
                apply(card, value.optJSONArray("tasks"));
                succeeded(token, 0L);
            }

            @Override public void onFailure() {
                failed(token);
            }
        });
    }

    static void apply(GenCard card, JSONArray tasks) {
        java.util.ArrayList<GenRow> rows = new java.util.ArrayList<>();
        int open = 0;
        for (int index = 0; tasks != null && index < tasks.length(); index++) {
            JSONObject task = tasks.optJSONObject(index);
            if (task == null) continue;
            String status = task.optString("status", "open");
            if (!"open".equals(status)) continue;
            String text = GenCardParser.clean(GenCardParser.value(task, "text"), GenSchema.CHECK_TEXT, null, null);
            if (text == null) continue;
            open++;
            if (rows.size() >= GenSchema.ITEMS) continue;
            GenRow row = new GenRow();
            row.title = text;
            row.ref = GenCardParser.clean(GenCardParser.value(task, "taskId"), GenSchema.REF, null, null);
            rows.add(row);
        }
        card.body.clear();
        card.liveStatus = GenSchema.STATUS_ACTIVE;
        card.liveNote = null;
        if (rows.isEmpty()) {
            GenBlock empty = new GenBlock(GenBlock.Type.TEXT);
            empty.id = "tasks";
            empty.style = GenSchema.STYLE_MUTED;
            empty.text = "Nothing open. Ask me to add a task.";
            card.body.add(empty);
            card.liveSubtitle = "All clear";
            return;
        }
        GenBlock list = new GenBlock(GenBlock.Type.CHECKLIST);
        list.id = "tasks";
        list.items = rows.toArray(new GenRow[0]);
        list.rowSay = ROW_SAY;
        card.body.add(list);
        card.liveSubtitle = open == 1 ? "1 open" : open + " open";
    }

    @Override protected void closeClient() {
        if (client != null) client.close();
        client = null;
    }
}
