// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Actual decoding and playback, without a network or playback service. */
@RunWith(AndroidJUnit4.class)
@UnstableApi
public final class BufferingDeviceTest {
    private static final long RUN_MS = 32_000;
    private static final Uri FIXTURE_URI = Uri.parse("test-audio://buffering/silence.wav");

    @Test(timeout = 80_000)
    public void intermittentReadsCauseFewerStuttersWithPlaybackPolicy() throws Exception {
        Result defaults = play(new DefaultLoadControl());
        Result improved = play(PlaybackBufferPolicy.createLoadControl());
        Log.i("BufferingDeviceTest", "default: " + defaults + "; policy: " + improved);
        assertNull("Default playback failed", defaults.error);
        assertNull("Policy playback failed", improved.error);
        assertTrue("Default should reproduce repeated post-start buffering: " + defaults,
                defaults.bufferingEvents >= 2);
        assertTrue("Policy should reduce post-start buffering: default=" + defaults
                + ", policy=" + improved, improved.bufferingEvents < defaults.bufferingEvents);
        assertTrue("Policy must actually play audio: " + improved, improved.positionMs >= 10_000);
    }

    private static Result play(DefaultLoadControl loadControl) throws Exception {
        Result result = new Result();
        long startMs = SystemClock.elapsedRealtime();
        AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ExoPlayer player = new ExoPlayer.Builder(
                    InstrumentationRegistry.getInstrumentation().getTargetContext())
                    .setLoadControl(loadControl).build();
            playerRef.set(player);
            player.setVolume(0f);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY && !result.started) {
                        result.started = true;
                        result.startupMs = SystemClock.elapsedRealtime() - startMs;
                    }
                    if (state == Player.STATE_BUFFERING && result.started) result.bufferingEvents++;
                }
                @Override public void onPlayerError(PlaybackException error) { result.error = error; }
            });
            player.setMediaSource(new ProgressiveMediaSource.Factory(BurstyWavSource::new)
                    .createMediaSource(MediaItem.fromUri(FIXTURE_URI)));
            player.prepare();
            player.play();
        });
        try { SystemClock.sleep(RUN_MS); }
        finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ExoPlayer player = playerRef.get();
                result.positionMs = player.getCurrentPosition();
                player.release();
            });
        }
        return result;
    }

    private static final class Result {
        boolean started;
        int bufferingEvents;
        long positionMs;
        long startupMs;
        PlaybackException error;
        @Override public String toString() {
            return "started=" + started + ", buffering=" + bufferingEvents + ", startupMs=" + startupMs + ", positionMs=" + positionMs;
        }
    }

    /** 40 seconds of PCM WAV, with outages at 5, 10, 15 and 20 seconds of data. */
    private static final class BurstyWavSource implements DataSource {
        private static final int BYTES_PER_SECOND = 16_000;
        private static final int HEADER_BYTES = 44;
        private static final int AUDIO_BYTES = 40 * BYTES_PER_SECOND;
        private final byte[] header = wavHeader();
        private final boolean[] outages = new boolean[4];
        private long position;
        private long end;
        private boolean open;
        @Override public void addTransferListener(TransferListener listener) { }
        @Override public long open(DataSpec spec) throws IOException {
            if (!FIXTURE_URI.equals(spec.uri)) throw new IOException("Unexpected fixture URI: " + spec.uri);
            position = spec.position;
            end = Math.min(HEADER_BYTES + AUDIO_BYTES,
                    spec.length == C.LENGTH_UNSET ? Long.MAX_VALUE : position + spec.length);
            if (position > end) throw new IOException("Position past fixture end");
            open = true;
            return end - position;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (position >= end) return C.RESULT_END_OF_INPUT;
            for (int i = 0; i < outages.length; i++) {
                long boundary = HEADER_BYTES + (long) (i + 1) * 5 * BYTES_PER_SECOND;
                if (!outages[i] && position >= boundary) {
                    outages[i] = true;
                    try { Thread.sleep(6_000); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Read interrupted", e);
                    }
                }
                if (!outages[i] && position < boundary) {
                    length = (int) Math.min(length, boundary - position);
                    break;
                }
            }
            int count = (int) Math.min(length, end - position);
            Arrays.fill(target, offset, offset + count, (byte) 0);
            if (position < HEADER_BYTES) {
                int headerCount = (int) Math.min(count, HEADER_BYTES - position);
                System.arraycopy(header, (int) position, target, offset, headerCount);
            }
            position += count;
            return count;
        }
        @Override public Uri getUri() { return open ? FIXTURE_URI : null; }
        @Override public void close() { open = false; }
        private static byte[] wavHeader() {
            byte[] h = new byte[HEADER_BYTES];
            putText(h, 0, "RIFF"); putInt(h, 4, 36 + AUDIO_BYTES); putText(h, 8, "WAVE");
            putText(h, 12, "fmt "); putInt(h, 16, 16); putShort(h, 20, 1);
            putShort(h, 22, 1); putInt(h, 24, 8_000); putInt(h, 28, BYTES_PER_SECOND);
            putShort(h, 32, 2); putShort(h, 34, 16); putText(h, 36, "data");
            putInt(h, 40, AUDIO_BYTES);
            return h;
        }
        private static void putText(byte[] h, int p, String s) {
            for (int i = 0; i < s.length(); i++) h[p + i] = (byte) s.charAt(i);
        }
        private static void putShort(byte[] h, int p, int n) {
            h[p] = (byte) n; h[p + 1] = (byte) (n >>> 8);
        }
        private static void putInt(byte[] h, int p, int n) {
            putShort(h, p, n); putShort(h, p + 2, n >>> 16);
        }
    }
}
