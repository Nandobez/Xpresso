package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.BuildSystem;
import picocli.CommandLine.*;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "test", aliases = {"t"}, description = "Run the test suite (optional class pattern).")
public class TestCmd implements Callable<Integer> {

    @Parameters(arity = "0..1", description = "Optional test class or pattern (UserService*).")
    String pattern;

    public Integer call() throws Exception {
        var bs = BuildSystem.detect(Paths.get("."));
        banner("xpresso test", bs.name() + (pattern == null ? "" : " · " + pattern));
        return new ProcessBuilder(bs.test(pattern)).inheritIO().start().waitFor();
    }
}
