# BlackYT

BlackYT is a native Android app for finding and playing YT music links. The phone extracts a stream URL directly and plays audio through Android's native media service. Normal use needs one app: no Termux, Chrome, local server, Mac, account, or cloud relay.

## Install the native Android app

The public native release is [v0.3.1](https://github.com/ezzye/BlackYT/releases/tag/v0.3.1), with the asset **BlackYT-Android-v0.3.1.apk**. It contains wording and disclaimer updates on the v0.3.0 playback engine; playback behavior has not changed. Download the APK and standalone HTML guide from the release page. No GitHub sign-in is needed for a public release.

1. On the Android phone, open the public release link above.
2. Download **BlackYT-Android-v0.3.1.apk** and open the completed download.
3. If Android asks, allow the browser or file manager to install apps, return to the installer, and tap **Install**.
4. Open **BlackYT** from the launcher. The older PWA home-screen app was labelled **BlackYT Phone**.

See the [native Android guide](android/docs/native-android.html) for step-by-step installation, use, and recovery details, and [android/README.md](android/README.md) for source builds and signing. The Android component is GPL-3.0-or-later; see [android/LICENSE](android/LICENSE) and [android/NOTICE](android/NOTICE).

## Daily use

Use the search box to enter a song, artist, or paste a YT video link, then tap **Search**. The **Music results only** checkbox is enabled by default. If it hides something you want, uncheck it. Optional comma-separated **Include any words** and **Exclude any words** fields narrow results by available title/channel text. Include keeps a match if any entered word appears; Exclude removes a match if any entered word appears.

Tap **Play** on a result to start it immediately. Tap **Add** to place it at the end of the first-in, first-out queue. The queue exists only for the current app session. Player controls include Previous, Stop, Play/Pause, Next, seeking, and Retry current song. Android's notification and lock-screen media controls use the same native player session. If a stream fails after a network change, reconnect and tap **Retry current song** to extract a fresh link.

Music classification depends on YT metadata and is not guaranteed. BlackYT has no offline downloads or sign-in. It plays audio directly from YT's Googlevideo stream host; no BlackYT cloud relay is involved. YT may change its service and break extraction or playback. BlackYT does not add a separate advertising layer.

## About BlackYT

BlackYT is an independent educational project about making music easier to discover and enjoy. Its creator uses BlackYT to listen to songs they wrote and perform. The project aims to help students explore and learn about music regardless of their socioeconomic circumstances, with fewer advertising interruptions and low running costs.

## Disclaimer

BlackYT is an independent, unofficial project for educational use. Use it lawfully, respect copyright, licences, creators' rights, and applicable platform terms. Educational status is not permission to break the law or bypass rights or restrictions. See [DISCLAIMER.md](DISCLAIMER.md) for the full notice.

## Current validation

The v0.3.0-native signed APK was installed on the Pixel 10 Pro XL and its installed APK hash matched the release file. Five unit checks passed, release lint reported zero errors with routine warnings, and direct native Googlevideo audio returned HTTP 200. The owner confirmed audible playback, continued playback with the screen locked, and automatic playback of the next queued song while locked. The owner also confirmed the signed v0.3.0-native release played a newly searched song on mobile data with USB unplugged and continued with the screen locked. v0.3.1 changes the app wording and disclaimer only; it uses the same playback engine, and those v0.3.0 physical checks are not v0.3.1 device tests. The Mac and Termux are not playback dependencies.

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

The release uses a signing key outside the repository. Keep a protected backup of that key; updates must use the same key. Never publish the key or commit it to Git. Future product work is tracked in [ROADMAP.md](ROADMAP.md).
