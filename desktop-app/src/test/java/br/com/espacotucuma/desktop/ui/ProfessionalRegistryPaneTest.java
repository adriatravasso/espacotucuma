package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.professional.MaintainProfessionalUseCase;
import br.com.espacotucuma.desktop.application.professional.Professional;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProfessionalRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
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
class ProfessionalRegistryPaneTest {
  private static final UUID STATION = UUID.fromString("1310e747-c097-4204-9e6a-bbe76f377df0");
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 1000, 700));
    stage.show();
  }

  @Test
  @Timeout(45)
  void registersScheduleReportsConflictsAndKeepsInactivatedProfessionalVisible(
      FxRobot robot, @TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("professional-ui.sqlite"))) {
      ProfessionalRegistryPane pane =
          new ProfessionalRegistryPane(fixture.repository, fixture.maintain, fixture.authorization);
      robot.interact(() -> stage.setScene(new Scene(pane, 1000, 700)));
      waitForPage(robot, "0 registros");

      Platform.runLater(() -> robot.lookup("#tableNew").queryButton().fire());
      waitForNode(robot, "#professionalName");
      setField(robot, "#professionalName", "Ana Profissional");
      setField(robot, "#professionalPhone", "(92) 99999-1122");
      setField(robot, "#professionalDay-monday", "09:00-12:00,13:00-18:00");
      setField(robot, "#professionalAbsences", "2026-11-02 10:00 | 2026-11-02 11:00 | Consulta");
      robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
      waitForPage(robot, "1 / 1");
      Professional saved =
          fixture
              .repository
              .searchPage(
                  new br.com.espacotucuma.desktop.application.professional.ProfessionalPageQuery(
                      "", false, 0, 25))
              .items()
              .getFirst();
      assertEquals("92999991122", saved.phone());
      assertEquals(2, saved.schedule().weeklyIntervals().size());
      assertEquals(1, saved.schedule().unavailabilities().size());
      assertFalse(
          fixture.repository.canReceive(
              saved.id(),
              LocalDateTime.of(2026, 11, 2, 10, 0),
              LocalDateTime.of(2026, 11, 2, 10, 30)));

      TableView<Professional> table = robot.lookup("#dataTable").queryTableView();
      robot.interact(() -> table.getSelectionModel().selectFirst());
      Platform.runLater(() -> robot.lookup("#tableEdit").queryButton().fire());
      waitForNode(robot, "#professionalDay-monday");
      setField(robot, "#professionalDay-monday", "09:00-12:00,11:30-13:00");
      robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
      assertTrue(
          robot
              .lookup("#professionalFormMessage")
              .queryLabeled()
              .getText()
              .contains("sobrepostos"));
      setField(robot, "#professionalDay-monday", "09:00-12:00,14:00-18:00");
      robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
      waitForPage(robot, "1 / 1");
      assertEquals(
          LocalTime.of(14, 0),
          fixture
              .repository
              .findById(saved.id())
              .orElseThrow()
              .schedule()
              .weeklyIntervals()
              .get(1)
              .startsAt());
      robot.interact(() -> table.getSelectionModel().selectFirst());
      Platform.runLater(() -> robot.lookup("#professionalDeactivate").queryButton().fire());
      waitForNode(robot, "#professionalDeactivateConfirm");
      robot.interact(() -> robot.lookup("#professionalDeactivateConfirm").queryButton().fire());
      waitForPage(robot, "0 registros");
      assertFalse(
          fixture
              .repository
              .searchPage(
                  new br.com.espacotucuma.desktop.application.professional.ProfessionalPageQuery(
                      "Ana", true, 0, 25))
              .items()
              .getFirst()
              .active());
      robot.interact(
          () -> robot.lookup("#professionalShowInactive").queryAs(CheckBox.class).fire());
      waitForPage(robot, "1 / 1");
      TableView<Professional> retainedTable = robot.lookup("#dataTable").queryTableView();
      assertEquals("Ana Profissional", retainedTable.getItems().getFirst().name());
      fixture.sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
      robot.interact(pane::refreshAccess);
      assertTrue(robot.lookup("#tableNew").queryButton().isDisabled());
      assertTrue(robot.lookup("#tableEdit").queryButton().isDisabled());
      assertTrue(robot.lookup("#professionalDeactivate").queryButton().isDisabled());
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
    private final ProfessionalRepository repository;
    private final AuthorizationService authorization;
    private final MaintainProfessionalUseCase maintain;
    private final SessionContext sessions;

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
                  "SELECT id, nome, login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        result.next();
        sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                UUID.fromString(result.getString("id")),
                result.getString("nome"),
                result.getString("login"),
                UserRole.ADMINISTRADOR));
        authorization = new AuthorizationService(sessions);
      }
      repository = new SqliteProfessionalRepository(database.connection(), unitOfWork);
      maintain =
          new MaintainProfessionalUseCase(
              unitOfWork,
              repository,
              new SqliteOutboxRepository(database.connection(), unitOfWork),
              authorization,
              audit,
              STATION);
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
