package dev.nandobez.xpresso.gen;

import dev.nandobez.xpresso.core.FieldSpec;
import java.util.*;
import java.util.stream.Collectors;

public class Templates {

    public static String pluralize(String s) {
        String low = s.toLowerCase();
        if (low.endsWith("y") && low.length() > 1 && !"aeiou".contains(String.valueOf(low.charAt(low.length() - 2))))
            return low.substring(0, low.length() - 1) + "ies";
        if (low.endsWith("s") || low.endsWith("x") || low.endsWith("z") || low.endsWith("ch") || low.endsWith("sh"))
            return low + "es";
        return low + "s";
    }

    public static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String snake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    // ---------------- Entity ----------------
    public static String entity(String pkg, String name, List<FieldSpec> fields) {
        String table = pluralize(snake(name));
        var imports = new TreeSet<String>();
        for (var f : fields) {
            String imp = f.importLine();
            if (imp != null) imports.add(imp);
        }
        String importBlock = imports.isEmpty() ? "" : String.join("\n", imports) + "\n\n";
        String fieldDecls = fields.stream()
            .map(f -> "    @Column(name = \"" + snake(f.name()) + "\")\n    private " + f.javaType() + " " + f.name() + ";\n")
            .collect(Collectors.joining());
        String getters = fields.stream()
            .map(f -> "    public " + f.javaType() + " " + getter(f) + "() { return " + f.name() + "; }\n"
                    + "    public void set" + capitalize(f.name()) + "(" + f.javaType() + " v) { this." + f.name() + " = v; }\n")
            .collect(Collectors.joining());
        return """
            package %s.domain;

            import jakarta.persistence.*;
            %s@Entity
            @Table(name = "%s")
            public class %s {

                @Id
                @GeneratedValue(strategy = GenerationType.IDENTITY)
                private Long id;

            %s
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }

            %s}
            """.formatted(pkg, importBlock, table, name, fieldDecls, getters);
    }

    private static String getter(FieldSpec f) {
        if (f.javaType().equals("Boolean")) return "is" + capitalize(f.name());
        return "get" + capitalize(f.name());
    }

    // ---------------- Repository ----------------
    public static String repository(String pkg, String name) {
        return """
            package %s.repository;

            import %s.domain.%s;
            import org.springframework.data.jpa.repository.JpaRepository;

            public interface %sRepository extends JpaRepository<%s, Long> {
            }
            """.formatted(pkg, pkg, name, name, name);
    }

    // ---------------- DTO ----------------
    public static String dto(String pkg, String name, List<FieldSpec> fields) {
        var imports = new TreeSet<String>();
        for (var f : fields) {
            String imp = f.importLine();
            if (imp != null) imports.add(imp);
        }
        String importBlock = imports.isEmpty() ? "" : String.join("\n", imports) + "\n\n";
        String params = fields.stream()
            .map(f -> f.javaType() + " " + f.name())
            .collect(Collectors.joining(",\n        "));
        return """
            package %s.dto;

            %spublic record %sDto(
                Long id,
                %s
            ) {}
            """.formatted(pkg, importBlock, name, params);
    }

    // ---------------- Controller ----------------
    public static String controller(String pkg, String name) {
        String plural = pluralize(snake(name));
        String varName = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        return """
            package %s.web;

            import %s.domain.%s;
            import %s.repository.%sRepository;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.*;

            import java.util.List;

            @RestController
            @RequestMapping("/api/%s")
            public class %sController {

                private final %sRepository repo;

                public %sController(%sRepository repo) {
                    this.repo = repo;
                }

                @GetMapping
                public List<%s> list() {
                    return repo.findAll();
                }

                @GetMapping("/{id}")
                public ResponseEntity<%s> get(@PathVariable Long id) {
                    return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
                }

                @PostMapping
                public %s create(@RequestBody %s body) {
                    return repo.save(body);
                }

                @PutMapping("/{id}")
                public ResponseEntity<%s> update(@PathVariable Long id, @RequestBody %s body) {
                    return repo.findById(id).map(existing -> {
                        body.setId(id);
                        return ResponseEntity.ok(repo.save(body));
                    }).orElse(ResponseEntity.notFound().build());
                }

                @DeleteMapping("/{id}")
                public ResponseEntity<Void> delete(@PathVariable Long id) {
                    if (!repo.existsById(id)) return ResponseEntity.notFound().build();
                    repo.deleteById(id);
                    return ResponseEntity.noContent().build();
                }
            }
            """.formatted(pkg, pkg, name, pkg, name, plural, name, name, name, name, name, name, name, name, name, name);
    }

