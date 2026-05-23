package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.ProjectLayout;
import picocli.CommandLine.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "console", aliases = {"c"},
    description = "Open jshell with the project's compile classpath preloaded.")
public class ConsoleCmd implements Callable<Integer> {

    public Integer call() throws Exception {
        var p = ProjectLayout.detect(Paths.get("."));
        banner("xpresso console", "jshell + compile classpath");

        // Build classpath via mvn
        Path tmp = Files.createTempFile("xpresso-cp", ".txt");
        new ProcessBuilder("mvn", "-q", "-DincludeScope=runtime",
            "dependency:build-classpath", "-Dmdep.outputFile=" + tmp)
            .directory(p.root.toFile()).inheritIO().start().waitFor();
        String cp = Files.readString(tmp).trim() + File.pathSeparator + p.root.resolve("target/classes");

        // Build a startup script with import * for the project's packages
        Path init = Files.createTempFile("xpresso-init", ".jsh");
        Files.writeString(init,
            "import " + p.basePackage + ".*;\n" +
            "import " + p.basePackage + ".domain.*;\n" +
            "import " + p.basePackage + ".repository.*;\n" +
            "import " + p.basePackage + ".service.*;\n" +
            "System.out.println(\"xpresso console · " + p.artifactId + "\");\n");

        info("classpath: " + p.root.resolve("target/classes"));
        info("imports preloaded for: " + p.basePackage);
        System.out.println();
        return new ProcessBuilder("jshell", "--class-path", cp, init.toString())
            .inheritIO().start().waitFor();
    }
}
