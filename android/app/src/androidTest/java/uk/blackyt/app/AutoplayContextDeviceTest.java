// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises rendered rows and the real MediaSession queue, without audible playback. */
@RunWith(AndroidJUnit4.class)
@androidx.media3.common.util.UnstableApi
public final class AutoplayContextDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity activity;
    private MediaController player;
    private final YouTubeRepository.Track a = track("aaaaaaaaaaa", "Context Alpha");
    private final YouTubeRepository.Track b = track("bbbbbbbbbbb", "Context Bravo");
    private final YouTubeRepository.Track c = track("ccccccccccc", "Context Charlie");
    private final YouTubeRepository.Rules r1 = new YouTubeRepository.Rules(true, "one", "");
    private final YouTubeRepository.Rules r2 = new YouTubeRepository.Rules(false, "two", "excluded");
    private final YouTubeRepository.Rules r3 = new YouTubeRepository.Rules(false, "three", "");

    @Test public void rowsLaunchTheirOwnOrderedCollections() throws Exception {
        SharedPreferences library = context.getSharedPreferences("blackyt_listening_store_default", Context.MODE_PRIVATE);
        SharedPreferences ui = context.getSharedPreferences("blackyt_ui", Context.MODE_PRIVATE);
        String libraryBefore = library.getString("data_v1", null);
        Map<String, ?> uiBefore = ui.getAll();
        library.edit().remove("data_v1").commit();
        AtomicReference<ListenableFuture<MediaController>> connection = new AtomicReference<>();
        main(() -> { connection.set(new MediaController.Builder(context,
                new SessionToken(context, new ComponentName(context, PlaybackService.class))).buildAsync()); return null; });
        player = connection.get().get(25, TimeUnit.SECONDS);
        List<MediaItem> priorQueue = main(() -> { List<MediaItem> items = new ArrayList<>();
            for (int i=0; i<player.getMediaItemCount(); i++) items.add(player.getMediaItemAt(i)); return items; });
        int priorIndex = main(player::getCurrentMediaItemIndex);
        long priorPosition = main(player::getCurrentPosition);
        float priorVolume = main(player::getVolume);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        CachedAudioFixtures fixtures = new CachedAudioFixtures(context);
        try {
            fixtures.seed(a, r1, 10); fixtures.seed(b, r2, 240); fixtures.seed(c, r3, 10);
            main(() -> { player.stop(); player.clearMediaItems(); player.setVolume(0); return null; });
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            activity = (MainActivity) monitor.waitForActivityWithTimeout(12000);
            assertNotNull(activity);
            await(() -> main(() -> field("controller") != null), "Activity controller did not connect");

            // Search results replace a pre-existing queue, selecting the middle result.
            main(() -> {
                player.setMediaItem(NativeMediaItems.fromTrack(c, r3));
                list("results").clear(); list("results").addAll(Arrays.asList(a,b,c));
                rules().clear(); rules().put(a.id,r1); rules().put(b.id,r2); rules().put(c.id,r3);
                set("creatorMode", false); set("creatorPageUrl", null); invoke("showTab", new Class<?>[]{int.class}, 1);
                click("Play Context Bravo by"); return null;
            });
            checkQueue(Arrays.asList(a,b,c), 1, Arrays.asList(r1,r2,r3));
            // The explicit plus action remains an append, not a new list selection.
            main(() -> { click("Add Context Alpha to queue"); return null; });
            await(() -> main(() -> player.getMediaItemCount() == 4), "Append did not retain queue");
            assertEquals(a.id, main(() -> player.getMediaItemAt(3).mediaId));

            // Creator videos use channelResults, rather than the stale search list.
            main(() -> {
                list("channelResults").clear(); list("channelResults").addAll(Arrays.asList(c,b,a));
                set("creatorMode", true); set("creatorPageUrl", "https://www.youtube.com/@test");
                invoke("showTab", new Class<?>[]{int.class}, 1); click("Play Context Bravo by"); return null;
            });
            checkQueue(Arrays.asList(c,b,a), 1, Arrays.asList(r3,r2,r1));

            // Suggestions are a separate displayed collection with their own rules.
            main(() -> {
                set("creatorMode", false); set("creatorPageUrl", null);
                invoke("showTab", new Class<?>[]{int.class}, 1);
                list("suggestionRows").clear();
                list("suggestionRows").add(suggestion(c,r3)); list("suggestionRows").add(suggestion(b,r2));
                set("suggestionSearchGeneration", field("searchGeneration"));
                invoke("renderSearchResults", new Class<?>[0]);
                // Search and suggestion can have the same titles; use the last matching row.
                clickLast("Play Context Charlie by"); return null;
            });
            checkQueue(Arrays.asList(c,b), 0, Arrays.asList(r3,r2));

            // Actual playlist detail view, with a middle entry.
            List<PlaylistStore.Entry> entries = Arrays.asList(new PlaylistStore.Entry(a,r1),new PlaylistStore.Entry(b,r2),new PlaylistStore.Entry(c,r3));
            PlaylistStore.Playlist playlist = new PlaylistStore.Playlist("test", "Context playlist", 0, 0, entries);
            main(() -> { View detail=(View)invoke("buildPlaylistDetail", new Class<?>[]{PlaylistStore.Playlist.class}, playlist);
                clickTextRow(detail, "Context Bravo"); return null; });
            checkQueue(Arrays.asList(a,b,c), 1, Arrays.asList(r1,r2,r3));

            // Stop the previous Bravo playback before saving it. Otherwise its pause/transition
            // progress write can legitimately replace the test's newly created resume point.
            main(() -> { player.stop(); player.clearMediaItems(); return null; });
            await(() -> main(() -> player.getMediaItemCount()==0), "Previous queue did not clear");
            ListeningStore store = new ListeningStore(context);
            store.saveEpisode(a,r1,0); store.saveEpisode(b,r2,205000); store.saveEpisode(c,r3,0);
            assertEquals("Fixture must have a resumable saved position", 205000L, store.resumePositionMs(b.id));
            List<ListeningStore.SavedEpisode> episodes = store.listEpisodes();
            int selected = -1;
            for (int i=0;i<episodes.size();i++) if (episodes.get(i).track.id.equals(b.id)) selected=i;
            final int episodeIndex=selected;
            assertTrue(episodeIndex >= 0);
            main(() -> { invoke("showTab",new Class<?>[]{int.class},2);
                assertEquals("Library row must capture the saved position", 205000L, store.resumePositionMs(b.id));
                click("Resume saved episode Context Bravo"); return null; });
            await(() -> main(() -> player.getMediaItemCount()==3 && player.getCurrentMediaItemIndex()==episodeIndex
                    && player.getCurrentPosition()>=205000), "Saved episode did not launch at its resume point");
            List<YouTubeRepository.Track> episodeTracks=new ArrayList<>(); List<YouTubeRepository.Rules> episodeRules=new ArrayList<>();
            for(ListeningStore.SavedEpisode e:episodes){ episodeTracks.add(e.track); episodeRules.add(e.rules); }
            checkQueue(episodeTracks, episodeIndex, episodeRules);

            // History changes on actual transitions, so the rendered snapshot must remain stable.
            main(() -> {
                list("history").clear(); list("history").add(recent(a,r1)); list("history").add(recent(b,r2)); list("history").add(recent(c,r3));
                invoke("showTab",new Class<?>[]{int.class},0); click("Play Context Bravo by"); return null;
            });
            checkQueue(Arrays.asList(a,b,c),1,Arrays.asList(r1,r2,r3));
        } finally {
            instrumentation.removeMonitor(monitor);
            main(() -> { player.stop(); player.clearMediaItems();
                if (!priorQueue.isEmpty() && priorIndex >= 0 && priorIndex < priorQueue.size()) {
                    player.setMediaItems(priorQueue, priorIndex, priorPosition); player.pause();
                }
                player.setVolume(priorVolume); player.release(); if(activity!=null)activity.finish(); return null; });
            SystemClock.sleep(500);
            fixtures.close();
            if(libraryBefore==null) library.edit().remove("data_v1").commit(); else library.edit().putString("data_v1",libraryBefore).commit();
            SharedPreferences.Editor editor=ui.edit().clear();
            for(Map.Entry<String,?> e:uiBefore.entrySet()) {
                Object v=e.getValue(); if(v instanceof String)editor.putString(e.getKey(),(String)v);
                else if(v instanceof Boolean)editor.putBoolean(e.getKey(),(Boolean)v);
                else if(v instanceof Integer)editor.putInt(e.getKey(),(Integer)v);
                else if(v instanceof Long)editor.putLong(e.getKey(),(Long)v);
                else if(v instanceof Float)editor.putFloat(e.getKey(),(Float)v);
                else if(v instanceof java.util.Set)editor.putStringSet(e.getKey(),(java.util.Set<String>)v);
            }
            editor.commit();
        }
    }

    private void checkQueue(List<YouTubeRepository.Track> tracks,int index,List<YouTubeRepository.Rules> expected) {
        await(() -> main(() -> player.getMediaItemCount()==tracks.size() && player.getCurrentMediaItemIndex()==index
                && player.getMediaItemAt(index).mediaId.equals(tracks.get(index).id)), "Wrong collection or selected index");
        main(() -> { player.pause(); for(int i=0;i<tracks.size();i++) {
            assertEquals(tracks.get(i).id,player.getMediaItemAt(i).mediaId);
            NativeMediaItems.OwnedItem item=NativeMediaItems.parse(player.getMediaItemAt(i));
            assertEquals(expected.get(i).musicOnly,item.rules.musicOnly);
            assertEquals(expected.get(i).include,item.rules.include); assertEquals(expected.get(i).exclude,item.rules.exclude);
        } return null; });
    }
    private static YouTubeRepository.Track track(String id,String title){return new YouTubeRepository.Track(id,title,"Test creator",600);}
    private Object recent(YouTubeRepository.Track t,YouTubeRepository.Rules r){return construct("RecentTrack",new Class<?>[]{YouTubeRepository.Track.class,YouTubeRepository.Rules.class},t,r);}
    private Object suggestion(YouTubeRepository.Track t,YouTubeRepository.Rules r){return construct("PlaylistSuggestionRow",new Class<?>[]{YouTubeRepository.Track.class,YouTubeRepository.Rules.class,String.class},t,r,"Test suggestion");}
    private Object construct(String name,Class<?>[] types,Object... args){try{Class<?> c=Class.forName(MainActivity.class.getName()+"$"+name);Constructor<?> k=c.getDeclaredConstructor(types);k.setAccessible(true);return k.newInstance(args);}catch(Exception e){throw new AssertionError(e);}}
    private Object field(String name){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new AssertionError(e);}}
    private void set(String name,Object value){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") private List<Object> list(String name){return (List<Object>)field(name);}
    @SuppressWarnings("unchecked") private Map<String,YouTubeRepository.Rules> rules(){return (Map<String,YouTubeRepository.Rules>)field("resultRules");}
    private Object invoke(String name,Class<?>[] types,Object... args){try{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args);}catch(Exception e){throw new AssertionError(e);}}
    private void collect(View v,List<View> out){out.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
    private List<View> views(View root){List<View> all=new ArrayList<>();collect(root,all);return all;}
    private void click(String desc){clickMatching(desc,false);}
    private void clickLast(String desc){clickMatching(desc,true);}
    private void clickMatching(String desc,boolean last){List<View> all=views(activity.findViewById(android.R.id.content));if(last)java.util.Collections.reverse(all);for(View v:all)if(v.isClickable()&&v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith(desc)){v.performClick();return;}throw new AssertionError("Missing row "+desc);}
    private void clickTextRow(View root,String title){for(View v:views(root))if(v instanceof android.widget.TextView&&((android.widget.TextView)v).getText().toString().equals(title)){while(!v.isClickable()&&v.getParent() instanceof View)v=(View)v.getParent();assertTrue(v.isClickable());v.performClick();return;}throw new AssertionError("Missing row "+title);}
    private <T>T main(Supplier<T> action){AtomicReference<T> result=new AtomicReference<>();instrumentation.runOnMainSync(()->result.set(action.get()));return result.get();}
    private static void await(Supplier<Boolean> condition,String message){long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(condition.get())return;SystemClock.sleep(100);}throw new AssertionError(message);}
}
