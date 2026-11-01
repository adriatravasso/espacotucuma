package br.com.espacotucuma.desktop.application.finance;

import br.com.espacotucuma.desktop.application.attendance.Attendance;
import br.com.espacotucuma.desktop.application.attendance.AttendanceStatus;
import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.attendance.AttendanceRepository;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.finance.OpenCashTurnRepository;
import br.com.espacotucuma.desktop.application.port.finance.PaymentRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Local receivable and cash movement are committed together with audit and outbox records. */
public final class RegisterPaymentUseCase {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private final UnitOfWork unitOfWork;
  private final AttendanceRepository attendances;
  private final PaymentRepository payments;
  private final OpenCashTurnRepository cashTurns;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;
  private final Clock clock;

  public RegisterPaymentUseCase(
      UnitOfWork unitOfWork,
      AttendanceRepository attendances,
      PaymentRepository payments,
      OpenCashTurnRepository cashTurns,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this(
        unitOfWork,
        attendances,
        payments,
        cashTurns,
        outbox,
        authorization,
        audit,
        stationId,
        Clock.system(BUSINESS_ZONE));
  }

  public RegisterPaymentUseCase(
      UnitOfWork unitOfWork,
      AttendanceRepository attendances,
      PaymentRepository payments,
      OpenCashTurnRepository cashTurns,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.attendances = Objects.requireNonNull(attendances, "attendances");
    this.payments = Objects.requireNonNull(payments, "payments");
    this.cashTurns = Objects.requireNonNull(cashTurns, "cashTurns");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Payment register(
      UUID attendanceId, PaymentMethod method, Money amount, String idempotencyKey) {
    var actor = authorization.requireUser(Permission.PAYMENT_RECORD);
    String key = normalizeKey(idempotencyKey);
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(amount, "amount");
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    return unitOfWork.execute(
        () -> {
          var previous = payments.findByIdempotencyKey(key);
          if (previous.isPresent()) {
            Payment existing = previous.get();
            if (!existing.attendanceId().equals(attendanceId)
                || existing.method() != method
                || !existing.amount().equals(amount)) {
              throw new IllegalStateException(
                  "A chave de idempotência já foi usada em outro recebimento.");
            }
            return existing;
          }
          Attendance attendance = requireCompleted(attendanceId);
          long totalCents = cents(attendance.total());
          long receivedCents = payments.confirmedTotalCents(attendanceId);
          long amountCents = cents(amount);
          long outstandingCents = totalCents - receivedCents;
          if (amountCents > outstandingCents) {
            throw new IllegalArgumentException(
                "O valor excede o saldo pendente de " + formatMoney(outstandingCents) + ".");
          }
          UUID turnId =
              cashTurns
                  .findOpenTurn(actor.id())
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Abra um turno de caixa antes de registrar recebimentos."));
          Payment payment =
              new Payment(UUID.randomUUID(), attendanceId, method, amount, now, actor.id(), key);
          payments.insert(payment);
          UUID movementId = cashTurns.recordReceipt(turnId, payment, now);
          UUID paymentOperationId = UUID.randomUUID();
          record(
              "recebimento",
              payment.id(),
              "RECEBIMENTO_REGISTRADO",
              paymentPayload(payment),
              "{\"atendimentoId\":\""
                  + attendanceId
                  + "\",\"forma\":\""
                  + method.name()
                  + "\",\"valorCentavos\":"
                  + amountCents
                  + ",\"caixaTurnoId\":\""
                  + turnId
                  + "\"}",
              actor.id(),
              paymentOperationId,
              now);
          record(
              "movimento_caixa",
              movementId,
              "MOVIMENTO_CAIXA_RECEBIMENTO",
              "{\"id\":\""
                  + movementId
                  + "\",\"caixaTurnoId\":\""
                  + turnId
                  + "\",\"recebimentoId\":\""
                  + payment.id()
                  + "\",\"tipo\":\"ENTRADA\",\"valorCentavos\":"
                  + amountCents
                  + "}",
              "{\"recebimentoId\":\"" + payment.id() + "\"}",
              actor.id(),
              UUID.randomUUID(),
              now);
          return payment;
        });
  }

  public Attendance findAttendanceByAppointment(UUID appointmentId) {
    authorization.require(Permission.PAYMENT_RECORD);
    return attendances
        .findByAppointment(Objects.requireNonNull(appointmentId, "appointmentId"))
        .orElseThrow(() -> new IllegalArgumentException("Atendimento associado não encontrado."));
  }

  public ReceivableSummary summary(UUID attendanceId) {
    authorization.require(Permission.PAYMENT_RECORD);
    Attendance attendance = requireCompleted(attendanceId);
    long received = payments.confirmedTotalCents(attendanceId);
    long total = cents(attendance.total());
    if (received > total) {
      throw new IllegalStateException("Os recebimentos locais excedem o total do atendimento.");
    }
    return new ReceivableSummary(money(total), money(received), money(total - received));
  }

  public List<Payment> findForAttendance(UUID attendanceId) {
    authorization.require(Permission.PAYMENT_RECORD);
    requireCompleted(attendanceId);
    return payments.findForAttendance(attendanceId);
  }

  private Attendance requireCompleted(UUID attendanceId) {
    Attendance attendance =
        attendances
            .findById(Objects.requireNonNull(attendanceId, "attendanceId"))
            .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado."));
    if (attendance.status() != AttendanceStatus.CONCLUIDO) {
      throw new IllegalStateException("Conclua o atendimento antes de registrar um pagamento.");
    }
    return attendance;
  }

  private void record(
      String entity,
      UUID entityId,
      String action,
      String payload,
      String details,
      UUID actorId,
      UUID operationId,
      Instant at) {
    outbox.enqueue(
        new PendingOperation(
            operationId,
            entity,
            entityId,
            action,
            operationId.toString(),
            payload,
            1,
            outbox.expectedVersionForNext(entity, entityId),
            stationId,
            at,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            action,
            entity,
            entityId,
            stationId,
            details,
            null,
            at));
  }

  private String paymentPayload(Payment payment) {
    return "{\"id\":\""
        + payment.id()
        + "\",\"atendimentoId\":\""
        + payment.attendanceId()
        + "\",\"forma\":\""
        + payment.method().name()
        + "\",\"valorCentavos\":"
        + cents(payment.amount())
        + ",\"estado\":\"CONFIRMADO\",\"chaveIdempotencia\":\""
        + escape(payment.idempotencyKey())
        + "\"}";
  }

  private String escape(String text) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        default -> {
          if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
          else result.append(character);
        }
      }
    }
    return result.toString();
  }

  private String normalizeKey(String key) {
    String normalized = Objects.requireNonNull(key, "idempotencyKey").strip();
    if (normalized.isEmpty() || normalized.length() > 120) {
      throw new IllegalArgumentException("Informe uma chave de recebimento válida.");
    }
    return normalized;
  }

  private long cents(Money value) {
    return value.amount().movePointRight(2).longValueExact();
  }

  private Money money(long cents) {
    return new Money(BigDecimal.valueOf(cents, 2));
  }

  private String formatMoney(long cents) {
    return "R$ " + BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',');
  }
}
