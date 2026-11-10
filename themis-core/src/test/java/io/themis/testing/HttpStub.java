package io.themis.testing;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local HTTP server for hermetic tests (127.0.0.1, ephemeral port, never the network).
 * Responses are registered per raw request path; unregistered paths answer 404. A path can be
 * given a queue of responses (e.g. 503 then 200) to exercise retries.
 */
public final class HttpStub implements AutoCloseable {

    public record Response(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Deque<Response>> responses = new ConcurrentHashMap<>();
    private final Map<String, byte[]> binaries = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> headers = new CopyOnWriteArrayList<>();

    private HttpStub(HttpServer server) {
        this.server = server;
    }

    public static HttpStub start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HttpStub stub = new HttpStub(server);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getRawPath();
                stub.requests.add(path);
                stub.userAgents.add(String.valueOf(exchange.getRequestHeaders().getFirst("User-Agent")));
                stub.headers.add(exchange.getRequestHeaders().entrySet().toString());
                stub.bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] binary = stub.binaries.get(path);
                if (binary != null) {
                    exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
                    exchange.sendResponseHeaders(200, binary.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(binary);
                    }
                    return;
                }
                Deque<Response> queue = stub.responses.get(path);
                Response r = queue == null || queue.isEmpty() ? new Response(404, "{}")
                        : (queue.size() > 1 ? queue.pollFirst() : queue.peekFirst());
                byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(r.status(), body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Base URL, e.g. {@code http://127.0.0.1:54321/}. */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    /** Responds to {@code path} with these responses in order; the last one repeats. */
    public HttpStub on(String path, Response... rs) {
        responses.put(path, new ArrayDeque<>(List.of(rs)));
        return this;
    }

    /** Serves these bytes (e.g. a jar) at {@code path}. */
    public HttpStub onBytes(String path, byte[] body) {
        binaries.put(path, body.clone());
        return this;
    }

    /** Registers a deps.dev v3 version response for {@code g:a:v} with the given license strings. */
    public HttpStub depsDev(String gav, String... licenses) {
        String[] p = gav.split(":");
        StringBuilder json = new StringBuilder("{\"licenses\":[");
        for (int i = 0; i < licenses.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(licenses[i]).append('"');
        }
        json.append("]}");
        return on(depsDevPath(p[0], p[1], p[2]), new Response(200, json.toString()));
    }

    public static String depsDevPath(String groupId, String artifactId, String version) {
        return "/v3/systems/maven/packages/" + groupId + "%3A" + artifactId + "/versions/" + version;
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** Request bodies, in arrival order (POST payloads; empty for GET). */
    public List<String> bodies() {
        return List.copyOf(bodies);
    }

    /** Every request's headers, rendered as text, for leak assertions. */
    public List<String> headers() {
        return List.copyOf(headers);
    }

    /** Everything clients sent us: paths, headers and bodies. */
    public String everythingSent() {
        return String.join("\n", requests) + "\n" + String.join("\n", headers) + "\n" + String.join("\n", bodies);
    }

    public List<String> userAgents() {
        return List.copyOf(userAgents);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
