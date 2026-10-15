// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Native search and playback controls for the app-owned MediaSessionService. */
@androidx.media3.common.util.UnstableApi
public final class MainActivity extends android.app.Activity {
    private static final String PREFS = "blackyt_filters";
    private static final int BG = Color.rgb(16, 38, 37);
    private static final int PANEL = Color.rgb(25, 52, 50);
    private static final int CREAM = Color.rgb(243, 240, 232);
    private static final int MUTED = Color.rgb(178, 193, 179);
    private static final int LIME = Color.rgb(210, 239, 145);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<YouTubeRepository.Track> results = new ArrayList<>();
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private EditText queryInput, includeInput, excludeInput;
    private CheckBox musicOnly;
    private LinearLayout resultsBox, queueBox;
    private TextView status, nowTitle, nowChannel, timeLabel, playButton;
    private SeekBar seekBar;
    private ProgressBar searchProgress;
    private boolean seeking;
    private String renderedQueueKey = "";
    private boolean destroyed;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!destroyed) { updatePlayer(); main.postDelayed(this, 1000); }
        }
    };
    private long searchGeneration;
    private String pendingProbeId;

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Diagnostics.init(getApplicationContext());
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        connectController();
        Intent launch = getIntent();
        if (BuildConfig.DEBUG && launch != null) {
            String probeVideo = launch.getStringExtra("probe_video");
            String probeQuery = launch.getStringExtra("probe_query");
            if (probeVideo != null && probeVideo.matches("[A-Za-z0-9_-]{11}")) {
                pendingProbeId = probeVideo;
                queryInput.setText("https://www.youtube.com/watch?v=" + probeVideo);
                search();
            } else if (probeQuery != null && !probeQuery.trim().isEmpty()) {
                queryInput.setText(probeQuery);
                main.postDelayed(this::search, 350);
            }
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(20));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(20), top + dp(12), dp(20), bottom + dp(20));
            return insets;
        });

        TextView brand = text("BLACKYT  ·  LISTEN YOUR WAY", 12, LIME, true);
        brand.setLetterSpacing(.08f);
        root.addView(brand, lp(-1, -2, 0, 0, 0, 8));
        root.addView(text("Find your next\nfavourite.", 31, CREAM, true), lp(-1, -2, 0, 0, 0, 16));
        root.addView(text("Search YouTube or paste a video link. Play starts now; Add queues it after songs already lined up.",
                15, MUTED, false), lp(-1, -2, 0, 0, 0, 18));

        LinearLayout searchCard = panel(root);
        searchCard.addView(label("SEARCH", 11));
        queryInput = input("Song, artist, or YouTube link");
        searchCard.addView(queryInput, lp(-1, dp(54), 0, 8, 0, 10));
        musicOnly = new CheckBox(this);
        musicOnly.setText("Music results only");
        musicOnly.setTextColor(CREAM);
        musicOnly.setTextSize(15);
        musicOnly.setButtonTintList(android.content.res.ColorStateList.valueOf(LIME));
        searchCard.addView(musicOnly, lp(-1, -2, 0, 0, 0, 6));
        includeInput = input("Include any words (comma separated)");
        searchCard.addView(includeInput, lp(-1, dp(50), 0, 0, 0, 8));
        excludeInput = input("Exclude any words (comma separated)");
        searchCard.addView(excludeInput, lp(-1, dp(50), 0, 0, 0, 12));
        LinearLayout searchActions = row();
        Button searchButton = button("Search", true);
        searchButton.setOnClickListener(v -> search());
        searchActions.addView(searchButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        searchProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        searchProgress.setIndeterminate(true);
        searchProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(LIME));
        searchProgress.setVisibility(View.GONE);
        searchActions.addView(searchProgress, new LinearLayout.LayoutParams(dp(34), dp(34)));
        searchCard.addView(searchActions);
        status = text("Ready when you are.", 13, MUTED, false);
        searchCard.addView(status, lp(-1, -2, 0, 8, 0, 0));
        restoreFilters();
        attachFilterPersistence();

        resultsBox = panel(root);
        resultsBox.setVisibility(View.GONE);
        resultsBox.addView(label("MATCHES", 11));

        LinearLayout playerCard = panel(root);
        playerCard.addView(label("NOW PLAYING", 11));
        nowTitle = text("Nothing playing yet", 19, CREAM, true);
        playerCard.addView(nowTitle, lp(-1, -2, 0, 8, 0, 3));
        nowChannel = text("Choose a result to start listening.", 13, MUTED, false);
        playerCard.addView(nowChannel, lp(-1, -2, 0, 0, 0, 7));
        timeLabel = text("0:00  /  0:00", 12, MUTED, false);
        playerCard.addView(timeLabel, lp(-1, -2, 0, 0, 0, 0));
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(LIME));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(LIME));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) { seeking = fromUser; }
            @Override public void onStartTrackingTouch(SeekBar b) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar b) {
                seeking = false;
                if (controller != null && controller.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
                    long duration = controller.getDuration();
                    if (duration > 0) controller.seekTo((duration * b.getProgress()) / 1000L);
                }
                updatePlayer();
            }
        });
        playerCard.addView(seekBar, lp(-1, dp(45), -8, 0, -8, 4));
        LinearLayout controls = row();
        Button previous = button("Previous", false);
        previous.setOnClickListener(v -> ifController(c -> c.seekToPreviousMediaItem()));
        Button stop = button("Stop", false);
        stop.setOnClickListener(v -> ifController(c -> c.stop()));
        playButton = text("▶", 26, LIME, true);
        playButton.setGravity(Gravity.CENTER);
        playButton.setContentDescription("Play or pause");
        playButton.setBackground(background(LIME, 28));
        playButton.setTextColor(BG);
        playButton.setOnClickListener(v -> {
            ifController(c -> { if (c.isPlaying()) c.pause(); else { c.prepare(); c.play(); } });
        });
        Button next = button("Next", false);
        next.setOnClickListener(v -> ifController(c -> c.seekToNextMediaItem()));
        addWeight(controls, previous, 1); addWeight(controls, stop, 1);
        controls.addView(playButton, new LinearLayout.LayoutParams(dp(56), dp(52)));
        addWeight(controls, next, 1);
        playerCard.addView(controls);
        Button retry = button("Retry current song", false);
        retry.setOnClickListener(v -> retryCurrent());
        playerCard.addView(retry, lp(-1, dp(46), 0, 10, 0, 0));

        LinearLayout queueCard = panel(root);
        LinearLayout queueHeading = row();
        queueHeading.addView(label("UP NEXT", 11), new LinearLayout.LayoutParams(0, -2, 1));
        queueHeading.addView(text("FIFO queue · Add queues it.", 11, MUTED, false));
        queueCard.addView(queueHeading);
        queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        queueCard.addView(queueBox, lp(-1, -2, 0, 6, 0, 0));

        root.addView(searchCard, lp(-1, -2, 0, 0, 0, 12));
        root.addView(playerCard, lp(-1, -2, 0, 0, 0, 12));
        root.addView(queueCard, lp(-1, -2, 0, 0, 0, 12));
        root.addView(resultsBox, lp(-1, -2, 0, 0, 0, 0));
        setContentView(scroll);
        main.post(this::updatePlayer);
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
                if (status != null) status.setText("Player connection failed. Reopen the app to retry.");
            }
        }, main::post);
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onEvents(Player player, Player.Events events) { updatePlayer(); }
        @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) { updatePlayer(); }
        @Override public void onPlayerError(PlaybackException error) {
            safeLog("player_error_" + error.errorCode);
            if (status != null) status.setText("Could not play this song. Check your connection or retry.");
        }
    };

    private void search() {
        final long generation = ++searchGeneration;
        String query = queryInput.getText().toString().trim();
        if (query.isEmpty()) { status.setText("Enter a song, artist, or YouTube link."); return; }
        YouTubeRepository.Rules rules = currentRules();
        setSearching(true, "Searching YouTube…");
        io.execute(() -> {
            try {
                List<YouTubeRepository.Track> found = new ArrayList<>();
                if (looksLikeUrl(query)) {
                    String id = YouTubeRepository.normalizeVideoId(query);
                    YouTubeRepository.Resolved resolved = YouTubeRepository.get().resolve(id, rules, false);
                    found.add(new YouTubeRepository.Track(id, resolved.title, resolved.channel,
                            resolved.durationSeconds));
                } else {
                    found.addAll(YouTubeRepository.get().search(query, rules));
                }
                main.post(() -> {
                    if (generation != searchGeneration) return;
                    results.clear(); results.addAll(found);
                    showResults();
                    setSearching(false, found.isEmpty() ? "No matches. Try different words or filters." :
                            found.size() + (found.size() == 1 ? " result" : " results") + " · Play starts now; Add queues after songs already lined up.");
                    maybePlayProbe();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (generation != searchGeneration) return;
                    results.clear(); showResults(); setSearching(false,
                            failure.getMessage() == null ? "Search failed. Check your connection and retry." : failure.getMessage());
                });
            }
        });
    }

    private boolean looksLikeUrl(String value) {
        return value.startsWith("https://") || value.matches("[A-Za-z0-9_-]{11}");
    }

    private YouTubeRepository.Rules currentRules() {
        return new YouTubeRepository.Rules(musicOnly.isChecked(), includeInput.getText().toString(),
                excludeInput.getText().toString());
    }

    private void showResults() {
        resultsBox.removeViews(1, Math.max(0, resultsBox.getChildCount() - 1));
        resultsBox.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);
        for (YouTubeRepository.Track track : results) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(11), dp(12), dp(10));
            card.setBackground(background(BG, 14));
            TextView title = text(track.title, 16, CREAM, true);
            title.setMaxLines(2);
            card.addView(title);
            card.addView(text(track.channel + "  ·  " + format(track.durationSeconds), 12, MUTED, false),
                    lp(-1, -2, 0, 4, 0, 9));
            LinearLayout actions = row();
            Button play = button("Play", true);
            play.setOnClickListener(v -> playTrack(track));
            Button add = button("Add to queue", false);
            add.setOnClickListener(v -> addNext(track));
            addWeight(actions, play, 1); addWeight(actions, add, 1);
            card.addView(actions);
            resultsBox.addView(card, lp(-1, -2, 0, 10, 0, 0));
        }
    }

    private void playTrack(YouTubeRepository.Track track) {
        if (controller == null) { status.setText("Connecting to the player… try again in a moment."); return; }
        MediaItem item = NativeMediaItems.fromTrack(track, currentRules());
        int insertAt = controller.getMediaItemCount() == 0 ? 0 : Math.max(0, controller.getCurrentMediaItemIndex() + 1);
        controller.addMediaItem(insertAt, item);
        controller.seekTo(insertAt, 0);
        controller.prepare();
        controller.play();
        safeLog("play_selected");
    }

    private void addNext(YouTubeRepository.Track track) {
        if (controller == null) { status.setText("Connecting to the player… try again in a moment."); return; }
        MediaItem item = NativeMediaItems.fromTrack(track, currentRules());
        int count = controller.getMediaItemCount();
        controller.addMediaItem(item);
        status.setText(count == 0 ? "Added to the queue. Press Play to start it." : "Added to the end of the queue. Current playback continues.");
        safeLog("queue_add");
        updatePlayer();
    }

    private void retryCurrent() {
        if (controller == null || controller.getCurrentMediaItem() == null) {
            status.setText("There is no current song to retry."); return;
        }
        MediaItem item = controller.getCurrentMediaItem();
        String id = item.mediaId;
        boolean resume = controller.getPlayWhenReady();
        io.execute(() -> {
            YouTubeRepository.get().invalidate(id);
            main.post(() -> {
                if (controller == null || controller.getCurrentMediaItem() == null
                        || !id.equals(controller.getCurrentMediaItem().mediaId)) return;
                controller.seekTo(0);
                controller.prepare();
                if (resume) controller.play();
                status.setText("Refreshing the stream and retrying…");
            });
        });
    }

    private void updatePlayer() {
        if (controller == null || nowTitle == null) return;
        MediaItem current = controller.getCurrentMediaItem();
        if (current == null) {
            nowTitle.setText("Nothing playing yet"); nowChannel.setText("Choose a result to start listening.");
        } else {
            CharSequence title = current.mediaMetadata.title;
            CharSequence artist = current.mediaMetadata.artist;
            nowTitle.setText(title == null || title.length() == 0 ? "YouTube track" : title);
            nowChannel.setText(artist == null ? "" : artist);
        }
        boolean isPlaying = controller.isPlaying();
        playButton.setText(isPlaying ? "Ⅱ" : "▶");
        playButton.setContentDescription(isPlaying ? "Pause" : "Play");
        long duration = controller.getDuration();
        if (duration < 0 || duration == C.TIME_UNSET) duration = 0;
        long position = Math.max(0, controller.getCurrentPosition());
        timeLabel.setText(format(position / 1000) + "  /  " + format(duration / 1000));
        if (!seeking) seekBar.setProgress(duration <= 0 ? 0 : (int) Math.min(1000, position * 1000 / duration));
        seekBar.setEnabled(duration > 0 && current != null);
        renderQueue();
    }

    private void renderQueue() {
        StringBuilder key = new StringBuilder();
        if (controller != null) {
            key.append(controller.getCurrentMediaItemIndex()).append(':');
            for (int i=0; i<controller.getMediaItemCount(); i++) {
                MediaItem entry = controller.getMediaItemAt(i);
                key.append(entry.mediaId).append(':').append(titleOf(entry)).append('|');
            }
        }
        if (key.toString().equals(renderedQueueKey) && queueBox.getChildCount()>0) return;
        renderedQueueKey = key.toString();
        queueBox.removeAllViews();
        if (controller == null || controller.getMediaItemCount() == 0) {
            queueBox.addView(text("Your queue is empty. Add to queue lines up a song; Play starts it.", 13, MUTED, false));
            return;
        }
        int current = controller.getCurrentMediaItemIndex();
        for (int i = 0; i < controller.getMediaItemCount(); i++) {
            MediaItem item = controller.getMediaItemAt(i);
            LinearLayout line = row();
            TextView title = text((i == current ? "▶  " : "") + titleOf(item), 14,
                    i == current ? LIME : CREAM, i == current);
            title.setMaxLines(2);
            line.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
            if (i != current) {
                final int index = i;
                Button remove = button("Remove", false);
                remove.setMinWidth(dp(76));
                remove.setOnClickListener(v -> { ifController(c -> c.removeMediaItem(index)); });
                line.addView(remove, new LinearLayout.LayoutParams(dp(92), dp(42)));
            }
            queueBox.addView(line, lp(-1, -2, 0, 4, 0, 0));
        }
    }

    private String titleOf(MediaItem item) {
        CharSequence title = item.mediaMetadata.title;
        return title == null || title.length() == 0 ? "YouTube track" : title.toString();
    }

    private void setSearching(boolean value, String message) {
        searchProgress.setVisibility(value ? View.VISIBLE : View.GONE);
        status.setText(message);
    }

    private void restoreFilters() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        queryInput.setText(p.getString("query", ""));
        musicOnly.setChecked(p.getBoolean("music", true));
        includeInput.setText(p.getString("include", ""));
        excludeInput.setText(p.getString("exclude", ""));
    }

    private void attachFilterPersistence() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("query", queryInput.getText().toString())
                        .putString("include", includeInput.getText().toString())
                        .putString("exclude", excludeInput.getText().toString()).apply();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        queryInput.addTextChangedListener(watcher); includeInput.addTextChangedListener(watcher);
        excludeInput.addTextChangedListener(watcher);
        musicOnly.setOnCheckedChangeListener((button, checked) -> getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean("music", checked).apply());
    }

    private void ifController(java.util.function.Consumer<MediaController> action) {
        if (controller == null) { if (status != null) status.setText("Connecting to the player…"); return; }
        action.accept(controller);
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackground(background(PANEL, 20));
        return box;
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL); line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private TextView label(String value, int size) {
        TextView label = text(value, size, LIME, true); label.setLetterSpacing(.07f); return label;
    }

    private EditText input(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true); field.setTextSize(15); field.setTextColor(CREAM);
        field.setHintTextColor(MUTED); field.setHint(hint); field.setPadding(dp(13), 0, dp(13), 0);
        field.setBackground(background(BG, 13));
        field.setSelectAllOnFocus(false);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        return field;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this);
        b.setText(value); b.setTextSize(14); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setMinHeight(dp(44)); b.setPadding(dp(10), 0, dp(10), 0);
        b.setTextColor(primary ? BG : CREAM); b.setBackground(background(primary ? LIME : BG, 14));
        b.setStateListAnimator(null);
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }

    private LinearLayout.LayoutParams lp(int widthPx, int heightPx, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(widthPx, heightPx);
        p.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return p;
    }

    private void addWeight(LinearLayout row, View view, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), weight);
        p.setMargins(dp(2), 0, dp(2), 0); row.addView(view, p);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String format(long seconds) {
        seconds = Math.max(0, seconds); return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
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
        super.onNewIntent(intent); setIntent(intent);
        if (BuildConfig.DEBUG && intent.hasExtra("probe_query")) {
            queryInput.setText(intent.getStringExtra("probe_query")); search();
        } else if (BuildConfig.DEBUG && intent.hasExtra("probe_video")) {
            String id = intent.getStringExtra("probe_video");
            if (id != null && id.matches("[A-Za-z0-9_-]{11}")) {
                pendingProbeId = id;
                queryInput.setText("https://www.youtube.com/watch?v=" + id);
                search();
            }
        }
    }

    @Override protected void onResume() {
        super.onResume(); main.removeCallbacks(ticker); main.post(ticker);
    }
    @Override protected void onPause() {
        main.removeCallbacks(ticker); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null);
        if (controller != null) controller.removeListener(playerListener);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        io.shutdownNow();
        super.onDestroy();
    }
}
