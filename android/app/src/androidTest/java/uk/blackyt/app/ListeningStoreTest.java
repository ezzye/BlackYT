// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ListeningStoreTest {
    private static final String ID = "abc_DEF-123";
    private String currentNamespace;
    private final java.util.List<String> testNamespaces=new java.util.ArrayList<>();
    @org.junit.After public void removeTestNamespaces(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();for(String name:testNamespaces)c.deleteSharedPreferences("blackyt_listening_store_"+name);}
    private ListeningStore store() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        currentNamespace = "instrumentation-" + UUID.randomUUID();
        testNamespaces.add(currentNamespace);
        return new ListeningStore(context, currentNamespace);
    }

    @Test public void episodePersistsAcrossInstancesAndProgressUpdatesWithoutLosingMetadata() {
        ListeningStore first = store();
        first.saveEpisode(new YouTubeRepository.Track(ID, "A song", "An artist", 300,
                "https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg", "https://youtube.com/@artist"),
                new YouTubeRepository.Rules(true, "live", "cover"), 0);
        first.updateProgressIfSaved(ID, 120000, 300000);
        ListeningStore.SavedEpisode saved = new ListeningStore(InstrumentationRegistry.getInstrumentation().getTargetContext(), getNamespace(first)).getEpisode(ID);
        assertEquals("A song", saved.track.title); assertEquals("https://www.youtube.com/@artist", saved.track.creatorUrl); assertTrue(saved.rules.musicOnly);
        assertEquals("live", saved.rules.include); assertEquals(120000L, saved.positionMs);
        assertEquals(120000L, first.resumePositionMs(ID));
        first.saveEpisode(new YouTubeRepository.Track(ID, "Updated title", "Artist", 300), null, 5);
        assertEquals(120000L, first.getEpisode(ID).positionMs);
        assertEquals(saved.savedAtMs, first.getEpisode(ID).savedAtMs);
    }

    @Test public void completedAndNearEndPositionsRestartAtZeroAndRemovalIsExplicit() {
        ListeningStore s = store(); s.saveEpisode(track(ID), null, 0);
        s.updateProgressIfSaved(ID, 300000, 300000); assertEquals(0L, s.resumePositionMs(ID));
        s.updateProgressIfSaved(ID, 275000, 300000); assertEquals(0L, s.resumePositionMs(ID));
        s.removeEpisode(ID); assertFalse(s.isSaved(ID));
    }

    @Test public void creatorAndTileCrudReorderAndResetStayInStore() {
        ListeningStore s = store();
        s.saveCreator("https://youtube.com/@artist", "Artist", "https://i.ytimg.com/avatar.jpg");
        assertTrue(s.isCreatorSaved("https://www.youtube.com/@artist"));
        assertEquals("Artist", s.listCreators().get(0).name);
        s.removeCreator("https://youtube.com/@artist"); assertFalse(s.isCreatorSaved("https://youtube.com/@artist"));
        int initial = s.listTiles().size(); assertTrue(initial >= 12);
        s.putTile(new ListeningStore.ExploreTile("test-tile", "Test", "test search", false, 0xff123456));
        assertEquals("test-tile", s.listTiles().get(initial).id);
        s.moveTile("test-tile", -1); assertEquals("test-tile", s.listTiles().get(initial - 1).id);
        s.removeTile("test-tile"); assertEquals(initial, s.listTiles().size());
        s.resetTiles(); assertEquals(initial, s.listTiles().size());
    }

    @Test public void invalidIdentifiersAndUnsafeArtworkAreRejectedOrDiscarded() {
        ListeningStore s = store();
        try { s.saveEpisode(new YouTubeRepository.Track("not-valid", "x", "y", 1), null, 0); fail(); }
        catch (IllegalArgumentException expected) { }
        try { s.putTile(new ListeningStore.ExploreTile("bad id", "x", "x", false, 0)); fail(); }
        catch (IllegalArgumentException expected) { }
        s.saveCreator("https://youtube.com/@artist", "Artist", "https://evil.test/track");
        assertEquals("", s.listCreators().get(0).artworkUrl);
    }

    @Test public void enforcesEpisodeLimitWithVisibleException() {
        ListeningStore s = store();
        for (int i = 0; i < ListeningStore.MAX_EPISODES; i++) {
            String id = String.format(java.util.Locale.ROOT, "%011d", i);
            s.saveEpisode(track(id), null, 0);
        }
        try { s.saveEpisode(track("99999999999"), null, 0); fail("Expected capacity error"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("limit")); }
        assertEquals(ListeningStore.MAX_EPISODES, s.listEpisodes().size());
    }

    @Test public void creatorAndTileCapsRejectNewEntriesButAllowUpserts() {
        ListeningStore s = store();
        for (int i = 0; i < ListeningStore.MAX_CREATORS; i++) {
            s.saveCreator("https://youtube.com/@creator" + i, "Creator " + i, "");
        }
        try { s.saveCreator("https://youtube.com/@overflow", "Overflow", ""); fail("Expected creator cap"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("limit")); }
        s.saveCreator("https://youtube.com/@creator0", "Updated", "");
        assertEquals(ListeningStore.MAX_CREATORS, s.listCreators().size());

        s = store();
        for (ListeningStore.ExploreTile tile : s.listTiles()) s.removeTile(tile.id);
        for (int i = 0; i < ListeningStore.MAX_TILES; i++) {
            String id = "tile-" + i;
            s.putTile(new ListeningStore.ExploreTile(id, id, id, false, i));
        }
        try { s.putTile(new ListeningStore.ExploreTile("tile-overflow", "Overflow", "overflow", false, 0)); fail("Expected tile cap"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("limit")); }
        s.putTile(new ListeningStore.ExploreTile("tile-0", "Updated", "query", false, 0));
        assertEquals(ListeningStore.MAX_TILES, s.listTiles().size());
    }

    @Test public void staleServiceSnapshotCannotOverwriteRemovedAndResavedEpisode() {
        ListeningStore s=store();String id="EmaXeEdlJkI";
        s.saveEpisode(track(id),null,10000);long previousSave=s.getEpisode(id).savedAtMs;
        s.removeEpisode(id);android.os.SystemClock.sleep(3);s.saveEpisode(track(id),null,90000);
        s.updateProgressIfSaved(id,20000,300000,previousSave);
        assertEquals(90000,s.resumePositionMs(id));
        s.updateProgressIfSaved(id,5000,300000,s.getEpisode(id).savedAtMs);
        assertEquals("An intentional backward seek remains valid",5000,s.resumePositionMs(id));
    }

    private static YouTubeRepository.Track track(String id) { return new YouTubeRepository.Track(id, "Song", "Artist", 300); }
    private String getNamespace(ListeningStore unused) { return currentNamespace; }
}
