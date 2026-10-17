// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import static org.junit.Assert.*;

public final class PlaylistSuggestionsTest {
    @Test public void ideasAreBoundedAndDoNotClaimVerifiedFreshness() {
        YouTubeRepository.Track seed=new YouTubeRepository.Track("abcdefghijk","Episode one","Creator",100);
        List<PlaylistSuggestions.Idea> ideas=PlaylistSuggestions.forTrack(seed,false);
        assertEquals(2,ideas.size());
        assertTrue(ideas.get(0).query.contains("latest episodes"));
        assertTrue(ideas.get(0).label.contains("Search for new episodes"));
        for (PlaylistSuggestions.Idea idea : ideas) assertFalse(idea.label.toLowerCase().contains("new this week"));
    }
    @Test public void musicSuggestionsNameRelatedArtists() {
        YouTubeRepository.Track seed=new YouTubeRepository.Track("abcdefghijk","Song","Artist",100);
        List<PlaylistSuggestions.Idea> ideas=PlaylistSuggestions.forTrack(seed,true);
        assertEquals(2,ideas.size());
        assertTrue(ideas.get(1).label.startsWith("Related artists"));
        assertTrue(ideas.get(1).musicOnly);
    }
    @Test public void candidatesDeduplicateSavedAndRepeatedResultsWithinLimit() {
        YouTubeRepository.Track saved=new YouTubeRepository.Track("abcdefghijk","Saved","A",1);
        YouTubeRepository.Track fresh=new YouTubeRepository.Track("bcdefghijkl","Fresh","B",1);
        List<YouTubeRepository.Track> result=PlaylistSuggestions.uniqueUnseen(
                Arrays.asList(saved,fresh,fresh),new HashSet<>(Arrays.asList(saved.id)));
        assertEquals(1,result.size()); assertEquals(fresh.id,result.get(0).id);
    }
}
