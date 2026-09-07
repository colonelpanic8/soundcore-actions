#!/usr/bin/env python3
"""Build and run the patch in an isolated Android runtime test harness."""

import argparse
import os
import subprocess
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--sdk", type=Path, required=True)
parser.add_argument("--jdk", type=Path, required=True)
parser.add_argument("--adb", type=Path, required=True)
parser.add_argument("--port", default="5037")
parser.add_argument("--serial", required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
out = root / "build/android-tests"
classes = out / "classes"
classes.mkdir(parents=True, exist_ok=True)
tools = args.sdk / "build-tools/36.0.0"
android = args.sdk / "platforms/android-36/android.jar"
env = dict(os.environ, JAVA_HOME=str(args.jdk))
env["PATH"] = str(args.jdk / "bin") + os.pathsep + env["PATH"]


def run(*command, capture=False):
    return subprocess.run(
        [str(arg) for arg in command],
        check=True,
        env=env,
        text=True,
        capture_output=capture,
    )


run(
    args.jdk / "bin/javac",
    "--release",
    "17",
    "-Xlint:all",
    "-cp",
    str(android) + os.pathsep + str(root / "build/release/classes"),
    "-d",
    classes,
    *sorted((root / "tests/android").rglob("*.java")),
)
run(args.jdk / "bin/jar", "cf", out / "tests.jar", "-C", classes, ".")
run(
    tools / "d8",
    "--min-api",
    "28",
    "--lib",
    android,
    "--output",
    out,
    out / "tests.jar",
    root / "build/release/classes.jar",
)
run(
    tools / "aapt2",
    "compile",
    "--dir",
    root / "tests/android/res",
    "-o",
    out / "resources.zip",
)
run(
    tools / "aapt2",
    "link",
    "--manifest",
    root / "tests/android/AndroidManifest.xml",
    "-I",
    android,
    "-o",
    out / "unsigned.apk",
    out / "resources.zip",
)
with zipfile.ZipFile(out / "unsigned.apk", "a") as apk:
    apk.write(out / "classes.dex", "classes.dex")
run(tools / "zipalign", "-f", "4", out / "unsigned.apk", out / "aligned.apk")
run(
    tools / "apksigner",
    "sign",
    "--ks",
    root / "debug.keystore",
    "--ks-pass",
    "pass:android",
    "--out",
    out / "tests.apk",
    out / "aligned.apk",
)
adb = [args.adb, "-P", args.port, "-s", args.serial]
run(*adb, "install", "--no-incremental", "-r", out / "tests.apk")
result = run(
    *adb,
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "com.colonelpanic.soundcoreactions.tests/com.colonelpanic.soundcorepatch.RuntimeTests",
    capture=True,
)
print(result.stdout)
if "Passed " not in result.stdout or "INSTRUMENTATION_CODE: -1" not in result.stdout:
    raise SystemExit("Android runtime tests failed")
