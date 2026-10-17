// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PlaylistStoreTest {
    private static final String PREFS="blackyt_playlists_instrumentation_test";
    private Context context;
    private PlaylistStore store;
    private PlaylistStore.Playlist playlist;

    @Before public void setUp() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteSharedPreferences(PREFS);
        store=new PlaylistStore(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE));
        playlist=store.create("Road trip");
    }
    @After public void tearDown() { context.deleteSharedPreferences(PREFS); }

    @Test public void batchDeduplicatesAndPersistsAcrossStoreInstances() {
        YouTubeRepository.Rules rules=new YouTubeRepository.Rules(false,"interview","cover");
        YouTubeRepository.Track one=track("abcdefghijk"), two=track("bcdefghijkl");
        List<PlaylistStore.Entry> batch=Arrays.asList(new PlaylistStore.Entry(one,rules),
                new PlaylistStore.Entry(one,rules),new PlaylistStore.Entry(two,rules));
        assertEquals(2,store.addAll(playlist.id,batch));
        assertEquals(0,new PlaylistStore(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)).addAll(playlist.id,batch));
        PlaylistStore.Playlist restored=new PlaylistStore(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)).get(playlist.id);
        assertNotNull(restored); assertEquals(2,restored.entries.size());
        assertEquals("interview",restored.entries.get(0).rules.include);
        assertEquals(two.id,restored.entries.get(1).track.id);
    }

    @Test public void capacityFailureLeavesPlaylistUnchanged() {
        List<PlaylistStore.Entry> base=new ArrayList<>();
        for (int i=0;i<PlaylistStore.MAX_TRACKS_PER_PLAYLIST-1;i++)
            base.add(new PlaylistStore.Entry(track(id(i)),new YouTubeRepository.Rules(false,"","")));
        assertEquals(PlaylistStore.MAX_TRACKS_PER_PLAYLIST-1,store.addAll(playlist.id,base));
        List<PlaylistStore.Entry> tooMany=Arrays.asList(base.get(0),
                new PlaylistStore.Entry(track("99999999999"),null),
                new PlaylistStore.Entry(track("88888888888"),null));
        try { store.addAll(playlist.id,tooMany); fail("Expected capacity error"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("cannot hold")); }
        assertEquals(PlaylistStore.MAX_TRACKS_PER_PLAYLIST-1,store.get(playlist.id).entries.size());
    }

    private static YouTubeRepository.Track track(String id) { return new YouTubeRepository.Track(id,"Title " + id,"Creator",60); }
    private static String id(int number) { return String.format(java.util.Locale.ROOT,"%011d",number); }
}
