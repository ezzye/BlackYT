// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ConnectionRetryPolicyTest {
    @Test public void retriesSignalLossButNotDeniedContentOrChangedAudio() {
        assertTrue(ConnectionRetryPolicy.isTransient(new java.io.IOException("resolve", new java.net.UnknownHostException())));
        assertTrue(ConnectionRetryPolicy.isTransient(new java.net.SocketTimeoutException()));
        assertTrue(ConnectionRetryPolicy.isTransient(new AudioCacheInvalidatedException()));
        assertTrue(ConnectionRetryPolicy.isTransient(response(503)));
        assertFalse(ConnectionRetryPolicy.isTransient(response(403)));
        assertFalse(ConnectionRetryPolicy.isTransient(response(404)));
        assertFalse(ConnectionRetryPolicy.isTransient(new AudioFormatChangedException()));
        assertFalse(ConnectionRetryPolicy.isTransient(new java.io.IOException("unsupported format")));
    }
    private HttpDataSource.InvalidResponseCodeException response(int code) {
        return new HttpDataSource.InvalidResponseCodeException(code, "test", null,
                java.util.Collections.emptyMap(), new DataSpec(Uri.parse("https://test.invalid/audio")), new byte[0]);
    }
}
