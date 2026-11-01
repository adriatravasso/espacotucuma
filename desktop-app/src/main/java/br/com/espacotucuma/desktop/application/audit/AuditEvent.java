package br.com.espacotucuma.desktop.application.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditEvent(
    UUID id,
    UUID userId,
    UUID operationId,
    String action,
    String entity,
    UUID entityId,
    UUID originStationId,
    String detailsJson,
    String reason,
    Instant occurredAt) {
  public AuditEvent {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(operationId, "operationId");
    action = requiredText(action, "action", 80);
    entity = requiredText(entity, "entity", 80);
    Objects.requireNonNull(entityId, "entityId");
    Objects.requireNonNull(originStationId, "originStationId");
    detailsJson = Objects.requireNonNull(detailsJson, "detailsJson").trim();
    if (detailsJson.length() < 2
        || detailsJson.length() > 2000
        || !detailsJson.startsWith("{")
        || !detailsJson.endsWith("}")) {
      throw new IllegalArgumentException(
          "Os detalhes da auditoria devem ser um objeto JSON curto.");
    }
    reason = reason == null ? null : reason.trim();
    if (reason != null && (reason.length() < 2 || reason.length() > 240)) {
      throw new IllegalArgumentException("O motivo deve ter de 2 a 240 caracteres.");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  private static String requiredText(String value, String field, int maxLength) {
    String normalized = Objects.requireNonNull(value, field).trim();
    if (normalized.length() < 2 || normalized.length() > maxLength) {
      throw new IllegalArgumentException(
          "O campo " + field + " deve ter de 2 a " + maxLength + " caracteres.");
    }
    return normalized;
  }
}
