// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Private, on-device library for saved listening, creators, and editable Explore shortcuts. */
public final class ListeningStore {
    public static final int MAX_EPISODES = 500;
    public static final int MAX_CREATORS = 100;
    public static final int MAX_TILES = 60;
    private static final String PREFS_PREFIX = "blackyt_listening_store_";
    private static final String DATA_KEY = "data_v1";
    private static final Object STORE_LOCK = new Object();
    private final SharedPreferences preferences;

    public static final class SavedEpisode {
        public final YouTubeRepository.Track track;
        public final YouTubeRepository.Rules rules;
        public final long positionMs;
        public final long durationMs;
        public final long savedAtMs;
        public final long updatedAtMs;

        public SavedEpisode(YouTubeRepository.Track track, YouTubeRepository.Rules rules,
                            long positionMs, long durationMs, long savedAtMs, long updatedAtMs) {
            this.track = track;
            this.rules = rules;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.savedAtMs = savedAtMs;
            this.updatedAtMs = updatedAtMs;
        }
    }

    public static final class SavedCreator {
        public final String url;
        public final String name;
        public final String artworkUrl;

        public SavedCreator(String url, String name, String artworkUrl) {
            this.url = url;
            this.name = name;
            this.artworkUrl = artworkUrl;
        }
    }

    public static final class ExploreTile {
        public final String id;
        public final String title;
        public final String query;
        public final boolean musicOnly;
        public final int color;

        public ExploreTile(String id, String title, String query, boolean musicOnly, int color) {
            this.id = id;
            this.title = title;
            this.query = query;
            this.musicOnly = musicOnly;
            this.color = color;
        }
    }

    public ListeningStore(Context context) {
        this(context, "default");
    }

