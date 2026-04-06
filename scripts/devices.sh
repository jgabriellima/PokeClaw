#!/usr/bin/env bash
# PokeClaw — list/start emulators, slim AVD, physical USB, cloud farm pointers.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-$HOME/Android/Sdk}}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"
SDKM="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDM="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"

SLIM_AVD_NAME="${POKECLAW_SLIM_AVD:-poke_slim}"
# google_apis is heavier but more compatible than "default" on some channels
SLIM_IMAGE="${POKECLAW_SLIM_IMAGE:-system-images;android-34;google_apis;x86_64}"
SLIM_DEVICE_PROFILE="${POKECLAW_SLIM_DEVICE:-pixel_2}"

DIM='\033[2m'; GRN='\033[0;32m'; YEL='\033[0;33m'; RST='\033[0m'
info() { echo -e "${GRN}[devices]${RST} $*"; }
warn() { echo -e "${YEL}[devices]${RST} $*"; }

have() { [[ -x "$1" ]]; }

cmd_help() {
  cat <<'EOF'
Usage: make devices <command> [args]

Commands:
  list, ls          Show `adb devices` and `emulator -list-avds`
  start <avd>       Launch AVD (add POKECLAW_EMU_EXTRA for more flags)
  create-slim       Install API 34 x86_64 Google APIs image + create AVD "poke_slim" (override via env)
  kill              adb emu kill (stops emulator attached to adb)
  wait              Wait until at least one device (usb or emulator) is online
  install-debug     ./gradlew installDebug (needs one device)
  kvm-check         Linux: /dev/kvm and cpu virtualization flags
  usb, physical     Tips for USB debugging + udev
  farms, cloud      Links and when to use remote device farms
  help              This text

Also: make emulator-test — headless AVD + :app:connectedDebugAndroidTest (scripts/emulator-test.sh)

Environment:
  ANDROID_SDK_ROOT / ANDROID_HOME
  POKECLAW_SLIM_AVD, POKECLAW_SLIM_IMAGE, POKECLAW_SLIM_DEVICE
  POKECLAW_EMU_EXTRA  extra args passed to `emulator` (e.g. -no-snapshot -gpu swiftshader_indirect)

Slim / fast local emulator (research summary):
  - Use x86_64 or arm64-v8a images matching the host to avoid ARM-on-x86 translation.
  - Prefer API 33–34 for smaller system images vs API 35/36; this app minSdk=28.
  - Small screen profile (pixel_2) + 2–3 GB RAM reduces host load.
  - Linux: KVM (/dev/kvm) is the largest win vs pure software virtualization.
  - CI / no GPU: -gpu swiftshader_indirect or off; disable unnecessary Google Play auto-update inside guest.
  - Alternatives: Genymotion Desktop, Waydroid (Linux), physical device (often fastest for real sensors).

Examples:
  make devices create-slim
  make devices start poke_slim
  POKECLAW_EMU_EXTRA="-memory 2048 -cores 2" make devices start poke_slim
EOF
}

cmd_list() {
  echo "=== adb devices ==="
  if have "$ADB"; then
    "$ADB" devices -l || true
  else
    warn "adb not found at $ADB — run make setup"
  fi
  echo ""
  echo "=== AVDs (emulator -list-avds) ==="
  if have "$EMULATOR"; then
    "$EMULATOR" -list-avds || true
  else
    warn "emulator binary not found — run make setup"
  fi
}

cmd_start() {
  local avd="${1:-}"
  if [[ -z "$avd" ]]; then
    err "usage: make devices start <avd_name>"; exit 1
  fi
  have "$EMULATOR" || { echo "Missing emulator. Run: make setup"; exit 1; }
  # shellcheck disable=2086
  exec "$EMULATOR" -avd "$avd" ${POKECLAW_EMU_EXTRA:-}
}

