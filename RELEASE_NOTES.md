Initial release of Soundcore Actions, based on Soundcore 5.0.21.

- Configure Anka, translation, and other Soundcore app entry points with named custom actions.
- Open installed apps, deep links, Android intents, or send broadcasts to automation apps.
- Browse recent events and declared components; restore original behavior per mapping or globally.
- Show configured action names in supported native Soundcore controls.
- Install and update from a single consistently signed APK, including through Obtainium.

Requires Android 9+ on ARM64/ARMv7. The package is `com.oceanwing.soundcore`; replacing the official app requires uninstalling it first and clears its local data. The patch leaves the system's default assistant unchanged.

This intercepts Android component creation, not every low-level earbud event. Background mappings and individual hardware gestures need testing on the intended device. The bundled Paseo preset requires a compatible Paseo Live Voice build.
