// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Protocol;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okio.BufferedSource;
import okio.Okio;

/** Temporary, user-requested downloads. Work runs sequentially only while the app process lives. */
@androidx.media3.common.util.UnstableApi
public final class OfflineAudioStore {
    public static final long EXPIRY_MS = 24L * 60 * 60 * 1000;
    public static final long TOTAL_LIMIT_BYTES = 512L * 1024 * 1024;
    public static final long ITEM_LIMIT_BYTES = 256L * 1024 * 1024;
    public static final int MAX_ENTRIES = 100;
    private static volatile OfflineAudioStore instance;

    public enum Status { QUEUED, DOWNLOADING, READY, FAILED }

    /** Immutable snapshot; call list() again to observe progress. */
    public static final class Entry {
        public final YouTubeRepository.Track track;
        public final YouTubeRepository.Rules rules;
        public final Status status;
        public final long bytesDownloaded, totalBytes, expiresAtMs;
        public final String error;
        private Entry(YouTubeRepository.Track track, YouTubeRepository.Rules rules, Status status,
                      long bytesDownloaded, long totalBytes, long expiresAtMs, String error) {
            this.track = track; this.rules = rules; this.status = status;
            this.bytesDownloaded = bytesDownloaded; this.totalBytes = totalBytes;
            this.expiresAtMs = expiresAtMs; this.error = error;
        }
    }

    private static final class Job {
        final YouTubeRepository.Track track;
        final YouTubeRepository.Rules rules;
        Status status;
        long bytes, total, expiry;
        String error = "";
        volatile boolean cancelled;
        volatile Call call;
        FutureTask<Void> task;
        Job(YouTubeRepository.Track track, YouTubeRepository.Rules rules, Status status) {
            this.track = track; this.rules = rules; this.status = status;
        }
        Entry snapshot() { return new Entry(track, rules, status, bytes, total, expiry, error); }
    }

    interface TestSource { Response open(String id, YouTubeRepository.Rules rules) throws Exception; }

    private final TestSource testSource;
    private final File directory;
    private final AtomicFile metadata;
    // One active task and at most MAX_ENTRIES - 1 waiting tasks, including cancelled work.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_ENTRIES - 1));
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private boolean playbackBusy;

    public static OfflineAudioStore get(Context context) {
        OfflineAudioStore result = instance;
        if (result == null) synchronized (OfflineAudioStore.class) {
            result = instance;
            if (result == null) instance = result = new OfflineAudioStore(context.getApplicationContext());
        }
        return result;
    }

    OfflineAudioStore(Context context) { this(context, null); }

    OfflineAudioStore(Context context, TestSource testSource) {
        this.testSource = testSource;
        directory = new File(context.getFilesDir(), "offline-audio");
        metadata = new AtomicFile(new File(directory, "index.json"));
        if (!directory.isDirectory()) directory.mkdirs();
        load();
    }

    /** Pause transfers while streaming playback needs bandwidth. */
    public synchronized void setPlaybackBusy(boolean busy) {
        playbackBusy = busy;
        if (!busy) notifyAll();
    }

    private synchronized void awaitPlayback(Job job) throws IOException {
        while (playbackBusy && current(job)) {
            try { wait(); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted.");
            }
        }
        if (!current(job)) throw new IOException("Cancelled.");
    }

    public synchronized List<Entry> list() {
        clearExpired();
        List<Entry> result = new ArrayList<>();
        for (Job job : jobs.values()) result.add(job.snapshot());
        return Collections.unmodifiableList(result);
    }

