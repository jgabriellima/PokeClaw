#!/usr/bin/env bash
# Build debug APK and publish a GitHub release (tag v<versionName> from app/build.gradle.kts).
# Requires: gh authenticated; clean git tree; versionName strictly above latest merged semver tag;
#           at least one commit after that tag (something to ship). Optional: GH_REPO, RELEASE_NOTES.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v gh >/dev/null 2>&1; then
  echo "gh (GitHub CLI) not found. Install it and run: gh auth login" >&2
  exit 1
fi

if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
  echo "Working tree is not clean — commit or stash before make release." >&2
  exit 1
fi

GIT_REMOTE="${GIT_REMOTE:-origin}"
git fetch "$GIT_REMOTE" --tags 2>/dev/null || true

if [[ -z "${GH_REPO:-}" ]]; then
  url="$(git remote get-url "$GIT_REMOTE" 2>/dev/null || true)"
  if [[ -z "$url" ]]; then
    echo "No git remote '$GIT_REMOTE' and GH_REPO is unset." >&2
    exit 1
  fi
  url="${url%.git}"
  url="${url#https://github.com/}"
  url="${url#git@github.com:}"
  GH_REPO="$url"
fi

if [[ -z "$GH_REPO" || "$GH_REPO" == *"*"* ]]; then
  echo "Could not parse GitHub repo from remote. Set GH_REPO=owner/name." >&2
  exit 1
fi

version="$(
  grep -E '^\s+versionName\s*=\s*"' "$ROOT/app/build.gradle.kts" \
    | head -1 \
    | sed -E 's/.*versionName\s*=\s*"([^"]+)".*/\1/'
)"
if [[ -z "$version" ]]; then
  echo "Could not read versionName from app/build.gradle.kts" >&2
  exit 1
fi

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "versionName must be semver X.Y.Z (got: $version)" >&2
  exit 1
fi

TAG="v${version}"
NOTES="${RELEASE_NOTES:-Debug build (\`assembleDebug\`). APK attached.}"

# True if $1 is strictly greater than $2 (semver strings X.Y.Z).
semver_gt() {
  [[ "$1" != "$2" ]] || return 1
  local lo
  lo="$(printf '%s\n' "$1" "$2" | sort -V | head -n1)"
  [[ "$lo" != "$1" ]]
}

LAST_TAG="$(
  git tag -l 'v[0-9]*.[0-9]*.[0-9]*' --merged HEAD 2>/dev/null | sort -V | tail -n1 || true
)"

if [[ -n "$LAST_TAG" ]]; then
  last_ver="${LAST_TAG#v}"
  commits_since=$(( $(git rev-list --count "${LAST_TAG}..HEAD" 2>/dev/null) || 0 ))
  if [[ "$commits_since" -eq 0 ]]; then
    echo "Nothing to ship: HEAD is at ${LAST_TAG} (no commits after that tag)." >&2
    echo "Do new work and/or bump versionName in app/build.gradle.kts, commit, then release." >&2
    exit 1
  fi
  if ! semver_gt "$version" "$last_ver"; then
    echo "versionName must be greater than the latest release tag (semver)." >&2
    echo "  Latest tag on this branch: ${LAST_TAG} (${last_ver})" >&2
    echo "  app/build.gradle.kts versionName: ${version}" >&2
    exit 1
  fi
fi

if gh release view "$TAG" --repo "$GH_REPO" &>/dev/null; then
  echo "GitHub release ${TAG} already exists on ${GH_REPO}." >&2
  exit 1
fi

echo "[release] building…"
bash "$ROOT/gradlew" assembleDebug

shopt -s nullglob
apks=("$ROOT/app/build/outputs/apk/debug"/*.apk)
shopt -u nullglob
if [[ ${#apks[@]} -eq 0 ]]; then
  echo "No APK in app/build/outputs/apk/debug after build." >&2
  exit 1
fi
APK="$(ls -t "${apks[@]}" | head -1)"
echo "[release] APK: $APK"
echo "[release] creating GitHub release $TAG on $GH_REPO …"

gh release create "$TAG" \
  --repo "$GH_REPO" \
  --title "$TAG" \
  --notes "$NOTES" \
  "$APK"

echo "[release] done."
