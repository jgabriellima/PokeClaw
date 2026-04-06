#!/usr/bin/env bash
# Gradle installDebug + launch (full cycle).
set -euo pipefail
# shellcheck source=adb-env.sh
source "$(dirname "${BASH_SOURCE[0]}")/adb-env.sh"
require_one_target
adb_opts

echo "[run-on-device] ./gradlew installDebug (${ANDROID_SERIAL:-default device})"
(cd "$ROOT" && ./gradlew installDebug)

echo "[run-on-device] starting SplashActivity..."
"$ADB" "${ADB_OPTS[@]}" shell am start -n io.agents.pokeclaw/io.agents.pokeclaw.ui.splash.SplashActivity
echo "[run-on-device] done."
