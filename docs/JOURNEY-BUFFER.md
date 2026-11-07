# Journey buffer — v0.7.0 test update

This test update improves listening through short signal gaps and reduces repeated start–stop playback. The rc2 build passed the physical Pixel check: 120 seconds of offline locked-screen progress, a natural transition to the next cached song, and automatic recovery of an uncached episode about 8.4 seconds after reconnecting. The automated check muted app audio; audible quality and a real Underground journey remain owner checks. The public website’s stable download remains unchanged.

## Install the update

Download **BlackYT-Android-v0.7.0-rc2.apk** from the [test release](https://github.com/ezzye/BlackYT/releases/tag/v0.7.0-rc2). Open it in Files/Downloads and install over your existing BlackYT. The package uses the existing signing identity and versionCode 71. Do not uninstall or clear app storage: your saved playlists and episodes are kept when Android installs a compatible update. If Android reports a signing conflict, stop and contact blackyt@streamit2.me. Developer options, USB debugging, Termux and a running Mac are not needed for ordinary installation or listening.

## Before going underground

1. While connected, search and build a queue. Start listening.
2. Open the top-right menu → **Journey buffer**. Leave preparation on **unmetered connections**, or explicitly choose **Wi-Fi and mobile data** if you want to spend mobile data preparing the queue.
3. Give the audio time to arrive. The dialog shows saved megabytes and the current playback buffer in seconds. A nonzero total does not mean every queued item is ready.
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

## Device evidence and rc2 repair

The first handset run found missing optional extractor format metadata caused preparation to skip caching. Rc2 uses hashed stable representation/revision identifiers and available language metadata, rejecting ambiguous identities. Signed URL tokens are excluded. Both songs were cached after repair. Two preliminary screen-off attempts were interrupted by lift-to-wake; the controlled passing run temporarily disabled that gesture and restored it afterward. Original Library and network settings were restored; the temporary test queue was cleared. See [device evidence](testing/pixel-journey-20261107.json) and [build checks](testing/journey-build-rc2.json). The full passing test took about four minutes. Restricted-bandwidth listening remains unverified.
