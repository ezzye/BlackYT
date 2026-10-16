// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Retries one expired/denied signed audio URL with exactly one fresh resolution on HTTP 403. */
@androidx.media3.common.util.UnstableApi
final class RefreshingAudioDataSource implements DataSource {
    interface StreamResolver {
        Uri resolve(DataSpec original, boolean fresh) throws IOException;
    }

    private final DataSource.Factory http;
    private final StreamResolver resolver;
    private final List<TransferListener> transferListeners = new ArrayList<>();
    private DataSource upstream;

    RefreshingAudioDataSource(DataSource.Factory http, StreamResolver resolver) {
        this.http = Objects.requireNonNull(http);
        this.resolver = Objects.requireNonNull(resolver);
    }

    @Override public void addTransferListener(TransferListener listener) {
        if (listener == null || transferListeners.contains(listener)) return;
        transferListeners.add(listener);
        if (upstream != null) upstream.addTransferListener(listener);
    }

    @Override public long open(DataSpec original) throws IOException {
        close();
        Uri firstUri = requireResolvedUri(resolver.resolve(original, false));
        DataSource first = newUpstream();
        upstream = first;
        try {
            return first.open(original.withUri(firstUri));
        } catch (HttpDataSource.InvalidResponseCodeException failure) {
            if (failure.responseCode != 403) throw failure;
            closeAfterDenied(first, failure);
        }

        Uri freshUri = requireResolvedUri(resolver.resolve(original, true));
        DataSource retry = newUpstream();
        upstream = retry;
        // Deliberately propagate any retry failure; this source performs only one refresh.
        return retry.open(original.withUri(freshUri));
    }

    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (upstream == null) throw new IOException("Audio source is not open.");
        return upstream.read(buffer, offset, length);
    }

    @Override public Uri getUri() {
        return upstream == null ? null : upstream.getUri();
    }

    @Override public Map<String, List<String>> getResponseHeaders() {
        return upstream == null ? Collections.emptyMap() : upstream.getResponseHeaders();
    }

    @Override public void close() throws IOException {
        DataSource current = upstream;
        upstream = null;
        if (current != null) current.close();
    }

    private DataSource newUpstream() {
        DataSource created = http.createDataSource();
        for (TransferListener listener : transferListeners) created.addTransferListener(listener);
        return created;
    }

    private void closeAfterDenied(DataSource failed, IOException failure) {
        if (upstream == failed) upstream = null;
        try {
            failed.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Uri requireResolvedUri(Uri value) throws IOException {
        if (value == null) throw new IOException("Unable to resolve this audio item.");
        return value;
    }
}
