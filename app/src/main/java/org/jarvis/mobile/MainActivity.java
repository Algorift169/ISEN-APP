package org.jarvis.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
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

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.android.gms.tasks.Tasks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int RECORD_AUDIO_REQUEST = 41;
    private static final int IMAGE_PICK_REQUEST = 42;
    private static final int TEXT_PICK_REQUEST = 43;
    private static final int MAX_ATTACHMENT_CHARS = 50000;
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    private static final String TAG = "ISEN";
    private long attachmentSequence;
    private WebView webView;
    private ApiKeyStore keyStore;
    private ChatSessionStore sessionStore;
    private ChatSessionActions sessionActions;
    private JarvisAssistant assistant;
    private volatile long currentSessionId;
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService attachmentExecutor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer speechRecognizer;
    private JarvisSpeechOutput speechOutput;
    private boolean voiceModeActive;
    private String speechNotice = "";

    private static final class PreparedImage {
        final Bitmap bitmap;
        final String preview;

        PreparedImage(Bitmap bitmap, String preview) {
            this.bitmap = bitmap;
            this.preview = preview;
        }
    }

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
        attachmentExecutor.shutdownNow();
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
            sendChatMessage(message, message, null);
        }

        @JavascriptInterface
        public void sendAttachmentMessage(String message, String displayContent, String attachment) {
            sendChatMessage(message, displayContent, attachment);
        }

        @JavascriptInterface
        public void pickImage() {
            runOnUiThread(() -> openAttachmentPicker(IMAGE_PICK_REQUEST, "image/*"));
        }

        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(() -> openAttachmentPicker(TEXT_PICK_REQUEST, "*/*"));
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

    private void sendChatMessage(String message, String displayContent, String attachment) {
        long sessionId = currentSessionId;
        sessionStore.addMessage(sessionId, "user", message, displayContent, attachment);
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

    private void openAttachmentPicker(int requestCode, String mimeType) {
        Intent intent = requestCode == IMAGE_PICK_REQUEST
            ? new Intent(Intent.ACTION_GET_CONTENT)
            : new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        startActivityForResult(Intent.createChooser(intent,
            requestCode == IMAGE_PICK_REQUEST ? "Choose a photo" : "Choose a file"), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != IMAGE_PICK_REQUEST && requestCode != TEXT_PICK_REQUEST)
                || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        String name = getAttachmentName(uri);
        long attachmentId = ++attachmentSequence;
        runOnUiThread(() -> callPage("onAttachmentLoading", quote(name) + "," + attachmentId));
        String mimeType = getContentResolver().getType(uri);
        if (requestCode == IMAGE_PICK_REQUEST || (mimeType != null && mimeType.startsWith("image/"))) {
            attachmentExecutor.execute(() -> readImageText(uri, name, attachmentId));
        } else if ("application/pdf".equals(mimeType)
                || name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            attachmentExecutor.execute(() -> readPdf(uri, name, attachmentId));
        } else if (requestCode == TEXT_PICK_REQUEST) {
            attachmentExecutor.execute(() -> readTextFile(uri, name, attachmentId));
        } else {
            showAttachmentError("Choose a photo, PDF, or supported text file.", attachmentId);
        }
    }

    private String getAttachmentName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex);
                }
            }
        } catch (Exception ignored) {
        }
        return "Attachment";
    }

    private void readImageText(Uri uri, String name, long attachmentId) {
        PreparedImage prepared;
        try {
            prepared = createPreparedImage(uri);
        } catch (Exception exception) {
            Log.e(TAG, "Unable to decode selected image " + name, exception);
            String detail = exception.getMessage();
            showAttachmentError("Could not decode " + name + ". "
                + (detail == null || detail.isEmpty() ? "Choose a standard PNG or JPG image." : detail),
                attachmentId);
            return;
        }

        String preview = prepared.preview;
        runOnUiThread(() -> callPage("onAttachmentPreviewReady",
            quote(name) + "," + quote(preview) + "," + attachmentId));
        try {
            InputImage image = InputImage.fromBitmap(prepared.bitmap, 0);
            Bitmap imageBitmap = prepared.bitmap;
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        recognizer.close();
                        String text = result.getText().trim();
                        readImageLabels(image, name, text, preview, imageBitmap, attachmentId);
                    })
                    .addOnFailureListener(exception -> {
                        recognizer.close();
                        readImageLabels(image, name, "", preview, imageBitmap, attachmentId);
                    });
        } catch (Exception exception) {
            Log.e(TAG, "Unable to start local image recognition for " + name, exception);
            finishImageAnalysis(name, "", Collections.emptyList(), preview, prepared.bitmap,
                    attachmentId);
        }
    }

    private void readImageLabels(InputImage image, String name, String recognizedText,
                                 String preview, Bitmap bitmap, long attachmentId) {
        try {
            ImageLabeler labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);
            labeler.process(image)
                    .addOnSuccessListener(labels -> {
                        labeler.close();
                        ArrayList<String> descriptions = new ArrayList<>();
                        for (ImageLabel label : labels) {
                            if (label.getConfidence() >= 0.5f && descriptions.size() < 8) {
                                descriptions.add(label.getText());
                            }
                        }
                        finishImageAnalysis(name, recognizedText, descriptions, preview, bitmap,
                                attachmentId);
                    })
                    .addOnFailureListener(exception -> {
                        labeler.close();
                        finishImageAnalysis(name, recognizedText, Collections.emptyList(), preview,
                                bitmap, attachmentId);
                    });
        } catch (Exception exception) {
            finishImageAnalysis(name, recognizedText, Collections.emptyList(), preview, bitmap,
                    attachmentId);
        }
    }

    private void finishImageAnalysis(String name, String recognizedText, List<String> labels,
                                     String preview, Bitmap bitmap, long attachmentId) {
        StringBuilder analysis = new StringBuilder();
        if (!labels.isEmpty()) {
            analysis.append("Image labels: ");
            for (int index = 0; index < labels.size(); index++) {
                if (index > 0) {
                    analysis.append(", ");
                }
                analysis.append(labels.get(index));
            }
            analysis.append('\n');
        }
        if (!recognizedText.isEmpty()) {
            analysis.append("Text in image:\n").append(recognizedText);
        }
        if (analysis.length() == 0) {
            analysis.append("No readable text or recognizable labels were detected locally.");
        }
        String extractedContent = limitAttachment(analysis.toString());
        String previewDataUrl = preview;
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
        runOnUiThread(() -> callPage("onAttachmentReady",
            quote(name) + "," + quote(extractedContent) + "," + quote(previewDataUrl)
                + "," + attachmentId));
    }

        private void readPdf(Uri uri, String name, long attachmentId) {
        try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(uri, "r");
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            StringBuilder extracted = new StringBuilder();
            String preview = "";
            int pageCount = Math.min(renderer.getPageCount(), 8);
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PdfRenderer.Page page = renderer.openPage(pageIndex);
                float scale = Math.min(1.5f, 1200f / Math.max(page.getWidth(), page.getHeight()));
                Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (page.getWidth() * scale)),
                        Math.max(1, (int) (page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                if (pageIndex == 0) {
                    preview = bitmapToDataUrl(bitmap);
                }
                String pageText = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                        .getText().trim();
                if (!pageText.isEmpty()) {
                    extracted.append("Page ").append(pageIndex + 1).append(":\n")
                            .append(pageText).append('\n');
                }
                bitmap.recycle();
            }
            recognizer.close();
            if (pageCount < renderer.getPageCount()) {
                extracted.append("[Only the first ").append(pageCount).append(" pages were read.]\n");
            }
            String content = extracted.toString().trim();
            if (content.isEmpty()) {
                content = "No readable text was detected in the first " + pageCount + " PDF pages.";
            }
            String extractedContent = limitAttachment(content);
            String previewDataUrl = preview;
            runOnUiThread(() -> callPage("onAttachmentReady",
                    quote(name) + "," + quote(extractedContent) + "," + quote(previewDataUrl)
                        + "," + attachmentId));
        } catch (Exception exception) {
                showAttachmentError("Could not read " + name + " on this device.", attachmentId);
        }
    }

    private PreparedImage createPreparedImage(Uri uri) throws IOException {
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("The selected photo provider returned no data.");
            }
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (imageBytes.size() + count > MAX_IMAGE_BYTES) {
                    throw new IOException("The selected image exceeds the 32 MB local processing limit.");
                }
                imageBytes.write(buffer, 0, count);
            }
        }
        byte[] encodedImage = imageBytes.toByteArray();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encodedImage, 0, encodedImage.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Android could not read this image format. Try sharing the original photo, not a preview.");
        }
        int sampleSize = 1;
        while ((bounds.outWidth / sampleSize > 1600
                || bounds.outHeight / sampleSize > 1600) && sampleSize < (1 << 30)) {
            sampleSize <<= 1;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sampleSize);
        Bitmap bitmap = BitmapFactory.decodeByteArray(encodedImage, 0, encodedImage.length, options);
        if (bitmap == null) {
            throw new IOException("Android could not decode this image. Try a standard PNG or JPG.");
        }
        return new PreparedImage(bitmap, bitmapToDataUrl(bitmap));
    }

    private String bitmapToDataUrl(Bitmap bitmap) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output);
        return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    private void readTextFile(Uri uri, String name, long attachmentId) {
        String mimeType = getContentResolver().getType(uri);
        String lowerName = name.toLowerCase(Locale.ROOT);
        boolean supportedExtension = lowerName.endsWith(".txt") || lowerName.endsWith(".md")
                || lowerName.endsWith(".csv") || lowerName.endsWith(".json")
                || lowerName.endsWith(".xml") || lowerName.endsWith(".html")
                || lowerName.endsWith(".log");
        if ((mimeType == null || !mimeType.startsWith("text/")) && !supportedExtension) {
                showAttachmentError("Choose an image or a text file (TXT, MD, CSV, JSON, XML, HTML, or LOG).",
                    attachmentId);
            return;
        }
        try (InputStream stream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1 && content.length() < MAX_ATTACHMENT_CHARS) {
                int remaining = MAX_ATTACHMENT_CHARS - content.length();
                content.append(buffer, 0, Math.min(count, remaining));
            }
            String text = content.toString().trim();
            if (text.isEmpty()) {
                showAttachmentError(name + " is empty.", attachmentId);
            } else {
                String extractedContent = limitAttachment(text);
                runOnUiThread(() -> callPage("onAttachmentReady", quote(name) + ","
                    + quote(extractedContent) + "," + quote("file:" + name) + "," + attachmentId));
            }
        } catch (Exception exception) {
            showAttachmentError("Could not read " + name + ".", attachmentId);
        }
    }

    private void showAttachmentError(String message, long attachmentId) {
        runOnUiThread(() -> callPage("onAttachmentError", quote(message) + "," + attachmentId));
    }

    private String limitAttachment(String text) {
        if (text.length() <= MAX_ATTACHMENT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_ATTACHMENT_CHARS) + "\n[Content truncated]";
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