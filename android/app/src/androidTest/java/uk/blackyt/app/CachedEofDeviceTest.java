// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Reads the existing saved track silently, with all upstream requests refused. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class CachedEofDeviceTest {
    @Test public void savedYellowReadsOfflineThroughEof() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject result = new JSONObject().put("status", "running");
        String stage = "identify";
        long bytes = 0;
        Throwable failure = null;
        String id = null;
        PlaybackPreparation preparation = PlaybackPreparation.get(context);
        java.lang.reflect.Field busyField = PlaybackPreparation.class.getDeclaredField("playbackBusy");
        busyField.setAccessible(true);
        boolean originalBusy = busyField.getBoolean(preparation);
        DataSource source = null;
        try {
            preparation.setPlaybackBusy(true);
            PlaylistStore.Playlist chosen = null;
            for (PlaylistStore.Playlist playlist : new PlaylistStore(context).list()) {
                if ("coldplay".equalsIgnoreCase(playlist.name.trim())) {
                    assertNull("Ambiguous playlist", chosen); chosen = playlist;
                }
            }
            assertNotNull("Playlist unavailable", chosen);
            PlaylistStore.Entry selected = null;
            for (PlaylistStore.Entry entry : chosen.entries) {
                if (entry.track.title != null && entry.track.title.toLowerCase(Locale.ROOT).contains("yellow")) {
                    assertNull("Ambiguous track", selected); selected = entry;
                }
            }
            assertNotNull("Track unavailable", selected);
            MediaItem item = NativeMediaItems.fromTrack(selected.track, selected.rules);
            NativeMediaItems.OwnedItem owned = NativeMediaItems.parse(item);
            id = owned.id;
            PlaybackFormatPins.clear(id);
            stage = "readiness";
            AudioPrefixCache cache = AudioPrefixCache.get(context);
            AudioPrefixCache.OfflineStatus status = cache.offlineStatuses(Collections.singletonList(
                    new PlaybackPreparation.Candidate(owned.id, owned.rules)))
                    .get(AudioPrefixCache.identity(owned.id, owned.rules));
            assertNotNull("No cache status", status);
            result.put("readyOffline", status.ready).put("contentLength", status.contentLength)
                    .put("cachedBytes", status.cachedBytes);
            assertTrue("Track is not ready offline", status.ready);
            DataSource.Factory unavailable = () -> new DataSource() {
                public void addTransferListener(TransferListener listener) { }
                public long open(DataSpec spec) throws IOException { throw new IOException("upstream disabled"); }
                public int read(byte[] b, int offset, int length) throws IOException { throw new IOException("upstream disabled"); }
                public Uri getUri() { return null; }
                public void close() { }
            };
            source = new KeyedAudioCacheDataSource(cache, unavailable);
            stage = "open";
            source.open(new DataSpec.Builder().setUri(item.localConfiguration.uri).build());
            stage = "read";
            byte[] buffer = new byte[32768];
            int count;
            while ((count = source.read(buffer, 0, buffer.length)) != -1) {
                assertTrue("Read made no progress", count > 0);
                bytes += count;
                assertTrue("Read exceeded validated length", bytes <= status.contentLength);
            }
            stage = "eof";
            assertEquals("Unexpected cached length", status.contentLength, bytes);
            result.put("status", "passed").put("reachedEof", true);
        } catch (Throwable t) {
            failure = t;
            result.put("status", "failed").put("failureClass", t.getClass().getSimpleName());
        } finally {
            if (source != null) try { source.close(); } catch (Throwable t) {
                if (failure == null) { failure = t; result.put("status", "failed").put("failureClass", t.getClass().getSimpleName()); }
            }
            if (id != null) PlaybackFormatPins.clear(id);
            preparation.setPlaybackBusy(originalBusy);
            result.put("stage", stage).put("bytesRead", bytes);
            File dir = context.getExternalFilesDir("playlist-cache-check");
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new AssertionError("Evidence directory unavailable");
            try (FileOutputStream out = new FileOutputStream(new File(dir, "eof-result.json"))) {
                out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (failure != null) throw failure;
    }
}
