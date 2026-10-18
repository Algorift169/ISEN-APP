#!/usr/bin/env bash
set -euo pipefail

ANDROID_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_VERSION="${GRADLE_VERSION:-8.9}"
GRADLE_HOME="$HOME/.local/share/jarvis/gradle-${GRADLE_VERSION}"
JDK_HOME="$HOME/.local/share/jarvis/jdk-17"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" && -f "$ANDROID_DIR/local.properties" ]]; then
    ANDROID_SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$ANDROID_DIR/local.properties" | head -n 1)"
fi
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"

fail() {
    printf 'Error: %s\n' "$1" >&2
    exit 1
}

download_file() {
    curl --fail --location --show-error --progress-bar --retry 3 --retry-delay 2 \
        "$1" -o "$2"
}

if [[ "$EUID" -eq 0 ]]; then
    fail "Do not run this script with sudo. Run ./android/deps.sh as your normal user."
fi

java_major_version() {
    local java_bin="$1"
    local version_line
    version_line="$("$java_bin" -version 2>&1 | head -n 1)"
    if [[ "$version_line" =~ \"([0-9]+)(\.[0-9]+)? ]]; then
        printf '%s' "${BASH_REMATCH[1]}"
    else
        printf '0'
    fi
}

install_apt_packages() {
    local packages=("$@")
    command -v apt-get >/dev/null 2>&1 || fail \
        "apt-get is unavailable. Install these packages manually: ${packages[*]}"
    if [[ "$EUID" -eq 0 ]]; then
        apt-get update
        apt-get install -y "${packages[@]}"
    elif command -v sudo >/dev/null 2>&1; then
        sudo apt-get update
        sudo apt-get install -y "${packages[@]}"
    else
        fail "Install these packages with root or sudo: ${packages[*]}"
    fi
}

packages=()
for tool in curl unzip; do
    command -v "$tool" >/dev/null 2>&1 || packages+=("$tool")
done
if ((${#packages[@]} > 0)); then
    printf 'Installing required download tools: %s\n' "${packages[*]}"
    install_apt_packages "${packages[@]}"
fi

printf 'Checking for JDK 17...\n'
JAVA_BIN=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]] \
    && [[ "$(java_major_version "$JAVA_HOME/bin/java")" == 17 ]]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1 \
    && [[ "$(java_major_version "$(command -v java)")" == 17 ]]; then
    JAVA_BIN="$(command -v java)"
else
    for candidate in /usr/lib/jvm/java-17*/bin/java; do
        if [[ -x "$candidate" ]]; then
            JAVA_BIN="$candidate"
            break
        fi
    done
fi
if [[ -z "$JAVA_BIN" && -x "$JDK_HOME/bin/java" ]] \
    && [[ "$(java_major_version "$JDK_HOME/bin/java")" == 17 ]]; then
    JAVA_BIN="$JDK_HOME/bin/java"
fi
if [[ -z "$JAVA_BIN" ]]; then
    case "$(uname -m)" in
        x86_64) JDK_ARCH="x64" ;;
        aarch64|arm64) JDK_ARCH="aarch64" ;;
        *) fail "Unsupported CPU architecture for automatic JDK install: $(uname -m)" ;;
    esac
    TEMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TEMP_DIR"' EXIT
    printf 'Downloading Eclipse Temurin JDK 17...\n'
    download_file \
        "https://api.adoptium.net/v3/binary/latest/17/ga/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse" \
        "$TEMP_DIR/jdk.tar.gz"
    printf 'Extracting JDK 17...\n'
    tar -xzf "$TEMP_DIR/jdk.tar.gz" -C "$TEMP_DIR"
    EXTRACTED_JDK="$(find "$TEMP_DIR" -mindepth 1 -maxdepth 1 -type d -name 'jdk-17*' -print -quit)"
    [[ -n "$EXTRACTED_JDK" ]] || fail "The downloaded JDK archive had an unexpected layout."
    mkdir -p "$(dirname -- "$JDK_HOME")"
    [[ ! -e "$JDK_HOME" ]] || fail "JDK install directory exists but is incomplete: $JDK_HOME"
    mv "$EXTRACTED_JDK" "$JDK_HOME"
    JAVA_BIN="$JDK_HOME/bin/java"
    rm -rf "$TEMP_DIR"
    trap - EXIT
