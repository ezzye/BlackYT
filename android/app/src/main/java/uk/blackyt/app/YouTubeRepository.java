// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.os.SystemClock;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** On-device YouTube search and direct progressive-audio resolver. */
public final class YouTubeRepository {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";

    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(45);
    private static final int CACHE_LIMIT = 64;
    private static final String GOOGLEVIDEO_SUFFIX = ".googlevideo.com";
    private static final Localization LOCALIZATION = new Localization("en", "GB");
    private static final ContentCountry COUNTRY = new ContentCountry("GB");
    private static volatile YouTubeRepository instance;

    private final OkHttpClient extractorClient;
    private final OkHttpClient mediaClient;
    private final Map<String, CachedResolved> resolvedCache =
            Collections.synchronizedMap(new LinkedHashMap<String, CachedResolved>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedResolved> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    private YouTubeRepository() {
        extractorClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .build();
        mediaClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(false)
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT).build()))
                .addNetworkInterceptor(chain -> {
                    okhttp3.Request request = chain.request();
                    requireGoogleVideoUrl(request.url().uri().toString());
                    okhttp3.Response response = chain.proceed(request);
                    Diagnostics.event("audio",request.url().host()+" status="+response.code()+" type="+response.header("Content-Type","unknown"));
                    android.util.Log.i("BlackYTNetwork", "audio host=" + request.url().host() + " status=" + response.code() + " type=" + response.header("Content-Type", "unknown"));
                    return response;
                })
                .build();
        NewPipe.init(new OkHttpDownloader(extractorClient), LOCALIZATION, COUNTRY);
    }

    public static YouTubeRepository get() {
        YouTubeRepository current = instance;
        if (current == null) {
            synchronized (YouTubeRepository.class) {
                current = instance;
                if (current == null) {
                    current = new YouTubeRepository();
                    instance = current;
                }
            }
        }
        return current;
    }

    public static final class Rules {
        public final boolean musicOnly;
        public final String include;
        public final String exclude;

        public Rules(boolean musicOnly, String include, String exclude) {
            this.musicOnly = musicOnly;
            this.include = include == null ? "" : include;
            this.exclude = exclude == null ? "" : exclude;
        }
    }

    public static final class Track {
        public final String id;
        public final String title;
        public final String channel;
        public final long durationSeconds;

        public Track(String id, String title, String channel, long durationSeconds) {
            this.id = id;
            this.title = title;
            this.channel = channel;
            this.durationSeconds = durationSeconds;
        }
    }

    public static final class Resolved {
        public final String url;
        public final String mimeType;
        public final String title;
        public final String channel;
        public final long durationSeconds;
        public final String sourceHost;

        public Resolved(String url, String mimeType, String title, String channel,
                        long durationSeconds, String sourceHost) {
            this.url = url;
            this.mimeType = mimeType;
            this.title = title;
            this.channel = channel;
            this.durationSeconds = durationSeconds;
            this.sourceHost = sourceHost;
        }
    }

    private static final class CachedResolved {
        final Resolved value;
        final long savedAtMs;
        final boolean musicCategory;

        CachedResolved(Resolved value, long savedAtMs, boolean musicCategory) {
            this.value = value;
            this.savedAtMs = savedAtMs;
            this.musicCategory = musicCategory;
        }
    }

    public List<Track> search(String query, Rules rules) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            throw new IOException("Enter a search phrase.");
        }
        Rules safeRules = rules == null ? new Rules(false, "", "") : rules;
        try {
            String pastedVideoId;
            try {
                pastedVideoId = normalizeVideoId(query.trim());
            } catch (IllegalArgumentException notAVideoLink) {
                pastedVideoId = null;
            }
            if (pastedVideoId != null) {
                StreamInfo info = StreamInfo.getInfo(youtubeService(),
                        "https://www.youtube.com/watch?v=" + pastedVideoId);
                String title = nullToEmpty(info.getName());
                String channel = nullToEmpty(info.getUploaderName());
                boolean musicCategory = isMusicCategory(info.getCategory());
                if (matches(title, channel, safeRules)
                        && cachedResultAllowed(musicCategory, safeRules.musicOnly)) {
                    return Collections.singletonList(new Track(
                            pastedVideoId, title, channel, info.getDuration()));
                }
                return Collections.emptyList();
            }

            StreamingService youtube = youtubeService();
            List<String> filters = safeRules.musicOnly
                    ? Collections.singletonList(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)
                    : Collections.singletonList(YoutubeSearchQueryHandlerFactory.ALL);
            SearchExtractor extractor = youtube.getSearchExtractor(
                    youtube.getSearchQHFactory().fromQuery(query.trim(), filters, null));
            extractor.fetchPage();
            ListExtractor.InfoItemsPage<InfoItem> page = extractor.getInitialPage();
            List<Track> tracks = new ArrayList<>();
            for (InfoItem item : page.getItems()) {
                if (!(item instanceof StreamInfoItem)) {
                    continue;
                }
                StreamInfoItem stream = (StreamInfoItem) item;
                String id;
                try {
                    id = normalizeVideoId(stream.getUrl());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                String title = nullToEmpty(stream.getName());
                String channel = nullToEmpty(stream.getUploaderName());
                if (matches(title, channel, safeRules)) {
                    tracks.add(new Track(id, title, channel, stream.getDuration()));
                }
                if (tracks.size() >= 50) {
                    break;
                }
            }
            return tracks;
        } catch (Exception failure) {
            Diagnostics.failure("search-error",failure);
            if (BuildConfig.DEBUG) android.util.Log.w("BlackYTNetwork", "Search error=" + failure.getClass().getSimpleName());
            throw new IOException("YT search failed. Check your connection and retry.");
        }
    }

    public Resolved resolve(String inputId, Rules rules, boolean fresh) throws Exception {
        final String id = normalizeVideoId(inputId);
        final Rules safeRules = rules == null ? new Rules(false, "", "") : rules;
        if (!fresh) {
            CachedResolved cached = resolvedCache.get(id);
            if (cached != null) {
                if (SystemClock.elapsedRealtime() - cached.savedAtMs <= CACHE_TTL_MS
                        && matches(cached.value.title, cached.value.channel, safeRules)
                        && cachedResultAllowed(cached.musicCategory, safeRules.musicOnly)) {
                    return cached.value;
                }
                resolvedCache.remove(id);
            }
        }
        try {
            StreamInfo info = StreamInfo.getInfo(youtubeService(),
                    "https://www.youtube.com/watch?v=" + id);
            String title = nullToEmpty(info.getName());
            String channel = nullToEmpty(info.getUploaderName());
            if (!matches(title, channel, safeRules)) {
                throw new IOException("This item does not match the current filters.");
            }
            boolean musicCategory = isMusicCategory(info.getCategory());
            if (!cachedResultAllowed(musicCategory, safeRules.musicOnly)) {
                throw new IOException("This item is not categorized as Music.");
            }

            AudioStream selected = null;
            for (AudioStream candidate : info.getAudioStreams()) {
                if (!candidate.isUrl()
                        || candidate.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP
                        || candidate.getFormat() == null) {
                    continue;
                }
                String mime = candidate.getFormat().getMimeType();
                if ("audio/mp4".equalsIgnoreCase(mime)) {
                    selected = candidate;
                    break;
                }
                if ("audio/webm".equalsIgnoreCase(mime) && selected == null) {
                    selected = candidate;
                }
            }
            if (selected == null) {
                throw new IOException("No direct progressive MP4 or WebM audio stream is available.");
            }

            String url = selected.getUrl();
            String host = requireGoogleVideoUrl(url);
            Resolved result = new Resolved(url, selected.getFormat().getMimeType(),
                    title, channel, info.getDuration(), host);
            resolvedCache.put(id, new CachedResolved(
                    result, SystemClock.elapsedRealtime(), musicCategory));
            return result;
        } catch (IOException e) {
            invalidate(id);
            throw e;
        } catch (Exception failure) {
            Diagnostics.failure("resolve-error",failure);
            if (BuildConfig.DEBUG) android.util.Log.w("BlackYTNetwork", "Resolve error=" + failure.getClass().getSimpleName());
            invalidate(id);
            throw new IOException("YT stream resolution failed. Check your connection and retry.");
        }
    }

    public void invalidate(String inputId) {
        try {
            resolvedCache.remove(normalizeVideoId(inputId));
        } catch (IllegalArgumentException ignored) {
            // Invalid input has no cache entry.
        }
    }

    public OkHttpClient mediaClient() {
        return mediaClient;
    }

    public static boolean matches(String title, String channel, Rules rules) {
        Rules current = rules == null ? new Rules(false, "", "") : rules;
        String haystack = (nullToEmpty(title) + " " + nullToEmpty(channel))
                .toLowerCase(Locale.ROOT);
        if (containsAny(haystack, current.exclude)) {
            return false;
        }
        return !hasTerms(current.include) || containsAny(haystack, current.include);
    }

    public static String normalizeVideoId(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Invalid YT item.");
        }
        String value = input.trim();
        if (value.matches("[A-Za-z0-9_-]{11}")) {
            return value;
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException("Invalid YT item.");
            }
            String host = uri.getHost();
            if (host == null) {
                throw new IllegalArgumentException("Invalid YT item.");
            }
            host = host.toLowerCase(Locale.ROOT);
            String id = null;
            if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
                String path = uri.getPath();
                if (path != null && path.matches("/[A-Za-z0-9_-]{11}/?")) {
                    id = path.substring(1, 12);
                }
            } else if (host.equals("youtube.com") || host.equals("www.youtube.com")
                    || host.equals("m.youtube.com") || host.equals("music.youtube.com")) {
                if ("/watch".equals(uri.getPath())) {
                    String query = uri.getRawQuery();
                    if (query != null) {
                        for (String pair : query.split("&")) {
                            String[] parts = pair.split("=", 2);
                            if (parts.length == 2 && "v".equals(parts[0])) {
                                id = parts[1];
                                break;
                            }
                        }
                    }
                }
            }
            if (id != null && id.matches("[A-Za-z0-9_-]{11}")) {
                return id;
            }
        } catch (URISyntaxException ignored) {
            // Use the same safe public error for malformed URLs and unsupported hosts.
        }
        throw new IllegalArgumentException("Invalid YT item.");
    }

    private static StreamingService youtubeService() throws Exception {
        return NewPipe.getService("YouTube");
    }

    static boolean cachedResultAllowed(boolean musicCategory, boolean musicOnly) {
        return !musicOnly || musicCategory;
    }

    private static boolean isMusicCategory(String category) {
        return "music".equalsIgnoreCase(trimToEmpty(category));
    }

    private static String requireGoogleVideoUrl(String value) throws IOException {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IOException("YT returned an unsupported media host.");
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (!(lowerHost.equals("googlevideo.com") || lowerHost.endsWith(GOOGLEVIDEO_SUFFIX))) {
                throw new IOException("YT returned an unsupported media host.");
            }
            return lowerHost;
        } catch (URISyntaxException e) {
            throw new IOException("YT returned an unsupported media host.");
        }
    }

    private static boolean containsAny(String haystack, String input) {
        for (String term : terms(input)) {
            if (haystack.contains(term.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTerms(String input) {
        return !terms(input).isEmpty();
    }

    private static List<String> terms(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : input.split("[,;\\n]")) {
            String term = part.trim().toLowerCase(Locale.ROOT);
            if (!term.isEmpty()) {
                result.add(term);
            }
        }
        return result;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimToEmpty(String value) {
        return nullToEmpty(value).trim();
    }

    private static final class OkHttpDownloader extends Downloader {
        private final OkHttpClient client;

        OkHttpDownloader(OkHttpClient client) {
            this.client = client;
        }

        @Override
        public Response execute(Request request) throws IOException {
            try {
                okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.url());
                Headers.Builder headers = new Headers.Builder();
                for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
                    for (String value : entry.getValue()) {
                        headers.add(entry.getKey(), value);
                    }
                }
                if (!headers.build().names().contains("User-Agent")) {
                    headers.add("User-Agent", USER_AGENT);
                }
                builder.headers(headers.build());
                String method = request.httpMethod();
                byte[] data = request.dataToSend();
                RequestBody body = data == null ? null
                        : RequestBody.create((MediaType) null, data);
                if (("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))
                        && body == null) {
                    body = RequestBody.create(null, new byte[0]);
                }
                builder.method(method, body);
                try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
                    Diagnostics.event("extract",response.request().url().host()+" status="+response.code());
                    if (BuildConfig.DEBUG) android.util.Log.i("BlackYTNetwork", "extract host=" + response.request().url().host() + " status=" + response.code());
                    String responseBody = response.body() == null ? "" : response.body().string();
                    return new Response(response.code(), response.message(),
                            response.headers().toMultimap(), responseBody,
                            response.request().url().toString());
                }
            } catch (IOException ignored) {
                throw new IOException("Extractor network request failed.");
            }
        }
    }
}
