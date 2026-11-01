package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

@ExtendWith(ApplicationExtension.class)
class InitialAdministratorDialogTest {

  private Stage owner;

  @Start
  void start(Stage stage) {
    owner = stage;
    stage.setTitle("Tucumã test owner");
    stage.setScene(new Scene(new StackPane(), 320, 200));
    stage.show();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void requiresMatchingPasswordsBeforeCommittingFirstSetup(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database = LocalDatabaseSession.open(temp.resolve("ui.sqlite"))) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      CreateFirstAdministratorUseCase useCase =
          new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              new br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository(
                  database.connection(), unitOfWork),
              java.util.UUID.randomUUID());
      CompletableFuture<Boolean> dialogResult = new CompletableFuture<>();
      Platform.runLater(
          () -> dialogResult.complete(InitialAdministratorDialog.showAndCreate(owner, useCase)));
      WaitForAsyncUtils.waitFor(
          5,
          TimeUnit.SECONDS,
          () ->
              robot
                  .lookup("#establishmentName")
                  .tryQuery()
                  .map(node -> node.getScene() != null && node.getScene().getWindow().isShowing())
                  .orElse(false));

      robot.interact(
          () -> {
            robot
                .lookup("#establishmentName")
                .queryTextInputControl()
                .setText("Espaço Tucumã Teste");
            robot
                .lookup("#administratorName")
                .queryTextInputControl()
                .setText("Administradora Sintética");
            robot.lookup("#administratorLogin").queryTextInputControl().setText("admin");
            robot
                .lookup("#administratorPassword")
                .queryTextInputControl()
                .setText("SenhaForteOffline2026!");
            robot
                .lookup("#passwordConfirmation")
                .queryTextInputControl()
                .setText("senha diferente");
            robot.lookup("Criar administrador").queryButton().fire();
          });
      WaitForAsyncUtils.waitForFxEvents();

      assertEquals(
          "As senhas não correspondem.", robot.lookup("#setupMessage").queryLabeled().getText());
      assertTrue(useCase.isSetupRequired());

      robot.interact(
          () -> {
            robot
                .lookup("#administratorPassword")
                .queryTextInputControl()
                .setText("SenhaForteOffline2026!");
            robot
                .lookup("#passwordConfirmation")
                .queryTextInputControl()
                .setText("SenhaForteOffline2026!");
            robot.lookup("Criar administrador").queryButton().fire();
          });

      assertTrue(dialogResult.get(5, TimeUnit.SECONDS));
      assertFalse(useCase.isSetupRequired());
    }
  }
}
