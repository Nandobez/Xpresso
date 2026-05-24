package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "deps", aliases = {"dependencies"},
    description = "Shortcut for `jdp list` — show declared dependencies.")
public class DepsCmd implements Callable<Integer> {

    public Integer call() throws Exception {
        String jdpJar = DoctorCmd.locateJdpJar();
        if (jdpJar == null) {
            error("jdp not found. Install from https://github.com/Nandobez/jdp");
            return 2;
        }
        return new ProcessBuilder("java", "-jar", jdpJar, "list").inheritIO().start().waitFor();
    }
}
