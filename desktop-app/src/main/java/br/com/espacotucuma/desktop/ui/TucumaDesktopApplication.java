package br.com.espacotucuma.desktop.ui;

import br.com.espacotucuma.desktop.DesktopRuntime;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public final class TucumaDesktopApplication extends Application {

  private DesktopRuntime runtime;
  private TucumaMainShell shell;

  @Override
  public void start(Stage stage) {
    try {
      runtime = DesktopRuntime.open();
    } catch (Exception startupFailure) {
      Alert alert = new Alert(Alert.AlertType.ERROR);
      alert.setTitle("Tucumã — Gestão");
      alert.setHeaderText("Não foi possível abrir os dados locais.");
      alert.setContentText(startupErrorMessage(startupFailure));
      TucumaTheme.apply(alert.getDialogPane());
      alert.showAndWait();
      Platform.exit();
      return;
    }

    if (runtime.createFirstAdministrator().isSetupRequired()
        && !InitialAdministratorDialog.showAndCreate(stage, runtime.createFirstAdministrator())) {
      Platform.exit();
      return;
    }

    boolean smokeTest =
        getParameters().getRaw().contains("--smoke-test") && runtime.isTestProfile();
    if (smokeTest) {
      if (runtime.login().login("admin", "SenhaOfflineForte2026!".toCharArray()).isEmpty()) {
        throw new IllegalStateException("A credencial sintética do smoke test não foi aceita.");
      }
    } else if (LocalLoginDialog.show(stage, runtime.login()).isEmpty()) {
      Platform.exit();
      return;
    }

    shell =
        new TucumaMainShell(
            runtime::protectionStatus,
            new ClientRegistryPane(
                runtime.clients(),
                runtime.registerClient(),
                runtime.maintainClient(),
                runtime.authorization()),
            new ProfessionalRegistryPane(
                runtime.professionals(), runtime.maintainProfessional(), runtime.authorization()),
            new ServiceCatalogPane(
                runtime.services(), runtime.maintainService(), runtime.authorization()));
    shell.setLogoutAction(
        () -> {
          runtime.logout().logout();
          if (LocalLoginDialog.show(stage, runtime.login()).isEmpty()) {
            stage.close();
          } else {
            shell.refreshStatus();
            shell.refreshActiveModuleAccess();
          }
        });
    stage.setTitle("Tucumã — Gestão");
    stage.setScene(new Scene(shell.root(), 1180, 720));
    stage.setMinWidth(1024);
    stage.setMinHeight(680);
    stage.show();

    if (smokeTest) {
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
    if (shell != null) {
      shell.stopStatusRefresh();
    }
    if (runtime != null) {
      runtime.close();
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
