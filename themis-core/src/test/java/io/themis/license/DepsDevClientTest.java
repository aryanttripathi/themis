package io.themis.license;

import io.themis.model.Coordinate;
import io.themis.testing.HttpStub;
import io.themis.testing.HttpStub.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepsDevClientTest {

    private final HttpStub stub = HttpStub.start();
    private final DepsDevClient client = DepsDevClient.create(stub.baseUrl(), Duration.ofMillis(1));

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void readsLicensesOfAVersionWithUrlEncodedPackageNameAndUserAgent() throws IOException {
        stub.depsDev("org.apache.commons:commons-text:1.12.0", "Apache-2.0");

        assertThat(client.licenses(Coordinate.parse("org.apache.commons:commons-text:1.12.0")))
                .contains(List.of("Apache-2.0"));
        assertThat(stub.requests()).containsExactly("/v3/systems/maven/packages/org.apache.commons%3Acommons-text/versions/1.12.0");
        assertThat(stub.userAgents()).allSatisfy(ua -> assertThat(ua).startsWith("Themis/"));
    }

    @Test
    void notFoundIsEmptyNotAnError() throws IOException {
        assertThat(client.licenses(Coordinate.parse("com.acme:private:1.0"))).isEmpty();
        assertThat(stub.requests()).hasSize(1); // 404 is an answer: no retry
    }

    @Test
    void retriesTransientFailuresTwiceThenSucceeds() throws IOException {
        stub.on(HttpStub.depsDevPath("g", "a", "1"), new Response(503, ""), new Response(429, ""),
                new Response(200, "{\"licenses\":[\"MIT\"]}"));

        assertThat(client.licenses(Coordinate.parse("g:a:1"))).contains(List.of("MIT"));
        assertThat(stub.requests()).hasSize(3);
    }

    @Test
    void givesUpAfterTwoRetriesWithAClearError() {
        stub.on(HttpStub.depsDevPath("g", "a", "1"), new Response(500, ""));

        assertThatThrownBy(() -> client.licenses(Coordinate.parse("g:a:1")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("deps.dev")
                .hasMessageContaining("500")
                .hasMessageContaining("3 attempts");
        assertThat(stub.requests()).hasSize(3);
    }

    @Test
    void responseWithoutLicensesFieldIsAnEmptyList() throws IOException {
        stub.on(HttpStub.depsDevPath("g", "a", "1"), new Response(200, "{\"versionKey\":{}}"));

        assertThat(client.licenses(Coordinate.parse("g:a:1"))).contains(List.of());
    }
}
