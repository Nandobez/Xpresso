package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "build", description = "Run `mvn -DskipTests package` (produces target/*.jar).")
public class BuildCmd implements Callable<Integer> {

    @Option(names = "--with-tests", description = "Run tests as part of the build.")
    boolean withTests;

    public Integer call() throws Exception {
        banner("xpresso build", withTests ? "with tests" : "skipping tests");
        var cmd = new java.util.ArrayList<String>();
        cmd.add("mvn"); cmd.add("clean"); cmd.add("package");
        if (!withTests) cmd.add("-DskipTests");
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
