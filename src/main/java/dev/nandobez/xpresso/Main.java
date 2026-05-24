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
        TestCmd.class, BuildCmd.class,
        CleanCmd.class, CompileCmd.class, InstallCmd.class,
        DoctorCmd.class, DepsCmd.class
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
        System.out.println(BLD + "xpresso " + R + DIM + "0.1.0" + R + " — Spring Boot scaffolder & lifecycle");
        System.out.println(DIM + "  Maven + Gradle. Plays well with " + R + BLD + "jdp" + R + DIM + " (deps) + " + R + BLD + "macc" + R + DIM + " (frontend)." + R);
        System.out.println();
        System.out.println("  " + DIM + "USAGE" + R);
        System.out.println();
        System.out.println("    xpresso <command> [options]");
        System.out.println();
        System.out.println();
        System.out.println("  " + DIM + "PROJECT" + R);
        System.out.println();
        System.out.println("    " + BLD + "new <name>" + R + "          scaffold (use --gradle for Kotlin DSL)");
        System.out.println("    " + BLD + "server, s" + R + "           spring-boot:run / bootRun (auto-builds frontend)");
        System.out.println("    " + BLD + "console, c" + R + "          jshell + project classpath");
        System.out.println("    " + BLD + "clean" + R + "               mvn clean / gradle clean (--deep also wipes node_modules)");
        System.out.println("    " + BLD + "compile" + R + "             mvn -q compile (+ macc codegen if frontend)");
        System.out.println("    " + BLD + "build" + R + "               clean + package (skip tests by default)");
        System.out.println("    " + BLD + "install" + R + "             full pipeline: clean + install + macc install");
        System.out.println("    " + BLD + "test, t" + R + "             test suite (optional pattern)");
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
        System.out.println("  " + DIM + "INTEGRATIONS" + R);
        System.out.println();
        System.out.println("    " + BLD + "doctor" + R + "              delegate to " + DIM + "jdp doctor" + R + " (CVE + outdated + score)");
        System.out.println("    " + BLD + "deps" + R + "                delegate to " + DIM + "jdp list" + R);
        System.out.println();
        System.out.println();
        System.out.println("  " + DIM + "FIELD TYPES" + R);
        System.out.println();
        System.out.println("    string text int long float double decimal bool date datetime uuid json");
        System.out.println();
    }
}
