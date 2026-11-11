# Automatic playback from a selected list

The local v0.7.0-rc5 candidate (versionCode 74) starts the displayed collection at the tapped item and continues in its displayed order. Playlist rows and their Play now action, loaded search results, creator episodes, related suggestions, saved episodes and recent history each supply their own queue. Rules remain attached to each entry; the selected saved episode retains its resume point and a shared search item retains its timestamp. Selecting another collection replaces the current queue. Explicit Add to queue still appends, and selecting an existing queue row still seeks within it. Playback ends at the collection's end; pagination remains explicit.

While offline, the foreground service chooses the next fully cached, unexpired item at or after an unavailable new selection. Missing or partial-cache entries are bypassed without deleting or reordering the queue. A playing item's usable buffer is allowed to continue; after an offline read failure the service can move to a complete cached successor. No complete successor means the existing connection-wait behavior is retained. Cache checks run off the player thread and verify the queue, selection, position, error, network and play intent before applying a seek. Pause and a clean explicit Stop cancel automatic continuation. The existing cache size, expiry and data-preparation preferences apply.

Verification on 11 November 2026: all 64 JVM/Robolectric tests passed; release lint reported zero errors and 26 warnings. AutoplayContextDeviceTest exercises actual rendered rows against the real MediaController for all six collection types, middle selection, per-item rules, old queue replacement, explicit append and saved resume. AutoContinuationDeviceTest uses locally seeded silent WAVs with the real service/cache/decoder: natural EOF without seeking, missing and partial offline gaps, uncached offline start, pause, stop, no-cache waiting and the 500-entry saved-episode limit. Its network settings and test fixtures are restored. PlaylistCacheReplayDeviceTest also passed six real music starts and a seek-assisted automatic next-track transition, preserving saved playlists. All playback was app-muted; audible quality and a longer owner listening session remain unverified. No public release or website deployment is part of this local candidate.

Run focused device checks with a USB-authorized Android device and matching signing configuration:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class uk.blackyt.app.AutoplayContextDeviceTest,uk.blackyt.app.AutoContinuationDeviceTest uk.blackyt.app.test/androidx.test.runner.AndroidJUnitRunner
```

The second test temporarily disables Wi-Fi and mobile data and switches the screen off, then restores their previous settings. The context test temporarily isolates and restores the owner's Library and UI preferences. Tests use small temporary cache fixtures and may exercise normal cache eviction. The existing real-music replay check requires the previously saved test playlist. The signed release APK is installed in place; do not uninstall to update.
