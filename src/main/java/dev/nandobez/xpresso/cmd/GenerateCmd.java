package dev.nandobez.xpresso.cmd;

import dev.nandobez.xpresso.core.*;
import dev.nandobez.xpresso.gen.Templates;
import dev.nandobez.xpresso.gen.ExtraTemplates;
import picocli.CommandLine.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Callable;

import static dev.nandobez.xpresso.cmd.Tui.*;

@Command(name = "generate", aliases = {"g"},
    description = "Generate a model/controller/service/migration/resource.")
public class GenerateCmd implements Callable<Integer> {

    @Parameters(index = "0", description = "Kind: model | controller | service | migration | resource | auth | job | event | exception | config | component | test")
    String kind;

    @Parameters(index = "1", arity = "0..1", description = "Name (e.g. User, posts, AddIndexToUsers). Optional for 'auth'.")
    String name;

    @Parameters(index = "2..*", arity = "0..*", description = "For model/resource: fields like name:string email:string.")
    List<String> fields;

    public Integer call() throws Exception {
        var p = ProjectLayout.detect(Paths.get("."));
        if (!"auth".equals(kind == null ? "" : kind.toLowerCase()) && (name == null || name.isBlank())) {
            error("missing <name>. Try: xpresso g " + kind + " <Name>");
            return 2;
        }
        var fs = parseFields();
        String entity = name == null ? "" : capitalize(name);

        banner("xpresso g " + kind, name == null ? "" : name);

        switch (kind.toLowerCase()) {
            case "model"      -> genModel(p, entity, fs, /*migration*/ true);
            case "controller" -> genController(p, entity);
            case "service"    -> genService(p, entity);
            case "migration"  -> genMigration(p, name);
            case "resource"   -> {
                genModel(p, entity, fs, true);
                genService(p, entity);
                genController(p, entity);
            }
            case "auth"       -> genAuth(p);
            case "job"        -> write(p.packageDir("job").resolve((name.endsWith("Job") ? name : name + "Job") + ".java"),
                                       ExtraTemplates.scheduledJob(p.basePackage, name));
            case "event"      -> {
                String e  = name.endsWith("Event") ? name : name + "Event";
                String ln = (name.endsWith("Event") ? name.substring(0, name.length() - 5) : name) + "Listener";
                write(p.packageDir("event").resolve(e + ".java"), ExtraTemplates.event(p.basePackage, name));
                write(p.packageDir("event").resolve(ln + ".java"), ExtraTemplates.eventListener(p.basePackage, name));
            }
            case "exception"  -> {
                String e = name.endsWith("Exception") ? name : name + "Exception";
                write(p.packageDir("exception").resolve(e + ".java"),
                      ExtraTemplates.exception(p.basePackage, name));
                Path advice = p.packageDir("exception").resolve("GlobalExceptionHandler.java");
                if (!Files.exists(advice))
                    write(advice, ExtraTemplates.exceptionAdvice(p.basePackage));
            }
            case "config"     -> {
                String c = name.endsWith("Config") ? name : name + "Config";
                write(p.packageDir("config").resolve(c + ".java"),
                      ExtraTemplates.configClass(p.basePackage, name));
            }
            case "component"  -> write(p.packageDir("component").resolve(entity + ".java"),
                                        ExtraTemplates.componentClass(p.basePackage, entity));
            case "test"       -> {
                Path testDir = p.root.resolve("src/test/java/" + p.basePackage.replace('.', '/'));
                Files.createDirectories(testDir);
                write(testDir.resolve(entity + "Test.java"),
                      ExtraTemplates.testClass(p.basePackage, entity));
            }
            default -> { error("unknown kind '" + kind + "'. use: model | controller | service | migration | resource | auth | job | event | exception | config | component | test"); return 2; }
        }
        return 0;
    }

    private List<FieldSpec> parseFields() {
        if (fields == null) return List.of();
        return fields.stream().map(FieldSpec::parse).toList();
    }

    static void genModel(ProjectLayout p, String name, List<FieldSpec> fs, boolean withMigration) throws Exception {
        write(p.packageDir("domain").resolve(name + ".java"),
            Templates.entity(p.basePackage, name, fs));
        write(p.packageDir("repository").resolve(name + "Repository.java"),
            Templates.repository(p.basePackage, name));
        write(p.packageDir("dto").resolve(name + "Dto.java"),
            Templates.dto(p.basePackage, name, fs));
        if (withMigration) {
            Files.createDirectories(p.migrationsDir);
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            Path mig = p.migrationsDir.resolve("V" + ts + "__create_" + Templates.pluralize(Templates.snake(name)) + ".sql");
            write(mig, Templates.migrationCreate(name, fs));
        }
    }

    static void genController(ProjectLayout p, String name) throws Exception {
        write(p.packageDir("web").resolve(name + "Controller.java"),
            Templates.controller(p.basePackage, name));
    }

    static void genService(ProjectLayout p, String name) throws Exception {
        write(p.packageDir("service").resolve(name + "Service.java"),
            Templates.service(p.basePackage, name));
    }

    static void genAuth(ProjectLayout p) throws Exception {
        write(p.packageDir("domain").resolve("AppUser.java"), ExtraTemplates.authUser(p.basePackage));
        write(p.packageDir("repository").resolve("AppUserRepository.java"), ExtraTemplates.authUserRepository(p.basePackage));
        write(p.packageDir("web").resolve("AuthController.java"), ExtraTemplates.authController(p.basePackage));
        write(p.packageDir("config").resolve("SecurityConfig.java"), ExtraTemplates.securityConfig(p.basePackage));
        Files.createDirectories(p.migrationsDir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        write(p.migrationsDir.resolve("V" + ts + "__create_users.sql"), ExtraTemplates.authMigration());
    }

    static void genMigration(ProjectLayout p, String description) throws Exception {
        Files.createDirectories(p.migrationsDir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Path mig = p.migrationsDir.resolve("V" + ts + "__" + Templates.snake(description) + ".sql");
        write(mig, Templates.migrationEmpty(description));
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        boolean exists = Files.exists(path);
        Files.writeString(path, content);
        if (exists) updated(path.toString()); else created(path.toString());
    }
}
