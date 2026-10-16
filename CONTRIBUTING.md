# Contributing to BlackYT

Help make music and learning easier to explore. Contributions to accessibility, search, artwork presentation, testing, documentation and the Android app are welcome. You do not need to be a programmer to help.

BlackYT is **Android only** today. Please read the [README](README.md), [roadmap](ROADMAP.md) and [disclaimer](DISCLAIMER.md) first. A GitHub account is needed to open issues and pull requests, but not to download or use the app.

## Report a problem or suggest an improvement

1. Search [existing issues](https://github.com/ezzye/BlackYT/issues) to avoid duplicates.
2. Choose [New issue](https://github.com/ezzye/BlackYT/issues/new/choose), then a bug report or feature request.
3. For a bug, include your BlackYT version, Android version and phone model; the steps you took; what you expected; and what happened instead. Copy the exact error message. State whether you tested Wi-Fi or mobile data, if relevant.
4. For an idea, explain the problem and who would benefit. Discuss substantial features in an issue before starting a large change.

Screenshots and short screen recordings can help. Cover personal details. A normal public YT watch/share link is useful for a content-specific problem; omit private or unlisted material unless its owner has agreed. Do not post account cookies, tokens, signing keys, full temporary stream URLs, personal listening history or copyrighted audio files.

For private security or rights concerns, email **blackyt@streamit2.me** instead of posting sensitive details publicly.

## Improve the guides without coding

You can report confusing instructions, propose clearer wording, check accessibility or describe a phone test in an issue. To propose a small documentation edit directly:

1. Open the relevant file on GitHub and choose the pencil / **Edit this file** control.
2. GitHub will offer to create a fork if you do not have write access. A fork is your own copy of the repository.
3. Make your edit, preview it, and choose **Commit changes** on a new branch in your fork.
4. Open a **pull request** into `ezzye/BlackYT`, base branch `main`. Explain what you changed and why.

See [GitHub's contribution walkthrough](https://docs.github.com/en/get-started/exploring-projects-on-github/contributing-to-a-project) for the fork and pull request steps. When instructions appear in both Markdown and an HTML guide, update both or identify the matching page for the maintainer.

## Contribute code

Fork the repository on GitHub, then clone **your fork**. Replace `YOUR-USERNAME` with your GitHub username:

```sh
git clone https://github.com/YOUR-USERNAME/BlackYT.git
cd BlackYT
git switch -c improve-search
```

Use a short branch name that describes your change. Keep each pull request focused on one problem. Useful starting points:

- `android/app/src/main/`: native Android app, interface and playback.
- `android/app/src/test/`: local Android unit tests.
- `android/app/src/androidTest/`: optional connected-device tests.
- `docs/` and `android/docs/`: installation, help, architecture and verification notes.
- `server.py`, `public/` and `phone/`: legacy Python/PWA and Termux implementation; see the licensing note below.

### Build and check the native Android app

Use Java 17, Android SDK Platform 36 and Build Tools 35, with the included Gradle wrapper. Android Studio can help install the SDK. Full setup and signing details are in [android/README.md](android/README.md).

From the repository root:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintRelease
```

Use `gradlew.bat` instead of `./gradlew` in Windows Command Prompt. A phone is not needed for these local checks. Contributors can use a debug build without the maintainer's release signing key. A differently signed build cannot update the official APK in place; use a test device or emulator to avoid losing your saved app data.

Connected-device checks are optional and are documented in `android/README.md`. They make real network requests, stream audio and replace/clear the app's queue. Run them on a suitable test phone with the owner's permission, and respect its screen lock. State which checks actually ran and distinguish silent automated checks from listening tests.

For changes to the legacy Python/PWA route, follow the root README's setup and maintainer checks and report the checks relevant to your change.

### Protect the existing experience

For changes that affect them, check search and creator browsing, shared links and timestamps, saved episodes/progress, Explore, queue order and swipes, direct on-device streaming, and screen-off playback. Describe any checks you could not run. Live YT content and network conditions can change; do not claim a device test based only on unit tests.

Library saves are metadata and progress, not offline audio. Preserve accurate Android-only wording and avoid promises of guaranteed content access or an ad-free experience. Keep private recordings and credentials out of tests and commits.

## Open your pull request

Commit only the files relevant to your change and push your branch to your fork:

```sh
git push -u origin improve-search
```

On GitHub, choose **Compare & pull request**, with `ezzye/BlackYT:main` as the base. Include the problem, resulting behaviour, linked issue if any, checks performed and limitations. Add screenshots for interface changes using public or permissioned example content. Draft pull requests are welcome while work is unfinished. Maintainers review changes before inclusion; submission does not guarantee acceptance or a release date.

## Licences, assets and respectful collaboration

The native Android component in `android/` is **GPL-3.0-or-later**. Keep its licence headers, dependency notices and compatible licensing when proposing Android code. See [android/LICENSE](android/LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The legacy PWA's original code has no additional redistribution licence selected; contact the maintainer about permissions before reusing or distributing that component. Public visibility alone is not a licence.

Submit code, artwork, recordings or other material only when you have the necessary rights. Explain the source, licence and permission for new third-party assets. Keep signing files, keystores, passwords, local certificates, generated APKs and private device logs out of pull requests.

Be constructive and welcoming. Explain disagreements with evidence, respect different experience levels and make space for contributors who are learning.

Project contact: **blackyt@streamit2.me** · Unsigned Media Ltd.
