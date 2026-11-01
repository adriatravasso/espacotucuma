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
    AuthenticatedUser actor = authorization.requireUser(Permission.CLIENT_REGISTER);
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Client client = new Client(UUID.randomUUID(), name, phone, now);
    UUID operationId = UUID.randomUUID();
    PendingOperation operation =
        new PendingOperation(
            operationId,
            "cliente",
            client.id(),
            "CLIENTE_CRIADO",
            operationId.toString(),
            payload(client),
            PAYLOAD_VERSION,
            0,
            originStationId,
            now,
            0);
    return unitOfWork.execute(
        () -> {
          clients.save(client);
          outbox.enqueue(operation);
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

  private String payload(Client client) {
    return "{\"id\":\""
        + client.id()
        + "\",\"nome\":\""
        + escape(client.name())
        + "\",\"telefone\":"
        + (client.phone() == null ? "null" : "\"" + escape(client.phone()) + "\"")
        + ",\"criadoEmUtc\":\""
        + client.createdAt()
        + "\"}";
  }

  private String escape(String value) {
    StringBuilder escaped = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format("\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }
}
