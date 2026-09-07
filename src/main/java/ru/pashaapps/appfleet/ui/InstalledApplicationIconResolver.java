package ru.pashaapps.appfleet.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.WindowUtils;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HBITMAP;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HMODULE;
import com.sun.jna.platform.win32.WinDef.HRSRC;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFOHEADER;
import com.sun.jna.platform.win32.WinGDI.BITMAP;
import com.sun.jna.platform.win32.WinGDI.ICONINFO;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.WinUser.SIZE;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.pashaapps.appfleet.service.ApplicationSnapshot;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;
import java.awt.Graphics2D;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Resolves a detected executable's embedded high-resolution icon without blocking the UI. */
final class InstalledApplicationIconResolver implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InstalledApplicationIconResolver.class);
    private static final int ICON_SIZE = 48;
    private static final int SHELL_ICON_REQUEST_SIZE = 96;
    private static final int MAX_NATIVE_ICON_SIZE = 1024;
    private static final int COLOR_DEPTH = 32;
    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;
    private static final int GROUP_ICON_HEADER_BYTES = 6;
    private static final int GROUP_ICON_ENTRY_BYTES = 14;
    private static final int MAX_GROUP_ICON_ENTRIES = 128;
    private static final int MAX_ICON_RESOURCE_BYTES = 16 * 1024 * 1024;
    private static final int ICON_RESOURCE_VERSION = 0x00030000;
    private static final Duration EMPTY_ICON_RETRY_DELAY = Duration.ofSeconds(2);
    private static final int LOAD_LIBRARY_AS_IMAGE_RESOURCE = 0x00000020;
    private static final int RESOURCE_ONLY_LOAD_FLAGS = Kernel32.LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE;
    private static final Guid.IID IID_ISHELL_ITEM = new Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");
    private static final Guid.IID IID_ISHELL_ITEM_IMAGE_FACTORY = new Guid.IID("{BCC18B79-BA16-442F-80C4-8A59C30C463B}");
    private static final int SIIGBF_BIGGERSIZEOK = 0x00000001;
    private static final int SIIGBF_ICONONLY = 0x00000004;

    private final ExecutorService iconWorker;
    private final IconLoader iconLoader;
    private final Map<Path, CacheEntry> cachedIcons = new ConcurrentHashMap<>();
    private final AtomicLong cacheGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    InstalledApplicationIconResolver() {
        this(InstalledApplicationIconResolver::loadBestIcon);
    }

    /** Visible for tests: native extraction remains behind the default loader. */
    InstalledApplicationIconResolver(IconLoader iconLoader) {
        this.iconLoader = iconLoader;
        this.iconWorker = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("appfleet-icon-", 0).factory());
    }

    Node iconFor(ApplicationSnapshot snapshot) {
        Path executable = executable(snapshot);
        StackPane holder = placeholder(snapshot.displayName());
        if (executable == null || closed.get()) return holder;

        CacheEntry entry = resolve(executable);
        entry.icon().thenAccept(icon -> showIconWhenReady(holder, executable, entry, icon));
        return holder;
    }

    /**
     * Explicitly drops an icon after AppFleet itself has changed install metadata.  A future
     * asynchronous result carries its generation and cannot replace the new entry.
     */
    void invalidate(ApplicationSnapshot snapshot) {
        Path executable = executable(snapshot);
        if (executable != null) invalidate(executable);
    }

    void invalidate(Path executable) {
        if (executable == null) return;
        cachedIcons.remove(executable.toAbsolutePath().normalize());
    }

    CacheEntry resolve(Path rawExecutable) {
        Path executable = rawExecutable.toAbsolutePath().normalize();
        FileIdentity identity = FileIdentity.read(executable);
        Instant now = Instant.now();
        return cachedIcons.compute(executable, (path, current) -> {
            if (current == null || !current.identity().equals(identity) || current.retryDue(now)) {
                return startEntry(path, identity, now);
            }
            return current;
        });
    }

    CompletableFuture<Optional<BufferedImage>> resolveImage(Path executable) {
        return resolve(executable).icon().thenApply(icon -> icon.map(IconPayload::source));
    }

    private CacheEntry startEntry(Path executable, FileIdentity identity, Instant now) {
        long generation = cacheGeneration.incrementAndGet();
        CompletableFuture<Optional<IconPayload>> future = CompletableFuture
                .supplyAsync(() -> iconLoader.load(executable).flatMap(InstalledApplicationIconResolver::toPayload), iconWorker)
                .exceptionally(failure -> {
                    log.debug("Не удалось загрузить иконку {}", executable, failure);
                    return Optional.empty();
                });
        CacheEntry entry = new CacheEntry(identity, generation, future, now);
        future.thenAccept(entry::complete);
        return entry;
    }

    static Optional<Image> loadSystemIcon(Path executable) {
        if (executable == null || !Files.isRegularFile(executable)) return Optional.empty();
        return loadBestIcon(executable).flatMap(InstalledApplicationIconResolver::toJavaFxImage);
    }

    private static Optional<BufferedImage> loadBestIcon(Path executable) {
        Optional<BufferedImage> embeddedIcon = loadWindowsEmbeddedExecutableIcon(executable);
        if (embeddedIcon.isPresent()) {
            log.info("Иконка {}: встроенный ресурс EXE декодирован {}×{}; карточка отображает не больше {} px",
                    executable.getFileName(), embeddedIcon.get().getWidth(), embeddedIcon.get().getHeight(), ICON_SIZE);
            return embeddedIcon;
        }

        Optional<BufferedImage> shellImage = loadWindowsShellItemIcon(executable);
        if (shellImage.isPresent()) {
            log.warn("Иконка {}: Shell fallback {}×{} (встроенный ресурс EXE недоступен)", executable.getFileName(), shellImage.get().getWidth(), shellImage.get().getHeight());
            return shellImage;
        }

        Optional<BufferedImage> extractedIcon = loadWindowsExecutableIcon(executable);
        if (extractedIcon.isPresent()) {
            log.warn("Иконка {}: ExtractIconEx fallback {}×{} (Shell и встроенный ресурс EXE недоступны)", executable.getFileName(), extractedIcon.get().getWidth(), extractedIcon.get().getHeight());
            return extractedIcon;
        }

        Optional<BufferedImage> fileSystemIcon = loadShellFallback(executable);
        if (fileSystemIcon.isPresent()) log.warn("Иконка {}: системный fallback {}×{} (встроенный ресурс EXE и Shell недоступны)", executable.getFileName(), fileSystemIcon.get().getWidth(), fileSystemIcon.get().getHeight());
        else log.warn("Иконка {}: нативная иконка недоступна; будет показана буквенная заглушка", executable.getFileName());
        return fileSystemIcon;
    }

    /**
     * Reads the icon resource directly from a PE module opened as data, so no code from the
     * target executable or DLL is loaded or run. This avoids the Windows Shell icon cache.
     */
    static Optional<BufferedImage> loadWindowsEmbeddedExecutableIcon(Path executable) {
        if (!Platform.isWindows() || executable == null || !Files.isRegularFile(executable)) return Optional.empty();

        HMODULE module = null;
        HICON icon = null;
        try {
            module = Kernel32.INSTANCE.LoadLibraryEx(executable.toString(), null, RESOURCE_ONLY_LOAD_FLAGS);
            if (isNull(module)) return Optional.empty();

            ResourceName groupName = firstGroupIconName(module);
            if (groupName == null) return Optional.empty();
            ResourcePointer groupNamePointer = groupName.asPointer();
            HRSRC groupResource = Kernel32.INSTANCE.FindResource(module, groupNamePointer.pointer(), resourceId(RT_GROUP_ICON));
            if (isNull(groupResource)) return Optional.empty();

            HANDLE loadedGroup = Kernel32.INSTANCE.LoadResource(module, groupResource);
            int groupSize = Kernel32.INSTANCE.SizeofResource(module, groupResource);
            Pointer groupData = isNull(loadedGroup) ? null : Kernel32.INSTANCE.LockResource(loadedGroup);
            if (!isValidGroupIconDirectory(groupData, groupSize)) return Optional.empty();

            byte[] groupBytes = groupData.getByteArray(0, groupSize);
            Optional<GroupIconEntry> selected = selectBestGroupIcon(groupBytes);
            if (selected.isEmpty()) return Optional.empty();
            GroupIconEntry layer = selected.get();

            HRSRC iconResource = Kernel32.INSTANCE.FindResource(module, resourceId(layer.resourceId()), resourceId(RT_ICON));
            if (isNull(iconResource)) return Optional.empty();
            HANDLE loadedIcon = Kernel32.INSTANCE.LoadResource(module, iconResource);
            int iconSize = Kernel32.INSTANCE.SizeofResource(module, iconResource);
            Pointer iconData = isNull(loadedIcon) ? null : Kernel32.INSTANCE.LockResource(loadedIcon);
            if (iconData == null || iconSize <= 0 || iconSize > MAX_ICON_RESOURCE_BYTES) return Optional.empty();

            byte[] iconBytes = iconData.getByteArray(0, iconSize);
            Optional<BufferedImage> png = decodePngIconResource(iconBytes);
            if (png.isPresent()) {
                BufferedImage image = png.get();
                log.debug("Иконка {}: выбран PNG-слой ресурса {}×{}, декодирован {}×{}, отображение не больше {} px",
                        executable.getFileName(), layer.width(), layer.height(), image.getWidth(), image.getHeight(), ICON_SIZE);
                return png;
            }

            icon = User32IconResources.INSTANCE.CreateIconFromResourceEx(
                    iconData, iconSize, true, ICON_RESOURCE_VERSION,
                    0, 0, WinUser.LR_DEFAULTCOLOR);
            if (isNull(icon)) return Optional.empty();
            BufferedImage rendered = renderWindowsIcon(icon);
            if (rendered != null) {
                log.debug("Иконка {}: выбран bitmap-слой ресурса {}×{}, декодирован {}×{}, отображение не больше {} px",
                        executable.getFileName(), layer.width(), layer.height(), rendered.getWidth(), rendered.getHeight(), ICON_SIZE);
            }
            return Optional.ofNullable(rendered);
        } catch (LinkageError | RuntimeException failure) {
            log.debug("Не удалось извлечь встроенную иконку из {}", executable, failure);
            return Optional.empty();
        } finally {
            if (!isNull(icon)) User32.INSTANCE.DestroyIcon(icon);
            if (!isNull(module)) Kernel32.INSTANCE.FreeLibrary(module);
        }
    }

    private static ResourceName firstGroupIconName(HMODULE module) {
        AtomicReference<ResourceName> name = new AtomicReference<>();
        WinBase.EnumResNameProc selectFirst = (loadedModule, type, resourceName, context) -> {
            name.compareAndSet(null, ResourceName.copyOf(resourceName));
            return false;
        };
        try {
            Kernel32.INSTANCE.EnumResourceNames(module, resourceId(RT_GROUP_ICON), selectFirst, null);
            return name.get();
        } catch (LinkageError | RuntimeException failure) {
            log.debug("Не удалось перечислить RT_GROUP_ICON", failure);
            return null;
        }
    }

    private static boolean isValidGroupIconDirectory(Pointer directory, int size) {
        if (directory == null || size < GROUP_ICON_HEADER_BYTES) return false;
        int reserved = Short.toUnsignedInt(directory.getShort(0));
        int type = Short.toUnsignedInt(directory.getShort(2));
        int count = Short.toUnsignedInt(directory.getShort(4));
        long requiredBytes = GROUP_ICON_HEADER_BYTES + (long) count * GROUP_ICON_ENTRY_BYTES;
        return reserved == 0 && type == 1 && count > 0 && count <= MAX_GROUP_ICON_ENTRIES && requiredBytes <= size;
    }

    /**
     * Chooses the smallest native layer that is at least as large as the card icon; if all
     * layers are smaller, it keeps the largest one.  The selected bytes are never upscaled at
     * the Win32 boundary, so the image dimensions remain evidence of the actual resource.
     */
    static Optional<GroupIconEntry> selectBestGroupIcon(byte[] directory) {
        if (!isValidGroupIconDirectory(directory)) return Optional.empty();
        int count = Short.toUnsignedInt(readShort(directory, 4));
        GroupIconEntry bestAbove = null;
        GroupIconEntry bestBelow = null;
        for (int index = 0; index < count; index++) {
            int offset = GROUP_ICON_HEADER_BYTES + index * GROUP_ICON_ENTRY_BYTES;
            int width = Byte.toUnsignedInt(directory[offset]);
            int height = Byte.toUnsignedInt(directory[offset + 1]);
            width = width == 0 ? 256 : width;
            height = height == 0 ? 256 : height;
            int bitCount = Short.toUnsignedInt(readShort(directory, offset + 6));
            long bytes = Integer.toUnsignedLong(readInt(directory, offset + 8));
            int resourceId = Short.toUnsignedInt(readShort(directory, offset + 12));
            if (width <= 0 || height <= 0 || width > MAX_NATIVE_ICON_SIZE || height > MAX_NATIVE_ICON_SIZE
                    || bytes <= 0 || bytes > MAX_ICON_RESOURCE_BYTES || resourceId == 0) continue;
            GroupIconEntry entry = new GroupIconEntry(width, height, bitCount, resourceId);
            int extent = Math.max(width, height);
            if (extent >= ICON_SIZE) {
                if (bestAbove == null || compareNativeLayers(entry, bestAbove) < 0) bestAbove = entry;
            } else if (bestBelow == null || compareNativeLayers(entry, bestBelow) > 0) {
                bestBelow = entry;
            }
        }
        return Optional.ofNullable(bestAbove != null ? bestAbove : bestBelow);
    }

    private static boolean isValidGroupIconDirectory(byte[] directory) {
        if (directory == null || directory.length < GROUP_ICON_HEADER_BYTES) return false;
        int reserved = Short.toUnsignedInt(readShort(directory, 0));
        int type = Short.toUnsignedInt(readShort(directory, 2));
        int count = Short.toUnsignedInt(readShort(directory, 4));
        long requiredBytes = GROUP_ICON_HEADER_BYTES + (long) count * GROUP_ICON_ENTRY_BYTES;
        return reserved == 0 && type == 1 && count > 0 && count <= MAX_GROUP_ICON_ENTRIES && requiredBytes <= directory.length;
    }

    private static int compareNativeLayers(GroupIconEntry left, GroupIconEntry right) {
        int byExtent = Integer.compare(Math.max(left.width(), left.height()), Math.max(right.width(), right.height()));
        return byExtent != 0 ? byExtent : Integer.compare(left.bitCount(), right.bitCount());
    }

    private static short readShort(byte[] source, int offset) {
        return (short) (Byte.toUnsignedInt(source[offset]) | Byte.toUnsignedInt(source[offset + 1]) << 8);
    }

    private static int readInt(byte[] source, int offset) {
        return Byte.toUnsignedInt(source[offset])
                | Byte.toUnsignedInt(source[offset + 1]) << 8
                | Byte.toUnsignedInt(source[offset + 2]) << 16
                | Byte.toUnsignedInt(source[offset + 3]) << 24;
    }

    static Optional<BufferedImage> decodePngIconResource(byte[] iconBytes) {
        if (!isPng(iconBytes)) return Optional.empty();
        try (ByteArrayInputStream input = new ByteArrayInputStream(iconBytes)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0
                    || image.getWidth() > MAX_NATIVE_ICON_SIZE || image.getHeight() > MAX_NATIVE_ICON_SIZE) return Optional.empty();
            return Optional.of(image);
        } catch (IOException | RuntimeException invalidImage) {
            return Optional.empty();
        }
    }

    private static boolean isPng(byte[] value) {
        return value != null && value.length >= 8
                && value[0] == (byte) 0x89 && value[1] == 0x50 && value[2] == 0x4E && value[3] == 0x47
                && value[4] == 0x0D && value[5] == 0x0A && value[6] == 0x1A && value[7] == 0x0A;
    }

    private static Pointer resourceId(int id) {
        return Pointer.createConstant(Integer.toUnsignedLong(id));
    }

    private static boolean isNull(HANDLE handle) {
        return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == 0;
    }

    private static final class CacheEntry {
        private final FileIdentity identity;
        private final long generation;
        private final CompletableFuture<Optional<IconPayload>> icon;
        private final Instant createdAt;
        private volatile Optional<IconPayload> completed;
        /** Accessed only from the JavaFX callback. */
        private Optional<Image> presentation;

        private CacheEntry(FileIdentity identity, long generation, CompletableFuture<Optional<IconPayload>> icon, Instant createdAt) {
            this.identity = identity;
            this.generation = generation;
            this.icon = icon;
            this.createdAt = createdAt;
        }

        private FileIdentity identity() { return identity; }
        private CompletableFuture<Optional<IconPayload>> icon() { return icon; }
        private void complete(Optional<IconPayload> result) { completed = result; }

        private boolean retryDue(Instant now) {
            return completed != null && completed.isEmpty() && !now.isBefore(createdAt.plus(EMPTY_ICON_RETRY_DELAY));
        }

        private Optional<Image> presentation(Optional<IconPayload> payload) {
            if (presentation == null) {
                presentation = payload.flatMap(InstalledApplicationIconResolver::toJavaFxImage);
            }
            return presentation;
        }
    }

    private record IconPayload(BufferedImage source, byte[] png) {}

    @FunctionalInterface
    interface IconLoader {
        Optional<BufferedImage> load(Path executable);
    }

    private enum FileState { REGULAR_FILE, MISSING, UNAVAILABLE }

    private record FileIdentity(FileState state, long size, java.nio.file.attribute.FileTime lastModified, String fileKey) {
        private static FileIdentity read(Path executable) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(executable, BasicFileAttributes.class);
                if (!attributes.isRegularFile()) return new FileIdentity(FileState.MISSING, 0, null, null);
                Object key = attributes.fileKey();
                return new FileIdentity(FileState.REGULAR_FILE, attributes.size(), attributes.lastModifiedTime(), key == null ? null : key.toString());
            } catch (java.nio.file.NoSuchFileException missing) {
                return new FileIdentity(FileState.MISSING, 0, null, null);
            } catch (IOException | SecurityException unavailable) {
                return new FileIdentity(FileState.UNAVAILABLE, 0, null, null);
            }
        }

        private boolean regularFile() { return state == FileState.REGULAR_FILE; }
    }

    /**
     * Uses the documented IShellItemImageFactory API, which accepts the requested pixel size.
     * Unlike ExtractIconEx, it is not limited to the user's current system large-icon metric.
     */
    static Optional<BufferedImage> loadWindowsShellItemIcon(Path executable) {
        if (!Platform.isWindows() || executable == null || !Files.isRegularFile(executable)) return Optional.empty();

        boolean comInitialized = false;
        Unknown shellItem = null;
        ShellItemImageFactory imageFactory = null;
        HBITMAP bitmap = null;
        try {
            HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
            if (!COMUtils.SUCCEEDED(initialized)) return Optional.empty();
            comInitialized = true;

            PointerByReference factoryPointer = new PointerByReference();
            HRESULT created = Shell32Support.INSTANCE.SHCreateItemFromParsingName(
                    new WString(executable.toString()), null, new Guid.REFIID(IID_ISHELL_ITEM), factoryPointer);
            if (!COMUtils.SUCCEEDED(created) || factoryPointer.getValue() == null) return Optional.empty();

            shellItem = new Unknown(factoryPointer.getValue());
            PointerByReference imageFactoryPointer = new PointerByReference();
            HRESULT queried = shellItem.QueryInterface(new Guid.REFIID(IID_ISHELL_ITEM_IMAGE_FACTORY), imageFactoryPointer);
            if (!COMUtils.SUCCEEDED(queried) || imageFactoryPointer.getValue() == null) return Optional.empty();

            imageFactory = new ShellItemImageFactory(imageFactoryPointer.getValue());
            PointerByReference bitmapPointer = new PointerByReference();
            ShellSize requestedSize = new ShellSize(SHELL_ICON_REQUEST_SIZE, SHELL_ICON_REQUEST_SIZE);
            requestedSize.write();
            HRESULT loaded = imageFactory.getImage(requestedSize, SIIGBF_ICONONLY | SIIGBF_BIGGERSIZEOK, bitmapPointer);
            if (!COMUtils.SUCCEEDED(loaded) || bitmapPointer.getValue() == null) return Optional.empty();

            bitmap = new HBITMAP(bitmapPointer.getValue());
            return Optional.ofNullable(renderShellBitmap(bitmap));
        } catch (LinkageError | RuntimeException ignored) {
            return Optional.empty();
        } finally {
            if (bitmap != null) GDI32.INSTANCE.DeleteObject(bitmap);
            if (imageFactory != null) imageFactory.Release();
            if (shellItem != null) shellItem.Release();
            if (comInitialized) Ole32.INSTANCE.CoUninitialize();
        }
    }

    static Optional<BufferedImage> loadWindowsExecutableIcon(Path executable) {
        if (!Platform.isWindows() || executable == null || !Files.isRegularFile(executable)) return Optional.empty();
        HICON[] icons = new HICON[1];
        try {
            int extracted = Shell32.INSTANCE.ExtractIconEx(executable.toString(), 0, icons, null, 1);
            if (extracted != 1 || icons[0] == null) return Optional.empty();
            return Optional.ofNullable(renderWindowsIcon(icons[0]));
        } catch (LinkageError | RuntimeException ignored) {
            return Optional.empty();
        } finally {
            if (icons[0] != null) {
                try {
                    User32.INSTANCE.DestroyIcon(icons[0]);
                } catch (LinkageError | RuntimeException ignored) {
                    // A failure to release a native icon must not prevent the safe fallback below.
                }
            }
        }
    }

    private static Optional<BufferedImage> loadShellFallback(Path executable) {
        try {
            Icon icon = FileSystemView.getFileSystemView().getSystemIcon(executable.toFile());
            if (icon == null || icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0) return Optional.empty();
            BufferedImage buffered = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = buffered.createGraphics();
            try {
                icon.paintIcon(null, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            return Optional.of(buffered);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    static String monogram(String name) {
        if (name == null || name.isBlank()) return "A";
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) return words[0].substring(0, 1).toUpperCase();
        return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase();
    }

    private static Optional<Image> toJavaFxImage(BufferedImage buffered) {
        return toPayload(buffered).flatMap(InstalledApplicationIconResolver::toJavaFxImage);
    }

    private static Optional<IconPayload> toPayload(BufferedImage buffered) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(buffered, "png", bytes)) return Optional.empty();
            return Optional.of(new IconPayload(buffered, bytes.toByteArray()));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Image> toJavaFxImage(IconPayload payload) {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(payload.png())) {
            return Optional.of(new Image(bytes));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void showIconWhenReady(StackPane holder, Path executable, CacheEntry expected, Optional<IconPayload> payload) {
        if (payload.isEmpty() || closed.get()) return;
        try {
            javafx.application.Platform.runLater(() -> {
                if (closed.get() || cachedIcons.get(executable) != expected) return;
                expected.presentation(payload).ifPresent(image -> {
                    holder.getChildren().setAll(iconView(image));
                    holder.getStyleClass().remove("application-icon-placeholder");
                });
            });
        } catch (IllegalStateException ignored) {
            // JavaFX has already shut down; the holder will be discarded with the scene.
        }
    }

    private static StackPane placeholder(String displayName) {
        Label monogram = new Label(monogram(displayName));
        monogram.getStyleClass().add("application-icon-monogram");
        StackPane holder = new StackPane(monogram);
        holder.setAlignment(Pos.CENTER);
        holder.setMinSize(ICON_SIZE, ICON_SIZE);
        holder.setPrefSize(ICON_SIZE, ICON_SIZE);
        holder.setMaxSize(ICON_SIZE, ICON_SIZE);
        holder.getStyleClass().add("application-icon-placeholder");
        return holder;
    }

    private static ImageView iconView(Image image) {
        ImageView view = new ImageView(image);
        double displaySize = Math.min(ICON_SIZE, Math.min(image.getWidth(), image.getHeight()));
        view.setFitWidth(displaySize);
        view.setFitHeight(displaySize);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.getStyleClass().add("application-icon");
        return view;
    }

    private static BufferedImage renderShellBitmap(HBITMAP bitmap) {
        BITMAP bitmapDetails = new BITMAP();
        HDC deviceContext = null;
        try {
            if (GDI32.INSTANCE.GetObject(bitmap, bitmapDetails.size(), bitmapDetails.getPointer()) == 0) return null;
            bitmapDetails.read();
            int width = bitmapDetails.bmWidth.intValue();
            int height = Math.abs(bitmapDetails.bmHeight.intValue());
            if (width <= 0 || height <= 0 || width > MAX_NATIVE_ICON_SIZE || height > MAX_NATIVE_ICON_SIZE) return null;

            int rowBytes = Math.multiplyExact(width, 4);
            int bytesLength = Math.multiplyExact(rowBytes, height);
            BITMAPINFO bitmapInfo = new BITMAPINFO();
            BITMAPINFOHEADER header = new BITMAPINFOHEADER();
            bitmapInfo.bmiHeader = header;
            header.biWidth = width;
            header.biHeight = height;
            header.biPlanes = 1;
            header.biBitCount = 32;
            header.biCompression = WinGDI.BI_RGB;
            header.biSizeImage = bytesLength;
            header.write();
            bitmapInfo.write();

            deviceContext = User32.INSTANCE.GetDC(null);
            if (deviceContext == null) return null;
            Memory pixels = new Memory(bytesLength);
            if (GDI32.INSTANCE.GetDIBits(deviceContext, bitmap, 0, height, pixels, bitmapInfo, WinGDI.DIB_RGB_COLORS) == 0) return null;

            byte[] source = pixels.getByteArray(0, bytesLength);
            boolean containsAlpha = false;
            for (int offset = 3; offset < source.length; offset += 4) {
                if (source[offset] != 0) {
                    containsAlpha = true;
                    break;
                }
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                int sourceRow = (height - 1 - y) * rowBytes;
                for (int x = 0; x < width; x++) {
                    int offset = sourceRow + x * 4;
                    int blue = Byte.toUnsignedInt(source[offset]);
                    int green = Byte.toUnsignedInt(source[offset + 1]);
                    int red = Byte.toUnsignedInt(source[offset + 2]);
                    int alpha = containsAlpha ? Byte.toUnsignedInt(source[offset + 3]) : 0xFF;
                    image.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
                }
            }
            return image;
        } catch (LinkageError | RuntimeException ignored) {
            return null;
        } finally {
            if (deviceContext != null) User32.INSTANCE.ReleaseDC(null, deviceContext);
        }
    }

    private static BufferedImage renderWindowsIcon(HICON icon) {
        Dimension size = WindowUtils.getIconSize(icon);
        if (size.width <= 0 || size.height <= 0 || size.width > MAX_NATIVE_ICON_SIZE || size.height > MAX_NATIVE_ICON_SIZE) return null;
        ICONINFO iconInfo = new ICONINFO();
        HDC deviceContext = null;
        try {
            if (!User32.INSTANCE.GetIconInfo(icon, iconInfo)) return null;
            iconInfo.read();
            if (iconInfo.hbmColor == null) return null;

            deviceContext = User32.INSTANCE.GetDC(null);
            if (deviceContext == null) return null;
            byte[] color = readBgra32(deviceContext, iconInfo.hbmColor, size.width, size.height);
            if (color == null) return null;
            byte[] mask = iconInfo.hbmMask == null ? null : readBgra32(deviceContext, iconInfo.hbmMask, size.width, size.height);
            boolean containsAlpha = hasNonZeroAlpha(color);
            BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            int rowBytes = Math.multiplyExact(size.width, 4);
            for (int y = 0; y < size.height; y++) {
                int sourceRow = (size.height - 1 - y) * rowBytes;
                for (int x = 0; x < size.width; x++) {
                    int offset = sourceRow + x * 4;
                    int blue = Byte.toUnsignedInt(color[offset]);
                    int green = Byte.toUnsignedInt(color[offset + 1]);
                    int red = Byte.toUnsignedInt(color[offset + 2]);
                    int alpha = containsAlpha ? Byte.toUnsignedInt(color[offset + 3]) : legacyMaskAlpha(mask, offset);
                    if (containsAlpha && alpha > 0 && alpha < 0xFF) {
                        red = unpremultiply(red, alpha);
                        green = unpremultiply(green, alpha);
                        blue = unpremultiply(blue, alpha);
                    }
                    image.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
                }
            }
            return image;
        } catch (LinkageError | RuntimeException ignored) {
            return null;
        } finally {
            if (deviceContext != null) User32.INSTANCE.ReleaseDC(null, deviceContext);
            if (iconInfo.hbmColor != null) GDI32.INSTANCE.DeleteObject(iconInfo.hbmColor);
            if (iconInfo.hbmMask != null) GDI32.INSTANCE.DeleteObject(iconInfo.hbmMask);
        }
    }

    private static byte[] readBgra32(HDC deviceContext, HBITMAP bitmap, int width, int height) {
        try {
            int rowBytes = Math.multiplyExact(width, 4);
            int bytesLength = Math.multiplyExact(rowBytes, height);
            BITMAPINFO bitmapInfo = new BITMAPINFO();
            BITMAPINFOHEADER header = new BITMAPINFOHEADER();
            bitmapInfo.bmiHeader = header;
            header.biWidth = width;
            header.biHeight = height;
            header.biPlanes = 1;
            header.biBitCount = COLOR_DEPTH;
            header.biCompression = WinGDI.BI_RGB;
            header.biSizeImage = bytesLength;
            header.write();
            bitmapInfo.write();
            Memory pixels = new Memory(bytesLength);
            if (GDI32.INSTANCE.GetDIBits(deviceContext, bitmap, 0, height, pixels, bitmapInfo, WinGDI.DIB_RGB_COLORS) == 0) return null;
            return pixels.getByteArray(0, bytesLength);
        } catch (LinkageError | RuntimeException invalidBitmap) {
            return null;
        }
    }

    private static boolean hasNonZeroAlpha(byte[] pixels) {
        for (int offset = 3; offset < pixels.length; offset += 4) {
            if (pixels[offset] != 0) return true;
        }
        return false;
    }

    private static int legacyMaskAlpha(byte[] mask, int offset) {
        if (mask == null || offset + 2 >= mask.length) return 0xFF;
        return Math.max(Byte.toUnsignedInt(mask[offset]), Math.max(Byte.toUnsignedInt(mask[offset + 1]), Byte.toUnsignedInt(mask[offset + 2]))) >= 0x80 ? 0 : 0xFF;
    }

    private static int unpremultiply(int color, int alpha) {
        return Math.min(0xFF, (color * 0xFF + alpha / 2) / alpha);
    }

    private static Path executable(ApplicationSnapshot snapshot) {
        String raw = snapshot.persisted().executable();
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            iconWorker.shutdownNow();
            cachedIcons.clear();
        }
    }

    private interface Shell32Support extends StdCallLibrary {
        Shell32Support INSTANCE = Native.load("shell32", Shell32Support.class);

        HRESULT SHCreateItemFromParsingName(WString path, Pointer bindContext, Guid.REFIID requestedInterface, PointerByReference shellItem);
    }

    /** Win32 icon-resource APIs that are not exposed by the JNA platform mappings. */
    private interface User32IconResources extends StdCallLibrary {
        User32IconResources INSTANCE = Native.load("user32", User32IconResources.class);

        int LookupIconIdFromDirectoryEx(Pointer directory, boolean icon, int width, int height, int flags);

        HICON CreateIconFromResourceEx(Pointer bits, int bytes, boolean icon, int version, int width, int height, int flags);
    }

    private record ResourceName(Integer id, String text) {
        private static ResourceName copyOf(Pointer resourceName) {
            long rawValue = Pointer.nativeValue(resourceName);
            if ((rawValue & ~0xFFFFL) == 0) return new ResourceName((int) rawValue, null);
            return new ResourceName(null, resourceName.getWideString(0));
        }

        private ResourcePointer asPointer() {
            if (id != null) return new ResourcePointer(resourceId(id), null);
            Memory textMemory = new Memory((long) (text.length() + 1) * Native.WCHAR_SIZE);
            textMemory.setWideString(0, text);
            return new ResourcePointer(textMemory, textMemory);
        }
    }

    record GroupIconEntry(int width, int height, int bitCount, int resourceId) {}

    /** Retains the native buffer for a string resource name until FindResource returns. */
    private record ResourcePointer(Pointer pointer, Memory retainedMemory) {}

    private static final class ShellItemImageFactory extends Unknown {
        private ShellItemImageFactory(Pointer pointer) { super(pointer); }

        private HRESULT getImage(ShellSize size, int flags, PointerByReference bitmap) {
            return (HRESULT) _invokeNativeObject(3, new Object[]{getPointer(), size, flags, bitmap}, HRESULT.class);
        }
    }

    private static final class ShellSize extends SIZE implements Structure.ByValue {
        private ShellSize(int width, int height) { super(width, height); }
    }
}
