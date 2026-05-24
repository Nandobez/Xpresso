package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.*;
import java.util.stream.Stream;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "config",
    description = "List @ConfigurationProperties + their active values (from application.yml).")
public class ConfigCmd implements Callable<Integer> {

    @Option(names = {"-s", "--src"}, defaultValue = "src/main/java")
    Path src;

    @Option(names = "--yaml", defaultValue = "src/main/resources/application.yml")
    Path yaml;

    private static final Pattern PROPS = Pattern.compile("@ConfigurationProperties\\((?:prefix\\s*=\\s*)?\"([^\"]+)\"");

    public Integer call() throws Exception {
        banner("xpresso config", "scanning @ConfigurationProperties");
        if (!Files.exists(src)) { error("source dir not found: " + src); return 2; }
        Set<String> prefixes = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                Matcher m = PROPS.matcher(Files.readString(p));
                while (m.find()) prefixes.add(m.group(1));
            }
        }
        if (prefixes.isEmpty()) { info("no @ConfigurationProperties classes."); }
        else {
            for (String p : prefixes) System.out.println("    " + CYN + p + R);
            System.out.println();
        }
        if (Files.exists(yaml)) {
            System.out.println("  " + DIM + "── " + yaml + " ──" + R);
            for (String line : Files.readAllLines(yaml)) {
                if (line.trim().startsWith("#") || line.isBlank()) System.out.println("    " + DIM + line + R);
                else System.out.println("    " + line);
            }
        }
        return 0;
    }
}
