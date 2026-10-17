# Ads, privacy and playback preparation

BlackYT's native Android app plays content audio through Android Media3, using stream addresses extracted on the device. It does not run the YT embedded advertising player, insert its own advertising placements, or include an advertising or analytics SDK in the inspected source and release dependency metadata.

This is not a guarantee that every stream will always be free of advertising. Creator-recorded sponsorships and promotional messages are part of the content and can remain. There is no SponsorBlock integration. Upstream delivery can change.

## What leaves the phone

Search phrases, video/creator lookups, artwork requests and audio requests go directly to YT/Google services. Those services can receive the device's public IP address, queries, requested video/channel identifiers and request metadata. Public thumbnails use the allowed YT/Google image CDN families; audio streams use HTTPS Googlevideo hosts. BlackYT is not an anonymity service or privacy relay. No BlackYT account or cloud proxy is required.

## What BlackYT stores

Saved episodes and creators, resume progress, recent searches, listening history, filters and Explore topics are stored in app-private local preferences. There is no app-operated cloud sync or analytics upload. The application sets `allowBackup=false` and disallows cleartext traffic. These controls do not promise protection against every OS backup/transfer behaviour, compromised device, system service, or forensic access. Uninstalling or clearing app storage removes local saves.

Debug builds may write bounded local diagnostic files. Release diagnostic-file writing is disabled. In v0.6.0, playback-state logging that includes video identifiers is debug-only. No signed stream URLs should be printed or submitted in bug reports.

## v0.6.0: playlists, suggestions and preparation

Named playlists and their saved video metadata are stored locally on the device. **Play playlist** explicitly replaces the current queue; **Add all** appends playlist items and preserves current playback. Library and playlist data do not sync to an account or cloud. Suggestions are opt-in: **Find more like these** can use up to two creator pages and two searches and returns at most 12 items. The label **New to this playlist** is not a verified upload date. BlackYT never adds suggestions automatically.

Playback preparation targets the next two queued items and the first visible item. When stable format identity exists, it may retain a per-item prefix of at most 384 KiB in an on-device least-recently-used cache capped at 32 MiB. It prepares a bounded opening chunk, rather than providing an offline download library; very short clips can fit entirely within that cap. Preparation is off, unmetered by default, or allowed on Wi-Fi and mobile data; no more than eight items per minute are prepared. Turning it off cancels pending work and clears the cache asynchronously. Unknown identity or cache failure falls back to direct playback. Streaming remains directly from the device to YT. Instant starts and Spotify feature parity are not promised.

Existing search filters are still checked on playback, and original-track handling remains. A changed source format can require tapping **Retry current song**. Preparation issues should not be described as offline playback or guaranteed speed improvements.

## Validation status

The signed v0.6.0 versionCode 60 APK was installed and checked on a USB-connected Pixel with app data preserved; the owner confirmed audible playback. The live check covered search, playlist creation, optional suggestions, queue additions and appending, and full-player next/previous gestures. Four instrumentation checks passed, including natural progression after a 247.873-second sample while locked with the screen off over Wi-Fi. All 41 JVM unit tests pass and release lint has zero errors and 19 warnings. No mobile-data validation is claimed. This is a bounded device check, not a claim that every source, network, or Android device works.

Contact: blackyt@streamit2.me · Unsigned Media Ltd.
