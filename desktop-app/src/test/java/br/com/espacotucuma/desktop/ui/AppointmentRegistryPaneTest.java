package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.attendance.MaintainAttendanceUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.prepaid.PrepaidPackageRepository;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.scheduling.MaintainAppointmentUseCase;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAppointmentRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAttendanceRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteClientRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteCommissionRuleRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqlitePrepaidPackageRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProfessionalRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteServiceRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.DatePicker;
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
class AppointmentRegistryPaneTest {
  private static final UUID STATION = UUID.fromString("dc031a93-af4a-44ae-bfc7-ddfbf43fa270");
  private static final Instant NOW = Instant.parse("2026-11-01T14:00:00Z");
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 1180, 720));
    stage.show();
  }

  @Test
  @Timeout(60)
  void createsThenBrowsesWeekFiltersProfessionalAndDisplaysManausTimes(
      FxRobot robot, @TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("appointment-ui.sqlite"))) {
      fixture.maintain.create(
          fixture.clientId,
          fixture.secondProfessionalId,
          fixture.serviceId,
          LocalDateTime.of(MONDAY, LocalTime.of(10, 0)),
          "Segundo profissional");
      AppointmentRegistryPane pane =
          new AppointmentRegistryPane(
              fixture.maintain,
              fixture.clients,
              fixture.professionals,
              fixture.services,
              fixture.authorization,
              fixture.attendance,
              fixture.prepaidPackages);
      robot.interact(() -> stage.setScene(new Scene(pane, 1180, 720)));
      robot.interact(() -> pane.datePicker().setValue(MONDAY));

      Platform.runLater(() -> robot.lookup("#appointmentNew").queryButton().fire());
      waitForNode(robot, "#appointmentFormTime");
      robot.interact(
          () -> {
            robot.lookup("#appointmentClient").queryComboBox().getSelectionModel().selectFirst();
            robot
                .lookup("#appointmentProfessional")
                .queryComboBox()
                .getSelectionModel()
                .selectFirst();
            robot.lookup("#appointmentService").queryComboBox().getSelectionModel().selectFirst();
            robot.lookup("#appointmentFormDate").queryAs(DatePicker.class).setValue(MONDAY);
            robot.lookup("#appointmentFormTime").queryTextInputControl().setText("09:00");
          });
      Platform.runLater(() -> robot.lookup("OK").queryButton().fire());
      waitForRows(robot, 2);
      AppointmentRegistryPane.AppointmentRow created =
          pane.appointmentTable().getItems().stream()
              .filter(row -> row.appointment().startsAt().toLocalTime().equals(LocalTime.of(9, 0)))
              .findFirst()
              .orElseThrow();
      assertEquals("02/11/2026", created.dateText());
      assertEquals("09:00–09:30", created.timeText());
      assertEquals("Cliente Agenda", created.clientName());
      robot.interact(
          () ->
              pane.professionalFilter().getItems().stream()
                  .filter(choice -> fixture.professionalId.equals(choice.id()))
                  .findFirst()
                  .ifPresent(
                      choice -> pane.professionalFilter().getSelectionModel().select(choice)));
      waitForRows(robot, 1);
      assertTrue(
          robot.lookup("#appointmentAvailability").queryLabeled().getText().contains("08:00"));

      Platform.runLater(() -> robot.lookup("#appointmentNew").queryButton().fire());
      waitForNode(robot, "#appointmentFormTime");
      robot.interact(
          () -> {
            robot.lookup("#appointmentClient").queryComboBox().getSelectionModel().selectFirst();
            robot
                .lookup("#appointmentProfessional")
                .queryComboBox()
                .getSelectionModel()
                .selectFirst();
            robot.lookup("#appointmentService").queryComboBox().getSelectionModel().selectFirst();
            robot.lookup("#appointmentFormDate").queryAs(DatePicker.class).setValue(MONDAY);
            robot.lookup("#appointmentFormTime").queryTextInputControl().setText("09:15");
          });
      Platform.runLater(() -> robot.lookup("OK").queryButton().fire());
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              robot
                  .lookup("#appointmentMessage")
                  .queryLabeled()
                  .getText()
                  .contains("já possui agendamento"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM agendamento WHERE profissional_id='"
                  + fixture.professionalId
                  + "' AND estado IN ('AGENDADO','CONFIRMADO','EM_ATENDIMENTO')"));

      robot.interact(
          () ->
              robot.lookup("#appointmentTable").queryTableView().getSelectionModel().selectFirst());
      Platform.runLater(() -> robot.lookup("#appointmentConfirm").queryButton().fire());
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              fixture
                  .maintain
                  .findById(created.appointment().id())
                  .status()
                  .name()
                  .equals("CONFIRMADO"));
      robot.interact(
          () ->
              robot.lookup("#appointmentTable").queryTableView().getSelectionModel().selectFirst());
      Platform.runLater(() -> robot.lookup("#appointmentStart").queryButton().fire());
      waitForNode(robot, "#attendanceServiceChoice");
      Platform.runLater(() -> robot.lookup("#attendanceComplete").queryButton().fire());
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              robot
                  .lookup("#attendanceMessage")
                  .queryLabeled()
                  .getText()
                  .contains("Adicione ao menos"));
      robot.interact(
          () -> {
            robot
                .lookup("#attendanceServiceChoice")
                .queryComboBox()
                .getSelectionModel()
                .selectFirst();
          });
      Platform.runLater(() -> robot.lookup("#attendanceAddService").queryButton().fire());
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () -> robot.lookup("#attendanceServices").queryTableView().getItems().size() == 1);
      assertTrue(robot.lookup("#attendanceSubtotal").queryLabeled().getText().contains("50,00"));
      assertTrue(robot.lookup("#attendanceCommission").queryLabeled().getText().contains("0.00"));
      robot.interact(
          () -> {
            robot.lookup("#attendanceDiscount").queryTextInputControl().setText("5,00");
            robot
                .lookup("#attendanceDiscountReason")
                .queryTextInputControl()
                .setText("Ajuste aprovado");
          });
      robot.interact(
          () ->
              fixture.execute(
                  "UPDATE servico SET preco_centavos=6500 WHERE id='" + fixture.serviceId + "'"));
      Platform.runLater(() -> robot.lookup("#attendanceApplyDiscount").queryButton().fire());
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              robot
                  .lookup("#attendanceMessage")
                  .queryLabeled()
                  .getText()
                  .contains("Desconto autorizado"));
      assertTrue(robot.lookup("#attendanceTotal").queryLabeled().getText().contains("45,00"));
      robot.interact(
          () ->
              robot
                  .lookup("#attendanceNotes")
                  .queryTextInputControl()
                  .setText("Atendimento de teste"));
      Platform.runLater(() -> robot.lookup("#attendanceSaveNotes").queryButton().fire());
      Platform.runLater(() -> robot.lookup("#attendanceComplete").queryButton().fire());
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              fixture
                  .maintain
                  .findById(created.appointment().id())
                  .status()
                  .name()
                  .equals("CONCLUIDO"));
      assertEquals(5000, fixture.scalar("SELECT preco_unitario_centavos FROM atendimento_servico"));
      assertEquals(4500, fixture.scalar("SELECT total_centavos FROM atendimento"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM recebimento"));

      robot.interact(
          () ->
              robot
                  .lookup("#appointmentPeriod")
                  .queryComboBox()
                  .getSelectionModel()
                  .select("Semana"));
      robot.interact(() -> pane.professionalFilter().getSelectionModel().selectFirst());
      waitForRows(robot, 2);
      robot.interact(
          () ->
              pane.professionalFilter().getItems().stream()
                  .filter(choice -> fixture.professionalId.equals(choice.id()))
                  .findFirst()
                  .ifPresent(
                      choice -> pane.professionalFilter().getSelectionModel().select(choice)));
      waitForRows(robot, 1);
      assertEquals(
          fixture.professionalId,
          pane.appointmentTable().getItems().getFirst().appointment().professionalId());
      assertEquals(
          1,
          pane.appointmentTable().getItems().stream()
              .map(row -> row.appointment().id())
              .distinct()
              .count());
      assertTrue(robot.lookup("#appointmentMessage").queryLabeled().getText().contains("Manaus"));
    }
  }

  private void waitForRows(FxRobot robot, int count) throws Exception {
    WaitForAsyncUtils.waitFor(
        10,
        TimeUnit.SECONDS,
        () -> robot.lookup("#appointmentTable").queryTableView().getItems().size() == count);
  }

  private void waitForNode(FxRobot robot, String selector) throws Exception {
    WaitForAsyncUtils.waitFor(
        8, TimeUnit.SECONDS, () -> robot.lookup(selector).tryQuery().isPresent());
  }

  private static final class Fixture implements AutoCloseable {
    final LocalDatabaseSession database;
    final ClientRepository clients;
    final ProfessionalRepository professionals;
    final ServiceRepository services;
    final MaintainAttendanceUseCase attendance;
    final PrepaidPackageRepository prepaidPackages;
    final AuthorizationService authorization;
    final MaintainAppointmentUseCase maintain;
    final UUID clientId = UUID.randomUUID();
    final UUID professionalId = UUID.randomUUID();
    final UUID secondProfessionalId = UUID.randomUUID();
    final UUID serviceId = UUID.randomUUID();

    Fixture(Path path) throws Exception {
      database = LocalDatabaseSession.open(path);
      SqliteUnitOfWork unit = new SqliteUnitOfWork(database.connection());
      SqliteAuditRepository audit = new SqliteAuditRepository(database.connection(), unit);
      new CreateFirstAdministratorUseCase(
              unit,
              new SqliteInitialSetupRepository(database.connection(), unit),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã",
                  "Admin de teste",
                  "admin",
                  "SenhaOfflineTeste2026!".toCharArray()));
      try (Statement statement = database.connection().createStatement();
          ResultSet row =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        row.next();
        SessionContext sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                UUID.fromString(row.getString("id")),
                row.getString("nome"),
                row.getString("login"),
                UserRole.ADMINISTRADOR));
        authorization = new AuthorizationService(sessions);
      }
      clients = new SqliteClientRepository(database.connection(), unit);
      professionals = new SqliteProfessionalRepository(database.connection(), unit);
      services = new SqliteServiceRepository(database.connection(), unit);
      var appointmentRepository = new SqliteAppointmentRepository(database.connection(), unit);
      var outbox = new SqliteOutboxRepository(database.connection(), unit);
      var commissionRules = new SqliteCommissionRuleRepository(database.connection(), unit);
      prepaidPackages = new SqlitePrepaidPackageRepository(database.connection(), unit);
      seed();
      maintain =
          new MaintainAppointmentUseCase(
              unit,
              appointmentRepository,
              clients,
              professionals,
              services,
              outbox,
              authorization,
              audit,
              STATION,
              Clock.fixed(NOW, ZoneId.of("America/Manaus")));
      attendance =
          new MaintainAttendanceUseCase(
              unit,
              new SqliteAttendanceRepository(database.connection(), unit),
              appointmentRepository,
              services,
              commissionRules,
              outbox,
              authorization,
              audit,
              STATION,
              Clock.fixed(NOW, ZoneId.of("America/Manaus")));
    }

    private void seed() throws Exception {
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'CLI-994002','Cliente Agenda',1,?,?)")) {
        insert.setString(1, clientId.toString());
        insert.setString(2, NOW.toString());
        insert.setString(3, NOW.toString());
        insert.executeUpdate();
      }
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,?,1,?,?)")) {
        for (String[] person :
            new String[][] {
              {professionalId.toString(), "Ana Agenda"},
              {secondProfessionalId.toString(), "Bia Agenda"}
            }) {
          insert.setString(1, person[0]);
          insert.setString(2, person[1]);
          insert.setString(3, NOW.toString());
          insert.setString(4, NOW.toString());
          insert.addBatch();
        }
        insert.executeBatch();
      }
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO disponibilidade_profissional (id,profissional_id,dia_semana,inicio_local,fim_local,ativo) VALUES (?,?,1,'08:00','17:00',1)")) {
        for (UUID id : new UUID[] {professionalId, secondProfessionalId}) {
          insert.setString(1, UUID.randomUUID().toString());
          insert.setString(2, id.toString());
          insert.addBatch();
        }
        insert.executeBatch();
      }
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO servico (id,nome,categoria,duracao_minutos,preco_centavos,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Manicure teste','Unhas',30,5000,1,?,?)")) {
        insert.setString(1, serviceId.toString());
        insert.setString(2, NOW.toString());
        insert.setString(3, NOW.toString());
        insert.executeUpdate();
      }
    }

    int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet row = statement.executeQuery(sql)) {
        return row.next() ? row.getInt(1) : -1;
      }
    }

    void execute(String sql) {
      try (Statement statement = database.connection().createStatement()) {
        statement.execute(sql);
      } catch (java.sql.SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
