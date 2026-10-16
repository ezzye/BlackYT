// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.ContentAvailability;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Public YouTube creator discovery and latest-videos browsing through NewPipe. */
public final class CreatorRepository {
    private static final int MAX_UPSTREAM_PAGES = 2;
    private static final int MAX_RESULTS = 50;
    private static final int MAX_CURSOR_IDS = 200;
    private static volatile CreatorRepository instance;

    private CreatorRepository() {
        // Initializes NewPipe and its shared extractor client without changing the media client.
        YouTubeRepository.get();
    }

    public static CreatorRepository get() {
        CreatorRepository current = instance;
        if (current == null) {
            synchronized (CreatorRepository.class) {
                current = instance;
                if (current == null) {
                    current = new CreatorRepository();
                    instance = current;
                }
            }
        }
        return current;
    }

    public static final class Creator {
        public final String url;
        public final String name;
        public final String artworkUrl;
        public final String description;
        public final long subscriberCount;

        public Creator(String url, String name, String artworkUrl, String description,
                       long subscriberCount) {
            this.url = canonicalChannelUrl(url);
            this.name = clean(name);
            this.artworkUrl = YouTubeRepository.safeArtworkUrl(artworkUrl);
            this.description = clean(description);
            this.subscriberCount = subscriberCount;
        }
    }

    /** Opaque continuation token bound to the trimmed search query that created it. */
    public static final class CreatorCursor {
        private final String query;
        private final Page page;
        private final List<String> seenUrls;

        CreatorCursor(String query, Page page, List<String> seenUrls) {
            this.query = query;
            this.page = page;
            this.seenUrls = immutableIds(seenUrls);
        }

        boolean isBoundTo(String value) {
            return query.equals(value);
        }
    }

    public static final class CreatorPage {
        public final List<Creator> creators;
        public final CreatorCursor next;

        private CreatorPage(List<Creator> creators, CreatorCursor next) {
            this.creators = Collections.unmodifiableList(new ArrayList<>(creators));
            this.next = next;
        }
    }

    /** Opaque continuation token bound to a canonical channel URL and Videos tab. */
    public static final class ChannelCursor {
        private final String canonicalUrl;
        private final ListLinkHandler videosTab;
        private final Page page;
        private final Creator creator;
        private final List<String> seenIds;

        ChannelCursor(String canonicalUrl, ListLinkHandler videosTab, Page page,
                      Creator creator, List<String> seenIds) {
            this.canonicalUrl = canonicalUrl;
            this.videosTab = videosTab;
            this.page = page;
            this.creator = creator;
            this.seenIds = immutableIds(seenIds);
        }

        boolean isBoundTo(String value) {
            try { return canonicalUrl.equals(canonicalChannelUrl(value)); }
            catch (IllegalArgumentException invalid) { return false; }
        }
    }

    public static final class ChannelPage {
        public final Creator creator;
        public final List<YouTubeRepository.Track> tracks;
        public final ChannelCursor next;

        private ChannelPage(Creator creator, List<YouTubeRepository.Track> tracks,
                            ChannelCursor next) {
            this.creator = creator;
            this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
            this.next = next;
        }
    }

    /**
     * Search only YouTube's channel filter. A stream result can never be promoted to a creator.
     */
    public CreatorPage searchCreators(String query, CreatorCursor cursor) throws IOException {
        if (query == null || query.trim().isEmpty()) {
            throw new IOException("Enter a creator name to search.");
        }
        final String cleanedQuery = query.trim();
        if (cursor != null && !cursor.isBoundTo(cleanedQuery)) {
            throw new IOException("Creator page does not match the current search.");
        }
        try {
            StreamingService service = youtubeService();
            SearchExtractor extractor = service.getSearchExtractor(service.getSearchQHFactory()
                    .fromQuery(cleanedQuery,
                            Collections.singletonList(YoutubeSearchQueryHandlerFactory.CHANNELS), null));
            ListExtractor.InfoItemsPage<InfoItem> page;
            if (cursor == null) {
                extractor.fetchPage();
                page = extractor.getInitialPage();
            } else {
                page = extractor.getPage(cursor.page);
            }
            Set<String> seen = new HashSet<>();
            if (cursor != null) seen.addAll(cursor.seenUrls);
            List<Creator> creators = new ArrayList<>();
            int upstreamPages = 0;
            do {
                upstreamPages++;
                for (InfoItem item : page.getItems()) {
                    if (!(item instanceof ChannelInfoItem)) continue;
                    ChannelInfoItem channel = (ChannelInfoItem) item;
                    String canonical;
                    try {
                        canonical = canonicalChannelUrl(channel.getUrl());
                    } catch (IllegalArgumentException unsupported) {
                        continue;
                    }
                    if (!seen.add(canonical)) continue;
                    creators.add(new Creator(canonical, channel.getName(),
                            YouTubeRepository.bestSafeThumbnail(channel.getThumbnails()),
                            channel.getDescription(), channel.getSubscriberCount()));
                    if (creators.size() >= MAX_RESULTS) break;
                }
                if (creators.size() >= MAX_RESULTS || !page.hasNextPage()
                        || upstreamPages >= MAX_UPSTREAM_PAGES) break;
                page = extractor.getPage(page.getNextPage());
            } while (true);
            CreatorCursor next = page.hasNextPage()
                    ? new CreatorCursor(cleanedQuery, page.getNextPage(), boundedIds(seen)) : null;
            return new CreatorPage(creators, next);
        } catch (Exception failure) {
            Diagnostics.event("creator-search-error", failure.getClass().getSimpleName());
            throw new IOException("Creator search failed. Check your connection and retry.");
        }
    }

