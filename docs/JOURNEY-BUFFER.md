# Journey buffer — v0.7.0 test update

This test update improves listening through short signal gaps and reduces repeated start–stop playback. The preceding rc2 build passed the physical Pixel check: 120 seconds of offline locked-screen progress, a natural transition to the next cached song, and automatic recovery of an uncached episode about 8.4 seconds after reconnecting. The automated check muted app audio; audible quality and a real Underground journey remain owner checks. The main website download now serves the tested v0.7.0-rc3 build.

## Install the update

Download **BlackYT-Android-v0.7.0-rc3.apk** from the [test release](https://github.com/ezzye/BlackYT/releases/tag/v0.7.0-rc3). Open it in Files/Downloads and install over your existing BlackYT. The package uses the existing signing identity and versionCode 72. Do not uninstall or clear app storage: your saved playlists and episodes are kept when Android installs a compatible update. If Android reports a signing conflict, stop and contact blackyt@streamit2.me. Developer options, USB debugging, Termux and a running Mac are not needed for ordinary installation or listening.

## Before going underground

1. While connected, search and build a queue. Start listening.
2. Open the top-right menu → **Journey buffer**. Leave preparation on **unmetered connections**, or explicitly choose **Wi-Fi and mobile data** if you want to spend mobile data preparing the queue.
3. Give the audio time to arrive. Look for a small green check beside each item: **Ready offline**. A half-filled circle means **Part saved**, an empty circle means **Needs connection**, and dots mean **Checking saved audio**. Long-press the icon for the written status. The dialog still shows total saved megabytes and current buffered seconds.
4. Keep BlackYT playing and lock the screen normally. It uses the audio already received when the signal drops.

Played audio is cached without a second download. The player aims to buffer up to 10 minutes of the current item. After current playback has a useful reserve, the next two queued items may each prepare up to about 20 minutes, capped at 24 MiB per item. Search-result preparation stays limited to 384 KiB. Duration depends on bitrate, network availability and the source format; seeking may need ranges that have not been saved. Long outages and new searches still need a connection.

## Limits and controls

The private on-phone disk cache is limited to **256 MiB**. Each saved stream expires **six hours after it is first cached**; playing it again does not extend that lifetime. Expired bytes are rejected on access and removed while BlackYT is active or on its next start. Android may remove the cache earlier. This is a temporary journey buffer, with no export or permanent-download library. Signed stream addresses are not saved in its descriptor file.

**Clear saved audio** removes cached audio while leaving playlists and saved episode details. **Off** stops disk caching and speculative preparation, clears saved audio, and leaves the ordinary in-memory playback buffer. Preparing upcoming items downloads extra data; the unmetered default does not guarantee that a connection is cost-free.

## Smoother playback in weak signal

After an underrun, BlackYT first gathers roughly 15 seconds of playable audio. Repeated nearby underruns raise this to 30, then 60 seconds. A longer quiet wait can therefore replace many short bursts. It reduces preparation traffic whenever current playback is short of reserve. Network interruptions use delayed retries and retry again when a validated connection returns, preserving the queue and position. Automatic recovery is bounded to 30 minutes after a playback error; after that, use Track options → Retry. Denied/unavailable content and an incompatible audio-format change are not treated as temporary network loss.

No buffer can play audio that has not arrived. If average bandwidth stays below the stream bitrate, longer pauses can still be needed.

## Repeat the listening check

Queue three short tracks on Wi-Fi and let preparation run. Turn off both Wi-Fi and mobile data, keeping any headphone connection enabled; lock the screen and listen through the current item and a prepared next item. Restore data and check automatic recovery if the buffer ran out. Repeat with a longer episode and a seek beyond the prepared part. Check that Clear saved audio and Off do not lose your queue or Library. Please report whether you heard gaps, which items were queued and how many seconds the dialog showed before disconnecting.

Audio remains direct from YT/Google to the phone; this change adds no media proxy, account, analytics SDK or hosted streaming bill.

## Earlier device evidence and rc2 repair

The first handset run found missing optional extractor format metadata caused preparation to skip caching. Rc2 uses hashed stable representation/revision identifiers and available language metadata, rejecting ambiguous identities. Signed URL tokens are excluded. Both songs were cached after repair. Two preliminary screen-off attempts were interrupted by lift-to-wake; the controlled passing run temporarily disabled that gesture and restored it afterward. Original Library and network settings were restored; the temporary test queue was cleared. See [device evidence](testing/pixel-journey-20261107.json) and [build checks](testing/journey-build-rc2.json). The full passing test took about four minutes. Restricted-bandwidth listening remains unverified.

## Per-item offline icons — rc3

The compact status icon sits beside item details in Search, creator episodes, the queue, recent listening, saved episodes and playlist entries. Green **✓ Ready offline** means every byte of that selected audio file is currently saved, its total size is known, and its six-hour cache entry has not expired. **◐ Part saved** means some audio is saved, with a percentage in the long-press/accessibility explanation when the total size is known. **○ Needs connection** means the app cannot currently establish saved playable audio for that item; **…** means it is checking. Icons refresh while visible, roughly every two seconds and when you return to the app. Status inspection does not resolve or download new streams.

Long-press the tiny icon for its full status. Screen-reader descriptions include the status and, when ready, the expiry time. The full player keeps the written saved-audio status and separately shows the current **Buffered: … ahead** amount. RAM buffer alone never makes the whole item Ready offline. A saved playlist is still a list of items, and upcoming preparation is still limited to the next two queue entries. Expiry, eviction, Clear saved audio or Off can remove readiness. Android may clear cached audio at any time, so the icon describes the current state rather than a permanent download.

No connection is needed to inspect the audio already saved on this phone. Existing audio whose complete size is unknown stays Part saved until actual preparation/resolution supplies trustworthy size information. The update preserves the original cache expiry; inspecting an item does not extend it.

Rc3 also passed the Pixel offline/locked playback, natural queue transition and uncached recovery regression. See [rc3 device evidence](testing/pixel-offline-readiness-20261107.json) and [icon UI checks](testing/offline-icon-ui-20261107.json).
