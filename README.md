# Soundcore Actions

Make Soundcore's Anka and translation actions open the apps and automations you
actually use. A built-in settings page lets you choose **when this happens → do
this instead**, with a name for each action and a one-tap way to restore the
original behavior.

This is a modified Soundcore Android app. It keeps the normal device controls,
adds a **Soundcore Actions** launcher icon for mappings, and leaves your phone's
default assistant unchanged. The **Soundcore devices** icon opens the normal app.

## Install and update

Download **soundcore-actions-signed.apk** from [Releases](https://github.com/colonelpanic8/soundcore-actions/releases).
Requires Android 9 or newer on ARM64 or ARMv7. The current build is based on
Soundcore 5.0.21.

For Obtainium, add this source URL:

```
https://github.com/colonelpanic8/soundcore-actions
```

[Add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/colonelpanic8/soundcore-actions)

For F-Droid, add the repository from the [install page](https://colonelpanic8.github.io/soundcore-actions/).
This is a self-hosted repository; see [F-Droid setup](docs/fdroid.md).

Each regular release has one signed APK and a SHA-256 checksum. Keep prereleases
disabled: the `inputs-*` prerelease holds build inputs, not an app update.

The package remains `com.oceanwing.soundcore`. Installing over the official app
requires uninstalling it first because the signatures differ; that clears its
local app data. Subsequent Soundcore Actions releases update in place. Official
store updates cannot update the modified installation.

The signing certificate SHA-256 is:

```
6286a277fe2f2b0d8b0d5121bed231d150f1baffa6bfa4fe763adc95c6fc0640
```

## Make a mapping

1. Open **Soundcore Actions**.
2. Edit **Anka assistant**, **Real-time translation**, or **Face-to-face
   translation**, or choose **Add a mapping** for another event.
3. Choose an installed app, a link, an Android intent, or a broadcast to an
   automation app. Give it a useful display name, then test and save it.
4. In Soundcore's earbud controls, assign the corresponding function to a gesture.
   Supported native Anka and translation labels reflect the mapped action name.

The initial mappings open **Paseo Live Voice** (`paseo://live-voice` in
`sh.paseo.assembly`). They can be changed independently. **Use original Soundcore
behavior** removes one mapping; the main switch temporarily disables all of them.

**Recent events** helps identify an unfamiliar trigger: use a Soundcore feature,
then return to see which app entry point ran. The searchable browser also lists
all declared screens, services, and receivers. Technical identifiers are kept
under **Event details**.

## What can be repurposed?

Mappings intercept Soundcore's Android activity, service, and manifest receiver
entry points. They are not limited to three hard-coded translation presets.
Unmapped components are created normally through Soundcore's original factory.

This does **not** create new earbud firmware events or intercept every operation
inside Soundcore. Controls handled entirely by the earbuds, dynamically registered
receivers, and calls within already-running components may not pass through the
factory. Two gestures that produce the same event cannot be distinguished by this
version. A screen opened manually also follows its mapping.

Background events are an advanced option. Replacing a service or receiver removes
its original behavior, which can include device connectivity; Android may restrict
opening another app from the background. Existing component instances must be
recreated before newly edited mappings can take effect.

Mappings and recent component names/timestamps stay in the app's private storage.
The patch adds no permissions, accessibility service, root requirement, or remote
telemetry. Soundcore's own bundled services and permissions remain present.

## Development

See the [current architecture and review brief](docs/current-approach.md) for
the interception mechanism, verified behavior, limitations, and open questions.

See [building and verification](direct-patch/README.md). `version.json` controls
both the APK version and GitHub release tag. Releases keep the same signing key
so Obtainium can install updates without clearing app data.

The patch source in this repository is MIT licensed. The assembled APK includes
Soundcore and its third-party dependencies under their respective terms; the MIT
license does not relicense those components. See [third-party components](THIRD_PARTY.md).
