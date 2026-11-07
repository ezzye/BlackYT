// SPDX-License-Identifier: GPL-3.0-or-later
package uk.blackyt.app;

import android.app.AlertDialog;
import android.widget.RadioButton;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class JourneySettingsTest {
    @Test public void connectionChoicesRemainVisibleBesideCacheStatusAndPersist() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).create().get();
        java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("showPreparationSettings");
        method.setAccessible(true); method.invoke(activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        RadioButton off = dialog.findViewById(20_000), unmetered = dialog.findViewById(20_001), mobile = dialog.findViewById(20_002);
        assertNotNull(off); assertNotNull(unmetered); assertNotNull(mobile);
        assertTrue(unmetered.isChecked());
        mobile.performClick();
        assertEquals(PlaybackPreparation.ANY_NETWORK, PlaybackPreparation.get(activity).mode());
        off.performClick();
        assertEquals(PlaybackPreparation.OFF, PlaybackPreparation.get(activity).mode());
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().contains("Clear saved audio"));
        dialog.dismiss();
    }
}
