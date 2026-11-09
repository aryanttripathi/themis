package io.themis.resolve;

import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.ProjectInfo;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads basic project facts from the project's own pom.xml: coordinate and target Java release.
 * Dependency resolution is NOT done here (that needs the real Maven resolver).
 */
public final class ProjectInspector {

    public static final int DEFAULT_JAVA_RELEASE = 17;

    /** Properties checked in order; Spring Boot projects typically set java.version. */
    private static final List<String> JAVA_PROPERTIES = List.of(
            "maven.compiler.release", "maven.compiler.target", "maven.compiler.source", "java.version");

    public ProjectInfo inspect(Path projectDir, ScanConfig config) {
        Path root = projectDir.toAbsolutePath().normalize();
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IllegalArgumentException("No pom.xml found in " + root);
        }

        Element project = parse(pom).getDocumentElement();
        Optional<Element> parent = child(project, "parent");

        String groupId = text(project, "groupId").or(() -> parent.flatMap(p -> text(p, "groupId"))).orElse("unknown");
        String artifactId = text(project, "artifactId").orElse(root.getFileName().toString());
        String version = text(project, "version").or(() -> parent.flatMap(p -> text(p, "version"))).orElse("0");

        int javaRelease = config.javaRelease().isPresent()
                ? config.javaRelease().getAsInt()
                : detectJavaRelease(project);

        return new ProjectInfo(root, new Coordinate(groupId, artifactId, version), javaRelease,
                existing(root, "src/main/java"), existing(root, "src/main/resources"));
    }

    static int detectJavaRelease(Element project) {
        Optional<Element> props = child(project, "properties");
        if (props.isEmpty()) return DEFAULT_JAVA_RELEASE;
        for (String name : JAVA_PROPERTIES) {
            Optional<Integer> v = text(props.get(), name).flatMap(ProjectInspector::parseRelease);
            if (v.isPresent()) return v.get();
        }
        return DEFAULT_JAVA_RELEASE;
    }

    /** "17" -> 17, "1.8" -> 8, "${java.version}" -> empty. */
    static Optional<Integer> parseRelease(String raw) {
        String s = raw.trim();
        if (s.startsWith("1.")) s = s.substring(2);
        try {
            return Optional.of(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static List<Path> existing(Path root, String... rel) {
        List<Path> out = new ArrayList<>();
        for (String r : rel) {
            Path p = root.resolve(r);
            if (Files.isDirectory(p)) out.add(p);
        }
        return out;
    }

    private static Document parse(Path pom) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // Hardening: POMs are untrusted input, so block DTDs and external entities (XXE).
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(pom.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse " + pom + ": " + e.getMessage(), e);
        }
    }

    private static Optional<Element> child(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && name.equals(e.getTagName())) return Optional.of(e);
        }
        return Optional.empty();
    }

    private static Optional<String> text(Element parent, String name) {
        return child(parent, name).map(Element::getTextContent).map(String::trim).filter(s -> !s.isEmpty());
    }
}
