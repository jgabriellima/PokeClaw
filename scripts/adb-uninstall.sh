#!/usr/bin/env bash
# Remove installed app from device (needed when signatures differ vs local debug build).
set -euo pipefail
# shellcheck source=adb-env.sh
source "$(dirname "${BASH_SOURCE[0]}")/adb-env.sh"
require_one_target
adb_opts

echo "[uninstall] io.agents.pokeclaw (${ANDROID_SERIAL:-default})"
"$ADB" "${ADB_OPTS[@]}" uninstall io.agents.pokeclaw || {
  echo "[uninstall] Package may already be absent (OK)." >&2
  exit 0
}
echo "[uninstall] done."
