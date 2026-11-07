// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Private, URL-free bindings. A failed clock or persistence check never authorises cache use. */
final class AudioCacheDescriptors {
    static final long TTL_MS = 6L * 60 * 60 * 1000;
    static final class Entry {
        final String key, format;
        final long created;
        Entry(String key, String format, long created) { this.key = key; this.format = format; this.created = created; }
    }
    private final AtomicFile file;
    private final Map<String, Entry> entries = new HashMap<>();
    private long lastWall;
    private boolean healthy = true;

    AudioCacheDescriptors(File path) {
        file = new AtomicFile(path);
        if (!path.exists() && !new File(path.getPath() + ".bak").exists()) return;
        try (FileInputStream in = file.openRead()) {
            Properties p = new Properties(); p.load(in);
            lastWall = Long.parseLong(p.getProperty("lastWall"));
            for (String name : p.stringPropertyNames()) if (name.startsWith("entry.")) {
                String identity = name.substring(6);
                String[] fields = p.getProperty(name).split("\\|", 3);
                long created = Long.parseLong(fields[0]);
                String format = new String(java.util.Base64.getDecoder().decode(fields[2]), java.nio.charset.StandardCharsets.UTF_8);
                if (!identity.matches("[0-9a-f]{64}") || !fields[1].matches("blackyt-audio-v2-[0-9a-f]{64}")
                        || format.isEmpty() || created <= 0 || created > lastWall) throw new IOException("Invalid descriptor");
                entries.put(identity, new Entry(fields[1], format, created));
            }
        } catch (Exception invalid) { entries.clear(); healthy = false; }
    }

    synchronized Set<String> maintain(long now) {
        if (!healthy || now <= 0 || now < lastWall) entries.clear();
        entries.values().removeIf(e -> now < e.created || now - e.created >= TTL_MS);
        lastWall = now;
        healthy = save();
        return keys();
    }
    synchronized Entry get(String identity, long now) {
        maintain(now);
        return healthy ? entries.get(identity) : null;
    }
    // Caller has already run maintain once for the whole snapshot.
    synchronized Entry getMaintained(String identity) {
        return healthy ? entries.get(identity) : null;
    }
    synchronized boolean put(String identity, String key, String format, long now) {
        maintain(now);
        if (!healthy || now <= 0) return false;
        Entry previous = entries.get(identity);
        if (previous == null || !previous.key.equals(key) || !previous.format.equals(format))
            entries.put(identity, new Entry(key, format, now));
        healthy = save();
        return healthy;
    }
    synchronized boolean isCurrent(String identity, String key, long now) {
        Entry entry = entries.get(identity);
        return healthy && entry != null && entry.key.equals(key) && now >= lastWall
                && now >= entry.created && now - entry.created < TTL_MS;
    }
    synchronized void removeKey(String key) {
        entries.values().removeIf(e -> e.key.equals(key));
        healthy = save();
    }
    synchronized void clear() { entries.clear(); healthy = save(); }
    private Set<String> keys() {
        Set<String> result = new HashSet<>();
        if (healthy) for (Entry entry : entries.values()) result.add(entry.key);
        return result;
    }
    private boolean save() {
        FileOutputStream out = null;
        try {
            Properties p = new Properties(); p.setProperty("lastWall", Long.toString(lastWall));
            for (Map.Entry<String, Entry> row : entries.entrySet()) {
                Entry e = row.getValue();
                p.setProperty("entry." + row.getKey(), e.created + "|" + e.key + "|"
                        + java.util.Base64.getEncoder().encodeToString(e.format.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            out = file.startWrite(); p.store(out, null); file.finishWrite(out); return true;
        } catch (IOException | RuntimeException failure) {
            if (out != null) file.failWrite(out);
            return false;
        }
    }
}
