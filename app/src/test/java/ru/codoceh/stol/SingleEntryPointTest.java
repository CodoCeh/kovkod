package ru.codoceh.stol;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Шов 2 живёт, только пока его нельзя обойти: proot запускается из одного класса, а
 * процессы создаются в одном. Проверка идёт по исходникам, потому что подмена происходит
 * при написании кода, а не в рантайме.
 */
public class SingleEntryPointTest {

    @Test
    public void prootIsMentionedOnlyInProotSession() throws IOException {
        for (File source : sources()) {
            if (source.getName().equals("ProotSession.java")) {
                continue;
            }
            String text = read(source);
            if (text.toLowerCase().contains("proot")) {
                for (String line : text.split("\n")) {
                    if (line.toLowerCase().contains("proot")
                            && !line.contains("ProotSession")
                            && !line.contains("ru.codoceh.stol.runtime")) {
                        fail(source.getName() + " лезет к proot мимо ProotSession: " + line.trim());
                    }
                }
            }
        }
    }

    @Test
    public void processesAreCreatedOnlyInSystemProcessLauncher() throws IOException {
        for (File source : sources()) {
            if (source.getName().equals("SystemProcessLauncher.java")) {
                continue;
            }
            String text = read(source);
            assertTrue(source.getName() + " создаёт процесс сам",
                    !text.contains("ProcessBuilder") && !text.contains("Runtime.getRuntime"));
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static List<File> sources() {
        List<File> found = new ArrayList<>();
        collect(sourceRoot(), found);
        assertTrue("исходники не найдены", found.size() >= 4);
        return found;
    }

    private static void collect(File dir, List<File> found) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, found);
            } else if (child.getName().endsWith(".java")) {
                found.add(child);
            }
        }
    }

    private static File sourceRoot() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            for (String candidate : new String[]{"src/main/java", "app/src/main/java"}) {
                File root = new File(dir, candidate);
                if (root.isDirectory()) {
                    return root;
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл исходники от " + System.getProperty("user.dir"));
        return null;
    }
}
