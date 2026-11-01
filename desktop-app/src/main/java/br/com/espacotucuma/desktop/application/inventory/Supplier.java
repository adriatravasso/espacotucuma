package br.com.espacotucuma.desktop.application.inventory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Supplier(
    UUID id, String name, String phone, String email, boolean active, Instant createdAt) {
  public Supplier {
    Objects.requireNonNull(id, "id");
    name = required(name, 2, 160, "O nome do fornecedor deve conter de 2 a 160 caracteres.");
    phone = optional(phone, 40, "O telefone deve ter até 40 caracteres.");
    email = optional(email, 254, "O e-mail deve ter até 254 caracteres.");
    Objects.requireNonNull(createdAt, "createdAt");
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
