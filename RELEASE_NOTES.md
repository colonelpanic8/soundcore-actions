Add reliable background earbud actions and optional spoken messages.

- Read incoming notifications from selected messaging apps through connected Soundcore earbuds while the screen is off. Sender-only and sender-plus-message modes are available; speech stops when the screen turns on. This is opt-in and requires Android Notification Access.
- Keep the Liberty 5 Pro Anka listener active with an ongoing connected-device notification, and dispatch physical Anka callbacks without waiting for Soundcore to open its screen.
- Add a guided Display over other apps permission for mapped background app launches and keep the Soundcore and Actions launchers in independent tasks.
- Restore Soundcore account login in the repackaged APK by narrowly patching the request-signing certificate check while preserving existing database encryption keys.
- Expand Android runtime and packaging regression coverage. Existing mappings and app data are preserved.
