package br.com.espacotucuma.desktop.application.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Acknowledgement received from the remote service for a persisted operation. */
public record ServerConfirmation(UUID operationId, long remoteVersion, Instant confirmedAt) {
  public ServerConfirmation {
    Objects.requireNonNull(operationId, "operationId");
    if (remoteVersion < 1) {
      throw new IllegalArgumentException("A versão confirmada pelo servidor deve ser positiva.");
    }
    Objects.requireNonNull(confirmedAt, "confirmedAt");
  }
}
