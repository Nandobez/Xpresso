package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "compile",
    description = "mvn -q compile (+ macc codegen if frontend exists).")
public class CompileCmd implements Callable<Integer> {

    public Integer call() throws Exception {
        Path root = Paths.get(".").toAbsolutePath();
        var bs = dev.nandobez.xpresso.core.BuildSystem.detect(root);
        banner("xpresso compile", bs.name());
        int rc = Mvn.run(bs.compile(), root, null);
        if (rc != 0) return rc;

        Path frontend = root.resolve("src/main/frontend");
        if (Files.exists(frontend.resolve("package.json"))) {
            String maccJar = InstallCmd.locateMaccJar();
            if (maccJar != null) {
                info("running macc codegen");
                rc = new ProcessBuilder("java", "-jar", maccJar, "codegen").inheritIO().start().waitFor();
            }
        }
        if (rc == 0) System.out.println("    " + GRN + "✓ " + R + "compiled");
        return rc;
    }
}
