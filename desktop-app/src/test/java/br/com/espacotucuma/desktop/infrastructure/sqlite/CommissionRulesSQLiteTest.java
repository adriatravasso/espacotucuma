package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.commission.CloseCommissionCompetencyUseCase;
import br.com.espacotucuma.desktop.application.commission.CommissionDecision;
import br.com.espacotucuma.desktop.application.commission.CommissionService;
import br.com.espacotucuma.desktop.application.commission.MaintainCommissionRuleUseCase;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationException;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.application.service.MaintainServiceUseCase;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommissionRulesSQLiteTest {
  private static final UUID STATION = UUID.fromString("d416b80d-a3ec-45ee-b33c-4f2c5caa3801");
  private static final String NOW = "2026-11-01T12:00:00Z";

  @Test
  void overrideIsAuditedAndSnapshotsRemainAfterRulesChange(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-rules.sqlite"))) {
      BeautyService service =
          fixture.maintainService.register("Manicure", "Unhas", 45, Money.of("100.00"), 2000);
      UUID professionalId = fixture.insertProfessional();
      UUID attendanceLine = fixture.insertAttendanceService(service.id(), professionalId);

      assertEquals(
          Money.of("20.00"),
          fixture
              .commissions
              .preview(professionalId, service.id(), 2000, Money.of("100.00"), Money.of("0.00"))
              .commission());
      var rule = fixture.maintainRule.setRule(professionalId, service.id(), 2500);
      var discounted =
          fixture.commissions.preview(
              professionalId, service.id(), 2000, Money.of("100.00"), Money.of("20.00"));
      assertEquals(2500, discounted.decision().percentageBasisPoints());
      assertEquals(Money.of("20.00"), discounted.commission());
      fixture.commissions.snapshot(attendanceLine, discounted.decision());

      fixture.maintainRule.setRule(professionalId, service.id(), 3000);
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM atendimento_servico WHERE id='"
                  + attendanceLine
                  + "' AND comissao_pontos_base=2500 AND comissao_origem='PROFISSIONAL_SERVICO' AND comissao_regra_id='"
                  + rule.id()
                  + "'"));
      fixture.execute(
          "UPDATE atendimento SET estado='CONCLUIDO',concluido_em_utc='2026-11-01T12:30:00Z' WHERE id=(SELECT atendimento_id FROM atendimento_servico WHERE id='"
              + attendanceLine
              + "')");
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.commissions.snapshot(attendanceLine, discounted.decision()));
      assertEquals(
          2,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='regra_comissao_profissional_servico'"));
      assertEquals(
          2,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE entidade='regra_comissao_profissional_servico'"));

      fixture.maintainRule.deactivate(rule.id());
      var fallback =
          fixture.commissions.preview(
              professionalId, service.id(), 2000, Money.of("100.00"), Money.of("0.00"));
      assertEquals(2000, fallback.decision().percentageBasisPoints());
      assertEquals(Money.of("20.00"), fallback.commission());
      assertEquals(
          3,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='regra_comissao_profissional_servico'"));
    }
  }

  @Test
  void receptionistCanPreviewButCannotChangeCommissionRules(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-permissions.sqlite"))) {
      BeautyService service =
          fixture.maintainService.register("Pedicure", "Unhas", 50, Money.of("45.00"), 1500);
      UUID professionalId = fixture.insertProfessional();
      SessionContext sessions = new SessionContext();
      sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
      AuthorizationService receptionist = new AuthorizationService(sessions);
      CommissionService preview =
          new CommissionService(fixture.rules, fixture.unitOfWork, receptionist);
      MaintainCommissionRuleUseCase restrictedMaintenance =
          new MaintainCommissionRuleUseCase(
              fixture.unitOfWork,
              fixture.rules,
              fixture.outbox,
              receptionist,
              fixture.audit,
              STATION);

      assertEquals(
          Money.of("6.75"),
          preview
              .preview(professionalId, service.id(), 1500, Money.of("45.00"), Money.of("0.00"))
              .commission());
      assertThrows(
          AuthorizationException.class,
          () -> restrictedMaintenance.setRule(professionalId, service.id(), 2000));
      assertTrue(fixture.authorization.isAllowed(Permission.COMMISSION_RULE_MANAGE));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM regra_comissao_profissional_servico"));
    }
  }

  @Test
  void commissionRuleAndOutboxRollBackTogetherWhenAuditFails(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-rollback.sqlite"))) {
      BeautyService service =
          fixture.maintainService.register("Sobrancelha", "Rosto", 30, Money.of("35.00"), 2000);
      UUID professionalId = fixture.insertProfessional();
      fixture.execute(
          "CREATE TRIGGER trg_test_reject_commission_audit BEFORE INSERT ON evento_auditoria "
              + "WHEN NEW.entidade='regra_comissao_profissional_servico' "
              + "BEGIN SELECT RAISE(ABORT, 'auditoria rejeitada'); END");

      assertThrows(
          IllegalStateException.class,
          () -> fixture.maintainRule.setRule(professionalId, service.id(), 2500));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM regra_comissao_profissional_servico"));
      assertEquals(
          0,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='regra_comissao_profissional_servico'"));
    }
  }

  @Test
  void closesCommissionMonthFromSnapshotsAndKeepsStatementStable(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-statement.sqlite"))) {
      BeautyService service =
          fixture.maintainService.register("Manicure", "Unhas", 45, Money.of("100.00"), 2000);
      UUID professionalId = fixture.insertProfessional();
      UUID lineId = fixture.insertAttendanceService(service.id(), professionalId);
      fixture.commissions.snapshot(
          lineId, new CommissionDecision(2500, CommissionDecision.Source.SERVICE, null));
      UUID attendanceId =
          UUID.fromString(
              fixture.string(
                  "SELECT atendimento_id FROM atendimento_servico WHERE id='" + lineId + "'"));
      fixture.execute(
          "UPDATE atendimento SET estado='CONCLUIDO',subtotal_centavos=10000,"
              + "desconto_centavos=2000,total_centavos=8000,concluido_em_utc='2026-11-10T16:00:00Z' "
              + "WHERE id='"
              + attendanceId
              + "'");
      UUID canceledLineId = fixture.insertAttendanceService(service.id(), professionalId);
      UUID canceledAttendanceId =
          UUID.fromString(
              fixture.string(
                  "SELECT atendimento_id FROM atendimento_servico WHERE id='"
                      + canceledLineId
                      + "'"));
      fixture.execute(
          "UPDATE atendimento SET estado='CANCELADO' WHERE id='" + canceledAttendanceId + "'");

      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM comissao"));
      fixture.maintainRule.setRule(professionalId, service.id(), 3000);
      var closed = fixture.closeCompetency.close(YearMonth.of(2026, 11));
      assertEquals(1, closed.size());
      assertEquals(10_000, closed.getFirst().grossCents());
      assertEquals(2_000, closed.getFirst().discountCents());
      assertEquals(8_000, closed.getFirst().commissionBaseCents());
      assertEquals(2_000, closed.getFirst().commissionCents());
      assertEquals(2500, closed.getFirst().percentageBasisPoints());
      assertEquals("PENDENTE", closed.getFirst().status());
      assertTrue(fixture.closeCompetency.isClosed(YearMonth.of(2026, 11)));
      var statement = fixture.closeCompetency.statement(YearMonth.of(2026, 11), professionalId);
      assertEquals(closed, statement);
      assertThrows(
          java.sql.SQLException.class,
          () ->
              fixture.execute(
                  "UPDATE comissao SET valor_centavos=1 WHERE atendimento_servico_id='"
                      + lineId
                      + "'"));

      fixture.maintainRule.setRule(professionalId, service.id(), 3500);
      fixture.execute(
          "UPDATE profissional SET nome='Nome alterado após fechamento' WHERE id='"
              + professionalId
              + "'");
      var repeatedClose = fixture.closeCompetency.close(YearMonth.of(2026, 11));
      assertEquals(closed, repeatedClose);
      assertEquals(
          "Profissional Teste",
          fixture
              .closeCompetency
              .statement(YearMonth.of(2026, 11), professionalId)
              .getFirst()
              .professionalName());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM comissao"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM competencia_comissao"));
      assertEquals(
          1, fixture.scalar("SELECT COUNT(*) FROM evento_auditoria WHERE acao='COMISSAO_APURADA'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='COMPETENCIA_COMISSAO_FECHADA'"));
    }
  }

  @Test
  void commissionMonthCloseRollsBackAllEntriesIfAuditCannotBeWritten(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("commission-statement-rollback.sqlite"))) {
      BeautyService service =
          fixture.maintainService.register("Sobrancelha", "Rosto", 30, Money.of("60.00"), 2000);
      UUID professionalId = fixture.insertProfessional();
      UUID lineId = fixture.insertAttendanceService(service.id(), professionalId);
      UUID attendanceId =
          UUID.fromString(
              fixture.string(
                  "SELECT atendimento_id FROM atendimento_servico WHERE id='" + lineId + "'"));
      fixture.execute(
          "UPDATE atendimento SET estado='CONCLUIDO',subtotal_centavos=10000,"
              + "desconto_centavos=0,total_centavos=10000,concluido_em_utc='2026-11-10T16:00:00Z' "
              + "WHERE id='"
              + attendanceId
              + "'");
      fixture.execute(
          "CREATE TRIGGER trg_test_reject_commission_close BEFORE INSERT ON evento_auditoria "
              + "WHEN NEW.acao='COMPETENCIA_COMISSAO_FECHADA' BEGIN SELECT RAISE(ABORT, 'auditoria rejeitada'); END");

      assertThrows(
          IllegalStateException.class, () -> fixture.closeCompetency.close(YearMonth.of(2026, 11)));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM comissao"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM competencia_comissao"));
      assertEquals(
          0, fixture.scalar("SELECT COUNT(*) FROM operacao_pendente WHERE entidade='comissao'"));
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final LocalDatabaseSession database;
    private final SqliteUnitOfWork unitOfWork;
    private final SqliteAuditRepository audit;
    private final SqliteOutboxRepository outbox;
    private final SqliteCommissionRuleRepository rules;
    private final AuthorizationService authorization;
    private final MaintainServiceUseCase maintainService;
    private final MaintainCommissionRuleUseCase maintainRule;
    private final CommissionService commissions;
    private final SqliteCommissionStatementRepository commissionStatements;
    private final CloseCommissionCompetencyUseCase closeCompetency;
    private final UUID adminId;
    private int clientSequence;

    private Fixture(Path path) throws Exception {
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
                  "Espaço Tucumã", "Admin Teste", "admin", "SenhaTesteOffline2026!".toCharArray()));
      try (Statement statement = database.connection().createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        result.next();
        adminId = UUID.fromString(result.getString("id"));
        SessionContext sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                adminId,
                result.getString("nome"),
                result.getString("login"),
                UserRole.ADMINISTRADOR));
        authorization = new AuthorizationService(sessions);
      }
      outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      rules = new SqliteCommissionRuleRepository(database.connection(), unitOfWork);
      commissionStatements =
          new SqliteCommissionStatementRepository(database.connection(), unitOfWork);
      var services = new SqliteServiceRepository(database.connection(), unitOfWork);
      maintainService =
          new MaintainServiceUseCase(unitOfWork, services, outbox, authorization, audit, STATION);
      maintainRule =
          new MaintainCommissionRuleUseCase(
              unitOfWork, rules, outbox, authorization, audit, STATION);
      commissions = new CommissionService(rules, unitOfWork, authorization);
      closeCompetency =
          new CloseCommissionCompetencyUseCase(
              unitOfWork,
              commissionStatements,
              outbox,
              authorization,
              audit,
              STATION,
              Clock.fixed(Instant.parse("2026-12-01T12:00:00Z"), ZoneId.of("America/Manaus")));
    }

    private UUID insertProfessional() throws Exception {
      UUID id = UUID.randomUUID();
      try (PreparedStatement statement =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id,nome,telefone,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Profissional Teste',NULL,1,?,?)")) {
        statement.setString(1, id.toString());
        statement.setString(2, NOW);
        statement.setString(3, NOW);
        statement.executeUpdate();
      }
      return id;
    }

    private UUID insertAttendanceService(UUID serviceId, UUID professionalId) throws Exception {
      UUID clientId = UUID.randomUUID();
      UUID attendanceId = UUID.randomUUID();
      UUID lineId = UUID.randomUUID();
      try (PreparedStatement client =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,telefone,contato_opcional,observacao_operacional,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,?, 'Cliente Teste',NULL,NULL,NULL,1,?,?)")) {
        client.setString(1, clientId.toString());
        client.setString(
            2, "CLI-" + String.format(java.util.Locale.ROOT, "%06d", ++clientSequence));
        client.setString(3, NOW);
        client.setString(4, NOW);
        client.executeUpdate();
      }
      try (PreparedStatement attendance =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento (id,agendamento_id,cliente_id,profissional_id,estado,subtotal_centavos,desconto_centavos,total_centavos,iniciado_em_utc,concluido_em_utc,criado_por_usuario_id,criado_em_utc) VALUES (?,NULL,?,?,'EM_ANDAMENTO',10000,0,10000,?,NULL,?,?)")) {
        attendance.setString(1, attendanceId.toString());
        attendance.setString(2, clientId.toString());
        attendance.setString(3, professionalId.toString());
        attendance.setString(4, NOW);
        attendance.setString(5, adminId.toString());
        attendance.setString(6, NOW);
        attendance.executeUpdate();
      }
      try (PreparedStatement line =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento_servico (id,atendimento_id,servico_id,nome_snapshot,quantidade,preco_unitario_centavos,comissao_pontos_base) VALUES (?,?,?,'Manicure',1,10000,2000)")) {
        line.setString(1, lineId.toString());
        line.setString(2, attendanceId.toString());
        line.setString(3, serviceId.toString());
        line.executeUpdate();
      }
      return lineId;
    }

    private int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        return result.next() ? result.getInt(1) : -1;
      }
    }

    private String string(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        return result.next() ? result.getString(1) : null;
      }
    }

    private void execute(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement()) {
        statement.execute(sql);
      }
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
