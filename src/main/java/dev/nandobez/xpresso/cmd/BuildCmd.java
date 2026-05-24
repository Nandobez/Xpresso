package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.BuildSystem;
import picocli.CommandLine.*;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "build", description = "Build a runnable jar (skips tests by default).")
public class BuildCmd implements Callable<Integer> {

    @Option(names = "--with-tests")
    boolean withTests;

    public Integer call() throws Exception {
        var bs = BuildSystem.detect(Paths.get("."));
        banner("xpresso build", bs.name() + (withTests ? " · with tests" : ""));
        return new ProcessBuilder(bs.build(!withTests)).inheritIO().start().waitFor();
    }
}
