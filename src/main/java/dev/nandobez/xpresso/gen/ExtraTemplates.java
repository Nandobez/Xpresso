package dev.nandobez.xpresso.gen;

import dev.nandobez.xpresso.core.FieldSpec;
import java.util.List;

public class ExtraTemplates {

    // ---------------- Seed: factory + dev seeder ----------------

    /** A faker expression for a field, chosen by name heuristics first, then Java type. */
    static String fakerExpr(FieldSpec f) {
        String n = f.name().toLowerCase();
        String byName = switch (n) {
            case "email"      -> "faker.internet().emailAddress()";
            case "username"   -> "faker.name().username()";
            case "name"       -> "faker.name().fullName()";
            case "firstname"  -> "faker.name().firstName()";
            case "lastname"   -> "faker.name().lastName()";
            case "title"      -> "faker.book().title()";
            case "city"       -> "faker.address().city()";
            case "country"    -> "faker.address().country()";
            case "company"    -> "faker.company().name()";
            case "color"      -> "faker.color().name()";
            default -> null;
        };
        if (byName != null) return byName;
        if (n.contains("email"))                       return "faker.internet().emailAddress()";
        if (n.contains("firstname"))                   return "faker.name().firstName()";
        if (n.contains("lastname"))                    return "faker.name().lastName()";
        if (n.contains("username"))                    return "faker.name().username()";
        if (n.endsWith("name"))                        return "faker.name().fullName()";
        if (n.contains("phone"))                       return "faker.phoneNumber().cellPhone()";
        if (n.contains("address"))                     return "faker.address().fullAddress()";
        if (n.contains("url") || n.contains("website") || n.contains("link"))
                                                       return "faker.internet().url()";
        if (n.contains("title"))                       return "faker.book().title()";
        if (n.contains("description") || n.contains("body") || n.contains("content")
            || n.contains("summary") || n.contains("bio") || n.contains("text") || n.contains("note"))
                                                       return "faker.lorem().paragraph()";
        // numeric-by-name (only when the java type is numeric)
        boolean intType  = f.javaType().equals("Integer") || f.javaType().equals("Long");
        if (intType && (n.contains("age")))            return "faker.number().numberBetween(18, 80)";
        if (intType && (n.contains("year")))           return "faker.number().numberBetween(1990, 2024)";
        if (intType && (n.contains("qty") || n.contains("quantity") || n.contains("stock") || n.contains("count")))
                                                       return "faker.number().numberBetween(0, 100)";
        // type fallback
        return switch (f.javaType()) {
            case "String"     -> "faker.lorem().word()";
            case "Integer"    -> "faker.number().numberBetween(1, 1000)";
            case "Long"       -> "faker.number().randomNumber()";
            case "BigDecimal" -> "new java.math.BigDecimal(faker.commerce().price())";
            case "Double"     -> "faker.number().randomDouble(2, 1, 1000)";
            case "Float"      -> "(float) faker.number().randomDouble(2, 1, 1000)";
            case "Boolean"    -> "faker.bool().bool()";
            case "LocalDate"  -> "java.time.LocalDate.now().minusDays(faker.number().numberBetween(0, 3650))";
            case "Instant"    -> "java.time.Instant.now().minusSeconds(faker.number().numberBetween(0, 31536000))";
            case "UUID"       -> "java.util.UUID.randomUUID()";
            default           -> "faker.lorem().word()";
        };
    }

