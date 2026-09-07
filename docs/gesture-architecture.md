# How earbud gestures reach the phone, and what "new actions" can mean

Status: investigated on 2026-09-07 against Soundcore 5.0.21 with a connected
Liberty 5 Pro. Evidence came from reflection inside the running vendor process
and from decompiling the protected in-memory DEX files. Method bodies that the
vendor's protector strips (marked below) could not be read; those points are
inference, not proof.

## Three different things that could be called "an action"

1. **A row in the Controls picker.** The list is built per product by the
   control module (`com.soundcore.control.datasource.A3956DataSource` for the
   Liberty 5 Pro). Each row is a `CustomUiSetUpItemBean` with a `gestureCmd`
   integer that the popup (`CustomUiSetupPopup`, an XPopup bottom sheet in its
   own window) writes to the earbuds through the custom-button command. Adding
   a row is only a UI change: the row must carry a code the firmware already
   understands.
2. **A firmware function code.** The earbuds store one code per gesture
   (`ControllerBtnModel`: single/double/triple tap, long press, swipe, per side).
   Most codes are executed entirely on the earbuds (volume, track, play/pause,
   ambient sound, none). The firmware decides what each code does; the app
   cannot add codes.
3. **A phone-side event.** Only a few codes make the earbuds send a packet the
   app can see. Everything else either stays on the earbuds or goes through
   standard Bluetooth profiles (AVRCP media keys, HFP voice assistant) that
   Android routes to the media session or the default assistant, not to the
   Soundcore app.

The current design only substitutes Android components after the vendor app has
already decided to open a screen. That is a choice made because the vendor code
was unreadable at the time, not something the firmware requires.

## The firmware events that actually reach the app

Received packets flow `SppLink$ConnectedThread` → `Cmm2BtDeviceManager` →
product analysis service → `Cmm2BtDispatch` → every registered
`Cmm2BtEventCallback` (the list is `Cmm2BtDeviceManager.l`; registration is
`Q`, removal `T`). `Cmm2BtDispatch` is fully readable. Command group 24 is the
AI group (`Cmm2BtDispatch.p`):

| Sub-command | Callback | Meaning |
| --- | --- | --- |
| 3 | `getAIChatStartCmdCallback(success, isStartRecord)` | Earbud asked to start or stop Anka. No gesture or side identifier is included. |
| 2 | `getAudioRecordCmdCallback(success, isStartRecord, action)` | Earbud asked to start or stop translation recording. Handled by `RealtimeBizHandler`, `RealtimeActivity`, `TranslatingFaceToFaceActivity`, `PlayRecordActivity`. |
| 1, 4 | translation / AI chat audio data | Audio stream while a session runs. |
| -127, -124, -125, -123 | `set…Callback` acknowledgements | Replies to commands the phone sent. |

`getKeyDownReportData` (command -117) exists in the dispatcher, but its only
handler is the audio-note recorder product family; nothing suggests the Liberty
5 Pro firmware reports raw key presses.

Through the inspected vendor path, two gesture functions were identified that
produce an app-visible event: **Anka** and **AI translation**. Neither event
carries a gesture or side identifier, so two gestures assigned to the same
function would be indistinguishable on the phone through this path. No raw
gesture reporting was found for the Liberty 5 Pro, but the app dispatcher and a
stripped product data source cannot prove what the firmware is able to send:
the firmware may have functions or packets the app never registers for. Treat
"only two events" as an inference about the vendor app, not a verified limit of
the firmware, until a hardware or protocol test says otherwise. The System
Voice Assistant function is expected to go to Android's default assistant
through the standard Bluetooth profiles rather than the Soundcore process; that
is consistent with Gemini remaining the default assistant, but it was not
traced here.

## Who turns the Anka event into a screen

`getAIChatStartCmdCallback` is overridden only by `AIChatBizHandler.eventAdapter`
(body stripped). On the home screen the registered listeners were
`SoundCoreMainActivity$1`, `AIPrivacyManager$eventAdapter$1`,
`SportsTimingStateManager$eventAdapter$1` and `DiscoverFragment$1`; none of them
override the AI chat callback. When the process was started in the background
with no activity, the listener list was empty. `AIPrivacyManager` owns
`AiChatEnterExecutor` and `TranslateEnterExecutor` (both stripped) and the AI
privacy dialog that appears before `AIChatActivity`; it is the most likely
bridge, but the hop from the callback to `startActivity` was not observed.

Open question, only answerable with a physical gesture: whether a Liberty 5 Pro
Anka gesture opens `AIChatActivity` at all while the Soundcore app has no
activity alive. The dedicated wind command works from a broadcast because the
SPP link stays up while the process lives, but that says nothing about the
vendor's gesture handling.

## Consequences for the design

- **Adding picker rows is possible but not useful on its own.** A new row
  needs a firmware code the earbuds act on, and the only codes known to reach
  the phone through the inspected path are the ones Anka and AI translation
  already use. Whether unused codes exist that the firmware would forward to
  the phone is unknown.
- **Distinct hardware-triggered actions are currently bounded by those two
  identified events** (plus the start/stop flag on each event, if one wanted
  to distinguish a first press from a second). Widening that requires either
  evidence of additional firmware events or firmware changes; neither has been
  tested.
- **A proposed earlier hook to validate.** Registering our own
  `Cmm2BtEventCallback` proxy on `Cmm2BtDeviceManager` (the same technique
  `WindDevice` uses) should receive `getAIChatStartCmdCallback` and
  `getAudioRecordCmdCallback` directly. If it does, it could distinguish a
  physical gesture from manual navigation, avoid the vendor's AI consent
  dialog, and not depend on which vendor listener happens to be registered.
  None of that is proven: it requires the vendor process to be alive with the
  SPP link connected, and no physical gesture has been observed through it.
- **Labels are only labels.** Showing "Paseo Live Voice" in the picker changes
  nothing in the firmware; the earbuds still store the Anka code.

## Smallest next experiment

A stack trace inside `ComponentFactory.instantiateActivity` would not help: the
factory runs after the Android framework's asynchronous launch dispatch, so the
original callback-to-`startActivity` caller chain is gone by then. Instead,
register a temporary logging `Cmm2BtEventCallback` proxy (as the wind probe
did) that timestamps every raw callback, and timestamp `AIChatActivity`
creation in the factory. Perform a real Anka gesture with the app in the
foreground, then again with no Soundcore screen open. Matching timestamps show
whether `getAIChatStartCmdCallback` fires at all and whether it leads to an
activity launch in each state. If the caller chain itself matters, instrument
the sending `startActivity` site (for example by wrapping the vendor
`Instrumentation` or logging in `Activity.startActivity` of the mapped
component) rather than the receiving factory. If the callback fires in the
background, the proxy listener design becomes a candidate to replace the
component factory for gesture-driven actions, subject to its own validation.

## How the evidence was gathered

- Reflection on the live `Cmm2BtDeviceManager.y` singleton listed listener
  classes and read `ControllerBtnModel.getFunctionName` (generic table of 16
  codes; the Liberty 5 Pro list itself lives in the stripped data source).
- `/proc/self/mem` is denied to apps, but `sun.misc.Unsafe.getLong` can read the
  process's own memory. The `dalvik.system.DexFile.mCookie` native pointers give
  each in-memory DEX file's start and size; 21 files were dumped, their
  Adler-32 checksums recomputed, and decompiled with jadx. Roughly a fifth of
  the interesting methods are empty stubs executed by the protector natively.
