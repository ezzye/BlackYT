// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Process;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded, on-device metadata preparation. Never downloads speculative audio. */
final class PlaybackPreparation {
    static final int OFF = 0, UNMETERED = 1, ANY_NETWORK = 2;
    private static final int PER_SOURCE = 3, TOTAL = 5;
    private static final long RETRY_MS = 30_000;
    private final java.util.ArrayDeque<Long> starts = new java.util.ArrayDeque<>();
    private static volatile PlaybackPreparation instance;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run(); }, "BlackYT-prepare");
        t.setDaemon(true); return t;
    });
    private final Map<String, List<Candidate>> sources = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> attempted = new LinkedHashMap<String, Long>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 64; }
    };
    private List<Candidate> pending = Collections.emptyList();
    private boolean running;
    private volatile boolean playbackBusy;
    synchronized void setPlaybackBusy(boolean busy) { playbackBusy = busy; rebuild(); }

    static final class Candidate {
        final String id;
        final YouTubeRepository.Rules rules;
        Candidate(String id, YouTubeRepository.Rules rules) {
            this.id = YouTubeRepository.normalizeVideoId(id);
            this.rules = rules == null ? new YouTubeRepository.Rules(false, "", "") : rules;
        }
    }

    private PlaybackPreparation(Context context) { this.context = context.getApplicationContext(); }
    static PlaybackPreparation get(Context context) {
        if (instance == null) synchronized (PlaybackPreparation.class) {
            if (instance == null) instance = new PlaybackPreparation(context);
        }
        return instance;
    }
    int mode() { return context.getSharedPreferences("blackyt_preparation", Context.MODE_PRIVATE).getInt("mode", UNMETERED); }
    synchronized void setMode(int mode) {
        if (mode < OFF || mode > ANY_NETWORK) return;
        context.getSharedPreferences("blackyt_preparation", Context.MODE_PRIVATE).edit().putInt("mode", mode).apply();
        rebuild();
    }
    synchronized void update(String source, List<Candidate> candidates) {
        if (!"queue".equals(source) && !"screen".equals(source)) return;
        sources.put(source, new ArrayList<>(candidates.subList(0, Math.min(PER_SOURCE, candidates.size()))));
        rebuild();
    }
    private void rebuild() {
        LinkedHashMap<String, Candidate> ordered = new LinkedHashMap<>();
        // A user's explicit queue always wins over speculative visible results.
        for (String source : new String[] {"queue", "screen"}) {
            for (Candidate candidate : sources.getOrDefault(source, Collections.emptyList())) {
                ordered.putIfAbsent(candidate.id, candidate);
                if (ordered.size() == TOTAL) break;
            }
            if (ordered.size() == TOTAL) break;
        }
        pending = new ArrayList<>(ordered.values());
        if (!running && allowed() && !pending.isEmpty()) {
            running = true;
            worker.execute(this::drain);
        }
    }
    private void drain() {
        while (true) {
            Candidate next = null;
            synchronized (this) {
                if (allowed()) {
                    long now = SystemClock.elapsedRealtime();
                    while (!starts.isEmpty() && now - starts.peekFirst() >= 60_000) starts.removeFirst();
                    if (starts.size() >= 8) { running = false; return; }
                    for (Candidate candidate : pending) {
                        Long last = attempted.get(candidate.id);
                        if (last == null || now - last >= RETRY_MS) { next = candidate; attempted.put(candidate.id, now); starts.addLast(now); break; }
                    }
                }
                if (next == null) { running = false; return; }
            }
            try { YouTubeRepository.get().resolve(next.id, next.rules, false); }
            catch (Exception ignored) { /* A speculative failure must never interrupt playback or show an error. */ }
        }
    }
    private boolean allowed() {
        int mode = mode();
        if (mode == OFF || playbackBusy) return false;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && (mode == ANY_NETWORK || !cm.isActiveNetworkMetered());
    }
}
