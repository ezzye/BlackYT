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
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
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
    private ListeningStore listening;
    private String pinnedMediaId = "";
    private int progressSamples;
    private final android.os.Handler progressHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService progressWrites = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            if (++progressSamples % 5 == 0 && player.isPlaying()) saveCurrentProgress();
            updateQueuePreparation();
            progressHandler.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        listening = new ListeningStore(this);
        YouTubeRepository repository = YouTubeRepository.get();
        DataSource.Factory rawHttp = new OkHttpDataSource.Factory(repository.mediaClient())
                .setUserAgent(YouTubeRepository.USER_AGENT);
        DataSource.Factory http = () -> new QueryRangeAudioDataSource(rawHttp);
        DataSource.Factory ownedOnly = () -> new RefreshingAudioDataSource(http, (dataSpec, fresh) -> {
            NativeMediaItems.OwnedItem item = parseOwnedDataSpec(dataSpec);
            try {
                if (fresh) Log.i(TAG, "Refreshing a refused YT audio stream once");
                YouTubeRepository.Resolved result = repository.resolve(item.id, item.rules, fresh);
                String expectedKey = AudioPrefixCache.key(item.id, item.rules, result);
                if (result.formatKey != null && !result.formatKey.isEmpty()
                        && !PlaybackFormatPins.accept(item.id, result.formatKey))
                    throw new AudioFormatChangedException();
                if (dataSpec.key != null && !dataSpec.key.equals(expectedKey)) {
                    throw new AudioFormatChangedException();
                }
                // The repository validates this host against googlevideo.com; never log its URL.
                Log.i(TAG, "Resolved YT audio source host=" + result.sourceHost);
                return android.net.Uri.parse(result.url);
            } catch (AudioFormatChangedException changed) {
                throw changed;
            } catch (Exception failure) {
                Log.w(TAG, "Resolution failed (" + failure.getClass().getSimpleName() + ")");
                throw new IOException("Unable to resolve this audio item.");
            }
        });
        DataSource.Factory playbackFactory = () -> {
            AudioPrefixCache preparedCache = AudioPrefixCache.peek();
            if (preparedCache == null) return ownedOnly.createDataSource();
            try { return new KeyedAudioCacheDataSource(preparedCache.factory(ownedOnly), ownedOnly); }
            catch (RuntimeException unavailable) {
                Log.w(TAG, "Audio cache unavailable; using direct playback");
                return ownedOnly.createDataSource();
            }
        };
        player = new ExoPlayer.Builder(this)
                .setLoadControl(PlaybackBufferPolicy.createLoadControl())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(
                        () -> new OfflineFirstAudioDataSource(OfflineAudioStore.get(this), playbackFactory)))
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .build();
        player.addListener(new Player.Listener() {
            @Override public void onEvents(Player source, Player.Events events) { updateQueuePreparation(); }

            @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
                String nextId = item == null ? "" : item.mediaId;
                if (!pinnedMediaId.isEmpty() && !pinnedMediaId.equals(nextId))
                    PlaybackFormatPins.clear(pinnedMediaId);
                pinnedMediaId = nextId;
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (BuildConfig.DEBUG) Log.i(TAG, "BlackYTPlayback:state state=" + stateName(state)
                        + " isPlaying=" + player.isPlaying() + " mediaId=" + safeCurrentMediaId()
                        + " positionMs=" + player.getCurrentPosition());
            }

            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition, int reason) {
                if (oldPosition.mediaItem != null && (newPosition.mediaItem == null
                        || !oldPosition.mediaItem.mediaId.equals(newPosition.mediaItem.mediaId))) {
                    saveProgress(oldPosition.mediaItem.mediaId, oldPosition.positionMs,
                            oldPosition.mediaItem.mediaMetadata.extras == null ? 0
                            : oldPosition.mediaItem.mediaMetadata.extras.getLong("blackyt_duration_seconds", 0) * 1000L);
                }
                saveCurrentProgress();
            }

            @Override public void onIsPlayingChanged(boolean isPlaying) {
                saveCurrentProgress();
                if (BuildConfig.DEBUG) Log.i(TAG, "BlackYTPlayback:state state=" + stateName(player.getPlaybackState())
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
        progressHandler.postDelayed(progressTick, 10000);
        session = new MediaSession.Builder(this, player).setSessionActivity(activity)
                .setCallback(new QueueCallback()).build();
    }

    private void updateQueuePreparation() {
        if (player == null) return;
        boolean lowReserve = player.getPlayWhenReady() && PlaybackBufferPolicy.shouldPausePreparation(
                player.getPlaybackState(), player.getTotalBufferedDuration());
        PlaybackPreparation.get(this).setPlaybackBusy(lowReserve);
        OfflineAudioStore.get(this).setPlaybackBusy(lowReserve);
        List<PlaybackPreparation.Candidate> candidates = new ArrayList<>();
        if (!lowReserve) {
            int start = player.getCurrentMediaItemIndex() + 1;
            for (int i = Math.max(0, start); i < player.getMediaItemCount() && candidates.size() < 2; i++) {
                try {
                    NativeMediaItems.OwnedItem item = NativeMediaItems.parse(player.getMediaItemAt(i));
                    candidates.add(new PlaybackPreparation.Candidate(item.id, item.rules));
                } catch (RuntimeException invalid) { /* Only owned queue entries are eligible. */ }
            }
        }
        PlaybackPreparation.get(this).update("queue", candidates);
    }

    private void saveCurrentProgress() {
        if (player == null || player.getCurrentMediaItem() == null) return;
        MediaItem item = player.getCurrentMediaItem();
        long duration = player.getDuration();
        if (duration <= 0 || duration == C.TIME_UNSET) duration = item.mediaMetadata.extras == null ? 0
                : item.mediaMetadata.extras.getLong("blackyt_duration_seconds", 0) * 1000L;
        saveProgress(item.mediaId, Math.max(0, player.getCurrentPosition()), duration);
    }

    private void saveProgress(String id, long position, long duration) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}") || progressWrites.isShutdown()) return;
        ListeningStore.SavedEpisode saved = listening.getEpisode(id);
        if (saved == null) return;
        try { progressWrites.execute(() -> {
            try { listening.updateProgressIfSaved(id, position, duration, saved.savedAtMs); }
            catch (IllegalArgumentException invalid) { /* No unowned metadata is persisted. */ }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
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

    static NativeMediaItems.OwnedItem parseOwnedDataSpec(DataSpec spec) throws IOException {
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
        PlaybackPreparation.get(this).update("queue", java.util.Collections.emptyList());
        PlaybackPreparation.get(this).setPlaybackBusy(false);
        OfflineAudioStore.get(this).setPlaybackBusy(false);
        PlaybackFormatPins.clear(pinnedMediaId);
        saveCurrentProgress();
        progressHandler.removeCallbacksAndMessages(null);
        progressWrites.shutdown();
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
                    String artwork = item.mediaMetadata.artworkUri == null ? ""
                            : item.mediaMetadata.artworkUri.toString();
                    long duration = item.mediaMetadata.extras == null ? 0
                            : item.mediaMetadata.extras.getLong("blackyt_duration_seconds", 0);
                    duration = Math.max(0, Math.min(duration, 864000));
                    accepted.add(NativeMediaItems.fromTrack(
                            new YouTubeRepository.Track(owned.id, title, artist, duration, artwork,
                                    item.mediaMetadata.extras == null ? "" : item.mediaMetadata.extras.getString("blackyt_creator_url", "")), owned.rules));
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
