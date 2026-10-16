// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Live-network device check: creator search, Videos tab and an available continuation page. */
@RunWith(AndroidJUnit4.class)
public final class CreatorBrowsingDeviceTest {
    @Test
    public void searchesWesRothAndBrowsesLatestVideos() throws Exception {
        CreatorRepository repository = CreatorRepository.get();
        CreatorRepository.CreatorPage search = repository.searchCreators("Wes Roth", null);
        assertFalse("Creator-filtered search should return channel rows", search.creators.isEmpty());

        CreatorRepository.Creator selected = null;
        for (CreatorRepository.Creator creator : search.creators) {
            if (creator.name.toLowerCase(java.util.Locale.ROOT).contains("wes roth")) {
                selected = creator;
                break;
            }
        }
        assertNotNull("Creator search should find Wes Roth", selected);
        assertTrue(selected.url.startsWith("https://www.youtube.com/"));

        CreatorRepository.ChannelPage first = repository.channel(selected.url, null);
        assertNotNull(first.creator);
        assertTrue(first.creator.url.equals(selected.url));
        assertFalse("The actual Videos tab should have public playable videos", first.tracks.isEmpty());
        Set<String> ids = new HashSet<>();
        for (YouTubeRepository.Track track : first.tracks) {
            assertTrue("Channel page should deduplicate video IDs", ids.add(track.id));
            assertFalse("Each video needs a title", track.title.isEmpty());
            assertTrue("Videos should identify their source channel",
                    track.creatorUrl.isEmpty() || track.creatorUrl.equals(selected.url));
        }

        if (first.next != null) {
            CreatorRepository.ChannelPage next = repository.channel(selected.url, first.next);
            assertNotNull(next.tracks);
            for (YouTubeRepository.Track track : next.tracks) {
                assertTrue("Continuation should not repeat earlier video IDs", ids.add(track.id));
            }
        }
    }
}
