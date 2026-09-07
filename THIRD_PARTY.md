# Third-party components

- **Soundcore 5.0.21**, package `com.oceanwing.soundcore`: the underlying proprietary
  application and bundled libraries/assets. Its original terms and notices apply.
  The modified APK is not an official Soundcore release.
- **[APKEditor 1.4.9](https://github.com/REAndroid/APKEditor)**: Apache-2.0 licensed
  build tool, including ARSCLib, used to merge APK splits and edit the compiled
  manifest. Downloaded at build time and checked by SHA-256; not added to the
  Android runtime payload.

The repository's MIT license covers the custom patch source and supporting build
scripts. It does not cover or relicense the Soundcore APK build input or assembled
release's third-party code and assets.
