package br.com.espacotucuma.desktop.application.finance;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.finance.CashTurnRepository;
import br.com.espacotucuma.desktop.application.port.finance.PaymentRefundRepository;
import br.com.espacotucuma.desktop.application.port.finance.PaymentRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Records a compensating cash movement and immutable refund history without deleting its receipt.
 */
public final class RefundPaymentUseCase {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private final UnitOfWork unitOfWork;
  private final PaymentRepository payments;
  private final PaymentRefundRepository refunds;
  private final CashTurnRepository cashTurns;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;
  private final Clock clock;

  public RefundPaymentUseCase(
      UnitOfWork unitOfWork,
      PaymentRepository payments,
      PaymentRefundRepository refunds,
      CashTurnRepository cashTurns,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this(
        unitOfWork,
        payments,
        refunds,
        cashTurns,
        outbox,
        authorization,
        audit,
        stationId,
        Clock.system(BUSINESS_ZONE));
  }

  public RefundPaymentUseCase(
      UnitOfWork unitOfWork,
      PaymentRepository payments,
      PaymentRefundRepository refunds,
      CashTurnRepository cashTurns,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.payments = Objects.requireNonNull(payments);
    this.refunds = Objects.requireNonNull(refunds);
    this.cashTurns = Objects.requireNonNull(cashTurns);
    this.outbox = Objects.requireNonNull(outbox);
    this.authorization = Objects.requireNonNull(authorization);
    this.audit = Objects.requireNonNull(audit);
    this.stationId = Objects.requireNonNull(stationId);
    this.clock = Objects.requireNonNull(clock);
  }

  public PaymentRefund refund(
      UUID paymentId, long amountCents, String reason, String idempotencyKey) {
    var actor = authorization.requireUser(Permission.FINANCIAL_REVERSAL);
    String key = Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
    String normalizedReason = Objects.requireNonNull(reason, "reason").strip();
    if (key.isEmpty() || key.length() > 120)
      throw new IllegalArgumentException("Informe uma chave de estorno válida.");
    if (normalizedReason.length() < 2 || normalizedReason.length() > 240)
      throw new IllegalArgumentException("Informe um motivo de 2 a 240 caracteres.");
    if (amountCents <= 0)
      throw new IllegalArgumentException("O valor do estorno deve ser maior que zero.");
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    return unitOfWork.execute(
        () -> {
          var previous = refunds.findByIdempotencyKey(key);
          if (previous.isPresent()) {
            PaymentRefund existing = previous.get();
            if (!existing.paymentId().equals(paymentId)
                || existing.amountCents() != amountCents
                || !existing.reason().equals(normalizedReason))
              throw new IllegalStateException(
                  "A chave de idempotência já foi usada em outro estorno.");
            return existing;
          }
          Payment payment =
              payments
                  .findById(Objects.requireNonNull(paymentId, "paymentId"))
                  .orElseThrow(() -> new IllegalArgumentException("Recebimento não encontrado."));
          long remaining =
              payment.amount().amount().movePointRight(2).longValueExact()
                  - refunds.refundedTotalCents(paymentId);
          if (amountCents > remaining)
            throw new IllegalArgumentException(
                "O estorno excede o valor ainda disponível deste recebimento.");
          UUID turnId =
              cashTurns
                  .findOpenTurn(actor.id())
                  .map(CashTurn::id)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Abra um turno de caixa antes de registrar estornos."));
          UUID movementId =
              cashTurns.recordRefund(
                  turnId, paymentId, amountCents, normalizedReason, actor.id(), now);
          PaymentRefund refund =
              new PaymentRefund(
                  UUID.randomUUID(),
                  paymentId,
                  movementId,
                  amountCents,
                  key,
                  normalizedReason,
                  actor.id(),
                  now);
          refunds.insert(refund);
          UUID operationId = UUID.randomUUID();
          String payload =
              "{\"id\":\""
                  + refund.id()
                  + "\",\"recebimentoId\":\""
                  + paymentId
                  + "\",\"movimentoCaixaId\":\""
                  + movementId
                  + "\",\"valorCentavos\":"
                  + amountCents
                  + ",\"motivo\":\""
                  + escape(normalizedReason)
                  + "\",\"chaveIdempotencia\":\""
                  + escape(key)
                  + "\"}";
          outbox.enqueue(
              new PendingOperation(
                  operationId,
                  "estorno_recebimento",
                  refund.id(),
                  "RECEBIMENTO_ESTORNADO",
                  operationId.toString(),
                  payload,
                  1,
                  outbox.expectedVersionForNext("estorno_recebimento", refund.id()),
                  stationId,
                  now,
                  0));
          audit.record(
              new AuditEvent(
                  UUID.randomUUID(),
                  actor.id(),
                  operationId,
                  "RECEBIMENTO_ESTORNADO",
                  "estorno_recebimento",
                  refund.id(),
                  stationId,
                  "{\"recebimentoId\":\"" + paymentId + "\",\"valorCentavos\":" + amountCents + "}",
                  normalizedReason,
                  now));
          return refund;
        });
  }

  public java.util.List<PaymentRefund> history(UUID paymentId) {
    authorization.require(Permission.PAYMENT_RECORD);
    return refunds.findForPayment(Objects.requireNonNull(paymentId, "paymentId"));
  }

  private String escape(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }
}
