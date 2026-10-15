# BlackYT for Android

**Android only (Android 8.0 or newer). An iOS app for iPhone and iPad is planned for later; no release date yet.**

BlackYT is an Android-only app for finding and playing YT music links. It extracts stream URLs on the phone with NewPipe Extractor and plays them through AndroidX Media3. It requires Android 8.0 or newer. iPhone and iPad support is planned for later with no release date. The app label is **BlackYT**; normal playback needs no Termux, Chrome, local server, Mac, account, or cloud relay.

## Public release

The public native release is [v0.3.1](https://github.com/ezzye/BlackYT/releases/tag/v0.3.1), with **BlackYT-Android-v0.3.1.apk**. It updates app wording and the disclaimer on the v0.3.0 playback engine. For beginner installation steps, questions, and recovery help, see the [Android install guide](../docs/INSTALL.md), [FAQ](../docs/FAQ.md), and [troubleshooting guide](../docs/TROUBLESHOOTING.md). The [standalone HTML guide](docs/native-android.html) is convenient for offline reading and sharing.

The old Chrome PWA home-screen app was labelled **BlackYT Phone** and belongs to the separate legacy v0.2.0 Termux setup. The native APK does not need that installation.

## Search, filter, and listen

1. Tap the search box. Enter a song or artist, or paste a YT video URL, then tap **Search**. There is no Android Open/Share link intent; paste links into the search box.
2. **Music results only** starts checked. Uncheck it to see results that the music filter removed. The check is a best-effort metadata filter, not a reliable guarantee of music classification.
3. Optionally enter comma-separated words in **Include any words** and **Exclude any words**. A result is kept when it matches any included word (or the include field is empty), and removed when it matches any excluded word. Matching uses available title/channel text.
4. Tap **Play** on a result to start it immediately. Tap **Add** to append it to the FIFO queue, after items already lined up.
5. Use the player controls for Previous, Stop, Play/Pause, Next, and seeking. **Retry current song** re-extracts a fresh stream link, which can help after a network change or an expired link.

Android's notification and lock-screen controls use the native media session. Queue contents last only for the current app session; search filter settings persist across launches. There is no sign-in and no offline download. Audio goes directly from the phone to YT's Googlevideo stream host without a BlackYT cloud relay. YT may change its service and disrupt search or playback.

## Recovery

- **No search results:** check the phone's internet connection, try the song title or artist, uncheck **Music results only**, and clear or broaden include/exclude words.
- **Playback stops or reports a stream error:** check the connection, then tap **Retry current song**. This requests a fresh stream URL from the phone.
- **A YT link does not play:** verify it opens in the YT app or site, then paste its full URL into BlackYT's search box. Some videos may be unavailable to the extractor, including age-restricted or otherwise restricted videos; successful playback is not guaranteed.
- **App cannot update over an installed copy:** updates must be signed with the same release key. A differently signed APK cannot replace the existing installation in place. Do not uninstall if you need app state before you have checked your backup plan.
- **Release link shows 404:** check the release URL and try again later if publication is still pending. The source repository is public; no GitHub sign-in is required for published release assets.

## Current validation

The v0.3.0-native signed APK was installed on the Pixel 10 Pro XL and its installed APK hash matched the release file. Five unit checks passed, release lint reported zero errors with routine warnings, and direct native Googlevideo audio returned HTTP 200. The owner confirmed audible playback, continued playback with the screen locked, and automatic playback of the next queued song while locked. The owner also confirmed that v0.3.0-native played a newly searched song on mobile data with USB unplugged and continued with the screen locked. v0.3.1 changes wording and disclaimer only; it uses the same playback engine. These v0.3.0 physical checks are not device tests of v0.3.1. The Mac and Termux are not playback dependencies.

## Build from source

Source is licensed under GPL-3.0-or-later; see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). The Android package includes the GPL-3.0 text at `app/src/main/assets/LICENSE-GPL-3.0.txt`. Upstream references: [NewPipe Extractor v0.26.5](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5) and [Media3 background playback](https://developer.android.com/media/media3/session/background-playback).

Requirements: Java 17, Android SDK Platform 36, Android Build Tools 35, Android Gradle Plugin 8.13.2, and Gradle 8.13 through the included wrapper. From this directory:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintRelease
```

For local signing, the build reads `../.local/android-signing.properties`:

```properties
storeFile=/absolute/path/to/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The properties file and keystore are private secrets. Never commit or publish them. Without the file, debug builds use Android's standard debug key and release builds are unsigned. Updates require the same signing key as the installed APK. Keep a protected backup of the release keystore and its passwords in the owner's secure credential storage, with a second encrypted backup in an access-controlled private location. Verify that the backup can be retrieved before relying on it. Do not put the key, passwords, or backup details in this repository, release assets, or the HTML guide.
