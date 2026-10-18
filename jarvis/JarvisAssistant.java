package org.jarvis.mobile.jarvis;

import android.os.Handler;
import android.os.Looper;

import org.jarvis.mobile.api.ApiKeyStore;
import org.jarvis.mobile.api.JarvisApiClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class JarvisAssistant {
    public interface ReplyListener {
        void onReply(String reply, boolean failed, String provider, boolean switched);
    }

        private static final String SYSTEM_PROMPT = "You are ISEN, the mobile assistant powered "
            + "by the JARVIS AI core built by Israfil. Be concise, polished, "
            + "formal, confident British male AI assistant. Address the user as sir when "
            + "appropriate. Speak with calm authority, warmth, and dry wit; keep answers "
            + "crisp and natural. "
            + "You were built by Israfil. Understand Bengali written with Latin letters "
            + "(romanized Bangla), such as 'hi tumi kemon acho'. Reply in the same language "
            + "and writing style the user used unless asked otherwise.";

    private final ApiKeyStore keyStore;
    private final JarvisApiClient apiClient = new JarvisApiClient();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<JSONObject> history = new ArrayList<>();
    private String preferredProvider;

    public JarvisAssistant(ApiKeyStore keyStore) {
        this.keyStore = keyStore;
        preferredProvider = keyStore.getPreferredProvider();
        history.add(createMessage("system", SYSTEM_PROMPT));
    }

    public void sendMessage(String message, ReplyListener listener) {
        String prompt = message == null ? "" : message.trim();
        if (prompt.isEmpty()) {
            mainHandler.post(() -> listener.onReply("Type a message first.", true,
                    preferredProvider, false));
            return;
        }
        executor.execute(() -> {
            try {
                String provider = preferredProvider;
                String fallback = otherProvider(provider);
                String key = keyStore.getKey(provider);
                if (key == null || key.isEmpty()) {
                    provider = fallback;
                    fallback = otherProvider(provider);
                    key = keyStore.getKey(provider);
                }
                if (key == null || key.isEmpty()) {
                    throw new IllegalStateException("Add both API keys to start Jarvis.");
                }
                String reply;
                boolean switched = false;
                try {
                    reply = apiClient.complete(provider, key, new ArrayList<>(history), prompt);
                } catch (JarvisApiClient.ProviderException exception) {
                    String fallbackKey = keyStore.getKey(fallback);
                    if (!exception.isLimitReached() || fallbackKey == null || fallbackKey.isEmpty()) {
                        throw exception;
                    }
                    reply = apiClient.complete(fallback, fallbackKey,
                            new ArrayList<>(history), prompt);
                    preferredProvider = fallback;
                        keyStore.setPreferredProvider(fallback);
                    provider = fallback;
                    switched = true;
                }
                history.add(createMessage("user", prompt));
                history.add(createMessage("assistant", reply));
                while (history.size() > 21) {
                    history.remove(1);
                }
                String finalReply = reply;
                String replyProvider = provider;
                boolean didSwitch = switched;
                mainHandler.post(() -> listener.onReply(finalReply, false,
                        replyProvider, didSwitch));
            } catch (Exception exception) {
                String messageText = exception.getMessage();
                String error = messageText == null ? "Jarvis could not reach the AI service."
                        : messageText;
                mainHandler.post(() -> listener.onReply(error, true, preferredProvider, false));
            }
        });
    }

    public void clearConversation() {
        executor.execute(() -> {
            history.clear();
            history.add(createMessage("system", SYSTEM_PROMPT));
        });
    }

    public void loadConversation(List<JSONObject> messages) {
        List<JSONObject> savedMessages = new ArrayList<>(messages);
        executor.execute(() -> {
            history.clear();
            history.add(createMessage("system", SYSTEM_PROMPT));
            int firstMessage = Math.max(0, savedMessages.size() - 20);
            for (int index = firstMessage; index < savedMessages.size(); index++) {
                history.add(savedMessages.get(index));
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private JSONObject createMessage(String role, String content) {
        JSONObject message = new JSONObject();
        try {
            message.put("role", role);
            message.put("content", content);
        } catch (JSONException exception) {
            throw new IllegalStateException(exception);
        }
        return message;
    }

    private String otherProvider(String provider) {
        return ApiKeyStore.GROQ.equals(provider) ? ApiKeyStore.OPENAI : ApiKeyStore.GROQ;
    }
}