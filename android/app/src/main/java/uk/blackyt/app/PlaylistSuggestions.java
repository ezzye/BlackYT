// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded user-requested YT search ideas. Labels describe the query, never assert freshness. */
public final class PlaylistSuggestions {
    public static final int MAX_QUERIES = 2;
    public static final int MAX_RESULTS_PER_QUERY = 8;
    private PlaylistSuggestions() { }

    public static final class Idea {
        public final String label, query;
        public final boolean musicOnly;
        Idea(String label, String query, boolean musicOnly) { this.label=label; this.query=query; this.musicOnly=musicOnly; }
    }

    public static List<Idea> forTrack(YouTubeRepository.Track track, boolean musicOnly) {
        List<Idea> ideas=new ArrayList<>();
        if (track == null) return ideas;
        String channel=clean(track.channel), title=clean(track.title);
        if (!channel.isEmpty()) {
            String query=musicOnly ? channel + " official music" : channel + " latest episodes";
            ideas.add(new Idea(musicOnly ? "More music from " + channel : "Search for new episodes from " + channel, query, musicOnly));
        }
        if (ideas.size() < MAX_QUERIES && !title.isEmpty()) {
            String query=musicOnly ? title + " similar artists" : title + " related interview podcast";
            ideas.add(new Idea(musicOnly ? "Related artists for " + title : "Related talks to " + title, query, musicOnly));
        }
        return ideas;
    }

    /** Keep the first occurrence and exclude tracks already saved or shown in the current screen. */
    public static List<YouTubeRepository.Track> uniqueUnseen(List<YouTubeRepository.Track> candidates, Set<String> alreadySeen) {
        Set<String> seen=new LinkedHashSet<>(); if (alreadySeen != null) seen.addAll(alreadySeen);
        List<YouTubeRepository.Track> unique=new ArrayList<>();
        if (candidates != null) for (YouTubeRepository.Track track : candidates) {
            if (track != null && track.id != null && seen.add(track.id)) unique.add(track);
            if (unique.size() >= MAX_RESULTS_PER_QUERY) break;
        }
        return unique;
    }
    private static String clean(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("\\s+"," ").replaceAll("[\\r\\n]"," ");
    }
}
