// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackBufferPolicyTest {
    @Test public void shortStationBurstsDoNotRepeatedlyRestartPlayback() {
        PlaybackBufferPolicy policy = new PlaybackBufferPolicy();
        for (int i = 1; i <= 14; i++) assertFalse(policy.shouldResume(i * 1_000L, 1f, true, 1, i * 1_000L, -1));
        assertTrue(policy.shouldResume(15_000, 1f, true, 1, 15_000, -1));
        assertFalse(policy.shouldResume(20_000, 1f, true, 2, 30_000, -1));
        assertTrue(policy.shouldResume(30_000, 1f, true, 2, 35_000, -1));
        assertFalse(policy.shouldResume(59_000, 1f, true, 3, 50_000, -1));
        assertTrue(policy.shouldResume(60_000, 1f, true, 3, 60_000, -1));
    }
    @Test public void speedShortRemainderAndFreshStartsAreHandled() {
        PlaybackBufferPolicy p = new PlaybackBufferPolicy();
        assertFalse(p.shouldResume(29_000, 2f, true, 1, 1_000, -1));
        assertTrue(p.shouldResume(30_000, 2f, true, 1, 1_000, -1));
        assertTrue(p.shouldResume(3_000, 1f, true, 1, 1_000, 3_000));
        assertTrue(p.shouldResume(2_500, 1f, false, 0, 2_000, -1));
        assertFalse(p.shouldResume(2_499, 1f, false, 0, 2_000, -1));
    }
    @Test public void preparationCannotStarvePlaybackButCanUseACompletedTrack() {
        assertTrue(PlaybackBufferPolicy.playbackNeedsPriority(true, false, 0, -1));
        assertTrue(PlaybackBufferPolicy.playbackNeedsPriority(false, true, 59_000, -1));
        assertFalse(PlaybackBufferPolicy.playbackNeedsPriority(false, true, 60_000, -1));
        assertFalse(PlaybackBufferPolicy.playbackNeedsPriority(false, true, 20_000, 20_000));
        assertEquals(19_200_000, PlaybackBufferPolicy.queuePrefetchBytes(7_200, "140:original:128"));
        assertEquals(24L * 1024 * 1024, PlaybackBufferPolicy.queuePrefetchBytes(7_200, "141:original:512"));
        assertEquals(4_800_000, PlaybackBufferPolicy.queuePrefetchBytes(300, "140:original:128"));
    }
    @Test public void simulatedBurstLinkHasFarFewerRestartsThanTwoSecondDefault() {
        assertTrue(simulate(false) > simulate(true) * 3);
    }
    private int simulate(boolean smoother) {
        PlaybackBufferPolicy p = new PlaybackBufferPolicy();
        int buffer = 0, stalls = 0; boolean playing = true; long event = 0;
        for (int second = 0; second < 360; second++) {
            if (second % 15 < 4) buffer += 2; // 4-second stations, 11-second gaps; below realtime average.
            if (playing && buffer == 0) { playing = false; stalls++; event = second; }
            if (!playing && (smoother ? p.shouldResume(buffer * 1000L, 1f, true, event, second * 1000L, -1) : buffer >= 2)) playing = true;
            if (playing) buffer--;
        }
        return stalls;
    }
}
