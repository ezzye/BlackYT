// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Short silent WAV fixtures exercise the real decoder/cache without remote test dependencies. */
@androidx.media3.common.util.UnstableApi
final class CachedAudioFixtures implements AutoCloseable {
    private static final String FORMAT = "blackyt-autoplay-test-pcm16";
    private final AudioPrefixCache cache;
    private final List<String> keys = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    CachedAudioFixtures(Context context) { cache = AudioPrefixCache.get(context); }
    void seed(YouTubeRepository.Track track, YouTubeRepository.Rules rules, int seconds) throws Exception {
        seed(track, rules, seconds, Integer.MAX_VALUE);
    }
    void seed(YouTubeRepository.Track track, YouTubeRepository.Rules rules, int seconds, int prefixBytes) throws Exception {
        int size = seconds * 8000 * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + size)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(8000).putInt(16000)
                .putShort((short)2).putShort((short)16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(size);
        byte[] bytes = wav.array();
        String key = AudioPrefixCache.keyForFormat(track.id, rules, FORMAT);
        ids.add(track.id); keys.add(key);
        if (!cache.remember(track.id, rules, FORMAT)) throw new AssertionError("Unable to seed fixture descriptor");
        cache.rememberLength(track.id, rules, FORMAT, bytes.length);
        DataSource source = cache.factory(() -> new ByteArrayDataSource(bytes)).createDataSource();
        try {
            source.open(new DataSpec.Builder().setUri(NativeMediaItems.fromTrack(track,rules).localConfiguration.uri)
                    .setKey(key).setLength(Math.min(prefixBytes,bytes.length)).build());
            byte[] buffer = new byte[32768];
            while(source.read(buffer,0,buffer.length) != -1) { }
        } finally { source.close(); }
        cache.rememberLength(track.id, rules, FORMAT, bytes.length);
    }
    @Override public void close() {
        for(String id:ids) PlaybackFormatPins.clear(id);
        for(String key:keys) cache.invalidate(key);
    }
}
