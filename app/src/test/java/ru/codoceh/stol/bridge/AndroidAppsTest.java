package ru.codoceh.stol.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AndroidAppsTest {

    @Test
    public void onlyPlainPackageNamesPass() {
        assertTrue(AndroidApps.isPackageName("org.telegram.messenger"));
        assertTrue(AndroidApps.isPackageName("com.sec.android.app.camera"));
        assertFalse(AndroidApps.isPackageName("telegram"));
        assertFalse(AndroidApps.isPackageName("com.x;rm -rf /"));
        assertFalse(AndroidApps.isPackageName("com.x\nlaunch"));
        assertFalse(AndroidApps.isPackageName(null));
    }
}
