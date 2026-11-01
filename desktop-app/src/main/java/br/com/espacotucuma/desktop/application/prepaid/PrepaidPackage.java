package br.com.espacotucuma.desktop.application.prepaid;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PrepaidPackage(
    UUID id,
    String name,
    int sessions,
    int validityDays,
    Money price,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {
  public PrepaidPackage {
    Objects.requireNonNull(id, "id");
    name = Objects.requireNonNull(name, "name").strip();
    if (name.length() < 2 || name.length() > 120) {
      throw new IllegalArgumentException("O nome do pacote deve conter de 2 a 120 caracteres.");
    }
    if (sessions < 1 || sessions > 500) {
      throw new IllegalArgumentException("O pacote deve conter de 1 a 500 sessões.");
    }
    if (validityDays < 1 || validityDays > 3650) {
      throw new IllegalArgumentException("A validade deve estar entre 1 e 3650 dias.");
    }
    Objects.requireNonNull(price, "price");
    if (price.amount().signum() < 0) {
      throw new IllegalArgumentException("O valor do pacote não pode ser negativo.");
    }
    price.amount().movePointRight(2).longValueExact();
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("A atualização não pode anteceder o cadastro.");
    }
  }
}
