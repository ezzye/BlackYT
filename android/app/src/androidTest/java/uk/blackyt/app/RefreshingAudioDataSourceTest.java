// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class RefreshingAudioDataSourceTest {
    private static final String ITEM_URI = "blackyt://youtube/abc_DEF-123?music=0&include=&exclude=";
    private static final Uri FIRST_URL = Uri.parse("https://rr1.googlevideo.com/audio?sig=first");
    private static final Uri FRESH_URL = Uri.parse("https://rr2.googlevideo.com/audio?sig=fresh");

    @Test public void refreshesOnlyAfter403AndPreservesDataSpecAndListeners() throws Exception {
        DataSpec original = sampleSpec();
        FakeDataSource denied = FakeDataSource.denied(403, original);
        FakeDataSource success = FakeDataSource.success(new byte[] {1, 2, 3});
        FakeFactory factory = new FakeFactory(denied, success);
        FakeResolver resolver = new FakeResolver();
        RefreshingAudioDataSource source = new RefreshingAudioDataSource(factory, resolver);
        TransferListener listener = new NoOpTransferListener();
        source.addTransferListener(listener);

        assertEquals(12L, source.open(original));
        assertEquals(Arrays.asList(false, true), resolver.freshCalls);
        assertEquals(2, factory.created.size());
        assertEquals(1, denied.closeCalls);
        assertEquals(1, denied.listeners.size());
        assertSame(listener, denied.listeners.get(0));
        assertEquals(1, success.listeners.size());
        assertSame(listener, success.listeners.get(0));
        assertPreservedExceptUri(original, denied.openedSpec, FIRST_URL);
        assertPreservedExceptUri(original, success.openedSpec, FRESH_URL);
        assertEquals(FRESH_URL, source.getUri());
        assertEquals(Collections.singletonList("audio/test"),
                source.getResponseHeaders().get("Content-Type"));
        byte[] bytes = new byte[3];
        assertEquals(3, source.read(bytes, 0, bytes.length));
        assertArrayEquals(new byte[] {1, 2, 3}, bytes);
        source.close();
        assertEquals(1, success.closeCalls);
    }

    @Test public void second403PropagatesWithoutAnotherRefresh() throws Exception {
        DataSpec original = sampleSpec();
        FakeDataSource first = FakeDataSource.denied(403, original);
        FakeDataSource second = FakeDataSource.denied(403, original);
        FakeFactory factory = new FakeFactory(first, second);
        FakeResolver resolver = new FakeResolver();
        RefreshingAudioDataSource source = new RefreshingAudioDataSource(factory, resolver);
        try {
            source.open(original);
            fail("Expected second HTTP 403");
        } catch (HttpDataSource.InvalidResponseCodeException expected) {
            assertEquals(403, expected.responseCode);
        }
        assertEquals(Arrays.asList(false, true), resolver.freshCalls);
        assertEquals(2, factory.created.size());
        assertEquals(1, first.closeCalls);
        assertEquals(0, second.closeCalls);
        source.close();
        assertEquals(1, second.closeCalls);
    }

    @Test public void non403ResponseDoesNotRefresh() throws Exception {
        DataSpec original = sampleSpec();
        FakeDataSource notFound = FakeDataSource.denied(404, original);
        FakeFactory factory = new FakeFactory(notFound);
        FakeResolver resolver = new FakeResolver();
        RefreshingAudioDataSource source = new RefreshingAudioDataSource(factory, resolver);
        try {
            source.open(original);
            fail("Expected HTTP 404");
        } catch (HttpDataSource.InvalidResponseCodeException expected) {
            assertEquals(404, expected.responseCode);
        }
        assertEquals(Collections.singletonList(false), resolver.freshCalls);
        assertEquals(1, factory.created.size());
        assertEquals(0, notFound.closeCalls);
        source.close();
        assertEquals(1, notFound.closeCalls);
    }

    @Test public void resolverRejectsExternalUriBeforeOpeningHttp() throws Exception {
        FakeFactory factory = new FakeFactory(FakeDataSource.success(new byte[] {9}));
        RefreshingAudioDataSource source = new RefreshingAudioDataSource(factory,
                (original, fresh) -> {
                    if (!"blackyt".equals(original.uri.getScheme())) {
                        throw new IOException("Only app-owned items are supported.");
                    }
                    return FIRST_URL;
                });
        try {
            source.open(new DataSpec(Uri.parse("https://evil.example/audio")));
            fail("Expected the resolver to reject external URI");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("app-owned"));
        }
        assertTrue(factory.created.isEmpty());
        assertNull(source.getUri());
    }

    private static DataSpec sampleSpec() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Test-Header", "kept");
        return new DataSpec.Builder().setUri(Uri.parse(ITEM_URI))
                .setUriPositionOffset(13).setHttpMethod(DataSpec.HTTP_METHOD_GET)
                .setHttpRequestHeaders(headers).setPosition(17).setLength(64)
                .setKey("audio-key")
                .setFlags(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN
                        | DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .setCustomData("opaque-state").build();
    }

    private static void assertPreservedExceptUri(DataSpec original, DataSpec actual, Uri uri) {
        assertNotNull(actual);
        assertEquals(uri, actual.uri);
        assertEquals(original.uriPositionOffset, actual.uriPositionOffset);
        assertEquals(original.httpMethod, actual.httpMethod);
        assertArrayEquals(original.httpBody, actual.httpBody);
        assertEquals(original.httpRequestHeaders, actual.httpRequestHeaders);
        assertEquals(original.absoluteStreamPosition, actual.absoluteStreamPosition);
        assertEquals(original.position, actual.position);
        assertEquals(original.length, actual.length);
        assertEquals(original.key, actual.key);
        assertEquals(original.flags, actual.flags);
        assertSame(original.customData, actual.customData);
    }

    private static final class FakeResolver implements RefreshingAudioDataSource.StreamResolver {
        final List<Boolean> freshCalls = new java.util.ArrayList<>();
        @Override public Uri resolve(DataSpec original, boolean fresh) {
            freshCalls.add(fresh);
            return fresh ? FRESH_URL : FIRST_URL;
        }
    }

    private static final class FakeFactory implements DataSource.Factory {
        final ArrayDeque<FakeDataSource> queued = new ArrayDeque<>();
        final List<FakeDataSource> created = new java.util.ArrayList<>();
        FakeFactory(FakeDataSource... sources) { queued.addAll(Arrays.asList(sources)); }
        @Override public DataSource createDataSource() {
            FakeDataSource source = queued.removeFirst();
            created.add(source);
            return source;
        }
    }

    private static final class FakeDataSource implements DataSource {
        final IOException failure;
        final byte[] bytes;
        final List<TransferListener> listeners = new java.util.ArrayList<>();
        DataSpec openedSpec;
        Uri uri;
        int offset;
        int closeCalls;

        private FakeDataSource(IOException failure, byte[] bytes) {
            this.failure = failure;
            this.bytes = bytes;
        }
        static FakeDataSource denied(int status, DataSpec request) {
            return new FakeDataSource(new HttpDataSource.InvalidResponseCodeException(status,
                    "denied", null, Collections.emptyMap(), request, new byte[0]), new byte[0]);
        }
        static FakeDataSource success(byte[] bytes) { return new FakeDataSource(null, bytes); }
        @Override public void addTransferListener(TransferListener listener) { listeners.add(listener); }
        @Override public long open(DataSpec dataSpec) throws IOException {
            openedSpec = dataSpec;
            uri = dataSpec.uri;
            if (failure != null) throw failure;
            return 12L;
        }
        @Override public int read(byte[] buffer, int bufferOffset, int length) {
            if (offset >= bytes.length) return -1;
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, buffer, bufferOffset, count);
            offset += count;
            return count;
        }
        @Override public Uri getUri() { return uri; }
        @Override public Map<String, List<String>> getResponseHeaders() {
            return Collections.singletonMap("Content-Type", Collections.singletonList("audio/test"));
        }
        @Override public void close() { closeCalls++; }
    }

    private static final class NoOpTransferListener implements TransferListener {
        @Override public void onTransferInitializing(DataSource source, DataSpec spec, boolean network) {}
        @Override public void onTransferStart(DataSource source, DataSpec spec, boolean network) {}
        @Override public void onBytesTransferred(DataSource source, DataSpec spec, boolean network, int bytes) {}
        @Override public void onTransferEnd(DataSource source, DataSpec spec, boolean network) {}
    }
}
