package io.themis.upgrade;

import io.themis.Themis;
import io.themis.license.DepsDevClient;
import io.themis.model.Coordinate;
import io.themis.vuln.OsvClient;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Downloads a candidate version's jar from Maven Central (public coordinates only, HTTPS, no
 * redirects) so its bytecode can be checked. Jars land in an owner-only temp directory and are
 * deleted after reading; only the resulting Java release is cached.
 */
public final class JarFetcher {

    public static final URI MAVEN_CENTRAL = URI.create("https://repo.maven.apache.org/");

    private final URI base;
    private final Path dir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(DepsDevClient.proxySelector()).build();

    public JarFetcher(URI base, Path dir) {
        this.base = OsvClient.requireSafeUrl(base);
        this.dir = dir;
    }

    /** The jar, downloaded into a temp file the caller must delete; empty if Central has no jar for it. */
    Optional<Path> fetch(Coordinate c) throws IOException {
        String path = "maven2/" + c.groupId().replace('.', '/') + "/" + c.artifactId() + "/" + c.version() + "/"
                + c.artifactId() + "-" + c.version() + ".jar";
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "Themis/" + Themis.VERSION).GET().build();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "candidate", ".jar");
        try {
            HttpResponse<Path> r = http.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() == 404) {
                Files.deleteIfExists(tmp);
                return Optional.empty();
            }
            if (r.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IOException("Maven Central answered HTTP " + r.statusCode() + " for " + c);
            }
            return Optional.of(tmp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(tmp);
            throw new InterruptedIOException("Interrupted while downloading " + c);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
