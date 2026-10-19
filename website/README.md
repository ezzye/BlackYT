# BlackYT product website

Live: https://blackyt.streamit2.me/

This folder is the public source mirror of the static product website. Preview with `python3 -m http.server 8000 --directory website` from the repository root. Edit `index.html` for text and layout; assets are local and the approved FIXED-v3 promo loads on visitor request. `BlackYT-Android-Guide.html` is a standalone offline guide. Captions are in `assets/flow-captions.vtt`.

Hosting is managed through the existing BlackYT Sites project, whose dedicated checkout retains `.openai/hosting.json`. Use that existing project for deployment; do not create a replacement Site or change unrelated domain/mail settings. Mirror content changes here after publishing. The app download links to the signed GitHub release rather than hosting an unofficial build.

When updating a release, change the current version, APK URL and displayed size together, replace the offline guide with the verified release guide, and retain clear Android-only installation, use, FAQ, troubleshooting and privacy wording. Do not promise anonymity, no data leaving the phone, or removal of creator sponsorships.

The mascot and promo are existing BlackYT assets. Film ©2026 Unsigned Media Ltd; music Addicted To You by Ezzy Elliott; synthetic ElevenLabs narration. Screenshots identify the app version they preview.
