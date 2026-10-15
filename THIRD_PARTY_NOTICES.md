# Third-party components and source references

The native Android component in `android/` is GPL-3.0-or-later, as authorized by the project owner. See `android/LICENSE` and `android/NOTICE`. Public availability does not assign rights in recordings or alter dependency licences. The legacy PWA's original code has no additional redistribution licence selected; its third-party dependencies retain their respective licences.

## Native Android APK

- **NewPipeExtractor v0.26.5** is an actual on-device dependency, pinned through Gradle. Its GPL licence is included at `android/LICENSE` and inside the APK. The pinned upstream revision and artifact digest are recorded in `evidence/native-android-v0.3.0.json`.
- **AndroidX Media3 1.9.3** provides native audio playback, the foreground media service and system media session. Media3 uses the Apache-2.0 licence; its upstream notices continue to apply.
- **OkHttp 4.12.0**, AndroidX annotations and the Android desugaring library are pinned in `android/app/build.gradle`. Their upstream licences and notices continue to apply.
- The **Gradle wrapper** retains its Apache-2.0 headers and is used for source builds.
- **NewPipe and LibreTube application sources** were inspected as architectural references. Their application source code and APKs are not copied into BlackYT. NewPipeExtractor is the separately declared native dependency above.

## Legacy PWA / Termux release

The v0.2.0 ZIP contains original BlackYT runtime files and installer documentation. Python, Node, Termux packages and Python dependencies are installed separately. No YT audio, account cookies, private credentials, local certificates, source checkout of an upstream app, or Termux APK is bundled.

- **yt-dlp** is the pinned legacy Python extractor dependency. Its [source licence](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE) is the Unlicense; optional components and distributed binaries may have different notices.
- **yt-dlp EJS** and the selected JavaScript runtime support extraction; see [upstream EJS documentation](https://github.com/yt-dlp/yt-dlp/wiki/EJS).
- **Termux** is installed independently. Its app is [GPLv3 with documented component exceptions](https://github.com/termux/termux-app/blob/master/LICENSE.md). BlackYT does not package or fork the Termux APK.

This independent educational project does not grant rights to YT recordings or promise a supported platform API integration. Use only content you may lawfully access and use. See `DISCLAIMER.md`.
