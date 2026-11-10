package io.themis.pii;

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

class PiiAnalyzerTest {

    @TempDir
    Path root;

    @Test
    void identityAndStage() {
        assertThat(new PiiAnalyzer().name()).isEqualTo("pii");
        assertThat(new PiiAnalyzer().stage()).isEqualTo(Stage.SOURCE);
    }

    @Test
    void piiFieldsAndGettersPassedToLoggersAreHigh() throws Exception {
        java("OrderService", """
                package com.acme;
                class OrderService {
                    private static final org.slf4j.Logger log = null;
                    void place(Customer customer, String phoneNumber, long orderId) {
                        log.info("placing order {} for {}", orderId, customer.getEmail());
                        log.debug("otp sent to " + phoneNumber);
                        log.info("order {} placed", orderId);
                        System.out.println("aadhaar=" + customer.aadhaarNumber);
                    }
                }
                """);

        List<Finding> f = scan();

        assertThat(rules(f)).containsExactly("pii-in-log", "pii-in-log", "pii-in-log");
        assertThat(f).allMatch(x -> x.severity() == Severity.HIGH);
        assertThat(lines(f)).containsExactly(5, 6, 8);
        assertThat(f.get(0).title()).contains("email").contains("OrderService.java:5");
        assertThat(f.get(2).evidence()).containsEntry("field", "aadhaarNumber");
    }

    @Test
    void loggingAWholeEntityWithPiiFieldsIsFlagged() throws Exception {
        java("Customer", """
                package com.acme;
                @lombok.Data
                class Customer { private Long id; private String email; private String mobile; }
                """);
        java("Handler", """
                package com.acme;
                class Handler {
                    private static final org.slf4j.Logger LOGGER = null;
                    void on(Customer c, Long id) { LOGGER.warn("customer {}", c); LOGGER.warn("id {}", id); }
                }
                """);

        List<Finding> f = scan();

        assertThat(rules(f)).containsExactlyInAnyOrder("pii-tostring", "entity-in-log");
        Finding entity = f.stream().filter(x -> x.evidence().get("rule").equals("entity-in-log")).findFirst().orElseThrow();
        assertThat(entity.severity()).isEqualTo(Severity.HIGH);
        assertThat(entity.title()).contains("Customer").contains("Handler.java:4");
    }

    @Test
    void lombokToStringAndRecordsPrintPiiUnlessExcluded() throws Exception {
        java("A", "package com.acme;\n@lombok.Data class A { String panNumber; String city; }\n");
        java("B", "package com.acme;\n@lombok.ToString class B { @lombok.ToString.Exclude String panNumber; String city; }\n");
        java("C", "package com.acme;\n@lombok.ToString(onlyExplicitlyIncluded = true) class C { String email; }\n");
        java("D", "package com.acme;\nrecord D(String dateOfBirth, int age) {}\n");
        java("E", "package com.acme;\nclass E { String ifsc; public String toString() { return \"E \" + ifsc; } }\n");
        java("F", "package com.acme;\n@lombok.Getter class F { String email; }\n");
        java("G", "package com.acme;\n@lombok.Data @lombok.ToString(onlyExplicitlyIncluded = true) class G { @lombok.ToString.Include Long id; String email; }\n");
        java("H", "package com.acme;\n@lombok.Data @lombok.ToString(onlyExplicitlyIncluded = true) class H { @lombok.ToString.Include String email; String city; }\n");

        List<Finding> f = scan();

        assertThat(f).extracting(x -> x.location().orElseThrow().file().getFileName().toString())
                .containsExactlyInAnyOrder("A.java", "D.java", "E.java", "H.java");
        assertThat(f).allMatch(x -> x.evidence().get("rule").equals("pii-tostring") && x.severity() == Severity.MEDIUM);
    }

    @Test
    void piiInExceptionMessages() throws Exception {
        java("V", """
                package com.acme;
                class V { void check(String email) { if (email == null) throw new IllegalArgumentException("bad email " + email); } }
                """);

        assertThat(scan()).singleElement().satisfies(x -> {
            assertThat(x.evidence()).containsEntry("rule", "pii-in-exception");
            assertThat(x.severity()).isEqualTo(Severity.MEDIUM);
        });
    }

    @Test
    void vocabularyMatchesWholeTokensOnly() {
        PiiVocabulary v = PiiVocabulary.defaults();
        assertThat(v.match("customerPhoneNo")).hasValue("phone");
        assertThat(v.match("DATE_OF_BIRTH")).hasValue("date of birth");
        assertThat(v.match("panCard")).hasValue("pan");
        assertThat(v.match("company")).isEmpty();          // contains "pan" only inside a word
        assertThat(v.match("telephone")).hasValue("phone");
        assertThat(v.match("contactNumber")).hasValue("phone");
        assertThat(v.match("customerEmail")).hasValue("email");
        assertThat(v.match("emailVerifiedFlag")).isEmpty();      // a boolean about the email, not the email
        assertThat(v.match("emailTemplateId")).isEmpty();  // ids/templates/counts are not PII values
        assertThat(v.match("addressCount")).isEmpty();
    }

    @Test
    void configCanAddAndIgnoreFieldsAndTestsAreSkipped() throws Exception {
        java("S", """
                package com.acme;
                class S {
                    static final org.slf4j.Logger log = null;
                    void a(String customerId, String email) { log.info("{} {}", customerId, email); }
                }
                """);
        Path test = root.resolve("src/test/java/com/acme/T.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "package com.acme;\nclass T { static final org.slf4j.Logger log = null; void t(String email) { log.info(email); } }\n");

        List<Finding> f = scan(new SourceConfig(Set.of(), Set.of("customerId"), Set.of("email")));

        assertThat(f).singleElement().satisfies(x -> assertThat(x.evidence()).containsEntry("field", "customerId"));
    }

    @Test
    void ignoreCommentSuppressesALine() throws Exception {
        java("S", """
                package com.acme;
                class S { static final org.slf4j.Logger log = null;
                  void a(String email) {
                    log.info("{}", email); // themis:ignore masked upstream
                  }
                }
                """);

        assertThat(scan()).isEmpty();
    }

    // --- helpers

    private List<Finding> scan() throws Exception {
        return scan(SourceConfig.defaults());
    }

    private List<Finding> scan(SourceConfig source) throws Exception {
        ProjectInfo p = new ProjectInfo(root, Coordinate.parse("com.acme:app:1"), 17, List.of(), List.of());
        ScanConfig cfg = ScanConfig.defaults().withSource(source);
        AnalyzerResult r = new PiiAnalyzer().analyze(new ScanContext(p, DependencyGraph.empty(p.coordinate()), cfg, DependencyMetadata.NONE));
        assertThat(r.findings()).allMatch(x -> x.type() == FindingType.PII);
        return r.findings().stream().sorted(java.util.Comparator.comparing((Finding x) -> x.location().orElseThrow().file().toString())
                .thenComparingInt(x -> x.location().orElseThrow().line())).toList();
    }

    private void java(String name, String code) throws Exception {
        Path f = root.resolve("src/main/java/com/acme/" + name + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, code);
    }

    private static List<String> rules(List<Finding> f) {
        return f.stream().map(x -> x.evidence().get("rule")).toList();
    }

    private static List<Integer> lines(List<Finding> f) {
        return f.stream().map(x -> x.location().orElseThrow().line()).toList();
    }
}
