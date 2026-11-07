// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.PowerManager;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Coordinated real-device check. Network and screen changes belong to the host. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class JourneyConnectionDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final JSONObject result = new JSONObject();
    private String phase = "START";

    @Test public void cachedScreenOffJourneyAndConnectionRecovery() throws Throwable {
        SharedPreferences prefs = context.getSharedPreferences("blackyt_listening_store_default", Context.MODE_PRIVATE);
        boolean hadData = prefs.contains("data_v1");
        String savedData = prefs.getString("data_v1", null);
        SharedPreferences backup = context.getSharedPreferences("blackyt_journey_test_backup", Context.MODE_PRIVATE);
        if (backup.getBoolean("pending", false)) {
            if (backup.getBoolean("hadData", false)) prefs.edit().putString("data_v1", backup.getString("original", null)).commit();
            else prefs.edit().remove("data_v1").commit();
            hadData = prefs.contains("data_v1"); savedData = prefs.getString("data_v1", null);
        }
        assertTrue(backup.edit().putBoolean("pending", true).putBoolean("hadData", hadData).putString("original", savedData).commit());
        final int originalMode = PlaybackPreparation.get(context).mode();
        AtomicReference<ListenableFuture<MediaController>> future = new AtomicReference<>();
        MediaController player = null;
        Float originalVolume = null;
        Throwable failure = null;
        try {
            Diagnostics.init(context);
            // Isolate progress writes without exporting the owner's saved Library.
            assertTrue(prefs.edit().remove("data_v1").commit()); new ListeningStore(context);
            if (originalMode == PlaybackPreparation.OFF) PlaybackPreparation.get(context).setMode(PlaybackPreparation.UNMETERED);
            result.put("status", "running").put("startedAtMs", System.currentTimeMillis());
            main(() -> { future.set(new MediaController.Builder(context, new SessionToken(context,
                    new ComponentName(context, PlaybackService.class))).buildAsync()); return true; });
            player = future.get().get(25, TimeUnit.SECONDS);
            final MediaController c = player;
            originalVolume = main(c::getVolume);
            main(() -> { assertTrue(c.isCommandAvailable(Player.COMMAND_SET_VOLUME));
                c.setVolume(0f); c.stop(); c.clearMediaItems(); return true; });
            assertEquals(0f, main(c::getVolume), 0f);
            assertTrue("Audio preparation must be enabled for cached playback",
                    PlaybackPreparation.get(context).mode() != PlaybackPreparation.OFF);
            assertTrue("A validated connection is required for preflight", online());
            YouTubeRepository repo = YouTubeRepository.get();
            YouTubeRepository.Rules music = new YouTubeRepository.Rules(true, "", "");
            YouTubeRepository.Rules video = new YouTubeRepository.Rules(false, "", "");
            YouTubeRepository.Track first = choose(repo, "U2 With Or Without You", music);
            YouTubeRepository.Track second = choose(repo, "Prince When Doves Cry", music);
            AudioPrefixCache cache = AudioPrefixCache.get(context);
            List<YouTubeRepository.Track> uncached = new ArrayList<>();
            for (YouTubeRepository.Track candidate : repo.searchPage("Wes Roth AI", video, null).tracks)
                if (candidate.durationSeconds > 240 && cache.cachedBytes(candidate.id, video) == 0) uncached.add(candidate);
            assertFalse("No uncached recovery candidate", uncached.isEmpty());
            YouTubeRepository.Track recovery = DeviceStreamChecks.available(uncached, video);
            DeviceStreamChecks.available(Arrays.asList(recovery), video);
            assertEquals("Recovery item already has cached bytes; cannot prove an uncached request", 0,
                    cache.cachedBytes(recovery.id, video));
            result.put("firstId", first.id).put("firstDurationSeconds", first.durationSeconds)
                    .put("secondId", second.id).put("secondDurationSeconds", second.durationSeconds)
                    .put("recoveryId", recovery.id).put("recoveryStartMs", 205000);
            List<YouTubeRepository.Track> tracks = Arrays.asList(first, second);
            List<YouTubeRepository.Resolved> sources = new ArrayList<>();
            org.json.JSONArray prefetchEvidence = new org.json.JSONArray();
            result.put("prefetch", prefetchEvidence);
            for (YouTubeRepository.Track track : tracks) {
                YouTubeRepository.Resolved resolved = repo.resolve(track.id, music, true);
                sources.add(resolved);
                prefetchEvidence.put(new JSONObject().put("id", track.id)
                        .put("mimeType", resolved.mimeType == null ? JSONObject.NULL : resolved.mimeType)
                        .put("formatKey", resolved.formatKey)
                        .put("resolvedDurationSeconds", resolved.durationSeconds)
                        .put("cachedBytesBefore", cache.cachedBytes(track.id, music)));
            }
            for (int i = 0; i < tracks.size(); i++) {
                YouTubeRepository.Track track = tracks.get(i);
                JSONObject evidence = prefetchEvidence.getJSONObject(i);
                try {
                    cache.prefetch(track.id, music, sources.get(i), AudioPrefixCache.MAX_PREFETCH_BYTES, this::online);
                    evidence.put("prefetchReturned", true);
                } catch (Throwable t) {
                    evidence.put("prefetchFailureClass", t.getClass().getSimpleName());
                    throw t;
                } finally {
                    evidence.put("cachedBytesAfter", cache.cachedBytes(track.id, music));
                }
                assertTrue("No cached audio after prefetch", cache.cachedBytes(track.id, music) > 0);
                AudioPrefixCache.OfflineStatus readiness = cache.offlineStatuses(
                        java.util.Collections.singletonList(new PlaybackPreparation.Candidate(track.id, music)))
                        .get(AudioPrefixCache.identity(track.id, music));
                assertNotNull("Prefetched item has no readiness snapshot", readiness);
                evidence.put("readyOffline", readiness.ready).put("contentLength", readiness.contentLength)
                        .put("readinessCachedBytes", readiness.cachedBytes).put("expiresAtMs", readiness.expiresAtMs);
                assertTrue("Full offline journey requires a known length and all audio cached", readiness.ready);
                assertTrue("Connection was lost during prefetch", online());
            }
            result.put("firstCachedBytes", cache.cachedBytes(first.id, music))
                    .put("secondCachedBytes", cache.cachedBytes(second.id, music));
            AtomicReference<PlaybackException> error = new AtomicReference<>();
            AtomicInteger transition = new AtomicInteger(-1);
            main(() -> { c.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException e) { error.set(e); }
                @Override public void onMediaItemTransition(MediaItem item, int reason) { transition.set(reason); }
            });
                c.setMediaItems(Arrays.asList(NativeMediaItems.fromTrack(first, music),
                        NativeMediaItems.fromTrack(second, music)));
                c.prepare(); c.play(); return true; });
            await(() -> main(() -> c.isPlaying() && c.getCurrentPosition() > 2000), 60000, "Initial playback did not start");
            result.put("onlineStartPositionMs", main(c::getCurrentPosition));
            phase("READY_OFFLINE");
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager lock = context.getSystemService(KeyguardManager.class);
            await(() -> !online() && !power.isInteractive() && lock.isKeyguardLocked(), 120000, "Host did not turn off connectivity and screen");
            long sampleStartTime = SystemClock.elapsedRealtime();
            long sampleStartPosition = main(c::getCurrentPosition);
            while (SystemClock.elapsedRealtime() - sampleStartTime < 120000) {
                assertFalse("Validated network returned during offline sample", online());
                assertFalse("Screen turned on during offline sample", power.isInteractive());
                assertTrue("Phone unlocked during offline sample", lock.isKeyguardLocked());
                assertNull("Playback error during offline sample", error.get());
                assertTrue("Playback stopped during offline sample", main(c::isPlaying));
                assertEquals("First track ended before the two-minute sample", 0, (int) main(c::getCurrentMediaItemIndex));
                SystemClock.sleep(500);
            }
            long sampleEndPosition = main(c::getCurrentPosition);
            assertTrue("Cached playback did not advance for two minutes", sampleEndPosition - sampleStartPosition > 105000);
            result.put("offlineSampleMs", SystemClock.elapsedRealtime() - sampleStartTime)
                    .put("offlineAdvanceMs", sampleEndPosition - sampleStartPosition)
                    .put("continuouslyScreenOffAndLocked", true).put("continuouslyOffline", true);
            long endDeadline = SystemClock.elapsedRealtime() + 300000;
            while (SystemClock.elapsedRealtime() < endDeadline && !main(() -> c.getCurrentMediaItemIndex() == 1 && c.isPlaying() && c.getCurrentPosition() > 2000)) {
                assertFalse("Network returned before automatic transition", online());
                assertFalse("Screen turned on before automatic transition", power.isInteractive());
                assertNull("Playback error before automatic transition", error.get());
                SystemClock.sleep(500);
            }
            assertTrue("Next track did not play naturally offline", main(() -> c.getCurrentMediaItemIndex() == 1 && c.isPlaying() && c.getCurrentPosition() > 2000));
            assertFalse(online()); assertFalse(power.isInteractive());
            assertTrue(lock.isKeyguardLocked());
            assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, transition.get());
            assertEquals(2, (int) main(c::getMediaItemCount));
            assertEquals(second.id, main(() -> c.getCurrentMediaItem().mediaId));
            result.put("naturalTransition", true).put("transitionReason", transition.get())
                    .put("nextPositionMs", main(c::getCurrentPosition)).put("transitionAtMs", System.currentTimeMillis());
            assertEquals("Recovery item acquired cached bytes before test", 0, cache.cachedBytes(recovery.id, video));
            main(() -> { c.addMediaItem(NativeMediaItems.fromTrack(recovery, video));
                c.seekTo(2, 205000); c.prepare(); c.play(); return true; });
            long offlineWait = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - offlineWait < 10000) {
                assertFalse("Network returned before recovery phase", online());
                assertFalse("Screen turned on before recovery phase", power.isInteractive());
                assertFalse("Uncached item unexpectedly played offline", main(c::isPlaying));
                SystemClock.sleep(250);
            }
            result.put("offlineRecoveryWaitMs", SystemClock.elapsedRealtime() - offlineWait)
                    .put("offlineRecoveryPositionMs", main(c::getCurrentPosition))
                    .put("offlineRecoveryErrorClass", error.get() == null ? JSONObject.NULL : error.get().getClass().getSimpleName());
            long signalled = SystemClock.elapsedRealtime();
            phase("WAITING_FOR_NETWORK");
            await(() -> main(() -> c.isPlaying() && c.getCurrentPosition() >= 207000), 120000,
                    "Automatic recovery did not resume the uncached item");
            assertEquals(3, (int) main(c::getMediaItemCount));
            assertEquals(2, (int) main(c::getCurrentMediaItemIndex));
            assertEquals(recovery.id, main(() -> c.getCurrentMediaItem().mediaId));
            result.put("recoveryLatencyFromSignalMs", SystemClock.elapsedRealtime() - signalled)
                    .put("recoveryPositionMs", main(c::getCurrentPosition)).put("recoveryQueueCount", 3)
                    .put("recoveryIndex", 2).put("status", "passed");
            phase("COMPLETE");
        } catch (Throwable t) {
            failure = t;
            result.put("status", "failed").put("failurePhase", phase).put("failureClass", t.getClass().getSimpleName());
        } finally {
            if (player != null) {
                final MediaController c = player; final Float volume = originalVolume;
                try { main(() -> { c.stop(); c.clearMediaItems(); if (volume != null) c.setVolume(volume); c.release(); return true; }); }
                catch (Throwable cleanup) { if (failure == null) failure = cleanup; }
            }
            SystemClock.sleep(500);
            boolean restored = hadData ? prefs.edit().putString("data_v1", savedData).commit() : prefs.edit().remove("data_v1").commit();
            restored &= java.util.Objects.equals(savedData, prefs.getString("data_v1", null));
            PlaybackPreparation.get(context).setMode(originalMode);
            if (restored) backup.edit().clear().commit();
            if (!restored && failure == null) failure = new AssertionError("Listening data restoration failed");
            if (failure != null) result.put("status", "failed").put("failurePhase", phase).put("failureClass", failure.getClass().getSimpleName());
            result.put("listeningDataRestored", restored).put("finishedAtMs", System.currentTimeMillis());
            File dir = context.getExternalFilesDir("journey-check");
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new AssertionError("Evidence directory unavailable");
            try (FileOutputStream out = new FileOutputStream(new File(dir, "result.json"))) {
                out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (failure != null) throw failure;
    }

    private YouTubeRepository.Track choose(YouTubeRepository repo, String query, YouTubeRepository.Rules rules) throws Exception {
        List<YouTubeRepository.Track> candidates = new ArrayList<>();
        for (YouTubeRepository.Track track : repo.searchPage(query, rules, null).tracks)
            if (track.durationSeconds >= 180 && track.durationSeconds <= 330) candidates.add(track);
        candidates.sort(Comparator.comparingLong(t -> t.durationSeconds));
        assertFalse("No suitable public candidate", candidates.isEmpty());
        return DeviceStreamChecks.available(candidates, rules);
    }
    private boolean online() {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    private void phase(String value) throws Exception {
        phase = value; result.put("phase", value).put(value + "AtMs", System.currentTimeMillis());
        Bundle status = new Bundle(); status.putString("journey_phase", value);
        instrumentation.sendStatus(2, status); System.out.println("JOURNEY_PHASE=" + value);
    }
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> value.set(action.get())); return value.get();
    }
    private static void await(Supplier<Boolean> condition, long timeout, String message) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.get()) return; SystemClock.sleep(250); }
        throw new AssertionError(message);
    }
}
