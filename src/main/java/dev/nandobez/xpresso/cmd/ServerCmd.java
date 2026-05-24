package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.BuildSystem;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "server", aliases = {"s"},
    description = "Run the project. Detects a Macc frontend and rebuilds it when stale.")
public class ServerCmd implements Callable<Integer> {

    @Option(names = "--port", description = "Override server.port.")
    String port;

    @Option(names = "--profile", description = "Spring profile to activate.")
    String profile;

    @Option(names = "--skip-frontend", description = "Don't auto-build the frontend.")
    boolean skipFrontend;

    public Integer call() throws Exception {
        Path root = Paths.get(".").toAbsolutePath();
        var bs = BuildSystem.detect(root);
        banner("xpresso server", bs.name());

        Path frontend = root.resolve("src/main/frontend");
        Path bundled  = root.resolve("src/main/resources/static/index.html");
        boolean hasFrontend = Files.exists(frontend.resolve("package.json"));

        if (hasFrontend && !skipFrontend) {
            String maccJar = InstallCmd.locateMaccJar();
            if (maccJar == null) {
                info("macc not installed — skipping frontend rebuild");
            } else if (!Files.exists(bundled)) {
                info("no bundle in resources/static — running full " + BLD + "macc install" + R);
                int rc = new ProcessBuilder("java", "-jar", maccJar, "install").inheritIO().start().waitFor();
                if (rc != 0) return rc;
            } else if (frontendStale(root, bundled)) {
                info("Java @Page sources newer than the bundle — rebuilding frontend");
                int rc = new ProcessBuilder("java", "-jar", maccJar, "install").inheritIO().start().waitFor();
                if (rc != 0) return rc;
            } else {
                info("frontend bundle is up-to-date");
            }
        }
        return new ProcessBuilder(bs.server(port, profile)).inheritIO().start().waitFor();
    }

    /** True when any *.java in the project is newer than the bundled index.html. */
    private static boolean frontendStale(Path root, Path bundle) throws Exception {
        long bundleMtime = Files.getLastModifiedTime(bundle).toMillis();
        Path javaSrc = root.resolve("src/main/java");
        if (!Files.exists(javaSrc)) return false;
        try (Stream<Path> walk = Files.walk(javaSrc)) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                if (Files.getLastModifiedTime(p).toMillis() > bundleMtime) return true;
            }
        }
        return false;
    }
}
