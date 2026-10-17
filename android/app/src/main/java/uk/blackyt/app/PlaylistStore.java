// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small, private on-device playlist index. Playlists store public YT links and metadata only. */
public final class PlaylistStore {
    public static final int MAX_PLAYLISTS = 50;
    public static final int MAX_TRACKS_PER_PLAYLIST = 200;
    private static final String PREFS = "blackyt_playlists_v1";
    private static final String KEY = "playlists";
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;

    public static final class Entry {
        public final YouTubeRepository.Track track;
        public final YouTubeRepository.Rules rules;
        Entry(YouTubeRepository.Track track, YouTubeRepository.Rules rules) {
            this.track = track; this.rules = rules;
        }
    }
    public static final class Playlist {
        public final String id, name;
        public final long createdAtMs, updatedAtMs;
        public final List<Entry> entries;
        Playlist(String id, String name, long createdAtMs, long updatedAtMs, List<Entry> entries) {
            this.id=id; this.name=name; this.createdAtMs=createdAtMs; this.updatedAtMs=updatedAtMs;
            this.entries=Collections.unmodifiableList(entries);
        }
    }

    public PlaylistStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required.");
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Test seam: isolated preferences avoid touching a user's real playlists. */
    PlaylistStore(SharedPreferences isolatedPreferences) {
        if (isolatedPreferences == null) throw new IllegalArgumentException("Preferences are required.");
        preferences = isolatedPreferences;
    }

