package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Streams only a GitHub-provided HTTPS asset into one bounded operation directory. */
public final class GithubAssetDownloader {
    private static final Set<String> INITIAL_HOSTS = Set.of("github.com", "api.github.com");
    private static final Duration HEADER_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration IDLE_BODY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CANCELLATION_POLL_INTERVAL = Duration.ofMillis(100);
    private static final ExecutorService BLOCKING_READS = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client;
    private final String userAgent;
    private final Duration headerTimeout;
    private final Duration idleBodyTimeout;
    private final Duration cancellationPollInterval;
    public GithubAssetDownloader(HttpClient client, String userAgent) { this(client, userAgent, HEADER_TIMEOUT, IDLE_BODY_TIMEOUT, CANCELLATION_POLL_INTERVAL); }
    GithubAssetDownloader(HttpClient client, String userAgent, Duration headerTimeout, Duration idleBodyTimeout, Duration cancellationPollInterval) {
        this.client = client;
        this.userAgent = userAgent;
        this.headerTimeout = headerTimeout;
        this.idleBodyTimeout = idleBodyTimeout;
        this.cancellationPollInterval = cancellationPollInterval;
    }

    public DownloadedFile download(URI assetUri, Path operationDirectory, String filename, CancellationToken cancellation, DownloadProgress progress) throws IOException {
        validateInitialUri(assetUri);
        Path root = operationDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path destination = root.resolve(filename).normalize();
        if (!destination.getParent().equals(root) || destination.getFileName().toString().isBlank()) throw new IOException("Некорректное имя файла релиза");
        try {
            HttpRequest request = HttpRequest.newBuilder(assetUri).GET().timeout(headerTimeout).header("Accept", "application/octet-stream").header("User-Agent", userAgent).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw new IOException("Не удалось скачать файл: HTTP " + response.statusCode());
                validateFinalUri(response.uri());
                long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (total > 0 && Files.getFileStore(root).getUsableSpace() < total) throw new IOException("Недостаточно места для скачивания файла");
                long received = copy(body, destination, total, cancellation, progress);
                return new DownloadedFile(destination, received, assetUri);
            }
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

    long copy(InputStream input, Path destination, long total, CancellationToken cancellation, DownloadProgress progress) throws IOException {
        long received = 0;
        try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                cancellation.throwIfCancelled();
                Future<Integer> pendingRead = BLOCKING_READS.submit(() -> input.read(buffer));
                int count;
                Instant deadline = Instant.now().plus(idleBodyTimeout);
                while (true) {
                    cancellation.throwIfCancelled();
                    try {
                        count = pendingRead.get(cancellationPollInterval.toMillis(), TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException waiting) {
                        if (!Instant.now().isBefore(deadline)) {
                            pendingRead.cancel(true);
                            input.close();
                            throw new IOException("Скачивание остановлено: GitHub не передаёт данные более " + idleBodyTimeout.toSeconds() + " с");
                        }
                    } catch (InterruptedException interrupted) {
                        pendingRead.cancel(true);
                        Thread.currentThread().interrupt();
                        throw new IOException("Скачивание прервано", interrupted);
                    } catch (ExecutionException readFailure) {
                        Throwable cause = readFailure.getCause();
                        if (cause instanceof IOException io) throw io;
                        throw new IOException("Не удалось прочитать скачиваемый файл", cause);
                    }
                }
                if (count < 0) break;
                output.write(buffer, 0, count);
                received += count;
                progress.update(received, total);
            }
        } catch (IOException | RuntimeException cancelled) {
            try { input.close(); } catch (IOException closeFailure) { cancelled.addSuppressed(closeFailure); }
            try { Files.deleteIfExists(destination); } catch (IOException cleanupFailure) { cancelled.addSuppressed(cleanupFailure); }
            throw cancelled;
        }
        cancellation.throwIfCancelled();
        progress.completed(received, total);
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
