# Self-hosted F-Droid repository

Soundcore Actions is published through an F-Droid repository hosted on GitHub
Pages at <https://colonelpanic8.github.io/soundcore-actions/>.

The GitHub release is the primary download and works on its own. This repository
exists so an F-Droid client can track new versions and offer updates without
anyone watching the releases page. It indexes exactly the APK the release
carries — same file, same signature — so the two are interchangeable.

This is a personal repository, not a submission to the official F-Droid catalog.
The published APK is Anker's proprietary Soundcore release with a patch applied,
which is well outside what the catalog accepts, so no submission is planned. The
listing reflects that: flagged `UpstreamNonFree`, `NonFreeAssets`, `NonFreeDep`,
`NonFreeNet`, and `Tracking`, and licensed `Proprietary`.

## Layout

| Path | Purpose |
| --- | --- |
| `fdroid/config.yml` | fdroidserver repo config: URL, name, description, index signing |
| `fdroid/metadata/com.oceanwing.soundcore.yml` | App listing: license, links, anti-features |
| `fdroid/metadata/com.oceanwing.soundcore/en-US/` | Localized title, summary, description |
| `fdroid/config/antiFeatures.yml`, `categories.yml` | Vocabulary the listing is validated against and clients display |
| `fdroid/icon.png` | Icon for both the repo and the app listing (source: `fdroid/icon.svg`) |
| `scripts/fdroid/build-repo.sh` | Collects APKs, runs `fdroid update`, prints the fingerprint |
| `scripts/fdroid/apply-versions.py` | Writes `CurrentVersionCode` / `Builds` from the collected APKs |
| `scripts/fdroid/landing-page.py` | Renders the Pages `index.html` with the add-repo link |
| `.github/workflows/fdroid-repo.yml` | Builds the repo and deploys it to Pages |

The repository is never committed. `build-repo.sh` assembles a scratch
fdroidserver root under `build/fdroid/`, which is gitignored.

## How a release reaches the repository

1. A tag is pushed and the **Release App Artifacts** workflow attaches
   `soundcore-actions-signed.apk` to the GitHub release.
2. **Publish F-Droid Repository** fires on that workflow's completion, downloads
   the newest release's `*-signed.apk`, and rebuilds the index.
3. The whole site — landing page plus `fdroid/repo/` — is deployed to Pages,
   replacing the previous deployment.

Nothing is incremental. Every deploy re-collects the APKs from GitHub releases,
so the published repo is always reproducible from the releases alone.

Only app releases are indexed. This repository also publishes
`inputs-soundcore-<version>` prereleases pinning the upstream APK archive the
patch is built against, and those must never reach the index. `build-repo.sh`
therefore scans a wide window of releases (`FDROID_RELEASE_SCAN_LIMIT`, default
30), drops drafts and prereleases, keeps only tags matching `FDROID_TAG_PATTERN`
(default `^v`), and then takes the newest `FDROID_RELEASE_COUNT` of what
survives. Filtering has to happen before the count is applied — asking GitHub
for exactly one release and then filtering would come back empty any time an
input prerelease is the most recent thing published.

## One-time setup

- **Pages source.** Settings → Pages → Source must be **GitHub Actions**. A
  branch-based source will not work; the site is produced by the workflow.
- **Secrets.** The workflow reads the same signing secrets as the release
  workflow: `ANDROID_SIGNING_KEYSTORE_BASE64`, `ANDROID_SIGNING_KEY_ALIAS`,
  `ANDROID_SIGNING_KEYSTORE_PASSWORD`, `ANDROID_SIGNING_KEY_PASSWORD`.
  `FDROID_`-prefixed equivalents override them if the index ever needs a
  different key.
- **`workflow_run` only fires for workflow files on the repository's default
  branch**, so the first automatic run happens after
  `.github/workflows/fdroid-repo.yml` lands there. If the default branch is ever
  renamed, nothing needs changing here, but the workflow file has to exist on
  the new one.

## The signing key is load-bearing

