package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.BuildSystem;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // The Flyway plugin has no datasource of its own — feed it the app's application.yml.
        String url = yml("url"), user = yml("username"), pass = yml("password");
        if (url == null) {
            error("no spring.datasource.url in application.yml — cannot run db tasks");
            return 2;
        }
        cmd.add("-Dflyway.url=" + url);
        if (user != null) cmd.add("-Dflyway.user=" + user);
        cmd.add("-Dflyway.password=" + (pass == null ? "" : pass));
        cmd.add("-Dflyway.locations=filesystem:src/main/resources/db/migration");
        if (url.contains(":mem:"))
            info("note: this url is an in-memory H2 — db tasks run on a throwaway DB. Use a file/postgres datasource for persistent state.");

        return Mvn.run(cmd, bs.root(), null);
    }

    /** Reads a spring.datasource.<key> value from application.yml (best-effort). */
    private static String yml(String key) {
        try {
            String s = Files.readString(Paths.get("src/main/resources/application.yml"));
            Matcher m = Pattern.compile("(?m)^\\s*" + key + ":\\s*(.+?)\\s*$").matcher(s);
            if (m.find()) { String v = m.group(1).trim(); return v.isEmpty() ? null : v; }
        } catch (Exception ignore) {}
        return null;
    }
}
