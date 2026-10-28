// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Opens complete, unexpired app-owned downloads before any stream resolution or network request. */
@androidx.media3.common.util.UnstableApi
final class OfflineFirstAudioDataSource implements DataSource {
    private final OfflineAudioStore downloads;
    private final DataSource.Factory streaming;
    private final List<TransferListener> listeners = new ArrayList<>();
    private DataSource delegate;

    OfflineFirstAudioDataSource(OfflineAudioStore downloads, DataSource.Factory streaming) {
        this.downloads = downloads;
        this.streaming = streaming;
    }

    @Override public void addTransferListener(TransferListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
        if (delegate != null) delegate.addTransferListener(listener);
    }

    @Override public long open(DataSpec spec) throws IOException {
        close();
        NativeMediaItems.OwnedItem owned = PlaybackService.parseOwnedDataSpec(spec);
        File complete = downloads.open(owned.id, owned.rules);
        delegate = complete == null ? streaming.createDataSource() : new FileDataSource();
        for (TransferListener listener : listeners) delegate.addTransferListener(listener);
        return delegate.open(complete == null ? spec
                : spec.buildUpon().setUri(Uri.fromFile(complete)).setKey(null).build());
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (delegate == null) throw new IOException("Audio source is not open.");
        return delegate.read(buffer, offset, length);
    }
    @Override public Uri getUri() { return delegate == null ? null : delegate.getUri(); }
    @Override public Map<String, List<String>> getResponseHeaders() {
        return delegate == null ? Collections.emptyMap() : delegate.getResponseHeaders();
    }
    @Override public void close() throws IOException {
        DataSource current = delegate;
        delegate = null;
        if (current != null) current.close();
    }
}
