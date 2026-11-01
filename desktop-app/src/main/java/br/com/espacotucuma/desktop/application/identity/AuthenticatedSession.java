package br.com.espacotucuma.desktop.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuthenticatedSession(UUID id, AuthenticatedUser user, Instant authenticatedAt) {
  public AuthenticatedSession {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(authenticatedAt, "authenticatedAt");
  }
}
