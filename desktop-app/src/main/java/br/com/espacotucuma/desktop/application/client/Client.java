package br.com.espacotucuma.desktop.application.client;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Client(
    UUID id,
    String internalCode,
    String name,
    String phone,
    String optionalContact,
    String preferences,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {

  public Client {
    Objects.requireNonNull(id, "id");
    internalCode = optionalText(internalCode, 20, "Código interno");
    name = Objects.requireNonNull(name, "name").trim();
    if (name.length() < 2 || name.length() > 160) {
      throw new IllegalArgumentException("O nome deve ter entre 2 e 160 caracteres.");
    }
    phone = normalizePhone(phone);
    optionalContact = optionalText(optionalContact, 160, "Contato opcional");
    preferences = optionalText(preferences, 500, "Preferências administrativas");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("A atualização não pode anteceder o cadastro.");
    }
  }

  public Client(UUID id, String name, String phone, Instant createdAt) {
    this(id, null, name, phone, null, null, true, createdAt, createdAt);
  }

  private static String optionalText(String value, int maxLength, String field) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(
          field + " excede o limite de " + maxLength + " caracteres.");
    }
    return normalized;
  }

  private static String normalizePhone(String value) {
    if (value == null || value.isBlank()) return null;
    String input = value.trim();
    if (!input.matches("[+()0-9 .-]+")) {
      throw new IllegalArgumentException("Informe um telefone válido.");
    }
    String digits = input.replaceAll("\\D", "");
    if (digits.length() < 8 || digits.length() > 15) {
      throw new IllegalArgumentException("O telefone deve conter de 8 a 15 dígitos.");
    }
    return digits;
  }
}
