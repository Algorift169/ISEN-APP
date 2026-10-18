package org.jarvis.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import org.jarvis.mobile.api.ApiKeyStore;
import org.jarvis.mobile.jarvis.ChatSessionActions;
import org.jarvis.mobile.jarvis.ChatSessionStore;
import org.jarvis.mobile.jarvis.JarvisAssistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int RECORD_AUDIO_REQUEST = 41;
    private WebView webView;
    private ApiKeyStore keyStore;
    private ChatSessionStore sessionStore;
    private ChatSessionActions sessionActions;
    private JarvisAssistant assistant;
    private volatile long currentSessionId;
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer speechRecognizer;
    private JarvisSpeechOutput speechOutput;
    private boolean voiceModeActive;
    private String speechNotice = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        keyStore = new ApiKeyStore(this);
        sessionStore = new ChatSessionStore(this);
        assistant = new JarvisAssistant(keyStore);
        sessionActions = new ChatSessionActions(this, sessionStore, assistant);
        speechOutput = new JarvisSpeechOutput(this, new JarvisSpeechOutput.Listener() {
            @Override
            public void onSpeechStarted() {
                if (voiceModeActive) {
                    callPage("onVoiceStatus", quote("ISEN is speaking..."));
                }
            }

            @Override
            public void onQueueFinished() {
                restartListening();
            }

            @Override
            public void onInitializationFailed() {
                if (voiceModeActive) {
                    callPage("onVoiceStatus", quote("Text-to-speech is unavailable."));
                }
            }

            @Override
            public void onVoiceNotice(String notice) {
                speechNotice = notice;
                if (voiceModeActive) {
                    callPage("onVoiceStatus", quote(notice));
                }
            }
        });
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        webView.addJavascriptInterface(new JarvisBridge(), "Isen");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme())
                        && ("console.groq.com".equals(uri.getHost())
                        || "platform.openai.com".equals(uri.getHost()))) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return !("file".equals(uri.getScheme())
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/android_asset/"));
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (voiceModeActive) {
            stopVoiceMode();
            callPage("onVoiceClosed", "");
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        voiceModeActive = false;
        voiceHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        if (speechOutput != null) {
            speechOutput.shutdown();
        }
        sessionStore.close();
        assistant.shutdown();
        if (webView != null) {
            webView.removeJavascriptInterface("Isen");
            webView.destroy();
        }
        super.onDestroy();
    }

    private final class JarvisBridge {
        @JavascriptInterface
        public void requestState() {
            long sessionId = sessionStore.getLatestSessionId();
            if (sessionId == 0) {
                sessionId = sessionStore.createSession();
            }
            currentSessionId = sessionId;
            assistant.loadConversation(sessionStore.getAssistantHistory(sessionId));
            String sessionState = sessionStore.getSessionState(sessionId).toString();
            runOnUiThread(() -> {
                callPage("onIsenState", stateJson());
                callPage("onSessionLoaded", sessionState);
            });
        }

        @JavascriptInterface
        public void requestSessions() {
            String sessions = sessionStore.getSessions(currentSessionId).toString();
            runOnUiThread(() -> callPage("onSessionsState", sessions));
        }

        @JavascriptInterface
        public void newSession() {
            long sessionId = sessionStore.createSession();
            currentSessionId = sessionId;
            assistant.loadConversation(Collections.emptyList());
            String sessionState = sessionStore.getSessionState(sessionId).toString();
            runOnUiThread(() -> callPage("onSessionLoaded", sessionState));
        }

        @JavascriptInterface
        public void openSession(long sessionId) {
            if (!sessionStore.hasSession(sessionId)) {
                return;
            }
            currentSessionId = sessionId;
            assistant.loadConversation(sessionStore.getAssistantHistory(sessionId));
            String sessionState = sessionStore.getSessionState(sessionId).toString();
            runOnUiThread(() -> callPage("onSessionLoaded", sessionState));
        }

        @JavascriptInterface
        public void deleteSession(long sessionId) {
            runOnUiThread(() -> sessionActions.confirmDelete(sessionId, currentSessionId,
                    (activeId, activeSession) -> {
                        currentSessionId = activeId;
                        if (activeSession != null) {
                            callPage("onSessionLoaded", activeSession.toString());
                        } else {
                            callPage("onSessionsState", sessionStore.getSessions(activeId).toString());
                        }
                    }));
        }

        @JavascriptInterface
        public void saveKeys(String groqKey, String openAiKey) {
            try {
                if (ApiKeyStore.isBlank(groqKey) && !keyStore.hasKey(ApiKeyStore.GROQ)
                    || ApiKeyStore.isBlank(openAiKey) && !keyStore.hasKey(ApiKeyStore.OPENAI)) {
                    runOnUiThread(() -> callPage("onIsenError",
                            quote("Both API keys are required for automatic failover.")));
                    return;
                }
                keyStore.saveKeys(groqKey, openAiKey);
                runOnUiThread(() -> callPage("onIsenKeysSaved", stateJson()));
            } catch (Exception exception) {
                runOnUiThread(() -> callPage("onIsenError", quote("Could not save keys securely.")));
            }
        }

        @JavascriptInterface
        public void clearKeys() {
            keyStore.clearKeys();
            assistant.clearConversation();
            runOnUiThread(() -> callPage("onIsenKeysCleared", stateJson()));
        }

        @JavascriptInterface
        public void sendMessage(String message) {
            long sessionId = currentSessionId;
            sessionStore.addMessage(sessionId, "user", message);
            runOnUiThread(() -> callPage("onSessionsState",
                    sessionStore.getSessions(currentSessionId).toString()));
            assistant.sendMessage(message, (reply, failed, provider, switched) -> {
                if (sessionStore.hasSession(sessionId)) {
                    sessionStore.addMessage(sessionId, "assistant", reply);
                }
                runOnUiThread(() -> {
                    callPage("onIsenReply", quote(reply) + "," + failed + ","
                            + quote(provider) + "," + switched + "," + sessionId);
                    callPage("onSessionsState", sessionStore.getSessions(currentSessionId).toString());
                    speakReply(reply);
                });
            });
        }

        @JavascriptInterface
        public void startVoiceMode() {
            runOnUiThread(() -> {
                voiceModeActive = true;
                String status = speechNotice.isEmpty()
                        ? "Preparing voice chat..." : speechNotice;
                callPage("onVoiceStatus", quote(status));
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                            RECORD_AUDIO_REQUEST);
                } else {
                    beginListening();
                }
            });
        }

        @JavascriptInterface
        public void stopVoiceMode() {
            runOnUiThread(MainActivity.this::stopVoiceMode);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != RECORD_AUDIO_REQUEST || !voiceModeActive) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            beginListening();
        } else {
            voiceModeActive = false;
            callPage("onVoiceStatus", quote("Microphone access is required for voice chat."));
            callPage("onVoiceClosed", "");
        }
    }

    private String stateJson() {
        JSONObject state = new JSONObject();
        try {
            state.put("groqReady", keyStore.hasKey(ApiKeyStore.GROQ));
            state.put("openAiReady", keyStore.hasKey(ApiKeyStore.OPENAI));
            state.put("configured", keyStore.isConfigured());
        } catch (Exception exception) {
            return "{\"storageError\":true}";
        }
        return state.toString();
    }

    private void callPage(String function, String arguments) {
        webView.evaluateJavascript("window." + function + "(" + arguments + ")", null);
    }

    private String quote(String value) {
        return JSONObject.quote(value);
    }

    private void beginListening() {
        if (!voiceModeActive) {
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceModeActive = false;
            callPage("onVoiceStatus", quote("Speech recognition is unavailable on this device."));
            callPage("onVoiceClosed", "");
            return;
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    callPage("onVoiceStatus", quote("Listening. Speak now."));
                }

                @Override
                public void onBeginningOfSpeech() {
                    callPage("onVoiceStatus", quote("Hearing you..."));
                }

                @Override
                public void onRmsChanged(float rmsdB) { }

                @Override
                public void onBufferReceived(byte[] buffer) { }

                @Override
                public void onEndOfSpeech() {
                    callPage("onVoiceStatus", quote("Thinking..."));
                }

                @Override
                public void onError(int error) {
                    if (voiceModeActive) {
                        callPage("onVoiceStatus", quote("I didn't catch that. Listening again..."));
                        restartListening();
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches == null || matches.isEmpty()) {
                        restartListening();
                        return;
                    }
                    callPage("onVoiceResult", quote(matches.get(0)));
                }

                @Override
                public void onPartialResults(Bundle partialResults) { }

                @Override
                public void onEvent(int eventType, Bundle params) { }
            });
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        speechRecognizer.startListening(intent);
    }

    private void restartListening() {
        voiceHandler.postDelayed(() -> {
            if (voiceModeActive) {
                beginListening();
            }
        }, 700);
    }

    private void speakReply(String reply) {
        if (voiceModeActive && speechOutput != null) {
            speechOutput.speak(reply);
        }
    }

    private void stopVoiceMode() {
        voiceModeActive = false;
        voiceHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
        }
        if (speechOutput != null) {
            speechOutput.stop();
        }
        callPage("onVoiceStatus", quote("Voice chat ended."));
        callPage("onVoiceClosed", "");
    }
}