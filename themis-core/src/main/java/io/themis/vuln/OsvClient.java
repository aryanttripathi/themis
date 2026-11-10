package io.themis.vuln;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.themis.Themis;
import io.themis.license.DepsDevClient;
import io.themis.model.Coordinate;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Minimal client for the OSV API (https://google.github.io/osv.dev/api/).
 *
 * <p>Privacy: requests carry only Maven {@code group:artifact} names and versions, a fixed
 * {@code User-Agent: Themis/<version>} and {@code Accept}/{@code Content-Type} headers: no cookies, no
 * authentication, no paths or project details. Plain HTTP is refused except to loopback (tests),
 * and redirects are never followed, so payloads cannot be bounced to another host.
 */
public final class OsvClient {

    public static final URI DEFAULT_BASE_URL = URI.create("https://api.osv.dev/");
    static final int BATCH = 1000;
    static final int RETRIES = 2;
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI baseUrl;
    private final HttpClient http;
    private final Duration backoff;

    private OsvClient(URI baseUrl, HttpClient http, Duration backoff) {
        this.baseUrl = requireSafe(baseUrl);
        this.http = http;
        this.backoff = backoff;
    }

    public static OsvClient create(URI baseUrl) {
        return create(baseUrl, Duration.ofMillis(500));
    }

    public static OsvClient create(URI baseUrl, Duration initialBackoff) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(DepsDevClient.proxySelector())
                .build();
        return new OsvClient(baseUrl, http, initialBackoff);
    }

    static URI requireSafe(URI uri) {
        String host = String.valueOf(uri.getHost());
        boolean loopback = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback) {
            throw new IllegalArgumentException("Refusing non-HTTPS vulnerability database URL " + uri);
        }
        return uri;
    }

    /** Advisory ids affecting each coordinate, in input order. */
    public List<List<String>> query(List<Coordinate> coordinates) throws IOException {
        List<List<String>> out = new ArrayList<>();
        for (int from = 0; from < coordinates.size(); from += BATCH) {
            List<Coordinate> chunk = coordinates.subList(from, Math.min(coordinates.size(), from + BATCH));
            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode queries = body.putArray("queries");
            for (Coordinate c : chunk) {
                ObjectNode q = queries.addObject();
                q.putObject("package").put("ecosystem", "Maven").put("name", c.ga());
                q.put("version", c.version());
            }
            JsonNode results = send(post("v1/querybatch", MAPPER.writeValueAsString(body))).path("results");
            for (int i = 0; i < chunk.size(); i++) {
                List<String> ids = new ArrayList<>();
                results.path(i).path("vulns").forEach(v -> ids.add(v.path("id").asText()));
                out.add(ids);
            }
        }
        return out;
    }

    /** Full advisory record, or empty if OSV does not know the id. */
    public Optional<JsonNode> vulnerability(String id) throws IOException {
        HttpRequest get = request("v1/vulns/" + URLEncoder.encode(id, StandardCharsets.UTF_8)).GET().build();
        JsonNode node = send(get);
        return node == null ? Optional.empty() : Optional.of(node);
    }

    private HttpRequest post(String path, String json) {
        return request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(TIMEOUT)
                .header("User-Agent", "Themis/" + Themis.VERSION).header("Accept", "application/json");
    }

    /** JSON body; null on 404; IOException after retries on 429/5xx/network errors. */
    private JsonNode send(HttpRequest request) throws IOException {
        String problem = "";
        IOException last = null;
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            if (attempt > 0) sleep(backoff.multipliedBy(1L << (attempt - 1)));
            try {
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return MAPPER.readTree(r.body());
                if (r.statusCode() == 404) return null;
                problem = "HTTP " + r.statusCode();
                last = null;
                if (r.statusCode() != 429 && r.statusCode() < 500) break;
            } catch (IOException e) {
                problem = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while querying OSV");
            }
        }
        String message = "OSV request " + request.uri().getPath() + " failed after " + (RETRIES + 1) + " attempts: " + problem;
        throw last == null ? new IOException(message) : new IOException(message, last);
    }

    private static void sleep(Duration d) throws InterruptedIOException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted");
        }
    }
}
