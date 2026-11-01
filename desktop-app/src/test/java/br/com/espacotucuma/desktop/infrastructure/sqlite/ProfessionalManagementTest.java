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
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.professional.MaintainProfessionalUseCase;
import br.com.espacotucuma.desktop.application.professional.OneOffUnavailability;
import br.com.espacotucuma.desktop.application.professional.Professional;
import br.com.espacotucuma.desktop.application.professional.ProfessionalPageQuery;
import br.com.espacotucuma.desktop.application.professional.ProfessionalSchedule;
import br.com.espacotucuma.desktop.application.professional.WeeklyInterval;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfessionalManagementTest {
  private static final UUID STATION = UUID.fromString("3447a745-199c-462d-af50-9c201d38ded0");

  @Test
  void scheduleRejectsOverlapsAndBlocksBreaksAndOneOffAbsences() {
    WeeklyInterval morning =
        new WeeklyInterval(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON);
    WeeklyInterval afternoon =
        new WeeklyInterval(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(18, 0));
    var absence =
        new OneOffUnavailability(
            LocalDateTime.of(2026, 11, 2, 10, 0), LocalDateTime.of(2026, 11, 2, 11, 0), "Consulta");
    ProfessionalSchedule schedule =
        new ProfessionalSchedule(List.of(morning, afternoon), List.of(absence));

    assertTrue(
        schedule.allows(LocalDateTime.of(2026, 11, 2, 9, 0), LocalDateTime.of(2026, 11, 2, 9, 30)));
    assertFalse(
        schedule.allows(
            LocalDateTime.of(2026, 11, 2, 10, 0), LocalDateTime.of(2026, 11, 2, 10, 30)));
    assertFalse(
        schedule.allows(
            LocalDateTime.of(2026, 11, 2, 12, 0), LocalDateTime.of(2026, 11, 2, 12, 30)));
    assertFalse(
        schedule.allows(LocalDateTime.of(2026, 11, 7, 9, 0), LocalDateTime.of(2026, 11, 7, 10, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProfessionalSchedule(
                List.of(
                    morning,
                    new WeeklyInterval(
                        DayOfWeek.MONDAY, LocalTime.of(11, 30), LocalTime.of(13, 0))),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProfessionalSchedule(
                List.of(morning),
                List.of(
                    absence,
                    new OneOffUnavailability(
                        LocalDateTime.of(2026, 11, 2, 10, 30),
                        LocalDateTime.of(2026, 11, 2, 11, 30),
                        "Sobreposto"))));
  }

  @Test
  void savesScheduleTransactionallyAndInactivationPreservesHistoryAndBlocksAppointments(
      @TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("professionals.sqlite"))) {
      Professional professional =
          fixture.maintain.register("Ana Profissional", "(92) 99999-1122", fixture.schedule());
      assertEquals("92999991122", professional.phone());
      assertEquals(1, fixture.outboxCount());
      assertEquals(1, fixture.auditCount());
      assertTrue(
          fixture.repository.canReceive(
              professional.id(),
              LocalDateTime.of(2026, 11, 2, 9, 0),
              LocalDateTime.of(2026, 11, 2, 9, 30)));
      assertFalse(
          fixture.repository.canReceive(
              professional.id(),
              LocalDateTime.of(2026, 11, 2, 12, 0),
              LocalDateTime.of(2026, 11, 2, 12, 30)));
      assertFalse(
          fixture.repository.canReceive(
              professional.id(),
              LocalDateTime.of(2026, 11, 2, 10, 0),
              LocalDateTime.of(2026, 11, 2, 10, 30)));
      ProfessionalSchedule revisedSchedule =
          new ProfessionalSchedule(
              List.of(
                  new WeeklyInterval(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON),
                  new WeeklyInterval(DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(18, 0))),
              fixture.schedule().unavailabilities());
      fixture.maintain.update(
          professional.id(), professional.name(), professional.phone(), revisedSchedule);
      assertEquals(2, fixture.outboxCount());
      assertEquals(2, fixture.auditCount());
      assertTrue(
          fixture.repository.canReceive(
              professional.id(),
              LocalDateTime.of(2026, 11, 2, 14, 0),
              LocalDateTime.of(2026, 11, 2, 14, 30)));
      assertFalse(
          fixture.repository.canReceive(
              professional.id(),
              LocalDateTime.of(2026, 11, 2, 13, 0),
              LocalDateTime.of(2026, 11, 2, 13, 30)));
      UUID appointmentId = fixture.addClientAndHistory(professional.id());
      fixture.maintain.deactivate(professional.id());

      assertFalse(
          fixture.repository.canReceive(
              professional.id(),
              LocalDateTime.of(2026, 11, 2, 9, 0),
              LocalDateTime.of(2026, 11, 2, 9, 30)));
      assertTrue(fixture.repository.findActiveForScheduling().isEmpty());
      assertEquals(
          0,
          fixture
              .repository
              .searchPage(new ProfessionalPageQuery("Ana", false, 0, 25))
              .totalItems());
      Professional retained =
          fixture
              .repository
              .searchPage(new ProfessionalPageQuery("Ana", true, 0, 25))
              .items()
              .getFirst();
      assertFalse(retained.active());
      assertEquals(2, retained.schedule().weeklyIntervals().size());
      assertEquals(1, retained.schedule().unavailabilities().size());
      assertEquals(3, fixture.outboxCount());
      assertEquals(3, fixture.auditCount());
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM atendimento WHERE profissional_id='"
                  + professional.id()
                  + "'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM comissao WHERE profissional_id='" + professional.id() + "'"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM agendamento"));

      assertThrows(SQLException.class, fixture::insertAppointmentForInactiveProfessional);
      assertThrows(SQLException.class, () -> fixture.rescheduleAppointment(appointmentId));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM agendamento"));
      assertEquals(
          "2026-11-02T13:00:00Z",
          fixture.scalarString(
              "SELECT inicio_em_utc FROM agendamento WHERE id='" + appointmentId + "'"));
      assertEquals(
          1,
          fixture.scalar("SELECT COUNT(*) FROM profissional WHERE id='" + professional.id() + "'"));
    }
  }

  @Test
  void receptionCanReadProfessionalsButCannotEditSchedulesOrCommissions(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("professional-permissions.sqlite"))) {
      SessionContext sessions = new SessionContext();
      sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
      AuthorizationService receptionist = new AuthorizationService(sessions);
      receptionist.require(Permission.PROFESSIONAL_READ);
      assertThrows(
          AuthorizationException.class, () -> receptionist.require(Permission.PROFESSIONAL_UPDATE));
      assertThrows(
          AuthorizationException.class,
          () -> receptionist.require(Permission.PROFESSIONAL_COMMISSION_MANAGE));
      MaintainProfessionalUseCase receptionistUseCase =
          new MaintainProfessionalUseCase(
              fixture.unitOfWork,
              fixture.repository,
              fixture.outbox,
              receptionist,
              fixture.audit,
              STATION);
      assertThrows(
          AuthorizationException.class,
          () -> receptionistUseCase.register("Sem permissão", null, fixture.schedule()));
      assertEquals(0, fixture.outboxCount());
      assertTrue(fixture.authorization.isAllowed(Permission.PROFESSIONAL_COMMISSION_MANAGE));
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final LocalDatabaseSession database;
    private final SqliteUnitOfWork unitOfWork;
    private final ProfessionalRepository repository;
    private final SqliteOutboxRepository outbox;
    private final MaintainProfessionalUseCase maintain;
    private final AuthorizationService authorization;
    private final SqliteAuditRepository audit;
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
                  "SELECT id, nome, login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
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
      repository = new SqliteProfessionalRepository(database.connection(), unitOfWork);
      outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      maintain =
          new MaintainProfessionalUseCase(
              unitOfWork, repository, outbox, authorization, audit, STATION);
    }

    private ProfessionalSchedule schedule() {
      return new ProfessionalSchedule(
          List.of(
              new WeeklyInterval(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON),
              new WeeklyInterval(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(18, 0))),
          List.of(
              new OneOffUnavailability(
                  LocalDateTime.of(2026, 11, 2, 10, 0),
                  LocalDateTime.of(2026, 11, 2, 11, 0),
                  "Consulta")));
    }

    private UUID addClientAndHistory(UUID professionalId) throws Exception {
      UUID clientId = UUID.randomUUID();
      UUID serviceId = UUID.randomUUID();
      UUID attendanceId = UUID.randomUUID();
      UUID attendanceServiceId = UUID.randomUUID();
      UUID appointmentId = UUID.randomUUID();
      String now = "2026-11-01T12:00:00Z";
      try (PreparedStatement client =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,telefone,contato_opcional,observacao_operacional,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'CLI-989898','Cliente Teste',NULL,NULL,NULL,1,?,?)")) {
        client.setString(1, clientId.toString());
        client.setString(2, now);
        client.setString(3, now);
        client.executeUpdate();
      }
      try (PreparedStatement service =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO servico (id,nome,categoria,duracao_minutos,preco_centavos,comissao_pontos_base,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Manicure','Unhas',30,5000,1000,1,?,?)")) {
        service.setString(1, serviceId.toString());
        service.setString(2, now);
        service.setString(3, now);
        service.executeUpdate();
      }
      try (PreparedStatement attendance =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento (id,agendamento_id,cliente_id,profissional_id,estado,subtotal_centavos,desconto_centavos,total_centavos,iniciado_em_utc,concluido_em_utc,criado_por_usuario_id,criado_em_utc) VALUES (?,NULL,?,?,'CONCLUIDO',5000,0,5000,?,?,?,?)")) {
        attendance.setString(1, attendanceId.toString());
        attendance.setString(2, clientId.toString());
        attendance.setString(3, professionalId.toString());
        attendance.setString(4, now);
        attendance.setString(5, now);
        attendance.setString(6, adminId.toString());
        attendance.setString(7, now);
        attendance.executeUpdate();
      }
      try (PreparedStatement line =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento_servico (id,atendimento_id,servico_id,nome_snapshot,quantidade,preco_unitario_centavos,comissao_pontos_base) VALUES (?,?,?,'Manicure',1,5000,1000)")) {
        line.setString(1, attendanceServiceId.toString());
        line.setString(2, attendanceId.toString());
        line.setString(3, serviceId.toString());
        line.executeUpdate();
      }
      try (PreparedStatement commission =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO comissao (id,atendimento_servico_id,profissional_id,base_centavos,percentual_pontos_base,valor_centavos,estado,apurada_em_utc) VALUES (?,?,?,5000,1000,500,'PENDENTE',?)")) {
        commission.setString(1, UUID.randomUUID().toString());
        commission.setString(2, attendanceServiceId.toString());
        commission.setString(3, professionalId.toString());
        commission.setString(4, now);
        commission.executeUpdate();
      }
      try (PreparedStatement appointment =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO agendamento (id,cliente_id,profissional_id,servico_id,inicio_em_utc,fim_em_utc,estado,observacao,criado_por_usuario_id,criado_em_utc,atualizado_em_utc) VALUES (?,?,?,?,? ,?,'AGENDADO',NULL,?,?,?)")) {
        appointment.setString(1, appointmentId.toString());
        appointment.setString(2, clientId.toString());
        appointment.setString(3, professionalId.toString());
        appointment.setString(4, serviceId.toString());
        appointment.setString(5, "2026-11-02T13:00:00Z");
        appointment.setString(6, "2026-11-02T13:30:00Z");
        appointment.setString(7, adminId.toString());
        appointment.setString(8, now);
        appointment.setString(9, now);
        appointment.executeUpdate();
      }
      return appointmentId;
    }

    private void insertAppointmentForInactiveProfessional() throws SQLException {
      try (PreparedStatement appointment =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO agendamento (id,cliente_id,profissional_id,servico_id,inicio_em_utc,fim_em_utc,estado,observacao,criado_por_usuario_id,criado_em_utc,atualizado_em_utc) "
                      + "SELECT ?, cliente.id, profissional.id, servico.id, '2026-11-02T13:00:00Z', '2026-11-02T13:30:00Z', 'AGENDADO', NULL, ?, '2026-11-01T12:00:00Z', '2026-11-01T12:00:00Z' "
                      + "FROM cliente, profissional, servico WHERE profissional.nome = 'Ana Profissional'")) {
        appointment.setString(1, UUID.randomUUID().toString());
        appointment.setString(2, adminId.toString());
        appointment.executeUpdate();
      }
    }

    private void rescheduleAppointment(UUID appointmentId) throws SQLException {
      try (PreparedStatement appointment =
          database
              .connection()
              .prepareStatement(
                  "UPDATE agendamento SET inicio_em_utc='2026-11-02T16:00:00Z', fim_em_utc='2026-11-02T16:30:00Z' WHERE id=?")) {
        appointment.setString(1, appointmentId.toString());
        appointment.executeUpdate();
      }
    }

    private int outboxCount() throws Exception {
      return scalar("SELECT COUNT(*) FROM operacao_pendente WHERE entidade='profissional'");
    }

    private int auditCount() throws Exception {
      return scalar("SELECT COUNT(*) FROM evento_auditoria WHERE entidade='profissional'");
    }

    private int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        return result.next() ? result.getInt(1) : -1;
      }
    }

    private String scalarString(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet result = statement.executeQuery(sql)) {
        return result.next() ? result.getString(1) : null;
      }
    }

    @Override
    public void close() throws Exception {
      database.close();
    }
  }
}
