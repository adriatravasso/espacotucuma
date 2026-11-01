package br.com.espacotucuma.desktop.application.finance;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.finance.CashTurnRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Opens, reconciles, and audits local operator cash turns. */
public final class ManageCashTurnUseCase {
  private static final String ENTITY = "caixa_turno";
  private final UnitOfWork unitOfWork;
  private final CashTurnRepository turns;
  private final OutboxRepository outbox;
  private final AuditRepository audit;
  private final AuthorizationService authorization;
  private final UUID stationId;
  private final Clock clock;

  public ManageCashTurnUseCase(
      UnitOfWork unitOfWork,
      CashTurnRepository turns,
      OutboxRepository outbox,
      AuditRepository audit,
      AuthorizationService authorization,
      UUID stationId) {
    this(
        unitOfWork,
        turns,
        outbox,
        audit,
        authorization,
        stationId,
        Clock.system(ZoneId.of("America/Manaus")));
  }

  public ManageCashTurnUseCase(
      UnitOfWork unitOfWork,
      CashTurnRepository turns,
      OutboxRepository outbox,
      AuditRepository audit,
      AuthorizationService authorization,
      UUID stationId,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.turns = Objects.requireNonNull(turns, "turns");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public CashTurn open(long openingCents) {
    var actor = authorization.requireUser(Permission.CASH_MANAGE);
    if (openingCents < 0)
      throw new IllegalArgumentException("O saldo inicial não pode ser negativo.");
    Instant now = now();
    return unitOfWork.execute(
        () -> {
          if (turns.findOpenTurn(actor.id()).isPresent()) {
            throw new IllegalStateException("Este operador já tem um caixa aberto.");
          }
          CashTurn turn =
              new CashTurn(UUID.randomUUID(), actor.id(), true, now, null, openingCents, null);
          turns.open(turn);
          record(
              turn.id(),
              "CAIXA_ABERTO",
              "{\"id\":\""
                  + turn.id()
                  + "\",\"operadorId\":\""
                  + actor.id()
                  + "\",\"abertoEmUtc\":\""
                  + now
                  + "\",\"saldoAberturaCentavos\":"
                  + openingCents
                  + "}",
              "{\"saldoAberturaCentavos\":" + openingCents + "}",
              null,
              actor.id(),
              now);
          return turn;
        });
  }

  public CashMovement recordMovement(CashMovementType type, long amountCents, String reason) {
    var actor = authorization.requireUser(Permission.CASH_MANAGE);
    Objects.requireNonNull(type, "type");
    if (type != CashMovementType.SANGRIA
        && type != CashMovementType.SUPRIMENTO
        && type != CashMovementType.SAIDA) {
      throw new IllegalArgumentException("Selecione sangria, suprimento ou despesa.");
    }
    if (amountCents <= 0) throw new IllegalArgumentException("Informe um valor maior que zero.");
    String normalizedReason = requiredReason(reason);
    Instant now = now();
    return unitOfWork.execute(
        () -> {
          CashTurn turn = requireOpen(actor.id());
          if (type == CashMovementType.SANGRIA || type == CashMovementType.SAIDA) {
            long cashAvailable = turns.expectedTotals(turn.id()).get(PaymentMethod.DINHEIRO);
            if (amountCents > cashAvailable) {
              throw new IllegalArgumentException("O valor excede o dinheiro disponível no caixa.");
            }
          }
          CashMovement movement =
              new CashMovement(
                  UUID.randomUUID(),
                  turn.id(),
                  null,
                  type,
                  amountCents,
                  normalizedReason,
                  actor.id(),
                  now);
          turns.recordMovement(movement);
          String payload = movementPayload(movement);
          recordMovement(
              movement.id(),
              "MOVIMENTO_CAIXA_" + type.name(),
              payload,
              "{\"caixaTurnoId\":\"" + turn.id() + "\",\"valorCentavos\":" + amountCents + "}",
              normalizedReason,
              actor.id(),
              now);
          return movement;
        });
  }

  public List<CashReconciliation> close(Map<PaymentMethod, Long> countedCents, String reason) {
    var actor = authorization.requireUser(Permission.CASH_MANAGE);
    Objects.requireNonNull(countedCents, "countedCents");
    String normalizedReason = normalizeReason(reason);
    Instant now = now();
    return unitOfWork.execute(
        () -> {
          CashTurn turn = requireOpen(actor.id());
          Map<PaymentMethod, Long> expected = turns.expectedTotals(turn.id());
          EnumMap<PaymentMethod, CashReconciliation> totals = new EnumMap<>(PaymentMethod.class);
          for (PaymentMethod method : PaymentMethod.values()) {
            long counted = countedCents.getOrDefault(method, 0L);
            if (counted < 0)
              throw new IllegalArgumentException("O valor conferido não pode ser negativo.");
            totals.put(method, new CashReconciliation(method, expected.get(method), counted));
          }
          boolean divergent =
              totals.values().stream().anyMatch(item -> item.differenceCents() != 0);
          if (divergent && normalizedReason == null) {
            throw new IllegalArgumentException(
                "Informe a justificativa para a divergência do fechamento.");
          }
          UUID closingId = UUID.randomUUID();
          turns.close(turn.id(), closingId, actor.id(), now, totals, normalizedReason);
          String details = totalsJson(totals);
          record(
              turn.id(),
              "CAIXA_FECHADO",
              "{\"id\":\""
                  + turn.id()
                  + "\",\"fechamentoId\":\""
                  + closingId
                  + "\",\"fechadoEmUtc\":\""
                  + now
                  + "\",\"conferencias\":"
                  + details
                  + "}",
              "{\"fechamentoId\":\"" + closingId + "\",\"conferencias\":" + details + "}",
              normalizedReason,
              actor.id(),
              now);
          return List.copyOf(totals.values());
        });
  }

  public void reopen(UUID turnId, String reason) {
    var actor = authorization.requireUser(Permission.FINANCIAL_REVERSAL);
    String normalizedReason = requiredReason(reason);
    Instant now = now();
    unitOfWork.execute(
        () -> {
          CashTurn turn =
              turns
                  .findById(turnId)
                  .orElseThrow(
                      () -> new IllegalArgumentException("Turno de caixa não encontrado."));
          if (turn.open()) throw new IllegalStateException("O caixa já está aberto.");
          if (turns.findOpenTurn(actor.id()).isPresent()) {
            throw new IllegalStateException("Feche o turno atual antes de reabrir outro.");
          }
          turns.reopen(turnId);
          record(
              turnId,
              "CAIXA_REABERTO",
              "{\"id\":\"" + turnId + "\",\"operadorId\":\"" + actor.id() + "\"}",
              "{\"reabertura\":true}",
              normalizedReason,
              actor.id(),
              now);
          return null;
        });
  }

  public Optional<CashTurn> currentTurn() {
    var actor = authorization.requireUser(Permission.CASH_MANAGE);
    return turns.findOpenTurn(actor.id()).or(() -> turns.findLatestTurn(actor.id()));
  }

  public List<CashMovement> movements(UUID turnId) {
    authorization.require(Permission.CASH_MANAGE);
    return turns.findMovements(Objects.requireNonNull(turnId, "turnId"));
  }

  public List<CashReconciliation> latestClosing(UUID turnId) {
    authorization.require(Permission.CASH_MANAGE);
    return turns.findLatestClosing(Objects.requireNonNull(turnId, "turnId"));
  }

  public Map<PaymentMethod, Long> expectedTotals(UUID turnId) {
    authorization.require(Permission.CASH_MANAGE);
    return turns.expectedTotals(Objects.requireNonNull(turnId, "turnId"));
  }

  private CashTurn requireOpen(UUID operatorId) {
    return turns
        .findOpenTurn(operatorId)
        .orElseThrow(
            () -> new IllegalStateException("Abra um turno de caixa antes de movimentar o caixa."));
  }

  private void record(
      UUID turnId,
      String action,
      String payload,
      String details,
      String reason,
      UUID actorId,
      Instant now) {
    recordEntity("caixa_turno", turnId, action, payload, details, reason, actorId, now);
  }

  private void recordMovement(
      UUID movementId,
      String action,
      String payload,
      String details,
      String reason,
      UUID actorId,
      Instant now) {
    recordEntity("movimento_caixa", movementId, action, payload, details, reason, actorId, now);
  }

  private void recordEntity(
      String entity,
      UUID entityId,
      String action,
      String payload,
      String details,
      String reason,
      UUID actorId,
      Instant now) {
    UUID operationId = UUID.randomUUID();
    outbox.enqueue(
        new PendingOperation(
            operationId,
            entity,
            entityId,
            action,
            operationId.toString(),
            payload,
            1,
            outbox.expectedVersionForNext(entity, entityId),
            stationId,
            now,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            action,
            entity,
            entityId,
            stationId,
            details,
            reason,
            now));
  }

  private String movementPayload(CashMovement movement) {
    return "{\"id\":\""
        + movement.id()
        + "\",\"caixaTurnoId\":\""
        + movement.turnId()
        + "\",\"tipo\":\""
        + movement.type()
        + "\",\"valorCentavos\":"
        + movement.amountCents()
        + ",\"motivo\":\""
        + escape(movement.reason())
        + "\"}";
  }

  private String totalsJson(Map<PaymentMethod, CashReconciliation> totals) {
    StringBuilder json = new StringBuilder("[");
    for (PaymentMethod method : PaymentMethod.values()) {
      CashReconciliation total = totals.get(method);
      if (json.length() > 1) json.append(',');
      json.append("{\"forma\":\"")
          .append(method)
          .append("\",\"esperadoCentavos\":")
          .append(total.expectedCents())
          .append(",\"conferidoCentavos\":")
          .append(total.countedCents())
          .append('}');
    }
    return json.append(']').toString();
  }

  private String escape(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  private String normalizeReason(String reason) {
    if (reason == null || reason.isBlank()) return null;
    return requiredReason(reason);
  }

  private String requiredReason(String reason) {
    String normalized = Objects.requireNonNull(reason, "reason").strip();
    if (normalized.length() < 2 || normalized.length() > 240) {
      throw new IllegalArgumentException("O motivo deve ter de 2 a 240 caracteres.");
    }
    return normalized;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS);
  }
}
