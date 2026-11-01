package br.com.espacotucuma.desktop.application.inventory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StockEntry(
    UUID id,
    UUID productId,
    long quantityMilliUnits,
    long unitCostCents,
    String reason,
    String idempotencyKey,
    UUID createdBy,
    Instant createdAt) {
  public StockEntry {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(productId, "productId");
    if (quantityMilliUnits <= 0 || unitCostCents < 0)
      throw new IllegalArgumentException("A quantidade e o custo da entrada são inválidos.");
    reason = required(reason, 2, 240, "O motivo deve conter de 2 a 240 caracteres.");
    idempotencyKey = required(idempotencyKey, 1, 120, "A chave da entrada é inválida.");
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  private static String required(String value, int min, int max, String message) {
    String normalized = Objects.requireNonNull(value).strip();
    if (normalized.length() < min || normalized.length() > max)
      throw new IllegalArgumentException(message);
    return normalized;
  }
}
