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

    @Test
    public void artworkAllowsOnlyHttpsUrlsFromPublicImageCdnFamilies() {
        assertEquals("https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg",
                YouTubeRepository.safeArtworkUrl("https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg"));
        assertEquals("https://lh3.googleusercontent.com/a/photo",
                YouTubeRepository.safeArtworkUrl("https://lh3.googleusercontent.com/a/photo"));
        assertEquals("", YouTubeRepository.safeArtworkUrl("http://i.ytimg.com/image.jpg"));
        assertEquals("", YouTubeRepository.safeArtworkUrl("https://ytimg.com.evil.test/image.jpg"));
        assertEquals("", YouTubeRepository.safeArtworkUrl("https://user@i.ytimg.com/image.jpg"));
        assertEquals("", YouTubeRepository.safeArtworkUrl("https://i.ytimg.com:444/image.jpg"));
    }

    @Test
    public void trackKeepsCompatibleConstructorAndUsesSafeThumbnailFallback() {
        YouTubeRepository.Track track = new YouTubeRepository.Track(
                "abc_DEF-123", "Song", "Artist", 185);
        assertEquals("https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg", track.artworkUrl);
        YouTubeRepository.Track supplied = new YouTubeRepository.Track(
                "abc_DEF-123", "Song", "Artist", 185, "https://i.ytimg.com/custom.jpg");
        assertEquals("https://i.ytimg.com/custom.jpg", supplied.artworkUrl);
        YouTubeRepository.Track rejected = new YouTubeRepository.Track(
                "abc_DEF-123", "Song", "Artist", 185, "https://evil.test/custom.jpg");
        assertEquals("https://i.ytimg.com/vi/abc_DEF-123/hqdefault.jpg", rejected.artworkUrl);
    }
    @Test
    public void playerArtworkPrefersLargerSafeMetadataImage() {
        org.schabi.newpipe.extractor.Image.ResolutionLevel level =
                org.schabi.newpipe.extractor.Image.ResolutionLevel.UNKNOWN;
        java.util.List<org.schabi.newpipe.extractor.Image> images = java.util.Arrays.asList(
                new org.schabi.newpipe.extractor.Image("https://i.ytimg.com/tiny.jpg", 60, 60, level),
                new org.schabi.newpipe.extractor.Image("https://evil.test/large.jpg", 1000, 1000, level),
                new org.schabi.newpipe.extractor.Image("https://i.ytimg.com/cover.jpg", 544, 544, level));
        assertEquals("https://i.ytimg.com/cover.jpg", YouTubeRepository.bestSafeThumbnail(images));
        assertEquals("", new YouTubeRepository.Track("invalid", "Song", "Artist", 0).artworkUrl);
    }
    @Test
    public void elevenCharacterArtistNameRemainsTextSearch() {
        assertFalse(YouTubeRepository.isVideoLinkQuery("Blackstreet"));
        assertFalse(YouTubeRepository.isVideoLinkQuery("abc_DEF-123"));
        assertTrue(YouTubeRepository.isVideoLinkQuery(" https://youtu.be/abc_DEF-123 "));
    }
}
