package dev.nandobez.xpresso;

import dev.nandobez.xpresso.cmd.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(
    name = "xpresso",
    mixinStandardHelpOptions = true,
    version = "xpresso 0.1.0",
    description = "Spring Boot scaffolder, Rails-style.",
    subcommands = {
        NewCmd.class, GenerateCmd.class, DbCmd.class,
        RoutesCmd.class, ServerCmd.class, ConsoleCmd.class,
        TestCmd.class, BuildCmd.class
    }
)
public class Main implements Runnable {

    public void run() { printHelp(); }

    public static void main(String[] args) {
        if (args.length == 0) { printHelp(); System.exit(0); }
        System.out.println();
        int rc = new CommandLine(new Main()).execute(args);
        System.out.println();
        System.exit(rc);
    }

    private static void printHelp() {
        System.out.println();
        System.out.println();
        System.out.println(BLD + "xpresso " + R + DIM + "0.1.0" + R + " — Spring Boot scaffolder");
        System.out.println(DIM + "  Conventions over configuration. Rails for the JVM." + R);
        System.out.println();
        System.out.println("  " + DIM + "USAGE" + R);
        System.out.println();
        System.out.println("    xpresso <command> [options]");
        System.out.println();
        System.out.println();
        System.out.println("  " + DIM + "PROJECT" + R);
        System.out.println();
        System.out.println("    " + BLD + "new <name>" + R + "          scaffold a new Spring Boot project");
        System.out.println("    " + BLD + "server, s" + R + "           run with spring-boot:run + devtools");
        System.out.println("    " + BLD + "console, c" + R + "          jshell + project classpath");
        System.out.println("    " + BLD + "build" + R + "               mvn clean package (skip tests)");
        System.out.println("    " + BLD + "test, t" + R + "             mvn test (optional pattern)");
        System.out.println("    " + BLD + "routes" + R + "              list endpoints from @RestController");
        System.out.println();
        System.out.println();
        System.out.println("  " + DIM + "GENERATE" + R);
        System.out.println();
        System.out.println("    " + BLD + "g model <Name> <fields>" + R + "     entity + repository + DTO + migration");
        System.out.println("    " + BLD + "g controller <Name>" + R + "         REST controller wired to repo");
        System.out.println("    " + BLD + "g service <Name>" + R + "            @Service skeleton");
        System.out.println("    " + BLD + "g migration <desc>" + R + "          empty Flyway migration");
        System.out.println("    " + BLD + "g resource <Name> <fields>" + R + "   model + service + controller (combo)");
        System.out.println("    " + BLD + "g auth" + R + "                      user entity + bcrypt + login/register + Spring Security");
        System.out.println("    " + BLD + "g job <Name>" + R + "                @Scheduled task");
        System.out.println("    " + BLD + "g event <Name>" + R + "              ApplicationEvent + @EventListener");
        System.out.println("    " + BLD + "g exception <Name>" + R + "          custom exception + @RestControllerAdvice");
        System.out.println("    " + BLD + "g config <Name>" + R + "             @Configuration skeleton");
        System.out.println("    " + BLD + "g component <Name>" + R + "          @Component skeleton");
        System.out.println("    " + BLD + "g test <Subject>" + R + "            JUnit5 test class");
        System.out.println();
        System.out.println();
        System.out.println("  " + DIM + "DATABASE" + R);
        System.out.println();
        System.out.println("    " + BLD + "db migrate" + R + "          run Flyway migrate");
        System.out.println("    " + BLD + "db rollback" + R + "         Flyway undo");
        System.out.println("    " + BLD + "db info" + R + "             Flyway info");
        System.out.println("    " + BLD + "db clean" + R + "            Flyway clean (destructive)");
        System.out.println();
        System.out.println();
        System.out.println("  " + DIM + "FIELD TYPES" + R);
        System.out.println();
        System.out.println("    string text int long float double decimal bool date datetime uuid json");
        System.out.println();
    }
}