    /** Namespace overload intended for isolated tests and independent local store instances. */
    public ListeningStore(Context context, String namespace) {
        if (context == null) throw new IllegalArgumentException("Context is required.");
        String safeNamespace = bounded(namespace, 64, "default");
        if (!safeNamespace.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Invalid store namespace.");
        }
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_PREFIX + safeNamespace, Context.MODE_PRIVATE);
        synchronized (STORE_LOCK) {
            if (!preferences.contains(DATA_KEY)) write(defaultData());
        }
    }

    public List<SavedEpisode> listEpisodes() {
        synchronized (STORE_LOCK) {
            try {
                JSONArray values = read().getJSONArray("episodes");
                List<SavedEpisode> result = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) result.add(decodeEpisode(values.getJSONObject(i)));
                Collections.sort(result, (a, b) -> Long.compare(b.savedAtMs, a.savedAtMs));
                return Collections.unmodifiableList(result);
            } catch (Exception ignored) { return Collections.emptyList(); }
        }
    }

    public SavedEpisode getEpisode(String id) {
        String key = videoId(id);
        synchronized (STORE_LOCK) {
            try {
                JSONArray values = read().getJSONArray("episodes");
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.getJSONObject(i);
                    if (key.equals(value.optString("id"))) return decodeEpisode(value);
                }
            } catch (Exception ignored) { }
            return null;
        }
    }

    public boolean isSaved(String id) { return getEpisode(id) != null; }

    /** Adds or refreshes metadata while retaining an existing resume point and saved order. */
    public void saveEpisode(YouTubeRepository.Track track, YouTubeRepository.Rules rules,
                            long initialPositionMs) {
        if (track == null) throw new IllegalArgumentException("Track is required.");
        String id = videoId(track.id);
        YouTubeRepository.Track safeTrack = safeTrack(track, id);
        YouTubeRepository.Rules safeRules = safeRules(rules);
        synchronized (STORE_LOCK) {
            JSONObject data = read();
            JSONArray old = data.optJSONArray("episodes");
            JSONArray values = old == null ? new JSONArray() : old;
            int index = indexBy(values, id, "id");
            if (index < 0 && values.length() >= MAX_EPISODES) throw new IllegalArgumentException("Saved episode limit reached.");
            long now = System.currentTimeMillis();
            long position = Math.max(0L, initialPositionMs);
            long duration = durationMs(safeTrack.durationSeconds);
            long savedAt = now;
            if (index >= 0) {
                JSONObject previous = values.optJSONObject(index);
                position = Math.max(0L, previous.optLong("positionMs", position));
                duration = Math.max(0L, previous.optLong("durationMs", duration));
                savedAt = Math.max(0L, previous.optLong("savedAtMs", now));
            }
            try {
                JSONObject entry = encodeEpisode(safeTrack, safeRules, position, duration, savedAt, now);
                if (index < 0) values.put(entry); else values.put(index, entry);
                data.put("episodes", values);
                write(data);
            } catch (JSONException impossible) {
                throw new IllegalStateException("Could not save episode metadata.", impossible);
            }
        }
    }

    public void removeEpisode(String id) {
        String key = videoId(id);
        synchronized (STORE_LOCK) {
            JSONObject data = read(); JSONArray values = data.optJSONArray("episodes");
            if (values == null) return;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item != null && !key.equals(item.optString("id"))) kept.put(item);
            }
            try { data.put("episodes", kept); write(data); } catch (JSONException ignored) { }
        }
    }

    public void updateProgressIfSaved(String id, long positionMs, long durationMs) {
        updateProgressIfSaved(id, positionMs, durationMs, -1L);
    }

    /** A queued service snapshot may only update the save generation it observed. */
    public void updateProgressIfSaved(String id, long positionMs, long durationMs, long expectedSavedAtMs) {
        String key = videoId(id);
        synchronized (STORE_LOCK) {
            JSONObject data = read(); JSONArray values = data.optJSONArray("episodes");
            if (values == null) return;
            int index = indexBy(values, key, "id");
            if (index < 0) return;
            JSONObject entry = values.optJSONObject(index);
            if (expectedSavedAtMs >= 0 && entry.optLong("savedAtMs", -1) != expectedSavedAtMs) return;
            long duration = durationMs > 0 ? durationMs : Math.max(0L, entry.optLong("durationMs", 0L));
            long position = Math.max(0L, positionMs);
            if (duration > 0) position = Math.min(position, duration);
            try {
                entry.put("positionMs", position).put("durationMs", Math.max(0L, duration))
                        .put("updatedAtMs", System.currentTimeMillis());
                values.put(index, entry); data.put("episodes", values); write(data);
            } catch (JSONException ignored) { }
        }
    }

    public long resumePositionMs(String id) {
        SavedEpisode episode = getEpisode(id);
        if (episode == null || episode.positionMs <= 0) return 0L;
        if (episode.durationMs > 0 && (episode.positionMs >= episode.durationMs
                || episode.durationMs - episode.positionMs <= 30_000L)) return 0L;
        return episode.positionMs;
    }

    public List<SavedCreator> listCreators() {
        synchronized (STORE_LOCK) {
            try {
                JSONArray values = read().getJSONArray("creators"); List<SavedCreator> result = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) result.add(decodeCreator(values.getJSONObject(i)));
                return Collections.unmodifiableList(result);
            } catch (Exception ignored) { return Collections.emptyList(); }
        }
    }

    public boolean isCreatorSaved(String url) {
        String key = safeCreatorUrl(url);
        synchronized (STORE_LOCK) {
            JSONArray values = read().optJSONArray("creators");
            return values != null && indexBy(values, key, "url") >= 0;
        }
    }

    public void saveCreator(String url, String name, String artworkUrl) {
        String safeUrl = safeCreatorUrl(url);
        String safeName = bounded(name, 160, "Creator").trim();
        if (safeName.isEmpty()) safeName = "Creator";
        String safeArt = YouTubeRepository.safeArtworkUrl(artworkUrl);
        synchronized (STORE_LOCK) {
            JSONObject data = read(); JSONArray values = data.optJSONArray("creators");
            if (values == null) values = new JSONArray();
            int index = indexBy(values, safeUrl, "url");
            if (index < 0 && values.length() >= MAX_CREATORS) throw new IllegalArgumentException("Saved creator limit reached.");
            try {
                JSONObject entry = new JSONObject().put("url", safeUrl).put("name", safeName).put("artworkUrl", safeArt);
                if (index < 0) values.put(entry); else values.put(index, entry);
                data.put("creators", values); write(data);
            } catch (JSONException ignored) { }
        }
    }

    public void removeCreator(String url) {
        String key = safeCreatorUrl(url);
        synchronized (STORE_LOCK) {
            JSONObject data = read(); JSONArray values = data.optJSONArray("creators");
            if (values == null) return;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i); if (item != null && !key.equals(item.optString("url"))) kept.put(item);
            }
            try { data.put("creators", kept); write(data); } catch (JSONException ignored) { }
        }
    }

    public List<ExploreTile> listTiles() {
        synchronized (STORE_LOCK) {
            try {
                JSONArray values = read().getJSONArray("tiles"); List<ExploreTile> result = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) result.add(decodeTile(values.getJSONObject(i)));
                return Collections.unmodifiableList(result);
            } catch (Exception ignored) { return Collections.emptyList(); }
        }
    }

    public void putTile(ExploreTile tile) {
        if (tile == null) throw new IllegalArgumentException("Explore tile is required.");
        ExploreTile safe = safeTile(tile);
        synchronized (STORE_LOCK) {
            JSONObject data = read(); JSONArray values = data.optJSONArray("tiles");
            if (values == null) values = new JSONArray();
            int index = indexBy(values, safe.id, "id");
            if (index < 0 && values.length() >= MAX_TILES) throw new IllegalArgumentException("Explore tile limit reached.");
            try {
                JSONObject entry = encodeTile(safe);
                if (index < 0) values.put(entry); else values.put(index, entry);
                data.put("tiles", values); write(data);
            } catch (JSONException ignored) { }
        }
    }

    public void moveTile(String id, int delta) {
        String key = tileId(id); if (delta == 0) return;
        synchronized (STORE_LOCK) {
            JSONObject data = read(); JSONArray values = data.optJSONArray("tiles"); if (values == null) return;
            int from = indexBy(values, key, "id"); int to = Math.max(0, Math.min(values.length() - 1, from + (delta < 0 ? -1 : 1)));
            if (from < 0 || to == from) return;
            JSONObject moved = values.optJSONObject(from); JSONArray reordered = new JSONArray();
            for (int i = 0; i < values.length(); i++) if (i != from) reordered.put(values.opt(i));
            JSONArray finalValues = new JSONArray();
            for (int i = 0; i <= reordered.length(); i++) {
                if (i == to) finalValues.put(moved);
                if (i < reordered.length()) finalValues.put(reordered.opt(i));
            }
            try { data.put("tiles", finalValues); write(data); } catch (JSONException ignored) { }
        }
    }

    public void removeTile(String id) {
        String key = tileId(id);
        synchronized (STORE_LOCK) {
            JSONObject data = read(); JSONArray values = data.optJSONArray("tiles"); if (values == null) return;
            JSONArray kept = new JSONArray();
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i); if (item != null && !key.equals(item.optString("id"))) kept.put(item);
            }
            try { data.put("tiles", kept); write(data); } catch (JSONException ignored) { }
        }
    }

    public void resetTiles() {
        synchronized (STORE_LOCK) { JSONObject data = read(); try { data.put("tiles", defaults()); write(data); } catch (JSONException ignored) { } }
    }

    private JSONObject read() {
        String raw = preferences.getString(DATA_KEY, null);
        if (raw != null) try { return new JSONObject(raw); } catch (JSONException ignored) { }
        return defaultData();
    }

    private void write(JSONObject data) { preferences.edit().putString(DATA_KEY, data.toString()).apply(); }
    private static JSONObject defaultData() { try { return new JSONObject().put("episodes", new JSONArray()).put("creators", new JSONArray()).put("tiles", defaults()); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
    private static JSONArray defaults() {
        JSONArray values = new JSONArray();
        String[][] entries = {
                {"ai-tech", "AI & Tech", "AI technology", "false", "-13408513"},
                {"wes-roth", "Wes Roth", "Wes Roth", "false", "-13070789"},
                {"podcasts", "Podcasts", "podcasts", "false", "-12929308"},
                {"interviews", "Interviews", "music interviews", "false", "-6075996"},
                {"science", "Science", "science documentary", "false", "-12031926"},
                {"history", "History", "history documentary", "false", "-10040116"},
                {"music-learning", "Music learning", "music theory lesson", "false", "-8343142"},
                {"new-music", "New music", "new music", "true", "-10453621"},
                {"rock", "Rock", "rock music", "true", "-9146037"},
                {"soul", "Soul", "soul music", "true", "-12804007"},
                {"jazz", "Jazz", "jazz music", "true", "-10265400"},
                {"live-sessions", "Live sessions", "live music session", "true", "-11625297"},
                {"instrumentals", "Instrumentals", "instrumental music", "true", "-8143150"},
                {"classical", "Classical", "classical music", "true", "-11513776"},
                {"afrobeats", "Afrobeats", "Afrobeats", "true", "-13011419"},
                {"lofi", "Lo-fi", "lofi music", "true", "-8421505"}
        };
        for (String[] e : entries) try { values.put(new JSONObject().put("id", e[0]).put("title", e[1]).put("query", e[2]).put("musicOnly", Boolean.parseBoolean(e[3])).put("color", Integer.parseInt(e[4]))); } catch (JSONException ignored) { }
        return values;
    }

    private static JSONObject encodeEpisode(YouTubeRepository.Track track, YouTubeRepository.Rules rules,
                                             long position, long duration, long savedAt, long updatedAt) throws JSONException {
        return new JSONObject().put("id", track.id).put("title", bounded(track.title, 240, "Untitled"))
                .put("channel", bounded(track.channel, 160, "" )).put("durationSeconds", Math.max(0L, track.durationSeconds))
                .put("artworkUrl", YouTubeRepository.safeArtworkUrl(track.artworkUrl))
                .put("creatorUrl", canonicalCreatorMetadata(track.creatorUrl))
                .put("musicOnly", rules.musicOnly).put("include", bounded(rules.include, 240, ""))
                .put("exclude", bounded(rules.exclude, 240, "")).put("positionMs", position)
                .put("durationMs", duration).put("savedAtMs", savedAt).put("updatedAtMs", updatedAt);
    }
    private static SavedEpisode decodeEpisode(JSONObject o) {
        String id = videoId(o.optString("id"));
        YouTubeRepository.Track t = new YouTubeRepository.Track(id, o.optString("title", "Untitled"), o.optString("channel", ""), o.optLong("durationSeconds", 0), o.optString("artworkUrl", ""), o.optString("creatorUrl", ""));
        YouTubeRepository.Rules r = new YouTubeRepository.Rules(o.optBoolean("musicOnly"), o.optString("include", ""), o.optString("exclude", ""));
        return new SavedEpisode(t, r, Math.max(0, o.optLong("positionMs")), Math.max(0, o.optLong("durationMs")), Math.max(0, o.optLong("savedAtMs")), Math.max(0, o.optLong("updatedAtMs")));
    }
    private static SavedCreator decodeCreator(JSONObject o) { return new SavedCreator(safeCreatorUrl(o.optString("url")), bounded(o.optString("name"),160,"Creator"), YouTubeRepository.safeArtworkUrl(o.optString("artworkUrl"))); }
    private static JSONObject encodeTile(ExploreTile t) throws JSONException { return new JSONObject().put("id",t.id).put("title",t.title).put("query",t.query).put("musicOnly",t.musicOnly).put("color",t.color); }
    private static ExploreTile decodeTile(JSONObject o) { return safeTile(new ExploreTile(o.optString("id"),o.optString("title"),o.optString("query"),o.optBoolean("musicOnly"),o.optInt("color"))); }
    private static YouTubeRepository.Track safeTrack(YouTubeRepository.Track t,String id) { return new YouTubeRepository.Track(id,bounded(t.title,240,"Untitled"),bounded(t.channel,160,""),Math.max(0,t.durationSeconds),YouTubeRepository.safeArtworkUrl(t.artworkUrl),t.creatorUrl); }
    private static YouTubeRepository.Rules safeRules(YouTubeRepository.Rules r) { return r == null ? new YouTubeRepository.Rules(false,"","") : new YouTubeRepository.Rules(r.musicOnly,bounded(r.include,240,""),bounded(r.exclude,240,"")); }
    private static ExploreTile safeTile(ExploreTile t) { return new ExploreTile(tileId(t.id),bounded(t.title,100,"Explore"),bounded(t.query,200,""),t.musicOnly,t.color); }
    private static String videoId(String value) { String id = YouTubeRepository.normalizeVideoId(value); if (!id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("Invalid video ID."); return id; }
    private static String tileId(String value) { String id = bounded(value,48,"").trim(); if (!id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,47}")) throw new IllegalArgumentException("Invalid Explore tile ID."); return id; }
    private static String canonicalCreatorMetadata(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        return CreatorRepository.canonicalChannelUrl(value);
    }
    private static String safeCreatorUrl(String value) {
        if (value == null || value.length() > 300) throw new IllegalArgumentException("Invalid creator URL.");
        YtLink.Result result = YtLink.parse(value);
        if (result.channelUrl == null || result.channelUrl.isEmpty()) throw new IllegalArgumentException("A YouTube channel URL is required.");
        return CreatorRepository.canonicalChannelUrl(result.channelUrl);
    }
    private static String bounded(String value,int max,String fallback) { String s=value == null ? fallback : value; return s.length() <= max ? s : s.substring(0,max); }
    private static int indexBy(JSONArray a,String value,String field) { for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&value.equals(o.optString(field)))return i;} return -1; }
    private static long durationMs(long seconds) { if(seconds <= 0) return 0; return seconds > Long.MAX_VALUE/1000 ? Long.MAX_VALUE : seconds*1000; }
}
