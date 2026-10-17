# Ads, privacy and playback preparation

BlackYT's native Android app plays content audio through Android Media3, using stream addresses extracted on the device. It does not run the YT embedded advertising player, insert its own advertising placements, or include an advertising or analytics SDK in the inspected source and release dependency metadata.

This is not a guarantee that every stream will always be free of advertising. Creator-recorded sponsorships and promotional messages are part of the content and can remain. There is no SponsorBlock integration. Upstream delivery can change.

## What leaves the phone

Search phrases, video/creator lookups, artwork requests and audio requests go directly to YT/Google services. Those services can receive the device's public IP address, queries, requested video/channel identifiers and request metadata. Public thumbnails use the allowed YT/Google image CDN families; audio streams use HTTPS Googlevideo hosts. BlackYT is not an anonymity service or privacy relay. No BlackYT account or cloud proxy is required.

## What BlackYT stores

Saved episodes and creators, resume progress, recent searches, listening history, filters and Explore topics are stored in app-private local preferences. There is no app-operated cloud sync or analytics upload. The application sets `allowBackup=false` and disallows cleartext traffic. These controls do not promise protection against every OS backup/transfer behaviour, compromised device, system service, or forensic access. Uninstalling or clearing app storage removes local saves.

Debug builds may write bounded local diagnostic files. Release diagnostic-file writing is disabled. In the v0.5.2 candidate, playback-state logging that includes video identifiers is debug-only. No signed stream URLs should be printed or submitted in bug reports.

## v0.5.2 candidate: prepare likely next items

In **More options → Playback preparation**, choose **Off**, **Unmetered connections (default)**, or **Wi-Fi and mobile data**. Android's network metering flag is respected; a metered Wi-Fi network is treated as metered. This prepares stream addresses, not audio downloads.

The next two queued items take priority, followed by up to three currently visible results/history/Library entries. At most five candidates are considered at once, one speculative task runs at a time, and at most eight item-preparation attempts start in a rolling minute. Pending visible candidates are replaced as the screen changes and cleared when the activity pauses. New speculation pauses while playback buffers. One request already running may finish after a screen change or switching Off.

Resolved metadata is kept in an in-memory cache with at most 64 entries and a five-minute freshness window. Concurrent requests for the same item share a load. Every playback still rechecks its filters; a refused HTTP 403 stream can be refreshed once. Preparation makes extra YT requests before a tap; use Off if you prefer requests only when needed. Connection setup, audio buffering, unavailable content and network changes can still cause delays. Instant starts and offline playback are not promised.

## Evidence and limits (17 October 2026)

The candidate passed 33 debug unit tests, including seven cache/concurrency tests, and release APK compilation. Phone instrumentation tests compile. Release lint has zero errors and 19 warnings. This pass reviewed source, release dependency metadata and the merged release manifest; it did not capture runtime network traffic or test the new candidate on a physical Pixel. The previous v0.5.1 phone results do not validate the new preparation feature. Keep public speed claims conditional until measured on a phone.

Contact: blackyt@streamit2.me · Unsigned Media Ltd.
