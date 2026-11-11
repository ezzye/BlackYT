// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real service, cache and decoder; offline network changes are restored even on failure. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class AutoContinuationDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final YouTubeRepository.Rules rules = new YouTubeRepository.Rules(false,"autoplay fixture","");
    @Test public void naturalEndAndOfflineGapsContinueWithScreenOff() throws Exception {
        JSONObject result = new JSONObject().put("status","running").put("silent",true).put("fixtures","cached PCM WAV");
        String wifi = shell("settings get global wifi_on").trim();
        String data = shell("settings get global mobile_data").trim();
        PowerManager power = context.getSystemService(PowerManager.class);
        boolean interactive = power.isInteractive();
        PlaybackPreparation prep = PlaybackPreparation.get(context);
        int mode = prep.mode();
        CachedAudioFixtures fixtures = new CachedAudioFixtures(context);
        MediaController player = null;
        Float volume = null;
        try {
            if(mode == PlaybackPreparation.OFF) prep.setMode(PlaybackPreparation.UNMETERED);
            AtomicReference<ListenableFuture<MediaController>> future = new AtomicReference<>();
            main(() -> { future.set(new MediaController.Builder(context,new SessionToken(context,
                    new ComponentName(context,PlaybackService.class))).buildAsync());return null; });
            player = future.get().get(25, TimeUnit.SECONDS);
            final MediaController c = player;
            volume = main(c::getVolume);
            main(() -> { c.stop();c.clearMediaItems();c.setVolume(0f);return null; });
            YouTubeRepository.Track first = track("BLAuto00001",4);
            YouTubeRepository.Track missing = track("BLAuto00002",8);
            YouTubeRepository.Track prefix = track("BLAuto00003",8);
            YouTubeRepository.Track next = track("BLAuto00004",12);
            fixtures.seed(first,rules,4);fixtures.seed(prefix,rules,8,256);fixtures.seed(next,rules,12);
            AtomicInteger reason = new AtomicInteger(-1);
            main(() -> { c.addListener(new Player.Listener() {
                @Override public void onMediaItemTransition(MediaItem item,int r){reason.set(r);}
            });c.setMediaItems(Arrays.asList(item(first),item(next)));c.prepare();c.play();return null; });
            await(() -> main(() -> c.getCurrentMediaItemIndex()==1 && c.isPlaying() && c.getCurrentPosition()>500),20000,"Natural EOF did not continue");
            assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,reason.get());
            result.put("naturalEndWithoutSeeking",true).put("autoTransitionReason",reason.get());
            main(() -> { c.stop();c.clearMediaItems();return null; });
            shell("svc wifi disable");shell("svc data disable");
            await(() -> !online(),20000,"Device did not go offline");
            shell("input keyevent 223");
            await(() -> !power.isInteractive(),5000,"Display did not turn off");
            main(() -> { c.setMediaItems(Arrays.asList(item(first),item(missing),item(prefix),item(next)));
                c.prepare();c.play();return null; });
            await(() -> main(() -> c.getCurrentMediaItemIndex()==0 && c.isPlaying()),10000,"First cached item did not start offline");
            await(() -> main(() -> c.getCurrentMediaItemIndex()==3 && c.isPlaying() && c.getCurrentPosition()>500),25000,"Offline gaps did not lead to next complete cached item");
            assertFalse(online());assertFalse(power.isInteractive());
            assertEquals(4,(int)main(c::getMediaItemCount));
            assertEquals(next.id,main(() -> c.getCurrentMediaItem().mediaId));
            result.put("offlineNaturalEndSkippedMissingAndPartial",true).put("queuePreserved",true).put("screenOffAtSuccessor",true);
            main(() -> { c.stop();c.setMediaItems(Arrays.asList(item(missing),item(prefix),item(next)));c.prepare();c.play();return null; });
            await(() -> main(() -> c.getCurrentMediaItemIndex()==2 && c.isPlaying() && c.getCurrentPosition()>500),20000,"Starting on an uncached offline item did not skip forward");
            result.put("offlineStartSkipsUnavailable",true);
            main(() -> { c.stop();c.setMediaItems(Arrays.asList(item(missing),item(next)));c.prepare();c.pause();return null; });
            SystemClock.sleep(1500);
            assertEquals(0,(int)main(c::getCurrentMediaItemIndex));assertFalse(main(c::getPlayWhenReady));
            result.put("pauseDoesNotAutoplay",true);
            main(() -> { c.setMediaItems(Arrays.asList(item(missing),item(next)));c.prepare();c.play();c.stop();return null; });
            SystemClock.sleep(1500);
            assertEquals(Player.STATE_IDLE,(int)main(c::getPlaybackState));
            assertEquals(0,(int)main(c::getCurrentMediaItemIndex));assertFalse(main(c::isPlaying));
            result.put("stopDoesNotRestart",true);
            main(() -> { c.stop();c.setMediaItems(Collections.singletonList(item(missing)));c.prepare();c.play();return null; });
            SystemClock.sleep(2500);
            assertEquals(1,(int)main(c::getMediaItemCount));assertEquals(missing.id,main(() -> c.getCurrentMediaItem().mediaId));
            assertTrue(main(c::getPlayWhenReady));assertFalse(main(c::isPlaying));
            result.put("noCacheRetainsQueueAndWaits",true);
            main(() -> { c.stop();c.pause();List<MediaItem> large = new ArrayList<>();
                for(int i=0;i<ListeningStore.MAX_EPISODES;i++)large.add(item(first));c.setMediaItems(large);return null; });
            await(() -> main(() -> c.getMediaItemCount()==ListeningStore.MAX_EPISODES),10000,"Full saved-episode collection rejected");
            result.put("queueLimit",ListeningStore.MAX_EPISODES).put("status","passed");
        } catch(Throwable failure) {
            result.put("status","failed").put("failure",failure.getClass().getSimpleName()).put("message",failure.getMessage());
            throw failure;
        } finally {
            if(player!=null){final MediaController c=player;final float restore=volume==null?1f:volume;
                main(() -> {c.stop();c.clearMediaItems();c.setVolume(restore);c.release();return null;});}
            fixtures.close();
            if(mode!=prep.mode())prep.setMode(mode);
            shell("svc wifi " + ("1".equals(wifi)?"enable":"disable"));
            shell("svc data " + ("1".equals(data)?"enable":"disable"));
            if(interactive)shell("input keyevent 224");
            result.put("wifiSettingRestored",wifi.equals(shell("settings get global wifi_on").trim()))
                    .put("dataSettingRestored",data.equals(shell("settings get global mobile_data").trim()));
            File dir=context.getExternalFilesDir("auto-continuation-check");assertNotNull(dir);assertTrue(dir.isDirectory()||dir.mkdirs());
            Files.write(new File(dir,"service-result.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private MediaItem item(YouTubeRepository.Track t){return NativeMediaItems.fromTrack(t,rules);}
    private static YouTubeRepository.Track track(String id,long seconds){return new YouTubeRepository.Track(id,"Autoplay fixture","Test",seconds);}
    private boolean online(){ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);NetworkCapabilities cap=cm.getNetworkCapabilities(cm.getActiveNetwork());return cap!=null&&cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);}
    private static String shell(String command)throws Exception{try(ParcelFileDescriptor p=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);FileInputStream in=new FileInputStream(p.getFileDescriptor())){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private static <T>T main(Supplier<T> action){AtomicReference<T> value=new AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->value.set(action.get()));return value.get();}
    private static void await(Supplier<Boolean> condition,long timeout,String message){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.get())return;SystemClock.sleep(100);}throw new AssertionError(message);}
}
