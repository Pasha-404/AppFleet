package ru.pashaapps.appfleet.ui;

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
    private static final int SHELL_ICON_SOURCE_SIZE = 64;
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
        try {
            Icon icon = FileSystemView.getFileSystemView().getSystemIcon(
                    executable.toFile(), SHELL_ICON_SOURCE_SIZE, SHELL_ICON_SOURCE_SIZE);
            if (icon == null || icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0) return Optional.empty();
            BufferedImage buffered = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = buffered.createGraphics();
            try {
                icon.paintIcon(null, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(buffered, "png", bytes)) return Optional.empty();
            return Optional.of(new Image(new ByteArrayInputStream(bytes.toByteArray())));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    static String monogram(String name) {
        if (name == null || name.isBlank()) return "A";
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) return words[0].substring(0, 1).toUpperCase();
        return (words[0].substring(0, 1) + words[1].substring(0, 1)).toUpperCase();
    }

    static int shellIconSourceSize() {
        return SHELL_ICON_SOURCE_SIZE;
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
