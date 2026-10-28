// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Small on-device cache for the first fraction of explicitly upcoming audio. */
@androidx.media3.common.util.UnstableApi
final class AudioPrefixCache {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final long PREFIX_BYTES = 2L * 1024 * 1024;
    private static volatile AudioPrefixCache instance;
    private static final java.util.concurrent.ExecutorService maintenance =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "BlackYT-audio-cache-maintenance");
                thread.setDaemon(true);
                return thread;
            });
    private final SimpleCache cache;
    private volatile CacheWriter activeWriter;
    private boolean clearWhenWriterStops;

    private AudioPrefixCache(Context context) {
        cache = new SimpleCache(new File(context.getCacheDir(), "blackyt-audio-prefix"),
                new LeastRecentlyUsedCacheEvictor(MAX_BYTES));
    }

    static AudioPrefixCache get(Context context) {
        AudioPrefixCache value = instance;
        if (value == null) synchronized (AudioPrefixCache.class) {
            value = instance;
            if (value == null) instance = value = new AudioPrefixCache(context.getApplicationContext());
        }
        return value;
    }

    static AudioPrefixCache peek() { return instance; }

    CacheDataSource.Factory factory(androidx.media3.datasource.DataSource.Factory upstream) {
        return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(spec -> spec.key == null ? spec.uri.toString() : spec.key)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setCacheWriteDataSinkFactory(null);
    }

    private CacheDataSource.Factory writerFactory(androidx.media3.datasource.DataSource.Factory upstream) {
        return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(spec -> spec.key == null ? spec.uri.toString() : spec.key);
    }

    static String key(String id, YouTubeRepository.Rules rules, YouTubeRepository.Resolved resolved) {
        if (resolved == null || resolved.formatKey == null || resolved.formatKey.isEmpty()) return null;
        String material = YouTubeRepository.normalizeVideoId(id) + "\n"
                + (rules != null && rules.musicOnly) + "\n"
                + (rules == null ? "" : rules.include) + "\n"
                + (rules == null ? "" : rules.exclude) + "\n" + resolved.formatKey + "\n"
                + (System.currentTimeMillis() / 86_400_000L);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("blackyt-audio-v1-");
            for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception unavailable) { return null; }
    }

    void prefetch(String id, YouTubeRepository.Rules rules, YouTubeRepository.Resolved expected,
            BooleanSupplier stillAllowed)
            throws IOException {
        String cacheKey = key(id, rules, expected);
        if (cacheKey == null || cache.isCached(cacheKey, 0, PREFIX_BYTES)) return;
        YouTubeRepository repository = YouTubeRepository.get();
        AtomicBoolean formatChanged = new AtomicBoolean();
        androidx.media3.datasource.DataSource.Factory rawHttp =
                new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(repository.mediaClient())
                        .setUserAgent(YouTubeRepository.USER_AGENT);
        androidx.media3.datasource.DataSource.Factory upstream = () -> new RefreshingAudioDataSource(
                () -> new QueryRangeAudioDataSource(rawHttp), (dataSpec, fresh) -> {
                    try {
                        String pathId = dataSpec.uri.getLastPathSegment();
                        NativeMediaItems.OwnedItem owned = NativeMediaItems.parse(
                                new androidx.media3.common.MediaItem.Builder().setMediaId(pathId)
                                        .setUri(dataSpec.uri).build());
                        YouTubeRepository.Resolved resolved = repository.resolve(owned.id, owned.rules, fresh);
                        String currentKey = key(owned.id, owned.rules, resolved);
                        if (!cacheKey.equals(currentKey)) {
                            formatChanged.set(true);
                            throw new IOException("Audio format changed; speculative prefix discarded.");
                        }
                        return Uri.parse(resolved.url);
                    } catch (IOException failure) {
                        throw failure;
                    } catch (Exception invalid) {
                        throw new IOException("Unable to prepare this audio item.");
                    }
                });
        Uri uri = new Uri.Builder().scheme("blackyt").authority("youtube").appendPath(id)
                .appendQueryParameter("music", rules != null && rules.musicOnly ? "1" : "0")
                .appendQueryParameter("include", rules == null ? "" : rules.include)
                .appendQueryParameter("exclude", rules == null ? "" : rules.exclude).build();
        DataSpec spec = new DataSpec.Builder().setUri(uri).setKey(cacheKey).setPosition(0)
                .setLength(PREFIX_BYTES).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build();
        CacheDataSource source = writerFactory(upstream).createDataSource();
        CacheWriter writer = new CacheWriter(source, spec, new byte[CacheWriter.DEFAULT_BUFFER_SIZE_BYTES], null);
        synchronized (this) {
            if (clearWhenWriterStops) { source.close(); return; }
            activeWriter = writer;
        }
        try {
            if (stillAllowed == null || stillAllowed.getAsBoolean()) writer.cache();
            else writer.cancel();
        }
        finally {
            try { source.close(); }
            finally {
                if (formatChanged.get()) cache.removeResource(cacheKey);
                synchronized (this) {
                    activeWriter = null;
                    if (clearWhenWriterStops) clearResources();
                }
            }
        }
    }

    void cancelPrefetch() {
        CacheWriter writer = activeWriter;
        if (writer != null) writer.cancel();
    }

    static void cancel(Context context) {
        AudioPrefixCache value = instance;
        if (value != null) value.cancelPrefetch();
    }

    static void clear(Context context) {
        AudioPrefixCache value = instance;
        if (value != null) maintenance.execute(value::clear);
    }

    void invalidate(String cacheKey) {
        if (cacheKey != null) cache.removeResource(cacheKey);
    }

    synchronized void clear() {
        if (activeWriter != null) {
            clearWhenWriterStops = true;
            activeWriter.cancel();
            return;
        }
        clearResources();
    }

    private void clearResources() {
        for (String key : cache.getKeys()) cache.removeResource(key);
        clearWhenWriterStops = false;
    }

    static boolean hasPrefix(CacheDataSource.Factory factory, String key) {
        return key != null && factory.getCache() != null && factory.getCache().isCached(key, 0, PREFIX_BYTES);
    }
}
