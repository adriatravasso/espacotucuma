package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.client.Client;
import br.com.espacotucuma.desktop.application.client.ClientContactFilter;
import br.com.espacotucuma.desktop.application.client.ClientPageQuery;
import br.com.espacotucuma.desktop.application.client.ClientSortField;
import br.com.espacotucuma.desktop.application.client.MaintainClientUseCase;
import br.com.espacotucuma.desktop.application.client.RegisterClientUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteClientRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.sql.PreparedStatement;
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
class ClientRegistryPaneTest {

  private static final UUID STATION = UUID.fromString("168e98fc-ae43-48d8-8f57-38f7e7b31348");
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 1000, 700));
    stage.show();
  }

  @Test
  @Timeout(45)
  void receptionistCanRegisterSearchEditAndViewAttendanceHistoryOffline(
      FxRobot robot, @TempDir Path temp) throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("clients.sqlite"))) {
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
      UUID administratorId =
          UUID.fromString(scalar(database, "SELECT id FROM usuario WHERE perfil='ADMINISTRADOR'"));
      SessionContext sessions = new SessionContext();
      sessions.establish(
          new AuthenticatedUser(
              administratorId, "Admin de teste", "admin", UserRole.ADMINISTRADOR));
      AuthorizationService authorization = new AuthorizationService(sessions);
      SqliteClientRepository clients =
          new SqliteClientRepository(database.connection(), unitOfWork);
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      RegisterClientUseCase register =
          new RegisterClientUseCase(unitOfWork, clients, outbox, STATION, authorization, audit);
      MaintainClientUseCase maintain =
          new MaintainClientUseCase(unitOfWork, clients, outbox, authorization, audit, STATION);
      ClientRegistryPane pane = new ClientRegistryPane(clients, register, maintain, authorization);
      robot.interact(() -> stage.setScene(new Scene(pane, 1000, 700)));
      waitForPage(robot, "0 registros");

      Platform.runLater(() -> robot.lookup("#tableNew").queryButton().fire());
      waitForNode(robot, "#form-name");
      setField(robot, "#form-name", "Maria Oliveira");
      setField(robot, "#form-phone", "+55 (92) 99999-0001");
      setField(robot, "#form-contact", "WhatsApp");
      setField(robot, "#form-preferences", "Prefere horário da manhã");
      robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
      waitForPage(robot, "1 / 1");
      Client registered = clients.searchPage(query()).items().getFirst();
      assertEquals("CLI-000001", registered.internalCode());
      assertEquals("5592999990001", registered.phone());

      robot.interact(
          () -> robot.lookup("#tableSearch").queryTextInputControl().setText("92999990001"));
      waitForPage(robot, "1 / 1");
      TableView<Client> table = robot.lookup("#dataTable").queryTableView();
      robot.interact(() -> table.getSelectionModel().selectFirst());
      assertFalse(robot.lookup("#tableEdit").queryButton().isDisabled());
      addAttendance(database, registered.id(), administratorId);

      Platform.runLater(() -> robot.lookup("#tableEdit").queryButton().fire());
      waitForNode(robot, "#form-name");
      setField(robot, "#form-name", "Maria Oliveira Santos");
      robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
      waitForPage(robot, "1 / 1");
      assertEquals("Maria Oliveira Santos", clients.findById(registered.id()).orElseThrow().name());
      robot.interact(() -> table.getSelectionModel().selectFirst());

      Platform.runLater(() -> robot.lookup("#clientHistory").queryButton().fire());
      waitForNode(robot, "#clientAttendanceHistory");
      TableView<?> history = robot.lookup("#clientAttendanceHistory").queryTableView();
      assertEquals(1, history.getItems().size());
      assertTrue(history.getItems().getFirst().toString().contains("attendanceId"));
      robot.interact(() -> robot.lookup("#clientHistoryClose").queryButton().fire());

      Platform.runLater(() -> robot.lookup("#clientDeactivate").queryButton().fire());
      waitForNode(robot, "#clientDeactivateConfirm");
      robot.interact(() -> robot.lookup("#clientDeactivateConfirm").queryButton().fire());
      waitForPage(robot, "0 registros");
      assertFalse(clients.findById(registered.id()).orElseThrow().active());
      assertEquals(1, clients.attendanceHistory(registered.id()).size());
      robot.interact(
          () ->
              robot
                  .lookup("#clientShowInactive")
                  .queryAs(javafx.scene.control.CheckBox.class)
                  .fire());
      waitForPage(robot, "1 / 1");
      assertEquals(
          "Maria Oliveira Santos", clients.searchPage(query(true)).items().getFirst().name());
    }
  }

  @Test
  @Timeout(30)
  void showsAnAdvisoryForPossibleDuplicatesButAllowsHomonyms(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (LocalDatabaseSession database =
        LocalDatabaseSession.open(temp.resolve("duplicate.sqlite"))) {
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
      Fixture fixture = new Fixture(database);
      Client existing = fixture.register.register("Ana Souza", "92999990002");
      ClientRegistryPane pane =
          new ClientRegistryPane(
              fixture.clients, fixture.register, fixture.maintain, fixture.authorization);
      robot.interact(() -> stage.setScene(new Scene(pane, 1000, 700)));
      waitForPage(robot, "1 / 1");
      Platform.runLater(() -> robot.lookup("#tableNew").queryButton().fire());
      waitForNode(robot, "#form-name");
      setField(robot, "#form-name", "Ana Souza");
      setField(robot, "#form-phone", "92999990002");
      robot.interact(() -> robot.lookup("Salvar").queryButton().fire());
      waitForNode(robot, "#clientDuplicateContinue");
      assertTrue(
          robot
              .lookup(".alert .content.label")
              .queryLabeled()
              .getText()
              .contains("Pessoas homônimas"));
      robot.interact(() -> robot.lookup("#clientDuplicateContinue").queryButton().fire());
      waitForPage(robot, "1 / 1");
      assertEquals(2, fixture.clients.searchPage(query(true)).totalItems());
      assertTrue(fixture.clients.findById(existing.id()).isPresent());
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

  private static ClientPageQuery query() {
    return new ClientPageQuery("", ClientContactFilter.ALL, ClientSortField.NAME, true, 0, 25);
  }

  private static ClientPageQuery query(boolean inactive) {
    return new ClientPageQuery(
        "", ClientContactFilter.ALL, ClientSortField.NAME, true, 0, 25, inactive);
  }

  private static String scalar(LocalDatabaseSession database, String sql) throws Exception {
    try (Statement statement = database.connection().createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : "";
    }
  }

  private static void addAttendance(LocalDatabaseSession database, UUID clientId, UUID userId)
      throws Exception {
    UUID professionalId = UUID.randomUUID();
    UUID serviceId = UUID.randomUUID();
    UUID attendanceId = UUID.randomUUID();
    String now = "2026-10-31T15:00:00Z";
    try (PreparedStatement professional =
        database
            .connection()
            .prepareStatement(
                "INSERT INTO profissional (id, nome, telefone, ativo, criado_em_utc, atualizado_em_utc) VALUES (?, 'Profissional', NULL, 1, ?, ?)")) {
      professional.setString(1, professionalId.toString());
      professional.setString(2, now);
      professional.setString(3, now);
      professional.executeUpdate();
    }
    try (PreparedStatement service =
        database
            .connection()
            .prepareStatement(
                "INSERT INTO servico (id, nome, categoria, duracao_minutos, preco_centavos, comissao_pontos_base, ativo, criado_em_utc, atualizado_em_utc) VALUES (?, 'Manicure', 'Mãos', 45, 5000, 1000, 1, ?, ?)")) {
      service.setString(1, serviceId.toString());
      service.setString(2, now);
      service.setString(3, now);
      service.executeUpdate();
    }
    try (PreparedStatement attendance =
        database
            .connection()
            .prepareStatement(
                "INSERT INTO atendimento (id, agendamento_id, cliente_id, profissional_id, estado, subtotal_centavos, desconto_centavos, total_centavos, iniciado_em_utc, concluido_em_utc, criado_por_usuario_id, criado_em_utc) VALUES (?, NULL, ?, ?, 'CONCLUIDO', 5000, 0, 5000, ?, ?, ?, ?)")) {
      attendance.setString(1, attendanceId.toString());
      attendance.setString(2, clientId.toString());
      attendance.setString(3, professionalId.toString());
      attendance.setString(4, now);
      attendance.setString(5, now);
      attendance.setString(6, userId.toString());
      attendance.setString(7, now);
      attendance.executeUpdate();
    }
    try (PreparedStatement serviceLine =
        database
            .connection()
            .prepareStatement(
                "INSERT INTO atendimento_servico (id, atendimento_id, servico_id, nome_snapshot, quantidade, preco_unitario_centavos, comissao_pontos_base) VALUES (?, ?, ?, 'Manicure', 1, 5000, 1000)")) {
      serviceLine.setString(1, UUID.randomUUID().toString());
      serviceLine.setString(2, attendanceId.toString());
      serviceLine.setString(3, serviceId.toString());
      serviceLine.executeUpdate();
    }
  }

  private static final class Fixture {
    private final SqliteClientRepository clients;
    private final AuthorizationService authorization;
    private final RegisterClientUseCase register;
    private final MaintainClientUseCase maintain;

    private Fixture(LocalDatabaseSession database) {
      SqliteUnitOfWork unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteAuditRepository audit = new SqliteAuditRepository(database.connection(), unitOfWork);
      UUID adminId =
          UUID.fromString(
              readScalar(database, "SELECT id FROM usuario WHERE perfil='ADMINISTRADOR'"));
      SessionContext sessions = new SessionContext();
      sessions.establish(new AuthenticatedUser(adminId, "Admin", "admin", UserRole.ADMINISTRADOR));
      authorization = new AuthorizationService(sessions);
      clients = new SqliteClientRepository(database.connection(), unitOfWork);
      SqliteOutboxRepository outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      register =
          new RegisterClientUseCase(unitOfWork, clients, outbox, STATION, authorization, audit);
      maintain =
          new MaintainClientUseCase(unitOfWork, clients, outbox, authorization, audit, STATION);
    }

    private static String readScalar(LocalDatabaseSession database, String sql) {
      try (Statement statement = database.connection().createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        return result.next() ? result.getString(1) : "";
      } catch (Exception failure) {
        throw new IllegalStateException(failure);
      }
    }
  }
}
