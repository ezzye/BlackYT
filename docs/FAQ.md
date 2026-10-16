# BlackYT FAQ

## Which devices can run BlackYT?

BlackYT is for Android only and requires Android 8.0 or newer. iPhone and iPad are not supported now. An iOS app is planned for later, with no release date. The v0.5.0 phone journey passed the share-preview, timestamp, creator, Library, Explore, and muted screen-off checks described below. Other devices and audible acceptance are not established.

## Do I need Developer options, USB debugging, or a computer?

No. A normal install from the public release needs only the Android phone and an internet connection. Developer options and USB debugging are for developers installing test builds from a computer. BlackYT does not need root access, bootloader unlocking, or a custom certificate.

## Why does Android ask to allow installation from Chrome or Files?

Android asks you to allow the specific app that opened the downloaded APK to install apps. On a Pixel, open **Settings → Apps → Special app access → Install unknown apps → Chrome → Allow from this source**. If you opened the APK from Files, choose Files instead. You can switch that permission off after installing. Other Android brands may use different labels. See [Google Pixel's installation help](https://support.google.com/pixelphone/answer/7391672).

If you see a Play Protect, “not verified,” developer-verification, work, or school restriction, stop and contact the maintainer or your administrator. Do not disable protection or try to bypass a restriction. See the [Android developer verification FAQ](https://developer.android.com/developer-verification/guides/faq?hl=en).

## How do I install it?

Open the [v0.5.0 release page](https://github.com/ezzye/BlackYT/releases/tag/v0.5.0) and download **BlackYT-Android-v0.5.0.apk** from Assets. See [INSTALL.md](INSTALL.md) for steps.

## Why is the app called BlackYT when my old icon says BlackYT Phone?

**BlackYT** is the native Android app. **BlackYT Phone** is an older home-screen PWA shortcut that needs Termux and Chrome. The native app does not need either one. You can leave the old shortcut installed while trying the native app.

## What is new in v0.4.2?

The v0.4.2 release adds a clearer bird-and-headphones launcher icon, including an Android themed-icon version, plus immediate visual feedback when starting tracks and pressing controls. Swipe left or right across artwork or the miniplayer to move through the queue; short vertical drags do not skip, and queue ends do not wrap. The app also has Home, Search, and Queue navigation; Home's Jump back in row and six example Explore search shortcuts; Music/All videos search, filters and pages; and a Now Playing screen with seeking, Previous, Play/Pause, Next, Queue, Stop and Retry.

## What is new in v0.5.0?

Share or open a supported YT video link in BlackYT to see a preview without autoplay. `youtu.be` links and timestamps are supported; for example, `t=205s` starts at 3:25 after you choose to play. Search offers **Music**, **All videos**, and **Creators**; open a creator to browse and page through its Videos, then save it to Library. Library saves episode metadata and listening progress and keeps saved creators on the phone. Episodes within 30 seconds of the end resume from the beginning. Explore topics can be added, edited, moved, removed, or restored to defaults. There is no account or cloud sync, offline audio, podcast directory, RSS reader, or subscription system.

## Can I listen to podcasts, and are the Explore tiles a limited catalog?

Explore topics are editable search shortcuts, not a fixed catalog. Add or edit a topic name and search phrase, move it, remove it, or restore the starter set. Spoken videos available through YT can be found with **Search → All videos** or **Creators** channel browsing. BlackYT does not have a podcast directory, RSS reader, or subscription system.

## How do Play and Add differ?

**Play** starts that result now. **Add** puts it at the end of the current queue. The queue plays in the order songs were added and does not wrap after the last or before the first item. If nothing is playing, Add fills the queue but does not start it; choose a result to start playback. Queue items can be removed and are played FIFO. BlackYT does not automatically choose songs or fetch recommendations.

## What do the filters do?

Choose **Music** or **All videos** in Search. Music classification depends on YT metadata and can be incomplete. Include terms keep results containing any listed word; exclude terms remove results containing any listed word. Exclude takes priority when a word appears in both fields. Matching uses available title and channel text.

## Does BlackYT download songs or need an account?

No. It streams audio to the phone and needs an internet connection. Library saves episode links, metadata, and resume progress, not audio files. Saved creators fetch videos when opened and also need internet. There is no BlackYT sign-in or hosting account. Mobile-data and battery use depend on how long you listen and on your device and network plan.

## What permissions does BlackYT need?

It needs internet access to search and stream. While music plays, Android can show media controls; their visibility depends on your notification and lock-screen settings. It does not need microphone, contacts, or photo access for normal listening.

## Why does playback stop after I close the app?

The queue is kept in the running app session and can be lost if Android stops BlackYT, you force-stop it, or the phone restarts. Saved episodes, progress, creators, and Explore edits persist locally in Library and Home. Recent search phrases (up to eight) and recently played public track metadata (up to 12) are stored separately; search history has Clear; listening history is cleared from **Home → More (⋮) → Clear listening history**. No automatic recommendation service fills Home.

## Can I install an update over my existing copy?

Yes, install the new APK over the installed BlackYT app. Updates must have the same release signing key. Do not uninstall first if Android reports a signature conflict; contact the maintainer, because uninstalling may remove app data and would make this a fresh installation rather than an in-place update.

## What if a YT link fails?

Check your connection and tap **Retry current song** to request a fresh stream link. Some links may be unavailable, restricted, or blocked, and YT can change its service. See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for specific messages and next steps.

## Can I listen to spoken YT videos like a podcast?

Yes. Choose **Search → All videos** for spoken videos or **Creators** to find a channel and browse its Videos. The phone journey passed for the [Wes Roth link supplied by the owner](https://www.youtube.com/watch?v=EmaXeEdlJkI&t=205s): BlackYT previewed the shared link without autoplay, then started at 3:25 after selection. The episode’s saved resume position persisted after the Library activity was recreated. Individual videos depend on YT availability. BlackYT does not provide a podcast directory, RSS reader, or podcast subscriptions.


## What has been checked on a phone in v0.5.0?

The exercised journey passed for shared-link preview and timestamped playback, Library resume after activity recreation, Wes Roth creator search and Videos paging, save/reopen creator fetch, Explore add/edit and All videos search, and 35 seconds of muted screen-off playback with the activity finished. See [verification-v0.5.0.json](../android/docs/verification-v0.5.0.json) for final automated results. Other phones and audible acceptance are not established by this check.

## Does a 403 error mean a geographic block?

A 403 is an HTTP refusal, and does not identify its cause. In the Pixel checks an earlier music-stream 403 was followed by successful fresh requests and playback of that same video. A geographic block was not established. Temporary stream addresses and differences in client requests can also matter. BlackYT automatically requests one fresh stream address after a 403. If it still fails, try **Retry current song**, or choose another available upload. Comparing the same video in the official YT app on the same phone and network can help identify a content restriction. Respect any country or rights restriction shown there; BlackYT does not promise access to every result.

See [YT region-availability help](https://support.google.com/youtube/answer/92571?hl=en) and [Android's HTTP playback error reference](https://developer.android.com/reference/androidx/media3/common/PlaybackException#ERROR_CODE_IO_BAD_HTTP_STATUS).

Final v0.5.0 checks: **20 release unit tests and 14 phone instrumentation tests passed**; release lint had zero errors and 19 warnings. A complete 208.564-second song advanced naturally to the next queued track on mobile data. The full two-minute sample stayed locked/display asleep, and both conditions held at the next track. USB remained connected; playback was app-muted. See the versioned verification JSON for evidence and limits.
