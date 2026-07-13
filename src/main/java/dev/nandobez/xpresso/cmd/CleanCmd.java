package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "clean", description = "Run `mvn clean` (+ rm node_modules/dist if --deep).")
public class CleanCmd implements Callable<Integer> {

    @Option(names = "--deep", description = "Also remove node_modules and resources/static.")
    boolean deep;

    public Integer call() throws Exception {
        var bs = dev.nandobez.xpresso.core.BuildSystem.detect(Paths.get("."));
        banner("xpresso clean", bs.name() + (deep ? " · deep" : ""));
        int rc = Mvn.run(bs.clean(), bs.root(), null);
        if (rc != 0) return rc;
        if (deep) {
            Path fe = Paths.get("src/main/frontend");
            if (Files.exists(fe.resolve("node_modules"))) {
                info("removing src/main/frontend/node_modules");
                deleteRecursively(fe.resolve("node_modules"));
            }
            Path stat = Paths.get("src/main/resources/static");
            if (Files.exists(stat)) {
                info("removing src/main/resources/static");
                deleteRecursively(stat);
            }
        }
        System.out.println("    " + GRN + "✓ " + R + "cleaned");
        return 0;
    }

    private static void deleteRecursively(Path p) throws Exception {
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (Exception ignored) {} });
        }
    }
}
