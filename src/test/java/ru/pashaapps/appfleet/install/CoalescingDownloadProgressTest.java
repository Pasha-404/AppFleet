package ru.pashaapps.appfleet.install;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoalescingDownloadProgressTest {
    @Test void limitsFrequentUpdatesButAlwaysPublishesTheFinalByteCount() {
        AtomicLong clock = new AtomicLong();
        List<String> published = new ArrayList<>();
        CoalescingDownloadProgress progress = new CoalescingDownloadProgress(
                (received, total) -> published.add(received + "/" + total), Duration.ofMillis(100), clock::get);

        for (int received = 0; received < 1_000; received += 10) progress.update(received, 1_000);
        clock.addAndGet(Duration.ofMillis(100).toNanos());
        progress.update(1_000, 1_000);
        progress.completed(1_000, 1_000);

        assertEquals(List.of("0/1000", "1000/1000"), published);
    }

    @Test void forcesATerminalUpdateEvenBeforeTheNextDisplayInterval() {
        AtomicLong clock = new AtomicLong();
        List<String> published = new ArrayList<>();
        CoalescingDownloadProgress progress = new CoalescingDownloadProgress(
                (received, total) -> published.add(received + "/" + total), Duration.ofSeconds(1), clock::get);

        progress.update(10, -1);
        progress.update(20, -1);
        progress.completed(20, -1);

        assertEquals(List.of("10/-1", "20/-1"), published);
    }
}
