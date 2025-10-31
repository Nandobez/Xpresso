package dev.nandobez.xpresso.core;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.nio.file.*;
import java.util.stream.Stream;

/** Identifies a Spring Boot project's groupId + base package + source dirs. */
public class ProjectLayout {
    public final Path root;
    public final String groupId;
    public final String artifactId;
    public final String basePackage;
    public final Path javaRoot;
    public final Path resourcesRoot;
    public final Path migrationsDir;

    public ProjectLayout(Path root, String groupId, String artifactId,
                         String basePackage, Path javaRoot, Path resourcesRoot,
                         Path migrationsDir) {
        this.root = root;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.basePackage = basePackage;
        this.javaRoot = javaRoot;
        this.resourcesRoot = resourcesRoot;
        this.migrationsDir = migrationsDir;
    }

    /** Detect the project from a directory (walks up looking for pom.xml). */
    public static ProjectLayout detect(Path from) throws Exception {
        Path cur = from.toAbsolutePath();
        while (cur != null && !Files.exists(cur.resolve("pom.xml"))) cur = cur.getParent();
        if (cur == null) throw new RuntimeException("no pom.xml found from " + from);
        Path pom = cur.resolve("pom.xml");
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        String groupId = text(doc, "groupId");
        String artifactId = text(doc, "artifactId");

        Path javaRoot = cur.resolve("src/main/java");
        Path resourcesRoot = cur.resolve("src/main/resources");

        // Find the base package = first directory with a *Application.java or just go deep until multiple subdirs.
        String basePackage = findBasePackage(javaRoot, groupId);
        Path migrationsDir = resourcesRoot.resolve("db/migration");

        return new ProjectLayout(cur, groupId, artifactId, basePackage, javaRoot, resourcesRoot, migrationsDir);
    }

    private static String text(org.w3c.dom.Document doc, String tag) {
        NodeList nl = doc.getDocumentElement().getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getParentNode() == doc.getDocumentElement())
                return nl.item(i).getTextContent().trim();
        }
        return "app";
    }

    /** Walk java root looking for the package that contains *Application.java; fall back to groupId. */
    private static String findBasePackage(Path javaRoot, String groupId) throws Exception {
        if (!Files.exists(javaRoot)) return groupId;
        try (Stream<Path> walk = Files.walk(javaRoot)) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith("Application.java"))::iterator) {
                Path rel = javaRoot.relativize(p.getParent());
                return rel.toString().replace('/', '.');
            }
        }
        return groupId;
    }

    public Path packageDir(String subPackage) {
        Path d = javaRoot.resolve(basePackage.replace('.', '/'));
        if (!subPackage.isEmpty()) d = d.resolve(subPackage);
        return d;
    }

    public String packageOf(String subPackage) {
        return subPackage.isEmpty() ? basePackage : basePackage + "." + subPackage;
    }
}
