// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Search-first native interface for the app-owned MediaSessionService. */
@androidx.media3.common.util.UnstableApi
public final class MainActivity extends android.app.Activity {
    private static final String PREFS = "blackyt_ui";
    private static final String KEY_RECENTS = "recent_searches";
    private static final String KEY_HISTORY = "played_tracks";
    private static final int BG = Color.rgb(18, 18, 18);
    private static final int PANEL = Color.rgb(32, 32, 32);
    private static final int PANEL_LIGHT = Color.rgb(40, 40, 40);
    private static final int CREAM = Color.rgb(255, 255, 255);
    private static final int MUTED = Color.rgb(179, 179, 179);
    private static final int LIME = Color.rgb(190, 242, 100);
    private static final int TAB_HOME = 0, TAB_SEARCH = 1, TAB_LIBRARY = 2, TAB_QUEUE = 3;
    private static final int SEARCH_LIMIT = 200;

    private final ExecutorService searches = Executors.newFixedThreadPool(2);
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<YouTubeRepository.Track> results = new ArrayList<>();
    private final Map<String, YouTubeRepository.Rules> resultRules = new LinkedHashMap<>();
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private EditText queryInput;
    private boolean musicMode = true;
    private String includeFilter = "", excludeFilter = "";
    private LinearLayout pageHost, resultsBox, queueBox, bottomTabs;
    private LinearLayout miniPlayer;
    private ImageView miniArtwork, playerArtwork;
    private TextView status, miniTitle, miniSubtitle, playerTitle, playerArtist;
    private TextView timeLabel, queueSummary, filtersChip, playerHint;
    private ImageButton miniPlayButton, miniNextButton, fullPlayButton, fullPreviousButton, fullNextButton;
    private SeekBar seekBar;
    private ProgressBar miniProgress;
    private ProgressBar searchProgress;
    private boolean seeking, destroyed, activeSearch, resultLimitReached, fullPlayer, hasSearched;
    private boolean searchLoading;
    private String statusMessage = "Search for music, artists, or a YT link.";
    private String queryText = "";
    private String suppressHistoryIdAfterClear = "";
    private int currentTab = TAB_HOME, playerReturnTab = TAB_HOME;
    private long searchGeneration;
    private Future<?> searchTask;
    private YouTubeRepository.SearchCursor nextCursor;
    private YouTubeRepository.Rules displayedRules;
    private String displayedQuery;
    private String renderedQueueKey = "";
    private String pendingProbeId;
    private String startingTrackId, startingTrackTitle;
    private Toast playbackFeedback;
    private ArtworkLoader artworkLoader;
    private Object platformBackCallback;
    private final List<RecentTrack> history = new ArrayList<>();
    private String lastRememberedId = "";
    private ListeningStore listening;
    private boolean creatorMode;
    private ImageButton fullSaveButton;
    private String sharedVideoId = "";
    private long sharedStartMs;
    private String creatorPageUrl;
    private CreatorRepository.Creator creatorInfo;
    private CreatorRepository.CreatorCursor creatorCursor;
    private CreatorRepository.ChannelCursor channelCursor;
    private final List<CreatorRepository.Creator> creatorResults = new ArrayList<>();
    private final List<YouTubeRepository.Track> channelResults = new ArrayList<>();

