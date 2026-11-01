package br.com.espacotucuma.desktop.application.port.identity;

import java.util.Optional;
import java.util.UUID;

public interface CurrentUserProvider {
  Optional<UUID> currentUserId();
}
