package br.com.espacotucuma.desktop.application.client;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Client(UUID id, String name, String phone, Instant createdAt) {
  public Client {
    Objects.requireNonNull(id, "id");
    name = Objects.requireNonNull(name, "name").trim();
    if (name.length() < 2 || name.length() > 160) {
      throw new IllegalArgumentException("O nome deve ter entre 2 e 160 caracteres.");
    }
    if (phone != null && phone.isBlank()) {
      phone = null;
    }
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
