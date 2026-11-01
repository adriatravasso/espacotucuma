package br.com.espacotucuma.desktop.application.professional;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.professional.ProfessionalRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class MaintainProfessionalUseCase {
  private final UnitOfWork unitOfWork;
  private final ProfessionalRepository professionals;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;

  public MaintainProfessionalUseCase(
      UnitOfWork unitOfWork,
      ProfessionalRepository professionals,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.professionals = Objects.requireNonNull(professionals, "professionals");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
  }

  public Professional register(String name, String phone, ProfessionalSchedule schedule) {
    var actor = authorization.requireUser(Permission.PROFESSIONAL_REGISTER);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    Professional professional =
        new Professional(UUID.randomUUID(), name, phone, true, schedule, now, now);
    return unitOfWork.execute(
        () -> {
          professionals.insert(professional);
          record(professional, "PROFISSIONAL_CADASTRADO", operationId, now, actor.id());
          return professional;
        });
  }

  public Professional update(UUID id, String name, String phone, ProfessionalSchedule schedule) {
    var actor = authorization.requireUser(Permission.PROFESSIONAL_UPDATE);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          Professional current = professionals.findById(id).orElseThrow(() -> missing(id));
          if (!current.active()) {
            throw new IllegalStateException("O profissional está inativo.");
          }
          Instant updatedAt = current.updatedAt().isAfter(now) ? current.updatedAt() : now;
          Professional updated =
              new Professional(
                  current.id(),
                  name,
                  phone,
                  current.active(),
                  schedule,
                  current.createdAt(),
                  updatedAt);
          professionals.update(updated);
          record(updated, "PROFISSIONAL_ATUALIZADO", operationId, updatedAt, actor.id());
          return updated;
        });
  }

  public void deactivate(UUID id) {
    var actor = authorization.requireUser(Permission.PROFESSIONAL_DEACTIVATE);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    unitOfWork.execute(
        () -> {
          Professional current = professionals.findById(id).orElseThrow(() -> missing(id));
          if (!current.active()) return null;
          Instant updatedAt = current.updatedAt().isAfter(now) ? current.updatedAt() : now;
          professionals.deactivate(id, updatedAt);
          Professional inactive =
              new Professional(
                  current.id(),
                  current.name(),
                  current.phone(),
                  false,
                  current.schedule(),
                  current.createdAt(),
                  updatedAt);
          record(inactive, "PROFISSIONAL_INATIVADO", operationId, updatedAt, actor.id());
          return null;
        });
  }

  private void record(
      Professional professional, String action, UUID operationId, Instant at, UUID actorId) {
    long expectedVersion = outbox.expectedVersionForNext("profissional", professional.id());
    outbox.enqueue(
        new PendingOperation(
            operationId,
            "profissional",
            professional.id(),
            action,
            operationId.toString(),
            ProfessionalPayload.json(professional),
            1,
            expectedVersion,
            stationId,
            at,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            action,
            "profissional",
            professional.id(),
            stationId,
            "{}",
            null,
            at));
  }

  private IllegalArgumentException missing(UUID id) {
    return new IllegalArgumentException("Profissional não encontrado: " + id);
  }
}
