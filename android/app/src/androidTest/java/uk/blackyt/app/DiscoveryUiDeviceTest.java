// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** Own-app controls and view renders behind an existing lock. Playback is app-muted. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class DiscoveryUiDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private Activity activity;

    @Test public void compactSearchFullPlayerHistoryAndQueue() throws Exception {
        for(String screen:new String[]{"search","player","queue","home"})context.deleteFile("discovery-"+screen+"-view.png");
        AtomicReference<ListenableFuture<MediaController>> connection = new AtomicReference<>();
        main(() -> {connection.set(new MediaController.Builder(context,new SessionToken(context,
                new ComponentName(context,PlaybackService.class))).buildAsync());return true;});
        MediaController player = connection.get().get(25,TimeUnit.SECONDS);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        try {
            main(() -> {
                assertTrue(player.isCommandAvailable(Player.COMMAND_SET_VOLUME));
                player.stop();player.clearMediaItems();player.setVolume(0f);
                assertEquals(0f,player.getVolume(),0f);return true;
            });
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            activity = monitor.waitForActivityWithTimeout(12000);
            assertNotNull("Own activity must be created; this check never unlocks the phone",activity);
            await(() -> main(() -> hasText("Jump back in")),10000,"Home did not build");
            main(() -> {clickDescription("Search tab");return true;});
            main(() -> {input().setText("U2 With Or Without You");clickDescription("Search YT");return true;});
            main(() -> {clickDescription("Home tab");clickDescription("Queue tab");clickDescription("Search tab");return true;});
            await(() -> main(() -> countDescription("Add ")>0 && countDescription("Load more search results")>0),45000,"Search did not survive a tab switch");
            int before=main(() -> countDescription("Add "));
            main(() -> {clickDescription("Load more search results");return true;});
            await(() -> main(() -> countDescription("Add ")>before),45000,"Load more did not append unique results");
            await(() -> main(() -> loadedArtworkCount(activity.getWindow().getDecorView())>=2),20000,"Artwork did not load in search");
            capture("discovery-search-view.png");
            main(() -> {
                for(View view:views()) {
                    String description=description(view);
                    if(description.startsWith("Play ") && description.contains(" by ") && view.isClickable()) {
                        view.performClick();return true;
                    }
                }
                fail("No tappable song row");return false;
            });
            await(() -> main(() -> {
                assertNull("Selected song reported a playback error",player.getPlayerError());
                return player.isPlaying() && player.getCurrentPosition()>1000 && countDescription("Open Now Playing")>0;
            }),60000,"Song row did not start playback and reveal the miniplayer");
            main(() -> {clickDescription("Open Now Playing");return true;});
            await(() -> main(() -> countDescription("Back")>0),5000,"Miniplayer did not open full Now Playing");
            assertEquals(0f,(float)main(player::getVolume),0f);
            capture("discovery-player-view.png");
            main(() -> {invokeRegisteredBack();return true;});
            await(() -> main(() -> countDescription("Open Now Playing")>0),5000,"Miniplayer did not appear above navigation");
            main(() -> {clickDescription("Add ");clickDescription("Queue tab");return true;});
            await(() -> main(() -> player.getMediaItemCount()==2 && countDescription("Remove ")>0),10000,"Add did not append to the queue");
            capture("discovery-queue-view.png");
            main(() -> {clickDescription("Remove ");return true;});
            await(() -> main(() -> player.getMediaItemCount()==1),10000,"Queue removal did not update");
            main(() -> {clickDescription("Home tab");return true;});
            await(() -> main(() -> countDescription("Play ")>0),5000,"Played song did not reach Jump back in");
            capture("discovery-home-view.png");
            String savedHistory=context.getSharedPreferences("blackyt_ui",Context.MODE_PRIVATE).getString("played_tracks","[]");
            assertTrue("Actual-play history must be stored locally",new org.json.JSONArray(savedHistory).length()>0);
            main(() -> {clickDescription("Open Now Playing");return true;});
            assertTrue(main(() -> hasText("Now Playing")));
            main(() -> {clickDescription("Back");clickDescription("Search tab");clickDescription("Clear search results");return true;});
            assertEquals(0,(int)main(() -> countDescription("Add ")));
            main(() -> {clickDescription("Home tab");clickDescription("Search YT for music theory lesson");return true;});
            await(() -> main(() -> countDescription("All videos, selected")>0 && countDescription("Add ")>0),45000,"Music learning did not search educational videos");
            main(() -> {clickDescription("Music");input().setText("U2 With Or Without You");return true;});
            Diagnostics.event("device-ui-check","search=true tabSwitch=true pagination=true fullPlayer=true miniplayer=true history=true learningSearch=true addRemove=true appMuted=true appViewsOnly=true");
        } finally {
            instrumentation.removeMonitor(monitor);
            main(() -> {player.stop();player.clearMediaItems();player.setVolume(1f);player.release();return true;});
            if(activity!=null)main(() -> {activity.finish();return true;});
        }
    }

    private void invokeRegisteredBack() {
        if(android.os.Build.VERSION.SDK_INT>=33) {
            try {
                java.lang.reflect.Field field=MainActivity.class.getDeclaredField("platformBackCallback");
                field.setAccessible(true);Object callback=field.get(activity);
                assertNotNull("Modern Android Back must be registered",callback);
                ((android.window.OnBackInvokedCallback)callback).onBackInvoked();
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        }else activity.onBackPressed();
    }

    private EditText input() {
        for(View view:views()) if(view instanceof EditText && description(view).equals("Search songs, artists, or YT links"))return (EditText)view;
        throw new AssertionError("Search field missing");
    }
    private boolean hasText(String text) {
        for(View view:views())if(view instanceof TextView && ((TextView)view).getText().toString().equals(text))return true;
        return false;
    }
    private int countDescription(String prefix) {
        int count=0;for(View view:views())if(view.getVisibility()==View.VISIBLE && description(view).startsWith(prefix))count++;return count;
    }
    private void clickDescription(String prefix) {
        for(View view:views()) if(view.getVisibility()==View.VISIBLE && view.isClickable() && description(view).startsWith(prefix)) {
            assertTrue("Control disabled: "+prefix,view.isEnabled());view.performClick();return;
        }
        throw new AssertionError("Missing active control: "+prefix);
    }
    private static String description(View view) {return view.getContentDescription()==null?"":view.getContentDescription().toString();}
    private List<View> views() {List<View> found=new ArrayList<>();collect(activity.getWindow().getDecorView(),found);return found;}
    private static void collect(View view,List<View> found) {
        found.add(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),found);
    }
    private static int loadedArtworkCount(View view) {
        int count=view instanceof ImageView && view.getTag() instanceof String && !((String)view.getTag()).isEmpty()
                && ((ImageView)view).getScaleType()==ImageView.ScaleType.CENTER_CROP?1:0;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)count+=loadedArtworkCount(((ViewGroup)view).getChildAt(i));return count;
    }
    private void capture(String name) throws Exception {
        Bitmap bitmap=main(() -> {
            View content=activity.findViewById(android.R.id.content);
            assertTrue("Own content needs layout",content.getWidth()>0 && content.getHeight()>0);
            Bitmap image=Bitmap.createBitmap(content.getWidth(),content.getHeight(),Bitmap.Config.ARGB_8888);
            content.draw(new Canvas(image));return image;
        });
        try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));
        }finally{bitmap.recycle();}
    }
    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value=new AtomicReference<>();instrumentation.runOnMainSync(() -> value.set(action.get()));return value.get();
    }
    private static void await(Supplier<Boolean> condition,long timeout,String reason) {
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until){if(condition.get())return;SystemClock.sleep(200);}throw new AssertionError(reason);
    }
}
