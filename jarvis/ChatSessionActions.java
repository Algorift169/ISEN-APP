package org.jarvis.mobile.jarvis;

import android.app.Activity;
import android.app.AlertDialog;

import org.json.JSONObject;

public final class ChatSessionActions {
    public interface DeleteListener {
        void onDeleted(long activeSessionId, JSONObject activeSession);
    }

    private final Activity activity;
    private final ChatSessionStore store;
    private final JarvisAssistant assistant;

    public ChatSessionActions(Activity activity, ChatSessionStore store,
                              JarvisAssistant assistant) {
        this.activity = activity;
        this.store = store;
        this.assistant = assistant;
    }

    public void confirmDelete(long sessionId, long activeSessionId, DeleteListener listener) {
        new AlertDialog.Builder(activity)
                .setTitle("Delete chat?")
                .setMessage("This permanently deletes this chat and its messages.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (!store.deleteSession(sessionId)) {
                        return;
                    }
                    long nextSessionId = activeSessionId;
                    JSONObject nextSession = null;
                    if (sessionId == activeSessionId) {
                        nextSessionId = store.getLatestSessionId();
                        if (nextSessionId == 0) {
                            nextSessionId = store.createSession();
                        }
                        assistant.loadConversation(store.getAssistantHistory(nextSessionId));
                        nextSession = store.getSessionState(nextSessionId);
                    }
                    listener.onDeleted(nextSessionId, nextSession);
                })
                .show();
    }
}