package ru.pashaapps.appfleet;

import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import ru.pashaapps.appfleet.persistence.AppPaths;
import ru.pashaapps.appfleet.service.AppFleetService;
import ru.pashaapps.appfleet.service.SelfUpdateService;
import ru.pashaapps.appfleet.ui.MainWindow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public final class AppFleetApplication extends Application {
    private MainWindow window;
    @Override
    public void start(Stage stage) {
        stage.getIcons().add(loadWindowIcon());
        BuildInfo build = BuildInfo.load();
        AppPaths paths = AppPaths.forCurrentUser(System.getenv(), Path.of(System.getProperty("user.home")), Path.of(System.getProperty("java.io.tmpdir")));
        System.setProperty("LOG_DIR", paths.logDirectory().toString());
        AppFleetService service = new AppFleetService(paths, AppFleetObjectMapper.create(), build.version());
        window = new MainWindow(stage, service, new SelfUpdateService(build, paths, AppFleetObjectMapper.create()), build);
        stage.setOnCloseRequest(event -> window.close());
        window.show();
    }
    @Override public void stop() { if (window != null) window.close(); }

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
