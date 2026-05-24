package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.*;
import java.util.stream.Stream;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "beans", description = "List Spring beans (@Service / @Repository / @Component / @Configuration / @RestController).")
public class BeansCmd implements Callable<Integer> {

    @Option(names = {"-s", "--src"}, defaultValue = "src/main/java")
    Path src;

    @Option(names = "--type", description = "Filter by stereotype (service / repository / component / config / controller).")
    String typeFilter;

    private static final Pattern CLASS_NAME = Pattern.compile("(?:class|interface)\\s+(\\w+)");
    private static final Pattern PACKAGE    = Pattern.compile("^package\\s+([^;]+);", Pattern.MULTILINE);

    record Bean(String name, String stereotype, String pkg) {}

    public Integer call() throws Exception {
        banner("xpresso beans", src.toString());
        if (!Files.exists(src)) { error("source dir not found: " + src); return 2; }
        List<Bean> beans = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String body = Files.readString(p);
                String stereotype = detectStereotype(body);
                if (stereotype == null) continue;
                if (typeFilter != null && !stereotype.equalsIgnoreCase(typeFilter)) continue;
                Matcher cn = CLASS_NAME.matcher(body);
                Matcher pk = PACKAGE.matcher(body);
                if (cn.find() && pk.find()) beans.add(new Bean(cn.group(1), stereotype, pk.group(1).trim()));
            }
        }
        if (beans.isEmpty()) { info("no beans found."); return 0; }

        int w1 = "BEAN".length(), w2 = "@TYPE".length(), w3 = "PACKAGE".length();
        for (Bean b : beans) {
            w1 = Math.max(w1, b.name.length());
            w2 = Math.max(w2, b.stereotype.length() + 1);
            w3 = Math.max(w3, b.pkg.length());
        }
        System.out.printf("  " + BLD + "%-" + w1 + "s  %-" + w2 + "s  %s" + R + "%n", "BEAN", "@TYPE", "PACKAGE");
        for (Bean b : beans) {
            String color = switch (b.stereotype) {
                case "RestController", "Controller" -> GRN;
                case "Service"                      -> CYN;
                case "Repository"                   -> YLW;
                case "Configuration"                -> "[35m";
                default                             -> "";
            };
            System.out.printf("  " + BLD + "%-" + w1 + "s" + R + "  %s@%-" + (w2 - 1) + "s" + R + "  " + DIM + "%s" + R + "%n",
                b.name, color, b.stereotype, b.pkg);
        }
        System.out.println();
        info(beans.size() + " beans");
        return 0;
    }

    private static String detectStereotype(String body) {
        for (String s : new String[]{"RestController", "Controller", "Service", "Repository", "Component", "Configuration"}) {
            if (body.contains("@" + s + "\n") || body.contains("@" + s + " ")) return s;
            if (body.contains("@" + s + "(")) return s;
        }
        return null;
    }
}
