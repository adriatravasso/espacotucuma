package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.application.service.MaintainServiceUseCase;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteServiceRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
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
class ServiceCatalogPaneTest {
  private static final UUID STATION = UUID.fromString("260125ac-8294-4d0c-8693-e5fc7d6e4311");
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 1100, 720));
    stage.show();
  }

  @Test
  @Timeout(45)
  void createsServiceChangesPriceAndShowsImmutablePriceHistory(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("service-catalog-ui.sqlite"))) {
      ServiceCatalogPane pane =
          new ServiceCatalogPane(fixture.services, fixture.maintain, fixture.authorization);
      robot.interact(() -> stage.setScene(new Scene(pane, 1100, 720)));
      waitForPage(robot, "0 registros");

      Platform.runLater(() -> robot.lookup("#tableNew").queryButton().fire());
      waitForNode(robot, "#form-serviceName");
      setField(robot, "#form-serviceName", "Manicure clássica");
      setField(robot, "#form-serviceCategory", "Unhas");
      setField(robot, "#form-durationMinutes", "45");
      setField(robot, "#form-price", "50,00");
      setField(robot, "#form-commissionPercent", "20,00");
      robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
      waitForPage(robot, "1 / 1");
      BeautyService saved = fixture.services.searchPage(fixture.query()).items().getFirst();
      assertEquals(new Money(new java.math.BigDecimal("50.00")), saved.price());
      assertEquals(2000, saved.commissionBasisPoints());

      TableView<BeautyService> table = robot.lookup("#dataTable").queryTableView();
      robot.interact(() -> table.getSelectionModel().selectFirst());
      Platform.runLater(() -> robot.lookup("#tableEdit").queryButton().fire());
      waitForNode(robot, "#form-price");
      setField(robot, "#form-price", "65,00");
      setField(robot, "#form-commissionPercent", "25,00");
      robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
      waitForPage(robot, "1 / 1");
      assertEquals(
          new Money(new java.math.BigDecimal("65.00")),
          fixture.services.findById(saved.id()).orElseThrow().price());

      robot.interact(() -> table.getSelectionModel().selectFirst());
      Platform.runLater(() -> robot.lookup("#serviceHistory").queryButton().fire());
      waitForNode(robot, "#serviceChangeHistory");
      TableView<?> changes = robot.lookup("#serviceChangeHistory").queryTableView();
      assertEquals(2, changes.getItems().size());
      assertTrue(changes.getItems().getFirst().toString().contains("65.00"));
      assertTrue(changes.getItems().getFirst().toString().contains("50.00"));
      robot.interact(() -> robot.lookup("#serviceHistoryClose").queryButton().fire());
    }
  }

  private void setField(FxRobot robot, String selector, String value) {
    robot.interact(() -> robot.lookup(selector).queryTextInputControl().setText(value));
  }

  private void waitForPage(FxRobot robot, String value) throws Exception {
    WaitForAsyncUtils.waitFor(
        8,
        TimeUnit.SECONDS,
        () -> robot.lookup("#pageInfo").queryLabeled().getText().equals(value));
  }

  private void waitForNode(FxRobot robot, String selector) throws Exception {
    WaitForAsyncUtils.waitFor(
        8, TimeUnit.SECONDS, () -> robot.lookup(selector).tryQuery().isPresent());
  }

  private static final class Fixture implements AutoCloseable {
    private final LocalDatabaseSession database;
    private final ServiceRepository services;
    private final AuthorizationService authorization;
    private final MaintainServiceUseCase maintain;

    private Fixture(Path path) throws Exception {
      database = LocalDatabaseSession.open(path);
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteAuditRepository audit = new SqliteAuditRepository(database.connection(), unitOfWork);
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã",
                  "Admin de teste",
                  "admin",
                  "SenhaTesteOffline2026!".toCharArray()));
      try (Statement statement = database.connection().createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        result.next();
        SessionContext sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                UUID.fromString(result.getString("id")),
                result.getString("nome"),
                result.getString("login"),
                UserRole.ADMINISTRADOR));
        authorization = new AuthorizationService(sessions);
      }
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      services = new SqliteServiceRepository(database.connection(), unitOfWork);
      maintain =
          new MaintainServiceUseCase(unitOfWork, services, outbox, authorization, audit, STATION);
    }

    private br.com.espacotucuma.desktop.application.service.ServicePageQuery query() {
      return new br.com.espacotucuma.desktop.application.service.ServicePageQuery(
          "",
          "ALL",
          false,
          br.com.espacotucuma.desktop.application.service.ServiceSortField.NAME,
          true,
          0,
          25);
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
