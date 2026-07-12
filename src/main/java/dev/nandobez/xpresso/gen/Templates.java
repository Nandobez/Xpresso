package dev.nandobez.xpresso.gen;

import dev.nandobez.xpresso.core.FieldSpec;
import java.util.*;
import java.util.stream.Collectors;
import java.util.ArrayList;

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

    public static String capitalizeAction(String s) { return capitalize(s); }

    public static String snake(String s) {
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    // ---------------- Entity ----------------
    public static String entity(String pkg, String name, List<FieldSpec> fields) {
        String table = pluralize(snake(name));
        var imports = new TreeSet<String>();
        for (var f : fields) imports.addAll(f.imports());
        String importBlock = imports.isEmpty() ? "" : String.join("\n", imports) + "\n\n";

        var sb = new StringBuilder();
        for (var f : fields) {
            String validation = f.validationAnnotations();
            if (!validation.isEmpty()) sb.append("    ").append(validation).append("\n");
            switch (f.kind()) {
                case BELONGS_TO -> sb.append("    @ManyToOne(fetch = FetchType.LAZY)\n")
                                     .append("    @JoinColumn(name = \"").append(snake(f.name())).append("_id\")\n")
                                     .append("    private ").append(f.targetEntity()).append(" ").append(f.name()).append(";\n\n");
                case HAS_MANY  -> sb.append("    @OneToMany(mappedBy = \"").append(decapitalize(name)).append("\", cascade = CascadeType.ALL, orphanRemoval = true)\n")
                                     .append("    private List<").append(f.targetEntity()).append("> ").append(f.name()).append(" = new java.util.ArrayList<>();\n\n");
                case HAS_ONE   -> sb.append("    @OneToOne(cascade = CascadeType.ALL)\n")
                                     .append("    @JoinColumn(name = \"").append(snake(f.name())).append("_id\")\n")
                                     .append("    private ").append(f.targetEntity()).append(" ").append(f.name()).append(";\n\n");
                case ENUM      -> sb.append("    @Enumerated(EnumType.STRING)\n")
                                     .append("    @Column(").append(f.columnAttrs(snake(f.name()))).append(")\n")
                                     .append("    private ").append(f.javaType()).append(" ").append(f.name()).append(";\n\n");
                default        -> sb.append("    @Column(").append(f.columnAttrs(snake(f.name()))).append(")\n")
                                     .append("    private ").append(f.javaType()).append(" ").append(f.name()).append(";\n\n");
            }
        }
        String fieldDecls = sb.toString();

        var getterSb = new StringBuilder();
        for (var f : fields) {
            getterSb.append("    public ").append(f.javaType()).append(" ").append(getter(f)).append("() { return ").append(f.name()).append("; }\n")
                    .append("    public void set").append(capitalize(f.name())).append("(").append(f.javaType()).append(" v) { this.").append(f.name()).append(" = v; }\n");
        }
        String getters = getterSb.toString();

        // emit enum nested classes
        var enumSb = new StringBuilder();
        for (var f : fields) {
            if (f.kind() == FieldSpec.Kind.ENUM && !f.enumValues().isEmpty()) {
                enumSb.append("\n    public enum ").append(f.javaType()).append(" { ")
                      .append(String.join(", ", f.enumValues())).append(" }\n");
            }
        }

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

            %s%s}
            """.formatted(pkg, importBlock, table, name, fieldDecls, getters, enumSb.toString());
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
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

    // ---------------- DTOs (Request in, Response out) ----------------
    /** Write model: validated scalar fields, no id (server owns identity). */
    public static String request(String pkg, String name, List<FieldSpec> fields) {
        var imports = new TreeSet<String>();
        for (var f : fields) if (!f.isRelation()) imports.addAll(f.imports());
        String importBlock = imports.isEmpty() ? "" : String.join("\n", imports) + "\n\n";
        String params = fields.stream()
            .filter(f -> !f.isRelation())
            .map(f -> {
                String v = f.validationAnnotations();
                return (v.isEmpty() ? "" : v + " ") + f.javaType() + " " + f.name();
            })
            .collect(Collectors.joining(",\n        "));
        return """
            package %s.dto;

            %spublic record %sRequest(
                %s
            ) {}
            """.formatted(pkg, importBlock, name, params);
    }

    /** Read model: id plus scalar fields, returned to clients. */
    public static String response(String pkg, String name, List<FieldSpec> fields) {
        var imports = new TreeSet<String>();
        for (var f : fields) if (!f.isRelation()) imports.addAll(f.imports());
        // response carries no validation annotations
        imports.removeIf(i -> i.contains("jakarta.validation"));
        String importBlock = imports.isEmpty() ? "" : String.join("\n", imports) + "\n\n";
        String params = fields.stream()
            .filter(f -> !f.isRelation())
            .map(f -> f.javaType() + " " + f.name())
            .collect(Collectors.joining(",\n        "));
        String body = params.isEmpty() ? "    Long id\n" : "    Long id,\n        " + params + "\n";
        return """
            package %s.dto;

            %spublic record %sResponse(
            %s) {}
            """.formatted(pkg, importBlock, name, body);
    }

    // ---------------- Controller ----------------
    public static String controller(String pkg, String name) {
        return controller(pkg, name, List.of());
    }

    /** DTO-based CRUD controller delegating to the service layer; entities never cross the API boundary. */
    public static String controller(String pkg, String name, List<FieldSpec> fields) {
        String plural   = pluralize(snake(name));
        String pluralEn = pluralize(name);

        var scalars = fields.stream().filter(f -> !f.isRelation()).toList();
        StringBuilder toResp = new StringBuilder("e.getId()");
        for (var f : scalars) toResp.append(", e.").append(getter(f)).append("()");
        StringBuilder apply = new StringBuilder();
        for (var f : scalars)
            apply.append("        e.set").append(capitalize(f.name()))
                 .append("(req.").append(f.name()).append("());\n");

        return ("""
            package {PKG}.web;

            import {PKG}.domain.{N};
            import {PKG}.dto.{N}Request;
            import {PKG}.dto.{N}Response;
            import {PKG}.service.{N}Service;
            import io.swagger.v3.oas.annotations.Operation;
            import io.swagger.v3.oas.annotations.tags.Tag;
            import jakarta.validation.Valid;
            import org.springframework.data.domain.Page;
            import org.springframework.data.domain.Pageable;
            import org.springframework.http.ResponseEntity;
            import org.springframework.web.bind.annotation.*;
            import org.springframework.web.util.UriComponentsBuilder;

            import java.net.URI;

            /** Auto-generated by xpresso. Maps {N}Request/{N}Response; delegates persistence to {N}Service. */
            @RestController
            @RequestMapping("/api/{PLURAL}")
            @Tag(name = "{N}", description = "CRUD endpoints for {N}")
            public class {N}Controller {

                private final {N}Service service;

                public {N}Controller({N}Service service) {
                    this.service = service;
                }

                @Operation(summary = "List {PLURALEN} (paged)")
                @GetMapping
                public Page<{N}Response> list(Pageable pageable) {
                    return service.list(pageable).map({N}Controller::toResponse);
                }

                @Operation(summary = "Get a {N} by id")
                @GetMapping("/{id}")
                public ResponseEntity<{N}Response> get(@PathVariable Long id) {
                    return service.find(id).map({N}Controller::toResponse)
                        .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
                }

                @Operation(summary = "Create a new {N}")
                @PostMapping
                public ResponseEntity<{N}Response> create(@Valid @RequestBody {N}Request req, UriComponentsBuilder uri) {
                    {N} saved = service.create(apply(req, new {N}()));
                    URI location = uri.path("/api/{PLURAL}/{id}").buildAndExpand(saved.getId()).toUri();
                    return ResponseEntity.created(location).body(toResponse(saved));
                }

                @Operation(summary = "Update an existing {N}")
                @PutMapping("/{id}")
                public ResponseEntity<{N}Response> update(@PathVariable Long id, @Valid @RequestBody {N}Request req) {
                    return service.update(id, e -> apply(req, e)).map({N}Controller::toResponse)
                        .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
                }

                @Operation(summary = "Delete a {N} by id")
                @DeleteMapping("/{id}")
                public ResponseEntity<Void> delete(@PathVariable Long id) {
                    return service.delete(id)
                        ? ResponseEntity.noContent().build()
                        : ResponseEntity.notFound().build();
                }

                static {N}Response toResponse({N} e) {
                    return new {N}Response({TO_RESP});
                }

                static {N} apply({N}Request req, {N} e) {
            {APPLY}        return e;
                }
            }
            """)
            .replace("{PKG}", pkg)
            .replace("{N}", name)
            .replace("{PLURALEN}", pluralEn)
            .replace("{PLURAL}", plural)
            .replace("{TO_RESP}", toResp.toString())
            .replace("{APPLY}", apply.toString());
    }

    // ---------------- Service ----------------
    public static String service(String pkg, String name) {
        return """
            package %s.service;

            import %s.domain.%s;
            import %s.repository.%sRepository;
            import org.springframework.data.domain.Page;
            import org.springframework.data.domain.Pageable;
            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;

            import java.util.Optional;
            import java.util.function.UnaryOperator;

            @Service
            public class %sService {

                private final %sRepository repo;

                public %sService(%sRepository repo) {
                    this.repo = repo;
                }

                @Transactional(readOnly = true)
                public Page<%s> list(Pageable pageable) { return repo.findAll(pageable); }

                @Transactional(readOnly = true)
                public Optional<%s> find(Long id) { return repo.findById(id); }

                @Transactional
                public %s create(%s e) { return repo.save(e); }

                /** Loads the entity, applies the patch in-transaction, and saves. Empty if not found. */
                @Transactional
                public Optional<%s> update(Long id, UnaryOperator<%s> patch) {
                    return repo.findById(id).map(e -> repo.save(patch.apply(e)));
                }

                @Transactional
                public boolean delete(Long id) {
                    if (!repo.existsById(id)) return false;
                    repo.deleteById(id);
                    return true;
                }
            }
            """.formatted(pkg, pkg, name, pkg, name, name, name, name, name,
                          name, name, name, name, name, name);
    }

    // ---------------- Migration SQL ----------------
    public static String migrationCreate(String name, List<FieldSpec> fields) {
        String table = pluralize(snake(name));
        var cols = new ArrayList<String>();
        var fks  = new ArrayList<String>();
        for (var f : fields) {
            switch (f.kind()) {
                case BELONGS_TO -> {
                    cols.add("    " + snake(f.name()) + "_id BIGINT" + (f.flags().contains("notnull") ? " NOT NULL" : ""));
                    fks.add("    CONSTRAINT fk_" + table + "_" + snake(f.name())
                          + " FOREIGN KEY (" + snake(f.name()) + "_id) REFERENCES "
                          + pluralize(snake(f.targetEntity())) + "(id)");
                }
                case HAS_MANY, HAS_ONE -> { /* belongs to the other side */ }
                case ENUM -> cols.add("    " + snake(f.name()) + " VARCHAR(64)"
                                       + (f.flags().contains("notnull") ? " NOT NULL" : ""));
                case SCALAR -> {
                    String c = "    " + snake(f.name()) + " " + f.columnDef();
                    if (f.flags().contains("notnull") || f.flags().contains("required")) c += " NOT NULL";
                    if (f.flags().contains("unique")) c += " UNIQUE";
                    cols.add(c);
                }
            }
        }
        cols.addAll(fks);
        return """
            -- Auto-generated by xpresso
            CREATE TABLE %s (
                id BIGSERIAL PRIMARY KEY,
            %s
            );
            """.formatted(table, String.join(",\n", cols));
    }

    public static String migrationEmpty(String description) {
        return """
            -- %s
            -- Auto-generated by xpresso. Add your SQL below.
            """.formatted(description);
    }

    // ---------------- New project: pom.xml ----------------
    public static String newPom(String groupId, String artifactId) {
        return newPom(groupId, artifactId, "3.3.4", "rest-api");
    }

    public static String newPom(String groupId, String artifactId, String springVersion, String template, boolean useKotlin) {
        return newPom(groupId, artifactId, springVersion, template);
    }

    public static String newPom(String groupId, String artifactId, String springVersion, String template) {
        String extraDeps = switch (template) {
            case "graphql"        -> """
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-graphql</artifactId>
                        </dependency>
                """;
            case "webflux"        -> """
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-webflux</artifactId>
                        </dependency>
                """;
            case "kafka-consumer" -> """
                        <dependency>
                            <groupId>org.springframework.kafka</groupId>
                            <artifactId>spring-kafka</artifactId>
                        </dependency>
                """;
            case "batch"          -> """
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-batch</artifactId>
                        </dependency>
                """;
            default -> "";
        };
        boolean isLib = "lib".equals(template);
        String webStarter = isLib ? "" : """
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
            """;
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>

                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>%s</version>
                </parent>

                <groupId>%s</groupId>
                <artifactId>%s</artifactId>
                <version>0.1.0-SNAPSHOT</version>

                <properties>
                    <java.version>17</java.version>
                </properties>

                <dependencies>
            %s%s        <dependency>
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
                    <dependency>
                        <groupId>org.springdoc</groupId>
                        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                        <version>2.6.0</version>
                    </dependency>
                </dependencies>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                        </plugin>
                        <plugin>
                            <groupId>com.diffplug.spotless</groupId>
                            <artifactId>spotless-maven-plugin</artifactId>
                            <version>2.43.0</version>
                            <configuration>
                                <java>
                                    <googleJavaFormat><version>1.22.0</version></googleJavaFormat>
                                    <removeUnusedImports/>
                                </java>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.formatted(springVersion, groupId, artifactId, webStarter, extraDeps);
    }

    public static String newKotlinPom(String groupId, String artifactId, String springVersion) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>

                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>%s</version>
                </parent>

                <groupId>%s</groupId>
                <artifactId>%s</artifactId>
                <version>0.1.0-SNAPSHOT</version>

                <properties>
                    <java.version>17</java.version>
                    <kotlin.version>2.0.20</kotlin.version>
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
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-reflect</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-stdlib</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>com.fasterxml.jackson.module</groupId>
                        <artifactId>jackson-module-kotlin</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>com.h2database</groupId>
                        <artifactId>h2</artifactId>
                        <scope>runtime</scope>
                    </dependency>
                </dependencies>

                <build>
                    <sourceDirectory>src/main/kotlin</sourceDirectory>
                    <testSourceDirectory>src/test/kotlin</testSourceDirectory>
                    <plugins>
                        <plugin>
                            <groupId>org.jetbrains.kotlin</groupId>
                            <artifactId>kotlin-maven-plugin</artifactId>
                            <configuration>
                                <args>
                                    <arg>-Xjsr305=strict</arg>
                                </args>
                                <compilerPlugins>
                                    <plugin>spring</plugin>
                                    <plugin>jpa</plugin>
                                </compilerPlugins>
                            </configuration>
                            <dependencies>
                                <dependency>
                                    <groupId>org.jetbrains.kotlin</groupId>
                                    <artifactId>kotlin-maven-allopen</artifactId>
                                    <version>${kotlin.version}</version>
                                </dependency>
                                <dependency>
                                    <groupId>org.jetbrains.kotlin</groupId>
                                    <artifactId>kotlin-maven-noarg</artifactId>
                                    <version>${kotlin.version}</version>
                                </dependency>
                            </dependencies>
                            <executions>
                                <execution>
                                    <id>compile</id>
                                    <phase>compile</phase>
                                    <goals><goal>compile</goal></goals>
                                </execution>
                                <execution>
                                    <id>test-compile</id>
                                    <phase>test-compile</phase>
                                    <goals><goal>test-compile</goal></goals>
                                </execution>
                            </executions>
                        </plugin>
                        <plugin>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-maven-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """.formatted(springVersion, groupId, artifactId);
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
