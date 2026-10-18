package org.jarvis.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

public final class JarvisSpeechOutput {
    public interface Listener {
        void onSpeechStarted();
        void onQueueFinished();
        void onInitializationFailed();
        void onVoiceNotice(String notice);
    }

    private static final String TAG = "IsenTTS";
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Queue<String> queue = new ArrayDeque<>();
    private final Listener listener;
    private final TextToSpeech engine;
    private boolean initialized;
    private boolean initializationFailed;
    private boolean speaking;
    private boolean closed;
    private String activeId;

    public JarvisSpeechOutput(Context context, Listener listener) {
        this.listener = listener;
        engine = new TextToSpeech(context.getApplicationContext(),
                status -> mainHandler.post(() -> finishInitialization(status)));
    }

    public void speak(String text) {
        String speakableText = JarvisSpeechText.clean(text);
        if (speakableText.isEmpty()) {
            return;
        }
        runOnMain(() -> {
            if (closed) {
                return;
            }
            if (initializationFailed) {
                listener.onInitializationFailed();
                listener.onQueueFinished();
                return;
            }
            queue.add(speakableText);
            speakNext();
        });
    }

    public void stop() {
        runOnMain(() -> {
            queue.clear();
            activeId = null;
            speaking = false;
            if (initialized) {
                engine.stop();
            }
        });
    }

    public void shutdown() {
        runOnMain(() -> {
            closed = true;
            queue.clear();
            activeId = null;
            speaking = false;
            engine.stop();
            engine.shutdown();
        });
    }

    private void finishInitialization(int status) {
        if (closed) {
            return;
        }
        if (status != TextToSpeech.SUCCESS) {
            initializationFailed = true;
            Log.e(TAG, "TextToSpeech initialization failed: " + status);
            queue.clear();
            listener.onInitializationFailed();
            return;
        }
        initialized = true;
        JarvisSpeechProfile.Selection selection = JarvisSpeechProfile.configure(engine);
        if (!selection.notice.isEmpty()) {
            listener.onVoiceNotice(selection.notice);
        }
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                mainHandler.post(() -> {
                    if (utteranceId.equals(activeId)) {
                        listener.onSpeechStarted();
                    }
                });
            }

            @Override
            public void onDone(String utteranceId) {
                mainHandler.post(() -> finishUtterance(utteranceId));
            }

            @Override
            public void onError(String utteranceId) {
                mainHandler.post(() -> finishUtterance(utteranceId));
            }
        });
        speakNext();
    }

    private void speakNext() {
        if (!initialized || speaking || closed) {
            return;
        }
        String text = queue.poll();
        if (text == null) {
            return;
        }
        speaking = true;
        activeId = "isen-" + NEXT_ID.incrementAndGet();
        int result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeId);
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Could not queue speech: " + result);
            finishUtterance(activeId);
        }
    }

    private void finishUtterance(String utteranceId) {
        if (!speaking || !utteranceId.equals(activeId)) {
            return;
        }
        speaking = false;
        activeId = null;
        if (queue.isEmpty()) {
            listener.onQueueFinished();
        } else {
            speakNext();
        }
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }
}
