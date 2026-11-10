package io.themis.secrets;

import io.themis.config.ScanConfig;
import io.themis.engine.AnalyzerResult;
import io.themis.engine.ScanContext;
import io.themis.engine.Stage;
import io.themis.model.Coordinate;
import io.themis.model.DependencyGraph;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.ProjectInfo;
import io.themis.model.Severity;
import io.themis.resolve.DependencyMetadata;
import io.themis.source.SourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecretAnalyzerTest {

    @TempDir
    Path root;

    // Built from parts so this test file itself does not look like it contains real keys.
    private static final String AWS = "AKIA" + "IOSFODNN7" + "EXAMPLE";
    private static final String GH = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
    private static final String DB_PASS = "Xk9#mP2vQz8wL4tR";

    @Test
    void identityAndStage() {
        assertThat(new SecretAnalyzer().name()).isEqualTo("secrets");
        assertThat(new SecretAnalyzer().stage()).isEqualTo(Stage.SOURCE);
    }

    @Test
    void findsKnownTokenFormatsAndHardcodedPasswordsInConfigFiles() throws Exception {
        write("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:mysql://db.internal:3306/orders
                    username: orders
                    password: %s
                aws:
                  access-key: %s
                """.formatted(DB_PASS, AWS));
        write(".env", "GITHUB_TOKEN=" + GH + "\nREDIS_URL=redis://admin:S3cr3tR3d1s@cache:6379\n");

        List<Finding> f = scan();

        assertThat(rules(f)).containsExactlyInAnyOrder("generic-secret-assignment", "aws-access-key", "github-token", "url-credentials");
        Finding aws = byRule(f, "aws-access-key");
        assertThat(aws.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(aws.location()).hasValueSatisfying(l -> {
            assertThat(l.file()).isEqualTo(Path.of("src/main/resources/application.yml"));
            assertThat(l.line()).isEqualTo(7);
        });
        assertThat(byRule(f, "generic-secret-assignment").title()).contains("password").contains("application.yml:5");
    }

    @Test
    void neverStoresTheSecretValueAnywhereInTheFinding() throws Exception {
        write("src/main/resources/application.properties", "spring.datasource.password=" + DB_PASS + "\naws.key=" + AWS + "\n");

        for (Finding f : scan()) {
            String everything = f.title() + f.detail() + f.evidence() + f.id();
            assertThat(everything).doesNotContain(DB_PASS).doesNotContain(AWS);
            assertThat(f.evidence().get("masked")).contains("****");
        }
    }

    @Test
    void placeholdersAndEnvironmentReferencesAreNotSecrets() throws Exception {
        write("src/main/resources/application.yml", """
                spring.datasource.password: ${DB_PASSWORD}
                api-key: "{{ vault.api_key }}"
                token: <your-token-here>
                secret: changeme
                password: ""
                client-secret: ENC(abcdef0123456789)
                jwt.secret: ${JWT_SECRET:}
                """);
        write("docker-compose.yml", "environment:\n  - PASSWORD=$DB_PASS\n");

        assertThat(scan()).isEmpty();
    }

    @Test
    void javaConstantsSettersAndValueDefaultsAreFound() throws Exception {
        write("src/main/java/com/acme/Client.java", """
                package com.acme;
                import org.springframework.beans.factory.annotation.Value;
                class Client {
                    private static final String API_KEY = "q8Zt3VnR5wYb2LmK9pXs";
                    private static final String GREETING = "hello world";
                    @Value("${payment.secret:Hn7Qw2Lp9Zx4Rt6Y}") String paymentSecret;
                    void connect(DataSource ds) { ds.setPassword("Pz8Lq2Wm5Nt7Kx3V"); }
                }
                """);

        List<Finding> f = scan();

        assertThat(rules(f)).containsExactlyInAnyOrder("java-hardcoded-secret", "java-hardcoded-secret", "spring-value-default-secret");
        assertThat(f).extracting(x -> x.location().orElseThrow().line()).containsExactlyInAnyOrder(4, 6, 7);
    }

    @Test
    void testFilesAreLowSeverityAndIgnoreCommentsAndExcludesSuppress() throws Exception {
        write("src/test/resources/application-test.yml", "password: " + DB_PASS + "\n");
        write("src/main/resources/a.properties", "token=Lq9Wz2Xp7Rk4Mn8T # themis:ignore\n");
        write("src/main/resources/b.properties", "# themis:ignore\nsecret=Vb3Nm8Qa2Ws6Ed4R\n");
        write("legacy/old.properties", "password=Tg5Yh7Uj2Ik9Ol3P\n");

        List<Finding> f = scan(new SourceConfig(Set.of("legacy/**"), Set.of(), Set.of()));

        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.location().orElseThrow().file()).isEqualTo(Path.of("src/test/resources/application-test.yml"));
            assertThat(x.severity()).isEqualTo(Severity.LOW);
        });
    }

    @Test
    void buildOutputAndVcsDirectoriesAreSkipped() throws Exception {
        write("target/classes/application.yml", "password: " + DB_PASS + "\n");
        write("build/resources/main/application.yml", "password: " + DB_PASS + "\n");
        write(".git/config", "password = " + DB_PASS + "\n");
        write("node_modules/x/.env", "TOKEN=" + GH + "\n");

        assertThat(scan()).isEmpty();
    }

    @Test
    void privateKeysAreCritical() throws Exception {
        write("deploy/key.pem", "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----\n");

        assertThat(scan()).singleElement().satisfies(x -> {
            assertThat(x.evidence()).containsEntry("rule", "private-key");
            assertThat(x.severity()).isEqualTo(Severity.CRITICAL);
        });
    }

    // --- helpers

    private List<Finding> scan() throws Exception {
        return scan(SourceConfig.defaults());
    }

    private List<Finding> scan(SourceConfig source) throws Exception {
        ProjectInfo p = new ProjectInfo(root, Coordinate.parse("com.acme:app:1"), 17, List.of(), List.of());
        ScanConfig cfg = ScanConfig.defaults().withSource(source);
        AnalyzerResult r = new SecretAnalyzer().analyze(new ScanContext(p, DependencyGraph.empty(p.coordinate()), cfg, DependencyMetadata.NONE));
        assertThat(r.findings()).allMatch(x -> x.type() == FindingType.SECRET);
        return r.findings();
    }

    private void write(String rel, String content) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private static List<String> rules(List<Finding> f) {
        return f.stream().map(x -> x.evidence().get("rule")).toList();
    }

    private static Finding byRule(List<Finding> f, String rule) {
        return f.stream().filter(x -> rule.equals(x.evidence().get("rule"))).findFirst().orElseThrow(() -> new AssertionError(rule + " in " + rules(f)));
    }
}
