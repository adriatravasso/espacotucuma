package br.com.espacotucuma.desktop.application.identity;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.identity.UserAdministrationRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class DeactivateUserUseCase {
  private final UnitOfWork unitOfWork;
  private final UserAdministrationRepository users;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID originStationId;

  public DeactivateUserUseCase(
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

  public void deactivate(UUID userId, String reason) {
    String normalizedReason = Objects.requireNonNull(reason, "reason").trim();
    if (normalizedReason.length() < 2 || normalizedReason.length() > 240) {
      throw new IllegalArgumentException("Informe o motivo da desativação (2 a 240 caracteres).");
    }
    AuthenticatedUser actor = authorization.requireUser(Permission.MANAGE_USERS);
    if (actor.id().equals(userId)) {
      throw new IllegalArgumentException("Encerre a sessão antes de desativar a própria conta.");
    }
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    unitOfWork.execute(
        () -> {
          if (users.roleOf(userId) == UserRole.ADMINISTRADOR
              && users.activeAdministratorCount() <= 1) {
            throw new IllegalStateException(
                "A última conta administradora ativa não pode ser desativada.");
          }
          users.deactivate(userId);
          audit.record(
              new AuditEvent(
                  UUID.randomUUID(),
                  actor.id(),
                  UUID.randomUUID(),
                  "CONTA_DESATIVADA",
                  "usuario",
                  userId,
                  originStationId,
                  "{\"situacao\":\"INATIVA\"}",
                  normalizedReason,
                  now));
          return null;
        });
  }
}
