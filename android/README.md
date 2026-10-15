# BlackYT for Android

BlackYT is the primary Android app for finding and playing YouTube music links. It extracts stream URLs on the phone with NewPipe Extractor and plays them through AndroidX Media3. The app label is **BlackYT**. Normal playback needs no Termux, Chrome, local server, Mac, account, or cloud relay.

## Private release and installation

The private native preview is [v0.3.0-native](https://github.com/ezzye/BlackYT/releases/tag/v0.3.0-native), containing **BlackYT-Android-v0.3.0.apk** (about 5.5 MB). Download the APK and offline HTML guide from that release:

1. On the Android phone, sign in to GitHub using an account with access to the private `ezzye/BlackYT` repository.
2. Open the v0.3.0-native release page and download **BlackYT-Android-v0.3.0.apk**.
3. Open the downloaded APK. If Android asks, allow the browser or file manager to install apps, return to the installer, and tap **Install**.
4. Open **BlackYT** from the app launcher.

The old Chrome PWA home-screen app was labelled **BlackYT Phone** and belongs to the separate legacy v0.2.0 Termux setup. The native APK does not need that installation.

## Search, filter, and listen

1. Tap the search box. Enter a song or artist, or paste a YouTube video URL, then tap **Search**. There is no Android Open/Share link intent; paste links into the search box.
2. **Music results only** starts checked. Uncheck it to see results that the music filter removed. The check is a best-effort metadata filter, not a reliable guarantee of music classification.
3. Optionally enter comma-separated words in **Include any words** and **Exclude any words**. A result is kept when it matches any included word (or the include field is empty), and removed when it matches any excluded word. Matching uses available title/channel text.
4. Tap **Play** on a result to start it immediately. Tap **Add** to append it to the FIFO queue, after items already lined up.
5. Use the player controls for Previous, Stop, Play/Pause, Next, and seeking. **Retry current song** re-extracts a fresh stream link, which can help after a network change or an expired link.

Android's notification and lock-screen controls use the native media session. Queue contents last only for the current app session; search filter settings persist across launches. There is no sign-in and no offline download. Audio goes directly from the phone to YouTube's Googlevideo stream host, without a BlackYT cloud relay or separate advertising layer. YouTube may change its service and disrupt search or playback.

## Recovery

- **No search results:** check the phone's internet connection, try the song title or artist, uncheck **Music results only**, and clear or broaden include/exclude words.
- **Playback stops or reports a stream error:** check the connection, then tap **Retry current song**. This requests a fresh stream URL from the phone.
- **A YouTube link does not play:** verify it opens in YouTube, then paste its full URL into BlackYT's search box. Some videos may be unavailable to the extractor, including age-restricted or otherwise restricted videos; successful playback is not guaranteed.
- **App cannot update over an installed copy:** updates must be signed with the same release key. A differently signed APK cannot replace the existing installation in place. Do not uninstall if you need app state before you have checked your backup plan.
- **Release link shows 404:** this is a private repository. Sign in to an authorized GitHub account. A shared HTML guide does not grant release access.

## Current validation

The signed native release builds and is installed on the Pixel 10 Pro XL; its installed APK hash matches the release file. Five unit checks pass, and release lint reports zero errors with routine warnings. Direct native Googlevideo audio returned HTTP 200. The owner confirmed audible native playback, continued playback with the screen locked, and automatic playback of the next queued song while locked. Those owner checks used the pre-release build; the final signed release has the same playback engine. A fresh-song check on mobile data with USB disconnected is pending owner confirmation.

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
