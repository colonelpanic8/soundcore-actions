#!/usr/bin/env bash
#
# Assemble the self-hosted Soundcore Actions F-Droid repository.
#
# Collects the signed release APK from recent GitHub releases, lays out the
# fdroidserver directory structure using fdroid/config.yml and fdroid/metadata,
# then runs `fdroid update` to produce a signed index. The resulting directory
# is what gets deployed to GitHub Pages.
#
# Environment:
#   FDROID_OUT_DIR         Output directory (default: build/fdroid)
#   FDROID_RELEASE_COUNT   How many recent releases to index (default: 1). The
#                          APK is a repackaged Soundcore build of roughly 485 MB
#                          and two versions exceed the default 900 MiB budget.
#   FDROID_TAG_PATTERN     Only index releases whose tag matches this extended
#                          regex (default: ^v). Keeps the upstream-input
#                          prereleases out of the index.
#   FDROID_RELEASE_SCAN_LIMIT
#                          How many releases to examine before filtering
#                          (default: 30).
#   FDROID_LOCAL_APKS      Whitespace-separated APK paths to index instead of
#                          downloading from GitHub. For testing the pipeline
#                          before a release exists; skips `gh` entirely.
#   FDROID_MAX_REPO_MB     Fail if repo/ exceeds this (default: 900), rather
#                          than deploying a site GitHub Pages will reject.
#   FDROID_KEYSTORE_BASE64 Base64 PKCS#12 keystore used to sign the index
#   FDROID_KEYSTORE_FILE   Path to that keystore, as an alternative to the above
#   FDROID_KEY_ALIAS
#   FDROID_KEYSTORE_PASSWORD
#   FDROID_KEY_PASSWORD
#   GH_TOKEN               Token for `gh release` access
#
# The ANDROID_SIGNING_* variables used by the release workflow are accepted as
# fallbacks, since the index is signed with the same keystore as the APK.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

out_dir="${FDROID_OUT_DIR:-build/fdroid}"
release_count="${FDROID_RELEASE_COUNT:-1}"
max_repo_mb="${FDROID_MAX_REPO_MB:-900}"
scan_limit="${FDROID_RELEASE_SCAN_LIMIT:-30}"
tag_pattern="${FDROID_TAG_PATTERN:-^v}"
app_id="com.oceanwing.soundcore"

# EXIT traps replace one another, so everything transient is torn down from one
# handler. Both paths are set later; the trap is registered up front so an early
# failure still cleans up.
download_dir=""
keystore=""
cleanup() {
  [[ -n "$download_dir" ]] && rm -rf "$download_dir"
  [[ -n "$keystore" ]] && rm -f "$keystore"
  return 0
}
trap cleanup EXIT

# Fall back to the release-signing credentials; the index is signed with the
# same key so that installs from this repo and from GitHub releases are
# interchangeable.
: "${FDROID_KEYSTORE_BASE64:=${ANDROID_SIGNING_KEYSTORE_BASE64:-}}"
: "${FDROID_KEYSTORE_FILE:=${ANDROID_SIGNING_KEYSTORE_FILE:-}}"
: "${FDROID_KEY_ALIAS:=${ANDROID_SIGNING_KEY_ALIAS:-}}"
: "${FDROID_KEYSTORE_PASSWORD:=${ANDROID_SIGNING_KEYSTORE_PASSWORD:-}}"
: "${FDROID_KEY_PASSWORD:=${ANDROID_SIGNING_KEY_PASSWORD:-}}"
export FDROID_KEY_ALIAS FDROID_KEYSTORE_PASSWORD FDROID_KEY_PASSWORD

if [[ -z "$FDROID_KEYSTORE_BASE64" && -z "$FDROID_KEYSTORE_FILE" ]]; then
  echo "A signing keystore is required: set FDROID_KEYSTORE_BASE64 or FDROID_KEYSTORE_FILE" >&2
  exit 1
fi

for var in FDROID_KEY_ALIAS FDROID_KEYSTORE_PASSWORD FDROID_KEY_PASSWORD; do
  if [[ -z "${!var}" ]]; then
    echo "Signing the repo index requires $var" >&2
    exit 1
  fi
done

required_tools=(fdroid python3)
[[ -n "${FDROID_LOCAL_APKS:-}" ]] || required_tools+=(gh)
for tool in "${required_tools[@]}"; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Required tool not found on PATH: $tool" >&2
    exit 1
  fi
done

