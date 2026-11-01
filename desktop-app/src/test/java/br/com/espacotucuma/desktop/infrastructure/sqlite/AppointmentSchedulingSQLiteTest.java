package br.com.espacotucuma.desktop.infrastructure.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.espacotucuma.desktop.application.attendance.AttendanceStatus;
import br.com.espacotucuma.desktop.application.attendance.MaintainAttendanceUseCase;
import br.com.espacotucuma.desktop.application.finance.CashMovementType;
import br.com.espacotucuma.desktop.application.finance.ManageCashTurnUseCase;
import br.com.espacotucuma.desktop.application.finance.PaymentMethod;
import br.com.espacotucuma.desktop.application.finance.RefundPaymentUseCase;
import br.com.espacotucuma.desktop.application.finance.RegisterPaymentUseCase;
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
import br.com.espacotucuma.desktop.domain.money.Money;
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
import java.util.EnumMap;
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
  void noShowIsTerminalAndRecordedOnceWithItsReason(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-no-show.sqlite"))) {
      var created = fixture.createAt(9, 0);
      var absent = fixture.maintain.markNoShow(created.id(), "Cliente não compareceu");
      assertEquals(AppointmentStatus.NAO_COMPARECEU, absent.status());
      assertThrows(
          IllegalStateException.class,
          () -> fixture.maintain.markNoShow(created.id(), "Repetição de teste"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='AGENDAMENTO_NAO_COMPARECEU' AND motivo='Cliente não compareceu'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='agendamento' AND tipo_operacao='AGENDAMENTO_NAO_COMPARECEU'"));
    }
  }

  @Test
  void completionRequiresLinkedCompletedAttendanceAndDoesNotDuplicateEvents(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("schedule-complete.sqlite"))) {
      var appointment = fixture.createAt(9, 0);
      fixture.maintain.confirm(appointment.id());
      fixture.maintain.startAttendance(appointment.id());
      UUID attendanceId = UUID.randomUUID();
      fixture.insertAttendance(appointment.id(), attendanceId, "EM_ANDAMENTO");

      assertThrows(
          IllegalStateException.class,
          () -> fixture.maintain.complete(appointment.id(), attendanceId));
      assertEquals(
          AppointmentStatus.EM_ATENDIMENTO, fixture.maintain.findById(appointment.id()).status());

      fixture.execute(
          "UPDATE atendimento SET estado='CONCLUIDO',concluido_em_utc='2026-11-02T13:40:00Z' WHERE id='"
              + attendanceId
              + "'");
      var completed = fixture.maintain.complete(appointment.id(), attendanceId);
      assertEquals(AppointmentStatus.CONCLUIDO, completed.status());
      assertThrows(
          IllegalStateException.class,
          () -> fixture.maintain.complete(appointment.id(), attendanceId));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='AGENDAMENTO_CONCLUIDO'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='agendamento' AND tipo_operacao='AGENDAMENTO_CONCLUIDO'"));
    }
  }

  @Test
  void attendanceSnapshotsPriceAndCompletesAtomicallyWithoutCreatingPayment(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("attendance-workflow.sqlite"))) {
      var appointment = fixture.createAt(9, 0);
      fixture.maintain.confirm(appointment.id());

      var attendance = fixture.attendance.startFromAppointment(appointment.id());
      assertEquals(
          AppointmentStatus.EM_ATENDIMENTO, fixture.maintain.findById(appointment.id()).status());
      assertEquals(attendance.id(), fixture.attendance.startFromAppointment(appointment.id()).id());
      assertThrows(IllegalStateException.class, () -> fixture.attendance.complete(attendance.id()));

      var serviceLine = fixture.attendance.addService(attendance.id(), fixture.serviceId, 1);
      assertEquals(2500, serviceLine.unitPrice().amount().movePointRight(2).intValueExact());
      assertEquals(
          serviceLine.id(),
          fixture.attendance.addService(attendance.id(), fixture.serviceId, 1).id());
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM atendimento_servico"));
      fixture.execute(
          "UPDATE servico SET preco_centavos=4000 WHERE id='" + fixture.serviceId + "'");
      assertEquals(2500, fixture.scalar("SELECT preco_unitario_centavos FROM atendimento_servico"));
      assertEquals(0, fixture.scalar("SELECT comissao_pontos_base FROM atendimento_servico"));

      fixture.useReceptionRole();
      assertThrows(
          AuthorizationException.class,
          () ->
              fixture.attendance.applyDiscount(
                  attendance.id(), Money.of("5.00"), "Ajuste aprovado"));
      fixture.sessions.establish(
          new AuthenticatedUser(fixture.adminId, "Admin Agenda", "admin", UserRole.ADMINISTRADOR));
      var discounted =
          fixture.attendance.applyDiscount(attendance.id(), Money.of("5.00"), "Ajuste aprovado");
      assertEquals(2500, discounted.subtotal().amount().movePointRight(2).intValueExact());
      assertEquals(2000, discounted.total().amount().movePointRight(2).intValueExact());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.attendance.applyDiscount(
                  attendance.id(), Money.of("25.01"), "Acima do subtotal"));

      fixture.execute(
          "CREATE TRIGGER trg_test_reject_attendance_audit BEFORE INSERT ON evento_auditoria "
              + "WHEN NEW.acao='ATENDIMENTO_CONCLUIDO' BEGIN SELECT RAISE(ABORT, 'auditoria rejeitada'); END");
      assertThrows(IllegalStateException.class, () -> fixture.attendance.complete(attendance.id()));
      assertEquals(
          AttendanceStatus.EM_ANDAMENTO, fixture.attendance.findById(attendance.id()).status());
      assertEquals(
          AppointmentStatus.EM_ATENDIMENTO, fixture.maintain.findById(appointment.id()).status());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM recebimento"));
      assertEquals(
          0,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE tipo_operacao='ATENDIMENTO_CONCLUIDO'"));

      fixture.execute("DROP TRIGGER trg_test_reject_attendance_audit");
      var completed = fixture.attendance.complete(attendance.id());
      assertEquals(AttendanceStatus.CONCLUIDO, completed.status());
      assertEquals(
          AppointmentStatus.CONCLUIDO, fixture.maintain.findById(appointment.id()).status());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM recebimento"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM comissao"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='atendimento' AND tipo_operacao='ATENDIMENTO_CONCLUIDO'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='agendamento' AND tipo_operacao='AGENDAMENTO_CONCLUIDO' AND payload_json LIKE '%inicioUtc%' AND payload_json LIKE '%excecaoConflito%'"));
      assertThrows(IllegalStateException.class, () -> fixture.attendance.complete(attendance.id()));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='ATENDIMENTO_CONCLUIDO'"));
    }
  }

  @Test
  void partialPaymentsAreExactIdempotentAndAtomicWithCashMovement(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("partial-payments.sqlite"))) {
      var appointment = fixture.createAt(9, 0);
      fixture.maintain.confirm(appointment.id());
      var attendance = fixture.attendance.startFromAppointment(appointment.id());
      fixture.attendance.addService(attendance.id(), fixture.serviceId, 1);
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.payments.register(
                  attendance.id(),
                  PaymentMethod.PIX,
                  Money.of("8.33"),
                  "payment-before-completion"));
      fixture.attendance.complete(attendance.id());

      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.payments.register(
                  attendance.id(), PaymentMethod.PIX, Money.of("8.33"), "payment-no-cash"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM recebimento"));
      fixture.openCashTurn();
      fixture.execute(
          "CREATE TRIGGER trg_test_reject_cash_movement BEFORE INSERT ON movimento_caixa "
              + "WHEN NEW.tipo='ENTRADA' BEGIN SELECT RAISE(ABORT, 'caixa rejeitou entrada'); END");
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.payments.register(
                  attendance.id(), PaymentMethod.PIX, Money.of("8.33"), "payment-first"));
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM recebimento"));
      assertEquals(
          0, fixture.scalar("SELECT COUNT(*) FROM operacao_pendente WHERE entidade='recebimento'"));
      fixture.execute("DROP TRIGGER trg_test_reject_cash_movement");

      var first =
          fixture.payments.register(
              attendance.id(), PaymentMethod.PIX, Money.of("8.33"), "payment-first");
      var repeated =
          fixture.payments.register(
              attendance.id(), PaymentMethod.PIX, Money.of("8.33"), "payment-first");
      assertEquals(first.id(), repeated.id());
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.payments.register(
                  attendance.id(), PaymentMethod.PIX, Money.of("8.34"), "payment-first"));
      assertEquals(833, fixture.scalar("SELECT valor_centavos FROM recebimento"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM movimento_caixa WHERE tipo='ENTRADA'"));
      assertEquals(
          833,
          fixture
              .payments
              .summary(attendance.id())
              .received()
              .amount()
              .movePointRight(2)
              .intValueExact());
      assertEquals(
          1667,
          fixture
              .payments
              .summary(attendance.id())
              .outstanding()
              .amount()
              .movePointRight(2)
              .intValueExact());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.payments.register(
                  attendance.id(), PaymentMethod.DINHEIRO, Money.of("16.68"), "payment-too-much"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM recebimento"));

      fixture.payments.register(
          attendance.id(), PaymentMethod.DINHEIRO, Money.of("8.33"), "payment-second");
      fixture.payments.register(
          attendance.id(), PaymentMethod.CARTAO, Money.of("8.34"), "payment-third");
      var settled = fixture.payments.summary(attendance.id());
      assertTrue(settled.settled());
      assertEquals(2500, settled.received().amount().movePointRight(2).intValueExact());
      assertEquals(0, settled.outstanding().amount().movePointRight(2).intValueExact());
      assertEquals(3, fixture.payments.findForAttendance(attendance.id()).size());
      assertEquals(3, fixture.scalar("SELECT COUNT(*) FROM recebimento"));
      assertEquals(3, fixture.scalar("SELECT COUNT(*) FROM movimento_caixa WHERE tipo='ENTRADA'"));
      assertEquals(
          3,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='recebimento' AND tipo_operacao='RECEBIMENTO_REGISTRADO'"));
      assertEquals(
          3,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='RECEBIMENTO_REGISTRADO'"));
    }
  }

  @Test
  void refundsAreAuthorizedIdempotentTraceableAndReduceReceivableAndCashOnce(@TempDir Path temp)
      throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("refunds.sqlite"))) {
      var appointment = fixture.createAt(9, 0);
      fixture.maintain.confirm(appointment.id());
      var attendance = fixture.attendance.startFromAppointment(appointment.id());
      fixture.attendance.addService(attendance.id(), fixture.serviceId, 1);
      fixture.attendance.complete(attendance.id());
      fixture.openCashTurn();
      var payment =
          fixture.payments.register(
              attendance.id(), PaymentMethod.DINHEIRO, Money.of("25.00"), "refund-payment");
      int commissionsBefore = fixture.scalar("SELECT COUNT(*) FROM comissao");
      assertEquals(
          2_500,
          fixture
              .cashTurnRepository
              .expectedTotals(fixture.cashTurns.currentTurn().orElseThrow().id())
              .get(PaymentMethod.DINHEIRO));

      var refund = fixture.refunds.refund(payment.id(), 1_000, "Cobrança duplicada", "refund-once");
      var replay = fixture.refunds.refund(payment.id(), 1_000, "Cobrança duplicada", "refund-once");
      assertEquals(refund.id(), replay.id());
      assertThrows(
          IllegalStateException.class,
          () -> fixture.refunds.refund(payment.id(), 500, "Outro motivo", "refund-once"));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.refunds.refund(payment.id(), 1_501, "Excede recebido", "refund-too-much"));

      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM recebimento"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM estorno_recebimento"));
      assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM movimento_caixa WHERE tipo='ESTORNO'"));
      assertEquals(
          1_500,
          fixture
              .payments
              .summary(attendance.id())
              .received()
              .amount()
              .movePointRight(2)
              .intValueExact());
      assertEquals(
          1_500,
          fixture
              .cashTurnRepository
              .expectedTotals(fixture.cashTurns.currentTurn().orElseThrow().id())
              .get(PaymentMethod.DINHEIRO));
      assertEquals(commissionsBefore, fixture.scalar("SELECT COUNT(*) FROM comissao"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='RECEBIMENTO_ESTORNADO'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='estorno_recebimento'"));
      fixture.refunds.refund(payment.id(), 1_500, "Cancelamento do restante", "refund-rest");
      assertEquals(
          0,
          fixture
              .payments
              .summary(attendance.id())
              .received()
              .amount()
              .movePointRight(2)
              .intValueExact());
      assertEquals(
          "ESTORNADO",
          fixture.string("SELECT estado FROM recebimento WHERE id='" + payment.id() + "'"));
      assertEquals(
          0,
          fixture
              .cashTurnRepository
              .expectedTotals(fixture.cashTurns.currentTurn().orElseThrow().id())
              .get(PaymentMethod.DINHEIRO));
      assertThrows(
          java.sql.SQLException.class,
          () -> fixture.execute("DELETE FROM recebimento WHERE id='" + payment.id() + "'"));

      fixture.sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
      assertThrows(
          br.com.espacotucuma.desktop.application.identity.AuthorizationException.class,
          () ->
              fixture.refunds.refund(payment.id(), 100, "Sem autorização", "refund-unauthorized"));
    }
  }

  @Test
  void opensRejectsDuplicateReconcilesAndAuditsCashTurn(@TempDir Path temp) throws Exception {
    try (Fixture fixture = new Fixture(temp.resolve("cash-turn.sqlite"))) {
      var opened = fixture.cashTurns.open(1_000);
      assertThrows(IllegalStateException.class, () -> fixture.cashTurns.open(500));
      fixture.cashTurns.recordMovement(CashMovementType.SUPRIMENTO, 500, "Troco recebido");
      fixture.cashTurns.recordMovement(CashMovementType.SANGRIA, 200, "Depósito bancário");
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.cashTurns.recordMovement(CashMovementType.SAIDA, 1_301, "Despesa"));

      EnumMap<PaymentMethod, Long> counted = new EnumMap<>(PaymentMethod.class);
      counted.put(PaymentMethod.DINHEIRO, 1_299L);
      counted.put(PaymentMethod.PIX, 0L);
      counted.put(PaymentMethod.CARTAO, 0L);
      assertThrows(IllegalArgumentException.class, () -> fixture.cashTurns.close(counted, ""));
      assertTrue(fixture.cashTurns.currentTurn().orElseThrow().open());
      fixture.execute(
          "CREATE TRIGGER trg_test_reject_cash_close_audit BEFORE INSERT ON evento_auditoria "
              + "WHEN NEW.acao='CAIXA_FECHADO' BEGIN SELECT RAISE(ABORT, 'auditoria rejeitada'); END");
      assertThrows(
          IllegalStateException.class,
          () -> fixture.cashTurns.close(counted, "Diferença de R$ 0,01"));
      assertTrue(fixture.cashTurns.currentTurn().orElseThrow().open());
      assertEquals(0, fixture.scalar("SELECT COUNT(*) FROM conferencia_caixa"));
      assertEquals(
          0,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='caixa_turno' AND tipo_operacao='CAIXA_FECHADO'"));
      fixture.execute("DROP TRIGGER trg_test_reject_cash_close_audit");

      var close = fixture.cashTurns.close(counted, "Diferença de R$ 0,01");
      var cashResult =
          close.stream()
              .filter(total -> total.method() == PaymentMethod.DINHEIRO)
              .findFirst()
              .orElseThrow();
      assertEquals(1_300, cashResult.expectedCents());
      assertEquals(1_299, cashResult.countedCents());
      assertEquals(3, fixture.cashTurns.latestClosing(opened.id()).size());
      assertEquals(3, fixture.scalar("SELECT COUNT(*) FROM conferencia_caixa"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM operacao_pendente WHERE entidade='caixa_turno' AND tipo_operacao='CAIXA_FECHADO'"));
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='CAIXA_FECHADO' AND motivo='Diferença de R$ 0,01'"));
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.cashTurns.recordMovement(
                  CashMovementType.SUPRIMENTO, 100, "Após fechamento"));

      fixture.useReceptionRole();
      assertThrows(
          AuthorizationException.class,
          () -> fixture.cashTurns.reopen(opened.id(), "Ajuste autorizado"));
      fixture.restoreAdministrator();
      fixture.cashTurns.reopen(opened.id(), "Reabertura autorizada");
      assertTrue(fixture.cashTurns.currentTurn().orElseThrow().open());
      assertEquals(
          1,
          fixture.scalar(
              "SELECT COUNT(*) FROM evento_auditoria WHERE acao='CAIXA_REABERTO' AND motivo='Reabertura autorizada'"));
    }
  }

  @Test
  void closedCashTurnAndMovementsSurviveDatabaseRestart(@TempDir Path temp) throws Exception {
    Path databaseFile = temp.resolve("cash-restart.sqlite");
    UUID turnId;
    try (Fixture fixture = new Fixture(databaseFile)) {
      var turn = fixture.cashTurns.open(250);
      turnId = turn.id();
      fixture.cashTurns.recordMovement(CashMovementType.SUPRIMENTO, 150, "Troco adicional");
      EnumMap<PaymentMethod, Long> counted = new EnumMap<>(PaymentMethod.class);
      counted.put(PaymentMethod.DINHEIRO, 400L);
      counted.put(PaymentMethod.PIX, 0L);
      counted.put(PaymentMethod.CARTAO, 0L);
      fixture.cashTurns.close(counted, null);
    }
    try (LocalDatabaseSession reopened = LocalDatabaseSession.open(databaseFile)) {
      SqliteUnitOfWork unit = new SqliteUnitOfWork(reopened.connection());
      SqliteCashTurnRepository turns = new SqliteCashTurnRepository(reopened.connection(), unit);
      assertEquals(1, turns.findMovements(turnId).size());
      assertEquals(3, turns.findLatestClosing(turnId).size());
      assertEquals(400L, turns.findById(turnId).orElseThrow().closingCents());
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
    final MaintainAttendanceUseCase attendance;
    final RegisterPaymentUseCase payments;
    final RefundPaymentUseCase refunds;
    final ManageCashTurnUseCase cashTurns;
    final SqliteCashTurnRepository cashTurnRepository;
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
      var commissionRules = new SqliteCommissionRuleRepository(database.connection(), unitOfWork);
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
      attendance =
          new MaintainAttendanceUseCase(
              unitOfWork,
              new SqliteAttendanceRepository(database.connection(), unitOfWork),
              appointments,
              services,
              commissionRules,
              outbox,
              authorization,
              audit,
              STATION,
              Clock.fixed(NOW, ZoneId.of("America/Manaus")));
      payments =
          new RegisterPaymentUseCase(
              unitOfWork,
              new SqliteAttendanceRepository(database.connection(), unitOfWork),
              new SqlitePaymentRepository(database.connection(), unitOfWork),
              new SqliteOpenCashTurnRepository(database.connection(), unitOfWork),
              outbox,
              authorization,
              audit,
              STATION,
              Clock.fixed(NOW, ZoneId.of("America/Manaus")));
      cashTurnRepository = new SqliteCashTurnRepository(database.connection(), unitOfWork);
      refunds =
          new RefundPaymentUseCase(
              unitOfWork,
              new SqlitePaymentRepository(database.connection(), unitOfWork),
              new SqlitePaymentRefundRepository(database.connection(), unitOfWork),
              cashTurnRepository,
              outbox,
              authorization,
              audit,
              STATION,
              Clock.fixed(NOW, ZoneId.of("America/Manaus")));
      cashTurns =
          new ManageCashTurnUseCase(
              unitOfWork,
              cashTurnRepository,
              outbox,
              audit,
              authorization,
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

    void insertAttendance(UUID appointmentId, UUID attendanceId, String state) throws Exception {
      try (PreparedStatement insert =
          database
              .connection()
              .prepareStatement(
                  "INSERT INTO atendimento (id,agendamento_id,cliente_id,profissional_id,estado,subtotal_centavos,desconto_centavos,total_centavos,iniciado_em_utc,concluido_em_utc,criado_por_usuario_id,criado_em_utc) VALUES (?,?,?,?,?,2500,0,2500,?,NULL,?,?)")) {
        insert.setString(1, attendanceId.toString());
        insert.setString(2, appointmentId.toString());
        insert.setString(3, clientId.toString());
        insert.setString(4, professionalId.toString());
        insert.setString(5, state);
        insert.setString(6, "2026-11-02T13:00:00Z");
        insert.setString(7, adminId.toString());
        insert.setString(8, "2026-11-02T13:00:00Z");
        insert.executeUpdate();
      }
    }

    void openCashTurn() throws Exception {
      execute(
          "INSERT INTO caixa_turno (id,operador_usuario_id,estado,abertura_em_utc,saldo_abertura_centavos) "
              + "VALUES ('"
              + UUID.randomUUID()
              + "','"
              + adminId
              + "','ABERTO','"
              + NOW
              + "',0)");
    }

    void useReceptionRole() {
      sessions.establish(
          new AuthenticatedUser(UUID.randomUUID(), "Recepção", "recepcao", UserRole.RECEPCAO));
    }

    void restoreAdministrator() {
      sessions.establish(
          new AuthenticatedUser(adminId, "Admin Agenda", "admin", UserRole.ADMINISTRADOR));
    }

    int scalar(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet row = statement.executeQuery(sql)) {
        return row.next() ? row.getInt(1) : -1;
      }
    }

    String string(String sql) throws Exception {
      try (Statement statement = database.connection().createStatement();
          ResultSet rows = statement.executeQuery(sql)) {
        return rows.next() ? rows.getString(1) : null;
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
