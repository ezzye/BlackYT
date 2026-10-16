// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;

import java.util.List;
import java.util.Locale;

/** Selects the best supported direct audio stream without guessing the user's language. */
final class AudioStreamSelector {
    private AudioStreamSelector() { }

    static AudioStream select(List<AudioStream> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        AudioStream best = null;
        int bestTrackRank = Integer.MAX_VALUE;
        int bestFormatRank = Integer.MAX_VALUE;
        for (AudioStream candidate : candidates) {
            if (!isSupported(candidate)) {
                continue;
            }

            int trackRank = trackRank(candidate.getAudioTrackType());
            int formatRank = "audio/mp4".equalsIgnoreCase(
                    candidate.getFormat().getMimeType()) ? 0 : 1;
            if (trackRank < bestTrackRank
                    || (trackRank == bestTrackRank && formatRank < bestFormatRank)) {
                best = candidate;
                bestTrackRank = trackRank;
                bestFormatRank = formatRank;
            }
        }
        return best;
    }

    private static boolean isSupported(AudioStream stream) {
        if (stream == null || !stream.isUrl()
                || stream.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP
                || stream.getFormat() == null) {
            return false;
        }
        String mime = stream.getFormat().getMimeType();
        return "audio/mp4".equalsIgnoreCase(mime) || "audio/webm".equalsIgnoreCase(mime);
    }

    private static int trackRank(AudioTrackType type) {
        if (type == AudioTrackType.ORIGINAL) {
            return 0;
        }
        if (type == null) {
            return 1;
        }
        return 2;
    }

    static String diagnosticMetadata(AudioStream selected) {
        if (selected == null) {
            return "original=false type=none locale=none";
        }
        AudioTrackType type = selected.getAudioTrackType();
        Locale locale = selected.getAudioLocale();
        return "original=" + (type == AudioTrackType.ORIGINAL)
                + " type=" + (type == null ? "unknown" : type.name())
                + " locale=" + (locale == null ? "unknown" : locale.toLanguageTag());
    }
}
