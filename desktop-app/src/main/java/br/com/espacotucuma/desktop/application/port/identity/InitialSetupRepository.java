package br.com.espacotucuma.desktop.application.port.identity;

import java.time.Instant;
import java.util.UUID;

public interface InitialSetupRepository {
  boolean isComplete();

  void save(
      UUID establishmentId,
      String establishmentName,
      UUID administratorId,
      String administratorName,
      String login,
      String passwordHash,
      Instant createdAt);
}
