package br.com.espacotucuma.desktop.application.prepaid;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.prepaid.PrepaidPackageRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import br.com.espacotucuma.desktop.domain.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class MaintainPrepaidPackagesUseCase {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Manaus");
  private final UnitOfWork unitOfWork;
  private final PrepaidPackageRepository packages;
  private final ClientRepository clients;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID stationId;
  private final Clock clock;

  public MaintainPrepaidPackagesUseCase(
      UnitOfWork unitOfWork,
      PrepaidPackageRepository packages,
      ClientRepository clients,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId) {
    this(
        unitOfWork,
        packages,
        clients,
        outbox,
        authorization,
        audit,
        stationId,
        Clock.system(BUSINESS_ZONE));
  }

  public MaintainPrepaidPackagesUseCase(
      UnitOfWork unitOfWork,
      PrepaidPackageRepository packages,
      ClientRepository clients,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID stationId,
      Clock clock) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.packages = Objects.requireNonNull(packages, "packages");
    this.clients = Objects.requireNonNull(clients, "clients");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.stationId = Objects.requireNonNull(stationId, "stationId");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public PrepaidPackage create(String name, int sessions, int validityDays, Money price) {
    var actor = authorization.requireUser(Permission.PACKAGE_MANAGE);
    Instant now = now();
    UUID operationId = UUID.randomUUID();
    PrepaidPackage prepaidPackage =
        new PrepaidPackage(UUID.randomUUID(), name, sessions, validityDays, price, true, now, now);
    return unitOfWork.execute(
        () -> {
          packages.insertPackage(prepaidPackage);
          record(
              "pacote",
              prepaidPackage.id(),
              "PACOTE_CADASTRADO",
              operationId,
              json(prepaidPackage),
              actor.id(),
              "{\"nome\":\""
                  + escape(prepaidPackage.name())
                  + "\",\"sessoes\":"
                  + prepaidPackage.sessions()
                  + ",\"validadeDias\":"
                  + prepaidPackage.validityDays()
                  + ",\"precoCentavos\":"
                  + cents(prepaidPackage.price())
                  + "}",
              now);
          return prepaidPackage;
        });
  }

  public PrepaidPackage update(UUID id, String name, int sessions, int validityDays, Money price) {
    var actor = authorization.requireUser(Permission.PACKAGE_MANAGE);
    Instant now = now();
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          PrepaidPackage previous = packages.findPackage(id).orElseThrow(() -> missingPackage(id));
          if (!previous.active()) throw new IllegalStateException("O pacote está inativo.");
          Instant updatedAt = previous.updatedAt().isAfter(now) ? previous.updatedAt() : now;
          PrepaidPackage updated =
              new PrepaidPackage(
                  id, name, sessions, validityDays, price, true, previous.createdAt(), updatedAt);
          packages.updatePackage(updated);
          String details =
              "{\"nomeAntes\":\""
                  + escape(previous.name())
                  + "\",\"nomeDepois\":\""
                  + escape(updated.name())
                  + "\",\"sessoesAntes\":"
                  + previous.sessions()
                  + ",\"sessoesDepois\":"
                  + updated.sessions()
                  + ",\"validadeDiasAntes\":"
                  + previous.validityDays()
                  + ",\"validadeDiasDepois\":"
                  + updated.validityDays()
                  + ",\"precoCentavosAntes\":"
                  + cents(previous.price())
                  + ",\"precoCentavosDepois\":"
                  + cents(updated.price())
                  + "}";
          record(
              "pacote",
              updated.id(),
              "PACOTE_ATUALIZADO",
              operationId,
              json(updated),
              actor.id(),
              details,
              updatedAt);
          return updated;
        });
  }

  public void deactivate(UUID id) {
    var actor = authorization.requireUser(Permission.PACKAGE_MANAGE);
    Instant now = now();
    UUID operationId = UUID.randomUUID();
    unitOfWork.execute(
        () -> {
          PrepaidPackage current = packages.findPackage(id).orElseThrow(() -> missingPackage(id));
          if (!current.active()) return null;
          Instant updatedAt = current.updatedAt().isAfter(now) ? current.updatedAt() : now;
          packages.deactivatePackage(id, updatedAt);
          record(
              "pacote",
              id,
              "PACOTE_INATIVADO",
              operationId,
              "{\"id\":\"" + id + "\",\"ativo\":false}",
              actor.id(),
              "{\"ativoAntes\":true,\"ativoDepois\":false}",
              updatedAt);
          return null;
        });
  }

  public SessionBalance contract(UUID packageId, UUID clientId, PaymentForm paymentForm) {
    var actor = authorization.requireUser(Permission.PACKAGE_SELL);
    Objects.requireNonNull(paymentForm, "paymentForm");
    Instant now = now();
    LocalDate today = today();
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          PrepaidPackage prepaidPackage =
              packages.findPackage(packageId).orElseThrow(() -> missingPackage(packageId));
          if (!prepaidPackage.active()) {
            throw new IllegalStateException("Não é possível contratar um pacote inativo.");
          }
          var client =
              clients
                  .findById(clientId)
                  .orElseThrow(() -> new IllegalArgumentException("Cliente não encontrado."));
          if (!client.active())
            throw new IllegalStateException("Não é possível contratar para cliente inativo.");
          SessionBalance balance =
              new SessionBalance(
                  UUID.randomUUID(),
                  client.id(),
                  client.name(),
                  prepaidPackage.id(),
                  prepaidPackage.name(),
                  prepaidPackage.sessions(),
                  prepaidPackage.sessions(),
                  today.plusDays(prepaidPackage.validityDays()),
                  prepaidPackage.price(),
                  paymentForm,
                  BalanceState.ATIVO,
                  now,
                  actor.id());
          packages.contract(balance);
          record(
              "saldo_sessoes",
              balance.id(),
              "PACOTE_CONTRATADO",
              operationId,
              balanceJson(balance),
              actor.id(),
              "{\"clienteId\":\""
                  + client.id()
                  + "\",\"pacoteId\":\""
                  + prepaidPackage.id()
                  + "\",\"sessoesContratadas\":"
                  + balance.sessionsContracted()
                  + ",\"precoPagoCentavos\":"
                  + cents(balance.paidPrice())
                  + ",\"formaPagamento\":\""
                  + paymentForm.name()
                  + "\",\"validadeAte\":\""
                  + balance.expiresOn()
                  + "\"}",
              now);
          return balance;
        });
  }

  public void consume(UUID balanceId, UUID attendanceId) {
    var actor = authorization.requireUser(Permission.SESSION_CONSUME);
    Instant now = now();
    LocalDate today = today();
    UUID usageId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    unitOfWork.execute(
        () -> {
          SessionBalance balance =
              packages.findBalance(balanceId).orElseThrow(() -> missingBalance(balanceId));
          requireUsable(balance, today);
          AttendanceOption attendance =
              packages
                  .findAttendance(attendanceId)
                  .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado."));
          if (!balance.clientId().equals(attendance.clientId())) {
            throw new IllegalArgumentException(
                "O pacote e o atendimento pertencem a clientes diferentes.");
          }
          if (!"EM_ANDAMENTO".equals(attendance.state())) {
            throw new IllegalStateException(
                "A sessão só pode ser consumida em atendimento em andamento.");
          }
          packages.consume(balanceId, attendanceId, usageId, 1, actor.id(), now, today);
          record(
              "utilizacao_pacote",
              usageId,
              "SESSAO_PACOTE_CONSUMIDA",
              operationId,
              "{\"id\":\""
                  + usageId
                  + "\",\"saldoSessoesId\":\""
                  + balanceId
                  + "\",\"atendimentoId\":\""
                  + attendanceId
                  + "\",\"sessoesUtilizadas\":1,\"utilizadoEmUtc\":\""
                  + now
                  + "\"}",
              actor.id(),
              "{\"saldoSessoesId\":\""
                  + balanceId
                  + "\",\"atendimentoId\":\""
                  + attendanceId
                  + "\",\"sessoesUtilizadas\":1,\"clienteId\":\""
                  + balance.clientId()
                  + "\"}",
              now);
          return null;
        });
  }

  public void reverseUsage(UUID usageId, String reason) {
    var actor = authorization.requireUser(Permission.SESSION_REVERSE);
    String normalized = Objects.requireNonNull(reason, "reason").strip();
    if (normalized.length() < 2 || normalized.length() > 240) {
      throw new IllegalArgumentException("Informe o motivo da reversão (2 a 240 caracteres).");
    }
    Instant now = now();
    LocalDate today = today();
    UUID operationId = UUID.randomUUID();
    unitOfWork.execute(
        () -> {
          PackageUsage usage =
              packages
                  .historyForUsage(usageId)
                  .orElseThrow(
                      () -> new IllegalArgumentException("Utilização de pacote não encontrada."));
          if (usage.reversed()) throw new IllegalStateException("A utilização já foi revertida.");
          if (!"CANCELADO".equals(usage.attendanceState())) {
            throw new IllegalStateException(
                "Só é possível reverter pacote de atendimento cancelado.");
          }
          packages.reverse(usageId, normalized, actor.id(), now, today);
          UUID reversalId = UUID.randomUUID();
          record(
              "reversao_utilizacao_pacote",
              reversalId,
              "SESSAO_PACOTE_REVERTIDA",
              operationId,
              "{\"id\":\""
                  + reversalId
                  + "\",\"utilizacaoId\":\""
                  + usageId
                  + "\",\"motivo\":\""
                  + escape(normalized)
                  + "\"}",
              actor.id(),
              "{\"utilizacaoId\":\""
                  + usageId
                  + "\",\"motivo\":\""
                  + escape(normalized)
                  + "\",\"sessoesRepostas\":"
                  + usage.sessionsUsed()
                  + "}",
              now);
          return null;
        });
  }

  public br.com.espacotucuma.desktop.application.Page<PrepaidPackage> searchPackages(
      PackagePageQuery query) {
    authorization.require(Permission.PACKAGE_READ);
    return packages.searchPackages(query);
  }

  public br.com.espacotucuma.desktop.application.Page<SessionBalance> searchBalances(
      String clientSearch, int pageIndex, int pageSize) {
    authorization.require(Permission.PACKAGE_READ);
    return packages.searchBalances(clientSearch, pageIndex, pageSize);
  }

  public java.util.List<AttendanceOption> eligibleAttendances(UUID clientId) {
    authorization.require(Permission.PACKAGE_READ);
    return packages.eligibleAttendances(clientId);
  }

  public java.util.List<PackageUsage> history(UUID balanceId) {
    authorization.require(Permission.PACKAGE_READ);
    return packages.history(balanceId);
  }

  private void requireUsable(SessionBalance balance, LocalDate today) {
    if (balance.state() != BalanceState.ATIVO || balance.sessionsRemaining() < 1) {
      throw new IllegalStateException("O pacote não tem sessões disponíveis.");
    }
    if (balance.expiresOn().isBefore(today)) {
      throw new IllegalStateException("O pacote está vencido.");
    }
  }

  private void record(
      String entity,
      UUID entityId,
      String action,
      UUID operationId,
      String payload,
      UUID actorId,
      String details,
      Instant at) {
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
            at,
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
            null,
            at));
  }

  private String json(PrepaidPackage prepaidPackage) {
    return "{\"id\":\""
        + prepaidPackage.id()
        + "\",\"nome\":\""
        + escape(prepaidPackage.name())
        + "\",\"sessoes\":"
        + prepaidPackage.sessions()
        + ",\"validadeDias\":"
        + prepaidPackage.validityDays()
        + ",\"precoCentavos\":"
        + cents(prepaidPackage.price())
        + ",\"ativo\":"
        + prepaidPackage.active()
        + ",\"atualizadoEmUtc\":\""
        + prepaidPackage.updatedAt()
        + "\"}";
  }

  private String balanceJson(SessionBalance balance) {
    return "{\"id\":\""
        + balance.id()
        + "\",\"clienteId\":\""
        + balance.clientId()
        + "\",\"pacoteId\":\""
        + balance.packageId()
        + "\",\"nomePacoteSnapshot\":\""
        + escape(balance.packageName())
        + "\",\"sessoesContratadas\":"
        + balance.sessionsContracted()
        + ",\"sessoesRestantes\":"
        + balance.sessionsRemaining()
        + ",\"validadeAte\":\""
        + balance.expiresOn()
        + "\",\"precoPagoCentavos\":"
        + cents(balance.paidPrice())
        + ",\"formaPagamento\":\""
        + balance.paymentForm().name()
        + "\",\"adquiridoEmUtc\":\""
        + balance.purchasedAt()
        + "\"}";
  }

  private long cents(Money money) {
    return money.amount().movePointRight(2).longValueExact();
  }

  private String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.SECONDS);
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  private IllegalArgumentException missingPackage(UUID id) {
    return new IllegalArgumentException("Pacote não encontrado: " + id);
  }

  private IllegalArgumentException missingBalance(UUID id) {
    return new IllegalArgumentException("Saldo de sessões não encontrado: " + id);
  }
}
