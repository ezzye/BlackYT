// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

@androidx.media3.common.util.UnstableApi
public class PlaybackPreparationTest {
    private static PlaybackPreparation.Candidate candidate(String id) {
        return new PlaybackPreparation.Candidate(id,
                new YouTubeRepository.Rules(false, "", ""));
    }

    @Test public void queueGetsTwoSlotsAndVisibleResultsGetOnlyOne() {
        List<PlaybackPreparation.Candidate> selected = PlaybackPreparation.prioritize(
                Arrays.asList(candidate("queue000001"), candidate("queue000002"), candidate("queue000003")),
                Arrays.asList(candidate("screen00001"), candidate("screen00002")));

        assertEquals(Arrays.asList("queue000001", "queue000002", "screen00001"), ids(selected));
    }

    @Test public void queueWinsDuplicatesAndEmptyQueueAllowsOneVisibleCandidate() {
        List<PlaybackPreparation.Candidate> selected = PlaybackPreparation.prioritize(
                Collections.singletonList(candidate("same0000001")),
                Arrays.asList(candidate("same0000001"), candidate("screen00001")));

        assertEquals(Arrays.asList("same0000001", "screen00001"), ids(selected));
    }

    @Test public void audioCacheKeyIgnoresSignedAddressButBindsSelectedFormat() {
        YouTubeRepository.Rules rules = new YouTubeRepository.Rules(false, "", "");
        YouTubeRepository.Resolved first = new YouTubeRepository.Resolved(
                "https://rr1.googlevideo.com/audio?expire=1", "audio/mp4", "title", "artist", 10,
                "rr1.googlevideo.com", "", "140:original:ORIGINAL:audio/mp4");
        YouTubeRepository.Resolved rotated = new YouTubeRepository.Resolved(
                "https://rr2.googlevideo.com/audio?expire=2", "audio/mp4", "title", "artist", 10,
                "rr2.googlevideo.com", "", "140:original:ORIGINAL:audio/mp4");
        YouTubeRepository.Resolved alternate = new YouTubeRepository.Resolved(
                "https://rr2.googlevideo.com/audio?expire=2", "audio/mp4", "title", "artist", 10,
                "rr2.googlevideo.com", "", "141:original:ORIGINAL:audio/mp4");

        assertEquals(AudioPrefixCache.key("video000001", rules, first),
                AudioPrefixCache.key("video000001", rules, rotated));
        org.junit.Assert.assertNotEquals(AudioPrefixCache.key("video000001", rules, first),
                AudioPrefixCache.key("video000001", rules, alternate));
        org.junit.Assert.assertFalse(AudioPrefixCache.key("video000001", rules, first)
                .contains("expire=1"));
    }

    @Test public void unidentifiableAudioFormatIsNeverGivenACacheKey() {
        YouTubeRepository.Resolved unresolved = new YouTubeRepository.Resolved(
                "https://rr1.googlevideo.com/audio?secret=signed", "audio/mp4", "title", "artist", 10,
                "rr1.googlevideo.com", "", "");

        org.junit.Assert.assertNull(AudioPrefixCache.key("video000001",
                new YouTubeRepository.Rules(false, "", ""), unresolved));
        org.junit.Assert.assertFalse(KeyedAudioCacheDataSource.shouldUseCache(
                new androidx.media3.datasource.cache.CacheDataSource.Factory(), null));
        org.junit.Assert.assertFalse(KeyedAudioCacheDataSource.shouldUseCache(null, null));
    }

    @Test public void changedFormatCannotResumeUntilExplicitResolverInvalidation() {
        PlaybackFormatPins.clear("video000001");
        org.junit.Assert.assertTrue(PlaybackFormatPins.accept("video000001", "itag140:original"));
        org.junit.Assert.assertFalse(PlaybackFormatPins.accept("video000001", "itag251:original"));
        PlaybackFormatPins.clear("video000001");
        org.junit.Assert.assertTrue(PlaybackFormatPins.accept("video000001", "itag251:original"));
        PlaybackFormatPins.clear("video000001");
    }

    private static List<String> ids(List<PlaybackPreparation.Candidate> candidates) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (PlaybackPreparation.Candidate candidate : candidates) result.add(candidate.id);
        return result;
    }
}
