package br.com.espacotucuma.desktop.application.port.identity;

import br.com.espacotucuma.desktop.application.identity.LocalCredential;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LocalCredentialRepository {
  Optional<LocalCredential> findActiveByLogin(String login);

  void updateHash(UUID userId, String passwordHash, Instant updatedAt);
}
