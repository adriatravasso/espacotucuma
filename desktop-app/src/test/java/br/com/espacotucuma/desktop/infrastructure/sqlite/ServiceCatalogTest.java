package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationException;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.service.BeautyService;
import br.com.espacotucuma.desktop.application.service.MaintainServiceUseCase;
import br.com.espacotucuma.desktop.application.service.ServicePageQuery;
import br.com.espacotucuma.desktop.application.service.ServiceSortField;
import br.com.espacotucuma.desktop.domain.money.Money;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceCatalogTest {
  private static final UUID STATION = UUID.fromString("43a842c9-9538-4c86-82fc-c92fb07f6c6f");

  @Test
  void serviceValuesRejectInvalidPriceDurationAndCommission() {
    Instant now = Instant.parse("2026-11-01T12:00:00Z");
    assertEquals(new Money(new BigDecimal("35.00")), service("35.00", 45, 3000, now).price());
    assertThrows(IllegalArgumentException.class, () -> service("-0.01", 45, 3000, now));
    assertThrows(IllegalArgumentException.class, () -> service("35.00", 0, 3000, now));
    assertThrows(IllegalArgumentException.class, () -> service("35.00", 45, 10001, now));
    assertThrows(ArithmeticException.class, () -> new Money(new BigDecimal("35.001")));
  }

  @Test
  void priceAndCommissionSnapshotsStayHistoricalAndInactiveServicesRemainVisible(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("services.sqlite"))) {
      BeautyService service =
          fixture.maintain.register("Manicure", "Unhas", 45, Money.of("50.00"), 2000);
      UUID snapshotId = fixture.addAttendanceServiceSnapshot(service.id(), 5000, 2000);
      BeautyService repriced =
          fixture.maintain.update(service.id(), "Manicure", "Unhas", 60, Money.of("65.00"), 2500);

      assertEquals(new BigDecimal("65.00"), repriced.price().amount());
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM atendimento_servico WHERE id='"
                  + snapshotId
                  + "' AND preco_unitario_centavos=5000 AND comissao_pontos_base=2000"));
      assertEquals(2, fixture.services.changeHistory(service.id()).size());
      assertTrue(
          fixture.services.changeHistory(service.id()).getFirst().summary().contains("65.00"));
      assertEquals(1, fixture.services.searchPage(query("Unhas", false)).totalItems());
      assertEquals(java.util.List.of("Unhas"), fixture.services.categories());

      fixture.maintain.deactivate(service.id());
      BeautyService inactive = fixture.services.searchPage(query("Unhas", true)).items().getFirst();
      assertFalse(inactive.active());
      assertEquals(3, fixture.services.changeHistory(service.id()).size());
      assertEquals(3, fixture.outboxCount());
      assertEquals(3, fixture.auditCount());
      assertThrows(
          SQLException.class,
          () -> fixture.execute("DELETE FROM servico WHERE id='" + service.id() + "'"));
      assertEquals(
          1,
          fixture.scalar("SELECT COUNT(*) FROM atendimento_servico WHERE id='" + snapshotId + "'"));
    }
  }

  @Test
  void auditFailureRollsBackServiceAndOutboxTogether(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("service-rollback.sqlite"))) {
      BeautyService service =
          fixture.maintain.register("Pedicure", "Unhas", 50, Money.of("45.00"), 2500);
      fixture.execute(
          "CREATE TRIGGER trg_test_reject_service_audit BEFORE INSERT ON evento_auditoria "
              + "WHEN NEW.acao='SERVICO_ATUALIZADO' BEGIN SELECT RAISE(ABORT, 'auditoria rejeitada'); END");

      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.maintain.update(
                  service.id(), "Pedicure", "Unhas", 50, Money.of("55.00"), 2500));
      assertEquals(
          new BigDecimal("45.00"),
          fixture.services.findById(service.id()).orElseThrow().price().amount());
      assertEquals(1, fixture.outboxCount());
      assertEquals(1, fixture.auditCount());
    }
  }

  @Test
  void receptionCanReadServicesButCannotChangePricesOrCommission(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("service-permissions.sqlite"))) {
      SessionContext sessions = new SessionContext();
      sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
      AuthorizationService receptionist = new AuthorizationService(sessions);
      receptionist.require(Permission.SERVICE_READ);
      assertThrows(
          AuthorizationException.class, () -> receptionist.require(Permission.SERVICE_UPDATE));
      assertThrows(
          AuthorizationException.class,
          () ->
              new MaintainServiceUseCase(
                      fixture.unitOfWork,
                      fixture.services,
                      fixture.outbox,
                      receptionist,
                      fixture.audit,
                      STATION)
                  .register("Sem autorização", "Unhas", 45, Money.of("20.00"), 1000));
      assertEquals(0, fixture.outboxCount());
      assertTrue(fixture.authorization.isAllowed(Permission.SERVICE_UPDATE));
    }
  }

  private static BeautyService service(String amount, int duration, int commission, Instant now) {
    return new BeautyService(
        UUID.randomUUID(),
        "Manicure",
        "Unhas",
        duration,
        Money.of(amount),
        commission,
        true,
        now,
        now);
  }

  private ServicePageQuery query(String category, boolean includeInactive) {
    return new ServicePageQuery("", category, includeInactive, ServiceSortField.NAME, true, 0, 25);
  }

  private static final class Fixture implements AutoCloseable {
    private final LocalDatabaseSession database;
    private final SqliteUnitOfWork unitOfWork;
    private final SqliteAuditRepository audit;
    private final SqliteOutboxRepository outbox;
    private final ServiceRepository services;
    private final MaintainServiceUseCase maintain;
    private final AuthorizationService authorization;
    private final UUID adminId;

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
      services = new SqliteServiceRepository(database.connection(), unitOfWork);
      maintain =
          new MaintainServiceUseCase(unitOfWork, services, outbox, authorization, audit, STATION);
    }

    private UUID addAttendanceServiceSnapshot(UUID serviceId, long priceCents, int commission)
        throws Exception {
      UUID clientId = UUID.randomUUID();
      UUID professionalId = UUID.randomUUID();
      UUID attendanceId = UUID.randomUUID();
      UUID attendanceServiceId = UUID.randomUUID();
      String now = "2026-11-01T12:00:00Z";
      try (PreparedStatement client =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,telefone,contato_opcional,observacao_operacional,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'CLI-717171','Cliente Teste',NULL,NULL,NULL,1,?,?)")) {
        client.setString(1, clientId.toString());
        client.setString(2, now);
        client.setString(3, now);
        client.executeUpdate();
      }
      try (PreparedStatement professional =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id,nome,telefone,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Profissional Teste',NULL,1,?,?)")) {
        professional.setString(1, professionalId.toString());
        professional.setString(2, now);
        professional.setString(3, now);
        professional.executeUpdate();
      }
      try (PreparedStatement attendance =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento (id,agendamento_id,cliente_id,profissional_id,estado,subtotal_centavos,desconto_centavos,total_centavos,iniciado_em_utc,concluido_em_utc,criado_por_usuario_id,criado_em_utc) VALUES (?,NULL,?,?,'CONCLUIDO',?,0,?,?,?, ?,?)")) {
        attendance.setString(1, attendanceId.toString());
        attendance.setString(2, clientId.toString());
        attendance.setString(3, professionalId.toString());
        attendance.setLong(4, priceCents);
        attendance.setLong(5, priceCents);
        attendance.setString(6, now);
        attendance.setString(7, now);
        attendance.setString(8, adminId.toString());
        attendance.setString(9, now);
        attendance.executeUpdate();
      }
      try (PreparedStatement line =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento_servico (id,atendimento_id,servico_id,nome_snapshot,quantidade,preco_unitario_centavos,comissao_pontos_base) VALUES (?,?,?,'Manicure',1,?,?)")) {
        line.setString(1, attendanceServiceId.toString());
        line.setString(2, attendanceId.toString());
        line.setString(3, serviceId.toString());
        line.setLong(4, priceCents);
        line.setInt(5, commission);
        line.executeUpdate();
      }
      return attendanceServiceId;
    }

    private int outboxCount() throws Exception {
      return scalar("SELECT COUNT(*) FROM operacao_pendente WHERE entidade='servico'");
    }

    private int auditCount() throws Exception {
      return scalar("SELECT COUNT(*) FROM evento_auditoria WHERE entidade='servico'");
    }

    private int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        return result.next() ? result.getInt(1) : -1;
      }
    }

    private void execute(String sql) throws SQLException {
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
