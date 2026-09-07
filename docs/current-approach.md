# Current approach and architecture review

Status: Soundcore Actions v0.1.1, reviewed on 2026-09-07.

## Purpose

Let existing Soundcore Liberty 5 Pro controls run user-selected Android actions,
initially Paseo Live Voice, while retaining normal earbud configuration and
keeping Gemini as the phone's default assistant. Actions now support installed
apps, links, Android intent URIs with extras, and broadcasts.

The user wants few installed apps, a simple interface, and a clear explanation
of which existing earbud function maps to which action. Two launcher icons and
intercepting entire screens are implementation choices to reconsider, not
requirements. The user explicitly requested an independent Fable review.

## What is installed

One modified Soundcore APK uses the original package ID,
`com.oceanwing.soundcore`. It currently exposes two launcher entries:

- **soundcore (custom)** opens the original `WelcomeActivity`.
- **soundcore (actions)** opens our mapping settings. Those settings also contain
  an **Open Soundcore** button.

The old, separately installed accessibility bridge had the same visible name,
which made the launcher confusing. It and the isolated test APK have been
uninstalled. The current solution needs no accessibility service or root.
Paseo Assembly is a possible destination, and Obtainium is only an updater.
Neither is required for mappings that target another installed app.

## Two different mappings

The expected physical-input path is:

```text
Earbud gesture
  -> existing firmware/vendor event
  -> Soundcore's internal handling
  -> request to create a particular Android component
  -> our ComponentFactory substitutes a dispatcher
  -> configured Android action
```

The patch implements the last two arrows. It does not decode Bluetooth packets,
program new firmware gestures, or observe raw taps. The exact vendor event
handler between the physical gesture and the Android component has NOT been
traced or validated end to end. Treat that part of the diagram as a hypothesis
to verify on hardware, not a completed reverse-engineering result.

The gesture-to-built-in-function setting remains Soundcore's responsibility.
Our second mapping is keyed by **component type plus Java class name**, not by
left/right earbud or tap count. The settings UI calls these “events,” but this
means component creation, not necessarily a firmware event.

## Implementation

All patch Java classes live in
`direct-patch/src/com/colonelpanic/soundcorepatch/`.

| File | Responsibility |
| --- | --- |
| `ComponentFactory.java` | Installed as the manifest's `android:appComponentFactory`; substitutes mapped activities, services, and manifest receivers; delegates unmapped components to the original AndroidX factory when available. |
| `LaunchActivity.java` | Reads the source mapping, launches its action, then finishes. |
| `ActionService.java`, `ActionReceiver.java` | Dispatch mapped service/receiver creations. These are advanced, potentially disruptive options. |
| `ActionRunner.java` | Builds app-launch, link, intent, or broadcast intents and dispatches them; validates basic input and rejects direct targets in our own patch namespace. |
| `Rules.java` | Stores mappings in an AtomicFile JSON document, reads them across app processes, migrates prototype preferences, and keeps bounded component history in per-process files. |
| `SettingsActivity.java` | Source-to-action cards, enable switch, editor, app picker, test button, restore-original behavior, searchable component browser, and recent events. |
| `ControlLabels.java` | Attempts to replace known native Anka/translation TextView labels with configured action names. Does not rewrite Flutter-rendered text. |
| `direct-patch/PatchResources.java` | Alters the manifest to install the factory and settings launcher, keeping original activity declarations. |
| `direct-patch/build.py` | Adds compiled patch code as a second DEX, prepares native libraries, signs, and verifies the APK. |

The three initial mappings all target `paseo://live-voice` in
`sh.paseo.assembly`:

| User-facing source | Activity class |
| --- | --- |
| Anka assistant | `com.oceanwing.soundcore.activity.a3874.AIChatActivity` |
| Real-time translation | `com.soundcore.translation.translating.realtime.RealtimeActivity` |
| Face-to-face translation | `com.soundcore.translation.translating.TranslatingFaceToFaceActivity` |

Mappings can be changed independently or removed. Removing one restores normal
component creation; the global switch disables substitutions without deleting
mappings. The component browser lists more than these three presets, but that
is not proof that every listed component is a useful earbud trigger.

## Why this interception point was chosen

The vendor app's main code is protected by an iJiami loader. A normal static
DEX decompilation exposes the shell and some routing information, not a fully
readable implementation of its Bluetooth callbacks. Intercepting Android
component creation provided a small, demonstrably working place to substitute
an action while retaining the original application and device-control code.

That makes this a practical prototype, not proof that it is the best long-term
architecture. An earlier vendor-event hook could distinguish earbud events from
manual navigation. An independent Bluetooth receiver might avoid modifying
Soundcore, but protocol visibility, connection coexistence, and Android
background behavior would need evidence. Neither alternative has been shown
working for these earbuds. Changing the system assistant would undermine the
user's explicit requirement to keep Gemini as the default.

