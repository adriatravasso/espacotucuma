package br.com.espacotucuma.desktop.application.identity;

import br.com.espacotucuma.desktop.application.port.identity.UserAdministrationRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.util.Objects;
import java.util.UUID;

public final class DeactivateUserUseCase {
  private final UnitOfWork unitOfWork;
  private final UserAdministrationRepository users;
  private final AuthorizationService authorization;
  private final SessionContext sessions;

  public DeactivateUserUseCase(
      UnitOfWork unitOfWork,
      UserAdministrationRepository users,
      AuthorizationService authorization,
      SessionContext sessions) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.users = Objects.requireNonNull(users);
    this.authorization = Objects.requireNonNull(authorization);
    this.sessions = Objects.requireNonNull(sessions);
  }

  public void deactivate(UUID userId) {
    authorization.require(Permission.MANAGE_USERS);
    if (sessions.current().orElseThrow().user().id().equals(userId)) {
      throw new IllegalArgumentException("Encerre a sessão antes de desativar a própria conta.");
    }
    unitOfWork.execute(
        () -> {
          if (users.roleOf(userId) == UserRole.ADMINISTRADOR
              && users.activeAdministratorCount() <= 1) {
            throw new IllegalStateException(
                "A última conta administradora ativa não pode ser desativada.");
          }
          users.deactivate(userId);
          return null;
        });
  }
}
