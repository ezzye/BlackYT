// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

/** One clean restart per item until sustained playback or an item change. */
final class CacheRecoveryPolicy {
    private String item = "";
    private boolean attempted;
    private long playingSince = -1;
    void transition(String next) {
        if (next == null || next.isEmpty()) return;
        if (!item.equals(next)) { item = next; attempted = false; playingSince = -1; }
    }
    void playing(boolean playing, long now) {
        if (!playing) playingSince = -1;
        else if (playingSince < 0) playingSince = now;
        else if (now >= playingSince && now - playingSince >= 30_000) attempted = false;
    }
    boolean take(String current, Throwable error) {
        if (current == null || current.isEmpty() || !current.equals(item) || attempted || !isCacheFailure(error)) return false;
        attempted = true;
        playingSince = -1;
        return true;
    }
    static boolean isCacheFailure(Throwable error) {
        for (int i = 0; error != null && i < 20; i++, error = error.getCause())
            if (error instanceof AudioFormatChangedException || error instanceof AudioCacheInvalidatedException) return true;
        return false;
    }
}
