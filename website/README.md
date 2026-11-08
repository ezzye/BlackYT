# BlackYT product website

Public product site: https://blackyt.streamit2.me/

Buildless static site. `index.html` contains the page, styling and small guide-sharing enhancement. It works without a JavaScript framework. Assets are local so the promo does not load a third-party video player; it is fetched only when the visitor starts it.

## Edit and preview

From the repository root, edit `website/index.html`, then run `python3 -m http.server 8000 --directory website` and open http://localhost:8000/. Keep the Android-only statement, accurate current release filename and version, install instructions, GPL/source links, lawful-use disclaimer and privacy qualifications.

The promo is the approved `BlackYT-Stay-in-the-Flow-FIXED-v3.mp4`. Preserve its credits. Captions are in `assets/flow-captions.vtt`. Screenshot captions identify earlier-version previews. The offline installation guide is a complete local HTML file downloaded from the signed app release; keep it aligned with the current release candidate guide when updating.

## Publish

The managed Sites checkout uses `dist/` and retains its identity in `.openai/hosting.json`; this public folder mirrors the contents of that `dist/` directory. Copy edits back into the existing managed checkout before publishing. Use the installed Sites skill and source-workflow helper to commit/push, package and save the exact source state before deploying a version. Never create a replacement Site or change the project ID for normal updates. Custom-domain DNS is managed in the existing streamit2.me Route53 hosted zone; preserve unrelated records and mail settings.

The main download serves the signed v0.7.0-rc4 release candidate APK (versionCode 73, 7,162,416 bytes) from this site, with a GitHub alternative. Verify SHA256: a3cfb318189a380b5d3504e1c303af88f3869c0324dec1bfffae348a9124974b. Keep source builds and signing instructions in https://github.com/ezzye/BlackYT. No browser analytics, advertising script, external font, signup or cloud library is added by this static page. Hosting providers still process ordinary network requests.
