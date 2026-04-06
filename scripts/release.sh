#!/usr/bin/env bash
# Build debug APK and publish a GitHub release (tag v<versionName> from app/build.gradle.kts).
# Requires: gh authenticated; clean git tree; versionName strictly above latest merged semver tag;
#           at least one commit after that tag (something to ship). Optional: GH_REPO, RELEASE_NOTES.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) not found. Install it, then sign in: gh auth login" >&2
  exit 1
fi

if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
  echo "Your working tree still has uncommitted or staged changes." >&2
  echo "Commit everything you want in this release (or stash it), then run: make release" >&2
  exit 1
fi

GIT_REMOTE="${GIT_REMOTE:-origin}"
git fetch "$GIT_REMOTE" --tags 2>/dev/null || true

if [[ -z "${GH_REPO:-}" ]]; then
  url="$(git remote get-url "$GIT_REMOTE" 2>/dev/null || true)"
  if [[ -z "$url" ]]; then
    echo "No git remote named '$GIT_REMOTE'. Set the repo explicitly: GH_REPO=owner/repo" >&2
    exit 1
  fi
  url="${url%.git}"
  url="${url#https://github.com/}"
  url="${url#git@github.com:}"
  GH_REPO="$url"
fi

if [[ -z "$GH_REPO" || "$GH_REPO" == *"*"* ]]; then
  echo "Could not figure out the GitHub repo from your remote. Use: GH_REPO=owner/repo" >&2
  exit 1
fi

version="$(
  grep -E '^\s+versionName\s*=\s*"' "$ROOT/app/build.gradle.kts" \
    | head -1 \
    | sed -E 's/.*versionName\s*=\s*"([^"]+)".*/\1/'
)"
if [[ -z "$version" ]]; then
  echo "Could not read versionName from app/build.gradle.kts — check that file." >&2
  exit 1
fi

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "versionName must look like semver 1.2.3 (you have: $version)." >&2
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
    echo "Nothing new to ship: your HEAD is already the commit tagged ${LAST_TAG}." >&2
    echo "Add a new commit (e.g. bump versionName and versionCode in app/build.gradle.kts), then run make release again." >&2
    exit 1
  fi
  if ! semver_gt "$version" "$last_ver"; then
    echo "Almost there — you just need to bump the version in Gradle." >&2
    echo "Latest tag on this branch is ${LAST_TAG}, but versionName is still \"${version}\"." >&2
    echo "Open app/build.gradle.kts, set a higher version (e.g. after 0.1.0 use 0.1.1), bump versionCode, commit, then run: make release" >&2
    exit 1
  fi
fi

if gh release view "$TAG" --repo "$GH_REPO" &>/dev/null; then
  echo "Release ${TAG} already exists on ${GH_REPO} — skipping so we do not duplicate it." >&2
  echo "For another build, bump versionName, commit, then run make release again." >&2
  exit 1
fi

echo "[release] Building…"
bash "$ROOT/gradlew" assembleDebug

shopt -s nullglob
apks=("$ROOT/app/build/outputs/apk/debug"/*.apk)
shopt -u nullglob
if [[ ${#apks[@]} -eq 0 ]]; then
  echo "Build finished but no APK showed up under app/build/outputs/apk/debug — check the Gradle output." >&2
  exit 1
fi
APK="$(ls -t "${apks[@]}" | head -1)"
echo "[release] APK: $APK"
echo "[release] Creating release ${TAG} on ${GH_REPO}…"

gh release create "$TAG" \
  --repo "$GH_REPO" \
  --title "$TAG" \
  --notes "$NOTES" \
  "$APK"

echo "[release] Done — release published."
