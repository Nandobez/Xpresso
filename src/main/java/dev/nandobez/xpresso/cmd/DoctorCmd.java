package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "doctor", description = "Run jdp doctor (CVE + outdated + incompat).")
public class DoctorCmd implements Callable<Integer> {

    @Option(names = "--fix")
    boolean fix;

    public Integer call() throws Exception {
        banner("xpresso doctor", "delegating to jdp");
        String jdpJar = locateJdpJar();
        if (jdpJar == null) {
            error("jdp not found. Install from https://github.com/Nandobez/jdp");
            return 2;
        }
        var cmd = new java.util.ArrayList<String>();
        cmd.add("java"); cmd.add("-jar"); cmd.add(jdpJar); cmd.add("doctor");
        if (fix) cmd.add("--fix");
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }

    static String locateJdpJar() {
        for (String candidate : new String[]{
            System.getenv("JDP_HOME") == null ? null : System.getenv("JDP_HOME") + "/jdp.jar",
            System.getProperty("user.home") + "/.local/share/jdp/jdp.jar",
            "/usr/local/share/jdp/jdp.jar",
            "/tmp/jdp/target/jdp.jar",
        }) {
            if (candidate != null && Files.exists(Path.of(candidate))) return candidate;
        }
        return null;
    }
}
