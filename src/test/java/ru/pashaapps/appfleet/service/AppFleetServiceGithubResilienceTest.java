package ru.pashaapps.appfleet.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.pashaapps.appfleet.AppFleetObjectMapper;
import ru.pashaapps.appfleet.github.GithubApiClient;
import ru.pashaapps.appfleet.install.GithubAssetDownloader;
import ru.pashaapps.appfleet.persistence.AppPaths;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppFleetServiceGithubResilienceTest {
    @TempDir Path temporaryDirectory;
    private HttpServer server;
    private AppFleetService service;
    private final AtomicInteger releaseRequests = new AtomicInteger();
    private volatile boolean rateLimited;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/example/widget", this::repository);
        server.createContext("/repos/example/widget/releases", this::releases);
        server.start();
        HttpClient http = HttpClient.newHttpClient();
        GithubApiClient github = new GithubApiClient(http, AppFleetObjectMapper.create(), URI.create("http://localhost:" + server.getAddress().getPort() + "/"), "AppFleet-test/1.0.6");
        AppPaths paths = new AppPaths(temporaryDirectory.resolve("roaming"), temporaryDirectory.resolve("local"), temporaryDirectory.resolve("temp"), temporaryDirectory.resolve("program"));
        service = new AppFleetService(paths, AppFleetObjectMapper.create(), "1.0.6", github, new GithubAssetDownloader(http, "AppFleet-test/1.0.6"));
    }

    @AfterEach void stop() {
        if (service != null) service.close();
        if (server != null) server.stop(0);
    }

    @Test void preservesTheLastVerifiedRowAndStopsRepeatedRequestsDuringGithubRateLimit() throws Exception {
        var first = service.addRepository("https://github.com/example/widget").get(5, TimeUnit.SECONDS);
        assertEquals(AppStatus.NOT_INSTALLED, first.status());
        assertEquals(1, releaseRequests.get());

        rateLimited = true;
        var fallback = service.checkAll().get(5, TimeUnit.SECONDS).getFirst();
        assertEquals(AppStatus.NOT_INSTALLED, fallback.status());
        assertTrue(fallback.message().contains("последние подтверждённые данные"));
        assertEquals(2, releaseRequests.get());

        var repeated = service.checkAll().get(5, TimeUnit.SECONDS).getFirst();
        assertEquals(AppStatus.NOT_INSTALLED, repeated.status());
        assertTrue(repeated.message().contains("временно ограничил запросы"));
        assertEquals(2, releaseRequests.get());
    }

    private void repository(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"full_name\":\"example/widget\",\"private\":false,\"archived\":false}");
    }

    private void releases(HttpExchange exchange) throws IOException {
        releaseRequests.incrementAndGet();
        if (rateLimited) {
            exchange.getResponseHeaders().add("X-RateLimit-Reset", Long.toString(Instant.now().plusSeconds(600).getEpochSecond()));
            respond(exchange, 429, "{\"message\":\"API rate limit exceeded\"}");
            return;
        }
        exchange.getResponseHeaders().add("ETag", "\"widget-v1\"");
        respond(exchange, 200, "[{\"id\":11,\"tag_name\":\"v1.0.0\",\"name\":\"Widget\",\"body\":\"\",\"draft\":false,\"prerelease\":false,\"published_at\":\"2026-08-31T10:00:00Z\",\"html_url\":\"https://github.com/example/widget/releases/tag/v1.0.0\",\"assets\":[{\"id\":12,\"name\":\"Widget-Setup-1.0.0-x64.exe\",\"size\":42,\"browser_download_url\":\"https://github.com/example/widget/releases/download/v1.0.0/Widget-Setup-1.0.0-x64.exe\",\"content_type\":\"application/vnd.microsoft.portable-executable\"}]}]");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
