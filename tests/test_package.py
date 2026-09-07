import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "build", Path(__file__).resolve().parents[1] / "direct-patch/build.py"
)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class PackagingTests(unittest.TestCase):
    def test_native_assets_are_loadable_without_splits(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.apk"
            output = Path(directory) / "patched.apk"
            with zipfile.ZipFile(source, "w") as apk:
                apk.writestr("AndroidManifest.xml", b"old-manifest")
                apk.writestr("classes.dex", b"packed-code")
                apk.writestr("resources.arsc", b"original-resources")
                apk.writestr("lib/arm64-v8a/libmain.so", b"existing-library")
                apk.writestr(
                    "assets/lib/arm64-v8a/libdependency.so", b"native-dependency"
                )
                apk.writestr("assets/lib/arm64-v8a/libmain.so", b"different-asset-copy")
                apk.writestr("assets/lib/x86/libdependency.so", b"unsupported-abi")
                apk.writestr("META-INF/CERT.RSA", b"old-signature")
                apk.writestr("META-INF/services/provider", b"runtime-metadata")
                apk.writestr("stamp-cert-sha256", b"old-stamp")
            builder.repack(source, output, b"new-longer-manifest", b"new-code")
            with zipfile.ZipFile(output) as apk:
                self.assertEqual(
                    apk.read("lib/arm64-v8a/libdependency.so"), b"native-dependency"
                )
                self.assertEqual(
                    apk.read("assets/lib/arm64-v8a/libdependency.so"),
                    b"native-dependency",
                )
                self.assertEqual(
                    apk.getinfo("lib/arm64-v8a/libdependency.so").compress_type,
                    zipfile.ZIP_STORED,
                )
                self.assertEqual(
                    apk.read("lib/arm64-v8a/libmain.so"), b"existing-library"
                )
                self.assertEqual(apk.read("classes.dex"), b"packed-code")
                self.assertEqual(apk.read("classes2.dex"), b"new-code")
                self.assertEqual(apk.read("resources.arsc"), b"original-resources")
                self.assertEqual(
                    apk.read("META-INF/services/provider"), b"runtime-metadata"
                )
                self.assertNotIn("lib/x86/libdependency.so", apk.namelist())
                self.assertNotIn("stamp-cert-sha256", apk.namelist())
                self.assertNotIn("META-INF/CERT.RSA", apk.namelist())

    def test_existing_second_dex_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.apk"
            with zipfile.ZipFile(source, "w") as apk:
                apk.writestr("classes2.dex", b"existing-code")
            with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
                builder.repack(
                    source, Path(directory) / "out.apk", b"manifest", b"replacement"
                )


if __name__ == "__main__":
    unittest.main()
