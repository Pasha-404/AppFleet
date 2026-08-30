package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

/** Streams only a GitHub-provided HTTPS asset into one bounded operation directory. */
public final class GithubAssetDownloader {
    private static final Set<String> INITIAL_HOSTS = Set.of("github.com", "api.github.com");
    private final HttpClient client;
    private final String userAgent;
    public GithubAssetDownloader(HttpClient client, String userAgent) { this.client = client; this.userAgent = userAgent; }

    public DownloadedFile download(URI assetUri, Path operationDirectory, String filename, CancellationToken cancellation, DownloadProgress progress) throws IOException {
        validateInitialUri(assetUri);
        Path root = operationDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path destination = root.resolve(filename).normalize();
        if (!destination.getParent().equals(root) || destination.getFileName().toString().isBlank()) throw new IOException("Некорректное имя файла релиза");
        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(assetUri).GET().header("Accept", "application/octet-stream").header("User-Agent", userAgent).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw new IOException("Не удалось скачать файл: HTTP " + response.statusCode());
            validateFinalUri(response.uri());
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (total > 0 && Files.getFileStore(root).getUsableSpace() < total) throw new IOException("Недостаточно места для скачивания файла");
            long received = copy(response.body(), destination, total, cancellation, progress);
            return new DownloadedFile(destination, received, assetUri);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Скачивание прервано", interrupted);
        } catch (RuntimeException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        } catch (IOException failure) {
            Files.deleteIfExists(destination);
            throw failure;
        }
    }

    private static long copy(InputStream input, Path destination, long total, CancellationToken cancellation, DownloadProgress progress) throws IOException {
        long received = 0;
        try (input; var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) {
                cancellation.throwIfCancelled();
                output.write(buffer, 0, count);
                received += count;
                progress.update(received, total);
            }
        }
        cancellation.throwIfCancelled();
        return received;
    }
    private static void validateInitialUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !INITIAL_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw new IOException("Файл можно скачать только по HTTPS-адресу GitHub API");
    }
    private static void validateFinalUri(URI uri) throws IOException {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !(host.equals("github.com") || host.endsWith(".githubusercontent.com"))) throw new IOException("GitHub перенаправил скачивание на недоверенный адрес");
    }
}

