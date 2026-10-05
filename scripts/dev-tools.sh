#!/usr/bin/env bash
# User-space toolchain (no sudo). Usage: bash scripts/dev-tools.sh spark2|laptop
#  spark2: Erlang/Elixir/Phoenix + Java/Gradle + Android SDK (no emulator) + arm64 build-tools
#  laptop: Erlang/Elixir (optional use) + Java/Gradle + Android SDK + emulator + AVD
# Claude Code may bump versions to the latest stable values and record that in docs/decisions/.
set -euo pipefail
TARGET="${1:?usage: dev-tools.sh spark2|laptop}"
ERLANG=28; ELIXIR=1.19
JAVA=temurin-17; GRADLE=8
ANDROID_API=36; BUILD_TOOLS=36.1.0

command -v mise >/dev/null 2>&1 || [ -x "$HOME/.local/bin/mise" ] || curl -fsSL https://mise.run | sh
export PATH="$HOME/.local/bin:$PATH"
grep -q 'mise activate' ~/.bashrc || echo 'eval "$(~/.local/bin/mise activate bash)"' >> ~/.bashrc

export KERL_CONFIGURE_OPTIONS="--without-javac --without-wx --without-odbc"
mise use -g "erlang@$ERLANG" "elixir@$ELIXIR" "java@$JAVA" "gradle@$GRADLE"
mise exec -- mix local.hex --force
mise exec -- mix local.rebar --force
mise exec -- mix archive.install hex phx_new --force

# ---- Android SDK (command-line tools are Java, so they run on both architectures) ----
ANDROID_HOME="$HOME/Android/Sdk"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKM" ]; then
  CMDLINE_TOOLS_URL="${CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
  mkdir -p "$ANDROID_HOME/cmdline-tools" && tmp=$(mktemp -d)
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp/clt.zip" && unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" && mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
grep -q 'ANDROID_HOME' ~/.bashrc || cat >> ~/.bashrc <<RC
export ANDROID_HOME="\$HOME/Android/Sdk"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"
RC
export ANDROID_HOME PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
# Newer cmdline-tools (23.0 seen on spark2) wrap a native x86-64 'android' binary, so sdkmanager
# cannot run on aarch64. Skip it there once the components exist.
if [ "$(uname -m)" = "aarch64" ] && [ -d "$ANDROID_HOME/platforms/android-$ANDROID_API" ] \
   && [ -d "$ANDROID_HOME/build-tools/$BUILD_TOOLS" ]; then
  echo "aarch64: SDK components present, skipping sdkmanager"
else
  yes | mise exec -- sdkmanager --licenses >/dev/null || true
  mise exec -- sdkmanager "cmdline-tools;latest" "platforms;android-$ANDROID_API" "build-tools;$BUILD_TOOLS"
fi
# sdkmanager self-update lands in latest-2 when latest exists: promote it
if [ -d "$ANDROID_HOME/cmdline-tools/latest-2" ]; then
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" && mv "$ANDROID_HOME/cmdline-tools/latest-2" "$ANDROID_HOME/cmdline-tools/latest"
fi

if [ "$TARGET" = "spark2" ]; then
  [ "$(uname -m)" = "aarch64" ] || { echo "spark2 target expects aarch64"; exit 1; }
  # Swap in arm64 aapt2/aidl/zipalign/split-select (community build: review the release before trusting it)
  curl -fsSL https://raw.githubusercontent.com/Commit451/android-arm-build-tools/main/install.sh -o /tmp/arm-bt.sh
  "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2" version 2>/dev/null || bash /tmp/arm-bt.sh --version "$BUILD_TOOLS" --sdk "$ANDROID_HOME"
  "$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2" version
  # AGP 9 pulls its own x86 aapt2 from Maven: override it for THIS MACHINE ONLY (never in the repo)
  mkdir -p ~/.gradle
  sed -i '/android.aapt2FromMavenOverride/d' ~/.gradle/gradle.properties 2>/dev/null || true
  echo "android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/$BUILD_TOOLS/aapt2" >> ~/.gradle/gradle.properties
else
  mise exec -- sdkmanager "platform-tools" "emulator" "system-images;android-$ANDROID_API;google_apis;x86_64"
  echo no | mise exec -- avdmanager create avd -n risime_a -k "system-images;android-$ANDROID_API;google_apis;x86_64" -d pixel_8 || true
fi
echo "Toolchain ready on $TARGET"
