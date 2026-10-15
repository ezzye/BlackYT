# Install and use BlackYT on Android

This guide provides a new portable phone-only installation route and the earlier Mac-assisted transfer. Runtime playback, manual queueing and two minutes with the screen locked were confirmed on a Pixel 10 Pro XL. A fresh phone installation using the new portable ZIP has not been repeated physically. The source repository is private; use your own GitHub account with access. BlackYT is not a standalone APK. It uses Termux for the local Python/Node runtime and Chrome for the separate PWA icon.

## How it works

During setup only, the Mac serves a generated installer and app bundle to the phone over the same home Wi-Fi. The command in the generated phone guide checks the installer SHA-256, and the installer checks the bundle SHA-256 before unpacking it. After installation, Termux runs the search and stream helper on the phone at `127.0.0.1:8765`. Chrome opens the PWA at `http://127.0.0.1:8765`. Termux resolves a selected public YouTube music result and requests its audio directly from YouTube; Chrome reads the audio over phone loopback. The search request and audio connection both use the phone's network. No Mac, cloud server, or off-device audio relay carries the phone-mode audio. The app does not download tracks for offline playback.

The loopback site uses plain HTTP; the phone does not need the Mac's private test certificate or a certificate authority. Keep the resolver running in its foreground Termux session while listening.

## Phone-only release installation

This route does not require a computer. GitHub authentication happens in Chrome; never paste a GitHub token into Termux.

