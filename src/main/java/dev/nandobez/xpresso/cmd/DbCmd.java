package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.BuildSystem;
import picocli.CommandLine.*;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "db", description = "Database tasks: migrate | rollback | info | clean | repair.")
public class DbCmd implements Callable<Integer> {

    @Parameters(arity = "1", description = "Task: migrate | rollback | info | clean | repair")
    String task;

    public Integer call() throws Exception {
        String goal = switch (task.toLowerCase()) {
            case "migrate"  -> "migrate";
            case "rollback" -> "undo";
            case "info"     -> "info";
            case "clean"    -> "clean";
            case "repair"   -> "repair";
            default -> { error("unknown task '" + task + "'. use: migrate | rollback | info | clean | repair"); yield null; }
        };
        if (goal == null) return 2;
        var bs = BuildSystem.detect(Paths.get("."));
        banner("xpresso db:" + task, bs.name());
        return new ProcessBuilder(bs.dbTask(goal)).inheritIO().start().waitFor();
    }
}
