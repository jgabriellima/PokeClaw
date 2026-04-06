#!/usr/bin/env bash
# PokeClaw — bootstrap JDK 17 + Android SDK for headless / CI / minimal machines.
# Idempotent: safe to re-run. Override URLs with env vars documented below.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# --- tunables ---
: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-$HOME/Android/Sdk}}"
: "${ANDROID_CMDLINE_TOOLS_URL:=https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
: "${REQUIRED_JAVA_MAJOR:=17}"
# PokeClaw: compileSdk 36, AGP 9.1 (JDK 17)
: "${ANDROID_PLATFORM:=android-36}"
: "${ANDROID_BUILD_TOOLS:=36.1.0}"

RED='\033[0;31m'; GRN='\033[0;32m'; YEL='\033[0;33m'; DIM='\033[2m'; RST='\033[0m'
info() { echo -e "${GRN}[setup]${RST} $*"; }
warn() { echo -e "${YEL}[setup]${RST} $*"; }
err() { echo -e "${RED}[setup]${RST} $*" >&2; }

have_cmd() { command -v "$1" >/dev/null 2>&1; }

detect_java_major() {
  if have_cmd java; then
    java -version 2>&1 | head -1 | sed -E 's/.* version "([0-9]+)(\.|$).*/\1/'
  else
    echo "0"
  fi
}

ensure_java() {
  local major
  major="$(detect_java_major || echo 0)"
  if [[ "$major" -ge "$REQUIRED_JAVA_MAJOR" ]]; then
    info "Java OK (major=$major, need >=$REQUIRED_JAVA_MAJOR)"
    if [[ -z "${JAVA_HOME:-}" ]] && have_cmd java; then
      if [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v "$REQUIRED_JAVA_MAJOR" 2>/dev/null || /usr/libexec/java_home)"
      elif java_path="$(command -v java)" && readlink -f / >/dev/null 2>&1; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$java_path")")")"
      fi
      export JAVA_HOME
      [[ -n "${JAVA_HOME:-}" ]] && info "JAVA_HOME was unset — now JAVA_HOME=$JAVA_HOME"
    fi
    return 0
  fi

  warn "Java $REQUIRED_JAVA_MAJOR+ not found (found major=$major). Attempting install..."

  if [[ "$(uname -s)" == "Darwin" ]]; then
    if have_cmd brew; then
      brew install --quiet openjdk@17 || true
      export JAVA_HOME
      JAVA_HOME="$(brew --prefix openjdk@17 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
      export PATH="$JAVA_HOME/bin:$PATH"
    else
      err "Install Homebrew or JDK 17 manually: https://adoptium.net/"
      exit 1
    fi
  elif have_cmd apt-get; then
    sudo apt-get update -qq
    sudo apt-get install -y openjdk-17-jdk
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f /etc/alternatives/java)")")"
    export JAVA_HOME
  elif have_cmd dnf; then
    sudo dnf install -y java-17-openjdk-devel
    JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
    export JAVA_HOME
  elif have_cmd pacman; then
    sudo pacman -S --needed --noconfirm jdk17-openjdk
    JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
    export JAVA_HOME
  else
    err "No supported package manager. Install JDK $REQUIRED_JAVA_MAJOR and set JAVA_HOME."
    err "See: https://adoptium.net/"
    exit 1
  fi

  major="$(detect_java_major)"
  if [[ "$major" -lt "$REQUIRED_JAVA_MAJOR" ]]; then
    err "Java still not $REQUIRED_JAVA_MAJOR+ after install."
    exit 1
  fi
  info "Java installed (major=$major), JAVA_HOME=$JAVA_HOME"
}

ensure_sdk_root() {
  mkdir -p "$ANDROID_SDK_ROOT"
  export ANDROID_SDK_ROOT
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
  info "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
}

