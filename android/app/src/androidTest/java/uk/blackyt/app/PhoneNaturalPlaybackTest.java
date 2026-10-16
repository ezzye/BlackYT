// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Opt-in real-network device checks. Playback is muted in this app only, never globally. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class PhoneNaturalPlaybackTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void liveSearchHasArtworkFilteringAndMoreResults() throws Exception {
        Diagnostics.init(context);
        YouTubeRepository repo = YouTubeRepository.get();
        YouTubeRepository.Rules music = new YouTubeRepository.Rules(true, "", "");
        YouTubeRepository.SearchPage songs = repo.searchPage("U2 With Or Without You", music, null);
        assertFalse("Music search must return real playable candidates", songs.tracks.isEmpty());
        Set<String> ids = new HashSet<>();
        for (YouTubeRepository.Track t : songs.tracks) {
            assertTrue("Page contains duplicate IDs", ids.add(t.id));
            assertFalse(t.title.isEmpty());
            assertFalse(YouTubeRepository.safeArtworkUrl(t.artworkUrl).isEmpty());
        }
        assertTrue("Search must contain the requested U2 song", songs.tracks.stream().anyMatch(t ->
                (t.title+" "+t.channel).toLowerCase(java.util.Locale.ROOT).contains("without you")));
        YouTubeRepository.SearchPage artist = repo.searchPage("Blackstreet", music, null);
        assertFalse("An eleven-character artist name must return search results", artist.tracks.isEmpty());
        assertTrue(artist.tracks.stream().anyMatch(t -> (t.title+" "+t.channel).toLowerCase(java.util.Locale.ROOT).contains("blackstreet")));
        YouTubeRepository.Track first = songs.tracks.get(0);
        AtomicReference<ArtworkLoader> loader = new AtomicReference<>();
        android.widget.ImageView image = main(() -> new android.widget.ImageView(context));
        AtomicReference<android.graphics.drawable.Drawable> placeholder = new AtomicReference<>();
        main(() -> {
            loader.set(new ArtworkLoader(context));
            loader.get().load(first.artworkUrl, image);
            placeholder.set(image.getDrawable());
            return true;
        });
        try {
            await(() -> main(() -> image.getDrawable() != placeholder.get()), new AtomicReference<>(), 20000);
            assertTrue(main(() -> image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable));
        } finally { main(() -> {loader.get().close(); return true;}); }
        YouTubeRepository.SearchPage link = repo.searchPage("https://youtu.be/"+first.id, music, null);
        assertEquals(first.id, link.tracks.get(0).id);
        YouTubeRepository.SearchPage empty = repo.searchPage("U2", new YouTubeRepository.Rules(true,
                "blackyt_unmatchable_filter_768157", ""), null);
        assertTrue("Include filter must be applied", empty.tracks.isEmpty());
        YouTubeRepository.Rules videos = new YouTubeRepository.Rules(false, "", "reaction, cover");
        YouTubeRepository.SearchPage page = repo.searchPage("jazz improvisation", videos, null);
        assertFalse("Broader video search must return candidates", page.tracks.isEmpty());
        for (YouTubeRepository.Track t : page.tracks) assertTrue(YouTubeRepository.matches(t.title,t.channel,videos));
        boolean more = page.next != null;
        if (more) {
            YouTubeRepository.SearchPage next = repo.searchPage("jazz improvisation", videos, page.next);
            assertNotNull(next.tracks);
            try { repo.searchPage("different query", videos, page.next); fail("Stale cursor must be rejected"); }
            catch (java.io.IOException expected) { }
            try { repo.searchPage("jazz improvisation", new YouTubeRepository.Rules(false,"live",""), page.next); fail("Changed-filter cursor must be rejected"); }
            catch (java.io.IOException expected) { }
        }
        Diagnostics.event("device-search-check", "music="+songs.tracks.size()+" videos="+page.tracks.size()+" more="+more);
    }

    @Test public void screenOffPlaybackReachesNaturalEndAndAdvances() throws Exception {
        Diagnostics.init(context);
        AtomicReference<ListenableFuture<MediaController>> future = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> future.set(
                new MediaController.Builder(context, new SessionToken(context,
                        new ComponentName(context,PlaybackService.class))).buildAsync()));
        MediaController c = future.get().get(25,TimeUnit.SECONDS);
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        AtomicInteger transitionReason = new AtomicInteger(-1);
        try {
            YouTubeRepository repo = YouTubeRepository.get();
            YouTubeRepository.Rules music = new YouTubeRepository.Rules(true,"","");
            List<YouTubeRepository.Track> a = repo.searchPage("Prince When Doves Cry",music,null).tracks;
            List<YouTubeRepository.Track> b = repo.searchPage("U2 With Or Without You",music,null).tracks;
            assertFalse(a.isEmpty());assertFalse(b.isEmpty());
            List<MediaItem> queue = new ArrayList<>();
            YouTubeRepository.Track firstLongSong = null;
            for (YouTubeRepository.Track candidate : a) {
                if (candidate.durationSeconds >= 180 && candidate.durationSeconds <= 360) {
                    if (firstLongSong == null || candidate.durationSeconds < firstLongSong.durationSeconds)
                        firstLongSong = candidate;
                }
            }
            assertNotNull("Search needs a song longer than the two-minute sample", firstLongSong);
            List<YouTubeRepository.Track> firstCandidates = new ArrayList<>();
            firstCandidates.add(firstLongSong);
            for(YouTubeRepository.Track candidate:a)if(candidate!=firstLongSong&&candidate.durationSeconds>=180&&candidate.durationSeconds<=360)firstCandidates.add(candidate);
            YouTubeRepository.Track availableFirst=DeviceStreamChecks.available(firstCandidates,music);
            YouTubeRepository.Track availableNext=DeviceStreamChecks.available(b,music);
            queue.add(NativeMediaItems.fromTrack(availableFirst,music));
            queue.add(NativeMediaItems.fromTrack(availableNext,music));
            android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if ("true".equals(InstrumentationRegistry.getArguments().getString("expect_screen_off"))) {
                await(() -> keyguard.isKeyguardLocked() && !power.isInteractive(),error,60000);
            }
            main(() -> {
                assertTrue("App volume must be controllable before silent testing",c.isCommandAvailable(Player.COMMAND_SET_VOLUME));
                c.setVolume(0f);
                assertEquals(0f,c.getVolume(),0f);
                c.addListener(new Player.Listener(){
                    @Override public void onPlayerError(PlaybackException e){error.set(e);}
                    @Override public void onMediaItemTransition(MediaItem item,int reason){transitionReason.set(reason);}
                });
                c.setMediaItems(queue);c.prepare();c.play();return true;
            });
            await(() -> main(() -> c.isPlaying() && c.getCurrentPosition()>1500),error,60000);
            assertNotNull(main(() -> c.getMediaMetadata().artworkUri));
            long start = main(c::getCurrentPosition);
            long until = SystemClock.elapsedRealtime()+120000;
            boolean lockedObserved = false;
            boolean screenOffObserved = false;
            boolean continuouslyLocked = true;
            boolean continuouslyScreenOff = true;
            while(SystemClock.elapsedRealtime()<until){
                assertNull("Playback error during background sample",error.get());
                lockedObserved |= keyguard.isKeyguardLocked();
                screenOffObserved |= !power.isInteractive();
                continuouslyLocked &= keyguard.isKeyguardLocked();
                continuouslyScreenOff &= !power.isInteractive();
                SystemClock.sleep(1000);
            }
            if ("true".equals(InstrumentationRegistry.getArguments().getString("expect_screen_off"))) {
                assertTrue("Expected to observe the existing screen lock", lockedObserved);
                assertTrue("Expected playback with the display off", screenOffObserved);
                assertTrue("Phone must stay locked for the complete two-minute sample", continuouslyLocked);
                assertTrue("Display must stay off for the complete two-minute sample", continuouslyScreenOff);
            }
            assertTrue("First song must remain playing",main(c::isPlaying));
            assertTrue("Playback position must advance while UI is absent",main(c::getCurrentPosition)>start+105000);
            long duration=main(c::getDuration);assertTrue("Progressive audio needs a finite duration",duration>5000);
            long naturalEndTimeout = Math.max(60000, duration - main(c::getCurrentPosition) + 60000);
            await(() -> main(() -> c.getCurrentMediaItemIndex()==1 && c.isPlaying() && c.getCurrentPosition()>1500),error,naturalEndTimeout);
            boolean lockedAtNext = keyguard.isKeyguardLocked();
            boolean displayOffAtNext = !power.isInteractive();
            assertTrue("Screen must still be locked when the next song starts",lockedAtNext);
            assertTrue("Display must still be off when the next song starts",displayOffAtNext);
            assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,transitionReason.get());
            assertEquals(availableNext.id,main(() -> c.getCurrentMediaItem().mediaId));
            assertEquals(2,(int)main(c::getMediaItemCount));
            android.net.ConnectivityManager connectivity = context.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities caps = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            String transport = caps == null ? "unknown" : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" : caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "wifi" : "other";
            String evidence = "silent=true minimumSampleMs=120000 naturalCompletion=true firstDurationMs="+duration+" automaticNext=true keyguardObserved="+lockedObserved+" screenOffObserved="+screenOffObserved+" continuouslyLocked="+continuouslyLocked+" continuouslyScreenOff="+continuouslyScreenOff+" lockedAtNext="+lockedAtNext+" displayOffAtNext="+displayOffAtNext+" transport="+transport;
            Diagnostics.event("device-playback-check", evidence);
            android.os.Bundle status = new android.os.Bundle();
            status.putString("blackyt_evidence", evidence);
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        } finally {
            main(() -> {c.stop();c.clearMediaItems();c.setVolume(1f);c.release();return true;});
        }
    }

    private static <T> T main(Supplier<T> action){
        AtomicReference<T> value=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> value.set(action.get()));
        return value.get();
    }
    private static void await(Supplier<Boolean> condition,AtomicReference<PlaybackException> error,long timeout){
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until){
            assertNull("Native playback reported an error",error.get());
            if(condition.get())return;
            SystemClock.sleep(250);
        }
        fail("Timed out waiting for native playback state");
    }
}
