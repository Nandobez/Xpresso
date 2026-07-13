package dev.nandobez.xpresso.cmd;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.nandobez.xpresso.cmd.Tui.*;

/** Runs a build command, capturing its output and re-rendering it in our style — no raw [INFO]/[ERROR]. */
final class Mvn {

    private static final Pattern ERR   = Pattern.compile("(\\S+\\.java):\\[(\\d+),(\\d+)\\]\\s+(.*)");
    private static final Pattern TESTS = Pattern.compile("^Tests run: \\d+.*");
    private static final Pattern ANSI  = Pattern.compile("\\u001B\\[[0-9;]*m");

    static int run(List<String> cmd, Path dir, String okMsg) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        render(out);
        if (rc == 0) { if (okMsg != null) System.out.println("    " + GRN + "✓ " + R + okMsg); }
        else         System.out.println("    " + RED + "✗ " + R + "failed" + DIM + " (mvn exit " + rc + ")" + R);
        return rc;
    }

    private static void render(String out) {
        var seen = new LinkedHashSet<String>();
        for (String raw : out.split("\n")) {
            String line = ANSI.matcher(raw).replaceAll("").stripTrailing();
            String s = line.replaceFirst("^\\[[A-Z]+\\]\\s?", "").strip();   // drop [INFO]/[ERROR]/[WARNING]
            if (s.isEmpty()) continue;

            Matcher m = ERR.matcher(s);
            if (m.find()) {
                if (seen.add(m.group(1) + m.group(2) + m.group(4)))
                    System.out.println("  " + RED + shortFile(m.group(1)) + ":" + m.group(2) + R + "  " + m.group(4));
                continue;
            }
            if (s.startsWith("both ") || s.startsWith("symbol:") || s.startsWith("location:")
                || s.startsWith("required:") || s.startsWith("found:")) {
                System.out.println("      " + DIM + s + R);
                continue;
            }
            if (TESTS.matcher(s).find()) {
                boolean bad = !s.matches(".*Failures: 0.*") || !s.matches(".*Errors: 0.*");
                System.out.println("    " + (bad ? RED : GRN) + s + R);
                continue;
            }
            if (s.startsWith("Running ")) { System.out.println("    " + DIM + s + R); continue; }
            // Flyway table + status lines
            if (s.startsWith("|") || s.startsWith("+--")) { System.out.println("    " + DIM + s + R); continue; }
            if (s.startsWith("Successfully") || s.startsWith("Migrating ") || s.startsWith("Creating ")
                || s.startsWith("Current version") || s.startsWith("Schema history") || s.contains("No migration")) {
                System.out.println("    " + DIM + s + R);
                continue;
            }
            // everything else (BUILD, Total time, Downloading, Re-run, Help, Scanning, plugin banners) is dropped
        }
    }

    private static String shortFile(String path) {
        int i = path.replace('\\', '/').lastIndexOf("/java/");
        return i >= 0 ? path.substring(i + 6) : path.substring(Math.max(0, path.lastIndexOf('/') + 1));
    }
}
