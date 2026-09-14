# Soundcore Actions

Make Soundcore's Anka and translation actions open the apps and automations you
actually use. A built-in settings page lets you choose **when this happens → do
this instead**, with a name for each action and a one-tap way to restore the
original behavior.

This is a modified Soundcore Android app. It keeps the normal device controls,
adds a **soundcore (actions)** launcher icon for mappings, and leaves your phone's
default assistant unchanged. The **soundcore (custom)** icon opens the normal app.

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

1. Open **soundcore (actions)**.
2. Edit **Anka assistant**, **Real-time translation**, or **Face-to-face
   translation**, or choose **Add a mapping** for another event.
3. Choose an installed app, a link, an Android intent, a broadcast to an
   automation app, or **Toggle wind reduction**. Give it a useful display name, then test and save it.
4. In Soundcore's earbud controls, assign the corresponding function to a gesture.
   Supported native Anka and translation labels reflect the mapped action name.

On Liberty 5 Pro, Anka gestures dispatch the mapping directly from the Bluetooth
callback, without waiting for Soundcore to open its Anka screen. To open another
app while Soundcore is in the background, choose **Allow background app launches**
and enable Android's **Display over other apps** permission for Soundcore. Open
Soundcore to establish the earbud connection. An ongoing **Earbud actions**
notification keeps the listener running in the background. Disabling custom
mappings or removing the Anka mapping stops this service.

The initial mappings open **Paseo Live Voice** (`paseo://live-voice` in
`sh.paseo.assembly`). They can be changed independently. **Use original Soundcore
behavior** removes one mapping; the main switch temporarily disables all of them.

**Recent events** helps identify an unfamiliar trigger: use a Soundcore feature,
then return to see which app entry point ran. The searchable browser also lists
all declared screens, services, and receivers. Technical identifiers are kept
under **Event details**.

## Wind reduction

Choose **Toggle wind reduction** as a mapping's action, or use **Toggle wind
reduction now** on the mappings page. This currently supports Liberty 5 Pro
(product D1203), with an active connection in Soundcore. It reads the earbuds'
current setting, toggles it, and confirms the result before reporting on or off.
The command runs in the background without opening Soundcore's controls.

An automation app can also send an explicit broadcast:

```text
Action: com.colonelpanic.soundcoreactions.TOGGLE_WIND_REDUCTION
Package: com.oceanwing.soundcore
Receiver: com.colonelpanic.soundcorepatch.WindToggleReceiver
```

A physical gesture still needs an existing earbud event, such as Anka or
translation, assigned in Soundcore. Repurposing that event supplies the trigger;
the wind command itself does not require opening or replacing a Soundcore screen.
The explicit broadcast and the test button work independently of mappings.
Other local apps can invoke this exported receiver; it accepts only the fixed
wind-toggle action and does not accept arbitrary commands or device addresses.

## Spoken messages

Soundcore Actions can read incoming message notifications through the connected
Soundcore earbuds while the phone screen is off. In **soundcore (actions)**,
enable **Read incoming messages when the screen is off**, choose the messaging
apps to allow, and grant Android's Notification Access when prompted. Message
text can be disabled to announce only the sender.

The reader accepts Android messaging-style notifications and message-category
fallbacks from the selected apps. It ignores group summaries and duplicate
updates, refuses to speak through the phone speaker or unrelated audio devices,
and stops speech when the screen turns on. Music temporarily ducks while the
configured Android text-to-speech engine reads the message. Notification contents
stay in Soundcore Actions memory only; the selected TTS engine's privacy behavior
still applies.

The notification listener runs in a dedicated `:message_reader` process, but it
is still part of the modified Soundcore package. Notification Access is powerful:
only enable it if you trust this build, and revoke it at any time from Android's
Notification Access settings.

## What can be repurposed?

Liberty 5 Pro Anka gestures have a direct Bluetooth listener. Other mappings
intercept Soundcore's Android activity, service, and manifest receiver entry
points. They are not limited to three hard-coded translation presets.
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

Mappings, spoken-message preferences, and recent component names/timestamps stay
in the app's private storage. The patch adds no accessibility service, root
requirement, or remote telemetry. Spoken messages require explicit Notification
Access; Soundcore's own bundled services and permissions remain present.
Background app launches use Soundcore's existing **Display over other apps**
permission when enabled by the user.
On ARM64, the build patches the vendor certificate check in the two request-secret
getters, restoring login in the repackaged app. Database encryption behavior is
preserved so existing app data remains readable.

## Development

See [how earbud gestures reach the phone](docs/gesture-architecture.md) for
what the firmware can and cannot trigger, and the
[current architecture and review brief](docs/current-approach.md) for
the interception mechanism, verified behavior, limitations, and open questions.

See [building and verification](direct-patch/README.md). `version.json` controls
both the APK version and GitHub release tag. Releases keep the same signing key
so Obtainium can install updates without clearing app data.

The patch source in this repository is MIT licensed. The assembled APK includes
Soundcore and its third-party dependencies under their respective terms; the MIT
license does not relicense those components. See [third-party components](THIRD_PARTY.md).
