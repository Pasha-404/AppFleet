package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GithubAssetDownloaderTest {
    @TempDir Path temporaryDirectory;

    @Test void cancellationClosesABlockedBodyReadAndRemovesThePartialFile() throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        AtomicBoolean cancelled = new AtomicBoolean();
        GithubAssetDownloader downloader = new GithubAssetDownloader(HttpClient.newHttpClient(), "test", Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(20));
        Thread canceller = Thread.ofVirtual().start(() -> {
            try {
                input.readStarted.await();
                cancelled.set(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        assertThrows(OperationCancelledException.class, () -> downloader.copy(input, temporaryDirectory.resolve("partial.exe"), -1, cancelled::get, DownloadProgress.NONE));
        canceller.join();

        assertTrue(input.closed.get());
        assertFalse(Files.exists(temporaryDirectory.resolve("partial.exe")));
    }

    @Test void stalledBodyHasABoundedIdleDeadline() {
        BlockingInputStream input = new BlockingInputStream();
        GithubAssetDownloader downloader = new GithubAssetDownloader(HttpClient.newHttpClient(), "test", Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(10));

        IOException failure = assertThrows(IOException.class, () -> downloader.copy(input, temporaryDirectory.resolve("partial.exe"), -1, CancellationToken.NEVER_CANCELLED, DownloadProgress.NONE));

        assertTrue(failure.getMessage().contains("не передаёт данные"));
        assertTrue(input.closed.get());
        assertFalse(Files.exists(temporaryDirectory.resolve("partial.exe")));
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        @Override public int read() throws IOException { return read(new byte[1]); }
        @Override public int read(byte[] buffer) throws IOException {
            readStarted.countDown();
            while (!closed.get()) {
                try { Thread.sleep(10); }
                catch (InterruptedException ignored) { /* Closing the stream is the cancellation signal. */ }
            }
            throw new IOException("stream closed");
        }
        @Override public void close() { closed.set(true); }
    }
}
