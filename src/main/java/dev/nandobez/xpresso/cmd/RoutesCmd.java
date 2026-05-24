package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.ProjectLayout;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.*;
import java.util.stream.Stream;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "routes", description = "List HTTP endpoints declared by @RestController + @*Mapping.")
public class RoutesCmd implements Callable<Integer> {

    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
    private static final Pattern METHOD_MAP = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping(?:\\(\"([^\"]*)\"\\))?");

    @Option(names = "--diff", description = "Show endpoints added/removed since the last git commit (HEAD).")
    boolean diff;

    public Integer call() throws Exception {
        var p = ProjectLayout.detect(Paths.get("."));
        if (diff) return runDiff(p);
        banner("xpresso routes", p.artifactId);
        record Route(String verb, String path, String handler) {}
        List<Route> routes = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(p.javaRoot)) {
            for (Path j : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(j);
                if (!src.contains("@RestController") && !src.contains("@Controller")) continue;
                String classPrefix = "";
                Matcher cm = CLASS_MAPPING.matcher(src);
                if (cm.find()) classPrefix = cm.group(1);
                Matcher mm = METHOD_MAP.matcher(src);
                while (mm.find()) {
                    String verb = mm.group(1).toUpperCase();
                    String sub = mm.group(2) == null ? "" : mm.group(2);
                    String full = (classPrefix + sub).replaceAll("//+", "/");
                    if (full.isEmpty()) full = "/";
                    int idx = src.indexOf("class ");
                    String cls = idx >= 0 ? src.substring(idx + 6).split("[\\s{]")[0] : j.getFileName().toString();
                    int hStart = mm.end();
                    String afterAnnotation = src.substring(hStart, Math.min(src.length(), hStart + 300));
                    Matcher methodMatcher = Pattern.compile("\\s+public\\s+\\S+\\s+(\\w+)\\s*\\(").matcher(afterAnnotation);
                    String method = methodMatcher.find() ? methodMatcher.group(1) : "?";
                    routes.add(new Route(verb, full, cls + "#" + method));
                }
            }
        }
        if (routes.isEmpty()) {
            info("no endpoints found.");
            return 0;
        }
        int wV = "VERB".length(), wP = "PATH".length(), wH = "HANDLER".length();
        for (Route r : routes) {
            wV = Math.max(wV, r.verb.length());
            wP = Math.max(wP, r.path.length());
            wH = Math.max(wH, r.handler.length());
        }
        System.out.printf("  " + BLD + "%-" + wV + "s  %-" + wP + "s  %s" + R + "%n", "VERB", "PATH", "HANDLER");
        for (Route r : routes) {
            String color = switch (r.verb) {
                case "GET"    -> GRN;
                case "POST"   -> YLW;
                case "PUT", "PATCH" -> BLU;
                case "DELETE" -> RED;
                default -> "";
            };
            System.out.printf("  %s%-" + wV + "s" + R + "  %-" + wP + "s  " + DIM + "%s" + R + "%n",
                color, r.verb, r.path, r.handler);
        }
        System.out.println();
        info(routes.size() + " endpoints");
        return 0;
    }

    private int runDiff(ProjectLayout p) throws Exception {
        banner("xpresso routes --diff", "vs HEAD");
        // 1. Current endpoints
        Set<String> current = collect(p, /*fromGit=*/false);
        // 2. Endpoints in HEAD (via `git show HEAD:<file>`)
        Set<String> head    = collectFromGit(p);
        var added   = new TreeSet<>(current); added.removeAll(head);
        var removed = new TreeSet<>(head);    removed.removeAll(current);
        if (added.isEmpty() && removed.isEmpty()) { info("no route changes."); return 0; }
        for (String a : added)   System.out.println("    " + GRN + "+ " + R + a);
        for (String r : removed) System.out.println("    " + RED + "- " + R + r);
        System.out.println();
        info(added.size() + " added · " + removed.size() + " removed");
        return 0;
    }

    private Set<String> collect(ProjectLayout p, boolean fromGit) throws Exception {
        // reuses the same patterns from call() — quickest way is to walk again.
        Set<String> out = new TreeSet<>();
        try (var s = Files.walk(p.javaRoot)) {
            for (Path j : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(j);
                addFrom(src, out);
            }
        }
        return out;
    }

    private Set<String> collectFromGit(ProjectLayout p) throws Exception {
        Set<String> out = new TreeSet<>();
        try (var s = Files.walk(p.javaRoot)) {
            for (Path j : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String rel = p.root.relativize(j).toString();
                var proc = new ProcessBuilder("git", "show", "HEAD:" + rel).directory(p.root.toFile())
                    .redirectErrorStream(true).start();
                String src = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();
                if (src.startsWith("fatal:")) continue;
                addFrom(src, out);
            }
        }
        return out;
    }

    private void addFrom(String src, Set<String> out) {
        if (!src.contains("@RestController") && !src.contains("@Controller")) return;
        String classPrefix = "";
        var cm = CLASS_MAPPING.matcher(src);
        if (cm.find()) classPrefix = cm.group(1);
        var mm = METHOD_MAP.matcher(src);
        while (mm.find()) {
            String verb = mm.group(1).toUpperCase();
            String sub = mm.group(2) == null ? "" : mm.group(2);
            String full = (classPrefix + sub).replaceAll("//+", "/");
            if (full.isEmpty()) full = "/";
            out.add(String.format("%-6s %s", verb, full));
        }
    }
}
