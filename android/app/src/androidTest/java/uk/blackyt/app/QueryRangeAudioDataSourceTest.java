// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class QueryRangeAudioDataSourceTest {
    private static final long TOTAL = 2_100_000;
    private static final Uri URI = Uri.parse("https://rr1.googlevideo.com/audio?clen=" + TOTAL + "&sig=fixture");

    @Test public void readsContiguousWindowsWithNonzeroSeekAndEof() throws Exception {
        Fixture factory = new Fixture();
        QueryRangeAudioDataSource source = new QueryRangeAudioDataSource(factory);
        long start = 75, length = 2_000_000;
        DataSpec spec = new DataSpec.Builder().setUri(URI).setPosition(start).setLength(length)
                .setHttpRequestHeaders(Collections.singletonMap("X-Test", "retained")).build();
        assertEquals(length, source.open(spec));
        assertEquals(URI, source.getUri());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[7001]; int n;
        while ((n = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) output.write(buffer, 0, n);
        byte[] actual = output.toByteArray();
        assertEquals(length, actual.length);
        for (int i = 0; i < actual.length; i++) assertEquals("position " + i, sample(start + i), actual[i]);
        assertEquals(2, factory.specs.size());
        assertEquals("75-1048650", factory.specs.get(0).uri.getQueryParameter("range"));
        assertEquals("1048651-2000074", factory.specs.get(1).uri.getQueryParameter("range"));
        for (DataSpec part : factory.specs) {
            assertEquals(0, part.position);
            assertEquals(C.LENGTH_UNSET, part.length);
            assertEquals("retained", part.httpRequestHeaders.get("X-Test"));
        }
        source.close();
        assertEquals(2, factory.closed);
        assertNull(source.getUri());
    }

    @Test public void clipsAtEndAndZeroRemainingDoesNotOpenNetwork() throws Exception {
        Fixture factory = new Fixture(); QueryRangeAudioDataSource source = new QueryRangeAudioDataSource(factory);
        assertEquals(7, source.open(new DataSpec.Builder().setUri(URI).setPosition(TOTAL - 7).setLength(50).build()));
        byte[] b = new byte[20]; assertEquals(7, source.read(b, 0, b.length));
        assertEquals(C.RESULT_END_OF_INPUT, source.read(b, 0, b.length));
        assertEquals((byte)sample(TOTAL-1), b[6]); source.close();
        assertEquals(0, source.open(new DataSpec.Builder().setUri(URI).setPosition(TOTAL).build()));
        assertEquals(C.RESULT_END_OF_INPUT, source.read(b, 0, b.length));
        assertEquals(1, factory.specs.size()); source.close();
    }

    @Test public void rejectsWrongHeaderAndEarlyEnd() throws Exception {
        Fixture wrong = new Fixture(); wrong.wrongLength = true;
        QueryRangeAudioDataSource source = new QueryRangeAudioDataSource(wrong);
        try { source.open(new DataSpec(URI)); fail("Expected wrong length failure"); }
        catch (IOException expected) { assertEquals(1, wrong.closed); }
        source.close();
        Fixture shortBody = new Fixture(); shortBody.shortBody = true;
        source = new QueryRangeAudioDataSource(shortBody);
        source.open(new DataSpec.Builder().setUri(URI).setLength(10).build());
        byte[] b = new byte[10]; assertEquals(9, source.read(b, 0, 10));
        try { source.read(b, 0, 10); fail("Expected EOF failure"); }
        catch (EOFException expected) { }
        source.close(); assertEquals(1, shortBody.closed);
    }

    @Test public void missingSizeOrUnsupportedMethodUsesOriginalSpec() throws Exception {
        Fixture factory = new Fixture(); QueryRangeAudioDataSource source = new QueryRangeAudioDataSource(factory);
        DataSpec missing = new DataSpec.Builder().setUri("https://rr1.googlevideo.com/a?sig=fixture").setPosition(4).build();
        source.open(missing); assertSame(missing, factory.specs.get(0)); source.close();
        DataSpec post = new DataSpec.Builder().setUri(URI).setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(new byte[]{1}).build();
        source.open(post); assertSame(post, factory.specs.get(1)); source.close();
    }
    private static byte sample(long position) { return (byte)(position * 31 + position / 997); }
    private static final class Fixture implements DataSource.Factory {
        final List<DataSpec> specs = new ArrayList<>(); int closed; boolean wrongLength, shortBody;
        @Override public DataSource createDataSource() { return new DataSource() {
            long position, remaining, reported; boolean open; Uri uri;
            @Override public void addTransferListener(TransferListener listener) { }
            @Override public long open(DataSpec spec) {
                specs.add(spec); uri = spec.uri; open = true;
                String range = spec.uri.getQueryParameter("range");
                if (range == null) { position = 0; remaining = reported = 0; return 0; }
                String[] parts = range.split("-"); position = Long.parseLong(parts[0]);
                reported = Long.parseLong(parts[1]) - position + 1;
                remaining = shortBody ? reported - 1 : reported;
                return C.LENGTH_UNSET; // Header validation must not rely on open()'s return value.
            }
            @Override public int read(byte[] target, int offset, int length) {
                if (remaining == 0) return C.RESULT_END_OF_INPUT;
                int count = (int)Math.min(length, remaining);
                for (int i=0;i<count;i++) target[offset+i] = sample(position++);
                remaining -= count; return count;
            }
            @Override public Uri getUri() { return uri; }
            @Override public Map<String,List<String>> getResponseHeaders() {
                return Collections.singletonMap("content-length", Collections.singletonList(Long.toString(reported + (wrongLength ? 1 : 0))));
            }
            @Override public void close() { if (open) { closed++; open = false; } }
        }; }
    }
}
