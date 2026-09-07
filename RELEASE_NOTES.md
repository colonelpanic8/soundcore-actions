Add **Toggle wind reduction** as a custom action for connected Liberty 5 Pro earbuds.

- Select it for an Anka, translation, or other supported event, or use the new **Toggle wind reduction now** button.
- Runs the dedicated earbud command in the background and confirms the resulting setting before reporting success.
- Automation apps can invoke the explicit `com.colonelpanic.soundcoreactions.TOGGLE_WIND_REDUCTION` broadcast on `com.colonelpanic.soundcorepatch.WindToggleReceiver` in `com.oceanwing.soundcore`.
- Requires Soundcore to be connected to Liberty 5 Pro (D1203). Existing mappings and app data are preserved.
