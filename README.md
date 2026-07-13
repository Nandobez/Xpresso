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
xpresso g seed Product name:string price:decimal --count 30              # fake data on dev startup

xpresso db migrate                                                       # Flyway (uses application.yml datasource)
xpresso routes                                                           # colour-coded
xpresso beans                                                            # list Spring beans
xpresso s                                                                # spring-boot:run

xpresso api GET products                                                 # hit the live API
xpresso api POST -m products                                             # -m mocks a valid body from OpenAPI
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
  routes               list endpoints (colour-coded by verb)
  beans [--type X]     list @Service / @Repository / @Controller / @Configuration / @Component
  config               list @ConfigurationProperties + application.yml dump
  health [-u URL]      curl /actuator/health, pretty-print
  api <METHOD> [-m] <endpoint> [k=v…]
                       call the running app; -m mocks a valid body from OpenAPI,
                       --raw dumps colored JSON

GENERATE   (output grouped by layer; --dry-run previews without writing)
  g model <Name> <fields>      entity + repository + Request/Response DTOs + migration
  g controller <Name>          DTO-based REST controller (delegates to the service)
  g service <Name>             @Transactional @Service
  g migration <description>    blank Flyway migration (unique timestamp)
  g resource <Name> <fields>   model + service + controller + error handler + tests
                               [--seed] also emits a dev seed factory + seeder
  g seed <Name> <fields> [--count N]
                               editable Faker factory + dev-profile seeder (adds datafaker)
  g endpoint <ctrl> <action>   add method to existing controller
                               --method POST --path /{id}/archive
  g auth                       AppUser + bcrypt + SecurityConfig + migration
  g job / event / exception / config / component / test
  --tdd                        also generate a test class for the generated artifact

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
│   ├── service/       → @Transactional @Service classes
│   ├── web/           → @RestController (DTO-based, paged) with @Operation
│   ├── dto/           → <Name>Request + <Name>Response records
│   ├── config/        → @Configuration
│   ├── dev/           → seed factories + dev-profile seeders
│   ├── job/           → @Scheduled
│   ├── event/         → ApplicationEvent + Listener
│   └── exception/     → custom + GlobalExceptionHandler
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
