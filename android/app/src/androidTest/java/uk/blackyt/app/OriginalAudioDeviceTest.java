// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Opt-in real-network regression: original speech, normal speed, locked playback and queue. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class OriginalAudioDeviceTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();

    @Test public void originalSpeechAtNormalSpeedWhileLockedThenNext() throws Exception {
        Diagnostics.init(context);
        YouTubeRepository repo=YouTubeRepository.get();
        String id="EmaXeEdlJkI";
        StreamInfo info=StreamInfo.getInfo(NewPipe.getService(0),"https://www.youtube.com/watch?v="+id);
        assertTrue("Fixture needs a dubbed alternative",info.getAudioStreams().stream().anyMatch(a->a.getAudioTrackType()==AudioTrackType.DUBBED));
        AudioStream selected=AudioStreamSelector.select(info.getAudioStreams());
        assertNotNull(selected);
        assertEquals(AudioTrackType.ORIGINAL,selected.getAudioTrackType());
        assertNotNull(selected.getAudioLocale());
        assertEquals("This fixture's original speech is English","en",selected.getAudioLocale().getLanguage());
        YouTubeRepository.Rules all=new YouTubeRepository.Rules(false,"","");
        YouTubeRepository.Resolved original=repo.resolve(id,all,true);
        // Only a non-secret audio-language label is inspected, never the signed URL itself.
        String resolvedTags=Uri.parse(original.url).getQueryParameter("xtags");
        assertNotNull("Fixture must expose the audio language label",resolvedTags);
        assertTrue("Production resolver must choose original speech",resolvedTags.contains("acont=original"));
        assertTrue(resolvedTags.contains("lang=en-US"));
        YouTubeRepository.Rules music=new YouTubeRepository.Rules(true,"","");
        YouTubeRepository.Track next=DeviceStreamChecks.available(repo.searchPage("U2 With Or Without You",music,null).tracks,music);
        List<MediaItem> queue=Arrays.asList(NativeMediaItems.fromTrack(new YouTubeRepository.Track(id,original.title,original.channel,original.durationSeconds,original.artworkUrl),all),NativeMediaItems.fromTrack(next,music));
        SharedPreferences library=context.getSharedPreferences("blackyt_listening_store_default",Context.MODE_PRIVATE);
        String ownerLibrary=library.getString("data_v1",null);
        library.edit().remove("data_v1").commit();new ListeningStore(context);
        AtomicReference<ListenableFuture<MediaController>> connection=new AtomicReference<>();
        main(()->{connection.set(new MediaController.Builder(context,new SessionToken(context,new ComponentName(context,PlaybackService.class))).buildAsync());return true;});
        MediaController player=connection.get().get(25,TimeUnit.SECONDS);
        AtomicInteger transition=new AtomicInteger(-1);
        try {
            PowerManager power=context.getSystemService(PowerManager.class);
            KeyguardManager lock=context.getSystemService(KeyguardManager.class);
            boolean expectOff="true".equals(InstrumentationRegistry.getArguments().getString("expect_screen_off"));
            if(expectOff)await(()->lock.isKeyguardLocked()&&!power.isInteractive(),player,60000);
            main(()->{player.stop();player.clearMediaItems();player.setVolume(0f);assertEquals(0f,player.getVolume(),0f);player.addListener(new Player.Listener(){@Override public void onMediaItemTransition(MediaItem item,int reason){transition.set(reason);}});player.setMediaItems(queue);player.prepare();player.play();return true;});
            await(()->main(()->player.isPlaying()&&player.getCurrentPosition()>1000),player,60000);
            assertEquals(id,main(()->player.getCurrentMediaItem().mediaId));
            long wallStart=SystemClock.elapsedRealtime(),positionStart=main(player::getCurrentPosition);
            boolean continuouslyLocked=true,continuouslyOff=true;
            while(SystemClock.elapsedRealtime()-wallStart<120000){
                assertNull("No error during original speech playback",main(player::getPlayerError));
                assertTrue("Speech must remain playing",main(player::isPlaying));
                assertEquals(1f,(float)main(()->player.getPlaybackParameters().speed),0f);
                assertEquals(1f,(float)main(()->player.getPlaybackParameters().pitch),0f);
                continuouslyLocked&=lock.isKeyguardLocked();continuouslyOff&=!power.isInteractive();
                SystemClock.sleep(1000);
            }
            long elapsed=SystemClock.elapsedRealtime()-wallStart,advanced=main(player::getCurrentPosition)-positionStart;
            assertTrue("Audio time must track real time at 1x",Math.abs(advanced-elapsed)<3000);
            if(expectOff){assertTrue(continuouslyLocked);assertTrue(continuouslyOff);}
            long duration=main(player::getDuration);assertTrue(duration>120000);
            main(()->{player.seekTo(duration-1500);return true;});
            await(()->main(()->player.getCurrentMediaItemIndex()==1&&player.isPlaying()&&player.getCurrentPosition()>1500),player,60000);
            assertEquals(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,transition.get());
            assertEquals(next.id,main(()->player.getCurrentMediaItem().mediaId));
            if(expectOff){assertTrue(lock.isKeyguardLocked());assertFalse(power.isInteractive());}
            Bundle evidence=new Bundle();evidence.putString("blackyt_original_audio_evidence","original=en-US type=ORIGINAL speed=1.0 pitch=1.0 elapsedMs="+elapsed+" advancedMs="+advanced+" continuouslyLocked="+continuouslyLocked+" continuouslyDisplayOff="+continuouslyOff+" automaticNext=true seekNearEndUsed=true appMuted=true");instrumentation.sendStatus(2,evidence);
        } finally {
            main(()->{player.stop();player.clearMediaItems();player.setVolume(1f);player.release();return true;});
            if(ownerLibrary==null)library.edit().remove("data_v1").commit();else library.edit().putString("data_v1",ownerLibrary).commit();
        }
    }
    private <T>T main(Supplier<T> action){AtomicReference<T> result=new AtomicReference<>();instrumentation.runOnMainSync(()->result.set(action.get()));return result.get();}
    private void await(Supplier<Boolean> done,MediaController player,long timeout){long until=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<until){assertNull("Playback failed",main(player::getPlayerError));if(done.get())return;SystemClock.sleep(250);}fail("Timed out waiting for playback or existing screen lock");}
}
