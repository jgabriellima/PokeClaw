#!/usr/bin/env bash
# Shared adb + multi-device handling (sourced by other scripts).
# Sets: ROOT, SDK, ADB, ADB_OPTS (array)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
ADB="$SDK/platform-tools/adb"

device_count() {
  "$ADB" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { c++ } END { print c + 0 }'
}

require_adb() {
  if [[ ! -x "$ADB" ]]; then
    echo "adb not found at $ADB — run: make setup" >&2
    exit 1
  fi
}

require_one_target() {
  require_adb
  local n
  n="$(device_count)"
  if [[ "$n" -eq 0 ]]; then
    echo "No device. Run: adb devices" >&2
    exit 1
  fi
  if [[ "$n" -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
    echo "Multiple devices — set ANDROID_SERIAL (adb devices -l):" >&2
    "$ADB" devices -l >&2
    exit 1
  fi
}

adb_opts() {
  ADB_OPTS=()
  [[ -n "${ANDROID_SERIAL:-}" ]] && ADB_OPTS+=(-s "$ANDROID_SERIAL")
}
