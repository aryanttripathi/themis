package io.themis.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.themis.Themis;
import io.themis.model.Coordinate;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the licenses deps.dev knows for a Maven version:
 * {@code GET {base}/v3/systems/maven/packages/{urlencoded g:a}/versions/{v}} -> {@code licenses[]}
 * (already SPDX, or "non-standard"). Only the coordinate leaves the machine.
 * <p>
 * 10 s connect/request timeouts; IO errors, 429 and 5xx are retried twice with exponential backoff;
 * 404 is an answer ("deps.dev does not know this version"), not an error. Proxy: the JVM's
 * {@code https.proxyHost}/{@code http.nonProxyHosts} settings if present, otherwise the
 * {@code HTTPS_PROXY} / {@code NO_PROXY} environment variables; loopback is never proxied.
 */
public final class DepsDevClient {

    public static final URI DEFAULT_BASE_URL = URI.create("https://api.deps.dev/");
    static final int RETRIES = 2;
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI baseUrl;
    private final HttpClient http;
    private final Duration backoff;

    DepsDevClient(URI baseUrl, HttpClient http, Duration backoff) {
        this.baseUrl = baseUrl.toString().endsWith("/") ? baseUrl : URI.create(baseUrl + "/");
        this.http = http;
        this.backoff = backoff;
    }

    /** Production client: 500 ms initial backoff (doubled per retry). */
    public static DepsDevClient create(URI baseUrl) {
        return create(baseUrl, Duration.ofMillis(500));
    }

    public static DepsDevClient create(URI baseUrl, Duration initialBackoff) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(proxySelector())
                .build();
        return new DepsDevClient(baseUrl, http, initialBackoff);
    }

    /**
     * @return the version's license strings ({@code Optional.empty()} if deps.dev does not know it)
     * @throws IOException if deps.dev cannot be reached or keeps failing after the retries
     */
    public Optional<List<String>> licenses(Coordinate c) throws IOException {
        URI uri = baseUrl.resolve("v3/systems/maven/packages/"
                + URLEncoder.encode(c.ga(), StandardCharsets.UTF_8) + "/versions/"
                + URLEncoder.encode(c.version(), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "Themis/" + Themis.VERSION)
                .header("Accept", "application/json")
                .GET()
                .build();

        String lastProblem = "";
        IOException lastException = null;
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            if (attempt > 0) sleep(backoff.multipliedBy(1L << (attempt - 1)));
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) return Optional.of(parse(response.body(), c));
                if (status == 404) return Optional.empty();
                lastProblem = "HTTP " + status;
                lastException = null;
                if (status != 429 && status < 500) break; // other 4xx: retrying will not help
            } catch (IOException e) {
                lastProblem = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                lastException = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while querying deps.dev for " + c);
            }
        }
        String message = "deps.dev request for " + c + " failed after " + (RETRIES + 1) + " attempts: " + lastProblem;
        throw lastException == null ? new IOException(message) : new IOException(message, lastException);
    }

    private static List<String> parse(String body, Coordinate c) throws IOException {
        JsonNode licenses = MAPPER.readTree(body).path("licenses");
        if (!licenses.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        licenses.forEach(n -> {
            if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText());
        });
        return List.copyOf(out);
    }

    private static void sleep(Duration d) throws InterruptedIOException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while backing off from deps.dev");
        }
    }

    public static ProxySelector proxySelector() {
        if (System.getProperty("https.proxyHost") != null) return ProxySelector.getDefault();
        String env = Optional.ofNullable(System.getenv("HTTPS_PROXY")).orElse(System.getenv("https_proxy"));
        if (env == null || env.isBlank()) return ProxySelector.getDefault();
        URI proxy = URI.create(env.contains("://") ? env : "http://" + env);
        String noProxy = Optional.ofNullable(System.getenv("NO_PROXY")).orElse(Optional.ofNullable(System.getenv("no_proxy")).orElse(""));
        return new EnvProxySelector(new InetSocketAddress(proxy.getHost(), proxy.getPort() > 0 ? proxy.getPort() : 80), noProxy);
    }

    /** {@code HTTPS_PROXY} with {@code NO_PROXY} (comma-separated host suffixes); loopback always direct. */
    private static final class EnvProxySelector extends ProxySelector {
        private final Proxy proxy;
        private final List<String> noProxy;

        EnvProxySelector(SocketAddress address, String noProxy) {
            this.proxy = new Proxy(Proxy.Type.HTTP, address);
            List<String> entries = new ArrayList<>();
            for (String e : noProxy.split(",")) {
                String s = e.trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) entries.add(s.startsWith("*") ? s.substring(1) : s);
            }
            this.noProxy = List.copyOf(entries);
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean direct = host.equals("localhost") || host.startsWith("127.") || host.equals("::1") || host.equals("[::1]")
                    || noProxy.stream().anyMatch(n -> host.equals(n) || host.endsWith(n.startsWith(".") ? n : "." + n));
            return List.of(direct ? Proxy.NO_PROXY : proxy);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // nothing to remember: the request itself reports the failure
        }
    }
}
