package dev.nandobez.xpresso.core;

import java.nio.file.*;
import java.util.*;

/** Abstracts Maven vs Gradle for lifecycle commands. Detect via the root marker file. */
public sealed interface BuildSystem permits BuildSystem.Maven, BuildSystem.Gradle {

    Path root();
    String name();

    List<String> server(String port, String profile);
    List<String> build(boolean skipTests);
    List<String> clean();
    List<String> compile();
    List<String> test(String pattern);
    List<String> install(boolean skipTests);
    List<String> dependencyTree(String includes);
    List<String> dbTask(String flywayGoal);

    static BuildSystem detect(Path from) {
        Path cur = from.toAbsolutePath();
        while (cur != null) {
            if (Files.exists(cur.resolve("pom.xml"))) return new Maven(cur);
            if (Files.exists(cur.resolve("build.gradle.kts"))
                || Files.exists(cur.resolve("build.gradle"))) return new Gradle(cur);
            cur = cur.getParent();
        }
        throw new RuntimeException("no pom.xml or build.gradle[.kts] found from " + from);
    }

    record Maven(Path root) implements BuildSystem {
        public String name() { return "maven"; }
        public List<String> server(String port, String profile) {
            var l = new ArrayList<String>(List.of("mvn", "spring-boot:run"));
            if (port != null)    l.add("-Dspring-boot.run.arguments=--server.port=" + port);
            if (profile != null) l.add("-Dspring-boot.run.profiles=" + profile);
            return l;
        }
        public List<String> build(boolean skipTests) {
            var l = new ArrayList<String>(List.of("mvn", "clean", "package"));
            if (skipTests) l.add("-DskipTests");
            return l;
        }
        public List<String> clean()   { return List.of("mvn", "-q", "clean"); }
        public List<String> compile() { return List.of("mvn", "-q", "compile"); }
        public List<String> test(String pattern) {
            var l = new ArrayList<String>(List.of("mvn", "test"));
            if (pattern != null) l.add("-Dtest=" + pattern);
            return l;
        }
        public List<String> install(boolean skipTests) {
            var l = new ArrayList<String>(List.of("mvn", "clean", "install"));
            if (skipTests) l.add("-DskipTests");
            return l;
        }
        public List<String> dependencyTree(String includes) {
            var l = new ArrayList<String>(List.of("mvn", "-B", "dependency:tree"));
            if (includes != null) l.add("-Dincludes=" + includes);
            return l;
        }
        public List<String> dbTask(String flywayGoal) {
            return List.of("mvn", "flyway:" + flywayGoal);
        }
    }

    record Gradle(Path root) implements BuildSystem {
        public String name() { return "gradle"; }
        private String g() { return Files.exists(root.resolve("gradlew")) ? "./gradlew" : "gradle"; }
        public List<String> server(String port, String profile) {
            var l = new ArrayList<String>(List.of(g(), "bootRun"));
            if (port != null)    l.add("--args=--server.port=" + port);
            if (profile != null) l.add("-Dspring.profiles.active=" + profile);
            return l;
        }
        public List<String> build(boolean skipTests) {
            var l = new ArrayList<String>(List.of(g(), "clean", "build"));
            if (skipTests) l.add("-x"); l.add("test");
            return l;
        }
        public List<String> clean()   { return List.of(g(), "-q", "clean"); }
        public List<String> compile() { return List.of(g(), "-q", "compileJava"); }
        public List<String> test(String pattern) {
            var l = new ArrayList<String>(List.of(g(), "test"));
            if (pattern != null) l.addAll(List.of("--tests", pattern));
            return l;
        }
        public List<String> install(boolean skipTests) {
            var l = new ArrayList<String>(List.of(g(), "clean", "publishToMavenLocal"));
            if (skipTests) l.add("-x"); l.add("test");
            return l;
        }
        public List<String> dependencyTree(String includes) {
            var l = new ArrayList<String>(List.of(g(), "dependencies", "--configuration", "runtimeClasspath"));
            return l;
        }
        public List<String> dbTask(String flywayGoal) {
            return List.of(g(), "-q", "flyway" + capitalize(flywayGoal));
        }
        private static String capitalize(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    }
}
