// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises only items already in the live queue; never clears the cache or changes the queue. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class PlaylistCacheReplayDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final JSONObject result = new JSONObject();
    private final JSONArray stages = new JSONArray();
    private final AtomicReference<PlaybackException> error = new AtomicReference<>();
    private String phase = "connect";

    @Test public void replayExistingQueue() throws Throwable {
        MediaController controller = null;
        List<MediaItem> originalQueue = new ArrayList<>();
        int originalIndex = -1; long originalPosition = 0; boolean originalPlay = false;
        float originalVolume = 1; boolean captured = false; boolean usedSavedPlaylist = false; Throwable failure = null;
        String playlistDataBefore = context.getSharedPreferences("blackyt_playlists_v1", Context.MODE_PRIVATE).getString("playlists", null);
        try {
            result.put("status", "running").put("stages", stages);
            AtomicReference<ListenableFuture<MediaController>> future = new AtomicReference<>();
            main(() -> { future.set(new MediaController.Builder(context, new SessionToken(context,
                    new ComponentName(context, PlaybackService.class))).buildAsync()); return true; });
            controller = future.get().get(25, TimeUnit.SECONDS);
            final MediaController c = controller;
            main(() -> {
                assertTrue(c.isCommandAvailable(Player.COMMAND_GET_TIMELINE));
                assertTrue(c.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM));
                assertTrue(c.isCommandAvailable(Player.COMMAND_SET_VOLUME));
                for (int i = 0; i < c.getMediaItemCount(); i++) originalQueue.add(c.getMediaItemAt(i));
                return true;
            });
            originalIndex = main(c::getCurrentMediaItemIndex);
            originalPosition = main(c::getCurrentPosition);
            originalPlay = main(c::getPlayWhenReady);
            originalVolume = main(c::getVolume);
            if (originalQueue.isEmpty()) {
                // Starting instrumentation may restart the service and lose its in-memory queue.
                PlaylistStore.Playlist chosen = null;
                for (PlaylistStore.Playlist playlist : new PlaylistStore(context).list()) {
                    if ("coldplay".equalsIgnoreCase(playlist.name.trim())) {
                        assertNull("Saved Coldplay playlist is ambiguous", chosen);
                        chosen = playlist;
                    }
                }
                assertNotNull("Saved Coldplay playlist not found", chosen);
                for (PlaylistStore.Entry entry : chosen.entries)
                    originalQueue.add(NativeMediaItems.fromTrack(entry.track, entry.rules));
                originalIndex = find(originalQueue, "the scientist");
                assertTrue("The Scientist missing or ambiguous in saved playlist", originalIndex >= 0);
                originalPosition = 67881; originalPlay = false;
                assertTrue(main(() -> c.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)));
                final int savedIndex = originalIndex; final long savedPosition = originalPosition;
                main(() -> { c.setMediaItems(originalQueue, savedIndex, savedPosition); c.pause(); return true; });
                usedSavedPlaylist = true;
            }
            result.put("usedSavedPlaylist", usedSavedPlaylist)
                    .put("originalServiceQueuePreserved", !usedSavedPlaylist);
            assertTrue("A current item is required", originalIndex >= 0 && originalIndex < originalQueue.size());
            captured = true;
            final Player.Listener listener = new Player.Listener() {
                @Override public void onPlayerError(PlaybackException e) { error.set(e); }
            };
            main(() -> { c.addListener(listener); c.setVolume(0f); c.pause(); return true; });
            String[] names = {"yellow", "a head full of dreams"};
            int[] indexes = new int[names.length];
            phase = "identify";
            for (int i = 0; i < names.length; i++) {
                indexes[i] = find(originalQueue, names[i]);
                assertTrue("Required sample missing or ambiguous: " + names[i], indexes[i] >= 0);
                stages.put(snapshot(originalQueue.get(indexes[i]), names[i]));
            }
            boolean allPassed = true;
            if ("true".equals(InstrumentationRegistry.getArguments().getString("cacheConflict"))) {
                allPassed = cacheConflict(c, originalQueue.get(indexes[0]), indexes[0]);
            } else {
            for (int cycle = 1; cycle <= 3; cycle++) {
                for (int i = 0; i < names.length; i++) {
                    phase = "replay";
                    JSONObject stage = new JSONObject().put("kind", "replay").put("title", names[i]).put("cycle", cycle);
                    stages.put(stage);
                    error.set(null);
                    final int index = indexes[i];
                    main(() -> { c.seekTo(index, 0); c.prepare(); c.play(); return true; });
                    boolean ok = waitForProgress(c, index, 0, 45000);
                    recordPlayer(stage, c, ok);
                    allPassed &= ok;
                }
            }
            // Seek near the end and observe an automatic transition to the actual next queue item.
            phase = "natural-next";
            int from = indexes[0];
            if (from + 1 < originalQueue.size()) {
                final int start = from;
                error.set(null);
                main(() -> { c.seekTo(start, 0); c.prepare(); c.play(); return true; });
                boolean ready = waitForProgress(c, start, 0, 45000);
                long duration = main(c::getDuration);
                JSONObject stage = new JSONObject().put("kind", "natural-next").put("title", names[0]);
                stages.put(stage);
                if (ready && duration > 10000 && duration < Long.MAX_VALUE / 2) {
                    AtomicReference<Integer> reason = new AtomicReference<>();
                    Player.Listener transition = new Player.Listener() {
                        @Override public void onMediaItemTransition(MediaItem item, int why) { reason.set(why); }
                    };
                    main(() -> { c.addListener(transition); c.seekTo(start, duration - 5000); return true; });
                    boolean advanced = waitForProgress(c, start + 1, 0, 30000);
                    main(() -> { c.removeListener(transition); return true; });
                    boolean automatic = Integer.valueOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO).equals(reason.get());
                    stage.put("automatic", automatic);
                    recordPlayer(stage, c, advanced && automatic);
                    allPassed &= advanced && automatic;
                } else { stage.put("passed", false).put("reason", "duration_or_playback_unavailable"); allPassed = false; }
            } else { stages.put(new JSONObject().put("kind", "natural-next").put("passed", false).put("reason", "no_next_item")); allPassed = false; }
            }
            result.put("status", allPassed ? "passed" : "failed");
        } catch (Throwable t) {
            failure = t;
            result.put("status", "failed").put("failurePhase", phase).put("failureClass", t.getClass().getSimpleName());
        } finally {
            if (controller != null) {
                final MediaController c = controller;
                if (captured) {
                    final int index = originalIndex; final long position = originalPosition;
                    final boolean play = originalPlay; final float volume = originalVolume;
                    try {
                        boolean queueUnchanged = main(() -> {
                            if (c.getMediaItemCount() != originalQueue.size()) return false;
                            for (int i = 0; i < originalQueue.size(); i++) if (!originalQueue.get(i).equals(c.getMediaItemAt(i))) return false;
                            return true;
                        });
                        // Do not overwrite a queue another user or process changed during this test.
                        main(() -> { if (queueUnchanged) { c.pause(); c.seekTo(index, position); c.setPlayWhenReady(play); } c.setVolume(volume); return true; });
                        boolean restored = main(() -> queueUnchanged && c.getCurrentMediaItemIndex() == index
                                && Math.abs(c.getCurrentPosition() - position) < 2000 && c.getPlayWhenReady() == play
                                && Math.abs(c.getVolume() - volume) < 0.001f);
                        result.put("queueUnchanged", queueUnchanged).put("playerRestored", restored);
                        if (!restored && failure == null) failure = new AssertionError("Player restoration not verified");
                    } catch (Throwable t) { result.put("playerRestored", false).put("restoreFailureClass", t.getClass().getSimpleName()); if (failure == null) failure = t; }
                }
                try { main(() -> { c.release(); return true; }); } catch (Throwable t) { if (failure == null) failure = t; }
            }
            boolean playlistUnchanged = java.util.Objects.equals(playlistDataBefore,
                    context.getSharedPreferences("blackyt_playlists_v1", Context.MODE_PRIVATE).getString("playlists", null));
            result.put("savedPlaylistsUnchanged", playlistUnchanged);
            if (!playlistUnchanged && failure == null) failure = new AssertionError("Saved playlists changed");
            if (failure != null) result.put("status", "failed");
            File dir = context.getExternalFilesDir("playlist-cache-check");
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new AssertionError("Evidence directory unavailable");
            try (FileOutputStream out = new FileOutputStream(new File(dir, "result.json"))) {
                out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (failure != null) throw failure;
        assertEquals("One or more replay stages failed", "passed", result.getString("status"));
    }

    private boolean cacheConflict(MediaController c, MediaItem item, int index) throws Exception {
        phase = "cache-conflict";
        JSONObject stage = new JSONObject().put("kind", "cache-conflict"); stages.put(stage);
        error.set(null);
        main(() -> { c.seekTo(index, 7000); c.prepare(); c.play(); return true; });
        if (!waitForProgress(c, index, 7000, 45000)) { recordPlayer(stage, c, false); return false; }
        NativeMediaItems.OwnedItem owned = NativeMediaItems.parse(item);
        AudioPrefixCache cache = AudioPrefixCache.get(context);
        AudioCacheDescriptors.Entry old = cache.descriptor(owned.id, owned.rules);
        assertNotNull("No playback descriptor", old);
        YouTubeRepository.Resolved fresh = YouTubeRepository.get().resolve(owned.id, owned.rules, true);
        assertFalse("Need a different representation identity to reproduce the conflict", old.format.equals(fresh.formatKey));
        // Same operation as speculative preparation: replace an older binding with a freshly resolved one.
        assertTrue(cache.remember(owned.id, owned.rules, fresh.formatKey));
        cache.rememberLength(owned.id, owned.rules, fresh.formatKey, fresh.contentLength);
        long position = main(c::getCurrentPosition);
        stage.put("bindingChanged", true).put("restartPositionMs", position);
        error.set(null);
        main(() -> { c.stop(); c.seekTo(index, position); c.prepare(); c.play(); return true; });
        long end = SystemClock.elapsedRealtime() + 60000;
        boolean recovered = false;
        while (SystemClock.elapsedRealtime() < end) {
            if (main(() -> c.getCurrentMediaItemIndex() == index && c.isPlaying()
                    && c.getCurrentPosition() >= position + 2000 && c.getPlayerError() == null)) { recovered = true; break; }
            SystemClock.sleep(250);
        }
        recordPlayer(stage, c, recovered);
        stage.put("recoveredWithoutManualRetry", recovered);
        return recovered;
    }

    private JSONObject snapshot(MediaItem item, String title) throws Exception {
        JSONObject out = new JSONObject().put("kind", "cache-snapshot").put("title", title);
        try {
            NativeMediaItems.OwnedItem owned = NativeMediaItems.parse(item);
            AudioPrefixCache cache = AudioPrefixCache.get(context);
            AudioCacheDescriptors.Entry descriptor = cache.descriptor(owned.id, owned.rules);
            AudioPrefixCache.OfflineStatus status = cache.offlineStatuses(Collections.singletonList(
                    new PlaybackPreparation.Candidate(owned.id, owned.rules))).get(AudioPrefixCache.identity(owned.id, owned.rules));
            out.put("descriptorPresent", descriptor != null).put("cachedBytes", cache.cachedBytes(owned.id, owned.rules));
            if (status != null) out.put("contentLength", status.contentLength).put("readyOffline", status.ready);
            try {
                YouTubeRepository.Resolved fresh = YouTubeRepository.get().resolve(owned.id, owned.rules, true);
                out.put("freshResolved", true).put("formatMatchesDescriptor", descriptor != null && descriptor.format.equals(fresh.formatKey));
                YouTubeRepository.Resolved again = YouTubeRepository.get().resolve(owned.id, owned.rules, true);
                out.put("freshFormatsEqual", fresh.formatKey.equals(again.formatKey));
                JSONObject equal = new JSONObject();
                Uri a = Uri.parse(fresh.url), b = Uri.parse(again.url);
                for (String field : new String[]{"itag","clen","lmt","id","xtags","audio_track_id"})
                    equal.put(field, java.util.Objects.equals(a.getQueryParameter(field), b.getQueryParameter(field)));
                out.put("freshUrlFieldsEqual", equal);
            } catch (Throwable t) { out.put("freshResolved", false).put("resolveFailureClass", t.getClass().getSimpleName()); }
        } catch (Throwable t) { out.put("snapshotFailureClass", t.getClass().getSimpleName()); }
        return out;
    }
    private void recordPlayer(JSONObject stage, MediaController c, boolean ok) throws Exception {
        stage.put("screenOff", !context.getSystemService(android.os.PowerManager.class).isInteractive());
        android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
        boolean bt = false;
        for (android.media.AudioDeviceInfo device : audio.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS))
            if (device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) bt = true;
        stage.put("a2dpConnected", bt);
        PlaybackException e = error.get();
        if (e == null) e = main(c::getPlayerError);
        stage.put("passed", ok).put("positionMs", main(c::getCurrentPosition)).put("playbackState", main(c::getPlaybackState));
        if (e != null) {
            stage.put("errorCode", e.errorCode).put("errorClass", e.getClass().getSimpleName());
            JSONArray causes = new JSONArray(); Throwable cause = e.getCause();
            for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause()) causes.put(cause.getClass().getSimpleName());
            stage.put("causeClasses", causes);
        }
    }
    private boolean waitForProgress(MediaController c, int index, long start, long timeout) {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < end) {
            if (main(() -> c.getCurrentMediaItemIndex() == index && c.getPlaybackState() == Player.STATE_READY
                    && c.isPlaying() && c.getCurrentPosition() >= start + 2000)) return true;
            if (error.get() != null) return false;
            SystemClock.sleep(250);
        }
        return false;
    }
    private int find(List<MediaItem> queue, String wanted) {
        int found = -1;
        for (int i = 0; i < queue.size(); i++) {
            CharSequence title = queue.get(i).mediaMetadata.title;
            String text = title == null ? "" : title.toString().toLowerCase(Locale.ROOT);
            if (text.contains(wanted)) { if (found >= 0) return -1; found = i; }
        }
        return found;
    }
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> value.set(action.get())); return value.get();
    }
}
