// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import androidx.media3.common.C;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

/** Back off network interruptions without retrying denied or incompatible streams. */
@androidx.media3.common.util.UnstableApi
final class ConnectionRetryPolicy extends DefaultLoadErrorHandlingPolicy {
    private final java.util.function.BooleanSupplier connected;
    ConnectionRetryPolicy() { this(() -> true); }
    ConnectionRetryPolicy(java.util.function.BooleanSupplier connected) {
        super(12);
        this.connected = connected;
    }

    static boolean isTransient(Throwable error) {
        for (int depth = 0; error != null && depth < 20; depth++, error = error.getCause()) {
            if (error instanceof AudioCacheInvalidatedException) return true;
            if (error instanceof AudioFormatChangedException) return false;
            if (error instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
                return code == 408 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
            }
            if (error instanceof java.net.SocketException || error instanceof java.net.SocketTimeoutException
                    || error instanceof java.net.UnknownHostException) return true;
        }
        return false;
    }

    @Override public long getRetryDelayMsFor(LoadErrorInfo info) {
        if (info.exception instanceof AudioCacheInvalidatedException) return 0;
        // Surface the miss to the service promptly so it can select cached successors.
        // When none are available, the service retains the item and resumes on reconnect.
        if (!connected.getAsBoolean()) return C.TIME_UNSET;
        if (isTransient(info.exception)) return info.errorCount > 12 ? C.TIME_UNSET
                : Math.min(15_000, 1_000L << Math.min(4, Math.max(0, info.errorCount - 1)));
        for (Throwable e = info.exception; e != null; e = e.getCause()) {
            if (e instanceof AudioFormatChangedException || e instanceof HttpDataSource.InvalidResponseCodeException)
                return C.TIME_UNSET;
        }
        return super.getRetryDelayMsFor(info);
    }
}
