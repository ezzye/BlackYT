/* SPDX-License-Identifier: GPL-3.0-or-later */
package uk.blackyt.app;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ExpiringSingleFlightCacheTest {
    @Test public void coalescesSameKeyLoads() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExpiringSingleFlightCache<String, String> c = new ExpiringSingleFlightCache<>(4, 1000,
                System::currentTimeMillis, k -> { calls.incrementAndGet(); entered.countDown(); release.await(); return "v"; });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> c.get("k", false));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Future<String> b = pool.submit(() -> c.get("k", false));
            release.countDown();
            assertEquals("v", a.get(1, TimeUnit.SECONDS));
            assertEquals("v", b.get(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test public void unrelatedKeyCanLoadWhileOneIsBlocked() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExpiringSingleFlightCache<String, String> c = new ExpiringSingleFlightCache<>(4, 1000,
                System::currentTimeMillis, k -> { if (k.equals("a")) { blocked.countDown(); release.await(); } return k; });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> c.get("a", false));
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            assertEquals("b", pool.submit(() -> c.get("b", false)).get(1, TimeUnit.SECONDS));
            release.countDown(); assertEquals("a", a.get(1, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test public void freshSupersedesOlderFlightAndOlderCannotOverwrite() throws Exception {
        CountDownLatch oldEntered = new CountDownLatch(1), releaseOld = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExpiringSingleFlightCache<String, String> c = new ExpiringSingleFlightCache<>(4, 1000,
                System::currentTimeMillis, k -> { if (calls.incrementAndGet() == 1) { oldEntered.countDown(); return waitThen(releaseOld, "old"); } return "new"; });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> old = pool.submit(() -> c.get("k", false));
            assertTrue(oldEntered.await(1, TimeUnit.SECONDS));
            assertEquals("new", c.get("k", true));
            releaseOld.countDown(); assertEquals("old", old.get(1, TimeUnit.SECONDS));
            assertEquals("new", c.get("k", false));
            assertEquals(2, calls.get());
        } finally { releaseOld.countDown(); pool.shutdownNow(); }
    }

    private static String waitThen(CountDownLatch latch, String value) throws Exception { latch.await(); return value; }

    @Test public void invalidatePreventsLateFillButOldWaiterCompletes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExpiringSingleFlightCache<String, String> c = new ExpiringSingleFlightCache<>(4, 1000,
                System::currentTimeMillis, k -> { if (calls.incrementAndGet() == 1) { entered.countDown(); return waitThen(release, "old"); } return "new"; });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> old = pool.submit(() -> c.get("k", false)); assertTrue(entered.await(1, TimeUnit.SECONDS));
            c.invalidate("k"); assertEquals("new", c.get("k", false));
            release.countDown(); assertEquals("old", old.get(1, TimeUnit.SECONDS));
            assertEquals("new", c.get("k", false));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test public void ttlAndLruBoundAreEnforced() throws Exception {
        AtomicLong now = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        ExpiringSingleFlightCache<String, String> c = new ExpiringSingleFlightCache<>(2, 10, now::get,
                k -> k + calls.incrementAndGet());
        String a = c.get("a", false); c.get("b", false); c.get("a", false); c.get("c", false);
        assertEquals(a, c.get("a", false)); // touch keeps a most-recently-used
        c.get("b", false); assertEquals(4, calls.get()); // b was evicted
        now.set(10); c.get("a", false); assertEquals(5, calls.get()); // expires at boundary
    }

    @Test public void failedLoadIsRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ExpiringSingleFlightCache<String, String> c = new ExpiringSingleFlightCache<>(2, 1000,
                System::currentTimeMillis, k -> { if (calls.incrementAndGet() == 1) throw new java.io.IOException("offline"); return "ok"; });
        try { c.get("k", false); fail("expected failure"); } catch (java.io.IOException expected) { assertEquals("offline", expected.getMessage()); }
        assertEquals("ok", c.get("k", false)); assertEquals(2, calls.get());
    }

    @Test public void failedFreshLoadDoesNotRestoreStaleValue() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ExpiringSingleFlightCache<String, String> c = new ExpiringSingleFlightCache<>(2, 1000,
                System::currentTimeMillis, k -> {
                    int n = calls.incrementAndGet();
                    if (n == 2) throw new java.io.IOException("refresh failed");
                    return n == 1 ? "stale" : "replacement";
                });
        assertEquals("stale", c.get("k", false));
        try { c.get("k", true); fail("expected refresh failure"); }
        catch (java.io.IOException expected) { assertEquals("refresh failed", expected.getMessage()); }
        assertEquals("replacement", c.get("k", false));
        assertEquals(3, calls.get());
    }
}
