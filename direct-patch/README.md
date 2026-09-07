# Building Soundcore Actions

The build adds a custom `AppComponentFactory` and a settings activity to a pinned
Soundcore APK. Original component declarations and packed application code remain
intact. The factory delegates unmapped components to the original AndroidX factory
and substitutes a small dispatcher for mapped components.

The settings page supports named app, link, intent, and broadcast actions. Mappings
are stored atomically and read across Soundcore processes. Recent events are kept
in separate bounded files for each process. Native control labels update from the
mappings without changing text input fields.

## Build

Requires Python 3.11+, JDK 21, Android SDK platform 36 and build tools 36.0.0.

```sh
python scripts/fetch-input.py
python direct-patch/build.py --sdk "$ANDROID_HOME" --jdk "$JAVA_HOME" \
  --keystore /path/to/signing.keystore
```

Set `ANDROID_SIGNING_KEYSTORE_PASSWORD`, `ANDROID_SIGNING_KEY_PASSWORD`, and
`ANDROID_SIGNING_KEY_ALIAS` for the signing key. The local development default is
`debug.keystore`, password `android`, alias `androiddebugkey`. Keys and generated
APKs are ignored by Git. Preserve the release key to keep Android updates working.

Output: `build/release/soundcore-actions-signed.apk` and `SHA256SUMS`.

`upstream.json` pins the source APK and input archive by SHA-256. The input was
created by merging the four Soundcore 5.0.21 splits with APKEditor 1.4.9. The build
also checks APKEditor's SHA-256. It preserves the original DEX/resources, adds the
patch as `classes2.dex`, and promotes native libraries from `assets/lib/<abi>/`
into the standard `lib/<abi>/` paths for ABIs already supported by the app. This
avoids Soundcore's lookup of a split APK that no longer exists. The asset copies
remain available for code that uses them directly.

The build compiles Java with `-Xlint:all`, aligns native libraries to 16 KB pages,
signs the combined APK, and verifies both signature and alignment.

## Check

```sh
ruff format --check direct-patch/build.py scripts/fetch-input.py scripts/test-android.py tests/test_package.py
ruff check direct-patch/build.py scripts/fetch-input.py scripts/test-android.py tests/test_package.py
python -m unittest discover -s tests -p test_package.py -v
google-java-format --dry-run --set-exit-if-changed \
  direct-patch/PatchResources.java direct-patch/src/com/colonelpanic/soundcorepatch/*.java
```

Build the app, then run the focused Android tests on a connected device:

```sh
python scripts/test-android.py --sdk "$ANDROID_HOME" --jdk "$JAVA_HOME" \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial DEVICE_SERIAL
```

The tests package the compiled patch in a separate Android harness, avoiding
Soundcore’s initialization under instrumentation and leaving its data untouched.
They cover typed intent extras, invalid targets, arbitrary component interception,
removal/global disable, and actual delivery of a custom broadcast.

For device verification, exercise Anka and both translation modes, change a target
in the UI, test it, and restore the original behavior. Check native gesture labels,
Bluetooth connectivity after startup, a cold restart, and a physical earbud press.
The current defaults also need a Paseo build that supports the `live-voice` link.

```sh
adb logcat -s SoundcoreActions:I '*:S'
```

## Publish

Update `version.json` with a new version name and strictly increasing version code,
update `RELEASE_NOTES.md`, then push a matching `v<versionName>` tag. The **Release
App Artifacts** workflow builds and verifies the APK and publishes it with its
checksum. It uses the repository's `ANDROID_SIGNING_*` Actions secrets.

The `inputs-soundcore-5.0.21` prerelease is a pinned build dependency. Do not replace
its asset in place. A future upstream update needs a new input archive, updated
hashes, and device verification of the component factory and native libraries.

## Built-in wind reduction

`WindToggle` performs read → invert → write → acknowledgement → readback, with
an eight-second timeout in `WindToggleReceiver`. Acknowledgement alone is not
success. `WindDevice` adapts the pinned upstream runtime through reflection:
`Cmm2BtDeviceManager.D5(11, enabled)` is the dedicated switch command; `r()`
requests fresh device information. The adapter accepts only the verified D1203
product and checks the active device address before each request. Callback work
is posted to the main queue so listener removal cannot mutate the vendor's
listener list during dispatch. Multiple simultaneous toggles are rejected.

The explicit receiver provides a background entry point. Activity mappings use
a no-display trampoline for this action. Android runtime tests cover both toggle
directions, readback confirmation, failed reads/writes/acknowledgements, timeout,
and listener cleanup. Hardware verification on Liberty 5 Pro also confirmed the
broadcast can toggle the setting while Android remains in Dozing state.
