// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, network-free parser for public YouTube video and channel links. */
public final class YtLink {
    private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)https?://[^\\s<>\\\"']+");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final long MAX_START_MS = 864_000_000L;
    private YtLink() { }

    public static final class Result {
        public final String videoId;
        public final String channelUrl;
        public final long startMs;
        public final String canonicalUrl;
        private Result(String videoId, String channelUrl, long startMs, String canonicalUrl) {
            this.videoId = videoId; this.channelUrl = channelUrl;
            this.startMs = startMs; this.canonicalUrl = canonicalUrl;
        }
        public boolean isVideo() { return videoId != null && !videoId.isEmpty(); }
    }

    public static Result parse(String input) {
        if (input == null || input.length() > 16_384) throw invalid();
        String value = input.trim();
        if (VIDEO_ID.matcher(value).matches()) return video(value, 0L);
        Matcher matcher = URL_IN_TEXT.matcher(value);
        if (matcher.find()) {
            String candidate = matcher.group();
            while (candidate.endsWith(".") || candidate.endsWith(",") || candidate.endsWith(")")
                    || candidate.endsWith("]") || candidate.endsWith("!")) candidate = candidate.substring(0, candidate.length()-1);
            return parseUrl(candidate);
        }
        if (!value.contains("://")) throw invalid();
        return parseUrl(value);
    }

    private static Result parseUrl(String raw) {
        try {
            validateEscapes(raw);
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) throw invalid();
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getRawAuthority() == null
                    || uri.getRawAuthority().contains("%")) throw invalid();
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port != -1 && !((scheme.equalsIgnoreCase("https") && port == 443)
                    || (scheme.equalsIgnoreCase("http") && port == 80))) throw invalid();
            boolean shortHost = host.equals("youtu.be") || host.equals("www.youtu.be");
            boolean youtubeHost = host.equals("youtube.com") || host.equals("www.youtube.com")
                    || host.equals("m.youtube.com") || host.equals("music.youtube.com");
            if (!shortHost && !youtubeHost) throw invalid();
            Map<String,String> query = parseQuery(uri.getRawQuery());
            String path = uri.getRawPath();
            if (path == null) path = "/";
            if (path.indexOf('%') >= 0 || path.contains("//")) throw invalid();
            String id = null;
            if (shortHost) {
                if (path.matches("/[A-Za-z0-9_-]{11}/?")) id = path.substring(1,12);
            } else if ("/watch".equals(path)) {
                id = query.get("v");
            } else {
                Matcher m = Pattern.compile("^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})/?$").matcher(path);
                if (m.matches()) id = m.group(1);
            }
            if (id != null) {
                if (!VIDEO_ID.matcher(id).matches()) throw invalid();
                long start = parseStart(query, uri.getRawFragment());
                return video(id, start);
            }
            if (!youtubeHost) throw invalid();
            String channelPath = canonicalChannelPath(path);
            if (channelPath == null) throw invalid();
            if (!query.isEmpty() && !(query.size() == 1 && query.containsKey("si"))) throw invalid();
            if (uri.getRawFragment() != null) throw invalid();
            return new Result("", "https://www.youtube.com" + channelPath, 0L,
                    "https://www.youtube.com" + channelPath);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }

    private static String canonicalChannelPath(String path) {
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.matches("/channel/UC[A-Za-z0-9_-]{22}")) return path;
        if (path.matches("/@[A-Za-z0-9._-]{3,30}")) return path;
        if (path.matches("/c/[A-Za-z0-9._-]{1,100}")
                || path.matches("/user/[A-Za-z0-9._-]{1,100}")) return path;
        return null;
    }

    private static Map<String,String> parseQuery(String raw) {
        Map<String,String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&", -1)) {
            if (pair.isEmpty()) continue;
            int at = pair.indexOf('=');
            String key = decode(at < 0 ? pair : pair.substring(0,at));
            String value = decode(at < 0 ? "" : pair.substring(at+1));
            if (!result.containsKey(key)) result.put(key,value);
            else if ("v".equals(key) || "t".equals(key) || "timestamp".equals(key) || "start".equals(key)) throw invalid();
        }
        return result;
    }

    private static long parseStart(Map<String,String> query, String fragment) {
        String value = first(query, "t", "timestamp", "start");
        if ((value == null || value.isEmpty()) && fragment != null) {
            String f = fragment.startsWith("#") ? fragment.substring(1) : fragment;
            if (f.startsWith("t=")) {
                try { value = decode(f.substring(2)); } catch (RuntimeException ignored) { return 0L; }
            }
        }
        if (value == null || value.isEmpty()) return 0L;
        try {
            long seconds = parseTimeSeconds(value);
            if (seconds <= 0 || seconds > MAX_START_MS / 1000L) return 0L;
            return seconds * 1000L;
        } catch (Exception ignored) { return 0L; }
    }

    private static String first(Map<String,String> values, String... keys) {
        for (String key : keys) { String value = values.get(key); if (value != null && !value.isEmpty()) return value; }
        return null;
    }

    private static long parseTimeSeconds(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.matches("[0-9]+")) return Long.parseLong(value);
        if (value.matches("[0-9]{1,6}:[0-5]?[0-9]:[0-5]?[0-9]")) {
            String[] p = value.split(":");
            return Math.addExact(Math.addExact(Math.multiplyExact(Long.parseLong(p[0]), 3600L),
                    Math.multiplyExact(Long.parseLong(p[1]), 60L)), Long.parseLong(p[2]));
        }
        Matcher matcher = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matcher(value);
        if (!matcher.matches() || (matcher.group(1) == null && matcher.group(2) == null && matcher.group(3) == null)) throw invalid();
        long h = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
        long m = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
        long s = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
        return Math.addExact(Math.addExact(Math.multiplyExact(h,3600L),Math.multiplyExact(m,60L)),s);
    }

    private static Result video(String id, long start) {
        String canonical = "https://www.youtube.com/watch?v=" + id;
        if (start > 0) canonical += "&t=" + (start / 1000L) + "s";
        return new Result(id, "", start, canonical);
    }
    private static void validateEscapes(String value) {
        for (int i=0;i<value.length();i++) if (value.charAt(i)=='%') {
            if (i+2>=value.length() || Character.digit(value.charAt(i+1),16)<0 || Character.digit(value.charAt(i+2),16)<0) throw invalid();
            i += 2;
        }
    }
    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid YouTube link."); }
}