    // ---------------- Service ----------------
    public static String service(String pkg, String name) {
        return """
            package %s.service;

            import %s.domain.%s;
            import %s.repository.%sRepository;
            import org.springframework.stereotype.Service;

            import java.util.List;
            import java.util.Optional;

            @Service
            public class %sService {

                private final %sRepository repo;

                public %sService(%sRepository repo) {
                    this.repo = repo;
                }

                public List<%s> findAll() { return repo.findAll(); }
                public Optional<%s> findById(Long id) { return repo.findById(id); }
                public %s save(%s e) { return repo.save(e); }
                public void delete(Long id) { repo.deleteById(id); }
            }
            """.formatted(pkg, pkg, name, pkg, name, name, name, name, name, name, name, name, name);
    }

    // ---------------- Migration SQL ----------------
    public static String migrationCreate(String name, List<FieldSpec> fields) {
        String table = pluralize(snake(name));
        String cols = fields.stream()
            .map(f -> "    " + snake(f.name()) + " " + f.columnDef())
            .collect(Collectors.joining(",\n"));
        return """
            -- Auto-generated by xpresso
            CREATE TABLE %s (
                id BIGSERIAL PRIMARY KEY,
            %s
            );
            """.formatted(table, cols);
    }

    public static String migrationEmpty(String description) {
        return """
            -- %s
            -- Auto-generated by xpresso. Add your SQL below.
            """.formatted(description);
    }

    // ---------------- New project: pom.xml ----------------
    public static String newPom(String groupId, String artifactId) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>

                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.3.4</version>
                </parent>

                <groupId>%s</groupId>
                <artifactId>%s</artifactId>
                <version>0.1.0-SNAPSHOT</version>

                <properties>
                    <java.version>17</java.version>
                </properties>

                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-data-jpa</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-validation</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-actuator</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-devtools</artifactId>
                        <scope>runtime</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.flywaydb</groupId>
                        <artifactId>flyway-core</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.flywaydb</groupId>
                        <artifactId>flyway-database-postgresql</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.postgresql</groupId>
                        <artifactId>postgresql</artifactId>
                        <scope>runtime</scope>
                    </dependency>
                    <dependency>
                        <groupId>com.h2database</groupId>
                        <artifactId>h2</artifactId>
                        <scope>runtime</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-test</artifactId>
                        <scope>test</scope>
                    </dependency>
                </dependencies>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.formatted(groupId, artifactId);
    }

    public static String newGradle(String groupId, String artifactId, String javaVersion) {
        return """
            plugins {
                java
                id("org.springframework.boot") version "3.3.4"
                id("io.spring.dependency-management") version "1.1.6"
                id("org.flywaydb.flyway") version "10.17.0"
            }

            group = "%s"
            version = "0.1.0-SNAPSHOT"

            java {
                toolchain { languageVersion.set(JavaLanguageVersion.of(%s)) }
            }

            repositories { mavenCentral() }

            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-web")
                implementation("org.springframework.boot:spring-boot-starter-data-jpa")
                implementation("org.springframework.boot:spring-boot-starter-validation")
                implementation("org.springframework.boot:spring-boot-starter-actuator")
                developmentOnly("org.springframework.boot:spring-boot-devtools")
                implementation("org.flywaydb:flyway-core")
                implementation("org.flywaydb:flyway-database-postgresql")
                runtimeOnly("org.postgresql:postgresql")
                runtimeOnly("com.h2database:h2")
                testImplementation("org.springframework.boot:spring-boot-starter-test")
            }

            tasks.named<Test>("test") { useJUnitPlatform() }
            """.formatted(groupId, javaVersion);
    }

    public static String application(String pkg, String className) {
        return """
            package %s;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class %s {
                public static void main(String[] args) {
                    SpringApplication.run(%s.class, args);
                }
            }
            """.formatted(pkg, className, className);
    }

    public static String applicationYml(String artifactId) {
        return """
            spring:
              application:
                name: %s
              datasource:
                url: jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1
                username: sa
                password:
                driver-class-name: org.h2.Driver
              jpa:
                hibernate:
                  ddl-auto: validate
                properties:
                  hibernate:
                    dialect: org.hibernate.dialect.H2Dialect
              flyway:
                enabled: true
                locations: classpath:db/migration
            server:
              port: 8080
            management:
              endpoints:
                web:
                  exposure:
                    include: health,info
            """.formatted(artifactId, artifactId);
    }
}
