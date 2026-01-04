package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "server", aliases = {"s"},
    description = "Run the project (mvn spring-boot:run + devtools hot reload).")
public class ServerCmd implements Callable<Integer> {

    @Option(names = "--port", description = "Override server.port.")
    String port;

    @Option(names = "--profile", description = "Spring profile to activate.")
    String profile;

    public Integer call() throws Exception {
        banner("xpresso server", "spring-boot:run");
        var cmd = new java.util.ArrayList<String>();
        cmd.add("mvn"); cmd.add("spring-boot:run");
        if (port != null)    cmd.add("-Dspring-boot.run.arguments=--server.port=" + port);
        if (profile != null) cmd.add("-Dspring-boot.run.profiles=" + profile);
        return new ProcessBuilder(cmd).inheritIO().start().waitFor();
    }
}
