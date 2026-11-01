package br.com.espacotucuma.sync.contract;

import java.util.UUID;

public record OperationResult(UUID operationId, Outcome outcome, SyncErrorCode errorCode) {
  public enum Outcome {
    ACCEPTED,
    REJECTED,
    CONFLICT
  }
}
