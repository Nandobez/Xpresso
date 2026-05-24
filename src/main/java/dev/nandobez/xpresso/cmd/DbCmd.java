package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.BuildSystem;
import picocli.CommandLine.*;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "db",
    description = "Database tasks: migrate | rollback | info | status | clean | repair.")
public class DbCmd implements Callable<Integer> {

    @Parameters(arity = "1", description = "Task: migrate | rollback | info | status | clean | repair")
    String task;

    @Option(names = "--to", description = "Migrate up to (or down to) this version (Flyway target).")
    String target;

    public Integer call() throws Exception {
        String goal = switch (task.toLowerCase()) {
            case "migrate"        -> "migrate";
            case "rollback"       -> "undo";
            case "info", "status" -> "info";
            case "clean"          -> "clean";
            case "repair"         -> "repair";
            default -> { error("unknown task '" + task + "'. use: migrate | rollback | info | status | clean | repair"); yield null; }
        };
        if (goal == null) return 2;
        var bs = BuildSystem.detect(Paths.get("."));
        banner("xpresso db:" + task, bs.name() + (target == null ? "" : " · target=" + target));
        var cmd = new java.util.ArrayList<>(bs.dbTask(goal));
        if (target != null) cmd.add("-Dflyway.target=" + target);
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
