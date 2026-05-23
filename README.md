<div align="center">

# ☕ xpresso

### Spring Boot scaffolder — Rails for the JVM

[![JDK](https://img.shields.io/badge/JDK-17+-007396?style=for-the-badge&logo=openjdk)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3+-6DB33F?style=for-the-badge&logo=spring)](https://spring.io)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](./LICENSE)

</div>

`xpresso` brings Rails-style conventions to Spring Boot. One command
scaffolds a project, one generator wires an entire JPA resource, and
domain-aware generators (auth, jobs, events, exceptions) cover the
boilerplate that Spring Boot tutorials skip.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/Nandobez/Xpresso/main/install.sh | bash
```

Prerequisites: **JDK 17+**, **mvn**, **git**.
Installs to `~/.local/bin/xpresso`.

## 60-second tour

```bash
xpresso new myshop --group io.acme
cd myshop

xpresso g resource Product name:string price:decimal stock:int
xpresso g auth                       # bcrypt + login/register + Spring Security
xpresso g job CleanupOrders          # @Scheduled bean
xpresso g event OrderPlaced          # ApplicationEvent + @EventListener

xpresso routes                       # list all endpoints, colour-coded
xpresso db migrate                   # run Flyway
xpresso s                            # spring-boot:run + devtools
```

## Commands

```
PROJECT
  new <name>            scaffold a new Spring Boot 3.x project
  server, s             spring-boot:run with devtools hot reload
  console, c            jshell with project classpath + imports
  build                 mvn clean package -DskipTests
  test, t               mvn test (optional class pattern)
  routes                list endpoints from @*Mapping annotations

GENERATE
  g model <Name> <fields>      entity + repository + DTO + migration
  g controller <Name>          REST controller wired to repository
  g service <Name>             @Service skeleton
  g migration <description>    blank Flyway migration (timestamped)
  g resource <Name> <fields>   combo: model + service + controller
  g auth                       user entity + bcrypt + Spring Security
  g job <Name>                 @Scheduled task
  g event <Name>               ApplicationEvent + @EventListener
  g exception <Name>           custom RuntimeException + @RestControllerAdvice
  g config <Name>              @Configuration skeleton
  g component <Name>           @Component skeleton
  g test <Subject>             JUnit5 test class

DATABASE
  db migrate                   run flyway:migrate
  db rollback                  flyway:undo
  db info                      flyway:info
  db clean                     flyway:clean (destructive)
  db repair                    flyway:repair
```

Per-command help via picocli: `xpresso <cmd> --help`.

## Field types

```
string text int long float double decimal bool date datetime uuid json
```

Mapped to:

| `xpresso` type | Java                | SQL              |
|---|---|---|
| `string`        | `String`            | `VARCHAR(255)`   |
| `text`          | `String`            | `TEXT`           |
| `int`           | `Integer`           | `INTEGER`        |
| `long`          | `Long`              | `BIGINT`         |
| `decimal`/`money` | `BigDecimal`      | `DECIMAL(19,4)`  |
| `bool`          | `Boolean`           | `BOOLEAN`        |
| `date`          | `LocalDate`         | `DATE`           |
| `datetime`      | `Instant`           | `TIMESTAMP`      |
| `uuid`          | `UUID`              | `UUID`           |
| `json`          | `String`            | `JSONB`          |

## Convention layout

`xpresso new` creates:

```
myshop/
├── pom.xml                              # Spring Boot 3.3 + JPA + Flyway + H2 + PostgreSQL
├── src/main/java/io/acme/myshop/
│   ├── MyshopApplication.java
│   ├── domain/        → @Entity classes
│   ├── repository/    → JpaRepository<T, Long>
│   ├── service/       → @Service classes
│   ├── web/           → @RestController classes
│   ├── dto/           → records
│   ├── config/        → @Configuration
│   ├── job/           → @Scheduled
│   ├── event/         → ApplicationEvent + Listener
│   └── exception/     → custom + @RestControllerAdvice
└── src/main/resources/
    ├── application.yml
    └── db/migration/  → V<timestamp>__<desc>.sql (Flyway)
```

## Examples

### Full resource

```bash
xpresso g resource Post title:string body:text published:bool author:string

# creates:
#   src/main/java/.../domain/Post.java           (@Entity)
#   src/main/java/.../repository/PostRepository.java
#   src/main/java/.../dto/PostDto.java           (record)
#   src/main/java/.../service/PostService.java
#   src/main/java/.../web/PostController.java    (CRUD endpoints)
#   src/main/resources/db/migration/V<ts>__create_posts.sql
```

### Auth scaffold

```bash
xpresso g auth

# creates:
#   domain/AppUser.java
#   repository/AppUserRepository.java
#   web/AuthController.java          (/auth/register, /auth/login)
#   config/SecurityConfig.java       (bcrypt + filter chain)
#   db/migration/V<ts>__create_users.sql
```

### Background job

```bash
xpresso g job DailyDigest

# creates: job/DailyDigestJob.java with @Scheduled(cron = "0 * * * * *")
```

### Domain events

```bash
xpresso g event OrderPlaced

# creates:
#   event/OrderPlacedEvent.java       (extends ApplicationEvent)
#   event/OrderPlacedListener.java    (@EventListener)
```

## How `routes` works

`xpresso routes` greps `src/main/java/**/*.java` for `@RequestMapping`
on classes and `@GetMapping/@PostMapping/...` on methods, joins paths
and prints a colour-coded table:

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

## Why not Spring Initializr / start.spring.io?

Initializr scaffolds the *project*. `xpresso` scaffolds the project
**and** every file you'd write afterwards — entity, repository,
controller, migration, listener, scheduled job, advice — with the same
conventions and reasonable defaults.

Think `rails generate scaffold Post title:string body:text` for the JVM.

## Contributing

PRs welcome. Easy additions:
- new field types in `core/FieldSpec.java`
- new generators in `gen/ExtraTemplates.java` (+ wire in `GenerateCmd`)
- new project templates in `gen/Templates.newPom`

## License

MIT — Fernando Bezerra · 2026
