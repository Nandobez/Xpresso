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

    @Parameters(index = "2..*", arity = "0..*", description = "For model/resource: fields like name:string email:string!unique!email user:belongs_to.")
    List<String> fields;

    @Option(names = "--method", defaultValue = "GET", description = "For endpoint: HTTP verb.")
    String httpMethod;

    @Option(names = "--path", defaultValue = "", description = "For endpoint: URL path suffix.")
    String urlPath;

    @Option(names = "--tdd", description = "Also generate a JUnit5 test class.")
    boolean tdd;

    @Option(names = "--dry-run", description = "Print what would be generated without writing any file.")
    boolean dryRun;

    @Option(names = "--count", defaultValue = "20", description = "For seed: how many rows to insert on dev startup.")
    int count;

    @Option(names = "--seed", description = "For resource: also generate a dev seed factory + seeder.")
    boolean withSeed;

    /** Set from --dry-run so the static write() helpers can preview instead of writing. */
    static boolean DRY = false;

    public Integer call() throws Exception {
        DRY = dryRun;
        var p = ProjectLayout.detect(Paths.get("."));
        if (!"auth".equals(kind == null ? "" : kind.toLowerCase()) && (name == null || name.isBlank())) {
            error("missing <name>. Try: xpresso g " + kind + " <Name>");
            return 2;
        }
        // Skip field parsing for kinds that don't take name:type fields.
        boolean wantsFields = switch (kind.toLowerCase()) {
            case "model", "resource", "seed" -> true;
            default -> false;
        };
        List<FieldSpec> fs;
        try {
            fs = wantsFields ? parseFields() : java.util.List.of();
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return 2;
        }
        String entity = name == null ? "" : capitalize(name);

        banner("xpresso g " + kind, name == null ? "" : name);

        switch (kind.toLowerCase()) {
            case "model"      -> { genModel(p, entity, fs, /*migration*/ true); if (tdd) genTest(p, entity); }
            case "controller" -> { genController(p, entity, fs);                if (tdd) genTest(p, entity + "Controller"); }
            case "service"    -> { genService(p, entity);                       if (tdd) genTest(p, entity + "Service"); }
            case "migration"  -> genMigration(p, name);
            case "resource"   -> {
                genModel(p, entity, fs, true);
                genService(p, entity);
                genController(p, entity, fs);
                // A full CRUD resource ships with a shared error handler and tests by default.
                Path advice = p.packageDir("exception").resolve("GlobalExceptionHandler.java");
                if (!Files.exists(advice)) write(advice, ExtraTemplates.exceptionAdvice(p.basePackage));
                genTest(p, entity + "Service");
                genTest(p, entity + "Controller");
                if (tdd) genTest(p, entity);
                if (withSeed) genSeed(p, entity, fs, count);
            }
            case "seed"       -> genSeed(p, entity, fs, count);
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
            case "endpoint"   -> {
                String action = (fields != null && !fields.isEmpty()) ? fields.get(0) : httpMethod.toLowerCase();
                genEndpoint(p, name, action, httpMethod, urlPath);
            }
            default -> { error("unknown kind '" + kind + "'. use: model | controller | service | migration | resource | auth | job | event | exception | config | component | test"); return 2; }
        }
        flush();
        return 0;
    }

    /** Timestamp version (yyyyMMddHHmmss) bumped past any existing migration so versions never collide. */
    private static String nextVersion(Path dir) {
        var used = new java.util.HashSet<Long>();
        try {
            Files.list(dir).forEach(f -> {
                var m = java.util.regex.Pattern.compile("^V(\\d+)__").matcher(f.getFileName().toString());
                if (m.find()) used.add(Long.parseLong(m.group(1)));
            });
        } catch (Exception ignore) {}
        long v = Long.parseLong(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        while (used.contains(v)) v++;
        return String.valueOf(v);
    }

    private List<FieldSpec> parseFields() {
        if (fields == null) return List.of();
        var out = new java.util.ArrayList<FieldSpec>();
        for (String f : fields) {
            try {
                out.add(FieldSpec.parse(f));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid field '" + f
                    + "' — expected name:type (e.g. title:string, price:decimal, user:belongs_to)");
            }
        }
        return out;
    }

    static void genModel(ProjectLayout p, String name, List<FieldSpec> fs, boolean withMigration) throws Exception {
        write(p.packageDir("domain").resolve(name + ".java"),
            Templates.entity(p.basePackage, name, fs));
        write(p.packageDir("repository").resolve(name + "Repository.java"),
            Templates.repository(p.basePackage, name));
        write(p.packageDir("dto").resolve(name + "Request.java"),
            Templates.request(p.basePackage, name, fs));
        write(p.packageDir("dto").resolve(name + "Response.java"),
            Templates.response(p.basePackage, name, fs));
        if (withMigration) {
            Files.createDirectories(p.migrationsDir);
            String ts = nextVersion(p.migrationsDir);
            Path mig = p.migrationsDir.resolve("V" + ts + "__create_" + Templates.pluralize(Templates.snake(name)) + ".sql");
            write(mig, Templates.migrationCreate(name, fs));
        }
    }

    static void genController(ProjectLayout p, String name, List<FieldSpec> fs) throws Exception {
        write(p.packageDir("web").resolve(name + "Controller.java"),
            Templates.controller(p.basePackage, name, fs));
        hintMissingDeps(p);
    }

    static void genService(ProjectLayout p, String name) throws Exception {
        write(p.packageDir("service").resolve(name + "Service.java"),
            Templates.service(p.basePackage, name));
    }

    static void genSeed(ProjectLayout p, String name, List<FieldSpec> fs, int count) throws Exception {
        write(p.packageDir("dev").resolve(name + "Factory.java"),
            ExtraTemplates.seedFactory(p.basePackage, name, fs));
        write(p.packageDir("dev").resolve(name + "Seeder.java"),
            ExtraTemplates.seeder(p.basePackage, name, count));
        if (!DRY) {
            Path pom = p.root.resolve("pom.xml");
            if (Files.exists(pom) && ensureDatafaker(pom))
                GEN.computeIfAbsent("pom", k -> new java.util.ArrayList<>()).add("+datafaker");
        }
        info("run with the 'dev' profile to seed: SPRING_PROFILES_ACTIVE=dev xpresso s");
    }

    /** Adds the datafaker dependency to the pom if missing. Returns true if changed. */
    private static boolean ensureDatafaker(Path pom) throws Exception {
        String xml = Files.readString(pom);
        if (xml.contains("<artifactId>datafaker</artifactId>")) return false;
        int idx = xml.lastIndexOf("</dependencies>");
        if (idx < 0) return false;
        String dep = ""
            + "        <dependency>\n"
            + "            <groupId>net.datafaker</groupId>\n"
            + "            <artifactId>datafaker</artifactId>\n"
            + "            <version>2.4.2</version>\n"
            + "        </dependency>\n    ";
        Files.writeString(pom, xml.substring(0, idx) + dep + xml.substring(idx));
        return true;
    }

    static void genTest(ProjectLayout p, String subject) throws Exception {
        Path testDir = p.root.resolve("src/test/java/" + p.basePackage.replace('.', '/'));
        Path out = testDir.resolve(subject + "Test.java");
        record(out);
        if (DRY) return;
        Files.createDirectories(testDir);
        if (Files.exists(out)) return;
        Files.writeString(out, ExtraTemplates.testClass(p.basePackage, subject));
    }

    /** Detect missing validation / openapi deps via jdp doctor result; print hint only. */
    static void hintMissingDeps(ProjectLayout p) {
        try {
            Path pom = p.root.resolve("pom.xml");
            if (!Files.exists(pom)) return;
            String body = Files.readString(pom);
            if (!body.contains("starter-validation"))
                info("hint: run " + BLD + "xpresso deps" + R + " — `starter-validation` is recommended for @Valid");
            if (!body.contains("springdoc"))
                info("hint: run " + BLD + "jdp add springdoc-openapi-starter-webmvc-ui" + R + " for Swagger UI");
        } catch (Exception ignored) {}
    }

    static void genEndpoint(ProjectLayout p, String controllerHint, String action, String method, String path) throws Exception {
        String want = capitalize(controllerHint) + "Controller";
        Path target = null;
        try (var s = Files.walk(p.javaRoot)) {
            for (Path c : (Iterable<Path>) s.filter(x -> x.toString().endsWith(want + ".java"))::iterator) {
                target = c; break;
            }
        }
        if (target == null) { error("controller not found: " + want + ".java"); return; }
        String body = Files.readString(target);
        int closeBrace = body.lastIndexOf('}');
        if (closeBrace < 0) { error("malformed controller"); return; }

        String mapping = Templates.capitalize(method.toLowerCase()) + "Mapping" +
            (path.isBlank() ? "" : "(\"" + path + "\")");
        String pathParams = "";
        for (var m = java.util.regex.Pattern.compile("\\{(\\w+)\\}").matcher(path); m.find();) {
            pathParams += (pathParams.isEmpty() ? "" : ", ") + "@PathVariable Long " + m.group(1);
        }

        String snippet = """

                @%s
                public org.springframework.http.ResponseEntity<?> %s(%s) {
                    // TODO implement
                    return org.springframework.http.ResponseEntity.ok().build();
                }
            """.formatted(mapping, action, pathParams);

        String newBody = body.substring(0, closeBrace) + snippet + body.substring(closeBrace);
        Files.writeString(target, newBody);
        updated(target.toString());
    }

    static void genAuth(ProjectLayout p) throws Exception {
        write(p.packageDir("domain").resolve("AppUser.java"), ExtraTemplates.authUser(p.basePackage));
        write(p.packageDir("repository").resolve("AppUserRepository.java"), ExtraTemplates.authUserRepository(p.basePackage));
        write(p.packageDir("web").resolve("AuthController.java"), ExtraTemplates.authController(p.basePackage));
        write(p.packageDir("config").resolve("SecurityConfig.java"), ExtraTemplates.securityConfig(p.basePackage));
        Files.createDirectories(p.migrationsDir);
        String ts = nextVersion(p.migrationsDir);
        write(p.migrationsDir.resolve("V" + ts + "__create_users.sql"), ExtraTemplates.authMigration());
    }

    static void genMigration(ProjectLayout p, String description) throws Exception {
        Files.createDirectories(p.migrationsDir);
        String ts = nextVersion(p.migrationsDir);
        Path mig = p.migrationsDir.resolve("V" + ts + "__" + Templates.snake(description) + ".sql");
        write(mig, Templates.migrationEmpty(description));
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void write(Path path, String content) throws Exception {
        record(path);
        if (DRY) return;
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    // ---- grouped generation summary ----

    private static final java.util.Map<String, java.util.List<String>> GEN = new java.util.LinkedHashMap<>();
    private static final String[] LAYER_ORDER =
        {"domain", "repository", "dto", "service", "web", "config", "exception", "dev", "job", "event", "migration", "test"};

    /** Record a generated file under its layer (folder), instead of printing a full path per file. */
    private static void record(Path p) {
        String s = p.toString().replace('\\', '/');
        String file = p.getFileName().toString();
        String name = file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
        String layer;
        if (s.contains("/db/migration/")) { layer = "migration"; name = name.replaceFirst("^V\\d+__", "V…__"); }
        else if (s.contains("/src/test/")) layer = "test";
        else { Path par = p.getParent(); layer = par == null ? "misc" : par.getFileName().toString(); }
        GEN.computeIfAbsent(layer, k -> new java.util.ArrayList<>()).add(name);
    }

    /** Print the grouped summary (layer → files) and clear. */
    private void flush() {
        if (GEN.isEmpty()) return;
        int w = 0, total = 0;
        for (var e : GEN.entrySet()) { w = Math.max(w, e.getKey().length()); total += e.getValue().size(); }
        var seen = new java.util.LinkedHashSet<String>();
        for (String l : LAYER_ORDER) if (GEN.containsKey(l)) { printLayer(l, w); seen.add(l); }
        for (String l : GEN.keySet()) if (seen.add(l)) printLayer(l, w);
        System.out.println();
        System.out.println("    " + DIM + total + " files" + (DRY ? " · dry-run, nothing written" : "") + R);
        GEN.clear();
    }

    private void printLayer(String layer, int w) {
        System.out.println("    " + DIM + pad(layer, w) + R + "  " + String.join(DIM + " · " + R, GEN.get(layer)));
    }

    private static String pad(String s, int w) { return s.length() >= w ? s : s + " ".repeat(w - s.length()); }
}
