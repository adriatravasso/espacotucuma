package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.DesktopRuntime;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public final class TucumaDesktopApplication extends Application {

  private DesktopRuntime runtime;

  @Override
  public void start(Stage stage) {
    try {
      runtime = DesktopRuntime.open();
    } catch (Exception startupFailure) {
      Alert alert = new Alert(Alert.AlertType.ERROR);
      alert.setTitle("Tucumã — Gestão");
      alert.setHeaderText("Não foi possível abrir os dados locais.");
      alert.setContentText(startupErrorMessage(startupFailure));
      alert.showAndWait();
      Platform.exit();
      return;
    }

    BorderPane root = new BorderPane(new Label("Sistema de Gestão do Espaço Tucumã"));
    stage.setTitle("Tucumã — Gestão");
    stage.setScene(new Scene(root, 900, 600));
    stage.show();

    if (getParameters().getRaw().contains("--smoke-test")) {
      Platform.runLater(stage::close);
    }
  }

  public static String startupErrorMessage(Exception startupFailure) {
    String detail = startupFailure.getMessage();
    if (detail == null || detail.isBlank()) {
      detail = startupFailure.getClass().getSimpleName();
    }
    return "O banco existente não foi substituído nem apagado. Verifique se outra instância está "
        + "usando o arquivo e se há permissão para acessar o disco. Preserve uma cópia do banco "
        + "antes de qualquer tentativa de recuperação.\n\nDetalhes: "
        + detail;
  }

  @Override
  public void stop() throws Exception {
    if (runtime != null) {
      runtime.close();
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