fi
JAVA_BIN="$(readlink -f "$JAVA_BIN")"
export JAVA_HOME="$(cd -- "$(dirname -- "$JAVA_BIN")/.." && pwd -P)"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
    GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
    TEMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TEMP_DIR"' EXIT
    printf 'Downloading Gradle %s...\n' "$GRADLE_VERSION"
    download_file "$GRADLE_URL" "$TEMP_DIR/gradle.zip"
    download_file "${GRADLE_URL}.sha256" "$TEMP_DIR/gradle.sha256"
    GRADLE_SHA="$(awk '{print $1}' "$TEMP_DIR/gradle.sha256")"
    [[ "$GRADLE_SHA" =~ ^[[:xdigit:]]{64}$ ]] \
        || fail "Could not read the Gradle distribution checksum."
    printf '%s  %s\n' "$GRADLE_SHA" "$TEMP_DIR/gradle.zip" | sha256sum --check --status \
        || fail "Gradle distribution checksum did not match."
    printf 'Extracting Gradle %s...\n' "$GRADLE_VERSION"
    unzip -q "$TEMP_DIR/gradle.zip" -d "$TEMP_DIR"
    mkdir -p "$(dirname -- "$GRADLE_HOME")"
    [[ ! -e "$GRADLE_HOME" ]] || fail \
        "Gradle install directory exists but is incomplete: $GRADLE_HOME"
    mv "$TEMP_DIR/gradle-${GRADLE_VERSION}" "$GRADLE_HOME"
    rm -rf "$TEMP_DIR"
    trap - EXIT
fi

mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
    SDK_TOOLS_DIR="$ANDROID_SDK_ROOT/cmdline-tools/latest"
    if [[ -L "$SDK_TOOLS_DIR" && ! -e "$SDK_TOOLS_DIR" ]]; then
        printf 'Removing broken Android SDK tools symlink: %s\n' "$SDK_TOOLS_DIR"
        rm -- "$SDK_TOOLS_DIR"
    fi
    [[ ! -e "$SDK_TOOLS_DIR" ]] || fail \
        "Android command-line tools directory exists but sdkmanager is missing: $SDK_TOOLS_DIR"
    TEMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TEMP_DIR"' EXIT
    printf 'Downloading Android command-line tools...\n'
    download_file \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
        "$TEMP_DIR/android-tools.zip"
    printf 'Extracting Android command-line tools...\n'
    unzip -q "$TEMP_DIR/android-tools.zip" -d "$TEMP_DIR"
    mv "$TEMP_DIR/cmdline-tools" "$SDK_TOOLS_DIR"
    SDKMANAGER="$SDK_TOOLS_DIR/bin/sdkmanager"
fi

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

printf 'Accept the Android SDK licenses when prompted.\n'
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses
printf 'Installing Android SDK Platform 35, Build Tools 35.0.0, and Platform Tools...\n'
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"

SDK_DIR_PROPERTY="${ANDROID_SDK_ROOT//\\/\\\\}"
SDK_DIR_PROPERTY="${SDK_DIR_PROPERTY//:/\\:}"
SDK_DIR_PROPERTY="${SDK_DIR_PROPERTY// /\\ }"
SDK_DIR_PROPERTY="${SDK_DIR_PROPERTY//&/\\&}"
SDK_DIR_PROPERTY="${SDK_DIR_PROPERTY//|/\\|}"
if [[ -f "$ANDROID_DIR/local.properties" ]] \
    && grep -q '^sdk\.dir=' "$ANDROID_DIR/local.properties"; then
    sed -i "s|^sdk\\.dir=.*|sdk.dir=$SDK_DIR_PROPERTY|" "$ANDROID_DIR/local.properties"
else
    printf 'sdk.dir=%s\n' "$SDK_DIR_PROPERTY" >> "$ANDROID_DIR/local.properties"
fi
printf '\nAndroid dependencies installed. Build the APK from the repository root with:\n'
printf '  make app\n'
printf 'Gradle: %s\n' "$GRADLE_HOME/bin/gradle"
printf 'SDK: %s\n' "$ANDROID_SDK_ROOT"