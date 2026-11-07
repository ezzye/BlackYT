// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSource;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Adds the selected track identity to the cache key before any cached bytes are read. */
@androidx.media3.common.util.UnstableApi
final class KeyedAudioCacheDataSource implements DataSource {
    private final CacheDataSource.Factory cacheFactory;
    private final AudioPrefixCache cache;
    private final DataSource.Factory directFactory;
    private final java.util.ArrayList<TransferListener> listeners = new java.util.ArrayList<>();
    private DataSource delegate;
    private String cacheKey;
    private String itemId;
    private YouTubeRepository.Rules itemRules;

    KeyedAudioCacheDataSource(CacheDataSource.Factory cacheFactory, DataSource.Factory directFactory) {
        this.cacheFactory = cacheFactory;
        this.cache = null;
        this.directFactory = directFactory;
    }

    KeyedAudioCacheDataSource(AudioPrefixCache cache, DataSource.Factory directFactory) {
        this.cache = cache;
        this.cacheFactory = cache.factory(directFactory);
        this.directFactory = directFactory;
    }

    @Override public void addTransferListener(TransferListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
        if (delegate != null) delegate.addTransferListener(listener);
    }

    @Override public long open(DataSpec spec) throws IOException {
        close();
        NativeMediaItems.OwnedItem item = PlaybackService.parseOwnedDataSpec(spec);
        AudioCacheDescriptors.Entry descriptor = cache == null ? null : cache.descriptor(item.id, item.rules);
        String format;
        String key;
        if (descriptor != null) {
            key = descriptor.key;
            format = descriptor.format;
        } else {
            YouTubeRepository.Resolved resolved;
            try { resolved = YouTubeRepository.get().resolve(item.id, item.rules, false); }
            catch (Exception e) { throw new IOException("Unable to resolve this audio item.", e); }
            key = AudioPrefixCache.key(item.id, item.rules, resolved);
            format = resolved.formatKey;
            if (cache != null && key != null) {
                if (!cache.remember(item.id, item.rules, format)) key = null;
                else cache.rememberLength(item.id, item.rules, format, resolved.contentLength);
            }
        }
        if (!shouldUseCache(cacheFactory, key)) {
            delegate = directFactory.createDataSource();
            for (TransferListener listener : listeners) delegate.addTransferListener(listener);
            return delegate.open(spec.buildUpon().setKey(null).build());
        }
        if (!PlaybackFormatPins.accept(item.id, format))
            throw new AudioFormatChangedException();
        cacheKey = key;
        itemId = item.id;
        itemRules = item.rules;
        delegate = cacheFactory.createDataSource();
        for (TransferListener listener : listeners) delegate.addTransferListener(listener);
        try { return delegate.open(spec.buildUpon().setKey(key).build()); }
        catch (IOException failure) { invalidateOnFormatChange(failure); throw failure; }
    }

    static boolean shouldUseCache(CacheDataSource.Factory cacheFactory, String key) {
        return cacheFactory != null && key != null;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (delegate == null) throw new IOException("Audio source is not open.");
        checkCurrent();
        try {
            int count = delegate.read(buffer, offset, length);
            checkCurrent();
            return count;
        } catch (IOException failure) {
            invalidateOnFormatChange(failure);
            throw failure;
        }
    }
    private void invalidateOnFormatChange(IOException failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof AudioFormatChangedException)) {
            Throwable next = cause.getCause();
            if (next == cause) return;
            cause = next;
        }
        if (cause == null || cacheKey == null) return;
        String incompatibleKey = cacheKey;
        try { close(); }
        catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        if (cache != null) cache.invalidate(incompatibleKey);
        else if (cacheFactory != null && cacheFactory.getCache() != null)
            cacheFactory.getCache().removeResource(incompatibleKey);
    }
    private void checkCurrent() throws IOException {
        if (cache != null && cacheKey != null && !cache.isCurrent(itemId, itemRules, cacheKey)) {
            String expiredKey = cacheKey;
            AudioCacheInvalidatedException expired = new AudioCacheInvalidatedException();
            try { close(); } catch (IOException failure) { expired.addSuppressed(failure); }
            cache.invalidate(expiredKey);
            throw expired;
        }
    }
    @Override public Uri getUri() { return delegate == null ? null : delegate.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() {
        return delegate == null ? java.util.Collections.emptyMap() : delegate.getResponseHeaders();
    }
    @Override public void close() throws IOException {
        DataSource current = delegate;
        delegate = null;
        if (current != null) current.close();
        cacheKey = null;
        itemId = null;
        itemRules = null;
    }

}
