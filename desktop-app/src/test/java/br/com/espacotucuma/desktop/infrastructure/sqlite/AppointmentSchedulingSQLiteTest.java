package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationException;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.CreateFirstAdministratorUseCase;
import br.com.espacotucuma.desktop.application.identity.InitialAdministratorSetup;
import br.com.espacotucuma.desktop.application.identity.SessionContext;
import br.com.espacotucuma.desktop.application.identity.UserRole;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.scheduling.AppointmentStatus;
import br.com.espacotucuma.desktop.application.scheduling.MaintainAppointmentUseCase;
import br.com.espacotucuma.desktop.infrastructure.security.Pbkdf2PasswordHasher;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppointmentSchedulingSQLiteTest {
  private static final UUID STATION = UUID.fromString("b8638bc7-63e2-4585-b990-45a8d7792ea1");
  private static final Instant NOW = Instant.parse("2026-11-02T13:00:00Z");
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

  @Test
  void usesServiceDurationAndAllowsTouchingBoundariesButRejectsOverlap(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-slots.sqlite"))) {
      var first = fixture.createAt(9, 0);
      var next = fixture.createAt(9, 30);
      assertEquals(LocalTime.of(9, 30), first.endsAt().toLocalTime());
      assertEquals(LocalTime.of(10, 0), next.endsAt().toLocalTime());
      assertThrows(IllegalStateException.class, () -> fixture.createAt(9, 29));
      assertEquals(2, fixture.appointments.findForDay(MONDAY, fixture.professionalId).size());
      assertEquals(
          2,
          fixture.scalar("SELECT COUNT(*) FROM evento_auditoria WHERE acao='AGENDAMENTO_CRIADO'"));
      assertEquals(
          2, fixture.scalar("SELECT COUNT(*) FROM operacao_pendente WHERE entidade='agendamento'"));
    }
  }

  @Test
  void overlappingSlotsAreScopedToTheProfessional(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-professional-scope.sqlite"))) {
      fixture.createAt(9, 0);
      var other = fixture.createAt(fixture.otherProfessionalId, 9, 0);
      assertEquals(fixture.otherProfessionalId, other.professionalId());
      assertEquals(2, fixture.scalar("SELECT COUNT(*) FROM agendamento WHERE estado='AGENDADO'"));
    }
  }

  @Test
  void longerServiceDurationIsUsedWhenCheckingTheNextSlot(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-service-duration.sqlite"))) {
      var longService = fixture.createWithLongServiceAt(9, 0);
      assertEquals(at(10, 0), longService.endsAt());
      assertThrows(IllegalStateException.class, () -> fixture.createAt(9, 45));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM agendamento"));
    }
  }

  @Test
  void rescheduleFailurePreservesOldTimeAndCancellationReleasesSlot(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-reschedule.sqlite"))) {
      var blocker = fixture.createAt(9, 0);
      var toMove = fixture.createAt(10, 0);
      assertThrows(
          IllegalStateException.class,
          () -> fixture.maintain.reschedule(toMove.id(), fixture.professionalId, at(9, 15)));
      assertEquals(at(10, 0), fixture.maintain.findById(toMove.id()).startsAt());

      fixture.maintain.cancel(blocker.id(), "Cliente solicitou cancelamento");
      assertEquals(AppointmentStatus.CANCELADO, fixture.maintain.findById(blocker.id()).status());
      var replacement = fixture.createAt(9, 0);
      assertEquals(at(9, 0), replacement.startsAt());
    }
  }

  @Test
  void checksShiftAbsenceAndMidnightBoundaries(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-availability.sqlite"))) {
      fixture.insertAbsence(at(11, 0), at(12, 0));
      assertEquals(at(11, 0), fixture.createAt(10, 30).endsAt());
      assertThrows(IllegalStateException.class, () -> fixture.createAt(11, 0));
      assertEquals(at(12, 30), fixture.createAt(12, 0).endsAt());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.maintain.create(
                  fixture.clientId,
                  fixture.professionalId,
                  fixture.serviceId,
                  LocalDateTime.of(MONDAY, LocalTime.of(23, 45)),
                  "Virada de dia"));
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.maintain.create(
                  fixture.clientId,
                  fixture.professionalId,
                  fixture.serviceId,
                  LocalDateTime.of(MONDAY, LocalTime.of(8, 30)),
                  "Antes do expediente"));
      assertEquals(at(17, 0), fixture.createAt(16, 30).endsAt());
      assertThrows(IllegalStateException.class, () -> fixture.createAt(17, 0));
      LocalDateTime nextDay = LocalDateTime.of(MONDAY.plusDays(1), LocalTime.of(9, 0));
      assertEquals(
          nextDay,
          fixture
              .maintain
              .create(
                  fixture.clientId, fixture.otherProfessionalId, fixture.serviceId, nextDay, null)
              .startsAt());
    }
  }

  @Test
  void inactiveProfessionalCannotBeScheduled(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-inactive-professional.sqlite"))) {
      fixture.execute("UPDATE profissional SET ativo=0 WHERE id='" + fixture.professionalId + "'");
      assertThrows(IllegalStateException.class, () -> fixture.createAt(9, 0));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM agendamento"));
    }
  }

  @Test
  void auditFailureRollsBackAppointmentAndOutboxTogether(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-rollback.sqlite"))) {
      fixture.execute(
          "CREATE TRIGGER trg_test_reject_appointment_audit BEFORE INSERT ON evento_auditoria "
              + "WHEN NEW.acao='AGENDAMENTO_CRIADO' BEGIN SELECT RAISE(ABORT, 'auditoria rejeitada'); END");
      assertThrows(IllegalStateException.class, () -> fixture.createAt(9, 0));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM agendamento"));
      assertEquals(
          0, fixture.scalar("SELECT COUNT(*) FROM operacao_pendente WHERE entidade='agendamento'"));
    }
  }

  @Test
  void confirmingAppointmentUpdatesStateAuditAndOutboxTogether(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-confirm.sqlite"))) {
      var created = fixture.createAt(9, 0);
      var confirmed = fixture.maintain.confirm(created.id());
      assertEquals(AppointmentStatus.CONFIRMADO, confirmed.status());
      assertEquals(AppointmentStatus.CONFIRMADO, fixture.maintain.findById(created.id()).status());
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='AGENDAMENTO_CONFIRMADO'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='agendamento' AND tipo_operacao='AGENDAMENTO_CONFIRMADO'"));
      assertThrows(IllegalStateException.class, () -> fixture.maintain.confirm(created.id()));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='AGENDAMENTO_CONFIRMADO'"));
    }
  }

  @Test
  void concurrentCreatesOnSingleConnectionCannotClaimSameSlot(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-concurrent.sqlite"))) {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch start = new CountDownLatch(1);
      try (var executor = Executors.newFixedThreadPool(2)) {
        var first = executor.submit(() -> fixture.createAfterBarrier(ready, start));
        var second = executor.submit(() -> fixture.createAfterBarrier(ready, start));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        int created =
            (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
        assertEquals(1, created);
        assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM agendamento WHERE estado='AGENDADO'"));
      }
    }
  }

  @Test
  void onlyAdministratorCanRecordAnOverlapException(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-override.sqlite"))) {
      fixture.createAt(9, 0);
      assertThrows(IllegalStateException.class, () -> fixture.createAt(9, 0));
      var exception =
          fixture.maintain.create(
              fixture.clientId,
              fixture.professionalId,
              fixture.serviceId,
              at(9, 0),
              null,
              true,
              "Encaixe aprovado pela administração");
      assertTrue(fixture.maintain.findById(exception.id()).id().equals(exception.id()));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='AGENDAMENTO_CRIADO' AND motivo IS NOT NULL"));

      fixture.useReceptionRole();
      assertThrows(
          AuthorizationException.class,
          () ->
              fixture.maintain.create(
                  fixture.clientId,
                  fixture.professionalId,
                  fixture.serviceId,
                  at(9, 0),
                  null,
                  true,
                  "Recepção tentou autorizar encaixe"));
    }
  }

  private static LocalDateTime at(int hour, int minute) {
    return LocalDateTime.of(MONDAY, LocalTime.of(hour, minute));
  }

  private static final class Fixture implements AutoCloseable {
    final LocalDatabaseSession database;
    final SqliteUnitOfWork unitOfWork;
    final SqliteAuditRepository audit;
    final SqliteOutboxRepository outbox;
    final SqliteClientRepository clients;
    final ProfessionalRepository professionals;
    final ServiceRepository services;
    final SqliteAppointmentRepository appointments;
    final MaintainAppointmentUseCase maintain;
    final SessionContext sessions;
    final AuthorizationService authorization;
    final UUID adminId;
    final UUID clientId = UUID.randomUUID();
    final UUID professionalId = UUID.randomUUID();
    final UUID otherProfessionalId = UUID.randomUUID();
    final UUID serviceId = UUID.randomUUID();
    final UUID longServiceId = UUID.randomUUID();

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
                  "Espaço Tucumã",
                  "Admin Agenda",
                  "admin",
                  "SenhaTesteOffline2026!".toCharArray()));
      try (Statement statement = database.connection().createStatement();
          ResultSet row =
              statement.executeQuery(
                  "SELECT id,nome,login FROM usuario WHERE perfil='ADMINISTRADOR'")) {
        row.next();
        adminId = UUID.fromString(row.getString("id"));
        sessions = new SessionContext();
        sessions.establish(
            new AuthenticatedUser(
                adminId, row.getString("nome"), row.getString("login"), UserRole.ADMINISTRADOR));
      }
      authorization = new AuthorizationService(sessions);
      outbox = new SqliteOutboxRepository(database.connection(), unitOfWork);
      clients = new SqliteClientRepository(database.connection(), unitOfWork);
      professionals = new SqliteProfessionalRepository(database.connection(), unitOfWork);
      services = new SqliteServiceRepository(database.connection(), unitOfWork);
      appointments = new SqliteAppointmentRepository(database.connection(), unitOfWork);
      insertFixtures();
      maintain =
          new MaintainAppointmentUseCase(
              unitOfWork,
              appointments,
              clients,
              professionals,
              services,
              outbox,
              authorization,
              audit,
              STATION,
              Clock.fixed(NOW, ZoneId.of("America/Manaus")));
    }

    private void insertFixtures() throws Exception {
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO cliente (id,codigo_interno,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'CLI-994001','Cliente Agenda',1,?,?)")) {
        insert.setString(1, clientId.toString());
        insert.setString(2, NOW.toString());
        insert.setString(3, NOW.toString());
        insert.executeUpdate();
      }
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Profissional Agenda',1,?,?)")) {
        insert.setString(1, professionalId.toString());
        insert.setString(2, NOW.toString());
        insert.setString(3, NOW.toString());
        insert.executeUpdate();
      }
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO profissional (id,nome,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Outra Profissional',1,?,?)")) {
        insert.setString(1, otherProfessionalId.toString());
        insert.setString(2, NOW.toString());
        insert.setString(3, NOW.toString());
        insert.executeUpdate();
      }
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO disponibilidade_profissional (id,profissional_id,dia_semana,inicio_local,fim_local,ativo) VALUES (?,?,?,'09:00','17:00',1)")) {
        for (int day = 1; day <= 5; day++) {
          insert.setString(1, UUID.randomUUID().toString());
          insert.setString(2, professionalId.toString());
          insert.setInt(3, day);
          insert.addBatch();
          insert.setString(1, UUID.randomUUID().toString());
          insert.setString(2, otherProfessionalId.toString());
          insert.setInt(3, day);
          insert.addBatch();
        }
        insert.executeBatch();
      }
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO servico (id,nome,categoria,duracao_minutos,preco_centavos,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Serviço Agenda','Geral',30,2500,1,?,?)")) {
        insert.setString(1, serviceId.toString());
        insert.setString(2, NOW.toString());
        insert.setString(3, NOW.toString());
        insert.executeUpdate();
      }
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO servico (id,nome,categoria,duracao_minutos,preco_centavos,ativo,criado_em_utc,atualizado_em_utc) VALUES (?,'Serviço Longo','Geral',60,5000,1,?,?)")) {
        insert.setString(1, longServiceId.toString());
        insert.setString(2, NOW.toString());
        insert.setString(3, NOW.toString());
        insert.executeUpdate();
      }
    }

    br.com.espacotucuma.desktop.application.scheduling.Appointment createAt(int hour, int minute) {
      return maintain.create(
          clientId, professionalId, serviceId, at(hour, minute), "Teste de agenda");
    }

    br.com.espacotucuma.desktop.application.scheduling.Appointment createAt(
        UUID otherProfessional, int hour, int minute) {
      return maintain.create(clientId, otherProfessional, serviceId, at(hour, minute), null);
    }

    br.com.espacotucuma.desktop.application.scheduling.Appointment createWithLongServiceAt(
        int hour, int minute) {
      return maintain.create(clientId, professionalId, longServiceId, at(hour, minute), null);
    }

    boolean createAfterBarrier(CountDownLatch ready, CountDownLatch start) {
      ready.countDown();
      try {
        if (!start.await(5, TimeUnit.SECONDS))
          throw new IllegalStateException("Barreira do teste expirou.");
        createAt(9, 0);
        return true;
      } catch (IllegalStateException expectedConflict) {
        return false;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }

    void insertAbsence(LocalDateTime start, LocalDateTime end) throws Exception {
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO indisponibilidade_profissional (id,profissional_id,inicio_local,fim_local,motivo,criado_em_utc) VALUES (?,?,?,?,?,?)")) {
        insert.setString(1, UUID.randomUUID().toString());
        insert.setString(2, professionalId.toString());
        insert.setString(3, start.toString());
        insert.setString(4, end.toString());
        insert.setString(5, "Bloqueio de teste");
        insert.setString(6, NOW.toString());
        insert.executeUpdate();
      }
    }

    void useReceptionRole() {
      sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
    }

    int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet row = statement.executeQuery(sql)) {
        return row.next() ? row.getInt(1) : -1;
      }
    }

    void execute(String sql) throws Exception {
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
