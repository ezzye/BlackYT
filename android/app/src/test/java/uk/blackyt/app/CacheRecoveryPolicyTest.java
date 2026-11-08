// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CacheRecoveryPolicyTest {
    @Test public void onlyCacheErrorsGetOneCleanRestartPerItem() {
        CacheRecoveryPolicy p = new CacheRecoveryPolicy();
        p.transition("one");
        assertFalse(p.take("one", new IOException("network")));
        assertTrue(p.take("one", new IOException(new AudioFormatChangedException())));
        assertFalse(p.take("one", new AudioCacheInvalidatedException()));
        p.transition(""); // Playlist rebuild can briefly have no current item.
        p.transition("one");
        assertFalse(p.take("one", new AudioFormatChangedException()));
        p.transition("two");
        assertTrue(p.take("two", new AudioCacheInvalidatedException()));
    }
    @Test public void onlySustainedPlayingResetsTheLimit() {
        CacheRecoveryPolicy p = new CacheRecoveryPolicy();
        p.transition("one");
        assertTrue(p.take("one", new AudioFormatChangedException()));
        p.playing(true, 100); p.playing(true, 30_099);
        assertFalse(p.take("one", new AudioFormatChangedException()));
        p.playing(false, 30_099); p.playing(true, 50_000); p.playing(true, 80_000);
        assertTrue(p.take("one", new AudioFormatChangedException()));
    }
}
