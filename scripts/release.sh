#!/usr/bin/env bash
# Build debug APK and publish a GitHub release. Automatically bumps patch version + versionCode
# in app/build.gradle.kts, commits, pushes, builds, and runs gh release create.
# Requires: gh auth; clean tree; commits after latest merged vX.Y.Z tag (when tags exist).
# Optional: GH_REPO, RELEASE_NOTES, GIT_REMOTE. RELEASE_SKIP_PUSH=1 skips git push (gh will fail if the commit is not on the remote).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
GRADLE_FILE="$ROOT/app/build.gradle.kts"

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

read_gradle_version() {
  grep -E '^\s+versionName\s*=\s*"' "$GRADLE_FILE" | head -1 | sed -E 's/.*versionName\s*=\s*"([^"]+)".*/\1/'
}

read_gradle_version_code() {
  grep -E '^\s+versionCode\s*=' "$GRADLE_FILE" | head -1 | sed -E 's/.*versionCode = ([0-9]+).*/\1/'
}

gradle_version="$(read_gradle_version)"
version_code="$(read_gradle_version_code)"
if [[ -z "$gradle_version" || -z "$version_code" ]]; then
  echo "Could not read versionName / versionCode from app/build.gradle.kts." >&2
  exit 1
fi

if [[ ! "$gradle_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "versionName must look like semver 1.2.3 (you have: $gradle_version)." >&2
  exit 1
fi

NOTES="${RELEASE_NOTES:-Debug build (\`assembleDebug\`). APK attached.}"

# True if $1 is strictly greater than $2 (semver strings X.Y.Z).
semver_gt() {
  [[ "$1" != "$2" ]] || return 1
  local lo
  lo="$(printf '%s\n' "$1" "$2" | sort -V | head -n1)"
  [[ "$lo" != "$1" ]]
}

bump_patch() {
  local v="$1" a b c
  IFS=. read -r a b c <<< "$v"
  a="${a:-0}"
  b="${b:-0}"
  c="${c:-0}"
  printf '%s.%s.%d\n' "$a" "$b" "$((c + 1))"
}

apply_versions() {
  local code="$1" name="$2"
  sed -i \
    -e 's/\(versionCode = \)[0-9]\+/\1'"$code"'/' \
    -e 's/\(versionName = "\)[^"]*\("\)/\1'"$name"'\2/' \
    "$GRADLE_FILE"
}

LAST_TAG="$(
  git tag -l 'v[0-9]*.[0-9]*.[0-9]*' --merged HEAD 2>/dev/null | sort -V | tail -n1 || true
)"

if [[ -n "$LAST_TAG" ]]; then
  last_ver="${LAST_TAG#v}"
  commits_since=$(( $(git rev-list --count "${LAST_TAG}..HEAD" 2>/dev/null) || 0 ))
  if [[ "$commits_since" -eq 0 ]]; then
    echo "Nothing new to ship: your HEAD is already the commit tagged ${LAST_TAG}." >&2
    echo "Add commits with your changes, then run make release again (version bump is automatic)." >&2
    exit 1
  fi
  candidate="$(bump_patch "$last_ver")"
  if semver_gt "$gradle_version" "$candidate"; then
    next_ver="$gradle_version"
  else
    next_ver="$candidate"
  fi
else
  next_ver="$gradle_version"
fi

# Avoid clashing with an existing GitHub release (e.g. tags not fetched yet).
while gh release view "v${next_ver}" --repo "$GH_REPO" &>/dev/null; do
  echo "[release] v${next_ver} already on GitHub — bumping patch…" >&2
  next_ver="$(bump_patch "$next_ver")"
done

if [[ -n "$LAST_TAG" ]] && ! semver_gt "$next_ver" "$last_ver"; then
  echo "Internal error: next version ${next_ver} is not after last tag ${last_ver}." >&2
  exit 1
fi

new_code=$((version_code + 1))
TAG="v${next_ver}"

echo "[release] Setting versionName=${next_ver}, versionCode=${new_code}"
apply_versions "$new_code" "$next_ver"

echo "[release] Committing app/build.gradle.kts…"
git add app/build.gradle.kts
git commit -m "chore(release): ${TAG}"

SHA="$(git rev-parse HEAD)"
if [[ -z "${RELEASE_SKIP_PUSH:-}" ]]; then
  echo "[release] Pushing to ${GIT_REMOTE} (required so GitHub can point the release at this commit)…"
  git push "$GIT_REMOTE" HEAD
else
  echo "[release] RELEASE_SKIP_PUSH is set — not pushing. If this commit is not on GitHub, gh release will fail." >&2
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
echo "[release] Creating release ${TAG} on ${GH_REPO} (target ${SHA})…"

gh release create "$TAG" \
  --repo "$GH_REPO" \
  --target "$SHA" \
  --title "$TAG" \
  --notes "$NOTES" \
  "$APK"

echo "[release] Done — release published. Run: git pull ${GIT_REMOTE} --tags  (to sync the new tag locally)"