    /** Load channel metadata and its Videos tab, then return public playable video entries. */
    public ChannelPage channel(String url, ChannelCursor cursor) throws IOException {
        final String canonical;
        try {
            canonical = canonicalChannelUrl(url);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Enter a valid public YouTube channel link.");
        }
        if (cursor != null && !cursor.isBoundTo(canonical)) {
            throw new IOException("Creator page does not match the current channel.");
        }
        try {
            StreamingService service = youtubeService();
            ChannelInfo creatorInfo;
            ListLinkHandler videosTab;
            Creator creator;
            ListExtractor.InfoItemsPage<InfoItem> page;
            if (cursor == null) {
                creatorInfo = ChannelInfo.getInfo(service, canonical);
                creator = new Creator(creatorInfo.getUrl(), creatorInfo.getName(),
                        YouTubeRepository.bestSafeThumbnail(creatorInfo.getAvatars()),
                        creatorInfo.getDescription(), creatorInfo.getSubscriberCount());
                videosTab = findVideosTab(creatorInfo.getTabs());
                if (videosTab == null) {
                    throw new PublicFailure("This creator does not have a public videos tab.");
                }
                ChannelTabInfo tabInfo = ChannelTabInfo.getInfo(service, videosTab);
                page = new ListExtractor.InfoItemsPage<InfoItem>(tabInfo.getRelatedItems(),
                        tabInfo.getNextPage(), Collections.emptyList());
            } else {
                creator = cursor.creator;
                videosTab = cursor.videosTab;
                page = ChannelTabInfo.getMoreItems(service, videosTab, cursor.page);
            }

            Set<String> seen = new HashSet<>();
            if (cursor != null) seen.addAll(cursor.seenIds);
            List<YouTubeRepository.Track> tracks = new ArrayList<>();
            int upstreamPages = 0;
            do {
                upstreamPages++;
                for (InfoItem item : page.getItems()) {
                    if (!(item instanceof StreamInfoItem)) continue;
                    StreamInfoItem stream = (StreamInfoItem) item;
                    StreamType type = stream.getStreamType();
                    if (type != StreamType.VIDEO_STREAM && type != StreamType.AUDIO_STREAM) continue;
                    ContentAvailability availability = stream.getContentAvailability();
                    if (availability == ContentAvailability.UPCOMING
                            || availability == ContentAvailability.MEMBERSHIP
                            || availability == ContentAvailability.PAID) continue;
                    String id;
                    try {
                        id = YouTubeRepository.normalizeVideoId(stream.getUrl());
                    } catch (IllegalArgumentException unsupported) {
                        continue;
                    }
                    if (!seen.add(id)) continue;
                    tracks.add(new YouTubeRepository.Track(id, stream.getName(),
                            stream.getUploaderName(), stream.getDuration(),
                            YouTubeRepository.bestSafeThumbnail(stream.getThumbnails()),
                            stream.getUploaderUrl()));
                    if (tracks.size() >= MAX_RESULTS) break;
                }
                if (tracks.size() >= MAX_RESULTS || !page.hasNextPage()
                        || upstreamPages >= MAX_UPSTREAM_PAGES) break;
                page = ChannelTabInfo.getMoreItems(service, videosTab, page.getNextPage());
            } while (true);
            ChannelCursor next = page.hasNextPage()
                    ? new ChannelCursor(canonical, videosTab, page.getNextPage(), creator,
                            boundedIds(seen)) : null;
            return new ChannelPage(creator, tracks, next);
        } catch (PublicFailure failure) {
            throw failure;
        } catch (Exception failure) {
            Diagnostics.event("creator-channel-error", failure.getClass().getSimpleName());
            throw new IOException("Unable to load creator videos. Check your connection and retry.");
        }
    }

    /** Validate and normalize public YouTube channel URLs accepted by the extractor. */
    public static String canonicalChannelUrl(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid YouTube channel URL.");
        }
        try {
            URI uri = new URI(input.trim());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getRawAuthority() == null || uri.getRawAuthority().contains("%")
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Invalid YouTube channel URL.");
            }
            host = host.toLowerCase(Locale.ROOT);
            if (!(host.equals("youtube.com") || host.equals("www.youtube.com")
                    || host.equals("m.youtube.com") || host.equals("music.youtube.com"))) {
                throw new IllegalArgumentException("Invalid YouTube channel URL.");
            }
            String path = uri.getRawPath();
            if (path == null) throw new IllegalArgumentException("Invalid YouTube channel URL.");
            while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            boolean valid = path.matches("/channel/UC[A-Za-z0-9_-]{22}")
                    || path.matches("/@[A-Za-z0-9._-]{3,30}")
                    || path.matches("/c/[A-Za-z0-9._-]{1,100}")
                    || path.matches("/user/[A-Za-z0-9._-]{1,100}");
            if (!valid) throw new IllegalArgumentException("Invalid YouTube channel URL.");
            return "https://www.youtube.com" + path;
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("Invalid YouTube channel URL.");
        }
    }

    private static final class PublicFailure extends IOException {
        PublicFailure(String message) { super(message); }
    }

    private static ListLinkHandler findVideosTab(List<ListLinkHandler> tabs) {
        if (tabs == null) return null;
        for (ListLinkHandler tab : tabs) {
            if (tab == null) continue;
            for (String filter : tab.getContentFilters()) {
                if ("videos".equalsIgnoreCase(filter)) return tab;
            }
        }
        return null;
    }

    private static StreamingService youtubeService() throws Exception {
        return NewPipe.getService("YouTube");
    }

    private static List<String> boundedIds(Set<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (result.size() >= MAX_CURSOR_IDS) break;
            result.add(value);
        }
        return result;
    }

    private static List<String> immutableIds(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
