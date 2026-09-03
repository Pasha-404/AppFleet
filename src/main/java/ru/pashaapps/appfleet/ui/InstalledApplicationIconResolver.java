package ru.pashaapps.appfleet.ui;

import com.sun.jna.Memory;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.WindowUtils;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HDC;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFO;
import com.sun.jna.platform.win32.WinGDI.BITMAPINFOHEADER;
import com.sun.jna.platform.win32.WinGDI.ICONINFO;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Reads the Windows Shell icon of a detected executable and falls back to a stable monogram. */
final class InstalledApplicationIconResolver {
    private static final int ICON_SIZE = 48;
    private static final int MAX_NATIVE_ICON_SIZE = 256;
    private static final int COLOR_DEPTH = 24;
    private final Map<Path, Optional<Image>> cachedIcons = new HashMap<>();

    Node iconFor(ApplicationSnapshot snapshot) {
        Path executable = executable(snapshot);
        Optional<Image> image = executable == null ? Optional.empty() : cachedIcons.computeIfAbsent(executable, InstalledApplicationIconResolver::loadSystemIcon);
        if (image.isPresent()) {
            ImageView view = new ImageView(image.get());
            view.setFitWidth(ICON_SIZE);
            view.setFitHeight(ICON_SIZE);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.getStyleClass().add("application-icon");
            return view;
        }
        Label monogram = new Label(monogram(snapshot.displayName())); monogram.getStyleClass().add("application-icon-monogram");
        StackPane placeholder = new StackPane(monogram); placeholder.setAlignment(Pos.CENTER); placeholder.setMinSize(ICON_SIZE, ICON_SIZE); placeholder.setPrefSize(ICON_SIZE, ICON_SIZE); placeholder.setMaxSize(ICON_SIZE, ICON_SIZE); placeholder.getStyleClass().add("application-icon-placeholder");
        return placeholder;
    }

    static Optional<Image> loadSystemIcon(Path executable) {
        if (executable == null || !Files.isRegularFile(executable)) return Optional.empty();
        Optional<Image> nativeIcon = loadWindowsExecutableIcon(executable).flatMap(InstalledApplicationIconResolver::toJavaFxImage);
        return nativeIcon.isPresent() ? nativeIcon : loadShellFallback(executable);
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

    private static Optional<Image> loadShellFallback(Path executable) {
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
            return toJavaFxImage(buffered);
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
}
