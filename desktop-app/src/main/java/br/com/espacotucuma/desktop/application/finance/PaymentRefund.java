package br.com.espacotucuma.desktop.application.finance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaymentRefund(
    UUID id,
    UUID paymentId,
    UUID cashMovementId,
    long amountCents,
    String idempotencyKey,
    String reason,
    UUID refundedBy,
    Instant refundedAt) {
  public PaymentRefund {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(paymentId, "paymentId");
    Objects.requireNonNull(cashMovementId, "cashMovementId");
    if (amountCents <= 0)
      throw new IllegalArgumentException("O valor do estorno deve ser positivo.");
    idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
    reason = Objects.requireNonNull(reason, "reason").strip();
    if (idempotencyKey.isEmpty() || idempotencyKey.length() > 120)
      throw new IllegalArgumentException("Informe uma chave de estorno válida.");
    if (reason.length() < 2 || reason.length() > 240)
      throw new IllegalArgumentException("Informe um motivo de 2 a 240 caracteres.");
    Objects.requireNonNull(refundedBy, "refundedBy");
    Objects.requireNonNull(refundedAt, "refundedAt");
  }
}