download_cmdline_tools() {
  have_cmd unzip || { err "install unzip (e.g. sudo apt install unzip)"; exit 1; }
  local zip tmp
  tmp="$(mktemp -d)"
  zip="$tmp/cmdline-tools.zip"
  info "Downloading command-line tools..."
  if have_cmd curl; then
    curl -fsSL "$ANDROID_CMDLINE_TOOLS_URL" -o "$zip"
  elif have_cmd wget; then
    wget -q "$ANDROID_CMDLINE_TOOLS_URL" -O "$zip"
  else
    err "Need curl or wget to download Android cmdline-tools."
    exit 1
  fi
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  unzip -q "$zip" -d "$tmp/extract"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  # Google zip contains a single top-level cmdline-tools/ folder
  if [[ -d "$tmp/extract/cmdline-tools" ]]; then
    mv "$tmp/extract/cmdline-tools/"* "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
  else
    mv "$tmp/extract"/*/* "$ANDROID_SDK_ROOT/cmdline-tools/latest/" 2>/dev/null || {
      err "Unexpected cmdline-tools zip layout under $tmp/extract — set ANDROID_CMDLINE_TOOLS_URL to a valid zip."
      exit 1
    }
  fi
  rm -rf "$tmp"
  info "cmdline-tools installed under cmdline-tools/latest/"
}

ensure_cmdline_tools() {
  local sdkm="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  if [[ -x "$sdkm" ]]; then
    info "sdkmanager already present"
    return 0
  fi
  download_cmdline_tools
}

install_sdk_packages() {
  local sdkm="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  [[ -x "$sdkm" ]] || { err "sdkmanager missing"; exit 1; }

  info "Accepting licenses (non-interactive)..."
  yes | "$sdkm" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true

  local bt="build-tools;$ANDROID_BUILD_TOOLS"
  info "Installing platform-tools, emulator, $ANDROID_PLATFORM, $bt ..."
  set +e
  "$sdkm" --sdk_root="$ANDROID_SDK_ROOT" \
    "cmdline-tools;latest" \
    "platform-tools" \
    "emulator" \
    "platforms;$ANDROID_PLATFORM" \
    "$bt"
  local st=$?
  set -e
  if [[ $st -ne 0 ]]; then
    warn "Primary package set failed (build-tools version?). Retrying with 36.0.0..."
    yes | "$sdkm" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true
    "$sdkm" --sdk_root="$ANDROID_SDK_ROOT" \
      "cmdline-tools;latest" \
      "platform-tools" \
      "emulator" \
      "platforms;$ANDROID_PLATFORM" \
      "build-tools;36.0.0"
  fi

  info "Installed packages (summary):"
  "$sdkm" --sdk_root="$ANDROID_SDK_ROOT" --list_installed | head -40 || true
}

write_local_properties() {
  local prop="$ROOT/local.properties"
  local dir_esc="${ANDROID_SDK_ROOT//\\/\\\\}"
  if [[ -f "$prop" ]] && grep -q 'sdk.dir=' "$prop" 2>/dev/null; then
    warn "local.properties already has sdk.dir — leaving as-is (delete file to regenerate)"
    return 0
  fi
  echo "sdk.dir=$dir_esc" >"$prop"
  info "Wrote $prop"
}

kvm_hint() {
  if [[ "$(uname -s)" != "Linux" ]]; then return 0; fi
  if [[ -r /dev/kvm ]]; then
    info "KVM device /dev/kvm readable — emulator should use hardware acceleration."
  else
    warn "No read access to /dev/kvm. Add your user to group kvm, then re-login:"
    echo -e "  ${DIM}sudo usermod -aG kvm \"\$USER\" && newgrp kvm${RST}"
  fi
}

gradle_sanity() {
  if [[ -x "$ROOT/gradlew" ]]; then
    info "Running ./gradlew --version (sanity check)..."
    (cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-}" ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT" ./gradlew --version) || warn "gradlew --version failed — fix JAVA_HOME/SDK and retry"
  fi
}

main() {
  info "PokeClaw dev-setup (project root: $ROOT)"
  ensure_java
  ensure_sdk_root
  ensure_cmdline_tools
  install_sdk_packages
  write_local_properties
  kvm_hint
  gradle_sanity
  echo ""
  info "Done. Next:"
  echo -e "  ${DIM}export ANDROID_SDK_ROOT=\"$ANDROID_SDK_ROOT\"${RST}"
  echo -e "  ${DIM}export ANDROID_HOME=\"\$ANDROID_SDK_ROOT\"${RST}"
  echo -e "  ${DIM}export PATH=\"\$ANDROID_SDK_ROOT/platform-tools:\$ANDROID_SDK_ROOT/emulator:\$PATH\"${RST}"
  echo -e "  ${DIM}make devices create-slim && make devices start poke_slim${RST}"
  echo -e "  ${DIM}make build${RST}"
}

main "$@"
