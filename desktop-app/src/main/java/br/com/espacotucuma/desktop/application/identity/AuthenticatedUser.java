package br.com.espacotucuma.desktop.application.identity;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedUser(UUID id, String name, String login, UserRole role) {
  public AuthenticatedUser {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(login, "login");
    Objects.requireNonNull(role, "role");
  }
}
