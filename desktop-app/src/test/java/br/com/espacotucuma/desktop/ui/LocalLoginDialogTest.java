package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.LoginUseCase;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteLocalCredentialRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteLoginAttemptRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
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
class LocalLoginDialogTest {
  private Stage owner;

  @Start
  void start(Stage stage) {
    owner = stage;
    stage.setScene(new Scene(new StackPane(), 320, 200));
    stage.show();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsInvalidCredentialsAndAcceptsOfflineLogin(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("login-ui.sqlite"))) {
      var unitOfWork = new SqliteUnitOfWork(database.connection());
      var hasher = new Pbkdf2PasswordHasher();
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              hasher,
              new SqliteAuditRepository(database.connection(), unitOfWork),
              java.util.UUID.randomUUID())
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã", "Admin Teste", "admin", "SenhaOfflineForte2026!".toCharArray()));
      LoginUseCase login =
          new LoginUseCase(
              unitOfWork,
              new SqliteLocalCredentialRepository(database.connection(), unitOfWork),
              new SqliteLoginAttemptRepository(database.connection(), unitOfWork),
              hasher,
              new SessionContext());
      CompletableFuture<
              Optional<br.com.espacotucuma.desktop.application.identity.AuthenticatedSession>>
          result = new CompletableFuture<>();
      Platform.runLater(() -> result.complete(LocalLoginDialog.show(new Stage(), login)));
      WaitForAsyncUtils.waitFor(
          5,
          TimeUnit.SECONDS,
          () ->
              robot
                  .lookup("#login")
                  .tryQuery()
                  .map(node -> node.getScene() != null && node.getScene().getWindow().isShowing())
                  .orElse(false));
      WaitForAsyncUtils.waitForFxEvents();
      var screenshot = Path.of("target", "visual-regression", "login-dialog.png");
      Files.createDirectories(screenshot.getParent());
      Node dialogPane = robot.lookup(".dialog-pane").query();
      robot
          .robotContext()
          .getCaptureSupport()
          .saveImage(robot.capture((Node) dialogPane).getImage(), screenshot);
      assertTrue(Files.size(screenshot) > 0);
      assertTrue(
          ((Parent) dialogPane)
              .getStylesheets()
              .contains(TucumaTheme.class.getResource("tucuma-classic.css").toExternalForm()));

      robot.interact(
          () -> {
            robot.lookup("#login").queryTextInputControl().setText("admin");
            robot.lookup("#password").queryTextInputControl().setText("senha errada");
            robot.lookup("Entrar").queryButton().fire();
          });
      WaitForAsyncUtils.waitForFxEvents();
      assertEquals(
          "Login ou senha inválidos.", robot.lookup("#loginMessage").queryLabeled().getText());

      robot.interact(
          () -> {
            robot.lookup("#password").queryTextInputControl().setText("SenhaOfflineForte2026!");
            robot.lookup("Entrar").queryButton().fire();
          });
      assertTrue(result.get(5, TimeUnit.SECONDS).isPresent());
    }
  }
}
