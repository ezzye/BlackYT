// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Native foreground audio playback for BlackYT-owned queue items. */
@UnstableApi
public final class PlaybackService extends MediaSessionService {
    private static final String TAG = "BlackYTPlayback";
    private ExoPlayer player;
    private MediaSession session;

    @Override public void onCreate() {
        super.onCreate();
        YouTubeRepository repository = YouTubeRepository.get();
        DataSource.Factory http = new OkHttpDataSource.Factory(repository.mediaClient())
                .setUserAgent(YouTubeRepository.USER_AGENT);
        DataSource.Factory ownedOnly = new ResolvingDataSource.Factory(http, dataSpec -> {
            NativeMediaItems.OwnedItem item = parseOwnedDataSpec(dataSpec);
            try {
                YouTubeRepository.Resolved result = repository.resolve(item.id, item.rules, false);
                // The repository validates this host against googlevideo.com; never log its URL.
                Log.i(TAG, "Resolved YT audio source host=" + result.sourceHost);
                return dataSpec.withUri(android.net.Uri.parse(result.url));
            } catch (Exception failure) {
                Log.w(TAG, "Resolution failed (" + failure.getClass().getSimpleName() + ")");
                throw new IOException("Unable to resolve this audio item.");
            }
        });
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(ownedOnly))
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .build();
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                Log.i(TAG, "BlackYTPlayback:state state=" + stateName(state)
                        + " isPlaying=" + player.isPlaying() + " mediaId=" + safeCurrentMediaId()
                        + " positionMs=" + player.getCurrentPosition());
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                Log.i(TAG, "BlackYTPlayback:state state=" + stateName(player.getPlaybackState())
                        + " isPlaying=" + isPlaying + " mediaId=" + safeCurrentMediaId()
                        + " positionMs=" + player.getCurrentPosition());
            }

            @Override public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Playback failed (code=" + error.errorCode + ", "
                        + error.getClass().getSimpleName() + ")");
            }
        });
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent activity = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, player).setSessionActivity(activity)
                .setCallback(new QueueCallback()).build();
    }

    private String safeCurrentMediaId() {
        if (player == null || player.getCurrentMediaItem() == null) return "none";
        try {
            return YouTubeRepository.normalizeVideoId(player.getCurrentMediaItem().mediaId);
        } catch (IllegalArgumentException invalid) {
            return "invalid";
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "idle";
            case Player.STATE_BUFFERING: return "buffering";
            case Player.STATE_READY: return "ready";
            case Player.STATE_ENDED: return "ended";
            default: return "unknown";
        }
    }

    private static NativeMediaItems.OwnedItem parseOwnedDataSpec(DataSpec spec) throws IOException {
        try {
            String pathId = spec.uri.getLastPathSegment();
            MediaItem item = new MediaItem.Builder().setMediaId(pathId == null ? "" : pathId)
                    .setUri(spec.uri).build();
            return NativeMediaItems.parse(item);
        } catch (RuntimeException invalid) {
            throw new IOException("Only BlackYT queue items can be played.");
        }
    }

    @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller) {
        int uid = controller.getUid();
        if (uid == getApplicationInfo().uid || controller.isTrusted()) return session;
        Log.w(TAG, "Rejected media controller uid=" + uid);
        return null;
    }

    @Override public void onTaskRemoved(@Nullable Intent rootIntent) {
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
    }

    @Override public void onDestroy() {
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }

    private static final class QueueCallback implements MediaSession.Callback {
        @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(
                MediaSession mediaSession, MediaSession.ControllerInfo controller, List<MediaItem> items) {
            try {
                if (items == null || items.isEmpty() || items.size() > 200)
                    throw new IllegalArgumentException("Invalid queue size");
                List<MediaItem> accepted = new ArrayList<>(items.size());
                for (MediaItem item : items) {
                    NativeMediaItems.OwnedItem owned = NativeMediaItems.parse(item);
                    String title = item.mediaMetadata.title == null ? "" : item.mediaMetadata.title.toString();
                    String artist = item.mediaMetadata.artist == null ? "" : item.mediaMetadata.artist.toString();
                    // Rebuild to discard arbitrary headers, tags, and external playback configuration.
                    accepted.add(NativeMediaItems.fromTrack(
                            new YouTubeRepository.Track(owned.id, title, artist, 0), owned.rules));
                }
                return Futures.immediateFuture(accepted);
            } catch (RuntimeException invalid) {
                Log.w(TAG, "Rejected unsupported queue media items");
                return Futures.immediateFailedFuture(
                        new IllegalArgumentException("Only BlackYT YT queue items are supported."));
            }
        }
    }
}