1. Install and open [Termux from its official F-Droid page](https://f-droid.org/en/packages/com.termux/). Keep the same distribution if Termux is already installed; different sources use different signatures.
2. In Chrome, sign into your GitHub account with repository access. Open [release v0.2.0](https://github.com/ezzye/BlackYT/releases/tag/v0.2.0). Download **BlackYT-Phone-v0.2.0.zip** from Assets, not the generic “Source code” ZIP. Keep that exact filename in Downloads. If Chrome adds `(1)` to the filename, rename the new file to match before using these commands.
3. In Termux, prepare packages together:

   ```sh
   apt update && apt full-upgrade -y && apt install -y python curl unzip termux-tools
   ```

4. Request access to shared Downloads:

   ```sh
   termux-setup-storage
   ```

   Allow the Android permission prompt before continuing. This grants Termux shared-storage access for the transfer; the installed application is kept in its private home. The [Termux storage guide](https://github.com/termux/termux-tools/blob/master/scripts/termux-setup-storage.in) describes these links.
5. Extract this release into a dedicated setup folder and start its installer:

   ```sh
   mkdir -p "$HOME/blackyt-setup-v0.2.0" && unzip -o "$HOME/storage/downloads/BlackYT-Phone-v0.2.0.zip" -d "$HOME/blackyt-setup-v0.2.0" && bash "$HOME/blackyt-setup-v0.2.0/setup-phone.sh"
   ```

   The setup script verifies the bundled installer fingerprint. The installer then verifies the bundled runtime archive and rejects unsafe paths before changing the managed installation. The private ZIP is downloaded through your signed-in browser; these internal hashes are not an independent signature.
6. Wait for the dependency setup and Chrome to open `http://127.0.0.1:8765/`. Look for **Running on this phone**. Chrome ⋮ → **Add to Home screen** → **Install**, if offered. Use the separate **BlackYT Phone** icon. No custom certificate is required.
7. Keep Termux running. If your Android settings offer it, allow Termux background activity or unrestricted battery use. Run `blackyt` after each phone restart or if Android stops its process.

For updates, stop BlackYT using Ctrl+C first, download the new release, and use that release’s exact ZIP name and guide. Do not overwrite a running installation with mismatched release files. Your currently working installation does not need to be replaced merely to test this packaging.

The download also contains `START-HERE.html`, and the release has **BlackYT-Guide.html** as a separate asset. Save and attach that HTML to share the guide privately. Sharing it does not grant access to the private GitHub repository.

## Mac-assisted prerequisites

- A Mac with Git, Python 3.10 or newer, and internet access for the first setup; the Mac and phone must be on the same trusted home Wi-Fi during transfer.
- Your own GitHub account with access to the private `ezzye/BlackYT` repository. Do not paste an access token into a command or share it with anyone.
- Use the official [Termux F-Droid distribution](https://f-droid.org/en/packages/com.termux/). Termux installs from different sources have different signing keys; do not replace an existing Termux installation from another source without checking its origin first.
- Have internet access and allow time and mobile data for Termux package updates, Python/Node installation, and the BlackYT package download.
- Do not use a setup command copied from another Wi-Fi address or an earlier generated build. It contains the current Mac address and exact checksum-pinned filenames.

## Mac-assisted first transfer

1. In GitHub, confirm that your own account can open the private repository. In Terminal, clone it using your normal GitHub sign-in or credential helper:

   ```sh
   git clone https://github.com/ezzye/BlackYT.git
   cd BlackYT
   ```

   GitHub may ask you to authenticate. Never put a password or access token directly in the clone URL or paste one into the setup commands.
2. In the repository folder, prepare the Mac environment:

   ```sh
   ./setup.sh
   ```

3. Find the Mac's current Wi-Fi IPv4 address in macOS **System Settings → Wi-Fi → Details → TCP/IP**. It is typically a private-network address. In the commands below, replace `YOUR_MAC_WIFI_IP` with that address. Do not type the placeholder literally. Keep the phone on the same Wi-Fi for this one-time transfer.
4. Generate the phone installer, bundle, and matching setup command using that address:

   ```sh
   python3 phone/build_phone.py --transfer-url "http://YOUR_MAC_WIFI_IP:8765"
   ```

   The build prints the bundle and installer fingerprints. It also writes a phone guide with the exact command for these generated files. Rebuild this bundle and guide if the Mac's Wi-Fi address changes.
5. Start the Mac's temporary local transfer server, allowing the phone to connect to the Mac's address:

   ```sh
   ./start.sh --listen 0.0.0.0 --allow-host YOUR_MAC_WIFI_IP
   ```

   Leave this Terminal window open. On the Pixel, open Chrome to `http://YOUR_MAC_WIFI_IP:8765/phone.html`, replacing the placeholder with the same address. This is the private setup guide hosted by your Mac; it contains the current installer command and hashes.
6. Install and open Termux from F-Droid. If Android asks whether Chrome may install an APK downloaded from F-Droid, allow it for that installation. In the phone guide, tap **Copy setup command**, switch to Termux, paste, and press Enter. Do not edit the checksums or substitute a URL from another build.
7. Let the command finish. It runs `apt update` and `apt full-upgrade` before installing `curl`, then verifies the downloaded installer and app bundle with SHA-256 before installing them. This order matters: Termux package libraries can be inconsistent after a partial upgrade, causing errors such as curl failing to link.
8. When the command starts BlackYT, Chrome should open `http://127.0.0.1:8765/`. Confirm the page says **Running on this phone**. In Chrome's ⋮ menu choose **Add to Home screen** and **Install** if offered. Open the new **BlackYT Phone** icon. An older BlackYT icon pointing to a Mac's LAN address is a different setup.
9. In Android Settings, open Termux's app information and allow background activity or unrestricted battery use if that control is available. This reduces the chance that Android stops playback, but it cannot guarantee background execution on every device or power setting.
10. Once installation succeeds, stop the Mac transfer server with **Ctrl+C**. The phone's daily search and audio do not depend on it. The phone setup guide may be saved as HTML before stopping the server.

## Listen and use the queue

Open Termux and run:

```sh
blackyt
```

Keep the Termux session running and switch to the BlackYT Phone icon. Search for music. **Play** starts that result; **Add** places it in the manual queue. Use the queue's track buttons, next/previous controls, and the browser's seek controls as needed. Search mode and include/exclude words are saved in that browser's local storage. The queue is held in the open page session and is not a durable backup; a page restart may clear it.

If a track stops after switching between Wi-Fi and mobile data, use **Retry track**. It asks for a fresh media link and attempts to resume. A new search and playback over mobile data with the Mac unavailable are the meaningful independence check; an already-buffered track continuing by itself does not prove that.

## Start, check, and stop

After a phone restart, or if Android stops Termux, reopen Termux and run `blackyt` again. The launcher starts the server in the foreground, acquires a Termux wake lock for that session, checks that the local service identifies as BlackYT, and opens the player. The foreground session must remain active.

Optional checks from another Termux prompt:

```sh
blackyt status
blackyt open
```

`status` checks the service on loopback; `open` asks Android to open the local player or prints its address. To stop the server, return to the running Termux session and press **Ctrl+C** (the Termux extra-key row can send Ctrl+C). The launcher releases its wake lock when it exits.

## Update using the Mac route

On the Mac, enter the repository folder and update it:

```sh
git pull --ff-only
./setup.sh
python3 phone/build_phone.py --transfer-url "http://YOUR_MAC_WIFI_IP:8765"
./start.sh --listen 0.0.0.0 --allow-host YOUR_MAC_WIFI_IP
```

Replace `YOUR_MAC_WIFI_IP` with the Mac's current Wi-Fi address in both commands. Open `http://YOUR_MAC_WIFI_IP:8765/phone.html` on the Pixel and use the exact command from that guide. Close the running `blackyt` session with Ctrl+C first. The installer verifies the package, creates a new managed release under `~/blackyt/releases`, and switches `~/blackyt/current` to it. It reuses the Termux virtual environment and installs the pinned Python requirements. If the browser shows an older app shell, reload after Chrome completes the update. Do not manually replace `~/blackyt/current` or the launcher. Stop the Mac transfer server when the update finishes.

## Back up settings and remove BlackYT

The current app stores only its search mode and include/exclude words in Chrome local storage. Those preferences are not exported by BlackYT; note them down if you want to restore them. The manual queue is session state, not a persistent playlist. There are no user audio files or account credentials in the install tree.

To remove BlackYT, stop the running Termux session. If the Mac transfer server is open, stop it with Ctrl+C too. Then in Termux run:

```sh
rm -rf "$HOME/blackyt"
rm -f "${PREFIX:-/data/data/com.termux/files/usr}/bin/blackyt"
```

Then uninstall **BlackYT Phone** from Android/Chrome. If you no longer use the separate Mac-assisted version, you may remove only the certificate named **BlackYT local test CA - remove after testing** from Android's user credentials. Do not remove unrelated certificates. Finally, uninstall Termux only if you no longer need it for anything else. Removing the BlackYT directory also removes its managed release history and virtual environment.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| `curl` reports a missing library or cannot link | In Termux run `apt update && apt full-upgrade -y`, allow it to finish, then rerun the setup command shown in the current Mac-hosted phone guide. The installer deliberately upgrades packages before downloading. |
| Setup checksum does not match | Stop. Do not run that installer. On the Mac, regenerate the installer/bundle pair and guide, then copy the complete command from that guide. A checksum mismatch can mean a partial download or mismatched URL/build. |
| Installer says the bundle checksum or paths are invalid | Stop. On the Mac, regenerate the matching installer and app bundle, then retry using the command from the regenerated phone guide. The installer rejects modified bytes and unsafe archive paths. |
| `blackyt` says the server is not ready | Keep the Termux session visible, check the full error above the prompt, then retry `blackyt`. Use `blackyt status`. If the install is incomplete, regenerate the bundle on the Mac and rerun its phone-guide command. |
| Port 8765 is occupied by a service that is not BlackYT | BlackYT leaves that service running. Do not kill an unknown process. Identify and stop the service you recognize, then retry. |
| Search or playback returns HTTP 403 | This is a response from YouTube/media access, not a local certificate warning. Confirm the phone is online, try a fresh search, then choose **Retry track**. YouTube can change or block this unofficial route; a 403 may persist. |
| Browser reports a certificate warning on `127.0.0.1` | Phone mode uses loopback HTTP and should not need a custom certificate. Check that the address is exactly `http://127.0.0.1:8765/`, not the older Mac LAN/HTTPS address. |
| Playback stops when the screen locks or app is backgrounded | Check Android's Termux battery/background setting, reopen Termux, and run `blackyt` again. Keep its foreground session open. Android may still stop background work. |
| The next track does not start | Confirm it was added with **Add**, keep Termux running, then try the queue's track button or **Next**. Manual queue playback was confirmed by the owner; locked-screen queue transition has not been separately confirmed. |

## Verification and limits

The repository records 22 passing backend tests, passing mocked installer checks, and physical Pixel evidence for phone-local Prince playback (HTTP 206, audio/mp4, advancing playback) plus the owner's confirmation that it was audible. The owner also confirmed that the manual queue worked after tapping the correct control. The owner subsequently confirmed two minutes with the screen locked on the phone-local version. A locked queue transition and exact disconnected/mobile-data conditions were not separately restated. The portable ZIP is a new installation route with mocked installer and package checks; a clean physical-phone install from that exact release has not been repeated. Review the `evidence/` files for detail.

Termux's own [installation guidance](https://github.com/termux/termux-app#installation) and [Android lifecycle notes](https://github.com/termux/termux-app#termux-on-android-11) explain its distribution and background limits. BlackYT uses yt-dlp and its EJS support for an unofficial access route; see the [yt-dlp FAQ](https://github.com/yt-dlp/yt-dlp/wiki/FAQ) and [EJS documentation](https://github.com/yt-dlp/yt-dlp/wiki/EJS). Availability can change, and audio may include creator messages or promotions. BlackYT does not remove them.
