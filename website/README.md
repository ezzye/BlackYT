# BlackYT product website

Public product site: https://blackyt.streamit2.me/

Buildless static site. `index.html` contains the page, styling and small guide-sharing enhancement. It works without a JavaScript framework. Assets are local so the promo does not load a third-party video player; it is fetched only when the visitor starts it.

## Edit and preview

From the repository root, edit `website/index.html`, then run `python3 -m http.server 8000 --directory website` and open http://localhost:8000/. Keep the Android-only statement, accurate current release filename and version, install instructions, GPL/source links, lawful-use disclaimer and privacy qualifications.

The promo is the approved `BlackYT-Stay-in-the-Flow-FIXED-v3.mp4`. Preserve its credits. Captions are in `assets/flow-captions.vtt`. Screenshot captions identify earlier-version previews. The offline installation guide is a complete local HTML file downloaded from the signed app release; replace it with a verified current release guide when updating.

## Publish

The managed Sites checkout uses `dist/` and retains its identity in `.openai/hosting.json`; this public folder mirrors the contents of that `dist/` directory. Copy edits back into the existing managed checkout before publishing. Use the installed Sites skill and source-workflow helper to commit/push, package and save the exact source state before deploying a version. Never create a replacement Site or change the project ID for normal updates. Custom-domain DNS is managed in the existing streamit2.me Route53 hosted zone; preserve unrelated records and mail settings.

The main download serves the unchanged signed v0.6.0 APK from this site, with a GitHub alternative. Verify its SHA256 when replacing it: 6156f058edd326a7880f2b0879286497a4273888671eb644608d406beca02c64. Keep source builds and signing instructions in https://github.com/ezzye/BlackYT. No browser analytics, advertising script, external font, signup or cloud library is added by this static page. Hosting providers still process ordinary network requests.
