package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.commission.CloseCommissionCompetencyUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteCommissionStatementRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
class CommissionStatementPaneTest {
  private static final UUID STATION = UUID.fromString("5a6d4a7c-d655-44aa-910c-b17db4859890");
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 980, 620));
    stage.show();
  }

  @Test
  @Timeout(45)
  void administratorCanCloseCompetencyFromTheStatementPane(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-pane.sqlite"))) {
      showPane(robot, fixture);
      robot.interact(
          () -> robot.lookup("#commissionPeriod").queryTextInputControl().setText("2026-11"));
      robot.interact(() -> robot.lookup("#commissionCompetencyClose").queryButton().fire());
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              robot.lookup("#commissionCompetencyClose").queryButton().isDisabled()
                  && robot
                      .lookup("#commissionStatementState")
                      .queryLabeled()
                      .getText()
                      .contains("fechada"));
      assertTrue(
          robot.lookup("#commissionStatementState").queryLabeled().getText().contains("fechada"));
      assertTrue(robot.lookup("#commissionStatementTable").tryQuery().isPresent());
      assertEquals(
          1,
          fixture.scalar("SELECT COUNT(*) FROM competencia_comissao WHERE competencia='2026-11'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='COMPETENCIA_COMISSAO_FECHADA'"));
    }
  }

  @Test
  @Timeout(45)
  void receptionistCannotCloseCommissionCompetency(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-pane-reception.sqlite"))) {
      fixture.sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
      showPane(robot, fixture);
      assertTrue(robot.lookup("#commissionCompetencyClose").queryButton().isDisabled());
      assertFalse(robot.lookup("#commissionStatementRefresh").queryButton().isDisabled());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM competencia_comissao"));
    }
  }

  private void showPane(FxRobot robot, Fixture fixture) {
    robot.interact(
        () ->
            stage.setScene(
                new Scene(
                    new CommissionStatementPane(fixture.closeCompetency, fixture.authorization),
                    980,
                    620)));
  }

  private static final class Fixture implements AutoCloseable {
    private final LocalDatabaseSession database;
    private final AuthorizationService authorization;
    private final SessionContext sessions;
    private final CloseCommissionCompetencyUseCase closeCompetency;

    private Fixture(Path path) throws Exception {
      database = LocalDatabaseSession.open(path);
      var unit = new SqliteUnitOfWork(database.connection());
      var audit = new SqliteAuditRepository(database.connection(), unit);
      new CreateFirstAdministratorUseCase(
              unit,
              new SqliteInitialSetupRepository(database.connection(), unit),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã", "Admin", "admin", "SenhaTesteOffline2026!".toCharArray()));
      sessions = new SessionContext();
      try (Statement statement = database.connection().createStatement();
          ResultSet row =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        row.next();
        sessions.establish(
            new AuthenticatedUser(
                UUID.fromString(row.getString("id")),
                row.getString("nome"),
                row.getString("login"),
                UserRole.ADMINISTRADOR));
      }
      authorization = new AuthorizationService(sessions);
      closeCompetency =
          new CloseCommissionCompetencyUseCase(
              unit,
              new SqliteCommissionStatementRepository(database.connection(), unit),
              new SqliteOutboxRepository(database.connection(), unit),
              authorization,
              audit,
              STATION,
              Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"), ZoneId.of("America/Manaus")));
    }

    private int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet rows = statement.executeQuery(sql)) {
        return rows.next() ? rows.getInt(1) : -1;
      }
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