## Known limitations and questions

- Manually opening a mapped screen also launches the replacement. We do not
  distinguish an in-app tap from an earbud-originated request.
- The factory runs on component creation. Existing activity instances receiving
  `onNewIntent`, running services, internal callbacks, and dynamically registered
  receivers may bypass it.
- Two gestures resolving to the same component cannot have distinct mappings.
- Replacing an arbitrary service/receiver removes its original behavior, which
  could break connectivity or another feature. Android may restrict background
  activity launches. The generic browser is broader than the tested surface.
- Atomic file replacement prevents partial JSON, but should not be assumed to
  solve every concurrent read-modify-write case across multiple processes.
- Recursive mappings through other Soundcore components or implicit intents
  deserve review; current validation only rejects direct patch-class targets.
- Native gesture-label rewriting is implemented but not yet verified on the
  physical controls page. The two launcher entries are not architecturally
  necessary. Do not disable the original WelcomeActivity to hide its icon:
  that could also disable the entry point used by Open Soundcore.

## Evidence and remaining validation

Verified on the connected Android Fold:

- Normal cold launch of the modified app and its settings.
- Manual Anka launch intercepted by the factory.
- Changing Anka to Calculator through the editor, saving, and opening Anka in
  Soundcore opened Calculator. The Paseo preset was subsequently restored.
- Both translation modes dispatched the Paseo link in an earlier, simpler
  direct-patch prototype. They still require retesting against the generalized
  factory and from a physical earbud gesture.
- Default assistant remained Google's assistant package. No accessibility
  service was enabled for the current solution.
- Obtainium recognizes v0.1.0 as installed/current.

Automated checks:

- 16 assertions in an isolated Android harness cover intent parsing, typed
  extras, basic invalid targets, mapping persistence, activity/service/receiver
  substitution, disable/removal, and actual broadcast delivery. These are not
  evidence of the vendor Bluetooth dispatch path.
- Two packaging regression tests, Java compilation/lint, source formatting,
  workflow checks, signature verification, and APK alignment passed.
- GitHub release and automatic F-Droid deployment succeeded. Live F-Droid index
  validation with a pinned certificate passed through fdroidserver's downloader.

A physical earbud gesture has not been confirmed. At the last check the vendor
app reported the earbuds disconnected. Do not report the complete gesture-to-
voice flow as working.

The installed Paseo APK separately showed “Unmatched Route” for the Live Voice
link. Its native-link routing fix is committed in the Paseo project, but an
updated assembly APK had not been installed. This is independent of Soundcore's
mapping UI and action dispatcher. Avoid treating that failure as evidence that
Soundcore did not dispatch an action.

## Build constraints learned from failures

- Retain the original application ID. A renamed-package experiment did not
  initialize the protected vendor application successfully.
- Preserve the application label as a resource reference. Replacing it with a
  literal string made `ApplicationInfo.labelRes` zero and caused vendor startup
  failures. Activity launcher labels can have their own names.
- The input merges the original four APK splits. Some vendor code expected a
  removed split when loading native libraries; the build promotes native asset
  libraries into standard `lib/<abi>/` locations while retaining asset copies.
  Both ARM64 and ARMv7 remain supported. See the packaging tests before changing
  this behavior.
- Keep the signing key: replacing it prevents in-place updates. No keystore,
  original APK, decompiled code, phone logs, or credentials belong in Git.
- Use the pinned Google Java Format 1.36.1 used by CI. An unpinned local Nix
  formatter produced a different layout and failed CI on a later change.

Build instructions and release details: [building](../direct-patch/README.md),
[F-Droid](fdroid.md), [project README](../README.md).

## Requested review

Read this brief, then inspect the implementation rather than accepting its
claims. Write an independent recommendation in `docs/architecture-review.md`:

1. Keep the component-factory approach, narrow it, or pursue an earlier hook?
   Explain the decision against the user's needs and the evidence available.
2. Propose a single-icon interface that keeps device controls and mapping
   settings discoverable, with minimal modification to protected vendor code.
3. Identify which abstractions or settings are misleading or unnecessarily
   broad, especially the difference between a firmware event and component
   creation. Prioritize concrete correctness issues over generic hardening.
4. Specify the smallest useful experiment to trace the actual earbud dispatch
   path and distinguish a gesture from manual navigation, if possible.
5. Recommend an ordered implementation and verification plan. Clearly separate
   verified behavior, inference, and unanswered questions.

This initial task is consultation and documentation. Do not change runtime code,
install APKs, modify the phone, or publish a release as part of the review.
