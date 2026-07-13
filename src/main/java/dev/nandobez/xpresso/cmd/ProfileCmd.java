package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "profile", description = "Manage Spring profiles (application-<name>.yml).")
public class ProfileCmd implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Subcommand: add | list | rm")
    String sub;

    @Parameters(index = "1", arity = "0..1", description = "Profile name (for add / rm)")
    String name;

    public Integer call() throws Exception {
        if (sub == null) {
            System.out.println();
            System.out.println("  " + BLD + "profile" + R + DIM + " — manage Spring profiles (application-<name>.yml)" + R);
            System.out.println();
            System.out.println("    " + BLD + "xpresso profile list" + R + DIM + "        show profiles" + R);
            System.out.println("    " + BLD + "xpresso profile add <name>" + R + DIM + "  create application-<name>.yml" + R);
            System.out.println("    " + BLD + "xpresso profile rm <name>" + R + DIM + "   delete it" + R);
            return 2;
        }
        Path resDir = Paths.get("src/main/resources");
        Files.createDirectories(resDir);
        switch (sub.toLowerCase()) {
            case "add" -> {
                if (name == null) { error("usage: xpresso profile add <name>"); return 2; }
                Path f = resDir.resolve("application-" + name + ".yml");
                if (Files.exists(f)) { error(f + " already exists"); return 1; }
                Files.writeString(f, """
                    # Profile: %s
                    # Activate with: --spring.profiles.active=%s
                    spring:
                      jpa:
                        show-sql: false
                    """.formatted(name, name));
                System.out.println("    " + GRN + "wrote" + R + "  " + f);
                info("activate with: " + BLD + "xpresso s --profile " + name + R);
                return 0;
            }
            case "list" -> {
                banner("xpresso profile list", resDir.toString());
                try (var s = Files.list(resDir)) {
                    s.filter(p -> p.getFileName().toString().startsWith("application-")
                               && p.toString().endsWith(".yml"))
                     .forEach(p -> {
                        String n = p.getFileName().toString();
                        String only = n.substring("application-".length(), n.length() - 4);
                        System.out.println("    " + GRN + "●" + R + " " + only);
                     });
                }
                return 0;
            }
            case "rm" -> {
                if (name == null) { error("usage: xpresso profile rm <name>"); return 2; }
                Path f = resDir.resolve("application-" + name + ".yml");
                if (Files.deleteIfExists(f)) {
                    System.out.println("    " + YLW + "removed" + R + "  " + f);
                    return 0;
                }
                error("not found: " + f);
                return 2;
            }
            default -> { error("unknown subcommand: " + sub + ". use: add | list | rm"); return 2; }
        }
    }
}
