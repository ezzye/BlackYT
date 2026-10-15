// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

/** Creates and validates the app-owned, deferred-resolution media item format. */
public final class NativeMediaItems {
    private static final String SCHEME = "blackyt";
    private static final String AUTHORITY = "youtube";
    private NativeMediaItems() {}

    public static MediaItem fromTrack(YouTubeRepository.Track track, YouTubeRepository.Rules rules) {
        if (track == null) throw new IllegalArgumentException("Missing track");
        String id = YouTubeRepository.normalizeVideoId(track.id);
        YouTubeRepository.Rules safe = rules == null ? new YouTubeRepository.Rules(false, "", "") : rules;
        Uri uri = new Uri.Builder().scheme(SCHEME).authority(AUTHORITY).appendPath(id)
                .appendQueryParameter("music", safe.musicOnly ? "1" : "0")
                .appendQueryParameter("include", safe.include).appendQueryParameter("exclude", safe.exclude).build();
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(track.title == null ? "" : track.title)
                .setArtist(track.channel == null ? "" : track.channel).build();
        return new MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(metadata).build();
    }

    public static OwnedItem parse(MediaItem item) {
        if (item == null || item.localConfiguration == null || item.localConfiguration.uri == null)
            throw new IllegalArgumentException("Missing owned media URI");
        Uri uri = item.localConfiguration.uri;
        if (!SCHEME.equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getPathSegments().size() != 1)
            throw new IllegalArgumentException("Unsupported media URI");
        String id = YouTubeRepository.normalizeVideoId(uri.getLastPathSegment());
        if (!id.equals(item.mediaId)) throw new IllegalArgumentException("Media id mismatch");
        String music = uri.getQueryParameter("music");
        String include = uri.getQueryParameter("include"), exclude = uri.getQueryParameter("exclude");
        if (!("0".equals(music) || "1".equals(music)) || include == null || exclude == null
                || include.length() > 1000 || exclude.length() > 1000)
            throw new IllegalArgumentException("Invalid playback rules");
        return new OwnedItem(id, new YouTubeRepository.Rules("1".equals(music), include, exclude));
    }

    public static final class OwnedItem {
        public final String id;
        public final YouTubeRepository.Rules rules;
        private OwnedItem(String id, YouTubeRepository.Rules rules) { this.id = id; this.rules = rules; }
    }
}
