package br.com.espacotucuma.desktop.application.client;

import br.com.espacotucuma.desktop.application.audit.AuditEvent;
import br.com.espacotucuma.desktop.application.identity.AuthenticatedUser;
import br.com.espacotucuma.desktop.application.identity.AuthorizationService;
import br.com.espacotucuma.desktop.application.identity.Permission;
import br.com.espacotucuma.desktop.application.outbox.PendingOperation;
import br.com.espacotucuma.desktop.application.port.audit.AuditRepository;
import br.com.espacotucuma.desktop.application.port.client.ClientRepository;
import br.com.espacotucuma.desktop.application.port.outbox.OutboxRepository;
import br.com.espacotucuma.desktop.application.port.transaction.UnitOfWork;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Saves a client and its durable replication event as one local operation. */
public final class RegisterClientUseCase {

  public static final int PAYLOAD_VERSION = 1;
  private final UnitOfWork unitOfWork;
  private final ClientRepository clients;
  private final OutboxRepository outbox;
  private final UUID originStationId;
  private final AuthorizationService authorization;
  private final AuditRepository audit;

  public RegisterClientUseCase(
      UnitOfWork unitOfWork,
      ClientRepository clients,
      OutboxRepository outbox,
      UUID originStationId,
      AuthorizationService authorization,
      AuditRepository audit) {
    this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    this.clients = Objects.requireNonNull(clients, "clients");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.originStationId = Objects.requireNonNull(originStationId, "originStationId");
    this.authorization = Objects.requireNonNull(authorization, "authorization");
    this.audit = Objects.requireNonNull(audit, "audit");
  }

  public Client register(String name, String phone) {
    return register(name, phone, null, null);
  }

  public Client register(String name, String phone, String optionalContact, String preferences) {
    AuthenticatedUser actor = authorization.requireUser(Permission.CLIENT_REGISTER);
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    UUID operationId = UUID.randomUUID();
    return unitOfWork.execute(
        () -> {
          Client client =
              new Client(
                  UUID.randomUUID(),
                  clients.nextInternalCode(),
                  name,
                  phone,
                  optionalContact,
                  preferences,
                  true,
                  now,
                  now);
          clients.save(client);
          outbox.enqueue(
              operation(
                  client,
                  "CLIENTE_CRIADO",
                  operationId,
                  outbox.expectedVersionForNext("cliente", client.id()),
                  now));
          audit.record(
              new AuditEvent(
                  UUID.randomUUID(),
                  actor.id(),
                  operationId,
                  "CLIENTE_CRIADO",
                  "cliente",
                  client.id(),
                  originStationId,
                  "{}",
                  null,
                  now));
          return client;
        });
  }

  private PendingOperation operation(
      Client client, String action, UUID operationId, long expectedVersion, Instant now) {
    return new PendingOperation(
        operationId,
        "cliente",
        client.id(),
        action,
        operationId.toString(),
        ClientPayload.json(client),
        PAYLOAD_VERSION,
        expectedVersion,
        originStationId,
        now,
        0);
  }
}
