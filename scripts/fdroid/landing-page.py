#!/usr/bin/env python3
"""Write the GitHub Pages landing page for the Soundcore Actions F-Droid repo.

The page exists so the Pages root is not a 404, and so there is somewhere to put
the repo address together with its signing fingerprint, which is what a client
needs in order to add the repo securely. It also carries the warnings a user has
to read before installing: this replaces Anker's Soundcore install, and doing so
clears its data.

Usage:
    scripts/fdroid/landing-page.py <fdroid-build-dir> <output-html>
"""

from __future__ import annotations

import html
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CONFIG = REPO_ROOT / "fdroid/config.yml"
PROJECT_URL = "https://github.com/colonelpanic8/soundcore-actions"

TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Soundcore Actions F-Droid Repository</title>
<style>
  :root {{ color-scheme: light dark; }}
  body {{
    margin: 0 auto;
    max-width: 44rem;
    padding: 2.5rem 1.25rem 4rem;
    font: 16px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif;
  }}
  h1 {{ display: flex; align-items: center; gap: 0.75rem; font-size: 1.6rem; }}
  h1 img {{ width: 3rem; height: 3rem; border-radius: 0.75rem; }}
  h2 {{ font-size: 1.15rem; margin-top: 2.25rem; }}
  code, pre {{
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.9em;
  }}
  pre {{
    padding: 0.85rem 1rem;
    border-radius: 0.5rem;
    background: rgba(127, 127, 127, 0.14);
    /* The fingerprint is meant to be compared against a client by eye, so it
       has to stay fully visible rather than scroll off the right edge. */
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }}
  .cta {{
    display: inline-block;
    margin: 0.5rem 0 1.5rem;
    padding: 0.7rem 1.2rem;
    border-radius: 0.5rem;
    background: #14303A;
    color: #F6F7F4;
    font-weight: 600;
    text-decoration: none;
  }}
  .warning {{
    margin: 1.5rem 0;
    padding: 0.1rem 1.1rem;
    border-left: 4px solid #F2A65A;
    background: rgba(242, 166, 90, 0.12);
    border-radius: 0 0.5rem 0.5rem 0;
  }}
  footer {{ margin-top: 3rem; font-size: 0.9rem; opacity: 0.75; }}
</style>
</head>
<body>
<h1><img src="fdroid/repo/icons/icon.png" alt="">Soundcore Actions</h1>

<p>
  Soundcore Actions is Anker's Soundcore app, patched so that components it
  already declares — the Anka assistant, the translation screens, and other
  activities, services, and manifest receivers — can be mapped to an action you
  pick: open an app, follow a link, fire an Android intent, or send a broadcast.
  A settings screen pairs each source with its action, and a recent-events view
  shows what fired. Three actions ship mapped to Paseo Live Voice.
</p>

<p>
  This page hosts an F-Droid repository containing the same signed APK attached
  to <a href="{project_url}/releases">the GitHub releases</a>. The APK there is
  the primary download; this repository exists so that a client can notice new
  versions on its own instead of you checking the releases page.
</p>

<a class="cta" href="{add_url}">Add this repository to F-Droid</a>

<p>If that link does not open your client, add the repository manually:</p>
<pre>{repo_url}</pre>

<p>Verify the repository signing fingerprint (SHA-256):</p>
<pre>{fingerprint_display}</pre>

<div class="warning">
  <p>
    <strong>Installing replaces your existing Soundcore install and clears its
    data.</strong> This APK is signed with this project's key rather than
    Anker's, so Android will not upgrade a Play Store copy in place. You have to
    uninstall the official app first, which removes its local data. Installing
    Anker's build again restores the original, and the same uninstall applies in
    reverse.
  </p>
</div>

<h2>What you are actually installing</h2>

<p>
  This is not a build from source. The published APK is Anker's release APK with
  a small launcher added, a patched manifest, and a new signature. It therefore
  carries all of Soundcore's proprietary code, bundled assets, cloud services,
  and analytics along with it. Only the patch, the build tooling, and this
  repository's automation are open source; Soundcore's own source is not
  available and is not distributed here. The F-Droid listing is flagged
  <code>UpstreamNonFree</code>, <code>NonFreeAssets</code>,
  <code>NonFreeDep</code>, <code>NonFreeNet</code>, and <code>Tracking</code>
  for exactly these reasons.
</p>

<p>
  What the patch can reach is bounded by what the earbuds report. The firmware
  exposes a fixed set of events, and the patch works by taking over components
  Soundcore already declares. It does not invent new physical gestures, and it
  does not hook internal callbacks or receivers registered at runtime.
</p>

<p>
  This repository is self-hosted and personal, and is not part of the official
  F-Droid catalog. Not affiliated with, endorsed by, or supported by Anker or
  Oceanwing.
</p>

<p>
  The APK is around 485 MB, because it carries Soundcore's native libraries for
  both ARM architectures plus a second copy of many of them under
  <code>assets/</code>. Only the most recent version is published here; older
  versions stay on the GitHub releases page.
</p>

<footer>
  Source, build instructions, and issues:
  <a href="{project_url}">github.com/colonelpanic8/soundcore-actions</a>
</footer>
</body>
</html>
"""


def read_repo_url() -> str:
    # A dependency-free read of the single value needed; the file is consumed
    # and validated by fdroidserver elsewhere.
    match = re.search(
        r"^repo_url:\s*(\S+)\s*$", CONFIG.read_text(encoding="utf-8"), re.MULTILINE
    )
    if not match:
        raise SystemExit(f"repo_url not found in {CONFIG}")
    return match.group(1)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2

    build_dir = Path(sys.argv[1])
    output = Path(sys.argv[2])

    repo_url = read_repo_url()

    fingerprint_file = build_dir / "fingerprint.txt"
    fingerprint = (
        fingerprint_file.read_text(encoding="utf-8").strip()
        if fingerprint_file.exists()
        else ""
    )

    if fingerprint:
        add_url = f"{repo_url}?fingerprint={fingerprint}"
        # Grouped into byte pairs so it can be compared against a client by eye.
        fingerprint_display = " ".join(
            fingerprint[i : i + 2].upper() for i in range(0, len(fingerprint), 2)
        )
    else:
        add_url = repo_url
        fingerprint_display = "unavailable"

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        TEMPLATE.format(
            add_url=html.escape(add_url, quote=True),
            repo_url=html.escape(repo_url),
            fingerprint_display=html.escape(fingerprint_display),
            project_url=PROJECT_URL,
        ),
        encoding="utf-8",
    )
    print(f"wrote {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
