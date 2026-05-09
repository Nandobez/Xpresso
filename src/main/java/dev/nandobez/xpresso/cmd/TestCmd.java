package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "test", aliases = {"t"}, description = "Run `mvn test` (single class via -k).")
public class TestCmd implements Callable<Integer> {

    @Parameters(arity = "0..1", description = "Specific test class or pattern (e.g. UserService*).")
    String pattern;

    public Integer call() throws Exception {
        banner("xpresso test", pattern == null ? "all" : pattern);
        var cmd = new java.util.ArrayList<String>();
        cmd.add("mvn"); cmd.add("test");
        if (pattern != null) cmd.add("-Dtest=" + pattern);
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
