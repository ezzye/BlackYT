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
    static final long MAX_BYTES = 256L * 1024 * 1024;
    static final long MAX_PREFETCH_BYTES = 24L * 1024 * 1024;
    static final long PREFIX_BYTES = 384L * 1024;
    private static volatile AudioPrefixCache instance;
    private static final java.util.concurrent.ExecutorService maintenance =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "BlackYT-audio-cache-maintenance");
                thread.setDaemon(true);
                return thread;
            });
    private final SimpleCache cache;
    private final AudioCacheDescriptors descriptors;
    private final java.util.function.LongSupplier clock;
    private volatile CacheWriter activeWriter;
    private String activeWriterKey;
    private boolean clearWhenWriterStops;

    private AudioPrefixCache(Context context) { this(context, System::currentTimeMillis); }

    AudioPrefixCache(Context context, java.util.function.LongSupplier clock) {
        this(context, clock, MAX_BYTES);
    }

    AudioPrefixCache(Context context, java.util.function.LongSupplier clock, long maxBytes) {
        this.clock = clock;
        cache = new SimpleCache(new File(context.getCacheDir(), "blackyt-audio-prefix"),
                new LeastRecentlyUsedCacheEvictor(maxBytes));
        descriptors = new AudioCacheDescriptors(new File(context.getCacheDir(), "blackyt-audio-descriptors"));
        maintain();
    }

    static AudioPrefixCache get(Context context) {
        AudioPrefixCache value = instance;
        if (value == null) synchronized (AudioPrefixCache.class) {
            value = instance;
            if (value == null) instance = value = new AudioPrefixCache(context.getApplicationContext());
        }
        return value;
    }

    void releaseForTest() { cache.release(); }

    static AudioPrefixCache peek() { return instance; }

    CacheDataSource.Factory factory(androidx.media3.datasource.DataSource.Factory upstream) {
        return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(spec -> spec.key == null ? spec.uri.toString() : spec.key)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    private CacheDataSource.Factory writerFactory(androidx.media3.datasource.DataSource.Factory upstream) {
        return new CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(spec -> spec.key == null ? spec.uri.toString() : spec.key);
    }

    private static String digest(String material) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception unavailable) { return null; }
    }

    private static String field(String value) {
        String v = value == null ? "" : value;
        return v.length() + ":" + v;
    }
    static String identity(String id, YouTubeRepository.Rules rules) {
        return digest(field(YouTubeRepository.normalizeVideoId(id))
                + field(rules != null && rules.musicOnly ? "1" : "0")
                + field(rules == null ? "" : rules.include)
                + field(rules == null ? "" : rules.exclude));
    }
    static String keyForFormat(String id, YouTubeRepository.Rules rules, String format) {
        if (format == null || format.isEmpty()) return null;
        String hash = digest(field(identity(id, rules)) + field(format));
        return hash == null ? null : "blackyt-audio-v2-" + hash;
    }
    static String key(String id, YouTubeRepository.Rules rules, YouTubeRepository.Resolved resolved) {
        return resolved == null ? null : keyForFormat(id, rules, resolved.formatKey);
    }

    synchronized void maintain() {
        java.util.Set<String> valid = descriptors.maintain(clock.getAsLong());
        if (activeWriter != null && !valid.contains(activeWriterKey)) activeWriter.cancel();
        for (String key : cache.getKeys()) if (!valid.contains(key)) cache.removeResource(key);
    }
    synchronized AudioCacheDescriptors.Entry descriptor(String id, YouTubeRepository.Rules rules) {
        maintain();
        AudioCacheDescriptors.Entry entry = descriptors.get(identity(id, rules), clock.getAsLong());
        if (entry != null && !entry.key.equals(keyForFormat(id, rules, entry.format))) {
            invalidate(entry.key); return null;
        }
        return entry;
    }
    synchronized boolean remember(String id, YouTubeRepository.Rules rules, String format) {
        maintain();
        String cacheKey = keyForFormat(id, rules, format);
        if (cacheKey == null) return false;
        AudioCacheDescriptors.Entry old = descriptors.get(identity(id, rules), clock.getAsLong());
        if (old != null && !old.key.equals(cacheKey)) cache.removeResource(old.key);
        return descriptors.put(identity(id, rules), cacheKey, format, clock.getAsLong());
    }
    synchronized boolean isCurrent(String id, YouTubeRepository.Rules rules, String key) {
        return descriptors.isCurrent(identity(id, rules), key, clock.getAsLong());
    }
    synchronized long statusBytes() { maintain(); return cache.getCacheSpace(); }
    synchronized long cachedBytes(String id, YouTubeRepository.Rules rules) {
        AudioCacheDescriptors.Entry entry = descriptor(id, rules);
        return entry == null ? 0 : cache.getCachedBytes(entry.key, 0, Long.MAX_VALUE);
    }

    void prefetch(String id, YouTubeRepository.Rules rules, YouTubeRepository.Resolved expected,
            BooleanSupplier stillAllowed)
            throws IOException {
        prefetch(id, rules, expected, PREFIX_BYTES, stillAllowed);
    }

    void prefetch(String id, YouTubeRepository.Rules rules, YouTubeRepository.Resolved expected,
            long maxBytes, BooleanSupplier stillAllowed) throws IOException {
        long limit = Math.min(MAX_PREFETCH_BYTES, Math.max(0, maxBytes));
        String cacheKey = key(id, rules, expected);
        if (limit == 0 || cacheKey == null || !remember(id, rules, expected.formatKey)
                || cache.isCached(cacheKey, 0, limit)) return;
        YouTubeRepository repository = YouTubeRepository.get();
        AtomicBoolean formatChanged = new AtomicBoolean();
        androidx.media3.datasource.DataSource.Factory upstream = () -> new RefreshingAudioDataSource(
                new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(repository.mediaClient())
                        .setUserAgent(YouTubeRepository.USER_AGENT), (dataSpec, fresh) -> {
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
                .setLength(limit).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build();
        CacheDataSource source = writerFactory(upstream).createDataSource();
        java.util.concurrent.atomic.AtomicReference<CacheWriter> writerRef = new java.util.concurrent.atomic.AtomicReference<>();
        CacheWriter writer = new CacheWriter(source, spec, new byte[CacheWriter.DEFAULT_BUFFER_SIZE_BYTES],
                (contentLength, bytesCached, newBytesCached) -> {
                    if ((stillAllowed != null && !stillAllowed.getAsBoolean())
                            || !isCurrent(id, rules, cacheKey)) {
                        CacheWriter current = writerRef.get();
                        if (current != null) current.cancel();
                    }
                });
        writerRef.set(writer);
        synchronized (this) {
            if (clearWhenWriterStops) { source.close(); return; }
            activeWriter = writer;
            activeWriterKey = cacheKey;
        }
        try {
            if (stillAllowed == null || stillAllowed.getAsBoolean()) writer.cache();
            else writer.cancel();
        }
        finally {
            try { source.close(); }
            finally {
                if (formatChanged.get()) invalidate(cacheKey);
                synchronized (this) {
                    activeWriter = null;
                    activeWriterKey = null;
                    if (clearWhenWriterStops) clearResources();
                    else maintain();
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
        Context app = context.getApplicationContext();
        maintenance.execute(() -> get(app).clear());
    }

    void invalidate(String cacheKey) {
        if (cacheKey != null) { descriptors.removeKey(cacheKey); cache.removeResource(cacheKey); }
    }

    synchronized void clear() {
        if (activeWriter != null) {
            clearWhenWriterStops = true;
            activeWriter.cancel();
            descriptors.clear();
            for (String key : cache.getKeys()) cache.removeResource(key);
            return;
        }
        clearResources();
    }

    private void clearResources() {
        descriptors.clear();
        for (String key : cache.getKeys()) cache.removeResource(key);
        clearWhenWriterStops = false;
    }

    static boolean hasPrefix(CacheDataSource.Factory factory, String key) {
        return key != null && factory.getCache() != null && factory.getCache().isCached(key, 0, PREFIX_BYTES);
    }
}
