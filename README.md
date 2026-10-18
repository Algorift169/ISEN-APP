# ISEN for Android

This is a Java Android app. Its interface is HTML/CSS in a WebView, and a small
JavaScript bridge sends chat and key-management actions to Java. GTK is not used:
it is not a supported UI toolkit for a standard installable Android app.

## Build

From the repository root, install JDK 17, Gradle 8.9, and Android SDK Platform
35/build-tools with:

```sh
./android/deps.sh
```

Run the script as your normal user (not with `sudo`). It installs JDK 17 and
Gradle into your user data directory, installs Android SDK tools, and asks you
to accept the Android SDK licenses. Then build with:

```sh
make app
```

The debug APK is copied to `build/isen.apk`.

## First launch and chat

The first launch asks for both the Groq and OpenAI API keys. After setup, the
app opens directly to chat on later launches. Keys are encrypted with Android
Keystore-backed AES-GCM and saved only on this device. ISEN is powered by the
existing Jarvis AI core. It uses Groq first
and automatically switches to OpenAI when a rate or quota limit is reported;
the preferred provider is remembered. Groq uses `openai/gpt-oss-20b`, and OpenAI
uses `gpt-4o-mini`. **API Keys** in the app lets you update or clear saved keys.

Tap the microphone beside the send arrow to start voice chat; tap it again to
stop listening and speech. No separate voice window opens. Android will ask for
microphone permission; ISEN uses a masculine English TTS voice when available,
preferring British English. Speech uses a natural pitch and a slightly slower
rate. Android's voice metadata does not standardize gender, so a male voice is
only selected when its installed name/features identify it. If none is found,
install English voice data and choose a male voice in Android Settings under
Accessibility (or General management) > Text-to-speech output. No remote TTS
service is used. Android speech recognition handles voice input. The assistant
understands romanized Bangla typed in Latin characters and follows the user's
language/script when replying. Chat messages are saved locally in timestamp-
named sessions and remain available after app restarts. The supplied
`jarvis.jpeg` is used as the app background and launcher icon.

The Android app is separate from the Python desktop assistant in the repository
root. It currently supports text chat and does not execute desktop-control
commands from the Python assistant.
