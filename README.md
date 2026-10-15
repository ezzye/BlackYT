# BlackYT

BlackYT is a native Android app for finding and playing YouTube music links. The phone extracts a stream URL directly and plays audio through Android's native media service. Normal use needs one app: no Termux, Chrome, local server, Mac, account, or cloud relay.

## Install the native Android app

The private native preview is [v0.3.0-native](https://github.com/ezzye/BlackYT/releases/tag/v0.3.0-native), with the asset **BlackYT-Android-v0.3.0.apk** (about 5.5 MB). Download the APK and the standalone HTML guide from that private release. This first native release is a preview; its validation and current limits are below.

1. On the Android phone, sign in to GitHub with an account that can access this private repository and open the release link above.
2. Download **BlackYT-Android-v0.3.0.apk** and open the completed download.
3. If Android asks, allow the browser or file manager to install apps, return to the installer, and tap **Install**.
4. Open **BlackYT** from the launcher. The older PWA home-screen app was labelled **BlackYT Phone**.

See the [native Android guide](android/docs/native-android.html) for step-by-step installation, use, and recovery details, and [android/README.md](android/README.md) for source builds and signing. The Android component is GPL-3.0-or-later; see [android/LICENSE](android/LICENSE) and [android/NOTICE](android/NOTICE).

## Daily use

Use the search box to enter a song, artist, or paste a YouTube video link, then tap **Search**. The **Music results only** checkbox is enabled by default. If it hides something you want, uncheck it. Optional comma-separated **Include any words** and **Exclude any words** fields narrow results by available title/channel text. Include keeps a match if any entered word appears; Exclude removes a match if any entered word appears.

Tap **Play** on a result to start it immediately. Tap **Add** to place it at the end of the first-in, first-out queue. The queue exists only for the current app session. Player controls include Previous, Stop, Play/Pause, Next, seeking, and Retry current song. Android's notification and lock-screen media controls use the same native player session. If a stream fails after a network change, reconnect and tap **Retry current song** to extract a fresh link.

Music classification depends on YouTube metadata and is not guaranteed. BlackYT has no offline downloads or sign-in. It plays audio directly from YouTube's Googlevideo stream host; no BlackYT cloud relay is involved. YouTube may change its service and break extraction or playback. BlackYT does not add a separate advertising layer.

## Current validation

The signed native release builds and is installed on the Pixel 10 Pro XL; its installed APK hash matches the release file. Five unit checks pass, and release lint reports zero errors with routine warnings. Direct native Googlevideo audio returned HTTP 200. The owner confirmed audible native playback, continued playback with the screen locked, and automatic playback of the next queued song while locked. Those owner checks used the pre-release build; the final signed release has the same playback engine. The owner then confirmed that the final signed release plays a newly searched song on mobile data with USB unplugged and continues with the screen locked. The Mac and Termux are not playback dependencies.

## Legacy alternative: Termux + Chrome PWA (v0.2.0)

The earlier [v0.2.0 PWA release](https://github.com/ezzye/BlackYT/releases/tag/v0.2.0) is a separate two-part install: a Termux helper and a Chrome home-screen app labelled **BlackYT Phone**. It is not the native APK. Its setup and evidence are documented in the [legacy installation guide](docs/INSTALL.md). Choose it only if you need that older route; native Android is the primary install.

## Development

The Python web app remains available for local development. With Python 3:

```sh
./setup.sh
./start.sh
```

Then open <http://localhost:8765>. The [architecture notes](docs/ARCHITECTURE.md) describe the native app and legacy PWA data paths.

## Maintainer checks

```sh
.venv/bin/python -m unittest -v test_server test_phone_audio
.venv/bin/python phone/test_termux.py
cd android && ./gradlew assembleDebug testDebugUnitTest lintRelease
```

The private release uses a signing key outside the repository. Keep a protected backup of that key; updates must use the same key. Never publish the key or commit it to Git.