    public List<Playlist> list() {
        synchronized (LOCK) {
            JSONArray data = read(); List<Playlist> result = new ArrayList<>();
            for (int i=0; i<Math.min(data.length(),MAX_PLAYLISTS); i++) {
                JSONObject raw=data.optJSONObject(i); Playlist decoded=decode(raw);
                if (decoded != null) result.add(decoded);
            }
            return Collections.unmodifiableList(result);
        }
    }
    public Playlist get(String id) {
        if (id == null) return null;
        for (Playlist p : list()) if (p.id.equals(id)) return p;
        return null;
    }
    public Playlist create(String name) {
        String safe=cleanName(name);
        synchronized (LOCK) {
            JSONArray data=read();
            if (data.length() >= MAX_PLAYLISTS) throw new IllegalArgumentException("Playlist limit reached.");
            for (int i=0; i<data.length(); i++) {
                JSONObject existing=data.optJSONObject(i);
                if (existing != null && safe.equalsIgnoreCase(existing.optString("name")))
                    throw new IllegalArgumentException("A playlist with that name already exists.");
            }
            long now=System.currentTimeMillis();
            String id=java.util.UUID.randomUUID().toString();
            JSONObject item=new JSONObject();
            try { item.put("id",id).put("name",safe).put("createdAtMs",now).put("updatedAtMs",now).put("tracks",new JSONArray()); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
            data.put(item); write(data); return decode(item);
        }
    }
    public boolean add(String playlistId, YouTubeRepository.Track track, YouTubeRepository.Rules rules) {
        return addAll(playlistId,Collections.singletonList(new Entry(track,rules))) > 0;
    }
    /** Adds all new IDs atomically with a single preference write. Capacity errors add nothing. */
    public int addAll(String playlistId, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        synchronized (LOCK) {
            JSONArray data=read(); JSONObject p=find(data,playlistId);
            if (p == null) return 0;
            JSONArray tracks=p.optJSONArray("tracks"); if (tracks == null) tracks=new JSONArray();
            java.util.Set<String> known=new java.util.HashSet<>();
            for (int i=0; i<tracks.length(); i++) {
                JSONObject stored=tracks.optJSONObject(i);
                if (stored != null) known.add(stored.optString("id"));
            }
            List<JSONObject> additions=new ArrayList<>();
            for (Entry entry : entries) {
                if (entry == null || entry.track == null || entry.track.id == null
                        || !entry.track.id.matches("[A-Za-z0-9_-]{11}"))
                    throw new IllegalArgumentException("Choose valid YT results.");
                if (known.add(entry.track.id)) additions.add(encode(entry.track,entry.rules));
            }
            if (tracks.length() + additions.size() > MAX_TRACKS_PER_PLAYLIST)
                throw new IllegalArgumentException("This playlist cannot hold all selected items. Remove some items and try again.");
            if (additions.isEmpty()) return 0;
            for (JSONObject addition : additions) tracks.put(addition);
            try { p.put("tracks",tracks).put("updatedAtMs",System.currentTimeMillis()); }
            catch (Exception ignored) { return 0; }
            write(data); return additions.size();
        }
    }
    public boolean remove(String playlistId, String videoId) {
        synchronized (LOCK) {
            JSONArray data=read(); JSONObject p=find(data,playlistId); if (p == null) return false;
            JSONArray old=p.optJSONArray("tracks"), kept=new JSONArray(); boolean removed=false;
            if (old != null) for (int i=0; i<old.length(); i++) {
                JSONObject track=old.optJSONObject(i);
                if (track != null && videoId != null && videoId.equals(track.optString("id"))) removed=true;
                else if (track != null) kept.put(track);
            }
            if (removed) { try { p.put("tracks",kept).put("updatedAtMs",System.currentTimeMillis()); } catch(Exception ignored) { return false; } write(data); }
            return removed;
        }
    }
    public boolean delete(String playlistId) {
        synchronized (LOCK) {
            JSONArray old=read(), kept=new JSONArray(); boolean removed=false;
            for (int i=0; i<old.length(); i++) {
                JSONObject p=old.optJSONObject(i);
                if (p != null && playlistId != null && playlistId.equals(p.optString("id"))) removed=true;
                else if (p != null) kept.put(p);
            }
            if (removed) write(kept); return removed;
        }
    }

    private static JSONObject find(JSONArray data, String id) {
        if (id == null) return null;
        for (int i=0; i<data.length(); i++) { JSONObject p=data.optJSONObject(i); if (p != null && id.equals(p.optString("id"))) return p; }
        return null;
    }
    private static JSONObject encode(YouTubeRepository.Track t, YouTubeRepository.Rules r) {
        JSONObject o=new JSONObject();
        try { o.put("id",t.id).put("title",limit(t.title,240)).put("channel",limit(t.channel,160))
                .put("duration",Math.max(0,t.durationSeconds)).put("artwork",limit(t.artworkUrl,1024)).put("creator",limit(t.creatorUrl,1024))
                .put("musicOnly",r != null && r.musicOnly).put("include",limit(r == null ? "" : r.include,300)).put("exclude",limit(r == null ? "" : r.exclude,300)); }
        catch(Exception ignored) { }
        return o;
    }
    private static Playlist decode(JSONObject p) {
        if (p == null) return null;
        String id=p.optString("id"), name=p.optString("name");
        if (id.isEmpty() || name.isEmpty()) return null;
        List<Entry> entries=new ArrayList<>(); JSONArray values=p.optJSONArray("tracks");
        if (values != null) for (int i=0; i<Math.min(values.length(),MAX_TRACKS_PER_PLAYLIST); i++) {
            JSONObject o=values.optJSONObject(i); if (o == null) continue;
            String video=o.optString("id"); if (!video.matches("[A-Za-z0-9_-]{11}")) continue;
            YouTubeRepository.Track track=new YouTubeRepository.Track(video,o.optString("title","YT video"),o.optString("channel","YT"),
                    Math.max(0,o.optLong("duration",0)),o.optString("artwork",""),o.optString("creator",""));
            YouTubeRepository.Rules rules=new YouTubeRepository.Rules(o.optBoolean("musicOnly",false),o.optString("include",""),o.optString("exclude",""));
            entries.add(new Entry(track,rules));
        }
        return new Playlist(id,name,p.optLong("createdAtMs",0),p.optLong("updatedAtMs",0),entries);
    }
    private JSONArray read() {
        try { return new JSONArray(preferences.getString(KEY,"[]")); }
        catch(Exception ignored) { return new JSONArray(); }
    }
    private void write(JSONArray data) { preferences.edit().putString(KEY,data.toString()).apply(); }
    private static String cleanName(String name) {
        String value=limit(name == null ? "" : name.trim().replaceAll("\\s+"," "),48);
        if (value.isEmpty()) throw new IllegalArgumentException("Enter a playlist name.");
        return value;
    }
    private static String limit(String value,int max) { if (value == null) return ""; return value.length() > max ? value.substring(0,max) : value; }
}
