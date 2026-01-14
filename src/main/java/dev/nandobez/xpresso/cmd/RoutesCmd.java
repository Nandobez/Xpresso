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

    public Integer call() throws Exception {
        var p = ProjectLayout.detect(Paths.get("."));
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
}
