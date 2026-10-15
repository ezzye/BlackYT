# BlackYT FAQ

## Which devices can run BlackYT?

BlackYT is for Android only and requires Android 8.0 or newer. iPhone and iPad are not supported now. An iOS app is planned for later, with no release date. The owner physically tested v0.3.0 on a Pixel 10 Pro XL. v0.3.1 uses the same playback engine with updated wording; this does not establish support for every Android phone or a separate v0.3.1 device test.

## Do I need Developer options, USB debugging, or a computer?

No. A normal install from the public release needs only the Android phone and an internet connection. Developer options and USB debugging are for developers installing test builds from a computer. BlackYT does not need root access, bootloader unlocking, or a custom certificate.

## Why does Android ask to allow installation from Chrome or Files?

Android asks you to allow the specific app that opened the downloaded APK to install apps. On a Pixel, open **Settings → Apps → Special app access → Install unknown apps → Chrome → Allow from this source**. If you opened the APK from Files, choose Files instead. You can switch that permission off after installing. Other Android brands may use different labels. See [Google Pixel's installation help](https://support.google.com/pixelphone/answer/7391672).

If you see a Play Protect, “not verified,” developer-verification, work, or school restriction, stop and contact the maintainer or your administrator. Do not disable protection or try to bypass a restriction. See the [Android developer verification FAQ](https://developer.android.com/developer-verification/guides/faq?hl=en).

## How do I install it?

Use the public [v0.3.1 release page](https://github.com/ezzye/BlackYT/releases/tag/v0.3.1), download **BlackYT-Android-v0.3.1.apk** from Assets, then open it from Chrome Downloads or Files → Downloads. Choose the APK asset, not GitHub's source-code ZIP. The full steps are in [INSTALL.md](INSTALL.md).

## Why is the app called BlackYT when my old icon says BlackYT Phone?

**BlackYT** is the native Android app. **BlackYT Phone** is an older home-screen PWA shortcut that needs Termux and Chrome. The native app does not need either one. You can leave the old shortcut installed while trying the native app.

## How do Play and Add differ?

**Play** starts that result now. **Add** puts it at the end of the current queue. The queue plays in the order songs were added. If nothing is playing, Add fills the queue but does not start it; press the large Play button in the Now Playing controls to start the queued songs. BlackYT does not automatically choose more songs.

## What do the filters do?

**Music results only** is a best-effort filter based on labels supplied by YT. Turn it off if a wanted result is missing. Include terms keep results containing any listed word; exclude terms remove results containing any listed word. Exclude takes priority when a word appears in both fields. Matching uses available title and channel text.

## Does BlackYT download songs or need an account?

No. It streams audio to the phone and needs an internet connection. It has no offline library, and there is no BlackYT sign-in or hosting account. Mobile-data and battery use depend on how long you listen and on your device and network plan.

## What permissions does BlackYT need?

It needs internet access to search and stream. While music plays, Android can show media controls; their visibility depends on your notification and lock-screen settings. It does not need microphone, contacts, or photo access for normal listening.

## Why does playback stop after I close the app?

The current queue is kept in the running app session. The queue is not saved permanently and can be lost if Android stops BlackYT, you force-stop it, or the phone restarts. Pressing Home or locking the screen while music plays lets playback continue. Search filter settings persist. To listen in the background, leave playback running and use Android's playback notification or lock-screen controls.

## Can I install an update over my existing copy?

Yes, install the new APK over the installed BlackYT app. Updates must have the same release signing key. Do not uninstall first if Android reports a signature conflict; contact the maintainer, because uninstalling may remove app data and would make this a fresh installation rather than an in-place update.

## What if a YT link fails?

Check your connection and tap **Retry current song** to request a fresh stream link. Some links may be unavailable, restricted, or blocked, and YT can change its service. See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for specific messages and next steps.
