#!/usr/bin/env bash
# Install the newest debug APK from app/build/... without running Gradle.
# Use after: make build   (or a previous successful assembleDebug)
# If INSTALL_FAILED_UPDATE_INCOMPATIBLE: run make uninstall once, then retry.
set -euo pipefail
# shellcheck source=adb-env.sh
source "$(dirname "${BASH_SOURCE[0]}")/adb-env.sh"
require_one_target
adb_opts

shopt -s nullglob
apks=("$ROOT/app/build/outputs/apk/debug"/*.apk)
shopt -u nullglob

if [[ ${#apks[@]} -eq 0 ]]; then
  echo "No APK in app/build/outputs/apk/debug — run: make build" >&2
  exit 1
fi

# Newest by mtime
APK="$(ls -t "${apks[@]}" | head -1)"
echo "[install-apk] $APK"

if ! "$ADB" "${ADB_OPTS[@]}" install -t -r "$APK"; then
  echo "" >&2
  echo "If you saw INSTALL_FAILED_UPDATE_INCOMPATIBLE (signatures differ):" >&2
  echo "  make uninstall && make install-apk" >&2
  echo "  (or: ANDROID_SERIAL=... make uninstall)" >&2
  exit 1
fi

if [[ "${LAUNCH:-}" == "1" ]]; then
  echo "[install-apk] starting SplashActivity..."
  "$ADB" "${ADB_OPTS[@]}" shell am start -n io.agents.pokeclaw/io.agents.pokeclaw.ui.splash.SplashActivity
fi

echo "[install-apk] done."
