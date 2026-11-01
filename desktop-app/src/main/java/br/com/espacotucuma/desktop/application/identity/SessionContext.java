package br.com.espacotucuma.desktop.application.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** The current local user; every successful login creates an isolated session identity. */
public final class SessionContext {
  private AuthenticatedSession current;

  public synchronized AuthenticatedSession establish(AuthenticatedUser user) {
    current = new AuthenticatedSession(UUID.randomUUID(), user, Instant.now());
    return current;
  }

  public synchronized Optional<AuthenticatedSession> current() {
    return Optional.ofNullable(current);
  }

  public synchronized void clear() {
    current = null;
  }
}
