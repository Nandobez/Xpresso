package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "install",
    description = "Full pipeline: mvn clean install (+ macc install if a frontend exists).")
public class InstallCmd implements Callable<Integer> {

    @Option(names = "--skip-tests", defaultValue = "true")
    boolean skipTests;

    @Option(names = "--skip-frontend", description = "Skip macc install even if a frontend dir is present.")
    boolean skipFrontend;

    public Integer call() throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        var bs = dev.nandobez.xpresso.core.BuildSystem.detect(root);
        banner("xpresso install", bs.name());
        int rc = run(bs.install(skipTests), root);
        if (rc != 0) return rc;

        // 2. macc install (if frontend dir exists)
        Path frontend = root.resolve("src/main/frontend");
        if (!skipFrontend && Files.exists(frontend.resolve("package.json"))) {
            info("frontend detected — invoking " + BLD + "macc install" + R);
            String maccJar = locateMaccJar();
            if (maccJar == null) {
                info("(macc jar not found; install from https://github.com/Nandobez/Macc to enable)");
            } else {
                rc = run(java.util.List.of("java", "-jar", maccJar, "install", "--skip-compile"), root);
                if (rc != 0) return rc;
            }
        } else if (!skipFrontend) {
            info("no frontend at src/main/frontend — skipping macc step");
        }
        System.out.println();
        System.out.println("  " + GRN + "✓" + R + " run with: " + BLD + "xpresso s" + R);
        return 0;
    }

    private static int run(java.util.List<String> cmd, Path dir) throws Exception {
        return new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start().waitFor();
    }

    /** Look for macc.jar in standard install locations + $MACC_HOME. */
    static String locateMaccJar() {
        for (String candidate : new String[]{
            System.getenv("MACC_HOME") == null ? null : System.getenv("MACC_HOME") + "/macc.jar",
            System.getProperty("user.home") + "/.local/share/macc/macc.jar",
            "/usr/local/share/macc/macc.jar",
            "/tmp/macc/target/macc.jar",
        }) {
            if (candidate != null && Files.exists(Path.of(candidate))) return candidate;
        }
        return null;
    }
}
