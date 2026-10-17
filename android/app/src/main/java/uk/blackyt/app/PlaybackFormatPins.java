// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import java.util.HashMap;
import java.util.Map;

/** Holds one selected audio format steady across Media3 retries for a logical item. */
final class PlaybackFormatPins {
    private static final Map<String, String> PINS = new HashMap<>();

    private PlaybackFormatPins() { }

    static synchronized boolean accept(String videoId, String formatKey) {
        if (formatKey == null || formatKey.isEmpty()) return false;
        String id;
        try { id = YouTubeRepository.normalizeVideoId(videoId); }
        catch (IllegalArgumentException invalid) { return false; }
        String prior = PINS.get(id);
        if (prior == null) { PINS.put(id, formatKey); return true; }
        return prior.equals(formatKey);
    }

    static synchronized void clear(String videoId) {
        try { PINS.remove(YouTubeRepository.normalizeVideoId(videoId)); }
        catch (IllegalArgumentException ignored) { }
    }
}