    /** Replaces a previous item with this ID; queues work without requiring a service. */
    public synchronized void download(YouTubeRepository.Track track, YouTubeRepository.Rules rules) {
        if (track == null) throw new IllegalArgumentException("Missing track");
        String id = YouTubeRepository.normalizeVideoId(track.id);
        YouTubeRepository.Rules safe = rules == null ? new YouTubeRepository.Rules(false, "", "") : rules;
        if (safe.include.length() > 1000 || safe.exclude.length() > 1000)
            throw new IllegalArgumentException("Invalid playback rules");
        clearExpired();
        Job existing = jobs.get(id);
        if (existing != null && existing.status == Status.READY) {
            if (sameRules(existing.rules, safe)) return;
            throw new IllegalStateException("Remove the existing download before replacing it.");
        }
        if (existing == null && jobs.size() >= MAX_ENTRIES)
            throw new IllegalStateException("Temporary download list is full. Remove an item and retry.");
        remove(id);
        YouTubeRepository.Track normalized = new YouTubeRepository.Track(id, track.title, track.channel,
                track.durationSeconds, track.artworkUrl, track.creatorUrl);
        Job job = new Job(normalized, safe, Status.QUEUED);
        jobs.put(id, job);
        job.task = new FutureTask<>(() -> { run(job); return null; });
        try {
            worker.execute(job.task);
            persistQuietly();
        } catch (RejectedExecutionException full) {
            jobs.remove(id);
            persistQuietly();
            throw new IllegalStateException("Temporary download queue is full. Try again shortly.");
        }
    }

    /** Cancels an active or queued transfer, and removes its partial file and entry. */
    public synchronized void cancel(String id) { remove(id); }

    public synchronized void remove(String inputId) {
        String id = YouTubeRepository.normalizeVideoId(inputId);
        Job old = jobs.remove(id);
        if (old != null) {
            old.cancelled = true;
            if (old.task != null) {
                old.task.cancel(false);
                worker.remove(old.task);
            }
            if (old.call != null) old.call.cancel();
            notifyAll();
        }
        // The worker owns its unique partial file and deletes it when cancellation is observed.
        file(id).delete();
        persistQuietly();
    }

