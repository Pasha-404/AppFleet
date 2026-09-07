package ru.pashaapps.appfleet.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstalledApplicationIconResolverTest {
    @Test void makesAStableFallbackMonogram() {
        assertEquals("MP", InstalledApplicationIconResolver.monogram("My Product"));
        assertEquals("S", InstalledApplicationIconResolver.monogram("SortIt"));
        assertEquals("A", InstalledApplicationIconResolver.monogram("  "));
    }

    @Test void doesNotAskTheWindowsShellForMissingExecutable() {
        assertTrue(InstalledApplicationIconResolver.loadSystemIcon(Path.of("missing-appfleet-test.exe")).isEmpty());
    }

    @Test void preservesPngResourcePixelsAndNativeDimensions() throws Exception {
        BufferedImage source = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x00010203);
        source.setRGB(1, 0, 0x80402010);
        source.setRGB(2, 0, 0xFF112233);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", bytes));

        BufferedImage decoded = InstalledApplicationIconResolver.decodePngIconResource(bytes.toByteArray()).orElseThrow();

        assertEquals(16, decoded.getWidth());
        assertEquals(16, decoded.getHeight());
        assertEquals(0x00010203, decoded.getRGB(0, 0));
        assertEquals(0x80402010, decoded.getRGB(1, 0));
        assertEquals(0xFF112233, decoded.getRGB(2, 0));
    }

    @Test void selectsNativeLayerWithoutInventingADisplaySizedResource() {
        byte[] smallOnly = groupIconDirectory(new int[][]{{16, 16, 32, 1}});
        var small = InstalledApplicationIconResolver.selectBestGroupIcon(smallOnly).orElseThrow();
        assertEquals(16, small.width());
        assertEquals(16, small.height());

        byte[] multiResolution = groupIconDirectory(new int[][]{{16, 16, 32, 1}, {256, 256, 32, 2}, {48, 48, 8, 3}});
        var selected = InstalledApplicationIconResolver.selectBestGroupIcon(multiResolution).orElseThrow();
        assertEquals(48, selected.width());
        assertEquals(48, selected.height());
        assertEquals(3, selected.resourceId());
    }

    @Test void refreshesTheCacheWhenAnExecutableIsReplacedOrExplicitlyInvalidated() throws Exception {
        Path executable = Files.createTempFile("appfleet-icon-cache-", ".exe");
        AtomicInteger calls = new AtomicInteger();
        try (InstalledApplicationIconResolver resolver = new InstalledApplicationIconResolver(path -> Optional.of(icon(calls.incrementAndGet())))) {
            assertEquals(1, resolver.resolveImage(executable).get(5, TimeUnit.SECONDS).orElseThrow().getRGB(0, 0));
            assertEquals(1, resolver.resolveImage(executable).get(5, TimeUnit.SECONDS).orElseThrow().getRGB(0, 0));
            assertEquals(1, calls.get());

            Files.writeString(executable, "replacement");
            Files.setLastModifiedTime(executable, FileTime.from(Instant.now().plusSeconds(2)));
            assertEquals(2, resolver.resolveImage(executable).get(5, TimeUnit.SECONDS).orElseThrow().getRGB(0, 0));
            assertEquals(2, calls.get());

            resolver.invalidate(executable);
            assertEquals(3, resolver.resolveImage(executable).get(5, TimeUnit.SECONDS).orElseThrow().getRGB(0, 0));
            assertEquals(3, calls.get());
        } finally {
            Files.deleteIfExists(executable);
        }
    }

    @Test void retriesAfterAMissingExecutableAppears() throws Exception {
        Path directory = Files.createTempDirectory("appfleet-icon-missing-");
        Path executable = directory.resolve("later.exe");
        AtomicInteger calls = new AtomicInteger();
        try (InstalledApplicationIconResolver resolver = new InstalledApplicationIconResolver(path -> {
            calls.incrementAndGet();
            return Files.isRegularFile(path) ? Optional.of(icon(42)) : Optional.empty();
        })) {
            assertTrue(resolver.resolveImage(executable).get(5, TimeUnit.SECONDS).isEmpty());
            Files.writeString(executable, "created");
            assertEquals(42, resolver.resolveImage(executable).get(5, TimeUnit.SECONDS).orElseThrow().getRGB(0, 0));
            assertEquals(2, calls.get());
        } finally {
            Files.deleteIfExists(executable);
            Files.deleteIfExists(directory);
        }
    }

    @Test void ignoresAnOlderAsynchronousResultAfterInvalidation() throws Exception {
        Path executable = Files.createTempFile("appfleet-icon-race-", ".exe");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (InstalledApplicationIconResolver resolver = new InstalledApplicationIconResolver(path -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                try {
                    assertTrue(finishFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Optional.of(icon(1));
            }
            return Optional.of(icon(2));
        })) {
            var oldResult = resolver.resolveImage(executable);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            resolver.invalidate(executable);
            assertEquals(2, resolver.resolveImage(executable).get(5, TimeUnit.SECONDS).orElseThrow().getRGB(0, 0));
            finishFirst.countDown();
            assertEquals(1, oldResult.get(5, TimeUnit.SECONDS).orElseThrow().getRGB(0, 0));
            assertEquals(2, resolver.resolveImage(executable).get(5, TimeUnit.SECONDS).orElseThrow().getRGB(0, 0));
        } finally {
            Files.deleteIfExists(executable);
        }
    }

    @Test void readsEmbeddedIconAtItsNativeResourceSizeForAWindowsExecutable() {
        Path notepad = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "System32", "notepad.exe");
        if (!Files.isRegularFile(notepad)) return;
        assertTrue(InstalledApplicationIconResolver.loadWindowsEmbeddedExecutableIcon(notepad)
                .map(image -> image.getWidth() > 0 && image.getHeight() > 0 && image.getWidth() <= 1024 && image.getHeight() <= 1024)
                .orElse(false));
    }

    @Test void readsHighResolutionShellFallbackIconForAWindowsExecutable() {
        Path notepad = Path.of(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "System32", "notepad.exe");
        if (!Files.isRegularFile(notepad)) return;
        assertTrue(InstalledApplicationIconResolver.loadWindowsShellItemIcon(notepad)
                .map(image -> image.getWidth() >= 48 && image.getHeight() >= 48)
                .orElse(false));
    }

    private static BufferedImage icon(int marker) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, marker);
        return image;
    }

    private static byte[] groupIconDirectory(int[][] entries) {
        byte[] result = new byte[6 + entries.length * 14];
        result[2] = 1;
        result[4] = (byte) entries.length;
        for (int index = 0; index < entries.length; index++) {
            int[] entry = entries[index];
            int offset = 6 + index * 14;
            result[offset] = (byte) (entry[0] == 256 ? 0 : entry[0]);
            result[offset + 1] = (byte) (entry[1] == 256 ? 0 : entry[1]);
            result[offset + 4] = 1;
            result[offset + 6] = (byte) entry[2];
            result[offset + 8] = 64;
            result[offset + 12] = (byte) entry[3];
        }
        return result;
    }
}
