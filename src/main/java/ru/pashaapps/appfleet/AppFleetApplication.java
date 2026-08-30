package ru.pashaapps.appfleet;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Entry point. The full UI is introduced after the domain and persistence layers. */
public final class AppFleetApplication extends Application {
    @Override
    public void start(Stage stage) {
        stage.setTitle("AppFleet");
        stage.setScene(new Scene(new StackPane(new Label("AppFleet запускается…")), 1100, 720));
        stage.show();
    }
}

