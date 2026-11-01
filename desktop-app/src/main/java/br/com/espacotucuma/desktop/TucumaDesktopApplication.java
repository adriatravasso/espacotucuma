package br.com.espacotucuma.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public final class TucumaDesktopApplication extends Application {

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane(new Label("Sistema de Gestão do Espaço Tucumã"));
        stage.setTitle("Tucumã — Gestão");
        stage.setScene(new Scene(root, 900, 600));
        stage.show();

        if (getParameters().getRaw().contains("--smoke-test")) {
            Platform.runLater(stage::close);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
