#!/usr/bin/env bash
# PokeClaw — start headless AVD (if needed), wait for boot, run instrumented tests.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-$HOME/Android/Sdk}}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT

ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"
AVD_NAME="${POKECLAW_SLIM_AVD:-poke_slim}"
# Cold-ish boot without persisting snapshot on exit (good for CI); override via POKECLAW_EMU_EXTRA.
DEFAULT_EMU_FLAGS="-no-window -no-snapshot-save -gpu swiftshader_indirect -no-audio"
BOOT_TIMEOUT_SEC="${POKECLAW_EMU_BOOT_TIMEOUT:-300}"
STARTED_EMULATOR=0

DIM='\033[2m'; GRN='\033[0;32m'; YEL='\033[0;33m'; RST='\033[0m'
info() { echo -e "${GRN}[emulator-test]${RST} $*"; }
warn() { echo -e "${YEL}[emulator-test]${RST} $*"; }

have() { [[ -x "$1" ]]; }

emulator_serial() {
  "$ADB" devices 2>/dev/null | awk '$2 == "device" && $1 ~ /^emulator-/ { print $1; exit }'
}

kvm_hint() {
  if [[ "$(uname -s)" == "Linux" ]] && [[ ! -r /dev/kvm ]]; then
    warn "/dev/kvm not readable — add user to group kvm (sudo usermod -aG kvm \"\$USER\") or expect very slow / failed boot."
  fi
}

start_emulator_bg() {
  # shellcheck disable=2086
  nohup "$EMULATOR" -avd "$AVD_NAME" ${DEFAULT_EMU_FLAGS} ${POKECLAW_EMU_EXTRA:-} \
    >>"${TMPDIR:-/tmp}/pokeclaw-emulator.log" 2>&1 &
  info "Started emulator PID $! (log: ${TMPDIR:-/tmp}/pokeclaw-emulator.log)"
}

wait_for_emulator_serial() {
  local start=$SECONDS
  local serial=""
  while (( SECONDS - start < BOOT_TIMEOUT_SEC )); do
    serial="$(emulator_serial || true)"
    if [[ -n "$serial" ]]; then
      echo "$serial"
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_boot_completed() {
  local serial="$1"
  local start=$SECONDS
  while (( SECONDS - start < BOOT_TIMEOUT_SEC )); do
    local v
    v="$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$v" == "1" ]]; then
      return 0
    fi
    sleep 3
  done
  return 1
}

cleanup() {
  local code=$?
  if [[ "$STARTED_EMULATOR" == 1 ]] && [[ "${POKECLAW_EMU_AUTOKILL:-}" == "1" ]]; then
    info "POKECLAW_EMU_AUTOKILL=1 — stopping emulator..."
    "$ADB" emu kill 2>/dev/null || true
  fi
  exit "$code"
}
trap cleanup EXIT

main() {
  have "$ADB" || { echo "Missing adb. Set ANDROID_HOME / run make setup" >&2; exit 1; }
  have "$EMULATOR" || { echo "Missing emulator binary. Run make setup" >&2; exit 1; }

  kvm_hint

  local serial
  serial="$(emulator_serial || true)"
  if [[ -n "$serial" ]]; then
    info "Using already-running emulator: $serial"
  else
    info "No emulator online — starting AVD \"$AVD_NAME\"..."
    start_emulator_bg
    STARTED_EMULATOR=1
    info "Waiting for emulator to appear in adb (timeout ${BOOT_TIMEOUT_SEC}s)..."
    if ! serial="$(wait_for_emulator_serial)"; then
      echo "Timed out waiting for emulator. See log: ${TMPDIR:-/tmp}/pokeclaw-emulator.log" >&2
      exit 1
    fi
    info "Emulator serial: $serial"
  fi

  export ANDROID_SERIAL="$serial"
  info "Waiting for boot_completed on $serial..."
  if ! wait_for_boot_completed "$serial"; then
    echo "Timed out waiting for sys.boot_completed=1" >&2
    exit 1
  fi

  info "Running :app:connectedDebugAndroidTest (ANDROID_SERIAL=$serial)..."
  (cd "$ROOT" && ./gradlew :app:connectedDebugAndroidTest "$@")

  info "Done."
  if [[ "$STARTED_EMULATOR" == 1 ]] && [[ "${POKECLAW_EMU_AUTOKILL:-}" != "1" ]]; then
    echo -e "${DIM}Emulator still running. Stop with: make devices kill${RST}"
  fi
}

main "$@"
