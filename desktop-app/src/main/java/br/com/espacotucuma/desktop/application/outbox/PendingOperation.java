package br.com.espacotucuma.desktop.application.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingOperation(
    UUID id,
    String entity,
    UUID entityId,
    String type,
    String idempotencyKey,
    String payloadJson,
    int payloadVersion,
    long expectedVersion,
    UUID originStationId,
    Instant createdAt,
    int attempts) {
  public PendingOperation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(entity, "entity");
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(payloadJson, "payloadJson");
    if (payloadVersion < 1 || expectedVersion < 0 || attempts < 0) {
      throw new IllegalArgumentException("Metadados da operação pendente inválidos.");
    }
    Objects.requireNonNull(originStationId, "originStationId");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
