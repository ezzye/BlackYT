// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;

/** Keep a useful reserve before starting/restarting on an intermittent mobile connection. */
@androidx.media3.common.util.UnstableApi
final class PlaybackBufferPolicy {
    static final int MIN_BUFFER_MS = 120_000;
    static final int MAX_BUFFER_MS = 300_000;
    static final int START_BUFFER_MS = 8_000;
    static final int REBUFFER_MS = 15_000;
    static final int PREPARATION_RESERVE_MS = 30_000;

    private PlaybackBufferPolicy() { }

    static DefaultLoadControl createLoadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    static boolean shouldPausePreparation(int state, long bufferedDurationMs) {
        return state == Player.STATE_BUFFERING
                || (state == Player.STATE_READY && bufferedDurationMs < PREPARATION_RESERVE_MS);
    }
}
