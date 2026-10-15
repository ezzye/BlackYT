# BlackYT troubleshooting

Start with the issue that best matches what you see. For safety warnings about the APK, follow the stop instructions below; do not turn off Android protections to make an installation proceed.

## Download and installation

| What you see | What to do |
| --- | --- |
| Release page or APK is missing | Open the public [v0.3.1 release page](https://github.com/ezzye/BlackYT/releases/tag/v0.3.1), scroll to **Assets**, and choose **BlackYT-Android-v0.3.1.apk**. The source-code ZIP is not the installer. If the release is temporarily unavailable, try again later or contact the maintainer. |
| I cannot find the downloaded APK | In Chrome, open **⋮ → Downloads**. Or open Android's **Files** app and choose **Downloads**. Look for the exact `.apk` filename. Wait for the download to finish before opening it. |
| Android says Chrome or Files cannot install apps from this source | Android controls this separately for each app that opens an APK. On a Pixel, go to **Settings → Apps → Special app access → Install unknown apps → Chrome → Allow from this source**. If Files opened the APK, choose Files instead. Return to Downloads and open the APK again. Labels differ on other phones. You can turn the permission off after installation. [Google Pixel help](https://support.google.com/pixelphone/answer/7391672). |
| Android shows a Play Protect, “not verified,” developer-verification, work, or school warning or block | Stop. Do not turn off Play Protect or bypass the warning. Contact the project maintainer, your organization administrator, or consult the [Android developer verification FAQ](https://developer.android.com/developer-verification/guides/faq?hl=en). |
| “App not installed,” “Problem parsing the package,” or the download will not open | Check that the phone runs Android 8.0 or newer, that the download completed, and that the name ends exactly in `.apk`. Download the APK asset again from the release page. If the same message remains, stop and contact the maintainer with the message and phone model. |
| “App not installed because the package conflicts with an existing package” or a signature error | Do not uninstall your current app or clear its data to experiment. Updates need the same signing key as the installed copy. Contact the maintainer and include the exact error. |
| Settings asks for Developer options or USB debugging | A normal phone install should not need either setting. Do not switch them on for this installation. If Android or a managed-device policy requires extra verification, stop and contact the maintainer or administrator. |

## Search and playback

| What you see | What to do |
| --- | --- |
| Search returns no results | Check internet access, try a shorter song or artist name, uncheck **Music results only**, and clear or broaden the include/exclude words. Include words allow a match if any term appears. Exclude words remove a result if any term appears. Exclude takes priority. |
| A result is missing | Turn off **Music results only**. YT labels are not always complete, and this filter cannot perfectly identify music. |
| A result does not start, or you see an HTTP 403/network error | Check that the phone is online and tap **Retry current song**. Retry asks for a fresh stream link. Try another result if it remains unavailable. Some videos may be restricted or blocked, and YT can change its service. |
| The song is playing but I hear nothing | Check the phone's media volume, mute setting, Bluetooth output, connected headphones, and the app's Play/Pause control. Check whether Android routed audio to another connected device. Then try another result. |
| Playback stops when the screen turns off | Reopen BlackYT and resume. Check only BlackYT's app-specific battery setting, if your phone provides one: **Settings → Apps → BlackYT → App battery usage**. On Pixels this may be shown as **Allow background usage**. Keep the general battery optimization setting at its recommended value; do not disable optimization for all apps. Android and manufacturers may still stop background work. [Google Pixel battery guidance](https://support.google.com/pixelphone/answer/6090599). |
| Next does nothing, or the queue is empty | **Add** places a song at the end of the queue; it does not start playback when nothing is already playing. Tap **Play** on a result first, then Add songs. Next moves to the next queued song. |
| My queue disappeared | The queue lasts only for the current app session. Force-stopping BlackYT, the phone restarting, or Android ending its process can clear it. Pressing Home or locking the screen while music plays is supported. Search filters are saved separately. |
| Mobile-data playback uses too much data | Streaming uses your phone's data plan. Prefer Wi-Fi when available or stop playback. BlackYT does not save music for offline listening. |

## App and device questions

- **I see BlackYT Phone and BlackYT.** BlackYT is the native app. BlackYT Phone is an older Chrome shortcut that relies on Termux; it is not needed for the native app.
- **I am using an iPhone or iPad.** BlackYT currently supports Android only. iOS is planned, with no release date.
- **The app asks for microphone, contacts, or photos.** The normal app does not need those permissions. Decline and contact the maintainer if such a prompt appears.
- **An update will not install over my current app.** Do not uninstall first if you want to retain app data. Send the exact message and whether your installed app is named BlackYT or BlackYT Phone to the maintainer.

If these steps do not help, [open a support issue](https://github.com/ezzye/BlackYT/issues/new) and include the phone model, Android version, exact error text, and which step you reached. Do not send passwords, signing keys, access tokens, or private account information.