    private static final class RecentTrack {
        final YouTubeRepository.Track track;
        final YouTubeRepository.Rules rules;
        RecentTrack(YouTubeRepository.Track track, YouTubeRepository.Rules rules) {
            this.track = track;
            this.rules = rules;
        }
    }
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!destroyed) { updatePlayer(); main.postDelayed(this, 1000); }
        }
    };

    private void releasePageViews() {
        if (pageHost != null) cancelArtworkInTree(pageHost);
        queryInput = null;
        resultsBox = null;
        queueBox = null;
        status = null;
        searchProgress = null;
        queueSummary = null;
        filtersChip = null;
        playerArtwork = null;
        playerTitle = null;
        playerArtist = null;
        playerHint = null;
        timeLabel = null;
        seekBar = null;
        fullPlayButton = null;
        fullPreviousButton = null;
        fullNextButton = null;
        fullSaveButton = null;
    }

    private void cancelArtworkInTree(View view) {
        if (view instanceof ImageView) artworkLoader.cancel((ImageView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) cancelArtworkInTree(group.getChildAt(i));
        }
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Diagnostics.init(getApplicationContext());
        SharedPreferences ui = getSharedPreferences(PREFS, MODE_PRIVATE);
        musicMode = ui.getBoolean("music", true);
        creatorMode = ui.getBoolean("creator_mode", false);
        listening = new ListeningStore(this);
        includeFilter = ui.getString("include", "");
        excludeFilter = ui.getString("exclude", "");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        artworkLoader = new ArtworkLoader(this);
        readHistory();
        buildShell();
        showTab(TAB_HOME);
        connectController();
        Intent launch = getIntent();
        if (handleIncomingLink(launch)) return;
        if (BuildConfig.DEBUG && launch != null) {
            String probeVideo = launch.getStringExtra("probe_video");
            String probeQuery = launch.getStringExtra("probe_query");
            if (probeVideo != null && probeVideo.matches("[A-Za-z0-9_-]{11}")) {
                showTab(TAB_SEARCH);
                pendingProbeId = probeVideo;
                queryInput.setText("https://www.youtube.com/watch?v=" + probeVideo);
                search();
            } else if (probeQuery != null && !probeQuery.trim().isEmpty()) {
                showTab(TAB_SEARCH);
                queryInput.setText(probeQuery);
                main.postDelayed(this::search, 350);
            }
        }
    }

    private void buildShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);

        pageHost = new LinearLayout(this);
        pageHost.setOrientation(LinearLayout.VERTICAL);
        pageHost.setBackgroundColor(BG);
        shell.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));

        miniPlayer = new LinearLayout(this);
        miniPlayer.setOrientation(LinearLayout.VERTICAL);
        miniPlayer.setPadding(dp(8), dp(5), dp(8), dp(1));
        miniPlayer.setBackground(background(PANEL, 8));
        LinearLayout miniRow = row();
        miniArtwork = imageView(dp(48), "Current song artwork");
        miniRow.addView(miniArtwork, lp(dp(48), dp(48), 0, 0, 10, 0));
        LinearLayout miniLabels = new LinearLayout(this);
        miniLabels.setOrientation(LinearLayout.VERTICAL);
        miniTitle = text("Nothing playing", 14, CREAM, true);
        miniTitle.setMaxLines(1);
        miniSubtitle = text("", 12, MUTED, false);
        miniSubtitle.setMaxLines(1);
        miniLabels.addView(miniTitle);
        miniLabels.addView(miniSubtitle, lp(-1, -2, 0, 2, 0, 0));
        miniRow.addView(miniLabels, new LinearLayout.LayoutParams(0, -2, 1));
        miniPlayButton = iconButton(R.drawable.ic_play, "Play current song", LIME);
        miniPlayButton.setOnClickListener(v -> togglePlayback());
        miniRow.addView(miniPlayButton, lp(dp(48), dp(48), 3, 0, 2, 0));
        miniNextButton = iconButton(R.drawable.ic_next, "Play next song", CREAM);
        miniNextButton.setOnClickListener(v -> skipQueuedSong(true));
        miniRow.addView(miniNextButton, lp(dp(48), dp(48), 0, 0, 0, 0));
        miniRow.setOnClickListener(v -> openFullPlayer());
        miniRow.setContentDescription("Open Now Playing");
        miniRow.setFocusable(true);
        touchFeedback(miniRow, 6);
        installQueueSwipe(miniRow, true);
        miniPlayer.addView(miniRow);
        miniProgress = progressBar();
        miniPlayer.addView(miniProgress, lp(-1, dp(3), 0, 3, 0, 0));
        miniPlayer.setVisibility(View.GONE);
        shell.addView(miniPlayer, lp(-1, -2, 8, 4, 8, 4));

        bottomTabs = new LinearLayout(this);
        bottomTabs.setOrientation(LinearLayout.HORIZONTAL);
        bottomTabs.setGravity(Gravity.CENTER);
        bottomTabs.setPadding(dp(4), dp(2), dp(4), dp(4));
        shell.addView(bottomTabs, lp(-1, dp(60), 0, 0, 0, 0));

        // Apply safe areas once on the attached shell, including rebuilt pages and Now Playing.
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            shell.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(shell);
        shell.requestApplyInsets();
        updateTabs();
    }

    private void showTab(int tab) {
        releasePageViews();
        fullPlayer = false;
        bottomTabs.setVisibility(View.VISIBLE);
        currentTab = tab;
        pageHost.removeAllViews();
        if (tab == TAB_HOME) pageHost.addView(buildHomePage(), new LinearLayout.LayoutParams(-1, -1));
        else if (tab == TAB_SEARCH) pageHost.addView(buildSearchExperience(), new LinearLayout.LayoutParams(-1, -1));
        else if (tab == TAB_LIBRARY) pageHost.addView(buildLibraryPage(), new LinearLayout.LayoutParams(-1, -1));
        else pageHost.addView(buildQueueExperience(), new LinearLayout.LayoutParams(-1, -1));
        updateTabs();
        miniPlayer.setVisibility(controller != null && controller.getCurrentMediaItem() != null ? View.VISIBLE : View.GONE);
        if (tab == TAB_QUEUE) renderQueue();
        updatePlayer();
        updateBackHandler();
    }

    private void openFullPlayer() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        playerReturnTab = currentTab;
        releasePageViews();
        fullPlayer = true;
        pageHost.removeAllViews();
        pageHost.addView(buildNowPlayingPage(), new LinearLayout.LayoutParams(-1, -1));
        miniPlayer.setVisibility(View.GONE);
        bottomTabs.setVisibility(View.GONE);
        updatePlayer();
        updateBackHandler();
    }

    private void closeFullPlayer() {
        fullPlayer = false;
        showTab(playerReturnTab);
    }

    private View buildHomePage() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageColumn(scroll);
        LinearLayout brand = row();
        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.blackyt_avatar);
        mascot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mascot.setContentDescription("BlackYT blackbird");
        brand.addView(mascot, lp(dp(48), dp(48), 0, 0, 10, 0));
        TextView wordmark = text("BlackYT", 20, CREAM, true);
        brand.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton about = iconButton(R.drawable.ic_more, "More options", MUTED);
        about.setOnClickListener(v -> showMoreMenu(about));
        brand.addView(about, lp(dp(48), dp(48), 4, 0, 0, 0));
        root.addView(brand, lp(-1, -2, 0, 0, 0, 14));

        root.addView(text("Jump back in", 22, CREAM, true), lp(-1, -2, 0, 2, 0, 9));
        if (history.isEmpty()) {
            LinearLayout empty = row();
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(dp(2), dp(5), dp(2), dp(9));
            ImageView art = new ImageView(this);
            art.setImageResource(R.drawable.blackyt_mascot);
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            empty.addView(art, lp(dp(58), dp(58), 0, 0, 11, 0));
            empty.addView(text("Songs you play will appear here.", 14, MUTED, false));
            root.addView(empty, lp(-1, -2, 0, 0, 0, 18));
        } else {
            HorizontalScrollView recentScroll = new HorizontalScrollView(this);
            recentScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout recentRow = new LinearLayout(this);
            recentRow.setOrientation(LinearLayout.HORIZONTAL);
            for (RecentTrack recent : history) {
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setFocusable(true);
                touchFeedback(tile, 6);
                tile.setOnClickListener(v -> playTrack(recent.track, recent.rules));
                ImageView art = imageView(dp(124), "Artwork for " + recent.track.title);
                artworkLoader.load(recent.track.artworkUrl, art);
                tile.addView(art, lp(dp(124), dp(124), 0, 0, 0, 7));
                TextView title = text(recent.track.title, 13, CREAM, true);
                title.setMaxLines(1);
                TextView artist = text(recent.track.channel, 12, MUTED, false);
                artist.setMaxLines(1);
                tile.addView(title);
                tile.addView(artist, lp(-1, -2, 0, 2, 0, 0));
                tile.setContentDescription("Play " + recent.track.title + " by " + recent.track.channel);
                recentRow.addView(tile, lp(dp(124), -2, 0, 0, 12, 0));
            }
            recentScroll.addView(recentRow);
            root.addView(recentScroll, lp(-1, -2, 0, 0, 0, 22));
        }

        LinearLayout exploreHeading = row();
        exploreHeading.addView(text("Explore", 22, CREAM, true), new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView edit = modeChip("Edit", false); edit.setContentDescription("Edit Explore topics");
        edit.setOnClickListener(v -> showExploreEditor());
        exploreHeading.addView(edit, lp(-2, dp(48), 0, 0, 0, 0));
        root.addView(exploreHeading, lp(-1, -2, 0, 0, 0, 6));
        root.addView(text("Music, talks and podcasts. Make these topics your own.", 13, MUTED, false), lp(-1, -2, 0, 0, 0, 10));
        LinearLayout discover = row();
        Button talks = button("Talks & podcasts", true);
        talks.setContentDescription("Discover talks and podcasts");
        talks.setOnClickListener(v -> enterSearch("podcast interview", false, false));
        Button creators = button("Find a creator", false); creators.setContentDescription("Find a creator");
        creators.setOnClickListener(v -> { switchSearchMode(false, true); showTab(TAB_SEARCH); });
        discover.addView(talks, new LinearLayout.LayoutParams(0, dp(48), 1));
        discover.addView(new View(this), lp(dp(8), 1, 0, 0, 0, 0));
        discover.addView(creators, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(discover, lp(-1, dp(48), 0, 0, 0, 12));
        List<ListeningStore.ExploreTile> tiles = listening.listTiles();
        for (int i = 0; i < tiles.size(); i += 2) {
            LinearLayout pair = row();
            ListeningStore.ExploreTile first = tiles.get(i);
            pair.addView(interestTile(first.title, first.query, first.color, first.musicOnly), new LinearLayout.LayoutParams(0, dp(82), 1));
            pair.addView(new View(this), lp(dp(9), 1, 0, 0, 0, 0));
            if (i + 1 < tiles.size()) {
                ListeningStore.ExploreTile next = tiles.get(i + 1);
                pair.addView(interestTile(next.title, next.query, next.color, next.musicOnly), new LinearLayout.LayoutParams(0, dp(82), 1));
            } else pair.addView(new View(this), new LinearLayout.LayoutParams(0, dp(82), 1));
            root.addView(pair, lp(-1, dp(82), 0, 0, 0, 9));
        }
        return scroll;
    }

    private View interestTile(String title, String query, int color, boolean musicOnly) {
        TextView tile = text(title, 17, CREAM, true);
        tile.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        tile.setPadding(dp(16), 0, dp(12), 0);
        tile.setBackground(background(color, 5));
        touchFeedback(tile, 5);
        tile.setContentDescription("Search YT for " + query);
        tile.setOnClickListener(v -> enterSearch(query, musicOnly, false));
        return tile;
    }

    private View buildSearchExperience() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageColumn(scroll);
        LinearLayout heading = row();
        heading.addView(text("Search", 26, CREAM, true), new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageButton about = iconButton(R.drawable.ic_more, "About BlackYT", MUTED);
        about.setOnClickListener(v -> showAbout());
        heading.addView(about, lp(dp(48), dp(48), 0, 0, 0, 0));
        root.addView(heading, lp(-1, dp(48), 0, 0, 0, 10));

        LinearLayout queryBar = row();
        queryBar.setPadding(dp(10), 0, dp(2), 0);
        queryBar.setBackground(background(PANEL_LIGHT, 6));
        ImageView magnifier = iconView(R.drawable.ic_nav_search, "Search", MUTED);
        queryBar.addView(magnifier, lp(dp(28), dp(28), 1, 0, 6, 0));
        queryInput = input("Songs, shows, creators, or a shared YT link");
        queryInput.setPadding(dp(3), 0, dp(2), 0);
        queryInput.setBackgroundColor(Color.TRANSPARENT);
        queryInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        queryText = getSharedPreferences(PREFS, MODE_PRIVATE).getString("query", "");
        queryInput.setText(queryText);
        queryInput.setContentDescription("Search songs, artists, or YT links");
        queryInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        queryInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(queryInput); search(); return true; }
            return false;
        });
        queryBar.addView(queryInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageButton clear = iconButton(R.drawable.ic_close, "Clear search text", MUTED);
        clear.setOnClickListener(v -> queryInput.setText(""));
        queryBar.addView(clear, lp(dp(48), dp(48), 0, 0, 0, 0));
        ImageButton go = iconButton(R.drawable.ic_nav_search, "Search YT", LIME);
        go.setOnClickListener(v -> { hideKeyboard(queryInput); search(); });
        queryBar.addView(go, lp(dp(48), dp(48), 0, 0, 0, 0));
        root.addView(queryBar, lp(-1, dp(48), 0, 0, 0, 10));

        HorizontalScrollView chipsScroll = new HorizontalScrollView(this);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        musicMode = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("music", true);
        TextView music = modeChip("Music", musicMode && !creatorMode);
        TextView videos = modeChip("All videos", !musicMode && !creatorMode);
        TextView creators = modeChip("Creators", creatorMode);
        music.setOnClickListener(v -> switchSearchMode(true, false));
        videos.setOnClickListener(v -> switchSearchMode(false, false));
        creators.setOnClickListener(v -> switchSearchMode(false, true));
        boolean hasFilters = !includeFilter.trim().isEmpty() || !excludeFilter.trim().isEmpty();
        TextView filters = modeChip(hasFilters ? "Filters •" : "Filters", hasFilters);
        filtersChip = filters;
        updateFiltersChip();
        filters.setOnClickListener(v -> showFiltersDialog());
        chips.addView(music, lp(-2, dp(48), 0, 0, 7, 0));
        chips.addView(videos, lp(-2, dp(48), 0, 0, 7, 0));
        chips.addView(creators, lp(-2, dp(48), 0, 0, 7, 0));
        if (!creatorMode) chips.addView(filters, lp(-2, dp(48), 0, 0, 0, 0));
        chipsScroll.addView(chips);
        root.addView(chipsScroll, lp(-1, dp(48), 0, 0, 0, 8));
        root.addView(text(creatorMode ? "Find a creator, then browse episodes without copying links."
                : musicMode ? "Songs and music. Use All videos for spoken episodes."
                : "Talks, interviews, podcasts and all public YT videos.", 12, MUTED, false), lp(-1, -2, 0, 0, 0, 10));

        LinearLayout progress = row();
        searchProgress = new ProgressBar(this);
        searchProgress.setIndeterminate(true);
        searchProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(LIME));
        searchProgress.setVisibility(searchLoading ? View.VISIBLE : View.GONE);
        progress.addView(searchProgress, lp(dp(18), dp(18), 1, 0, 8, 0));
        status = text(statusMessage, 12, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progress.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(progress, lp(-1, -2, 0, 0, 0, 8));

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsBox, lp(-1, -2, 0, 0, 0, 8));
        attachSearchPersistence();
        renderSearchResults();
        return scroll;
    }

    private TextView modeChip(String title, boolean selected) {
        TextView chip = text(title, 13, selected ? BG : CREAM, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(14), 0, dp(14), 0);
        chip.setBackground(background(selected ? LIME : PANEL_LIGHT, 5));
        chip.setFocusable(true);
        touchFeedback(chip, 5);
        chip.setContentDescription(title + (selected ? ", selected" : ""));
        return chip;
    }

    private void renderSearchResults() {
        if (resultsBox == null) return;
        resultsBox.removeAllViews();
        resultsBox.setVisibility(View.VISIBLE);
        if (creatorMode || creatorPageUrl != null) { renderCreatorResults(); return; }
        if (results.isEmpty()) {
            if (hasSearched && !searchLoading) resultsBox.addView(text("No matches. Try another search or adjust your filters.", 14, MUTED, false),
                    lp(-1, -2, 0, 4, 0, 10));
            renderSearchRecents(resultsBox);
            return;
        }
        LinearLayout resultHeading = row();
        resultHeading.addView(text("Results", 17, CREAM, true), new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView clear = text("Clear", 13, MUTED, true);
        clear.setGravity(Gravity.CENTER);
        clear.setContentDescription("Clear search results");
        touchFeedback(clear, 5);
        clear.setOnClickListener(v -> clearResults());
        resultHeading.addView(clear, lp(dp(60), dp(48), 0, 0, 0, 0));
        resultsBox.addView(resultHeading);
        if (sharedStartMs > 0 && !sharedVideoId.isEmpty())
            resultsBox.addView(text("Shared starting point · " + format(sharedStartMs / 1000) + " · tap a result to play", 13, LIME, false), lp(-1, -2, 0, 0, 0, 8));
        for (YouTubeRepository.Track track : results) resultsBox.addView(buildCompactResult(track), lp(-1, dp(72), 0, 0, 0, 2));
        if (nextCursor != null && !activeSearch && results.size() < SEARCH_LIMIT) {
            TextView more = text("Load more", 14, CREAM, true);
            more.setGravity(Gravity.CENTER);
            more.setBackground(background(PANEL_LIGHT, 5));
            touchFeedback(more, 5);
            more.setContentDescription("Load more search results");
            more.setOnClickListener(v -> loadMore());
            resultsBox.addView(more, lp(-1, dp(48), 0, 10, 0, 0));
        }
        if (resultLimitReached) resultsBox.addView(text("Showing the first " + SEARCH_LIMIT + " matches. Refine your search for more.",
                12, MUTED, false), lp(-1, -2, 0, 10, 0, 0));
    }

    private View buildCompactResult(YouTubeRepository.Track track) {
        LinearLayout item = row();
        item.setFocusable(true);
        touchFeedback(item, 5);
        item.setContentDescription("Play " + track.title + " by " + track.channel);
        item.setOnClickListener(v -> playTrack(track));
        ImageView art = imageView(dp(56), "Artwork for " + track.title);
        artworkLoader.load(track.artworkUrl, art);
        item.addView(art, lp(dp(56), dp(56), 0, 0, 10, 0));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(track.title, 14, CREAM, true);
        title.setMaxLines(1);
        TextView artist = text(track.channel + "  ·  " + format(track.durationSeconds), 12, MUTED, false);
        artist.setMaxLines(1);
        words.addView(title);
        words.addView(artist, lp(-1, -2, 0, 4, 0, 0));
        item.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton add = iconButton(R.drawable.ic_add, "Add " + track.title + " to queue", MUTED);
        add.setOnClickListener(v -> addNext(track));
        boolean saved = listening.isSaved(track.id);
        ImageButton bookmark = iconButton(saved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark,
                (saved ? "Saved " : "Save ") + track.title + " in Library", saved ? LIME : MUTED);
        bookmark.setOnClickListener(v -> {
            YouTubeRepository.Rules rules = resultRules.get(track.id);
            saveEpisode(track, rules == null ? new YouTubeRepository.Rules(musicMode && !creatorMode, includeFilter, excludeFilter) : rules, bookmark);
        });
        item.addView(bookmark, lp(dp(48), dp(48), 0, 0, 0, 0));
        item.addView(add, lp(dp(48), dp(48), 0, 0, 0, 0));
        if (!track.creatorUrl.isEmpty()) item.setOnLongClickListener(v -> { openCreator(track.creatorUrl); return true; });
        return item;
    }

    private void renderSearchRecents(LinearLayout parent) {
        List<String> recent = getRecents();
        if (recent.isEmpty()) return;
        LinearLayout heading = row();
        heading.addView(text("Recent searches", 15, CREAM, true), new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView clear = text("Clear", 12, MUTED, true);
        clear.setGravity(Gravity.CENTER);
        touchFeedback(clear, 5);
        clear.setOnClickListener(v -> { saveRecents(new ArrayList<>()); renderSearchResults(); });
        heading.addView(clear, lp(dp(56), dp(48), 0, 0, 0, 0));
        parent.addView(heading);
        for (String query : recent) {
            LinearLayout row = row();
            ImageView icon = iconView(R.drawable.ic_nav_search, "Recent search", MUTED);
            row.addView(icon, lp(dp(24), dp(24), 2, 0, 12, 0));
            TextView label = text(query, 14, CREAM, false);
            label.setMaxLines(1);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton erase = iconButton(R.drawable.ic_close, "Remove recent search " + query, MUTED);
            erase.setOnClickListener(v -> { List<String> values = getRecents(); values.remove(query); saveRecents(values); renderSearchResults(); });
            row.addView(erase, lp(dp(48), dp(48), 0, 0, 0, 0));
            row.setContentDescription("Search again for " + query);
            touchFeedback(row, 5);
            row.setOnClickListener(v -> { queryInput.setText(query); search(); });
            parent.addView(row, lp(-1, dp(52), 0, 0, 0, 1));
        }
    }

    private void showFiltersDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(4));
        EditText include = input("Include any words, comma separated");
        include.setText(includeFilter);
        EditText exclude = input("Exclude any words, comma separated");
        exclude.setText(excludeFilter);
        content.addView(include, lp(-1, dp(52), 0, 4, 0, 8));
        content.addView(exclude, lp(-1, dp(52), 0, 0, 0, 4));
        new android.app.AlertDialog.Builder(this).setTitle("Search filters").setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    includeFilter = include.getText().toString();
                    excludeFilter = exclude.getText().toString();
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("include", includeFilter)
                            .putString("exclude", excludeFilter).apply();
                    updateFiltersChip();
                    invalidateSearchForEdits();
                }).show();
    }

    private void updateFiltersChip() {
        if (filtersChip == null) return;
        boolean hasFilters = !includeFilter.trim().isEmpty() || !excludeFilter.trim().isEmpty();
        filtersChip.setText(hasFilters ? "Filters •" : "Filters");
        filtersChip.setTextColor(hasFilters ? BG : CREAM);
        filtersChip.setBackground(background(hasFilters ? LIME : PANEL_LIGHT, 5));
        filtersChip.setContentDescription(hasFilters
                ? "Search filters active. Include: " + (includeFilter.isEmpty() ? "none" : includeFilter)
                        + ". Exclude: " + (excludeFilter.isEmpty() ? "none" : excludeFilter)
                : "Open search filters");
    }

    private View buildNowPlayingPage() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageColumn(scroll);
        root.setPadding(dp(24), dp(4), dp(24), dp(24));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(48, 57, 40), BG, BG}));
        LinearLayout top = row();
        ImageButton back = iconButton(R.drawable.ic_back, "Back", CREAM);
        back.setOnClickListener(v -> closeFullPlayer());
        top.addView(back, lp(dp(48), dp(48), -8, 0, 0, 0));
        TextView now = text("Now Playing", 14, CREAM, true);
        now.setGravity(Gravity.CENTER);
        top.addView(now, new LinearLayout.LayoutParams(0, dp(48), 1));
        ImageButton queue = iconButton(R.drawable.ic_nav_queue, "Open Queue", CREAM);
        queue.setOnClickListener(v -> showTab(TAB_QUEUE));
        top.addView(queue, lp(dp(48), dp(48), 0, 0, 0, 0));
        ImageButton more = iconButton(R.drawable.ic_more, "More", CREAM);
        more.setOnClickListener(v -> showMoreMenu(more));
        top.addView(more, lp(dp(48), dp(48), 0, 0, -8, 0));
        root.addView(top, lp(-1, dp(52), 0, 0, 0, 12));

        LinearLayout artArea = new LinearLayout(this);
        artArea.setGravity(Gravity.CENTER);
        int artSize = Math.min(dp(360), Math.max(dp(220), getResources().getDisplayMetrics().widthPixels - dp(48)));
        playerArtwork = imageView(artSize, "Current song cover artwork. Swipe left for next song, right for previous song.");
        installQueueSwipe(playerArtwork, false);
        artArea.addView(playerArtwork, new LinearLayout.LayoutParams(artSize, artSize));
        root.addView(artArea, new LinearLayout.LayoutParams(-1, 0, 1));
        playerHint = text("Swipe artwork left or right to change queued songs", 12, MUTED, false);
        playerHint.setGravity(Gravity.CENTER);
        playerHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(playerHint, lp(-1, -2, 0, 10, 0, 2));

        LinearLayout details = row();
        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        playerTitle = text("Nothing playing yet", 22, CREAM, true);
        playerTitle.setMaxLines(2);
        playerArtist = text("Choose a result to start listening.", 15, MUTED, false);
        playerArtist.setMaxLines(1);
        titleColumn.addView(playerTitle);
        titleColumn.addView(playerArtist, lp(-1, -2, 0, 4, 0, 0));
        details.addView(titleColumn, new LinearLayout.LayoutParams(0, -2, 1));
        fullSaveButton = iconButton(R.drawable.ic_bookmark, "Save current episode in Library", MUTED);
        fullSaveButton.setOnClickListener(v -> saveCurrentEpisode());
        details.addView(fullSaveButton, lp(dp(48), dp(48), 4, 0, 0, 0));
        ImageButton trackMore = iconButton(R.drawable.ic_more, "Track options", MUTED);
        trackMore.setOnClickListener(v -> showMoreMenu(trackMore));
        details.addView(trackMore, lp(dp(48), dp(48), 8, 0, 0, 0));
        root.addView(details, lp(-1, -2, 0, 10, 0, 4));

        timeLabel = text("0:00", 12, MUTED, false);
        timeLabel.setGravity(Gravity.END);
        root.addView(timeLabel, lp(-1, -2, 0, 0, 0, 0));
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setContentDescription("Song position");
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(LIME));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(LIME));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { seeking = fromUser; }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (controller != null && controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                    long duration = controller.getDuration();
                    if (duration > 0 && duration != C.TIME_UNSET) controller.seekTo(duration * bar.getProgress() / 1000L);
                }
                updatePlayer();
            }
        });
        root.addView(seekBar, lp(-1, dp(42), -8, 0, -8, 8));

        LinearLayout transport = row();
        transport.setGravity(Gravity.CENTER);
        fullPreviousButton = iconButton(R.drawable.ic_previous, "Previous song", CREAM);
        fullPreviousButton.setOnClickListener(v -> skipQueuedSong(false));
        fullPlayButton = iconButton(R.drawable.ic_play, "Play", BG);
        fullPlayButton.setBackground(background(LIME, 40));
        fullPlayButton.setOnClickListener(v -> togglePlayback());
        fullNextButton = iconButton(R.drawable.ic_next, "Next song", CREAM);
        fullNextButton.setOnClickListener(v -> skipQueuedSong(true));
        transport.addView(fullPreviousButton, lp(dp(56), dp(56), 6, 0, 18, 0));
        transport.addView(fullPlayButton, lp(dp(64), dp(64), 8, 0, 8, 0));
        transport.addView(fullNextButton, lp(dp(56), dp(56), 18, 0, 6, 0));
        root.addView(transport, lp(-1, dp(72), 0, 4, 0, 0));
        return scroll;
    }

    private View buildQueueExperience() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageColumn(scroll);
        LinearLayout heading = row();
        heading.addView(text("Queue", 26, CREAM, true), new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView count = text("FIFO", 12, MUTED, true);
        count.setGravity(Gravity.CENTER);
        heading.addView(count, lp(dp(64), dp(48), 0, 0, 0, 0));
        root.addView(heading);
        queueSummary = text("", 13, MUTED, false);
        root.addView(queueSummary, lp(-1, -2, 0, 0, 0, 12));
        queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(queueBox);
        return scroll;
    }

    private void showMoreMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add("Save current episode").setOnMenuItemClickListener(item -> { saveCurrentEpisode(); return true; });
        menu.getMenu().add("Your Library").setOnMenuItemClickListener(item -> { showTab(TAB_LIBRARY); return true; });
        menu.getMenu().add("Edit Explore topics").setOnMenuItemClickListener(item -> { showExploreEditor(); return true; });
        YouTubeRepository.Track current = currentTrack();
        if (current != null && !current.creatorUrl.isEmpty()) menu.getMenu().add("Browse creator").setOnMenuItemClickListener(item -> { openCreator(current.creatorUrl); return true; });
        menu.getMenu().add("Stop playback").setOnMenuItemClickListener(item -> {
            ifController(MediaController::stop);
            return true;
        });
        menu.getMenu().add("Retry current song").setOnMenuItemClickListener(item -> { retryCurrent(); return true; });
        menu.getMenu().add("Clear listening history").setOnMenuItemClickListener(item -> {
            new android.app.AlertDialog.Builder(this).setTitle("Clear listening history?")
                    .setMessage("This removes Jump back in from this device. The song playing now will stay out of history until another song starts.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear", (dialog, which) -> clearListeningHistory()).show();
            return true;
        });
        menu.getMenu().add("About BlackYT").setOnMenuItemClickListener(item -> { showAbout(); return true; });
        menu.show();
    }

    private void updateTabs() {
        if (bottomTabs == null) return;
        bottomTabs.removeAllViews();
        String[] labels = {"Home", "Search", "Library", "Queue"};
        int[] icons = {R.drawable.ic_nav_home, R.drawable.ic_nav_search, R.drawable.ic_nav_library, R.drawable.ic_nav_queue};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i;
            boolean selected = i == currentTab;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            ImageView icon = iconView(icons[i], labels[i] + " tab", selected ? LIME : MUTED);
            item.addView(icon, lp(dp(24), dp(24), 0, 0, 0, 2));
            TextView label = text(labels[i], 11, selected ? LIME : MUTED, selected);
            label.setGravity(Gravity.CENTER);
            item.addView(label);
            item.setContentDescription(labels[i] + " tab" + (selected ? ", selected" : ""));
            item.setFocusable(true);
            touchFeedback(item, 8);
            item.setOnClickListener(v -> showTab(tab));
            bottomTabs.addView(item, new LinearLayout.LayoutParams(0, dp(54), 1));
        }
    }

    private void connectController() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            if (destroyed) return;
            try {
                controller = controllerFuture.get();
                controller.addListener(playerListener);
                updatePlayer();
                maybePlayProbe();
            } catch (Exception failure) {
                safeLog("controller_connect_failed");
                setStatus("Player connection failed. Reopen the app to retry.");
            }
        }, main::post);
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { updatePlayer(); }
        @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            if (!suppressHistoryIdAfterClear.isEmpty()) suppressHistoryIdAfterClear = "";
            if (controller != null && controller.isPlaying()) rememberCurrent();
            updatePlayer();
        }
        @Override public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying) rememberCurrent();
            updatePlayer();
        }
        @Override public void onPlayerError(PlaybackException error) {
            startingTrackId = null;
            startingTrackTitle = null;
            safeLog("player_error_" + error.errorCode);
            String message = "Could not play this song. Check your connection or retry.";
            setStatus(message);
            android.widget.Toast.makeText(MainActivity.this, message, android.widget.Toast.LENGTH_LONG).show();
        }
    };

    private void search() {
        String query = queryInput == null ? "" : queryInput.getText().toString().trim();
        if (query.isEmpty()) { setStatus("Enter a song, episode, creator, or YT link."); return; }
        if (creatorMode && !query.contains("://")) { creatorPageUrl = null; searchCreators(false); return; }
        if (creatorMode && query.contains("://")) {
            // A shared video remains a video even if the last search selected Creators.
            try {
                YtLink.Result link = YtLink.parse(query);
                if (!link.isVideo()) { openCreator(link.channelUrl); return; }
            } catch (IllegalArgumentException invalid) { setStatus("That link is not a supported YT video or creator link."); return; }
            creatorMode = false; musicMode = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("creator_mode", false).putBoolean("music", false).apply();
            showTab(TAB_SEARCH);
        }
        sharedVideoId = ""; sharedStartMs = 0;
        if (query.contains("://")) {
            try {
                YtLink.Result link = YtLink.parse(query);
                if (!link.isVideo()) { openCreator(link.channelUrl); return; }
                sharedVideoId = link.videoId; sharedStartMs = link.startMs;
                query = link.canonicalUrl;
                String displayLink = link.canonicalUrl;
                if (!displayLink.equals(queryInput.getText().toString())) queryInput.setText(displayLink);
            } catch (IllegalArgumentException invalid) { setStatus("That link is not a supported YT video or creator link."); return; }
        }
        final String requestQuery = query;
        final long generation = ++searchGeneration;
        final YouTubeRepository.Rules rules = currentRules();
        if (searchTask != null) searchTask.cancel(true);
        clearSearchResults(false);
        resultLimitReached = false;
        hasSearched = true;
        activeSearch = true;
        rememberSearch(query);
        setSearching(true, "Searching YT…");
        renderSearchResults();
        searchTask = searches.submit(() -> {
            try {
                YouTubeRepository.SearchPage page = YouTubeRepository.get().searchPage(requestQuery, rules, null);
                main.post(() -> {
                    if (destroyed || generation != searchGeneration) return;
                    activeSearch = false;
                    displayedRules = rules;
                    displayedQuery = requestQuery;
                    nextCursor = queryText.trim().equals(requestQuery)
                            && sameRules(currentRules(), rules) ? page.next : null;
                    appendUnique(page.tracks, rules);
                    showResults();
                    setSearching(false, results.isEmpty() ? "No matches. Try another search or adjust filters."
                            : resultLimitReached ? "Showing the first " + SEARCH_LIMIT + " matches. Refine your search for more."
                            : results.size() + (results.size() == 1 ? " result" : " results")
                                    + " · Play starts now; Add queues at the end.");
                    if (!page.suggestion.isEmpty()) setStatus("Try “" + page.suggestion + "” for more matches.");
                    maybePlayProbe();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (destroyed || generation != searchGeneration) return;
                    activeSearch = false;
                    clearSearchResults(false);
                    showResults();
                    setSearching(false, "Search failed. Check your connection and try again.");
                });
            }
        });
    }

    private void loadMore() {
        if (nextCursor == null || displayedRules == null || displayedQuery == null) return;
        final YouTubeRepository.SearchCursor cursor = nextCursor;
        final YouTubeRepository.Rules rules = displayedRules;
        final String query = displayedQuery;
        final long generation = searchGeneration;
        activeSearch = true;
        setSearching(true, "Loading more YT results…");
        searchTask = searches.submit(() -> {
            try {
                YouTubeRepository.SearchPage page = YouTubeRepository.get().searchPage(query, rules, cursor);
                main.post(() -> {
                    if (destroyed || generation != searchGeneration) return;
                    activeSearch = false;
                    nextCursor = page.next;
                    appendUnique(page.tracks, rules);
                    showResults();
                    setSearching(false, page.suggestion.length() > 0 ? "Try “" + page.suggestion + "” for more matches."
                            : resultLimitReached ? "Showing the first " + SEARCH_LIMIT + " matches. Refine your search for more."
                            : results.size() + (results.size() == 1 ? " result" : " results"));
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (destroyed || generation != searchGeneration) return;
                    activeSearch = false;
                    nextCursor = cursor;
                    setSearching(false, "Could not load more results. Try again.");
                    showResults();
                });
            }
        });
    }

    private void appendUnique(List<YouTubeRepository.Track> tracks, YouTubeRepository.Rules rules) {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (YouTubeRepository.Track existing : results) seen.add(existing.id);
        for (YouTubeRepository.Track track : tracks) {
            if (track != null && track.id != null && seen.add(track.id)) {
                if (results.size() >= SEARCH_LIMIT) { resultLimitReached = true; continue; }
                results.add(track);
                resultRules.put(track.id, rules);
            }
        }
    }

    private void showResults() {
        renderSearchResults();
    }

    private void playTrack(YouTubeRepository.Track track) {
        YouTubeRepository.Rules rules = resultRules.get(track.id);
        if (rules == null) rules = displayedRules == null ? currentRules() : displayedRules;
        playTrack(track, rules, track.id.equals(sharedVideoId) ? sharedStartMs : 0);
    }

    private void playTrack(YouTubeRepository.Track track, YouTubeRepository.Rules rules) { playTrack(track, rules, 0); }

    private void playTrack(YouTubeRepository.Track track, YouTubeRepository.Rules rules, long startMs) {
        if (controller == null) { showPlaybackFeedback("Connecting to the player… try again in a moment."); return; }
        // A second tap while this selection is resolving must not enqueue it twice.
        if (track.id.equals(startingTrackId) && controller.getCurrentMediaItem() != null
                && track.id.equals(controller.getCurrentMediaItem().mediaId) && controller.getPlayWhenReady()) {
            showPlaybackFeedback("Starting " + track.title + "…");
            return;
        }
        startingTrackId = track.id;
        startingTrackTitle = track.title;
        showPlaybackFeedback("Starting " + track.title + "…");
        MediaItem item = NativeMediaItems.fromTrack(track, rules);
        int insertAt = controller.getMediaItemCount() == 0 ? 0 : Math.max(0, controller.getCurrentMediaItemIndex() + 1);
        controller.addMediaItem(insertAt, item);
        controller.seekTo(insertAt, Math.max(0, startMs));
        controller.prepare();
        controller.play();
        updatePlayer();
        safeLog("play_selected");
    }

    private void addNext(YouTubeRepository.Track track) {
        if (controller == null) { setStatus("Connecting to the player… try again in a moment."); return; }
        YouTubeRepository.Rules rules = resultRules.get(track.id);
        if (rules == null) rules = displayedRules == null ? currentRules() : displayedRules;
        addToQueue(track, rules);
    }

    private void addToQueue(YouTubeRepository.Track track, YouTubeRepository.Rules rules) {
        if (controller == null) { showPlaybackFeedback("Connecting to the player…"); return; }
        MediaItem item = NativeMediaItems.fromTrack(track, rules);
        int count = controller.getMediaItemCount();
        controller.addMediaItem(item);
        setStatus(count == 0 ? "Added to your queue. Open Queue and tap the song to start it."
                : "Added to the end of your queue. Current playback continues.");
        safeLog("queue_add");
        updatePlayer();
        showTab(TAB_QUEUE);
    }

    private void retryCurrent() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            setStatus("There is no current song to retry."); return;
        }
        MediaItem item = controller.getCurrentMediaItem();
        String id = item.mediaId;
        boolean resume = controller.getPlayWhenReady();
        long resumePosition = Math.max(0, controller.getCurrentPosition());
        searches.execute(() -> {
            YouTubeRepository.get().invalidate(id);
            main.post(() -> {
                if (controller == null || controller.getCurrentMediaItem() == null
                        || !id.equals(controller.getCurrentMediaItem().mediaId)) return;
                controller.seekTo(resumePosition);
                controller.prepare();
                if (resume) controller.play();
                setStatus("Refreshing the stream and retrying…");
            });
        });
    }

    private void updatePlayer() {
        if (destroyed) return;
        MediaItem current = controller == null ? null : controller.getCurrentMediaItem();
        boolean hasCurrent = current != null;
        CharSequence rawTitle = hasCurrent ? current.mediaMetadata.title : null;
        CharSequence rawArtist = hasCurrent ? current.mediaMetadata.artist : null;
        String title = rawTitle == null || rawTitle.length() == 0 ? "Nothing playing" : rawTitle.toString();
        String artist = rawArtist == null || rawArtist.length() == 0
                ? (hasCurrent ? "YT" : "Your music will appear here") : rawArtist.toString();
        if (miniTitle != null) miniTitle.setText(title);
        boolean isPlaying = controller != null && controller.isPlaying();
        if (fullSaveButton != null && hasCurrent) {
            boolean saved = listening.isSaved(current.mediaId);
            fullSaveButton.setImageResource(saved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
            fullSaveButton.setColorFilter(saved ? LIME : MUTED);
            fullSaveButton.setContentDescription(saved ? "Current episode saved in Library" : "Save current episode in Library");
        }
        boolean wantsPlayback = controller != null && controller.getPlayWhenReady()
                && controller.getPlaybackState() != Player.STATE_ENDED && controller.getPlayerError() == null;
        boolean loading = hasCurrent && wantsPlayback && controller.getPlayerError() == null
                && (controller.getPlaybackState() == Player.STATE_IDLE || controller.getPlaybackState() == Player.STATE_BUFFERING);
        if (hasCurrent && current.mediaId.equals(startingTrackId) && isPlaying) {
            String startingMessage = "Starting " + startingTrackTitle + "…";
            if (startingMessage.equals(statusMessage)) setStatus("Playing " + title);
            startingTrackId = null;
            startingTrackTitle = null;
        }
        boolean failed = controller != null && controller.getPlayerError() != null;
        if (miniSubtitle != null) miniSubtitle.setText(failed ? "Could not play · " + artist : loading ? "Starting… · " + artist : artist);
        if (playerHint != null) playerHint.setText(failed ? "Could not play. Open Track options to retry."
                : loading ? "Starting…" : "Swipe artwork left or right to change queued songs");
        if (miniPlayer != null && !fullPlayer) miniPlayer.setVisibility(hasCurrent ? View.VISIBLE : View.GONE);
        if (miniPlayButton != null) {
            miniPlayButton.setImageResource(wantsPlayback ? R.drawable.ic_pause : R.drawable.ic_play);
            miniPlayButton.setColorFilter(LIME);
            miniPlayButton.setContentDescription(wantsPlayback ? "Pause current song" : "Play current song");
        }
        setTransportEnabled(miniNextButton, controller != null && controller.hasNextMediaItem());
        if (playerTitle != null) playerTitle.setText(hasCurrent ? title : "Nothing playing yet");
        if (playerArtist != null) playerArtist.setText(hasCurrent ? artist : "Choose a result to start listening.");
        if (fullPlayButton != null) {
            fullPlayButton.setImageResource(wantsPlayback ? R.drawable.ic_pause : R.drawable.ic_play);
            fullPlayButton.setContentDescription(wantsPlayback ? "Pause current song" : "Play current song");
        }
        setTransportEnabled(fullPreviousButton, controller != null && controller.hasPreviousMediaItem());
        setTransportEnabled(fullNextButton, controller != null && controller.hasNextMediaItem());
        Uri artUri = hasCurrent ? current.mediaMetadata.artworkUri : null;
        String artUrl = artUri == null ? "" : YouTubeRepository.safeArtworkUrl(artUri.toString());
        if (artUrl.isEmpty() && hasCurrent) {
            String id = current.mediaId;
            if (id != null && id.matches("[A-Za-z0-9_-]{11}")) artUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        }
        if (miniArtwork != null) artworkLoader.load(artUrl, miniArtwork);
        if (playerArtwork != null) artworkLoader.load(artUrl, playerArtwork);
        if (miniProgress != null && controller != null) {
            miniProgress.setIndeterminate(loading);
            miniProgress.setContentDescription(loading ? "Starting song" : "Playback progress");
            long duration = controller.getDuration();
            if (duration <= 0 || duration == C.TIME_UNSET) duration = hasCurrent && current.mediaMetadata.extras != null
                    ? current.mediaMetadata.extras.getLong("blackyt_duration_seconds", 0) * 1000L : 0;
            miniProgress.setProgress(duration <= 0 ? 0
                    : (int) Math.min(1000, Math.max(0, controller.getCurrentPosition()) * 1000 / duration));
        }
        if (controller != null && timeLabel != null && seekBar != null) {
            long duration = controller.getDuration();
            boolean canSeek = duration > 0 && duration != C.TIME_UNSET;
            if (!canSeek) duration = hasCurrent && current.mediaMetadata.extras != null
                    ? current.mediaMetadata.extras.getLong("blackyt_duration_seconds", 0) * 1000L : 0;
            long position = Math.max(0, controller.getCurrentPosition());
            timeLabel.setText(format(position / 1000) + "  /  " + format(duration / 1000));
            if (!seeking) seekBar.setProgress(duration <= 0 ? 0 : (int) Math.min(1000, position * 1000 / duration));
            seekBar.setEnabled(canSeek && current != null);
        }
        renderQueue();
    }

    private void renderQueue() {
        if (queueBox == null) return;
        StringBuilder key = new StringBuilder();
        if (controller != null) {
            key.append(controller.getCurrentMediaItemIndex()).append(':').append(controller.isPlaying()).append(':');
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                MediaItem entry = controller.getMediaItemAt(i);
                key.append(entry.mediaId).append(':').append(titleOf(entry)).append('|');
            }
        }
        if (key.toString().equals(renderedQueueKey) && queueBox.getChildCount() > 0) return;
        renderedQueueKey = key.toString();
        queueBox.removeAllViews();
        int count = controller == null ? 0 : controller.getMediaItemCount();
        if (queueSummary != null) queueSummary.setText(count + (count == 1 ? " song" : " songs") + " · plays in order");
        if (count == 0) {
            TextView empty = text("Your queue is empty. Search for a song and tap + to line it up here.", 15, MUTED, false);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(dp(4), dp(22), dp(4), dp(22));
            queueBox.addView(empty);
            return;
        }
        int current = controller == null ? -1 : controller.getCurrentMediaItemIndex();
        if (current >= 0 && current < count) {
            queueBox.addView(text("NOW PLAYING", 11, LIME, true), lp(-1, dp(36), 0, 0, 0, 2));
            queueBox.addView(queueRow(current, true), lp(-1, dp(64), 0, 0, 0, 12));
        }
        int firstUpcoming = current >= 0 ? current + 1 : 0;
        if (firstUpcoming < count) queueBox.addView(text("UP NEXT", 11, MUTED, true), lp(-1, dp(36), 0, 0, 0, 2));
        for (int i = firstUpcoming; i < count; i++) queueBox.addView(queueRow(i, false), lp(-1, dp(64), 0, 0, 0, 2));
    }

    private View queueRow(int index, boolean current) {
        MediaItem item = controller.getMediaItemAt(index);
        LinearLayout line = row();
        line.setPadding(dp(4), dp(4), dp(2), dp(4));
        line.setBackground(background(current ? PANEL : BG, 5));
        touchFeedback(line, 5);
        ImageView art = imageView(dp(48), "Artwork for " + titleOf(item));
        Uri uri = item.mediaMetadata.artworkUri;
        String url = uri == null ? fallbackArt(item.mediaId) : YouTubeRepository.safeArtworkUrl(uri.toString());
        artworkLoader.load(url, art);
        line.addView(art, lp(dp(48), dp(48), 0, 0, 10, 0));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(titleOf(item), 14, current ? LIME : CREAM, current);
        title.setMaxLines(1);
        names.addView(title);
        CharSequence artist = item.mediaMetadata.artist;
        names.addView(text(artist == null ? "YT" : artist.toString(), 12, MUTED, false), lp(-1, -2, 0, 3, 0, 0));
        line.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        line.setContentDescription((current ? "Current song, " : "Play ") + titleOf(item));
        line.setFocusable(true);
        line.setOnClickListener(v -> ifController(c -> {
            showPlaybackFeedback("Starting " + titleOf(item) + "…");
            c.seekTo(index, 0); c.prepare(); c.play(); updatePlayer();
        }));
        if (!current) {
            ImageButton remove = iconButton(R.drawable.ic_close, "Remove " + titleOf(item) + " from queue", MUTED);
            remove.setOnClickListener(v -> ifController(c -> {
                if (index < c.getMediaItemCount() && item.mediaId.equals(c.getMediaItemAt(index).mediaId)) c.removeMediaItem(index);
            }));
            line.addView(remove, lp(dp(48), dp(48), 0, 0, 0, 0));
        }
        return line;
    }

    private String fallbackArt(String id) {
        if (id != null && id.matches("[A-Za-z0-9_-]{11}")) return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        return "";
    }

    private String titleOf(MediaItem item) {
        CharSequence title = item.mediaMetadata.title;
        return title == null || title.length() == 0 ? "YT track" : title.toString();
    }

    private void persistMode(boolean music) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("music", music).apply();
    }

    private boolean sameRules(YouTubeRepository.Rules left, YouTubeRepository.Rules right) {
        return left != null && right != null && left.musicOnly == right.musicOnly
                && left.include.equals(right.include) && left.exclude.equals(right.exclude);
    }

    private void invalidateSearchForEdits() {
        String query = queryInput == null ? "" : queryInput.getText().toString().trim();
        YouTubeRepository.Rules rules = currentRules();
        boolean changed = activeSearch || nextCursor != null
                || (displayedQuery != null && (!displayedQuery.equals(query) || !sameRules(displayedRules, rules)));
        if (creatorMode) {
            searchGeneration++;
            if (searchTask != null) searchTask.cancel(true);
            activeSearch = false; creatorCursor = null; channelCursor = null;
            creatorPageUrl = null; creatorInfo = null; creatorResults.clear(); channelResults.clear();
            setSearching(false, "Search changed. Tap Search YT to find creators.");
            renderSearchResults(); return;
        }
        if (!changed) return;
        searchGeneration++;
        if (searchTask != null) searchTask.cancel(true);
        activeSearch = false;
        nextCursor = null;
        if (displayedQuery != null && (!displayedQuery.equals(query) || !sameRules(displayedRules, rules))) {
            clearSearchResults(false);
            hasSearched = false;
            renderSearchResults();
        }
        setSearching(false, "Search changed. Tap Search YT to update your results.");
    }

    private void attachSearchPersistence() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                queryText = s.toString();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("query", queryText).apply();
                invalidateSearchForEdits();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        queryInput.addTextChangedListener(watcher);
    }

    private void handleBack() {
        if (fullPlayer) { closeFullPlayer(); return; }
        if (currentTab == TAB_SEARCH && creatorPageUrl != null) {
            closeCreatorPage(); return;
        }
        if (currentTab != TAB_HOME) { showTab(TAB_HOME); return; }
        finish();
    }

    private void updateBackHandler() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean intercept = fullPlayer || currentTab != TAB_HOME;
        if (intercept && platformBackCallback == null)
            platformBackCallback = Api33Back.register(this, this::handleBack);
        else if (!intercept && platformBackCallback != null) {
            Api33Back.unregister(this, platformBackCallback);
            platformBackCallback = null;
        }
    }

    // Android 8-12 use this legacy callback. Android 13+ uses the platform dispatcher above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }

    @androidx.annotation.RequiresApi(33)
    private static final class Api33Back {
        static Object register(android.app.Activity activity, Runnable action) {
            android.window.OnBackInvokedCallback callback = action::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }
        static void unregister(android.app.Activity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) callback);
        }
    }

    private void clearResults() {
        searchGeneration++;
        if (searchTask != null) searchTask.cancel(true);
        activeSearch = false;
        hasSearched = false;
        clearSearchResults(true);
        renderSearchResults();
        setSearching(false, "Search for music, artists, or a YT link.");
    }

    private void clearSearchResults(boolean hide) {
        results.clear();
        resultRules.clear();
        nextCursor = null;
        displayedQuery = null;
        displayedRules = null;
        resultLimitReached = false;
        if (resultsBox != null) {
            resultsBox.removeAllViews();
            if (hide) resultsBox.setVisibility(View.GONE);
        }
    }

    private void setSearching(boolean value, String message) {
        searchLoading = value;
        if (searchProgress != null) searchProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        setStatus(message);
    }

    private void setStatus(String message) {
        statusMessage = message;
        if (status != null) status.setText(message);
    }

    private YouTubeRepository.Rules currentRules() {
        return new YouTubeRepository.Rules(musicMode && !creatorMode, includeFilter, excludeFilter);
    }

    private void readHistory() {
        history.clear();
        String encoded = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HISTORY, "[]");
        try {
            JSONArray items = new JSONArray(encoded);
            for (int i = 0; i < items.length() && history.size() < 12; i++) {
                JSONObject value = items.optJSONObject(i);
                if (value == null) continue;
                String id = value.optString("id", "");
                if (!id.matches("[A-Za-z0-9_-]{11}")) continue;
                String title = limited(value.optString("title", "YT track"), 300);
                String channel = limited(value.optString("channel", "YT"), 200);
                String art = YouTubeRepository.safeArtworkUrl(value.optString("art", ""));
                YouTubeRepository.Track track = new YouTubeRepository.Track(id, title, channel,
                        Math.max(0, value.optLong("duration", 0)), art, value.optString("creator", ""));
                YouTubeRepository.Rules rules = new YouTubeRepository.Rules(value.optBoolean("music", true),
                        limited(value.optString("include", ""), 1000), limited(value.optString("exclude", ""), 1000));
                history.add(new RecentTrack(track, rules));
            }
        } catch (Exception ignored) {
            history.clear();
        }
    }

    private void rememberCurrent() {
        if (controller == null || controller.getCurrentMediaItem() == null) return;
        MediaItem item = controller.getCurrentMediaItem();
        String id = item.mediaId;
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return;
        if (id.equals(suppressHistoryIdAfterClear)) return;
        String title = titleOf(item);
        CharSequence artistValue = item.mediaMetadata.artist;
        String artist = artistValue == null ? "YT" : artistValue.toString();
        long duration = item.mediaMetadata.extras == null ? 0
                : item.mediaMetadata.extras.getLong("blackyt_duration_seconds", 0);
        String art = item.mediaMetadata.artworkUri == null ? fallbackArt(id)
                : YouTubeRepository.safeArtworkUrl(item.mediaMetadata.artworkUri.toString());
        YouTubeRepository.Rules rules;
        try { rules = NativeMediaItems.parse(item).rules; }
        catch (Exception ignored) { rules = new YouTubeRepository.Rules(musicMode, includeFilter, excludeFilter); }
        rememberTrack(new YouTubeRepository.Track(id, limited(title, 300), limited(artist, 200), duration, art,
                item.mediaMetadata.extras == null ? "" : item.mediaMetadata.extras.getString("blackyt_creator_url", "")), rules);
    }

    private void clearListeningHistory() {
        history.clear();
        lastRememberedId = "";
        MediaItem current = controller == null ? null : controller.getCurrentMediaItem();
        suppressHistoryIdAfterClear = current == null || current.mediaId == null ? "" : current.mediaId;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HISTORY, "[]").apply();
        if (currentTab == TAB_HOME && !fullPlayer) showTab(TAB_HOME);
        Toast.makeText(this, "Listening history cleared", Toast.LENGTH_SHORT).show();
    }

    private void rememberTrack(YouTubeRepository.Track track, YouTubeRepository.Rules rules) {
        if (track == null || track.id == null || !track.id.matches("[A-Za-z0-9_-]{11}")) return;
        if (track.id.equals(lastRememberedId) && !history.isEmpty()
                && history.get(0).track.id.equals(track.id)) return;
        lastRememberedId = track.id;
        for (int i = history.size() - 1; i >= 0; i--) if (history.get(i).track.id.equals(track.id)) history.remove(i);
        YouTubeRepository.Track safeTrack = new YouTubeRepository.Track(track.id,
                limited(track.title, 300), limited(track.channel, 200), Math.max(0, track.durationSeconds),
                YouTubeRepository.safeArtworkUrl(track.artworkUrl), track.creatorUrl);
        YouTubeRepository.Rules original = rules == null ? currentRules() : rules;
        YouTubeRepository.Rules safeRules = new YouTubeRepository.Rules(original.musicOnly,
                limited(original.include, 1000), limited(original.exclude, 1000));
        history.add(0, new RecentTrack(safeTrack, safeRules));
        while (history.size() > 12) history.remove(history.size() - 1);
        JSONArray items = new JSONArray();
        for (RecentTrack recent : history) {
            try {
                JSONObject value = new JSONObject();
                value.put("id", recent.track.id);
                value.put("title", recent.track.title);
                value.put("channel", recent.track.channel);
                value.put("duration", recent.track.durationSeconds);
                value.put("art", YouTubeRepository.safeArtworkUrl(recent.track.artworkUrl));
                value.put("creator", recent.track.creatorUrl);
                value.put("music", recent.rules.musicOnly);
                value.put("include", recent.rules.include);
                value.put("exclude", recent.rules.exclude);
                items.put(value);
            } catch (Exception ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HISTORY, items.toString()).apply();
    }

    private String limited(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private void rememberSearch(String value) {
        if (value.startsWith("https://") || value.matches("[A-Za-z0-9_-]{11}")) return;
        List<String> all = getRecents();
        all.remove(value);
        all.add(0, value);
        while (all.size() > 8) all.remove(all.size() - 1);
        saveRecents(all);
    }

    private List<String> getRecents() {
        String encoded = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_RECENTS, "");
        List<String> values = new ArrayList<>();
        if (!encoded.isEmpty()) for (String item : encoded.split("\n")) if (!item.trim().isEmpty()) values.add(item);
        return values;
    }

    private void saveRecents(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (value == null) continue;
            String clean = value.replace('\n', ' ').trim();
            if (clean.isEmpty()) continue;
            if (joined.length() > 0) joined.append('\n');
            joined.append(clean);
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_RECENTS, joined.toString()).apply();
    }

    private void showAbout() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("About BlackYT")
                .setMessage("BlackYT is an educational listening prototype. Use it only where you have permission, and respect copyright, licences, and the law.")
                .setPositiveButton("Got it", null)
                .show();
    }

    private void togglePlayback() {
        ifController(c -> {
            // Buffering still means Play was requested; a tap should pause immediately.
            if (c.getPlayWhenReady() && c.getPlaybackState() != Player.STATE_ENDED && c.getPlayerError() == null) c.pause();
            else {
                if (c.getPlaybackState() == Player.STATE_ENDED) c.seekToDefaultPosition();
                if (c.getPlaybackState() == Player.STATE_IDLE || c.getPlaybackState() == Player.STATE_ENDED || c.getPlayerError() != null) c.prepare();
                c.play();
            }
            updatePlayer();
        });
    }

    private void setTransportEnabled(ImageButton button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private void showPlaybackFeedback(String message) {
        setStatus(message);
        if (playbackFeedback != null) playbackFeedback.cancel();
        playbackFeedback = Toast.makeText(this, message, Toast.LENGTH_SHORT);
        playbackFeedback.show();
    }

    private void skipQueuedSong(boolean next) {
        if (controller == null) { showPlaybackFeedback("Connecting to the player…"); return; }
        boolean available = next ? controller.hasNextMediaItem() : controller.hasPreviousMediaItem();
        int command = next ? Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM : Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM;
        if (!available || !controller.isCommandAvailable(command)) {
            showPlaybackFeedback(next ? "End of queue. Add another song in Search."
                    : "This is the first song in your queue.");
            return;
        }
        int index = controller.getCurrentMediaItemIndex() + (next ? 1 : -1);
        String title = titleOf(controller.getMediaItemAt(index));
        if (next) controller.seekToNextMediaItem(); else controller.seekToPreviousMediaItem();
        showPlaybackFeedback((next ? "Next: " : "Previous: ") + title);
        updatePlayer();
    }

    /** Gestures start only on artwork or the miniplayer surface, never transport/seek controls. */
    private void installQueueSwipe(View surface, boolean tapOpensPlayer) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        surface.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            long startTime;
            boolean moved, cancelled;
            private void releaseScroll(View view) {
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                view.setPressed(false);
            }
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX(); startY = event.getY(); startTime = event.getEventTime();
                        moved = false; cancelled = false;
                        // Decide the axis before the enclosing ScrollView steals a horizontal drag.
                        if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                        if (tapOpensPlayer) view.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        cancelled = true; releaseScroll(view); return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(event.getX() - startX), dy = Math.abs(event.getY() - startY);
                        if (dx > slop || dy > slop) { moved = true; view.setPressed(false); }
                        if (dy > slop && dy >= dx) { cancelled = true; releaseScroll(view); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        float distance = event.getX() - startX;
                        float vertical = Math.abs(event.getY() - startY);
                        boolean swipe = !cancelled && Math.abs(distance) >= Math.max(dp(48), view.getWidth() * 0.18f)
                                && Math.abs(distance) > vertical * 1.5f && event.getEventTime() - startTime <= 1500;
                        releaseScroll(view);
                        if (swipe) skipQueuedSong(distance < 0);
                        else if (tapOpensPlayer && !moved && !cancelled) view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelled = true; releaseScroll(view); return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void ifController(java.util.function.Consumer<MediaController> action) {
        if (controller == null) { setStatus("Connecting to the player…"); return; }
        action.accept(controller);
    }

    private void hideKeyboard(View target) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(target.getWindowToken(), 0);
        target.clearFocus();
    }

    private ScrollView pageScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BG);
        return scroll;
    }

    private LinearLayout pageColumn(ScrollView scroll) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(20));
        scroll.addView(root);
        return root;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private EditText input(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setTextColor(CREAM);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(background(BG, 15));
        field.setSelectAllOnFocus(false);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setContentDescription(hint);
        return field;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(9), 0, dp(9), 0);
        b.setTextColor(primary ? BG : CREAM);
        b.setBackground(background(primary ? LIME : BG, 14));
        b.setStateListAnimator(null);
        touchFeedback(b, 14);
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private ImageView iconView(int drawable, String description, int tint) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setColorFilter(tint);
        icon.setContentDescription(description);
        return icon;
    }

    private ImageButton iconButton(int drawable, String description, int tint) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawable);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setColorFilter(tint);
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setBackground(background(Color.TRANSPARENT, 24));
        touchFeedback(button, 24);
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        return button;
    }

    private ProgressBar progressBar() {
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(LIME));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(LIME));
        progress.setContentDescription("Playback progress");
        return progress;
    }

    private ImageView imageView(int size, String description) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(background(PANEL_LIGHT, 14));
        image.setContentDescription(description);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return image;
    }

    private void touchFeedback(View view, int radius) {
        // Foreground preserves artwork/selected colours while showing native press feedback.
        view.setForeground(new RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(55, 255, 255, 255)),
                null, background(Color.WHITE, radius)));
        view.setFocusable(true);
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private LinearLayout.LayoutParams lp(int widthPx, int heightPx, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(widthPx, heightPx);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String format(long seconds) {
        seconds = Math.max(0, seconds);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
    private void safeLog(String event) { android.util.Log.i("BlackYT", event); }

    private void maybePlayProbe() {
        if (pendingProbeId == null || controller == null) return;
        for (YouTubeRepository.Track track : results) {
            if (pendingProbeId.equals(track.id)) {
                pendingProbeId = null;
                playTrack(track);
                return;
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleIncomingLink(intent)) return;
        if (BuildConfig.DEBUG && intent.hasExtra("probe_query")) {
            showTab(TAB_SEARCH);
            queryInput.setText(intent.getStringExtra("probe_query"));
            search();
        } else if (BuildConfig.DEBUG && intent.hasExtra("probe_video")) {
            String id = intent.getStringExtra("probe_video");
            if (id != null && id.matches("[A-Za-z0-9_-]{11}")) {
                showTab(TAB_SEARCH);
                pendingProbeId = id;
                queryInput.setText("https://www.youtube.com/watch?v=" + id);
                search();
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    @Override protected void onPause() {
        main.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        searchGeneration++;
        if (searchTask != null) searchTask.cancel(true);
        main.removeCallbacksAndMessages(null);
        if (playbackFeedback != null) playbackFeedback.cancel();
        if (controller != null) controller.removeListener(playerListener);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        searches.shutdownNow();
        activeSearch = false;
        artworkLoader.close();
        if (Build.VERSION.SDK_INT >= 33 && platformBackCallback != null) {
            Api33Back.unregister(this, platformBackCallback);
            platformBackCallback = null;
        }
        super.onDestroy();
    }
    private void enterSearch(String query, boolean musicOnly, boolean creators) {
        creatorMode = creators;
        musicMode = musicOnly;
        creatorPageUrl = null;
        creatorInfo = null;
        creatorResults.clear();
        channelResults.clear();
        creatorCursor = null;
        channelCursor = null;
        includeFilter = ""; excludeFilter = "";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("music", musicOnly)
                .putBoolean("creator_mode", creators).putString("include", "").putString("exclude", "").apply();
        showTab(TAB_SEARCH);
        queryInput.setText(query);
        search();
    }

    private void switchSearchMode(boolean musicOnly, boolean creators) {
        creatorMode = creators; musicMode = musicOnly;
        creatorPageUrl = null; creatorInfo = null;
        creatorResults.clear(); channelResults.clear();
        creatorCursor = null; channelCursor = null;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("music", musicOnly)
                .putBoolean("creator_mode", creators).apply();
        clearResults();
        showTab(TAB_SEARCH);
        setStatus(creators ? "Find a creator, then browse and save their episodes."
                : musicOnly ? "Search for music." : "Search talks, podcasts, interviews, or any YT video.");
    }

    private boolean handleIncomingLink(Intent intent) {
        if (intent == null) return false;
        String incoming = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) incoming = text.toString();
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            incoming = intent.getData().toString();
        }
        if (incoming == null) return false;
        try {
            YtLink.Result link = YtLink.parse(incoming);
            if (!link.isVideo()) { openCreator(link.channelUrl); return true; }
            enterSearch(link.canonicalUrl, false, false);
            showPlaybackFeedback("Shared link ready. Choose Play or Save; playback has not started.");
        } catch (IllegalArgumentException invalid) {
            showPlaybackFeedback("Share a public YT video or creator link with BlackYT.");
        }
        return true;
    }

    private View buildLibraryPage() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageColumn(scroll);
        root.addView(text("Your Library", 26, CREAM, true), lp(-1, dp(52), 0, 0, 0, 4));
        root.addView(text("Saved on this phone. Return to an episode where you left off.", 13, MUTED, false), lp(-1, -2, 0, 0, 0, 14));
        List<ListeningStore.SavedEpisode> episodes = listening.listEpisodes();
        root.addView(text("Saved episodes · " + episodes.size(), 18, CREAM, true), lp(-1, -2, 0, 0, 0, 8));
        if (episodes.isEmpty()) {
            root.addView(text("Tap the bookmark beside a search result or in Now Playing. Saved items are links, not audio downloads.", 14, MUTED, false), lp(-1, -2, 0, 0, 0, 12));
            Button discover = button("Find talks & podcasts", true);
            discover.setContentDescription("Discover spoken episodes");
            discover.setOnClickListener(v -> enterSearch("podcast interview", false, false));
            root.addView(discover, lp(-1, dp(48), 0, 0, 0, 16));
        }
        for (ListeningStore.SavedEpisode episode : episodes) {
            long resume = listening.resumePositionMs(episode.track.id);
            LinearLayout line = row(); touchFeedback(line, 6);
            ImageView art = imageView(dp(56), "Saved artwork for " + episode.track.title);
            artworkLoader.load(episode.track.artworkUrl, art);
            line.addView(art, lp(dp(56), dp(56), 0, 0, 10, 0));
            LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(episode.track.title, 14, CREAM, true); title.setMaxLines(2);
            words.addView(title);
            TextView subtitle = text((resume > 0 ? "Resume " + format(resume / 1000) : "Play from beginning")
                    + " · " + episode.track.channel, 12, resume > 0 ? LIME : MUTED, false);
            subtitle.setMaxLines(1); words.addView(subtitle, lp(-1, -2, 0, 3, 0, 0));
            line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton more = iconButton(R.drawable.ic_more, "Saved episode options for " + episode.track.title, MUTED);
            more.setOnClickListener(v -> {
                android.widget.PopupMenu menu = new android.widget.PopupMenu(this, more);
                menu.getMenu().add("Play from beginning").setOnMenuItemClickListener(i -> { playTrack(episode.track, episode.rules, 0); return true; });
                menu.getMenu().add("Add to queue").setOnMenuItemClickListener(i -> { addToQueue(episode.track, episode.rules); return true; });
                if (!episode.track.creatorUrl.isEmpty()) menu.getMenu().add("Browse creator").setOnMenuItemClickListener(i -> { openCreator(episode.track.creatorUrl); return true; });
                menu.getMenu().add("Remove from Library").setOnMenuItemClickListener(i -> {
                    listening.removeEpisode(episode.track.id); showTab(TAB_LIBRARY); return true;
                });
                menu.show();
            });
            line.addView(more, lp(dp(48), dp(48), 2, 0, 0, 0));
            line.setContentDescription("Resume saved episode " + episode.track.title);
            line.setOnClickListener(v -> { playTrack(episode.track, episode.rules, resume); openFullPlayer(); });
            root.addView(line, lp(-1, dp(88), 0, 0, 0, 4));
        }
        List<ListeningStore.SavedCreator> savedCreators = listening.listCreators();
        root.addView(text("Saved creators · " + savedCreators.size(), 18, CREAM, true), lp(-1, -2, 0, 22, 0, 8));
        if (savedCreators.isEmpty()) root.addView(text("Find a creator in Search and save their page here. Open it to fetch their latest videos.", 14, MUTED, false), lp(-1, -2, 0, 0, 0, 12));
        for (ListeningStore.SavedCreator creator : savedCreators) {
            LinearLayout line = row(); touchFeedback(line, 6);
            ImageView art = imageView(dp(48), "Creator artwork for " + creator.name); artworkLoader.load(creator.artworkUrl, art);
            line.addView(art, lp(dp(48), dp(48), 0, 0, 10, 0));
            TextView name = text(creator.name, 15, CREAM, true); name.setMaxLines(2);
            line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            ImageButton remove = iconButton(R.drawable.ic_close, "Remove saved creator " + creator.name, MUTED);
            remove.setOnClickListener(v -> { listening.removeCreator(creator.url); showTab(TAB_LIBRARY); });
            line.addView(remove, lp(dp(48), dp(48), 0, 0, 0, 0));
            line.setContentDescription("Open saved creator " + creator.name);
            line.setOnClickListener(v -> openCreator(creator.url));
            root.addView(line, lp(-1, dp(72), 0, 0, 0, 4));
        }
        return scroll;
    }

    private long positionFor(YouTubeRepository.Track track) {
        if (controller != null && controller.getCurrentMediaItem() != null
                && track.id.equals(controller.getCurrentMediaItem().mediaId)) return Math.max(0, controller.getCurrentPosition());
        return track.id.equals(sharedVideoId) ? sharedStartMs : 0;
    }

    private void saveEpisode(YouTubeRepository.Track track, YouTubeRepository.Rules rules, ImageButton source) {
        try {
            listening.saveEpisode(track, rules, positionFor(track));
            if (source != null) { source.setImageResource(R.drawable.ic_bookmark_filled); source.setColorFilter(LIME);
                source.setContentDescription("Saved " + track.title + " in Library"); }
            showPlaybackFeedback("Saved to Your Library");
            updatePlayer();
        } catch (IllegalArgumentException full) { showPlaybackFeedback(full.getMessage()); }
    }

    private YouTubeRepository.Track currentTrack() {
        if (controller == null || controller.getCurrentMediaItem() == null) return null;
        MediaItem item = controller.getCurrentMediaItem();
        return new YouTubeRepository.Track(item.mediaId, titleOf(item),
                item.mediaMetadata.artist == null ? "YT" : item.mediaMetadata.artist.toString(),
                item.mediaMetadata.extras == null ? 0 : item.mediaMetadata.extras.getLong("blackyt_duration_seconds", 0),
                item.mediaMetadata.artworkUri == null ? "" : item.mediaMetadata.artworkUri.toString(),
                item.mediaMetadata.extras == null ? "" : item.mediaMetadata.extras.getString("blackyt_creator_url", ""));
    }

    private void saveCurrentEpisode() {
        YouTubeRepository.Track track = currentTrack(); if (track == null) { showPlaybackFeedback("Choose an episode first."); return; }
        YouTubeRepository.Rules rules;
        try { rules = NativeMediaItems.parse(controller.getCurrentMediaItem()).rules; }
        catch (Exception invalid) { rules = new YouTubeRepository.Rules(false, "", ""); }
        saveEpisode(track, rules, fullSaveButton);
    }

    private void showExploreEditor() {
        List<ListeningStore.ExploreTile> tiles = listening.listTiles();
        String[] names = new String[tiles.size() + 1]; names[0] = "+ Add a topic";
        for (int i = 0; i < tiles.size(); i++) names[i + 1] = tiles.get(i).title;
        new android.app.AlertDialog.Builder(this).setTitle("Edit Explore")
                .setItems(names, (dialog, which) -> {
                    if (which == 0) { editExploreTile(null); return; }
                    ListeningStore.ExploreTile tile = tiles.get(which - 1);
                    new android.app.AlertDialog.Builder(this).setTitle(tile.title)
                            .setItems(new String[]{"Edit topic", "Move up", "Move down", "Remove topic"}, (d, action) -> {
                                if (action == 0) { editExploreTile(tile); return; }
                                if (action == 1) listening.moveTile(tile.id, -1);
                                else if (action == 2) listening.moveTile(tile.id, 1);
                                else listening.removeTile(tile.id);
                                showTab(TAB_HOME); showExploreEditor();
                            }).show();
                }).setNeutralButton("Restore defaults", (dialog, which) -> new android.app.AlertDialog.Builder(this)
                        .setTitle("Restore Explore topics?").setMessage("This replaces your topic list with the starter topics.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Restore", (d, w) -> { listening.resetTiles(); showTab(TAB_HOME); }).show())
                .setNegativeButton("Done", null).show();
    }

    private void editExploreTile(ListeningStore.ExploreTile existing) {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(20), dp(8), dp(20), 0);
        EditText title = input("Topic name"); title.setContentDescription("Explore topic name");
        EditText query = input("What should this topic search for?"); query.setContentDescription("Explore search phrase");
        android.widget.CheckBox music = new android.widget.CheckBox(this); music.setText("Music results only"); music.setTextColor(CREAM);
        music.setContentDescription("Explore music results only");
        if (existing != null) { title.setText(existing.title); query.setText(existing.query); music.setChecked(existing.musicOnly); }
        fields.addView(title, lp(-1, dp(52), 0, 0, 0, 8)); fields.addView(query, lp(-1, dp(52), 0, 0, 0, 8)); fields.addView(music);
        fields.addView(text("Leave Music off for spoken videos, talks or podcasts.", 12, MUTED, false), lp(-1, -2, 0, 6, 0, 6));
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle(existing == null ? "Add Explore topic" : "Edit Explore topic")
                .setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            if (title.getText().toString().trim().isEmpty() || query.getText().toString().trim().isEmpty()) {
                title.setError("Enter a name and a search phrase"); return;
            }
            try {
                int[] colors = {0xff326660, 0xff7A4D34, 0xff75414B, 0xff4E527A, 0xff486044, 0xff87652E};
                listening.putTile(new ListeningStore.ExploreTile(existing == null ? java.util.UUID.randomUUID().toString() : existing.id,
                        title.getText().toString().trim(), query.getText().toString().trim(), music.isChecked(),
                        existing == null ? colors[listening.listTiles().size() % colors.length] : existing.color));
                dialog.dismiss(); showTab(TAB_HOME);
            } catch (IllegalArgumentException error) { query.setError(error.getMessage()); }
        }));
        dialog.show();
    }

    private void searchCreators(boolean more) {
        final String query = queryInput == null ? queryText.trim() : queryInput.getText().toString().trim();
        if (query.isEmpty()) { setStatus("Enter a creator or show name."); return; }
        final long generation = ++searchGeneration;
        if (searchTask != null) searchTask.cancel(true);
        if (!more) { creatorResults.clear(); creatorCursor = null; creatorPageUrl = null; }
        final CreatorRepository.CreatorCursor cursor = more ? creatorCursor : null;
        activeSearch = true; hasSearched = true; rememberSearch(query);
        setSearching(true, more ? "Loading more creators…" : "Finding creators…"); renderSearchResults();
        searchTask = searches.submit(() -> {
            try {
                CreatorRepository.CreatorPage page = CreatorRepository.get().searchCreators(query, cursor);
                main.post(() -> {
                    if (destroyed || generation != searchGeneration) return;
                    for (CreatorRepository.Creator creator : page.creators) {
                        boolean duplicate = false;
                        for (CreatorRepository.Creator prior : creatorResults) if (prior.url.equals(creator.url)) { duplicate = true; break; }
                        if (!duplicate && creatorResults.size() < SEARCH_LIMIT) creatorResults.add(creator);
                    }
                    creatorCursor = page.next; activeSearch = false;
                    setSearching(false, creatorResults.isEmpty() ? "No creators found. Try their name or YT channel link." : creatorResults.size() + " creators · open one to browse episodes");
                    renderSearchResults();
                });
            } catch (Exception error) { main.post(() -> {
                if (destroyed || generation != searchGeneration) return;
                activeSearch = false; setSearching(false, "Could not load creators. Check your connection and retry."); renderSearchResults();
            }); }
        });
    }

    private void closeCreatorPage() {
        searchGeneration++;
        if (searchTask != null) searchTask.cancel(true);
        activeSearch = false; creatorPageUrl = null; creatorInfo = null;
        channelResults.clear(); channelCursor = null;
        setSearching(false, "Find a creator or choose a result below.");
        renderSearchResults();
    }

    private void openCreator(String url) {
        searchGeneration++;
        if (searchTask != null) searchTask.cancel(true);
        activeSearch = false;
        try { creatorPageUrl = CreatorRepository.canonicalChannelUrl(url); }
        catch (Exception invalid) { showPlaybackFeedback("This creator link is not supported."); return; }
        creatorMode = true; musicMode = false; creatorInfo = null; channelResults.clear(); channelCursor = null;
        showTab(TAB_SEARCH); loadCreatorPage(false);
    }

    private void loadCreatorPage(boolean more) {
        if (creatorPageUrl == null || activeSearch) return;
        final String url = creatorPageUrl;
        final long generation = ++searchGeneration;
        final CreatorRepository.ChannelCursor cursor = more ? channelCursor : null;
        if (searchTask != null) searchTask.cancel(true);
        activeSearch = true; setSearching(true, more ? "Loading more episodes…" : "Opening creator…");
        searchTask = searches.submit(() -> {
            try {
                CreatorRepository.ChannelPage page = CreatorRepository.get().channel(url, cursor);
                main.post(() -> {
                    if (destroyed || generation != searchGeneration || !url.equals(creatorPageUrl)) return;
                    creatorInfo = page.creator;
                    for (YouTubeRepository.Track track : page.tracks) {
                        boolean duplicate = false;
                        for (YouTubeRepository.Track prior : channelResults) if (prior.id.equals(track.id)) { duplicate = true; break; }
                        if (!duplicate && channelResults.size() < SEARCH_LIMIT) channelResults.add(track);
                        resultRules.put(track.id, new YouTubeRepository.Rules(false, "", ""));
                    }
                    channelCursor = page.next; activeSearch = false;
                    setSearching(false, channelResults.size() + " episodes · save a bookmark to return later"); renderSearchResults();
                });
            } catch (Exception error) { main.post(() -> {
                if (destroyed || generation != searchGeneration) return;
                activeSearch = false; setSearching(false, "Could not open this creator. Check your connection and retry."); renderSearchResults();
            }); }
        });
    }

    private void renderCreatorResults() {
        if (creatorPageUrl != null) {
            Button back = button("Back to creator search", false);
            back.setContentDescription("Back to creator search");
            back.setOnClickListener(v -> closeCreatorPage());
            resultsBox.addView(back, lp(-1, dp(48), 0, 0, 0, 12));
            if (creatorInfo != null) {
                LinearLayout header = row();
                ImageView art = imageView(dp(64), "Creator artwork for " + creatorInfo.name); artworkLoader.load(creatorInfo.artworkUrl, art);
                header.addView(art, lp(dp(64), dp(64), 0, 0, 12, 0));
                TextView name = text(creatorInfo.name, 20, CREAM, true); name.setMaxLines(2);
                header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                boolean saved = listening.isCreatorSaved(creatorInfo.url);
                ImageButton save = iconButton(saved ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark,
                        (saved ? "Saved creator " : "Save creator ") + creatorInfo.name, saved ? LIME : CREAM);
                save.setOnClickListener(v -> {
                    try { listening.saveCreator(creatorInfo.url, creatorInfo.name, creatorInfo.artworkUrl); renderSearchResults(); showPlaybackFeedback("Creator saved to Your Library"); }
                    catch (IllegalArgumentException full) { showPlaybackFeedback(full.getMessage()); }
                });
                header.addView(save, lp(dp(48), dp(48), 0, 0, 0, 0));
                resultsBox.addView(header, lp(-1, -2, 0, 0, 0, 10));
                if (!creatorInfo.description.isEmpty()) {
                    TextView description = text(creatorInfo.description.replaceAll("<[^>]*>", ""), 12, MUTED, false); description.setMaxLines(3);
                    resultsBox.addView(description, lp(-1, -2, 0, 0, 0, 12));
                }
            }
            for (YouTubeRepository.Track track : channelResults) resultsBox.addView(buildCompactResult(track), lp(-1, dp(72), 0, 0, 0, 2));
            if (!activeSearch && (channelCursor != null || creatorInfo == null)) {
                Button more = button(creatorInfo == null ? "Retry creator" : "Load more episodes", false);
                more.setContentDescription(creatorInfo == null ? "Retry creator" : "Load more creator episodes");
                more.setOnClickListener(v -> loadCreatorPage(creatorInfo != null)); resultsBox.addView(more, lp(-1, dp(48), 0, 12, 0, 0));
            }
            return;
        }
        for (CreatorRepository.Creator creator : creatorResults) {
            LinearLayout line = row(); touchFeedback(line, 6);
            ImageView art = imageView(dp(56), "Creator artwork for " + creator.name); artworkLoader.load(creator.artworkUrl, art);
            line.addView(art, lp(dp(56), dp(56), 0, 0, 12, 0));
            LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(creator.name, 16, CREAM, true); name.setMaxLines(1); words.addView(name);
            TextView detail = text(creator.description.isEmpty() ? "Open creator and browse episodes" : creator.description, 12, MUTED, false);
            detail.setMaxLines(2); words.addView(detail);
            line.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(iconView(R.drawable.ic_next, "Open creator", MUTED), lp(dp(24), dp(24), 8, 0, 0, 0));
            line.setContentDescription("Open creator " + creator.name); line.setOnClickListener(v -> openCreator(creator.url));
            resultsBox.addView(line, lp(-1, dp(88), 0, 0, 0, 4));
        }
        if (!activeSearch && creatorCursor != null && creatorResults.size() < SEARCH_LIMIT) {
            Button more = button("Load more creators", false); more.setContentDescription("Load more creators");
            more.setOnClickListener(v -> searchCreators(true)); resultsBox.addView(more, lp(-1, dp(48), 0, 12, 0, 0));
        }
        if (!activeSearch && creatorResults.isEmpty()) {
            resultsBox.addView(text("Search a creator or show name, such as Wes Roth. Open a creator to browse their episodes and save the ones you want.", 14, MUTED, false), lp(-1, -2, 0, 8, 0, 8));
            renderSearchRecents(resultsBox);
        }
    }

}
