# BlackYT architecture

BlackYT now has a self-contained native Android app as its primary phone route. The v0.2.0 Termux and Chrome PWA remains available as a legacy alternative. These are separate implementations and their device results must be reported separately.

## Native Android app (primary)

```mermaid
flowchart LR
  UI[BlackYT Android UI] -->|search| NP[NewPipe Extractor on phone]
  UI -->|queue YT ID and filters| S[Media3 PlaybackService]
  S -->|resolve when playback opens| NP
  NP -->|HTTPS metadata requests| YT[YT]
  NP -->|fresh Googlevideo audio URL| S
  S -->|direct HTTPS audio| GV[Googlevideo stream]
  S -->|MediaSession| OS[Android notification and lock screen]
```

The Java app uses NewPipe Extractor `v0.26.5` for on-device search, filtering, creator lookup, channel video pages, and stream extraction. Media3 `1.9.3` plays the selected progressive audio URL in an Android foreground media service with a native media session. Audio requests go directly from the phone to YT's Googlevideo host; BlackYT has no cloud relay or separate advertising layer. There is no sign-in or offline audio download. YT can change its service and break search, extraction, or playback.

Search offers **Music**, **All videos**, and **Creators**. Creator results open channel video pages with paging; users can save a creator. Music classification uses available YT metadata and is not guaranteed. Include words match if any supplied word occurs in the searchable title/channel text; exclude words remove a result when any supplied word occurs. **Play** starts a result; **Add** appends it to the FIFO queue. The queue remains in app memory for the current session. Android Share and Open intents accept supported YT links, including shared short links and start timestamps; an incoming link is previewed before the user chooses to play it. A local Library stores saved episode metadata and resume position, and saved creators. Episode progress within 30 seconds of the end restarts from the beginning. Saved creators fetch their latest videos when opened, so an internet connection is needed. Explore search tiles can be added, edited, moved, removed, or restored to defaults. Library and Explore data stay on the phone; they are not account-synced, and the Library contains no offline audio.

Android notification and lock-screen controls share the Media3 session. Code and build configuration for the single APK live in `android/`; the app label is **BlackYT**. The retired PWA launcher label was **BlackYT Phone**. The current native release is v0.5.0 (versionCode 50). The v0.5.0 phone journey passed Share/Open preview and timestamp handling, creator browsing and saving, Library resume, Explore editing, and screen-off playback with the activity finished. See the [v0.5.0 verification record](../android/docs/verification-v0.5.0.json) for final automated results. Screenshots may still depict v0.4.2 until refreshed.

## Native build and evidence

Twenty debug unit tests passed. The phone journey passed a shared `youtu.be` link with `t=205s`: BlackYT previewed without autoplay and started at 205 seconds after selection; Library retained the resume point after activity recreation. A Wes Roth creator search opened and paged the Videos page, saved the creator, then reopened its latest videos from Library. Explore add/edit persisted and the edited topic launched an All videos search. With the activity finished, Media3 playback continued muted for 35 seconds with the screen locked and display off. These checks do not establish audible acceptance or results on other phones. See [verification-v0.5.0.json](../android/docs/verification-v0.5.0.json) for final automated and release-unit results.

The project uses Java 17, Android SDK 36, Build Tools 35, Android Gradle Plugin 8.13.2, and Gradle 8.13. Build and signing guidance is in [`android/README.md`](../android/README.md). The native code is GPL-3.0-or-later; see [`android/LICENSE`](../android/LICENSE) and [`android/NOTICE`](../android/NOTICE). The NewPipe Extractor upstream version is [v0.26.5](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.5). Android documents Media3 service-based background playback in its [background playback guide](https://developer.android.com/media/media3/session/background-playback).

## Legacy Termux + Chrome PWA (v0.2.0)

The legacy app uses a Python helper in Termux and a Chrome PWA. In phone mode, `server.py --phone --listen 127.0.0.1` resolves media and streams bounded HTTPS Googlevideo byte ranges through an opaque phone-loopback audio route. It has no cloud relay. The service worker caches only the app shell; audio is not available offline. The manual queue is page-session memory. The installed Chrome home-screen app was labelled **BlackYT Phone**.

The owner confirmed audible phone-local Prince playback, manual queue use, and two minutes of screen-locked playback for the legacy route. The locked queue transition was not separately confirmed. This is not evidence for the native APK. Legacy install and troubleshooting instructions remain in [`INSTALL.md`](INSTALL.md); the [v0.2.0 release](https://github.com/ezzye/BlackYT/releases/tag/v0.2.0) ships the portable PWA/Termux bundle, not a single-app APK.

The Mac development mode still serves the PWA at `http://localhost:8765`. It is a development path, not a playback dependency of the native APK.

### Temporary stream refusals

The native DataSource keeps the app-owned item and byte-range request while resolving a Googlevideo address. On HTTP 403 only, it closes the failed connection, requests a fresh stream address, and retries that open once. Repeated refusals and other HTTP statuses remain errors. This uses the same direct phone-to-YT route and does not add a relay or change content restrictions. Saved Library metadata never contains signed stream addresses.
