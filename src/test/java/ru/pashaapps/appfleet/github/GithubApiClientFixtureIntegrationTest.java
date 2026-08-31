package ru.pashaapps.appfleet.github;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.pashaapps.appfleet.AppFleetObjectMapper;
import ru.pashaapps.appfleet.domain.RepositoryId;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Integration test against prepared GitHub REST responses, never GitHub HTML. */
class GithubApiClientFixtureIntegrationTest {
    private HttpServer server;
    private GithubApiClient client;
    private final AtomicInteger temporaryFailures = new AtomicInteger();
    private Instant rateLimitReset;
    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/Pasha-404/sortit", this::repository);
        server.createContext("/repos/Pasha-404/sortit/releases", this::releases);
        server.start();
        client = new GithubApiClient(HttpClient.newHttpClient(), AppFleetObjectMapper.create(), URI.create("http://localhost:" + server.getAddress().getPort() + "/"), "AppFleet-test/1.0.0");
    }
    @AfterEach void stopServer() { server.stop(0); }
    @Test void readsStableReleaseAndUsesEtagForUnchangedData() throws IOException {
        RepositoryId sortIt = RepositoryId.fromGithubUrl("https://github.com/Pasha-404/sortit/releases/tag/v1.5.0");
        GithubResponse<List<ru.pashaapps.appfleet.domain.GithubRelease>> first = client.listReleases(sortIt, null);
        assertFalse(first.notModified());
        assertEquals("v1.5.0", first.body().getFirst().tagName());
        assertEquals("\"fixture-v1\"", first.etag());
        GithubResponse<List<ru.pashaapps.appfleet.domain.GithubRelease>> unchanged = client.listReleases(sortIt, first.etag());
        assertTrue(unchanged.notModified());
    }

    @Test void retriesTemporaryGithubFailureBeforeReturningRelease() {
        temporaryFailures.set(1);
        GithubResponse<List<ru.pashaapps.appfleet.domain.GithubRelease>> response = client.listReleases(RepositoryId.fromGithubUrl("https://github.com/Pasha-404/sortit"), null);
        assertFalse(response.notModified());
        assertEquals(0, temporaryFailures.get());
    }

    @Test void exposesTheRateLimitResetForTheServiceCooldown() {
        rateLimitReset = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        GithubApiException failure = assertThrows(GithubApiException.class, () -> client.listReleases(RepositoryId.fromGithubUrl("https://github.com/Pasha-404/sortit"), null));
        assertEquals(429, failure.statusCode());
        assertEquals(rateLimitReset, failure.retryAt().orElseThrow());
    }

    private void repository(HttpExchange exchange) throws IOException { respond(exchange, 200, "{\"full_name\":\"Pasha-404/sortit\",\"private\":false,\"archived\":false}"); }
    private void releases(HttpExchange exchange) throws IOException {
        if (rateLimitReset != null) {
            exchange.getResponseHeaders().add("X-RateLimit-Reset", Long.toString(rateLimitReset.getEpochSecond()));
            respond(exchange, 429, "{\"message\":\"API rate limit exceeded\"}");
            return;
        }
        if (temporaryFailures.get() > 0 && temporaryFailures.getAndDecrement() > 0) {
            respond(exchange, 503, "{\"message\":\"Service unavailable\"}");
            return;
        }
        if ("\"fixture-v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) { exchange.getResponseHeaders().add("ETag", "\"fixture-v1\""); exchange.sendResponseHeaders(304, -1); return; }
        respond(exchange, 200, "[" + new String(resource("fixtures/sortit-release-v1.5.0.json"), StandardCharsets.UTF_8) + "]");
    }
    private static void respond(HttpExchange exchange, int status, String body) throws IOException { exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.getResponseHeaders().add("ETag", "\"fixture-v1\""); byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); }
    private static byte[] resource(String name) throws IOException { try (var input = GithubApiClientFixtureIntegrationTest.class.getClassLoader().getResourceAsStream(name)) { return input.readAllBytes(); } }
}
