# BlackYT for Android

**Android only (Android 8.0 or newer). An iOS app for iPhone and iPad is planned for later; no release date yet.**

BlackYT is an Android-only app for finding and playing YT music links. It extracts stream URLs on the phone with NewPipe Extractor and plays them through AndroidX Media3. It requires Android 8.0 or newer. iPhone and iPad support is planned for later with no release date. The app label is **BlackYT**; normal playback needs no Termux, Chrome, local server, Mac, account, or cloud relay.

## Public release

The public native release is [v0.4.2](https://github.com/ezzye/BlackYT/releases/tag/v0.4.2), with **BlackYT-Android-v0.4.2.apk** (versionCode 42). For beginner installation steps, questions, and recovery help, see the [Android install guide](../docs/INSTALL.md), [FAQ](../docs/FAQ.md), and [troubleshooting guide](../docs/TROUBLESHOOTING.md). The [standalone HTML guide](docs/native-android.html) is convenient for offline reading and sharing.

The old Chrome PWA home-screen app was labelled **BlackYT Phone** and belongs to the separate legacy v0.2.0 Termux setup. The native APK does not need that installation.

## Home, search, and listen

1. Bottom navigation switches between **Home**, **Search**, and **Queue**. Home shows a small mascot, **Jump back in** with up to 12 recently played tracks, and six colorful Explore tiles that are example search shortcuts. Search for any genre, artist, topic, or title. Podcast episodes available through YT can be found using **Search → All videos** or a pasted YT link, then played in the regular player and queue; podcast directories, RSS feeds, and subscriptions are not supported.
2. Recent-play history stores public track metadata and original artwork references locally; **Home → More (⋮) → Clear listening history** removes it. The last eight search phrases are stored locally and can also be cleared. BlackYT does not create fake playlists or automatically fetch recommendations.
3. Search with the compact search field, its action button, or the keyboard Search/Enter action. Paste a YT link or enter a phrase. Choose **Music** or **All videos**; open **Filters** for optional include-any and exclude-any terms. Music classification depends on YT metadata and is not guaranteed.
4. Use **Load more** for further pages, up to 200 matches. Tap a result to play it immediately. **Add** appends it to the FIFO queue. Queue items can be removed.
5. A miniplayer appears above the bottom navigation only when a track is current. Tap it to open full **Now Playing**. Back returns to the previous screen. The full player has seeking, Previous, Play/Pause, Next, Queue, and **More**; More includes Stop and Retry current song. Track starts and button presses have immediate visual feedback. Swipe artwork or the miniplayer left for next and right for previous; short vertical drags do not change tracks. The queue does not wrap at either end.

The queue lasts only for the current app session. Audio streams directly over HTTPS from the phone to YT's Googlevideo stream host, with no BlackYT cloud relay. There is no sign-in or offline download.

## Recovery

- **No search results:** check the phone's internet connection, try the song title or artist, choose **All videos**, and clear or broaden include/exclude words.
- **Playback stops or reports a stream error:** check the connection, then tap **Retry current song**. This requests a fresh stream URL from the phone.
- **A YT link does not play:** verify it opens in the YT app or site, then paste its full URL into BlackYT's search box. Some videos may be unavailable to the extractor, including age-restricted or otherwise restricted videos; successful playback is not guaranteed.
- **App cannot update over an installed copy:** updates must be signed with the same release key. A differently signed APK cannot replace the existing installation in place. Do not uninstall if you need app state before you have checked your backup plan.
- **Release link shows 404:** check the release URL and try again later if publication is still pending. The source repository is public; no GitHub sign-in is required for published release assets.

## Current validation

Local build checks passed nine release unit tests and lint with zero errors and 17 warnings. Pixel 10 Pro XL checks passed for live search, links, Music/All videos, filters, pagination, artwork, navigation, queue controls, header spacing, native press feedback and artwork/miniplayer swipes. Vertical and short drags did not skip; queue boundaries did not wrap; taps worked after swiping. A silent track played for its full 208.564 seconds and advanced naturally. The complete 120-second sample stayed locked with the display off, and both conditions still held when the next track began. Playback used mobile data while USB remained connected; unplugged operation was not retested for v0.4.2. The installed APK hash matched the tested build. Owner audible and visual acceptance of v0.4.2 remains pending. Other phones are unverified. See [verification details](docs/verification-v0.4.2.json).

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

## Optional connected-device checks

The nine local unit tests run without a phone. The opt-in instrumentation tests use real public YT search and thumbnail requests and stream audio, so a connected Android phone and internet/data allowance are needed:

```sh
./gradlew connectedDebugAndroidTest
```

These checks replace and clear BlackYT's in-memory queue. The playback check mutes only BlackYT, streams for two minutes, checks automatic movement to the next queued song, then stops and clears playback before restoring app volume. It does not change global media volume or bypass the screen lock. The UI check also mutes BlackYT before invoking its controls and renders only the app's content, including when the phone is already locked. It verifies Home/Search/Queue navigation, search submission, result artwork, Load more, the full player and registered modern Back callback, listening history, Music learning search, Add/Remove, and Clear results. Real search results can change; unavailable upstream content or network restrictions can fail these checks.

For a screen-off check, lock the phone normally before running the playback test and add the runner argument `expect_screen_off=true`. This requires the test to observe both the existing lock and the display off. A silent automated pass does not establish audible quality, everyday touch usability, or support for every phone.
