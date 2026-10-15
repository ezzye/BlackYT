# Third-party components and source references

BlackYT is currently a private project. No redistribution licence has been selected for its original code. Making the repository public or including upstream code requires a deliberate licence decision.

The release ZIP contains BlackYT source/runtime files and installer documentation. Python, Node, Termux packages and Python dependencies are installed separately; their licences continue to apply. No YouTube audio, private credentials, local certificates, upstream app source checkout or bundled Termux APK is included.

- **yt-dlp** is the current extractor dependency, installed from the pinned Python requirements. Its [source licence](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE) is the Unlicense. Review upstream notices for optional components and any different binary bundle before redistribution.
- **yt-dlp EJS** and the selected JavaScript runtime support extraction; see [upstream EJS documentation](https://github.com/yt-dlp/yt-dlp/wiki/EJS). Installed packages retain their own notices.
- **Termux** is installed independently. The app is [GPLv3 with documented component exceptions](https://github.com/termux/termux-app/blob/master/LICENSE.md). This project does not package or fork the Termux APK.
- **NewPipe, NewPipeExtractor and LibreTube** were inspected as architectural references. The current runtime does not import or copy their application/extractor code. Pinned source references are in `research/`. A future native port using NewPipeExtractor must account for its [GPL licence](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/LICENSE).
- **Android Media3 and WebViewAssetLoader** are proposed native-port components, not dependencies shipped by this release. See `docs/ARCHITECTURE.md`.

This notice records project/dependency boundaries; it does not grant rights to YouTube content or promise supported YouTube API access. Use the unofficial prototype only with content you are entitled to access.
