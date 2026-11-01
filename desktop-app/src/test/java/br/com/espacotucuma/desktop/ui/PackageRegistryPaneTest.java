package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.port.prepaid.PrepaidPackageRepository;
import br.com.espacotucuma.desktop.application.prepaid.MaintainPrepaidPackagesUseCase;
import br.com.espacotucuma.desktop.application.prepaid.PaymentForm;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteClientRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqlitePrepaidPackageRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
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
class PackageRegistryPaneTest {
  private static final UUID STATION = UUID.fromString("59fd30ba-0c77-43c3-a261-e01bc1f6445c");
  private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 1100, 720));
    stage.show();
  }

  @Test
  @Timeout(45)
  void contractsAndConsumesOneSessionOffline(FxRobot robot, @TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("package-ui.sqlite"))) {
      fixture.maintain.create("Pacote de teste", 3, 30, Money.of("90.00"));
      PackageRegistryPane pane =
          new PackageRegistryPane(
              fixture.repository, fixture.clients, fixture.maintain, fixture.authorization);
      robot.interact(() -> stage.setScene(new Scene(pane, 1100, 720)));
      waitFor(robot, "#packageCatalogTable", 1);
      TableView<?> packageTable = robot.lookup("#packageCatalogTable").queryTableView();
      robot.interact(() -> packageTable.getSelectionModel().selectFirst());
      Platform.runLater(() -> robot.lookup("#packageContract").queryButton().fire());
      waitFor(robot, "#packageClient", 1);
      robot.interact(
          () -> robot.lookup("#packageClient").queryComboBox().getSelectionModel().selectFirst());
      robot.interact(
          () ->
              robot
                  .lookup("#packagePaymentForm")
                  .queryComboBox()
                  .getSelectionModel()
                  .select(PaymentForm.PIX));
      robot.interact(() -> robot.lookup("OK").queryButton().fire());
      waitFor(robot, "#packageBalanceTable", 1);

      TabPane tabs = robot.lookup("#packageTabs").queryAs(TabPane.class);
      robot.interact(() -> tabs.getSelectionModel().select(1));
      TableView<?> balanceTable = robot.lookup("#packageBalanceTable").queryTableView();
      WaitForAsyncUtils.waitFor(8, TimeUnit.SECONDS, () -> balanceTable.getItems().size() == 1);
      robot.interact(() -> balanceTable.getSelectionModel().selectFirst());
      Platform.runLater(() -> robot.lookup("#packageConsume").queryButton().fire());
      waitFor(robot, "#packageAttendance", 1);
      robot.interact(
          () ->
              robot.lookup("#packageAttendance").queryComboBox().getSelectionModel().selectFirst());
      robot.interact(() -> robot.lookup("#packageConsumeConfirm").queryButton().fire());
      WaitForAsyncUtils.waitFor(
          8,
          TimeUnit.SECONDS,
          () ->
              fixture.repository.searchBalances("", 0, 10).items().getFirst().sessionsRemaining()
                  == 2);
      assertEquals(
          "Pacote de teste",
          fixture.repository.searchBalances("", 0, 10).items().getFirst().packageName());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM utilizacao_pacote"));
    }
  }

  private void waitFor(FxRobot robot, String selector, int count) throws Exception {
    WaitForAsyncUtils.waitFor(
        8, TimeUnit.SECONDS, () -> robot.lookup(selector).tryQuery().isPresent() && count > 0);
  }

  private static final class Fixture implements AutoCloseable {
    final LocalDatabaseSession database;
    final SqliteUnitOfWork unitOfWork;
    final SqliteAuditRepository audit;
    final SqliteOutboxRepository outbox;
    final PrepaidPackageRepository repository;
    final SqliteClientRepository clients;
    final MaintainPrepaidPackagesUseCase maintain;
    final AuthorizationService authorization;
    final UUID adminId;
    final UUID clientId = UUID.randomUUID();
    final UUID professionalId = UUID.randomUUID();
    final UUID serviceId = UUID.randomUUID();
    final UUID attendanceId = UUID.randomUUID();

    Fixture(Path path) throws Exception {
      database = LocalDatabaseSession.open(path);
      unitOfWork = new SqliteUnitOfWork(database.connection());
      audit = new SqliteAuditRepository(database.connection(), unitOfWork);
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã", "Admin UI", "admin", "SenhaTesteOffline2026!".toCharArray()));
      try (Statement statement = database.connection().createStatement();
          ResultSet row =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        row.next();
        adminId = UUID.fromString(row.getString("id"));
        SessionContext session = new SessionContext();
        session.establish(
            new AuthenticatedUser(
                adminId, row.getString("nome"), row.getString("login"), UserRole.ADMINISTRADOR));
        authorization = new AuthorizationService(session);
      }
      outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      clients = new SqliteClientRepository(database.connection(), unitOfWork);
      repository = new SqlitePrepaidPackageRepository(database.connection(), unitOfWork);
      maintain =
          new MaintainPrepaidPackagesUseCase(
              unitOfWork,
              repository,
              clients,
              outbox,
              authorization,
              audit,
              STATION,
              Clock.fixed(NOW, ZoneId.of("America/Manaus")));
      insertFixtures();
    }

    private void insertFixtures() throws Exception {
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'CLI-992001','Cliente UI',1,?,?)")) {
        statement.setString(1, clientId.toString());
        statement.setString(2, NOW.toString());
        statement.setString(3, NOW.toString());
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Profissional UI',1,?,?)")) {
        statement.setString(1, professionalId.toString());
        statement.setString(2, NOW.toString());
        statement.setString(3, NOW.toString());
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO servico (id,nome,categoria,duracao_minutos,preco_centavos,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Serviço UI','Geral',30,1000,1,?,?)")) {
        statement.setString(1, serviceId.toString());
        statement.setString(2, NOW.toString());
        statement.setString(3, NOW.toString());
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento (id,cliente_id,profissional_id,estado,subtotal_centavos,desconto_centavos,total_centavos,iniciado_em_utc,criado_por_usuario_id,criado_em_utc) VALUES (?,?,?,'EM_ANDAMENTO',1000,0,1000,?,?,?)")) {
        statement.setString(1, attendanceId.toString());
        statement.setString(2, clientId.toString());
        statement.setString(3, professionalId.toString());
        statement.setString(4, NOW.toString());
        statement.setString(5, adminId.toString());
        statement.setString(6, NOW.toString());
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento_servico (id,atendimento_id,servico_id,nome_snapshot,quantidade,preco_unitario_centavos,comissao_pontos_base) VALUES (?,?,?,'Serviço UI',1,1000,0)")) {
        statement.setString(1, UUID.randomUUID().toString());
        statement.setString(2, attendanceId.toString());
        statement.setString(3, serviceId.toString());
        statement.executeUpdate();
      }
    }

    int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet row = statement.executeQuery(sql)) {
        return row.next() ? row.getInt(1) : -1;
      }
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
