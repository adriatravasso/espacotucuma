package br.com.espacotucuma.desktop.application.identity;

import br.com.espacotucuma.desktop.application.port.identity.UserAdministrationRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.util.Objects;
import java.util.UUID;

public final class ChangeUserRoleUseCase {
  private final UnitOfWork unitOfWork;
  private final UserAdministrationRepository users;
  private final AuthorizationService authorization;
  private final SessionContext sessions;

  public ChangeUserRoleUseCase(
      UnitOfWork unitOfWork,
      UserAdministrationRepository users,
      AuthorizationService authorization,
      SessionContext sessions) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.users = Objects.requireNonNull(users);
    this.authorization = Objects.requireNonNull(authorization);
    this.sessions = Objects.requireNonNull(sessions);
  }

  public void change(UUID userId, UserRole newRole) {
    Objects.requireNonNull(newRole, "newRole");
    authorization.require(Permission.MANAGE_PERMISSIONS);
    if (sessions.current().orElseThrow().user().id().equals(userId)) {
      throw new IllegalArgumentException("Encerre a sessão antes de alterar o próprio perfil.");
    }
    unitOfWork.execute(
        () -> {
          if (users.roleOf(userId) == UserRole.ADMINISTRADOR
              && newRole != UserRole.ADMINISTRADOR
              && users.activeAdministratorCount() <= 1) {
            throw new IllegalStateException(
                "A última conta administradora ativa não pode perder o perfil.");
          }
          users.changeRole(userId, newRole);
          return null;
        });
  }
}
