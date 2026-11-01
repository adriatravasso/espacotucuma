package br.com.espacotucuma.sync.contract;

import java.util.Map;
import java.util.UUID;

/** One client-owned operation; payload is a JSON object independent of server storage models. */
public record OperationSubmission(
    UUID operationId,
    String idempotencyKey,
    String entityType,
    UUID entityId,
    String operationType,
    long entityVersion,
    int payloadVersion,
    Map<String, Object> payload) {}
