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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Resolves a detected executable's embedded high-resolution icon without blocking the UI. */
final class InstalledApplicationIconResolver implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InstalledApplicationIconResolver.class);
    private static final int ICON_SIZE = 48;
    private static final int SHELL_ICON_REQUEST_SIZE = 96;
    private static final int EMBEDDED_ICON_REQUEST_SIZE = 256;
    private static final int MAX_NATIVE_ICON_SIZE = 256;
    private static final int COLOR_DEPTH = 24;
    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;
    private static final int GROUP_ICON_HEADER_BYTES = 6;
    private static final int GROUP_ICON_ENTRY_BYTES = 14;
    private static final int MAX_GROUP_ICON_ENTRIES = 128;
    private static final int MAX_ICON_RESOURCE_BYTES = 16 * 1024 * 1024;
    private static final int ICON_RESOURCE_VERSION = 0x00030000;
    private static final int LOAD_LIBRARY_AS_IMAGE_RESOURCE = 0x00000020;
    private static final int RESOURCE_ONLY_LOAD_FLAGS = Kernel32.LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE;
    private static final Guid.IID IID_ISHELL_ITEM = new Guid.IID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");
    private static final Guid.IID IID_ISHELL_ITEM_IMAGE_FACTORY = new Guid.IID("{BCC18B79-BA16-442F-80C4-8A59C30C463B}");
    private static final int SIIGBF_BIGGERSIZEOK = 0x00000001;
    private static final int SIIGBF_ICONONLY = 0x00000004;

    private final ExecutorService iconWorker = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("appfleet-icon-", 0).factory());
    private final Map<Path, CompletableFuture<Optional<BufferedImage>>> cachedIcons = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    Node iconFor(ApplicationSnapshot snapshot) {
        Path executable = executable(snapshot);
        StackPane holder = placeholder(snapshot.displayName());
        if (executable == null || closed.get()) return holder;

        cachedIcons.computeIfAbsent(executable, path -> CompletableFuture
                        .supplyAsync(() -> loadBestIcon(path), iconWorker)
                        .exceptionally(failure -> Optional.empty()))
                .thenAccept(icon -> showIconWhenReady(holder, icon));
        return holder;
    }

    static Optional<Image> loadSystemIcon(Path executable) {
        if (executable == null || !Files.isRegularFile(executable)) return Optional.empty();
        return loadBestIcon(executable).flatMap(InstalledApplicationIconResolver::toJavaFxImage);
    }

    private static Optional<BufferedImage> loadBestIcon(Path executable) {
        Optional<BufferedImage> embeddedIcon = loadWindowsEmbeddedExecutableIcon(executable);
        if (embeddedIcon.isPresent()) {
            log.info("Иконка {}: встроенный ресурс EXE {}×{}", executable.getFileName(), embeddedIcon.get().getWidth(), embeddedIcon.get().getHeight());
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

            int iconResourceId = User32IconResources.INSTANCE.LookupIconIdFromDirectoryEx(
                    groupData, true, EMBEDDED_ICON_REQUEST_SIZE, EMBEDDED_ICON_REQUEST_SIZE, WinUser.LR_DEFAULTCOLOR);
            if (iconResourceId == 0) return Optional.empty();

            HRSRC iconResource = Kernel32.INSTANCE.FindResource(module, resourceId(iconResourceId), resourceId(RT_ICON));
            if (isNull(iconResource)) return Optional.empty();
            HANDLE loadedIcon = Kernel32.INSTANCE.LoadResource(module, iconResource);
            int iconSize = Kernel32.INSTANCE.SizeofResource(module, iconResource);
            Pointer iconData = isNull(loadedIcon) ? null : Kernel32.INSTANCE.LockResource(loadedIcon);
            if (iconData == null || iconSize <= 0 || iconSize > MAX_ICON_RESOURCE_BYTES) return Optional.empty();

            icon = User32IconResources.INSTANCE.CreateIconFromResourceEx(
                    iconData, iconSize, true, ICON_RESOURCE_VERSION,
                    EMBEDDED_ICON_REQUEST_SIZE, EMBEDDED_ICON_REQUEST_SIZE, WinUser.LR_DEFAULTCOLOR);
            return isNull(icon) ? Optional.empty() : Optional.ofNullable(renderWindowsIcon(icon));
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

    private static Pointer resourceId(int id) {
        return Pointer.createConstant(Integer.toUnsignedLong(id));
    }

    private static boolean isNull(HANDLE handle) {
        return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == 0;
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
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(buffered, "png", bytes)) return Optional.empty();
            return Optional.of(new Image(new ByteArrayInputStream(bytes.toByteArray())));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void showIconWhenReady(StackPane holder, Optional<BufferedImage> bufferedIcon) {
        if (bufferedIcon.isEmpty() || closed.get()) return;
        try {
            javafx.application.Platform.runLater(() -> {
                if (closed.get()) return;
                toJavaFxImage(bufferedIcon.get()).ifPresent(image -> {
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
        int rowBytes = ((size.width * COLOR_DEPTH + 31) / 32) * 4;
        int bytesLength = Math.multiplyExact(rowBytes, size.height);
        ICONINFO iconInfo = new ICONINFO();
        HDC deviceContext = null;
        try {
            if (!User32.INSTANCE.GetIconInfo(icon, iconInfo)) return null;
            iconInfo.read();
            if (iconInfo.hbmColor == null || iconInfo.hbmMask == null) return null;

            BITMAPINFO bitmapInfo = new BITMAPINFO();
            BITMAPINFOHEADER header = new BITMAPINFOHEADER();
            bitmapInfo.bmiHeader = header;
            header.biWidth = size.width;
            header.biHeight = size.height;
            header.biPlanes = 1;
            header.biBitCount = COLOR_DEPTH;
            header.biCompression = WinGDI.BI_RGB;
            header.biSizeImage = bytesLength;
            header.write();
            bitmapInfo.write();

            deviceContext = User32.INSTANCE.GetDC(null);
            if (deviceContext == null) return null;
            Memory colorMemory = new Memory(bytesLength);
            Memory maskMemory = new Memory(bytesLength);
            if (GDI32.INSTANCE.GetDIBits(deviceContext, iconInfo.hbmColor, 0, size.height, colorMemory, bitmapInfo, WinGDI.DIB_RGB_COLORS) == 0
                    || GDI32.INSTANCE.GetDIBits(deviceContext, iconInfo.hbmMask, 0, size.height, maskMemory, bitmapInfo, WinGDI.DIB_RGB_COLORS) == 0) return null;

            byte[] color = colorMemory.getByteArray(0, bytesLength);
            byte[] mask = maskMemory.getByteArray(0, bytesLength);
            BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size.height; y++) {
                int sourceRow = (size.height - 1 - y) * rowBytes;
                for (int x = 0; x < size.width; x++) {
                    int offset = sourceRow + x * 3;
                    int blue = Byte.toUnsignedInt(color[offset]);
                    int green = Byte.toUnsignedInt(color[offset + 1]);
                    int red = Byte.toUnsignedInt(color[offset + 2]);
                    int alpha = 0xFF - Byte.toUnsignedInt(mask[offset]);
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
