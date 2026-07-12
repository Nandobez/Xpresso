package dev.nandobez.xpresso.gen;

public class ExtraTemplates {

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
