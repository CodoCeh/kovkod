package ru.codoceh.stol.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ProcessGuardTest {

    private static List<ProcessGuard.Entry> desk(int count) {
        List<ProcessGuard.Entry> list = new ArrayList<>();
        list.add(new ProcessGuard.Entry(1, 0, "linker64", 10));
        list.add(new ProcessGuard.Entry(2, 1, "Xtigervnc", 11));
        for (int i = 3; i <= count; i++) {
            list.add(new ProcessGuard.Entry(i, 1, "browser", 100 + i));
        }
        return list;
    }

    @Test
    public void belowLimitNothingIsKilled() {
        assertTrue(ProcessGuard.victims(desk(ProcessGuard.LIMIT), ProcessGuard.LIMIT).isEmpty());
    }

    @Test
    public void overLimitTheYoungestDie() {
        List<ProcessGuard.Entry> victims = ProcessGuard.victims(desk(33), 31);
        assertEquals(2, victims.size());
        assertEquals(33, victims.get(0).pid);
        assertEquals(32, victims.get(1).pid);
    }

    @Test
    public void protectedProcessesNeverDie() {
        List<ProcessGuard.Entry> list = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            list.add(new ProcessGuard.Entry(i, 1, i % 2 == 0 ? "linker64" : "Xtigervnc", i));
        }
        assertTrue(ProcessGuard.victims(list, 31).isEmpty());
    }

    @Test
    public void childrenOfInstallersNeverDie() {
        List<ProcessGuard.Entry> list = desk(32);
        list.add(new ProcessGuard.Entry(100, 1, "apt-get", 500));
        list.add(new ProcessGuard.Entry(101, 100, "store", 501));
        list.add(new ProcessGuard.Entry(102, 101, "gzip", 502));
        List<ProcessGuard.Entry> victims = ProcessGuard.victims(list, 31);
        assertEquals(4, victims.size());
        for (ProcessGuard.Entry victim : victims) {
            assertEquals("browser", victim.name);
        }
    }

    @Test
    public void wholeNewestProgramIsClosed() {
        List<ProcessGuard.Entry> list = desk(30);
        list.add(new ProcessGuard.Entry(100, 1, "claude-desktop", 900));
        list.add(new ProcessGuard.Entry(101, 100, "claude-desktop", 901));
        list.add(new ProcessGuard.Entry(102, 100, "Claude", 902));
        List<ProcessGuard.Entry> victims = ProcessGuard.victims(list, 31);
        assertEquals(3, victims.size());
        for (ProcessGuard.Entry victim : victims) {
            assertTrue(victim.pid >= 100);
        }
    }

    @Test
    public void statWithSpacesInNameIsParsed() {
        String stat = "42 (Web Content) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 777 20";
        ProcessGuard.Entry entry = ProcessGuard.parseStat(42, stat);
        assertEquals("Web Content", entry.name);
        assertEquals(777L, entry.started);
        assertNull(ProcessGuard.parseStat(42, "broken"));
    }
}
