package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.gen.Templates;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "new", description = "Scaffold a new Spring Boot project (Rails-style conventions).")
public class NewCmd implements Callable<Integer> {

    @Parameters(arity = "1", description = "Project name (also the artifactId).")
    String name;

    @Option(names = "--group", defaultValue = "com.example", description = "groupId for the new project.")
    String groupId;

    @Option(names = "--java", defaultValue = "17", description = "Java release version.")
    String javaVersion;

    @Option(names = "--db", defaultValue = "h2", description = "Database driver: h2 | postgres | mysql.")
    String db;

    public Integer call() throws Exception {
        Path root = Paths.get(name);
        if (Files.exists(root)) { error("directory '" + name + "' already exists."); return 2; }
        String basePackage = groupId + "." + name.replaceAll("[^a-zA-Z0-9]", "");
        String appClass = capitalize(name) + "Application";

        banner("xpresso new " + name, basePackage);

        write(root.resolve("pom.xml"), Templates.newPom(groupId, name));

        Path pkgDir = root.resolve("src/main/java/" + basePackage.replace('.', '/'));
        write(pkgDir.resolve(appClass + ".java"), Templates.application(basePackage, appClass));

        // Convention dirs
        for (String sub : new String[]{"domain", "repository", "service", "web", "dto", "config"}) {
            Files.createDirectories(pkgDir.resolve(sub));
            write(pkgDir.resolve(sub + "/.gitkeep"), "");
        }

        Path res = root.resolve("src/main/resources");
        Files.createDirectories(res.resolve("db/migration"));
        write(res.resolve("application.yml"), Templates.applicationYml(name));
        write(res.resolve("db/migration/.gitkeep"), "");

        write(root.resolve("src/test/java/" + basePackage.replace('.', '/') + "/" + appClass + "Tests.java"),
            applicationTest(basePackage, appClass));

        write(root.resolve(".gitignore"), gitignore());
        write(root.resolve("README.md"), readme(name));

        System.out.println();
        info("project ready. next:");
        System.out.println("    " + BLD + "cd " + name + R);
        System.out.println("    " + BLD + "xpresso g model User name:string email:string" + R);
        System.out.println("    " + BLD + "xpresso s" + R);
        return 0;
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).replaceAll("[^a-zA-Z0-9]", "");
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        created(path.toString());
    }

    private static String applicationTest(String pkg, String app) {
        return """
            package %s;

            import org.junit.jupiter.api.Test;
            import org.springframework.boot.test.context.SpringBootTest;

            @SpringBootTest
            class %sTests {
                @Test void contextLoads() {}
            }
            """.formatted(pkg, app);
    }

    private static String gitignore() {
        return """
            target/
            .idea/
            .vscode/
            *.iml
            .DS_Store
            *.log
            HELP.md
            """;
    }

    private static String readme(String name) {
        return """
            # %s

            Generated with [xpresso](https://github.com/Nandobez/xpresso) · `xpresso new %s`.

            ## Run

            ```bash
            xpresso s          # spring-boot:run with devtools
            xpresso routes     # list endpoints
            xpresso g model Post title:string body:text
            ```
            """.formatted(name, name);
    }
}
