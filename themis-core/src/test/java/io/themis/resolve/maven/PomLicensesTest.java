package io.themis.resolve.maven;

import io.themis.model.Coordinate;
import io.themis.resolve.DeclaredLicense;
import io.themis.resolve.PomLicenses;
import io.themis.resolve.ProjectSession;
import io.themis.testing.FixtureRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.themis.resolve.maven.MavenDependencyResolverTest.DEFAULTS;
import static io.themis.testing.FixtureRepo.deps;
import static org.assertj.core.api.Assertions.assertThat;

/** License declarations of dependencies, read from their effective POMs through the scan's session. */
class PomLicensesTest {

    @TempDir
    Path tmp;

    private FixtureRepo repo;

    @BeforeEach
    void setUp() {
        repo = FixtureRepo.create(tmp);
    }

    static String licenses(String... nameUrlPairs) {
        StringBuilder sb = new StringBuilder("<licenses>");
        for (int i = 0; i < nameUrlPairs.length; i += 2) {
            sb.append("<license><name>").append(nameUrlPairs[i]).append("</name>");
            if (nameUrlPairs[i + 1] != null) sb.append("<url>").append(nameUrlPairs[i + 1]).append("</url>");
            sb.append("</license>");
        }
        return sb.append("</licenses>").toString();
    }

    @Test
    void readsOwnInheritedMissingAndUnreadablePomLicenses() {
        repo.publish("com.acme:own:1.0", "jar", licenses("MIT License", "https://opensource.org/licenses/MIT",
                "Apache 2", null));
        repo.publish("com.acme:corp-parent:3", "pom", licenses("The Apache Software License, Version 2.0", null));
        repo.publish("com.acme:child:1.0", "jar",
                "<parent><groupId>com.acme</groupId><artifactId>corp-parent</artifactId><version>3</version></parent>");
        repo.publish("com.acme:none:1.0", "jar", "");
        repo.publishJarWithoutPom("com.acme:nopom:1.0");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0",
                deps("com.acme:own:1.0", "com.acme:child:1.0", "com.acme:none:1.0", "com.acme:nopom:1.0"));

        try (ProjectSession session = new MavenDependencyResolver(repo.options()).open(app, DEFAULTS)) {
            session.resolveDependencies();

            PomLicenses own = session.pomLicenses(Coordinate.parse("com.acme:own:1.0"));
            assertThat(own.licenses()).containsExactly(
                    new DeclaredLicense("MIT License", "https://opensource.org/licenses/MIT"),
                    new DeclaredLicense("Apache 2", null));
            assertThat(own.inherited()).isFalse();

            PomLicenses child = session.pomLicenses(Coordinate.parse("com.acme:child:1.0"));
            assertThat(child.licenses()).extracting(DeclaredLicense::name)
                    .containsExactly("The Apache Software License, Version 2.0");
            assertThat(child.inherited()).isTrue();

            assertThat(session.pomLicenses(Coordinate.parse("com.acme:none:1.0")).licenses()).isEmpty();
            assertThat(session.pomLicenses(Coordinate.parse("com.acme:none:1.0")).problem()).isEmpty();

            PomLicenses nopom = session.pomLicenses(Coordinate.parse("com.acme:nopom:1.0"));
            assertThat(nopom.licenses()).isEmpty();
            assertThat(nopom.problem()).hasValueSatisfying(p -> assertThat(p).contains("com.acme:nopom:pom:1.0"));
        }
    }

    @Test
    void classifiedArtifactsUseTheMainArtifactPom() {
        repo.publish("com.acme:native:1.0", "jar", licenses("MIT License", null));
        repo.publishClassified("com.acme:native:1.0", "linux-x86_64");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", """
                <dependencies><dependency><groupId>com.acme</groupId><artifactId>native</artifactId>
                  <version>1.0</version><classifier>linux-x86_64</classifier></dependency></dependencies>""");

        try (ProjectSession session = new MavenDependencyResolver(repo.options()).open(app, DEFAULTS)) {
            session.resolveDependencies();
            assertThat(session.pomLicenses(Coordinate.parse("com.acme:native:jar:linux-x86_64:1.0")).licenses())
                    .extracting(DeclaredLicense::name).containsExactly("MIT License");
        }
    }
}
