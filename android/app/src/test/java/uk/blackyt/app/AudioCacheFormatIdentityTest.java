// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AudioCacheFormatIdentityTest {
    private static final String BASE = "https://r1.googlevideo.com/videoplayback?itag=140&clen=456789&lmt=123456789";
    private static String key(String url, String track) {
        return AudioCacheFormatIdentity.create(url, 140, track, "", "audio/mp4", "", 0);
    }
    @Test public void signedUrlRotationKeepsStableIdentityEvenWithoutOptionalMetadata() {
        String a = key(BASE + "&expire=123&sig=secretA&ip=1.2.3.4", "");
        String b = key(BASE + "&ip=5.6.7.8&sig=secretB&expire=999", "");
        assertFalse(a.isEmpty()); assertEquals(a, b);
        assertFalse(a.contains("secret")); assertFalse(a.contains("googlevideo"));
        assertTrue(a.endsWith(":0"));
    }
    @Test public void revisionRepresentationAndTrackChangesDoNotCollide() {
        String base = key(BASE, "en");
        assertNotEquals(base, key(BASE.replace("lmt=123456789", "lmt=123456790"), "en"));
        assertNotEquals(base, key(BASE.replace("clen=456789", "clen=456790"), "en"));
        String otherItag = AudioCacheFormatIdentity.create(BASE.replace("itag=140", "itag=141"), 141, "en", "", "audio/mp4", "", 0);
        assertFalse(otherItag.isEmpty()); assertNotEquals(base, otherItag);
        assertNotEquals(base, key(BASE, "fr"));
        assertNotEquals(key(BASE + "&id=one", ""), key(BASE + "&id=two", ""));
        assertNotEquals(key(BASE + "&xtags=lang%3Den", ""), key(BASE + "&xtags=lang%3Dfr", ""));
        assertNotEquals(key(BASE + "&audio_track_id=en", ""), key(BASE + "&audio_track_id=fr", ""));
        assertNotEquals(base, AudioCacheFormatIdentity.create(BASE, 140, "en", "DUBBED", "audio/mp4", "", 0));
        assertNotEquals(base, AudioCacheFormatIdentity.create(BASE, 140, "en", "", "audio/mp4", "aac", 128000));
    }
    @Test public void malformedMissingDuplicateAndMismatchedIdentityFailsClosed() {
        for (String url : new String[] { BASE + "&itag=140", BASE + "&%69tag=140",
                BASE + "&clen=456789", BASE + "&lmt=123456789", BASE + "&xtags=en&xtags=fr", BASE + "&id=one&id=two", BASE.replace("&lmt=123456789", ""),
                BASE.replace("clen=456789", "clen=0"), BASE.replace("lmt=123456789", "lmt=-3"),
                BASE.replace("itag=140", "itag=141"), BASE.replace("r1.googlevideo.com", "googlevideo.com.evil.test"),
                BASE.replace("https:", "http:"), BASE + "&%zz=x" }) {
            assertEquals(url, "", key(url, ""));
        }
    }
    @Test public void bitrateRemainsAvailableToPrefetchPolicy() {
        String format = AudioCacheFormatIdentity.create(BASE, 140, "", "", "audio/mp4", "aac", 128000);
        assertTrue(format.endsWith(":128000"));
    }
}
