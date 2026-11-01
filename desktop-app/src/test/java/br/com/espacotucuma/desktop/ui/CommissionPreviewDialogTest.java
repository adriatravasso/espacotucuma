package br.com.espacotucuma.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.commission.CommissionService;
import br.com.espacotucuma.desktop.application.commission.MaintainCommissionRuleUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.application.service.MaintainServiceUseCase;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import br.com.espacotucuma.desktop.infrastructure.sqlite.LocalDatabaseSession;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteAuditRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteCommissionRuleRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteInitialSetupRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteOutboxRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteProfessionalRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteServiceRepository;
import br.com.espacotucuma.desktop.infrastructure.sqlite.SqliteUnitOfWork;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
class CommissionPreviewDialogTest {
  private static final UUID STATION = UUID.fromString("5a6d4a7c-d655-44aa-910c-b17db4859890");
  private Stage stage;

  @Start
  void start(Stage stage) {
    this.stage = stage;
    stage.setScene(new Scene(new StackPane(), 720, 480));
    stage.show();
  }

  @Test
  @Timeout(45)
  void administratorCanOverrideAndOperatorSeesDiscountedCommissionPreview(
      FxRobot robot, @TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-preview.sqlite"))) {
      openDialog(robot, fixture);
      waitForNode(robot, "#commissionEstimate");
      assertTrue(robot.lookup("#commissionEstimate").queryLabeled().getText().contains("20%"));

      robot.interact(
          () ->
              robot.lookup("#commissionOverridePercent").queryTextInputControl().setText("25,00"));
      robot.interact(() -> robot.lookup("#commissionSetRule").queryButton().fire());
      assertTrue(robot.lookup("#commissionEstimate").queryLabeled().getText().contains("25%"));

      robot.interact(
          () -> robot.lookup("#commissionDiscount").queryTextInputControl().setText("10,00"));
      assertTrue(robot.lookup("#commissionEstimate").queryLabeled().getText().contains("22,50"));
      robot.interact(() -> robot.lookup("#commissionPreviewClose").queryButton().fire());
    }
  }

  @Test
  @Timeout(45)
  void receptionistCanViewPreviewButCannotManageRule(FxRobot robot, @TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-reception-preview.sqlite"))) {
      fixture.maintainRule.setRule(fixture.professionalId, fixture.service.id(), 2500);
      robot.interact(
          () ->
              fixture.sessions.establish(
                  new AuthenticatedUser(
                      UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO)));
      openDialog(robot, fixture);
      waitForNode(robot, "#commissionEstimate");
      assertTrue(robot.lookup("#commissionEstimate").queryLabeled().getText().contains("25%"));
      assertTrue(!robot.lookup("#commissionSetRule").queryButton().isVisible());
      robot.interact(
          () -> robot.lookup("#commissionDiscount").queryTextInputControl().setText("10,00"));
      assertTrue(robot.lookup("#commissionEstimate").queryLabeled().getText().contains("22,50"));
      robot.interact(() -> robot.lookup("#commissionPreviewClose").queryButton().fire());
    }
  }

  private void openDialog(FxRobot robot, Fixture fixture) {
    AtomicReference<CommissionPreviewDialog> dialogReference = new AtomicReference<>();
    robot.interact(
        () ->
            dialogReference.set(
                new CommissionPreviewDialog(
                    stage,
                    fixture.service,
                    fixture.commissions,
                    fixture.maintainRule,
                    fixture.rules,
                    fixture.professionals,
                    fixture.authorization)));
    Platform.runLater(dialogReference.get()::showAndWait);
  }

  private void waitForNode(FxRobot robot, String selector) throws Exception {
    WaitForAsyncUtils.waitFor(
        8, TimeUnit.SECONDS, () -> robot.lookup(selector).tryQuery().isPresent());
  }

  private static final class Fixture implements AutoCloseable {
    private final LocalDatabaseSession database;
    private final SqliteUnitOfWork unitOfWork;
    private final SqliteCommissionRuleRepository rules;
    private final SqliteProfessionalRepository professionals;
    private final AuthorizationService authorization;
    private final SessionContext sessions;
    private final MaintainCommissionRuleUseCase maintainRule;
    private final CommissionService commissions;
    private final BeautyService service;
    private final UUID professionalId;

    private Fixture(Path path) throws Exception {
      database = LocalDatabaseSession.open(path);
      unitOfWork = new SqliteUnitOfWork(database.connection());
      SqliteAuditRepository audit = new SqliteAuditRepository(database.connection(), unitOfWork);
      new CreateFirstAdministratorUseCase(
              unitOfWork,
              new SqliteInitialSetupRepository(database.connection(), unitOfWork),
              new Pbkdf2PasswordHasher(),
              audit,
              STATION)
          .create(
              new InitialAdministratorSetup(
                  "Espaço Tucumã", "Admin Teste", "admin", "SenhaTesteOffline2026!".toCharArray()));
      UUID adminId;
      try (Statement statement = database.connection().createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        result.next();
        adminId = UUID.fromString(result.getString("id"));
        sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                adminId,
                result.getString("nome"),
                result.getString("login"),
                UserRole.ADMINISTRADOR));
        authorization = new AuthorizationService(sessions);
      }
      var outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      rules = new SqliteCommissionRuleRepository(database.connection(), unitOfWork);
      professionals = new SqliteProfessionalRepository(database.connection(), unitOfWork);
      var serviceRepository = new SqliteServiceRepository(database.connection(), unitOfWork);
      var serviceMaintenance =
          new MaintainServiceUseCase(
              unitOfWork, serviceRepository, outbox, authorization, audit, STATION);
      maintainRule =
          new MaintainCommissionRuleUseCase(
              unitOfWork, rules, outbox, authorization, audit, STATION);
      commissions = new CommissionService(rules, unitOfWork, authorization);
      service = serviceMaintenance.register("Manicure", "Unhas", 45, Money.of("100.00"), 2000);
      professionalId = UUID.randomUUID();
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id,nome,telefone,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Profissional Teste',NULL,1,'2026-11-01T12:00:00Z','2026-11-01T12:00:00Z')")) {
        statement.setString(1, professionalId.toString());
        statement.executeUpdate();
      }
      UUID clientId = UUID.randomUUID();
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,telefone,contato_opcional,observacao_operacional,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'CLI-727272','Cliente Teste',NULL,NULL,NULL,1,'2026-11-01T12:00:00Z','2026-11-01T12:00:00Z')")) {
        statement.setString(1, clientId.toString());
        statement.executeUpdate();
      }
      unitOfWork.execute(
          () -> {
            try (PreparedStatement interval =
                database
                    .connection()
                    .prepareStatement(
                        "INSERT INTO disponibilidade_profissional (id,profissional_id,dia_semana,inicio_local,fim_local,ativo) VALUES (?, ?, 1, '09:00','17:00',1)")) {
              interval.setString(1, UUID.randomUUID().toString());
              interval.setString(2, professionalId.toString());
              interval.executeUpdate();
              return null;
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          });
      // The active-professional picker requires a valid weekly schedule; the row above provides it.
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
