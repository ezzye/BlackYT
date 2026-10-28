// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public class OfflineAudioStoreTest {
    private File root, directory;
    private Context isolated;
    private final java.util.List<OfflineAudioStore> stores = new java.util.ArrayList<>();
    private static final String ID = "abcdefghijk";

    @Before public void setup() {
        Context base = ApplicationProvider.getApplicationContext();
        root = new File(base.getCacheDir(), "offline-test-" + System.nanoTime());
        directory = new File(root, "offline-audio");
        assertTrue(directory.mkdirs());
        isolated = new ContextWrapper(base) { @Override public File getFilesDir() { return root; } };
    }
    @After public void cleanup() {
        for (OfflineAudioStore store : stores) store.shutdownForTests();
        delete(root);
    }
    private OfflineAudioStore store() {
        OfflineAudioStore value = new OfflineAudioStore(isolated); stores.add(value); return value;
    }
    private OfflineAudioStore store(OfflineAudioStore.TestSource source) {
        OfflineAudioStore value = new OfflineAudioStore(isolated, source); stores.add(value); return value;
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private void write(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
    }
    private void index(String status, long expiry, long length) throws Exception {
        JSONObject item = new JSONObject().put("id", ID).put("title", "Track")
                .put("channel", "Artist").put("music", true).put("include", "Artist")
                .put("exclude", "").put("status", status).put("total", length).put("expiry", expiry);
        write(new File(directory, "index.json"), new JSONArray().put(item).toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test public void readyFileRequiresMatchingRulesAndValidLength() throws Exception {
        index("READY", System.currentTimeMillis() + 60000, 3);
        File audio = new File(directory, ID + ".audio");
        write(audio, new byte[] {1, 2, 3});
        OfflineAudioStore store = store();
        assertEquals(audio, store.open(ID, new YouTubeRepository.Rules(true, "Artist", "")));
        assertNull(store.open(ID, new YouTubeRepository.Rules(false, "Artist", "")));
        write(audio, new byte[] {1});
        assertNull(store.open(ID, new YouTubeRepository.Rules(true, "Artist", "")));
        assertTrue(store.list().isEmpty());
    }

    @Test public void expiredAndInterruptedFilesAreNeverReady() throws Exception {
        index("READY", System.currentTimeMillis() - 1, 2);
        File audio = new File(directory, ID + ".audio");
        write(audio, new byte[] {1, 2});
        OfflineAudioStore expired = store();
        assertTrue(expired.list().isEmpty());
        assertFalse(audio.exists());
        index("DOWNLOADING", 0, 2);
        File partial = new File(directory, ID + "-test.part");
        write(partial, new byte[] {1});
        OfflineAudioStore interrupted = store();
        assertEquals(OfflineAudioStore.Status.FAILED, interrupted.list().get(0).status);
        assertFalse(partial.exists());
        assertNull(interrupted.open(ID, new YouTubeRepository.Rules(true, "Artist", "")));
    }
    private Response response(byte[] bytes, long declaredLength) {
        ResponseBody body = new ResponseBody() {
            private final BufferedSource source = new Buffer().write(bytes);
            @Override public MediaType contentType() { return null; }
            @Override public long contentLength() { return declaredLength; }
            @Override public BufferedSource source() { return source; }
        };
        return new Response.Builder().request(new Request.Builder().url("https://example.com/").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build();
    }
    private OfflineAudioStore.Entry awaitFinished(OfflineAudioStore store) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (!store.list().isEmpty()) {
                OfflineAudioStore.Entry entry = store.list().get(0);
                if (entry.status == OfflineAudioStore.Status.READY || entry.status == OfflineAudioStore.Status.FAILED)
                    return entry;
            }
            Thread.sleep(10);
        }
        fail("Transfer did not finish");
        return null;
    }
    private YouTubeRepository.Track track() { return new YouTubeRepository.Track(ID, "Track", "Artist", 10); }
    private YouTubeRepository.Rules rules() { return new YouTubeRepository.Rules(true, "Artist", ""); }

    @Test public void completeTransferSurvivesRestartAndSupportsRandomReads() throws Exception {
        byte[] data = new byte[] {1, 2, 3, 4, 5};
        OfflineAudioStore store = store( (id, rules) -> response(data, data.length));
        store.download(track(), rules());
        assertEquals(OfflineAudioStore.Status.READY, awaitFinished(store).status);
        OfflineAudioStore restored = store();
        File file = restored.open(ID, rules());
        assertNotNull(file);
        try (RandomAccessFile audio = new RandomAccessFile(file, "r")) {
            audio.seek(2); assertEquals(3, audio.read()); assertEquals(4, audio.read());
        }
    }

    @Test public void truncatedAndOversizedTransfersAreNotReady() throws Exception {
        OfflineAudioStore store = store( (id, rules) -> response(new byte[] {1}, 2));
        store.download(track(), rules());
        assertEquals(OfflineAudioStore.Status.FAILED, awaitFinished(store).status);
        assertNull(store.open(ID, rules()));
        OfflineAudioStore oversized = store(
                (id, rules) -> response(new byte[] {1}, OfflineAudioStore.ITEM_LIMIT_BYTES + 1));
        oversized.download(track(), rules());
        assertEquals(OfflineAudioStore.Status.FAILED, awaitFinished(oversized).status);
        assertNull(oversized.open(ID, rules()));
    }

    @Test public void cancelWhilePausedDoesNotStartOrSaveTransfer() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean opened = new java.util.concurrent.atomic.AtomicBoolean();
        OfflineAudioStore store = store( (id, rules) -> {
            opened.set(true); return response(new byte[] {1}, 1);
        });
        store.setPlaybackBusy(true);
        store.download(track(), rules());
        store.cancel(ID);
        store.setPlaybackBusy(false);
        assertTrue(store.list().isEmpty());
        assertFalse(opened.get());
        assertNull(store.open(ID, rules()));
    }

    @Test public void persistedItemsCountAgainstTotalCap() throws Exception {
        JSONArray saved = new JSONArray();
        for (String id : new String[] {"abcdefghijk", "lmnopqrstuv"}) {
            saved.put(new JSONObject().put("id", id).put("status", "READY")
                    .put("total", OfflineAudioStore.ITEM_LIMIT_BYTES)
                    .put("expiry", System.currentTimeMillis() + 60000));
            try (RandomAccessFile file = new RandomAccessFile(new File(directory, id + ".audio"), "rw")) {
                file.setLength(OfflineAudioStore.ITEM_LIMIT_BYTES);
            }
        }
        write(new File(directory, "index.json"), saved.toString().getBytes(StandardCharsets.UTF_8));
        OfflineAudioStore store = store( (id, rules) -> response(new byte[] {1}, 1));
        String nextId = "01234567890";
        store.download(new YouTubeRepository.Track(nextId, "Next", "Artist", 1), rules());
        long deadline = System.currentTimeMillis() + 5000;
        OfflineAudioStore.Entry next = null;
        while (System.currentTimeMillis() < deadline) {
            for (OfflineAudioStore.Entry entry : store.list()) if (entry.track.id.equals(nextId)) next = entry;
            if (next != null && next.status == OfflineAudioStore.Status.FAILED) break;
            Thread.sleep(10);
        }
        assertNotNull(next);
        assertEquals(OfflineAudioStore.Status.FAILED, next.status);
        assertTrue(next.error.contains("full"));
        assertNull(store.open(nextId, rules()));
    }

    @Test public void entriesAreBoundedAndOrphansAreRemoved() throws Exception {
        JSONArray saved = new JSONArray();
        for (int i = 0; i < OfflineAudioStore.MAX_ENTRIES; i++) {
            String id = String.format(java.util.Locale.ROOT, "%011d", i);
            saved.put(new JSONObject().put("id", id).put("status", "FAILED"));
        }
        write(new File(directory, "index.json"), saved.toString().getBytes(StandardCharsets.UTF_8));
        File orphan = new File(directory, "abcdefghijk.audio");
        write(orphan, new byte[] {1});
        OfflineAudioStore store = store();
        assertEquals(OfflineAudioStore.MAX_ENTRIES, store.list().size());
        assertFalse(orphan.exists());
        try {
            store.download(new YouTubeRepository.Track("abcdefghijk", "New", "Artist", 1), rules());
            fail("Expected the entry limit");
        } catch (IllegalStateException expected) {
            assertEquals(OfflineAudioStore.MAX_ENTRIES, store.list().size());
        }
    }

    @Test public void repeatedRequeueDoesNotAccumulateCancelledTasks() throws Exception {
        OfflineAudioStore store = store((id, rules) -> response(new byte[] {1}, 1));
        store.setPlaybackBusy(true);
        store.download(track(), rules());
        long deadline = System.currentTimeMillis() + 5000;
        while (store.list().get(0).status != OfflineAudioStore.Status.DOWNLOADING
                && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(OfflineAudioStore.Status.DOWNLOADING, store.list().get(0).status);
        for (int i = 0; i < 500; i++) {
            store.download(track(), rules());
            assertTrue("Cancelled work accumulated", store.pendingTaskCountForTests() <= 1);
            assertEquals(1, store.list().size());
        }
        store.cancel(ID);
        assertEquals(0, store.pendingTaskCountForTests());
        assertTrue(store.list().isEmpty());
    }

    @Test public void readyCopySurvivesSameOrDifferentRulesRequest() throws Exception {
        long expiry = System.currentTimeMillis() + 60000;
        index("READY", expiry, 3);
        File audio = new File(directory, ID + ".audio");
        write(audio, new byte[] {1, 2, 3});
        java.util.concurrent.atomic.AtomicBoolean opened = new java.util.concurrent.atomic.AtomicBoolean();
        OfflineAudioStore store = store((id, rules) -> {
            opened.set(true); return response(new byte[] {9}, 1);
        });
        store.download(track(), rules());
        assertEquals(OfflineAudioStore.Status.READY, store.list().get(0).status);
        assertEquals(expiry, store.list().get(0).expiresAtMs);
        assertEquals(audio, store.open(ID, rules()));
        try {
            store.download(track(), new YouTubeRepository.Rules(false, "", ""));
            fail("Different rules should require explicit removal");
        } catch (IllegalStateException expected) {
            assertEquals("Remove the existing download before replacing it.", expected.getMessage());
        }
        assertEquals(audio, store.open(ID, rules()));
        assertEquals(3, audio.length());
        assertFalse(opened.get());
    }

}