# fdroidserver signs the v2 index with apksigner, which it locates through
# ANDROID_HOME. Failing here beats failing after 485 MB has been downloaded.
if ! command -v apksigner >/dev/null 2>&1; then
  shopt -s nullglob
  sdk_apksigners=("${ANDROID_HOME:-/nonexistent}"/build-tools/*/apksigner)
  shopt -u nullglob

  if [[ "${#sdk_apksigners[@]}" -eq 0 ]]; then
    echo "apksigner not found: put it on PATH or set ANDROID_HOME to an SDK with build-tools" >&2
    exit 1
  fi
fi

echo "==> Preparing $out_dir"
rm -rf "$out_dir"
mkdir -p "$out_dir/repo"

cp fdroid/config.yml "$out_dir/config.yml"
# fdroidserver warns about a world-readable config.yml, since it is the file
# that would hold credentials if they were not coming from the environment.
chmod 600 "$out_dir/config.yml"
# fdroid/metadata already uses fdroidserver's own layout: <appid>.yml alongside
# a <appid>/<locale>/ directory of listing text and graphics.
cp -r fdroid/metadata "$out_dir/metadata"
# Declares the anti-feature and category vocabulary; fdroidserver validates
# against it and clients read the display names from it.
cp -r fdroid/config "$out_dir/config"

# One icon serves both roles, so it lives at fdroid/icon.png and is placed
# where fdroidserver looks for each. `repo_icon` is resolved relative to the
# fdroid root; the localized copy overrides the icon fdroid update would
# otherwise extract from the APK, which is Anker's, and would make a patched
# build indistinguishable from the official one in a client.
cp fdroid/icon.png "$out_dir/icon.png"
cp fdroid/icon.png "$out_dir/metadata/$app_id/en-US/icon.png"

apk_count=0

collect_apk() {
  local apk="$1" label="$2"
  local dest="$out_dir/repo/soundcore-actions-${label}.apk"
  cp "$apk" "$dest"
  echo "  $label: $(basename "$dest") ($(du -h "$dest" | cut -f1))"
  apk_count=$((apk_count + 1))
}

if [[ -n "${FDROID_LOCAL_APKS:-}" ]]; then
  echo "==> Indexing local APKs (FDROID_LOCAL_APKS)"
  # Unquoted so whitespace separates entries and globs expand.
  # shellcheck disable=SC2086
  mapfile -t local_apks < <(printf '%s\n' $FDROID_LOCAL_APKS)
  for apk in "${local_apks[@]}"; do
    if [[ ! -f "$apk" ]]; then
      echo "Local APK not found: $apk" >&2
      exit 1
    fi
    collect_apk "$apk" "local$apk_count"
  done
else
  echo "==> Collecting signed APKs from the $release_count most recent app release(s)"
  # The scan window has to be wider than $release_count: this repo also
  # publishes `inputs-soundcore-<version>` prereleases pinning the upstream APK
  # archive, and those sort ahead of app releases. Asking gh for exactly
  # $release_count entries and then filtering would come back empty whenever a
  # prerelease is newest. Over-fetch, filter, then take the newest N.
  mapfile -t tags < <(
    gh release list --limit "$scan_limit" --json tagName,isDraft,isPrerelease \
      --jq '.[] | select(.isDraft == false and .isPrerelease == false) | .tagName' \
      | grep -E "$tag_pattern" \
      | head -n "$release_count"
  )

  if [[ "${#tags[@]}" -eq 0 ]]; then
    echo "No published release matching $tag_pattern was found in the last $scan_limit releases" >&2
    exit 1
  fi

  download_dir="$(mktemp -d)"

  for tag in "${tags[@]}"; do
    tag_dir="$download_dir/$tag"
    mkdir -p "$tag_dir"

    # A release that predates the APK artifact is expected to miss here.
    if ! gh release download "$tag" --pattern '*-signed.apk' --dir "$tag_dir" 2>/dev/null; then
      echo "  $tag: no signed APK asset, skipping"
      continue
    fi

    shopt -s nullglob
    apks=("$tag_dir"/*-signed.apk)
    shopt -u nullglob

    for apk in "${apks[@]}"; do
      # Release assets share a filename across tags, so namespace by tag to keep
      # every collected version distinct in the index.
      collect_apk "$apk" "$tag"
    done
  done
fi

if [[ "$apk_count" -eq 0 ]]; then
  echo "No signed APKs were found to index" >&2
  exit 1
fi

echo "==> Recording collected versions in the app metadata"
python3 scripts/fdroid/apply-versions.py "$out_dir" "$app_id"

echo "==> Installing index signing keystore"
keystore="$out_dir/keystore.p12"
if [[ -n "$FDROID_KEYSTORE_FILE" ]]; then
  cp "$FDROID_KEYSTORE_FILE" "$keystore"
else
  printf '%s' "$FDROID_KEYSTORE_BASE64" | base64 -d > "$keystore"
fi
chmod 600 "$keystore"

echo "==> Running fdroid update ($apk_count APK(s))"
(
  cd "$out_dir"
  fdroid update --pretty --verbose
)

# `fdroid update` leaves a build report in repo/status/ that records absolute
# toolchain paths from whatever machine ran it. No client reads it, so it does
# not belong on a public site.
rm -rf "$out_dir/repo/status"

# Clients pin a repo by the SHA-256 of its signing certificate, so the URL to
# hand out is repo_url?fingerprint=<this>. Best effort: keytool ships with the
# JDK that fdroidserver already needs, but is not worth failing the build over.
# The password goes through the environment, not argv, which any other process
# on the machine could read out of /proc.
fingerprint=""
if command -v keytool >/dev/null 2>&1; then
  fingerprint="$(
    keytool -list -v \
      -keystore "$keystore" \
      -storetype PKCS12 \
      -alias "$FDROID_KEY_ALIAS" \
      -storepass:env FDROID_KEYSTORE_PASSWORD 2>/dev/null \
      | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' \
      | head -1 \
      | tr -d ':' \
      | tr '[:upper:]' '[:lower:]'
  )"
fi

# The keystore must not reach the deployed site. cleanup() would also catch this
# on exit; removing it here means it is gone before anything else runs.
rm -f "$keystore"

repo_mb="$(du -sm "$out_dir/repo" | cut -f1)"
if [[ "$repo_mb" -gt "$max_repo_mb" ]]; then
  echo >&2
  echo "repo/ is ${repo_mb} MB, over the ${max_repo_mb} MB limit." >&2
  echo "GitHub Pages rejects published sites larger than 1 GB. Lower" >&2
  echo "FDROID_RELEASE_COUNT, or raise FDROID_MAX_REPO_MB if you know better." >&2
  exit 1
fi

echo
echo "F-Droid repository built in $out_dir"
echo "  APKs indexed: $apk_count"
echo "  repo/ size:   ${repo_mb} MB (limit ${max_repo_mb} MB)"
if [[ -n "$fingerprint" ]]; then
  echo "  Fingerprint:  $fingerprint"
  printf '%s\n' "$fingerprint" > "$out_dir/fingerprint.txt"
fi
