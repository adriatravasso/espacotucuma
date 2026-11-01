package br.com.espacotucuma.desktop.application.port.identity;

import br.com.espacotucuma.desktop.application.identity.UserRole;
import java.util.UUID;

public interface UserAdministrationRepository {
  boolean isActive(UUID userId);

  UserRole roleOf(UUID userId);

  int activeAdministratorCount();

  void changeRole(UUID userId, UserRole role);

  void deactivate(UUID userId);
}
