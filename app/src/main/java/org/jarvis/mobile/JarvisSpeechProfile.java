package org.jarvis.mobile;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.Locale;
import java.util.Set;

public final class JarvisSpeechProfile {
    private static final String TAG = "IsenTTS";
    private static final float SPEECH_RATE = 1.10f;
    private static final float SPEECH_PITCH = 0.88f;

    public static final class Selection {
        public final String voiceName;
        public final boolean identifiedMaleVoice;
        public final String notice;

        Selection(String voiceName, boolean identifiedMaleVoice, String notice) {
            this.voiceName = voiceName;
            this.identifiedMaleVoice = identifiedMaleVoice;
            this.notice = notice;
        }
    }

    private JarvisSpeechProfile() { }

    public static Selection configure(TextToSpeech engine) {
        Voice currentVoice = engine.getVoice();
        Voice selected = isMaleEnglish(currentVoice) ? currentVoice
                : findMaleEnglishVoice(engine.getVoices());
        boolean male = selected != null;
        String notice = "";

        if (selected != null && selected != currentVoice) {
            int result = engine.setVoice(selected);
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "setVoice failed for " + selected.getName() + ": " + result);
                selected = null;
                male = false;
            }
        }
        if (selected == null) {
            if (!isEnglish(currentVoice)) {
                int result = engine.setLanguage(Locale.UK);
                if (result < TextToSpeech.LANG_AVAILABLE) {
                    Log.w(TAG, "British English unavailable: " + result);
                }
            }
            selected = engine.getVoice();
            notice = "No identifiable male English voice is installed. Install English voice data "
                    + "and choose a male voice in Android Settings > Text-to-speech output.";
        }

        int rateResult = engine.setSpeechRate(SPEECH_RATE);
        int pitchResult = engine.setPitch(SPEECH_PITCH);
        if (rateResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Could not set speech rate: " + rateResult);
        }
        if (pitchResult != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Could not set natural pitch: " + pitchResult);
        }

        String name = selected == null ? "unknown" : selected.getName();
        Log.i(TAG, "Selected voice=" + name + ", identifiedMale=" + male + ", locale="
                + (selected == null ? "unknown" : selected.getLocale()));
        return new Selection(name, male, notice);
    }

    private static Voice findMaleEnglishVoice(Set<Voice> voices) {
        if (voices == null) {
            return null;
        }
        Voice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Voice voice : voices) {
            Log.d(TAG, "Installed voice=" + voice.getName() + ", locale=" + voice.getLocale()
                    + ", features=" + voice.getFeatures());
            if (!isMaleEnglish(voice)) {
                continue;
            }
            int score = "GB".equalsIgnoreCase(voice.getLocale().getCountry()) ? 1000 : 0;
            score += voice.getQuality();
            if (!voice.isNetworkConnectionRequired()) {
                score += 10;
            }
            if (score > bestScore) {
                best = voice;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean isMaleEnglish(Voice voice) {
        if (!isEnglish(voice)) {
            return false;
        }
        String details = (voice.getName() + " " + voice.getFeatures()).toLowerCase(Locale.ROOT);
        if (contains(details, "female", "feminine", "woman", "samantha", "victoria")) {
            return false;
        }
        return contains(details, "male", "masculine", "thomas", "david", "daniel",
                "george", "arthur", "james", "oliver", "henry", "en-gb-x-gbb",
                "en-gb-x-gbd");
    }

    private static boolean isEnglish(Voice voice) {
        return voice != null && voice.getLocale() != null
                && "en".equals(voice.getLocale().getLanguage());
    }

    private static boolean contains(String value, String... markers) {
        for (String marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