    public synchronized void clearExpired() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        java.util.Iterator<Map.Entry<String, Job>> iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Job> item = iterator.next();
            Job job = item.getValue();
            if (job.status == Status.READY && (job.expiry <= now || !validFile(job))) {
                file(item.getKey()).delete(); iterator.remove(); changed = true;
            }
        }
        if (changed) persistQuietly();
    }

    /** Returns a complete, unexpired local file only when the original rules match exactly. */
    public synchronized File open(String inputId, YouTubeRepository.Rules rules) {
        String id = YouTubeRepository.normalizeVideoId(inputId);
        clearExpired();
        Job job = jobs.get(id);
        YouTubeRepository.Rules safe = rules == null ? new YouTubeRepository.Rules(false, "", "") : rules;
        return job != null && job.status == Status.READY && sameRules(job.rules, safe) && validFile(job)
                ? file(id) : null;
    }

    int pendingTaskCountForTests() { return worker.getQueue().size(); }

    /** Test-only lifecycle for isolated stores; the application singleton lives with the process. */
    void shutdownForTests() {
        synchronized (this) {
            for (Job job : jobs.values()) {
                job.cancelled = true;
                if (job.call != null) job.call.cancel();
            }
            notifyAll();
            worker.shutdownNow();
        }
        try { worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static boolean sameRules(YouTubeRepository.Rules a, YouTubeRepository.Rules b) {
        return a.musicOnly == b.musicOnly && a.include.equals(b.include) && a.exclude.equals(b.exclude);
    }
    private File file(String id) { return new File(directory, id + ".audio"); }
    private boolean validFile(Job job) {
        File f = file(job.track.id);
        return job.total > 0 && f.isFile() && f.length() == job.total;
    }
    private boolean current(Job job) { return !job.cancelled && jobs.get(job.track.id) == job; }
    private synchronized long readyBytes() {
        long total = 0;
        for (Job j : jobs.values()) if (j.status == Status.READY) total += j.total;
        return total;
    }

    private void run(Job job) {
        File partial = null;
        try {
            synchronized (this) {
                if (!current(job)) return;
                clearExpired(); job.status = Status.DOWNLOADING; persistQuietly();
            }
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Storage is unavailable.");
            partial = File.createTempFile(job.track.id + "-", ".part", directory);
            awaitPlayback(job);
            Response response;
            if (testSource != null) {
                response = testSource.open(job.track.id, job.rules);
            } else {
                YouTubeRepository repo = YouTubeRepository.get();
                YouTubeRepository.Resolved resolved = repo.resolve(job.track.id, job.rules, false);
                response = request(job, repo, resolved.url);
                if (response.code() == 403) {
                    response.close();
                    YouTubeRepository.Resolved fresh = repo.resolve(job.track.id, job.rules, true);
                    if (resolved.formatKey.isEmpty() || !resolved.formatKey.equals(fresh.formatKey))
                        throw new IOException("The audio format changed; please retry the download.");
                    response = request(job, repo, fresh.url);
                }
            }
            try (Response received = response) {
                ResponseBody body = received.body();
                if (received.code() != 200 || body == null) throw new IOException("Audio download failed.");
                long length = body.contentLength();
                if (length <= 0) throw new IOException("Audio size is unavailable; download cannot be verified.");
                if (length > ITEM_LIMIT_BYTES) throw new IOException("This item exceeds the 256 MB limit.");
                synchronized (this) {
                    if (!current(job)) return;
                    if (readyBytes() + length > TOTAL_LIMIT_BYTES)
                        throw new IOException("Temporary downloads are full. Remove an item and retry.");
                    job.total = length;
                }
                try (InputStream in = body.byteStream(); FileOutputStream out = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    long written = 0;
                    int n;
                    while (true) {
                        awaitPlayback(job);
                        n = in.read(buffer);
                        if (n == -1) break;
                        if (job.cancelled) throw new IOException("Cancelled.");
                        written += n;
                        if (written > length || written > ITEM_LIMIT_BYTES) throw new IOException("Unexpected audio size.");
                        out.write(buffer, 0, n);
                        synchronized (this) { if (!current(job)) return; job.bytes = written; }
                    }
                    if (written != length) throw new IOException("The download was incomplete.");
                    out.getFD().sync();
                }
                synchronized (this) {
                    if (!current(job)) return;
                    File complete = file(job.track.id);
                    if (!partial.renameTo(complete)) throw new IOException("Could not save the audio file.");
                    job.expiry = System.currentTimeMillis() + EXPIRY_MS;
                    job.status = Status.READY;
                    try { persist(); }
                    catch (Exception failure) {
                        complete.delete(); job.status = Status.FAILED; job.error = "Could not save download details.";
                        persistQuietly();
                    }
                }
            }
        } catch (Exception failure) {
            synchronized (this) {
                if (current(job)) {
                    job.status = Status.FAILED; job.bytes = 0; job.total = 0;
                    job.error = safeError(failure);
                    persistQuietly();
                }
            }
        } finally {
            job.call = null;
            if (partial != null) partial.delete();
        }
    }

    private static String safeError(Exception failure) {
        String message = failure.getMessage();
        if ("Storage is unavailable.".equals(message)
                || "The audio format changed; please retry the download.".equals(message)
                || "Audio download failed.".equals(message)
                || "Audio size is unavailable; download cannot be verified.".equals(message)
                || "This item exceeds the 256 MB limit.".equals(message)
                || "Temporary downloads are full. Remove an item and retry.".equals(message)
                || "Unexpected audio size.".equals(message)
                || "The download was incomplete.".equals(message)
                || "Could not save the audio file.".equals(message)) return message;
        return "Download failed. Check your connection and retry.";
    }

    private Response request(Job job, YouTubeRepository repo, String url) throws IOException {
        // Keep the repository's host checks and timeouts, while making each range call cancellable.
        OkHttpClient client = repo.mediaClient().newBuilder().addInterceptor(chain -> {
            synchronized (OfflineAudioStore.this) {
                if (!current(job)) throw new IOException("Cancelled.");
                job.call = chain.call();
            }
            return chain.proceed(chain.request());
        }).build();
        DataSource source = new QueryRangeAudioDataSource(new OkHttpDataSource.Factory(client));
        CountingOpenSource counted = new CountingOpenSource(source);
        DataSourceInputStream input = new DataSourceInputStream(counted,
                new DataSpec.Builder().setUri(Uri.parse(url))
                        .setHttpRequestHeaders(Collections.singletonMap("Accept-Encoding", "identity"))
                        .build());
        Request request = new Request.Builder().url(url).build();
        try {
            input.open();
        } catch (HttpDataSource.InvalidResponseCodeException denied) {
            try { input.close(); } catch (IOException ignored) { }
            if (denied.responseCode != 403) throw denied;
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(403).message("Forbidden").body(ResponseBody.create(null, new byte[0])).build();
        } catch (IOException | RuntimeException failure) {
            try { input.close(); } catch (IOException ignored) { }
            throw failure;
        }
        final long length = counted.length;
        final BufferedSource buffered = Okio.buffer(Okio.source(input));
        ResponseBody body = new ResponseBody() {
            @Override public MediaType contentType() { return null; }
            @Override public long contentLength() { return length; }
            @Override public BufferedSource source() { return buffered; }
        };
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build();
    }

    /** Records open's total remaining length without opening the source twice. */
    private static final class CountingOpenSource implements DataSource {
        private final DataSource delegate;
        long length = -1;
        CountingOpenSource(DataSource delegate) { this.delegate = delegate; }
        @Override public void addTransferListener(TransferListener listener) { delegate.addTransferListener(listener); }
        @Override public long open(DataSpec spec) throws IOException { return length = delegate.open(spec); }
        @Override public int read(byte[] target, int offset, int size) throws IOException {
            return delegate.read(target, offset, size);
        }
        @Override public Uri getUri() { return delegate.getUri(); }
        @Override public java.util.Map<String, java.util.List<String>> getResponseHeaders() {
            return delegate.getResponseHeaders();
        }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private void load() {
        // Interrupted downloads are never resumed or marked ready; stale temporary files are discarded.
        File[] partials = directory.listFiles((dir, name) -> name.endsWith(".part"));
        if (partials != null) for (File f : partials) f.delete();
        try (FileInputStream in = metadata.openRead()) {
            byte[] bytes = new byte[(int) Math.min(metadata.getBaseFile().length(), 2 * 1024 * 1024)];
            int offset = 0, n;
            while (offset < bytes.length && (n = in.read(bytes, offset, bytes.length - offset)) != -1) offset += n;
            JSONArray array = new JSONArray(new String(bytes, 0, offset, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length() && i < MAX_ENTRIES; i++) {
                JSONObject o = array.getJSONObject(i);
                String id = YouTubeRepository.normalizeVideoId(o.getString("id"));
                YouTubeRepository.Track track = new YouTubeRepository.Track(id, o.optString("title"),
                        o.optString("channel"), o.optLong("duration"), o.optString("artwork"), o.optString("creator"));
                YouTubeRepository.Rules rules = new YouTubeRepository.Rules(o.optBoolean("music"),
                        o.optString("include"), o.optString("exclude"));
                Job job = new Job(track, rules, Status.valueOf(o.getString("status")));
                job.total = o.optLong("total"); job.bytes = job.total; job.expiry = o.optLong("expiry");
                job.error = o.optString("error");
                if (job.status == Status.QUEUED || job.status == Status.DOWNLOADING) {
                    job.status = Status.FAILED; job.bytes = job.total = 0;
                    job.error = "Download interrupted. Please retry.";
                }
                jobs.put(id, job);
            }
        } catch (Exception ignored) { /* Missing or damaged index means no trusted downloads. */ }
        clearExpired();
        // Remove orphan complete files, including files left after a crash before index commit.
        File[] complete = directory.listFiles((dir, name) -> name.endsWith(".audio"));
        if (complete != null) for (File f : complete) {
            String id = f.getName().substring(0, f.getName().length() - 6);
            Job job = jobs.get(id);
            if (job == null || job.status != Status.READY || !validFile(job)) f.delete();
        }
        persistQuietly();
    }

    private void persistQuietly() { try { persist(); } catch (Exception ignored) { } }
    private void persist() throws Exception {
        JSONArray array = new JSONArray();
        for (Job job : jobs.values()) {
            JSONObject o = new JSONObject();
            o.put("id", job.track.id); o.put("title", job.track.title); o.put("channel", job.track.channel);
            o.put("duration", job.track.durationSeconds); o.put("artwork", job.track.artworkUrl);
            o.put("creator", job.track.creatorUrl); o.put("music", job.rules.musicOnly);
            o.put("include", job.rules.include); o.put("exclude", job.rules.exclude);
            o.put("status", job.status.name()); o.put("total", job.total); o.put("expiry", job.expiry);
            o.put("error", job.error); array.put(o);
        }
        FileOutputStream out = metadata.startWrite();
        try {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            metadata.finishWrite(out);
        } catch (Exception failure) {
            metadata.failWrite(out); throw failure;
        }
    }
}
