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
import ru.pashaapps.appfleet.install.InstallationDetector;
import ru.pashaapps.appfleet.persistence.AppPaths;
import ru.pashaapps.appfleet.persistence.AtomicJsonStore;
import ru.pashaapps.appfleet.persistence.RepositoriesDocument;
import ru.pashaapps.appfleet.persistence.RepositoryState;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppFleetServiceLocalReconciliationTest {
    @TempDir Path temporaryDirectory;
    private HttpServer server;
    private AppFleetService service;
    private AppPaths paths;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/repos/example/widget/releases", this::releases);
        server.start();
        paths = new AppPaths(temporaryDirectory.resolve("roaming"), temporaryDirectory.resolve("local"), temporaryDirectory.resolve("temp"), temporaryDirectory.resolve("program"));
    }

    @AfterEach void stop() {
        if (service != null) service.close();
        if (server != null) server.stop(0);
    }

    @Test void localExecutableRemovalIsReconciledEvenWhenGithubReturns304() throws Exception {
        Path installed = temporaryDirectory.resolve("Widget.exe");
        Files.writeString(installed, "old binary");
        RepositoryState persisted = new RepositoryState(1, "example", "widget", "https://github.com/example/widget", "EXE", "X64", Set.of("widget"),
                "1.0.0", 12L, "EXE", temporaryDirectory.toString(), installed.toString(), Set.of("Widget.exe"), null, null, null);
        new AtomicJsonStore<>(AppFleetObjectMapper.create(), RepositoriesDocument.class, paths.repositoriesFile()).write(new RepositoriesDocument(1, List.of(persisted)));
        HttpClient http = HttpClient.newHttpClient();
        GithubApiClient github = new GithubApiClient(http, AppFleetObjectMapper.create(), URI.create("http://localhost:" + server.getAddress().getPort() + "/"), "test");
        InstallationDetector noStandardInstallation = ignored -> Optional.empty();
        service = new AppFleetService(paths, AppFleetObjectMapper.create(), "1.0.11", github, new GithubAssetDownloader(http, "test"), noStandardInstallation, new OperationCoordinator());

        assertEquals(AppStatus.UP_TO_DATE, service.checkAll().get(5, TimeUnit.SECONDS).getFirst().status());
        Files.delete(installed);

        var afterRemoval = service.checkAll().get(5, TimeUnit.SECONDS).getFirst();
        assertEquals(AppStatus.NOT_INSTALLED, afterRemoval.status());
        assertNull(afterRemoval.persisted().installedVersion());
        assertNull(afterRemoval.persisted().executable());
    }

    private void releases(HttpExchange exchange) throws IOException {
        if ("\"widget-v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
            exchange.getResponseHeaders().add("ETag", "\"widget-v1\"");
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        byte[] body = "[{\"id\":11,\"tag_name\":\"v1.0.0\",\"name\":\"Widget\",\"body\":\"\",\"draft\":false,\"prerelease\":false,\"published_at\":\"2026-08-31T10:00:00Z\",\"html_url\":\"https://github.com/example/widget/releases/tag/v1.0.0\",\"assets\":[{\"id\":12,\"name\":\"Widget-Setup-1.0.0-x64.exe\",\"size\":42,\"browser_download_url\":\"https://github.com/example/widget/releases/download/v1.0.0/Widget-Setup-1.0.0-x64.exe\",\"content_type\":\"application/vnd.microsoft.portable-executable\"}]}]".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("ETag", "\"widget-v1\"");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
