package br.com.espacotucuma.desktop.application.synchronization;

import java.util.UUID;

public record RemoteConflict(
    long sequence,
    UUID operationId,
    String entity,
    UUID entityId,
    String operation,
    String cause,
    String requiredAction,
    String state,
    String localPayloadJson,
    String remotePayloadJson,
    String decision,
    String decisionReason) {}
