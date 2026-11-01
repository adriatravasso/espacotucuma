package br.com.espacotucuma.desktop.application.commission;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.commission.CommissionRuleRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class MaintainCommissionRuleUseCase {
  private final UnitOfWork unitOfWork;
  private final CommissionRuleRepository rules;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;

  public MaintainCommissionRuleUseCase(
      UnitOfWork unitOfWork,
      CommissionRuleRepository rules,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.rules = Objects.requireNonNull(rules, "rules");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
  }

  public ServiceCommissionRule setRule(
      UUID professionalId, UUID serviceId, int percentageBasisPoints) {
    var actor = authorization.requireUser(Permission.COMMISSION_RULE_MANAGE);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          ServiceCommissionRule previous =
              rules.findAnyByProfessionalAndService(professionalId, serviceId).orElse(null);
          ServiceCommissionRule rule =
              new ServiceCommissionRule(
                  previous == null ? UUID.randomUUID() : previous.id(),
                  professionalId,
                  serviceId,
                  percentageBasisPoints,
                  true,
                  previous == null ? now : previous.createdAt(),
                  previous != null && previous.updatedAt().isAfter(now)
                      ? previous.updatedAt()
                      : now);
          rules.save(rule);
          record(
              rule,
              previous == null ? "REGRA_COMISSAO_CRIADA" : "REGRA_COMISSAO_ATUALIZADA",
              operationId,
              actor.id(),
              previous);
          return rule;
        });
  }

  public void deactivate(UUID id) {
    var actor = authorization.requireUser(Permission.COMMISSION_RULE_MANAGE);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    unitOfWork.execute(
        () -> {
          ServiceCommissionRule previous =
              rules
                  .findById(id)
                  .orElseThrow(() -> new IllegalArgumentException("Regra não encontrada."));
          if (!previous.active()) return null;
          ServiceCommissionRule inactive =
              new ServiceCommissionRule(
                  previous.id(),
                  previous.professionalId(),
                  previous.serviceId(),
                  previous.percentageBasisPoints(),
                  false,
                  previous.createdAt(),
                  previous.updatedAt().isAfter(now) ? previous.updatedAt() : now);
          rules.deactivate(id, inactive.updatedAt());
          record(inactive, "REGRA_COMISSAO_INATIVADA", operationId, actor.id(), previous);
          return null;
        });
  }

  private void record(
      ServiceCommissionRule current,
      String action,
      UUID operationId,
      UUID actorId,
      ServiceCommissionRule previous) {
    String payload = json(current);
    outbox.enqueue(
        new PendingOperation(
            operationId,
            "regra_comissao_profissional_servico",
            current.id(),
            action,
            operationId.toString(),
            payload,
            1,
            outbox.expectedVersionForNext("regra_comissao_profissional_servico", current.id()),
            stationId,
            current.updatedAt(),
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            action,
            "regra_comissao_profissional_servico",
            current.id(),
            stationId,
            "{\"percentualAntes\":"
                + (previous == null ? "null" : previous.percentageBasisPoints())
                + ",\"percentualDepois\":"
                + current.percentageBasisPoints()
                + ",\"profissionalId\":\""
                + current.professionalId()
                + "\",\"servicoId\":\""
                + current.serviceId()
                + "\",\"origem\":\"PROFISSIONAL_SERVICO\""
                + ",\"ativoAntes\":"
                + (previous == null ? "null" : previous.active())
                + ",\"ativoDepois\":"
                + current.active()
                + "}",
            null,
            current.updatedAt()));
  }

  private String json(ServiceCommissionRule rule) {
    return "{\"id\":\""
        + rule.id()
        + "\",\"profissionalId\":\""
        + rule.professionalId()
        + "\",\"servicoId\":\""
        + rule.serviceId()
        + "\",\"percentualPontosBase\":"
        + rule.percentageBasisPoints()
        + ",\"ativo\":"
        + rule.active()
        + ",\"criadoEmUtc\":\""
        + rule.createdAt()
        + "\",\"atualizadoEmUtc\":\""
        + rule.updatedAt()
        + "\"}";
  }
}
