// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Reads known-length Googlevideo audio in bounded windows, with exact byte-count checks. */
@androidx.media3.common.util.UnstableApi
final class QueryRangeAudioDataSource implements DataSource {
    private static final long WINDOW_BYTES = 1024L * 1024;
    private final DataSource.Factory factory;
    private final List<TransferListener> listeners = new ArrayList<>();
    private DataSource upstream;
    private DataSpec original;
    private long nextPosition, remaining, windowRemaining;
    private boolean chunked;

    QueryRangeAudioDataSource(DataSource.Factory http) { factory = Objects.requireNonNull(http); }
    @Override public void addTransferListener(TransferListener listener) {
        if (listener == null || listeners.contains(listener)) return;
        listeners.add(listener);
        if (upstream != null) upstream.addTransferListener(listener);
    }
    @Override public long open(DataSpec spec) throws IOException {
        close();
        original = spec;
        long total = eligibleLength(spec);
        if (total < 0) {
            chunked = false;
            upstream = create();
            return upstream.open(spec);
        }
        if (spec.position > total) throw new EOFException("Audio position beyond end");
        chunked = true;
        nextPosition = spec.position;
        remaining = Math.min(total - spec.position,
                spec.length == C.LENGTH_UNSET ? Long.MAX_VALUE : spec.length);
        if (remaining > 0) openWindow();
        return remaining;
    }
    private void openWindow() throws IOException {
        long count = Math.min(WINDOW_BYTES, remaining);
        long end = nextPosition + count - 1;
        Uri url = original.uri.buildUpon().appendQueryParameter("range", nextPosition + "-" + end).build();
        DataSpec window = original.buildUpon().setUri(url).setPosition(0)
                .setLength(C.LENGTH_UNSET).build();
        upstream = create();
        try {
            upstream.open(window);
            if (contentLength(upstream.getResponseHeaders()) != count)
                throw new IOException("Audio range returned an unexpected Content-Length");
            windowRemaining = count;
        } catch (IOException failure) {
            DataSource failed = upstream;
            upstream = null;
            try { failed.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    @Override public int read(byte[] target, int offset, int length) throws IOException {
        if (original == null) throw new IOException("Audio source is not open");
        if (length == 0) return 0;
        if (!chunked) return upstream.read(target, offset, length);
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        if (windowRemaining == 0) {
            closeUpstream();
            openWindow();
        }
        int n = upstream.read(target, offset, (int)Math.min(length, windowRemaining));
        if (n == C.RESULT_END_OF_INPUT) throw new EOFException("Audio range ended early");
        if (n > 0) { nextPosition += n; remaining -= n; windowRemaining -= n; }
        return n;
    }
    @Override public Uri getUri() { return original == null ? null : original.uri; }
    @Override public Map<String, List<String>> getResponseHeaders() {
        return upstream == null ? Collections.emptyMap() : upstream.getResponseHeaders();
    }
    @Override public void close() throws IOException {
        original = null;
        chunked = false;
        remaining = windowRemaining = 0;
        closeUpstream();
    }
    private void closeUpstream() throws IOException {
        DataSource current = upstream;
        upstream = null;
        if (current != null) current.close();
    }
    private DataSource create() {
        DataSource data = factory.createDataSource();
        for (TransferListener listener : listeners) data.addTransferListener(listener);
        return data;
    }
    private static long eligibleLength(DataSpec spec) {
        Uri uri = spec.uri;
        String host = uri.getHost();
        if (spec.httpMethod != DataSpec.HTTP_METHOD_GET || !"https".equalsIgnoreCase(uri.getScheme())
                || host == null || !(host.equalsIgnoreCase("googlevideo.com")
                || host.toLowerCase(Locale.US).endsWith(".googlevideo.com"))
                || uri.getQueryParameters("clen").size() != 1 || uri.getQueryParameter("range") != null)
            return -1;
        for (String header : spec.httpRequestHeaders.keySet())
            if ("range".equalsIgnoreCase(header)) return -1;
        String size = uri.getQueryParameter("clen");
        if (size == null || !size.matches("[0-9]{1,18}")) return -1;
        try { long n = Long.parseLong(size); return n > 0 ? n : -1; }
        catch (NumberFormatException e) { return -1; }
    }
    private static long contentLength(Map<String, List<String>> headers) {
        String value = null;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey() != null && header.getKey().equalsIgnoreCase("Content-Length")) {
                if (value != null || header.getValue() == null || header.getValue().size() != 1) return -1;
                value = header.getValue().get(0);
            }
        }
        if (value == null || !value.matches("[0-9]{1,18}")) return -1;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return -1; }
    }
}
