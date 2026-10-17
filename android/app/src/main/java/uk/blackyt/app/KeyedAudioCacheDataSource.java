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
    private final DataSource.Factory directFactory;
    private final java.util.ArrayList<TransferListener> listeners = new java.util.ArrayList<>();
    private DataSource delegate;
    private String cacheKey;

    KeyedAudioCacheDataSource(CacheDataSource.Factory cacheFactory, DataSource.Factory directFactory) {
        this.cacheFactory = cacheFactory;
        this.directFactory = directFactory;
    }

    @Override public void addTransferListener(TransferListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
        if (delegate != null) delegate.addTransferListener(listener);
    }

    @Override public long open(DataSpec spec) throws IOException {
        close();
        NativeMediaItems.OwnedItem item = PlaybackService.parseOwnedDataSpec(spec);
        YouTubeRepository.Resolved resolved;
        try { resolved = YouTubeRepository.get().resolve(item.id, item.rules, false); }
        catch (Exception e) { throw new IOException("Unable to resolve this audio item."); }
        String key = AudioPrefixCache.key(item.id, item.rules, resolved);
        if (!shouldUseCache(cacheFactory, key)) {
            delegate = directFactory.createDataSource();
            for (TransferListener listener : listeners) delegate.addTransferListener(listener);
            return delegate.open(spec.buildUpon().setKey(null).build());
        }
        if (!PlaybackFormatPins.accept(item.id, resolved.formatKey))
            throw new AudioFormatChangedException();
        cacheKey = key;
        delegate = cacheFactory.createDataSource();
        for (TransferListener listener : listeners) delegate.addTransferListener(listener);
        try { return delegate.open(spec.buildUpon().setKey(key).build()); }
        catch (IOException cacheFailure) {
            discardCachedPrefix(cacheFailure);
            delegate = directFactory.createDataSource();
            for (TransferListener listener : listeners) delegate.addTransferListener(listener);
            return delegate.open(spec.buildUpon().setKey(null).build());
        }
    }

    static boolean shouldUseCache(CacheDataSource.Factory cacheFactory, String key) {
        return cacheFactory != null && key != null;
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (delegate == null) throw new IOException("Audio source is not open.");
        try { return delegate.read(buffer, offset, length); }
        catch (IOException failure) { discardCachedPrefix(failure); throw failure; }
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
    }

    private void discardCachedPrefix(IOException original) {
        String key = cacheKey;
        try { close(); }
        catch (IOException closeFailure) { original.addSuppressed(closeFailure); }
        if (key != null && cacheFactory.getCache() != null)
            cacheFactory.getCache().removeResource(key);
    }
}
