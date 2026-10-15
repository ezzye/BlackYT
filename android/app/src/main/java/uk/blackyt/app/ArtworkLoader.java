// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small, public-thumbnail-only loader. It never uses the streaming/media HTTP client. */
final class ArtworkLoader {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EDGE = 4096;
    private static final long MAX_PIXELS = 16_000_000L;
    private static final int TARGET_EDGE = 720;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor executor;
    private final LruCache<String, BitmapDrawable> cache;
    private final Map<ImageView, Future<?>> pending = new WeakHashMap<>();
    private volatile boolean closed;

    ArtworkLoader(Context context) {
        appContext = context.getApplicationContext();
        executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), runnable -> {
                    Thread thread = new Thread(runnable, "BlackYT-artwork");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        int maxBytes = (int) Math.max(2L * 1024L * 1024L,
                Runtime.getRuntime().maxMemory() / 12L);
        cache = new LruCache<String, BitmapDrawable>(maxBytes) {
            @Override protected int sizeOf(String key, BitmapDrawable drawable) {
                Bitmap bitmap = drawable.getBitmap();
                return bitmap == null ? 1 : Math.max(1, bitmap.getByteCount());
            }
        };
    }

    synchronized void load(String candidate, ImageView target) {
        if (closed || target == null) return;
        String url = YouTubeRepository.safeArtworkUrl(candidate);
        if (url.equals(target.getTag())) return;
        Future<?> old = pending.remove(target);
        if (old != null) old.cancel(true);
        target.setTag(url);
        target.setScaleType(ImageView.ScaleType.FIT_CENTER);
        target.setImageResource(R.drawable.blackyt_mascot);
        if (url.isEmpty()) return;
        BitmapDrawable cached = cache.get(url);
        if (cached != null) {
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            target.setImageDrawable(cached);
            return;
        }
        final FutureTask<Void>[] request = new FutureTask[1];
        FutureTask<Void> future;
        try {
            future = new FutureTask<>(() -> {
                BitmapDrawable drawable = fetch(url);
                synchronized (ArtworkLoader.this) {
                    if (drawable != null && !executor.isShutdown()) cache.put(url, drawable);
                    if (pending.get(target) != request[0] || Thread.currentThread().isInterrupted()) return null;
                    pending.remove(target);
                }
                if (drawable != null) main.post(() -> {
                    if (closed || !url.equals(target.getTag())) return;
                    target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    target.setImageDrawable(drawable);
                });
                return null;
            });
            request[0] = future;
            pending.put(target, future);
            executor.execute(future);
        } catch (RejectedExecutionException overloaded) {
            pending.remove(target);
            target.setTag(null);
            return;
        }
    }

    synchronized void cancel(ImageView target) {
        if (target == null) return;
        Future<?> future = pending.remove(target);
        if (future != null) future.cancel(true);
        target.setTag(null);
    }

    private BitmapDrawable fetch(String startUrl) {
        HttpURLConnection connection = null;
        try {
            URL current = new URL(startUrl);
            for (int redirects = 0; redirects <= 3; redirects++) {
                String safe = YouTubeRepository.safeArtworkUrl(current.toString());
                if (safe.isEmpty()) return null;
                connection = (HttpURLConnection) new URL(safe).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(7000);
                connection.setUseCaches(true);
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/jpeg,image/png,image/*;q=0.8");
                connection.setRequestProperty("User-Agent", YouTubeRepository.USER_AGENT);
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || redirects == 3) return null;
                    current = new URL(new URL(safe), location);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) return null;
                String type = connection.getContentType();
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) return null;
                long declaredSize = connection.getContentLengthLong();
                if (declaredSize > MAX_BYTES) return null;
                byte[] bytes = readBounded(connection.getInputStream());
                return decode(bytes);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    private byte[] readBounded(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) return null;
                total += read;
                if (total > MAX_BYTES) return null;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private BitmapDrawable decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int width = bounds.outWidth, height = bounds.outHeight;
        if (width < 1 || height < 1 || width > MAX_EDGE || height > MAX_EDGE
                || (long) width * (long) height > MAX_PIXELS) return null;
        int sample = 1;
        while (Math.max(width / sample, height / sample) > TARGET_EDGE) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return bitmap == null ? null : new BitmapDrawable(appContext.getResources(), bitmap);
    }

    synchronized void close() {
        closed = true;
        for (Future<?> future : pending.values()) future.cancel(true);
        pending.clear();
        executor.shutdownNow();
        cache.evictAll();
    }
}
