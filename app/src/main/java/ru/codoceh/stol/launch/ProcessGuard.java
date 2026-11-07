package ru.codoceh.stol.launch;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.codoceh.stol.R;

public final class ProcessGuard implements Runnable {

    static final int LIMIT = 32;
    private static final long PERIOD_MS = 300;
    private static final long NOTICE_MS = 60_000;
    private static final String FLAG = "settings_enable_monitor_phantom_procs";
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "linker64", "Xtigervnc", "xfwm4", "xfce4-panel", "xfdesktop", "xfsettingsd", "xfconfd",
            "wrapper-2.0", "dbus-daemon", "at-spi-bus-laun", "at-spi2-registr", "dconf-service", "pulseaudio",
            "thunar", "sh", "bash", "sleep", "python3", "apt-get", "dpkg", "http", "https", "gpgv", "tar", "xz",
            "zstd", "wineserver", "services.exe", "penceh"));

    private static final Set<String> INSTALLERS = new HashSet<>(Arrays.asList("apt-get", "dpkg", "python3"));

    private final Context context;
    private volatile boolean stopped;
    private long lastNotice;

    private ProcessGuard(Context context) {
        this.context = context.getApplicationContext();
    }

    static ProcessGuard start(Context context) {
        ProcessGuard guard = new ProcessGuard(context);
        Thread thread = new Thread(guard, "process-guard");
        thread.setDaemon(true);
        thread.start();
        return guard;
    }

    void stop() {
        stopped = true;
    }

    public static boolean limitActive(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        try {
            return !"false".equals(Settings.Global.getString(context.getContentResolver(), FLAG));
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    static final class Entry {
        final int pid;
        final int parent;
        final String name;
        final long started;

        Entry(int pid, int parent, String name, long started) {
            this.pid = pid;
            this.parent = parent;
            this.name = name;
            this.started = started;
        }
    }

    static List<Entry> victims(List<Entry> processes, int limit) {
        java.util.Map<Integer, Entry> byPid = new java.util.HashMap<>();
        for (Entry entry : processes) {
            byPid.put(entry.pid, entry);
        }
        java.util.Map<Integer, List<Entry>> trees = new java.util.HashMap<>();
        for (Entry entry : processes) {
            if (!PROTECTED.contains(entry.name) && !installerBelow(entry, byPid)) {
                trees.computeIfAbsent(rootOf(entry, byPid).pid, key -> new ArrayList<>()).add(entry);
            }
        }
        List<Integer> roots = new ArrayList<>(trees.keySet());
        roots.sort((a, b) -> {
            Entry first = byPid.get(a);
            Entry second = byPid.get(b);
            return first.started != second.started ? Long.compare(second.started, first.started) : b - a;
        });
        List<Entry> chosen = new ArrayList<>();
        int excess = processes.size() - limit;
        for (int i = 0; i < roots.size() && chosen.size() < excess; i++) {
            chosen.addAll(trees.get(roots.get(i)));
        }
        return chosen;
    }

    private static Entry rootOf(Entry entry, java.util.Map<Integer, Entry> byPid) {
        Entry root = entry;
        Entry parent = byPid.get(root.parent);
        for (int depth = 0; parent != null && !PROTECTED.contains(parent.name) && depth < 64; depth++) {
            root = parent;
            parent = byPid.get(root.parent);
        }
        return root;
    }

    private static boolean installerBelow(Entry entry, java.util.Map<Integer, Entry> byPid) {
        Entry parent = byPid.get(entry.parent);
        for (int depth = 0; parent != null && depth < 64; depth++) {
            if (INSTALLERS.contains(parent.name)) {
                return true;
            }
            parent = byPid.get(parent.parent);
        }
        return false;
    }

    static Entry parseStat(int pid, String stat) {
        int open = stat.indexOf('(');
        int close = stat.lastIndexOf(')');
        if (open < 0 || close < open) {
            return null;
        }
        String[] rest = stat.substring(close + 2).split(" ");
        return rest.length > 19
                ? new Entry(pid, Integer.parseInt(rest[1]), stat.substring(open + 1, close), Long.parseLong(rest[19]))
                : null;
    }

    private List<Entry> phantoms() {
        List<Entry> found = new ArrayList<>();
        String[] names = new File("/proc").list();
        if (names == null) {
            return found;
        }
        String own = context.getPackageName();
        int self = Process.myPid();
        for (String name : names) {
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                continue;
            }
            try {
                int pid = Integer.parseInt(name);
                Entry entry = pid == self ? null : parseStat(pid, new String(
                        Files.readAllBytes(new File("/proc/" + name + "/stat").toPath()), StandardCharsets.UTF_8));
                if (entry != null && !own.startsWith(entry.name)) {
                    found.add(entry);
                }
            } catch (Exception gone) {
                continue;
            }
        }
        return found;
    }

    @Override
    public void run() {
        long checked = 0;
        boolean active = true;
        while (!stopped) {
            try {
                long now = System.currentTimeMillis();
                if (now - checked > 30_000) {
                    active = limitActive(context);
                    checked = now;
                }
                if (active) {
                    List<Entry> victims = victims(phantoms(), LIMIT);
                    for (Entry victim : victims) {
                        Process.sendSignal(victim.pid, Process.SIGNAL_KILL);
                    }
                    if (!victims.isEmpty() && now - lastNotice > NOTICE_MS) {
                        lastNotice = now;
                        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(
                                context, R.string.process_guard_notice, Toast.LENGTH_LONG).show());
                    }
                }
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException interrupted) {
                return;
            } catch (RuntimeException keepGuarding) {
                continue;
            }
        }
    }
}
