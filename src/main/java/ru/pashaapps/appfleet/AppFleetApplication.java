package ru.pashaapps.appfleet;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import ru.pashaapps.appfleet.lifecycle.ApplicationInstanceLock;
import ru.pashaapps.appfleet.persistence.AppPaths;
import ru.pashaapps.appfleet.service.AppFleetService;
import ru.pashaapps.appfleet.service.OperationCoordinator;
import ru.pashaapps.appfleet.service.SelfUpdateService;
import ru.pashaapps.appfleet.ui.MainWindow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public final class AppFleetApplication extends Application {
    private MainWindow window;
    private ApplicationInstanceLock instanceLock;

    @Override
    public void start(Stage stage) {
        BuildInfo build = BuildInfo.load();
        AppPaths paths = AppPaths.forCurrentUser(System.getenv(), Path.of(System.getProperty("user.home")), Path.of(System.getProperty("java.io.tmpdir")));
        try {
            instanceLock = ApplicationInstanceLock.tryAcquire(paths.cacheDirectory().resolve("appfleet.instance.lock")).orElse(null);
        } catch (IOException failure) {
            showStartupError("Не удалось проверить уже запущенный экземпляр AppFleet", failure.getMessage());
            Platform.exit();
            return;
        }
        if (instanceLock == null) {
            showStartupError("AppFleet уже запущен", "Закройте уже запущенное окно AppFleet или дождитесь завершения текущей операции.");
            Platform.exit();
            return;
        }
        stage.getIcons().add(loadWindowIcon());
        System.setProperty("LOG_DIR", paths.logDirectory().toString());
        OperationCoordinator operations = new OperationCoordinator();
        AppFleetService service = new AppFleetService(paths, AppFleetObjectMapper.create(), build.version(), operations);
        window = new MainWindow(stage, service, new SelfUpdateService(build, paths, AppFleetObjectMapper.create(), operations), build,
                Boolean.getBoolean("appfleet.developmentRun"));
        stage.setOnCloseRequest(event -> window.close());
        window.show();
    }
    @Override public void stop() {
        if (window != null) window.close();
        if (instanceLock != null) {
            try {
                instanceLock.close();
            } catch (IOException ignored) {
                // The process is stopping; Windows releases any remaining OS lock handle.
            }
        }
    }

    private static void showStartupError(String header, String details) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, details);
        alert.setTitle("AppFleet");
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    private static Image loadWindowIcon() {
        try (InputStream stream = AppFleetApplication.class.getResourceAsStream("/appfleet-window-icon.png")) {
            if (stream == null) {
                throw new IllegalStateException("AppFleet window icon resource is missing");
            }
            Image icon = new Image(stream);
            if (icon.isError()) {
                throw new IllegalStateException("AppFleet window icon resource is invalid", icon.getException());
            }
            return icon;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load AppFleet window icon", exception);
        }
    }
}
