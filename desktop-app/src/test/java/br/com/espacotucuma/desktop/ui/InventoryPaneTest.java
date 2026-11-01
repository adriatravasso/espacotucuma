package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.espacotucuma.desktop.application.identity.*;
import br.com.espacotucuma.desktop.application.inventory.MaintainInventoryUseCase;
import br.com.espacotucuma.desktop.application.port.inventory.InventoryRepository;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.*;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
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
class InventoryPaneTest {
  private static final UUID STATION = UUID.fromString("fa408eee-f687-48bd-b84f-93b06f25fa8c");
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.show();
  }

  @Test
  @Timeout(50)
  void registersProductThenStockEntryAndShowsLowStock(@TempDir Path temp, FxRobot robot)
      throws Exception {
    try (Fixture f = new Fixture(temp.resolve("inventory-ui.sqlite"))) {
      InventoryPane pane = new InventoryPane(f.repository, f.useCase, f.authorization);
      robot.interact(() -> stage.setScene(new Scene(pane, 1150, 700)));
      robot.interact(pane::refreshAccess);
      var product =
          f.useCase.registerProduct(
              null,
              "UI-01",
              "Creme de massagem",
              "Insumos",
              br.com.espacotucuma.desktop.application.inventory.UnitOfMeasure.UNIDADE,
              br.com.espacotucuma.desktop.domain.money.Money.of("15.50"),
              null,
              10_000);
      robot.interact(pane::refresh);
      waitForCount(robot, 1);
      TableView<?> table = robot.lookup("#inventory-products").queryTableView();
      robot.interact(() -> table.getSelectionModel().selectFirst());
      assertNotNull(table.getSelectionModel().getSelectedItem());
      f.useCase.registerEntry(product.id(), 4_000, "Compra de teste", UUID.randomUUID().toString());
      robot.interact(pane::refresh);
      robot.clickOn("#inventory-low-only");
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              table.getItems().size() == 1
                  && ((br.com.espacotucuma.desktop.application.inventory.StockProduct)
                              table.getItems().getFirst())
                          .onHandMilliUnits()
                      == 4_000);
      var stock =
          (br.com.espacotucuma.desktop.application.inventory.StockProduct)
              table.getItems().getFirst();
      assertTrue(stock.belowMinimum());
      assertTrue(robot.lookup("REPOR").tryQuery().isPresent());
    }
  }

  private void waitForCount(FxRobot robot, int count) throws Exception {
    WaitForAsyncUtils.waitFor(
        8,
        TimeUnit.SECONDS,
        () -> robot.lookup("#inventory-products").queryTableView().getItems().size() == count);
  }

  private static final class Fixture implements AutoCloseable {
    final LocalDatabaseSession database;
    final InventoryRepository repository;
    final MaintainInventoryUseCase useCase;
    final AuthorizationService authorization;

    Fixture(Path path) throws Exception {
      database = LocalDatabaseSession.open(path);
      var uow = new SqliteUnitOfWork(database.connection());
      var audit = new SqliteAuditRepository(database.connection(), uow);
      new CreateFirstAdministratorUseCase(
              uow,
              new SqliteInitialSetupRepository(database.connection(), uow),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã", "Admin Teste", "admin", "SenhaTesteOffline2026!".toCharArray()));
      try (Statement s = database.connection().createStatement();
          ResultSet r =
              s.executeQuery("SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        r.next();
        SessionContext sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                UUID.fromString(r.getString("id")),
                r.getString("nome"),
                r.getString("login"),
                UserRole.ADMINISTRADOR));
        authorization = new AuthorizationService(sessions);
      }
      repository = new SqliteInventoryRepository(database.connection(), uow);
      useCase =
          new MaintainInventoryUseCase(
              uow,
              repository,
              new SqliteOutboxRepository(database.connection(), uow),
              authorization,
              audit,
              STATION);
    }

    public void close() throws Exception {
      database.close();
    }
  }
}
