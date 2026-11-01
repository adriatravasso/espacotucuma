package br.com.espacotucuma.desktop.application.identity;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.identity.UserAdministrationRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class ChangeUserRoleUseCase {
  private final UnitOfWork unitOfWork;
  private final UserAdministrationRepository users;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID originStationId;

  public ChangeUserRoleUseCase(
      UnitOfWork unitOfWork,
      UserAdministrationRepository users,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID originStationId) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.users = Objects.requireNonNull(users);
    this.authorization = Objects.requireNonNull(authorization);
    this.audit = Objects.requireNonNull(audit);
    this.originStationId = Objects.requireNonNull(originStationId);
  }

  public void change(UUID userId, UserRole newRole, String reason) {
    Objects.requireNonNull(newRole, "newRole");
    String normalizedReason = requireReason(reason);
    AuthenticatedUser actor = authorization.requireUser(Permission.MANAGE_PERMISSIONS);
    if (actor.id().equals(userId)) {
      throw new IllegalArgumentException("Encerre a sessão antes de alterar o próprio perfil.");
    }
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    unitOfWork.execute(
        () -> {
          UserRole previousRole = users.roleOf(userId);
          if (previousRole == UserRole.ADMINISTRADOR
              && newRole != UserRole.ADMINISTRADOR
              && users.activeAdministratorCount() <= 1) {
            throw new IllegalStateException(
                "A última conta administradora ativa não pode perder o perfil.");
          }
          users.changeRole(userId, newRole);
          audit.record(
              new AuditEvent(
                  UUID.randomUUID(),
                  actor.id(),
                  UUID.randomUUID(),
                  "PERFIL_ALTERADO",
                  "usuario",
                  userId,
                  originStationId,
                  "{\"perfilAnterior\":\""
                      + previousRole.name()
                      + "\",\"perfilNovo\":\""
                      + newRole.name()
                      + "\"}",
                  normalizedReason,
                  now));
          return null;
        });
  }

  private String requireReason(String reason) {
    String normalized = Objects.requireNonNull(reason, "reason").trim();
    if (normalized.length() < 2 || normalized.length() > 240) {
      throw new IllegalArgumentException("Informe o motivo da alteração (2 a 240 caracteres).");
    }
    return normalized;
  }
}
