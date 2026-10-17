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

/** Bounded, on-device stream preparation. Writes only the explicitly limited audio prefix. */
@androidx.media3.common.util.UnstableApi
final class PlaybackPreparation {
    static final int OFF = 0, UNMETERED = 1, ANY_NETWORK = 2;
    private static final int QUEUE_LIMIT = 2, SCREEN_LIMIT = 1, TOTAL = 3;
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
    private String activeCandidateId;
    synchronized void setPlaybackBusy(boolean busy) {
        playbackBusy = busy;
        if (busy) AudioPrefixCache.cancel(context);
        rebuild();
    }

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
        if (mode == OFF) {
            AudioPrefixCache.cancel(context);
            AudioPrefixCache.clear(context);
        }
        rebuild();
    }
    synchronized void update(String source, List<Candidate> candidates) {
        if (!"queue".equals(source) && !"screen".equals(source)) return;
        int limit = "queue".equals(source) ? QUEUE_LIMIT : SCREEN_LIMIT;
        sources.put(source, new ArrayList<>(candidates.subList(0, Math.min(limit, candidates.size()))));
        rebuild();
    }
    private void rebuild() {
        pending = prioritize(sources.getOrDefault("queue", Collections.emptyList()),
                sources.getOrDefault("screen", Collections.emptyList()));
        if (activeCandidateId != null && !containsPending(activeCandidateId))
            AudioPrefixCache.cancel(context);
        if (!running && allowed() && !pending.isEmpty()) {
            running = true;
            worker.execute(this::drain);
        }
    }

    static List<Candidate> prioritize(List<Candidate> queue, List<Candidate> screen) {
        LinkedHashMap<String, Candidate> ordered = new LinkedHashMap<>();
        appendCandidates(ordered, queue, QUEUE_LIMIT);
        appendCandidates(ordered, screen, SCREEN_LIMIT);
        return new ArrayList<>(ordered.values());
    }

    private static void appendCandidates(LinkedHashMap<String, Candidate> ordered,
            List<Candidate> candidates, int limit) {
        int added = 0;
        for (Candidate candidate : candidates) {
            if (candidate == null || ordered.containsKey(candidate.id)) continue;
            ordered.put(candidate.id, candidate);
            if (++added >= limit || ordered.size() >= TOTAL) return;
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
                        if (last == null || now - last >= RETRY_MS) {
                            next = candidate; activeCandidateId = candidate.id;
                            attempted.put(candidate.id, now); starts.addLast(now); break;
                        }
                    }
                }
                if (next == null) { running = false; return; }
            }
            Candidate selected = next;
            try {
                YouTubeRepository.Resolved resolved = YouTubeRepository.get().resolve(selected.id, selected.rules, false);
                if (stillEligible(selected)) AudioPrefixCache.get(context).prefetch(selected.id, selected.rules,
                        resolved, () -> stillEligible(selected));
            }
            catch (Exception ignored) { /* A speculative failure must never interrupt playback or show an error. */ }
            finally {
                synchronized (this) { if (selected.id.equals(activeCandidateId)) activeCandidateId = null; }
            }
        }
    }
    private boolean containsPending(String id) {
        for (Candidate candidate : pending) if (candidate.id.equals(id)) return true;
        return false;
    }
    private synchronized boolean stillEligible(Candidate candidate) {
        if (!allowed()) return false;
        for (Candidate pendingCandidate : pending)
            if (pendingCandidate.id.equals(candidate.id)) return true;
        return false;
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