    /** Factory that builds one fake entity. Scalar fields only; relations are left for you to wire. */
    public static String seedFactory(String pkg, String name, List<FieldSpec> fields) {
        var sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".dev;\n\n");
        sb.append("import ").append(pkg).append(".domain.").append(name).append(";\n");
        sb.append("import net.datafaker.Faker;\n\n");
        sb.append("/** Builds fake ").append(name).append(" instances for local seeding. Tune the field values as needed. */\n");
        sb.append("public class ").append(name).append("Factory {\n\n");
        sb.append("    private static final Faker faker = new Faker();\n\n");
        sb.append("    public static ").append(name).append(" one() {\n");
        sb.append("        ").append(name).append(" e = new ").append(name).append("();\n");
        for (FieldSpec f : fields) {
            if (f.isRelation()) {
                sb.append("        // TODO: link ").append(f.name()).append(" (").append(f.kind()).append(") to an existing row\n");
                continue;
            }
            sb.append("        e.set").append(Templates.capitalize(f.name())).append("(").append(fakerExpr(f)).append(");\n");
        }
        sb.append("        return e;\n");
        sb.append("    }\n}\n");
        return sb.toString();
    }

    /** Dev-profile CommandLineRunner that seeds N rows on startup when the table is empty. */
    public static String seeder(String pkg, String name, int count) {
        return """
            package %s.dev;

            import %s.domain.%s;
            import %s.repository.%sRepository;
            import org.springframework.boot.CommandLineRunner;
            import org.springframework.context.annotation.Profile;
            import org.springframework.stereotype.Component;

            /** Seeds %d %s rows at startup under the 'dev' profile, only if the table is empty. */
            @Component
            @Profile("dev")
            public class %sSeeder implements CommandLineRunner {

                private final %sRepository repo;

                public %sSeeder(%sRepository repo) {
                    this.repo = repo;
                }

                @Override
                public void run(String... args) {
                    if (repo.count() > 0) return;
                    for (int i = 0; i < %d; i++) repo.save(%sFactory.one());
                    System.out.println("[seed] inserted %d %s rows");
                }
            }
            """.formatted(pkg, pkg, name, pkg, name, count, name, name, name, name, name, count, name, count, name);
    }


    public static String scheduledJob(String pkg, String name) {
        String cls = name.endsWith("Job") ? name : name + "Job";
        return """
            package %s.job;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.scheduling.annotation.Scheduled;
            import org.springframework.stereotype.Component;

            @Component
            public class %s {

                private static final Logger log = LoggerFactory.getLogger(%s.class);

                /** Runs every minute. Tune the cron expression as needed. */
                @Scheduled(cron = "0 * * * * *")
                public void run() {
                    log.info("%s tick");
                }
            }
            """.formatted(pkg, cls, cls, cls);
    }

    public static String event(String pkg, String name) {
        String cls = name.endsWith("Event") ? name : name + "Event";
        return """
            package %s.event;

            import org.springframework.context.ApplicationEvent;

            public class %s extends ApplicationEvent {
                private final String payload;

                public %s(Object source, String payload) {
                    super(source);
                    this.payload = payload;
                }

                public String getPayload() { return payload; }
            }
            """.formatted(pkg, cls, cls);
    }

    public static String eventListener(String pkg, String name) {
        String evt = name.endsWith("Event") ? name : name + "Event";
        String lst = (name.endsWith("Event") ? name.substring(0, name.length() - 5) : name) + "Listener";
        return """
            package %s.event;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.context.event.EventListener;
            import org.springframework.stereotype.Component;

            @Component
            public class %s {

                private static final Logger log = LoggerFactory.getLogger(%s.class);

                @EventListener
                public void handle(%s event) {
                    log.info("received %s: {}", event.getPayload());
                }
            }
            """.formatted(pkg, lst, lst, evt, evt);
    }

    public static String exception(String pkg, String name) {
        String cls = name.endsWith("Exception") ? name : name + "Exception";
        return """
            package %s.exception;

            public class %s extends RuntimeException {
                public %s(String message) { super(message); }
                public %s(String message, Throwable cause) { super(message, cause); }
            }
            """.formatted(pkg, cls, cls, cls);
    }

    public static String exceptionAdvice(String pkg) {
        return """
            package %s.exception;

            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.MethodArgumentNotValidException;
            import org.springframework.web.bind.annotation.*;

            import java.util.LinkedHashMap;
            import java.util.Map;
            import java.util.NoSuchElementException;

            @RestControllerAdvice
            public class GlobalExceptionHandler {

                /** Bean-validation failures on @Valid request bodies -> 400 with per-field messages. */
                @ExceptionHandler(MethodArgumentNotValidException.class)
                public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    e.getBindingResult().getFieldErrors()
                        .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
                    return ResponseEntity.badRequest().body(Map.of(
                        "error", "validation_failed",
                        "fields", fields
                    ));
                }

                @ExceptionHandler(NoSuchElementException.class)
                public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException e) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "not_found",
                        "message", e.getMessage() == null ? "" : e.getMessage()
                    ));
                }

                @ExceptionHandler(RuntimeException.class)
                public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException e) {
                    return ResponseEntity.status(500).body(Map.of(
                        "error", e.getClass().getSimpleName(),
                        "message", e.getMessage() == null ? "" : e.getMessage()
                    ));
                }
            }
            """.formatted(pkg);
    }

    public static String configClass(String pkg, String name) {
        String cls = name.endsWith("Config") ? name : name + "Config";
        return """
            package %s.config;

            import org.springframework.context.annotation.Configuration;

            @Configuration
            public class %s {
                // beans + @Value injections go here
            }
            """.formatted(pkg, cls);
    }

    public static String componentClass(String pkg, String name) {
        return """
            package %s.component;

            import org.springframework.stereotype.Component;

            @Component
            public class %s {
                // dependencies + business logic
            }
            """.formatted(pkg, name);
    }

    public static String testClass(String pkg, String subject) {
        String cls = subject + "Test";
        return """
            package %s;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            class %s {

                @Test
                void smoke() {
                    assertTrue(true);
                }
            }
            """.formatted(pkg, cls);
    }

    // ---------------- Auth scaffold ----------------
    public static String authUser(String pkg) {
        return """
            package %s.domain;

            import jakarta.persistence.*;

            @Entity
            @Table(name = "users")
            public class AppUser {

                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

                @Column(unique = true, nullable = false)
                private String email;

                @Column(nullable = false)
                private String passwordHash;

                @Column(nullable = false)
                private String role = "USER";

                public Long   getId() { return id; }                          public void setId(Long id) { this.id = id; }
                public String getEmail() { return email; }                    public void setEmail(String e) { this.email = e; }
                public String getPasswordHash() { return passwordHash; }      public void setPasswordHash(String p) { this.passwordHash = p; }
                public String getRole() { return role; }                      public void setRole(String r) { this.role = r; }
            }
            """.formatted(pkg);
    }

    public static String authUserRepository(String pkg) {
        return """
            package %s.repository;

            import %s.domain.AppUser;
            import org.springframework.data.jpa.repository.JpaRepository;
            import java.util.Optional;

            public interface AppUserRepository extends JpaRepository<AppUser, Long> {
                Optional<AppUser> findByEmail(String email);
            }
            """.formatted(pkg, pkg);
    }

    public static String authController(String pkg) {
        return """
            package %s.web;

            import %s.domain.AppUser;
            import %s.repository.AppUserRepository;
            import org.springframework.http.ResponseEntity;
            import org.springframework.security.crypto.password.PasswordEncoder;
            import org.springframework.web.bind.annotation.*;

            import java.util.Map;

            @RestController
            @RequestMapping("/auth")
            public class AuthController {

                private final AppUserRepository repo;
                private final PasswordEncoder encoder;

                public AuthController(AppUserRepository repo, PasswordEncoder encoder) {
                    this.repo = repo;
                    this.encoder = encoder;
                }

                @PostMapping("/register")
                public AppUser register(@RequestBody Map<String, String> body) {
                    var user = new AppUser();
                    user.setEmail(body.get("email"));
                    user.setPasswordHash(encoder.encode(body.get("password")));
                    return repo.save(user);
                }

                @PostMapping("/login")
                public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> body) {
                    boolean valid = repo.findByEmail(body.get("email"))
                        .filter(u -> encoder.matches(body.get("password"), u.getPasswordHash()))
                        .isPresent();
                    if (!valid) return ResponseEntity.status(401).body(Map.of("error", "bad credentials"));
                    // SECURITY: token issuance is not implemented. Wire a real JWT provider
                    // before enabling login. Never ship a placeholder token as if it were valid.
                    return ResponseEntity.status(501).body(Map.of(
                        "error", "token issuance not implemented — integrate a JWT provider (e.g. jjwt / spring-security-oauth2)"));
                }
            }
            """.formatted(pkg, pkg, pkg);
    }

    public static String securityConfig(String pkg) {
        return """
            package %s.config;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.security.config.annotation.web.builders.HttpSecurity;
            import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
            import org.springframework.security.crypto.password.PasswordEncoder;
            import org.springframework.security.web.SecurityFilterChain;

            @Configuration
            public class SecurityConfig {

                @Bean
                public PasswordEncoder passwordEncoder() {
                    return new BCryptPasswordEncoder();
                }

                @Bean
                public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                    http
                        .csrf(csrf -> csrf.disable())
                        .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/auth/**").permitAll()
                            .anyRequest().authenticated()
                        )
                        .httpBasic(b -> {});
                    return http.build();
                }
            }
            """.formatted(pkg);
    }

    public static String authMigration() {
        return """
            -- Auto-generated by xpresso (auth)
            CREATE TABLE users (
                id BIGSERIAL PRIMARY KEY,
                email VARCHAR(255) UNIQUE NOT NULL,
                password_hash VARCHAR(255) NOT NULL,
                role VARCHAR(64) NOT NULL DEFAULT 'USER'
            );
            """;
    }
}
