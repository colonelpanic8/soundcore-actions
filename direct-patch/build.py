#!/usr/bin/env python3
"""Build a single, signed Soundcore Actions APK from the pinned upstream APK."""

import argparse
import copy
import hashlib
import json
import os
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path

EDITOR_URL = "https://github.com/REAndroid/APKEditor/releases/download/V1.4.9/APKEditor-1.4.9.jar"
EDITOR_SHA256 = "a9cd40df818845456be6d696de6110c89edf4b0a0580cb83438ed6b25a366e67"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def repack(source, output, manifest, dex):
    with zipfile.ZipFile(source) as old, zipfile.ZipFile(output, "w") as new:
        if "classes2.dex" in old.namelist():
            raise ValueError(
                "Upstream already has classes2.dex; refusing to overwrite code"
            )
        for entry in old.infolist():
            if entry.filename == "stamp-cert-sha256" or (
                entry.filename.startswith("META-INF/")
                and entry.filename.endswith((".RSA", ".SF", ".MF", ".DSA", ".EC"))
            ):
                continue
            data = (
                manifest if entry.filename == "AndroidManifest.xml" else old.read(entry)
            )
            new.writestr(copy.copy(entry), data)
        abis = {
            name.split("/")[1]
            for name in old.namelist()
            if name.startswith("lib/") and name.endswith(".so")
        }
        for entry in old.infolist():
            if entry.filename.startswith("assets/lib/") and entry.filename.endswith(
                ".so"
            ):
                target = entry.filename.removeprefix("assets/")
                if target.split("/")[1] in abis and target not in old.namelist():
                    new.writestr(
                        target, old.read(entry), compress_type=zipfile.ZIP_STORED
                    )
        new.writestr("classes2.dex", dex, compress_type=zipfile.ZIP_STORED)


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument(
        "--input", type=Path, default=root / "build/direct-patch/original-merged.apk"
    )
    parser.add_argument("--keystore", type=Path, default=root / "debug.keystore")
    args = parser.parse_args()
    metadata = json.loads((root / "version.json").read_text())
    upstream = json.loads((root / "upstream.json").read_text())
    if digest(args.input) != upstream["sha256"]:
        parser.error("Input APK does not match the pinned upstream SHA-256")
    out = root / "build/release"
    out.mkdir(parents=True, exist_ok=True)
    cache = root / "build/tools"
    cache.mkdir(parents=True, exist_ok=True)
    editor = cache / "APKEditor-1.4.9.jar"
    if not editor.exists():
        with (
            urllib.request.urlopen(EDITOR_URL) as response,
            editor.open("wb") as target,
        ):
            shutil.copyfileobj(response, target)
    if digest(editor) != EDITOR_SHA256:
        parser.error("APKEditor SHA-256 mismatch")
    sdk = args.sdk.resolve()
    jdk = args.jdk.resolve()
    env = dict(os.environ, JAVA_HOME=str(jdk))
    env["PATH"] = str(jdk / "bin") + os.pathsep + env["PATH"]
    tools = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-36/android.jar"

    def run(*command):
        subprocess.run([str(arg) for arg in command], check=True, env=env)

    classes = out / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    sources = sorted((root / "direct-patch/src").rglob("*.java"))
    run(
        jdk / "bin/javac",
        "--release",
        "17",
        "-Xlint:all",
        "-cp",
        android,
        "-d",
        classes,
        *sources,
    )
    run(jdk / "bin/jar", "cf", out / "classes.jar", "-C", classes, ".")
    run(
        tools / "d8",
        "--release",
        "--min-api",
        "28",
        "--lib",
        android,
        "--output",
        out,
        out / "classes.jar",
    )
    run(
        jdk / "bin/javac",
        "--release",
        "17",
        "-Xlint:all",
        "-cp",
        str(editor) + os.pathsep + str(android),
        "-d",
        out,
        root / "direct-patch/PatchResources.java",
    )
    run(
        jdk / "bin/java",
        "-cp",
        str(editor) + os.pathsep + str(out),
        "PatchResources",
        args.input,
        out,
        metadata["versionName"],
        metadata["versionCode"],
    )
    unsigned = out / "unsigned.apk"
    repack(
        args.input,
        unsigned,
        (out / "AndroidManifest.xml").read_bytes(),
        (out / "classes.dex").read_bytes(),
    )
    aligned = out / "aligned.apk"
    run(tools / "zipalign", "-f", "-P", "16", "4", unsigned, aligned)
    signed = out / "soundcore-actions-signed.apk"
    env.setdefault("ANDROID_SIGNING_KEYSTORE_PASSWORD", "android")
    env.setdefault(
        "ANDROID_SIGNING_KEY_PASSWORD", env["ANDROID_SIGNING_KEYSTORE_PASSWORD"]
    )
    run(
        tools / "apksigner",
        "sign",
        "--v1-signing-enabled",
        "false",
        "--ks",
        args.keystore,
        "--ks-pass",
        "env:ANDROID_SIGNING_KEYSTORE_PASSWORD",
        "--key-pass",
        "env:ANDROID_SIGNING_KEY_PASSWORD",
        "--ks-key-alias",
        env.get("ANDROID_SIGNING_KEY_ALIAS", "androiddebugkey"),
        "--out",
        signed,
        aligned,
    )
    run(tools / "apksigner", "verify", "--verbose", "--print-certs", signed)
    run(tools / "zipalign", "-c", "-P", "16", "4", signed)
    (out / "SHA256SUMS").write_text(f"{digest(signed)}  {signed.name}\n")
    unsigned.unlink()
    aligned.unlink()
    print(signed, flush=True)


if __name__ == "__main__":
    main()
