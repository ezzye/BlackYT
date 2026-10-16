// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class YtLinkTest {
    private static final String ID = "abc_DEF-123";

    @Test public void parsesShortShareUrlAndSecondsWithSi() {
        YtLink.Result r = YtLink.parse("Listen here: https://youtu.be/" + ID + "?si=share&t=205s");
        assertTrue(r.isVideo()); assertEquals(ID, r.videoId); assertEquals(205000L, r.startMs);
        assertEquals("https://www.youtube.com/watch?v=" + ID + "&t=205s", r.canonicalUrl);
    }
    @Test public void parsesWatchLinkAndClockOrCompoundTime() {
        assertEquals(205000L, YtLink.parse("https://music.youtube.com/watch?v=" + ID + "&t=3m25s").startMs);
        assertEquals(205000L, YtLink.parse("https://youtube.com/watch?v=" + ID + "&start=205").startMs);
        assertEquals(3723000L, YtLink.parse("https://www.youtube.com/watch?v=" + ID + "&timestamp=01%3A02%3A03").startMs);
    }
    @Test public void acceptsVideoRoutesAndFragmentTime() {
        assertEquals(ID, YtLink.parse("https://m.youtube.com/shorts/" + ID).videoId);
        assertEquals(ID, YtLink.parse("http://youtube.com/live/" + ID + "#t=42").videoId);
        assertTrue(YtLink.parse("http://youtube.com/live/" + ID).canonicalUrl.startsWith("https://"));
        assertEquals(42000L, YtLink.parse("https://www.youtube.com/embed/" + ID + "#t=42").startMs);
    }
    @Test public void invalidTimeOnValidUrlFallsBackToZero() {
        assertEquals(0L, YtLink.parse("https://youtube.com/watch?v=" + ID + "&t=banana").startMs);
    }
    @Test public void acceptsBareIdAndChannelRoutes() {
        assertTrue(YtLink.parse(ID).isVideo());
        assertEquals("https://www.youtube.com/channel/UC1234567890123456789012",
                YtLink.parse("https://m.youtube.com/channel/UC1234567890123456789012").channelUrl);
        assertEquals("https://www.youtube.com/@artist", YtLink.parse("https://youtube.com/@artist?si=share").channelUrl);
        assertEquals("https://www.youtube.com/@artist", YtLink.parse("https://youtube.com/@artist").channelUrl);
        assertEquals("https://www.youtube.com/c/artist", YtLink.parse("https://www.youtube.com/c/artist").channelUrl);
        assertEquals("https://www.youtube.com/user/artist", YtLink.parse("https://www.youtube.com/user/artist").channelUrl);
    }
    @Test public void rejectsLookalikesCredentialsAndNonstandardPorts() {
        rejects("https://youtube.com.evil.test/watch?v=" + ID);
        rejects("https://youtube.com@evil.test/watch?v=" + ID);
        rejects("https://user@youtube.com/watch?v=" + ID);
        rejects("https://youtube.com:444/watch?v=" + ID);
        rejects("https://notyoutube.com/watch?v=" + ID);
    }
    @Test public void rejectsMalformedEscapesAndMissingHost() {
        rejects("https://youtu.be/" + ID + "?t=%Q1");
        rejects("https:///watch?v=" + ID);
        rejects("not a link");
        rejects("https://youtube.com/@ab");
        rejects("https://youtube.com/channel/UC12345678901234567890123");
        rejects("x".repeat(16_385));
    }
    private static void rejects(String input) {
        try { YtLink.parse(input); fail("Expected invalid link: " + input); }
        catch (IllegalArgumentException expected) { }
    }
}
