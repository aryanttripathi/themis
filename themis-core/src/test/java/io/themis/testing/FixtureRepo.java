package io.themis.testing;

import io.themis.resolve.maven.ResolverOptions;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * A hermetic, on-disk "remote" Maven repository for tests (Maven 2 layout under {@code root}),
 * plus helpers to write fixture projects and a settings.xml that keeps the resolver away from
 * the network and from the real {@code ~/.m2}.
 *
 * <pre>{@code
 * FixtureRepo repo = FixtureRepo.create(tmp);
 * repo.publish("com.acme:b:1.0", "jar", "");
 * repo.publish("com.acme:a:1.0", "jar", FixtureRepo.deps("com.acme:b:1.0"));
 * Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", FixtureRepo.deps("com.acme:a:1.0"));
 * }</pre>
 */
public final class FixtureRepo {

    private final Path base;
    private final Path remote;
    /** g:a -> published versions, for maven-metadata.xml (needed by version ranges). */
    private final Map<String, List<String>> versions = new LinkedHashMap<>();

    private FixtureRepo(Path base) {
        this.base = base;
        this.remote = base.resolve("remote-repo");
    }

    /** Creates the repository (and an empty "central" mirror target) below {@code base}. */
    public static FixtureRepo create(Path base) {
        try {
            FixtureRepo repo = new FixtureRepo(base);
            Files.createDirectories(repo.remote);
            Files.createDirectories(base.resolve("empty-central"));
            return repo;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path root() {
        return remote;
    }

    public String url() {
        return remote.toUri().toString();
    }

    /**
     * Publishes {@code g:a:v} with the given packaging. For {@code jar} packaging a tiny jar is
     * written next to the POM. {@code extraXml} is inserted verbatim inside {@code <project>}.
     */
    public Path publish(String gav, String packaging, String extraXml) {
        writePom(gav, pom(gav, packaging, extraXml));
        return "jar".equals(packaging) ? publishFile(gav, "", "jar") : versionDir(gav).resolve(fileBase(gav) + ".jar");
    }

    /** Publishes an extra artifact next to an existing one, e.g. classifier {@code linux-x86_64} or {@code tests}. */
    public Path publishClassified(String gav, String classifier) {
        return publishFile(gav, classifier, "jar");
    }

    /** Publishes only the jar, no POM ("The POM for ... is missing"). */
    public Path publishJarWithoutPom(String gav) {
        return publishFile(gav, "", "jar");
    }

    /** Publishes a POM with arbitrary (possibly broken) content, plus a jar. */
    public Path publishRawPom(String gav, String content) {
        writePom(gav, content);
        return publishFile(gav, "", "jar");
    }

    /** Writes {@code dir/pom.xml} for a project whose {@code <repositories>} points at this repo. */
    public Path project(Path dir, String gav, String extraXml) {
        return project(dir, gav, "jar", extraXml);
    }

    public Path project(Path dir, String gav, String packaging, String extraXml) {
        write(dir.resolve("pom.xml"), pom(gav, packaging, extraXml + repositoriesXml()));
        return dir;
    }

    /** {@code <repositories>} block pointing at this fixture repository. */
    public String repositoriesXml() {
        return """
                <repositories>
                  <repository>
                    <id>fixture</id>
                    <url>%s</url>
                    <releases><enabled>true</enabled></releases>
                    <snapshots><enabled>true</enabled></snapshots>
                  </repository>
                </repositories>
                """.formatted(url());
    }

    /**
     * Resolver options using a generated settings.xml: a fresh local repository under the base
     * directory and a mirror that redirects {@code central} to an empty directory, so nothing
     * ever goes to the network or to the real ~/.m2. No global settings, no settings-security.
     */
    public ResolverOptions options() {
        return options("");
    }

    /** As {@link #options()}, with extra XML inside {@code <settings>} (servers, profiles, ...). */
    public ResolverOptions options(String extraSettingsXml) {
        return options("", extraSettingsXml);
    }

    /** As {@link #options(String)}, with extra {@code <mirror>} entries before the central mirror. */
    public ResolverOptions options(String extraMirrorsXml, String extraSettingsXml) {
        Path settings = base.resolve("settings.xml");
        write(settings, """
                <settings>
                  <localRepository>%s</localRepository>
                  <mirrors>
                    %s
                    <mirror>
                      <id>no-central</id>
                      <mirrorOf>central</mirrorOf>
                      <url>%s</url>
                    </mirror>
                  </mirrors>
                  %s
                </settings>
                """.formatted(localRepo(), extraMirrorsXml, base.resolve("empty-central").toUri(), extraSettingsXml));
        return new ResolverOptions(settings, Optional.empty(), Optional.empty(),
                base.resolve("settings-security.xml"), false);
    }

    public Path localRepo() {
        return base.resolve("local-repo");
    }

    /**
     * Builds a {@code <dependencies>} block. Each spec is {@code g:a:v[:scope[:optional]]};
     * use an empty scope (e.g. {@code g:a:v::optional}) for a default-scope optional dependency.
     */
    public static String deps(String... specs) {
        StringBuilder sb = new StringBuilder("<dependencies>\n");
        for (String spec : specs) {
            sb.append(dependencyXml(spec, ""));
        }
        return sb.append("</dependencies>\n").toString();
    }

    /** A single {@code <dependency>} with {@code <exclusions>} of the given {@code g:a} entries. */
    public static String depExcluding(String gav, String... excludedGas) {
        StringBuilder ex = new StringBuilder("<exclusions>");
        for (String ga : excludedGas) {
            String[] p = ga.split(":");
            ex.append("<exclusion><groupId>").append(p[0]).append("</groupId><artifactId>")
                    .append(p[1]).append("</artifactId></exclusion>");
        }
        ex.append("</exclusions>");
        return "<dependencies>\n" + dependencyXml(gav, ex.toString()) + "</dependencies>\n";
    }

    private static String dependencyXml(String spec, String inner) {
        String[] p = spec.split(":", -1);
        StringBuilder sb = new StringBuilder("  <dependency><groupId>").append(p[0])
                .append("</groupId><artifactId>").append(p[1]).append("</artifactId>");
        if (p.length > 2 && !p[2].isEmpty()) sb.append("<version>").append(p[2]).append("</version>");
        if (p.length > 3 && !p[3].isEmpty()) sb.append("<scope>").append(p[3]).append("</scope>");
        if (p.length > 4 && "optional".equals(p[4])) sb.append("<optional>true</optional>");
        return sb.append(inner).append("</dependency>\n").toString();
    }

    private static String pom(String gav, String packaging, String extraXml) {
        String[] p = gav.split(":");
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>%s</packaging>
                %s
                </project>
                """.formatted(p[0], p[1], p[2], packaging, extraXml);
    }

    private void writePom(String gav, String content) {
        write(versionDir(gav).resolve(fileBase(gav) + ".pom"), content);
        recordVersion(gav);
    }

    private Path publishFile(String gav, String classifier, String extension) {
        Path file = versionDir(gav).resolve(fileBase(gav) + (classifier.isEmpty() ? "" : "-" + classifier) + "." + extension);
        writeJar(file, gav + ":" + classifier);
        recordVersion(gav);
        return file;
    }

    /** Keeps {@code g/a/maven-metadata.xml} listing every published version. */
    private void recordVersion(String gav) {
        String[] p = gav.split(":");
        List<String> vs = versions.computeIfAbsent(p[0] + ":" + p[1], k -> new ArrayList<>());
        if (vs.contains(p[2])) return;
        vs.add(p[2]);
        StringBuilder xml = new StringBuilder("<metadata><groupId>").append(p[0]).append("</groupId><artifactId>")
                .append(p[1]).append("</artifactId><versioning><versions>");
        vs.forEach(v -> xml.append("<version>").append(v).append("</version>"));
        xml.append("</versions></versioning></metadata>");
        write(remote.resolve(p[0].replace('.', '/')).resolve(p[1]).resolve("maven-metadata.xml"), xml.toString());
    }

    private Path versionDir(String gav) {
        String[] p = gav.split(":");
        return remote.resolve(p[0].replace('.', '/')).resolve(p[1]).resolve(p[2]);
    }

    private static String fileBase(String gav) {
        String[] p = gav.split(":");
        return p[1] + "-" + p[2];
    }

    private static void writeJar(Path jar, String content) {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try {
            Files.createDirectories(jar.getParent());
            try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os, mf)) {
                out.putNextEntry(new JarEntry("fixture.txt"));
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a file, creating parent directories. */
    public static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
