// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class AudioStreamSelectorTest {
    @Test
    public void originalWebmBeatsDubbedMp4() {
        AudioStream dubbed = stream(MediaFormat.M4A, AudioTrackType.DUBBED, Locale.forLanguageTag("ar"));
        AudioStream original = stream(MediaFormat.WEBMA, AudioTrackType.ORIGINAL, Locale.US);
        assertSame(original, AudioStreamSelector.select(Arrays.asList(dubbed, original)));
    }

    @Test
    public void mp4WinsWithinOriginalTracksAndNonEnglishOriginalIsAllowed() {
        AudioStream originalWebm = stream(MediaFormat.WEBMA, AudioTrackType.ORIGINAL, Locale.forLanguageTag("fr"));
        AudioStream originalMp4 = stream(MediaFormat.M4A, AudioTrackType.ORIGINAL, Locale.forLanguageTag("fr"));
        assertSame(originalMp4, AudioStreamSelector.select(Arrays.asList(originalWebm, originalMp4)));
        assertTrue(AudioStreamSelector.diagnosticMetadata(originalMp4).contains("locale=fr"));
    }

    @Test
    public void unknownTypePrecedesKnownAlternatives() {
        AudioStream dubbed = stream(MediaFormat.M4A, AudioTrackType.DUBBED, Locale.forLanguageTag("ar"));
        AudioStream unknown = stream(MediaFormat.WEBMA, null, null);
        assertSame(unknown, AudioStreamSelector.select(Arrays.asList(dubbed, unknown)));
    }

    @Test
    public void taggedAlternativesRemainPlayableAndFormatPreferenceIsApplied() {
        AudioStream secondaryWebm = stream(MediaFormat.WEBMA, AudioTrackType.SECONDARY, Locale.forLanguageTag("fr"));
        AudioStream descriptiveMp4 = stream(MediaFormat.M4A, AudioTrackType.DESCRIPTIVE, Locale.US);
        assertSame(secondaryWebm, AudioStreamSelector.select(Collections.singletonList(secondaryWebm)));
        assertSame(descriptiveMp4, AudioStreamSelector.select(Arrays.asList(secondaryWebm, descriptiveMp4)));
    }

    @Test
    public void filtersUnsupportedCandidatesAndReturnsNullWhenNoneRemain() {
        AudioStream nonProgressive = stream(MediaFormat.M4A, AudioTrackType.ORIGINAL, Locale.US, DeliveryMethod.DASH);
        AudioStream nullFormat = stream(null, AudioTrackType.ORIGINAL, Locale.US);
        AudioStream unsupportedFormat = stream(MediaFormat.MP3, AudioTrackType.ORIGINAL, Locale.US);
        AudioStream notDirect = stream(MediaFormat.M4A, AudioTrackType.ORIGINAL, Locale.US, false);
        assertNull(AudioStreamSelector.select(Arrays.asList(nonProgressive, nullFormat, unsupportedFormat, notDirect)));
        assertNull(AudioStreamSelector.select(null));
        assertNull(AudioStreamSelector.select(Collections.emptyList()));
    }

    @Test
    public void firstCandidateWinsExactRankAndFormatTies() {
        AudioStream first = stream(MediaFormat.M4A, AudioTrackType.ORIGINAL, Locale.US);
        AudioStream second = stream(MediaFormat.M4A, AudioTrackType.ORIGINAL, Locale.US);
        assertSame(first, AudioStreamSelector.select(Arrays.asList(first, second)));
    }

    private static AudioStream stream(MediaFormat format, AudioTrackType type, Locale locale) {
        return stream(format, type, locale, DeliveryMethod.PROGRESSIVE_HTTP, true);
    }

    private static AudioStream stream(MediaFormat format, AudioTrackType type, Locale locale,
                                      DeliveryMethod method) {
        return stream(format, type, locale, method, true);
    }

    private static AudioStream stream(MediaFormat format, AudioTrackType type, Locale locale,
                                      boolean direct) {
        return stream(format, type, locale, DeliveryMethod.PROGRESSIVE_HTTP, direct);
    }

    private static AudioStream stream(MediaFormat format, AudioTrackType type, Locale locale,
                                      DeliveryMethod method, boolean direct) {
        AudioStream.Builder builder = new AudioStream.Builder()
                .setId("test-track")
                .setContent("https://media.example.test/audio", direct)
                .setMediaFormat(format)
                .setDeliveryMethod(method)
                .setAudioTrackType(type)
                .setAudioLocale(locale);
        return builder.build();
    }
}
