// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Actual local decoding and seeking; the streaming factory must never be touched. */
@RunWith(AndroidJUnit4.class)
@UnstableApi
public class OfflinePlaybackDeviceTest {
    @Test(timeout = 20000) public void offlinePlayerAdvancesAndSeeksWithoutStreaming() throws Exception {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(base.getCacheDir(), "offline-player-" + System.nanoTime());
        assertTrue(root.mkdirs());
        Context isolated = new ContextWrapper(base) { @Override public File getFilesDir() { return root; } };
        byte[] wav = wav();
        OfflineAudioStore store = new OfflineAudioStore(isolated, (id, rules) -> new Response.Builder()
                .request(new Request.Builder().url("https://example.com/").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(null, wav)).build());
        AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        AtomicInteger streamCalls = new AtomicInteger();
        DataSource.Factory forbidden = () -> {
            streamCalls.incrementAndGet();
            throw new AssertionError("Streaming factory was accessed");
        };
        YouTubeRepository.Track track = new YouTubeRepository.Track("abcdefghijk", "Test", "Artist", 30);
        YouTubeRepository.Rules rules = new YouTubeRepository.Rules(false, "", "");
        try {
            store.download(track, rules);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (store.open(track.id, rules) == null && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(10);
            assertNotNull("Fixture must be complete", store.open(track.id, rules));
            MediaItem item = NativeMediaItems.fromTrack(track, rules);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ExoPlayer player = new ExoPlayer.Builder(base)
                        .setLoadControl(PlaybackBufferPolicy.createLoadControl()).build();
                playerRef.set(player);
                player.setVolume(0);
                player.addListener(new Player.Listener() {
                    @Override public void onPlayerError(PlaybackException value) { error.set(value); }
                });
                player.setMediaSource(new ProgressiveMediaSource.Factory(
                        () -> new OfflineFirstAudioDataSource(store, forbidden)).createMediaSource(item));
                player.prepare();
                player.play();
            });
            assertTrue("Playback did not advance", waitForPosition(playerRef, 500, 6000));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> playerRef.get().seekTo(15000));
            assertTrue("Playback did not advance after seeking", waitForPosition(playerRef, 15500, 6000));
            assertNull(error.get());
            assertEquals(0, streamCalls.get());

            // An owned URI with different rules must fall back to streaming; arbitrary URIs are rejected first.
            DataSource source = new OfflineFirstAudioDataSource(store, forbidden);
            Uri mismatch = NativeMediaItems.fromTrack(track, new YouTubeRepository.Rules(true, "", ""))
                    .localConfiguration.uri;
            try { source.open(new DataSpec(mismatch)); fail("Expected streaming fallback"); }
            catch (AssertionError expected) { assertEquals(1, streamCalls.get()); }
            try { source.open(new DataSpec(Uri.parse("https://example.com/audio"))); fail("Expected rejection"); }
            catch (IOException expected) { assertEquals(1, streamCalls.get()); }
        } finally {
            if (playerRef.get() != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> playerRef.get().release());
            store.shutdownForTests();
            delete(root);
        }
    }

    private static boolean waitForPosition(AtomicReference<ExoPlayer> player, long target, long timeout)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        AtomicReference<Long> position = new AtomicReference<>(0L);
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> position.set(player.get().getCurrentPosition()));
            if (position.get() >= target) return true;
            Thread.sleep(50);
        }
        return false;
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private static byte[] wav() {
        int audio = 30 * 16000;
        byte[] data = new byte[44 + audio];
        text(data, 0, "RIFF"); integer(data, 4, 36 + audio); text(data, 8, "WAVE");
        text(data, 12, "fmt "); integer(data, 16, 16); half(data, 20, 1); half(data, 22, 1);
        integer(data, 24, 8000); integer(data, 28, 16000); half(data, 32, 2); half(data, 34, 16);
        text(data, 36, "data"); integer(data, 40, audio);
        return data;
    }
    private static void text(byte[] data, int at, String value) {
        for (int i = 0; i < value.length(); i++) data[at + i] = (byte) value.charAt(i);
    }
    private static void half(byte[] data, int at, int value) {
        data[at] = (byte) value; data[at + 1] = (byte) (value >>> 8);
    }
    private static void integer(byte[] data, int at, int value) {
        half(data, at, value); half(data, at + 2, value >>> 16);
    }
}
