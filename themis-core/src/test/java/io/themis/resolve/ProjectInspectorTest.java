package io.themis.resolve;

import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.ProjectInfo;
import io.themis.model.Severity;
import io.themis.testing.FixtureRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectInspectorTest {

    @TempDir
    Path tmp;

    private FixtureRepo repo;
    private ProjectInspector inspector;

    @BeforeEach
    void setUp() {
        repo = FixtureRepo.create(tmp);
        inspector = new ProjectInspector(repo.options());
    }

    @Test
    void javaReleaseAndCoordinateAreInheritedFromRemoteParent() {
        repo.publish("com.acme:corp-parent:7", "pom", """
                <properties><maven.compiler.release>11</maven.compiler.release></properties>
                """);
        Path app = tmp.resolve("app");
        writePom(app, """
                <parent>
                  <groupId>com.acme</groupId><artifactId>corp-parent</artifactId><version>7</version>
                  <relativePath/>
                </parent>
                <artifactId>app</artifactId>
                """ + repo.repositoriesXml());

        ProjectInfo info = inspector.inspect(app, ScanConfig.defaults());

        assertThat(info.javaRelease()).isEqualTo(11);
        assertThat(info.coordinate()).isEqualTo(Coordinate.parse("com.acme:app:7"));
    }

    @Test
    void javaReleaseFromLocalParentInterpolatedAndLegacyFormat() {
        // parent on disk (default relativePath ../pom.xml), property interpolated into another
        writePom(tmp.resolve("parent"), """
                <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version>
                <packaging>pom</packaging>
                <properties>
                  <legacy>1.8</legacy>
                  <java.version>${legacy}</java.version>
                </properties>
                """);
        writePom(tmp.resolve("parent/child"), """
                <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1.0</version></parent>
                <artifactId>child</artifactId>
                """);

        assertThat(inspector.inspect(tmp.resolve("parent/child"), ScanConfig.defaults()).javaRelease()).isEqualTo(8);
    }

    @Test
    void releaseTakesPrecedenceOverJavaVersion() {
        writePom(tmp.resolve("p"), """
                <groupId>com.acme</groupId><artifactId>p</artifactId><version>1</version>
                <properties>
                  <java.version>11</java.version>
                  <maven.compiler.release>21</maven.compiler.release>
                </properties>
                """);

        assertThat(inspector.inspect(tmp.resolve("p"), ScanConfig.defaults()).javaRelease()).isEqualTo(21);
    }

    @Test
    void defaultsTo17AndConfigOverrideWins() {
        writePom(tmp.resolve("p"), "<groupId>com.acme</groupId><artifactId>p</artifactId><version>1</version>");
        ScanConfig override = new ScanConfig(Set.of(), Severity.HIGH, OptionalInt.of(8), false, false);

        assertThat(inspector.inspect(tmp.resolve("p"), ScanConfig.defaults()).javaRelease()).isEqualTo(17);
        assertThat(inspector.inspect(tmp.resolve("p"), override).javaRelease()).isEqualTo(8);
    }

    @Test
    void sourceRootsComeFromEffectiveBuildAndMustExist() throws IOException {
        Path p = tmp.resolve("p");
        writePom(p, """
                <groupId>com.acme</groupId><artifactId>p</artifactId><version>1</version>
                <build><sourceDirectory>src/java</sourceDirectory></build>
                """);
        Files.createDirectories(p.resolve("src/java"));

        ProjectInfo info = inspector.inspect(p, ScanConfig.defaults());

        assertThat(info.sourceRoots()).containsExactly(p.resolve("src/java").toAbsolutePath().normalize());
        assertThat(info.resourceRoots()).isEmpty();
    }

    @Test
    void rejectsPomWithDoctypeToPreventXxe() throws IOException {
        Path p = tmp.resolve("p");
        Files.createDirectories(p);
        Files.writeString(p.resolve("pom.xml"), """
                <?xml version="1.0"?>
                <!DOCTYPE project [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>&xxe;</artifactId><version>1</version>
                </project>""");

        java.io.PrintStream originalErr = System.err;
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(err, true));
        try {
            assertThatThrownBy(() -> inspector.inspect(p, ScanConfig.defaults()))
                    .hasMessageContaining("DOCTYPE");
        } finally {
            System.setErr(originalErr);
        }
        assertThat(err.toString()).as("no parser noise on stderr").doesNotContain("[Fatal Error]");
    }

    @Test
    void missingRemoteParentFailsWithResolutionExceptionNamingIt() {
        writePom(tmp.resolve("p"), """
                <parent><groupId>com.acme</groupId><artifactId>gone</artifactId><version>3</version><relativePath/></parent>
                <artifactId>p</artifactId>
                """ + repo.repositoriesXml());

        assertThatThrownBy(() -> inspector.inspect(tmp.resolve("p"), ScanConfig.defaults()))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("com.acme:gone:pom:3")
                .satisfies(e -> assertThat(((ResolutionException) e).unresolved()).containsExactly("com.acme:gone:pom:3"));
    }

    @Test
    void missingPomIsReported() {
        assertThatThrownBy(() -> inspector.inspect(tmp, ScanConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No pom.xml");
    }

    @Test
    void parseReleaseHandlesLegacyAndFullVersions() {
        assertThat(JavaRelease.parse("1.8")).contains(8);
        assertThat(JavaRelease.parse(" 17 ")).contains(17);
        assertThat(JavaRelease.parse("21.0.2")).contains(21);
        assertThat(JavaRelease.parse("${java.version}")).isEmpty();
    }

    private static void writePom(Path dir, String body) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      %s
                    </project>""".formatted(body));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
