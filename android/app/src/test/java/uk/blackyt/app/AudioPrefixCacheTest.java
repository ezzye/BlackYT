// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@androidx.media3.common.util.UnstableApi
public class AudioPrefixCacheTest {
    private static final String ID = "abcdefghijk";
    private final YouTubeRepository.Rules rules = new YouTubeRepository.Rules(true, "music", "dub");
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private AudioPrefixCache cache;
    private Context context;
    private Uri uri;

    @Before public void setup() throws Exception {
        File directory = Files.createTempDirectory("blackyt-cache-test").toFile();
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getCacheDir() { return directory; }
        };
        cache = new AudioPrefixCache(context, now::get);
        uri = new Uri.Builder().scheme("blackyt").authority("youtube").appendPath(ID)
                .appendQueryParameter("music", "1").appendQueryParameter("include", "music")
                .appendQueryParameter("exclude", "dub").build();
        PlaybackFormatPins.clear(ID);
    }
    @After public void teardown() { cache.releaseForTest(); PlaybackFormatPins.clear(ID); }

    private void seed(byte[] data) throws Exception {
        assertTrue(cache.remember(ID, rules, "format-original-en"));
        String key = AudioPrefixCache.keyForFormat(ID, rules, "format-original-en");
        DataSource source = cache.factory(() -> new ByteArrayDataSource(data)).createDataSource();
        source.open(new DataSpec.Builder().setUri(uri).setKey(key).setLength(data.length).build());
        byte[] buffer = new byte[64];
        while (source.read(buffer, 0, buffer.length) != -1) { }
        source.close();
    }
    private static DataSource.Factory offline() {
        return () -> new DataSource() {
            public void addTransferListener(TransferListener listener) { }
            public long open(DataSpec spec) throws IOException { throw new IOException("offline"); }
            public int read(byte[] b, int o, int l) throws IOException { throw new IOException("offline"); }
            public Uri getUri() { return null; }
            public void close() { }
        };
    }
    private byte[] readOffline(long position, int length) throws Exception {
        DataSource source = new KeyedAudioCacheDataSource(cache, offline());
        try {
            source.open(new DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build());
            byte[] result = new byte[length]; int offset = 0;
            while (offset < length) { int n = source.read(result, offset, length - offset); if (n == -1) break; offset += n; }
            assertEquals(length, offset); return result;
        } finally { source.close(); }
    }
    @Test public void restartOfflineSeekAndNetworkMissPreserveBytes() throws Exception {
        seed(new byte[] {1,2,3,4,5,6,7,8});
        cache.releaseForTest(); cache = new AudioPrefixCache(context, now::get);
        assertArrayEquals(new byte[] {3,4,5}, readOffline(2, 3));
        try { readOffline(8, 1); fail("expected offline miss"); } catch (IOException expected) { }
        assertArrayEquals(new byte[] {1,2,3,4}, readOffline(0, 4));
        assertEquals(8, cache.cachedBytes(ID, rules));
    }
    @Test public void partialWriteSurvivesTimeoutAndResumesAtMissingRange() throws Exception {
        byte[] original = new byte[] {1,2,3,4,5,6,7,8};
        assertTrue(cache.remember(ID, rules, "format-original-en"));
        String key = AudioPrefixCache.keyForFormat(ID, rules, "format-original-en");
        DataSource.Factory interrupted = () -> new DataSource() {
            private boolean delivered;
            public void addTransferListener(TransferListener listener) { }
            public long open(DataSpec spec) { return 8; }
            public int read(byte[] b, int offset, int length) throws IOException {
                if (delivered) throw new java.net.SocketTimeoutException("temporary timeout");
                int count = Math.min(4, length);
                System.arraycopy(original, 0, b, offset, count);
                delivered = true;
                return count;
            }
            public Uri getUri() { return uri; }
            public void close() { }
        };
        DataSource writer = cache.factory(interrupted).createDataSource();
        try {
            writer.open(new DataSpec.Builder().setUri(uri).setKey(key).setLength(8).build());
            assertEquals(4, writer.read(new byte[8], 0, 8));
            try { writer.read(new byte[8], 0, 8); fail("expected timeout"); }
            catch (java.net.SocketTimeoutException expected) { }
        } finally { writer.close(); }
        assertEquals(4, cache.cachedBytes(ID, rules));
        assertArrayEquals(new byte[] {1,2,3,4}, readOffline(0, 4));

        AtomicLong requestedPosition = new AtomicLong(-1);
        DataSource.Factory resumed = () -> new DataSource() {
            final ByteArrayDataSource inner = new ByteArrayDataSource(original);
            public void addTransferListener(TransferListener listener) { inner.addTransferListener(listener); }
            public long open(DataSpec spec) throws IOException {
                requestedPosition.set(spec.position);
                return inner.open(spec);
            }
            public int read(byte[] b, int offset, int length) throws IOException { return inner.read(b, offset, length); }
            public Uri getUri() { return inner.getUri(); }
            public void close() throws IOException { inner.close(); }
        };
        DataSource next = new KeyedAudioCacheDataSource(cache, resumed);
        try {
            next.open(new DataSpec.Builder().setUri(uri).setLength(8).build());
            byte[] actual = new byte[8]; int offset = 0;
            while (offset < actual.length) {
                int count = next.read(actual, offset, actual.length - offset);
                if (count == -1) break;
                offset += count;
            }
            assertEquals(8, offset);
            assertArrayEquals(original, actual);
        } finally { next.close(); }
        assertEquals(4, requestedPosition.get());
        assertArrayEquals(original, readOffline(0, 8));
    }
    @Test public void formatMismatchInvalidatesButOrdinaryNetworkErrorRetainsPrefix() throws Exception {
        seed(new byte[] {1,2,3,4});
        try { readOffline(4, 1); fail("expected offline miss"); } catch (IOException expected) { }
        assertNotNull(cache.descriptor(ID, rules));
        assertEquals(4, cache.cachedBytes(ID, rules));
        DataSource.Factory changed = () -> new DataSource() {
            public void addTransferListener(TransferListener listener) { }
            public long open(DataSpec spec) throws IOException {
                throw new IOException("wrapped format change", new AudioFormatChangedException());
            }
            public int read(byte[] b, int o, int l) throws IOException { throw new IOException("unused"); }
            public Uri getUri() { return null; }
            public void close() { }
        };
        DataSource source = new KeyedAudioCacheDataSource(cache, changed);
        try {
            source.open(new DataSpec.Builder().setUri(uri).setPosition(4).setLength(1).build());
            fail("expected format mismatch");
        } catch (IOException expected) { }
        finally { source.close(); }
        assertNull(cache.descriptor(ID, rules));
        assertEquals(0, cache.statusBytes());
    }
    @Test public void identityExpiryAndRollbackFailClosed() throws Exception {
        seed(new byte[] {1,2,3});
        assertNull(cache.descriptor(ID, new YouTubeRepository.Rules(true, "music", "other")));
        assertNotEquals(AudioPrefixCache.keyForFormat(ID, rules, "original-en"),
                AudioPrefixCache.keyForFormat(ID, rules, "dub-en"));
        now.addAndGet(AudioCacheDescriptors.TTL_MS);
        assertNull(cache.descriptor(ID, rules)); assertEquals(0, cache.statusBytes());
        seed(new byte[] {4,5});
        now.decrementAndGet();
        assertNull(cache.descriptor(ID, rules)); assertEquals(0, cache.statusBytes());
    }
    @Test public void activeReadStopsOnExpiryOrClearAndDoesNotRenew() throws Exception {
        seed(new byte[] {1,2,3,4});
        DataSource source = new KeyedAudioCacheDataSource(cache, offline());
        source.open(new DataSpec.Builder().setUri(uri).setLength(4).build());
        assertEquals(1, source.read(new byte[1], 0, 1));
        now.addAndGet(AudioCacheDescriptors.TTL_MS - 1);
        assertTrue(cache.remember(ID, rules, "format-original-en"));
        now.incrementAndGet();
        try { source.read(new byte[1], 0, 1); fail("expired stream continued"); }
        catch (IOException expected) { }
        assertEquals(0, cache.statusBytes());
        seed(new byte[] {5,6});
        source = new KeyedAudioCacheDataSource(cache, offline());
        source.open(new DataSpec.Builder().setUri(uri).setLength(2).build());
        cache.clear();
        try { source.read(new byte[1], 0, 1); fail("cleared stream continued"); }
        catch (IOException expected) { }
        assertEquals(0, cache.statusBytes());
    }
    private AudioPrefixCache.OfflineStatus status() {
        return cache.offlineStatuses(java.util.Collections.singletonList(
                new PlaybackPreparation.Candidate(ID, rules))).get(AudioPrefixCache.identity(ID, rules));
    }
    private void seedRange(long position, int length, byte[] data) throws Exception {
        String key = AudioPrefixCache.keyForFormat(ID, rules, "format-original-en");
        DataSource source = cache.factory(() -> new ByteArrayDataSource(data)).createDataSource();
        try {
            source.open(new DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).setLength(length).build());
            byte[] buffer = new byte[16];
            while (source.read(buffer, 0, buffer.length) != -1) { }
        } finally { source.close(); }
    }
    @Test public void statusRequiresKnownLengthAndEveryByteAcrossRestart() throws Exception {
        assertTrue(cache.remember(ID, rules, "format-original-en"));
        byte[] data = new byte[10];
        seedRange(0, 4, data);
        assertFalse(status().ready);
        assertEquals(-1, status().contentLength);
        cache.rememberLength(ID, rules, "wrong-format", 4);
        assertFalse(status().ready);
        cache.rememberLength(ID, rules, "format-original-en", 10);
        assertFalse(status().ready);
        assertEquals(4, status().cachedBytes);
        seedRange(6, 4, data);
        assertFalse(status().ready); // Four bytes at each end still leave a hole.
        seedRange(4, 2, data);
        assertTrue(status().ready);
        assertEquals(10, status().contentLength);
        long expiry = status().expiresAtMs;
        cache.releaseForTest(); cache = new AudioPrefixCache(context, now::get);
        assertTrue(status().ready);
        assertEquals(expiry, status().expiresAtMs);
        assertTrue(cache.offlineStatuses(java.util.Collections.singletonList(new PlaybackPreparation.Candidate(
                ID, new YouTubeRepository.Rules(true, "music", "other")))).isEmpty());
        now.set(expiry);
        assertNull(status());
    }
    @Test public void clearEvictionAndFormatReplacementRemoveReadiness() throws Exception {
        seed(new byte[10]);
        cache.rememberLength(ID, rules, "format-original-en", 10);
        assertTrue(status().ready);
        assertTrue(cache.remember(ID, rules, "new-format"));
        assertFalse(status().ready);
        cache.clear();
        assertNull(status());
        cache.releaseForTest(); cache = new AudioPrefixCache(context, now::get, 4);
        seed(new byte[10]);
        cache.rememberLength(ID, rules, "format-original-en", 10);
        assertFalse(status().ready);
    }
    @Test public void lruBoundIsEnforced() throws Exception {
        cache.releaseForTest(); cache = new AudioPrefixCache(context, now::get, 1024);
        assertEquals(256L * 1024 * 1024, AudioPrefixCache.MAX_BYTES);
        assertEquals(24L * 1024 * 1024, AudioPrefixCache.MAX_PREFETCH_BYTES);
        seed(new byte[2048]);
        assertTrue(cache.statusBytes() <= 1024);
    }
}
