package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.reports.CommercialReportType;
import br.com.espacotucuma.desktop.application.reports.QueryCommercialReportsUseCase;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteCommercialReportRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
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
class CommercialReportsPaneTest {
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new javafx.scene.layout.StackPane(), 1100, 700));
    stage.show();
  }

  @Test
  @Timeout(45)
  void readsLocalReportsAsynchronouslyWithoutNetworkAccess(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("reports-ui.sqlite"))) {
      var unit = new SqliteUnitOfWork(database.connection());
      var sessions = new SessionContext();
      sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
      var reports =
          new QueryCommercialReportsUseCase(
              new SqliteCommercialReportRepository(database.connection(), unit),
              new AuthorizationService(sessions));
      var pane = new CommercialReportsPane(reports, new AuthorizationService(sessions));
      robot.interact(() -> stage.setScene(new Scene(pane, 1100, 700)));
      robot.interact(() -> robot.lookup("#commercialReportQuery").queryButton().fire());

      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              robot.lookup("#commercialReportQuery").queryButton().isDisabled() == false
                  && robot.lookup("#commercialReportMessage").queryLabeled().getText().isBlank());
      assertTrue(robot.lookup("#commercialReportTable").tryQuery().isPresent());
      assertTrue(robot.lookup("#commercialReportType").tryQuery().isPresent());
      assertTrue(robot.lookup("#commercialReportPrint").tryQuery().isPresent());
      assertTrue(
          robot
              .lookup("#commercialReportIssuer")
              .queryLabeled()
              .getText()
              .contains("Emitido por Recepção"));
      robot.interact(pane::close);
    }
  }

  @Test
  void deniesReportsWithoutAnAuthorizedSession(FxRobot robot, @TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("reports-denied.sqlite"))) {
      var unit = new SqliteUnitOfWork(database.connection());
      var sessions = new SessionContext();
      var authorization = new AuthorizationService(sessions);
      var reports =
          new QueryCommercialReportsUseCase(
              new SqliteCommercialReportRepository(database.connection(), unit), authorization);
      var pane = new CommercialReportsPane(reports, authorization);
      robot.interact(() -> stage.setScene(new Scene(pane, 1100, 700)));
      robot.interact(pane::refresh);
      assertTrue(
          robot
              .lookup("#commercialReportMessage")
              .queryLabeled()
              .getText()
              .contains("não tem permissão"));
      robot.interact(pane::close);
    }
  }

  @Test
  @Timeout(45)
  void receptionistCannotReadInventoryCostOrCommissionReports(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("reports-sensitive.sqlite"))) {
      var unit = new SqliteUnitOfWork(database.connection());
      var sessions = new SessionContext();
      sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
      var authorization = new AuthorizationService(sessions);
      var reports =
          new QueryCommercialReportsUseCase(
              new SqliteCommercialReportRepository(database.connection(), unit), authorization);
      var pane = new CommercialReportsPane(reports, authorization);
      robot.interact(() -> stage.setScene(new Scene(pane, 1100, 700)));

      for (CommercialReportType type :
          new CommercialReportType[] {
            CommercialReportType.STOCK_POSITION, CommercialReportType.COMMISSION_STATEMENTS
          }) {
        robot.interact(
            () -> {
              pane.reportTypeSelector().setValue(type);
              pane.refresh();
            });
        WaitForAsyncUtils.waitFor(
            8,
            TimeUnit.SECONDS,
            () ->
                robot
                    .lookup("#commercialReportMessage")
                    .queryLabeled()
                    .getText()
                    .contains("não tem permissão"));
        assertTrue(pane.reportTable().getItems().isEmpty());
      }
      robot.interact(pane::close);
    }
  }
}
