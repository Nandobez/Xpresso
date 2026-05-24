<div align="center">

# ☕ xpresso

### Spring Boot scaffolder + lifecycle — Rails for the JVM

[![JDK](https://img.shields.io/badge/JDK-17+-007396?style=for-the-badge&logo=openjdk)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3+-6DB33F?style=for-the-badge&logo=spring)](https://spring.io)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](./LICENSE)

</div>

`xpresso` brings Rails-style conventions to Spring Boot. One command
scaffolds a project, one generator wires an entire JPA resource (with
relations, constraints, OpenAPI docs and tests), and domain-aware
generators (auth, jobs, events, exceptions) cover the boilerplate that
Spring Boot tutorials skip.

Pairs with **[jdp](https://github.com/Nandobez/jdp)** (deps) and
**[Macc](https://github.com/Nandobez/Macchiato)** (frontend) via the
**[Ristretto](https://github.com/Nandobez/Ristretto)** umbrella CLI.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/Nandobez/Xpresso/main/install.sh | bash
```

Prerequisites: **JDK 17+**, **mvn** (or **gradle** with `--gradle`), **git**.

## 90-second tour

```bash
xpresso new myshop --group io.acme                                       # scaffold
cd myshop

xpresso g resource Product name:string!notblank{200} price:decimal!positive
xpresso g model Order user:belongs_to status:enum(NEW,PAID,SHIPPED)      # relations + enums
xpresso g endpoint Product archive --method POST --path /{id}/archive    # extra controller method

xpresso db migrate                                                       # Flyway
xpresso routes                                                           # colour-coded
xpresso routes --diff                                                    # endpoints added/removed since HEAD
xpresso beans                                                            # list Spring beans
xpresso health                                                           # curl /actuator/health
xpresso s                                                                # spring-boot:run
```

## Commands

```
PROJECT
  new <name> [--gradle] [--kotlin] [--template …] [--spring 3.3.4]
                       scaffold Maven|Gradle, Java|Kotlin, with picked template
  server, s            spring-boot:run / bootRun (auto-builds frontend if Macc)
  console, c           Spring Shell when available, jshell fallback
  clean [--deep]       mvn clean / gradle clean (--deep wipes node_modules too)
  compile              mvn -q compile (+ macc codegen if frontend)
  build [--with-tests] clean + package (skips tests by default)
  install              clean + install + macc install if frontend present
  test, t [pattern]    test suite
  watch                re-compile on every .java change (DevTools-friendly)

INSPECT
  routes [--diff]      list endpoints (--diff: changes vs HEAD)
  beans [--type X]     list @Service / @Repository / @Controller / @Configuration / @Component
  config               list @ConfigurationProperties + application.yml dump
  health [-u URL]      curl /actuator/health, pretty-print

GENERATE
  g model <Name> <fields>      entity + repository + DTO + migration
  g controller <Name>          REST controller with @Operation + @Valid + @Tag
  g service <Name>             @Service skeleton
  g migration <description>    blank Flyway migration (timestamped)
  g resource <Name> <fields>   model + service + controller (combo)
  g endpoint <ctrl> <action>   add method to existing controller
                               --method POST --path /{id}/archive
  g auth                       AppUser + bcrypt + SecurityConfig + migration
  g job <Name>                 @Scheduled task
  g event <Name>               ApplicationEvent + @EventListener
  g exception <Name>           custom exception + @RestControllerAdvice
  g config <Name>              @Configuration skeleton
  g component <Name>           @Component skeleton
  g test <Subject>             JUnit5 test class
  --tdd                        also generate test class for the generated artifact

DATABASE
  db migrate [--to V…]         run Flyway migrate (optional target)
  db status, info              Flyway state
  db rollback                  Flyway undo
  db clean                     Flyway clean (destructive)
  db repair                    Flyway repair

PROFILES
  profile add <name>           create application-<name>.yml
  profile list                 show all defined profiles
  profile rm <name>            remove the profile file

INTEGRATIONS
  doctor [--fix]               delegate to jdp doctor (CVE + outdated + score)
  deps                         delegate to jdp list
```

`xpresso <cmd> --help` for per-command help.

## Field types

```
string text int long float double decimal bool date datetime uuid json
```

Plus relations:

```
user:belongs_to                → @ManyToOne (creates FK column + constraint)
comments:has_many              → @OneToMany List<Comment> (mappedBy)
profile:has_one                → @OneToOne
status:enum(NEW,PAID,SHIPPED)  → @Enumerated(STRING) + nested enum class
```

Plus constraints (suffix flags + length):

```
email:string!unique!email                 → @Email + @Column(unique=true)
title:string!notblank{200}                → @NotBlank + @Column(length=200)
price:decimal!positive                    → @Positive
date:date!future                          → @Future
url:string!url                            → @URL
```

Supported flags: `unique`, `notnull`, `notblank`, `notempty`, `email`,
`url`, `positive`, `negative`, `past`, `future`, plus `{N}` for VARCHAR
length.

## Generated structure (`xpresso new`)

```
myshop/
├── pom.xml                                  # Spring Boot 3.3 + JPA + Flyway + Validation
│                                            # + springdoc-openapi + spotless
├── src/main/java/io/acme/myshop/
│   ├── MyshopApplication.java
│   ├── domain/        → @Entity classes
│   ├── repository/    → JpaRepository<T, Long>
│   ├── service/       → @Service classes
│   ├── web/           → @RestController with @Tag + @Operation
│   ├── dto/           → records
│   ├── config/        → @Configuration
│   ├── job/           → @Scheduled
│   ├── event/         → ApplicationEvent + Listener
│   └── exception/     → custom + @RestControllerAdvice
├── src/test/java/...
└── src/main/resources/
    ├── application.yml
    └── db/migration/  → V<timestamp>__<desc>.sql (Flyway)
```

## Templates (`--template`)

| Template | Extra deps |
|---|---|
| `rest-api` (default) | starter-web |
| `graphql`            | starter-graphql |
| `webflux`            | starter-webflux |
| `kafka-consumer`     | spring-kafka |
| `batch`              | starter-batch |
| `lib`                | no starter-web (pure library) |

## How `routes` works

`xpresso routes` greps `src/main/java/**/*.java` for `@RequestMapping`
on classes and `@*Mapping` on methods, joins paths and prints a
colour-coded table:

```
VERB    PATH                HANDLER
GET     /api/products       ProductController#list
GET     /api/products/{id}  ProductController#get
POST    /api/products       ProductController#create
PUT     /api/products/{id}  ProductController#update
DELETE  /api/products/{id}  ProductController#delete
POST    /auth/register      AuthController#register
POST    /auth/login         AuthController#login
```

`--diff` adds `git show HEAD:<file>` and shows added/removed routes vs
the last commit.

## Integrations

- **`xpresso doctor`** → forwards to `jdp doctor` (CVE checks via
  OSV.dev + outdated + custom incompat rules + score). `--fix` bumps to
  patched versions.
- **`xpresso deps`** → forwards to `jdp list`.
- **`xpresso server`** → if a `src/main/frontend/` (Macc) project
  exists and the bundle is stale, runs `macc install` before starting.
- **`xpresso compile`** → also runs `macc codegen` after `mvn compile`
  when a Macc frontend is detected.

## Kotlin

```bash
xpresso new my-svc --kotlin --group io.demo
```

Produces a Kotlin scaffold with `kotlin-maven-plugin`, `spring` and
`jpa` allopen/noarg compiler plugins pre-wired. Generators currently
still emit Java; raise a PR if you want `.kt` output.

## Why not Spring Initializr / start.spring.io?

Initializr scaffolds the *project*. `xpresso` scaffolds the project
**and** every file you'd write afterwards — entity, repository,
controller (with OpenAPI annotations), migration, listener, scheduled
job, advice — with the same conventions, validation, and a `routes`
inspector built in.

Think `rails generate scaffold Post title:string body:text` for the JVM.

## Contributing

PRs welcome. Easy additions:
- new field types in `core/FieldSpec.java`
- new constraint flags in `FieldSpec.validationAnnotations`
- new generators in `gen/ExtraTemplates.java` + wire in `GenerateCmd`
- new project templates in `gen/Templates.newPom`

## License

MIT — Fernando Bezerra · 2026
