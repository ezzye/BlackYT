// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CreatorRepositoryTest {
    @Test
    public void canonicalizesSupportedPublicChannelUrls() {
        assertEquals("https://www.youtube.com/channel/UC1234567890123456789012",
                CreatorRepository.canonicalChannelUrl(
                        "https://youtube.com/channel/UC1234567890123456789012/"));
        assertEquals("https://www.youtube.com/@WesRoth",
                CreatorRepository.canonicalChannelUrl("https://m.youtube.com/@WesRoth"));
        assertEquals("https://www.youtube.com/c/Creator.Name",
                CreatorRepository.canonicalChannelUrl("https://music.youtube.com/c/Creator.Name"));
        assertEquals("https://www.youtube.com/user/Creator_Name",
                CreatorRepository.canonicalChannelUrl("https://www.youtube.com/user/Creator_Name"));
    }

    @Test
    public void rejectsUrlsThatAreNotPublicChannelEndpoints() {
        assertRejected("http://www.youtube.com/@WesRoth");
        assertRejected("https://youtube.com.evil.test/@WesRoth");
        assertRejected("https://user@youtube.com/@WesRoth");
        assertRejected("https://youtube.com:443/@WesRoth");
        assertRejected("https://youtube.com/watch?v=abcdefghijk");
        assertRejected("https://youtu.be/abcdefghijk");
        assertRejected("https://youtube.com/@WesRoth/videos");
        assertRejected("https://youtube.com/@WesRoth?feature=shared");
    }

    @Test
    public void cursorBindingUsesExactSearchAndCanonicalChannel() {
        CreatorRepository.CreatorCursor search = new CreatorRepository.CreatorCursor(
                "Wes Roth", null, null);
        assertTrue(search.isBoundTo("Wes Roth"));
        assertFalse(search.isBoundTo("wes roth"));

        String canonical = CreatorRepository.canonicalChannelUrl("https://youtube.com/@WesRoth");
        CreatorRepository.ChannelCursor channel = new CreatorRepository.ChannelCursor(
                canonical, null, null,
                new CreatorRepository.Creator(canonical, "Wes Roth", "", "", -1), null);
        assertTrue(channel.isBoundTo("https://m.youtube.com/@WesRoth/"));
        assertFalse(channel.isBoundTo("https://www.youtube.com/@AnotherCreator"));
    }

    @Test
    public void trackRetainsOnlyCanonicalOptionalCreatorMetadata() {
        YouTubeRepository.Track valid = new YouTubeRepository.Track("abc_DEF-123", "Video",
                "Creator", 100, null, "https://youtube.com/@WesRoth");
        assertEquals("https://www.youtube.com/@WesRoth", valid.creatorUrl);
        YouTubeRepository.Track invalid = new YouTubeRepository.Track("abc_DEF-123", "Video",
                "Creator", 100, null, "https://evil.test/@WesRoth");
        assertTrue(invalid.creatorUrl.isEmpty());
    }

    private static void assertRejected(String input) {
        try {
            CreatorRepository.canonicalChannelUrl(input);
            throw new AssertionError("Expected invalid channel URL to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
