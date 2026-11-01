package br.com.espacotucuma.desktop.application.inventory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StockMovement(
    UUID id,
    UUID productId,
    UUID attendanceId,
    UUID attendanceServiceLineId,
    StockMovementType type,
    AdjustmentDirection adjustmentDirection,
    long quantityMilliUnits,
    long unitCostCents,
    String reason,
    String idempotencyKey,
    UUID reversesMovementId,
    UUID createdBy,
    Instant createdAt) {
  public StockMovement {
    Objects.requireNonNull(id);
    Objects.requireNonNull(productId);
    Objects.requireNonNull(type);
    if (type == StockMovementType.AJUSTE && adjustmentDirection == null)
      throw new IllegalArgumentException("Informe a direção do ajuste.");
    if (type != StockMovementType.AJUSTE && adjustmentDirection != null)
      throw new IllegalArgumentException("Direção de ajuste inválida para este movimento.");
    if ((type == StockMovementType.CONSUMO_SERVICO) != (attendanceServiceLineId != null)
        || (type == StockMovementType.CONSUMO_SERVICO) != (attendanceId != null))
      throw new IllegalArgumentException("O consumo deve identificar a linha do atendimento.");
    if (quantityMilliUnits <= 0 || unitCostCents < 0)
      throw new IllegalArgumentException("A quantidade e o custo do movimento são inválidos.");
    reason = required(reason, 2, 240, "Informe um motivo com 2 a 240 caracteres.");
    idempotencyKey = required(idempotencyKey, 1, 120, "A chave idempotente é inválida.");
    Objects.requireNonNull(createdBy);
    Objects.requireNonNull(createdAt);
  }

  private static String required(String value, int min, int max, String message) {
    String normalized = Objects.requireNonNull(value).strip();
    if (normalized.length() < min || normalized.length() > max)
      throw new IllegalArgumentException(message);
    return normalized;
  }
}
