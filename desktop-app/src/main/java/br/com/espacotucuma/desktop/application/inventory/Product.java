package br.com.espacotucuma.desktop.application.inventory;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Product(
    UUID id,
    UUID supplierId,
    String sku,
    String name,
    String category,
    UnitOfMeasure unit,
    Money unitCost,
    Money salePrice,
    long minimumStockMilliUnits,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {
  public Product {
    Objects.requireNonNull(id, "id");
    sku = optional(sku, 80, "O SKU deve ter até 80 caracteres.");
    name = required(name, 2, 160, "O nome deve conter de 2 a 160 caracteres.");
    category = required(category, 2, 80, "A categoria deve conter de 2 a 80 caracteres.");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(unitCost, "unitCost");
    if (unitCost.amount().signum() < 0)
      throw new IllegalArgumentException("O custo não pode ser negativo.");
    unitCost.amount().movePointRight(2).longValueExact();
    if (salePrice != null) {
      if (salePrice.amount().signum() < 0)
        throw new IllegalArgumentException("O preço de venda não pode ser negativo.");
      salePrice.amount().movePointRight(2).longValueExact();
    }
    if (minimumStockMilliUnits < 0 || (unit.integral() && minimumStockMilliUnits % 1000 != 0))
      throw new IllegalArgumentException(
          "O estoque mínimo não é válido para a unidade selecionada.");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt))
      throw new IllegalArgumentException("A atualização não pode anteceder o cadastro.");
  }

  private static String required(String value, int min, int max, String message) {
    String normalized = Objects.requireNonNull(value).strip();
    if (normalized.length() < min || normalized.length() > max)
      throw new IllegalArgumentException(message);
    return normalized;
  }

  private static String optional(String value, int max, String message) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.strip();
    if (normalized.length() > max) throw new IllegalArgumentException(message);
    return normalized;
  }
}
