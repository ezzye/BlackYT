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
import android.view.MotionEvent;
import android.graphics.drawable.RippleDrawable;
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
        android.content.SharedPreferences ui=context.getSharedPreferences("blackyt_ui",Context.MODE_PRIVATE);
        String ownerHistory=ui.getString("played_tracks","[]");
        android.content.SharedPreferences library=context.getSharedPreferences("blackyt_listening_store_default",Context.MODE_PRIVATE);
        String ownerLibrary=library.getString("data_v1",null);
        library.edit().remove("data_v1").commit();new ListeningStore(context);
        ui.edit().putString("played_tracks","[]").commit();
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
            main(() -> {clickDescription("Search tab");clickDescription("Music");return true;});
            main(() -> {input().setText("U2 With Or Without You");clickDescription("Search YT");return true;});
            main(() -> {clickDescription("Home tab");clickDescription("Queue tab");clickDescription("Search tab");return true;});
            await(() -> main(() -> countDescription("Add ")>0 && countDescription("Load more search results")>0),45000,"Search did not survive a tab switch");
            int before=main(() -> countDescription("Add "));
            main(() -> {clickDescription("Load more search results");return true;});
            await(() -> main(() -> countDescription("Add ")>before),45000,"Load more did not append unique results");
            await(() -> main(() -> loadedArtworkCount(activity.getWindow().getDecorView())>=2),20000,"Artwork did not load in search");
            capture("discovery-search-view.png");
            main(() -> {
                assertNativeFeedback("Search YT");
                assertNativeFeedback("Search tab");
                assertNativeFeedback("Add ");
                for (View view : views()) if (description(view).startsWith("Play ") && description(view).contains(" by ")) {
                    assertTrue("Song rows need native pressed feedback", view.getForeground() instanceof RippleDrawable);
                    break;
                }
                return true;
            });
            @SuppressWarnings("unchecked") List<YouTubeRepository.Track> candidates=main(() -> {
                try {java.lang.reflect.Field field=MainActivity.class.getDeclaredField("results");field.setAccessible(true);
                    return new ArrayList<>((List<YouTubeRepository.Track>)field.get(activity));}
                catch(Exception error){throw new AssertionError(error);}
            });
            YouTubeRepository.Track playable=DeviceStreamChecks.available(candidates,new YouTubeRepository.Rules(true,"",""));
            final int playableIndex=candidates.indexOf(playable);
            main(() -> {clickNth("Play ",playableIndex,true);return true;});
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
            main(() -> {clickNth("Add ",playableIndex,false);clickDescription("Queue tab");return true;});
            await(() -> main(() -> player.getMediaItemCount()==2 && countDescription("Remove ")>0),10000,"Add did not append to the queue");
            capture("discovery-queue-view.png");
            checkPlayerGestures(player);
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
            Diagnostics.event("device-ui-check","search=true tabSwitch=true pagination=true fullPlayer=true miniplayer=true history=true learningSearch=true addRemove=true swipeNextPrevious=true verticalNoSkip=true shortDragNoSkip=true miniSwipe=true tapAfterSwipe=true nativeRipple=true appMuted=true appViewsOnly=true");
        } finally {
            instrumentation.removeMonitor(monitor);
            main(() -> {player.stop();player.clearMediaItems();player.setVolume(1f);player.release();return true;});
            if(activity!=null)main(() -> {activity.finish();return true;});
            ui.edit().putString("played_tracks",ownerHistory).commit();
            if(ownerLibrary==null)library.edit().remove("data_v1").commit();else library.edit().putString("data_v1",ownerLibrary).commit();
        }
    }

    private void checkPlayerGestures(MediaController player) throws Exception {
        assertEquals("Gesture check starts at the first queued song", 0, (int) main(player::getCurrentMediaItemIndex));
        main(() -> { clickDescription("Open Now Playing"); return true; });
        awaitLayout("Current song cover artwork");
        main(() -> {
            View art = findDescription("Current song cover artwork");
            drag(art, .5f, .2f, .5f, .8f);
            return true;
        });
        SystemClock.sleep(300);
        assertEquals("Vertical artwork scrolling must never skip a song", 0, (int) main(player::getCurrentMediaItemIndex));
        // Reopen after the vertical scroll to bring the complete artwork back into view.
        main(() -> { invokeRegisteredBack(); clickDescription("Open Now Playing"); return true; });
        awaitLayout("Current song cover artwork");
        main(() -> { drag(findDescription("Current song cover artwork"), .8f, .5f, .2f, .5f); return true; });
        await(() -> main(() -> player.getCurrentMediaItemIndex() == 1), 10000, "Artwork swipe left did not select next queued song");
        main(() -> {
            assertFalse("Next button must be disabled at queue end", findDescription("Next song").isEnabled());
            drag(findDescription("Current song cover artwork"), .8f, .5f, .2f, .5f);
            return true;
        });
        assertEquals("Swiping at queue end must not wrap or add a song", 1, (int) main(player::getCurrentMediaItemIndex));
        assertEquals(2, (int) main(player::getMediaItemCount));
        main(() -> { drag(findDescription("Current song cover artwork"), .2f, .5f, .8f, .5f); return true; });
        await(() -> main(() -> player.getCurrentMediaItemIndex() == 0), 10000, "Artwork swipe right did not select previous queued song");
        main(() -> {
            assertFalse("Previous button must be disabled at queue start", findDescription("Previous song").isEnabled());
            drag(findDescription("Current song cover artwork"), .5f, .5f, .46f, .5f);
            return true;
        });
        assertEquals("Small artwork drags must not skip", 0, (int) main(player::getCurrentMediaItemIndex));
        main(() -> { tap(findDescription("Pause current song")); return true; });
        await(() -> main(() -> !player.getPlayWhenReady()), 5000, "Pause tap stopped responding after artwork gesture");
        main(() -> { tap(findDescription("Play current song")); return true; });
        await(() -> main(player::getPlayWhenReady), 5000, "Play tap stopped responding after artwork gesture");
        main(() -> { invokeRegisteredBack(); return true; });
        awaitLayout("Open Now Playing");
        main(() -> { drag(findDescription("Open Now Playing"), .58f, .5f, .1f, .5f); return true; });
        await(() -> main(() -> player.getCurrentMediaItemIndex() == 1), 10000, "Miniplayer swipe left did not select next song");
        assertTrue("Miniplayer swipes must not also open the full player", main(() -> countDescription("Back") == 0));
        main(() -> { drag(findDescription("Open Now Playing"), .1f, .5f, .58f, .5f); return true; });
        await(() -> main(() -> player.getCurrentMediaItemIndex() == 0), 10000, "Miniplayer swipe right did not select previous song");
        main(() -> { tapAt(findDescription("Open Now Playing"), .3f, .5f); return true; });
        await(() -> main(() -> countDescription("Back") > 0), 5000, "Miniplayer tap did not open player after swiping");
        main(() -> { invokeRegisteredBack(); return true; });
        assertNull("Gesture navigation caused a playback error", main(player::getPlayerError));
        assertEquals("Gesture checks must remain silent", 0f, (float) main(player::getVolume), 0f);
    }

    private void awaitLayout(String prefix) {
        instrumentation.waitForIdleSync();
        // A sleeping display may defer Android's normal traversal. Layout only this app's
        // existing content for the behind-keyguard gesture check; never wake or unlock it.
        main(() -> {layoutOwnContent();return true;});
        await(() -> main(() -> {View v=findDescription(prefix);return v.isLaidOut()&&v.getWidth()>0&&v.getHeight()>0;}),5000,"View not laid out: "+prefix);
    }

    private void assertNativeFeedback(String prefix) {
        assertTrue("Missing native press feedback: " + prefix, findDescription(prefix).getForeground() instanceof RippleDrawable);
    }

    private View findDescription(String prefix) {
        for (View view : views()) if (view.getVisibility() == View.VISIBLE && description(view).startsWith(prefix)) return view;
        throw new AssertionError("Missing view: " + prefix);
    }

    private void tap(View target) { tapAt(target, .5f, .5f); }
    private void tapAt(View target, float x, float y) { drag(target, x, y, x, y); }

    /** Dispatch through the decor so real child buttons and ScrollView interception participate. */
    private void drag(View target, float fromX, float fromY, float toX, float toY) {
        assertTrue("Gesture target must be laid out", target.getWidth() > 0 && target.getHeight() > 0);
        View decor = activity.getWindow().getDecorView();
        int[] targetOrigin = new int[2], decorOrigin = new int[2];
        target.getLocationInWindow(targetOrigin); decor.getLocationInWindow(decorOrigin);
        float left = targetOrigin[0] - decorOrigin[0], top = targetOrigin[1] - decorOrigin[1];
        long down = SystemClock.uptimeMillis();
        for (int step = 0; step <= 10; step++) {
            float fraction = step / 10f;
            float x = left + target.getWidth() * (fromX + (toX - fromX) * fraction);
            float y = top + target.getHeight() * (fromY + (toY - fromY) * fraction);
            int action = step == 0 ? MotionEvent.ACTION_DOWN : step == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, down + step * 30, action, x, y, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { decor.dispatchTouchEvent(event); } finally { event.recycle(); }
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
    private void clickNth(String prefix,int index,boolean songRow) {
        int current=0;
        for(View view:views())if(view.getVisibility()==View.VISIBLE&&view.isClickable()&&description(view).startsWith(prefix)
                &&(!songRow||description(view).contains(" by "))){if(current++==index){assertTrue(view.isEnabled());view.performClick();return;}}
        throw new AssertionError("Missing exact result index "+index);
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
        String heading = name.contains("search") ? "Search" : name.contains("player") ? "Now Playing"
                : name.contains("queue") ? "Queue" : "BlackYT";
        main(() -> { assertHeadingBelowStatusBar(heading); return true; });
        Bitmap bitmap=main(() -> {
            layoutOwnContent();
            View content=activity.findViewById(android.R.id.content);
            assertTrue("Own content needs layout",content.getWidth()>0 && content.getHeight()>0);
            Bitmap image=Bitmap.createBitmap(content.getWidth(),content.getHeight(),Bitmap.Config.ARGB_8888);
            content.draw(new Canvas(image));return image;
        });
        try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));
        }finally{bitmap.recycle();}
    }
    private void layoutOwnContent() {
        View content=activity.findViewById(android.R.id.content);
        int width=content.getWidth(),height=content.getHeight();
        assertTrue("Existing app content must have bounds",width>0&&height>0);
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        content.layout(content.getLeft(),content.getTop(),content.getLeft()+width,content.getTop()+height);
    }
    private void assertHeadingBelowStatusBar(String heading) {
        android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
        assertNotNull("Window safe areas must be available", insets);
        int safeTop = android.os.Build.VERSION.SDK_INT >= 30
                ? insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout()).top
                : insets.getStableInsetTop();
        for (View view : views()) {
            if (view instanceof TextView && ((TextView) view).getText().toString().equals(heading)) {
                int[] position = new int[2]; view.getLocationOnScreen(position);
                assertTrue(heading + " overlaps the status bar: top=" + position[1] + " safeTop=" + safeTop,
                        position[1] >= safeTop);
                return;
            }
        }
        fail("Missing page heading: " + heading);
    }

    private <T> T main(Supplier<T> action) {
        AtomicReference<T> value=new AtomicReference<>();instrumentation.runOnMainSync(() -> value.set(action.get()));return value.get();
    }
    private static void await(Supplier<Boolean> condition,long timeout,String reason) {
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until){if(condition.get())return;SystemClock.sleep(200);}throw new AssertionError(reason);
    }
}
