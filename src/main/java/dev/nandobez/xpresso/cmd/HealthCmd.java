package dev.nandobez.xpresso.cmd;

import picocli.CommandLine.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "health",
    description = "Curl /actuator/health and pretty-print the status.")
public class HealthCmd implements Callable<Integer> {

    @Option(names = {"-u", "--url"}, defaultValue = "http://localhost:8080/actuator/health")
    String url;

    public Integer call() throws Exception {
        banner("xpresso health", url);
        try {
            var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).build();
            var resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            String status = parseStatus(body);
            String mark   = "UP".equals(status) ? GRN + "● UP" + R : RED + "✗ " + status + R;
            System.out.println("    " + mark + DIM + "  (" + resp.statusCode() + ")" + R);
            System.out.println();
            for (String line : body.split(",")) System.out.println("    " + DIM + line.trim() + R);
            return "UP".equals(status) ? 0 : 1;
        } catch (Exception e) {
            error("server unreachable — start it with " + BLD + "xpresso s" + R);
            return 2;
        }
    }

    private static String parseStatus(String body) {
        int i = body.indexOf("\"status\"");
        if (i < 0) return "UNKNOWN";
        int q1 = body.indexOf('"', i + 9);
        int q2 = body.indexOf('"', q1 + 1);
        return q1 > 0 && q2 > 0 ? body.substring(q1 + 1, q2) : "UNKNOWN";
    }
}
