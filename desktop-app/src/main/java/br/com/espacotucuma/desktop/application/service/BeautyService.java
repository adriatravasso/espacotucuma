package br.com.espacotucuma.desktop.application.service;

import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BeautyService(
    UUID id,
    String name,
    String category,
    int durationMinutes,
    Money price,
    int commissionBasisPoints,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {
  public BeautyService {
    Objects.requireNonNull(id, "id");
    name = requiredText(name, 120, "O nome do serviço deve conter de 2 a 120 caracteres.");
    category = requiredText(category, 80, "A categoria deve conter de 2 a 80 caracteres.");
    if (durationMinutes < 5 || durationMinutes > 1440) {
      throw new IllegalArgumentException("A duração deve ser de 5 minutos a 24 horas.");
    }
    Objects.requireNonNull(price, "price");
    if (price.amount().signum() < 0) {
      throw new IllegalArgumentException("O preço não pode ser negativo.");
    }
    price.amount().movePointRight(2).longValueExact();
    if (commissionBasisPoints < 0 || commissionBasisPoints > 10000) {
      throw new IllegalArgumentException("A comissão deve estar entre 0% e 100%.");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("A atualização não pode anteceder o cadastro.");
    }
  }

  private static String requiredText(String value, int maxLength, String message) {
    String normalized = Objects.requireNonNull(value, "value").strip();
    if (normalized.length() < 2 || normalized.length() > maxLength) {
      throw new IllegalArgumentException(message);
    }
    return normalized;
  }
}
