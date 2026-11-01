package br.com.espacotucuma.desktop.application.port.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LoginAttemptRepository {
  void record(Optional<UUID> userId, String login, boolean success, Instant occurredAt);
}
