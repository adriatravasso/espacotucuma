package br.com.espacotucuma.desktop.application.finance;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Payment(
    UUID id,
    UUID attendanceId,
    PaymentMethod method,
    Money amount,
    Instant receivedAt,
    UUID receivedBy,
    String idempotencyKey) {
  public Payment {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(attendanceId, "attendanceId");
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(amount, "amount");
    if (amount.amount().signum() <= 0) {
      throw new IllegalArgumentException("O valor recebido deve ser maior que zero.");
    }
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(receivedBy, "receivedBy");
    idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
    if (idempotencyKey.isEmpty() || idempotencyKey.length() > 120) {
      throw new IllegalArgumentException("A chave do recebimento deve ter de 1 a 120 caracteres.");
    }
  }
}
