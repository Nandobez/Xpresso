package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.BuildSystem;
import dev.nandobez.xpresso.core.ProjectLayout;
import picocli.CommandLine.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "console", aliases = {"c"},
    description = "Interactive REPL. Uses Spring Shell when it's a dep, falls back to jshell otherwise.")
public class ConsoleCmd implements Callable<Integer> {

    @Option(names = "--jshell", description = "Force the jshell backend.")
    boolean forceJshell;

    public Integer call() throws Exception {
        var p = ProjectLayout.detect(Paths.get("."));

        boolean springShell = !forceJshell && hasSpringShell(p.root);
        if (springShell) {
            banner("xpresso console", "Spring Shell · " + p.artifactId);
            info("running the app in interactive shell mode");
            var bs = BuildSystem.detect(p.root);
            var cmd = new java.util.ArrayList<>(bs.server(null, "shell"));
            // make sure Spring Shell takes over stdin
            return new ProcessBuilder(cmd).inheritIO().start().waitFor();
        }

        banner("xpresso console", "jshell · " + p.artifactId);
        info("tip: add " + BLD + "spring-shell-starter" + R + " to enable a richer Spring Shell REPL");

        Path tmp = Files.createTempFile("xpresso-cp", ".txt");
        new ProcessBuilder("mvn", "-q", "-DincludeScope=runtime",
            "dependency:build-classpath", "-Dmdep.outputFile=" + tmp)
            .directory(p.root.toFile()).inheritIO().start().waitFor();
        String cp = Files.readString(tmp).trim() + File.pathSeparator + p.root.resolve("target/classes");

        Path init = Files.createTempFile("xpresso-init", ".jsh");
        Files.writeString(init,
            "import " + p.basePackage + ".*;\n" +
            "import " + p.basePackage + ".domain.*;\n" +
            "import " + p.basePackage + ".repository.*;\n" +
            "import " + p.basePackage + ".service.*;\n" +
            "System.out.println(\"xpresso console · " + p.artifactId + "\");\n");

        info("imports preloaded for: " + p.basePackage);
        System.out.println();
        return new ProcessBuilder("jshell", "--class-path", cp, init.toString())
            .inheritIO().start().waitFor();
    }

    private static boolean hasSpringShell(Path root) {
        try {
            Path pom = root.resolve("pom.xml");
            if (Files.exists(pom)) return Files.readString(pom).contains("spring-shell");
            Path g = root.resolve("build.gradle.kts");
            if (Files.exists(g)) return Files.readString(g).contains("spring-shell");
            Path g2 = root.resolve("build.gradle");
            if (Files.exists(g2)) return Files.readString(g2).contains("spring-shell");
        } catch (Exception ignored) {}
        return false;
    }
}
