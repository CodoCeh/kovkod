package ru.codoceh.stol.launch;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;

public class DesktopServiceTest {

    @Test
    public void onCreateMethodIsOverridden() throws NoSuchMethodException {
        Method onCreate = DesktopService.class.getDeclaredMethod("onCreate");
        assertNotNull(onCreate);
        assertTrue(DesktopService.class.equals(onCreate.getDeclaringClass()));
    }

    @Test
    public void onDestroyMethodIsOverridden() throws NoSuchMethodException {
        Method onDestroy = DesktopService.class.getDeclaredMethod("onDestroy");
        assertNotNull(onDestroy);
        assertTrue(DesktopService.class.equals(onDestroy.getDeclaringClass()));
    }
}
