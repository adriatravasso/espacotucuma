package br.com.espacotucuma.desktop.application.client;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Edits or inactivates a client without removing its historical references. */
public final class MaintainClientUseCase {
  private final UnitOfWork unitOfWork;
  private final ClientRepository clients;
  private final OutboxRepository outbox;
  private final AuthorizationService authorization;
  private final AuditRepository audit;
  private final UUID originStationId;

  public MaintainClientUseCase(
      UnitOfWork unitOfWork,
      ClientRepository clients,
      OutboxRepository outbox,
      AuthorizationService authorization,
      AuditRepository audit,
      UUID originStationId) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.clients = Objects.requireNonNull(clients, "clients");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.originStationId = Objects.requireNonNull(originStationId, "originStationId");
  }

  public Client update(
      UUID id, String name, String phone, String optionalContact, String preferences) {
    var actor = authorization.requireUser(Permission.CLIENT_UPDATE);
    Instant operationTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          Client current = clients.findById(id).orElseThrow(() -> missing(id));
          if (!current.active()) throw new IllegalStateException("O cliente está inativo.");
          Instant now = later(current.updatedAt(), operationTime);
          Client updated =
              new Client(
                  current.id(),
                  current.internalCode(),
                  name,
                  phone,
                  optionalContact,
                  preferences,
                  true,
                  current.createdAt(),
                  now);
          clients.update(updated);
          recordMutation(
              updated,
              "CLIENTE_ATUALIZADO",
              operationId,
              now,
              actor.id(),
              changedFields(current, updated));
          return updated;
        });
  }

  public void deactivate(UUID id) {
    var actor = authorization.requireUser(Permission.CLIENT_DEACTIVATE);
    Instant operationTime = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    unitOfWork.execute(
        () -> {
          Client current = clients.findById(id).orElseThrow(() -> missing(id));
          if (!current.active()) return null;
          Instant now = later(current.updatedAt(), operationTime);
          clients.deactivate(id, now);
          Client inactive =
              new Client(
                  current.id(),
                  current.internalCode(),
                  current.name(),
                  current.phone(),
                  current.optionalContact(),
                  current.preferences(),
                  false,
                  current.createdAt(),
                  now);
          recordMutation(
              inactive, "CLIENTE_INATIVADO", operationId, now, actor.id(), "{\"ativo\":false}");
          return null;
        });
  }

  private void recordMutation(
      Client client, String action, UUID operationId, Instant now, UUID userId, String details) {
    long expectedVersion = outbox.expectedVersionForNext("cliente", client.id());
    outbox.enqueue(
        new PendingOperation(
            operationId,
            "cliente",
            client.id(),
            action,
            operationId.toString(),
            ClientPayload.json(client),
            RegisterClientUseCase.PAYLOAD_VERSION,
            expectedVersion,
            originStationId,
            now,
            0));
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            userId,
            operationId,
            action,
            "cliente",
            client.id(),
            originStationId,
            details,
            null,
            now));
  }

  private String changedFields(Client before, Client after) {
    java.util.List<String> changed = new java.util.ArrayList<>();
    if (!before.name().equals(after.name())) changed.add("nome");
    if (!Objects.equals(before.phone(), after.phone())) changed.add("telefone");
    if (!Objects.equals(before.optionalContact(), after.optionalContact()))
      changed.add("contatoOpcional");
    if (!Objects.equals(before.preferences(), after.preferences())) changed.add("preferencias");
    return "{\"campos\":[\"" + String.join("\",\"", changed) + "\"]}";
  }

  private Instant later(Instant existing, Instant operationTime) {
    return existing.isAfter(operationTime) ? existing : operationTime;
  }

  private IllegalArgumentException missing(UUID id) {
    return new IllegalArgumentException("Cliente não encontrado: " + id);
  }
}
