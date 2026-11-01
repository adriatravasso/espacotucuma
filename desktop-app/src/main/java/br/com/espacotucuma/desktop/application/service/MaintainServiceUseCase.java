package br.com.espacotucuma.desktop.application.service;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.service.ServiceRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class MaintainServiceUseCase {
  private final UnitOfWork unitOfWork;
  private final ServiceRepository services;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;

  public MaintainServiceUseCase(
      UnitOfWork unitOfWork,
      ServiceRepository services,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.services = Objects.requireNonNull(services, "services");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
  }

  public BeautyService register(
      String name, String category, int durationMinutes, Money price, int commissionBasisPoints) {
    var actor = authorization.requireUser(Permission.SERVICE_REGISTER);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    BeautyService service =
        new BeautyService(
            UUID.randomUUID(),
            name,
            category,
            durationMinutes,
            price,
            commissionBasisPoints,
            true,
            now,
            now);
    return unitOfWork.execute(
        () -> {
          services.insert(service);
          record(service, "SERVICO_CADASTRADO", operationId, now, actor.id(), null);
          return service;
        });
  }

  public BeautyService update(
      UUID id,
      String name,
      String category,
      int durationMinutes,
      Money price,
      int commissionBasisPoints) {
    var actor = authorization.requireUser(Permission.SERVICE_UPDATE);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          BeautyService previous = services.findById(id).orElseThrow(() -> missing(id));
          if (!previous.active()) throw new IllegalStateException("O serviço está inativo.");
          Instant updatedAt = previous.updatedAt().isAfter(now) ? previous.updatedAt() : now;
          BeautyService updated =
              new BeautyService(
                  id,
                  name,
                  category,
                  durationMinutes,
                  price,
                  commissionBasisPoints,
                  true,
                  previous.createdAt(),
                  updatedAt);
          services.update(updated);
          record(updated, "SERVICO_ATUALIZADO", operationId, updatedAt, actor.id(), previous);
          return updated;
        });
  }

  public void deactivate(UUID id) {
    var actor = authorization.requireUser(Permission.SERVICE_DEACTIVATE);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    unitOfWork.execute(
        () -> {
          BeautyService previous = services.findById(id).orElseThrow(() -> missing(id));
          if (!previous.active()) return null;
          Instant updatedAt = previous.updatedAt().isAfter(now) ? previous.updatedAt() : now;
          services.deactivate(id, updatedAt);
          BeautyService inactive =
              new BeautyService(
                  previous.id(),
                  previous.name(),
                  previous.category(),
                  previous.durationMinutes(),
                  previous.price(),
                  previous.commissionBasisPoints(),
                  false,
                  previous.createdAt(),
                  updatedAt);
          record(inactive, "SERVICO_INATIVADO", operationId, updatedAt, actor.id(), previous);
          return null;
        });
  }

  private void record(
      BeautyService current,
      String action,
      UUID operationId,
      Instant at,
      UUID actorId,
      BeautyService previous) {
    outbox.enqueue(
        new PendingOperation(
            operationId,
            "servico",
            current.id(),
            action,
            operationId.toString(),
            ServicePayload.json(current),
            1,
            outbox.expectedVersionForNext("servico", current.id()),
            stationId,
            at,
            0));
    String details =
        previous == null
            ? "{\"resumo\":\"Serviço cadastrado.\",\"precoCentavosDepois\":"
                + cents(current.price())
                + ",\"comissaoPontosBaseDepois\":"
                + current.commissionBasisPoints()
                + ",\"nomeDepois\":\""
                + ServicePayload.escape(current.name())
                + "\",\"categoriaDepois\":\""
                + ServicePayload.escape(current.category())
                + "\",\"duracaoMinutosDepois\":"
                + current.durationMinutes()
                + "}"
            : changeDetails(previous, current);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            action,
            "servico",
            current.id(),
            stationId,
            details,
            null,
            at));
  }

  private String changeDetails(BeautyService before, BeautyService after) {
    StringBuilder summary = new StringBuilder();
    if (!before.price().equals(after.price())) {
      append(summary, "Preço (R$): " + before.price().amount() + " → " + after.price().amount());
    }
    if (before.durationMinutes() != after.durationMinutes()) {
      append(
          summary,
          "Duração: " + before.durationMinutes() + " → " + after.durationMinutes() + " min");
    }
    if (before.commissionBasisPoints() != after.commissionBasisPoints()) {
      append(
          summary,
          "Comissão: "
              + percent(before.commissionBasisPoints())
              + " → "
              + percent(after.commissionBasisPoints()));
    }
    if (!before.category().equals(after.category())) append(summary, "Categoria alterada");
    if (!before.name().equals(after.name())) append(summary, "Nome alterado");
    if (!after.active()) append(summary, "Serviço inativado");
    return "{\"resumo\":\""
        + ServicePayload.escape(
            summary.isEmpty() ? "Nenhuma alteração de cadastro." : summary.toString())
        + "\",\"precoCentavosAntes\":"
        + cents(before.price())
        + ",\"precoCentavosDepois\":"
        + cents(after.price())
        + ",\"nomeAntes\":\""
        + ServicePayload.escape(before.name())
        + "\",\"nomeDepois\":\""
        + ServicePayload.escape(after.name())
        + "\",\"categoriaAntes\":\""
        + ServicePayload.escape(before.category())
        + "\",\"categoriaDepois\":\""
        + ServicePayload.escape(after.category())
        + "\",\"duracaoMinutosAntes\":"
        + before.durationMinutes()
        + ",\"duracaoMinutosDepois\":"
        + after.durationMinutes()
        + ",\"comissaoPontosBaseAntes\":"
        + before.commissionBasisPoints()
        + ",\"comissaoPontosBaseDepois\":"
        + after.commissionBasisPoints()
        + ",\"ativoAntes\":"
        + before.active()
        + ",\"ativoDepois\":"
        + after.active()
        + "}";
  }

  private long cents(Money money) {
    return money.amount().movePointRight(2).longValueExact();
  }

  private String percent(int basisPoints) {
    return java.math.BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + "%";
  }

  private void append(StringBuilder summary, String text) {
    if (!summary.isEmpty()) summary.append("; ");
    summary.append(text);
  }

  private IllegalArgumentException missing(UUID id) {
    return new IllegalArgumentException("Serviço não encontrado: " + id);
  }
}