The index is signed with the same key as the APK, `debug.keystore` in the
project root (PKCS#12, alias `androiddebugkey`). Its certificate fingerprint is
what clients pin the repository to, and what Android checks before allowing an
in-place app upgrade:

```
6286a277fe2f2b0d8b0d5121bed231d150f1baffa6bfa4fe763adc95c6fc0640
```

Losing or rotating that key breaks both halves at once. Every user has to remove
and re-add the repository under the new fingerprint, *and* uninstall and
reinstall the app, losing its data. Keep the keystore backed up. It is
gitignored and must stay that way.

## Running it by hand

Against the real GitHub releases, from a checkout with `gh` authenticated:

```sh
FDROID_KEYSTORE_FILE=debug.keystore \
FDROID_KEY_ALIAS=androiddebugkey \
FDROID_KEYSTORE_PASSWORD=android \
FDROID_KEY_PASSWORD=android \
  ./scripts/fdroid/build-repo.sh
```

Against a locally built APK, which needs no releases and no `gh`:

```sh
FDROID_LOCAL_APKS=build/release/soundcore-actions-signed.apk \
FDROID_KEYSTORE_FILE=debug.keystore \
FDROID_KEY_ALIAS=androiddebugkey \
FDROID_KEYSTORE_PASSWORD=android \
FDROID_KEY_PASSWORD=android \
  ./scripts/fdroid/build-repo.sh

./scripts/fdroid/landing-page.py build/fdroid build/site/index.html
```

On NixOS, the tools are not installed globally:

```sh
nix shell --impure --expr 'with import <nixpkgs> {};
  [ (python3.withPackages (ps: [ps.androguard])) fdroidserver apksigner jdk gh ]'
```

To redeploy without cutting a release, run the **Publish F-Droid Repository**
workflow manually from the Actions tab.

## Size budget

GitHub Pages refuses to publish a site larger than 1 GB, and the merged
Soundcore APK is around 485 MB. The repository therefore indexes only the single
most recent release (`FDROID_RELEASE_COUNT`, default `1`), and `build-repo.sh`
fails if `repo/` exceeds `FDROID_MAX_REPO_MB` (default 900) rather than
producing a site Pages will reject.

Two versions would come to roughly 926 MiB. That trips the 900 MiB guard, and
while it may still squeak under the Pages limit, it leaves no room for the APK
to grow between releases. Retention stays at 1; older versions remain
downloadable from the releases page.

Pages also has a soft 100 GB/month bandwidth limit, which a 485 MB APK reaches
in roughly 200 downloads.

### Where the size comes from

Nearly all of it is Soundcore's, not the patch's. Measured on the 0.1.0 APK:

| Component | Compressed |
| --- | --- |
| `lib/armeabi-v7a` | 104 MB |
| `lib/arm64-v8a` | 98 MB |
| `assets/lib/` (second copy of many of the above, plus x86 and x86_64) | 80 MB |
| `res/` and `resources.arsc` | 75 MB |
| `assets/ijiami.{dat,ajm}` (packer blobs holding the real bytecode) | 36 MB |
| other `assets/` bundles (`liberty`, `resource.zip`, `flutter_assets`, …) | 81 MB |

54 MB of the file is byte-identical duplicate payloads, mostly `lib/` against
`assets/lib/`.

The original Soundcore APK set already contains both ARM architectures, so this
is not a matter of which splits get merged — reducing it would mean deleting ABI
entries from the merged APK afterwards. Dropping the `x86` and `x86_64` entries
under `assets/lib/` could save ~29 MB on this ARM-only build, subject to
runtime verification; going further
and removing `armeabi-v7a` would cost 32-bit device support. **Both
architectures are retained for this release.** Any of this is a change to the
patch pipeline, not to this repository.

## Changing the listing

Edit `fdroid/metadata/`. The two files that fdroidserver merges are the app YAML
(structured fields and anti-features) and the `en-US/` text files (title,
summary, description). Anti-feature descriptions are localized maps and appear
per-version in `index-v2.json` and app-level in `index-v1.json`.

A new anti-feature or category name has to be declared in `fdroid/config/`
before it can be used, otherwise `fdroid lint` rejects it and clients show a
bare identifier with no display name.

`fdroid lint`, run inside `build/fdroid`, is clean except for one warning:
`Unexpected license tag "Proprietary"`. That is expected — lint assumes a
free-software catalog, and `Proprietary` is the honest, readable value to show a
user for a patched Anker binary.

`CurrentVersion`, `CurrentVersionCode`, and `Builds` are *not* edited by hand.
`apply-versions.py` appends them to the scratch copy of the YAML from the APKs
that were actually collected, and rejects any APK whose package is not
`com.oceanwing.soundcore`.

Screenshots can be added at
`fdroid/metadata/com.oceanwing.soundcore/en-US/phoneScreenshots/*.png`; they are
picked up automatically.

## Verifying a build

`fdroid update` output ends with the certificate SHA-256 it signed the index
with; it must match the fingerprint above. After a deploy, check:

- `https://colonelpanic8.github.io/soundcore-actions/` renders and shows the
  fingerprint.
- `.../fdroid/repo/index-v2.json` lists `com.oceanwing.soundcore` with the
  expected `versionCode`.
- `.../fdroid/repo/entry.jar` exists — clients fetch this first.
- Adding the repo in an F-Droid client shows the app without a signature
  warning.
