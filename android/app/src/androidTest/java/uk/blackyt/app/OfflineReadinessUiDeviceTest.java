// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real queue bindings, drawn only from this app; no synthetic audio is played. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class OfflineReadinessUiDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private Activity activity;
    private final YouTubeRepository.Rules rules = new YouTubeRepository.Rules(false, "", "");
    private static final String FORMAT = "instrumentation-offline-readiness";

    @Test public void queueReflectsCompletePartialAndRemovedCache() throws Exception {
        String prefix = UUID.randomUUID().toString().replace("-", "");
        String full = prefix.substring(0, 11), partial = prefix.substring(11, 22), missing = prefix.substring(21, 32);
        AudioPrefixCache cache = AudioPrefixCache.get(context);
        PlaybackPreparation preparation = PlaybackPreparation.get(context);
        java.lang.reflect.Field busyField = PlaybackPreparation.class.getDeclaredField("playbackBusy");
        busyField.setAccessible(true);
        boolean originalBusy = busyField.getBoolean(preparation);
        AtomicReference<ListenableFuture<MediaController>> future = new AtomicReference<>();
        main(() -> { future.set(new MediaController.Builder(context, new SessionToken(context,
                new ComponentName(context, PlaybackService.class))).buildAsync()); return true; });
        MediaController player = future.get().get(25, TimeUnit.SECONDS);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        List<MediaItem> original = main(() -> { List<MediaItem> items = new ArrayList<>();
            for (int i = 0; i < player.getMediaItemCount(); i++) items.add(player.getMediaItemAt(i)); return items; });
        int originalIndex = main(player::getCurrentMediaItemIndex);
        long originalPosition = main(player::getCurrentPosition);
        float originalVolume = main(player::getVolume);
        try {
            preparation.setPlaybackBusy(true);
            seed(cache, full, 512); seed(cache, partial, 128);
            main(() -> { player.setVolume(0f); player.stop(); player.clearMediaItems();
                player.setMediaItems(java.util.Arrays.asList(item(full, "Readiness complete"),
                        item(partial, "Readiness partial"), item(missing, "Readiness absent"))); return true; });
            preparation.setPlaybackBusy(true);
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            activity = monitor.waitForActivityWithTimeout(12000);
            assertNotNull("Own activity must be created without unlocking the device", activity);
            await(() -> main(() -> clickIfPresent("Queue tab")), 10000, "Queue tab did not appear");
            await(() -> main(() -> rowHas("Readiness complete", "Ready offline")
                    && rowHas("Readiness partial", "Part saved")), 12000, "Queue badges did not reflect cache state");
            assertFalse("Uncached item must not claim a saved state", main(() -> rowHas("Readiness absent", "Ready offline")
                    || rowHas("Readiness absent", "Part saved")));
            assertEquals("Reading readiness must not change the current item", full, main(() -> player.getCurrentMediaItem().mediaId));
            capture();
            cache.invalidate(AudioPrefixCache.keyForFormat(full, rules, FORMAT));
            await(() -> main(() -> !rowHas("Readiness complete", "Ready offline")
                    && !rowHas("Readiness complete", "Part saved")
                    && rowHas("Readiness partial", "Part saved")), 12000, "Removed cache badge did not refresh");
            assertEquals(full, main(() -> player.getCurrentMediaItem().mediaId));
        } finally {
            instrumentation.removeMonitor(monitor);
            if (activity != null) main(() -> { activity.finish(); return true; });
            main(() -> { player.stop(); player.clearMediaItems();
                if (!original.isEmpty()) player.setMediaItems(original, Math.max(0, Math.min(originalIndex, original.size() - 1)), Math.max(0, originalPosition));
                player.setVolume(originalVolume); player.release(); return true; });
            cache.invalidate(AudioPrefixCache.keyForFormat(full, rules, FORMAT));
            cache.invalidate(AudioPrefixCache.keyForFormat(partial, rules, FORMAT));
            preparation.setPlaybackBusy(originalBusy);
        }
    }
    private MediaItem item(String id, String title) {
        return NativeMediaItems.fromTrack(new YouTubeRepository.Track(id, title, "Test artist", 0), rules);
    }
    private void seed(AudioPrefixCache cache, String id, int bytes) throws Exception {
        assertTrue(cache.remember(id, rules, FORMAT));
        cache.rememberLength(id, rules, FORMAT, 512);
        String key = AudioPrefixCache.keyForFormat(id, rules, FORMAT);
        DataSource source = cache.factory(() -> new ByteArrayDataSource(new byte[512])).createDataSource();
        try {
            source.open(new DataSpec.Builder().setUri(Uri.parse("https://example.invalid/" + id)).setKey(key).setLength(bytes).build());
            byte[] buffer = new byte[128]; while (source.read(buffer, 0, buffer.length) != -1) { }
        } finally { source.close(); }
    }
    private boolean clickIfPresent(String description) {
        for (View view : views()) if (description.equals(String.valueOf(view.getContentDescription())) && view.isClickable()) {
            view.performClick(); return true;
        }
        return false;
    }
    private boolean rowHas(String title, String badge) {
        for (View view : views()) {
            String desc = String.valueOf(view.getContentDescription());
            if ((desc.startsWith("Current song, " + title) || desc.startsWith("Play " + title)) && view instanceof ViewGroup) {
                List<View> children = new ArrayList<>(); collect(view, children);
                for (View child : children) {
                    if (child == view) continue; // The row's own title is not evidence of an indicator.
                    CharSequence description = child.getContentDescription();
                    CharSequence tooltip = android.os.Build.VERSION.SDK_INT >= 26 ? child.getTooltipText() : null;
                    if ((description != null && description.toString().contains(badge))
                            || (tooltip != null && tooltip.toString().contains(badge))) return true;
                }
            }
        }
        return false;
    }
    private List<View> views() { List<View> list = new ArrayList<>(); collect(activity.getWindow().getDecorView(), list); return list; }
    private static void collect(View view, List<View> list) {
        list.add(view); if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), list);
    }
    private void capture() throws Exception {
        Bitmap bitmap = main(() -> {
            View content = activity.findViewById(android.R.id.content);
            int width = content.getWidth(), height = content.getHeight();
            assertTrue(width > 0 && height > 0);
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(content.getLeft(), content.getTop(), content.getLeft() + width, content.getTop() + height);
            Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); content.draw(new Canvas(image)); return image;
        });
        File dir = context.getExternalFilesDir("episodes-check"); assertNotNull(dir); assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, "offline-readiness-queue.png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { bitmap.recycle(); }
    }
    private <T> T main(Supplier<T> action) { AtomicReference<T> result = new AtomicReference<>(); instrumentation.runOnMainSync(() -> result.set(action.get())); return result.get(); }
    private static void await(Supplier<Boolean> condition, long timeout, String message) {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < until) { if (condition.get()) return; SystemClock.sleep(200); }
        throw new AssertionError(message);
    }
}
