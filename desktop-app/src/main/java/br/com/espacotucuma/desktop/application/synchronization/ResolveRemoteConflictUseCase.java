package br.com.espacotucuma.desktop.application.synchronization;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.synchronization.RemoteConflictRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Records the operator's decision while retaining the remote snapshot for audit and recovery. */
public final class ResolveRemoteConflictUseCase {
  private static final String DECISION = "MANTER_LOCAL_PRESERVAR_REMOTO";
  private final UnitOfWork unitOfWork;
  private final RemoteConflictRepository conflicts;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;

  public ResolveRemoteConflictUseCase(
      UnitOfWork unitOfWork,
      RemoteConflictRepository conflicts,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
  }

  public void keepLocalAndPreserveRemote(UUID operationId, String reason) {
    Objects.requireNonNull(operationId, "operationId");
    String normalizedReason = Objects.requireNonNull(reason, "reason").trim();
    if (normalizedReason.length() < 2 || normalizedReason.length() > 240) {
      throw new IllegalArgumentException("Informe um motivo de 2 a 240 caracteres.");
    }
    var operator = authorization.requireUser(Permission.SYNC_CONFLICT_RESOLVE);
    Instant now = Instant.now();
    unitOfWork.execute(
        () -> {
          conflicts.recordDecision(operationId, operator.id(), now, normalizedReason);
          audit.record(
              new AuditEvent(
                  UUID.randomUUID(),
                  operator.id(),
                  UUID.randomUUID(),
                  "CONFLITO_SYNC_DECIDIDO",
                  "CONFLITO_SYNC",
                  operationId,
                  stationId,
                  "{\"decisao\":\"" + DECISION + "\",\"remotoPreservado\":true}",
                  normalizedReason,
                  now));
          return null;
        });
  }
}
