// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** End-to-end own-app journey. It never unlocks the device and never plays audible audio. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class ListeningJourneyDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private Activity activity;
    private final ListeningStore store=new ListeningStore(context);
    private static final String EXAMPLE="EmaXeEdlJkI";
    private static final String SHORT_LINK="https://youtu.be/"+EXAMPLE+"?si=share-example&t=205s";

    @Test public void shareCreatorLibraryExploreAndBackgroundResume() throws Exception {
        SharedPreferences data=context.getSharedPreferences("blackyt_listening_store_default",Context.MODE_PRIVATE);
        String original=data.getString("data_v1",null);
        // Keep the owner's Library and custom topics untouched, and out of exported renders.
        data.edit().remove("data_v1").commit();new ListeningStore(context);
        AtomicReference<ListenableFuture<MediaController>> connection=new AtomicReference<>();
        main(()->{connection.set(new MediaController.Builder(context,new SessionToken(context,new ComponentName(context,PlaybackService.class))).buildAsync());return true;});
        MediaController player=connection.get().get(25,TimeUnit.SECONDS);
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(MainActivity.class.getName(),null,false);
        try {
            main(()->{player.stop();player.clearMediaItems();player.setVolume(0f);assertEquals(0f,player.getVolume(),0f);return true;});
            launch(monitor,new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"Listen to this episode " + SHORT_LINK));
            await(()->main(()->count("Save ")>0&&!searchBusy()),60000,"Shared short link did not reach a ready episode");
            assertEquals("Sharing must not autoplay",0,(int)main(player::getMediaItemCount));
            assertTrue(main(()->containsText("3:25")));
            main(()->{click("Save ");return true;});
            assertNotNull(store.getEpisode(EXAMPLE));
            assertEquals(205000,store.resumePositionMs(EXAMPLE));
            capture("listening-shared-view.png");
            context.startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(SHORT_LINK))
                    .setComponent(new ComponentName(context,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->main(()->Intent.ACTION_VIEW.equals(activity.getIntent().getAction())&&count("Saved ")>0&&containsText("3:25")&&!searchBusy()),60000,"Open link must show a timestamped preview");
            assertEquals("Opening a link must not autoplay",0,(int)main(player::getMediaItemCount));

            main(()->{click("Creators");input("Search songs, artists, or YT links").setText("Wes Roth");click("Search YT");return true;});
            await(()->main(()->count("Open creator Wes Roth")>0),60000,"Wes Roth creator search did not return a channel");
            capture("listening-creators-view.png");
            main(()->{click("Open creator Wes Roth");return true;});
            await(()->main(()->count("Save creator Wes Roth")>0&&count("Add ")>0),60000,"Creator did not open an actual Videos page");
            int initial=main(()->count("Add "));
            if(main(()->count("Load more creator episodes")>0)) {
                main(()->{click("Load more creator episodes");return true;});
                await(()->main(()->count("Add ")>initial),60000,"Creator continuation did not append episodes");
            }
            main(()->{click("Save creator Wes Roth");return true;});
            assertFalse(store.listCreators().isEmpty());
            capture("listening-channel-view.png");
            main(()->{click("Library tab");return true;});
            assertTrue(main(()->containsText("Your Library")));
            assertTrue(main(()->count("Resume saved episode ")>0));
            assertTrue(main(()->count("Open saved creator Wes Roth")>0));
            capture("listening-library-view.png");
            main(()->{click("Open saved creator Wes Roth");return true;});
            await(()->main(()->count("Saved creator Wes Roth")>0&&count("Add ")>0),60000,"Saved creator did not reopen latest episodes");
            main(()->{input("Search songs, artists, or YT links").setText(SHORT_LINK);click("Search YT");return true;});
            await(()->main(()->count("All videos, selected")>0&&containsText("3:25")&&count("Saved ")>0),60000,"Pasting a video while Creators is selected must switch to All videos");
            assertEquals(0,(int)main(player::getMediaItemCount));

            main(()->{click("Home tab");click("Edit Explore topics");return true;}); settle();
            main(()->{clickText("+ Add a topic");return true;}); settle();
            main(()->{input("Explore topic name").setText("My AI listening test");input("Explore search phrase").setText("Wes Roth AI news");clickText("Save");return true;}); settle();
            assertTrue(store.listTiles().stream().anyMatch(t->t.title.equals("My AI listening test")&&!t.musicOnly));
            main(()->{click("Edit Explore topics");return true;}); settle();
            main(()->{clickText("My AI listening test");return true;}); settle();
            main(()->{clickText("Edit topic");return true;}); settle();
            main(()->{input("Explore topic name").setText("My AI episodes test");clickText("Save");return true;}); settle();
            assertTrue(store.listTiles().stream().anyMatch(t->t.title.equals("My AI episodes test")));
            main(()->{click("Search YT for Wes Roth AI news");return true;});
            await(()->main(()->count("All videos, selected")>0&&count("Add ")>0),60000,"Edited Explore topic did not search spoken videos");
            main(()->{click("Home tab");return true;});
            capture("listening-home-view.png");

            // Finish/recreate the Activity: metadata and resume position must come from storage.
            main(()->{activity.finish();return true;});
            launch(monitor,new Intent(context,MainActivity.class));
            main(()->{click("Library tab");return true;});
            assertEquals(205000,new ListeningStore(context).resumePositionMs(EXAMPLE));
            main(()->{click("Resume saved episode "+store.getEpisode(EXAMPLE).track.title);return true;});
            await(()->main(()->player.isPlaying()&&player.getCurrentPosition()>=205000),60000,"Library did not resume at the shared timestamp");
            assertEquals(EXAMPLE,main(()->player.getCurrentMediaItem().mediaId));
            assertNull(main(player::getPlayerError));
            assertEquals(0f,(float)main(player::getVolume),0f);
            capture("listening-player-view.png");
            long before=main(player::getCurrentPosition);
            main(()->{activity.finish();return true;});
            try(android.os.ParcelFileDescriptor ignored=instrumentation.getUiAutomation().executeShellCommand("input keyevent 223")) { }
            PowerManager power=context.getSystemService(PowerManager.class);
            KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
            await(()->!power.isInteractive()&&keyguard.isKeyguardLocked(),10000,"Screen must be off and locked for background progress check");
            long until=SystemClock.elapsedRealtime()+35000;
            while(SystemClock.elapsedRealtime()<until) {
                assertFalse("Screen must remain off",power.isInteractive());
                assertTrue("Keyguard must remain locked",keyguard.isKeyguardLocked());
                assertTrue("Background playback stopped",main(player::isPlaying));
                assertNull(main(player::getPlayerError));
                SystemClock.sleep(1000);
            }
            ListeningStore.SavedEpisode progressed=new ListeningStore(context).getEpisode(EXAMPLE);
            assertNotNull(progressed);
            assertTrue("Service must persist progress with Activity absent",progressed.positionMs>=before+20000);
            assertEquals(0f,(float)main(player::getVolume),0f);
            JSONObject evidence=new JSONObject().put("sharedShortLink",true).put("shareDidNotAutoplay",true)
                    .put("openLinkPreviewWithoutAutoplay",true).put("pasteVideoWhileCreatorsSelected",true).put("sharedTimestampMs",205000).put("creatorSearch",true).put("actualCreatorVideos",true)
                    .put("creatorInitialRows",initial).put("savedCreatorReopened",true).put("exploreAddEditSearch",true)
                    .put("savedEpisodeActivityRestart",true).put("libraryResume",true).put("screenOffLockedSeconds",35)
                    .put("serviceProgressWithActivityAbsentMs",progressed.positionMs-before).put("appMuted",true)
                    .put("appViewRendersOnly",true);
            try(FileOutputStream out=context.openFileOutput("listening-journey-evidence.json",Context.MODE_PRIVATE)){out.write(evidence.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            java.nio.file.Files.copy(new File(context.getFilesDir(),"listening-journey-evidence.json").toPath(),new File(context.getExternalFilesDir("episodes-check"),"listening-journey-evidence.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            instrumentation.removeMonitor(monitor);
            main(()->{player.stop();player.clearMediaItems();player.setVolume(1f);player.release();if(activity!=null)activity.finish();return true;});
            SystemClock.sleep(500);
            if(original==null)data.edit().remove("data_v1").commit();else data.edit().putString("data_v1",original).commit();
        }
    }
    private void launch(Instrumentation.ActivityMonitor monitor,Intent intent) {
        intent.setComponent(new ComponentName(context,MainActivity.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);activity=monitor.waitForActivityWithTimeout(12000);
        assertNotNull("Own activity was not created",activity);
        instrumentation.waitForIdleSync();
    }
    private List<View> views(){List<View> all=new ArrayList<>();for(View root:WindowInspector.getGlobalWindowViews())collect(root,all);return all;}
    private void collect(View v,List<View> out){out.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
    private String desc(View v){return v.getContentDescription()==null?"":v.getContentDescription().toString();}
    private int count(String prefix){int n=0;for(View v:views())if(v.getVisibility()==View.VISIBLE&&desc(v).startsWith(prefix))n++;return n;}
    private void click(String prefix){for(View v:views())if(v.getVisibility()==View.VISIBLE&&v.isClickable()&&desc(v).startsWith(prefix)){assertTrue(v.isEnabled());v.performClick();return;}throw new AssertionError("Missing clickable "+prefix);}
    private void clickText(String text){
        // AlertDialog rows beyond the visible fold still have adapter positions. Invoke its
        // own list callback, rather than accidentally tapping the Home tile behind a dialog.
        List<View> candidates=views();java.util.Collections.reverse(candidates);
        for(View v:candidates)if(v instanceof android.widget.ListView&&v.getVisibility()==View.VISIBLE){
            android.widget.ListView list=(android.widget.ListView)v;
            for(int i=0;i<list.getAdapter().getCount();i++)if(text.equals(String.valueOf(list.getAdapter().getItem(i)))){
                list.performItemClick(null,i,list.getItemIdAtPosition(i));return;
            }
        }
        for(View v:candidates)if(v instanceof TextView&&v.getVisibility()==View.VISIBLE&&((TextView)v).getText().toString().equals(text)){View target=v;
            while(target.getParent() instanceof View) {
                if(target.getParent() instanceof android.widget.ListView) {
                    android.widget.ListView list=(android.widget.ListView)target.getParent();
                    int position=list.getPositionForView(target);list.performItemClick(target,position,list.getItemIdAtPosition(position));return;
                }
                if(target.isClickable())break;target=(View)target.getParent();
            }assertTrue("Text needs clickable parent "+text,target.isClickable());target.performClick();return;}throw new AssertionError("Missing text "+text);}
    private boolean containsText(String part){for(View v:views())if(v instanceof TextView&&v.getVisibility()==View.VISIBLE&&((TextView)v).getText().toString().contains(part))return true;return false;}
    private EditText input(String description){for(View v:views())if(v instanceof EditText&&desc(v).equals(description))return(EditText)v;throw new AssertionError("Missing input "+description);}
    private boolean searchBusy(){try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("activeSearch");f.setAccessible(true);return f.getBoolean(activity);}catch(Exception e){throw new AssertionError(e);}}
    private void settle(){instrumentation.waitForIdleSync();SystemClock.sleep(200);}
    private void capture(String name)throws Exception{instrumentation.waitForIdleSync();SystemClock.sleep(250);Bitmap image=main(()->{View content=activity.findViewById(android.R.id.content);assertTrue(content.getWidth()>0);Bitmap bitmap=Bitmap.createBitmap(content.getWidth(),content.getHeight(),Bitmap.Config.ARGB_8888);content.draw(new Canvas(bitmap));return bitmap;});try(FileOutputStream out=context.openFileOutput(name,Context.MODE_PRIVATE)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
        File dir=context.getExternalFilesDir("episodes-check");assertNotNull(dir);assertTrue(dir.isDirectory()||dir.mkdirs());
        java.nio.file.Files.copy(new File(context.getFilesDir(),name).toPath(),new File(dir,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private <T>T main(Supplier<T> action){AtomicReference<T> value=new AtomicReference<>();instrumentation.runOnMainSync(()->value.set(action.get()));return value.get();}
    private static void await(Supplier<Boolean> condition,long timeout,String reason){long until=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<until){if(condition.get())return;SystemClock.sleep(200);}throw new AssertionError(reason);}
}
