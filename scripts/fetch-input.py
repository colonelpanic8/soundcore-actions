#!/usr/bin/env python3
"""Download and verify the fixed Soundcore APK used by release builds."""

import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
metadata = json.loads((root / "upstream.json").read_text())
output = root / "build/direct-patch/original-merged.apk"
output.parent.mkdir(parents=True, exist_ok=True)


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


if not output.exists() or digest(output) != metadata["sha256"]:
    archive = output.parent / "input.zip"
    with (
        urllib.request.urlopen(metadata["inputArchive"]) as response,
        archive.open("wb") as target,
    ):
        shutil.copyfileobj(response, target)
    if digest(archive) != metadata["archiveSha256"]:
        raise ValueError("Input archive SHA-256 mismatch")
    with (
        zipfile.ZipFile(archive) as bundle,
        bundle.open("soundcore-5.0.21.apk") as source,
        output.open("wb") as target,
    ):
        shutil.copyfileobj(source, target)
    archive.unlink()
if digest(output) != metadata["sha256"]:
    raise ValueError("Upstream APK SHA-256 mismatch")
print(output)
