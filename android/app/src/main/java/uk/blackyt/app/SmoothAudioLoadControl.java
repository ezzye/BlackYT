// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/** Keeps a larger reserve and increases the resume threshold after clustered stalls. */
@androidx.media3.common.util.UnstableApi
final class SmoothAudioLoadControl extends DefaultLoadControl {
    private final PlaybackBufferPolicy policy = new PlaybackBufferPolicy();
    private final Timeline.Period period = new Timeline.Period();
    private Object periodUid;

    SmoothAudioLoadControl() {
        super(new DefaultAllocator(true, 64 * 1024),
                PlaybackBufferPolicy.MIN_BUFFER_MS, PlaybackBufferPolicy.MIN_BUFFER_MS,
                PlaybackBufferPolicy.MAX_BUFFER_MS, PlaybackBufferPolicy.MAX_BUFFER_MS,
                PlaybackBufferPolicy.START_MS, PlaybackBufferPolicy.START_MS, 15_000, 15_000,
                32 * 1024 * 1024, true, true, 30_000, false);
    }

    @Override public boolean shouldStartPlayback(Parameters p) {
        if (!java.util.Objects.equals(periodUid, p.mediaPeriodId.periodUid)) {
            periodUid = p.mediaPeriodId.periodUid; policy.reset();
        }
        long remainingMs = -1;
        int index = p.timeline.getIndexOfPeriod(p.mediaPeriodId.periodUid);
        if (index != C.INDEX_UNSET) {
            long durationUs = p.timeline.getPeriod(index, period).durationUs;
            if (durationUs != C.TIME_UNSET) remainingMs = Math.max(0, (durationUs - p.playbackPositionUs) / 1_000);
        }
        long event = p.lastRebufferRealtimeMs == C.TIME_UNSET ? p.playbackPositionUs : p.lastRebufferRealtimeMs;
        return policy.shouldResume(p.bufferedDurationUs / 1_000, p.playbackSpeed,
                p.rebuffering, event, SystemClock.elapsedRealtime(), remainingMs);
    }
}