cmd_create_slim() {
  have "$SDKM" || { echo "sdkmanager missing. Run: make setup"; exit 1; }
  have "$AVDM" || { echo "avdmanager missing. Run: make setup"; exit 1; }

  info "Installing system image: $SLIM_IMAGE"
  yes | "$SDKM" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null 2>&1 || true
  "$SDKM" --sdk_root="$ANDROID_SDK_ROOT" "$SLIM_IMAGE"

  info "Creating AVD: $SLIM_AVD_NAME (device profile: $SLIM_DEVICE_PROFILE)"
  echo no | "$AVDM" create avd \
    --force \
    --name "$SLIM_AVD_NAME" \
    --package "$SLIM_IMAGE" \
    --device "$SLIM_DEVICE_PROFILE" || true

  info "Created. Start with: make devices start $SLIM_AVD_NAME"
  info "Optional faster cold boot after first run: enable Quick Boot snapshots in AVD settings."
}

cmd_kill() {
  have "$ADB" || exit 1
  "$ADB" emu kill 2>/dev/null || warn "No emulator responded to emu kill"
}

cmd_wait() {
  have "$ADB" || exit 1
  info "Waiting for device..."
  "$ADB" wait-for-device
  info "Device online."
}

cmd_install_debug() {
  have "$ADB" || exit 1
  (cd "$ROOT" && ./gradlew installDebug)
}

cmd_kvm_check() {
  if [[ "$(uname -s)" != "Linux" ]]; then
    echo "kvm-check is Linux-specific."
    exit 0
  fi
  echo -n "/dev/kvm: "
  if [[ -r /dev/kvm ]]; then echo "readable OK"; else echo "missing or no permission (add user to kvm group)"; fi
  echo "CPU flags:"
  grep -E 'vmx|svm' /proc/cpuinfo | head -1 || echo "  (no vmx/svm in first line — check BIOS virtualization)"
}

cmd_usb() {
  cat <<EOF
Physical device (USB / TCP/IP)

1) Phone: enable Developer options → USB debugging.
2) Plug USB; accept RSA fingerprint on device.
3) ${DIM}$ADB devices${RST}

Wireless (same LAN, Android 11+ often supports pairing in Developer options):
  ${DIM}$ADB pair IP:PORT${RST}   # one-time
  ${DIM}$ADB connect IP:PORT${RST}

Linux udev (stable permissions), example rule file /etc/udev/rules.d/51-android.rules :
  ${DIM}SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", MODE=\"0666\", GROUP=\"plugdev\"${RST}
  (vendor id varies — use \`lsusb\`.) Then: sudo udevadm control --reload-rules
EOF
}

cmd_farms() {
  cat <<'EOF'
Cloud / farm options (deep-cut summary)

When to use:
  - Matrix of OEM skins / API levels you do not maintain locally
  - Pre-release soak tests, flaky repro on specific GPUs
  - CI without maintaining KVM + emulator snapshots

Common services (URLs stable; always verify current pricing / auth):
  - Firebase Test Lab — https://firebase.google.com/docs/test-lab
  - AWS Device Farm — https://aws.amazon.com/device-farm/
  - BrowserStack App Live / App Automate — https://www.browserstack.com/
  - Sauce Labs Real Device Cloud — https://saucelabs.com/
  - Genymotion Cloud / SaaS devices — https://www.genymotion.com/
  - Bitrise / GitHub Actions with Android emulator action (KVM runners)

For this repo, typical CI flow: assembleDebug + connectedDebugAndroidTest on a KVM GitHub runner,
or upload APK/AAB to Test Lab instrumented tests.

Physical device is usually the best trade-off for PokeClaw (accessibility, overlays, real notifications).
EOF
}

main() {
  local sub="${1:-list}"
  shift || true
  case "$sub" in
    help|-h|--help) cmd_help ;;
    list|ls) cmd_list ;;
    start) cmd_start "${1:-}" ;;
    create-slim|slim) cmd_create_slim ;;
    kill) cmd_kill ;;
    wait|wait-for-device) cmd_wait ;;
    install-debug) cmd_install_debug ;;
    kvm-check) cmd_kvm_check ;;
    usb|physical) cmd_usb ;;
    farms|cloud) cmd_farms ;;
    *)
      err "Unknown command: $sub"
      cmd_help
      exit 1
      ;;
  esac
}

main "$@"
