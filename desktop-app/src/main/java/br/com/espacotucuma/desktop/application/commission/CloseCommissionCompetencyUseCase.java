package br.com.espacotucuma.desktop.application.commission;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.commission.CommissionStatementRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Materializes commission snapshots once and closes the month in the same SQLite transaction. */
public final class CloseCommissionCompetencyUseCase {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private final UnitOfWork unitOfWork;
  private final CommissionStatementRepository statements;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;
  private final Clock clock;

  public CloseCommissionCompetencyUseCase(
      UnitOfWork unitOfWork,
      CommissionStatementRepository statements,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this(
        unitOfWork,
        statements,
        outbox,
        authorization,
        audit,
        stationId,
        Clock.system(BUSINESS_ZONE));
  }

  public CloseCommissionCompetencyUseCase(
      UnitOfWork unitOfWork,
      CommissionStatementRepository statements,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork);
    this.statements = Objects.requireNonNull(statements);
    this.outbox = Objects.requireNonNull(outbox);
    this.authorization = Objects.requireNonNull(authorization);
    this.audit = Objects.requireNonNull(audit);
    this.stationId = Objects.requireNonNull(stationId);
    this.clock = Objects.requireNonNull(clock);
  }

  public List<CommissionStatementLine> close(YearMonth month) {
    var actor = authorization.requireUser(Permission.COMMISSION_SETTLE);
    Objects.requireNonNull(month, "month");
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    return unitOfWork.execute(
        () -> {
          if (statements.isClosed(month)) return statements.findStatement(month, null);
          List<CommissionCandidate> candidates = statements.findEligible(month);
          Map<UUID, Long> discounts = allocateDiscounts(candidates);
          statements.closeCompetency(month, actor.id(), now);
          List<CommissionStatementLine> result = new ArrayList<>();
          long totalCents = 0;
          for (CommissionCandidate candidate : candidates) {
            long discount = discounts.getOrDefault(candidate.serviceLineId(), 0L);
            CommissionStatementLine line =
                calculateLine(candidate, discount, UUID.randomUUID(), "PENDENTE");
            statements.insert(line, month, now);
            recordLine(line, month, actor.id(), now);
            result.add(line);
            totalCents = Math.addExact(totalCents, line.commissionCents());
          }
          recordClosure(month, result.size(), totalCents, actor.id(), now);
          return List.copyOf(result);
        });
  }

  public List<CommissionStatementLine> statement(YearMonth month, UUID professionalId) {
    Objects.requireNonNull(month, "month");
    UUID authorizedProfessional = null;
    if (!authorization.isAllowed(Permission.COMMISSION_RULE_MANAGE)) {
      authorizedProfessional = authorization.requireUser(Permission.OWN_COMMISSION_READ).id();
    }
    if (authorizedProfessional != null
        && professionalId != null
        && !authorizedProfessional.equals(professionalId))
      throw new br.com.espacotucuma.desktop.application.identity.AuthorizationException(
          "Profissionais podem consultar somente o próprio extrato.");
    UUID selectedProfessional =
        authorizedProfessional == null ? professionalId : authorizedProfessional;
    if (statements.isClosed(month)) return statements.findStatement(month, selectedProfessional);
    List<CommissionCandidate> candidates = statements.findEligible(month);
    Map<UUID, Long> discounts = allocateDiscounts(candidates);
    return candidates.stream()
        .filter(
            candidate ->
                selectedProfessional == null
                    || candidate.professionalId().equals(selectedProfessional))
        .map(
            candidate ->
                calculateLine(
                    candidate,
                    discounts.getOrDefault(candidate.serviceLineId(), 0L),
                    candidate.serviceLineId(),
                    "PRÉVIA"))
        .toList();
  }

  public boolean isClosed(YearMonth month) {
    return statements.isClosed(Objects.requireNonNull(month, "month"));
  }

  static Map<UUID, Long> allocateDiscounts(List<CommissionCandidate> candidates) {
    Map<UUID, List<CommissionCandidate>> groups = new HashMap<>();
    for (CommissionCandidate candidate : candidates)
      groups.computeIfAbsent(candidate.attendanceId(), ignored -> new ArrayList<>()).add(candidate);
    Map<UUID, Long> result = new HashMap<>();
    for (List<CommissionCandidate> lines : groups.values()) {
      lines.sort(Comparator.comparing(line -> line.serviceLineId().toString()));
      long total =
          lines.stream().mapToLong(CommissionCandidate::grossCents).reduce(0L, Math::addExact);
      long discount = lines.getFirst().attendanceDiscountCents();
      if (lines.stream().anyMatch(line -> line.attendanceDiscountCents() != discount)
          || discount > total)
        throw new IllegalStateException("O desconto do atendimento não concilia com os serviços.");
      long allocated = 0;
      List<DiscountRemainder> remainders = new ArrayList<>();
      for (CommissionCandidate line : lines) {
        BigInteger numerator =
            BigInteger.valueOf(discount).multiply(BigInteger.valueOf(line.grossCents()));
        BigInteger[] division =
            numerator.divideAndRemainder(BigInteger.valueOf(total == 0 ? 1 : total));
        long share = division[0].longValueExact();
        result.put(line.serviceLineId(), share);
        allocated = Math.addExact(allocated, share);
        remainders.add(new DiscountRemainder(line.serviceLineId(), division[1]));
      }
      long remaining = discount - allocated;
      remainders.sort(
          Comparator.comparing(DiscountRemainder::remainder)
              .reversed()
              .thenComparing(item -> item.serviceLineId().toString()));
      for (int index = 0; index < remaining; index++) {
        UUID lineId = remainders.get(index).serviceLineId();
        result.put(lineId, result.get(lineId) + 1);
      }
    }
    return Map.copyOf(result);
  }

  private CommissionStatementLine calculateLine(
      CommissionCandidate candidate, long discount, UUID commissionId, String status) {
    long gross = candidate.grossCents();
    long base = Math.subtractExact(gross, discount);
    Money commission =
        CommissionCalculator.calculate(
            money(base),
            Money.of("0.00"),
            new CommissionDecision(
                candidate.percentageBasisPoints(), CommissionDecision.Source.SERVICE, null));
    return new CommissionStatementLine(
        commissionId,
        candidate.serviceLineId(),
        candidate.attendanceId(),
        candidate.professionalId(),
        candidate.professionalName(),
        candidate.serviceName(),
        candidate.completedAt(),
        gross,
        discount,
        base,
        candidate.percentageBasisPoints(),
        cents(commission),
        status);
  }

  private void recordLine(
      CommissionStatementLine line, YearMonth month, UUID actorId, Instant now) {
    UUID operationId = UUID.randomUUID();
    String payload =
        "{\"id\":\""
            + line.commissionId()
            + "\",\"atendimentoServicoId\":\""
            + line.serviceLineId()
            + "\",\"atendimentoId\":\""
            + line.attendanceId()
            + "\",\"profissionalId\":\""
            + line.professionalId()
            + "\",\"profissionalNomeSnapshot\":\""
            + escape(line.professionalName())
            + "\",\"servicoNomeSnapshot\":\""
            + escape(line.serviceName())
            + "\",\"competencia\":\""
            + month
            + "\",\"baseCentavos\":"
            + line.commissionBaseCents()
            + ",\"percentualPontosBase\":"
            + line.percentageBasisPoints()
            + ",\"descontoRateadoCentavos\":"
            + line.discountCents()
            + ",\"valorCentavos\":"
            + line.commissionCents()
            + "}";
    outbox.enqueue(
        new PendingOperation(
            operationId,
            "comissao",
            line.commissionId(),
            "COMISSAO_APURADA",
            operationId.toString(),
            payload,
            1,
            outbox.expectedVersionForNext("comissao", line.commissionId()),
            stationId,
            now,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            "COMISSAO_APURADA",
            "comissao",
            line.commissionId(),
            stationId,
            payload,
            null,
            now));
  }

  private void recordClosure(
      YearMonth month, int lineCount, long totalCents, UUID actorId, Instant now) {
    UUID entityId =
        UUID.nameUUIDFromBytes(
            (stationId + ":" + month).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID operationId = UUID.randomUUID();
    String payload =
        "{\"competencia\":\""
            + month
            + "\",\"linhas\":"
            + lineCount
            + ",\"totalCentavos\":"
            + totalCents
            + ",\"fechadaEmUtc\":\""
            + now
            + "\"}";
    outbox.enqueue(
        new PendingOperation(
            operationId,
            "competencia_comissao",
            entityId,
            "COMPETENCIA_COMISSAO_FECHADA",
            operationId.toString(),
            payload,
            1,
            outbox.expectedVersionForNext("competencia_comissao", entityId),
            stationId,
            now,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actorId,
            operationId,
            "COMPETENCIA_COMISSAO_FECHADA",
            "competencia_comissao",
            entityId,
            stationId,
            payload,
            null,
            now));
  }

  private Money money(long cents) {
    return new Money(java.math.BigDecimal.valueOf(cents, 2));
  }

  private String escape(String text) {
    return text.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  private long cents(Money money) {
    return money.amount().movePointRight(2).longValueExact();
  }

  private record DiscountRemainder(UUID serviceLineId, BigInteger remainder) {}
}
