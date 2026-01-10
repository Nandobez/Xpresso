package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "db", description = "Database tasks: migrate, rollback, seed, info.")
public class DbCmd implements Callable<Integer> {

    @Parameters(arity = "1", description = "Task: migrate | rollback | info | clean | repair")
    String task;

    public Integer call() throws Exception {
        String mvnGoal = switch (task.toLowerCase()) {
            case "migrate"  -> "flyway:migrate";
            case "rollback" -> "flyway:undo";
            case "info"     -> "flyway:info";
            case "clean"    -> "flyway:clean";
            case "repair"   -> "flyway:repair";
            default -> { error("unknown task '" + task + "'. use: migrate | rollback | info | clean | repair"); yield null; }
        };
        if (mvnGoal == null) return 2;
        banner("xpresso db:" + task, "running " + mvnGoal);
        var pb = new ProcessBuilder("mvn", "-q", mvnGoal).inheritIO();
        return pb.start().waitFor();
    }
}
