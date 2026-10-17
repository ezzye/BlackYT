/* SPDX-License-Identifier: GPL-3.0-or-later */
package uk.blackyt.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.LongSupplier;

/** A bounded TTL cache that coalesces concurrent loads for the same key. */
public final class ExpiringSingleFlightCache<K, V> {
    @FunctionalInterface
    public interface Loader<K, V> {
        V load(K key) throws Exception;
    }

    private static final class Entry<V> {
        final V value;
        final long expiresAt;
        Entry(V value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    private final int limit;
    private final long ttlMs;
    private final LongSupplier clock;
    private final Loader<K, V> loader;
    private final Object lock = new Object();
    private final LinkedHashMap<K, Entry<V>> values = new LinkedHashMap<>(16, .75f, true);
    private final Map<K, FutureTask<V>> flights = new java.util.HashMap<>();

    public ExpiringSingleFlightCache(int limit, long ttlMs, LongSupplier clock, Loader<K, V> loader) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (ttlMs < 0) throw new IllegalArgumentException("ttlMs must not be negative");
        this.limit = limit;
        this.ttlMs = ttlMs;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public V get(K key, boolean fresh) throws Exception {
        Objects.requireNonNull(key, "key");
        FutureTask<V> task;
        boolean creator = false;
        synchronized (lock) {
            long now = clock.getAsLong();
            task = fresh ? null : flights.get(key);
            if (task == null && !fresh) {
                Entry<V> entry = values.get(key);
                if (entry != null) {
                    if (now < entry.expiresAt) return entry.value;
                    values.remove(key);
                }
            }
            if (task == null) {
                if (fresh) values.remove(key);
                final FutureTask<V>[] holder = new FutureTask[1];
                task = new FutureTask<>(() -> {
                    try {
                        V loaded = loader.load(key);
                        synchronized (lock) {
                            if (flights.get(key) == holder[0]) {
                                if (ttlMs > 0) {
                                    long completedAt = clock.getAsLong();
                                    long expiry = completedAt > Long.MAX_VALUE - ttlMs
                                            ? Long.MAX_VALUE : completedAt + ttlMs;
                                    values.put(key, new Entry<>(loaded, expiry));
                                    while (values.size() > limit) {
                                        values.remove(values.keySet().iterator().next());
                                    }
                                }
                            }
                            return loaded;
                        }
                    } finally {
                        synchronized (lock) {
                            if (flights.get(key) == holder[0]) flights.remove(key);
                        }
                    }
                });
                holder[0] = task;
                flights.put(key, task);
                creator = true;
            }
        }
        if (creator) task.run();
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }

    /** Removes the cached value and detaches any current load without interrupting its waiters. */
    public void invalidate(K key) {
        synchronized (lock) {
            values.remove(key);
            flights.remove(key);
        }
    }

    public void clear() {
        synchronized (lock) {
            values.clear();
            flights.clear();
        }
    }
}
