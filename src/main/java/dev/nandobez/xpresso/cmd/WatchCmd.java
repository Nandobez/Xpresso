package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static dev.nandobez.xpresso.cmd.Tui.*;
import static java.nio.file.StandardWatchEventKinds.*;

@Command(name = "watch",
    description = "Watch src/main/java and re-run `mvn compile` on every change. Spring DevTools picks up the new classes.")
public class WatchCmd implements Callable<Integer> {

    @Option(names = {"-d", "--dir"}, defaultValue = "src/main/java")
    Path src;

    public Integer call() throws Exception {
        if (!Files.exists(src)) { error("not found: " + src); return 2; }
        banner("xpresso watch", src.toString());
        info("press Ctrl-C to stop");

        WatchService ws = FileSystems.getDefault().newWatchService();
        try (Stream<Path> w = Files.walk(src)) {
            w.filter(Files::isDirectory).forEach(p -> {
                try { p.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE); } catch (Exception ignored) {}
            });
        }

        long lastTrigger = 0;
        while (true) {
            WatchKey key = ws.take();
            for (var ev : key.pollEvents()) {
                String name = ev.context().toString();
                if (!name.endsWith(".java")) continue;
                long now = System.currentTimeMillis();
                if (now - lastTrigger < 300) continue;       // debounce
                lastTrigger = now;
                System.out.println();
                System.out.println("  " + CYN + "↻" + R + " change in " + DIM + name + R + " — recompiling…");
                long t0 = System.currentTimeMillis();
                int rc = new ProcessBuilder("mvn", "-q", "compile").inheritIO().start().waitFor();
                long ms = System.currentTimeMillis() - t0;
                if (rc == 0) System.out.println("    " + GRN + "✓" + R + " compiled" + DIM + "  (" + ms + "ms)" + R);
                else         System.out.println("    " + RED + "✗" + R + " compilation failed");
            }
            key.reset();
        }
    }
}
