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

So for these earbuds there are exactly two gesture functions that produce an
app-visible event: **Anka** and **AI translation**. Two gestures assigned to the
same function are indistinguishable on the phone. The System Voice Assistant
function goes to Android's default assistant and never enters the Soundcore
process, which is why keeping Gemini as the default assistant is unaffected by
this project.

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

- **Adding picker rows is possible but pointless on its own.** A new row needs
  a firmware code, and the only codes that reach the phone are already used by
  Anka and AI translation.
- **Genuinely distinct hardware-triggered actions are limited to two** on this
  firmware (Anka, AI translation), plus the start/stop flag on each event if one
  wanted to distinguish a first press from a second.
- **An earlier hook is available and would be better than the component
  factory.** Registering our own `Cmm2BtEventCallback` proxy on
  `Cmm2BtDeviceManager` (the same technique `WindDevice` uses) receives
  `getAIChatStartCmdCallback` and `getAudioRecordCmdCallback` directly. That
  would distinguish a physical gesture from manual navigation, skip the
  vendor's AI consent dialog, and not depend on which vendor listener happens
  to be registered. It still requires the vendor process to be alive with the
  SPP link connected, and it has not been validated with a physical gesture.
- **Labels are only labels.** Showing "Paseo Live Voice" in the picker changes
  nothing in the firmware; the earbuds still store the Anka code.

## Smallest next experiment

Add a temporary stack-trace log to `ComponentFactory.instantiateActivity` for
`AIChatActivity`, perform a real Anka gesture with the app in the foreground,
then again with the app in the background. The trace shows the vendor call
chain from `Cmm2BtDispatch.p` to `startActivity`, and the background run answers
the open question above. If the callback fires in the background, the proxy
listener design can replace the component factory for gesture-driven actions.

## How the evidence was gathered

- Reflection on the live `Cmm2BtDeviceManager.y` singleton listed listener
  classes and read `ControllerBtnModel.getFunctionName` (generic table of 16
  codes; the Liberty 5 Pro list itself lives in the stripped data source).
- `/proc/self/mem` is denied to apps, but `sun.misc.Unsafe.getLong` can read the
  process's own memory. The `dalvik.system.DexFile.mCookie` native pointers give
  each in-memory DEX file's start and size; 21 files were dumped, their
  Adler-32 checksums recomputed, and decompiled with jadx. Roughly a fifth of
  the interesting methods are empty stubs executed by the protector natively.
