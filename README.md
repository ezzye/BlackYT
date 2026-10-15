# BlackYT

BlackYT is a small, locally run music-search and playback PWA. The current Android setup runs the app and its YouTube audio resolver on a Pixel phone with Termux. The browser connects to `http://127.0.0.1:8765`; Termux requests media directly from YouTube and serves audio to Chrome over phone loopback. Audio does not pass through a Mac, cloud server, or off-device relay. After installation, the Mac can be switched off.

The PWA searches public YouTube music results, applies include/exclude words, and lets you play a result now or add it to a manual queue. It includes next/previous, seeking, retry after a network change, browser media controls, and an installable home-screen shell. Search and audio need an internet connection; the offline shell does not make tracks available offline. This is an unofficial YouTube access route and can stop working if YouTube changes its service.

## Install on Android

**Phone-only:** sign into GitHub in Android Chrome, open [release v0.2.0](https://github.com/ezzye/BlackYT/releases/tag/v0.2.0), and download **BlackYT-Phone-v0.2.0.zip**. Follow the [detailed installation guide](docs/INSTALL.md#phone-only-release-installation) or download **BlackYT-Guide.html** from the release for an offline guide with copy buttons. Termux needs no GitHub token. A Mac is not required for this route.

**Alternative:** clone this private repository on your Mac, build the checksum-pinned phone bundle and transfer it once over home Wi-Fi. See [Mac-assisted installation](docs/INSTALL.md#mac-assisted-first-transfer). Daily use stays on the phone.

This is a private repository. A 404 usually means the browser is signed out or the account does not have access. Share the HTML guide as a file; that does not grant software access.

## Daily use

Open Termux and run `blackyt`. Keep that foreground session open while listening. Open the **BlackYT Phone** home-screen app in Chrome; if Android restarts Termux or stops it in the background, run `blackyt` again. Stop the helper by returning to Termux and pressing **Ctrl+C**.

Search for a track, then choose **Play** to start it immediately or **Add** to put it in the queue. The queue exists only in the current page session; include/exclude words and search mode are saved by that browser on that phone. See [docs/INSTALL.md](docs/INSTALL.md) for update, backup, recovery, and uninstall steps.

## What has been verified

On a physical Pixel 10 Pro XL, phone-local playback of Prince's “When Doves Cry” returned HTTP 206 audio/mp4 and advanced in the player; the owner confirmed audible playback. The owner also confirmed that adding and using the manual queue worked. Automated backend checks (22 tests) and mocked installer checks (install/rerun, checksum and unsafe-path rejection, launcher start, and port-collision handling) passed.

The owner also confirmed two minutes of playback with the screen locked on the phone-local version. A queue transition while locked and the exact mobile-data/USB-disconnected conditions were not separately restated. The new portable release ZIP is checked structurally and through mocked local-install tests; a clean installation from that exact ZIP has not yet been physically repeated. A mocked installer test is not a physical-device test. The release is not a single APK: Termux is the runtime and the home-screen PWA is installed separately in Chrome.

## Development

For local development on a computer with Python 3, create the environment and run the server:

```sh
./setup.sh
./start.sh
```

Then open <http://localhost:8765>. This is the computer-assisted development mode, not the phone-only release setup. See the [phone installation guide](docs/INSTALL.md) for the Android architecture and [official Termux installation guidance](https://github.com/termux/termux-app#installation).

## Architecture and a single Android app

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for the current data path, security boundaries and staged single-APK plan. A real one-app version should own extraction and playback in an Android Media3 service. Merely wrapping the PWA would still leave the Termux helper separate. No APK is included in this release.

## Maintainer checks and releases

```sh
.venv/bin/python -m unittest -v test_server test_phone_audio
.venv/bin/python phone/test_termux.py
.venv/bin/python phone/package_release.py --version v0.2.0 --output .local/release-v0.2.0
```

The portable ZIP contains only the pinned runtime archive, installer, setup script, checksums and standalone guide. Dependencies are installed from their normal package sources. It excludes local credentials, certificates, virtual environments, usage records and research source checkouts. See [third-party notices](THIRD_PARTY_NOTICES.md). No redistribution licence has been selected for the original private BlackYT code.
