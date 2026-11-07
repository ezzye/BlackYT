// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

/** Playout-time hysteresis: one useful pause instead of repeated tiny bursts. */
final class PlaybackBufferPolicy {
    static final int MIN_BUFFER_MS = 120_000, MAX_BUFFER_MS = 600_000, START_MS = 2_500;
    private long lastEvent = Long.MIN_VALUE, lastStallAt = Long.MIN_VALUE;
    private int stalls;

    synchronized void reset() { lastEvent = Long.MIN_VALUE; lastStallAt = Long.MIN_VALUE; stalls = 0; }

    synchronized long requiredMs(boolean rebuffering, long event, long now) {
        if (!rebuffering) { reset(); return START_MS; }
        if (event != lastEvent) {
            if (lastStallAt == Long.MIN_VALUE || now < lastStallAt || now - lastStallAt > 120_000) stalls = 0;
            stalls = Math.min(3, stalls + 1);
            lastEvent = event; lastStallAt = now;
        }
        return stalls >= 3 ? 60_000 : stalls == 2 ? 30_000 : 15_000;
    }

    boolean shouldResume(long bufferedMs, float speed, boolean rebuffering,
            long event, long now, long remainingMs) {
        double safeSpeed = Math.max(0.1, speed);
        long threshold = requiredMs(rebuffering, event, now);
        if (remainingMs >= 0) threshold = Math.min(threshold, (long) Math.ceil(remainingMs / safeSpeed));
        return bufferedMs / safeSpeed >= threshold;
    }

    static boolean playbackNeedsPriority(boolean buffering, boolean playing, long aheadMs, long remainingMs) {
        if (buffering) return true;
        if (!playing) return false;
        if (remainingMs >= 0 && aheadMs >= remainingMs - 1_000) return false;
        return aheadMs < 60_000;
    }

    static long queuePrefetchBytes(long durationSeconds, String formatKey) {
        long kbps = 128;
        try {
            long parsed = Long.parseLong(formatKey.substring(formatKey.lastIndexOf(':') + 1));
            if (parsed >= 16 && parsed <= 1_024) kbps = parsed;
        } catch (RuntimeException ignored) { }
        long seconds = durationSeconds > 0 ? Math.min(1_200, durationSeconds) : 1_200;
        return Math.min(24L * 1024 * 1024, seconds * kbps * 125L);
    }
}
