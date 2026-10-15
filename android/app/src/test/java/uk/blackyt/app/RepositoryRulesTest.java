// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RepositoryRulesTest {
    @Test
    public void includeMatchesEitherTitleOrChannelAndExcludeWins() {
        YouTubeRepository.Rules rules = new YouTubeRepository.Rules(false, "live, acoustic", "cover");
        assertTrue(YouTubeRepository.matches("A Song", "Acoustic Sessions", rules));
        assertTrue(YouTubeRepository.matches("Live at Home", "Artist", rules));
        assertFalse(YouTubeRepository.matches("Live Cover", "Artist", rules));
        assertFalse(YouTubeRepository.matches("A Song", "Artist", rules));
    }

    @Test
    public void cachedInterestResultCannotSatisfyMusicMode() {
        assertFalse(YouTubeRepository.cachedResultAllowed(false, true));
        assertTrue(YouTubeRepository.cachedResultAllowed(true, true));
        assertTrue(YouTubeRepository.cachedResultAllowed(false, false));
    }

    @Test
    public void normalizesOnlyStrictPublicYoutubeIdsAndWatchLinks() {
        assertEquals("abc_DEF-123", YouTubeRepository.normalizeVideoId("abc_DEF-123"));
        assertEquals("abc_DEF-123", YouTubeRepository.normalizeVideoId(
                "https://www.youtube.com/watch?v=abc_DEF-123&si=ignored"));
        assertEquals("abc_DEF-123", YouTubeRepository.normalizeVideoId(
                "https://music.youtube.com/watch?v=abc_DEF-123"));
        assertEquals("abc_DEF-123", YouTubeRepository.normalizeVideoId(
                "https://youtu.be/abc_DEF-123?si=ignored"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInsecureYoutubeLinks() {
        YouTubeRepository.normalizeVideoId("http://youtube.com/watch?v=abc_DEF-123");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLookalikeYoutubeHosts() {
        YouTubeRepository.normalizeVideoId("https://youtube.com.evil.test/watch?v=abc_DEF-123");
    }
}
