package br.com.espacotucuma.desktop.application.identity;

import java.util.Objects;
import java.util.UUID;

public record LocalCredential(
    UUID userId, String name, String login, UserRole role, String passwordHash) {
  public LocalCredential {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(login, "login");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(passwordHash, "passwordHash");
  }
}
